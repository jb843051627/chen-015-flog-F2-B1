package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.exception.BusinessException;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TFlogApplyCardMapper;
import com.fc.v2.mapper.auto.TFlogStandDocMapper;
import com.fc.v2.model.auto.TFlogApplyCard;
import com.fc.v2.model.auto.TFlogStandDoc;
import com.fc.v2.service.ITFlogApplyCardService;
import com.fc.v2.shiro.util.ShiroUtils;
import com.fc.v2.util.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 伐区采伐申请建档卡Service业务层处理
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TFlogApplyCardServiceImpl extends ServiceImpl<TFlogApplyCardMapper, TFlogApplyCard> implements ITFlogApplyCardService {

    private final TFlogStandDocMapper flogStandDocMapper;

    public TFlogApplyCardServiceImpl(TFlogStandDocMapper flogStandDocMapper) {
        this.flogStandDocMapper = flogStandDocMapper;
    }

    @Override
    public TFlogApplyCard selectTFlogApplyCardById(Long id) {
        if (id == null) {
            return null;
        }
        return this.baseMapper.selectOne(new QueryWrapper<TFlogApplyCard>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TFlogApplyCard> selectTFlogApplyCardList(Wrapper<TFlogApplyCard> queryWrapper) {
        QueryWrapper<TFlogApplyCard> wrapper = (queryWrapper instanceof QueryWrapper)
                ? (QueryWrapper<TFlogApplyCard>) queryWrapper
                : new QueryWrapper<TFlogApplyCard>();
        // 只摆现行卡；挂在已收回（status=1）底档名下的卡不再进台账
        wrapper.eq("del_flag", 0)
                .inSql("site_id", "select id from t_flog_stand_doc where status != 1 and del_flag = 0");
        // 同秒数据按 id 兜底，分页不抖、不漏、不重
        wrapper.orderByDesc("create_time").orderByDesc("id");
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTFlogApplyCard(TFlogApplyCard record) {
        if (record == null) {
            throw new BusinessException("建档卡内容不能为空");
        }
        // 审计字段只准框架填：表单绑定可携带任意 createBy/updateBy/delFlag，
        // 而 fillStrategy 只在字段为 null 时填登录人——非空值会顶掉真实经办人（历史同款 bug）。
        sanitizeAuditFields(record);
        // 数量先于一切：非法数量不得触发任何查询与留痕
        BigDecimal qty = normalizeQty(record.getQty(), null);
        record.setQty(qty);

        // 卡号归一 + 必录
        String key = dupKey(record.getBillNo());
        if (StringUtils.isEmpty(key)) {
            throw new BusinessException("伐区采伐申请建档卡号不能为空");
        }
        // 底档：必须存在、未删、在册；停用底档不得新建卡
        TFlogStandDoc arch = requireUsableArch(record.getSiteId());
        // 卡号必须挂在该底档字号下，禁止手填与底档无关的号
        requireBillBelongsToSite(key, arch);
        // 同底档范围内判重（新增不排除任何行）
        if (dupExists(record.getSiteId(), key, null)) {
            throw new BusinessException("伐区采伐申请建档卡号「" + record.getBillNo().trim() + "」在该编制单位底档下已存在，请勿重复建卡");
        }

        record.setBillNo(key);
        // 冗余字号一律以底档为准，入参带的旧值覆盖掉
        record.setSiteNo(arch.getSiteNo());
        applyGrade(record, qty, arch);
        record.setDelFlag(0);
        // 创建人/创建时间一律走 MetaObjectHandler 自动填充（当前登录人），
        // 严禁在业务里手动 setCreateBy —— 否则会被界面字段值顶掉，事后查不出经办人。
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTFlogApplyCard(TFlogApplyCard record) {
        if (record == null || record.getId() == null) {
            throw new BusinessException("缺少要修改的建档卡主键");
        }
        // 同新增：审计字段不接受表单入参，全部交框架填充（更新人）或不进 SET（创建人/创建时间）
        sanitizeAuditFields(record);
        TFlogApplyCard cur = selectTFlogApplyCardById(record.getId());
        if (cur == null) {
            throw new BusinessException("建档卡不存在或已删除");
        }

        // 本次生效的底档：提交了以提交为准，没提交沿用库中（在途单据只改备注不受牵连）
        boolean siteChanged = record.getSiteId() != null && !record.getSiteId().equals(cur.getSiteId());
        Integer siteId = record.getSiteId() != null ? record.getSiteId() : cur.getSiteId();
        TFlogStandDoc arch;
        if (siteChanged) {
            // 主动换挂底档：新底档必须在册；仍挂原底档（哪怕原底档已停用）不算新引用，不拦
            arch = requireUsableArch(siteId);
        } else {
            arch = refLoad(siteId);
        }

        // 卡号：提交了才归一/校验
        String curKey = dupKey(cur.getBillNo());
        if (StringUtils.isNotEmpty(record.getBillNo())) {
            String key = dupKey(record.getBillNo());
            boolean billChanged = !key.equals(curKey);
            // 改号或换挂时才强制「卡号挂底档字号」；存量老卡只改备注不因历史号型被卡
            if ((billChanged || siteChanged) && arch != null) {
                requireBillBelongsToSite(key, arch);
            }
            // 关键：排除自身，编辑未改号（归一后与库值相同）不再误报重复
            if (dupExists(siteId, key, record.getId())) {
                throw new BusinessException("伐区采伐申请建档卡号「" + record.getBillNo().trim() + "」在该编制单位底档下已存在");
            }
            record.setBillNo(key);
        }
        // 底档字号以底档为准，入参带的旧值一律覆盖
        if (arch != null) {
            record.setSiteNo(arch.getSiteNo());
        }
        if (record.getSiteId() == null) {
            record.setSiteId(siteId);
        }

        BigDecimal qty = normalizeQty(record.getQty(), cur.getQty());
        record.setQty(qty);
        applyGrade(record, qty, arch != null ? arch : refLoad(siteId));

        // 更新人/更新时间走 FieldFill.UPDATE 自动填充，与新增同一口径，不手写
        return this.baseMapper.update(record, new UpdateWrapper<TFlogApplyCard>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    @Override
    public int deleteTFlogApplyCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        if (idArr == null || idArr.length == 0) {
            return 0;
        }
        // 软删：与全表 del_flag 口径一致，删后卡号不参与判重、记录仍可追溯
        UpdateWrapper<TFlogApplyCard> uw = new UpdateWrapper<TFlogApplyCard>()
                .in("id", Arrays.asList(idArr))
                .eq("del_flag", 0)
                .set("del_flag", 1)
                .set("update_time", new java.util.Date());
        try {
            uw.set("update_by", ShiroUtils.getLoginName());
        } catch (RuntimeException ignore) {
            // 无登录上下文（定时清理）时留空，不阻断删除
        }
        return this.baseMapper.update(null, uw);
    }

    @Override
    public int deleteTFlogApplyCardById(Long id) {
        if (id == null) {
            return 0;
        }
        return deleteTFlogApplyCardByIds(String.valueOf(id));
    }

    @Override
    public int recalcTFlogApplyCardById(Long id) {
        TFlogApplyCard cur = selectTFlogApplyCardById(id);
        if (cur == null) {
            throw new BusinessException("建档卡不存在或已删除，无法重算");
        }
        // 重算只按底档现行阈值刷新档位；底档停用不是新引用，不拦（阈值仍在档案上）
        TFlogStandDoc arch = refLoad(cur.getSiteId());
        if (arch == null) {
            throw new BusinessException("挂靠的编制单位底档已不存在，无法重算，请先改挂在册底档");
        }
        int level = bandLevelOf(cur.getQty(),
                bandResolve(arch, 1), bandResolve(arch, 2), bandResolve(arch, 3), 1);
        cur.setGradeLevel(level);
        // 整行是库里加载出来的，旧 update_by 非空会让 fillStrategy 跳过填充；
        // 这里主动清空，强制把「谁点的重算」记成当前登录人，创建人/创建时间保持原值不动。
        cur.setUpdateBy(null);
        cur.setUpdateTime(null);
        // 走整行 updateById：结果真正 UPDATE 落库（不再是只刷界面临时值），
        // 并由 FieldFill.UPDATE 同步更新人/更新时间；刷新页面读到的就是新档。
        return this.baseMapper.updateById(cur);
    }

    @Override
    public List<TFlogStandDoc> listSiteOptions(Integer currentSiteId) {
        List<TFlogStandDoc> rows = this.flogStandDocMapper.selectList(
                new QueryWrapper<TFlogStandDoc>().eq("del_flag", 0).orderByAsc("id"));
        Map<Long, TFlogStandDoc> merged = new LinkedHashMap<Long, TFlogStandDoc>();
        for (TFlogStandDoc row : rows) {
            merged.put(row.getId(), row);
        }
        // 现挂底档若已停用/已删，补进来给编辑页回显，前端置灰不可新选
        if (currentSiteId != null && !merged.containsKey(currentSiteId.longValue())) {
            TFlogStandDoc cur = this.flogStandDocMapper.selectById(currentSiteId);
            if (cur != null) {
                merged.put(cur.getId(), cur);
            }
        }
        return new ArrayList<TFlogStandDoc>(merged.values());
    }

    @Override
    public int countBillNoDuplicate(Long excludeId, Integer siteId, String billNo) {
        if (siteId == null) {
            // 底档未选定先不报重，由提交校验兜底
            return 0;
        }
        String key = dupKey(billNo);
        if (StringUtils.isEmpty(key)) {
            return 0;
        }
        return dupExists(siteId, key, excludeId) ? 1 : 0;
    }

    /**
     * 存量批量搬入（判重链第 3 处）：与手工新增共用同一套校验口径，不另起炉灶。
     * 顺序：先整批校验（含批内同号），全部通过才统一落库；任一行不合规整批回滚，
     * 避免「先入库一半、另一半撞号」的半截数据。错误信息带清册行次，方便回改再传。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public int importCards(List<TFlogApplyCard> records) {
        if (records == null || records.isEmpty()) {
            throw new BusinessException("没有需要搬入的建档卡数据");
        }
        // 批内已见（归一卡号）：同一底档下同号互撞也要拦
        Set<String> batchSeen = new HashSet<String>();
        // 校验通过、待落库的行与其底档（底档只取一次，已在 requireUsableArch 里统一）
        List<TFlogApplyCard> ready = new ArrayList<TFlogApplyCard>();
        int rowNo = 0;
        for (TFlogApplyCard record : records) {
            rowNo++;
            if (record == null) {
                throw new BusinessException("第 " + rowNo + " 行数据为空");
            }
            sanitizeAuditFields(record);
            BigDecimal qty;
            try {
                qty = normalizeQty(record.getQty(), null);
            } catch (BusinessException e) {
                throw new BusinessException("第 " + rowNo + " 行：" + e.getMessage());
            }
            String key = dupKey(record.getBillNo());
            if (StringUtils.isEmpty(key)) {
                throw new BusinessException("第 " + rowNo + " 行：伐区采伐申请建档卡号不能为空");
            }
            TFlogStandDoc arch;
            try {
                arch = requireUsableArch(record.getSiteId());
                requireBillBelongsToSite(key, arch);
            } catch (BusinessException e) {
                throw new BusinessException("第 " + rowNo + " 行：" + e.getMessage());
            }
            String scopeKey = record.getSiteId() + "|" + key;
            if (!batchSeen.add(scopeKey)) {
                throw new BusinessException("第 " + rowNo + " 行：建档卡号「" + record.getBillNo().trim()
                        + "」在本批数据中重复");
            }
            if (dupExists(record.getSiteId(), key, null)) {
                throw new BusinessException("第 " + rowNo + " 行：建档卡号「" + record.getBillNo().trim()
                        + "」在该编制单位底档下已存在");
            }
            record.setQty(qty);
            record.setBillNo(key);
            record.setSiteNo(arch.getSiteNo());
            applyGrade(record, qty, arch);
            record.setDelFlag(0);
            ready.add(record);
        }
        int inserted = 0;
        for (TFlogApplyCard record : ready) {
            inserted += this.baseMapper.insert(record);
        }
        return inserted;
    }

    // ------------------------------------------------------------------
    // 档案联动链
    // ------------------------------------------------------------------

    /**
     * 审计字段消毒：SpringMVC 表单绑定不挑字段，请求里带 createBy/updateBy/delFlag
     * 也会进到实体；而 fillStrategy 只在字段为 null 时填登录人，非空就被客户端值顶掉。
     * 这里统一抹掉：创建信息只由 INSERT 填充产生，更新信息只由 UPDATE 填充产生，
     * delFlag 只许走删除接口。
     */
    private void sanitizeAuditFields(TFlogApplyCard record) {
        record.setCreateBy(null);
        record.setCreateTime(null);
        record.setUpdateBy(null);
        record.setUpdateTime(null);
        record.setDelFlag(null);
    }

    /**
     * 取档案（档案联动链第 1 处）：统一软删过滤与空值语义。
     * 为什么单独抽出来：insert / update / recalc 三处都要取档案，
     * 口径写两遍必然漂移（一处查软删、一处不查 → 已删档案能被引用）。
     */
    private TFlogStandDoc refLoad(Integer id) {
        if (id == null) {
            return null;
        }
        return this.flogStandDocMapper.selectOne(new QueryWrapper<TFlogStandDoc>()
                .eq("id", id).eq("del_flag", 0));
    }

    /**
     * 档案可用性判定（档案联动链第 2 处）：停用（1）、状态为空（存量脏数据按未启用保守处理）、
     * 已删、不存在 一律不可用于新建/换挂。
     */
    private boolean refUsable(TFlogStandDoc arch) {
        if (arch == null) {
            return false;
        }
        Integer st = arch.getStatus();
        return st != null && st.intValue() != 1;
    }

    /** 取在册可用底档，任一不满足都给明确拒绝原因 */
    private TFlogStandDoc requireUsableArch(Integer siteId) {
        if (siteId == null) {
            throw new BusinessException("请选择所属编制单位在册底档");
        }
        TFlogStandDoc arch = refLoad(siteId);
        if (arch == null) {
            throw new BusinessException("所选编制单位底档不存在或已删除");
        }
        if (!refUsable(arch)) {
            throw new BusinessException("编制单位「" + arch.getSiteName() + "」底档已停用（收回），不能新建或改挂建档卡");
        }
        return arch;
    }

    /**
     * 卡号挂钩底档（档案联动链第 3 处）：建档卡号必须以底档字号打头
     * （形如 DC00-2026-001），手填与底档无关的号一律不认。
     * 新增、编辑同一口径，避免“号是号、档是档”两张皮。
     */
    private void requireBillBelongsToSite(String normalizedKey, TFlogStandDoc arch) {
        String prefix = dupKey(arch.getSiteNo());
        if (StringUtils.isEmpty(prefix)) {
            // 底档自身没维护字号，无法挂钩，保守拒绝
            throw new BusinessException("底档「" + arch.getSiteName() + "」未维护底档字号，不能核发建档卡号");
        }
        boolean hit = normalizedKey.equals(prefix) || normalizedKey.startsWith(prefix + "-");
        if (!hit) {
            throw new BusinessException("建档卡号必须以所属底档字号「" + arch.getSiteNo() + "-」开头，不能手填无关卡号");
        }
    }

    // ------------------------------------------------------------------
    // 判重链
    // ------------------------------------------------------------------

    /**
     * 编号归一（判重链第 1 处）：去首尾空白、丢全角空格、全角转半角、统一大写。
     * 「ｊｉａ-001」「JIA-001 」「jia-001」在业务上是同一个号，不归一就漏判重。
     */
    private static String dupKey(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : raw.trim().toCharArray()) {
            if (ch == 12288) {
                continue;
            }
            if (ch > 65280 && ch < 65375) {
                ch = (char) (ch - 65248);
            }
            sb.append(Character.toUpperCase(ch));
        }
        return sb.toString();
    }

    /**
     * 判重查询（判重链第 2 处）：在**同一档案范围内**按归一编号找重。
     * excludeId 非空时排除自身（编辑场景）；del_flag=0 保证软删的不参与判重。
     * 全局判重会误拦（不同底档各自编号），必须按底档收敛。
     */
    private boolean dupExists(Integer siteId, String key, Long excludeId) {
        if (siteId == null || StringUtils.isEmpty(key)) {
            return false;
        }
        QueryWrapper<TFlogApplyCard> q = new QueryWrapper<TFlogApplyCard>();
        q.eq("del_flag", 0).eq("site_id", siteId).eq("bill_no", key);
        if (excludeId != null) {
            q.ne("id", excludeId);
        }
        Integer n = this.baseMapper.selectCount(q);
        return n != null && n > 0;
    }

    // ------------------------------------------------------------------
    // 计算链（新增 / 编辑 / 重算三处共用一套，口径不允许分叉）
    // ------------------------------------------------------------------

    /** 数量口径：空 → 用回退值（编辑沿用旧值）；仍为空或非正 → 拒绝。统一 2 位精度。 */
    private BigDecimal normalizeQty(BigDecimal given, BigDecimal fallback) {
        BigDecimal v = given != null ? given : fallback;
        if (v == null || v.signum() <= 0) {
            throw new BusinessException("本卡合并后拟采蓄积必须大于 0");
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** 算档并写回实体，新增/编辑共用；档位不合法区间时归零（脏档案/异常输入兜底）。 */
    private void applyGrade(TFlogApplyCard record, BigDecimal qty, TFlogStandDoc arch) {
        int level = bandLevelOf(qty,
                bandResolve(arch, 1), bandResolve(arch, 2), bandResolve(arch, 3), 1);
        record.setGradeLevel(level);
    }

    /**
     * 阈值解析（计算链第 1 处）：以档案为准；档案缺该档时回退到更粗一档的边界
     * （兼容存量：早期只维护了前两档的规则继续可用）；统一精度到 2 位，
     * 避免「档案 5.0 与明细 5.00」被判成不同边界。
     */
    private BigDecimal bandResolve(TFlogStandDoc arch, int tier) {
        if (arch == null) {
            return null;
        }
        BigDecimal v;
        switch (tier) {
            case 1:
                v = arch.getTh1Max();
                break;
            case 2:
                v = arch.getTh2Max();
                break;
            case 3:
                v = arch.getTh3Max();
                break;
            default:
                v = null;
                break;
        }
        if (v == null && tier > 1) {
            // 回退：该档未维护时用上一档边界兜底（存量规则常见）
            return bandResolve(arch, tier - 1);
        }
        if (v == null) {
            return null;
        }
        if (v.signum() < 0) {
            // 脏配置：负边界视为未维护（否则低档会吞掉全部输入）
            return null;
        }
        if (tier > 1) {
            BigDecimal prev = bandResolve(arch, tier - 1);
            if (prev != null && v.compareTo(prev) < 0) {
                // 档位边界必须单调不减；逆序的档案按上一档兜底，防越界误档
                return prev;
            }
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 档位归属（计算链第 2 处）：等于上限取高一档（含起不含止）；
     * 任一阈值缺失 → 该档及以下不判，直接落到最高档；值为空 → 返回 0（不硬算）。
     */
    private int bandLevelOf(BigDecimal val, BigDecimal t1, BigDecimal t2, BigDecimal t3, int base) {
        if (val == null) {
            return 0;
        }
        BigDecimal x = val.setScale(2, RoundingMode.HALF_UP);
        if (t1 != null && x.compareTo(t1) <= 0) {
            return base;
        }
        if (t1 != null && t2 != null && x.compareTo(t2) <= 0) {
            return base + 1;
        }
        if (t2 != null && t3 != null && x.compareTo(t3) <= 0) {
            return base + 2;
        }
        return base + 3;
    }
}

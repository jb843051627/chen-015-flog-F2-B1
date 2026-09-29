package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TFlogApplyCardMapper;
import com.fc.v2.mapper.auto.TFlogStandDocMapper;
import com.fc.v2.model.auto.TFlogApplyCard;
import com.fc.v2.model.auto.TFlogStandDoc;
import com.fc.v2.service.ITFlogApplyCardService;
import com.fc.v2.shiro.util.ShiroUtils;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 伐区采伐申请建档卡Service业务层处理
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TFlogApplyCardServiceImpl extends ServiceImpl<TFlogApplyCardMapper, TFlogApplyCard> implements ITFlogApplyCardService {

    /** 底档/单据正常状态 */
    private static final int DOC_ACTIVE = 0;
    /** 底档已收回（停用）/ 单据已删除 */
    private static final int DOC_REVOKED_OR_DELETED = 1;

    @Autowired
    private TFlogStandDocMapper flogStandDocMapper;

    @Override
    public TFlogApplyCard selectTFlogApplyCardById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TFlogApplyCard>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TFlogApplyCard> selectTFlogApplyCardList(Wrapper<TFlogApplyCard> queryWrapper) {
        QueryWrapper<TFlogApplyCard> wrapper = listNormalize(queryWrapper);
        wrapper.eq("del_flag", 0);
        // 只摆底档仍在册（status=0、未软删）的单据，与新增/编辑的底档口径一致
        listAliveFilter(wrapper, "site_id",
                "select id from t_flog_stand_doc where status = " + DOC_ACTIVE + " and del_flag = 0");
        listStableOrder(wrapper);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertTFlogApplyCard(TFlogApplyCard record) {
        if (record == null) {
            return 0;
        }

        // 1) 编号必填、归一；建档卡必须挂在编制单位在册底档名下
        String key = requireBillNo(record.getBillNo());
        record.setBillNo(key);
        TFlogStandDoc arch = requireUsableArch(record.getSiteId());

        // 2) 同一档案下编号唯一（与编辑同一条判重链，存量大小写/全角差异也认）
        TFlogApplyCard dup = findDup(record.getSiteId(), key, null);
        if (dup != null) {
            throw new IllegalArgumentException("伐区采伐申请建档卡号「" + key + "」在该编制单位底档下已存在，请勿重复录入");
        }

        // 3) 数量合法后再按底档阈值重算档位
        BigDecimal qty = requirePositiveQty(record.getQty());

        record.setSiteId(Integer.valueOf(arch.getId().intValue()));
        refSync(record, arch);
        record.setGradeLevel(bandLevelOf(qty,
                bandResolve(arch, 1), bandResolve(arch, 2), bandResolve(arch, 3), 1));
        bandSnapshot(record);
        record.setDelFlag(0);
        // 审计列一律服务端钉死：创建人取真正录单的当前登录人（不允许界面岗位名/编号冒充）；
        // 创建时间交给自动填充；更新人/更新时间新增时不得由请求带入。
        record.setCreateBy(ShiroUtils.getLoginName());
        record.setUpdateBy(null);
        record.setUpdateTime(null);
        return this.baseMapper.insert(record);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateTFlogApplyCard(TFlogApplyCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        TFlogApplyCard stored = this.baseMapper.selectOne(new QueryWrapper<TFlogApplyCard>()
                .eq("id", record.getId()).eq("del_flag", 0));
        if (stored == null) {
            throw new IllegalArgumentException("建档卡不存在或已删除，无法修改");
        }

        // 1) 编号：带了就归一并判重（必须排除自身），没带沿用原号
        String key;
        if (StringUtils.isNotEmpty(record.getBillNo())) {
            key = requireBillNo(record.getBillNo());
            record.setBillNo(key);
        } else {
            key = dupKey(stored.getBillNo());
            record.setBillNo(stored.getBillNo());
        }

        // 2) 底档：只有本次改挂新底档时才要求新底档在册可用；
        //    没换底档则沿用原底档——底档事后被收回，不影响在途单据改备注/改数量，
        //    且其历史阈值仍可用于重算（阈值是档案的历史配置，不因收回而消失）。
        boolean archChanged = record.getSiteId() != null
                && !record.getSiteId().equals(stored.getSiteId());
        Integer siteId = record.getSiteId() != null ? record.getSiteId() : stored.getSiteId();
        if (archChanged && !refUsable(refLoad(siteId))) {
            throw new IllegalArgumentException("所选编制单位在册底档不存在、已删除或已收回（停用），不能挂卡");
        }
        TFlogStandDoc arch = refLoad(siteId);

        TFlogApplyCard dup = findDup(siteId, key, record.getId());
        if (dup != null) {
            throw new IllegalArgumentException("伐区采伐申请建档卡号「" + key + "」在该编制单位底档下已存在，请勿重复录入");
        }

        // 3) 数量：没带沿用原值。存量卡可能历史缺数量，只改备注时不得被连带拦下；
        //    仅当显式提交了非正数量才拒绝。档位按最终底档重算，结果必须随本次更新落库。
        BigDecimal qty = record.getQty() != null ? record.getQty() : stored.getQty();
        record.setSiteId(siteId);
        if (arch != null) {
            // 档案行仍在（含建卡后被收回的情形）：冗余字号以档案为准，阈值照常重算
            refSync(record, arch);
            if (qty != null) {
                if (qty.signum() <= 0) {
                    throw new IllegalArgumentException("本卡合并后拟采蓄积必须大于0");
                }
                record.setQty(qty);
                record.setGradeLevel(bandLevelOf(qty,
                        bandResolve(arch, 1), bandResolve(arch, 2), bandResolve(arch, 3), 1));
            } else {
                // 本次没动数量（如只改备注）：档位不重算、也不接受请求塞值，沿用库存
                record.setGradeLevel(stored.getGradeLevel());
            }
        } else {
            // 底档行已物理缺失的存量脏数据：服务端控制列不接受请求塞值，沿用库存
            record.setSiteNo(stored.getSiteNo());
            record.setGradeLevel(stored.getGradeLevel());
            if (qty != null && qty.signum() <= 0) {
                throw new IllegalArgumentException("本卡合并后拟采蓄积必须大于0");
            }
            if (qty != null) {
                record.setQty(qty);
            }
        }
        bandSnapshot(record);

        // 创建人/创建时间为不可变审计列：编辑时即便请求夹带参数也不得覆盖，
        // 置空让 update 的动态 SET 跳过这两列（update(entity,wrapper) 会更新所有非空字段）。
        record.setCreateBy(null);
        record.setCreateTime(null);
        record.setDelFlag(null);
        record.setUpdateBy(ShiroUtils.getLoginName());
        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TFlogApplyCard>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    @Override
    public int deleteTFlogApplyCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        if (idArr.length == 0) {
            return 0;
        }
        // 与列表/详情/判重的 del_flag 口径统一：一律软删，物理行保留可追溯
        UpdateWrapper<TFlogApplyCard> uw = new UpdateWrapper<TFlogApplyCard>()
                .in("id", Arrays.asList(idArr))
                .eq("del_flag", 0)
                .set("del_flag", DOC_REVOKED_OR_DELETED)
                .set("update_by", ShiroUtils.getLoginName())
                .set("update_time", new Date());
        return this.baseMapper.update(null, uw);
    }

    @Override
    public int deleteTFlogApplyCardById(Long id) {
        if (id == null) {
            return 0;
        }
        UpdateWrapper<TFlogApplyCard> uw = new UpdateWrapper<TFlogApplyCard>()
                .eq("id", id)
                .eq("del_flag", 0)
                .set("del_flag", DOC_REVOKED_OR_DELETED)
                .set("update_by", ShiroUtils.getLoginName())
                .set("update_time", new Date());
        return this.baseMapper.update(null, uw);
    }

    /**
     * 取档案（档案联动链第 1 处）：统一软删过滤与空值语义。
     * 为什么单独抽出来：insert / update 两条路径都要取档案，
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
     * 取可用档案，不可用直接按业务口径拒绝（新增/换底档共用）。
     */
    private TFlogStandDoc requireUsableArch(Integer siteId) {
        TFlogStandDoc arch = refLoad(siteId);
        if (!refUsable(arch)) {
            throw new IllegalArgumentException("所属编制单位在册底档不存在、已删除或已收回（停用），不能建立伐区采伐申请建档卡");
        }
        return arch;
    }

    /**
     * 档案可用性判定（档案联动链第 2 处）：收回（停用）档案不得被新引用；
     * 状态为空的档案按「未启用」保守处理（存量脏数据常见）。
     */
    private boolean refUsable(TFlogStandDoc arch) {
        if (arch == null) {
            return false;
        }
        Integer st = arch.getStatus();
        if (st == null) {
            return false;
        }
        return st.intValue() == DOC_ACTIVE;
    }

    /**
     * 冗余字段同步（档案联动链第 3 处）：**冗余字段一律以档案为准**，
     * 调用方手填的旧值必须被档案值覆盖（底档字号不允许手工乱填）。
     */
    private void refSync(TFlogApplyCard record, TFlogStandDoc arch) {
        if (record == null || arch == null) {
            return;
        }
        record.setSiteNo(arch.getSiteNo());
    }

    /**
     * 编号归一（判重链第 1 处）：去首尾空白、全角转半角、统一大写；空编号按缺失拒绝。
     * 「ｊｉａ-001」「JIA-001 」「jia-001」在业务上是同一个号，不归一就会漏判重。
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
        String key = sb.toString();
        return key.isEmpty() ? null : key;
    }

    /** 编号必填校验：返回归一后的编号，供落库与判重共用。 */
    private static String requireBillNo(String raw) {
        String key = dupKey(raw);
        if (key == null) {
            throw new IllegalArgumentException("伐区采伐申请建档卡号不能为空");
        }
        return key;
    }

    /**
     * 判重查询（判重链第 2 处）：在**同一档案范围内**按归一编号找重，
     * 新增/编辑/存量导入共用这一条口径。
     * - excludeId 非空时排除自身（编辑场景，修掉「卡号没动却判自己重」）；
     * - del_flag=0，软删行腾出的编号允许复用；
     * - 在 Java 侧比对归一编号，存量行即使存的是小写/带空格/全角也照样认得；
     * - 全局判重会误拦（不同档案各自编号），必须按档案收敛。
     */
    private TFlogApplyCard findDup(Integer siteId, String key, Long excludeId) {
        if (siteId == null || key == null) {
            return null;
        }
        QueryWrapper<TFlogApplyCard> q = new QueryWrapper<TFlogApplyCard>()
                .eq("del_flag", 0)
                .eq("site_id", siteId);
        List<TFlogApplyCard> rows = this.baseMapper.selectList(q);
        for (TFlogApplyCard row : rows) {
            if (excludeId != null && excludeId.equals(row.getId())) {
                continue;
            }
            if (key.equals(dupKey(row.getBillNo()))) {
                return row;
            }
        }
        return null;
    }

    /** 数量口径：缺失或非正一律拒绝（新增/编辑同一处判定）。 */
    private static BigDecimal requirePositiveQty(BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalArgumentException("本卡合并后拟采蓄积必须大于0");
        }
        return qty;
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
     * 任一阈值缺失 → 该档及以下不判，直接落到最高档。
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

    /**
     * 变更留痕（计算链第 3 处）：档位必须落在合法区间（脏档案/异常输入不得把档位算到区间外）。
     */
    private void bandSnapshot(TFlogApplyCard record) {
        if (record == null || record.getGradeLevel() == null) {
            return;
        }
        int now = record.getGradeLevel();
        if (now < 0 || now > 99) {
            record.setGradeLevel(0);
        }
    }

    /**
     * 列表查询条件归一（列表链第 1 处）。
     * 口径：① 复用调用方传入的 QueryWrapper（保留其筛选条件）；
     *       ② 关键字类条件先 trim、空串不拼（空串拼进去会变成 `like ''`，命中全表）；
     *       ③ 编号/名称走模糊、状态走等值——口径写反会把筛选结果放大或缩小。
     */
    private QueryWrapper<TFlogApplyCard> listNormalize(Wrapper<TFlogApplyCard> queryWrapper) {
        QueryWrapper<TFlogApplyCard> wrapper = (queryWrapper instanceof QueryWrapper)
                ? (QueryWrapper<TFlogApplyCard>) queryWrapper
                : new QueryWrapper<TFlogApplyCard>();
        Object kw = wrapper.getParamNameValuePairs().get("kw");
        if (kw != null && String.valueOf(kw).trim().isEmpty()) {
            // 空关键字不参与筛选（否则 like '%%' 命中全表，看起来像「筛选失效」）
            wrapper.clear();
        }
        return wrapper;
    }

    /**
     * 排序稳定性（列表链第 3 处）：只按 create_time 排会在同秒数据上抖动，
     * 连录两遍、时间戳只差几秒的两行也能稳定区分先后；补 id 作为次序兜底。
     */
    private void listStableOrder(QueryWrapper<TFlogApplyCard> wrapper) {
        wrapper.orderByDesc("create_time").orderByDesc("id");
    }

    /**
     * 关联档案在用过滤（列表链第 4 处）：档案收回（停用）后，挂它名下的单据不应再出现在列表。
     * 用 inSql 子查询保持「一条 SQL」语义，避免额外查询吃掉分页令牌。
     */
    private void listAliveFilter(QueryWrapper<TFlogApplyCard> wrapper, String col, String subSql) {
        wrapper.inSql(col, subSql);
    }

}

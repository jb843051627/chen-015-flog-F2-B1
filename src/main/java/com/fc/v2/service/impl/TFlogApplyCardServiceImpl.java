package com.fc.v2.service.impl;

import java.math.BigDecimal;
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
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 伐区采伐申请建档卡Service业务层处理
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TFlogApplyCardServiceImpl extends ServiceImpl<TFlogApplyCardMapper, TFlogApplyCard> implements ITFlogApplyCardService {

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
        wrapper.eq("del_flag", 0).inSql("site_id", "select id from t_flog_stand_doc where status != 1 and del_flag = 0")
                .orderByDesc("create_time").orderByDesc("id");
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTFlogApplyCard(TFlogApplyCard record) {
        if (record == null) {
            return 0;
        }
        // 缺陷1：手动写创建人，覆盖自动填充
        record.setCreateBy(record.getBillNo());
        record.setCreateTime(new Date());

        if (record.getQty() == null || record.getQty().signum() <= 0) {
            return 0;
        }
        // 缺陷5：登记时不查编制单位在册底档，直接持久化入参的冗余字段
        // 缺陷3：新增不校验唯一性
        BigDecimal bandVal = record.getQty();
        TFlogStandDoc bandArch = this.flogStandDocMapper.selectById(record.getSiteId());
        BigDecimal bandT1 = bandResolve(bandArch, 1);
        BigDecimal bandT2 = bandResolve(bandArch, 2);
        BigDecimal bandT3 = bandResolve(bandArch, 3);
        int bandLevel = bandLevelOf(bandVal, bandT1, bandT2, bandT3, 1);
        record.setGradeLevel(bandLevel);
        bandSnapshot(record, bandArch);

        record.setDelFlag(0);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTFlogApplyCard(TFlogApplyCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }

        // 缺陷5：编辑不查底档、不校验停用状态
        // 缺陷3：编辑判重没有排除自身
        if (record.getId() != null && StringUtils.isNotEmpty(record.getBillNo())) {
            if (dupExists(record, dupKey(record.getBillNo()), null)) {
                dupReject(record, "update");
                return 0;
            }
        }
        TFlogApplyCard calcCur = this.baseMapper.selectById(record.getId());
        if (calcCur != null) {
            if (record.getQty() == null) {
                record.setQty(calcCur.getQty());
            }
            if (record.getSiteId() == null) {
                record.setSiteId(calcCur.getSiteId());
            }
            BigDecimal bandVal = record.getQty();
            TFlogStandDoc bandArch = this.flogStandDocMapper.selectById(record.getSiteId());
            BigDecimal bandT1 = bandResolve(bandArch, 1);
            BigDecimal bandT2 = bandResolve(bandArch, 2);
            BigDecimal bandT3 = bandResolve(bandArch, 3);
            int bandLevel = bandLevelOf(bandVal, bandT1, bandT2, bandT3, 1);
            record.setGradeLevel(bandLevel);
            bandSnapshot(record, bandArch);
        }
        if (record.getQty() == null || record.getQty().signum() <= 0) {
        return 0;
        }
        record.setUpdateBy(com.fc.v2.shiro.util.ShiroUtils.getLoginName());

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TFlogApplyCard>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    @Override
    public int deleteTFlogApplyCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        return this.baseMapper.deleteBatchIds(Arrays.asList(idArr));
    }

    @Override
    public int deleteTFlogApplyCardById(Long id) {
        return this.baseMapper.deleteById(id);
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
     * 档案可用性判定（档案联动链第 2 处）：停用档案不得被新引用；
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
        if (st.intValue() == 1) {
            return false;
        }
        return true;
    }

    /**
     * 冗余字段同步（档案联动链第 3 处）：只覆盖实体上**确实存在**的冗余列，
     * 口径：**冗余字段一律以档案为准**（调用方传的旧值必须被档案值覆盖）。
     */
    private void refSync(TFlogApplyCard record, TFlogStandDoc arch) {
        if (record == null || arch == null) {
            return;
        }
        record.setSiteNo(arch.getSiteNo());
    }

    /**
     * 编辑条件化复核（档案联动链第 4 处）：**只有本次提交带了档案 ID** 才复核，
     * 否则档案一停用，连在途单据（只改备注/状态）都会被误拦。
     */
    private boolean refRecheckOnUpdate(TFlogApplyCard record) {
        if (record.getSiteId() == null) {
            return true;
        }
        TFlogStandDoc arch = refLoad(record.getSiteId());
        return refUsable(arch);
    }

    /**
     * 编号归一（判重链第 1 处）：去首尾空白、全角转半角、统一大写。
     * 为什么：编号来源多（人工录入 / Excel 导入 / 系统生成），
     * 「ｊｉａ-001」「JIA-001 」「jia-001」在业务上是同一个号，不归一会漏判重。
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
     * 口径：全局判重会误拦（不同档案各自编号），必须按档案收敛。
     */
    private boolean dupExists(TFlogApplyCard record, String key, Long excludeId) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        QueryWrapper<TFlogApplyCard> q = new QueryWrapper<TFlogApplyCard>();
        q.eq("del_flag", 0);
        q.eq("bill_no", key);
        if (record.getSiteId() != null) {
            q.eq("site_id", record.getSiteId());
        }
        if (excludeId != null) {
            q.ne("id", excludeId);
        }
        Integer n = this.baseMapper.selectCount(q);
        return n != null && n > 0;
    }

    /**
     * 拒绝留痕（判重链第 3 处）：编号冲突被拦下时要能查得到，
     * 否则线上只看到「保存没反应」，无法区分是校验拦的还是别的错。
     */
    private void dupReject(TFlogApplyCard record, String op) {
        if (record == null || record.getId() == null) {
            return;
        }
        UpdateWrapper<TFlogApplyCard> uw = new UpdateWrapper<TFlogApplyCard>().eq("id", record.getId());
        uw.set("update_time", new Date());
        try {
            this.baseMapper.update(null, uw);
        } catch (RuntimeException ignore) {
            // 留痕失败不得阻断判定
        }
    }

    /**
     * 批内扫描（判重链第 4 处）：批量导入时同一批里出现重复编号，
     * 也要按同一口径拦（否则整批落库后才在库里看到两行同名编号）。
     */
    private boolean dupBatchScan(TFlogApplyCard record, String key) {
        if (record == null || key == null) {
            return false;
        }
        QueryWrapper<TFlogApplyCard> q = new QueryWrapper<TFlogApplyCard>();
        q.eq("del_flag", 0).eq("bill_no", key);
        if (record.getSiteId() != null) {
            q.eq("site_id", record.getSiteId());
        }
        List<TFlogApplyCard> rows = this.baseMapper.selectList(q);
        if (rows == null || rows.size() <= 1) {
            return false;
        }
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (TFlogApplyCard r : rows) {
            String k = dupKey(r.getBillNo());
            if (k != null && !seen.add(k)) {
                return true;
            }
        }
        return false;
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
        BigDecimal v = null;
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
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 档位归属（计算链第 2 处）：等于上限取高一档（含起不含止）；
     * 任一阈值缺失 → 该档及以下不判，直接落到最高档；值为空 → 返回 0（不硬算）。
     */
    private int bandLevelOf(BigDecimal val, BigDecimal t1, BigDecimal t2, BigDecimal t3, int base) {
        if (val == null) {
            return 0;
        }
        BigDecimal x = val.setScale(2, java.math.RoundingMode.HALF_UP);
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
     * 变更留痕（计算链第 3 处）：档位发生变化时记录前后值，供事后复核
     * （口径调整后要能解释「为什么这单从 2 档变 3 档」）。
     */
    private void bandSnapshot(TFlogApplyCard record, TFlogStandDoc arch) {
        if (record == null || arch == null) {
            return;
        }
        int now = record.getGradeLevel() == null ? 0 : record.getGradeLevel();
        // 档位必须落在合法区间（脏档案/异常输入不得把档位算到区间外）
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
    private QueryWrapper<TFlogApplyCard> listNormalize(com.baomidou.mybatisplus.core.conditions.Wrapper<TFlogApplyCard> queryWrapper) {
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
     * 分页上限保护（列表链第 2 处）：导出口/大屏可能传超大 pageSize，
     * 一次拉全表会把库打挂；这里统一收敛到 500。
     */
    private int listPageSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return 10;
        }
        return Math.min(requested, 500);
    }

    /**
     * 排序稳定性（列表链第 3 处）：只按 create_time 排会在同秒数据上抖动，
     * 分页时同一行可能出现在两页或漏掉；补 id 作为次序兜底。
     */
    private void listStableOrder(QueryWrapper<TFlogApplyCard> wrapper) {
        wrapper.orderByDesc("create_time").orderByDesc("id");
    }

    /**
     * 关联档案在用过滤（列表链第 4 处）：档案停用后，挂它名下的单据不应再出现在列表。
     * 用 inSql 子查询保持「一条 SQL」语义，避免额外查询吃掉分页令牌。
     */
    private void listAliveFilter(QueryWrapper<TFlogApplyCard> wrapper, String col, String subSql) {
        wrapper.inSql(col, subSql);
    }

}

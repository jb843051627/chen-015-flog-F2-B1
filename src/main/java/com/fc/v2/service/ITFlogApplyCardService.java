package com.fc.v2.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.model.auto.TFlogApplyCard;
import com.fc.v2.model.auto.TFlogStandDoc;

import java.util.List;

/**
 * 伐区采伐申请建档卡 Service接口
 *
 * @author fuce
 * @date 2026-09-12
 */
public interface ITFlogApplyCardService {

    /** 按主键查询 */
    TFlogApplyCard selectTFlogApplyCardById(Long id);

    /** 按条件查询列表（分页由调用方统一处理） */
    List<TFlogApplyCard> selectTFlogApplyCardList(Wrapper<TFlogApplyCard> queryWrapper);

    /** 新增 */
    int insertTFlogApplyCard(TFlogApplyCard record);

    /** 修改 */
    int updateTFlogApplyCard(TFlogApplyCard record);

    /** 批量删除 */
    int deleteTFlogApplyCardByIds(String ids);

    /** 按主键删除 */
    int deleteTFlogApplyCardById(Long id);

    /**
     * 档位重算并落库：按库中现行底档阈值重算取用顺位，直接 UPDATE 回库，
     * 不允许只刷界面临时值（刷新退回旧档）。
     */
    int recalcTFlogApplyCardById(Long id);

    /**
     * 建卡可选底档：只给在册（status=0）、未删的底档；
     * currentSiteId 非空时把该卡现挂底档一并带回（即便已停用，编辑页要能显出来）。
     */
    List<TFlogStandDoc> listSiteOptions(Integer currentSiteId);

    /**
     * 建档卡号同底档范围内判重。excludeId 非空时排除自身（编辑场景）。
     * 返回冲突条数，0 表示可用。
     */
    int countBillNoDuplicate(Long excludeId, Integer siteId, String billNo);

    /**
     * 存量数据批量搬入：逐行走与手工新增完全相同的校验（卡号归一/挂钩底档/
     * 底档在册/同底档判重/批内互重/数量为正）。任一行不合规整批不落库，
     * 错误信息带行次返回，保证「新规矩」与「老规矩」一个口径。
     *
     * @return 实际入库条数
     */
    int importCards(List<TFlogApplyCard> records);
}

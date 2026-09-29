package com.fc.v2.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.common.exception.BusinessException;
import com.fc.v2.mapper.auto.TFlogApplyCardMapper;
import com.fc.v2.mapper.auto.TFlogStandDocMapper;
import com.fc.v2.model.auto.TFlogApplyCard;
import com.fc.v2.model.auto.TFlogStandDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 伐区采伐申请建档卡口径单测：
 * 创建人、同底档判重、底档在册/卡号挂钩、重算落库、批量搬入。
 */
class TFlogApplyCardServiceImplTest {

    private TFlogApplyCardMapper cardMapper;
    private TFlogStandDocMapper standMapper;
    private TFlogApplyCardServiceImpl service;

    @BeforeEach
    void setUp() {
        cardMapper = mock(TFlogApplyCardMapper.class);
        standMapper = mock(TFlogStandDocMapper.class);
        service = new TFlogApplyCardServiceImpl(standMapper);
        // ServiceImpl.baseMapper 由 Spring 注入，单测手工塞
        ReflectionTestUtils.setField(service, "baseMapper", cardMapper);
        when(cardMapper.insert(any())).thenReturn(1);
        when(cardMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(cardMapper.updateById(any())).thenReturn(1);
    }

    private TFlogStandDoc stand(int id, String siteNo, Integer status) {
        TFlogStandDoc d = new TFlogStandDoc();
        d.setId((long) id);
        d.setSiteNo(siteNo);
        d.setSiteName("林场" + id);
        d.setStatus(status);
        d.setDelFlag(0);
        d.setTh1Max(new BigDecimal("40"));
        d.setTh2Max(new BigDecimal("120"));
        d.setTh3Max(new BigDecimal("300"));
        return d;
    }

    private TFlogApplyCard card(String billNo, Integer siteId, String qty) {
        TFlogApplyCard c = new TFlogApplyCard();
        c.setBillNo(billNo);
        c.setSiteId(siteId);
        c.setQty(new BigDecimal(qty));
        return c;
    }

    private void givenStand(TFlogStandDoc d) {
        when(standMapper.selectOne(any())).thenReturn(d);
    }

    // ---------- 创建人 ----------

    @Test
    @DisplayName("新增时创建人不再被卡号顶掉，保持由自动填充写入（业务层不动 createBy）")
    void insert_doesNotOverwriteCreateBy() {
        givenStand(stand(0, "DC00", 0));
        when(cardMapper.selectCount(any())).thenReturn(0);

        TFlogApplyCard c = card("DC00-2026-001", 0, "50");
        service.insertTFlogApplyCard(c);
        ArgumentCaptor<TFlogApplyCard> cap = ArgumentCaptor.forClass(TFlogApplyCard.class);
        verify(cardMapper).insert(cap.capture());
        assertNull(cap.getValue().getCreateBy(), "业务层不得手写创建人，应由 MetaObjectHandler 填登录名");
        assertEquals("DC00-2026-001", cap.getValue().getBillNo());
        assertEquals("DC00", cap.getValue().getSiteNo(), "字号以底档为准");
        assertEquals(2, cap.getValue().getGradeLevel(), "50 在 (40,120] 应为 2 档");
    }

    @Test
    @DisplayName("表单伪造 createBy/updateBy/delFlag 一律抹掉，不顶掉真实经办审计")
    void insert_formSuppliedAuditFieldsAreCleared() {
        givenStand(stand(0, "DC00", 0));
        when(cardMapper.selectCount(any())).thenReturn(0);

        TFlogApplyCard c = card("DC00-2026-009", 0, "50");
        c.setCreateBy("县林业局伐区建卡岗");
        c.setUpdateBy("fake");
        c.setDelFlag(1);
        service.insertTFlogApplyCard(c);

        ArgumentCaptor<TFlogApplyCard> cap = ArgumentCaptor.forClass(TFlogApplyCard.class);
        verify(cardMapper).insert(cap.capture());
        assertNull(cap.getValue().getCreateBy(), "伪造创建人必须被清掉，由填充写登录名");
        assertNull(cap.getValue().getUpdateBy());
        assertEquals(0, cap.getValue().getDelFlag().intValue(), "delFlag 必须由服务强制为 0");
    }

    // ---------- 判重 ----------

    @Test
    @DisplayName("同底档同号（全角/空白/大小写归一后）拒绝")
    void insert_duplicateNormalizedRejected() {
        givenStand(stand(0, "DC00", 0));
        when(cardMapper.selectCount(any())).thenReturn(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.insertTFlogApplyCard(card("ｄc00-2026-001 ", 0, "50")));
        assertTrue(ex.getMessage().contains("已存在"));
        verify(cardMapper, never()).insert(any());
    }

    @Test
    @DisplayName("编辑时卡号没动，排除自身后不报重复（旧逻辑误报的回归点）")
    void update_sameBillNoExcludesSelf() {
        TFlogApplyCard cur = card("DC00-2026-001", 0, "50");
        cur.setId(100L);
        when(cardMapper.selectOne(any())).thenReturn(cur);
        givenStand(stand(0, "DC00", 0));
        when(cardMapper.selectCount(any())).thenReturn(0);

        TFlogApplyCard form = card("DC00-2026-001", 0, "60"); // 号没变
        form.setId(100L);
        assertDoesNotThrow(() -> service.updateTFlogApplyCard(form));
        verify(cardMapper).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("编辑改成别人已占用的号仍拒绝")
    void update_changedToDuplicateRejected() {
        TFlogApplyCard cur = card("DC00-2026-001", 0, "50");
        cur.setId(100L);
        when(cardMapper.selectOne(any())).thenReturn(cur);
        givenStand(stand(0, "DC00", 0));
        when(cardMapper.selectCount(any())).thenReturn(1);

        TFlogApplyCard form = card("DC00-2026-002", 0, "60");
        form.setId(100L);
        assertThrows(BusinessException.class, () -> service.updateTFlogApplyCard(form));
        verify(cardMapper, never()).update(any(), any(Wrapper.class));
    }

    // ---------- 底档 ----------

    @Test
    @DisplayName("停用（status=1）底档不得新建卡")
    void insert_disabledStandRejected() {
        givenStand(stand(1, "DC01", 1));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.insertTFlogApplyCard(card("DC01-2026-001", 1, "50")));
        assertTrue(ex.getMessage().contains("停用"));
        verify(cardMapper, never()).insert(any());
    }

    @Test
    @DisplayName("底档不存在/已删拒绝")
    void insert_missingStandRejected() {
        when(standMapper.selectOne(any())).thenReturn(null);
        assertThrows(BusinessException.class,
                () -> service.insertTFlogApplyCard(card("DC99-1", 99, "50")));
    }

    @Test
    @DisplayName("卡号不以底档字号开头（手填无关号）拒绝")
    void insert_billNotBelongToSiteRejected() {
        givenStand(stand(0, "DC00", 0));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.insertTFlogApplyCard(card("XX99-2026-001", 0, "50")));
        assertTrue(ex.getMessage().contains("底档字号"));
        verify(cardMapper, never()).insert(any());
    }

    @Test
    @DisplayName("已挂停用底档的老卡只改备注不拦；只有主动改挂在册外底档才拦")
    void update_existingCardOnDisabledStand_remarkAllowed() {
        TFlogStandDoc disabled = stand(1, "DC01", 1);
        TFlogApplyCard cur = card("DC01-2026-001", 1, "50");
        cur.setId(100L);
        when(cardMapper.selectOne(any())).thenReturn(cur);
        when(standMapper.selectOne(any())).thenReturn(disabled);
        when(cardMapper.selectCount(any())).thenReturn(0);

        TFlogApplyCard form = new TFlogApplyCard();
        form.setId(100L);
        form.setSiteId(1);               // 仍挂原底档
        form.setBillNo("DC01-2026-001"); // 号没动
        form.setQty(new BigDecimal("50"));
        form.setRemark("只改备注");
        assertDoesNotThrow(() -> service.updateTFlogApplyCard(form));

        // 主动改挂停用底档 → 拒绝（cur2 在册 DC00，改挂到停用的 DC01）
        TFlogApplyCard cur2 = card("DC00-2026-001", 0, "50");
        cur2.setId(101L);
        when(cardMapper.selectOne(any())).thenReturn(cur2);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateTFlogApplyCard(moveWithId(101L, 1, "DC01-2026-002")));
        assertTrue(ex.getMessage().contains("停用"));
    }

    private TFlogApplyCard moveWithId(Long id, Integer siteId, String billNo) {
        TFlogApplyCard c = card(billNo, siteId, "50");
        c.setId(id);
        return c;
    }

    // ---------- 数量 ----------

    @Test
    @DisplayName("拟采蓄积为空/非正拒绝")
    void insert_badQtyRejected() {
        givenStand(stand(0, "DC00", 0));
        TFlogApplyCard c = card("DC00-1", 0, "0");
        assertThrows(BusinessException.class, () -> service.insertTFlogApplyCard(c));
    }

    // ---------- 重算落库 ----------

    @Test
    @DisplayName("重算按底档阈值算档并 updateById 真正落库")
    void recalc_persistsGrade() {
        TFlogApplyCard cur = card("DC00-2026-001", 0, "300"); // 等于 300 上限 → 3 档
        cur.setId(7L);
        cur.setGradeLevel(1);
        when(cardMapper.selectOne(any())).thenReturn(cur);
        givenStand(stand(0, "DC00", 0));

        int rows = service.recalcTFlogApplyCardById(7L);
        assertEquals(1, rows);
        ArgumentCaptor<TFlogApplyCard> cap = ArgumentCaptor.forClass(TFlogApplyCard.class);
        verify(cardMapper).updateById(cap.capture());
        assertEquals(3, cap.getValue().getGradeLevel(), "等于 300 上限取高一档（3档）");
    }

    @Test
    @DisplayName("重算不存在的卡报错，不静默成功")
    void recalc_missingRejected() {
        when(cardMapper.selectOne(any())).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.recalcTFlogApplyCardById(404L));
        verify(cardMapper, never()).updateById(any());
    }

    // ---------- 软删 ----------

    @Test
    @DisplayName("删除为软删：del_flag 置 1，记录仍在、卡号可复用")
    void remove_isSoftDelete() {
        when(cardMapper.update(any(), any(Wrapper.class))).thenReturn(2);
        int rows = service.deleteTFlogApplyCardByIds("1,2");
        assertEquals(2, rows);
        verify(cardMapper).update(isNull(), any(Wrapper.class));
    }

    // ---------- 批量搬入 ----------

    @Test
    @DisplayName("批量搬入：批内同号整批拒绝且一条都不落库")
    void import_batchDuplicateRejectsAll() {
        givenStand(stand(0, "DC00", 0));
        when(cardMapper.selectCount(any())).thenReturn(0);
        List<TFlogApplyCard> rows = new ArrayList<>(Arrays.asList(
                card("DC00-2026-001", 0, "10"),
                card("dc00-2026-001 ", 0, "20")));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.importCards(rows));
        assertTrue(ex.getMessage().contains("第 2 行"));
        verify(cardMapper, never()).insert(any());
    }

    @Test
    @DisplayName("批量搬入：库里已有同号拒绝")
    void import_dbDuplicateRejected() {
        givenStand(stand(0, "DC00", 0));
        when(cardMapper.selectCount(any())).thenReturn(1);
        List<TFlogApplyCard> rows = new ArrayList<>(Arrays.asList(
                card("DC00-2026-001", 0, "10")));
        assertThrows(BusinessException.class, () -> service.importCards(rows));
        verify(cardMapper, never()).insert(any());
    }

    @Test
    @DisplayName("批量搬入：停用底档拒绝（与手工新增一个口径）")
    void import_disabledStandRejected() {
        givenStand(stand(1, "DC01", 1));
        List<TFlogApplyCard> rows = new ArrayList<>(Arrays.asList(
                card("DC01-2026-001", 1, "10")));
        assertThrows(BusinessException.class, () -> service.importCards(rows));
        verify(cardMapper, never()).insert(any());
    }

    @Test
    @DisplayName("批量搬入：全部合规则逐条落库")
    void import_ok() {
        givenStand(stand(0, "DC00", 0));
        when(cardMapper.selectCount(any())).thenReturn(0);
        List<TFlogApplyCard> rows = new ArrayList<>(Arrays.asList(
                card("DC00-2026-001", 0, "10"),
                card("DC00-2026-002", 0, "200")));
        int n = service.importCards(rows);
        assertEquals(2, n);
        verify(cardMapper, times(2)).insert(any());
    }

    // ---------- 列表 ----------

    @Test
    @DisplayName("列表强制 del_flag=0、只留在册底档、按创建时间+id 稳定倒序")
    void list_filtersAndStableOrder() {
        when(cardMapper.selectList(any())).thenReturn(new ArrayList<>());
        service.selectTFlogApplyCardList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TFlogApplyCard>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(cardMapper).selectList(cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertTrue(sql.contains("del_flag ="), "必须带软删过滤");
        assertTrue(sql.contains("site_id IN"), "必须只留在册底档");
        assertTrue(sql.contains("ORDER BY create_time DESC"), "必须按创建时间倒序: " + sql);
        assertTrue(sql.contains("id DESC"), "同秒数据必须以 id 兜底稳定排序: " + sql);
    }
}

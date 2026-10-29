package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-26 权益快照兑现。
 *
 * <p>病:coop_order.perks_snapshot 列建了、resultMap 配了、insert 的 &lt;if&gt; 也写了 ——
 * 但 setPerksSnapshot **零调用方** ⇒ `<if test="perksSnapshot != null">` 永远不成立 ⇒
 * 库里该列恒 NULL。建表注释写的「同意瞬间冻结,后续结算只认合作单」在代码里**从未发生**:
 * 合作单建出来是没有权益快照的空壳。
 *
 * <p>叠加 CoopPerkMapper 无 update(改权益只能 delete-then-insert),商家事后重新申报会把旧记录
 * 整批软删 ⇒ 历史合作单一旦要对账,没有任何证据能证明当初谈的是什么。
 *
 * <p>⚠️ **本类初版是假绿**:6 条用例全直接调 {@code createCoopOrder},**一条都没驱动主路径**。
 * 而主路径 {@code acceptInviteWithLock}(商家点「接受邀约」)当时是裸 {@code insertCoopOrder},
 * 根本不经过 createCoopOrder ⇒ 线上主路径的 perks_snapshot 照样恒 NULL,本类却全绿。
 * 现补 {@link #acceptInvite_mainPath_freezesSnapshot} 等用例**从 acceptInviteWithLock 打进去**。
 *
 * ⚠️ 负控已实测:
 *  - 注释掉 createCoopOrder 里的 freezePerksSnapshot 调用 ⇒ 三条正控全红
 *  - 把 acceptInviteWithLock 的 createCoopOrder(order) 改回 coopOrderMapper.insertCoopOrder(order)
 *    ⇒ acceptInvite_mainPath_* 红(这正是初版漏掉的那条路)
 */
class CoopPerksSnapshotTest {

    private static final Long INVITE = 42L;
    private static final Long REG = 900L;
    private static final Long TOPIC = 300L;
    private static final Long GAME = 55L;

    private CoopInviteMapper originalInvites;
    private CoopInvite original;
    private CoopPerkMapper perkMapper;
    private CoopOrderMapper orderMapper;
    private CoopInviteServiceImpl service;

    @BeforeEach
    void setUp() {
        perkMapper = mock(CoopPerkMapper.class);
        orderMapper = mock(CoopOrderMapper.class);
        service = new CoopInviteServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "commercialReviewService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.ITopicCommercialReviewService.class));
        ReflectionTestUtils.setField(service, "coopPerkMapper", perkMapper);
        ReflectionTestUtils.setField(service, "coopOrderMapper", orderMapper);
        // A-13 幂等复用探针:默认无既有单
        when(orderMapper.selectCoopOrderByRegId(anyLong())).thenReturn(Collections.<CoopOrder>emptyList());
        originalInvites = mock(CoopInviteMapper.class);
        ReflectionTestUtils.setField(service, "coopInviteMapper", originalInvites);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        CmsTopic topic = new CmsTopic(); topic.setId(TOPIC); topic.setDelFlag(0);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        original = new CoopInvite(); original.setId(INVITE); original.setStatus(1); original.setDelFlag(0);
        original.setInviteType(0); original.setTopicId(TOPIC); original.setGameId(GAME);
        original.setFromId(11L); original.setFromType("talent"); original.setToId(22L); original.setToType("merchant");
        when(originalInvites.selectCoopInviteById(INVITE)).thenReturn(original);
        when(originalInvites.selectCoopInviteByIdForUpdate(INVITE)).thenReturn(original);
        when(originalInvites.selectOriginalCoopOrdersForUpdate(any(), any())).thenReturn(Collections.emptyList());
        com.chengyinhub.business.domain.CmsRegistrationMerchant registration = new com.chengyinhub.business.domain.CmsRegistrationMerchant();
        registration.setId(REG); registration.setTopicId(TOPIC); registration.setMemberId(22L);
        registration.setAuditStatus(1L); registration.setDelFlag(0);
        when(originalInvites.selectSupplyStationForUpdate(REG, TOPIC, 22L)).thenReturn(registration);
        org.mockito.Mockito.doAnswer(call -> { CoopOrder row = call.getArgument(0); row.setId(800L); return 1; })
                .when(orderMapper).insertCoopOrder(any());
    }

    private CoopPerk perk(String name, String cost) {
        CoopPerk p = new CoopPerk();
        p.setInviteId(INVITE);
        p.setName(name);
        p.setUnitCost(new BigDecimal(cost));
        p.setPerkType(0);
        return p;
    }

    private CoopOrder order() {
        CoopOrder o = new CoopOrder();
        o.setInviteId(INVITE); o.setTopicId(TOPIC); o.setGameId(GAME);
        o.setFromId(11L); o.setToId(22L); o.setToType("merchant");
        o.setRegId(REG);
        return o;
    }

    private CoopOrder inserted() {
        ArgumentCaptor<CoopOrder> captor = ArgumentCaptor.forClass(CoopOrder.class);
        verify(orderMapper).insertCoopOrder(captor.capture());
        return captor.getValue();
    }

    /** 核心正控:成单瞬间把已申报权益冻进快照。 */
    @Test
    void createOrder_freezesDeclaredPerks() {
        when(perkMapper.selectByInviteId(INVITE))
                .thenReturn(Arrays.asList(perk("手冲咖啡", "18.00"), perk("甜品券", "12.00")));

        service.createCoopOrder(order());

        String snap = inserted().getPerksSnapshot();
        assertNotNull(snap, "成单必须冻结权益快照,否则「结算只认合作单」是句空话");
        assertTrue(snap.contains("手冲咖啡") && snap.contains("甜品券"), "快照要含全部申报权益,实际: " + snap);
        assertTrue(snap.contains("18.00") || snap.contains("18"), "快照要含成本价(对账要用),实际: " + snap);
    }

    /** 无申报权益 ⇒ 快照留空,不写 "[]" 这种假证据。 */
    @Test
    void createOrder_noPerks_leavesSnapshotNull() {
        when(perkMapper.selectByInviteId(INVITE)).thenReturn(Collections.<CoopPerk>emptyList());

        service.createCoopOrder(order());

        assertNull(inserted().getPerksSnapshot(), "没申报权益就该是 NULL,别写个空数组冒充「冻结过」");
    }

    /** 调用方已自带快照 ⇒ 不覆盖(快照是冻结,不是刷新)。 */
    @Test
    void createOrder_existingSnapshot_notOverwritten() {
        CoopOrder o = order();
        o.setPerksSnapshot("[{\"name\":\"调用方给的\"}]");

        service.createCoopOrder(o);

        assertEquals("[{\"name\":\"调用方给的\"}]", inserted().getPerksSnapshot(),
                "快照一旦有值就不该被刷新 —— 冻结的意义就是不变");
    }

    /** 快照失败不阻断成单:它是审计证据,不是成单前置条件。但不能静默。 */
    @Test
    void createOrder_snapshotFailure_doesNotBlockOrder() {
        when(perkMapper.selectByInviteId(INVITE)).thenThrow(new RuntimeException("库炸了"));

        service.createCoopOrder(order());

        verify(orderMapper).insertCoopOrder(any());   // 单照建
        assertNull(inserted().getPerksSnapshot());
    }

    /** Retired unsafe bypass: an unbound direct order now fails before any insert or snapshot. */
    @Test
    void createOrder_noInviteId_skipsSnapshot() {
        CoopOrder o = new CoopOrder();
        o.setRegId(REG);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.createCoopOrder(o));
        verify(orderMapper, org.mockito.Mockito.never()).insertCoopOrder(any());
        assertNull(o.getPerksSnapshot());
    }

    /** A-13 幂等复用:已有生效单则直接返旧单,不重复建、也不该再冻一次。 */
    @Test
    void createOrder_reusesExistingOrder() {
        CoopOrder existing = new CoopOrder();
        existing.setId(7L); existing.setInviteId(INVITE); existing.setRegId(REG);
        existing.setTopicId(TOPIC); existing.setGameId(GAME); existing.setFromId(11L);
        existing.setToId(22L); existing.setToType("merchant");
        when(originalInvites.selectOriginalCoopOrdersForUpdate(INVITE, REG)).thenReturn(Collections.singletonList(existing));

        assertEquals(Long.valueOf(7L), service.createCoopOrder(order()));
        verify(orderMapper, org.mockito.Mockito.never()).insertCoopOrder(any());
    }

    // ------------------------------------------------------- 主路径(商家点「接受邀约」)

    /** 装配 acceptInviteWithLock 这条路需要的依赖:待处理的现役 type0 商家邀约。 */
    private void givenPendingInvite() {
        CoopInviteMapper inviteMapper = mock(CoopInviteMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        ReflectionTestUtils.setField(service, "coopInviteMapper", inviteMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        MmsMerchantMapper merchantMapper = mock(MmsMerchantMapper.class);
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", merchantMapper);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setMemberId(22L);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        when(merchantMapper.selectMmsMerchantByMemberIdForUpdate(22L)).thenReturn(merchant);

        CoopInvite inv = new CoopInvite();
        inv.setId(INVITE);
        inv.setStatus(0); inv.setDelFlag(0);                 // 待处理
        inv.setInviteType(0);             // H1:type2 已只读，快照主路改用现役 type0
        inv.setTopicId(TOPIC);
        inv.setGameId(GAME);              // gameLevel=true → 走「每游戏已接受=1」的容量复核
        inv.setFromId(11L);
        inv.setFromType("talent");
        inv.setToId(22L);
        inv.setToType("merchant");
        when(inviteMapper.selectCoopInviteByIdForUpdate(INVITE)).thenReturn(inv);
        // 接受前先无锁读定位 topic,再按「topic→invite」顺序加锁
        when(inviteMapper.selectCoopInviteById(INVITE)).thenReturn(inv);
        when(inviteMapper.selectOriginalCoopOrdersForUpdate(any(), any())).thenReturn(Collections.emptyList());
        org.mockito.Mockito.doAnswer(call -> { CoopInvite update = call.getArgument(0); inv.setStatus(update.getStatus()); return 1; })
                .when(inviteMapper).updateCoopInvite(any());
        when(inviteMapper.selectCoopInviteList(any())).thenReturn(Collections.<CoopInvite>emptyList());

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC); topic.setDelFlag(0);
        topic.setLifecycle(1);            // <3:未锁价,可接受
        topic.setRecruitDeadline(new Date(System.currentTimeMillis() + 60L * 60L * 1000L));
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
    }

    /**
     * **这条是本类的心脏**:主路径(商家点「接受邀约」)必须也冻结快照。
     *
     * <p>初版就漏在这:acceptInviteWithLock 自建 CoopOrder 后裸 insert,绕过 createCoopOrder
     * ⇒ 线上真正在跑的那条路 perks_snapshot 恒 NULL,而 6 条测试全绿 —— 因为它们都直接调
     * createCoopOrder,没有一条从这里打进去。
     */
    @Test
    void acceptInvite_mainPath_freezesSnapshot() {
        givenPendingInvite();
        when(perkMapper.selectByInviteId(INVITE))
                .thenReturn(Arrays.asList(perk("手冲咖啡", "18.00")));

        service.acceptInviteWithLock(INVITE, null);

        String snap = inserted().getPerksSnapshot();
        assertNotNull(snap, "主路径(接受邀约)也必须冻结快照 —— 它才是商家真正走的那条路");
        assertTrue(snap.contains("手冲咖啡"), "实际: " + snap);
    }

    /** 主路径建的单必须带 inviteId(否则 freezePerksSnapshot 无从取权益,会静默跳过)。 */
    @Test
    void acceptInvite_mainPath_orderCarriesInviteId() {
        givenPendingInvite();
        when(perkMapper.selectByInviteId(INVITE)).thenReturn(Collections.<CoopPerk>emptyList());

        service.acceptInviteWithLock(INVITE, null);

        assertEquals(INVITE, inserted().getInviteId());
    }

    /** 主路径无申报权益 ⇒ 快照留空,单照建(接受邀约不该因为没报权益就失败)。 */
    @Test
    void acceptInvite_mainPath_noPerks_stillCreatesOrder() {
        givenPendingInvite();
        when(perkMapper.selectByInviteId(INVITE)).thenReturn(Collections.<CoopPerk>emptyList());

        service.acceptInviteWithLock(INVITE, null);

        assertNull(inserted().getPerksSnapshot());
    }
}

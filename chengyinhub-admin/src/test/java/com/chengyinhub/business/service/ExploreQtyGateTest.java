package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.TopicSaleSnapshot;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.domain.dto.RegistrationTicketItem;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.support.TopicSaleCanonicalCodec;
import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ③ 探索票限购闸(决策 A)—— 行级 + 整单两道。
 *
 * <p><b>为什么必须有这道闸</b>:{@code reserveEntitlements} 的循环维度**只有 chapter,没有 qty**,
 * 且 {@code uk_entitlement(registration_id, chapter_id)} 从表结构上禁止同一单同一章建多行。
 * ⇒ 一行 ③ 票 qty=3:库存扣 3、payable 按 3 张算,但 entitlement 只 1 份/章
 * ⇒ **超卖 2 份权益**,且「补建 2 行」这条路直接撞 1062 走不通。
 * 一票 = 一人玩一趟一个主题,qty>1 在 ③ 上根本没有语义。
 *
 * <p><b>两道闸缺一不可</b>(与 #22 二清闸同型):
 * <ul>
 *   <li>行级(createRegistration):判每行自己的 qty;</li>
 *   <li>整单(createMultiTicketRegistrations):{@code tickets=[{9,1},{9,1}]} 时每行 qty 都是 1
 *       ⇒ 行行过闸 ⇒ 同一订单照样买到 2 张 ③ 票。「限购 1 张」是**整单**概念,不是行级概念。</li>
 * </ul>
 *
 * <p>⚠️ harness 选型:本类照 {@code ErqingGateTest} 建(它注入了全部 6 个 mock 且 stub 了
 * {@code isPlatformOwned})。**不要挂到 ExploreSellGateTest 上** —— 那里只注入了
 * topicMapper/activityMapper 两个 mock,verify {@code setOrderNum}/{@code deductInventory}
 * 的断言抓手根本跑不到;且 PR#81 的 {@code assertCollectible} 会把「qty=1 应放行」的用例打红。
 *
 * <p>⚠️ 负控已实测(注入→红→撤销→绿):
 * <ul>
 *   <li>删掉行级闸 ⇒ explore_qty2_rejected / multiTicket_exploreRow_qty2_rejected 红</li>
 *   <li>删掉整单闸 ⇒ multiTicket_explore_twoRowsQty1_rejected 红</li>
 *   <li>行级闸漏掉 derive 判断(写成裸 {@code if (qty > 1) throw})⇒ guided_qty3_accepted 红</li>
 * </ul>
 */
class ExploreQtyGateTest {

    private static final Long TOPIC = 700L;
    private static final Long ACTIVITY = 800L;
    private static final Long TICKET = 9L;
    private static final Long MEMBER = 1L;

    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;
    private OmsTicketMapper ticketMapper;
    private UmsMemberMapper memberMapper;
    private CmsRegistrationMapper registrationMapper;
    private IProductRoleService productRoleService;
    private TopicSaleSnapshotMapper snapshotMapper;
    private CmsRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        memberMapper = mock(UmsMemberMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        productRoleService = mock(IProductRoleService.class);
        snapshotMapper = mock(TopicSaleSnapshotMapper.class);

        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        ReflectionTestUtils.setField(service, "topicSaleSnapshotMapper", snapshotMapper);
        when(snapshotMapper.selectSnapshotByTopicId(TOPIC)).thenReturn(snapshot());
        when(registrationMapper.lockExploreTopicForUpdate(TOPIC)).thenReturn(TOPIC);
        // [P0-15] ③ qty=1 放行后会走到 reserveEntitlements;本类不测预占(归 ExploreReserveWiringTest),
        // 但不注入会 NPE(手工装配不走 Spring 容器)。
        ReflectionTestUtils.setField(service, "perkLedgerService",
                mock(IPerkLedgerService.class));

        // ③-自营 是首轮内测标的(D12);二清闸对自营放行,不干扰本类要测的 qty 闸
        when(productRoleService.isPlatformOwned(anyLong())).thenReturn(true);
        // 库存扣减成功(不 stub 则返 0 ⇒ 抛「库存不足,请重试」,把正控全打红)
        when(ticketMapper.deductInventory(eq(TICKET), anyLong())).thenReturn(1);
        givenActivity();
        givenTicket();
        givenMember();
    }

    /** productType=2 且 lifecycle=3(已锁价开售)⇒ 过 assertExploreSellable,闸才轮得到 qty */
    private void givenTopic(Integer productType) {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setName("测试主题");
        t.setProductType(productType);
        t.setLifecycle(3);
        t.setSellingStartedAt(new Date(System.currentTimeMillis() - 60_000L));
        t.setSaleEndAt(new Date(System.currentTimeMillis() + 86_400_000L));
        t.setStatus(1);
        t.setUserStatus(1);
        t.setDelFlag(0);
        t.setFeePlatformRate(new BigDecimal("0.1000"));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(t);
    }

    private static TopicSaleSnapshot snapshot() {
        return TopicSaleCanonicalTestFixture.snapshot(TOPIC);
    }

    private void givenActivity() {
        CmsActivity a = new CmsActivity();
        a.setId(ACTIVITY);
        a.setName("测试活动");
        a.setTopicId(TOPIC);
        a.setStatus(1L);
        a.setPublishStatus(1L);
        a.setDelFlag(0);
        a.setEndDate(new Date(System.currentTimeMillis() + 86400_000L));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(a);
    }

    private void givenTicket() {
        OmsTicket t = new OmsTicket();
        t.setId(TICKET);
        t.setName("测试票");
        t.setOwnerId(ACTIVITY);
        t.setOwnerType(2L);
        t.setPrice(new BigDecimal("100.00"));
        t.setDelFlag(0);
        t.setMode(1);
        t.setRemainingInventory(100L);
        t.setStartTime(new Date(System.currentTimeMillis() + 3600_000L));
        t.setEndTime(new Date(System.currentTimeMillis() + 86400_000L));
        when(ticketMapper.selectOmsTicketById(TICKET)).thenReturn(t);
    }

    private void givenMember() {
        UmsMember m = new UmsMember();
        m.setId(MEMBER);
        m.setPoint(0L);
        m.setStatus(1L);
        m.setDelFlag(0);
        when(memberMapper.selectUmsMemberById(MEMBER)).thenReturn(m);
    }

    /** 13 参直调 —— 9 参重载把 count 硬编码成 1,对新闸而言是「闸不触发」路径,证明不了任何事。 */
    private CmsRegistration create(int count) {
        return service.createRegistration(MEMBER, 2, ACTIVITY, TICKET, 0,
                "张三", "13800000000", null, null, count, null, null, null);
    }

    private RegistrationTicketItem item(int count) {
        RegistrationTicketItem it = new RegistrationTicketItem();
        it.setTicketId(TICKET);
        it.setCount(count);
        return it;
    }

    // ------------------------------------------------- 行级闸

    @Test
    void explore_qty2_rejected() {
        givenTopic(ProductType.FREE_EXPLORE);

        RuntimeException e = assertThrows(RuntimeException.class, () -> create(2));
        assertTrue(e.getMessage().contains("限购"), "实际:" + e.getMessage());
        // 闸在 computePrice 之前 ⇒ 不算价、不建单、不扣库存
        verify(registrationMapper, never()).insertCmsRegistration(any());
        verify(ticketMapper, never()).deductInventory(anyLong(), anyLong());
    }

    /** 闸没写成 qty >= 1 把 ③ 全打死。 */
    @Test
    void explore_qty1_accepted() {
        givenTopic(ProductType.FREE_EXPLORE);

        create(1);

        ArgumentCaptor<CmsRegistration> c = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrationMapper).insertCmsRegistration(c.capture());
        assertEquals(Long.valueOf(1L), c.getValue().getOrderNum());
        verify(ticketMapper).deductInventory(TICKET, 1L);
    }

    /**
     * ★ ① 不误伤的**唯一守卫**。
     * 闸若误写成裸 {@code if (qty > 1) throw}(漏掉 derive 判断),explore_qty2_rejected 与
     * explore_qty1_accepted 仍然全绿,只有这条会红。没有它,「① 不受影响」就是无证据的口头承诺。
     */
    @Test
    void guided_qty3_accepted() {
        givenTopic(ProductType.ORIENTEERING);   // ① —— 现网 14 个主题全是它

        create(3);

        ArgumentCaptor<CmsRegistration> c = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrationMapper).insertCmsRegistration(c.capture());
        assertEquals(Long.valueOf(3L), c.getValue().getOrderNum(), "① 多票必须继续可用");
        verify(ticketMapper).deductInventory(TICKET, 3L);
    }

    /**
     * productType 为 null(独立活动无主题真源 / 存量未回填)⇒ derive 兜底 GUIDED_TICKET ⇒ 放行。
     * 锁死与 :345 落库同口径 —— 生产实测 3 个主题的 product_type 就是 NULL。
     */
    @Test
    void nullProductType_qty3_accepted() {
        givenTopic(null);

        create(3);

        verify(ticketMapper).deductInventory(TICKET, 3L);
    }

    // ------------------------------------------------- 多票路径

    /** 行级闸经 :491 透传的 qty 覆盖多票路径;抛异常时 @Transactional 回滚,不留孤儿单。 */
    @Test
    void multiTicket_exploreRow_qty2_rejected() {
        givenTopic(ProductType.FREE_EXPLORE);

        assertThrows(RuntimeException.class, () -> service.createMultiTicketRegistrations(
                MEMBER, 2, ACTIVITY, 0, "张三", "13800000000", null, null,
                Collections.singletonList(item(2))));

        verify(registrationMapper, never()).insertCmsRegistration(any());
        verify(ticketMapper, never()).deductInventory(anyLong(), anyLong());
    }

    /**
     * ★ 整单闸的唯一守卫 —— 交接文档把这条列为「当前放行 = 已知缺口」,本次做掉了。
     *
     * <p>tickets=[{9,1},{9,1}]:每行 qty 都是 1 ⇒ 行级闸全放行 ⇒ 不设整单闸就会买到 2 张 ③ 票
     * ⇒ 2 份 entitlement 吃掉 2 份容量,而玩家只有一个人、只玩一趟。
     */
    @Test
    void multiTicket_explore_twoRowsQty1_rejected() {
        givenTopic(ProductType.FREE_EXPLORE);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createMultiTicketRegistrations(
                        MEMBER, 2, ACTIVITY, 0, "张三", "13800000000", null, null,
                        Arrays.asList(item(1), item(1))));
        assertTrue(e.getMessage().contains("限购"), "实际:" + e.getMessage());
        verify(registrationMapper, never()).insertCmsRegistration(any());
    }

    /** ① 的多票整单不受整单闸影响。 */
    @Test
    void multiTicket_guided_twoRowsQty1_accepted() {
        givenTopic(ProductType.ORIENTEERING);

        service.createMultiTicketRegistrations(
                MEMBER, 2, ACTIVITY, 0, "张三", "13800000000", null, null,
                Arrays.asList(item(1), item(1)));

        verify(registrationMapper, org.mockito.Mockito.times(2)).insertCmsRegistration(any());
    }

    /** ③ 单行 qty=1 的多票整单(购物车里就一张 ③)仍可下单。 */
    @Test
    void multiTicket_explore_singleRowQty1_accepted() {
        givenTopic(ProductType.FREE_EXPLORE);

        service.createMultiTicketRegistrations(
                MEMBER, 2, ACTIVITY, 0, "张三", "13800000000", null, null,
                Collections.singletonList(item(1)));

        verify(registrationMapper).insertCmsRegistration(any());
    }
}

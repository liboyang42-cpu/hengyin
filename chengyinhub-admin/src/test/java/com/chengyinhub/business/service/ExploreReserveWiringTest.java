package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.SettlementRule;
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
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-15 购票预占接线 —— {@code createRegistration} 是否真的走到了 {@code reserveEntitlements}。
 *
 * <p><b>本类守的是「闸在路径上」,不是「闸自己对不对」</b>(后者归 {@code PerkLedgerServiceTest})。
 * 这个区分是本仓的血教训:
 * <blockquote>「负控证明的是『删了闸会红』,不是『闸在路径上』。」
 * —— 同一道闸放错层放了两次,5 条负控全绿 + 源码断言全绿,洞照样在。</blockquote>
 * 所以本类的每条用例都**从生产入口(createRegistration 的 public 方法)进**,mock 掉最底层的 mapper,
 * 断言 perkLedgerService 真的被调到、且参数正确。源码 grep 是弱一档的保护,不能顶替这个。
 *
 * <p><b>接线前的生产真相</b>:{@code IPerkLedgerService} 9 个方法实现完备、单测齐全,
 * 但全仓零调用方(除接口与实现自身)⇒ 购票不预占容量、退款不释放,全部静默、零告警。
 *
 * <p>⚠️ 负控已实测(注入→红→撤销→绿):
 * <ul>
 *   <li>删掉 :446 那行接线 ⇒ explore_reservesEntitlements 红</li>
 *   <li>把 if 条件去掉改成无条件调 ⇒ guided_neverReserves 红</li>
 *   <li>topicId 改用 registration.getTopicId() ⇒ explore_reservesWithSellerTopicId 红
 *       (活动单的 registration.topicId 恒 null)</li>
 * </ul>
 */
class ExploreReserveWiringTest {

    private static final Long TOPIC = 700L;
    private static final Long ACTIVITY = 800L;
    private static final Long TICKET = 9L;
    private static final Long MEMBER = 1L;
    private static final Long REG_ID = 4242L;

    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;
    private OmsTicketMapper ticketMapper;
    private UmsMemberMapper memberMapper;
    private CmsRegistrationMapper registrationMapper;
    private IProductRoleService productRoleService;
    private IPerkLedgerService perkLedgerService;
    private ISettlementRuleService settlementRuleService;
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
        perkLedgerService = mock(IPerkLedgerService.class);
        settlementRuleService = mock(ISettlementRuleService.class);
        snapshotMapper = mock(TopicSaleSnapshotMapper.class);

        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        ReflectionTestUtils.setField(service, "perkLedgerService", perkLedgerService);
        ReflectionTestUtils.setField(service, "settlementRuleService", settlementRuleService);
        ReflectionTestUtils.setField(service, "topicSaleSnapshotMapper", snapshotMapper);
        when(snapshotMapper.selectSnapshotByTopicId(TOPIC)).thenReturn(snapshot());
        when(registrationMapper.lockExploreTopicForUpdate(TOPIC)).thenReturn(TOPIC);

        when(productRoleService.isPlatformOwned(anyLong())).thenReturn(true);
        SettlementRule settlementRule = new SettlementRule();
        settlementRule.setPlatformCommissionRate(new BigDecimal("0.1000"));
        when(settlementRuleService.getActiveRule(0L, "activity")).thenReturn(settlementRule);
        when(ticketMapper.deductInventory(eq(TICKET), anyLong())).thenReturn(1);
        // insert 后 registration.id 必须有值 —— reserveEntitlements 第一个参数就是它
        when(registrationMapper.insertCmsRegistration(any())).thenAnswer(a -> {
            ((com.chengyinhub.business.domain.CmsRegistration) a.getArgument(0)).setId(REG_ID);
            return 1;
        });
        givenActivity(TOPIC);
        givenTicket();
        givenMember();
    }

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

    private void givenActivity(Long topicId) {
        CmsActivity a = new CmsActivity();
        a.setId(ACTIVITY);
        a.setName("测试活动");
        a.setTopicId(topicId);
        a.setStatus(1L);
        a.setPublishStatus(1L);
        a.setDelFlag(0);
        a.setEndDate(new Date(System.currentTimeMillis() + 86400_000L));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(a);
    }

    private void givenTicket() {
        OmsTicket t = new OmsTicket();
        t.setId(TICKET);
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

    private void create() {
        service.createRegistration(MEMBER, 2, ACTIVITY, TICKET, 0,
                "张三", "13800000000", null, null, 1, null, null, null);
    }

    private RegistrationTicketItem item() {
        RegistrationTicketItem it = new RegistrationTicketItem();
        it.setTicketId(TICKET);
        it.setCount(1);
        return it;
    }

    // ------------------------------------------------- 15-1 正控

    /** ★ 接线存在性:③ 票从生产入口进,必须真的走到 reserveEntitlements。 */
    @Test
    void explore_reservesEntitlements() {
        givenTopic(ProductType.FREE_EXPLORE);

        create();

        verify(perkLedgerService).reserveEntitlements(REG_ID, TOPIC);
    }

    /**
     * ★ topicId 必须来自 sellerTopic,不是 registration.getTopicId()。
     * createRegistration 全程不 setTopicId(全类只有 createSelfPlayPass 设它)⇒ 活动单的
     * registration.topicId 恒 null ⇒ 用它会把 ③ 整条静默漏掉。这条用例就是钉死这个来源。
     */
    @Test
    void explore_reservesWithSellerTopicId() {
        givenTopic(ProductType.FREE_EXPLORE);

        create();

        // 第二个参数必须是主题真源 id,不能是 null
        verify(perkLedgerService).reserveEntitlements(anyLong(), eq(TOPIC));
        verify(perkLedgerService, never()).reserveEntitlements(anyLong(), eq((Long) null));
    }

    /** 扣减链顺序:扣库存 → 占权益。库存闸更便宜先跑,容量不足多数已被库存挡掉。 */
    @Test
    void reserve_runsAfterInventoryDeduction() {
        givenTopic(ProductType.FREE_EXPLORE);

        create();

        InOrder o = inOrder(registrationMapper, ticketMapper, perkLedgerService);
        o.verify(registrationMapper).insertCmsRegistration(any());   // 先有 registrationId
        o.verify(ticketMapper).deductInventory(eq(TICKET), anyLong());
        o.verify(perkLedgerService).reserveEntitlements(anyLong(), anyLong());
    }

    // ------------------------------------------------- 15-2 ①/② 不误伤

    /**
     * ★ ① 票绝不预占。
     * 把 if 条件去掉改成无条件调 ⇒ 只有这条红。
     *
     * <p>为什么必须显式收窄而不能靠「reserveEntitlements 对 ① 事实上 no-op」:
     * 那个 no-op 靠的是 requiredChapters(topicId) 恰好为空这个**数据巧合** ——
     * selectRequiredByTopic 只 where topic_id=? and required=1,**不看 product_type**。
     * 生产实测 34 个存量章节全部 required=1 ⇒ 一旦有人给 ① 主题配章节,
     * ① 的票会立刻静默开始占 ③ 的容量。
     */
    @Test
    void guided_neverReserves() {
        givenTopic(ProductType.ORIENTEERING);

        create();

        verify(perkLedgerService, never()).reserveEntitlements(anyLong(), anyLong());
    }

    /** productType 为 null(独立活动 / 存量未回填)⇒ derive 兜底 ① ⇒ 不预占。 */
    @Test
    void nullProductType_neverReserves() {
        givenTopic(null);

        create();

        verify(perkLedgerService, never()).reserveEntitlements(anyLong(), anyLong());
    }

    /**
     * 独立活动(activity.topicId == null)⇒ sellerTopic 为 null ⇒ 不预占。
     * 生产实测 12 笔付费单走这条路,拦了或误占都是打挂现网。
     */
    @Test
    void standaloneActivity_neverReserves() {
        givenActivity(null);

        create();

        verify(perkLedgerService, never()).reserveEntitlements(anyLong(), anyLong());
    }

    // ------------------------------------------------- 15-3 回滚

    /**
     * ★ 容量不足时整单失败 —— 异常必须**穿透出去**,不能被吞。
     * 吞掉 = 建了单、扣了库存、却没占权益 = 超卖(且零告警)。
     * 真正的回滚由 @Transactional 保证(单测证明不了事务,但能证明异常没被吞)。
     */
    @Test
    @Timeout(10)
    void reserveThrows_propagatesAndDoesNotSwallow() {
        givenTopic(ProductType.FREE_EXPLORE);
        doThrow(new RuntimeException("该路线权益名额不足,暂时无法购买"))
                .when(perkLedgerService).reserveEntitlements(anyLong(), anyLong());

        RuntimeException e = assertThrows(RuntimeException.class, this::create);
        assertTrue(e.getMessage().contains("名额不足"), "实际:" + e.getMessage());
    }

    // ------------------------------------------------- 多票

    /**
     * 多票路径自动继承::491 循环调 createRegistration ⇒ 每行各得一整套 entitlement。
     * ⚠️ 不要照抄二清闸的「整单层再判一次」—— 那个理由(payable 是整单概念)不适用于 entitlement:
     * 容量按行算不按钱算,整单再调一次 = 重复预占。本例就是钉住「恰好 N 次,不是 N+1 次」。
     *
     * <p>注:③ 已被整单限购闸压到最多 1 行,故这里用 ① 验证「循环继承」这个机制本身,
     * 再用下面那条验证 ③ 单行确实预占一次。
     */
    @Test
    void multiTicket_guided_neverReserves() {
        givenTopic(ProductType.ORIENTEERING);

        service.createMultiTicketRegistrations(MEMBER, 2, ACTIVITY, 0,
                "张三", "13800000000", null, null, Arrays.asList(item(), item()));

        verify(perkLedgerService, never()).reserveEntitlements(anyLong(), anyLong());
    }

    /** ③ 单行多票路径:恰好预占一次,不是两次(整单层没有重复调)。 */
    @Test
    void multiTicket_exploreSingleRow_reservesExactlyOnce() {
        givenTopic(ProductType.FREE_EXPLORE);

        service.createMultiTicketRegistrations(MEMBER, 2, ACTIVITY, 0,
                "张三", "13800000000", null, null, java.util.Collections.singletonList(item()));

        verify(perkLedgerService, times(1)).reserveEntitlements(REG_ID, TOPIC);
    }
}

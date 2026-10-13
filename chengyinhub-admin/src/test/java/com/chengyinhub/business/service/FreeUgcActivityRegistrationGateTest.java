package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F03:免费 UGC 城市定向「审核上架」与「允许报名」必须同一口径。
 *
 * <p>纯 UGC 主题不走商业生命周期(TopicCommercialReviewServiceImpl:纯 UGC 不建审核任务),
 * 仓内也没有任何用户路径把它推到 SELLING;报名却对所有挂主题的活动调合同二售卖闸 ⇒ 永远被拒。
 * 行为测试把 createRegistration / quoteRegistration 真的调进去:下单用不存在的用户 1L,过闸的证据是
 * 走到下一步 validateAndGetMember 抛「用户不存在」;报价在票闸之前就校验用户,所以用真实用户 2L。
 */
class FreeUgcActivityRegistrationGateTest {

    private static final Long TOPIC = 990049L;
    private static final Long ACTIVITY = 10L;
    private static final Long TICKET = 9L;

    private CmsTopicMapper topicMapper;
    private OmsTicketMapper ticketMapper;
    private CommercialTopicPolicy commercialTopicPolicy;
    private CmsRegistrationServiceImpl service;
    private OmsTicket ticket;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        commercialTopicPolicy = mock(CommercialTopicPolicy.class);
        when(commercialTopicPolicy.isFreeTopic(any())).thenCallRealMethod();
        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        // 下单用 1L(无此用户 ⇒ 过闸后第一步抛「用户不存在」);报价在票闸之前就校验用户,所以用真实用户 2L。
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        UmsMember quoter = new UmsMember();
        quoter.setId(2L);
        quoter.setStatus(1L);
        quoter.setDelFlag(0);
        quoter.setPoint(0L);
        when(memberMapper.selectUmsMemberById(2L)).thenReturn(quoter);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        // 修复前 service 没有这个依赖;按字段存在与否注入,保证 RED 是业务断言红而不是反射报错。
        if (ReflectionUtils.findField(CmsRegistrationServiceImpl.class, "commercialTopicPolicy") != null) {
            ReflectionTestUtils.setField(service, "commercialTopicPolicy", commercialTopicPolicy);
        }

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setName("自办派生活动");
        activity.setTopicId(TOPIC);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        activity.setEndDate(new Date(System.currentTimeMillis() + 86400_000L));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        ticket = ticket(TICKET, BigDecimal.ZERO);
        when(ticketMapper.selectOmsTicketById(TICKET)).thenReturn(ticket);
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Arrays.asList(ticket));
    }

    private static OmsTicket ticket(Long id, BigDecimal price) {
        OmsTicket t = new OmsTicket();
        t.setId(id);
        t.setOwnerType(2L);
        t.setOwnerId(ACTIVITY);
        t.setPrice(price);
        t.setRemainingInventory(5L);
        t.setDelFlag(0);
        return t;
    }

    private void givenTopic(int lifecycle, int userStatus, CommercialTopicPolicy.Evaluation evaluation) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setName("免费城市定向");
        topic.setProductType(ProductType.ORIENTEERING);
        topic.setLifecycle(lifecycle);
        topic.setStatus(1);
        topic.setUserStatus(userStatus);
        topic.setDelFlag(0);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(commercialTopicPolicy.evaluate(TOPIC)).thenReturn(evaluation);
        lastTopic = topic;
    }

    private CmsTopic lastTopic;

    private void givenSelfPlay(BigDecimal price) {
        lastTopic.setSelfPlay(1);
        lastTopic.setSelfPlayPrice(price);
    }

    private RuntimeException register() {
        return assertThrows(RuntimeException.class,
                () -> service.createRegistration(1L, 2, ACTIVITY, TICKET, 0, "张三", "13800000000", null, null));
    }

    private RuntimeException quoteRejected() {
        return assertThrows(RuntimeException.class,
                () -> service.quoteRegistration(2L, 2, ACTIVITY, TICKET, null, 0));
    }

    @Test
    void freeUgcTopic_reviewedAndOnShelf_registrationPassesSaleGate() {
        givenTopic(0, 1, CommercialTopicPolicy.Evaluation.ugc());
        assertEquals("用户不存在", register().getMessage(), "免费 UGC 已审核上架 = 可报名,不能被 SELLING 闸拦");
        assertEquals(0, BigDecimal.ZERO.compareTo(
                service.quoteRegistration(2L, 2, ACTIVITY, TICKET, null, 0).getPayAmount()), "报价与报名同口径");
    }

    /** 负控:商业主题未过开售审(lifecycle=0)仍必须被合同二闸拦,报价也一样。 */
    @Test
    void commercialTopic_notSelling_stillBlocked() {
        givenTopic(0, 1, CommercialTopicPolicy.Evaluation.commercial("paidTicket", 1L));
        assertTrue(register().getMessage().contains("SELLING"));
        assertTrue(quoteRejected().getMessage().contains("SELLING"), "报价不能对未开售商业主题报成功");
    }

    /** 负控:主题票免费但活动票被改成收费 —— 活动票不在 CommercialTopicPolicy 口径内,必须回到合同二闸。 */
    @Test
    void freeTopicWithPaidActivityTicket_stillBlocked() {
        givenTopic(0, 1, CommercialTopicPolicy.Evaluation.ugc());
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Arrays.asList(ticket, ticket(11L, new BigDecimal("10"))));
        assertTrue(register().getMessage().contains("SELLING"));
    }

    @Test
    void freeUgcTopic_offShelf_blocked() {
        givenTopic(0, 0, CommercialTopicPolicy.Evaluation.ugc());
        assertEquals("主题活动已下架", register().getMessage());
    }

    @Test
    void freeUgcTopic_signupDeadlinePassed_blocked() {
        givenTopic(0, 1, CommercialTopicPolicy.Evaluation.ugc());
        ticket.setSignupDeadline(new Date(System.currentTimeMillis() - 60_000L));
        assertEquals("票种报名已截止", register().getMessage());
    }

    /** W3f 口径统一:立项待审(PENDING_REVIEW)已进入商业流程,不是可报名的免费态,必须回合同二闸。 */
    @Test
    void pendingReviewTopic_isNotFreeRegistrationState() {
        givenTopic(7, 1, CommercialTopicPolicy.Evaluation.ugc());
        assertTrue(register().getMessage().contains("SELLING"));
    }

    /**
     * R19-PENDINGSHELL 复现陪测:pro 主题内容审核未过(status=0/userStatus=0),但商业审核已先行通过
     * (lifecycle=SELLING)。合同二售卖闸只读 lifecycle 与售卖时间,【不读主题 status】—— 只要自办活动壳
     * 公开(status=1/publishStatus=1),下单就能过闸走到用户校验(证据:错误文案是「用户不存在」而非闸拒)。
     * ⇒ 「壳只在过审后存在」是这条链路的唯一防线,见 {@link TopicPendingReviewSelfHostShellTest}。
     */
    @Test
    void pendingContentReviewSellingTopic_publicShellOrderGateDoesNotReadTopicStatus() {
        givenTopic(3, 0, CommercialTopicPolicy.Evaluation.commercial("paidTicket", 1L));
        // 主题内容审核未过:status=0 待审、user_status=0 未上架(公开主题列表/主题详情都不可见)
        lastTopic.setStatus(0);
        lastTopic.setUserStatus(0);
        // 壳已经卖得动了:开售事实 + 费率快照齐备
        lastTopic.setSellingStartedAt(new Date(System.currentTimeMillis() - 60_000L));
        lastTopic.setSaleEndAt(new Date(System.currentTimeMillis() + 86400_000L));
        lastTopic.setFeePlatformRate(new BigDecimal("0.01"));

        // 收费票走二清闸/费率闸,补上这两个协作者(其余路径与免费票一致)
        IProductRoleService productRoleService = mock(IProductRoleService.class);
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        when(ticketMapper.selectOmsTicketById(TICKET)).thenReturn(ticket(TICKET, new BigDecimal("10")));
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Arrays.asList(ticket(TICKET, new BigDecimal("10"))));

        assertEquals("用户不存在", register().getMessage(),
                "待审主题的公开壳在报名闸眼里与已过审主题无异(闸不读 status)");
    }

    /**
     * W3f 资损竞态:计价读到的票是 ¥10,免费判定时读到的活动票已被改回 ¥0(READ COMMITTED 下两次读可见不同提交)。
     * 修复前免费分支放行,按 ¥10 建单 = 未过开售审就收款。免费分支必须复核「真正计价的那张票」为 0 元。
     */
    @Test
    void freeBranch_rechecksChargedTicketPrice_singleTicket() {
        givenTopic(0, 1, CommercialTopicPolicy.Evaluation.ugc());
        when(ticketMapper.selectOmsTicketById(TICKET)).thenReturn(ticket(TICKET, new BigDecimal("10")));

        assertTrue(register().getMessage().contains("票价已变更"));
    }

    /** 同一竞态的多票路径:整单金额在首轮循环累加,行内建单再读票 —— 首轮读到的收费票同样必须过闸。 */
    @Test
    void freeBranch_rechecksChargedTicketPrice_multiTicket() {
        givenTopic(0, 1, CommercialTopicPolicy.Evaluation.ugc());
        when(ticketMapper.selectOmsTicketById(TICKET)).thenReturn(ticket(TICKET, new BigDecimal("10")), ticket);
        com.chengyinhub.business.domain.dto.RegistrationTicketItem item = new com.chengyinhub.business.domain.dto.RegistrationTicketItem();
        item.setTicketId(TICKET);
        item.setCount(1);

        RuntimeException error = assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "createMultiTicketRegistrationsInternal", 2L, 2, ACTIVITY, 0, "张三", "13800000000", null, null,
                Arrays.asList(item), null, null, null));
        assertTrue(String.valueOf(error.getMessage()).contains("票价已变更"), String.valueOf(error.getMessage()));
    }

    /** 9-15 拍板:免费自玩通行证不需要 SELLING,上架且未过截止即可领(与免费报名同口径)。 */
    @Test
    void freeTopic_selfPlayPass_doesNotRequireSelling() {
        givenTopic(0, 1, CommercialTopicPolicy.Evaluation.ugc());
        givenSelfPlay(BigDecimal.ZERO);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.createSelfPlayPass(1L, TOPIC, "张三", "13800000000"));
        assertEquals("用户不存在", error.getMessage(), "免费自玩过闸后应走到用户校验");
    }

    /** 负控:收费自玩 = 商业主题,DRAFT 下仍必须被 SELLING 闸拦。 */
    @Test
    void paidSelfPlayTopic_stillRequiresSelling() {
        givenTopic(0, 1, CommercialTopicPolicy.Evaluation.ugc());
        givenSelfPlay(new BigDecimal("9.9"));
        when(commercialTopicPolicy.evaluate(TOPIC)).thenReturn(CommercialTopicPolicy.Evaluation.commercial("paidTicket", 1L));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.createSelfPlayPass(1L, TOPIC, "张三", "13800000000"));
        assertTrue(error.getMessage().contains("SELLING"), error.getMessage());
    }

    /** 负控:免费自玩下架 / 过截止都拒。 */
    @Test
    void freeTopic_selfPlayPass_offShelfOrExpiredBlocked() {
        givenTopic(0, 0, CommercialTopicPolicy.Evaluation.ugc());
        givenSelfPlay(BigDecimal.ZERO);
        assertEquals("主题活动已下架", assertThrows(RuntimeException.class,
                () -> service.createSelfPlayPass(1L, TOPIC, "张三", "13800000000")).getMessage());

        givenTopic(0, 1, CommercialTopicPolicy.Evaluation.ugc());
        givenSelfPlay(BigDecimal.ZERO);
        lastTopic.setEndDate(new Date(System.currentTimeMillis() - 60_000L));
        assertEquals("主题已截止", assertThrows(RuntimeException.class,
                () -> service.createSelfPlayPass(1L, TOPIC, "张三", "13800000000")).getMessage());
    }

    /** 主题级报价与自玩同口径:免费主题不因非 SELLING 报价失败。 */
    @Test
    void freeTopic_topicLevelQuote_doesNotRequireSelling() {
        givenTopic(0, 1, CommercialTopicPolicy.Evaluation.ugc());
        OmsTicket topicTicket = ticket(21L, BigDecimal.ZERO);
        topicTicket.setOwnerType(1L);
        topicTicket.setOwnerId(TOPIC);
        when(ticketMapper.selectOmsTicketById(21L)).thenReturn(topicTicket);

        assertEquals(0, BigDecimal.ZERO.compareTo(
                service.quoteRegistration(2L, 1, TOPIC, 21L, null, 0).getPayAmount()));
    }

    /** 自玩同一竞态:免费判定读到 0 元,加锁行上的自玩价已是收费 ⇒ 免费分支必须按锁行复核,不能按收费价建单。 */
    @Test
    void freeSelfPlay_rechecksLockedSelfPlayPrice() {
        givenTopic(0, 1, CommercialTopicPolicy.Evaluation.ugc());
        givenSelfPlay(new BigDecimal("9.9"));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.createSelfPlayPass(1L, TOPIC, "张三", "13800000000"));
        assertTrue(error.getMessage().contains("票价已变更"), error.getMessage());
    }

    /** 负控:商业主题的主题级报价照旧要 SELLING。 */
    @Test
    void commercialTopic_topicLevelQuote_stillRequiresSelling() {
        givenTopic(0, 1, CommercialTopicPolicy.Evaluation.commercial("paidTicket", 21L));
        OmsTicket topicTicket = ticket(21L, new BigDecimal("10"));
        topicTicket.setOwnerType(1L);
        topicTicket.setOwnerId(TOPIC);
        when(ticketMapper.selectOmsTicketById(21L)).thenReturn(topicTicket);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.quoteRegistration(2L, 1, TOPIC, 21L, null, 0));
        assertTrue(error.getMessage().contains("SELLING"), error.getMessage());
    }
}

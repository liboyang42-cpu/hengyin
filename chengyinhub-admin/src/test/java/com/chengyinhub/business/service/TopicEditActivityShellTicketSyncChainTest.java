package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.domain.vo.PublicTicketVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsCollaboratorsMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.OmsTicketServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R9 P1：主题编辑 → 自办活动刷新 → 活动壳票同步，按<b>真实调用顺序</b>跑通的链路级测试。
 *
 * <p>被钉死的病:{@code rebuildTopicContent} 先物理删掉主题票、再整批重插,主题票 id 全换新;
 * 等到同一方法末尾 {@code refreshSelfHostActivity → syncTopicTicketsToActivity} 时,壳票里记的
 * {@code source_ticket_id} 已经指向不存在的旧 id ⇒ 每张壳票都被判成 orphan。已售壳票于是撞上
 * {@code ORPHAN_ALREADY_SOLD},整个编辑事务 409 回滚,发布者在产品内无自救路径;而
 * {@code OmsTicketCopyToActivityTest.syncingEditedTopicTicketPreservesAlreadySoldSeats} 断言的
 * 「原地更新保住已售席位」分支在生产从来走不到。
 *
 * <p>这里刻意<b>不</b>直接调 {@code syncTopicTicketsToActivity}:单测那个形状(source.id 恰好等于
 * 壳票 sourceTicketId)是手工喂出来的,生产链路造不出来。只有从 {@code updateTopicByOwner} 进,
 * 才能证明修复在真实顺序里成立。
 */
class TopicEditActivityShellTicketSyncChainTest {

    private static final Long ME = 100L;
    private static final Long TOPIC = 7L;
    private static final Long ACTIVITY = 22L;
    private static final Long OTHER_TOPIC = 8L;
    private static final Long SELF_HOST_CLASSIC_ACTIVITY = 79L;

    private static final String LUNCH = "探店日午场";
    private static final BigDecimal PRICE = new BigDecimal("69.00");
    private static final Date START = new Date(1_800_000_000_000L);
    private static final Date END = new Date(1_800_003_600_000L);

    private final List<OmsTicket> ticketRows = new ArrayList<>();
    private final AtomicLong ticketSequence = new AtomicLong(1000L);
    private Long selfHostClassicShellTicketId;

    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;
    private OmsTicketMapper ticketMapper;
    private OmsTicketServiceImpl ticketService;
    private CmsTopicServiceImpl topicService;
    private final java.util.Map<Long,com.chengyinhub.business.domain.CmsTopicChapter> publicationChapters=new java.util.LinkedHashMap<>();
    private final java.util.Map<Long,com.chengyinhub.business.domain.CmsTopicNode> publicationNodes=new java.util.LinkedHashMap<>();
    private final AtomicLong publicationSequence=new AtomicLong(20000L);

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        ticketMapper = fakeTicketMapper();

        ticketService = new OmsTicketServiceImpl();
        ReflectionTestUtils.setField(ticketService, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(ticketService, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(ticketService, "cmsActivityMapper", activityMapper);
        // P1-LC-1:后台票务写口多了「已发布免费主题禁转商业」的写后复核。本类钉的是壳票同步
        // 顺序,政策桩默认放行(受保护=false ⇒ 复核 no-op),判别力留在 OmsTicketFreeTopicConversionGuardTest。
        ReflectionTestUtils.setField(ticketService, "commercialTopicPolicy",
                mock(com.chengyinhub.business.service.support.CommercialTopicPolicy.class));
        ReflectionTestUtils.setField(ticketService, "productRoleService",
                mock(com.chengyinhub.business.service.IProductRoleService.class));

        topicService = new CmsTopicServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(topicService, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(topicService, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(topicService, "quotaService", mock(IQuotaService.class));
        ReflectionTestUtils.setField(topicService, "settlementDebtLedger", mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        when(topicMapper.updateCmsTopic(any())).thenReturn(1);
        ReflectionTestUtils.setField(topicService, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(topicService, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(topicService, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(topicService, "cmsTopicChapterMapper", mock(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(topicService, "cmsTopicNodeMapper", mock(CmsTopicNodeMapper.class));
        ReflectionTestUtils.setField(topicService, "cmsCollaboratorsMapper", mock(CmsCollaboratorsMapper.class));
        ReflectionTestUtils.setField(topicService, "couponRewardAuthorizationService",
                mock(ICouponRewardAuthorizationService.class));
        ReflectionTestUtils.setField(topicService, "clubService", mock(IClubService.class));
        ReflectionTestUtils.setField(topicService, "xpBudgetService", mock(IXpBudgetService.class));
        ReflectionTestUtils.setField(topicService, "auditTaskService", mock(IAuditTaskService.class));
        ReflectionTestUtils.setField(topicService, "topicCommercialReviewService",
                mock(ITopicCommercialReviewService.class));
        com.chengyinhub.business.service.support.CommercialTopicPolicy actualPolicy =
                new com.chengyinhub.business.service.support.CommercialTopicPolicy(ticketMapper,
                        (CmsTopicChapterMapper) ReflectionTestUtils.getField(topicService,"cmsTopicChapterMapper"),
                        mock(com.chengyinhub.business.mapper.MerchantChapterOfferMapper.class),topicMapper);
        ReflectionTestUtils.setField(topicService, "commercialTopicPolicy", actualPolicy);
        ReflectionTestUtils.setField(ticketService, "commercialTopicPolicy", actualPolicy);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(topicService, "omsTicketService", ticketService);

        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        // 主题层零报名 ⇒ assertTopicDeletable 放行(自办主题的销售在活动层,这道闸本来就看不见)
        when(registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(Collections.<CmsRegistration>emptyList());
        ReflectionTestUtils.setField(topicService, "cmsRegistrationMapper", registrationMapper);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(ME);
        topic.setStatus(2);          // 被后台驳回 ⇒ 回到可全字段重建的编辑窗
        topic.setLifecycle(0);
        topic.setDelFlag(0);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setName("探店日");
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(0);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setMemberId(ME);
        activity.setTopicId(TOPIC);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        when(activityMapper.selectCmsActivityList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(activity)));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        wirePublicationContent();
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(topicService);
        ReflectionTestUtils.setField(topicService, "themeStateKeyService", new com.chengyinhub.business.service.support.ThemeStateKeyService(
                mock(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class)));
        topicService = com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(topicService);
    }

    // ---------- P1 ----------

    @Test
    void editingTopicKeepsSoldShellTicketAndRecomputesRemainingFromSoldSeats() {
        Long topicTicketId = seedTopicTicket(TOPIC, LUNCH, PRICE, 30L);
        Long shellTicketId = seedShellTicket(topicTicketId, LUNCH, PRICE, 30L, 5L);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(topicService);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(topicService).updateTopicByOwner(dtoWithTickets(ticketRequest(LUNCH, PRICE, 40)), ME, "me");

        OmsTicket shell = row(shellTicketId);
        assertNotNull(shell, "已售壳票不能被删:它被订单的 ticket_id 引用");
        assertEquals(topicTicketId, shell.getSourceTicketId(), "壳票必须仍钉在同一张主题票上");
        assertEquals(Long.valueOf(40L), shell.getTotalInventory());
        assertEquals(Long.valueOf(15L), shell.getRemainingInventory(),
                "原票已售 25 张，总量 30 改 40 后余席只能是 15，不能复活成 40");
        assertNotNull(row(topicTicketId), "主题票必须原地保留 id，否则同步键在同步之前就没了");
        assertRealPublishedContent(TOPIC);
    }

    @Test
    void editingTopicWithoutTouchingTicketsKeepsBothRowsInPlace() {
        Long topicTicketId = seedTopicTicket(TOPIC, LUNCH, PRICE, 30L);
        Long shellTicketId = seedShellTicket(topicTicketId, LUNCH, PRICE, 30L, 5L);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(topicService);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(topicService).updateTopicByOwner(dtoWithTickets(ticketRequest(LUNCH, PRICE, 30)), ME, "me");

        assertEquals(2, ticketRows.size(), "未改票不得增删任何一行");
        assertNotNull(row(topicTicketId));
        assertEquals(topicTicketId, row(shellTicketId).getSourceTicketId());
        assertEquals(Long.valueOf(5L), row(shellTicketId).getRemainingInventory(), "未改票不得动余席");
    }

    @Test
    void droppingASoldTicketFromTheEditFailsClosedWithoutDestroyingIt() {
        Long topicTicketId = seedTopicTicket(TOPIC, LUNCH, PRICE, 30L);
        Long shellTicketId = seedShellTicket(topicTicketId, LUNCH, PRICE, 30L, 5L);

        ServiceException error = assertThrows(ServiceException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(topicService).updateTopicByOwner(
                        dtoWithTickets(ticketRequest("探店日晚场", PRICE, 20)), ME, "me"));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("ORPHAN_ALREADY_SOLD"), error.getMessage());
        assertTrue(error.getMessage().contains("ticketId=" + shellTicketId), error.getMessage());
        assertEquals(Long.valueOf(5L), row(shellTicketId).getRemainingInventory());
    }

    @Test
    void droppingAnUnsoldTicketDeletesItsShellRowAtomically() {
        Long topicTicketId = seedTopicTicket(TOPIC, LUNCH, PRICE, 30L);
        Long shellTicketId = seedShellTicket(topicTicketId, LUNCH, PRICE, 30L, 30L);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(topicService);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(topicService).updateTopicByOwner(dtoWithTickets(ticketRequest("探店日晚场", PRICE, 20)), ME, "me");

        assertNull(row(topicTicketId), "被移出编辑的主题票要真删掉");
        assertNull(row(shellTicketId), "未售孤儿壳票必须被条件删除清掉");
        assertEquals(2, ticketRows.size(), "新场次:一张主题票 + 一张壳票");
    }

    @Test
    void topicTicketIdentityIsNeverBorrowedFromAnotherTopic() {
        Long foreignTicketId = seedTopicTicket(OTHER_TOPIC, LUNCH, PRICE, 30L);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(topicService);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(topicService).updateTopicByOwner(dtoWithTickets(ticketRequest(LUNCH, PRICE, 30)), ME, "me");

        OmsTicket foreign = row(foreignTicketId);
        assertNotNull(foreign, "别的主题的同名同价票不得被本次编辑接管");
        assertEquals(OTHER_TOPIC, foreign.getOwnerId());
        assertEquals(Long.valueOf(30L), foreign.getTotalInventory());
        assertEquals(1, ticketsOf(1L, TOPIC).size());
        assertEquals(1, ticketsOf(2L, ACTIVITY).size());
    }

    @Test
    void repricingASoldSessionIsRefusedRatherThanQuietlyRebuiltUnderTheSoldSeats() {
        Long topicTicketId = seedTopicTicket(TOPIC, LUNCH, PRICE, 30L);
        Long shellTicketId = seedShellTicket(topicTicketId, LUNCH, PRICE, 30L, 5L);

        ServiceException error = assertThrows(ServiceException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(topicService).updateTopicByOwner(
                        dtoWithTickets(ticketRequest(LUNCH, new BigDecimal("99.00"), 30)), ME, "me"));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("ORPHAN_ALREADY_SOLD"), error.getMessage());
        assertEquals(PRICE, row(shellTicketId).getPrice(), "改价没成功就不能留下半改的壳票");
    }

    @Test
    void twoIdenticalSessionsStayEditableInsteadOfBrickingTheTopic() {
        Long first = seedTopicTicket(TOPIC, LUNCH, PRICE, 30L);
        Long second = seedTopicTicket(TOPIC, LUNCH, PRICE, 30L);
        Long firstShell = seedShellTicket(first, LUNCH, PRICE, 30L, 5L);
        Long secondShell = seedShellTicket(second, LUNCH, PRICE, 30L, 30L);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(topicService);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(topicService).updateTopicByOwner(
                dtoWithTickets(ticketRequest(LUNCH, PRICE, 30), ticketRequest(LUNCH, PRICE, 30)), ME, "me");

        assertEquals(4, ticketRows.size(), "同键重复票按 id 升序做多重集配对，不得因歧义把主题卡成不可编辑");
        assertEquals(first, row(firstShell).getSourceTicketId());
        assertEquals(second, row(secondShell).getSourceTicketId());
        assertEquals(Long.valueOf(5L), row(firstShell).getRemainingInventory());
    }

    @Test
    void concurrentlyChangedTopicTicketFailsClosedInsteadOfSilentlyInsertingASecondOne() {
        Long topicTicketId = seedTopicTicket(TOPIC, LUNCH, PRICE, 30L);
        seedShellTicket(topicTicketId, LUNCH, PRICE, 30L, 5L);
        // 条件更新命中 0 行 = 这张票在本次编辑期间被并发改动/搬走了
        org.mockito.Mockito.doReturn(0).when(ticketMapper).updateTopicTicketOnRebuild(any(OmsTicket.class));

        ServiceException error = assertThrows(ServiceException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(topicService).updateTopicByOwner(dtoWithTickets(ticketRequest(LUNCH, PRICE, 40)), ME, "me"));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("TOPIC_TICKET_REBUILD_RACE"), error.getMessage());
        // 事务回滚由 @Transactional 负责,这里钉的是「抛之前没先插」:
        // 更新命中 0 行时不得退化成 insert,否则同一场次会变成两张主题票。
        assertEquals(2, ticketRows.size(), "更新未命中时不得静默插入第二张主题票");
    }

    @Test
    void failedConditionalOrphanDeleteFailsClosed() {
        Long topicTicketId = seedTopicTicket(TOPIC, LUNCH, PRICE, 30L);
        seedShellTicket(topicTicketId, LUNCH, PRICE, 30L, 30L);
        // 条件删除命中 0 行 = 这张「未售孤儿」在读到与删掉之间被卖掉了
        org.mockito.Mockito.doReturn(0).when(ticketMapper)
                .deleteUnsoldActivityOrphanTicket(any(), any(), any());

        ServiceException error = assertThrows(ServiceException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(topicService).updateTopicByOwner(
                        dtoWithTickets(ticketRequest("探店日晚场", PRICE, 20)), ME, "me"));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("ORPHAN_DELETE_RACE"), error.getMessage());
    }

    @Test
    void shellTicketPointingAtAnotherTopicsTicketIsTreatedAsAnOrphanNotAsAMatch() {
        Long foreignTicketId = seedTopicTicket(OTHER_TOPIC, LUNCH, PRICE, 30L);
        seedTopicTicket(TOPIC, LUNCH, PRICE, 30L);
        Long shellTicketId = seedShellTicket(foreignTicketId, LUNCH, PRICE, 30L, 5L);

        ServiceException error = assertThrows(ServiceException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(topicService).updateTopicByOwner(dtoWithTickets(ticketRequest(LUNCH, PRICE, 30)), ME, "me"));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("ORPHAN_ALREADY_SOLD"), error.getMessage());
        assertTrue(error.getMessage().contains("ticketId=" + shellTicketId), error.getMessage());
    }

    @Test
    void anotherActivitysShellTicketIsNotTouchedByThisTopicSync() {
        Long topicTicketId = seedTopicTicket(TOPIC, LUNCH, PRICE, 30L);
        seedShellTicket(topicTicketId, LUNCH, PRICE, 30L, 5L);
        OmsTicket foreignShell = new OmsTicket();
        foreignShell.setOwnerType(2L);
        foreignShell.setOwnerId(999L);
        foreignShell.setSourceTicketId(topicTicketId);
        foreignShell.setName(LUNCH);
        foreignShell.setPrice(PRICE);
        foreignShell.setTotalInventory(30L);
        foreignShell.setRemainingInventory(8L);
        foreignShell.setDelFlag(0);
        ticketMapper.insertOmsTicket(foreignShell);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(topicService);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(topicService).updateTopicByOwner(dtoWithTickets(ticketRequest(LUNCH, PRICE, 40)), ME, "me");

        assertEquals(Long.valueOf(30L), row(foreignShell.getId()).getTotalInventory(),
                "同步只准动本活动的壳票:别的活动的壳票不在 owner scope 内");
        assertEquals(Long.valueOf(8L), row(foreignShell.getId()).getRemainingInventory());
    }

    // ---------- P2 ----------

    @Test
    void adminAddingATicketOnAFreeExploreShellIsRefusedInsteadOfMintingAnUnkeyedOne() {
        Long topicTicketId = seedTopicTicket(TOPIC, LUNCH, PRICE, 30L);
        Long shellTicketId = seedShellTicket(topicTicketId, LUNCH, PRICE, 30L, 25L);
        loginWith("business:ticket:add", "business:activity:edit");

        ServiceException refused = assertThrows(ServiceException.class,
                () -> ticketService.insertOmsTicket(adminSubmittedShellTicket()));

        assertEquals(409, refused.getCode());
        assertTrue(refused.getMessage().contains("TOPIC_SHELL_TICKET_NOT_DIRECTLY_CREATABLE"),
                refused.getMessage());
        assertEquals(2, ticketRows.size(), "被拒的添加不得留下任何行");

        // ① 读侧没被毒到:整期余席仍然确定,不退回「余席待确认」
        PublicTicketVO published = new PublicTicketVO();
        published.setId(topicTicketId);
        ticketService.applyActivityShellRemaining(TOPIC, new ArrayList<>(Collections.singletonList(published)));
        assertEquals(Long.valueOf(25L), published.getRemainingInventory());

        // ② 写侧没被毒到:主题编辑仍然点得动
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(topicService);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(topicService).updateTopicByOwner(dtoWithTickets(ticketRequest(LUNCH, PRICE, 30)), ME, "me");
        assertEquals(topicTicketId, row(shellTicketId).getSourceTicketId());
    }

    @Test
    void aGenuinelyUnkeyedShellRowStillForcesUnknownInventoryAndBlocksSync() {
        Long topicTicketId = seedTopicTicket(TOPIC, LUNCH, PRICE, 30L);
        seedShellTicket(topicTicketId, LUNCH, PRICE, 30L, 25L);
        // R6 回填留下的歧义行:这类 NULL 是真事实,闸不能因为入口被堵上就顺手放松
        seedShellTicket(null, LUNCH, PRICE, 30L, 30L);

        PublicTicketVO published = new PublicTicketVO();
        published.setId(topicTicketId);
        ticketService.applyActivityShellRemaining(TOPIC, new ArrayList<>(Collections.singletonList(published)));
        assertNull(published.getRemainingInventory(), "壳里有不可归属的库存 ⇒ 整期必须 unknown");

        ServiceException error = assertThrows(ServiceException.class,
                () -> ticketService.syncTopicTicketsToActivity(TOPIC, ACTIVITY, ME));
        assertTrue(error.getMessage().contains("ACTIVITY_TICKET_UNKEYED"), error.getMessage());
    }

    @Test
    void addingATicketOnASelfHostClassicShellIsRefused() {
        seedSelfHostClassicShell();
        loginWith("business:ticket:add", "business:activity:edit");
        OmsTicket submitted = adminSubmittedShellTicket();
        submitted.setOwnerId(SELF_HOST_CLASSIC_ACTIVITY);

        ServiceException refused = assertThrows(ServiceException.class,
                () -> ticketService.insertOmsTicket(submitted));

        assertEquals(409, refused.getCode());
        assertTrue(refused.getMessage().contains("TOPIC_SHELL_TICKET_NOT_DIRECTLY_CREATABLE"),
                refused.getMessage());
        assertEquals(2, ticketRows.size(), "被拒的添加不得留下任何行");
    }

    @Test
    void selfHostClassicShellTicketMustNotBrickTheNextTopicEdit() {
        Long topicTicketId = seedSelfHostClassicShell();
        loginWith("business:ticket:add", "business:activity:edit");
        OmsTicket submitted = adminSubmittedShellTicket();
        submitted.setOwnerId(SELF_HOST_CLASSIC_ACTIVITY);
        // 修好之后这一步应当被拒;这里刻意吞掉,好让「下一次主题编辑还点不点得动」成为本用例真正的判据。
        try {
            ticketService.insertOmsTicket(submitted);
        } catch (ServiceException expectedAfterFix) {
            // no-op
        }

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(topicService);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(topicService).updateTopicByOwner(classicDto(ticketRequest(LUNCH, PRICE, 30)), ME, "me");

        assertEquals(topicTicketId, row(selfHostClassicShellTicketId).getSourceTicketId(),
                "自办①主题的壳票必须仍钉在同一张主题票上");
    }

    @Test
    void addingATicketOnAMerchantRunClassicActivityStaysAllowed() {
        // 反向断言:闸不得误伤「商家承接的①活动」——它的 memberId 不是主题发布者,
        // 因此 refreshSelfHostActivity 的 (topicId, memberId) 探针根本找不到它、永远不会被同步,
        // 在这上面加一张独立票是合法且无副作用的。把闸放宽成「凡有 topic_id 就拦」时这条必须变红。
        CmsTopic classic = new CmsTopic();
        classic.setId(OTHER_TOPIC);
        classic.setMemberId(ME);
        classic.setLifecycle(0);
        classic.setProductType(ProductType.ORIENTEERING);
        when(topicMapper.selectCmsTopicById(OTHER_TOPIC)).thenReturn(classic);
        CmsActivity classicShell = new CmsActivity();
        classicShell.setId(78L);
        classicShell.setMemberId(555L);          // 商家承接方,不是主题发布者
        classicShell.setTopicId(OTHER_TOPIC);
        when(activityMapper.selectCmsActivityById(78L)).thenReturn(classicShell);
        loginWith("business:ticket:add", "business:activity:edit");
        OmsTicket submitted = adminSubmittedShellTicket();
        submitted.setOwnerId(78L);

        assertEquals(1, ticketService.insertOmsTicket(submitted));
    }

    @Test
    void addingATicketOnAShellOfATopicWithoutAnOwnerStaysAllowed() {
        // 存量脏数据:主题没有 memberId。它既不是探店日、也判不出自办 —— 而且
        // refreshSelfHostActivity 的探针要拿 memberId 去匹配,这种主题的壳永远同步不到,
        // 所以放行。判据两边都要判空,不能拿 topic.getMemberId().equals(...) 直接点下去。
        CmsTopic ownerless = new CmsTopic();
        ownerless.setId(OTHER_TOPIC);
        ownerless.setMemberId(null);
        ownerless.setLifecycle(0);
        ownerless.setProductType(ProductType.ORIENTEERING);
        when(topicMapper.selectCmsTopicById(OTHER_TOPIC)).thenReturn(ownerless);
        CmsActivity shell = new CmsActivity();
        shell.setId(80L);
        shell.setMemberId(555L);
        shell.setTopicId(OTHER_TOPIC);
        when(activityMapper.selectCmsActivityById(80L)).thenReturn(shell);
        loginWith("business:ticket:add", "business:activity:edit");
        OmsTicket submitted = adminSubmittedShellTicket();
        submitted.setOwnerId(80L);

        assertEquals(1, ticketService.insertOmsTicket(submitted));
    }

    @Test
    void addingATicketOnAnActivityWithoutATopicStaysAllowed() {
        CmsActivity standalone = new CmsActivity();
        standalone.setId(77L);
        standalone.setMemberId(ME);
        standalone.setTopicId(null);
        when(activityMapper.selectCmsActivityById(77L)).thenReturn(standalone);
        loginWith("business:ticket:add", "business:activity:edit");
        OmsTicket submitted = adminSubmittedShellTicket();
        submitted.setOwnerId(77L);

        assertEquals(1, ticketService.insertOmsTicket(submitted));
    }

    // ---------- 夹具 ----------

    /**
     * ① 经典定向 + 发布者自办的活动壳：{@code activity.memberId == topic.memberId}，
     * 正是 {@code refreshSelfHostActivity} 的 (topicId, memberId) 探针会命中、因而会被同步的那一类。
     *
     * @return 该主题票 id（壳票 id 落在 {@link #selfHostClassicShellTicketId}）
     */
    private Long seedSelfHostClassicShell() {
        CmsTopic classic = new CmsTopic();
        classic.setId(OTHER_TOPIC);
        classic.setMemberId(ME);
        classic.setStatus(2);
        classic.setLifecycle(0);
        classic.setDelFlag(0);
        classic.setProductType(ProductType.ORIENTEERING);
        classic.setName("经典定向");
        when(topicMapper.selectCmsTopicById(OTHER_TOPIC)).thenReturn(classic);
        when(topicMapper.selectTopicIsDraft(OTHER_TOPIC)).thenReturn(0);

        CmsActivity selfHostShell = new CmsActivity();
        selfHostShell.setId(SELF_HOST_CLASSIC_ACTIVITY);
        selfHostShell.setMemberId(ME);               // 承接方就是发布者本人 ⇒ 自办壳
        selfHostShell.setTopicId(OTHER_TOPIC);
        selfHostShell.setStatus(1L);
        selfHostShell.setPublishStatus(1L);
        selfHostShell.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(SELF_HOST_CLASSIC_ACTIVITY)).thenReturn(selfHostShell);
        when(activityMapper.selectCmsActivityList(any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(selfHostShell)));

        Long topicTicketId = seedTopicTicket(OTHER_TOPIC, LUNCH, PRICE, 30L);
        OmsTicket shell = new OmsTicket();
        shell.setMemberId(ME);
        shell.setOwnerType(2L);
        shell.setOwnerId(SELF_HOST_CLASSIC_ACTIVITY);
        shell.setSourceTicketId(topicTicketId);
        shell.setName(LUNCH);
        shell.setPrice(PRICE);
        shell.setStartTime(START);
        shell.setEndTime(END);
        shell.setTotalInventory(30L);
        shell.setRemainingInventory(5L);
        shell.setDelFlag(0);
        ticketMapper.insertOmsTicket(shell);
        selfHostClassicShellTicketId = shell.getId();
        return topicTicketId;
    }

    private TopicUpdateDTO classicDto(ActivityTicketRequest... tickets) {
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(OTHER_TOPIC);
        dto.setName("经典定向");
        dto.setProductType(ProductType.ORIENTEERING);
        dto.setChapters(publicationContent(OTHER_TOPIC));
        dto.setTickets(new ArrayList<>(java.util.Arrays.asList(tickets)));
        return dto;
    }

    private OmsTicket adminSubmittedShellTicket() {
        OmsTicket submitted = new OmsTicket();
        submitted.setOwnerType(2L);
        submitted.setOwnerId(ACTIVITY);
        submitted.setName("后台临时加的场次");
        submitted.setPrice(PRICE);
        submitted.setStartTime(START);
        submitted.setEndTime(END);
        submitted.setTotalInventory(20L);
        return submitted;
    }

    private void loginWith(String... permissions) {
        com.chengyinhub.common.core.domain.entity.SysUser user =
                new com.chengyinhub.common.core.domain.entity.SysUser();
        user.setUserId(1L);
        com.chengyinhub.common.core.domain.model.LoginUser loginUser =
                new com.chengyinhub.common.core.domain.model.LoginUser(
                        user, new java.util.HashSet<>(java.util.Arrays.asList(permissions)));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        loginUser, null, Collections.emptyList()));
    }

    @org.junit.jupiter.api.AfterEach
    void clearLogin() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private TopicUpdateDTO dtoWithTickets(ActivityTicketRequest... tickets) {
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(TOPIC);
        dto.setName("探店日");
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setRecruitDeadline(new Date(1_790_000_000_000L));
        dto.setChapters(publicationContent(TOPIC));
        dto.setTickets(new ArrayList<>(java.util.Arrays.asList(tickets)));
        return dto;
    }

    private static ActivityTicketRequest ticketRequest(String name, BigDecimal price, Integer totalStock) {
        ActivityTicketRequest request = new ActivityTicketRequest();
        request.setName(name);
        request.setPrice(price);
        request.setStartTime(START);
        request.setEndTime(END);
        request.setTotalStock(totalStock);
        return request;
    }

    private Long seedTopicTicket(Long topicId, String name, BigDecimal price, Long total) {
        OmsTicket ticket = new OmsTicket();
        ticket.setMemberId(ME);
        ticket.setOwnerType(1L);
        ticket.setOwnerId(topicId);
        ticket.setName(name);
        ticket.setPrice(price);
        ticket.setStartTime(START);
        ticket.setEndTime(END);
        ticket.setTotalInventory(total);
        ticket.setRemainingInventory(total);
        ticket.setDelFlag(0);
        ticketMapper.insertOmsTicket(ticket);
        return ticket.getId();
    }

    private Long seedShellTicket(Long sourceTicketId, String name, BigDecimal price, Long total, Long remaining) {
        OmsTicket ticket = new OmsTicket();
        ticket.setMemberId(ME);
        ticket.setOwnerType(2L);
        ticket.setOwnerId(ACTIVITY);
        ticket.setSourceTicketId(sourceTicketId);
        ticket.setName(name);
        ticket.setPrice(price);
        ticket.setStartTime(START);
        ticket.setEndTime(END);
        ticket.setTotalInventory(total);
        ticket.setRemainingInventory(remaining);
        ticket.setDelFlag(0);
        ticketMapper.insertOmsTicket(ticket);
        return ticket.getId();
    }

    private OmsTicket row(Long id) {
        for (OmsTicket ticket : ticketRows) {
            if (ticket.getId().equals(id)) return ticket;
        }
        return null;
    }

    private List<OmsTicket> ticketsOf(Long ownerType, Long ownerId) {
        List<OmsTicket> hit = new ArrayList<>();
        for (OmsTicket ticket : ticketRows) {
            if (ownerType.equals(ticket.getOwnerType()) && ownerId.equals(ticket.getOwnerId())) hit.add(ticket);
        }
        return hit;
    }

    /**
     * 真行为的内存 oms_ticket:自增 id、按 owner 过滤、条件删除真判 remaining==total。
     * 只有这样「主题票换了新 id」才是被跑出来的事实,而不是被 stub 假设出来的。
     */
    private OmsTicketMapper fakeTicketMapper() {
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        when(mapper.insertOmsTicket(any(OmsTicket.class))).thenAnswer(invocation -> {
            OmsTicket ticket = invocation.getArgument(0);
            ticket.setId(ticketSequence.incrementAndGet());
            OmsTicket row = copy(ticket);
            // 复刻列默认值:insertOmsTicket 的 XML 只在 delFlag 非空时写这一列,后台建票路径
            // (OmsTicketController.insert)从不设它 ⇒ 落库值来自 DDL 默认。全仓 del_flag 一律
            // `NOT NULL DEFAULT 0`,且后台建的票确实对 selectOmsTicketList(delFlag=0) 可见
            // (P2-1 那条毒丸链路的前提就是它可见)。⚠️ oms_ticket 的建表语句不在本仓 SQL 里,
            // 该默认值只能靠生产 DDL 回读坐实 —— 见报告 EXTERNAL_GATE。
            if (row.getDelFlag() == null) row.setDelFlag(0);
            ticketRows.add(row);
            return 1;
        });
        when(mapper.selectOmsTicketList(any(OmsTicket.class))).thenAnswer(invocation -> {
            OmsTicket query = invocation.getArgument(0);
            List<OmsTicket> hit = new ArrayList<>();
            for (OmsTicket ticket : ticketRows) {
                if (query.getOwnerType() != null && !query.getOwnerType().equals(ticket.getOwnerType())) continue;
                if (query.getOwnerId() != null && !query.getOwnerId().equals(ticket.getOwnerId())) continue;
                if (query.getDelFlag() != null && !query.getDelFlag().equals(ticket.getDelFlag())) continue;
                hit.add(copy(ticket));
            }
            hit.sort(java.util.Comparator.comparing(OmsTicket::getId));
            return hit;
        });
        when(mapper.selectOmsTicketById(any())).thenAnswer(invocation -> {
            OmsTicket found = row(invocation.getArgument(0));
            return found == null ? null : copy(found);
        });
        when(mapper.updateOmsTicket(any(OmsTicket.class))).thenAnswer(invocation ->
                applyPatch(invocation.getArgument(0)));
        when(mapper.updateTopicTicketOnRebuild(any(OmsTicket.class))).thenAnswer(invocation ->
                applyTopicTicketRebuild(invocation.getArgument(0)));
        // 复刻 selectActivityShellTicketRemainingByTopic 的 LEFT JOIN:壳恒占至少一行,
        // 未配对票以 topicTicketId=NULL 原样出现,判不判得了归 service。
        when(mapper.selectActivityShellTicketRemainingByTopic(any())).thenAnswer(invocation -> {
            List<com.chengyinhub.business.domain.vo.ActivityShellTicketInventoryVO> rows = new ArrayList<>();
            List<OmsTicket> shells = ticketsOf(2L, ACTIVITY);
            if (shells.isEmpty()) {
                rows.add(inventoryRow(ACTIVITY, null, null));
                return rows;
            }
            for (OmsTicket shell : shells) {
                rows.add(inventoryRow(ACTIVITY, shell.getSourceTicketId(), shell.getRemainingInventory()));
            }
            return rows;
        });
        when(mapper.deleteOmsTicketById(any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return ticketRows.removeIf(ticket -> id.equals(ticket.getId())) ? 1 : 0;
        });
        when(mapper.deleteUnsoldActivityOrphanTicket(any(), any(), any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            Long activityId = invocation.getArgument(1);
            Long sourceTicketId = invocation.getArgument(2);
            OmsTicket target = row(id);
            if (target == null || !activityId.equals(target.getOwnerId())
                    || !java.util.Objects.equals(sourceTicketId, target.getSourceTicketId())
                    || target.getTotalInventory() == null || target.getRemainingInventory() == null
                    || !target.getRemainingInventory().equals(target.getTotalInventory())) {
                return 0;
            }
            ticketRows.remove(target);
            return 1;
        });
        return mapper;
    }

    /**
     * 复刻 updateTopicTicketOnRebuild 的 where:归属不符/已删/库存被并发改动一律 0 行,
     * 且编辑器拥有的列无条件覆盖(清空要真清空)。
     */
    private int applyTopicTicketRebuild(OmsTicket patch) {
        OmsTicket target = row(patch.getId());
        if (target == null || !Long.valueOf(1L).equals(target.getOwnerType())
                || !java.util.Objects.equals(patch.getOwnerId(), target.getOwnerId())
                || !Integer.valueOf(0).equals(target.getDelFlag())) {
            return 0;
        }
        Long derived = target.getRemainingInventory() - (target.getTotalInventory() - patch.getTotalInventory());
        if (!java.util.Objects.equals(patch.getRemainingInventory(), derived) || derived < 0L) {
            return 0;
        }
        target.setName(patch.getName());
        target.setPrice(patch.getPrice());
        target.setStartTime(patch.getStartTime());
        target.setEndTime(patch.getEndTime());
        target.setTotalInventory(patch.getTotalInventory());
        target.setRemainingInventory(patch.getRemainingInventory());
        target.setMode(patch.getMode());
        target.setMeetingPoint(patch.getMeetingPoint());
        target.setTeamSize(patch.getTeamSize());
        target.setMinPeople(patch.getMinPeople());
        target.setMaxPeople(patch.getMaxPeople());
        target.setSignupDeadline(patch.getSignupDeadline());
        target.setGatherLng(patch.getGatherLng());
        target.setGatherLat(patch.getGatherLat());
        target.setRefundRule(patch.getRefundRule());
        target.setNeedAudit(patch.getNeedAudit());
        return 1;
    }

    /** 与 OmsTicketMapper.xml 的 &lt;if&gt; 语义一致:只写非空字段,并复刻库存一致性 where 条件。 */
    private int applyPatch(OmsTicket patch) {
        OmsTicket target = row(patch.getId());
        if (target == null) return 0;
        if (patch.getTotalInventory() != null) {
            Long derived = target.getRemainingInventory() - (target.getTotalInventory() - patch.getTotalInventory());
            if (!java.util.Objects.equals(patch.getRemainingInventory(), derived) || derived < 0L) return 0;
        }
        if (patch.getName() != null) target.setName(patch.getName());
        if (patch.getPrice() != null) target.setPrice(patch.getPrice());
        if (patch.getStartTime() != null) target.setStartTime(patch.getStartTime());
        if (patch.getEndTime() != null) target.setEndTime(patch.getEndTime());
        if (patch.getTotalInventory() != null) target.setTotalInventory(patch.getTotalInventory());
        if (patch.getRemainingInventory() != null) target.setRemainingInventory(patch.getRemainingInventory());
        if (patch.getMode() != null) target.setMode(patch.getMode());
        if (patch.getMeetingPoint() != null) target.setMeetingPoint(patch.getMeetingPoint());
        if (patch.getTeamSize() != null) target.setTeamSize(patch.getTeamSize());
        if (patch.getSignupDeadline() != null) target.setSignupDeadline(patch.getSignupDeadline());
        if (patch.getRefundRule() != null) target.setRefundRule(patch.getRefundRule());
        if (patch.getNeedAudit() != null) target.setNeedAudit(patch.getNeedAudit());
        return 1;
    }

    private static com.chengyinhub.business.domain.vo.ActivityShellTicketInventoryVO inventoryRow(
            Long activityId, Long topicTicketId, Long remainingInventory) {
        com.chengyinhub.business.domain.vo.ActivityShellTicketInventoryVO row =
                new com.chengyinhub.business.domain.vo.ActivityShellTicketInventoryVO();
        row.setActivityId(activityId);
        row.setTopicTicketId(topicTicketId);
        row.setRemainingInventory(remainingInventory);
        return row;
    }

    private static OmsTicket copy(OmsTicket source) {
        OmsTicket target = new OmsTicket();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }

    /** Current content rows shared by authoring, publication checks and the real compiler. */
    private void wirePublicationContent() {
        var chapters = (com.chengyinhub.business.mapper.CmsTopicChapterMapper)
                ReflectionTestUtils.getField(topicService,"cmsTopicChapterMapper");
        var nodes = (com.chengyinhub.business.mapper.CmsTopicNodeMapper)
                ReflectionTestUtils.getField(topicService,"cmsTopicNodeMapper");
        Object originalTemplates=com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(topicService,"cmsMemberTemplateMapper");
        final var templates = originalTemplates==null
                ? org.mockito.Mockito.mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class)
                : (com.chengyinhub.business.mapper.CmsMemberTemplateMapper)originalTemplates;
        var template = new com.chengyinhub.business.domain.CmsMemberTemplate();
        template.setId(77001L);template.setMemberId(ME);template.setTitle("营业时间核对");template.setStatus(1L);
        template.setDraftStatus(1);template.setPublishStatus(1L);template.setCheckStatus(1);template.setDelFlag(0);
        template.setValidationMethod(1);template.setQuestionName("场地何时开放？");template.setQuestionAnswer("18:00");
        template.setAdvancedConfigJson(new com.chengyinhub.business.service.support.AdvancedGameConfigValidator().validateAndNormalize(
                "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"场地何时开放？\",\"answerText\":\"18:00\",\"maxTries\":0,\"xp\":0}}"));
        org.mockito.Mockito.when(templates.selectCmsMemberTemplateById(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> template.getId().equals(call.getArgument(0)) ? template : null);
        org.mockito.Mockito.when(templates.selectCmsMemberTemplateByIds(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(call -> {
                    java.util.List<Long> ids=call.getArgument(0);
                    return ids.contains(template.getId()) ? java.util.List.of(template) : java.util.List.of();
                });
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(topicService,"cmsMemberTemplateMapper",templates);
        var chapter = new com.chengyinhub.business.domain.CmsTopicChapter();
        chapter.setId(701L);chapter.setTopicId(TOPIC);chapter.setName("入场核对");chapter.setSortId(1L);chapter.setRequired(1);
        publicationChapters.put(chapter.getId(),chapter);
        var node = new com.chengyinhub.business.domain.CmsTopicNode();
        node.setId(702L);node.setTopicId(TOPIC);node.setChapterId(chapter.getId());node.setTemplateId(template.getId());
        node.setName("营业时间核对");node.setStatus(1L);node.setSortId(1L);
        publicationNodes.put(node.getId(),node);
        org.mockito.Mockito.doAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicChapter query=call.getArgument(0);
            return new java.util.ArrayList<>(publicationChapters.values().stream()
                    .filter(row -> query != null && row.getTopicId().equals(query.getTopicId())).toList());
        }).when(chapters).selectCmsTopicChapterList(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(call -> publicationChapters.get(call.getArgument(0)))
                .when(chapters).selectCmsTopicChapterById(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.doAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicChapter row=call.getArgument(0);
            row.setId(publicationSequence.incrementAndGet());publicationChapters.put(row.getId(),row);return 1;
        }).when(chapters).insertCmsTopicChapter(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(call -> publicationChapters.remove(call.getArgument(0)) == null ? 0 : 1)
                .when(chapters).deleteCmsTopicChapterById(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.doAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicNode query=call.getArgument(0);
            return new java.util.ArrayList<>(publicationNodes.values().stream()
                    .filter(row -> query != null && row.getTopicId().equals(query.getTopicId())
                            && (query.getStatus()==null || query.getStatus().equals(row.getStatus()))).toList());
        }).when(nodes).selectCmsTopicNodeList(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(call -> publicationNodes.get(call.getArgument(0)))
                .when(nodes).selectCmsTopicNodeById(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.doAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicNode row=call.getArgument(0);
            row.setId(publicationSequence.incrementAndGet());publicationNodes.put(row.getId(),row);return 1;
        }).when(nodes).insertCmsTopicNode(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(call -> publicationNodes.remove(call.getArgument(0)) == null ? 0 : 1)
                .when(nodes).deleteCmsTopicNodeById(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.doAnswer(call -> {
            CmsTopic patch=call.getArgument(0);CmsTopic row=topicMapper.selectCmsTopicById(patch.getId());
            if(row==null)return 0;
            var source=new org.springframework.beans.BeanWrapperImpl(patch);
            var target=new org.springframework.beans.BeanWrapperImpl(row);
            for(var property:source.getPropertyDescriptors()) {
                String name=property.getName();
                if(source.isReadableProperty(name) && target.isWritableProperty(name)
                        && source.getPropertyValue(name)!=null)target.setPropertyValue(name,source.getPropertyValue(name));
            }
            return 1;
        }).when(topicMapper).updateCmsTopic(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(call -> {
            CmsTopic row=topicMapper.selectCmsTopicById(call.getArgument(0));if(row==null)return 0;
            String snapshot=call.getArgument(1);assertTrue(snapshot!=null && !snapshot.isBlank());
            row.setPublishedRouteGraphJson(snapshot);
            row.setPublishedConfigVersion(row.getPublishedConfigVersion()==null ? 1L : row.getPublishedConfigVersion()+1);
            return 1;
        }).when(topicMapper).publishRouteSnapshot(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
    }

    private java.util.List<com.chengyinhub.business.domain.dto.ChapterDTO> publicationContent(Long topicId) {
        var chapter=new com.chengyinhub.business.domain.dto.ChapterDTO();chapter.setName("入场核对");chapter.setRequired(1);
        chapter.setAtmospherePreset(com.chengyinhub.business.util.ChapterAtmospherePreset.DEFAULT);
        publicationChapters.values().stream().filter(row -> topicId.equals(row.getTopicId())).findFirst()
                .ifPresent(row -> chapter.setId(row.getId()));
        var node=new com.chengyinhub.business.domain.dto.NodeDTO();node.setName("营业时间核对");
        node.setTemplateId(77001L);node.setLongitude("121.0");node.setLatitude("31.0");
        publicationNodes.values().stream().filter(row -> topicId.equals(row.getTopicId())).findFirst()
                .ifPresent(row -> node.setId(row.getId()));
        chapter.setNodes(java.util.List.of(node));return java.util.List.of(chapter);
    }

    private void assertRealPublishedContent(Long topicId) {
        var row=topicMapper.selectCmsTopicById(topicId);
        var snapshot=org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(row.getPublishedRouteGraphJson()));
        assertEquals(1,snapshot.path("runtimeSnapshotVersion").asInt());
        assertEquals(1,snapshot.path("stateKeySnapshotVersion").asInt());
        assertEquals(1,snapshot.path("nodeAnswers").size());
        var content=snapshot.path("nodeAnswers").elements().next();
        assertEquals(77001L,content.path("templateId").asLong());
        assertEquals(topicId.longValue(),content.path("nodePresentation").path("topicId").asLong());
        assertEquals("18:00",content.path("questionAnswer").asText());
    }
}

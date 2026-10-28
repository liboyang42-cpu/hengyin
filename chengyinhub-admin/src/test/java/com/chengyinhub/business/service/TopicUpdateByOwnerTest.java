package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsCollaboratorsMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [B3b] 创作者编辑自己主题的三档编辑窗口。
 *
 * <p>病:publish/fabu 三个入口都带 ?id= 跳进来,但页面不读 id、保存只调 /api/topic/create
 * ⇒「编辑草稿」实际在空表单上再建一条,原草稿不动、重复主题越攒越多。
 *
 * <p>本文件钉死的承重不变量(口径真源 = vault 方案 §6,2026-08-04 拍板):
 * <ul>
 *   <li>本人 + 草稿/待审/驳回 + 未开始 → 就地改,<b>绝不 insert 新主题</b>(这条就是原 bug)</li>
 *   <li>他人 → 拒</li>
 *   <li>已开始 → 只读</li>
 *   <li>已过审开卖 → <b>只有白名单文案与图能改</b>;日期 / 章节站点结构 / 票价三类<b>必须被拒且值不变</b>
 *       —— 只测「白名单能改」是假绿,那证明不了不该改的被拦住了</li>
 * </ul>
 */
class TopicUpdateByOwnerTest {

    private static final Long ME = 100L;
    private static final Long TOPIC = 7L;

    private CmsTopicMapper topicMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicNodeMapper nodeMapper;
    private OmsTicketMapper ticketMapper;
    private CmsCollaboratorsMapper collaboratorsMapper;
    private CmsActivityMapper activityMapper;
    private CmsRegistrationMapper registrationMapper;
    private ICouponRewardAuthorizationService couponAuth;
    private IClubService clubService;
    private IXpBudgetService xpBudgetService;
    private IAuditTaskService auditTaskService;
    private IOmsTicketService omsTicketService;
    private CmsTopicServiceImpl svc;
    private com.chengyinhub.business.service.support.CommercialTopicPolicy commercialTopicPolicy;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        collaboratorsMapper = mock(CmsCollaboratorsMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        couponAuth = mock(ICouponRewardAuthorizationService.class);
        clubService = mock(IClubService.class);
        xpBudgetService = mock(IXpBudgetService.class);
        auditTaskService = mock(IAuditTaskService.class);
        omsTicketService = mock(IOmsTicketService.class);

        // Mapper 成功插入须返回 1 并回填生成 ID；null 是 Mockito 重 stub 的占位调用。
        AtomicLong chapterIds = new AtomicLong(801L);
        org.mockito.Mockito.doAnswer(invocation -> {
            CmsTopicChapter chapter = invocation.getArgument(0);
            if (chapter != null) chapter.setId(chapterIds.getAndIncrement());
            return 1;
        }).when(chapterMapper).insertCmsTopicChapter(any());
        AtomicLong nodeIds = new AtomicLong(901L);
        org.mockito.Mockito.doAnswer(invocation -> {
            CmsTopicNode node = invocation.getArgument(0);
            if (node != null) node.setId(nodeIds.getAndIncrement());
            return 1;
        }).when(nodeMapper).insertCmsTopicNode(any());
        AtomicLong activityIds = new AtomicLong(1001L);
        org.mockito.Mockito.doAnswer(invocation -> {
            CmsActivity activity = invocation.getArgument(0);
            if (activity != null) activity.setId(activityIds.getAndIncrement());
            return 1;
        }).when(activityMapper).insertCmsActivity(any());

        svc = new CmsTopicServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(svc, "quotaService", mock(IQuotaService.class));
        when(topicMapper.publishRouteSnapshot(any(), any(), any())).thenReturn(1);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "settlementDebtLedger", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        commercialTopicPolicy = new com.chengyinhub.business.service.support.CommercialTopicPolicy(
                ticketMapper, chapterMapper, mock(com.chengyinhub.business.mapper.MerchantChapterOfferMapper.class), topicMapper);
        ReflectionTestUtils.setField(svc, "commercialTopicPolicy", commercialTopicPolicy);
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(svc, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(svc, "cmsCollaboratorsMapper", collaboratorsMapper);
        ReflectionTestUtils.setField(svc, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(svc, "couponRewardAuthorizationService", couponAuth);
        ReflectionTestUtils.setField(svc, "clubService", clubService);
        ReflectionTestUtils.setField(svc, "xpBudgetService", xpBudgetService);
        ReflectionTestUtils.setField(svc, "auditTaskService", auditTaskService);
        ReflectionTestUtils.setField(svc, "omsTicketService", omsTicketService);

        // 没有任何报名(钱不在场),结构重建的钱闸放行
        when(registrationMapper.selectCmsRegistrationList(any())).thenReturn(Collections.<CmsRegistration>emptyList());
        // 自办活动:主题下没有既有活动 → refreshSelfHostActivity 走「按需新建」分支
        when(activityMapper.selectCmsActivityList(any())).thenReturn(Collections.<CmsActivity>emptyList());
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
    }

    // ---------- 夹具 ----------

    private CmsTopic topic(int status, Date startDate) {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(ME);
        t.setStatus(status);
        t.setLifecycle(status == 1 ? 1 : 0);
        t.setDelFlag(0);
        t.setStartDate(startDate);
        t.setName("旧名字");
        return t;
    }

    private static Date daysFromNow(int days) {
        return new Date(System.currentTimeMillis() + days * 24L * 3600_000L);
    }

    /** 只带白名单文案/图的 DTO —— 没有日期、没有章节、没有票。 */
    private TopicUpdateDTO whitelistOnlyDto() {
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(TOPIC);
        dto.setName("新名字");
        dto.setSubtitle("新副标题");
        dto.setDescription("新简介");
        dto.setImgUrl("media-asset:1");
        dto.setImgArr("media-asset:1,media-asset:3");
        dto.setCategoryIds("3,4");
        return dto;
    }

    /** 全字段 DTO:一章一节点、一张票、经典定向。 */
    private TopicUpdateDTO fullDto() {
        TopicUpdateDTO dto = whitelistOnlyDto();
        dto.setProductType(ProductType.ORIENTEERING);
        dto.setStartDate(daysFromNow(3));
        dto.setEndDate(daysFromNow(5));
        NodeDTO node = new NodeDTO();
        node.setName("第一站");
        node.setAddress("人民广场");
        node.setNodeTime(30);
        ChapterDTO chapter = new ChapterDTO();
        chapter.setName("第一章");
        chapter.setNodes(new ArrayList<>(Arrays.asList(node)));
        dto.setChapters(new ArrayList<>(Arrays.asList(chapter)));
        dto.setTickets(new ArrayList<ActivityTicketRequest>());
        return dto;
    }

    private void stubExisting(CmsTopic t, Integer isDraft) {
        // Non-draft pending/rejected fixtures have already entered a review lifecycle.
        // The two free-to-commercial tests below explicitly retain DRAFT to test that boundary.
        if (!Integer.valueOf(1).equals(isDraft) && Integer.valueOf(0).equals(t.getLifecycle())) t.setLifecycle(1);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
        // 带 journeyRules 的保存会先锁主题行再冻结属性定义；真 mapper 锁读返回同一行。
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(t);
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(isDraft);
        org.mockito.Mockito.doAnswer(invocation -> {
            CmsTopic patch = invocation.getArgument(0);
            if (patch.getStatus() != null) t.setStatus(patch.getStatus());
            if (patch.getUserStatus() != null) t.setUserStatus(patch.getUserStatus());
            return 1;
        }).when(topicMapper).updateCmsTopic(any());
    }

    // ---------- 档 1:草稿/待审/驳回 全字段可改 ----------

    @Test
    void draftOwner_updatesInPlace_neverInsertsANewTopic() {
        stubExisting(topic(0, daysFromNow(3)), 1);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        Long id = svc.updateTopicByOwner(fullDto(), ME, "me");

        assertEquals(TOPIC, id);
        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(cap.capture());
        assertEquals(TOPIC, cap.getValue().getId(), "必须改回原主题,不能另起一条");
        assertEquals("新名字", cap.getValue().getName());
        // 这就是原 bug:编辑走的是 create ⇒ 每保存一次多一条重复主题
        verify(topicMapper, never()).insertCmsTopic(any());
        // 结构整体重建:旧章节/节点被清、新的被写
        verify(chapterMapper).insertCmsTopicChapter(any());
        verify(nodeMapper).insertCmsTopicNode(any());
    }

    /**
     * 9-15 拍板:已发布免费主题(待审/驳回档仍走全量重建)编辑时加收费票 = 免费转商业 ⇒ 整单拒、事务回滚,
     * 且不能继续把它送进立项审。
     *
     * <p>⚠️ 本用例<b>刻意不带 recruitDeadline</b>:它钉的是 {@code rebuildTopicContent} 末尾的
     * <b>写后复核</b>(整包重建后 evaluate 看到商业事实 ⇒ 抛)。带 recruitDeadline 会在方法开头的
     * 早退分支就抛(那条由 {@link #publishedFreeTopic_addingRecruitDeadlineOnly_isRejected} 覆盖),
     * 复核那一行就永远跑不到 —— 2026-09-15 审查实证:删掉复核行,当时的用例仍全绿(假绿)。
     */
    @Test
    void publishedFreeTopic_editThatMakesItCommercial_isRejected() {
        stubExisting(topic(0, daysFromNow(3)), 0);
        topicMapper.selectCmsTopicById(TOPIC).setLifecycle(0);
        java.util.List<com.chengyinhub.business.domain.OmsTicket> storedTickets = new ArrayList<>();
        when(ticketMapper.selectOmsTicketList(any())).thenAnswer(call -> new ArrayList<>(storedTickets));
        when(ticketMapper.insertOmsTicket(any())).thenAnswer(call -> {
            com.chengyinhub.business.domain.OmsTicket stored = call.getArgument(0);
            stored.setId(501L); storedTickets.add(stored); return 1;
        });
        ITopicCommercialReviewService reviewService = mock(ITopicCommercialReviewService.class);
        ReflectionTestUtils.setField(svc, "topicCommercialReviewService", reviewService);
        TopicUpdateDTO dto = fullDto();
        ActivityTicketRequest paid = new ActivityTicketRequest();
        paid.setName("新增收费票"); paid.setPrice(new BigDecimal("9.00")); paid.setTotalStock(10);
        paid.setStartTime(dto.getStartDate()); paid.setEndTime(dto.getEndDate());
        dto.setTickets(new ArrayList<>(Collections.singletonList(paid)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.updateTopicByOwner(dto, ME, "me"));
        assertEquals(com.chengyinhub.business.service.support.CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED,
                error.getMessage());
        verify(reviewService, never()).submitProjectReview(anyLong(), anyLong(), any());
    }

    /** 审查发现的绕过:已发布免费主题只加招募截止 ⇒ 被送进 RECRUITING(商业轨道),之后不再受保护。必须同样拒。 */
    @Test
    void publishedFreeTopic_addingRecruitDeadlineOnly_isRejected() {
        stubExisting(topic(0, daysFromNow(3)), 0);
        topicMapper.selectCmsTopicById(TOPIC).setLifecycle(0);
        ITopicCommercialReviewService reviewService = mock(ITopicCommercialReviewService.class);
        ReflectionTestUtils.setField(svc, "topicCommercialReviewService", reviewService);
        TopicUpdateDTO dto = fullDto();
        dto.setRecruitDeadline(daysFromNow(2));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> svc.updateTopicByOwner(dto, ME, "me"));
        assertEquals(com.chengyinhub.business.service.support.CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED,
                error.getMessage());
        verify(reviewService, never()).submitProjectReview(anyLong(), anyLong(), any());
    }

    /**
     * 2026-09-15 补充裁决(审查D §1 第 4 条):归属俱乐部的主题不开放公开承接池。
     * 编辑时显式上送 1 必须被服务端压回 0 —— 前端对俱乐部自办团隐藏开关只是体验。
     */
    @Test
    void clubBoundTopic_editCannotOpenPublicClubPool() {
        CmsTopic exist = topic(0, daysFromNow(3));
        exist.setClubId(9L);
        stubExisting(exist, 1);
        Club club = new Club();
        club.setId(9L);
        club.setMemberId(ME);
        when(clubService.selectClubByIdForUpdate(9L)).thenReturn(club);
        TopicUpdateDTO dto = fullDto();
        dto.setClubId(9L);
        dto.setOpenClubPool(1);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(dto, ME, "me");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(cap.capture());
        assertEquals(0, cap.getValue().getOpenClubPool(), "俱乐部自办团不进公开承接池");
    }

    /** 非俱乐部主题的编辑语义不动:显式 1 照常开。 */
    @Test
    void independentTopic_editHonorsExplicitOpenClubPool() {
        stubExisting(topic(0, daysFromNow(3)), 1);
        TopicUpdateDTO dto = fullDto();
        dto.setOpenClubPool(1);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(dto, ME, "me");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(cap.capture());
        assertEquals(1, cap.getValue().getOpenClubPool());
    }

    @Test
    void draftEdit_doesNotFlipAuditOrOnlineState() {
        stubExisting(topic(0, daysFromNow(3)), 1);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(fullDto(), ME, "me");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(cap.capture());
        assertNull(cap.getValue().getStatus(), "编辑草稿不动审核态(发布走 publish-draft)");
        assertNull(cap.getValue().getUserStatus(), "编辑草稿不动上架态");
    }

    /**
     * R18-DRAFT:重复保存草稿不得触发立项审 —— 原来「带 recruitDeadline 即送审」对草稿等于
     * 一次保存就把主题推进 RECRUITING(商业轨道),草稿应该是作者点了发布才走审核。
     */
    @Test
    void draftSave_neverSubmitsCommercialReview() {
        stubExisting(topic(0, daysFromNow(3)), 1);
        ITopicCommercialReviewService reviewService = mock(ITopicCommercialReviewService.class);
        ReflectionTestUtils.setField(svc, "topicCommercialReviewService", reviewService);
        TopicUpdateDTO dto = fullDto();
        dto.setRecruitDeadline(daysFromNow(2));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(dto, ME, "me");

        verify(reviewService, never()).submitProjectReview(anyLong(), anyLong(), any());
    }

    /** 内容status待审、商业lifecycle仍DRAFT且已有收费事实：首次发布须提交立项，不是重提既有立项单。 */
    @Test
    void pendingReviewSave_stillSubmitsCommercialReview() {
        CmsTopic existing = topic(0, daysFromNow(3));
        stubExisting(existing, 0);
        // status=0 describes content review. The business state is independently still DRAFT.
        existing.setLifecycle(com.chengyinhub.business.domain.enums.TopicLifecycleState.DRAFT.getCode());
        ITopicCommercialReviewService reviewService = mock(ITopicCommercialReviewService.class);
        ReflectionTestUtils.setField(svc, "topicCommercialReviewService", reviewService);
        TopicUpdateDTO dto = fullDto();
        dto.setRecruitDeadline(daysFromNow(2));
        ActivityTicketRequest ticket = new ActivityTicketRequest();
        ticket.setName("既有收费票"); ticket.setPrice(new BigDecimal("9.00")); ticket.setTotalStock(10);
        ticket.setStartTime(dto.getStartDate()); ticket.setEndTime(dto.getEndDate());
        dto.setTickets(new ArrayList<>(Collections.singletonList(ticket)));
        com.chengyinhub.business.domain.OmsTicket stored = new com.chengyinhub.business.domain.OmsTicket();
        stored.setId(501L); stored.setMemberId(ME); stored.setOwnerType(1L); stored.setOwnerId(TOPIC);
        stored.setName(ticket.getName()); stored.setPrice(ticket.getPrice()); stored.setDelFlag(0);
        stored.setStartTime(ticket.getStartTime()); stored.setEndTime(ticket.getEndTime());
        stored.setTotalInventory(10L); stored.setRemainingInventory(10L);
        when(ticketMapper.selectOmsTicketList(any())).thenAnswer(call -> {
            com.chengyinhub.business.domain.OmsTicket query = call.getArgument(0);
            return Long.valueOf(1L).equals(query.getOwnerType()) && TOPIC.equals(query.getOwnerId())
                    ? Collections.singletonList(stored) : Collections.emptyList();
        });
        when(ticketMapper.updateTopicTicketOnRebuild(any())).thenAnswer(call -> {
            com.chengyinhub.business.domain.OmsTicket patch = call.getArgument(0);
            if (!stored.getId().equals(patch.getId()) || !TOPIC.equals(patch.getOwnerId())
                    || !Long.valueOf(1L).equals(patch.getOwnerType())) return 0;
            org.springframework.beans.BeanUtils.copyProperties(patch,stored);
            return 1;
        });
        assertTrue(commercialTopicPolicy.evaluate(TOPIC).isCommercial(), "已有真实收费票，不能把本次编辑视为免费转商业");
        org.junit.jupiter.api.Assertions.assertFalse(commercialTopicPolicy.isConversionProtected(existing));

        svc.updateTopicByOwner(dto, ME, "me");

        assertTrue(commercialTopicPolicy.evaluate(TOPIC).isCommercial());
        assertEquals(501L,stored.getId());
        verify(ticketMapper,never()).insertOmsTicket(any());
        verify(reviewService).submitProjectReview(eq(TOPIC), eq(ME),
                eq(com.chengyinhub.business.domain.enums.TopicLifecycleState.RECRUITING));
    }

    /**
     * R18-DRAFT:保存草稿不得建自办活动壳。{@code generateSelfHostActivity} 落的是
     * status=1/publish_status=1 的活动 —— 第一次保存草稿就建,等于草稿已经公开可下单。
     */
    @Test
    void draftSave_neverBuildsSelfHostActivityShell() {
        stubExisting(topic(0, daysFromNow(3)), 1);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(fullDto(), ME, "me");

        verify(activityMapper, never()).insertCmsActivity(any());
    }

    /** 旧待审主题重新发布后立即生成可玩的活动。 */
    @Test
    void pendingSave_publishesSelfHostActivityShell() {
        stubExisting(topic(0, daysFromNow(3)), 0);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(fullDto(), ME, "me");

        verify(activityMapper).insertCmsActivity(any());
    }

    /** 驳回后编辑重发，重新上架并送后审。 */
    @Test
    void rejectedSave_republishesSelfHostActivityShell() {
        stubExisting(topic(2, daysFromNow(3)), 0);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(fullDto(), ME, "me");

        verify(activityMapper).insertCmsActivity(any());
    }

    @Test
    void rejectedTopic_republishesAndResubmitsAudit() {
        stubExisting(topic(2, daysFromNow(3)), 0);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(fullDto(), ME, "me");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper, org.mockito.Mockito.times(2)).updateCmsTopic(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getValue().getStatus(), "编辑重发先上架，后台后审");
        verify(auditTaskService).submit(anyString(), anyLong(), anyLong(), anyString(), anyString());
        verify(topicMapper).publishRouteSnapshot(
                any(Long.class), nullable(String.class), any(Date.class));
    }

    // ---------- R2-B10 主题数值规则 ----------

    /** 带 journeyRules 保存:校验 + 归一化(缺省补齐)后落库。 */
    @Test
    void draftEdit_persistsNormalizedJourneyRules() {
        stubExisting(topic(0, daysFromNow(3)), 1);
        TopicUpdateDTO dto = fullDto();
        dto.setJourneyRules("{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"max\":6}}");

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(dto, ME, "me");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(cap.capture());
        String stored = cap.getValue().getJourneyRulesJson();
        assertNotNull(stored, "配置了就必须落库");
        assertTrue(stored.contains("\"checkEnabled\":true"), stored);
        assertTrue(stored.contains("\"max\":6"), stored);
        assertTrue(stored.contains("\"init\":6"), "缺省 init 必须补齐为 6:" + stored);
    }

    /**
     * 老编辑端整包上送不带 journeyRules ⇒ service 落 null,mapper 的动态 update 跳过这一列
     * ⇒ 库里原值不变(不是被抹成空)。
     */
    @Test
    void draftEdit_withoutJourneyRules_leavesColumnUntouched() {
        stubExisting(topic(0, daysFromNow(3)), 1);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(fullDto(), ME, "me");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(cap.capture());
        assertNull(cap.getValue().getJourneyRulesJson(),
                "不带 journeyRules 必须写 null(动态 SQL 靠它跳过),不能落一份空配置把原值盖掉");
    }

    /** 非法数值规则当场拒,且一个字节都不写库。 */
    @Test
    void draftEdit_illegalJourneyRules_rejectedBeforeAnyWrite() {
        stubExisting(topic(0, daysFromNow(3)), 1);
        TopicUpdateDTO dto = fullDto();
        dto.setJourneyRules("{\"schemaVersion\":1,\"checkEnabled\":true,\"modCap\":11}");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> svc.updateTopicByOwner(dto, ME, "me"));

        assertTrue(error.getMessage().contains("modCap"), error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any());
    }

    // ---------- 权限闸 / 只读闸 ----------
    @Test
    void otherMember_cannotEdit() {
        stubExisting(topic(0, daysFromNow(3)), 1);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> svc.updateTopicByOwner(fullDto(), 999L, "attacker"));

        assertTrue(e.getMessage().contains("无权编辑"), "错误文案要指明是哪道闸,别只断言抛了异常:" + e.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any());
        verify(nodeMapper, never()).deleteCmsTopicNodeById(any());
    }

    /**
     * 模板「使用此模板」那条入口的存活测试。
     *
     * <p>{@code copyTopicBasicInfo} 把 status/user_status 重置成 0,却<b>原样复制 startDate</b>;
     * 模板多半由已办完的主题 save-as 而来 ⇒ 复制出的新草稿一落地就带着过去的日期。
     * 判定顺序若退回「先判日期」,这条入口实现完仍是死的(edit-detail 直接报「主题已开始」),
     * 存量已复制的草稿也全部打不开。
     */
    @Test
    void draftWithPastStartDate_isStillFullyEditable() {
        stubExisting(topic(0, daysFromNow(-30)), 1);

        TopicUpdateDTO dto = fullDto();
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        Long id = svc.updateTopicByOwner(dto, ME, "me");

        assertEquals(TOPIC, id, "从没公开过的草稿,startDate 只是表单上的值,作者进来就是为了改它");
        verify(topicMapper).updateCmsTopic(any());
        verify(chapterMapper).insertCmsTopicChapter(any());
    }

    @Test
    void startedTopic_isReadOnly() {
        stubExisting(topic(1, daysFromNow(-1)), 0);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> svc.updateTopicByOwner(whitelistOnlyDto(), ME, "me"));

        assertTrue(e.getMessage().contains("已开始"), "应命中「已开始」只读闸,实际:" + e.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any());
    }

    @Test
    void paidRegistration_blocksStructuralRebuild() {
        stubExisting(topic(2, daysFromNow(3)), 0);
        CmsRegistration paid = new CmsRegistration();
        when(registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(paid));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> svc.updateTopicByOwner(fullDto(), ME, "me"));

        assertTrue(e.getMessage().contains("报名"), "钱在场必须拦住物理重建,实际:" + e.getMessage());
        verify(nodeMapper, never()).deleteCmsTopicNodeById(any());
        verify(ticketMapper, never()).deleteOmsTicketById(any());
    }

    @Test
    void approvedWhitelistRejectsUnknownOrOwnHeldMediaBeforeAnyWrite() {
        CmsTopic online = topic(1, daysFromNow(3)); online.setUserStatus(1);
        stubExisting(online, 0);
        com.chengyinhub.business.service.support.AppMediaPublicationGuard publication =
                (com.chengyinhub.business.service.support.AppMediaPublicationGuard) ReflectionTestUtils.getField(svc,"mediaPublicationGuard");
        com.chengyinhub.business.service.support.AppMediaReferenceGuard references =
                (com.chengyinhub.business.service.support.AppMediaReferenceGuard) ReflectionTestUtils.getField(publication,"references");
        com.chengyinhub.business.mapper.AppMediaAssetMapper media =
                (com.chengyinhub.business.mapper.AppMediaAssetMapper) ReflectionTestUtils.getField(references,"mapper");
        when(media.select(99L)).thenReturn(java.util.Map.of("id",99L,"memberId",ME,"ownerType","APP",
                "reviewState","HOLD","mediaType","IMAGE"));
        for (String image : new String[]{"https://unregistered/image.jpg","media-asset:99"}) {
            TopicUpdateDTO request = whitelistOnlyDto(); request.setImgUrl(image);
            assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                    () -> svc.updateTopicByOwner(request,ME,"owner"));
        }
        verify(topicMapper,never()).updateCmsTopic(any());
    }

    @Test
    void foreignWhitelistOwnerIsRejectedBeforeMediaLookupOrWrite() {
        CmsTopic online = topic(1, daysFromNow(3)); online.setUserStatus(1);
        stubExisting(online, 0);
        Object publication = ReflectionTestUtils.getField(svc,"mediaPublicationGuard");
        Object references = ReflectionTestUtils.getField(publication,"references");
        com.chengyinhub.business.mapper.AppMediaAssetMapper media =
                (com.chengyinhub.business.mapper.AppMediaAssetMapper) ReflectionTestUtils.getField(references,"mapper");
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> svc.updateTopicByOwner(whitelistOnlyDto(),999L,"foreign"));
        assertEquals("无权编辑该主题",error.getMessage());
        org.mockito.Mockito.verifyNoInteractions(media);
        verify(topicMapper,never()).updateCmsTopic(any());
    }

    // ---------- 档 2:已过审开卖 只许白名单 ----------

    @Test
    void approvedTopic_appliesWhitelistTextAndImagesOnly() {
        CmsTopic online = topic(1, daysFromNow(3));
        online.setUserStatus(1);
        stubExisting(online, 0);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(whitelistOnlyDto(), ME, "me");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(cap.capture());
        CmsTopic patch = cap.getValue();
        assertEquals("新名字", patch.getName());
        assertEquals("新副标题", patch.getSubtitle());
        assertEquals("新简介", patch.getDescription());
        assertEquals("media-asset:1", patch.getImgUrl());
        assertEquals("media-asset:1;media-asset:3", patch.getImgArr());
        assertEquals("3,4", patch.getCategoryIds());
        // 白名单档不许碰结构/票:mapper 的 <if test="x != null"> 靠 null 跳过该列
        assertNull(patch.getStartDate(), "白名单档不得写日期");
        assertNull(patch.getEndDate(), "白名单档不得写日期");
        verify(chapterMapper, never()).deleteCmsTopicChapterById(any());
        verify(nodeMapper, never()).insertCmsTopicNode(any());
        verify(ticketMapper, never()).insertOmsTicket(any());
    }

    @Test
    void approvedTopic_nullRecruitDeadline_rejectsNewDeadlineAndChangesNothing() {
        CmsTopic online = topic(1, daysFromNow(3));
        online.setUserStatus(1);
        stubExisting(online, 0);
        TopicUpdateDTO dto = whitelistOnlyDto();
        dto.setRecruitDeadline(daysFromNow(2));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> svc.updateTopicByOwner(dto, ME, "me"));

        assertTrue(error.getMessage().contains("招募截止时间"),
                "拒绝信息要指明招募截止被锁定:" + error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any());
    }

    @Test
    void approvedTopic_rejectsChangedRecruitDeadlineAndChangesNothing() {
        CmsTopic online = topic(1, daysFromNow(3));
        online.setUserStatus(1);
        online.setRecruitDeadline(daysFromNow(2));
        stubExisting(online, 0);
        TopicUpdateDTO dto = whitelistOnlyDto();
        dto.setRecruitDeadline(daysFromNow(4));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> svc.updateTopicByOwner(dto, ME, "me"));

        assertTrue(error.getMessage().contains("招募截止时间"),
                "拒绝信息要指明招募截止被锁定:" + error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any());
    }

    @Test
    void approvedTopic_sameRecruitDeadline_allowsWhitelistTextUpdate() {
        Date deadline = daysFromNow(2);
        CmsTopic online = topic(1, daysFromNow(3));
        online.setUserStatus(1);
        online.setRecruitDeadline(deadline);
        stubExisting(online, 0);
        TopicUpdateDTO dto = whitelistOnlyDto();
        dto.setRecruitDeadline(new Date(deadline.getTime()));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(dto, ME, "me");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(cap.capture());
        assertEquals("新名字", cap.getValue().getName());
        assertNull(cap.getValue().getRecruitDeadline(),
                "相同截止只用于比较，白名单 patch 不应重写锁定字段");
    }

    @Test
    void approvedTopic_omittedRecruitDeadline_keepsPatchSemantics() {
        CmsTopic online = topic(1, daysFromNow(3));
        online.setUserStatus(1);
        online.setRecruitDeadline(daysFromNow(2));
        stubExisting(online, 0);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(whitelistOnlyDto(), ME, "me");

        ArgumentCaptor<CmsTopic> cap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(cap.capture());
        assertEquals("新名字", cap.getValue().getName());
        assertNull(cap.getValue().getRecruitDeadline(),
                "缺省 null 继续表示不改原值，白名单 patch 不应回写截止时间");
    }

    @Test
    void approvedTopic_rejectsDateChange_andChangesNothing() {
        stubExisting(topic(1, daysFromNow(3)), 0);
        TopicUpdateDTO dto = whitelistOnlyDto();
        dto.setStartDate(daysFromNow(9));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> svc.updateTopicByOwner(dto, ME, "me"));

        assertTrue(e.getMessage().contains("开始/结束日期"), "拒绝信息要指名是日期被拦:" + e.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any());
    }

    @Test
    void approvedTopic_rejectsChapterStructureChange_andChangesNothing() {
        stubExisting(topic(1, daysFromNow(3)), 0);
        TopicUpdateDTO dto = whitelistOnlyDto();
        ChapterDTO chapter = new ChapterDTO();
        chapter.setName("偷加一章");
        chapter.setNodes(new ArrayList<NodeDTO>());
        dto.setChapters(new ArrayList<>(Arrays.asList(chapter)));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> svc.updateTopicByOwner(dto, ME, "me"));

        assertTrue(e.getMessage().contains("章节站点结构"), "拒绝信息要指名是结构被拦:" + e.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any());
        verify(chapterMapper, never()).insertCmsTopicChapter(any());
        verify(chapterMapper, never()).deleteCmsTopicChapterById(any());
        verify(nodeMapper, never()).deleteCmsTopicNodeById(any());
    }

    @Test
    void approvedTopic_rejectsTicketPriceChange_andLeavesTicketsUntouched() {
        stubExisting(topic(1, daysFromNow(3)), 0);
        TopicUpdateDTO dto = whitelistOnlyDto();
        ActivityTicketRequest ticket = new ActivityTicketRequest();
        ticket.setPrice(new BigDecimal("0.01"));
        dto.setTickets(new ArrayList<>(Arrays.asList(ticket)));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> svc.updateTopicByOwner(dto, ME, "me"));

        assertTrue(e.getMessage().contains("票种与价格"), "拒绝信息要指名是票价被拦:" + e.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any());
        verify(ticketMapper, never()).insertOmsTicket(any());
        verify(ticketMapper, never()).deleteOmsTicketById(any());
        // 活动票是玩家真正买到的那份,一根汗毛都不能动
        verify(omsTicketService, never()).copyTopicTicketsToActivity(any(), any(), any());
    }

    @Test
    void approvedTopic_listsEveryLockedFieldItRejects() {
        stubExisting(topic(1, daysFromNow(3)), 0);
        TopicUpdateDTO dto = whitelistOnlyDto();
        dto.setEndDate(daysFromNow(9));
        ChapterDTO chapter = new ChapterDTO();
        chapter.setNodes(new ArrayList<NodeDTO>());
        dto.setChapters(new ArrayList<>(Arrays.asList(chapter)));
        dto.setTickets(new ArrayList<>(Arrays.asList(new ActivityTicketRequest())));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> svc.updateTopicByOwner(dto, ME, "me"));

        String msg = e.getMessage();
        assertTrue(msg.contains("开始/结束日期") && msg.contains("章节站点结构") && msg.contains("票种与价格"),
                "三类锁定字段要一次报全,别让用户改一次撞一次:" + msg);
    }

    // ---------- 参数闸 ----------

    @Test
    void missingId_rejected() {
        TopicUpdateDTO dto = whitelistOnlyDto();
        dto.setId(null);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        assertThrows(RuntimeException.class, () -> svc.updateTopicByOwner(dto, ME, "me"));
        verify(topicMapper, never()).selectCmsTopicById(any());
    }

    @Test
    void deletedTopic_rejected() {
        CmsTopic gone = topic(0, daysFromNow(3));
        gone.setDelFlag(2);
        stubExisting(gone, 1);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> svc.updateTopicByOwner(fullDto(), ME, "me"));
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
    }

    /** 结构重建必须先清干净旧子表,否则「编辑」变成「往上叠」。 */
    @Test
    void fullRebuild_clearsOldChildrenFirst() {
        stubExisting(topic(0, daysFromNow(3)), 1);
        com.chengyinhub.business.domain.CmsTopicNode oldNode = new com.chengyinhub.business.domain.CmsTopicNode();
        oldNode.setId(11L);
        com.chengyinhub.business.domain.CmsTopicChapter oldChapter = new com.chengyinhub.business.domain.CmsTopicChapter();
        oldChapter.setId(21L);
        OmsTicket oldTicket = new OmsTicket();
        oldTicket.setId(31L);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(oldNode));
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(oldChapter));
        when(ticketMapper.selectOmsTicketList(any())).thenReturn(Collections.singletonList(oldTicket));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(fullDto(), ME, "me");

        verify(nodeMapper).deleteCmsTopicNodeById(11L);
        verify(chapterMapper).deleteCmsTopicChapterById(21L);
        verify(ticketMapper).deleteOmsTicketById(31L);
    }

    @Test
    void legacyEditWithoutRouteFieldsRemapsExistingBranchGraphToRebuiltNodeIds() {
        CmsTopic existing = topic(0, daysFromNow(3));
        existing.setProductType(ProductType.ORIENTEERING);
        existing.setRouteMode("BRANCH_GRAPH");
        existing.setConfigVersion(4L);
        existing.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":11," +
                "\"terminalNodeIds\":[12],\"edges\":[{\"id\":\"go\"," +
                "\"fromNodeId\":11,\"toNodeId\":12," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}");
        stubExisting(existing, 1);

        TopicUpdateDTO dto = fullDto();
        NodeDTO first = dto.getChapters().get(0).getNodes().get(0);
        first.setId(11L);
        NodeDTO second = new NodeDTO();
        second.setId(12L);
        second.setName("第二站");
        second.setAddress("外滩");
        second.setNodeTime(30);
        dto.getChapters().get(0).getNodes().add(second);
        assertNull(dto.getRouteMode());
        assertNull(dto.getRouteGraphJson());
        assertNull(dto.getConfigVersion());

        com.chengyinhub.business.domain.CmsTopicNode oldFirst = new com.chengyinhub.business.domain.CmsTopicNode();
        oldFirst.setId(11L);
        com.chengyinhub.business.domain.CmsTopicNode oldSecond = new com.chengyinhub.business.domain.CmsTopicNode();
        oldSecond.setId(12L);
        com.chengyinhub.business.domain.CmsTopicNode newFirst = new com.chengyinhub.business.domain.CmsTopicNode();
        newFirst.setId(101L);
        com.chengyinhub.business.domain.CmsTopicNode newSecond = new com.chengyinhub.business.domain.CmsTopicNode();
        newSecond.setId(102L);
        when(nodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Arrays.asList(oldFirst, oldSecond), Arrays.asList(newFirst, newSecond));
        final long[] insertedId = {101L};
        when(nodeMapper.insertCmsTopicNode(any())).thenAnswer(invocation -> {
            ((com.chengyinhub.business.domain.CmsTopicNode) invocation.getArgument(0))
                    .setId(insertedId[0]++);
            return 1;
        });
        ReflectionTestUtils.setField(svc, "cmsMemberTemplateMapper",
                mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(svc, "distanceCalculatorUtils",
                mock(com.chengyinhub.common.utils.DistanceCalculatorUtils.class));
        when(topicMapper.updateRouteDefinition(eq(TOPIC), eq("BRANCH_GRAPH"), anyString(),
                eq(4L), eq(5L), any(Date.class))).thenReturn(1);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(dto, ME, "me");

        ArgumentCaptor<String> graph = ArgumentCaptor.forClass(String.class);
        verify(topicMapper).updateRouteDefinition(eq(TOPIC), eq("BRANCH_GRAPH"), graph.capture(),
                eq(4L), eq(5L), any(Date.class));
        assertTrue(graph.getValue().contains("\"startNodeId\":101"), graph.getValue());
        assertTrue(graph.getValue().contains("\"terminalNodeIds\":[102]"), graph.getValue());
        assertTrue(graph.getValue().contains("\"fromNodeId\":101"), graph.getValue());
        assertTrue(graph.getValue().contains("\"toNodeId\":102"), graph.getValue());
    }

    /**
     * SC-UI-DATE-COUNT:后台「隐藏」(status=0)的节点经作者整体替换保存后仍是隐藏。
     * 重建会物理删节点再插回;原来插回一律写 status=1 ⇒ 作者重存一次就把后台隐藏的节点放回玩家侧。
     * 按 NodeDTO.id(编辑器回传的既有节点 id)对回旧行:旧行隐藏 ⇒ 新行仍隐藏;可见旧行与新节点照旧是 1。
     */
    @Test
    void fullRebuild_keepsBackOfficeHiddenNodeHidden_andLeavesVisibleAndNewNodesVisible() {
        stubExisting(topic(0, daysFromNow(3)), 1);
        java.util.Map<Long, com.chengyinhub.business.domain.CmsTopicNode> table = new java.util.LinkedHashMap<>();
        table.put(11L, storedNode(11L, 1L));
        table.put(12L, storedNode(12L, 0L));
        java.util.function.Supplier<java.util.List<com.chengyinhub.business.domain.CmsTopicNode>> rows =
                () -> new ArrayList<>(table.values());
        when(nodeMapper.selectCmsTopicNodeList(any())).thenAnswer(call -> rows.get());
        when(nodeMapper.selectByTopicForUpdate(TOPIC)).thenAnswer(call -> rows.get());
        when(nodeMapper.deleteCmsTopicNodeById(anyLong())).thenAnswer(call -> table.remove((Long) call.getArgument(0)) == null ? 0 : 1);
        final long[] nextId = {101L};
        when(nodeMapper.insertCmsTopicNode(any())).thenAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicNode n = call.getArgument(0);
            n.setId(nextId[0]++);
            table.put(n.getId(), n);
            return 1;
        });
        when(nodeMapper.updateCmsTopicNode(any())).thenAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicNode patch = call.getArgument(0);
            com.chengyinhub.business.domain.CmsTopicNode row = table.get(patch.getId());
            if (row == null) return 0;
            if (patch.getStatus() != null) row.setStatus(patch.getStatus());
            return 1;
        });
        ReflectionTestUtils.setField(svc, "cmsMemberTemplateMapper",
                mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(svc, "distanceCalculatorUtils",
                mock(com.chengyinhub.common.utils.DistanceCalculatorUtils.class));

        TopicUpdateDTO dto = fullDto();
        java.util.List<NodeDTO> nodes = dto.getChapters().get(0).getNodes();
        nodes.get(0).setId(11L);
        nodes.add(nodeDto(12L, "隐藏站"));
        nodes.add(nodeDto(null, "新站"));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(dto, ME, "me");

        java.util.Map<String, Long> statusByName = new java.util.LinkedHashMap<>();
        for (com.chengyinhub.business.domain.CmsTopicNode n : table.values()) statusByName.put(n.getName(), n.getStatus());
        java.util.Map<String, Long> expected = new java.util.LinkedHashMap<>();
        expected.put("第一站", 1L);
        expected.put("隐藏站", 0L);
        expected.put("新站", 1L);
        assertEquals(expected, statusByName, "作者重存不能把后台隐藏的节点放回;可见节点与新节点照旧上架");
    }

    private static com.chengyinhub.business.domain.CmsTopicNode storedNode(Long id, Long status) {
        com.chengyinhub.business.domain.CmsTopicNode n = new com.chengyinhub.business.domain.CmsTopicNode();
        n.setId(id);
        n.setTopicId(TOPIC);
        n.setStatus(status);
        return n;
    }

    private static NodeDTO nodeDto(Long id, String name) {
        NodeDTO n = new NodeDTO();
        n.setId(id);
        n.setName(name);
        n.setAddress("外滩");
        n.setNodeTime(30);
        return n;
    }

    // ---------- D1:整体替换契约 —— 局部 body 不许静默清空子结构 ----------

    /**
     * 2026-08-18 真跑复现:对 990016 调 {@code POST /api/topic/update},body 只有
     * {@code {"id":990016,"openMerchantPool":1}} → 响应「保存成功」,库里 4 章 4 节点被清成 0/0,
     * 同期未被动过的 990015 仍是 4+4 作对照。整条链路零报错、零告警。
     *
     * <p>断言的是「子结构没有被清空」(一次 delete 都不许发),不是接口返回码。
     */
    @Test
    void partialBody_doesNotSilentlyWipeChapters() {
        stubExisting(topic(0, daysFromNow(3)), 1);
        com.chengyinhub.business.domain.CmsTopicNode oldNode = new com.chengyinhub.business.domain.CmsTopicNode();
        oldNode.setId(11L);
        com.chengyinhub.business.domain.CmsTopicChapter oldChapter = new com.chengyinhub.business.domain.CmsTopicChapter();
        oldChapter.setId(21L);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(oldNode));
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(oldChapter));

        TopicUpdateDTO partial = new TopicUpdateDTO();
        partial.setId(TOPIC);
        partial.setOpenMerchantPool(1);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> svc.updateTopicByOwner(partial, ME, "me"));

        assertTrue(e.getMessage().contains("chapters"),
                "拒绝文案必须点名缺的是哪个字段,否则调用方仍然不知道该补什么:" + e.getMessage());
        // 这三条才是本缺陷的本体:一次 delete 都不许发生
        verify(nodeMapper, never()).deleteCmsTopicNodeById(any());
        verify(chapterMapper, never()).deleteCmsTopicChapterById(any());
        verify(topicMapper, never()).updateCmsTopic(any());
    }

    /** 票 / 合作者 / 彩蛋是同一个删了重建的口子,缺席同样拒收,且一次报全。 */
    @Test
    void partialBody_alsoProtectsTicketsCollaboratorsAndEggs() {
        stubExisting(topic(0, daysFromNow(3)), 1);
        OmsTicket oldTicket = new OmsTicket();
        oldTicket.setId(31L);
        when(ticketMapper.selectOmsTicketList(any())).thenReturn(Collections.singletonList(oldTicket));
        com.chengyinhub.business.domain.CmsCollaborators oldCollab =
                new com.chengyinhub.business.domain.CmsCollaborators();
        oldCollab.setId(41L);
        when(collaboratorsMapper.selectCmsCollaboratorsList(any()))
                .thenReturn(Collections.singletonList(oldCollab));

        TopicUpdateDTO partial = new TopicUpdateDTO();
        partial.setId(TOPIC);
        partial.setChapters(new ArrayList<ChapterDTO>());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> svc.updateTopicByOwner(partial, ME, "me"));

        String msg = e.getMessage();
        assertTrue(msg.contains("tickets") && msg.contains("collaboratorIds"),
                "缺的子集合要一次报全,别让用户补一个撞一个:" + msg);
        verify(ticketMapper, never()).deleteOmsTicketById(any());
        verify(collaboratorsMapper, never()).deleteCmsCollaboratorsById(any());
    }

    /**
     * 判据是「字段缺席(null)」不是「空列表」—— 显式传 {@code []} 就是「我要清空」,照办。
     * 少了这条,闸会把「清空最后一章」这个合法操作也一起堵死。
     */
    @Test
    void explicitEmptyArray_isHonoredAsDeliberateClear() {
        stubExisting(topic(0, daysFromNow(3)), 1);
        com.chengyinhub.business.domain.CmsTopicChapter oldChapter = new com.chengyinhub.business.domain.CmsTopicChapter();
        oldChapter.setId(21L);
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(oldChapter));

        TopicUpdateDTO explicit = new TopicUpdateDTO();
        explicit.setId(TOPIC);
        explicit.setChapters(new ArrayList<ChapterDTO>());
        explicit.setTickets(new ArrayList<ActivityTicketRequest>());
        explicit.setCollaboratorIds(new ArrayList<Long>());

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        svc.updateTopicByOwner(explicit, ME, "me");

        verify(chapterMapper).deleteCmsTopicChapterById(21L);
        verify(topicMapper).updateCmsTopic(any());
    }
}

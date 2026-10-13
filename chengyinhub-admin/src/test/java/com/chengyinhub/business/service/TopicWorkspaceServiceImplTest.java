package com.chengyinhub.business.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CoopClubApply;
import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.dto.TopicWorkspaceDTO;
import com.chengyinhub.common.constant.RoleEntityType;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CoopClubApplyMapper;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IMerchantOfferService;
import com.chengyinhub.business.service.impl.TopicWorkspaceServiceImpl;
import com.chengyinhub.business.service.impl.TopicSettlementEvidenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicWorkspaceServiceImplTest {

    private static final Long TOPIC_ID = 71L;

    private ICmsTopicService topicService;
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;
    private ICmsTopicChapterService chapterService;
    private ICmsTopicNodeService nodeService;
    private IOmsTicketService ticketService;
    private ICmsRegistrationMerchantService candidateService;
    private IProductRoleService productRoleService;
    private MmsMerchantMapper merchantMapper;
    private CmsRegistrationMapper registrationMapper;
    private CoopClubApplyMapper clubApplyMapper;
    private CoopInviteMapper inviteMapper;
    private CoopOrderMapper orderMapper;
    private CoopGroupVerifyMapper groupVerifyMapper;
    private CoopSettlementMapper settlementMapper;
    private CoopComplaintMapper complaintMapper;
    private ClubLeadMapper clubLeadMapper;
    private ClubMapper clubMapper;
    private UmsMemberMapper memberMapper;
    private IMerchantOfferService merchantOfferService;
    private TopicSettlementEvidenceService settlementEvidenceService;
    private TopicSettlementEvidenceService.Summary topicEvidence;
    private TopicWorkspaceServiceImpl service;

    @BeforeEach
    void setUp() {
        topicService = mock(ICmsTopicService.class);
        topicOperationsScopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        chapterService = mock(ICmsTopicChapterService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        ticketService = mock(IOmsTicketService.class);
        candidateService = mock(ICmsRegistrationMerchantService.class);
        productRoleService = mock(IProductRoleService.class);
        merchantMapper = mock(MmsMerchantMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);
        clubApplyMapper = mock(CoopClubApplyMapper.class);
        inviteMapper = mock(CoopInviteMapper.class);
        orderMapper = mock(CoopOrderMapper.class);
        groupVerifyMapper = mock(CoopGroupVerifyMapper.class);
        settlementMapper = mock(CoopSettlementMapper.class);
        complaintMapper = mock(CoopComplaintMapper.class);
        clubLeadMapper = mock(ClubLeadMapper.class);
        clubMapper = mock(ClubMapper.class);
        memberMapper = mock(UmsMemberMapper.class);
        merchantOfferService = mock(IMerchantOfferService.class);
        settlementEvidenceService = mock(TopicSettlementEvidenceService.class);
        topicEvidence = mock(TopicSettlementEvidenceService.Summary.class);
        when(settlementEvidenceService.readTopic(TOPIC_ID)).thenReturn(topicEvidence);
        when(topicOperationsScopeAuthorizer.canViewTopic(TOPIC_ID)).thenReturn(true);
        service = new TopicWorkspaceServiceImpl(topicService, chapterService, nodeService, ticketService,
                candidateService, productRoleService, merchantMapper, registrationMapper,
                clubApplyMapper, inviteMapper, orderMapper, groupVerifyMapper, settlementMapper, complaintMapper,
                clubLeadMapper, clubMapper, memberMapper, merchantOfferService, topicOperationsScopeAuthorizer,
                settlementEvidenceService);
    }

    @Test
    void missingTopicStopsBeforeReadingDependentTables() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(null);

        assertNull(service.getWorkspace(TOPIC_ID));
        verify(productRoleService, never()).activeContentSlot(any());
        verify(candidateService, never()).selectCmsRegistrationMerchantList(any());
    }

    @Test
    void missingTopicScopeStopsBeforeReadingAnyTopicData() {
        when(topicOperationsScopeAuthorizer.canViewTopic(TOPIC_ID)).thenReturn(false);

        assertNull(service.getWorkspace(TOPIC_ID));
        verify(topicService, never()).selectCmsTopicById(any());
        verify(productRoleService, never()).activeContentSlot(any());
        verify(candidateService, never()).selectCmsRegistrationMerchantList(any());
    }

    @Test
    void workspaceProjectsContentPublisherOrMarksHistoricalPending() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        ProductRole contentSlot = new ProductRole();
        contentSlot.setEntityType(RoleEntityType.CLUB);
        contentSlot.setEntityId(701L);
        when(productRoleService.activeContentSlot(TOPIC_ID)).thenReturn(contentSlot);
        Club publisherClub = new Club();
        publisherClub.setId(701L);
        publisherClub.setName("梧桐夜游社");
        when(clubMapper.selectClubById(701L)).thenReturn(publisherClub);

        TopicWorkspaceDTO workspace = service.getWorkspace(TOPIC_ID);

        assertEquals(RoleEntityType.CLUB, workspace.getPublisher().getEntityType());
        assertEquals("梧桐夜游社", workspace.getPublisher().getDisplayName());
        assertThrows(NoSuchMethodException.class,
                () -> TopicWorkspaceDTO.Publisher.class.getMethod("getEntityId"),
                "主题工作台不应向普通查询权限暴露内容主体内部标识");
        assertFalse(workspace.getPublisher().isHistoricalPending());
        assertEquals(TopicWorkspaceDTO.OfficialActivityRelationState.UNLINKED,
                workspace.getOfficialActivityRelation().getState());
        assertEquals("未关联官方活动", workspace.getOfficialActivityRelation().getMessage());

        when(productRoleService.activeContentSlot(TOPIC_ID)).thenReturn(null);
        TopicWorkspaceDTO historical = service.getWorkspace(TOPIC_ID);
        assertTrue(historical.getPublisher().isHistoricalPending());
        assertNull(historical.getPublisher().getEntityType());
    }

    @Test
    void workspaceProjectsOperationGovernanceAndMerchantSupply() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setProductType(1);
        topic.setLifecycle(3);
        topic.setMerchantStatus(1);
        topic.setOpenClubPool(0);
        topic.setSelfPlay(0);
        topic.setMerchantSignUpStartDate(new Date(1700000000000L));
        topic.setMerchantSignUpEndDate(new Date(1700100000000L));
        topic.setCheckStatus(1);
        topic.setUserStatus(1);
        topic.setReason("审核通过");
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        CmsRegistrationMerchant pending = new CmsRegistrationMerchant();
        pending.setStatus(0L);
        pending.setAuditStatus(0L);
        CmsRegistrationMerchant awarded = new CmsRegistrationMerchant();
        awarded.setStatus(1L);
        awarded.setAuditStatus(1L);
        when(candidateService.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Arrays.asList(pending, awarded));
        OmsTicket guidedTicket = new OmsTicket();
        guidedTicket.setMode(1);
        guidedTicket.setSubType("guided");
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.singletonList(guidedTicket));

        TopicWorkspaceDTO workspace = service.getWorkspace(TOPIC_ID);

        assertEquals(1, workspace.getOperation().getProductType());
        assertEquals(3, workspace.getOperation().getLifecycle());
        assertEquals(1, workspace.getOperation().getMerchantPoolStatus());
        assertEquals(0, workspace.getOperation().getClubPoolStatus());
        assertEquals(1, workspace.getGovernance().getCheckStatus());
        assertEquals(1, workspace.getGovernance().getUserStatus());
        assertEquals("审核通过", workspace.getGovernance().getReason());
        assertEquals(2, workspace.getMerchantSupply().getCandidateCount());
        assertEquals(1, workspace.getMerchantSupply().getPendingReviewCount());
        assertEquals(1, workspace.getMerchantSupply().getApprovedCount());
        assertEquals(1, workspace.getMerchantSupply().getAwardedCount());
        assertTrue(workspace.getOperation().getRunBlockers()
                        .contains(TopicWorkspaceDTO.RunBlocker.CLUB_FULFILLMENT_UNCONFIRMED),
                "俱乐部公开池关闭不等于明确自办；无签约俱乐部时必须阻塞可开跑结论");
    }


    @Test
    void terminalLifecycleTakesPrecedenceOverStaleRunBlockers() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setLifecycle(5);
        topic.setSelfPlay(0);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.<OmsTicket>emptyList());

        TopicWorkspaceDTO settled = service.getWorkspace(TOPIC_ID);

        assertTrue(settled.getOperation().getRunBlockers()
                .contains(TopicWorkspaceDTO.RunBlocker.TICKET_CONFIGURATION_MISSING));
        assertEquals(TopicWorkspaceDTO.NextAction.SETTLE_AND_REVIEW, settled.getOperation().getNextAction());

        topic.setLifecycle(6);
        TopicWorkspaceDTO closed = service.getWorkspace(TOPIC_ID);

        assertEquals(TopicWorkspaceDTO.NextAction.VIEW_REVIEW, closed.getOperation().getNextAction());
    }

    @Test
    void workspacePreservesSuccessfulEmptyProjectionsAsAvailable() throws Exception {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        TopicWorkspaceDTO workspace = service.getWorkspace(TOPIC_ID);

        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getAudit().getState());
        assertNotNull(workspace.getGovernance());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getStructure().getState());
        assertTrue(workspace.getChapters().isEmpty());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getTickets().getState());
        assertTrue(workspace.getTickets().isEmpty());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getCandidates().getState());
        assertTrue(workspace.getCandidates().isEmpty());
        assertNotNull(workspace.getMerchantSupply());
        assertEquals(0, workspace.getMerchantSupply().getCandidateCount());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getFulfillment().getState());
        assertNotNull(workspace.getFulfillment());
        assertEquals(BigDecimal.ZERO, workspace.getFulfillment().getTotalSales());
        assertEquals(0, workspace.getFulfillment().getVerifiedHeads());
        assertEquals(BigDecimal.ZERO, workspace.getFulfillment().getVerifiedSales());

        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getMerchantSupply().getState());
        assertTrue(workspace.getMerchantSupply().getChapters().isEmpty());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getClubFulfillment().getState());
        assertTrue(workspace.getClubFulfillment().getRelationships().isEmpty());
        assertTrue(workspace.getClubFulfillment().getSessions().isEmpty());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getCooperation().getState());
        assertTrue(workspace.getCooperations().isEmpty());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getSettlement().getState());
        assertTrue(workspace.getSettlements().isEmpty());
        assertUnavailableAndOmitted(workspace.getCapabilities().getRoam(),
                "ROAM_RELATION_UNDEFINED", null);

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode responseBody = objectMapper.readTree(objectMapper.writeValueAsString(workspace));
        assertTrue(responseBody.path("governance").isObject());
        assertTrue(responseBody.path("chapters").isArray());
        assertTrue(responseBody.path("tickets").isArray());
        assertTrue(responseBody.path("candidates").isArray());
        assertTrue(responseBody.path("merchantSupply").isObject());
        assertTrue(responseBody.path("clubFulfillment").isObject());
        assertTrue(responseBody.path("fulfillment").isObject());
        assertTrue(responseBody.path("cooperations").isArray());
        assertTrue(responseBody.path("settlements").isArray());
    }

    @Test
    void aggregatesRealTopicOperationsDataWithoutLeakingCandidatePii() throws Exception {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setName("夜游梧桐区");
        topic.setCheckStatus(1);
        topic.setUserStatus(1);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(11L);
        chapter.setTopicId(TOPIC_ID);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(21L);
        node.setTopicId(TOPIC_ID);
        node.setChapterId(11L);
        when(chapterService.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        OmsTicket ticket = new OmsTicket();
        ticket.setId(31L);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.singletonList(ticket));

        CmsRegistrationMerchant candidate = new CmsRegistrationMerchant();
        candidate.setId(41L);
        candidate.setTopicId(TOPIC_ID);
        candidate.setMemberId(401L);
        candidate.setNodeId(21L);
        candidate.setTemplateId(51L);
        candidate.setAddressName("武康大楼");
        candidate.setEmail("should-not-leak@example.com");
        candidate.setAuditStatus(1L);
        when(candidateService.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(candidate));
        MmsMerchant merchant = new MmsMerchant();
        merchant.setName("梧桐咖啡");
        merchant.setMemberId(401L);
        when(merchantMapper.selectMerchantNamesByMemberIds(any())).thenReturn(Collections.singletonList(merchant));

        when(topicEvidence.getTotalSales()).thenReturn(new BigDecimal("580.00"));
        when(topicEvidence.getVerifiedHeads()).thenReturn(12);
        when(topicEvidence.getVerifiedSales()).thenReturn(new BigDecimal("320.00"));
        when(complaintMapper.countOpenByTopicId(TOPIC_ID)).thenReturn(2);

        TopicWorkspaceDTO workspace = service.getWorkspace(TOPIC_ID);

        assertEquals("夜游梧桐区", workspace.getTopic().getName());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getAudit().getState());
        assertNotNull(workspace.getGovernance());
        assertEquals(1, workspace.getChapters().size());
        assertEquals(1, workspace.getChapters().get(0).getNodes().size());
        assertEquals(1, workspace.getTickets().size());
        assertEquals("梧桐咖啡", workspace.getCandidates().get(0).getMerchantName());
        assertEquals(new BigDecimal("580.00"), workspace.getFulfillment().getTotalSales());
        assertEquals(12, workspace.getFulfillment().getVerifiedHeads());
        assertEquals(new BigDecimal("320.00"), workspace.getFulfillment().getVerifiedSales());
        assertEquals(2, workspace.getFulfillment().getOpenComplaintCount());
        verify(settlementEvidenceService).readTopic(TOPIC_ID);
        assertThrows(NoSuchMethodException.class,
                () -> TopicWorkspaceDTO.Fulfillment.class.getMethod("getLeadSessions"),
                "通用履约 DTO 不得保留带队名册字段");
        assertTrue(workspace.getCooperations().isEmpty());
        assertTrue(workspace.getSettlements().isEmpty());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getStructure().getState());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getTickets().getState());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getCandidates().getState());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getFulfillment().getState());
        assertThrows(NoSuchMethodException.class,
                () -> TopicWorkspaceDTO.Candidate.class.getMethod("getEmail"),
                "工作台候选 DTO 不得暴露报名邮箱");
        assertThrows(NoSuchMethodException.class,
                () -> TopicWorkspaceDTO.Candidate.class.getMethod("getMemberId"),
                "工作台候选 DTO 不得暴露商家成员标识");
        assertThrows(NoSuchMethodException.class,
                () -> TopicWorkspaceDTO.Topic.class.getMethod("getMemberId"),
                "工作台主题身份不得暴露收款成员标识");
        assertThrows(NoSuchMethodException.class,
                () -> TopicWorkspaceDTO.Ticket.class.getMethod("getMemberId"),
                "工作台票务不得暴露票归属成员标识");
        assertThrows(NoSuchMethodException.class,
                () -> TopicWorkspaceDTO.Settlement.class.getMethod("getPayeeId"),
                "主题查询权限不得读取结算收款人标识");
        assertThrows(NoSuchMethodException.class,
                () -> TopicWorkspaceDTO.Settlement.class.getMethod("getAmount"),
                "主题查询权限不得读取结算金额");
        assertThrows(NoSuchMethodException.class,
                () -> TopicWorkspaceDTO.Node.class.getMethod("getCheckinCode"),
                "工作台节点不得暴露核销码");
        assertEquals(TopicWorkspaceDTO.CapabilityState.NOT_INTEGRATED,
                workspace.getCapabilities().getRoam().getState(),
                "聚合接口不得绕过独立彩蛋查看权限");
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getCooperation().getState(),
                "已读取的空合作链路必须保留为空数组");
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getSettlement().getState(),
                "已读取的空结算链路必须保留为空数组");
    }

    @Test
    void workspaceBatchLoadsNodesAndCandidateMerchantNames() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        CmsTopicChapter first = new CmsTopicChapter();
        first.setId(11L);
        CmsTopicChapter second = new CmsTopicChapter();
        second.setId(12L);
        when(chapterService.selectCmsTopicChapterList(any())).thenReturn(Arrays.asList(first, second));
        CmsTopicNode firstNode = new CmsTopicNode();
        firstNode.setChapterId(11L);
        CmsTopicNode secondNode = new CmsTopicNode();
        secondNode.setChapterId(12L);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(firstNode, secondNode));

        CmsRegistrationMerchant firstCandidate = new CmsRegistrationMerchant();
        firstCandidate.setMemberId(401L);
        CmsRegistrationMerchant secondCandidate = new CmsRegistrationMerchant();
        secondCandidate.setMemberId(402L);
        when(candidateService.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Arrays.asList(firstCandidate, secondCandidate));
        MmsMerchant firstMerchant = new MmsMerchant();
        firstMerchant.setMemberId(401L);
        firstMerchant.setName("甲商家");
        MmsMerchant secondMerchant = new MmsMerchant();
        secondMerchant.setMemberId(402L);
        secondMerchant.setName("乙商家");
        when(merchantMapper.selectMerchantNamesByMemberIds(any()))
                .thenReturn(Arrays.asList(firstMerchant, secondMerchant));

        TopicWorkspaceDTO workspace = service.getWorkspace(TOPIC_ID);

        assertEquals(1, workspace.getChapters().get(0).getNodes().size());
        assertEquals(1, workspace.getChapters().get(1).getNodes().size());
        assertEquals("甲商家", workspace.getCandidates().get(0).getMerchantName());
        assertEquals("乙商家", workspace.getCandidates().get(1).getMerchantName());
        verify(nodeService, times(1)).selectCmsTopicNodeList(any());
        verify(merchantMapper, times(1)).selectMerchantNamesByMemberIds(any());
        verify(merchantMapper, never()).selectMmsMerchantByMemberId(any());
    }

    @Test
    void workspacePreservesClubAndSettlementEmptyStatesAfterSourceConnection() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(topicEvidence.getTotalSales()).thenReturn(new BigDecimal("10.00"));
        when(topicEvidence.getVerifiedSales()).thenReturn(new BigDecimal("10.00"));
        when(topicEvidence.getVerifiedHeads()).thenReturn(1);

        TopicWorkspaceDTO workspace = service.getWorkspace(TOPIC_ID);

        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getFulfillment().getState());
        assertThrows(NoSuchMethodException.class,
                () -> TopicWorkspaceDTO.Fulfillment.class.getMethod("getLeadSessions"),
                "带队信息不能混入通用履约轴");
        assertTrue(workspace.getCooperations().isEmpty());
        assertTrue(workspace.getSettlements().isEmpty());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getClubFulfillment().getState());
        assertTrue(workspace.getClubFulfillment().getRelationships().isEmpty());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getCooperation().getState());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getSettlement().getState());
    }

    @Test
    void workspaceSeparatesClubIntentContractsAndChapterSupply() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setLifecycle(3);
        topic.setOpenClubPool(1);
        topic.setSelfPlay(0);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        CmsTopicChapter suppliedChapter = new CmsTopicChapter();
        suppliedChapter.setId(11L);
        suppliedChapter.setTopicId(TOPIC_ID);
        suppliedChapter.setName("梧桐路线");
        suppliedChapter.setRequired(1);
        suppliedChapter.setMaxMerchant(1);
        CmsTopicChapter missingChapter = new CmsTopicChapter();
        missingChapter.setId(12L);
        missingChapter.setTopicId(TOPIC_ID);
        missingChapter.setName("复兴路线");
        missingChapter.setRequired(1);
        missingChapter.setMaxMerchant(1);
        when(chapterService.selectCmsTopicChapterList(any()))
                .thenReturn(Arrays.asList(suppliedChapter, missingChapter));

        CmsTopicNode firstNode = new CmsTopicNode();
        firstNode.setId(101L);
        firstNode.setTopicId(TOPIC_ID);
        firstNode.setChapterId(11L);
        CmsTopicNode secondNode = new CmsTopicNode();
        secondNode.setId(102L);
        secondNode.setTopicId(TOPIC_ID);
        secondNode.setChapterId(12L);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(firstNode, secondNode));
        OmsTicket ticket = new OmsTicket();
        ticket.setId(31L);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.singletonList(ticket));

        CoopClubApply application = new CoopClubApply();
        application.setId(401L);
        application.setTopicId(TOPIC_ID);
        application.setClubId(501L);
        application.setStatus(0);
        application.setMessage("希望承接夜游");
        application.setCreateTime(new Date(1_700_000_000_000L));
        when(clubApplyMapper.selectCoopClubApplyList(any())).thenReturn(Collections.singletonList(application));

        CoopInvite pendingInvite = new CoopInvite();
        pendingInvite.setId(402L);
        pendingInvite.setTopicId(TOPIC_ID);
        pendingInvite.setToType("club");
        pendingInvite.setToId(502L);
        pendingInvite.setStatus(0);
        pendingInvite.setCreateTime(new Date(1_700_000_100_000L));
        CoopInvite acceptedInvite = new CoopInvite();
        acceptedInvite.setId(403L);
        acceptedInvite.setTopicId(TOPIC_ID);
        acceptedInvite.setToType("club");
        acceptedInvite.setToId(503L);
        acceptedInvite.setStatus(1);
        acceptedInvite.setDepositAmount(new BigDecimal("50.00"));
        acceptedInvite.setDepositStatus(1);
        acceptedInvite.setCreateTime(new Date(1_700_000_200_000L));
        when(inviteMapper.selectCoopInviteList(any()))
                .thenReturn(Arrays.asList(pendingInvite, acceptedInvite));

        CoopOrder clubOrder = new CoopOrder();
        clubOrder.setId(404L);
        clubOrder.setInviteId(403L);
        clubOrder.setTopicId(TOPIC_ID);
        clubOrder.setToType("club");
        clubOrder.setToId(503L);
        clubOrder.setStatus(0);
        clubOrder.setCreateTime(new Date(1_700_000_300_000L));
        CoopOrder merchantOrder = new CoopOrder();
        merchantOrder.setId(406L);
        merchantOrder.setTopicId(TOPIC_ID);
        merchantOrder.setToType("merchant");
        merchantOrder.setToId(801L);
        merchantOrder.setStatus(0);
        when(orderMapper.selectEffectiveOrdersByTopicId(TOPIC_ID))
                .thenReturn(Arrays.asList(clubOrder, merchantOrder));
        CoopGroupVerify clubVerification = new CoopGroupVerify();
        clubVerification.setCoopOrderId(404L);
        CoopGroupVerify merchantVerification = new CoopGroupVerify();
        merchantVerification.setCoopOrderId(406L);
        when(groupVerifyMapper.selectByTopicId(TOPIC_ID))
                .thenReturn(Arrays.asList(clubVerification, merchantVerification));

        TopicWorkspaceDTO.ClubFulfillment.Session session = new TopicWorkspaceDTO.ClubFulfillment.Session();
        session.setActivityId(405L);
        session.setClubId(503L);
        session.setStatus(2);
        session.setLeaderDisplayName("主理人小林");
        TopicWorkspaceDTO.ClubFulfillment.Session settledSession = new TopicWorkspaceDTO.ClubFulfillment.Session();
        settledSession.setActivityId(407L);
        settledSession.setClubId(503L);
        settledSession.setStatus(3);
        when(clubLeadMapper.selectLeadSessionsByTopic(TOPIC_ID))
                .thenReturn(Arrays.asList(session, settledSession));
        TopicWorkspaceDTO.ClubFulfillment.AttendanceMarker attendance =
                new TopicWorkspaceDTO.ClubFulfillment.AttendanceMarker();
        attendance.setActivityId(405L);
        when(clubLeadMapper.selectLeadAttendanceByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(attendance));

        Club pendingClub = new Club();
        pendingClub.setId(501L);
        pendingClub.setName("夜游申请社");
        Club invitedClub = new Club();
        invitedClub.setId(502L);
        invitedClub.setName("复兴邀约社");
        Club signedClub = new Club();
        signedClub.setId(503L);
        signedClub.setName("梧桐带队社");
        when(clubMapper.selectClubsByIds(any())).thenReturn(Arrays.asList(pendingClub, invitedClub, signedClub));

        MerchantChapterOffer activeOffer = new MerchantChapterOffer();
        activeOffer.setId(701L);
        activeOffer.setTopicId(TOPIC_ID);
        activeOffer.setChapterId(11L);
        activeOffer.setMerchantId(801L);
        activeOffer.setMerchantName("梧桐咖啡");
        activeOffer.setStatus(1);
        activeOffer.setQuotaTotal(20);
        activeOffer.setQuotaUsed(4);
        when(merchantOfferService.adminQueue(TOPIC_ID, null)).thenReturn(Collections.singletonList(activeOffer));
        when(settlementMapper.selectByTopicId(TOPIC_ID)).thenReturn(Collections.<CoopSettlement>emptyList());

        TopicWorkspaceDTO workspace = service.getWorkspace(TOPIC_ID);

        TopicWorkspaceDTO.ClubFulfillment clubFulfillment = workspace.getClubFulfillment();
        assertEquals(1, clubFulfillment.getPendingApplicationCount());
        assertEquals(1, clubFulfillment.getPendingInviteCount());
        assertEquals(1, clubFulfillment.getSignedCooperationCount());
        assertEquals(1, clubFulfillment.getActiveSessionCount(),
                "已结算场次不能被当成正在带队");
        assertEquals(2, clubFulfillment.getSessions().size(),
                "已结算场次仍应保留在履约历史中");
        assertEquals(1, clubFulfillment.getAttendanceCount());
        assertThrows(NoSuchMethodException.class,
                () -> TopicWorkspaceDTO.ClubFulfillment.Session.class.getMethod("getAttendees"),
                "主题工作台场次不得暴露到场名册");
        assertThrows(NoSuchMethodException.class,
                () -> TopicWorkspaceDTO.ClubFulfillment.Session.class.getMethod("getLeaderMemberId"),
                "主题工作台场次不得暴露领队成员标识");
        TopicWorkspaceDTO.ClubFulfillment.ClubRelationship signedRelationship = clubFulfillment.getRelationships()
                .stream().filter(row -> Long.valueOf(503L).equals(row.getClubId())).findFirst().orElse(null);
        assertNotNull(signedRelationship);
        assertEquals("梧桐带队社", signedRelationship.getClubName());
        assertEquals(Long.valueOf(404L), signedRelationship.getCooperationId());
        assertEquals(2, signedRelationship.getSessionCount(),
                "关系卡保留该俱乐部全部有效合作场次，不把历史场次丢掉");
        assertEquals("梧桐带队社", workspace.getOperation().getResponsibility().getOrganizerLabel());
        assertEquals("CLUB_LEADING", workspace.getOperation().getResponsibility().getOrganizerState());
        assertTrue(workspace.getOperation().getResponsibility().getActualFulfillers().contains("俱乐部 · 梧桐带队社"));
        assertTrue(workspace.getOperation().getResponsibility().getActualFulfillers().contains("商家 · 梧桐咖啡"));
        assertEquals(1, clubFulfillment.getDepositSummary().getPayingCount());
        assertEquals(1, workspace.getCooperations().get(0).getVerifiedGroupCount(),
                "合作单核验数必须来自 coop_group_verify，不能伪造为默认零");

        TopicWorkspaceDTO.MerchantSupply supply = workspace.getMerchantSupply();
        assertEquals(2, supply.getRequiredChapterCount());
        assertEquals(1, supply.getSuppliedRequiredChapterCount());
        assertEquals(1, supply.getMissingRequiredChapterCount());
        TopicWorkspaceDTO.MerchantSupply.Offer suppliedOffer = supply.getChapters().get(0).getOffers().get(0);
        assertEquals(Long.valueOf(406L), suppliedOffer.getCooperationId(),
                "章节有效供给必须显式关联唯一商家合作单，不能把供给记录伪装成合同");
        assertEquals(1, suppliedOffer.getVerifiedGroupCount(),
                "章节供给应带可追溯的合作团核销数");
        TopicWorkspaceDTO.MerchantSupply.ChapterSupply missingSupply = supply.getChapters().stream()
                .filter(row -> Long.valueOf(12L).equals(row.getChapterId())).findFirst().orElse(null);
        assertNotNull(missingSupply);
        assertEquals(0, missingSupply.getActiveOfferCount());
        assertEquals(Integer.valueOf(1), missingSupply.getMissingMerchantCount());

        assertEquals(TopicWorkspaceDTO.RunReadinessState.BLOCKED, workspace.getOperation().getRunReadiness());
        assertTrue(workspace.getOperation().getRunBlockers()
                .contains(TopicWorkspaceDTO.RunBlocker.REQUIRED_CHAPTER_SUPPLY_MISSING));
        assertEquals(TopicWorkspaceDTO.NextAction.RESOLVE_SUPPLY_GAPS, workspace.getOperation().getNextAction());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getClubFulfillment().getState());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getMerchantSupply().getState());
        assertEquals(TopicWorkspaceDTO.CapabilityState.AVAILABLE,
                workspace.getCapabilities().getSettlement().getState());
    }


    @Test
    void selfPlayPassCountsAsTicketButDoesNotWaiveGuidedClubFulfillment() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setLifecycle(3);
        topic.setSelfPlay(1);
        topic.setSelfPlayPrice(new BigDecimal("29.00"));
        topic.setSelfPlayQuota(10);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(11L);
        chapter.setTopicId(TOPIC_ID);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(21L);
        node.setTopicId(TOPIC_ID);
        node.setChapterId(11L);
        when(chapterService.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        OmsTicket guidedTicket = new OmsTicket();
        guidedTicket.setId(31L);
        guidedTicket.setMode(1);
        guidedTicket.setSubType("guided");
        guidedTicket.setPrice(new BigDecimal("88.00"));
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.singletonList(guidedTicket));
        when(registrationMapper.countActiveSelfPlayPasses(TOPIC_ID)).thenReturn(3);

        TopicWorkspaceDTO workspace = service.getWorkspace(TOPIC_ID);

        assertEquals(2, workspace.getTickets().size(), "自玩通行证不在 oms_ticket，仍必须进入票务投影");
        TopicWorkspaceDTO.Ticket selfPlayPass = workspace.getTickets().stream()
                .filter(ticket -> "SELF_PLAY_PASS".equals(ticket.getKind()))
                .findFirst().orElse(null);
        assertNotNull(selfPlayPass);
        assertEquals(new BigDecimal("29.00"), selfPlayPass.getPrice());
        assertEquals(Long.valueOf(10L), selfPlayPass.getTotalInventory());
        assertEquals(Long.valueOf(7L), selfPlayPass.getRemainingInventory());
        assertFalse(workspace.getOperation().getRunBlockers()
                .contains(TopicWorkspaceDTO.RunBlocker.TICKET_CONFIGURATION_MISSING));
        assertTrue(workspace.getOperation().getRunBlockers()
                .contains(TopicWorkspaceDTO.RunBlocker.CLUB_FULFILLMENT_UNCONFIRMED),
                "自玩入口与有人带票可共存；不能拿 selfPlay 替代俱乐部承接事实");
        assertEquals(TopicWorkspaceDTO.ClubFulfillmentState.UNCONFIRMED,
                workspace.getClubFulfillment().getState());
    }

    @Test
    void selfPlayOnlyTopicHasARealTicketAndDoesNotInventAClubRequirement() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setLifecycle(3);
        topic.setSelfPlay(1);
        topic.setSelfPlayPrice(new BigDecimal("29.00"));
        topic.setSelfPlayQuota(0);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(11L);
        chapter.setTopicId(TOPIC_ID);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(21L);
        node.setTopicId(TOPIC_ID);
        node.setChapterId(11L);
        when(chapterService.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.<OmsTicket>emptyList());

        TopicWorkspaceDTO workspace = service.getWorkspace(TOPIC_ID);

        assertEquals(1, workspace.getTickets().size());
        assertEquals("SELF_PLAY_PASS", workspace.getTickets().get(0).getKind());
        assertNull(workspace.getTickets().get(0).getTotalInventory(), "NULL 是不限量，不得伪造为零库存");
        assertFalse(workspace.getOperation().getRunBlockers()
                .contains(TopicWorkspaceDTO.RunBlocker.TICKET_CONFIGURATION_MISSING));
        assertFalse(workspace.getOperation().getRunBlockers()
                .contains(TopicWorkspaceDTO.RunBlocker.CLUB_FULFILLMENT_UNCONFIRMED),
                "只有显式 guided 票种才要求俱乐部带队；自玩通行证本身不是俱乐部合同");
        assertEquals(TopicWorkspaceDTO.ClubFulfillmentState.NOT_REQUIRED,
                workspace.getClubFulfillment().getState());
        assertEquals(TopicWorkspaceDTO.RunReadinessState.READY, workspace.getOperation().getRunReadiness());
    }

    @Test
    void partiallyFilledRequiredChapterRemainsSupplyGapUntilTargetMerchantCountIsMet() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setLifecycle(3);
        topic.setSelfPlay(1);
        topic.setSelfPlayPrice(new BigDecimal("29.00"));
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(11L);
        chapter.setTopicId(TOPIC_ID);
        chapter.setRequired(1);
        chapter.setMaxMerchant(2);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(21L);
        node.setTopicId(TOPIC_ID);
        node.setChapterId(11L);
        when(chapterService.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.<OmsTicket>emptyList());

        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setId(41L);
        offer.setTopicId(TOPIC_ID);
        offer.setChapterId(11L);
        offer.setMerchantId(401L);
        offer.setStatus(1);
        when(merchantOfferService.adminQueue(TOPIC_ID, null)).thenReturn(Collections.singletonList(offer));

        TopicWorkspaceDTO workspace = service.getWorkspace(TOPIC_ID);

        assertEquals(1, workspace.getMerchantSupply().getRequiredChapterCount());
        assertEquals(0, workspace.getMerchantSupply().getSuppliedRequiredChapterCount(),
                "目标为两家时，一家有效 offer 仍不是该章节供给就绪");
        assertEquals(1, workspace.getMerchantSupply().getMissingRequiredChapterCount());
        assertEquals(Integer.valueOf(1), workspace.getMerchantSupply().getChapters().get(0).getMissingMerchantCount());
        assertTrue(workspace.getOperation().getRunBlockers()
                .contains(TopicWorkspaceDTO.RunBlocker.REQUIRED_CHAPTER_SUPPLY_MISSING));
    }

    private void assertUnavailableAndOmitted(TopicWorkspaceDTO.AxisCapability capability,
                                            String reasonCode,
                                            Object payload) {
        assertEquals(TopicWorkspaceDTO.CapabilityState.NOT_INTEGRATED, capability.getState());
        assertEquals(reasonCode, capability.getReasonCode());
        assertNull(capability.getAsOf());
        assertNull(payload, "未接通的数据轴不得携带业务数据");
    }
}

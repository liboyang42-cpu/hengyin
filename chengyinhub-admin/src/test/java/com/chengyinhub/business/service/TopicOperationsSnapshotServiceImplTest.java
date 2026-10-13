package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopClubApply;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.dto.TopicOperationsSnapshotDTO;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.impl.TopicOperationsSnapshotServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 统一待办只读合同：它只指向领域事实，不能把报名、邀约或供给实体原样暴露给 Web 工作台。
 */
class TopicOperationsSnapshotServiceImplTest {

    private static final Long TOPIC_ID = 71L;

    private ICmsTopicService topicService;
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;
    private IProductRoleService productRoleService;
    private ICmsRegistrationMerchantService registrationMerchantService;
    private MerchantChapterOfferMapper offerMapper;
    private ICoopClubApplyService clubApplyService;
    private CoopInviteMapper inviteMapper;
    private CoopOrderMapper orderMapper;
    private TopicOperationsSnapshotServiceImpl service;

    @BeforeEach
    void setUp() {
        topicService = mock(ICmsTopicService.class);
        topicOperationsScopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        productRoleService = mock(IProductRoleService.class);
        registrationMerchantService = mock(ICmsRegistrationMerchantService.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        clubApplyService = mock(ICoopClubApplyService.class);
        inviteMapper = mock(CoopInviteMapper.class);
        orderMapper = mock(CoopOrderMapper.class);
        service = new TopicOperationsSnapshotServiceImpl(topicService, topicOperationsScopeAuthorizer, productRoleService,
                registrationMerchantService, offerMapper, clubApplyService, inviteMapper, orderMapper);
        when(topicOperationsScopeAuthorizer.canViewTopic(TOPIC_ID)).thenReturn(true);
    }

    @Test
    void missingTopicScopeFailsClosedBeforeAnySourceIsRead() {
        when(topicOperationsScopeAuthorizer.canViewTopic(TOPIC_ID)).thenReturn(false);

        TopicOperationsSnapshotDTO snapshot = service.getSnapshot(TOPIC_ID);

        assertFalse(snapshot.isAvailable());
        assertEquals("BLOCKED_TOPIC_SCOPE_UNAVAILABLE", snapshot.getUnavailableReasonCode());
        assertTrue(snapshot.getItems().isEmpty());
        verify(topicService, never()).selectCmsTopicById(TOPIC_ID);
        verify(registrationMerchantService, never()).selectCmsRegistrationMerchantList(any());
        verify(offerMapper, never()).selectByTopic(TOPIC_ID);
        verify(clubApplyService, never()).selectByTopic(TOPIC_ID);
        verify(inviteMapper, never()).selectCoopInviteList(any());
        verify(orderMapper, never()).selectEffectiveOrdersByTopicId(TOPIC_ID);
    }

    @Test
    void missingTopicStopsBeforeReadingAnyDomainSource() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(null);

        assertNull(service.getSnapshot(TOPIC_ID));
        verify(registrationMerchantService, never()).selectCmsRegistrationMerchantList(any());
        verify(offerMapper, never()).selectByTopic(TOPIC_ID);
        verify(clubApplyService, never()).selectByTopic(TOPIC_ID);
        verify(inviteMapper, never()).selectCoopInviteList(any());
        verify(orderMapper, never()).selectEffectiveOrdersByTopicId(TOPIC_ID);
    }

    @Test
    void projectsEveryP0SourceAsReadOnlyItemsWithoutLeakingRawEvidence() throws Exception {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        ProductRole content = new ProductRole();
        content.setEntityType("PLATFORM");
        when(productRoleService.activeContentSlot(TOPIC_ID)).thenReturn(content);

        CmsRegistrationMerchant registration = new CmsRegistrationMerchant();
        registration.setId(11L);
        registration.setTopicId(TOPIC_ID);
        registration.setStatus(1L);
        registration.setAuditStatus(0L);
        registration.setEmail("private@example.com");
        registration.setLongitude("121.471");
        registration.setLatitude("31.231");
        registration.setCreateTime(new Date(1_700_000_000_000L));
        when(registrationMerchantService.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(registration));

        MerchantChapterOffer offer = new MerchantChapterOffer();
        offer.setId(12L);
        offer.setTopicId(TOPIC_ID);
        offer.setChapterId(5L);
        offer.setMerchantId(201L);
        offer.setStatus(1);
        offer.setCreateTime(new Date(1_700_001_000_000L));
        when(offerMapper.selectByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(offer));

        CoopClubApply apply = new CoopClubApply();
        apply.setId(13L);
        apply.setTopicId(TOPIC_ID);
        apply.setClubId(301L);
        apply.setStatus(0);
        apply.setMessage("只在详情证据里可见");
        apply.setCreateTime(new Date(1_700_002_000_000L));
        when(clubApplyService.selectByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(apply));

        CoopInvite invite = new CoopInvite();
        invite.setId(14L);
        invite.setTopicId(TOPIC_ID);
        invite.setInviteType(1);
        invite.setToType("club");
        invite.setToId(301L);
        invite.setStatus(0);
        invite.setMessage("不得进入待办摘要");
        invite.setCreateTime(new Date(1_700_003_000_000L));
        when(inviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(invite));

        CoopOrder order = new CoopOrder();
        order.setId(15L);
        order.setTopicId(TOPIC_ID);
        order.setToType("club");
        order.setToId(301L);
        order.setStatus(0);
        order.setCreateTime(new Date(1_700_004_000_000L));
        when(orderMapper.selectEffectiveOrdersByTopicId(TOPIC_ID)).thenReturn(Collections.singletonList(order));

        TopicOperationsSnapshotDTO snapshot = service.getSnapshot(TOPIC_ID);

        assertEquals("DECLARED", snapshot.getTopic().getContentOwnershipState());
        assertEquals("PLATFORM", snapshot.getTopic().getContentOwnerType());
        assertEquals(5, snapshot.getItems().size());
        assertEquals(0, snapshot.getSummary().getActionableCount(), "没有真实受托范围时不能伪造可处理待办");
        assertEquals(2, snapshot.getSummary().getBlockedCount());
        assertEquals(1, snapshot.getSummary().getWaitingExternalCount());
        assertEquals(2, snapshot.getSummary().getReadOnlyCount());

        TopicOperationsSnapshotDTO.OperationItemDTO merchant = item(snapshot, "MERCHANT_REGISTRATION:11");
        assertEquals("PENDING_DISPATCH", merchant.getSourceState());
        assertEquals("待调配", merchant.getDisplayState());
        assertEquals("BLOCKED", merchant.getActionability());
        assertEquals(TOPIC_ID, merchant.getSourceRef().getTopicId());
        assertFalse(merchant.getAllowedActions().get(0).isEnabled());
        assertEquals("BLOCKED_OBJECT_AUTHORIZATION_UNAVAILABLE",
                merchant.getAllowedActions().get(0).getDisabledReasonCode());

        assertEquals("WAITING_EXTERNAL", item(snapshot, "CLUB_INVITE:14").getActionability());
        assertEquals("READ_ONLY", item(snapshot, "CLUB_COOPERATION:15").getActionability());
        assertTrue(snapshot.getItems().stream()
                        .flatMap(candidate -> candidate.getAllowedActions().stream())
                        .noneMatch(TopicOperationsSnapshotDTO.AllowedActionDTO::isEnabled),
                "缺少后台账号到主题的对象级受托范围时，任何动作都不得被前端当作可执行命令");

        assertThrows(NoSuchMethodException.class,
                () -> TopicOperationsSnapshotDTO.OperationItemDTO.class.getMethod("getEmail"));
        assertThrows(NoSuchMethodException.class,
                () -> TopicOperationsSnapshotDTO.OperationItemDTO.class.getMethod("getLongitude"));
        assertThrows(NoSuchMethodException.class,
                () -> TopicOperationsSnapshotDTO.OperationItemDTO.class.getMethod("getLatitude"));
        assertThrows(NoSuchMethodException.class,
                () -> TopicOperationsSnapshotDTO.OperationItemDTO.class.getMethod("getMessage"));
        assertThrows(NoSuchMethodException.class,
                () -> TopicOperationsSnapshotDTO.OperationItemDTO.class.getMethod("getPhoneNumber"));
        assertThrows(NoSuchMethodException.class,
                () -> TopicOperationsSnapshotDTO.OperationItemDTO.class.getMethod("getAnswer"));
        assertThrows(NoSuchMethodException.class,
                () -> TopicOperationsSnapshotDTO.OperationItemDTO.class.getMethod("getRawJson"));
        assertThrows(NoSuchMethodException.class,
                () -> TopicOperationsSnapshotDTO.AllowedActionDTO.class.getMethod("getUrl"));
        assertThrows(NoSuchMethodException.class,
                () -> TopicOperationsSnapshotDTO.AllowedActionDTO.class.getMethod("getAttachmentUrl"));
    }

    @Test
    void projectsRejectedAndUnknownMerchantReviewStatesWithoutPretendingTheyArePending() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        CmsRegistrationMerchant rejected = new CmsRegistrationMerchant();
        rejected.setId(21L);
        rejected.setStatus(2L);
        rejected.setCreateTime(new Date(1_700_005_000_000L));
        CmsRegistrationMerchant unknown = new CmsRegistrationMerchant();
        unknown.setId(22L);
        unknown.setStatus(null);
        unknown.setCreateTime(new Date(1_700_006_000_000L));
        CmsRegistrationMerchant pendingReview = new CmsRegistrationMerchant();
        pendingReview.setId(23L);
        pendingReview.setStatus(0L);
        pendingReview.setCreateTime(new Date(1_700_007_000_000L));
        when(registrationMerchantService.selectCmsRegistrationMerchantList(any()))
                .thenReturn(java.util.Arrays.asList(rejected, unknown, pendingReview));

        TopicOperationsSnapshotDTO snapshot = service.getSnapshot(TOPIC_ID);

        assertEquals("REGISTRATION_REJECTED", item(snapshot, "MERCHANT_REGISTRATION:21").getSourceState());
        assertEquals("报名审核未通过", item(snapshot, "MERCHANT_REGISTRATION:21").getDisplayState());
        assertEquals("READ_ONLY", item(snapshot, "MERCHANT_REGISTRATION:21").getActionability());
        assertEquals("REGISTRATION_STATUS_UNKNOWN", item(snapshot, "MERCHANT_REGISTRATION:22").getSourceState());
        assertEquals("报名状态待核验", item(snapshot, "MERCHANT_REGISTRATION:22").getDisplayState());
        assertEquals("READ_ONLY", item(snapshot, "MERCHANT_REGISTRATION:22").getActionability());
        assertEquals("REGISTRATION_PENDING_REVIEW", item(snapshot, "MERCHANT_REGISTRATION:23").getSourceState());
        assertEquals("BLOCKED", item(snapshot, "MERCHANT_REGISTRATION:23").getActionability());
    }

    @Test
    void derivesClubApplicationStateFromLinkedInviteAndEffectiveCooperation() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        CoopClubApply apply = new CoopClubApply();
        apply.setId(31L);
        apply.setTopicId(TOPIC_ID);
        apply.setStatus(0);
        apply.setCreateTime(new Date(1_700_007_000_000L));
        when(clubApplyService.selectByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(apply));

        CoopInvite invite = new CoopInvite();
        invite.setId(41L);
        invite.setTopicId(TOPIC_ID);
        invite.setInviteType(1);
        invite.setToType("club");
        invite.setOriginApplyId(31L);
        invite.setStatus(1);
        invite.setCreateTime(new Date(1_700_008_000_000L));
        CoopInvite laterPendingInvite = new CoopInvite();
        laterPendingInvite.setId(42L);
        laterPendingInvite.setTopicId(TOPIC_ID);
        laterPendingInvite.setInviteType(1);
        laterPendingInvite.setToType("club");
        laterPendingInvite.setOriginApplyId(31L);
        laterPendingInvite.setStatus(0);
        laterPendingInvite.setCreateTime(new Date(1_700_010_000_000L));
        when(inviteMapper.selectCoopInviteList(any()))
                .thenReturn(java.util.Arrays.asList(invite, laterPendingInvite));

        CoopOrder order = new CoopOrder();
        order.setId(51L);
        order.setTopicId(TOPIC_ID);
        order.setInviteId(41L);
        order.setToType("club");
        order.setStatus(0);
        Date cooperationAt = new Date(1_700_009_000_000L);
        order.setCreateTime(cooperationAt);
        when(orderMapper.selectEffectiveOrdersByTopicId(TOPIC_ID)).thenReturn(Collections.singletonList(order));

        TopicOperationsSnapshotDTO snapshot = service.getSnapshot(TOPIC_ID);
        TopicOperationsSnapshotDTO.OperationItemDTO application = item(snapshot, "CLUB_APPLICATION:31");

        assertEquals("PENDING", application.getSourceState());
        assertEquals("已成合作单", application.getDisplayState());
        assertEquals("READ_ONLY", application.getActionability());
        assertEquals(cooperationAt, application.getLatestAt());
        assertEquals("ACCEPTED", item(snapshot, "CLUB_INVITE:41").getSourceState());
        assertEquals("EFFECTIVE", item(snapshot, "CLUB_COOPERATION:51").getSourceState());
    }

    @Test
    void derivesApplicationFromTheMostRecentlyHandledInviteWhenNoCooperationExists() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        CoopClubApply apply = new CoopClubApply();
        apply.setId(32L);
        apply.setTopicId(TOPIC_ID);
        apply.setStatus(0);
        apply.setCreateTime(new Date(1_700_011_000_000L));
        when(clubApplyService.selectByTopic(TOPIC_ID)).thenReturn(Collections.singletonList(apply));

        CoopInvite accepted = new CoopInvite();
        accepted.setId(43L);
        accepted.setTopicId(TOPIC_ID);
        accepted.setInviteType(1);
        accepted.setToType("club");
        accepted.setOriginApplyId(32L);
        accepted.setStatus(1);
        accepted.setCreateTime(new Date(1_700_012_000_000L));
        accepted.setHandleTime(new Date(1_700_015_000_000L));
        CoopInvite newerPending = new CoopInvite();
        newerPending.setId(44L);
        newerPending.setTopicId(TOPIC_ID);
        newerPending.setInviteType(1);
        newerPending.setToType("club");
        newerPending.setOriginApplyId(32L);
        newerPending.setStatus(0);
        newerPending.setCreateTime(new Date(1_700_014_000_000L));
        when(inviteMapper.selectCoopInviteList(any()))
                .thenReturn(java.util.Arrays.asList(accepted, newerPending));

        TopicOperationsSnapshotDTO snapshot = service.getSnapshot(TOPIC_ID);
        TopicOperationsSnapshotDTO.OperationItemDTO application = item(snapshot, "CLUB_APPLICATION:32");

        assertEquals("PENDING", application.getSourceState());
        assertEquals("邀约已接受，合作单待核验", application.getDisplayState());
        assertEquals("READ_ONLY", application.getActionability());
    }

    private TopicOperationsSnapshotDTO.OperationItemDTO item(TopicOperationsSnapshotDTO snapshot, String key) {
        return snapshot.getItems().stream()
                .filter(candidate -> key.equals(candidate.getItemKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing operation item: " + key));
    }
}

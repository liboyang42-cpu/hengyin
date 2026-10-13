package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Arrays;
import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class OfficialInviteAcceptanceTest {
    private CoopInviteMapper inviteMapper;
    private CmsTopicMapper topicMapper;
    private CmsTopicNodeMapper nodeMapper;
    private ClubMapper clubMapper;
    private CmsRegistrationMerchantMapper registrationMapper;
    private MmsMerchantMapper merchantMapper;
    private CoopInviteServiceImpl service;

    @BeforeEach
    void setUp() {
        inviteMapper = mock(CoopInviteMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        clubMapper = mock(ClubMapper.class);
        registrationMapper = mock(CmsRegistrationMerchantMapper.class);
        merchantMapper = mock(MmsMerchantMapper.class);
        service = new CoopInviteServiceImpl();
        ReflectionTestUtils.setField(service, "coopInviteMapper", inviteMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMerchantMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", merchantMapper);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setMemberId(201L);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        when(merchantMapper.selectMmsMerchantByMemberId(201L)).thenReturn(merchant);
        when(merchantMapper.selectMmsMerchantByMemberIdForUpdate(201L)).thenReturn(merchant);
    }

    @Test
    void acceptOfficialInvite_buildsRegistrationFromServerOwnedNodeSnapshot() {
        CoopInvite invite = pendingOfficialInvite(201L);
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setLifecycle(1);
        topic.setDelFlag(0);
        topic.setProductType(1);
        topic.setRecruitDeadline(new Date(System.currentTimeMillis() + 60L * 60L * 1000L));
        CmsTopicNode node = new CmsTopicNode();
        node.setId(9L);
        node.setTopicId(7L);
        node.setTemplateId(33L);
        node.setName("码头站");
        node.setAddress("滨江路 1 号");
        node.setLongitude("121.1");
        node.setLatitude("31.1");
        when(inviteMapper.selectCoopInviteByIdForUpdate(11L)).thenReturn(invite);
        when(topicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        when(nodeMapper.selectCmsTopicNodeById(9L)).thenReturn(node);
        when(registrationMapper.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.emptyList());
        when(inviteMapper.updateCoopInvite(any())).thenReturn(1);
        doAnswer(call -> {
            CmsRegistrationMerchant registration = call.getArgument(0);
            registration.setId(71L);
            return 1;
        }).when(registrationMapper).insertCmsRegistrationMerchant(any());

        Long registrationId = service.acceptOfficialInviteWithLock(11L, 201L);

        assertEquals(71L, registrationId);
        verify(registrationMapper).insertCmsRegistrationMerchant(org.mockito.ArgumentMatchers.argThat(registration ->
                Long.valueOf(201L).equals(registration.getMemberId())
                        && Long.valueOf(7L).equals(registration.getTopicId())
                        && Long.valueOf(9L).equals(registration.getNodeId())
                        && Long.valueOf(33L).equals(registration.getTemplateId())
                        && Long.valueOf(0L).equals(registration.getAuditStatus())));
        verify(inviteMapper).updateCoopInvite(org.mockito.ArgumentMatchers.argThat(update ->
                Long.valueOf(11L).equals(update.getId()) && Integer.valueOf(1).equals(update.getStatus())));
    }

    @Test
    void acceptOfficialInvite_wrongMerchant_isRejectedByRecipientGate() {
        when(inviteMapper.selectCoopInviteByIdForUpdate(11L)).thenReturn(pendingOfficialInvite(201L));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.acceptOfficialInviteWithLock(11L, 999L));

        assertTrue(error.getMessage().contains("仅受邀商家"), "异常文案必须指认受邀方归属闸");
        verify(registrationMapper, never()).insertCmsRegistrationMerchant(any());
        verify(inviteMapper, never()).updateCoopInvite(any());
    }

    @Test
    void acceptOfficialInvite_alreadyAccepted_neverCreatesSecondRegistration() {
        CoopInvite invite = pendingOfficialInvite(201L);
        invite.setStatus(1);
        when(inviteMapper.selectCoopInviteByIdForUpdate(11L)).thenReturn(invite);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.acceptOfficialInviteWithLock(11L, 201L));

        assertTrue(error.getMessage().contains("已处理"), "重复点击必须命中邀约状态幂等闸");
        verify(registrationMapper, never()).insertCmsRegistrationMerchant(any());
    }

    @Test
    void acceptOfficialInvite_created49HoursAgo_isTerminalizedAndRejected() {
        CoopInvite invite = pendingOfficialInvite(201L);
        invite.setCreateTime(new Date(System.currentTimeMillis() - 49L * 60L * 60L * 1000L));
        invite.setExpireTime(new Date(System.currentTimeMillis() + 24L * 60L * 60L * 1000L));
        when(inviteMapper.selectCoopInviteByIdForUpdate(11L)).thenReturn(invite);
        when(inviteMapper.updateCoopInvite(any())).thenReturn(1);

        CoopInviteExpiredException error = assertThrows(CoopInviteExpiredException.class,
                () -> service.acceptOfficialInviteWithLock(11L, 201L));

        assertTrue(error.getMessage().contains("已过期"));
        verify(inviteMapper).updateCoopInvite(org.mockito.ArgumentMatchers.argThat(update ->
                Long.valueOf(11L).equals(update.getId()) && Integer.valueOf(5).equals(update.getStatus())));
        verify(registrationMapper, never()).insertCmsRegistrationMerchant(any());
    }

    @Test
    void acceptOfficialInvite_existingTargetRegistration_isReusedUnderTopicLock() {
        CoopInvite invite = pendingOfficialInvite(201L);
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setLifecycle(1);
        topic.setDelFlag(0);
        topic.setProductType(1);
        topic.setRecruitDeadline(new Date(System.currentTimeMillis() + 60L * 60L * 1000L));
        CmsTopicNode node = new CmsTopicNode();
        node.setId(9L);
        node.setTopicId(7L);
        node.setTemplateId(33L);
        CmsRegistrationMerchant existing = new CmsRegistrationMerchant();
        existing.setId(70L);
        existing.setMemberId(201L);
        existing.setTopicId(7L);
        existing.setNodeId(9L);
        existing.setTemplateId(33L);
        when(inviteMapper.selectCoopInviteByIdForUpdate(11L)).thenReturn(invite);
        when(topicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        when(nodeMapper.selectCmsTopicNodeById(9L)).thenReturn(node);
        when(registrationMapper.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(existing));
        when(inviteMapper.updateCoopInvite(any())).thenReturn(1);

        Long registrationId = service.acceptOfficialInviteWithLock(11L, 201L);

        assertEquals(70L, registrationId);
        verify(topicMapper).selectCmsTopicByIdForUpdate(7L);
        verify(registrationMapper, never()).insertCmsRegistrationMerchant(any());
    }

    @Test
    void rejectOfficialInvite_updatesOnlyAfterRecipientAndPendingChecksUnderLock() {
        when(inviteMapper.selectCoopInviteByIdForUpdate(11L)).thenReturn(pendingOfficialInvite(201L));
        when(inviteMapper.updateCoopInvite(any())).thenReturn(1);

        service.rejectOfficialInviteWithLock(11L, 201L, "档期冲突");

        ArgumentCaptor<CoopInvite> update = ArgumentCaptor.forClass(CoopInvite.class);
        verify(inviteMapper).updateCoopInvite(update.capture());
        assertEquals(11L, update.getValue().getId());
        assertEquals(2, update.getValue().getStatus());
        assertEquals("档期冲突", update.getValue().getHandleReason());
        verify(registrationMapper, never()).insertCmsRegistrationMerchant(any());
    }

    @Test
    void rejectOfficialInvite_blankReason_isRejectedByReasonGate() {
        when(inviteMapper.selectCoopInviteByIdForUpdate(11L)).thenReturn(pendingOfficialInvite(201L));

        assertThrows(IllegalStateException.class,
                () -> service.rejectOfficialInviteWithLock(11L, 201L, "  "));

        verify(inviteMapper, never()).updateCoopInvite(any());
    }

    @Test
    void listOfficialMerchantInvites_scopesQueryToCurrentRecipientAndOfficialDomain() {
        when(inviteMapper.selectCoopInviteList(any())).thenReturn(Collections.emptyList());

        service.listOfficialMerchantInvites(201L);

        verify(inviteMapper).expirePendingInvites(any());
        verify(inviteMapper).selectCoopInviteList(org.mockito.ArgumentMatchers.argThat(query ->
                CoopInvite.TYPE_OFFICIAL_MERCHANT == query.getInviteType()
                        && CoopInvite.FROM_OFFICIAL.equals(query.getFromType())
                        && CoopInvite.TO_MERCHANT.equals(query.getToType())
                        && Long.valueOf(201L).equals(query.getToId())));
    }

    @Test
    void listOfficialMerchantInvites_hidesDissolvedTopicParent() {
        CoopInvite invite = pendingOfficialInvite(201L);
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setLifecycle(1);
        topic.setClubId(73L);
        topic.setDelFlag(0);
        when(inviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(invite));
        when(topicMapper.selectCmsTopicById(7L)).thenReturn(topic);
        when(clubMapper.selectClubById(73L)).thenReturn(null);

        assertTrue(service.listOfficialMerchantInvites(201L).isEmpty(),
                "官方邀约历史保留审计，但解散父团不能继续出现在商家消费面");
    }

    @Test
    void listAwardedOfficialActivities_excludesAcceptedButUnawardedRegistration() {
        CoopInvite invite = pendingOfficialInvite(201L);
        invite.setStatus(1);
        when(inviteMapper.selectCoopInviteList(any())).thenReturn(Collections.singletonList(invite));
        CmsRegistrationMerchant registration = new CmsRegistrationMerchant();
        registration.setId(71L);
        registration.setAuditStatus(0L);
        when(registrationMapper.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(registration));

        assertTrue(service.listAwardedOfficialActivities(201L).isEmpty());
    }

    @Test
    void listAwardedOfficialActivities_missingMerchantIdentity_neverRunsUnscopedQuery() {
        assertTrue(service.listAwardedOfficialActivities(null).isEmpty());
        verify(inviteMapper, never()).selectCoopInviteList(any());
    }

    @Test
    void createOfficialInvite_persistsStructuredRewardAndEarlierTopicDeadline() {
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setLifecycle(1);
        topic.setDelFlag(0);
        topic.setRecruitDeadline(new Date(System.currentTimeMillis() + 60L * 60L * 1000L));
        when(topicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        doAnswer(call -> { ((CoopInvite) call.getArgument(0)).setId(11L); return 1; })
                .when(inviteMapper).insertCoopInvite(any());

        com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest request =
                new com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest();
        request.setTopicId(7L);
        request.setMerchantIds(Arrays.asList(201L));
        request.setMessage("请承接");
        request.setShareMode(1);
        request.setShareRate(new BigDecimal("12.5"));
        service.createOfficialMerchantInvites(100092L, request);

        verify(inviteMapper).insertCoopInvite(org.mockito.ArgumentMatchers.argThat(invite ->
                Integer.valueOf(1).equals(invite.getShareMode())
                        && new BigDecimal("12.5").compareTo(invite.getShareRate()) == 0
                        && topic.getRecruitDeadline().equals(invite.getExpireTime())));
    }

    @Test
    void createOfficialInvite_capsClientDeadlineAt48Hours() {
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setLifecycle(1);
        topic.setDelFlag(0);
        topic.setRecruitDeadline(new Date(System.currentTimeMillis() + 60L * 60L * 1000L));
        when(topicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest request =
                new com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest();
        request.setTopicId(7L);
        request.setMerchantIds(Arrays.asList(201L));
        request.setExpireTime(new Date(System.currentTimeMillis() + 10L * 24L * 60L * 60L * 1000L));
        ArgumentCaptor<CoopInvite> inserted = ArgumentCaptor.forClass(CoopInvite.class);
        long before = System.currentTimeMillis();

        service.createOfficialMerchantInvites(100092L, request);

        long after = System.currentTimeMillis();
        verify(inviteMapper).insertCoopInvite(inserted.capture());
        long actual = inserted.getValue().getExpireTime().getTime();
        assertTrue(actual >= before + 48L * 60L * 60L * 1000L
                        && actual <= after + 48L * 60L * 60L * 1000L,
                "官方定向邀约也不得信任客户端超过48h的截止时间");
    }

    @Test
    void createOfficialInvite_rejectsDissolvedTopicParentBeforePersistence() {
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setLifecycle(1);
        topic.setClubId(73L);
        topic.setDelFlag(0);
        when(topicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        when(clubMapper.selectClubByIdForUpdate(73L)).thenReturn(null);
        com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest request =
                new com.chengyinhub.business.domain.dto.OfficialMerchantInviteRequest();
        request.setTopicId(7L);
        request.setMerchantIds(Arrays.asList(201L));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createOfficialMerchantInvites(100092L, request));

        assertEquals("关联俱乐部已解散，不能发起官方合作邀请", error.getMessage(),
                "该红必须指认到官方批量邀约写入的解散互斥闸");
        verify(inviteMapper, never()).insertCoopInvite(any());
    }

    @Test
    void acceptOfficialInvite_rejectsDissolvedTopicParentBeforeRegistrationOrStatusUpdate() {
        CoopInvite invite = pendingOfficialInvite(201L);
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setLifecycle(1);
        topic.setClubId(73L);
        topic.setDelFlag(0);
        when(inviteMapper.selectCoopInviteByIdForUpdate(11L)).thenReturn(invite);
        when(topicMapper.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        when(clubMapper.selectClubByIdForUpdate(73L)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.acceptOfficialInviteWithLock(11L, 201L));

        assertEquals("关联俱乐部已解散，不能接受官方合作邀请", error.getMessage(),
                "该红必须指认到官方接受与解散共用的 club 行锁");
        verify(registrationMapper, never()).insertCmsRegistrationMerchant(any());
        verify(inviteMapper, never()).updateCoopInvite(any());
    }

    @Test
    void acceptOfficialInvite_nonMerchantRecipient_isRejectedBeforeBuildingRegistration() {
        when(inviteMapper.selectCoopInviteByIdForUpdate(11L)).thenReturn(pendingOfficialInvite(999L));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.acceptOfficialInviteWithLock(11L, 999L));

        assertTrue(error.getMessage().contains("审核通过且启用的商家"), "必须指认商家身份闸");
        verify(registrationMapper, never()).insertCmsRegistrationMerchant(any());
    }

    @Test
    void acceptOfficialInvite_merchantWithoutEnabledAccount_isRejectedFailClosed() {
        MmsMerchant unresolved = new MmsMerchant();
        unresolved.setMemberId(999L);
        unresolved.setStatus(1L);
        unresolved.setAccountStatus(null);
        unresolved.setDelFlag(0);
        when(merchantMapper.selectMmsMerchantByMemberId(999L)).thenReturn(unresolved);
        when(inviteMapper.selectCoopInviteByIdForUpdate(11L)).thenReturn(pendingOfficialInvite(999L));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.acceptOfficialInviteWithLock(11L, 999L));

        assertTrue(error.getMessage().contains("审核通过且启用的商家"));
        verify(registrationMapper, never()).insertCmsRegistrationMerchant(any());
    }

    private static CoopInvite pendingOfficialInvite(Long merchantId) {
        CoopInvite invite = new CoopInvite();
        invite.setId(11L);
        invite.setInviteType(CoopInvite.TYPE_OFFICIAL_MERCHANT);
        invite.setFromType(CoopInvite.FROM_OFFICIAL);
        invite.setFromId(100092L);
        invite.setToType(CoopInvite.TO_MERCHANT);
        invite.setToId(merchantId);
        invite.setTopicId(7L);
        invite.setNodeId(9L);
        invite.setStatus(0);
        return invite;
    }
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiCoopMerchantAccessTest {
    private static final long PRINCIPAL = 901L;
    private static final long OWNER = 900L;

    @Test
    void mybizRequiresFinancePermissionAndScopesAllReadsToMerchantOwner() {
        ICoopInviteService service = mock(ICoopInviteService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.FINANCE_READ)).thenReturn(
                new MerchantAccessContext(PRINCIPAL, 77L, OWNER, MerchantRoleCode.MERCHANT_FINANCE));
        ApiCoopController controller = loggedInController();
        ReflectionTestUtils.setField(controller, "coopInviteService", service);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);

        AjaxResult result = controller.mybiz(null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(access).require(PRINCIPAL, MerchantPermission.FINANCE_READ);
        verify(service).creditSummary(OWNER);
        verify(service).mySettlements(OWNER);
        verify(service).reviewSummary(OWNER);
    }

    @Test
    void listRejectsDisabledMerchantBeforeReadingAnyInviteOrPartnerContact() {
        ICoopInviteService service = mock(ICoopInviteService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MmsMerchant disabled = new MmsMerchant();
        disabled.setId(77L);
        disabled.setMemberId(PRINCIPAL);
        disabled.setStatus(1L);
        disabled.setAccountStatus(2L);
        disabled.setDelFlag(0);
        when(access.require(PRINCIPAL, MerchantPermission.COOP_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));
        when(merchants.selectMmsMerchantByMemberId(PRINCIPAL)).thenReturn(disabled);
        ApiCoopController controller = loggedInController();
        ReflectionTestUtils.setField(controller, "coopInviteService", service);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);

        AjaxResult result = controller.list(null);

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).selectCoopInviteListReadOnly(any(CoopInvite.class));
    }

    @Test
    void listUsesResolvedOwnerForMerchantSentAndReceivedQueries() {
        ICoopInviteService service = mock(ICoopInviteService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        IClubService clubs = mock(IClubService.class);
        when(access.require(PRINCIPAL, MerchantPermission.COOP_MANAGE)).thenReturn(
                new MerchantAccessContext(PRINCIPAL, 77L, OWNER, MerchantRoleCode.MERCHANT_MANAGER));
        when(service.selectCoopInviteListReadOnly(any(CoopInvite.class))).thenReturn(Collections.emptyList());
        when(clubs.selectClubsByMemberId(PRINCIPAL)).thenReturn(Collections.<Club>emptyList());
        ApiCoopController controller = loggedInController();
        ReflectionTestUtils.setField(controller, "coopInviteService", service);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", mock(IMmsMerchantService.class));
        ReflectionTestUtils.setField(controller, "clubService", clubs);

        AjaxResult result = controller.list(null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        ArgumentCaptor<CoopInvite> captor = ArgumentCaptor.forClass(CoopInvite.class);
        verify(service, org.mockito.Mockito.times(2)).selectCoopInviteListReadOnly(captor.capture());
        List<CoopInvite> queries = captor.getAllValues();
        assertEquals(OWNER, queries.get(0).getFromId());
        assertEquals("merchant", queries.get(1).getToType());
        assertEquals(OWNER, queries.get(1).getToId());
        verify(clubs).selectClubsByMemberId(PRINCIPAL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void receivedClubInviteNamesTheReceivingClubWithoutExposingPendingContact() {
        ICoopInviteService service = mock(ICoopInviteService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        IClubService clubs = mock(IClubService.class);
        when(access.require(PRINCIPAL, MerchantPermission.COOP_MANAGE)).thenReturn(
                new MerchantAccessContext(PRINCIPAL, 77L, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        Club own = new Club();
        own.setId(7001L); own.setMemberId(PRINCIPAL); own.setName("接收俱乐部");
        Club peer = new Club();
        peer.setId(7002L); peer.setMemberId(902L); peer.setName("发起俱乐部");
        peer.setPhone("13800000000");
        when(clubs.selectClubsByMemberId(PRINCIPAL)).thenReturn(Collections.singletonList(own));
        when(clubs.selectClubById(7001L)).thenReturn(own);
        when(clubs.selectClubById(7002L)).thenReturn(peer);
        CoopInvite invite = new CoopInvite();
        invite.setId(3L); invite.setInviteType(1); invite.setStatus(0);
        invite.setFromType("club"); invite.setFromId(7002L);
        invite.setToType("club"); invite.setToId(7001L);
        when(service.selectCoopInviteListReadOnly(any(CoopInvite.class))).thenAnswer(call -> {
            CoopInvite query = call.getArgument(0);
            return "club".equals(query.getToType()) && Long.valueOf(7001L).equals(query.getToId())
                    ? Collections.singletonList(invite) : Collections.emptyList();
        });
        ApiCoopController controller = loggedInController();
        ReflectionTestUtils.setField(controller, "coopInviteService", service);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "clubService", clubs);
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) controller.list(null).get(AjaxResult.DATA_TAG);
        CoopInvite received = ((List<CoopInvite>) data.get("received")).get(0);
        assertEquals("接收俱乐部", received.getViewerPartyName());
        assertEquals("发起俱乐部", received.getPartner().getName());
        org.junit.jupiter.api.Assertions.assertNull(received.getPartner().getPhone());
    }

    @Test
    void personalIdCollisionDoesNotExposeAnotherClubsInvite() {
        assertEquals(0, sentForClubInvite(902L, false).size());
    }

    @Test
    void ownedClubInviteAppearsOnceWhenClubIdEqualsMemberId() {
        List<CoopInvite> sent = sentForClubInvite(PRINCIPAL, true);
        assertEquals(1, sent.size());
        assertEquals("发起俱乐部", sent.get(0).getViewerPartyName());
    }

    @SuppressWarnings("unchecked")
    private List<CoopInvite> sentForClubInvite(long clubOwner, boolean ownsClub) {
        ICoopInviteService service = mock(ICoopInviteService.class);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        IClubService clubs = mock(IClubService.class);
        when(access.require(PRINCIPAL, MerchantPermission.COOP_MANAGE))
                .thenThrow(new ServiceException("无商家身份", 403));
        Club from = new Club();
        from.setId(PRINCIPAL); from.setMemberId(clubOwner); from.setName("发起俱乐部");
        Club to = new Club();
        to.setId(7002L); to.setMemberId(903L); to.setName("接收俱乐部");
        when(clubs.selectClubById(PRINCIPAL)).thenReturn(from);
        when(clubs.selectClubById(7002L)).thenReturn(to);
        when(clubs.selectClubsByMemberId(PRINCIPAL)).thenReturn(
                ownsClub ? Collections.singletonList(from) : Collections.emptyList());
        CoopInvite invite = new CoopInvite();
        invite.setId(10L); invite.setInviteType(1); invite.setStatus(0);
        invite.setFromType("club"); invite.setFromId(PRINCIPAL);
        invite.setToType("club"); invite.setToId(7002L);
        when(service.selectCoopInviteListReadOnly(any(CoopInvite.class))).thenAnswer(call -> {
            CoopInvite query = call.getArgument(0);
            return Long.valueOf(PRINCIPAL).equals(query.getFromId())
                    ? Collections.singletonList(invite) : Collections.emptyList();
        });
        ApiCoopController controller = loggedInController();
        ReflectionTestUtils.setField(controller, "coopInviteService", service);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", mock(IMmsMerchantService.class));
        ReflectionTestUtils.setField(controller, "clubService", clubs);
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) controller.list(null).get(AjaxResult.DATA_TAG);
        return (List<CoopInvite>) data.get("sent");
    }

    private static ApiCoopController loggedInController() {
        ApiCoopController controller = spy(new ApiCoopController());
        doReturn(PRINCIPAL).when(controller).getAppUserId();
        return controller;
    }
}

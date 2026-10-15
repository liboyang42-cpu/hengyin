package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiOfficialInviteVisibilityTest {
    @Test
    @SuppressWarnings("unchecked")
    void listOfficialInvites_queriesOnlyCurrentMerchantRecipient() {
        ICoopInviteService inviteService = mock(ICoopInviteService.class);
        IClubService clubService = mock(IClubService.class);
        ApiCoopController controller = new TestableController(201L);
        ReflectionTestUtils.setField(controller, "coopInviteService", inviteService);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", mock(ICmsTopicService.class));
        com.chengyinhub.business.access.merchant.IMerchantAccessService access =
                mock(com.chengyinhub.business.access.merchant.IMerchantAccessService.class);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "umsMemberService", org.mockito.Mockito.mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", org.mockito.Mockito.mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        when(access.require(201L, com.chengyinhub.business.access.merchant.MerchantPermission.COOP_MANAGE))
                .thenReturn(new com.chengyinhub.business.access.merchant.MerchantAccessContext(
                        201L, 8L, 201L,
                        com.chengyinhub.business.access.merchant.MerchantRoleCode.MERCHANT_OWNER));
        when(clubService.selectClubsByMemberId(201L)).thenReturn(Collections.emptyList());
        when(inviteService.selectCoopInviteListReadOnly(argThat(query -> query != null && Long.valueOf(201L).equals(query.getFromId()))))
                .thenReturn(Collections.emptyList());
        CoopInvite mine = new CoopInvite();
        mine.setId(11L);
        mine.setInviteType(CoopInvite.TYPE_OFFICIAL_MERCHANT);
        mine.setFromType(CoopInvite.FROM_OFFICIAL);
        mine.setToType(CoopInvite.TO_MERCHANT);
        mine.setToId(201L);
        mine.setStatus(0);
        when(inviteService.selectCoopInviteListReadOnly(argThat(query -> query != null && Long.valueOf(201L).equals(query.getToId()))))
                .thenReturn(Collections.singletonList(mine));

        AjaxResult result = controller.list(null);

        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<CoopInvite> received = (List<CoopInvite>) data.get("received");
        assertEquals(1, received.size());
        assertTrue(received.stream().allMatch(row -> Long.valueOf(201L).equals(row.getToId())),
                "received 不得包含发给其他商家的官方邀约");
        verify(inviteService).selectCoopInviteListReadOnly(argThat(query -> query != null
                && CoopInvite.TO_MERCHANT.equals(query.getToType()) && Long.valueOf(201L).equals(query.getToId())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleOfficialInvite_routesToIdempotentRegistrationPath() {
        ICoopInviteService inviteService = mock(ICoopInviteService.class);
        ApiCoopController controller = new TestableController(201L);
        ReflectionTestUtils.setField(controller, "coopInviteService", inviteService);
        com.chengyinhub.business.access.merchant.IMerchantAccessService access =
                mock(com.chengyinhub.business.access.merchant.IMerchantAccessService.class);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "umsMemberService", org.mockito.Mockito.mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", org.mockito.Mockito.mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        when(access.require(201L, com.chengyinhub.business.access.merchant.MerchantPermission.COOP_MANAGE))
                .thenReturn(new com.chengyinhub.business.access.merchant.MerchantAccessContext(
                        201L, 8L, 201L,
                        com.chengyinhub.business.access.merchant.MerchantRoleCode.MERCHANT_OWNER));
        ReflectionTestUtils.setField(controller, "notifyService",
                mock(com.chengyinhub.business.service.INotifyService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService",
                mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "cmsTopicService", mock(ICmsTopicService.class));
        CoopInvite persisted = new CoopInvite();
        persisted.setId(11L);
        persisted.setInviteType(CoopInvite.TYPE_OFFICIAL_MERCHANT);
        persisted.setFromType(CoopInvite.FROM_OFFICIAL);
        persisted.setFromId(100092L);
        persisted.setToType(CoopInvite.TO_MERCHANT);
        persisted.setToId(201L);
        persisted.setTopicId(7L);
        persisted.setStatus(0);
        when(inviteService.selectCoopInviteById(11L)).thenReturn(persisted);
        when(inviteService.acceptOfficialInviteWithLock(11L, 201L)).thenReturn(71L);
        CoopInvite request = new CoopInvite();
        request.setId(11L);
        request.setStatus(1);

        AjaxResult result = controller.handle(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(71L, data.get("registrationId"));
        verify(inviteService).acceptOfficialInviteWithLock(11L, 201L);
        verify(inviteService, never()).acceptInviteWithLock(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectOfficialInvite_routesToLockedOfficialPath() {
        ICoopInviteService inviteService = mock(ICoopInviteService.class);
        ApiCoopController controller = new TestableController(201L);
        ReflectionTestUtils.setField(controller, "coopInviteService", inviteService);
        ReflectionTestUtils.setField(controller, "notifyService",
                mock(com.chengyinhub.business.service.INotifyService.class));
        com.chengyinhub.business.access.merchant.IMerchantAccessService access =
                mock(com.chengyinhub.business.access.merchant.IMerchantAccessService.class);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "umsMemberService", org.mockito.Mockito.mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", org.mockito.Mockito.mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        when(access.require(201L, com.chengyinhub.business.access.merchant.MerchantPermission.COOP_MANAGE))
                .thenReturn(new com.chengyinhub.business.access.merchant.MerchantAccessContext(
                        201L, 8L, 201L,
                        com.chengyinhub.business.access.merchant.MerchantRoleCode.MERCHANT_OWNER));
        ReflectionTestUtils.setField(controller, "umsMemberService",
                mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "cmsTopicService", mock(ICmsTopicService.class));
        CoopInvite persisted = new CoopInvite();
        persisted.setId(11L);
        persisted.setInviteType(CoopInvite.TYPE_OFFICIAL_MERCHANT);
        persisted.setFromType(CoopInvite.FROM_OFFICIAL);
        persisted.setFromId(100092L);
        persisted.setToType(CoopInvite.TO_MERCHANT);
        persisted.setToId(201L);
        persisted.setTopicId(7L);
        persisted.setStatus(0);
        when(inviteService.selectCoopInviteById(11L)).thenReturn(persisted);
        CoopInvite request = new CoopInvite();
        request.setId(11L);
        request.setStatus(2);
        request.setHandleReason("当天无法接待");

        AjaxResult result = controller.handle(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(inviteService).rejectOfficialInviteWithLock(11L, 201L, "当天无法接待");
        verify(inviteService, never()).updateCoopInvite(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void merchantOfficialInbox_readsOnlyCurrentMerchantProjection() {
        ICoopInviteService inviteService = mock(ICoopInviteService.class);
        ApiOfficialEventController controller = new TestableOfficialController(201L);
        ReflectionTestUtils.setField(controller, "coopInviteService", inviteService);
        Map<String, Object> row = Collections.singletonMap("inviteId", 11L);
        when(inviteService.listOfficialMerchantInvites(201L)).thenReturn(Collections.singletonList(row));

        AjaxResult result = controller.merchantInvites();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(Collections.singletonList(row), result.get(AjaxResult.DATA_TAG));
        verify(inviteService).listOfficialMerchantInvites(201L);
        verify(inviteService, never()).listOfficialMerchantInvites(999L);
    }

    private static class TestableController extends ApiCoopController {
        private final Long memberId;
        private TestableController(Long memberId) { this.memberId = memberId; }
        @Override public Long getAppUserId() { return memberId; }
    }

    private static class TestableOfficialController extends ApiOfficialEventController {
        private final Long memberId;
        private TestableOfficialController(Long memberId) { this.memberId = memberId; }
        @Override public Long getAppUserId() { return memberId; }
    }
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMerchantOfferService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiCoopOfferAccessTest {
    private static final Long OPERATOR = 7002L;
    private static final Long OWNER = 9001L;

    private IMerchantAccessService accessService;
    private IMerchantOfferService offerService;
    private ICoopInviteService coopService;
    private ApiCoopController controller;

    @BeforeEach
    void setUp() {
        accessService = mock(IMerchantAccessService.class);
        offerService = mock(IMerchantOfferService.class);
        coopService = mock(ICoopInviteService.class);
        controller = new ApiCoopController() {
            @Override
            public Long getAppUserId() {
                return OPERATOR;
            }
        };
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(controller, "merchantOfferService", offerService);
        ReflectionTestUtils.setField(controller, "coopInviteService", coopService);
    }

    @Test
    void operatorPermissionUsesOwnerForOfferBusinessScope() {
        when(accessService.require(OPERATOR, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(
                        OPERATOR, 11L, OWNER, MerchantRoleCode.MERCHANT_MANAGER));
        when(offerService.enroll(any(MerchantChapterOffer.class), org.mockito.ArgumentMatchers.eq(OWNER)))
                .thenReturn(77L);

        AjaxResult result = controller.offerEnroll(new MerchantChapterOffer());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(offerService).enroll(any(MerchantChapterOffer.class), org.mockito.ArgumentMatchers.eq(OWNER));
    }

    @Test
    void inactiveMerchantCannotEnrollOffer() {
        when(accessService.require(OPERATOR, MerchantPermission.COOP_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        AjaxResult result = controller.offerEnroll(new MerchantChapterOffer());

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(offerService, never()).enroll(any(), any());
    }

    @Test
    void inactiveMerchantCannotAcceptPendingInvite() {
        CoopInvite invite = new CoopInvite();
        invite.setId(61L);
        invite.setStatus(0);
        invite.setToType("merchant");
        invite.setToId(OWNER);
        when(coopService.selectCoopInviteById(61L)).thenReturn(invite);
        when(accessService.require(OPERATOR, MerchantPermission.COOP_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));
        CoopInvite request = new CoopInvite();
        request.setId(61L);
        request.setStatus(1);

        AjaxResult result = controller.handle(request);

        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        verify(coopService, never()).acceptInviteWithLock(any(), any());
        verify(coopService, never()).acceptInviteWithLock(any(), any(), any());
    }

    @Test
    void merchantOperatorAcceptsInviteInOwnerScope() {
        CoopInvite invite = new CoopInvite();
        invite.setId(62L);
        invite.setStatus(0);
        invite.setToType("merchant");
        invite.setToId(OWNER);
        when(coopService.selectCoopInviteById(62L)).thenReturn(invite);
        when(accessService.require(OPERATOR, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(
                        OPERATOR, 11L, OWNER, MerchantRoleCode.MERCHANT_MANAGER));
        CoopInvite request = new CoopInvite();
        request.setId(62L);
        request.setStatus(1);

        AjaxResult result = controller.handle(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(coopService).acceptInviteWithLock(62L, null, null);
    }

    @Test
    void merchantAttachPerksUsesOwnerScopeAfterActiveGuard() {
        CoopInvite invite = new CoopInvite();
        invite.setId(51L);
        invite.setToType("merchant");
        when(coopService.selectCoopInviteById(51L)).thenReturn(invite);
        when(accessService.require(OPERATOR, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(
                        OPERATOR, 11L, OWNER, MerchantRoleCode.MERCHANT_MANAGER));
        when(coopService.attachPerks(51L, OWNER, Arrays.asList(1L, 2L))).thenReturn(2);
        Map<String, Object> body = new HashMap<>();
        body.put("inviteId", 51L);
        body.put("templateIds", Arrays.asList(1L, 2L));

        AjaxResult result = controller.perksAttach(body);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(coopService).attachPerks(51L, OWNER, Arrays.asList(1L, 2L));
    }

    @Test
    void clubAttachPerksKeepsClubPrincipalWithoutMerchantGuard() {
        CoopInvite invite = new CoopInvite();
        invite.setId(52L);
        invite.setToType("club");
        when(coopService.selectCoopInviteById(52L)).thenReturn(invite);
        when(coopService.attachPerks(52L, OPERATOR, Arrays.asList(3L))).thenReturn(1);
        Map<String, Object> body = new HashMap<>();
        body.put("inviteId", 52L);
        body.put("templateIds", Arrays.asList(3L));

        AjaxResult result = controller.perksAttach(body);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(accessService, never()).require(any(), any());
        verify(coopService).attachPerks(52L, OPERATOR, Arrays.asList(3L));
    }
}

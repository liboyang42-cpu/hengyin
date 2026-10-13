package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.IMerchantNodeDiscoveryService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMerchantDecorDiscoverySyncTest {

    @Test
    void saveDecorUpdateMissDoesNotReportSuccessOrRunDiscoveryAndMediaSideEffects() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        IMerchantNodeDiscoveryService discoveryService = mock(IMerchantNodeDiscoveryService.class);
        IWxContentSecurityService contentSecurityService = mock(IWxContentSecurityService.class);
        ApiMerchantController controller = new TestableController();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);
        ReflectionTestUtils.setField(controller, "merchantNodeDiscoveryService", discoveryService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", contentSecurityService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        injectOwnerAccess(controller);

        MmsMerchant unchanged = new MmsMerchant();
        unchanged.setId(77L);
        unchanged.setTags("旧标签");
        unchanged.setCoverImage("https://cdn.example/old.png");
        when(merchantService.selectMmsMerchantById(77L)).thenReturn(unchanged);
        when(merchantService.updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString())).thenReturn(0);
        MmsMerchant request = new MmsMerchant();
        request.setTags("新标签");
        request.setCoverImage("https://cdn.example/new.png");

        ServiceException error = assertThrows(ServiceException.class,
                () -> controller.saveDecor(request));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
        verify(discoveryService, never()).syncTags(anyLong(), anyString());
        verify(contentSecurityService, never()).submitMediaCheck(
                anyInt(), anyLong(), anyString(), any());
    }

    @Test
    void saveDecorUpdateMissRunsRequiredSideEffectsOnlyAfterMatchingExactRowReadback() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        IMerchantNodeDiscoveryService discoveryService = mock(IMerchantNodeDiscoveryService.class);
        IWxContentSecurityService contentSecurityService = mock(IWxContentSecurityService.class);
        ApiMerchantController controller = new TestableController();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);
        ReflectionTestUtils.setField(controller, "merchantNodeDiscoveryService", discoveryService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", contentSecurityService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        injectOwnerAccess(controller);

        MmsMerchant persisted = new MmsMerchant();
        persisted.setId(77L);
        persisted.setTags("夜间友好");
        persisted.setCoverImage("https://cdn.example/cover.png");
        when(merchantService.selectMmsMerchantById(77L)).thenReturn(persisted);
        when(merchantService.updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString())).thenReturn(0);
        MmsMerchant request = new MmsMerchant();
        request.setTags("夜间友好");
        request.setCoverImage("https://cdn.example/cover.png");

        AjaxResult result = controller.saveDecor(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(discoveryService).syncTags(77L, "夜间友好");
        verify(contentSecurityService).submitMediaCheck(
                IWxContentSecurityService.BIZ_TYPE_MERCHANT_PROFILE, 77L,
                "https://cdn.example/cover.png", null);
    }

    @Test
    void saveDecor_syncsOwnedMerchantTagsToDiscoveryNodes() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        IMerchantNodeDiscoveryService discoveryService = mock(IMerchantNodeDiscoveryService.class);
        IWxContentSecurityService contentSecurityService = mock(IWxContentSecurityService.class);
        ApiMerchantController controller = new TestableController();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);
        ReflectionTestUtils.setField(controller, "merchantNodeDiscoveryService", discoveryService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", contentSecurityService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        injectOwnerAccess(controller);

        MmsMerchant owned = new MmsMerchant();
        owned.setId(77L);
        when(merchantService.selectMmsMerchantById(77L)).thenReturn(owned);
        when(merchantService.updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString())).thenReturn(1);
        MmsMerchant request = new MmsMerchant();
        request.setTags("夜间友好,亲子");

        AjaxResult result = controller.saveDecor(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(discoveryService).syncTags(77L, "夜间友好,亲子");
    }

    @Test
    void saveDecor_persistsServiceLineAndRejectsOverlong() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        ApiMerchantController controller = new TestableController();
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);
        ReflectionTestUtils.setField(controller, "merchantNodeDiscoveryService", mock(IMerchantNodeDiscoveryService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        injectOwnerAccess(controller);

        MmsMerchant owned = new MmsMerchant();
        owned.setId(77L);
        when(merchantService.selectMmsMerchantById(77L)).thenReturn(owned);
        when(merchantService.updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString())).thenReturn(1);
        MmsMerchant request = new MmsMerchant();
        request.setServiceTag("¥5");
        request.setServiceText("健身体验卡");

        assertEquals(200, controller.saveDecor(request).get(AjaxResult.CODE_TAG));
        org.mockito.ArgumentCaptor<MmsMerchant> upd = org.mockito.ArgumentCaptor.forClass(MmsMerchant.class);
        verify(merchantService).updateAuthorizedProfile(
                upd.capture(), anyLong(), anyString());
        assertEquals("¥5", upd.getValue().getServiceTag());
        assertEquals("健身体验卡", upd.getValue().getServiceText());

        // 超列宽(varchar(8))必须在落库前被拒,且错误文案指明是服务标签闸
        MmsMerchant tooLong = new MmsMerchant();
        tooLong.setServiceTag("一二三四五六七八九");
        AjaxResult rejected = controller.saveDecor(tooLong);
        assertEquals(500, rejected.get(AjaxResult.CODE_TAG));
        assertEquals("服务标签最多8个字", rejected.get(AjaxResult.MSG_TAG));
    }

    private static class TestableController extends ApiMerchantController {
        @Override
        public Long getAppUserId() {
            return 9L;
        }
    }

    private static void injectOwnerAccess(ApiMerchantController controller) {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(9L, MerchantPermission.PROFILE_WRITE))
                .thenReturn(new MerchantAccessContext(9L, 77L, 9L,
                        MerchantRoleCode.MERCHANT_OWNER));
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
    }
}

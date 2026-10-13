package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiMerchantWriteReceiptControllerTest {
    private static final long OWNER = 9L;
    private static final long MERCHANT_ROW = 77L;

    @Test
    void businessStatusUpdateMissConflictsWhenExactRowDoesNotContainRequestedValue() {
        Fixture fixture = fixture();
        fixture.loaded.setBusinessStatus(1);
        MmsMerchant unchanged = merchantWithId();
        unchanged.setBusinessStatus(1);
        when(fixture.merchants.selectMmsMerchantById(MERCHANT_ROW))
                .thenReturn(fixture.loaded, unchanged);
        when(fixture.merchants.updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString())).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> fixture.controller.businessStatusUpdate("0"));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
    }

    @Test
    void businessStatusUpdateMissIsIdempotentOnlyAfterExactRowReadbackMatches() {
        Fixture fixture = fixture();
        fixture.loaded.setBusinessStatus(0);
        MmsMerchant persisted = merchantWithId();
        persisted.setBusinessStatus(0);
        when(fixture.merchants.selectMmsMerchantById(MERCHANT_ROW))
                .thenReturn(fixture.loaded, persisted);
        when(fixture.merchants.updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString())).thenReturn(0);

        AjaxResult result = fixture.controller.businessStatusUpdate("0");

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void coopProfileUpdateMissConflictsWhenExactRowDoesNotContainRequestedValues() {
        Fixture fixture = fixture();
        fixture.loaded.setCapacity(10);
        MmsMerchant unchanged = merchantWithId();
        unchanged.setCapacity(10);
        when(fixture.merchants.selectMmsMerchantById(MERCHANT_ROW))
                .thenReturn(fixture.loaded, unchanged);
        when(fixture.merchants.updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString())).thenReturn(0);
        MmsMerchant request = new MmsMerchant();
        request.setCapacity(20);

        ServiceException error = assertThrows(ServiceException.class,
                () -> fixture.controller.saveCoopProfile(request));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
    }

    @Test
    void coopProfileUpdateMissIsIdempotentOnlyAfterExactRowReadbackMatches() {
        Fixture fixture = fixture();
        fixture.loaded.setCapacity(20);
        MmsMerchant persisted = merchantWithId();
        persisted.setCapacity(20);
        when(fixture.merchants.selectMmsMerchantById(MERCHANT_ROW))
                .thenReturn(fixture.loaded, persisted);
        when(fixture.merchants.updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString())).thenReturn(0);
        MmsMerchant request = new MmsMerchant();
        request.setCapacity(20);

        AjaxResult result = fixture.controller.saveCoopProfile(request);

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
    }

    private static Fixture fixture() {
        Fixture fixture = new Fixture();
        fixture.loaded = merchantWithId();
        fixture.merchants = mock(IMmsMerchantService.class);
        when(fixture.merchants.selectMmsMerchantById(MERCHANT_ROW)).thenReturn(fixture.loaded);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(anyLong(), anyString())).thenReturn(new MerchantAccessContext(
                OWNER, MERCHANT_ROW, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        fixture.controller = new LoggedInController();
        ReflectionTestUtils.setField(fixture.controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(fixture.controller, "mmsMerchantService", fixture.merchants);
        ReflectionTestUtils.setField(fixture.controller, "wxContentSecurityService",
                mock(IWxContentSecurityService.class));
        ReflectionTestUtils.setField(fixture.controller, "umsMemberService", mock(IUmsMemberService.class));
        return fixture;
    }

    private static MmsMerchant merchantWithId() {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(MERCHANT_ROW);
        merchant.setMemberId(OWNER);
        return merchant;
    }

    private static class LoggedInController extends ApiMerchantController {
        @Override
        public Long getAppUserId() {
            return OWNER;
        }
    }

    private static class Fixture {
        private LoggedInController controller;
        private IMmsMerchantService merchants;
        private MmsMerchant loaded;
    }
}

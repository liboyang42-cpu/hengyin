package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsMemberAddress;
import com.chengyinhub.business.service.IUmsMemberAddressService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiUmsMemberControllerAddressSecurityTest {

    private IUmsMemberAddressService addressService;
    private TestableApiUmsMemberController controller;

    @BeforeEach
    void setUp() {
        addressService = mock(IUmsMemberAddressService.class);
        controller = new TestableApiUmsMemberController(1001L);
        ReflectionTestUtils.setField(controller, "umsMemberAddressService", addressService);
    }

    @Test
    void getUserAddressInfoRejectsOtherMemberAddress() {
        when(addressService.selectOwnedAddressById(10L, 1001L)).thenReturn(null);

        AjaxResult result = controller.getUserAddressInfo("10");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("地址不存在或不可用", result.get(AjaxResult.MSG_TAG));
        verify(addressService, never()).selectUmsMemberAddressById(10L);
    }

    @Test
    void getUserAddressInfoAllowsOwnAddress() {
        UmsMemberAddress address = address(10L, 1001L);
        when(addressService.selectOwnedAddressById(10L, 1001L)).thenReturn(address);

        AjaxResult result = controller.getUserAddressInfo("10");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertSame(address, result.get(AjaxResult.DATA_TAG));
    }

    @Test
    void deleteUserAddressRejectsOtherMemberAddress() {
        when(addressService.deleteOwnedAddress(10L, 1001L)).thenReturn(0);

        AjaxResult result = controller.deleteUserAddress("10");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("地址不存在或不可用", result.get(AjaxResult.MSG_TAG));
        verify(addressService, never()).deleteUmsMemberAddressById(10L);
    }

    @Test
    void userAddressActionRejectsEditingOtherMemberAddress() {
        when(addressService.updateOwnedAddress(any(UmsMemberAddress.class), eq(1001L))).thenReturn(0);

        AjaxResult result = controller.userAddressActoin("10", "Alice", "13800138000", "上海", "测试地址", "0");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("地址不存在或不可用", result.get(AjaxResult.MSG_TAG));
        verify(addressService, never()).updateUmsMemberAddress(any(UmsMemberAddress.class));
    }

    @Test
    void userAddressCreateRequiresIdempotencyKeyAndReturnsStableAddress() {
        UmsMemberAddress saved = address(77L, 1001L);
        when(addressService.insertOwnedAddressIdempotent(any(UmsMemberAddress.class), eq(1001L),
                eq("address_1234567890"))).thenReturn(saved);

        AjaxResult result = controller.userAddressActoin(null, "Alice", "13800138000", "上海",
                "测试地址", "0", "address_1234567890");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertSame(saved, result.get(AjaxResult.DATA_TAG));
    }

    @Test
    void legacyAddressCreateWithoutRequestIdStillReturnsSavedAddress() {
        UmsMemberAddress saved = address(78L, 1001L);
        when(addressService.insertOwnedAddressIdempotent(any(UmsMemberAddress.class), eq(1001L), isNull()))
                .thenReturn(saved);

        AjaxResult result = controller.userAddressActoin(null, "Alice", "13800138000", "上海", "测试地址", "0");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertSame(saved, result.get(AjaxResult.DATA_TAG));
    }

    @Test
    void userAddressActionRejectsBlankNameBeforeAnyWrite() {
        AjaxResult result = controller.userAddressActoin(null, "   ", "13800138000", "上海",
                "测试地址", "0", "address_1234567890");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("请输入姓名", result.get(AjaxResult.MSG_TAG));
        verify(addressService, never()).insertOwnedAddressIdempotent(any(), any(), any());
        verify(addressService, never()).updateOwnedAddress(any(), any());
    }

    @Test
    void userAddressActionRejectsInvalidMobileBeforeAnyWrite() {
        AjaxResult result = controller.userAddressActoin("10", "Alice", "12345", "上海",
                "测试地址", "0", null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("请输入正确的手机号", result.get(AjaxResult.MSG_TAG));
        verify(addressService, never()).insertOwnedAddressIdempotent(any(), any(), any());
        verify(addressService, never()).updateOwnedAddress(any(), any());
    }

    @Test
    void getUserAddressInfoRejectsDeletedOwnAddressWithoutReturningPii() {
        UmsMemberAddress address = address(10L, 1001L);
        address.setDelFlag(2);
        when(addressService.selectOwnedAddressById(10L, 1001L)).thenReturn(null);

        AjaxResult result = controller.getUserAddressInfo("10");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("地址不存在或不可用", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void setDefaultRejectsUnavailableAddressWithoutClearingCurrentDefault() {
        when(addressService.setOwnedAddressDefault(10L, 1001L)).thenReturn(0);

        AjaxResult result = controller.setDefaultUserAddress("10");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("地址不存在或不可用", result.get(AjaxResult.MSG_TAG));
        verify(addressService, never()).updateUmsMemberAddressIsDefault(any(UmsMemberAddress.class));
    }

    private static UmsMemberAddress address(Long id, Long memberId) {
        UmsMemberAddress address = new UmsMemberAddress();
        address.setId(id);
        address.setMemberId(memberId);
        address.setFullName("Alice");
        address.setMobilePhone("13800138000");
        address.setDelFlag(0);
        return address;
    }

    private static class TestableApiUmsMemberController extends ApiUmsMemberController {
        private final Long appUserId;

        private TestableApiUmsMemberController(Long appUserId) {
            this.appUserId = appUserId;
        }

        @Override
        public Long getAppUserId() {
            return appUserId;
        }
    }
}

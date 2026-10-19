package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.vo.MerchantFinancePage;
import com.chengyinhub.business.domain.vo.MerchantRedemptionView;
import com.chengyinhub.business.service.impl.MerchantFinanceQueryService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiMerchantAccrualBasisAccessTest {
    private static final class Fixture {
        final ApiMerchantController controller = new ApiMerchantController() {
            @Override public Long getAppUserId() { return 9002L; }
        };
        final IMerchantAccessService access = mock(IMerchantAccessService.class);
        final MerchantFinanceQueryService finance = mock(MerchantFinanceQueryService.class);
        Fixture(String role) {
            when(access.require(eq(9002L), anyString())).thenReturn(new MerchantAccessContext(9002L, 11L, 9001L, role));
            ReflectionTestUtils.setField(controller, "merchantAccessService", access);
            ReflectionTestUtils.setField(controller, "merchantFinanceQueryService", finance);
        }
    }
    private static MerchantRedemptionView row() {
        MerchantRedemptionView row = new MerchantRedemptionView();
        row.setRecordKey("redemption:7"); row.setRecordType("redemption"); row.setRecordId("7");
        row.setFulfillmentState("ACTIVE"); row.setSettlementRoute("CHAPTER_OFFER");
        row.setSettlementState("PENDING"); row.setDisplayState("PENDING_SETTLEMENT");
        row.setUnitFee("12.00"); row.setHeadCount(1); row.setSettlementAmount("12.00");
        return row;
    }
    private static Map<String, Object> body() {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("recordType", "redemption"); result.put("recordId", "7");
        result.put("merchantMemberId", 999L); return result;
    }
    @Test void financeDetailKeepsBasisAndUsesResolvedOwnerRatherThanClientOrPrincipal() {
        Fixture f = new Fixture(MerchantRoleCode.MERCHANT_FINANCE);
        when(f.finance.redemptionDetail(9001L, "redemption", "7")).thenReturn(row());
        AjaxResult response = f.controller.financeRedemptionDetail(body());
        MerchantRedemptionView value = (MerchantRedemptionView) response.get(AjaxResult.DATA_TAG);
        assertEquals("12.00", value.getUnitFee()); assertEquals(Integer.valueOf(1), value.getHeadCount());
        verify(f.access).require(9002L, MerchantPermission.VERIFY_RECORD_READ);
        verify(f.finance).redemptionDetail(9001L, "redemption", "7"); verifyNoMoreInteractions(f.finance);
    }
    @Test void verifyOnlyDetailRedactsBothBasisFieldsAlongWithFinance() {
        Fixture f = new Fixture(MerchantRoleCode.MERCHANT_CHECKIN);
        when(f.finance.redemptionDetail(9001L, "redemption", "7")).thenReturn(row());
        AjaxResult response = f.controller.financeRedemptionDetail(body());
        MerchantRedemptionView value = (MerchantRedemptionView) response.get(AjaxResult.DATA_TAG);
        assertNull(value.getUnitFee()); assertNull(value.getHeadCount()); assertNull(value.getSettlementAmount());
        assertNull(value.getSettlementState()); assertNull(value.getSettlementRoute());
        assertEquals("ACTIVE", value.getFulfillmentState()); assertEquals("7", value.getRecordId());
    }
    @Test void verifyOnlyListRedactsBasisAndPreservesOriginalAllFilterRule() {
        Fixture f = new Fixture(MerchantRoleCode.MERCHANT_MANAGER);
        MerchantFinancePage<MerchantRedemptionView> page = new MerchantFinancePage<MerchantRedemptionView>();
        page.setRows(Collections.singletonList(row())); page.setTotal(1); page.setPageNum(1); page.setPageSize(20);
        when(f.finance.redemptions(9001L, "all", 1, 20)).thenReturn(page);
        Map<String, Object> query = new HashMap<String, Object>(); query.put("filter", "settled");
        f.controller.financeRedemptions(query);
        assertNull(page.getRows().get(0).getUnitFee()); assertNull(page.getRows().get(0).getHeadCount());
        verify(f.finance).redemptions(9001L, "all", 1, 20); verifyNoMoreInteractions(f.finance);
    }
    @Test void deniedRoleNeverCallsTheFinanceQuery() {
        Fixture f = new Fixture(MerchantRoleCode.MERCHANT_CHECKIN);
        when(f.access.require(9002L, MerchantPermission.VERIFY_RECORD_READ)).thenThrow(new ServiceException("denied"));
        assertThrows(ServiceException.class, () -> f.controller.financeRedemptionDetail(body()));
        assertThrows(ServiceException.class, () -> f.controller.financeRedemptions(Collections.<String, Object>emptyMap()));
        verifyNoInteractions(f.finance);
    }
}

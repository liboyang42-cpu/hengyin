package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ExploreBundleRefundHold;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FreeExploreBundleRefundContractTest {
    @Test
    void preparationRequiresActualCallerTransaction() {
        var service = new FreeExploreBundleRefundServiceImpl(mock(CmsRegistrationMapper.class), mock(RefundApplicationMapper.class),
                mock(ExploreBundleMapper.class), mock(ExploreBundleRefundMapper.class));
        assertThrows(IllegalStateException.class, () -> service.prepareFullRefund(1L, BigDecimal.ONE));
        assertThrows(IllegalStateException.class, () -> service.syncRefundOutcome(1L));
    }

    @Test
    void financialEnumsNeverTreatApprovalUnknownFailureOrManualAsOriginalChannelArrival() {
        RefundApplication app = new RefundApplication(); app.setStatus(1);
        for (int payout : new int[]{0, 5, 6}) {
            app.setPayoutStatus(payout);
            assertEquals("APPROVED", ReflectionTestUtils.invokeMethod(FreeExploreBundleRefundServiceImpl.class, "outcome", app));
        }
        for (int payout : new int[]{2, 3, 4}) {
            app.setPayoutStatus(payout);
            assertEquals("MANUAL_REVIEW_HELD", ReflectionTestUtils.invokeMethod(FreeExploreBundleRefundServiceImpl.class, "outcome", app));
        }
        app.setStatus(2); app.setPayoutStatus(5);
        assertEquals("REJECTED_HELD", ReflectionTestUtils.invokeMethod(FreeExploreBundleRefundServiceImpl.class, "outcome", app));
        app.setStatus(1); app.setPayoutStatus(1);
        assertEquals("SUCCEEDED", ReflectionTestUtils.invokeMethod(FreeExploreBundleRefundServiceImpl.class, "outcome", app));
    }

    @Test
    void refundHoldIdentifiersKeepLongPrecisionOnWire() throws Exception {
        ExploreBundleRefundHold hold = new ExploreBundleRefundHold();
        hold.setRegistrationId(Long.MAX_VALUE); hold.setHolderMemberId(Long.MAX_VALUE);
        hold.setSourceRegistrationId(Long.MAX_VALUE); hold.setRefundId(Long.MAX_VALUE); hold.setVersion(3);
        ObjectMapper json = new ObjectMapper(); var tree = json.readTree(json.writeValueAsString(hold));
        for (String key : new String[]{"registrationId", "holderMemberId", "sourceRegistrationId", "refundId"}) {
            assertEquals(Long.toString(Long.MAX_VALUE), tree.get(key).textValue());
        }
        assertEquals(3, tree.get("version").intValue());
    }
}

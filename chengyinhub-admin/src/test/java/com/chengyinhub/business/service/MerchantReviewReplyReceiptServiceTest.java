package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.mapper.MerchantReviewReplyReceiptMapper;
import com.chengyinhub.business.service.support.MerchantReviewReplyReceiptService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MerchantReviewReplyReceiptServiceTest {
    private final IMerchantAccessService access = mock(IMerchantAccessService.class);
    private final MerchantReviewReplyReceiptMapper mapper = mock(MerchantReviewReplyReceiptMapper.class);
    private final MerchantReviewReplyReceiptService service = new MerchantReviewReplyReceiptService();
    private static final String ACTION = "MERCHANT_REVIEW_REPLIED";
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "accessService", access);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        when(access.require(7L, MerchantPermission.MARKETING_WRITE)).thenReturn(
                new MerchantAccessContext(7L, 12L, 8L, MerchantRoleCode.MERCHANT_MANAGER));
    }
    @Test void positiveReceiptUsesCurrentMerchantOwnerAndActorAndPreservesId() {
        long review = 9007199254740993L;
        when(mapper.countApplied(12L, 8L, 7L, review, ACTION, "reply-1234")).thenReturn(1);
        var result = service.receipt(7L, review, ACTION, "reply-1234");
        assertEquals("APPLIED", result.get("state"));
        assertEquals("9007199254740993", result.get("reviewId"));
        assertEquals(4, result.size());
        verify(mapper).countApplied(12L, 8L, 7L, review, ACTION, "reply-1234");
    }
    @Test void MissingOrOtherActorsReceiptNeverProvesNotWritten() {
        assertEquals("UNKNOWN", service.receipt(7L, 71L, ACTION, "reply-1234").get("state"));
        verify(mapper).countApplied(12L, 8L, 7L, 71L, ACTION, "reply-1234");
    }
    @Test void permissionFailureCannotReadAudit() {
        when(access.require(9L, MerchantPermission.MARKETING_WRITE)).thenThrow(new ServiceException("forbidden", 403));
        assertThrows(ServiceException.class, () -> service.receipt(9L, 71L, ACTION, "reply-1234"));
        verifyNoInteractions(mapper);
    }
    @Test void invalidActionOrRequestDoesNotQuery() {
        assertThrows(ServiceException.class, () -> service.receipt(7L, 71L, "MERCHANT_REVIEW_SUBMITTED", "reply-1234"));
        assertThrows(ServiceException.class, () -> service.receipt(7L, 71L, ACTION, "bad request"));
        assertThrows(ServiceException.class, () -> service.receipt(7L, 0L, ACTION, "reply-1234"));
        verifyNoInteractions(mapper);
    }
}

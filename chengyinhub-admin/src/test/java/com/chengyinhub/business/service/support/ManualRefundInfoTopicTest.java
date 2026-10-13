package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.ActivityCancelManualRefundCase;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.ActivityCancelManualRefundCaseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FX-05:主题票(owner_type=1)转人工后,订单详情同样要读到工单状态;非主题/场次票不查。 */
class ManualRefundInfoTopicTest {

    private RegistrationCancellationReadService service(ActivityCancelManualRefundCaseMapper mapper) {
        RegistrationCancellationReadService s = new RegistrationCancellationReadService(null, null);
        ReflectionTestUtils.setField(s, "activityCancelManualRefundCaseMapper", mapper);
        return s;
    }

    private CmsRegistration reg(long ownerType) {
        CmsRegistration r = new CmsRegistration();
        r.setId(2L);
        r.setMemberId(88L);
        r.setOwnerType(ownerType);
        r.setOwnerId(9L);
        r.setParentOrderNo("P1");
        return r;
    }

    @Test
    void topicTicketReadsItsManualRefundCaseStatus() {
        ActivityCancelManualRefundCaseMapper mapper = mock(ActivityCancelManualRefundCaseMapper.class);
        when(mapper.selectPlayerCaseStatus(2L, 88L)).thenReturn(ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT);
        CmsRegistration r = reg(1L);

        service(mapper).attachManualRefundInfo(r);

        assertEquals(ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT, r.getManualRefundCaseStatus());
        assertEquals("该订单正在人工评估退款", r.getRefundInfo().reason);
        assertEquals(false, r.getRefundInfo().refundable);
        assertEquals(true, r.getRefundInfo().manualReview);
    }

    @Test
    void unknownOwnerTypeIsNotQueried() {
        ActivityCancelManualRefundCaseMapper mapper = mock(ActivityCancelManualRefundCaseMapper.class);
        CmsRegistration r = reg(3L);

        service(mapper).attachManualRefundInfo(r);

        assertNull(r.getManualRefundCaseStatus());
        verify(mapper, never()).selectPlayerCaseStatus(any(), any());
    }
}

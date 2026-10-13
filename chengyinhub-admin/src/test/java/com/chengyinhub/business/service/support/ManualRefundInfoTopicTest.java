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
    void existingPlayerCaseReturnsExactStatusWithoutExecutingRefund() {
        ActivityCancelManualRefundCaseMapper mapper = mock(ActivityCancelManualRefundCaseMapper.class);
        String[] statuses = {
                ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT,
                ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_REQUIRED,
                ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_RECORDED,
                ActivityCancelManualRefundCase.STATUS_MANUAL_REFUND_EVIDENCE_VERIFIED,
                ActivityCancelManualRefundCase.STATUS_NO_REFUND
        };
        String[] messages = {
                "本单已转平台人工评估，尚未退款",
                "人工评估已通过，等待退款处理",
                "人工退款已登记，等待凭证复核",
                "人工退款凭证已复核",
                "人工评估结果：不予退款"
        };
        RegistrationCancellationReadService service = service(mapper);

        for (int i = 0; i < statuses.length; i++) {
            when(mapper.selectPlayerCaseStatus(2L, 88L)).thenReturn(statuses[i]);

            RegistrationCancellationReadService.Result result =
                    service.readPlayerManualRefundCase(2L, 88L);

            assertEquals(messages[i], result.getMessage());
            assertEquals(2L, result.getData().get("registrationId"));
            assertEquals("MANUAL_REVIEW", result.getData().get("cancellationStatus"));
            assertEquals("MANUAL_REVIEW", result.getData().get("cashRefundStatus"));
            assertEquals(statuses[i], result.getData().get("manualRefundCaseStatus"));
            assertNull(result.getData().get("cashRefundAmount"));
        }
    }

    @Test
    void missingOrOtherPlayersCaseReturnsNoFeedback() {
        ActivityCancelManualRefundCaseMapper mapper = mock(ActivityCancelManualRefundCaseMapper.class);
        RegistrationCancellationReadService service = service(mapper);

        assertNull(service.readPlayerManualRefundCase(2L, 77L));
        assertNull(service.readPlayerManualRefundCase(0L, 88L));

        verify(mapper).selectPlayerCaseStatus(2L, 77L);
        verify(mapper, never()).selectPlayerCaseStatus(0L, 88L);
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

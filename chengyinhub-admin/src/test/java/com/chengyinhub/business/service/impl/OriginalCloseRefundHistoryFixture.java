package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CoopSettlementOriginalMapper;
import java.util.List;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.mock;

/** Explicit refund-history input for old composition units; not a MySQL/fence/ledger persistence proof. */
public final class OriginalCloseRefundHistoryFixture {
    private OriginalCloseRefundHistoryFixture() { }

    /** Empty means this fixture explicitly has no approved refunds; null models an unavailable read. */
    public static CoopSettlementOriginalMapper install(CmsTopicServiceImpl service, Long topicId, List<Long> approvedIds) {
        if (topicId == null || topicId <= 0) throw new IllegalArgumentException("fixture topic required");
        List<Long> original = approvedIds == null ? null : List.copyOf(approvedIds);
        CoopSettlementOriginalMapper rows = mock(CoopSettlementOriginalMapper.class, call -> {
            if (call.getMethod().getDeclaringClass() == Object.class)
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
            if (!"selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate".equals(call.getMethod().getName())
                    || !topicId.equals(call.getArgument(0))) {
                throw new AssertionError("Unmodelled original-refund fixture read: " + call.getMethod().getName());
            }
            return original;
        });
        ReflectionTestUtils.setField(service, "coopSettlementOriginalMapper", rows);
        // These original composition units do not model provider/close/component persistence.
        ReflectionTestUtils.setField(service, "coopSettlementOriginalProducer", mock(CoopSettlementOriginalProducer.class));
        return rows;
    }
}

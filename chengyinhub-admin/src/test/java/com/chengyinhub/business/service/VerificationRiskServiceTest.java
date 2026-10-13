package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.VerificationReview;
import com.chengyinhub.business.mapper.VerificationReviewMapper;
import com.chengyinhub.business.service.impl.VerificationRiskService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerificationRiskServiceTest {
    @Test
    void highRateAndBurst_entersManualQueue() {
        VerificationReviewMapper mapper = mock(VerificationReviewMapper.class);
        VerificationRiskService service = new VerificationRiskService(new BigDecimal("0.90"), 8, 5);
        ReflectionTestUtils.setField(service, "reviewMapper", mapper);
        when(mapper.insertIgnore(any())).thenReturn(1);

        // productType = 1(①② 经典定向)。③ 探店日的分支见 VerificationRiskFreeExploreTest。
        assertTrue(service.evaluateAndQueue(7L, 99L, 10, 9, 8, 1));
        verify(mapper).insertIgnore(any(VerificationReview.class));
    }

    @Test
    void ordinarySample_doesNotEnterManualQueue() {
        VerificationReviewMapper mapper = mock(VerificationReviewMapper.class);
        VerificationRiskService service = new VerificationRiskService(new BigDecimal("0.90"), 8, 5);
        ReflectionTestUtils.setField(service, "reviewMapper", mapper);

        assertFalse(service.evaluateAndQueue(7L, 99L, 20, 12, 2, 1));
        verify(mapper, never()).insertIgnore(any());
    }
}

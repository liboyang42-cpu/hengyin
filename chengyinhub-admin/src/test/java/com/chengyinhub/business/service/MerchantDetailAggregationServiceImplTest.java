package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.MerchantOpsVO;
import com.chengyinhub.business.mapper.MerchantReviewMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.MerchantDetailAggregationServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MerchantDetailAggregationServiceImplTest {
    @Test
    void aggregationExposesOnlyOwnedSourceAndFreshness() {
        MmsMerchantMapper merchantMapper = mock(MmsMerchantMapper.class);
        MerchantReviewMapper reviewMapper = mock(MerchantReviewMapper.class);
        MmsMerchant merchant = activeMerchant();
        Date reviewUpdatedAt = new Date(1700000000000L);
        Map<String, Object> summary = new HashMap<>();
        summary.put("reviewCount", 5L);
        summary.put("averageRating", new BigDecimal("4.6"));
        when(merchantMapper.selectMmsMerchantById(9L)).thenReturn(merchant);
        when(reviewMapper.selectPublicRatingSummary(9L)).thenReturn(summary);
        when(reviewMapper.selectPublicReviewsUpdatedAt(9L)).thenReturn(reviewUpdatedAt);

        MerchantOpsVO.Detail result = new MerchantDetailAggregationServiceImpl(merchantMapper, reviewMapper)
                .getPublicDetail(9L);

        assertEquals("CHENGYIN", result.getSource());
        assertEquals(1, result.getReviews().getSources().size());
        assertEquals("CHENGYIN", result.getReviews().getSources().get(0));
        assertEquals(reviewUpdatedAt, result.getReviews().getUpdatedAt());
        assertEquals("AVAILABLE", result.getReviews().getSummaryStatus());
        assertNotNull(result.getUpdatedAt());
        MerchantOpsVO.QuickAction save = result.getQuickActions().stream()
                .filter(action -> "SAVE".equals(action.getCode())).findFirst().orElseThrow(AssertionError::new);
        assertFalse(save.getAvailable());
        assertEquals("NOT_SUPPORTED", save.getUnavailableReason());
    }

    private MmsMerchant activeMerchant() {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(9L);
        merchant.setName("商家");
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        merchant.setUpdateTime(new Date());
        return merchant;
    }
}

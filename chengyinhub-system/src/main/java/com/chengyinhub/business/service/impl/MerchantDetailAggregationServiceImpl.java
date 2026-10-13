package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.MerchantOpsVO;
import com.chengyinhub.business.mapper.MerchantReviewMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.IMerchantDetailAggregationService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

@Service
public class MerchantDetailAggregationServiceImpl implements IMerchantDetailAggregationService {
    static final String SOURCE = "CHENGYIN";
    private static final long MIN_SUMMARY_SAMPLE = 3L;

    private final MmsMerchantMapper merchantMapper;
    private final MerchantReviewMapper reviewMapper;

    public MerchantDetailAggregationServiceImpl(MmsMerchantMapper merchantMapper, MerchantReviewMapper reviewMapper) {
        this.merchantMapper = merchantMapper;
        this.reviewMapper = reviewMapper;
    }

    @Override
    public MerchantOpsVO.Detail getPublicDetail(Long merchantId) {
        if (merchantId == null || merchantId <= 0) throw new ServiceException("商家参数无效", 400);
        MmsMerchant merchant = merchantMapper.selectMmsMerchantById(merchantId);
        if (merchant == null || !Long.valueOf(1L).equals(merchant.getStatus())
                || !Long.valueOf(1L).equals(merchant.getAccountStatus())
                || Integer.valueOf(2).equals(merchant.getDelFlag())) {
            throw new ServiceException("商家不存在或不可访问", 404);
        }

        MerchantOpsVO.Detail detail = new MerchantOpsVO.Detail();
        detail.setMerchantId(merchant.getId());
        detail.setName(merchant.getName());
        Date merchantUpdatedAt = merchant.getUpdateTime() == null ? merchant.getCreateTime() : merchant.getUpdateTime();
        if (merchantUpdatedAt == null) merchantUpdatedAt = new Date();
        detail.setUpdatedAt(merchantUpdatedAt);
        detail.setSource(SOURCE);
        detail.setPlay(play(merchant));
        detail.setVibe(vibe(merchant));
        detail.setReviews(reviews(merchantId, merchantUpdatedAt));
        detail.getQuickActions().add(action("CALL", "电话", "NATIVE_CALL", merchant.getPhone(), hasText(merchant.getPhone()), "MISSING_PHONE"));
        boolean hasLocation = Integer.valueOf(1).equals(merchant.getLocationVerified())
                && merchant.getLocationLat() != null && merchant.getLocationLng() != null;
        detail.getQuickActions().add(action("DIRECTIONS", "导航", "NATIVE_MAP",
                hasLocation ? merchant.getLocationLat() + "," + merchant.getLocationLng() : null,
                hasLocation, "MISSING_VERIFIED_LOCATION"));
        boolean safeWebsite = merchant.getWebsite() != null
                && (merchant.getWebsite().startsWith("https://") || merchant.getWebsite().startsWith("http://"));
        detail.getQuickActions().add(action("WEBSITE", "官网", "WEB_URL", safeWebsite ? merchant.getWebsite() : null,
                safeWebsite, "MISSING_SAFE_WEBSITE"));
        detail.getQuickActions().add(action("SAVE", "收藏", "UNSUPPORTED", null, false, "NOT_SUPPORTED"));
        return detail;
    }

    private MerchantOpsVO.PlaySection play(MmsMerchant merchant) {
        MerchantOpsVO.PlaySection section = new MerchantOpsVO.PlaySection();
        section.setBusinessTime(merchant.getBusinessTime());
        section.setServiceText(merchant.getServiceText());
        section.setServiceTag(merchant.getServiceTag());
        section.setFeaturedType(merchant.getFeaturedType());
        section.setFeaturedId(merchant.getFeaturedId());
        return section;
    }

    private MerchantOpsVO.VibeSection vibe(MmsMerchant merchant) {
        MerchantOpsVO.VibeSection section = new MerchantOpsVO.VibeSection();
        section.setDescription(merchant.getDescription());
        section.setSlogan(merchant.getSlogan());
        section.setCityRole(merchant.getCityRole());
        section.setTags(merchant.getTags());
        section.setGallery(merchant.getGallery());
        return section;
    }

    private MerchantOpsVO.ReviewSection reviews(Long merchantId, Date merchantUpdatedAt) {
        Map<String, Object> summary = reviewMapper.selectPublicRatingSummary(merchantId);
        Number totalValue = number(summary, "reviewCount");
        long total = totalValue == null ? 0L : totalValue.longValue();
        MerchantOpsVO.ReviewSection section = new MerchantOpsVO.ReviewSection();
        section.setTotal(total);
        Number average = number(summary, "averageRating");
        if (average != null) section.setAverageRating(new BigDecimal(average.toString()));
        section.setSummaryStatus(total >= MIN_SUMMARY_SAMPLE ? "AVAILABLE" : "INSUFFICIENT_SAMPLE");
        Date reviewsUpdatedAt = reviewMapper.selectPublicReviewsUpdatedAt(merchantId);
        section.setUpdatedAt(reviewsUpdatedAt == null ? merchantUpdatedAt : reviewsUpdatedAt);
        section.setSources(Collections.singletonList(SOURCE));
        return section;
    }

    private Number number(Map<String, Object> values, String key) {
        if (values == null) return null;
        Object value = values.get(key);
        if (value == null) value = values.get(key.toLowerCase());
        return value instanceof Number ? (Number) value : null;
    }

    private MerchantOpsVO.QuickAction action(String code, String label, String type, String value,
                                              boolean available, String reason) {
        MerchantOpsVO.QuickAction action = new MerchantOpsVO.QuickAction();
        action.setCode(code);
        action.setLabel(label);
        action.setType(type);
        action.setValue(value);
        action.setAvailable(available);
        if (!available) action.setUnavailableReason(reason);
        return action;
    }

    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}

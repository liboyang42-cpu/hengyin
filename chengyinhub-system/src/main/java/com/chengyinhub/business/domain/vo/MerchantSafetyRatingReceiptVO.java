package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.MerchantSafetyRating;

import java.util.Date;

public class MerchantSafetyRatingReceiptVO {
    private Long ratingId;
    private Long merchantId;
    private String overallLevel;
    private String ratingPeriod;
    private Date acceptedAt;

    public static MerchantSafetyRatingReceiptVO from(MerchantSafetyRating row) {
        MerchantSafetyRatingReceiptVO vo = new MerchantSafetyRatingReceiptVO();
        vo.ratingId = row.getId();
        vo.merchantId = row.getMerchantId();
        vo.overallLevel = row.getOverallLevel();
        vo.ratingPeriod = row.getRatingPeriod();
        vo.acceptedAt = row.getCreateTime();
        return vo;
    }

    public Long getRatingId() { return ratingId; }
    public Long getMerchantId() { return merchantId; }
    public String getOverallLevel() { return overallLevel; }
    public String getRatingPeriod() { return ratingPeriod; }
    public Date getAcceptedAt() { return acceptedAt; }
}

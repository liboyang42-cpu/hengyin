package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.VerificationReview;
import com.chengyinhub.business.mapper.VerificationReviewMapper;
import com.chengyinhub.common.constant.ProductType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** 核销率/频次异常仅进入人工复核，不做自动资金动作。 */
@Service
public class VerificationRiskService {
    @Autowired private VerificationReviewMapper reviewMapper;
    @Autowired private AlarmService alarmService;
    @Autowired private com.chengyinhub.business.service.support.CommittedBusinessNotice committedBusinessNotice;
    private final BigDecimal highRateThreshold;
    private final int oneMinuteThreshold;
    private final int minimumSample;

    public VerificationRiskService(
            @Value("${merchant.verification-risk.high-rate-threshold:0.90}") BigDecimal highRateThreshold,
            @Value("${merchant.verification-risk.one-minute-threshold:8}") int oneMinuteThreshold,
            @Value("${merchant.verification-risk.minimum-sample:5}") int minimumSample) {
        this.highRateThreshold = highRateThreshold;
        this.oneMinuteThreshold = oneMinuteThreshold;
        this.minimumSample = minimumSample;
    }

    /**
     * @param productType 主题 {@code cms_topic.product_type}(活动壳已由
     *                    {@link VerificationReviewMapper#selectVerifierStats} 经
     *                    {@code cms_activity.topic_id} 归一)。探店日(③ {@link ProductType#FREE_EXPLORE})
     *                    跳过高核销率判据、保留 burst;NULL(存量未回填)按非探店日处理 —— fail-closed。
     */
    public boolean evaluateAndQueue(Long verifierMemberId, Long topicId, int paidCount,
                                    int verifiedCount, int oneMinuteCount, Integer productType) {
        if (paidCount < minimumSample) return false;
        BigDecimal rate = new BigDecimal(verifiedCount).divide(new BigDecimal(paidCount), 4, RoundingMode.HALF_UP);
        // ③ 探店日的 rate ≈ A / N_service,而 G2 硬门要求它 ≥ 80% ⇒ 越成功越触发高核销率,
        // 且 idempotencyKey 含 LocalDate ⇒ 四店四天堆 16 条无人能处置的误报工单。
        // 只摘 high-rate 不摘 burst:高核销率对探店日是设计目标,一分钟连扫 N 次仍是刷单信号。
        boolean shopDay = Integer.valueOf(ProductType.FREE_EXPLORE).equals(productType);
        boolean highRate = !shopDay && rate.compareTo(highRateThreshold) >= 0;
        boolean burst = oneMinuteCount >= oneMinuteThreshold;
        if (!highRate && !burst) return false;
        VerificationReview review = new VerificationReview();
        review.setVerifierMemberId(verifierMemberId);
        review.setTopicId(topicId);
        review.setRiskType(highRate && burst ? "HIGH_RATE_AND_BURST" : (highRate ? "HIGH_RATE" : "BURST"));
        review.setVerificationRate(rate);
        review.setPaidCount(paidCount);
        review.setVerifiedCount(verifiedCount);
        review.setOneMinuteCount(oneMinuteCount);
        review.setStatus(0);
        review.setIdempotencyKey(verifierMemberId + ":" + topicId + ":" + review.getRiskType()
                + ":" + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")));
        if (reviewMapper.insertIgnore(review) != 1) return false;
        Long reviewId = review.getId();
        String riskType = review.getRiskType();
        committedBusinessNotice.executeAfterCommit("verification.risk", reviewId,
                () -> alarmService.deliver("核销异常待人工复核", "reviewId=" + reviewId + " risk=" + riskType)
                        == AlarmService.AlarmDelivery.SUCCESS);
        return true;
    }

    public boolean inspectAndQueue(Long verifierMemberId, Long registrationId) {
        Map<String, Object> stats = reviewMapper.selectVerifierStats(verifierMemberId, registrationId);
        if (stats == null || stats.get("topicId") == null) return false;
        return evaluateAndQueue(verifierMemberId, number(stats.get("topicId")).longValue(),
                number(stats.get("paidCount")).intValue(), number(stats.get("verifiedCount")).intValue(),
                number(stats.get("oneMinuteCount")).intValue(), productType(stats.get("productType")));
    }

    /** product_type 允许为 NULL(存量未回填/混票待人工定型);别当 0 用 —— 0 不是合法产品类型。 */
    private Integer productType(Object value) {
        return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
    }

    private Number number(Object value) {
        return value instanceof Number ? (Number) value : 0;
    }
}

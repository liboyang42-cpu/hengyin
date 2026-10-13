package com.chengyinhub.business.service.support;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FINAL 时点的补偿事实。F / Q / EXTRA / HEADCOUNT 是四个不同经济事实，
 * 分开带出来，绝不合并成一个总额 —— 应付台账要按 component 各自幂等。
 */
public final class FinalCompensationFacts {
    private final Long clubId;
    private final BigDecimal fixedCompensation;
    private final BigDecimal quality;
    private final BigDecimal extraHours;
    private final BigDecimal headcount;
    private final Map<String, BigDecimal> extraByCategory;
    private final boolean pending;
    private final String pendingReason;

    private FinalCompensationFacts(Long clubId, BigDecimal fixedCompensation, BigDecimal quality,
                                   BigDecimal extraHours, BigDecimal headcount,
                                   Map<String, BigDecimal> extraByCategory,
                                   boolean pending, String pendingReason) {
        this.clubId = clubId;
        this.fixedCompensation = fixedCompensation;
        this.quality = quality;
        this.extraHours = extraHours;
        this.headcount = headcount;
        this.extraByCategory = extraByCategory == null
                ? Collections.<String, BigDecimal>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, BigDecimal>(extraByCategory));
        this.pending = pending;
        this.pendingReason = pendingReason;
    }

    public static FinalCompensationFacts settled(Long clubId, BigDecimal fixedCompensation, BigDecimal quality,
                                                 BigDecimal extraHours,
                                                 BigDecimal headcount,
                                                 Map<String, BigDecimal> extraByCategory) {
        return new FinalCompensationFacts(clubId, fixedCompensation, quality, extraHours,
                headcount, extraByCategory,
                false, null);
    }

    public static FinalCompensationFacts pending(Long clubId, String reason) {
        return new FinalCompensationFacts(clubId, null, null, null, null, null, true, reason);
    }

    public Long getClubId() { return clubId; }
    public BigDecimal getFixedCompensation() { return fixedCompensation; }
    public BigDecimal getQuality() { return quality; }
    public BigDecimal getExtraHours() { return extraHours; }
    /** 按已赚票数计酬的承接费（2026-08-25 换轨）。未配置人头计酬的期次是 0，不是 null。 */
    public BigDecimal getHeadcount() { return headcount; }
    public Map<String, BigDecimal> getExtraByCategory() { return extraByCategory; }
    public boolean isPending() { return pending; }
    public String getPendingReason() { return pendingReason; }
}

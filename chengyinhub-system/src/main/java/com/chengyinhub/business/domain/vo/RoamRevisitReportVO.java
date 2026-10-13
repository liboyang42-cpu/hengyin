package com.chengyinhub.business.domain.vo;

import java.time.LocalDate;
import java.util.Date;

/** 漫游复访只读报表行；不可变，避免查询结果被后续流程改写。 */
public final class RoamRevisitReportVO {
    public enum Metric { SHOP_REVISIT, ROAM_REVISIT }

    private final LocalDate cohortDate;
    private final Metric metric;
    private final int windowDays;
    private final int eligibleMembers;
    private final int revisitedMembers;
    private final int notMatureMembers;
    private final Date dataCutoffAt;
    private final Date queryExecutedAt;
    private final String scope;
    private final String identityBasis;

    public RoamRevisitReportVO(LocalDate cohortDate, Metric metric, int windowDays,
                               int eligibleMembers, int revisitedMembers, int notMatureMembers,
                               Date dataCutoffAt, Date queryExecutedAt, String scope, String identityBasis) {
        this.cohortDate = cohortDate;
        this.metric = metric;
        this.windowDays = windowDays;
        this.eligibleMembers = eligibleMembers;
        this.revisitedMembers = revisitedMembers;
        this.notMatureMembers = notMatureMembers;
        this.dataCutoffAt = copy(dataCutoffAt);
        this.queryExecutedAt = copy(queryExecutedAt);
        this.scope = scope;
        this.identityBasis = identityBasis;
    }

    public LocalDate getCohortDate() { return cohortDate; }
    public Metric getMetric() { return metric; }
    public int getWindowDays() { return windowDays; }
    public int getEligibleMembers() { return eligibleMembers; }
    public int getRevisitedMembers() { return revisitedMembers; }
    public int getNotMatureMembers() { return notMatureMembers; }
    public Date getDataCutoffAt() { return copy(dataCutoffAt); }
    public Date getQueryExecutedAt() { return copy(queryExecutedAt); }
    public String getScope() { return scope; }
    public String getIdentityBasis() { return identityBasis; }

    private static Date copy(Date value) {
        return value == null ? null : new Date(value.getTime());
    }
}

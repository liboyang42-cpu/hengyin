package com.chengyinhub.business.citygame.analytics.fairness;

/** Stored read-back for one account and one query. */
public class CityFairnessReadRow {
    public Long accountId;
    public String queryId;
    public Long asOf;
    public String payload;
}

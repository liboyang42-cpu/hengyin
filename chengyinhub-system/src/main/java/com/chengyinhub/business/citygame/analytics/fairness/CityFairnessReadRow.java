package com.chengyinhub.business.citygame.analytics.fairness;

/** Stored read-back for one account, one query, and one board scope. */
public class CityFairnessReadRow {
    public Long accountId;
    public String queryId;
    public Long asOf;
    public String boardId;
    public String regionId;
    public String seasonId;
    public String rulesVersion;
    public String payload;
}

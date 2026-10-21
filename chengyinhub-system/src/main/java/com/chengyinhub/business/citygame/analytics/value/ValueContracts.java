package com.chengyinhub.business.citygame.analytics.value;

import java.time.Instant;
import java.util.Date;
import java.util.Set;

/** 价值统计的摄入与查询输入。口径由调用方显式给出，服务端不补默认漏斗。 */
public final class ValueContracts {
    private ValueContracts() {
    }

    public static final class Ingest {
        public String sourceId;
        public String kind;
        public String regionId;
        public String boardId;
        public String seasonId;
        public String rulesReleaseId;
        public Long memberId;
        public String visitId;
        public String serviceId;
        public String actionCode;
        public String goalId;
        public String reasonCode;
        public Long amount;
        public String unit;
        public String currency;
        public Integer rating;
        public Date occurredAt;
    }

    public static final class Definition {
        public Set<String> meaningfulActions;
        public Set<String> effectiveChoices;
        public Integer ratingMin;
        public Integer ratingMax;
        public Integer veteranMinPriorServiceSuccess;
        public String budgetCurrency;
        public String pointsUnit;
    }

    public static final class Query {
        public String requestId;
        public String regionId;
        public String boardId;
        public String seasonId;
        public String rulesReleaseId;
        public Instant from;
        public Instant to;
        public Instant priorFrom;
        public Instant priorTo;
        public int limit;
        public Definition definition;
    }

    public static final class Page {
        public String regionId;
        public String boardId;
        public String seasonId;
        public String rulesReleaseId;
        public Instant from;
        public Instant to;
        public int limit;
        public String cursor;
    }
}

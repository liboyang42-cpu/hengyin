package com.chengyinhub.business.city;

/** MyBatis-only storage records. Never accept these records from HTTP or client payloads. */
public final class CityPersistenceRows {
    private CityPersistenceRows() { }
    public static class BoardRow { public String boardId; public String payload; public long version; }
    public static class FactRow {
        public String boardId; public String factKey; public String teamId; public long roundSequence;
        public String serviceInstanceId; public String visitScope; public String status; public String payload;
    }
    public static class EffectRow {
        public Long id; public String boardId; public String effectKey; public String kind;
        public long sequence; public String payload;
    }
    public static class RequestRow {
        public String boardId; public long actorId; public String requestId; public String requestKey;
        public String payloadHash; public long effectId;
    }
    public static class BudgetRow {
        public String budgetId; public String boardId; public String rulesReleaseId; public String approvalId;
        public long version; public long ceiling; public long reserved; public long consumed; public long released;
    }
    public static class ReservationRow {
        public String reservationId; public String boardId; public String budgetId; public String effectKey;
        public String offerHash; public long gross; public String status;
    }
    public static class ScopeRow { public String scopeKey; public String kind; public String effectKey; }
    public static class PointRow { public Long id; public Long point; }
    public static class DetailRow {
        public Long id; public long memberId; public long before; public long after; public long amount;
        public int changeType; public long eventType; public long effectId; public String businessKey;
    }
}

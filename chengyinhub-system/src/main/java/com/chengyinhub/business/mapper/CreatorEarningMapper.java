package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * R12 creator earnings ledger. The ledger is append-only: this mapper has inserts and selects, no update or delete.
 * Every insert is {@code insert ignore} on a deterministic unique key, so a replayed run writes nothing twice.
 */
public interface CreatorEarningMapper {

    // ---- source facts not yet in the ledger (each row: actorId, creatorId, itemRef, itemTitle, occurTime, ...) ----
    List<Map<String, Object>> selectAdoptionCandidates(@Param("from") Date from, @Param("to") Date to, @Param("limit") int limit);

    List<Map<String, Object>> selectInstallCandidates(@Param("from") Date from, @Param("to") Date to, @Param("limit") int limit);

    List<Map<String, Object>> selectTemplatePlayCandidates(@Param("from") Date from, @Param("to") Date to, @Param("limit") int limit);

    List<Map<String, Object>> selectWorkshopPlayCandidates(@Param("from") Date from, @Param("to") Date to, @Param("limit") int limit);

    List<Map<String, Object>> selectSaleCandidates(@Param("from") Date from, @Param("to") Date to, @Param("limit") int limit);

    List<Map<String, Object>> selectReversalCandidates(@Param("from") Date from, @Param("to") Date to, @Param("limit") int limit);

    // ---- ledger writes ----
    int countEvent(@Param("eventKey") String eventKey);

    int countRewardedPlays(@Param("creatorId") long creatorId, @Param("day") Date day);

    int countRewardedPlayerItemPlays(@Param("creatorId") long creatorId, @Param("actorId") long actorId,
                                     @Param("itemKind") String itemKind, @Param("itemRef") String itemRef, @Param("day") Date day);

    Map<String, Object> selectSaleForOrder(@Param("eventKey") String saleEventKey);

    int insertLedger(Map<String, Object> row);

    /** Projects ledger rows of approved creators into creator_income_detail (insert ignore on idempotency_key). */
    int mirrorIncomeDetail(@Param("limit") int limit);

    // ---- creator's own reads ----
    Map<String, Object> selectSummary(@Param("creatorId") long creatorId);

    List<Map<String, Object>> selectMoneyIntents(@Param("creatorId") long creatorId);

    List<Map<String, Object>> selectItems(@Param("creatorId") long creatorId, @Param("limit") int limit);

    List<Map<String, Object>> selectLedgerPage(@Param("creatorId") long creatorId, @Param("before") Long before,
                                               @Param("limit") int limit);

    List<Map<String, Object>> selectIncomeFacts(@Param("creatorId") long creatorId, @Param("before") String before,
                                                @Param("limit") int limit);

    List<Map<String, Object>> selectIncomeRows(@Param("creatorId") long creatorId, @Param("orderKey") String orderKey);

    // ---- admin report ----
    List<Map<String, Object>> selectCreatorReport(@Param("creatorId") Long creatorId, @Param("from") Date from,
                                                  @Param("to") Date to);

    List<Map<String, Object>> selectAdminLedger(@Param("creatorId") Long creatorId, @Param("eventType") String eventType);
}

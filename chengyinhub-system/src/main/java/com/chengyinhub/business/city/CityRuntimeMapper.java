package com.chengyinhub.business.city;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.city.CityPersistenceRows.*;

/** Board lock is the serialization guard. No cache, upsert, delete, or historical key removal. */
public interface CityRuntimeMapper {
    int insertBoard(BoardRow row);
    BoardRow lockBoard(@Param("boardId") String boardId);
    int updateBoard(@Param("boardId") String boardId, @Param("before") long before,
                    @Param("after") long after, @Param("payload") String payload);
    List<FactRow> rounds(@Param("boardId") String boardId);
    List<FactRow> visits(@Param("boardId") String boardId);
    List<EffectRow> effects(@Param("boardId") String boardId);
    List<RequestRow> requests(@Param("boardId") String boardId);
    List<ScopeRow> scopes(@Param("boardId") String boardId);
    int insertRound(FactRow row);
    int updateRound(FactRow row);
    int insertVisit(FactRow row);
    int finishVisit(FactRow row);
    int insertEffect(EffectRow row);
    int insertRequest(RequestRow row);
    int insertScope(@Param("boardId") String boardId, @Param("kind") String kind,
                    @Param("scopeKey") String scopeKey, @Param("effectKey") String effectKey);
    int insertBudget(BudgetRow row);
    BudgetRow lockBudget(@Param("budgetId") String budgetId);
    int reserveBudget(@Param("budgetId") String budgetId, @Param("gross") long gross);
    int finishBudget(@Param("budgetId") String budgetId, @Param("gross") long gross,
                     @Param("consume") boolean consume);
    ReservationRow findReservation(@Param("boardId") String boardId, @Param("effectKey") String effectKey);
    ReservationRow lockReservation(@Param("boardId") String boardId, @Param("effectKey") String effectKey);
    int insertReservation(ReservationRow row);
    int finishReservation(@Param("reservationId") String reservationId, @Param("status") String status);
    int insertPointFact(@Param("effectId") long effectId, @Param("entry") CityPointSettlementPlan.MemberEntry entry,
                        @Param("detailId") Long detailId, @Param("mappingApproval") String mappingApproval);
    int insertOutbox(EffectRow row);
}

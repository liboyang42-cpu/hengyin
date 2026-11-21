package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** R7 points governance: daily caps per award source, monthly budgets per programme, liability dashboard reads. */
public interface PointsGovernanceMapper {

    /** Cap row of one award source. Keys: eventType, sourceName, programme, dailyCapPoints, status. Null when ungoverned. */
    Map<String, Object> selectCap(@Param("eventType") Long eventType);

    /** Budget row of one programme. Keys: programme, programmeName, monthlyBudgetPoints, status. */
    Map<String, Object> selectBudget(@Param("programme") String programme);

    /** Points this member earned from this source since {@code since} (income rows only). */
    long sumMemberSourceSince(@Param("memberId") Long memberId, @Param("eventType") Long eventType, @Param("since") Date since);

    /** Makes sure the usage row of (programme, period) exists. */
    int ensureUsage(@Param("programme") String programme, @Param("period") String period, @Param("now") Date now);

    /**
     * Adds {@code points} to the programme's monthly usage only while it stays within {@code budget}
     * (null budget = unlimited). Returns 1 when admitted, 0 when the budget would be exceeded.
     */
    int consumeBudget(@Param("programme") String programme, @Param("period") String period,
                      @Param("points") long points, @Param("budget") Long budget, @Param("now") Date now);

    List<Map<String, Object>> selectCaps();

    /** Budgets joined with the usage of {@code period}. Keys: programme, programmeName, monthlyBudgetPoints, status, issuedPoints. */
    List<Map<String, Object>> selectBudgetsWithUsage(@Param("period") String period);

    int updateCap(@Param("eventType") Long eventType, @Param("dailyCapPoints") Integer dailyCapPoints,
                  @Param("status") String status, @Param("updateBy") String updateBy, @Param("now") Date now);

    int updateBudget(@Param("programme") String programme, @Param("monthlyBudgetPoints") Long monthlyBudgetPoints,
                     @Param("status") String status, @Param("updateBy") String updateBy, @Param("now") Date now);

    /** Sum of points ledger rows with this change type (1 income, 2 expense) in [from, to). */
    long sumPointsDetail(@Param("changeType") Long changeType, @Param("from") Date from, @Param("to") Date to);

    /** Points currently held by all members (outstanding liability in points). */
    long sumOutstandingPoints();
}

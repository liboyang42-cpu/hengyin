package com.chengyinhub.business.domain.vo;

/** 单章核销的事务内回执；通关探索值只在 grantOnce 首次发放时大于 0。 */
public class ExploreChapterRedemptionResult
{
    private final Long redemptionId;
    private final int completionEarnedXp;
    private final boolean replayed;

    public ExploreChapterRedemptionResult(Long redemptionId, int completionEarnedXp)
    {
        this(redemptionId, completionEarnedXp, false);
    }

    public ExploreChapterRedemptionResult(Long redemptionId, int completionEarnedXp, boolean replayed)
    {
        this.redemptionId = redemptionId;
        this.completionEarnedXp = completionEarnedXp;
        this.replayed = replayed;
    }

    public Long getRedemptionId() { return redemptionId; }
    public int getCompletionEarnedXp() { return completionEarnedXp; }
    public boolean isReplayed() { return replayed; }
}

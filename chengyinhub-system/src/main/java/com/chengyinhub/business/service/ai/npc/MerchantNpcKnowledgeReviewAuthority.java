package com.chengyinhub.business.service.ai.npc;

/**
 * Required deployment adapter; deliberately no implementation/bean is supplied.
 * It must establish that the distinct review type is installed in the existing authenticated
 * audit controller/service, every decision/replay invokes this source's exact-version validation,
 * and the current reviewer has merchant-knowledge review authority. Profile approval is unrelated.
 * This is NOT a publisher authority. No adapter may grant runtime publication from a draft decision.
 */
public interface MerchantNpcKnowledgeReviewAuthority {
    void requireInstalledReviewRoute(long merchantId, long ownerMemberId);
    void requireCurrentReviewer(long systemUserId, long merchantId);
}

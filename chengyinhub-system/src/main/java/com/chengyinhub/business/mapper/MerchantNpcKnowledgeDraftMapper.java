package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantNpcKnowledgeDraft;
import org.apache.ibatis.annotations.Param;

/** All calls require the existing merchant-row owner lock in the enclosing transaction. */
public interface MerchantNpcKnowledgeDraftMapper {
    MerchantNpcKnowledgeDraft selectHead(@Param("merchantId") long merchantId);
    MerchantNpcKnowledgeDraft selectVersion(@Param("merchantId") long merchantId, @Param("ownerId") long ownerId,
                                            @Param("version") long version);
    MerchantNpcKnowledgeDraft selectRequest(@Param("merchantId") long merchantId, @Param("ownerId") long ownerId,
                                            @Param("requestId") String requestId);
    int insertRevision(MerchantNpcKnowledgeDraft row);
    int insertHead(MerchantNpcKnowledgeDraft row);
    int advanceHead(@Param("row") MerchantNpcKnowledgeDraft row, @Param("expectedVersion") long expectedVersion);
    int bindReview(@Param("row") MerchantNpcKnowledgeDraft row);
}

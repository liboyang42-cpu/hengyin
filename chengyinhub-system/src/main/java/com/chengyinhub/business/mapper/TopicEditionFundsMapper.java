package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.TopicEditionFunds;
import com.chengyinhub.business.domain.TopicEditionFundsRawFacts;
import com.chengyinhub.business.domain.TopicEditionFundsAttribution;
import com.chengyinhub.business.domain.TopicEditionCost;
import com.chengyinhub.business.domain.ClubEditionHours;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface TopicEditionFundsMapper {
    TopicEditionFundsRawFacts selectProvisionalRawFacts(@Param("topicId") Long topicId,
                                                        @Param("asOf") Date asOf,
                                                        @Param("productType") Integer productType,
                                                        @Param("purchaseKind") Integer purchaseKind);

    List<TopicEditionFundsAttribution> selectFinalAttributionFacts(@Param("topicId") Long topicId,
                                                                   @Param("productType") Integer productType,
                                                                   @Param("purchaseKind") Integer purchaseKind);

    int insertFinalAttribution(TopicEditionFundsAttribution attribution);

    List<TopicEditionFundsAttribution> selectAttributionsBySnapshotId(@Param("fundsSnapshotId") Long fundsSnapshotId);

    TopicEditionFunds selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    TopicEditionFunds selectCurrentForUpdate(@Param("topicId") Long topicId,
                                              @Param("snapshotKind") String snapshotKind);

    TopicEditionFunds selectCurrent(@Param("topicId") Long topicId,
                                    @Param("snapshotKind") String snapshotKind);

    int classifyUnknownExplorePurchases(@Param("topicId") Long topicId,
                                        @Param("productType") Integer productType,
                                        @Param("purchaseKind") Integer purchaseKind);

    int insertSnapshot(TopicEditionFunds snapshot);

    int supersedeCurrentProvisional(@Param("id") Long id,
                                    @Param("operator") String operator,
                                    @Param("reason") String reason);

    TopicEditionCost selectCurrentCost(@Param("topicId") Long topicId);
    TopicEditionCost selectCurrentCostForUpdate(@Param("topicId") Long topicId);
    int supersedeCurrentCost(@Param("id") Long id);
    int insertCost(TopicEditionCost cost);
    List<ClubEditionHours> selectHours(@Param("topicId") Long topicId);
}

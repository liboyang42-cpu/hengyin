package com.chengyinhub.business.mapper.rewardpledge;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Award;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.CaseRow;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Intent;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Pledge;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Policy;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Stock;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Version;

public interface RewardPledgeMapper {
    Policy findPolicy(@Param("policyCode") String policyCode, @Param("ruleVersion") String ruleVersion);

    int insertPledge(Pledge row);

    Pledge findPledge(@Param("pledgeId") String pledgeId);

    int updatePledgeState(@Param("pledgeId") String pledgeId, @Param("state") String state);

    int updatePledgePayload(Pledge row);

    int insertVersion(Version row);

    Version findVersion(@Param("versionId") String versionId);

    Version latestVersion(@Param("pledgeId") String pledgeId);

    int confirmVersion(@Param("versionId") String versionId, @Param("memberId") long memberId,
                       @Param("hash") String hash, @Param("at") long at);

    int freezeVersion(@Param("versionId") String versionId, @Param("memberId") long memberId,
                      @Param("hash") String hash, @Param("ruleVersion") String ruleVersion, @Param("at") long at);

    int withdrawVersion(@Param("versionId") String versionId);

    int insertStock(Stock row);

    Stock lockStock(@Param("versionId") String versionId);

    int reserveOne(@Param("versionId") String versionId, @Param("versionNo") long versionNo);

    int settleAward(@Param("versionId") String versionId, @Param("versionNo") long versionNo);

    int withdrawAvailable(@Param("versionId") String versionId, @Param("versionNo") long versionNo);

    int holdAvailable(@Param("versionId") String versionId, @Param("versionNo") long versionNo);

    int markRedeemed(@Param("versionId") String versionId);

    int markExpired(@Param("versionId") String versionId);

    int returnExpired(@Param("versionId") String versionId);

    int insertIntent(Intent row);

    Intent lockIntent(@Param("ownerId") long ownerId, @Param("requestId") String requestId);

    int updateIntent(@Param("ownerId") long ownerId, @Param("requestId") String requestId,
                     @Param("state") String state, @Param("awardId") String awardId, @Param("errorCode") String errorCode);

    Award findAwardByKey(@Param("ruleVersion") String ruleVersion, @Param("beneficiaryId") long beneficiaryId,
                         @Param("rewardScope") String rewardScope, @Param("factRef") String factRef);

    Award findAward(@Param("awardId") String awardId);

    int insertAward(Award row);

    int bindHistory(@Param("awardId") String awardId, @Param("historyId") long historyId);

    int markAwardReturned(@Param("awardId") String awardId);

    int insertReceipt(@Param("awardId") String awardId, @Param("historyId") long historyId,
                      @Param("termsHash") String termsHash, @Param("createdAt") long createdAt);

    int insertOutbox(@Param("awardId") String awardId);

    int markOutboxSent(@Param("awardId") String awardId);

    Integer outboxAttempt(@Param("awardId") String awardId);

    int insertCase(CaseRow row);

    CaseRow findCase(@Param("caseId") String caseId);

    Long completedThemeSession(@Param("memberId") long memberId, @Param("topicId") long topicId);

    List<Award> listAwards(@Param("ownerId") long ownerId);

    int countAwards(@Param("versionId") String versionId);
}

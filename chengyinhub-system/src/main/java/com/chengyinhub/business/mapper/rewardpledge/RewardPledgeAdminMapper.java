package com.chengyinhub.business.mapper.rewardpledge;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** R10 BE-22 平台控制台：奖励承诺政策行与平台审核队列。授权与状态判定在 RewardPledgeAdminService。 */
public interface RewardPledgeAdminMapper
{
    List<Map<String, Object>> listPolicies();

    int upsertPolicy(@Param("policyCode") String policyCode, @Param("ruleVersion") String ruleVersion,
                     @Param("decision") String decision, @Param("actorMemberId") Long actorMemberId);

    /** 版本 + 承诺头，按商家确认时间排队；state 为空时默认 CONFIRMED。 */
    List<Map<String, Object>> queue(@Param("state") String state, @Param("storeId") Long storeId);

    Map<String, Object> lockVersion(@Param("versionId") String versionId);

    String latestVersionId(@Param("pledgeId") String pledgeId);

    int freezeByPlatform(@Param("versionId") String versionId, @Param("hash") String hash,
                         @Param("ruleVersion") String ruleVersion, @Param("at") long at);

    int rejectVersion(@Param("versionId") String versionId);

    int insertNote(@Param("versionId") String versionId, @Param("pledgeId") String pledgeId,
                   @Param("decision") String decision, @Param("note") String note, @Param("sysUserId") Long sysUserId);

    List<Map<String, Object>> notes(@Param("pledgeId") String pledgeId);
}

package com.chengyinhub.business.mapper.growthloops;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** R11 growth-loops：召回状态与候选、邀请奖励账本。 */
public interface GrowthLoopsMapper
{
    // ------------------------------------------------------------ win-back

    /** 连续天数 ≥ minStreak、昨天活跃今天还没活跃、有启用的推送设备、可以再联系的玩家。 */
    List<Map<String, Object>> selectStreakAtRisk(@Param("yesterday") Date yesterdayStart, @Param("today") Date todayStart,
            @Param("minStreak") int minStreak, @Param("sentBefore") Date sentBefore, @Param("maxIgnored") int maxIgnored,
            @Param("evalBefore") Date evalBefore, @Param("limit") int limit);

    /** 最近活跃落在 [lapsedAfter, lapsedBefore) 的玩家（有启用的推送设备、可以再联系）。 */
    List<Map<String, Object>> selectLapsed(@Param("lapsedBefore") Date lapsedBefore, @Param("lapsedAfter") Date lapsedAfter,
            @Param("sentBefore") Date sentBefore, @Param("maxIgnored") int maxIgnored, @Param("evalBefore") Date evalBefore,
            @Param("limit") int limit);

    /** 发过召回、还在等结果或累计过没回应的玩家，附最近活跃时间。 */
    List<Map<String, Object>> selectPendingResolution(@Param("limit") int limit);

    int updateResolution(@Param("memberId") long memberId, @Param("awaiting") int awaiting, @Param("ignored") int ignored);

    int ensureState(@Param("memberId") long memberId);

    Map<String, Object> selectState(@Param("memberId") long memberId);

    int touchEvaluated(@Param("memberId") long memberId, @Param("now") Date now);

    /** 原子占用一次发送（并发与重复任务下只有一个成功）。 */
    int claimSend(@Param("memberId") long memberId, @Param("now") Date now, @Param("sentBefore") Date sentBefore,
            @Param("maxIgnored") int maxIgnored, @Param("trigger") String trigger, @Param("ref") String ref);

    /** 没有设备接受时撤回占用，恢复发送前的状态。 */
    int releaseSend(@Param("memberId") long memberId, @Param("now") Date now, @Param("prevSent") Date prevSent,
            @Param("prevAwaiting") int prevAwaiting);

    int insertLog(@Param("memberId") long memberId, @Param("trigger") String trigger, @Param("ref") String ref,
            @Param("outcome") String outcome, @Param("devices") int devices, @Param("now") Date now);

    int upsertOptOut(@Param("memberId") long memberId, @Param("optedOut") int optedOut, @Param("now") Date now);

    List<String> selectExploredPrefixes(@Param("memberId") long memberId, @Param("limit") int limit);

    List<Map<String, Object>> selectNewPois(@Param("since") Date since, @Param("limit") int limit);

    List<Map<String, Object>> selectUpcomingActivities(@Param("from") Date from, @Param("to") Date to, @Param("limit") int limit);

    List<Long> selectMemberClubIds(@Param("memberId") long memberId);

    // ------------------------------------------------------------ invite reward

    /** 被邀请人资料：邀请人（invite_user_id 文本列）、手机号、状态。 */
    Map<String, Object> selectInvitee(@Param("memberId") long memberId);

    Map<String, Object> selectInviteReward(@Param("inviteeId") long inviteeId);

    int countGrantedByPhoneHash(@Param("phoneHash") String phoneHash);

    int countGrantedByDeviceHashes(@Param("hashes") List<String> hashes);

    int countGrantedForInviterSince(@Param("inviterId") long inviterId, @Param("since") Date since);

    int countGrantedForInviter(@Param("inviterId") long inviterId);

    /** 被邀请人在 App 上登记过的推送设备 token（任意环境，含已停用）。 */
    List<String> selectInviteeDeviceTokens(@Param("memberId") long memberId);

    /** 本次漫游的位置记录：被拒或不计奖的条数、干净的条数。 */
    Map<String, Object> selectSessionTrust(@Param("memberId") long memberId, @Param("sessionId") long sessionId);

    /** 写一行结果（被邀请人唯一）；返回 1 = 本次写入，0 = 已有。 */
    int insertInviteReward(@Param("inviteeId") long inviteeId, @Param("inviterId") long inviterId,
            @Param("status") String status, @Param("reason") String reason, @Param("sessionId") Long sessionId,
            @Param("phoneHash") String phoneHash, @Param("now") Date now);

    int insertInviteDevice(@Param("deviceHash") String deviceHash, @Param("inviteeId") long inviteeId, @Param("now") Date now);

    List<Map<String, Object>> selectInviteRewardsForInviter(@Param("inviterId") long inviterId, @Param("limit") int limit);
}

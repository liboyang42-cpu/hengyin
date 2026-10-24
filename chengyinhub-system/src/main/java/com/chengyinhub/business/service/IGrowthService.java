package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.GrowthMission;
import com.chengyinhub.business.domain.MemberGrowth;
import com.chengyinhub.business.domain.dto.GrowthMissionCompleteDTO;
import com.chengyinhub.business.domain.dto.LeaderboardQueryDTO;
import com.chengyinhub.business.domain.vo.MemberGrowthCenterVO;
import com.chengyinhub.business.domain.vo.MemberLeaderboardVO;

import java.util.List;

/**
 * P3 玩家成长服务。
 */
public interface IGrowthService {
    MemberGrowthCenterVO getCenter(Long memberId);

    /** 成长中心排行榜:积分/成长 × 总/周 + 我的名次。 */
    MemberLeaderboardVO getLeaderboard(Long memberId, LeaderboardQueryDTO query);

    int completeMission(Long memberId, GrowthMissionCompleteDTO request);

    int applyEvent(Long memberId, String eventName, String bizType, Long bizId);

    /** 与成功核销同一事务保存当时的成长任务，不在核销事务内发奖。 */
    void enqueueStoreVerification(Long registrationId);

    /** 根据持久核销事件恢复奖励，不依赖再次扫码。 */
    int deliverStoreVerification(Long registrationId);

    /**
     * M7 变量额直发探索值(XP)。双轨红线:只写 member_growth.exp_value,
     * 绝不写 ums_member_points_detail(可抵现积分)。幂等由调用方保证(节点/通关一次性场景)。
     * @return 实际入账 exp(<=0 入参返回 0)
     */
    int addExp(Long memberId, int exp, String reason);

    /** 通关经验返回实际获得明细 ID，供撤销原笔发放使用。 */
    Long addCompletionExp(Long memberId, int exp, Long topicId);

    List<MemberGrowth> selectAdminGrowthList(MemberGrowth growth);

    List<GrowthMission> selectAdminMissionList(GrowthMission mission);

    int saveMission(GrowthMission mission);
}

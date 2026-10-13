package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.EventSignup;

/**
 * 官方活动报名 Mapper
 *
 * @author cy
 * @date 2026-07-08
 */
public interface EventSignupMapper
{
    /** uk_event_member 冲突时靠 insert ignore 幂等(见 XML)。返回受影响行数(0=已报名)。 */
    int insertIgnore(EventSignup eventSignup);

    EventSignup selectByEventAndMember(@Param("eventId") Long eventId, @Param("memberId") Long memberId);

    /** 报名总人数(集体指标 signup_count)。 */
    int countByEvent(@Param("eventId") Long eventId);

    /** 已完成人数(status>=3;集体指标 complete_count)。 */
    int countDoneByEvent(@Param("eventId") Long eventId);

    /** 完成任务总数(集体指标 light_count = 全体 progress 之和)。 */
    int sumProgressByEvent(@Param("eventId") Long eventId);

    /** 我报名的活动(倒序)。 */
    List<EventSignup> selectByMember(@Param("memberId") Long memberId);

    /** 某活动的全部报名(结算发奖遍历)。 */
    List<EventSignup> selectByEvent(@Param("eventId") Long eventId);

    int updateEventSignup(EventSignup eventSignup);

    /**
     * 原子领取结算奖励:仅当 reward_claimed 仍为 0/NULL 时置 1 并推进状态,返回受影响行数。
     * 返回 1=本次抢到(可发奖);0=已被领取(并发/重跑,应跳过)。结算并发安全与幂等的基石。
     */
    int claimReward(@Param("id") Long id, @Param("rewardedStatus") int rewardedStatus);
}

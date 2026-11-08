package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RegistrationSeatInvitation;
import com.chengyinhub.business.domain.SeatRecipientFact;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 内含席位邀请。锁序：activity/topic → cms_registration → invitation。
 * 状态迁移全部条件更新（0 行 = 并发已变），容量由唯一键 + 原单行锁双重保证。
 */
public interface RegistrationSeatInvitationMapper {

    int insert(RegistrationSeatInvitation row);

    RegistrationSeatInvitation selectById(@Param("id") Long id);

    RegistrationSeatInvitation selectByIdForUpdate(@Param("id") Long id);

    RegistrationSeatInvitation selectByTokenHash(@Param("tokenHash") String tokenHash);

    RegistrationSeatInvitation selectByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    List<RegistrationSeatInvitation> selectByRegistration(@Param("registrationId") Long registrationId);

    /** 只取仍在占用内含席位的行（0 待领取 / 1 已领取），用于容量与座位选择。 */
    List<RegistrationSeatInvitation> selectActiveByRegistrationForUpdate(@Param("registrationId") Long registrationId);

    int claim(@Param("id") Long id, @Param("inviteeMemberId") Long inviteeMemberId, @Param("now") Date now);

    int transition(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                   @Param("toStatus") int toStatus, @Param("now") Date now);

    /** 服务端过期：只释放未领取的待领取行，不碰已领取。 */
    int expirePending(@Param("registrationId") Long registrationId, @Param("now") Date now);

    Integer maxGeneration(@Param("registrationId") Long registrationId, @Param("seatNo") Integer seatNo);

    int countActive(@Param("registrationId") Long registrationId);

    /** 同一原单同一受让人最多一个有效已领取席位；领取事务持原单行锁后调用。 */
    int countClaimedByRecipient(@Param("registrationId") Long registrationId,
                                @Param("inviteeMemberId") Long inviteeMemberId);

    /** 持原单锁查询原始使用事实，包含终态；团队/共享无法精确归席时保守拒绝释放。 */
    Boolean hasOriginalUseFacts(@Param("memberId") Long memberId, @Param("topicId") Long topicId,
                                @Param("activityId") Long activityId);

    /** 受让人可玩事实：仅 CLAIMED，且原报名单仍是成功报名 + 已支付（退款/撤销即消失）。 */
    List<SeatRecipientFact> selectClaimedFactsByRecipient(@Param("memberId") Long memberId,
                                                          @Param("ownerType") long ownerType,
                                                          @Param("ownerId") Long ownerId);
}

package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ActivitySettlementHold;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/** 客诉期提现闸:活动结算时有未结客诉的报名单先暂扣,客诉关闭后补结。 */
public interface ActivitySettlementHoldMapper {

    /** 把该活动下「已付未退且有未结客诉」的报名单登记为暂扣;重复调用幂等(uk registration_id)。 */
    int holdOpenComplaintRegistrations(@Param("activityId") Long activityId);

    /** 该活动仍在暂扣中、且报名仍是已付未退的实付合计。 */
    BigDecimal selectHeldSalesByActivityId(@Param("activityId") Long activityId);

    /** 客诉已全部关闭、可以补结或作废的暂扣行。 */
    List<ActivitySettlementHold> selectReleasableHolds();

    /** CAS:仅 status=0 的行能被翻到 toStatus;并发下只有一方 rows=1。 */
    int claimHold(@Param("id") Long id, @Param("toStatus") int toStatus);

    /** 该报名单是否仍在暂扣中(退款冲销判「商家是否已拿到这单的钱」用)。 */
    int countHeldByRegistrationId(@Param("registrationId") Long registrationId);

    /** 补结锁行后复查:该报名单此刻是否又有未结客诉。 */
    int countOpenComplaintsByRegistrationId(@Param("registrationId") Long registrationId);

    /** 余额三段:本人名下还没结算的活动(按是否涉诉拆开实付)。 */
    List<com.chengyinhub.business.domain.dto.MemberActivityFundsRow> selectUnsettledActivityFundsByMember(
            @Param("memberId") Long memberId);

    /** 余额三段:本人名下已结算活动里仍暂扣的报名单(客诉未结 = 涉诉;已结待下轮补结 = 普通)。 */
    List<com.chengyinhub.business.domain.dto.MemberActivityFundsRow> selectHeldActivityFundsByMember(
            @Param("memberId") Long memberId);
}

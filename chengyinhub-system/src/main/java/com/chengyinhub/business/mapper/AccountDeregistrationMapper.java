package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AccountDeregistration;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface AccountDeregistrationMapper {
    AccountDeregistration selectLatestByMemberId(@Param("memberId") Long memberId);

    AccountDeregistration selectByIdForUpdate(@Param("id") Long id);

    int insert(AccountDeregistration deregistration);

    int cancelPending(@Param("memberId") Long memberId, @Param("cancelTime") Date cancelTime);

    int markMemberPending(@Param("memberId") Long memberId,
                          @Param("applyTime") Date applyTime,
                          @Param("executeAfter") Date executeAfter);

    int markMemberNormal(@Param("memberId") Long memberId);

    List<AccountDeregistration> selectDue(@Param("now") Date now);

    int countUnsettledRefunds(@Param("memberId") Long memberId);

    int anonymizeMember(@Param("memberId") Long memberId);

    int anonymizeRegistrations(@Param("memberId") Long memberId);

    int anonymizeOrders(@Param("memberId") Long memberId);

    /** 清除提现申请(通道B)的姓名/银行卡/手机号明文+密文+掩码 PII;保留金额/流水做对账 */
    int anonymizeMemberWithdrawal(@Param("memberId") Long memberId);

    /** 清除快速提现(通道A)的 openid */
    int anonymizeCashWithdraw(@Param("memberId") Long memberId);

    /** 清除商家 lead 目录里的玩家 PII(电话/名称/地址) */
    int anonymizeMerchantLead(@Param("memberId") Long memberId);

    /** 清除发布者实名登记(姓名+身份证号密文+盲索引)。人不在了,这行没有留存的道理。 */
    int deleteMemberIdentity(@Param("memberId") Long memberId);

    int deleteMemberAddresses(@Param("memberId") Long memberId);

    /** 执行期阻断 → 置注销单失败(仅 PENDING 时) */
    int markFailed(@Param("id") Long id, @Param("reason") String reason, @Param("time") Date time);

    int complete(@Param("id") Long id, @Param("completeTime") Date completeTime);
}

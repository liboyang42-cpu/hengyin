package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MinorGuardianAuthorization;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** DOC2 F7 监护授权。会员侧写操作都带监护人或被监护人本人的 member_id。 */
public interface MinorGuardianAuthorizationMapper {
    int insert(MinorGuardianAuthorization authorization);

    MinorGuardianAuthorization selectByRequest(@Param("guardianMemberId") Long guardianMemberId,
                                               @Param("requestId") String requestId);

    MinorGuardianAuthorization selectByInviteHashForUpdate(@Param("inviteTokenHash") String inviteTokenHash);

    /** 监护人重放 PENDING 授权时换发邀请码（旧码随即作废）。 */
    int rotateInvite(@Param("id") Long id,
                     @Param("guardianMemberId") Long guardianMemberId,
                     @Param("inviteTokenHash") String inviteTokenHash,
                     @Param("inviteExpiresAt") Date inviteExpiresAt,
                     @Param("now") Date now);

    /** 被监护人确认：只有 PENDING + 邀请码摘要匹配才能 CAS 成 ACTIVE，同时清空邀请码。 */
    int activate(@Param("id") Long id,
                 @Param("inviteTokenHash") String inviteTokenHash,
                 @Param("wardMemberId") Long wardMemberId,
                 @Param("validFrom") Date validFrom,
                 @Param("validUntil") Date validUntil,
                 @Param("now") Date now);

    /** 同一 (被监护人, 监护人, 用途) 只保留最新一条 ACTIVE。 */
    int supersedeActive(@Param("wardMemberId") Long wardMemberId,
                        @Param("guardianMemberId") Long guardianMemberId,
                        @Param("purpose") String purpose,
                        @Param("exceptId") Long exceptId,
                        @Param("now") Date now);

    /** 监护人或被监护人任一方撤回；WHERE 带本人身份，越权命中 0 行。 */
    int revoke(@Param("id") Long id, @Param("memberId") Long memberId, @Param("now") Date now);

    MinorGuardianAuthorization selectById(@Param("id") Long id);

    List<MinorGuardianAuthorization> selectByMember(@Param("memberId") Long memberId);

    /** 被监护人在 now 时刻、当前政策版本下是否有某用途的有效授权。 */
    int countActive(@Param("wardMemberId") Long wardMemberId,
                    @Param("purpose") String purpose,
                    @Param("policyVersion") String policyVersion,
                    @Param("now") Date now);

    /** guardian 在 now 时刻、当前政策版本下是否对 ward 持有任一用途的有效授权（即监护关系成立）。 */
    int countActiveBetween(@Param("guardianMemberId") Long guardianMemberId,
                           @Param("wardMemberId") Long wardMemberId,
                           @Param("policyVersion") String policyVersion,
                           @Param("now") Date now);
}

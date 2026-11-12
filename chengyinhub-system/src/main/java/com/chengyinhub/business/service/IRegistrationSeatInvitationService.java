package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.SeatInvitationActionRequest;
import com.chengyinhub.business.domain.dto.SeatInvitationClaimRequest;
import com.chengyinhub.business.domain.dto.SeatInvitationCreateRequest;
import com.chengyinhub.business.domain.vo.SeatInvitationOwnerViewVO;
import com.chengyinhub.business.domain.vo.SeatInvitationVO;

/**
 * 已付多人票「内含席位」邀请生命周期（P5）。
 *
 * <p>原 {@code cms_registration} 是唯一已付/财务权威；本服务只增加邀请事实：
 * 购票人把第 2..N 席邀给他人、受让人登录后领取、待领取会服务端过期、
 * 误邀可在游玩前撤销或释放。不建新购买、不改买家 member_id/金额/退款归属、
 * 不写商户核销席位 {@code cms_registration_seat}。
 */
public interface IRegistrationSeatInvitationService {

    /** 购票人签发邀请；返回 invitation 视图 + 本次明文 token（幂等重放可重算同一枚）。 */
    SeatInvitationVO invite(long callerMemberId, SeatInvitationCreateRequest request);

    /** 购票人列出原单全部邀请历史 + 席位守恒计数。 */
    SeatInvitationOwnerViewVO ownerView(long callerMemberId, Long registrationId);

    /** 购票人或已领取的受让人按 invitationId 恢复结果。 */
    SeatInvitationVO status(long callerMemberId, Long invitationId);

    /** 受让人登录后领取；token 只是身份证明，recipient 永远取自登录态。 */
    SeatInvitationVO claim(long callerMemberId, SeatInvitationClaimRequest request);

    /** 购票人撤销待领取邀请（误邀）。已领取须走 {@link #release}。 */
    SeatInvitationVO cancel(long callerMemberId, SeatInvitationActionRequest request);

    /** 购票人释放已领取席位；受让人已在玩/已开局时拒绝，避免撤销活跃玩家。 */
    SeatInvitationVO release(long callerMemberId, SeatInvitationActionRequest request);
}

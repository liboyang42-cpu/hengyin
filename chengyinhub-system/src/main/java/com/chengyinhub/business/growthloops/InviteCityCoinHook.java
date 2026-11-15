package com.chengyinhub.business.growthloops;

/**
 * ===== CITY-COIN HOOK (points-governance package) =====
 *
 * <p>邀请奖励现在只发徽章（{@code INVITE_1/3/10}）。城市币（不可兑现）由 points-governance 包在并行开发；
 * 它落地后实现本接口并注册为 Spring bean，{@link InviteRewardService} 会在徽章发出后、同一个事务里调用。
 * 没有实现时什么也不发。</p>
 *
 * <p>约定：</p>
 * <ul>
 * <li>只在 {@code growth_invite_reward} 写入 GRANTED 行成功之后调用，每位被邀请人最多一次；
 *     实现方仍应以 {@code inviteeId} 做幂等键（例如流水 biz_key = "invite:" + inviteeId）。</li>
 * <li>金额、上限由实现方的配置决定；本包不定义任何数额。</li>
 * <li>抛异常会回滚这次邀请奖励（徽章与账本行一起），下次可信漫游再试。</li>
 * </ul>
 */
public interface InviteCityCoinHook
{
    void grantInviteReward(long inviterId, long inviteeId);
}

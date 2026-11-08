package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.SeatRecipientFact;

import java.util.List;

/**
 * 付费多人票「内含席位」受让人的只读可玩事实。
 *
 * <p>与礼品 {@link IMarketingGiftPlayEligibility} 分开：礼品是自玩域的可玩权转移，
 * 本事实只说明「这名会员已领取原已付行的第 2..N 席」。两者都只在
 * {@code CurrentPlayRegistration} 里与买家原行合并，不替换任一原真源。
 *
 * <p>实现只读 {@code cms_registration_seat_invitation} + 原 {@code cms_registration}
 * 的已付形状；不写表、不扫全表。普通投影不加锁，准入确认在调用方持原单锁的事务中锁读邀请。缺失时生产启动失败，不会静默退化成可玩。
 */
public interface IRegistrationSeatRecipientEligibility {

    /**
     * 与 actor 相关、仍可玩的已领取邀请事实，按 owner 域一次查询。
     *
     * @param memberId  当前 actor（领取人）
     * @param ownerType 1=主题 2=活动
     * @param ownerId   主题 id 或活动 id
     */
    List<SeatRecipientFact> playFacts(Long memberId, long ownerType, Long ownerId);

    /** 调用方先持原单行锁；用当前锁读再次确认邀请，避免 RR 旧快照放行已释放席位。 */
    boolean claimedUnderRegistrationLock(SeatRecipientFact fact, Long memberId, CmsRegistration registration);
}

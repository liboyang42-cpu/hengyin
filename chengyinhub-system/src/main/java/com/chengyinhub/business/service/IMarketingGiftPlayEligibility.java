package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.GiftPlayFact;

import java.util.List;

/**
 * 礼品对可玩资格的影响（只读，一次按 owner 域的查询）。
 *
 * <p>只在自玩域（{@code owner_type=1}，owner 为主题）有意义：礼品 SKU 只能挂在自玩通行证上。
 * 活动/场次（{@code owner_type=2}）返回空列表，走原有报名资格，不受礼品影响。
 *
 * <p>不写表、不加锁；领取事务自己持锁。缺失时调用方必须按"无礼品事实"处理旧行为 ——
 * 生产由 Spring 注入，缺失即启动失败，不会悄悄退化成可玩。
 */
public interface IMarketingGiftPlayEligibility {

    /**
     * 与该 actor 相关的礼品事实（status ∈ 待领取/已领取），按 owner 域收窄。
     *
     * <p>只返回「行属本人」或「已领取给本人」的来源：
     * <ul>
     *   <li>本人买、待领取 ⇒ 返回（<b>must</b>，用于遮断买家）；</li>
     *   <li>本人买、已领取给他人 ⇒ 返回（遮断买家）；</li>
     *   <li>他人买、已领取给本人 ⇒ 返回（放行领取人）；</li>
     *   <li>与本 actor 无关的第三人礼品 ⇒ <b>不返回</b>（不拿全主题所有人的准入事实）。</li>
     * </ul>
     *
     * @param memberId  当前 actor（必须非空；调用方都持有）
     * @param ownerType 1=主题 2=活动（2 恒返回空，走原报名资格）
     * @param ownerId   主题 id 或活动 id
     */
    List<GiftPlayFact> playFacts(Long memberId, long ownerType, Long ownerId);
}

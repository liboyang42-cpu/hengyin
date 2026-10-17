package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.Redemption;

/**
 * 核销流水 Mapper 接口(施工图 §3.2 · 不可变事件 + 反向撤销)
 */
public interface RedemptionMapper {

    /**
     * 插一条核销事件。
     * ⚠️ 列清单**绝不含 active_key**:它是库里的 GENERATED ALWAYS AS ... VIRTUAL 生成列,
     * 写它会报 3105。它的值由 MySQL 从 status/registration_id/chapter_id 自己算。
     * 撞 uk_redemption_active 报 1062 = 这票这章已有生效核销(跨商家重复核销在此被库拦下)。
     */
    public int insertRedemption(Redemption r);

    public Redemption selectById(@Param("id") Long id);

    /** 资金域核销记录仅按当前商家归属读取，scan 不在此列表中补行。 */
    public List<Redemption> selectByMerchant(@Param("merchantId") Long merchantId);

    /** 详情同样必须在 SQL 层带商家归属，不能从分页列表中再筛。 */
    public Redemption selectByMerchantAndId(@Param("merchantId") Long merchantId, @Param("id") Long id);

    /**
     * 请求级幂等:按 idempotency_key 找**生效**核销。命中 = 这个请求之前成功过。
     *
     * <p>走生成列 {@code active_idem_key}(= status='ACTIVE' 时才等于 idempotency_key,
     * 否则 NULL),因此:
     * <ul>
     *   <li>命中 uk_redemption_idem_active 唯一索引,不必新建索引;</li>
     *   <li><b>撤销后自动失忆</b> —— REVERSED 的行 active_idem_key 变 NULL,查不到
     *       ⇒ 同一商家改正后重扫能正常走完整流程,而不是被自己的旧流水幂等掉。
     *       这正是 DDL 里那段「裸 UNIQUE(idempotency_key) 会让撤销后重扫恒 1062」要的效果。</li>
     * </ul>
     *
     * @param key 见 {@code PerkLedgerServiceImpl.redeemKey}:redeem:{reg}:{chapter}:{merchant}
     * @return 生效核销;null = 没有(首次请求,或之前那次已被撤销)
     */
    public Redemption selectActiveByIdempotencyKey(@Param("key") String key);

    /** 当前锁读，与报名锁共同序列化丢回包重试、撤销和退款。 */
    Redemption selectActiveByIdempotencyKeyForUpdate(@Param("key") String key);


    /** 某票的生效核销(status='ACTIVE');已撤销的不算数,不返 */
    public List<Redemption> selectActiveByRegistration(@Param("registrationId") Long registrationId);
    List<Redemption> selectActiveByRegistrationForUpdate(@Param("registrationId") Long registrationId);

    /**
     * 某票的**全部**核销流水,含已撤销 —— 后台撤销入口的读回列表。
     *
     * <p>与 {@link #selectActiveByRegistration} 的区别就在「含不含 REVERSED」:撤销后要靠它
     * 把「何时撤销、为什么撤销」显示给平台侧,否则撤销完消息就从界面上消失了,等于看不见留痕。
     */
    public List<Redemption> selectByRegistration(@Param("registrationId") Long registrationId);

    /** 某票某章是否已有生效核销;0 = 可核销。⚠️ 仅供展示/预判,防重复的真防线是 uk_redemption_active */
    public int countActiveByRegistrationAndChapter(@Param("registrationId") Long registrationId,
                                                   @Param("chapterId") Long chapterId);

    /**
     * 撤销核销:条件置位,返回 0 = 该行已是 REVERSED,不该再回补配额。
     * 带 status='ACTIVE' 条件而非无条件 update:重复撤销拿 0 被挡,
     * 否则调用方会跟着调 releaseQuota 把配额重复回补出来。
     */
    public int reverse(@Param("id") Long id, @Param("reason") String reason);

    /**
     * ③ 通关判定:该票在**必选章**上有几个生效核销(可选章不计入通关)。
     * count(distinct chapter_id) 而非 count(*):理论上唯一键已保证每章至多一条 ACTIVE,
     * distinct 是第二道保险,任何一天唯一键被绕过也不会把通关数算多。
     */
    public int countActiveRequiredChaptersRedeemed(@Param("registrationId") Long registrationId,
                                                   @Param("topicId") Long topicId);

    /** 可配完成规则：同一事实查询同时返回角色数与需求组数。 */
    public java.util.Map<String, Object> selectActiveCompletionCounts(
            @Param("registrationId") Long registrationId, @Param("topicId") Long topicId);

    /** 可配完成规则的批量看板读；查询次数与玩家数无关。 */
    public List<java.util.Map<String, Object>> selectCompletionCountsByTopic(@Param("topicId") Long topicId);

    /**
     * P0-17 批量通关口径(俱乐部看板专用):一次算出该批章节上**每个 (member, registration)
     * 各核销了几个不同章节**。
     *
     * <p><b>为什么必须有它</b>:ApiStatsController.club 是唯一「一次算 M 玩家 × N 主题」的场景,
     * 手上既没有 registrationId 也没有固定 member ⇒ 循环调 isExploreCompleted
     * = 2 × M玩家 × N主题(20 主题 × 200 玩家 = 8000 次查询),会把看板拖垮。
     *
     * <p><b>为什么从 chapter 侧驱动而不是 where topic_id = ?</b>:redemption 的索引只有
     * uk_redemption_active / uk_redemption_idem_active / idx_redemption_reg(registration_id) /
     * idx_redemption_merchant(merchant_id, create_time) / idx_redemption_chapter(chapter_id, status)
     * —— **没有 topic_id 索引**,按 topic_id 查会全表扫。走 chapter_id IN (...) 恰好命中
     * idx_redemption_chapter,不需要新索引、不需要迁移。
     *
     * <p>⚠️ <b>chapterIds 为空时调用方必须提前 return,不能让空 list 进来</b> ——
     * MyBatis 的 foreach 会生成 {@code in ()},是 SQL 语法错。
     *
     * @param chapterIds 该主题的**必选章** id(来自 selectRequiredByTopic)
     * @return 每行 {memberId, registrationId, cnt};cnt = 该票在这批章节上的生效核销章节数
     */
    public List<java.util.Map<String, Object>> selectRedeemedCountsByChapters(
            @Param("chapterIds") List<Long> chapterIds);
}

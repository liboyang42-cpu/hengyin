package com.chengyinhub.business.service;

import java.util.List;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;

/**
 * 商家入驻章节(P0-12)—— 把申报层的「我有什么」变成执行层的「这一章真正在供给什么」。
 *
 * <p><b>为什么必须有它</b>:{@code merchant_chapter_offer} 在此之前**零 insert 入口**
 * ({@code insertMerchantChapterOffer} 只有声明与实现,无任何调用方)。而 ③ 的购票预占
 * ({@code PerkLedgerServiceImpl.reserveEntitlements}) 算容量时:
 * supply = Σ(quota_total − quota_used) 走 offer 表 → 表里没有行 → supply = 0
 * → capacity = 0 − held ≤ 0 → 对**任何有章节的 ③ 主题**都抛「该路线权益名额不足」。
 * ⇒ 没有入驻入口,③ 不是部分受限,是全站熔断。本服务就是那个缺失的入口。
 *
 * <p><b>与 coop_perk 的分工</b>:coop_perk 是申报层(商家在某个 invite 下报「我有一份 88 元的下午茶」),
 * 本服务落的 offer 是执行层(「这份下午茶挂在第 3 章,配额 50 人次」)。coop_perk 挂 invite 维度,
 * 答不出「这份权益是哪一章的」,故另立 offer 表补章节维。
 */
public interface IMerchantOfferService {

    /**
     * 商家入驻某章节,落一行生效供给。
     *
     * <p>入参只认 {@code chapterId / termsMode / perkId / perHeadFee / quotaTotal / validEnd};
     * {@code topicId} 由章节反查(不信前端传的),{@code merchantId} 由调用方从登录态传入(不信前端传的),
     * {@code status/quotaUsed} 由本方法定死。
     *
     * <p><b>merchantId 语义 = memberId</b>,不是 {@code mms_merchant.id} —— 库里「商家」一词被三个
     * 东西共用,语义超载曾致一次资损。调用方直接传 {@code getAppUserId()} 的返回。
     *
     * @return 新建 offer 的 id
     * @throws IllegalStateException 校验不过时抛,message 是给商家看的人话
     */
    Long enroll(MerchantChapterOffer offer, Long merchantId);

    /** 商家重新确认自己的圈层供给事实；夜间供给需至少每7天调用一次。 */
    void reconfirmCircleSupply(Long offerId, MerchantCircleSupplyProfile profile, Long merchantId);

    /** 商家确认当前供给事实没有变化；服务端复制当前快照并刷新确认时间。 */
    void reconfirmCurrentCircleSupply(Long offerId, Long merchantId);

    /** 商家主动暂停自己的圈层供给；不删除历史快照。 */
    void deactivateOwnCircleSupply(Long offerId, Long merchantId);

    /** 普通章节商家退出；必选章暂停新报名，保留已售订单和历史履约。返回本次是否暂停报名。 */
    boolean withdrawOwnChapterOffer(Long offerId, Long merchantId, String reason);

    /**
     * 后台审核台队列:offer 全量(含失效行 —— 停用历史是调证的一部分)连主题/章节/商家/权益展示字段。
     * 主题→章节的分组在 controller 拼(与 dispatchTree 同款)。
     *
     * @param topicId 可空:只看某主题
     * @param status  可空:1 只看生效 / 0 只看失效
     */
    List<MerchantChapterOffer> adminQueue(Long topicId, Integer status);

    /**
     * 后台定档定参(§4.2 商家承接审核台的灵魂):调整一行 offer 的条款档与参数,复用入驻同一套闸
     * (费率上限 / 权益门槛与归属 / 二清边界 / 锁价冻结)。
     *
     * <p>入参只认 {@code id / termsMode / perkId / perHeadFee / quotaTotal / validEnd},其余忽略;
     * 档必须等于**当前**章节口径 —— 章节是口径真源,要换档先改章节的 terms_mode(主题详情已有入口)。
     *
     * @param patch  携带新条款的 offer,{@code id} 定位行
     * @param operBy 操作人,落 update_by(「谁改的条款」要能查)
     * @throws IllegalStateException 校验不过时抛,message 指明是哪道闸
     */
    void adjustTerms(MerchantChapterOffer patch, String operBy);

    /**
     * 后台停用一行供给(劣质/违约商家的下架杆)。幂等:已失效再点不报错。
     * <p>⚠️ 停用不删行(核销流水引用 offer_id)。必选章的最后一家被停用会让在售票出履约缺口 ——
     * 这是运营自担的应急权衡,本方法不拦:拦了就没有「拉闸」这个杆了。
     */
    void deactivate(Long id);
}

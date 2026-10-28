package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ExploreBundleExitCase;
import com.chengyinhub.business.domain.ExploreBundleOrder;
import com.chengyinhub.business.domain.ExploreBundleReservation;
import com.chengyinhub.business.service.support.FreeExploreBundlePolicy;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * F3 自由探索(product_type=2)套票 Phase 1(DOC2 D-11)。
 *
 * <p>不是平行的套票系统:订单仍是 cms_registration(既有幂等键 + 指纹),权益/核销仍走
 * registration_entitlement / redemption / 既有扫码入口;本服务只在其旁边按「每持有人每单位」记账,
 * 并提供只读视图。所有写钩子由 {@link com.chengyinhub.business.service.support.FreeExploreBundleSwitches}
 * 门控,默认全部关闭 ⇒ 既有路径行为不变。不新增任何支付/退款资金路由。
 */
public interface IFreeExploreBundleService {

    // ---------------------------------------------------------------- 既有链路钩子(同事务)

    /** 建单钩子:sale 开关打开且为 ③ 主题时冻结五个版本并发放单位;否则返回 null、零写入。 */
    ExploreBundleOrder freezeOnOrderCreated(CmsRegistration registration, CmsTopic topic, CmsActivity activity);

    /** 核销钩子:有套票单时消耗该持有人在该章的一个单位;余额不足/过期/已约他店则抛业务拒绝让核销整体回滚。 */
    void onChapterRedeemed(Long registrationId, Long chapterId, Long merchantId, Long redemptionId);

    /** 撤销核销钩子:对应单位退回(票已退款则置 REFUNDED)。 */
    void onRedemptionReversed(Long redemptionId);

    /** 整单退款钩子:未履约单位置 REFUNDED,在约预约取消。 */
    void onRegistrationRefunded(Long registrationId);

    // ---------------------------------------------------------------- 只读

    Map<String, Object> catalog(Long topicId);

    Map<String, Object> orderView(Long memberId, Long registrationId);

    /** 下单结果 UNKNOWN 时按客户端幂等键(cms_registration.request_id)回读回执。 */
    Map<String, Object> orderReceiptByRequestId(Long memberId, String requestId);

    FreeExploreBundlePolicy.RefundQuote refundQuote(Long memberId, Long registrationId);

    ExploreBundleReservation reservationByIdempotencyKey(Long memberId, String idempotencyKey);

    List<ExploreBundleReservation> merchantReservations(Long merchantOwnerMemberId, Date from, Date to);

    // ---------------------------------------------------------------- 预约 / 商家退出(reservation 开关)

    ExploreBundleReservation reserve(Long memberId, Long registrationId, Long unitId, Long offerId,
                                     Date startAt, String idempotencyKey);

    ExploreBundleReservation cancelReservation(Long memberId, Long reservationId, boolean useWaiver);

    ExploreBundleReservation markNoShow(Long merchantOwnerMemberId, Long reservationId);

    ExploreBundleExitCase decideMerchantExit(Long memberId, Long unitId, String decision, Long targetOfferId,
                                             String idempotencyKey);
}

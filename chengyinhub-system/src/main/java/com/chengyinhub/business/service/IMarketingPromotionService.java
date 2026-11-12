package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MarketingOrderAdjustment;
import com.chengyinhub.business.domain.MarketingTier;
import com.chengyinhub.business.domain.dto.MarketingPromotionQuote;
import com.chengyinhub.business.domain.dto.MarketingPromotionRequest;
import com.chengyinhub.business.domain.vo.MarketingOfferCandidateView;
import com.chengyinhub.business.domain.vo.MarketingPromotionCandidateView;

import java.util.Date;
import java.util.List;

/**
 * 促销:适用供给投影 + 报价计算 + 成交快照/使用事实。
 *
 * <p><b>与既有报价的关系</b>:本服务不取代
 * {@code CmsRegistrationServiceImpl.computePrice} —— 它只回答"票面金额降到多少",
 * 积分抵扣与支付方式仍由既有 computePrice 处理。调用方(报价 / 建单)必须
 * <b>用同一份入参调同一个方法</b>,否则"报价=实付"立刻失效。
 *
 * <p><b>不允许的用法</b>:
 * <ul>
 *   <li>不得用返回值直接覆盖 {@code cms_registration.total_amount}(那列的语义是票面额)。</li>
 *   <li>不得在成交后按现规则重算优惠额;退款/对账读
 *       {@link #readOrderAdjustment} 的冻结快照。</li>
 *   <li>不得把 {@link #commitApplied} 放在建单事务之外 —— 使用事实必须与订单同生共死。</li>
 * </ul>
 */
public interface IMarketingPromotionService {

    /**
     * 计算促销报价。<b>纯读</b>:不写任何表、不扣库存、不占用资格。
     *
     * @param request 行 + 意图(仅 offerCode;金额与资格一律服务端算)
     * @param now     服务端时钟;窗口判定一律用它,不用客户端时间
     */
    MarketingPromotionQuote quote(MarketingPromotionRequest request, Date now);

    /** 本报价可选的组合包(含此刻不可用的原因)。礼品独立售价由 M3 礼品 SKU 提供,不走本接口。 */
    List<MarketingOfferCandidateView> listOffers(Long memberId, Integer ownerType, Long ownerId, Date now);

    /** 本报价相关的触发式优惠及其资格状态。 */
    List<MarketingPromotionCandidateView> listPromotions(Long memberId, Integer ownerType, Long ownerId, Date now);

    /** 本报价适用的人数阶梯价行(只读投影,价格即服务端报价依据)。 */
    List<MarketingTier> listTiers(Integer ownerType, Long ownerId, Date now);

    /**
     * 建单事务内落库:订单促销快照 + 使用事实。
     *
     * <p><b>重放先返回</b>:同一 (orderType, orderId) 已有快照时直接返回,不重复消耗
     * {@code max_per_member}、不报错 —— 快照本身就是"这单已经用过这份优惠"的事实。
     *
     * <p><b>组合包限次在建单期生效</b>:带 {@code offerId} 的报价会先锁住该 {@code marketing_offer} 行的
     * 现有行锁,锁后用<b>当前读</b> {@code countByOfferAndMemberForUpdate} 重算使用数再校验;超限抛
     * {@code MarketingPromotionException}({@code OFFER_MEMBER_LIMIT})并回滚整笔建单,
     * 由用户重新报价 —— 不按原价成交,也不静默放过。报价(read)路径不加锁。
     *
     * <p>幂等:同一 (promotionId, memberId, sourceFactId) 重复提交由唯一键拒绝,
     * 抛出 {@code DuplicateKeyException} 由调用方转 409 —— 不允许静默放过。
     */
    void commitApplied(MarketingPromotionQuote quote, Integer orderType, Long orderId, Date now);

    /**
     * 退款撤销资格:只把状态改成"已随退款撤销",不删行。
     *
     * <p>不删行的原因:删了就等于"退款后可以再领一次",那正是要防的重复领取。
     * 已撤销的行重复调用不改(幂等)。
     */
    void revokeForOrder(Integer orderType, Long orderId, Date now);

    /** 退款/对账读成交快照,不重算现规则。 */
    MarketingOrderAdjustment readOrderAdjustment(Integer orderType, Long orderId);
}

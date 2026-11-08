package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.dto.MarketingExtraSeatPlan;
import com.chengyinhub.business.domain.dto.MarketingExtraSeatQuote;
import com.chengyinhub.business.domain.dto.MarketingExtraSeatQuoteRequest;
import com.chengyinhub.business.domain.dto.MarketingProductOrderRequest;

import java.util.List;
import java.util.Map;

/**
 * 加座（{@code marketing_product.fulfillment_route=3}）业务层。
 *
 * <p><b>商业合同（root 裁决 A / C）</b>：加一位 = 一笔**独立付款**的独立业务订单；
 * N 位 = N 单。每一位都由原 grant 产出一行 {@code cms_registration}，同事务签发
 * {@code gift_entitlement}（单源单准入、领取一次），买家保留财务与退款归属。
 * 原单的已付金额、数量、支付事实一律不动；不建平行席位/权益账本。
 *
 * <p><b>不宣称入队</b>：自玩通行证（{@code owner_type=1}）在本仓没有队伍/席位名册
 * （{@code PlayTeam} 只服务活动场次、{@code cms_registration_seat} 要求
 * {@code seatNo <= orderNum}）。因此所有响应都回报
 * {@code partyBinding=NOT_APPLICABLE}，团队加座明确未支持。
 *
 * <p><b>本层没有容量概念</b>：自玩增购不存在"场次座位"这类资源，也读不到任何既有容量配置，
 * 所以不判"末位竞争"、不把活动组队人数当上限；{@code partyCapacity}/{@code seatsRemaining}
 * 恒为 null。
 *
 * <p><b>本接口不做资金动作</b>：积分扣减 / 微信 verified 事实 / grant / 礼品签发
 * 继续由 {@code IMarketingProductPurchaseService} 的单一成交路径执行；本层只负责
 * 加座特有的准入前置（原单、买家角色、范围、内容覆盖、重复加座）与读取。
 */
public interface IMarketingExtraSeatService {

    /** 加座报价：服务端重算价格并签名；同时回报 partyBinding 事实（容量键恒为 null）。 */
    MarketingExtraSeatQuote quote(long callerMemberId, MarketingExtraSeatQuoteRequest request);

    /**
     * 下单前在**原单行锁内**产出加座计划。下单路径必须在插入订单前调用：
     * 校验原单属于本人、已付、非礼品/非加座；原 registration 已付且属于本人（自玩域）；
     * 加座 SKU 覆盖的主题集合与原单成交冻结行完全一致；同一受赠人未在本原单重复加座。
     *
     * @throws com.chengyinhub.business.exception.MarketingProductException 稳定 reasonCode
     */
    MarketingExtraSeatPlan requirePurchasePlan(long callerMemberId, MarketingProduct product,
                                               MarketingProductOrderRequest request,
                                               List<MarketingProductItem> items);

    /** 我买过的加座（含待支付现金单与已退款），带礼品领取状态。 */
    List<Map<String, Object>> myExtraSeats(long callerMemberId, int limit);
}

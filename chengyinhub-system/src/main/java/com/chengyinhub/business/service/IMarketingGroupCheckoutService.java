package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutCreateRequest;
import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutQuoteRequest;
import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutQuoteResult;
import com.chengyinhub.business.domain.dto.MarketingGroupCheckoutResult;

/**
 * 团体/学校商品（marketing_product.fulfillment_route=2）到既有报名结算的应用适配层。
 *
 * <p>只解析「商品 → 真实活动票种 → 活动/主题/场次」这一段营销目录关系，然后
 * <b>原样调用</b> {@link IRegistrationCheckoutService#quote(Long, com.chengyinhub.business.domain.dto.RegistrationRequest)}
 * 与 {@link IRegistrationCheckoutService#createOrReplay(Long, com.chengyinhub.business.domain.dto.RegistrationRequest)}。
 * 金额、积分抵扣、库存扣减、名额竞争、幂等重放、支付事实全部由原链路决定；
 * 本层不建第二张订单/席位表，不写 {@code cms_registration}，不接收客户端金额。
 */
public interface IMarketingGroupCheckoutService {

    /**
     * 解析商品绑定并复用原报名报价。
     *
     * <p>客户端只提交 productId/quantity/participateDate/isUsePoint/offerCode；
     * 票价与应付由原服务端报价返回，目录价仅作展示、不参与成交。
     */
    MarketingGroupCheckoutQuoteResult quote(long callerMemberId, MarketingGroupCheckoutQuoteRequest request);

    /**
     * 解析同一绑定并复用原报名建单/幂等重放。
     *
     * <p>返回的是原 {@code cms_registration} 的 registrationId/registrationIds 与支付参数；
     * 报价签名由原建单闸重算比对，价格变化时拒绝而不是按客户端数值成交。
     */
    MarketingGroupCheckoutResult create(long callerMemberId, MarketingGroupCheckoutCreateRequest request);
}

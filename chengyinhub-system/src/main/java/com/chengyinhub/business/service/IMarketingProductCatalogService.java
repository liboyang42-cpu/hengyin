package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PassBundleView;
import com.chengyinhub.business.domain.dto.MarketingProductQuoteRequest;
import com.chengyinhub.business.domain.dto.MarketingProductQuoteResult;

import java.util.List;

/** 商品可售读面与报价（C06）。价格由服务端按 marketing_product 计算，客户端只拿签名。 */
public interface IMarketingProductCatalogService {

    List<PassBundleView> listSellable(String city, Integer kind, int limit);

    PassBundleView detail(Long productId);

    MarketingProductQuoteResult quote(long callerMemberId, MarketingProductQuoteRequest request);
}

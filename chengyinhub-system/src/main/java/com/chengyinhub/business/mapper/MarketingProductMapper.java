package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 商品配置与内容绑定。只读消费为主；insert 供运营配置与 MySqlIT 使用。 */
public interface MarketingProductMapper {

    MarketingProduct selectById(@Param("id") Long id);

    MarketingProduct selectByIdForUpdate(@Param("id") Long id);

    List<MarketingProduct> selectSellable(@Param("city") String city,
                                          @Param("kind") Integer kind,
                                          @Param("now") Date now,
                                          @Param("limit") int limit);

    List<MarketingProductItem> selectItems(@Param("productId") Long productId);

    List<MarketingProductItem> selectItemsByProductIds(@Param("productIds") List<Long> productIds);

    int insertProduct(MarketingProduct product);

    int insertItem(MarketingProductItem item);
}

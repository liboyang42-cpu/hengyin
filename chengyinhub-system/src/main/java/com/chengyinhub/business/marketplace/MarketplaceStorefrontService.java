package com.chengyinhub.business.marketplace;

import com.chengyinhub.business.mapper.MarketplaceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R1 storefront (MKT-03 / MON-07): the public merchant home shows published products, their SKUs and prices.
 * {@code purchasable} stays false: there is no player checkout for merchant SKUs, and real money is off in
 * production. Clients show prices and an honest "not sold in the app yet" state, never a dead buy button.
 */
@Service
public class MarketplaceStorefrontService {
    private final MarketplaceMapper mapper;

    @Autowired
    public MarketplaceStorefrontService(MarketplaceMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> storefront(Long merchantId) {
        List<Map<String, Object>> products = merchantId == null ? null : mapper.selectPublishedProducts(merchantId);
        List<Map<String, Object>> skus = merchantId == null ? null : mapper.selectPublishedSkus(merchantId);
        return assemble(products, skus);
    }

    static Map<String, Object> assemble(List<Map<String, Object>> products, List<Map<String, Object>> skus) {
        Map<Object, List<Map<String, Object>>> byProduct = new LinkedHashMap<>();
        if (skus != null) {
            for (Map<String, Object> sku : skus) {
                if (sku == null) continue;
                Object productId = key(sku.get("productId"));
                Map<String, Object> row = new LinkedHashMap<>(sku);
                row.remove("productId");
                byProduct.computeIfAbsent(productId, k -> new ArrayList<>()).add(row);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (products != null) {
            for (Map<String, Object> product : products) {
                if (product == null) continue;
                Map<String, Object> row = new LinkedHashMap<>(product);
                row.put("skus", byProduct.getOrDefault(key(product.get("productId")), new ArrayList<>()));
                out.add(row);
            }
        }
        Map<String, Object> storefront = new LinkedHashMap<>();
        storefront.put("products", out);
        storefront.put("purchasable", false);
        return storefront;
    }

    private static Object key(Object id) {
        return id instanceof Number n ? (Object) n.longValue() : id;
    }
}

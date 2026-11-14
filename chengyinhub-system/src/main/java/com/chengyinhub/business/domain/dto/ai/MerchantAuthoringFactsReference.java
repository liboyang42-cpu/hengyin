package com.chengyinhub.business.domain.dto.ai;

/** A caller selects identities, never supplies the authoritative product facts. */
public class MerchantAuthoringFactsReference {
    private Long productId;
    private Long skuId;
    private String factsHash;
    public Long getProductId() { return productId; }
    public void setProductId(Long value) { productId = value; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long value) { skuId = value; }
    public String getFactsHash() { return factsHash; }
    public void setFactsHash(String value) { factsHash = value; }
}

package com.chengyinhub.business.marketplace.paid.store;
/** Persistence row; never serialize as a public response. */
public final class PaidSnapshotRow {
    private Long orderLineId;
    public Long getOrderLineId(){return orderLineId;} public void setOrderLineId(Long value){orderLineId=value;}
    private Long orderId;
    public Long getOrderId(){return orderId;} public void setOrderId(Long value){orderId=value;}
    private Long buyerMemberId;
    public Long getBuyerMemberId(){return buyerMemberId;} public void setBuyerMemberId(Long value){buyerMemberId=value;}
    private Long productId;
    public Long getProductId(){return productId;} public void setProductId(Long value){productId=value;}
    private Long skuId;
    public Long getSkuId(){return skuId;} public void setSkuId(Long value){skuId=value;}
    private Long amountMinor;
    public Long getAmountMinor(){return amountMinor;} public void setAmountMinor(Long value){amountMinor=value;}
    private String currency;
    public String getCurrency(){return currency;} public void setCurrency(String value){currency=value;}
    private String providerAppId;
    public String getProviderAppId(){return providerAppId;} public void setProviderAppId(String value){providerAppId=value;}
    private String providerMerchantId;
    public String getProviderMerchantId(){return providerMerchantId;} public void setProviderMerchantId(String value){providerMerchantId=value;}
    private byte[] snapshotJson;
    public byte[] getSnapshotJson(){return snapshotJson;} public void setSnapshotJson(byte[] value){snapshotJson=value;}
    private String snapshotHash;
    public String getSnapshotHash(){return snapshotHash;} public void setSnapshotHash(String value){snapshotHash=value;}
    private String state;
    public String getState(){return state;} public void setState(String value){state=value;}
    private byte[] sourceContent;
    public byte[] getSourceContent(){return sourceContent;} public void setSourceContent(byte[] value){sourceContent=value;}
    private byte[] termsDocument;
    public byte[] getTermsDocument(){return termsDocument;} public void setTermsDocument(byte[] value){termsDocument=value;}
    private java.sql.Timestamp frozenAt;
    public java.sql.Timestamp getFrozenAt(){return frozenAt;} public void setFrozenAt(java.sql.Timestamp value){frozenAt=value;}
    @Override public String toString(){return "PaidSnapshotRow[redacted]";}
}

package com.chengyinhub.business.marketplace.paid.store;
/** Persistence row; never serialize as a public response. */
public final class PaidLicenseRow {
    private String licenseId;
    public String getLicenseId(){return licenseId;} public void setLicenseId(String value){licenseId=value;}
    private String providerTransactionId;
    public String getProviderTransactionId(){return providerTransactionId;} public void setProviderTransactionId(String value){providerTransactionId=value;}
    private String sourceEventId;
    public String getSourceEventId(){return sourceEventId;} public void setSourceEventId(String value){sourceEventId=value;}
    private String snapshotHash;
    public String getSnapshotHash(){return snapshotHash;} public void setSnapshotHash(String value){snapshotHash=value;}
    private String state;
    public String getState(){return state;} public void setState(String value){state=value;}
    private Long orderLineId;
    public Long getOrderLineId(){return orderLineId;} public void setOrderLineId(Long value){orderLineId=value;}
    private Long orderId;
    public Long getOrderId(){return orderId;} public void setOrderId(Long value){orderId=value;}
    private Long buyerMemberId;
    public Long getBuyerMemberId(){return buyerMemberId;} public void setBuyerMemberId(Long value){buyerMemberId=value;}
    private java.sql.Timestamp paidAt;
    public java.sql.Timestamp getPaidAt(){return paidAt;} public void setPaidAt(java.sql.Timestamp value){paidAt=value;}
    private java.sql.Timestamp issuedAt;
    public java.sql.Timestamp getIssuedAt(){return issuedAt;} public void setIssuedAt(java.sql.Timestamp value){issuedAt=value;}
    @Override public String toString(){return "PaidLicenseRow[redacted]";}
}

package com.chengyinhub.business.marketplace.paid;
/** NEW operation metadata only; PREPARED is never paid evidence or permission to create CMS. */
public final class PaidProfessionalOperationRow {
    private String operationKey;public String getOperationKey(){return operationKey;}public void setOperationKey(String v){operationKey=v;}
    private Long ownerMemberId;public Long getOwnerMemberId(){return ownerMemberId;}public void setOwnerMemberId(Long v){ownerMemberId=v;}
    private String requestId;public String getRequestId(){return requestId;}public void setRequestId(String v){requestId=v;}
    private String commandHash;public String getCommandHash(){return commandHash;}public void setCommandHash(String v){commandHash=v;}
    private byte[] commandData;public byte[] getCommandData(){return commandData;}public void setCommandData(byte[] v){commandData=v;}
    private Long commandBytes;public Long getCommandBytes(){return commandBytes;}public void setCommandBytes(Long v){commandBytes=v;}
    private String state;public String getState(){return state;}public void setState(String v){state=v;}
    private String rejectionCode;public String getRejectionCode(){return rejectionCode;}public void setRejectionCode(String v){rejectionCode=v;}
    private java.sql.Timestamp createdAt;public java.sql.Timestamp getCreatedAt(){return createdAt;}public void setCreatedAt(java.sql.Timestamp v){createdAt=v;}
    private java.sql.Timestamp updatedAt;public java.sql.Timestamp getUpdatedAt(){return updatedAt;}public void setUpdatedAt(java.sql.Timestamp v){updatedAt=v;}
}

package com.chengyinhub.business.domain;

/** 持久原照片命令；不保存签名URL、图像字节或模型原文。 */
public class PhotoCheckIntentRow {
    private Long id;
    private Long memberId;
    private Long sessionId;
    private String actionKey;
    private String requestFingerprint;
    private String contextJson;
    private String imageReference;
    private String reservation;
    private String originalDay;
    private String nonce;
    private String state;
    private String providerState;
    private String reasonCode;
    private String encodedInputSha256;
    private String modelName;
    private String promptVersion;
    private String encoderVersion;
    private Boolean resultPass;
    private Integer resultConfidence;
    private String resultReason;
    private Boolean resultDegraded;
    private Long attemptLogId;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { sessionId = value; }
    public String getActionKey() { return actionKey; }
    public void setActionKey(String value) { actionKey = value; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String value) { requestFingerprint = value; }
    public String getContextJson() { return contextJson; }
    public void setContextJson(String value) { contextJson = value; }
    public String getImageReference() { return imageReference; }
    public void setImageReference(String value) { imageReference = value; }
    public String getReservation() { return reservation; }
    public void setReservation(String value) { reservation = value; }
    public String getOriginalDay() { return originalDay; }
    public void setOriginalDay(String value) { originalDay = value; }
    public String getNonce() { return nonce; }
    public void setNonce(String value) { nonce = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public String getProviderState() { return providerState; }
    public void setProviderState(String value) { providerState = value; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String value) { reasonCode = value; }
    public String getEncodedInputSha256() { return encodedInputSha256; }
    public void setEncodedInputSha256(String value) { encodedInputSha256 = value; }
    public String getModelName() { return modelName; }
    public void setModelName(String value) { modelName = value; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String value) { promptVersion = value; }
    public String getEncoderVersion() { return encoderVersion; }
    public void setEncoderVersion(String value) { encoderVersion = value; }
    public Boolean getResultPass() { return resultPass; }
    public void setResultPass(Boolean value) { resultPass = value; }
    public Integer getResultConfidence() { return resultConfidence; }
    public void setResultConfidence(Integer value) { resultConfidence = value; }
    public String getResultReason() { return resultReason; }
    public void setResultReason(String value) { resultReason = value; }
    public Boolean getResultDegraded() { return resultDegraded; }
    public void setResultDegraded(Boolean value) { resultDegraded = value; }
    public Long getAttemptLogId() { return attemptLogId; }
    public void setAttemptLogId(Long value) { attemptLogId = value; }
}

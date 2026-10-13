package com.chengyinhub.business.domain.dto;

/** 单次活动任务到达验证请求。原始坐标只用于本次判定，不写入活动证据表。 */
public class OfficialEventV2ArrivalRequest {
    private String missionCode;
    private Double latitude;
    private Double longitude;
    private Double accuracyM;
    private Long sessionId;
    private String requestId;

    public String getMissionCode() { return missionCode; }
    public void setMissionCode(String missionCode) { this.missionCode = missionCode; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getAccuracyM() { return accuracyM; }
    public void setAccuracyM(Double accuracyM) { this.accuracyM = accuracyM; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}

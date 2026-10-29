package com.chengyinhub.business.domain;

/** Frozen official-result notice plus the irreversible boundary of its single WeChat attempt. */
public class CommittedOfficialNoticeWx {
    public static final String OFFICIAL_BROADCAST = "OFFICIAL_BROADCAST";
    public static final String BEFORE_START = "BEFORE_START";
    public static final String PENDING = "PENDING";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String ACCEPTED = "ACCEPTED";
    private String noticeType = OFFICIAL_BROADCAST;
    private String wxPayloadJson;
    private Long id;
    private String eventKey;
    private Long recipient;
    private String title;
    private String body;
    private Long imIntentId;
    private String requestHash;
    private String wxState;
    private String ownerToken;

    public String getNoticeType() { return noticeType; }
    public void setNoticeType(String value) { noticeType=value; }
    public String getWxPayloadJson() { return wxPayloadJson; }
    public void setWxPayloadJson(String value) { wxPayloadJson=value; }

    public Long getId() { return id; }
    public void setId(Long value) { id=value; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String value) { eventKey=value; }
    public Long getRecipient() { return recipient; }
    public void setRecipient(Long value) { recipient=value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title=value; }
    public String getBody() { return body; }
    public void setBody(String value) { body=value; }
    public Long getImIntentId() { return imIntentId; }
    public void setImIntentId(Long value) { imIntentId=value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash=value; }
    public String getWxState() { return wxState; }
    public void setWxState(String value) { wxState=value; }
    public String getOwnerToken() { return ownerToken; }
    public void setOwnerToken(String value) { ownerToken=value; }
}

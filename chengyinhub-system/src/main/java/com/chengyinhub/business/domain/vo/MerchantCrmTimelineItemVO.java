package com.chengyinhub.business.domain.vo;

import java.util.Date;

public class MerchantCrmTimelineItemVO {
    private String key;
    private String type;
    private String title;
    private String description;
    private Date occurredAt;
    private Long noteId;
    private Integer noteVersion;
    private Long correctsNoteId;

    public String getKey() { return key; }
    public void setKey(String value) { key = value; }
    public String getType() { return type; }
    public void setType(String value) { type = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public Date getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Date value) { occurredAt = value; }
    public Long getNoteId() { return noteId; }
    public void setNoteId(Long value) { noteId = value; }
    public Integer getNoteVersion() { return noteVersion; }
    public void setNoteVersion(Integer value) { noteVersion = value; }
    public Long getCorrectsNoteId() { return correctsNoteId; }
    public void setCorrectsNoteId(Long value) { correctsNoteId = value; }
}

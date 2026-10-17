package com.chengyinhub.business.domain;
/** Current-visible metadata for an authenticated member's authoritative historical completion. */
public class ExplorationAnniversary {
    private String id;
    private String topicId;
    private String title;
    private String completedAt;
    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getTopicId() { return topicId; }
    public void setTopicId(String value) { topicId = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String value) { completedAt = value; }
}

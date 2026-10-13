package com.chengyinhub.business.domain.community;

/** 帖文候选查询；分页、可见性和个人关系在同一条读模型中生效。 */
public class CommunityPostFeedQuery {
    private Long viewerId;
    private String mode;
    private Long cursor;
    private Long cursorScore;
    private Integer limit;
    private String cityCode;
    private Long communityId;
    private String topicCode;
    private String collectionCode;
    private String keyword;
    private Long authorId;

    public Long getViewerId() { return viewerId; }
    public void setViewerId(Long viewerId) { this.viewerId = viewerId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Long getCursor() { return cursor; }
    public void setCursor(Long cursor) { this.cursor = cursor; }
    public Long getCursorScore() { return cursorScore; }
    public void setCursorScore(Long cursorScore) { this.cursorScore = cursorScore; }
    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public Long getCommunityId() { return communityId; }
    public void setCommunityId(Long communityId) { this.communityId = communityId; }
    public String getTopicCode() { return topicCode; }
    public void setTopicCode(String topicCode) { this.topicCode = topicCode; }
    public String getCollectionCode() { return collectionCode; }
    public void setCollectionCode(String collectionCode) { this.collectionCode = collectionCode; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }
}

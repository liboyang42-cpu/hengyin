package com.chengyinhub.business.domain.dto;

/** 俱乐部动态编辑/置顶/历史请求；clubId 永远从帖子服务端回读。 */
public class ClubPostMutationRequest {
    private Long id;
    private String content;
    private String images;
    private Boolean pinned;
    private Integer version;
    private String requestId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getImages() { return images; }
    public void setImages(String images) { this.images = images; }
    public Boolean getPinned() { return pinned; }
    public void setPinned(Boolean pinned) { this.pinned = pinned; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}

package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/** 俱乐部动态每次人工编辑前的不可变快照。 */
public class ClubPostRevision extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long postId;
    private Long clubId;
    private Long editorMemberId;
    private Integer snapshotVersion;
    private String content;
    private String images;
    private Integer type;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPostId() { return postId; }
    public void setPostId(Long postId) { this.postId = postId; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getEditorMemberId() { return editorMemberId; }
    public void setEditorMemberId(Long editorMemberId) { this.editorMemberId = editorMemberId; }
    public Integer getSnapshotVersion() { return snapshotVersion; }
    public void setSnapshotVersion(Integer snapshotVersion) { this.snapshotVersion = snapshotVersion; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getImages() { return images; }
    public void setImages(String images) { this.images = images; }
    public Integer getType() { return type; }
    public void setType(Integer type) { this.type = type; }
}

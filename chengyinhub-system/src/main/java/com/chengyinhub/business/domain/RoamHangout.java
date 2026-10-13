package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 漫游·附近的局 roam_hangout:玩家自组的免费约玩群。
 * 成员真源 = im_conversation_member(conversation_id 对应群),本表不存成员。
 * status:0 进行中 · 1 已关 · 2 机审/举报隐藏。
 */
public class RoamHangout {
    public static final int STATUS_OPEN = 0;
    public static final int STATUS_CLOSED = 1;
    public static final int STATUS_HIDDEN = 2;

    private Long id;
    private Long memberId;
    private String title;
    private String description;
    private String coverUrl;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String addressName;
    private Date startAt;
    private Integer status;
    private Long conversationId;
    private Date createTime;
    private Date updateTime;
    private Integer delFlag;

    // --- 仅 selectNearby / selectById 联查填充,不落库 ---
    private Double distance;
    private Integer memberCount;
    private Integer isMember;
    private String ownerAvatar;
    private String ownerNickname;
    /** 局主身份:ums_member.role(merchant / club / 空=玩家)。地图上商家局用店招方框、个人局用圆头像。 */
    private String ownerRole;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public String getAddressName() { return addressName; }
    public void setAddressName(String addressName) { this.addressName = addressName; }
    public Date getStartAt() { return startAt; }
    public void setStartAt(Date startAt) { this.startAt = startAt; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public Double getDistance() { return distance; }
    public void setDistance(Double distance) { this.distance = distance; }
    public Integer getMemberCount() { return memberCount; }
    public void setMemberCount(Integer memberCount) { this.memberCount = memberCount; }
    public Integer getIsMember() { return isMember; }
    public void setIsMember(Integer isMember) { this.isMember = isMember; }
    public String getOwnerAvatar() { return ownerAvatar; }
    public void setOwnerAvatar(String ownerAvatar) { this.ownerAvatar = ownerAvatar; }
    public String getOwnerNickname() { return ownerNickname; }
    public void setOwnerNickname(String ownerNickname) { this.ownerNickname = ownerNickname; }
    public String getOwnerRole() { return ownerRole; }
    public void setOwnerRole(String ownerRole) { this.ownerRole = ownerRole; }
}

package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 玩家已获徽章
 *
 * @author cy
 * @date 2026-06-21
 */
public class PlayerBadge
{
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long memberId;
    private String badgeCode;
    private String badgeName;
    private String iconUrl;
    private Integer sourceType;
    private Long sourceId;
    private Date obtainTime;
    private Integer delFlag;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public String getBadgeCode() { return badgeCode; }
    public void setBadgeCode(String badgeCode) { this.badgeCode = badgeCode; }

    public String getBadgeName() { return badgeName; }
    public void setBadgeName(String badgeName) { this.badgeName = badgeName; }

    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }

    public Integer getSourceType() { return sourceType; }
    public void setSourceType(Integer sourceType) { this.sourceType = sourceType; }

    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }

    public Date getObtainTime() { return obtainTime; }
    public void setObtainTime(Date obtainTime) { this.obtainTime = obtainTime; }

    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}

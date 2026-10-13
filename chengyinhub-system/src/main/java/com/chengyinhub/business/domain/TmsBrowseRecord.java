package com.chengyinhub.business.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 主题/活动浏览记录对象 tms_browse_record
 * 
 * @author webxh
 * @date 2025-10-20
 */
public class TmsBrowseRecord extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    private Long id;

    /** 用户ID（关联用户表） */
    @Excel(name = "用户ID", readConverterExp = "关=联用户表")
    private Long memberId;

    /** 浏览类型：1-主题 2-活动 */
    @Excel(name = "浏览类型：1-主题 2-活动")
    private Long browseType;

    /** 目标ID（主题ID或活动ID，关联对应表） */
    @Excel(name = "目标ID", readConverterExp = "主=题ID或活动ID，关联对应表")
    private Long targetId;

    /** 目标名称（冗余，如主题/活动标题） */
    @Excel(name = "目标名称", readConverterExp = "冗=余，如主题/活动标题")
    private String targetTitle;

    /** 浏览时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "浏览时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date browseTime;

    /** 浏览时长（秒，可选，统计停留时间） */
    @Excel(name = "浏览时长", readConverterExp = "秒=，可选，统计停留时间")
    private Long browseDuration;

    /** 浏览IP地址 */
    @Excel(name = "浏览IP地址")
    private String ipAddress;

    /** 用户终端信息（浏览器/设备，可选） */
    @Excel(name = "用户终端信息", readConverterExp = "浏=览器/设备，可选")
    private String userAgent;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
    }

    public void setBrowseType(Long browseType) 
    {
        this.browseType = browseType;
    }

    public Long getBrowseType() 
    {
        return browseType;
    }

    public void setTargetId(Long targetId) 
    {
        this.targetId = targetId;
    }

    public Long getTargetId() 
    {
        return targetId;
    }

    public void setTargetTitle(String targetTitle) 
    {
        this.targetTitle = targetTitle;
    }

    public String getTargetTitle() 
    {
        return targetTitle;
    }

    public void setBrowseTime(Date browseTime) 
    {
        this.browseTime = browseTime;
    }

    public Date getBrowseTime() 
    {
        return browseTime;
    }

    public void setBrowseDuration(Long browseDuration) 
    {
        this.browseDuration = browseDuration;
    }

    public Long getBrowseDuration() 
    {
        return browseDuration;
    }

    public void setIpAddress(String ipAddress) 
    {
        this.ipAddress = ipAddress;
    }

    public String getIpAddress() 
    {
        return ipAddress;
    }

    public void setUserAgent(String userAgent) 
    {
        this.userAgent = userAgent;
    }

    public String getUserAgent() 
    {
        return userAgent;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("browseType", getBrowseType())
            .append("targetId", getTargetId())
            .append("targetTitle", getTargetTitle())
            .append("browseTime", getBrowseTime())
            .append("browseDuration", getBrowseDuration())
            .append("ipAddress", getIpAddress())
            .append("userAgent", getUserAgent())
            .append("createTime", getCreateTime())
            .append("createBy", getCreateBy())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}

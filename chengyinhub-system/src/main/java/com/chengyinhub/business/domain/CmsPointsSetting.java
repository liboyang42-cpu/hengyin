package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 积分规则与分润设置对象 cms_points_setting
 * 
 * @author ruoyi
 * @date 2026-01-31
 */
public class CmsPointsSetting extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** ID (1-8) */
    private Long id;

    /** 规则标题 */
    @Excel(name = "规则标题")
    private String title;

    @Excel(name = "规则标题")
    private String icon;


    /** 规则数值 */
    @Excel(name = "规则数值")
    private BigDecimal value;

    /** 规则描述 */
    @Excel(name = "规则描述")
    private String description;

    /** 类型：1-积分规则 2-分润比例 */
    @Excel(name = "类型：1-积分规则 2-分润比例")
    private Long type;

    /** 对应的事件类型（参考ums_member_points_detail.event_type） */
    @Excel(name = "对应的事件类型", readConverterExp = "参=考ums_member_points_detail.event_type")
    private Long eventType;

    /** 规则版本；每次修改递增，奖励流水冻结该版本和值。 */
    private Long ruleVersion;

    /** 状态：0-禁用 1-启用 */
    @Excel(name = "状态：0-禁用 1-启用")
    private Long status;

    /** 排序 */
    @Excel(name = "排序")
    private Long sortOrder;

    /** 删除标记 0 代表存在 2删除 */
    private Long delFlag;

    private Integer pointsNum;

    public Integer getPointsNum() {
        return pointsNum;
    }

    public void setPointsNum(Integer pointsNum) {
        this.pointsNum = pointsNum;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setTitle(String title) 
    {
        this.title = title;
    }

    public String getTitle() 
    {
        return title;
    }

    public void setValue(BigDecimal value) 
    {
        this.value = value;
    }

    public BigDecimal getValue()
    {
        return value;
    }

    public void setDescription(String description) 
    {
        this.description = description;
    }

    public String getDescription() 
    {
        return description;
    }

    public void setType(Long type) 
    {
        this.type = type;
    }

    public Long getType() 
    {
        return type;
    }

    public void setEventType(Long eventType) 
    {
        this.eventType = eventType;
    }

    public Long getEventType()
    {
        return eventType;
    }

    public Long getRuleVersion()
    {
        return ruleVersion;
    }

    public void setRuleVersion(Long ruleVersion)
    {
        this.ruleVersion = ruleVersion;
    }

    public void setStatus(Long status) 
    {
        this.status = status;
    }

    public Long getStatus() 
    {
        return status;
    }

    public void setSortOrder(Long sortOrder) 
    {
        this.sortOrder = sortOrder;
    }

    public Long getSortOrder() 
    {
        return sortOrder;
    }

    public void setDelFlag(Long delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Long getDelFlag() 
    {
        return delFlag;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("title", getTitle())
            .append("value", getValue())
            .append("description", getDescription())
            .append("type", getType())
            .append("eventType", getEventType())
            .append("ruleVersion", getRuleVersion())
            .append("status", getStatus())
            .append("sortOrder", getSortOrder())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}

package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 活动/主题合作者对象 cms_collaborators
 * 
 * @author webxh
 * @date 2025-09-02
 */
public class CmsCollaborators extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 类型1、主题  2、活动  */
    @Excel(name = "类型1、主题  2、活动 ")
    private Long dataType;

    /** 主题ID/活动ID */
    @Excel(name = "主题ID/活动ID")
    private Long dataId;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    /**
     * 类型1、主题  2、活动
     * @param dataType 类型1、主题  2、活动
     */
    public void setDataType(Long dataType) 
    {
        this.dataType = dataType;
    }

    public Long getDataType() 
    {
        return dataType;
    }

    public void setDataId(Long dataId) 
    {
        this.dataId = dataId;
    }

    public Long getDataId() 
    {
        return dataId;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("dataType", getDataType())
            .append("dataId", getDataId())
            .append("memberId", getMemberId())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}

package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 热门搜索词对象 sms_hot_search
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public class SmsHotSearch extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    private Long id;

    /** 搜索关键词 */
    @Excel(name = "搜索关键词")
    private String keyword;

    /** 搜索次数 */
    @Excel(name = "搜索次数")
    private Long searchCount;

    /** 状态（0-禁用，1-启用） */
    @Excel(name = "状态", readConverterExp = "0=-禁用，1-启用")
    private Integer status;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setKeyword(String keyword) 
    {
        this.keyword = keyword;
    }

    public String getKeyword() 
    {
        return keyword;
    }

    public void setSearchCount(Long searchCount) 
    {
        this.searchCount = searchCount;
    }

    public Long getSearchCount() 
    {
        return searchCount;
    }

    public void setStatus(Integer status) 
    {
        this.status = status;
    }

    public Integer getStatus() 
    {
        return status;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("keyword", getKeyword())
            .append("searchCount", getSearchCount())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}

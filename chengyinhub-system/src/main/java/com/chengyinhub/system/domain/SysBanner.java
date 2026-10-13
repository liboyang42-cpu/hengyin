package com.chengyinhub.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 轮播图对象 sys_banner
 * 
 * @author ruoyi
 * @date 2025-08-21
 */
public class SysBanner extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Integer id;

    /** 显示类型 1首页头部轮播  2其它 */
    @Excel(name = "显示类型 1首页头部轮播  2其它")
    private Integer showType;

    /** 点击类型   0无事件 1单页查看详情 2: 跳转H5 3、活动详情 4、主题详情 */
    @Excel(name = "点击类型   0无事件 1单页查看详情 2: 跳转H5 3、活动详情 4、主题详情")
    private Integer linkType;

    /** H5链接/活动ID/主题ID */
    @Excel(name = "H5链接/活动ID/主题ID")
    private String dataId;

    /** 图片地址 */
    @Excel(name = "图片地址")
    private String picUrl;

    /** 单页详情内容 */
    @Excel(name = "单页详情内容")
    private String contents;

    /** 排序 */
    @Excel(name = "排序")
    private Integer sortId;

    /** 状态 1显示 2隐藏 */
    @Excel(name = "状态 1显示 2隐藏")
    private Integer status;

    public void setId(Integer id) 
    {
        this.id = id;
    }

    public Integer getId() 
    {
        return id;
    }

    public void setShowType(Integer showType) 
    {
        this.showType = showType;
    }

    public Integer getShowType() 
    {
        return showType;
    }

    public void setLinkType(Integer linkType) 
    {
        this.linkType = linkType;
    }

    public Integer getLinkType() 
    {
        return linkType;
    }

    public void setDataId(String dataId) 
    {
        this.dataId = dataId;
    }

    public String getDataId() 
    {
        return dataId;
    }

    public void setPicUrl(String picUrl) 
    {
        this.picUrl = picUrl;
    }

    public String getPicUrl() 
    {
        return picUrl;
    }

    public void setContents(String contents) 
    {
        this.contents = contents;
    }

    public String getContents() 
    {
        return contents;
    }

    public void setSortId(Integer sortId) 
    {
        this.sortId = sortId;
    }

    public Integer getSortId() 
    {
        return sortId;
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
            .append("showType", getShowType())
            .append("linkType", getLinkType())
            .append("dataId", getDataId())
            .append("picUrl", getPicUrl())
            .append("contents", getContents())
            .append("sortId", getSortId())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}

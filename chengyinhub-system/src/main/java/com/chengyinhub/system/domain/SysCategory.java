package com.chengyinhub.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.List;

/**
 * 活动、主题等分类对象 sys_category
 * 
 * @author webxh
 * @date 2025-08-31
 */
public class SysCategory extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /**  类型 1、活动  2、主题 */
    @Excel(name = " 类型1、主题  2、活动  3、创意广场 4、模版 5其它")
    private Long type;

    /** 上级分类的编号：0表示一级分类 */
    @Excel(name = "上级分类的编号：0表示一级分类")
    private Long parentId;

    /** 分类名称 */
    @Excel(name = "分类名称")
    private String categoryName;

    /** 分类级别：0-&gt;1级；1-&gt;2级 */
    @Excel(name = "分类级别：0-&gt;1级；1-&gt;2级")
    private Long level;

    /** 排序 */
    @Excel(name = "排序")
    private Long sortId;

    /** logo 图片 */
    @Excel(name = "logo 图片")
    private String icon;

    /** 状态 1显示 2隐藏 */
    @Excel(name = "状态 1显示 2隐藏")
    private Integer status;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;


    private List<SysCategory> subCategoryList;

    public List<SysCategory> getSubCategoryList() {
        return subCategoryList;
    }

    public void setSubCategoryList(List<SysCategory> subCategoryList) {
        this.subCategoryList = subCategoryList;
    }


    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setType(Long type) 
    {
        this.type = type;
    }

    public Long getType() 
    {
        return type;
    }

    public void setParentId(Long parentId) 
    {
        this.parentId = parentId;
    }

    public Long getParentId() 
    {
        return parentId;
    }

    public void setCategoryName(String categoryName) 
    {
        this.categoryName = categoryName;
    }

    public String getCategoryName() 
    {
        return categoryName;
    }

    public void setLevel(Long level) 
    {
        this.level = level;
    }

    public Long getLevel() 
    {
        return level;
    }

    public void setSortId(Long sortId) 
    {
        this.sortId = sortId;
    }

    public Long getSortId() 
    {
        return sortId;
    }

    public void setIcon(String icon) 
    {
        this.icon = icon;
    }

    public String getIcon() 
    {
        return icon;
    }

    public void setStatus(Integer status) 
    {
        this.status = status;
    }

    public Integer getStatus() 
    {
        return status;
    }

    public void setDelFlag(Integer delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag() 
    {
        return delFlag;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("type", getType())
            .append("parentId", getParentId())
            .append("categoryName", getCategoryName())
            .append("level", getLevel())
            .append("sortId", getSortId())
            .append("icon", getIcon())
            .append("status", getStatus())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}

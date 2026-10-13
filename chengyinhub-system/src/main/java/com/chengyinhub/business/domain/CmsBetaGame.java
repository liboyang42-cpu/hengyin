package com.chengyinhub.business.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 活动对象 cms_beta_game
 * 
 * @author webxh
 * @date 2025-10-13
 */
public class CmsBetaGame extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /**  */
    @Excel(name = "ID",sort = 1)
    private Long id;

    /** 游戏名称 */
    @Excel(name = "游戏名称",sort = 10)
    private String name;

    /** 游戏描述 */
//    @Excel(name = "游戏描述")
    private String description;

    /** 游戏封面图 */
    //@Excel(name = "游戏封面图")
    private String imgUrl;

    /** 游戏轮播图（多张）分号隔开 */
    //@Excel(name = "游戏轮播图", readConverterExp = "多=张")
    private String imgArr;

    /** 主题/活动 */
    @Excel(name = "类型",sort = 20)
    private String typeName;

    /** 游戏标签,逗号隔开 */
    @Excel(name = "游戏标签,逗号隔开",sort = 30)
    private String tags;

    /** 后台审核状态：0下架  1上架 */
    @Excel(name = "状态", readConverterExp = "0=正常,1=停用",sort = 40)
    private Long status;

    /** 删除标记 0 代表存在  2删除 */
    private Long delFlag;

    /** 投票开始日期 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "投票开始日期", width = 30, dateFormat = "yyyy-MM-dd",sort =50 )
    private Date startDate;

    /** 投票结束日期 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "投票结束日期", width = 30, dateFormat = "yyyy-MM-dd",sort = 60)
    private Date endDate;

    private Integer isVote;//是否投票

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setName(String name) 
    {
        this.name = name;
    }

    public String getName() 
    {
        return name;
    }

    public void setDescription(String description) 
    {
        this.description = description;
    }

    public String getDescription() 
    {
        return description;
    }

    public void setImgUrl(String imgUrl) 
    {
        this.imgUrl = imgUrl;
    }

    public String getImgUrl() 
    {
        return imgUrl;
    }

    public void setImgArr(String imgArr) 
    {
        this.imgArr = imgArr;
    }

    public String getImgArr() 
    {
        return imgArr;
    }

    public void setTypeName(String typeName) 
    {
        this.typeName = typeName;
    }

    public String getTypeName() 
    {
        return typeName;
    }

    public void setTags(String tags) 
    {
        this.tags = tags;
    }

    public String getTags() 
    {
        return tags;
    }

    public void setStatus(Long status) 
    {
        this.status = status;
    }

    public Long getStatus() 
    {
        return status;
    }

    public void setDelFlag(Long delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Long getDelFlag() 
    {
        return delFlag;
    }

    public void setStartDate(Date startDate) 
    {
        this.startDate = startDate;
    }

    public Date getStartDate() 
    {
        return startDate;
    }

    public void setEndDate(Date endDate) 
    {
        this.endDate = endDate;
    }

    public Date getEndDate() 
    {
        return endDate;
    }


    public Integer getIsVote() {
        return isVote;
    }

    public void setIsVote(Integer isVote) {
        this.isVote = isVote;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("name", getName())
            .append("description", getDescription())
            .append("imgUrl", getImgUrl())
            .append("imgArr", getImgArr())
            .append("typeName", getTypeName())
            .append("tags", getTags())
            .append("status", getStatus())
            .append("delFlag", getDelFlag())
            .append("startDate", getStartDate())
            .append("endDate", getEndDate())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}

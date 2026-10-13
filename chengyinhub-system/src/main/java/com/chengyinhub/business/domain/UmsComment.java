package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 用户评论对象 ums_comment
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public class UmsComment extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /** 拥有类型1、主题  2、活动  3、创意广场 */
    @Excel(name = "拥有类型1、主题  2、活动  3、创意广场")
    private Long ownerType;

    /** 主题ID/活动ID/创意广场帖子ID */
    @Excel(name = "主题ID/活动ID/创意广场帖子ID")
    private Long ownerId;

    /** 评分0-5 */
    @Excel(name = "评分0-5")
    private Long rating;

    /** 评论内容 */
    @Excel(name = "评论内容")
    private String contents;

    /** 回复评论ID */
    @Excel(name = "回复评论ID")
    private Long replyId;

    /** 评论图片（多张）分号隔开 */
    @Excel(name = "评论图片", readConverterExp = "多=张")
    private String imgArr;

    /** 状态 1显示 2隐藏 */
    @Excel(name = "状态 1显示 2隐藏")
    private Integer status;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;

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

    public void setOwnerType(Long ownerType) 
    {
        this.ownerType = ownerType;
    }

    public Long getOwnerType() 
    {
        return ownerType;
    }

    public void setOwnerId(Long ownerId) 
    {
        this.ownerId = ownerId;
    }

    public Long getOwnerId() 
    {
        return ownerId;
    }

    public void setRating(Long rating) 
    {
        this.rating = rating;
    }

    public Long getRating() 
    {
        return rating;
    }

    public void setContents(String contents) 
    {
        this.contents = contents;
    }

    public String getContents() 
    {
        return contents;
    }

    public void setReplyId(Long replyId) 
    {
        this.replyId = replyId;
    }

    public Long getReplyId() 
    {
        return replyId;
    }

    public void setImgArr(String imgArr) 
    {
        this.imgArr = imgArr;
    }

    public String getImgArr() 
    {
        return imgArr;
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
            .append("memberId", getMemberId())
            .append("ownerType", getOwnerType())
            .append("ownerId", getOwnerId())
            .append("rating", getRating())
            .append("contents", getContents())
            .append("replyId", getReplyId())
            .append("imgArr", getImgArr())
            .append("status", getStatus())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}

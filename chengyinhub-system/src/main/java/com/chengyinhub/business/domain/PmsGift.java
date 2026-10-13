package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 众筹礼物对象 pms_gift
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
public class PmsGift extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 礼物名称 */
    @Excel(name = "礼物名称")
    private String giftName;

    /** 礼物图片（支持GIF） */
    @Excel(name = "礼物图片", readConverterExp = "支=持GIF")
    private String picUrl;

    /** 礼物介绍 */
    @Excel(name = "礼物介绍")
    private String contents;

    /** 所需积分 */
    @Excel(name = "所需积分")
    private Long point;

    /** 状态 1显示 2隐藏 */
    @Excel(name = "状态 1显示 2隐藏")
    private Integer status;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;

    /**
     * 调价前积分，仅用于后台操作日志兼容字段；不落 pms_gift 表。
     * 不可缺失的持久化审计记录写入 pms_gift_change_audit。
     */
    private Long auditBeforePoint;

    /**
     * 后台变更说明，不落 pms_gift 表；持久化审计记录写入 pms_gift_change_audit。
     */
    private String auditReason;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setGiftName(String giftName) 
    {
        this.giftName = giftName;
    }

    public String getGiftName() 
    {
        return giftName;
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

    public void setPoint(Long point) 
    {
        this.point = point;
    }

    public Long getPoint() 
    {
        return point;
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

    public void setAuditBeforePoint(Long auditBeforePoint)
    {
        this.auditBeforePoint = auditBeforePoint;
    }

    public Long getAuditBeforePoint()
    {
        return auditBeforePoint;
    }

    public void setAuditReason(String auditReason)
    {
        this.auditReason = auditReason;
    }

    public String getAuditReason()
    {
        return auditReason;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("giftName", getGiftName())
            .append("picUrl", getPicUrl())
            .append("contents", getContents())
            .append("point", getPoint())
            .append("status", getStatus())
            .append("delFlag", getDelFlag())
            .append("auditBeforePoint", getAuditBeforePoint())
            .append("auditReason", getAuditReason())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}

package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 主题节点（路线）对象 cms_topic_node
 * 
 * @author ruoyi
 * @date 2025-09-12
 */
public class CmsTopicNode extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /**  */
    private Long id;

    /** 主题的ID */
    @Excel(name = "主题的ID")
    private Long topicId;

    /** 章节ID */
    @Excel(name = "章节ID")
    private Long chapterId;

    /**
     * 该节点归属的承接商家 member_id。NULL = 主办方自建节点。
     * 自由探索允许一章节多商家,每家一个自己的节点(2026-08-06 拍板 B 方案)。
     */
    private Long merchantMemberId;

    /**
     * 商家节点审核态:0 待审 / 1 通过 / 2 驳回。NULL = 主办方节点,不走审核。
     * ⚠️ 玩家侧可见性判据是「merchantMemberId 为空 或 nodeAuditStatus=1」——
     * 写成「nodeAuditStatus<>1 才隐藏」会把 NULL 的存量主办方节点一起隐藏,整条路线空掉。
     */
    private Integer nodeAuditStatus;

    /** 驳回原因(审核态=2 时给商家看) */
    private String nodeAuditReason;

    /** 放行/驳回该节点的主办方 member_id。⚠️ 审核是**准入不是背书**,内容责任仍在作者(商家)。 */
    private Long nodeAuditMemberId;

    /** 审核时间 */
    private java.util.Date nodeAuditTime;

    /** 普通编辑换绑商家时，要求 mapper 显式清空上一家的审核留痕。 */
    private boolean clearNodeAuditTrace;


    /** 节点名称 */
    @Excel(name = "节点名称")
    private String name;

    /** 节点描述 */
    @Excel(name = "节点描述")
    private String description;

    /** 节点地址 */
    @Excel(name = "节点地址")
    private String address;

    /** 地址经度 */
    @Excel(name = "地址经度")
    private String longitude;

    /** 地址纬度 */
    @Excel(name = "地址纬度")
    private String latitude;

    /** 地点图片（单张） */
    @Excel(name = "地点图片", readConverterExp = "单=张")
    private String imgUrl;

    /** 节点时间(分钟） */
    @Excel(name = "节点时间(分钟）")
    private Integer nodeTime;

    /** 我的模版ID */
    @Excel(name = "我的模版ID")
    private Long templateId;

    /** 节点打卡码(扫码内容)，唯一随机串 */
    private String checkinCode;

    /** 该地点营业时间 */
    @Excel(name = "该地点营业时间")
    private String businessTime;

    /** 状态 0不显示 1显示(@Excel 表头保留原文以免改动后台导出,见 C:U-078) */
    @Excel(name = "状态0不显示 1不显示")
    private Long status;

    @Excel(name = "排序")
    private Long sortId;

    /** M7 纯停靠节点探索值(游戏节点的 xp 挂模板层 CmsMemberTemplate.xpValue) */
    private Integer xpValue;

    public Integer getXpValue() { return xpValue; }
    public void setXpValue(Integer xpValue) { this.xpValue = xpValue; }

    /** 自由定向:卡堆行一句钩子 */
    @Excel(name = "自由定向卡堆钩子")
    private String hookText;

    /** 自由定向:地点大卡 NPC 2-3 句钩子 */
    @Excel(name = "自由定向大卡NPC钩子")
    private String cardHookLong;

    /** 自由定向:故事碎片正文(完成入手记) */
    @Excel(name = "自由定向故事碎片")
    private String fragmentText;

    public String getHookText() { return hookText; }
    public void setHookText(String hookText) { this.hookText = hookText; }
    public String getCardHookLong() { return cardHookLong; }
    public void setCardHookLong(String cardHookLong) { this.cardHookLong = cardHookLong; }
    public String getFragmentText() { return fragmentText; }
    public void setFragmentText(String fragmentText) { this.fragmentText = fragmentText; }

    /** 节点级前置解锁:完成该节点(progress.status=1)才能玩本节点;NULL/0=无前置。复制主题时随 nodeIdMap 重映射 */
    private Long unlockAfterNodeId;

    public Long getUnlockAfterNodeId() { return unlockAfterNodeId; }
    public void setUnlockAfterNodeId(Long unlockAfterNodeId) { this.unlockAfterNodeId = unlockAfterNodeId; }

    public Long getSortId() {
        return sortId;
    }

    public void setSortId(Long sortId) {
        this.sortId = sortId;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setTopicId(Long topicId) 
    {
        this.topicId = topicId;
    }

    public Long getTopicId() 
    {
        return topicId;
    }

    public void setChapterId(Long chapterId) 
    {
        this.chapterId = chapterId;
    }

    public Long getMerchantMemberId()
    {
        return merchantMemberId;
    }

    public void setMerchantMemberId(Long merchantMemberId)
    {
        this.merchantMemberId = merchantMemberId;
    }

    public Integer getNodeAuditStatus()
    {
        return nodeAuditStatus;
    }

    public void setNodeAuditStatus(Integer nodeAuditStatus)
    {
        this.nodeAuditStatus = nodeAuditStatus;
    }

    public String getNodeAuditReason()
    {
        return nodeAuditReason;
    }

    public void setNodeAuditReason(String nodeAuditReason)
    {
        this.nodeAuditReason = nodeAuditReason;
    }

    public Long getNodeAuditMemberId()
    {
        return nodeAuditMemberId;
    }

    public void setNodeAuditMemberId(Long nodeAuditMemberId)
    {
        this.nodeAuditMemberId = nodeAuditMemberId;
    }

    public java.util.Date getNodeAuditTime()
    {
        return nodeAuditTime;
    }

    public void setNodeAuditTime(java.util.Date nodeAuditTime)
    {
        this.nodeAuditTime = nodeAuditTime;
    }

    public boolean isClearNodeAuditTrace()
    {
        return clearNodeAuditTrace;
    }

    public void setClearNodeAuditTrace(boolean clearNodeAuditTrace)
    {
        this.clearNodeAuditTrace = clearNodeAuditTrace;
    }

    public Long getChapterId() 
    {
        return chapterId;
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

    public void setAddress(String address) 
    {
        this.address = address;
    }

    public String getAddress() 
    {
        return address;
    }

    public void setLongitude(String longitude) 
    {
        this.longitude = longitude;
    }

    public String getLongitude() 
    {
        return longitude;
    }

    public void setLatitude(String latitude) 
    {
        this.latitude = latitude;
    }

    public String getLatitude() 
    {
        return latitude;
    }

    public void setImgUrl(String imgUrl) 
    {
        this.imgUrl = imgUrl;
    }

    public String getImgUrl() 
    {
        return imgUrl;
    }

    public void setNodeTime(Integer nodeTime)
    {
        this.nodeTime = nodeTime;
    }

    public Integer getNodeTime()
    {
        return nodeTime;
    }

    public void setTemplateId(Long templateId) 
    {
        this.templateId = templateId;
    }

    public Long getTemplateId()
    {
        return templateId;
    }

    public void setCheckinCode(String checkinCode)
    {
        this.checkinCode = checkinCode;
    }

    public String getCheckinCode()
    {
        return checkinCode;
    }

    public void setBusinessTime(String businessTime) 
    {
        this.businessTime = businessTime;
    }

    public String getBusinessTime() 
    {
        return businessTime;
    }

    public void setStatus(Long status) 
    {
        this.status = status;
    }

    public Long getStatus() 
    {
        return status;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("topicId", getTopicId())
            .append("chapterId", getChapterId())
            .append("name", getName())
            .append("description", getDescription())
            .append("address", getAddress())
            .append("longitude", getLongitude())
            .append("latitude", getLatitude())
            .append("imgUrl", getImgUrl())
            .append("nodeTime", getNodeTime())
            .append("templateId", getTemplateId())
            .append("businessTime", getBusinessTime())
            .append("status", getStatus())
            .append("sortId", getSortId())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}

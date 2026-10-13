package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 主题/活动票务对象 oms_ticket
 * 
 * @author webxh
 * @date 2025-09-02
 */
public class OmsTicket extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /**  */
    private Long id;

    /** 票务发布用户ID */
    @Excel(name = "票务发布用户ID")
    private Long memberId;

    /** 拥有类型1、主题  2、活动 */
    @Excel(name = "拥有类型1、主题  2、活动")
    private Long ownerType;

    /** 主题ID/活动ID */
    @Excel(name = "主题ID/活动ID")
    private Long ownerId;

    /** 从主题票复制到活动壳时的稳定来源票 id；主题票自身为 NULL。 */
    private Long sourceTicketId;

    /** 票务名称 */
    @Excel(name = "票务名称")
    private String name;

    /** 价格 */
    @Excel(name = "价格")
    private BigDecimal price;

    /** 开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "开始时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    /** 结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "结束时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    /** 售票开始时间(发布页填写;仅落库回显,购票闸未接) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date saleStartTime;

    /** 售票结束时间(发布页填写;仅落库回显,购票闸未接) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date saleEndTime;

    /** 总库存 */
    @Excel(name = "总库存")
    private Long totalInventory;

    /** 剩余库存 */
    @Excel(name = "剩余库存")
    private Long remainingInventory;

    /** 活动模式 1=经典定向 2=自由定向 */
    private Integer mode;

    /** 票种子类型 guided=有人带/self=无人带(仅 mode=1 经典定向) */
    private String subType;

    /** 服务端从自营活动归属计算，不持久化、不接受客户端指定。 */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private Boolean selfGuided;

    public Boolean getSelfGuided() { return selfGuided; }
    public void setSelfGuided(Boolean selfGuided) { this.selfGuided = selfGuided; }

    /** 集合地点 */
    private String meetingPoint;

    /** 集合点地址(地图选点文本,发布页 meetingPointAddress) */
    private String meetingPointAddress;

    /** 建议组队人数 */
    private Integer teamSize;

    /** 最低成团人数(达到则成团);null/<=0 表示不设成团 */
    private Integer minPeople;

    /** 成团人数上限 */
    private Integer maxPeople;

    /** 报名截止时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date signupDeadline;

    /** 集合点经度 */
    private BigDecimal gatherLng;

    /** 集合点纬度 */
    private BigDecimal gatherLat;

    /** 成团状态 0募集中 1已成团 2未成团已散 */
    private Integer teamStatus;

    /** 退款规则(JSON/文本) */
    private String refundRule;

    /** 支持随时退款(发布页填写;仅落库回显,退款规则未接) */
    private Boolean refundSupported;

    /** 售票时间与主题日期同步(发布页填写;仅落库回显,同步逻辑未接) */
    private Boolean syncWithTheme;

    /** 报名是否需主理人确认 0否1是(预留) */
    private Integer needAudit;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;


    private List<ViewRegistration> cmsRegistrationList;


    public List<ViewRegistration> getCmsRegistrationList() {
        return cmsRegistrationList;
    }

    public void setCmsRegistrationList(List<ViewRegistration> cmsRegistrationList) {
        this.cmsRegistrationList = cmsRegistrationList;
    }

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

    public Long getSourceTicketId()
    {
        return sourceTicketId;
    }

    public void setSourceTicketId(Long sourceTicketId)
    {
        this.sourceTicketId = sourceTicketId;
    }

    public void setName(String name) 
    {
        this.name = name;
    }

    public String getName() 
    {
        return name;
    }

    public void setPrice(BigDecimal price) 
    {
        this.price = price;
    }

    public BigDecimal getPrice() 
    {
        return price;
    }

    public void setStartTime(Date startTime)
    {
        this.startTime = startTime;
    }

    public Date getStartTime()
    {
        return startTime;
    }

    public void setEndTime(Date endTime)
    {
        this.endTime = endTime;
    }

    public Date getEndTime()
    {
        return endTime;
    }

    public void setSaleStartTime(Date saleStartTime)
    {
        this.saleStartTime = saleStartTime;
    }

    public Date getSaleStartTime()
    {
        return saleStartTime;
    }

    public void setSaleEndTime(Date saleEndTime)
    {
        this.saleEndTime = saleEndTime;
    }

    public Date getSaleEndTime()
    {
        return saleEndTime;
    }

    public void setTotalInventory(Long totalInventory) 
    {
        this.totalInventory = totalInventory;
    }

    public Long getTotalInventory() 
    {
        return totalInventory;
    }

    public void setRemainingInventory(Long remainingInventory) 
    {
        this.remainingInventory = remainingInventory;
    }

    public Long getRemainingInventory() 
    {
        return remainingInventory;
    }

    public Integer getMode() {
        return mode;
    }

    public void setMode(Integer mode) {
        this.mode = mode;
    }

    public String getSubType() {
        return subType;
    }

    public void setSubType(String subType) {
        this.subType = subType;
    }

    public String getMeetingPoint() {
        return meetingPoint;
    }

    public void setMeetingPoint(String meetingPoint) {
        this.meetingPoint = meetingPoint;
    }

    public String getMeetingPointAddress() {
        return meetingPointAddress;
    }

    public void setMeetingPointAddress(String meetingPointAddress) {
        this.meetingPointAddress = meetingPointAddress;
    }

    public Integer getTeamSize() {
        return teamSize;
    }

    public void setTeamSize(Integer teamSize) {
        this.teamSize = teamSize;
    }

    public Integer getMinPeople() { return minPeople; }
    public void setMinPeople(Integer minPeople) { this.minPeople = minPeople; }
    public Integer getMaxPeople() { return maxPeople; }
    public void setMaxPeople(Integer maxPeople) { this.maxPeople = maxPeople; }
    public Date getSignupDeadline() { return signupDeadline; }
    public void setSignupDeadline(Date signupDeadline) { this.signupDeadline = signupDeadline; }
    public BigDecimal getGatherLng() { return gatherLng; }
    public void setGatherLng(BigDecimal gatherLng) { this.gatherLng = gatherLng; }
    public BigDecimal getGatherLat() { return gatherLat; }
    public void setGatherLat(BigDecimal gatherLat) { this.gatherLat = gatherLat; }
    public Integer getTeamStatus() { return teamStatus; }
    public void setTeamStatus(Integer teamStatus) { this.teamStatus = teamStatus; }
    public String getRefundRule() { return refundRule; }
    public void setRefundRule(String refundRule) { this.refundRule = refundRule; }
    public Boolean getRefundSupported() { return refundSupported; }
    public void setRefundSupported(Boolean refundSupported) { this.refundSupported = refundSupported; }
    public Boolean getSyncWithTheme() { return syncWithTheme; }
    public void setSyncWithTheme(Boolean syncWithTheme) { this.syncWithTheme = syncWithTheme; }
    public Integer getNeedAudit() { return needAudit; }
    public void setNeedAudit(Integer needAudit) { this.needAudit = needAudit; }

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
            .append("name", getName())
            .append("price", getPrice())
            .append("startTime", getStartTime())
            .append("endTime", getEndTime())
            .append("mode", getMode())
            .append("meetingPoint", getMeetingPoint())
            .append("teamSize", getTeamSize())
            .append("totalInventory", getTotalInventory())
            .append("remainingInventory", getRemainingInventory())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}

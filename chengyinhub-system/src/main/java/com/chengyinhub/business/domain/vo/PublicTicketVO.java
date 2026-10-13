package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.ViewRegistration;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 公开活动/主题详情中的票种白名单，不包含发布者、归属、删除及报名管理字段。 */
public class PublicTicketVO {
    private Long id;
    private String name;
    private BigDecimal price;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;
    private Long totalInventory;
    private Long remainingInventory;
    private Integer mode;
    private String subType;
    private Boolean selfGuided;

    public Boolean getSelfGuided() { return selfGuided; }
    public void setSelfGuided(Boolean selfGuided) { this.selfGuided = selfGuided; }
    private String meetingPoint;
    private Integer teamSize;
    private Integer minPeople;
    private Integer maxPeople;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date signupDeadline;
    private BigDecimal gatherLng;
    private BigDecimal gatherLat;
    private Integer teamStatus;
    private String refundRule;
    private Integer needAudit;
    private Integer registrationCount;
    private List<PublicRegistrationSummaryVO> cmsRegistrationList;

    public static PublicTicketVO from(OmsTicket source, List<ViewRegistration> registrations) {
        PublicTicketVO out = new PublicTicketVO();
        if (source != null) {
            out.setId(source.getId());
            out.setName(source.getName());
            out.setPrice(source.getPrice());
            out.setStartTime(source.getStartTime());
            out.setEndTime(source.getEndTime());
            out.setTotalInventory(source.getTotalInventory());
            out.setRemainingInventory(source.getRemainingInventory());
            out.setMode(source.getMode());
            out.setSubType(source.getSubType());
            out.setSelfGuided(source.getSelfGuided());
            out.setMeetingPoint(source.getMeetingPoint());
            out.setTeamSize(source.getTeamSize());
            out.setMinPeople(source.getMinPeople());
            out.setMaxPeople(source.getMaxPeople());
            out.setSignupDeadline(source.getSignupDeadline());
            out.setGatherLng(source.getGatherLng());
            out.setGatherLat(source.getGatherLat());
            out.setTeamStatus(source.getTeamStatus());
            // 5.9:带队场次票(ownerType=2 且非自由探索)对玩家只承诺平台统一口径;
            // 自由探索票/主题票(快照钟、履约窗)原样保留发布者文本。
            out.setRefundRule(com.chengyinhub.business.util.RefundPolicy.isGuidedSessionTicket(
                    source.getOwnerType(), source.getMode())
                    ? com.chengyinhub.business.util.RefundPolicy.SESSION_REFUND_RULE_TEXT
                    : source.getRefundRule());
            out.setNeedAudit(source.getNeedAudit());
        }
        List<PublicRegistrationSummaryVO> summaries = new ArrayList<>();
        if (registrations != null) {
            for (ViewRegistration registration : registrations) {
                summaries.add(PublicRegistrationSummaryVO.from(registration));
            }
        }
        out.setCmsRegistrationList(summaries);
        out.setRegistrationCount(summaries.size());
        return out;
    }

    /** 玩家公开主题详情只给人数，不给报名者身份；名册由俱乐部 owner/admin 专用接口提供。 */
    public static PublicTicketVO fromWithoutRegistrationIdentities(
            OmsTicket source, List<ViewRegistration> registrations) {
        PublicTicketVO out = from(source, null);
        out.setRegistrationCount(registrations == null ? 0 : registrations.size());
        return out;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }
    public Date getEndTime() { return endTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }
    public Long getTotalInventory() { return totalInventory; }
    public void setTotalInventory(Long totalInventory) { this.totalInventory = totalInventory; }
    public Long getRemainingInventory() { return remainingInventory; }
    public void setRemainingInventory(Long remainingInventory) { this.remainingInventory = remainingInventory; }
    public Integer getMode() { return mode; }
    public void setMode(Integer mode) { this.mode = mode; }
    public String getSubType() { return subType; }
    public void setSubType(String subType) { this.subType = subType; }
    public String getMeetingPoint() { return meetingPoint; }
    public void setMeetingPoint(String meetingPoint) { this.meetingPoint = meetingPoint; }
    public Integer getTeamSize() { return teamSize; }
    public void setTeamSize(Integer teamSize) { this.teamSize = teamSize; }
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
    public Integer getNeedAudit() { return needAudit; }
    public void setNeedAudit(Integer needAudit) { this.needAudit = needAudit; }
    public Integer getRegistrationCount() { return registrationCount; }
    public void setRegistrationCount(Integer registrationCount) { this.registrationCount = registrationCount; }
    public List<PublicRegistrationSummaryVO> getCmsRegistrationList() { return cmsRegistrationList; }
    public void setCmsRegistrationList(List<PublicRegistrationSummaryVO> cmsRegistrationList) { this.cmsRegistrationList = cmsRegistrationList; }
}

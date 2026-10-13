package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;

/**
 * 商家调配候选的最小披露视图。
 *
 * <p>调配只需识别报名、竞争位、状态与原因；联系方式、精确地址、问答答案和历史结算字段
 * 不属于该决策场景，禁止随调配树返回。</p>
 */
public class MerchantDispatchCandidateVO {

    private Long id;
    private Long memberId;
    private Integer mode;
    private Long nodeId;
    private String addressName;
    private Long templateId;
    private String activityDesc;
    private Long auditStatus;
    private String reason;

    public static MerchantDispatchCandidateVO from(CmsRegistrationMerchant source) {
        MerchantDispatchCandidateVO target = new MerchantDispatchCandidateVO();
        target.setId(source.getId());
        target.setMemberId(source.getMemberId());
        target.setMode(source.getMode());
        target.setNodeId(source.getNodeId());
        target.setAddressName(source.getAddressName());
        target.setTemplateId(source.getTemplateId());
        target.setActivityDesc(source.getActivityDesc());
        target.setAuditStatus(source.getAuditStatus());
        target.setReason(source.getReason());
        return target;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Integer getMode() { return mode; }
    public void setMode(Integer mode) { this.mode = mode; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getAddressName() { return addressName; }
    public void setAddressName(String addressName) { this.addressName = addressName; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getActivityDesc() { return activityDesc; }
    public void setActivityDesc(String activityDesc) { this.activityDesc = activityDesc; }
    public Long getAuditStatus() { return auditStatus; }
    public void setAuditStatus(Long auditStatus) { this.auditStatus = auditStatus; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

package com.chengyinhub.business.domain.dto;

import java.util.Date;

/**
 * 发行请求。商品与数量不在此选择，必须由已验证渠道订单映射，
 * 运营只能决定有效期、持有人规则和可选库存预留。
 */
public class ChannelVoucherIssueRequest {

    private Long verificationId;
    private Date expiresAt;
    private Integer subjectMode;
    private Long boundMemberId;
    private Long inventoryTicketId;
    private Integer inventoryQuantity;
    private String requestId;

    public Long getVerificationId() { return verificationId; }
    public void setVerificationId(Long verificationId) { this.verificationId = verificationId; }

    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }

    public Integer getSubjectMode() { return subjectMode; }
    public void setSubjectMode(Integer subjectMode) { this.subjectMode = subjectMode; }

    public Long getBoundMemberId() { return boundMemberId; }
    public void setBoundMemberId(Long boundMemberId) { this.boundMemberId = boundMemberId; }

    public Long getInventoryTicketId() { return inventoryTicketId; }
    public void setInventoryTicketId(Long inventoryTicketId) { this.inventoryTicketId = inventoryTicketId; }

    public Integer getInventoryQuantity() { return inventoryQuantity; }
    public void setInventoryQuantity(Integer inventoryQuantity) { this.inventoryQuantity = inventoryQuantity; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}

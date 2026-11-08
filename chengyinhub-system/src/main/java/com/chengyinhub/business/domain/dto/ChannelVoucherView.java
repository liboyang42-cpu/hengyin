package com.chengyinhub.business.domain.dto;

import java.util.Date;

/**
 * 发行结果。{@link #code} 只在首次发行响应中出现；
 * 同 request 重放返回 {@code codeAvailable=false}，绝不回读明文。
 */
public class ChannelVoucherView {

    private Long id;
    private String publicId;
    private String code;
    private Boolean codeAvailable;
    private Long accountId;
    private String channelKey;
    private Long verificationId;
    private String channelOrderNo;
    private Long productId;
    private Long productRevision;
    private Integer quantity;
    private Integer seatCount;
    private Integer subjectMode;
    private Long boundMemberId;
    private Integer status;
    private Date issuedAt;
    private Date expiresAt;
    private Long inventoryTicketId;
    private Integer inventoryQuantity;
    private Integer inventoryState;
    private Boolean replayed;
    private Boolean revokePending;
    private String revokeReasonCode;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public Boolean getCodeAvailable() { return codeAvailable; }
    public void setCodeAvailable(Boolean codeAvailable) { this.codeAvailable = codeAvailable; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public String getChannelKey() { return channelKey; }
    public void setChannelKey(String channelKey) { this.channelKey = channelKey; }

    public Long getVerificationId() { return verificationId; }
    public void setVerificationId(Long verificationId) { this.verificationId = verificationId; }

    public String getChannelOrderNo() { return channelOrderNo; }
    public void setChannelOrderNo(String channelOrderNo) { this.channelOrderNo = channelOrderNo; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long getProductRevision() { return productRevision; }
    public void setProductRevision(Long productRevision) { this.productRevision = productRevision; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public Integer getSeatCount() { return seatCount; }
    public void setSeatCount(Integer seatCount) { this.seatCount = seatCount; }

    public Integer getSubjectMode() { return subjectMode; }
    public void setSubjectMode(Integer subjectMode) { this.subjectMode = subjectMode; }

    public Long getBoundMemberId() { return boundMemberId; }
    public void setBoundMemberId(Long boundMemberId) { this.boundMemberId = boundMemberId; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Date getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Date issuedAt) { this.issuedAt = issuedAt; }

    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }

    public Long getInventoryTicketId() { return inventoryTicketId; }
    public void setInventoryTicketId(Long inventoryTicketId) { this.inventoryTicketId = inventoryTicketId; }

    public Integer getInventoryQuantity() { return inventoryQuantity; }
    public void setInventoryQuantity(Integer inventoryQuantity) { this.inventoryQuantity = inventoryQuantity; }

    public Integer getInventoryState() { return inventoryState; }
    public void setInventoryState(Integer inventoryState) { this.inventoryState = inventoryState; }

    public Boolean getRevokePending() { return revokePending; }
    public void setRevokePending(Boolean revokePending) { this.revokePending = revokePending; }

    public String getRevokeReasonCode() { return revokeReasonCode; }
    public void setRevokeReasonCode(String revokeReasonCode) { this.revokeReasonCode = revokeReasonCode; }

    public Boolean getReplayed() { return replayed; }
    public void setReplayed(Boolean replayed) { this.replayed = replayed; }
}

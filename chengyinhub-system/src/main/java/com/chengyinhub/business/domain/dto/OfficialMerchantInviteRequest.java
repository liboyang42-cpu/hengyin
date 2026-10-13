package com.chengyinhub.business.domain.dto;

import java.util.List;
import java.math.BigDecimal;
import java.util.Date;

/** 官方活动批量邀请商家承接请求。 */
public class OfficialMerchantInviteRequest {
    private Long topicId;
    private Long nodeId;
    private List<Long> merchantIds;
    private String message;
    private Date expireTime;
    private Integer shareMode;
    private BigDecimal shareRate;
    private BigDecimal fixedFee;

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public List<Long> getMerchantIds() { return merchantIds; }
    public void setMerchantIds(List<Long> merchantIds) { this.merchantIds = merchantIds; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Date getExpireTime() { return expireTime; }
    public void setExpireTime(Date expireTime) { this.expireTime = expireTime; }
    public Integer getShareMode() { return shareMode; }
    public void setShareMode(Integer shareMode) { this.shareMode = shareMode; }
    public BigDecimal getShareRate() { return shareRate; }
    public void setShareRate(BigDecimal shareRate) { this.shareRate = shareRate; }
    public BigDecimal getFixedFee() { return fixedFee; }
    public void setFixedFee(BigDecimal fixedFee) { this.fixedFee = fixedFee; }
}

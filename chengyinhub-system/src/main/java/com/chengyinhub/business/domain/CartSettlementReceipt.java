package com.chengyinhub.business.domain;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** 会员积分兑换的可恢复结果。 */
public class CartSettlementReceipt implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long memberId;
    private String requestId;
    private String requestHash;
    private String intentHash;
    private Long firstOrderId;
    private List<Long> orderIds = new ArrayList<>();
    private Long totalPoints;
    private String status;

    @JsonIgnore
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    @JsonIgnore
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    @JsonIgnore
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    @JsonIgnore
    public String getIntentHash() { return intentHash; }
    public void setIntentHash(String intentHash) { this.intentHash = intentHash; }
    public Long getFirstOrderId() { return firstOrderId; }
    public void setFirstOrderId(Long firstOrderId) { this.firstOrderId = firstOrderId; }
    public List<Long> getOrderIds() { return orderIds; }
    public void setOrderIds(List<Long> orderIds) { this.orderIds = orderIds; }
    public Long getTotalPoints() { return totalPoints; }
    public void setTotalPoints(Long totalPoints) { this.totalPoints = totalPoints; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}

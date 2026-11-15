package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * M3 盲盒每日下发记录 node_task_dispatch。
 */
public class NodeTaskDispatch {
    private Long id;
    private Long memberId;
    private Long poolId;
    /** YYYY-MM-DD (Asia/Shanghai) */
    private String dispatchDate;
    private Long nodeId;
    private String randomReceiptId;
    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getPoolId() { return poolId; }
    public void setPoolId(Long poolId) { this.poolId = poolId; }
    public String getDispatchDate() { return dispatchDate; }
    public void setDispatchDate(String dispatchDate) { this.dispatchDate = dispatchDate; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getRandomReceiptId() { return randomReceiptId; }
    public void setRandomReceiptId(String randomReceiptId) { this.randomReceiptId = randomReceiptId; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}

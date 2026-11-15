package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * M3 盲盒任务池 node_task_pool。
 */
public class NodeTaskPool {
    private Long id;
    private String poolName;
    private Long merchantMemberId;
    private Long entryNodeId;
    private Long topicId;
    /** 1=启用 0=停用 */
    private Integer status;
    private String createBy;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPoolName() { return poolName; }
    public void setPoolName(String poolName) { this.poolName = poolName; }
    public Long getMerchantMemberId() { return merchantMemberId; }
    public void setMerchantMemberId(Long merchantMemberId) { this.merchantMemberId = merchantMemberId; }
    public Long getEntryNodeId() { return entryNodeId; }
    public void setEntryNodeId(Long entryNodeId) { this.entryNodeId = entryNodeId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}

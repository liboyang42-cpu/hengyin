package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * M3 盲盒任务池条目 node_task_pool_item。
 */
public class NodeTaskPoolItem {
    private Long id;
    private Long poolId;
    private Long nodeId;
    private Integer weight;
    /** 1=启用 0=停用 */
    private Integer status;
    private Date createTime;
    /** 关联站点名（查询投影只读） */
    private String nodeName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPoolId() { return poolId; }
    public void setPoolId(Long poolId) { this.poolId = poolId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
}

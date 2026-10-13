package com.chengyinhub.business.domain;

import java.util.Date;

/** REOPEN_NEXT_EDITION 的数据库幂等占位；一条成功请求只对应一个新期。 */
public class TopicEditionReopenRequest {
    private Long id;
    private Long sourceTopicId;
    private String idempotencyKey;
    private Long newTopicId;
    private String createBy;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSourceTopicId() { return sourceTopicId; }
    public void setSourceTopicId(Long sourceTopicId) { this.sourceTopicId = sourceTopicId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Long getNewTopicId() { return newTopicId; }
    public void setNewTopicId(Long newTopicId) { this.newTopicId = newTopicId; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}

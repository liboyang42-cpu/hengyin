package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * P0-35 角色槽 —— 一个主题上「谁占哪个位置」的声明。
 *
 * <p>见 {@link com.chengyinhub.common.constant.ProductSlot} /
 * {@link com.chengyinhub.common.constant.RoleEntityType}。
 */
public class ProductRole {

    private Long id;

    /** 主题 id。 */
    private Long topicId;

    /** 角色槽,见 {@link com.chengyinhub.common.constant.ProductSlot}。 */
    private String slot;

    /** 主体类型,见 {@link com.chengyinhub.common.constant.RoleEntityType}。 */
    private String entityType;

    /** 主体 id。⚠️ entityType=PLATFORM 时为 null —— 平台不是某个 member(库有 CHECK 兜底)。 */
    private Long entityId;

    /** 该槽的分成比例。本轮不落分账逻辑,列先留着由 P0-46/P0-50 填。 */
    private java.math.BigDecimal revenueShare;

    /** 1 生效 0 失效。⚠️ 旧承接标失效**不删行**(要留痕),唯一键只对生效行生效。 */
    private Integer status;

    /** 声明该角色槽的后台操作账号；用于冻结 actor 变更审计，不参与主体归属判断。 */
    private Long declaredBy;

    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getSlot() { return slot; }
    public void setSlot(String slot) { this.slot = slot; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }

    public java.math.BigDecimal getRevenueShare() { return revenueShare; }
    public void setRevenueShare(java.math.BigDecimal revenueShare) { this.revenueShare = revenueShare; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Long getDeclaredBy() { return declaredBy; }
    public void setDeclaredBy(Long declaredBy) { this.declaredBy = declaredBy; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}

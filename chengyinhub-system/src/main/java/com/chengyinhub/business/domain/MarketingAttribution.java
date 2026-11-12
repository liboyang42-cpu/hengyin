package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 下单来源快照(归因事实,insert-only)。表 marketing_attribution。
 *
 * <p>一单一行:{@code UNIQUE(order_type, order_id)}。同单第二个不同来源一律拒绝
 * (409 ATTRIBUTION_CONFLICT),不覆盖 —— 归因一旦写下就是历史,和"支付后不得改票源归因"
 * 是同一条原则。
 *
 * <p><b>本表刻意不含任何金额/费率/支付状态列</b>:归因只回答"这单从哪来",
 * 不参与报价、锁价、分账、退款与权益;写入路径也不 touch cms_registration。
 */
public class MarketingAttribution {

    /** 订单类型:1 报名单(cms_registration)。当前 v1 只支持这一种。 */
    public static final int ORDER_REGISTRATION = 1;

    private Long id;
    private Integer orderType;
    private Long orderId;
    private Long credentialId;
    /** 分享者(=凭据签发人),写入时从凭据冗余,便于按人统计而不依赖 join 时点。 */
    private Long sharerMemberId;
    private String campaignKey;
    /** 实际下单人;必须等于订单 member_id,否则拒写。 */
    private Long claimedMemberId;
    /** 下单时刻快照(取订单 create_time),与凭据有效期共同构成归因窗口。 */
    private Date orderCreatedAt;
    /** 上方字段的规范化 SHA-256,用于发现事后篡改。 */
    private String snapshotHash;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getOrderType() { return orderType; }
    public void setOrderType(Integer orderType) { this.orderType = orderType; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getCredentialId() { return credentialId; }
    public void setCredentialId(Long credentialId) { this.credentialId = credentialId; }

    public Long getSharerMemberId() { return sharerMemberId; }
    public void setSharerMemberId(Long sharerMemberId) { this.sharerMemberId = sharerMemberId; }

    public String getCampaignKey() { return campaignKey; }
    public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }

    public Long getClaimedMemberId() { return claimedMemberId; }
    public void setClaimedMemberId(Long claimedMemberId) { this.claimedMemberId = claimedMemberId; }

    public Date getOrderCreatedAt() { return orderCreatedAt; }
    public void setOrderCreatedAt(Date orderCreatedAt) { this.orderCreatedAt = orderCreatedAt; }

    public String getSnapshotHash() { return snapshotHash; }
    public void setSnapshotHash(String snapshotHash) { this.snapshotHash = snapshotHash; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}

package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家本人推广/推荐收入只读汇总（需登录）。
 *
 * <p><b>只聚合已存在的事实</b>：成交计数来自 {@code marketing_attribution} 与
 * {@code cms_registration} 的支付/退款状态；金额来自 {@code marketing_revenue_allocation_line}
 * 的整数分分录（statu 1 应计 / 2 可结算 / 3 已打款 / 4-5 冲销）。
 *
 * <p><b>不提现</b>：收入分配域没有把快照标记为已打款或转入钱包的玩家通道，所以这里
 * 只给 {@code payout.available=false} 与原因码，不返回 availableToWithdraw，
 * 也不把商户提现接口暴露给玩家。要分享新链接仍调用原有
 * {@code POST /api/marketing/referral/token}（明文 token 只在签发应答出现一次）。
 */
public class PlayerAffiliateSummaryVO {

    private static final String LINK_ISSUE_ENDPOINT = "/api/marketing/referral/token";

    private String linkIssueEndpoint = LINK_ISSUE_ENDPOINT;
    private List<Credential> credentials = new ArrayList<>();
    private List<Campaign> campaigns = new ArrayList<>();
    private Map<String, Bucket> allocations = new LinkedHashMap<>();
    private Held held;
    private Capability payout;
    private Provenance provenance;

    public String getLinkIssueEndpoint() { return linkIssueEndpoint; }
    public void setLinkIssueEndpoint(String linkIssueEndpoint) { this.linkIssueEndpoint = linkIssueEndpoint; }

    public List<Credential> getCredentials() { return credentials; }
    public void setCredentials(List<Credential> credentials) { this.credentials = credentials; }

    public List<Campaign> getCampaigns() { return campaigns; }
    public void setCampaigns(List<Campaign> campaigns) { this.campaigns = campaigns; }

    public Map<String, Bucket> getAllocations() { return allocations; }
    public void setAllocations(Map<String, Bucket> allocations) { this.allocations = allocations; }

    public Held getHeld() { return held; }
    public void setHeld(Held held) { this.held = held; }

    public Capability getPayout() { return payout; }
    public void setPayout(Capability payout) { this.payout = payout; }

    public Provenance getProvenance() { return provenance; }
    public void setProvenance(Provenance provenance) { this.provenance = provenance; }

    /** 自己名下凭据元数据；不含 token 明文或摘要。 */
    public static class Credential {
        private String campaignKey;
        private Integer targetType;
        private Long targetId;
        private String targetTitle;
        private Integer status;
        private String statusText;
        private boolean active;
        private Date issuedAt;
        private Date expiresAt;

        public String getCampaignKey() { return campaignKey; }
        public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }

        public Integer getTargetType() { return targetType; }
        public void setTargetType(Integer targetType) { this.targetType = targetType; }

        public Long getTargetId() { return targetId; }
        public void setTargetId(Long targetId) { this.targetId = targetId; }

        public String getTargetTitle() { return targetTitle; }
        public void setTargetTitle(String targetTitle) { this.targetTitle = targetTitle; }

        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }

        public String getStatusText() { return statusText; }
        public void setStatusText(String statusText) { this.statusText = statusText; }

        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }

        public Date getIssuedAt() { return issuedAt; }
        public void setIssuedAt(Date issuedAt) { this.issuedAt = issuedAt; }

        public Date getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
    }

    /** 每 campaign+目标 的真实成交与退款聚合。 */
    public static class Campaign {
        private String campaignKey;
        private Integer targetType;
        private Long targetId;
        private String targetTitle;
        private long attributedOrders;
        private long paidOrders;
        private BigDecimal paidAmount;
        private long refundedOrders;
        private BigDecimal refundedAmount;
        private long heldOrders;

        public String getCampaignKey() { return campaignKey; }
        public void setCampaignKey(String campaignKey) { this.campaignKey = campaignKey; }

        public Integer getTargetType() { return targetType; }
        public void setTargetType(Integer targetType) { this.targetType = targetType; }

        public Long getTargetId() { return targetId; }
        public void setTargetId(Long targetId) { this.targetId = targetId; }

        public String getTargetTitle() { return targetTitle; }
        public void setTargetTitle(String targetTitle) { this.targetTitle = targetTitle; }

        public long getAttributedOrders() { return attributedOrders; }
        public void setAttributedOrders(long attributedOrders) { this.attributedOrders = attributedOrders; }

        public long getPaidOrders() { return paidOrders; }
        public void setPaidOrders(long paidOrders) { this.paidOrders = paidOrders; }

        public BigDecimal getPaidAmount() { return paidAmount; }
        public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

        public long getRefundedOrders() { return refundedOrders; }
        public void setRefundedOrders(long refundedOrders) { this.refundedOrders = refundedOrders; }

        public BigDecimal getRefundedAmount() { return refundedAmount; }
        public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }

        public long getHeldOrders() { return heldOrders; }
        public void setHeldOrders(long heldOrders) { this.heldOrders = heldOrders; }
    }

    /**
     * 单个分配状态桶。金额同时给整数分（真源）与展示金额（分/100），不二次定价。
     * 冲销桶的 {@code reversedFen} 为正的冲销绝对值，{@code netFen} 已扣除冲销。
     * 多币种不换汇合计：金额返回 null，amountUnavailableReason=MULTIPLE_CURRENCIES。
     */
    public static class Bucket {
        private String statusText;
        private long allocationCount;
        private Long accruedFen;
        private Long reversedFen;
        private Long netFen;
        private String currency;
        private BigDecimal accruedAmount;
        private BigDecimal reversedAmount;
        private BigDecimal netAmount;
        private String amountUnavailableReason;

        public String getStatusText() { return statusText; }
        public void setStatusText(String statusText) { this.statusText = statusText; }

        public long getAllocationCount() { return allocationCount; }
        public void setAllocationCount(long allocationCount) { this.allocationCount = allocationCount; }

        public Long getAccruedFen() { return accruedFen; }
        public void setAccruedFen(Long accruedFen) { this.accruedFen = accruedFen; }

        public Long getReversedFen() { return reversedFen; }
        public void setReversedFen(Long reversedFen) { this.reversedFen = reversedFen; }

        public Long getNetFen() { return netFen; }
        public void setNetFen(Long netFen) { this.netFen = netFen; }

        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }

        public BigDecimal getAccruedAmount() { return accruedAmount; }
        public void setAccruedAmount(BigDecimal accruedAmount) { this.accruedAmount = accruedAmount; }

        public BigDecimal getReversedAmount() { return reversedAmount; }
        public void setReversedAmount(BigDecimal reversedAmount) { this.reversedAmount = reversedAmount; }

        public BigDecimal getNetAmount() { return netAmount; }
        public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }

        public String getAmountUnavailableReason() { return amountUnavailableReason; }
        public void setAmountUnavailableReason(String reason) { this.amountUnavailableReason = reason; }
    }

    /**
     * 暂缓：已支付且已归因、但还没有收入分配行的单。
     * 金额不猜（{@code amountFen=null}），原因明确是"尚未应计"。
     */
    public static class Held {
        private long orders;
        private Long amountFen;
        private String reason;

        public long getOrders() { return orders; }
        public void setOrders(long orders) { this.orders = orders; }

        public Long getAmountFen() { return amountFen; }
        public void setAmountFen(Long amountFen) { this.amountFen = amountFen; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    /** 能力声明：只报服务端确实具备的能力。 */
    public static class Capability {
        private boolean available;
        private String reason;
        private String endpoint;

        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    }

    /** 每个数字的来源表，便于客户端/客服追溯。 */
    public static class Provenance {
        private String credentials = "marketing_referral_credential";
        private String deals = "marketing_attribution + cms_registration.payment_status";
        private String revenue = "marketing_revenue_allocation_line (issued role=referrer)";
        private String held = "paid attributed orders with no marketing_revenue_allocation row";

        public String getCredentials() { return credentials; }
        public void setCredentials(String credentials) { this.credentials = credentials; }

        public String getDeals() { return deals; }
        public void setDeals(String deals) { this.deals = deals; }

        public String getRevenue() { return revenue; }
        public void setRevenue(String revenue) { this.revenue = revenue; }

        public String getHeld() { return held; }
        public void setHeld(String held) { this.held = held; }
    }
}

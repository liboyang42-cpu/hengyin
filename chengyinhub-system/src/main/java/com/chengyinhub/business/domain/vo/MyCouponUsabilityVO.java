package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 玩家【自己已持有】优惠券的可用性投影。只回答"我这张券现在能不能用、为什么不能用"。
 *
 * <p>⚠️ 刻意不含的字段(别顺手加回来):
 * <ul>
 *   <li>{@code memberId} —— 调用方就是持有人,回传别人的/自己的 ID 都没有用处;</li>
 *   <li>{@code couponCode} / {@code qrcodeUrl} —— 出示码必须走 /api/coupon/qr-token 的
 *       动态签名,静态码等于把券白送;</li>
 *   <li>发行量 / 领取量 / 核销量 —— 商家经营数据,不属于玩家。</li>
 * </ul>
 *
 * <p>判定顺序与核销服务端闸口一致(已用 → 过期 → 平台失效 → 未开始 → 可用),
 * 但这里**只读不改**:不触发过期状态回写,也不代替核销时的 CAS 与结算。
 */
public class MyCouponUsabilityVO {

    /** 可用 */
    public static final String USABLE = "AVAILABLE";
    /** 已核销(use_status=1) */
    public static final String USED = "USED";
    /** 已过期(use_status=2 或超过领取记录的 end_time) */
    public static final String EXPIRED = "EXPIRED";
    /** 平台手动失效(券 status=3);商家停发(4)不影响已领的券 */
    public static final String DISABLED = "DISABLED";
    /** 未到生效时间 */
    public static final String NOT_STARTED = "NOT_STARTED";
    /** 券模板已不存在/被删除:核销会返回 NOT_FOUND */
    public static final String COUPON_MISSING = "COUPON_MISSING";

    private Long historyId;
    private Long couponId;
    private String name;
    private Integer couponType;
    /** 金额 / 折扣系数,语义随 couponType */
    private BigDecimal amount;
    /**
     * 券说明(商家填写的使用条款文案)。
     *
     * <p>⚠️ 刻意不下发 min_point「满X可用」:该展示已被拍板删除(见
     * {@code PublicDetailVoBoundaryTest} 对 PublicCouponSummaryVO 的断言),持券人需要的是
     * "能不能用/为什么不能用",门槛由商家核销时按券条款处理。需要补充时先改拍板,不要在这里加回来。
     */
    private String description;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;
    /** 0 平台券 / 1 商家券 */
    private Integer scopeType;
    /** 仅商家券有值:持券人去核销的商家 */
    private Long merchantId;
    /** 领取记录原始状态:0 未使用 / 1 已使用 / 2 已过期 */
    private Integer useStatus;
    private boolean usable;
    /** {@link #USABLE} 及上面的终态之一 */
    private String reason;

    public Long getHistoryId() { return historyId; }
    public void setHistoryId(Long historyId) { this.historyId = historyId; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long couponId) { this.couponId = couponId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getCouponType() { return couponType; }
    public void setCouponType(Integer couponType) { this.couponType = couponType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }
    public Date getEndTime() { return endTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }
    public Integer getScopeType() { return scopeType; }
    public void setScopeType(Integer scopeType) { this.scopeType = scopeType; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Integer getUseStatus() { return useStatus; }
    public void setUseStatus(Integer useStatus) { this.useStatus = useStatus; }
    public boolean isUsable() { return usable; }
    public void setUsable(boolean usable) { this.usable = usable; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

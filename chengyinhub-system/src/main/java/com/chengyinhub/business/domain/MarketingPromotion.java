package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 触发式优惠:完局后的限时优惠 / 首关完成后的有效期优惠。表 marketing_promotion。
 *
 * <p><b>资格只认服务端事实,不认客户端信号</b>:
 * <ul>
 *   <li>{@code triggerKind=1}(完局):以 {@code player_badge} 里
 *       {@code badge_code = TOPIC_FINISH_<topicId>} 的真实通关事实为准,
 *       窗口从该事实的 {@code obtain_time} 起算。<b>不是</b>客户端点"完成"按钮。</li>
 *   <li>{@code triggerKind=2}(首关):以该会员最早一条通关事实为准。</li>
 * </ul>
 * 窗口时长 {@code windowHours} 是<b>每一条配置自己的值</b>,DDL 无默认值、
 * 校验要求 &gt; 0 —— 竞品样本里的 48h/30d 不会被写成城瘾默认政策。
 *
 * <p><b>叠加与互斥是显式配置</b>:同一 {@code stackGroup} 只取一个(按 priority 降序、id 升序);
 * {@code exclusive=1} 独占 —— 它生效后其余一律不参与,并在报价里逐条给出被排除原因。
 */
public class MarketingPromotion extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 完局后限时优惠:窗口从"本主题通关事实"起算。 */
    public static final int TRIGGER_TOPIC_COMPLETION = 1;
    /** 首关完成后的有效期优惠:窗口从会员最早通关事实起算。 */
    public static final int TRIGGER_FIRST_QUEST = 2;

    /** 适用范围:0=全局(任何报价) 1=主题 2=活动。 */
    public static final int SCOPE_ALL = 0;
    public static final int SCOPE_TOPIC = 1;
    public static final int SCOPE_ACTIVITY = 2;

    /** 固定减免(元)。 */
    public static final int DISCOUNT_FIXED_AMOUNT = 1;
    /** 折扣率:discountValue 是"减掉的比例"(0.15 = 减 15%),不是"折后比例"。 */
    public static final int DISCOUNT_RATE = 2;

    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ENABLED = 1;

    private Long id;
    private String promoCode;
    private Integer triggerKind;
    private String name;
    private String description;
    private Integer scopeType;
    private Long scopeId;
    private Integer discountType;
    /** 固定金额(元)或折扣率(0&lt;r≤1);含义由 discountType 决定,不做隐式换算。 */
    private BigDecimal discountValue;
    /** 比率折扣的封顶金额;固定金额折扣忽略本列。 */
    private BigDecimal maxDiscountAmount;
    /** 触发事实之后多少小时内有效;必须显式配置。 */
    private Integer windowHours;
    /** 折前金额门槛;null = 无门槛。 */
    private BigDecimal minPayableAmount;
    /** 1 = 独占:它生效后其余促销全部不参与。 */
    private Integer exclusiveFlag;
    /** 同组只取一个。 */
    private String stackGroup;
    /** 越大越先生效。 */
    private Integer priority;
    private Integer maxUsesPerMember;
    private Integer status;
    private Integer ruleVersion;
    private Date validFrom;
    private Date validTo;
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPromoCode() { return promoCode; }
    public void setPromoCode(String promoCode) { this.promoCode = promoCode; }

    public Integer getTriggerKind() { return triggerKind; }
    public void setTriggerKind(Integer triggerKind) { this.triggerKind = triggerKind; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getScopeType() { return scopeType; }
    public void setScopeType(Integer scopeType) { this.scopeType = scopeType; }

    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }

    public Integer getDiscountType() { return discountType; }
    public void setDiscountType(Integer discountType) { this.discountType = discountType; }

    public BigDecimal getDiscountValue() { return discountValue; }
    public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }

    public BigDecimal getMaxDiscountAmount() { return maxDiscountAmount; }
    public void setMaxDiscountAmount(BigDecimal maxDiscountAmount) { this.maxDiscountAmount = maxDiscountAmount; }

    public Integer getWindowHours() { return windowHours; }
    public void setWindowHours(Integer windowHours) { this.windowHours = windowHours; }

    public BigDecimal getMinPayableAmount() { return minPayableAmount; }
    public void setMinPayableAmount(BigDecimal minPayableAmount) { this.minPayableAmount = minPayableAmount; }

    public Integer getExclusiveFlag() { return exclusiveFlag; }
    public void setExclusiveFlag(Integer exclusiveFlag) { this.exclusiveFlag = exclusiveFlag; }

    public String getStackGroup() { return stackGroup; }
    public void setStackGroup(String stackGroup) { this.stackGroup = stackGroup; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Integer getMaxUsesPerMember() { return maxUsesPerMember; }
    public void setMaxUsesPerMember(Integer maxUsesPerMember) { this.maxUsesPerMember = maxUsesPerMember; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }

    public Date getValidFrom() { return validFrom; }
    public void setValidFrom(Date validFrom) { this.validFrom = validFrom; }

    public Date getValidTo() { return validTo; }
    public void setValidTo(Date validTo) { this.validTo = validTo; }

    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}

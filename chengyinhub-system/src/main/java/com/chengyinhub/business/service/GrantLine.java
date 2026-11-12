package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 一行 = 一个覆盖主题 = 一行 {@code cms_registration}。
 *
 * <p>{@code payableAmount} 是**真实分摊应付**(root 纠偏:不得清零)。
 * 支付渠道总额与支付事实只由首行持有,所以"按 payable 汇总"和"按 wechat_payment_amount 汇总"各自守恒。
 */
public final class GrantLine {

    /** 首次使用起算(激活才落行,见 {@link IMarketingProductFulfillmentService#activateOnFirstUse})。 */
    public static final int VALIDITY_ACTIVATE_ON_FIRST_USE = -1;
    /** 永久(无到期)。此时不得填 expires_at=NULL 冒充,必须显式声明本档。 */
    public static final int VALIDITY_NONE = 0;
    /** 购买起 N 日。 */
    public static final int VALIDITY_RELATIVE_DAYS = 1;
    /** 固定起止窗口。 */
    public static final int VALIDITY_FIXED_WINDOW = 2;

    public long topicId;

    /** 该主题分买票面(元)。 */
    public BigDecimal listAmount;

    /** 真实分摊应付(元),Σ = 商品实付。 */
    public BigDecimal payableAmount;

    /** {@link #VALIDITY_ACTIVATE_ON_FIRST_USE} / {@link #VALIDITY_NONE} / {@link #VALIDITY_RELATIVE_DAYS} / {@link #VALIDITY_FIXED_WINDOW}。 */
    public int validityKind;
    public int validDays;
    public Date fixedValidFrom;
    public Date fixedValidTo;

    /**
     * true = 该行是 ③ 探索票:走既有 {@code perkLedgerService.reserveEntitlements} 占章节容量。
     * 普通 pass 恒 false —— {@code registration_entitlement} 是章节容量,不是可玩授权(root 已纠正语义)。
     */
    public boolean exploreCapacity;

    // ⚠️ 刻意没有 refundPolicyCode/Version 字段:
    // 退款政策只有 RefundPolicy 一个真源。本服务**不自造**政策标识,也不接受调用方传入"自定义退期" ——
    // 能被执行的是 RefundPolicy.snapshotOnPurchase 已表达的档位(当前只有 ③ 探索票);
    // 表达不了的 SKU 一律不可售(抛精确缺口),绝不因缺锚点取消用户退款权、也不落 NULL 让读侧现取。
}

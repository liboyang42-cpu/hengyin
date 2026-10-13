package com.chengyinhub.common.constant;

/**
 * {@code registration_entitlement.invalid_reason} 的取值(P0-15)。
 *
 * <p><b>为什么常量集合在这里而不在被调方</b>:{@code IPerkLedgerService.releaseOnRefund} 的
 * {@code reason} 是裸 String,**既无枚举校验也无 null 校验** —— 被调方只是原样写进
 * {@code invalid_reason} 列。取值集合只活在它的 javadoc 里,是个君子协定。
 * 而调用方有 5 处(玩家自退 / 整单退从单 / 后台审核退 / 主动取消未支付 / 弃单关单),
 * 散着写字面量 = 口径二源,拼错了不报错、只是账上多一种没人认识的 reason。
 *
 * <p>取值与 {@code IPerkLedgerService.releaseOnRefund} 的 javadoc 声明一致。
 */
public final class EntitlementInvalidReason {
    private EntitlementInvalidReason() {}

    /** 退款(含玩家自退、主理人退、后台运营审核退)。 */
    public static final String REFUND = "REFUND";

    /** 主办方取消活动 / 玩家取消未支付单 / 弃单关单 —— 票根本没成立,权益随之作废。 */
    public static final String ORGANIZER_CANCEL = "ORGANIZER_CANCEL";

    /** 缺口事件:平台兑现不了承诺的权益,主动作废并整单退。 */
    public static final String FULFILLMENT_GAP = "FULFILLMENT_GAP";

    /** 人工处置。 */
    public static final String MANUAL = "MANUAL";
}

package com.chengyinhub.common.constant;

/**
 * P0-06 退款政策标识 —— 下单时快照进 {@code cms_registration.refund_policy_code}。
 *
 * <p><b>为什么要有 code 而不是只存 deadline</b>:政策会变。加档位、改参数、按产品分流,都靠加新 code
 * 或升 version 来表达,**旧单认旧 code、永不追溯**。只存一个 deadline 的话,
 * 「这单当初按哪套规则卖的」就永远说不清了。
 *
 * <p><b>只有 ③ 一个码</b>(施工图 §4 必清栏原话:「P0-06 只需 ③ 的档位」)。①/② 的码等它们开闸再加 ——
 * 提前写占位常量只会让后来者 grep 到之后以为已经实现了,照着它接线,得到一个永远不会被写进任何一行的值。
 */
public final class RefundPolicyCode {
    private RefundPolicyCode() {}

    /**
     * ③ 自由探索票:锚 {@code oms_ticket.end_time},截止前且未核销 100% / 首核销转人工 / 截止后 0%。
     * <p><b>两档,没有 30%</b> —— 30% 是 ① 专属,而 ① 不在首轮(D12:首轮 = ③-自营)。
     */
    public static final String EXPLORE_END_100_0 = "EXPLORE_END_100_0";

    /**
     * 通行证三档(业主 2026-11-03:与场次票统一):② 自玩通行证与 ③ 自由探索票,锚 = 下单时冻结的
     * 履约窗开始({@code refund_deadline} 存的就是这个开始时刻)。开始前 24 小时以上 100%,
     * 24 小时内 50%,开始后 0。积分在 50% 时仍全额退回。
     * <p>只给 2026-11-03 之后的新单;存量单认自己快照里的旧码(③ = {@link #EXPLORE_END_100_0}),
     * ② 存量单无快照,读侧仍按旧口径「开始前全额退」,不追溯收回已答应的退款。
     */
    public static final String START_24H_100_50_0 = "START_24H_100_50_0";

    /**
     * 当前政策版本。同一个 code 的**参数**调整(比如 48h 改 72h、100/30 改 100/50)靠升它区分,
     * 不用新开 code。⚠️ 升版只影响之后的新单,存量单认自己快照里的那个版本。
     */
    public static final int CURRENT_VERSION = 1;
}

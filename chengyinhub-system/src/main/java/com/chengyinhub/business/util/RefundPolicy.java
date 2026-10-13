package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.service.support.FulfillmentWindowResolver;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.constant.RefundPolicyCode;

import java.util.Date;

/**
 * 退款可退判定(整改报告 2026-06-30 口径):全额退款,按业务类型判定可退窗口。
 * <ul>
 *   <li>经典定向(主题 ownerType=1 且 票 mode=1):集合时间前可退,错过集合时间不可退。</li>
 *   <li>自由定向(主题 ownerType=1 且 票 mode=2):主题有效期前可退,错过有效期不可退。</li>
 *   <li>活动/场次(ownerType=2):**集合时间前 24 小时可全额退,之后不退**(产品拍板 2026-09-15,5.9)。
 *       集合时间真源 = {@code cms_activity.start_date}(承接方 edit-ops 改的就是这一列);
 *       缺锚点 ⇒ fail-closed 转人工,不自动退。</li>
 *   <li>已核销 / 未支付:一律不可退。</li>
 * </ul>
 * 本类为纯判定逻辑,不涉及资金流(资金流见 TeamGroupServiceImpl.refundOne:微信原路退+失败兜底余额)。
 * 调用方负责把各业务的"截止时间"字段映射到对应入参(如活动 startTime 为 String,调用方先解析为 Date)。
 */
public final class RefundPolicy {

    private RefundPolicy() {
    }

    /** 场次票(ownerType=2)的退款窗口:集合时间前 24 小时(5.9 统一口径,唯一一处定义)。 */
    public static final long SESSION_REFUND_WINDOW_MS = 24L * 60 * 60 * 1000;

    /**
     * 场次票退款规则文案(产品拍板 2026-09-15,5.9) —— 购买页票种行与订单页退款卡共用。
     * 只给带队场次票/场次单用;自玩通行证与自由探索票(快照钟)不引用。
     */
    public static final String SESSION_REFUND_RULE_TEXT = "集合前 24 小时可全额退;之后或未到场不退";

    /** 退款判定结果。 */
    public static final class Verdict {
        /** 是否可退(**自动**退)。 */
        public final boolean refundable;
        /** 说明:可退说明或不可退原因(面向用户)。 */
        public final String reason;
        /** 可退截止点(到此为止可退);null=无法确定截止点。 */
        public final Date deadline;
        /**
         * P0-05:是否需转人工。
         *
         * <p>与 {@link #refundable} 的关系:refundable=false 有两种,别混:
         * <ul>
         *   <li>manualReview=false —— **规则上就不该退**(未支付/已核销/已错过窗口)。终局,不用人管。</li>
         *   <li>manualReview=true —— **算不出来**(截止时间缺失)。不是不该退,是系统没资格自己决定。</li>
         * </ul>
         */
        public final boolean manualReview;

        public Verdict(boolean refundable, String reason, Date deadline) {
            this(refundable, reason, deadline, false);
        }

        public Verdict(boolean refundable, String reason, Date deadline, boolean manualReview) {
            this.refundable = refundable;
            this.reason = reason;
            this.deadline = deadline;
            this.manualReview = manualReview;
        }
    }

    /** P0-06 下单时冻结的政策快照,落进 cms_registration 的三列。 */
    public static final class Snapshot {
        /** 政策标识,见 {@link RefundPolicyCode}。 */
        public final String code;
        /** 政策版本。 */
        public final Integer version;
        /** 可退截止点(③ = 票的 end_time)。 */
        public final Date deadline;

        public Snapshot(String code, Integer version, Date deadline) {
            this.code = code;
            this.version = version;
            this.deadline = deadline;
        }
    }

    /**
     * P0-06 下单时的政策快照 —— **玩家买的时候答应他什么,就必须一直是什么**。
     *
     * <p><b>为什么必须冻</b>:evaluate 现在每次现取票的 end_time 算截止点,而 oms_ticket 卖了票之后
     * 还能被后台改。运营把 end_time 往前挪两天,所有**已售**订单的退款窗口就被追溯砍掉两天 ——
     * 玩家买的时候看到的是「7-20 前可退」,退的时候变成「已错过有效期」,而且零告警。
     * 这与 P0-28 的费率快照是同一个病根:**成交时固化的是结果,判定时却现取输入**。
     *
     * <p><b>只落 ③</b>(施工图 §4 原话:「P0-06 只需 ③ 的档位」)。①/② 返回 null = 不打快照,
     * 读侧回落现取 —— 与今天行为完全一致。等它们开闸再落,别提前造一份没人验证过的档位。
     *
     * @param purchaseKind  订单购买类型({@link PurchaseKind});null(后台手工建单/独立活动)⇒ 不快照
     * @param ticketEndTime ③ 的锚点源:票的有效期截止
     * @return 快照;**null = 不打快照**(非 ③,或锚点源本身就缺失 —— 缺失时留空比冻结一个
     *         凭空捏造的截止点强,读侧会走 P0-05 的「算不出来 ⇒ 转人工」)
     */
    public static Snapshot snapshotOnPurchase(Integer purchaseKind, Date ticketEndTime) {
        if (purchaseKind == null || purchaseKind != PurchaseKind.EXPLORE_PASS || ticketEndTime == null) {
            return null;
        }
        return new Snapshot(RefundPolicyCode.EXPLORE_END_100_0, RefundPolicyCode.CURRENT_VERSION, ticketEndTime);
    }

    /**
     * <b>[P0-06] 「已核销订单怎么处置」的唯一决策点</b> —— 退款三个入口的前置闸 + {@link #evaluate}
     * 全部问这一个方法,别再各写各的。
     *
     * <p><b>为什么必须抽出来</b>:退款入口在 evaluate 之前各自硬编码了一句
     * {@code if (已核销) throw "已核销报名不可退款"},于是 evaluate 里那套判定**根本走不到** ——
     * 政策写在 RefundPolicy 里,真正生效的却是三个入口各自的 if。结果就是改了政策没人知道、
     * 展示路径(走 evaluate)与执行路径(走硬闸)各说各话:页面说「需人工处理」,点下去说「不可退款」。
     *
     * <p><b>③ 首核销转人工,其余硬拒</b>(施工图 §4 只授权了 ③ 的档位):③ 的「已核销」只证明
     * 「码被扫了」,不证明「玩家拿到了他买的东西」—— 店员误扫、商家没兑现、权益缺口都是核销后仍该退的
     * 真实场景。而 ①/活动 的已核销 = 领队真把人带出去了 / 人真去参加了,那是终局,硬拒是对的。
     * 存量单(purchaseKind 为 null)推不出类型 ⇒ 走原口径,别拿它赌。
     *
     * <p>⚠️ 「转人工」的兜底仍然不完整,别当成已存在:{@link Verdict#manualReview} 只有**一处**
     * 生产读方({@code RefundServiceImpl#ensureRefundable} 的运营覆盖路,只覆盖这里 ③ 已核销这一档);
     * 截止时间/履约窗缺失那一档({@link #evaluate} / {@link #evaluateTopicFulfillmentWindow})
     * **没有真队列**。2026-09-16:该档做到「不静默」—— 文案写明找客服(下游读侧另打
     * {@code [退款转人工]} 可查日志),真队列见修复交接材料 T0 方案,未拍板前别在代码里假造一个。
     *
     * @param purchaseKind       订单购买类型({@link PurchaseKind});null ⇒ 走原口径
     * @param verificationStatus 核销状态(1=已核销)
     * @return <b>null = 未核销,放行</b>;非 null = 不自动退,{@code reason} 即面向用户的话
     */
    public static Verdict verifiedVerdict(Integer purchaseKind, Integer verificationStatus) {
        if (verificationStatus == null || verificationStatus != 1) {
            return null;
        }
        if (purchaseKind != null && purchaseKind == PurchaseKind.EXPLORE_PASS) {
            return new Verdict(false, "已核销订单需人工核实,请联系客服", null, true);
        }
        return new Verdict(false, "已核销报名不可退款", null);
    }

    /**
     * @param ownerType          归属类型 1=主题 2=活动
     * @param paymentStatus      报名支付状态(2=已支付)
     * @param verificationStatus 核销状态(1=已核销)
     * @param ticketMode         票模式 1=经典定向 2=自由定向(仅主题用)
     * @param ticketStartTime    票集合时间(经典定向用)
     * @param ticketEndTime      票有效期截止(自由定向用)
     * @param activityStartTime  活动开始时间(活动用)
     * @param now                当前时间
     * @deprecated P0-06:该签名**不读快照**,等价于每次现取 —— 有 registration 在手的调用方一律改用
     *             {@link #evaluate(Long, Integer, Integer, Integer, Date, Date, Date, Integer, Date, Date)}
     *             并传 {@code registration.getPurchaseKind()} 与 {@code registration.getRefundDeadline()}。
     *             留着仅为不带快照上下文的纯算场景。
     */
    @Deprecated
    public static Verdict evaluate(Long ownerType, Integer paymentStatus, Integer verificationStatus,
                                   Integer ticketMode, Date ticketStartTime, Date ticketEndTime,
                                   Date activityStartTime, Date now) {
        return evaluate(ownerType, paymentStatus, verificationStatus, ticketMode, ticketStartTime,
                ticketEndTime, activityStartTime, null, null, now);
    }

    /**
     * P0-06:**快照优先**的退款判定。
     *
     * @param purchaseKind     订单购买类型({@link PurchaseKind})。**只用来圈定 ③ 的档位** ——
     *                         施工图 §4 明写「P0-06 只需 ③ 的档位」,①/活动 的档位不在本轮授权内,
     *                         行为必须原样不动。null(存量单/后台手工建单)⇒ 一律走原有口径。
     * @param snapshotDeadline 下单时冻结的截止点({@code cms_registration.refund_deadline})。
     *                         非空即胜出,后面那些现取的时间字段一律不看 —— 这正是「别再算了」:
     *                         判定不该重算一个成交时已经定死的数。
     *                         null = 存量单 / 未打快照 ⇒ 回落现取(与快照落地前一致)。
     * @param now              当前时间
     */
    public static Verdict evaluate(Long ownerType, Integer paymentStatus, Integer verificationStatus,
                                   Integer ticketMode, Date ticketStartTime, Date ticketEndTime,
                                   Date activityStartTime, Integer purchaseKind,
                                   Date snapshotDeadline, Date now) {
        if (paymentStatus == null || paymentStatus != 2) {
            return new Verdict(false, "未支付订单无需退款", null);
        }
        // [P0-06] 已核销的处置委托给唯一决策点 —— 三个退款入口的前置闸问的是同一个方法,
        // 保证「展示怎么说,执行就怎么做」。
        Verdict verified = verifiedVerdict(purchaseKind, verificationStatus);
        if (verified != null) {
            return verified;
        }
        Date deadline = snapshotDeadline != null ? snapshotDeadline
                : resolveDeadline(ownerType, ticketMode, ticketStartTime, ticketEndTime, activityStartTime);
        if (deadline == null) {
            // [P0-05] 从严 + 转人工。
            //
            // 这里原本是「保守判可退,避免错误锁死用户」—— 方向反了。截止时间缺失不是罕见异常,
            // 而是发布器的常态产物(G2:mode=1 票 start_time 可为 null;G3:mode=2 票 end_time 可为 null),
            // 于是「保守」等价于**这些票永远无条件全额可退**,玩完再退也照退。三条「永远可退」里的两条
            // 就是从这一行漏出去的。
            //
            // 算不出截止点时,系统没资格替业务决定退不退 ⇒ 不自动退、转人工。这是「两头堵」的兜底那头;
            // 另一头是发布器保证时间必落库(saveTickets),两头都上了才不会让人工队列被淹。
            return new Verdict(false, "退款截止时间缺失,需人工核实,请联系客服", null, true);
        }
        if (now != null && now.after(deadline)) {
            return new Verdict(false, refusalReason(ownerType, ticketMode, snapshotDeadline != null), deadline);
        }
        return new Verdict(true, "可全额退款,原路退回(预计1-3个工作日)", deadline);
    }

    /**
     * 合同二主题退款入口：直接复用履约真源，避免无票自玩空读 ticket。
     * 退款窗口在履约开始前开放，开始时刻本身属于履约半开区间，故相等即关闭。
     */
    public static Verdict evaluateTopicFulfillmentWindow(Integer paymentStatus, Integer verificationStatus,
                                                         CmsTopic topic, OmsTicket ticket, Date now) {
        if (paymentStatus == null || paymentStatus != 2) {
            return new Verdict(false, "未支付订单无需退款", null);
        }
        Verdict verified = verifiedVerdict(null, verificationStatus);
        if (verified != null) {
            return verified;
        }
        FulfillmentWindowResolver.Window window;
        try {
            window = FulfillmentWindowResolver.resolve(topic, ticket);
        } catch (IllegalStateException e) {
            return new Verdict(false, "退款履约窗缺失,需人工核实,请联系客服", null, true);
        }
        Date deadline = window.getStartInclusive();
        if (now == null || !now.before(deadline)) {
            return new Verdict(false, "已进入履约窗,不可退款", deadline);
        }
        return new Verdict(true, "可全额退款,原路退回(预计1-3个工作日)", deadline);
    }

    /** 按业务类型解析"可退截止点"。 */
    private static Date resolveDeadline(Long ownerType, Integer ticketMode,
                                        Date ticketStartTime, Date ticketEndTime, Date activityStartTime) {
        if (ownerType != null && ownerType == 2L) {
            // 5.9:场次票看**集合时间前 24 小时**(不再用集合时间本身)。集合时间 =
            // cms_activity.start_date;缺锚点返回 null ⇒ 上层 fail-closed 转人工。
            // 唯一的场次钟在这一行 —— 别在任何调用方再减/再猜 24h。
            return sessionRefundDeadline(activityStartTime);
        }
        if (ticketMode != null && ticketMode == 2) {
            return ticketEndTime;                // 自由定向:有效期前可退
        }
        return ticketStartTime;                  // 经典定向(默认):集合时间前可退
    }

    /**
     * 场次集合时间 → 可退截止点(集合时间前 24 小时)。{@code null} 入参原样返回
     * (缺锚点由 {@link #evaluate} 统一转人工)。
     *
     * <p>公开只为下单**前**的报价侧也要看这同一口钟(1-22 附注:开场前 24h 内的 ¥ 单,
     * 确认弹层必须写明「本单不可退」)。除 {@link #resolveDeadline} 外,这是唯一合法读方;
     * 调用方拿到截止点后只许比较,不许再减/再猜 24h。
     */
    public static Date sessionRefundDeadline(Date assemblyTime) {
        return assemblyTime == null ? null : new Date(assemblyTime.getTime() - SESSION_REFUND_WINDOW_MS);
    }

    /**
     * 错过窗口的不可退原因(面向用户)。
     *
     * @param fromSnapshot 截止点来自下单快照(③ 的票面有效期)时给通用口径 ——
     *                     那一档的钟不是集合时间,别把场次票的话术安到它头上。
     */
    private static String refusalReason(Long ownerType, Integer ticketMode, boolean fromSnapshot) {
        if (fromSnapshot) {
            return "已错过退款截止时间,不可退款";
        }
        if (ownerType != null && ownerType == 2L) {
            return "已错过集合前24小时,不可退款";
        }
        if (ticketMode != null && ticketMode == 2) {
            return "已错过主题有效期,不可退款";
        }
        return "已错过集合时间,不可退款";
    }

    /**
     * 带队场次票(ownerType=2 且非自由探索 mode=2)判据 ——
     * 购买页文案覆写与订单页规则句共用这一处,别在视图/调用方各写一份。
     */
    public static boolean isGuidedSessionTicket(Long ownerType, Integer ticketMode) {
        return ownerType != null && ownerType == 2L && (ticketMode == null || ticketMode != 2);
    }

    /**
     * 场次单(订单页口径)= ownerType=2 且不是 ③ 自由探索票(快照钟)。
     * {@code purchaseKind} 为 null 的存量单按场次票处理 —— 它们的钟也是这次统一的这口。
     */
    public static boolean isGuidedSessionOrder(Long ownerType, Integer purchaseKind) {
        return ownerType != null && ownerType == 2L
                && (purchaseKind == null || purchaseKind != PurchaseKind.EXPLORE_PASS);
    }
}

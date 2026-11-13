package com.chengyinhub.business.platformpass;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.platformpass.mapper.SelfPlayXpayMapper;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.business.service.impl.AlarmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台自有主题自玩票的小程序虚拟支付（XPAY）。复用通行证的签名/配置/网关（{@link XpaySigner}、
 * {@link PlatformPassProperties.Xpay}、{@link XpayGateway}），不另立支付体系。
 *
 * <ul>
 *   <li>路由由服务端决定（{@link #routeFor}），客户端不能强制 JSAPI；首次签发时在 cms_registration_xpay 冻结商品/环境/金额。</li>
 *   <li>只有向微信回查到「已付且 paid_fee/order_fee 等于冻结价」才开通；推送与前端 confirm 都只是触发回查。</li>
 *   <li>发货确认可补偿：开通后 delivery_confirmed=0 由对账任务补报 notify_provide_goods。</li>
 *   <li>渠道发起的退款：回查到已退款即关闭报名、停止游玩；重复/迟到通知幂等。</li>
 *   <li>开关关闭时 {@link #routeFor} 对没有冻结行的单恒为 JSAPI，行为与现状一致。</li>
 * </ul>
 */
@Service
public class SelfPlayXpayService {
    private static final Logger log = LoggerFactory.getLogger(SelfPlayXpayService.class);
    public static final int SIGNED = 0, PAID = 1, REFUNDING = 2, REFUNDED = 3, PAID_CLOSED = 4, UNPAID_RELEASED = 5;

    public enum Route { JSAPI, XPAY }

    public enum RefundOutcome { ACCEPTED, DONE, REJECTED, UNKNOWN }

    private final SelfPlayXpayMapper mapper;
    private final SelfPlayXpayProperties props;
    private final PlatformPassProperties passProps;
    @Autowired(required = false) private XpayGateway xpay;
    @Autowired(required = false) private IWeChatMiniSessionService miniSessions;
    @Autowired(required = false) private UmsMemberMapper memberMapper;
    @Autowired(required = false) private IProductRoleService roles;
    @Autowired(required = false) @Lazy private ICmsRegistrationService registrations;
    @Autowired(required = false) @Lazy private IRefundService refunds;
    @Autowired(required = false) private AlarmService alarm;

    private long reconcileCursor;

    public SelfPlayXpayService(SelfPlayXpayMapper mapper, SelfPlayXpayProperties props,
                               PlatformPassProperties passProps) {
        this.mapper = mapper;
        this.props = props;
        this.passProps = passProps;
    }

    // ------------------------------------------------------------------ 路由

    /** 开关开且支付配置齐（OfferID/AppKey/env/网关/小程序会话）。 */
    public boolean isReady() {
        return props.isEnabled() && canSign();
    }

    private boolean canSign() {
        return passProps.getXpay().isReady() && xpay != null && miniSessions != null;
    }

    /** 该单是否已有虚拟支付冻结行（旧 JSAPI 单恒为 false）。 */
    public boolean owns(Long registrationId) {
        return registrationId != null && rowOf(registrationId) != null;
    }

    /**
     * 读冻结行。开关关闭且表尚未迁移时按「没有冻结行」处理，保证未配置的环境行为与现状逐字一致；
     * 开关开启时表缺失是部署错误，照常抛出而不是静默当成 JSAPI。
     */
    private Map<String, Object> rowOf(long registrationId) {
        try {
            return mapper.selectByRegistrationId(registrationId);
        } catch (RuntimeException e) {
            if (props.isEnabled()) throw e;
            return null;
        }
    }

    /**
     * 服务端路由。已有冻结行 → XPAY（冻结永不被开关/配置改写）；
     * 否则仅当开关开、平台自有主题、自玩通行证、金额有对应道具、且尚未发起过任何 JSAPI 尝试 → XPAY；其余一律 JSAPI。
     */
    public Route routeFor(CmsRegistration reg) {
        if (reg == null || reg.getId() == null) return Route.JSAPI;
        if (rowOf(reg.getId()) != null) return Route.XPAY;
        return eligibleForNew(reg) ? Route.XPAY : Route.JSAPI;
    }

    boolean eligibleForNew(CmsRegistration reg) {
        if (!isReady() || roles == null) return false;
        if (reg.getPurchaseKind() == null || reg.getPurchaseKind() != PurchaseKind.SELF_PLAY_PASS) return false;
        if (!Long.valueOf(1L).equals(reg.getOwnerType()) || reg.getTopicId() == null) return false;
        if (!Integer.valueOf(1).equals(reg.getRegistrationStatus())) return false;
        // 已发起过 JSAPI 尝试（payment_status != 0）的旧单继续 JSAPI。
        if (reg.getPaymentStatus() != null && reg.getPaymentStatus() != 0) return false;
        Long fen = fen(reg);
        if (fen == null || props.goodsIdFor(fen) == null) return false;
        return roles.isPlatformOwned(reg.getTopicId());
    }

    // ------------------------------------------------------------------ 签发

    /** 返回 {payChannel:'XPAY', virtualPayment:{mode,signData,paySig,signature}}。首次签发时冻结商品/环境/金额。 */
    public Map<String, Object> prepare(long memberId, CmsRegistration reg, String jsCode) {
        if (reg == null || !Long.valueOf(memberId).equals(reg.getMemberId())) {
            throw new IllegalStateException("报名不存在或无权操作");
        }
        if (!canSign()) throw new SelfPlayXpayException("XPAY_NOT_CONFIGURED", "支付暂不可用");
        // 先校验 jsCode,再冻结:没有真正签发过参数就不写冻结行。
        if (jsCode == null || jsCode.isBlank()) throw new SelfPlayXpayException("JS_CODE_REQUIRED", "请重新登录后再试");
        Map<String, Object> row = mapper.selectByRegistrationId(reg.getId());
        if (row == null) {
            if (!eligibleForNew(reg)) throw new SelfPlayXpayException("XPAY_NOT_ELIGIBLE", "支付暂不可用");
            Long fen = fen(reg);
            mapper.insertFrozen(reg.getId(), reg.getRegistrationNo(), memberId, props.goodsIdFor(fen),
                    passProps.getXpay().getOfferId(), passProps.getXpay().getEnv(), fen);
            row = mapper.selectByRegistrationId(reg.getId());
        }
        if (row == null || num(row.get("state")) != SIGNED) {
            throw new SelfPlayXpayException("ORDER_NOT_PAYABLE", "订单当前不可支付");
        }
        if (!Integer.valueOf(1).equals(reg.getRegistrationStatus())) {
            throw new SelfPlayXpayException("ORDER_NOT_PAYABLE", "订单当前不可支付");
        }
        if (num(row.get("xpay_env")) != passProps.getXpay().getEnv()) {
            throw new SelfPlayXpayException("XPAY_ENV_CHANGED", "支付环境已变更，请重新下单");
        }
        if (jsCode == null || jsCode.isBlank()) throw new SelfPlayXpayException("JS_CODE_REQUIRED", "请重新登录后再试");
        String openid = openId(memberId);
        IWeChatMiniSessionService.WeChatMiniSession session = miniSessions.exchange(jsCode.trim());
        if (session == null || !openid.equals(session.getOpenId())) {
            throw new SelfPlayXpayException("SESSION_MISMATCH", "请重新登录后再试");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("payChannel", "XPAY");
        out.put("virtualPayment", PlatformPassService.xpayClientParams(passProps.getXpay(),
                (String) row.get("offer_id"), num(row.get("xpay_env")).intValue(),
                (String) row.get("product_id"), num(row.get("price_fen")), reg.getRegistrationNo(),
                session.getSessionKey()));
        return out;
    }

    // ------------------------------------------------------------------ 确认（前端 / 推送 / 对账共用）

    /** 前端 requestVirtualPayment 之后调用：仅回查，不信客户端结果。返回当前状态视图。 */
    public Map<String, Object> confirm(long memberId, long registrationId) {
        CmsRegistration reg = registrations.selectCmsRegistrationById(registrationId);
        if (reg == null || !Long.valueOf(memberId).equals(reg.getMemberId())) {
            throw new IllegalStateException("报名不存在或无权操作");
        }
        Map<String, Object> row = mapper.selectByRegistrationId(registrationId);
        if (row == null) throw new SelfPlayXpayException("CHANNEL_MISMATCH", "订单支付方式不符");
        if (!canSign()) throw new SelfPlayXpayException("XPAY_NOT_CONFIGURED", "支付暂不可用");
        confirmInternal(row, false);
        return view(registrationId);
    }

    public Map<String, Object> view(long registrationId) {
        CmsRegistration reg = registrations.selectCmsRegistrationById(registrationId);
        Map<String, Object> row = mapper.selectByRegistrationId(registrationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("registrationId", registrationId);
        out.put("payChannel", "XPAY");
        out.put("paymentStatus", reg == null ? null : reg.getPaymentStatus());
        out.put("registrationStatus", reg == null ? null : reg.getRegistrationStatus());
        out.put("xpayState", row == null ? null : row.get("state"));
        return out;
    }

    /** 回查并开通。返回 true=已确认为已付（含已开通/已退）；false=尚未付。 */
    boolean confirmInternal(Map<String, Object> row, boolean fromPush) {
        int state = num(row.get("state")).intValue();
        if (state == PAID || state == REFUNDING || state == REFUNDED) {
            if (num(row.get("delivery_confirmed")) == 0) deliver(row, null);
            return true;
        }
        if (state == PAID_CLOSED) return true;
        String no = (String) row.get("registration_no");
        long price = num(row.get("price_fen"));
        int env = num(row.get("xpay_env")).intValue();
        JSONObject response = xpay.queryOrder(openId(num(row.get("member_id"))), no, env);
        JSONObject remote = response == null ? null : response.getJSONObject("order");
        if (response == null || response.getIntValue("errcode", -1) != 0 || remote == null) return false;
        Integer remoteStatus = remote.getInteger("status");
        if (remoteStatus == null || !PlatformPassService.XPAY_PAID.contains(remoteStatus)
                && !PlatformPassService.XPAY_REFUNDED.contains(remoteStatus)) return false;
        if (!no.equals(remote.getString("order_id")) || remote.getLongValue("paid_fee", -1) != price
                || remote.getLongValue("order_fee", -1) != price) {
            warn("selfplay.xpay.fact_conflict", "自玩票虚拟支付事实与冻结价不一致 reg=" + no);
            throw new SelfPlayXpayException("PAYMENT_FACT_CONFLICT", "支付事实与订单不一致");
        }
        String wxOrderId = remote.getString("wx_order_id");
        if (wxOrderId == null || wxOrderId.isBlank()) {
            throw new SelfPlayXpayException("PAYMENT_FACT_CONFLICT", "支付事实缺少微信单号");
        }
        String result = registrations.completeXpayPayment(no, wxOrderId, price);
        if ("SUCCESS".equals(result)) {
            mapper.markPaid(num(row.get("id")), wxOrderId, new Date(), 0);
            if (PlatformPassService.XPAY_REFUNDED.contains(remoteStatus)) {
                // 付款后用户已在渠道侧退掉：开通与退款都落账，随即关闭。
                finalizeRefund(mapper.selectByRegistrationId(num(row.get("registration_id"))));
                return true;
            }
            deliver(mapper.selectByRegistrationId(num(row.get("registration_id"))), remoteStatus);
            return true;
        }
        if ("CLOSED".equals(result)) {
            // 钱已收但订单已关闭/取消：不开通，登记并自动全额退款（可补偿）。
            mapper.markPaidOrphan(num(row.get("id")), wxOrderId, new Date());
            warn("selfplay.xpay.paid_closed", "自玩票虚拟支付已付但报名已关闭 reg=" + no);
            if (!PlatformPassService.XPAY_REFUNDED.contains(remoteStatus)) {
                refundOrphan(mapper.selectByRegistrationId(num(row.get("registration_id"))));
            }
            return true;
        }
        throw new SelfPlayXpayException("PAYMENT_STATE_CONFLICT", "支付状态冲突，请稍后重试");
    }

    /** 补报已发货；remoteStatus 为 null 时重新回查。失败不影响已开通，由对账重试。 */
    private void deliver(Map<String, Object> row, Integer remoteStatus) {
        if (row == null || num(row.get("delivery_confirmed")) == 1) return;
        String no = (String) row.get("registration_no");
        int env = num(row.get("xpay_env")).intValue();
        try {
            Integer status = remoteStatus;
            if (status == null) {
                JSONObject q = xpay.queryOrder(openId(num(row.get("member_id"))), no, env);
                JSONObject remote = q == null ? null : q.getJSONObject("order");
                if (q == null || q.getIntValue("errcode", -1) != 0 || remote == null) return;
                status = remote.getInteger("status");
            }
            if (status == null) return;
            if (status == 2) {
                JSONObject r = xpay.notifyProvideGoods(no, env);
                if (r != null && r.getIntValue("errcode", -1) == 0) mapper.markDelivered(num(row.get("id")));
            } else if (status == 3 || status == 4 || PlatformPassService.XPAY_REFUNDED.contains(status)) {
                mapper.markDelivered(num(row.get("id")));
            }
        } catch (RuntimeException e) {
            log.warn("[自玩票XPAY] 补报发货失败 reg={}", no);
        }
    }

    // ------------------------------------------------------------------ 推送

    /** 虚拟支付推送：只当「去回查」的信号。非自玩票单号返回 false（交给通行证处理）。 */
    public boolean handleXpayPush(JSONObject event) {
        if (event == null || !canSign()) return false;
        String type = event.getString("Event");
        String no = event.getString("OutTradeNo");
        if (no == null || no.startsWith(PlatformPassService.ORDER_PREFIX)) return false;
        Map<String, Object> row = mapper.selectByRegistrationNo(no);
        if (row == null) return false;
        if ("xpay_goods_deliver_notify".equals(type)) return confirmInternal(row, true);
        if ("xpay_refund_notify".equals(type)) return refreshRefund(row);
        return false;
    }

    // ------------------------------------------------------------------ 退款

    /** 回查虚拟支付订单；已退款则关闭报名并停止游玩。重复/迟到调用幂等。 */
    boolean refreshRefund(Map<String, Object> row) {
        int state = num(row.get("state")).intValue();
        if (state == REFUNDED) return true;
        if (state == SIGNED || state == UNPAID_RELEASED) return false;
        JSONObject response = xpay.queryOrder(openId(num(row.get("member_id"))), (String) row.get("registration_no"),
                num(row.get("xpay_env")).intValue());
        JSONObject remote = response == null ? null : response.getJSONObject("order");
        if (response == null || response.getIntValue("errcode", -1) != 0 || remote == null) return false;
        Integer remoteStatus = remote.getInteger("status");
        if (remoteStatus == null || !PlatformPassService.XPAY_REFUNDED.contains(remoteStatus)) return false;
        if (remote.getLongValue("refund_fee", -1) != num(row.get("price_fen"))) {
            warn("selfplay.xpay.partial_refund", "自玩票虚拟支付退款金额与冻结价不一致 reg=" + row.get("registration_no"));
            throw new SelfPlayXpayException("REFUND_FACT_CONFLICT", "退款金额与订单不一致");
        }
        return finalizeRefund(row);
    }

    private boolean finalizeRefund(Map<String, Object> row) {
        if (row == null) return false;
        long regId = num(row.get("registration_id"));
        CmsRegistration reg = registrations.selectCmsRegistrationById(regId);
        boolean live = reg != null && (Integer.valueOf(2).equals(reg.getPaymentStatus())
                || Integer.valueOf(4).equals(reg.getPaymentStatus()));
        if (live && !registrations.applyXpayChannelRefund((String) row.get("registration_no"))) {
            throw new SelfPlayXpayException("REFUND_STATE_CONFLICT", "退款状态冲突，请稍后重试");
        }
        mapper.markRefunded(num(row.get("id")), new Date());
        if (refunds != null) refunds.settleChannelRefundSuccess(regId);
        return true;
    }

    private void refundOrphan(Map<String, Object> row) {
        if (row == null) return;
        long price = num(row.get("price_fen"));
        String no = (String) row.get("registration_no");
        String refundNo = row.get("refund_no") != null ? (String) row.get("refund_no") : "XR" + no;
        try {
            if (mapper.markRefundRequested(num(row.get("id")), refundNo) != 1
                    && num(mapper.selectByRegistrationId(num(row.get("registration_id"))).get("state")) != REFUNDING) {
                return;
            }
            xpay.refundOrder(openId(num(row.get("member_id"))), no, refundNo, price, price, "订单已关闭自动退款",
                    num(row.get("xpay_env")).intValue());
        } catch (RuntimeException e) {
            log.warn("[自玩票XPAY] 已付订单自动退款未确认 reg={}", no); // 保持退款中，由对账按同一退款号重试
        }
    }

    /**
     * 平台发起的退款（后台审核通过后的打款）：整单退到原虚拟支付订单。
     * 退款号固定为 XR+报名号，重复调用幂等；结果未知保持原号，不更换身份重发。
     */
    public RefundOutcome submitRefundForPayout(long registrationId, BigDecimal refundYuan) {
        Map<String, Object> row = mapper.selectByRegistrationId(registrationId);
        if (row == null || !canSign()) return RefundOutcome.UNKNOWN;
        int state = num(row.get("state")).intValue();
        long price = num(row.get("price_fen"));
        if (state == REFUNDED) return RefundOutcome.DONE;
        if (state != PAID && state != REFUNDING && state != PAID_CLOSED) return RefundOutcome.REJECTED;
        long refundFen;
        try {
            refundFen = refundYuan.movePointRight(2).longValueExact();
        } catch (ArithmeticException | NullPointerException e) {
            return RefundOutcome.REJECTED;
        }
        if (refundFen != price) return RefundOutcome.REJECTED; // 仅支持整单退
        String no = (String) row.get("registration_no");
        String refundNo = row.get("refund_no") != null ? (String) row.get("refund_no") : "XR" + no;
        if (mapper.markRefundRequested(num(row.get("id")), refundNo) != 1
                && num(mapper.selectByRegistrationId(registrationId).get("state")) != REFUNDING) {
            return RefundOutcome.UNKNOWN;
        }
        try {
            JSONObject response = xpay.refundOrder(openId(num(row.get("member_id"))), no, refundNo, price, price,
                    "自玩票退款", num(row.get("xpay_env")).intValue());
            if (response != null && response.getIntValue("errcode", -1) == 0) return RefundOutcome.ACCEPTED;
            // 渠道明确拒绝：退回已付并释放退款号，交人工。
            mapper.markRefundRolledBack(num(row.get("id")), refundNo);
            return RefundOutcome.REJECTED;
        } catch (RuntimeException e) {
            return RefundOutcome.UNKNOWN;
        }
    }

    // ------------------------------------------------------------------ 关单围栏

    /**
     * 本地关单（过期/用户取消）前的围栏：虚拟支付单可能正在付，不能凭本地时钟直接关。
     * 返回 true=可以关；false=已付（已开通）或暂无法确认，调用方不得关单。
     */
    public boolean readyToClose(Long registrationId) {
        Map<String, Object> row = registrationId == null ? null : rowOf(registrationId);
        if (row == null) return true;
        int state = num(row.get("state")).intValue();
        if (state == UNPAID_RELEASED) return true;
        if (state != SIGNED) return false;
        if (!canSign()) return false;
        try {
            if (confirmInternal(row, false)) return false;
            // 回查确认尚未付款：放行关单；若稍后才付成，会走「已付但订单已关闭」自动退款。
            JSONObject response = xpay.queryOrder(openId(num(row.get("member_id"))),
                    (String) row.get("registration_no"), num(row.get("xpay_env")).intValue());
            if (response == null || response.getIntValue("errcode", -1) != 0) return false;
            mapper.markUnpaidReleased(num(row.get("id")));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 对账

    @Scheduled(fixedDelayString = "${chengyin.selfplay.xpay.reconcile-delay-ms:60000}",
            initialDelayString = "${chengyin.selfplay.xpay.reconcile-delay-ms:60000}")
    public void reconcile() {
        if (!canSign() || registrations == null) return;
        try {
            reconcileBatch();
        } catch (RuntimeException e) {
            if (props.isEnabled()) throw e; // 未启用且表未迁移：静默
        }
    }

    private void reconcileBatch() {
        Date graceBefore = new Date(System.currentTimeMillis() - props.getCloseGraceMinutes() * 60_000L);
        List<Map<String, Object>> rows = mapper.selectReconcileCandidates(reconcileCursor, graceBefore, 100);
        if (rows.isEmpty()) { reconcileCursor = 0; return; }
        for (Map<String, Object> row : rows) {
            reconcileCursor = num(row.get("id"));
            try {
                reconcileOne(row);
            } catch (RuntimeException e) {
                log.warn("[自玩票XPAY] 对账失败 reg={} type={}", row.get("registration_no"), e.getClass().getSimpleName());
            }
        }
    }

    void reconcileOne(Map<String, Object> row) {
        switch (num(row.get("state")).intValue()) {
            case SIGNED -> readyToClose(num(row.get("registration_id")));
            case PAID -> { if (num(row.get("delivery_confirmed")) == 0) deliver(row, null); }
            case REFUNDING -> refreshRefund(row);
            case PAID_CLOSED -> refundOrphan(row);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ 内部

    private String openId(long memberId) {
        UmsMember member = memberMapper == null ? null : memberMapper.selectUmsMemberById(memberId);
        if (member == null || member.getWxOpenId() == null || member.getWxOpenId().isBlank()) {
            throw new SelfPlayXpayException("OPENID_MISSING", "当前账号缺少微信支付标识");
        }
        return member.getWxOpenId();
    }

    private static Long fen(CmsRegistration reg) {
        BigDecimal amount = reg.getWechatPaymentAmount();
        if (amount == null || amount.signum() <= 0) return null;
        try {
            return amount.setScale(2, java.math.RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private void warn(String key, String message) {
        log.error("[自玩票XPAY] {}", message);
        if (alarm != null) alarm.alarmThrottled(key, "自玩票虚拟支付异常", message);
    }

    private static Long num(Object value) { return PlatformPassService.num(value); }
}

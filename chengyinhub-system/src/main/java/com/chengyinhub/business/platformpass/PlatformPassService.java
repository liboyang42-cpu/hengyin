package com.chengyinhub.business.platformpass;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.platformpass.mapper.PlatformPassMapper;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.business.service.RefundRequestResult;
import com.chengyinhub.business.service.WechatPaymentOperations;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import com.chengyinhub.business.service.support.SaleAvailabilityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 通行证（平台会员）：平台为卖方，年卡 / 月卡，不自动续费。
 *
 * <p>资金规则：
 * <ul>
 *   <li>总开关 {@code platform-pass.enabled} 关闭时不出售、不报 ¥0、不领取；公开主题库照常可读，
 *       既有自玩票报价与下单结果不变。</li>
 *   <li>JSAPI 渠道：只在 C07 已验证支付事实（{@link WechatVerifiedPaymentFactService}）消费成功的同一事务里开通。</li>
 *   <li>虚拟支付渠道：消息推送只当「去查」的信号；开通依据是服务端 /xpay/query_order 回查到的已支付订单，
 *       且订单号、金额必须与本地冻结值一致。</li>
 *   <li>退款：只允许未领取过任何主题的通行证整单退款（领取过 = 已交付）；退款成功后撤销这一段，并把它之后的段前移。</li>
 * </ul>
 * 所有写入口都只用登录态的会员身份，订单号查不到或不属于本人一律「订单不存在」。
 */
@Service
public class PlatformPassService {
    private static final Logger log = LoggerFactory.getLogger(PlatformPassService.class);

    public static final String ORDER_PREFIX = "PPO";
    public static final String REFUND_PREFIX = "RPP";
    public static final String CHANNEL_JSAPI = "JSAPI";
    public static final String CHANNEL_XPAY = "XPAY";
    /** 售出时的退款条款引用；具体条款文案由业主确认（T08）。 */
    public static final String REFUND_TERMS_REF = "PASS-REFUND-V1";
    public static final int PENDING = 0, PAID = 1, CANCELLED = 2, REFUNDED = 3, REFUNDING = 4;
    /** query_order 的 order.status：2 已支付待发货、3 发货中、4 已发货 → 已付；5 已退款、8 用户退款完成 → 已退。 */
    static final Set<Integer> XPAY_PAID = Set.of(2, 3, 4);
    static final Set<Integer> XPAY_REFUNDED = Set.of(5, 8);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 领取 ¥0 时绑定的有效段。 */
    public record Coverage(long membershipId, Date validTo) { }

    private final PlatformPassMapper mapper;
    private final PlatformPassProperties properties;
    private final TransactionTemplate tx;
    // 与营销商品同一做法:直接用 SDK 网关(它不依赖回调路由)。WechatPaymentOperations 有门面和网关两个实现,
    // 按类型注入会歧义;注入门面 IWechatPayService 又会形成 门面→路由→报名→通行证→门面 的循环依赖(隔离启动探针实测)。
    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("wechatPaymentSdkGateway")
    private WechatPaymentOperations wechatPay;
    @Autowired(required = false) private WechatVerifiedPaymentFactService verifiedFacts;
    @Autowired(required = false) private XpayGateway xpay;
    @Autowired(required = false) private IWeChatMiniSessionService miniSessions;
    @Autowired(required = false) private UmsMemberMapper memberMapper;

    public PlatformPassService(PlatformPassMapper mapper, PlatformPassProperties properties,
                               PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
    }

    // ------------------------------------------------------------------ 读

    /** 通行证页：套餐、本人有效期、可玩主题数、两个渠道是否可用。未登录 memberId 传 null。 */
    public Map<String, Object> overview(Long memberId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        if (!properties.isEnabled()) {
            out.put("unavailableReason", "PLATFORM_PASS_DISABLED");
            return out;
        }
        List<Map<String, Object>> plans = new ArrayList<>();
        for (Map<String, Object> p : mapper.selectOnSalePlans()) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("planCode", p.get("plan_code"));
            plan.put("title", p.get("title"));
            plan.put("priceFen", num(p.get("price_fen")));
            plan.put("validDays", num(p.get("valid_days")));
            plans.add(plan);
        }
        out.put("plans", plans);
        out.put("libraryCount", includedCount(new Date()));
        out.put("channels", channels());
        if (memberId != null && memberId > 0) out.put("holding", holding(memberId, new Date()));
        return out;
    }

    /** 「通行证含」的主题数：与领取同一口径（配置范围 + 已发布 + 自玩可领），不含已停售/已过期/非自玩。 */
    private int includedCount(Date now) {
        int n = 0;
        for (Map<String, Object> t : mapper.selectLibrary(null, 500)) {
            if (properties.coversTopic(num(t.get("id"))) && selfPlayAvailable(t, now)) n++;
        }
        return n;
    }

    public Map<String, Object> holding(long memberId, Date now) {
        Map<String, Object> holding = new LinkedHashMap<>();
        Map<String, Object> active = row(mapper.selectActiveMembership(memberId, now));
        holding.put("active", active != null);
        holding.put("validTo", active == null ? null : toDate(active.get("valid_to")));
        holding.put("validToText", active == null ? null : beijing(toDate(active.get("valid_to"))));
        holding.put("coverageEnd", mapper.selectCoverageEnd(memberId, now));
        putCardFacts(holding, active, now);
        return holding;
    }

    private static final long DAY_MS = 86_400_000L;

    /**
     * D-112 通行证卡字段（业主 2026-11-11「后端补字段后画」），全部由当前有效段 + 其订单冻结的套餐推出，没有就是 null：
     * planCode/planLabel 年卡·月卡；daysTotal/daysRemaining 进度条（当前有效段，向上取整天，剩余夹在 [0, 总数]）；
     * teammateBenefit 通行证只覆盖本人自玩（coverage 不含同行席位），恒 false，前端不据此画「同行可用」；
     * badge 只有「即将到期」（剩余 ≤7 天）一种事实，其余 null。
     */
    private static void putCardFacts(Map<String, Object> holding, Map<String, Object> active, Date now) {
        String code = active == null || blank(active.get("plan_code")) ? null : active.get("plan_code").toString();
        holding.put("planCode", code);
        holding.put("planLabel", code == null ? null : "YEAR".equals(code) ? "年卡" : "MONTH".equals(code) ? "月卡" : null);
        Integer total = null, remaining = null;
        Date from = active == null ? null : toDate(active.get("valid_from"));
        Date to = active == null ? null : toDate(active.get("valid_to"));
        if (from != null && to != null && to.after(from)) {
            long t = Math.max(1L, ceilDays(to.getTime() - from.getTime()));
            long r = Math.min(t, Math.max(0L, ceilDays(to.getTime() - now.getTime())));
            total = (int) t;
            remaining = (int) r;
        }
        holding.put("daysTotal", total);
        holding.put("daysRemaining", remaining);
        holding.put("teammateBenefit", false);
        holding.put("badge", remaining != null && remaining <= 7 ? "EXPIRING" : null);
    }

    private static long ceilDays(long ms) { return (ms + DAY_MS - 1) / DAY_MS; }

    public Map<String, Object> channels() {
        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put("jsapi", availability("JSAPI_PASS_PURCHASE_CLOSED")); // T01:新购不再走 JSAPI
        channels.put("xpay", availability(xpayUnavailableReason()));
        return channels;
    }

    /** 游戏主题库：复用公开目录可见性，不依赖通行证开关。productType 1 城市定向 / 2 自由探索 / null 全部。 */
    public Map<String, Object> library(Long memberId, Integer productType, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        Integer type = productType != null && (productType == 1 || productType == 2) ? productType : null;
        int bounded = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> topics = mapper.selectLibrary(type, bounded);
        Date now = new Date();
        Set<Long> covered = coveredTopics(memberId, topics.stream().map(t -> num(t.get("id"))).toList(), now);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> t : topics) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("topicId", num(t.get("id")));
            row.put("name", t.get("name"));
            row.put("imgUrl", t.get("img_url"));
            row.put("productType", t.get("product_type") == null ? null : num(t.get("product_type")));
            row.put("totalTime", t.get("total_time") == null ? null : num(t.get("total_time")));
            row.put("locationCount", t.get("location_count") == null ? null : num(t.get("location_count")));
            row.put("addressName", t.get("address_name"));
            // 通行证含：主题本身在范围内且可领；对我生效：再加上本人持有有效段（T08）
            row.put("passIncluded", properties.coversTopic(num(t.get("id"))) && selfPlayAvailable(t, now));
            row.put("passCovered", covered.contains(num(t.get("id"))));
            rows.add(row);
        }
        out.put("rows", rows);
        out.put("covered", !rows.isEmpty() && rows.stream().allMatch(r -> Boolean.TRUE.equals(r.get("passCovered"))));
        return out;
    }

    /**
     * 自玩票报价 / 下单时调用：本人此刻持有有效通行证、且已发布主题在配置覆盖范围内时返回覆盖段。
     * 总开关关闭时恒为 null（既有结果不变）。
     */
    public Coverage coverage(Long memberId, Long topicId, Date now) {
        if (!properties.coversTopic(topicId)) return null;
        if (!properties.isEnabled() || memberId == null || memberId <= 0 || topicId == null || topicId <= 0) return null;
        Map<String, Object> active = row(mapper.selectActiveMembership(memberId, now));
        if (active == null) return null;
        if (!claimableTopics(List.of(topicId), now).contains(topicId)) return null;
        return new Coverage(num(active.get("id")), toDate(active.get("valid_to")));
    }

    /** 按页批量取覆盖事实；匿名/开关关闭/无有效段均不查询主题，也不影响目录可读性。 */
    public Set<Long> coveredTopics(Long memberId, Collection<Long> topicIds, Date now) {
        if (!properties.isEnabled() || memberId == null || memberId <= 0 || topicIds == null || topicIds.isEmpty()) return Set.of();
        List<Long> eligible = topicIds.stream().filter(properties::coversTopic).distinct().toList();
        if (eligible.isEmpty() || row(mapper.selectActiveMembership(memberId, now)) == null) return Set.of();
        return claimableTopics(eligible, now);
    }

    /** 覆盖 / 报价 / 下单 / 主题库共用的唯一规则：已发布 + 自玩可领（未停售、未过期）。 */
    private Set<Long> claimableTopics(List<Long> topicIds, Date now) {
        Set<Long> ids = new HashSet<>();
        for (Map<String, Object> t : mapper.selectCoverageTopics(topicIds)) {
            if (selfPlayAvailable(t, now)) ids.add(num(t.get("id")));
        }
        return ids;
    }

    private boolean selfPlayAvailable(Map<String, Object> row, Date now) {
        if (num(row.get("self_play")) != 1) return false;
        BigDecimal price = row.get("self_play_price") == null ? null : new BigDecimal(row.get("self_play_price").toString());
        if (price == null || price.signum() < 0) return false;
        Date saleEnd = toDate(row.get("sale_end_at"));
        if (price.signum() == 0) {
            Date end = saleEnd == null ? toDate(row.get("end_date")) : saleEnd;
            return end == null || now.before(end);
        }
        CmsTopic topic = new CmsTopic();
        topic.setLifecycle(num(row.get("lifecycle")).intValue());
        topic.setSellingStartedAt(toDate(row.get("selling_started_at")));
        topic.setSaleEndAt(saleEnd);
        return SaleAvailabilityPolicy.sellableBlockReason(topic, null, now) == null;
    }

    /**
     * 与领取的 ¥0 报名行同一事务写入。事务内按与退款相同的锁序（订单行 → 会员锁 → 段）重验这一段仍然有效，
     * 避免与并发退款交叉后留下退款后的新领取。
     */
    public void recordClaim(Coverage coverage, long memberId, long topicId, long registrationId) {
        if (coverage == null) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("通行证领取必须与报名行同事务");
        }
        Map<String, Object> segment = row(mapper.selectMembershipById(coverage.membershipId()));
        Map<String, Object> order = segment == null ? null
                : row(mapper.selectOrderByIdForUpdate(num(segment.get("order_id"))));
        if (order != null) lockMember(memberId);
        Map<String, Object> locked = order == null ? null : row(mapper.selectMembershipByIdForUpdate(coverage.membershipId()));
        Date now = new Date();
        if (order == null || locked == null || num(order.get("status")) != PAID || num(locked.get("status")) != 1
                || num(locked.get("member_id")) != memberId
                || toDate(locked.get("valid_from")).after(now) || !toDate(locked.get("valid_to")).after(now)) {
            throw PlatformPassException.conflict("PASS_COVERAGE_LOST", "通行证已失效，请刷新后再试");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("membership_id", coverage.membershipId());
        row.put("member_id", memberId);
        row.put("topic_id", topicId);
        row.put("registration_id", registrationId);
        if (mapper.insertClaim(row) != 1) throw new IllegalStateException("通行证领取记录写入失败");
    }

    // ------------------------------------------------------------------ 下单 / 支付

    public Map<String, Object> createOrder(long memberId, String planCode, String channel, String requestId) {
        requireEnabled();
        String ch = channel == null ? "" : channel.trim().toUpperCase();
        if (!CHANNEL_JSAPI.equals(ch) && !CHANNEL_XPAY.equals(ch)) {
            throw PlatformPassException.badRequest("CHANNEL_INVALID", "支付方式无效");
        }
        // T01:渠道只由服务端决定。新购通行证不再走 JSAPI（旧 JSAPI 单仍可查询/回调/退款）。
        if (CHANNEL_JSAPI.equals(ch)) {
            throw PlatformPassException.badRequest("JSAPI_PASS_PURCHASE_CLOSED", "该支付方式暂不支持购买通行证");
        }
        String reason = xpayUnavailableReason();
        if (reason != null) throw PlatformPassException.unavailable(reason, "支付暂不可用");
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{8,64}")) {
            throw PlatformPassException.badRequest("REQUEST_ID_REQUIRED", "请求标识无效");
        }
        Map<String, Object> plan = planCode == null ? null : row(mapper.selectPlanByCode(planCode.trim().toUpperCase()));
        if (plan == null || num(plan.get("status")) != 1) {
            throw PlatformPassException.notFound("PLAN_NOT_ON_SALE", "该通行证暂未开售");
        }
        if (CHANNEL_XPAY.equals(ch) && blank(plan.get("xpay_product_id"))) {
            throw PlatformPassException.unavailable("XPAY_PRODUCT_NOT_CONFIGURED", "支付暂不可用");
        }
        String fingerprint = sha256(plan.get("plan_code") + "|" + ch + "|" + num(plan.get("price_fen"))
                + "|" + num(plan.get("valid_days")));
        Map<String, Object> replay = row(mapper.selectOrderByMemberRequest(memberId, requestId));
        if (replay != null) {
            if (!fingerprint.equals(replay.get("request_fingerprint"))) {
                throw PlatformPassException.conflict("REQUEST_CONFLICT", "请求已用于另一笔订单，请重新下单");
            }
            return orderView(replay);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("order_no", newNo(ORDER_PREFIX));
        row.put("member_id", memberId);
        row.put("request_id", requestId);
        row.put("request_fingerprint", fingerprint);
        row.put("plan_id", num(plan.get("id")));
        row.put("plan_code", plan.get("plan_code"));
        row.put("price_fen", num(plan.get("price_fen")));
        row.put("valid_days", num(plan.get("valid_days")));
        row.put("channel", ch);
        // T02:意图在建单时冻结，后续改价 / 换商品 / 换环境都不会让旧单被改签到别的商品或环境
        row.put("xpay_product_id", plan.get("xpay_product_id"));
        row.put("xpay_offer_id", properties.getXpay().getOfferId());
        row.put("xpay_env", properties.getXpay().getEnv());
        // T08:售出版本的覆盖范围与退款条款引用（最小快照）
        row.put("coverage_snapshot", coverageSnapshot());
        row.put("refund_terms_ref", REFUND_TERMS_REF);
        try {
            if (mapper.insertOrder(row) != 1) throw new IllegalStateException("通行证订单写入失败");
        } catch (org.springframework.dao.DuplicateKeyException race) {
            Map<String, Object> winner = mapper.selectOrderByMemberRequest(memberId, requestId);
            if (winner != null && fingerprint.equals(winner.get("request_fingerprint"))) return orderView(winner);
            throw PlatformPassException.conflict("REQUEST_CONFLICT", "请求已用于另一笔订单，请重新下单");
        }
        return orderView(mapper.selectOrderByNo((String) row.get("order_no")));
    }

    /**
     * 拉起支付参数。JSAPI 返回 wx.requestPayment 参数；XPAY 返回 wx.requestVirtualPayment 参数。
     * 不在事务内调用外部接口。
     */
    public Map<String, Object> preparePayment(long memberId, String orderNo, String jsCode) {
        requireEnabled();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("预下单不得在事务内");
        }
        Map<String, Object> order = ownedOrder(memberId, orderNo);
        if (num(order.get("status")) != PENDING) {
            throw PlatformPassException.conflict("ORDER_NOT_PAYABLE", "订单当前不可支付");
        }
        if (!CHANNEL_XPAY.equals(order.get("channel"))) {
            throw PlatformPassException.conflict("JSAPI_PASS_PURCHASE_CLOSED", "该支付方式暂不支持购买通行证");
        }
        String reason = xpayUnavailableReason();
        if (reason != null) throw PlatformPassException.unavailable(reason, "支付暂不可用");
        // T07:拉起支付是新购动作，套餐停售后旧待支付单不能再被签名；已付订单的回调/查询/退款不受影响
        Map<String, Object> plan = row(mapper.selectPlanByCode((String) order.get("plan_code")));
        if (plan == null || num(plan.get("status")) != 1) {
            throw PlatformPassException.notFound("PLAN_NOT_ON_SALE", "该通行证暂未开售");
        }
        PlatformPassProperties.Xpay cfg = properties.getXpay();
        if (blank(order.get("xpay_product_id"))) {
            // 迁移前建的旧单：首次签名前补冻结（此后不再改）
            if (blank(plan.get("xpay_product_id"))) {
                throw PlatformPassException.unavailable("XPAY_PRODUCT_NOT_CONFIGURED", "支付暂不可用");
            }
            if (mapper.freezeXpayIntent(num(order.get("id")), (String) plan.get("xpay_product_id"),
                    cfg.getOfferId(), cfg.getEnv()) != 1) {
                order = ownedOrder(memberId, orderNo);
            } else {
                order.put("xpay_product_id", plan.get("xpay_product_id"));
                order.put("xpay_offer_id", cfg.getOfferId());
                order.put("xpay_env", cfg.getEnv());
            }
        }
        if (blank(order.get("xpay_product_id")) || blank(order.get("xpay_offer_id")) || order.get("xpay_env") == null) {
            throw PlatformPassException.conflict("XPAY_INTENT_NOT_FROZEN", "订单支付意图未冻结，请重新下单");
        }
        if (!cfg.getOfferId().equals(order.get("xpay_offer_id")) || num(order.get("xpay_env")) != cfg.getEnv()) {
            throw PlatformPassException.conflict("XPAY_INTENT_CHANGED", "支付环境已变更，请重新下单");
        }
        if (jsCode == null || jsCode.isBlank()) throw PlatformPassException.badRequest("JS_CODE_REQUIRED", "请重新登录后再试");
        IWeChatMiniSessionService.WeChatMiniSession session = miniSessions.exchange(jsCode.trim());
        String openid = requireOpenId(memberId);
        if (session == null || !openid.equals(session.getOpenId())) {
            throw PlatformPassException.conflict("SESSION_MISMATCH", "请重新登录后再试");
        }
        Map<String, Object> out = orderView(order);
        out.put("virtualPayment", xpayClientParams(cfg, (String) order.get("xpay_offer_id"),
                num(order.get("xpay_env")).intValue(), (String) order.get("xpay_product_id"),
                num(order.get("price_fen")), (String) order.get("order_no"), session.getSessionKey()));
        return out;
    }

    /** wx.requestVirtualPayment 参数（道具直购）。signData 原串即签名串。 */
    static Map<String, Object> xpayClientParams(PlatformPassProperties.Xpay cfg, String offerId, int env,
                                                String productId, long priceFen,
                                                String orderNo, String sessionKey) {
        JSONObject sign = new JSONObject(new LinkedHashMap<>());
        sign.put("offerId", offerId);
        sign.put("buyQuantity", 1);
        sign.put("env", env);
        sign.put("currencyType", "CNY");
        sign.put("productId", productId);
        sign.put("goodsPrice", priceFen);
        sign.put("outTradeNo", orderNo);
        sign.put("attach", orderNo);
        String signData = sign.toJSONString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mode", "short_series_goods");
        params.put("signData", signData);
        params.put("paySig", XpaySigner.paySig(cfg.getAppKey(), XpaySigner.CLIENT_URI, signData));
        params.put("signature", XpaySigner.userSignature(sessionKey, signData));
        return params;
    }

    public Map<String, Object> order(long memberId, String orderNo) {
        return orderView(ownedOrder(memberId, orderNo));
    }

    public List<Map<String, Object>> myOrders(long memberId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : mapper.selectOrdersByMember(memberId, Math.max(1, Math.min(limit, 50)))) {
            out.add(orderView(row));
        }
        return out;
    }

    public Map<String, Object> cancel(long memberId, String orderNo) {
        Map<String, Object> order = ownedOrder(memberId, orderNo);
        if (num(order.get("status")) == PENDING) {
            mapper.markCancelled(num(order.get("id")), memberId, new Date());
        }
        return orderView(mapper.selectOrderByNo((String) order.get("order_no")));
    }

    // ------------------------------------------------------------------ JSAPI 回调（C07 已验证事实）

    /** 由 WechatPaymentCallbackRouter 调用。异常向上抛 → 回调不 ACK，微信重投。 */
    public boolean handleJsapiPaid(Map<String, String> facts) {
        if (facts == null || !"SUCCESS".equals(facts.get("trade_state"))) return false;
        String no = facts.get("out_trade_no");
        if (no == null || !no.startsWith(ORDER_PREFIX)) return false;
        Reference reference = reference(facts);
        return Boolean.TRUE.equals(tx.execute(s -> {
            Map<String, Object> order = row(mapper.selectOrderByNoForUpdate(no));
            if (order == null || !CHANNEL_JSAPI.equals(order.get("channel"))) return false;
            WechatVerifiedPaymentFact fact = verifiedFacts.requireConsumablePayment(reference).facts();
            if (fact.kind() != WechatVerifiedPaymentFact.Kind.PAYMENT || !no.equals(fact.businessOutTradeNo())
                    || fact.totalFen() != num(order.get("price_fen")) || fact.amountFen() != fact.totalFen()
                    || !"JSAPI".equals(fact.tradeType()) || !"CNY".equals(fact.currency())) {
                throw PlatformPassException.conflict("PAYMENT_FACT_CONFLICT", "支付事实与订单不一致");
            }
            int status = num(order.get("status")).intValue();
            if (order.get("paid_time") != null) {
                if (!Objects.equals(num(order.get("payment_fact_id")), reference.id())
                        || !reference.hash().equals(order.get("payment_fact_hash"))) {
                    throw PlatformPassException.conflict("FACT_OR_IDEMPOTENCY_CONFLICT", "重复支付事实冲突");
                }
                return true; // 已退款的不会因为旧的 SUCCESS 重新开通。
            }
            if (status != PENDING && status != CANCELLED) return false;
            Date paidAt = fact.successAt() == null ? new Date() : Date.from(fact.successAt());
            if (mapper.markJsapiPaid(num(order.get("id")), reference.id(), reference.hash(),
                    fact.transactionId(), paidAt) != 1) {
                throw new IllegalStateException("通行证订单已付回写失败");
            }
            activate(order, new Date(), paidAt);
            verifiedFacts.completePaymentRecovery(reference, num(order.get("id")));
            return true;
        }));
    }

    /** JSAPI 退款回调（outRefundNo 以 RPP 开头）。 */
    public boolean handleJsapiRefund(Map<String, String> facts) {
        if (facts == null || !"SUCCESS".equals(facts.get("status"))) return false;
        String no = facts.get("outTradeNo");
        String refundNo = facts.get("outRefundNo");
        if (no == null || !no.startsWith(ORDER_PREFIX) || refundNo == null || !refundNo.startsWith(REFUND_PREFIX)) {
            return false;
        }
        Reference reference = reference(facts);
        return Boolean.TRUE.equals(tx.execute(s -> {
            Map<String, Object> order = row(mapper.selectOrderByNoForUpdate(no));
            if (order == null || order.get("paid_time") == null || !CHANNEL_JSAPI.equals(order.get("channel"))) {
                return false;
            }
            WechatVerifiedPaymentFact original = verifiedFacts.requireVerified(new Reference(
                    num(order.get("payment_fact_id")), (String) order.get("payment_fact_hash"))).facts();
            WechatVerifiedPaymentFact fact = verifiedFacts.requireVerified(reference).facts();
            if (fact.kind() != WechatVerifiedPaymentFact.Kind.REFUND || !no.equals(fact.businessOutTradeNo())
                    || !refundNo.equals(order.get("refund_no")) || !refundNo.equals(fact.merchantEventNo())
                    || fact.amountFen() != num(order.get("price_fen")) || fact.totalFen() != original.totalFen()
                    || !Objects.equals(fact.transactionId(), original.transactionId())
                    || !Objects.equals(fact.mchid(), original.mchid()) || !"CNY".equals(fact.currency())) {
                throw PlatformPassException.conflict("REFUND_FACT_CONFLICT", "退款事实与订单不一致");
            }
            int status = num(order.get("status")).intValue();
            if (status == REFUNDED) {
                if (!Objects.equals(num(order.get("refund_fact_id")), reference.id())) {
                    throw PlatformPassException.conflict("FACT_OR_IDEMPOTENCY_CONFLICT", "重复退款事实冲突");
                }
                return true;
            }
            if (status != REFUNDING) return false;
            finalizeRefund(order, reference.id(), reference.hash(),
                    fact.successAt() == null ? new Date() : Date.from(fact.successAt()), num(order.get("price_fen")), "LOCAL");
            return true;
        }));
    }

    // ------------------------------------------------------------------ 虚拟支付

    /**
     * 消息推送里的虚拟支付事件。签名已由调用方用消息推送 token 校验。
     * 推送内容只当信号：开通/退款都以服务端回查结果为准。
     */
    public boolean handleXpayPush(JSONObject event) {
        // T07:回调只依赖凭据完整，不依赖「新售」开关；停售后既有订单的推送照常处理
        if (event == null || xpayHandlingReason() != null) return false;
        String type = event.getString("Event");
        String no = event.getString("OutTradeNo");
        if (no == null || !no.startsWith(ORDER_PREFIX)) return false;
        Map<String, Object> order = row(mapper.selectOrderByNo(no));
        if (order == null || !CHANNEL_XPAY.equals(order.get("channel"))) return false;
        if ("xpay_goods_deliver_notify".equals(type)) return confirmXpayInternal(order, true) != null;
        if ("xpay_refund_notify".equals(type)) return refreshXpayRefundInternal(order);
        return false;
    }

    /** 前端 requestVirtualPayment 成功后调用；沙箱不推送发货通知，也靠这里回查开通。 */
    public Map<String, Object> confirmXpay(long memberId, String orderNo) {
        Map<String, Object> order = ownedOrder(memberId, orderNo);
        if (!CHANNEL_XPAY.equals(order.get("channel"))) {
            throw PlatformPassException.conflict("CHANNEL_MISMATCH", "订单支付方式不符");
        }
        String unavailable = xpayHandlingReason();
        if (unavailable != null) throw PlatformPassException.unavailable(unavailable, "支付暂不可用");
        Map<String, Object> confirmed = confirmXpayInternal(order, false);
        return orderView(confirmed != null ? confirmed : mapper.selectOrderByNo((String) order.get("order_no")));
    }

    /** 订单冻结的环境；旧单没有冻结值时退回当前配置。 */
    private int envOf(Map<String, Object> order) {
        return order.get("xpay_env") == null ? properties.getXpay().getEnv() : num(order.get("xpay_env")).intValue();
    }

    /**
     * 回查虚拟支付订单；已付且金额一致则开通。返回最新订单行；未付返回 null。
     * 开通与「渠道发货确认待办」在同一事务落库；发货确认在事务外做，失败只留待办由补偿任务 / 重复推送 / 再次确认重试，
     * 不影响已开通，也不会再开第二段。
     */
    Map<String, Object> confirmXpayInternal(Map<String, Object> observed, boolean fromPush) {
        String no = (String) observed.get("order_no");
        int status = num(observed.get("status")).intValue();
        if (status == PAID || status == REFUNDED || status == REFUNDING) {
            if (status != REFUNDED && observed.get("delivery_status") != null && num(observed.get("delivery_status")) == 1) {
                confirmDelivery(observed);
                Map<String, Object> latest = row(mapper.selectOrderByNo(no));
                return latest != null ? latest : observed;
            }
            return observed;
        }
        long memberId = num(observed.get("member_id"));
        int env = envOf(observed);
        JSONObject response = xpay.queryOrder(requireOpenId(memberId), no, env);
        JSONObject remote = response == null ? null : response.getJSONObject("order");
        if (response == null || response.getIntValue("errcode", -1) != 0 || remote == null) return null;
        Integer remoteStatus = remote.getInteger("status");
        if (remoteStatus == null || !XPAY_PAID.contains(remoteStatus)) return null;
        long price = num(observed.get("price_fen"));
        if (!no.equals(remote.getString("order_id")) || remote.getLongValue("paid_fee", -1) != price
                || remote.getLongValue("order_fee", -1) != price
                || (remote.get("env") != null && remote.getIntValue("env", -1) != env)) {
            log.error("[通行证] 虚拟支付回查金额、单号或环境不一致 orderNo={}", no);
            throw PlatformPassException.conflict("PAYMENT_FACT_CONFLICT", "支付事实与订单不一致");
        }
        String wxOrderId = remote.getString("wx_order_id");
        if (wxOrderId == null || wxOrderId.isBlank()) {
            throw PlatformPassException.conflict("PAYMENT_FACT_CONFLICT", "支付事实缺少微信单号");
        }
        int deliveryStatus = remoteStatus == 2 ? 1 : 2;
        Date paidAt = channelPaidAt(remote, observed);
        Map<String, Object> result = tx.execute(s -> {
            Map<String, Object> order = mapper.selectOrderByNoForUpdate(no);
            int current = num(order.get("status")).intValue();
            if (current == PAID || current == REFUNDED || current == REFUNDING) {
                if (!wxOrderId.equals(order.get("transaction_id"))) {
                    throw PlatformPassException.conflict("FACT_OR_IDEMPOTENCY_CONFLICT", "重复支付事实冲突");
                }
                return order;
            }
            Date now = new Date();
            if (mapper.markXpayPaid(num(order.get("id")), wxOrderId, env, paidAt != null ? paidAt : now,
                    deliveryStatus) != 1) {
                throw new IllegalStateException("通行证订单已付回写失败");
            }
            activate(order, now, paidAt);
            return mapper.selectOrderByNo(no);
        });
        // 平台仍显示待发货（含仅收到推送、用户支付后立刻退出的情形）：补报已发货。
        if (deliveryStatus == 1 && result != null) {
            Map<String, Object> pending = new HashMap<>(result);
            pending.put("delivery_status", 1);
            confirmDelivery(pending);
        }
        return result;
    }

    /** 回查里的支付时间（unix 秒）：存在且合理才采用，否则沿用当前行为（以服务端确认时刻起算）。 */
    static Date channelPaidAt(JSONObject remote, Map<String, Object> order) {
        Long seconds = remote == null ? null : remote.getLong("paid_time");
        if (seconds == null || seconds <= 0) return null;
        Date at = new Date(seconds * 1000L);
        Date now = new Date();
        if (at.after(now)) return null;
        Date created = toDateOrNull(order.get("create_time"));
        if (created != null && at.getTime() < created.getTime() - 60_000L) return null;
        return at;
    }

    private static Date toDateOrNull(Object value) {
        return value == null ? null : toDate(value);
    }

    /**
     * 渠道发货确认（notify_provide_goods），事务外调用。已确认 / 已被渠道标记发货都算完成；
     * 失败只记待办并退避，绝不回滚已开通的权益，也不把已付订单显示成未付。
     */
    void confirmDelivery(Map<String, Object> order) {
        long id = num(order.get("id"));
        String no = (String) order.get("order_no");
        int env = envOf(order);
        boolean confirmed = false;
        try {
            JSONObject r = xpay.notifyProvideGoods(no, env);
            confirmed = r != null && r.getIntValue("errcode", -1) == 0;
            if (!confirmed) {
                // 重复发货通知 / 已发货：以回查为准，不依赖具体错误码
                JSONObject q = xpay.queryOrder(requireOpenId(num(order.get("member_id"))), no, env);
                JSONObject remote = q == null ? null : q.getJSONObject("order");
                Integer st = remote == null || q.getIntValue("errcode", -1) != 0 ? null : remote.getInteger("status");
                confirmed = st != null && (st == 3 || st == 4 || XPAY_REFUNDED.contains(st));
            }
        } catch (RuntimeException e) {
            log.warn("[通行证] 虚拟支付发货确认失败，留待补偿 orderNo={}", no);
        }
        if (confirmed) {
            mapper.markDeliveryConfirmed(id);
        } else {
            long attempts = order.get("delivery_attempts") == null ? 0 : num(order.get("delivery_attempts"));
            long minutes = Math.min(60L, 1L << Math.min(6L, attempts));
            mapper.markDeliveryRetry(id, new Date(System.currentTimeMillis() + minutes * 60_000L));
        }
    }

    /** 补偿任务入口：重试「已开通但渠道发货确认未完成」的订单。幂等；返回本轮处理数。 */
    public int retryPendingDeliveries(int limit) {
        if (xpay == null || xpayHandlingReason() != null) return 0;
        int driven = 0;
        for (Map<String, Object> order : mapper.selectOrdersPendingDelivery(new Date(), Math.max(1, Math.min(limit, 100)))) {
            confirmDelivery(order);
            driven++;
        }
        return driven;
    }

    // ------------------------------------------------------------------ 退款（后台）

    /**
     * 后台整单退款：只退「未领取过任何主题」的已付通行证。外部接口在事务外调用。
     * JSAPI 由退款回调收口；虚拟支付由退款推送或 {@link #refreshXpayRefund} 回查收口。
     */
    public Map<String, Object> requestRefund(String orderNo, String operator, String reason) {
        String refundNo = newNo(REFUND_PREFIX);
        String why = reason == null || reason.isBlank() ? "通行证退款" : reason.trim();
        Map<String, Object> order = tx.execute(s -> {
            Map<String, Object> locked = orderNo == null ? null : row(mapper.selectOrderByNoForUpdate(orderNo.trim()));
            if (locked == null) throw PlatformPassException.notFound("ORDER_NOT_FOUND", "订单不存在");
            if (num(locked.get("status")) != PAID) {
                throw PlatformPassException.conflict("ORDER_NOT_REFUNDABLE", "订单当前不可退款");
            }
            if (locked.get("refund_amount_fen") != null
                    && num(locked.get("refund_amount_fen")) < num(locked.get("price_fen"))) {
                throw PlatformPassException.conflict("PARTIAL_REFUND_RECORDED", "渠道已部分退款，请人工处理");
            }
            // T06:不再因「领取过主题」一刀切拒退；用过的整单退款须人工复核通过
            Map<String, Object> membership = row(mapper.selectMembershipByOrder(num(locked.get("id"))));
            boolean used = membership != null && mapper.countClaimsByMembership(num(membership.get("id"))) > 0;
            boolean approved = locked.get("review_status") != null && num(locked.get("review_status")) == 2;
            if (used && !approved) {
                throw PlatformPassException.conflict("MANUAL_REVIEW_REQUIRED", "通行证已领取过主题，需人工复核通过后才能退款");
            }
            if (mapper.markRefundRequested(num(locked.get("id")), refundNo,
                    why.length() > 120 ? why.substring(0, 120) : why, operator, new Date()) != 1) {
                throw new IllegalStateException("退款申请回写失败");
            }
            if (approved) mapper.markReviewReceipt(num(locked.get("id")), refundNo);
            return mapper.selectOrderByNo(orderNo.trim());
        });
        long price = num(order.get("price_fen"));
        boolean definitiveFailure;
        if (CHANNEL_JSAPI.equals(order.get("channel"))) {
            RefundRequestResult result;
            try {
                result = wechatPay.submitRefund(new Reference(num(order.get("payment_fact_id")),
                        (String) order.get("payment_fact_hash")), (String) order.get("order_no"), refundNo,
                        yuan(price), yuan(price), why);
            } catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) {
                throw paused;
            } catch (RuntimeException e) {
                result = RefundRequestResult.UNKNOWN;
            }
            definitiveFailure = result == RefundRequestResult.DEFINITIVE_FAILED;
        } else {
            JSONObject response;
            try {
                response = xpay.refundOrder(requireOpenId(num(order.get("member_id"))), (String) order.get("order_no"),
                        refundNo, price, price, why, num(order.get("xpay_env")).intValue());
                definitiveFailure = response != null && response.getIntValue("errcode", -1) != 0;
            } catch (RuntimeException e) {
                definitiveFailure = false; // 结果未知：保持退款中，按原退款号回查。
            }
        }
        if (definitiveFailure) {
            mapper.markRefundRolledBack(num(order.get("id")), refundNo);
            throw PlatformPassException.conflict("REFUND_REJECTED", "渠道拒绝退款，订单已恢复为已支付");
        }
        return orderView(mapper.selectOrderByNo((String) order.get("order_no")));
    }

    // ------------------------------------------------------------------ 玩家申请 / 后台复核（T06）

    /** 玩家申请整单退款并看进度。只登记申请与使用事实，真正出款由后台复核通过后触发。 */
    public Map<String, Object> applyRefund(long memberId, String orderNo, String reason) {
        Map<String, Object> order = ownedOrder(memberId, orderNo);
        if (num(order.get("status")) != PAID) {
            throw PlatformPassException.conflict("ORDER_NOT_REFUNDABLE", "订单当前不可申请退款");
        }
        String why = reason == null || reason.isBlank() ? "申请退款" : reason.trim();
        if (why.length() > 255) why = why.substring(0, 255);
        String facts = usageFacts(order);
        mapper.requestReview(num(order.get("id")), why, facts);
        return orderView(mapper.selectOrderByNo((String) order.get("order_no")));
    }

    /** 后台处理申请：通过则立即走原整单退款链路（渠道回执 = 退款单号）；驳回只记决定。 */
    public Map<String, Object> reviewRefund(String orderNo, String operator, boolean approve, String note) {
        String decision = note == null ? "" : note.trim();
        if (decision.length() > 255) decision = decision.substring(0, 255);
        String finalDecision = decision;
        Map<String, Object> order = tx.execute(s -> {
            Map<String, Object> locked = orderNo == null ? null : row(mapper.selectOrderByNoForUpdate(orderNo.trim()));
            if (locked == null) throw PlatformPassException.notFound("ORDER_NOT_FOUND", "订单不存在");
            if (mapper.decideReview(num(locked.get("id")), approve ? 2 : 3, operator, finalDecision, new Date()) != 1) {
                throw PlatformPassException.conflict("REVIEW_NOT_PENDING", "该申请当前不可处理");
            }
            return mapper.selectOrderByNo(orderNo.trim());
        });
        if (approve) {
            return requestRefund((String) order.get("order_no"), operator, (String) order.get("review_apply_reason"));
        }
        return orderView(order);
    }

    /** 后台订单列表（含复核信息）。 */
    public List<Map<String, Object>> adminOrders(Integer status, Integer reviewStatus, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : mapper.selectOrdersForAdmin(status, reviewStatus, Math.max(1, Math.min(limit, 200)))) {
            Map<String, Object> view = orderView(row);
            view.put("memberId", num(row.get("member_id")));
            view.put("transactionId", row.get("transaction_id"));
            view.put("usageFacts", row.get("review_usage_facts"));
            view.put("reviewHandler", row.get("review_handler"));
            view.put("reviewChannelReceipt", row.get("review_channel_receipt"));
            view.put("coverageSnapshot", row.get("coverage_snapshot"));
            view.put("refundTermsRef", row.get("refund_terms_ref"));
            out.add(view);
        }
        return out;
    }

    private String usageFacts(Map<String, Object> order) {
        long memberId = num(order.get("member_id"));
        Map<String, Object> membership = row(mapper.selectMembershipByOrder(num(order.get("id"))));
        List<String> topics = new ArrayList<>();
        if (membership != null) {
            for (Map<String, Object> claim : mapper.selectActiveClaims(memberId)) {
                if (num(claim.get("membership_id")).equals(num(membership.get("id")))) topics.add(String.valueOf(claim.get("topic_id")));
            }
        }
        String text = "已领取主题数=" + topics.size() + (topics.isEmpty() ? "" : "；主题=" + String.join(",", topics));
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    public Map<String, Object> refreshXpayRefund(String orderNo) {
        Map<String, Object> order = orderNo == null ? null : row(mapper.selectOrderByNo(orderNo.trim()));
        if (order == null) throw PlatformPassException.notFound("ORDER_NOT_FOUND", "订单不存在");
        refreshXpayRefundInternal(order);
        return orderView(mapper.selectOrderByNo((String) order.get("order_no")));
    }

    /**
     * 回查退款事实并收口。渠道侧发起的退款（用户在渠道申请、客服操作）也可直接把已付单关闭为已退：
     * 依据是服务端回查到的退款事实，且订单号 / 微信单号 / 金额严格匹配；推送本身只是信号，伪造通知无法撤权。
     */
    boolean refreshXpayRefundInternal(Map<String, Object> observed) {
        if (!CHANNEL_XPAY.equals(observed.get("channel"))) return false;
        int status = num(observed.get("status")).intValue();
        if (status == REFUNDED) return true;
        if (status != REFUNDING && status != PAID) return false;
        if (xpayHandlingReason() != null) return false;
        String no = (String) observed.get("order_no");
        JSONObject response = xpay.queryOrder(requireOpenId(num(observed.get("member_id"))), no, envOf(observed));
        JSONObject remote = response == null ? null : response.getJSONObject("order");
        if (response == null || response.getIntValue("errcode", -1) != 0 || remote == null) return false;
        Integer remoteStatus = remote.getInteger("status");
        if (remoteStatus == null || !XPAY_REFUNDED.contains(remoteStatus)) return false;
        long price = num(observed.get("price_fen"));
        long refund = remote.getLongValue("refund_fee", -1);
        String localTx = (String) observed.get("transaction_id");
        if (!no.equals(remote.getString("order_id")) || refund <= 0 || refund > price
                || (localTx != null && !localTx.isBlank() && !localTx.equals(remote.getString("wx_order_id")))) {
            throw PlatformPassException.conflict("REFUND_FACT_CONFLICT", "退款事实与订单不一致");
        }
        if (refund < price) {
            // 渠道部分退款：如实记录真实金额并转人工，不撤权
            if (status != PAID) return false;
            return Boolean.TRUE.equals(tx.execute(s -> {
                Map<String, Object> order = row(mapper.selectOrderByNoForUpdate(no));
                if (order == null || num(order.get("status")) != PAID) return false;
                mapper.recordPartialChannelRefund(num(order.get("id")), refund, "渠道部分退款 " + refund + " 分");
                return true;
            }));
        }
        return Boolean.TRUE.equals(tx.execute(s -> {
            Map<String, Object> order = mapper.selectOrderByNoForUpdate(no);
            if (num(order.get("status")) == REFUNDED) return true;
            int current = num(order.get("status")).intValue();
            if (current != REFUNDING && current != PAID) return false;
            finalizeRefund(order, null, null, new Date(), refund, current == REFUNDING ? "LOCAL" : "CHANNEL");
            return true;
        }));
    }

    // ------------------------------------------------------------------ 内部

    /** 在调用方事务内：按订单开通一段有效期，续买从本人最后一段的到期时刻顺延。幂等（order_id 唯一）。 */
    void activate(Map<String, Object> order, Date now, Date paidAt) {
        long orderId = num(order.get("id"));
        if (row(mapper.selectMembershipByOrder(orderId)) != null) return;
        long memberId = num(order.get("member_id"));
        lockMember(memberId); // T10:同会员串行，两笔不同订单并发开通不会重叠、不丢天数
        // 起点 = 可核实的渠道支付时间（不晚于现在）；没有则当前行为
        Date base = paidAt != null && paidAt.before(now) ? paidAt : now;
        Date latest = mapper.selectLatestValidToForUpdate(memberId, base);
        Date from = latest != null && latest.after(base) ? latest : base;
        Date to = new Date(from.getTime() + num(order.get("valid_days")) * 86_400_000L);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("member_id", memberId);
        row.put("order_id", orderId);
        row.put("valid_from", from);
        row.put("valid_to", to);
        if (mapper.insertMembership(row) != 1) throw new IllegalStateException("通行证有效期写入失败");
        extendClaimTickets(memberId);
    }

    /** 在调用方事务内：订单置已退，撤销这一段，并把它之后的段前移（不白送也不吞时长）；派生免费票同事务停用 / 回收。 */
    private void finalizeRefund(Map<String, Object> order, Long factId, String factHash, Date refundedAt,
                                long amount, String source) {
        long orderId = num(order.get("id"));
        long memberId = num(order.get("member_id"));
        if (mapper.markRefundedFromChannel(orderId, factId, factHash, refundedAt, amount, source) != 1) {
            throw new IllegalStateException("通行证退款回写失败");
        }
        lockMember(memberId);
        Map<String, Object> membership = row(mapper.selectMembershipByOrder(orderId));
        if (membership == null || num(membership.get("status")) != 1) return;
        Date from = toDate(membership.get("valid_from"));
        Date to = toDate(membership.get("valid_to"));
        Date now = new Date();
        // 向上取整到秒:后一段起点不晚于现在,避免退款后出现不到 1 秒的无覆盖空档(真库 IT 实测)。
        long remainingMs = Math.max(0, to.getTime() - Math.max(from.getTime(), now.getTime()));
        long seconds = (remainingMs + 999) / 1000;
        mapper.revokeMembership(orderId);
        if (seconds > 0) mapper.shiftLaterMemberships(memberId, to, seconds);
        recheckClaimTickets(memberId, num(membership.get("id")), now);
    }

    /** 连续段（间隔 ≤1 秒）合成一条覆盖链，返回每段所属链的结束时刻。 */
    static Map<Long, Date> chainEnds(List<Map<String, Object>> segments) {
        Map<Long, Date> out = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        Date end = null;
        for (Map<String, Object> seg : segments) {
            Date from = toDate(seg.get("valid_from")), to = toDate(seg.get("valid_to"));
            if (end != null && from.getTime() > end.getTime() + 1000) {
                for (Long id : ids) out.put(id, end);
                ids.clear();
                end = null;
            }
            ids.add(num(seg.get("id")));
            if (end == null || to.after(end)) end = to;
        }
        for (Long id : ids) out.put(id, end);
        return out;
    }

    /** 退款后：被撤销段的派生票停用；其余派生票不得超出各自所属覆盖链的结束时刻（段前移后重验）。 */
    private void recheckClaimTickets(long memberId, long revokedMembershipId, Date now) {
        Map<Long, Date> ends = chainEnds(mapper.selectActiveSegments(memberId));
        for (Map<String, Object> claim : mapper.selectActiveClaims(memberId)) {
            long membershipId = num(claim.get("membership_id"));
            Date end = membershipId == revokedMembershipId ? null : ends.get(membershipId);
            mapper.clampClaimTicket(num(claim.get("registration_id")), end == null ? now : end);
        }
        mapper.markClaimsStopped(revokedMembershipId, now);
    }

    /** 正常续买后：原先被上一段结束时刻截断的派生票，顺延到新的覆盖链结束（不新增票、奖励或库存）。 */
    private void extendClaimTickets(long memberId) {
        List<Map<String, Object>> segments = mapper.selectActiveSegments(memberId);
        if (segments == null || segments.isEmpty()) return;
        Map<Long, Date> ends = chainEnds(segments);
        Map<Long, Date> own = new HashMap<>();
        for (Map<String, Object> seg : segments) own.put(num(seg.get("id")), toDate(seg.get("valid_to")));
        for (Map<String, Object> claim : mapper.selectActiveClaims(memberId)) {
            long membershipId = num(claim.get("membership_id"));
            Date segEnd = own.get(membershipId), chainEnd = ends.get(membershipId);
            if (segEnd != null && chainEnd != null && chainEnd.getTime() > segEnd.getTime() + 1000) {
                mapper.extendClaimTicket(num(claim.get("registration_id")), new Date(segEnd.getTime() - 1000),
                        new Date(segEnd.getTime() + 1000), chainEnd);
            }
        }
    }

    private void lockMember(long memberId) {
        mapper.insertMemberLock(memberId);
        mapper.lockMember(memberId);
    }

    private Map<String, Object> ownedOrder(long memberId, String orderNo) {
        Map<String, Object> order = orderNo == null ? null : row(mapper.selectOrderByNo(orderNo.trim()));
        if (order == null || num(order.get("member_id")) != memberId) {
            throw PlatformPassException.notFound("ORDER_NOT_FOUND", "订单不存在");
        }
        return order;
    }

    Map<String, Object> orderView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderNo", row.get("order_no"));
        view.put("planCode", row.get("plan_code"));
        view.put("priceFen", num(row.get("price_fen")));
        view.put("validDays", num(row.get("valid_days")));
        view.put("channel", row.get("channel"));
        int status = num(row.get("status")).intValue();
        view.put("status", switch (status) {
            case PAID -> "PAID";
            case CANCELLED -> "CANCELLED";
            case REFUNDED -> "REFUNDED";
            case REFUNDING -> "REFUNDING";
            default -> "PENDING";
        });
        Date paid = toDate(row.get("paid_time"));
        view.put("paidTime", paid);
        view.put("paidTimeText", beijing(paid));
        // 渠道发货确认只是对账待办，已付就是已付
        int delivery = row.get("delivery_status") == null ? 0 : num(row.get("delivery_status")).intValue();
        view.put("channelDelivery", delivery == 1 ? "PENDING" : delivery == 2 ? "CONFIRMED" : "NONE");
        int review = row.get("review_status") == null ? 0 : num(row.get("review_status")).intValue();
        Map<String, Object> refund = new LinkedHashMap<>();
        refund.put("reviewStatus", switch (review) {
            case 1 -> "PENDING";
            case 2 -> "APPROVED";
            case 3 -> "REJECTED";
            default -> "NONE";
        });
        refund.put("applyReason", row.get("review_apply_reason"));
        refund.put("decision", row.get("review_decision"));
        refund.put("decidedTime", toDate(row.get("review_time")));
        refund.put("refundedFen", row.get("refund_amount_fen") == null ? null : num(row.get("refund_amount_fen")));
        view.put("refund", refund);
        if (status == PAID) {
            Map<String, Object> membership = row(mapper.selectMembershipByOrder(num(row.get("id"))));
            if (membership != null) {
                Date vf = toDate(membership.get("valid_from")), vt = toDate(membership.get("valid_to"));
                view.put("validFrom", vf);
                view.put("validTo", vt);
                view.put("validFromText", beijing(vf));
                view.put("validToText", beijing(vt));
            }
        }
        return view;
    }

    /** 库内一律存瞬时值，对外展示按北京时间。 */
    static String beijing(Date at) {
        if (at == null) return null;
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.of("Asia/Shanghai")).format(at.toInstant());
    }

    /** 售出时的覆盖范围：空配置 = 全部已发布；否则排序后的主题 ID 列表（超长截断，仅作留痕）。 */
    private String coverageSnapshot() {
        Set<Long> ids = properties.getCoverageTopicIds();
        if (ids.isEmpty()) return "ALL";
        String text = "IDS:" + ids.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private String xpayUnavailableReason() {
        if (!properties.isEnabled()) return "PLATFORM_PASS_DISABLED";
        if (!properties.getXpay().isEnabled()) return "XPAY_DISABLED";
        if (!properties.getXpay().isReady() || xpay == null || miniSessions == null) return "XPAY_NOT_CONFIGURED";
        return null;
    }

    /** 既有订单的回查 / 发货确认 / 退款只要凭据完整即可，不依赖新售开关（T07）。 */
    private String xpayHandlingReason() {
        if (xpay == null || !properties.getXpay().hasCredentials()) return "XPAY_NOT_CONFIGURED";
        return null;
    }

    private static Map<String, Object> availability(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", reason == null);
        if (reason != null) out.put("reason", reason);
        return out;
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw PlatformPassException.unavailable("PLATFORM_PASS_DISABLED", "通行证暂未开放");
        }
    }

    private String requireOpenId(long memberId) {
        UmsMember member = memberMapper == null ? null : memberMapper.selectUmsMemberById(memberId);
        if (member == null || member.getWxOpenId() == null || member.getWxOpenId().isBlank()) {
            throw PlatformPassException.conflict("OPENID_MISSING", "当前账号缺少微信支付标识");
        }
        return member.getWxOpenId();
    }

    private static Reference reference(Map<String, String> facts) {
        try {
            return new Reference(Long.parseLong(facts.get("verified_fact_id")), facts.get("verified_fact_hash"));
        } catch (RuntimeException invalid) {
            throw PlatformPassException.conflict("VERIFIED_FACT_REQUIRED", "缺少已验证支付事实");
        }
    }

    static String newNo(String prefix) {
        String stamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        StringBuilder sb = new StringBuilder(prefix).append(stamp);
        while (sb.length() < 32) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }

    static BigDecimal yuan(long fen) { return BigDecimal.valueOf(fen).movePointLeft(2); }

    static Long num(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof Boolean bit) return bit ? 1L : 0L;
        if (value == null) return null;
        return Long.valueOf(value.toString());
    }

    /** Map 结果里 DATETIME 列由驱动给 LocalDateTime（真库 IT 实测），统一转回 Date（JVM 默认时区，与写入一致）。 */
    static Date toDate(Object value) {
        if (value == null) return null;
        if (value instanceof Date d) return new Date(d.getTime());
        if (value instanceof java.time.LocalDateTime t) return Date.from(t.atZone(java.time.ZoneId.systemDefault()).toInstant());
        if (value instanceof java.time.OffsetDateTime t) return Date.from(t.toInstant());
        if (value instanceof java.time.Instant t) return Date.from(t);
        throw new IllegalStateException("无法识别的时间类型: " + value.getClass().getName());
    }

    /** MyBatis 查不到返回 null；空行同样视为不存在。 */
    private static Map<String, Object> row(Map<String, Object> value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean blank(Object value) { return value == null || value.toString().isBlank(); }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

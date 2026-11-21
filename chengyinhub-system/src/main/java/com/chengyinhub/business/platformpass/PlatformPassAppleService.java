package com.chengyinhub.business.platformpass;

import com.apple.itunes.storekit.model.InAppOwnershipType;
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload;
import com.apple.itunes.storekit.model.ResponseBodyV2DecodedPayload;
import com.apple.itunes.storekit.model.Type;
import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import com.chengyinhub.business.platformpass.mapper.PlatformPassAppleMapper;
import com.chengyinhub.business.platformpass.mapper.PlatformPassMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * R6a(FLOW-10 / MON-05):通行证在 iOS 上用 StoreKit 2 购买。
 *
 * <ul>
 *   <li>建单:服务端冻结套餐、价格、天数与 App Store 商品 ID,并签发一个 {@code appAccountToken}(UUID)。App 购买时
 *       把它交给 StoreKit,Apple 把它签进交易,服务端靠它把交易对回本单——客户端说的订单号、价格、天数一律不信。</li>
 *   <li>开通:只接受 Apple 签名的交易(证书链到运营配置的 Apple 根证书、bundle id、环境都由 Apple SDK 校验),
 *       一笔交易只开通一单(transaction_id 唯一),重复提交返回原结果,不会多开一段。</li>
 *   <li>退款 / 撤销:App Store Server Notifications V2 的 REFUND / REVOKE 收回这一段(与微信退款同一套收回逻辑),
 *       即使资金开关已关也照常处理;不能处理时回非 2xx,让 Apple 重投。</li>
 *   <li>资金开关:只管「卖」。建单(与购买页报价)要求 {@code platform-pass.enabled}、本渠道开关、以及资金开关的
 *       pass 区域同时打开;任一关闭即拒绝,生产环境默认全部关闭。</li>
 *   <li>交付(MERCHANT-2):Apple 已扣款的交易必须能开通、能恢复。确认、按交易号取回、以及 ONE_TIME_CHARGE 通知
 *       只要求本渠道的验签配置可用,不读资金开关:能对上的订单只可能在开关打开时建出,关开关只停止新的售卖。</li>
 * </ul>
 */
@Service
public class PlatformPassAppleService {
    private static final Logger log = LoggerFactory.getLogger(PlatformPassAppleService.class);

    private final PlatformPassService pass;
    private final PlatformPassMapper passMapper;
    private final PlatformPassAppleMapper mapper;
    private final PlatformPassProperties passProperties;
    private final PlatformPassAppleProperties config;
    private final PlatformPassAppleVerifier verifier;
    private final MoneySwitchService moneySwitch;
    private final TransactionTemplate tx;
    private final TransactionTemplate inboxTx;

    public PlatformPassAppleService(PlatformPassService pass, PlatformPassMapper passMapper, PlatformPassAppleMapper mapper,
                                    PlatformPassProperties passProperties, PlatformPassAppleProperties config,
                                    PlatformPassAppleVerifier verifier, MoneySwitchService moneySwitch,
                                    PlatformTransactionManager transactions) {
        this.pass = pass;
        this.passMapper = passMapper;
        this.mapper = mapper;
        this.passProperties = passProperties;
        this.config = config;
        this.verifier = verifier;
        this.moneySwitch = moneySwitch;
        this.tx = new TransactionTemplate(transactions);
        this.inboxTx = new TransactionTemplate(transactions);
        this.inboxTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 资金开关的 pass 区域。读失败一律视为关闭。 */
    boolean moneyOpen() {
        try {
            return Boolean.TRUE.equals(moneySwitch.snapshot().get(MoneyArea.PASS.key()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 不能卖的原因;null = 可以卖。顺序:通行证总开关 → 资金开关 → 内购配置。 */
    public String unavailableReason() {
        if (!passProperties.isEnabled()) return "PLATFORM_PASS_DISABLED";
        if (!moneyOpen()) return "MONEY_SWITCH_CLOSED";
        return verifier.unavailableReason();
    }

    /** 卖:报价与建单。通行证总开关、资金开关 pass 区域、内购配置都要打开。 */
    private void requireSellOpen() {
        String reason = unavailableReason();
        if (reason != null) throw PlatformPassException.unavailable(reason, "App Store 购买暂不可用");
    }

    /**
     * 交付:不能交付的原因;null = 可以交付。只看本渠道的验签配置——资金开关关闭只停止新的售卖,
     * Apple 已扣款的购买照常开通与恢复(MERCHANT-2)。
     */
    public String undeliverableReason() {
        return verifier.unavailableReason();
    }

    private void requireDeliverable() {
        String reason = undeliverableReason();
        if (reason != null) throw PlatformPassException.unavailable(reason, "App Store 购买暂不可用");
    }

    // ------------------------------------------------------------------ 读

    /** App 的购买页:能不能买、哪个环境、每个在售套餐对应的 App Store 商品 ID(只来自服务端配置)。 */
    public Map<String, Object> offer() {
        Map<String, Object> out = new LinkedHashMap<>();
        String reason = unavailableReason();
        List<Map<String, Object>> products = new ArrayList<>();
        if (reason == null) {
            for (Map<String, Object> plan : passMapper.selectOnSalePlans()) {
                String productId = config.productForPlan(str(plan.get("plan_code")));
                if (productId == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("planCode", plan.get("plan_code"));
                row.put("title", plan.get("title"));
                row.put("priceFen", PlatformPassService.num(plan.get("price_fen")));
                row.put("validDays", PlatformPassService.num(plan.get("valid_days")));
                row.put("productId", productId);
                products.add(row);
            }
            if (products.isEmpty()) reason = "APPLE_PRODUCT_NOT_CONFIGURED";
        }
        out.put("available", reason == null);
        out.put("unavailableReason", reason);
        out.put("environment", reason == null ? verifier.configuredEnvironment() : null);
        out.put("products", reason == null ? products : List.of());
        return out;
    }

    // ------------------------------------------------------------------ 建单

    public Map<String, Object> createOrder(long memberId, String planCode, String requestId) {
        requireSellOpen();
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{8,64}")) {
            throw PlatformPassException.badRequest("REQUEST_ID_REQUIRED", "请求标识无效");
        }
        Map<String, Object> plan = planCode == null ? null : passMapper.selectPlanByCode(planCode.trim().toUpperCase());
        if (plan == null || plan.isEmpty() || !Long.valueOf(1L).equals(PlatformPassService.num(plan.get("status")))) {
            throw PlatformPassException.notFound("PLAN_NOT_ON_SALE", "该通行证暂未开售");
        }
        String productId = config.productForPlan(str(plan.get("plan_code")));
        if (productId == null) {
            throw PlatformPassException.unavailable("APPLE_PRODUCT_NOT_CONFIGURED", "App Store 购买暂不可用");
        }
        String fingerprint = PlatformPassService.sha256(plan.get("plan_code") + "|" + PlatformPassService.CHANNEL_APPLE
                + "|" + PlatformPassService.num(plan.get("price_fen")) + "|" + PlatformPassService.num(plan.get("valid_days"))
                + "|" + productId);
        Map<String, Object> replay = passMapper.selectOrderByMemberRequest(memberId, requestId);
        if (replay != null && !replay.isEmpty()) {
            if (!fingerprint.equals(replay.get("request_fingerprint"))) {
                throw PlatformPassException.conflict("REQUEST_CONFLICT", "请求已用于另一笔订单，请重新下单");
            }
            return orderView(replay);
        }
        String orderNo = PlatformPassService.newNo(PlatformPassService.ORDER_PREFIX);
        try {
            tx.execute(s -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("order_no", orderNo);
                row.put("member_id", memberId);
                row.put("request_id", requestId);
                row.put("request_fingerprint", fingerprint);
                row.put("plan_id", PlatformPassService.num(plan.get("id")));
                row.put("plan_code", plan.get("plan_code"));
                row.put("price_fen", PlatformPassService.num(plan.get("price_fen")));
                row.put("valid_days", PlatformPassService.num(plan.get("valid_days")));
                row.put("channel", PlatformPassService.CHANNEL_APPLE);
                row.put("coverage_snapshot", pass.coverageSnapshot());
                row.put("refund_terms_ref", PlatformPassService.REFUND_TERMS_REF);
                if (passMapper.insertOrder(row) != 1) throw new IllegalStateException("通行证订单写入失败");
                Map<String, Object> apple = new LinkedHashMap<>();
                apple.put("order_no", orderNo);
                apple.put("member_id", memberId);
                apple.put("plan_code", plan.get("plan_code"));
                apple.put("product_id", productId);
                apple.put("app_account_token", UUID.randomUUID().toString());
                apple.put("bundle_id", config.getBundleId());
                apple.put("environment", verifier.configuredEnvironment());
                if (mapper.insertAppleOrder(apple) != 1) throw new IllegalStateException("内购订单写入失败");
                return null;
            });
        } catch (org.springframework.dao.DuplicateKeyException race) {
            Map<String, Object> winner = passMapper.selectOrderByMemberRequest(memberId, requestId);
            if (winner != null && fingerprint.equals(winner.get("request_fingerprint"))) return orderView(winner);
            throw PlatformPassException.conflict("REQUEST_CONFLICT", "请求已用于另一笔订单，请重新下单");
        }
        return orderView(passMapper.selectOrderByNo(orderNo));
    }

    // ------------------------------------------------------------------ 开通

    /** App 把 StoreKit 2 的 {@code jwsRepresentation} 交上来。只认 Apple 签名;成功后 App 才 finish 这笔交易。 */
    public Map<String, Object> confirm(long memberId, String signedTransaction) {
        requireDeliverable();
        Fact fact = fact(verifier.transaction(signedTransaction));
        return grant(fact, memberId);
    }

    /** 丢了签名交易时按交易号向 App Store Server API 取回,再走同一条验签与开通。 */
    public Map<String, Object> lookup(long memberId, String transactionId) {
        requireDeliverable();
        String signed = verifier.lookup(transactionId);
        Fact fact = fact(verifier.transaction(signed));
        if (!Objects.equals(transactionId, fact.transactionId())) {
            throw PlatformPassException.conflict("APPLE_LOOKUP_CONFLICT", "交易号不一致");
        }
        return grant(fact, memberId);
    }

    /** memberId 为 null 表示来自 Apple 通知(没有登录态),此时只按 appAccountToken 对单。 */
    Map<String, Object> grant(Fact fact, Long memberId) {
        String result = tx.execute(s -> {
            Map<String, Object> apple = mapper.selectByTokenForUpdate(fact.appAccountToken());
            if (apple == null || (memberId != null && PlatformPassService.num(apple.get("member_id")) != memberId.longValue())) {
                throw PlatformPassException.notFound("ORDER_NOT_FOUND", "订单不存在");
            }
            String orderNo = str(apple.get("order_no"));
            if (!fact.productId().equals(apple.get("product_id"))) {
                throw PlatformPassException.conflict("APPLE_PRODUCT_CONFLICT", "购买的商品与订单不一致");
            }
            if (!Objects.equals(apple.get("bundle_id"), fact.bundleId())
                    || !Objects.equals(apple.get("environment"), fact.environment())) {
                throw PlatformPassException.conflict("APPLE_ENVIRONMENT_CONFLICT", "购买环境与订单不一致");
            }
            String bound = str(apple.get("transaction_id"));
            if (bound != null && !bound.equals(fact.transactionId())) {
                throw PlatformPassException.conflict("APPLE_ORDER_ALREADY_BOUND", "该订单已绑定另一笔购买");
            }
            if (bound == null) {
                Map<String, Object> other = mapper.selectByTransactionForUpdate(fact.transactionId());
                if (other != null && !orderNo.equals(other.get("order_no"))) {
                    throw PlatformPassException.conflict("APPLE_TRANSACTION_ALREADY_USED", "这笔购买已用于另一笔订单");
                }
                if (mapper.bindTransaction(orderNo, fact.transactionId(), fact.originalTransactionId(), fact.price(),
                        fact.currency(), fact.purchaseDate()) != 1) {
                    throw PlatformPassException.conflict("APPLE_ORDER_ALREADY_BOUND", "该订单已绑定另一笔购买");
                }
            }
            if ("REVOKED".equals(apple.get("state")) || "REVIEW".equals(apple.get("state")) || fact.revokedAt() != null) {
                // 已退款 / 已撤销的交易永远不再开通;先到的退款也会把本单钉死。
                if (fact.revokedAt() != null) mapper.markRevoked(orderNo, fact.revokedAt(), fact.revocationReason());
                return orderNo;
            }
            Map<String, Object> order = passMapper.selectOrderByNoForUpdate(orderNo);
            if (order == null) throw PlatformPassException.notFound("ORDER_NOT_FOUND", "订单不存在");
            int status = PlatformPassService.num(order.get("status")).intValue();
            if (status == PlatformPassService.PENDING || status == PlatformPassService.CANCELLED) {
                Date now = new Date();
                if (mapper.markOrderPaid(PlatformPassService.num(order.get("id")), fact.transactionId(),
                        fact.purchaseDate()) != 1) {
                    throw new IllegalStateException("通行证订单已付回写失败");
                }
                pass.activate(order, now, fact.purchaseDate());
            }
            mapper.markGranted(orderNo);
            return orderNo;
        });
        return orderView(passMapper.selectOrderByNo(result));
    }

    // ------------------------------------------------------------------ App Store Server Notifications V2

    /**
     * 通知路由入口。不是通行证商品(或本渠道未配置)时返回 empty,由调用方转给 MOD 工坊的内购处理。
     * 是通行证商品时,处理结果只有两种:返回(App Store 收到 200 不再重投)或抛出(非 2xx,Apple 重投)。
     */
    public Optional<Map<String, Object>> notificationIfPass(String signedPayload) {
        if (verifier.unavailableReason() != null) return Optional.empty();
        ResponseBodyV2DecodedPayload body;
        try {
            body = verifier.notification(signedPayload);
        } catch (PlatformPassException e) {
            return Optional.empty();
        }
        String type = body.getRawNotificationType();
        if (body.getData() == null || body.getData().getSignedTransactionInfo() == null) {
            // TEST 等不带交易的通知:确认收到即可。
            return "TEST".equals(type) ? Optional.of(Map.of("state", "IGNORED", "notificationType", type)) : Optional.empty();
        }
        JWSTransactionDecodedPayload decoded;
        try {
            decoded = verifier.transaction(body.getData().getSignedTransactionInfo());
        } catch (PlatformPassException e) {
            return Optional.empty();
        }
        if (config.planForProduct(decoded.getProductId()) == null) return Optional.empty();
        return Optional.of(handleNotification(body, decoded, PlatformPassService.sha256(signedPayload)));
    }

    Map<String, Object> handleNotification(ResponseBodyV2DecodedPayload body, JWSTransactionDecodedPayload decoded,
                                           String hash) {
        String uuid = uuid(body.getNotificationUUID());
        String type = body.getRawNotificationType();
        String subtype = body.getRawSubtype();
        boolean applied = Boolean.TRUE.equals(inboxTx.execute(s -> {
            mapper.insertNotification(uuid, type, subtype, decoded.getTransactionId(), hash);
            Map<String, Object> inbox = mapper.lockNotification(uuid);
            if (inbox == null || !hash.equals(inbox.get("payload_hash"))) {
                throw PlatformPassException.conflict("APPLE_NOTIFICATION_UUID_CONFLICT", "通知编号冲突");
            }
            // 旧版本在开关关闭时把 ONE_TIME_CHARGE 记为 IGNORED;Apple 重投时要再处理一次,不能当作已完成。
            return "APPLIED".equals(inbox.get("state"))
                    || ("IGNORED".equals(inbox.get("state")) && !"ONE_TIME_CHARGE".equals(type));
        }));
        if (applied) return Map.of("state", "APPLIED", "notificationUUID", uuid);
        try {
            String state;
            if ("REFUND".equals(type) || "REVOKE".equals(type)) {
                revoke(fact(decoded), body.getSignedDate());
                state = "APPLIED";
            } else if ("ONE_TIME_CHARGE".equals(type)) {
                // 交付不看资金开关:能对上订单就开通(幂等);对不上订单的扣款通知不是本服务签出的,忽略。
                Fact fact = fact(decoded);
                Map<String, Object> order = mapper.selectByToken(fact.appAccountToken());
                if (order != null && !order.isEmpty()) {
                    grant(fact, null);
                    state = "APPLIED";
                } else {
                    state = "IGNORED";
                }
            } else if ("REFUND_REVERSED".equals(type)) {
                reviewReversal(fact(decoded));
                state = "APPLIED";
            } else {
                state = "IGNORED";
            }
            String finalState = state;
            inboxTx.execute(s -> mapper.notificationState(uuid, finalState, null));
            return Map.of("state", state, "notificationUUID", uuid);
        } catch (RuntimeException e) {
            String code = e instanceof PlatformPassException p ? p.getReasonCode() : "APPLE_NOTIFICATION_PROCESSING_FAILED";
            inboxTx.execute(s -> mapper.notificationState(uuid, "PENDING", code));
            log.warn("[通行证内购] 通知未处理,等待 Apple 重投 uuid={} type={} reason={}", uuid, type, code);
            throw new PlatformPassException(code, 503, "通知暂未处理");
        }
    }

    /** 退款 / 撤销:收回本单那一段;先于开通到达也会把本单钉死,之后永不开通。幂等。 */
    void revoke(Fact fact, Long signedDate) {
        Date revokedAt = fact.revokedAt() != null ? fact.revokedAt()
                : signedDate != null && signedDate > 0 ? new Date(signedDate) : new Date();
        tx.execute(s -> {
            Map<String, Object> apple = mapper.selectByTransactionForUpdate(fact.transactionId());
            if (apple == null) {
                Map<String, Object> byToken = mapper.selectByTokenForUpdate(fact.appAccountToken());
                if (byToken == null || !fact.productId().equals(byToken.get("product_id"))
                        || (byToken.get("transaction_id") != null && !fact.transactionId().equals(byToken.get("transaction_id")))) {
                    throw PlatformPassException.unavailable("APPLE_ORDER_NOT_MATCHED", "订单未找到");
                }
                mapper.bindTransaction(str(byToken.get("order_no")), fact.transactionId(), fact.originalTransactionId(),
                        fact.price(), fact.currency(), fact.purchaseDate());
                apple = byToken;
            }
            String orderNo = str(apple.get("order_no"));
            if ("REVOKED".equals(apple.get("state")) || "REVIEW".equals(apple.get("state"))) return null;
            Map<String, Object> order = passMapper.selectOrderByNoForUpdate(orderNo);
            if (order != null) {
                int status = PlatformPassService.num(order.get("status")).intValue();
                if (status == PlatformPassService.PAID) {
                    pass.finalizeAppleRefund(order, revokedAt);
                } else if (status == PlatformPassService.PENDING) {
                    passMapper.markCancelled(PlatformPassService.num(order.get("id")),
                            PlatformPassService.num(order.get("member_id")), new Date());
                }
            }
            mapper.markRevoked(orderNo, revokedAt, fact.revocationReason());
            return null;
        });
    }

    /** Apple 撤回了退款:不自动重开(可能已被别的订单顺延),标记待人工复核。 */
    private void reviewReversal(Fact fact) {
        tx.execute(s -> {
            Map<String, Object> apple = mapper.selectByTransactionForUpdate(fact.transactionId());
            if (apple == null) throw PlatformPassException.unavailable("APPLE_ORDER_NOT_MATCHED", "订单未找到");
            mapper.markReview(str(apple.get("order_no")));
            log.warn("[通行证内购] Apple 撤回退款,待人工复核 orderNo={}", apple.get("order_no"));
            return null;
        });
    }

    // ------------------------------------------------------------------ 事实

    record Fact(String transactionId, String originalTransactionId, String bundleId, String environment,
                String productId, String appAccountToken, Long price, String currency, Date purchaseDate,
                Date revokedAt, Integer revocationReason) { }

    /**
     * SDK 已验过签名链、bundle id 与环境;这里再逐项比对一次(纵深防御),并只接受「非续期订阅」、本人购买、
     * 数量 1、带服务端签发的 appAccountToken、且商品 ID 在配置里的交易。
     */
    Fact fact(JWSTransactionDecodedPayload data) {
        if (data == null || data.getEnvironment() == null) {
            throw PlatformPassException.conflict("APPLE_TRANSACTION_FACTS_INCOMPLETE", "购买凭证不完整");
        }
        if (!Objects.equals(data.getBundleId(), config.getBundleId())) {
            throw PlatformPassException.conflict("APPLE_BUNDLE_CONFLICT", "购买凭证不属于本 App");
        }
        if (!Objects.equals(data.getEnvironment().getValue(), verifier.configuredEnvironment())) {
            throw PlatformPassException.conflict("APPLE_ENVIRONMENT_CONFLICT", "购买环境不符");
        }
        if (data.getType() != Type.NON_RENEWING_SUBSCRIPTION
                || (data.getInAppOwnershipType() != null && data.getInAppOwnershipType() != InAppOwnershipType.PURCHASED)
                || (data.getQuantity() != null && data.getQuantity() != 1)) {
            throw PlatformPassException.conflict("APPLE_PRODUCT_TYPE_UNSUPPORTED", "该商品不是通行证");
        }
        if (config.planForProduct(data.getProductId()) == null) {
            throw PlatformPassException.conflict("APPLE_PRODUCT_UNKNOWN", "该商品不是通行证");
        }
        if (data.getTransactionId() == null || !data.getTransactionId().matches("[0-9]{1,64}")
                || data.getOriginalTransactionId() == null || !data.getOriginalTransactionId().matches("[0-9]{1,64}")
                || data.getAppAccountToken() == null || data.getPurchaseDate() == null || data.getPurchaseDate() <= 0) {
            throw PlatformPassException.conflict("APPLE_TRANSACTION_FACTS_INCOMPLETE", "购买凭证不完整");
        }
        String currency = data.getCurrency() != null && data.getCurrency().matches("[A-Z]{3}") ? data.getCurrency() : null;
        Date purchase = new Date(data.getPurchaseDate());
        Date now = new Date();
        return new Fact(data.getTransactionId(), data.getOriginalTransactionId(), data.getBundleId(),
                data.getEnvironment().getValue(), data.getProductId(), data.getAppAccountToken().toString(),
                data.getPrice(), currency, purchase.after(now) ? now : purchase,
                data.getRevocationDate() == null ? null : new Date(data.getRevocationDate()),
                data.getRawRevocationReason());
    }

    // ------------------------------------------------------------------ 视图

    Map<String, Object> orderView(Map<String, Object> row) {
        Map<String, Object> view = pass.orderView(row);
        Map<String, Object> apple = mapper.selectByOrderNo(str(row.get("order_no")));
        if (apple != null) {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("productId", apple.get("product_id"));
            facts.put("appAccountToken", apple.get("app_account_token"));
            facts.put("environment", apple.get("environment"));
            facts.put("state", apple.get("state"));
            facts.put("transactionId", apple.get("transaction_id"));
            view.put("apple", facts);
        }
        return view;
    }

    private static String uuid(String v) {
        try {
            String canonical = UUID.fromString(v).toString();
            if (!canonical.equalsIgnoreCase(v)) throw new IllegalArgumentException();
            return canonical;
        } catch (Exception e) {
            throw PlatformPassException.badRequest("APPLE_NOTIFICATION_UUID_INVALID", "通知编号无效");
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}

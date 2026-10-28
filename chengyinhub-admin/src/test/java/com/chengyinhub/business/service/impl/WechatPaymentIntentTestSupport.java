package com.chengyinhub.business.service.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.*;

/** In-memory JDBC double for gateway unit tests only; not a MySQL, isolation or transaction proof. */
final class WechatPaymentIntentTestSupport extends JdbcTemplate {
    final Map<String, BigDecimal> businessAmounts;
    final Map<String, Map<String, Object>> intents = new HashMap<>();
    int commits;

    private WechatPaymentIntentTestSupport(Map<String, BigDecimal> amounts) {
        businessAmounts = amounts;
    }

    static WechatPaymentIntentTestSupport install(WechatPayServiceImpl service, Map<String, BigDecimal> amounts) {
        var jdbc = new WechatPaymentIntentTestSupport(amounts);
        if (WechatVerifiedFactTestSupport.getField(service, "jsapiService") == null)
            WechatVerifiedFactTestSupport.setField(service, "jsapiService", absentOrderSdk());
        WechatVerifiedFactTestSupport.setField(service, "paymentIntentJdbc", jdbc);
        WechatVerifiedFactTestSupport.setField(service, "paymentIntentTransactions", new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                if (definition.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRES_NEW
                        && definition.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED) {
                    throw new AssertionError("intent must commit independently before SDK");
                }
                return new SimpleTransactionStatus();
            }
            public void commit(TransactionStatus status) { jdbc.commits++; }
            public void rollback(TransactionStatus status) { /* No MySQL rollback semantics are claimed. */ }
        });
        return jdbc;
    }

    static com.wechat.pay.java.service.payments.jsapi.JsapiService absentOrderSdk() {
        var sdk = org.mockito.Mockito.mock(com.wechat.pay.java.service.payments.jsapi.JsapiService.class);
        var missing = org.mockito.Mockito.mock(com.wechat.pay.java.core.exception.ServiceException.class);
        org.mockito.Mockito.when(missing.getHttpStatusCode()).thenReturn(404);
        org.mockito.Mockito.when(missing.getErrorCode()).thenReturn("ORDER_NOT_EXIST");
        org.mockito.Mockito.lenient().when(sdk.queryOrderByOutTradeNo(org.mockito.ArgumentMatchers.any())).thenThrow(missing);
        return sdk;
    }

    @Override
    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        if (sql.contains("FROM wx_pay_order_intent")) {
            if (sql.contains("WHERE biz_type=")) {
                var result = new ArrayList<Map<String, Object>>();
                for (var row : intents.values()) {
                    if (Objects.equals(args[0], row.get("biz_type")) && Objects.equals(args[1], row.get("biz_id"))
                            && (!sql.contains("closed_confirmed_at IS NULL") || row.get("closed_confirmed_at") == null))
                        result.add(new HashMap<>(row));
                }
                result.sort(Comparator.comparingInt((Map<String, Object> r) -> ((Number) r.get("attempt_no")).intValue()).reversed());
                return sql.contains("LIMIT 1") && result.size() > 1 ? List.of(result.get(0)) : result;
            }
            if (sql.contains("WHERE mchid=? AND business_out_trade_no=")) {
                var result = new ArrayList<Map<String, Object>>();
                for (var row : intents.values()) if (Objects.equals(args[0], row.get("mchid"))
                        && Objects.equals(args[1], row.get("business_out_trade_no"))) result.add(new HashMap<>(row));
                result.sort(Comparator.comparingInt((Map<String, Object> r) -> ((Number) r.getOrDefault("attempt_no", 1)).intValue()).reversed());
                return result.isEmpty() ? List.of() : List.of(result.get(0));
            }
            Map<String, Object> row = intents.get(args[0] + ":" + args[1]);
            return row == null ? List.of() : List.of(new HashMap<>(row));
        }
        String order = (String) args[0];
        BigDecimal amount = businessAmounts.get(order);
        if (amount == null) return List.of();
        return List.of(Map.of("id", 1L, "payable", 1, "amount", sql.contains("club_membership_invoice")
                ? amount.movePointRight(2).longValueExact() : amount));
    }

    @Override
    public int update(String sql, Object... args) {
        if (sql.startsWith("INSERT INTO wx_pay_order_intent")) {
            String key = args[0] + ":" + args[1];
            if (intents.containsKey(key)) return 0;
            for (Map<String, Object> existing : intents.values()) {
                if (existing.get("biz_type").equals(args[3])
                        && existing.get("biz_id").equals(args[4]) && existing.get("closed_confirmed_at") == null) return 0;
            }
            Map<String, Object> row = new HashMap<>();
            String[] columns = {"mchid", "out_trade_no", "business_out_trade_no", "biz_type", "biz_id", "attempt_no", "appid", "trade_type", "currency", "total_fen", "request_hash", "payer_openid", "request_description", "notify_url"};
            for (int i = 0; i < columns.length; i++) row.put(columns[i], args[i]);
            intents.put(key, row);
            return 1;
        }
        if (sql.startsWith("UPDATE wx_pay_order_intent SET closed_confirmed_at=")) {
            Map<String, Object> row = intents.get(args[0] + ":" + args[1]);
            if (row == null) return 0;
            row.putIfAbsent("closed_confirmed_at", new java.sql.Timestamp(1));
            return 1;
        }
        throw new AssertionError("Unexpected SQL in gateway test double: " + sql);
    }
}

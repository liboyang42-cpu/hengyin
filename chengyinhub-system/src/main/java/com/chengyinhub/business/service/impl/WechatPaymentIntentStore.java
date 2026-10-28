package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.config.RestoreDispatchGuard;

import com.chengyinhub.business.service.PaymentPreorderException;
import com.chengyinhub.business.service.WechatPaymentOrderOwner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Permanent intent identities. The domain row lock precedes the latest-attempt lock in every path. */
@Component
public class WechatPaymentIntentStore {
    @Autowired private org.springframework.jdbc.core.JdbcTemplate paymentIntentJdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager paymentIntentTransactions;
    @Autowired private WechatPaymentOrderOwner businessOwner;
    @Autowired private WechatVerifiedPaymentFactService verifiedFacts;

    String freezePaymentIntent(String outTradeNo, String mchid, String appid, String tradeType,
                             String currency, Integer totalFen, String openid, String description, String notifyUrl, Runnable verifyHistoricalOrder) {
        RestoreDispatchGuard.requireDispatchAllowed();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw PaymentPreorderException.resultUncertain("支付调用必须在领域意图提交后、事务外执行", null);
        }
        if (outTradeNo == null || outTradeNo.isBlank() || mchid == null || mchid.isBlank()
                || appid == null || appid.isBlank() || totalFen == null || totalFen <= 0 || !"CNY".equals(currency)
                || !("APP".equals(tradeType) || "JSAPI".equals(tradeType))) {
            throw PaymentPreorderException.definiteFailure("支付意图必填事实不完整", null);
        }
        final String fingerprint;
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream encoded = new java.io.DataOutputStream(bytes);
            for (String part : new String[]{outTradeNo, mchid, appid, tradeType, currency, totalFen.toString(),
                    openid == null ? "" : openid, description == null ? "" : description, notifyUrl == null ? "" : notifyUrl}) {
                byte[] value = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                encoded.writeInt(value.length);
                encoded.write(value);
            }
            fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception error) {
            throw PaymentPreorderException.resultUncertain("支付原请求指纹无法建立", error);
        }
        try {
            // Without a local frozen intent, an existing provider order is historical evidence, not permission
            // to overwrite its channel/request. Recovery must query/close it or use retained original params.
            // Valid legacy external numbers are <=32; longer domain numbers were never valid WeChat numbers.
            var known = paymentIntentJdbc.queryForList("SELECT out_trade_no FROM wx_pay_order_intent WHERE mchid=? AND business_out_trade_no=? ORDER BY attempt_no DESC LIMIT 1", mchid, outTradeNo);
            if (known.isEmpty() && outTradeNo.matches("[A-Za-z0-9_-]{1,32}")) {
                verifyHistoricalOrder.run();
            }
            var transaction = new org.springframework.transaction.support.TransactionTemplate(paymentIntentTransactions);
            transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return transaction.execute(status -> {
                WechatPaymentOrderOwner.Snapshot business = businessOwner.lock(outTradeNo);
                if (!business.payable()) throw new IllegalStateException("业务订单已不可支付，不能提交旧准备结果");
                String bizType = business.type();
                Number bizId = business.id();
                long expectedFen = business.cents() ? business.amount().longValueExact()
                        : business.amount().movePointRight(2).longValueExact();
                if (expectedFen != totalFen.longValue()) throw new IllegalStateException("支付金额与业务冻结金额不符");
                if (verifiedFacts.hasConfirmedExternalSuccess(mchid,outTradeNo))
                    throw new IllegalStateException("微信成功事实已持久化，须恢复原业务结果，不能再次预下单或换渠道");
                var active = paymentIntentJdbc.queryForList("SELECT * FROM wx_pay_order_intent WHERE biz_type=? AND biz_id=? AND closed_confirmed_at IS NULL FOR UPDATE", bizType, bizId.longValue());
                var previous = paymentIntentJdbc.queryForList("SELECT * FROM wx_pay_order_intent WHERE biz_type=? AND biz_id=? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE", bizType, bizId.longValue());
                int attempt;
                String wxOrderNo;
                if (!active.isEmpty()) {
                    var original = active.get(0);
                    if (!mchid.equals(original.get("mchid")) || !outTradeNo.equals(original.get("business_out_trade_no")))
                        throw new IllegalStateException("未关闭原意图的业务身份不能替换");
                    attempt = Math.toIntExact(((Number) original.get("attempt_no")).longValue());
                    wxOrderNo = (String) original.get("out_trade_no");
                } else {
                    attempt = 1;
                    if (!previous.isEmpty()) {
                        var prior = previous.get(0);
                        if (prior.get("closed_confirmed_at") == null || !mchid.equals(prior.get("mchid"))
                                || !outTradeNo.equals(prior.get("business_out_trade_no"))
                                || ((Number) prior.get("total_fen")).longValue() != totalFen.longValue())
                            throw new IllegalStateException("未确认关闭或金额/商户变化不能新发起");
                        attempt = Math.addExact(Math.toIntExact(((Number) prior.get("attempt_no")).longValue()), 1);
                    }
                    wxOrderNo = attempt == 1 && outTradeNo.matches("[A-Za-z0-9_-]{1,32}") ? outTradeNo
                            : "WXP" + sha256(mchid + ":" + bizType + ":" + bizId + ":" + attempt + ":" + outTradeNo).substring(0, 29);
                }
                String wireHash = sha256(wxOrderNo + ":" + fingerprint);
                paymentIntentJdbc.update("INSERT INTO wx_pay_order_intent(mchid,out_trade_no,business_out_trade_no,biz_type,biz_id,attempt_no,appid,trade_type,currency,total_fen,request_hash,payer_openid,request_description,notify_url) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE out_trade_no=out_trade_no",
                        mchid, wxOrderNo, outTradeNo, bizType, bizId.longValue(), attempt, appid, tradeType, currency, totalFen.longValue(), wireHash, openid == null ? "" : openid, description == null ? "" : description, notifyUrl);
                var rows = paymentIntentJdbc.queryForList("SELECT * FROM wx_pay_order_intent WHERE mchid=? AND out_trade_no=? FOR UPDATE", mchid, wxOrderNo);
                if (rows.size() != 1) throw new IllegalStateException("原业务仍有未确认关闭的支付意图");
                var row = rows.get(0);
                if (!bizType.equals(row.get("biz_type")) || !(row.get("biz_id") instanceof Number storedId)
                        || storedId.longValue() != bizId.longValue() || !wireHash.equals(row.get("request_hash"))
                        || !appid.equals(row.get("appid")) || !tradeType.equals(row.get("trade_type"))
                        || !currency.equals(row.get("currency")) || !(row.get("total_fen") instanceof Number storedFen)
                        || storedFen.longValue() != totalFen.longValue()
                        || row.get("closed_confirmed_at") != null) {
                    throw new IllegalStateException("支付渠道/参数已锁定，关闭原单前不能改号或换渠道；关闭原号也不得复用");
                }
                return wxOrderNo;
            });
        } catch (RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (Exception error) {
            // 原意图可能已经调用过微信；本次拒绝不能释放原 PAYING 或变更原身份。
            throw PaymentPreorderException.resultUncertain("支付永久意图未确认或与首次提交冲突", error);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    String currentWechatPaymentNo(String businessOrderNo, String mchid) {
        var rows = paymentIntentJdbc.queryForList("SELECT out_trade_no FROM wx_pay_order_intent WHERE mchid=? AND business_out_trade_no=? ORDER BY attempt_no DESC LIMIT 1",
                mchid, businessOrderNo);
        return rows.isEmpty() ? businessOrderNo : (String) rows.get(0).get("out_trade_no");
    }

    String businessPaymentOrderNo(String wxOrderNo, String mchid) {
        var rows = paymentIntentJdbc.queryForList("SELECT business_out_trade_no FROM wx_pay_order_intent WHERE mchid=? AND out_trade_no=?",
                mchid, wxOrderNo);
        if (rows.isEmpty()) return wxOrderNo; // Legacy existing order: still verified against the actual WeChat order.
        Object value = rows.get(0).get("business_out_trade_no");
        if (!(value instanceof String no) || no.isBlank()) throw new IllegalStateException("永久支付身份映射缺失");
        return no;
    }

    void recordConfirmedClosedAttempt(String mchid, String wxNo) {
        String businessNo = businessPaymentOrderNo(wxNo, mchid);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(paymentIntentTransactions);
        transaction.execute(status -> {
            businessOwner.lock(businessNo);
            if (verifiedFacts.hasConfirmedExternalSuccess(mchid,businessNo))
                throw new IllegalStateException("原业务已有微信成功事实，不得释放原支付身份");
            paymentIntentJdbc.update(
                    "UPDATE wx_pay_order_intent SET closed_confirmed_at=COALESCE(closed_confirmed_at,CURRENT_TIMESTAMP(6)) WHERE mchid=? AND out_trade_no=?",
                    mchid, wxNo);
            return null;
        });
    }

    boolean currentAttemptClosable(String businessNo,String observedWxNo,String mchid) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("关单SDK调用须在原业务事务外");
        var transaction=new org.springframework.transaction.support.TransactionTemplate(paymentIntentTransactions);
        return Boolean.TRUE.equals(transaction.execute(status -> lockClosablePaymentAttemptForUpdate(businessNo,observedWxNo,mchid)));
    }

    boolean lockClosablePaymentAttemptForUpdate(String businessNo,String observedWxNo,String mchid) {
        return lockPaymentAttemptForUpdate(businessNo,observedWxNo,mchid)
                && !verifiedFacts.hasConfirmedExternalSuccess(mchid,businessNo);
    }

    /** For an owner already holding its original business transaction; no SDK call or channel selection. */
    boolean lockClosedPaymentAttemptForRetry(String businessNo) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("支付重试事实核验必须与原业务准备共用事务");
        if (businessNo==null || businessNo.isBlank()) return false;
        WechatPaymentOrderOwner.Snapshot business=businessOwner.lock(businessNo);
        if (verifiedFacts.hasConfirmedExternalSuccess(businessNo)) return false;
        var rows=paymentIntentJdbc.queryForList(
                "SELECT * FROM wx_pay_order_intent WHERE biz_type=? AND biz_id=? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE",
                business.type(),business.id().longValue());
        if (rows.size()!=1) return false;
        var original=rows.get(0);
        long expectedFen=business.cents()?business.amount().longValueExact():business.amount().movePointRight(2).longValueExact();
        return businessNo.equals(original.get("business_out_trade_no"))
                && business.type().equals(original.get("biz_type"))
                && original.get("biz_id") instanceof Number id && id.longValue()==business.id().longValue()
                && original.get("mchid") instanceof String merchant && !merchant.isBlank()
                && original.get("out_trade_no") instanceof String wxNo && !wxNo.isBlank()
                && original.get("closed_confirmed_at")!=null
                && original.get("total_fen") instanceof Number total && expectedFen>0 && total.longValue()==expectedFen
                && "CNY".equals(original.get("currency"))
                && ("APP".equals(original.get("trade_type")) || "JSAPI".equals(original.get("trade_type")))
                && original.get("appid") instanceof String appid && !appid.isBlank()
                && original.get("request_hash") instanceof String hash && hash.matches("[0-9a-f]{64}");
    }

    boolean lockPaymentAttemptForUpdate(String businessOutTradeNo, String observedWxOutTradeNo, String mchid) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("支付尝试校验必须与领域关闭CAS共用事务");
        }
        if (businessOutTradeNo == null || observedWxOutTradeNo == null || observedWxOutTradeNo.isBlank()) return false;
        WechatPaymentOrderOwner.Snapshot business = businessOwner.lock(businessOutTradeNo);
        var rows = paymentIntentJdbc.queryForList(
                "SELECT * FROM wx_pay_order_intent WHERE biz_type=? AND biz_id=? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE",
                business.type(), business.id().longValue());
        if (rows.isEmpty()) return businessOutTradeNo.equals(observedWxOutTradeNo); // Verified historical provider number.
        var current = rows.get(0);
        return observedWxOutTradeNo.equals(current.get("out_trade_no"))
                && businessOutTradeNo.equals(current.get("business_out_trade_no"))
                && mchid.equals(current.get("mchid"));
    }

    java.util.List<java.util.Map<String, Object>> frozenFacts(String mchid, String outTradeNo) {
        return paymentIntentJdbc.queryForList(
                "SELECT appid,trade_type,currency,total_fen FROM wx_pay_order_intent WHERE mchid=? AND out_trade_no=?", mchid, outTradeNo);
    }
    String frozenRequestHash(String mchid, String wxNo) {
        var rows = paymentIntentJdbc.queryForList("SELECT request_hash FROM wx_pay_order_intent WHERE mchid=? AND out_trade_no=?",
                mchid,wxNo);
        if (rows.isEmpty()) return null; // Historical provider original order, never a guessed configuration backfill.
        if (rows.size()!=1 || !(rows.get(0).get("request_hash") instanceof String hash) || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("原意图请求指纹缺失或冲突");
        }
        return hash;
    }
}

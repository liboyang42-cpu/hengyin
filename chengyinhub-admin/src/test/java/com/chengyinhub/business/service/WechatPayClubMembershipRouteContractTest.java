package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WechatPayClubMembershipRouteContractTest {
    @Test
    void clmCallbackRoutesBeforeOtherBusinessAndPassesExactAmountAndTransaction() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WechatPaymentCallbackRouter.java")),
                StandardCharsets.UTF_8);
        int membership = source.indexOf("new Owner(\"club-membership\"");
        int commerce = source.indexOf("new Owner(\"merchant-commerce\"");
        assertTrue(membership > 0 && commerce > membership,
                "CLM 专属订单必须在活动票/商家订单前路由，不能掉进 registration callback");
        assertTrue(source.contains("club.handlePaidInvoice(facts.get(\"out_trade_no\"), paid(facts),")
                && source.contains("cents(facts), facts.get(\"transaction_id\")"));
        assertTrue(source.contains("facts.get(\"transaction_id\"), verifiedPaymentReference(facts)"),
                "CLM 与商城必须转交持久原成功引用，不能回退可写 raw 参数入口");
        assertTrue(source.contains("return \"SUCCESS\".equals(facts.get(\"trade_state\"))"),
                "会员回调必须严格认 SUCCESS，不能宽松猜测支付成功");
        assertTrue(source.contains("club != null && club.supportsOutTradeNo(facts.get(\"out_trade_no\"))"),
                "未知或未装配的 CLM 订单必须 FAIL，不能降级给活动报名回调");
    }
}

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
                "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WechatPayServiceImpl.java")),
                StandardCharsets.UTF_8);
        int membership = source.indexOf("clubMembershipService.supportsOutTradeNo(otn)");
        int commerce = source.indexOf("merchantCommerceService.supportsOutTradeNo(otn)");
        assertTrue(membership > 0 && commerce > membership,
                "CLM 专属订单必须在活动票/商家订单前路由，不能掉进 registration callback");
        assertTrue(source.contains("clubMembershipService.handlePaidInvoice(otn, paid, paidAmountFen"));
        assertTrue(source.contains("transaction.getTransactionId()"));
        assertTrue(source.contains("boolean paid = \"SUCCESS\".equals(resultMap.get(\"trade_state\"))"),
                "会员回调必须严格认 SUCCESS，不能宽松猜测支付成功");
        assertTrue(source.contains("clubMembershipService != null && clubMembershipService.supportsOutTradeNo(otn)"),
                "未知或未装配的 CLM 订单必须 FAIL，不能降级给活动报名回调");
    }
}

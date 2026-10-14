package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合同二闸3接线守卫：时间策略本身正确还不够，四条售卖路径都必须实际调用它。
 */
class RegistrationSaleAvailabilityWiringTest {

    private static final String REGISTRATION =
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java";

    @Test
    void everySalePathInvokesTheSingleAvailabilityPolicy() throws Exception {
        String source = WiringSource.read(REGISTRATION).replaceAll("\\s+", "");

        // F03:活动路径统一经 assertRegistrationSellable 分流(商业→合同二闸,免费 UGC→上架事实),
        // 该方法本身必须落回合同二闸。行为契约见 FreeUgcActivityRegistrationGateTest。
        // F-W1-05:非免费 UGC 分支先给出「未开售」明确提示，再落回合同二售卖闸。
        assertTrue(source.contains("assertCommercialSaleOpen(topic,ticket,now);return;"),
                "统一可售判定的非免费 UGC 分支必须经过商业可售闸");
        assertTrue(source.replaceAll("(?s).*privatevoidassertCommercialSaleOpen\\(", "").startsWith("CmsTopictopic,OmsTicketticket,Datenow){")
                        && source.contains("SaleAvailabilityPolicy.assertSellable(topic,ticket,now);}"),
                "商业可售闸必须落回合同二售卖闸");
        assertTrue(source.contains("assertRegistrationSellable(registrationActivity,sellerTopic,ticket,newDate())"),
                "普通下单必须经过统一可售闸");
        assertTrue(source.contains("assertRegistrationSellable(activity,sellerTopic,ticket,newDate())"),
                "报价必须与下单同一可售闸");
        assertTrue(source.contains("assertRegistrationSellable(activity,sellerTopic,ticket,now)"),
                "幂等支付重试必须重新经过统一可售闸");
        assertTrue(source.contains("assertRegistrationSellable(activity,sellerTopic,null,now)"),
                "无 ticket 的支付重试必须经过统一可售闸");
        assertTrue(source.contains("SaleAvailabilityPolicy.assertSellable(topic,null,newDate())"),
                "自玩通行证不得缺少统一可售闸");
    }
}

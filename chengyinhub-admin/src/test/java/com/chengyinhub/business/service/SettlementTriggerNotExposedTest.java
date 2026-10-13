package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * P0-28 安全接线测试:App 面公共控制器 {@code ApiCommonController} 不得暴露结算触发。
 *
 * <p>原 /api/common/{topic,activity}_clear 无 @PreAuthorize、落在 /api/ App 鉴权命名空间下,
 * 任何登录会员(含买家)即可 POST 触发全平台结算 —— 越权。已下线;结算由 settlementTask 定时任务承担。
 *
 * <p>本测试守「别再把结算触发加回 App 面公共控制器」。若确需人工触发,应另建后台
 * {@code @PreAuthorize} 管理端点,不放在 /api/common。
 *
 * ⚠️ 负控:把 executeTopicSettlement 的调用加回 ApiCommonController ⇒ 本测试红。
 */
class SettlementTriggerNotExposedTest {

    private static final String API_COMMON =
            "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCommonController.java";

    @Test
    void apiCommonMustNotTriggerSettlement() throws Exception {
        String src = WiringSource.read(API_COMMON).replaceAll("\\s+", "");
        assertFalse(src.contains("executeTopicSettlement()"),
                "ApiCommonController 不得调用 executeTopicSettlement —— App 面无鉴权结算触发=越权,应走后台管理端点");
        assertFalse(src.contains("executeActivitySettlement()"),
                "ApiCommonController 不得调用 executeActivitySettlement —— 同上");
    }
}

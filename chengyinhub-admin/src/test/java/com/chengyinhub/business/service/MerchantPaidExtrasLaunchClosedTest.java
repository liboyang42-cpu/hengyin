package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.service.impl.MerchantCommerceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ROUND10 f4 第 3 项:商家装修页的付费权益(高级模板、推广曝光位、品牌主页、活动定制)上线时保持关闭。
 *
 * <p>闸就是现有开关 {@code merchant.commerce.self-checkout-enabled}:默认 false,生产不设
 * {@code CHENGYIN_MERCHANT_SELF_CHECKOUT_ENABLED} 即关。关着时后端在建单前拒绝,客户端只给「联系运营开通」。
 * 有人把默认值改成 true 时这里先红。
 */
class MerchantPaidExtrasLaunchClosedTest {

    @Test
    void selfCheckoutSwitchDefaultsOffInShippedConfig() throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName() != null && "chengyinhub-admin".equals(root.getFileName().toString())) {
            root = root.getParent();
        }
        String yml = new String(Files.readAllBytes(root.resolve(
                "chengyinhub-admin/src/main/resources/application.yml")), StandardCharsets.UTF_8);
        assertTrue(yml.contains("self-checkout-enabled: ${CHENGYIN_MERCHANT_SELF_CHECKOUT_ENABLED:false}"),
                "付费权益自助开通默认必须关闭");
    }

    @Test
    void closedSwitchRefusesEveryPaidExtraBeforeAnyOrderIsWritten() {
        MerchantCommerceService service = new MerchantCommerceService(false);
        OmsOrderMapper orders = mock(OmsOrderMapper.class);
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        for (String bizType : new String[]{"premium_template", "promotion_slot", "brand_home", "custom_event"}) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> service.createOrder(7L, 8L, "openid", bizType, "commerce_req_" + bizType));
            assertEquals("自助开通暂未开放，请联系官方人工开通", refused.getMessage());
        }
        verifyNoInteractions(orders);
    }
}

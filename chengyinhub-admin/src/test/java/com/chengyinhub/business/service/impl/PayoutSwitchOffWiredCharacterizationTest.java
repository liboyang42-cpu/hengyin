package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.EcomPayoutMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.payout.EcommercePayGateway;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.EcommerceSellerResolver;
import com.chengyinhub.business.payout.EcommerceSettlementGuard;
import com.chengyinhub.business.payout.PayoutSwitches;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * l-payout2 特征测试（接线后）：装上全部新接线、开关全关，复跑父类三条金标准，结果必须逐字相同；
 * 且新表 mapper、电商收付通网关、卖家判定、分账服务、意图 / 事实存储的新入口一次都没有被调用。
 */
class PayoutSwitchOffWiredCharacterizationTest extends PayoutSwitchOffCharacterizationTest {

    private final EcomPayoutMapper payoutMapper = mock(EcomPayoutMapper.class);
    private final EcommercePayGateway ecommerceGateway = mock(EcommercePayGateway.class);
    private final EcommerceSellerResolver resolver = mock(EcommerceSellerResolver.class);
    private final EcommerceProfitShareService profitSharing = mock(EcommerceProfitShareService.class);
    private final WechatPaymentIntentStore partnerIntents = mock(WechatPaymentIntentStore.class);
    private final WechatVerifiedPaymentFactService partnerFacts = mock(WechatVerifiedPaymentFactService.class);
    private final UmsMemberMapper partnerMembers = mock(UmsMemberMapper.class);

    private EcommercePartnerPaymentAdapter adapterOff() {
        return new EcommercePartnerPaymentAdapter(PayoutSwitches.allOff(), payoutMapper, ecommerceGateway, resolver,
                profitSharing, partnerIntents, partnerFacts, partnerMembers);
    }

    @Override
    protected void customizeGateway(WechatPayServiceImpl service) {
        Object gateway = ReflectionTestUtils.getField(service, "gateway");
        ReflectionTestUtils.setField(gateway, "partner", adapterOff());
        ReflectionTestUtils.setField(service, "ecommerceGateway", ecommerceGateway);
    }

    @Override
    protected void customizeTopicService(CmsTopicServiceImpl service) {
        ReflectionTestUtils.setField(service, "ecommerceSettlementGuard",
                new EcommerceSettlementGuard(PayoutSwitches.allOff(), payoutMapper, profitSharing));
    }

    @Override
    protected void afterScenario() {
        verifyNoInteractions(payoutMapper, ecommerceGateway, resolver, profitSharing, partnerIntents, partnerFacts, partnerMembers);
    }

    @Test
    void switchOffCallbacksAnswerFailWithoutTouchingAnything() {
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        customizeGateway(service);
        String fail = "{\"code\": \"FAIL\",\"message\": \"未开通\"}";
        assertEquals(fail, service.handleEcommercePayCallback("{}", java.util.Map.of()));
        assertEquals(fail, service.handleEcommerceRefundCallback("{}", java.util.Map.of()));
        afterScenario();
    }

    @Test
    void switchOffSettlementGuardIsInertAndReadsNothing() {
        EcommerceSettlementGuard guard = new EcommerceSettlementGuard(PayoutSwitches.allOff(), payoutMapper, profitSharing);
        assertFalse(guard.active());
        assertEquals(java.math.BigDecimal.ZERO, guard.partnerPortion(700L));
        assertFalse(guard.isPartnerPaymentFact(1L));
        guard.afterTopicSettled(200L);
        afterScenario();
    }
}

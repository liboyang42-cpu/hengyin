package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.EcomCombineMapper;
import com.chengyinhub.business.mapper.EcomPayoutMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.payout.EcommerceCombinedSettlementService;
import com.chengyinhub.business.payout.EcommercePayGateway;
import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.EcommerceSellerResolver;
import com.chengyinhub.business.payout.EcommerceSettlementGuard;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.service.IPricingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * l-payout3 特征测试：装上 l-payout2 全部接线 + 本线合单组件（合单适配、合单结算、结算接点的合单 mapper）、开关全关，
 * 复跑父类三条金标准（报名下单请求、原路退款请求、T+14 入余额），结果必须逐字相同；
 * 且合单表 mapper、合单结算、电商网关、卖家判定、合作单 / 费率读取一次都没有被调用。
 */
class PayoutCombineSwitchOffCharacterizationTest extends PayoutSwitchOffCharacterizationTest {

    private final EcomPayoutMapper payoutMapper = mock(EcomPayoutMapper.class);
    private final EcomCombineMapper combineMapper = mock(EcomCombineMapper.class);
    private final EcommercePayGateway ecommerceGateway = mock(EcommercePayGateway.class);
    private final EcommerceSellerResolver resolver = mock(EcommerceSellerResolver.class);
    private final EcommerceProfitShareService profitSharing = mock(EcommerceProfitShareService.class);
    private final EcommerceCombinedSettlementService combinedSettlement = mock(EcommerceCombinedSettlementService.class);
    private final WechatPaymentIntentStore partnerIntents = mock(WechatPaymentIntentStore.class);
    private final WechatVerifiedPaymentFactService partnerFacts = mock(WechatVerifiedPaymentFactService.class);
    private final UmsMemberMapper partnerMembers = mock(UmsMemberMapper.class);
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CoopOrderMapper coopOrders = mock(CoopOrderMapper.class);
    private final IPricingService pricing = mock(IPricingService.class);

    private EcommercePartnerPaymentAdapter adapterOff() {
        EcommercePartnerPaymentAdapter adapter = new EcommercePartnerPaymentAdapter(PayoutSwitches.allOff(), payoutMapper,
                ecommerceGateway, resolver, profitSharing, partnerIntents, partnerFacts, partnerMembers);
        adapter.setCombined(new EcommerceCombinedPayment(PayoutSwitches.allOff(), combineMapper, ecommerceGateway, resolver,
                combinedSettlement, partnerIntents, partnerFacts, partnerMembers, topics, coopOrders, pricing));
        return adapter;
    }

    private EcommerceSettlementGuard guardOff() {
        EcommerceSettlementGuard guard = new EcommerceSettlementGuard(PayoutSwitches.allOff(), payoutMapper, profitSharing);
        guard.setCombined(combineMapper, combinedSettlement);
        return guard;
    }

    @Override
    protected void customizeGateway(WechatPayServiceImpl service) {
        Object gateway = ReflectionTestUtils.getField(service, "gateway");
        ReflectionTestUtils.setField(gateway, "partner", adapterOff());
        ReflectionTestUtils.setField(service, "ecommerceGateway", ecommerceGateway);
    }

    @Override
    protected void customizeTopicService(CmsTopicServiceImpl service) {
        ReflectionTestUtils.setField(service, "ecommerceSettlementGuard", guardOff());
    }

    @Override
    protected void afterScenario() {
        verifyNoInteractions(payoutMapper, combineMapper, ecommerceGateway, resolver, profitSharing, combinedSettlement,
                partnerIntents, partnerFacts, partnerMembers, topics, coopOrders, pricing);
    }

    @Test
    void switchOffCallbacksIncludingCombineAnswerFailWithoutTouchingAnything() {
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        customizeGateway(service);
        String fail = "{\"code\": \"FAIL\",\"message\": \"未开通\"}";
        assertEquals(fail, service.handleEcommercePayCallback("{}", java.util.Map.of()));
        assertEquals(fail, service.handleEcommerceRefundCallback("{}", java.util.Map.of()));
        assertEquals(fail, service.handleEcommerceCombinePayCallback("{}", java.util.Map.of()));
        afterScenario();
    }

    @Test
    void switchOffGuardWithCombineWiredIsInertAndReadsNothing() {
        EcommerceSettlementGuard guard = guardOff();
        assertFalse(guard.active());
        assertEquals(java.math.BigDecimal.ZERO, guard.partnerPortion(700L));
        assertFalse(guard.isPartnerPaymentFact(1L));
        guard.afterTopicSettled(200L);
        afterScenario();
    }
}

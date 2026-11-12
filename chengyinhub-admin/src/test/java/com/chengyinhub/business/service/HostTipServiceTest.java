package com.chengyinhub.business.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.chengyinhub.business.domain.HostTipConfig;
import com.chengyinhub.business.domain.HostTipIntent;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.HostTipConfigRequest;
import com.chengyinhub.business.domain.dto.HostTipPrepareRequest;
import com.chengyinhub.business.exception.BountyTipException;
import com.chengyinhub.business.mapper.HostTipConfigMapper;
import com.chengyinhub.business.mapper.HostTipIntentMapper;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.HostTipServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主持小费服务契约。
 *
 * <p>守的是<b>不伪造收款</b>:没有受支持的通道时,准备接口必须返回可区分的
 * "通道未启用",并且不能给任何余额加钱、不能把状态写成已付。
 *
 * <p>注:本测试不证明真实通道回调与资金到账;那需要真实通道与外部验收。
 */
class HostTipServiceTest {

    private static final long PAYER = 21L;
    private static final long HOST = 33L;
    private static final long OTHER = 44L;
    private static final long CONFIG_ID = 9L;

    private HostTipConfigMapper configs;
    private HostTipIntentMapper intents;
    private UmsMemberMapper members;
    private OmsOrderMapper orders;
    private HostTipServiceImpl service;

    @BeforeEach
    void setUp() {
        configs = mock(HostTipConfigMapper.class);
        intents = mock(HostTipIntentMapper.class);
        members = mock(UmsMemberMapper.class);
        orders = mock(OmsOrderMapper.class);
        service = new HostTipServiceImpl(configs, intents, members, orders);

        when(members.selectUmsMemberById(anyLong())).thenAnswer(invocation -> member(invocation.getArgument(0)));
    }

    private static UmsMember member(long id) {
        UmsMember member = new UmsMember();
        member.setId(id);
        member.setStatus(1L);
        return member;
    }

    private static HostTipConfig enabledConfig(String channel) {
        HostTipConfig config = new HostTipConfig();
        config.setId(CONFIG_ID);
        config.setHostMemberId(HOST);
        config.setClubId(0L);
        config.setEnabled(1);
        config.setHostSharePercent(100);
        config.setChannelFeeBearer(HostTipConfig.FEE_BEARER_HOST);
        config.setMinAmount(new BigDecimal("1.00"));
        config.setCurrency("CNY");
        config.setPaymentChannel(channel);
        config.setConfigVersion(1);
        return config;
    }

    private static HostTipPrepareRequest prepareRequest() {
        HostTipPrepareRequest request = new HostTipPrepareRequest();
        request.setHostMemberId(HOST);
        request.setAmount(new BigDecimal("20.00"));
        request.setRequestId("req-1");
        return request;
    }

    // ------------------------------------------------------------------ 未启用通道

    @Test
    void prepareWithUnsupportedChannelRecordsIntentAndRefusesPayment() {
        when(configs.selectByHostAndClub(HOST, 0L)).thenReturn(enabledConfig("unapproved-channel"));
        ArgumentCaptor<HostTipIntent> captor = ArgumentCaptor.forClass(HostTipIntent.class);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.prepare(PAYER, prepareRequest()));

        assertEquals("CHANNEL_NOT_ENABLED", rejection.getReasonCode());
        assertEquals(BountyTipException.CHANNEL_NOT_ENABLED, rejection.getHttpStatus());
        verify(intents).insertIntent(captor.capture());
        HostTipIntent stored = captor.getValue();
        assertEquals(HostTipIntent.STATUS_CHANNEL_UNSUPPORTED, stored.getStatus().intValue(),
                "通道未启用必须落 5,不能落待支付或已支付");
        assertEquals(100, stored.getHostSharePercent().intValue(), "小费 100% 归 host");
        assertTrue(stored.getExternalTradeNo() == null, "未收款就没有交易号");
    }

    @Test
    void prepareWithNoChannelAtAllAlsoRefuses() {
        when(configs.selectByHostAndClub(HOST, 0L)).thenReturn(enabledConfig(null));

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.prepare(PAYER, prepareRequest()));

        assertEquals("CHANNEL_NOT_ENABLED", rejection.getReasonCode());
    }

    @Test
    void confirmPaidCannotBeCalledWithoutSupportedChannel() {
        HostTipIntent intent = new HostTipIntent();
        intent.setId(1L);
        intent.setStatus(HostTipIntent.STATUS_AWAITING_PAYMENT);
        when(intents.selectById(1L)).thenReturn(intent);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.confirmPaidByChannel(1L, "trade-1", "unapproved-channel"));

        assertEquals("CHANNEL_NOT_ENABLED", rejection.getReasonCode());
        verify(intents, never()).markPaidIfAwaiting(anyLong(), anyString());
    }

    @Test
    void enablingConfigWithoutSupportedChannelIsRejected() {
        HostTipConfigRequest request = new HostTipConfigRequest();
        request.setEnabled(true);
        request.setCurrency("CNY");
        request.setPaymentChannel("unapproved-channel");

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.upsertConfig(HOST, request));

        assertEquals("CHANNEL_NOT_ENABLED", rejection.getReasonCode());
        verify(configs, never()).insertConfig(any());
    }

    // ------------------------------------------------------------------ 校验

    @Test
    void cannotTipYourself() {
        HostTipPrepareRequest request = prepareRequest();
        request.setHostMemberId(PAYER);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.prepare(PAYER, request));

        assertEquals("SELF_TIP_NOT_ALLOWED", rejection.getReasonCode());
    }

    @Test
    void missingHostConfigIsNotFound() {
        when(configs.selectByHostAndClub(HOST, 0L)).thenReturn(null);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.prepare(PAYER, prepareRequest()));

        assertEquals("TIP_CONFIG_NOT_FOUND", rejection.getReasonCode());
    }

    @Test
    void disabledHostConfigRejects() {
        HostTipConfig disabled = enabledConfig("unapproved-channel");
        disabled.setEnabled(0);
        when(configs.selectByHostAndClub(HOST, 0L)).thenReturn(disabled);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.prepare(PAYER, prepareRequest()));

        assertEquals("TIP_NOT_ENABLED", rejection.getReasonCode());
    }

    @Test
    void amountBelowHostMinimumRejects() {
        HostTipConfig config = enabledConfig("unapproved-channel");
        config.setMinAmount(new BigDecimal("50.00"));
        when(configs.selectByHostAndClub(HOST, 0L)).thenReturn(config);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.prepare(PAYER, prepareRequest()));

        assertEquals("AMOUNT_BELOW_MIN", rejection.getReasonCode());
    }

    @Test
    void nonPositiveAmountRejects() {
        when(configs.selectByHostAndClub(HOST, 0L)).thenReturn(enabledConfig("unapproved-channel"));
        HostTipPrepareRequest request = prepareRequest();
        request.setAmount(BigDecimal.ZERO);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.prepare(PAYER, request));

        assertEquals("AMOUNT_INVALID", rejection.getReasonCode());
    }

    @Test
    void orderOfAnotherMemberIsNotFound() {
        when(configs.selectByHostAndClub(HOST, 0L)).thenReturn(enabledConfig("unapproved-channel"));
        OmsOrder otherOrder = new OmsOrder();
        otherOrder.setId(555L);
        otherOrder.setMemberId(OTHER);
        when(orders.selectOmsOrderById(555L)).thenReturn(otherOrder);
        HostTipPrepareRequest request = prepareRequest();
        request.setOrderRefId(555L);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.prepare(PAYER, request));

        assertEquals("ORDER_NOT_FOUND", rejection.getReasonCode(),
                "借别人的订单给小费必须被拒,且不泄露该订单存在");
    }

    @Test
    void requestIdIsRequiredForIdempotency() {
        when(configs.selectByHostAndClub(HOST, 0L)).thenReturn(enabledConfig("unapproved-channel"));
        HostTipPrepareRequest request = prepareRequest();
        request.setRequestId("  ");

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.prepare(PAYER, request));

        assertEquals("REQUEST_ID_REQUIRED", rejection.getReasonCode());
    }

    // ------------------------------------------------------------------ 幂等

    @Test
    void replayedPrepareReturnsSameIntentWithoutInsertingAgain() {
        when(configs.selectByHostAndClub(HOST, 0L)).thenReturn(enabledConfig("unapproved-channel"));
        HostTipIntent existing = new HostTipIntent();
        existing.setId(77L);
        existing.setHostMemberId(HOST);
        existing.setAmount(new BigDecimal("20.00"));
        existing.setCurrency("CNY");
        existing.setHostSharePercent(100);
        existing.setStatus(HostTipIntent.STATUS_CHANNEL_UNSUPPORTED);
        when(intents.selectByIdempotencyKey(anyString())).thenReturn(existing);

        var result = service.prepare(PAYER, prepareRequest());

        assertEquals(77L, result.getIntentId().longValue());
        assertFalse(result.isPayable());
        assertEquals("CHANNEL_NOT_ENABLED", result.getReasonCode());
        verify(intents, never()).insertIntent(any());
    }

    // ------------------------------------------------------------------ 取消

    @Test
    void cancelRejectsAlreadyPaidIntent() {
        HostTipIntent paid = new HostTipIntent();
        paid.setId(5L);
        paid.setPayerMemberId(PAYER);
        paid.setStatus(HostTipIntent.STATUS_PAID);
        when(intents.selectById(5L)).thenReturn(paid);
        when(intents.cancelIfOpen(5L, PAYER)).thenReturn(0);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.cancel(PAYER, 5L));

        assertEquals("CANCEL_NOT_ALLOWED", rejection.getReasonCode());
    }

    @Test
    void cancelOfSomeoneElsesIntentIsNotFound() {
        HostTipIntent other = new HostTipIntent();
        other.setId(5L);
        other.setPayerMemberId(OTHER);
        when(intents.selectById(5L)).thenReturn(other);

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.cancel(PAYER, 5L));

        assertEquals("TIP_INTENT_NOT_FOUND", rejection.getReasonCode());
    }

    // ------------------------------------------------------------------ 配置

    @Test
    void savedConfigAlwaysSharesOneHundredPercentWithHost() {
        when(configs.selectByHostAndClub(HOST, 0L)).thenReturn(null);
        HostTipConfigRequest request = new HostTipConfigRequest();
        request.setEnabled(false);
        request.setCurrency("cny");
        request.setMinAmount(new BigDecimal("5.00"));
        ArgumentCaptor<HostTipConfig> captor = ArgumentCaptor.forClass(HostTipConfig.class);

        service.upsertConfig(HOST, request);

        verify(configs).insertConfig(captor.capture());
        assertEquals(100, captor.getValue().getHostSharePercent().intValue(),
                "平台不抽成是代码不变量,不能靠配置漂移");
        assertEquals(0, captor.getValue().getEnabled().intValue());
        assertEquals("CNY", captor.getValue().getCurrency());
        assertEquals(HostTipConfig.FEE_BEARER_HOST, captor.getValue().getChannelFeeBearer().intValue());
    }

    @Test
    void feeBearerCanBeConfiguredIndependently() {
        when(configs.selectByHostAndClub(HOST, 0L)).thenReturn(null);
        HostTipConfigRequest request = new HostTipConfigRequest();
        request.setEnabled(false);
        request.setCurrency("CNY");
        request.setChannelFeeBearer(HostTipConfig.FEE_BEARER_PLATFORM);
        ArgumentCaptor<HostTipConfig> captor = ArgumentCaptor.forClass(HostTipConfig.class);

        service.upsertConfig(HOST, request);

        verify(configs).insertConfig(captor.capture());
        assertEquals(HostTipConfig.FEE_BEARER_PLATFORM, captor.getValue().getChannelFeeBearer().intValue(),
                "通道手续费承担方必须单独配置,不能默认算平台支出");
    }

    @Test
    void invalidCurrencyIsRejected() {
        HostTipConfigRequest request = new HostTipConfigRequest();
        request.setCurrency("RMB!");

        BountyTipException rejection = assertThrows(BountyTipException.class,
                () -> service.upsertConfig(HOST, request));

        assertEquals("CURRENCY_INVALID", rejection.getReasonCode());
    }
}

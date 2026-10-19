package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.OmsOrderPaymentCheck;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.mapper.OmsOrderPaymentCheckMapper;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.WechatOrderNotExistException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F0535:积分/现金来源分开;现金只认渠道回执,本地保存状态永不当作已核验。
 */
class OrderPaymentFactServiceTest {

    private OmsOrderMapper orders;
    private OmsOrderPaymentCheckMapper checks;
    private IWechatPayService wechat;
    private OrderPaymentFactService service;

    @BeforeEach
    void setUp() {
        orders = mock(OmsOrderMapper.class);
        checks = mock(OmsOrderPaymentCheckMapper.class);
        wechat = mock(IWechatPayService.class);
        service = new OrderPaymentFactService();
        ReflectionTestUtils.setField(service, "omsOrderMapper", orders);
        ReflectionTestUtils.setField(service, "paymentCheckMapper", checks);
        ReflectionTestUtils.setField(service, "wechatPayService", wechat);
        when(checks.insertPaymentCheck(any())).thenReturn(1);
    }

    private static OmsOrder cashOrder(long status) {
        OmsOrder o = new OmsOrder();
        o.setId(9L);
        o.setOrderSn("TPLTM_123");
        o.setBizType("template_market");
        o.setStatus(status);
        o.setPayAmount(new BigDecimal("19.90"));
        o.setPaymentTransactionId(status >= 1 ? "4200001" : null);
        return o;
    }

    private static Map<String, String> receipt(String state, String txn, String total) {
        Map<String, String> r = new HashMap<>();
        r.put("trade_state", state);
        r.put("transaction_id", txn);
        r.put("total", total);
        return r;
    }

    @Test
    void neverQueriedCashOrderIsUnconfirmedEvenIfSavedAsPaid() {
        when(orders.selectOmsOrderById(9L)).thenReturn(cashOrder(1));

        Map<String, Object> facts = service.facts(9L);

        assertEquals("CASH", facts.get("source"));
        assertEquals(false, facts.get("savedStatusVerified"));
        Map<?, ?> cash = (Map<?, ?>) facts.get("cash");
        assertEquals("UNCONFIRMED", cash.get("verdict"));
        assertNull(cash.get("lastQueryTime"));
        verifyNoInteractions(wechat);
    }

    @Test
    void pointsOrderHasNoChannelAndCannotBeQueried() {
        OmsOrder points = new OmsOrder();
        points.setId(5L);
        points.setOrderSn("PM5");
        points.setStatus(1L);
        points.setPayAmount(new BigDecimal("300"));
        when(orders.selectOmsOrderById(5L)).thenReturn(points);

        Map<String, Object> facts = service.facts(5L);
        assertEquals("POINTS", facts.get("source"));
        assertNull(facts.get("cash"));
        assertThrows(IllegalArgumentException.class, () -> service.queryChannel(5L, 1L));
        verify(wechat, never()).queryOrderStatus(anyString());
    }

    @Test
    void matchingReceiptIsConfirmedAndRecorded() {
        when(orders.selectOmsOrderById(9L)).thenReturn(cashOrder(1));
        when(wechat.queryOrderStatus("TPLTM_123")).thenReturn(receipt("SUCCESS", "4200001", "1990"));

        Map<String, Object> facts = service.queryChannel(9L, 3L);

        ArgumentCaptor<OmsOrderPaymentCheck> row = ArgumentCaptor.forClass(OmsOrderPaymentCheck.class);
        verify(checks).insertPaymentCheck(row.capture());
        assertEquals("CONFIRMED", row.getValue().getVerdict());
        assertEquals(Integer.valueOf(1990), row.getValue().getSavedPayAmountFen());
        assertEquals("CONFIRMED", ((Map<?, ?>) facts.get("cash")).get("verdict"));
    }

    @Test
    void mismatchesAreConflicts() {
        OmsOrder paid = cashOrder(1);
        OmsOrderPaymentCheck c = new OmsOrderPaymentCheck();
        c.setQueryStatus("OK");
        c.setChannelTradeState("SUCCESS");
        c.setChannelTransactionId("4200001");
        c.setChannelTotalFen(1000);
        assertEquals("CONFLICT", OrderPaymentFactService.verdict(paid, c)[0], "金额不一致");
        c.setChannelTotalFen(1990);
        c.setChannelTransactionId("OTHER");
        assertEquals("CONFLICT", OrderPaymentFactService.verdict(paid, c)[0], "交易号不一致");
        c.setChannelTradeState("NOTPAY");
        assertEquals("CONFLICT", OrderPaymentFactService.verdict(paid, c)[0], "本地已付、渠道未付");
        c.setChannelTradeState("SUCCESS");
        c.setChannelTransactionId(null);
        assertEquals("CONFLICT", OrderPaymentFactService.verdict(cashOrder(0), c)[0], "渠道已付、本地未付");
        c.setChannelTradeState("USERPAYING");
        assertEquals("UNCONFIRMED", OrderPaymentFactService.verdict(paid, c)[0]);
    }

    @Test
    void channelFailureIsUnconfirmedNotConfirmedAndOrderNotExistAgainstPaidIsConflict() {
        when(orders.selectOmsOrderById(9L)).thenReturn(cashOrder(1));
        when(wechat.queryOrderStatus("TPLTM_123")).thenThrow(new RuntimeException("timeout"));
        assertEquals("UNCONFIRMED", ((Map<?, ?>) service.queryChannel(9L, 3L).get("cash")).get("verdict"));

        org.mockito.Mockito.doThrow(new WechatOrderNotExistException("none", null)).when(wechat).queryOrderStatus("TPLTM_123");
        assertEquals("CONFLICT", ((Map<?, ?>) service.queryChannel(9L, 3L).get("cash")).get("verdict"));
    }
}

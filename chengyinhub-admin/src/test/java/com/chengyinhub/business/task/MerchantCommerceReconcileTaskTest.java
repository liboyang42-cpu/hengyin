package com.chengyinhub.business.task;

import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.service.IMerchantCommerceService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MerchantCommerceReconcileTaskTest {
    @Test
    void failedOrdersDoNotStarveLaterPagesAndFailureIsReported() {
        OmsOrderMapper orders = mock(OmsOrderMapper.class);
        IMerchantCommerceService commerce = mock(IMerchantCommerceService.class);
        IWechatPayService pay = mock(IWechatPayService.class);
        AlarmService alarms = mock(AlarmService.class);
        MerchantCommerceReconcileTask task = task(orders, commerce, pay, alarms);
        when(pay.isReady()).thenReturn(true);
        List<OmsOrder> first = new ArrayList<>();
        for (long id = 1; id <= 200; id++) first.add(order(id));
        when(orders.selectPendingCommerceOrders(eq(0L), any(), eq(200))).thenReturn(first);
        when(orders.selectPendingCommerceOrders(eq(200L), any(), eq(200))).thenReturn(Collections.singletonList(order(201)));
        doThrow(new IllegalStateException("query unavailable")).when(commerce).reconcileOrder("TPLTM_1");
        assertThrows(IllegalStateException.class, task::reconcile);
        verify(commerce).reconcileOrder("TPLTM_200");
        verify(alarms).alarm(eq("商业化查单补偿失败"), contains("1"));
        task.reconcile();
        verify(commerce).reconcileOrder("TPLTM_201");
        org.junit.jupiter.api.Assertions.assertEquals(0L, ReflectionTestUtils.getField(task, "cursor"));
    }

    @Test
    void unavailablePaymentChannelDoesNotPretendAnEmptySuccessfulRun() {
        OmsOrderMapper orders = mock(OmsOrderMapper.class);
        IWechatPayService pay = mock(IWechatPayService.class);
        MerchantCommerceReconcileTask task = task(orders, mock(IMerchantCommerceService.class), pay, mock(AlarmService.class));
        assertThrows(IllegalStateException.class, task::reconcile);
        verifyNoInteractions(orders);
    }

    private OmsOrder order(long id) {
        OmsOrder order = new OmsOrder(); order.setId(id); order.setOrderSn("TPLTM_" + id); return order;
    }

    private MerchantCommerceReconcileTask task(OmsOrderMapper orders, IMerchantCommerceService commerce,
                                              IWechatPayService pay, AlarmService alarms) {
        MerchantCommerceReconcileTask task = new MerchantCommerceReconcileTask();
        ReflectionTestUtils.setField(task, "orderMapper", orders);
        ReflectionTestUtils.setField(task, "commerceService", commerce);
        ReflectionTestUtils.setField(task, "wechatPayService", pay);
        ReflectionTestUtils.setField(task, "alarmService", alarms);
        return task;
    }
}

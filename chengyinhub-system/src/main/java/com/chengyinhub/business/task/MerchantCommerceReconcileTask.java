package com.chengyinhub.business.task;

import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.service.IMerchantCommerceService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.impl.AlarmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Component("merchantCommerceReconcileTask")
public class MerchantCommerceReconcileTask {
    @Autowired private OmsOrderMapper orderMapper;
    @Autowired private IMerchantCommerceService commerceService;
    @Autowired private IWechatPayService wechatPayService;
    @Autowired private AlarmService alarmService;
    private volatile long cursor;

    public void reconcile() {
        if (!wechatPayService.isReady()) throw new IllegalStateException("商业化查单渠道未就绪");
        List<OmsOrder> orders = orderMapper.selectPendingCommerceOrders(cursor,
                new Date(System.currentTimeMillis() - 5 * 60_000L), 200);
        if (orders.isEmpty()) {
            cursor = 0;
            return;
        }
        Exception firstFailure = null;
        int failed = 0;
        for (OmsOrder order : orders) {
            cursor = order.getId();
            try {
                commerceService.reconcileOrder(order.getOrderSn());
            } catch (Exception failure) {
                failed++;
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (orders.size() < 200) cursor = 0;
        if (firstFailure != null) {
            IllegalStateException failure = new IllegalStateException("商业化查单补偿失败 " + failed + " 单", firstFailure);
            try {
                alarmService.alarm("商业化查单补偿失败", "未确认订单保留待处理，失败数量=" + failed);
            } catch (Exception alarmFailure) {
                failure.addSuppressed(alarmFailure);
            }
            throw failure;
        }
    }
}

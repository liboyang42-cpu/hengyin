package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.RefundApplicationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 原退款行是持久重试来源，包括到账、人工、被取代/驳回的原意图。
 * 不增加发款队列，不修改资金结果；进程重启按持久尝试次序续扫，09按原ID幂等消费。
 */
@Service
public class RegistrationRefundFulfillmentRecovery {
    private static final Logger log = LoggerFactory.getLogger(RegistrationRefundFulfillmentRecovery.class);
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PER_RUN = 1000;

    @Autowired
    private RefundApplicationMapper refundApplicationMapper;
    @Autowired
    private RegistrationRefundFulfillmentSyncService fulfillmentSyncService;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized void reconcile() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("履约恢复必须在原资金事务之外运行");
        }
        Long upperBound = refundApplicationMapper.selectRegistrationRefundRecoveryUpperBound();
        if (upperBound == null) return;
        // 新单以入队时间排队，尝试后移到当前时间；持续新单也不能永远抢在失败旧单之前。
        // 此次序持久化在原行，重启后第1001条仍可达；不是“同步已成功”标记。
        // 本轮固定上界及排除集合，既不被新单无限延长，也不重复占用本轮预算。
        java.util.Set<Long> visited = new java.util.LinkedHashSet<>();
        while (visited.size() < MAX_PER_RUN) {
            int limit = Math.min(PAGE_SIZE, MAX_PER_RUN - visited.size());
            List<Long> ids = refundApplicationMapper.selectRegistrationRefundRecoveryBatch(
                    upperBound, limit, new java.util.ArrayList<>(visited));
            if (ids == null) throw new IllegalStateException("退款履约恢复查询结果未知");
            if (ids.size() > limit) throw new IllegalStateException("退款履约恢复批次超出预算");
            if (ids.isEmpty()) return;
            for (Long id : ids) {
                if (id == null || id <= 0 || id > upperBound || !visited.add(id)) {
                    throw new IllegalStateException("退款履约恢复批次身份重复或范围异常");
                }
                // NOT_SUPPORTED下本条元数据独立提交；F3失败不撤销尝试次序，下一轮仍会公平回到原ID。
                // 数据库错误向外传播，不把未持久化的进度当作成功；0行仅表示原行已退出查询范围。
                if (refundApplicationMapper.markRegistrationRefundRecoveryAttempt(id) != 1) continue;
                try {
                    fulfillmentSyncService.sync(id); // 注入的独立bean开启REQUIRES_NEW。
                } catch (RuntimeException failure) {
                    log.error("退款履约同步失败，保留原ID待公平重扫 refundId={}", id, failure);
                }
            }
            if (ids.size() < limit) return;
        }
    }
}

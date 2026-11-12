package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.RevenueAccrualRequest;
import com.chengyinhub.business.domain.dto.RevenueReversalRequest;
import com.chengyinhub.business.domain.marketing.MarketingRevenuePostingTask;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.mapper.MarketingRevenuePostingTaskMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.IMarketingRevenueAllocationService;
import com.chengyinhub.business.service.IMarketingRevenuePostingService;
import com.chengyinhub.common.utils.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 可靠投递实现。
 *
 * <p>四个关键性质：
 * <ul>
 *   <li><b>同事务入队</b>：{@code enqueue*} 标 {@code PROPAGATION_MANDATORY} —— 必须已有调用方事务。
 *       没有事务就直接失败，而不是偷偷开一个"看起来成功"的独立事务（那会让任务与事实脱钩）。</li>
 *   <li><b>提交后消费</b>：领取与执行各自独立事务，此时原事实已提交、可正常读取，
 *       不会与调用方持有的行锁互相等待。</li>
 *   <li><b>租约可恢复</b>：领取写 {@code lease_owner + lease_expires_at}；崩溃后
 *       {@link #reclaimExpiredLeases()} 把过期行放回队列。</li>
 *   <li><b>失败可审计可重试</b>：写 status/attempts/reason_code/next_attempt_at（指数退避），
 *       不是 catch 后只打一行日志，也不会"吞掉异常继续提交半笔账"。</li>
 * </ul>
 */
@Service
public class MarketingRevenuePostingServiceImpl implements IMarketingRevenuePostingService {

    private static final Logger log = LoggerFactory.getLogger(MarketingRevenuePostingServiceImpl.class);

    /** 退避：第 n 次失败后等待 min(30 分钟, n^2 分钟)。 */
    private static final int BACKOFF_UNIT_MINUTES = 1;
    private static final int BACKOFF_CAP_MINUTES = 30;
    /** 租约时长：必须明显大于单笔处理耗时，又小于任务调度间隔的数倍。 */
    private static final int LEASE_SECONDS = 300;

    private final IMarketingRevenueAllocationService allocationService;
    private final MarketingRevenuePostingTaskMapper taskMapper;
    private final RefundApplicationMapper refundMapper;
    private final TransactionTemplate claimTransaction;
    private final TransactionTemplate executeTransaction;
    private final TransactionTemplate failureTransaction;
    private final String leaseOwner;

    public MarketingRevenuePostingServiceImpl(IMarketingRevenueAllocationService allocationService,
                                              MarketingRevenuePostingTaskMapper taskMapper,
                                              RefundApplicationMapper refundMapper,
                                              PlatformTransactionManager transactionManager) {
        this.allocationService = allocationService;
        this.taskMapper = taskMapper;
        this.refundMapper = refundMapper;
        this.claimTransaction = newTransaction(transactionManager);
        this.executeTransaction = newTransaction(transactionManager);
        this.failureTransaction = newTransaction(transactionManager);
        // 单实例唯一标识：崩溃重启后旧租约自然过期，不需要额外清理。
        this.leaseOwner = "m5rev-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static TransactionTemplate newTransaction(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    // ------------------------------------------------------------------ enqueue（原事务内）

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long enqueueAccrual(Long registrationId) {
        if (registrationId == null || registrationId <= 0) {
            throw MarketingRevenueException.badRequest("ORDER_REQUIRED", "缺少报名单");
        }
        return enqueue(MarketingRevenuePostingTask.KIND_ACCRUE, registrationId, registrationId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long enqueueReversal(Long refundId, Long registrationId) {
        if (refundId == null || refundId <= 0) {
            throw MarketingRevenueException.badRequest("REFUND_ID_REQUIRED", "缺少退款单标识");
        }
        Long orderId = registrationId;
        if (orderId == null) {
            RefundApplication refund = refundMapper.selectRefundApplicationById(refundId);
            if (refund != null && "registration".equals(refund.getSourceType())) {
                orderId = refund.getSourceId();
            }
        }
        if (orderId == null || orderId <= 0) {
            throw MarketingRevenueException.badRequest("ORDER_REQUIRED", "退款单未关联报名单，无法入队");
        }
        return enqueue(MarketingRevenuePostingTask.KIND_REVERSE, refundId, orderId);
    }

    private Long enqueue(int kind, long refId, long orderId) {
        Date now = DateUtils.getNowDate();
        MarketingRevenuePostingTask task = new MarketingRevenuePostingTask();
        task.setTaskKind(kind);
        task.setRefId(refId);
        task.setOrderId(orderId);
        task.setStatus(MarketingRevenuePostingTask.STATUS_PENDING);
        task.setAttempts(0);
        task.setNextAttemptAt(now);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        taskMapper.enqueue(task);
        MarketingRevenuePostingTask stored = taskMapper.selectByKindAndRef(kind, refId);
        if (stored == null) {
            throw new IllegalStateException("投递任务入队后读不到 kind=" + kind + " ref=" + refId);
        }
        return stored.getId();
    }

    @Override
    public boolean hasAccrualTask(Long registrationId) {
        if (registrationId == null || registrationId <= 0) {
            return false;
        }
        List<MarketingRevenuePostingTask> tasks = taskMapper.selectByOrder(registrationId);
        if (tasks == null) {
            return false;
        }
        for (MarketingRevenuePostingTask task : tasks) {
            if (Integer.valueOf(MarketingRevenuePostingTask.KIND_ACCRUE).equals(task.getTaskKind())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ consume（独立事务 + 租约）

    @Override
    public int consumeDue(int limit) {
        if (limit <= 0) {
            return 0;
        }
        List<MarketingRevenuePostingTask> claimed = claim(limit);
        int processed = 0;
        for (MarketingRevenuePostingTask task : claimed) {
            if (processOne(task)) {
                processed++;
            }
        }
        return processed;
    }

    @Override
    public int reclaimExpiredLeases() {
        try {
            Integer reclaimed = claimTransaction.execute(status -> taskMapper.reclaimExpired());
            return reclaimed == null ? 0 : reclaimed;
        } catch (RuntimeException unavailable) {
            log.warn("[M5] 回收过期租约失败,下轮再试", unavailable);
            return 0;
        }
    }

    private List<MarketingRevenuePostingTask> claim(int limit) {
        try {
            List<MarketingRevenuePostingTask> claimed = claimTransaction.execute(status -> {
                taskMapper.claim(leaseOwner, LEASE_SECONDS, limit);
                return taskMapper.selectClaimed(leaseOwner);
            });
            return claimed == null ? new ArrayList<>() : claimed;
        } catch (RuntimeException unavailable) {
            log.warn("[M5] 领取投递任务失败,本轮跳过", unavailable);
            return new ArrayList<>();
        }
    }

    /**
     * 单个任务使用**独立**事务执行：一个失败不牵连其它任务，
     * 也不会把整批标记成不可重试。
     *
     * <p>账务动作与任务状态在同一事务里；但"置 DONE"带租约栅栏（{@code status=3 and lease_owner=?}）：
     * 若本 worker 的租约已过期并被别人重新领取，置 DONE 命中 0 行 —— 账务动作（按业务键幂等）
     * 照常提交，任务状态却不会被旧 worker 覆盖。
     *
     * @return 是否成功完成并拥有租约
     */
    private boolean processOne(MarketingRevenuePostingTask task) {
        try {
            Boolean done = executeTransaction.execute(status -> runTask(task));
            return Boolean.TRUE.equals(done);
        } catch (MarketingRevenueException rejection) {
            recordFailure(task, rejection.getReasonCode(), rejection.getMessage());
            return false;
        } catch (RuntimeException unexpected) {
            log.warn("[M5] 投递任务执行异常 taskId={} kind={}", task.getId(), task.getTaskKind(), unexpected);
            recordFailure(task, "POSTING_FAILED", unexpected.getClass().getSimpleName());
            return false;
        }
    }

    private boolean runTask(MarketingRevenuePostingTask task) {
        if (task.getTaskKind() != null && task.getTaskKind() == MarketingRevenuePostingTask.KIND_ACCRUE) {
            // 已有快照 → accrue 走"无 payload"分支幂等重放。
            // 尚无快照 → 缺费用证据会抛 FEE_BASIS_MISSING，任务保留待核（正确行为：
            //   未核算费用时绝不入账，也绝不默认 0 写虚假净额）。
            RevenueAccrualRequest request = new RevenueAccrualRequest();
            request.setRegistrationId(task.getRefId());
            request.setSourceFact("queued-accrual:" + task.getId());
            allocationService.accrue(request);
            return markDoneWithinLease(task);
        }
        if (task.getTaskKind() != null && task.getTaskKind() == MarketingRevenuePostingTask.KIND_REVERSE) {
            RevenueReversalRequest request = new RevenueReversalRequest();
            request.setRefundId(task.getRefId());
            request.setEventType("refund");
            allocationService.reverse(request);
            return markDoneWithinLease(task);
        }
        if (task.getTaskKind() != null
                && task.getTaskKind() == MarketingRevenuePostingTask.KIND_CHANNEL_REVERSE) {
            // 金额/币种由分配服务按渠道事实主键回读，队列里只有 id。
            // 整笔退款按事实覆盖的注册单范围逐条冲销；任一行尚无应计则整笔待重试（不冲一半）。
            allocationService.reverseChannelRefundFact(task.getRefId());
            return markDoneWithinLease(task);
        }
        throw MarketingRevenueException.badRequest("TASK_KIND_INVALID", "投递任务类型不合法");
    }

    /**
     * 只在**仍持有租约**时置 DONE。0 行 = 租约已失效（被回收/被重新入队/被他人领取）：
     * 账务动作照常提交（幂等），但本 worker 不得声称完成，也不得覆盖新持有者的状态。
     */
    private boolean markDoneWithinLease(MarketingRevenuePostingTask task) {
        int updated = taskMapper.markDoneIfLeaseHeld(task.getId(), leaseOwner);
        if (updated == 1) {
            return true;
        }
        log.warn("[M5] 投递任务账务已完成但租约已失效,未写 DONE（旧 worker 不覆盖新 worker）"
                        + " taskId={} kind={} ref={} owner={}",
                task.getId(), task.getTaskKind(), task.getRefId(), leaseOwner);
        return false;
    }

    /**
     * 失败回写：**独立事务**写入。这一步与执行事务彼此独立，所以执行事务回滚后，
     * 失败状态与 attempts 仍然可靠落库（不会被一起回滚掉）。
     *
     * <p>若连这里也写不进去，说明队列本身不可写 —— 此时抛异常，
     * 因为"任务无法落状态"意味着会无限重复处理同一行，必须让调度方看到。
     */
    private void recordFailure(MarketingRevenuePostingTask task, String reasonCode, String detail) {
        int attempts = task.getAttempts() == null ? 0 : task.getAttempts();
        int waitMinutes = Math.min(BACKOFF_CAP_MINUTES,
                BACKOFF_UNIT_MINUTES * (attempts + 1) * (attempts + 1));
        Date nextAttempt = new Date(System.currentTimeMillis() + waitMinutes * 60_000L);
        String safeDetail = detail == null ? null
                : (detail.length() <= 255 ? detail : detail.substring(0, 255));
        Integer updated;
        try {
            updated = failureTransaction.execute(status ->
                    taskMapper.markFailedIfLeaseHeld(task.getId(), leaseOwner, reasonCode, safeDetail, nextAttempt));
        } catch (RuntimeException cannotRecord) {
            log.error("[M5] 投递任务状态回写失败 taskId={} reason={}", task.getId(), reasonCode, cannotRecord);
            throw new IllegalStateException(
                    "投递任务失败且状态无法落库 taskId=" + task.getId() + " reason=" + reasonCode,
                    cannotRecord);
        }
        if (updated == null || updated == 0) {
            // 租约已失效：任务已被回收/被重新入队/被他人领取。写 FAILED 会清掉新持有者的租约，
            // 所以这里只留痕，状态交给当前持有者。任务仍在队列里，不会丢。
            log.warn("[M5] 投递任务失败但租约已失效,未写 FAILED（不覆盖新 worker）"
                            + " taskId={} kind={} ref={} reason={} owner={}",
                    task.getId(), task.getTaskKind(), task.getRefId(), reasonCode, leaseOwner);
            return;
        }
        log.info("[M5] 投递任务待重试 taskId={} kind={} ref={} reason={} attempts={}",
                task.getId(), task.getTaskKind(), task.getRefId(), reasonCode, attempts + 1);
    }

    @Override
    public List<MarketingRevenuePostingTask> tasksOf(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw MarketingRevenueException.badRequest("ORDER_REQUIRED", "缺少订单标识");
        }
        List<MarketingRevenuePostingTask> rows = taskMapper.selectByOrder(orderId);
        return rows == null ? new ArrayList<>() : rows;
    }
}

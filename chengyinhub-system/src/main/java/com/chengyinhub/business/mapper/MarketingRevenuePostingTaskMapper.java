package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.marketing.MarketingRevenuePostingTask;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 可靠队列。入队与状态回写都在**调用方事务内**完成；领取用租约，崩溃可回收。
 *
 * <p>为什么用租约而不是只靠 {@code FOR UPDATE SKIP LOCKED}：
 * 领取事务提交后行锁就释放了，若进程在处理过程中崩溃，任务会永远停在"已领取"而无人推进。
 * 租约把"谁在处理、处理到什么时候"写进行里，过期的 CLAIMED 行由 {@link #reclaimExpired} 放回队列。
 */
public interface MarketingRevenuePostingTaskMapper {

    /** 幂等入队：同 (task_kind, ref_id) 只保留一行；已 DONE 的不再复活。 */
    int enqueue(@Param("task") MarketingRevenuePostingTask task);

    /**
     * 领取：把到期 PENDING 行原子地置为 CLAIMED 并打上租约。
     * 返回本次领取行数（随后用 {@link #selectClaimed} 取回）。
     */
    int claim(@Param("leaseOwner") String leaseOwner,
              @Param("leaseSeconds") int leaseSeconds,
              @Param("limit") int limit);

    /** 取回本租约持有的任务。 */
    List<MarketingRevenuePostingTask> selectClaimed(@Param("leaseOwner") String leaseOwner);

    /** 租约过期回收：CLAIMED 且租约到期 → 回到 PENDING 立即可重试。 */
    int reclaimExpired();

    MarketingRevenuePostingTask selectByKindAndRef(@Param("taskKind") Integer taskKind,
                                                   @Param("refId") Long refId);

    /**
     * 生产路径：只有**当前租约持有者**才能把任务标成 DONE。
     *
     * <p>{@code where id=? and status=3(CLAIMED) and lease_owner=?}：租约过期被回收、或被重新入队
     * （{@code enqueue} 会把 CLAIMED 重置为 PENDING 并清空 lease_owner）后，旧 worker 的
     * markDone 命中 0 行 —— 旧 worker 不能覆盖新 worker 的状态。
     *
     * @return 1 = 仍是本 worker 的租约且已置 DONE；0 = 租约已失效，调用方必须按"未完成"处理
     */
    int markDoneIfLeaseHeld(@Param("id") Long id, @Param("leaseOwner") String leaseOwner);

    /**
     * 生产路径：失败回写同样只能用当前租约改。
     * 0 行 = 任务已被回收/重投，**不得**覆盖成 FAILED（否则会清掉新持有者的租约）。
     */
    int markFailedIfLeaseHeld(@Param("id") Long id,
                              @Param("leaseOwner") String leaseOwner,
                              @Param("reasonCode") String reasonCode,
                              @Param("detail") String detail,
                              @Param("nextAttemptAt") Date nextAttemptAt);

    /**
     * 不带租约的完成写入。**仅供测试夹具/人工补偿**构造状态；生产消费必须走
     * {@link #markDoneIfLeaseHeld}，否则旧 worker 可覆盖新 worker。
     */
    int markDone(@Param("id") Long id);

    /** 不带租约的失败写入；使用边界同 {@link #markDone(Long)}。 */
    int markFailed(@Param("id") Long id,
                   @Param("reasonCode") String reasonCode,
                   @Param("detail") String detail,
                   @Param("nextAttemptAt") Date nextAttemptAt);

    List<MarketingRevenuePostingTask> selectByOrder(@Param("orderId") Long orderId);

    List<MarketingRevenuePostingTask> selectByStatus(@Param("status") Integer status);
}

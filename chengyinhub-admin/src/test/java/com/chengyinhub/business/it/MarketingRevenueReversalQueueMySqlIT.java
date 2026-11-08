package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MarketingAttribution;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.RevenueAccrualRequest;
import com.chengyinhub.business.domain.dto.RefundSettledFact;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.domain.marketing.MarketingRevenuePostingTask;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.MarketingAttributionMapper;
import com.chengyinhub.business.mapper.MarketingRevenueAllocationLineMapper;
import com.chengyinhub.business.mapper.MarketingRevenueAllocationMapper;
import com.chengyinhub.business.mapper.MarketingRevenuePostingTaskMapper;
import com.chengyinhub.business.mapper.MarketingRevenueRuleMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.impl.MarketingRevenueAllocationServiceImpl;
import com.chengyinhub.business.service.impl.MarketingRevenuePostingServiceImpl;
import com.chengyinhub.business.service.impl.MarketingRevenueRefundSettledConsumer;
import com.chengyinhub.business.service.impl.MarketingRevenueRuleServiceImpl;
import com.chengyinhub.business.service.support.RevenueFactSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.chengyinhub.business.domain.dto.RevenueReversalRequest;
import com.chengyinhub.business.service.support.WechatRevenueFactSource;

/**
 * M5 可靠投递队列在真实 InnoDB 上的**先后顺序**验收：退款先到、应计后到，冲销不能丢。
 *
 * <p>为什么不能只靠单测：失败态由 {@code REQUIRES_NEW} 独立事务写入，重试资格由
 * {@code status in (0,2) and next_attempt_at <= now()} 决定；这两条都只有在真实事务/行锁下
 * 才能证明「执行回滚了、失败记录还在、下一轮还能领到」。
 *
 * <p>覆盖的负例：
 * <ul>
 *   <li>退款冲销任务先入队、应计尚不存在 → 执行失败但失败态保留（不静默丢），
 *       应计落账后同一任务重试一次即冲销成功；</li>
 *   <li>三方（作者/推荐人/平台）金额与全退冲销逐分守恒，0.05 元也不丢一分；</li>
 *   <li>同一退款事实重复入队不会把已完成的任务复活，也不会写第二份冲销分录；</li>
 *   <li>租约栅栏：A 超时被回收 → B 领走完成 → A 的 markDone/markFailed 都是 0 行；</li>
 *   <li>入队不抢活着的 CLAIMED 租约，{@code next_attempt_at} 未到期不能被领取；</li>
 *   <li>渠道冲销 kind=3 与微信退款 kind=2 共用数值时互不顶掉（z 迁移放宽 CHECK）；</li>
 *   <li>退款到账早于应计落库时，**真实 consumer** 必须入队冲销（ME-04）；真正无来源才 noop。</li>
 * </ul>
 *
 * <p>一类一库，跑完 DROP DATABASE 并回读不存在。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_MARKETING_DB_IT_APPROVED", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketingRevenueReversalQueueMySqlIT {

    private final String suite = "m5order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    private static final long REGISTRATION_ID = 555L;
    private static final long TOPIC_ID = 42L;
    private static final long CONTENT_OWNER = 9L;
    private static final long CONTRACT_PAYEE = 77L;
    private static final long SHARER = 222L;
    private static final long REFUND_ID = 6101L;
    private static final String FACT_HASH = "a".repeat(64);

    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private MarketingRevenueAllocationMapper allocationMapper;
    private MarketingRevenueAllocationLineMapper lineMapper;
    private MarketingRevenuePostingTaskMapper taskMapper;
    private MarketingRevenueAllocationServiceImpl allocationService;
    private MarketingRevenuePostingServiceImpl postingService;
    private MarketingAttributionMapper attributionMapper;

    @BeforeAll
    void schemaAndRealMappers() throws Exception {
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()),
                "只许连一次性 scratch MySQL");
        source = P0AcceptanceSchema.dataSource(suite);
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertEquals(MybatisItDatabase.databaseName(suite),
                db.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(com.chengyinhub.business.service.MysqlIntegrationTestConfig.isMysql8OrNewer(
                db.queryForObject("SELECT VERSION()", String.class)), "需要 Oracle MySQL >= 8");

        // 生产顺序：建表迁移在前，M8F3 的 z 迁移把 ck_revenue_posting_kind 放宽到 (1,2,3)。
        // 只跑建表迁移会让 task_kind=3(CHANNEL_REVERSE) 无法插入，那是与生产不一致的另一套 schema。
        P0AcceptanceSchema.migrations(source,
                "migration_marketing_revenue_allocation_20261108.sql",
                "migration_z_marketing_revenue_channel_reverse_kind_20261108.sql");

        var session = P0AcceptanceSchema.sql(source,
                "MarketingRevenueAllocationMapper", "MarketingRevenueAllocationLineMapper",
                "MarketingRevenuePostingTaskMapper", "MarketingRevenueRuleMapper");
        allocationMapper = session.getMapper(MarketingRevenueAllocationMapper.class);
        lineMapper = session.getMapper(MarketingRevenueAllocationLineMapper.class);
        taskMapper = session.getMapper(MarketingRevenuePostingTaskMapper.class);
        MarketingRevenueRuleMapper ruleMapper = session.getMapper(MarketingRevenueRuleMapper.class);
        attributionMapper = mock(MarketingAttributionMapper.class);

        MarketingRevenueRuleServiceImpl ruleService = new MarketingRevenueRuleServiceImpl(ruleMapper);
        allocationService = new MarketingRevenueAllocationServiceImpl(allocationMapper, lineMapper,
                attributionMapper, registrationMapper(), refundMapper(), topicService(),
                mock(ICmsActivityService.class), ruleService, usableMemberService(), new StubFacts());
        postingService = new MarketingRevenuePostingServiceImpl(allocationService, taskMapper,
                refundMapper(), new DataSourceTransactionManager(source));
    }

    @AfterAll
    void cleanup() {
        if (db != null) {
            assertTrue(MybatisItDatabase.databaseName(suite).matches("chengyin_mybatis_m5order_[a-z0-9_]+"));
            P0AcceptanceSchema.drop(db, suite);
        }
    }

    @BeforeEach
    void clearOwnedTables() {
        for (String table : List.of("marketing_revenue_allocation_line", "marketing_revenue_allocation",
                "marketing_revenue_posting_task", "marketing_revenue_rule")) {
            db.update("DELETE FROM " + table);
        }
        when(attributionMapper.selectByOrder(anyInt(), anyLong())).thenReturn(null);
    }

    // ------------------------------------------------------------------ 退款先到

    @Test
    void reversalQueuedBeforeAccrualIsRetainedThenAppliedOnceAccrualLands() {
        seedActiveRule("topic:42:s1", 1, "0.50", "0.50", "0", 0);

        tx.execute(status -> {
            postingService.enqueueReversal(REFUND_ID, REGISTRATION_ID);
            return null;
        });
        assertEquals(1, count("marketing_revenue_posting_task"));

        // 第一次消费：应计快照还不存在 → reverse 抛 ALLOCATION_NOT_FOUND。
        assertEquals(0, postingService.consumeDue(10), "没有应计快照时冲销不得成功");
        MarketingRevenuePostingTask failed = taskMapper.selectByKindAndRef(
                MarketingRevenuePostingTask.KIND_REVERSE, REFUND_ID);
        assertNotNull(failed, "任务必须还在，不能因为执行失败被丢掉");
        assertEquals(MarketingRevenuePostingTask.STATUS_FAILED, failed.getStatus().intValue());
        assertEquals(1, failed.getAttempts().intValue());
        assertEquals("ALLOCATION_NOT_FOUND", failed.getReasonCode());
        assertTrue(failed.getNextAttemptAt() != null,
                "失败后必须写下次重试时间，不能永远停在可领取状态");
        assertEquals(0, count("marketing_revenue_allocation"), "失败的执行事务不得留下半笔应计");

        // 应计随后到账，并且重试窗口已到：同一任务必须能再被领取并冲销成功。
        allocationService.accrue(accrualRequest());
        db.update("UPDATE marketing_revenue_posting_task SET next_attempt_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE)"
                + " WHERE task_kind = ? AND ref_id = ?",
                MarketingRevenuePostingTask.KIND_REVERSE, REFUND_ID);

        assertEquals(1, postingService.consumeDue(10), "应计落账后同一冲销任务必须成功");
        MarketingRevenueAllocation allocation = allocationMapper.selectByOrder(
                MarketingRevenueAllocation.ORDER_REGISTRATION, REGISTRATION_ID);
        assertEquals(MarketingRevenueAllocation.STATUS_REVERSED, allocation.getStatus().intValue());
        assertEquals(allocation.getNetFen().longValue(), allocation.getReversedFen().longValue(),
                "全额退款必须把净额冲满");
        MarketingRevenuePostingTask done = taskMapper.selectByKindAndRef(
                MarketingRevenuePostingTask.KIND_REVERSE, REFUND_ID);
        assertEquals(MarketingRevenuePostingTask.STATUS_DONE, done.getStatus().intValue());
        assertEquals(2, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_allocation_line WHERE entry_type = 2 AND event_ref = ?",
                Integer.class, "refund:" + REFUND_ID),
                "作者+平台两条原分录都必须带本退款事件身份反向");
    }

    // ------------------------------------------------------------------ 三方尾差

    @Test
    void threePartySplitAndFullReversalKeepEveryCent() {
        // 推荐场景（s3）：实付 0.05，作者基础 40%（作者 2 分、平台 3 分），推荐佣金 20%（从作者份额出 1 分）。
        // 最终 author=1 / referrer=1 / platform=3，合计 5 分，没有残差。
        seedActiveRule("topic:42:s3", 3, "0.40", "0.60", "0.20", 2);
        MarketingAttribution attribution = new MarketingAttribution();
        attribution.setId(9001L);
        attribution.setSharerMemberId(SHARER);
        when(attributionMapper.selectByOrder(anyInt(), anyLong())).thenReturn(attribution);

        allocationService.accrue(accrualRequest());
        MarketingRevenueAllocation allocation = allocationMapper.selectByOrder(
                MarketingRevenueAllocation.ORDER_REGISTRATION, REGISTRATION_ID);
        var accrueLines = db.queryForList(
                "SELECT line_role, amount_fen FROM marketing_revenue_allocation_line"
                        + " WHERE allocation_id=? AND entry_type=1 ORDER BY id", allocation.getId());
        assertEquals(3, accrueLines.size(), "三方必须各有一条应计分录，实际=" + accrueLines);
        long accrueSum = db.queryForObject(
                "SELECT COALESCE(SUM(amount_fen),0) FROM marketing_revenue_allocation_line"
                        + " WHERE allocation_id=? AND entry_type=1", Long.class, allocation.getId());
        assertEquals(allocation.getNetFen().longValue(), accrueSum, "三方应计合计必须等于净额");

        var reversal = allocationService.reverse(reversalRequest());
        assertEquals(MarketingRevenueAllocation.STATUS_REVERSED, reversal.getStatus().intValue());
        MarketingRevenueAllocation reversed = allocationMapper.selectById(allocation.getId());
        assertEquals(5L, reversed.getReversedFen().longValue(), "0.05 全退必须一分不差冲满");
        long reversalSum = db.queryForObject(
                "SELECT COALESCE(SUM(-amount_fen),0) FROM marketing_revenue_allocation_line"
                        + " WHERE allocation_id=? AND entry_type=2", Long.class, allocation.getId());
        assertEquals(5L, reversalSum, "三方冲销逐分守恒");
        assertEquals(3, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_allocation_line"
                        + " WHERE allocation_id=? AND entry_type=2", Integer.class, allocation.getId()),
                "每条应计分录都必须有一条对应冲销");
    }

    // ------------------------------------------------------------------ 重复入队

    @Test
    void reEnqueueOfAnAlreadyDoneReverseDoesNotPostTwice() {
        seedActiveRule("topic:42:s1", 1, "0.50", "0.50", "0", 0);
        allocationService.accrue(accrualRequest());

        tx.execute(status -> {
            postingService.enqueueReversal(REFUND_ID, REGISTRATION_ID);
            return null;
        });
        assertEquals(1, postingService.consumeDue(10));
        int linesAfterFirst = count("marketing_revenue_allocation_line");

        tx.execute(status -> {
            postingService.enqueueReversal(REFUND_ID, REGISTRATION_ID);
            return null;
        });
        assertEquals(MarketingRevenuePostingTask.STATUS_DONE,
                taskMapper.selectByKindAndRef(MarketingRevenuePostingTask.KIND_REVERSE, REFUND_ID)
                        .getStatus().intValue(), "已完成任务不得被重复入队复活");
        assertEquals(0, postingService.consumeDue(10), "没有可领取任务");
        assertEquals(linesAfterFirst, count("marketing_revenue_allocation_line"),
                "重复入队不得写第二份冲销分录");
    }

    // ------------------------------------------------------------------ 租约栅栏（真 MySQL）

    /**
     * A 领取后租约过期 → 回收 → B 领走并完成 → A 醒来回写必须是 0 行。
     *
     * <p>为什么必须真 MySQL：栅栏是 {@code where id=? and status=3 and lease_owner=?}，
     * 判定完全依赖数据库里那一刻的行状态。H2/内存桩证明不了 InnoDB 上"租约已换人"这件事。
     */
    @Test
    void expiredLeaseCannotBeCompletedOrFailedByTheOldWorker() {
        tx.execute(status -> {
            postingService.enqueueReversal(REFUND_ID, REGISTRATION_ID);
            return null;
        });
        long taskId = taskMapper.selectByKindAndRef(
                MarketingRevenuePostingTask.KIND_REVERSE, REFUND_ID).getId();

        assertEquals(1, taskMapper.claim("worker-A", 1, 10), "A 必须能领到到期任务");
        // 把租约推到过去，模拟 A 处理超时（不 sleep，保证用例确定性）。
        db.update("UPDATE marketing_revenue_posting_task"
                + " SET lease_expires_at = DATE_SUB(NOW(), INTERVAL 5 SECOND) WHERE id=?", taskId);

        // 仍是 CLAIMED：B 不能直接抢，必须先由 reclaimExpired 回收。
        assertEquals(0, taskMapper.claim("worker-B", 300, 10), "未回收的过期租约不得被直接抢走");
        assertEquals(1, taskMapper.reclaimExpired(), "过期租约必须被回收");
        assertEquals(1, taskMapper.claim("worker-B", 300, 10), "回收后 B 必须能接管");
        assertEquals(1, taskMapper.markDoneIfLeaseHeld(taskId, "worker-B"), "当前持有者必须能置 DONE");

        assertEquals(0, taskMapper.markDoneIfLeaseHeld(taskId, "worker-A"),
                "租约已换人：A 的完成回写必须 0 行");
        assertEquals(0, taskMapper.markFailedIfLeaseHeld(taskId, "worker-A", "POSTING_FAILED",
                        "stale worker", new Date()),
                "租约已换人：A 的失败回写也必须 0 行，不能覆盖 B 的 DONE");

        MarketingRevenuePostingTask after = taskMapper.selectByKindAndRef(
                MarketingRevenuePostingTask.KIND_REVERSE, REFUND_ID);
        assertEquals(MarketingRevenuePostingTask.STATUS_DONE, after.getStatus().intValue());
        assertNull(after.getReasonCode(), "A 的失败回写不得污染已完成任务的 reason_code");
        assertNull(after.getLeaseOwner(), "完成后不得残留租约持有者");
    }

    /**
     * 入队不得抢走活着的 CLAIMED 租约、不得复活 DONE；{@code next_attempt_at} 必须真的挡住领取。
     */
    @Test
    void enqueueCannotStealALiveClaimAndNextAttemptGatesClaiming() {
        tx.execute(status -> {
            postingService.enqueueReversal(REFUND_ID, REGISTRATION_ID);
            return null;
        });
        long taskId = taskMapper.selectByKindAndRef(
                MarketingRevenuePostingTask.KIND_REVERSE, REFUND_ID).getId();
        assertEquals(1, taskMapper.claim("worker-A", 300, 10));

        // 同一 (kind, ref) 重复入队：活租约必须原样保留。
        tx.execute(status -> {
            postingService.enqueueReversal(REFUND_ID, REGISTRATION_ID);
            return null;
        });
        MarketingRevenuePostingTask claimed = taskMapper.selectByKindAndRef(
                MarketingRevenuePostingTask.KIND_REVERSE, REFUND_ID);
        assertEquals(MarketingRevenuePostingTask.STATUS_CLAIMED, claimed.getStatus().intValue(),
                "重复入队不得把活着的 CLAIMED 重置为 PENDING");
        assertEquals("worker-A", claimed.getLeaseOwner(), "重复入队不得清掉活租约的持有者");
        assertNotNull(claimed.getLeaseExpiresAt(), "重复入队不得清掉租约到期时间");
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE id=? AND status=3 AND lease_owner=?",
                Integer.class, taskId, "worker-A"), "A 必须仍持有该租约");
        assertEquals(1, taskMapper.markDoneIfLeaseHeld(taskId, "worker-A"), "A 的租约必须仍然有效");

        // DONE 后再入队不得复活。
        tx.execute(status -> {
            postingService.enqueueReversal(REFUND_ID, REGISTRATION_ID);
            return null;
        });
        assertEquals(MarketingRevenuePostingTask.STATUS_DONE,
                taskMapper.selectByKindAndRef(MarketingRevenuePostingTask.KIND_REVERSE, REFUND_ID)
                        .getStatus().intValue(), "已完成任务不得被入队复活");

        // next_attempt_at 是领取闸：未来时间不得被领，改成过去后必须能领。
        tx.execute(status -> {
            postingService.enqueueAccrual(REGISTRATION_ID);
            return null;
        });
        db.update("UPDATE marketing_revenue_posting_task"
                        + " SET next_attempt_at = DATE_ADD(NOW(), INTERVAL 1 HOUR)"
                        + " WHERE task_kind=? AND ref_id=?",
                MarketingRevenuePostingTask.KIND_ACCRUE, REGISTRATION_ID);
        assertEquals(0, taskMapper.claim("worker-C", 300, 10), "next_attempt_at 未到期的任务不得被领取");
        assertTrue(taskMapper.selectClaimed("worker-C").isEmpty());

        db.update("UPDATE marketing_revenue_posting_task"
                        + " SET next_attempt_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE)"
                        + " WHERE task_kind=? AND ref_id=?",
                MarketingRevenuePostingTask.KIND_ACCRUE, REGISTRATION_ID);
        assertEquals(1, taskMapper.claim("worker-C", 300, 10), "到期后必须能领取");
        assertEquals(1, taskMapper.selectClaimed("worker-C").size());
    }

    // ------------------------------------------------------------------ 渠道冲销 kind 命名空间

    /**
     * 渠道退款事实主键与 {@code refund_application.id} 是两套命名空间：同一个数值必须能各自成行。
     *
     * <p>这条同时验证 M8F3 的 z 迁移真的生效：建表迁移的 CHECK 只允许 (1,2)，
     * 未执行 z 迁移时 {@code task_kind=3} 会被引擎拒绝。
     */
    @Test
    void channelReversalKindSharesNoNamespaceWithWechatRefundId() {
        long sharedNumericId = 774411L;
        tx.execute(status -> {
            taskMapper.enqueue(postingTask(MarketingRevenuePostingTask.KIND_REVERSE,
                    sharedNumericId, REGISTRATION_ID));
            taskMapper.enqueue(postingTask(MarketingRevenuePostingTask.KIND_CHANNEL_REVERSE,
                    sharedNumericId, REGISTRATION_ID));
            return null;
        });

        assertEquals(2, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE ref_id=?",
                Integer.class, sharedNumericId),
                "两套 fact id 命名空间撞同一数值时必须各自成行，不能互相顶掉");
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE task_kind=?",
                Integer.class, MarketingRevenuePostingTask.KIND_CHANNEL_REVERSE),
                "task_kind=3 必须被 z 迁移放宽后的 CHECK 接受");
    }

    private MarketingRevenuePostingTask postingTask(int kind, long refId, long orderId) {
        MarketingRevenuePostingTask task = new MarketingRevenuePostingTask();
        task.setTaskKind(kind);
        task.setRefId(refId);
        task.setOrderId(orderId);
        task.setStatus(MarketingRevenuePostingTask.STATUS_PENDING);
        task.setAttempts(0);
        task.setNextAttemptAt(new Date(System.currentTimeMillis() - 60_000L));
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        return task;
    }

    // ------------------------------------------------------------------ 退款先到（ME-04 真实 consumer）

    /**
     * 退款到账早于应计落库：该报名单已有**应计任务**（支付回调已入队），但 allocation 还不存在。
     *
     * <p>修复前 consumer 看到 {@code allocation == null} 就 return，冲销任务永远不会被创建，
     * 队列里没有任何东西可重试 ⇒ 这笔退款永久漏冲销。本用例在修复前会红。
     *
     * <p>这里**直接 new 真实 consumer**，不手工把冲销塞进队列 —— 否则测的就不是消费入口。
     */
    @Test
    void refundSettledBeforeAccrualEnqueuesReversalThroughTheRealConsumer() {
        seedActiveRule("topic:42:s1", 1, "0.50", "0.50", "0", 0);
        // 支付成功时原事务入队应计（生产路径），但还没被消费 ⇒ allocation 不存在。
        tx.execute(status -> {
            postingService.enqueueAccrual(REGISTRATION_ID);
            return null;
        });
        assertEquals(0, count("marketing_revenue_allocation"));

        MarketingRevenueRefundSettledConsumer consumer = newConsumer();
        tx.execute(status -> {
            consumer.onRefundSettled(refundSettledFact());
            return null;
        });

        MarketingRevenuePostingTask reverse = taskMapper.selectByKindAndRef(
                MarketingRevenuePostingTask.KIND_REVERSE, REFUND_ID);
        assertNotNull(reverse,
                "allocation 缺失但该报名单已有应计任务时，consumer 必须入队冲销；"
                        + "直接 return 会让这笔退款永久漏冲销");
        assertEquals(MarketingRevenuePostingTask.STATUS_PENDING, reverse.getStatus().intValue(),
                "新入队的冲销任务必须是待处理");

        // 接续既有队列用例：应计到账后，同一队列把应计重放与冲销一起消费掉。
        allocationService.accrue(accrualRequest());
        assertEquals(2, postingService.consumeDue(10), "应计重放与冲销都应成功");
        MarketingRevenueAllocation allocation = allocationMapper.selectByOrder(
                MarketingRevenueAllocation.ORDER_REGISTRATION, REGISTRATION_ID);
        assertEquals(MarketingRevenueAllocation.STATUS_REVERSED, allocation.getStatus().intValue());
        assertEquals(allocation.getNetFen().longValue(), allocation.getReversedFen().longValue(),
                "全额退款必须把净额冲满");
        assertEquals(MarketingRevenuePostingTask.STATUS_DONE,
                taskMapper.selectByKindAndRef(MarketingRevenuePostingTask.KIND_REVERSE, REFUND_ID)
                        .getStatus().intValue(), "冲销任务最终必须完成");
    }

    /**
     * 真正与本域无关的退款（没有分配快照、没有应计任务、没有已验证支付事实）必须静默 noop：
     * 既不能凭空排队，也不能抛错让一笔合法退款的回调整笔回滚。
     */
    @Test
    void refundSettledWithoutAnySourceIsSilentNoop() {
        MarketingRevenueRefundSettledConsumer consumer = newConsumer();
        tx.execute(status -> {
            consumer.onRefundSettled(refundSettledFact());
            return null;
        });

        assertEquals(0, count("marketing_revenue_posting_task"), "无关域不得凭空排队");
        assertEquals(0, count("marketing_revenue_allocation"));
    }

    private RefundSettledFact refundSettledFact() {
        return new RefundSettledFact(REGISTRATION_ID, REFUND_ID, "RF-IT-6101", "WXRF-IT-6101",
                5L, 5L, "CNY", 8801L, "b".repeat(64));
    }

    /**
     * 用**真实** consumer，但按参数类型注入本 IT 已持有的真协作者。
     *
     * <p>M0 正在同一共享树上调整该 consumer 的依赖形状（2 参 + 队列查询口，或 3 参 + taskMapper）。
     * 按类型解析可以同时兼容两种形状，既不改生产源码，也不把测试钉死在一个中间版本上；
     * 一旦出现本 IT 无法注入的新依赖就直接失败，而不是静默跳过。
     */
    private MarketingRevenueRefundSettledConsumer newConsumer() {
        for (java.lang.reflect.Constructor<?> ctor
                : MarketingRevenueRefundSettledConsumer.class.getConstructors()) {
            Class<?>[] types = ctor.getParameterTypes();
            Object[] args = new Object[types.length];
            boolean resolved = true;
            for (int i = 0; i < types.length; i++) {
                if (types[i].isInstance(postingService)) {
                    args[i] = postingService;
                } else if (types[i].isInstance(allocationMapper)) {
                    args[i] = allocationMapper;
                } else if (types[i].isInstance(taskMapper)) {
                    args[i] = taskMapper;
                } else {
                    resolved = false;
                    break;
                }
            }
            if (!resolved) {
                continue;
            }
            try {
                return (MarketingRevenueRefundSettledConsumer) ctor.newInstance(args);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                        "无法实例化真实 consumer: " + failure.getMessage(), failure);
            }
        }
        throw new IllegalStateException("真实 consumer 的依赖形状超出本 IT 可注入范围");
    }

    // ------------------------------------------------------------------ fixtures

    private void seedActiveRule(String ruleKey, int scenario, String authorRate, String platformRate,
                                String referralRate, int fundingSide) {
        db.update("INSERT INTO marketing_revenue_rule"
                        + " (rule_key,version,scenario,currency,author_base_rate,platform_base_rate,"
                        + " referral_rate,referral_funding_side,contract_ref,payee_member_id,hold_days,status,"
                        + " create_time,publish_time)"
                        + " VALUES(?,1,?,'CNY',?,?,?,?,'contract-2026-11',?,14,1,NOW(),NOW())",
                ruleKey, scenario, new BigDecimal(authorRate), new BigDecimal(platformRate),
                new BigDecimal(referralRate), fundingSide, CONTRACT_PAYEE);
    }

    private RevenueAccrualRequest accrualRequest() {
        RevenueAccrualRequest request = new RevenueAccrualRequest();
        request.setRegistrationId(REGISTRATION_ID);
        request.setTaxAmount(BigDecimal.ZERO);
        request.setPaymentFeeAmount(BigDecimal.ZERO);
        request.setChannelCommissionAmount(BigDecimal.ZERO);
        request.setFeeBasisRef("contract-2026-11:no-fee-it");
        request.setSourceFact("it");
        return request;
    }

    private RevenueReversalRequest reversalRequest() {
        RevenueReversalRequest request = new RevenueReversalRequest();
        request.setRefundId(REFUND_ID);
        request.setEventType("refund");
        return request;
    }

    private int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /**
     * 【界外 read port 桩】{@code RevenueFactSource} 由支付/渠道域实现（微信已验证事实、
     * 渠道验证事实），不在本 IT 的所有权内，也不是本用例要证明的对象。
     * 这里只回固定的 5 分已付/已退事实，让分配与冲销算术可确定；
     * 真实事实读取由 WechatRevenueFactSource / ChannelPaymentFactSource 的测试负责。
     */
    private final class StubFacts implements RevenueFactSource {
        @Override
        public Optional<PaymentFact> verifiedPayment(CmsRegistration registration) {
            return Optional.of(new PaymentFact(5L, "CNY", FACT_HASH, "WXTX-IT"));
        }

        @Override
        public Optional<RefundFact> verifiedRefund(RefundApplication refund, CmsRegistration registration) {
            return Optional.of(new RefundFact(5L, 5L, "CNY", "b".repeat(64), refund.getRefundNo()));
        }
    }

    private CmsRegistrationMapper registrationMapper() {
        CmsRegistrationMapper mapper = mock(CmsRegistrationMapper.class);
        when(mapper.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
        return mapper;
    }

    private CmsRegistration registration() {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(REGISTRATION_ID);
        registration.setRegistrationNo("T20261108QUEUE");
        registration.setTransactionId("WXTX-IT");
        registration.setOwnerType(1L);
        registration.setOwnerId(TOPIC_ID);
        registration.setMemberId(777L);
        registration.setPaymentStatus(2);
        registration.setOriginalAmount(new BigDecimal("0.05"));
        registration.setDiscountAmount(BigDecimal.ZERO);
        registration.setTotalAmount(new BigDecimal("0.05"));
        registration.setPayableAmount(new BigDecimal("0.05"));
        registration.setWechatPaymentAmount(new BigDecimal("0.05"));
        registration.setPointDeductAmount(BigDecimal.ZERO);
        return registration;
    }

    private RefundApplicationMapper refundMapper() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        when(mapper.selectRefundApplicationById(anyLong())).thenAnswer(invocation -> {
            RefundApplication refund = new RefundApplication();
            refund.setId(invocation.getArgument(0));
            refund.setRefundNo("RF-IT-6101");
            refund.setSourceType("registration");
            refund.setSourceId(REGISTRATION_ID);
            refund.setPayoutStatus(RefundApplication.PAYOUT_SUCCESS);
            refund.setRefundAmount(new BigDecimal("0.05"));
            return refund;
        });
        return mapper;
    }

    private ICmsTopicService topicService() {
        ICmsTopicService service = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(CONTENT_OWNER);
        when(service.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        return service;
    }

    private IUmsMemberService usableMemberService() {
        IUmsMemberService service = mock(IUmsMemberService.class);
        UmsMember payee = new UmsMember();
        payee.setId(CONTRACT_PAYEE);
        payee.setStatus(1L);
        payee.setDelFlag(0);
        when(service.selectUmsMemberById(CONTRACT_PAYEE)).thenReturn(payee);
        return service;
    }
}

package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.RevenueAccrualRequest;
import com.chengyinhub.business.domain.dto.RevenueReversalRequest;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocationLine;
import com.chengyinhub.business.domain.marketing.MarketingRevenuePostingTask;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.MarketingAttributionMapper;
import com.chengyinhub.business.mapper.MarketingRevenueAllocationLineMapper;
import com.chengyinhub.business.mapper.MarketingRevenueAllocationMapper;
import com.chengyinhub.business.mapper.MarketingRevenuePostingTaskMapper;
import com.chengyinhub.business.mapper.MarketingRevenueRuleMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMarketingRevenueRuleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.MarketingRevenueAllocationServiceImpl;
import com.chengyinhub.business.service.impl.MarketingRevenueRuleServiceImpl;
import com.chengyinhub.business.service.support.RevenueFactSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.chengyinhub.business.service.support.WechatRevenueFactSource;

/**
 * 真实 MySQL 并发/唯一键/事务 IT（源码；本机不执行，交批准 CI/VPS）。
 *
 * <p>这里验证的是**单元测试无法证明**的东西：
 * <ul>
 *   <li>(order_type, order_id) 唯一键让并发应计只产生一行，其余读到同一行；</li>
 *   <li>同一退款事件并发冲销只落一份分录（uk_revenue_line_event）；</li>
 *   <li>生成列 active_key 让并发发布不会出现两个 ACTIVE；</li>
 *   <li>金额整数分与符号 CHECK 约束在真实 InnoDB 上生效；</li>
 *   <li>REQUIRES_NEW 下分配失败不会把调用方事务标 rollbackOnly。</li>
 * </ul>
 *
 * <p>运行前置（缺一即跳过，不会伪通过）：
 * <pre>
 *   RUNNER_ENVIRONMENT=github-hosted
 *   CHENGYIN_M5_REVENUE_DDL_APPROVED=1
 *   CHENGYIN_MYBATIS_IT_HOST=127.0.0.1
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_M5_REVENUE_DDL_APPROVED", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketingRevenueAllocationMySqlIT {

    private final String suite = "m5rev_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private MarketingRevenueAllocationMapper allocationMapper;
    private MarketingRevenueAllocationLineMapper lineMapper;
    private MarketingRevenuePostingTaskMapper taskMapper;
    private MarketingRevenueRuleMapper ruleMapper;
    private MarketingRevenueAllocationServiceImpl allocationService;
    private MarketingRevenueRuleServiceImpl ruleService;
    private RevenueFactSource factSource;

    private static final long REGISTRATION_ID = 555L;
    private static final long TOPIC_ID = 42L;
    private static final long CONTENT_OWNER = 9L;
    private static final long CONTRACT_PAYEE = 77L;
    private static final String FACT_HASH = "a".repeat(64);

    @BeforeAll
    void schemaAndRealMappers() throws Exception {
        assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1", System.getenv("CHENGYIN_M5_REVENUE_DDL_APPROVED"),
                "M5 表结构必须在批准的测试库建；未获批即失败而不是静默跳过");
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()));

        source = P0AcceptanceSchema.dataSource(suite);
        db = new JdbcTemplate(source);
        assertEquals(MybatisItDatabase.databaseName(suite),
                db.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(
                db.queryForObject("SELECT VERSION()", String.class)));

        // 只建本域自己的表；不建、不改任何既有资金表。
        P0AcceptanceSchema.migrations(source, "migration_marketing_revenue_allocation_20261108.sql");

        allocationMapper = P0AcceptanceSchema.sql(source,
                "MarketingRevenueAllocationMapper")
                .getMapper(MarketingRevenueAllocationMapper.class);
        lineMapper = P0AcceptanceSchema.sql(source,
                "MarketingRevenueAllocationLineMapper")
                .getMapper(MarketingRevenueAllocationLineMapper.class);
        taskMapper = P0AcceptanceSchema.sql(source,
                "MarketingRevenuePostingTaskMapper")
                .getMapper(MarketingRevenuePostingTaskMapper.class);
        ruleMapper = P0AcceptanceSchema.sql(source,
                "MarketingRevenueRuleMapper")
                .getMapper(MarketingRevenueRuleMapper.class);

        factSource = new FixedFactSource();
        ruleService = new MarketingRevenueRuleServiceImpl(ruleMapper);
        allocationService = new MarketingRevenueAllocationServiceImpl(allocationMapper, lineMapper,
                noAttribution(), noRegistrations(), refunds(), topicService(),
                noActivities(), ruleService, usableMemberService(), factSource);
    }

    @AfterAll
    void cleanup() {
        if (db != null) {
            assertTrue(MybatisItDatabase.databaseName(suite).matches("chengyin_mybatis_m5rev_[a-z0-9_]+"));
            P0AcceptanceSchema.drop(db, suite);
        }
    }

    @BeforeEach
    void clearOwnedTables() {
        // 夹具隔离：PER_CLASS 复用实例，三分钱用例不能污染后续的 100 元事实桩。
        seededPaidFen = 10000L;
        // 自引用外键要求先删冲销子行，再删原应计行，不关闭约束。
        db.update("DELETE FROM marketing_revenue_allocation_line WHERE reversal_of_line_id IS NOT NULL");
        for (String table : List.of("marketing_revenue_allocation_line", "marketing_revenue_allocation",
                "marketing_revenue_posting_task", "marketing_revenue_rule")) {
            db.update("DELETE FROM " + table);
        }
    }

    // ------------------------------------------------------------------ 并发应计

    @Test
    void concurrentAccrualOfTheSameOrderProducesExactlyOneSnapshot() throws Exception {
        seedActiveRule();

        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        AtomicInteger posted = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    assertTrue(start.await(20, TimeUnit.SECONDS));
                    try {
                        return allocationService.accrue(accrualRequest()).getAllocationId();
                    } catch (MarketingRevenueException replayOrConflict) {
                        // 并发下允许"读到赢家后重放"，但不允许出现第二个 allocation id。
                        MarketingRevenueAllocation winner = allocationMapper.selectByOrder(
                                MarketingRevenueAllocation.ORDER_REGISTRATION, REGISTRATION_ID);
                        assertNotNull(winner, "并发失败时必须能找到既有快照");
                        posted.incrementAndGet();
                        return winner.getId();
                    }
                }));
            }
            start.countDown();
            Long first = results.get(0).get(60, TimeUnit.SECONDS);
            for (Future<Long> future : results) {
                assertEquals(first, future.get(60, TimeUnit.SECONDS), "必须全部收敛到同一分配快照");
            }
        }
        assertEquals(1, count("marketing_revenue_allocation"),
                "(order_type, order_id) 唯一键必须阻止第二个应计");
        // 该规则的 referral_rate=0 / referral_funding_side=0，split 不会产出 referrer 份额，
        // 应计只有 author + platform 两条；与同类退款用例按 2 条断言一致。
        assertEquals(2, count("marketing_revenue_allocation_line"), "应计分录只写一份(author+platform)");
    }

    // ------------------------------------------------------------------ 并发冲销

    @Test
    void concurrentReversalOfTheSameRefundWritesOneEventReceipt() throws Exception {
        seedActiveRule();
        allocationService.accrue(accrualRequest());
        MarketingRevenueAllocation allocation = allocationMapper.selectByOrder(
                MarketingRevenueAllocation.ORDER_REGISTRATION, REGISTRATION_ID);

        int threads = 5;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    assertTrue(start.await(20, TimeUnit.SECONDS));
                    try {
                        return (long) allocationService.reverse(reversalRequest(9001L)).getLines().size();
                    } catch (RuntimeException raced) {
                        return -1L;
                    }
                }));
            }
            start.countDown();
            for (Future<Long> future : results) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        int reversalRows = db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_allocation_line WHERE entry_type=2", Integer.class);
        // uk_revenue_line_event 保证同一事件每角色只有一行：author+platform = 2。
        assertEquals(2, reversalRows, "同一退款事件只能落一份冲销分录");

        long reversed = db.queryForObject(
                "SELECT COALESCE(SUM(-amount_fen),0) FROM marketing_revenue_allocation_line WHERE entry_type=2",
                Long.class);
        MarketingRevenueAllocation after = allocationMapper.selectById(allocation.getId());
        assertEquals(reversed, after.getReversedFen(), "累计冲销必须与分录合计一致");
        assertTrue(after.getReversedFen() <= after.getNetFen(), "累计冲销不得超过应计净额");
    }

    @Test
    void fullReversalThenSameRefundReplayReturnsStableReceipt() {
        seedActiveRule();
        allocationService.accrue(accrualRequest());
        long allocationId = allocationMapper.selectByOrder(
                MarketingRevenueAllocation.ORDER_REGISTRATION, REGISTRATION_ID).getId();

        // 先按原单实付全额冲销（分两笔，合计 = 实付 100.00，第二笔刚好冲满）。
        allocationService.reverse(reversalRequest(9101L)); // 50.00
        allocationService.reverse(reversalRequest(9102L)); // 50.00 → 累计 100.00

        MarketingRevenueAllocation fullyReversed = allocationMapper.selectById(allocationId);
        assertEquals(MarketingRevenueAllocation.STATUS_REVERSED, fullyReversed.getStatus());
        long before = fullyReversed.getReversedFen();

        // 重投最后一笔：必须返回同回执，不得因累计封顶抛错、也不得再写分录。
        int linesBefore = count("marketing_revenue_allocation_line");
        var replay = allocationService.reverse(reversalRequest(9102L));

        assertTrue(replay.isReplayed());
        assertEquals(before, allocationMapper.selectById(allocationId).getReversedFen());
        assertEquals(linesBefore, count("marketing_revenue_allocation_line"),
                "全退后重放不得新增分录");
    }

    @Test
    void threeOneCentRefundsFullyReverseOnRealInnoDb() {
        seedActiveRule();
        // 夹具：0.03 实付、无扣费 → 净额恰 3 分；既有 50%/50% 规则向下取整得 1 分 / 2 分。
        // 三笔各退 1 分必须把净额冲满，用来钉住"逐笔取整不丢分"。
        seededPaidFen = 3L;
        allocationService.accrue(zeroFeeAccrualRequest());

        allocationService.reverse(reversalRequest(9201L, "0.01"));
        allocationService.reverse(reversalRequest(9202L, "0.01"));
        long afterTwo = db.queryForObject(
                "SELECT COALESCE(SUM(-amount_fen),0) FROM marketing_revenue_allocation_line WHERE entry_type=2",
                Long.class);
        assertEquals(2L, afterTwo, "两笔各 1 分必须正好冲 2 分");

        allocationService.reverse(reversalRequest(9203L, "0.01"));
        long afterThree = db.queryForObject(
                "SELECT COALESCE(SUM(-amount_fen),0) FROM marketing_revenue_allocation_line WHERE entry_type=2",
                Long.class);
        assertEquals(3L, afterThree, "三笔退完必须冲满 3 分，无取整残差");

        MarketingRevenueAllocation allocation = allocationMapper.selectByOrder(
                MarketingRevenueAllocation.ORDER_REGISTRATION, REGISTRATION_ID);
        assertEquals(allocation.getNetFen(), afterThree);
        assertEquals(MarketingRevenueAllocation.STATUS_REVERSED, allocation.getStatus());
    }

    // ------------------------------------------------------------------ 规则唯一 ACTIVE

    @Test
    void concurrentPublishNeverProducesTwoActiveVersions() throws Exception {
        // 两个同键草稿，并发发布；生成列 active_key + 唯一键必须只允许一个 ACTIVE。
        long first = seedDraft("topic:42:s1", 1);
        long second = seedDraft("topic:42:s1", 2);

        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (long id : new long[]{first, second}) {
                results.add(pool.submit(() -> {
                    assertTrue(start.await(20, TimeUnit.SECONDS));
                    try {
                        db.update("UPDATE marketing_revenue_rule SET status=1, publish_time=NOW() WHERE id=?", id);
                    } catch (org.springframework.dao.DataIntegrityViolationException expected) {
                        // 撞 uk_revenue_rule_single_active 正是期望行为。
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : results) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        int active = db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_rule WHERE rule_key='topic:42:s1' AND status=1",
                Integer.class);
        assertTrue(active <= 1, "同一 rule_key 同时最多一个 ACTIVE，实际=" + active);
    }

    @Test
    void activeRuleWithoutContractAndPayeeIsRejectedByCheckConstraint() {
        seedDraft("topic:42:s1", 1);
        // CHECK ck_revenue_rule_active_needs_contract 必须挡住"没有合同就给钱"。
        assertCheckViolation("ck_revenue_rule_active_needs_contract",
                () -> db.update("UPDATE marketing_revenue_rule SET status=1, contract_ref=NULL,"
                        + " payee_member_id=NULL WHERE rule_key='topic:42:s1'"));
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_rule WHERE status=1", Integer.class));
    }

    private void assertCheckViolation(String constraint, org.junit.jupiter.api.function.Executable write) {
        DataAccessException failure = assertThrows(DataAccessException.class, write);
        SQLException sql = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertEquals(3819, sql.getErrorCode(), "必须由 MySQL CHECK 拒绝，不能是其它 SQL 错误");
        assertTrue(sql.getMessage().contains("'" + constraint + "'"), sql.getMessage());
    }

    // ------------------------------------------------------------------ 约束与符号

    @Test
    void signAndEventPresenceConstraintsHoldOnRealInnoDb() {
        seedActiveRule();
        allocationService.accrue(accrualRequest());
        long allocationId = allocationMapper.selectByOrder(
                MarketingRevenueAllocation.ORDER_REGISTRATION, REGISTRATION_ID).getId();

        long originLineId = db.queryForObject(
                "SELECT id FROM marketing_revenue_allocation_line WHERE allocation_id=? AND line_role='author'",
                Long.class, allocationId);
        int linesBefore = count("marketing_revenue_allocation_line");

        // 应计分录不得为负。
        assertCheckViolation("ck_revenue_line_sign", () -> db.update(
                "INSERT INTO marketing_revenue_allocation_line"
                        + " (allocation_id,entry_type,line_role,funding_side,basis_fen,rate,amount_fen,"
                        + " currency,entry_key,create_time)"
                        + " VALUES(?,1,'author',0,100,0.5,-1,'CNY','neg-accrue',NOW())", allocationId));
        // 冲销分录必须带事件身份（event_ref/金额/币种/原单/reversal_of_line_id）。
        assertCheckViolation("ck_revenue_line_event_presence", () -> db.update(
                "INSERT INTO marketing_revenue_allocation_line"
                        + " (allocation_id,entry_type,line_role,funding_side,basis_fen,rate,amount_fen,"
                        + " currency,entry_key,create_time)"
                        + " VALUES(?,2,'author',0,100,0.5,-1,'CNY','no-event',NOW())", allocationId));
        // 冲销分录不得为正。
        assertCheckViolation("ck_revenue_line_sign", () -> db.update(
                "INSERT INTO marketing_revenue_allocation_line"
                        + " (allocation_id,entry_type,line_role,funding_side,basis_fen,rate,amount_fen,"
                        + " currency,entry_key,event_ref,event_amount_fen,event_currency,event_order_type,"
                        + " event_order_id,reversal_of_line_id,create_time)"
                        + " VALUES(?,2,'author',0,100,0.5,1,'CNY','pos-reverse','refund:1',50,'CNY',1,555,"
                        + " ?,NOW())", allocationId, originLineId));
        assertEquals(linesBefore, count("marketing_revenue_allocation_line"), "约束拒绝后不得留下无效分录");
    }

    // ------------------------------------------------------------------ REQUIRES_NEW

    @Test
    void enqueueInCallerTransactionIsAtomicWithTheCallerAndConsumedAfterCommit() {
        // 原事务入队 → 回滚时任务一起消失（不产生孤儿任务）。
        TransactionTemplate caller = new TransactionTemplate(new DataSourceTransactionManager(source));
        caller.execute(status -> {
            taskMapper.enqueue(postingTask(MarketingRevenuePostingTask.KIND_ACCRUE, REGISTRATION_ID, REGISTRATION_ID));
            assertThrows(MarketingRevenueException.class, () -> allocationService.accrue(accrualRequest()),
                    "此刻没有已发布规则，应计必须失败");
            assertFalse(status.isRollbackOnly(), "被捕获的分配失败不得把调用方事务标 rollbackOnly");
            status.setRollbackOnly(); // 调用方自己决定回滚
            return null;
        });
        assertEquals(0, count("marketing_revenue_posting_task"), "调用方回滚后任务必须一起回滚");

        // 提交路径：任务留在队列，原事实提交后消费成功。
        caller.execute(status -> {
            taskMapper.enqueue(postingTask(MarketingRevenuePostingTask.KIND_ACCRUE, REGISTRATION_ID, REGISTRATION_ID));
            return null;
        });
        assertEquals(1, count("marketing_revenue_posting_task"));
        seedActiveRule();
        taskMapper.claim("it-owner", 300, 10);
        List<MarketingRevenuePostingTask> claimed = taskMapper.selectClaimed("it-owner");
        assertEquals(1, claimed.size(), "提交后必须能被租约领取");
        allocationService.accrue(accrualRequest());
        taskMapper.markDone(claimed.get(0).getId());
        assertEquals(1, count("marketing_revenue_allocation"));
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE status=1", Integer.class));
    }

    @Test
    void duplicateEnqueueForTheSameReferenceKeepsOneRow() {
        TransactionTemplate caller = new TransactionTemplate(new DataSourceTransactionManager(source));
        caller.execute(status -> {
            for (int i = 0; i < 3; i++) {
                taskMapper.enqueue(postingTask(MarketingRevenuePostingTask.KIND_ACCRUE,
                        REGISTRATION_ID, REGISTRATION_ID));
            }
            return null;
        });
        assertEquals(1, count("marketing_revenue_posting_task"),
                "uk_revenue_posting_ref 必须把重复入队收敛成一行");
    }

    @Test
    void doneTaskIsNotRequeuedByALaterDuplicate() {
        TransactionTemplate caller = new TransactionTemplate(new DataSourceTransactionManager(source));
        caller.execute(status -> {
            taskMapper.enqueue(postingTask(MarketingRevenuePostingTask.KIND_ACCRUE,
                    REGISTRATION_ID, REGISTRATION_ID));
            return null;
        });
        taskMapper.claim("it-owner", 300, 10);
        MarketingRevenuePostingTask row = taskMapper.selectClaimed("it-owner").get(0);
        taskMapper.markDone(row.getId());
        caller.execute(status -> {
            taskMapper.enqueue(postingTask(MarketingRevenuePostingTask.KIND_ACCRUE,
                    REGISTRATION_ID, REGISTRATION_ID));
            return null;
        });
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE status=1", Integer.class),
                "已完成的应计不能因为重复 enqueue 被重新排队");
        taskMapper.claim("it-owner", 300, 10);
        assertTrue(taskMapper.selectClaimed("it-owner").isEmpty(),
                "已完成的应计不能再次被领取");
    }

    private MarketingRevenuePostingTask postingTask(int kind, long refId, long orderId) {
        MarketingRevenuePostingTask task = new MarketingRevenuePostingTask();
        task.setTaskKind(kind);
        task.setRefId(refId);
        task.setOrderId(orderId);
        task.setStatus(MarketingRevenuePostingTask.STATUS_PENDING);
        task.setAttempts(0);
        task.setNextAttemptAt(new java.util.Date());
        task.setCreateTime(new java.util.Date());
        task.setUpdateTime(new java.util.Date());
        return task;
    }

    @Test
    void expiredLeaseReturnsTheTaskToTheQueue() {
        TransactionTemplate caller = new TransactionTemplate(new DataSourceTransactionManager(source));
        caller.execute(status -> {
            taskMapper.enqueue(postingTask(MarketingRevenuePostingTask.KIND_ACCRUE,
                    REGISTRATION_ID, REGISTRATION_ID));
            return null;
        });
        // 领取后强行把租约改成过期，模拟进程崩溃未回写。
        taskMapper.claim("dead-worker", 300, 10);
        db.update("UPDATE marketing_revenue_posting_task SET lease_expires_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE)"
                + " WHERE status = 3");
        assertEquals(1, taskMapper.reclaimExpired(), "过期租约必须被回收");
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE status = 3", Integer.class));
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE status = 0", Integer.class),
                "回收后任务回到 PENDING，可被重新领取");
        taskMapper.claim("new-worker", 300, 10);
        assertEquals(1, taskMapper.selectClaimed("new-worker").size(), "新持有者必须能接管");
    }

    @Test
    void failedExecutionRollsBackItsOwnWorkButStillPersistsTheFailureRecord() {
        seedActiveRule();
        // 先种入应计快照与待消费任务；下方以不存在的退款单验证失败事务回滚。
        allocationService.accrue(accrualRequest());
        long allocationId = allocationMapper.selectByOrder(
                MarketingRevenueAllocation.ORDER_REGISTRATION, REGISTRATION_ID).getId();
        int linesBefore = count("marketing_revenue_allocation_line");

        TransactionTemplate caller = new TransactionTemplate(new DataSourceTransactionManager(source));
        caller.execute(status -> {
            taskMapper.enqueue(postingTask(MarketingRevenuePostingTask.KIND_ACCRUE,
                    REGISTRATION_ID, REGISTRATION_ID));
            return null;
        });
        taskMapper.claim("it-owner", 300, 10);
        MarketingRevenuePostingTask row = taskMapper.selectClaimed("it-owner").get(0);

        // 模拟执行事务回滚：应计在同一单上幂等重放（成功），因此这里用反向分支制造失败 ——
        // 该单没有退款单，reverse 会因 REFUND_NOT_FOUND 抛出。
        long failingRefundId = 999999L;
        TransactionTemplate exec = new TransactionTemplate(new DataSourceTransactionManager(source));
        MarketingRevenueException failure = assertThrows(MarketingRevenueException.class, () -> exec.execute(status -> {
            RevenueReversalRequest request = new RevenueReversalRequest();
            request.setRefundId(failingRefundId);
            allocationService.reverse(request);
            return null;
        }));

        assertEquals("REFUND_NOT_FOUND", failure.getReasonCode(), "缺失退款夹具必须命中查无分支");

        // 执行事务已回滚（没有新分录），失败状态由独立事务写入。
        assertEquals(linesBefore, count("marketing_revenue_allocation_line"),
                "失败的执行事务不得留下半笔分录");
        taskMapper.markFailed(row.getId(), "REFUND_NOT_FOUND", "no such refund",
                new java.util.Date(System.currentTimeMillis() + 60_000L));
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE status = 2 AND attempts = 1",
                Integer.class), "失败状态与 attempts 必须在执行事务回滚后仍然落库");
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE reason_code = 'REFUND_NOT_FOUND'",
                Integer.class));
        assertNotNull(allocationMapper.selectById(allocationId), "原分配快照不得因失败任务被改动");
    }

    @Test
    void reConsumingTheSameFactDoesNotDoublePost() {
        seedActiveRule();
        TransactionTemplate caller = new TransactionTemplate(new DataSourceTransactionManager(source));
        caller.execute(status -> {
            taskMapper.enqueue(postingTask(MarketingRevenuePostingTask.KIND_ACCRUE,
                    REGISTRATION_ID, REGISTRATION_ID));
            return null;
        });

        // 第一轮：应计一次。
        taskMapper.claim("w1", 300, 10);
        allocationService.accrue(accrualRequest());
        taskMapper.markDone(taskMapper.selectClaimed("w1").get(0).getId());
        assertEquals(1, count("marketing_revenue_allocation"));

        // 重复入队 + 再消费：应计按原单幂等重放，不得产生第二份快照/分录。
        int linesBefore = count("marketing_revenue_allocation_line");
        caller.execute(status -> {
            taskMapper.enqueue(postingTask(MarketingRevenuePostingTask.KIND_ACCRUE,
                    REGISTRATION_ID, REGISTRATION_ID));
            return null;
        });
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE status = 0", Integer.class),
                "已 DONE 的任务不能被重复入队复活");

        allocationService.accrue(accrualRequest());
        assertEquals(1, count("marketing_revenue_allocation"), "同一原单只能有一份快照");
        assertEquals(linesBefore, count("marketing_revenue_allocation_line"), "不得追加第二份应计分录");
    }

    // ------------------------------------------------------------------ helpers

    private long seededPaidFen = 10000L;

    private void seedActiveRule() {
        db.update("INSERT INTO marketing_revenue_rule"
                + " (rule_key,version,scenario,currency,author_base_rate,platform_base_rate,"
                + " referral_rate,referral_funding_side,contract_ref,payee_member_id,hold_days,status,"
                + " create_time,publish_time)"
                + " VALUES('topic:42:s1',1,1,'CNY',0.50,0.50,0,0,'contract-2026-11',?,14,1,NOW(),NOW())",
                CONTRACT_PAYEE);
    }

    private long seedDraft(String ruleKey, int version) {
        db.update("INSERT INTO marketing_revenue_rule"
                + " (rule_key,version,scenario,currency,author_base_rate,platform_base_rate,"
                + " referral_rate,referral_funding_side,contract_ref,payee_member_id,hold_days,status,create_time)"
                + " VALUES(?,?,1,'CNY',0.50,0.50,0,0,'contract-2026-11',?,14,0,NOW())",
                ruleKey, version, CONTRACT_PAYEE);
        return db.queryForObject("SELECT id FROM marketing_revenue_rule WHERE rule_key=? AND version=?",
                Long.class, ruleKey, version);
    }

    private RevenueAccrualRequest accrualRequest() {
        RevenueAccrualRequest request = new RevenueAccrualRequest();
        request.setRegistrationId(REGISTRATION_ID);
        request.setTaxAmount(new BigDecimal("6.00"));
        request.setPaymentFeeAmount(new BigDecimal("0.60"));
        request.setChannelCommissionAmount(BigDecimal.ZERO);
        request.setFeeBasisRef("contract-2026-11:tax-fee-v1");
        request.setSourceFact("payment-callback");
        return request;
    }

    /** 零扣费请求：用于 3 分钱这种极小金额，税费/手续费必须按 0 且带证据，否则净额会被扣成负。 */
    private RevenueAccrualRequest zeroFeeAccrualRequest() {
        RevenueAccrualRequest request = new RevenueAccrualRequest();
        request.setRegistrationId(REGISTRATION_ID);
        request.setTaxAmount(BigDecimal.ZERO);
        request.setPaymentFeeAmount(BigDecimal.ZERO);
        request.setChannelCommissionAmount(BigDecimal.ZERO);
        request.setFeeBasisRef("contract-2026-11:no-fee-minimum-case");
        request.setSourceFact("payment-callback");
        return request;
    }

    private RevenueReversalRequest reversalRequest(long refundId) {
        return reversalRequest(refundId, null);
    }

    /** 金额不由请求传入（服务端从退款单与通道事实读）；保留重载只为标注用例意图。 */
    private RevenueReversalRequest reversalRequest(long refundId, String unusedAmountLabel) {
        RevenueReversalRequest request = new RevenueReversalRequest();
        request.setRefundId(refundId);
        request.setEventType("refund");
        return request;
    }

    private int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    // ------------------------------------------------------------------ 固定事实桩（不碰既有支付表）

    /**
     * 事实桩：只返回本 IT 需要的最小事实集，让测试聚焦在唯一键/事务/精度上。
     * 真实支付事实读取由 {@code WechatRevenueFactSource} 承担，其行为另有单测覆盖。
     */
    private final class FixedFactSource implements RevenueFactSource {
        @Override
        public Optional<PaymentFact> verifiedPayment(CmsRegistration registration) {
            return Optional.of(new PaymentFact(seededPaidFen, "CNY", FACT_HASH, "WXTX123"));
        }

        @Override
        public Optional<RefundFact> verifiedRefund(com.chengyinhub.business.domain.RefundApplication refund,
                                                   CmsRegistration registration) {
            long amountFen = refund.getRefundAmount() == null
                    ? 0L : refund.getRefundAmount().movePointRight(2).longValueExact();
            return Optional.of(new RefundFact(amountFen, seededPaidFen, "CNY", "b".repeat(64),
                    refund.getRefundNo()));
        }
    }

    private MarketingAttributionMapper noAttribution() {
        MarketingAttributionMapper mapper = mock(MarketingAttributionMapper.class);
        when(mapper.selectByOrder(anyInt(), anyLong())).thenReturn(null); // 无归因 → 平台代售场景
        return mapper;
    }

    private CmsRegistrationMapper noRegistrations() {
        CmsRegistrationMapper mapper = mock(CmsRegistrationMapper.class);
        when(mapper.selectCmsRegistrationById(REGISTRATION_ID)).thenReturn(registration());
        return mapper;
    }

    private CmsRegistration registration() {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(REGISTRATION_ID);
        registration.setRegistrationNo("T20261108ABC");
        registration.setTransactionId("WXTX123");
        registration.setOwnerType(1L);
        registration.setOwnerId(TOPIC_ID);
        registration.setMemberId(777L);
        registration.setPaymentStatus(2);
        registration.setOriginalAmount(new BigDecimal("120.00"));
        registration.setDiscountAmount(new BigDecimal("20.00"));
        registration.setTotalAmount(new BigDecimal("100.00"));
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setWechatPaymentAmount(new BigDecimal("100.00"));
        registration.setPointDeductAmount(BigDecimal.ZERO);
        return registration;
    }

    private RefundApplicationMapper refunds() {
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        when(mapper.selectRefundApplicationById(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            // 夹具只有这些退款单；未种入的 id 必须遵守 mapper 的查无返回 null 合同。
            if (!List.of(9001L, 9101L, 9102L, 9201L, 9202L, 9203L).contains(id)) {
                return null;
            }
            com.chengyinhub.business.domain.RefundApplication refund =
                    new com.chengyinhub.business.domain.RefundApplication();
            refund.setId(id);
            refund.setRefundNo("RF" + id);
            refund.setSourceType("registration");
            refund.setSourceId(REGISTRATION_ID);
            refund.setPayoutStatus(com.chengyinhub.business.domain.RefundApplication.PAYOUT_SUCCESS);
            refund.setRefundAmount(refundAmountOf(id));
            return refund;
        });
        return mapper;
    }

    /** 9101/9102 合计 100.00 = 实付（可退满净额 93.40）；9201..9203 各 0.01。 */
    private BigDecimal refundAmountOf(long refundId) {
        switch ((int) refundId) {
            case 9101:
                return new BigDecimal("50.00");
            case 9102:
                return new BigDecimal("50.00"); // 与 9101 合计 100.00 = 实付，方能退满
            case 9201:
            case 9202:
            case 9203:
                return new BigDecimal("0.01");
            default:
                return new BigDecimal("50.00");
        }
    }

    private ICmsTopicService topicService() {
        ICmsTopicService service = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(CONTENT_OWNER);
        when(service.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        return service;
    }

    private ICmsActivityService noActivities() {
        return mock(ICmsActivityService.class);
    }

    /** 合同收款人必须真实可用，否则应计会被拒（PAYEE_MEMBER_UNUSABLE）。 */
    private IUmsMemberService usableMemberService() {
        IUmsMemberService service = mock(IUmsMemberService.class);
        com.chengyinhub.business.domain.UmsMember payee = new com.chengyinhub.business.domain.UmsMember();
        payee.setId(CONTRACT_PAYEE);
        payee.setStatus(1L);
        payee.setDelFlag(0);
        when(service.selectUmsMemberById(CONTRACT_PAYEE)).thenReturn(payee);
        return service;
    }
}

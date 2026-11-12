package com.chengyinhub.business.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/**
 * 收入投递队列 SQL 的契约（不连库）：租约栅栏 + 失败可重领 + 退避不被绕过。
 *
 * <p>背景（真实缺陷复查）：
 * <ul>
 *   <li>{@code claim} 曾经只领 {@code status = 0}，而 {@code markFailed} 写的是 {@code status = 2}，
 *       ⇒ 一次失败的任务永远不会再被领取。渠道退款是一次性回调、没有第二次入队，
 *       于是"退款先到、费用未核"会永久卡死。</li>
 *   <li>{@code markDone}/{@code markFailed} 曾经只按 id 更新，没有租约校验；
 *       租约过期被回收、或任务被重新入队（{@code enqueue} 把 CLAIMED 重置为 PENDING）后，
 *       旧 worker 仍能覆盖新 worker 的状态。</li>
 * </ul>
 * 本测试只读 mapper XML 的渲染 SQL，防住这两个不变量不被改回去。
 */
class MarketingRevenuePostingTaskMapperXmlTest {

    private static final String RESOURCE = "mapper/business/MarketingRevenuePostingTaskMapper.xml";

    @Test
    void claimPicksUpBothPendingAndFailedButRespectsBackoff() throws Exception {
        Configuration configuration = parse();
        String claim = sql(configuration, statement(configuration, "claim"));

        assertTrue(claim.contains("status in (0, 2)"),
                "必须同时领取 PENDING(0) 与 FAILED(2)，否则失败任务永不再试");
        assertTrue(claim.contains("next_attempt_at <= now()"),
                "重新领取必须受 next_attempt_at 约束，不能绕过退避立刻重跑同一任务");
        assertTrue(claim.contains("lease_owner"), "领取必须写租约持有人");
        assertTrue(claim.contains("lease_expires_at"), "领取必须写租约到期时间，崩溃后可回收");
    }

    @Test
    void reclaimOnlyTouchesExpiredClaimedRows() throws Exception {
        Configuration configuration = parse();
        String reclaim = sql(configuration, statement(configuration, "reclaimExpired"));

        assertTrue(reclaim.contains("status = 3"), "只回收 CLAIMED 行");
        assertTrue(reclaim.contains("lease_expires_at"), "必须按租约到期判断，不能让活着的持有者被抢");
    }

    @Test
    void terminalWritesAreLeaseFenced() throws Exception {
        Configuration configuration = parse();

        String done = sql(configuration, statement(configuration, "markDoneIfLeaseHeld"));
        assertTrue(done.contains("status = 3"), "只能改仍处于 CLAIMED 的行");
        assertTrue(done.contains("lease_owner = ?"),
                "DONE 必须校验租约持有人，旧 worker 不能覆盖新 worker");

        String failed = sql(configuration, statement(configuration, "markFailedIfLeaseHeld"));
        assertTrue(failed.contains("status = 3") && failed.contains("lease_owner = ?"),
                "FAILED 同样必须带租约栅栏，否则会清掉新持有者的租约");
        assertTrue(failed.contains("attempts = attempts + 1"), "失败必须累加尝试次数用于退避");
        assertTrue(failed.contains("next_attempt_at"), "失败必须安排下次重试时间");
    }

    @Test
    void unfencedWritesAreFixtureOnlyNames() throws Exception {
        Configuration configuration = parse();
        // 不加租约的旧语句仍存在（测试夹具/人工补偿用），但名字必须与生产路径区分开，
        // 这样任何"生产代码误用"都能在 review/测试里一眼看出来。
        String bareDone = sql(configuration, statement(configuration, "markDone"));
        assertFalse(bareDone.contains("lease_owner = ?"),
                "markDone 是手工/夹具口径；一旦它也加栅栏，就说明有人把两套语义合并了，需人工确认");
        assertTrue(configuration.hasStatement(statement(configuration, "markDoneIfLeaseHeld")));
    }

    @Test
    void enqueueNeverStealsALiveLeaseNorRevivesDoneRows() throws Exception {
        Configuration configuration = parse();
        String enqueue = sql(configuration, statement(configuration, "enqueue"));

        assertTrue(enqueue.contains("on duplicate key update"),
                "同 (task_kind, ref_id) 必须幂等入队，不能插重复行");
        assertTrue(enqueue.contains("lease_owner = if(status in (1, 3), lease_owner, null)"),
                "CLAIMED(3) 的活跃租约必须保留：重复入队抢走租约会让旧持有者与新持有者并发跑同一任务");
        assertTrue(enqueue.contains("status = if(status in (1, 3), status, 0)"),
                "DONE(1)/CLAIMED(3) 都不得被重置为 PENDING（僵死租约由 reclaimExpired 处理）");
        assertTrue(enqueue.contains("next_attempt_at = if(status in (1, 3), next_attempt_at, now())"),
                "被保留的行不得改写下次重试时间");
        // MySQL 单表 UPDATE 从左到右求值：status 必须最后赋值，否则上面的 if() 会读到新状态。
        assertTrue(enqueue.indexOf("status = if(status in (1, 3), status, 0)")
                        > enqueue.indexOf("lease_owner = if(status in (1, 3), lease_owner, null)"),
                "status 赋值必须在租约/重试时间之后，否则条件判断会被自身写入污染");
    }

    private static String statement(Configuration configuration, String name) {
        return MarketingRevenuePostingTaskMapper.class.getName() + "." + name;
    }

    private static String sql(Configuration configuration, String statement) {
        return configuration.getMappedStatement(statement).getBoundSql(null).getSql();
    }

    private static Configuration parse() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(input, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
}

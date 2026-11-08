package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.marketing.MarketingRevenuePostingTask;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 真 {@code MarketingRevenuePostingTaskMapper.xml} 跑 H2(MySQL 模式)：租约栅栏与失败重试。
 *
 * <p>覆盖 MAU 指出的真实时序：<b>A 超租 → B 接手并完成 → A 再写失败状态</b>。
 * 期望：A 的回写命中 0 行，DONE 不被覆盖；任务账务幂等（本测试只断言状态机）。
 *
 * <p>claim 语句用了 {@code date_add(now(), interval ? second)}（MySQL 专有），H2 不保证支持，
 * 因此这里不跑 claim，而是直接用 JDBC 构造"已被某 worker 领取"的行；claim 的
 * status/next_attempt 条件与 enqueue 的 {@code if()} 语义由
 * {@code MarketingRevenuePostingTaskMapperXmlTest} 的 SQL 契约测试守护。
 */
class MarketingRevenuePostingTaskMapperH2Test {

    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:revenue_posting_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table marketing_revenue_posting_task("
                + " id bigint auto_increment primary key,"
                + " task_kind tinyint not null,"
                + " ref_id bigint not null,"
                + " order_id bigint not null,"
                + " status tinyint not null default 0,"
                + " attempts int not null default 0,"
                + " lease_owner varchar(64) null,"
                + " lease_expires_at timestamp null,"
                + " reason_code varchar(48) null,"
                + " detail varchar(255) null,"
                + " next_attempt_at timestamp not null,"
                + " create_time timestamp not null,"
                + " update_time timestamp not null,"
                + " constraint uk_revenue_posting_ref unique (task_kind, ref_id))");
    }

    @Test
    void oldOwnerCannotOverwriteTheNewOwnersCompletedTask() throws Exception {
        long id = seedPending(3L, 31L);
        claimAs(id, "worker-A", "2026-11-08 12:00:00");

        try (SqlSession session = factory().openSession(true)) {
            MarketingRevenuePostingTaskMapper mapper = session.getMapper(MarketingRevenuePostingTaskMapper.class);

            // A 的租约过期 → 回收 → B 领取并完成
            assertEquals(0, mapper.markDoneIfLeaseHeld(id, "worker-B"),
                    "B 尚未领取时不能写 DONE");
            claimAs(id, "worker-B", "2026-11-08 13:00:00");
            assertEquals(1, mapper.markDoneIfLeaseHeld(id, "worker-B"));

            // A 醒来后试图写失败/完成：都必须命中 0 行，DONE 不被覆盖
            assertEquals(0, mapper.markFailedIfLeaseHeld(id, "worker-A", "POSTING_FAILED", "stale", later()),
                    "旧 owner 的失败回写必须被租约栅栏挡掉");
            assertEquals(0, mapper.markDoneIfLeaseHeld(id, "worker-A"),
                    "旧 owner 的完成写入同样必须被挡掉");
        }

        assertEquals(1, status(id));
        assertNull(reason(id), "DONE 行不得被旧 worker 塞进失败原因");
        assertNull(leaseOwner(id), "完成后租约必须释放");
    }

    @Test
    void failedTaskKeepsItsBackoffAndNeverBecomesDoneByAStaleOwner() throws Exception {
        long id = seedPending(3L, 32L);
        claimAs(id, "worker-A", "2026-11-08 12:00:00");

        try (SqlSession session = factory().openSession(true)) {
            MarketingRevenuePostingTaskMapper mapper = session.getMapper(MarketingRevenuePostingTaskMapper.class);
            assertEquals(1, mapper.markFailedIfLeaseHeld(id, "worker-A", "FEE_BASIS_MISSING", "费用未核", later()));
            // A 已不是持有者：再次回写失败也不得累加 attempts（否则退避会被旧 worker 无限推后）
            assertEquals(0, mapper.markFailedIfLeaseHeld(id, "worker-A", "FEE_BASIS_MISSING", "费用未核", later()));
        }

        assertEquals(2, status(id));
        assertEquals(1, attempts(id), "attempts 只能由持有者累加一次");
        assertEquals("FEE_BASIS_MISSING", reason(id));
        assertEquals(true, nextAttemptAfterNow(id), "失败行必须保留下次重试时间（退避不被绕过）");
    }

    @Test
    void reclaimOnlyReleasesExpiredLeases() throws Exception {
        long expired = seedPending(3L, 34L);
        long live = seedPending(3L, 35L);
        claimAs(expired, "worker-A", "2020-01-01 00:00:00");
        claimAs(live, "worker-B", "2999-01-01 00:00:00");

        try (SqlSession session = factory().openSession(true)) {
            MarketingRevenuePostingTaskMapper mapper = session.getMapper(MarketingRevenuePostingTaskMapper.class);
            assertEquals(1, mapper.reclaimExpired(), "只回收已过期的那一行");
        }

        assertEquals(0, status(expired));
        assertNull(leaseOwner(expired));
        assertEquals(3, status(live), "活着的持有者不能被抢");
        assertEquals("worker-B", leaseOwner(live));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 直接用 JDBC 造 PENDING 行：{@code enqueue} 的 ON DUPLICATE KEY UPDATE 里用了 MySQL 的
     * {@code if()} 函数，H2 不保证支持；入队语义改由 SQL 契约测试守护。
     */
    private long seedPending(long kind, long refId) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        jdbc.update("insert into marketing_revenue_posting_task"
                        + " (task_kind, ref_id, order_id, status, attempts, reason_code, detail,"
                        + " next_attempt_at, lease_owner, lease_expires_at, create_time, update_time)"
                        + " values (?,?,?,0,0,null,null,?,null,null,?,?)",
                kind, refId, refId, new Timestamp(now.getTime() - 60_000L), now, now);
        Long id = jdbc.queryForObject(
                "select id from marketing_revenue_posting_task where task_kind=? and ref_id=?",
                Long.class, kind, refId);
        return id == null ? -1L : id;
    }

    /** 直接构造"某 worker 已领取"的行：claim 的 date_add 是 MySQL 专有语法，H2 不跑。 */
    private void claimAs(long id, String owner, String leaseUntil) {
        jdbc.update("update marketing_revenue_posting_task set status=3, lease_owner=?,"
                + " lease_expires_at=? where id=?", owner, Timestamp.valueOf(leaseUntil), id);
    }

    private int status(long id) {
        Integer value = jdbc.queryForObject("select status from marketing_revenue_posting_task where id=?",
                Integer.class, id);
        return value == null ? -1 : value;
    }

    private String reason(long id) {
        return jdbc.queryForObject("select reason_code from marketing_revenue_posting_task where id=?",
                String.class, id);
    }

    private String leaseOwner(long id) {
        return jdbc.queryForObject("select lease_owner from marketing_revenue_posting_task where id=?",
                String.class, id);
    }

    private int attempts(long id) {
        Integer value = jdbc.queryForObject("select attempts from marketing_revenue_posting_task where id=?",
                Integer.class, id);
        return value == null ? -1 : value;
    }

    private boolean nextAttemptAfterNow(long id) {
        Timestamp value = jdbc.queryForObject(
                "select next_attempt_at from marketing_revenue_posting_task where id=?",
                Timestamp.class, id);
        return value != null && value.getTime() > System.currentTimeMillis();
    }

    private static Timestamp later() {
        return new Timestamp(System.currentTimeMillis() + 5 * 60_000L);
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration configuration = new Configuration(
                new Environment("revenue-posting", new JdbcTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAlias("MarketingRevenuePostingTask",
                MarketingRevenuePostingTask.class);
        String resource = "mapper/business/MarketingRevenuePostingTaskMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}

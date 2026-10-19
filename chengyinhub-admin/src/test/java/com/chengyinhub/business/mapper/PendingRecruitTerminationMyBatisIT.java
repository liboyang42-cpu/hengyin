package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.service.support.PendingRecruitWorkflowTerminatorImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * W2 招募窗口关闭 seam 的真库覆盖。
 *
 * <p><b>为什么必须是真库而不是 mock mapper</b>:这个 seam 的两条不变量 ——「只动本期」与「可重放」——
 * 完全长在两条 UPDATE 的 {@code where} 里(topic_id 圈定 + status 条件)。mock mapper 只能证明
 * 「Java 调了这个方法」,证明不了「SQL 圈对了行」。隔壁期不被动、第二遍命中 0 行,这两条只有真库能答。
 *
 * <p>库不可达即<b>红</b>,不 skip —— skip 在构建里算绿,而永远不会红的测试比没有测试更危险。
 */
class PendingRecruitTerminationMyBatisIT {

    private static final String DEFAULT_JDBC_URL =
            com.chengyinhub.business.it.MybatisItDatabase.url("pending_recruit");
    private static final String JDBC_URL = System.getProperty("chengyin.mybatis.it.jdbcUrl", DEFAULT_JDBC_URL);

    private static final Long THIS_TOPIC = 9001L;
    private static final Long NEIGHBOUR_TOPIC = 9002L;

    private static final String[] FIXTURE_DDL = {
            "DROP TABLE IF EXISTS coop_invite",
            "DROP TABLE IF EXISTS merchant_chapter_application",
            // 列形状取自生产快照 base_schema_20260818.sql 里这两张表被本 seam 触及的列。
            "CREATE TABLE coop_invite (id BIGINT PRIMARY KEY, topic_id BIGINT, status INT, invite_type INT,"
                    + " del_flag INT NOT NULL DEFAULT 0, update_time DATETIME NULL)",
            "CREATE TABLE merchant_chapter_application (id BIGINT PRIMARY KEY, topic_id BIGINT NOT NULL,"
                    + " chapter_id BIGINT NOT NULL, merchant_id BIGINT NOT NULL,"
                    + " status TINYINT NOT NULL DEFAULT 0, audit_remark VARCHAR(500) NULL,"
                    + " audit_member_id BIGINT NULL, handle_time DATETIME NULL,"
                    + " del_flag TINYINT NOT NULL DEFAULT 0, update_time DATETIME NULL)"
    };

    private static final String[] FIXTURE_DML = {
            // 本期:2 条 pending 邀约(该被关)+ 1 条已接受 + 1 条 invite_type=2 旧商家点位(只读,不许动)+ 1 条已删
            "INSERT INTO coop_invite (id,topic_id,status,invite_type,del_flag) VALUES"
                    + " (1,9001,0,1,0),(2,9001,0,1,0),(3,9001,1,1,0),(4,9001,0,2,0),(5,9001,0,1,2)",
            // 隔壁期:1 条 pending 邀约 —— 边界负控,必须纹丝不动
            "INSERT INTO coop_invite (id,topic_id,status,invite_type,del_flag) VALUES (6,9002,0,1,0)",
            // 本期:2 条待审申请(该被关)+ 1 条已通过 + 1 条已拒绝 + 1 条已撤回(del_flag=2)
            "INSERT INTO merchant_chapter_application (id,topic_id,chapter_id,merchant_id,status,del_flag) VALUES"
                    + " (1,9001,51,101,0,0),(2,9001,52,102,0,0),(3,9001,53,103,1,0),"
                    + " (4,9001,54,104,2,0),(5,9001,55,105,0,2)",
            // 隔壁期:1 条待审申请 —— 边界负控
            "INSERT INTO merchant_chapter_application (id,topic_id,chapter_id,merchant_id,status,del_flag) VALUES"
                    + " (6,9002,56,106,0,0)"
    };

    @Test
    void terminatorClosesOnlyThisTopicsPendingRowsAndIsReplayable() throws Exception {
        SqlSessionFactory factory = factory();
        try (SqlSession session = factory.openSession(true)) {
            try (Statement st = session.getConnection().createStatement()) {
                for (String ddl : FIXTURE_DDL) st.execute(ddl);
                for (String dml : FIXTURE_DML) st.execute(dml);
            }

            CoopInviteMapper inviteMapper = session.getMapper(CoopInviteMapper.class);
            MerchantChapterApplicationMapper applicationMapper =
                    session.getMapper(MerchantChapterApplicationMapper.class);
            PendingRecruitWorkflowTerminatorImpl terminator =
                    new PendingRecruitWorkflowTerminatorImpl(inviteMapper, applicationMapper);

            assertEquals(2, inviteMapper.countPendingByTopic(THIS_TOPIC), "先数:本期 pending 邀约 2 条");
            assertEquals(2, applicationMapper.countPendingByTopic(THIS_TOPIC), "先数:本期待审申请 2 条");

            terminator.terminatePendingUnderTopicLock(THIS_TOPIC, new Date());

            // ① 本期该关的都关了
            assertEquals(2, count(session, "select count(*) from coop_invite where topic_id=9001 and status=5"),
                    "本期两条 pending 邀约必须都置 5已过期");
            assertEquals(2, count(session,
                    "select count(*) from merchant_chapter_application where topic_id=9001 and status=2 and audit_remark is not null"),
                    "本期两条待审申请必须都置 2已拒绝并留下机器原因");
            // 没有人审过它 —— 不许伪造审核人
            assertEquals(0, count(session,
                    "select count(*) from merchant_chapter_application where id in (1,2) and audit_member_id is not null"),
                    "audit_member_id 必须保持 NULL");

            // ② 不该动的一条都没动:已接受邀约 / 旧商家点位邀约 / 已删邀约 / 已通过 / 已拒绝 / 已撤回
            assertEquals(1, count(session, "select count(*) from coop_invite where id=3 and status=1"),
                    "已接受(status=1)的邀约不属于 pending,不许被关");
            assertEquals(1, count(session, "select count(*) from coop_invite where id=4 and status=0"),
                    "invite_type=2 的旧商家点位邀约是只读的,不许被关");
            assertEquals(1, count(session, "select count(*) from coop_invite where id=5 and status=0"),
                    "已删除(del_flag=2)的邀约不许被动");
            assertEquals(1, count(session, "select count(*) from merchant_chapter_application where id=3 and status=1"),
                    "已通过的申请不许被改成拒绝");
            assertEquals(1, count(session,
                    "select count(*) from merchant_chapter_application where id=5 and status=0 and del_flag=2"),
                    "已撤回的申请不许被动");

            // ③ ★边界负控:隔壁期的 pending 行纹丝不动
            assertEquals(1, count(session, "select count(*) from coop_invite where topic_id=9002 and status=0"),
                    "隔壁期的 pending 邀约不许被本期的终结波及");
            assertEquals(1, count(session,
                    "select count(*) from merchant_chapter_application where topic_id=9002 and status=0"),
                    "隔壁期的待审申请不许被本期的终结波及");

            // ④ ★幂等负控:再跑一遍,两条 update 都必须命中 0 行(否则 assertMatched 也会因先数=0 而对不上)
            assertEquals(0, inviteMapper.countPendingByTopic(THIS_TOPIC));
            assertEquals(0, applicationMapper.countPendingByTopic(THIS_TOPIC));
            terminator.terminatePendingUnderTopicLock(THIS_TOPIC, new Date());
            assertEquals(2, count(session, "select count(*) from coop_invite where topic_id=9001 and status=5"),
                    "重放不得产生第二次副作用");
            assertEquals(1, count(session, "select count(*) from coop_invite where topic_id=9002 and status=0"),
                    "重放也不得波及隔壁期");

            // ⑤ 空参 fail-closed
            assertThrows(IllegalArgumentException.class,
                    () -> terminator.terminatePendingUnderTopicLock(null, new Date()));
            assertThrows(IllegalArgumentException.class,
                    () -> terminator.terminatePendingUnderTopicLock(THIS_TOPIC, null));
        }
    }

    private static int count(SqlSession session, String sql) throws Exception {
        try (Statement st = session.getConnection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private SqlSessionFactory factory() {
        try {
            requireDedicatedLocalFixture(JDBC_URL);
            Configuration cfg = new Configuration();
            cfg.getTypeAliasRegistry().registerAlias("CoopInvite", CoopInvite.class);
            cfg.getTypeAliasRegistry().registerAlias("MerchantChapterApplication",
                    com.chengyinhub.business.domain.MerchantChapterApplication.class);
            PooledDataSource ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", JDBC_URL, com.chengyinhub.business.it.MybatisItDatabase.user(), com.chengyinhub.business.it.MybatisItDatabase.password());
            cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
            for (String r : new String[]{
                    "mapper/business/CoopInviteMapper.xml",
                    "mapper/business/MerchantChapterApplicationMapper.xml"}) {
                try (InputStream in = Resources.getResourceAsStream(r)) {
                    new XMLMapperBuilder(in, cfg, r, cfg.getSqlFragments()).parse();
                }
            }
            SqlSessionFactory f = new SqlSessionFactoryBuilder().build(cfg);
            try (SqlSession probe = f.openSession()) {
                probe.getConnection().isValid(2);
            }
            return f;
        } catch (Throwable t) {
            throw new IllegalStateException("IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),"
                    + "本套必须真跑不得跳过:" + t.getMessage(), t);
        }
    }

    /** 只许连本机专用 fixture 库;生产地址与生产库名一律拒绝在建连接之前。 */
    static void requireDedicatedLocalFixture(String jdbcUrl) {
        // 2026-08-19 一类一库之后库名是 chengyin_mybatis_<suite>[后缀];守卫改成认**家族前缀**,
        // 严格度不降(仍钉死 127.0.0.1 与 chengyin_mybatis_ 前缀,生产地址照旧拒绝 —— 本类负控在打这条)。
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://127.0.0.1:")
                || !jdbcUrl.contains("/chengyin_mybatis_")) {
            throw new IllegalStateException("只允许连本机专用 fixture 库 chengyin_mybatis_*: " + jdbcUrl);
        }
    }

    @Test
    void rejectsAnyNonDedicatedLocalJdbcBeforeDatasourceCreation() {
        assertThrows(IllegalStateException.class,
                () -> requireDedicatedLocalFixture("jdbc:mysql://203.0.113.7:3306/chengyinhubdb"));
        assertThrows(IllegalStateException.class,
                () -> requireDedicatedLocalFixture("jdbc:mysql://127.0.0.1:3306/chengyinhubdb"));
    }
}

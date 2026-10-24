package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ActivityCancelManualRefundCase;
import com.chengyinhub.business.it.MybatisItDatabase;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ActivityCancelPlayerProjectionMyBatisIT {
    private static boolean scratchCreated;
    @Test
    void playerSeesOnlyOwnFrozenCaseAndReadsReopenedStatus() throws Exception {
        String url = MybatisItDatabase.url("cancel_player_projection");
        scratchCreated = true;
        PooledDataSource source = null;
        try {
            source = new PooledDataSource("com.mysql.cj.jdbc.Driver", url,
                    MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration cfg = new Configuration(new Environment("it", new JdbcTransactionFactory(), source));
        cfg.getTypeAliasRegistry().registerAlias("ActivityCancelManualRefundCase", ActivityCancelManualRefundCase.class);
        try (InputStream xml = Resources.getResourceAsStream("mapper/business/ActivityCancelManualRefundCaseMapper.xml")) {
            new XMLMapperBuilder(xml, cfg, "mapper/business/ActivityCancelManualRefundCaseMapper.xml",
                    cfg.getSqlFragments()).parse();
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(cfg).openSession(true);
             Statement sql = session.getConnection().createStatement()) {
            // 查询所需真实列类型；专属scratch，不改变审查库或生产结构。
            sql.execute("create table cms_registration (id bigint primary key, owner_type bigint,"
                    + "owner_id bigint, parent_order_no varchar(64), member_id bigint)");
            sql.execute("create table activity_cancel_manual_refund_case (activity_id bigint, topic_id bigint,"
                    + "parent_order_no varchar(64), payer_registration_id bigint, payer_member_id bigint,"
                    + "case_type varchar(32), status varchar(48),"
                    + "verified_registration_ids_json json, unverified_registration_ids_json json)");
            sql.execute("insert into cms_registration values (1,2,200,'P',99),(2,2,200,'P',99),"
                    + "(3,2,200,'P',99),(4,2,200,'P',100),(5,2,201,'P',99),(6,1,200,'P',99),"
                    + "(7,1,300,'T',99),(8,2,300,'T',99)");
            sql.execute("insert into activity_cancel_manual_refund_case values"
                    + "(200,null,'P',1,99,'MIXED_TICKETS','NO_REFUND','[1]','[2,4,5,6]'),"
                    // FX-05:主题工单挂 topic_id;玩家按主题票(owner_type=1)投影,同 id 的场次票不串。
                    + "(null,300,'T',7,99,'MIXED_TICKETS','PENDING_ASSESSMENT','[9]','[7,8]')");
            ActivityCancelManualRefundCaseMapper mapper = session.getMapper(ActivityCancelManualRefundCaseMapper.class);
            assertEquals("NO_REFUND", mapper.selectPlayerCaseStatus(1L, 99L));
            assertEquals("NO_REFUND", mapper.selectPlayerCaseStatus(2L, 99L));
            assertNull(mapper.selectPlayerCaseStatus(2L, 100L));
            assertNull(mapper.selectPlayerCaseStatus(3L, 99L));
            assertNull(mapper.selectPlayerCaseStatus(4L, 100L));
            assertNull(mapper.selectPlayerCaseStatus(5L, 99L));
            assertNull(mapper.selectPlayerCaseStatus(6L, 99L));
            assertEquals("PENDING_ASSESSMENT", mapper.selectPlayerCaseStatus(7L, 99L));
            assertNull(mapper.selectPlayerCaseStatus(8L, 99L));
            sql.execute("update activity_cancel_manual_refund_case set status='PENDING_ASSESSMENT' where parent_order_no='P'");
            session.clearCache();
            assertEquals("PENDING_ASSESSMENT", mapper.selectPlayerCaseStatus(2L, 99L));
        }
        } finally {
            try { if (source != null) source.forceCloseAll(); }
            finally { dropScratch(); }
        }
    }

    private static void dropScratch() throws Exception {
        if (!scratchCreated) return;
        String database = MybatisItDatabase.databaseName("cancel_player_projection");
        if (!"127.0.0.1".equals(MybatisItDatabase.host()) || !database.matches("chengyin_mybatis_[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Refusing cleanup outside owned loopback scratch");
        }
        try (java.sql.Connection admin = java.sql.DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + MybatisItDatabase.port() + "/?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password());
             java.sql.Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE `" + database + "`");
            try (java.sql.PreparedStatement check = admin.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                check.setString(1, database);
                try (java.sql.ResultSet row = check.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(0, row.getInt(1), "owned scratch must be absent");
                    assertFalse(row.next());
                }
            }
            scratchCreated = false;
        }
    }
}

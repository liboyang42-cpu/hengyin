package com.chengyinhub.business.it;

import com.chengyinhub.business.mapper.NpcChatLogMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真实 MyBatis + 本地 MySQL:证明 npc_chat_log 留存删除的 SQL 语义真的按 create_time 过滤。
 *
 * <p>为什么必须连真库:{@code DataRetentionServiceImplTest} 用 mock mapper 只证明分批循环,
 * 证明不了 {@code where create_time < #{cutoff}} 本身对不对。这条 SQL 是隐私政策留存承诺的执行体,
 * 删错(把近期原文误删)或删不动(NULL 行永活)都要在这里被抓到:
 * <ul>
 *   <li>正控:早于 cutoff 的旧行被删。</li>
 *   <li>负控①:晚于 cutoff 的近期行必须存活 —— 若谁把 {@code <} 写成 {@code >} 或去掉条件,这条转红。</li>
 *   <li>负控②:create_time 为 NULL 的行不被 {@code < cutoff} 命中,必须存活(NULL 比较恒 false)。</li>
 *   <li>LIMIT:单批删除数被 limit 截断(分批护栏的地基)。</li>
 * </ul>
 *
 * <p>本地库缺失时自动 skip(不破坏 CI/常规 mvn test)。手动跑法:
 *   1) brew services start mysql
 *   2) 建库/用户:
 *      CREATE DATABASE chengyin_mybatis_it;
 *      CREATE USER 'ituser'@'127.0.0.1' IDENTIFIED BY 'itpass';
 *      GRANT ALL ON chengyin_mybatis_it.* TO 'ituser'@'127.0.0.1';
 *   ⚠️ 2026-08-19 起改成**一类一库**:库由 MybatisItDatabase 自己 DROP+CREATE(见该类注释),
 *      本地不再需要预先建 chengyin_mybatis_it、也不再用 ituser —— 默认走 root(本机空口令),
 *      连接信息用 CHENGYIN_MYBATIS_IT_HOST/PORT/USER/PASSWORD 覆盖。
 *   3) mvn -pl chengyinhub-admin -am test -Dtest=NpcChatRetentionMyBatisIT
 *
 * <p>本 IT 自建表(CREATE TABLE IF NOT EXISTS,schema 同 migration_npc_chat_log.sql),不依赖手动 source。
 * <p>⚠️ 注意:surefire 默认只收 *Test.java,<b>本 *IT 不会在 CI 自动跑</b>,须 -Dtest 点名。
 */
class NpcChatRetentionMyBatisIT {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS npc_chat_log ("
          + "  id BIGINT PRIMARY KEY AUTO_INCREMENT,"
          + "  user_id BIGINT NOT NULL,"
          + "  profile_id BIGINT NOT NULL,"
          + "  activity_id BIGINT NOT NULL,"
          + "  user_msg TEXT NOT NULL,"
          + "  npc_reply TEXT,"
          + "  safety_flag TINYINT DEFAULT 0,"
          + "  model VARCHAR(32),"
          + "  latency_ms INT, token_in INT, token_out INT,"
          + "  create_time DATETIME,"
          + "  KEY idx_user_activity (user_id, activity_id),"
          + "  KEY idx_safety (safety_flag)"
          + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("npc_chat_retention"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/NpcChatLogMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/NpcChatLogMapper.xml", cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private SqlSession open() {
        try {
            SqlSession s = factory().openSession();
            s.getConnection().isValid(2);
            Statement st = s.getConnection().createStatement();
            st.execute(CREATE_TABLE);
            st.execute("DELETE FROM npc_chat_log");
            return s;
        } catch (Throwable t) {
            // ★ 2026-08-14:原本是 Assumptions.assumeTrue(false, ...) —— 库连不上就整套 skip,
            // 而 skip 在构建里算**绿**。永远不会红的测试比没有测试更危险:它让人以为验过了。
            // 现在缺库 = 红。跑法见 ci/backend-test.sh(CI 的 backend job 自带 MySQL 服务)。
            throw new IllegalStateException("IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),本套必须真跑不得跳过:" + t.getMessage(), t);
        }
    }

    private void insert(Connection c, String createTimeExpr) throws Exception {
        c.createStatement().execute(
                "INSERT INTO npc_chat_log (user_id, profile_id, activity_id, user_msg, npc_reply, create_time) "
              + "VALUES (1, 1, 0, '原文', '回复', " + createTimeExpr + ")");
    }

    private int count(Connection c, String whereClause) throws Exception {
        try (ResultSet rs = c.createStatement()
                .executeQuery("SELECT COUNT(*) FROM npc_chat_log WHERE " + whereClause)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** 正控 + 双负控:旧行删,近期行 & NULL create_time 行都存活。 */
    @Test
    void deleteBefore_removesOnlyOldRows_recentAndNullSurvive() throws Exception {
        try (SqlSession s = open()) {
            Connection c = s.getConnection();
            insert(c, "'2020-01-01 00:00:00'"); // 旧:早于 cutoff → 应删
            insert(c, "NOW()");                 // 近期:晚于 cutoff → 应活(负控①)
            insert(c, "NULL");                  // NULL:恒不命中 < cutoff → 应活(负控②)

            // cutoff 落在 2020 旧行与 NOW 之间
            Date cutoff = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2021-01-01 00:00:00");
            int deleted = s.getMapper(NpcChatLogMapper.class).deleteNpcChatLogsBefore(cutoff, 1000);
            s.commit();

            assertEquals(1, deleted, "只应删掉那一条 2020 旧行");
            // 按身份断言存活集合,而不是只数个数:
            //   若把 < 写成 >(删近期留旧),deleted 仍=1、总数仍=2,只数个数抓不到 —— 下面三条才是真负控。
            assertEquals(0, count(c, "create_time = '2020-01-01 00:00:00'"), "2020 旧行必须被删掉");
            assertEquals(1, count(c, "create_time IS NULL"), "NULL create_time 行必须存活(NULL 恒不命中 < cutoff)");
            assertEquals(1, count(c, "create_time >= '2021-01-01 00:00:00'"), "近期行必须存活;删到它 = WHERE create_time < cutoff 被写反");
        }
    }

    /** LIMIT 截断:5 条旧行、limit=2,单批只删 2(分批循环的地基)。 */
    @Test
    void deleteBefore_respectsLimit() throws Exception {
        try (SqlSession s = open()) {
            Connection c = s.getConnection();
            for (int i = 0; i < 5; i++) {
                insert(c, "'2020-01-01 00:00:00'");
            }
            Date cutoff = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2021-01-01 00:00:00");
            int deleted = s.getMapper(NpcChatLogMapper.class).deleteNpcChatLogsBefore(cutoff, 2);
            s.commit();

            assertEquals(2, deleted, "单批删除必须被 limit 截断为 2");
            assertEquals(3, count(c, "1 = 1"), "剩 3 条待下一批");
        }
    }
}

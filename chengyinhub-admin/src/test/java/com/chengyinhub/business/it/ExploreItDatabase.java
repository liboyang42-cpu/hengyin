package com.chengyinhub.business.it;

import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 探店日联盟局两套真库 IT 的库接法：走资金门禁的【唯一临时 schema】。
 *
 * <p>取代原来的硬编码 {@code jdbc:mysql://127.0.0.1:3306/chengyin_explore_it}。那个写法有三条问题，
 * 2026-08-17 在真库上逐条坐实：
 * <ul>
 *   <li>不读 {@code CHENGYIN_TEST_MYSQL_*} ⇒ {@code ci/funds-mysql-it-gate.sh} 把连接指到哪，
 *       它都当没看见；门禁以为自己在验的是 Oracle MySQL 8.0.45，实跑的却是本机另一台 9.7.1；</li>
 *   <li>库是跨运行长期存在的，与门禁脚本头写死的「唯一临时 schema，跑完 DROP，绝不依赖业务库」相反；</li>
 *   <li>schema 会过期：{@code oms_ticket} 缺 R6 产品迁移加的 {@code source_ticket_id}，
 *       5 条用例长期 ERROR 1054 —— 而套件从未在门禁里真跑过，所以没人看得见。</li>
 * </ul>
 *
 * <p>现在结构由仓库决定：先灌 {@code it-schema/explore-alliance-it-schema.sql}，
 * 再把仓库里的相关 migration 叠上去，所以 schema 跟着仓库演进走。
 */
final class ExploreItDatabase {

    /** 叠在基表之上的仓库迁移；顺序即执行顺序。加新迁移就往这里加，别再手工改库。 */
    private static final String[] MIGRATIONS = {
            "sql/migration_20260817_tandianri_r6_product_contracts.sql",
            "sql/migration_circle_theme_batch2_20260821.sql",
            "sql/migration_circle_theme_primary_schema_20260821.sql",
            "sql/migration_20260823_topic_route_graph.sql",
            "sql/migration_20260823_topic_route_published_snapshot.sql",
            "sql/migration_ordinary_chapter_offer_withdraw_20261003.sql",
            "sql/migration_20261003_merchant_offer_withdrawal_columns.sql",
            "sql/migration_20260930_registration_first_successful_scan.sql",
            "sql/migration_club_direct_merchant_receivable_20261004.sql",
    };

    private final MysqlIntegrationTestConfig config;

    ExploreItDatabase(String suite) {
        this.config = MysqlIntegrationTestConfig.fromEnvironment(suite);
    }

    /** 建库 + 建表 + 叠迁移。每次调用都重建结构，测试不接受环境里恰好剩下什么。 */
    PooledDataSource freshDataSource() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                config.adminUrl(), config.user(), config.password());
             Statement st = admin.createStatement()) {
            // ★ 2026-08-19:先 DROP 再 CREATE,不接受"环境里恰好剩下一个同名库"。
            // 原来是 CREATE DATABASE IF NOT EXISTS ⇒ 本类默默继承上一次(可能是崩在半路的那次)留下的表,
            // 于是 @BeforeEach 建表撞 "Table 'xxx' already exists" —— #741 的资金闸就是这么连红的:
            // 本地怎么跑都绿,只有 runner 上那个库有残留。库名本来就一类一个(见 MysqlIntegrationTestConfig),
            // 这里补的是另一半:**每次开跑都从零建**,隔离才是完整的。
            // 本类的类注释早就写着「每次调用都重建结构,测试不接受环境里恰好剩下什么」——
            // 但当时只重建了表,库本身还是 IF NOT EXISTS 捡回来的。这里把那句话补全。
            st.execute("DROP DATABASE IF EXISTS " + config.databaseName());
            st.execute("CREATE DATABASE " + config.databaseName() + " CHARACTER SET utf8mb4");
        }
        try (Connection db = DriverManager.getConnection(
                config.databaseUrl(), config.user(), config.password())) {
            ItSchema.apply(db, "it-schema/explore-alliance-it-schema.sql");
            for (String migration : MIGRATIONS) {
                ScriptUtils.executeSqlScript(db, new FileSystemResource(migration));
            }
        }
        PooledDataSource ds = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                config.databaseUrl(), config.user(), config.password());
        return ds;
    }

    void drop() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                config.adminUrl(), config.user(), config.password());
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + config.databaseName());
        }
    }
}

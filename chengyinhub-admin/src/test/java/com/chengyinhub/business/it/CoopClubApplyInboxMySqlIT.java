package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CoopClubApply;
import com.chengyinhub.business.mapper.CoopClubApplyMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收发件箱的俱乐部申请查询,在真 MySQL 8.0.45 + 仓库真实迁移 DDL 上跑。
 *
 * <p>发布者「收到的」:归属闸在 SQL join 里 —— 别人主题上的申请必须查不出来(负控)。
 * 两侧都必须返回已婉拒/已撤回/已转邀约的历史,不能像 /pool/list 那样按开放态过滤掉。
 */
class CoopClubApplyInboxMySqlIT {

    private static final String SUITE = "coop_club_apply_inbox";

    @Test
    void ownerSeesOnlyApplicationsOnOwnTopicsAndApplicantSeesHistory() throws Exception {
        String url = MybatisItDatabase.url(SUITE);
        String database = MybatisItDatabase.databaseName(SUITE);
        PooledDataSource source = new PooledDataSource("com.mysql.cj.jdbc.Driver", url,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        try {
            Configuration cfg = new Configuration(new Environment("it", new JdbcTransactionFactory(), source));
            cfg.getTypeAliasRegistry().registerAlias("CoopClubApply", CoopClubApply.class);
            String resource = "mapper/business/CoopClubApplyMapper.xml";
            try (InputStream xml = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(xml, cfg, resource, cfg.getSqlFragments()).parse();
            }
            try (SqlSession session = new SqlSessionFactoryBuilder().build(cfg).openSession(true);
                 Statement sql = session.getConnection().createStatement()) {
                ResultSet version = sql.executeQuery("select version()");
                version.next();
                String versionText = version.getString(1);
                assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(versionText), "需要 MySQL >= 8,实际 " + versionText);
                // cms_topic 只建本查询与迁移 ALTER 需要的列;coop_club_apply 用仓库真实迁移
                sql.execute("create table cms_topic (id bigint primary key, member_id bigint,"
                        + " merchant_status int, del_flag int not null default 0)");
                ItSchema.applyMigrations(session.getConnection(), "sql/migration_open_club_pool.sql");

                // 商家 9 发布 100(在招)、101(已锁价/历史);商家 8 发布 200;101 被删的主题 102 不算
                sql.execute("insert into cms_topic (id, member_id, del_flag) values"
                        + " (100, 9, 0), (101, 9, 0), (102, 9, 2), (200, 8, 0)");
                sql.execute("insert into coop_club_apply (id, topic_id, club_id, member_id, status, create_time, del_flag) values"
                        + " (1, 100, 7, 42, 0, '2026-09-01 10:00:00', 0),"   // 待处理
                        + " (2, 101, 7, 42, 1, '2026-09-02 10:00:00', 0),"   // 已婉拒(历史)
                        + " (3, 101, 6, 43, 3, '2026-09-03 10:00:00', 0),"   // 已转邀约
                        + " (4, 200, 7, 42, 2, '2026-09-04 10:00:00', 0),"   // 别人主题上的(负控)
                        + " (5, 100, 5, 44, 0, '2026-09-05 10:00:00', 2),"   // 软删
                        + " (6, 102, 5, 44, 0, '2026-09-06 10:00:00', 0)");  // 主题已删

                CoopClubApplyMapper mapper = session.getMapper(CoopClubApplyMapper.class);
                assertEquals(List.of(3L, 2L, 1L), ids(mapper.selectByTopicOwner(9L)),
                        "只含我主题上的、含历史、按时间倒序;别人主题/软删/已删主题都不出现");
                assertEquals(List.of(4L), ids(mapper.selectByTopicOwner(8L)));
                assertTrue(mapper.selectByTopicOwner(42L).isEmpty(), "申请人不是发布者,收件箱为空");

                CoopClubApply q = new CoopClubApply();
                q.setMemberId(42L);
                assertEquals(List.of(4L, 2L, 1L), ids(mapper.selectCoopClubApplyList(q)),
                        "申请人发件箱含已婉拒/已撤回历史,不按主题开放态过滤");
            }
        } finally {
            source.forceCloseAll();
            try (Connection admin = DriverManager.getConnection(
                    "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                            + "/?useSSL=false&allowPublicKeyRetrieval=true",
                    MybatisItDatabase.user(), MybatisItDatabase.password());
                 Statement st = admin.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + database + "`");
                ResultSet left = st.executeQuery(
                        "select count(*) from information_schema.schemata where schema_name = '" + database + "'");
                left.next();
                assertEquals(0, left.getInt(1), "scratch 库必须删干净");
            }
        }
    }

    private static List<Long> ids(List<CoopClubApply> rows) {
        return rows.stream().map(CoopClubApply::getId).collect(Collectors.toList());
    }
}

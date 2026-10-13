package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.mapper.UmsCreativeSquareMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [RV-p6 #2 / 6-09] 主题恢复公开时,只能放回「被主题联动隐藏」的同步帖。
 *
 * <p>6-09 原实现:联动隐藏写 status=2,恢复按 status=2 且 check_status≠2 放回。但 status=2 还有两个来源 ——
 * 内容安全图片违规自动下架(WxContentSecurityServiceImpl)、文本 BLOCK(ApiCreativeSquareController)、
 * 后台手动隐藏 —— 它们都不写 check_status,于是主题一恢复,违规帖就跟着重新公开。
 * 现在联动隐藏用独立的 status=3,恢复只认 3。真库跑 MyBatis,不做 SQL 子串匹配。
 */
class TopicSyncPostRestoreMyBatisTest {

    private static final long TOPIC = 10L;

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.setLocalCacheScope(LocalCacheScope.STATEMENT);
        cfg.getTypeAliasRegistry().registerAlias("UmsCreativeSquare", UmsCreativeSquare.class);
        PooledDataSource ds = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("topic_sync_post"), MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/UmsCreativeSquareMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/UmsCreativeSquareMapper.xml", cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private static int status(Statement st, long id) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT status FROM ums_creative_square WHERE id=" + id)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    @Test
    void topicRestoreOnlyRevivesPostsItHidItself() throws Exception {
        try (SqlSession session = factory().openSession(true)) {
            Statement st = session.getConnection().createStatement();
            st.execute("DROP TABLE IF EXISTS ums_creative_square");
            // 列类型照 sql/base_schema_20260818.sql:5553
            st.execute("CREATE TABLE ums_creative_square (id BIGINT PRIMARY KEY, data_id BIGINT, data_type INT, "
                    + "status TINYINT(1) DEFAULT 1, check_status TINYINT DEFAULT 0, del_flag INT DEFAULT 0, "
                    + "update_time DATETIME)");
            st.execute("INSERT INTO ums_creative_square(id,data_id,data_type,status,check_status,del_flag) VALUES "
                    + "(1,10,2,1,1,0),"        // 公开的同步帖
                    + "(2,10,2,2,NULL,0),"     // 内容安全/后台下架:只写了 status=2
                    + "(3,10,2,2,2,0),"        // 举报审核驳回
                    + "(4,10,2,1,1,2),"        // 作者自己删了
                    + "(5,11,2,1,1,0),"        // 别的主题
                    + "(6,10,1,1,1,0)");       // 普通帖,不是同步帖
            UmsCreativeSquareMapper mapper = session.getMapper(UmsCreativeSquareMapper.class);

            assertEquals(1, mapper.hideTopicSyncPosts(TOPIC), "只隐藏本主题仍公开、未删的同步帖");
            assertEquals(2, status(st, 2), "平台下架那行不能被联动改写");
            assertEquals(1, status(st, 5));
            assertEquals(1, status(st, 6));

            assertEquals(1, mapper.restoreTopicSyncPosts(TOPIC), "只放回自己隐藏的那一行");
            assertEquals(1, status(st, 1));
            assertEquals(2, status(st, 2), "平台下架的同步帖在主题恢复后必须仍然隐藏");
            assertEquals(2, status(st, 3));
            assertEquals(1, status(st, 4));
        }
    }

    /** 主题隐藏期间,内容安全又把这条帖判违规下架(写 status=2):主题恢复时同样不能放回。 */
    @Test
    void takedownWhileTopicHiddenSurvivesTopicRestore() throws Exception {
        try (SqlSession session = factory().openSession(true)) {
            Statement st = session.getConnection().createStatement();
            st.execute("DROP TABLE IF EXISTS ums_creative_square");
            st.execute("CREATE TABLE ums_creative_square (id BIGINT PRIMARY KEY, data_id BIGINT, data_type INT, "
                    + "status TINYINT(1) DEFAULT 1, check_status TINYINT DEFAULT 0, del_flag INT DEFAULT 0, "
                    + "update_time DATETIME)");
            st.execute("INSERT INTO ums_creative_square(id,data_id,data_type,status,check_status,del_flag) "
                    + "VALUES (1,10,2,1,1,0)");
            UmsCreativeSquareMapper mapper = session.getMapper(UmsCreativeSquareMapper.class);

            mapper.hideTopicSyncPosts(TOPIC);
            st.execute("UPDATE ums_creative_square SET status=2 WHERE id=1"); // 内容安全回调的写法
            assertEquals(0, mapper.restoreTopicSyncPosts(TOPIC));
            assertEquals(2, status(st, 1));
        }
    }
}

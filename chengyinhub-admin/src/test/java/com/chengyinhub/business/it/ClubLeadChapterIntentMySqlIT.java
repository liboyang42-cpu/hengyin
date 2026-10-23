package com.chengyinhub.business.it;

import com.chengyinhub.business.access.club.ClubEventLeadResolver;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiClubLeadController;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅供批准的 CI/VPS 执行：真实 InnoDB、MyBatis、Spring 事务，不替代 HTTP/真机验收。 */
class ClubLeadChapterIntentMySqlIT {
    private static PooledDataSource ds;
    private static SqlSessionTemplate sql;
    private static DataSourceTransactionManager transactions;
    private static JdbcTemplate jdbc;
    private static String ownedSchema;
    private ApiClubLeadController controller;

    @BeforeAll static void schema() throws Exception {
        for (String key : new String[]{"HOST", "PORT", "USER", "PASSWORD", "DB_SUFFIX"}) {
            assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_" + key), "Explicit isolated MySQL binding required: " + key);
        }
        assertTrue(System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX").matches("_[a-z0-9_]+"));
        assertEquals("127.0.0.1", MybatisItDatabase.host());
        ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", MybatisItDatabase.url("c_chapter_intent"), MybatisItDatabase.user(), MybatisItDatabase.password());
        ownedSchema = MybatisItDatabase.databaseName("c_chapter_intent");
        try (Connection connection = ds.getConnection()) {
            ItSchema.apply(connection, "it-schema/run41-prod-ddl.sql");
            ItSchema.applyMigrations(connection, "sql/migration_club_lead.sql", "sql/migration_club_role_assignment_20260823.sql", "sql/migration_20260823_game_session_runtime.sql");
        }
        Configuration config = new Configuration(new Environment("c-chapter", new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String mapper : Arrays.asList("ClubLeadMapper", "CmsActivityMapper", "ClubRoleAssignmentMapper", "GameSessionRuntimeMapper")) {
            String resource = "mapper/business/" + mapper + ".xml";
            try (InputStream input = ClubLeadChapterIntentMySqlIT.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource); new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        sql = new SqlSessionTemplate(factory.getObject()); transactions = new DataSourceTransactionManager(ds); jdbc = new JdbcTemplate(ds);
    }

    @AfterAll static void cleanupOwnedSchema() throws Exception {
        if (ds != null) ds.forceCloseAll();
        if (ownedSchema == null) return;
        assertTrue(ownedSchema.matches("chengyin_mybatis_c_chapter_intent_[a-z0-9_]+"));
        try (Connection connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + MybatisItDatabase.port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true", MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + ownedSchema + "`");
        }
    }

    @BeforeEach void seed() {
        // 仅清理本类专属测试库，绝不接生产。
        for (String table : Arrays.asList("game_command_receipt", "club_role_assignment", "club_lead_state", "cms_activity", "club")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO club(id,member_id,name,del_flag) VALUES(5,9,'intent fixture',0)");
        jdbc.update("INSERT INTO cms_activity(id,topic_id,member_id,club_id,del_flag) VALUES(100,1,9,5,0)");
        jdbc.update("INSERT INTO club_lead_state(activity_id,leader_member_id,current_chapter_id,status,create_time) VALUES(100,9,10,2,NOW())");
        controller = proxied(sql.getMapper(GameSessionRuntimeMapper.class));
    }

    private ApiClubLeadController proxied(GameSessionRuntimeMapper receipts) {
        ApiClubLeadController target = new ApiClubLeadController() { @Override public Long getAppUserId() { return 9L; } };
        CmsActivityServiceImpl activities = new CmsActivityServiceImpl(); ReflectionTestUtils.setField(activities, "cmsActivityMapper", sql.getMapper(CmsActivityMapper.class));
        IClubService clubs = mock(IClubService.class);
        when(clubs.selectClubById(5L)).thenAnswer(i -> { Club club = new Club(); club.setId(5L); club.setMemberId(jdbc.queryForObject("SELECT member_id FROM club WHERE id=5", Long.class)); return club; });
        ICmsTopicChapterService chapters = mock(ICmsTopicChapterService.class);
        when(chapters.selectCmsTopicChapterList(any())).thenAnswer(i -> { List<CmsTopicChapter> rows = new ArrayList<>(); for (long id : new long[]{10,20,30}) { CmsTopicChapter c = new CmsTopicChapter(); c.setId(id); c.setSortId(id); c.setName("chapter-" + id); rows.add(c); } return rows; });
        ReflectionTestUtils.setField(target, "clubLeadMapper", sql.getMapper(ClubLeadMapper.class));
        ReflectionTestUtils.setField(target, "commandReceiptMapper", receipts);
        ReflectionTestUtils.setField(target, "cmsActivityMapper", sql.getMapper(CmsActivityMapper.class));
        ReflectionTestUtils.setField(target, "cmsActivityService", activities);
        ReflectionTestUtils.setField(target, "cmsTopicChapterService", chapters);
        ReflectionTestUtils.setField(target, "clubEventLeadResolver", new ClubEventLeadResolver(sql.getMapper(ClubRoleAssignmentMapper.class), clubs));
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (ApiClubLeadController) proxy.getProxy();
    }

    @Test void concurrentAndSequentialRetryHaveOneEffectAndOriginalReceipt() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<AjaxResult> action = () -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return controller.unlockChapter("100", "same", 10L, 20L); };
            Future<AjaxResult> a = pool.submit(action), b = pool.submit(action); start.countDown();
            AjaxResult first = a.get(15, TimeUnit.SECONDS); assertEquals(200, first.get("code")); assertEquals(first, b.get(15, TimeUnit.SECONDS));
            assertEquals(20L, chapter()); assertEquals(1, receipts());
            assertEquals(200, controller.unlockChapter("100", "next", 20L, 30L).get("code"));
            assertEquals(first, controller.unlockChapter("100", "same", 10L, 20L)); assertEquals(30L, chapter());
            assertEquals(409, controller.unlockChapter("100", "same", 20L, 30L).get("code"));
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void failedTerminalReceiptRollsBackActualChapterAndClaim() {
        GameSessionRuntimeMapper fail = spy(sql.getMapper(GameSessionRuntimeMapper.class)); doReturn(0).when(fail).completeReceipt(any());
        ApiClubLeadController broken = proxied(fail);
        assertThrows(IllegalStateException.class, () -> broken.unlockChapter("100", "rollback", 10L, 20L));
        assertEquals(10L, chapter()); assertEquals(0, receipts());
    }

    @Test void committedHandoverWinsBeforeOldLeaderWrite() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> transfer = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT member_id FROM club WHERE id=5 FOR UPDATE", Long.class); locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                jdbc.update("UPDATE club SET member_id=8 WHERE id=5"); return null;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<AjaxResult> command = pool.submit(() -> controller.unlockChapter("100", "old-lead", 10L, 20L));
            assertThrows(TimeoutException.class, () -> command.get(200, TimeUnit.MILLISECONDS)); release.countDown(); transfer.get(15, TimeUnit.SECONDS);
            assertNotEquals(200, command.get(15, TimeUnit.SECONDS).get("code")); assertEquals(10L, chapter()); assertEquals(0, receipts());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    private long chapter() { return jdbc.queryForObject("SELECT current_chapter_id FROM club_lead_state WHERE activity_id=100", Long.class); }
    private int receipts() { return jdbc.queryForObject("SELECT COUNT(*) FROM game_command_receipt WHERE activity_id=100", Integer.class); }
}

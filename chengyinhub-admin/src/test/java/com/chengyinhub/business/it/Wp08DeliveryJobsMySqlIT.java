package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.task.*;
import com.chengyinhub.common.constant.ScheduleConstants;
import com.chengyinhub.common.utils.spring.SpringUtils;
import com.chengyinhub.quartz.domain.SysJob;
import com.chengyinhub.quartz.mapper.SysJobMapper;
import com.chengyinhub.quartz.service.*;
import com.chengyinhub.quartz.service.impl.SysJobServiceImpl;
import com.chengyinhub.quartz.util.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.quartz.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CI/VPS only. Actual MySQL/DDL/MyBatis/named locks/transactions/task and original consumers.
 * Quartz timing/log storage mocked; no HTTP permission/production activation or provider calls.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Wp08DeliveryJobsMySqlIT {
    private static final String R7 = "imNotificationDeliveryTask.deliverPending()", R1 = "roamRewardDeliveryTask.run()";
    private static final List<String> BASE_TABLES = List.of("sys_job","im_conversation","im_conversation_member","im_message",
            "reward_drop_log","member_growth","player_badge","ums_member");
    private static final List<String> OWNED_TABLES = List.of("sys_job","im_notification_intent","im_message","im_conversation_member",
            "im_conversation","roam_reward_delivery","roam_reward_reconcile_cursor","reward_drop_log","roam_badge_delivery",
            "roam_badge_reconcile_cursor","player_badge","member_growth","member_exp_earned","ums_member");
    private DataSource source; private JdbcTemplate db; private DataSourceTransactionManager transactions;
    private SqlSessionTemplate session; private Path sql; private String candidate;
    private IImService messages; private ImNotificationIntentMapper notificationMapper;
    private ImNotificationDeliveryTask imTask; private RoamRewardDeliveryTask roamTask;
    private ISmsCouponHistoryService coupons; private IRewardDropService drops;
    private MySqlSchedulerCoordinator coordinator; private Object originalFactory; private boolean factoryChanged;
    private long fire; private Long notification; private String createdDatabase;

    @BeforeAll void schema() throws Exception {
        for (String name : List.of("HOST","PORT","USER","PASSWORD","DB_SUFFIX")) {
            assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_"+name), "必须显式配置测试连接项："+name);
        }
        String host = System.getenv("CHENGYIN_MYBATIS_IT_HOST");
        assertTrue("127.0.0.1".equals(host) || "localhost".equals(host), "必须显式指定已批准的隔离回环 MySQL 实例");
        int port = Integer.parseInt(System.getenv("CHENGYIN_MYBATIS_IT_PORT"));
        assertTrue(port>0 && port<=65535); assertFalse(System.getenv("CHENGYIN_MYBATIS_IT_USER").isBlank());
        assertTrue(System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX").matches("[A-Za-z0-9_]*"));
        String suite = "g_wp08_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        assertTrue(MybatisItDatabase.databaseName(suite).length()<=64);
        String url = MybatisItDatabase.url(suite);
        createdDatabase = MybatisItDatabase.databaseName(suite); // only after helper successfully created this UUID schema.
        source = new DriverManagerDataSource(url,MybatisItDatabase.user(),MybatisItDatabase.password());
        db = new JdbcTemplate(source); assertTrue(db.queryForObject("select version()",String.class).startsWith("8."),"需要 MySQL 8");
        Path root = Files.isRegularFile(Path.of("sql/base_schema_20260818.sql"))?Path.of(""):Path.of("..");
        sql = root.resolve("chengyinhub-admin/sql"); String base = Files.readString(root.resolve("sql/base_schema_20260818.sql"));
        for (String table : BASE_TABLES) {
            java.util.regex.Matcher ddl = Pattern.compile("(?s)CREATE TABLE `"+table+"` \\(.*?\\) ENGINE=.*?;").matcher(base);
            assertTrue(ddl.find(),table); db.execute(ddl.group());
        }
        createTablesFrom("migration_20261013_b_im_notification_intent.sql");
        createTablesFrom("migration_20261013_roam_reward_delivery.sql");
        createTablesFrom("migration_20261013_roam_badge_delivery.sql");
        createTablesFrom("migration_member_exp_earned_20261011.sql");
        new ResourceDatabasePopulator(new FileSystemResource(sql.resolve("migration_im_send_idempotency_20260913.sql")),
                new FileSystemResource(sql.resolve("migration_20261012_im_media_visibility.sql"))).execute(source);
        candidate = Files.readString(sql.resolve("migration_20261023_g_wp08_delivery_jobs_paused.sql"));
        transactions = new DataSourceTransactionManager(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain,com.chengyinhub.quartz.domain");
        String[] names = {"ImConversationMapper","ImConversationMemberMapper","ImMessageMapper","ImNotificationIntentMapper",
                "RoamRewardDeliveryMapper","RoamBadgeDeliveryMapper","RoamMapper","MemberGrowthMapper","PlayerBadgeMapper"};
        List<ClassPathResource> resources = new ArrayList<>();
        for (String name : names) resources.add(new ClassPathResource("mapper/business/"+name+".xml"));
        resources.add(new ClassPathResource("mapper/quartz/SysJobMapper.xml"));
        factory.setMapperLocations(resources.toArray(ClassPathResource[]::new)); session = new SqlSessionTemplate(factory.getObject());
        notificationMapper = session.getMapper(ImNotificationIntentMapper.class);
        ImServiceImpl im = new ImServiceImpl();
        ReflectionTestUtils.setField(im,"notificationIntentMapper",notificationMapper);
        ReflectionTestUtils.setField(im,"conversationMapper",session.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(im,"memberMapper",session.getMapper(ImConversationMemberMapper.class));
        ReflectionTestUtils.setField(im,"messageMapper",session.getMapper(ImMessageMapper.class));
        messages = transactional(im,IImService.class);
        imTask = new ImNotificationDeliveryTask(); ReflectionTestUtils.setField(imTask,"mapper",notificationMapper);
        ReflectionTestUtils.setField(imTask,"imService",messages);
        GrowthServiceImpl growth = new GrowthServiceImpl(); ReflectionTestUtils.setField(growth,"memberGrowthMapper",session.getMapper(MemberGrowthMapper.class));
        coupons = mock(ISmsCouponHistoryService.class); drops = mock(IRewardDropService.class);
        RoamRewardDeliveryService rewards = new RoamRewardDeliveryService(session.getMapper(RoamRewardDeliveryMapper.class),coupons,
                transactions,drops,transactional(growth,IGrowthService.class));
        RoamBadgeDeliveryService badges = new RoamBadgeDeliveryService(session.getMapper(RoamBadgeDeliveryMapper.class),
                session.getMapper(PlayerBadgeMapper.class),session.getMapper(RoamMapper.class),transactions);
        roamTask = new RoamRewardDeliveryTask(rewards,badges);
    }
    @BeforeEach void resetOwnedFixtures() {
        for (String table : OWNED_TABLES) db.execute("TRUNCATE TABLE "+table);
        clearInvocations(coupons,drops); notification=null;
    }
    @AfterEach void stopTestCoordinator() {
        if (coordinator!=null) { coordinator.stop(); coordinator=null; }
        if (factoryChanged) { ReflectionTestUtils.setField(SpringUtils.class,"beanFactory",originalFactory); factoryChanged=false; }
    }
    @AfterAll void dropOnlyRecordedUuidSchema() throws Exception {
        stopTestCoordinator();
        if (createdDatabase==null) return;
        assertTrue(createdDatabase.matches("chengyin_mybatis_g_wp08_[a-f0-9]{12}[A-Za-z0-9_]*"));
        try (Connection connection=source.getConnection(); Statement statement=connection.createStatement()) {
            assertEquals(createdDatabase,connection.getCatalog());
            statement.execute("DROP DATABASE `"+createdDatabase+"`");
            try (PreparedStatement check=connection.prepareStatement("select count(*) from information_schema.schemata where schema_name=?")) {
                check.setString(1,createdDatabase);
                try (ResultSet rows=check.executeQuery()) { assertTrue(rows.next()); assertEquals(0,rows.getInt(1)); }
            }
        }
        createdDatabase=null; // DriverManagerDataSource has no pool to close.
    }
    @Test void missingSeedsArePausedIdempotentAndRetainOriginalCron() throws Exception {
        applyCandidate(); applyCandidate(); assertEquals(2,count("sys_job"));
        for (String target : List.of(R7,R1)) {
            Map<String,Object> row = db.queryForMap("select * from sys_job where invoke_target=?",target);
            assertEquals("1",row.get("status")); assertEquals("1",row.get("concurrent")); assertEquals("3",row.get("misfire_policy"));
            assertEquals(target.equals(R7)?"0 0/1 * * * ?":"0 0/10 * * * ?",row.get("cron_expression"));
        }
    }
    @Test void existingPausedActiveAndUnrelatedRowsStayUnchangedIncludingOperatorSchedules() throws Exception {
        db.update("insert into sys_job(job_name,job_group,invoke_target,cron_expression,status,remark) values"
                + "('operator-R7','CUSTOM',?,'0 15 * * * ?','1','keep pause'),"
                + "('operator-R1','OTHER',?,'0 30 * * * ?','0','keep explicit activation'),"
                + "('other','DEFAULT','clubEventOpsTask.run()','0 5 * * * ?','1','untouched')", "  "+R7+"  ",R1);
        List<Map<String,Object>> before = db.queryForList("select * from sys_job order by job_id");
        applyCandidate(); applyCandidate(); assertEquals(before,db.queryForList("select * from sys_job order by job_id"));
    }
    @Test void duplicatesAndKnownCanonicalClassFormsFailBeforeAnyInsert() {
        for (String target : List.of(R7,R1)) {
            db.update("delete from sys_job"); db.update("insert into sys_job(invoke_target,status) values(?,'1'),(?,'0')",target," "+target+" ");
            List<Map<String,Object>> before = db.queryForList("select * from sys_job order by job_id");
            assertThrows(AssertionError.class,this::applyCandidate); assertEquals(before,db.queryForList("select * from sys_job order by job_id"));
        }
        db.update("delete from sys_job");
        db.update("insert into sys_job(invoke_target,status) values('com.chengyinhub.business.task.ImNotificationDeliveryTask.deliverPending()','1')");
        assertThrows(AssertionError.class,this::applyCandidate); assertEquals(1,count("sys_job"));
    }
    @Test void missingIntentSchemaStopsBeforeRegistration() {
        db.execute("RENAME TABLE im_notification_intent TO g_wp08_missing_intent");
        try { assertThrows(AssertionError.class,this::applyCandidate); assertEquals(0,count("sys_job")); }
        finally { db.execute("RENAME TABLE g_wp08_missing_intent TO im_notification_intent"); }
    }
    @Test void pausedSqlSeedsCannotConsumeCommittedOriginalIntents() throws Exception {
        applyCandidate(); prepareOriginals(); startCoordinator(); callback(R7); callback(R1);
        assertEquals(0,count("im_message")); assertEquals(0,count("member_growth")); assertEquals(0,count("player_badge"));
        assertEquals("PENDING",db.queryForObject("select status from im_notification_intent where id=?",String.class,notification));
        assertEquals("DUE",db.queryForObject("select state from roam_reward_delivery where drop_log_id=101",String.class));
    }
    @Test void explicitlyActiveFixtureConsumesRealImXpBadgeAndReplaysWithoutDuplicateEffects() throws Exception {
        applyCandidate(); prepareOriginals();
        db.update("update sys_job set status='0'"); // ONLY this random test schema; never an operational activation.
        startCoordinator(); callback(R7); callback(R1); assertDeliveredOnce();
        callback(R7); callback(R1); assertDeliveredOnce(); verifyNoInteractions(coupons,drops);
    }
    @Test void realTaskSessionLockBlocksBothConsumersUntilOriginalSessionReleases() throws Exception {
        applyCandidate(); prepareOriginals(); db.update("update sys_job set status='0'"); startCoordinator();
        for (String target : List.of(R7,R1)) {
            try (MySqlSessionLock.Lease inFlight=MySqlSessionLock.acquire(source,"task",CanonicalJobTarget.canonical(target),0)) {
                assertNotNull(inFlight); callback(target);
            }
        }
        assertEquals(0,count("im_message")); assertEquals(0,count("member_growth")); assertEquals(0,count("player_badge"));
        callback(R7); callback(R1); assertDeliveredOnce();
    }
    private void assertDeliveredOnce() {
        assertEquals(1,count("im_message"));
        Long message = db.queryForObject("select message_id from im_notification_intent where id=? and status='DELIVERED'",Long.class,notification);
        assertNotNull(message); assertEquals("signup_success_g_wp08",db.queryForObject("select event_key from im_notification_intent",String.class));
        assertEquals("原报名成功通知",db.queryForObject("select content from im_message where id=?",String.class,message));
        assertEquals("notification_"+notification,db.queryForObject("select client_message_id from im_message",String.class));
        assertEquals(1,db.queryForObject("select unread_count from im_conversation_member where member_id=7",Integer.class));
        assertEquals(9,db.queryForObject("select exp_value from member_growth where member_id=7",Integer.class));
        assertEquals(1,count("member_exp_earned")); assertEquals(1,count("player_badge"));
        assertEquals("DELIVERED",db.queryForObject("select state from roam_reward_delivery where drop_log_id=101",String.class));
        assertEquals("DELIVERED",db.queryForObject("select state from roam_badge_delivery",String.class));
    }
    private void prepareOriginals() {
        db.update("insert into ums_member(id) values(7)");
        notification = messages.enqueueSystemNotification(7L,ImMessage.MSG_CARD,"原报名成功通知","{}","signup_success_g_wp08");
        db.update("insert into reward_drop_log(id,member_id,scene_key,drop_type,param_json) values(101,7,'roam_merchant_poi',1,'{}')");
        new TransactionTemplate(transactions).execute(status -> {
            session.getMapper(RoamRewardDeliveryMapper.class).prepareXp(101L,7L,9);
            PlayerBadge badge = new PlayerBadge(); badge.setMemberId(7L); badge.setBadgeCode("G_WP08_ORIGINAL"); badge.setSourceId(44L);
            badge.setSourceType(2); badge.setBadgeName("原徽章"); badge.setObtainTime(java.sql.Timestamp.valueOf("2026-10-01 10:00:00"));
            return session.getMapper(RoamBadgeDeliveryMapper.class).prepare(700L,badge);
        });
    }
    private void startCoordinator() throws Exception {
        originalFactory=ReflectionTestUtils.getField(SpringUtils.class,"beanFactory"); factoryChanged=true;
        DefaultListableBeanFactory factory=new DefaultListableBeanFactory(); factory.registerSingleton("imNotificationDeliveryTask",imTask);
        factory.registerSingleton("roamRewardDeliveryTask",roamTask);
        coordinator=new MySqlSchedulerCoordinator(source,mock(ScheduledExecutorService.class));
        ReflectionTestUtils.setField(coordinator,"running",true); factory.registerSingleton("coordinator",coordinator);
        SysJobServiceImpl jobs=new SysJobServiceImpl(); ReflectionTestUtils.setField(jobs,"jobMapper",session.getMapper(SysJobMapper.class));
        ReflectionTestUtils.setField(jobs,"coordinator",coordinator); ReflectionTestUtils.setField(jobs,"scheduler",mock(Scheduler.class));
        factory.registerSingleton("jobs",jobs); factory.registerSingleton("logs",mock(ISysJobLogService.class));
        ReflectionTestUtils.setField(SpringUtils.class,"beanFactory",factory);
        assertTrue(ScheduleUtils.whiteList(R7)); assertTrue(ScheduleUtils.whiteList(R1)); jobs.init(); coordinator.maintainLeadership();
        assertTrue(coordinator.isLeader()); fire=System.currentTimeMillis()+1000;
    }
    private void callback(String target) throws JobExecutionException {
        SysJob job=session.getMapper(SysJobMapper.class).selectJobAll().stream().filter(row -> target.equals(row.getInvokeTarget())).findFirst().orElseThrow();
        JobDataMap data=new JobDataMap(); data.put(ScheduleConstants.TASK_PROPERTIES,job);
        JobExecutionContext context=mock(JobExecutionContext.class); when(context.getMergedJobDataMap()).thenReturn(data);
        when(context.getScheduledFireTime()).thenReturn(new java.util.Date(++fire)); new QuartzDisallowConcurrentExecution().execute(context);
    }
    private <T> T transactional(Object target,Class<T> api) {
        ProxyFactory factory=new ProxyFactory(target); factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return api.cast(factory.getProxy());
    }
    private int count(String table) { return db.queryForObject("select count(*) from "+table,Integer.class); }
    private void createTablesFrom(String migration) throws Exception {
        String raw=Files.readString(sql.resolve(migration)); StringBuilder clean=new StringBuilder();
        raw.lines().filter(line -> !line.stripLeading().startsWith("--")).forEach(line -> clean.append(line).append('\n'));
        for (String statement : ItSchema.splitStatements(clean.toString()))
            if (statement.stripLeading().startsWith("CREATE TABLE")) db.execute(statement);
    }
    /** Assertion-aware runner: all preflight SELECT guards precede DML; false guard aborts and rolls back. */
    private void applyCandidate() throws Exception {
        StringBuilder clean=new StringBuilder(); candidate.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .forEach(line -> clean.append(line).append('\n'));
        try (Connection connection=source.getConnection(); Statement sql=connection.createStatement()) {
            try {
                for (String statement : ItSchema.splitStatements(clean.toString())) {
                    if (statement.stripLeading().startsWith("SELECT")) {
                        try (ResultSet result=sql.executeQuery(statement)) {
                            assertTrue(result.next()); assertEquals(1,result.getInt(1),statement); assertFalse(result.next());
                        }
                    } else sql.execute(statement);
                }
            } catch (Exception | AssertionError failure) {
                try { sql.execute("ROLLBACK"); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
}

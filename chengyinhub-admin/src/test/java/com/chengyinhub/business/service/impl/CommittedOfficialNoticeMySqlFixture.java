package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.notify.WxSubscribeChannel;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.CommittedOfficialNoticeService;
import com.chengyinhub.business.task.CommittedOfficialNoticeTask;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real canonical IM migrations, mappers, consumer and new typed owner on controlled MySQL.
 * A JDBC complaint fact adapter proves transaction coupling, not the complete financial producer. */
final class CommittedOfficialNoticeMySqlFixture implements AutoCloseable {
    final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment(
            "committed_notice_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    final WxSubscribeChannel wx = mock(WxSubscribeChannel.class);
    final IWxSubscribeMsgService beforeStartWx = mock(IWxSubscribeMsgService.class);
    java.time.Clock beforeStartClock=java.time.Clock.systemUTC();
    final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    final List<Runnable> wakes = new CopyOnWriteArrayList<>();
    JdbcTemplate admin, db;
    DataSourceTransactionManager manager;
    TransactionTemplate tx;
    SqlSessionTemplate sql;
    CommittedOfficialNoticeWxMapper mapper;
    ImNotificationIntentMapper imIntents;
    ImServiceImpl imTarget;
    IImService im;
    ISystemNotificationIntentService intents;
    CommittedOfficialNoticeService notice;
    Path sqlRoot;
    boolean created;

    static final String TYPED_NOTICE_MIGRATION = "migration_20261024_g_before_start_notice_metadata_v2.sql";

    void open() throws Exception {
        openWithOriginalNoticeShape(ignored -> { });
    }

    /** Test-only drift/legacy-row setup after canonical D, before the strict replacement C entry. */
    void openWithOriginalNoticeShape(java.util.function.Consumer<JdbcTemplate> beforeTyped) throws Exception {
        for (String key : List.of("CHENGYIN_TEST_MYSQL_PORT", "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"))
            assertNotNull(System.getenv(key), "controlled runner must supply " + key);
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4"); created = true;
        DriverManagerDataSource source = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        initialize(source, new DataSourceTransactionManager(source), beforeTyped);
        db.execute("create table complaint_notice_fact(id bigint primary key,status int,recipient bigint,"
                + "title varchar(200),body text) engine=InnoDB");
        db.update("insert into complaint_notice_fact values(1,0,100,'投诉处理有进展',"
                + "'工单#1已先行垫付 ¥50，请在投诉记录查看处理进度。')");
    }

    /** Reuse the financial fixture's exact DataSource/transaction manager; never create another business database. */
    void attach(DataSourceTransactionManager original) throws Exception {
        for (String key : List.of("CHENGYIN_TEST_MYSQL_PORT", "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"))
            assertNotNull(System.getenv(key), "controlled runner must supply " + key);
        initialize(original.getDataSource(), original, ignored -> { });
    }

    private void appendNoticeDiagnostic(org.springframework.dao.DataAccessException failure, String label, String select) {
        try {
            failure.addSuppressed(new IllegalStateException("Canonical notice " + label + "=" + db.queryForList(select)));
        } catch (RuntimeException diagnosticsFailure) {
            failure.addSuppressed(new IllegalStateException("Canonical notice diagnostic failed: " + label, diagnosticsFailure));
        }
    }

    private void initialize(DataSource source, DataSourceTransactionManager original,
            java.util.function.Consumer<JdbcTemplate> beforeTyped) throws Exception {
        db = new JdbcTemplate(source);
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(db.queryForObject("select version()", String.class)));
        sqlRoot = Paths.get("chengyinhub-admin/sql");
        if (!sqlRoot.toFile().isDirectory()) sqlRoot = Paths.get("sql");
        new ResourceDatabasePopulator(new FileSystemResource(sqlRoot.resolve("migration_im.sql")),
                new FileSystemResource(sqlRoot.resolve("migration_im_send_idempotency_20260913.sql")),
                new FileSystemResource(sqlRoot.resolve("migration_20261012_im_media_visibility.sql"))).execute(source);
        db.execute("create table sys_job(job_name varchar(64),job_group varchar(64),invoke_target varchar(255),"
                + "cron_expression varchar(255),misfire_policy varchar(20),concurrent varchar(20),status varchar(20),"
                + "create_by varchar(64),create_time datetime,remark text) engine=InnoDB");
        new ResourceDatabasePopulator(new FileSystemResource(sqlRoot.resolve("migration_20261013_b_im_notification_intent.sql")),
                new FileSystemResource(sqlRoot.resolve("migration_20261024_d_committed_official_notice_wx.sql"))).execute(source);
        beforeTyped.accept(db);
        try {
            // Controlled replacement retains every strict gate/DDL; only metadata quote serialization differs.
            new ResourceDatabasePopulator(new FileSystemResource(sqlRoot.resolve(TYPED_NOTICE_MIGRATION))).execute(source);
        } catch (org.springframework.dao.DataAccessException failedMigration) {
            // The old CAST failure contained no actual metadata. Preserve the failure and attach
            // only schema diagnostics from this exclusive scratch database, never notification rows.
            appendNoticeDiagnostic(failedMigration, "context",
                    "SELECT VERSION() AS version, DATABASE() AS selected_schema, @@SESSION.sql_mode AS sql_mode");
            // These are the nine original C #1 predicates, projected independently without coercing NULL.
            appendNoticeDiagnostic(failedMigration, "original_preflight_predicates", """
                    SELECT
                     (DATABASE() IS NOT NULL) AS database_selected,
                     ((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
                          AND table_name='committed_official_notice_wx' AND table_type='BASE TABLE' AND engine='InnoDB')=1) AS canonical_innodb_table,
                     ((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
                          AND table_name='committed_official_notice_wx')=12) AS canonical_12_columns,
                     ((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
                          AND table_name='committed_official_notice_wx' AND column_name IN ('notice_type','wx_payload_json'))=0) AS typed_columns_not_yet_present,
                     ((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
                          AND table_name='committed_official_notice_wx' AND (
                           (column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND column_type NOT LIKE '%zerofill%' AND is_nullable='NO' AND extra='auto_increment')
                           OR (column_name='event_key' AND data_type='varchar' AND character_maximum_length=96 AND is_nullable='NO' AND collation_name='ascii_bin')
                           OR (column_name='recipient' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND column_type NOT LIKE '%zerofill%' AND is_nullable='NO')
                           OR (column_name='title' AND data_type='varchar' AND character_maximum_length=200 AND is_nullable='NO')
                           OR (column_name='body' AND data_type='text' AND is_nullable='NO')
                           OR (column_name='im_intent_id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%' AND column_type NOT LIKE '%zerofill%' AND is_nullable='NO')
                           OR (column_name='request_hash' AND data_type='char' AND character_maximum_length=64 AND is_nullable='NO' AND collation_name='ascii_bin')
                           OR (column_name='wx_state' AND data_type='varchar' AND character_maximum_length=16 AND is_nullable='NO' AND collation_name='ascii_bin' AND column_default='PENDING')
                           OR (column_name='owner_token' AND data_type='char' AND character_maximum_length=36 AND is_nullable='YES' AND collation_name='ascii_bin')
                           OR (column_name='created_at' AND data_type='datetime' AND datetime_precision=3 AND is_nullable='NO')
                           OR (column_name='attempted_at' AND data_type='datetime' AND datetime_precision=3 AND is_nullable='YES')
                           OR (column_name='accepted_at' AND data_type='datetime' AND datetime_precision=3 AND is_nullable='YES')))=12) AS all_12_column_shapes,
                     ((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
                          AND table_name='committed_official_notice_wx' AND constraint_type='CHECK')=3) AS exactly_3_checks,
                     ((SELECT COUNT(*) FROM information_schema.table_constraints tc
                          JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
                          WHERE tc.constraint_schema=DATABASE() AND tc.table_name='committed_official_notice_wx'
                          AND tc.constraint_type='CHECK' AND tc.enforced='YES' AND (
                           (tc.constraint_name='ck_committed_official_notice_recipient' AND LOWER(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cc.check_clause,'_utf8mb4',''),'_utf8mb3',''),'_ascii',''),'_latin1',''),'[[:space:]()`]',''))='recipient>0andim_intent_id>0')
                           OR (tc.constraint_name='ck_committed_official_notice_state' AND LOWER(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cc.check_clause,'_utf8mb4',''),'_utf8mb3',''),'_ascii',''),'_latin1',''),'[[:space:]()`]',''))='wx_statein''pending'',''unknown'',''accepted''')
                           OR (tc.constraint_name='ck_committed_official_notice_owner' AND LOWER(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cc.check_clause,'_utf8mb4',''),'_utf8mb3',''),'_ascii',''),'_latin1',''),'[[:space:]()`]',''))='wx_state=''pending''andowner_tokenisnullandattempted_atisnullandaccepted_atisnullorwx_state=''unknown''andowner_tokenisnotnullandattempted_atisnotnullandaccepted_atisnullorwx_state=''accepted''andowner_tokenisnotnullandattempted_atisnotnullandaccepted_atisnotnull')))=3) AS all_3_enforced_exact_check_texts,
                     ((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()
                          AND constraint_name='ck_committed_official_notice_typed')=0) AS typed_constraint_name_not_reserved,
                     ((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
                          AND table_name='committed_official_notice_wx' AND index_name='idx_committed_official_notice_typed_pending')=0) AS typed_index_name_not_reserved
                    """);
            appendNoticeDiagnostic(failedMigration, "canonical_table", "SHOW CREATE TABLE committed_official_notice_wx");
            appendNoticeDiagnostic(failedMigration, "columns",
                    "SELECT column_name,data_type,column_type,is_nullable,character_maximum_length,"
                            + "collation_name,column_default,extra,datetime_precision FROM information_schema.columns "
                            + "WHERE table_schema=DATABASE() AND table_name='committed_official_notice_wx' "
                            + "ORDER BY ordinal_position");
            appendNoticeDiagnostic(failedMigration, "checks", """
                    SELECT tc.constraint_name,tc.constraint_type,tc.enforced,cc.check_clause,
                           LOWER(REGEXP_REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cc.check_clause,
                             '_utf8mb4',''),'_utf8mb3',''),'_ascii',''),'_latin1',''),'[[:space:]()`]','')) AS original_guard_normalized
                    FROM information_schema.table_constraints tc
                    LEFT JOIN information_schema.check_constraints cc
                      ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
                    WHERE tc.constraint_schema=DATABASE() AND tc.table_name='committed_official_notice_wx'
                    ORDER BY tc.constraint_name
                    """);
            appendNoticeDiagnostic(failedMigration, "indexes",
                    "SELECT index_name,non_unique,seq_in_index,column_name FROM information_schema.statistics "
                            + "WHERE table_schema=DATABASE() AND table_name='committed_official_notice_wx' "
                            + "ORDER BY index_name,seq_in_index");
            throw failedMigration;
        }
        manager = original; tx = new TransactionTemplate(manager);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"),
                new ClassPathResource("mapper/business/ImNotificationIntentMapper.xml"),
                new ClassPathResource("mapper/business/CommittedOfficialNoticeWxMapper.xml"));
        sql = new SqlSessionTemplate(factory.getObject());
        mapper = sql.getMapper(CommittedOfficialNoticeWxMapper.class);
        imIntents = sql.getMapper(ImNotificationIntentMapper.class);
        imTarget = spy(new ImServiceImpl());
        ReflectionTestUtils.setField(imTarget, "notificationIntentMapper", imIntents);
        ReflectionTestUtils.setField(imTarget, "conversationMapper", sql.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(imTarget, "messageMapper", sql.getMapper(ImMessageMapper.class));
        ReflectionTestUtils.setField(imTarget, "memberMapper", sql.getMapper(ImConversationMemberMapper.class));
        ProxyFactory imProxy = new ProxyFactory(imTarget);
        imProxy.setInterfaces(IImService.class, ISystemNotificationIntentService.class);
        imProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        im = (IImService) imProxy.getProxy(); intents = (ISystemNotificationIntentService) im;
        when(executor.schedule(any(Runnable.class), eq(10L), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
            wakes.add(call.getArgument(0)); return mock(ScheduledFuture.class);
        });
        when(wx.send(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "WX must have no database transaction");
            return true;
        });
        notice = freshOwner(mapper);
    }

    CommittedOfficialNoticeService freshOwner(CommittedOfficialNoticeWxMapper source) {
        CommittedOfficialNoticeService target = new CommittedOfficialNoticeService(source, intents, im, wx, manager, executor);
        ReflectionTestUtils.setField(target, "beforeStartWx", beforeStartWx);
        ReflectionTestUtils.setField(target, "beforeStartClock", beforeStartClock);
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (CommittedOfficialNoticeService) proxy.getProxy();
    }

    Long winner() {
        return tx.execute(status -> {
            Map<String, Object> original = db.queryForMap("select * from complaint_notice_fact where id=1");
            if (db.update("update complaint_notice_fact set status=1 where id=1 and status=0") != 1)
                throw new IllegalStateException("original CAS lost");
            return notice.enqueueOfficial("complaint-advance:1", ((Number) original.get("recipient")).longValue(),
                    (String) original.get("title"), (String) original.get("body"));
        });
    }

    Long enqueue(String key, long recipient, String title, String body) {
        return tx.execute(status -> notice.enqueueOfficial(key, recipient, title, body));
    }

    int count(String table) { return db.queryForObject("select count(*) from " + table, Integer.class); }
    String state(Long id) { return db.queryForObject("select wx_state from committed_official_notice_wx where id=?", String.class, id); }
    void recover() { new CommittedOfficialNoticeTask(freshOwner(mapper)).deliverPending(); }

    void oneMessage() {
        assertEquals(1, count("im_message"));
        assertEquals(1, db.queryForObject("select unread_count from im_conversation_member where member_id=100", Integer.class));
        assertEquals("DELIVERED", db.queryForObject("select status from im_notification_intent", String.class));
        assertEquals(db.queryForObject("select id from im_message", Long.class),
                db.queryForObject("select message_id from im_notification_intent", Long.class));
    }

    @Override public void close() {
        if (created) {
            admin.execute("drop database " + mysql.databaseName());
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?",
                    Integer.class, mysql.databaseName()));
            System.out.println("COMMITTED_OFFICIAL_NOTICE_CLEAN " + mysql.databaseName());
        }
    }
}

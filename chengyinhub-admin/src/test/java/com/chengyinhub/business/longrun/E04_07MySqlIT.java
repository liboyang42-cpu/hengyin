package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** Source only. Opt-in isolated MySQL8 scratch schema, production SQL and transaction proxies. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E04_07MySqlIT {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("e04_07_it");
    private boolean created;
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;
    private Path root;
    private SqlSessionTemplate template;
    private ITopicCompletionCouponRewardService producer;
    private RedemptionRewardClawbackService consumer;
    private TransactionTemplate tx;

    @BeforeAll void setUp() throws Exception {
        for(String key:new String[]{"HOST","PORT","DATABASE_BASE","DATABASE_SUFFIX","USER"}) {
            String value=System.getenv("CHENGYIN_TEST_MYSQL_"+key);
            assertTrue(value!=null&&!value.trim().isEmpty(),"Explicit MySQL variable required: "+key);
        }
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_PASSWORD"));
        assertEquals("e04_scratch",System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_BASE"));
        JdbcTemplate admin=new JdbcTemplate(dataSource(mysql.adminUrl()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("SELECT VERSION()",String.class)));
        admin.execute("CREATE DATABASE "+mysql.databaseName()+" CHARACTER SET utf8mb4"); created=true;
        ds=dataSource(mysql.databaseUrl()); jdbc=new JdbcTemplate(ds);
        root=Paths.get("").toAbsolutePath();
        while(root!=null&&!Files.exists(root.resolve("sql/base_schema_20260818.sql"))) root=root.getParent();
        assertNotNull(root);
        Configuration cfg=new Configuration(new Environment("e04-07",new SpringManagedTransactionFactory(),ds));
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for(Class<?> mapper:new Class<?>[]{SmsCouponMapper.class,SmsCouponHistoryMapper.class,
                CmsTopicCompletionCouponRewardMapper.class,UmsMemberMapper.class,UmsMemberPointsDetailMapper.class,
                GrowthMissionProgressMapper.class,MemberGrowthMapper.class,TopicCompletionXpAwardMapper.class,BusinessAuditEventMapper.class}) {
            cfg.addMapper(mapper); String resource="mapper/business/"+mapper.getSimpleName()+".xml";
            try(InputStream in=getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(in,resource); new XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse();
            }
        }
        template=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
        DataSourceTransactionManager manager=new DataSourceTransactionManager(ds); tx=new TransactionTemplate(manager);
        SmsCouponHistoryServiceImpl issuing=new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(issuing,"smsCouponMapper",template.getMapper(SmsCouponMapper.class));
        ReflectionTestUtils.setField(issuing,"smsCouponHistoryMapper",template.getMapper(SmsCouponHistoryMapper.class));
        ISmsCouponHistoryService issuingProxy=(ISmsCouponHistoryService)proxy(issuing,ISmsCouponHistoryService.class,manager);
        TopicCompletionCouponRewardServiceImpl grant=new TopicCompletionCouponRewardServiceImpl();
        ReflectionTestUtils.setField(grant,"rewardMapper",template.getMapper(CmsTopicCompletionCouponRewardMapper.class));
        ReflectionTestUtils.setField(grant,"smsCouponMapper",template.getMapper(SmsCouponMapper.class));
        ReflectionTestUtils.setField(grant,"smsCouponHistoryService",issuingProxy);
        producer=(ITopicCompletionCouponRewardService)proxy(grant,ITopicCompletionCouponRewardService.class,manager);
        consumer=new RedemptionRewardClawbackService();
        ReflectionTestUtils.setField(consumer,"completionCouponRewardMapper",template.getMapper(CmsTopicCompletionCouponRewardMapper.class));
        ReflectionTestUtils.setField(consumer,"smsCouponHistoryMapper",template.getMapper(SmsCouponHistoryMapper.class));
        ReflectionTestUtils.setField(consumer,"umsMemberMapper",template.getMapper(UmsMemberMapper.class));
        ReflectionTestUtils.setField(consumer,"pointsDetailMapper",template.getMapper(UmsMemberPointsDetailMapper.class));
        ReflectionTestUtils.setField(consumer,"growthMissionProgressMapper",template.getMapper(GrowthMissionProgressMapper.class));
        ReflectionTestUtils.setField(consumer,"memberGrowthMapper",template.getMapper(MemberGrowthMapper.class));
        ReflectionTestUtils.setField(consumer,"topicCompletionXpAwardMapper",template.getMapper(TopicCompletionXpAwardMapper.class));
        ReflectionTestUtils.setField(consumer,"businessAuditEventMapper",template.getMapper(BusinessAuditEventMapper.class));
        ProxyFactory consumerFactory=new ProxyFactory(consumer); consumerFactory.setProxyTargetClass(true);
        consumerFactory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        consumer=(RedemptionRewardClawbackService)consumerFactory.getProxy();
    }

    @BeforeEach void reset() throws Exception {
        for(String table:new String[]{"sms_coupon_history","sms_coupon","cms_topic_completion_coupon_reward_log",
                "ums_member","ums_member_points_detail","growth_mission_progress","member_growth","topic_completion_xp_award","business_audit_event"})
            jdbc.execute("DROP TABLE IF EXISTS "+table);
        for(String table:new String[]{"sms_coupon_history","sms_coupon","cms_topic_completion_coupon_reward_log",
                "ums_member","ums_member_points_detail","growth_mission_progress","member_growth"}) createTable("sql/base_schema_20260818.sql",table);
        jdbc.execute("ALTER TABLE cms_topic_completion_coupon_reward_log ADD COLUMN history_id BIGINT NULL");
        jdbc.execute("ALTER TABLE ums_member ADD COLUMN app_open_id VARCHAR(128) NULL");
        createTable("chengyinhub-admin/sql/migration_20260817_tandianri_r6_product_contracts.sql","topic_completion_xp_award");
        createTable("chengyinhub-admin/sql/migration_business_audit_event_20260823.sql","business_audit_event");
        jdbc.execute("ALTER TABLE business_audit_event ADD COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' AFTER owner_member_id, MODIFY COLUMN request_id VARCHAR(64) NOT NULL");
        jdbc.update("INSERT INTO sms_coupon(id,name,publish_count,receive_count,status) VALUES(30,'E04',10,0,1)");
        jdbc.update("INSERT INTO ums_member(id,point,del_flag) VALUES(501,10,0)");
        jdbc.update("INSERT INTO member_growth(member_id,level_no,exp_value) VALUES(501,1,8)");
    }

    @Test void newCompletionBindsActualHistoryAndDuplicateCompletionDoesNotIssueAgain() {
        assertTrue(producer.grantOnce(700L,501L,30L));
        Long id=jdbc.queryForObject("SELECT history_id FROM cms_topic_completion_coupon_reward_log",Long.class);
        assertNotNull(id); assertTrue(id>0);
        assertEquals(id,jdbc.queryForObject("SELECT id FROM sms_coupon_history WHERE coupon_id=30 AND member_id=501",Long.class));
        assertFalse(producer.grantOnce(700L,501L,30L));
        assertEquals(1L,count("sms_coupon_history"));
        assertEquals(1L,jdbc.queryForObject("SELECT receive_count FROM sms_coupon WHERE id=30",Long.class));
    }

    @Test void realReceiptConstraintFailureRollsBackLogHistoryAndInventoryAndAllowsRetry() {
        jdbc.execute("ALTER TABLE cms_topic_completion_coupon_reward_log ADD CONSTRAINT reject_receipt CHECK(history_id IS NULL)");
        RuntimeException failure=assertThrows(RuntimeException.class,()->producer.grantOnce(700L,501L,30L));
        assertTrue(hasVendorCode(failure,3819),"Must be real MySQL ER_CHECK_CONSTRAINT_VIOLATED (3819)");
        assertEquals(0L,count("cms_topic_completion_coupon_reward_log")); assertEquals(0L,count("sms_coupon_history"));
        assertEquals(0L,jdbc.queryForObject("SELECT receive_count FROM sms_coupon WHERE id=30",Long.class));
        jdbc.execute("ALTER TABLE cms_topic_completion_coupon_reward_log DROP CHECK reject_receipt");
        assertTrue(producer.grantOnce(700L,501L,30L)); assertEquals(1L,count("sms_coupon_history"));
    }

    @Test void exactOriginal41IsVoidedInsteadOfLaterUnrelated43AndRecompletionGetsANewReceipt() {
        seedHistory(41,0); seedHistory(43,0);
        jdbc.update("INSERT INTO cms_topic_completion_coupon_reward_log(topic_id,member_id,coupon_id,history_id) VALUES(700,501,30,41)");
        reverse(61);
        assertEquals(2,state(41)); assertEquals(0,state(43)); assertEquals(0L,count("cms_topic_completion_coupon_reward_log"));
        assertTrue(jdbc.queryForObject("SELECT after_json FROM business_audit_event WHERE target_id=61",String.class).contains("VOIDED"));
        assertTrue(producer.grantOnce(700L,501L,30L));
        Long next=jdbc.queryForObject("SELECT history_id FROM cms_topic_completion_coupon_reward_log",Long.class);
        assertNotEquals(Long.valueOf(41),next); assertNotEquals(Long.valueOf(43),next);
        assertFalse(producer.grantOnce(700L,501L,30L)); assertEquals(0,state(43));
    }

    @Test void legacyNullIdentityAuditsWithoutTouchingEitherCouponWhilePointsAndXpContinue() {
        seedHistory(41,0); seedHistory(43,0);
        jdbc.update("INSERT INTO cms_topic_completion_coupon_reward_log(topic_id,member_id,coupon_id) VALUES(700,501,30)");
        jdbc.update("INSERT INTO ums_member_points_detail(member_id,change_points,event_type,event_id,change_type,del_flag) VALUES(501,5,13,8001,1,0)");
        jdbc.update("INSERT INTO topic_completion_xp_award(member_id,topic_id,xp_amount,granted_at) VALUES(501,700,3,NOW())");
        reverse(61);
        assertEquals(0,state(41)); assertEquals(0,state(43)); assertEquals(1L,count("cms_topic_completion_coupon_reward_log"));
        assertEquals(5L,jdbc.queryForObject("SELECT point FROM ums_member WHERE id=501",Long.class));
        assertEquals(5,jdbc.queryForObject("SELECT exp_value FROM member_growth WHERE member_id=501",Integer.class));
        assertEquals(0L,count("topic_completion_xp_award"));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM growth_mission_progress WHERE mission_code='COMPLETION_CLAWBACK_COUPON'",Long.class));
        assertTrue(jdbc.queryForObject("SELECT after_json FROM business_audit_event WHERE target_id=61",String.class).contains("UNKNOWN_IDENTITY_NOT_RECOVERED"));
    }

    @Test void usedOriginalReceiptKeepsUsedCouponAndGrantWithoutSubstitutingUnused43() {
        seedHistory(41,1); seedHistory(43,0);
        jdbc.update("INSERT INTO cms_topic_completion_coupon_reward_log(topic_id,member_id,coupon_id,history_id) VALUES(700,501,30,41)");
        reverse(61); assertEquals(1,state(41)); assertEquals(0,state(43));
        assertEquals(1L,count("cms_topic_completion_coupon_reward_log"));
        assertTrue(jdbc.queryForObject("SELECT after_json FROM business_audit_event WHERE target_id=61",String.class).contains("USED_NOT_RECOVERED"));
        assertFalse(producer.grantOnce(700L,501L,30L));
        assertEquals(2L,count("sms_coupon_history"));
    }

    private void reverse(long id) {
        Redemption r=new Redemption(); r.setId(id); r.setTopicId(700L); r.setRegistrationId(9001L); r.setMerchantId(8001L);
        CmsRegistration reg=new CmsRegistration(); reg.setMemberId(501L); reg.setOwnerId(8001L);
        tx.execute(status->{consumer.clawback(r,reg,false,true,"E04-07",1L);return null;});
    }
    private void seedHistory(long id,int state) {
        jdbc.update("INSERT INTO sms_coupon_history(id,coupon_id,member_id,coupon_code,use_status,get_time,del_flag) VALUES(?,30,501,?,?,NOW(),0)",id,"E04-"+id,state);
    }
    private int state(long id) {return jdbc.queryForObject("SELECT use_status FROM sms_coupon_history WHERE id=?",Integer.class,id);}
    private long count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private void createTable(String file,String table) throws Exception {
        String sql=new String(Files.readAllBytes(root.resolve(file)),StandardCharsets.UTF_8);
        Matcher m=Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?`?"+table+"`? \\(.*?\\) ENGINE=[^;]*;",Pattern.DOTALL).matcher(sql);
        assertTrue(m.find(),"Missing table "+table); jdbc.execute(m.group());
    }
    private boolean hasVendorCode(Throwable ex,int expected) {
        for(Throwable t=ex;t!=null;t=t.getCause()) if(t instanceof java.sql.SQLException&&expected==((java.sql.SQLException)t).getErrorCode()) return true;
        return false;
    }
    private Object proxy(Object target,Class<?> api,DataSourceTransactionManager manager) {
        ProxyFactory factory=new ProxyFactory(target); factory.setInterfaces(api);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource())); return factory.getProxy();
    }
    private DriverManagerDataSource dataSource(String url) {
        return new DriverManagerDataSource(url,mysql.user(),mysql.password());
    }
    @AfterAll void cleanUp() {if(created)new JdbcTemplate(dataSource(mysql.adminUrl())).execute("DROP DATABASE "+mysql.databaseName());}
}

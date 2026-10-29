package com.chengyinhub.web.controller.api;

import com.chengyinhub.web.service.verify.CityNodeRedemptionApplication;

import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.mapper.RoamShareSnapshotMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.RoamLocationRetentionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.lang.reflect.Proxy;
import java.util.Date;
import java.util.UUID;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoamLocationRetentionMySqlIT {
    JdbcTemplate admin, db;
    DriverManagerDataSource source;
    String database;
    org.apache.ibatis.session.SqlSessionFactory sqlFactory;
    RoamMapper roam;
    com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper orphan;
    RoamShareSnapshotMapper shares;
    Date cutoff;
    @BeforeAll void setup() throws Exception {
        MysqlIntegrationTestConfig c = MysqlIntegrationTestConfig.fromEnvironment("roam_retention");
        admin = new JdbcTemplate(new DriverManagerDataSource(c.adminUrl(),c.user(),c.password()));
        assertEquals("8.0.45",admin.queryForObject("select version()",String.class));
        database="cy_retention_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("create database "+database);
        source = new DriverManagerDataSource(c.databaseUrl().replace("/"+c.databaseName()+"?","/"+database+"?"),c.user(),c.password());
        db=new JdbcTemplate(source);
        db.execute("create table ums_member(id bigint primary key,avatar varchar(128),del_flag int) engine=InnoDB");
        db.execute("insert into ums_member values(9,'owner-avatar',0),(10,'other-avatar',0)");
        db.execute("create table roam_session(id bigint primary key,member_id bigint,status int,finished_at datetime,last_lat decimal(10,7),last_lng decimal(10,7),last_report_at datetime,presence_visible tinyint,finish_result_json text,location_cleared_at datetime null,distance_m int,tiles_revealed int,xp_awarded int,started_at datetime,pois_found int,recall_sent_at datetime,recall_attempts int,recall_last_attempt_at datetime,create_time datetime,client_session_key varchar(32),unique key uk_member_client_key(member_id,client_session_key)) engine=InnoDB");
        db.execute("create table roam_share_snapshot(id bigint auto_increment primary key,share_token varchar(32),member_id bigint,session_id bigint null,snapshot_json text,create_time datetime) engine=InnoDB");
        db.execute("create table roam_fog_tile(id bigint primary key auto_increment,member_id bigint,session_id bigint,geohash7 varchar(12) not null,revealed_at datetime,unique key uk_member_tile(member_id,geohash7)) engine=InnoDB");
        db.execute("create table roam_poi_found(id bigint primary key auto_increment,member_id bigint,session_id bigint,poi_id bigint not null,found_at datetime,unique key uk_member_poi(member_id,poi_id)) engine=InnoDB");
        db.execute("create table roam_shop_visit(id bigint primary key auto_increment,member_id bigint,session_id bigint,source_type tinyint,source_id bigint not null,shop_name varchar(128) not null,visited_at datetime,unique key uk_session_source(session_id,source_type,source_id)) engine=InnoDB");
        db.execute("create table roam_poi_reward_log(id bigint primary key auto_increment,member_id bigint,poi_id bigint not null,coupon_id bigint,trigger_lat decimal(10,6),trigger_lng decimal(10,6),status tinyint,create_time datetime,unique key uk_poi_member(poi_id,member_id)) engine=InnoDB");
        try (java.sql.Connection connection=source.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource("sql/migration_20261012_roam_claim_fingerprints.sql"));
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.FileSystemResource("sql/migration_20261012_roam_anonymous_shop_visitors.sql"));
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource("sql/migration_20261012_roam_claim_fingerprints.sql"));
        }
        db.execute("CREATE TABLE sms_coupon (id BIGINT PRIMARY KEY, name VARCHAR(100), coupon_type INT, platform INT, amount DECIMAL(12,2), min_point DECIMAL(12,2),"
                        + "start_time DATETIME(3), end_time DATETIME(3), use_type INT, publish_count BIGINT, use_count BIGINT, receive_count BIGINT,"
                        + "enable_time DATETIME(3), code VARCHAR(100), scope_type INT, member_id BIGINT, merchant_id BIGINT, status INT, del_flag INT,"
                        + "description VARCHAR(500), create_by VARCHAR(64), create_time DATETIME(3), update_by VARCHAR(64), update_time DATETIME(3)) ENGINE=InnoDB");
        db.execute("CREATE TABLE sms_coupon_history (id BIGINT PRIMARY KEY AUTO_INCREMENT, coupon_id BIGINT, member_id BIGINT, coupon_code VARCHAR(100), qrcode_url VARCHAR(500),"
                        + "get_type INT, get_time DATETIME(3), use_status INT, use_time DATETIME(3), order_id BIGINT, order_sn VARCHAR(100),"
                        + "start_time DATETIME(3), end_time DATETIME(3), note VARCHAR(500), del_flag INT, create_by VARCHAR(64), create_time DATETIME(3),"
                        + "update_by VARCHAR(64), update_time DATETIME(3)) ENGINE=InnoDB");
        db.execute("CREATE TABLE business_audit_event (id BIGINT PRIMARY KEY AUTO_INCREMENT, domain_type VARCHAR(32) NOT NULL, domain_id BIGINT NOT NULL,"
                        + "owner_member_id BIGINT NOT NULL, actor_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',"
                        + "actor_member_id BIGINT NOT NULL, actor_role_code VARCHAR(64) NOT NULL,"
                        + "action VARCHAR(64) NOT NULL, target_type VARCHAR(64), target_id BIGINT, relation_id BIGINT,"
                        + "before_json TEXT, after_json TEXT, reason VARCHAR(500), request_id VARCHAR(64), create_time DATETIME(3) NOT NULL) ENGINE=InnoDB");
        db.execute("create table consent_record(id bigint auto_increment primary key,member_id bigint not null,doc_type varchar(32) not null,doc_version varchar(32) not null,scene varchar(32) not null,scope_type varchar(16),scope_id bigint,event_type varchar(16) not null,occurred_at datetime not null,request_id varchar(64) not null unique,ip_address varchar(64),create_time datetime not null) engine=InnoDB");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ConsentRecordMapper.xml"),new ClassPathResource("mapper/business/RoamMapper.xml"),new ClassPathResource("mapper/business/RoamShareSnapshotMapper.xml"),new ClassPathResource("mapper/business/RoamOrphanLocationRetentionMapper.xml"),new ClassPathResource("mapper/business/SmsCouponMapper.xml"),new ClassPathResource("mapper/business/SmsCouponHistoryMapper.xml"),new ClassPathResource("mapper/business/BusinessAuditEventMapper.xml"));
        sqlFactory=factory.getObject();
        SqlSessionTemplate session = new SqlSessionTemplate(sqlFactory);
        roam=session.getMapper(RoamMapper.class); shares=session.getMapper(RoamShareSnapshotMapper.class);
        orphan=session.getMapper(com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper.class);
    }
    @BeforeEach void seed() {
        db.execute("delete from consent_record");db.execute("delete from roam_shop_visitor_claim");db.execute("delete from roam_shop_visitor_total");
        db.execute("insert into consent_record(member_id,doc_type,doc_version,scene,event_type,occurred_at,request_id,create_time) values(9,'privacy_policy','v-test','roam_location','AGREE',now(),'initial9',now()),(10,'privacy_policy','v-test','roam_location','AGREE',now(),'initial10',now())");
        db.execute("delete from roam_share_snapshot");db.execute("delete from roam_fog_tile");db.execute("delete from roam_session");
        new com.chengyinhub.business.security.PiiCryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]),"test-roam-claim-hmac").register();
        db.execute("delete from roam_poi_found");db.execute("delete from roam_shop_visit");db.execute("delete from roam_poi_reward_log");
        db.execute("delete from roam_claim_key_guard");
        db.execute("delete from sms_coupon_history");db.execute("delete from sms_coupon");db.execute("delete from business_audit_event");
        cutoff = db.queryForObject("select date_sub(now(),interval 30 day)",Date.class);
        db.update("insert into roam_session(id,member_id,status,finished_at,last_lat,last_lng,last_report_at,presence_visible,finish_result_json,location_cleared_at,distance_m,tiles_revealed,xp_awarded) values(1,9,1,?,31.2,121.4,now(),1,?,null,1200,3,9)",cutoff,"{\"totalXp\":9,\"track\":[{\"lat\":31.2,\"lng\":121.4}],\"stats\":{\"distanceM\":1200}}");
        db.update("insert into roam_session(id,member_id,status,finished_at,last_lat,last_lng,last_report_at,presence_visible,finish_result_json,location_cleared_at,distance_m,tiles_revealed,xp_awarded) values(2,9,1,date_add(?,interval 1 day),31.3,121.5,now(),0,'{\"totalXp\":2}',null,200,1,2)",cutoff);
        db.update("insert into roam_session(id,member_id,status,finished_at,last_lat,last_lng,last_report_at,presence_visible,finish_result_json,location_cleared_at,distance_m,tiles_revealed,xp_awarded) values(3,10,0,?,31.3,121.5,now(),0,null,null,0,0,0)",cutoff);
        db.update("insert into roam_share_snapshot(share_token,member_id,session_id,snapshot_json,create_time) values('old',9,1,'{\"track\":[1]}',now()),('recent',9,2,'{\"track\":[2]}',now()),('wrong-owner',10,1,'{}',now()),('legacy',9,null,'{}',date_sub(now(),interval 31 day))");
        db.update("insert into roam_fog_tile(id,member_id,session_id,geohash7,revealed_at) values(1,9,1,'wtw3sjq',now()),(2,9,2,'wtw3sjr',now()),(3,10,3,'wtw3sjs',now())");
    }
    @Test void persistedFingerprintsDenySameTileAndPoiInNewSessionButOtherMemberCanClaim() {
        db.update("insert into roam_poi_found(member_id,session_id,poi_id,found_at) values(9,1,77,now())");
        db.update("insert into roam_shop_visit(member_id,session_id,source_type,source_id,shop_name,visited_at) values(9,1,1,77,'old shop',now())");
        assertEquals(1,service(roam).clear(1L,9L,cutoff));
        assertNull(db.queryForObject("select geohash7 from roam_fog_tile where id=1",String.class));
        assertNull(db.queryForObject("select poi_id from roam_poi_found where session_id=1",Long.class));
        assertEquals(1,roam.countDistinctShopsBySession(1L));
        assertEquals(0,roam.batchInsertTile(9L,2L,java.util.List.of("wtw3sjq"),new Date()));
        assertEquals(0,roam.insertPoiFoundIgnore(9L,77L,2L,new Date()));
        assertEquals(1,roam.batchInsertTile(10L,3L,java.util.List.of("wtw3sjq"),new Date()));
        assertEquals(1,roam.insertPoiFoundIgnore(10L,77L,3L,new Date()));
        assertEquals(0,roam.countTilesBySession(2L)-1);
    }
    @Test void simultaneousCleanupAndTwoNewSessionsCannotReawardTileOrPoi() throws Exception {
        db.update("insert into roam_poi_found(member_id,session_id,poi_id,found_at) values(9,1,77,now())");
        db.update("update roam_session set status=0,finished_at=null where id=2");
        db.update("insert into roam_session(id,member_id,status,xp_awarded) values(4,9,0,0)");
        try(org.apache.ibatis.session.SqlSession initialized=sqlFactory.openSession(true)) {
            initialized.getMapper(RoamMapper.class).requireClaimKey();initialized.commit(true);
        }
        assertEquals(1,db.queryForObject("select count(*) from roam_claim_key_guard where id=1",Integer.class),
                "independent JDBC connection must see the committed sentinel before cleanup starts");
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicLong cleanupConnectionId=new java.util.concurrent.atomic.AtomicLong(), firstConnectionId=new java.util.concurrent.atomic.AtomicLong(), secondConnectionId=new java.util.concurrent.atomic.AtomicLong();
        RoamMapper heldCleanup=(RoamMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RoamMapper.class},(p,m,a)-> {
            Object result=invokeMapper(m,roam,a);
            if(m.getName().equals("lockClaimOwner")) {assertEquals(9L,a[0]);assertEquals(9L,result);}
            if(m.getName().equals("clearSessionTileLocation")) {
                java.sql.Connection connection=DataSourceUtils.getConnection(source);
                assertEquals(database,connection.getCatalog());
                cleanupConnectionId.set(connectionId(connection));
                held.countDown(); assertTrue(release.await(15,java.util.concurrent.TimeUnit.SECONDS));
            }
            return result;
        });
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            java.util.concurrent.Future<Integer> cleanup=pool.submit(()->service(heldCleanup).clear(1L,9L,cutoff));
            assertTrue(held.await(5,java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<Integer> first=pool.submit(()->claimTileOnRecordedConnection(2L,firstConnectionId));
            java.util.concurrent.Future<Integer> second=pool.submit(()->claimTileOnRecordedConnection(4L,secondConnectionId));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            boolean waited=false;
            String lockSample="not sampled";long nextSample=0;
            while(System.nanoTime()<deadline) {
                if(first.isDone())fail("first claim completed before cleanup release: " + completedClaimDiagnostic(first));
                if(second.isDone())fail("second claim completed before cleanup release: " + completedClaimDiagnostic(second));
                if(actualOwnerLockWait(cleanupConnectionId.get(),firstConnectionId.get(),secondConnectionId.get())) { waited=true; break; }
                if(System.nanoTime()>=nextSample) {
                    lockSample=ownerLockDiagnostic(cleanupConnectionId.get(),firstConnectionId.get(),secondConnectionId.get());
                    nextSample=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250);
                }
                // MySQL 8.0.45 trx0i_s.cc:720-726,916-917 refreshes only after >100ms read-idle; each read resets last_read.
                Thread.sleep(150);
            }
            assertTrue(waited,"actual MySQL claim must wait on the cleanup's actual member row lock before session and fact locks; safe diagnostic="+lockSample);
            assertThrows(java.util.concurrent.TimeoutException.class,()->first.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,()->second.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(1,cleanup.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,first.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,second.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,poiWriter(roam).recordPoiFound(9L,2L,77L));
            assertEquals(0,poiWriter(roam).recordPoiFound(9L,4L,77L));
            assertEquals(0,roam.countTilesBySession(2L)-1);
            assertEquals(0,roam.countTilesBySession(4L));
            assertEquals(2,db.queryForObject("select xp_awarded from roam_session where id=2",Integer.class));
            assertEquals(0,db.queryForObject("select xp_awarded from roam_session where id=4",Integer.class));
            assertEquals(1,db.queryForObject("select count(*) from roam_fog_tile where claim_fingerprint is not null",Integer.class));
            assertEquals(9,db.queryForObject("select xp_awarded from roam_session where id=1",Integer.class));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    private long connectionId(java.sql.Connection connection) throws java.sql.SQLException {
        try(java.sql.Statement statement=connection.createStatement();java.sql.ResultSet result=statement.executeQuery("select connection_id()")) {
            assertTrue(result.next());return result.getLong(1);
        }
    }
    private int claimTileOnRecordedConnection(Long sessionId,java.util.concurrent.atomic.AtomicLong recorded) {
        return claimTileOnRecordedConnection(sessionId,recorded,false);
    }
    private int claimTileOnRecordedConnection(Long sessionId,java.util.concurrent.atomic.AtomicLong recorded,boolean pinOldConsentSnapshot) {
        RoamMapper observed=(RoamMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RoamMapper.class},(p,m,a)-> {
            if(m.getName().equals("lockClaimOwner")) {
                assertEquals(9L,a[0]);
                java.sql.Connection connection=DataSourceUtils.getConnection(source);
                assertEquals(database,connection.getCatalog());
                recorded.set(connectionId(connection)); // The same real transaction then waits at this owner SELECT.
                if(pinOldConsentSnapshot) {
                    var old=new SqlSessionTemplate(sqlFactory).getMapper(com.chengyinhub.business.mapper.ConsentRecordMapper.class)
                            .selectLatestRoamLocation(9L);
                    assertEquals("AGREE",old.getEventType()); // Plain read pins an RR snapshot before the pending REVOKE commits.
                }
            }
            return invokeMapper(m,roam,a);
        });
        com.chengyinhub.common.core.domain.AjaxResult result=controller(observed).reveal(String.valueOf(sessionId),"wtw3sjq");
        assertEquals(200,result.get("code"));
        return ((Number)((Map<?,?>)result.get("data")).get("newlyRevealed")).intValue();
    }
    private Object invokeMapper(java.lang.reflect.Method method,RoamMapper mapper,Object[] args) throws Throwable {
        try {return method.invoke(mapper,args);}
        catch(java.lang.reflect.InvocationTargetException error) {throw error.getCause();}
    }
    private com.chengyinhub.business.service.impl.RoamLocationConsentService locationPolicy(RoamMapper mapper) {
        com.chengyinhub.common.config.ComplianceProperties properties=new com.chengyinhub.common.config.ComplianceProperties();
        properties.setDocVersions(java.util.Map.of("privacy_policy","v-test"));
        return new com.chengyinhub.business.service.impl.RoamLocationConsentService(mapper,
                new SqlSessionTemplate(sqlFactory).getMapper(com.chengyinhub.business.mapper.ConsentRecordMapper.class),properties);
    }
    private com.chengyinhub.business.service.impl.RoamLocationConsentService consentService(com.chengyinhub.business.mapper.ConsentRecordMapper records) {
        com.chengyinhub.common.config.ComplianceProperties properties=new com.chengyinhub.common.config.ComplianceProperties();
        properties.setDocVersions(java.util.Map.of("privacy_policy","v-test"));
        ProxyFactory proxy=new ProxyFactory(new com.chengyinhub.business.service.impl.RoamLocationConsentService(roam,records,properties));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        return (com.chengyinhub.business.service.impl.RoamLocationConsentService)proxy.getProxy();
    }
    private ApiRoamController controller(RoamMapper mapper) {
        ApiRoamController target=new ApiRoamController(){@Override Long uid(){return 9L;}};
        org.springframework.test.util.ReflectionTestUtils.setField(target,"roamMapper",mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(target,"locationConsent",locationPolicy(mapper));
        ProxyFactory proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        return (ApiRoamController)proxy.getProxy();
    }
    private com.chengyinhub.business.service.impl.RoamSessionWriteService poiWriter(RoamMapper mapper) {
        com.chengyinhub.business.service.impl.RoamSessionWriteService target=new com.chengyinhub.business.service.impl.RoamSessionWriteService();
        org.springframework.test.util.ReflectionTestUtils.setField(target,"roamMapper",mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(target,"locationConsent",locationPolicy(mapper));
        ProxyFactory proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        return (com.chengyinhub.business.service.impl.RoamSessionWriteService)proxy.getProxy();
    }
    private String completedClaimDiagnostic(java.util.concurrent.Future<Integer> claim) {
        try {return "result="+claim.get();}
        catch(Exception error) {
            Throwable cause=error instanceof java.util.concurrent.ExecutionException?error.getCause():error;
            String result="exception="+cause.getClass().getName();
            for(Throwable current=cause;current!=null;current=current.getCause())if(current instanceof java.sql.SQLException) {
                java.sql.SQLException sql=(java.sql.SQLException)current;
                result+=" sqlState="+sql.getSQLState()+" vendorCode="+sql.getErrorCode();break;
            }
            return result; // Never echo SQL parameters, coordinates, claim fingerprints or keys.
        }
    }
    private String ownerLockDiagnostic(long cleanupId,long firstId,long secondId) {
        java.util.List<Map<String,Object>> rows=db.queryForList("select t.trx_mysql_thread_id as connectionId,t.trx_state as state,t.trx_requested_lock_id as requestedLockId,t.trx_rows_locked as rowsLocked,t.trx_rows_modified as rowsModified,p.db as connectionDatabase,case when lower(t.trx_query) like 'select id from ums_member where id=%for update' then 1 else 0 end as exactOwnerLock from information_schema.innodb_trx t left join information_schema.processlist p on p.id=t.trx_mysql_thread_id where t.trx_mysql_thread_id in (?,?,?)",cleanupId,firstId,secondId);
        return "PFS="+db.queryForObject("select @@performance_schema",Integer.class)+" cleanup="+cleanupId+" claims="+firstId+","+secondId+" trx="+rows;
    }
    private boolean actualOwnerLockWait(long cleanupId,long firstId,long secondId) {
        if(cleanupId<=0 || firstId<=0 || secondId<=0 || firstId==secondId)return false;
        if(Boolean.TRUE.equals(db.queryForObject("select @@performance_schema",Boolean.class))) {
            Integer waits=db.queryForObject("select count(distinct r.processlist_id) from performance_schema.data_lock_waits w join performance_schema.data_locks l on l.engine=w.engine and l.engine_lock_id=w.requesting_engine_lock_id join performance_schema.data_locks b on b.engine=w.engine and b.engine_lock_id=w.blocking_engine_lock_id join performance_schema.threads r on r.thread_id=w.requesting_thread_id join performance_schema.threads h on h.thread_id=w.blocking_thread_id where l.object_schema=? and l.object_name='ums_member' and l.index_name='PRIMARY' and b.object_schema=? and b.object_name='ums_member' and b.index_name='PRIMARY' and h.processlist_id=? and r.processlist_id in (?,?)",Integer.class,database,database,cleanupId,firstId,secondId);
            return waits!=null && waits==2;
        }
        // With PFS disabled, prove both actual owner SELECTs have requested InnoDB row locks.
        // Cleanup locks exactly owner 9 before session/facts; no unrelated same-schema modifier is allowed.
        Integer waits=db.queryForObject("select count(*) from information_schema.innodb_trx t join information_schema.processlist p on p.id=t.trx_mysql_thread_id where p.db=? and t.trx_mysql_thread_id in (?,?) and t.trx_state='LOCK WAIT' and t.trx_requested_lock_id is not null and lower(t.trx_query) like 'select id from ums_member where id=%for update'",Integer.class,database,firstId,secondId);
        Integer holder=db.queryForObject("select count(*) from information_schema.innodb_trx where trx_mysql_thread_id=? and trx_state='RUNNING' and trx_rows_locked>0 and trx_rows_modified>0",Integer.class,cleanupId);
        Integer otherHolders=db.queryForObject("select count(*) from information_schema.innodb_trx t join information_schema.processlist p on p.id=t.trx_mysql_thread_id where p.db=? and t.trx_mysql_thread_id not in (?,?,?) and t.trx_rows_modified>0",Integer.class,database,cleanupId,firstId,secondId);
        return waits!=null && waits==2 && holder!=null && holder==1 && otherHolders!=null && otherHolders==0;
    }
    @Test void realSqlFailureAfterClaimRollsBackClaimKeyAndOriginalSessionTransaction() {
        RoamMapper failing=(RoamMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RoamMapper.class},(p,m,a)-> {
            Object result=invokeMapper(m,roam,a);
            if(m.getName().equals("batchInsertTile"))db.update("insert into ums_member(id) values(9)"); // Real duplicate-key SQL failure after the actual claim.
            return result;
        });
        db.update("update roam_session set status=0,finished_at=null where id=2");
        assertThrows(org.springframework.dao.DataAccessException.class,()->controller(failing).reveal("2","wtw3sjt"));
        assertEquals(0,db.queryForObject("select count(*) from roam_fog_tile where geohash7='wtw3sjt'",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from roam_claim_key_guard",Integer.class));
        assertEquals(2,db.queryForObject("select xp_awarded from roam_session where id=2",Integer.class));
        assertEquals(1,((Map<?,?>)controller(roam).reveal("2","wtw3sjt").get("data")).get("newlyRevealed"));
    }
    @Test void orphanCandidatesCannotExpandOrClearAfterOwnerTimeOrActiveScopeChanges() {
        db.update("insert into roam_fog_tile(id,member_id,session_id,geohash7,revealed_at) values(11,9,null,'wtw3sj1',null)");
        com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper changed=(com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper.class},(p,m,a)-> {
            Object result=m.invoke(orphan,a);
            if(m.getName().equals("tiles"))db.update("update roam_fog_tile set member_id=10,session_id=3 where id=11");
            return result;
        });
        assertEquals(0,orphanService(roam,changed).clearTiles(cutoff,100));
        assertEquals("wtw3sj1",db.queryForObject("select geohash7 from roam_fog_tile where id=11",String.class));
        db.update("insert into roam_poi_found(id,member_id,session_id,poi_id,found_at) values(11,9,null,77,null)");
        com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper recent=(com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper.class},(p,m,a)-> {
            Object result=m.invoke(orphan,a);
            if(m.getName().equals("pois"))db.update("update roam_poi_found set found_at=date_add(?,interval 1 second) where id=11",cutoff);
            return result;
        });
        assertEquals(0,orphanService(roam,recent).clearPois(cutoff,100));
        assertEquals(77L,db.queryForObject("select poi_id from roam_poi_found where id=11",Long.class));
        db.update("insert into roam_shop_visit(id,member_id,session_id,source_type,source_id,shop_name,visited_at) values(11,9,2,1,77,'ended unknown',null)");
        com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper active=(com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper.class},(p,m,a)-> {
            Object result=m.invoke(orphan,a);
            if(m.getName().equals("shops"))db.update("update roam_session set status=0,finished_at=null where id=2");
            return result;
        });
        assertEquals(0,orphanService(roam,active).clearShops(cutoff,100));
        assertEquals(77L,db.queryForObject("select source_id from roam_shop_visit where id=11",Long.class));
    }
    @Test void sortedOwnerBatchAndRealSqlFailureRollbackAllCoordinatesTogether() {
        db.update("insert into roam_fog_tile(id,member_id,session_id,geohash7,revealed_at) values(11,10,null,'wtw3sj1',null),(12,9,null,'wtw3sj2',null)");
        java.util.List<Long> owners=new java.util.ArrayList<>();
        RoamMapper failing=(RoamMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RoamMapper.class},(p,m,a)-> {
            if(m.getName().equals("lockClaimOwner"))owners.add((Long)a[0]);
            Object result=invokeMapper(m,roam,a);
            if(m.getName().equals("clearSessionTileLocation") && Long.valueOf(12).equals(a[0]))
                db.update("insert into ums_member(id) values(9)");
            return result;
        });
        assertThrows(org.springframework.dao.DataAccessException.class,()->orphanService(failing,orphan).clearTiles(cutoff,100));
        assertEquals(java.util.List.of(9L,10L),owners);
        assertEquals(2,db.queryForObject("select count(*) from roam_fog_tile where id in(11,12) and geohash7 is not null and claim_fingerprint is null",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from roam_claim_key_guard",Integer.class));
        assertEquals(2,orphanService(roam,orphan).clearTiles(cutoff,100));
    }
    private com.chengyinhub.business.service.impl.RoamOrphanLocationRetentionService orphanService(RoamMapper mapper,com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper candidates) {
        com.chengyinhub.business.service.impl.RoamOrphanLocationRetentionService target=new com.chengyinhub.business.service.impl.RoamOrphanLocationRetentionService(mapper,candidates);
        org.springframework.test.util.ReflectionTestUtils.setField(target,"transactionManager",new DataSourceTransactionManager(source));
        return target;
    }
    private RoamMapper cityFixtureMapper() {
        // Discovery/auth scope is a fixed DTO fixture here; claim/status/key/stock/history/audit use real XML+MySQL.
        com.chengyinhub.business.domain.vo.CityNodeVO node=new com.chengyinhub.business.domain.vo.CityNodeVO();
        node.setPoiId(77L);node.setStatus(1);node.setLat(new java.math.BigDecimal("31.2"));node.setLng(new java.math.BigDecimal("121.4"));node.setRadiusM(80);node.setMerchantId(11L);node.setCouponId(22L);
        RoamMapper fixture=org.mockito.Mockito.mock(RoamMapper.class,org.mockito.AdditionalAnswers.delegatesTo(roam));
        org.mockito.Mockito.doReturn(node).when(fixture).selectPlayerMerchantNodeDetail(77L,0L);
        org.mockito.Mockito.doReturn(node).when(fixture).selectMerchantNodeDetail(77L);
        return fixture;
    }
    private ApiCityNodeController cityController(RoamMapper mapper) {
        ApiCityNodeController target=new ApiCityNodeController(){@Override public Long getAppUserId(){return 9L;}};
        org.springframework.test.util.ReflectionTestUtils.setField(target,"roamMapper",mapper);
        ProxyFactory proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        return (ApiCityNodeController)proxy.getProxy();
    }
    @Test void actualCityCompleteClaimAndKeyRollbackOnPostInsertSqlFailureThenRetry() {
        RoamMapper fixture=cityFixtureMapper();
        RoamMapper failing=(RoamMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RoamMapper.class},(p,m,a)-> {
            Object result=invokeMapper(m,fixture,a);
            if(m.getName().equals("insertRewardLogIgnore"))db.update("insert into ums_member(id) values(9)");
            return result;
        });
        assertThrows(org.springframework.dao.DataAccessException.class,()->cityController(failing).complete(77L,31.2,121.4,null,null,null));
        assertEquals(0,db.queryForObject("select count(*) from roam_poi_reward_log",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from roam_claim_key_guard",Integer.class));
        assertEquals(200,cityController(fixture).complete(77L,31.2,121.4,null,null,null).get("code"));
        assertEquals(1,db.queryForObject("select count(*) from roam_poi_reward_log where status=0 and coupon_id=22",Integer.class));
    }
    private ApiVerifyController verifyController(RoamMapper mapper,boolean failAudit) {
        SqlSessionTemplate session=new SqlSessionTemplate(sqlFactory);
        com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl couponTarget=new com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(couponTarget,"smsCouponMapper",session.getMapper(com.chengyinhub.business.mapper.SmsCouponMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(couponTarget,"smsCouponHistoryMapper",session.getMapper(com.chengyinhub.business.mapper.SmsCouponHistoryMapper.class));
        ProxyFactory couponProxy=new ProxyFactory(couponTarget);
        couponProxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        ApiVerifyController target=new ApiVerifyController(){@Override public Long getAppUserId(){return 1001L;}};
        com.chengyinhub.business.access.merchant.IMerchantAccessService access=org.mockito.Mockito.mock(com.chengyinhub.business.access.merchant.IMerchantAccessService.class);
        org.mockito.Mockito.when(access.require(1001L,com.chengyinhub.business.access.merchant.MerchantPermission.VERIFY)).thenReturn(new com.chengyinhub.business.access.merchant.MerchantAccessContext(1001L,11L,1001L,com.chengyinhub.business.access.merchant.MerchantRoleCode.MERCHANT_CHECKIN));
        org.springframework.test.util.ReflectionTestUtils.setField(target,"merchantAccessService",access);
        com.chengyinhub.business.mapper.BusinessAuditEventMapper actual=session.getMapper(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        com.chengyinhub.business.mapper.BusinessAuditEventMapper audit=(com.chengyinhub.business.mapper.BusinessAuditEventMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{com.chengyinhub.business.mapper.BusinessAuditEventMapper.class},(p,m,a)-> {
            Object result;
            try {result=m.invoke(actual,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            if(failAudit)db.update("insert into ums_member(id) values(9)"); // after actual audit insertion, rollback every earlier SQL effect
            return result;
        });
        CityNodeRedemptionApplication application=new CityNodeRedemptionApplication(access,mapper,
                (com.chengyinhub.business.service.ISmsCouponHistoryService)couponProxy.getProxy(),audit,org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.QrcodeServiceImpl.class),"retention-transaction-test-only");
        ProxyFactory proxy=new ProxyFactory(application);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        org.springframework.test.util.ReflectionTestUtils.setField(target,"cityNodeRedemptionApplication",(CityNodeRedemptionApplication)proxy.getProxy());
        return target;
    }
    @Test void actualCityRedeemAuditFailureRollsBackStatusStockHistoryAndAuditThenRetry() {
        db.update("insert into sms_coupon(id,name,publish_count,receive_count,status,del_flag) values(22,'actual transaction coupon',1,0,1,0)");
        RoamMapper fixture=cityFixtureMapper();
        assertEquals(200,cityController(fixture).complete(77L,31.2,121.4,null,null,null).get("code"));
        String code=com.chengyinhub.common.utils.VerifyDynCodeUtil.issue(77L,"citynode_9",System.currentTimeMillis(),60_000L,7L,"retention-transaction-test-only");
        assertThrows(org.springframework.dao.DataAccessException.class,()->verifyController(fixture,true).redeemCityNodeCode(code));
        assertEquals(0,db.queryForObject("select status from roam_poi_reward_log",Integer.class));
        assertEquals(0L,db.queryForObject("select receive_count from sms_coupon where id=22",Long.class));
        assertEquals(0,db.queryForObject("select count(*) from sms_coupon_history",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from business_audit_event",Integer.class));
        assertEquals(200,verifyController(fixture,false).redeemCityNodeCode(code).get("code"));
        assertEquals(1,db.queryForObject("select status from roam_poi_reward_log",Integer.class));
        assertEquals(1L,db.queryForObject("select receive_count from sms_coupon where id=22",Long.class));
        assertEquals(1,db.queryForObject("select count(*) from sms_coupon_history where member_id=9 and coupon_id=22",Integer.class));
        assertEquals(1,db.queryForObject("select count(*) from business_audit_event where action='CITY_NODE_REDEEM'",Integer.class));
    }
    @Test void changedOrMissingKeyDeniesClaimsAndCleanupWithoutRemovingCoordinates() {
        roam.requireClaimKey();
        new com.chengyinhub.business.security.PiiCryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]),"different-test-hmac").register();
        assertThrows(IllegalStateException.class,()->roam.batchInsertTile(9L,2L,java.util.List.of("wtw3sjt"),new Date()));
        assertThrows(IllegalStateException.class,()->service(roam).clear(1L,9L,cutoff));
        assertNotNull(db.queryForObject("select last_lat from roam_session where id=1",Double.class));
        new com.chengyinhub.business.security.PiiCryptoService("","").register();
        assertThrows(IllegalStateException.class,()->roam.insertPoiFoundIgnore(9L,77L,2L,new Date()));
    }
    @Test void rewardCleanupKeepsRealCouponFulfillmentWhileNoCouponRetainsOnlyClaimFingerprint() {
        db.update("insert into roam_poi_reward_log(member_id,poi_id,coupon_id,trigger_lat,trigger_lng,status,create_time) values(9,77,null,31.2,121.4,1,?),(9,78,22,31.3,121.5,0,?)",cutoff,cutoff);
        assertEquals(2,service(roam).clearRewardLocations(cutoff,10));
        assertNull(db.queryForObject("select poi_id from roam_poi_reward_log where coupon_id is null",Long.class));
        assertEquals(78L,db.queryForObject("select poi_id from roam_poi_reward_log where coupon_id=22",Long.class));
        assertEquals(0,db.queryForObject("select count(*) from roam_poi_reward_log where trigger_lat is not null or trigger_lng is not null",Integer.class));
        assertEquals(0,roam.insertRewardLogIgnore(77L,9L,null,null,null,1,new Date()));
        assertEquals(22L,roam.selectPendingCityNodeCouponId(78L,9L));
    }
    @Test void unknownEndedSessionAndUnlinkedRewardOrShareClearWithoutInventingDates() {
        db.update("update roam_session set finished_at=null where id=1");
        db.update("insert into roam_poi_reward_log(member_id,poi_id,trigger_lat,trigger_lng,status,create_time) values(9,77,31.2,121.4,1,null)");
        db.update("insert into roam_share_snapshot(share_token,member_id,session_id,snapshot_json,create_time) values('unknown',9,null,'{}',null),('orphan-unknown',9,999,'{\"track\":[1]}',null),('foreign-active-unknown',9,3,'{\"track\":[2]}',null),('owned-active-unknown',10,3,'{\"track\":[3]}',null)");
        assertTrue(roam.selectLocationRetentionCandidates(cutoff,100).stream().anyMatch(s->Long.valueOf(1L).equals(s.getId())));
        assertEquals(1,service(roam).clear(1L,9L,cutoff));
        assertNull(db.queryForObject("select last_lat from roam_session where id=1",Double.class));
        assertNull(db.queryForObject("select finished_at from roam_session where id=1",Date.class));
        assertEquals(9,db.queryForObject("select xp_awarded from roam_session where id=1",Integer.class));
        assertEquals(1,service(roam).clearRewardLocations(cutoff,100));
        assertNull(db.queryForObject("select poi_id from roam_poi_reward_log",Long.class));
        assertNotNull(db.queryForObject("select claim_fingerprint from roam_poi_reward_log",String.class));
        assertEquals(4,shares.deleteExpiredUnbound(cutoff,100));
        assertEquals(1,db.queryForObject("select count(*) from roam_share_snapshot where share_token='owned-active-unknown'",Integer.class));
        assertNotNull(db.queryForObject("select last_lat from roam_session where id=3",Double.class));
    }
    @Test void orphanFactsUseRealAgeAndUnknownTimeClearsExceptVerifiedActiveSession() {
        db.update("insert into roam_fog_tile(id,member_id,session_id,geohash7,revealed_at) values(11,9,null,'wtw3sj1',?),(12,9,null,'wtw3sj2',date_add(?,interval 1 second)),(13,9,null,'wtw3sj3',null),(14,10,3,'wtw3sj4',null)",cutoff,cutoff);
        db.update("insert into roam_poi_found(member_id,session_id,poi_id,found_at) values(9,null,77,?),(9,999,78,?),(9,3,79,null),(10,3,80,null)",cutoff,cutoff);
        db.update("insert into roam_shop_visit(member_id,session_id,source_type,source_id,shop_name,visited_at) values(9,999,1,77,'old orphan',?),(9,3,1,78,'foreign active is not owned',null),(10,3,1,79,'owned active',null)",cutoff);
        assertEquals(cutoff.getTime(),assertInstanceOf(Date.class,roam.selectCleanupTileForUpdate(11L,9L,null).get("collectedAt")).getTime());
        Long poiFactId=db.queryForObject("select id from roam_poi_found where member_id=9 and poi_id=77",Long.class);
        assertEquals(cutoff.getTime(),assertInstanceOf(Date.class,roam.selectCleanupPoiForUpdate(poiFactId,9L,null).get("collectedAt")).getTime());
        Long shopFactId=db.queryForObject("select id from roam_shop_visit where member_id=9 and source_id=77",Long.class);
        assertEquals(cutoff.getTime(),assertInstanceOf(Date.class,roam.selectCleanupShopForUpdate(shopFactId,9L,999L).get("collectedAt")).getTime());
        com.chengyinhub.business.service.impl.RoamOrphanLocationRetentionService target=new com.chengyinhub.business.service.impl.RoamOrphanLocationRetentionService(roam,orphan);
        org.springframework.test.util.ReflectionTestUtils.setField(target,"transactionManager",new DataSourceTransactionManager(source));
        ProxyFactory proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        com.chengyinhub.business.service.impl.RoamOrphanLocationRetentionService cleanup=(com.chengyinhub.business.service.impl.RoamOrphanLocationRetentionService)proxy.getProxy();
        assertEquals(2,cleanup.clearTiles(cutoff,100));
        assertEquals(3,cleanup.clearPois(cutoff,100));
        assertEquals(2,cleanup.clearShops(cutoff,100));
        assertNull(db.queryForObject("select geohash7 from roam_fog_tile where id=11",String.class));
        assertNull(db.queryForObject("select geohash7 from roam_fog_tile where id=13",String.class));
        assertEquals(2,db.queryForObject("select count(*) from roam_fog_tile where id in(12,14) and geohash7 is not null",Integer.class));
        assertEquals(7,db.queryForObject("select count(*) from roam_fog_tile",Integer.class));
        assertEquals(0,roam.batchInsertTile(9L,2L,java.util.List.of("wtw3sj1"),new Date()));
        assertEquals(0,roam.insertPoiFoundIgnore(9L,77L,2L,new Date()));
        assertEquals(0,cleanup.clearTiles(cutoff,100));
        assertEquals(80L,db.queryForObject("select poi_id from roam_poi_found where member_id=10",Long.class));
        assertEquals(79L,db.queryForObject("select source_id from roam_shop_visit where member_id=10",Long.class));
        assertEquals(3,db.queryForObject("select count(*) from roam_poi_found where claim_fingerprint is not null",Integer.class));
        assertEquals(2,db.queryForObject("select count(*) from roam_shop_visit where claim_fingerprint is not null",Integer.class));
    }
    RoamLocationRetentionService service(RoamMapper mapper) {
        RoamLocationRetentionService target=new RoamLocationRetentionService(mapper,shares);
        org.springframework.test.util.ReflectionTestUtils.setField(target,"transactionManager",new DataSourceTransactionManager(source));
        ProxyFactory proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        return (RoamLocationRetentionService)proxy.getProxy();
    }
    @Test void realOwnedBoundaryRemovesShareAndGeometryPreservesStatsAndDedupe() {
        assertEquals(1,service(roam).clear(1L,9L,cutoff));
        assertNull(db.queryForObject("select last_lat from roam_session where id=1",Double.class));
        assertNotNull(db.queryForObject("select location_cleared_at from roam_session where id=1",Date.class));
        assertEquals("{\"totalXp\":9,\"stats\":{\"distanceM\":1200}}",db.queryForObject("select finish_result_json from roam_session where id=1",String.class));
        assertEquals(9,db.queryForObject("select xp_awarded from roam_session where id=1",Integer.class));
        assertEquals(1200,db.queryForObject("select distance_m from roam_session where id=1",Integer.class));
        assertEquals(3,db.queryForObject("select count(*) from roam_fog_tile",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from roam_share_snapshot where share_token='old'",Integer.class));
        assertEquals(1,db.queryForObject("select count(*) from roam_share_snapshot where share_token='wrong-owner'",Integer.class));
        assertEquals(0,service(roam).clear(1L,9L,cutoff));
    }
    @Test void expiredOrOrphanVisitCannotExposeIdentityBeforeCleanupWhileActiveRecentRemainVisible() {
        db.update("insert into roam_shop_visit(member_id,session_id,source_type,source_id,shop_name,visited_at) values(9,1,1,77,'old',now()),(9,2,1,77,'recent',now()),(10,3,1,77,'active',now()),(9,3,1,78,'wrong owner',now()),(9,null,1,79,'unknown',null)");
        java.util.List<com.chengyinhub.business.domain.vo.ShopVisitorAvatarVO> visible=roam.selectShopVisitorAvatars(1,java.util.List.of(77L,78L,79L),10);
        assertEquals(2,visible.size());assertTrue(visible.stream().allMatch(v->Long.valueOf(77L).equals(v.getSourceId())));
        db.update("update roam_session set finished_at=null where id=2");
        visible=roam.selectShopVisitorAvatars(1,java.util.List.of(77L,78L,79L),10);
        assertEquals(1,visible.size());assertEquals("other-avatar",visible.get(0).getAvatar());
        assertEquals(9,db.queryForObject("select xp_awarded from roam_session where id=1",Integer.class));
    }
    @Test void activeRecentAndOtherOwnerCannotClear() {
        assertEquals(0,service(roam).clear(1L,10L,cutoff));
        assertEquals(0,service(roam).clear(2L,9L,cutoff));
        assertEquals(0,service(roam).clear(3L,10L,cutoff));
        assertEquals(4,db.queryForObject("select count(*) from roam_share_snapshot",Integer.class));
    }
    @Test void zeroFinalCasRollsBackRealShareDelete() {
        RoamMapper injected=(RoamMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RoamMapper.class},(p,m,a)->
                m.getName().equals("clearEndedSessionLocation") ? 0 : m.invoke(roam,a));
        assertThrows(IllegalStateException.class,()->service(injected).clear(1L,9L,cutoff));
        assertEquals(1,db.queryForObject("select count(*) from roam_share_snapshot where share_token='old'",Integer.class));
        assertNotNull(db.queryForObject("select last_lat from roam_session where id=1",Double.class));
    }
    @Test void oldGeometryNotReadableBeforeJobRunsAndLegacyPrunesInBoundedBatch() {
        assertEquals(java.util.List.of("wtw3sjr"),roam.selectTilesByMember(9L,100));
        assertEquals(2L,((Number)roam.selectTilePageByMember(9L,0L,100).get(0).get("id")).longValue());
        assertNull(shares.selectSnapshotJsonByToken("old"));
        assertNotNull(shares.selectSnapshotJsonByToken("recent"));
        assertNull(shares.selectSnapshotJsonByToken("legacy"));
        assertEquals(1,shares.deleteExpiredUnbound(cutoff,1));
        assertEquals(0,shares.deleteExpiredUnbound(cutoff,1));
    }
    @AfterAll void cleanup() {
        if(admin!=null && database!=null)admin.execute("drop database "+database);
    }
    @Test void anonymousVisitorTotalsSurviveGeometryErasureAndIdempotentReconciliation() {
        db.update("insert into roam_shop_visit(member_id,session_id,source_type,source_id,shop_name,visited_at) values(9,1,1,77,'old',now()),(9,2,1,77,'same-person',now()),(10,3,1,77,'other-person',now()),(9,null,1,78,'orphan',null)");
        assertEquals(2,visitorCount(77L));
        assertEquals(1,service(roam).clear(1L,9L,cutoff));
        assertEquals(2,visitorCount(77L));
        assertEquals(0,service(roam).clear(1L,9L,cutoff));
        assertEquals(1,orphanService(roam,orphan).clearShops(cutoff,100));
        assertEquals(1,visitorCount(78L));
        assertEquals(0,db.queryForObject("select count(*) from roam_shop_visit where shop_name in ('old','orphan')",Integer.class));
        assertEquals(2,db.queryForObject("select count(*) from roam_shop_visitor_claim",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from information_schema.columns where table_schema=? and table_name in ('roam_shop_visitor_claim','roam_shop_visitor_total') and column_name in ('member_id','session_id','lat','lng','coordinates')",Integer.class,database));
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.execute(status->{roam.lockClaimOwner(9L);roam.recordAnonymousShopVisitor(9L,1,77L);return null;});
        assertEquals(2,visitorCount(77L));
        assertEquals(1,db.queryForObject("select total from roam_shop_visitor_total where source_type=1 and source_id=77",Integer.class));
        assertEquals(9,db.queryForObject("select xp_awarded from roam_session where id=1",Integer.class));
    }

    @Test void realCleanupTaskPreservesFavoriteRouteAndNonLocationFactsAcrossReplay() throws Exception {
        // The real retention owner also redacts expired ASR bodies; retain its original durable identities.
        try (java.sql.Connection connection = source.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource("sql/migration_20261024_d_npc_asr_intent.sql"));
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource("sql/migration_20261024_d_npc_asr_shared_quota.sql"));
        }
        for (int id = 101; id <= 103; id++) {
            boolean unknown = id == 103;
            db.update("insert into npc_asr_intent(id,user_id,request_id,node_id,merchant_member_id,audio_sha256,audio_format,"
                    + "claim_token,state,transcript,body_expires_at,handoff_started,text_admitted,quota_reservation,create_time) "
                    + "values(?,9,?,31,81,?,'wav',?,?,?,?,?,?,?,date_sub(now(),interval 49 hour))",
                    id, UUID.randomUUID().toString(), "b".repeat(64), UUID.randomUUID().toString(),
                    unknown ? "UNKNOWN" : "RECEIVED", unknown ? null : "original transcript " + id,
                    unknown ? null : db.queryForObject(id == 101 ? "select date_sub(now(),interval 1 hour)"
                            : "select date_add(now(),interval 1 hour)", Date.class),
                    unknown ? 0 : 1, unknown ? 0 : 1, "original-reservation-" + id);
        }
        var asrIdentities = asrIdentitySnapshot();
        var freshAsr = db.queryForMap("select * from npc_asr_intent where id=102");
        var unknownAsr = db.queryForMap("select * from npc_asr_intent where id=103");
        for (String table : java.util.List.of("tms_browse_record", "biz_event_log", "npc_chat_log", "npc_chat_request")) {
            String timeColumn = table.equals("tms_browse_record") ? "browse_time" : "create_time";
            db.execute("create table " + table + "(id bigint primary key," + timeColumn + " datetime not null) engine=InnoDB");
            String oldAge = switch (table) {
                case "tms_browse_record" -> "181 day";
                case "biz_event_log" -> "366 day";
                case "npc_chat_log" -> "91 day";
                default -> "49 hour";
            };
            db.execute("insert into " + table + " values(1,date_sub(now(),interval " + oldAge + ")),(2,now())");
        }
        // These are the real topic-route/favorite columns used by /api/topic/like_list, not a cleanup-only table.
        db.execute("create table cms_topic(id bigint primary key,name varchar(100),route_mode varchar(32),route_graph_json json,"
                + "published_route_graph_json json,config_version bigint,published_config_version bigint,del_flag int) engine=InnoDB");
        db.execute("create table cms_topic_like(id bigint primary key,member_id bigint,data_id bigint,like_type bigint,"
                + "create_by varchar(64),create_time datetime,update_by varchar(64),update_time datetime) engine=InnoDB");
        String route = "{\"nodes\":[{\"id\":1,\"lat\":31.6,\"lng\":121.8}],\"edges\":[]}";
        db.update("insert into cms_topic values(77,'retention favorite route','BRANCH_GRAPH',?,?,4,4,0)", route, route);
        db.update("insert into cms_topic_like values(1,9,77,1,'fixture',date_sub(now(),interval 400 day),'fixture',now())");
        var favoritesBefore = favoriteRouteSnapshot();

        db.execute("update roam_session set finished_at=date_sub(now(),interval 31 day) where id=1");
        db.execute("insert into roam_session(id,member_id,status,finished_at,last_lat,last_lng,finish_result_json,distance_m,tiles_revealed,xp_awarded)"
                + " values(4,9,1,null,31.7,121.9,'{\"totalXp\":5,\"stats\":{\"distanceM\":600}}',600,2,5)");
        db.execute("insert into roam_fog_tile(member_id,session_id,geohash7,revealed_at) values(9,null,'wtw3sjz',null)");
        db.execute("insert into roam_poi_found(member_id,session_id,poi_id,found_at) values(9,1,77,date_sub(now(),interval 31 day))");
        db.execute("insert into roam_poi_reward_log(member_id,poi_id,trigger_lat,trigger_lng,status,create_time) values(9,77,31.2,121.4,1,null)");
        db.execute("insert into roam_shop_visit(member_id,session_id,source_type,source_id,shop_name,visited_at)"
                + " values(9,1,1,77,'expired',now()),(9,null,1,78,'unknown orphan',null)");

        SqlSessionFactoryBean extraFactory = new SqlSessionFactoryBean();
        extraFactory.setDataSource(source);
        extraFactory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        extraFactory.setMapperLocations(new ClassPathResource("mapper/business/TmsBrowseRecordMapper.xml"),
                new ClassPathResource("mapper/business/BizEventLogMapper.xml"),
                new ClassPathResource("mapper/business/NpcChatLogMapper.xml"),
                new ClassPathResource("mapper/business/NpcChatRequestMapper.xml"),
                new ClassPathResource("mapper/business/NpcAsrIntentMapper.xml"));
        SqlSessionTemplate extra = new SqlSessionTemplate(extraFactory.getObject());
        var target = new com.chengyinhub.business.service.impl.DataRetentionServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(target, "tmsBrowseRecordMapper", extra.getMapper(com.chengyinhub.business.mapper.TmsBrowseRecordMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(target, "bizEventLogMapper", extra.getMapper(com.chengyinhub.business.mapper.BizEventLogMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(target, "npcChatLogMapper", extra.getMapper(com.chengyinhub.business.mapper.NpcChatLogMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(target, "npcChatRequestMapper", extra.getMapper(com.chengyinhub.business.mapper.NpcChatRequestMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(target, "npcAsrIntents", extra.getMapper(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(target, "roamMapper", roam);
        org.springframework.test.util.ReflectionTestUtils.setField(target, "roamShareSnapshotMapper", shares);
        org.springframework.test.util.ReflectionTestUtils.setField(target, "roamLocationRetentionService", service(roam));
        org.springframework.test.util.ReflectionTestUtils.setField(target, "roamOrphanLocationRetentionService", orphanService(roam, orphan));
        var task = new com.chengyinhub.business.task.DataRetentionCleanupTask();
        org.springframework.test.util.ReflectionTestUtils.setField(task, "dataRetentionService", target);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "browseDays", 180);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "eventDays", 365);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "npcChatDays", 90);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "npcChatRequestHours", 48);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "batchSize", 2);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "maxBatches", 20);

        assertDoesNotThrow(task::run);
        for (String table : java.util.List.of("tms_browse_record", "biz_event_log", "npc_chat_log", "npc_chat_request"))
            assertEquals(java.util.List.of(2L), db.queryForList("select id from " + table + " order by id", Long.class));
        assertEquals(asrIdentities, asrIdentitySnapshot(), "body expiry must not release ASR/quota/UNKNOWN identity");
        assertEquals(java.util.List.of(101L, 102L, 103L), db.queryForList("select id from npc_asr_intent order by id", Long.class));
        assertNull(db.queryForObject("select transcript from npc_asr_intent where id=101", String.class));
        assertEquals(1, db.queryForObject("select body_erased from npc_asr_intent where id=101", Integer.class));
        assertEquals(freshAsr, db.queryForMap("select * from npc_asr_intent where id=102"));
        assertEquals(unknownAsr, db.queryForMap("select * from npc_asr_intent where id=103"));
        assertEquals(0, db.queryForObject("select count(*) from roam_session where id in(1,4) and (last_lat is not null or last_lng is not null)", Integer.class));
        assertEquals(14, db.queryForObject("select sum(xp_awarded) from roam_session where id in(1,4)", Integer.class));
        assertEquals(1800, db.queryForObject("select sum(distance_m) from roam_session where id in(1,4)", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from roam_session where id in(1,4) and finish_result_json like '%track%'", Integer.class));
        assertNotNull(db.queryForObject("select last_lat from roam_session where id=2", Double.class));
        assertNotNull(db.queryForObject("select last_lat from roam_session where id=3", Double.class));
        assertEquals(0, db.queryForObject("select count(*) from roam_share_snapshot where share_token in('old','legacy')", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from roam_fog_tile where member_id=9 and geohash7 is not null and session_id is null", Integer.class));
        assertEquals(1, db.queryForObject("select count(*) from roam_poi_found where member_id=9 and poi_id is null and claim_fingerprint is not null", Integer.class));
        assertEquals(1, db.queryForObject("select count(*) from roam_poi_reward_log where trigger_lat is null and trigger_lng is null and poi_id is null and claim_fingerprint is not null", Integer.class));
        assertEquals(1, visitorCount(77L));
        assertEquals(1, visitorCount(78L));
        assertEquals(favoritesBefore, favoriteRouteSnapshot());
        var afterFirstRun = retainedFactsSnapshot();
        assertDoesNotThrow(task::run);
        assertEquals(afterFirstRun, retainedFactsSnapshot(), "second real task run must not change retained facts or recreate coordinates");
        assertEquals(favoritesBefore, favoriteRouteSnapshot());
        assertEquals(0, roam.insertPoiFoundIgnore(9L,77L,2L,new Date()), "cleanup must not permit a second reward");
    }

    private java.util.List<java.util.Map<String,Object>> favoriteRouteSnapshot() {
        return db.queryForList("select t.id,t.name,t.route_mode,cast(t.route_graph_json as char) as route_graph_json,"
                + "cast(t.published_route_graph_json as char) as published_route_graph_json,t.config_version,t.published_config_version,t.del_flag,"
                + "l.id as favorite_id,l.member_id,l.data_id,l.like_type,l.create_by,l.create_time,l.update_by,l.update_time"
                + " from cms_topic t join cms_topic_like l on l.data_id=t.id order by t.id,l.id");
    }

    private java.util.List<java.util.Map<String,Object>> asrIdentitySnapshot() {
        return db.queryForList("select id,user_id,request_id,node_id,merchant_member_id,audio_sha256,audio_format,"
                + "claim_token,state,handoff_started,text_admitted,quota_reservation,quota_refund_pending,"
                + "body_expires_at,create_time from npc_asr_intent order by id");
    }

    private java.util.Map<String,java.util.List<java.util.Map<String,Object>>> retainedFactsSnapshot() {
        var result = new java.util.LinkedHashMap<String,java.util.List<java.util.Map<String,Object>>>();
        for (String table : java.util.List.of("roam_session","roam_fog_tile","roam_poi_found","roam_poi_reward_log",
                "roam_shop_visit","roam_share_snapshot","roam_shop_visitor_claim","roam_shop_visitor_total",
                "tms_browse_record","biz_event_log","npc_chat_log","npc_chat_request","npc_asr_intent"))
            result.put(table,db.queryForList("select * from " + table + " order by 1,2"));
        return result;
    }
    private int visitorCount(Long sourceId) {
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setReadOnly(true);
        return transaction.execute(status->roam.selectShopVisitorCounts(1,java.util.List.of(sourceId)).stream()
                .filter(row->sourceId.equals(row.getSourceId())).mapToInt(row->row.getTotal()).findFirst().orElse(0));
    }
    @Test void failedRawErasureRollsBackAnonymousVisitorAndCounterTogether() {
        db.update("insert into roam_shop_visit(member_id,session_id,source_type,source_id,shop_name,visited_at) values(9,1,1,77,'old',now())");
        RoamMapper failing=(RoamMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RoamMapper.class},(p,m,a)-> {
            Object result=invokeMapper(m,roam,a);return m.getName().equals("clearSessionShopLocation")?0:result;
        });
        assertThrows(IllegalStateException.class,()->service(failing).clear(1L,9L,cutoff));
        assertEquals(77L,db.queryForObject("select source_id from roam_shop_visit limit 1",Long.class));
        assertEquals(0,db.queryForObject("select count(*) from roam_shop_visitor_claim",Integer.class));
        assertEquals(0,db.queryForObject("select count(*) from roam_shop_visitor_total",Integer.class));
        assertEquals(1,visitorCount(77L));
    }
    @Test void revokedConsentRejectsOldAgreeRetryAndFreshWritesUntilExplicitCurrentCasAgreement() {
        var records=new SqlSessionTemplate(sqlFactory).getMapper(com.chengyinhub.business.mapper.ConsentRecordMapper.class);
        var consent=consentService(records);
        db.update("update roam_session set status=0,finished_at=null where id=2");
        db.update("update consent_record set occurred_at=date_add(now(),interval 1 day) where request_id='initial9'");
        consent.record(9L,"REVOKE","revoke9",null,"");
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,()->controller(roam).reveal("2","wtw3sjt"));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,()->poiWriter(roam).recordPoiFound(9L,2L,77L));
        assertThrows(com.chengyinhub.business.service.impl.RoamLocationConsentService.ConsentChangedException.class,
                ()->consent.record(9L,"AGREE","initial9","", ""));
        assertThrows(com.chengyinhub.business.service.impl.RoamLocationConsentService.ConsentChangedException.class,
                ()->consent.record(9L,"AGREE","late-agree","initial9", ""));
        assertEquals("REVOKE",records.selectLatestRoamLocation(9L).getEventType());
        consent.record(9L,"AGREE","new-agree","revoke9","");
        assertEquals(200,controller(roam).reveal("2","wtw3sjt").get("code"));
        assertThrows(com.chengyinhub.business.service.impl.RoamLocationConsentService.ConsentChangedException.class,
                ()->consent.record(9L,"REVOKE","revoke9",null,""));
        assertEquals("new-agree",records.selectLatestRoamLocation(9L).getRequestId());
        assertEquals(1,db.queryForObject("select count(*) from consent_record where request_id='new-agree'",Integer.class));
    }
    @Test void inFlightGeometryWaitsForRevocationCommitAndCannotUseAnOlderRepeatableReadSnapshot() throws Exception {
        db.update("update roam_session set status=0,finished_at=null where id=2");
        db.update("insert into roam_session(id,member_id,status,xp_awarded) values(4,9,0,0)");
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var holderId=new java.util.concurrent.atomic.AtomicLong();var firstId=new java.util.concurrent.atomic.AtomicLong();var secondId=new java.util.concurrent.atomic.AtomicLong();
        var real=new SqlSessionTemplate(sqlFactory).getMapper(com.chengyinhub.business.mapper.ConsentRecordMapper.class);
        var paused=(com.chengyinhub.business.mapper.ConsentRecordMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{com.chengyinhub.business.mapper.ConsentRecordMapper.class},(p,m,a)-> {
            Object result;try{result=m.invoke(real,a);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            if(m.getName().equals("insertConsentRecord")) {
                holderId.set(connectionId(DataSourceUtils.getConnection(source)));held.countDown();assertTrue(release.await(15,java.util.concurrent.TimeUnit.SECONDS));
            }
            return result;
        });
        var pool=java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var revoke=pool.submit(()->consentService(paused).record(9L,"REVOKE","racing-revoke",null,""));
            assertTrue(held.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var first=pool.submit(()->claimTileOnRecordedConnection(2L,firstId,true));
            var second=pool.submit(()->claimTileOnRecordedConnection(4L,secondId,true));
            boolean waited=false;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline) {
                assertFalse(first.isDone());assertFalse(second.isDone());
                if(actualOwnerLockWait(holderId.get(),firstId.get(),secondId.get())){waited=true;break;}
                Thread.sleep(150); // INNODB_TRX cache refresh needs >100ms between reads.
            }
            assertTrue(waited,ownerLockDiagnostic(holderId.get(),firstId.get(),secondId.get()));
            assertThrows(java.util.concurrent.TimeoutException.class,()->first.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,()->second.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();assertEquals("REVOKE",revoke.get(5,java.util.concurrent.TimeUnit.SECONDS).getEventType());
            var one=assertThrows(java.util.concurrent.ExecutionException.class,()->first.get(5,java.util.concurrent.TimeUnit.SECONDS));
            var two=assertThrows(java.util.concurrent.ExecutionException.class,()->second.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(com.chengyinhub.common.exception.ServiceException.class,one.getCause());
            assertInstanceOf(com.chengyinhub.common.exception.ServiceException.class,two.getCause());
            assertEquals(3,db.queryForObject("select count(*) from roam_fog_tile",Integer.class));
            assertEquals(2,db.queryForObject("select xp_awarded from roam_session where id=2",Integer.class));
            assertEquals(0,db.queryForObject("select xp_awarded from roam_session where id=4",Integer.class));
            assertEquals(0,db.queryForObject("select count(*) from roam_claim_key_guard",Integer.class));
        } finally {release.countDown();pool.shutdownNow();}
    }
}

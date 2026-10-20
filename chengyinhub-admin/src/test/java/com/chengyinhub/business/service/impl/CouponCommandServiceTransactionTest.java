package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CouponPublisDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real H2/MyBatis/Spring transactions; only authority, Redis and member/role ports are fixture doubles. */
class CouponCommandServiceTransactionTest {
    static class CountingManager extends DataSourceTransactionManager {
        int begins;
        boolean failAfterCommit;
        CountingManager(javax.sql.DataSource ds) { super(ds); }
        @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            begins++; super.doBegin(transaction,definition);
        }
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
            super.doCommit(status);
            if (failAfterCommit) throw new org.springframework.transaction.TransactionSystemException("synthetic lost commit acknowledgement");
        }
    }
    CountingManager manager;
    final ThreadLocal<java.sql.Connection> ownerConnection = new ThreadLocal<>();
    CouponCommandService service;
    JdbcTemplate jdbc;
    CouponCommandReceiptMapper receipts;
    SmsCouponMapper coupons;
    SmsCouponServiceImpl couponService;
    IQuotaService quota;
    IMerchantAccessService access;
    IUmsMemberService members;
    RedisCache redis;
    UserPermission permissions;
    MerchantAccessContext actor = new MerchantAccessContext(7002L, 11L, 9001L, MerchantRoleCode.MERCHANT_MARKETING);
    String key = "11111111-1111-4111-8111-111111111111";

    static Path root() {
        Path p = Paths.get(System.getProperty("coupon.test.root", ".")).toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("chengyinhub-admin/sql/migration_coupon_command_receipt.sql"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("repo root not found");
        return p;
    }
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:coupon_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table fixture_owner(id bigint primary key)"); jdbc.update("insert into fixture_owner values(9001)");
        jdbc.execute("create table sms_coupon(id bigint auto_increment primary key,name varchar(255),coupon_type int,platform int,start_time timestamp,end_time timestamp,publish_count bigint,use_count bigint,receive_count bigint,scope_type int,member_id bigint,merchant_id bigint,status int,del_flag int,description varchar(512),create_time timestamp,update_time timestamp)");
        jdbc.execute("create table sms_coupon_history(id bigint primary key,coupon_id bigint,use_status int)");
        String ddl = new String(Files.readAllBytes(root().resolve("chengyinhub-admin/sql/migration_coupon_command_receipt.sql")), java.nio.charset.StandardCharsets.UTF_8);
        // Only MySQL storage/charset syntax removed for H2. Oracle MySQL must execute original separately.
        ddl = ddl.replace("CHARACTER SET ascii COLLATE ascii_bin", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        jdbc.execute(ddl);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(ds);
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.getTypeAliasRegistry().registerAlias("SmsCoupon", SmsCoupon.class);
        factory.setConfiguration(config);
        Path mapper = root().resolve("chengyinhub-system/src/main/resources/mapper/business");
        factory.setMapperLocations(new FileSystemResource(mapper.resolve("SmsCouponMapper.xml")), new FileSystemResource(mapper.resolve("CouponCommandReceiptMapper.xml")));
        SqlSessionFactory sqlFactory = factory.getObject(); SqlSessionTemplate session = new SqlSessionTemplate(sqlFactory);
        coupons = session.getMapper(SmsCouponMapper.class); receipts = session.getMapper(CouponCommandReceiptMapper.class);
        manager = new CountingManager(ds);
        members = mock(IUmsMemberService.class);
        when(members.lockMemberById(9001L)).thenAnswer(i -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            java.sql.Connection connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);
            if (ownerConnection.get() == null) {
                ownerConnection.set(connection);
                TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {ownerConnection.remove();}
                });
            }
            else assertSame(ownerConnection.get(),connection,"both owner locks must use the same transaction connection");
            return jdbc.queryForObject("select id from fixture_owner where id=9001 for update", Long.class);
        });
        when(members.selectUmsMemberById(9001L)).thenReturn(new UmsMember());
        IRoleService roles = mock(IRoleService.class); permissions = new UserPermission();
        permissions.setCanPublishCoupon(true); permissions.setMaxCoupons(100);
        when(roles.getPermission(any())).thenReturn(permissions);
        couponService = spy(new SmsCouponServiceImpl()); ReflectionTestUtils.setField(couponService, "smsCouponMapper", coupons);
        doAnswer(i -> {
            assertSame(ownerConnection.get(),org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds),"coupon insertion must share owner/quota connection");
            return i.callRealMethod();
        }).when(couponService).insertSmsCoupon(any());
        QuotaServiceImpl target = new QuotaServiceImpl(); ReflectionTestUtils.setField(target, "umsMemberService", members);
        ReflectionTestUtils.setField(target, "roleService", roles); ReflectionTestUtils.setField(target, "smsCouponService", couponService);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); quota = (IQuotaService) proxy.getProxy();
        access = mock(IMerchantAccessService.class); when(access.require(7002L, MerchantPermission.COUPON_MANAGE)).thenReturn(actor);
        redis = mock(RedisCache.class); when(redis.setIfAbsent(anyString(),eq("1"),eq(60),eq(TimeUnit.SECONDS))).thenReturn(true);
        service = new CouponCommandService();
        ReflectionTestUtils.setField(service,"enabled",true); ReflectionTestUtils.setField(service,"merchantAccessService",access);
        ReflectionTestUtils.setField(service,"umsMemberService",members); ReflectionTestUtils.setField(service,"quotaService",quota);
        ReflectionTestUtils.setField(service,"smsCouponService",couponService); ReflectionTestUtils.setField(service,"smsCouponMapper",coupons);
        ReflectionTestUtils.setField(service,"receiptMapper",receipts); ReflectionTestUtils.setField(service,"redisCache",redis);
        ReflectionTestUtils.setField(service,"transactionManager",manager);
    }
    CouponPublisDTO request() {
        CouponPublisDTO r = new CouponPublisDTO(); r.setScope("MERCHANT"); r.setName("测试券"); r.setDescription(null);
        r.setCouponType(0); r.setPublishCount(10L); r.setStartTime(new Date(1791129600000L)); r.setEndTime(new Date(1791302399000L)); return r;
    }
    AjaxResult publish() { return service.publish(7002L,key,11L,request()); }
    int count(String table) { return jdbc.queryForObject("select count(*) from " + table,Integer.class); }
    @SuppressWarnings("unchecked") Map<String,Object> receipt(AjaxResult result) { return (Map<String,Object>) ((Map<?,?>)result.get("data")).get("commandReceipt"); }
    long couponId(AjaxResult result) { return ((Number)receipt(result).get("couponId")).longValue(); }
    void injectedReceiptFailure(boolean after) {
        CouponCommandReceiptMapper broken = mock(CouponCommandReceiptMapper.class);
        when(broken.selectCurrent(anyLong(),anyLong(),anyString())).thenAnswer(i -> receipts.selectCurrent(i.getArgument(0),i.getArgument(1),i.getArgument(2)));
        when(broken.insertReceipt(any())).thenAnswer(i -> { if (after) receipts.insertReceipt(i.getArgument(0)); throw new IllegalStateException("injected receipt failure"); });
        ReflectionTestUtils.setField(service,"receiptMapper",broken);
    }
    @Test void commitsCouponAndReceiptAndReplaysBeforeQuotaAndMinute() {
        AjaxResult first = publish(); assertEquals(200,first.get("code")); assertEquals(1,count("sms_coupon")); assertEquals(1,count("coupon_command_receipt"));
        permissions.setMaxCoupons(0); when(redis.setIfAbsent(anyString(),eq("1"),eq(60),eq(TimeUnit.SECONDS))).thenReturn(false);
        assertEquals(first,publish()); verify(redis,times(1)).setIfAbsent(anyString(),eq("1"),eq(60),eq(TimeUnit.SECONDS));
    }
    @Test void differentPayloadAndOperationConflict() {
        publish(); CouponPublisDTO changed=request(); changed.setName("changed");
        assertEquals(409,service.publish(7002L,key,11L,changed).get("code"));
        assertEquals(409,service.stop(7002L,key,11L,1L,"MERCHANT").get("code")); assertEquals(1,count("sms_coupon"));
    }
    @Test void receiptFailureBeforeInsertRollsBackCoupon() { injectedReceiptFailure(false); assertThrows(IllegalStateException.class,this::publish); assertEquals(0,count("sms_coupon")); assertEquals(0,count("coupon_command_receipt")); }
    @Test void receiptFailureAfterInsertRollsBackBoth() { injectedReceiptFailure(true); assertThrows(IllegalStateException.class,this::publish); assertEquals(0,count("sms_coupon")); assertEquals(0,count("coupon_command_receipt")); }
    @Test void exceptionAfterCouponInsertRollsBackBoth() {
        doAnswer(i -> { i.callRealMethod(); throw new IllegalStateException("post insert"); }).when(couponService).insertSmsCoupon(any());
        assertThrows(IllegalStateException.class,this::publish); assertEquals(0,count("sms_coupon")); assertEquals(0,count("coupon_command_receipt"));
    }
    @Test void missingGeneratedKeyRollsBackBoth() {
        doAnswer(i -> { int rows=(Integer)i.callRealMethod(); ((SmsCoupon)i.getArgument(0)).setId(null); return rows; }).when(couponService).insertSmsCoupon(any());
        assertThrows(IllegalStateException.class,this::publish); assertEquals(0,count("sms_coupon")); assertEquals(0,count("coupon_command_receipt"));
    }
    @Test void committedOutcomeSurvivesPostResponseFailure() {
        AjaxResult written=publish(); assertThrows(IllegalStateException.class,()-> {throw new IllegalStateException("serialization after returned commit");});
        assertEquals(written,service.read(7002L,key,11L,"MERCHANT")); assertEquals(1,count("sms_coupon"));
    }
    @Test void deterministicQuotaRejectionIsDurable() {
        permissions.setMaxCoupons(0); AjaxResult first=publish(); assertEquals("REJECTED",receipt(first).get("outcome"));
        permissions.setMaxCoupons(100); assertEquals(first,publish()); assertEquals(0,count("sms_coupon")); assertEquals(1,count("coupon_command_receipt")); verifyNoInteractions(redis);
    }
    @Test void deterministicMinuteRejectionIsDurable() {
        when(redis.setIfAbsent(anyString(),eq("1"),eq(60),eq(TimeUnit.SECONDS))).thenReturn(false);
        assertEquals("PUBLISH_RATE_LIMITED",receipt(publish()).get("reasonCode")); assertEquals(0,count("sms_coupon")); assertEquals(1,count("coupon_command_receipt"));
    }
    @Test void concurrentSameKeyCommitsOneCoupon() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try { Callable<AjaxResult> work=()->{ start.await(); return publish(); }; Future<AjaxResult>a=pool.submit(work),b=pool.submit(work);start.countDown();
            assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)); assertEquals(1,count("sms_coupon")); assertEquals(1,count("coupon_command_receipt"));
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentDifferentKeysKeepActualQuotaGuard() throws Exception {
        permissions.setMaxCoupons(1); ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try { Future<AjaxResult>a=pool.submit(()->{start.await();return publish();}); Future<AjaxResult>b=pool.submit(()->{start.await();return service.publish(7002L,UUID.randomUUID().toString(),11L,request());});start.countDown();
            Set<Object> codes=new HashSet<>(Arrays.asList(a.get(15,TimeUnit.SECONDS).get("code"),b.get(15,TimeUnit.SECONDS).get("code")));
            assertEquals(new HashSet<>(Arrays.asList(200,500)),codes); assertEquals(1,count("sms_coupon")); assertEquals(2,count("coupon_command_receipt"));
        } finally {pool.shutdownNow();}
    }
    @Test void stopReplayPreservesClaimedHistoryAndFreshKeyRejects() {
        long id=couponId(publish()); jdbc.update("insert into sms_coupon_history values(1,?,0)",id);
        String stop=UUID.randomUUID().toString(); AjaxResult first=service.stop(7002L,stop,11L,id,"MERCHANT");
        assertEquals(200,first.get("code")); assertEquals(first,service.stop(7002L,stop,11L,id,"MERCHANT"));
        assertEquals("COUPON_ALREADY_STOPPED",receipt(service.stop(7002L,UUID.randomUUID().toString(),11L,id,"MERCHANT")).get("reasonCode"));
        assertEquals(0,jdbc.queryForObject("select use_status from sms_coupon_history where id=1",Integer.class));
    }
    @Test void stopReceiptFailureRollsBackStatus() {
        long id=couponId(publish()); injectedReceiptFailure(true);
        assertThrows(IllegalStateException.class,()->service.stop(7002L,UUID.randomUUID().toString(),11L,id,"MERCHANT"));
        assertEquals(1,jdbc.queryForObject("select status from sms_coupon where id=?",Integer.class,id)); assertEquals(1,count("coupon_command_receipt"));
    }
    @Test void stopForeignMerchantCannotMutate() {
        long id=couponId(publish()); jdbc.update("update sms_coupon set merchant_id=12 where id=?",id);
        assertEquals(500,service.stop(7002L,UUID.randomUUID().toString(),11L,id,"MERCHANT").get("code"));
        assertEquals(1,jdbc.queryForObject("select status from sms_coupon where id=?",Integer.class,id));
    }
    @Test void terminalStatusesAndDeletedCannotStop() {
        long id=couponId(publish()); for(int status:new int[]{2,3,4}) {jdbc.update("update sms_coupon set status=? where id=?",status,id);
            assertEquals(500,service.stop(7002L,UUID.randomUUID().toString(),11L,id,"MERCHANT").get("code"));
            assertEquals(status,jdbc.queryForObject("select status from sms_coupon where id=?",Integer.class,id));}
        jdbc.update("update sms_coupon set status=1,del_flag=1 where id=?",id); assertEquals(500,service.stop(7002L,UUID.randomUUID().toString(),11L,id,"MERCHANT").get("code"));
    }
    @Test void actorCannotReadAnotherActorsReceipt() {
        publish(); when(access.require(7003L,MerchantPermission.COUPON_MANAGE)).thenReturn(new MerchantAccessContext(7003L,11L,9001L,MerchantRoleCode.MERCHANT_MANAGER));
        assertEquals(404,service.read(7003L,key,11L,"MERCHANT").get("code"));
        assertThrows(ServiceException.class,()->service.read(7002L,key,12L,"MERCHANT"));
    }
    @Test void revokedActorCannotReplayOrRead() {
        publish(); when(access.require(7002L,MerchantPermission.COUPON_MANAGE)).thenThrow(new ServiceException("revoked",403));
        assertThrows(ServiceException.class,this::publish); assertThrows(ServiceException.class,()->service.read(7002L,key,11L,"MERCHANT")); assertEquals(1,count("sms_coupon"));
    }
    @Test void disabledAndPartialMetadataNeverMutate() {
        ReflectionTestUtils.setField(service,"enabled",false); assertEquals(503,publish().get("code"));
        ReflectionTestUtils.setField(service,"enabled",true); assertEquals(400,service.publish(7002L,null,11L,request()).get("code"));
        assertEquals(400,service.publish(7002L,key,null,request()).get("code")); CouponPublisDTO request=request();request.setScope(null);
        assertEquals(400,service.publish(7002L,key,11L,request).get("code")); assertEquals(0,count("sms_coupon"));verifyNoInteractions(redis);
    }
    @Test void missingTableFailsClosedWithoutCoupon() {
        jdbc.execute("drop table coupon_command_receipt"); assertThrows(RuntimeException.class,this::publish); assertEquals(0,count("sms_coupon"));
    }
    @Test void canonicalHashSeparatesNullEmptyDatesAndScope() {
        CouponPublisDTO a=request(),b=request();
        StringBuilder golden=new StringBuilder();
        for(byte value:CouponCommandService.hash("PUBLISH",actor,a,null)) golden.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
        assertEquals("a72d49610a0a0687449a8068e4565db197cb007ca6d6b28ffa140235b6817cbb",golden.toString(),"independent length-prefixed v1 UTF-8 hash vector");
        assertArrayEquals(CouponCommandService.hash("PUBLISH",actor,a,null),CouponCommandService.hash("PUBLISH",actor,b,null));
        b.setDescription(""); assertFalse(Arrays.equals(CouponCommandService.hash("PUBLISH",actor,a,null),CouponCommandService.hash("PUBLISH",actor,b,null)));
        b=request();b.setStartTime(new Date(a.getStartTime().getTime()+1)); assertFalse(Arrays.equals(CouponCommandService.hash("PUBLISH",actor,a,null),CouponCommandService.hash("PUBLISH",actor,b,null)));
        assertFalse(Arrays.equals(CouponCommandService.hash("PUBLISH",actor,a,null),CouponCommandService.hash("PUBLISH",new MerchantAccessContext(7002L,12L,9001L,MerchantRoleCode.MERCHANT_MARKETING),a,null)));
    }
    @Test void actualControllerGenericFailureAfterCommitRecoversReceipt() throws Exception {
        com.chengyinhub.web.controller.api.ApiCouponController controller = new com.chengyinhub.web.controller.api.ApiCouponController() {
            @Override public Long getAppUserId() { return 7002L; }
        };
        CouponCommandService afterCommit = mock(CouponCommandService.class);
        when(afterCommit.publish(eq(7002L),eq(key),eq(11L),any())).thenAnswer(i -> {
            service.publish(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));
            throw new IllegalStateException("synthetic postcommit response failure");
        });
        ReflectionTestUtils.setField(controller,"couponCommandService",afterCommit);
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.chengyinhub.framework.web.exception.GlobalExceptionHandler()).build();
        String body = "{\"scope\":\"MERCHANT\",\"name\":\"test\",\"couponType\":0,\"publishCount\":10,\"startTime\":\"2026-10-05 00:00:00\",\"endTime\":\"2026-10-06 23:59:59\"}";
        org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/coupon/publish")
                .header("X-Coupon-Command-Id",key).header("X-Coupon-Merchant-Id","11")
                .contentType("application/json").content(body)).andReturn().getResponse();
        assertEquals(200,response.getStatus());
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertEquals(500,json.get("code").intValue()); assertFalse(json.has("data"));
        assertEquals("SUCCEEDED",receipt(service.read(7002L,key,11L,"MERCHANT")).get("outcome"));
        assertEquals(1,count("sms_coupon")); assertEquals(1,count("coupon_command_receipt"));
    }
    @Test void actualQuotaRequiredProxyJoinsSingleOuterTransaction() {
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(quota));
        publish(); assertEquals(1,manager.begins,"quota REQUIRED must not commit an independent transaction");
        verify(members,times(2)).lockMemberById(9001L);
    }
    @Test void beforeCommitFailureRollsBackCouponAndReceipt() {
        doAnswer(i -> {
            Object value=i.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {throw new IllegalStateException("synthetic precommit failure");}
            });
            return value;
        }).when(couponService).insertSmsCoupon(any());
        assertThrows(IllegalStateException.class,this::publish); assertEquals(0,count("sms_coupon")); assertEquals(0,count("coupon_command_receipt"));
    }
    @Test void lostCommitAcknowledgementLeavesRecoverableCommittedReceipt() {
        manager.failAfterCommit=true;
        assertThrows(org.springframework.transaction.TransactionSystemException.class,this::publish);
        manager.failAfterCommit=false;
        assertEquals(1,count("sms_coupon")); assertEquals(1,count("coupon_command_receipt"));
        assertEquals("SUCCEEDED",receipt(service.read(7002L,key,11L,"MERCHANT")).get("outcome"));
        assertEquals(200,publish().get("code")); assertEquals(1,count("sms_coupon"));
    }
    @Test void changedOwnerCannotReadOrReplayOriginalBinding() {
        publish(); when(access.require(7002L,MerchantPermission.COUPON_MANAGE)).thenReturn(new MerchantAccessContext(7002L,11L,9002L,MerchantRoleCode.MERCHANT_MARKETING));
        assertEquals(404,service.read(7002L,key,11L,"MERCHANT").get("code"));
        assertEquals(409,publish().get("code")); assertEquals(1,count("sms_coupon"));
    }
    @Test void forcedReceiptUniqueRaceRollsBackLoserBeforeFreshReplay() {
        AjaxResult original=publish();
        CouponCommandReceiptMapper raced=mock(CouponCommandReceiptMapper.class);
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        when(raced.selectCurrent(anyLong(),anyLong(),anyString())).thenAnswer(i -> reads.getAndIncrement()==0 ? null : receipts.selectCurrent(i.getArgument(0),i.getArgument(1),i.getArgument(2)));
        when(raced.insertReceipt(any())).thenAnswer(i -> receipts.insertReceipt(i.getArgument(0)));
        ReflectionTestUtils.setField(service,"receiptMapper",raced);
        assertEquals(original,publish()); assertEquals(3,manager.begins);
        assertEquals(1,count("sms_coupon")); assertEquals(1,count("coupon_command_receipt"));
    }
    @Test void forcedReceiptUniqueRaceWithDifferentPayloadReturnsConflictAfterRollback() {
        publish(); CouponCommandReceiptMapper raced=mock(CouponCommandReceiptMapper.class);
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        when(raced.selectCurrent(anyLong(),anyLong(),anyString())).thenAnswer(i -> reads.getAndIncrement()==0 ? null : receipts.selectCurrent(i.getArgument(0),i.getArgument(1),i.getArgument(2)));
        when(raced.insertReceipt(any())).thenAnswer(i -> receipts.insertReceipt(i.getArgument(0)));
        ReflectionTestUtils.setField(service,"receiptMapper",raced);
        CouponPublisDTO different=request();different.setName("different");
        assertEquals(409,service.publish(7002L,key,11L,different).get("code"));assertEquals(3,manager.begins);
        assertEquals(1,count("sms_coupon"));assertEquals(1,count("coupon_command_receipt"));
    }
}

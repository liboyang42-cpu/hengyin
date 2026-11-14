package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.UserInvoiceMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.IUserInvoiceService;
import com.chengyinhub.business.service.invoice.InvoiceIssuer;
import com.chengyinhub.business.service.invoice.UserInvoiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual service proxy and mapper XML on isolated H2. Not evidence of MySQL lock behaviour. */
class UserInvoiceCommandReceiptTransactionTest {
    private PooledDataSource dataSource;
    private JdbcTemplate jdbc;
    private UserInvoiceMapper real;
    private UserInvoiceMapper boundary;
    private IUserInvoiceService service;

    @BeforeEach void setup() throws Exception {
        dataSource = new PooledDataSource("org.h2.Driver", "jdbc:h2:mem:invoice_command_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        String resource = "mapper/business/UserInvoiceMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        real = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg)).getMapper(UserInvoiceMapper.class);
        boundary = mock(UserInvoiceMapper.class, org.mockito.AdditionalAnswers.delegatesTo(real));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table user_invoice_command_receipt(member_id bigint not null, request_id varchar(64) not null,"
                + "operation varchar(32) not null, target_id bigint not null, outcome varchar(16), create_time timestamp not null,"
                + "completed_at timestamp, primary key(member_id,request_id))");
        jdbc.execute("create table user_invoice_title(id bigint primary key, member_id bigint, del_flag int, is_default int, update_time timestamp)");
        jdbc.execute("create table user_invoice_application(id bigint primary key, member_id bigint, request_id varchar(64),"
                + "application_no varchar(64),title_id bigint,title_type varchar(20),title_name varchar(100),tax_no varchar(100),"
                + "total_amount decimal(12,2),order_count int,status varchar(32),provider varchar(30),invoice_no varchar(64),"
                + "reject_reason varchar(200),issued_at timestamp,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table user_invoice_application_order(id bigint primary key,application_id bigint,member_id bigint,"
                + "order_source varchar(32),order_id bigint,amount decimal(12,2),active_key int,create_time timestamp)");
        jdbc.execute("create table cms_registration(id bigint primary key,member_id bigint,activity_title varchar(100),ticket_name varchar(100),payment_time timestamp)");
        jdbc.execute("create table user_invoice_processing(application_id bigint,reconciliation_status varchar(32))");
        jdbc.update("insert into user_invoice_title values(10,42,0,1,null)");
        jdbc.update("insert into user_invoice_application(id,member_id,request_id,status,order_count) values(20,42,'apply-original','SUBMITTED',1)");
        jdbc.update("insert into user_invoice_application_order(id,application_id,member_id,order_source,order_id,active_key) values(30,20,42,'registration',40,1)");
        UserInvoiceServiceImpl target = new UserInvoiceServiceImpl(boundary, mock(PiiCryptoService.class),
                mock(InvoiceIssuer.class), mock(IRefundService.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(IUserInvoiceService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        service = (IUserInvoiceService) proxy.getProxy();
    }

    @AfterEach void cleanup() { if (dataSource != null) { jdbc.execute("drop all objects"); dataSource.forceCloseAll(); } }
    private int receiptCount() { return jdbc.queryForObject("select count(*) from user_invoice_command_receipt", Integer.class); }
    private String appStatus() { return jdbc.queryForObject("select status from user_invoice_application where id=20", String.class); }
    private Integer activeKey() { return jdbc.queryForObject("select active_key from user_invoice_application_order where id=30", Integer.class); }
    private int titleFlag() { return jdbc.queryForObject("select del_flag from user_invoice_title where id=10", Integer.class); }

    @Test void deleteCommitsOriginalReceiptAndReplaysWithoutDeletingAgain() {
        Map<String,Object> first = service.deleteTitleWithRequest(42L,"delete-original",10L);
        assertEquals(Map.of("requestId","delete-original","operation","DELETE_TITLE","targetId","10","outcome","DELETED"),first);
        assertEquals(2,titleFlag()); assertEquals(1,receiptCount());
        assertEquals(first, service.commandReceipt(42L,"delete-original"));
        assertEquals(first, service.deleteTitleWithRequest(42L,"delete-original",10L));
        verify(boundary,times(1)).deleteTitle(42L,10L);
    }

    @Test void cancelCommitsReceiptAndReleaseButKeepsOriginalApplyIdentity() {
        Map<String,Object> first = service.cancelWithRequest(42L,"cancel-original",20L);
        assertEquals("CANCELLED",first.get("outcome")); assertEquals("20",first.get("targetId"));
        assertEquals("CANCELLED",appStatus()); assertNull(activeKey());
        assertEquals("apply-original",jdbc.queryForObject("select request_id from user_invoice_application where id=20",String.class));
        assertEquals(first,service.cancelWithRequest(42L,"cancel-original",20L));
        assertEquals(first,service.commandReceipt(42L,"cancel-original"));
        verify(boundary,times(1)).cancelApplication(eq(42L),eq(20L),any());
        verify(boundary,times(1)).releaseOrderLines(20L);
    }

    @Test void sameKeyChangedTargetOrOperationCannotOverwriteOriginalReceipt() {
        Map<String,Object> first = service.deleteTitleWithRequest(42L,"same",10L);
        assertEquals(UserInvoiceException.REQUEST_REUSED, assertThrows(UserInvoiceException.class,
                () -> service.deleteTitleWithRequest(42L,"same",11L)).getErrorCode());
        assertEquals(UserInvoiceException.REQUEST_REUSED, assertThrows(UserInvoiceException.class,
                () -> service.cancelWithRequest(42L,"same",20L)).getErrorCode());
        assertEquals(first,service.commandReceipt(42L,"same")); assertEquals("SUBMITTED",appStatus()); assertEquals(1,receiptCount());
    }

    @Test void foreignOwnerCannotReadReceiptOrMutateEitherTarget() {
        service.deleteTitleWithRequest(42L,"owner-only",10L);
        assertEquals(UserInvoiceException.NOT_FOUND,assertThrows(UserInvoiceException.class,
                () -> service.commandReceipt(99L,"owner-only")).getErrorCode());
        assertThrows(UserInvoiceException.class,() -> service.deleteTitleWithRequest(99L,"foreign-delete",10L));
        assertThrows(UserInvoiceException.class,() -> service.cancelWithRequest(99L,"foreign-cancel",20L));
        assertEquals(1,receiptCount()); assertEquals("SUBMITTED",appStatus()); assertEquals(Integer.valueOf(1),activeKey());
    }

    @Test void failureAfterReceiptFinalizationRollsBackDeletionAndReceipt() {
        doAnswer(call -> {
            assertEquals(1,real.finishCommandReceipt(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4)));
            throw new IllegalStateException("injected after receipt SQL");
        }).when(boundary).finishCommandReceipt(anyLong(),anyString(),anyString(),anyLong(),anyString());
        assertThrows(IllegalStateException.class,() -> service.deleteTitleWithRequest(42L,"rollback-delete",10L));
        assertEquals(0,titleFlag()); assertEquals(0,receiptCount());
    }

    @Test void receiptCasFailureRollsBackCancellationAndOrderRelease() {
        doReturn(0).when(boundary).finishCommandReceipt(anyLong(),anyString(),anyString(),anyLong(),anyString());
        assertThrows(UserInvoiceException.class,() -> service.cancelWithRequest(42L,"rollback-cancel",20L));
        verify(boundary).releaseOrderLines(20L);
        assertEquals("SUBMITTED",appStatus()); assertEquals(Integer.valueOf(1),activeKey()); assertEquals(0,receiptCount());
    }

    @Test void failureAfterOrderReleaseRollsBackOriginalApplicationAndReservation() {
        doAnswer(call -> { real.releaseOrderLines(20L); throw new IllegalStateException("injected after release SQL"); })
                .when(boundary).releaseOrderLines(20L);
        assertThrows(IllegalStateException.class,() -> service.cancelWithRequest(42L,"rollback-release",20L));
        assertEquals("SUBMITTED",appStatus()); assertEquals(Integer.valueOf(1),activeKey()); assertEquals(0,receiptCount());
    }

    @Test void invalidInputAndMissingReceiptNeverMutateState() {
        assertThrows(UserInvoiceException.class,() -> service.deleteTitleWithRequest(42L,"bad key",10L));
        assertThrows(UserInvoiceException.class,() -> service.cancelWithRequest(42L,"valid",0L));
        assertThrows(UserInvoiceException.class,() -> service.commandReceipt(0L,"valid"));
        assertEquals(UserInvoiceException.NOT_FOUND,assertThrows(UserInvoiceException.class,
                () -> service.commandReceipt(42L,"unknown")).getErrorCode());
        verify(boundary).selectCommandReceipt(42L,"unknown");
        verifyNoMoreInteractions(boundary);
        assertEquals(0,receiptCount()); assertEquals(0,titleFlag()); assertEquals("SUBMITTED",appStatus());
    }

    @Test void issuedApplicationCannotCancelOrLeaveAReceiptReservation() {
        jdbc.update("update user_invoice_application set status='ISSUED' where id=20");
        assertThrows(UserInvoiceException.class,() -> service.cancelWithRequest(42L,"issued-cancel",20L));
        assertEquals("ISSUED",appStatus()); assertEquals(Integer.valueOf(1),activeKey()); assertEquals(0,receiptCount());
    }

    @Test void concurrentSameCommandReturnsOneDurableReceipt() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Map<String,Object>> action = () -> {
                start.await(); return service.deleteTitleWithRequest(42L,"concurrent",10L);
            };
            var one = pool.submit(action); var two = pool.submit(action); start.countDown();
            assertEquals(one.get(10,java.util.concurrent.TimeUnit.SECONDS),two.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,receiptCount()); assertEquals(2,titleFlag());
            verify(boundary,times(1)).deleteTitle(42L,10L);
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)); }
    }

    @Test void completedReceiptCannotBeRewrittenAndFreshServiceCanRecoverIt() {
        Map<String,Object> first = service.deleteTitleWithRequest(42L,"immutable",10L);
        assertEquals(0,real.finishCommandReceipt(42L,"immutable","DELETE_TITLE",10L,"DELETED"));
        assertEquals(0,real.finishCommandReceipt(99L,"immutable","DELETE_TITLE",10L,"DELETED"));
        UserInvoiceServiceImpl restarted = new UserInvoiceServiceImpl(real,mock(PiiCryptoService.class),
                mock(InvoiceIssuer.class),mock(IRefundService.class));
        assertEquals(first,restarted.commandReceipt(42L,"immutable"));
    }
}

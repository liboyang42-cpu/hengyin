package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** NOT_RUN. H2 with production Mapper XML verifies the transaction boundary, not MySQL concurrency. */
class E04_09Test {
    private JdbcTemplate jdbc;
    private ISmsCouponHistoryService service;
    private SmsCouponHistoryServiceImpl target;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:e04_09;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE sms_coupon(id BIGINT PRIMARY KEY,status INT,receive_count BIGINT,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE sms_coupon_history(id BIGINT AUTO_INCREMENT PRIMARY KEY,coupon_id BIGINT,member_id BIGINT,"
                + "coupon_code VARCHAR(100),use_status INT,create_time TIMESTAMP)");
        jdbc.update("INSERT INTO sms_coupon(id,status,receive_count) VALUES (30,4,0)");

        Configuration config = new Configuration(new Environment("e04-09",
                new SpringManagedTransactionFactory(),ds));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(config,SmsCouponMapper.class,"mapper/business/SmsCouponMapper.xml");
        addMapper(config,SmsCouponHistoryMapper.class,"mapper/business/SmsCouponHistoryMapper.xml");
        SqlSessionTemplate template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        target = new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(target,"smsCouponMapper",template.getMapper(SmsCouponMapper.class));
        ReflectionTestUtils.setField(target,"smsCouponHistoryMapper",template.getMapper(SmsCouponHistoryMapper.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(ISmsCouponHistoryService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
                new AnnotationTransactionAttributeSource()));
        service = (ISmsCouponHistoryService) proxy.getProxy();
    }

    @Test
    void statisticalUpdateConstraintFailureRollsBackPreviouslyInsertedAdministrativeHistory() {
        jdbc.execute("ALTER TABLE sms_coupon ADD CONSTRAINT reject_stat_increment CHECK (receive_count=0)");
        SmsCouponMapper real = (SmsCouponMapper)ReflectionTestUtils.getField(target,"smsCouponMapper");
        java.util.concurrent.atomic.AtomicInteger statisticAttempts = new java.util.concurrent.atomic.AtomicInteger();
        SmsCouponMapper observed = (SmsCouponMapper)java.lang.reflect.Proxy.newProxyInstance(
                SmsCouponMapper.class.getClassLoader(),new Class<?>[]{SmsCouponMapper.class},(proxy,method,args) -> {
                    if ("incrementReceiveCount".equals(method.getName())) {
                        // Observe our own preceding write inside the same transaction before the real failing UPDATE.
                        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sms_coupon_history",Integer.class));
                        statisticAttempts.incrementAndGet();
                    }
                    try {
                        return method.invoke(real,args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        ReflectionTestUtils.setField(target,"smsCouponMapper",observed);
        RuntimeException failure = assertThrows(RuntimeException.class,() -> service.insertSmsCouponHistory(history()));
        assertEquals(1,statisticAttempts.get());
        org.junit.jupiter.api.Assertions.assertTrue(hasSqlState(failure,"23513"),"Must fail on the real H2 CHECK constraint");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sms_coupon_history",Integer.class));
        assertEquals(0L,jdbc.queryForObject("SELECT receive_count FROM sms_coupon WHERE id=30",Long.class));
        assertEquals(4,jdbc.queryForObject("SELECT status FROM sms_coupon WHERE id=30",Integer.class));
    }

    @Test
    void administrativeInsertStillCommitsHistoryAndOriginalStatisticForStoppedTemplate() {
        SmsCouponHistory row = history();
        assertEquals(1,service.insertSmsCouponHistory(row));
        assertNotNull(row.getId());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sms_coupon_history WHERE id=? AND coupon_id=30 AND member_id=7001",Integer.class,row.getId()));
        assertEquals(1L,jdbc.queryForObject("SELECT receive_count FROM sms_coupon WHERE id=30",Long.class));
        assertEquals(4,jdbc.queryForObject("SELECT status FROM sms_coupon WHERE id=30",Integer.class));
    }

    private boolean hasSqlState(Throwable failure,String state) {
        for (Throwable current=failure;current!=null;current=current.getCause()) {
            if (current instanceof java.sql.SQLException && state.equals(((java.sql.SQLException)current).getSQLState())) return true;
        }
        return false;
    }

    private SmsCouponHistory history() {
        SmsCouponHistory row = new SmsCouponHistory();
        row.setCouponId(30L);
        row.setMemberId(7001L);
        row.setCouponCode("e04-administrative-history");
        row.setUseStatus(0);
        return row;
    }

    private void addMapper(Configuration config,Class<?> type,String resource) throws Exception {
        config.addMapper(type);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in,resource);
            new XMLMapperBuilder(in,config,resource,config.getSqlFragments()).parse();
        }
    }
}

package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.MerchantDashboardIncome;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberBalanceDetailServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 MySQL + 真实 mapper：聚合必须与原明细口径一致；默认 *Test 发现，无跳过门控。 */
class MerchantDashboardAggregationMyBatisTest {
    private static final String SUITE = "merchant_dashboard_aggregation";
    private static DriverManagerDataSource source;
    private static DataSourceTransactionManager transactionManager;
    private static JdbcTemplate db;
    private static SqlSessionTemplate sql;
    private static String database;

    @BeforeAll
    static void setup() throws Exception {
        assertEquals("127.0.0.1", MybatisItDatabase.host(), "只允许隔离的 loopback 测试库");
        source = new DriverManagerDataSource(MybatisItDatabase.url(SUITE),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        database = MybatisItDatabase.databaseName(SUITE);
        db = new JdbcTemplate(source);
        assertTrue(db.queryForObject("select version()", String.class).startsWith("8.0."));
        String schema = Files.readString(Path.of("..", "sql", "base_schema_20260818.sql"));
        for (String table : Arrays.asList("ums_member_balance_detail", "cms_registration", "cms_topic", "cms_activity")) {
            String head = "CREATE TABLE `" + table + "`";
            String ddl = ItSchema.splitStatements(schema.substring(schema.indexOf(head))).get(0);
            assertTrue(ddl.startsWith(head));
            db.execute(ddl);
        }
        try (Connection c = source.getConnection()) {
            ItSchema.applyMigrations(c, "sql/migration_20260930_registration_first_successful_scan.sql");
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/UmsMemberBalanceDetailMapper.xml"),
                new ClassPathResource("mapper/business/CmsRegistrationMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicMapper.xml"));
        sql = new SqlSessionTemplate(factory.getObject());
        transactionManager = new DataSourceTransactionManager(source);
    }

    @AfterAll
    static void cleanup() {
        if (database == null) return;
        assertTrue(database.matches("chengyin_mybatis_merchant_dashboard_aggregation[a-z0-9_]*"));
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:"
                + MybatisItDatabase.port() + "/?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password()));
        admin.execute("DROP DATABASE `" + database + "`");
        assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?",
                Integer.class, database));
    }

    @BeforeEach
    void reset() {
        db.execute("delete from ums_member_balance_detail");
        db.execute("delete from cms_registration");
        db.execute("delete from cms_topic");
        db.execute("delete from cms_activity");
    }

    @Test
    void incomeAggregationPreservesOwnerTypeDeletedRowsAndCalendarBoundary() {
        db.update("insert into ums_member_balance_detail(member_id,change_type,change_balance,del_flag,create_time)"
                + " values(11,1,20,0,'2026-10-17 23:59:59'),(11,1,10,0,'2026-10-18 00:00:00'),"
                + "(11,1,3.25,0,'2026-10-24 00:00:00'),(11,1,2,2,'2026-10-24 23:59:59'),"
                + "(11,1,-1,0,'2026-10-24 11:00:00'),(11,1,null,0,'2026-10-24 12:00:00'),"
                + "(11,2,100,0,'2026-10-24 12:00:00'),(12,1,999,0,'2026-10-24 12:00:00'),"
                + "(11,1,4,0,null)");
        UmsMemberBalanceDetailMapper mapper = sql.getMapper(UmsMemberBalanceDetailMapper.class);
        IUmsMemberBalanceDetailService service = incomeService(mapper);
        UmsMemberBalanceDetail query = new UmsMemberBalanceDetail();
        query.setMemberId(11L);
        query.setChangeType(1L);
        BigDecimal oldTotal = mapper.selectUmsMemberBalanceDetailList(query).stream()
                .filter(row -> row.getChangeBalance() != null)
                .map(UmsMemberBalanceDetail::getChangeBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("38.25").compareTo(oldTotal));
        Date since = Date.from(LocalDateTime.parse("2026-10-18T00:00:00")
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        MerchantDashboardIncome income = service.readDashboardIncome(11L, since);
        assertEquals(0, oldTotal.compareTo(income.getTotal()),
                "不因聚合额外排除 del_flag=2、负收入或无日期流水");
        Map<String, BigDecimal> byDay = income.getByDay();
        assertEquals(java.util.Set.of("2026-10-18", "2026-10-24"), byDay.keySet());
        assertEquals(0, new BigDecimal("10.00").compareTo(byDay.get("2026-10-18")));
        assertEquals(0, new BigDecimal("4.25").compareTo(byDay.get("2026-10-24")));
        MerchantDashboardIncome empty = service.readDashboardIncome(13L, since);
        assertEquals(0, BigDecimal.ZERO.compareTo(empty.getTotal()));
        assertTrue(empty.getByDay().isEmpty());
    }

    @Test
    void groupedVerificationCountsMatchOldListsAndKeepOwnerTypeAndPaymentScope() {
        registration(1, 1, 7, 2, 0, 2);
        registration(2, 1, 7, 2, 0, 3); // 原列表没有报名状态条件，聚合不擅自新增。
        registration(3, 1, 7, 2, 1, 2);
        registration(4, 1, 8, 2, 1, 2);
        registration(5, 1, 8, 2, 1, 2);
        registration(6, 1, 9, 2, 0, 2); // 不属于请求项目。
        registration(7, 2, 7, 2, 0, 2); // 同 id 的活动不是主题。
        registration(8, 1, 7, 0, 0, 1); // 未支付。
        registration(9, 1, 7, 2, null, 2);
        CmsRegistrationMapper mapper = sql.getMapper(CmsRegistrationMapper.class);
        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", mapper);
        Map<Long, long[]> counts = service.countPaidVerificationByOwners(1L, Arrays.asList(7L, 8L));
        assertEquals(java.util.Set.of(7L, 8L), counts.keySet());
        assertArrayEquals(new long[] {2, 1}, counts.get(7L));
        assertArrayEquals(new long[] {0, 2}, counts.get(8L));
        for (Long owner : Arrays.asList(7L, 8L)) {
            for (int status : new int[] {0, 1}) {
                CmsRegistration oldQuery = new CmsRegistration();
                oldQuery.setOwnerType(1L);
                oldQuery.setOwnerId(owner);
                oldQuery.setPaymentStatus(2);
                oldQuery.setVerificationStatus(status);
                assertEquals(mapper.selectCmsRegistrationList(oldQuery).size(), counts.get(owner)[status]);
            }
        }
        assertArrayEquals(new long[] {1, 0},
                service.countPaidVerificationByOwners(2L, Collections.singletonList(7L)).get(7L));
        assertTrue(service.countPaidVerificationByOwners(1L, Collections.emptyList()).isEmpty());
    }

    /** W2 复用本类真实MySQL/schema fixture，只测新增窄投影，不执行订单或资金写侧。 */
    @Test
    void todoBatchProjectionsKeepMissingDeletedNullAndOwnerTypeSemantics() {
        db.update("insert into cms_topic(id,end_date,del_flag) values(7,'2026-10-01 12:00:00',2),(8,null,0)");
        db.update("insert into cms_activity(id,end_date,del_flag) values(7,'2026-10-20 12:00:00',2)");
        registration(101, 1, 7, 0, 0, 3); // 新投影不得加支付/报名状态过滤。
        registration(102, 2, 7, 2, 0, 2); // 同id活动读自己的日期，不读主题日期。
        registration(103, 1, 8, 2, 0, 2); // null结束时间。
        registration(104, 1, 999, 2, 0, 2); // 项目已不存在仍保留报名归属。
        registration(105, 3, 7, 2, 0, 2); // 未知ownerType保持日期null。
        registration(106, 1, 7, 2, 0, 2); // 请求ID集之外的报名。
        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", sql.getMapper(CmsRegistrationMapper.class));
        Map<Long, com.chengyinhub.business.domain.vo.MerchantTodoRegistrationVO> owners =
                service.selectTodoRegistrationOwners(Arrays.asList(101L, 101L, 102L, 103L, 104L, 105L, 999L));
        assertEquals(java.util.Set.of(101L, 102L, 103L, 104L, 105L), owners.keySet());
        assertEquals(db.queryForObject("select end_date from cms_topic where id=7", Date.class).getTime(), owners.get(101L).getEndDate().getTime());
        assertEquals(db.queryForObject("select end_date from cms_activity where id=7", Date.class).getTime(), owners.get(102L).getEndDate().getTime());
        assertEquals(2L, owners.get(102L).getOwnerType());
        assertNull(owners.get(103L).getEndDate()); assertNull(owners.get(104L).getEndDate());
        assertEquals(999L, owners.get(104L).getOwnerId()); assertNull(owners.get(105L).getEndDate());
        com.chengyinhub.business.mapper.CmsTopicMapper topicMapper = sql.getMapper(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        com.chengyinhub.business.service.impl.CmsTopicServiceImpl topics = new com.chengyinhub.business.service.impl.CmsTopicServiceImpl();
        ReflectionTestUtils.setField(topics, "cmsTopicMapper", topicMapper);
        Map<Long, Date> ends = topics.selectTodoTopicEndDates(Arrays.asList(7L, 8L, 999L));
        assertEquals(java.util.Set.of(7L, 8L), ends.keySet()); assertNull(ends.get(8L));
        assertEquals(ends.get(7L), owners.get(101L).getEndDate());
        // 直接打mapper也必须fail-closed，不能把空IN退成全表。
        assertTrue(topicMapper.selectTodoTopicEndDates(Collections.emptySet()).isEmpty());
        assertTrue(topicMapper.selectTodoTopicEndDates(null).isEmpty());
        assertTrue(sql.getMapper(CmsRegistrationMapper.class).selectTodoRegistrationOwners(Collections.emptySet()).isEmpty());
        assertTrue(sql.getMapper(CmsRegistrationMapper.class).selectTodoRegistrationOwners(null).isEmpty());
    }

    @Test
    void concurrentIncomeCommitCannotMixTotalAndDailySnapshots() throws Exception {
        MerchantDashboardIncome income = raceIncomeRead(true);
        assertEquals(0, income.getTotal().compareTo(BigDecimal.ZERO));
        assertTrue(income.getByDay().isEmpty(), "两次读取之间提交的新收入不能只出现在每日收入");

        MerchantDashboardIncome next = incomeService(sql.getMapper(UmsMemberBalanceDetailMapper.class))
                .readDashboardIncome(11L, new Date(0));
        assertEquals(0, next.getTotal().compareTo(new BigDecimal("10")));
        assertEquals(0, next.getByDay().get("2026-10-24").compareTo(new BigDecimal("10")),
                "下一次服务调用必须看到已经提交的收入，不能靠固定结果通过");
    }

    @Test
    void unproxiedServiceIsANegativeControlForTheMixedSnapshotRace() throws Exception {
        MerchantDashboardIncome income = raceIncomeRead(false);
        assertEquals(0, income.getTotal().compareTo(BigDecimal.ZERO));
        assertEquals(0, income.getByDay().get("2026-10-24").compareTo(new BigDecimal("10")),
                "去掉事务代理后，真实并发写入必须复现总收入 0 / 今日收入 10");
    }

    @Test
    void readCommittedCallerCannotWeakenTheDashboardSnapshot() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        MerchantDashboardIncome income = outer.execute(status -> assertDoesNotThrow(() -> raceIncomeRead(true)));
        assertNotNull(income);
        assertEquals(0, income.getTotal().compareTo(BigDecimal.ZERO));
        assertTrue(income.getByDay().isEmpty(), "服务必须建立自己的 RR 只读事务，而不是加入外层 RC");
    }

    private static IUmsMemberBalanceDetailService incomeService(UmsMemberBalanceDetailMapper mapper) {
        UmsMemberBalanceDetailServiceImpl target = incomeTarget(mapper);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (IUmsMemberBalanceDetailService) proxy.getProxy();
    }

    private static UmsMemberBalanceDetailServiceImpl incomeTarget(UmsMemberBalanceDetailMapper mapper) {
        UmsMemberBalanceDetailServiceImpl target = new UmsMemberBalanceDetailServiceImpl();
        ReflectionTestUtils.setField(target, "umsMemberBalanceDetailMapper", mapper);
        return target;
    }

    /** 两条 SQL 均委托真实 mapper；只在第一条查完后用 latch 安排另一连接完成并提交插入。 */
    private MerchantDashboardIncome raceIncomeRead(boolean transactional) throws Exception {
        db.update("insert into ums_member_balance_detail(member_id,change_type,change_balance,create_time)"
                + " values(11,1,0,null)"); // 确保首查读取真实 InnoDB 行并建立快照。
        CountDownLatch totalRead = new CountDownLatch(1);
        CountDownLatch incomeCommitted = new CountDownLatch(1);
        AtomicInteger queries = new AtomicInteger();
        AtomicReference<Connection> connection = new AtomicReference<>();
        UmsMemberBalanceDetailMapper real = sql.getMapper(UmsMemberBalanceDetailMapper.class);
        UmsMemberBalanceDetailMapper coordinated = (UmsMemberBalanceDetailMapper) Proxy.newProxyInstance(
                UmsMemberBalanceDetailMapper.class.getClassLoader(),
                new Class<?>[] {UmsMemberBalanceDetailMapper.class}, (proxy, method, args) -> {
                    boolean aggregate = method.getName().equals("selectDashboardIncomeTotal")
                            || method.getName().equals("selectDashboardIncomeDaily");
                    if (aggregate) {
                        queries.incrementAndGet();
                        if (transactional) {
                            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                            assertEquals(Connection.TRANSACTION_REPEATABLE_READ,
                                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                            Connection current = DataSourceUtils.getConnection(source);
                            try {
                                assertEquals(Connection.TRANSACTION_REPEATABLE_READ, current.getTransactionIsolation());
                                assertTrue(current.isReadOnly());
                                assertFalse(current.getAutoCommit());
                                if (!connection.compareAndSet(null, current)) assertSame(connection.get(), current,
                                        "两个聚合必须使用同一个事务绑定的 JDBC 连接");
                            } finally {
                                DataSourceUtils.releaseConnection(current, source);
                            }
                        } else {
                            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                        }
                    }
                    Object value;
                    try {
                        value = method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (method.getName().equals("selectDashboardIncomeTotal")) {
                        totalRead.countDown();
                        assertTrue(incomeCommitted.await(10, TimeUnit.SECONDS), "并发收入未能在两条查询之间提交");
                    }
                    return value;
                });
        ExecutorService writer = Executors.newSingleThreadExecutor();
        Future<Integer> committed = writer.submit(() -> {
            try {
                assertTrue(totalRead.await(10, TimeUnit.SECONDS), "未观察到首条真实聚合查询");
                db.update("insert into ums_member_balance_detail(member_id,change_type,change_balance,create_time)"
                        + " values(11,1,10,'2026-10-24 12:00:00')");
                return db.queryForObject("select count(*) from ums_member_balance_detail"
                        + " where member_id=11 and change_balance=10", Integer.class);
            } finally {
                incomeCommitted.countDown();
            }
        });
        try {
            IUmsMemberBalanceDetailService service = transactional ? incomeService(coordinated) : incomeTarget(coordinated);
            MerchantDashboardIncome result = service.readDashboardIncome(11L, new Date(0));
            assertEquals(1, committed.get(10, TimeUnit.SECONDS), "另一连接必须已经提交真实收入");
            assertEquals(2, queries.get());
            return result;
        } finally {
            totalRead.countDown();
            incomeCommitted.countDown();
            writer.shutdownNow();
            assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS), "并发写线程未结束");
        }
    }

    private void registration(long id, long type, long owner, int payment, Integer verification, int status) {
        db.update("insert into cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,"
                + "member_id,original_amount,total_amount,payable_amount,payment_status,registration_status,"
                + "pay_expire_time,verification_status) values(?,?,?,?,1,1,41,1,1,1,?,?,now()+interval 1 day,?)",
                id, "dashboard-aggregation-" + id, type, owner, payment, status, verification);
    }
}

package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CouponSourceGrantMapper;
import com.chengyinhub.business.mapper.RewardDropLogMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DOC2 O-06 part 1 · 券库存守恒(真 MySQL 8 + 真 mapper + 真 @Transactional 代理)。
 *
 * <p>N 个并发领取打在库存 K 的限量券上,恰好 K 个成功:receive_count == K、领取记录 == K,
 * 失败的一方不留领取记录也不占库存。覆盖三条入口:
 * <ul>
 *   <li>grantCouponById —— 玩法/活动结算/扫码核销等全部发券调用方的公共入口;</li>
 *   <li>grantCouponBySource —— 奖励掉落按来源(业务键)发券,同来源重放不再占库存;</li>
 *   <li>insertSmsCouponHistory —— 后台手工补录(本次修复的旁路)。</li>
 * </ul>
 * 负控:旧的「无条件 +1」语句在同样的并发下真会越过 publish_count,证明本测试能看见超发。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponStockConservationMySqlIT {

    private static final int CONTENDERS = 20;
    private static final long STOCK = 5;
    private static final long COUPON = 30L;

    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private SmsCouponMapper couponMapper;
    private ISmsCouponHistoryService service;

    @BeforeAll
    void setup() throws Exception {
        assertTrue("127.0.0.1".equals(MybatisItDatabase.host()) || "localhost".equals(MybatisItDatabase.host()),
                "Only localhost scratch MySQL");
        String url = MybatisItDatabase.url("coupon_stock_o06");
        database = MybatisItDatabase.databaseName("coupon_stock_o06");
        admin = new JdbcTemplate(new DriverManagerDataSource(url.replace("/" + database + "?", "/mysql?"),
                MybatisItDatabase.user(), MybatisItDatabase.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("SELECT VERSION()", String.class)));
        DriverManagerDataSource source = new DriverManagerDataSource(url, MybatisItDatabase.user(), MybatisItDatabase.password());
        jdbc = new JdbcTemplate(source);

        Configuration configuration = new Configuration(new Environment("coupon-stock-o06",
                new SpringManagedTransactionFactory(), source));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(configuration, CouponSourceGrantMapper.class, "mapper/business/CouponSourceGrantMapper.xml");
        addMapper(configuration, RewardDropLogMapper.class, "mapper/business/RewardDropLogMapper.xml");
        addMapper(configuration, SmsCouponMapper.class, "mapper/business/SmsCouponMapper.xml");
        addMapper(configuration, SmsCouponHistoryMapper.class, "mapper/business/SmsCouponHistoryMapper.xml");
        addMapper(configuration, BusinessAuditEventMapper.class, "mapper/business/BusinessAuditEventMapper.xml");
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));

        couponMapper = session.getMapper(SmsCouponMapper.class);
        SmsCouponHistoryServiceImpl target = new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(target, "smsCouponHistoryMapper", session.getMapper(SmsCouponHistoryMapper.class));
        ReflectionTestUtils.setField(target, "smsCouponMapper", couponMapper);
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", session.getMapper(BusinessAuditEventMapper.class));
        ReflectionTestUtils.setField(target, "couponSourceGrantMapper", session.getMapper(CouponSourceGrantMapper.class));
        ReflectionTestUtils.setField(target, "rewardDropLogMapper", session.getMapper(RewardDropLogMapper.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(ISmsCouponHistoryService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (ISmsCouponHistoryService) proxy.getProxy();
    }

    @BeforeEach
    void resetSchema() {
        for (String table : new String[]{"coupon_source_grant", "reward_drop_log", "business_audit_event",
                "sms_coupon_history", "sms_coupon"}) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("CREATE TABLE coupon_source_grant (source_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                + " source_id BIGINT NOT NULL, member_id BIGINT NOT NULL, coupon_id BIGINT NOT NULL, history_id BIGINT NULL,"
                + " created_at DATETIME NOT NULL, delivered_at DATETIME NULL, PRIMARY KEY(source_type,source_id),"
                + " UNIQUE KEY uk_coupon_source_history(history_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE reward_drop_log (id BIGINT PRIMARY KEY, member_id BIGINT, topic_id BIGINT, node_id BIGINT,"
                + " activity_id BIGINT, scene_key VARCHAR(64), drop_type INT, param_json TEXT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE business_audit_event (id BIGINT PRIMARY KEY AUTO_INCREMENT, domain_type VARCHAR(32) NOT NULL,"
                + " domain_id BIGINT NOT NULL, owner_member_id BIGINT NOT NULL, actor_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',"
                + " actor_member_id BIGINT NOT NULL, actor_role_code VARCHAR(64) NOT NULL, action VARCHAR(64) NOT NULL,"
                + " target_type VARCHAR(64), target_id BIGINT, relation_id BIGINT, before_json TEXT, after_json TEXT,"
                + " reason VARCHAR(500), request_id VARCHAR(64), create_time DATETIME(3) NOT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE sms_coupon (id BIGINT PRIMARY KEY, name VARCHAR(100), coupon_type INT, platform INT,"
                + " amount DECIMAL(12,2), min_point DECIMAL(12,2), start_time DATETIME(3), end_time DATETIME(3), use_type INT,"
                + " publish_count BIGINT, use_count BIGINT, receive_count BIGINT, enable_time DATETIME(3), code VARCHAR(100),"
                + " scope_type INT, member_id BIGINT, merchant_id BIGINT, status INT, del_flag INT, description VARCHAR(500),"
                + " create_by VARCHAR(64), create_time DATETIME(3), update_by VARCHAR(64), update_time DATETIME(3)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE sms_coupon_history (id BIGINT PRIMARY KEY AUTO_INCREMENT, coupon_id BIGINT, member_id BIGINT,"
                + " coupon_code VARCHAR(100), qrcode_url VARCHAR(500), get_type INT, get_time DATETIME(3), use_status INT,"
                + " use_time DATETIME(3), order_id BIGINT, order_sn VARCHAR(100), start_time DATETIME(3), end_time DATETIME(3),"
                + " note VARCHAR(500), del_flag INT, create_by VARCHAR(64), create_time DATETIME(3), update_by VARCHAR(64),"
                + " update_time DATETIME(3)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO sms_coupon(id,name,amount,publish_count,receive_count,use_count,member_id,scope_type,merchant_id,status,del_flag)"
                + " VALUES (?,'限量券',10,?,0,0,99,1,77,1,0)", COUPON, STOCK);
    }

    @AfterAll
    void cleanup() {
        if (database != null) {
            admin.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void concurrentGrantsByIdOnStockKSucceedExactlyKTimes() throws Exception {
        List<Boolean> results = race(i -> service.grantCouponById(7000L + i, COUPON));

        assertEquals(STOCK, results.stream().filter(Boolean::booleanValue).count());
        assertConserved(STOCK);
        // 售罄后再领仍被拒,库存不回弹
        assertEquals(false, service.grantCouponById(8000L, COUPON));
        assertConserved(STOCK);
    }

    @Test
    void concurrentDistinctSourcesOnStockKDeliverExactlyKAndReplaysConsumeNothing() throws Exception {
        for (int i = 0; i < CONTENDERS; i++) {
            long dropId = 500L + i;
            jdbc.update("INSERT INTO reward_drop_log(id,member_id,drop_type,param_json) VALUES (?,?,3,?)",
                    dropId, 7000L + i, "{\"couponTemplateId\":" + COUPON + "}");
            jdbc.update("INSERT INTO coupon_source_grant(source_type,source_id,member_id,coupon_id,created_at)"
                    + " VALUES ('reward_drop',?,?,?,NOW())", dropId, 7000L + i, COUPON);
        }

        List<Long> results = race(i -> service.grantCouponBySource(7000L + i, COUPON, "reward_drop", 500L + i));

        assertEquals(STOCK, results.stream().filter(id -> id != null).count());
        assertConserved(STOCK);
        assertEquals(STOCK, count("SELECT COUNT(*) FROM coupon_source_grant WHERE history_id IS NOT NULL"));
        // 同一业务键(来源)重放:已交付的返回原记录、不再占库存;未交付的仍因售罄拒发
        for (int i = 0; i < CONTENDERS; i++) {
            Long replay = service.grantCouponBySource(7000L + i, COUPON, "reward_drop", 500L + i);
            assertEquals(results.get(i), replay, "source " + (500 + i));
        }
        assertConserved(STOCK);
    }

    @Test
    void concurrentManualInsertsOnStockKSucceedExactlyKTimes() throws Exception {
        List<Boolean> results = race(i -> {
            SmsCouponHistory h = new SmsCouponHistory();
            h.setCouponId(COUPON);
            h.setMemberId(7000L + i);
            h.setCouponCode("manual-" + i);
            h.setUseStatus(0);
            h.setDelFlag(0);
            try {
                return service.insertSmsCouponHistory(h) == 1;
            } catch (RuntimeException rejected) {
                return false;
            }
        });

        assertEquals(STOCK, results.stream().filter(Boolean::booleanValue).count());
        assertConserved(STOCK);
    }

    @Test
    void negativeControlUnconditionalIncrementOversellsUnderTheSameRace() throws Exception {
        // 修复前补录走的就是这条:不看 publish_count。同样 N 个并发真会把计数推过 K。
        race(i -> couponMapper.incrementReceiveCount(COUPON));
        assertEquals(CONTENDERS, count("SELECT receive_count FROM sms_coupon WHERE id=" + COUPON));
        assertTrue(CONTENDERS > STOCK);
    }

    private void assertConserved(long expected) {
        assertEquals(expected, count("SELECT receive_count FROM sms_coupon WHERE id=" + COUPON));
        assertEquals(expected, count("SELECT COUNT(*) FROM sms_coupon_history WHERE coupon_id=" + COUPON));
        assertTrue(count("SELECT receive_count FROM sms_coupon WHERE id=" + COUPON)
                <= count("SELECT publish_count FROM sms_coupon WHERE id=" + COUPON));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private interface Contender<T> {
        T run(int index) throws Exception;
    }

    private <T> List<T> race(Contender<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(CONTENDERS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < CONTENDERS; i++) {
                final int index = i;
                Callable<T> contender = () -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return action.run(index);
                };
                futures.add(pool.submit(contender));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    private static void addMapper(Configuration configuration, Class<?> mapper, String resource) throws Exception {
        configuration.addMapper(mapper);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到 MyBatis XML: " + resource);
            }
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }
}

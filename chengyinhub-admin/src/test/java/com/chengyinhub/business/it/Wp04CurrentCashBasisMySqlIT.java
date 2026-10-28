package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.TopicSettlementCashBasis;
import com.chengyinhub.business.mapper.CoopSettlementEvidenceMapper;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** 仅由获批 CI/VPS 执行；RR/当前读/缓存反例，不代表付款或退款服务完整闭环。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Wp04CurrentCashBasisMySqlIT {
    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private CoopSettlementEvidenceMapper mapper;
    private String createdDatabase;

    @BeforeAll void schema() throws Exception {
        for (String name : List.of("HOST","PORT","USER","PASSWORD","DB_SUFFIX")) {
            assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_"+name),"必须显式配置测试连接项："+name);
        }
        String host = System.getenv("CHENGYIN_MYBATIS_IT_HOST");
        assertTrue("127.0.0.1".equals(host)||"localhost".equals(host));
        int port = Integer.parseInt(System.getenv("CHENGYIN_MYBATIS_IT_PORT"));
        assertTrue(port>0&&port<=65535); assertFalse(System.getenv("CHENGYIN_MYBATIS_IT_USER").isBlank());
        assertTrue(System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX").matches("[A-Za-z0-9_]*"));
        String suite = "g_wp04_cash_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        assertTrue(MybatisItDatabase.databaseName(suite).length()<=64);
        String url = MybatisItDatabase.url(suite);
        createdDatabase = MybatisItDatabase.databaseName(suite);
        source = new DriverManagerDataSource(url,MybatisItDatabase.user(),MybatisItDatabase.password());
        db = new JdbcTemplate(source); assertTrue(db.queryForObject("select version()",String.class).startsWith("8."));
        Path baseline = Path.of("sql/base_schema_20260818.sql");
        if (!Files.isRegularFile(baseline)) baseline = Path.of("../sql/base_schema_20260818.sql");
        String ddl = Files.readString(baseline);
        for (String table : List.of("cms_registration","cms_activity","refund_application")) {
            Matcher match = Pattern.compile("(?s)CREATE TABLE `"+table+"` \\(.*?\\) ENGINE=.*?;").matcher(ddl);
            assertTrue(match.find(),table); db.execute(match.group());
        }
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); tx.setTimeout(10);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/CoopSettlementEvidenceMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(CoopSettlementEvidenceMapper.class);
    }
    @AfterAll void dropOnlyRecordedUuidSchema() throws Exception {
        if (createdDatabase==null) return;
        assertTrue(createdDatabase.matches("chengyin_mybatis_g_wp04_cash_[a-f0-9]{12}[A-Za-z0-9_]*"));
        try (Connection connection=source.getConnection(); Statement statement=connection.createStatement()) {
            assertEquals(createdDatabase,connection.getCatalog()); statement.execute("DROP DATABASE `"+createdDatabase+"`");
            try (PreparedStatement check=connection.prepareStatement("select count(*) from information_schema.schemata where schema_name=?")) {
                check.setString(1,createdDatabase);
                try (ResultSet rows=check.executeQuery()) { assertTrue(rows.next()); assertEquals(0,rows.getInt(1)); }
            }
        }
        createdDatabase=null; // DriverManagerDataSource 无连接池；每次连接均已关闭。
    }
    @BeforeEach void seed() {
        db.update("DELETE FROM refund_application"); db.update("DELETE FROM cms_registration"); db.update("DELETE FROM cms_activity");
        db.update("INSERT INTO cms_activity(id,topic_id,del_flag) VALUES(201,11,0),(11,99,0),(202,11,2)");
        registration(1,1,11,"10.00",2,1); registration(2,1,11,"90.00",1,0);
        registration(3,2,201,"90.00",0,0); registration(4,2,11,"77.00",2,0);
        registration(5,2,202,"30.00",0,0);
    }
    @Test void currentCashAndAuditDoNotReuseEarlierRepeatableReadTotal() throws Exception {
        ExecutorService callback=Executors.newSingleThreadExecutor();
        try {
            tx.execute(status -> {
                assertEquals(0,new BigDecimal("10.00").compareTo(oldCash()));
                commit(callback,"UPDATE cms_registration SET payment_status=2 WHERE id=2");
                TopicSettlementCashBasis basis=TopicSettlementCashBasis.fromLockedRows(11L,lockedRows());
                assertEquals(0,new BigDecimal("100.00").compareTo(basis.getTotalSales()));
                assertEquals(0,new BigDecimal("10.00").compareTo(basis.getVerifiedSales()));
                assertEquals(List.of(1L),basis.getVerifiedRegistrationIds());
                assertEquals(0,new BigDecimal("10.00").compareTo(oldCash()),"普通 RR 读确实仍旧；不可用于现金");
                return null;
            });
        } finally { callback.shutdownNow(); }
    }
    @Test void unverifiedActivityCashIncludingDeletedShellCannotHideBehindOldZeroCount() {
        ExecutorService callback=Executors.newSingleThreadExecutor();
        try {
            tx.execute(status -> {
                db.update("UPDATE cms_registration SET payment_status=0 WHERE id=2");
                assertEquals(0,mapper.countPaidActivityRegistrations(11L)); // 故意建立旧视图/一级缓存。
                commit(callback,"UPDATE cms_registration SET payment_status=2 WHERE id=5");
                List<CmsRegistration> rows=lockedRows();
                assertFalse(rows.stream().anyMatch(row -> row.getId()==4L),"同数 activity id 不是真父关系");
                CmsRegistration paid=rows.stream().filter(row -> row.getId()==5L).findFirst().orElseThrow();
                assertEquals(11L,paid.getTopicId()); assertEquals(2,paid.getPaymentStatus()); assertEquals(0,paid.getVerificationStatus());
                IllegalStateException failure=assertThrows(IllegalStateException.class,
                        () -> TopicSettlementCashBasis.fromLockedRows(11L,rows));
                assertTrue(failure.getMessage().contains("ACTIVITY_CASH_BASIS_UNSUPPORTED"));
                return null;
            });
        } finally { callback.shutdownNow(); }
    }
    @Test void explicitCurrentReadsBypassMybatisLocalCacheAfterSameTransactionWrites() {
        tx.execute(status -> {
            List<CmsRegistration> first=lockedRows();
            assertEquals(1,first.stream().filter(row -> row.getId()==2L).findFirst().orElseThrow().getPaymentStatus());
            db.update("UPDATE cms_registration SET payment_status=2 WHERE id=2");
            List<CmsRegistration> second=lockedRows();
            assertEquals(2,second.stream().filter(row -> row.getId()==2L).findFirst().orElseThrow().getPaymentStatus());
            assertEquals(List.of(),mapper.selectCashBasisBlockingRefundIdsForUpdate(11L));
            db.update("INSERT INTO refund_application(id,source_type,source_id,owner_type,owner_id,status,payout_status,del_flag)"
                    +" VALUES(91,'registration',1,1,99,0,0,0)"); // 冗余 owner 错误也不能绕过真实 source。
            assertEquals(List.of(91L),mapper.selectCashBasisBlockingRefundIdsForUpdate(11L));
            return null;
        });
    }
    @Test void lockedCashRowsSerializeAnExistingRegistrationStateWriter() {
        ExecutorService callback=Executors.newSingleThreadExecutor();
        try {
            tx.execute(status -> {
                lockedRows();
                Future<Integer> blocked=callback.submit(() -> {
                    try (Connection connection=source.getConnection(); Statement statement=connection.createStatement()) {
                        statement.execute("SET SESSION innodb_lock_wait_timeout=1");
                        SQLException failure=assertThrows(SQLException.class,
                                () -> statement.executeUpdate("UPDATE cms_registration SET payment_status=4 WHERE id=1"));
                        return failure.getErrorCode();
                    }
                });
                try { assertEquals(1205,blocked.get(5,TimeUnit.SECONDS)); }
                catch (Exception failure) { throw new IllegalStateException("并发报名锁探针失败",failure); }
                assertEquals(2,db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1",Integer.class));
                return null;
            });
        } finally { callback.shutdownNow(); }
    }
    private List<CmsRegistration> lockedRows() {
        List<CmsRegistration> rows=new ArrayList<>(mapper.selectTopicCashRowsForUpdate(11L));
        rows.addAll(mapper.selectTopicActivityCashRowsForUpdate(11L)); return rows;
    }
    private BigDecimal oldCash() {
        return db.queryForObject("SELECT COALESCE(SUM(payable_amount),0) FROM cms_registration"
                +" WHERE owner_type=1 AND owner_id=11 AND payment_status=2 AND registration_status=2",BigDecimal.class);
    }
    private void commit(ExecutorService callback,String sql) {
        try { assertEquals(1,callback.submit(() -> db.update(sql)).get(5,TimeUnit.SECONDS)); }
        catch (Exception failure) { throw new IllegalStateException("独立写事务未提交",failure); }
    }
    private void registration(int id,int type,int owner,String amount,int payment,int verified) {
        db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,order_num,"
                +"original_amount,total_amount,payable_amount,payment_status,registration_status,verification_status,purchase_kind,pay_expire_time)"
                +" VALUES(?,?,?,?,21,?,?,1,?,?,?, ?,2,?,1,'2026-10-01 08:00:00')",
                id,"G-CASH-"+id,type,owner,amount,1000+id,amount,amount,amount,payment,verified);
    }
}

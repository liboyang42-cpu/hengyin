package com.chengyinhub.business.home;

import com.chengyinhub.business.home.PrivateHomeContracts.*;
import com.chengyinhub.business.mapper.PrivateHomeMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real mapper XML, isolated translated migration, real Spring transactions; not a MySQL rehearsal. */
class PrivateHomeTransactionTest {
    DataSource ds; JdbcTemplate jdbc; PrivateHomeService service; PrivateHomeService target;
    Fixture fixture; PrivateHomeMapper mapper;
    static final long NOW=1800000000000L;
    @BeforeEach void setup() throws Exception {
        ds=createDataSource();
        jdbc=new JdbcTemplate(ds);
        createSchema();
        initializeService();
    }
    DataSource createDataSource() throws Exception {return new DriverManagerDataSource("jdbc:h2:mem:home"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");}
    String migrationSql() throws Exception {
        Path migration=Paths.get("sql/migration_20261002_private_player_home.sql");
        if(!Files.exists(migration)) migration=Paths.get("chengyinhub-admin/sql/migration_20261002_private_player_home.sql");
        String sql=new String(Files.readAllBytes(migration), java.nio.charset.StandardCharsets.UTF_8);
        return sql;
    }
    /** H2 2.4 closes the database when another session evaluates a CHECK IN-list. */
    static String rewriteInChecks(String sql) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([A-Za-z_]+) IN \\(([^)]*)\\)").matcher(sql);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            StringBuilder expression = new StringBuilder("CASE ").append(matcher.group(1));
            for (String value : matcher.group(2).split(",")) expression.append(" WHEN ").append(value.trim()).append(" THEN 1");
            expression.append(" ELSE 0 END = 1");
            matcher.appendReplacement(rewritten, java.util.regex.Matcher.quoteReplacement(expression.toString()));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }
    void createSchema() throws Exception {
        String sql=migrationSql();
        sql=sql.substring(0,sql.indexOf("-- runner:"));
        sql=sql.replace("_ascii\'", "\'").replaceAll("(?m)^--.*$", "").replaceAll("CHARACTER SET ascii COLLATE ascii_bin", "")
            .replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='[^']*'", "");
        sql=rewriteInChecks(sql);
        for(String statement:sql.split(";")) if(!statement.trim().isEmpty()) jdbc.execute(statement);
    }
    void initializeService() throws Exception {
        jdbc.execute("CREATE TABLE test_resource(owner_id BIGINT, scope VARCHAR(128), current_value BIGINT, cap BIGINT, version BIGINT, PRIMARY KEY(owner_id,scope))");
        jdbc.update("INSERT INTO test_resource VALUES(7,'run:1',2,10,0),(7,'run:2',3,20,0),(8,'run:1',4,30,0)");
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean(); factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mapper/business/PrivateHomeMapper.xml"));
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(PrivateHomeMapper.class);
        fixture=new Fixture(); target=new PrivateHomeService();
        ReflectionTestUtils.setField(target,"mapper",mapper); ReflectionTestUtils.setField(target,"access",fixture);
        ReflectionTestUtils.setField(target,"dataSource",ds); ReflectionTestUtils.setField(target,"authority",fixture);
        ReflectionTestUtils.setField(target,"resources",fixture); ReflectionTestUtils.setField(target,"clock",Clock.fixed(Instant.ofEpochMilli(NOW),ZoneOffset.UTC));
        ProxyFactory proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service=(PrivateHomeService)proxy.getProxy();
    }
    Point point(){return new Point(new BigDecimal("30.123456"),new BigDecimal("120.123456"),"WGS84");}
    void home(){assertEquals("SAVED",service.setMyHome("set",0,"Virtual base",point()).decision);}
    Proof proof(){home();return service.verifyReturn("verify",1,"run:1","evidence");}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    long balance(String scope){return jdbc.queryForObject("SELECT current_value FROM test_resource WHERE owner_id=7 AND scope=?",Long.class,scope);}
    @Test void ownerOnlyReadAndDeleteScrubCoordinatesAndGrant() {
        Proof proof=proof(); fixture.owner.set(8L); assertEquals(0L,service.getMyHome().version);
        fixture.owner.set(7L); assertEquals("DELETED",service.deleteMyHome("delete",1).decision);
        HomeView view=service.getMyHome(); assertNull(view.latitude); assertNull(view.longitude); assertNull(view.label); assertEquals(2L,view.version);
        assertNull(jdbc.queryForObject("SELECT grant_id FROM player_private_home_proof",String.class));
        assertEquals("PROOF_REVOKED",service.restore("restore",proof.proofId,"run:1").decision);
        assertEquals(2,balance("run:1"));
    }
    @Test void restoreAndLostResponseReplayAreAtomicAndScoped() {
        Proof proof=proof(); Receipt result=service.restore("restore",proof.proofId,"run:1");
        assertEquals("RESTORED",result.decision); assertEquals(8L,result.amount); assertEquals(10,balance("run:1")); assertEquals(3,balance("run:2"));
        assertEquals(result.receiptId,service.restore("restore",proof.proofId,"run:1").receiptId);
        assertEquals("PROOF_CONSUMED",service.restore("second",proof.proofId,"run:1").decision);
        assertEquals(1,jdbc.queryForObject("SELECT version FROM test_resource WHERE owner_id=7 AND scope='run:1'",Long.class));
    }
    @Test void writeFailureRollsBackProofAccountAndReceipt() {
        Proof proof=proof(); fixture.failAfterWrite=true;
        assertThrows(ServiceException.class,()->service.restore("restore",proof.proofId,"run:1"));
        assertEquals(2,balance("run:1")); assertEquals(0,count("player_private_home_restore"));
        assertEquals("READY",jdbc.queryForObject("SELECT status FROM player_private_home_proof",String.class));
    }
    @Test void lateRevocationRollsBackEverything() {
        Proof proof=proof(); fixture.revokeAfterWrite=true;
        assertThrows(ServiceException.class,()->service.restore("restore",proof.proofId,"run:1"));
        assertEquals(2,balance("run:1")); assertEquals(0,count("player_private_home_restore"));
        assertEquals("READY",jdbc.queryForObject("SELECT status FROM player_private_home_proof",String.class));
    }
    @Test void sameProofConcurrentRestoresOnlyCreditOnce() throws Exception {
        Proof proof=proof(); ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch go=new CountDownLatch(1);
        try {
            Future<Receipt> a=pool.submit(()->{go.await();return service.restore("a",proof.proofId,"run:1");});
            Future<Receipt> b=pool.submit(()->{go.await();return service.restore("b",proof.proofId,"run:1");}); go.countDown();
            Set<String> decisions=new HashSet<>(Arrays.asList(a.get(10,TimeUnit.SECONDS).decision,b.get(10,TimeUnit.SECONDS).decision));
            assertEquals(new HashSet<>(Arrays.asList("RESTORED","PROOF_CONSUMED")),decisions); assertEquals(10,balance("run:1"));
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentCreationUsesVersionCasAndOneHome() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch go=new CountDownLatch(1);
        try {
            Future<Mutation> a=pool.submit(()->{go.await();return service.setMyHome("a",0,"A",point());});
            Future<Mutation> b=pool.submit(()->{go.await();return service.setMyHome("b",0,"B",point());});go.countDown();
            Set<String> results=new HashSet<>(Arrays.asList(a.get(10,TimeUnit.SECONDS).decision,b.get(10,TimeUnit.SECONDS).decision));
            assertEquals(new HashSet<>(Arrays.asList("SAVED","VERSION_CONFLICT")),results);assertEquals(1,count("player_private_home"));
        } finally {pool.shutdownNow();}
    }
    @Test void changingHomeRacesRestoreWithoutStaleCredit() throws Exception {
        Proof proof=proof(); ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
        try {
            Future<Receipt> restore=pool.submit(()->{go.await();return service.restore("r",proof.proofId,"run:1");});
            Future<Mutation> change=pool.submit(()->{go.await();return service.setMyHome("change",1,"Changed",point());});go.countDown();
            String decision=restore.get(10,TimeUnit.SECONDS).decision;assertEquals("SAVED",change.get(10,TimeUnit.SECONDS).decision);
            assertTrue(Arrays.asList("RESTORED","PROOF_REVOKED").contains(decision));
            assertEquals("RESTORED".equals(decision)?10:2,balance("run:1"));assertEquals(2L,service.getMyHome().version);
            assertNotEquals("RESTORED",service.restore("later",proof.proofId,"run:1").decision);
        } finally {pool.shutdownNow();}
    }
    @Test void replayCannotSwitchOperationAndVersionCasDoesNotOverwrite() {
        home(); assertEquals(1L,service.setMyHome("set",0,"Virtual base",point()).resultVersion);
        assertThrows(ServiceException.class,()->service.deleteMyHome("set",0));
        assertEquals("VERSION_CONFLICT",service.setMyHome("stale",0,"Other",point()).decision);
        assertEquals("Virtual base",service.getMyHome().label); assertEquals(1L,service.getMyHome().version);
    }
    @Test void expiredAndCrossScopeProofDoNotCredit() {
        Proof proof=proof(); assertEquals("PROOF_SCOPE_CHANGED",service.restore("cross",proof.proofId,"run:2").decision);
        ReflectionTestUtils.setField(target,"clock",Clock.fixed(Instant.ofEpochMilli(NOW+1001),ZoneOffset.UTC));
        assertEquals("PROOF_EXPIRED",service.restore("expired",proof.proofId,"run:1").decision); assertEquals(2,balance("run:1"));
    }
    @Test void missingAuthorityAndAdapterFailClosed() {
        ReflectionTestUtils.setField(target,"authority",null); assertThrows(ServiceException.class,()->service.getMyHome());
        ReflectionTestUtils.setField(target,"authority",fixture); home(); ReflectionTestUtils.setField(target,"resources",null);
        assertThrows(ServiceException.class,()->service.verifyReturn("v",1,"run:1","evidence")); assertEquals(0,count("player_private_home_proof"));
    }
    @Test void unsupportedCoordinatesAndUnproxiedWritesFailClosed() {
        assertThrows(ServiceException.class,()->service.setMyHome("s",0,"Home",new Point(BigDecimal.ZERO,BigDecimal.ZERO,"GCJ02")));
        assertThrows(ServiceException.class,()->target.setMyHome("s",0,"Home",point())); assertEquals(0,count("player_private_home"));
    }
    @Test void expiryDuringResourceWriteRollsBackAll() {
        Proof proof=proof(); fixture.expireAfterWrite=true;
        assertThrows(ServiceException.class,()->service.restore("late",proof.proofId,"run:1"));
        assertEquals(2,balance("run:1")); assertEquals(0,count("player_private_home_restore"));
        assertEquals("READY",jdbc.queryForObject("SELECT status FROM player_private_home_proof",String.class));
    }
    @Test void arrivalGrantCannotMintSecondProofAfterConsumption() {
        fixture.fixedGrant="single-authoritative-grant"; Proof proof=proof(); service.restore("restore",proof.proofId,"run:1");
        assertThrows(Exception.class,()->service.verifyReturn("another",1,"run:1","evidence"));
        assertEquals(1,count("player_private_home_proof"));
    }
    @Test void inPlaceAuthorityPolicyChangeRollsBackRestore() {
        fixture.sharedPolicy=fixture.policy(fixture.current()); Proof proof=proof();fixture.changePolicyAfterWrite=true;
        assertThrows(ServiceException.class,()->service.restore("changed-policy",proof.proofId,"run:1"));
        assertEquals(2,balance("run:1"));assertEquals(0,count("player_private_home_restore"));
    }
    @Test void inPlaceResourceRuleChangeRollsBackRestore() {
        fixture.sharedResource=fixture.resourcePolicy(fixture.current(),"run:1");Proof proof=proof();fixture.changeResourceAfterWrite=true;
        assertThrows(ServiceException.class,()->service.restore("changed-resource",proof.proofId,"run:1"));
        assertEquals(2,balance("run:1"));assertEquals(0,count("player_private_home_restore"));
    }
    @Test void differentDatasourceIsRejected() {
        home(); PrivateHomeResources other=org.mockito.Mockito.mock(PrivateHomeResources.class);
        ReflectionTestUtils.setField(target,"resources",other);
        assertThrows(ServiceException.class,()->service.verifyReturn("v",1,"run:1","evidence"));
        assertEquals(0,count("player_private_home_proof"));
    }
    @Test void constraintsRejectInvalidPrivateRowsAndDuplicateRequests() {
        home(); assertThrows(Exception.class,()->jdbc.update("UPDATE player_private_home SET latitude=91 WHERE owner_id=7"));
        assertThrows(Exception.class,()->jdbc.update("UPDATE player_private_home SET latitude=NULL WHERE owner_id=7"));
        assertThrows(Exception.class,()->jdbc.update("INSERT INTO player_private_home_mutation SELECT * FROM player_private_home_mutation"));
    }
    class Fixture implements PrivateHomeAccess, PrivateHomeAuthority, PrivateHomeResources {
        ThreadLocal<Long> owner=ThreadLocal.withInitial(()->7L); volatile boolean revoked,failAfterWrite,revokeAfterWrite,expireAfterWrite,changePolicyAfterWrite,changeResourceAfterWrite;
        Policy sharedPolicy; ResourcePolicy sharedResource; String fixedGrant;
        public Actor current(){return new Actor(owner.get(),1);}
        public void revalidate(Actor actor){if(revoked || actor.ownerId!=owner.get()) throw new ServiceException("HOME_UNAUTHORIZED");}
        public void requireNative(Actor actor) {revalidate(actor);}
        public Policy policy(Actor actor){if(sharedPolicy!=null)return sharedPolicy;Policy p=new Policy();p.version="test-only";p.changeCooldownMillis=0L;p.activationDelayMillis=0L;p.proofTtlMillis=1000L;return p;}
        public boolean placementAllowed(Actor actor,Point point){return true;}
        public ResourcePolicy resourcePolicy(Actor actor,String scope){if(sharedResource!=null)return sharedResource;ResourcePolicy p=new ResourcePolicy();p.scope=scope;p.resourceType="STAMINA";p.scopeRevision="scope1";p.ruleVersion="test-only";p.restoreCooldownMillis=0L;return p;}
        public Arrival verifyArrival(Actor actor,Home home,ResourcePolicy resource,String handle){Arrival a=new Arrival();a.grantId=fixedGrant==null?UUID.randomUUID().toString():fixedGrant;a.ownerId=actor.ownerId;a.epoch=actor.epoch;a.homeVersion=home.version;a.scope=resource.scope;a.scopeRevision=resource.scopeRevision;a.ruleVersion=resource.ruleVersion;a.action="RESTORE_TO_CAP";a.expiresAt=NOW+1000;return a;}
        public boolean grantCurrent(Actor actor,Home home,Proof proof){return !revoked;}
        public DataSource transactionDataSource(){return ds;}
        public Resource lockAuthorized(Actor actor,ResourcePolicy policy){return jdbc.queryForObject("SELECT * FROM test_resource WHERE owner_id=? AND scope=? FOR UPDATE",(rs,n)->{Resource r=new Resource();r.current=rs.getLong("current_value");r.cap=rs.getLong("cap");r.version=rs.getLong("version");return r;},actor.ownerId,policy.scope);}
        public int restoreToCap(Actor actor,ResourcePolicy policy,Resource locked,String source){int n=jdbc.update("UPDATE test_resource SET current_value=cap,version=version+1 WHERE owner_id=? AND scope=? AND version=?",actor.ownerId,policy.scope,locked.version);if(changeResourceAfterWrite)sharedResource.ruleVersion="changed";if(changePolicyAfterWrite)sharedPolicy.version="changed";if(expireAfterWrite)ReflectionTestUtils.setField(target,"clock",Clock.fixed(Instant.ofEpochMilli(NOW+1001),ZoneOffset.UTC));if(revokeAfterWrite)revoked=true;if(failAfterWrite)throw new ServiceException("RESOURCE_WRITE_FAILED");return n;}
    }
}

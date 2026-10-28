package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CoopInviteRequestResult;
import com.chengyinhub.business.it.ItSchema;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.CoopClubApplyServiceImpl;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import com.chengyinhub.business.service.support.ClubCoopWindowPolicy;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual receipt/invite/apply SQL and Spring transaction proxy. Topic/club fixtures provide real row locks. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoopInviteOriginalRequestMySqlIT {
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("coop_request_it");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private TransactionTemplate tx;
    private CoopInviteRequestReceiptMapper receipts;
    private CoopInviteMapper invites;
    private CoopClubApplyMapper applyMapper;
    private MerchantOperatorMapper operators;
    private IMerchantAccessService accessOverride;
    private CoopClubApplyServiceImpl applies;
    private boolean databaseCreated;

    @BeforeAll
    void createDatabase() throws Exception {
        try (Connection c = dataSource(MYSQL.adminUrl()).getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4"); databaseCreated = true;
        }
        dataSource = dataSource(MYSQL.databaseUrl()); jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(transactions); tx.setTimeout(20);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        Configuration config = new Configuration(new Environment("coop-request-it", new SpringManagedTransactionFactory(), dataSource));
        config.getTypeAliasRegistry().registerAlias("CoopInvite", CoopInvite.class);
        config.getTypeAliasRegistry().registerAlias("CoopClubApply", CoopClubApply.class);
        for (Class<?> mapper : List.of(CoopInviteMapper.class, CoopInviteRequestReceiptMapper.class, CoopClubApplyMapper.class, MerchantOperatorMapper.class)) {
            config.addMapper(mapper);
            String resource = "mapper/business/" + mapper.getSimpleName() + ".xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        receipts = session.getMapper(CoopInviteRequestReceiptMapper.class);
        invites = session.getMapper(CoopInviteMapper.class);
        applyMapper = session.getMapper(CoopClubApplyMapper.class);
        operators = session.getMapper(MerchantOperatorMapper.class);
    }

    private Path rootPath(String name) {
        Path root = Path.of(name); return Files.exists(root) ? root : Path.of("..").resolve(name);
    }

    @BeforeEach
    void resetSchema() throws Exception {
        accessOverride = null;
        try (Connection c = dataSource.getConnection()) {
            ItSchema.apply(c, "it-schema/coop-it-schema.sql");
            ItSchema.applyMigrations(c, rootPath("chengyinhub-admin/sql/migration_20261025_coop_invite_request_receipt.sql").toString());
        }
        jdbc.execute("DROP TABLE IF EXISTS coop_club_apply");
        String baseline = Files.readString(rootPath("sql/base_schema_20260818.sql"));
        var ddl = Pattern.compile("(?s)CREATE TABLE `coop_club_apply` \\(.*?\\) ENGINE=.*?;").matcher(baseline);
        assertTrue(ddl.find(), "original apply DDL must be present"); jdbc.execute(ddl.group());
        jdbc.execute("DROP TABLE IF EXISTS merchant_operator");
        String operatorMigration = Files.readString(rootPath("chengyinhub-admin/sql/migration_merchant_operator_20260823.sql"));
        var operatorDdl = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS merchant_operator \\(.*?\\) ENGINE=.*?;").matcher(operatorMigration);
        assertTrue(operatorDdl.find(), "existing employee DDL must be present"); jdbc.execute(operatorDdl.group());
        jdbc.update("INSERT INTO merchant_operator(id,merchant_id,operator_member_id,role_code,status,accepted_at,create_time) "
                + "VALUES(1,8,9,'MERCHANT_MANAGER','ACTIVE',NOW(),NOW())");
        jdbc.execute("DROP TABLE IF EXISTS cms_topic");
        jdbc.execute("CREATE TABLE cms_topic(id BIGINT PRIMARY KEY,member_id BIGINT,club_id BIGINT,del_flag INT,lifecycle INT,recruit_deadline DATETIME) ENGINE=InnoDB");
        jdbc.execute("DROP TABLE IF EXISTS club");
        jdbc.execute("CREATE TABLE club(id BIGINT PRIMARY KEY,member_id BIGINT,del_flag INT) ENGINE=InnoDB");
        jdbc.update("INSERT INTO cms_topic VALUES(10,1,NULL,0,1,DATE_ADD(NOW(),INTERVAL 1 DAY)),(11,1,NULL,0,1,DATE_ADD(NOW(),INTERVAL 1 DAY))");
        jdbc.update("INSERT INTO club VALUES(3,3,0),(4,4,0)");
        jdbc.update("INSERT INTO coop_club_apply(id,topic_id,club_id,member_id,status,del_flag) VALUES(7,10,3,3,0,0)");
        applies = spy(new CoopClubApplyServiceImpl()); ReflectionTestUtils.setField(applies, "coopClubApplyMapper", applyMapper);
    }

    @AfterAll
    void dropDatabase() throws Exception {
        if (!databaseCreated) return;
        try (Connection c = dataSource(MYSQL.adminUrl()).getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource(); ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url); ds.setUsername(MYSQL.user()); ds.setPassword(MYSQL.password()); return ds;
    }

    private CmsTopic topic(Long id, boolean lock) {
        return jdbc.query("SELECT * FROM cms_topic WHERE id=?" + (lock ? " FOR UPDATE" : ""), (rs, n) -> {
            CmsTopic t = new CmsTopic(); t.setId(rs.getLong("id")); t.setMemberId(rs.getLong("member_id"));
            t.setClubId(rs.getObject("club_id", Long.class)); t.setDelFlag(rs.getInt("del_flag"));
            t.setLifecycle(rs.getInt("lifecycle")); t.setRecruitDeadline(rs.getTimestamp("recruit_deadline")); return t;
        }, id).stream().findFirst().orElse(null);
    }

    private Club club(Long id, boolean lock) {
        return jdbc.query("SELECT * FROM club WHERE id=? AND del_flag=0" + (lock ? " FOR UPDATE" : ""), (rs, n) -> {
            Club c = new Club(); c.setId(rs.getLong("id")); c.setMemberId(rs.getLong("member_id")); c.setDelFlag(rs.getInt("del_flag")); return c;
        }, id).stream().findFirst().orElse(null);
    }

    private ICoopInviteService service(CoopInviteRequestReceiptMapper receiptMapper, CoopInviteMapper inviteMapper) {
        CoopInviteServiceImpl target = new CoopInviteServiceImpl();
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicById(anyLong())).thenAnswer(c -> topic(c.getArgument(0), false));
        when(topics.selectCmsTopicByIdForUpdate(anyLong())).thenAnswer(c -> topic(c.getArgument(0), true));
        ClubMapper clubs = mock(ClubMapper.class);
        when(clubs.selectClubById(anyLong())).thenAnswer(c -> club(c.getArgument(0), false));
        when(clubs.selectClubByIdForUpdate(anyLong())).thenAnswer(c -> club(c.getArgument(0), true));
        MmsMerchantMapper merchants = mock(MmsMerchantMapper.class);
        when(merchants.selectMmsMerchantByMemberId(anyLong())).thenAnswer(c -> merchant(c.getArgument(0)));
        when(merchants.selectMmsMerchantByMemberIdForUpdate(anyLong())).thenAnswer(c -> merchant(c.getArgument(0)));
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(eq(1L), anyString())).thenReturn(new MerchantAccessContext(1L, 1L, 1L, "owner"));
        ReflectionTestUtils.setField(target, "requestReceiptMapper", receiptMapper);
        ReflectionTestUtils.setField(target, "coopInviteMapper", inviteMapper);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(target, "clubMapper", clubs);
        ReflectionTestUtils.setField(target, "mmsMerchantMapper", merchants);
        ReflectionTestUtils.setField(target, "merchantAccessService", accessOverride == null ? access : accessOverride);
        ReflectionTestUtils.setField(target, "transactionManager", transactions);
        ReflectionTestUtils.setField(target, "coopClubApplyService", applies);
        ReflectionTestUtils.setField(target, "clubCoopWindowPolicy", new ClubCoopWindowPolicy(mock(CommercialTopicPolicy.class)));
        ProxyFactory proxy = new ProxyFactory(target); proxy.setInterfaces(ICoopInviteService.class);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (ICoopInviteService) proxy.getProxy();
    }

    private MmsMerchant merchant(Long id) {
        MmsMerchant m = new MmsMerchant(); m.setMemberId(id); m.setDelFlag(0); m.setStatus(1L); m.setAccountStatus(1L); return m;
    }
    private CoopInvite request(String key) {
        CoopInvite r = new CoopInvite(); r.setRequestId(key); r.setInviteType(0); r.setTopicId(10L);
        r.setToType("merchant"); r.setToId(2L); r.setMessage("original"); r.setShareMode(2); r.setFixedFee(new BigDecimal("12")); return r;
    }
    private CoopInvite sourced(String key) {
        CoopInvite r = request(key); r.setInviteType(1); r.setToType("club"); r.setToId(3L); r.setOriginApplyId(7L); return r;
    }

    @Test
    void serialRecoverySurvivesHandledAndExpiredOriginalWithOneConversion() {
        ICoopInviteService service = service(receipts, invites); AtomicInteger checks = new AtomicInteger();
        var first = service.createOrReplayInvite(1L, sourced("serial"), checks::incrementAndGet);
        assertEquals(3, jdbc.queryForObject("SELECT status FROM coop_club_apply WHERE id=7", Integer.class));
        for (int status : List.of(1,2,3,4,5)) {
            jdbc.update("UPDATE coop_invite SET status=?,expire_time='2000-01-01',create_time='2000-01-01'", status);
            jdbc.update("UPDATE cms_topic SET lifecycle=5,recruit_deadline='2000-01-01'");
            var replay = service.createOrReplayInvite(1L, sourced("serial"), checks::incrementAndGet);
            assertTrue(replay.getReplayed()); assertEquals(first.getInviteId(), replay.getInviteId());
        }
        assertEquals(1, checks.get()); assertOneBoundInvite();
        verify(applies, times(1)).markConverted(7L);
    }

    @Test
    void concurrentSameKeyWithPreexistingRrSnapshotsReturnsWinner() throws Exception {
        List<Object> results = race("race", "race", false);
        assertEquals(2, results.stream().filter(CoopInviteRequestResult.class::isInstance).count());
        var a = (CoopInviteRequestResult) results.get(0); var b = (CoopInviteRequestResult) results.get(1);
        assertEquals(a.getInviteId(), b.getInviteId()); assertNotEquals(a.getReplayed(), b.getReplayed());
        assertOneBoundInvite();
        assertEquals(3, jdbc.queryForObject("SELECT status FROM coop_club_apply WHERE id=7", Integer.class));
        verify(applies, times(1)).markConverted(7L);
    }

    @Test
    void concurrentChangedMessageConflictsAndDifferentKeyStillHitsBusinessDuplicate() throws Exception {
        List<Object> conflict = race("race", "race", true);
        assertEquals(1, conflict.stream().filter(CoopInviteRequestResult.class::isInstance).count());
        assertEquals(409, ((ServiceException) conflict.stream().filter(ServiceException.class::isInstance).findFirst().orElseThrow()).getCode());
        assertOneBoundInvite();
        jdbc.update("DELETE FROM coop_invite_request_receipt"); jdbc.update("DELETE FROM coop_invite");
        jdbc.update("UPDATE coop_club_apply SET status=0,handle_time=NULL WHERE id=7");
        List<Object> duplicate = race("first-key", "second-key", false);
        assertEquals(1, duplicate.stream().filter(CoopInviteRequestResult.class::isInstance).count());
        assertEquals(400, ((ServiceException) duplicate.stream().filter(ServiceException.class::isInstance).findFirst().orElseThrow()).getCode());
        assertOneBoundInvite();
    }

    private List<Object> race(String keyA, String keyB, boolean changedMessage) throws Exception {
        CountDownLatch snapshots = new CountDownLatch(2); CountDownLatch release = new CountDownLatch(1);
        CoopInviteRequestReceiptMapper wrapped = mock(CoopInviteRequestReceiptMapper.class, delegatesTo(receipts));
        ThreadLocal<Boolean> initial = ThreadLocal.withInitial(() -> true);
        doAnswer(call -> {
            var empty = receipts.selectByRequest(call.getArgument(0), call.getArgument(1));
            if (initial.get()) {
                initial.set(false); assertNull(empty); snapshots.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return empty;
        }).when(wrapped).selectByRequest(anyLong(), anyString());
        ICoopInviteService service = service(wrapped, invites);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> a = pool.submit(() -> concurrentSend(service, sourced(keyA)));
            CoopInvite second = sourced(keyB); if (changedMessage) second.setMessage("changed");
            Future<Object> b = pool.submit(() -> concurrentSend(service, second));
            assertTrue(snapshots.await(10, TimeUnit.SECONDS)); release.countDown();
            return List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    private Object concurrentSend(ICoopInviteService service, CoopInvite request) {
        try { return tx.execute(status -> {
            assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            return service.createOrReplayInvite(1L, request, () -> {});
        }); } catch (ServiceException failure) { return failure; }
    }

    @Test
    void invitationConversionAndBindingFailuresRollbackWholeTransaction() {
        CoopInviteMapper failedInvite = mock(CoopInviteMapper.class, delegatesTo(invites));
        doAnswer(call -> { invites.insertCoopInvite(call.getArgument(0)); return 0; }).when(failedInvite).insertCoopInvite(any());
        assertThrows(IllegalStateException.class, () -> service(receipts, failedInvite).createOrReplayInvite(1L, sourced("insert-fail"), () -> {}));
        assertEmptyAndPending();
        doAnswer(call -> { call.callRealMethod(); return 0; }).when(applies).markConverted(7L);
        assertThrows(IllegalStateException.class, () -> service(receipts, invites).createOrReplayInvite(1L, sourced("convert-fail"), () -> {}));
        assertEmptyAndPending();
        doCallRealMethod().when(applies).markConverted(7L);
        CoopInviteRequestReceiptMapper failedBind = mock(CoopInviteRequestReceiptMapper.class, delegatesTo(receipts));
        doReturn(0).when(failedBind).bindInvite(anyLong(), anyLong());
        assertThrows(IllegalStateException.class, () -> service(failedBind, invites).createOrReplayInvite(1L, sourced("bind-fail"), () -> {}));
        assertEmptyAndPending();
    }

    @Test
    void caseSensitiveKeysReadOnlyDiscoveryAndSubjectMigration() {
        ICoopInviteService service = service(receipts, invites);
        var first = service.createOrReplayInvite(1L, request("CaseKey"), () -> {});
        CoopInvite other = request("casekey"); other.setTopicId(11L);
        service.createOrReplayInvite(1L, other, () -> {});
        assertEquals("NOT_FOUND", service.findInviteRequest(9L, "CaseKey").getState());
        jdbc.update("UPDATE coop_invite SET create_time='2000-01-01',expire_time='2000-01-01'");
        assertEquals("CREATED", service.findInviteRequest(1L, "CaseKey").getState());
        var page = service.listInviteRequests(1L, null, 1); assertEquals(true, page.get("hasMore"));
        var lastPage = service.listInviteRequests(1L, Long.valueOf((String) page.get("nextCursor")), 1);
        assertEquals(1, ((List<?>) lastPage.get("rows")).size());
        assertEquals(false, lastPage.get("hasMore")); assertNull(lastPage.get("nextCursor"));
        assertEquals(0, jdbc.queryForObject("SELECT MAX(status) FROM coop_invite", Integer.class), "recovery reads do not expire rows");
        jdbc.update("UPDATE cms_topic SET member_id=9 WHERE id=10");
        var unavailable = service.findInviteRequest(1L, "CaseKey"); assertEquals("UNAVAILABLE", unavailable.getState()); assertNull(unavailable.getInviteId());
        assertEquals(403, assertThrows(ServiceException.class, () -> service.createOrReplayInvite(1L, request("CaseKey"), () -> {})).getCode());
        assertNotNull(first.getInviteId()); assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM coop_invite", Integer.class));
    }

    private IMerchantAccessService realEmployeeAccess() {
        com.chengyinhub.business.access.merchant.MerchantAccessServiceImpl access = new com.chengyinhub.business.access.merchant.MerchantAccessServiceImpl();
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MmsMerchant owner = merchant(1L); owner.setId(8L);
        when(merchants.selectMmsMerchantList(any())).thenAnswer(call -> {
            MmsMerchant query = call.getArgument(0);
            return Long.valueOf(1L).equals(query.getMemberId()) ? List.of(owner) : Collections.emptyList();
        });
        when(merchants.selectMmsMerchantById(8L)).thenReturn(owner);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember employee = new UmsMember(); employee.setId(9L); employee.setStatus(1L); employee.setDelFlag(0);
        when(members.selectUmsMemberById(9L)).thenReturn(employee);
        ReflectionTestUtils.setField(access, "merchantService", merchants);
        ReflectionTestUtils.setField(access, "merchantOperatorMapper", operators);
        ReflectionTestUtils.setField(access, "umsMemberMapper", members);
        return access;
    }

    private void revokeEmployeeOnAnotherConnection() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertTrue(connection.getAutoCommit());
            assertEquals(1, statement.executeUpdate("UPDATE merchant_operator SET status='REVOKED',version=version+1 WHERE id=1"));
        }
    }

    @Test
    void revokedEmployeeCannotUseOuterRrSnapshotToClaimReplayOrReadOriginal() {
        accessOverride = realEmployeeAccess();
        ICoopInviteService service = service(receipts, invites);
        CoopInvite request = request("employee"); request.setScope("MERCHANT");
        ServiceException createDenied = assertThrows(ServiceException.class, () -> tx.execute(status -> {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM merchant_operator WHERE active_operator_member_id=9", Integer.class));
            try { revokeEmployeeOnAnotherConnection(); } catch (Exception failure) { throw new IllegalStateException(failure); }
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM merchant_operator WHERE active_operator_member_id=9", Integer.class), "outer snapshot still contains the revoked grant");
            return service.createOrReplayInvite(9L, request, () -> {});
        }));
        assertEquals(403, createDenied.getCode()); assertEmptyAndPending();
        jdbc.update("UPDATE merchant_operator SET status='ACTIVE' WHERE id=1");
        String originalId = service.createOrReplayInvite(9L, request, () -> {}).getInviteId();
        tx.execute(status -> {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM merchant_operator WHERE active_operator_member_id=9", Integer.class));
            try { revokeEmployeeOnAnotherConnection(); } catch (Exception failure) { throw new IllegalStateException(failure); }
            var unavailable = service.findInviteRequest(9L, "employee");
            assertEquals("UNAVAILABLE", unavailable.getState()); assertNull(unavailable.getInviteId());
            var page = service.listInviteRequests(9L, null, 20);
            assertEquals("UNAVAILABLE", ((CoopInviteRequestResult) ((List<?>) page.get("rows")).get(0)).getState());
            return null;
        });
        assertEquals(403, assertThrows(ServiceException.class, () -> service.createOrReplayInvite(9L, request, () -> {})).getCode());
        assertOneBoundInvite(); assertNotNull(originalId);
    }

    private void assertOneBoundInvite() {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM coop_invite", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM coop_invite_request_receipt", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM coop_invite_request_receipt r JOIN coop_invite i ON r.invite_id=i.id", Integer.class));
    }
    private void assertEmptyAndPending() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM coop_invite", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM coop_invite_request_receipt", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM coop_club_apply WHERE id=7", Integer.class));
    }
}

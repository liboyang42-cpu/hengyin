package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.impl.ClubServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.chengyinhub.business.it.ItSchema;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL, production Mapper SQL and transactional Service; isolated schema, no external writes. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClubProfileEditMySqlIT {
    private JdbcTemplate admin, db;
    private String database;
    private ClubMapper mapper;
    private IClubService service;

    @BeforeAll
    void setup() throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        assertEquals("127.0.0.1", env.get("CHENGYIN_TEST_MYSQL_HOST"));
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "_" + UUID.randomUUID().toString().replace("-", ""));
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.from(env, "club");
        database = config.databaseName();
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        assertEquals("REPEATABLE-READ", admin.queryForObject("select @@transaction_isolation", String.class));
        admin.execute("create database " + database);
        DriverManagerDataSource source = new DriverManagerDataSource(config.databaseUrl(), config.user(), config.password());
        db = new JdbcTemplate(source);
        try (Connection connection = source.getConnection()) {
            ItSchema.apply(connection, "it-schema/run41-prod-ddl.sql");
            ItSchema.applyMigrations(connection, "sql/migration_20261013_club_profile_edit_version.sql");
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ClubMapper.xml"),
                new ClassPathResource("mapper/business/ClubMemberMapper.xml"));
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory.getObject());
        mapper = sessions.getMapper(ClubMapper.class);
        ClubServiceImpl target = new ClubServiceImpl();
        ReflectionTestUtils.setField(target, "clubMapper", mapper);
        ReflectionTestUtils.setField(target, "clubMemberMapper", sessions.getMapper(ClubMemberMapper.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(IClubService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (IClubService) proxy.getProxy();
    }

    @BeforeEach
    void seed() {
        db.update("delete from club_profile_edit_receipt");
        db.update("delete from club_member");
        db.update("delete from club");
        db.update("insert into club(id,member_id,name,status,del_flag,member_count,level,member_discount_price,profile_version) "
                + "values(9,1,'Original',1,0,8,3,12.50,1),(10,2,'Other club',1,0,4,2,9.50,1)");
        db.update("insert into club_member(club_id,member_id,role,status) values(9,3,1,1),(9,4,0,1)");
    }

    @AfterAll
    void cleanup() {
        if (database != null && admin != null) {
            admin.execute("drop database " + database);
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?", Integer.class, database));
        }
    }

    private Club edit(long expected, String request, String name) {
        Club c = new Club(); c.setId(9L); c.setExpectedVersion(expected); c.setEditRequestId(request); c.setName(name);
        return c;
    }
    private Map<String, Object> save(Club c) {
        return service.saveOwnedClubProfile(c, 1L, null, String.format("%064x", c.getName().hashCode() & 0xffffffffL));
    }
    private long version() { return mapper.selectClubById(9L).getProfileVersion(); }

    @Test
    void twoEditorsSameVersionOnlyOneSucceedsAndSqlItselfRejectsStaleOwner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> first = () -> { start.await(); try { save(edit(1, "club_request_first", "First")); return true; }
            catch (ConcurrentModificationException conflict) { return false; } };
        Callable<Boolean> second = () -> { start.await(); try { save(edit(1, "club_request_second", "Second")); return true; }
            catch (ConcurrentModificationException conflict) { return false; } };
        try {
            Future<Boolean> a = pool.submit(first), b = pool.submit(second); start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(2, version());
            assertEquals(1, db.queryForObject("select count(*) from club_profile_edit_receipt", Integer.class));
            Club stale = edit(1, "club_request_stale", "Stale"); stale.setMemberId(1L);
            assertEquals(0, mapper.updateClub(stale), "Atomic SQL predicate must enforce version even without the Service precheck");
            Club otherOwner = edit(2, "club_request_other", "Other"); otherOwner.setMemberId(2L);
            assertEquals(0, mapper.updateClub(otherOwner), "Atomic SQL predicate binds the locked club owner");
        } finally { pool.shutdownNow(); }
    }

    @Test
    void originalReceiptSurvivesLaterEditsAndDifferentPayloadCannotReuseId() {
        Map<String, Object> original = save(edit(1, "club_request_original", "Mine"));
        save(edit(2, "club_request_later", "Later"));
        Map<String, Object> replay = save(edit(1, "club_request_original", "Mine"));
        assertEquals(((Number) original.get("profileVersion")).longValue(), ((Number) replay.get("profileVersion")).longValue());
        assertEquals(2, ((Number) replay.get("profileVersion")).longValue());
        assertEquals("Later", mapper.selectClubById(9L).getName()); assertEquals(3, version());
        assertThrows(IllegalArgumentException.class, () -> save(edit(1, "club_request_original", "Changed")));
        assertEquals(3, version());
    }

    @Test
    void validOwnerDoesNotOverwriteUnrelatedFieldsAndOldClientsAdvanceVersion() {
        Club c = edit(1, "club_request_owner", "Edited"); c.setOperationConfigUpdated(true);
        c.setPrioritySignupEnabled(1); c.setMemberReservedQuota(2);
        save(c);
        Club saved = mapper.selectClubById(9L);
        assertEquals(8, saved.getMemberCount()); assertEquals(3, saved.getLevel()); assertEquals(1L, saved.getStatus());
        assertEquals(new java.math.BigDecimal("12.50"), saved.getMemberDiscountPrice());
        assertEquals("Other club", mapper.selectClubById(10L).getName());
        Club legacy = new Club(); legacy.setId(9L); legacy.setName("Legacy");
        service.saveOwnedClubProfile(legacy, 1L, null, null);
        assertEquals(3, version());
        assertThrows(ConcurrentModificationException.class, () -> save(edit(2, "club_request_stale", "Old")));
        assertEquals("Legacy", mapper.selectClubById(9L).getName());
        Club clear = edit(3, "club_request_clear", "Clear"); clear.setOperationConfigUpdated(true);
        clear.setMemberDiscountPrice(null); clear.setPrioritySignupEnabled(0); clear.setMemberReservedQuota(0);
        save(clear); assertNull(mapper.selectClubById(9L).getMemberDiscountPrice(), "Explicit null still clears the old optional discount");
    }

    @Test
    void receiptCannotCrossActorOrClubAndCurrentRoleStillGatesReplay() {
        save(edit(1, "club_request_identity", "Mine"));
        assertThrows(IllegalArgumentException.class, () -> service.saveOwnedClubProfile(edit(1, "club_request_identity", "Mine"), 4L, null, "same"));
        Club other = edit(1, "club_request_identity", "Other"); other.setId(10L);
        assertThrows(IllegalArgumentException.class, () -> service.saveOwnedClubProfile(other, 1L, null, "same"));
        assertThrows(ConcurrentModificationException.class, () -> service.saveOwnedClubProfile(edit(1, "club_request_identity", "Mine"), 3L, null, "same"));
        service.saveOwnedClubProfile(edit(2, "club_request_admin", "Admin"), 3L, null, "same");
        db.update("update club_member set role=0 where club_id=9 and member_id=3");
        assertThrows(IllegalArgumentException.class, () -> service.saveOwnedClubProfile(edit(2, "club_request_admin", "Admin"), 3L, null, "same"));
        assertEquals(3, version());
    }

    @Test
    void allExistingProfileWritersInvalidateEditorsAndJoinPolicySaveReadsFinalVersion() {
        mapper.clearProfileMedia(9L); assertEquals(2, version());
        mapper.updatePublicVisible(9L, 0); assertEquals(3, version());
        mapper.updateMemberPostAllowed(9L, 0); assertEquals(4, version());
        mapper.updateMerchantUndertakeOpen(9L, 0); assertEquals(5, version());
        mapper.updateJoinPolicy(9L, 1); assertEquals(6, version());
        assertThrows(ConcurrentModificationException.class, () -> save(edit(1, "club_request_stale", "Stale")));
        Map<String, Object> result = service.saveOwnedClubProfile(edit(6, "club_request_policy", "Policy"), 1L, 0, "same");
        assertEquals(8, ((Number) result.get("profileVersion")).longValue());
        assertEquals(0, ((Number) result.get("joinPolicy")).intValue());
    }

    @Test
    void receiptFailureRollsBackProfileAndJoinPolicy() {
        db.execute("alter table club_profile_edit_receipt add constraint no_receipts check (actor_id < 0)");
        try {
            assertThrows(RuntimeException.class, () -> service.saveOwnedClubProfile(edit(1, "club_request_rollback", "Must roll back"), 1L, 1, "same"));
            assertEquals(1, version()); assertEquals("Original", mapper.selectClubById(9L).getName());
            assertEquals(0, mapper.selectJoinPolicy(9L));
        } finally { db.execute("alter table club_profile_edit_receipt drop check no_receipts"); }
    }
}

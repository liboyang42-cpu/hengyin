package com.chengyinhub.business.mapper;

import com.chengyinhub.business.it.ItSchema;
import com.chengyinhub.business.it.MybatisItDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Actual XML against the retained production DDL in a fresh localhost-only schema. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityActivityReferenceMySqlIT {
    private JdbcTemplate jdbc;
    private CommunityPostMapper mapper;
    private String database;

    @BeforeAll
    void setup() throws Exception {
        assertTrue("127.0.0.1".equals(MybatisItDatabase.host()) || "localhost".equals(MybatisItDatabase.host()));
        String suite = "cpostref_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        DriverManagerDataSource source = new DriverManagerDataSource(MybatisItDatabase.url(suite),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        database = MybatisItDatabase.databaseName(suite);
        jdbc = new JdbcTemplate(source);
        assertEquals(database, jdbc.queryForObject("SELECT DATABASE()", String.class));
        try (Connection connection = source.getConnection()) {
            ItSchema.apply(connection, "it-schema/run41-prod-ddl.sql");
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CommunityPostMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(CommunityPostMapper.class);
    }

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM cms_registration");
        jdbc.update("DELETE FROM cms_activity");
        jdbc.update("INSERT INTO cms_activity(id,member_id,name,status,publish_status,del_flag,img_url,address_name) "
                + "VALUES(101,10,'synthetic owner activity',1,1,0,'cover','synthetic address')");
    }

    @AfterAll
    void cleanup() {
        if (jdbc != null && database != null) {
            assertTrue(database.matches("chengyin_mybatis_cpostref_[a-z0-9_]+"));
            jdbc.execute("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void registrationDdlUsesCancellationAndPaymentStatesWithoutDeletionColumn() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=? AND table_name='cms_registration' AND column_name='del_flag'",
                Integer.class, database));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=? AND table_name='cms_registration' "
                + "AND column_name IN ('registration_status','payment_status')", Integer.class, database));
    }

    @Test
    void creatorCanSelectSnapshotAndOptionsWithoutRegistration() {
        assertAdmitted(10L);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cms_registration", Integer.class));
    }

    @Test
    void paidRegisteredMemberCanSelectBothWithoutDuplicateOptions() {
        register(1, 30, 2, 101, 2, 2);
        register(2, 30, 2, 101, 2, 2);
        assertAdmitted(30L);
    }

    @Test
    void paidMemberOptionsUsesActualRegistrationSchema() {
        register(1, 30, 2, 101, 2, 2);
        assertEquals(List.of(101L), optionIds(30L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "owner_type", "owner_id", "unpaid", "cancelled", "refunded"})
    void ineligibleRegistrationCannotSelectEither(String reason) {
        register(1, "member".equals(reason) ? 31 : 30, "owner_type".equals(reason) ? 1 : 2,
                "owner_id".equals(reason) ? 102 : 101, "unpaid".equals(reason) ? 0 : "refunded".equals(reason) ? 4 : 2,
                "cancelled".equals(reason) ? 3 : 2);
        assertDenied(30L);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cms_registration", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "unpublished", "deleted", "unapproved"})
    void unavailableActivityRejectsCreatorAndPaidMember(String reason) {
        register(1, 30, 2, 101, 2, 2);
        String mutation = "cancelled".equals(reason) ? "cancel_time=NOW()"
                : "unpublished".equals(reason) ? "publish_status=0"
                : "deleted".equals(reason) ? "del_flag=2" : "status=0";
        jdbc.update("UPDATE cms_activity SET " + mutation + " WHERE id=101");
        assertDenied(10L);
        assertDenied(30L);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cms_registration", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cms_activity", Integer.class));
    }

    private void register(long id, long member, int ownerType, long owner, int payment, int registration) {
        jdbc.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,"
                + "member_id,original_amount,total_amount,payable_amount,payment_status,registration_status,pay_expire_time) "
                + "VALUES(?,?,?,?,1,0,?,0,0,0,?,?,DATE_ADD(NOW(),INTERVAL 1 DAY))", id, "synthetic-" + id, ownerType, owner, member, payment, registration);
    }

    private void assertAdmitted(long member) {
        Map<String, Object> snapshot = mapper.selectReferenceSnapshot("ACTIVITY", 101L, member);
        assertNotNull(snapshot);
        assertEquals(101L, ((Number) snapshot.get("reference_id")).longValue());
        assertEquals("synthetic owner activity", snapshot.get("title"));
        assertEquals("cover", snapshot.get("cover_url"));
        assertEquals(List.of(101L), optionIds(member));
    }

    private void assertDenied(long member) {
        assertNull(mapper.selectReferenceSnapshot("ACTIVITY", 101L, member));
        assertTrue(optionIds(member).isEmpty());
    }

    private List<Long> optionIds(long member) {
        return mapper.selectReferenceOptions("ACTIVITY", member, 20).stream()
                .map(row -> ((Number) row.get("id")).longValue()).collect(Collectors.toList());
    }
}

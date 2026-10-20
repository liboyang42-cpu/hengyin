package com.chengyinhub.business.mapper;

import com.chengyinhub.business.service.support.LegacyCmsProvenanceGuard;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes the production mapper XML against H2, with the real guard and no mocked mapper.
 * Minimal schema models only columns consumed by the guard; this is not migration application,
 * MySQL collation/locking validation, writer integration or authorization/license acceptance.
 */
class LegacyCmsProvenanceGuardMyBatisTest {
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private LegacyCmsProvenanceMapper mapper;
    private LegacyCmsProvenanceGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:legacy_provenance_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table cms_member_template(id bigint primary key, w18_provenance_kind varchar(32) null)");
        jdbc.execute("create table w18_cms_template_provenance(template_id bigint primary key)");
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("legacy-provenance-h2",
                new SpringManagedTransactionFactory(), dataSource));
        String resource = "mapper/business/LegacyCmsProvenanceMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration))
                .getMapper(LegacyCmsProvenanceMapper.class);
        guard = new LegacyCmsProvenanceGuard(mapper);
    }

    private void template(long id, String marker) {
        jdbc.update("insert into cms_member_template(id,w18_provenance_kind) values(?,?)", id, marker);
    }

    private void sidecar(long id) {
        jdbc.update("insert into w18_cms_template_provenance(template_id) values(?)", id);
    }

    private void blocked(long id) {
        assertEquals(Integer.valueOf(1), mapper.selectLegacyBlockState(id));
        assertThrows(ServiceException.class, () -> guard.requireLegacy(id));
    }

    @Test
    void existingRowWithNullMarkerAndNoSidecarIsLegacy() {
        template(1, null);
        assertEquals(Integer.valueOf(0), mapper.selectLegacyBlockState(1L));
        assertDoesNotThrow(() -> guard.requireLegacy(1L));
    }

    @Test
    void managedMarkerWithoutSidecarBlocks() {
        template(1, "W18_MANAGED");
        blocked(1);
    }

    @Test
    void emptyUnknownAndWhitespaceMarkersWithoutSidecarsAllBlock() {
        String[] markers = {"", "UNKNOWN_FUTURE", " ", "\t"};
        for (int i = 0; i < markers.length; i++) {
            long id = i + 1;
            template(id, markers[i]);
            blocked(id);
        }
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "select count(*) from w18_cms_template_provenance", Integer.class));
    }

    @Test
    void nullMarkerWithSidecarBlocks() {
        template(1, null);
        sidecar(1);
        blocked(1);
    }

    @Test
    void bothSignalsBlockAndOtherRowsSidecarDoesNotBlockLegacyRow() {
        template(1, "W18_MANAGED");
        sidecar(1);
        template(2, null);
        blocked(1);
        assertDoesNotThrow(() -> guard.requireLegacy(2L));
        assertEquals(Integer.valueOf(0), mapper.selectLegacyBlockState(2L));
    }

    @Test
    void missingCanonicalRowIsRejectedEvenWhenOrphanSidecarExists() {
        assertNull(mapper.selectLegacyBlockState(1L));
        assertThrows(ServiceException.class, () -> guard.requireLegacy(1L));
        sidecar(1);
        assertNull(mapper.selectLegacyBlockState(1L));
        assertThrows(ServiceException.class, () -> guard.requireLegacy(1L));
    }

    @Test
    void invalidIdsCannotBeAcceptedAsLegacy() {
        for (Long id : new Long[] {null, 0L, -1L}) {
            assertThrows(ServiceException.class, () -> guard.requireLegacy(id));
        }
    }

    @Test
    void missingSidecarTablePropagatesDatabaseFailure() {
        template(1, null);
        jdbc.execute("drop table w18_cms_template_provenance");
        assertDatabaseFailure();
    }

    @Test
    void missingMarkerColumnPropagatesDatabaseFailure() {
        template(1, null);
        jdbc.execute("alter table cms_member_template drop column w18_provenance_kind");
        assertDatabaseFailure();
    }

    @Test
    void missingCanonicalTablePropagatesDatabaseFailure() {
        jdbc.execute("drop table cms_member_template");
        assertDatabaseFailure();
    }

    @Test
    void missingSidecarIdentityColumnPropagatesDatabaseFailure() {
        template(1, null);
        jdbc.execute("alter table w18_cms_template_provenance rename column template_id to unavailable_id");
        assertDatabaseFailure();
    }

    @Test
    void missingConnectionSchemaPropagatesDatabaseFailureAtGuardCall() {
        // H2 does not permit dropping PUBLIC. Change the real mapper datasource so its
        // next connection requests a nonexistent schema; only requireLegacy opens it.
        dataSource.setUrl(dataSource.getUrl() + ";SCHEMA=missing_provenance_schema");
        assertDatabaseFailure();
    }

    @Test
    void componentRemainsPresentAcrossUnrelatedEnvironmentValues() {
        // No authoritative writer property name is available. This deliberately proves only
        // unconditional guard registration, not a real paid/materializer writer-off scenario.
        template(1, "W18_MANAGED");
        template(2, null);
        for (String value : new String[] {"false", "true"}) {
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                        "unrelated-test-properties", Collections.<String, Object>singletonMap(
                                "test.unrelated.feature.enabled", value)));
                context.registerBean(LegacyCmsProvenanceMapper.class, () -> mapper);
                context.register(LegacyCmsProvenanceGuard.class);
                context.refresh();
                assertEquals(value, context.getEnvironment().getProperty("test.unrelated.feature.enabled"));
                LegacyCmsProvenanceGuard actual = context.getBean(LegacyCmsProvenanceGuard.class);
                assertThrows(ServiceException.class, () -> actual.requireLegacy(1L));
                assertDoesNotThrow(() -> actual.requireLegacy(2L));
            }
        }
    }

    private void assertDatabaseFailure() {
        DataAccessException failure = assertThrows(DataAccessException.class, () -> guard.requireLegacy(1L));
        assertTrue(failure.getMostSpecificCause() instanceof java.sql.SQLException,
                "The real JDBC failure must remain visible; no legacy fallback is allowed");
    }
}

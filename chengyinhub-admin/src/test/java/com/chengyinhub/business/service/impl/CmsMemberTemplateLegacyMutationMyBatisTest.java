package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.LegacyCmsTemplateReadMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.support.LegacyCmsMutationGuard;
import com.chengyinhub.business.service.support.LegacyCmsTemplateBodyReader;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.io.Reader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Real production services, Spring transaction advice, MyBatis XML and H2. No mocked mapper.
 * Fixture schema is adapted from existing test DDL, not a production migration or MySQL proof. */
class CmsMemberTemplateLegacyMutationMyBatisTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private ICmsMemberTemplateService service;
    private CmsMemberTemplateServiceImpl rawService;
    private final SqlTrace trace = new SqlTrace();

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:cms_mutation_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(source);
        StringBuilder fixture = new StringBuilder();
        try (Reader reader = Resources.getResourceAsReader("it-schema/journey-full-flow-it-schema.sql")) {
            char[] buffer = new char[4096]; int read;
            while ((read = reader.read(buffer)) != -1) fixture.append(buffer, 0, read);
        }
        Matcher ddl = Pattern.compile("(?is)CREATE TABLE `cms_member_template`\\s*\\(.*?;").matcher(fixture);
        assertTrue(ddl.find(), "Existing template fixture DDL must be present");
        jdbc.execute(ddl.group().replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", ""));
        jdbc.execute("alter table cms_member_template add w18_provenance_kind varchar(32) null");
        jdbc.execute("create table w18_cms_template_provenance(template_id bigint primary key)");
        manager = new DataSourceTransactionManager(source);
        Configuration config = new Configuration(new Environment("cms-mutation-h2", new SpringManagedTransactionFactory(), source));
        config.getTypeAliasRegistry().registerAlias("CmsMemberTemplate", CmsMemberTemplate.class);
        config.getTypeAliasRegistry().registerAlias("MemberTemplateVO", MemberTemplateVO.class);
        config.addInterceptor(trace);
        for (String name : Arrays.asList("CmsMemberTemplateMapper", "LegacyCmsTemplateReadMapper")) {
            String resource = "mapper/business/" + name + ".xml";
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        LegacyCmsMutationGuard guard = proxy(new LegacyCmsMutationGuard(sql.getMapper(LegacyCmsTemplateReadMapper.class)));
        rawService = new CmsMemberTemplateServiceImpl();
        ReflectionTestUtils.setField(rawService, "cmsMemberTemplateMapper", sql.getMapper(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(rawService, "legacyCmsMutationGuard", guard);
        ReflectionTestUtils.setField(rawService, "legacyCmsTemplateBodyReader", proxy(new LegacyCmsTemplateBodyReader(
                sql.getMapper(LegacyCmsTemplateReadMapper.class), sql.getMapper(CmsMemberTemplateMapper.class))));
        service = proxy(rawService);
        trace.events.clear();
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
    private void seed(long id, Long origin, String marker, boolean sidecar) {
        jdbc.update("insert into cms_member_template(id,member_id,original_template_id,w18_provenance_kind,title) values(?,7,?,?,?)",
                id, origin, marker, "before");
        if (sidecar) jdbc.update("insert into w18_cms_template_provenance(template_id) values(?)", id);
    }
    private CmsMemberTemplate patch(Long id, Long claimedOrigin) {
        CmsMemberTemplate value = new CmsMemberTemplate(); value.setId(id);
        value.setOriginalTemplateId(claimedOrigin); value.setTitle("after"); return value;
    }
    private int count() { return jdbc.queryForObject("select count(*) from cms_member_template", Integer.class); }
    private String title(long id) { return jdbc.queryForObject("select title from cms_member_template where id=?", String.class, id); }
    private Long origin(long id) { return jdbc.queryForObject("select original_template_id from cms_member_template where id=?", Long.class, id); }
    private int sidecars() { return jdbc.queryForObject("select count(*) from w18_cms_template_provenance", Integer.class); }

    @Test void normalUnmarkedInsertStillUsesExistingMapper() {
        CmsMemberTemplate value = patch(null, 0L); value.setMemberId(7L);
        assertEquals(1, service.insertCmsMemberTemplate(value)); assertNotNull(value.getId());
        assertNull(jdbc.queryForObject("select w18_provenance_kind from cms_member_template where id=?", String.class, value.getId()));
    }
    @Test void anyNonNullInputMarkerCannotEnterOrdinaryInsert() {
        for (String marker : new String[] {"W18_MANAGED", "", "UNKNOWN", " "}) {
            CmsMemberTemplate value = patch(null, 0L); value.setW18ProvenanceKind(marker);
            assertThrows(ServiceException.class, () -> service.insertCmsMemberTemplate(value));
        }
        assertThrows(ServiceException.class, () -> service.insertCmsMemberTemplate(null));
        assertEquals(0, count()); assertTrue(trace.events.isEmpty());
    }
    @Test void updatePreservesPersistedPositiveSourceWhenInputOmitsZerosOrReplacesIt() {
        seed(1, 3001L, null, false);
        for (Long claimed : new Long[] {null, 0L, 9999L}) {
            assertEquals(1, service.updateCmsMemberTemplate(patch(1L, claimed)));
            assertEquals(Long.valueOf(3001L), origin(1));
        }
        assertEquals("after", title(1));
        assertEquals("lock:1", trace.events.get(0));
        assertEquals("canonical", trace.events.get(1));
        assertEquals("update", trace.events.get(2));
    }
    @Test void updatePreservesPersistedNullSourceInsteadOfConvertingItToZero() {
        seed(1, null, null, false);
        assertEquals(1, service.updateCmsMemberTemplate(patch(1L, 0L)));
        assertNull(origin(1));
    }
    @Test void nullBeanMarkerDoesNotBypassPersistentMarkerOrSidecar() {
        seed(1, 3001L, "W18_MANAGED", false); seed(2, 3002L, null, true);
        seed(3, 3003L, "", false); seed(4, 3004L, "UNKNOWN", true);
        for (long id = 1; id <= 4; id++) {
            final long selected = id;
            assertThrows(ServiceException.class, () -> service.updateCmsMemberTemplate(patch(selected, 0L)));
            assertEquals("before", title(id)); assertEquals(Long.valueOf(3000 + id), origin(id));
        }
        assertEquals(2, sidecars()); assertFalse(trace.events.contains("update"));
    }
    @Test void updateRejectsMarkedInputAndMissingOrInvalidCanonicalRows() {
        seed(1, 3001L, null, false);
        CmsMemberTemplate marked = patch(1L, 0L); marked.setW18ProvenanceKind("");
        assertThrows(ServiceException.class, () -> service.updateCmsMemberTemplate(marked));
        for (Long id : new Long[] {null, 0L, -1L, 999L}) {
            assertThrows(ServiceException.class, () -> service.updateCmsMemberTemplate(patch(id, 0L)));
        }
        assertEquals("before", title(1));
    }
    @Test void protectedSingleDeleteKeepsCanonicalAndSidecar() {
        seed(1, 3001L, null, true);
        assertThrows(ServiceException.class, () -> service.deleteCmsMemberTemplateById(1L));
        assertEquals(1, count()); assertEquals(1, sidecars()); assertFalse(trace.events.contains("delete"));
    }
    @Test void batchChecksEverySortedDistinctRowBeforeOneDelete() {
        seed(1, 0L, null, false); seed(2, 0L, null, false);
        // Orphan evidence is deliberately retained, never cleaned up by a generic delete.
        jdbc.update("insert into w18_cms_template_provenance values(999)");
        assertEquals(2, service.deleteCmsMemberTemplateByIds(new Long[] {2L, 1L, 2L}));
        assertEquals(Arrays.asList("lock:1", "lock:2", "delete"), trace.events);
        assertEquals(0, count()); assertEquals(1, sidecars());
    }
    @Test void mixedLegacyAndProtectedBatchDoesNotPartiallyDelete() {
        seed(1, 0L, null, false); seed(2, 3001L, null, true);
        assertThrows(ServiceException.class, () -> service.deleteCmsMemberTemplateByIds(new Long[] {2L, 1L}));
        assertEquals(Arrays.asList("lock:1", "lock:2"), trace.events);
        assertEquals(2, count()); assertEquals(1, sidecars());
    }
    @Test void missingOrInvalidBatchIdsNeverDeleteExistingRows() {
        seed(1, 0L, null, false);
        for (Long[] ids : new Long[][] {{1L, 999L}, {1L, null}, {1L, 0L}, null}) {
            assertThrows(ServiceException.class, () -> service.deleteCmsMemberTemplateByIds(ids));
            assertEquals(1, count());
        }
        assertEquals(0, service.deleteCmsMemberTemplateByIds(new Long[0]));
        assertFalse(trace.events.contains("delete"));
    }
    @Test void guardFailureRollsBackEarlierMutationInSameSpringTransaction() {
        seed(1, 3001L, null, false); seed(2, 3002L, "W18_MANAGED", true);
        assertThrows(ServiceException.class, () -> new TransactionTemplate(manager).execute(status -> {
            service.updateCmsMemberTemplate(patch(1L, 0L));
            service.deleteCmsMemberTemplateByIds(new Long[] {1L, 2L});
            return null;
        }));
        assertEquals("before", title(1)); assertEquals(Long.valueOf(3001L), origin(1));
        assertEquals(2, count()); assertEquals(1, sidecars());
    }
    @Test void missingSidecarTableFailsWithoutWriteFallback() {
        seed(1, 3001L, null, false); jdbc.execute("drop table w18_cms_template_provenance");
        assertThrows(DataAccessException.class, () -> service.updateCmsMemberTemplate(patch(1L, 0L)));
        assertEquals("before", title(1)); assertEquals(Long.valueOf(3001L), origin(1));
    }
    @Test void missingOrReadOnlyTransactionCannotReachWrite() {
        seed(1, 3001L, null, false);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () -> rawService.updateCmsMemberTemplate(patch(1L, 0L)));
        TransactionTemplate readOnly = new TransactionTemplate(manager); readOnly.setReadOnly(true);
        assertThrows(ServiceException.class, () -> readOnly.execute(status -> service.updateCmsMemberTemplate(patch(1L, 0L))));
        assertEquals("before", title(1)); assertFalse(trace.events.contains("update"));
    }

    /** Observes real MyBatis calls; it never substitutes SQL results or skips execution. */
    @Intercepts({@Signature(type=Executor.class, method="query", args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}),
            @Signature(type=Executor.class, method="update", args={MappedStatement.class,Object.class})})
    public static class SqlTrace implements Interceptor {
        final List<String> events = new ArrayList<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String id = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (id.endsWith(".selectCanonicalStateForUpdate"))
                events.add("lock:" + ((Map<?,?>) invocation.getArgs()[1]).get("templateId"));
            else if (id.endsWith(".selectCmsMemberTemplateByIdsForUpdate")) events.add("canonical");
            else if (id.endsWith(".updateCmsMemberTemplate")) events.add("update");
            else if (id.endsWith(".deleteCmsMemberTemplateByIds") || id.endsWith(".deleteCmsMemberTemplateById")) events.add("delete");
            return invocation.proceed();
        }
        @Override public Object plugin(Object target) { return Plugin.wrap(target, this); }
        @Override public void setProperties(Properties properties) { }
    }
}

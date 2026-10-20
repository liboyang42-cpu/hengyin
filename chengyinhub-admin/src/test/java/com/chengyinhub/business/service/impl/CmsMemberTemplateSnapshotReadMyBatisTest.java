package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.LegacyCmsTemplateReadMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.support.LegacyCmsMutationGuard;
import com.chengyinhub.business.service.support.LegacyCmsTemplateBodyReader;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.Reader;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Real service/reader beans, transaction proxies and production MyBatis XML on H2; no mocks.
 * Demonstrates statement-level body/provenance filtering, not MySQL concurrency or license rights. */
class CmsMemberTemplateSnapshotReadMyBatisTest {
    private static final String SNAPSHOT = LegacyCmsTemplateReadMapper.class.getName() + ".selectLegacySnapshotById";
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private ICmsMemberTemplateService service;
    private LegacyCmsTemplateBodyReader reader;
    private DataSourceTransactionManager transactions;
    private QueryTrace trace;

    @org.springframework.context.annotation.Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration { }

    @BeforeEach void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:cms_snapshot_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver"); jdbc = new JdbcTemplate(source);
        StringBuilder ddlSource = new StringBuilder();
        try (Reader input = Resources.getResourceAsReader("it-schema/journey-full-flow-it-schema.sql")) {
            char[] buffer = new char[4096]; int n;
            while ((n = input.read(buffer)) != -1) ddlSource.append(buffer, 0, n);
        }
        Matcher ddl = Pattern.compile("(?is)CREATE TABLE `cms_member_template`\\s*\\(.*?;").matcher(ddlSource);
        assertTrue(ddl.find());
        jdbc.execute(ddl.group().replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", ""));
        jdbc.execute("alter table cms_member_template add w18_provenance_kind varchar(32) null");
        jdbc.execute("create table w18_cms_template_provenance(template_id bigint primary key)");
        Configuration configuration = new Configuration(new Environment("cms-snapshot-h2", new SpringManagedTransactionFactory(), source));
        configuration.getTypeAliasRegistry().registerAlias("CmsMemberTemplate", CmsMemberTemplate.class);
        configuration.getTypeAliasRegistry().registerAlias("MemberTemplateVO", MemberTemplateVO.class);
        trace = new QueryTrace(); configuration.addInterceptor(trace);
        for (String name : Arrays.asList("CmsMemberTemplateMapper", "LegacyCmsTemplateReadMapper")) {
            String resource = "mapper/business/" + name + ".xml";
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        transactions = new DataSourceTransactionManager(source);
        context = new AnnotationConfigApplicationContext();
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> transactions);
        context.registerBean(CmsMemberTemplateMapper.class, () -> sql.getMapper(CmsMemberTemplateMapper.class));
        context.registerBean(LegacyCmsTemplateReadMapper.class, () -> sql.getMapper(LegacyCmsTemplateReadMapper.class));
        context.register(TransactionConfiguration.class, LegacyCmsTemplateBodyReader.class,
                LegacyCmsMutationGuard.class, CmsMemberTemplateServiceImpl.class);
        context.refresh();
        service = context.getBean(ICmsMemberTemplateService.class);
        reader = context.getBean(LegacyCmsTemplateBodyReader.class);
    }
    @AfterEach void close() { if (context != null) context.close(); }

    private void seed(long id, String marker, boolean sidecar) {
        jdbc.update("insert into cms_member_template(id,member_id,original_template_id,title,question_answer,"
                + "medal_name,status,publish_status,del_flag,w18_provenance_kind) values(?,7,3001,'body','answer','medal',0,0,0,?)", id, marker);
        if (sidecar) jdbc.update("insert into w18_cms_template_provenance(template_id) values(?)", id);
    }
    private void assertOnlySnapshotQuery() {
        assertEquals(Collections.singletonList(SNAPSHOT), trace.ids);
        assertEquals(1, trace.sql.size());
        String sql = trace.sql.get(0).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        assertTrue(sql.contains("question_answer"));
        assertTrue(sql.contains("w18_provenance_kind is null"));
        assertTrue(sql.contains("not exists"));
        assertTrue(sql.contains("provenance.template_id = cms_member_template.id"));
        assertFalse(sql.contains("for update"));
    }

    @Test void legacyBodyUsesFullExistingResultMapInOneUnfilteredByOwnerOrStatusProjection() {
        seed(1, null, false);
        CmsMemberTemplate row = service.selectCmsMemberTemplateById(1L);
        assertNotNull(row); assertEquals("answer", row.getQuestionAnswer());
        assertEquals("medal", row.getMedalName()); assertEquals(Long.valueOf(3001L), row.getOriginalTemplateId());
        assertEquals(Long.valueOf(7L), row.getMemberId()); assertEquals(Long.valueOf(0L), row.getStatus());
        assertEquals(Long.valueOf(0L), row.getPublishStatus());
        assertOnlySnapshotQuery();
        assertEquals(Collections.singletonList(false), trace.active);
    }
    @Test void snapshotReadWorksInsideExistingReadOnlyTransactionWithoutLockUpgrade() {
        seed(1, null, false);
        TransactionTemplate readOnly = new TransactionTemplate(transactions); readOnly.setReadOnly(true);
        CmsMemberTemplate row = readOnly.execute(status -> service.selectCmsMemberTemplateById(1L));
        assertNotNull(row); assertOnlySnapshotQuery();
        assertEquals(Collections.singletonList(true), trace.active);
        assertEquals(Collections.singletonList(true), trace.readOnly);
    }
    @Test void everyNonNullMarkerIsHiddenWithoutSecondBodyQuery() {
        long id = 1;
        for (String marker : new String[] {"W18_MANAGED", "", "UNKNOWN", " "}) {
            seed(id, marker, false); trace.clear();
            assertNull(service.selectCmsMemberTemplateById(id)); assertOnlySnapshotQuery(); id++;
        }
    }
    @Test void sidecarAloneAndBothSignalsAreHidden() {
        seed(1, null, true); seed(2, "W18_MANAGED", true);
        assertNull(service.selectCmsMemberTemplateById(1L)); assertOnlySnapshotQuery(); trace.clear();
        assertNull(service.selectCmsMemberTemplateById(2L)); assertOnlySnapshotQuery();
    }
    @Test void missingRowIsNullAndUnrelatedOrphanDoesNotHideLegacyRow() {
        seed(1, null, false); jdbc.update("insert into w18_cms_template_provenance values(999)");
        assertNull(service.selectCmsMemberTemplateById(999L)); assertOnlySnapshotQuery(); trace.clear();
        assertNotNull(service.selectCmsMemberTemplateById(1L)); assertOnlySnapshotQuery();
    }
    @Test void invalidIdsRemainNullWithoutAnyQuery() {
        for (Long id : new Long[] {null, 0L, -1L}) assertNull(service.selectCmsMemberTemplateById(id));
        assertTrue(trace.ids.isEmpty());
    }
    @Test void missingSidecarTablePropagatesWithoutFallback() {
        seed(1, null, false); jdbc.execute("drop table w18_cms_template_provenance");
        assertThrows(DataAccessException.class, () -> service.selectCmsMemberTemplateById(1L));
        assertEquals(Collections.singletonList(SNAPSHOT), trace.ids);
    }
    @Test void missingMarkerColumnPropagatesWithoutFallback() {
        seed(1, null, false); jdbc.execute("alter table cms_member_template drop column w18_provenance_kind");
        assertThrows(DataAccessException.class, () -> service.selectCmsMemberTemplateById(1L));
        assertEquals(Collections.singletonList(SNAPSHOT), trace.ids);
    }
    @Test void repeatedReadUsesFreshStatementRatherThanCachedAllowedBody() {
        seed(1, null, false);
        assertNotNull(service.selectCmsMemberTemplateById(1L));
        jdbc.update("insert into w18_cms_template_provenance values(1)");
        assertNull(service.selectCmsMemberTemplateById(1L));
        assertEquals(Arrays.asList(SNAPSHOT, SNAPSHOT), trace.ids);
    }
    @Test void existingLockingReaderKeepsItsLockThenBodyContract() {
        seed(1, null, false);
        assertNotNull(reader.findLegacy(1L));
        assertEquals(Arrays.asList(LegacyCmsTemplateReadMapper.class.getName()+".selectCanonicalStateForUpdate",
                CmsMemberTemplateMapper.class.getName()+".selectCmsMemberTemplateById"), trace.ids);
        assertTrue(trace.sql.get(0).toLowerCase(Locale.ROOT).contains("for update"));
        assertEquals(Arrays.asList(true, true), trace.active);
        assertEquals(Arrays.asList(false, false), trace.readOnly);
    }
    @Test void genericListsHideProtectedBodiesWhileInternalEvidenceReadsRemainVisible() {
        seed(1, null, false); seed(2, "W18_MANAGED", true);
        List<CmsMemberTemplate> batch = service.selectCmsMemberTemplateByIds(Arrays.asList(1L, 2L));
        List<CmsMemberTemplate> list = service.selectCmsMemberTemplateList(new CmsMemberTemplate());
        assertEquals(1, batch.size()); assertEquals(Long.valueOf(1L), batch.get(0).getId());
        assertEquals(1, list.size()); assertEquals(Long.valueOf(1L), list.get(0).getId());
        assertFalse(trace.ids.contains(SNAPSHOT));
        CmsMemberTemplateMapper raw = context.getBean(CmsMemberTemplateMapper.class);
        assertEquals(Long.valueOf(2L), raw.selectCmsMemberTemplateById(2L).getId());
        List<CmsMemberTemplate> locked = new TransactionTemplate(transactions).execute(status ->
                raw.selectCmsMemberTemplateByIdsForUpdate(Arrays.asList(1L, 2L)));
        assertNotNull(locked); assertEquals(2, locked.size());
        assertEquals(Long.valueOf(1L), locked.get(0).getId());
        assertEquals(Long.valueOf(2L), locked.get(1).getId());
    }

    @Intercepts(@Signature(type=Executor.class, method="query", args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    public static class QueryTrace implements Interceptor {
        final List<String> ids = new ArrayList<>(); final List<String> sql = new ArrayList<>();
        final List<Boolean> active = new ArrayList<>(); final List<Boolean> readOnly = new ArrayList<>();
        void clear() { ids.clear(); sql.clear(); active.clear(); readOnly.clear(); }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            ids.add(statement.getId()); sql.add(statement.getBoundSql(invocation.getArgs()[1]).getSql());
            active.add(TransactionSynchronizationManager.isActualTransactionActive());
            readOnly.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return invocation.proceed();
        }
        @Override public Object plugin(Object target) { return Plugin.wrap(target, this); }
        @Override public void setProperties(Properties properties) { }
    }
}

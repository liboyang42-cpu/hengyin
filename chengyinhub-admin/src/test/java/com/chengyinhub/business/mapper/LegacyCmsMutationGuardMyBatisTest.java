package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.service.support.LegacyCmsMutationGuard;
import com.chengyinhub.common.exception.ServiceException;
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
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring AOP/transactions and production MyBatis XML/H2; not MySQL lock acceptance. */
class LegacyCmsMutationGuardMyBatisTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private LegacyCmsMutationGuard guard;
    private Trace trace;

    @org.springframework.context.annotation.Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions { }

    @BeforeEach void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:mutation_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table cms_member_template(id bigint primary key,w18_provenance_kind varchar(32),title varchar(64))");
        jdbc.execute("create table w18_cms_template_provenance(template_id bigint primary key)");
        jdbc.execute("create table rollback_probe(id bigint primary key)");
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("mutation-h2", new SpringManagedTransactionFactory(), dataSource));
        trace = new Trace(); configuration.addInterceptor(trace);
        configuration.getTypeAliasRegistry().registerAlias("CmsMemberTemplate", CmsMemberTemplate.class);
        configuration.getTypeAliasRegistry().registerAlias("MemberTemplateVO", com.chengyinhub.business.domain.vo.MemberTemplateVO.class);
        // ReadMapper also exposes a snapshot statement that reuses the canonical body result map.
        for (String resource : java.util.Arrays.asList("mapper/business/CmsMemberTemplateMapper.xml",
                "mapper/business/LegacyCmsTemplateReadMapper.xml")) {
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        context = new AnnotationConfigApplicationContext();
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> manager);
        context.registerBean(LegacyCmsTemplateReadMapper.class, () -> session.getMapper(LegacyCmsTemplateReadMapper.class));
        context.register(Transactions.class, LegacyCmsMutationGuard.class); context.refresh();
        guard = context.getBean(LegacyCmsMutationGuard.class);
        transaction = new TransactionTemplate(manager);
    }
    @AfterEach void close() { if (context != null) context.close(); }
    private void row(long id, String marker) {
        jdbc.update("insert into cms_member_template(id,w18_provenance_kind,title) values(?,?,'before')", id, marker);
    }
    private CmsMemberTemplate bean(long id, String marker) {
        CmsMemberTemplate bean = new CmsMemberTemplate(); bean.setId(id); bean.setW18ProvenanceKind(marker); return bean;
    }
    private void run(Runnable work) { transaction.execute(status -> { work.run(); return null; }); }

    @Test void mandatoryProxyRejectsEveryApiWithoutAmbientTransaction() {
        assertTrue(AopUtils.isAopProxy(guard)); row(1, null);
        assertThrows(IllegalTransactionStateException.class, () -> guard.requireLegacyForMutation(1L));
        assertThrows(IllegalTransactionStateException.class, () -> guard.requireLegacyForMutations(Collections.singletonList(1L)));
        assertThrows(IllegalTransactionStateException.class, () -> guard.requireGenericApprovalAllowed(bean(1, null), "audit"));
        assertThrows(IllegalTransactionStateException.class, () -> guard.requireGenericApprovalsAllowed(Collections.singletonList(bean(1, null)), "bundle"));
        assertTrue(trace.ids.isEmpty());
    }
    @Test void acceptsLegacyInCallerWriteTransaction() {
        row(1, null); run(() -> {
            guard.requireLegacyForMutation(1L);
            jdbc.update("update cms_member_template set title='after' where id=1");
        });
        assertEquals("after", jdbc.queryForObject("select title from cms_member_template where id=1", String.class));
        assertEquals(Collections.singletonList(1L), trace.ids);
    }
    @Test void everyNonNullMarkerAndSidecarBlocks() {
        long id = 0;
        for (String marker : Arrays.asList("W18_MANAGED", "", " ", "UNKNOWN_FUTURE")) {
            final long target = ++id; row(target, marker);
            assertThrows(ServiceException.class, () -> run(() -> guard.requireLegacyForMutation(target)));
        }
        row(9, null); jdbc.update("insert into w18_cms_template_provenance(template_id) values(9)");
        assertThrows(ServiceException.class, () -> run(() -> guard.requireLegacyForMutation(9L)));
    }
    @Test void callerWriteBeforeRejectionActuallyRollsBackInDatabase() {
        row(1, "W18_MANAGED");
        assertThrows(ServiceException.class, () -> run(() -> {
            jdbc.update("update cms_member_template set title='uncommitted' where id=1");
            jdbc.update("insert into rollback_probe(id) values(1)");
            guard.requireLegacyForMutation(1L);
        }));
        assertEquals("before", jdbc.queryForObject("select title from cms_member_template where id=1", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from rollback_probe", Integer.class));
    }
    @Test void caughtGuardExceptionStillMarksCallerTransactionRollbackOnly() {
        row(1, "W18_MANAGED");
        assertThrows(UnexpectedRollbackException.class, () -> run(() -> {
            jdbc.update("insert into rollback_probe(id) values(1)");
            try { guard.requireLegacyForMutation(1L); } catch (ServiceException expected) { /* caller cannot commit */ }
        }));
        assertEquals(0, jdbc.queryForObject("select count(*) from rollback_probe", Integer.class));
    }
    @Test void batchSortsDeduplicatesAndRejectsInvalidBeforeAnyQuery() {
        row(1, null); row(2, null); row(3, null);
        run(() -> guard.requireLegacyForMutations(Arrays.asList(3L, 1L, 3L, 2L)));
        assertEquals(Arrays.asList(1L, 2L, 3L), trace.ids); trace.ids.clear();
        assertThrows(ServiceException.class, () -> run(() -> guard.requireLegacyForMutations(Arrays.asList(1L, null))));
        assertTrue(trace.ids.isEmpty());
        for (Long id : new Long[] {null, 0L, -1L, 999L})
            assertThrows(ServiceException.class, () -> run(() -> guard.requireLegacyForMutation(id)));
    }
    @Test void readonlyCallerIsNotSilentlyUpgraded() {
        row(1, null); transaction.setReadOnly(true);
        assertThrows(ServiceException.class, () -> run(() -> guard.requireLegacyForMutation(1L)));
        assertTrue(trace.ids.isEmpty());
    }
    @Test void schemaFailurePropagatesAndRollsBackCallerWrite() {
        row(1, null); jdbc.execute("drop table w18_cms_template_provenance");
        assertThrows(DataAccessException.class, () -> run(() -> {
            jdbc.update("insert into rollback_probe(id) values(1)"); guard.requireLegacyForMutation(1L);
        }));
        assertEquals(0, jdbc.queryForObject("select count(*) from rollback_probe", Integer.class));
    }
    @Test void arbitraryFalseEnvironmentPropertyCannotDisableGuard() {
        // Deliberately NOT named as lane2's unknown real writer toggle; only config-independence evidence.
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-only-unrelated-property",
                Collections.<String,Object>singletonMap("test.unrelated.writer.enabled", "false")));
        row(1, "W18_MANAGED");
        assertThrows(ServiceException.class, () -> run(() -> guard.requireLegacyForMutation(1L)));
    }
    @Test void approvalChecksDatabaseSidecarEvenWhenBeanMarkerIsNull() {
        row(1, null); jdbc.update("insert into w18_cms_template_provenance(template_id) values(1)");
        assertThrows(ServiceException.class, () -> run(() -> guard.requireGenericApprovalAllowed(bean(1, null), "audit")));
        assertEquals(Collections.singletonList(1L), trace.ids);
    }
    @Test void approvalRejectsNonNullBeanMarkerAndPreflightsWholeBatchBeforeWrites() {
        row(1, null); row(2, null);
        assertThrows(ServiceException.class, () -> run(() -> guard.requireGenericApprovalAllowed(bean(1, ""), "audit")));
        assertTrue(trace.ids.isEmpty());
        jdbc.update("insert into w18_cms_template_provenance(template_id) values(2)");
        assertThrows(ServiceException.class, () -> run(() -> {
            guard.requireGenericApprovalsAllowed(Arrays.asList(bean(2, null), bean(1, null)), "bundle");
            jdbc.update("update cms_member_template set title='approved'");
        }));
        assertEquals(Arrays.asList(1L, 2L), trace.ids);
        assertEquals(2, jdbc.queryForObject("select count(*) from cms_member_template where title='before'", Integer.class));
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    public static class Trace implements Interceptor {
        final List<Long> ids = new ArrayList<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            Map<?, ?> parameters = (Map<?, ?>) invocation.getArgs()[1];
            ids.add((Long) parameters.get("templateId"));
            return invocation.proceed();
        }
        @Override public Object plugin(Object target) { return Plugin.wrap(target, this); }
        @Override public void setProperties(Properties properties) { }
    }
}

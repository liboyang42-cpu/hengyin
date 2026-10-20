package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.mapper.LegacyCmsTemplateReadMapper;
import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** Source-state fixture only. Existing tests still own their CMS/projection mocks.
 * Both production guards use the actual production marker-OR-sidecar MyBatis statement,
 * join one real writable H2/Spring transaction and retain their MANDATORY advice.
 * A missing row, marker or sidecar never falls back to an ordinary legacy template.
 */
public final class LegacyCmsUnitGuardFixture implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final DataSourceTransactionManager manager;
    private final TransactionStatus transaction;
    public final LegacyCmsMutationGuard mutationGuard;
    public final LegacyCmsTemplateApprovalBoundary approvalBoundary;

    public LegacyCmsUnitGuardFixture(long... ordinaryTemplateIds) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:legacy_unit_guard_" + java.util.UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        jdbc.execute("CREATE TABLE cms_member_template(id BIGINT PRIMARY KEY,w18_provenance_kind VARCHAR(64))");
        jdbc.execute("CREATE TABLE w18_cms_template_provenance(template_id BIGINT PRIMARY KEY)");
        for (long id : ordinaryTemplateIds) {
            if (id <= 0) throw new IllegalArgumentException("Explicit positive fixture template ID required");
            jdbc.update("INSERT INTO cms_member_template(id,w18_provenance_kind) VALUES(?,NULL)", id);
        }
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("legacy-unit-guard", new SpringManagedTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAlias("CmsMemberTemplate", CmsMemberTemplate.class);
        configuration.getTypeAliasRegistry().registerAlias("MemberTemplateVO", MemberTemplateVO.class);
        for (String path : new String[] { "mapper/business/CmsMemberTemplateMapper.xml", "mapper/business/LegacyCmsTemplateReadMapper.xml" }) {
            try (InputStream input = Resources.getResourceAsStream(path)) {
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        LegacyCmsTemplateReadMapper mapper = sql.getMapper(LegacyCmsTemplateReadMapper.class);
        mutationGuard = proxy(new LegacyCmsMutationGuard(mapper), LegacyCmsMutationGuard.class);
        approvalBoundary = proxy(new LegacyCmsTemplateApprovalBoundary(mapper), LegacyCmsTemplateApprovalBoundary.class);
        transaction = manager.getTransaction(new DefaultTransactionDefinition());
    }

    private <T> T proxy(T target, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }

    public void addSidecar(long templateId) {
        jdbc.update("INSERT INTO w18_cms_template_provenance(template_id) VALUES(?)", templateId);
    }

    @Override public void close() {
        if (!transaction.isCompleted()) manager.rollback(transaction);
        jdbc.execute("SHUTDOWN");
    }
}

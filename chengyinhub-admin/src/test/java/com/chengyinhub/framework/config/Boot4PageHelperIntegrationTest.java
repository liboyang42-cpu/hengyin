package com.chengyinhub.framework.config;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import com.chengyinhub.common.core.domain.BaseEntity;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInterceptor;
import com.github.pagehelper.autoconfigure.PageHelperAutoConfiguration;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production factory + real starter auto-configuration + real JDBC SQL on isolated H2.
 * H2 MySQL mode proves wiring/count/limit only; it does not validate MySQL schema,
 * server-specific SQL, locking, isolation, concurrency, or the application's business mappers.
 */
class Boot4PageHelperIntegrationTest
{
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(SupportedStackApplicationProperties::initialize)
            .withConfiguration(AutoConfigurations.of(MybatisAutoConfiguration.class,
                    PageHelperAutoConfiguration.class))
            .withUserConfiguration(MyBatisConfig.class, H2Configuration.class)
            .withPropertyValues(
                    "mybatis.typeAliasesPackage=com.chengyinhub.common.core.domain",
                    "mybatis.mapperLocations=classpath:supported-stack/PaginationContractMapper.xml");

    @BeforeEach
    @AfterEach
    void clearPageState()
    {
        PageHelper.clearPage();
    }

    @Test
    void productionFactoryAndXmlRemainAuthoritativeWithExactlyOnePageInterceptor()
    {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SqlSessionFactory.class)
                    .hasSingleBean(SqlSessionTemplate.class).hasSingleBean(PageHelperAutoConfiguration.class);
            SqlSessionFactory factory = context.getBean(SqlSessionFactory.class);
            String factoryBeanName = context.getBeanFactory().getBeanDefinition("sqlSessionFactory")
                    .getFactoryBeanName();
            assertThat(context.getBean(factoryBeanName)).isInstanceOf(MyBatisConfig.class);
            assertThat(context.getBean(SqlSessionTemplate.class).getSqlSessionFactory()).isSameAs(factory);
            assertThat(context.getEnvironment().getProperty("mybatis.configLocation"))
                    .isEqualTo("classpath:mybatis/mybatis-config.xml");
            assertThat(factory.getConfiguration().isUseGeneratedKeys()).isTrue();
            assertThat(factory.getConfiguration().getDefaultExecutorType()).isEqualTo(ExecutorType.SIMPLE);
            assertThat(factory.getConfiguration().getTypeAliasRegistry().resolveAlias("BaseEntity"))
                    .isEqualTo(BaseEntity.class);
            assertThat(factory.getConfiguration().hasMapper(PaginationMapper.class)).isTrue();
            assertThat(factory.getConfiguration().getInterceptors()).hasSize(1)
                    .allMatch(PageInterceptor.class::isInstance);
            assertThat(context.getEnvironment().getProperty("pagehelper.helperDialect")).isEqualTo("mysql");

            // The starter must also avoid double attachment if initialization is repeated.
            context.getBean(PageHelperAutoConfiguration.class).afterPropertiesSet();
            assertThat(factory.getConfiguration().getInterceptors()).hasSize(1)
                    .allMatch(PageInterceptor.class::isInstance);
        });
    }

    @Test
    void paginationExecutesCountAndMysqlLimitAndDoesNotLeakIntoNextQuery()
    {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            try (Connection anchor = createDatabase(context))
            {
                SqlRecorder recorder = recordSql(context);
                PaginationMapper mapper = context.getBean(SqlSessionTemplate.class).getMapper(PaginationMapper.class);
                PageHelper.startPage(2, 2);
                List<Long> results = mapper.selectIds();
                assertThat(results).isInstanceOf(Page.class).containsExactly(3L, 4L);
                Page<?> page = (Page<?>) results;
                assertThat(page.getTotal()).isEqualTo(5);
                assertThat(page.getPageNum()).isEqualTo(2);
                assertThat(page.getPageSize()).isEqualTo(2);
                assertThat(page.getPages()).isEqualTo(3);
                assertThat(recorder.sql).hasSize(2);
                assertThat(recorder.sql.get(0)).contains("count(", "supported_stack_page");
                assertThat(recorder.sql.get(1)).matches(".*\\blimit\\s+\\?\\s*,\\s*\\?$");
                assertThat(PageHelper.getLocalPage()).isNull();

                recorder.sql.clear();
                assertThat(mapper.selectIds()).containsExactly(1L, 2L, 3L, 4L, 5L);
                assertThat(recorder.sql).hasSize(1);
                assertThat(recorder.sql.get(0)).doesNotContain("limit", "count(");
                assertThat(context.getBean(SqlSessionFactory.class).getConfiguration().getInterceptors().stream()
                        .filter(PageInterceptor.class::isInstance).count()).isEqualTo(1);
            }
        });
    }

    @Test
    void outOfRangePageRetainsTotalAndClearsThreadStateAfterCountShortCircuit()
    {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            try (Connection anchor = createDatabase(context))
            {
                SqlRecorder recorder = recordSql(context);
                PaginationMapper mapper = context.getBean(SqlSessionTemplate.class).getMapper(PaginationMapper.class);
                PageHelper.startPage(4, 2);
                List<Long> results = mapper.selectIds();
                assertThat(results).isInstanceOf(Page.class).isEmpty();
                assertThat(((Page<?>) results).getTotal()).isEqualTo(5);
                assertThat(recorder.sql).hasSize(1);
                assertThat(recorder.sql.get(0)).contains("count(");
                assertThat(PageHelper.getLocalPage()).isNull();

                recorder.sql.clear();
                assertThat(mapper.selectIds()).hasSize(5);
                assertThat(recorder.sql).hasSize(1);
                assertThat(recorder.sql.get(0)).doesNotContain("limit", "count(");
            }
        });
    }

    private static Connection createDatabase(AssertableApplicationContext context) throws Exception
    {
        // Keeping this real connection open preserves the in-memory database; closing it
        // after the template sessions close drops the database without DB_CLOSE_DELAY=-1.
        Connection anchor = context.getBean(DataSource.class).getConnection();
        try (Statement statement = anchor.createStatement())
        {
            assertThat(anchor.getMetaData().getDatabaseProductName()).isEqualTo("H2");
            statement.execute("CREATE TABLE supported_stack_page (id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO supported_stack_page (id) VALUES (1), (2), (3), (4), (5)");
            return anchor;
        }
        catch (Exception | AssertionError exception)
        {
            anchor.close();
            throw exception;
        }
    }

    private static SqlRecorder recordSql(AssertableApplicationContext context)
    {
        SqlRecorder recorder = new SqlRecorder();
        context.getBean(SqlSessionFactory.class).getConfiguration().addInterceptor(recorder);
        return recorder;
    }

    public interface PaginationMapper
    {
        List<Long> selectIds();
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = { Connection.class, Integer.class }))
    static class SqlRecorder implements Interceptor
    {
        final List<String> sql = new ArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable
        {
            String statement = ((StatementHandler) invocation.getTarget()).getBoundSql().getSql();
            sql.add(statement.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT));
            return invocation.proceed();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class H2Configuration
    {
        @Bean
        DataSource dataSource()
        {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUrl("jdbc:h2:mem:boot4_pagehelper_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            return dataSource;
        }
    }
}

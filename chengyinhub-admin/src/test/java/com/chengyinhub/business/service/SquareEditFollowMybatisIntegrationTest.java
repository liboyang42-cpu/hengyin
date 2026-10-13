package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.UmsCreativeSquareMapper;
import com.chengyinhub.business.mapper.UmsMemberFollowMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.UmsCreativeSquareServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberFollowServiceImpl;
import com.chengyinhub.web.controller.api.ApiCreativeSquareController;
import com.chengyinhub.web.controller.api.ApiUmsMemberController;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * R10-06/07 真实 MyBatis/MySQL8:编辑显式清空落到库、未提交字段不动;
 * 关注目标态幂等(丢响应重试不反转),旧 toggle 仍兼容。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SquareEditFollowMybatisIntegrationTest {

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("square_follow_it");
    private static final Long ME = 9L;

    private DataSource dataSource;
    private ApiCreativeSquareController squareController;
    private ApiUmsMemberController followController;

    private static class SquareStubController extends ApiCreativeSquareController {
        @Override
        public Long getAppUserId() { return ME; }
    }

    private static class FollowStubController extends ApiUmsMemberController {
        @Override
        public Long getAppUserId() { return ME; }
    }

    @BeforeAll
    void createDatabaseAndServices() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        SqlSessionTemplate template = new SqlSessionTemplate(buildFactory(dataSource));

        UmsCreativeSquareServiceImpl squareService = new UmsCreativeSquareServiceImpl();
        ReflectionTestUtils.setField(squareService, "umsCreativeSquareMapper", template.getMapper(UmsCreativeSquareMapper.class));
        squareController = new SquareStubController();
        // 手工 new 不走 @Value 默认值,旧写门禁显式开闸(与 Spring 运行时默认一致)。
        ReflectionTestUtils.setField(squareController, "legacyCommunityWritesEnabled", true);
        ReflectionTestUtils.setField(squareController, "umsCreativeSquareService", squareService);
        ReflectionTestUtils.setField(squareController, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(squareController, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        ReflectionTestUtils.setField(squareController, "cmsActivityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(squareController, "cmsRegistrationService", mock(ICmsRegistrationService.class));

        PlatformTransactionManager tx = new DataSourceTransactionManager(dataSource);
        UmsMemberFollowServiceImpl followTarget = new UmsMemberFollowServiceImpl();
        ReflectionTestUtils.setField(followTarget, "umsMemberFollowMapper", template.getMapper(UmsMemberFollowMapper.class));
        ReflectionTestUtils.setField(followTarget, "umsMemberMapper", template.getMapper(UmsMemberMapper.class));
        IUmsMemberFollowService followService = transactionalProxy(followTarget, IUmsMemberFollowService.class, tx);
        followController = new FollowStubController();
        ReflectionTestUtils.setField(followController, "umsMemberFollowService", followService);
        // FOLLOWED 分支会发关注通知;本 IT 证的是关注行语义,通知副作用以 mock 隔离。
        ReflectionTestUtils.setField(followController, "communityPostService", mock(ICommunityPostService.class));
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP TABLE IF EXISTS ums_creative_square", "DROP TABLE IF EXISTS ums_member_follow", "DROP TABLE IF EXISTS ums_member");
        execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, status INT, del_flag INT) ENGINE=InnoDB",
                "INSERT INTO ums_member(id,status,del_flag) VALUES (9,1,0),(88,1,0)",
                "CREATE TABLE ums_creative_square (id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, contents TEXT, pics VARCHAR(2000),"
                        + " file_url VARCHAR(2000), address VARCHAR(500), longitude VARCHAR(64), latitude VARCHAR(64), data_id BIGINT, data_type BIGINT,"
                        + " like_num BIGINT, nolike_num BIGINT, status INT, is_crowdfunding INT, del_flag INT, check_status INT,"
                        + " route_preview_img VARCHAR(2000), request_id VARCHAR(64) NULL, request_signature VARCHAR(64) NULL,"
                        + " create_by VARCHAR(64), create_time DATETIME(3), update_by VARCHAR(64), update_time DATETIME(3),"
                        + " UNIQUE KEY uk_creative_square_member_request (member_id, request_id)) ENGINE=InnoDB",
                "CREATE TABLE ums_member_follow (id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, follow_member_id BIGINT,"
                        + " create_by VARCHAR(64), create_time DATETIME(3), update_by VARCHAR(64), update_time DATETIME(3)) ENGINE=InnoDB",
                "INSERT INTO ums_creative_square(id,member_id,contents,pics,file_url,address,longitude,latitude,status,del_flag,is_crowdfunding,"
                        + "route_preview_img) VALUES (50,9,'旧正文','old-a;old-b','old-file','旧地点','1.1','2.2',1,0,1,'old-route')");
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @Test
    void editExplicitEmptyClearsPersistedMediaAndLocation() throws Exception {
        squareController.creativeSquareActoin("50", "新正文", "", "", "", "", "", null, "", "", null);

        assertEquals("新正文", string("SELECT contents FROM ums_creative_square WHERE id=50"));
        assertEquals("", string("SELECT pics FROM ums_creative_square WHERE id=50"));
        assertEquals("", string("SELECT address FROM ums_creative_square WHERE id=50"));
        assertEquals("", string("SELECT longitude FROM ums_creative_square WHERE id=50"));
        assertEquals("", string("SELECT latitude FROM ums_creative_square WHERE id=50"));
        assertEquals(1, number("SELECT status FROM ums_creative_square WHERE id=50"));
    }

    @Test
    void editOmittedFieldsKeepPersistedValues() throws Exception {
        squareController.creativeSquareActoin("50", "只改正文", null, null, null, null, null, null, null, null, null);

        assertEquals("只改正文", string("SELECT contents FROM ums_creative_square WHERE id=50"));
        assertEquals("old-a;old-b", string("SELECT pics FROM ums_creative_square WHERE id=50"));
        assertEquals("旧地点", string("SELECT address FROM ums_creative_square WHERE id=50"));
        assertEquals("1.1", string("SELECT longitude FROM ums_creative_square WHERE id=50"));
        assertEquals("2.2", string("SELECT latitude FROM ums_creative_square WHERE id=50"));
        assertEquals("old-file", string("SELECT file_url FROM ums_creative_square WHERE id=50"));
        assertEquals("old-route", string("SELECT route_preview_img FROM ums_creative_square WHERE id=50"));
        assertEquals(1, number("SELECT is_crowdfunding FROM ums_creative_square WHERE id=50"));
    }

    @Test
    void editPartialClearOnlyTouchesSubmittedField() throws Exception {
        squareController.creativeSquareActoin("50", "部分清", null, "", null, null, null, null, null, null, null);

        assertEquals("", string("SELECT file_url FROM ums_creative_square WHERE id=50"));
        assertEquals("old-a;old-b", string("SELECT pics FROM ums_creative_square WHERE id=50"));
        assertEquals("旧地点", string("SELECT address FROM ums_creative_square WHERE id=50"));
    }

    @Test
    void publishWithoutMediaPersistsNull() throws Exception {
        squareController.creativeSquareActoin(null, "新发正文", "", "", "", "", "", null, "", "", null);

        assertEquals(1, number("SELECT COUNT(*) FROM ums_creative_square WHERE id <> 50"));
        assertNull(string("SELECT pics FROM ums_creative_square WHERE id <> 50"));
        assertNull(string("SELECT address FROM ums_creative_square WHERE id <> 50"));
    }

    @Test
    void followTargetStateIsIdempotentAcrossRetries() throws Exception {
        followController.userFollowActoin("88", "1");
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=9 AND follow_member_id=88"));

        // 丢响应后的同一意图重试:不得反转
        followController.userFollowActoin("88", "1");
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=9 AND follow_member_id=88"));

        followController.userFollowActoin("88", "0");
        assertEquals(0, number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=9 AND follow_member_id=88"));

        // 取消丢响应后的重试:不得重新关注
        followController.userFollowActoin("88", "0");
        assertEquals(0, number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=9 AND follow_member_id=88"));
    }

    @Test
    void legacyToggleCallerStillWorks() throws Exception {
        followController.userFollowActoin("88");
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=9 AND follow_member_id=88"));

        followController.userFollowActoin("88");
        assertEquals(0, number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=9 AND follow_member_id=88"));
    }

    @Test
    void followTargetStateDoesNotTouchOtherMembers() throws Exception {
        execute("INSERT INTO ums_member_follow(id,member_id,follow_member_id) VALUES (7,77,88)");

        followController.userFollowActoin("88", "1");
        followController.userFollowActoin("88", "0");

        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=77 AND follow_member_id=88"),
                "只允许操作当前登录会员自己的关注关系");
        assertEquals(0, number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=9"));
    }

    private int number(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String string(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void execute(String... sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : sql) {
                s.execute(statement);
            }
        }
    }

    private SqlSessionFactory buildFactory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("square-follow-it",
                new SpringManagedTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(configuration, UmsCreativeSquareMapper.class, "mapper/business/UmsCreativeSquareMapper.xml");
        addMapper(configuration, UmsMemberFollowMapper.class, "mapper/business/UmsMemberFollowMapper.xml");
        addMapper(configuration, UmsMemberMapper.class, "mapper/business/UmsMemberMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void addMapper(Configuration configuration, Class<?> mapper, String resource) throws Exception {
        configuration.addMapper(mapper);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到 MyBatis XML: " + resource);
            }
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(Object target, Class<T> iface, PlatformTransactionManager tx) {
        TransactionInterceptor interceptor = new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(iface);
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}

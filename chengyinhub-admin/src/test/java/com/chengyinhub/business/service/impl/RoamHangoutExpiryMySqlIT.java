package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Date;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Dedicated VPS MySQL only; no scheduler, account or external channel calls. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_HANGOUT_EXPIRY_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoamHangoutExpiryMySqlIT {
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String schema;
    private RoamHangoutServiceImpl service;
    private IImService realIm;
    private RoamHangoutMapper hangouts;

    @BeforeAll
    void setup() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_PORT", "3306"));
        assertTrue(port > 0 && port <= 65535);
        String user = System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_USER", "root");
        String password = System.getenv().getOrDefault("CHENGYIN_MYBATIS_IT_PASSWORD", "");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = "cy_hangout_expiry_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + schema);
        DriverManagerDataSource source = new DriverManagerDataSource(base + schema + options, user, password);
        db = new JdbcTemplate(source);
        Path sql = Paths.get("chengyinhub-admin/sql");
        if (!Files.isDirectory(sql)) sql = Paths.get("sql");
        String hangoutSql = new String(Files.readAllBytes(sql.resolve("migration_roam_hangout_20260905.sql")), StandardCharsets.UTF_8);
        // Keep the production table DDL; do not register a real scheduler in this fixture.
        hangoutSql = hangoutSql.substring(0, hangoutSql.indexOf("-- 过期关局任务"));
        new ResourceDatabasePopulator(new FileSystemResource(sql.resolve("migration_im.sql").toFile()),
                new ByteArrayResource(hangoutSql.getBytes(StandardCharsets.UTF_8))).execute(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/RoamHangoutMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        hangouts = session.getMapper(RoamHangoutMapper.class);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        ImServiceImpl target = new ImServiceImpl();
        ReflectionTestUtils.setField(target, "conversationMapper", session.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(target, "memberMapper", session.getMapper(ImConversationMemberMapper.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        realIm = (IImService) proxy.getProxy();
        service = new RoamHangoutServiceImpl();
        ReflectionTestUtils.setField(service, "roamHangoutMapper", hangouts);
        ReflectionTestUtils.setField(service, "transactionManager", manager);
        ReflectionTestUtils.setField(service, "imService", realIm);
    }

    @BeforeEach
    void seed() {
        db.update("delete from roam_hangout");
        db.update("delete from im_conversation_member");
        db.update("delete from im_conversation");
        db.update("insert into im_conversation(id,type,biz_key,status) values(101,4,'hangout_1',0),(102,4,'hangout_2',0)");
        db.update("insert into im_conversation_member(conversation_id,member_id,unread_count) values(101,9,3),(102,9,4)");
        db.update("insert into roam_hangout(id,member_id,title,latitude,longitude,start_at,conversation_id) values"
                + "(1,9,'first',0,0,date_sub(now(), interval 2 day),101),"
                + "(2,9,'second',0,0,date_sub(now(), interval 2 day),102)");
        ReflectionTestUtils.setField(service, "imService", realIm);
    }

    @AfterAll
    void cleanup() {
        if (admin != null && schema != null) admin.execute("drop database " + schema);
    }

    @Test
    void imFailureRollsBackBothStatesKeepsFailedItemSelectableAndOtherItemCommits() {
        IImService failing = mock(IImService.class, org.mockito.AdditionalAnswers.delegatesTo(realIm));
        doAnswer(call -> {
            realIm.closeHangoutGroup(1L);
            throw new IllegalStateException("after IM writes before commit");
        }).when(failing).closeHangoutGroup(1L);
        ReflectionTestUtils.setField(service, "imService", failing);
        assertEquals(1, service.expireDue(new Date()));
        assertState(1, 0, 0, 0, 3);
        assertState(2, 1, 1, 1, 0);
        assertEquals(1, hangouts.selectExpired(new Date()).size());
        ReflectionTestUtils.setField(service, "imService", realIm);
        assertEquals(1, service.expireDue(new Date()));
        assertState(1, 1, 1, 1, 0);
        assertEquals(0, service.expireDue(new Date()));
    }

    private void assertState(long id, int hangoutStatus, int conversationStatus, int deleted, int unread) {
        assertEquals(Integer.valueOf(hangoutStatus), db.queryForObject("select status from roam_hangout where id=?", Integer.class, id));
        assertEquals(Integer.valueOf(conversationStatus), db.queryForObject("select status from im_conversation where id=?", Integer.class, 100 + id));
        assertEquals(Integer.valueOf(deleted), db.queryForObject("select deleted from im_conversation_member where conversation_id=?", Integer.class, 100 + id));
        assertEquals(Integer.valueOf(unread), db.queryForObject("select unread_count from im_conversation_member where conversation_id=?", Integer.class, 100 + id));
    }
}

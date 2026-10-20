package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUDIT-G2-5:模板市场 use_num 只记「每个会员首次采用」。同一会员从同一库模板反复新建/发布个人玩法
 * (含删掉再建)不得重复 +1;另一个会员首次采用照常 +1。走真实 mapper + 本机 scratch MySQL。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateUseNumFirstAdoptionMybatisIT {

    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private CmsTemplateLibraryMapper mapper;

    @BeforeAll
    void setUp() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("use_num");
        assertTrue(config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")
                        || config.adminUrl().startsWith("jdbc:mysql://localhost:"),
                "只允许本机 scratch MySQL");
        database = "chengyin_use_num_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database);
        DriverManagerDataSource source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE cms_template_library (id BIGINT PRIMARY KEY, use_num INT NULL) ENGINE=InnoDB");
        db.execute("CREATE TABLE cms_member_template (id BIGINT PRIMARY KEY, member_id BIGINT,"
                + " original_template_id BIGINT, del_flag INT DEFAULT 0) ENGINE=InnoDB");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain,com.chengyinhub.business.domain.vo");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsTemplateLibraryMapper.xml"));
        SqlSessionFactory sessionFactory = factory.getObject();
        mapper = new SqlSessionTemplate(sessionFactory).getMapper(CmsTemplateLibraryMapper.class);
    }

    @AfterAll
    void tearDown() {
        if (database != null && admin != null) {
            admin.execute("DROP DATABASE " + database);
        }
    }

    @BeforeEach
    void seed() {
        db.update("DELETE FROM cms_member_template");
        db.update("DELETE FROM cms_template_library");
        db.update("INSERT INTO cms_template_library(id, use_num) VALUES (9001, NULL)");
    }

    private int useNum() {
        Integer n = db.queryForObject("SELECT IFNULL(use_num, 0) FROM cms_template_library WHERE id = 9001", Integer.class);
        return n == null ? 0 : n;
    }

    private void adopt(long memberTemplateId, long memberId, int delFlag) {
        db.update("INSERT INTO cms_member_template(id, member_id, original_template_id, del_flag) VALUES (?,?,?,?)",
                memberTemplateId, memberId, 9001L, delFlag);
    }

    @Test
    void sameMemberAdoptingAgainDoesNotInflate() {
        adopt(1, 7, 0);
        assertEquals(1, mapper.incrementUseNumOnFirstAdoption(9001L, 7L, 1L));
        adopt(2, 7, 0);
        assertEquals(0, mapper.incrementUseNumOnFirstAdoption(9001L, 7L, 2L));
        assertEquals(1, useNum(), "同一会员第二次采用不得再 +1");
    }

    @Test
    void deleteAndRecreateDoesNotInflate() {
        adopt(1, 7, 0);
        mapper.incrementUseNumOnFirstAdoption(9001L, 7L, 1L);
        db.update("UPDATE cms_member_template SET del_flag = 2 WHERE id = 1");
        adopt(3, 7, 0);
        mapper.incrementUseNumOnFirstAdoption(9001L, 7L, 3L);
        assertEquals(1, useNum(), "删掉再建仍是同一会员的采用");
    }

    @Test
    void anotherMemberFirstAdoptionCounts() {
        adopt(1, 7, 0);
        mapper.incrementUseNumOnFirstAdoption(9001L, 7L, 1L);
        adopt(4, 8, 0);
        assertEquals(1, mapper.incrementUseNumOnFirstAdoption(9001L, 8L, 4L));
        assertEquals(2, useNum());
    }
}

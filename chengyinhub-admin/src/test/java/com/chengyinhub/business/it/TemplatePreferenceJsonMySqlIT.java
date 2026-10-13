package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.domain.vo.TemplateLibraryVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 偏好 JSON 必须在真实 MySQL JSON 列上保持可写、可清理，且不破坏普通模板。 */
class TemplatePreferenceJsonMySqlIT {

    @Test
    void mapperNeverWritesEmptyJsonAndClearsConfigurationWhenLeavingVm6() throws Exception {
        SqlSessionFactory factory = factory();
        try (SqlSession session = factory.openSession(true)) {
            Connection connection = session.getConnection();
            createTables(connection);
            ItSchema.applyMigrations(connection,
                    "sql/migration_20260821_new_city_preference_library.sql");

            assertEquals("json", scalar(connection,
                    "SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema=DATABASE() "
                            + "AND table_name='cms_template_library' AND column_name='preference_json'"));
            assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO cms_member_template(title,validation_method,preference_json) "
                            + "VALUES ('negative',3,'')"),
                    "负控：MySQL JSON 必须真的拒绝空串，否则本测试无法保护发布链");

            CmsMemberTemplate member = member("普通模板", 3, "");
            session.getMapper(CmsMemberTemplateMapper.class).insertCmsMemberTemplate(member);
            assertNull(scalar(connection,
                    "SELECT preference_json FROM cms_member_template WHERE id=" + member.getId()));

            CmsTemplateLibrary library = library("普通模板库", 3, "");
            session.getMapper(CmsTemplateLibraryMapper.class).insertCmsTemplateLibrary(library);
            assertNull(scalar(connection,
                    "SELECT preference_json FROM cms_template_library WHERE id=" + library.getId()));

            member.setValidationMethod(6);
            member.setPreferenceJson("{\"dimensions\":[\"slow\"]}");
            session.getMapper(CmsMemberTemplateMapper.class).updateCmsMemberTemplate(member);
            assertEquals("{\"dimensions\": [\"slow\"]}", scalar(connection,
                    "SELECT preference_json FROM cms_member_template WHERE id=" + member.getId()));

            member.setValidationMethod(3);
            member.setPreferenceJson(null);
            session.getMapper(CmsMemberTemplateMapper.class).updateCmsMemberTemplate(member);
            assertNull(scalar(connection,
                    "SELECT preference_json FROM cms_member_template WHERE id=" + member.getId()));

            library.setValidationMethod(6);
            library.setPreferenceJson("{\"dimensions\":[\"slow\"]}");
            session.getMapper(CmsTemplateLibraryMapper.class).updateCmsTemplateLibrary(library);
            assertEquals("{\"dimensions\": [\"slow\"]}", scalar(connection,
                    "SELECT preference_json FROM cms_template_library WHERE id=" + library.getId()));
            library.setValidationMethod(3);
            library.setPreferenceJson(null);
            session.getMapper(CmsTemplateLibraryMapper.class).updateCmsTemplateLibrary(library);
            assertNull(scalar(connection,
                    "SELECT preference_json FROM cms_template_library WHERE id=" + library.getId()));
        }
    }

    private static SqlSessionFactory factory() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsMemberTemplate", CmsMemberTemplate.class);
        configuration.getTypeAliasRegistry().registerAlias("CmsTemplateLibrary", CmsTemplateLibrary.class);
        configuration.getTypeAliasRegistry().registerAlias("MemberTemplateVO", MemberTemplateVO.class);
        configuration.getTypeAliasRegistry().registerAlias("TemplateLibraryVO", TemplateLibraryVO.class);
        PooledDataSource dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("template_preference_json"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        configuration.setEnvironment(new Environment("it", new JdbcTransactionFactory(), dataSource));
        parse(configuration, "mapper/business/CmsMemberTemplateMapper.xml");
        parse(configuration, "mapper/business/CmsTemplateLibraryMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void parse(Configuration configuration, String resource) throws Exception {
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private static void createTables(Connection connection) throws Exception {
        execute(connection, "CREATE TABLE cms_member_template (id BIGINT NOT NULL AUTO_INCREMENT, "
                + "title VARCHAR(255), validation_method INT, question_option_media_json VARCHAR(2000), "
                + "preference_json JSON NULL, sensor_type VARCHAR(32), sensor_config JSON, "
                + "PRIMARY KEY(id)) ENGINE=InnoDB");
        execute(connection, "CREATE TABLE cms_template_library (id BIGINT NOT NULL AUTO_INCREMENT, "
                + "title VARCHAR(255), validation_method INT, question_option_media_json VARCHAR(2000), "
                + "sensor_type VARCHAR(32), sensor_config JSON, PRIMARY KEY(id)) ENGINE=InnoDB");
    }

    private static CmsMemberTemplate member(String title, int validationMethod, String preferenceJson) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setTitle(title);
        template.setValidationMethod(validationMethod);
        template.setPreferenceJson(preferenceJson);
        return template;
    }

    private static CmsTemplateLibrary library(String title, int validationMethod, String preferenceJson) {
        CmsTemplateLibrary template = new CmsTemplateLibrary();
        template.setTitle(title);
        template.setValidationMethod(validationMethod);
        template.setPreferenceJson(preferenceJson);
        return template;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}

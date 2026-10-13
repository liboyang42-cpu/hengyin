package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R9-49 真实 Mapper + MySQL 更新→回读:验证「清空音频/换新音频」后 audio_duration 不再残留,
 * 且 patch 未提供 audioUrl 时不会误清其他音频字段。
 *
 * <p>走真实 {@code CmsMemberTemplateMapper.xml} 与 MySQL(独立 scratch 库),不用 H2 模拟。
 * 由 {@code CHENGYIN_FUNDS_MYSQL_IT=1} 门禁启用;库名随机且隔离,只连本机 3317 类 scratch。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CmsMemberTemplateAudioPatchMybatisIntegrationTest {

    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private CmsMemberTemplateMapper mapper;

    @BeforeAll
    void setUp() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("audio");
        assertTrue(config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")
                        || config.adminUrl().startsWith("jdbc:mysql://localhost:"),
                "只允许本机 scratch MySQL");
        database = "chengyin_audio_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database);
        DriverManagerDataSource source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE cms_member_template ("
                + "id BIGINT PRIMARY KEY, member_id BIGINT, title VARCHAR(255),"
                + " audio_url VARCHAR(500), audio_duration INT, update_time DATETIME) ENGINE=InnoDB");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain,com.chengyinhub.business.domain.vo");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsMemberTemplateMapper.xml"));
        SqlSessionFactory sessionFactory = factory.getObject();
        mapper = new SqlSessionTemplate(sessionFactory).getMapper(CmsMemberTemplateMapper.class);
    }

    @AfterAll
    void tearDown() {
        if (database != null && admin != null) {
            admin.execute("DROP DATABASE " + database);
        }
    }

    private void seed(String audioUrl, Integer duration) {
        db.update("DELETE FROM cms_member_template");
        db.update("INSERT INTO cms_member_template(id, member_id, title, audio_url, audio_duration)"
                + " VALUES(1, 77, '旧标题', ?, ?)", audioUrl, duration);
    }

    private Map<String, Object> read() {
        return db.queryForMap("SELECT title, audio_url, audio_duration FROM cms_member_template WHERE id=1");
    }

    private CmsMemberTemplate patch() {
        CmsMemberTemplate p = new CmsMemberTemplate();
        p.setId(1L);
        return p;
    }

    @Test
    void clearAudioClearsUrlAndDurationTogether() {
        seed("https://cdn/old.mp3", 600);
        CmsMemberTemplate p = patch();
        p.setAudioUrl("");
        p.setAudioDuration(null);
        assertEquals(1, mapper.updateCmsMemberTemplate(p));

        Map<String, Object> row = read();
        assertEquals("", row.get("audio_url"));
        assertNull(row.get("audio_duration"), "清空音频后旧时长必须一起清掉,实际:" + row);
    }

    @Test
    void titleOnlyPatchLeavesAudioUrlAndDurationUntouched() {
        seed("https://cdn/old.mp3", 600);
        CmsMemberTemplate p = patch();
        p.setTitle("只改标题");
        assertEquals(1, mapper.updateCmsMemberTemplate(p));

        Map<String, Object> row = read();
        assertEquals("只改标题", row.get("title"));
        assertEquals("https://cdn/old.mp3", row.get("audio_url"), "未提供 audioUrl 不得动音频地址");
        assertEquals(600, ((Number) row.get("audio_duration")).intValue(), "未提供音频字段不得清掉时长");
    }

    @Test
    void replacingAudioWithoutDurationDoesNotInheritOldDuration() {
        seed("https://cdn/old.mp3", 600);
        CmsMemberTemplate p = patch();
        p.setAudioUrl("https://cdn/new.mp3");
        p.setAudioDuration(null);
        assertEquals(1, mapper.updateCmsMemberTemplate(p));

        Map<String, Object> row = read();
        assertEquals("https://cdn/new.mp3", row.get("audio_url"));
        assertNull(row.get("audio_duration"), "换新音频不能继承旧时长,实际:" + row);
    }

    @Test
    void sameUrlWithoutDurationKeepsKnownDuration() {
        seed("https://cdn/same.mp3", 600);
        CmsMemberTemplate p = patch();
        p.setAudioUrl("https://cdn/same.mp3");
        p.setAudioDuration(null);
        assertEquals(1, mapper.updateCmsMemberTemplate(p));

        Map<String, Object> row = read();
        assertEquals("https://cdn/same.mp3", row.get("audio_url"));
        assertEquals(600, ((Number) row.get("audio_duration")).intValue(),
                "同地址未给时长必须保留已知时长,不能被误清,实际:" + row);
    }

    @Test
    void caseOnlyChangeIsANewObjectAndDoesNotInheritDuration() {
        // 表 collation 是 CI:`A.mp3` 与 `a.mp3` 默认会判等;OSS path 大小写不同是不同对象,
        // 必须按 BINARY 精确比较,大小写变化一律按「换了文件」清旧时长。
        seed("https://cdn/A.mp3", 600);
        CmsMemberTemplate p = patch();
        p.setAudioUrl("https://cdn/a.mp3");
        p.setAudioDuration(null);
        assertEquals(1, mapper.updateCmsMemberTemplate(p));

        Map<String, Object> row = read();
        assertEquals("https://cdn/a.mp3", row.get("audio_url"));
        assertNull(row.get("audio_duration"),
                "仅大小写不同即不同对象,不得继承旧时长 600,实际:" + row);
    }

    @Test
    void emptyUrlWithDurationClearsBothInsteadOfStoringContradiction() {
        seed("https://cdn/old.mp3", 600);
        CmsMemberTemplate p = patch();
        p.setAudioUrl("");
        p.setAudioDuration(120);
        assertEquals(1, mapper.updateCmsMemberTemplate(p));

        Map<String, Object> row = read();
        assertEquals("", row.get("audio_url"));
        assertNull(row.get("audio_duration"),
                "明确清空 URL 时时长一律清空,不管请求时长,不得留 ('',120) 矛盾态,实际:" + row);
    }

    @Test
    void replacingAudioWithDurationStoresBoth() {
        seed("https://cdn/old.mp3", 600);
        CmsMemberTemplate p = patch();
        p.setAudioUrl("https://cdn/new.mp3");
        p.setAudioDuration(120);
        assertEquals(1, mapper.updateCmsMemberTemplate(p));

        Map<String, Object> row = read();
        assertEquals("https://cdn/new.mp3", row.get("audio_url"));
        assertEquals(120, ((Number) row.get("audio_duration")).intValue());
    }

    @Test
    void durationOnlyPatchKeepsAudioUrl() {
        seed("https://cdn/old.mp3", 600);
        CmsMemberTemplate p = patch();
        p.setAudioDuration(90);
        assertEquals(1, mapper.updateCmsMemberTemplate(p));

        Map<String, Object> row = read();
        assertEquals("https://cdn/old.mp3", row.get("audio_url"), "只改时长不得动地址");
        assertEquals(90, ((Number) row.get("audio_duration")).intValue());
    }

    @Test
    void nonPositiveProvidedDurationIsNotWrittenAsContradiction() {
        // 真实业务合同:语音时长(秒)必须是正数。新地址配 0 秒不得写成 0,应视为未提供 → NULL。
        seed("https://cdn/old.mp3", 600);
        CmsMemberTemplate p = patch();
        p.setAudioUrl("https://cdn/new.mp3");
        p.setAudioDuration(0);
        assertEquals(1, mapper.updateCmsMemberTemplate(p));

        Map<String, Object> row = read();
        assertEquals("https://cdn/new.mp3", row.get("audio_url"));
        assertNull(row.get("audio_duration"), "非正数时长不得落库成矛盾态,实际:" + row);
    }
}

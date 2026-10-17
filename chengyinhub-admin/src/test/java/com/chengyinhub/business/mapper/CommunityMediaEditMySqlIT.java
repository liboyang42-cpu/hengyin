package com.chengyinhub.business.mapper;

import com.chengyinhub.business.it.MybatisItDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Actual mapper XML and retained media DDL in a fresh localhost schema. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityMediaEditMySqlIT {
    private JdbcTemplate jdbc;
    private CommunityPostMapper mapper;
    private String database;

    @BeforeAll
    void setup() throws Exception {
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()));
        String suite = "cmediaedit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        DriverManagerDataSource source = new DriverManagerDataSource(MybatisItDatabase.url(suite),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        database = MybatisItDatabase.databaseName(suite);
        jdbc = new JdbcTemplate(source);
        assertEquals(database, jdbc.queryForObject("SELECT DATABASE()", String.class));
        String migration = Files.readString(Path.of("sql/migration_community_post_platform_20260909.sql"));
        int start = migration.indexOf("CREATE TABLE IF NOT EXISTS community_post_media (");
        assertTrue(start >= 0);
        jdbc.execute(migration.substring(start, migration.indexOf(";", start)));
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CommunityPostMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(CommunityPostMapper.class);
    }

    @BeforeEach
    void clear() { jdbc.update("DELETE FROM community_post_media"); }

    @AfterAll
    void cleanup() {
        if (jdbc != null && database != null) {
            assertTrue(database.matches("chengyin_mybatis_cmediaedit_[a-z0-9_]+"));
            jdbc.execute("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void editingBodyKeepsHeldMediaOnTheSameOwnedPost() {
        media(1, 9, 7L, "ATTACHED", "HOLD", "ACTIVE");
        media(2, 9, 7L, "ATTACHED", "PASS", "ACTIVE");
        media(3, 9, 7L, "ATTACHED", "HOLD", "ACTIVE");
        media(4, 10, 7L, "ATTACHED", "HOLD", "ACTIVE");
        mapper.detachMedia(7L, 9L, List.of(1L, 2L), new Date());
        assertNull(jdbc.queryForObject("SELECT post_id FROM community_post_media WHERE id=3", Long.class));
        assertEquals(7L, jdbc.queryForObject("SELECT post_id FROM community_post_media WHERE id=4", Long.class));
        assertEquals(1, mapper.attachMedia(7L, 9L, 1L, 1, new Date()));
        assertEquals(1, mapper.attachMedia(7L, 9L, 2L, 0, new Date()));
        assertEquals("HOLD", jdbc.queryForObject("SELECT review_state FROM community_post_media WHERE id=1", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM community_post_media WHERE post_id=7 AND owner_member_id=9 AND upload_state='ATTACHED'", Integer.class));
    }

    @Test
    void removingAllAttachmentsOnlyDetachesTheOwnedPost() {
        media(1, 9, 7L, "ATTACHED", "HOLD", "ACTIVE");
        media(2, 10, 7L, "ATTACHED", "HOLD", "ACTIVE");
        media(3, 9, 8L, "ATTACHED", "HOLD", "ACTIVE");
        assertEquals(1, mapper.detachMedia(7L, 9L, List.of(), new Date()));
        assertNull(jdbc.queryForObject("SELECT post_id FROM community_post_media WHERE id=1", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM community_post_media WHERE post_id IS NOT NULL", Integer.class));
    }

    @ParameterizedTest
    @CsvSource({"PENDING", "PASS"})
    void newCommittedMediaKeepsExistingAdmission(String review) {
        media(1, 9, null, "COMMITTED", review, "ACTIVE");
        assertEquals(1, mapper.attachMedia(7L, 9L, 1L, 0, new Date()));
    }

    @ParameterizedTest
    @CsvSource({
        "9, NULL, COMMITTED, HOLD, ACTIVE",
        "9, NULL, COMMITTED, REJECTED, ACTIVE",
        "9, 7, ATTACHED, REJECTED, ACTIVE",
        "9, 8, ATTACHED, HOLD, ACTIVE",
        "10, 7, ATTACHED, HOLD, ACTIVE",
        "9, 7, ATTACHED, HOLD, ORPHANED",
        "9, 7, ATTACHED, HOLD, LEGAL_HOLD",
        "9, 7, ATTACHED, HOLD, DELETED",
        "9, NULL, UPLOADED, PENDING, ACTIVE",
        "9, NULL, INITIATED, PASS, ACTIVE"
    })
    void cannotAdoptForeignRejectedOrUncommittedMedia(long owner, String post,
            String upload, String review, String retention) {
        media(1, owner, "NULL".equals(post) ? null : Long.valueOf(post), upload, review, retention);
        assertEquals(0, mapper.attachMedia(7L, 9L, 1L, 0, new Date()));
        assertEquals(review, jdbc.queryForObject("SELECT review_state FROM community_post_media WHERE id=1", String.class));
    }

    private void media(long id, long owner, Long post, String upload, String review, String retention) {
        jdbc.update("INSERT INTO community_post_media(id,owner_member_id,upload_request_id,post_id,media_type,"
                + "object_key,mime_type,byte_size,content_hash,upload_state,review_state,retention_state,create_time,update_time) "
                + "VALUES(?,?,?,?,'IMAGE','synthetic-key','image/png',1,?,?,?,?,NOW(),NOW())",
                id, owner, "synthetic-" + id, post, "a".repeat(64), upload, review, retention);
    }
}

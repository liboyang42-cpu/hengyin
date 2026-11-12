package com.chengyinhub.business.it;

import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Executes repository migration SQL, not a Java reconstruction of the backfill or generated key. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_MYBATIS_IT", matches = "1")
@Timeout(60)
class TemplateD89D90MigrationsMySqlIT {
    private static final String USAGE = "migration_20261112_template_usage_count.sql";
    private static final String STATUS = "migration_20261112_template_market_status.sql";
    private static final String TITLE = "migration_20261112_template_author_title_unique.sql";
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private DriverManagerDataSource source;
    private String schema;
    private boolean schemaCreated;

    @BeforeEach
    void createScratchSchema() {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("template_d89_d90_migration");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = config.databaseName();
        // No IF NOT EXISTS: a collision must fail rather than reuse or drop someone else's schema.
        admin.execute("create database `" + schema + "` character set utf8mb4 collate utf8mb4_unicode_ci");
        schemaCreated = true;
        source = new DriverManagerDataSource(config.databaseUrl(), config.user(), config.password());
        db = new JdbcTemplate(source);
        db.execute("create table cms_template_library (id bigint primary key, member_id bigint, "
                + "title varchar(255), status bigint default 0, publish_status int default 0, "
                + "del_flag int not null default 0) engine=InnoDB");
        db.execute("create table cms_member_template (id bigint primary key, member_id bigint, "
                + "original_template_id bigint, status bigint, del_flag int default 0) engine=InnoDB");
        db.execute("create table audit_task (id bigint primary key, snapshot_json json) engine=InnoDB");
    }

    @AfterEach
    void dropScratchSchema() {
        if (schemaCreated) admin.execute("drop database `" + schema + "`");
    }

    @Test
    void missingUsageColumnBackfillsDistinctOwnersIncludingDeletedDraftsAndDoesNotInflateOnRerun() throws Exception {
        seedAdoptions();
        executeMigration(USAGE);
        assertCounts();
        Map<String, Object> column = db.queryForMap("select DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT "
                + "from information_schema.columns where table_schema=database() "
                + "and table_name='cms_template_library' and column_name='use_num'");
        assertEquals("int", column.get("DATA_TYPE"));
        assertEquals("NO", column.get("IS_NULLABLE"));
        assertEquals("0", String.valueOf(column.get("COLUMN_DEFAULT")));
        executeMigration(USAGE);
        assertCounts();
        db.update("insert into cms_template_library(id,member_id,title) values(4,40,'new')");
        assertEquals(0, count(4));
    }

    @Test
    void nullableLegacyCountsAreRecomputedInsteadOfAddedOrTrusted() throws Exception {
        db.execute("alter table cms_template_library add column use_num int null default null");
        seedAdoptions();
        db.update("update cms_template_library set use_num=900 where id=1");
        db.update("update cms_template_library set use_num=-5 where id=2");
        executeMigration(USAGE);
        assertCounts();
        executeMigration(USAGE);
        assertCounts();
        assertEquals("NO", db.queryForObject("select IS_NULLABLE from information_schema.columns "
                + "where table_schema=database() and table_name='cms_template_library' and column_name='use_num'", String.class));
    }

    private void seedAdoptions() {
        db.update("insert into cms_template_library(id,member_id,title) values(1,10,'one'),(2,20,'two'),(3,30,'unused')");
        db.update("insert into cms_member_template values "
                + "(11,101,1,1,0),(12,101,1,0,1),(13,102,1,0,0),(14,103,1,1,1),"
                + "(15,101,2,0,1),(16,101,2,1,0),(17,104,0,0,0),(18,105,null,0,0),(19,106,999,0,0)");
    }

    private void assertCounts() {
        assertEquals(3, count(1), "repeat rows count once; draft and deleted owners still count");
        assertEquals(1, count(2), "same owner may adopt a different library");
        assertEquals(0, count(3));
    }

    private int count(long id) {
        return db.queryForObject("select use_num from cms_template_library where id=?", Integer.class, id);
    }

    @Test
    void marketStatesAndMutableTakedownSnapshotsNormalizeWithoutChangingPersonalState() throws Exception {
        db.update("insert into cms_template_library(id,member_id,title,status,publish_status,del_flag) values "
                + "(1,7,'live',1,1,0),(2,7,'offline',1,0,0),(3,7,'pending',0,0,0),"
                + "(4,7,'rejected',2,0,0),(5,7,'deleted offline',1,0,1)");
        String snapshot = "{\"sentinel\":\"keep\",\"publishStatus\":0,\"preTakedown\":{"
                + "\"template\":{\"status\":1,\"checkStatus\":1,\"publishStatus\":0,\"reason\":\"personal\"},"
                + "\"libraries\":[{\"id\":1,\"status\":1,\"publishStatus\":1,\"reason\":\"live\"},"
                + "{\"id\":2,\"status\":1,\"publishStatus\":0,\"reason\":\"offline\"},"
                + "{\"id\":3,\"status\":0,\"publishStatus\":0},{\"id\":4,\"status\":2,\"publishStatus\":0}]}}";
        String personalOnly = "{\"preTakedown\":{\"template\":{\"status\":1,\"publishStatus\":0}}}";
        db.update("insert into audit_task values(1,?),(2,?),(3,null),(4,'{}')", snapshot, personalOnly);
        ObjectMapper json = new ObjectMapper();
        JsonNode before = json.readTree(snapshot);

        executeMigration(STATUS);

        assertEquals(java.util.Arrays.asList(1L, 3L, 0L, 2L, 3L),
                db.queryForList("select status from cms_template_library order by id", Long.class));
        assertEquals(0, columnCount("publish_status"));
        JsonNode after = snapshot(1);
        assertEquals(before.path("preTakedown").path("template"), after.path("preTakedown").path("template"));
        assertEquals(before.path("sentinel"), after.path("sentinel"));
        assertEquals(before.path("publishStatus"), after.path("publishStatus"));
        for (int i = 0; i < 4; i++) {
            JsonNode library = after.path("preTakedown").path("libraries").get(i);
            assertEquals(new int[]{1, 3, 0, 2}[i], library.path("status").asInt());
            assertFalse(library.has("publishStatus"));
            assertEquals(before.path("preTakedown").path("libraries").get(i).path("id"), library.path("id"));
            assertEquals(before.path("preTakedown").path("libraries").get(i).path("reason"), library.path("reason"));
        }
        assertEquals(json.readTree(personalOnly), snapshot(2));
        assertNull(db.queryForObject("select snapshot_json from audit_task where id=3", String.class));
        assertEquals(json.readTree("{}"), snapshot(4));
        executeMigration(STATUS);
        assertEquals(after, snapshot(1), "snapshot migration is stable after legacy column is dropped");
        assertEquals(java.util.Arrays.asList(1L, 3L, 0L, 2L, 3L),
                db.queryForList("select status from cms_template_library order by id", Long.class));
    }

    private JsonNode snapshot(long id) throws Exception {
        return new ObjectMapper().readTree(db.queryForObject("select snapshot_json from audit_task where id=?", String.class, id));
    }

    @Test
    void cleanTitleMigrationCreatesRealGeneratedBinaryKeyAndEnforcesWriteTransitions() throws Exception {
        insertTitle(1, 7, "\t Night\r\n", 0);
        insertTitle(2, 7, "night", 0);
        insertTitle(3, 8, "Night", 0);
        insertTitle(4, 7, "Night", 1);
        insertTitle(5, 7, " \t\r\n", 0);
        insertTitle(6, 7, null, 0);

        assertTrue(executeMigration(TITLE).finalAssertion());
        Map<String, Object> key = db.queryForMap("select DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, EXTRA, GENERATION_EXPRESSION "
                + "from information_schema.columns where table_schema=database() "
                + "and table_name='cms_template_library' and column_name='active_title_key'");
        assertEquals("varbinary", key.get("DATA_TYPE"));
        assertEquals(1024L, ((Number) key.get("CHARACTER_MAXIMUM_LENGTH")).longValue());
        assertTrue(String.valueOf(key.get("EXTRA")).contains("GENERATED"));
        assertFalse(String.valueOf(key.get("GENERATION_EXPRESSION")).isEmpty());
        assertEquals(java.util.Arrays.asList("member_id", "active_title_key"), db.queryForList(
                "select COLUMN_NAME from information_schema.statistics where table_schema=database() "
                        + "and table_name='cms_template_library' and index_name='uk_template_author_live_title' order by SEQ_IN_INDEX", String.class));
        assertEquals(0, db.queryForObject("select max(NON_UNIQUE) from information_schema.statistics "
                + "where table_schema=database() and table_name='cms_template_library' "
                + "and index_name='uk_template_author_live_title'", Integer.class));
        assertArrayEquals("Night".getBytes(StandardCharsets.UTF_8),
                db.queryForObject("select active_title_key from cms_template_library where id=1", byte[].class));
        assertEquals(3, db.queryForObject("select count(*) from cms_template_library where active_title_key is null", Integer.class));

        assertDuplicate(() -> insertTitle(10, 7, " Night ", 0));
        insertTitle(11, 7, "NIGHT", 0);
        insertTitle(12, 9, "Night", 0);
        insertTitle(13, 7, "different", 0);
        assertDuplicate(() -> db.update("update cms_template_library set title=' Night ' where id=13"));
        assertEquals("different", db.queryForObject("select title from cms_template_library where id=13", String.class));
        db.update("update cms_template_library set title=title where id=1");
        db.update("update cms_template_library set del_flag=1 where id=1");
        insertTitle(14, 7, "Night", 0);
        assertDuplicate(() -> db.update("update cms_template_library set del_flag=0 where id=1"));
        assertEquals(1, db.queryForObject("select del_flag from cms_template_library where id=1", Integer.class));
        insertTitle(15, 7, "", 0);
        insertTitle(16, 7, "\t ", 0);
        // Java trim strips every boundary codepoint U+0000..U+0020, not Unicode spaces.
        insertTitle(17, 7, "\u0001\u001fEdge\u0002", 0);
        assertDuplicate(() -> insertTitle(18, 7, "Edge", 0));
        insertTitle(19, 7, "\u00a0Night\u00a0", 0);
        insertTitle(20, 7, "Ni ght", 0);
        assertTrue(executeMigration(TITLE).finalAssertion(), "rerun preserves the enforced unique key");
    }

    @Test
    void legacyDuplicateMigrationPreviewsRowsSkipsIndexAndReturnsFalseWithoutSqlFailureOrRenaming() throws Exception {
        insertTitle(41, 7, " Night ", 0);
        insertTitle(42, 7, "\tNight\n", 0);
        insertTitle(43, 8, "Night", 0);
        insertTitle(44, 7, "Night", 1);
        List<Map<String, Object>> before = db.queryForList("select id,member_id,title,del_flag from cms_template_library order by id");

        MigrationResults result = assertDoesNotThrow(() -> executeMigration(TITLE));

        assertFalse(result.finalAssertion(), "false is a blocked migration, not successful installation");
        assertTrue(result.hasPreview(), "operators receive duplicate detail rows before the final scalar assertion");
        String preview = result.previewText();
        assertTrue(preview.contains("41"), preview);
        assertTrue(preview.contains("42"), preview);
        assertTrue(preview.contains("Night"), preview);
        assertEquals(0, db.queryForObject("select count(*) from information_schema.statistics "
                + "where table_schema=database() and table_name='cms_template_library' "
                + "and index_name='uk_template_author_live_title'", Integer.class));
        assertEquals(before, db.queryForList("select id,member_id,title,del_flag from cms_template_library order by id"));
        assertFalse(executeMigration(TITLE).finalAssertion());
        assertEquals(before, db.queryForList("select id,member_id,title,del_flag from cms_template_library order by id"));
    }

    @Test
    void generatedKeyRetainsAsciiSpaceBeforeFinalUnicodeLineTerminators() throws Exception {
        assertTrue(executeMigration(TITLE).finalAssertion());
        int variant = 0;
        for (String terminator : new String[]{"\u0085", "\u2028", "\u2029"}) {
            long owner = 100 + variant;
            long id = 1000 + variant * 10;
            String title = "x " + terminator;
            insertTitle(id, owner, "x" + terminator, 0);
            insertTitle(id + 1, owner, title, 0);
            assertArrayEquals(title.getBytes(StandardCharsets.UTF_8),
                    db.queryForObject("select active_title_key from cms_template_library where id=?", byte[].class, id + 1),
                    "ASCII space before final Unicode terminator must survive generated-key normalization");
            assertDuplicate(() -> insertTitle(id + 2, owner, "\t" + title + "\r", 0));
            variant++;
        }
    }

    private int columnCount(String name) {
        return db.queryForObject("select count(*) from information_schema.columns where table_schema=database() "
                + "and table_name='cms_template_library' and column_name=?", Integer.class, name);
    }

    private void insertTitle(long id, long owner, String title, int deleted) {
        db.update("insert into cms_template_library(id,member_id,title,del_flag) values(?,?,?,?)", id, owner, title, deleted);
    }

    private void assertDuplicate(org.junit.jupiter.api.function.Executable write) {
        org.springframework.dao.DuplicateKeyException error = assertThrows(org.springframework.dao.DuplicateKeyException.class, write);
        assertTrue(error.getMostSpecificCause() instanceof SQLException);
        assertEquals(1062, ((SQLException) error.getMostSpecificCause()).getErrorCode());
        assertTrue(error.getMostSpecificCause().getMessage().contains("uk_template_author_live_title"));
    }

    /** Execute plain SQL and capture result sets; deliberately do not interpret runner false as SQL failure. */
    private MigrationResults executeMigration(String file) throws Exception {
        Path path = Paths.get("sql", file);
        if (!Files.isRegularFile(path)) path = Paths.get("chengyinhub-admin", "sql", file);
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        StringBuilder sql = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("--") && !trimmed.startsWith("#")) sql.append(line).append('\n');
        }
        MigrationResults result = new MigrationResults();
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            for (String command : ItSchema.splitStatements(sql.toString())) {
                boolean rows = statement.execute(command);
                while (true) {
                    if (rows) {
                        try (ResultSet rs = statement.getResultSet()) {
                            ResultSetMetaData metadata = rs.getMetaData();
                            List<Map<String, Object>> table = new ArrayList<>();
                            while (rs.next()) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                                    row.put(metadata.getColumnLabel(column), rs.getObject(column));
                                }
                                table.add(row);
                            }
                            result.tables.add(table);
                        }
                    } else if (statement.getUpdateCount() == -1) {
                        break;
                    }
                    rows = statement.getMoreResults();
                }
            }
        }
        return result;
    }

    private static final class MigrationResults {
        private final List<List<Map<String, Object>>> tables = new ArrayList<>();

        private boolean finalAssertion() {
            assertFalse(tables.isEmpty(), "migration must return its final runner scalar assertion");
            List<Map<String, Object>> last = tables.get(tables.size() - 1);
            assertEquals(1, last.size());
            assertEquals(1, last.get(0).size());
            Object value = last.get(0).values().iterator().next();
            return value instanceof Boolean ? (Boolean) value : Integer.parseInt(String.valueOf(value)) == 1;
        }

        private boolean hasPreview() {
            return tables.size() > 1 && tables.subList(0, tables.size() - 1).stream().anyMatch(table -> !table.isEmpty());
        }

        private String previewText() {
            return tables.subList(0, tables.size() - 1).toString();
        }
    }
}

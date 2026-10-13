package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hint 等级与既有 result 码复用时，质量视图必须显式排除 Hint 事件。 */
class PuzzleAttemptLogMigrationTest {

    @Test
    void nodeQualityViewExcludesPuzzleHintEvents() throws Exception {
        String sql = new String(Files.readAllBytes(Paths.get("sql",
                "migration_20260820_questo_puzzle_atmosphere.sql")), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertTrue(sql.contains("create or replace view v_node_quality"));
        assertTrue(sql.contains("where attempt_type is null or attempt_type <> 5"));
    }
}

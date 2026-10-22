package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class O14MigrationContractTest {

    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("sql/" + name)), StandardCharsets.UTF_8);
    }

    @Test
    void jobsAreRegisteredPausedAndIdempotently() throws Exception {
        String sql = read("migration_o14_reco_search_jobs_20261022.sql");
        String[] inserts = sql.split("INSERT INTO sys_job");
        assertEquals(3, inserts.length, "恰好两条登记");
        for (String target : new String[]{"recommendationScoreDailyTask.run()", "contentSearchProjectionTask.refresh()"}) {
            String insert = null;
            // 取第一个命中的块：最后一块尾部还带着 runner 断言，也会提到同一个 invoke_target
            for (int i = 1; i < inserts.length && insert == null; i++) {
                if (inserts[i].contains("'" + target + "'")) insert = inserts[i];
            }
            assertTrue(insert != null, target);
            String values = insert.substring(0, insert.indexOf("WHERE NOT EXISTS"));
            // concurrent='1'(禁止并发) + status='1'(暂停)
            assertTrue(values.contains("'1', '1', 'admin'"), "任务必须以 concurrent=1、status=1(暂停)登记: " + target);
            assertTrue(insert.contains("SELECT 1 FROM sys_job WHERE invoke_target = '" + target + "'"));
        }
        assertFalse(sql.toUpperCase().contains("UPDATE SYS_JOB"), "不得改动 owner 已有的任务状态");
    }

    @Test
    void cityCodeColumnsAreNullableAndGuarded() throws Exception {
        for (String table : new String[]{"cms_topic", "cms_activity"}) {
            String sql = read("migration_o14_" + table + "_city_code_20261022.sql");
            assertTrue(sql.contains("ADD COLUMN city_code VARCHAR(32) NULL DEFAULT NULL"));
            assertTrue(sql.contains("information_schema.columns"), "加列前必须查 information_schema 保证幂等");
            assertFalse(sql.toUpperCase().contains("DROP COLUMN"));
        }
    }

    @Test
    void backfillOnlyFillsEmptyValues() throws Exception {
        String sql = read("migration_o14_content_city_code_backfill_20261022.sql");
        int updates = sql.split("(?m)^UPDATE ").length - 1;
        assertEquals(3, updates);
        assertEquals(3, sql.split("WHERE (t|a)\\.city_code IS NULL").length - 1,
                "每条回填 UPDATE 都只能写 city_code IS NULL 的行");
        assertTrue(sql.contains("HAVING COUNT(DISTINCT k.city_code) = 1"), "命中多个城市的地址不能猜");
    }
}

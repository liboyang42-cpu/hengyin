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
    void backfillNeverMatchesFreeTextAddressesAndOnlyRewritesItsOwnRows() throws Exception {
        String sql = read("migration_o14_content_city_code_backfill_20261022.sql");
        String code = sql.replaceAll("(?m)^--.*$", "");
        assertFalse(code.contains("address_name") || code.contains("address "), "地址自由文本不得参与匹配(南京西路→南京、北京路→北京)");
        assertFalse(code.contains("LIKE"), "不得做子串匹配");
        assertTrue(code.contains("k.keyword = TRIM(TRAILING '市' FROM TRIM(c.city)) COLLATE utf8mb4_general_ci"),
                "只认俱乐部「所在城市」字段整串相等");
        int updates = code.split("(?m)^UPDATE ").length - 1;
        assertEquals(2, updates);
        assertEquals(2, code.split("WHERE \\((t|a)\\.city_code IS NULL OR l\\.city_code = (t|a)\\.city_code\\)").length - 1,
                "每条回填 UPDATE 只能写 city_code IS NULL 或本回填写过且未改动的行");
        assertEquals(2, code.split("INSERT INTO o14_city_code_backfill_log").length - 1, "每次写入都要记来源标记");
    }

    @Test
    void previewIsCommittedReadOnlyAndUsesTheSameCityWhitelist() throws Exception {
        String preview = read("_precheck_o14_content_city_code_backfill_20261022.sql");
        String code = preview.replaceAll("(?m)^--.*$", "").toUpperCase();
        for (String write : new String[]{"INSERT ", "UPDATE ", "DELETE ", "CREATE ", "DROP ", "ALTER ", "REPLACE "}) {
            assertFalse(code.contains(write), "预览必须只读: " + write.trim());
        }
        java.util.regex.Matcher pair = java.util.regex.Pattern.compile("'([^'\\d]+)','(\\d{6})'").matcher(
                read("migration_o14_content_city_code_backfill_20261022.sql"));
        java.util.List<String> cities = new java.util.ArrayList<>();
        while (pair.find()) cities.add(pair.group(1) + "=" + pair.group(2));
        assertEquals(15, cities.size());
        java.util.regex.Matcher kw = java.util.regex.Pattern.compile("SELECT '([^']+)'(?: AS keyword)?, ?'(\\d{6})'").matcher(preview);
        java.util.List<String> previewCities = new java.util.ArrayList<>();
        while (kw.find()) previewCities.add(kw.group(1) + "=" + kw.group(2));
        // 预览里白名单在三条语句各出现一次,逐份与迁移一致
        assertEquals(3 * cities.size(), previewCities.size());
        for (int i = 0; i < previewCities.size(); i++) {
            assertEquals(cities.get(i % cities.size()), previewCities.get(i));
        }
    }
}

package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R7 DISTRIBUTION 存量补登与单例约束的迁移合同；真 MySQL 行为由 funds gate 点名 IT 承重。 */
class R7FundsMigrationContractTest {
    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** 剥掉整行 SQL 注释：判据必须落在真会执行的 SQL 上，注释里提到某个反面写法不该把门禁弄红。 */
    private static String executableSql(String sql) {
        return sql.replaceAll("(?m)^[ \\t]*--[^\\n]*$", "");
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void distributionBackfillUsesOnlyFrozenTermsAndOneLegalOrganizerActor() throws Exception {
        String sql = read("sql/migration_20260817_tandianri_r7_distribution_backfill.sql");
        String normalized = sql.replace("''", "'").replaceAll("\\s+", " ").toLowerCase();

        assertTrue(sql.contains("【类别】③"));
        assertTrue(sql.contains("【前置】"));
        assertTrue(sql.contains("【幂等】"));
        assertTrue(sql.contains("【可逆】"));
        assertTrue(sql.contains("必须先于包含 DISTRIBUTION 硬闸的 jar"));
        assertTrue(normalized.contains("join club_edition_compensation_terms terms"));
        assertTrue(normalized.contains("terms.executing_club_id"));
        assertTrue(normalized.contains("organizer.declared_by"));
        assertTrue(normalized.contains("organizer.slot = 'organizer'"));
        assertTrue(normalized.contains("organizer.entity_type = 'platform'"));
        assertTrue(normalized.contains("organizer.entity_id is null"));
        assertTrue(normalized.contains("distribution.slot = 'distribution'"));
        assertTrue(normalized.contains("distribution.entity_id = terms.executing_club_id"));
        assertTrue(sql.split("-- runner: assert=scalar-true", -1).length >= 3,
                "写前预检与末端 readback 都必须由 runner 执行，缺一就是可部分绿");
    }

    @Test
    void distributionSingletonMigrationIsReplayableAndReadsBackDatabaseOwnedFacts() throws Exception {
        String sql = read("sql/migration_20260817_tandianri_r7_distribution_singleton.sql");
        String normalized = sql.replace("''", "'").replaceAll("\\s+", " ").toLowerCase();

        assertTrue(normalized.contains("having count(*) >= 2"));
        assertTrue(normalized.contains("distribution_singleton_key varchar(32) generated always as"));
        assertTrue(normalized.contains("case when status = 1 and slot = 'distribution'"));
        assertTrue(normalized.contains("unique key uk_product_role_distribution_singleton"));
        assertTrue(normalized.contains("information_schema.columns"));
        assertTrue(normalized.contains("information_schema.statistics"));
        assertTrue(sql.split("-- runner: assert=scalar-true", -1).length >= 8,
                "写前冲突、写前列/唯一键 fail-closed guard、写后列形状/取值/唯一键与单例都必须能分别让 runner 变红");

        String originalDdl = read("sql/migration_product_role_20260716.sql");
        assertTrue(originalDdl.contains("DISTRIBUTION 当前首三期为单例"),
                "建表注释不能继续宣称 DISTRIBUTION 可多行");
    }

    /**
     * R8：guard 与 readback 必须精确到能证伪。
     * 关键词模糊匹配放行错表达式、只核第一列放行复合 UNIQUE —— 两者都是「绿得很便宜」的假绿。
     */
    @Test
    void distributionSingletonProvesExactGeneratedColumnAndSingleColumnUniqueKey() throws Exception {
        String sql = read("sql/migration_20260817_tandianri_r7_distribution_singleton.sql");
        String normalized = executableSql(sql).replace("''", "'").replaceAll("\\s+", " ").toLowerCase();
        // 反面判据一律对【去掉全部空白】的副本判：否则写成 seq_in_index=1（无空格）就能绕过。
        String squeezed = normalized.replace(" ", "");

        assertFalse(squeezed.contains("like'%"),
                "生成列 readback 不得用关键词模糊匹配：status=2 之类的错表达式照样命中 %status%%slot%%distribution%%topic_id%");
        assertFalse(squeezed.contains("seq_in_index=1"),
                "按 seq_in_index = 1 过滤等于只核第一列，(distribution_singleton_key, other) 复合 UNIQUE 会被判成 1 行假绿");
        assertFalse(squeezed.contains("dropindex"),
                "同名错唯一键必须在写前红，不得静默 drop/recreate 换掉一条并发防线");
        assertFalse(squeezed.contains("dropcolumn"),
                "同名错生成列必须在写前红，不得静默 drop/recreate");

        // MySQL 打印生成表达式时会加反引号、字符集引导符与括号；剥掉这些噪音后必须逐字符相等。
        String canonicalExpression =
                "'casewhenstatus=1andslot='distribution'thenconcattopic_id,'-distribution'end'";
        // 每条判据都必须【出现两次】：写前 guard 一次、末端 readback 一次。
        // 只断言「出现过」是不够的：把末端 readback 放松成 COUNT(*) >= 1（复合 UNIQUE 假绿）时，
        // guard 里那一份会替它接住断言，门禁照样绿 —— 实测过的漏法。
        String[] discriminators = {
                canonicalExpression,
                "lower(data_type) <=> 'varchar'",
                "character_maximum_length <=> 32",
                "is_nullable <=> 'yes'",
                "upper(extra) <=> 'virtual generated'",
                "group by index_name",
                "count(*) <=> 1",
                "min(non_unique) <=> 0",
                "min(seq_in_index) <=> 1",
                "min(column_name) <=> 'distribution_singleton_key'",
                "count(sub_part) <=> 0",
                "min(is_visible) <=> 'yes'",
        };
        for (String discriminator : discriminators) {
            assertEquals(2, countOf(normalized, discriminator),
                    "写前 guard 与末端 readback 必须用同一条判据，任一侧放松都要红：" + discriminator);
        }

        assertTrue(normalized.contains("not (distribution_singleton_key <=> case when status = 1"
                        + " and slot = 'distribution' then concat(topic_id, '-distribution') end)"),
                "末端还要把生成列真实取值与目标语义逐行比对，别只信 information_schema 的文本");

        // 两份判据必须【一前一后】跨在 DDL 两侧：只钉首次出现，两份都缩到写前也照样绿。
        int firstWrite = normalized.indexOf("alter table product_role add");
        assertTrue(firstWrite > 0, "文件必须仍然自己建列与唯一键");
        for (String discriminator : new String[]{canonicalExpression, "min(non_unique) <=> 0"}) {
            assertTrue(normalized.indexOf(discriminator) < firstWrite,
                    "半落库重放时，同名错对象必须在任何 DDL 之前红：" + discriminator);
            assertTrue(normalized.lastIndexOf(discriminator) > firstWrite,
                    "写后还必须再观测一次，不能只靠写前 guard：" + discriminator);
        }

        // IT 侧靠「剥整行注释 → 按 ; 切 → 取 SELECT 打头的」把这 7 条回读真跑一遍并断言标量为 1。
        // 那条链路要连真库才跑得到，先在这里离线证明它切出来的条数与形状是对的。
        assertEquals(7, countOf(sql, "-- runner: assert=scalar-true"),
                "写前冲突、写前列/唯一键 guard、写后列形状/取值/唯一键、末端业务共 7 条，各自可单独变红");
        int standaloneSelects = 0;
        for (String statement : executableSql(sql).split(";")) {
            if (statement.trim().regionMatches(true, 0, "SELECT", 0, "SELECT".length())) {
                standaloneSelects++;
            }
        }
        assertEquals(7, standaloneSelects,
                "每条声明的回读都必须是独立的、以 SELECT 打头的语句，IT 才能逐条跑并读回标量");
    }
}

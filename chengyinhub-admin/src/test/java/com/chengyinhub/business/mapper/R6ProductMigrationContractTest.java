package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R6 产品迁移的发布顺序与对面事实回读合同。 */
class R6ProductMigrationContractTest {
    private static final String PATH = "sql/migration_20260817_tandianri_r6_product_contracts.sql";

    private static String migration() throws Exception {
        return new String(Files.readAllBytes(Paths.get(PATH)), StandardCharsets.UTF_8);
    }

    /** 剥掉整行 SQL 注释：判据必须落在真会执行的 SQL 上，注释里写某个反面写法不该把门禁弄红。 */
    private static String executableSql(String sql) {
        return sql.replaceAll("(?m)^[ \\t]*--[^\\n]*$", "");
    }

    private static String normalized() throws Exception {
        return executableSql(migration()).replace("''", "'").replaceAll("\\s+", " ").toLowerCase();
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void productMigrationDeclaresCategoryPrerequisitesIdempotencyRollbackAndJarOrder() throws Exception {
        String sql = migration();

        assertTrue(sql.contains("【类别】③"));
        assertTrue(sql.contains("【前置】"));
        assertTrue(sql.contains("【幂等】"));
        assertTrue(sql.contains("【可逆】"));
        assertTrue(sql.contains("【顺序】必须先于包含新 mapper 的 jar 落库"));
        assertTrue(sql.contains("oms_ticket 全域读取报 ERROR 1054"));
        assertTrue(sql.contains("topic_completion_xp_award 表不存在"));
    }

    /**
     * 索引回读必须能把「同名错索引」判红：只比名字的回读，对错列、错序、前缀索引、
     * UNIQUE 化、INVISIBLE 全都恒绿。这里把整条断言逐字钉死，改列/改序即红。
     */
    @Test
    void productMigrationReadsBackTheActivitySourceIndexShapeNotJustItsName() throws Exception {
        String compact = migration().replaceAll("\\s+", " ");

        assertTrue(compact.contains("-- runner: assert=scalar-true "
                        + "SELECT (SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'oms_ticket' "
                        + "AND index_name = 'idx_oms_ticket_activity_source') = 4 "
                        + "AND (SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'oms_ticket' "
                        + "AND index_name = 'idx_oms_ticket_activity_source' "
                        + "AND non_unique = 1 AND index_type = 'BTREE' "
                        + "AND sub_part IS NULL AND is_visible = 'YES' "
                        + "AND ((seq_in_index = 1 AND column_name = 'owner_type') "
                        + "OR (seq_in_index = 2 AND column_name = 'owner_id') "
                        + "OR (seq_in_index = 3 AND column_name = 'source_ticket_id') "
                        + "OR (seq_in_index = 4 AND column_name = 'del_flag'))) = 4"),
                "索引回读必须逐位证明列集合与列顺序，并锁定非唯一/BTREE/无前缀/可见；只比名字对同名错索引恒绿");
    }

    /**
     * R9：XP 事实表的三条回读必须精确到能证伪。
     *
     * <p>旧版只断言「表存在」+「叫 uk_topic_completion_xp_member_topic 的索引有 2 个 key part 且
     * non_unique = 0」——(member_id, xp_amount)、(topic_id, member_id) 这类同名错唯一键照样绿，
     * 而那把键正是「每人每期通关 XP 只发一次」的库侧防线；xp_amount 类型与 CHECK 更是完全没回读。
     */
    @Test
    void productMigrationProvesXpAwardColumnsUniqueKeyOrderAndPositiveCheckExactly() throws Exception {
        String normalized = normalized();
        String squeezed = normalized.replace(" ", "");

        assertFalse(squeezed.contains("like'%"),
                "CHECK / 索引回读不得用关键词模糊匹配：xp_amount >= 0 这类语义相反的约束照样命中 %xp_amount%");
        assertFalse(squeezed.contains("droptable"),
                "同名错表必须在写前红，不得静默 drop/recreate 一张已有业务行的事实表");

        // 每条判据都必须【出现两次】：写前 guard 一次、写后 readback 一次。
        // 只断言「出现过」不够 —— 把写后 readback 放松时，写前 guard 那一份会替它接住断言。
        String[] discriminators = {
                "column_name = 'member_id' and lower(data_type) = 'bigint' and is_nullable = 'no'",
                "column_name = 'topic_id' and lower(data_type) = 'bigint' and is_nullable = 'no'",
                "column_name = 'xp_amount' and lower(data_type) = 'int' and is_nullable = 'no'",
                "column_name = 'granted_at' and lower(data_type) = 'datetime' and is_nullable = 'no'",
                "group by index_name",
                "count(*) <=> 2",
                "min(non_unique) <=> 0",
                "count(sub_part) <=> 0",
                "min(is_visible) <=> 'yes'",
                "sum(seq_in_index = 1 and column_name = 'member_id') <=> 1",
                "sum(seq_in_index = 2 and column_name = 'topic_id') <=> 1",
                "<=> 'xp_amount>0'",
                "lower(data_type) = 'bigint' and is_nullable = 'yes'",
        };
        for (String discriminator : discriminators) {
            assertEquals(2, countOf(normalized, discriminator),
                    "写前 guard 与写后 readback 必须用同一条判据，任一侧放松都要红：" + discriminator);
        }

        assertFalse(squeezed.contains("min(column_name)<=>'member_id'"),
                "对唯一键只取 MIN(column_name) 等于只核一列，(member_id, xp_amount) 会被判成形状正确");
    }

    /**
     * 写前 guard 必须落在【任何 DDL 与回填之前】：半落库重放时，同名错对象要在
     * CREATE TABLE IF NOT EXISTS / ADD INDEX 把它当成已完成之前就红。
     */
    @Test
    void productMigrationGuardsRunBeforeAnyDestructiveWriteAndAreObservedAgainAfterwards() throws Exception {
        String normalized = normalized();

        int firstWrite = normalized.indexOf("'alter table oms_ticket add");
        assertTrue(firstWrite > 0, "文件必须仍然自己建列与索引");
        assertTrue(normalized.indexOf("update oms_ticket activity_ticket") > firstWrite);
        assertTrue(normalized.indexOf("create table if not exists topic_completion_xp_award") > firstWrite);

        for (String discriminator : new String[]{
                "lower(data_type) = 'bigint' and is_nullable = 'yes'",
                "non_unique = 1 and index_type = 'btree'",
                "<=> 'xp_amount>0'",
                "sum(seq_in_index = 2 and column_name = 'topic_id') <=> 1"}) {
            assertTrue(normalized.indexOf(discriminator) < firstWrite,
                    "半落库重放时，同名错对象必须在任何 DDL / 回填之前红：" + discriminator);
            assertTrue(normalized.lastIndexOf(discriminator) > firstWrite,
                    "写后还必须再观测一次，不能只靠写前 guard：" + discriminator);
        }
    }

    /**
     * 回填必须挡住【两个方向】的歧义。反向歧义（两张活动票争同一张主题票）在旧写法下恒绿：
     * 每张活动票各自只命中一张主题票，HAVING COUNT(*) = 1 全部通过，结果两张票拿到同一个
     * source_ticket_id —— 配对键当场失去唯一性，读侧只能整期 unknown。
     */
    @Test
    void productMigrationBackfillRefusesBothForwardAndReverseAmbiguity() throws Exception {
        String normalized = normalized();

        assertTrue(normalized.contains("having count(*) = 1"), "正向歧义仍必须由 HAVING 挡住");
        assertTrue(normalized.contains("not exists ( select 1 from oms_ticket sibling"),
                "反向歧义必须显式挡住：四字段相等是对称关系，同主题下的同形兄弟票即代表有人争同一张主题票");
        assertTrue(normalized.contains("sibling_activity.topic_id = activity.topic_id"),
                "兄弟票的范围是【同一主题下的全部活动壳】，不是同一个活动 —— 一个主题可以有多个活动壳");
        assertTrue(normalized.contains("sibling.id <> at.id"));
        assertFalse(normalized.contains("sibling.source_ticket_id is null"),
                "兄弟票不限定未回填：已被上一轮占走的主题票同样不能再被认领，否则重放不收敛");
    }

    /**
     * 声明的 scalar 回读条数与「独立 SELECT 语句」条数必须一致，
     * 否则 IT 侧「剥注释 → 按 ; 切 → 取 SELECT 打头」的逐条消费会与文件对不上。
     */
    @Test
    void productMigrationDeclaresEveryReadbackAsAStandaloneAssertedSelect() throws Exception {
        String sql = migration();

        assertEquals(8, countOf(sql, "-- runner: assert=scalar-true"),
                "三条写前 guard + 列/索引/XP 列/XP 唯一键/XP CHECK 五条写后观测 = 8，各自可单独变红");
        int standaloneSelects = 0;
        for (String statement : executableSql(sql).split(";")) {
            if (statement.trim().regionMatches(true, 0, "SELECT", 0, "SELECT".length())) {
                standaloneSelects++;
            }
        }
        assertEquals(8, standaloneSelects,
                "每条声明的回读都必须是独立的、以 SELECT 打头的语句，IT 才能逐条跑并读回标量");
    }
}

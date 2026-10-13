package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R6 新迁移的幂等/回读合同；真 MySQL 重放由 funds gate 点名 IT 承重。 */
class R6FundsMigrationContractTest {
    private static final String CLOSURE = "sql/migration_20260817_tandianri_r6_funds_final_closure.sql";

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** 剥掉整行 SQL 注释：判据必须落在真会执行的 SQL 上，注释里写反面写法不该把门禁弄红。 */
    private static String executableSql(String sql) {
        return sql.replaceAll("(?m)^[ \\t]*--[^\\n]*$", "");
    }

    private static String normalizedClosure() throws Exception {
        return executableSql(read(CLOSURE)).replace("''", "'").replaceAll("\\s+", " ").toLowerCase();
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void finalClosureMigrationPersistsAttributionHeadAndRepairsMissingChecks() throws Exception {
        String sql = read(CLOSURE);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS topic_edition_funds_attribution"));
        assertTrue(sql.contains("source_key BIGINT GENERATED ALWAYS AS (COALESCE(source_club_id, 0))"));
        assertTrue(sql.contains("ticket_source_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00"));
        assertTrue(sql.contains("@r6_component_check_exists"));
        assertTrue(sql.contains("ALTER TABLE club_payable ADD CONSTRAINT ck_club_payable_component"),
                "旧 CHECK 缺失时必须能直接 ADD，不能再裸 DROP 后中断");
        assertTrue(sql.replace("''", "'").contains("'FIXED', 'QUALITY', 'EXTRA', 'TICKET_SOURCE'"));
        assertTrue(sql.contains("-- runner: assert=scalar-true"));
        assertTrue(sql.contains("information_schema.check_constraints"));
    }

    @Test
    void dependencyGraphOrdersClosureAfterBothPayableAndRoleSchema() throws Exception {
        String json = read("../ci/migration-dependencies.json");
        int closure = json.indexOf("\"20260817_tandianri_r6_funds_final_closure\"");
        assertTrue(closure >= 0);
        String tail = json.substring(closure, Math.min(json.length(), closure + 260));
        assertTrue(tail.contains("20260817_tandianri_batch3_funds_ticket_source_payable"));
        assertTrue(tail.contains("product_role_20260716"));
    }

    /**
     * R9：两条 CHECK 回读必须能区分【语义相反】的约束。
     *
     * <p>旧版写的是 {@code MAX(UPPER(check_clause) LIKE '%TICKET_SOURCE%') = 1}：
     * {@code component IN ('TICKET_SOURCE')}（把另外三档删光）、{@code component <> 'TICKET_SOURCE'}、
     * {@code IN ('FIXED','TICKET_SOURCE','ANYTHING')} 全部命中；而且 LIKE 里的 {@code _}
     * 是未转义的单字符通配符，连 {@code TICKETxSOURCE} 都能冒充。那是恒绿，不是断言。
     */
    @Test
    void finalClosureProvesBothCheckClausesExactlyInsteadOfMatchingAKeyword() throws Exception {
        String normalized = normalizedClosure();
        String squeezed = normalized.replace(" ", "");

        assertFalse(squeezed.contains("like'%"),
                "CHECK 回读不得用关键词模糊匹配：删掉三个枚举、或把 >= 写成 <= 都照样命中");

        assertEquals(1, countOf(normalized,
                        "<=> 'componentin'fixed','quality','extra','ticket_source''"),
                "四枚举必须归一后逐字符相等：缺一个枚举、多一个枚举、改成 <> 都要红");
        assertEquals(1, countOf(normalized,
                        "<=> 'fixed_compensation>=0andquality_amount>=0"
                                + "andextra_amount>=0andticket_source_amount>=0'"),
                "四分量非负必须归一后逐字符相等：漏掉 ticket_source_amount 或写反不等号都要红");
    }

    /**
     * R9：attribution 表不能只回读「表存在」。
     * 它用 CREATE TABLE IF NOT EXISTS 建，同名错表会被静默接受；而
     * uk_edition_funds_attribution_source(funds_snapshot_id, source_key) 正是
     * 「同一快照同一俱乐部只能有一条归因行」的库侧防线 —— 缺了它，票源奖励可被重复计提。
     */
    @Test
    void finalClosureReadsBackAttributionColumnsGeneratedKeyUniqueIndexForeignKeyAndChecks()
            throws Exception {
        String normalized = normalizedClosure();

        // 每条判据都必须【出现两次】：写前 guard 一次、写后 readback 一次；任一侧放松都要红。
        String[] discriminators = {
                "column_name = 'funds_snapshot_id' and lower(data_type) = 'bigint' and is_nullable = 'no'",
                "column_name = 'source_club_id' and lower(data_type) = 'bigint' and is_nullable = 'yes'",
                "column_name = 'ticket_count' and lower(data_type) = 'bigint' and is_nullable = 'no'",
                "upper(extra) = 'stored generated'",
                "<=> 'coalescesource_club_id,0'",
                "group by index_name",
                "count(*) <=> 2",
                "min(non_unique) <=> 0",
                "min(index_type) <=> 'btree' and max(index_type) <=> 'btree'",
                "count(sub_part) <=> 0",
                "min(is_visible) <=> 'yes'",
                "sum(seq_in_index = 1 and column_name = 'funds_snapshot_id') <=> 1",
                "sum(seq_in_index = 2 and column_name = 'source_key') <=> 1",
                "referenced_table_name = 'topic_edition_funds' and referenced_column_name = 'id'",
                "<=> 'source_club_idisnullorsource_club_id>0'",
                "<=> 'ticket_count>0andearned_amount>=0'",
        };
        for (String discriminator : discriminators) {
            assertEquals(2, countOf(normalized, discriminator),
                    "写前 guard 与写后 readback 必须用同一条判据：" + discriminator);
        }

        assertFalse(normalized.replace(" ", "").contains("min(column_name)<=>'funds_snapshot_id'"),
                "对复合唯一键只取 MIN(column_name) 等于只核一列，列序颠倒会被判成形状正确");
    }

    /**
     * 写前 guard 必须落在 CREATE TABLE IF NOT EXISTS 与回填 UPDATE 之前：
     * 形状不对时绝不能先把钱写进 ticket_source_amount，也不能让同名错表被当成已完成。
     */
    @Test
    void finalClosureGuardsRunBeforeCreateTableAndBeforeTheMoneyBackfill() throws Exception {
        String normalized = normalizedClosure();

        int createTable = normalized.indexOf("create table if not exists topic_edition_funds_attribution");
        int backfill = normalized.indexOf("update topic_edition_finalization f");
        assertTrue(createTable > 0 && backfill > createTable, "文件必须仍然自己建表并回填历史头");

        for (String discriminator : new String[]{
                "<=> 'coalescesource_club_id,0'",
                "sum(seq_in_index = 2 and column_name = 'source_key') <=> 1",
                "<=> 'ticket_count>0andearned_amount>=0'"}) {
            assertTrue(normalized.indexOf(discriminator) < createTable,
                    "同名错表必须在 CREATE TABLE IF NOT EXISTS 把它当成已完成之前红：" + discriminator);
            assertTrue(normalized.lastIndexOf(discriminator) > createTable,
                    "写后还必须再观测一次，不能只靠写前 guard：" + discriminator);
        }

        String columnGuard = "lower(data_type) = 'decimal' and numeric_precision = 14 and numeric_scale = 2";
        assertTrue(normalized.indexOf(columnGuard) < backfill,
                "ticket_source_amount 的形状必须在回填 UPDATE 真写钱之前证明");
    }

    /**
     * 声明的 scalar 回读条数与「独立 SELECT 语句」条数必须一致，
     * 否则 IT 侧「剥注释 → 按 ; 切 → 取 SELECT 打头」的逐条消费会与文件对不上。
     */
    @Test
    void finalClosureDeclaresEveryReadbackAsAStandaloneAssertedSelect() throws Exception {
        String sql = read(CLOSURE);

        assertEquals(11, countOf(sql, "-- runner: assert=scalar-true"),
                "两条写前 guard + attribution 四条形状 + attribution 两条 CHECK + 终结头列 "
                        + "+ 两条 CHECK 语义 = 11，各自可单独变红");
        int standaloneSelects = 0;
        for (String statement : executableSql(sql).split(";")) {
            if (statement.trim().regionMatches(true, 0, "SELECT", 0, "SELECT".length())) {
                standaloneSelects++;
            }
        }
        assertEquals(11, standaloneSelects,
                "每条声明的回读都必须是独立的、以 SELECT 打头的语句，IT 才能逐条跑并读回标量");
    }
}

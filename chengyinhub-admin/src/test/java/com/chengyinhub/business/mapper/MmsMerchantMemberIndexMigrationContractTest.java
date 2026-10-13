package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmsMerchantMemberIndexMigrationContractTest {
    private static final Path MIGRATION = Paths.get(
            "sql", "migration_mms_merchant_member_index_20260824.sql");

    @Test
    void migrationAddsExactVisibleMemberIndexWithIdempotentReadback() throws Exception {
        assertTrue(Files.isRegularFile(MIGRATION),
                "accept 的 member_id locking count 必须自带可发布 migration");
        String sql = normalized(read(MIGRATION));

        assertTrue(sql.contains("from information_schema.statistics")
                        && sql.contains("table_name = 'mms_merchant'")
                        && sql.contains("index_name = 'idx_mms_merchant_member'"),
                "重跑前必须从 MySQL 自身回读目标索引，不能盲目 ADD INDEX");
        assertTrue(sql.contains("alter table mms_merchant add index idx_mms_merchant_member (member_id), algorithm=inplace, lock=none")
                        && sql.contains("prepare mms_merchant_member_index_stmt")
                        && sql.contains("deallocate prepare mms_merchant_member_index_stmt"),
                "MySQL 8 必须用在线 secondary-index DDL，并保留可重跑分支");
        assertTrue(sql.contains("-- runner: assert=scalar-true")
                        && sql.contains("seq_in_index = 1")
                        && sql.contains("column_name = 'member_id'")
                        && sql.contains("non_unique = 1")
                        && sql.contains("index_type = 'btree'")
                        && sql.contains("sub_part is null")
                        && sql.contains("is_visible = 'yes'"),
                "执行后必须精确回读列序、唯一性、类型、前缀与可见性");
    }

    @Test
    void dependencyAndMapperContractsKeepTheLockingLookupOnMemberId() throws Exception {
        String dependencies = normalized(read(Paths.get("..", "ci", "migration-dependencies.json")));
        String mapper = normalized(read(Paths.get("..", "chengyinhub-system", "src", "main", "resources",
                "mapper", "business", "MmsMerchantMapper.xml")));

        assertTrue(dependencies.contains("\"mms_merchant_member_index_20260824\": []"),
                "新增 migration 即使没有批内前置，也必须显式登记依赖事实");
        int statement = mapper.indexOf("id=\"countmmsmerchantrowsbymemberidforupdate\"");
        assertTrue(statement >= 0);
        String lockingCount = mapper.substring(statement,
                Math.min(mapper.length(), statement + 320));
        assertTrue(lockingCount.contains("from mms_merchant")
                && lockingCount.contains("where member_id = #{id}")
                && lockingCount.contains("for update"));
        assertFalse(lockingCount.contains(" limit "),
                "邀请接受必须 count/exists 全量判存在，不能用 LIMIT 1 隐藏多门店事实");
    }

    private String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private String normalized(String text) {
        return text.toLowerCase().replaceAll("\\s+", " ").trim();
    }
}

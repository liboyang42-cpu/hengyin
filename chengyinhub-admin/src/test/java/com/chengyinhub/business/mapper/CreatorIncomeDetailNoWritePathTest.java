package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * f-money P0-17：幂等唯一键迁移已备好，但**现在不允许有任何写入路径**（审计 A-6：「不能新增写入」）。
 *
 * <p>与 {@link CreatorIncomeDetailWriteGuardTest} 互补：那一条只管「有写入就必须有唯一键」；本条直接断言
 * 生产源码里（所有模块的 Java 与 MyBatis XML）对 creator_income_detail 的 INSERT / REPLACE / UPDATE / DELETE 为零，
 * 并锁住唯一键迁移已登记。将来接写入是业主决定的新功能，届时需同时改本测试并说明。</p>
 */
class CreatorIncomeDetailNoWritePathTest {

    private static final Pattern WRITE = Pattern.compile(
            "(?is)\\b(?:(?:insert|replace)\\s+(?:ignore\\s+)?into|update|delete\\s+from)\\s+`?creator_income_detail`?\\b");
    private static final List<String> MODULES = List.of("chengyinhub-admin", "chengyinhub-system", "chengyinhub-framework",
            "chengyinhub-common", "chengyinhub-quartz", "chengyinhub-generator");
    private static final String MIGRATION = "chengyinhub-admin/sql/migration_20261111_creator_income_detail_idempotency.sql";

    private static Path root() {
        return Files.exists(Paths.get("chengyinhub-system")) ? Paths.get("") : Paths.get("..");
    }

    @Test
    void noProductionCodeWritesCreatorIncomeDetail() throws IOException {
        Path root = root();
        List<String> writers = new ArrayList<>();
        int scanned = 0;
        for (String module : MODULES) {
            Path main = root.resolve(module).resolve("src/main");
            if (!Files.isDirectory(main)) continue;
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".xml") || p.toString().endsWith(".java"))::iterator) {
                    scanned++;
                    if (WRITE.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).find()) {
                        writers.add(root.relativize(file).toString());
                    }
                }
            }
        }
        assertTrue(scanned > 1000, "扫描范围异常，只扫到 " + scanned + " 个文件");
        assertEquals(List.of(), writers, "creator_income_detail 现在不允许有写入路径（审计 P0-17）");
    }

    @Test
    void theOnlyMapperOnTheTableIsReadOnly() throws IOException {
        String xml = new String(Files.readAllBytes(root().resolve(
                "chengyinhub-system/src/main/resources/mapper/business/CreatorIncomeDetailMapper.xml")), StandardCharsets.UTF_8);
        assertTrue(xml.contains("<select id=\"selectRecentIncome\""));
        assertFalse(Pattern.compile("<(insert|update|delete)\\b").matcher(xml).find(), "CreatorIncomeDetailMapper.xml 只能有 select");
    }

    @Test
    void idempotencyUniqueKeyMigrationIsPresentAndRegistered() throws IOException {
        String sql = new String(Files.readAllBytes(root().resolve(MIGRATION)), StandardCharsets.UTF_8);
        assertTrue(Pattern.compile("(?is)ADD UNIQUE KEY uk_creator_income_idempotency \\(idempotency_key\\)").matcher(sql).find());
        assertTrue(Pattern.compile("(?is)creator_income_detail.{0,4000}?unique\\s+(key|index)\\b[^;]{0,200}idempotency_key").matcher(sql).find(),
                "CreatorIncomeDetailWriteGuardTest 的唯一键识别必须认得本迁移");
        String deps = new String(Files.readAllBytes(root().resolve("ci/migration-dependencies.json")), StandardCharsets.UTF_8);
        assertTrue(deps.contains("\"20261111_creator_income_detail_idempotency\": [\"p3_phase_b_player_world_creator\"]"),
                "迁移必须登记在 ci/migration-dependencies.json，并排在建表迁移之后");
    }

    @Test
    void negativeControlRecognisesEveryWriteShape() {
        assertTrue(WRITE.matcher("INSERT INTO creator_income_detail (a) VALUES (1)").find());
        assertTrue(WRITE.matcher("insert ignore into `creator_income_detail`(a) values (1)").find());
        assertTrue(WRITE.matcher("replace into creator_income_detail(a) values(1)").find());
        assertTrue(WRITE.matcher("update creator_income_detail set amount = 0").find());
        assertTrue(WRITE.matcher("DELETE FROM creator_income_detail WHERE id = 1").find());
        assertFalse(WRITE.matcher("select * from creator_income_detail").find());
        assertFalse(WRITE.matcher("update creator_income_detail_archive set x=1").find());
    }
}

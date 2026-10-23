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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-17:creator_income_detail 目前只有 PK 和普通索引,没有幂等唯一键。现在没有任何写入路径,所以不会重复入账;
 * 本闸保证「接入写入」和「补幂等唯一键」必须同时落地 —— 先有写入、后补唯一键的窗口里,回调重放就会重复记收益。
 */
class CreatorIncomeDetailWriteGuardTest {

    private static final Pattern WRITE = Pattern.compile(
            "(?is)\\b(insert|replace)\\s+(ignore\\s+)?into\\s+`?creator_income_detail`?\\b");
    private static final Pattern UNIQUE_KEY = Pattern.compile(
            "(?is)creator_income_detail.{0,4000}?unique\\s+(key|index)\\b[^;]{0,200}idempotency_key");

    @Test
    void noWritePathUntilAnIdempotencyUniqueKeyExists() throws IOException {
        Path root = Files.exists(Paths.get("chengyinhub-system")) ? Paths.get("") : Paths.get("..");
        List<String> writers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("chengyinhub-system/src/main"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".xml") || p.toString().endsWith(".java"))::iterator) {
                if (WRITE.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).find()) {
                    writers.add(root.relativize(file).toString());
                }
            }
        }
        if (writers.isEmpty()) return;
        boolean keyed = false;
        try (Stream<Path> files = Files.list(root.resolve("chengyinhub-admin/sql"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.getFileName().toString().startsWith("migration_"))::iterator) {
                if (UNIQUE_KEY.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).find()) keyed = true;
            }
        }
        assertTrue(keyed, "creator_income_detail 已有写入路径 " + writers
                + ",但 chengyinhub-admin/sql/migration_*.sql 里没有 idempotency_key 唯一键;先补唯一键再接写入");
    }

    @Test
    void negativeControlRecognisesAnInsert() {
        assertTrue(WRITE.matcher("<insert id=\"x\">insert ignore into creator_income_detail (a) values (1)</insert>").find());
        assertTrue(WRITE.matcher("INSERT INTO `creator_income_detail`(a) VALUES (1)").find());
        assertEquals(false, WRITE.matcher("select * from creator_income_detail").find());
    }
}

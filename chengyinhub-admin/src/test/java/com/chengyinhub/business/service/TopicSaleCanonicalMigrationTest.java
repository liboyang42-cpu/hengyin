package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopicSaleCanonicalMigrationTest {

    @Test
    void bothCanonicalTablesAreReplaySafeAfterPartialApply() throws Exception {
        String sql = new String(Files.readAllBytes(Paths.get(
                "sql/migration_topic_sale_canonical15_20260815.sql")), StandardCharsets.UTF_8)
                .toUpperCase();

        assertEquals(2, occurrences(sql, "CREATE TABLE IF NOT EXISTS"));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) count++;
        return count;
    }
}

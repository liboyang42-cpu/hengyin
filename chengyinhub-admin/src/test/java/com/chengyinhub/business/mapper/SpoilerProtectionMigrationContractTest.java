package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpoilerProtectionMigrationContractTest {
    @Test
    void revealIdempotencyKeysAreNonNullAndCoverBusinessIdentity() throws Exception {
        String sql = new String(Files.readAllBytes(Paths.get("sql",
                "migration_spoiler_protection_20260828.sql")), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertTrue(sql.contains("request_id VARCHAR(64) NOT NULL"));
        assertTrue(sql.contains("UNIQUE KEY uk_spoiler_content (member_id, content_type, content_id)"));
        assertTrue(sql.contains("UNIQUE KEY uk_spoiler_request (member_id, request_id)"));
        assertFalse(sql.contains("ADD COLUMN IF NOT EXISTS"));
    }
}

package com.chengyinhub.business.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalGovernanceMigrationContractTest {
    private static final String MIGRATION_KEY = "approval_governance_20260828";

    @Test
    void migrationRepairsNullRequestGateAndDeclaresDependencies() throws Exception {
        Path repo = Paths.get(System.getProperty("user.dir")).getParent();
        String sql = new String(Files.readAllBytes(repo.resolve(
                "chengyinhub-admin/sql/migration_approval_governance_20260828.sql")), StandardCharsets.UTF_8);
        JsonNode dependencies = new ObjectMapper().readTree(Files.readAllBytes(
                repo.resolve("ci/migration-dependencies.json"))).path("dependencies").path(MIGRATION_KEY);

        assertTrue(sql.contains("request_id VARCHAR(64) NOT NULL"));
        assertTrue(sql.contains("WHERE request_id IS NULL OR request_id = ''"));
        assertTrue(sql.contains("BEFORE INSERT ON business_audit_event"));
        assertTrue(sql.contains("NEW.request_id IS NULL OR NEW.request_id = ''"));
        assertTrue(sql.contains("SHA2(CONCAT_WS"));
        assertTrue(sql.contains("actor_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'"));
        assertTrue(sql.contains("and status = #{expectedStatus}") || mapperXml(repo).contains("and status = #{expectedStatus}"));
        assertFalse(sql.contains("ADD COLUMN IF NOT EXISTS"));
        assertTrue(dependencies.isArray() && dependencies.size() > 0);
    }

    private String mapperXml(Path repo) throws Exception {
        return new String(Files.readAllBytes(repo.resolve(
                "chengyinhub-system/src/main/resources/mapper/business/AuditTaskMapper.xml")), StandardCharsets.UTF_8);
    }
}

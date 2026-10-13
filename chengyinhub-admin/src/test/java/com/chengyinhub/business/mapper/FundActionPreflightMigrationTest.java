package com.chengyinhub.business.mapper;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FundActionPreflightMigrationTest {

    @Test
    void migrationRejectsNullAndDuplicateBusinessIntentKeys() throws Exception {
        String sql = new String(Files.readAllBytes(Paths.get(
                "sql/migration_fund_action_preflight_20260828.sql")), StandardCharsets.UTF_8);
        int start = sql.indexOf("CREATE TABLE fund_action_preflight");
        int end = sql.indexOf(";", start);
        String ddl = sql.substring(start, end)
                .replaceAll("(?m)^\\s*KEY idx_[^\\n]+\\n", "")
                .replaceAll("(?m)^\\s*CONSTRAINT chk_[^\\n]+\\n", "")
                .replaceAll(",\\s*\\)", ")")
                .replaceAll("\\) ENGINE=InnoDB.*$", ")");

        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:fund_preflight_migration_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            assertEquals(1, statement.executeUpdate(insert("'intent-1'", "'token-1'")));
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate(insert("'intent-1'", "'token-2'")),
                    "同一 member/action/requestId 必须由数据库唯一键拒绝");
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate(insert("NULL", "'token-3'")),
                    "requestId 为空必须由数据库 NOT NULL 拒绝，不能依赖 MySQL UNIQUE(NULL)");
        }
    }

    private String insert(String requestId, String tokenHash) {
        return "insert into fund_action_preflight(member_id,action_type,request_id,request_fingerprint,"
                + "challenge_nonce,challenge_token_hash,state,currency,risk_level,context_initiated_at,"
                + "expires_at,create_time,update_time) values("
                + "9201,'BANK_WITHDRAWAL'," + requestId + ",'fingerprint','nonce'," + tokenHash
                + ",0,'CNY','STATIC',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";
    }
}

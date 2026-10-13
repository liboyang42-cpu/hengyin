package com.chengyinhub.business.task;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 新巡检必须注册且默认启用，否则代码全绿但线上仍然零告警。 */
class ClubSessionReconciliationJobMigrationTest {

    @Test
    void migrationRegistersEnabledJobIdempotently() throws Exception {
        String sql = new String(Files.readAllBytes(Paths.get("sql",
                "migration_club_session_reconciliation_job_20260807.sql")), StandardCharsets.UTF_8)
                .toLowerCase().replaceAll("\\s+", " ");

        assertTrue(sql.contains("'clubsessionreconciliationtask.run()'"));
        assertTrue(sql.contains("'0', 'admin'"), "status=0 必须默认启用，不能把零告警延期为人工待办");
        assertTrue(sql.contains("where not exists") && sql.contains("invoke_target = 'clubsessionreconciliationtask.run()'"),
                "注册 SQL 必须可重复执行且不重复建 job");
    }
}

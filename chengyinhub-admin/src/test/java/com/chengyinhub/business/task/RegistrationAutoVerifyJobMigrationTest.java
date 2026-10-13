package com.chengyinhub.business.task;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RUN-01 自动核销任务必须注册且默认启用:ci/migration_gates.py 只保证「有 SQL」,
 * 本测试钉死 SQL 内容 —— bean 名与 invoke_target 拼错一个字母,门禁全绿而线上永不调度、零告警。
 */
class RegistrationAutoVerifyJobMigrationTest {

    @Test
    void migrationRegistersEnabledJobIdempotently() throws Exception {
        String sql = new String(Files.readAllBytes(Paths.get("sql",
                "migration_registration_auto_verify_job_20260919.sql")), StandardCharsets.UTF_8)
                .toLowerCase().replaceAll("\\s+", " ");

        assertTrue(sql.contains("'registrationautoverifytask.run()'"));
        assertTrue(sql.contains("'3', '1', '0', 'system'"), "status=0 必须默认启用,不能把零告警延期为人工待办");
        assertTrue(sql.contains("where not exists")
                        && sql.contains("invoke_target = 'registrationautoverifytask.run()'"),
                "注册 SQL 必须可重复执行且不重复建 job(DELETE+INSERT 会换 job_id 造成双触发器)");
        assertTrue(sql.contains("runner: assert=scalar-true"), "必须带回读断言,证明真落库且形状正确");
    }
}

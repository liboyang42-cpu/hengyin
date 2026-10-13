package com.chengyinhub.web.controller.api;

import com.chengyinhub.web.task.RoamHangoutExpireTask;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** bean 名与 sys_job 注册的 invoke_target 必须一字不差,否则 quartz 永远找不到它且零告警。 */
class RoamHangoutExpireTaskContractTest {
    @Test
    void bean名与注册SQL一致() throws Exception {
        String bean = RoamHangoutExpireTask.class.getAnnotation(Component.class).value();
        assertEquals("roamHangoutExpireTask", bean);
        Path p = Paths.get("sql/migration_roam_hangout_20260905.sql");
        if (!Files.exists(p)) p = Paths.get("chengyinhub-admin/sql/migration_roam_hangout_20260905.sql");
        String sql = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        assertTrue(sql.contains("'" + bean + ".run()'"), "迁移里必须注册 " + bean + ".run()");
        assertTrue(sql.contains("WHERE NOT EXISTS"), "注册必须幂等");
    }
}

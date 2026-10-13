package com.chengyinhub.business.task;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5-3:bean 名与方法名必须与 sys_job 注册的 invoke_target 一字不差 —— Quartz 找不到就是
 * 每 10 分钟静默 NoSuchMethodException,任务等于不存在(predictVoidExpiredTask 的老教训)。
 * 迁移文件与依赖声明也在这里钉住,防止「代码合了、注册 SQL 没写」的静默漏配。
 */
class OrderClosePayingReconcileTaskContractTest {

    private static final String MIGRATION = "migration_registration_paying_reconcile_job_20260915.sql";

    @Test
    void bean名与注册SQL和依赖声明一致() throws Exception {
        String bean = OrderCloseTask.class.getAnnotation(Component.class).value();
        assertEquals("orderCloseTask", bean);
        OrderCloseTask.class.getMethod("reconcileExpiredPaying");

        String sql = readFirstExisting(
                Paths.get("sql", MIGRATION),
                Paths.get("chengyinhub-admin", "sql", MIGRATION));
        assertTrue(sql.contains("'" + bean + ".reconcileExpiredPaying()'"),
                "迁移里必须注册 " + bean + ".reconcileExpiredPaying()");
        // P2-PT-4:DELETE+INSERT 重跑会换 job_id,已激活的旧 JobKey 仍在内存 Scheduler 里 ⇒ 两个触发器并跑。
        assertTrue(sql.contains("WHERE NOT EXISTS"),
                "注册必须幂等且不换 job_id(INSERT ... WHERE NOT EXISTS)");
        assertFalse(sql.toUpperCase().contains("DELETE FROM SYS_JOB"),
                "禁止 DELETE+INSERT 重注册:重跑会换 job_id");
        assertTrue(sql.contains("-- runner: assert=scalar-true"),
                "注册必须带回读断言,否则迁移跑没跑只有人肉知道");
        assertTrue(sql.contains("post-deploy"), "任务注册必须在 jar 部署后执行");

        String dependencies = readFirstExisting(
                Paths.get("..", "ci", "migration-dependencies.json"),
                Paths.get("ci", "migration-dependencies.json"));
        String migrationId = MIGRATION.substring("migration_".length(), MIGRATION.length() - ".sql".length());
        assertTrue(dependencies.contains("\"" + migrationId + "\""),
                "新增迁移必须在 ci/migration-dependencies.json 有条目");
    }

    private String readFirstExisting(Path... candidates) throws Exception {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("找不到文件: " + candidates[0]);
    }
}

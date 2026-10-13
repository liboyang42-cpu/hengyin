package com.chengyinhub.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 官方主题与主题广场是两个独立入口，但必须复用同一主题列表组件和既有角色可见性。
 *
 * <p>这是迁移合同测试：只写 C 菜单而不复制已有角色菜单关联，会让当前运营账号突然看不见广场入口；
 * 反过来若把历史待归属做成第三个 C 菜单，则违反 IA 已拍板的“双入口 + 入口内待办队列”边界。
 */
class TopicSourceMenuMigrationSqlTest {

    private static final String MIGRATION =
            "chengyinhub-admin/sql/migration_topic_source_menu_20260722.sql";
    private static final String CONTENT_SLOT_PERMISSION_MIGRATION =
            "migration_topic_source_menu_20260722_02_content_slot_permission.sql";
    private static final String ROLLBACK =
            "chengyinhub-admin/sql/bak_topic_source_menu_20260722_rollback.sql";

    private String sql() throws Exception {
        return WiringSource.read(MIGRATION).replaceAll("\\s+", " ");
    }

    private String rollbackSql() throws Exception {
        return WiringSource.read(ROLLBACK).replaceAll("\\s+", " ");
    }

    @Test
    void createsTwoSourceRoutesForTheSameTopicListComponent() throws Exception {
        String sql = sql();
        assertTrue(sql.contains("menu_name = '官方主题'"), sql);
        assertTrue(sql.contains("path = 'official-topic'"), sql);
        assertTrue(sql.contains("`query` = '{\"scope\":\"official\"}'"), sql);
        assertTrue(sql.contains("route_name = 'OfficialTopic'"), sql);
        assertTrue(sql.contains("'topic-plaza', 'business/topic/index', '{\"scope\":\"plaza\"}', 'TopicPlaza'"), sql);
        assertTrue(!sql.contains("'historical-topic'"), "历史待归属只能是入口内待办队列，不能注册第三个 C 菜单。");
    }

    @Test
    void clonesExistingTopicMenuVisibilityToTheNewPlazaRoute() throws Exception {
        String sql = sql();
        assertTrue(sql.contains("INSERT INTO sys_role_menu (role_id, menu_id)"), sql);
        assertTrue(sql.contains("FROM sys_role_menu rm"), sql);
        assertTrue(sql.contains("rm.menu_id = @officialTopicMenu"), sql);
        assertTrue(sql.contains("@topicPlazaMenu"), sql);
    }

    @Test
    void refusesToWriteWhenEitherTargetPathOrRouteNameIsAlreadyOccupied() throws Exception {
        String sql = sql();
        assertTrue(sql.contains("@legacyTopicMenuCount"), sql);
        assertTrue(sql.contains("@officialRouteConflict"), sql);
        assertTrue(sql.contains("@plazaPathConflict"), sql);
        assertTrue(sql.contains("@plazaRouteConflict"), sql);
        assertTrue(sql.contains("IF(@legacyTopicMenuCount <> 1, 1, 0)"), sql);
        assertTrue(sql.contains("@menuRouteConflict = 0"), sql);
    }

    @Test
    void failsClosedWhenAnExistingPlazaRouteIsOnlyPartiallyConfigured() throws Exception {
        String sql = sql();
        assertTrue(sql.contains("path <=> 'topic-plaza'"), sql);
        assertTrue(sql.contains("parent_id <=> @topicParent"), sql);
        assertTrue(sql.contains("route_name <=> 'TopicPlaza'"), sql);
        assertTrue(sql.contains("`query` <=> '{\"scope\":\"plaza\"}'"), sql);
        assertTrue(sql.contains("AND @plazaExactCount = 0"), sql);
    }

    @Test
    void preservesTheExistingTopicPermissionInsteadOfRewritingIt() throws Exception {
        String sql = sql();
        assertTrue(sql.contains("SET @topicPerms"), sql);
        assertTrue(sql.contains(", @topicPerms, 'guide'"), sql);
        assertFalse(sql.contains("perms = 'business:topic:list'"), sql);
    }

    @Test
    void includesIdScopedReadbackAndRollbackInstructions() throws Exception {
        String sql = sql();
        String rollback = rollbackSql();
        assertTrue(sql.contains("回读断言"), sql);
        assertTrue(rollback.contains("DELETE rm FROM sys_role_menu rm"), rollback);
        assertTrue(rollback.contains("route_name <=> 'OfficialTopic'"), rollback);
    }

    @Test
    void rollbackAlsoFailsClosedUnlessBothExactSourceMenusAreUnique() throws Exception {
        String rollback = rollbackSql();
        assertTrue(rollback.contains("@officialTopicMenuCount"), rollback);
        assertTrue(rollback.contains("@topicPlazaMenuCount"), rollback);
        assertTrue(rollback.contains("@rollbackReady = 1"), rollback);
        assertTrue(rollback.contains("path <=> 'official-topic'"), rollback);
        assertTrue(rollback.contains("path <=> 'topic-plaza'"), rollback);
    }

    private static final String SOURCE_MENU_MIGRATION_ID = "topic_source_menu_20260722";
    private static final String CONTENT_SLOT_PERMISSION_MIGRATION_ID =
            "topic_source_menu_20260722_02_content_slot_permission";

    /**
     * release-manifest 的执行顺序现在由 ci/migration_ledger.py 按 ci/migration-dependencies.json
     * 里声明的显式依赖做拓扑排序生成（不再是字典序），所以这条合同测试必须验证真实排序产物，
     * 而不是脚本源码里长什么样。
     */
    @Test
    void sourceMenuMigrationRunsBeforeItsDependentContentSlotPermissionMigration() throws Exception {
        assertTrue(WiringSource.exists("chengyinhub-admin/sql/" + CONTENT_SLOT_PERMISSION_MIGRATION));

        String dependenciesJson = WiringSource.read("ci/migration-dependencies.json");
        assertTrue(dependenciesJson.contains("\"" + CONTENT_SLOT_PERMISSION_MIGRATION_ID + "\""),
                "内容槽权限迁移必须在 ci/migration-dependencies.json 里声明依赖，"
                        + "否则发布台账的拓扑排序不会保证它晚于主题来源菜单迁移执行: " + dependenciesJson);

        Map<String, Integer> order = migrationLedgerOrder();
        assertTrue(order.containsKey(SOURCE_MENU_MIGRATION_ID)
                        && order.containsKey(CONTENT_SLOT_PERMISSION_MIGRATION_ID),
                "migration_ledger 未能对两个迁移排序: " + order);
        assertTrue(order.get(SOURCE_MENU_MIGRATION_ID) < order.get(CONTENT_SLOT_PERMISSION_MIGRATION_ID),
                "migration_ledger 依赖排序未能保证主题来源菜单先于内容槽权限迁移执行: " + order);
    }

    /**
     * 用仓库里真实的 ci/migration-dependencies.json 依赖声明，喂给生产用的
     * ci/migration_ledger.py#build_manifest 做真实拓扑排序，返回 {migrationId: order}。
     * 只挑这两个迁移放进一次性临时目录，绕开仓库里其余 SQL 与本测试无关的 inventory 问题。
     */
    private Map<String, Integer> migrationLedgerOrder() throws Exception {
        Path repoRoot = Paths.get("..");
        if (!Files.exists(repoRoot.resolve("ci/migration_ledger.py"))) {
            repoRoot = Paths.get(".");
        }

        String script = "import sys, os, json, tempfile, shutil\n"
                + "from pathlib import Path\n"
                + "repo_root = sys.argv[1]\n"
                + "sys.path.insert(0, os.path.join(repo_root, 'ci'))\n"
                + "from migration_ledger import build_manifest\n"
                + "deps_path = Path(repo_root) / 'ci/migration-dependencies.json'\n"
                + "all_deps = json.loads(deps_path.read_text(encoding='utf-8'))['dependencies']\n"
                + "source_id = '" + SOURCE_MENU_MIGRATION_ID + "'\n"
                + "content_slot_id = '" + CONTENT_SLOT_PERMISSION_MIGRATION_ID + "'\n"
                + "filtered_deps = {k: v for k, v in all_deps.items() if k == content_slot_id}\n"
                + "tmp = Path(tempfile.mkdtemp())\n"
                + "try:\n"
                + "    sql_dir = tmp / 'chengyinhub-admin/sql'\n"
                + "    sql_dir.mkdir(parents=True)\n"
                + "    (sql_dir / ('migration_%s.sql' % source_id)).write_text('SELECT 1;\\n')\n"
                + "    (sql_dir / ('migration_%s.sql' % content_slot_id)).write_text('SELECT 1;\\n')\n"
                + "    manifest = build_manifest(tmp, 'a' * 40, dependencies=filtered_deps)\n"
                + "    order = {m['migrationId']: m['order'] for m in manifest['migrations']}\n"
                + "    print(json.dumps(order))\n"
                + "finally:\n"
                + "    shutil.rmtree(tmp)\n";

        ProcessBuilder builder = new ProcessBuilder("python3", "-c", script, repoRoot.toString());
        builder.redirectErrorStream(false);
        Process process = builder.start();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (java.io.InputStream out = process.getInputStream(); java.io.InputStream err = process.getErrorStream()) {
            Thread outPump = new Thread(() -> copy(out, stdout));
            Thread errPump = new Thread(() -> copy(err, stderr));
            outPump.start();
            errPump.start();
            int exitCode = process.waitFor();
            outPump.join();
            errPump.join();
            assertEquals(0, exitCode, "migration_ledger.build_manifest 排序失败: " + stderr);
        }

        return new ObjectMapper().readValue(stdout.toByteArray(), Map.class);
    }

    private static void copy(java.io.InputStream in, ByteArrayOutputStream out) {
        byte[] buffer = new byte[4096];
        int read;
        try {
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

package com.chengyinhub.business.it;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Funds IT 的隔离库必须先叠路线迁移，才能加载包含路线列的 CmsTopicMapper。 */
class ExploreItDatabaseRouteMigrationWiringTest {

    @Test
    void routeSchemaMigrationsAreAppliedInDependencyOrder() throws Exception {
        Field field = ExploreItDatabase.class.getDeclaredField("MIGRATIONS");
        field.setAccessible(true);
        List<String> migrations = Arrays.asList((String[]) field.get(null));

        int graph = migrations.indexOf("sql/migration_20260823_topic_route_graph.sql");
        int published = migrations.indexOf("sql/migration_20260823_topic_route_published_snapshot.sql");
        assertTrue(graph >= 0, "Funds IT 隔离库缺少路线基础迁移");
        assertTrue(published > graph, "发布快照迁移必须排在路线基础迁移之后");
    }

    @Test
    void standaloneTopicFixturesContainAllMapperRouteColumns() throws Exception {
        assertRouteColumns("src/test/resources/it-schema/cms-topic-it-schema.sql");
        assertRouteColumns("src/test/resources/it-schema/complaint-it-schema.sql");
    }

    private void assertRouteColumns(String path) throws Exception {
        String sql = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        for (String column : Arrays.asList("route_mode", "route_graph_json", "config_version",
                "published_route_graph_json", "published_config_version")) {
            assertTrue(sql.contains("`" + column + "`"), path + " 缺少 " + column);
        }
    }
}

package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C:U-102:preset_route_node 数值坐标是由文本列派生的 STORED 生成列,写入方无需改动、也不能写它。 */
class PresetRouteNodeGeoMigrationContractTest {
    private static final Path SQL = Paths.get("sql/migration_preset_route_node_geo_numeric_20261024.sql");
    private static final Path MAPPER = Paths.get("../chengyinhub-system/src/main/resources/mapper/business/CmsPresetRouteNodeMapper.xml");
    private static final Path DEPENDENCIES = Paths.get("../ci/migration-dependencies.json");

    @Test
    void numericColumnsAreGeneratedFromTextColumnsAndIndexed() throws IOException {
        String sql = Files.readString(SQL, StandardCharsets.UTF_8);
        assertTrue(sql.contains("ADD COLUMN lng_num DECIMAL(10,7) GENERATED ALWAYS AS"));
        assertTrue(sql.contains("ADD COLUMN lat_num DECIMAL(10,7) GENERATED ALWAYS AS"));
        assertTrue(sql.contains("BETWEEN -180 AND 180") && sql.contains("BETWEEN -90 AND 90"), "越界坐标必须派生为 NULL");
        assertTrue(sql.contains("ADD INDEX idx_preset_node_geo (lat_num, lng_num)"));
        assertTrue(sql.split("information_schema", -1).length - 1 >= 3, "三个动作都要预检,可重复执行");
        assertTrue(sql.contains("-- runner: assert=scalar-true"));
        assertTrue(Files.readString(DEPENDENCIES, StandardCharsets.UTF_8)
                .contains("\"preset_route_node_geo_numeric_20261024\": [\"preset_route\"]"));
    }

    @Test
    void mapperNeverWritesGeneratedColumns() throws IOException {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);
        assertFalse(xml.contains("lng_num") || xml.contains("lat_num"), "生成列由库派生,写入会报 3105");
    }
}

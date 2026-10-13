package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.RoamRevisitFact;
import com.chengyinhub.business.domain.vo.RoamRevisitReportVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoamRevisitReportContractTest {
    private static final Path SYSTEM = Paths.get("../chengyinhub-system");
    private static final Path REPORT_XML = SYSTEM.resolve("src/main/resources/mapper/business/RoamRevisitReportMapper.xml");
    private static final Path WRITE_XML = SYSTEM.resolve("src/main/resources/mapper/business/RoamMapper.xml");
    private static final Path CONTROLLER = Paths.get(
            "src/main/java/com/chengyinhub/web/controller/business/RoamRevisitReportController.java");
    private static final Path PERMISSION_SQL = Paths.get("sql/migration_20260815_tandianri_roam_revisit_report_permission.sql");
    private static final Path INDEX_SQL = Paths.get("sql/migration_20260815_tandianri_roam_revisit_report_indexes.sql");

    @Test
    void reportMapperIsSelectOnlyAndReadsOnlyExplicitFactTables() throws IOException {
        String xml = read(REPORT_XML).toLowerCase(Locale.ROOT);
        assertTrue(xml.contains("<select"));
        assertFalse(Pattern.compile("<(insert|update|delete)\\b").matcher(xml).find(), "report mapper 只能有 select");
        assertFalse(Pattern.compile("\\b(insert|update|delete|call)\\b").matcher(stripXmlComments(xml)).find(),
                "报表 SQL 不得出现写语句或存储过程调用");

        Set<String> tables = new HashSet<>();
        Matcher matcher = Pattern.compile("\\b(?:from|join)\\s+([a-z0-9_]+)").matcher(xml);
        while (matcher.find()) tables.add(matcher.group(1));
        assertEquals(new HashSet<>(Arrays.asList("roam_shop_visit", "roam_session")), tables);
        assertFalse(Pattern.compile("\\b(player_node_progress|[a-z0-9_]+_settlement[a-z0-9_]*|"
                        + "cms_registration[a-z0-9_]*|oms_order[a-z0-9_]*|[a-z0-9_]+_payment[a-z0-9_]*|"
                        + "[a-z0-9_]+_refund[a-z0-9_]*)\\b")
                .matcher(xml).find(), "报表 mapper 不得触达玩法、报名、结算、订单、支付或退款表");
    }

    @Test
    void reportMapperXmlIsLoadableAndRegistersOnlyTheFourReadStatements() throws IOException {
        Configuration configuration = new Configuration();
        String resource = "mapper/business/RoamRevisitReportMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String namespace = RoamRevisitReportMapper.class.getName() + ".";
        assertTrue(configuration.hasStatement(namespace + "selectShopDataCutoffAt"));
        assertTrue(configuration.hasStatement(namespace + "selectRoamDataCutoffAt"));
        assertTrue(configuration.hasStatement(namespace + "selectShopVisitFacts"));
        assertTrue(configuration.hasStatement(namespace + "selectRoamSessionFacts"));
    }

    @Test
    void reportSqlPreservesMetricFactBoundaries() throws IOException {
        String xml = read(REPORT_XML);
        assertTrue(xml.contains("source_type"));
        assertTrue(xml.contains("source_id"));
        assertTrue(xml.contains("from roam_shop_visit"));
        assertTrue(xml.contains("from roam_session"));
    }

    @Test
    void reportFactsDropUnusedShopNameProjectionAndOrdering() throws IOException {
        String xml = stripXmlComments(read(REPORT_XML)).toLowerCase(Locale.ROOT);
        assertFalse(xml.contains("shop_name"), "shop_name 没有消费方，不得查询或装入事实 DTO");
        assertFalse(Pattern.compile("\\border\\s+by\\b").matcher(xml).find(),
                "消费方只使用集合，事实查询不得承担无消费排序");

        Set<String> fields = new HashSet<>();
        for (Field field : RoamRevisitFact.class.getDeclaredFields()) {
            if (!field.isSynthetic()) fields.add(field.getName());
        }
        assertEquals(new HashSet<>(Arrays.asList(
                "memberId", "sessionId", "sourceType", "sourceId", "occurredAt")), fields);
    }

    @Test
    void revisitFactTimeIndexesHaveExplicitIdempotentExistenceGuards() throws IOException {
        String sql = stripSqlComments(read(INDEX_SQL)).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        assertTrue(sql.contains("from information_schema.statistics"));
        assertTrue(sql.contains("group_concat(column_name order by seq_in_index separator ',')"));
        assertTrue(sql.contains("table_name = 'roam_session' and index_name = 'idx_roam_session_started_at'"));
        assertTrue(sql.contains("@roam_session_started_at_shape is null "
                + "or @roam_session_started_at_shape <> 'started_at'"));
        assertTrue(sql.contains("alter table roam_session add index idx_roam_session_started_at (started_at)"));
        assertTrue(sql.contains("table_name = 'roam_shop_visit' and index_name = 'idx_roam_shop_visit_visited_at'"));
        assertTrue(sql.contains("@roam_shop_visit_visited_at_shape is null "
                + "or @roam_shop_visit_visited_at_shape <> 'visited_at'"));
        assertTrue(sql.contains("alter table roam_shop_visit add index idx_roam_shop_visit_visited_at (visited_at)"));
    }

    @Test
    void roamMetricSqlUsesStartedAtFactsWithoutSettlementFilters() throws IOException {
        String xml = read(REPORT_XML).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        assertTrue(xml.contains("from roam_session where started_at &gt;= #{from} and started_at &lt; #{to}"));
        assertFalse(xml.contains("finished_at"));
        assertFalse(xml.contains("status"));
    }

    @Test
    void reportCodeStaysSeparateFromStateWritingRoamMapper() throws IOException {
        String writeMapper = read(WRITE_XML);
        assertFalse(writeMapper.contains("RoamRevisitReport"));
        assertFalse(writeMapper.contains("SHOP_REVISIT"));
        assertFalse(writeMapper.contains("ROAM_REVISIT"));
    }

    @Test
    void factAndReportObjectsAreImmutable() {
        assertImmutable(RoamRevisitFact.class);
        assertImmutable(RoamRevisitReportVO.class);
    }

    @Test
    void permissionMigrationOnlyRegistersTheIndependentReadPermission() throws IOException {
        String sql = read(PERMISSION_SQL).toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("business:roamrevisitreport:list"));
        assertTrue(sql.contains("insert into sys_menu"));
        assertFalse(Pattern.compile("(?im)^\\s*(update|delete|call|alter|create|drop|truncate)\\b")
                .matcher(stripSqlComments(sql)).find());
        assertFalse(stripSqlComments(sql).contains("sys_job"));
    }

    @Test
    void controllerPermissionMatchesTheFailClosedMigrationContract() throws IOException {
        String controller = read(CONTROLLER);
        String migration = read(PERMISSION_SQL);
        String permission = "business:roamRevisitReport:list";
        String bare = stripSqlComments(migration);
        String quotedPermission = "'" + permission + "'";
        assertTrue(controller.contains("@ss.hasPermi('" + permission + "')"),
                "controller 必须使用独立只读权限，不能放宽或漂移");
        assertEquals(3, bare.split(Pattern.quote(quotedPermission), -1).length - 1,
                "migration 写入 / 幂等守卫 / 执行后回读必须同时锁定 controller 权限");
        assertTrue(Pattern.compile("'F',\\s*'0',\\s*'0',\\s*" + Pattern.quote(quotedPermission))
                        .matcher(bare).find(),
                "migration 写入 sys_menu 的 perms 必须与 controller 一致");
        assertTrue(bare.replaceAll("\\s+", " ").contains("menu_type, visible, status, perms, icon"),
                "migration 写入列序必须把 controller 权限落到 perms");
    }

    @Test
    void starTestIsCollectedByTheExistingBackendCiJob() throws IOException {
        String backend = read(Paths.get("../ci/backend-test.sh"));
        String workflow = read(Paths.get("../.github/workflows/ci.yml"));
        for (String testClass : Arrays.asList(
                "business/mapper/RoamRevisitReportContractTest.java",
                "business/service/impl/RoamRevisitReportServiceTest.java",
                "web/controller/business/RoamRevisitReportControllerTest.java")) {
            Path testPath = Paths.get("src/test/java/com/chengyinhub").resolve(testClass);
            assertTrue(Files.isRegularFile(testPath), testPath + " 必须存在");
            assertTrue(testPath.getFileName().toString().endsWith("Test.java"),
                    testPath + " 必须命中 Surefire 默认 *Test 收集规则");
        }
        assertTrue(backend.contains("mvn -B clean test"));
        assertTrue(workflow.contains("- 'chengyinhub-admin/**'"));
        assertTrue(workflow.contains("run: bash ci/backend-test.sh"));
    }

    private static void assertImmutable(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                assertTrue(Modifier.isFinal(field.getModifiers()), type.getSimpleName() + "." + field.getName());
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            assertFalse(method.getName().startsWith("set"), type.getSimpleName() + " 不得暴露 setter");
        }
    }

    private static String stripXmlComments(String text) {
        return text.replaceAll("(?s)<!--.*?-->", " ");
    }

    private static String stripSqlComments(String text) {
        return text.replaceAll("(?m)--.*$", " ");
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}

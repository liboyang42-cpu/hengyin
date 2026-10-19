package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.domain.vo.TemplateLibraryVO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.LegacyCmsMutationGuard;
import com.chengyinhub.business.service.support.LegacyCmsTemplateApprovalBoundary;
import com.chengyinhub.business.service.support.LegacyCmsTemplateBodyReader;
import com.chengyinhub.business.service.support.LegacyCmsProvenanceGuard;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.sql.Connection;
import java.util.Collections;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 MySQL + Mapper + Spring 事务：作者修改、审核、撤回不能相互覆盖。 */
class TemplateMarketLifecycleMySqlIT {
    private static PooledDataSource source;
    private static SqlSessionTemplate sql;
    private static DataSourceTransactionManager transactions;
    private JdbcTemplate jdbc;
    private CmsMemberTemplateMapper members;
    private CmsTemplateLibraryMapper libraries;
    private AuditTaskMapper tasks;
    private AuditTaskServiceImpl auditTarget;
    private IAuditTaskService audit;
    private ITemplatePublishService publish;

    @BeforeAll
    static void database() throws Exception {
        source = new PooledDataSource("com.mysql.cj.jdbc.Driver", MybatisItDatabase.url("template_market"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration config = new Configuration();
        for (Class<?> type : new Class<?>[]{CmsMemberTemplate.class, CmsTemplateLibrary.class,
                MemberTemplateVO.class, TemplateLibraryVO.class, AuditTask.class, AuditRecord.class,
                BusinessAuditEvent.class}) config.getTypeAliasRegistry().registerAlias(type.getSimpleName(), type);
        config.setEnvironment(new Environment("it", new SpringManagedTransactionFactory(), source));
        for (String name : new String[]{"CmsMemberTemplate", "CmsTemplateLibrary", "AuditTask", "AuditRecord", "BusinessAuditEvent", "AppMediaAsset", "LegacyCmsTemplateRead", "LegacyCmsProvenance"}) {
            String resource = "mapper/business/" + name + "Mapper.xml";
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        transactions = new DataSourceTransactionManager(source);
        try (Connection connection = source.getConnection()) {
            assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("8.0"));
            ItSchema.apply(connection, "it-schema/journey-full-flow-it-schema.sql");
            // Test-only consumer schema; production migration remains owned by the paid materializer.
            connection.createStatement().execute("ALTER TABLE cms_member_template ADD w18_provenance_kind VARCHAR(32) NULL");
            connection.createStatement().execute("CREATE TABLE w18_cms_template_provenance(template_id BIGINT PRIMARY KEY)");
            connection.createStatement().execute("CREATE TABLE cms_template_library LIKE cms_member_template");
            connection.createStatement().execute("ALTER TABLE cms_template_library ADD member_template_id BIGINT, ADD pack_type INT, ADD INDEX idx_member_template(member_template_id)");
            String base = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../sql/ry_20250522.sql")), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher menu = java.util.regex.Pattern.compile("(?is)create table sys_menu\\s*\\(.*?;" ).matcher(base);
            assertTrue(menu.find(), "基础 SQL 必须包含 sys_menu 的真实 DDL");
            connection.createStatement().execute(menu.group());
            ItSchema.applyMigrations(connection, "sql/migration_p3_platform_foundation.sql",
                    "sql/migration_audit_open_task_guard_20260721.sql", "sql/migration_business_audit_event_20260823.sql",
                    "sql/migration_20261012_app_media_asset.sql", "sql/migration_20261012_app_media_asset_namespaces.sql");
            String governance = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("sql/migration_approval_governance_20260828.sql")), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher alter = java.util.regex.Pattern.compile("(?is)ALTER TABLE (?:audit_record|business_audit_event)\\s+.*?;").matcher(governance);
            int applied = 0;
            while (alter.find()) { connection.createStatement().execute(alter.group()); applied++; }
            assertEquals(2, applied, "审核治理的两条真实 ALTER 必须加载");
        }
    }

    @AfterAll
    static void close() { if (source != null) source.forceCloseAll(); }

    @BeforeEach
    void services() {
        jdbc = new JdbcTemplate(source);
        for (String table : new String[]{"business_audit_event", "audit_record", "audit_task", "w18_cms_template_provenance", "cms_template_library", "cms_member_template"})
            jdbc.update("DELETE FROM " + table);
        members = sql.getMapper(CmsMemberTemplateMapper.class);
        libraries = sql.getMapper(CmsTemplateLibraryMapper.class);
        tasks = sql.getMapper(AuditTaskMapper.class);
        auditTarget = new AuditTaskServiceImpl();
        field(auditTarget, "templateMapper", members); field(auditTarget, "libraryMapper", libraries);
        field(auditTarget, "auditTaskMapper", tasks);
        field(auditTarget, "auditRecordMapper", sql.getMapper(AuditRecordMapper.class));
        field(auditTarget, "businessAuditEventMapper", sql.getMapper(BusinessAuditEventMapper.class));
        field(auditTarget, "legacyTemplateApprovalBoundary", proxy(new LegacyCmsTemplateApprovalBoundary(
                sql.getMapper(LegacyCmsTemplateReadMapper.class))));
        ObjectProvider<IImService> im = mock(ObjectProvider.class);
        when(im.getObject()).thenReturn(mock(IImService.class)); field(auditTarget, "imServiceProvider", im);
        field(auditTarget, "mediaPublicationGuard", MediaPublicationMySqlFixture.guard(sql, members));
        audit = proxy(auditTarget);
        CmsMemberTemplateServiceImpl memberService = new CmsMemberTemplateServiceImpl();
        field(memberService, "cmsMemberTemplateMapper", members);
        LegacyCmsMutationGuard mutationGuard = proxy(new LegacyCmsMutationGuard(sql.getMapper(LegacyCmsTemplateReadMapper.class)));
        field(memberService, "legacyCmsMutationGuard", mutationGuard);
        field(memberService, "legacyCmsTemplateBodyReader", proxy(new LegacyCmsTemplateBodyReader(
                sql.getMapper(LegacyCmsTemplateReadMapper.class), members)));
        CmsTemplateLibraryServiceImpl libraryService = new CmsTemplateLibraryServiceImpl();
        field(libraryService, "cmsTemplateLibraryMapper", libraries);
        TemplatePublishServiceImpl target = new TemplatePublishServiceImpl();
        field(target, "legacyCmsMutationGuard", mutationGuard);
        field(target, "legacyCmsProvenanceGuard", new LegacyCmsProvenanceGuard(sql.getMapper(LegacyCmsProvenanceMapper.class)));
        field(target, "memberTemplateService", proxy(memberService)); field(target, "memberTemplateMapper", members);
        field(target, "templateLibraryService", libraryService); field(target, "libraryMapper", libraries);
        field(target, "auditTaskMapper", tasks); field(target, "auditTaskService", audit);
        field(target, "capabilityGuard", mock(ICapabilityGuard.class));
        field(target, "mediaPublicationGuard", MediaPublicationMySqlFixture.guard(sql, members));
        publish = proxy(target);
    }

    @Test
    void legacy42ConfigsAdoptSaveAndIndependentReadbackWithoutLosingMechanics() throws Exception {
        jdbc.execute("ALTER TABLE cms_template_library CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        String[] seedFiles = {"migration_seed_merchant_game_a8_20260823.sql",
                "migration_seed_merchant_game_b27_20260823.sql", "migration_seed_merchant_game_v51_20260827.sql"};
        jdbc.execute("CREATE TABLE sys_category (id BIGINT AUTO_INCREMENT PRIMARY KEY, category_name VARCHAR(64), type INT, status INT, del_flag INT) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        java.util.Set<String> categories = new java.util.LinkedHashSet<>();
        for (String file : seedFiles) {
            String text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("sql", file)), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^\\s*\\('[a-z0-9_]+', '[^'{}]+', '([^'{}]{1,64})'\\)[,;]?$" ).matcher(text);
            while (matcher.find()) categories.add(matcher.group(1));
        }
        assertFalse(categories.isEmpty());
        for (String category : categories) jdbc.update("INSERT INTO sys_category(category_name,type,status,del_flag) VALUES (?,1,1,0)", category);
        // 采用链路的夹具读取真实种子配置，不宣称重放历史迁移。
        // A8 历史脚本末尾仍检查旧封面路径；不改历史脚本、不绕过它的断言。
        for (String file : seedFiles) {
            String text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("sql", file)), java.nio.charset.StandardCharsets.UTF_8);
            java.util.Map<String, String[]> metadata = new java.util.LinkedHashMap<>();
            java.util.regex.Matcher markers = java.util.regex.Pattern.compile("(?m)^-- TEMPLATE ([a-z0-9_]+)\\|([^|]+)\\|([^|]+)\\|").matcher(text);
            while (markers.find()) metadata.put(markers.group(1), new String[]{markers.group(2), file.contains("_b27_") ? "0" : markers.group(3)});
            java.util.regex.Matcher configs = java.util.regex.Pattern.compile("VALUES \\('([a-z0-9_]+)', '(\\{[\\s\\S]*?\\})'(?:, (?:NULL|'([\\s\\S]*?)'))?\\);").matcher(text);
            while (configs.find()) {
                String[] meta = metadata.get(configs.group(1)); assertNotNull(meta, file + ":" + configs.group(1));
                CmsTemplateLibrary row = new CmsTemplateLibrary();
                row.setTitle(meta[0]); row.setValidationMethod(Integer.parseInt(meta[1]));
                String seedConfig = configs.group(2).replace("''", "'");
                if ("rhythm_blind_guess".equals(configs.group(1))) {
                    com.fasterxml.jackson.databind.JsonNode items = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(seedConfig).path("random").path("items");
                    assertEquals(3, items.size());
                    String[] clips = {"one", "two", "three"};
                    for (int index = 0; index < clips.length; index++)
                        assertEquals("/audio/merchant-games/rhythm-" + clips[index] + ".mp3",
                                items.get(index).path("audioUrl").asText());
                }
                row.setAdvancedConfigJson(seedConfig);
                row.setPreferenceJson(configs.group(3) == null ? null : configs.group(3).replace("''", "'"));
                row.setDescription("旧版较长的玩法介绍需要完整保留，采用与再次保存都不能静默截断或丢掉内容。");
                row.setDuration(7L); row.setMemberId(100000L); row.setStatus(1L); row.setPublishStatus(1L); row.setDelFlag(0);
                row.setCreateBy(file.replace("migration_", "").replace(".sql", ""));
                assertEquals(1, libraries.insertCmsTemplateLibrary(row));
            }
        }
        try (Connection connection = source.getConnection()) {
            ItSchema.applyMigrations(connection, "sql/migration_20261012_legacy_game_v51_category_collation.sql");
        }
        assertEquals(42, count("cms_template_library"));
        assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM cms_template_library WHERE create_by='seed_merchant_game_v51_20260827' AND activity_categoryids IS NOT NULL", Integer.class));
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.chengyinhub.business.service.support.AdvancedGameConfigValidator validator = new com.chengyinhub.business.service.support.AdvancedGameConfigValidator();
        for (Long libraryId : jdbc.queryForList("SELECT id FROM cms_template_library ORDER BY id", Long.class)) {
            CmsTemplateLibrary original = libraries.selectCmsTemplateLibraryById(libraryId);
            com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest request = new com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest();
            request.setOriginalTemplateId(libraryId); request.setAdoptSourceOnly(true); request.setTitle("客户端占位，不应保存");
            Long draftId;
            try { draftId = publish.saveOrUpdateDraft(request, 7L, "local-test-author"); }
            catch (RuntimeException failure) {
                throw new AssertionError("采用失败:library=" + libraryId + " title=" + original.getTitle()
                        + "，种子配置保持原值，不跳过媒体准入", failure);
            }
            // 用独立物理连接回读，不依赖写事务或 Mapper 缓存。
            try (Connection readback = java.sql.DriverManager.getConnection(MybatisItDatabase.url("template_market"), MybatisItDatabase.user(), MybatisItDatabase.password());
                 java.sql.PreparedStatement query = readback.prepareStatement("SELECT member_id,original_template_id,title,description,duration,activity_categoryids,advanced_config_json,publish_status FROM cms_member_template WHERE id=?")) {
                query.setLong(1, draftId);
                try (java.sql.ResultSet row = query.executeQuery()) {
                    assertTrue(row.next()); assertEquals(7L, row.getLong("member_id"));
                    assertEquals(libraryId.longValue(), row.getLong("original_template_id"));
                    assertEquals(original.getTitle(), row.getString("title"));
                    assertEquals(original.getDescription(), row.getString("description"));
                    assertEquals(original.getDuration().longValue(), row.getLong("duration"));
                    assertEquals(original.getActivityCategoryids(), row.getString("activity_categoryids"));
                    assertEquals(json.readTree(validator.validateAndNormalize(original.getAdvancedConfigJson())), json.readTree(row.getString("advanced_config_json")));
                    assertEquals(0L, row.getLong("publish_status"));
                }
            }
            CmsMemberTemplate own = members.selectCmsMemberTemplateById(draftId);
            org.springframework.beans.BeanUtils.copyProperties(own, request, "duration", "adoptSourceOnly");
            request.setDuration(Math.toIntExact(own.getDuration())); request.setAdoptSourceOnly(false);
            assertEquals(draftId, publish.saveOrUpdateDraft(request, 7L, "local-test-author"));
            assertEquals(json.readTree(own.getAdvancedConfigJson()), json.readTree(members.selectCmsMemberTemplateById(draftId).getAdvancedConfigJson()));
        }
        assertEquals(42, count("cms_member_template"), "续存不能重复创建");
        jdbc.update("UPDATE cms_template_library SET activity_categoryids='999' WHERE title='今日城市签'");
        try (Connection connection = source.getConnection()) { ItSchema.applyMigrations(connection, "sql/migration_20261012_legacy_game_v51_category_collation.sql"); }
        assertEquals("999", jdbc.queryForObject("SELECT activity_categoryids FROM cms_template_library WHERE title='今日城市签'", String.class), "修复脚本不能覆盖作者分类");
        jdbc.update("UPDATE cms_template_library SET activity_categoryids=NULL WHERE title='闭眼味觉师'");
        jdbc.update("INSERT INTO sys_category(category_name,type,status,del_flag) VALUES ('美食烹饪',1,1,0)");
        try (Connection connection = source.getConnection()) {
            assertThrows(java.sql.SQLException.class,
                    () -> ItSchema.applyMigrations(connection, "sql/migration_20261012_legacy_game_v51_category_collation.sql"), "同名品类不唯一必须拒绝，不能猜 ID");
        }
        assertNull(jdbc.queryForObject("SELECT activity_categoryids FROM cms_template_library WHERE title='闭眼味觉师'", String.class));
    }

    @Test
    void databaseDefaultsAreReviewedAndPersonalApprovalDoesNotCreateMarketEntry() {
        Long id = personal();
        assertEquals(0, members.selectCmsMemberTemplateById(id).getPhotoReview());
        assertEquals(1L, members.selectCmsMemberTemplateById(id).getStatus());
        assertEquals(0, count("cms_template_library"));
        Long task = publish.submitTemplateToMarket(id, 7L, "author");
        assertEquals(0L, projection(id).getPublishStatus());
        audit.review(task, 8L, approval(0));
        assertEquals(1L, projection(id).getPublishStatus());
        assertEquals(1, audit.review(task, 8L, approval(0)), "重试复用不可变审核事件");
        assertEquals(2, count("business_audit_event"));
    }

    @Test
    void immutableEventFailureRollsBackTaskTimelineAndMarketVisibility() {
        Long id = personal();
        Long task = publish.submitTemplateToMarket(id, 7L, "author");
        BusinessAuditEventMapper failing = mock(BusinessAuditEventMapper.class);
        when(failing.insertBusinessAuditEvent(any())).thenThrow(new IllegalStateException("event unavailable"));
        field(auditTarget, "businessAuditEventMapper", failing);
        int records = count("audit_record");
        assertThrows(IllegalStateException.class, () -> audit.review(task, 8L, approval(0)));
        assertEquals(0, tasks.selectAuditTaskById(task).getStatus());
        assertEquals(0, tasks.selectAuditTaskById(task).getVersion());
        assertEquals(0L, projection(id).getPublishStatus());
        assertEquals(records, count("audit_record"));
    }

    @Test
    void authorWithdrawalWinsAgainstWaitingApprovalAndResubmissionNeedsFreshReview() throws Exception {
        Long id = personal();
        Long oldTask = publish.submitTemplateToMarket(id, 7L, "author");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        Future<?>[] pending = new Future<?>[1];
        try {
            new TransactionTemplate(transactions).execute(status -> {
                members.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(id));
                pending[0] = worker.submit(() -> { started.countDown(); return audit.review(oldTask, 8L, approval(0)); });
                try { assertTrue(started.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                assertThrows(TimeoutException.class, () -> pending[0].get(150, TimeUnit.MILLISECONDS), "审批必须等待作者持有的真源锁");
                assertTrue(publish.updateTemplateLibraryStatus(id, 0, 7L));
                return null;
            });
            assertThrows(ExecutionException.class, () -> pending[0].get(10, TimeUnit.SECONDS));
            assertEquals(4, tasks.selectAuditTaskById(oldTask).getStatus());
            assertEquals(0L, projection(id).getPublishStatus());
            Long fresh = publish.submitTemplateToMarket(id, 7L, "author");
            assertNotEquals(oldTask, fresh);
            audit.review(fresh, 8L, approval(0));
            assertEquals(1L, projection(id).getPublishStatus());
            CmsMemberTemplate edit = new CmsMemberTemplate(); edit.setId(id); edit.setQuestionAnswer("changed");
            publish.updateAdminTemplate(edit, "author");
            assertEquals(0L, projection(id).getPublishStatus());
            assertEquals(0L, members.selectCmsMemberTemplateById(id).getStatus());
        } finally { worker.shutdownNow(); }
    }

    @Test
    void personalListKeepsOneRowAndSeparatesOwnerMarketState() {
        Long id = personal();
        jdbc.update("UPDATE cms_member_template SET original_template_id=0 WHERE id=?", id);
        Long task = publish.submitTemplateToMarket(id, 7L, "author");
        CmsTemplateLibrary first = projection(id);
        CmsTemplateLibrary duplicate = new CmsTemplateLibrary();
        duplicate.setMemberTemplateId(id); duplicate.setMemberId(7L); duplicate.setDelFlag(0);
        duplicate.setStatus(2L); duplicate.setPublishStatus(0L);
        libraries.insertCmsTemplateLibrary(duplicate);
        MemberTemplateVO query = new MemberTemplateVO(); query.setMemberId(7L);
        java.util.List<MemberTemplateVO> rows = members.selectMemberTemplateList(query);
        assertEquals(1, rows.size(), "重复历史投影不能放大我的玩法列表分页");
        assertEquals(1, rows.get(0).getCheckStatus());
        assertEquals(first.getId(), rows.get(0).getMarketTemplateId());
        assertEquals(0L, rows.get(0).getMarketPublishStatus());
        query.setMemberId(99L);
        assertTrue(members.selectMemberTemplateList(query).isEmpty());
        assertEquals(0, tasks.selectAuditTaskById(task).getStatus());
    }

    private Long personal() {
        CmsMemberTemplate row = new CmsMemberTemplate(); row.setMemberId(7L); row.setTitle("审核测试");
        row.setValidationMethod(0); row.setQuestionAnswer("original");
        assertEquals(1, publish.createAdminTemplate(row, "author"));
        AuditTask query = new AuditTask(); query.setBizType(AuditTask.BIZ_TEMPLATE); query.setBizId(row.getId());
        audit.review(tasks.selectAuditTaskList(query).get(0).getId(), 8L, approval(0));
        return row.getId();
    }
    private CmsTemplateLibrary projection(Long id) {
        return libraries.selectByMemberTemplateForUpdate(id).get(0);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static AuditReviewDTO approval(int version) {
        AuditReviewDTO value = new AuditReviewDTO(); value.setStatus(AuditTask.STATUS_APPROVED);
        value.setExpectedVersion(version); return value;
    }
    private static void field(Object target, String name, Object value) { ReflectionTestUtils.setField(target, name, value); }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Object target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}

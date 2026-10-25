package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.support.GameplayTemplateRules;
import com.chengyinhub.system.service.ISysCategoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Same fixture compiles on ad7 (legacy owner) and the extracted candidate: no compile-time new-owner imports.
 * All seven content/role mappers and the member-template/role services are real. Canonical invalidation and
 * creative-square collaboration at the outer transfer boundary are controlled mocks; no financial/HTTP/Quartz proof.
 */
final class TopicContentCopyMySqlFixture implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long SOURCE = 100L, OWNER = 1000L, TARGET = 2000L;
    static final List<String> TABLES = List.of("cms_topic", "cms_topic_chapter", "cms_topic_node",
            "cms_member_template", "npc_profile", "cms_collaborators", "product_role", "merchant_chapter_offer");
    static final Set<String> WRITABLE = Set.of("cms_topic", "cms_topic_chapter", "cms_topic_node",
            "cms_member_template", "npc_profile", "cms_collaborators", "product_role");
    final String ownerMode = System.getProperty("chengyin.wp10.copy.owner",
            System.getenv().getOrDefault("CHENGYIN_WP10_COPY_OWNER", "extracted"));
    final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment(
            "wp10copy_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    final Writes writes = new Writes();
    JdbcTemplate admin, db;
    DriverManagerDataSource source;
    DataSourceTransactionManager manager;
    TransactionTemplate tx;
    ICmsTopicService service;
    CmsTopicServiceImpl facade;
    CmsTopicMapper topics;
    IProductRoleService roles;
    ICmsMemberTemplateService templates;
    IUmsCreativeSquareService square;
    Map<String, List<Map<String, Object>>> before;
    boolean created;
    boolean couponFixture;

    void open() throws Exception {
        assertTrue(Set.of("legacy", "extracted").contains(ownerMode), "explicit owner mode must be legacy/extracted");
        for (String key : List.of("CHENGYIN_TEST_MYSQL_PORT", "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"))
            assertNotNull(System.getenv(key), "controlled scratch runner must explicitly supply " + key);
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4");
        created = true;
        source = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        db = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("it-schema/cms-topic-it-schema.sql"),
                new ClassPathResource("it-schema/wp10-content-copy-scratch.sql")).execute(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setPlugins(writes);
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsTopicMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicChapterMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicNodeMapper.xml"),
                new ClassPathResource("mapper/business/CmsMemberTemplateMapper.xml"),
                new ClassPathResource("mapper/business/NpcProfileMapper.xml"),
                new ClassPathResource("mapper/business/CmsCollaboratorsMapper.xml"),
                new ClassPathResource("mapper/business/ProductRoleMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        topics = sql.getMapper(CmsTopicMapper.class);
        manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
        ProductRoleServiceImpl roleTarget = new ProductRoleServiceImpl();
        ReflectionTestUtils.setField(roleTarget, "productRoleMapper", sql.getMapper(ProductRoleMapper.class));
        roles = (IProductRoleService) proxy(roleTarget, IProductRoleService.class);
        CmsMemberTemplateServiceImpl templateTarget = new CmsMemberTemplateServiceImpl();
        ReflectionTestUtils.setField(templateTarget, "cmsMemberTemplateMapper", sql.getMapper(CmsMemberTemplateMapper.class));
        templates = (ICmsMemberTemplateService) proxy(templateTarget, ICmsMemberTemplateService.class);
        facade = new CmsTopicServiceImpl();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("cmsTopicMapper", topics);
        fields.put("cmsTopicChapterMapper", sql.getMapper(CmsTopicChapterMapper.class));
        fields.put("cmsTopicNodeMapper", sql.getMapper(CmsTopicNodeMapper.class));
        fields.put("cmsMemberTemplateMapper", sql.getMapper(CmsMemberTemplateMapper.class));
        fields.put("npcProfileMapper", sql.getMapper(NpcProfileMapper.class));
        fields.put("cmsCollaboratorsMapper", sql.getMapper(CmsCollaboratorsMapper.class));
        fields.put("productRoleService", roles);
        fields.put("cmsMemberTemplateService", templates);
        fields.put("sysCategoryService", mock(ISysCategoryService.class)); // recruitment disabled in these content fixtures
        fields.put("platformMemberId", 100000L);
        fields.forEach((name, value) -> {
            if (ownerMode.equals("legacy")) ReflectionTestUtils.setField(facade, name, value);
            else {
                try {
                    Class.forName("com.chengyinhub.business.service.support.TopicPublicationTestWiring")
                            .getMethod("set", Object.class, String.class, Object.class).invoke(null, facade, name, value);
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException("copy publication collaborator assembly failed", failure); }
            }
        });
        square = mock(IUmsCreativeSquareService.class);
        ReflectionTestUtils.setField(facade, "umsCreativeSquareService", square);
        ReflectionTestUtils.setField(facade, "canonicalMutationService", mock(ITopicSaleCanonicalMutationService.class));
        String copyClass = "com.chengyinhub.business.service.impl.TopicContentCopyServiceImpl";
        if (ownerMode.equals("legacy")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(copyClass),
                    "legacy comparison must actually run the old source, not label the new source legacy");
        } else {
            Object copy = Class.forName(copyClass).getConstructor().newInstance();
            fields.forEach((name, value) -> ReflectionTestUtils.setField(copy, name, value));
            Class<?> guardClass = Class.forName("com.chengyinhub.business.service.support.TopicPlatformContentSlotGuard");
            Object guard = guardClass.getConstructor(IProductRoleService.class).newInstance(roles);
            ReflectionTestUtils.setField(copy, "platformContentSlotGuard", guard);
            ReflectionTestUtils.setField(facade, "platformContentSlotGuard", guard);
            Object copyProxy = proxy(copy, Class.forName("com.chengyinhub.business.service.ITopicContentCopyService"));
            assertTrue(AopUtils.isAopProxy(copyProxy));
            ReflectionTestUtils.setField(facade, "topicContentCopyService", copyProxy);
        }
        service = (ICmsTopicService) proxy(facade, ICmsTopicService.class);
        assertTrue(AopUtils.isAopProxy(service));
        seed();
        before = snapshot();
    }

    private Object proxy(Object target, Class<?> type) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(type);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return proxy.getProxy();
    }

    private void seed() throws Exception {
        String graph = "{\"schemaVersion\":1,\"startNodeId\":11,\"terminalNodeIds\":[13],\"edges\":["
                + "{\"id\":\"a\",\"fromNodeId\":11,\"toNodeId\":12,\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}},"
                + "{\"id\":\"b\",\"fromNodeId\":12,\"toNodeId\":13,\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}],"
                + "\"fallbacks\":[{\"fromNodeId\":11,\"toNodeId\":12},{\"fromNodeId\":12,\"toNodeId\":13}]}";
        String story = "{\"schemaVersion\":1,\"call\":{\"npcId\":501,\"opener\":\"call\"},"
                + "\"stages\":[{\"chapterId\":10},{\"chapterId\":20}],\"ending\":{\"epilogues\":["
                + "{\"npcId\":501,\"ally\":\"a\",\"neutral\":\"n\",\"guarded\":\"g\"},"
                + "{\"npcId\":599,\"ally\":\"a\",\"neutral\":\"n\",\"guarded\":\"g\"}]}}";
        ObjectNode advanced = JSON.createObjectNode().put("schemaVersion", 1);
        advanced.putObject("enter").put("npcId", 501).put("opener", "enter");
        advanced.putObject("outcome").put("npcId", 599).put("line", "done");
        ObjectNode rules = advanced.putObject("interactionRules");
        rules.putArray("resourceSlots").addObject().put("key", "material").put("kind", "ITEM").put("label", "material").put("required", true);
        rules.putArray("resourceCosts").addObject().put("itemId", "material").put("quantity", 1);
        ObjectNode runtime = JSON.createObjectNode().put("schemaVersion", 1).put("capacity", 2);
        runtime.putArray("items").addObject().put("id", "supply").put("kind", "ITEM");
        ObjectNode bindings = runtime.putObject("templateBindings");
        for (String node : List.of("11", "12")) {
            ObjectNode binding = bindings.putObject(node).put("templateId", 701)
                    .put("slotDigest", GameplayTemplateRules.slotDigest(rules.path("resourceSlots")));
            binding.putObject("values"); binding.putObject("items").put("material", "supply");
        }
        db.update("insert into cms_topic(id,member_id,name,topic_type,product_type,circle_theme_code,route_mode,route_graph_json,config_version,"
                + "published_route_graph_json,published_config_version,journey_story_json,theme_runtime_config_json,published_theme_runtime_config_json,"
                + "complete_rule_json,status,user_status,merchant_status,del_flag,is_sync,is_template,is_draft,lifecycle,"
                + "self_play_price,self_play_quota,fee_platform_rate,create_time,update_time) "
                + "values(100,1000,'source',1,1,'CITY','BRANCH_GRAPH',?,9,?,8,?,?,?, ?,1,1,1,0,1,1,0,0,88.50,9,0.1300,'2026-01-02 03:04:05','2026-01-02 03:04:05')",
                graph, graph, story, runtime.toString(), runtime.toString(),
                "{\"nodeCompletion\":{\"mode\":\"AT_LEAST\",\"requiredCount\":2},\"future\":{\"kept\":true}}");
        String blocks = "[{\"type\":\"text\",\"content\":\"before\"},{\"type\":\"node\",\"nodeId\":11},"
                + "{\"type\":\"text\",\"content\":\"after\",\"beat\":\"enter\",\"field\":\"opener\",\"nodeId\":11,\"npcId\":501}]";
        db.update("insert into cms_topic_chapter(id,topic_id,name,sort_id,recruit_enabled,max_merchant,required,terms_mode,blocks,circle_enabled,circle_candidate_code) values(10,100,'one',1,0,0,1,'PERK',?,1,'CITY')", blocks);
        db.update("insert into cms_topic_chapter(id,topic_id,name,sort_id,recruit_enabled,max_merchant,required,terms_mode,max_per_head_fee,blocks,circle_enabled) values(20,100,'two',1,0,0,1,'REVSHARE',5.00,?,1)", "[{\"type\":\"node\",\"nodeId\":13}]");
        for (int i = 11; i <= 13; i++) {
            db.update("insert into cms_topic_node(id,topic_id,chapter_id,name,sort_id,status,template_id,xp_value,checkin_code,unlock_after_node_id) values(?,100,?,?,1,1,?,7,?,?)",
                    i, i == 13 ? 20 : 10, "node-" + i, i == 13 ? 702 : 701, "source-code-" + i, i == 11 ? null : i - 1);
        }
        db.update("insert into cms_member_template(id,member_id,original_template_id,title,status,draft_status,publish_status,is_sync,del_flag,advanced_config_json) values(701,1000,301,'shared-npc',1,1,1,1,'0',?)", advanced.toString());
        db.update("insert into cms_member_template(id,member_id,original_template_id,title,status,draft_status,publish_status,is_sync,del_flag) values(702,1000,null,'plain',1,1,1,1,'0')");
        db.update("insert into npc_profile(id,scope_type,scope_id,name,persona,greeting,sort,audit_status,enabled,active_slot,voice_id,voice_status,voice_sample) values(501,4,11,'ready','p','g',0,1,1,1,'fixture-ready-voice',2,'/media/voice.wav')");
        db.update("insert into npc_profile(id,scope_type,scope_id,name,audit_status,enabled,active_slot,voice_status) values(502,4,12,'pending',1,1,1,1)");
        db.update("insert into npc_profile(id,scope_type,scope_id,name,enabled) values(599,2,900,'merchant-avatar',1)");
        db.update("insert into cms_collaborators(id,data_type,data_id,member_id) values(91,1,100,3000),(92,1,100,3001)");
        db.update("insert into product_role(id,topic_id,slot,entity_type,entity_id,status,created_at) values(81,100,'CONTENT','MEMBER',1000,1,'2026-01-02 03:04:05')");
        // Empty dependency table still participates in the real public-node filter.
    }

    long copy(int type, Long member) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("copyType", type);
        if (member != null) params.put("memberId", member);
        return service.copyCmsTopic(SOURCE, params);
    }

    Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : TABLES) result.put(table, db.queryForList("select * from " + table + " order by id"));
        if (couponFixture) result.put("sms_coupon", db.queryForList("select * from sms_coupon order by id"));
        return result;
    }

    /** The original coupon table/mapper/owner guard, installed only by the new copy-resource cases. */
    void installCouponOwnership() throws Exception {
        assertFalse(couponFixture);
        Path root = Paths.get("").toAbsolutePath();
        if (!Files.exists(root.resolve("sql/base_schema_20260818.sql"))) root = root.getParent();
        String baseline = Files.readString(root.resolve("sql/base_schema_20260818.sql"));
        int start = baseline.indexOf("CREATE TABLE `sms_coupon` (");
        int closing = baseline.indexOf("\n) ENGINE", start), end = baseline.indexOf(";\n", closing);
        assertTrue(start >= 0 && closing > start && end > closing, "canonical coupon table required");
        db.execute(baseline.substring(start, end + 1)); couponFixture = true;
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/SmsCouponMapper.xml"));
        SmsCouponServiceImpl couponReads = new SmsCouponServiceImpl();
        ReflectionTestUtils.setField(couponReads, "smsCouponMapper", new SqlSessionTemplate(factory.getObject()).getMapper(SmsCouponMapper.class));
        CouponRewardAuthorizationServiceImpl ownerGuard = new CouponRewardAuthorizationServiceImpl();
        ReflectionTestUtils.setField(ownerGuard, "smsCouponService", couponReads);
        CmsMemberTemplateServiceImpl templateOwner =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(templates);
        ReflectionTestUtils.setField(templateOwner, "couponRewardAuthorizationService", ownerGuard);
    }

    void assertUnchanged() { assertEquals(before, snapshot(), "all rows must roll back or remain unchanged"); }

    void assertOriginalUnchanged() {
        Map<String, List<Map<String, Object>>> now = snapshot();
        for (String table : TABLES) for (Map<String, Object> row : before.get(table)) {
            Object id = row.get("id");
            assertEquals(row, now.get(table).stream().filter(r -> Objects.equals(r.get("id"), id)).findFirst().orElseThrow(), table);
        }
    }

    List<Map<String, Object>> nodes(long topic) { return db.queryForList("select * from cms_topic_node where topic_id=? order by id", topic); }
    List<Map<String, Object>> chapters(long topic) { return db.queryForList("select * from cms_topic_chapter where topic_id=? order by id", topic); }
    JsonNode json(Object raw) throws Exception { return raw == null ? JSON.nullNode() : JSON.readTree(raw.toString()); }
    long number(Object value) { return ((Number) value).longValue(); }

    void receipt(String name, Object outcome) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("owner", ownerMode);
        payload.put("outcome", outcome);
        Map<String, List<Map<String, Object>>> normalized = snapshot();
        for (String table : TABLES) for (Map<String, Object> row : normalized.get(table)) {
            Map<String, Object> original = before.get(table).stream().filter(r -> Objects.equals(r.get("id"), row.get("id"))).findFirst().orElse(null);
            for (String key : new ArrayList<>(row.keySet())) {
                Object value = row.get(key);
                if (Set.of("create_time", "update_time", "created_at").contains(key) && value != null) {
                    row.put(key, original != null && Objects.equals(original.get(key), value) ? value.toString() : "<operation-time>");
                } else if (key.equals("checkin_code") && original == null && value != null) {
                    row.put(key, "<new-checkin-code>");
                } else if (value instanceof String text && (text.startsWith("{") || text.startsWith("["))) {
                    row.put(key, JSON.readTree(text));
                }
            }
        }
        payload.put("tables", normalized);
        payload.put("mapperStatements", new ArrayList<>(writes.statements));
        Path folder = Paths.get(System.getProperty("chengyin.wp10.copy.evidenceDir", "target/surefire-reports/wp10-copy-equivalence"));
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(name + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
        System.out.println("WP10_COPY_RECEIPT " + name + " owner=" + ownerMode + " writes=" + writes.statements.size());
    }

    @Override public void close() {
        if (created) {
            admin.execute("drop database " + mysql.databaseName());
            assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?", Integer.class, mysql.databaseName()));
            System.out.println("WP10_COPY_CLEAN " + mysql.databaseName());
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    public static final class Writes implements Interceptor {
        final List<String> statements = new ArrayList<>();
        Predicate<Update> fail = update -> false;
        Predicate<Update> zero = update -> false;
        boolean failBefore;
        int ordinal;
        @Override public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement mapped = (MappedStatement) invocation.getArgs()[0];
            Object parameter = invocation.getArgs()[1];
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "every production content write must join a Spring transaction");
            String sql = mapped.getBoundSql(parameter).getSql().replaceAll("\\s+", " ").trim();
            Matcher table = Pattern.compile("(?i)^(?:insert\\s+into|update|delete\\s+from)\\s+`?([a-z_]+)").matcher(sql);
            assertTrue(table.find(), "unrecognized production write: " + sql);
            assertTrue(WRITABLE.contains(table.group(1).toLowerCase(Locale.ROOT)), "unexpected domain write: " + sql);
            statements.add(mapped.getId());
            Update update = new Update(mapped.getId(), parameter, ++ordinal);
            if (failBefore && fail.test(update)) throw new IllegalStateException("fixture write failure: " + mapped.getId());
            if (zero.test(update)) return 0;
            Object value = invocation.proceed();
            if (!failBefore && fail.test(update)) throw new IllegalStateException("fixture write failure: " + mapped.getId());
            return value;
        }
    }
    record Update(String statement, Object parameter, int ordinal) { }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.utils.DistanceCalculatorUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.mockito.Answers;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Same fixture on the original facade and extracted graph. No compile-time new-owner references. */
final class TopicContentGraphMySqlFixture implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final long TOPIC = 100L, OWNER = 42L;
    static final List<String> TABLES = List.of("cms_topic", "cms_topic_chapter", "cms_topic_node",
            "cms_member_template", "npc_profile", "cms_collaborators", "product_role", "merchant_chapter_offer", "theme_state_key");
    final String ownerMode = System.getProperty("chengyin.wp10.graph.owner",
            System.getenv().getOrDefault("CHENGYIN_WP10_GRAPH_OWNER", "extracted"));
    final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment(
            "wp10graph_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    final List<String> trace = new ArrayList<>();
    final List<String> statements = new ArrayList<>();
    final List<Boolean> txFacts = new ArrayList<>();
    final Map<String, Object> outcomes = new LinkedHashMap<>();
    final Capture capture = new Capture(this);
    JdbcTemplate admin, db;
    DataSourceTransactionManager manager;
    SqlSessionTemplate sql;
    TransactionTemplate tx;
    CmsTopicMapper topics;
    CmsTopicChapterMapper chapters;
    CmsTopicNodeMapper nodes;
    CmsMemberTemplateMapper templateMapper;
    CmsCollaboratorsMapper collaborators;
    IProductRoleService roles;
    CmsTopicServiceImpl facade;
    ICmsTopicService service;
    Object graph, guard;
    ITopicRouteRuntimeService runtime;
    OmsTicketMapper tickets;
    IXpBudgetService xp;
    TopicBundleReviewService bundle;
    ISysCategoryService categories;
    Map<String, List<Map<String, Object>>> before;
    boolean created;
    Predicate<Call> failBefore = call -> false;
    Predicate<Call> zeroAfter = call -> false;
    Predicate<Call> clearIdentityAfter = call -> false;
    int inserts;
    String boundaryFailure;

    void open() throws Exception {
        assertTrue(Set.of("legacy", "extracted").contains(ownerMode));
        for (String key : List.of("CHENGYIN_TEST_MYSQL_PORT", "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"))
            assertNotNull(System.getenv(key), "controlled runner must explicitly supply " + key);
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4");
        created = true;
        DriverManagerDataSource data = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        db = new JdbcTemplate(data);
        new ResourceDatabasePopulator(new ClassPathResource("it-schema/cms-topic-it-schema.sql"),
                new ClassPathResource("it-schema/wp10-content-graph-scratch.sql")).execute(data);
        for (String file : List.of("migration_theme_state_key_20260916.sql", "migration_20261022_theme_state_key_attribute_def.sql")) {
            Path path = Paths.get("sql", file);
            if (!Files.exists(path)) path = Paths.get("chengyinhub-admin", "sql", file);
            new ResourceDatabasePopulator(new org.springframework.core.io.FileSystemResource(path.toFile())).execute(data);
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(data);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setPlugins(capture);
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsTopicMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicChapterMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicNodeMapper.xml"),
                new ClassPathResource("mapper/business/CmsMemberTemplateMapper.xml"),
                new ClassPathResource("mapper/business/CmsCollaboratorsMapper.xml"),
                new ClassPathResource("mapper/business/ProductRoleMapper.xml"),
                new ClassPathResource("mapper/business/ThemeStateKeyMapper.xml"));
        sql = new SqlSessionTemplate(factory.getObject());
        topics = sql.getMapper(CmsTopicMapper.class);
        chapters = sql.getMapper(CmsTopicChapterMapper.class);
        nodes = sql.getMapper(CmsTopicNodeMapper.class);
        templateMapper = sql.getMapper(CmsMemberTemplateMapper.class);
        collaborators = sql.getMapper(CmsCollaboratorsMapper.class);
        manager = new DataSourceTransactionManager(data);
        tx = new TransactionTemplate(manager);
        ProductRoleServiceImpl role = new ProductRoleServiceImpl();
        ReflectionTestUtils.setField(role, "productRoleMapper", sql.getMapper(ProductRoleMapper.class));
        roles = (IProductRoleService) proxy(role, IProductRoleService.class);
        CmsMemberTemplateServiceImpl templates = new CmsMemberTemplateServiceImpl();
        ReflectionTestUtils.setField(templates, "cmsMemberTemplateMapper", templateMapper);
        ICmsMemberTemplateService templateService = (ICmsMemberTemplateService) proxy(templates, ICmsMemberTemplateService.class);
        facade = new CmsTopicServiceImpl();
        for (Field field : CmsTopicServiceImpl.class.getDeclaredFields()) {
            if (!field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) continue;
            field.setAccessible(true);
            if (field.get(facade) == null) field.set(facade, boundary(field.getType(), field.getName()));
        }
        if (!ownerMode.equals("legacy")) publicationFixture("wireRelocatedCollaborators",
                new Class<?>[]{Object.class, java.util.function.Function.class}, facade,
                (java.util.function.Function<Field, Object>) publicationField -> boundary(publicationField.getType(), publicationField.getName()));
        set("cmsTopicMapper", topics); set("cmsTopicChapterMapper", chapters); set("cmsTopicNodeMapper", nodes);
        set("cmsMemberTemplateMapper", templateMapper); set("cmsCollaboratorsMapper", collaborators);
        ThemeStateKeyService keyService = new ThemeStateKeyService(sql.getMapper(ThemeStateKeyMapper.class));
        ProxyFactory keyProxy = new ProxyFactory(keyService); keyProxy.setProxyTargetClass(true);
        keyProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        set("themeStateKeyService", keyProxy.getProxy());
        TopicStateKeyRefService references = new TopicStateKeyRefService(nodes, templateMapper, chapters);
        ReflectionTestUtils.setField(references, "cmsTopicMapper", topics);
        set("topicStateKeyRefService", references);
        set("productRoleService", roles); set("cmsMemberTemplateService", templateService);
        set("distanceCalculatorUtils", new DistanceCalculatorUtils());
        tickets = boundary(OmsTicketMapper.class, "ticket"); set("omsTicketMapper", tickets);
        xp = boundary(IXpBudgetService.class, "xp"); set("xpBudgetService", xp);
        bundle = boundary(TopicBundleReviewService.class, "bundle"); set("topicBundleReviewService", bundle);
        categories = boundary(ISysCategoryService.class, "category"); set("sysCategoryService", categories);
        TopicSupplyReviewService supply = mock(TopicSupplyReviewService.class, invocation -> {
            trace.add("D:supply." + invocation.getMethod().getName());
            if (invocation.getMethod().getName().equals("lockTopic")) return topics.selectCmsTopicByIdForUpdate(invocation.getArgument(0));
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        set("supplyReviewService", supply);
        runtime = new TopicRouteRuntimeServiceImpl(mock(TopicRouteSessionMapper.class), topics, nodes, templateMapper,
                mock(PlayerNodeProgressMapper.class), mock(CmsActivityMapper.class), mock(CmsRegistrationMapper.class),
                mock(PlayerActivityRoleMapper.class), () -> 1L, Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC),
                RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(runtime, "cmsTopicChapterMapper", chapters);
        ReflectionTestUtils.setField(runtime, "themeStateKeyMapper", sql.getMapper(ThemeStateKeyMapper.class));
        set("topicRouteRuntimeService", runtime);
        TopicPublicationTestFixtures.wire(facade);
        String name = "com.chengyinhub.business.service.impl.TopicContentGraphAuthoringServiceImpl";
        if (ownerMode.equals("legacy")) {
            for (String cls : List.of(name, "com.chengyinhub.business.service.support.TopicContentWriteGuard",
                    "com.chengyinhub.business.service.support.TopicRouteOutcomeReader", "com.chengyinhub.business.service.support.TopicAuthoredGraphIds",
                    "com.chengyinhub.business.service.impl.TopicPublicationService",
                    "com.chengyinhub.business.service.support.TopicPublicationContentGuard",
                    "com.chengyinhub.business.service.support.TopicSelfHostActivityService"))
                assertThrows(ClassNotFoundException.class, () -> Class.forName(cls), "legacy must run the original production source");
            graph = facade; guard = facade;
        } else {
            graph = Class.forName(name).getConstructor().newInstance();
            Class<?> guardClass = Class.forName("com.chengyinhub.business.service.support.TopicContentWriteGuard");
            guard = guardClass.getConstructor(CmsTopicMapper.class, CmsTopicChapterMapper.class, CmsMemberTemplateMapper.class,
                    IProductRoleService.class, ISysCategoryService.class).newInstance(topics, chapters, templateMapper, roles, categories);
            Object reader = Class.forName("com.chengyinhub.business.service.support.TopicRouteOutcomeReader")
                    .getConstructor(CmsTopicNodeMapper.class, CmsMemberTemplateMapper.class).newInstance(nodes, templateMapper);
            for (String field : List.of("cmsTopicMapper", "cmsTopicChapterMapper", "cmsTopicNodeMapper",
                    "cmsMemberTemplateMapper", "cmsMemberTemplateService", "topicRouteRuntimeService"))
                ReflectionTestUtils.setField(graph, field, get(field));
            ReflectionTestUtils.setField(graph, "contentWriteGuard", guard);
            ReflectionTestUtils.setField(graph, "routeOutcomeReader", reader);
            Object graphProxy = proxy(graph, Class.forName("com.chengyinhub.business.service.ITopicContentGraphAuthoringService"));
            assertTrue(AopUtils.isAopProxy(graphProxy));
            set("contentGraphAuthoringService", graphProxy); set("contentWriteGuard", guard); set("routeOutcomeReader", reader);
        }
        if (!ownerMode.equals("legacy")) publicationFixture("wire", new Class<?>[]{Object.class}, facade);
        service = (ICmsTopicService) proxy(facade, ICmsTopicService.class);
        assertTrue(AopUtils.isAopProxy(service));
        seed(); mark();
    }

    @SuppressWarnings("unchecked")
    private <T> T boundary(Class<T> type, String label) {
        return mock(type, invocation -> {
            String method = invocation.getMethod().getName();
            if (invocation.getMethod().getDeclaringClass() != Object.class) {
                trace.add("D:" + label + "." + method);
                if ((label.equals("xp") && method.equals("autoAllocate") && "xp".equals(boundaryFailure))
                        || (label.equals("ticket") && method.equals("selectOmsTicketList") && "ticket".equals(boundaryFailure)
                            && statements.stream().anyMatch(id -> id.endsWith(".insertCmsTopicNode")))
                        || (label.equals("bundle") && method.equals("freezeForSubmission") && "review".equals(boundaryFailure))) {
                    trace.add("THROW:"+boundaryFailure);
                    throw new IllegalStateException("fixture-" + boundaryFailure);
                }
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private Object proxy(Object target, Class<?> type) {
        ProxyFactory factory = new ProxyFactory(target); factory.setInterfaces(type);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return factory.getProxy();
    }

    void set(String field, Object value) {
        if (ownerMode.equals("legacy")) ReflectionTestUtils.setField(facade, field, value);
        else publicationFixture("set", new Class<?>[]{Object.class, String.class, Object.class}, facade, field, value);
    }
    Object get(String field) {
        return ownerMode.equals("legacy") ? ReflectionTestUtils.getField(facade, field)
                : publicationFixture("get", new Class<?>[]{Object.class, String.class}, facade, field);
    }
    Object publicationFixture(String method, Class<?>[] signature, Object... args) {
        try {
            return Class.forName("com.chengyinhub.business.service.support.TopicPublicationTestWiring")
                    .getMethod(method, signature).invoke(null, args);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("publication fixture assembly failed", failure); }
    }

    private void seed() {
        db.update("insert into cms_topic(id,member_id,name,topic_type,product_type,status,user_status,merchant_status,del_flag,is_draft,lifecycle,"
                + "route_mode,route_graph_json,config_version,journey_story_json,publish_mode,create_time,update_time) "
                + "values(100,42,'original',2,1,0,0,0,0,1,0,'BRANCH_GRAPH',?,7,?,'pro','2026-01-02 03:04:05','2026-01-02 03:04:05')",
                graphJson(), "{\"schemaVersion\":1,\"stages\":[{\"chapterId\":10},{\"chapterId\":20}],\"future\":{\"nodeId\":11}}");
        db.update("insert into cms_topic(id,member_id,name,topic_type,product_type,status,user_status,del_flag,is_draft) values(101,77,'unrelated',2,2,0,0,0,1)");
        db.update("insert into cms_topic_chapter(id,topic_id,name,sort_id,recruit_enabled,max_merchant,required,terms_mode) values(10,100,'first',1,0,0,1,'PERK'),(20,100,'second',1,0,0,1,'PERK'),(21,101,'unrelated',1,0,0,1,'PERK')");
        for (int id=11; id<=13; id++) db.update("insert into cms_topic_node(id,topic_id,chapter_id,name,sort_id,status,template_id,checkin_code,unlock_after_node_id) values(?,100,?,?,1,?,701,?,?)",
                id,id==13?20:10,"old-"+id,id==12?0:1,"original-code-"+id,id==13?11:null);
        db.update("insert into cms_topic_node(id,topic_id,chapter_id,name,sort_id,status,template_id,checkin_code) values(21,101,21,'unrelated',1,1,702,'unrelated-code')");
        db.update("insert into cms_member_template(id,member_id,title,status,draft_status,publish_status,check_status,is_sync,del_flag) values(701,42,'own',1,1,1,1,0,'0'),(702,77,'unrelated',1,1,1,1,0,'0')");
        db.update("insert into npc_profile(id,scope_type,scope_id,name,audit_status,enabled,active_slot) values(501,4,11,'original-npc',1,1,1)");
        db.update("insert into cms_collaborators(id,data_type,data_id,member_id) values(91,1,100,81),(92,1,100,82),(93,1,101,83)");
        db.update("insert into product_role(id,topic_id,slot,entity_type,entity_id,status,created_at) values(81,100,'CONTENT','MEMBER',42,1,'2026-01-02 03:04:05'),(82,101,'CONTENT','MEMBER',77,1,'2026-01-02 03:04:05')");
    }

    String graphJson() {
        return "{\"schemaVersion\":1,\"startNodeId\":11,\"terminalNodeIds\":[13],\"edges\":["
                + "{\"id\":\"a\",\"fromNodeId\":11,\"toNodeId\":12,\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}],"
                + "\"fallbacks\":[{\"fromNodeId\":11,\"toNodeId\":12},{\"fromNodeId\":12,\"toNodeId\":13}]}";
    }

    NodeDTO node(Long id, String key) {
        NodeDTO node = new NodeDTO(); node.setId(id); node.setClientNodeKey(key); node.setName(key);
        node.setTemplateId(701L); node.setAddress("station"); node.setLongitude("121.4"); node.setLatitude("31.2"); node.setNodeTime(3);
        return node;
    }

    ChapterDTO chapter(long id, String name, boolean blocks, NodeDTO... nodes) {
        ChapterDTO chapter = new ChapterDTO(); chapter.setId(id); chapter.setName(name); chapter.setRequired(1);
        chapter.setTermsMode("PERK"); chapter.setRecruitEnabled(0); chapter.setMaxMerchant(0);
        chapter.setNodes(new ArrayList<>(Arrays.asList(nodes)));
        if (blocks) {
            chapter.setSchemaVersion(1);
            List<ChapterBlockDTO> flow = new ArrayList<>();
            ChapterBlockDTO text = new ChapterBlockDTO(); text.setType("text"); text.setContent("intro\nkept"); flow.add(text);
            for (int i=0;i<nodes.length;i++) { ChapterBlockDTO block = new ChapterBlockDTO(); block.setType("node"); block.setNodeIndex(i); flow.add(block); }
            chapter.setBlocks(flow);
        }
        return chapter;
    }

    <T extends TopicCreateDTO> T fill(T dto, boolean blocks, int product) {
        dto.setName("authored"); dto.setSaveDraft(1); dto.setProductType(product); dto.setPublishMode("pro");
        dto.setCollaboratorIds(new ArrayList<>(List.of(81L,82L))); dto.setTickets(new ArrayList<>());
        dto.setChapters(new ArrayList<>(List.of(chapter(10,"first",blocks,node(11L,"first"),node(12L,"second")),
                chapter(20,"second",blocks,node(13L,"third")))));
        return dto;
    }
    TopicCreateDTO create(boolean blocks,int product) { return fill(new TopicCreateDTO(),blocks,product); }
    TopicUpdateDTO update(boolean blocks,int product) { TopicUpdateDTO dto=fill(new TopicUpdateDTO(),blocks,product);dto.setId(TOPIC);return dto; }

    long create(TopicCreateDTO dto, boolean v2) { return v2?service.createTopicV2(dto,OWNER,"author"):service.createTopic(dto,OWNER,"author"); }
    long update(TopicUpdateDTO dto, boolean v2) { return v2?service.updateTopicByOwnerV2(dto,OWNER,"author"):service.updateTopicByOwner(dto,OWNER,"author"); }

    void mark() { before=snapshot();trace.clear();statements.clear();txFacts.clear();inserts=0; }

    <T> T success(String scenario, Supplier<T> action) {
        assertFalse(outcomes.containsKey(scenario));
        trace.add("CASE:"+scenario);
        T result=action.get(); trace.add("END:"+scenario);outcomes.put(scenario,Map.of("return",result)); return result;
    }

    Throwable failure(String scenario, Runnable action) {
        assertFalse(outcomes.containsKey(scenario));
        trace.add("CASE:"+scenario);
        Throwable error=assertThrows(RuntimeException.class, action::run);
        while (error.getCause()!=null) error=error.getCause();
        Map<String,Object> outcome=new LinkedHashMap<>();outcome.put("class",error.getClass().getName());outcome.put("message",error.getMessage());
        trace.add("END:"+scenario);
        outcomes.put(scenario,outcome);return error;
    }

    Object graphCall(String name,Object... args) { return tx.execute(status -> ReflectionTestUtils.invokeMethod(graph,name,args)); }
    Object guardCall(String name,Object... args) { return tx.execute(status -> ReflectionTestUtils.invokeMethod(guard,name,args)); }
    Object outcomes() {
        return tx.execute(status -> {
            // Controlled JDBC fixture updates bypass MyBatis write invalidation in the reader scenarios.
            sql.clearCache();
            return ReflectionTestUtils.invokeMethod(ownerMode.equals("legacy") ? facade : get("publicationContentGuard"),"declaredRouteOutcomes",TOPIC);
        });
    }

    Map<String,List<Map<String,Object>>> snapshot() {
        Map<String,List<Map<String,Object>>> rows=new LinkedHashMap<>();
        for(String table:TABLES)rows.put(table,db.queryForList("select * from "+table+" order by id"));
        return rows;
    }
    void unchanged() { assertEquals(before,snapshot(),"rejection/rollback must restore every real content row"); }
    void originalRowsUnchanged() {
        Map<String,List<Map<String,Object>>> now=snapshot();
        for(String table:TABLES)for(Map<String,Object> original:before.get(table))
            assertEquals(original,now.get(table).stream().filter(row -> Objects.equals(row.get("id"),original.get("id"))).findFirst().orElseThrow(),table);
    }
    void runtime(ITopicRouteRuntimeService value) {
        set("topicRouteRuntimeService",value);
        if(graph!=facade)ReflectionTestUtils.setField(graph,"topicRouteRuntimeService",value);
    }
    void protectedRowsUnchanged() {
        Map<String,List<Map<String,Object>>> now=snapshot();
        for(String table:TABLES)for(Map<String,Object> old:before.get(table)) {
            boolean protectedRow=table.equals("cms_member_template")||table.equals("npc_profile")||table.equals("merchant_chapter_offer")
                    || table.equals("cms_topic")&&number(old.get("id"))==101L
                    || old.get("topic_id")!=null&&number(old.get("topic_id"))==101L
                    || table.equals("cms_collaborators")&&number(old.get("data_id"))==101L;
            if(protectedRow)assertEquals(old,now.get(table).stream().filter(row -> Objects.equals(row.get("id"),old.get("id"))).findFirst().orElseThrow(),table);
        }
    }
    long number(Object value) { return ((Number)value).longValue(); }
    List<Map<String,Object>> topicNodes(long topic) {
        // sort_id is chapter-local in the legacy writer; preserve the submitted chapter/node hierarchy.
        return db.queryForList("select n.* from cms_topic_node n left join cms_topic_chapter c "
                + "on c.id=n.chapter_id and c.topic_id=n.topic_id where n.topic_id=? "
                + "order by c.sort_id,c.id,n.sort_id,n.id",topic);
    }
    List<Map<String,Object>> topicChapters(long topic) { return db.queryForList("select * from cms_topic_chapter where topic_id=? order by sort_id,id",topic); }
    JsonNode json(String raw) throws Exception { return JSON.readTree(raw); }
    void earlier(String first,String second) {
        int a=index(first),b=index(second);assertTrue(a>=0&&b>a,first+" before "+second+" in "+trace);
    }
    int index(String part) { for(int i=0;i<trace.size();i++)if(trace.get(i).contains(part))return i;return -1; }
    void noExternalActions() { assertTrue(trace.stream().noneMatch(s -> s.contains("wechatPayService.")||s.contains("notifyService.")),trace.toString()); }

    void receipt(String caseName) throws Exception {
        assertFalse(outcomes.isEmpty(),"each ordinary @Test must record its executed scenarios");
        Map<String,Object> result=new LinkedHashMap<>(); result.put("owner",ownerMode); result.put("caseName",caseName);
        result.put("outcomes",outcomes); result.put("mapperStatements",statements); result.put("orderedBoundaryTrace",trace);
        result.put("transactionFacts",txFacts);
        result.put("originalRows",JSON.valueToTree(before));
        protectedRowsUnchanged();
        result.put("protectedRowsVerified",true);
        Map<String,List<Map<String,Object>>> rows=snapshot();
        for(String table:TABLES)for(Map<String,Object> row:rows.get(table)) {
            Map<String,Object> original=before.get(table).stream().filter(r -> Objects.equals(r.get("id"),row.get("id"))).findFirst().orElse(null);
            for(String key:new ArrayList<>(row.keySet())) {
                Object value=row.get(key);
                if(Set.of("create_time","update_time","created_at").contains(key)&&value!=null)
                    row.put(key,original!=null&&Objects.equals(original.get(key),value)?value.toString():"<operation-time>");
                else if(key.equals("checkin_code")&&original==null&&value!=null) {
                    assertTrue(value.toString().matches("[0-9a-f]{32}")); row.put(key,"<new-checkin-code>");
                }
            }
        }
        result.put("tables",rows);
        List<String> newCodes = new ArrayList<>();
        for (Map<String,Object> row : rows.get("cms_topic_node")) {
            boolean existing = before.get("cms_topic_node").stream().anyMatch(old -> Objects.equals(old.get("id"),row.get("id")));
            if (!existing) newCodes.add(db.queryForObject("select checkin_code from cms_topic_node where id=?",String.class,row.get("id")));
        }
        assertEquals(newCodes.size(),new HashSet<>(newCodes).size(),"new checkin identities must be distinct");
        noExternalActions();
        Path folder=Paths.get(System.getProperty("chengyin.wp10.graph.evidenceDir","target/surefire-reports/wp10-authoring-equivalence"));
        Files.createDirectories(folder);Files.writeString(folder.resolve(caseName+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println("WP10_GRAPH_RECEIPT "+caseName+" owner="+ownerMode);
    }

    @Override public void close() {
        if(created) {
            admin.execute("drop database "+mysql.databaseName());
            assertEquals(0,admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?",Integer.class,mysql.databaseName()));
            System.out.println("WP10_GRAPH_CLEAN "+mysql.databaseName());
        }
    }

    record Call(String statement,Object parameter) { boolean is(String suffix) { return statement.endsWith("."+suffix); } }

    @Intercepts({@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}),
            @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class})})
    public static final class Capture implements Interceptor {
        private final TopicContentGraphMySqlFixture fixture;
        Capture(TopicContentGraphMySqlFixture fixture) { this.fixture=fixture; }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            String id=((MappedStatement)invocation.getArgs()[0]).getId();Object parameter=invocation.getArgs()[1];
            Call call=new Call(id,parameter);fixture.trace.add("M:"+id);
            fixture.txFacts.add(TransactionSynchronizationManager.isActualTransactionActive());
            if(invocation.getMethod().getName().equals("update"))fixture.statements.add(id);
            if(fixture.failBefore.test(call))throw new IllegalStateException("fixture-mapper:"+id.substring(id.lastIndexOf('.')+1));
            Object result=invocation.proceed();
            if(fixture.clearIdentityAfter.test(call)) {
                if(parameter instanceof CmsTopicChapter chapter)chapter.setId(null);
                if(parameter instanceof CmsTopicNode node)node.setId(null);
            }
            return fixture.zeroAfter.test(call)?0:result;
        }
    }
}

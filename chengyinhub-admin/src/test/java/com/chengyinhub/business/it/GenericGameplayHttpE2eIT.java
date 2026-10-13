package com.chengyinhub.business.it;

import com.chengyinhub.RuoYiApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.chengyinhub.business.service.support.GameplayTemplateRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.framework.web.service.AppLoginService;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.Permission;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(classes = RuoYiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = GenericGameplayHttpE2eIT.LocalInitializer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GenericGameplayHttpE2eIT {
    @Autowired private TestRestTemplate http;
    @Autowired private ConfigurableApplicationContext context;
    @Autowired private UmsMemberMapper members;
    @Autowired private AppLoginService login;
    @Autowired private JdbcTemplate database;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private final com.fasterxml.jackson.databind.node.ArrayNode transcript = json.createArrayNode();

    static void assertFixtureMapperColumns(Statement statement) throws SQLException {
        try(ResultSet columns=statement.executeQuery("SELECT withdraw_reason, withdraw_required, replacement_of_offer_id FROM merchant_chapter_offer WHERE 1=0")) {
            assertEquals(3,columns.getMetaData().getColumnCount(),"商家供给 Mapper 列必须存在于本地夹具");
        }
        try(ResultSet columns=statement.executeQuery("SELECT declared_by FROM product_role WHERE 1=0")) {
            assertEquals(1,columns.getMetaData().getColumnCount(),"角色声明 Mapper 列必须存在于本地夹具");
        }
        try(ResultSet columns=statement.executeQuery("SELECT replacement_of_offer_id FROM merchant_chapter_application WHERE 1=0")) {
            assertEquals(1,columns.getMetaData().getColumnCount(),"商家补位 Mapper 列必须存在于本地夹具");
        }
    }

    public static class LocalInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext application) {
            assertEquals("127.0.0.1", MybatisItDatabase.host());
            Policy.setPolicy(new Policy() {
                @Override public PermissionCollection getPermissions(CodeSource source) {
                    Permissions permissions = new Permissions();
                    permissions.add(new AllPermission());
                    return permissions;
                }
            });
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkPermission(Permission permission) { }
                @Override public void checkPermission(Permission permission, Object accessContext) { }
                @Override public void checkConnect(String host, int port) {
                    if (!"127.0.0.1".equals(host) && !"localhost".equals(host)
                            && !"::1".equals(host) && !"0:0:0:0:0:0:0:1".equals(host)) {
                        throw new SecurityException("HTTP E2E forbids every non-loopback connection");
                    }
                }
                @Override public void checkConnect(String host, int port, Object accessContext) {
                    checkConnect(host, port);
                }
            });
            String database=System.getenv("CHENGYIN_HTTP_E2E_DATABASE");
            assertNotNull(database,"Run the isolated HTTP script first");
            assertTrue(database.matches("cy_e2e_w1_http_[0-9_]+"),"Private HTTP database required");
            String reportPath=System.getenv("CHENGYIN_HTTP_E2E_SCHEMA_REPORT");
            assertNotNull(reportPath,"Full fresh-schema report required");
            try {
                JsonNode report=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(Paths.get(reportPath)));
                assertEquals("SUCCEEDED",report.path("mode").asText());assertEquals(database,report.path("database").asText());
                assertTrue(report.path("files").size()>100);assertEquals(report.path("files").size(),report.path("completed").size());
            } catch(java.io.IOException failure) {throw new IllegalStateException("Complete fresh-schema report unavailable",failure);}
            String url="jdbc:mysql://127.0.0.1:"+MybatisItDatabase.port()+"/"+database+"?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
            try (Connection connection = DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password());
                 Statement statement = connection.createStatement()) {
                assertFixtureMapperColumns(statement);
            } catch (Exception failure) {
                throw new IllegalStateException("Local HTTP schema bootstrap failed", failure);
            }
            byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
            String secret = UUID.randomUUID().toString() + UUID.randomUUID().toString();
            String configuredPort = System.getenv("CHENGYIN_HTTP_E2E_REDIS_PORT");
            assertNotNull(configuredPort, "Run test/tools/generic-gameplay-http-e2e.sh with its private Redis");
            int redisPort = Integer.parseInt(configuredPort);
            assertThrows(SecurityException.class, () -> System.getSecurityManager().checkConnect("outside.invalid", 443));
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(application,
                    "server.address=127.0.0.1", "server.port=0", "server.servlet.context-path=",
                    "spring.datasource.druid.master.url=" + url,
                    "spring.datasource.druid.master.username=" + MybatisItDatabase.user(),
                    "spring.datasource.druid.master.password=" + MybatisItDatabase.password(),
                    "spring.datasource.druid.slave.enabled=false",
                    "spring.redis.host=127.0.0.1", "spring.redis.port=" + redisPort, "spring.redis.database=15", "spring.redis.password=",
                    "token.secret=" + secret, "coupon.qr-secret=" + secret,
                    "chengyin.pii.key=" + Base64.getEncoder().encodeToString(key),
                    "withdrawal.pii.key=" + Base64.getEncoder().encodeToString(key),
                    "wx.pay.enabled=false", "wx.pay.api-v3-key=", "wx.sec-check.enabled=false", "wx.mini.secret=",
                    "alarm.webhook=", "alarm.failure-webhook=",
                    "spring.quartz.auto-startup=false", "spring.devtools.restart.enabled=false",
                    "chengyin.status.feed-file=target/http-e2e/status.json", "logging.level.com.chengyinhub=WARN");
        }
    }

    @org.junit.jupiter.api.BeforeAll void loadExistingPermissionFixture() throws Exception {
        // Fresh-schema skips migration data; reuse its existing role defaults for local author capabilities.
        for(String file:new String[]{"sql/migration_p1_rbac_permission_defaults.sql","sql/migration_m1_permission_full.sql","sql/migration_p1_rbac_permission_defaults.sql"})
            for(String statement:ItSchema.splitStatements(new String(Files.readAllBytes(Paths.get(file)),java.nio.charset.StandardCharsets.UTF_8))) {
                String sql=statement.replaceAll("(?m)^\\s*--.*$","").trim();
                if(sql.matches("(?is)^(INSERT INTO|UPDATE) user_permission\\b.*"))database.execute(sql);
            }
    }

    @AfterAll void closeLocalServer() throws Exception {
        if (context != null) {
            java.util.concurrent.ScheduledExecutorService executor = context.getBean("scheduledExecutorService", java.util.concurrent.ScheduledExecutorService.class);
            executor.shutdown();
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) executor.shutdownNow();
        }
        Files.createDirectories(Paths.get("target/http-e2e"));
        json.writerWithDefaultPrettyPrinter().writeValue(Paths.get("target/http-e2e/transcript.json").toFile(),transcript);
    }

    @Test void realHttpUsesTheProductionControllerAndRejectsAnonymousState() throws Exception {
        assertNotNull(context.getBean(com.chengyinhub.web.controller.api.ApiThemeRuntimeController.class));
        ResponseEntity<JsonNode> anonymous = http.getForEntity("/api/play/theme/state?topicId=1", JsonNode.class);
        record("GET","/api/play/theme/state?topicId=1",null,anonymous);
        assertNotEquals(200, anonymous.getBody().path("code").asInt());
        UmsMember author = new UmsMember();author.setPhone("13800000001");author.setNickname("本地测试作者");
        author.setStatus(1L);author.setDelFlag(0);members.insertUmsMember(author);
        MockHttpServletRequest issuance = new MockHttpServletRequest();issuance.setRemoteAddr("127.0.0.1");
        issuance.addHeader("User-Agent", "Local HTTP E2E");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(issuance));
        String jwt;
        try { jwt = login.loginByMember(author,"phone",author.getPhone(),null); }
        finally { RequestContextHolder.resetRequestAttributes(); }
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();headers.setBearerAuth(jwt);
        ResponseEntity<JsonNode> matrix = http.exchange("/api/play/theme/support-matrix", org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), JsonNode.class);
        record("GET","/api/play/theme/support-matrix",null,matrix);
        assertEquals(200, matrix.getStatusCode().value());
        assertEquals(200, matrix.getBody().path("code").asInt());
        ObjectNode effectTemplate=json.createObjectNode().put("schemaVersion",1);
        effectTemplate.putArray("effects").addObject().put("var","energy").put("op","FLOOR").put("value",8);
        effectTemplate.putObject("interactionRules").put("limit",2).putArray("resourceSlots").addObject()
                .put("key","energy").put("kind","VALUE").put("label","所需数值").put("required",true);
        ObjectNode template = json.createObjectNode().put("title","本地HTTP资源模板")
                .put("validationMethod",4).put("isSync",0).put("advancedConfigJson",effectTemplate.toString());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> draft = http.exchange("/api/template/draft", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(template,headers),JsonNode.class);
        record("POST","/api/template/draft",template,draft);
        assertEquals(200,draft.getBody().path("code").asInt(),draft.getBody().toString());
        long templateId=draft.getBody().path("data").asLong();assertTrue(templateId>0);
        JsonNode storedTemplate=json.readTree(database.queryForObject("SELECT advanced_config_json FROM cms_member_template WHERE id=?",String.class,templateId));
        assertEquals("energy",storedTemplate.path("effects").path(0).path("var").asText());
        ObjectNode templateReadback=json.createObjectNode().put("templateId",templateId).put("memberId",author.getId());templateReadback.set("advancedConfig",storedTemplate);
        transcript.addObject().put("method","DB_READBACK").put("path","cms_member_template:"+templateId).put("httpStatus",0).set("response",templateReadback);
        ObjectNode topic = topicRequest("本地HTTP通用主题");
        ResponseEntity<JsonNode> created = http.exchange("/api/topic/v2/create", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(topic,headers),JsonNode.class);
        record("POST","/api/topic/v2/create",topic,created);
        assertEquals(200,created.getBody().path("code").asInt(),created.getBody().toString());
        long topicId=created.getBody().path("data").path("topicId").asLong();assertTrue(topicId>0);
        org.springframework.http.HttpHeaders stranger=loginHeaders("13800000002","其他作者");
        rejected(form("/api/topic/edit-detail",topicId,stranger));
        rejected(form("/api/template/myinfo",templateId,stranger));
        ObjectNode attack=topic.deepCopy().put("id",topicId).put("name","越权改名");rejected(post("/api/topic/v2/update",attack,stranger));
        rejected(post("/api/template/draft",template.deepCopy().put("id",templateId).put("title","越权模板"),stranger));
        assertEquals(author.getId(),database.queryForObject("SELECT member_id FROM cms_member_template WHERE id=?",Long.class,templateId));
        assertEquals("本地HTTP资源模板",database.queryForObject("SELECT title FROM cms_member_template WHERE id=?",String.class,templateId));
        assertEquals("本地HTTP通用主题",database.queryForObject("SELECT name FROM cms_topic WHERE id=?",String.class,topicId));
        topic.put("id",topicId);refreshChapterIds(topic,topicId);
        topic.put("journeyRules","{\"schemaVersion\":1,\"stateEnabled\":true,\"checkEnabled\":false,\"hp\":{\"enabled\":true,\"init\":6,\"max\":12},\"attributes\":[{\"key\":\"counter.focus\",\"label\":\"专注\",\"initial\":2,\"min\":0,\"max\":12}]}");
        success(post("/api/topic/v2/update",topic,headers));
        ObjectNode registration=json.createObjectNode().put("topicId",topicId);
        registration.putArray("keys").addObject().put("stateKey","counter.focus").put("keyType","counter").put("scope","run").put("label","专注").put("initialValue",2);
        success(post("/api/topic/state-keys",registration,headers));
        refreshChapterIds(topic,topicId);
        ((ObjectNode)topic.path("chapters").path(0)).withArray("nodes").addObject()
                .put("clientNodeKey","restore").put("name","补充站").put("templateId",templateId).put("nodeTime",0).put("nodeMoney",0).put("latitude","31.2304").put("longitude","121.4737").put("address","本地测试位置");
        ((ObjectNode)topic.path("chapters").path(0)).withArray("blocks").addObject().put("type","node").put("nodeIndex",0);
        ObjectNode theme=json.createObjectNode().put("schemaVersion",1).put("capacity",6).put("hpName","状态");
        theme.putObject("gameplayRuntime").put("runtimeScope","PLAYER").putObject("survival").put("zeroHpPolicy","BLOCK_HP_COST").put("actionLimitPolicy","BLOCK_OVERFLOW");
        ObjectNode binding=theme.putObject("templateBindings").putObject("client:restore").put("templateId",templateId)
                .put("slotDigest",GameplayTemplateRules.slotDigest(effectTemplate.path("interactionRules").path("resourceSlots")));
        binding.putObject("values").put("energy","counter.focus");binding.putObject("items");
        JsonNode beforeAdoption=persistedTopic(topicId,"before-adoption");
        java.util.List<Long> beforeNodes=database.queryForList("SELECT id FROM cms_topic_node WHERE topic_id=? ORDER BY id",Long.class,topicId);
        ObjectNode foreignTheme=theme.deepCopy();ObjectNode foreignBindings=(ObjectNode)foreignTheme.path("templateBindings");
        foreignBindings.set("client:foreign",foreignBindings.remove("client:restore"));
        JsonNode foreign=post("/api/topic/v2/update",topic.deepCopy().put("themeRuntimeConfig",foreignTheme.toString()),headers);
        rejected(foreign);assertTrue(foreign.path("msg").asText().contains("client:foreign"),foreign.toString());
        assertEquals(beforeAdoption,persistedTopic(topicId,"before-adoption"));
        assertEquals(beforeNodes,database.queryForList("SELECT id FROM cms_topic_node WHERE topic_id=? ORDER BY id",Long.class,topicId));
        topic.put("themeRuntimeConfig",theme.toString());JsonNode adoption=post("/api/topic/v2/update",topic,headers);
        if(adoption.path("code").asInt()!=200) {
            assertEquals(beforeAdoption,persistedTopic(topicId,"before-adoption"));
            assertEquals(beforeNodes,database.queryForList("SELECT id FROM cms_topic_node WHERE topic_id=? ORDER BY id",Long.class,topicId));
        }
        success(adoption);
        long nodeId=database.queryForObject("SELECT id FROM cms_topic_node WHERE topic_id=? ORDER BY id LIMIT 1",Long.class,topicId);
        JsonNode persisted=persistedTopic(topicId,"adopted");
        assertEquals("counter.focus",persisted.path("theme").path("templateBindings").path(String.valueOf(nodeId)).path("values").path("energy").asText());
        assertFalse(persisted.path("theme").toString().contains("client:"));
        ObjectNode bad=topic.deepCopy();refreshChapterIds(bad,topicId);
        ((ObjectNode)theme.path("templateBindings").path("client:restore")).put("slotDigest",String.join("",java.util.Collections.nCopies(64,"0")));
        bad.put("themeRuntimeConfig",theme.toString());String before=database.queryForObject("SELECT theme_runtime_config_json FROM cms_topic WHERE id=?",String.class,topicId);
        java.util.List<Long> chapterIds=database.queryForList("SELECT id FROM cms_topic_chapter WHERE topic_id=? ORDER BY id",Long.class,topicId);
        JsonNode stale=post("/api/topic/v2/update",bad,headers);rejected(stale);
        assertTrue(stale.path("msg").asText().contains("声明已变化"),stale.toString());
        assertEquals(before,database.queryForObject("SELECT theme_runtime_config_json FROM cms_topic WHERE id=?",String.class,topicId));
        assertEquals(chapterIds,database.queryForList("SELECT id FROM cms_topic_chapter WHERE topic_id=? ORDER BY id",Long.class,topicId));
        success(form("/api/topic/publish-draft",topicId,headers));persisted=persistedTopic(topicId,"published");
        assertEquals("counter.focus",persisted.path("snapshot").path("nodeConfigs").path(String.valueOf(nodeId)).path("effects").path(0).path("var").asText());
        assertEquals("counter.focus",persisted.path("snapshot").path("nodeJourney").path(String.valueOf(nodeId)).path("effects").path(0).path("var").asText());
        assertFalse(persisted.path("snapshot").toString().contains("resourceSlots"));assertFalse(persisted.path("snapshot").toString().contains("templateBindings"));
        ResponseEntity<JsonNode> play=http.exchange("/api/play/theme/state?topicId="+topicId,org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(headers),JsonNode.class);
        record("GET","/api/play/theme/state?topicId="+topicId,null,play);
        rejected(play.getBody());assertTrue(play.getBody().path("msg").asText().contains("通行证"),play.getBody().toString());
        assertFreshTemplateCopyIsGated(topicId,headers);
    }
    @Test void terminalVoteAdoptionUsesThePersistedFinalChapter() throws Exception {
        org.springframework.http.HttpHeaders headers=loginHeaders("13800000003","结局测试作者");
        ObjectNode advanced=json.createObjectNode().put("schemaVersion",1);
        ObjectNode tools=advanced.putObject("interactionTools").put("votePolicy","MAJORITY").put("decidesEnding",true);
        ArrayNode plans=tools.putArray("plans");plans.addObject().put("planKey","finish").put("title","共同结束");
        long templateId=success(post("/api/template/draft",json.createObjectNode().put("title","本地结局投票模板")
                .put("validationMethod",4).put("isSync",0).put("advancedConfigJson",advanced.toString()),headers)).asLong();
        ObjectNode topic=topicRequest("本地HTTP结局主题");
        ObjectNode ending=((ArrayNode)topic.path("chapters")).addObject().put("name","最终章").put("schemaVersion",1);
        ending.putArray("nodes");ending.putArray("blocks").addObject().put("type","text").put("content","到达共同结局");
        ending.putObject("ending").put("fallback",true);
        long topicId=success(post("/api/topic/v2/create",topic,headers)).path("topicId").asLong();assertTrue(topicId>0);topic.put("id",topicId);refreshChapterIds(topic,topicId);
        JsonNode before=persistedTopic(topicId,"before-terminal-adoption");
        long endingChapterId=topic.path("chapters").path(1).path("id").asLong();
        assertEquals(endingChapterId,before.path("story").path("ending").path("endings").path(0).path("chapterId").asLong());
        ObjectNode first=(ObjectNode)topic.path("chapters").path(0);
        first.withArray("nodes").addObject().put("clientNodeKey","vote").put("name","讨论站").put("templateId",templateId).put("nodeTime",0).put("nodeMoney",0)
                .put("latitude","31.2304").put("longitude","121.4737").put("address","本地测试位置");
        first.withArray("blocks").addObject().put("type","node").put("nodeIndex",0);
        ObjectNode theme=json.createObjectNode().put("schemaVersion",1).put("capacity",6);
        theme.putObject("gameplayRuntime").put("runtimeScope","TEAM").put("worldMode","WORLD_SHARED");
        ObjectNode binding=theme.putObject("templateBindings").putObject("client:vote").put("templateId",templateId)
                .put("slotDigest",GameplayTemplateRules.slotDigest(json.createArrayNode())).put("planDigest",GameplayTemplateRules.planDigest(plans));
        binding.putObject("values");binding.putObject("items");binding.putObject("planEndings").put("finish",String.valueOf(endingChapterId));
        topic.put("themeRuntimeConfig",theme.toString());
        JsonNode response=post("/api/topic/v2/update",topic,headers);
        if(response.path("code").asInt()!=200) {
            assertEquals(before,persistedTopic(topicId,"before-terminal-adoption"),"终局绑定验证失败必须回滚章节与主题");
        }
        success(response);
        success(form("/api/topic/publish-draft",topicId,headers));
        JsonNode frozen=persistedTopic(topicId,"terminal-published");
        JsonNode plan=frozen.path("published").path("gameplayRuntime").path("plans").path(0);
        assertTrue(plan.path("terminal").asBoolean());assertEquals("ending1",plan.path("storyEndingId").asText());
        assertEquals(frozen.path("story").path("ending").path("endings").path(0).path("chapterId"),plan.path("endingChapterId"));
        assertFreshTemplateCopyIsGated(topicId,headers);
    }
    private static class Lifecycle {
        org.springframework.http.HttpHeaders headers;
        long memberId, templateId, topicId, nodeId, routeId, advancedId, activityId, teamId;
        org.springframework.http.HttpHeaders partner;
        String valueKey;
        JsonNode publication;
    }
    private ObjectNode r5(String kit) {
        ObjectNode c=json.createObjectNode().put("schemaVersion",1);
        ObjectNode main=c.putObject(kit).put("enabled",true);
        if("qa".equals(kit))main.put("title","答案").put("mode","TYPE").put("answerText","correct");
        if("typeIn".equals(kit))main.put("title","打字").put("target","hello").put("seconds",3).put("tries",1);
        if("check".equals(kit))main.put("checkId","lc").put("tier","hard");
        ObjectNode o=c.putObject("outcomeRules").put("version",1);
        o.putObject("pass").put("reply","完成").putArray("costs").addObject().put("statKey","vital").put("amount",1);
        if(!"album".equals(kit))o.putObject("fail").put("reply","再试一次").putArray("costs").addObject().put("statKey","vital").put("amount",9);
        c.putObject("interactionRules").put("limit",2).putArray("resourceSlots").addObject().put("key","vital").put("kind","VALUE").put("label","生命").put("required",true);
        return c;
    }
    private Lifecycle lifecycle(ObjectNode config,String policy) throws Exception {
        return lifecycle(config,policy,"counter.energy");
    }
    private Lifecycle lifecycle(ObjectNode config,String policy,String valueKey) throws Exception {
        return lifecycle(config,policy,valueKey,"PLAYER",false);
    }
    private Lifecycle lifecycle(ObjectNode config,String policy,String valueKey,String scope,boolean publishOnly) throws Exception {
        return lifecycle(config,policy,valueKey,scope,publishOnly,4);
    }
    private Lifecycle lifecycle(ObjectNode config,String policy,String valueKey,String scope,boolean publishOnly,int participation) throws Exception {
        Lifecycle s=new Lifecycle();s.valueKey=valueKey;String phone="138"+String.format("%08d",100+transcript.size());
        s.headers=loginHeaders(phone,"生命周期作者");s.memberId=database.queryForObject("SELECT id FROM ums_member WHERE phone=?",Long.class,phone);
        ObjectNode template=json.createObjectNode().put("title","LC "+phone).put("validationMethod",participation).put("isSync",0).put("advancedConfigJson",config.toString());
        s.templateId=success(post("/api/template/draft",template,s.headers)).asLong();
        template.put("id",s.templateId);success(post("/api/template/draft",template,s.headers));
        JsonNode read=success(form("/api/template/myinfo",s.templateId,s.headers));assertTrue(read.toString().contains("advancedConfigJson"),read.toString());
        ObjectNode topic=topicRequest("LC "+phone);if("TEAM".equals(scope))topic.put("teamMode",1).put("teamMaxMembers",4);s.topicId=success(post("/api/topic/v2/create",topic,s.headers)).path("topicId").asLong();topic.put("id",s.topicId);refreshChapterIds(topic,s.topicId);
        topic.put("journeyRules","{\"schemaVersion\":1,\"stateEnabled\":true,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"init\":6,\"max\":12},\"luck\":{\"enabled\":true,\"init\":2,\"max\":3},\"recovery\":{\"stageRestHpFloor\":0,\"stageRestLuckFloor\":0,\"stationEndIfHurt\":0}}");
        ObjectNode rules=(ObjectNode)json.readTree(topic.path("journeyRules").asText());
        if(!valueKey.startsWith("sys.")){rules.putArray("attributes").addObject().put("key",valueKey).put("label","能量").put("initial",6).put("min",0).put("max",12);ObjectNode registration=json.createObjectNode().put("topicId",s.topicId);registration.putArray("keys").addObject().put("stateKey",valueKey).put("keyType","counter").put("scope","run").put("label","能量").put("initialValue",6);success(post("/api/topic/state-keys",registration,s.headers));}

        topic.put("journeyRules",rules.toString());
        ObjectNode chapter=(ObjectNode)topic.path("chapters").path(0);
        for(int i=0;i<2;i++){chapter.withArray("nodes").addObject().put("clientNodeKey","lc"+i).put("name","站点"+i).put("templateId",s.templateId).put("nodeTime",0).put("nodeMoney",0).put("latitude","31.2304").put("longitude","121.4737").put("address","本地位置");chapter.withArray("blocks").addObject().put("type","node").put("nodeIndex",i);}
        ObjectNode theme=json.createObjectNode().put("schemaVersion",1).put("capacity",6);
        ObjectNode survival=theme.putObject("gameplayRuntime").put("runtimeScope",scope).putObject("survival").put("zeroHpPolicy",policy).put("actionLimitPolicy","BLOCK_OVERFLOW");
        if("STEPS".equals(policy))survival.putObject("zeroSteps").put("version",1).put("steps",100).put("restoreTo",4).put("limit",2).put("thenPolicy","THEME_FAIL");
        if("WAIT".equals(policy))survival.putObject("zeroWait").put("version",1).put("minutes",1).put("restoreTo",4).put("limit",2).put("thenPolicy","THEME_FAIL");
        if("TEAM".equals(scope))theme.with("gameplayRuntime").put("worldMode","WORLD_SHARED");
        boolean stock=config.path("interactionTools").path("plans").path(0).path("materialCost").size()>0;
        if(stock){theme.putArray("items").addObject().put("id","paper").put("kind","ITEM").put("name","纸张").put("image","https://static.chengyinhub.com/food.png").put("slotCost",1);theme.putArray("events").addObject().put("id","initial_stock").put("content","初始物资").putArray("grants").addObject().put("itemId","paper").put("quantity",2);}
        for(int i=0;i<2;i++){ObjectNode b=theme.with("templateBindings").putObject("client:lc"+i).put("templateId",s.templateId).put("slotDigest",GameplayTemplateRules.slotDigest(config.path("interactionRules").path("resourceSlots")));b.putObject("values").put("vital",valueKey);ObjectNode items=b.putObject("items");if(stock)items.put("stock","paper");}
        topic.put("themeRuntimeConfig",theme.toString());success(post("/api/topic/v2/update",topic,s.headers));success(form("/api/topic/edit-detail",s.topicId,s.headers));
        s.nodeId=database.queryForObject("SELECT id FROM cms_topic_node WHERE topic_id=? ORDER BY id LIMIT 1",Long.class,s.topicId);
        s.publication=form("/api/topic/publish-draft",s.topicId,s.headers);
        if(participation==0){rejected(s.publication);return s;}
        success(s.publication);JsonNode frozen=persistedTopic(s.topicId,"r5-frozen");if(config.has("outcomeRules"))assertEquals(valueKey,frozen.path("snapshot").path("nodeConfigs").path(String.valueOf(s.nodeId)).path("outcomeRules").path("pass").path("costs").path(0).path("statKey").asText());
        if(publishOnly)return s;
        // Local paid entitlement/static scan fixtures; no payment or remote QR API is exercised.
        database.update("INSERT INTO cms_registration(registration_no,member_id,owner_type,owner_id,topic_id,registration_status,payment_status,purchase_kind,original_amount,total_amount,payable_amount,pay_expire_time,create_time) VALUES(?,?,1,?,?,2,2,2,0,0,0,DATE_ADD(NOW(),INTERVAL 1 DAY),NOW())","lc-"+s.topicId,s.memberId,s.topicId,s.topicId);
        database.update("UPDATE cms_topic_node SET checkin_code=? WHERE id=?","lc-"+s.nodeId,s.nodeId);
        JsonNode view=theme(s);s.routeId=view.path("sessionId").asLong();assertTrue(s.routeId>0,view.toString());
        success(formFields("/api/play/checkin",json.createObjectNode().put("topicId",s.topicId).put("code","lc-"+s.nodeId).put("routeActionId","arrive"),s.headers));
        if(config.has("outcomeRules"))assertScanHasNotCompleted(s);
        success(route(s,"QUEST_START","start"));
        if(config.has("outcomeRules")){JsonNode advanced=success(post("/api/play/advanced/start",json.createObjectNode().put("topicId",s.topicId).put("nodeId",s.nodeId),s.headers));s.advancedId=advanced.path("sessionId").asLong();assertTrue(s.advancedId>0,advanced.toString());}
        return s;
    }
    private void assertScanHasNotCompleted(Lifecycle s) throws Exception {
        JsonNode read=theme(s),state=stored(s);
        assertTrue(read.path("lastOutcome").isNull() || read.path("lastOutcome").isMissingNode(),read.toString());
        assertEquals("sys.luck".equals(s.valueKey)?2:6,state.path("variables").path(s.valueKey).asInt());
        assertEquals(0,state.path("themeRuntime").path("verifiedNodes").path(String.valueOf(s.nodeId)).asLong());
        assertEquals(0,state.path("themeRuntime").path("cooperation").path("facilities").path("tpl_node_"+s.nodeId).path("count").asInt());
    }
    @Test void r5HttpDefaultAdoptedQaCanPublishAsZeroHpChallenge() throws Exception {
        org.springframework.http.HttpHeaders headers=loginHeaders("13800000931","归零挑战作者");
        ObjectNode config=r5("qa");config.remove("interactionRules");
        config.with("outcomeRules").with("pass").putArray("costs");
        config.with("outcomeRules").with("fail").putArray("costs");
        ObjectNode template=json.createObjectNode().put("title","R5免费恢复题组")
                .put("validationMethod",5).put("isSync",0).put("advancedConfigJson",config.toString());
        long templateId=success(post("/api/template/draft",template,headers)).asLong();
        template.put("id",templateId);success(post("/api/template/draft",template,headers));
        assertEquals(1,json.readTree(success(form("/api/template/myinfo",templateId,headers))
                .path("advancedConfigJson").asText()).path("outcomeRules").path("version").asInt());
        ObjectNode topic=topicRequest("R5默认采用归零挑战");
        long topicId=success(post("/api/topic/v2/create",topic,headers)).path("topicId").asLong();
        topic.put("id",topicId);
        JsonNode read=success(form("/api/topic/edit-detail",topicId,headers));
        topic.put("configVersion",read.path("topic").path("configVersion").asInt());
        ObjectNode chapter=(ObjectNode)topic.path("chapters").path(0);
        chapter.put("id",read.path("chapters").path(0).path("id").asLong());
        chapter.withArray("nodes").addObject().put("clientNodeKey","recovery").put("name","恢复题组")
                .put("templateId",templateId).put("nodeTime",0).put("nodeMoney",0)
                .put("latitude","31.2304").put("longitude","121.4737").put("address","本地位置");
        chapter.withArray("blocks").addObject().put("type","node").put("nodeIndex",0);
        chapter.withArray("nodes").addObject().put("clientNodeKey","ordinary").put("name","普通题组")
                .put("templateId",templateId).put("nodeTime",0).put("nodeMoney",0)
                .put("latitude","31.2304").put("longitude","121.4737").put("address","本地位置");
        chapter.withArray("blocks").addObject().put("type","node").put("nodeIndex",1);
        topic.put("journeyRules","{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"enabled\":true,\"init\":6,\"max\":12}}");
        ObjectNode theme=json.createObjectNode().put("schemaVersion",1).put("capacity",6);
        theme.putObject("gameplayRuntime").put("runtimeScope","PLAYER").putObject("survival")
                .put("zeroHpPolicy","BLOCK_HP_COST").put("actionLimitPolicy","BLOCK_OVERFLOW");
        ObjectNode binding=theme.putObject("templateBindings").putObject("client:recovery")
                .put("templateId",templateId).put("slotDigest",GameplayTemplateRules.slotDigest(json.createArrayNode()));
        binding.putObject("values");binding.putObject("items");
        topic.put("themeRuntimeConfig",theme.toString());success(post("/api/topic/v2/update",topic,headers));
        read=success(form("/api/topic/edit-detail",topicId,headers));
        long nodeId=read.path("chapters").path(0).path("cmsTopicNodeList").path(0).path("id").asLong();
        assertTrue(nodeId>0,read.toString());
        topic.put("configVersion",read.path("topic").path("configVersion").asInt());
        chapter.put("id",read.path("chapters").path(0).path("id").asLong());
        ((ObjectNode)chapter.path("nodes").path(0)).put("id",nodeId);
        long ordinaryNodeId=read.path("chapters").path(0).path("cmsTopicNodeList").path(1).path("id").asLong();
        assertTrue(ordinaryNodeId>0,read.toString());
        ((ObjectNode)chapter.path("nodes").path(1)).put("id",ordinaryNodeId);
        theme=(ObjectNode)json.readTree(read.path("themeRuntimeConfig").asText());
        ObjectNode survival=theme.with("gameplayRuntime").with("survival");
        survival.put("zeroHpPolicy","CHALLENGE").putObject("zeroChallenge").put("version",2)
                .put("challengeNodeId",nodeId).put("gameplayKey","qa").put("requiredCompletions",2)
                .put("withinSeconds",3600).put("thenPolicy","THEME_FAIL").put("restoreTo",4).put("limit",2);
        topic.put("themeRuntimeConfig",theme.toString());
        // The author selects the adopted node; ordinary task declarations remain the server defaults.
        success(post("/api/topic/v2/update",topic,headers));
        read=success(form("/api/topic/edit-detail",topicId,headers));
        nodeId=read.path("chapters").path(0).path("cmsTopicNodeList").path(0).path("id").asLong();
        ordinaryNodeId=read.path("chapters").path(0).path("cmsTopicNodeList").path(1).path("id").asLong();
        success(form("/api/topic/publish-draft",topicId,headers));
        JsonNode snapshot=json.readTree(database.queryForObject("SELECT published_route_graph_json FROM cms_topic WHERE id=?",String.class,topicId));
        JsonNode game=snapshot.path("themeConfig").path("gameplayRuntime");
        assertEquals(1,game.path("quests").size());
        assertEquals(ordinaryNodeId,game.path("quests").path(0).path("nodeId").asLong());
        assertEquals(nodeId,game.path("survival").path("zeroChallenge").path("challengeNodeId").asLong());
        assertEquals(1,snapshot.path("nodeConfigs").path(String.valueOf(nodeId)).path("outcomeRules").path("version").asInt());
    }
    @Test void personalR5DirectParticipationWithoutMerchantIsRefused() throws Exception {
        Lifecycle s=lifecycle(r5("qa"),"BLOCK_HP_COST","counter.energy","PLAYER",true,0);
        assertTrue(s.publication.path("msg").asText().contains("个人任务"),s.publication.toString());
        assertNull(database.queryForObject("SELECT published_route_graph_json FROM cms_topic WHERE id=?",String.class,s.topicId));
        success(form("/api/topic/edit-detail",s.topicId,s.headers));success(form("/api/template/myinfo",s.templateId,s.headers));
    }
    @Test void personalR5ScanOnlyDoesNotCompleteTheGameplay() throws Exception {
        Lifecycle s=lifecycle(r5("qa"),"BLOCK_HP_COST");assertScanHasNotCompleted(s);
        assertFalse(advanced(s).path("readyForBase").asBoolean());rejected(route(s,"QUEST_RESOLVE","scan-only"));
        assertScanHasNotCompleted(s);act(s,"SUBMIT_QA","correct",json.createObjectNode().put("input","correct"));
        success(route(s,"QUEST_RESOLVE","settle"));assertOutcome(s,"PASS",5,1);
    }
    private JsonNode get(String path,org.springframework.http.HttpHeaders headers) {
        ResponseEntity<JsonNode> response=http.exchange(path,org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(headers),JsonNode.class);record("GET",path,null,response);return response.getBody();
    }
    private JsonNode formFields(String path,ObjectNode fields,org.springframework.http.HttpHeaders original) {
        org.springframework.http.HttpHeaders headers=new org.springframework.http.HttpHeaders();headers.putAll(original);headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
        org.springframework.util.LinkedMultiValueMap<String,String> body=new org.springframework.util.LinkedMultiValueMap<>();fields.fields().forEachRemaining(e->body.add(e.getKey(),e.getValue().asText()));
        ResponseEntity<JsonNode> response=http.exchange(path,org.springframework.http.HttpMethod.POST,new org.springframework.http.HttpEntity<>(body,headers),JsonNode.class);record("POST",path,fields,response);return response.getBody();
    }
    private JsonNode theme(Lifecycle s){return success(get("/api/play/theme/state?topicId="+s.topicId,s.headers));}
    private JsonNode advanced(Lifecycle s){return success(get("/api/play/advanced/state?sessionId="+s.advancedId,s.headers));}
    private ObjectNode actionBody(Lifecycle s,String action,String key,ObjectNode payload){ObjectNode b=json.createObjectNode().put("sessionId",s.advancedId).put("version",advanced(s).path("version").asInt()).put("idempotencyKey","lc-"+s.advancedId+"-"+key).put("action",action);b.set("payload",payload);return b;}
    private JsonNode act(Lifecycle s,String action,String key,ObjectNode payload){return success(post("/api/play/advanced/action",actionBody(s,action,key,payload),s.headers));}
    private JsonNode route(Lifecycle s,String operation,String key){return post("/api/play/theme/gameplay-action",json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId",key).put("operation",operation).put("contentId","node_"+s.nodeId),s.headers);}
    private JsonNode stored(Lifecycle s) throws Exception {return json.readTree(database.queryForObject("SELECT state_json FROM topic_route_session WHERE id=?",String.class,s.routeId));}
    private void assertOutcome(Lifecycle s,String outcome,int hp,int uses) throws Exception {
        JsonNode state=stored(s);assertEquals(hp,state.path("variables").path(s.valueKey).asInt());assertEquals(outcome,state.path("themeRuntime").path("lastOutcome").path("outcome").asText());
        assertEquals(uses,state.path("themeRuntime").path("cooperation").path("facilities").path("tpl_node_"+s.nodeId).path("count").asInt(),state.toString());
        JsonNode read=theme(s);assertEquals(outcome,read.path("lastOutcome").path("outcome").asText(),read.toString());
        assertPersonalCardReadback(s,state);
        JsonNode usage=null;for(JsonNode quest:read.path("quests"))if(quest.path("nodeId").asLong()==s.nodeId)usage=quest.path("facility");assertNotNull(usage,read.toString());assertEquals(uses,usage.path("count").asInt());assertEquals(2-uses,usage.path("remainingCount").asInt());success(form("/api/topic/edit-detail",s.topicId,s.headers));success(form("/api/template/myinfo",s.templateId,s.headers));
    }
    private void assertPersonalCardReadback(Lifecycle s,JsonNode state) {
        JsonNode card=get("/api/play/theme/card?sessionId="+s.routeId,s.headers);
        if(state.path("themeRuntime").path("result").isObject()) {
            JsonNode data=success(card);assertEquals(state.path("themeRuntime").path("result").path("success"),data.path("success"));
            assertEquals(data,success(get("/api/play/theme/card?sessionId="+s.routeId,s.headers)));
        } else {rejected(card);assertTrue(card.path("msg").asText().contains("尚未结束"),card.toString());}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void r5HttpTypeInUnlimitedAndTimerDeadlineSurviveReentry(boolean timer) throws Exception {
            ObjectNode c=r5("typeIn");if(timer)c.putObject("timer").put("enabled",true).put("durationSeconds",30).put("timeoutResult","FAILED");
            Lifecycle s=lifecycle(c,"BLOCK_HP_COST");act(s,"START_CHALLENGE","begin",json.createObjectNode().put("game","typeIn"));
            JsonNode before=advanced(s);JsonNode reopened=success(post("/api/play/advanced/start",json.createObjectNode().put("topicId",s.topicId).put("nodeId",s.nodeId),s.headers));
            assertEquals(before.path("deadlineAt"),reopened.path("deadlineAt"));if(timer)assertTrue(before.path("deadlineAt").asLong()>0,before.toString());
            else assertTrue(before.path("deadlineAt").isNull(),before.toString());
            ObjectNode body=actionBody(s,"SUBMIT_TYPE_IN","typed",json.createObjectNode().put("text","hello").put("elapsedMs",0));JsonNode judged=success(post("/api/play/advanced/action",body,s.headers));assertEquals(judged,success(post("/api/play/advanced/action",body,s.headers)));
            ObjectNode settle=json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId","settle").put("operation","QUEST_RESOLVE").put("contentId","node_"+s.nodeId);
            JsonNode done=success(post("/api/play/theme/gameplay-action",settle,s.headers));assertEquals(done,success(post("/api/play/theme/gameplay-action",settle,s.headers)));assertOutcome(s,"PASS",5,1);
    }
    @Test void r5HttpPassInsufficientCostKeepsProofAndDoesNotConsumeSuccessfulUse() throws Exception {
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(com.chengyinhub.business.service.ITopicRouteRuntimeService.class));Object original=org.springframework.test.util.ReflectionTestUtils.getField(target,"d20");
        // Existing injected dice seam fixes PASS; real HTTP reroll spends luck after affordability preflight.
        org.springframework.test.util.ReflectionTestUtils.setField(target,"d20",(java.util.function.IntSupplier)()->20);
        try {
        ObjectNode c=r5("check");((ObjectNode)c.path("outcomeRules").path("pass").path("costs").path(0)).put("amount",2);
        Lifecycle s=lifecycle(c,"BLOCK_HP_COST","sys.luck");act(s,"ROLL_CHECK","roll",json.createObjectNode());act(s,"REROLL_CHECK","reroll",json.createObjectNode());
        assertEquals(1,stored(s).path("variables").path("sys.luck").asInt());act(s,"SUBMIT_CHECK","submit",json.createObjectNode());
        ObjectNode body=json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId","blocked-pass").put("operation","QUEST_RESOLVE").put("contentId","node_"+s.nodeId);
        JsonNode result=success(post("/api/play/theme/gameplay-action",body,s.headers));assertEquals(result,success(post("/api/play/theme/gameplay-action",body,s.headers)));
        assertEquals("BLOCKED_COST",result.path("settlement").path("status").asText(),result.toString());assertOutcome(s,"PASS",1,0);
        assertEquals("node_"+s.nodeId,stored(s).path("themeRuntime").path("gameplay").path("activeQuest").asText());assertTrue(advanced(s).path("readyForBase").asBoolean());
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(target,"d20",original);}
    }
    @Test void r5HttpFailClampsAndFreezesFailureCard() throws Exception {
        Lifecycle s=lifecycle(r5("qa"),"THEME_FAIL","sys.hp");ObjectNode b=actionBody(s,"SUBMIT_QA","wrong",json.createObjectNode().put("input","wrong"));JsonNode result=success(post("/api/play/advanced/action",b,s.headers));assertEquals(result,success(post("/api/play/advanced/action",b,s.headers)));assertOutcome(s,"FAIL",0,0);
        JsonNode last=stored(s).path("themeRuntime").path("lastOutcome");assertEquals(9,last.path("costs").path(0).path("requested").asInt(),last.toString());assertEquals(6,last.path("costs").path(0).path("charged").asInt());assertFalse(last.toString().contains("BLOCKED_COST"));
        JsonNode card=success(get("/api/play/theme/card?sessionId="+s.routeId,s.headers));assertFalse(card.path("success").asBoolean(true));assertEquals(card,success(get("/api/play/theme/card?sessionId="+s.routeId,s.headers)));rejected(route(s,"QUEST_START","closed"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"easy","medium","hard"})
    void r5HttpCheckRollRerollSubmitHasAuthoritativeActions(String tier) throws Exception {
        ObjectNode c=r5("check");c.with("check").put("tier",tier);Lifecycle s=lifecycle(c,"BLOCK_HP_COST");assertTrue(advanced(s).path("check").path("allowedActions").toString().contains("ROLL_CHECK"));
        act(s,"ROLL_CHECK","roll",json.createObjectNode());JsonNode rolled=advanced(s);assertTrue(rolled.path("check").path("allowedActions").toString().contains("SUBMIT_CHECK"));
        act(s,"REROLL_CHECK","reroll",json.createObjectNode());assertFalse(advanced(s).path("check").path("allowedActions").toString().contains("REROLL_CHECK"));
        ObjectNode b=actionBody(s,"SUBMIT_CHECK","submit",json.createObjectNode());JsonNode done=success(post("/api/play/advanced/action",b,s.headers));assertEquals(done,success(post("/api/play/advanced/action",b,s.headers)));
        String outcome=done.path("check").path("success").asBoolean()?"PASS":"FAIL";if("PASS".equals(outcome)){success(route(s,"QUEST_RESOLVE","confirm"));assertOutcome(s,"PASS",5,1);}else assertOutcome(s,"FAIL",0,0);
    }

    @Test void r5HttpAlbumAcceptsOwnedEvidenceAndRefusesForeignEvidence() throws Exception {
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class));
        Object original=org.springframework.test.util.ReflectionTestUtils.getField(target,"photoEvidenceVerifier");
        // Existing IT upload-verifier seam: HTTP/transactions are real, OSS metadata is local evidence.
        org.springframework.test.util.ReflectionTestUtils.setField(target,"photoEvidenceVerifier",(java.util.function.BiConsumer<String,Long>)(url,member)->{if(!url.equals("/profile/lc-owned-"+member+".png"))throw new IllegalArgumentException("foreign upload");});
        try {
            Lifecycle s=lifecycle(r5("album"),"BLOCK_HP_COST");ObjectNode foreign=json.createObjectNode();foreign.putArray("imageUrls").add("/profile/lc-owned-0.png");String before=stored(s).toString();rejected(post("/api/play/advanced/action",actionBody(s,"SUBMIT_ALBUM","foreign",foreign),s.headers));assertEquals(before,stored(s).toString());
            ObjectNode owned=json.createObjectNode();owned.putArray("imageUrls").add("/profile/lc-owned-"+s.memberId+".png");ObjectNode body=actionBody(s,"SUBMIT_ALBUM","album",owned);JsonNode done=success(post("/api/play/advanced/action",body,s.headers));assertTrue(done.path("playKit").path("album").path("done").asBoolean(),done.toString());assertEquals(done,success(post("/api/play/advanced/action",body,s.headers)));success(route(s,"QUEST_RESOLVE","confirm"));assertOutcome(s,"PASS",5,1);
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(target,"photoEvidenceVerifier",original);}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PASS","FAIL","CLOSED"})
    void r5HttpSettledFeedbackCanReopenThroughTheComponentStartPath(String outcome) throws Exception {
        Lifecycle s=lifecycle(feedbackConfig(),"BLOCK_HP_COST","sys.hp");
        boolean passed=!"FAIL".equals(outcome);
        act(s,"SUBMIT_QA","answer",json.createObjectNode().put("input",passed?"correct":"wrong"));
        if(passed)success(route(s,"QUEST_RESOLVE","confirm"));
        if("CLOSED".equals(outcome))success(post("/api/play/theme/action",json.createObjectNode()
                .put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt())
                .put("actionId","close-feedback").put("operation","END"),s.headers));
        JsonNode before=advanced(s);assertEquals("feedback",before.path("branch").path("currentStepId").asText());
        assertEquals(!"CLOSED".equals(outcome),before.path("branch").path("writable").asBoolean());
        JsonNode routeBefore=stored(s);
        // advanced-game opens a node through /start; a /state-only readback misses this reentry gate.
        JsonNode reopened=success(post("/api/play/advanced/start",json.createObjectNode()
                .put("topicId",s.topicId).put("nodeId",s.nodeId),s.headers));
        assertEquals(s.advancedId,reopened.path("sessionId").asLong());
        assertEquals(before.path("branch"),reopened.path("branch"));
        assertEquals(before.path("version"),reopened.path("version"));
        assertEquals(routeBefore,stored(s),"Reopen must not charge costs or settle the route again");
    }
    private ObjectNode feedbackConfig() {
        ObjectNode c=r5("qa");for(String result:new String[]{"pass","fail"})c.with("outcomeRules").with(result).put("nextStepId","feedback");
        ArrayNode steps=c.putObject("branch").put("enabled",true).put("startStepId","feedback").putArray("steps");
        steps.addObject().put("id","feedback").put("title","反馈").putArray("options").addObject().put("id","continue").put("label","继续").put("nextStepId","done").putArray("effects").addObject().put("var","vital").put("op","INC").put("value",1);
        steps.addObject().put("id","done").put("terminal",true).putArray("options");return c;
    }
    @Test void r5HttpFeedbackRunsAfterOutcomeOnceAndClosedSessionRefusesChoice() throws Exception {
        for(boolean close:new boolean[]{false,true}){
            Lifecycle s=lifecycle(feedbackConfig(),"BLOCK_HP_COST");rejected(post("/api/play/advanced/action",actionBody(s,"CHOOSE","early",json.createObjectNode().put("optionId","continue")),s.headers));
            act(s,"SUBMIT_QA","wrong",json.createObjectNode().put("input","wrong"));assertOutcome(s,"FAIL",0,0);JsonNode receipt=stored(s).path("themeRuntime").path("lastOutcome").deepCopy();
            if(close){success(post("/api/play/theme/action",json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId","end").put("operation","END"),s.headers));rejected(post("/api/play/advanced/action",actionBody(s,"CHOOSE","closed",json.createObjectNode().put("optionId","continue")),s.headers));}
            else {ObjectNode body=actionBody(s,"CHOOSE","choice",json.createObjectNode().put("optionId","continue"));JsonNode done=success(post("/api/play/advanced/action",body,s.headers));assertEquals(done,success(post("/api/play/advanced/action",body,s.headers)));assertEquals(1,stored(s).path("variables").path(s.valueKey).asInt());assertTrue(advanced(s).path("branch").path("ended").asBoolean());}
            assertEquals(receipt,stored(s).path("themeRuntime").path("lastOutcome"));theme(s);
        }
    }
    @Test void r5HttpStepsRecoveryEpisodeUsesVerifiedFixtureAndRestoresExactlyOnce() throws Exception {
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(com.chengyinhub.business.service.ITopicRouteRuntimeService.class));Object original=org.springframework.test.util.ReflectionTestUtils.getField(target,"verifiedWeRunService");
        org.springframework.test.util.ReflectionTestUtils.setField(target,"verifiedWeRunService",R5WeRunFixture.service());
        try {
            Lifecycle s=lifecycle(r5("qa"),"STEPS","sys.hp");act(s,"SUBMIT_QA","wrong",json.createObjectNode().put("input","wrong"));assertOutcome(s,"FAIL",0,0);JsonNode zero=theme(s).path("zeroHp");String episode=zero.path("episodeId").asText();assertFalse(episode.isEmpty(),zero.toString());assertTrue(zero.path("allowedActions").toString().contains("ZERO_HP_STEPS"));assertEquals(episode,theme(s).path("zeroHp").path("episodeId").asText());
            ObjectNode body=json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId","baseline").put("operation","ZERO_HP_STEPS");body.set("weRun",R5WeRunFixture.today(System.currentTimeMillis(),s.memberId,episode,1000));JsonNode first=success(post("/api/play/theme/gameplay-action",body,s.headers));assertEquals(first,success(post("/api/play/theme/gameplay-action",body,s.headers)));assertEquals(0,theme(s).path("zeroHp").path("progress").asInt());
            body.put("expectedVersion",theme(s).path("version").asInt()).put("actionId","restore");body.set("weRun",R5WeRunFixture.today(System.currentTimeMillis(),s.memberId,episode,1100));JsonNode restored=success(post("/api/play/theme/gameplay-action",body,s.headers));assertEquals(restored,success(post("/api/play/theme/gameplay-action",body,s.headers)));assertEquals(4,stored(s).path("variables").path("sys.hp").asInt());assertEquals(1,stored(s).path("themeRuntime").path("zeroHp").path("used").asInt());theme(s);assertEquals(1,stored(s).path("themeRuntime").path("zeroHp").path("used").asInt());assertOutcome(s,"FAIL",4,0);
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(target,"verifiedWeRunService",original);}
    }
    @Test void r5HttpWaitReentryKeepsServerDeadlineAndRestoreIsOnce() throws Exception {
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(com.chengyinhub.business.service.ITopicRouteRuntimeService.class));Object original=org.springframework.test.util.ReflectionTestUtils.getField(target,"clock");
        try {
            Lifecycle s=lifecycle(r5("qa"),"WAIT","sys.hp");act(s,"SUBMIT_QA","wrong",json.createObjectNode().put("input","wrong"));assertOutcome(s,"FAIL",0,0);JsonNode zero=theme(s).path("zeroHp");long deadline=zero.path("deadlineAt").asLong();assertTrue(deadline>System.currentTimeMillis());assertEquals(deadline,theme(s).path("zeroHp").path("deadlineAt").asLong());success(route(s,"ZERO_HP_SYNC","early"));assertEquals(0,stored(s).path("variables").path("sys.hp").asInt());
            // Existing service clock seam avoids sleeping; episode/deadline/state remain in real MySQL.
            org.springframework.test.util.ReflectionTestUtils.setField(target,"clock",java.time.Clock.fixed(java.time.Instant.ofEpochMilli(deadline),java.time.ZoneId.of("Asia/Shanghai")));
            ObjectNode body=json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId","wait-restore").put("operation","ZERO_HP_SYNC");JsonNode restored=success(post("/api/play/theme/gameplay-action",body,s.headers));assertEquals(restored,success(post("/api/play/theme/gameplay-action",body,s.headers)));assertEquals(4,stored(s).path("variables").path("sys.hp").asInt());assertEquals(1,stored(s).path("themeRuntime").path("zeroHp").path("used").asInt());theme(s);assertEquals(1,stored(s).path("themeRuntime").path("zeroHp").path("used").asInt());assertOutcome(s,"FAIL",4,0);
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(target,"clock",original);}
    }

    @Test void r5HttpIndependentVoteAdoptsTeamWorldAndPlayerScopeRefusesWithReason() throws Exception {
        ObjectNode c=r5("qa");ObjectNode tools=c.putObject("interactionTools").put("votePolicy","MAJORITY");tools.putArray("plans").addObject().put("planKey","continue").put("title","继续前行").putArray("materialCost").addObject().put("itemId","stock").put("quantity",1);c.with("interactionRules").withArray("resourceSlots").addObject().put("key","stock").put("kind","ITEM").put("label","投入物资").put("required",true);
        Lifecycle s=lifecycle(c,"BLOCK_HP_COST","counter.energy","TEAM",true);JsonNode frozen=persistedTopic(s.topicId,"vote-team-world");assertEquals("TEAM",frozen.path("published").path("gameplayRuntime").path("runtimeScope").asText());assertEquals("WORLD_SHARED",frozen.path("published").path("gameplayRuntime").path("worldMode").asText());assertTrue(frozen.path("published").path("gameplayRuntime").path("plans").size()>0,frozen.toString());assertEquals("paper",frozen.path("published").path("gameplayRuntime").path("plans").path(0).path("materialCost").path(0).path("itemId").asText());
        JsonNode read=success(form("/api/topic/edit-detail",s.topicId,s.headers));assertTrue(read.toString().contains("MAJORITY"));
        ObjectNode topic=topicRequest("PLAYER投票拒绝");long topicId=success(post("/api/topic/v2/create",topic,s.headers)).path("topicId").asLong();topic.put("id",topicId);refreshChapterIds(topic,topicId);topic.put("journeyRules","{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":[{\"key\":\"counter.energy\",\"label\":\"能量\",\"initial\":6,\"min\":0,\"max\":12}]}");ObjectNode reg=json.createObjectNode().put("topicId",topicId);reg.putArray("keys").addObject().put("stateKey","counter.energy").put("keyType","counter").put("scope","run").put("label","能量").put("initialValue",6);success(post("/api/topic/state-keys",reg,s.headers));ObjectNode ch=(ObjectNode)topic.path("chapters").path(0);ch.withArray("nodes").addObject().put("clientNodeKey","vote-player").put("name","投票站").put("templateId",s.templateId).put("nodeTime",0).put("nodeMoney",0).put("latitude","31.2304").put("longitude","121.4737").put("address","本地位置");ch.withArray("blocks").addObject().put("type","node").put("nodeIndex",0);ObjectNode theme=json.createObjectNode().put("schemaVersion",1).put("capacity",6);theme.putObject("gameplayRuntime").put("runtimeScope","PLAYER");ObjectNode b=theme.putObject("templateBindings").putObject("client:vote-player").put("templateId",s.templateId).put("slotDigest",GameplayTemplateRules.slotDigest(c.path("interactionRules").path("resourceSlots")));b.putObject("values").put("vital","counter.energy");b.putObject("items").put("stock","paper");theme.putArray("items").addObject().put("id","paper").put("kind","ITEM").put("name","纸张").put("image","https://static.chengyinhub.com/food.png").put("slotCost",1);topic.put("themeRuntimeConfig",theme.toString());JsonNode refusal=post("/api/topic/v2/update",topic,s.headers);rejected(refusal);assertTrue(refusal.path("msg").asText().contains("投票")||refusal.path("msg").asText().contains("公共世界"),refusal.toString());
    }
    @Test void legacyHttpFrozenScanSessionIgnoresLaterR5TemplateEdit() throws Exception {
        ObjectNode legacy=json.createObjectNode().put("schemaVersion",1);legacy.putObject("interactionRules").put("limit",2).putArray("resourceSlots").addObject().put("key","vital").put("kind","VALUE").put("label","能量").put("required",true);legacy.putArray("effects").addObject().put("var","vital").put("op","INC").put("value",1);
        Lifecycle s=lifecycle(legacy,"BLOCK_HP_COST");String frozen=database.queryForObject("SELECT config_snapshot_json FROM topic_route_session WHERE id=?",String.class,s.routeId);assertFalse(frozen.contains("outcomeRules"));
        success(post("/api/template/draft",json.createObjectNode().put("id",s.templateId).put("title","旧模板改为R5").put("validationMethod",4).put("isSync",0).put("advancedConfigJson",r5("qa").toString()),s.headers));assertEquals(frozen,database.queryForObject("SELECT config_snapshot_json FROM topic_route_session WHERE id=?",String.class,s.routeId));
        JsonNode old=theme(s);assertFalse(old.toString().contains("gameplayDecision"));ObjectNode body=json.createObjectNode().put("sessionId",s.routeId).put("expectedVersion",theme(s).path("version").asInt()).put("actionId","old-confirm").put("operation","QUEST_RESOLVE").put("contentId","node_"+s.nodeId);JsonNode done=success(post("/api/play/theme/gameplay-action",body,s.headers));assertEquals(done,success(post("/api/play/theme/gameplay-action",body,s.headers)));assertEquals(7,stored(s).path("variables").path(s.valueKey).asInt());assertFalse(stored(s).path("themeRuntime").has("lastOutcome"));assertEquals(frozen,database.queryForObject("SELECT config_snapshot_json FROM topic_route_session WHERE id=?",String.class,s.routeId));success(form("/api/template/myinfo",s.templateId,s.headers));theme(s);
    }

    private JsonNode gameCommand(long activityId,String action,String key,ObjectNode payload,org.springframework.http.HttpHeaders headers) {
        JsonNode view=success(get("/api/game/session/view?activityId="+activityId+"&perspective=CLUB",headers));
        ObjectNode body=json.createObjectNode().put("activityId",activityId).put("expectedRevision",view.path("revision").asInt()).put("requestId",key).put("action",action);
        if(view.path("authorityEpoch").isInt())body.set("authorityEpoch",view.path("authorityEpoch"));body.set("payload",payload);return success(post("/api/game/session/command",body,headers));
    }
    private Lifecycle teamLifecycle(ObjectNode config) throws Exception {
        Lifecycle s=lifecycle(config,"BLOCK_HP_COST","counter.energy","TEAM",true);
        long club=success(post("/api/club/apply",json.createObjectNode().put("name","LC真实本地主理团").put("leaderName","LC主理人"),s.headers)).path("clubId").asLong();assertTrue(club>0);
        // Existing local activity/paid/scope fixtures; topic publication and all runtime state/actions use HTTP.
        database.update("INSERT INTO cms_activity(member_id,topic_id,club_id,name,status,publish_status,del_flag,start_date,end_date) VALUES(?,?,?,'LC隔离场次',1,1,0,?,?)",s.memberId,s.topicId,club,new java.sql.Timestamp(System.currentTimeMillis()-3600000),new java.sql.Timestamp(System.currentTimeMillis()+3600000));
        long activity=database.queryForObject("SELECT id FROM cms_activity WHERE topic_id=? AND name='LC隔离场次' ORDER BY id DESC LIMIT 1",Long.class,s.topicId);
        database.update("INSERT INTO coop_order(topic_id,from_id,to_id,to_type,status,del_flag,create_time) VALUES(?,?,?,'club',0,0,NOW())",s.topicId,s.memberId,club);
        String phone="139"+String.format("%08d",transcript.size());org.springframework.http.HttpHeaders partner=loginHeaders(phone,"LC队员");long member=database.queryForObject("SELECT id FROM ums_member WHERE phone=?",Long.class,phone);
        for(long player:new long[]{s.memberId,member})database.update("INSERT INTO cms_registration(registration_no,member_id,owner_type,owner_id,topic_id,registration_status,payment_status,purchase_kind,original_amount,total_amount,payable_amount,pay_expire_time,create_time) VALUES(?,?,2,?,?,2,2,0,0,0,0,DATE_ADD(NOW(),INTERVAL 1 DAY),NOW())","lc-team-"+activity+"-"+player,player,activity,s.topicId);
        JsonNode team=success(post("/api/team/create",json.createObjectNode().put("ownerType",2).put("ownerId",activity).put("maxMembers",4).put("joinMode",1),s.headers));long teamId=team.path("teamId").asLong();assertTrue(teamId>0,team.toString());
        success(post("/api/team/join",json.createObjectNode().put("inviteCode",team.path("team").path("inviteCode").asText()),partner));
        gameCommand(activity,"PREPARE","prepare",json.createObjectNode(),s.headers);gameCommand(activity,"START","start",json.createObjectNode(),s.headers);
        JsonNode arrival=gameCommand(activity,"ARRIVAL_ISSUE","arrival-issue",json.createObjectNode().put("questKey","node_"+s.nodeId),s.headers);
        String nonce=arrival.path("result").path("nonce").asText();assertFalse(nonce.isEmpty(),arrival.toString());
        gameCommand(activity,"ARRIVAL_CONFIRM","arrival-confirm",json.createObjectNode().put("teamId",teamId).put("questKey","node_"+s.nodeId).put("nonce",nonce),s.headers);
        if(config.path("interactionTools").path("plans").isEmpty())gameCommand(activity,"QUEST_START","quest",json.createObjectNode().put("questKey","node_"+s.nodeId),s.headers);
        s.activityId=activity;s.teamId=teamId;s.partner=partner;return s;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"easy","medium","hard"})
    void r5HttpTeamCheckRerollIsCaptainOnlyAndSharedSettlementIsIdempotent(String tier) throws Exception {
        ObjectNode config=r5("check");config.with("check").put("tier",tier);Lifecycle s=teamLifecycle(config);long activity=s.activityId,teamId=s.teamId;org.springframework.http.HttpHeaders partner=s.partner;
        JsonNode opened=success(post("/api/play/advanced/start",json.createObjectNode().put("topicId",s.topicId).put("nodeId",s.nodeId).put("activityId",activity).put("teamId",teamId),s.headers));s.advancedId=opened.path("sessionId").asLong();assertEquals(2,opened.path("ownerType").asInt(),opened.toString());
        act(s,"ROLL_CHECK","roll",json.createObjectNode());JsonNode captain=advanced(s);JsonNode other=success(get("/api/play/advanced/state?sessionId="+s.advancedId,partner));com.chengyinhub.business.domain.JourneyRules.Tiers tiers=new com.chengyinhub.business.domain.JourneyRules().getTiers();assertEquals(("easy".equals(tier)?tiers.getEasy():"medium".equals(tier)?tiers.getMedium():tiers.getHard()).getDc(),captain.path("check").path("dc").asInt());assertEquals(tier,captain.path("check").path("tier").asText());assertTrue(captain.path("check").path("allowedActions").toString().contains("REROLL_CHECK"));assertFalse(other.path("check").path("allowedActions").toString().contains("REROLL_CHECK"));
        rejected(post("/api/play/advanced/action",actionBody(s,"REROLL_CHECK","member-reroll",json.createObjectNode()),partner));act(s,"REROLL_CHECK","captain-reroll",json.createObjectNode());ObjectNode submit=actionBody(s,"SUBMIT_CHECK","submit",json.createObjectNode());JsonNode outcome=success(post("/api/play/advanced/action",submit,s.headers));assertEquals(outcome,success(post("/api/play/advanced/action",submit,s.headers)));
        boolean passed=outcome.path("check").path("success").asBoolean();
        if(passed){JsonNode view=success(get("/api/game/session/view?activityId="+activity+"&perspective=CLUB",s.headers));ObjectNode settle=json.createObjectNode().put("activityId",activity).put("expectedRevision",view.path("revision").asInt()).put("authorityEpoch",view.path("authorityEpoch").asInt()).put("requestId","settle").put("action","QUEST_RESOLVE");settle.putObject("payload").put("questKey","node_"+s.nodeId);JsonNode done=success(post("/api/game/session/command",settle,s.headers));JsonNode repeat=success(post("/api/game/session/command",settle,s.headers));assertEquals(done.path("result"),repeat.path("result"));assertTrue(repeat.path("replayed").asBoolean());}
        JsonNode player=success(get("/api/game/session/view?activityId="+activity+"&perspective=PLAYER",s.headers)).path("player");assertEquals(passed?"PASS":"FAIL",player.path("lastOutcome").path("outcome").asText(),player.toString());
        assertFalse(player.has("resultCard"),player.toString());
        JsonNode usage=null;for(JsonNode tool:player.path("interactionTools"))if(tool.path("nodeId").asLong()==s.nodeId)usage=tool;assertNotNull(usage,player.toString());assertEquals(passed?1:0,usage.path("count").asInt());assertEquals(passed?1:2,usage.path("remainingCount").asInt());
        JsonNode owner=json.readTree(database.queryForObject("SELECT variables_json FROM game_story_state WHERE game_session_id=(SELECT id FROM game_session WHERE activity_id=?) AND owner_type='TEAM' AND owner_id=?",String.class,activity,teamId));assertEquals(passed?5:0,owner.path("variables").path(s.valueKey).asInt());assertEquals(passed?1:0,owner.path("themeRuntime").path("cooperation").path("facilities").path("tpl_node_"+s.nodeId).path("count").asInt());success(form("/api/topic/edit-detail",s.topicId,s.headers));success(form("/api/template/myinfo",s.templateId,s.headers));
    }

    @Test void r5HttpTeamVoteStakeExecutesOnceAndReopensPublishedTemplate() throws Exception {
        ObjectNode c=r5("qa");c.putObject("interactionTools").put("votePolicy","MAJORITY").putArray("plans").addObject().put("planKey","continue").put("title","继续前行").putArray("materialCost").addObject().put("itemId","stock").put("quantity",1);
        c.with("interactionRules").withArray("resourceSlots").addObject().put("key","stock").put("kind","ITEM").put("label","投入物资").put("required",true);
        Lifecycle s=teamLifecycle(c);long activity=s.activityId;
        JsonNode frozen=persistedTopic(s.topicId,"vote-runtime-frozen");String plan=frozen.path("published").path("gameplayRuntime").path("plans").path(0).path("endingKey").asText();assertFalse(plan.isEmpty());
        gameCommand(activity,"PLAN_PROPOSE","propose",json.createObjectNode().put("proposalKey","lc_proposal").put("endingKey",plan),s.headers);
        ObjectNode intent=json.createObjectNode().put("proposalKey","lc_proposal").put("version",1);intent.putArray("amounts").addObject().put("itemId","paper").put("quantity",1);gameCommand(activity,"PLAN_INTENT","stake",intent,s.headers);
        gameCommand(activity,"VOTE_OPEN","open-vote",json.createObjectNode(),s.headers);
        JsonNode world=json.readTree(database.queryForObject("SELECT variables_json FROM game_story_state WHERE game_session_id=(SELECT id FROM game_session WHERE activity_id=?) AND owner_type='WORLD'",String.class,activity));int round=world.path("themeRuntime").path("cooperation").path("round").asInt();assertTrue(round>0,world.toString());
        ObjectNode selected=json.createObjectNode().put("round",round).put("proposalKey","lc_proposal");gameCommand(activity,"VOTE_CAST","vote",selected,s.headers);
        gameCommand(activity,"OPERATION_START","vote-op",json.createObjectNode().put("operationKind","PLAN").put("operationKey",plan),s.headers);
        JsonNode opened=success(post("/api/play/advanced/start",json.createObjectNode().put("topicId",s.topicId).put("nodeId",s.nodeId).put("activityId",activity).put("teamId",s.teamId),s.headers));s.advancedId=opened.path("sessionId").asLong();act(s,"SUBMIT_QA","vote-proof",json.createObjectNode().put("input","correct"));
        gameCommand(activity,"PLAN_CONFIRM","confirm-stake",selected,s.headers);
        JsonNode view=success(get("/api/game/session/view?activityId="+activity+"&perspective=CLUB",s.headers));ObjectNode execute=json.createObjectNode().put("activityId",activity).put("expectedRevision",view.path("revision").asInt()).put("authorityEpoch",view.path("authorityEpoch").asInt()).put("requestId","execute").put("action","PLAN_EXECUTE");execute.set("payload",selected);
        JsonNode first=success(post("/api/game/session/command",execute,s.headers)),repeat=success(post("/api/game/session/command",execute,s.headers));assertEquals(first.path("result"),repeat.path("result"));assertTrue(repeat.path("replayed").asBoolean());
        JsonNode owner=json.readTree(database.queryForObject("SELECT variables_json FROM game_story_state WHERE game_session_id=(SELECT id FROM game_session WHERE activity_id=?) AND owner_type='TEAM' AND owner_id=?",String.class,activity,s.teamId));assertEquals(1,owner.path("themeRuntime").path("inventory").path("paper").asInt(),owner.toString());assertEquals(5,owner.path("variables").path(s.valueKey).asInt());assertEquals("PASS",owner.path("themeRuntime").path("lastOutcome").path("outcome").asText());assertEquals(1,owner.path("themeRuntime").path("cooperation").path("facilities").path("plan:"+s.nodeId).path("count").asInt(),owner.toString());
        JsonNode reopened=success(get("/api/game/session/view?activityId="+activity+"&perspective=PLAYER",s.headers));assertEquals("PASS",reopened.path("player").path("lastOutcome").path("outcome").asText(),reopened.toString());assertFalse(reopened.path("player").has("resultCard"),reopened.toString());JsonNode usage=null;for(JsonNode tool:reopened.path("player").path("interactionTools"))if(plan.equals(tool.path("endingKey").asText()))usage=tool;assertNotNull(usage,reopened.toString());assertEquals(1,usage.path("count").asInt());assertEquals(0,usage.path("remainingCount").asInt());success(form("/api/topic/edit-detail",s.topicId,s.headers));success(form("/api/template/myinfo",s.templateId,s.headers));
    }

    private void assertFreshTemplateCopyIsGated(long topicId,org.springframework.http.HttpHeaders headers) throws Exception {
        String before=database.queryForObject("SELECT theme_runtime_config_json FROM cms_topic WHERE id=?",String.class,topicId);
        success(form("/api/template/topic-template/save-as",topicId,headers));persistedTopic(topicId,"marked-as-template");
        assertEquals(1,database.queryForObject("SELECT is_template FROM cms_topic WHERE id=?",Integer.class,topicId));
        assertEquals(0,database.queryForObject("SELECT COUNT(*) FROM cms_topic_template_meta WHERE topic_id=? AND template_status='VERIFIED' AND del_flag=0",Integer.class,topicId));
        assertEquals(before,database.queryForObject("SELECT theme_runtime_config_json FROM cms_topic WHERE id=?",String.class,topicId));
        int rows=database.queryForObject("SELECT COUNT(*) FROM cms_topic",Integer.class);
        JsonNode result=form("/api/template/topic-template/use",topicId,headers);
        rejected(result);assertEquals("该主题不是可用模板",result.path("msg").asText());
        assertEquals(rows,database.queryForObject("SELECT COUNT(*) FROM cms_topic",Integer.class));
    }
    private ObjectNode topicRequest(String name) {
        ObjectNode request=json.createObjectNode().put("name",name).put("saveDraft",1).put("publishMode","simple").put("productType",1).put("routeMode","LINEAR");
        ObjectNode chapter=request.putArray("chapters").addObject().put("name","第一章").put("schemaVersion",1);
        chapter.putArray("nodes");chapter.putArray("blocks").addObject().put("type","text").put("content","本地作者流程验证");return request;
    }
    private void refreshChapterIds(ObjectNode request,long topicId) {
        java.util.List<Long> ids=database.queryForList("SELECT id FROM cms_topic_chapter WHERE topic_id=? ORDER BY sort_id,id",Long.class,topicId);
        for(int index=0;index<ids.size();index++)((ObjectNode)request.path("chapters").path(index)).put("id",ids.get(index));
        request.put("configVersion",database.queryForObject("SELECT config_version FROM cms_topic WHERE id=?",Long.class,topicId));
    }
    private org.springframework.http.HttpHeaders loginHeaders(String phone,String nickname) {
        UmsMember member=new UmsMember();member.setPhone(phone);member.setNickname(nickname);member.setStatus(1L);member.setDelFlag(0);members.insertUmsMember(member);
        context.getBean(com.chengyinhub.business.service.ICapabilityService.class).grant(member.getId(),"club_leader",member.getId());
        MockHttpServletRequest request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");request.addHeader("User-Agent","Local HTTP E2E");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        org.springframework.http.HttpHeaders headers=new org.springframework.http.HttpHeaders();
        try {headers.setBearerAuth(login.loginByMember(member,"phone",phone,null));}finally {RequestContextHolder.resetRequestAttributes();}
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);return headers;
    }
    private JsonNode post(String path,JsonNode body,org.springframework.http.HttpHeaders headers) {
        ResponseEntity<JsonNode> response=http.exchange(path,org.springframework.http.HttpMethod.POST,new org.springframework.http.HttpEntity<>(body,headers),JsonNode.class);
        record("POST",path,body,response);return response.getBody();
    }
    private JsonNode form(String path,long id,org.springframework.http.HttpHeaders original) {
        org.springframework.http.HttpHeaders headers=new org.springframework.http.HttpHeaders();headers.putAll(original);headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
        org.springframework.util.LinkedMultiValueMap<String,String> body=new org.springframework.util.LinkedMultiValueMap<>();body.add("id",String.valueOf(id));
        ResponseEntity<JsonNode> response=http.exchange(path,org.springframework.http.HttpMethod.POST,new org.springframework.http.HttpEntity<>(body,headers),JsonNode.class);
        record("POST",path,json.createObjectNode().put("id",id),response);return response.getBody();
    }
    private JsonNode success(JsonNode response) {assertEquals(200,response.path("code").asInt(),response.toString());return response.path("data");}
    private void rejected(JsonNode response) {assertNotEquals(200,response.path("code").asInt(),response.toString());}
    private JsonNode persistedTopic(long id,String phase) throws Exception {
        java.util.Map<String,Object> row=database.queryForMap("SELECT id,member_id,is_template,journey_rules_json,journey_story_json,theme_runtime_config_json,published_theme_runtime_config_json,published_route_graph_json FROM cms_topic WHERE id=?",id);
        ObjectNode values=json.createObjectNode().put("topicId",id).put("phase",phase).put("memberId",((Number)row.get("member_id")).longValue());
        values.set("isTemplate",json.valueToTree(row.get("is_template")));
        for(String[] field:new String[][]{{"journey_rules_json","rules"},{"journey_story_json","story"},{"theme_runtime_config_json","theme"},{"published_theme_runtime_config_json","published"},{"published_route_graph_json","snapshot"}})
            if(row.get(field[0])!=null)values.set(field[1],json.readTree(row.get(field[0]).toString()));
        transcript.addObject().put("method","DB_READBACK").put("path","cms_topic:"+id).put("httpStatus",0).set("response",values);return values;
    }
    static JsonNode redact(JsonNode node) {
        if(node==null)return null;JsonNode copy=node.deepCopy();
        if(copy.isObject()){ObjectNode object=(ObjectNode)copy;object.fields().forEachRemaining(e->{if(java.util.Arrays.asList("invitecode","nonce","token","quotesign","authorization","password").contains(e.getKey().toLowerCase(java.util.Locale.ROOT)))object.put(e.getKey(),"[REDACTED]");else object.set(e.getKey(),redact(e.getValue()));});}
        else if(copy.isArray())for(int i=0;i<copy.size();i++)((ArrayNode)copy).set(i,redact(copy.get(i)));return copy;
    }
    private void record(String method,String path,JsonNode request,ResponseEntity<JsonNode> response) {
        com.fasterxml.jackson.databind.node.ObjectNode row = transcript.addObject().put("method",method).put("path",path)
                .put("authorization","[REDACTED]").put("httpStatus",response.getStatusCode().value());
        if(request!=null){JsonNode safe=request.deepCopy();if(safe.path("weRun").isObject()){ObjectNode weRun=(ObjectNode)safe.path("weRun");weRun.remove(java.util.Arrays.asList("code","encryptedData","iv"));weRun.put("fixture","R5WeRunFixture: local AES and mocked WeChat exchange");}row.set("request",redact(safe));}
        row.set("response",redact(response.getBody()));
    }
}

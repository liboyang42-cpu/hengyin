package com.chengyinhub.business.it;

import com.chengyinhub.RuoYiApplication;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.support.GameplayTemplateRules;
import com.chengyinhub.framework.web.service.AppLoginService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 《末日寒冬 · 零下72小时》v0.2 七幕完整链路：作者端真实 HTTP 建模板、建主题、采用/绑定、发布冻结，
 * 再以三支双人队在同一共享世界里按原稿五条资源路径（S01–S05）与终局方案逐幕运行。
 * 所有玩法通过都走玩家可调用的 HTTP 动作（到达核验、开始任务、提交题组、结算），不调用内部可信结果方法。
 * 本地夹具只建场次/报名/俱乐部关联（与 GenericGameplayHttpE2eIT 相同的既有本地夹具口径）。
 */
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(classes = RuoYiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = GenericGameplayHttpE2eIT.LocalInitializer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FreezeGameHttpE2eIT {
    @Autowired private TestRestTemplate http;
    @Autowired private ConfigurableApplicationContext context;
    @Autowired private UmsMemberMapper members;
    @Autowired private AppLoginService login;
    @Autowired private JdbcTemplate database;
    private final ObjectMapper json = new ObjectMapper();
    private final ArrayNode transcript = json.createArrayNode();
    private final ObjectNode evidence = json.createObjectNode();
    private static final String[] ITEMS = {"ration", "fuel", "power", "parts", "medical"};
    private static final String[] LETTERS = {"R", "F", "E", "P", "M"};
    private int phones = 0;

    // ---------- 作者端（一次）----------
    private HttpHeaders author; private long authorId;
    private long topicId, clubId;
    private final Map<String, Long> templateIds = new LinkedHashMap<>();
    private final Map<String, JsonNode> templateConfigs = new LinkedHashMap<>();
    private final Map<String, Long> nodes = new LinkedHashMap<>();
    private final List<Long> chapterIds = new ArrayList<>();
    private final Map<String, String> plans = new LinkedHashMap<>();
    private final Map<String, String> questKeys = new LinkedHashMap<>();

    @BeforeAll void loadExistingPermissionFixture() throws Exception {
        GenericGameplayHttpE2eIT.assertContentSecurityFixture(context);
        for (String file : new String[]{"sql/migration_p1_rbac_permission_defaults.sql", "sql/migration_m1_permission_full.sql", "sql/migration_p1_rbac_permission_defaults.sql"})
            for (String statement : ItSchema.splitStatements(new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8))) {
                String sql = statement.replaceAll("(?m)^\\s*--.*$", "").trim();
                if (sql.matches("(?is)^(INSERT INTO|UPDATE) user_permission\\b.*")) database.execute(sql);
            }
    }

    @AfterAll void writeEvidence() throws Exception {
        if (context != null) {
            ScheduledExecutorService executor = context.getBean("scheduledExecutorService", ScheduledExecutorService.class);
            executor.shutdown(); if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        }
        Files.createDirectories(Paths.get("target/http-e2e"));
        json.writerWithDefaultPrettyPrinter().writeValue(Paths.get("target/http-e2e/freeze-transcript.json").toFile(), transcript);
        json.writerWithDefaultPrettyPrinter().writeValue(Paths.get("target/http-e2e/freeze-evidence.json").toFile(), evidence);
    }

    @Test @Order(1) void authorBuildsSavesReopensAndPublishesTheSevenActGame() throws Exception {
        String phone = nextPhone(); author = loginHeaders(phone, "寒冬作者"); authorId = memberId(phone);
        // 1) 独立玩法模板（原玩法编辑器同一保存入口），每个模板单独可复用
        JsonNode fixtures = json.readTree(getClass().getClassLoader().getResourceAsStream("freeze-game/templates.json"));
        for (JsonNode row : fixtures) {
            ObjectNode config = resolveClues(row.path("config").deepCopy());
            ObjectNode request = json.createObjectNode().put("title", row.path("title").asText()).put("validationMethod", row.path("validationMethod").asInt())
                    .put("isSync", 0).put("advancedConfigJson", config.toString());
            if (row.has("story")) {
                // 与小程序保存一致：线索段落（标签=本模板线索名）只进 storyJson，不进到达即读的 storyText
                Set<String> clueNames = new HashSet<>(); config.path("clues").forEach(name -> clueNames.add(name.asText()));
                StringBuilder text = new StringBuilder();
                for (JsonNode beat : row.path("story")) if (!clueNames.contains(beat.path("tag").asText())) text.append(text.length() > 0 ? "\n" : "").append(beat.path("text").asText());
                request.put("storyJson", row.path("story").toString()).put("storyText", text.toString());
            }
            long id = success(post("/api/template/draft", request, author)).asLong(); assertTrue(id > 0);
            JsonNode reread = success(form("/api/template/myinfo", id, author));
            JsonNode stored = json.readTree(reread.path("advancedConfigJson").asText());
            assertEquals(config.path("interactionRules").path("resourceSlots"), stored.path("interactionRules").path("resourceSlots"), row.path("key").asText());
            templateIds.put(row.path("key").asText(), id); templateConfigs.put(row.path("key").asText(), stored);
        }
        // 2) 主题与七章（每章即一幕），站点采用上面的模板
        JsonNode layout = json.readTree(getClass().getClassLoader().getResourceAsStream("freeze-game/chapters.json"));
        ObjectNode topic = json.createObjectNode().put("name", "末日寒冬 · 零下72小时").put("saveDraft", 1).put("publishMode", "simple").put("productType", 1)
                .put("routeMode", "LINEAR").put("teamMode", 1).put("teamMaxMembers", 2);
        ArrayNode chapters = topic.putArray("chapters");
        ObjectNode bindings = json.createObjectNode();
        for (JsonNode chapter : layout) {
            ObjectNode row = chapters.addObject().put("name", chapter.path("name").asText()).put("schemaVersion", 1);
            ArrayNode nodeRows = row.putArray("nodes"); ArrayNode blocks = row.putArray("blocks");
            blocks.addObject().put("type", "text").put("content", chapter.path("name").asText());
            int index = 0;
            for (JsonNode node : chapter.path("nodes")) {
                String key = node.path(0).asText();
                nodeRows.addObject().put("clientNodeKey", key).put("name", node.path(1).asText()).put("templateId", templateIds.get(key))
                        .put("nodeTime", 0).put("nodeMoney", 0).put("latitude", "31.2304").put("longitude", "121.4594").put("address", "上海静安雕塑公园（候选）");
                blocks.addObject().put("type", "node").put("nodeIndex", index++);
                bindings.set("client:" + key, binding(key));
            }
        }
        topic.put("journeyRules", rules().toString());
        ObjectNode theme = themeBase(); theme.set("templateBindings", bindings);
        topic.put("themeRuntimeConfig", theme.toString());
        topicId = success(post("/api/topic/v2/create", topic, author)).path("topicId").asLong(); assertTrue(topicId > 0);
        // 3) 读回真实章节/站点 ID，再在章节设置里写完成要求、互斥、额外节点、首章初始物资与章末配给
        JsonNode read = success(form("/api/topic/edit-detail", topicId, author));
        topic.put("id", topicId).put("configVersion", read.path("topic").path("configVersion").asInt());
        for (int c = 0; c < layout.size(); c++) {
            JsonNode chapter = read.path("chapters").get(c); chapterIds.add(chapter.path("id").asLong());
            ((ObjectNode) chapters.get(c)).put("id", chapter.path("id").asLong());
            for (int n = 0; n < layout.get(c).path("nodes").size(); n++) {
                long id = chapter.path("cmsTopicNodeList").get(n).path("id").asLong();
                nodes.put(layout.get(c).path("nodes").get(n).path(0).asText(), id);
                ((ObjectNode) chapters.get(c).path("nodes").get(n)).put("id", id).remove("clientNodeKey");
            }
        }
        theme = (ObjectNode) json.readTree(read.path("themeRuntimeConfig").asText());
        assertFalse(theme.toString().contains("client:"), "回读不残留临时站点键");
        ArrayNode settings = theme.putArray("chapters");
        for (int c = 0; c < 7; c++) settings.addObject().put("index", c).put("hpCost", 0).put("luckCost", 0);
        ArrayNode grants = ((ObjectNode) settings.get(0)).putArray("grants");
        for (String item : new String[]{"ration", "fuel", "power"}) grants.addObject().put("itemId", item).put("quantity", 1).put("timing", "START");
        ArrayNode extra0 = ((ObjectNode) settings.get(0)).putArray("extraNodes");
        extra0.addObject().put("nodeId", nodes.get("rest")).put("slot", "rest"); extra0.addObject().put("nodeId", nodes.get("aid")).put("slot", "aid");
        ((ObjectNode) settings.get(2)).putObject("completion").put("type", "ANY_N").put("quantity", 2).put("overflowSlot", "mobile");
        ((ObjectNode) settings.get(3)).putArray("exclusiveGroups").addArray().add(nodes.get("rescue")).add(nodes.get("recover")).add(nodes.get("hold"));
        ((ObjectNode) settings.get(4)).putArray("extraNodes").addObject().put("nodeId", nodes.get("heat")).put("slot", "heat");
        ((ObjectNode) settings.get(5)).putArray("exclusiveGroups").addArray().add(nodes.get("a6p")).add(nodes.get("a6e"));
        ((ObjectNode) settings.get(5)).putArray("extraNodes").addObject().put("nodeId", nodes.get("a6m")).put("slot", "mobile");
        ((ObjectNode) settings.get(6)).putArray("exclusiveGroups").addArray().add(nodes.get("end1")).add(nodes.get("end2")).add(nodes.get("end3")).add(nodes.get("end4"));
        ObjectNode survival = theme.with("gameplayRuntime").with("survival");
        ArrayNode rations = survival.putArray("rations");
        rations.addObject().put("rationKey", "ration_a2").put("actKey", actKey(theme, nodes.get("a2"))).put("itemId", "ration").put("quantity", 1).put("fallbackHpCost", 2);
        rations.addObject().put("rationKey", "ration_a5").put("actKey", actKey(theme, nodes.get("a5"))).put("itemId", "ration").put("quantity", 1).put("fallbackHpCost", 2);
        topic.put("themeRuntimeConfig", theme.toString()).put("journeyRules", rules().toString());
        success(post("/api/topic/v2/update", topic, author));
        // 4) 重新打开：结构、绑定、配给、章节设置都已持久化（V2 保存会重建站点 ID，绑定/额外节点随之重映射）
        JsonNode reopened = success(form("/api/topic/edit-detail", topicId, author));
        chapterIds.clear();
        for (int c = 0; c < layout.size(); c++) {
            JsonNode chapter = reopened.path("chapters").get(c); chapterIds.add(chapter.path("id").asLong());
            for (int n = 0; n < layout.get(c).path("nodes").size(); n++) nodes.put(layout.get(c).path("nodes").get(n).path(0).asText(), chapter.path("cmsTopicNodeList").get(n).path("id").asLong());
        }
        evidence.with("authoring").put("idsRemappedOnSave", true);
        JsonNode saved = json.readTree(reopened.path("themeRuntimeConfig").asText());
        assertEquals(2, saved.path("gameplayRuntime").path("survival").path("rations").size(), saved.toString());
        assertEquals("ANY_N", saved.path("chapters").get(2).path("completion").path("type").asText());
        assertEquals(nodes.get("a6m").longValue(), saved.path("chapters").get(5).path("extraNodes").get(0).path("nodeId").asLong(), "额外节点随站点重映射");
        assertEquals(actKey(saved, nodes.get("a2")), saved.path("gameplayRuntime").path("survival").path("rations").get(0).path("actKey").asText(), "章末配给仍指向本章的幕");
        for (String key : nodes.keySet()) assertEquals(templateIds.get(key).longValue(), saved.path("templateBindings").path(String.valueOf(nodes.get(key))).path("templateId").asLong(), key);
        // 5) 发布冻结
        success(form("/api/topic/publish-draft", topicId, author));
        JsonNode frozen = persistedTopic(topicId, "published");
        JsonNode game = frozen.path("published").path("gameplayRuntime");
        assertEquals(7, game.path("acts").size(), game.toString());
        for (String end : new String[]{"end1", "end2", "end3", "end4"}) {
            JsonNode plan = null; for (JsonNode row : game.path("plans")) if (row.path("operationNodeId").asLong() == nodes.get(end)) plan = row;
            assertNotNull(plan, end); assertTrue(plan.path("terminal").asBoolean()); plans.put(end, plan.path("endingKey").asText());
            assertEquals(chapterIds.get(6).longValue(), plan.path("endingChapterId").asLong(), "未单独链接结局章的方案在最终章收场");
        }
        assertEquals("MAJORITY_ACCEPTANCE", game.path("votePolicy").asText(), "表决方式取主题设置");
        for (String end : new String[]{"end1", "end2", "end3", "end4"}) { JsonNode tools = templateConfigs.get(end).path("interactionTools"); assertFalse(tools.has("votePolicy") || tools.has("decidesEnding"), end + " 模板不写表决方式/收场"); }
        assertTrue(facility(game, nodes.get("heat")).has("arrivalLocationKey"), "取暖要求效果对象已到达");
        assertTrue(facility(game, nodes.get("a6e")).path("publicOnce").asBoolean()); assertTrue(facility(game, nodes.get("a6m")).path("publicOnce").asBoolean());
        int fallbacks = 0; for (JsonNode row : game.path("storyEndings")) if (row.path("fallback").asBoolean()) { fallbacks++; assertEquals("END05 沉默的城市", row.path("title").asText()); }
        assertEquals(1, fallbacks);
        for (String key : nodes.keySet()) questKeys.put(key, quest(game, nodes.get(key)).path("questKey").asText());
        JsonNode a3Quest = quest(game, nodes.get("a3f"));
        assertEquals(2, a3Quest.path("choices").size(), "任选奖励编译为本站配包"); assertEquals("三份口粮", a3Quest.path("choices").get(0).path("label").asText());
        assertTrue(quest(game, nodes.get("rest")).path("extraOnly").asBoolean());
        List<String> registered = database.queryForList("SELECT state_key FROM theme_state_key WHERE topic_id=?", String.class, topicId);
        assertTrue(registered.contains(clueKey("EV06 负载校验")), registered.toString());
        ObjectNode record = evidence.with("authoring");
        record.put("topicId", topicId); record.set("templateIds", json.valueToTree(templateIds)); record.set("nodeIds", json.valueToTree(nodes));
        record.set("chapterIds", json.valueToTree(chapterIds)); record.set("planKeys", json.valueToTree(plans));
        record.set("rations", saved.path("gameplayRuntime").path("survival").path("rations"));
        record.set("storyEndings", game.path("storyEndings"));
        clubId = success(post("/api/club/apply", json.createObjectNode().put("name", "寒冬测试主理团").put("leaderName", "寒冬主持"), author)).path("clubId").asLong();
        database.update("INSERT INTO coop_order(topic_id,from_id,to_id,to_type,status,del_flag,create_time) VALUES(?,?,?,'club',0,0,NOW())", topicId, authorId, clubId);
    }

    // ---------- 场次与队伍 ----------
    private final class Team {
        final String name; long id; HttpHeaders captain, member; long captainId, memberId; int peak;
        Team(String name) { this.name = name; }
    }
    private final class Run {
        final String label; long activityId; final List<Team> teams = new ArrayList<>(); int request;
        Run(String label) { this.label = label; }
        Team t(int i) { return teams.get(i); }
    }

    private Run open(String label) throws Exception {
        Run run = new Run(label);
        database.update("INSERT INTO cms_activity(member_id,topic_id,club_id,name,status,publish_status,del_flag,start_date,end_date) VALUES(?,?,?,?,1,1,0,?,?)",
                authorId, topicId, clubId, "寒冬 " + label, new java.sql.Timestamp(System.currentTimeMillis() - 3600000), new java.sql.Timestamp(System.currentTimeMillis() + 6 * 3600000));
        run.activityId = database.queryForObject("SELECT id FROM cms_activity WHERE topic_id=? AND name=? ORDER BY id DESC LIMIT 1", Long.class, topicId, "寒冬 " + label);
        for (String name : new String[]{"A", "B", "C"}) {
            Team team = new Team(name);
            String p1 = nextPhone(), p2 = nextPhone();
            team.captain = loginHeaders(p1, label + name + "队长"); team.member = loginHeaders(p2, label + name + "队员");
            team.captainId = memberId(p1); team.memberId = memberId(p2);
            for (long player : new long[]{team.captainId, team.memberId})
                database.update("INSERT INTO cms_registration(registration_no,member_id,owner_type,owner_id,topic_id,registration_status,payment_status,purchase_kind,original_amount,total_amount,payable_amount,pay_expire_time,create_time) VALUES(?,?,2,?,?,2,2,0,0,0,0,DATE_ADD(NOW(),INTERVAL 1 DAY),NOW())",
                        "fz-" + run.activityId + "-" + player, player, run.activityId, topicId);
            JsonNode created = success(post("/api/team/create", json.createObjectNode().put("ownerType", 2).put("ownerId", run.activityId).put("maxMembers", 2).put("joinMode", 1), team.captain));
            team.id = created.path("teamId").asLong();
            success(post("/api/team/join", json.createObjectNode().put("inviteCode", created.path("team").path("inviteCode").asText()), team.member));
            run.teams.add(team);
        }
        staff(run, "PREPARE", json.createObjectNode()); staff(run, "START", json.createObjectNode());
        for (Team team : run.teams) assertEquals(8, player(run, team).path("hp").asInt(), "初始保温储备 H8");
        return run;
    }

    // ---------- 一幕一题的真实玩家动作 ----------
    private JsonNode doQuest(Run run, Team team, String key, Integer choice, Integer costOption) throws Exception {
        String quest = questKey(key);
        arrive(run, team, json.createObjectNode().put("questKey", quest));
        ObjectNode start = json.createObjectNode().put("questKey", quest); if (costOption != null) start.put("costOption", costOption);
        success(cmd(run, team.captain, "QUEST_START", start));
        solve(run, team, key);
        ObjectNode resolve = json.createObjectNode().put("questKey", quest); if (choice != null) resolve.put("choiceKey", "opt_" + choice);
        JsonNode receipt = success(cmd(run, team.captain, "QUEST_RESOLVE", resolve));
        track(run, team);
        return receipt;
    }
    private JsonNode useFacility(Run run, Team team, String key) throws Exception {
        String facility = "tpl_node_" + nodes.get(key);
        ObjectNode op = json.createObjectNode().put("operationKind", "FACILITY").put("operationKey", facility);
        success(cmd(run, team.captain, "OPERATION_START", op));
        arrive(run, team, op.deepCopy());
        solve(run, team, key);
        JsonNode receipt = success(cmd(run, team.captain, "FACILITY_USE", json.createObjectNode().put("facilityKey", facility)));
        track(run, team);
        return receipt;
    }
    private void arrive(Run run, Team team, ObjectNode target) {
        JsonNode issued = success(cmd(run, team.captain, "ARRIVAL_ISSUE", target.deepCopy()));
        ObjectNode confirm = target.deepCopy().put("teamId", team.id).put("nonce", issued.path("result").path("nonce").asText());
        success(cmd(run, author, "ARRIVAL_CONFIRM", confirm));
    }
    private long advancedSession(Run run, Team team, String key) {
        JsonNode opened = success(post("/api/play/advanced/start", json.createObjectNode().put("topicId", topicId).put("nodeId", nodes.get(key))
                .put("activityId", run.activityId).put("teamId", team.id), team.captain));
        return opened.path("sessionId").asLong();
    }
    private long solve(Run run, Team team, String key) {
        long session = advancedSession(run, team, key);
        for (ObjectNode[] step : answers(key)) {
            JsonNode state = success(get("/api/play/advanced/state?sessionId=" + session, team.captain));
            ObjectNode body = json.createObjectNode().put("sessionId", session).put("version", state.path("version").asInt())
                    .put("idempotencyKey", run.activityId + "-" + run.label + "-" + team.name + "-" + key + "-" + (++run.request)).put("action", step[0].path("action").asText());
            body.set("payload", step[1]);
            success(post("/api/play/advanced/action", body, team.captain));
        }
        return session;
    }
    private List<ObjectNode[]> answers(String key) {
        List<ObjectNode[]> out = new ArrayList<>();
        JsonNode config = templateConfigs.get(key);
        if (config.path("sort").path("enabled").asBoolean()) out.add(step("SUBMIT_SORT", json.createObjectNode().set("order", config.path("sort").path("answerOrder"))));
        if (config.path("match").path("enabled").asBoolean()) out.add(step("SUBMIT_MATCH", json.createObjectNode().set("pairs", config.path("match").path("pairs"))));
        if (config.path("classify").path("enabled").asBoolean()) out.add(step("SUBMIT_CLASSIFY", json.createObjectNode().set("placement", config.path("classify").path("answer"))));
        if (config.path("qa").path("enabled").asBoolean()) {
            JsonNode qa = config.path("qa");
            if (qa.path("multi").asBoolean()) { ArrayNode ids = json.createArrayNode(); for (JsonNode o : qa.path("options")) if (o.path("correct").asBoolean()) ids.add(o.path("id").asText()); out.add(step("SUBMIT_QA", json.createObjectNode().set("optionIds", ids))); }
            else { String id = qa.path("options").get(0).path("id").asText(); for (JsonNode o : qa.path("options")) if (o.path("correct").asBoolean()) id = o.path("id").asText(); out.add(step("SUBMIT_QA", json.createObjectNode().put("optionId", id))); }
        }
        return out;
    }
    private ObjectNode[] step(String action, ObjectNode payload) { return new ObjectNode[]{json.createObjectNode().put("action", action), payload}; }

    // ---------- 共享世界：交换、提案、投入、表决、执行 ----------
    private void gift(Run run, Team from, Team to, String item, int quantity) {
        String trade = "gift_" + (++run.request);
        ObjectNode open = json.createObjectNode().put("tradeKey", trade).put("toTeamId", to.id);
        open.putArray("give").addObject().put("itemId", item).put("quantity", quantity); open.putArray("take");
        success(cmd(run, from.captain, "TRADE_OPEN", open));
        int version = tradeVersion(run, from, trade);
        for (Team side : new Team[]{from, to}) success(cmd(run, side.captain, "TRADE_CONFIRM", json.createObjectNode().put("tradeKey", trade).put("version", version)));
        track(run, from); track(run, to);
    }
    private int tradeVersion(Run run, Team team, String trade) {
        for (JsonNode row : player(run, team).path("world").path("trades")) if (trade.equals(row.path("tradeKey").asText())) return row.path("version").asInt();
        fail("交易未回读：" + trade); return -1;
    }
    private JsonNode execute(Run run, String end, Map<Team, Map<String, Integer>> funding, Team executor) throws Exception { return execute(run, end, funding, executor, null); }
    private JsonNode execute(Run run, String end, Map<Team, Map<String, Integer>> funding, Team executor, Runnable afterConfirm) throws Exception {
        String plan = plans.get(end), proposal = "p_" + end + "_" + (++run.request);
        success(cmd(run, executor.captain, "PLAN_PROPOSE", json.createObjectNode().put("proposalKey", proposal).put("endingKey", plan)));
        for (Map.Entry<Team, Map<String, Integer>> share : funding.entrySet()) {
            ObjectNode intent = json.createObjectNode().put("proposalKey", proposal).put("version", proposalVersion(run, share.getKey(), proposal));
            ArrayNode amounts = intent.putArray("amounts");
            share.getValue().forEach((item, quantity) -> { if (quantity > 0) amounts.addObject().put("itemId", item).put("quantity", quantity); });
            success(cmd(run, share.getKey().captain, "PLAN_INTENT", intent));
        }
        success(cmd(run, executor.captain, "VOTE_OPEN", json.createObjectNode()));
        int round = world(run).path("themeRuntime").path("cooperation").path("round").asInt();
        ObjectNode selected = json.createObjectNode().put("round", round).put("proposalKey", proposal);
        for (Team team : run.teams) success(cmd(run, team.captain, "VOTE_CAST", selected.deepCopy()));
        ObjectNode op = json.createObjectNode().put("operationKind", "PLAN").put("operationKey", plan);
        success(cmd(run, executor.captain, "OPERATION_START", op));
        arrive(run, executor, op.deepCopy());
        solve(run, executor, end);
        for (Team team : funding.keySet()) success(cmd(run, team.captain, "PLAN_CONFIRM", selected.deepCopy()));
        if (afterConfirm != null) afterConfirm.run();
        ObjectNode body = commandBody(run, executor.captain, "PLAN_EXECUTE", selected.deepCopy());
        JsonNode done = success(post("/api/game/session/command", body, executor.captain));
        JsonNode replay = success(post("/api/game/session/command", body, executor.captain));
        assertTrue(replay.path("replayed").asBoolean()); assertEquals(done.path("result"), replay.path("result"));
        rejected(cmd(run, executor.captain, "PLAN_EXECUTE", selected.deepCopy()));
        return done;
    }
    /** 取暖要求效果对象已到达：其他队未核验到达时不能结算；全部到达后一次结算、全场生效。 */
    private void heatTogether(Run run, Team user) throws Exception {
        // 取暖站首次使用经站点任务结算：开始→题组→结算；结算时效果对象（全场）须都已核验到达
        String quest = questKey("heat");
        arrive(run, user, json.createObjectNode().put("questKey", quest));
        success(cmd(run, user.captain, "QUEST_START", json.createObjectNode().put("questKey", quest)));
        solve(run, user, "heat");
        int[] hp = new int[3]; for (int i = 0; i < 3; i++) hp[i] = player(run, run.t(i)).path("hp").asInt();
        rejected(cmd(run, user.captain, "QUEST_RESOLVE", json.createObjectNode().put("questKey", quest)));
        for (int i = 0; i < 3; i++) assertEquals(hp[i], player(run, run.t(i)).path("hp").asInt(), "未全部到达时不结算");
        for (Team team : run.teams) if (team != user) arrive(run, team, json.createObjectNode().put("questKey", quest));
        success(cmd(run, user.captain, "QUEST_RESOLVE", json.createObjectNode().put("questKey", quest)));
        for (Team team : run.teams) track(run, team);
        evidence.with("negative").put("heatRequiresVerifiedArrivalOfRecipients", true);
    }
    /** 三案并列 → 重投仍并列 → 撤回再提 → 接受集合两案并列 → 共识一致 → 确认锁定 → 执行。 */
    private JsonNode tieThenConsensus(Run run, Map<Team, Map<String, Integer>> funding, Team executor) throws Exception {
        Team a = run.t(0), b = run.t(1), c = run.t(2);
        Map<String, String> proposal = new LinkedHashMap<>();
        for (String end : new String[]{"end4", "end1", "end3"}) {
            String key = "tie_" + end + "_" + (++run.request); proposal.put(end, key);
            success(cmd(run, executor.captain, "PLAN_PROPOSE", json.createObjectNode().put("proposalKey", key).put("endingKey", plans.get(end))));
            for (Map.Entry<Team, Map<String, Integer>> share : fund(run, need(end)).entrySet()) {
                ObjectNode intent = json.createObjectNode().put("proposalKey", key).put("version", proposalVersion(run, share.getKey(), key));
                ArrayNode amounts = intent.putArray("amounts"); share.getValue().forEach((item, q) -> amounts.addObject().put("itemId", item).put("quantity", q));
                success(cmd(run, share.getKey().captain, "PLAN_INTENT", intent));
            }
        }
        ObjectNode n = evidence.with("voting");
        // 第一轮：三队各投一案，无多数 → 并列
        success(cmd(run, executor.captain, "VOTE_OPEN", json.createObjectNode()));
        castSplit(run, proposal);
        assertEquals("TIED", stage(run), "三案各一票为并列");
        // 重投：仍各投一案，仍并列
        success(cmd(run, executor.captain, "VOTE_NEXT", json.createObjectNode()));
        castSplit(run, proposal);
        assertEquals("TIED", stage(run));
        n.put("tieThenRevoteStillTied", true);
        // 撤回：提案队撤回 END03 提案后票轮作废回到草稿，再提出
        String old = proposal.get("end3");
        success(cmd(run, executor.captain, "PLAN_WITHDRAW", json.createObjectNode().put("proposalKey", old).put("version", proposalVersion(run, executor, old))));
        assertEquals("DRAFT", stage(run), "撤回后票轮重置");
        n.put("withdrawResetsBallot", true);
        // 再次开票，三队仍在两案间各执一词 → 接受集合：两案都获全部接受 → 共识
        success(cmd(run, executor.captain, "VOTE_OPEN", json.createObjectNode()));
        int round = round(run);
        success(cmd(run, a.captain, "VOTE_CAST", json.createObjectNode().put("round", round).put("proposalKey", proposal.get("end4"))));
        success(cmd(run, b.captain, "VOTE_CAST", json.createObjectNode().put("round", round).put("proposalKey", proposal.get("end1"))));
        assertEquals("VOTING", stage(run), "尚有队伍未投");
        // 第三队弃票改走接受集合：用尚未投完的轮次直接提交接受集合须被拒
        ObjectNode early = json.createObjectNode().put("round", round); early.putArray("proposalKeys"); rejected(cmd(run, c.captain, "ACCEPT_SET", early));
        success(cmd(run, c.captain, "VOTE_CAST", json.createObjectNode().put("round", round).put("proposalKey", proposal.get("end1"))));
        // 2 票达到多数：END01 当选；改案解除后进入接受集合流程
        assertEquals("SELECTED", stage(run)); assertEquals(proposal.get("end1"), world(run).path("themeRuntime").path("cooperation").path("selected").asText());
        n.put("majoritySelects", true);
        success(cmd(run, executor.captain, "PLAN_CHANGE", json.createObjectNode()));
        // 再提一次 END03 让三案并列，再走接受集合
        String again = "tie_end3_" + (++run.request); proposal.put("end3", again);
        success(cmd(run, executor.captain, "PLAN_PROPOSE", json.createObjectNode().put("proposalKey", again).put("endingKey", plans.get("end3"))));
        for (Map.Entry<Team, Map<String, Integer>> share : fund(run, need("end3")).entrySet()) {
            ObjectNode intent = json.createObjectNode().put("proposalKey", again).put("version", proposalVersion(run, share.getKey(), again));
            ArrayNode amounts = intent.putArray("amounts"); share.getValue().forEach((item, q) -> amounts.addObject().put("itemId", item).put("quantity", q));
            success(cmd(run, share.getKey().captain, "PLAN_INTENT", intent));
        }
        success(cmd(run, executor.captain, "VOTE_OPEN", json.createObjectNode()));
        castSplit(run, proposal);
        assertEquals("TIED", stage(run));
        round = round(run);
        for (Team team : run.teams) {
            ObjectNode accept = json.createObjectNode().put("round", round); accept.putArray("proposalKeys").add(proposal.get("end4")).add(proposal.get("end1"));
            success(cmd(run, team.captain, "ACCEPT_SET", accept));
        }
        assertEquals("CONSENSUS", stage(run), "两案同样获全部接受 → 共识");
        JsonNode tied = world(run).path("themeRuntime").path("cooperation").path("tiedCandidates");
        assertEquals(2, tied.size(), tied.toString());
        // 共识须一致；不一致时不选出，改选后一致选出 END04
        success(cmd(run, a.captain, "CONSENSUS_CAST", json.createObjectNode().put("round", round).put("proposalKey", proposal.get("end4"))));
        success(cmd(run, b.captain, "CONSENSUS_CAST", json.createObjectNode().put("round", round).put("proposalKey", proposal.get("end1"))));
        success(cmd(run, c.captain, "CONSENSUS_CAST", json.createObjectNode().put("round", round).put("proposalKey", proposal.get("end4"))));
        assertEquals("CONSENSUS", stage(run), "共识不一致不选出");
        rejected(cmd(run, a.captain, "CONSENSUS_CAST", json.createObjectNode().put("round", round).put("proposalKey", proposal.get("end3"))));
        success(cmd(run, b.captain, "CONSENSUS_CAST", json.createObjectNode().put("round", round).put("proposalKey", proposal.get("end4"))));
        assertEquals("SELECTED", stage(run)); assertEquals(proposal.get("end4"), world(run).path("themeRuntime").path("cooperation").path("selected").asText());
        n.put("acceptanceSetTieToConsensus", true).put("consensusRequiresAll", true);
        // 确认与锁定：未当选案不能确认；出资队确认锁定后执行
        ObjectNode selected = json.createObjectNode().put("round", round).put("proposalKey", proposal.get("end4"));
        rejected(cmd(run, a.captain, "PLAN_CONFIRM", json.createObjectNode().put("round", round).put("proposalKey", proposal.get("end1"))));
        ObjectNode op = json.createObjectNode().put("operationKind", "PLAN").put("operationKey", plans.get("end4"));
        success(cmd(run, executor.captain, "OPERATION_START", op));
        arrive(run, executor, op.deepCopy());
        solve(run, executor, "end4");
        Set<Team> funders = new LinkedHashSet<>();
        JsonNode intents = world(run).path("themeRuntime").path("cooperation").path("proposals").path(proposal.get("end4")).path("intents");
        for (Team team : run.teams) if (intents.has(String.valueOf(team.id))) funders.add(team);
        Team first = funders.iterator().next();
        success(cmd(run, first.captain, "PLAN_CONFIRM", selected.deepCopy()));
        if (funders.size() > 1) rejected(cmd(run, executor.captain, "PLAN_EXECUTE", selected.deepCopy()));
        assertTrue(world(run).path("themeRuntime").path("cooperation").path("locks").has(String.valueOf(first.id)), "确认即锁定");
        for (Team team : funders) if (team != first) success(cmd(run, team.captain, "PLAN_CONFIRM", selected.deepCopy()));
        n.put("executeRequiresAllLocks", true);
        ObjectNode body = commandBody(run, executor.captain, "PLAN_EXECUTE", selected.deepCopy());
        JsonNode done = success(post("/api/game/session/command", body, executor.captain));
        JsonNode replay = success(post("/api/game/session/command", body, executor.captain));
        assertTrue(replay.path("replayed").asBoolean()); assertEquals(done.path("result"), replay.path("result"));
        return done;
    }
    private void castSplit(Run run, Map<String, String> proposal) {
        int round = round(run); String[] order = {"end4", "end1", "end3"};
        for (int i = 0; i < 3; i++) success(cmd(run, run.t(i).captain, "VOTE_CAST", json.createObjectNode().put("round", round).put("proposalKey", proposal.get(order[i]))));
    }
    private String stage(Run run) { return world(run).path("themeRuntime").path("cooperation").path("stage").asText(); }
    private int round(Run run) { return world(run).path("themeRuntime").path("cooperation").path("round").asInt(); }
    private JsonNode playerView(Run run, HttpHeaders who) {
        return success(get("/api/game/session/view?activityId=" + run.activityId + "&perspective=PLAYER", who)).path("player");
    }
    /** 指定范围的线索须带来源段落正文（含片段）且图片数量一致。 */
    private void assertClueText(JsonNode clues, String name, String scope, String fragment, int images) {
        for (JsonNode row : clues) if (name.equals(row.path("name").asText()) && scope.equals(row.path("scope").asText())) {
            for (JsonNode source : row.path("sources")) for (JsonNode block : source.path("blocks"))
                if (block.path("text").asText().contains(fragment)) { assertEquals(images, block.path("images").size(), block.toString()); return; }
            fail(name + "/" + scope + " 缺少正文「" + fragment + "」：" + row);
        }
        fail("缺少线索 " + name + "/" + scope + "：" + clues);
    }
    private boolean hasClue(JsonNode clues, String name, String scope) {
        for (JsonNode row : clues) if (name.equals(row.path("name").asText()) && scope.equals(row.path("scope").asText())) return true;
        return false;
    }
    private int proposalVersion(Run run, Team team, String proposal) {
        for (JsonNode row : player(run, team).path("world").path("proposals")) if (proposal.equals(row.path("proposalKey").asText())) return row.path("version").asInt();
        fail("提案未回读：" + proposal); return -1;
    }

    // ---------- 原稿 S01–S05 ----------
    @Test @Order(2) void s01CooperativeRescueReachesTheReportedLedgerAndExecutesEnd04() throws Exception {
        Run run = open("S01");
        Team a = run.t(0), b = run.t(1), c = run.t(2);
        start(run, new int[]{1, 2, 3});
        doQuest(run, a, "a3f", 1, null); eat(run, a); doQuest(run, a, "a3w", 1, null);
        doQuest(run, b, "a3f", 1, null); eat(run, b); doQuest(run, b, "a3w", 2, null);
        doQuest(run, c, "a3f", 1, null); eat(run, c); doQuest(run, c, "a3l", 1, null);
        gift(run, c, a, "medical", 1);
        doQuest(run, a, "rescue", null, 0);
        doQuest(run, b, "recover", null, null); doQuest(run, c, "recover", null, null);
        heatTogether(run, a);
        for (Team team : run.teams) doQuest(run, team, "a5", null, null);
        doQuest(run, a, "a6p", 1, null); doQuest(run, b, "a6e", null, null); doQuest(run, c, "a6p", 2, null);
        reconcile(run, "S01", new int[]{5, 8, 4, 7, 2}, new int[]{6, 6, 6}, new int[]{7, 7, 7}, new int[]{13, 14, 12});
        // 线索内容：救援队私有原表与留言（原件），全场只读 A5 镜像；其他队看不到私有证据
        for (HttpHeaders who : new HttpHeaders[]{a.captain, a.member}) {
            JsonNode clues = playerView(run, who).path("clues");
            assertClueText(clues, "EV03 人口原表", "TEAM", "原件", 1); assertClueText(clues, "EV07 许岚留言", "TEAM", "外区还有人活着", 1);
            assertClueText(clues, "EV03 人口原表", "PUBLIC", "镜像", 0);
        }
        for (Team other : new Team[]{b, c}) for (HttpHeaders who : new HttpHeaders[]{other.captain, other.member}) {
            JsonNode clues = playerView(run, who).path("clues");
            assertClueText(clues, "EV03 人口原表", "PUBLIC", "镜像", 0);
            assertFalse(clues.toString().contains("EV07") || clues.toString().contains("原件") || clues.toString().contains("外区还有人活着"), "其他队不可见救援队私有证据：" + clues);
        }
        evidence.with("scenarios").with("S01").set("clueContentTeamA", playerView(run, a.captain).path("clues"));
        evidence.with("scenarios").with("S01").set("clueContentTeamB", playerView(run, b.captain).path("clues"));
        Map<Team, Map<String, Integer>> funding = fund(run, new int[]{0, 6, 3, 6, 2});
        JsonNode result = tieThenConsensus(run, funding, a);
        assertTrue(result.path("result").path("result").path("terminal").asBoolean(), result.toString());
        finished(run, "END04 第二次日出", new int[]{5, 2, 1, 1, 0});
    }

    @Test @Order(3) void s02ConservativeEvacuationExecutesEnd01() throws Exception {
        Run run = s02Path("S02");
        reconcile(run, "S02", new int[]{9, 12, 3, 3, 0}, new int[]{5, 5, 5}, new int[]{7, 7, 7}, new int[]{13, 13, 13});
        assertPlanFeasibility(run, "end1", true); assertPlanFeasibility(run, "end2", false); assertPlanFeasibility(run, "end4", false);
        rejected(cmd(run, run.t(0).member, "PLAN_PROPOSE", json.createObjectNode().put("proposalKey", "member_try").put("endingKey", plans.get("end1"))));
        Map<Team, Map<String, Integer>> funding = fund(run, new int[]{0, 5, 2, 2, 0});
        Team funder = funding.keySet().iterator().next(); String locked = funding.get(funder).keySet().iterator().next();
        execute(run, "end1", funding, run.t(1), () -> {
            // 托管期间：出资队不能把已锁定的物资交易出去，也不能吃掉或丢弃
            ObjectNode open = json.createObjectNode().put("tradeKey", "escrow_conflict").put("toTeamId", run.t(2).id);
            open.putArray("give").addObject().put("itemId", locked).put("quantity", 1); open.putArray("take");
            rejected(cmd(run, funder.captain, "TRADE_OPEN", open));
            evidence.with("negative").put("escrowedItemCannotBeTraded", true).put("memberCannotPropose", true);
        });
        finished(run, "END01 最后的撤离者", new int[]{9, 7, 1, 1, 0});
    }

    @Test @Order(4) void s03MedicalStayUsesTheMobileThirdStationAndExecutesEnd02() throws Exception {
        Run run = open("S03");
        start(run, new int[]{3, 3, 3});
        for (Team team : run.teams) { doQuest(run, team, "a3f", 1, null); eat(run, team); doQuest(run, team, "a3l", 1, null); }
        doQuest(run, run.t(0), "a3w", 1, null);
        JsonNode mobile = player(run, run.t(0)).path("gameplay").path("extraSlots");
        assertTrue(mobile.has("mobile"), "第三站占用唯一机动额度：" + mobile);
        for (Team team : run.teams) doQuest(run, team, "hold", null, null);
        for (Team team : run.teams) doQuest(run, team, "a5", null, null);
        for (Team team : run.teams) doQuest(run, team, "a6p", 1, null);
        reconcile(run, "S03", new int[]{3, 5, 3, 7, 9}, new int[]{4, 5, 5}, new int[]{8, 7, 7}, new int[]{14, 9, 9});
        // 机动额度已用于第三站：人工复核不可再用（到达与开始都被拒）
        rejected(cmd(run, run.t(0).captain, "ARRIVAL_ISSUE", json.createObjectNode().put("questKey", questKey("a6m"))));
        rejected(cmd(run, run.t(0).captain, "QUEST_START", json.createObjectNode().put("questKey", questKey("a6m"))));
        execute(run, "end2", fund(run, new int[]{0, 4, 0, 4, 2}), run.t(0));
        finished(run, "END02 地下方舟", new int[]{3, 1, 3, 3, 7});
    }

    @Test @Order(5) void s04NoRescueRepairRouteStillReachesEnd04() throws Exception {
        Run run = open("S04");
        start(run, new int[]{3, 3, 3});
        for (Team team : run.teams) { doQuest(run, team, "a3f", 1, null); eat(run, team); doQuest(run, team, "a3w", 1, null); }
        for (int i = 0; i < 2; i++) { eat(run, run.t(i)); doQuest(run, run.t(i), "recover", null, null); }
        doQuest(run, run.t(2), "a3l", 2, null);
        doQuest(run, run.t(2), "hold", null, null);
        heatTogether(run, run.t(0));
        for (Team team : run.teams) doQuest(run, team, "a5", null, null);
        assertEquals(0, world(run).path("variables").path(clueKey("许遥已稳定")).asInt(), "无人救援");
        assertEquals(1, world(run).path("variables").path(clueKey("EV03 人口原表")).asInt(), "A5 公开人口原表（镜像）");
        for (Team team : run.teams) assertEquals(0, stored(run, team).path("variables").path(clueKey("EV07 许岚留言")).asInt(), "未救援不发私人证词");
        // 线索卡：玩家视图按名称给出已公开线索，重开后仍在；未获得的私人证词不出现
        for (Team team : run.teams) for (HttpHeaders who : new HttpHeaders[]{team.captain, team.member}) {
            JsonNode clues = success(get("/api/game/session/view?activityId=" + run.activityId + "&perspective=PLAYER", who)).path("player").path("clues");
            assertTrue(hasClue(clues, "EV03 人口原表", "PUBLIC"), clues.toString()); assertFalse(clues.toString().contains("EV07"), clues.toString());
            // 完整规程与授权链正文、图片全队可读（队长与队员）；不含未取得的私有原件
            assertClueText(clues, "EV04 维护规程", "PUBLIC", "顺序不可颠倒，旁路接通前必须完成负载校验", 2);
            assertClueText(clues, "EV05 授权链", "PUBLIC", "顾闻舟签 E-17 优先令；陈执行封门；周启明维护节点；ECHO 仅按名单调度", 1);
            assertFalse(clues.toString().contains("原件"), "未救援不出现原件：" + clues);
        }
        evidence.with("scenarios").with("S04").set("clueCards", player(run, run.t(0)).path("clues"));
        for (int i = 0; i < 2; i++) doQuest(run, run.t(i), "a6p", 1, null);
        doQuest(run, run.t(2), "a6e", null, null);
        reconcile(run, "S04", new int[]{1, 10, 3, 11, 3}, new int[]{9, 9, 7}, new int[]{7, 7, 8}, new int[]{14, 14, 14});
        execute(run, "end4", fund(run, new int[]{0, 6, 3, 6, 2}), run.t(2));
        finished(run, "END04 第二次日出", new int[]{1, 4, 0, 5, 1});
    }

    @Test @Order(6) void s05NoRationPathReachesH0AndStillExecutesEnd03() throws Exception {
        Run run = open("S05");
        start(run, new int[]{2, 2, 2});
        for (Team team : run.teams) { doQuest(run, team, "a3w", 1, null); doQuest(run, team, "a3l", 2, null); }
        for (Team team : run.teams) doQuest(run, team, "hold", null, null);
        for (Team team : run.teams) doQuest(run, team, "a5", null, null);
        for (Team team : run.teams) doQuest(run, team, "a6p", 1, null);
        reconcile(run, "S05", new int[]{0, 12, 9, 9, 0}, new int[]{0, 0, 0}, new int[]{7, 7, 7}, new int[]{14, 14, 14});
        assertPlanFeasibility(run, "end4", false);
        JsonNode end4 = null; for (JsonNode tool : player(run, run.t(0)).path("interactionTools")) if (plans.get("end4").equals(tool.path("endingKey").asText())) end4 = tool;
        assertNotNull(end4, "最终章方案可见"); assertTrue(end4.path("missingClues").size() > 0, end4.toString());
        assertFalse(end4.path("missingClues").toString().contains("clue."), "缺线索只给名称");
        evidence.with("scenarios").with("S05").set("end4MissingClues", end4.path("missingClues"));
        // H0：不能再开有保温消耗的新任务（人工复核 H1），但证据、协商、表决与 A7 照常
        arrive(run, run.t(0), json.createObjectNode().put("questKey", questKey("a6m")));
        rejected(cmd(run, run.t(0).captain, "QUEST_START", json.createObjectNode().put("questKey", questKey("a6m"))));
        execute(run, "end3", fund(run, new int[]{0, 0, 2, 2, 0}), run.t(0));
        finished(run, "END03 关闭太阳", new int[]{0, 12, 7, 7, 0});
    }

    @Test @Order(7) void end05OnlyWhenEveryTeamRejectsTheExecutableProposals() throws Exception {
        Run run = s02Path("END05");
        for (Team team : run.teams) success(cmd(run, team.captain, "PLAN_REJECT_ALL", json.createObjectNode()));
        success(cmd(run, author, "PLAN_FALLBACK", json.createObjectNode().put("reason", "三队均明确拒绝所有可执行方案")));
        finished(run, "END05 沉默的城市", new int[]{9, 12, 3, 3, 0});
    }

    private Run s02Path(String label) throws Exception {
        Run run = open(label);
        start(run, new int[]{1, 1, 1});
        for (Team team : run.teams) { doQuest(run, team, "a3f", 1, null); eat(run, team); doQuest(run, team, "a3w", 1, null); }
        // 物资不足：没有医疗包时不能按医疗包方式承接救援，开始即拒且不扣费
        int hp = player(run, run.t(0)).path("hp").asInt();
        arrive(run, run.t(0), json.createObjectNode().put("questKey", questKey("rescue")));
        rejected(cmd(run, run.t(0).captain, "QUEST_START", json.createObjectNode().put("questKey", questKey("rescue")).put("costOption", 0)));
        assertEquals(hp, player(run, run.t(0)).path("hp").asInt());
        for (Team team : run.teams) doQuest(run, team, "hold", null, null);
        for (Team team : run.teams) doQuest(run, team, "a5", null, null);
        for (Team team : run.teams) doQuest(run, team, "a6p", 2, null);
        return run;
    }

    @Test @Order(8) void onceOnlySettlementUnderRetryRaceCancelAndConflicts() throws Exception {
        Run run = open("NEG");
        Team a = run.t(0), b = run.t(1), c = run.t(2);
        ObjectNode n = evidence.with("negative");
        // A1：只扫码/只到达、或答错，都不能结算，也不扣保温/行动
        String a1 = questKey("a1");
        arrive(run, a, json.createObjectNode().put("questKey", a1));
        success(cmd(run, a.captain, "QUEST_START", json.createObjectNode().put("questKey", a1)));
        rejected(cmd(run, a.captain, "QUEST_RESOLVE", json.createObjectNode().put("questKey", a1).put("choiceKey", "opt_1")));
        long session = advancedSession(run, a, "a1");
        ObjectNode wrong = json.createObjectNode(); ObjectNode placement = wrong.putObject("placement");
        templateConfigs.get("a1").path("classify").path("answer").fieldNames().forEachRemaining(k -> placement.put(k, "wrong"));
        JsonNode state = success(get("/api/play/advanced/state?sessionId=" + session, a.captain));
        post("/api/play/advanced/action", json.createObjectNode().put("sessionId", session).put("version", state.path("version").asInt()).put("idempotencyKey", run.activityId + "-neg-wrong").put("action", "SUBMIT_CLASSIFY").set("payload", wrong), a.captain);
        rejected(cmd(run, a.captain, "QUEST_RESOLVE", json.createObjectNode().put("questKey", a1).put("choiceKey", "opt_1")));
        assertEquals(8, player(run, a).path("hp").asInt()); assertEquals(0, player(run, a).path("actions").asInt());
        // 取消释放：不扣费
        success(cmd(run, a.captain, "QUEST_CANCEL", json.createObjectNode().put("questKey", a1)));
        assertEquals(8, player(run, a).path("hp").asInt());
        n.put("scanOrWrongAnswerDoesNotSettle", true).put("cancelReleases", true);
        // 正确完成；同一请求重放返回原回执，换请求号不能再领
        arrive(run, a, json.createObjectNode().put("questKey", a1));
        success(cmd(run, a.captain, "QUEST_START", json.createObjectNode().put("questKey", a1)));
        long a1Session = solve(run, a, "a1");
        ObjectNode body = commandBody(run, a.captain, "QUEST_RESOLVE", json.createObjectNode().put("questKey", a1).put("choiceKey", "opt_3"));
        JsonNode first = success(post("/api/game/session/command", body, a.captain));
        JsonNode again = success(post("/api/game/session/command", body, a.captain));
        assertTrue(again.path("replayed").asBoolean()); assertEquals(first.path("result"), again.path("result"));
        rejected(cmd(run, a.captain, "QUEST_RESOLVE", json.createObjectNode().put("questKey", a1).put("choiceKey", "opt_1")));
        assertEquals(7, player(run, a).path("hp").asInt()); assertEquals(1, player(run, a).path("actions").asInt());
        assertEquals(1, count(player(run, a), "parts")); assertEquals(1, count(player(run, a), "medical"));
        n.put("replaySameResult", true).put("changedRequestIdRejected", true);
        // 已完成的站点重进：原局只读可读回，不能新开一局，也不能再开始任务
        assertTrue(success(get("/api/play/advanced/state?sessionId=" + a1Session, a.member)).path("readyForBase").asBoolean(), "队员重读原局已完成");
        JsonNode reopenA1 = post("/api/play/advanced/start", json.createObjectNode().put("topicId", topicId).put("nodeId", nodes.get("a1")).put("activityId", run.activityId).put("teamId", a.id), a.captain);
        assertTrue(reopenA1.path("code").asInt() != 200 || reopenA1.path("data").path("sessionId").asLong() == a1Session, reopenA1.toString());
        int[] settledA = {player(run, a).path("hp").asInt(), player(run, a).path("actions").asInt(), count(player(run, a), "parts"), count(player(run, a), "medical")};
        JsonNode restart = cmd(run, a.captain, "QUEST_START", json.createObjectNode().put("questKey", a1));
        assertFalse(player(run, a).path("gameplay").path("activeQuest").asText("").equals(a1), "已结算任务不会重新开始：" + restart);
        assertArrayEquals(settledA, new int[]{player(run, a).path("hp").asInt(), player(run, a).path("actions").asInt(), count(player(run, a), "parts"), count(player(run, a), "medical")}, "重开只回读原结算，不再扣费或发放");
        // 非队长不能动用共享物资
        rejected(cmd(run, a.member, "ITEM_USE", json.createObjectNode().put("contentId", "ration")));
        n.put("completedStationReadOnly", true).put("memberCannotSpend", true);
        for (Team team : new Team[]{b, c}) doQuest(run, team, "a1", 1, null);
        for (Team team : run.teams) doQuest(run, team, "a2", null, null);
        // A2 幕末配给：每队只扣一次；重读、重进不再扣
        int rationsA = count(player(run, a), "ration");
        player(run, a); post("/api/play/advanced/start", json.createObjectNode().put("topicId", topicId).put("nodeId", nodes.get("a2")).put("activityId", run.activityId).put("teamId", a.id), a.captain);
        assertEquals(rationsA, count(player(run, a), "ration"));
        // 休整：两次各 +2，第三次被拒；满值时不计次
        int hp = player(run, a).path("hp").asInt();
        doQuest(run, a, "rest", null, null); assertEquals(Math.min(12, hp + 2), player(run, a).path("hp").asInt());
        useFacility(run, a, "rest"); assertEquals(Math.min(12, hp + 4), player(run, a).path("hp").asInt());
        String rest = "tpl_node_" + nodes.get("rest");
        int afterTwo = player(run, a).path("hp").asInt();
        JsonNode third = cmd(run, a.captain, "OPERATION_START", json.createObjectNode().put("operationKind", "FACILITY").put("operationKey", rest));
        if (third.path("code").asInt() == 200) {
            arrive(run, a, json.createObjectNode().put("operationKind", "FACILITY").put("operationKey", rest));
            solve(run, a, "rest");
            JsonNode used = cmd(run, a.captain, "FACILITY_USE", json.createObjectNode().put("facilityKey", rest));
            if (used.path("code").asInt() == 200) assertNotEquals(true, used.path("data").path("result").path("consumed").asBoolean() && used.path("data").path("result").path("count").asInt() > 2, used.toString());
            success(cmd(run, a.captain, "OPERATION_CANCEL", json.createObjectNode()));
        }
        assertEquals(afterTwo, player(run, a).path("hp").asInt(), "第三次休整无效果");
        n.put("restTwiceThenBlocked", true);
        // A3：B、C 两队同时争全局救援之前，先让 C 物资不足以救援
        for (Team team : run.teams) { doQuest(run, team, "a3f", 2, null); doQuest(run, team, "a3w", 1, null); }
        // 并发救援：两队同时结算，只有一队扣费与生效
        Team[] racers = {a, b};
        for (Team team : racers) {
            arrive(run, team, json.createObjectNode().put("questKey", questKey("rescue")));
            success(cmd(run, team.captain, "QUEST_START", json.createObjectNode().put("questKey", questKey("rescue")).put("costOption", 0)));
            solve(run, team, "rescue");
        }
        int[] before = {player(run, a).path("hp").asInt(), player(run, b).path("hp").asInt()};
        List<JsonNode> results = race(run, racers, "QUEST_RESOLVE", json.createObjectNode().put("questKey", questKey("rescue")));
        int settled = 0, lost = 0;
        for (JsonNode result : results) { if (result.path("data").path("result").path("competitionLost").asBoolean()) lost++; else if (result.path("code").asInt() == 200) settled++; }
        assertEquals(1, world(run).path("themeRuntime").path("cooperation").path("facilities").path("tpl_node_" + nodes.get("rescue")).path("count").asInt(), results.toString());
        int charged = 0; for (int i = 0; i < 2; i++) if (player(run, racers[i]).path("hp").asInt() == before[i] - 2) charged++;
        assertEquals(1, charged, "全局救援只扣一次：" + results);
        n.put("concurrentRescueSettledOnce", true).put("raceSettled", settled).put("raceLost", lost);
        evidence.with("negative").set("raceResults", json.valueToTree(results));
        // 超出 14 格：回收奖励装不下时结算被拒，保温/行动/物资都不变；取消后可改选固守
        String recover = questKey("recover");
        int load = player(run, c).path("used").asInt(), hpC = player(run, c).path("hp").asInt(), actC = player(run, c).path("actions").asInt();
        n.put("cLoadBeforeRecover", load);
        arrive(run, c, json.createObjectNode().put("questKey", recover));
        success(cmd(run, c.captain, "QUEST_START", json.createObjectNode().put("questKey", recover)));
        solve(run, c, "recover");
        JsonNode overflow = cmd(run, c.captain, "QUEST_RESOLVE", json.createObjectNode().put("questKey", recover));
        if (load + 3 > 14) {
            rejected(overflow);
            assertEquals(hpC, player(run, c).path("hp").asInt()); assertEquals(actC, player(run, c).path("actions").asInt()); assertEquals(load, player(run, c).path("used").asInt());
            success(cmd(run, c.captain, "QUEST_CANCEL", json.createObjectNode().put("questKey", recover)));
            n.put("overCapacityRejectedWithoutCharge", true);
        } else success(overflow);
        // 重连：队员用自己的登录重新读取，与队长视图同一份队伍状态；高级局重进不改变已完成结果
        JsonNode captainView = player(run, a), memberView = success(get("/api/game/session/view?activityId=" + run.activityId + "&perspective=PLAYER", a.member)).path("player");
        for (String field : new String[]{"hp", "actions", "used", "contents", "clues"}) assertEquals(captainView.path(field), memberView.path(field), "重连后 " + field + " 一致");
        int revision = success(get("/api/game/session/view?activityId=" + run.activityId + "&perspective=PLAYER", a.member)).path("revision").asInt();
        JsonNode reread = success(get("/api/play/advanced/state?sessionId=" + a1Session, a.member));
        assertTrue(reread.path("readyForBase").asBoolean(), reread.toString());
        assertEquals(revision, success(get("/api/game/session/view?activityId=" + run.activityId + "&perspective=PLAYER", a.captain)).path("revision").asInt(), "只读重进不推进世界版本");
        n.put("reconnectSameTeamState", true);
    }

    private void assertPlanFeasibility(Run run, String end, boolean expected) {
        String plan = plans.get(end), proposal = "probe_" + end + "_" + (++run.request);
        JsonNode response = cmd(run, run.t(0).captain, "PLAN_PROPOSE", json.createObjectNode().put("proposalKey", proposal).put("endingKey", plan));
        if (!expected) {
            // 证据未公开时方案不可提出；证据满足但物资不足时提案可存在，但无法足额出资
            if (response.path("code").asInt() == 200) {
                int[] need = need(end); int[] have = totals(run);
                boolean enough = true; for (int k = 0; k < 5; k++) enough &= have[k] >= need[k];
                assertFalse(enough, end + " 不应可执行");
                success(cmd(run, run.t(0).captain, "PLAN_WITHDRAW", json.createObjectNode().put("proposalKey", proposal).put("version", proposalVersion(run, run.t(0), proposal))));
            }
            evidence.with("scenarios").with(run.label).with("feasible").put(end, false);
            return;
        }
        success(response);
        success(cmd(run, run.t(0).captain, "PLAN_WITHDRAW", json.createObjectNode().put("proposalKey", proposal).put("version", proposalVersion(run, run.t(0), proposal))));
        evidence.with("scenarios").with(run.label).with("feasible").put(end, true);
    }
    private int[] need(String end) {
        switch (end) { case "end1": return new int[]{0, 5, 2, 2, 0}; case "end2": return new int[]{0, 4, 0, 4, 2}; case "end3": return new int[]{0, 0, 2, 2, 0}; default: return new int[]{0, 6, 3, 6, 2}; }
    }
    private int[] totals(Run run) {
        int[] sum = new int[5];
        for (Team team : run.teams) { JsonNode view = player(run, team); for (int k = 0; k < 5; k++) sum[k] += count(view, ITEMS[k]); }
        return sum;
    }
    private int count(JsonNode view, String item) {
        int total = 0; for (JsonNode row : view.path("contents")) if (item.equals(row.path("itemId").asText(row.path("id").asText()))) total += row.path("quantity").asInt();
        return total;
    }
    private JsonNode stored(Run run, Team team) {
        try { return json.readTree(database.queryForObject("SELECT variables_json FROM game_story_state WHERE game_session_id=(SELECT id FROM game_session WHERE activity_id=?) AND owner_type='TEAM' AND owner_id=?", String.class, run.activityId, team.id)); }
        catch (Exception unreadable) { throw new IllegalStateException(unreadable); }
    }
    private ObjectNode commandBody(Run run, HttpHeaders headers, String action, ObjectNode payload) {
        JsonNode view = success(get("/api/game/session/view?activityId=" + run.activityId + "&perspective=" + (headers == author ? "CLUB" : "PLAYER"), headers));
        ObjectNode body = json.createObjectNode().put("activityId", run.activityId).put("expectedRevision", view.path("revision").asInt())
                .put("requestId", run.activityId + "-" + run.label + "-" + action.toLowerCase(Locale.ROOT) + "-" + (++run.request)).put("action", action);
        if (view.path("authorityEpoch").isInt()) body.set("authorityEpoch", view.path("authorityEpoch"));
        body.set("payload", payload); return body;
    }
    /** 多队同时提交同一动作；版本冲突的一方按原合同重读后用新请求号重试。 */
    private List<JsonNode> race(Run run, Team[] teams, String action, ObjectNode payload) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(teams.length);
        try {
            List<ObjectNode> bodies = new ArrayList<>(); for (Team team : teams) bodies.add(commandBody(run, team.captain, action, payload.deepCopy()));
            CountDownLatch gate = new CountDownLatch(1); List<Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < teams.length; i++) { final int index = i; futures.add(pool.submit(() -> { gate.await(); return post("/api/game/session/command", bodies.get(index), teams[index].captain); })); }
            gate.countDown();
            List<JsonNode> out = new ArrayList<>();
            for (int i = 0; i < teams.length; i++) {
                JsonNode response = futures.get(i).get(60, TimeUnit.SECONDS);
                if (response.path("code").asInt() != 200 && "GAME_STATE_CONFLICT".equals(response.path("data").path("reasonCode").asText()))
                    response = post("/api/game/session/command", commandBody(run, teams[i].captain, action, payload.deepCopy()), teams[i].captain);
                out.add(response);
            }
            return out;
        } finally { pool.shutdownNow(); }
    }

    // ---------- 公用步骤 ----------
    private void start(Run run, int[] packs) throws Exception {
        for (int i = 0; i < 3; i++) {
            Team team = run.t(i);
            doQuest(run, team, "a1", packs[i], null);
            doQuest(run, team, "a2", null, null);
        }
    }
    private void eat(Run run, Team team) {
        success(cmd(run, team.captain, "ITEM_USE", json.createObjectNode().put("contentId", "ration"))); track(run, team);
    }
    private Map<Team, Map<String, Integer>> fund(Run run, int[] need) {
        Map<Team, Map<String, Integer>> out = new LinkedHashMap<>();
        int[] left = need.clone();
        for (Team team : run.teams) {
            JsonNode inv = player(run, team).path("contents");
            Map<String, Integer> share = new LinkedHashMap<>();
            for (int k = 0; k < 5; k++) {
                int have = 0; for (JsonNode row : inv) if (ITEMS[k].equals(row.path("itemId").asText(row.path("id").asText()))) have += row.path("quantity").asInt();
                int take = Math.min(have, left[k]); if (take > 0) share.put(ITEMS[k], take); left[k] -= take;
            }
            if (!share.isEmpty()) out.put(team, share);
        }
        for (int k = 0; k < 5; k++) assertEquals(0, left[k], "物资不足以出资：" + LETTERS[k]);
        return out;
    }
    private void reconcile(Run run, String label, int[] totals, int[] hp, int[] actions, int[] reportedPeak) {
        int[] sum = new int[5]; int[] h = new int[3], a = new int[3], peak = new int[3];
        for (int i = 0; i < 3; i++) {
            JsonNode view = player(run, run.t(i));
            h[i] = view.path("hp").asInt(); a[i] = view.path("actions").asInt(); peak[i] = run.t(i).peak;
            for (JsonNode row : view.path("contents")) for (int k = 0; k < 5; k++) if (ITEMS[k].equals(row.path("itemId").asText(row.path("id").asText()))) sum[k] += row.path("quantity").asInt();
        }
        ObjectNode record = evidence.with("scenarios").putObject(label);
        record.put("activityId", run.activityId);
        record.set("inventory", json.valueToTree(sum)); record.set("hp", json.valueToTree(h)); record.set("actions", json.valueToTree(a)); record.set("peakLoad", json.valueToTree(peak));
        assertArrayEquals(totals, sum, label + " 终局前共享 R/F/E/P/M");
        assertArrayEquals(hp, h, label + " H");
        assertArrayEquals(actions, a, label + " 行动");
        record.set("reportedPeakLoad", json.valueToTree(reportedPeak));
        assertArrayEquals(reportedPeak, peak, label + " 背包峰值（原稿规则报告）");
    }
    private void finished(Run run, String title, int[] after) {
        JsonNode world = player(run, run.t(0)).path("world");
        assertEquals(title, world.path("result").path("title").asText(), world.toString());
        int[] sum = new int[5];
        for (Team team : run.teams) {
            JsonNode view = player(run, team);
            assertEquals(title, view.path("result").path("title").asText(), team.name + " 同一世界结局：" + view.toString());
            for (JsonNode row : view.path("contents")) for (int k = 0; k < 5; k++) if (ITEMS[k].equals(row.path("itemId").asText(row.path("id").asText()))) sum[k] += row.path("quantity").asInt();
        }
        if (after != null) assertArrayEquals(after, sum, "执行后只扣一次方案材料");
        evidence.with("scenarios").with(run.label).put("ending", title).set("afterExecution", json.valueToTree(sum));
    }
    private void track(Run run, Team team) { team.peak = Math.max(team.peak, player(run, team).path("used").asInt()); }

    // ---------- 读回 ----------
    private JsonNode player(Run run, Team team) {
        return success(get("/api/game/session/view?activityId=" + run.activityId + "&perspective=PLAYER", team.captain)).path("player");
    }
    private JsonNode world(Run run) {
        try { return json.readTree(database.queryForObject("SELECT variables_json FROM game_story_state WHERE game_session_id=(SELECT id FROM game_session WHERE activity_id=?) AND owner_type='WORLD'", String.class, run.activityId)); }
        catch (Exception unreadable) { throw new IllegalStateException(unreadable); }
    }
    private JsonNode cmd(Run run, HttpHeaders headers, String action, ObjectNode payload) {
        JsonNode view = success(get("/api/game/session/view?activityId=" + run.activityId + "&perspective=" + (headers == author ? "CLUB" : "PLAYER"), headers));
        ObjectNode body = json.createObjectNode().put("activityId", run.activityId).put("expectedRevision", view.path("revision").asInt())
                .put("requestId", run.activityId + "-" + run.label + "-" + action.toLowerCase(Locale.ROOT) + "-" + (++run.request)).put("action", action);
        if (view.path("authorityEpoch").isInt()) body.set("authorityEpoch", view.path("authorityEpoch"));
        body.set("payload", payload);
        return post("/api/game/session/command", body, headers);
    }
    private void staff(Run run, String action, ObjectNode payload) { success(cmd(run, author, action, payload)); }

    // ---------- 作者配置 ----------
    private ObjectNode rules() {
        ObjectNode rules = json.createObjectNode().put("schemaVersion", 1).put("stateEnabled", true).put("checkEnabled", false);
        rules.putObject("hp").put("enabled", true).put("init", 8).put("max", 12);
        rules.putObject("luck").put("enabled", false).put("init", 3).put("max", 3);
        return rules;
    }
    private ObjectNode themeBase() {
        ObjectNode theme = json.createObjectNode().put("schemaVersion", 1).put("capacity", 14).put("hpName", "保温储备").put("actionName", "外勤行动").put("actionLimit", 8);
        ArrayNode items = theme.putArray("items");
        String[][] defs = {{"ration", "口粮", "1"}, {"fuel", "燃料", "2"}, {"power", "电源", "1"}, {"parts", "维修件", "1"}, {"medical", "医疗包", "1"}};
        for (String[] d : defs) {
            ObjectNode item = items.addObject().put("id", d[0]).put("kind", "ITEM").put("name", d[1]).put("image", "https://www.chengyinhub.com/favicon.ico").put("slotCost", Integer.parseInt(d[2]));
            if ("ration".equals(d[0])) item.put("stat", "HP").put("delta", 3);
        }
        ObjectNode game = theme.putObject("gameplayRuntime").put("runtimeScope", "TEAM").put("worldMode", "WORLD_SHARED").put("allowTrade", true).put("votePolicy", "MAJORITY_ACCEPTANCE");
        game.putObject("survival").put("zeroHpPolicy", "BLOCK_HP_COST").put("actionLimitPolicy", "BLOCK_OVERFLOW");
        return theme;
    }
    private ObjectNode binding(String key) {
        JsonNode slots = templateConfigs.get(key).path("interactionRules").path("resourceSlots");
        ObjectNode binding = json.createObjectNode().put("templateId", templateIds.get(key)).put("slotDigest", GameplayTemplateRules.slotDigest(slots.isMissingNode() ? json.createArrayNode() : slots));
        ObjectNode values = binding.putObject("values"), items = binding.putObject("items");
        for (JsonNode slot : slots) {
            String k = slot.path("key").asText();
            if ("h".equals(k)) values.put(k, "sys.hp"); else if ("act".equals(k)) values.put(k, "sys.actions");
            else items.put(k, new String[]{"ration", "fuel", "power", "parts", "medical"}["rfepm".indexOf(k)]);
        }
        return binding;
    }
    private String actKey(JsonNode theme, long nodeId) {
        for (JsonNode q : theme.path("gameplayRuntime").path("quests")) if (q.path("nodeId").asLong() == nodeId) return q.path("actKey").asText();
        fail("站点没有编译出幕：" + nodeId + " " + theme); return null;
    }
    /** 发布后任务标识以发布配置为准（站点重建后 nodeId 已重映射，标识保持不透明）。 */
    private String questKey(String key) { return questKeys.get(key); }
    private JsonNode facility(JsonNode game, long nodeId) {
        for (JsonNode f : game.path("facilities")) if (f.path("operationNodeId").asLong() == nodeId || f.path("facilityKey").asText().equals("tpl_node_" + nodeId)) return f;
        fail("缺少设施 " + nodeId); return null;
    }
    private JsonNode quest(JsonNode game, long nodeId) {
        for (JsonNode q : game.path("quests")) if (q.path("nodeId").asLong() == nodeId) return q;
        fail("缺少任务 " + nodeId); return null;
    }
    private ObjectNode resolveClues(ObjectNode config) throws Exception {
        Map<String, String> names = new LinkedHashMap<>();
        JsonNode resolved = replaceClues(config, names);
        ObjectNode out = (ObjectNode) resolved;
        if (!names.isEmpty()) out.set("clues", json.valueToTree(names));
        return out;
    }
    private JsonNode replaceClues(JsonNode node, Map<String, String> names) throws Exception {
        if (node.isTextual() && node.asText().startsWith("$clue:")) { String name = node.asText().substring(6); names.put(clueKey(name), name); return json.getNodeFactory().textNode(clueKey(name)); }
        if (node.isObject()) { ObjectNode copy = json.createObjectNode(); Iterator<Map.Entry<String, JsonNode>> it = node.fields(); while (it.hasNext()) { Map.Entry<String, JsonNode> e = it.next(); copy.set(e.getKey(), replaceClues(e.getValue(), names)); } return copy; }
        if (node.isArray()) { ArrayNode copy = json.createArrayNode(); for (JsonNode child : node) copy.add(replaceClues(child, names)); return copy; }
        return node;
    }
    /** 与小程序「线索」工具同一算法：clue.h_ + 名称 UTF-8 SHA-256 前 12 位。 */
    static String clueKey(String name) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte b : digest) hex.append(String.format("%02x", b));
            return "clue.h_" + hex.substring(0, 12);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    // ---------- HTTP 基础 ----------
    private final int phoneBase = (int) (System.nanoTime() % 5000000L);
    private String nextPhone() { return "1391" + String.format("%07d", (phoneBase + (++phones)) % 10000000); }
    private long memberId(String phone) { return database.queryForObject("SELECT id FROM ums_member WHERE phone=?", Long.class, phone); }
    private HttpHeaders loginHeaders(String phone, String nickname) {
        UmsMember member = new UmsMember(); member.setPhone(phone); member.setNickname(nickname); member.setStatus(1L); member.setDelFlag(0); member.setWxOpenId("e2e-http-freeze-" + phone); members.insertUmsMember(member);
        GenericGameplayHttpE2eIT.registerPublisherIdentity(context, database, member.getId());
        context.getBean(com.chengyinhub.business.service.ICapabilityService.class).grant(member.getId(), "club_leader", member.getId());
        MockHttpServletRequest request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1"); request.addHeader("User-Agent", "Freeze HTTP E2E");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        HttpHeaders headers = new HttpHeaders();
        try { headers.setBearerAuth(login.loginByMember(member, "phone", phone, null)); } finally { RequestContextHolder.resetRequestAttributes(); }
        headers.setContentType(MediaType.APPLICATION_JSON); return headers;
    }
    private JsonNode get(String path, HttpHeaders headers) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class); record("GET", path, null, response); return response.getBody();
    }
    private JsonNode post(String path, JsonNode body, HttpHeaders headers) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class); record("POST", path, body, response); return response.getBody();
    }
    private JsonNode form(String path, long id, HttpHeaders original) {
        HttpHeaders headers = new HttpHeaders(); headers.putAll(original); headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        org.springframework.util.LinkedMultiValueMap<String, String> body = new org.springframework.util.LinkedMultiValueMap<>(); body.add("id", String.valueOf(id));
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        record("POST", path, json.createObjectNode().put("id", id), response); return response.getBody();
    }
    private JsonNode success(JsonNode response) { assertEquals(200, response.path("code").asInt(), response.toString()); return response.path("data"); }
    private void rejected(JsonNode response) { assertNotEquals(200, response.path("code").asInt(), response.toString()); }
    private JsonNode persistedTopic(long id, String phase) throws Exception {
        Map<String, Object> row = database.queryForMap("SELECT theme_runtime_config_json,published_theme_runtime_config_json,published_route_graph_json FROM cms_topic WHERE id=?", id);
        ObjectNode values = json.createObjectNode().put("topicId", id).put("phase", phase);
        if (row.get("theme_runtime_config_json") != null) values.set("theme", json.readTree(row.get("theme_runtime_config_json").toString()));
        if (row.get("published_theme_runtime_config_json") != null) values.set("published", json.readTree(row.get("published_theme_runtime_config_json").toString()));
        if (row.get("published_route_graph_json") != null) values.set("snapshot", json.readTree(row.get("published_route_graph_json").toString()));
        transcript.addObject().put("method", "DB_READBACK").put("path", "cms_topic:" + id).put("httpStatus", 0).set("response", values);
        return values;
    }
    private void record(String method, String path, JsonNode request, ResponseEntity<JsonNode> response) {
        ObjectNode row = transcript.addObject().put("method", method).put("path", path).put("authorization", "[REDACTED]").put("httpStatus", response.getStatusCode().value());
        if (request != null) row.set("request", GenericGameplayHttpE2eIT.redact(request));
        row.set("response", GenericGameplayHttpE2eIT.redact(response.getBody()));
    }
}

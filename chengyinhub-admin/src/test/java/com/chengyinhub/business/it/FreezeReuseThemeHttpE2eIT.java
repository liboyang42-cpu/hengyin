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

/** 跨主题复用原寒冬模板，作者发布与三支双人队的玩法均走真实 HTTP；仅场次/报名使用既有本地夹具。 */
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(classes = RuoYiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = GenericGameplayHttpE2eIT.LocalInitializer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FreezeReuseThemeHttpE2eIT {
    @Autowired private TestRestTemplate http;
    @Autowired private ConfigurableApplicationContext context;
    @Autowired private UmsMemberMapper members;
    @Autowired private AppLoginService login;
    @Autowired private JdbcTemplate database;
    private final ObjectMapper json = new ObjectMapper();
    private final ArrayNode transcript = json.createArrayNode();
    private final GenericGameplayHttpE2eIT.ReviewedMediaFixture mediaFixture = new GenericGameplayHttpE2eIT.ReviewedMediaFixture("reuse");
    private final ObjectNode evidence = json.createObjectNode();
    private static final String[] ITEMS = {"water", "diesel", "battery", "spare", "kit"};
    private static final String STAMINA = "counter.stamina";
    private int phones;
    private HttpHeaders author;
    private long authorId, topicId, clubId;
    private final Map<String, Long> templateIds = new LinkedHashMap<>();
    private final Map<String, JsonNode> templateConfigs = new LinkedHashMap<>();
    private final Map<String, Long> nodes = new LinkedHashMap<>();
    private final List<Long> chapterIds = new ArrayList<>();
    private final Map<String, String> plans = new LinkedHashMap<>();
    private final Map<String, String> questKeys = new LinkedHashMap<>();

    @BeforeAll void loadExistingPermissionFixture() throws Exception {
        GenericGameplayHttpE2eIT.configureRequestSerialization(http, json);
        GenericGameplayHttpE2eIT.assertContentSecurityFixture(context);
        for (String file : new String[]{"sql/migration_p1_rbac_permission_defaults.sql", "sql/migration_m1_permission_full.sql", "sql/migration_p1_rbac_permission_defaults.sql"})
            for (String statement : ItSchema.splitStatements(new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8))) {
                String sql = statement.replaceAll("(?m)^\\s*--.*$", "").trim();
                if (sql.matches("(?is)^(INSERT INTO|UPDATE) user_permission\\b.*")) database.execute(sql);
            }
    }

    @AfterAll void writeEvidence() throws Exception {
        try { mediaFixture.close(); } finally {
            if (context != null) {
                ScheduledExecutorService executor = context.getBean("scheduledExecutorService", ScheduledExecutorService.class);
                executor.shutdown(); if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
            }
            Files.createDirectories(Paths.get("target/http-e2e"));
            json.writerWithDefaultPrettyPrinter().writeValue(Paths.get("target/http-e2e/reuse-transcript.json").toFile(), transcript);
            json.writerWithDefaultPrettyPrinter().writeValue(Paths.get("target/http-e2e/reuse-evidence.json").toFile(), evidence);
        }
    }

    @Test @Order(1) void authorReusesEightTemplatesInAnIndependentThreeChapterTheme() throws Exception {
        String phone = nextPhone(); author = loginHeaders(phone, "海港作者"); authorId = memberId(phone);
        JsonNode layout = json.readTree(getClass().getClassLoader().getResourceAsStream("freeze-game/reuse-chapters.json"));
        Set<String> selected = new LinkedHashSet<>();
        for (JsonNode chapter : layout) for (JsonNode node : chapter.path("nodes")) selected.add(node.path(0).asText());
        JsonNode fixtures = json.readTree(getClass().getClassLoader().getResourceAsStream("freeze-game/templates.json"));
        assertEquals(19, fixtures.size()); assertEquals(8, selected.size());
        for (JsonNode row : fixtures) {
            String key = row.path("key").asText(); if (!selected.contains(key)) continue;
            ObjectNode config = resolveClues(row.path("config").deepCopy());
            ObjectNode request = json.createObjectNode().put("title", row.path("title").asText()).put("validationMethod", row.path("validationMethod").asInt())
                    .put("isSync", 0).put("advancedConfigJson", config.toString());
            long id = success(post("/api/template/draft", request, author)).asLong(); assertTrue(id > 0);
            JsonNode reread = success(form("/api/template/myinfo", id, author));
            JsonNode stored = json.readTree(reread.path("advancedConfigJson").asText());
            assertEquals(config.path("interactionRules").path("resourceSlots"), stored.path("interactionRules").path("resourceSlots"), key);
            templateIds.put(key, id); templateConfigs.put(key, stored);
        }
        // 先登记本主题数值域，再采用模板，避免绑定尚未注册的 counter。
        ObjectNode topic = json.createObjectNode().put("name", "海港停电夜").put("saveDraft", 1).put("publishMode", "simple").put("productType", 1)
                .put("routeMode", "LINEAR").put("teamMode", 1).put("teamMaxMembers", 2).put("journeyRules", rules().toString());
        ArrayNode chapters = topic.putArray("chapters");
        for (JsonNode chapter : layout) {
            ObjectNode row = chapters.addObject().put("name", chapter.path("name").asText()).put("schemaVersion", 1);
            row.putArray("nodes"); row.putArray("blocks").addObject().put("type", "text").put("content", chapter.path("name").asText());
        }
        topicId = success(post("/api/topic/v2/create", topic, author)).path("topicId").asLong(); assertTrue(topicId > 0);
        ObjectNode registration = json.createObjectNode().put("topicId", topicId);
        registration.putArray("keys").addObject().put("stateKey", STAMINA).put("keyType", "counter").put("scope", "run").put("label", "体力").put("initialValue", 20).put("minValue", 0).put("maxValue", 30);
        success(post("/api/topic/state-keys", registration, author));
        JsonNode read = success(form("/api/topic/edit-detail", topicId, author));
        topic.put("id", topicId).put("configVersion", read.path("topic").path("configVersion").asInt());
        String registryVersion = GenericGameplayHttpE2eIT.confirmAttributeBinding(topic, success(get("/api/topic/attribute-binding?topicId=" + topicId, author)));
        ObjectNode bindings = json.createObjectNode();
        for (int c = 0; c < layout.size(); c++) {
            ObjectNode chapter = (ObjectNode) chapters.get(c); chapter.put("id", read.path("chapters").get(c).path("id").asLong());
            int index = 0;
            for (JsonNode node : layout.get(c).path("nodes")) {
                String key = node.path(0).asText();
                chapter.withArray("nodes").addObject().put("clientNodeKey", key).put("name", node.path(1).asText()).put("templateId", templateIds.get(key))
                        .put("nodeTime", 0).put("nodeMoney", 0).put("latitude", "31.2304").put("longitude", "121.4594").put("address", "海港测试站");
                chapter.withArray("blocks").addObject().put("type", "node").put("nodeIndex", index++);
                bindings.set("client:" + key, binding(key));
            }
        }
        ObjectNode theme = themeBase(); theme.set("templateBindings", bindings); topic.put("themeRuntimeConfig", theme.toString());
        success(post("/api/topic/v2/update", topic, author));
        read = success(form("/api/topic/edit-detail", topicId, author)); readIds(read, layout);
        topic.put("configVersion", read.path("topic").path("configVersion").asInt());
        for (int c = 0; c < layout.size(); c++) {
            ((ObjectNode) chapters.get(c)).put("id", chapterIds.get(c));
            for (int n = 0; n < layout.get(c).path("nodes").size(); n++)
                ((ObjectNode) chapters.get(c).path("nodes").get(n)).put("id", nodes.get(layout.get(c).path("nodes").get(n).path(0).asText())).remove("clientNodeKey");
        }
        theme = (ObjectNode) json.readTree(read.path("themeRuntimeConfig").asText());
        mediaFixture.restoreKnownItemImages(theme);
        assertFalse(theme.toString().contains("client:"));
        ArrayNode settings = theme.putArray("chapters");
        for (int c = 0; c < 3; c++) {
            ObjectNode setting = settings.addObject().put("index", c).put("hpCost", 0).put("luckCost", 0);
            setting.putObject("completion").put("type", "ANY_N").put("quantity", c == 1 ? 2 : 1);
        }
        ((ObjectNode) settings.get(0)).putArray("extraNodes").addObject().put("nodeId", nodes.get("rest")).put("slot", "rest");
        ArrayNode grants = ((ObjectNode) settings.get(0)).putArray("grants");
        for (String item : new String[]{"diesel", "battery", "spare", "kit"}) grants.addObject().put("itemId", item).put("quantity", 4).put("timing", "START");
        ((ObjectNode) settings.get(2)).putArray("exclusiveGroups").addArray().add(nodes.get("end1")).add(nodes.get("end2"));
        ObjectNode ration = theme.with("gameplayRuntime").with("survival").putArray("rations").addObject()
                .put("rationKey", "harbor_water").put("actKey", actKey(theme, nodes.get("a3f"))).put("itemId", "water").put("quantity", 4);
        ration.putObject("fallback").put("statKey", STAMINA).put("amount", 2);
        Map<String, Long> previousNodes = new LinkedHashMap<>(nodes);
        topic.put("themeRuntimeConfig", theme.toString()); success(post("/api/topic/v2/update", topic, author));
        JsonNode reopened = success(form("/api/topic/edit-detail", topicId, author)); readIds(reopened, layout);
        for (String key : selected) assertNotEquals(previousNodes.get(key), nodes.get(key), "保存重建节点：" + key);
        JsonNode saved = json.readTree(reopened.path("themeRuntimeConfig").asText());
        assertEquals(nodes.get("rest").longValue(), saved.path("chapters").get(0).path("extraNodes").get(0).path("nodeId").asLong());
        assertEquals(actKey(saved, nodes.get("a3f")), saved.path("gameplayRuntime").path("survival").path("rations").get(0).path("actKey").asText());
        success(publishBound(topicId, registryVersion, author));
        JsonNode frozen = persistedTopic(topicId, "published");
        JsonNode published = frozen.path("published"), game = published.path("gameplayRuntime");
        assertEquals(3, game.path("acts").size()); assertEquals(8, game.path("quests").size());
        assertEquals("MAJORITY", game.path("votePolicy").asText()); assertTrue(game.path("allowTrade").asBoolean());
        for (String key : selected) {
            JsonNode q = quest(game, nodes.get(key)); questKeys.put(key, q.path("questKey").asText());
            assertFalse(q.path("questKey").asText().isEmpty());
            assertEquals(templateIds.get(key).longValue(), published.path("templateBindings").path(String.valueOf(nodes.get(key))).path("templateId").asLong());
        }
        for (JsonNode act : game.path("acts")) assertEquals("ANY_N", act.path("completionRule").path("type").asText());
        for (JsonNode f : game.path("facilities")) assertTrue(nodes.containsValue(f.path("operationNodeId").asLong()), f.toString());
        assertItemReferences(game);
        assertEquals(STAMINA, facility(game, nodes.get("a3f")).path("statCosts").get(0).path("statKey").asText());
        JsonNode choices = quest(game, nodes.get("a3f")).path("choices");
        assertEquals(2, choices.size()); assertEquals("water", choices.get(0).path("grants").get(0).path("itemId").asText());
        assertEquals(3, choices.get(0).path("grants").get(0).path("quantity").asInt());
        assertTrue(quest(game, nodes.get("rest")).path("extraOnly").asBoolean());
        assertEquals(quest(game, nodes.get("heat")).path("locationKey"), facility(game, nodes.get("heat")).path("arrivalLocationKey"));
        assertTrue(facility(game, nodes.get("a6e")).path("publicOnce").asBoolean());
        assertEquals(2, game.path("plans").size());
        for (String end : new String[]{"end1", "end2"}) {
            JsonNode plan = null; for (JsonNode row : game.path("plans")) if (row.path("operationNodeId").asLong() == nodes.get(end)) plan = row;
            assertNotNull(plan); assertTrue(plan.path("terminal").asBoolean());
            assertEquals(chapterIds.get(2).longValue(), plan.path("endingChapterId").asLong()); plans.put(end, plan.path("endingKey").asText());
        }
        int fallbacks = 0; for (JsonNode row : game.path("storyEndings")) if (row.path("fallback").asBoolean()) { fallbacks++; assertEquals(chapterIds.get(2).longValue(), row.path("chapterId").asLong()); }
        assertEquals(1, fallbacks); assertEquals(3, game.path("storyEndings").size());
        for (JsonNode config : templateConfigs.values()) {
            Iterator<Map.Entry<String, JsonNode>> clues = config.path("clues").fields();
            while (clues.hasNext()) {
                Map.Entry<String, JsonNode> clue = clues.next();
                assertEquals(clueKey(clue.getValue().asText()), clue.getKey());
                assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM theme_state_key WHERE topic_id=? AND state_key=? AND label=?", Integer.class, topicId, clue.getKey(), clue.getValue().asText()));
            }
        }
        ObjectNode record = evidence.with("authoring"); record.put("topicId", topicId).put("templateCount", templateIds.size()).put("chapterCount", chapterIds.size()).put("nodeIdsRemapped", true);
        record.set("nodeIds", json.valueToTree(nodes)); record.set("templateIds", json.valueToTree(templateIds)); record.set("questKeys", json.valueToTree(questKeys));
        record.set("published", published); record.set("journeyRules", rules());
        clubId = success(post("/api/club/apply", json.createObjectNode().put("name", "海港测试主理团").put("leaderName", "海港主持"), author)).path("clubId").asLong();
        database.update("INSERT INTO coop_order(topic_id,from_id,to_id,to_type,status,del_flag,create_time) VALUES(?,?,?,'club',0,0,NOW())", topicId, authorId, clubId);
    }

    @Test @Order(2) void threeDoubleTeamsReuseRewardsArrivalPublicCluesAndMajorityEnding() throws Exception {
        assertEquals(8, questKeys.size(), "作者测试须先完成发布");
        Run run = open("REUSE"); Team a = run.t(0), b = run.t(1), c = run.t(2);
        for (Team team : run.teams) {
            doQuest(run, team, "a3f", 1, null);
            assertEquals(3, count(player(run, team), "water"), "本站任选奖励发放新主题淡水");
            assertEquals(17, stamina(run, team), "模板耗体力 1 + 不足配给扣体力 2");
            assertEquals(8, player(run, team).path("hp").asInt(), "不足配给与模板 h 槽均未扣 HP");
            assertEquals(1, player(run, team).path("gameplay").path("actIndex").asInt());
            JsonNode journal = stored(run, team).path("themeRuntime").path("journal");
            int rations = 0; for (JsonNode row : journal) if ("RATION".equals(row.path("action").asText())) {
                rations++; assertEquals(STAMINA, row.path("result").path("statKey").asText()); assertEquals(-2, row.path("result").path("statDelta").asInt());
            }
            assertEquals(1, rations); player(run, team); assertEquals(17, stamina(run, team), "读回不重复配给");
        }
        doQuest(run, a, "rest", null, null); assertEquals(19, stamina(run, a), "额外休整节点首次经站点任务结算");
        useFacility(run, a, "rest"); assertEquals(21, stamina(run, a), "已过章节的额外休整设施可再次使用");
        for (Team team : new Team[]{a, b}) {
            arrive(run, team, json.createObjectNode().put("questKey", questKey("a6e")));
            success(cmd(run, team.captain, "QUEST_START", json.createObjectNode().put("questKey", questKey("a6e")))); solve(run, team, "a6e");
        }
        success(cmd(run, a.captain, "QUEST_RESOLVE", json.createObjectNode().put("questKey", questKey("a6e"))));
        JsonNode before = stored(run, b); int battery = count(player(run, b), "battery");
        JsonNode lost = success(cmd(run, b.captain, "QUEST_RESOLVE", json.createObjectNode().put("questKey", questKey("a6e"))));
        assertTrue(lost.path("result").path("competitionLost").asBoolean(), lost.toString());
        assertEquals(before.path("variables"), stored(run, b).path("variables"), "第二队不再扣数值");
        assertEquals(battery, count(player(run, b), "battery"), "第二队不再扣电池");
        assertEquals(1, world(run).path("variables").path(clueKey("EV06 负载校验")).asInt());
        heatTogether(run, a);
        for (Team team : new Team[]{b, c}) { doQuest(run, team, "a2", null, null); doQuest(run, team, "hold", null, null); }
        for (Team team : run.teams) assertEquals(2, player(run, team).path("gameplay").path("actIndex").asInt(), "队伍已到最后一章");
        String proposal = "harbor_depart";
        success(cmd(run, a.captain, "PLAN_PROPOSE", json.createObjectNode().put("proposalKey", proposal).put("endingKey", plans.get("end1"))));
        int[][] amounts = {{3, 2, 0}, {2, 0, 2}};
        String[] supplies = {"diesel", "battery", "spare"};
        for (int i = 0; i < 2; i++) {
            Team team = run.t(i); ObjectNode intent = json.createObjectNode().put("proposalKey", proposal).put("version", proposalVersion(run, team, proposal));
            ArrayNode rows = intent.putArray("amounts"); for (int j = 0; j < 3; j++) if (amounts[i][j] > 0) rows.addObject().put("itemId", supplies[j]).put("quantity", amounts[i][j]);
            success(cmd(run, team.captain, "PLAN_INTENT", intent));
        }
        success(cmd(run, a.captain, "VOTE_OPEN", json.createObjectNode()));
        ObjectNode selected = json.createObjectNode().put("round", world(run).path("themeRuntime").path("cooperation").path("round").asInt()).put("proposalKey", proposal);
        for (Team team : new Team[]{a, b}) success(cmd(run, team.captain, "VOTE_CAST", selected.deepCopy()));
        // 既有票轮等全体表达后结算；第三队反对，两票检验多数而非全票一致。
        success(cmd(run, c.captain, "VOTE_CAST", json.createObjectNode().put("round", selected.path("round").asInt()).put("proposalKey", "REJECT")));
        JsonNode vote = world(run).path("themeRuntime").path("cooperation");
        assertEquals("SELECTED", vote.path("stage").asText()); assertEquals(proposal, vote.path("selected").asText());
        assertEquals(3, vote.path("votes").size());
        assertEquals("REJECT", vote.path("votes").path(String.valueOf(c.id)).path("choice").asText());
        ObjectNode operation = json.createObjectNode().put("operationKind", "PLAN").put("operationKey", plans.get("end1"));
        success(cmd(run, a.captain, "OPERATION_START", operation));
        arrive(run, a, operation.deepCopy());
        solve(run, a, "end1");
        for (Team team : new Team[]{a, b}) success(cmd(run, team.captain, "PLAN_CONFIRM", selected.deepCopy()));
        success(cmd(run, a.captain, "PLAN_EXECUTE", selected.deepCopy()));
        JsonNode ending = player(run, a).path("result"); assertEquals("END01 最后的撤离者", ending.path("title").asText());
        assertEquals(plans.get("end1"), ending.path("endingId").asText());
        for (Team team : run.teams) for (HttpHeaders who : new HttpHeaders[]{team.captain, team.member}) {
            JsonNode reread = success(get("/api/game/session/view?activityId=" + run.activityId + "&perspective=PLAYER", who)).path("player").path("result");
            for (String field : new String[]{"endingId", "storyEndingId", "endingChapterId", "title", "summary", "success", "endedAt"})
                assertEquals(ending.path(field), reread.path(field), "六名玩家读回同一结局：" + field);
        }
        evidence.with("players").put("activityId", run.activityId).put("teams", 3).put("membersPerTeam", 2).put("votingTeams", 3)
                .put("supportingTeams", 2).put("rejectingTeams", 1).put("publicOnceCompetitionLost", true).put("counterRationDelta", -2).put("advancedA2Steps", answers("a2").size()).set("ending", ending);
    }

    private void readIds(JsonNode read, JsonNode layout) {
        chapterIds.clear(); nodes.clear();
        for (int c = 0; c < layout.size(); c++) {
            JsonNode chapter = read.path("chapters").get(c); chapterIds.add(chapter.path("id").asLong());
            for (int n = 0; n < layout.get(c).path("nodes").size(); n++)
                nodes.put(layout.get(c).path("nodes").get(n).path(0).asText(), chapter.path("cmsTopicNodeList").get(n).path("id").asLong());
        }
    }
    private void assertItemReferences(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("itemId".equals(field.getKey())) assertTrue(Arrays.asList(ITEMS).contains(field.getValue().asText()), field.toString());
                else assertItemReferences(field.getValue());
            }
        } else if (node.isArray()) for (JsonNode child : node) assertItemReferences(child);
    }
    private int stamina(Run run, Team team) { return stored(run, team).path("variables").path(STAMINA).asInt(); }
    private int proposalVersion(Run run, Team team, String proposal) {
        for (JsonNode row : player(run, team).path("world").path("proposals")) if (proposal.equals(row.path("proposalKey").asText())) return row.path("version").asInt();
        fail("提案未读回：" + proposal); return -1;
    }

    // ---------- 场次与队伍 ----------
    private final class Team {
        final String name; long id; HttpHeaders captain, member; long captainId, memberId;
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
                authorId, topicId, clubId, "海港 " + label, new java.sql.Timestamp(System.currentTimeMillis() - 3600000), new java.sql.Timestamp(System.currentTimeMillis() + 6 * 3600000));
        run.activityId = database.queryForObject("SELECT id FROM cms_activity WHERE topic_id=? AND name=? ORDER BY id DESC LIMIT 1", Long.class, topicId, "海港 " + label);
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
        for (Team team : run.teams) assertEquals(20, stamina(run, team), "初始体力");
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
        return receipt;
    }
    private JsonNode useFacility(Run run, Team team, String key) throws Exception {
        String facility = "tpl_node_" + nodes.get(key);
        ObjectNode op = json.createObjectNode().put("operationKind", "FACILITY").put("operationKey", facility);
        success(cmd(run, team.captain, "OPERATION_START", op));
        arrive(run, team, op.deepCopy());
        solve(run, team, key);
        JsonNode receipt = success(cmd(run, team.captain, "FACILITY_USE", json.createObjectNode().put("facilityKey", facility)));
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
    private void solve(Run run, Team team, String key) {
        long session = advancedSession(run, team, key);
        for (ObjectNode[] step : answers(key)) {
            JsonNode state = success(get("/api/play/advanced/state?sessionId=" + session, team.captain));
            ObjectNode body = json.createObjectNode().put("sessionId", session).put("version", state.path("version").asInt())
                    .put("idempotencyKey", run.activityId + "-" + run.label + "-" + team.name + "-" + key + "-" + (++run.request)).put("action", step[0].path("action").asText());
            body.set("payload", step[1]);
            success(post("/api/play/advanced/action", body, team.captain));
        }
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

    /** 取暖要求效果对象已到达：其他队未核验到达时不能结算；全部到达后一次结算、全场生效。 */
    private void heatTogether(Run run, Team user) throws Exception {
        String quest = questKey("heat");
        ObjectNode target = json.createObjectNode().put("questKey", quest);
        arrive(run, user, target.deepCopy());
        success(cmd(run, user.captain, "QUEST_START", target.deepCopy()));
        solve(run, user, "heat");
        int[] stamina = new int[3]; for (int i = 0; i < 3; i++) stamina[i] = stamina(run, run.t(i));
        int diesel = count(player(run, user), "diesel");
        JsonNode refused = cmd(run, user.captain, "QUEST_RESOLVE", target.deepCopy());
        rejected(refused); evidence.with("negative").set("arrivalRefusal", refused);
        JsonNode failure = GenericGameplayHttpE2eIT.requireGameCommandReceipt(refused.path("data"), "FAILED");
        assertEquals("GAME_TASK_COST_UNAVAILABLE", failure.path("reasonCode").asText());
        assertEquals("QUEST_RESOLVE", failure.path("action").asText());
        assertEquals(run.activityId, failure.path("activityId").asLong());
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM game_command_receipt WHERE id=? AND request_id=? AND activity_id=?"
                + " AND action='QUEST_RESOLVE' AND outcome='FAILED' AND reason_code='GAME_TASK_COST_UNAVAILABLE'",
                Integer.class, failure.path("receiptId").asLong(), failure.path("requestId").asText(), run.activityId));
        for (int i = 0; i < 3; i++) assertEquals(stamina[i], stamina(run, run.t(i)), "未全部到达时不结算");
        assertEquals(diesel, count(player(run, user), "diesel"));
        for (Team team : run.teams) if (team != user) arrive(run, team, target.deepCopy());
        success(cmd(run, user.captain, "QUEST_RESOLVE", target.deepCopy()));
        for (int i = 0; i < 3; i++) assertEquals(stamina[i] + 3, stamina(run, run.t(i)), "全部到达后全场加体力");
        assertEquals(diesel - 1, count(player(run, user), "diesel"));
        evidence.with("negative").put("heatRequiresVerifiedArrivalOfRecipients", true);
    }
    private int count(JsonNode view, String item) {
        int total = 0; for (JsonNode row : view.path("contents")) if (item.equals(row.path("itemId").asText(row.path("id").asText()))) total += row.path("quantity").asInt();
        return total;
    }
    private JsonNode stored(Run run, Team team) {
        try { return json.readTree(database.queryForObject("SELECT variables_json FROM game_story_state WHERE game_session_id=(SELECT id FROM game_session WHERE activity_id=?) AND owner_type='TEAM' AND owner_id=?", String.class, run.activityId, team.id)); }
        catch (Exception unreadable) { throw new IllegalStateException(unreadable); }
    }
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

    private ObjectNode rules() {
        ObjectNode rules = json.createObjectNode().put("schemaVersion", 1).put("stateEnabled", true).put("checkEnabled", false);
        rules.putObject("hp").put("enabled", true).put("init", 8).put("max", 12);
        rules.putObject("luck").put("enabled", false).put("init", 3).put("max", 3);
        rules.putArray("attributes").addObject().put("key", STAMINA).put("label", "体力").put("initial", 20).put("min", 0).put("max", 30);
        return rules;
    }
    private ObjectNode themeBase() {
        ObjectNode theme = json.createObjectNode().put("schemaVersion", 1).put("capacity", 40).put("hpName", "生命").put("actionName", "巡检次数").put("actionLimit", 8);
        ArrayNode items = theme.putArray("items");
        String[] names = {"淡水", "柴油", "电池", "零件", "药箱"};
        for (int i = 0; i < ITEMS.length; i++) items.addObject().put("id", ITEMS[i]).put("kind", "ITEM").put("name", names[i]).put("image", mediaFixture.approvedImage(context, database, authorId)).put("slotCost", 1);
        ObjectNode game = theme.putObject("gameplayRuntime").put("runtimeScope", "TEAM").put("worldMode", "WORLD_SHARED").put("allowTrade", true).put("votePolicy", "MAJORITY");
        game.putObject("survival").put("zeroHpPolicy", "BLOCK_HP_COST").put("actionLimitPolicy", "BLOCK_OVERFLOW");
        return theme;
    }
    private ObjectNode binding(String key) {
        JsonNode slots = templateConfigs.get(key).path("interactionRules").path("resourceSlots");
        ObjectNode binding = json.createObjectNode().put("templateId", templateIds.get(key)).put("slotDigest", GameplayTemplateRules.slotDigest(slots));
        ObjectNode values = binding.putObject("values"), items = binding.putObject("items");
        for (JsonNode slot : slots) {
            String k = slot.path("key").asText();
            if ("h".equals(k)) values.put(k, STAMINA); else if ("act".equals(k)) values.put(k, "sys.actions");
            else items.put(k, ITEMS["rfepm".indexOf(k)]);
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
        UmsMember member = new UmsMember(); member.setPhone(phone); member.setNickname(nickname); member.setStatus(1L); member.setDelFlag(0); member.setWxOpenId("e2e-http-reuse-" + phone); members.insertUmsMember(member);
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
    private JsonNode publishBound(long id, String registryVersion, HttpHeaders original) {
        HttpHeaders headers = new HttpHeaders(); headers.putAll(original); headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        long configVersion = database.queryForObject("SELECT config_version FROM cms_topic WHERE id=?", Long.class, id);
        org.springframework.util.LinkedMultiValueMap<String, String> body = new org.springframework.util.LinkedMultiValueMap<>();
        body.add("id", String.valueOf(id)); body.add("configVersion", String.valueOf(configVersion)); body.add("registryVersion", registryVersion);
        ResponseEntity<JsonNode> response = http.exchange("/api/topic/publish-draft", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        record("POST", "/api/topic/publish-draft", json.createObjectNode().put("id", id).put("configVersion", configVersion).put("registryVersion", registryVersion), response); return response.getBody();
    }
    private JsonNode success(JsonNode response) { return GenericGameplayHttpE2eIT.appliedResponseData(response); }
    private void rejected(JsonNode response) { GenericGameplayHttpE2eIT.rejectedGameCommand(response); }
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

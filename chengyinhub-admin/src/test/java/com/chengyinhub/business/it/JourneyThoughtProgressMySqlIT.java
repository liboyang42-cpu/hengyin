package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.NodeOutcome;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家「念头」卡(P5 认领 / P6 步数基线 / P7 步数想通)在真 MySQL 8 + 真 mapper XML 上的端到端。
 *
 * <p><b>为什么要真库</b>:{@code ThoughtClaimOnceAcceptanceTest} / {@code TopicRouteRuntimeServiceImplTest}
 * 把 {@code updateStateCas} 桩成恒返回 1,证明不了「拒绝时库里一个字节没变」「CAS 版本号真的 +1」
 * 「旧版本号写不进去」「别人的局没被碰」—— 这些只有 {@code topic_route_session} 的 JSON 列 +
 * {@code where id=? and member_id=? and version=? and status=0} 在真库上才成立。
 *
 * <p>服务走生产 {@link TopicRouteRuntimeServiceImpl} + Spring 事务代理(syncThoughts 上的
 * {@code @Transactional(READ_COMMITTED)} 真生效),到店证明是唯一的桩(外部依赖)。
 * 夹具与装配沿用 {@link JourneyFullFlowMySqlIT}(同一套 it-schema 与真实迁移),库为一类一库
 * {@code journey_thought_progress},结束 DROP 并回读确认。
 *
 * <p>念头块放在第 1 章 node 块<b>之后</b>:玩家投影在未完成节点处截断,所以节点完成前读不到这个念头。
 */
class JourneyThoughtProgressMySqlIT {

    private static final String SUITE = "journey_thought_progress";
    private static final long MEMBER = 97L;
    private static final long OTHER_MEMBER = 98L;
    private static final long TOPIC = 92001L;
    private static final long CHAPTER_1 = 92011L;
    private static final long CHAPTER_2 = 92012L;
    private static final long NODE_1 = 92021L;
    private static final long NODE_2 = 92022L;
    private static final long TEMPLATE_1 = 92031L;
    private static final long TEMPLATE_2 = 92032L;
    private static final String THOUGHT = "afternoon";
    private static final String THOUGHT_VAR = "thought." + THOUGHT;
    /** 第二个念头没有任何 thought 块:永远领不到,视图里也绝不该出现。 */
    private static final String UNCLAIMED = "night";
    private static final int NEED = 500;
    private static final int LUCK_MAX = 3;
    /** 读数日(纪元日)固定,不依赖跑测试那天的日期。 */
    private static final long DAY = 20_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String JOURNEY_RULES = "{\"schemaVersion\":1,"
            + "\"luck\":{\"enabled\":true,\"max\":" + LUCK_MAX + ",\"init\":1},"
            + "\"thoughts\":[{\"key\":\"" + THOUGHT + "\",\"name\":\"那个下午\",\"need\":" + NEED + "},"
            + "{\"key\":\"" + UNCLAIMED + "\",\"name\":\"那个夜晚\",\"need\":100}]}";

    private static final String CHAPTER_1_BLOCKS = "["
            + "{\"type\":\"text\",\"content\":\"你推开阿荣的店门\"},"
            + "{\"type\":\"node\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"thought\",\"thoughtKey\":\"" + THOUGHT + "\"}]";

    private static final String CHAPTER_2_BLOCKS = "[{\"type\":\"node\",\"nodeId\":" + NODE_2 + "}]";

    private static PooledDataSource dataSource;
    private static SqlSessionTemplate sql;
    private static DataSourceTransactionManager transactionManager;
    private static String database;
    private static String jdbcUrl;
    private static String adminUrl;

    @BeforeAll
    static void start() throws Exception {
        assertEquals("127.0.0.1", MybatisItDatabase.host(), "只允许显式本机 scratch");
        adminUrl = "jdbc:mysql://127.0.0.1:" + MybatisItDatabase.port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select version()")) {
            assertTrue(rs.next());
            assertTrue(rs.getString(1).startsWith("8.0."), "必须使用 MySQL 8.0,不能把 H2/别的版本当作通过");
        }
        jdbcUrl = MybatisItDatabase.url(SUITE);
        database = MybatisItDatabase.databaseName(SUITE);
        dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", jdbcUrl,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("journey-thought", new SpringManagedTransactionFactory(), dataSource));
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsTopicNode", CmsTopicNode.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsTopicChapter", CmsTopicChapter.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsMemberTemplate", CmsMemberTemplate.class);
        cfg.getTypeAliasRegistry().registerAlias("MemberTemplateVO", MemberTemplateVO.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsRegistration", CmsRegistration.class);
        cfg.getTypeAliasRegistry().registerAlias("PlayerNodeProgress", PlayerNodeProgress.class);
        cfg.getTypeAliasRegistry().registerAlias("ThemeStateKey", ThemeStateKey.class);
        cfg.getTypeAliasRegistry().registerAlias("TopicRouteSession", TopicRouteSession.class);
        cfg.getTypeAliasRegistry().registerAlias("TopicRouteAction", TopicRouteAction.class);
        for (String name : Arrays.asList("CmsTopicMapper", "CmsTopicNodeMapper", "CmsTopicChapterMapper",
                "CmsMemberTemplateMapper", "CmsRegistrationMapper", "PlayerNodeProgressMapper",
                "ThemeStateKeyMapper", "NpcProfileMapper", "TopicRouteSessionMapper")) {
            String path = "mapper/business/" + name + ".xml";
            try (InputStream in = JourneyThoughtProgressMySqlIT.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("mapper XML 不在 classpath:" + path);
                new XMLMapperBuilder(in, cfg, path, cfg.getSqlFragments()).parse();
            }
        }
        sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
        transactionManager = new DataSourceTransactionManager(dataSource);
        try (Connection c = open()) {
            // 与 JourneyFullFlowMySqlIT 同一份建表:存量表走 it-schema,路线局/状态键走仓库真实迁移
            ItSchema.apply(c, "it-schema/cms-topic-it-schema.sql");
            ItSchema.apply(c, "it-schema/journey-full-flow-it-schema.sql");
            ItSchema.apply(c, "it-schema/merchant-chapter-offer-it-schema.sql");
            ItSchema.applyMigrations(c, "sql/migration_theme_state_key_20260916.sql",
                    "sql/migration_20261022_theme_state_key_attribute_def.sql",
                    "sql/migration_20260823_topic_route_graph.sql",
                    "sql/migration_20260930_registration_first_successful_scan.sql",
                    "sql/migration_ordinary_chapter_offer_withdraw_20261003.sql",
                    "sql/migration_20261003_merchant_offer_withdrawal_columns.sql");
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (dataSource != null) dataSource.forceCloseAll();
        if (database == null) return;
        assertTrue(database.matches("chengyin_mybatis_journey_thought_progress[a-z0-9_]*"), "只许 DROP 本套 scratch");
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement()) {
            st.execute("drop database `" + database + "`");
            try (ResultSet rs = st.executeQuery(
                    "select count(*) from information_schema.schemata where schema_name = '" + database + "'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "scratch 清理必须回读确认");
            }
        }
    }

    @BeforeEach
    void resetFixture() throws Exception {
        try (Connection c = open(); Statement st = c.createStatement()) {
            for (String table : Arrays.asList("topic_route_action", "topic_route_session", "player_node_progress",
                    "cms_registration", "theme_state_key", "cms_member_template", "cms_topic_node",
                    "cms_topic_chapter", "cms_topic")) {
                st.execute("delete from " + table);
            }
            st.execute("insert into cms_topic(id,member_id,route_mode,published_config_version,journey_rules_json,"
                    + "name,status,user_status,del_flag,create_time,update_time) values(" + TOPIC + ",1,'LINEAR',1,'"
                    + JOURNEY_RULES + "','念头真库',1,1,0,now(),now())");
            st.execute("insert into cms_topic_chapter(id,topic_id,name,sort_id,required,blocks) values("
                    + CHAPTER_1 + "," + TOPIC + ",'第一章',1,1,'" + CHAPTER_1_BLOCKS + "')");
            st.execute("insert into cms_topic_chapter(id,topic_id,name,sort_id,required,blocks) values("
                    + CHAPTER_2 + "," + TOPIC + ",'第二章',2,1,'" + CHAPTER_2_BLOCKS + "')");
            st.execute("insert into cms_topic_node(id,topic_id,chapter_id,name,template_id,status,sort_id,xp_value,create_time)"
                    + " values(" + NODE_1 + "," + TOPIC + "," + CHAPTER_1 + ",'阿荣的店'," + TEMPLATE_1 + ",1,1,10,now()),("
                    + NODE_2 + "," + TOPIC + "," + CHAPTER_2 + ",'小满的店'," + TEMPLATE_2 + ",1,2,10,now())");
            st.execute("insert into cms_member_template(id,title,status,story_text) values("
                    + TEMPLATE_1 + ",'柜台',1,'阿荣'),(" + TEMPLATE_2 + ",'盖章',1,'小满')");
            for (long member : new long[]{MEMBER, OTHER_MEMBER}) {
                st.execute("insert into cms_registration(member_id,owner_type,owner_id,topic_id,registration_status,"
                        + "ticket_id,purchase_kind,create_time) values(" + member + ",1," + TOPIC + "," + TOPIC
                        + ",2,1,0,now())");
            }
        }
    }

    // ==================== P5 念头认领 ====================

    /**
     * P5:节点没完成前认领被拒,state_json 与 version 原样;完成节点后认领落库且 version+1;
     * 再认领不动进度;另一名成员的局全程不被碰;拿旧 version 走 CAS 一行都写不进去。
     */
    @Test
    void claimIsRefusedBeforeNodeCompletionAndPersistsOnceAfterward() throws Exception {
        ITopicRouteRuntimeService runtime = runtime();
        runtime.state(MEMBER, TOPIC, 0L);
        runtime.state(OTHER_MEMBER, TOPIC, 0L);
        Map<String, Object> otherBefore = sessionRow(OTHER_MEMBER);
        Map<String, Object> before = sessionRow(MEMBER);

        // ── 节点未完成:念头块在 node 块之后,投影读不到 ⇒ 拒绝,库里不变 ──
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.singletonList(THOUGHT), -1, DAY));
        assertEquals("尚未读到这个念头", refused.getMessage());
        assertEquals(before, sessionRow(MEMBER), "被拒的认领不得改动 state_json / version / update_time");
        assertFalse(state(MEMBER).path("variables").has(THOUGHT_VAR), "被拒的认领不得留下进度变量");
        assertEquals("念头不存在", assertThrows(IllegalArgumentException.class,
                () -> runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.singletonList("ghost"), -1, DAY))
                .getMessage());
        assertEquals(before, sessionRow(MEMBER), "不存在的念头同样一行不写");

        // ── 完成第 1 站后认领:进度变量 0 落库,CAS version +1 ──
        advanceNode(runtime, MEMBER, NODE_1);
        int afterAdvance = version(MEMBER);
        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.singletonList(THOUGHT), -1, DAY);
        assertEquals(afterAdvance + 1, version(MEMBER), "认领成功走一次 CAS:version 恰好 +1");
        JsonNode claimed = state(MEMBER);
        assertEquals(0, claimed.path("variables").path(THOUGHT_VAR).asInt(-1), "认领落库:进度从 0 开始");
        assertFalse(claimed.path("variables").has("thought." + UNCLAIMED), "没认领的念头不替玩家开始");

        // ── 先攒一点进度,再重复认领:进度不被重置 ──
        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.<String>emptyList(), 1000, DAY);
        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.<String>emptyList(), 1120, DAY);
        assertEquals(120, state(MEMBER).path("variables").path(THOUGHT_VAR).asInt());
        JsonNode beforeReclaim = state(MEMBER);
        runtime.syncThoughts(MEMBER, TOPIC, 0L, Arrays.asList(THOUGHT, THOUGHT), -1, DAY);
        JsonNode reclaimed = state(MEMBER);
        assertEquals(beforeReclaim.path("variables"), reclaimed.path("variables"), "重复认领不改任何进度变量");
        assertEquals(beforeReclaim.path("tags"), reclaimed.path("tags"), "重复认领不加标签");

        // ── 另一名成员:同主题的局全程原样 ──
        assertEquals(otherBefore, sessionRow(OTHER_MEMBER), "别人的 topic_route_session 行不得被碰");
        assertEquals("尚未读到这个念头", assertThrows(IllegalArgumentException.class,
                () -> runtime.syncThoughts(OTHER_MEMBER, TOPIC, 0L, Collections.singletonList(THOUGHT), -1, DAY))
                .getMessage(), "我完成了节点不等于别人读到了念头");
        assertEquals(otherBefore, sessionRow(OTHER_MEMBER));

        // ── 旧 version 的 CAS:mapper 真 SQL 命中 0 行,库里不变 ──
        TopicRouteSessionMapper mapper = sql.getMapper(TopicRouteSessionMapper.class);
        TopicRouteSession stale = mapper.selectByOwner(0L, TOPIC, MEMBER, "default");
        assertNotNull(stale);
        Map<String, Object> current = sessionRow(MEMBER);
        ObjectNode forged = (ObjectNode) JSON.readTree(stale.getStateJson());
        ((ObjectNode) forged.get("variables")).put(THOUGHT_VAR, NEED);
        stale.setStateJson(JSON.writeValueAsString(forged));
        stale.setVersion(stale.getVersion() - 1);
        stale.setUpdateTime(new java.util.Date());
        assertEquals(0, mapper.updateStateCas(stale), "旧 version 的 CAS 必须命中 0 行");
        stale.setVersion(stale.getVersion() + 1);
        stale.setMemberId(OTHER_MEMBER);
        assertEquals(0, mapper.updateStateCas(stale), "别人的 member_id 带着我的局 id 也写不进去");
        assertEquals(current, sessionRow(MEMBER), "两次被拒的 CAS 都不得改动本局");
    }

    // ==================== P6 步数基线(同日只认单调增量) ====================

    /**
     * P6:同日 1000 → 1000 → 800,持久化的 last 停在 1000、进度增量为 0;次日 300 整数计入。
     */
    @Test
    void sameDayLowerReadingKeepsBaselineAndNextDayCountsFully() throws Exception {
        ITopicRouteRuntimeService runtime = runtime();
        runtime.state(MEMBER, TOPIC, 0L);
        advanceNode(runtime, MEMBER, NODE_1);
        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.singletonList(THOUGHT), -1, DAY);
        assertFalse(state(MEMBER).path("variables").has("sys.steps.last"), "没有读数的认领不建立步数基线");

        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.<String>emptyList(), 1000, DAY);
        assertSteps(1000, DAY, 0, "首个读数只建立基线,不算进度");
        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.<String>emptyList(), 1000, DAY);
        assertSteps(1000, DAY, 0, "同日同读数增量为 0");
        int beforeDrop = version(MEMBER);
        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.<String>emptyList(), 800, DAY);
        assertSteps(1000, DAY, 0, "同日读数回落:基线停在 1000,不倒扣也不重算");
        assertEquals(beforeDrop + 1, version(MEMBER), "回落那次照常走 CAS,只是增量为 0");

        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.<String>emptyList(), 1100, DAY);
        assertSteps(1100, DAY, 100, "回落后再涨:只认超过 1000 的部分");

        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.<String>emptyList(), 300, DAY + 1);
        assertSteps(300, DAY + 1, 400, "次日 300 整数计入,基线换成次日读数");

        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.<String>emptyList(), 5000, DAY);
        assertSteps(300, DAY + 1, 400, "迟到的前一日读数不改基线也不加进度");
    }

    // ==================== P7 步数想通 ====================

    /**
     * P7:进度到 need 即想通 —— 落 thought.done 标签、sys.luck.max 恰好 +1;再同步不重复加;
     * 新建的服务实例 state() 回读:想通的念头在,没认领的念头不下发。
     */
    @Test
    void reachingNeedMarksDoneAndRaisesLuckMaxExactlyOnce() throws Exception {
        ITopicRouteRuntimeService runtime = runtime();
        runtime.state(MEMBER, TOPIC, 0L);
        advanceNode(runtime, MEMBER, NODE_1);
        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.singletonList(THOUGHT), 1000, DAY);
        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.<String>emptyList(), 1300, DAY);
        JsonNode partial = state(MEMBER);
        assertEquals(300, partial.path("variables").path(THOUGHT_VAR).asInt());
        assertFalse(tags(partial).contains(THOUGHT_VAR + ".done"), "没到 need 不算想通");
        assertFalse(partial.path("variables").has("sys.luck.max"), "没想通不改幸运上限");

        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.<String>emptyList(), 1650, DAY);
        JsonNode done = state(MEMBER);
        assertEquals(NEED, done.path("variables").path(THOUGHT_VAR).asInt(), "进度封顶在 need");
        assertTrue(tags(done).contains(THOUGHT_VAR + ".done"), "到 need 落 done 标签");
        assertEquals(LUCK_MAX + 1, done.path("variables").path("sys.luck.max").asInt(), "想通:幸运上限 +1");

        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.singletonList(THOUGHT), 3000, DAY);
        runtime.syncThoughts(MEMBER, TOPIC, 0L, Collections.<String>emptyList(), 900, DAY + 1);
        JsonNode after = state(MEMBER);
        assertEquals(LUCK_MAX + 1, after.path("variables").path("sys.luck.max").asInt(), "幸运上限只 +1 一次");
        assertEquals(NEED, after.path("variables").path(THOUGHT_VAR).asInt(), "想通之后不再累计");
        assertEquals(1, Collections.frequency(tags(after), THOUGHT_VAR + ".done"), "done 标签只有一个");

        // ── 新服务实例(无任何内存状态)回读:视图只来自库 ──
        TopicRouteState view = runtime().state(MEMBER, TOPIC, 0L);
        assertEquals(2, view.getThoughtCount(), "规则里共两个念头");
        List<Map<String, Object>> thoughts = view.getThoughts();
        assertNotNull(thoughts, "想通的念头要下发");
        List<Object> keys = new ArrayList<Object>();
        for (Map<String, Object> row : thoughts) keys.add(row.get("key"));
        assertEquals(Collections.singletonList(THOUGHT), keys, "没认领的念头不下发(剧透闸)");
        assertEquals(Boolean.TRUE, thoughts.get(0).get("done"));
        assertEquals(NEED, thoughts.get(0).get("progress"));
        assertEquals(NEED, thoughts.get(0).get("need"));
    }

    // ==================== 装配 ====================

    /** 每次都新建实例:生产服务 + 真 mapper + 真 Spring 事务代理。 */
    private static ITopicRouteRuntimeService runtime() {
        TopicRouteRuntimeServiceImpl target = new TopicRouteRuntimeServiceImpl(
                sql.getMapper(TopicRouteSessionMapper.class), sql.getMapper(CmsTopicMapper.class),
                sql.getMapper(CmsTopicNodeMapper.class), sql.getMapper(CmsMemberTemplateMapper.class),
                sql.getMapper(PlayerNodeProgressMapper.class), null, sql.getMapper(CmsRegistrationMapper.class),
                null, new ArrivedShopDayProof(), () -> 12345L, () -> 10, Clock.systemUTC(),
                RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(target, "cmsTopicChapterMapper", sql.getMapper(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(target, "themeStateKeyMapper", sql.getMapper(ThemeStateKeyMapper.class));
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(ITopicRouteRuntimeService.class);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (ITopicRouteRuntimeService) proxyFactory.getProxy();
    }

    /** 完成事实(player_node_progress status=1)先落库,推进才有节点完成回执。 */
    private static void advanceNode(ITopicRouteRuntimeService runtime, long member, long nodeId) throws Exception {
        exec("insert into player_node_progress(member_id,activity_id,topic_id,node_id,status,del_flag,create_time)"
                + " values(" + member + ",0," + TOPIC + "," + nodeId + ",1,0,now())");
        TopicRouteState view = runtime.advance(member, TOPIC, 0L, version(member), "adv:" + nodeId,
                "CHOICE", new NodeOutcome(nodeId, "COMPLETED", "progress-" + member + "-" + nodeId));
        assertNotNull(view.getSessionId());
        JsonNode completed = state(member).path("completedNodeIds");
        boolean found = false;
        for (JsonNode id : completed) found |= id.asLong() == nodeId;
        assertTrue(found, "前置:节点完成必须写进本局 completedNodeIds,实际 " + completed);
    }

    private void assertSteps(int last, long day, int progress, String message) throws Exception {
        JsonNode variables = state(MEMBER).path("variables");
        assertEquals(last, variables.path("sys.steps.last").asInt(-1), message + "(sys.steps.last)");
        assertEquals(day, variables.path("sys.steps.day").asLong(-1), message + "(sys.steps.day)");
        assertEquals(progress, variables.path(THOUGHT_VAR).asInt(-1), message + "(念头进度)");
    }

    // ==================== 读侧小工具 ====================

    private static Map<String, Object> sessionRow(long member) throws Exception {
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select id,state_json,version,status,current_node_id,update_time"
                     + " from topic_route_session where topic_id=" + TOPIC + " and member_id=" + member)) {
            assertTrue(rs.next(), "本局必须已建:member=" + member);
            Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
            }
            assertFalse(rs.next(), "同主题自玩只许一局:member=" + member);
            return row;
        }
    }

    private static JsonNode state(long member) throws Exception {
        return JSON.readTree(String.valueOf(sessionRow(member).get("state_json")));
    }

    private static int version(long member) throws Exception {
        return ((Number) sessionRow(member).get("version")).intValue();
    }

    private static List<String> tags(JsonNode state) {
        List<String> out = new ArrayList<String>();
        for (JsonNode tag : state.path("tags")) out.add(tag.asText());
        return out;
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(jdbcUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
    }

    private static void exec(String statement) throws Exception {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute(statement);
        }
    }

    /** 到店证明是外部依赖(店内扫码写 checkin_time);念头卡不考它,恒为已到店。 */
    private static final class ArrivedShopDayProof implements IShopDayProofService {
        @Override
        public PlayerNodeProgress markArrived(Long memberId, Long activityId, CmsTopicNode node) { return null; }

        @Override
        public PlayerNodeProgress submitPhoto(Long memberId, Long activityId, Long nodeId, String picUrl) { return null; }

        @Override
        public boolean hasArrived(Long memberId, Long activityId, Long nodeId) { return true; }

        @Override
        public boolean markGameDone(Long memberId, Long activityId, Long topicId, Long nodeId) { return true; }
    }
}

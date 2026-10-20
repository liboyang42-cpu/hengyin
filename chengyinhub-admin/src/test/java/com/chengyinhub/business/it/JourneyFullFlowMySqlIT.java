package com.chengyinhub.business.it;

import com.chengyinhub.business.service.support.LegacyCmsMutationGuard;
import com.chengyinhub.business.service.support.LegacyCmsTemplateBodyReader;
import com.chengyinhub.business.mapper.LegacyCmsTemplateReadMapper;
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
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.impl.CmsMemberTemplateServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicChapterServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicNodeServiceImpl;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayEncounterServiceImpl;
import com.chengyinhub.business.service.impl.PlayJourneyStoryServiceImpl;
import com.chengyinhub.business.service.impl.PlayerNodeProgressServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R12-E2E 旅程全链路真库端到端:一个线性主题从建局走到结局,全程真 MySQL 8 + 真 mapper。
 *
 * <p><b>为什么必须是真库 IT</b>:{@code topic_route_session.state_json} / {@code topic_route_action}
 * 的 JSON 列、唯一键与 CAS(version)语义只有真 MySQL 才有;单测里被 Mock 掉的
 * 「回执写没写进库、唯一键拦不拦得住并发」恰恰是这一族缺陷的判据(mock 只能证明调了方法)。
 *
 * <p>链路覆盖(全走生产服务与真表,外部依赖只用桩):
 * 线性建局(sys.hp/sys.luck 初值)→ B7 Encounter 的锁定与 missingKeys → 到店桩 →
 * 两个问题的问与重放 → 判错首错免罚/再错扣 1 → 固定骰点 roll → settle(扣血/失败效果/重放不重复扣)
 * → 节点推进(节点 effects 写入 clue/relation、站末恢复)→ 第 2 站解锁 → 章节 stage-end
 * → 结局按 relation 三态选尾声与 found/total。
 *
 * <p>并发两条:结算幂等靠 {@code uk_topic_route_action} 唯一键(重放同一回执),
 * 负控用测试内临时 DDL 摘掉唯一键,同一时序下第二次结算会真的再扣一次血 —— 摘掉后断言红,
 * 还原后回读 information_schema 确认唯一键原样在位。
 *
 * <p>⚠️ 每次运行拿一类一库 scratch({@link MybatisItDatabase}),结束 DROP 后回读确认为 0。
 */
class JourneyFullFlowMySqlIT {

    private static final String SUITE = "journey_full_flow";
    private static final long MEMBER = 97L;
    private static final long TOPIC = 91001L;
    private static final long CHAPTER_1 = 91011L;
    private static final long CHAPTER_2 = 91012L;
    private static final long NODE_1 = 91021L;
    private static final long NODE_2 = 91022L;
    private static final long TEMPLATE_1 = 91031L;
    private static final long TEMPLATE_2 = 91032L;
    private static final long NPC_1 = 91041L;
    private static final long NPC_2 = 91042L;
    private static final long NPC_3 = 91043L;
    /** R11-C 口径:尾声三态读显式 relationKey(已注册的 relation.* 键),不再由 npcId 推隐含键。 */
    private static final String REL_ARONG = "relation.arong";
    private static final String REL_XIAOMAN = "relation.xiaoman";
    private static final String REL_LAOZHOU = "relation.laozhou";
    private static final String CHECK_ID = "ask_arong";
    /** medium dc=13,固定骰点 10 = 必败(且 mod 依赖的 clue 此时还没拿到,不生效)。 */
    private static final int D20_FAIL = 10;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String JOURNEY_RULES = "{\"schemaVersion\":1,\"checkEnabled\":true,"
            + "\"hp\":{\"enabled\":true,\"max\":10,\"init\":8},"
            + "\"luck\":{\"enabled\":true,\"max\":3,\"init\":2},"
            + "\"tiers\":{\"medium\":{\"dc\":13,\"damage\":2}},"
            + "\"recovery\":{\"stationEndIfHurt\":2,\"stageRestHpFloor\":0,\"stageRestLuckFloor\":0}}";

    private static final String JOURNEY_STORY = "{\"schemaVersion\":1,"
            + "\"call\":{\"npcId\":" + NPC_1 + ",\"opener\":\"喂?\",\"brief\":\"今晚这通电话\","
            + "\"options\":[{\"id\":\"a\",\"line\":\"接\",\"reply\":\"我听着\"},"
            + "{\"id\":\"b\",\"line\":\"不接\",\"reply\":\"晚点再说\"}]},"
            + "\"stages\":["
            + "{\"chapterId\":" + CHAPTER_1 + ",\"question\":\"账本是谁的?\",\"end\":{\"changed\":["
            + "{\"when\":null,\"text\":\"你合上了第一章\"},"
            + "{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},"
            + "\"text\":\"阿荣把账本推了过来\"}],\"open\":\"第一章收了\"}},"
            + "{\"chapterId\":" + CHAPTER_2 + ",\"question\":\"钥匙开哪扇门?\",\"end\":{\"changed\":["
            + "{\"when\":null,\"text\":\"你合上了第二章\"},"
            + "{\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},"
            + "\"text\":\"你带着一路的慌乱离开\"}],\"open\":\"第二章收了\"}}],"
            + "\"ending\":{\"dispositionNodeId\":null,\"endings\":["
            + "{\"code\":\"ally\",\"title\":\"同盟\",\"summary\":\"账本归位\","
            + "\"when\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}]},"
            + "{\"code\":\"default\",\"title\":\"路人\",\"summary\":\"各走各路\",\"fallback\":true}],"
            + "\"epilogues\":["
            + "{\"npcId\":" + NPC_1 + ",\"relationKey\":\"" + REL_ARONG + "\","
            + "\"ally\":\"阿荣把钥匙交给你\",\"neutral\":\"阿荣点点头\","
            + "\"guarded\":\"阿荣背过身去\"},"
            + "{\"npcId\":" + NPC_2 + ",\"relationKey\":\"" + REL_XIAOMAN + "\","
            + "\"ally\":\"小满约你下次再来\",\"neutral\":\"小满收拾着柜台\","
            + "\"guarded\":\"小满躲开了你的目光\"},"
            + "{\"npcId\":" + NPC_3 + ",\"relationKey\":\"" + REL_LAOZHOU + "\","
            + "\"ally\":\"老周拍了拍你的肩\",\"neutral\":\"老周在门口抽着烟\","
            + "\"guarded\":\"老周锁上了门\"}],"
            + "\"exhibits\":["
            + "{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},"
            + "\"text\":\"账本残页已归位\"},"
            + "{\"when\":{\"var\":\"clue.photo_story\",\"op\":\"GTE\",\"value\":1},"
            + "\"text\":\"老照片已归档\"}],"
            + "\"badges\":[{\"when\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}],"
            + "\"title\":\"阿荣的信任\"},{\"title\":\"路过的客人\",\"fallback\":true}],"
            + "\"optionalClues\":[\"clue.ledger_page\",\"clue.photo_story\"]}}";

    /**
     * R11-C 口径:尾声三态读 {@code epilogues[].relationKey}(显式、已注册、类型 relation),
     * 块配置 effects 写的是同名关系键({@code relation.arong} 等),不再用数字 {@code relation.<npcId>}。
     * 这条写法同时过 JourneyStoryValidator 与 AdvancedGameConfigValidator 的 v3 命名空间正则,
     * 与 R12-SEED 演示配置(seventh-pier/journey-story.json)同一口径。
     */
    private static final String NODE_1_CONFIG = "{\"schemaVersion\":1,"
            + "\"check\":{\"enabled\":true,\"checkId\":\"" + CHECK_ID + "\",\"skill\":\"察言\",\"tier\":\"medium\","
            + "\"mods\":[{\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},"
            + "\"value\":2,\"label\":\"手握账本残页\"}],"
            + "\"successEffects\":[{\"op\":\"INC\",\"var\":\"counter.checks_passed\",\"value\":1}],"
            + "\"failEffects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.hurried\"}],"
            + "\"successText\":\"阿荣把话收住了\",\"failText\":\"阿荣皱了皱眉\","
            + "\"failCostTag\":\"tag.hurried\"},"
            + "\"effects\":[{\"op\":\"SET\",\"var\":\"clue.ledger_page\",\"value\":1},"
            + "{\"op\":\"INC\",\"var\":\"" + REL_ARONG + "\",\"value\":1}]}";

    private static final String NODE_2_CONFIG = "{\"schemaVersion\":1,"
            + "\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1}],"
            + "\"effects\":[{\"op\":\"INC\",\"var\":\"" + REL_LAOZHOU + "\",\"value\":-1}],"
            + "\"qa\":{\"enabled\":true}}";

    /** R18:叙事段住在章节故事流(顺序 = 阅读序,归属看 nodeId);模板只留判定配置。 */
    private static final String CHAPTER_1_BLOCKS = "["
            + "{\"type\":\"text\",\"beat\":\"brief\",\"field\":\"question\",\"content\":\"今晚要问什么?\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"brief\",\"field\":\"carry\",\"content\":\"带着账本的问题\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"route\",\"field\":\"walk\",\"content\":\"步行六分钟\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\",\"content\":\"阿荣抬头看你\",\"npcId\":" + NPC_1 + ",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"aside\",\"content\":\"柜台后面有一摞纸\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"q\",\"qid\":\"night\",\"content\":\"今晚几点关门?\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"a\",\"qid\":\"night\",\"content\":\"十点整\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"q\",\"qid\":\"ledger\",\"content\":\"账本放哪?\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"a\",\"qid\":\"ledger\",\"content\":\"柜台底下\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"unlock\",\"field\":\"hook\",\"content\":\"阿荣似乎还知道点什么\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"node\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"title\",\"content\":\"柜台问答\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"line\",\"content\":\"阿荣记下了你的话\",\"npcId\":" + NPC_1 + ",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"lineIfFail\",\"content\":\"阿荣没有松口\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"fact\",\"content\":\"柜台上的收音机一直开着\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"fact\",\"content\":\"账本残页被你收进背包\","
            + "\"when\":{\"var\":\"clue.ledger_page\",\"op\":\"GTE\",\"value\":1},\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"outcome\",\"field\":\"open\",\"content\":\"下一站是小满的店\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"deliver\",\"field\":\"reward\",\"content\":\"阿荣的旧钥匙\",\"claimLater\":true,\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"deliver\",\"field\":\"line\",\"content\":\"阿荣把钥匙递过来\",\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"revisit\",\"field\":\"change\",\"content\":\"阿荣看见你还在喘\","
            + "\"when\":{\"op\":\"HAS_TAG\",\"value\":\"tag.hurried\"},\"nodeId\":" + NODE_1 + "},"
            + "{\"type\":\"text\",\"beat\":\"revisit\",\"field\":\"change\",\"content\":\"柜台后的收音机换了台\",\"nodeId\":" + NODE_1 + "}]";

    private static final String CHAPTER_2_BLOCKS = "["
            + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\",\"content\":\"小满在等你\",\"npcId\":" + NPC_2 + ",\"nodeId\":" + NODE_2 + "},"
            + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"q\",\"qid\":\"receipt\",\"content\":\"钥匙带了吗?\",\"nodeId\":" + NODE_2 + "},"
            + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"a\",\"qid\":\"receipt\",\"content\":\"带了\",\"nodeId\":" + NODE_2 + "},"
            + "{\"type\":\"node\",\"nodeId\":" + NODE_2 + "},"
            + "{\"type\":\"text\",\"beat\":\"deliver\",\"field\":\"reward\",\"content\":\"小满的章\",\"claimLater\":true,\"nodeId\":" + NODE_2 + "},"
            + "{\"type\":\"text\",\"beat\":\"deliver\",\"field\":\"line\",\"content\":\"小满在册子上盖章\",\"nodeId\":" + NODE_2 + "}]";

    /** 已注册状态键:契约 v3 的命名空间与类型一一对应(clue→flag / relation→relation / tag→tag / counter→counter)。 */
    private static final String[][] STATE_KEYS = {
            {"clue.ledger_page", "flag", "账本残页"},
            {"clue.photo_story", "flag", "老照片"},
            {REL_ARONG, "relation", "阿荣"},
            {REL_XIAOMAN, "relation", "小满"},
            {REL_LAOZHOU, "relation", "老周"},
            {"tag.hurried", "tag", "一路慌乱"},
            {"counter.checks_passed", "counter", "检定通过"},
    };

    private static PooledDataSource dataSource;
    private static SqlSessionTemplate sql;
    private static DataSourceTransactionManager transactionManager;
    private static PlayJourneyStoryServiceImpl storyService;
    private static PlayEncounterServiceImpl encounterService;
    private static StubShopDayProofService shopProof;
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
        cfg.setEnvironment(new Environment("journey-full-flow", new SpringManagedTransactionFactory(), dataSource));
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
                "CmsMemberTemplateMapper", "LegacyCmsTemplateReadMapper", "CmsRegistrationMapper", "PlayerNodeProgressMapper",
                "ThemeStateKeyMapper", "NpcProfileMapper", "TopicRouteSessionMapper")) {
            String path = "mapper/business/" + name + ".xml";
            try (InputStream in = JourneyFullFlowMySqlIT.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("mapper XML 不在 classpath:" + path);
                new XMLMapperBuilder(in, cfg, path, cfg.getSqlFragments()).parse();
            }
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        sql = new SqlSessionTemplate(factory);
        transactionManager = new DataSourceTransactionManager(dataSource);
        try (Connection c = DriverManager.getConnection(jdbcUrl, MybatisItDatabase.user(), MybatisItDatabase.password())) {
            createSchema(c);
        }
        shopProof = new StubShopDayProofService();
        wireServices();
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (dataSource != null) dataSource.forceCloseAll();
        if (database == null) return;
        assertTrue(database.matches("chengyin_mybatis_journey_full_flow[a-z0-9_]*"), "只许 DROP 本套 scratch");
        try (Connection c = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = c.createStatement()) {
            st.execute("drop database `" + database + "`");
            try (ResultSet rs = st.executeQuery(
                    "select count(*) from information_schema.schemata where schema_name = '" + database + "'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "scratch 清理必须回读确认");
            }
        }
        System.out.println("Cleaned unique MySQL 8 scratch: " + database);
    }

    @BeforeEach
    void resetFixture() throws Exception {
        shopProof.arrived.set(false);
        wipe();
        seedFixture();
    }

    // ==================== 1. 全链路 ====================

    @Test
    void linearJourneyWalksFromBuildToThreeStateEpilogue() throws Exception {
        ITopicRouteRuntimeService runtime = runtimeWithDice(fixedDice(D20_FAIL));

        // ── 建局:sys.hp / sys.luck 初值(旅程数值规则里显式配的 8 / 2) ──
        TopicRouteState built = runtime.state(MEMBER, TOPIC, 0L);
        assertNotNull(built.getSessionId(), "配了 journeyRules 的线性主题必须建出权威局");
        assertEquals(8, hp(), "建局即写 sys.hp=hp.init");
        assertEquals(2, luck(), "建局即写 sys.luck=luck.init");
        assertEquals(NODE_1, stateJson().path("currentNodeId").asLong(), "线性局从排序第一个节点开始");
        assertEquals("本局尚未完成",
                assertThrows(ServiceException.class, () -> storyService.ending(MEMBER, TOPIC)).getMessage(),
                "没完成就不给结局");

        // ── 第 2 站 encounter:requires 依赖第 1 站的 clue ⇒ 锁定 + missingKeys 精确 ──
        Map<String, Object> lockedNode2 = encounterService.resolve(MEMBER, TOPIC, NODE_2);
        assertEquals(Boolean.TRUE, lockedNode2.get("locked"));
        assertEquals(Collections.singletonList("clue.ledger_page"), lockedNode2.get("missingKeys"));
        assertEquals(Boolean.FALSE, lockedNode2.get("arrived"), "还没到店");
        assertEquals(Collections.emptyList(), lockedNode2.get("allowedActions"), "锁着/没到店不给动作");

        // ── 第 1 站:没锁(没配 requires),但没到店也不给动作 ──
        Map<String, Object> node1 = encounterService.resolve(MEMBER, TOPIC, NODE_1);
        assertEquals(Boolean.FALSE, node1.get("locked"));
        assertEquals(Boolean.FALSE, node1.get("arrived"));
        assertEquals(Collections.emptyList(), node1.get("allowedActions"));
        assertEquals(Collections.emptyList(), node1.get("missingKeys"));

        // 叙事时机:问到之前 question 里连 a 字段都不该有(null 也是一种泄题)
        List<Map<String, Object>> questionsBefore = rows(map(node1.get("enter")).get("questions"));
        assertEquals(2, questionsBefore.size());
        assertFalse(questionsBefore.get(0).containsKey("a"), "没问过的问题不下发 a");
        assertEquals(Boolean.FALSE, questionsBefore.get(0).get("asked"));
        // 到店前 check 的 mod 条件不成立(此时还没拿到 clue)
        assertEquals(Boolean.FALSE, rows(map(node1.get("check")).get("mods")).get(0).get("held"));
        assertNull(node1.get("outcome"), "outcome 只在本节点完成后下发");
        Map<String, Object> deliverBefore = map(node1.get("deliver"));
        assertEquals(Boolean.TRUE, deliverBefore.get("claimLater"));
        assertFalse(deliverBefore.containsKey("line"), "deliver.line 只在完成后下发");
        assertEquals("阿荣似乎还知道点什么", map(node1.get("unlock")).get("hook"));

        // ── 到店前问问题必须被拒 ──
        assertThrows(IllegalStateException.class, () -> runtime.ask(MEMBER, TOPIC, NODE_1, "night"));

        // ── 第 1 站到店(到店证明是外部依赖,用桩;真实验里它由店内扫码写下)──
        shopProof.arrived.set(true);
        Map<String, Object> askedNight = runtime.ask(MEMBER, TOPIC, NODE_1, "night");
        Map<String, Object> askedLedger = runtime.ask(MEMBER, TOPIC, NODE_1, "ledger");
        Map<String, Object> askedNightReplay = runtime.ask(MEMBER, TOPIC, NODE_1, "night");
        assertEquals("十点整", askedNight.get("a"));
        assertEquals("柜台底下", askedLedger.get("a"));
        // 回执落的是 JSON 列,MySQL 会把对象键重排 —— 逐字段比语义,不比字符串序
        assertEquals(askedNight.get("q"), askedNightReplay.get("q"), "同 id 重放返回原回执");
        assertEquals(askedNight.get("a"), askedNightReplay.get("a"));
        assertEquals(askedNight.get("questionId"), askedNightReplay.get("questionId"));
        assertEquals(((Number) askedNight.get("nodeId")).longValue(),
                ((Number) askedNightReplay.get("nodeId")).longValue());
        assertEquals(((Number) askedNight.get("stateVersion")).intValue(),
                ((Number) askedNightReplay.get("stateVersion")).intValue());
        assertEquals(1, intOf("select count(*) from topic_route_action where action_id='ask:" + NODE_1 + ":night'"),
                "重放不重复登记动作");
        JsonNode askedState = stateJson();
        assertEquals(1, askedState.path("variables").path("sys.asked." + NODE_1 + ".night").asInt());
        assertEquals(1, askedState.path("variables").path("sys.asked." + NODE_1 + ".ledger").asInt());
        List<Map<String, Object>> questionsAfter = rows(map(encounterService.resolve(MEMBER, TOPIC, NODE_1)
                .get("enter")).get("questions"));
        assertEquals(Boolean.TRUE, questionsAfter.get(0).get("asked"));
        assertEquals("十点整", questionsAfter.get(0).get("a"), "问过之后才随响应给出 a");
        assertEquals(Collections.singletonList("check"),
                encounterService.resolve(MEMBER, TOPIC, NODE_1).get("allowedActions"), "到店且没锁:给 check");

        // ── 判错:首错免罚,再错扣 1 ──
        runtime.recordMistake(MEMBER, TOPIC, NODE_1);
        JsonNode afterFirst = stateJson();
        assertEquals(1, afterFirst.path("variables").path("sys.mistakes." + NODE_1).asInt());
        assertEquals(8, afterFirst.path("variables").path("sys.hp").asInt(), "每个节点第一次判错免罚");
        assertFalse(afterFirst.path("variables").has("sys.damage." + NODE_1), "免罚那次不算扣过血");
        runtime.recordMistake(MEMBER, TOPIC, NODE_1);
        JsonNode afterSecond = stateJson();
        assertEquals(2, afterSecond.path("variables").path("sys.mistakes." + NODE_1).asInt());
        assertEquals(7, afterSecond.path("variables").path("sys.hp").asInt(), "第二次判错扣 1 血");
        assertEquals(1, afterSecond.path("variables").path("sys.damage." + NODE_1).asInt());

        // ── roll:固定骰点 10 < medium dc 13 ⇒ 必败;掷骰回执不得携带文案/效果 ──
        PlayCheckReceipt roll = runtime.roll(MEMBER, TOPIC, NODE_1, CHECK_ID);
        assertFalse(roll.isSettled());
        assertNull(roll.getText(), "未结算的回执绝不携带 successText/failText");
        assertEquals(13, roll.getDc());
        assertEquals(10, roll.getKept());
        assertEquals(10, roll.getTotal(), "clue 还没到手,mod 不生效");
        assertEquals(Boolean.FALSE, roll.getSuccess());
        assertEquals(Boolean.FALSE, roll.getMods().get(0).isApplied());
        assertEquals(7, roll.getHp(), "掷骰回执带上当前读数");

        // ── settle:失败扣 medium damage=2、failCostTag 落 tag、写失败文案 ──
        PlayCheckReceipt settled = runtime.settle(MEMBER, TOPIC, NODE_1, CHECK_ID);
        assertTrue(settled.isSettled());
        assertEquals(Boolean.FALSE, settled.getSuccess());
        assertEquals("阿荣皱了皱眉", settled.getText());
        assertEquals(5, settled.getHp(), "7 - medium 2");
        JsonNode settledState = stateJson();
        assertEquals(5, settledState.path("variables").path("sys.hp").asInt());
        assertEquals(3, settledState.path("variables").path("sys.damage." + NODE_1).asInt(), "判错 1 + 检定 2");
        assertTrue(tags(settledState).contains("tag.hurried"), "failCostTag 落进本局 tags");
        assertFalse(settledState.path("variables").has("counter.checks_passed"), "成功效果不该在失败时写入");

        PlayCheckReceipt settledReplay = runtime.settle(MEMBER, TOPIC, NODE_1, CHECK_ID);
        assertEquals(settled.getStateVersion(), settledReplay.getStateVersion());
        assertEquals(5, settledReplay.getHp(), "重放不二次扣血");
        assertEquals(5, hp());
        assertEquals(1, countSettleActions(), "settle 动作只登记一次");

        // ── 站未完成:阶段结算必须被拒 ──
        assertEquals("本章节还有站点未完成",
                assertThrows(ServiceException.class, () -> storyService.stageEnd(MEMBER, TOPIC, CHAPTER_1)).getMessage());

        // ── 完成第 1 站:节点 effects(写 clue/relation)+ 站末恢复(扣过血才回) ──
        advanceNode(runtime, NODE_1, "progress-node-1");
        JsonNode afterNode1 = stateJson();
        assertEquals(1, afterNode1.path("variables").path("clue.ledger_page").asInt(), "节点 effects 写 clue");
        assertEquals(1, afterNode1.path("variables").path(REL_ARONG).asInt(), "节点 effects 写显式关系键");
        assertEquals(7, afterNode1.path("variables").path("sys.hp").asInt(), "本节点真扣过血:回 stationEndIfHurt 2");
        assertFalse(afterNode1.path("variables").has("sys.damage." + NODE_1), "恢复用掉即清标记");
        assertEquals(NODE_2, afterNode1.path("currentNodeId").asLong(), "推进到第 2 站");
        assertEquals(2, afterNode1.path("variables").path("sys.mistakes." + NODE_1).asInt());

        // 完成后的叙事段:outcome(按检定成败取 lineIfFail)、deliver.line、revisit、mod 条件成立
        Map<String, Object> node1Done = encounterService.resolve(MEMBER, TOPIC, NODE_1);
        Map<String, Object> outcome = map(node1Done.get("outcome"));
        assertNotNull(outcome, "完成后才有 outcome");
        assertEquals("阿荣没有松口", outcome.get("line"), "结算过且失败 ⇒ lineIfFail");
        assertEquals(2, rows(outcome.get("facts")).size(), "when=null 与 clue 条件各一条");
        assertEquals("阿荣把钥匙递过来", map(node1Done.get("deliver")).get("line"));
        assertEquals(2, rows(map(node1Done.get("revisit")).get("changes")).size(), "完成后复访段才出");
        assertEquals(Boolean.TRUE, rows(map(node1Done.get("check")).get("mods")).get(0).get("held"),
                "clue 到手后 mod 条件成立");

        // ── 第 2 站解锁:requires 的 clue 已在位 ──
        Map<String, Object> node2 = encounterService.resolve(MEMBER, TOPIC, NODE_2);
        assertEquals(Boolean.FALSE, node2.get("locked"), "requires 依赖的 clue 已写入 ⇒ 解锁");
        assertEquals(Collections.emptyList(), node2.get("missingKeys"));
        assertEquals(Boolean.TRUE, node2.get("arrived"));
        assertEquals(Collections.singletonList("qa"), node2.get("allowedActions"), "解锁且到店:给启用的玩法段");

        // ── 阶段结算:第一章的 changed 按 when 过滤 ──
        Map<String, Object> stageEnd1 = storyService.stageEnd(MEMBER, TOPIC, CHAPTER_1);
        assertEquals(Arrays.asList("你合上了第一章", "阿荣把账本推了过来"), stageEnd1.get("changed"));
        assertEquals("第一章收了", stageEnd1.get("open"));
        assertEquals(7, stageEnd1.get("hp"));
        assertEquals(2, stageEnd1.get("luck"));

        // ── 完成第 2 站:一局收尾(status=COMPLETED) ──
        advanceNode(runtime, NODE_2, "progress-node-2");
        assertEquals(1, intOf("select status from topic_route_session where topic_id=" + TOPIC
                + " order by id desc limit 1"), "最后一站完成即整局完成");
        assertEquals(1, intOf("select count(*) from topic_route_session where topic_id=" + TOPIC
                + " and current_node_id is null"));

        // ── 第二章阶段结算:changed 拿 tag 条件,花费里带已注册 label ──
        Map<String, Object> stageEnd2 = storyService.stageEnd(MEMBER, TOPIC, CHAPTER_2);
        assertEquals(Arrays.asList("你合上了第二章", "你带着一路的慌乱离开"), stageEnd2.get("changed"));
        assertEquals(1, rows(stageEnd2.get("costs")).size(), "tag.hurried 已注册且有 label");
        assertEquals("tag.hurried", rows(stageEnd2.get("costs")).get(0).get("tag"));
        assertEquals("一路慌乱", rows(stageEnd2.get("costs")).get(0).get("label"));
        assertEquals(7, stageEnd2.get("hp"));
        assertEquals(2, stageEnd2.get("luck"));

        // ── 结局:relation 三态选尾声,when 选结局,found/total 只认 optionalClues ──
        Map<String, Object> ending = storyService.ending(MEMBER, TOPIC);
        assertEquals("ally", ending.get("code"), "clue.ledger_page ⇒ ally 结局 when 通过");
        assertEquals("同盟", ending.get("title"));
        List<Map<String, Object>> epilogues = rows(ending.get("epilogues"));
        assertEquals(3, epilogues.size());
        assertEpilogue(epilogues.get(0), NPC_1, "阿荣", "ally", "阿荣把钥匙交给你");
        assertEpilogue(epilogues.get(1), NPC_2, "小满", "neutral", "小满收拾着柜台");
        assertEpilogue(epilogues.get(2), NPC_3, null, "guarded", "老周锁上了门");
        assertEquals(Collections.singletonList("账本残页已归位"), ending.get("exhibits"));
        assertEquals("阿荣的信任", map(ending.get("badge")).get("title"));
        assertEquals(1, ending.get("found"), "clue.ledger_page 已持有");
        assertEquals(2, ending.get("total"), "clue.photo_story 未拿到");
    }

    // ==================== 2. 并发结算只结算一次 ====================

    /**
     * 两个线程同时 settle 同一检定:唯一键 {@code uk_topic_route_action} 把后到的那次
     * 挡在 insertAction,服务捕获 DuplicateKeyException 后重放先到者的回执 ——
     * 双方拿到同一份结算,血量只扣一次。
     *
     * <p>为了让「两人同时读到还没有 settle 动作」稳定发生(而不是靠调度碰运气),
     * 后到者的 mapper 在第一次读 settle 动作时用锁存器握手:先到者完整结算并提交后才放行。
     */
    @Test
    void legacyDefaultSettleOverloadRollsBackInsertedActionWhenCasFails() throws Exception {
        ITopicRouteRuntimeService runtime = runtimeWithDice(fixedDice(D20_FAIL));
        runtime.state(MEMBER, TOPIC, 0L);
        shopProof.arrived.set(true);
        runtime.roll(MEMBER, TOPIC, NODE_1, CHECK_ID);
        TopicRouteSessionMapper failing = org.mockito.Mockito.mock(TopicRouteSessionMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(sql.getMapper(TopicRouteSessionMapper.class)));
        org.mockito.Mockito.doReturn(0).when(failing).updateStateCas(org.mockito.ArgumentMatchers.any());
        ITopicRouteRuntimeService failedRuntime = wireRuntime(fixedDice(D20_FAIL), failing);
        assertThrows(ServiceException.class, () -> failedRuntime.settle(MEMBER, TOPIC, NODE_1, CHECK_ID));
        assertEquals(0, countSettleActions(), "接口默认重载仍须开启事务，CAS 失败不得残留动作");
        assertEquals(8, hp());
    }

    @Test
    void concurrentSettleSettlesExactlyOnce() throws Exception {
        ITopicRouteRuntimeService winnerRuntime = runtimeWithDice(fixedDice(D20_FAIL));
        winnerRuntime.state(MEMBER, TOPIC, 0L);
        shopProof.arrived.set(true);
        winnerRuntime.roll(MEMBER, TOPIC, NODE_1, CHECK_ID);

        CountDownLatch loserReadEmptyAction = new CountDownLatch(1);
        CountDownLatch releaseLoser = new CountDownLatch(1);
        ITopicRouteRuntimeService loserRuntime = wireRuntime(fixedDice(D20_FAIL),
                new HandshakeSessionMapper(sql.getMapper(TopicRouteSessionMapper.class), settleActionId(),
                        loserReadEmptyAction, releaseLoser));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PlayCheckReceipt> loser = pool.submit(() -> loserRuntime.settle(MEMBER, TOPIC, NODE_1, CHECK_ID));
            assertTrue(loserReadEmptyAction.await(5, TimeUnit.SECONDS),
                    "后到者必须真的读到「还没有 settle 动作」才叫并发");
            PlayCheckReceipt winner = winnerRuntime.settle(MEMBER, TOPIC, NODE_1, CHECK_ID);
            releaseLoser.countDown();
            PlayCheckReceipt replayed = loser.get(5, TimeUnit.SECONDS);
            assertTrue(replayed.isSettled());
            assertEquals(winner.getStateVersion(), replayed.getStateVersion(), "两边拿到同一份结算");
            assertEquals(winner.getText(), replayed.getText());
            assertEquals(winner.getHp(), replayed.getHp());
        } finally {
            releaseLoser.countDown();
            pool.shutdownNow();
        }
        assertEquals(1, countSettleActions(), "并发 settle 只许留一条动作");
        assertEquals(6, hp(), "失败只扣一次 medium 2:8 - 2");
    }

    /**
     * 负控:同一时序,把幂等的两把唯一键都摘掉(测试内临时 DDL)⇒ 并发断言必须变红。
     *
     * <p><b>实测到的破坏不是「扣两次血」,而是「幂等没了」</b>:后到者的 insertAction 这次写得进去,
     * 但它手上的 session 快照是第一次读的(version=V、hp 旧值),{@code updateStateCas} 的
     * {@code where version=V ...} 命中不了先到者已提交的 V+1 ⇒ 抛「路线状态已更新，请刷新后重试」。
     * 也就是说:两把唯一键买的是**重放同一份回执**,CAS 买的是**至多扣一次**;少了唯一键,
     * 钱不会双付,但客户端会拿到 409 而不是上次的回执(合同要求重放)。
     * 上一版按「摘键就会双扣」写断言,实测直接红 —— 那正是这条负控要挡住的自以为是。
     *
     * <p>为什么两把一起摘:检定动作的 receiptId 与 actionId 同值,只摘
     * {@code uk_topic_route_action},{@code uk_topic_route_receipt} 照样把第二次插入变成重放
     * (第一版实测:只摘一把时后到者仍是重放)。
     *
     * <p>还原后回读 information_schema 确认两把键原样在位(动作 id 3 列 / 回执 2 列);
     * 若哪天本用例变红(比如又加了一道幂等),说明防线换人了,回来改注释和断言。
     */
    @Test
    void withoutUniqueActionKeysLockOrderStillReplaysIdempotently() throws Exception {
        ITopicRouteRuntimeService winnerRuntime = runtimeWithDice(fixedDice(D20_FAIL));
        winnerRuntime.state(MEMBER, TOPIC, 0L);
        shopProof.arrived.set(true);
        winnerRuntime.roll(MEMBER, TOPIC, NODE_1, CHECK_ID);
        assertEquals(3, uniqueActionKeyColumns(), "前置:动作 id 唯一键必须在位(session_id,member_id,action_id)");
        assertEquals(2, uniqueReceiptKeyColumns(), "前置:回执唯一键必须在位(session_id,receipt_id)");

        exec("alter table topic_route_action drop index uk_topic_route_action");
        exec("alter table topic_route_action drop index uk_topic_route_receipt");
        try {
            assertEquals(0, uniqueActionKeyColumns(), "负控前置:动作 id 唯一键必须真的被摘掉");
            assertEquals(0, uniqueReceiptKeyColumns(), "负控前置:回执唯一键必须真的被摘掉");

            CountDownLatch loserReadEmptyAction = new CountDownLatch(1);
            CountDownLatch releaseLoser = new CountDownLatch(1);
            ITopicRouteRuntimeService loserRuntime = wireRuntime(fixedDice(D20_FAIL),
                    new HandshakeSessionMapper(sql.getMapper(TopicRouteSessionMapper.class), settleActionId(),
                            loserReadEmptyAction, releaseLoser));
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<PlayCheckReceipt> loser = pool.submit(() -> loserRuntime.settle(MEMBER, TOPIC, NODE_1, CHECK_ID));
                assertTrue(loserReadEmptyAction.await(5, TimeUnit.SECONDS),
                        "后到者必须真的读到「还没有 settle 动作」才叫并发");
                PlayCheckReceipt winner = winnerRuntime.settle(MEMBER, TOPIC, NODE_1, CHECK_ID);
                releaseLoser.countDown();
                // 2026-10-14 集成:防线换人了(见本方法注释末段)。C 包 settle 先 FOR UPDATE 锁 activity→registration,
                // 后到者在先到者提交后才拿到锁并以锁读看到其动作 ⇒ 即使摘掉两把唯一键,后到者也拿到同一份重放回执。
                // 唯一键现为纵深防御;本负控改为证明「锁序化后摘键仍幂等」,两把键的还原回读保持不变。
                PlayCheckReceipt replayed = loser.get(5, TimeUnit.SECONDS);
                assertTrue(replayed.isSettled());
                assertEquals(winner.getStateVersion(), replayed.getStateVersion(), "摘键后后到者仍重放同一份结算");
            } finally {
                releaseLoser.countDown();
                pool.shutdownNow();
            }
            assertEquals(1, countSettleActions(), "锁序化:库里只有先到者那一条 settle 动作");
            assertEquals(6, hp(), "至多扣一次:血量不能变成 4");
        } finally {
            exec("alter table topic_route_action add unique key uk_topic_route_action"
                    + " (session_id,member_id,action_id)");
            exec("alter table topic_route_action add unique key uk_topic_route_receipt (session_id,receipt_id)");
        }
        assertEquals(3, uniqueActionKeyColumns(), "负控后动作 id 唯一键必须原样还原");
        assertEquals(2, uniqueReceiptKeyColumns(), "负控后回执唯一键必须原样还原");
    }

    // ==================== 装配 ====================

    @SuppressWarnings("unchecked")
    private static <T> T mutationProxy(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static void wireServices() {
        CmsTopicNodeServiceImpl nodeService = new CmsTopicNodeServiceImpl();
        ReflectionTestUtils.setField(nodeService, "cmsTopicNodeMapper", sql.getMapper(CmsTopicNodeMapper.class));
        CmsMemberTemplateServiceImpl templateService = new CmsMemberTemplateServiceImpl();
        ReflectionTestUtils.setField(templateService, "cmsMemberTemplateMapper",
                sql.getMapper(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(templateService, "legacyCmsMutationGuard",
                mutationProxy(new LegacyCmsMutationGuard(sql.getMapper(LegacyCmsTemplateReadMapper.class))));
        ReflectionTestUtils.setField(templateService, "legacyCmsTemplateBodyReader", mutationProxy(new LegacyCmsTemplateBodyReader(
                sql.getMapper(LegacyCmsTemplateReadMapper.class), sql.getMapper(CmsMemberTemplateMapper.class))));
        PlayerNodeProgressServiceImpl progressService = new PlayerNodeProgressServiceImpl();
        ReflectionTestUtils.setField(progressService, "playerNodeProgressMapper",
                sql.getMapper(PlayerNodeProgressMapper.class));
        NpcProfileService npcProfileService = new NpcProfileService();
        ReflectionTestUtils.setField(npcProfileService, "npcProfileMapper", sql.getMapper(NpcProfileMapper.class));

        storyService = new PlayJourneyStoryServiceImpl(sql.getMapper(CmsTopicMapper.class),
                sql.getMapper(CmsTopicChapterMapper.class), sql.getMapper(CmsTopicNodeMapper.class),
                sql.getMapper(TopicRouteSessionMapper.class),
                new ThemeStateKeyService(sql.getMapper(ThemeStateKeyMapper.class)), npcProfileService);

        encounterService = new PlayEncounterServiceImpl();
        ReflectionTestUtils.setField(encounterService, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(encounterService, "cmsMemberTemplateService", mutationProxy(templateService));
        ReflectionTestUtils.setField(encounterService, "topicRouteSessionMapper",
                sql.getMapper(TopicRouteSessionMapper.class));
        ReflectionTestUtils.setField(encounterService, "shopDayProofService", shopProof);
        ReflectionTestUtils.setField(encounterService, "npcProfileService", npcProfileService);
        ReflectionTestUtils.setField(encounterService, "playerNodeProgressService", progressService);
        // R18-ENC 起 Encounter 的 preload 要读章节音频:真 service + 真 mapper(与运行时同一份)。
        CmsTopicChapterServiceImpl chapterService = new CmsTopicChapterServiceImpl();
        ReflectionTestUtils.setField(chapterService, "cmsTopicChapterMapper",
                sql.getMapper(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(encounterService, "cmsTopicChapterService", chapterService);
    }

    /** 走真 Spring 事务代理:settle 上的 @Transactional 与 READ_COMMITTED 才真的生效。 */
    private static ITopicRouteRuntimeService runtimeWithDice(IntSupplier d20) {
        return wireRuntime(d20, sql.getMapper(TopicRouteSessionMapper.class));
    }

    private static ITopicRouteRuntimeService wireRuntime(IntSupplier d20, TopicRouteSessionMapper routeMapper) {
        TopicRouteRuntimeServiceImpl target = new TopicRouteRuntimeServiceImpl(routeMapper,
                sql.getMapper(CmsTopicMapper.class), sql.getMapper(CmsTopicNodeMapper.class),
                sql.getMapper(CmsMemberTemplateMapper.class), sql.getMapper(PlayerNodeProgressMapper.class),
                null, sql.getMapper(CmsRegistrationMapper.class), null, shopProof,
                () -> 12345L, d20, Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        ReflectionTestUtils.setField(target, "legacyTemplateBodyReader", mutationProxy(new LegacyCmsTemplateBodyReader(
                sql.getMapper(LegacyCmsTemplateReadMapper.class), sql.getMapper(CmsMemberTemplateMapper.class))));
        ReflectionTestUtils.setField(target, "cmsTopicChapterMapper", sql.getMapper(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(target, "themeStateKeyMapper", sql.getMapper(ThemeStateKeyMapper.class));
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(ITopicRouteRuntimeService.class);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (ITopicRouteRuntimeService) proxyFactory.getProxy();
    }

    private static IntSupplier fixedDice(int... values) {
        final AtomicInteger index = new AtomicInteger();
        return () -> values[Math.min(index.getAndIncrement(), values.length - 1)];
    }

    // ==================== 夹具 ====================

    private static void createSchema(Connection connection) throws Exception {
        ItSchema.apply(connection, "it-schema/cms-topic-it-schema.sql");
        ItSchema.apply(connection, "it-schema/journey-full-flow-it-schema.sql");
        ItSchema.apply(connection, "it-schema/merchant-chapter-offer-it-schema.sql");
        // Test-only consumer schema; no external database is touched by source editing.
        connection.createStatement().execute("ALTER TABLE cms_member_template ADD w18_provenance_kind VARCHAR(32) NULL");
        connection.createStatement().execute("CREATE TABLE w18_cms_template_provenance(template_id BIGINT PRIMARY KEY)");
        ItSchema.applyMigrations(connection, "sql/migration_theme_state_key_20260916.sql",
                "sql/migration_20260823_topic_route_graph.sql",
                "sql/migration_20260930_registration_first_successful_scan.sql",
                "sql/migration_ordinary_chapter_offer_withdraw_20261003.sql",
                "sql/migration_20261003_merchant_offer_withdrawal_columns.sql");
    }

    private static void wipe() throws Exception {
        try (Connection c = open(); Statement st = c.createStatement()) {
            for (String table : Arrays.asList("topic_route_action", "topic_route_session", "player_node_progress",
                    "cms_registration", "theme_state_key", "w18_cms_template_provenance", "cms_member_template", "cms_topic_node",
                    "cms_topic_chapter", "cms_topic", "npc_profile")) {
                st.execute("delete from " + table);
            }
        }
    }

    private static void seedFixture() throws Exception {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("insert into cms_topic(id,member_id,route_mode,published_config_version,journey_rules_json,"
                    + "journey_story_json,name,status,user_status,del_flag,create_time,update_time) values("
                    + TOPIC + "," + MEMBER + ",'LINEAR',1,'" + JOURNEY_RULES + "','" + JOURNEY_STORY
                    + "','旅程全链路',1,1,0,now(),now())");
            st.execute("insert into cms_topic_chapter(id,topic_id,name,sort_id,blocks) values("
                    + CHAPTER_1 + "," + TOPIC + ",'第一章',1,'" + CHAPTER_1_BLOCKS + "')");
            st.execute("insert into cms_topic_chapter(id,topic_id,name,sort_id,blocks) values("
                    + CHAPTER_2 + "," + TOPIC + ",'第二章',2,'" + CHAPTER_2_BLOCKS + "')");
            st.execute("insert into cms_topic_node(id,topic_id,chapter_id,name,template_id,status,sort_id,"
                    + "merchant_member_id,xp_value,hook_text,create_time) values("
                    + NODE_1 + "," + TOPIC + "," + CHAPTER_1 + ",'阿荣的店'," + TEMPLATE_1
                    + ",1,1,null,10,'一句钩子',now())");
            st.execute("insert into cms_topic_node(id,topic_id,chapter_id,name,template_id,status,sort_id,"
                    + "merchant_member_id,xp_value,hook_text,create_time) values("
                    + NODE_2 + "," + TOPIC + "," + CHAPTER_2 + ",'小满的店'," + TEMPLATE_2
                    + ",1,2,null,10,'另一句钩子',now())");
            st.execute("insert into cms_member_template(id,title,status,advanced_config_json,story_text) values("
                    + TEMPLATE_1 + ",'柜台问答',1,'" + NODE_1_CONFIG + "','阿荣的故事')");
            st.execute("insert into cms_member_template(id,title,status,advanced_config_json,story_text) values("
                    + TEMPLATE_2 + ",'盖章',1,'" + NODE_2_CONFIG + "','小满的故事')");
            st.execute("insert into npc_profile(id,scope_type,scope_id,name,persona,audit_status,enabled,del_flag,"
                    + "sort,create_time,update_time) values(" + NPC_1 + ",4," + NODE_1
                    + ",'阿荣','old shopkeeper',1,1,'0',0,now(),now())");
            st.execute("insert into npc_profile(id,scope_type,scope_id,name,persona,audit_status,enabled,del_flag,"
                    + "sort,create_time,update_time) values(" + NPC_2 + ",4," + NODE_2
                    + ",'小满','young clerk',1,1,'0',0,now(),now())");
            st.execute("insert into cms_registration(member_id,owner_type,owner_id,topic_id,registration_status,"
                    + "ticket_id,purchase_kind,create_time) values(" + MEMBER + ",1," + TOPIC + "," + TOPIC
                    + ",2,1,0,now())");
            for (String[] key : STATE_KEYS) {
                st.execute("insert into theme_state_key(topic_id,state_key,key_type,scope,label,initial_value,"
                        + "create_time,update_time) values(" + TOPIC + ",'" + key[0] + "','" + key[1]
                        + "','run','" + key[2] + "',0,now(3),now(3))");
            }
        }
    }

    /** 完成事实(player_node_progress status=1)先落库,推进才有「节点完成回执」。 */
    private static void advanceNode(ITopicRouteRuntimeService runtime, long nodeId, String receiptId)
            throws Exception {
        exec("insert into player_node_progress(member_id,activity_id,topic_id,node_id,status,del_flag,create_time)"
                + " values(" + MEMBER + ",0," + TOPIC + "," + nodeId + ",1,0,now())");
        TopicRouteState view = runtime.advance(MEMBER, TOPIC, 0L, sessionVersion(), "adv:" + nodeId,
                "CHOICE", new NodeOutcome(nodeId, "COMPLETED", receiptId));
        assertNotNull(view.getSessionId());
    }

    private static String settleActionId() {
        return "check:" + NODE_1 + ":" + CHECK_ID + ":settle";
    }

    // ==================== 读侧小工具 ====================

    private static JsonNode stateJson() throws Exception {
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select state_json from topic_route_session"
                     + " where topic_id=" + TOPIC + " order by id desc limit 1")) {
            assertTrue(rs.next(), "本局必须已建");
            return JSON.readTree(rs.getString(1));
        }
    }

    private static List<String> tags(JsonNode state) {
        List<String> out = new java.util.ArrayList<String>();
        for (JsonNode tag : state.path("tags")) out.add(tag.asText());
        return out;
    }

    private static int hp() throws Exception {
        return stateJson().path("variables").path("sys.hp").asInt();
    }

    private static int luck() throws Exception {
        return stateJson().path("variables").path("sys.luck").asInt();
    }

    private static int sessionVersion() throws Exception {
        return intOf("select version from topic_route_session where topic_id=" + TOPIC + " order by id desc limit 1");
    }

    private static int countSettleActions() throws Exception {
        return intOf("select count(*) from topic_route_action where action_id='" + settleActionId() + "'");
    }

    private static int uniqueActionKeyColumns() throws Exception {
        return intOf("select count(*) from information_schema.statistics where table_schema=database()"
                + " and table_name='topic_route_action' and index_name='uk_topic_route_action' and non_unique=0");
    }

    private static int uniqueReceiptKeyColumns() throws Exception {
        return intOf("select count(*) from information_schema.statistics where table_schema=database()"
                + " and table_name='topic_route_action' and index_name='uk_topic_route_receipt' and non_unique=0");
    }

    private static void assertEpilogue(Map<String, Object> epilogue, long npcId, String name, String state,
            String line) {
        assertEquals(npcId, epilogue.get("npcId"));
        assertEquals(name, epilogue.get("name"));
        assertEquals(state, epilogue.get("state"));
        assertEquals(line, epilogue.get("line"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection(jdbcUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static int intOf(String sql) throws Exception {
        try (Connection c = open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "断言查询必须有结果:" + sql);
            return rs.getInt(1);
        }
    }

    // ==================== 桩与钩子 ====================

    /** 到店证明:真实现要店内扫码写 checkin_time;IT 里只留一个可切的布尔。 */
    private static final class StubShopDayProofService implements IShopDayProofService {
        private final AtomicBoolean arrived = new AtomicBoolean(false);

        @Override
        public PlayerNodeProgress markArrived(Long memberId, Long activityId, CmsTopicNode node) {
            arrived.set(true);
            return null;
        }

        @Override
        public PlayerNodeProgress submitPhoto(Long memberId, Long activityId, Long nodeId, String picUrl) {
            arrived.set(true);
            return null;
        }

        @Override
        public boolean hasArrived(Long memberId, Long activityId, Long nodeId) {
            return arrived.get();
        }

        @Override
        public boolean markGameDone(Long memberId, Long activityId, Long topicId, Long nodeId) {
            return true;
        }
    }

    /**
     * 只在真 mapper 外层加一个握手点:第一次读「指定 settle 动作」为空时锁住,
     * 让先到者把整个结算提交完再放行后到者 —— 并发时序变成确定性的,不靠调度碰运气。
     * 所有 SQL 照常打在真库上。
     */
    private static final class HandshakeSessionMapper implements TopicRouteSessionMapper {
        public String selectMemberNickname(Long memberId) {return real.selectMemberNickname(memberId);}
        @Override public List<TopicRouteSession> selectCurrentCandidatesByOwners(List<TopicRouteSession> scopes) {return real.selectCurrentCandidatesByOwners(scopes);}

        @Override public TopicRouteSession selectCurrentByOwnerForUpdate(Long activityId,Long topicId,Long memberId) {return real.selectCurrentByOwnerForUpdate(activityId,topicId,memberId);}
        @Override public TopicRouteSession selectByOwnerForUpdate(Long activityId,Long topicId,Long memberId,String instance) {return real.selectByOwnerForUpdate(activityId,topicId,memberId,instance);}

        private final TopicRouteSessionMapper real;
        private final String watchedActionId;
        private final CountDownLatch readEmpty;
        private final CountDownLatch release;
        private volatile boolean armed = true;

        private HandshakeSessionMapper(TopicRouteSessionMapper real, String watchedActionId,
                CountDownLatch readEmpty, CountDownLatch release) {
            this.real = real;
            this.watchedActionId = watchedActionId;
            this.readEmpty = readEmpty;
            this.release = release;
        }

        @Override
        public TopicRouteAction selectActionByAction(Long sessionId, Long memberId, String actionId) {
            return real.selectActionByAction(sessionId, memberId, actionId);
        }

        /*
         * 2026-10-14 集成:C 包 settle 开头先做 activity→registration 当前锁读(FOR UPDATE)。若仍停在
         * 「读到空动作」处,后到者已握报名行锁,先到者被挡 ⇒ 交错不可能出现(只剩握手超时)。改停在本事务
         * 第一次一致性读(找局,无锁):RR 快照由此固定,先到者提交后后到者再拿锁,其普通读仍读不到先到者的
         * settle 动作 —— 与原设计同一个「双方都认为还没有 settle」的并发窗口,断言不变。
         */
        @Override
        public List<TopicRouteSession> selectMemberTopicSessions(Long memberId, Long topicId) {
            List<TopicRouteSession> rows = real.selectMemberTopicSessions(memberId, topicId);
            if (armed) {
                armed = false;
                readEmpty.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("并发握手超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return rows;
        }

        @Override
        public int insertSession(TopicRouteSession session) {
            return real.insertSession(session);
        }

        @Override
        public int countRuns(Long activityId,Long topicId,Long memberId) {return real.countRuns(activityId,topicId,memberId);}
        public TopicRouteSession selectCurrentByOwner(Long activityId, Long topicId, Long memberId) { return real.selectCurrentByOwner(activityId,topicId,memberId); }
        @Override
        public List<TopicRouteSession> selectHistoryPage(Long memberId,Long topicId,Long beforeSessionId,int limit) {return real.selectHistoryPage(memberId,topicId,beforeSessionId,limit);}
        public List<String> selectEndingResults(Long memberId,Long topicId) {return real.selectEndingResults(memberId,topicId);}
        public List<TopicRouteSession> selectHistory(Long memberId, Long topicId) { return real.selectHistory(memberId,topicId); }

        @Override
        public TopicRouteSession selectByOwner(Long activityId, Long topicId, Long memberId, String playInstanceId) {
            return real.selectByOwner(activityId, topicId, memberId, playInstanceId);
        }

        @Override
        public TopicRouteSession selectAccessibleByIdForUpdate(Long id, Long memberId) { return real.selectAccessibleByIdForUpdate(id, memberId); }

        @Override
        public TopicRouteSession selectAccessibleById(Long id, Long memberId) {
            return real.selectAccessibleById(id, memberId);
        }

        @Override
        public List<TopicRouteSession> selectByOwners(List<TopicRouteSession> scopes) {
            return real.selectByOwners(scopes);
        }

        @Override
        public int updateStateCas(TopicRouteSession session) {
            return real.updateStateCas(session);
        }

        @Override
        public TopicRouteAction selectActionByReceipt(Long sessionId, String receiptId) {
            return real.selectActionByReceipt(sessionId, receiptId);
        }

        @Override
        public int insertAction(TopicRouteAction action) {
            return real.insertAction(action);
        }

        @Override
        public int updateActionResponse(Long id, String responseJson) {
            return real.updateActionResponse(id, responseJson);
        }

        @Override
        public List<Long> selectDistinctMemberIdsByTopic(Long topicId, Integer status) {
            return real.selectDistinctMemberIdsByTopic(topicId, status);
        }

        @Override
        public int countDistinctMembersByTopic(Long topicId, Integer status) {
            return real.countDistinctMembersByTopic(topicId, status);
        }
    }
}

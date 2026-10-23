package com.chengyinhub.business.mapper;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantInsightCrowdFact;
import com.chengyinhub.business.domain.dto.MerchantInsightVisitFact;
import com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent;
import com.chengyinhub.business.domain.dto.ShopDayMerchantRosterFact;
import com.chengyinhub.business.domain.dto.TopicChapterLoadFact;
import com.chengyinhub.business.domain.dto.TopicOnsiteTicketFact;
import com.chengyinhub.business.domain.dto.TopicRepeatPaymentFact;
import com.chengyinhub.business.domain.dto.TopicWaitFact;
import com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp;
import com.chengyinhub.business.domain.vo.ai.MerchantInsightResp;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMerchantLeadService;
import com.chengyinhub.business.service.ITopicContentPublisherProjectionService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.impl.MerchantInsightServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报名事实读取与真实 cms_registration schema 的合同测试。
 *
 * <p>真源 = {@code sql/base_schema_20260818.sql}:{@code cms_registration} 没有 {@code del_flag}
 * 列,删除是物理 DELETE({@code CmsRegistrationMapper.xml} deleteCmsRegistrationById)。
 * 有效资格由 {@code payment_status=2 + registration_status=2}(+purchase_kind 时效/entitlement)
 * 表达:取消=registration_status 3、退款=payment_status 4 + registration_status 3、
 * 过期=registration_status 4 + payment_status 3。
 *
 * <p>本测试**刻意不预置 del_flag 假列**(另有断言锁住);每条用例断言的是「删掉幽灵列谓词后,
 * 已付款资格保留、取消/退款/过期/他人购买仍被拒绝」的行为,而不是“SQL 不报错”。
 * 负控含:商家离岗(mms_merchant.status!=1)、非商家本人的票、物理删除后资格消失、
 * 以及其它表的 del_flag 过滤没有被顺手拆掉。
 *
 * <p>用真实 mapper XML + MySQL 8 scratch(UUID 库名,结束 DROP 后回读 information_schema=0)。
 * 仅在 {@code CHENGYIN_FUNDS_MYSQL_IT=1} 且本机 scratch MySQL 可达时运行。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationReadContractMybatisIntegrationTest {

    // 2026-09-17:3317/root/空密码兜底退役 —— 连接参数只认门禁注入的 CHENGYIN_TEST_MYSQL_*
    // (PR #1077 报告第 3 条);缺了直接失败(fail-closed),不再悄悄落到本机另一台库。
    private static final String HOST = requiredEnv("CHENGYIN_TEST_MYSQL_HOST");
    private static final String PORT = requiredEnv("CHENGYIN_TEST_MYSQL_PORT");
    private static final String USER = requiredEnv("CHENGYIN_TEST_MYSQL_USER");
    private static final String PASSWORD = System.getenv("CHENGYIN_TEST_MYSQL_PASSWORD");
    private static final String PARAMS =
            "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL";
    private static final String ROOT = resolveRoot();

    private String database;
    private SqlSessionTemplate sql;
    private CmsTopicMapper topicMapper;
    private MerchantInsightMapper insightMapper;
    private GameSessionRuntimeMapper gameMapper;
    private TopicOnsiteMetricsMapper onsiteMapper;
    private ShopDayMerchantRosterMapper rosterMapper;
    private MmsMerchantMapper merchantMapper;

    @BeforeAll
    void createScratchSchema() throws Exception {
        database = "chengyin_regread_" + UUID.randomUUID().toString().replace("-", "");
        execAdmin("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        createSchema();
        assertTrue(queryInt("select count(*) from information_schema.tables"
                        + " where table_schema=database() and table_name='cms_registration'") == 1,
                "真源 DDL 必须建出 cms_registration");
        assertEquals(0, queryInt("select count(*) from information_schema.columns"
                        + " where table_schema=database() and table_name='cms_registration'"
                        + " and column_name='del_flag'"),
                "夹具不许给 cms_registration 造 del_flag 假列,真实合同本就没有这一列");
        openMappers();
    }

    @AfterAll
    void dropScratchSchemaAndReadBack() throws Exception {
        String dropped = database;
        if (dropped != null) {
            execAdmin("DROP DATABASE IF EXISTS `" + dropped + "`");
        }
        int remaining;
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT + "/" + PARAMS, USER, PASSWORD);
             PreparedStatement p = c.prepareStatement(
                     "select count(*) from information_schema.schemata where schema_name=?")) {
            p.setString(1, dropped);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                remaining = rs.getInt(1);
            }
        }
        assertEquals(0, remaining, "UUID scratch 必须已销毁(结束读回 0)");
        System.out.println("OWN_SCRATCH_DROPPED=true schema=" + dropped);
    }

    @BeforeEach
    void seedFixtures() throws Exception {
        for (String table : new String[]{
                "cms_registration", "cms_activity", "cms_topic", "cms_topic_node", "cms_topic_chapter",
                "club", "club_member", "merchant_chapter_offer", "mms_merchant", "registration_entitlement",
                "redemption", "player_node_progress", "player_growth", "member_interest_profile",
                "ums_member", "ums_member_follow", "oms_ticket", "refund_application", "coop_complaint",
                "game_session", "game_station_run", "game_team_role_assignment", "game_command_receipt",
                "game_task_submission", "game_player_node_outcome", "play_team", "play_team_member"}) {
            exec("DELETE FROM " + table);
        }

        exec("INSERT INTO ums_member(id,nickname,role,del_flag,sex) VALUES"
                + " (41,'club41','player',0,0),(42,'paid42','player',0,1),(43,'refund43','player',0,1),"
                + " (44,'cancel44','player',0,2),(45,'expire45','player',0,2),(46,'pending46','player',0,1),"
                + " (47,'other47','player',0,1),(888,'merchant888','merchant',0,0),(999,'owner999','club',0,0)");
        exec("INSERT INTO club(id,member_id,name,status,del_flag) VALUES (9,999,'club9',1,0)");
        exec("INSERT INTO club_member(id,club_id,member_id,status,del_flag,join_time)"
                + " VALUES (1,9,41,1,0,now())");
        // 主题1=会员制俱乐部主题(公开列表读取);主题2 未过审,只作“他人/回购”事实的控制组。
        exec("INSERT INTO cms_topic(id,member_id,name,status,user_status,del_flag,is_template,club_id,"
                + "club_member_only,product_type,start_date,end_date) VALUES"
                + " (1,999,'member-only topic',1,1,0,0,9,1,2,now(),date_add(now(), interval 30 day)),"
                + " (2,999,'second topic',0,1,0,0,9,1,2,now(),date_add(now(), interval 30 day))");
        exec("INSERT INTO cms_activity(id,member_id,name,topic_id,del_flag,status) VALUES"
                + " (100,999,'act100',1,0,1),(101,999,'act101',2,0,1)");

        exec("INSERT INTO cms_registration"
                + " (id,registration_no,owner_type,owner_id,ticket_id,member_id,purchase_kind,"
                + "  payment_status,registration_status,payment_time,pay_expire_time,ticket_price,"
                + "  original_amount,total_amount,payable_amount,create_time,update_time) VALUES"
                + " (1001,'REG1001',2,100,1000,42,3,2,2,now(),date_add(now(),interval 1 day),10,10,10,10,now(),now()),"
                + " (1002,'REG1002',2,100,1000,43,3,4,3,date_sub(now(),interval 2 day),date_sub(now(),interval 1 day),10,10,10,10,date_sub(now(),interval 3 day),now()),"
                + " (1003,'REG1003',2,100,1000,44,3,2,3,now(),date_add(now(),interval 1 day),10,10,10,10,now(),now()),"
                + " (1004,'REG1004',2,100,1000,45,3,3,4,now(),date_sub(now(),interval 1 day),10,10,10,10,date_sub(now(),interval 3 day),now()),"
                + " (1005,'REG1005',2,100,1000,46,3,0,1,null,date_add(now(),interval 1 hour),10,10,10,10,now(),now()),"
                + " (1006,'REG1006',2,101,2000,47,3,2,2,now(),date_add(now(),interval 1 day),10,10,10,10,now(),now()),"
                + " (1007,'REG1007',1,1,1000,42,1,2,2,null,date_add(now(),interval 1 day),10,10,10,10,now(),now()),"
                + " (1009,'REG1009',2,101,2000,42,3,2,2,now(),date_add(now(),interval 1 day),10,10,10,10,now(),now()),"
                + " (1010,'REG1010',2,101,2000,44,3,2,3,now(),date_add(now(),interval 1 day),10,10,10,10,now(),now()),"
                + " (1011,'REG1011',2,101,2000,45,3,2,4,now(),date_add(now(),interval 1 day),10,10,10,10,now(),now()),"
                + " (1012,'REG1012',2,101,2000,46,3,4,3,now(),date_add(now(),interval 1 day),10,10,10,10,now(),now())");

        // 只给主题1 建供给/权益:商家参谋/探店日排期的事实面隔离在 topic 维度。
        exec("INSERT INTO registration_entitlement(id,registration_id,chapter_id,topic_id,status,create_time) VALUES"
                + " (1,1001,11,1,0,now()),(2,1002,11,1,0,now()),(3,1004,11,1,0,now())");
        exec("INSERT INTO merchant_chapter_offer(id,topic_id,chapter_id,merchant_id,quota_total,"
                + "quota_used,valid_end,status,terms_mode) VALUES"
                + " (1,1,11,888,10,1,'2030-12-31 00:00:00',1,'TRAFFIC')");
        exec("INSERT INTO mms_merchant(id,member_id,name,status,account_status,del_flag)"
                + " VALUES (1,888,'merchant888',1,1,0)");
        exec("INSERT INTO cms_topic_node(id,topic_id,chapter_id,name,merchant_member_id,status,node_audit_status) VALUES"
                + " (1,1,11,'node-11',888,1,1),(2,2,22,'node-22',888,1,1)");
        exec("INSERT INTO player_node_progress(id,member_id,activity_id,topic_id,node_id,status,checkin_time,del_flag)"
                + " VALUES (1,42,100,1,1,1,date_sub(now(),interval 1 hour),0),"
                + " (2,43,100,1,1,1,date_sub(now(),interval 2 hour),0)");
        exec("INSERT INTO redemption(id,registration_id,chapter_id,topic_id,merchant_id,offer_id,"
                + "operator_id,status,idempotency_key,create_time) VALUES"
                + " (1,1001,11,1,888,1,1,'ACTIVE','k1',date_sub(now(),interval 30 minute)),"
                + " (2,1002,11,1,888,1,1,'ACTIVE','k2',date_sub(now(),interval 30 minute))");
        exec("INSERT INTO player_growth(id,member_id,level) VALUES (1,42,3)");
        exec("INSERT INTO member_interest_profile(member_id,category_weights_json) VALUES (42,'{}')");
        exec("INSERT INTO oms_ticket(id,member_id,owner_type,owner_id,name,price,start_time,end_time,del_flag,create_time) VALUES"
                + " (1000,999,2,100,'ticket-a',10,date_sub(now(),interval 1 day),date_add(now(),interval 1 hour),0,now()),"
                + " (2000,999,2,101,'ticket-b',10,date_sub(now(),interval 1 day),date_add(now(),interval 1 hour),0,now())");
        exec("INSERT INTO refund_application(id,refund_no,source_type,source_id,owner_type,owner_id,"
                + "member_id,refund_amount,status,payout_status,del_flag,create_time) VALUES"
                + " (1,'REF1','registration',1002,2,100,43,10,1,3,0,date_sub(now(),interval 30 minute))");
        exec("INSERT INTO coop_complaint(id,topic_id,registration_id,reporter_member_id,respondent_type,"
                + "respondent_member_id,status,fault_ratio,del_flag,create_time) VALUES"
                + " (1,1,1001,42,'merchant',888,3,50,0,now()),"
                + " (2,1,1002,43,'merchant',888,3,50,0,now()),"
                + " (3,1,1001,42,'merchant',888,2,50,0,now())");

        exec("INSERT INTO game_session(id,activity_id,topic_id,club_id,module_config_snapshot_json,"
                + "status,revision,prepared_by,create_time,update_time)"
                + " VALUES (1,100,1,9,'{}','RUNNING',0,999,now(),now())");
        exec("INSERT INTO play_team(id,owner_type,owner_id,leader_member_id,title,status)"
                + " VALUES (1,2,100,42,'team',1)");
        exec("INSERT INTO play_team_member(id,team_id,member_id,role,status) VALUES (1,1,42,1,1),(2,1,43,0,1)");
        exec("INSERT INTO game_station_run(id,game_session_id,activity_id,node_id,node_name,station_code,"
                + "merchant_member_id,source_type,source_id,status,revision,create_time,update_time)"
                + " VALUES (1,1,100,1,'node-11','NODE_1',888,'CHAPTER_NODE',1,'READY',0,now(),now())");
        exec("INSERT INTO game_command_receipt(id,game_session_id,activity_id,node_id,actor_member_id,"
                + "actor_perspective,request_id,request_hash,action,outcome,revision,result_json,create_time,update_time) VALUES"
                + " (1,1,100,1,42,'PLAYER','r1',repeat('a',64),'PLAYER_HINT','APPLIED',1,'{\"hint\":{\"currentLevel\":2}}',now(),now()),"
                + " (2,1,100,1,43,'PLAYER','r2',repeat('b',64),'PLAYER_HINT','APPLIED',1,'{\"hint\":{\"currentLevel\":3}}',now(),now())");
    }

    // ------------------------------------------------------------------ ① clubMemberOnlyGate

    @Test
    void clubMemberOnlyTopicKeepsPaidBuyersAndRejectsRefundCancelExpirePending() throws Exception {
        CmsTopic probe = new CmsTopic();
        probe.setViewerMemberId(42L);
        assertEquals(Collections.singletonList(1L), ids(topicMapper.selectPublicCmsTopicList(probe)),
                "已付款且报名成功的买家必须仍能看到买过的会员制主题");

        probe.setViewerMemberId(999L);
        assertEquals(Collections.singletonList(1L), ids(topicMapper.selectPublicCmsTopicList(probe)),
                "主题主理人必须可见");
        probe.setViewerMemberId(41L);
        assertEquals(Collections.singletonList(1L), ids(topicMapper.selectPublicCmsTopicList(probe)),
                "俱乐部成员必须可见");

        for (long viewer : new long[]{43L, 44L, 45L, 46L, 47L}) {
            probe.setViewerMemberId(viewer);
            assertTrue(topicMapper.selectPublicCmsTopicList(probe).isEmpty(),
                    "viewer=" + viewer + "(退款/取消/过期/待支付/他人购买)不得看到会员制主题");
        }
        probe.setViewerMemberId(null);
        assertTrue(topicMapper.selectPublicCmsTopicList(probe).isEmpty(), "未登录 fail-closed");

        assertNotNull(topicMapper.selectCmsTopicDetailById(1L, 42L), "已付款买家详情可见");
        assertNull(topicMapper.selectCmsTopicDetailById(1L, 43L), "已退款不得可见");
        assertNull(topicMapper.selectCmsTopicDetailById(1L, 44L), "已取消不得可见");
        assertNull(topicMapper.selectCmsTopicDetailById(1L, 45L), "已过期不得可见");
        assertNotNull(topicMapper.selectCmsTopicDetailById(1L, 999L), "主理人详情可见");

        // 物理 DELETE 才是真表的“删除”;不存在可绕过资格判定的软删列。
        exec("DELETE FROM cms_registration WHERE id IN (1001, 1007)");
        probe.setViewerMemberId(42L);
        assertTrue(topicMapper.selectPublicCmsTopicList(probe).isEmpty(),
                "报名被物理删除后,已付款资格随之消失");
    }

    // ------------------------------------------------------------------ ② MerchantInsight

    @Test
    void merchantInsightKeepsPaidFactsAndRejectsRefundsAndOffDutyMerchant() throws Exception {
        Date since = new Date(System.currentTimeMillis() - 7L * 86_400_000L);
        List<MerchantInsightVisitFact> visits = insightMapper.selectVisitFacts(888L, since);
        assertEquals(Collections.singletonList(1001L),
                visits.stream().map(MerchantInsightVisitFact::getRegistrationId).collect(Collectors.toList()),
                "本商家已付款报名的到店事实保留;退款/取消/过期不进入");

        List<MerchantInsightCrowdFact> crowd = insightMapper.selectCrowdFacts(888L, since);
        assertEquals(Collections.singletonList(42L),
                crowd.stream().map(MerchantInsightCrowdFact::getMemberId).collect(Collectors.toList()),
                "画像只含有效报名会员");

        List<MerchantInsightVisitorEvent> events = insightMapper.selectVisitorEvents(888L);
        assertEquals(Collections.singletonList(42L),
                events.stream().map(MerchantInsightVisitorEvent::getMemberId).collect(Collectors.toList()),
                "访客事件只含有效报名会员");

        exec("UPDATE mms_merchant SET status = 0 WHERE member_id = 888");
        assertTrue(insightMapper.selectVisitFacts(888L, since).isEmpty(), "商家离岗后本商家事实整体不可见");
        assertTrue(insightMapper.selectVisitorEvents(888L).isEmpty(), "商家离岗负控(事件面)");
        exec("UPDATE mms_merchant SET status = 1 WHERE member_id = 888");
    }

    // ------------------------------------------------------------------ ②b 店铺参谋主卡走势(2026-10-22 改版)

    /**
     * 店铺参谋改版后端:真 mapper XML + 生产 DDL 的 mms_merchant + 真 MerchantInsightServiceImpl,
     * 只把 AI 与 Redis 换成可控替身。半年 40+ 条到店事件(回头客、只核销、零点边界、退款/失效/他商负控)
     * 灌进真库,逐个窗口逐日核对 trends,再核对页头店名/头像、prompt 不含新字段、v3 缓存往返和 JSON 外形。
     */
    @Test
    void merchantInsightTrendsAndProfileFromRealRows() throws Exception {
        exec("DELETE FROM player_node_progress");
        exec("DELETE FROM redemption");
        exec("UPDATE mms_merchant SET name='城南小馆', logo='merchant/logo/888.png' WHERE member_id=888");
        exec("INSERT INTO cms_activity(id,member_id,name,topic_id,del_flag,status) VALUES (102,999,'act102',1,0,1)");

        LocalDate today = LocalDate.now();
        DateTimeFormatter sqlTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        // 有效事件:{memberId, checkinAt|null, redeemedAt|null};oracle 只从这张表算
        List<Object[]> valid = new ArrayList<>();
        List<String> members = new ArrayList<>();
        int reg = 5000;
        for (int k = 0; k < 40; k++) {
            long member = 5000 + k % 30;                   // 5000..5009 各来两次(第二次走 act102)
            long activity = k < 30 ? 100 : 102;
            LocalDateTime at = today.minusDays((k * 37L) % 175).atTime(8 + k % 14, (k * 7) % 60);
            boolean redeemOnly = k % 11 == 0;
            LocalDateTime checkin = redeemOnly ? null : at;
            LocalDateTime redeemed = k % 3 == 0 ? null : at.plusMinutes(40);
            if (redeemOnly) redeemed = at;
            seedVisit(++reg, member, activity, 1, 11, 2, 2, 0, checkin, redeemed, sqlTime);
            valid.add(new Object[]{member, checkin, redeemed});
        }
        // 自然日边界:今天 00:00:05 算今天;7 天窗首日 00:00:01 在窗内;前一日 23:59:59 落上期
        LocalDateTime[] edges = {today.atTime(0, 0, 5), today.minusDays(6).atTime(0, 0, 1),
                today.minusDays(7).atTime(23, 59, 59)};
        for (int i = 0; i < edges.length; i++) {
            seedVisit(++reg, 6001 + i, 100, 1, 11, 2, 2, 0, edges[i], null, sqlTime);
            valid.add(new Object[]{6001L + i, edges[i], null});
        }
        // 负控:退款票 / 失效权益 / 没有本商家 offer 的主题 —— 都打过卡,一条都不许进
        seedVisit(++reg, 6004, 100, 1, 11, 4, 3, 0, today.atTime(12, 0), today.atTime(12, 30), sqlTime);
        seedVisit(++reg, 6005, 100, 1, 11, 2, 2, 2, today.atTime(13, 0), null, sqlTime);
        seedVisit(++reg, 6006, 101, 2, 22, 2, 2, 0, today.atTime(14, 0), null, sqlTime);
        for (long m = 5000; m <= 6006; m = m == 5029 ? 6001 : m + 1) members.add("(" + m + ",'p" + m + "','player',0,1)");
        exec("INSERT INTO ums_member(id,nickname,role,del_flag,sex) VALUES " + String.join(",", members));

        Map<String, Object> redis = new ConcurrentHashMap<>();
        IAiCreatorService ai = mock(IAiCreatorService.class);
        when(ai.merchantInsight(anyString(), anyLong())).thenThrow(new AiServiceException("AI 未就绪"));
        MerchantInsightServiceImpl service = insightService(ai, redis);

        MerchantInsightResp first = service.insight(888L);
        if (!LocalDate.now().equals(today)) return;        // 恰好跨零点:窗口基准已变,本轮不作判断
        assertEquals("AI 未就绪", first.getAiError(), "AI 挂了数据区照出");
        assertTrue(redis.isEmpty(), "AI 失败不写缓存");
        assertEquals("城南小馆", first.getProfile().getString("storeName"));
        assertEquals("merchant/logo/888.png", first.getProfile().getString("logo"));
        String facts = first.getFacts().toJSONString();
        assertFalse(facts.contains("城南小馆") || facts.contains("logo") || facts.contains("trends")
                || facts.contains("storeName"), "店名/头像/走势只在展示层,不进 AI prompt");

        JSONArray windows = first.getTrends().getJSONArray("windows");
        assertEquals(3, windows.size());
        int[] spans = {7, 30, 90};
        DateTimeFormatter label = DateTimeFormatter.ofPattern("MM-dd");
        for (int w = 0; w < 3; w++) {
            JSONObject win = windows.getJSONObject(w);
            int days = spans[w];
            LocalDate start = today.minusDays(days - 1L), prevStart = start.minusDays(days);
            assertEquals(days, win.getIntValue("days"));
            Set<Long> cur = new HashSet<>(), prev = new HashSet<>();
            int curC = 0, prevC = 0, curR = 0, prevR = 0;
            List<Set<Long>> dayMembers = new ArrayList<>();
            int[] dayC = new int[days], dayR = new int[days];
            for (int i = 0; i < days; i++) dayMembers.add(new HashSet<>());
            for (Object[] e : valid) {
                long member = (Long) e[0];
                LocalDateTime checkin = (LocalDateTime) e[1], redeemed = (LocalDateTime) e[2];
                LocalDate at = (checkin != null ? checkin : redeemed).toLocalDate();
                if (!at.isBefore(start)) { cur.add(member); dayMembers.get((int) (at.toEpochDay() - start.toEpochDay())).add(member); }
                else if (!at.isBefore(prevStart)) prev.add(member);
                if (checkin != null) {
                    LocalDate d = checkin.toLocalDate();
                    if (!d.isBefore(start)) { curC++; dayC[(int) (d.toEpochDay() - start.toEpochDay())]++; }
                    else if (!d.isBefore(prevStart)) prevC++;
                }
                if (redeemed != null) {
                    LocalDate d = redeemed.toLocalDate();
                    if (!d.isBefore(start)) { curR++; dayR[(int) (d.toEpochDay() - start.toEpochDay())]++; }
                    else if (!d.isBefore(prevStart)) prevR++;
                }
            }
            String at = days + " 天窗";
            assertEquals(cur.size(), win.getIntValue("visitors"), at + " 到店玩家");
            assertEquals(curC, win.getIntValue("checkins"), at + " 打卡");
            assertEquals(curR, win.getIntValue("redeems"), at + " 核销");
            assertEquals(prev.size(), win.getIntValue("prevVisitors"), at + " 上期到店玩家");
            assertEquals(prevC, win.getIntValue("prevCheckins"), at + " 上期打卡");
            assertEquals(prevR, win.getIntValue("prevRedeems"), at + " 上期核销");
            JSONArray daily = win.getJSONArray("daily");
            assertEquals(days, daily.size(), at + " 逐日格数");
            for (int i = 0; i < days; i++) {
                JSONObject d = daily.getJSONObject(i);
                assertEquals(start.plusDays(i).format(label), d.getString("date"), at + " 第" + i + "格日期");
                assertEquals(dayMembers.get(i).size(), d.getIntValue("visitors"), at + " " + d.getString("date") + " 到店");
                assertEquals(dayC[i], d.getIntValue("checkins"), at + " " + d.getString("date") + " 打卡");
                assertEquals(dayR[i], d.getIntValue("redeems"), at + " " + d.getString("date") + " 核销");
            }
        }
        JSONObject week = windows.getJSONObject(0);
        assertTrue(week.getJSONArray("daily").getJSONObject(6).getIntValue("visitors") >= 1, "今天 00:00:05 落在今天");
        assertTrue(week.getJSONArray("daily").getJSONObject(0).getIntValue("visitors") >= 1, "窗首日 00:00:01 落在窗内");
        assertTrue(week.getIntValue("prevVisitors") >= 1, "前一日 23:59:59 落在上期");
        assertTrue(windows.getJSONObject(2).getIntValue("prevVisitors") > 0, "90 天窗的上期(91–180 天)有真实回读");

        // 接口外形:控制器原样 success(resp),按 Jackson 序列化后小程序读得到这些路径
        JsonNode body = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(AjaxResult.success(first)));
        assertEquals("城南小馆", body.path("data").path("profile").path("storeName").asText());
        assertEquals(30, body.path("data").path("trends").path("windows").path(1).path("daily").size());
        assertEquals(today.format(label),
                body.path("data").path("trends").path("windows").path(0).path("daily").path(6).path("date").asText());

        // AI 恢复:写 v3 缓存;同日再取走缓存,trends 原样回来,店名仍实时读
        AiMerchantInsightResp ok = new AiMerchantInsightResp();
        IAiCreatorService ai2 = mock(IAiCreatorService.class);
        when(ai2.merchantInsight(anyString(), anyLong())).thenReturn(ok);
        service = insightService(ai2, redis);
        MerchantInsightResp fresh = service.insight(888L);
        String key = "ai:merchant:insight:v3:888:" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertEquals(Collections.singleton(key), redis.keySet(), "缓存键是 v3");
        exec("UPDATE mms_merchant SET name='城南小馆·新' WHERE member_id=888");
        MerchantInsightResp cached = service.insight(888L);
        verify(ai2, times(1)).merchantInsight(anyString(), anyLong());
        assertEquals(fresh.getTrends().toJSONString(), cached.getTrends().toJSONString(), "trends 经缓存往返不变");
        assertEquals(first.getTrends().toJSONString(), cached.getTrends().toJSONString());
        assertEquals("城南小馆·新", cached.getProfile().getString("storeName"), "店名不吃缓存");
    }

    private void seedVisit(int reg, long member, long activity, long topic, long chapter, int payment, int status,
                           int entitlementStatus, LocalDateTime checkin, LocalDateTime redeemed,
                           DateTimeFormatter fmt) throws Exception {
        LocalDateTime first = checkin != null ? checkin : redeemed;
        String created = "'" + first.minusHours(1).format(fmt) + "'";
        exec("INSERT INTO cms_registration (id,registration_no,owner_type,owner_id,ticket_id,member_id,purchase_kind,"
                + "payment_status,registration_status,payment_time,pay_expire_time,ticket_price,original_amount,"
                + "total_amount,payable_amount,create_time,update_time) VALUES (" + reg + ",'REG" + reg + "',2,"
                + activity + "," + (topic == 1 ? 1000 : 2000) + "," + member + ",3," + payment + "," + status + ","
                + created + "," + created + ",10,10,10,10," + created + "," + created + ")");
        exec("INSERT INTO registration_entitlement(id,registration_id,chapter_id,topic_id,status,create_time) VALUES ("
                + reg + "," + reg + "," + chapter + "," + topic + "," + entitlementStatus + "," + created + ")");
        if (checkin != null) {
            exec("INSERT INTO player_node_progress(id,member_id,activity_id,topic_id,node_id,status,checkin_time,del_flag)"
                    + " VALUES (" + reg + "," + member + "," + activity + "," + topic + "," + topic + ",1,'"
                    + checkin.format(fmt) + "',0)");
        }
        if (redeemed != null) {
            exec("INSERT INTO redemption(id,registration_id,chapter_id,topic_id,merchant_id,offer_id,operator_id,status,"
                    + "idempotency_key,create_time) VALUES (" + reg + "," + reg + "," + chapter + "," + topic
                    + ",888,1,1,'ACTIVE','k" + reg + "','" + redeemed.format(fmt) + "')");
        }
    }

    @SuppressWarnings("unchecked")
    private MerchantInsightServiceImpl insightService(IAiCreatorService ai, Map<String, Object> redis) {
        RedisTemplate<Object, Object> template = mock(RedisTemplate.class);
        ValueOperations<Object, Object> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get(any())).thenAnswer(i -> redis.get(String.valueOf(i.getArgument(0))));
        doAnswer(i -> { redis.put(String.valueOf(i.getArgument(0)), i.getArgument(1)); return null; })
                .when(values).set(any(), any(), anyLong(), any(TimeUnit.class));
        ICmsTopicService topics = mock(ICmsTopicService.class);
        when(topics.selectCmsTopicList(any())).thenReturn(new ArrayList<>());
        MerchantInsightServiceImpl service = new MerchantInsightServiceImpl();
        ReflectionTestUtils.setField(service, "merchantInsightMapper", insightMapper);
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", merchantMapper);
        ReflectionTestUtils.setField(service, "aiCreatorService", ai);
        ReflectionTestUtils.setField(service, "redisTemplate", template);
        ReflectionTestUtils.setField(service, "cmsTopicService", topics);
        ReflectionTestUtils.setField(service, "merchantLeadService", mock(IMerchantLeadService.class));
        ReflectionTestUtils.setField(service, "topicContentPublisherProjectionService",
                mock(ITopicContentPublisherProjectionService.class));
        return service;
    }

    // ------------------------------------------------------------------ ③ GameSessionRuntime

    @Test
    void gameSessionProjectionsCountOnlyPaidSuccessMembers() {
        List<Map<String, Object>> members = gameMapper.selectClubMemberProjection(1L);
        assertEquals(1, members.size(), "导演台成员投影只含已付款成功成员");
        assertEquals(42L, ((Number) members.get(0).get("memberId")).longValue());

        List<Map<String, Object>> progress = gameMapper.selectClubTeamProgressProjection(1L);
        assertEquals(1, progress.size());
        assertEquals(2, ((Number) progress.get(0).get("hintLevel")).intValue(),
                "退款成员(43)的提示回执不得计入");
        assertEquals("r1", progress.get(0).get("recentEventRequestId"),
                "最近命令只认已付款成员的权威回执");

        Map<String, Object> recap = gameMapper.selectClubRecapAggregate(1L, 100L);
        assertEquals(1, ((Number) recap.get("paidPlayers")).intValue(),
                "复盘付费玩家只数已付款成功,退款成员不算");
    }

    // ------------------------------------------------------------------ ④ TopicOnsiteMetrics

    @Test
    void topicOnsiteMetricsKeepRefundFactsForJavaAndRejectCancelledFromLoadsAndComplaints() throws Exception {
        List<TopicOnsiteTicketFact> facts = onsiteMapper.selectTicketFacts(1L);
        assertEquals(5, facts.size(), "票事实原样带回(含退款/取消/过期),由 Java 裁决");
        Map<Long, TopicOnsiteTicketFact> byReg = new LinkedHashMap<>();
        for (TopicOnsiteTicketFact fact : facts) byReg.put(fact.getRegistrationId(), fact);
        assertNotNull(byReg.get(1002L).getRefundTime(), "退款时刻必须仍可读(退款率分子)");
        assertNotNull(byReg.get(1002L).getRefundRequestedAt());

        List<Long> waitIds = onsiteMapper.selectWaitFacts(1L).stream()
                .map(TopicWaitFact::getRegistrationId).sorted().collect(Collectors.toList());
        assertEquals(Arrays.asList(1001L, 1002L), waitIds,
                "等待分位数人口是既有合同(不按付款状态过滤),此处只锁幽灵列移除未改变它");

        List<TopicRepeatPaymentFact> repeats = onsiteMapper.selectRepeatPayments(1L);
        assertEquals(1, repeats.size(), "退款/取消/过期后的 later 付款事实不得计入复购");
        assertEquals(42L, repeats.get(0).getMemberId().longValue());

        List<TopicChapterLoadFact> loads = onsiteMapper.selectInStoreLoads(1L);
        assertEquals(1, loads.size(), "退款票不占店内负载");
        assertEquals(11L, loads.get(0).getChapterId().longValue());
        assertEquals(1, loads.get(0).getInStoreLoad());

        assertEquals(1, onsiteMapper.countEffectiveComplaintUsers(1L),
                "绑定退款报名的工单不是有效投诉用户");

        // 其它表的 del_flag 过滤必须原样保留 —— 本修复不是全局删 del_flag。
        exec("UPDATE player_node_progress SET del_flag = 2 WHERE id = 1");
        assertTrue(onsiteMapper.selectInStoreLoads(1L).isEmpty(), "progress.del_flag 过滤仍生效");
        exec("UPDATE player_node_progress SET del_flag = 0 WHERE id = 1");
    }

    // ------------------------------------------------------------------ ⑤ ShopDayMerchantRoster

    @Test
    void shopDayRosterKeepsPaidArrivedVisitorsAndRejectsRefunded() {
        List<ShopDayMerchantRosterFact> roster = rosterMapper.selectRoster(1L, 888L);
        assertEquals(1, roster.size(), "只含本商家本主题已付款成功且到店的报名");
        assertEquals(42L, roster.get(0).getMemberId().longValue());
        assertEquals("REG1001", roster.get(0).getTicketNo());
        assertTrue(rosterMapper.selectRoster(1L, 889L).isEmpty(), "其它商家不得读到本商家排期");
        assertTrue(rosterMapper.selectRoster(2L, 888L).isEmpty(), "其它主题不得串读");
    }

    // ------------------------------------------------------------------ infra

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(key + " 未设置:真库 IT 只认门禁注入的 CHENGYIN_TEST_MYSQL_*,不回落本机默认值");
        }
        return value;
    }

    private static String resolveRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.exists(cwd.resolve("sql/base_schema_20260818.sql"))) return cwd.toString();
        Path parent = cwd.getParent();
        if (parent != null && Files.exists(parent.resolve("sql/base_schema_20260818.sql"))) {
            return parent.toString();
        }
        throw new IllegalStateException("找不到仓库根(sql/base_schema_20260818.sql),cwd=" + cwd);
    }

    private static Connection connectDatabase() throws Exception {
        return DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT + "/" + DATABASE_HOLDER[0] + PARAMS, USER, PASSWORD);
    }

    /** 连接串在 @BeforeAll 才拿到库名;用一个 holder 避免每处重复拼接。 */
    private static final String[] DATABASE_HOLDER = new String[1];

    private static Connection connectAdmin() throws Exception {
        return DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT + "/" + PARAMS, USER, PASSWORD);
    }

    private static void execAdmin(String statement) throws Exception {
        try (Connection c = connectAdmin(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static void exec(String statement) throws Exception {
        try (Connection c = connectDatabase(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static int queryInt(String query) throws Exception {
        try (Connection c = connectDatabase(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void createSchema() throws Exception {
        DATABASE_HOLDER[0] = database;
        for (String table : new String[]{
                "cms_registration", "cms_activity", "cms_topic", "cms_topic_node", "cms_topic_chapter",
                "club", "club_member", "merchant_chapter_offer", "registration_entitlement",
                "redemption", "player_node_progress", "player_growth", "member_interest_profile",
                "ums_member", "ums_member_follow", "oms_ticket", "refund_application", "coop_complaint"}) {
            exec(baseDdl(table));
        }
        // mms_merchant 取生产 SHOW CREATE TABLE:base 少 derivative_benefits/disable_reason/service_*,
        // 真 MmsMerchantMapper 的 select 在 base 上跑不起来(店铺参谋页头读店名/头像走的就是它)。
        exec(prodDdl("mms_merchant"));
        // base_schema 之后主题侧新增列(类型抄各自迁移文件);只补读侧列,让真实 select 能执行。
        exec("ALTER TABLE cms_topic ADD COLUMN circle_theme_code VARCHAR(24) NULL");              // circle_theme_batch2_20260821
        exec("ALTER TABLE cms_topic ADD COLUMN source_template_topic_id BIGINT NULL");            // circle_theme_batch2_20260821
        exec("ALTER TABLE cms_topic ADD COLUMN circle_reviewed_at DATETIME NULL");                // circle_theme_primary_schema_20260821
        exec("ALTER TABLE cms_topic ADD COLUMN complete_rule_json JSON NULL");                    // new_city_setup_plan_20260820
        exec("ALTER TABLE cms_topic ADD COLUMN route_mode VARCHAR(20) NOT NULL DEFAULT 'LINEAR'"); // topic_route_graph_20260823
        exec("ALTER TABLE cms_topic ADD COLUMN route_graph_json JSON NULL");                      // topic_route_graph_20260823
        exec("ALTER TABLE cms_topic ADD COLUMN config_version BIGINT NOT NULL DEFAULT 1");        // topic_route_graph_20260823
        exec("ALTER TABLE cms_topic ADD COLUMN published_route_graph_json JSON NULL");            // topic_route_published_snapshot_20260823
        exec("ALTER TABLE cms_topic ADD COLUMN published_config_version BIGINT NOT NULL DEFAULT 0"); // topic_route_published_snapshot_20260823
        exec("ALTER TABLE cms_topic ADD COLUMN beta_flag TINYINT NOT NULL DEFAULT 0");            // topic_beta_zone_20260821
        exec("ALTER TABLE cms_topic ADD COLUMN team_mode TINYINT NOT NULL DEFAULT 0");            // topic_team_config_20260821
        exec("ALTER TABLE cms_topic ADD COLUMN team_max_members INT NOT NULL DEFAULT 4");         // topic_team_config_20260821
        exec("ALTER TABLE cms_topic ADD COLUMN club_coop_open TINYINT(1) NOT NULL DEFAULT 1");    // club_topic_display_settings_20260906
        exec("ALTER TABLE cms_topic ADD COLUMN club_pinned TINYINT(1) NOT NULL DEFAULT 0");       // club_topic_display_settings_20260906
        exec("ALTER TABLE cms_topic ADD COLUMN club_member_only TINYINT(1) NOT NULL DEFAULT 0");  // club_topic_display_settings_20260906
        exec("ALTER TABLE cms_topic ADD COLUMN game_module_config_json JSON NULL");               // game_session_runtime_20260823
        exec("ALTER TABLE cms_topic ADD COLUMN journey_rules_json JSON NULL");                    // topic_journey_rules_20260916
        exec("ALTER TABLE cms_topic ADD COLUMN journey_story_json JSON NULL");                    // topic_journey_story_20260916
        exec("ALTER TABLE cms_topic ADD COLUMN theme_runtime_config_json MEDIUMTEXT NULL");       // theme_runtime_20261004
        exec("ALTER TABLE cms_topic ADD COLUMN published_theme_runtime_config_json MEDIUMTEXT NULL"); // theme_runtime_20261004
        exec("ALTER TABLE cms_topic_chapter ADD COLUMN circle_candidate_code VARCHAR(8) NULL");   // circle_theme_batch2_20260821
        exec("ALTER TABLE cms_topic_chapter ADD COLUMN circle_role VARCHAR(16) NULL");            // circle_theme_batch2_20260821
        exec("ALTER TABLE cms_topic_chapter ADD COLUMN circle_policy VARCHAR(24) NULL");          // circle_theme_batch2_20260821
        exec("ALTER TABLE cms_topic_chapter ADD COLUMN circle_enabled TINYINT NOT NULL DEFAULT 1"); // circle_theme_batch2_20260821

        exec(migrationDdl("chengyinhub-admin/sql/migration_circle_theme_batch2_20260821.sql",
                "merchant_circle_supply_profile"));
        exec(migrationDdl("chengyinhub-admin/sql/migration_20260820_play_team_core.sql", "play_team"));
        exec(migrationDdl("chengyinhub-admin/sql/migration_20260820_play_team_core.sql", "play_team_member"));
        for (String table : new String[]{"game_session", "game_station_run", "game_team_role_assignment",
                "game_command_receipt", "game_task_submission", "game_player_node_outcome"}) {
            exec(migrationDdl("chengyinhub-admin/sql/migration_20260823_game_session_runtime.sql", table));
        }
    }

    private static String baseDdl(String table) throws Exception {
        String source = Files.readString(Paths.get(ROOT, "sql/base_schema_20260818.sql"), StandardCharsets.UTF_8);
        // 结尾按「整行到分号」取,不能用 [^;]* —— 表 COMMENT 里本身可能带分号(base 的 merchant_chapter_offer)。
        Matcher matcher = Pattern.compile("CREATE TABLE `" + table + "` \\(.*?\\n\\) [^\\n]*;",
                Pattern.DOTALL).matcher(source);
        assertTrue(matcher.find(), "base_schema 缺少 " + table);
        return matcher.group().replaceAll(";$", "");
    }

    private static String prodDdl(String table) throws Exception {
        String source = Files.readString(Paths.get(ROOT,
                "chengyinhub-admin/src/test/resources/it-schema/run41-prod-ddl.sql"), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("CREATE TABLE `" + table + "` \\(.*?\\n\\) [^\\n]*;",
                Pattern.DOTALL).matcher(source);
        assertTrue(matcher.find(), "run41 生产 DDL 缺少 " + table);
        return matcher.group().replaceAll(";$", "");
    }

    private static String migrationDdl(String relativeFile, String table) throws Exception {
        String source = Files.readString(Paths.get(ROOT, relativeFile), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile(
                "CREATE TABLE (?:IF NOT EXISTS )?`?" + table + "`? \\(.*?\\n\\) [^\\n]*;",
                Pattern.DOTALL).matcher(source);
        assertTrue(matcher.find(), relativeFile + " 缺少 " + table);
        return matcher.group().replaceAll(";$", "");
    }

    private void openMappers() throws Exception {
        UnpooledDataSource source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                "jdbc:mysql://" + HOST + ":" + PORT + "/" + database + PARAMS, USER, PASSWORD);
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        configuration.getTypeAliasRegistry().registerAlias("MmsMerchant", MmsMerchant.class);
        configuration.setEnvironment(new Environment("regread",
                new SpringManagedTransactionFactory(), source));
        for (String mapper : new String[]{"CmsTopicMapper", "MerchantInsightMapper",
                "GameSessionRuntimeMapper", "TopicOnsiteMetricsMapper", "ShopDayMerchantRosterMapper",
                "MmsMerchantMapper"}) {
            String path = ROOT + "/chengyinhub-system/src/main/resources/mapper/business/" + mapper + ".xml";
            try (InputStream in = new FileInputStream(path)) {
                new XMLMapperBuilder(in, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        topicMapper = sql.getMapper(CmsTopicMapper.class);
        insightMapper = sql.getMapper(MerchantInsightMapper.class);
        gameMapper = sql.getMapper(GameSessionRuntimeMapper.class);
        onsiteMapper = sql.getMapper(TopicOnsiteMetricsMapper.class);
        rosterMapper = sql.getMapper(ShopDayMerchantRosterMapper.class);
        merchantMapper = sql.getMapper(MmsMerchantMapper.class);
    }

    private static List<Long> ids(List<CmsTopic> topics) {
        List<Long> result = new ArrayList<>();
        for (CmsTopic topic : topics) result.add(topic.getId());
        return result;
    }
}

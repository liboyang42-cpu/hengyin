package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.impl.ShopDayProofServiceImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R16-ARRIVE:同主题同章节同地点的多个节点共享到店记录(一站多步只扫一次码)。
 *
 * <p>真跑两张 MyBatis XML + 真唯一键,不用 mock:本改动的两处承重都在 SQL 侧 ——
 * 兄弟节点查询受「未审商家节点不可见」闸过滤(selectCmsTopicNodeList),共享写入靠
 * uk_progress(member_id, topic_id, node_id, activity_id) 兜并发。mock 掉就再也测不到。
 *
 * <p>方案 b(markArrived 时补写兄弟到店行)的理由:拍照、完成、核销等下游都按 node 读
 * player_node_progress 行;写侧共享一次,下游一行不改。
 */
class PlayerNodeArrivalSharingH2Test {

    private static final long MEMBER = 9L;
    private static final long OTHER_MEMBER = 10L;
    private static final long ACTIVITY = 11L;
    private static final long OTHER_ACTIVITY = 12L;
    private static final long TOPIC = 7L;
    private static final long OTHER_TOPIC = 8L;
    private static final long CHAPTER = 100L;
    private static final long OTHER_CHAPTER = 101L;
    private static final long MERCHANT = 88L;
    private static final long OTHER_MERCHANT = 99L;

    private static final long SRC = 31L;          // 商家 88,A路1号
    private static final long SAME_SHOP = 32L;    // 商家 88,同章节 ⇒ 共享
    private static final long CROSS_CHAPTER = 33L; // 商家 88,别的章节 ⇒ 不共享
    private static final long OTHER_SHOP = 34L;   // 商家 99,同章节同地址 ⇒ 不共享(商家优先)
    private static final long HOST_SRC = 35L;     // 主办方,B路2号
    private static final long HOST_NEAR = 36L;    // 主办方,B路2号,约 14m ⇒ 共享
    private static final long HOST_FAR = 37L;     // 主办方,B路2号,约 70m ⇒ 不共享
    private static final long UNAPPROVED = 38L;   // 商家 88 但未过审 ⇒ 玩家侧不可见,不共享
    private static final long MIXED_HOST = 39L;   // 主办方,A路1号,坐标同 SRC ⇒ 共享(地址+坐标)
    private static final long CROSS_TOPIC = 50L;  // 别的主题,同章节同商家 ⇒ 不共享
    private static final long GAME_ONLY = 40L;    // 只答游戏不扫码的节点

    private static final String DDL_PROGRESS =
            "create table player_node_progress ("
                    + "id bigint primary key auto_increment,"
                    + "member_id bigint not null,"
                    + "activity_id bigint,"
                    + "topic_id bigint,"
                    + "node_id bigint not null,"
                    + "status tinyint not null default 0,"
                    + "checkin_time datetime,"
                    + "pic_url varchar(500),"
                    + "review_status tinyint,"
                    + "longitude varchar(64),"
                    + "latitude varchar(64),"
                    + "puzzle_score smallint,"
                    + "completion_mode varchar(16),"
                    + "del_flag tinyint not null default 0,"
                    + "create_time datetime,"
                    + "constraint uk_progress unique (member_id, activity_id, topic_id, node_id))";

    private static final String DDL_NODE =
            "create table cms_topic_node ("
                    + "id bigint primary key auto_increment, topic_id bigint, chapter_id bigint, name varchar(80), "
                    + "description varchar(255), address varchar(255), longitude varchar(40), latitude varchar(40), "
                    + "img_url varchar(500), node_time varchar(40), template_id bigint, "
                    + "merchant_member_id bigint, node_audit_status tinyint, node_audit_reason varchar(200), "
                    + "node_audit_member_id bigint, node_audit_time timestamp, "
                    + "checkin_code varchar(64), business_time varchar(64), status bigint, sort_id bigint, "
                    + "xp_value int, hook_text varchar(255), card_hook_long varchar(255), fragment_text varchar(255), "
                    + "unlock_after_node_id bigint, "
                    + "create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp)";

    private JdbcTemplate jdbc;
    private CmsTopicNodeMapper nodeMapper;
    private PlayerNodeProgressMapper progressMapper;
    private IShopDayProofService service;

    @BeforeEach
    void setUp() throws Exception {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:arrival_sharing_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        DataSource dataSource = ds;

        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(DDL_PROGRESS);
        jdbc.execute("create table topic_route_session(id bigint primary key,activity_id bigint,topic_id bigint,member_id bigint,play_instance_id varchar(64))");
        jdbc.execute("alter table player_node_progress add column play_instance_id varchar(64) default 'default'");
        jdbc.execute(DDL_NODE);
        jdbc.execute("create table merchant_chapter_offer (id bigint primary key, chapter_id bigint, "
                + "merchant_id bigint, status int, withdraw_reason varchar(200))");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(
                new ClassPathResource("mapper/business/PlayerNodeProgressMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicNodeMapper.xml"));
        SqlSessionFactory sqlSessionFactory = factory.getObject();
        progressMapper = new SqlSessionTemplate(sqlSessionFactory).getMapper(PlayerNodeProgressMapper.class);
        nodeMapper = new SqlSessionTemplate(sqlSessionFactory).getMapper(CmsTopicNodeMapper.class);

        ShopDayProofServiceImpl target = new ShopDayProofServiceImpl();
        ReflectionTestUtils.setField(target, "playerNodeProgressMapper", progressMapper);
        com.chengyinhub.business.service.impl.PlayerNodeProgressServiceImpl owner =
                new com.chengyinhub.business.service.impl.PlayerNodeProgressServiceImpl();
        ReflectionTestUtils.setField(owner, "playerNodeProgressMapper", progressMapper);
        com.chengyinhub.business.mapper.TopicRouteSessionMapper noRoute =
                org.mockito.Mockito.mock(com.chengyinhub.business.mapper.TopicRouteSessionMapper.class);
        // 原H2 fixture明确无route session、使用default；真实owner执行实例解析。
        org.mockito.Mockito.when(noRoute.selectCurrentByOwner(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(null);
        ReflectionTestUtils.setField(owner, "routeSessions", noRoute);
        ReflectionTestUtils.setField(target, "playerNodeProgressService", owner);
        ReflectionTestUtils.setField(target, "cmsTopicNodeMapper", nodeMapper);
        service = target;

        seed();
    }

    /** 1=扫码到店;节点行按 id 从库里读,走与控制器同一条取数路径。 */
    private PlayerNodeProgress scan(long memberId, long activityId, long nodeId) {
        CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(nodeId);
        assertNotNull(node, "fixture 节点必须存在: " + nodeId);
        return service.markArrived(memberId, activityId, node);
    }

    @Test
    void sameChapterSameShop_siblingNodeIsArrivedWithoutScanning() {
        PlayerNodeProgress arrived = scan(MEMBER, ACTIVITY, SRC);

        assertNotNull(arrived.getCheckinTime());
        assertTrue(service.hasArrived(MEMBER, ACTIVITY, SAME_SHOP),
                "同章节同商家的第二个节点必须无需扫码即视为已到店");
        assertEquals(0, rows(MEMBER, ACTIVITY, SAME_SHOP), "共享只写到店行,不是完成:status=0");
        assertTrue(jdbc.queryForObject("select pic_url is null from player_node_progress where node_id=" + SAME_SHOP
                + " and member_id=" + MEMBER, Boolean.class), "共享不得写照片凭证");
        assertEquals(1, jdbc.queryForObject("select count(*) from player_node_progress a join player_node_progress b"
                + " on a.checkin_time = b.checkin_time where a.node_id=" + SRC + " and b.node_id=" + SAME_SHOP
                + " and a.member_id=" + MEMBER + " and b.member_id=" + MEMBER, Integer.class),
                "共享行必须与源行同一 checkin_time");
    }

    @Test
    void sharedArrivalIsScopedToMemberAndActivity() {
        scan(MEMBER, ACTIVITY, SRC);

        assertFalse(service.hasArrived(OTHER_MEMBER, ACTIVITY, SAME_SHOP), "他人到店不共享");
        assertFalse(service.hasArrived(MEMBER, OTHER_ACTIVITY, SAME_SHOP), "不跨 activity 共享");
        assertEquals(0, countRows(OTHER_MEMBER, OTHER_ACTIVITY));
    }

    @Test
    void crossChapterSameShopDoesNotShare() {
        scan(MEMBER, ACTIVITY, SRC);

        assertFalse(service.hasArrived(MEMBER, ACTIVITY, CROSS_CHAPTER),
                "★负控:去掉「同一章节」限制,这条必红 —— 跨章节不得共享");
    }

    @Test
    void differentShopInSameChapterDoesNotShare() {
        scan(MEMBER, ACTIVITY, SRC);

        assertFalse(service.hasArrived(MEMBER, ACTIVITY, OTHER_SHOP),
                "★负控:去掉商家/距离判定,这条必红 —— 同章不同店不得共享");
        assertFalse(service.hasArrived(MEMBER, ACTIVITY, UNAPPROVED),
                "未过审的商家节点在玩家侧不可见,不得被共享到店(可见性闸)");
    }

    @Test
    void hostNodesFallBackToAddressPlusThirtyMeters() {
        scan(MEMBER, ACTIVITY, HOST_SRC);

        assertTrue(service.hasArrived(MEMBER, ACTIVITY, HOST_NEAR),
                "无商家绑定时,同地址且 ≤30m 的兄弟节点共享");
        assertFalse(service.hasArrived(MEMBER, ACTIVITY, HOST_FAR),
                "★负控:同地址但 >30m,不得共享");
    }

    @Test
    void mixedMerchantHostPairFallsBackToAddressPlusDistance() {
        scan(MEMBER, ACTIVITY, SRC);

        assertTrue(service.hasArrived(MEMBER, ACTIVITY, MIXED_HOST),
                "一方无商家绑定时按地址+坐标判定:同一地点应共享");
        assertFalse(service.hasArrived(MEMBER, ACTIVITY, CROSS_TOPIC),
                "跨主题同地点不共享");
    }

    @Test
    void repeatedScanDoesNotDuplicateSharedRows() {
        scan(MEMBER, ACTIVITY, SRC);
        scan(MEMBER, ACTIVITY, SRC);

        assertEquals(1, jdbc.queryForObject("select count(*) from player_node_progress where member_id=" + MEMBER
                + " and activity_id=" + ACTIVITY + " and node_id=" + SAME_SHOP, Integer.class),
                "重复到店不得重复写兄弟行");
        assertEquals(1, jdbc.queryForObject("select count(*) from player_node_progress where member_id=" + MEMBER
                + " and activity_id=" + ACTIVITY + " and node_id=" + SRC, Integer.class),
                "本人节点也只有一行");
    }

    @Test
    void withdrawnMerchantNodesNeverInheritHostArrival() {
        jdbc.update("insert into merchant_chapter_offer(id, chapter_id, merchant_id, status, withdraw_reason) "
                + "values (1, ?, ?, 0, ?)", CHAPTER, MERCHANT, "商家退出");

        scan(MEMBER, ACTIVITY, MIXED_HOST);

        assertTrue(service.hasArrived(MEMBER, ACTIVITY, MIXED_HOST));
        assertFalse(service.hasArrived(MEMBER, ACTIVITY, SRC), "退出商家节点不得继承同址主办节点的到店记录");
        assertFalse(service.hasArrived(MEMBER, ACTIVITY, SAME_SHOP));
        assertTrue(service.hasArrived(MEMBER, ACTIVITY, OTHER_SHOP), "退出只影响该商家，不隐藏同址其他已审商家");
        assertEquals(2, countRows(MEMBER, ACTIVITY), "只为仍可见的同址节点写入到店记录");
    }

    @Test
    void completingGameplayAloneNeverGrantsSiblingArrival() {
        assertFalse(service.markGameDone(MEMBER, ACTIVITY, TOPIC, GAME_ONLY), "没有到店行,游戏完成无处推进");

        assertFalse(service.hasArrived(MEMBER, ACTIVITY, GAME_ONLY));
        assertFalse(service.hasArrived(MEMBER, ACTIVITY, SAME_SHOP), "只玩玩法不给兄弟节点到店");
    }

    private int rows(long memberId, long activityId, long nodeId) {
        return jdbc.queryForObject("select status from player_node_progress where member_id=" + memberId
                + " and activity_id=" + activityId + " and node_id=" + nodeId, Integer.class);
    }

    private int countRows(long memberId, long activityId) {
        return jdbc.queryForObject("select count(*) from player_node_progress where member_id=" + memberId
                + " and activity_id=" + activityId, Integer.class);
    }

    private void seed() {
        // 商家节点:同商家同章节共享;跨章节、异商家、未过审都不共享
        node("SRC", SRC, TOPIC, CHAPTER, MERCHANT, 1, "A路1号", "31.200000", "121.400000");
        node("SAME_SHOP", SAME_SHOP, TOPIC, CHAPTER, MERCHANT, 1, "A路1号", "31.200000", "121.400000");
        node("CROSS_CHAPTER", CROSS_CHAPTER, TOPIC, OTHER_CHAPTER, MERCHANT, 1, "A路1号", "31.200000", "121.400000");
        node("OTHER_SHOP", OTHER_SHOP, TOPIC, CHAPTER, OTHER_MERCHANT, 1, "A路1号", "31.200000", "121.400000");
        node("UNAPPROVED", UNAPPROVED, TOPIC, CHAPTER, MERCHANT, 0, "A路1号", "31.200000", "121.400000");
        // 主办方节点:同地址 + 30 米内共享(HOST_NEAR ≈ 14m,HOST_FAR ≈ 70m)
        node("HOST_SRC", HOST_SRC, TOPIC, CHAPTER, null, null, "B路2号", "31.300000", "121.500000");
        node("HOST_NEAR", HOST_NEAR, TOPIC, CHAPTER, null, null, "B路2号", "31.300100", "121.500100");
        node("HOST_FAR", HOST_FAR, TOPIC, CHAPTER, null, null, "B路2号", "31.300500", "121.500500");
        // 混合配对:商家源节点 vs 主办方同址节点(按地址+坐标共享)
        node("MIXED_HOST", MIXED_HOST, TOPIC, CHAPTER, null, null, "A路1号", "31.200000", "121.400000");
        // 跨主题:同章节同商家同坐标也不共享
        node("CROSS_TOPIC", CROSS_TOPIC, OTHER_TOPIC, CHAPTER, MERCHANT, 1, "A路1号", "31.200000", "121.400000");
        node("GAME_ONLY", GAME_ONLY, TOPIC, CHAPTER, MERCHANT, 1, "A路1号", "31.200000", "121.400000");
    }

    private void node(String name, long id, long topicId, long chapterId, Long merchantId, Integer auditStatus,
                      String address, String latitude, String longitude) {
        jdbc.update("insert into cms_topic_node(id, topic_id, chapter_id, name, status, merchant_member_id, "
                        + "node_audit_status, address, latitude, longitude) values (?,?,?,?,1,?,?,?,?,?)",
                id, topicId, chapterId, name, merchantId, auditStatus, address, latitude, longitude);
    }
}

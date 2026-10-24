package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B5 体验层的<b>跨接口时序</b>：扫码进店 → 答对游戏 → 拍现场凭证。
 *
 * <p>★为什么必须用 H2 跑真 MyBatis XML，不能 mock mapper：这个缺陷是
 * <b>Java 守卫与 SQL where 子句两层同时</b>把 status 当「节点是否完成」用，而
 * {@code markShopDayGameDone} 又把同一列改写成「游戏是否答完」。mapper 一旦被 mock，
 * SQL 里那句 {@code and status = 0} 就不再参与，删掉它做负控测试照样绿 —— 那是假绿。
 * 既有 {@code ShopDayProofServiceTest} 正是全 mock 的，所以它 5 条全绿却抓不到本缺陷。
 *
 * <p>★为什么既有 7 个契约测试也抓不到：它们全是 wxml↔js 字段绑定与函数体级正则负控，
 * 没有一条跑「答题 → 拍照」这条<b>跨接口时序</b>。状态机冲突只有按真实顺序调公共
 * service 出口才暴露得出来。
 *
 * <p>失败链（配了游戏的店 100% 触发，非边缘情况）：
 * 扫码建行 status=0 → 自动弹游戏卡 → 答对走 markGameDone 把 status 改成 1 →
 * 游戏卡按钮文案就是「下一步 · 拍现场凭证」→ submitPhoto 撞
 * {@code 该节点已完成，不能改写到店凭证}，SQL 侧同样 0 行。
 * 后果：凡配了游戏的店，玩家永远交不上到店照片，{@code selfReported} 恒 false，
 * 商家页三步条第 2 步永久点不亮 —— 而通关走 registration_entitlement，
 * <b>不报警、无资损，只静默丢失履约照片证据</b>。
 */
class ShopDayGameThenPhotoH2Test {

    private static final long MEMBER = 9L;
    private static final long ACTIVITY = 11L;
    private static final long TOPIC = 7L;
    private static final long NODE = 31L;
    private static final String PROOF = "https://img/proof.jpg";

    /** 只建本用例真正要用的列；uk_progress 照生产形状建，markArrived 的重复键分支才是真的。 */
    private static final String DDL =
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

    private JdbcTemplate jdbc;
    private PlayerNodeProgressMapper mapper;
    private IShopDayProofService service;

    @BeforeEach
    void setUp() throws Exception {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:shop_day_proof_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        DataSource dataSource = ds;

        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(DDL);
        jdbc.execute("create table topic_route_session(id bigint primary key,activity_id bigint,topic_id bigint,member_id bigint,play_instance_id varchar(64))");
        jdbc.execute("alter table player_node_progress add column play_instance_id varchar(64) default 'default'");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/PlayerNodeProgressMapper.xml"));
        SqlSessionFactory sqlSessionFactory = factory.getObject();
        mapper = new SqlSessionTemplate(sqlSessionFactory).getMapper(PlayerNodeProgressMapper.class);

        ShopDayProofServiceImpl target = new ShopDayProofServiceImpl();
        ReflectionTestUtils.setField(target, "playerNodeProgressMapper", mapper);
        com.chengyinhub.business.service.impl.PlayerNodeProgressServiceImpl owner =
                new com.chengyinhub.business.service.impl.PlayerNodeProgressServiceImpl();
        ReflectionTestUtils.setField(owner, "playerNodeProgressMapper", mapper);
        com.chengyinhub.business.mapper.TopicRouteSessionMapper noRoute =
                org.mockito.Mockito.mock(com.chengyinhub.business.mapper.TopicRouteSessionMapper.class);
        // 原H2 fixture明确无route session、使用default；真实owner执行实例解析。
        org.mockito.Mockito.when(noRoute.selectCurrentByOwner(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(null);
        ReflectionTestUtils.setField(owner, "routeSessions", noRoute);
        ReflectionTestUtils.setField(target, "playerNodeProgressService", owner);
        service = target;
    }

    /**
     * ★P0 本体：答完游戏之后必须还能交到店照片。
     *
     * <p>这条按真实顺序走三个公共出口，断言的是<b>数据库那一行</b>的最终样子，
     * 不是「我调过它了」。
     */
    @Test
    void aPlayerWhoAnsweredTheGameCanStillSubmitTheOnsitePhoto() {
        service.markArrived(MEMBER, ACTIVITY, node());
        assertTrue(service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE), "答对游戏应把 status 推到 1");
        assertEquals(1, storedStatus(), "前提：游戏已答完，status 已是 1");

        PlayerNodeProgress saved = service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF);

        // 观测对面那一行，不是回执
        assertEquals(PROOF, storedPicUrl(), "照片必须真的落库");
        assertEquals(0, storedReviewStatus(), "落库同时置为待审核");
        assertEquals(PROOF, saved.getPicUrl(), "返回体要带上刚写进去的照片");
        // selfReported 的判据就是 picUrl 非空（ApiPlayProgressController#shopDayProofData）
        assertTrue(saved.getPicUrl() != null && !saved.getPicUrl().isEmpty(),
                "selfReported 必须为真，否则商家页三步条第 2 步永久点不亮");
        // 「游戏已答」这个事实不能被拍照抹掉
        assertEquals(1, storedStatus(), "拍照不得把 status 改回 0，那会让游戏可以重答一次");
        assertEquals(Integer.valueOf(1), saved.getStatus(),
                "返回体的 status 必须是库里的真值，不能硬写 0 —— 那是对调用方说谎");
    }

    /**
     * ★负控 1：照片不可覆盖性由 picUrl 承重，放宽 status 之后<b>不能</b>连它一起放掉。
     * 第二次换一张图必须仍被拒。
     */
    @Test
    void aSecondDifferentPhotoIsStillRefusedAfterTheGameWasAnswered() {
        service.markArrived(MEMBER, ACTIVITY, node());
        service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE);
        service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.submitPhoto(MEMBER, ACTIVITY, NODE, "https://img/replacement.jpg"));

        assertEquals("到店照片已提交，不能覆盖原始凭证", refused.getMessage());
        assertEquals(PROOF, storedPicUrl(), "原始凭证必须原样留着");
    }

    /** 负控 2：SQL 那一层自己也要挡住覆盖 —— 绕过 Java 守卫直接打 mapper，必须 0 行。 */
    @Test
    void theSqlLayerAlsoRefusesToOverwriteAnExistingPhoto() {
        service.markArrived(MEMBER, ACTIVITY, node());
        service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE);
        service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF);
        Long rowId = storedId();

        assertEquals(0, mapper.updateShopDayPhoto(rowId, "https://img/replacement.jpg"),
                "pic_url is null 是这层的承重条件，Java 闸被绕过时它必须仍然接住");
        assertEquals(PROOF, storedPicUrl());
    }

    /** 负控 3：重放同一张图是幂等的，不许报「写入失败」。 */
    @Test
    void replayingTheSamePhotoIsIdempotent() {
        service.markArrived(MEMBER, ACTIVITY, node());
        service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE);
        service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF);

        PlayerNodeProgress again = service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF);

        assertEquals(PROOF, again.getPicUrl());
        assertEquals(1, countRows(), "重放不得产生第二行");
    }

    /** 负控 4：没扫码进店仍然一律拒 —— 放宽 status 不得把这道前置闸一起放掉。 */
    @Test
    void aPhotoWithoutArrivalIsStillRefused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF));

        assertEquals("请先扫码确认到店", refused.getMessage());
        assertEquals(0, countRows());
    }

    /**
     * ★负控 5（XP 幂等）：并发/重放双答只有一边命中。
     *
     * <p>{@code markShopDayGameDone} 的 {@code and status = 0} 是防 XP 双发的那道条件更新，
     * 本次放宽的是<b>照片</b>那条 SQL，绝不能顺手把这条也放宽。
     */
    @Test
    void answeringTheGameTwiceOnlyCountsOnceSoXpCannotBeDoubleGranted() {
        service.markArrived(MEMBER, ACTIVITY, node());

        assertTrue(service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE), "第一次答完命中 1 行");
        assertFalse(service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE), "第二次恒 0 行 ⇒ 不发第二份 XP");

        // 拍完照之后也不能被重新算成一次新的首次完成
        service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF);
        assertFalse(service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE),
                "拍照不得把 status 打回 0，否则游戏能重答、XP 能重发");
    }

    /** 没配游戏的店（不走 markGameDone）原路径不受影响：status 仍是 0，照样能交照片。 */
    @Test
    void aStoreWithoutAGameKeepsTheOriginalArriveThenPhotoPath() {
        service.markArrived(MEMBER, ACTIVITY, node());

        PlayerNodeProgress saved = service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF);

        assertEquals(PROOF, storedPicUrl());
        assertEquals(0, storedStatus(), "没答游戏就不该有人把 status 推到 1");
        assertEquals(Integer.valueOf(0), saved.getStatus());
        assertNotNull(saved.getCheckinTime());
    }

    /** 到店记录本身仍然是「未完成」起点，不许假装完成。 */
    @Test
    void arrivalItselfStillStartsAtPending() {
        PlayerNodeProgress arrived = service.markArrived(MEMBER, ACTIVITY, node());

        assertEquals(Integer.valueOf(0), arrived.getStatus());
        assertNotNull(arrived.getCheckinTime());
        assertNull(storedPicUrl());
        assertTrue(service.hasArrived(MEMBER, ACTIVITY, NODE));
    }

    /** 真 MyBatis + 真唯一键：同一节点重放只能保留一行，并回读既有事实。 */
    @Test
    void repeatedArrivalUsesTheRealProgressUniqueKeyIdempotently() {
        PlayerNodeProgress first = service.markArrived(MEMBER, ACTIVITY, node());
        PlayerNodeProgress replay = service.markArrived(MEMBER, ACTIVITY, node());

        assertEquals(1, countRows());
        assertEquals(first.getId(), replay.getId());
        assertNotNull(replay.getCheckinTime());
    }

    private static CmsTopicNode node() {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        return node;
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("select * from player_node_progress where node_id = " + NODE);
    }

    private Long storedId() {
        return ((Number) row().get("id")).longValue();
    }

    private int storedStatus() {
        return ((Number) row().get("status")).intValue();
    }

    private String storedPicUrl() {
        Object value = row().get("pic_url");
        return value == null ? null : String.valueOf(value);
    }

    private int storedReviewStatus() {
        return ((Number) row().get("review_status")).intValue();
    }

    private int countRows() {
        return jdbc.queryForObject("select count(*) from player_node_progress", Integer.class);
    }
}

package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.impl.PlayerNodeProgressServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.ThemeRuntimeFeaturePolicy;
import com.chengyinhub.common.exception.ServiceException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

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
                    + "play_instance_id varchar(64) not null default 'default',"
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
                    + "constraint uk_progress unique (member_id, activity_id, topic_id, node_id, play_instance_id))";

    private JdbcTemplate jdbc;
    private PlayerNodeProgressMapper mapper;
    private IShopDayProofService service;
    private ShopDayProofServiceImpl shopTarget;
    private ShopDayH2RuntimeFixture shopRuntime;
    private com.chengyinhub.business.mapper.CmsTopicNodeMapper nodeMapper;

    @BeforeEach
    void setUp() throws Exception {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:shop_day_proof_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        DataSource dataSource = ds;

        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(DDL);
        jdbc.execute(ShopDayH2RuntimeFixture.DDL_ROUTE);
        jdbc.execute(PlayerNodeArrivalSharingH2Test.DDL_NODE);
        jdbc.update("insert into cms_topic_node(id,topic_id,status) values(?,?,1)", NODE, TOPIC);

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/PlayerNodeProgressMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicNodeMapper.xml"),
                new ClassPathResource("mapper/business/TopicRouteSessionMapper.xml"));
        SqlSessionFactory sqlSessionFactory = factory.getObject();
        mapper = new SqlSessionTemplate(sqlSessionFactory).getMapper(PlayerNodeProgressMapper.class);

        ShopDayProofServiceImpl target = new ShopDayProofServiceImpl();
        ReflectionTestUtils.setField(target, "playerNodeProgressMapper", mapper);
        nodeMapper = new SqlSessionTemplate(sqlSessionFactory).getMapper(com.chengyinhub.business.mapper.CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(target, "cmsTopicNodeMapper", nodeMapper);
        shopTarget = target;
        shopRuntime = new ShopDayH2RuntimeFixture(dataSource, sqlSessionFactory, nodeMapper, mapper);
        service = shopRuntime.service(target);
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

    /** Real MyBatis/H2 instance predicates; no SQL JSON helpers or current-session inference. */
    @Test
    void explicitProgressReadsIsolateReplaysAndLegacyReadsStayDefault() {
        Long original = progress("default", 0).getId();
        Long old = progress("theme-old", 0).getId();
        Long current = progress("theme-current", 0).getId();
        jdbc.update("insert into topic_route_session(id,activity_id,topic_id,member_id,play_instance_id) values (999, ?, ?, ?, 'unrelated-latest')",
                ACTIVITY, TOPIC, MEMBER);

        assertEquals(original, mapper.selectPlayerNodeProgressList(progressProbe()).get(0).getId());
        assertEquals(old, mapper.selectPlayerNodeProgressListForInstance(progressProbe(), "theme-old").get(0).getId());
        assertEquals(current, mapper.selectPlayerNodeProgressListForInstance(progressProbe(), "theme-current").get(0).getId());
        assertTrue(mapper.selectPlayerNodeProgressListForInstance(progressProbe(), null).isEmpty());
        PlayerNodeProgress otherOwner = progressProbe(); otherOwner.setMemberId(MEMBER + 1);
        assertTrue(mapper.selectPlayerNodeProgressListForInstance(otherOwner, "theme-current").isEmpty());
        PlayerNodeProgress otherActivity = progressProbe(); otherActivity.setActivityId(ACTIVITY + 1);
        assertTrue(mapper.selectPlayerNodeProgressListForInstance(otherActivity, "theme-current").isEmpty());
        PlayerNodeProgress otherTopic = progressProbe(); otherTopic.setTopicId(TOPIC + 1);
        assertTrue(mapper.selectPlayerNodeProgressListForInstance(otherTopic, "theme-current").isEmpty());
    }

    @Test
    void gameCompletionUsesExactlyOneExplicitInstanceAndPreservesConditionalUniqueness() {
        Long original = progress("default", 0).getId();
        Long old = progress("theme-old", 0).getId();
        Long current = progress("theme-current", 0).getId();
        assertEquals(0, mapper.markShopDayGameDoneForInstance(MEMBER, ACTIVITY, TOPIC + 1, NODE, "theme-current"));
        assertEquals(0, mapper.markShopDayGameDoneForInstance(MEMBER, ACTIVITY, TOPIC, NODE, null));
        assertEquals(1, mapper.markShopDayGameDoneForInstance(MEMBER, ACTIVITY, TOPIC, NODE, "theme-current"));
        assertEquals(0, mapper.markShopDayGameDoneForInstance(MEMBER, ACTIVITY, TOPIC, NODE, "theme-current"));
        assertEquals(Integer.valueOf(0), mapper.selectPlayerNodeProgressById(old).getStatus());
        assertEquals(Integer.valueOf(0), mapper.selectPlayerNodeProgressById(original).getStatus());
        assertEquals(Integer.valueOf(1), mapper.selectPlayerNodeProgressById(current).getStatus());
        assertEquals(1, mapper.markShopDayGameDone(MEMBER, ACTIVITY, TOPIC, NODE));
        assertEquals(Integer.valueOf(0), mapper.selectPlayerNodeProgressById(old).getStatus());
    }

    @Test
    void photoSqlRequiresTheExactOwnerScopeAndInstance() {
        Long current = progress("theme-current", 0).getId();
        assertEquals(0, mapper.markPhotoMachineApproved(current));
        assertEquals(0, mapper.markPhotoMachineApprovedForInstance(current, MEMBER + 1, ACTIVITY, TOPIC, "theme-current"));
        assertEquals(0, mapper.markPhotoMachineApprovedForInstance(current, MEMBER, ACTIVITY + 1, TOPIC, "theme-current"));
        assertEquals(0, mapper.markPhotoMachineApprovedForInstance(current, MEMBER, ACTIVITY, TOPIC + 1, "theme-current"));
        assertEquals(0, mapper.markPhotoMachineApprovedForInstance(current, MEMBER, ACTIVITY, TOPIC, "theme-old"));
        assertEquals(1, mapper.markPhotoMachineApprovedForInstance(current, MEMBER, ACTIVITY, TOPIC, "theme-current"));
        assertEquals(0, mapper.markPhotoMachineApprovedForInstance(current, MEMBER, ACTIVITY, TOPIC, "theme-current"));
    }

    @Test
    void progressServiceUsesRuntimeAuthorityForReadsAndRejectsClientInstances() {
        progress("default", 0); progress("theme-old", 0);
        Long current = progress("theme-current", 0).getId();
        PlayerNodeProgressServiceImpl target = progressService(runtime("theme-current"));
        assertEquals(current, target.selectPlayerNodeProgressList(progressProbe()).get(0).getId());
        PlayerNodeProgress forged = progressProbe(); forged.setPlayInstanceId("theme-old");
        assertThrows(ServiceException.class, () -> target.selectPlayerNodeProgressList(forged));
        assertThrows(ServiceException.class, () -> target.insertPlayerNodeProgress(forged));
        assertEquals(3, countRows());
    }

    @Test
    void photoServiceRejectsHistoricalRowsBeforeWritingAndApprovesCurrentOnlyOnce() {
        Long old = progress("theme-old", 0).getId();
        Long current = progress("theme-current", 0).getId();
        ITopicRouteRuntimeService runtime = runtime("theme-current");
        PlayerNodeProgressServiceImpl target = progressService(runtime);
        assertThrows(ServiceException.class, () -> target.markPhotoMachineApproved(old));
        assertEquals(Integer.valueOf(0), mapper.selectPlayerNodeProgressById(old).getStatus());
        assertEquals(1, target.markPhotoMachineApproved(current));
        assertEquals(0, target.markPhotoMachineApproved(current));
        verify(runtime, org.mockito.Mockito.times(2)).assertCurrentPlayInstance(MEMBER, TOPIC, ACTIVITY, "theme-current");
    }

    @Test
    void aReplayBetweenInstanceReadAndWriteIsRejectedBeforeInsertOrApproval() {
        Long current = progress("theme-current", 0).getId();
        ITopicRouteRuntimeService runtime = runtime("theme-current");
        when(runtime.assertCurrentPlayInstance(MEMBER, TOPIC, ACTIVITY, "theme-current"))
                .thenThrow(new ServiceException("本局已更新，请刷新", 409));
        PlayerNodeProgressServiceImpl target = progressService(runtime);
        PlayerNodeProgress next = progressProbe(); next.setNodeId(NODE + 1); next.setStatus(1);
        assertThrows(ServiceException.class, () -> target.insertPlayerNodeProgress(next));
        assertThrows(ServiceException.class, () -> target.markPhotoMachineApproved(current));
        assertEquals(1, countRows());
        assertEquals(Integer.valueOf(0), mapper.selectPlayerNodeProgressById(current).getStatus());
    }

    @Test
    void publishedUnstartedThemeCannotUseProgressWhenDisabledOrEnabled() {
        Long rowId = progress("default", 0).getId();
        TopicRouteSessionMapper sessions = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic(); topic.setId(TOPIC);
        topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1}");
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        TopicRouteRuntimeServiceImpl runtime = new TopicRouteRuntimeServiceImpl(sessions, topics, null, null, null, null);
        PlayerNodeProgressServiceImpl target = progressService(runtime);
        for (boolean enabled : new boolean[] { false, true }) {
            ThemeRuntimeFeaturePolicy policy = new ThemeRuntimeFeaturePolicy();
            ReflectionTestUtils.setField(policy, "enabled", enabled);
            ReflectionTestUtils.setField(runtime, "themeRuntimePolicy", policy);
            assertThrows(ServiceException.class, () -> target.selectPlayerNodeProgressList(progressProbe()));
            assertThrows(ServiceException.class, () -> target.insertPlayerNodeProgress(progressProbe()));
            assertThrows(ServiceException.class, () -> target.markPhotoMachineApproved(rowId));
            assertEquals(1, countRows());
            assertEquals(Integer.valueOf(0), mapper.selectPlayerNodeProgressById(rowId).getStatus());
        }
    }

    @Test
    void legacyRuntimeAuthorityIgnoresAnUnconfiguredNewerInstance() {
        Long original = progress("default", 0).getId(); progress("unrelated-latest", 0);
        TopicRouteSessionMapper sessions = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        TopicRouteSession legacy = routeSession(11L, "default", "{}");
        TopicRouteSession newer = routeSession(12L, "unrelated-latest", "{}");
        when(sessions.selectByOwner(ACTIVITY, TOPIC, MEMBER, "default")).thenReturn(legacy);
        when(sessions.selectCurrentByOwner(ACTIVITY, TOPIC, MEMBER)).thenReturn(newer);
        TopicRouteRuntimeServiceImpl runtime = new TopicRouteRuntimeServiceImpl(sessions, topics, null, null, null, null);
        assertEquals(original, progressService(runtime).selectPlayerNodeProgressList(progressProbe()).get(0).getId());
    }

    @Test
    void missingRuntimeNeverAcceptsAnExplicitThemeInstance() {
        PlayerNodeProgress row = progressProbe(); row.setPlayInstanceId("theme-current");
        PlayerNodeProgressServiceImpl target = progressService(null);
        assertThrows(ServiceException.class, () -> target.selectPlayerNodeProgressList(row));
        assertThrows(ServiceException.class, () -> target.insertPlayerNodeProgress(row));
        assertEquals(0, countRows());
    }

    @Test
    void shopDayReplayNeverReusesDefaultOrPreviousArrivalGameOrPhoto() {
        PlayerNodeProgress original = service.markArrived(MEMBER, ACTIVITY, node());
        service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE);
        service.submitPhoto(MEMBER, ACTIVITY, NODE, "https://img/default.jpg");
        shopRuntime.theme(1L, MEMBER, ACTIVITY, TOPIC, "theme-old");
        assertFalse(service.hasArrived(MEMBER, ACTIVITY, NODE));
        assertFalse(service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE));
        assertThrows(IllegalStateException.class, () -> service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF));
        PlayerNodeProgress old = service.markArrived(MEMBER, ACTIVITY, node());
        service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE);
        service.submitPhoto(MEMBER, ACTIVITY, NODE, "https://img/old.jpg");
        shopRuntime.theme(2L, MEMBER, ACTIVITY, TOPIC, "theme-current");
        assertFalse(service.hasArrived(MEMBER, ACTIVITY, NODE));
        assertFalse(service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE));
        assertThrows(IllegalStateException.class, () -> service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF));
        PlayerNodeProgress current = service.markArrived(MEMBER, ACTIVITY, node());
        assertEquals("theme-current", current.getPlayInstanceId());
        assertEquals(Integer.valueOf(0), current.getStatus());
        assertNull(current.getPicUrl());
        assertTrue(service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE));
        assertFalse(service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE));
        assertEquals(PROOF, service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF).getPicUrl());
        assertEquals(current.getId(), service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF).getId());
        assertThrows(IllegalStateException.class, () -> service.submitPhoto(MEMBER, ACTIVITY, NODE, "https://img/new.jpg"));
        assertEquals(3, countRows());
        assertEquals("https://img/default.jpg", mapper.selectPlayerNodeProgressById(original.getId()).getPicUrl());
        assertEquals("https://img/old.jpg", mapper.selectPlayerNodeProgressById(old.getId()).getPicUrl());
        assertEquals(Integer.valueOf(1), mapper.selectPlayerNodeProgressById(current.getId()).getStatus());
    }

    @Test
    void shopDayAuthoritativeNodeRejectsTopicMismatchAndMissingNode() {
        CmsTopicNode forged = node(); forged.setTopicId(TOPIC + 1);
        assertThrows(IllegalArgumentException.class, () -> service.markArrived(MEMBER, ACTIVITY, forged));
        assertThrows(IllegalArgumentException.class, () -> service.markGameDone(MEMBER, ACTIVITY, TOPIC + 1, NODE));
        assertThrows(IllegalArgumentException.class, () -> service.hasArrived(MEMBER, ACTIVITY, NODE + 1));
        assertThrows(IllegalArgumentException.class, () -> service.submitPhoto(MEMBER, ACTIVITY, NODE + 1, PROOF));
        assertEquals(0, countRows());
        assertEquals(0, shopRuntime.guardCalls);
    }

    @Test
    void unstartedConfiguredThemeAndDisabledFrozenThemeFailBeforeAnyProgressWrite() {
        CmsTopic topic = new CmsTopic(); topic.setId(TOPIC);
        topic.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1}");
        when(shopRuntime.topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        for (boolean enabled : new boolean[] { false, true }) {
            ReflectionTestUtils.setField(shopRuntime.policy, "enabled", enabled);
            assertShopDayBlocked();
            assertEquals(0, countRows());
        }
        shopRuntime.theme(1L, MEMBER, ACTIVITY, TOPIC, "theme-current");
        ReflectionTestUtils.setField(shopRuntime.policy, "enabled", false);
        assertShopDayBlocked();
        assertEquals(0, countRows());
    }

    @Test
    void lateReplayGuardStopsArrivalCompletionAndPhotoWithinTheRealTransaction() {
        shopRuntime.theme(1L, MEMBER, ACTIVITY, TOPIC, "theme-old");
        PlayerNodeProgress old = service.markArrived(MEMBER, ACTIVITY, node());
        shopRuntime.beforeGuard = () -> shopRuntime.theme(2L, MEMBER, ACTIVITY, TOPIC, "theme-current");
        // Replay is inserted after playInstance, before the real locking route guard; rollback removes the fixture insert too.
        assertThrows(ServiceException.class, () -> service.markArrived(MEMBER, ACTIVITY, node()));
        assertThrows(ServiceException.class, () -> service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE));
        assertThrows(ServiceException.class, () -> service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF));
        assertEquals(1, countRows());
        assertEquals(Integer.valueOf(0), mapper.selectPlayerNodeProgressById(old.getId()).getStatus());
        assertNull(mapper.selectPlayerNodeProgressById(old.getId()).getPicUrl());
        assertEquals(1, jdbc.queryForObject("select count(*) from topic_route_session", Integer.class));
    }

    @Test
    void scopedPhotoSqlRejectsEveryWrongScopeAndPreservesEvidenceAndStatus() {
        shopRuntime.theme(1L, MEMBER, ACTIVITY, TOPIC, "theme-current");
        Long id = service.markArrived(MEMBER, ACTIVITY, node()).getId();
        assertEquals(0, mapper.updateShopDayPhoto(id, PROOF), "legacy API is default-only");
        assertEquals(0, mapper.updateShopDayPhotoForInstance(id + 1, MEMBER, ACTIVITY, TOPIC, NODE, "theme-current", PROOF));
        assertEquals(0, mapper.updateShopDayPhotoForInstance(id, MEMBER + 1, ACTIVITY, TOPIC, NODE, "theme-current", PROOF));
        assertEquals(0, mapper.updateShopDayPhotoForInstance(id, MEMBER, ACTIVITY + 1, TOPIC, NODE, "theme-current", PROOF));
        assertEquals(0, mapper.updateShopDayPhotoForInstance(id, MEMBER, ACTIVITY, TOPIC + 1, NODE, "theme-current", PROOF));
        assertEquals(0, mapper.updateShopDayPhotoForInstance(id, MEMBER, ACTIVITY, TOPIC, NODE + 1, "theme-current", PROOF));
        assertEquals(0, mapper.updateShopDayPhotoForInstance(id, MEMBER, ACTIVITY, TOPIC, NODE, "theme-old", PROOF));
        assertEquals(0, mapper.updateShopDayPhotoForInstance(id, MEMBER, ACTIVITY, TOPIC, NODE, null, PROOF));
        jdbc.update("update player_node_progress set status=2 where id=?", id);
        assertThrows(IllegalStateException.class, () -> service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF));
        assertEquals(0, mapper.updateShopDayPhotoForInstance(id, MEMBER, ACTIVITY, TOPIC, NODE, "theme-current", PROOF));
        jdbc.update("update player_node_progress set status=1,del_flag=1 where id=?", id);
        assertEquals(0, mapper.updateShopDayPhotoForInstance(id, MEMBER, ACTIVITY, TOPIC, NODE, "theme-current", PROOF));
        jdbc.update("update player_node_progress set del_flag=0,checkin_time=null where id=?", id);
        assertEquals(0, mapper.updateShopDayPhotoForInstance(id, MEMBER, ACTIVITY, TOPIC, NODE, "theme-current", PROOF));
        jdbc.update("update player_node_progress set checkin_time=current_timestamp where id=?", id);
        assertEquals(1, mapper.updateShopDayPhotoForInstance(id, MEMBER, ACTIVITY, TOPIC, NODE, "theme-current", PROOF));
        assertEquals(0, mapper.updateShopDayPhotoForInstance(id, MEMBER, ACTIVITY, TOPIC, NODE, "theme-current", "https://img/replacement.jpg"));
        assertEquals(PROOF, mapper.selectPlayerNodeProgressById(id).getPicUrl());
        assertEquals(Integer.valueOf(1), mapper.selectPlayerNodeProgressById(id).getStatus());
    }

    @Test
    void nonThemeRuntimeKeepsDefaultEvenWithANewerUnconfiguredSession() {
        PlayerNodeProgress original = service.markArrived(MEMBER, ACTIVITY, node());
        jdbc.update("insert into topic_route_session(id,activity_id,topic_id,member_id,play_instance_id,config_snapshot_json)"
                + " values(1,?,?,?,'unrelated-latest','{}')", ACTIVITY, TOPIC, MEMBER);
        assertEquals(original.getId(), service.markArrived(MEMBER, ACTIVITY, node()).getId());
        assertTrue(service.hasArrived(MEMBER, ACTIVITY, NODE));
        assertTrue(service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE));
        assertEquals("default", service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF).getPlayInstanceId());
        assertEquals(1, countRows());
    }

    @Test
    void duplicateArrivalOnlyReadsBackTheExactInstance() {
        PlayerNodeProgress original = service.markArrived(MEMBER, ACTIVITY, node());
        // Deliberately retain a pre-migration uniqueness shape: another instance must not satisfy this duplicate.
        jdbc.execute("alter table player_node_progress add constraint old_unique unique(member_id,activity_id,topic_id,node_id)");
        shopRuntime.theme(1L, MEMBER, ACTIVITY, TOPIC, "theme-current");
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> service.markArrived(MEMBER, ACTIVITY, node()));
        assertEquals(1, countRows());
        assertEquals("default", mapper.selectPlayerNodeProgressById(original.getId()).getPlayInstanceId());
        assertFalse(service.hasArrived(MEMBER, ACTIVITY, NODE));
    }

    @Test
    void duplicateArrivalRaceUsesRealUniqueKeyAndSameInstanceReadback() {
        shopRuntime.theme(1L, MEMBER, ACTIVITY, TOPIC, "theme-current");
        java.util.concurrent.atomic.AtomicBoolean inserted = new java.util.concurrent.atomic.AtomicBoolean();
        PlayerNodeProgressMapper racedMapper = (PlayerNodeProgressMapper) java.lang.reflect.Proxy.newProxyInstance(
                PlayerNodeProgressMapper.class.getClassLoader(), new Class<?>[] { PlayerNodeProgressMapper.class },
                (proxy, method, args) -> {
                    if ("insertPlayerNodeProgress".equals(method.getName()) && inserted.compareAndSet(false, true)) {
                        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
                        try {
                            pool.submit(() -> jdbc.update("insert into player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id,status,checkin_time)"
                                    + " values(?,?,?,?,?,0,current_timestamp)", MEMBER, ACTIVITY, TOPIC, NODE, "theme-current"))
                                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
                        } finally { pool.shutdownNow(); }
                    }
                    try { return method.invoke(mapper, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        ReflectionTestUtils.setField(shopTarget, "playerNodeProgressMapper", racedMapper);
        PlayerNodeProgress result = service.markArrived(MEMBER, ACTIVITY, node());
        assertEquals("theme-current", result.getPlayInstanceId());
        assertNotNull(result.getId());
        assertEquals(1, countRows());
        assertTrue(service.hasArrived(MEMBER, ACTIVITY, NODE));
    }

    @Test
    void routeRowLockSurvivesTheGuardUntilShopDayTransactionCommits() throws Exception {
        shopRuntime.theme(1L, MEMBER, ACTIVITY, TOPIC, "theme-current");
        java.util.concurrent.CountDownLatch guarded = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch continueWrite = new java.util.concurrent.CountDownLatch(1);
        shopRuntime.afterGuard = () -> {
            guarded.countDown();
            try { assertTrue(continueWrite.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        };
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<PlayerNodeProgress> write = pool.submit(() -> service.markArrived(MEMBER, ACTIVITY, node()));
        try {
            assertTrue(guarded.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try (java.sql.Connection connection = shopRuntime.dataSource.getConnection(); java.sql.Statement statement = connection.createStatement()) {
                statement.execute("set lock_timeout 100");
                assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("update topic_route_session set version=version+1 where id=1"));
            }
            assertEquals(0, countRows(), "the service has not yet written progress");
            continueWrite.countDown();
            assertEquals("theme-current", write.get(5, java.util.concurrent.TimeUnit.SECONDS).getPlayInstanceId());
            assertEquals(1, jdbc.update("update topic_route_session set version=version+1 where id=1"));
            assertEquals(1, countRows());
        } finally { continueWrite.countDown(); pool.shutdownNow(); }
    }

    @Test
    void samePhotoWinningAConcurrentConditionalUpdateIsAnIdempotentSuccess() {
        shopRuntime.theme(1L, MEMBER, ACTIVITY, TOPIC, "theme-current");
        PlayerNodeProgress arrival = service.markArrived(MEMBER, ACTIVITY, node());
        service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE);
        photoRace(PROOF);
        PlayerNodeProgress result = service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF);
        assertEquals(arrival.getId(), result.getId());
        assertEquals(PROOF, result.getPicUrl());
        assertEquals(Integer.valueOf(1), result.getStatus());
        assertEquals(1, countRows());
    }

    @Test
    void differentPhotoWinningAConcurrentConditionalUpdateIsNeverOverwritten() {
        shopRuntime.theme(1L, MEMBER, ACTIVITY, TOPIC, "theme-current");
        PlayerNodeProgress arrival = service.markArrived(MEMBER, ACTIVITY, node());
        photoRace("https://img/winner.jpg");
        assertThrows(IllegalStateException.class, () -> service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF));
        assertEquals("https://img/winner.jpg", mapper.selectPlayerNodeProgressById(arrival.getId()).getPicUrl());
        assertEquals(Integer.valueOf(0), mapper.selectPlayerNodeProgressById(arrival.getId()).getStatus());
    }

    @Test
    void replayCommittedDuringArrivalReadCannotReuseTheHistoricalAnswer() {
        shopRuntime.theme(1L, MEMBER, ACTIVITY, TOPIC, "theme-old");
        service.markArrived(MEMBER, ACTIVITY, node());
        java.util.concurrent.atomic.AtomicBoolean switched = new java.util.concurrent.atomic.AtomicBoolean();
        PlayerNodeProgressMapper raced = (PlayerNodeProgressMapper) java.lang.reflect.Proxy.newProxyInstance(
                PlayerNodeProgressMapper.class.getClassLoader(), new Class<?>[] { PlayerNodeProgressMapper.class },
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(mapper, args);
                        if ("selectPlayerNodeProgressListForInstance".equals(method.getName()) && switched.compareAndSet(false, true)) {
                            separately(() -> shopRuntime.theme(2L, MEMBER, ACTIVITY, TOPIC, "theme-current"));
                        }
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        ReflectionTestUtils.setField(shopTarget, "playerNodeProgressMapper", raced);
        assertThrows(ServiceException.class, () -> service.hasArrived(MEMBER, ACTIVITY, NODE));
        assertFalse(service.hasArrived(MEMBER, ACTIVITY, NODE));
        assertEquals(1, countRows());
    }

    private void photoRace(String winningPhoto) {
        java.util.concurrent.atomic.AtomicBoolean written = new java.util.concurrent.atomic.AtomicBoolean();
        PlayerNodeProgressMapper raced = (PlayerNodeProgressMapper) java.lang.reflect.Proxy.newProxyInstance(
                PlayerNodeProgressMapper.class.getClassLoader(), new Class<?>[] { PlayerNodeProgressMapper.class },
                (proxy, method, args) -> {
                    if ("updateShopDayPhotoForInstance".equals(method.getName()) && written.compareAndSet(false, true)) {
                        separately(() -> assertEquals(1, mapper.updateShopDayPhotoForInstance((Long) args[0], MEMBER,
                                ACTIVITY, TOPIC, NODE, "theme-current", winningPhoto)));
                    }
                    try { return method.invoke(mapper, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        ReflectionTestUtils.setField(shopTarget, "playerNodeProgressMapper", raced);
    }

    private void separately(Runnable write) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try { pool.submit(write).get(5, java.util.concurrent.TimeUnit.SECONDS); }
        finally { pool.shutdownNow(); }
    }

    private void assertShopDayBlocked() {
        assertThrows(ServiceException.class, () -> service.markArrived(MEMBER, ACTIVITY, node()));
        assertThrows(ServiceException.class, () -> service.hasArrived(MEMBER, ACTIVITY, NODE));
        assertThrows(ServiceException.class, () -> service.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE));
        assertThrows(ServiceException.class, () -> service.submitPhoto(MEMBER, ACTIVITY, NODE, PROOF));
    }

    private ITopicRouteRuntimeService runtime(String instance) {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        when(runtime.playInstance(MEMBER, TOPIC, ACTIVITY)).thenReturn(instance);
        when(runtime.assertCurrentPlayInstance(MEMBER, TOPIC, ACTIVITY, instance)).thenReturn(!"default".equals(instance));
        return runtime;
    }

    private PlayerNodeProgressServiceImpl progressService(ITopicRouteRuntimeService runtime) {
        PlayerNodeProgressServiceImpl target = new PlayerNodeProgressServiceImpl();
        ReflectionTestUtils.setField(target, "playerNodeProgressMapper", mapper);
        ReflectionTestUtils.setField(target, "routeRuntimeService", runtime);
        return target;
    }

    private PlayerNodeProgress progressProbe() {
        PlayerNodeProgress p = new PlayerNodeProgress(); p.setMemberId(MEMBER); p.setActivityId(ACTIVITY);
        p.setTopicId(TOPIC); p.setNodeId(NODE); return p;
    }

    private PlayerNodeProgress progress(String instance, int status) {
        PlayerNodeProgress p = progressProbe(); p.setPlayInstanceId(instance); p.setStatus(status); p.setDelFlag(0);
        assertEquals(1, mapper.insertPlayerNodeProgress(p)); return p;
    }

    private TopicRouteSession routeSession(long id, String instance, String snapshot) {
        TopicRouteSession s = new TopicRouteSession(); s.setId(id); s.setMemberId(MEMBER);
        s.setTopicId(TOPIC); s.setActivityId(ACTIVITY); s.setPlayInstanceId(instance);
        s.setConfigSnapshotJson(snapshot); return s;
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

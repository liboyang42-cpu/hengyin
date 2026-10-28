package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOrientSafetyService;
import com.chengyinhub.business.service.IOrientTaskService.SubmitCommand;
import com.chengyinhub.business.service.support.OrientRejection;
import com.chengyinhub.business.service.support.OrientSwitches;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.utils.DistanceCalculatorUtils;
import com.chengyinhub.common.utils.geo.GeoCoordinates;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Doc2 F1d:AR 替代任务走同一逻辑 task_id,同分、只完成一次、拍照待审不算完成。 */
class OrientTaskServiceImplTest {

    private static final long OWNER = 900L;
    private static final long PLAYER = 1L;
    private static final long TOPIC = 300L;
    private static final long TASK = 11L;
    // 上海外滩附近,节点坐标按 GCJ02 存
    private static final double W_LNG = 121.4900;
    private static final double W_LAT = 31.2400;

    private OrientH2Fixture db;
    private OrientTaskServiceImpl service;
    private OrientGeoServiceImpl geo;
    private Map<String, String> config;
    private PlayTeamMapper playTeamMapper;
    private IOrientSafetyService safety;
    private com.chengyinhub.business.mapper.TopicRouteSessionMapper routeMapper;
    private com.chengyinhub.business.service.IOrientScoringService scoring;
    private com.chengyinhub.business.domain.TopicRouteSession originalRun;
    private CmsTopic topic;
    private CmsTopicNode node;
    private final AtomicInteger photoChecks = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        db = new OrientH2Fixture("orient_task_" + System.nanoTime());
        config = new HashMap<>();
        config.put(OrientSwitches.KEY_ALT_TASK, "true");
        config.put(OrientSwitches.KEY_GEO, "true");
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey(anyString())).thenAnswer(inv -> config.get(inv.getArgument(0)));
        OrientSwitches switches = new OrientSwitches();
        ReflectionTestUtils.setField(switches, "configService", configService);

        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(OWNER);
        topic.setProductType(ProductType.ORIENTEERING);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        double[] gcj = GeoCoordinates.wgs84ToGcj02(W_LNG, W_LAT);
        node = new CmsTopicNode();
        node.setId(TASK);
        node.setTopicId(TOPIC);
        node.setName("外滩");
        node.setStatus(1L);
        node.setLongitude(Double.toString(gcj[0]));
        node.setLatitude(Double.toString(gcj[1]));
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        when(nodeService.selectCmsTopicNodeById(TASK)).thenReturn(node);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(List.of(node));

        playTeamMapper = mock(PlayTeamMapper.class);
        when(playTeamMapper.hasPaidRegistration(anyLong(), anyInt(), anyLong())).thenReturn(1);
        safety = mock(IOrientSafetyService.class);

        geo = new OrientGeoServiceImpl();
        ReflectionTestUtils.setField(geo, "orientMapper", db.mapper);
        ReflectionTestUtils.setField(geo, "topicService", topicService);
        ReflectionTestUtils.setField(geo, "topicNodeService", nodeService);
        ReflectionTestUtils.setField(geo, "distanceCalculator", new DistanceCalculatorUtils());
        ReflectionTestUtils.setField(geo, "switches", switches);

        service = new OrientTaskServiceImpl();
        ReflectionTestUtils.setField(service, "orientMapper", db.mapper);
        ReflectionTestUtils.setField(service, "playTeamMapper", playTeamMapper);
        ReflectionTestUtils.setField(service, "topicService", topicService);
        ReflectionTestUtils.setField(service, "topicNodeService", nodeService);
        ReflectionTestUtils.setField(service, "activityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(service, "geoService", geo);
        ReflectionTestUtils.setField(service, "safetyService", safety);
        ReflectionTestUtils.setField(service, "switches", switches);
        routeMapper = mock(com.chengyinhub.business.mapper.TopicRouteSessionMapper.class);
        scoring = mock(com.chengyinhub.business.service.IOrientScoringService.class);
        originalRun = new com.chengyinhub.business.domain.TopicRouteSession();
        originalRun.setId(71L); originalRun.setMemberId(PLAYER); originalRun.setTopicId(TOPIC);
        originalRun.setActivityId(0L); originalRun.setPlayInstanceId("run-71"); originalRun.setStatus(0);
        when(routeMapper.selectByOwner(0L, TOPIC, PLAYER, "run-71")).thenReturn(originalRun);
        when(routeMapper.selectAccessibleByIdForUpdate(71L, PLAYER)).thenReturn(originalRun);
        when(scoring.isActiveFor(topic)).thenReturn(true);
        ReflectionTestUtils.setField(service, "routeMapper", routeMapper);
        ReflectionTestUtils.setField(service, "scoringService", scoring);

        service.photoVerifier = (url, member) -> {
            photoChecks.incrementAndGet();
            if (!url.startsWith("https://oss/")) throw new IllegalArgumentException("照片无效");
        };

        service.configureAlternative(OWNER, TOPIC, TASK, "SCAN", null, "BUND-2026", true);
        service.configureAlternative(OWNER, TOPIC, TASK, "QUIZ", "外滩建筑群叫什么?", "万国 建筑博览群", true);
        service.configureAlternative(OWNER, TOPIC, TASK, "PHOTO", "拍下海关大楼钟楼", null, true);
    }

    @Test
    void scanCompletesTheLogicalTaskOnceAndOtherPathsBecomeAlreadyCompleted() throws Exception {
        Map<String, Object> scan = service.submit(cmd("SCAN", "k-scan-0001", c -> c.code = " bund-2026 "));
        assertEquals("COMPLETED", scan.get("outcome"));
        assertEquals(true, scan.get("completed"));
        assertEquals("SCAN", scan.get("completedVia"));
        assertEquals(TASK, scan.get("scoreKey"), "分数挂逻辑任务,与路径无关");

        Map<String, Object> quiz = service.submit(cmd("QUIZ", "k-quiz-0001", c -> c.answer = "万国建筑博览群"));
        assertEquals("ALREADY_COMPLETED", quiz.get("outcome"));
        assertEquals("SCAN", quiz.get("completedVia"));
        assertEquals(1L, db.count("select count(*) from orient_task_completion"));
    }

    @Test
    void wrongAnswerIsRecordedAndReplaysDeterministically() throws Exception {
        Map<String, Object> wrong = service.submit(cmd("QUIZ", "k-quiz-0002", c -> c.answer = "外白渡桥"));
        assertEquals("REJECTED", wrong.get("outcome"));
        assertEquals(OrientRejection.ALT_VERIFY_FAILED, wrong.get("errorCode"));
        assertEquals(false, wrong.get("completed"));

        Map<String, Object> replay = service.submit(cmd("QUIZ", "k-quiz-0002", c -> c.answer = "外白渡桥"));
        assertEquals(true, replay.get("replay"));
        assertEquals(wrong.get("attemptId"), replay.get("attemptId"));

        OrientRejection conflict = assertThrows(OrientRejection.class,
                () -> service.submit(cmd("QUIZ", "k-quiz-0002", c -> c.answer = "万国 建筑博览群")));
        assertEquals(OrientRejection.IDEMPOTENCY_CONFLICT, conflict.getErrorCode());

        Map<String, Object> right = service.submit(cmd("QUIZ", "k-quiz-0003", c -> c.answer = "万国　建筑博览群"));
        assertEquals("COMPLETED", right.get("outcome"), "全角空格经 NFKC 归一后与答案一致");
    }

    @Test
    void originalReceiptTracksReviewWithoutWritingOrExposingEvidence() throws Exception {
        Map<String, Object> photo = service.submit(cmdInRun("PHOTO", "k-photo-read", c -> c.picUrl = "https://oss/p1.jpg"));
        Map<String, Object> pending = service.receipt(PLAYER, TOPIC, 0L, "run-71", TASK, "k-photo-read");
        assertEquals("PENDING_REVIEW", pending.get("outcome"));
        assertEquals(false, pending.get("completed"));
        assertFalse(pending.containsKey("picUrl"));
        assertFalse(pending.containsKey("requestFingerprint"));
        assertEquals(1L, db.count("select count(*) from orient_task_attempt"));
        assertEquals(0L, db.count("select count(*) from orient_task_completion"));
        service.review(OWNER, (Long) photo.get("attemptId"), true);
        assertEquals("COMPLETED", service.receipt(PLAYER, TOPIC, 0L, "run-71", TASK, "k-photo-read").get("outcome"));
        assertEquals(1L, db.count("select count(*) from orient_task_attempt"));
    }

    @Test
    void originalReceiptRejectsAnotherOwnerInstanceAndMissingKey() throws Exception {
        service.submit(cmd("QUIZ", "k-read-owner", c -> c.answer = "wrong"));
        assertThrows(OrientRejection.class, () -> service.receipt(777L, TOPIC, 0L, null, TASK, "k-read-owner"));
        assertThrows(OrientRejection.class, () -> service.receipt(PLAYER, TOPIC, 0L, "another", TASK, "k-read-owner"));
        assertThrows(OrientRejection.class, () -> service.receipt(PLAYER, TOPIC, 0L, null, TASK, "k-not-found"));
        assertEquals("REJECTED", service.receipt(PLAYER, TOPIC, 0L, null, TASK, "k-read-owner").get("outcome"));
        assertEquals(1L, db.count("select count(*) from orient_task_attempt"));
    }

    @Test
    void photoUnderReviewDoesNotClearTheTaskUntilOwnerApproves() throws Exception {
        Map<String, Object> photo = service.submit(cmdInRun("PHOTO", "k-photo-0001", c -> c.picUrl = "https://oss/p1.jpg"));
        assertEquals("PENDING_REVIEW", photo.get("outcome"));
        assertEquals(false, photo.get("completed"));
        assertEquals(1, photoChecks.get(), "照片走上传事实校验");
        Map<String, Object> state = service.taskState(PLAYER, TOPIC, 0L, "run-71", TASK);
        assertEquals(false, state.get("completed"));
        assertEquals(true, state.get("photoPendingReview"));

        OrientRejection again = assertThrows(OrientRejection.class,
                () -> service.submit(cmdInRun("PHOTO", "k-photo-0002", c -> c.picUrl = "https://oss/p2.jpg")));
        assertEquals(OrientRejection.PHOTO_PENDING, again.getErrorCode());

        OrientRejection stranger = assertThrows(OrientRejection.class,
                () -> service.review(777L, (Long) photo.get("attemptId"), true));
        assertEquals(OrientRejection.NOT_OWNER, stranger.getErrorCode());

        Map<String, Object> approved = service.review(OWNER, (Long) photo.get("attemptId"), true);
        assertEquals("COMPLETED", approved.get("outcome"));
        assertEquals("PHOTO", approved.get("completedVia"));
        Map<String, Object> reApprove = service.review(OWNER, (Long) photo.get("attemptId"), true);
        assertEquals(true, reApprove.get("replay"), "重复审核通过是幂等回执");
        assertEquals(1L, db.count("select count(*) from orient_task_completion"));
    }

    @Test
    void approvingPhotoAfterAnotherPathWonIsSupersededNotDoubleCompleted() throws Exception {
        Map<String, Object> photo = service.submit(cmdInRun("PHOTO", "k-photo-0003", c -> c.picUrl = "https://oss/p3.jpg"));
        service.submit(cmdInRun("SCAN", "k-scan-0003", c -> c.code = "BUND-2026"));
        Map<String, Object> approved = service.review(OWNER, (Long) photo.get("attemptId"), true);
        assertEquals("SUPERSEDED", approved.get("outcome"));
        assertEquals("SCAN", approved.get("completedVia"));
        assertEquals(1L, db.count("select count(*) from orient_task_completion"));
    }

    @Test
    void rejectedPhotoLeavesTaskOpen() throws Exception {
        Map<String, Object> photo = service.submit(cmdInRun("PHOTO", "k-photo-0004", c -> c.picUrl = "https://oss/p4.jpg"));
        Map<String, Object> rejected = service.review(OWNER, (Long) photo.get("attemptId"), false);
        assertEquals("REJECTED", rejected.get("outcome"));
        assertEquals(false, rejected.get("completed"));
        OrientRejection flip = assertThrows(OrientRejection.class,
                () -> service.review(OWNER, (Long) photo.get("attemptId"), true));
        assertEquals(OrientRejection.ATTEMPT_NOT_PENDING, flip.getErrorCode());
        assertEquals(0L, db.count("select count(*) from orient_task_completion"));
    }

    @Test
    void ownerCannotApproveOwnPhoto() throws Exception {
        Map<String, Object> photo = service.submit(cmdAs(OWNER, "PHOTO", "k-photo-0005", c -> c.picUrl = "https://oss/p5.jpg"));
        OrientRejection self = assertThrows(OrientRejection.class,
                () -> service.review(OWNER, (Long) photo.get("attemptId"), true));
        assertEquals(OrientRejection.NOT_OWNER, self.getErrorCode());
    }

    @Test
    void primaryPathCompletionBlocksAlternativePaths() throws Exception {
        db.exec("insert into player_node_progress(member_id, activity_id, topic_id, node_id, status, checkin_time, del_flag)"
                + " values (1, 0, 300, 11, 1, timestamp '2026-10-22 10:00:00', 0)");
        Map<String, Object> scan = service.submit(cmd("SCAN", "k-scan-0006", c -> c.code = "BUND-2026"));
        assertEquals("ALREADY_COMPLETED", scan.get("outcome"));
        assertEquals("PRIMARY", scan.get("completedVia"));
        assertEquals(0L, db.count("select count(*) from orient_task_completion"));
    }

    @Test
    void guardsSwitchScopeAndConfiguration() {
        config.put(OrientSwitches.KEY_ALT_TASK, "false");
        assertEquals(OrientRejection.DISABLED, assertThrows(OrientRejection.class,
                () -> service.submit(cmd("SCAN", "k-scan-0007", c -> c.code = "x"))).getErrorCode());
        config.remove(OrientSwitches.KEY_ALT_TASK);
        assertEquals(OrientRejection.DISABLED, assertThrows(OrientRejection.class,
                () -> service.submit(cmd("SCAN", "k-scan-0007", c -> c.code = "x"))).getErrorCode(),
                "键缺失 = 关");
        config.put(OrientSwitches.KEY_ALT_TASK, "true");

        when(playTeamMapper.hasPaidRegistration(eq(PLAYER), anyInt(), anyLong())).thenReturn(0);
        assertEquals(OrientRejection.NOT_PARTICIPANT, assertThrows(OrientRejection.class,
                () -> service.submit(cmd("SCAN", "k-scan-0008", c -> c.code = "x"))).getErrorCode());
        when(playTeamMapper.hasPaidRegistration(eq(PLAYER), anyInt(), anyLong())).thenReturn(1);

        assertEquals(OrientRejection.NOT_OWNER, assertThrows(OrientRejection.class,
                () -> service.configureAlternative(PLAYER, TOPIC, TASK, "SCAN", null, "x", true)).getErrorCode());

        assertEquals(OrientRejection.BAD_REQUEST, assertThrows(OrientRejection.class,
                () -> service.submit(cmd("SCAN", "short", c -> c.code = "x"))).getErrorCode(), "幂等键必填");

        service.configureAlternative(OWNER, TOPIC, TASK, "SCAN", null, "BUND-2026", false);
        assertEquals(OrientRejection.ALT_NOT_CONFIGURED, assertThrows(OrientRejection.class,
                () -> service.submit(cmd("SCAN", "k-scan-0009", c -> c.code = "BUND-2026"))).getErrorCode());

        topic.setProductType(2);
        assertEquals(OrientRejection.NOT_ORIENTEERING, assertThrows(OrientRejection.class,
                () -> service.submit(cmd("QUIZ", "k-quiz-0010", c -> c.answer = "x"))).getErrorCode(),
                "老主题 / 非定向主题行为不变");
    }

    @Test
    void safetyBlockStopsSubmissionBeforeAnythingIsWritten() throws Exception {
        doThrow(new OrientRejection(OrientRejection.SAFETY_BLOCKED, "天气预警,本场暂停"))
                .when(safety).assertAllowed(eq(PLAYER), any(), anyLong());
        assertEquals(OrientRejection.SAFETY_BLOCKED, assertThrows(OrientRejection.class,
                () -> service.submit(cmd("SCAN", "k-scan-0011", c -> c.code = "BUND-2026"))).getErrorCode());
        assertEquals(0L, db.count("select count(*) from orient_task_attempt"));
    }

    @Test
    void reportedGcj02PositionIsConvertedOnceAndDistanceUsesNormalizedNode() throws Exception {
        Map<String, Object> before = service.submit(cmd("SCAN", "k-scan-0012", c -> {
            c.code = "nope";
            c.coordSys = "gcj02";
            double[] g = GeoCoordinates.wgs84ToGcj02(W_LNG, W_LAT);
            c.lng = g[0];
            c.lat = g[1];
        }));
        @SuppressWarnings("unchecked")
        Map<String, Object> g1 = (Map<String, Object>) before.get("geo");
        assertEquals("UNNORMALIZED", g1.get("nodeGeoStatus"));
        assertNull(g1.get("distanceM"), "节点坐标未归一时不算距离,也不当 0");

        Map<String, Object> report = geo.normalizeTopic(OWNER, TOPIC, "GCJ02", true);
        assertEquals(1, report.get("written"));
        assertEquals(1, geo.normalizeTopic(OWNER, TOPIC, "GCJ02", true).get("unchanged"), "指纹未变不重写");

        Map<String, Object> after = service.submit(cmd("SCAN", "k-scan-0013", c -> {
            c.code = "nope";
            c.coordSys = "GCJ02";
            double[] g = GeoCoordinates.wgs84ToGcj02(W_LNG, W_LAT);
            c.lng = g[0];
            c.lat = g[1];
        }));
        @SuppressWarnings("unchecked")
        Map<String, Object> g2 = (Map<String, Object>) after.get("geo");
        assertEquals("CLEAN", g2.get("nodeGeoStatus"));
        assertEquals(0, g2.get("distanceM"), "同一点两侧各转一次 WGS84,距离为 0;若任一侧多转/漏转会差出几百米");

        OrientRejection noSys = assertThrows(OrientRejection.class,
                () -> service.submit(cmd("SCAN", "k-scan-0014", c -> { c.code = "x"; c.lng = 121.0; c.lat = 31.0; })));
        assertEquals(OrientRejection.COORD_SYS_INVALID, noSys.getErrorCode());
    }

    @Test
    void dirtyNodeCoordinatesAreFlaggedNotZeroed() throws Exception {
        node.setLongitude("0");
        node.setLatitude("0");
        Map<String, Object> report = geo.normalizeTopic(OWNER, TOPIC, "GCJ02", true);
        assertEquals(1, report.get("dirtyCount"));
        assertEquals(1L, db.count("select count(*) from orient_node_geo where geo_status = 1 and dirty_reason = 'ZERO'"
                + " and wgs84_lng is null and wgs84_lat is null"));
        Map<String, Object> r = service.submit(cmd("SCAN", "k-scan-0015", c -> {
            c.code = "nope"; c.coordSys = "WGS84"; c.lng = 121.49; c.lat = 31.24;
        }));
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) r.get("geo");
        assertEquals("ZERO", g.get("nodeGeoStatus"));
        assertNull(g.get("distanceM"));
        assertEquals(1L, db.count("select count(*) from orient_task_attempt where node_geo_dirty = 1"));
    }

    @Test
    void invalidPhotoIsRejectedWithoutPersisting() throws Exception {
        OrientRejection bad = assertThrows(OrientRejection.class,
                () -> service.submit(cmd("PHOTO", "k-photo-0016", c -> c.picUrl = "https://evil/p.jpg")));
        assertEquals(OrientRejection.PHOTO_INVALID, bad.getErrorCode());
        assertFalse(db.count("select count(*) from orient_task_attempt") > 0);
        assertTrue(photoChecks.get() > 0);
    }


    @Test
    void reviewLocksOriginalSessionBeforeAttemptAndReplayDoesNotRescore() throws Exception {
        Map<String, Object> photo = service.submit(cmdInRun("PHOTO", "review-order-1", c -> c.picUrl = "https://oss/p.jpg"));
        com.chengyinhub.business.mapper.OrientMapper mapper = org.mockito.Mockito.spy(db.mapper);
        ReflectionTestUtils.setField(service, "orientMapper", mapper);
        Long attemptId = (Long) photo.get("attemptId");
        service.review(OWNER, attemptId, true);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(mapper, routeMapper, scoring);
        order.verify(mapper).selectAttemptById(attemptId);
        order.verify(routeMapper).selectByOwner(0L, TOPIC, PLAYER, "run-71");
        order.verify(routeMapper).selectAccessibleByIdForUpdate(71L, PLAYER);
        order.verify(mapper).selectAttemptByIdForUpdate(attemptId);
        order.verify(scoring).syncSession(PLAYER, 71L, "lifecycle:alternative:" + attemptId);
        originalRun.setStatus(1);
        assertEquals(true, service.review(OWNER, attemptId, true).get("replay"));
        org.mockito.Mockito.verify(scoring, org.mockito.Mockito.times(1)).syncSession(PLAYER, 71L, "lifecycle:alternative:" + attemptId);
    }

    @Test
    void legacyMissingBindingAndEndedRunCannotCreateCompletion() throws Exception {
        Map<String, Object> old = service.submit(cmd("PHOTO", "review-legacy-1", c -> c.picUrl = "https://oss/p.jpg"));
        assertThrows(OrientRejection.class, () -> service.review(OWNER, (Long) old.get("attemptId"), true));
        org.mockito.Mockito.verifyNoInteractions(routeMapper, scoring);
        Map<String, Object> bound = service.submit(cmdInRun("PHOTO", "review-ended-1", c -> c.picUrl = "https://oss/p.jpg"));
        originalRun.setStatus(1);
        assertThrows(OrientRejection.class, () -> service.review(OWNER, (Long) bound.get("attemptId"), true));
        assertEquals(0L, db.count("select count(*) from orient_task_completion"));
    }

    @Test
    void lockedIdentityChangeRejectsBeforeReviewCas() throws Exception {
        Map<String, Object> photo = service.submit(cmdInRun("PHOTO", "review-identity-1", c -> c.picUrl = "https://oss/p.jpg"));
        Long attemptId = (Long) photo.get("attemptId");
        com.chengyinhub.business.mapper.OrientMapper mapper = org.mockito.Mockito.spy(db.mapper);
        com.chengyinhub.business.domain.OrientTaskAttempt changed = db.mapper.selectAttemptById(attemptId);
        changed.setRequestFingerprint("different");
        org.mockito.Mockito.doReturn(changed).when(mapper).selectAttemptByIdForUpdate(attemptId);
        ReflectionTestUtils.setField(service, "orientMapper", mapper);
        assertEquals(OrientRejection.IDEMPOTENCY_CONFLICT,
                assertThrows(OrientRejection.class, () -> service.review(OWNER, attemptId, true)).getErrorCode());
        assertEquals(0L, db.count("select count(*) from orient_task_completion"));
        org.mockito.Mockito.verifyNoInteractions(scoring);
    }

    private static SubmitCommand cmdInRun(String type, String key, Tweak tweak) {
        SubmitCommand command = cmd(type, key, tweak); command.playInstanceId = "run-71"; return command;
    }

    private interface Tweak {
        void apply(SubmitCommand c);
    }

    private static SubmitCommand cmd(String type, String key, Tweak tweak) {
        return cmdAs(PLAYER, type, key, tweak);
    }

    private static SubmitCommand cmdAs(long member, String type, String key, Tweak tweak) {
        SubmitCommand c = new SubmitCommand();
        c.memberId = member;
        c.topicId = TOPIC;
        c.activityId = 0L;
        c.taskId = TASK;
        c.alternativeTaskType = type;
        c.idempotencyKey = key;
        tweak.apply(c);
        return c;
    }
}

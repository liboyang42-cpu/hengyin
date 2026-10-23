package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ai.LlmClient;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.PhotoCheckIntentService;
import com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient;
import com.chengyinhub.business.service.ai.PhotoCheckVisionService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.chengyinhub.common.config.RuoYiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 预制人生 photoCheck 拍照审核的运行期契约(2026-09-17 改版:服务端真视觉判定)。
 *
 * <p>硬负控(三条都要真打回/真降级,不能静默放过):
 * ① 模型说不过 → passed=false 且 tries+1;
 * ② key 未注入 → degraded=true,绝不能变成通过;
 * ③ 模型回非 JSON → 同②。
 * 另加:payload 里的 score 字段对判定毫无影响(收到也不读)。
 *
 * <p>测试用真 PhotoCheckVisionService + 假 LlmClient 走完整条判定链,
 * 只有 HTTP 出口被替换 —— 降级分支是生产代码跑的,不是 mock 演的。
 */
class PlayKitPhotoCheckRuntimeTest {

    private static final long MEMBER_ID = 7L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long TEMPLATE_ID = 39L;
    private static final long SESSION_ID = 51L;

    private GameRuntimeSessionMapper sessionMapper;
    private PlayAttemptLogMapper attemptMapper;
    private GameRuntimeSession session;
    private AdvancedGameRuntimeServiceImpl service;
    private PhotoCheckVisionService visionService;
    private AiGatewayService quota;
    private PhotoCheckIntentService photoIntents;
    private Path profileDir;
    private String originalProfile;

    private static final String CHECK_CONFIG = "{\"schemaVersion\":1,\"photoCheck\":{\"enabled\":true,"
            + "\"title\":\"拍一张窗外\",\"shotNote\":\"把窗框也拍进去\","
            + "\"requirement\":\"画面里要能看见窗外的街景,窗框入镜\",\"minConfidence\":60,"
            + "\"maxTries\":3,\"fallback\":\"retake\",\"xp\":5}}";

    @BeforeEach
    void setUp() throws Exception {
        // 图片用真本机文件:photoCheck 现在把站内路径读成本地文件再转 base64,单测不联网
        profileDir = Files.createTempDirectory("playkit-photo-check");
        originalProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(profileDir.toString());
        File uploadDir = profileDir.resolve("upload").toFile();
        assertTrue(uploadDir.mkdirs());
        Files.createDirectories(profileDir.resolve("upload/2026/10/12"));
        Files.write(profileDir.resolve("upload/2026/10/12/0123456789abcdef0123456789abcdef.jpg"), jpeg(2, 2));

        sessionMapper = mock(GameRuntimeSessionMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        PlayTeamMapper teamMapper = mock(PlayTeamMapper.class);

        service = new AdvancedGameRuntimeServiceImpl();
        AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(TOPIC_ID);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        ReflectionTestUtils.setField(service, "registrationMapper", mock(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", attemptMapper);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) Date::new);
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);

        visionService = spy(new PhotoCheckVisionService());
        quota = mock(AiGatewayService.class);
        when(quota.consumeQuota(MEMBER_ID, "photo_check")).thenReturn("reservation");
        ReflectionTestUtils.setField(visionService, "aiGatewayService", quota);
        org.springframework.test.util.ReflectionTestUtils.setField(visionService, "consentGuard", org.mockito.Mockito.mock(com.chengyinhub.business.service.ConsentGuard.class));
        com.chengyinhub.business.mapper.AppMediaAssetMapper reviewedImages = mock(com.chengyinhub.business.mapper.AppMediaAssetMapper.class);
        when(reviewedImages.selectByObjectKey("/profile/upload/2026/10/12/0123456789abcdef0123456789abcdef.jpg")).thenReturn(Map.of(
                "id", 101L, "memberId", MEMBER_ID, "ownerType", "APP", "reviewState", "APPROVED",
                "mediaType", "IMAGE", "storageType", "LOCAL", "objectKey", "/profile/upload/2026/10/12/0123456789abcdef0123456789abcdef.jpg"));
        com.chengyinhub.business.service.support.AppMediaReferenceGuard reviewedMedia = new com.chengyinhub.business.service.support.AppMediaReferenceGuard();
        ReflectionTestUtils.setField(reviewedMedia, "mapper", reviewedImages);
        ReflectionTestUtils.setField(visionService, "mediaReferences", reviewedMedia);
        photoIntents = AdvancedActionTestFixtures.photos(service, command -> visionService.review(
                command.memberId(), command.imageReference(), command.requirement()));

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setTemplateId(TEMPLATE_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
    }

    @AfterEach
    void tearDown() {
        new RuoYiConfig().setProfile(originalProfile);
        deleteRecursively(profileDir.toFile());
    }

    // ==================== 负控①:模型判不过必须真被打回 ====================

    @Test
    void 负控_模型判不过必须真被打回且计次() {
        session = running(CHECK_CONFIG);
        arm();
        modelSays("{\"pass\":false,\"confidence\":92,\"reason\":\"画面里只有一堵墙,没有窗外街景\"}");

        Map<String, Object> view = act("photo-low-1");
        Map<?, ?> kit = kit(view);
        assertEquals(false, kit.get("passed"), "模型说不过就不能被放过:" + view);
        assertEquals(1, kit.get("tries"), "打回必须计次");
        assertEquals(0, view.get("score"), "没过不给分");
        assertEquals(false, kit.get("degraded"), "这是拿到了真结论,不是降级");
        assertEquals(92, kit.get("lastConfidence"));
        assertTrue(String.valueOf(kit.get("lastReason")).contains("没有窗外街景"),
                "不过要告诉玩家为什么:" + kit);

        modelSays("{\"pass\":false,\"confidence\":80,\"reason\":\"还是没拍到\"}");
        Map<String, Object> second = act("photo-low-2");
        assertEquals(false, kit(second).get("passed"));
        assertEquals(2, kit(second).get("tries"), "再不过继续计次");
    }

    @Test
    void 负控_模型说pass但置信度低于阈值不算过() {
        session = running(CHECK_CONFIG);
        arm();
        modelSays("{\"pass\":true,\"confidence\":30,\"reason\":\"有点看不清\"}");

        Map<String, Object> view = act("photo-conf-1");
        Map<?, ?> kit = kit(view);
        assertEquals(false, kit.get("passed"), "置信度不足不能放行:" + view);
        assertEquals(1, kit.get("tries"));
        assertEquals(0, view.get("score"));
        assertEquals(false, kit.get("degraded"));
    }

    // ==================== 负控②:key 未注入 ====================

    @Test
    void 负控_key未注入必须走降级且绝不能变成通过() {
        session = running(CHECK_CONFIG);
        arm();
        modelHasNoKey();

        Map<String, Object> view = act("photo-nokey-1");
        Map<?, ?> kit = kit(view);
        assertEquals(false, kit.get("passed"), "拿不到结论不能当通过:" + view);
        assertEquals(true, kit.get("degraded"), "key 未注入必须标降级");
        assertEquals(true, kit.get("flagged"), "降级必须留痕");
        assertEquals(1, kit.get("tries"));
        assertEquals(0, view.get("score"), "降级不给分");
        assertFalse(String.valueOf(kit.get("lastReason")).isEmpty(), "降级也要给玩家一个理由:" + kit);

        Map<String, Object> second = act("photo-nokey-2");
        assertEquals(false, kit(second).get("passed"), "降级后重拍仍不能被假装放过");
        assertEquals(2, kit(second).get("tries"));
    }

    // ==================== 负控③:模型回非 JSON ====================

    @Test
    void 负控_模型回非JSON必须同样降级_不能变成通过() {
        session = running(CHECK_CONFIG);
        arm();
        modelSays("抱歉,这张照片我无法判断。");

        Map<String, Object> view = act("photo-badjson-1");
        Map<?, ?> kit = kit(view);
        assertEquals(false, kit.get("passed"), "解析不了不能当通过:" + view);
        assertEquals(true, kit.get("degraded"));
        assertEquals(true, kit.get("flagged"));
        assertEquals(1, kit.get("tries"));
        assertEquals(0, view.get("score"));
    }

    @Test
    void 负控_模型JSON缺pass字段也算拿不到结论() {
        session = running(CHECK_CONFIG);
        arm();
        modelSays("{\"confidence\":88,\"reason\":\"看起来还行\"}");

        Map<String, Object> view = act("photo-nofield-1");
        assertEquals(false, kit(view).get("passed"), "字段不全不能猜成通过:" + view);
        assertEquals(true, kit(view).get("degraded"));
    }

    @Test
    void 降级且兜底为pass时才放行_且不给分() {
        String config = CHECK_CONFIG.replace("\"fallback\":\"retake\"", "\"fallback\":\"pass\"");
        session = running(config);
        arm();
        modelHasNoKey();

        Map<String, Object> view = act("photo-deg-pass-1");
        Map<?, ?> kit = kit(view);
        assertEquals(true, kit.get("passed"), "兜底 pass 至少让节点可完成:" + view);
        assertEquals(true, kit.get("degraded"), "放行也改变不了没拿到结论的事实");
        assertEquals(true, kit.get("flagged"), "被放过的痕迹商家要看得到");
        assertEquals(0, view.get("score"), "降级放行不等于达标,不给分");
    }

    // ==================== score 后门 ====================

    @Test
    void 负控_客户端score字段对判定毫无影响() {
        session = running(CHECK_CONFIG);
        arm();
        // 模型明说不过:客户端报满分 100 也照样打回
        modelSays("{\"pass\":false,\"confidence\":99,\"reason\":\"没拍到窗外\"}");
        Map<String, Object> rejected = actWithScore("photo-score-1", "100");
        assertEquals(false, kit(rejected).get("passed"), "score 不能覆盖模型结论:" + rejected);
        assertEquals(0, rejected.get("score"));

        // 模型说通过:客户端报 0 也照样通过
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");
        Map<String, Object> accepted = actWithScore("photo-score-2", "0");
        assertEquals(true, kit(accepted).get("passed"), "score 不能拖累模型结论:" + accepted);

        // score 不能替代照片:只带 score 不带 imageUrl 必须拒
        session = running(CHECK_CONFIG);
        arm();
        Map<String, Object> onlyScore = new HashMap<>();
        onlyScore.put("score", "100");
        when(attemptMapper.selectByIdempotencyKey("photo-score-3")).thenReturn(null);
        clearInvocations(quota, visionService, photoIntents, sessionMapper, attemptMapper);
        AdvancedActionTestFixtures.assertPhotoNotExecuted(
                () -> service.act(MEMBER_ID, SESSION_ID, session.getVersion(), "photo-score-3",
                        "SUBMIT_PHOTO_CHECK", onlyScore), "照片地址不能为空且最多 512 字");
        assertEquals("{}", session.getStateJson());
        assertEquals(0, session.getVersion());
        assertEquals(0, session.getScore());
        assertNoNewPhotoEffects();
    }

    // ==================== 通过 / 兜底 / 降级后恢复 ====================

    @Test
    void 模型判过且置信度达标才通过并给分() {
        session = running(CHECK_CONFIG);
        arm();
        modelSays("{\"pass\":true,\"confidence\":80,\"reason\":\"能看到窗外街景和窗框\"}");

        Map<String, Object> view = act("photo-hit-1");
        Map<?, ?> kit = kit(view);
        assertEquals(true, kit.get("passed"));
        assertEquals(5, view.get("score"), "达标给分");
        assertEquals(80, kit.get("lastConfidence"));
        assertEquals(false, kit.get("degraded"));
        assertTrue(String.valueOf(kit.get("lastReason")).contains("窗框"));

        verify(quota).consumeQuota(MEMBER_ID, "photo_check");
        verify(visionService).review(anyLong(), anyString(), anyString());
        String committedState = session.getStateJson();
        Integer committedVersion = session.getVersion();
        clearInvocations(quota, visionService, photoIntents, sessionMapper, attemptMapper);
        AdvancedActionTestFixtures.assertPhotoNotExecuted(() -> act("photo-hit-2"), "这一张已经过了");
        assertEquals(committedState, session.getStateJson());
        assertEquals(committedVersion, session.getVersion());
        assertEquals(5, session.getScore());
        assertNoNewPhotoEffects();
    }

    /** 拍物成卡(立体藏品卡契约 §2.4):mode / cardTitle / cardStyle 必须投影进 kit。
     *  漏一个键不报错、不打日志 —— 组件收到空值,静默退回普通拍照审核,玩家等不到那张卡。 */
    @Test
    void 卡片三字段投影进kit() {
        String card = CHECK_CONFIG.replace("\"fallback\":\"retake\"",
                "\"fallback\":\"retake\",\"mode\":\"CARD\",\"cardTitle\":\"一枚弹壳\",\"cardStyle\":\"plain\"");
        session = running(card);
        arm();
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了一件金属的东西\"}");

        Map<?, ?> kit = kit(act("photo-card-1"));
        assertEquals("CARD", kit.get("mode"));
        assertEquals("一枚弹壳", kit.get("cardTitle"));
        assertEquals("plain", kit.get("cardStyle"));
        assertEquals(true, kit.get("passed"), "投影卡片字段不改判定:该过还是过");
    }

    /** 负控:老模板(配置里没有 mode)的回包必须与今天逐字相同 —— 一个键都不许多。 */
    @Test
    void 负控_老模板不投影卡片键回包不变() {
        session = running(CHECK_CONFIG);
        arm();
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");

        Map<?, ?> kit = kit(act("photo-legacy-1"));
        assertFalse(kit.containsKey("mode"), "空 mode 也下发 = 客户端要替老模板猜玩法:" + kit);
        assertFalse(kit.containsKey("cardTitle"), "同上:" + kit);
        assertFalse(kit.containsKey("cardStyle"), "同上:" + kit);
    }

    @Test
    void 不过时兜底为retake则次数用尽也不放过() {
        session = running(CHECK_CONFIG);
        arm();
        modelSays("{\"pass\":false,\"confidence\":90,\"reason\":\"不满足\"}");

        act("photo-r-1");
        act("photo-r-2");
        Map<String, Object> third = act("photo-r-3");

        Map<?, ?> kit = kit(third);
        assertEquals(3, kit.get("tries"));
        assertEquals(false, kit.get("passed"), "fallback=retake 时次数用尽也不能放行");
    }

    @Test
    void 兜底为pass时次数用尽才放过并打标() {
        String config = CHECK_CONFIG.replace("\"fallback\":\"retake\"", "\"fallback\":\"pass\"");
        session = running(config);
        arm();
        modelSays("{\"pass\":false,\"confidence\":85,\"reason\":\"不满足\"}");

        act("photo-p-1");
        act("photo-p-2");
        Map<String, Object> third = act("photo-p-3");

        Map<?, ?> kit = kit(third);
        assertEquals(true, kit.get("passed"));
        assertEquals(true, kit.get("flagged"), "被放过的必须打标,商家事后看得见");
        assertEquals(false, kit.get("degraded"), "这次拿到了结论,只是不达标");
        assertEquals(0, third.get("score"), "被放过不等于达标,不给分");
    }

    @Test
    void 降级后再拿到真结论_degraded清掉而flagged留痕() {
        session = running(CHECK_CONFIG);
        arm();
        modelHasNoKey();
        assertEquals(true, kit(act("photo-mix-1")).get("degraded"));

        modelSays("{\"pass\":true,\"confidence\":90,\"reason\":\"拍到了\"}");
        Map<String, Object> second = act("photo-mix-2");
        assertEquals(true, kit(second).get("passed"));
        assertEquals(false, kit(second).get("degraded"), "这一次拿到了真结论");
        assertEquals(true, kit(second).get("flagged"), "曾经的降级痕迹仍要给商家看");
        assertEquals(5, second.get("score"));
    }

    // ==================== 取景轮廓(2026-09-22 现场感三件套 S1) ====================

    @Test
    void 取景轮廓开局就下发_不必等passed() {
        // 玩家得先看见框才对得上 —— 这两个字段是题面，与 requirement 同档，不能等过了才给
        session = running(withFrame("\"frameUrl\":\"/profile/upload/outline.png\",\"frameOpacity\":60"));
        arm();
        modelSays("{\"pass\":false,\"confidence\":50,\"reason\":\"没对上\"}");

        Map<?, ?> kit = kit(act("photo-frame-1"));
        assertEquals("/profile/upload/outline.png", kit.get("frameUrl"), "没过也要有框:" + kit);
        assertEquals(60, kit.get("frameOpacity"));
    }

    @Test
    void 未配取景轮廓时下发形状与今天逐字相同() {
        // 商家没配 → 客户端拿到的 photoCheck 一个键都不多，走原来的 chooseMedia 那条路
        session = running(CHECK_CONFIG);
        arm();
        modelSays("{\"pass\":false,\"confidence\":50,\"reason\":\"没拍到\"}");

        Map<?, ?> kit = kit(act("photo-noframe-1"));
        assertFalse(kit.containsKey("frameUrl"), "没配就不该出现这个键:" + kit);
        assertFalse(kit.containsKey("frameOpacity"), "同上:" + kit);
    }

    @Test
    void 存量快照里的越界不透明度下发前夹回0至100() {
        // 会话存的是配置快照，可能出自加校验之前的老主题，运行期再兜一层
        session = running(withFrame("\"frameUrl\":\"/profile/upload/outline.png\",\"frameOpacity\":999"));
        arm();
        modelSays("{\"pass\":false,\"confidence\":50,\"reason\":\"没对上\"}");
        assertEquals(100, kit(act("photo-frame-2")).get("frameOpacity"));

        session = running(withFrame("\"frameUrl\":\"/profile/upload/outline.png\",\"frameOpacity\":-20"));
        arm();
        modelSays("{\"pass\":false,\"confidence\":50,\"reason\":\"没对上\"}");
        assertEquals(0, kit(act("photo-frame-3")).get("frameOpacity"));
    }

    @Test
    void 只配轮廓不配不透明度时按缺省四成下发() {
        session = running(withFrame("\"frameUrl\":\"/profile/upload/outline.png\""));
        arm();
        modelSays("{\"pass\":false,\"confidence\":50,\"reason\":\"没对上\"}");
        assertEquals(40, kit(act("photo-frame-4")).get("frameOpacity"));
    }

    // ==================== 脚手架 ====================

    /** 往 CHECK_CONFIG 的 photoCheck 段里追加取景轮廓字段，其余真值不动。 */
    private static String withFrame(String frameFields) {
        return CHECK_CONFIG.replace("\"minConfidence\":60", frameFields + ",\"minConfidence\":60");
    }

    private void assertNoNewPhotoEffects() {
        verifyNoInteractions(quota);
        verify(visionService, never()).review(anyLong(), anyString(), anyString());
        verify(photoIntents, never()).prepare(any());
        verify(photoIntents, never()).executeOnce(anyLong(), anyLong(), anyString());
        verify(photoIntents, never()).markApplied(anyLong(), anyString(), anyLong());
        verify(sessionMapper, never()).updateStateCas(any());
        verify(attemptMapper, never()).insertPlayAttemptLog(any());
    }

    private void arm() {
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);
    }

    /** 假视觉模型:chatVision 直接回固定文本,不碰 HTTP。 */
    private void modelSays(String answer) {
        ReflectionTestUtils.setField(visionService, "visionClient", new LlmClient() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public String chat(String systemPrompt, String userPrompt) {
                throw new UnsupportedOperationException("photoCheck 只能走视觉通道");
            }

            @Override
            public String chatVision(String systemPrompt, String imageUrl, String userPrompt) {
                return answer;
            }
        });
    }

    /** 真 OpenAiCompatibleLlmClient + 空 key:调用在发 HTTP 之前就抛「未配置 apiKey」。 */
    private void modelHasNoKey() {
        ReflectionTestUtils.setField(visionService, "visionClient",
                new OpenAiCompatibleLlmClient("vision", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "", "qwen-vl-max", true, 3000));
    }

    private Map<String, Object> act(String key) {
        return submit(key, new HashMap<>());
    }

    private Map<String, Object> actWithScore(String key, String score) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("score", score);
        return submit(key, payload);
    }

    private Map<String, Object> submit(String key, Map<String, Object> payload) {
        when(attemptMapper.selectByIdempotencyKey(key)).thenReturn(null);
        payload.put("imageUrl", "/profile/upload/2026/10/12/0123456789abcdef0123456789abcdef.jpg");
        return service.act(MEMBER_ID, SESSION_ID, session.getVersion(), key, "SUBMIT_PHOTO_CHECK", payload);
    }

    private Map<?, ?> kit(Map<String, Object> view) {
        return (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("photoCheck");
    }

    private GameRuntimeSession running(String config) {
        GameRuntimeSession fresh = new GameRuntimeSession();
        fresh.setId(SESSION_ID);
        fresh.setActivityId(ACTIVITY_ID);
        fresh.setTopicId(TOPIC_ID);
        fresh.setNodeId(NODE_ID);
        fresh.setTemplateId(TEMPLATE_ID);
        fresh.setOwnerType(GameRuntimeSession.OWNER_PLAYER);
        fresh.setOwnerId(MEMBER_ID);
        fresh.setConfigSnapshotJson(config);
        fresh.setStateJson("{}");
        fresh.setStatus(GameRuntimeSession.STATUS_RUNNING);
        fresh.setVersion(0);
        fresh.setScore(0);
        fresh.setStartedAt(new Date());
        return fresh;
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg", out);
        return out.toByteArray();
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}

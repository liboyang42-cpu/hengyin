package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayerObjectCard;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.vo.PlayerObjectCardVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ObjectCardService;
import com.chengyinhub.business.service.ai.LlmClient;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.PhotoCheckIntentService;
import com.chengyinhub.business.service.ai.PhotoCheckIntentCommand;
import com.chengyinhub.business.service.ai.OpenAiCompatibleLlmClient;
import com.chengyinhub.business.service.ai.PhotoCheckVisionService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.chengyinhub.common.config.RuoYiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拍物成卡 · 铸卡挂载点(立体藏品卡契约 §4.2:铸卡挂在判过之后)。
 *
 * <p>这条链上的两个方向都要钉住:
 * ① 该铸的时候一定铸(真判过 + mode=CARD),参数带得上玩家、照片、卡面与来源节点;
 * ② 不该铸的时候一次都不铸(老模板 / 判不过 / 降级放行 / 次数用尽兜底放过) ——
 *    后三条是同一句话的另一半:「玩家没拍到达标」不能白得一张卡。
 *
 * <p>还有承重的那条:铸卡整段抛异常时,通关判定必须原样成立(契约 §4.2)。
 * 铸卡被推迟到事务提交之后跑,所以这里的用例都在「无事务同步」分支上直调 ——
 * 那正是回滚时不会留下卡、提交后才铸的同一道闸。
 */
class ObjectCardMintHookTest {

    private static final long MEMBER_ID = 7L;
    private static final long ACTIVITY_ID = 9L;
    private static final long TOPIC_ID = 19L;
    private static final long NODE_ID = 29L;
    private static final long TEMPLATE_ID = 39L;
    private static final long SESSION_ID = 51L;
    private static final String PHOTO_URL = "/profile/upload/2026/10/12/0123456789abcdef0123456789abcdef.jpg";
    /** 抠图转存后自家 OSS 上的地址:T5 铸卡时并行跑出来,只能随这次回包一起下去。 */
    private static final String CUTOUT_URL = "https://cdn.chengyinhub.com/profile/upload/cutout.png";
    private static final String CATEGORY = "玩具摆件";

    private static final String CHECK_CONFIG = "{\"schemaVersion\":1,\"photoCheck\":{\"enabled\":true,"
            + "\"title\":\"拍一张窗外\",\"shotNote\":\"把窗框也拍进去\","
            + "\"requirement\":\"画面里要能看见窗外的街景,窗框入镜\",\"minConfidence\":60,"
            + "\"maxTries\":3,\"fallback\":\"retake\",\"xp\":5}}";

    private static final String CARD_CONFIG = CHECK_CONFIG.replace("\"fallback\":\"retake\"",
            "\"fallback\":\"retake\",\"mode\":\"CARD\",\"cardTitle\":\"一枚弹壳\",\"cardStyle\":\"plain\"");

    /** 同一份卡片配置再多开一个无关玩法:用它验证「铸卡只挂在拍照审核上」。 */
    private static final String COIN_AND_CARD_CONFIG = CARD_CONFIG.replace("{\"schemaVersion\":1,",
            "{\"schemaVersion\":1,\"coinFlip\":{\"enabled\":true},");

    private GameRuntimeSessionMapper sessionMapper;
    private PlayAttemptLogMapper attemptMapper;
    private PhotoCheckVisionService visionService;
    private AiGatewayService quota;
    private PhotoCheckIntentService photoIntents;
    private ObjectCardService objectCardService;
    private GameRuntimeSession session;
    private AdvancedGameRuntimeServiceImpl service;
    private Path profileDir;
    private String originalProfile;

    @BeforeEach
    void setUp() throws Exception {
        profileDir = Files.createTempDirectory("object-card-mint-hook");
        originalProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(profileDir.toString());
        File uploadDir = profileDir.resolve("upload").toFile();
        assertTrue(uploadDir.mkdirs());
        Files.createDirectories(profileDir.resolve("upload/2026/10/12"));
        Files.write(profileDir.resolve("upload/2026/10/12/0123456789abcdef0123456789abcdef.jpg"), jpeg());

        sessionMapper = mock(GameRuntimeSessionMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);

        service = new AdvancedGameRuntimeServiceImpl();
        AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", mock(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "teamMapper", mock(PlayTeamMapper.class));
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
        objectCardService = mock(ObjectCardService.class);
        ReflectionTestUtils.setField(service, "objectCardService", objectCardService);
        com.chengyinhub.business.service.support.AppMediaReferenceGuard media = mock(com.chengyinhub.business.service.support.AppMediaReferenceGuard.class);
        when(media.visibleUrl(PHOTO_URL, MEMBER_ID)).thenReturn(PHOTO_URL);
        when(media.visibleUrl(CUTOUT_URL, MEMBER_ID)).thenReturn(CUTOUT_URL);
        ReflectionTestUtils.setField(service, "mediaReferences", media);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setTemplateId(TEMPLATE_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(TOPIC_ID);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
    }

    @AfterEach
    void tearDown() {
        new RuoYiConfig().setProfile(originalProfile);
        deleteRecursively(profileDir.toFile());
    }

    // ==================== 该铸的时候 ====================

    @Test
    void 判过且模板是CARD时铸一张卡_参数带得上玩家照片卡面与来源节点() {
        given(CARD_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了一件金属的东西\"}");

        Map<String, Object> view = act("card-hit-1");

        assertEquals(true, kit(view).get("passed"));
        ArgumentCaptor<JsonNode> cfg = ArgumentCaptor.forClass(JsonNode.class);
        verify(objectCardService).mint(eq(MEMBER_ID), eq(PHOTO_URL), cfg.capture(),
                eq(ObjectCardService.ORIGIN_PLAY), eq(String.valueOf(NODE_ID)));
        assertEquals("一枚弹壳", cfg.getValue().path("cardTitle").asText(""));
        assertEquals("plain", cfg.getValue().path("cardStyle").asText(""));
    }

    /** 负控:同一个人同一节点只该有一张卡 —— 过了再交不铸第二张(uk_member_card_origin 的语义)。 */
    @Test
    void 负控_过了再交不再铸第二张卡() {
        given(CARD_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");
        act("card-twice-1");

        verify(quota).consumeQuota(MEMBER_ID, "photo_check");
        verify(visionService).review(anyLong(), anyString(), anyString());
        String committedState = session.getStateJson();
        Integer committedVersion = session.getVersion();
        clearInvocations(quota, visionService, photoIntents, sessionMapper, attemptMapper);
        AdvancedActionTestFixtures.assertPhotoNotExecuted(() -> act("card-twice-2"), "这一张已经过了");
        assertEquals(committedState, session.getStateJson());
        assertEquals(committedVersion, session.getVersion());
        assertEquals(5, session.getScore());
        assertNoNewPhotoEffects();
        verify(objectCardService, times(1)).mint(anyLong(), anyString(), any(JsonNode.class), anyString(),
                anyString());
    }

    // ==================== 不该铸的时候 ====================

    /** 负控:老模板(没有 mode)一行都不许多铸 —— 普通拍照审核判过只是判过。 */
    @Test
    void 负控_老模板判过不铸卡() {
        given(CHECK_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");

        Map<String, Object> view = act("legacy-hit-1");
        assertEquals(true, kit(view).get("passed"), "判定本身不受铸卡影响");
        verify(objectCardService, never()).mint(anyLong(), anyString(), any(JsonNode.class), anyString(),
                anyString());
    }

    @Test
    void 负控_判不过不铸卡() {
        given(CARD_CONFIG);
        modelSays("{\"pass\":false,\"confidence\":92,\"reason\":\"画面里只有一堵墙\"}");

        Map<String, Object> view = act("card-miss-1");
        assertEquals(false, kit(view).get("passed"));
        verify(objectCardService, never()).mint(anyLong(), anyString(), any(JsonNode.class), anyString(),
                anyString());
    }

    /** 降级 + fallback=pass:节点能过,但「这次没能审」,白给一张卡就是把假结论落成了实物。 */
    @Test
    void 负控_降级放行不铸卡() {
        String config = CARD_CONFIG.replace("\"fallback\":\"retake\"", "\"fallback\":\"pass\"");
        given(config);
        modelHasNoKey();

        Map<String, Object> view = act("card-degraded-1");
        Map<?, ?> kit = kit(view);
        assertEquals(true, kit.get("passed"), "兜底 pass 让节点可完成");
        assertEquals(true, kit.get("degraded"));
        verify(objectCardService, never()).mint(anyLong(), anyString(), any(JsonNode.class), anyString(),
                anyString());
    }

    /** 次数用尽被兜底放过(flagged)同上:没拍到达标不该进藏品库。 */
    @Test
    void 负控_次数用尽被放过不铸卡() {
        String config = CARD_CONFIG.replace("\"fallback\":\"retake\"", "\"fallback\":\"pass\"");
        given(config);
        modelSays("{\"pass\":false,\"confidence\":85,\"reason\":\"不满足\"}");

        act("card-exhaust-1");
        act("card-exhaust-2");
        Map<String, Object> third = act("card-exhaust-3");

        assertEquals(true, kit(third).get("passed"));
        assertEquals(true, kit(third).get("flagged"));
        verify(objectCardService, never()).mint(anyLong(), anyString(), any(JsonNode.class), anyString(),
                anyString());
    }

    /**
     * 契约 §10.2「判过 → 当场看到卡」:铸出来的那张卡要随本次回包一起下去。
     * 客户端没有第二个入口 —— 藏品册那一页是「事后翻」,当场这一屏只能靠回包。
     *
     * <p>投影复用 {@link PlayerObjectCardVO}:与册子那条读面同一份字段表,两边不可能漂。
     */
    @Test
    void 判过时回包带上刚铸出来的那张卡() {
        given(CARD_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");
        when(objectCardService.mint(anyLong(), anyString(), any(JsonNode.class), anyString(), anyString()))
                .thenReturn(card());

        Map<String, Object> view = act("card-view-1");

        Object receipt = view.get("objectCard");
        assertTrue(receipt instanceof PlayerObjectCardVO, "回包里要带本次铸出的那张卡:" + view);
        PlayerObjectCardVO shown = (PlayerObjectCardVO) receipt;
        assertEquals(61L, shown.getId().longValue());
        assertEquals("一枚弹壳", shown.getTitle());
        assertEquals(PHOTO_URL, shown.getSourceUrl());
        assertEquals(CUTOUT_URL, shown.getCutoutUrl(), "抠出来的贴纸要随这次回包带下去,否则客户端当场只能看到照片档");
        assertEquals(CATEGORY, shown.getCategory(), "AI 认物的分类要随这次回包带下去");
        assertEquals("plain", shown.getCardStyle());
        assertEquals(ObjectCardService.ORIGIN_PLAY, shown.getOriginType());
        assertEquals(String.valueOf(NODE_ID), shown.getOriginId());
        assertEquals(true, kit(view).get("passed"), "带卡不改变判定");
    }

    /** 负控:老模板(没有 mode)压根没铸卡,回包里也不许多出一个假回执。 */
    @Test
    void 负控_没铸卡时回包不带objectCard() {
        given(CHECK_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");

        Map<String, Object> view = act("legacy-noccard-1");

        assertFalse(view.containsKey("objectCard"), "普通拍照审核的回包与今天逐字相同:" + view);
    }

    /** 铸卡炸了:通关照旧(下一条用例已钉),但不能回一张压根不存在的卡让客户端演假成功。 */
    @Test
    void 铸卡抛异常时回包不带objectCard() {
        given(CARD_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");
        doThrow(new IllegalStateException("藏品库写入失败"))
                .when(objectCardService).mint(anyLong(), anyString(), any(JsonNode.class), anyString(),
                        anyString());

        Map<String, Object> view = act("card-boom-noc-1");

        assertEquals(true, kit(view).get("passed"));
        assertFalse(view.containsKey("objectCard"), "没铸出来就不许回一张卡:" + view);
    }

    // ==================== 铸卡炸了不能连累通关 ====================

    @Test
    void 铸卡抛异常时通关判定与得分原样成立() {
        given(CARD_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");
        doThrow(new IllegalStateException("藏品库写入失败"))
                .when(objectCardService).mint(anyLong(), anyString(), any(JsonNode.class), anyString(),
                        anyString());

        Map<String, Object> view = assertDoesNotThrow(() -> act("card-boom-1"));
        assertEquals(true, kit(view).get("passed"), "铸卡失败不能把已过改成未过:" + view);
        assertEquals(5, view.get("score"), "该给的分照给");
    }

    /** 负控:铸卡服务没装配(纯单测排空 / 灰度关着)时,拍照审核这条老链一字不变。 */
    @Test
    void 负控_铸卡服务缺失时通关不受影响() {
        given(CARD_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");
        ReflectionTestUtils.setField(service, "objectCardService", null);

        Map<String, Object> view = assertDoesNotThrow(() -> act("card-nojob-1"));
        assertEquals(true, kit(view).get("passed"));
        assertEquals(5, view.get("score"));
    }

    /** 铸卡只挂在 SUBMIT_PHOTO_CHECK 的真判过分支上:别的动作即便 state 里躺着 passed 也不重复铸。 */
    @Test
    void 负控_其他动作不触发铸卡() {
        given(COIN_AND_CARD_CONFIG);
        session.setStateJson("{\"photoCheck\":{\"passed\":true,\"tries\":1,\"lastUrl\":\"" + PHOTO_URL
                + "\",\"degraded\":false,\"flagged\":false}}");
        arm();

        service.act(MEMBER_ID, SESSION_ID, session.getVersion(), "other-action", "FLIP_COIN", new HashMap<>());

        verify(objectCardService, never()).mint(anyLong(), anyString(), any(JsonNode.class), anyString(),
                anyString());
    }

    // ==================== 脚手架 ====================

    // ==================== 事务边界(监工复审必须改 2) ====================

    /**
     * 铸卡要并行等抠图与认物最多 4 秒。挂在 act 的 afterCommit 里时,Spring 要等回调跑完才在
     * cleanupAfterCompletion 归还连接 —— 每次判过多占一个库连接 4 秒,Druid 只有 20 个。
     * 所以 act 只判定,真正的铸卡留给事务外的收尾。
     */
    @Test
    void act本身不铸卡_铸卡留给事务外的收尾() {
        given(CARD_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");

        Map<String, Object> judged = judgeOnly("card-judge-only");
        verify(objectCardService, never()).mint(anyLong(), anyString(), any(JsonNode.class), anyString(), anyString());

        service.finishPendingCardMint(judged);
        verify(objectCardService, times(1)).mint(eq(MEMBER_ID), eq(PHOTO_URL), any(JsonNode.class), anyString(),
                eq(String.valueOf(NODE_ID)));
    }

    @Test
    void mintedCardNeverReturnsUnreviewedSourceCutoutOrTurntableUrls() {
        given(CARD_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");
        when(objectCardService.mint(anyLong(), anyString(), any(JsonNode.class), anyString(), anyString())).thenReturn(card());
        com.chengyinhub.business.service.support.AppMediaReferenceGuard media = mock(com.chengyinhub.business.service.support.AppMediaReferenceGuard.class);
        when(media.visibleUrl(anyString(), eq(MEMBER_ID))).thenReturn("");
        ReflectionTestUtils.setField(service, "mediaReferences", media);
        PlayerObjectCardVO result = (PlayerObjectCardVO) act("card-unreviewed").get("objectCard");
        assertEquals("", result.getSourceUrl()); assertEquals("", result.getCutoutUrl()); assertTrue(result.getFrames().isEmpty());
    }

    @Test
    void mediaRegistryFailureKeepsCommittedGameplayWithoutLeakingCardUrls() {
        given(CARD_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");
        when(objectCardService.mint(anyLong(), anyString(), any(JsonNode.class), anyString(), anyString())).thenReturn(card());
        com.chengyinhub.business.service.support.AppMediaReferenceGuard media = mock(com.chengyinhub.business.service.support.AppMediaReferenceGuard.class);
        when(media.visibleUrl(anyString(), eq(MEMBER_ID))).thenThrow(new IllegalStateException("registry unavailable"));
        ReflectionTestUtils.setField(service, "mediaReferences", media);
        Map<String, Object> view = act("card-registry-down");
        assertFalse(view.containsKey("objectCard"));
        assertTrue(view.containsKey("sessionId"));
    }

    @Test
    void 收尾之后回包里不留内部标记_能正常序列化() throws Exception {
        given(CARD_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");
        when(objectCardService.mint(anyLong(), anyString(), any(JsonNode.class), anyString(), anyString()))
                .thenReturn(card());

        Map<String, Object> view = act("card-finish-clean");

        for (String key : view.keySet()) {
            assertFalse(key.startsWith("_"), "内部标记漏进了回包:" + key);
        }
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(view);
        assertFalse(json.contains("pendingCardMint"), json);
    }

    /** 同一张回包收尾两次只铸一次:标记被第一次收尾拿走,第二次什么都不做。 */
    @Test
    void 负控_收尾两次只铸一次() {
        given(CARD_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");

        Map<String, Object> judged = judgeOnly("card-finish-twice");
        service.finishPendingCardMint(judged);
        service.finishPendingCardMint(judged);
        verify(objectCardService, times(1)).mint(anyLong(), anyString(), any(JsonNode.class), anyString(), anyString());
    }

    /** Actual Spring transaction scopes, a NEVER provider, and post-commit mint; no database claim. */
    @Test
    void 照片先提交原意图_模型NEVER_玩法回执与APPLIED同事务_提交后铸卡() throws Exception {
        given(CARD_CONFIG);
        modelSays("{\"pass\":true,\"confidence\":88,\"reason\":\"拍到了\"}");
        List<String> phases = new ArrayList<>();
        AbstractPlatformTransactionManager tx = new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected boolean isExistingTransaction(Object transaction) {
                return TransactionSynchronizationManager.isActualTransactionActive();
            }
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                assertFalse(definition.isReadOnly());
                assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, definition.getIsolationLevel());
                phases.add("begin");
            }
            protected void doCommit(DefaultTransactionStatus status) { phases.add("commit"); }
            protected void doRollback(DefaultTransactionStatus status) { phases.add("rollback"); }
        };
        ReflectionTestUtils.setField(service, "transactionManager", tx);
        TransactionAttribute providerBoundary = new AnnotationTransactionAttributeSource().getTransactionAttribute(
                PhotoCheckIntentService.class.getMethod("executeOnce", Long.class, Long.class, String.class), PhotoCheckIntentService.class);
        org.junit.jupiter.api.Assertions.assertNotNull(providerBoundary);
        assertEquals(TransactionDefinition.PROPAGATION_NEVER, providerBoundary.getPropagationBehavior());
        TransactionTemplate providerNever = new TransactionTemplate(tx, providerBoundary);
        PhotoCheckIntentService storedIntents = photoIntents;
        PhotoCheckIntentService observed = mock(PhotoCheckIntentService.class, delegatesTo(storedIntents));
        AtomicReference<PhotoCheckIntentCommand> original = new AtomicReference<>();
        doAnswer(i -> {
            assertWritableTransaction(); phases.add("prepare"); original.set(i.getArgument(0));
            return storedIntents.prepare(i.getArgument(0));
        }).when(observed).prepare(any());
        doAnswer(i -> {
            assertEquals(List.of("begin", "prepare", "commit"), phases, "original intent must commit before provider");
            return providerNever.execute(status -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                phases.add("provider");
                return storedIntents.executeOnce(i.getArgument(0), i.getArgument(1), i.getArgument(2));
            });
        }).when(observed).executeOnce(anyLong(), anyLong(), anyString());
        doAnswer(i -> {
            assertWritableTransaction();
            PlayAttemptLog receipt = attemptMapper.selectByIdempotencyKeyForUpdate(original.get().actionKey());
            assertEquals(receipt.getId(), i.<Long>getArgument(2));
            assertEquals(original.get().requestFingerprint(), receipt.getRequestFingerprint());
            assertEquals(Integer.valueOf(8), receipt.getAttemptType());
            assertEquals(MEMBER_ID, receipt.getMemberId().longValue());
            phases.add("markApplied");
            storedIntents.markApplied(i.getArgument(0), i.getArgument(1), i.getArgument(2));
            return null;
        }).when(observed).markApplied(anyLong(), anyString(), anyLong());
        ReflectionTestUtils.setField(service, "photoCheckIntentService", observed);
        doAnswer(i -> { assertWritableTransaction(); phases.add("cas"); return 1; })
                .when(sessionMapper).updateStateCas(any());
        doAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(List.of("begin", "prepare", "commit", "provider", "begin", "cas", "markApplied", "commit"), phases);
            phases.add("mint"); return card();
        }).when(objectCardService).mint(anyLong(), anyString(), any(JsonNode.class), anyString(), anyString());

        Map<String, Object> result = act("card-transaction-scopes");
        assertEquals(true, kit(result).get("passed")); assertEquals(5, result.get("score"));
        assertTrue(result.get("objectCard") instanceof PlayerObjectCardVO);
        assertEquals(List.of("begin", "prepare", "commit", "provider", "begin", "cas", "markApplied", "commit", "mint"), phases);
        verify(quota).consumeQuota(MEMBER_ID, "photo_check");
        verify(visionService).review(anyLong(), anyString(), anyString());
        verify(attemptMapper).insertPlayAttemptLog(any());
        // The provider's NEVER scope must reject a business transaction before touching Vision/quota.
        TransactionTemplate enclosing = new TransactionTemplate(tx);
        enclosing.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        org.junit.jupiter.api.Assertions.assertThrowsExactly(
                org.springframework.transaction.IllegalTransactionStateException.class,
                () -> enclosing.execute(status -> providerNever.execute(inner -> { throw new AssertionError("provider entered"); })));
        verify(quota).consumeQuota(MEMBER_ID, "photo_check");
        verify(visionService).review(anyLong(), anyString(), anyString());
    }

    private void assertWritableTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }

    private void given(String config) {
        session = running(config);
        arm();
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

    private void modelHasNoKey() {
        ReflectionTestUtils.setField(visionService, "visionClient",
                new OpenAiCompatibleLlmClient("vision", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "", "qwen-vl-max", true, 3000));
    }

    /** 与控制器同序:act 完成短事务编排后,事务外收尾铸卡。 */
    private Map<String, Object> act(String key) {
        return service.finishPendingCardMint(judgeOnly(key));
    }

    private Map<String, Object> judgeOnly(String key) {
        when(attemptMapper.selectByIdempotencyKey(key)).thenReturn(null);
        Map<String, Object> payload = new HashMap<>();
        payload.put("imageUrl", PHOTO_URL);
        return service.act(MEMBER_ID, SESSION_ID, session.getVersion(), key, "SUBMIT_PHOTO_CHECK", payload);
    }

    private Map<?, ?> kit(Map<String, Object> view) {
        return (Map<?, ?>) ((Map<?, ?>) view.get("playKit")).get("photoCheck");
    }

    /** mint 的返回值长相:真实实现 upsert 后把整行还回来,这里照形状造一份。 */
    private PlayerObjectCard card() {
        PlayerObjectCard row = new PlayerObjectCard();
        row.setId(61L);
        row.setMemberId(MEMBER_ID);
        row.setSourceUrl(PHOTO_URL);
        row.setTitle("一枚弹壳");
        row.setCutoutUrl(CUTOUT_URL);
        row.setCategory(CATEGORY);
        row.setCaption("把窗框也拍进去");
        row.setCardStyle("plain");
        row.setOriginType(ObjectCardService.ORIGIN_PLAY);
        row.setOriginId(String.valueOf(NODE_ID));
        row.setCreateTime(new Date());
        return row;
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

    private static byte[] jpeg() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "jpeg", out);
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

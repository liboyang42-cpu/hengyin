package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.vo.ContentManifestVO;
import com.chengyinhub.business.domain.vo.TopicContentRevisionView;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ITopicContentRevisionService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BE-10 清单入口的准入与越权边界。
 *
 * <p>固定五条:必须登录、必须已报名/持通行证、已开局只读本局快照、
 * 不凭 revisionId 读别人主题、清单读取不产生任何运行时写入。
 */
class PlayContentManifestApplicationTest {

    private PlayContentManifestApplication application;
    private ICmsActivityService activityService;
    private PlayCompletionFacts completionFacts;
    private PlayNodeContext nodeContext;
    private ITopicRouteRuntimeService runtime;
    private ITopicContentRevisionService revisions;
    private com.chengyinhub.business.service.IGameSessionRuntimeService gameSessions;
    private com.chengyinhub.business.mapper.CmsRegistrationMapper cmsRegistrationMapper;
    private com.chengyinhub.business.service.IMarketingGiftPlayEligibility giftPlayEligibility;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        application = new PlayContentManifestApplication();
        activityService = mock(ICmsActivityService.class);
        completionFacts = mock(PlayCompletionFacts.class);
        nodeContext = mock(PlayNodeContext.class);
        runtime = mock(ITopicRouteRuntimeService.class);
        revisions = mock(ITopicContentRevisionService.class);
        gameSessions = mock(com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        ReflectionTestUtils.setField(application, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(application, "playCompletionFacts", completionFacts);
        ReflectionTestUtils.setField(application, "playNodeContext", nodeContext);
        cmsRegistrationMapper = mock(CmsRegistrationMapper.class);
        giftPlayEligibility = mock(com.chengyinhub.business.service.IMarketingGiftPlayEligibility.class);
        ReflectionTestUtils.setField(application, "currentPlayActivityMapper", mock(CmsActivityMapper.class));
        ReflectionTestUtils.setField(application, "cmsRegistrationMapper", cmsRegistrationMapper);
        ReflectionTestUtils.setField(application, "topicRouteRuntimeService", runtime);
        ReflectionTestUtils.setField(application, "contentRevisionService", revisions);
        ReflectionTestUtils.setField(application, "gameSessionRuntimeService", gameSessions);
        ReflectionTestUtils.setField(application, "giftPlayEligibility", giftPlayEligibility);
    }

    private CmsActivity activity() {
        CmsActivity activity = new CmsActivity();
        activity.setId(77L);
        activity.setTopicId(21L);
        return activity;
    }

    private ObjectNode frozen() {
        ObjectNode frozen = json.createObjectNode().put("runtimeSnapshotVersion", 1).put("routeMode", "LINEAR");
        frozen.putObject("openingChapter").put("chapterId", 501L).put("name", "序章");
        frozen.putObject("nodeAnswers").putObject("11").put("contentSnapshotVersion", 1)
                .putObject("nodePresentation").put("id", 11L).put("topicId", 21L);
        frozen.putObject("nodeConfigs").putObject("11").putObject("qa").put("enabled", true);
        frozen.putArray("routeStations").addObject().put("nodeId", 11L).put("chapterId", 501L);
        return frozen;
    }

    private TopicContentRevisionView revision(long id, long revisionNo) {
        TopicContentRevisionView view = new TopicContentRevisionView();
        view.setId(id);
        view.setTopicId(21L);
        view.setRevisionNo(revisionNo);
        view.setSchemaVersion(1);
        view.setContentSha256("sha-" + revisionNo);
        view.setPublicManifestJson("{\"manifestVersion\":1,\"topicId\":21,\"revisionNo\":" + revisionNo
                + ",\"contentSha256\":\"sha-" + revisionNo
                + "\",\"schemaVersion\":1,\"stations\":[{\"nodeId\":11,\"chapterId\":501}]}");
        view.setOrigin("PUBLISH");
        return view;
    }

    @Test
    void anonymousCallerNeverReadsContent() {
        AjaxResult result = application.manifest(0L, null, 21L, null);
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(activityService, completionFacts, runtime, revisions);
    }

    @Test
    void activityCallerMustHoldPaidRegistration() {
        when(activityService.selectCmsActivityById(77L)).thenReturn(activity());
        when(completionFacts.isRegistered(7L, 77L)).thenReturn(false);

        AjaxResult result = application.manifest(7L, 77L, null, null);

        assertEquals(402, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(runtime, revisions);
    }

    @Test
    void activityCallerCannotAskForAnotherTopicsRevision() {
        when(activityService.selectCmsActivityById(77L)).thenReturn(activity());
        when(completionFacts.isRegistered(7L, 77L)).thenReturn(true);
        when(nodeContext.activityTimeError(any())).thenReturn(null);

        AjaxResult result = application.manifest(7L, 77L, 22L, null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(revisions);
    }

    @Test
    void selfPlayCallerWithoutPassGets402AndNoManifest() {
        when(completionFacts.validSelfPlayPass(7L, 21L)).thenReturn(null);
        when(runtime.hasRunningSession(7L, 21L, 0L)).thenReturn(false);

        AjaxResult result = application.manifest(7L, null, 21L, null);

        assertEquals(402, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(revisions);
    }

    @Test
    void runningSessionReadsItsOwnSnapshotAndIgnoresCurrentPublication() {
        CmsRegistration pass = new CmsRegistration();
        pass.setExpiresAt(new Date(System.currentTimeMillis() + 60_000L));
        when(completionFacts.validSelfPlayPass(7L, 21L)).thenReturn(pass);
        when(runtime.hasRunningSession(7L, 21L, 0L)).thenReturn(true);
        ObjectNode own = frozen().put("contentRouteSessionId", 91L);
        when(runtime.queryNodeContentSnapshot(7L, 21L, 0L, null)).thenReturn(own);

        AjaxResult result = application.manifest(7L, null, 21L, null);

        Object data = result.get(AjaxResult.DATA_TAG);
        assertInstanceOf(ContentManifestVO.class, data);
        ContentManifestVO manifest = (ContentManifestVO) data;
        assertEquals(ContentManifestVO.STATUS_RUN_SNAPSHOT, manifest.getStatus());
        assertEquals(21L, manifest.getTopicId());
        verify(revisions, never()).currentPublished(anyLong());
        verify(revisions, never()).publishedRevision(anyLong(), anyLong());
        verify(revisions, never()).frozenSnapshot(anyLong(), anyLong());
    }

    @Test
    void startedRunWithoutOwnSnapshotStaysOnlineInsteadOfBorrowingPublication() {
        // 线性路线只记过进度:没有本局内容快照,也没有可冒充的修订清单。
        CmsRegistration pass = new CmsRegistration();
        when(completionFacts.validSelfPlayPass(7L, 21L)).thenReturn(pass);
        when(runtime.hasRunningSession(7L, 21L, 0L)).thenReturn(true);
        when(runtime.queryNodeContentSnapshot(7L, 21L, 0L, null)).thenReturn(frozen());

        AjaxResult result = application.manifest(7L, null, 21L, null);

        ContentManifestVO manifest = (ContentManifestVO) result.get(AjaxResult.DATA_TAG);
        assertEquals(ContentManifestVO.STATUS_ONLINE_LEGACY, manifest.getStatus());
        assertTrue(manifest.isOnlineRequired());
        assertNull(manifest.getContentRevisionId());
        verifyNoInteractions(revisions);
    }

    @Test
    void sharedRunInProgressReadsItsTeamSnapshotNotCurrentPublication() {
        when(activityService.selectCmsActivityById(77L)).thenReturn(activity());
        when(completionFacts.isRegistered(7L, 77L)).thenReturn(true);
        when(nodeContext.activityTimeError(any())).thenReturn(null);
        when(runtime.hasRunningSession(7L, 21L, 77L)).thenReturn(false);
        when(gameSessions.routeSessionStatus(77L)).thenReturn("RUNNING");
        ObjectNode shared = frozen().put("gameSessionId", 555L).put("teamId", 3L);
        when(runtime.queryNodeContentSnapshot(7L, 21L, 77L, null)).thenReturn(shared);

        AjaxResult result = application.manifest(7L, 77L, null, null);

        ContentManifestVO manifest = (ContentManifestVO) result.get(AjaxResult.DATA_TAG);
        assertEquals(ContentManifestVO.STATUS_RUN_SNAPSHOT, manifest.getStatus());
        assertEquals(77L, manifest.getActivityId());
        verifyNoInteractions(revisions);
    }

    @Test
    void runScopedSnapshotFailureNeverFallsBackToPublication() {
        CmsRegistration pass = new CmsRegistration();
        when(completionFacts.validSelfPlayPass(7L, 21L)).thenReturn(pass);
        when(runtime.hasRunningSession(7L, 21L, 0L)).thenReturn(true);
        when(runtime.queryNodeContentSnapshot(7L, 21L, 0L, null))
                .thenThrow(new IllegalStateException("本局内容归属不可用"));

        AjaxResult result = application.manifest(7L, null, 21L, null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(revisions);
    }

    @Test
    void topicWithoutRevisionStaysOnExistingOnlineRead() {
        CmsRegistration pass = new CmsRegistration();
        when(completionFacts.validSelfPlayPass(7L, 21L)).thenReturn(pass);
        when(runtime.hasRunningSession(7L, 21L, 0L)).thenReturn(false);
        when(revisions.currentPublished(21L)).thenReturn(null);

        AjaxResult result = application.manifest(7L, null, 21L, null);

        ContentManifestVO manifest = (ContentManifestVO) result.get(AjaxResult.DATA_TAG);
        assertEquals(ContentManifestVO.STATUS_ONLINE_LEGACY, manifest.getStatus());
        assertTrue(manifest.isOnlineRequired());
    }

    @Test
    void explicitRevisionOfAnotherTopicIsNotReadable() {
        CmsRegistration pass = new CmsRegistration();
        when(completionFacts.validSelfPlayPass(7L, 21L)).thenReturn(pass);
        when(runtime.hasRunningSession(7L, 21L, 0L)).thenReturn(false);
        // 别人的修订:按 (topicId, revisionId) 查不到。
        when(revisions.publishedRevision(21L, 555L)).thenReturn(null);

        AjaxResult result = application.manifest(7L, null, 21L, 555L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(revisions).publishedRevision(21L, 555L);
        verify(revisions, never()).currentPublished(anyLong());
        verify(revisions, never()).frozenSnapshot(anyLong(), anyLong());
    }

    @Test
    void explicitRevisionIsServedWithRealFrozenContent() {
        CmsRegistration pass = new CmsRegistration();
        when(completionFacts.validSelfPlayPass(7L, 21L)).thenReturn(pass);
        when(runtime.hasRunningSession(7L, 21L, 0L)).thenReturn(false);
        when(revisions.publishedRevision(21L, 900L)).thenReturn(revision(900L, 3L));
        when(revisions.frozenSnapshot(21L, 900L)).thenReturn(frozen().toString());

        AjaxResult result = application.manifest(7L, null, 21L, 900L);

        ContentManifestVO manifest = (ContentManifestVO) result.get(AjaxResult.DATA_TAG);
        assertEquals(ContentManifestVO.STATUS_REVISION, manifest.getStatus());
        assertEquals(900L, manifest.getContentRevisionId());
        assertEquals(3L, manifest.getRevisionNo());
        assertEquals(1, manifest.getStations().size());
    }

    @Test
    void unreadableFrozenPayloadFailsClosed() {
        CmsRegistration pass = new CmsRegistration();
        when(completionFacts.validSelfPlayPass(7L, 21L)).thenReturn(pass);
        when(runtime.hasRunningSession(7L, 21L, 0L)).thenReturn(false);
        when(revisions.currentPublished(21L)).thenReturn(revision(900L, 3L));

        AjaxResult blank = application.manifest(7L, null, 21L, null);
        assertEquals(500, blank.get(AjaxResult.CODE_TAG));

        when(revisions.frozenSnapshot(21L, 900L)).thenReturn("not-json");
        AjaxResult broken = application.manifest(7L, null, 21L, null);
        assertEquals(500, broken.get(AjaxResult.CODE_TAG));
    }

    @Test
    void manifestReadNeverInitialisesRunOrSettlesAnything() {
        CmsRegistration pass = new CmsRegistration();
        when(completionFacts.validSelfPlayPass(7L, 21L)).thenReturn(pass);
        when(runtime.hasRunningSession(7L, 21L, 0L)).thenReturn(false);
        when(revisions.currentPublished(21L)).thenReturn(revision(900L, 3L));
        when(revisions.frozenSnapshot(21L, 900L)).thenReturn(frozen().toString());

        application.manifest(7L, null, 21L, null);

        // 只有只读查询;没有任何开局 / 到达 / 奖励入口被碰到。
        verify(runtime, never()).state(anyLong(), anyLong(), anyLong());
        verify(runtime, never()).nodeRuntimeConfig(anyLong(), anyLong(), anyLong(), anyLong());
        verify(runtime, never()).bargainOffered(anyLong(), anyLong(), anyLong());
    }

    @Test
    void revisionIndexAbsentKeepsLegacyOnlineRead() {
        ReflectionTestUtils.setField(application, "contentRevisionService", null);
        CmsRegistration pass = new CmsRegistration();
        when(completionFacts.validSelfPlayPass(7L, 21L)).thenReturn(pass);
        when(runtime.hasRunningSession(7L, 21L, 0L)).thenReturn(false);

        AjaxResult result = application.manifest(7L, null, 21L, null);

        ContentManifestVO manifest = (ContentManifestVO) result.get(AjaxResult.DATA_TAG);
        assertEquals(ContentManifestVO.STATUS_ONLINE_LEGACY, manifest.getStatus());
    }

    // ---------------------------------------------------------------- 礼品闸回归(BE-10 F1)

    private static final long GIFT_BUYER = 7L;
    private static final long GIFT_RECIPIENT = 9L;
    private static final long GIFT_REGISTRATION = 501L;

    private CmsRegistration paidSelfPlayRow(long memberId) {
        CmsRegistration row = new CmsRegistration();
        row.setId(GIFT_REGISTRATION);
        row.setMemberId(memberId);
        row.setOwnerType(1L);
        row.setOwnerId(21L);
        row.setRegistrationStatus(2);
        row.setPaymentStatus(2);
        row.setPurchaseKind(2);
        row.setExpiresAt(new Date(System.currentTimeMillis() + 86_400_000L));
        return row;
    }

    /** 买家原已付行仍在库:谁买、待领取/已领取给谁,都由礼品事实投影出来。 */
    private CmsRegistration buyerOwnsGiftablePass() {
        CmsRegistration buyerRow = paidSelfPlayRow(GIFT_BUYER);
        when(cmsRegistrationMapper.selectCmsRegistrationList(any())).thenAnswer(inv -> {
            CmsRegistration probe = inv.getArgument(0);
            return probe.getMemberId() != null && GIFT_BUYER == probe.getMemberId().longValue()
                    ? java.util.Collections.singletonList(buyerRow)
                    : new java.util.ArrayList<CmsRegistration>();
        });
        when(cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(GIFT_REGISTRATION)).thenReturn(buyerRow);
        when(cmsRegistrationMapper.selectCmsRegistrationById(GIFT_REGISTRATION)).thenReturn(buyerRow);
        return buyerRow;
    }

    private com.chengyinhub.business.domain.GiftPlayFact giftFact(int status, Long claimedMemberId) {
        com.chengyinhub.business.domain.GiftPlayFact fact = new com.chengyinhub.business.domain.GiftPlayFact();
        fact.setRegistrationId(GIFT_REGISTRATION);
        fact.setStatus(status);
        fact.setClaimedMemberId(claimedMemberId);
        return fact;
    }

    @Test
    void pendingGiftBuyerWithRunningSessionCannotReadRunSnapshot() {
        // 回归:pass 被礼品剥成 null 且本局已开局时,下载入口必须和 /nodes 一样拒绝,
        // 不能按 member_id 吃下买家自己的原行再吐本局冻结内容。
        buyerOwnsGiftablePass();
        when(completionFacts.validSelfPlayPass(GIFT_BUYER, 21L)).thenReturn(null);
        when(runtime.hasRunningSession(GIFT_BUYER, 21L, 0L)).thenReturn(true);
        when(giftPlayEligibility.playFacts(GIFT_BUYER, 1L, 21L))
                .thenReturn(java.util.List.of(giftFact(com.chengyinhub.business.domain.GiftPlayFact.STATUS_PENDING, null)));

        AjaxResult result = application.manifest(GIFT_BUYER, null, 21L, null);

        assertEquals(402, result.get(AjaxResult.CODE_TAG));
        verify(runtime, never()).queryNodeContentSnapshot(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(revisions);
    }

    @Test
    void giftClaimedByAnotherMemberAlsoBlocksBuyerDownload() {
        CmsRegistration buyerRow = buyerOwnsGiftablePass();
        when(completionFacts.validSelfPlayPass(GIFT_BUYER, 21L)).thenReturn(null);
        when(runtime.hasRunningSession(GIFT_BUYER, 21L, 0L)).thenReturn(true);
        when(giftPlayEligibility.playFacts(GIFT_BUYER, 1L, 21L))
                .thenReturn(java.util.List.of(giftFact(com.chengyinhub.business.domain.GiftPlayFact.STATUS_CLAIMED, GIFT_RECIPIENT)));

        AjaxResult result = application.manifest(GIFT_BUYER, null, 21L, null);

        assertEquals(402, result.get(AjaxResult.CODE_TAG));
        verify(runtime, never()).queryNodeContentSnapshot(anyLong(), anyLong(), anyLong(), any());
        // 财务归属不变:准入失败不得改写或重建买家的原已付行。
        assertEquals(GIFT_BUYER, buyerRow.getMemberId().longValue());
        verify(cmsRegistrationMapper, never()).insertCmsRegistration(any());
        verify(cmsRegistrationMapper, never()).updateCmsRegistration(any());
    }

    @Test
    void giftRecipientWithRunningSessionReadsRunSnapshotWithoutOwningTheRow() {
        CmsRegistration buyerRow = buyerOwnsGiftablePass();
        when(completionFacts.validSelfPlayPass(GIFT_RECIPIENT, 21L)).thenReturn(null);
        when(runtime.hasRunningSession(GIFT_RECIPIENT, 21L, 0L)).thenReturn(true);
        when(giftPlayEligibility.playFacts(GIFT_RECIPIENT, 1L, 21L))
                .thenReturn(java.util.List.of(giftFact(com.chengyinhub.business.domain.GiftPlayFact.STATUS_CLAIMED, GIFT_RECIPIENT)));
        ObjectNode own = frozen().put("contentRouteSessionId", 91L);
        when(runtime.queryNodeContentSnapshot(GIFT_RECIPIENT, 21L, 0L, null)).thenReturn(own);

        AjaxResult result = application.manifest(GIFT_RECIPIENT, null, 21L, null);

        ContentManifestVO manifest = (ContentManifestVO) result.get(AjaxResult.DATA_TAG);
        assertEquals(ContentManifestVO.STATUS_RUN_SNAPSHOT, manifest.getStatus());
        // 领取人只是被放行,原行仍归买家;不新建第二份准入或资金行。
        assertEquals(GIFT_BUYER, buyerRow.getMemberId().longValue());
        verify(cmsRegistrationMapper, never()).insertCmsRegistration(any());
        verify(cmsRegistrationMapper, never()).updateCmsRegistration(any());
    }

    @Test
    void buyerWithoutAnyGiftFactKeepsOriginalContinueBehaviour() {
        buyerOwnsGiftablePass();
        when(completionFacts.validSelfPlayPass(GIFT_BUYER, 21L)).thenReturn(null);
        when(runtime.hasRunningSession(GIFT_BUYER, 21L, 0L)).thenReturn(true);
        when(giftPlayEligibility.playFacts(GIFT_BUYER, 1L, 21L)).thenReturn(java.util.List.of());
        ObjectNode own = frozen().put("contentRouteSessionId", 91L);
        when(runtime.queryNodeContentSnapshot(GIFT_BUYER, 21L, 0L, null)).thenReturn(own);

        AjaxResult result = application.manifest(GIFT_BUYER, null, 21L, null);

        assertEquals(ContentManifestVO.STATUS_RUN_SNAPSHOT,
                ((ContentManifestVO) result.get(AjaxResult.DATA_TAG)).getStatus());
    }
}

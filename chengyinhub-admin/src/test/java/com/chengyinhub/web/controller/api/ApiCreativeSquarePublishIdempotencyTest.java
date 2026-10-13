package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R10-03:发布意图键在控制器层的编排 —— 已提交丢响应重试不重复审核/送检/插入;
 * 审核拒绝幂等;同键变载荷冲突;并发待审走审核单飞锁;旧调用者行为不变。
 */
class ApiCreativeSquarePublishIdempotencyTest {

    private static final Long ME = 9L;
    private static final String RID = "sq_intent_000000000001";
    private static final String LOCK = "square:publish:audit:";

    private static class StubController extends ApiCreativeSquareController {
        @Override
        public Long getAppUserId() { return ME; }
    }

    private StubController controller;
    private IUmsCreativeSquareService squareService;
    private IWxContentSecurityService securityService;
    private RedisCache redisCache;

    @BeforeEach
    void setUp() {
        controller = new StubController();
        // 手工 new 出来的控制器不走 @Value 默认值,旧写门禁必须显式开闸(与 Spring 运行时默认一致)。
        ReflectionTestUtils.setField(controller, "legacyCommunityWritesEnabled", true);
        squareService = mock(IUmsCreativeSquareService.class);
        securityService = mock(IWxContentSecurityService.class);
        redisCache = mock(RedisCache.class);
        ReflectionTestUtils.setField(controller, "umsCreativeSquareService", squareService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", securityService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", mock(ICmsRegistrationService.class));
        ReflectionTestUtils.setField(controller, "redisCache", redisCache);
    }

    @Test
    void newIntentRunsAuditOnceAndReleasesLock() {
        stubIdempotent(IUmsCreativeSquareService.IdempotentPublishResult.Kind.NEW, existing(60L, 0));
        when(redisCache.setIfAbsent(eq(LOCK + 60L), eq("1"), eq(30), any())).thenReturn(true);

        AjaxResult result = controller.creativeSquareActoin(null, "正文", "", "", "", "", "", null, "", "", null, RID);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(squareService).updateUmsCreativeSquare(any());
        verify(securityService).checkText(eq("正文"), any());
        verify(securityService).submitMediaCheck(anyInt(), eq(60L), eq(""), any());
        verify(redisCache).deleteObject(LOCK + 60L);
    }

    @Test
    void committedButLostResponseRetryReturnsExistingWithoutTextAuditButRecoversMedia() {
        UmsCreativeSquare visible = existing(61L, 1);
        visible.setPics("https://cdn.example/a.jpg;https://cdn.example/b.jpg");
        stubIdempotent(IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_VISIBLE, visible);

        AjaxResult result = controller.creativeSquareActoin(null, "正文", visible.getPics(), "", "", "", "", null, "", "", null, RID);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(securityService, never()).checkText(any(), any());
        verify(squareService, never()).updateUmsCreativeSquare(any());
        verify(redisCache, never()).setIfAbsent(anyString(), any(), anyInt(), any());
        // R10-03 收尾:文本审核/插入不重复,但媒体送检是 best-effort,未知结果必须能补送
        verify(securityService).submitMediaCheck(anyInt(), eq(61L), eq(visible.getPics()), any());
    }

    @Test
    void retryWithoutMediaNeverCallsRecoverySubmit() {
        stubIdempotent(IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_VISIBLE, existing(62L, 1));

        AjaxResult result = controller.creativeSquareActoin(null, "正文", "", "", "", "", "", null, "", "", null, RID);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(securityService, never()).submitMediaCheck(anyInt(), any(), any(), any());
        verify(securityService, never()).checkText(any(), any());
    }

    @Test
    void softDeletedIntentRetryNeverReportsSuccessNorResurrects() {
        UmsCreativeSquare deleted = existing(68L, 1);
        deleted.setDelFlag(2);
        stubIdempotent(IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_DELETED, deleted);

        AjaxResult result = controller.creativeSquareActoin(null, "正文", "pic", "", "", "", "", null, "", "", null, RID);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals(Boolean.TRUE, result.get("intentDiscarded"), "必须带客户端丢键标记");
        verify(securityService, never()).checkText(any(), any());
        verify(securityService, never()).submitMediaCheck(anyInt(), any(), any(), any());
        verify(squareService, never()).updateUmsCreativeSquare(any());
        verify(squareService, never()).insertUmsCreativeSquare(any());
    }

    @Test
    void redisFailureCompletesAuditInsteadOfStaleSuccessOrStuckConfirm() {
        stubIdempotent(IUmsCreativeSquareService.IdempotentPublishResult.Kind.PENDING, existing(69L, 0));
        when(redisCache.setIfAbsent(anyString(), any(), anyInt(), any()))
                .thenThrow(new RuntimeException("redis down"));

        AjaxResult result = controller.creativeSquareActoin(null, "正文", "", "", "", "", "", null, "", "", null, RID);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(securityService).checkText(eq("正文"), any());
        verify(squareService).updateUmsCreativeSquare(any());
        verify(redisCache, never()).deleteObject(anyString());
    }

    @Test
    void auditRejectedIntentRetryFailsConsistentlyWithoutNewAudit() {
        stubIdempotent(IUmsCreativeSquareService.IdempotentPublishResult.Kind.EXISTING_REJECTED, existing(62L, 2));

        AjaxResult result = controller.creativeSquareActoin(null, "正文", "", "", "", "", "", null, "", "", null, RID);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("审核"));
        verify(securityService, never()).checkText(any(), any());
        verify(squareService, never()).updateUmsCreativeSquare(any());
    }

    @Test
    void sameKeyDifferentPayloadIsConflictWithoutAudit() {
        stubIdempotent(IUmsCreativeSquareService.IdempotentPublishResult.Kind.CONFLICT, existing(63L, 1));

        AjaxResult result = controller.creativeSquareActoin(null, "改过的正文", "", "", "", "", "", null, "", "", null, RID);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("发布标识"));
        verify(securityService, never()).checkText(any(), any());
    }

    @Test
    void invalidRequestKeyFailsBeforeService() {
        AjaxResult result = controller.creativeSquareActoin(null, "正文", "", "", "", "", "", null, "", "", null, "short");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(squareService, never()).insertUmsCreativeSquareIdempotent(any(), any(), any());
        verify(securityService, never()).checkText(any(), any());
    }

    @Test
    void pendingIntentClaimsLockAndCompletesAudit() {
        stubIdempotent(IUmsCreativeSquareService.IdempotentPublishResult.Kind.PENDING, existing(64L, 0));
        when(redisCache.setIfAbsent(eq(LOCK + 64L), eq("1"), eq(30), any())).thenReturn(true);

        AjaxResult result = controller.creativeSquareActoin(null, "正文", "", "", "", "", "", null, "", "", null, RID);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(securityService).submitMediaCheck(anyInt(), eq(64L), eq(""), any());
        verify(redisCache).deleteObject(LOCK + 64L);
    }

    @Test
    void concurrentPendingWithoutLockReturnsCurrentTruthNeverFakeSuccess() {
        stubIdempotent(IUmsCreativeSquareService.IdempotentPublishResult.Kind.PENDING, existing(65L, 0));
        when(redisCache.setIfAbsent(anyString(), any(), anyInt(), any())).thenReturn(false);

        // 另一个请求已把帖子审成可见 → 直接成功,不重复送检
        when(squareService.selectUmsCreativeSquareById(65L)).thenReturn(existing(65L, 1));
        assertEquals(200, controller.creativeSquareActoin(null, "正文", "", "", "", "", "", null, "", "", null, RID)
                .get(AjaxResult.CODE_TAG));
        verify(securityService, never()).submitMediaCheck(anyInt(), any(), any(), any());

        // 还没审完 → 明确"确认中",不假装成功
        when(squareService.selectUmsCreativeSquareById(66L)).thenReturn(existing(66L, 0));
        stubIdempotent(IUmsCreativeSquareService.IdempotentPublishResult.Kind.PENDING, existing(66L, 0));
        AjaxResult confirming = controller.creativeSquareActoin(null, "正文", "", "", "", "", "", null, "", "", null, RID);
        assertEquals(500, confirming.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(confirming.get(AjaxResult.MSG_TAG)).contains("确认中"));
    }

    @Test
    void auditBlockMarksRejectedAndReturnsErrorWithoutMediaCheck() {
        stubIdempotent(IUmsCreativeSquareService.IdempotentPublishResult.Kind.NEW, existing(67L, 0));
        when(redisCache.setIfAbsent(eq(LOCK + 67L), eq("1"), eq(30), any())).thenReturn(true);
        when(securityService.checkText(eq("违规内容"), any())).thenReturn("内容不合规");

        AjaxResult result = controller.creativeSquareActoin(null, "违规内容", "", "", "", "", "", null, "", "", null, RID);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("内容不合规", result.get(AjaxResult.MSG_TAG));
        ArgumentCaptor<UmsCreativeSquare> captor = ArgumentCaptor.forClass(UmsCreativeSquare.class);
        verify(squareService, times(1)).updateUmsCreativeSquare(captor.capture());
        assertEquals(2, captor.getValue().getStatus());
        verify(securityService, never()).submitMediaCheck(anyInt(), any(), any(), any());
        verify(redisCache).deleteObject(LOCK + 67L);
    }

    @Test
    void legacyCallerWithoutRequestKeyKeepsOriginalBehavior() {
        when(squareService.insertUmsCreativeSquare(any())).thenReturn(1);

        AjaxResult result = controller.creativeSquareActoin(null, "正文", "", "", "", "", "", null, "", "", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(squareService).insertUmsCreativeSquare(any());
        verify(squareService, never()).insertUmsCreativeSquareIdempotent(any(), any(), any());
        verify(securityService).checkText(eq("正文"), any());
    }

    @Test
    void payloadSignatureChangesWhenIntentChanged() {
        UmsCreativeSquare a = new UmsCreativeSquare();
        a.setContents("正文");
        a.setPics("p1");
        a.setStatus(0);
        UmsCreativeSquare b = new UmsCreativeSquare();
        b.setContents("正文");
        b.setPics("p2");
        b.setStatus(0);
        String sigA = ApiCreativeSquareController.publishSignature(a);
        String sigB = ApiCreativeSquareController.publishSignature(b);

        assertNotNull(sigA);
        assertEquals(64, sigA.length());
        assertNotEquals(sigA, sigB, "媒体变化必须是不同的发布意图指纹");
    }

    @Test
    void signatureIsUnambiguousWhenFieldsContainSeparatorLikeChars() {
        // 旧 \u0001 拼接下这两组不同载荷会算出同一 sha256;长度前缀后必须区分
        UmsCreativeSquare splitAtFileUrl = new UmsCreativeSquare();
        splitAtFileUrl.setContents("A");
        splitAtFileUrl.setPics("B");
        splitAtFileUrl.setFileUrl("\u0001C");
        UmsCreativeSquare splitAtPics = new UmsCreativeSquare();
        splitAtPics.setContents("A");
        splitAtPics.setPics("B\u0001");
        splitAtPics.setFileUrl("C");

        String sigA = ApiCreativeSquareController.publishSignature(splitAtFileUrl);
        String sigB = ApiCreativeSquareController.publishSignature(splitAtPics);
        assertNotEquals(sigA, sigB, "字段边界必须由序列化本身唯一确定");

        // 同载荷仍必须同签名(规范化规则不被本次修改改变)
        UmsCreativeSquare same = new UmsCreativeSquare();
        same.setContents("A");
        same.setPics("B");
        same.setFileUrl("\u0001C");
        assertEquals(sigA, ApiCreativeSquareController.publishSignature(same));
    }

    private void stubIdempotent(IUmsCreativeSquareService.IdempotentPublishResult.Kind kind, UmsCreativeSquare square) {
        when(squareService.insertUmsCreativeSquareIdempotent(any(), eq(RID), anyString()))
                .thenReturn(new IUmsCreativeSquareService.IdempotentPublishResult(kind, square));
    }

    private UmsCreativeSquare existing(Long id, int status) {
        UmsCreativeSquare square = new UmsCreativeSquare();
        square.setId(id);
        square.setMemberId(ME);
        square.setContents("正文");
        square.setStatus(status);
        return square;
    }
}

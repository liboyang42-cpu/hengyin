package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.dto.ai.AiClubDesignReq;
import com.chengyinhub.business.domain.dto.ai.AiMerchantPlanReq;
import com.chengyinhub.business.domain.dto.ai.AiMerchantTaskSuggestReq;
import com.chengyinhub.business.domain.dto.ai.AiNodeGenReq;
import com.chengyinhub.business.domain.dto.ai.AiSafetyPrecheckReq;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftPrepareReq;
import com.chengyinhub.business.domain.vo.ai.AiClubDesignResp;
import com.chengyinhub.business.domain.vo.ai.AiMerchantPlanResp;
import com.chengyinhub.business.domain.vo.ai.AiMerchantTaskSuggestResp;
import com.chengyinhub.business.domain.vo.ai.AiNodeGenResp;
import com.chengyinhub.business.domain.vo.ai.AiSafetyPrecheckResp;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentView;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentState;
import com.chengyinhub.business.service.impl.AiThemeDraftRequestService;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftQuotaResp;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.common.core.domain.AjaxResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 创作助手控制器测试:6 接口 + 身份门禁 + 错误处理。
 */
class ApiAiControllerTest {

    private IAiCreatorService aiCreatorService;
    private AiThemeDraftRequestService themeDraftRequests;
    private TestableApiAiController controller;

    private static final Long CLUB_MEMBER_ID = 1001L;
    private static final Long PLAYER_MEMBER_ID = 2001L;

    @BeforeEach
    void setUp() {
        aiCreatorService = mock(IAiCreatorService.class);
        themeDraftRequests = mock(AiThemeDraftRequestService.class);
        when(aiCreatorService.resolveRoleFor(CLUB_MEMBER_ID)).thenReturn("club");
        when(aiCreatorService.resolveRoleFor(PLAYER_MEMBER_ID)).thenReturn("player");
    }

    private TestableApiAiController clubController() {
        TestableApiAiController c = new TestableApiAiController(CLUB_MEMBER_ID);
        ReflectionTestUtils.setField(c, "aiCreatorService", aiCreatorService);
        ReflectionTestUtils.setField(c, "themeDraftRequests", themeDraftRequests);
        return c;
    }

    private TestableApiAiController playerController() {
        TestableApiAiController c = new TestableApiAiController(PLAYER_MEMBER_ID);
        ReflectionTestUtils.setField(c, "aiCreatorService", aiCreatorService);
        ReflectionTestUtils.setField(c, "themeDraftRequests", themeDraftRequests);
        return c;
    }

    private TestableApiAiController nullMemberController() {
        TestableApiAiController c = new TestableApiAiController(null);
        ReflectionTestUtils.setField(c, "aiCreatorService", aiCreatorService);
        ReflectionTestUtils.setField(c, "themeDraftRequests", themeDraftRequests);
        return c;
    }

    // ---- merchantPlan ----

    @Test
    void merchantPlanReturnsSuccessForClub() {
        AiMerchantPlanResp mockResp = new AiMerchantPlanResp();
        mockResp.setTraceId("trace-1");
        when(aiCreatorService.merchantPlan(any(AiMerchantPlanReq.class), eq(CLUB_MEMBER_ID)))
                .thenReturn(mockResp);

        AjaxResult result = clubController().merchantPlan(new AiMerchantPlanReq());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(aiCreatorService).merchantPlan(any(AiMerchantPlanReq.class), eq(CLUB_MEMBER_ID));
    }

    @Test
    void merchantPlanBlocksPlayer() {
        AjaxResult result = playerController().merchantPlan(new AiMerchantPlanReq());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void merchantPlanReturnsLoginErrorForNullMemberId() {
        AjaxResult result = nullMemberController().merchantPlan(new AiMerchantPlanReq());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void merchantPlanReturnsErrorOnAiServiceException() {
        when(aiCreatorService.merchantPlan(any(AiMerchantPlanReq.class), eq(CLUB_MEMBER_ID)))
                .thenThrow(new AiServiceException("今日AI次数已用完"));

        AjaxResult result = clubController().merchantPlan(new AiMerchantPlanReq());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("今日AI次数已用完", result.get(AjaxResult.MSG_TAG));
    }

    // ---- themeDraft ----

    @Test
    void themeDraftReturnsSuccessForClub() {
        AiThemeDraftIntentView prepared = new AiThemeDraftIntentView("td2_0123456789abcdef0123456789abcdef",
                AiThemeDraftIntentState.PREPARED, false, true, false, null, null, null, null, 1);
        AiThemeDraftPrepareReq input = new AiThemeDraftPrepareReq("7c9e6679-7425-40de-944b-e07fc1f90ae7", "club", 1, null);
        when(themeDraftRequests.prepare(CLUB_MEMBER_ID, input)).thenReturn(prepared);
        AjaxResult result = clubController().themeDraftPrepare(input);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(prepared, result.get(AjaxResult.DATA_TAG));
    }

    @Test
    void themeDraftAllowsPlayerForSimpleCreation() {
        AiThemeDraftIntentView prepared = new AiThemeDraftIntentView("td2_0123456789abcdef0123456789abcdef",
                AiThemeDraftIntentState.PREPARED, false, true, false, null, null, null, null, 1);
        AiThemeDraftPrepareReq input = new AiThemeDraftPrepareReq("7c9e6679-7425-40de-944b-e07fc1f90ae7", "player", 1, null);
        when(themeDraftRequests.prepare(PLAYER_MEMBER_ID, input)).thenReturn(prepared);
        AjaxResult result = playerController().themeDraftPrepare(input);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(themeDraftRequests).prepare(PLAYER_MEMBER_ID, input);
    }

    @Test
    void themeDraftQuotaAllowsPlayer() {
        AiThemeDraftQuotaResp mockResp = new AiThemeDraftQuotaResp();
        mockResp.setLimited(true);
        mockResp.setLimit(3);
        mockResp.setRemaining(2);
        when(aiCreatorService.themeDraftQuota(PLAYER_MEMBER_ID)).thenReturn(mockResp);

        AjaxResult result = playerController().themeDraftQuota();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(aiCreatorService).themeDraftQuota(PLAYER_MEMBER_ID);
    }

    // ---- nodeGenerate ----

    @Test
    void nodeGenerateReturnsSuccessForClub() {
        AiNodeGenResp mockResp = new AiNodeGenResp();
        mockResp.setTraceId("trace-ng");
        when(aiCreatorService.nodeGenerate(any(AiNodeGenReq.class), eq(CLUB_MEMBER_ID)))
                .thenReturn(mockResp);

        AjaxResult result = clubController().nodeGenerate(new AiNodeGenReq());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void nodeGenerateBlocksPlayer() {
        AjaxResult result = playerController().nodeGenerate(new AiNodeGenReq());
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
    }

    // ---- safetyPrecheck ----

    @Test
    void safetyPrecheckReturnsSuccessForClub() {
        AiSafetyPrecheckResp mockResp = new AiSafetyPrecheckResp();
        mockResp.setTraceId("trace-sp");
        mockResp.setPass(true);
        when(aiCreatorService.safetyPrecheck(any(AiSafetyPrecheckReq.class), eq(CLUB_MEMBER_ID)))
                .thenReturn(mockResp);

        AjaxResult result = clubController().safetyPrecheck(new AiSafetyPrecheckReq());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void safetyPrecheckBlocksPlayer() {
        AjaxResult result = playerController().safetyPrecheck(new AiSafetyPrecheckReq());
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
    }

    // ---- merchantTaskSuggest ----

    @Test
    void merchantTaskSuggestReturnsSuccessForClub() {
        AiMerchantTaskSuggestResp mockResp = new AiMerchantTaskSuggestResp();
        mockResp.setTraceId("trace-mts");
        when(aiCreatorService.merchantTaskSuggest(any(AiMerchantTaskSuggestReq.class), eq(CLUB_MEMBER_ID)))
                .thenReturn(mockResp);

        AjaxResult result = clubController().merchantTaskSuggest(new AiMerchantTaskSuggestReq());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void merchantTaskSuggestBlocksPlayer() {
        AjaxResult result = playerController().merchantTaskSuggest(new AiMerchantTaskSuggestReq());
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
    }

    // ---- clubDesign ----

    @Test
    void clubDesignReturnsSuccessForClub() {
        AiClubDesignResp mockResp = new AiClubDesignResp();
        mockResp.setTraceId("trace-cd");
        when(aiCreatorService.clubDesign(any(AiClubDesignReq.class), eq(CLUB_MEMBER_ID)))
                .thenReturn(mockResp);

        AjaxResult result = clubController().clubDesign(new AiClubDesignReq());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void clubDesignBlocksPlayer() {
        AjaxResult result = playerController().clubDesign(new AiClubDesignReq());
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
    }

    // ---- Exception handling ----

    @Test
    void aiServiceExceptionReturnsErrorMessage() {
        when(aiCreatorService.clubDesign(any(AiClubDesignReq.class), eq(CLUB_MEMBER_ID)))
                .thenThrow(new AiServiceException("生成内容未通过安全检测,请调整后重试"));

        AjaxResult result = clubController().clubDesign(new AiClubDesignReq());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertNotNull(result.get(AjaxResult.MSG_TAG));
    }

    // 2026-08-10:用户报"AI 不响应"而生产日志零痕迹。gateRole 原先把**任何**后端异常
    // 都 catch 成 PLAYER_BLOCKED("当前身份暂不支持AI创作")并且不写日志 —— 服务端故障
    // 被伪装成用户身份问题,排查时既看不到异常也无从分辨。这两条锁住"别再说谎"。
    @Test
    void roleResolveFailureIsReportedAsServiceFailureNotIdentityProblem() {
        when(aiCreatorService.resolveRoleFor(CLUB_MEMBER_ID))
                .thenThrow(new RuntimeException("db down"));

        AjaxResult result = clubController().merchantPlan(new AiMerchantPlanReq());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        String msg = String.valueOf(result.get(AjaxResult.MSG_TAG));
        assertEquals("AI 服务暂时不可用,请稍后重试", msg,
                "角色解析炸了是服务端故障,不能对用户谎称是身份不对——那会让用户去切身份,而问题根本不在那");
    }

    @Test
    void genuinePlayerStillGetsTheIdentityMessage() {
        // 负控:真的是玩家身份时,文案不能被上面那条改动带跑
        AjaxResult result = playerController().merchantPlan(new AiMerchantPlanReq());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("当前身份暂不支持AI创作,请切换到俱乐部或商家身份",
                String.valueOf(result.get(AjaxResult.MSG_TAG)));
    }

    // ---- merchantInsight(商家成员 + MARKETING_READ 门禁) ----

    private static final Long MERCHANT_MEMBER_ID = 3001L;
    private static final Long OPERATOR_MEMBER_ID = 3002L;

    @Test
    void merchantInsightSucceedsForMerchantWithLoginMemberId() {
        com.chengyinhub.business.service.IMerchantInsightService insightService =
                mock(com.chengyinhub.business.service.IMerchantInsightService.class);
        when(insightService.insight(MERCHANT_MEMBER_ID))
                .thenReturn(new com.chengyinhub.business.domain.vo.ai.MerchantInsightResp());
        TestableApiAiController c = new TestableApiAiController(MERCHANT_MEMBER_ID);
        ReflectionTestUtils.setField(c, "aiCreatorService", aiCreatorService);
        ReflectionTestUtils.setField(c, "themeDraftRequests", themeDraftRequests);
        ReflectionTestUtils.setField(c, "merchantInsightService", insightService);
        ReflectionTestUtils.setField(c, "merchantAccessService",
                merchantAccess(MERCHANT_MEMBER_ID, MERCHANT_MEMBER_ID,
                        com.chengyinhub.business.access.merchant.MerchantRoleCode.MERCHANT_OWNER));

        AjaxResult result = c.merchantInsight();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        // 接口本身无参数,memberId 只能来自登录态 —— 店主就是商家主体
        verify(insightService).insight(MERCHANT_MEMBER_ID);
    }

    @Test
    void merchantInsightAllowsOperatorWithMarketingReadScopedToOwner() {
        com.chengyinhub.business.service.IMerchantInsightService insightService =
                mock(com.chengyinhub.business.service.IMerchantInsightService.class);
        when(insightService.insight(MERCHANT_MEMBER_ID))
                .thenReturn(new com.chengyinhub.business.domain.vo.ai.MerchantInsightResp());
        TestableApiAiController c = new TestableApiAiController(OPERATOR_MEMBER_ID);
        ReflectionTestUtils.setField(c, "merchantInsightService", insightService);
        ReflectionTestUtils.setField(c, "merchantAccessService",
                merchantAccess(OPERATOR_MEMBER_ID, MERCHANT_MEMBER_ID,
                        com.chengyinhub.business.access.merchant.MerchantRoleCode.MERCHANT_MARKETING));

        AjaxResult result = c.merchantInsight();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(insightService).insight(MERCHANT_MEMBER_ID);
    }

    @Test
    void merchantInsightBlocksOperatorWithoutMarketingRead() {
        com.chengyinhub.business.service.IMerchantInsightService insightService =
                mock(com.chengyinhub.business.service.IMerchantInsightService.class);
        TestableApiAiController c = new TestableApiAiController(OPERATOR_MEMBER_ID);
        ReflectionTestUtils.setField(c, "merchantInsightService", insightService);
        ReflectionTestUtils.setField(c, "merchantAccessService",
                merchantAccess(OPERATOR_MEMBER_ID, MERCHANT_MEMBER_ID,
                        com.chengyinhub.business.access.merchant.MerchantRoleCode.MERCHANT_CHECKIN));

        AjaxResult result = c.merchantInsight();

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("店铺参谋仅商家身份可用", String.valueOf(result.get(AjaxResult.MSG_TAG)));
        verify(insightService, org.mockito.Mockito.never()).insight(any());
    }

    private com.chengyinhub.business.access.merchant.IMerchantAccessService merchantAccess(
            Long principal, Long owner, String roleCode) {
        com.chengyinhub.business.access.merchant.IMerchantAccessService accessService =
                mock(com.chengyinhub.business.access.merchant.IMerchantAccessService.class);
        com.chengyinhub.business.access.merchant.MerchantAccessContext context =
                new com.chengyinhub.business.access.merchant.MerchantAccessContext(
                        principal, 11L, owner, roleCode);
        when(accessService.require(org.mockito.ArgumentMatchers.eq(principal),
                org.mockito.ArgumentMatchers.eq(
                        com.chengyinhub.business.access.merchant.MerchantPermission.MARKETING_READ))).thenAnswer(
                invocation -> {
                    if (!com.chengyinhub.business.access.merchant.MerchantPermissionPolicy
                            .hasPermission(roleCode,
                                    com.chengyinhub.business.access.merchant.MerchantPermission.MARKETING_READ)) {
                        throw new com.chengyinhub.common.exception.ServiceException(
                                "商家身份无效或权限不足", com.chengyinhub.common.constant.HttpStatus.FORBIDDEN);
                    }
                    return context;
                });
        return accessService;
    }

    @Test
    void merchantInsightBlocksPlayer() {
        com.chengyinhub.business.service.IMerchantInsightService insightService =
                mock(com.chengyinhub.business.service.IMerchantInsightService.class);
        TestableApiAiController c = playerController();
        ReflectionTestUtils.setField(c, "merchantInsightService", insightService);
        ReflectionTestUtils.setField(c, "merchantAccessService", noMerchantAccess());

        AjaxResult result = c.merchantInsight();

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(insightService, org.mockito.Mockito.never()).insight(any());
    }

    @Test
    void merchantInsightBlocksClubToo() {
        // 店铺参谋看的是本商家经营数据,club 也不放行 —— 这是它与 gateRole(club/merchant 都放)的差别
        com.chengyinhub.business.service.IMerchantInsightService insightService =
                mock(com.chengyinhub.business.service.IMerchantInsightService.class);
        TestableApiAiController c = clubController();
        ReflectionTestUtils.setField(c, "merchantInsightService", insightService);
        ReflectionTestUtils.setField(c, "merchantAccessService", noMerchantAccess());

        AjaxResult result = c.merchantInsight();

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("店铺参谋仅商家身份可用", String.valueOf(result.get(AjaxResult.MSG_TAG)));
        verify(insightService, org.mockito.Mockito.never()).insight(any());
    }

    private com.chengyinhub.business.access.merchant.IMerchantAccessService noMerchantAccess() {
        com.chengyinhub.business.access.merchant.IMerchantAccessService accessService =
                mock(com.chengyinhub.business.access.merchant.IMerchantAccessService.class);
        when(accessService.require(any(), any())).thenThrow(
                new com.chengyinhub.common.exception.ServiceException(
                        "商家身份无效或权限不足", com.chengyinhub.common.constant.HttpStatus.FORBIDDEN));
        return accessService;
    }

    // ---- Testable controller ----

    private static class TestableApiAiController extends ApiAiController {
        private final Long appUserId;

        TestableApiAiController(Long appUserId) {
            this.appUserId = appUserId;
        }

        @Override
        public Long getAppUserId() {
            return appUserId;
        }
    }
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IClubEventOpsService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.ITeamGroupService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.support.ActivityReviewPolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ActivityPublishRoleGateTest
{
    private static final Long MEMBER_ID = 101L;

    /** 商家主体(mms_merchant.member_id)。店员登录时操作人 = MEMBER_ID,主体 = 77。 */
    private static final Long MERCHANT_OWNER_ID = 77L;

    private TestableApiActivityController controller;
    private IUmsMemberService umsMemberService;
    private IRoleService roleService;
    private IQuotaService quotaService;
    private ICmsActivityService cmsActivityService;
    private ITeamGroupService teamGroupService;
    private IWxContentSecurityService wxContentSecurityService;
    private IMerchantAccessService merchantAccessService;
    private UmsMember member;

    @BeforeEach
    void setUp()
    {
        controller = new TestableApiActivityController(MEMBER_ID);
        umsMemberService = mock(IUmsMemberService.class);
        roleService = mock(IRoleService.class);
        quotaService = mock(IQuotaService.class);
        cmsActivityService = mock(ICmsActivityService.class);
        teamGroupService = mock(ITeamGroupService.class);
        wxContentSecurityService = mock(IWxContentSecurityService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        member = new UmsMember();
        member.setId(MEMBER_ID);
        when(umsMemberService.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        ReflectionTestUtils.setField(controller, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(controller, "roleService", roleService);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", cmsActivityService);
        ReflectionTestUtils.setField(controller, "teamGroupService", teamGroupService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", wxContentSecurityService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        // 分级审核 P0:发布链路改走统一门禁;本类只测身份/归属,门禁一律放行,模式按默认(先发后审)
        IContentSafetyGate contentSafetyGate = mock(IContentSafetyGate.class);
        when(contentSafetyGate.decide(any())).thenReturn(SafetyDecision.pass());
        ActivityReviewPolicy activityReviewPolicy = new ActivityReviewPolicy();
        ReflectionTestUtils.setField(activityReviewPolicy, "configService", mock(ISysConfigService.class));
        ReflectionTestUtils.setField(controller, "contentSafetyGate", contentSafetyGate);
        ReflectionTestUtils.setField(controller, "activityReviewPolicy", activityReviewPolicy);
        ReflectionTestUtils.setField(controller, "auditTaskService", mock(IAuditTaskService.class));
    }

    /** 让配额闸的「锁+复查+写」在本测试里等价于直接执行写动作。 */
    private void passCreateGuardThrough()
    {
        when(quotaService.runWithCreateGuard(anyLong(), anyString(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
    }

    /** 店员持项目管理权:主体解析成店主 memberId。 */
    private void grantMerchantProjectManage()
    {
        when(merchantAccessService.require(MEMBER_ID, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(MEMBER_ID, 5L, MERCHANT_OWNER_ID, "MERCHANT_MANAGER"));
    }

    private CmsActivityPublishRequest merchantRequest()
    {
        CmsActivityPublishRequest request = new CmsActivityPublishRequest();
        request.setScope("MERCHANT");
        request.setName("门店开放日");
        return request;
    }

    private CmsActivityPublishRequest merchantRequestWithPrice(String price)
    {
        CmsActivityPublishRequest request = merchantRequest();
        ActivityTicketRequest ticket = new ActivityTicketRequest();
        ticket.setName("入场票");
        ticket.setPrice(new BigDecimal(price));
        request.setTickets(Collections.singletonList(ticket));
        return request;
    }

    @Test
    void playerCannotPublishActivity()
    {
        when(roleService.resolveRole(member)).thenReturn(IRoleService.ROLE_PLAYER);

        AjaxResult result = controller.publish(new CmsActivityPublishRequest());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("仅俱乐部主理人或商家可发布活动", result.get(AjaxResult.MSG_TAG));
        verify(quotaService, never()).checkCreate(MEMBER_ID, IQuotaService.TYPE_EVENT);
    }

    @Test
    void clubReachesExistingQuotaGate()
    {
        when(roleService.resolveRole(member)).thenReturn(IRoleService.ROLE_CLUB);
        when(quotaService.checkCreate(MEMBER_ID, IQuotaService.TYPE_EVENT)).thenReturn("活动配额已满");

        AjaxResult result = controller.publish(new CmsActivityPublishRequest());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("活动配额已满", result.get(AjaxResult.MSG_TAG));
        verify(quotaService).checkCreate(MEMBER_ID, IQuotaService.TYPE_EVENT);
    }

    @Test
    void playerCannotRepublishExistingActivity()
    {
        when(roleService.resolveRole(member)).thenReturn(IRoleService.ROLE_PLAYER);

        AjaxResult result = controller.activityUpdatePublishStatus("9", null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("仅俱乐部主理人或商家可上下架活动", result.get(AjaxResult.MSG_TAG));
        verify(cmsActivityService, never()).selectCmsActivityById(9L);
    }

    @Test
    void playerCannotDeleteExistingActivity()
    {
        when(roleService.resolveRole(member)).thenReturn(IRoleService.ROLE_PLAYER);

        AjaxResult result = controller.activityDelete("9", null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("仅俱乐部主理人或商家可删除活动", result.get(AjaxResult.MSG_TAG));
        verify(cmsActivityService, never()).selectCmsActivityById(9L);
    }

    @Test
    void playerCannotCancelExistingActivity()
    {
        when(roleService.resolveRole(member)).thenReturn(IRoleService.ROLE_PLAYER);

        AjaxResult result = controller.activityCancel("9", "天气原因", null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("仅俱乐部主理人可取消活动", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(teamGroupService);
    }

    /**
     * [6-17] 取消活动要跟 /event-ops/cancel 同一套收尾,不能再直接调退款核心了事。
     *
     * <p>[FIX-WEATHER] 签名是 (actorMemberId, activityId, reason)。6-17 原测试照着控制器写成了
     * (9L, MEMBER_ID) —— 和控制器一起传反,互相作证、恒绿,生产上俱乐部取消实际拿活动 id 当操作人。
     */
    @Test
    void clubCancelGoesThroughClubEventWrapUpNotRawRefundCore()
    {
        when(roleService.resolveRole(member)).thenReturn(IRoleService.ROLE_CLUB);
        IClubEventOpsService clubEventOpsService = mock(IClubEventOpsService.class);
        when(clubEventOpsService.cancelActivityByOwnerWithClubWrapUp(MEMBER_ID, 9L, "天气原因"))
                .thenReturn(new ActivityCancelResult(1, 0, 0));
        ReflectionTestUtils.setField(controller, "clubEventOpsService", clubEventOpsService);

        AjaxResult result = controller.activityCancel("9", "天气原因", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(clubEventOpsService).cancelActivityByOwnerWithClubWrapUp(MEMBER_ID, 9L, "天气原因");
        verifyNoInteractions(teamGroupService);
    }

    /**
     * [FIX-WEATHER] 跨层钉死参数顺序:控制器 → 真实 ClubEventOpsServiceImpl → 退款核心。
     * 只 mock 最下游,断言退款核心收到的是 (活动id=9, 会员=MEMBER_ID)。
     * 6-17 把两个参数传反时,这里退款核心会收到 (101, 9) —— 即「拿会员号当活动 id 去取消」的越权面。
     */
    @Test
    void clubCancelReachesRefundCoreWithActivityIdAndMemberInRealOrder()
    {
        when(roleService.resolveRole(member)).thenReturn(IRoleService.ROLE_CLUB);
        ITeamGroupService refundCore = mock(ITeamGroupService.class);
        CmsActivity original = new CmsActivity();
        original.setId(9L);
        original.setMemberId(MEMBER_ID);
        original.setDelFlag(0);
        original.setPublishStatus(1L);
        var activities = mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        when(activities.selectCmsActivityById(9L)).thenReturn(original);
        when(activities.selectCmsActivityByIdForUpdate(9L)).thenReturn(original);
        var payments = com.chengyinhub.business.service.CancellationPhaseTestSupport.stubEmptyObservation(
                refundCore, new com.chengyinhub.business.domain.dto.RegistrationCancellationPayments.Scope(
                        2L, 9L, null, MEMBER_ID, null, Collections.emptyList()));
        when(refundCore.cancelActivityWithObservedPayments(9L, MEMBER_ID, MEMBER_ID,
                "天气原因", false, payments)).thenAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertTrue(
                    org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return new ActivityCancelResult(1, 0, 0);
        });
        com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl realWrapUp =
                new com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl(
                        mock(com.chengyinhub.business.access.club.IClubAccessService.class),
                        new com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer(),
                        mock(com.chengyinhub.business.service.IClubSessionService.class),
                        mock(com.chengyinhub.business.mapper.ClubEventOpsMapper.class),
                        mock(com.chengyinhub.business.mapper.ClubMemberMapper.class),
                        mock(com.chengyinhub.business.mapper.ClubLeadMapper.class),
                        mock(com.chengyinhub.business.service.IClubRoleAssignmentService.class),
                        refundCore);
        com.chengyinhub.business.service.CancellationPhaseTestSupport.wireTransactions(realWrapUp,
                com.chengyinhub.business.service.CancellationPhaseTestSupport.memoryTransactions());
        ReflectionTestUtils.setField(realWrapUp, "cancellationActivityMapper", activities);
        ReflectionTestUtils.setField(realWrapUp, "cancellationTopicMapper",
                mock(com.chengyinhub.business.mapper.CmsTopicMapper.class));
        com.chengyinhub.business.service.CancellationPhaseTestSupport.wireReceiptMocks(realWrapUp);
        ReflectionTestUtils.setField(controller, "clubEventOpsService", realWrapUp);

        AjaxResult result = controller.activityCancel("9", "天气原因", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(refundCore).cancelActivityWithObservedPayments(
                9L, MEMBER_ID, MEMBER_ID, "天气原因", false, payments);
        verify(refundCore, never()).cancelActivityWithObservedPayments(
                MEMBER_ID, 9L, MEMBER_ID, "天气原因", false, payments);
        org.mockito.InOrder phases = org.mockito.Mockito.inOrder(refundCore);
        phases.verify(refundCore).captureCancellationPayments(2L, 9L);
        phases.verify(refundCore).observeCancellationPayments(payments);
        phases.verify(refundCore).cancelActivityWithObservedPayments(
                9L, MEMBER_ID, MEMBER_ID, "天气原因", false, payments);
        verify(refundCore, never()).cancelActivityByOwner(anyLong(), anyLong(), anyString());
    }

    /** [C8-05] 商家主办:按商家权限解析店主,退款核心收到 (活动, 店主, 实际操作员工)。不看俱乐部角色。 */
    @Test
    void merchantScopeCancelResolvesOwnerAndKeepsStaffAsActor()
    {
        grantMerchantProjectManage();
        when(teamGroupService.cancelActivityByMerchantHost(9L, MERCHANT_OWNER_ID, MEMBER_ID, "暴雨预警"))
                .thenReturn(new ActivityCancelResult(2, 0, 0));

        AjaxResult result = controller.activityCancel("9", "暴雨预警", "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(teamGroupService).cancelActivityByMerchantHost(9L, MERCHANT_OWNER_ID, MEMBER_ID, "暴雨预警");
        verify(roleService, never()).resolveRole(member);
    }

    @Test
    void merchantScopeWithoutPermissionNeverTouchesRefundCore()
    {
        when(merchantAccessService.require(MEMBER_ID, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("当前岗位没有这个权限", 403));

        AjaxResult result = controller.activityCancel("9", "暴雨预警", "MERCHANT");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(teamGroupService);
    }

    // ==================== CU-M-05 商家主办发布链路 ====================

    /**
     * 商家店员发免费活动:落库主体必须是店主的 memberId,不是操作人的。
     * 撤掉「按主体归属」这条修复(改回传 memberId 操作人)后,这里 verify 的 77L 会变成 101L ⇒ 红。
     */
    @Test
    void merchantScopePublishAttributesActivityToMerchantOwnerNotActor()
    {
        grantMerchantProjectManage();
        passCreateGuardThrough();
        when(cmsActivityService.publishActivity(any(), eq(MERCHANT_OWNER_ID), anyString(), anyBoolean())).thenReturn(9L);

        AjaxResult result = controller.publish(merchantRequest());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(quotaService).checkCreate(MERCHANT_OWNER_ID, IQuotaService.TYPE_EVENT);
        verify(cmsActivityService).publishActivity(any(), eq(MERCHANT_OWNER_ID), anyString(), anyBoolean());
        verify(cmsActivityService, never()).publishActivity(any(), eq(MEMBER_ID), anyString(), anyBoolean());
        verify(roleService, never()).resolveRole(member);
    }

    /** 玩家/未入驻拿 MERCHANT scope 也发不出去:身份闸在商家域同样生效,且不落库。 */
    @Test
    void merchantScopeWithoutPermissionCannotPublish()
    {
        when(merchantAccessService.require(MEMBER_ID, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        AjaxResult result = controller.publish(merchantRequest());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("商家身份无效或权限不足", result.get(AjaxResult.MSG_TAG));
        verify(quotaService, never()).checkCreate(anyLong(), anyString());
        verify(cmsActivityService, never()).publishActivity(any(), anyLong(), anyString(), anyBoolean());
    }

    /**
     * 收款缺口(9-15 定稿「钱进商家子商户号」未落地,现码一律进平台商户号):
     * 商家主办只放开免费场,票价>0 直接拒并给明确文案,不是静默失败。
     */
    @Test
    void merchantScopePublishRejectsPaidTicketWithExplicitMessage()
    {
        grantMerchantProjectManage();

        AjaxResult result = controller.publish(merchantRequestWithPrice("69"));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("商家主办活动还没开通收款，票价只能是 0", result.get(AjaxResult.MSG_TAG));
        verify(cmsActivityService, never()).publishActivity(any(), anyLong(), anyString(), anyBoolean());
    }

    /** 负控的另一半:0 元票是「免费场」不是「没开通收款」,必须放行 —— 误把 >0 写成 >=0 会在这儿红。 */
    @Test
    void merchantScopePublishAllowsFreeTicket()
    {
        grantMerchantProjectManage();
        passCreateGuardThrough();
        when(cmsActivityService.publishActivity(any(), eq(MERCHANT_OWNER_ID), anyString(), anyBoolean())).thenReturn(9L);

        AjaxResult result = controller.publish(merchantRequestWithPrice("0"));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(cmsActivityService).publishActivity(any(), eq(MERCHANT_OWNER_ID), anyString(), anyBoolean());
    }

    /** 俱乐部主线不受本次改动影响:同样 69 元的票,主理人照样能发(收款进平台是既有口径)。 */
    @Test
    void clubScopePublishStillAllowsPaidTicket()
    {
        when(roleService.resolveRole(member)).thenReturn(IRoleService.ROLE_CLUB);
        passCreateGuardThrough();
        when(cmsActivityService.publishActivity(any(), eq(MEMBER_ID), anyString(), anyBoolean())).thenReturn(9L);

        CmsActivityPublishRequest request = new CmsActivityPublishRequest();
        request.setName("外滩夜行");
        ActivityTicketRequest ticket = new ActivityTicketRequest();
        ticket.setName("标准票");
        ticket.setPrice(new BigDecimal("69"));
        request.setTickets(Collections.singletonList(ticket));

        AjaxResult result = controller.publish(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(cmsActivityService).publishActivity(any(), eq(MEMBER_ID), anyString(), anyBoolean());
    }

    /** 两种身份不能混挂:商家 scope 带 club_id 就是「冒用俱乐部主办」,拒。 */
    @Test
    void merchantScopePublishRejectsClubBinding()
    {
        grantMerchantProjectManage();
        CmsActivityPublishRequest request = merchantRequest();
        request.setClubId(3L);

        AjaxResult result = controller.publish(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("商家主办活动不能挂到俱乐部", result.get(AjaxResult.MSG_TAG));
        verify(cmsActivityService, never()).publishActivity(any(), anyLong(), anyString(), anyBoolean());
    }

    /** 商家的活动商家能改:SQL 的 where 带 member_id,必须拿主体(77)去改,传操作人(101)一行都写不动。 */
    @Test
    void merchantScopeUpdateWritesWithMerchantOwner()
    {
        grantMerchantProjectManage();
        CmsActivity exist = new CmsActivity();
        exist.setId(9L);
        exist.setMemberId(MERCHANT_OWNER_ID);
        exist.setDelFlag(0);
        when(cmsActivityService.selectCmsActivityById(9L)).thenReturn(exist);
        when(cmsActivityService.updateActivityByOwner(any(), eq(MERCHANT_OWNER_ID), anyString(), anyBoolean())).thenReturn(1);

        CmsActivityPublishRequest request = merchantRequest();
        request.setId(9L);

        AjaxResult result = controller.update(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(cmsActivityService).updateActivityByOwner(any(), eq(MERCHANT_OWNER_ID), anyString(), anyBoolean());
        verify(cmsActivityService, never()).updateActivityByOwner(any(), eq(MEMBER_ID), anyString(), anyBoolean());
    }

    /** 归属仍要真校验:商家 scope 改别人的活动 = 越权,拒。 */
    @Test
    void merchantScopeUpdateCannotTouchAnotherOwnersActivity()
    {
        grantMerchantProjectManage();
        CmsActivity exist = new CmsActivity();
        exist.setId(9L);
        exist.setMemberId(88L);
        exist.setDelFlag(0);
        when(cmsActivityService.selectCmsActivityById(9L)).thenReturn(exist);

        CmsActivityPublishRequest request = merchantRequest();
        request.setId(9L);

        AjaxResult result = controller.update(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("无权操作该活动", result.get(AjaxResult.MSG_TAG));
        verify(cmsActivityService, never()).updateActivityByOwner(any(), anyLong(), anyString(), anyBoolean());
    }

    /** 承办场次(中标别人主题生成的那场)在商家域只读:改、上架、删都归主题主办方。 */
    @Test
    void merchantScopeCannotEditHostedRun()
    {
        grantMerchantProjectManage();
        when(cmsActivityService.selectCmsActivityById(9L)).thenReturn(hostedRun());

        CmsActivityPublishRequest request = merchantRequest();
        request.setId(9L);

        AjaxResult result = controller.update(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("这场是承办场次，需由主题主办方处理", result.get(AjaxResult.MSG_TAG));
        verify(cmsActivityService, never()).updateActivityByOwner(any(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void merchantScopeCannotToggleHostedRunOnline()
    {
        grantMerchantProjectManage();
        when(cmsActivityService.selectCmsActivityById(9L)).thenReturn(hostedRun());

        AjaxResult result = controller.activityUpdatePublishStatus("9", "MERCHANT");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("这场是承办场次，需由主题主办方处理", result.get(AjaxResult.MSG_TAG));
        verify(cmsActivityService, never()).updateCmsActivity(any());
    }

    @Test
    void merchantScopeCannotDeleteHostedRun()
    {
        grantMerchantProjectManage();
        when(cmsActivityService.selectCmsActivityById(9L)).thenReturn(hostedRun());

        AjaxResult result = controller.activityDelete("9", "MERCHANT");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("这场是承办场次，需由主题主办方处理", result.get(AjaxResult.MSG_TAG));
        verify(cmsActivityService, never()).updateCmsActivity(any());
    }

    @Test
    void merchantStaffCannotWriteOwnersClubActivityEvenWithoutClubIdInRequest()
    {
        grantMerchantProjectManage();
        CmsActivity exist = new CmsActivity();
        exist.setId(9L);
        exist.setMemberId(MERCHANT_OWNER_ID);
        exist.setClubId(7L);
        exist.setDelFlag(0);
        exist.setPublishStatus(0L);
        when(cmsActivityService.selectCmsActivityById(9L)).thenReturn(exist);
        CmsActivityPublishRequest request = merchantRequest();
        request.setId(9L);
        // 请求不带 clubId、存量活动无 topicId:不能把店主的俱乐部活动当成门店项目。
        AjaxResult[] results = {
                controller.update(request),
                controller.activityUpdatePublishStatus("9", "MERCHANT"),
                controller.activityDelete("9", "MERCHANT")
        };
        for (AjaxResult result : results) {
            assertEquals(500, result.get(AjaxResult.CODE_TAG));
            assertEquals("俱乐部活动需由俱乐部主理人处理", result.get(AjaxResult.MSG_TAG));
        }
        verify(cmsActivityService, never()).updateActivityByOwner(any(), anyLong(), anyString(), anyBoolean());
        verify(cmsActivityService, never()).updateCmsActivity(any());
    }

    /** 商家自办活动(无 topic_id)店员能上下架 —— 撤掉主体归属改造后这里会退回「仅俱乐部主理人可上下架活动」。 */
    @Test
    void merchantScopeCanToggleOwnHostedActivity()
    {
        grantMerchantProjectManage();
        CmsActivity exist = new CmsActivity();
        exist.setId(9L);
        exist.setMemberId(MERCHANT_OWNER_ID);
        exist.setDelFlag(0);
        exist.setPublishStatus(0L);
        when(cmsActivityService.selectCmsActivityById(9L)).thenReturn(exist);
        when(cmsActivityService.updateCmsActivity(any())).thenReturn(1);

        AjaxResult result = controller.activityUpdatePublishStatus("9", "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("上架成功", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void merchantStaffDeletePreservesMerchantOwner()
    {
        grantMerchantProjectManage();
        CmsActivity exist = new CmsActivity();
        exist.setId(9L);
        exist.setMemberId(MERCHANT_OWNER_ID);
        exist.setDelFlag(0);
        when(cmsActivityService.selectCmsActivityById(9L)).thenReturn(exist);
        when(cmsActivityService.updateCmsActivity(any())).thenReturn(1);

        assertEquals(200, controller.activityDelete("9", "MERCHANT").get(AjaxResult.CODE_TAG));

        org.mockito.ArgumentCaptor<CmsActivity> deleted = org.mockito.ArgumentCaptor.forClass(CmsActivity.class);
        verify(cmsActivityService).updateCmsActivity(deleted.capture());
        assertEquals(MERCHANT_OWNER_ID, deleted.getValue().getMemberId());
        assertEquals(2, deleted.getValue().getDelFlag());
    }

    /** scope 传脏值不能静默落到某个身份 —— 判据在 MerchantOperationScope,控制器必须把它透出去。 */
    @Test
    void unknownScopeIsRejectedNotSilentlyDowngraded()
    {
        CmsActivityPublishRequest request = merchantRequest();
        request.setScope("MERHANT");

        AjaxResult result = controller.publish(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("业务作用域参数不合法", result.get(AjaxResult.MSG_TAG));
        verify(cmsActivityService, never()).publishActivity(any(), anyLong(), anyString(), anyBoolean());
    }

    private CmsActivity hostedRun()
    {
        CmsActivity exist = new CmsActivity();
        exist.setId(9L);
        exist.setMemberId(MERCHANT_OWNER_ID);
        exist.setTopicId(21L);
        exist.setDelFlag(0);
        exist.setPublishStatus(0L);
        return exist;
    }

    private static class TestableApiActivityController extends ApiActivityController
    {
        private final Long memberId;

        private TestableApiActivityController(Long memberId)
        {
            this.memberId = memberId;
        }

        @Override
        public Long getAppUserId()
        {
            return memberId;
        }

        /** create_by/update_by 是别名字符串,单测没有 SecurityContext。 */
        @Override
        public String getAppUserName()
        {
            return "阿测";
        }
    }
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubEventLeadResolver;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeadState;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.service.IAccountDeregistrationService;
import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.business.service.IClubEventOpsService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IRegistrationCheckoutService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.ITeamGroupService;
import com.chengyinhub.business.service.IUmsMemberCartService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IUserInvoiceService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.RegistrationRequestConflictException;
import com.chengyinhub.business.service.invoice.UserInvoiceException;
import com.chengyinhub.business.service.compliance.ComplianceFeatureSwitches;
import com.chengyinhub.business.service.support.RegistrationCancellationReadService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * SMOKE-500-R4（worker A）：TEST 冒烟里这些业务拒绝曾以 code=500 返回。
 * 文案保持不变，只把业务码改成 4xx：找不到 404、无权 403、参数 400、状态冲突 409。
 */
class SmokeR4BusinessRefusalCodeTest {

    private static final Long ME = 9003L;

    private static void assertRefusal(AjaxResult r, int code, String msg) {
        assertEquals(Integer.valueOf(code), r.get(AjaxResult.CODE_TAG));
        assertEquals(msg, r.get(AjaxResult.MSG_TAG));
    }

    // ── ApiClubController ──────────────────────────────────────────────

    private ApiClubController club(IClubService clubs, IClubMemberService members) {
        ApiClubController c = spy(new ApiClubController());
        doReturn(ME).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "clubService", clubs);
        ReflectionTestUtils.setField(c, "clubMemberService", members);
        return c;
    }

    @Test
    void clubRefusalsUse4xx() {
        IClubService clubs = mock(IClubService.class);
        IClubMemberService members = mock(IClubMemberService.class);
        ApiClubController c = club(clubs, members);

        com.chengyinhub.business.domain.ClubMember missingClub = new com.chengyinhub.business.domain.ClubMember();
        missingClub.setClubId(7007L);
        missingClub.setMemberId(9009L);
        assertRefusal(c.removeMember(missingClub), HttpStatus.NOT_FOUND, "俱乐部不存在");

        com.chengyinhub.business.domain.ClubMember review = new com.chengyinhub.business.domain.ClubMember();
        review.setClubId(7001L);
        review.setMemberId(9009L);
        doThrow(new IllegalArgumentException("入会申请不存在或已处理")).when(members)
                .reviewJoinRequest(anyLong(), anyLong(), anyLong(), anyBoolean(), anyString());
        assertRefusal(c.approveJoinRequest(review), HttpStatus.BAD_REQUEST, "入会申请不存在或已处理");
        assertRefusal(c.rejectJoinRequest(review), HttpStatus.BAD_REQUEST, "入会申请不存在或已处理");

        Club owned = new Club();
        owned.setId(7003L);
        owned.setMemberId(ME);
        when(clubs.selectClubById(7003L)).thenReturn(owned);
        assertRefusal(c.quit(owned), HttpStatus.CONFLICT, "主理人不能直接退出；请先在成员治理中转让主理人，或解散俱乐部");

        ReflectionTestUtils.setField(c, "growthBadgeMapper", mock(GrowthBadgeMapper.class));
        Map<String, Object> grant = new HashMap<>();
        grant.put("clubId", 7003L);
        grant.put("memberId", 9004L);
        grant.put("badgeCode", "FIRST_STEP");
        assertRefusal(c.badgeGrant(grant), HttpStatus.NOT_FOUND, "勋章不存在或不属于本俱乐部");
    }

    // ── ApiClubLeadController ──────────────────────────────────────────

    @Test
    void releasingAMemberOutsideTheRunIs404() {
        ApiClubLeadController c = spy(new ApiClubLeadController());
        doReturn(ME).when(c).getAppUserId();
        ICmsActivityService activities = mock(ICmsActivityService.class);
        IClubService clubs = mock(IClubService.class);
        ClubLeadMapper leads = mock(ClubLeadMapper.class);
        ReflectionTestUtils.setField(c, "cmsActivityService", activities);
        ReflectionTestUtils.setField(c, "clubLeadMapper", leads);
        ReflectionTestUtils.setField(c, "advancedGameRuntimeService", mock(IAdvancedGameRuntimeService.class));
        ReflectionTestUtils.setField(c, "clubEventLeadResolver",
                new ClubEventLeadResolver(mock(ClubRoleAssignmentMapper.class), clubs));
        CmsActivity act = new CmsActivity();
        act.setId(992032L);
        act.setClubId(7001L);
        when(activities.selectCmsActivityById(992032L)).thenReturn(act);
        Club owner = new Club();
        owner.setId(7001L);
        owner.setMemberId(ME);
        when(clubs.selectClubById(7001L)).thenReturn(owner);
        ClubLeadState st = new ClubLeadState();
        st.setActivityId(992032L);
        st.setLeaderMemberId(ME);
        st.setStatus(2);
        when(leads.selectByActivity(992032L)).thenReturn(st);

        assertRefusal(c.releaseStation(992032L, ME, 992021L), HttpStatus.NOT_FOUND, "该成员不在本场");
    }

    // ── ApiCoopController ──────────────────────────────────────────────

    @Test
    void coopRefusalsUse4xx() {
        ApiCoopController c = spy(new ApiCoopController());
        doReturn(ME).when(c).getAppUserId();
        ICoopInviteService invites = mock(ICoopInviteService.class);
        ReflectionTestUtils.setField(c, "coopInviteService", invites);

        CoopInvite mismatch = new CoopInvite();
        mismatch.setInviteType(1);
        mismatch.setToType("smoke");
        mismatch.setToId(1L);
        assertRefusal(c.invite(mismatch), HttpStatus.BAD_REQUEST, "邀请类型与受邀方类型不匹配");

        assertRefusal(c.reviewSummary(Map.of("toId", "smoke")), HttpStatus.BAD_REQUEST, "缺少被评人");

    }

    // ── ApiOfficialEventController ─────────────────────────────────────

    @Test
    void publishCheckWithoutReadAccessIs403() {
        ApiOfficialEventController c = spy(new ApiOfficialEventController());
        doReturn(ME).when(c).getAppUserId();
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey("official_publish_whitelist")).thenReturn(String.valueOf(ME));
        IOfficialEventV2Service v2 = mock(IOfficialEventV2Service.class);
        when(v2.canReadControlledEvent(1L, ME)).thenReturn(false);
        ReflectionTestUtils.setField(c, "configService", config);
        ReflectionTestUtils.setField(c, "officialEventV2Service", v2);

        assertRefusal(c.v2PublishCheck(1L), HttpStatus.FORBIDDEN, "无权查看该活动发布检查");
    }

    // ── ApiMarketingProductController ──────────────────────────────────

    @Test
    void alreadyEffectiveProductActivationIs409() {
        ApiMarketingProductController c = new ApiMarketingProductController(null, null, null);
        assertRefusal(c.handleRequestConflict(new RegistrationRequestConflictException("该主题不是首次使用型,已在成交时生效")),
                HttpStatus.CONFLICT, "该主题不是首次使用型,已在成交时生效");
    }

    // ── ApiAccountDeregistrationController ─────────────────────────────

    @Test
    void cancellingWithoutPendingDeregistrationIs409() {
        ApiAccountDeregistrationController c = spy(new ApiAccountDeregistrationController());
        doReturn(ME).when(c).getAppUserId();
        IAccountDeregistrationService service = mock(IAccountDeregistrationService.class);
        when(service.cancel(ME)).thenThrow(new IllegalStateException("当前没有可撤销的注销申请"));
        ReflectionTestUtils.setField(c, "deregistrationService", service);

        assertRefusal(c.cancel(), HttpStatus.CONFLICT, "当前没有可撤销的注销申请");
    }

    // ── ApiCartController ──────────────────────────────────────────────

    @Test
    void missingCartRowsAre404() {
        ApiCartController c = spy(new ApiCartController());
        doReturn(ME).when(c).getAppUserId();
        IUmsMemberCartService carts = mock(IUmsMemberCartService.class);
        when(carts.updateCart(ME, "9925568", "1", "992551"))
                .thenThrow(new ServiceException("购物车信息不存在", HttpStatus.NOT_FOUND));
        when(carts.previewSettlement(ME, "9925568"))
                .thenThrow(new ServiceException("购物车数据异常", HttpStatus.NOT_FOUND));
        ReflectionTestUtils.setField(c, "umsMemberCartService", carts);

        assertRefusal(c.cartUpdate("9925568", "1", "992551"), HttpStatus.NOT_FOUND, "购物车信息不存在");
        assertRefusal(c.cartSettlement("9925568"), HttpStatus.NOT_FOUND, "购物车数据异常");
    }

    // ── ApiActivityController ──────────────────────────────────────────

    @Test
    void cancelLifecycleConflictKeeps409() {
        ApiActivityController c = spy(new ApiActivityController());
        doReturn(ME).when(c).getAppUserId();
        IWxContentSecurityService wx = mock(IWxContentSecurityService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        IRoleService roles = mock(IRoleService.class);
        IClubEventOpsService ops = mock(IClubEventOpsService.class);
        UmsMember me = new UmsMember();
        when(members.selectUmsMemberById(ME)).thenReturn(me);
        when(roles.resolveRole(me)).thenReturn(IRoleService.ROLE_CLUB);
        when(ops.cancelActivityByOwnerWithClubWrapUp(ME, 992033L, "E2E API smoke check"))
                .thenThrow(new ServiceException("原取消生命周期已变化，请人工核对原结果", HttpStatus.CONFLICT));
        ReflectionTestUtils.setField(c, "wxContentSecurityService", wx);
        ReflectionTestUtils.setField(c, "umsMemberService", members);
        ReflectionTestUtils.setField(c, "roleService", roles);
        ReflectionTestUtils.setField(c, "clubEventOpsService", ops);

        assertRefusal(c.activityCancel("992033", "E2E API smoke check", "CLUB"),
                HttpStatus.CONFLICT, "原取消生命周期已变化，请人工核对原结果");
    }

    // ── ApiRegistrationController ──────────────────────────────────────

    @Test
    void registrationRefusalsKeepServiceCodes() {
        ApiRegistrationController c = spy(new ApiRegistrationController());
        doReturn(ME).when(c).getAppUserId();
        IRegistrationCheckoutService checkout = mock(IRegistrationCheckoutService.class);
        when(checkout.quote(any(), any())).thenThrow(new ServiceException("主题活动已下架", HttpStatus.NOT_FOUND));
        ITeamGroupService teams = mock(ITeamGroupService.class);
        String cashMsg = "支付现金快照未确认，请通过后台退款工单核对后处理";
        when(teams.cancelPaidRegistration(992198L, ME)).thenThrow(new ServiceException(cashMsg, HttpStatus.CONFLICT));
        when(teams.cancelPaidRegistrationByOwner(992197L, ME)).thenThrow(new ServiceException(cashMsg, HttpStatus.CONFLICT));
        ReflectionTestUtils.setField(c, "registrationCheckoutService", checkout);
        ReflectionTestUtils.setField(c, "teamGroupService", teams);
        ReflectionTestUtils.setField(c, "cancellationReadService", mock(RegistrationCancellationReadService.class));

        assertRefusal(c.quote(new RegistrationRequest()), HttpStatus.NOT_FOUND, "主题活动已下架");
        assertRefusal(c.cancelRefund("992198"), HttpStatus.CONFLICT, cashMsg);
        assertRefusal(c.cancelByOwner("992197"), HttpStatus.CONFLICT, cashMsg);
        // 未带码的异常仍保持原来的 500 回显
        assertRefusal(ApiRegistrationController.codedRefusalOrError(new IllegalStateException("x")), HttpStatus.ERROR, "x");
    }

    // ── ApiUserInvoiceController ───────────────────────────────────────

    @Test
    void unknownInvoiceRequestIs404AndKeepsErrorCode() {
        IUserInvoiceService invoices = mock(IUserInvoiceService.class);
        ComplianceFeatureSwitches switches = mock(ComplianceFeatureSwitches.class);
        when(switches.userInvoiceOn()).thenReturn(true);
        when(invoices.applicationByRequest(ME, "d8abf7a2-52e4-4697-8a27-8c8fdddfcd30"))
                .thenThrow(new UserInvoiceException(UserInvoiceException.NOT_FOUND, "暂未查到原申请"));
        ApiUserInvoiceController c = spy(new ApiUserInvoiceController(invoices, switches));
        doReturn(ME).when(c).getAppUserId();

        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "d8abf7a2-52e4-4697-8a27-8c8fdddfcd30");
        AjaxResult r = c.originalRequest(body);
        assertRefusal(r, HttpStatus.NOT_FOUND, "暂未查到原申请");
        assertEquals("INVOICE_NOT_FOUND", r.get("errorCode"));
    }
}

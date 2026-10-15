package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopClubApply;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopClubApplyService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /api/coop/pool/* 端点测试(P0-4 开放范围)。
 *
 * 合作池列表:只展示「开放+在架+未锁价+非我发布」的主题,并给出俱乐部视角状态
 * (open可申请 / applied已申请 / taken已被别家承接 / cooped我在合作中 / declined已婉拒 / withdrawn已撤回)。
 * 申请/婉拒:委托 service 状态机,闸失败原样透出文案,订阅通知 best-effort 不阻断。
 */
class ApiCoopPoolControllerTest {

    private static final Long ME = 42L;      // 俱乐部主理人
    private static final Long MY_CLUB = 7L;
    private static final Long MERCHANT = 9L; // 商家(主题发布者)

    private ApiCoopPoolController controller;
    private ICoopClubApplyService coopClubApplyService;
    private ICmsTopicService cmsTopicService;
    private ICoopInviteService coopInviteService;
    private IClubService clubService;
    private INotifyService notifyService;
    private IUmsMemberService umsMemberService;
    private IMerchantAccessService merchantAccessService;
    private com.chengyinhub.business.service.support.CommercialTopicPolicy commercialTopicPolicy;

    @BeforeEach
    void setUp() {
        coopClubApplyService = mock(ICoopClubApplyService.class);
        cmsTopicService = mock(ICmsTopicService.class);
        coopInviteService = mock(ICoopInviteService.class);
        clubService = mock(IClubService.class);
        notifyService = mock(INotifyService.class);
        umsMemberService = mock(IUmsMemberService.class);
        merchantAccessService = mock(IMerchantAccessService.class);

        controller = spy(new ApiCoopPoolController());
        doReturn(ME).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "coopClubApplyService", coopClubApplyService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(controller, "coopInviteService", coopInviteService);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "notifyService", notifyService);
        ReflectionTestUtils.setField(controller, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        commercialTopicPolicy = mock(com.chengyinhub.business.service.support.CommercialTopicPolicy.class);
        when(commercialTopicPolicy.isFreeTopic(any())).thenCallRealMethod();
        ReflectionTestUtils.setField(controller, "clubCoopWindowPolicy",
                new com.chengyinhub.business.service.support.ClubCoopWindowPolicy(commercialTopicPolicy));

        Club mine = new Club();
        mine.setId(MY_CLUB);
        mine.setMemberId(ME);
        mine.setStatus(1L);
        when(clubService.selectClubsByMemberId(ME)).thenReturn(Collections.singletonList(mine));
        when(coopInviteService.selectCoopInviteList(any(CoopInvite.class))).thenReturn(Collections.emptyList());
        when(coopClubApplyService.selectByClubIds(any())).thenReturn(Collections.emptyList());
    }

    private CmsTopic topic(Long id, Long owner, int openClubPool, Integer lifecycle) {
        CmsTopic t = new CmsTopic();
        t.setId(id);
        t.setMemberId(owner);
        t.setOpenClubPool(openClubPool);
        t.setStatus(1);
        t.setUserStatus(1);
        t.setLifecycle(lifecycle);
        // F04:招商期(lifecycle 1/2)必须带未到期的 recruitDeadline,与邀约写闸同口径
        t.setRecruitDeadline(new java.util.Date(System.currentTimeMillis() + 86400_000L));
        t.setName("主题" + id);
        return t;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(AjaxResult r) {
        assertEquals(200, r.get("code"));
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        return (List<Map<String, Object>>) data.get("rows");
    }

    private Map<String, Object> rowOf(List<Map<String, Object>> rows, Long topicId) {
        for (Map<String, Object> r : rows) {
            if (topicId.equals(r.get("topicId"))) return r;
        }
        return null;
    }

    // ---------- /list ----------

    @Test
    void list_excludes_my_own_and_price_locked_topics() {
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class))).thenReturn(Arrays.asList(
                topic(1L, MERCHANT, 1, 1),   // 可申请
                topic(2L, ME, 1, 1),         // 我发布的 → 不进池
                topic(3L, MERCHANT, 1, 3))); // 已锁价 → 不进池

        List<Map<String, Object>> rows = rows(controller.list(null));

        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).get("topicId"));
        assertEquals("open", rows.get(0).get("state"));
    }

    /** F04:池里能看见 ⇔ 能申请 ⇔ 能被邀约。免费 UGC 停在 DRAFT 可见;商业主题未过立项审不可见。 */
    @Test
    void list_uses_same_recruitment_window_as_invite() {
        CmsTopic freeUgc = topic(11L, MERCHANT, 1, 0);
        freeUgc.setRecruitDeadline(null);
        CmsTopic commercialDraft = topic(12L, MERCHANT, 1, 0);
        commercialDraft.setRecruitDeadline(null);
        CmsTopic expired = topic(13L, MERCHANT, 1, 1);
        expired.setRecruitDeadline(new java.util.Date(System.currentTimeMillis() - 60_000L));
        when(commercialTopicPolicy.evaluate(11L))
                .thenReturn(com.chengyinhub.business.service.support.CommercialTopicPolicy.Evaluation.ugc());
        when(commercialTopicPolicy.evaluate(12L)).thenReturn(com.chengyinhub.business.service.support
                .CommercialTopicPolicy.Evaluation.commercial("paidTicket", 1L));
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Arrays.asList(freeUgc, commercialDraft, expired));

        List<Map<String, Object>> rows = rows(controller.list(null));

        assertEquals(1, rows.size());
        assertEquals(11L, rows.get(0).get("topicId"));
    }

    @Test
    void list_marks_applied_taken_and_cooped_states() {
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class))).thenReturn(Arrays.asList(
                topic(1L, MERCHANT, 1, 1),   // 我已申请待处理
                topic(2L, MERCHANT, 1, 1),   // 别家俱乐部已承接
                topic(3L, MERCHANT, 1, 1))); // 我的俱乐部合作中

        CoopClubApply myApply = new CoopClubApply();
        myApply.setId(55L);
        myApply.setTopicId(1L);
        myApply.setClubId(MY_CLUB);
        myApply.setStatus(0);
        when(coopClubApplyService.selectByClubIds(any())).thenReturn(Collections.singletonList(myApply));

        CoopInvite othersAccepted = new CoopInvite();
        othersAccepted.setTopicId(2L);
        othersAccepted.setToType("club");
        othersAccepted.setToId(88L);
        othersAccepted.setStatus(1);
        CoopInvite mineAccepted = new CoopInvite();
        mineAccepted.setTopicId(3L);
        mineAccepted.setToType("club");
        mineAccepted.setToId(MY_CLUB);
        mineAccepted.setStatus(1);
        when(coopInviteService.selectCoopInviteList(any(CoopInvite.class))).thenAnswer(inv -> {
            CoopInvite q = inv.getArgument(0);
            if (q.getTopicId() != null && q.getTopicId() == 2L) return Collections.singletonList(othersAccepted);
            if (q.getTopicId() != null && q.getTopicId() == 3L) return Collections.singletonList(mineAccepted);
            return Collections.emptyList();
        });

        List<Map<String, Object>> rows = rows(controller.list(null));

        assertEquals("applied", rowOf(rows, 1L).get("state"));
        assertEquals(55L, rowOf(rows, 1L).get("applyId"));
        assertEquals("taken", rowOf(rows, 2L).get("state"));
        assertEquals("cooped", rowOf(rows, 3L).get("state"));
    }

    @Test
    void invited_wins_over_applied_so_club_sees_next_step() {
        // 主链路:俱乐部申请(行仍 status=0)→ 商家回一张 type1 邀约 → 池卡必须显示「商家已邀约」CTA,
        // 不能停在「已申请·等商家处理」(否则商家确认这一跳俱乐部永远看不见)。
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Collections.singletonList(topic(1L, MERCHANT, 1, 1)));

        CoopClubApply myApply = new CoopClubApply();
        myApply.setId(55L);
        myApply.setTopicId(1L);
        myApply.setClubId(MY_CLUB);
        myApply.setStatus(0);
        when(coopClubApplyService.selectByClubIds(any())).thenReturn(Collections.singletonList(myApply));

        CoopInvite pendingToMe = new CoopInvite();
        pendingToMe.setTopicId(1L);
        pendingToMe.setInviteType(1);
        pendingToMe.setToType("club");
        pendingToMe.setToId(MY_CLUB);
        pendingToMe.setStatus(0);
        when(coopInviteService.selectCoopInviteList(any(CoopInvite.class)))
                .thenReturn(Collections.singletonList(pendingToMe));

        assertEquals("invited", rowOf(rows(controller.list(null)), 1L).get("state"));
    }

    @Test
    void club_published_topics_never_enter_pool() {
        // §9.2/9.3 开放报名只覆盖玩家/商家发布的主题;俱乐部主题进池会让三态永远显示不出(对手方按商家派生)
        CmsTopic clubTopic = topic(9L, MERCHANT, 1, 1);
        clubTopic.setClubId(77L);
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Arrays.asList(topic(1L, MERCHANT, 1, 1), clubTopic));

        List<Map<String, Object>> rows = rows(controller.list(null));

        assertEquals(1, rows.size());
        assertNull(rowOf(rows, 9L));
    }

    // ---------- /apply ----------

    private Map<String, Object> applyBody() {
        Map<String, Object> m = new HashMap<>();
        m.put("topicId", 1L);
        m.put("message", "想带这条线");
        return m;
    }

    @Test
    void apply_returns_apply_id_and_notifies_topic_owner() {
        CoopClubApply row = new CoopClubApply();
        row.setId(66L);
        row.setTopicId(1L);
        row.setClubId(MY_CLUB);
        when(coopClubApplyService.apply(eq(1L), eq(ME), any(), eq("想带这条线"))).thenReturn(row);
        when(cmsTopicService.selectCmsTopicById(1L)).thenReturn(topic(1L, MERCHANT, 1, 1));

        AjaxResult r = controller.apply(applyBody());

        assertEquals(200, r.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals(66L, data.get("applyId"));
        verify(notifyService).notifyCoopStatus(eq(MERCHANT), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void apply_gate_failure_returns_service_message() {
        when(coopClubApplyService.apply(anyLong(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("该主题未开放俱乐部承接"));

        AjaxResult r = controller.apply(applyBody());

        assertEquals(500, r.get("code"));
        assertEquals("该主题未开放俱乐部承接", r.get("msg"));
        verify(notifyService, never()).notifyCoopStatus(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void apply_notify_failure_does_not_break_success() {
        CoopClubApply row = new CoopClubApply();
        row.setId(66L);
        row.setTopicId(1L);
        when(coopClubApplyService.apply(anyLong(), anyLong(), any(), any())).thenReturn(row);
        when(cmsTopicService.selectCmsTopicById(1L)).thenReturn(topic(1L, MERCHANT, 1, 1));
        doThrow(new RuntimeException("模板未配置")).when(notifyService)
                .notifyCoopStatus(anyLong(), anyString(), anyString(), anyString(), anyString());

        AjaxResult r = controller.apply(applyBody());

        assertEquals(200, r.get("code"));
    }

    // ---------- /withdraw & /decline ----------

    @Test
    void withdraw_delegates_to_service() {
        Map<String, Object> body = new HashMap<>();
        body.put("topicId", 1L);
        CoopClubApply upd = new CoopClubApply();
        upd.setId(66L);
        upd.setStatus(2);
        when(coopClubApplyService.withdraw(1L, ME)).thenReturn(upd);

        AjaxResult r = controller.withdraw(body);

        assertEquals(200, r.get("code"));
        verify(coopClubApplyService).withdraw(1L, ME);
    }

    @Test
    void decline_delegates_and_notifies_applicant() {
        Map<String, Object> body = new HashMap<>();
        body.put("applyId", 66L);
        CoopClubApply declined = new CoopClubApply();
        declined.setId(66L);
        declined.setTopicId(1L);
        declined.setMemberId(ME + 1); // 申请方主理人
        declined.setStatus(1);
        when(coopClubApplyService.decline(66L, ME)).thenReturn(declined);
        when(cmsTopicService.selectCmsTopicById(1L)).thenReturn(topic(1L, ME, 1, 1));

        AjaxResult r = controller.decline(body);

        assertEquals(200, r.get("code"));
        verify(coopClubApplyService).decline(66L, ME);
        verify(notifyService).notifyCoopStatus(eq(ME + 1), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void decline_gate_failure_returns_service_message() {
        Map<String, Object> body = new HashMap<>();
        body.put("applyId", 66L);
        when(coopClubApplyService.decline(66L, ME))
                .thenThrow(new IllegalStateException("仅主题发布者可处理承接申请"));

        AjaxResult r = controller.decline(body);

        assertEquals(500, r.get("code"));
        assertEquals("仅主题发布者可处理承接申请", r.get("msg"));
    }

    @Test
    void merchantScopedDeclineUsesProjectOwnerInsteadOfOperatorPrincipal() {
        Map<String, Object> body = new HashMap<>();
        body.put("applyId", 66L);
        body.put("scope", "MERCHANT");
        when(merchantAccessService.require(ME, MerchantPermission.PROJECT_MANAGE)).thenReturn(
                new MerchantAccessContext(ME, 90L, MERCHANT, MerchantRoleCode.MERCHANT_MANAGER));
        CoopClubApply declined = new CoopClubApply();
        declined.setId(66L);
        declined.setTopicId(1L);
        declined.setStatus(1);
        when(coopClubApplyService.decline(66L, MERCHANT)).thenReturn(declined);

        AjaxResult result = controller.decline(body);

        assertEquals(200, result.get("code"));
        verify(merchantAccessService).require(ME, MerchantPermission.PROJECT_MANAGE);
        verify(coopClubApplyService).decline(66L, MERCHANT);
    }

    // ---------- /received /mine(收发件箱) ----------

    private CoopClubApply applyOf(Long id, Long topicId, Long clubId, Long member, int status, long createdAt) {
        CoopClubApply a = new CoopClubApply();
        a.setId(id);
        a.setTopicId(topicId);
        a.setClubId(clubId);
        a.setMemberId(member);
        a.setStatus(status);
        a.setCreateTime(new java.util.Date(createdAt));
        return a;
    }

    private Club clubOf(Long id, String name) {
        Club c = new Club();
        c.setId(id);
        c.setName(name);
        // 带上真实联系方式:行里若泄漏,下面按值断言才会红
        c.setLeaderName("王主理");
        c.setPhone("13800001111");
        c.setStatus(1L);
        return c;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listData(AjaxResult r) {
        assertEquals(200, r.get("code"));
        return (List<Map<String, Object>>) r.get("data");
    }

    @Test
    void received_merchant_scope_reads_project_owner_hides_dissolved_club_and_contact() {
        Map<String, Object> body = new HashMap<>();
        body.put("scope", "MERCHANT");
        when(merchantAccessService.require(ME, MerchantPermission.PROJECT_MANAGE)).thenReturn(
                new MerchantAccessContext(ME, 90L, MERCHANT, MerchantRoleCode.MERCHANT_MANAGER));
        when(coopClubApplyService.selectByTopicOwner(MERCHANT)).thenReturn(Arrays.asList(
                applyOf(1L, 100L, 7L, 42L, 0, 2000L), applyOf(2L, 100L, 8L, 43L, 1, 1000L)));
        when(clubService.selectClubById(7L)).thenReturn(clubOf(7L, "夜跑团"));
        when(clubService.selectClubById(8L)).thenReturn(null); // 已解散
        CmsTopic t = topic(100L, MERCHANT, 1, 50);
        when(cmsTopicService.selectCmsTopicById(100L)).thenReturn(t);

        List<Map<String, Object>> rows = listData(controller.received(body));

        verify(coopClubApplyService).selectByTopicOwner(MERCHANT);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals(1L, row.get("applyId"));
        assertEquals("夜跑团", row.get("clubName"));
        assertEquals("主题100", row.get("topicName"));
        assertEquals("MERCHANT", row.get("scope"), "owner 主题上的行要带 scope,前端回传给 /decline");
        for (Object v : row.values()) {
            assertEquals(false, String.valueOf(v).contains("13800001111") || String.valueOf(v).contains("王主理"),
                    "§3.8 申请行不下发主理人联系方式:" + v);
        }
    }

    @Test
    void received_without_scope_non_merchant_reads_principal_only() {
        doThrow(new com.chengyinhub.common.exception.ServiceException("非商家成员"))
                .when(merchantAccessService).require(ME, MerchantPermission.PROJECT_MANAGE);
        when(coopClubApplyService.selectByTopicOwner(ME)).thenReturn(Collections.emptyList());
        listData(controller.received(null));
        verify(coopClubApplyService).selectByTopicOwner(ME);
        verify(coopClubApplyService, never()).selectByTopicOwner(MERCHANT);
    }

    @Test
    void received_without_scope_staff_sees_own_and_owner_rows_owner_rows_tagged_merchant() {
        when(merchantAccessService.require(ME, MerchantPermission.PROJECT_MANAGE)).thenReturn(
                new MerchantAccessContext(ME, 90L, MERCHANT, MerchantRoleCode.MERCHANT_MANAGER));
        when(coopClubApplyService.selectByTopicOwner(ME)).thenReturn(Collections.singletonList(applyOf(5L, 200L, 7L, 42L, 0, 3000L)));
        when(coopClubApplyService.selectByTopicOwner(MERCHANT)).thenReturn(Collections.singletonList(applyOf(6L, 100L, 7L, 42L, 0, 2000L)));
        when(clubService.selectClubById(7L)).thenReturn(clubOf(7L, "夜跑团"));

        List<Map<String, Object>> rows = listData(controller.received(null));

        assertEquals(Arrays.asList(5L, 6L), Arrays.asList(rows.get(0).get("applyId"), rows.get(1).get("applyId")));
        assertNull(rows.get(0).get("scope"), "本人主题上的行用本人身份处理");
        assertEquals("MERCHANT", rows.get(1).get("scope"), "owner 主题上的行必须带 scope,否则员工 /decline 被归属闸拒");
    }

    @Test
    void received_explicit_merchant_scope_without_access_surfaces_error_not_personal_rows() {
        doThrow(new com.chengyinhub.common.exception.ServiceException("无商家权限", 403))
                .when(merchantAccessService).require(ME, MerchantPermission.PROJECT_MANAGE);
        Map<String, Object> body = new HashMap<>();
        body.put("scope", "MERCHANT");
        AjaxResult r = controller.received(body);
        assertEquals(403, r.get("code"));
        verify(coopClubApplyService, never()).selectByTopicOwner(anyLong());
    }

    @Test
    void mine_unions_applicant_and_current_club_dedupes_and_keeps_locked_history() {
        when(coopClubApplyService.selectByApplicant(ME)).thenReturn(Arrays.asList(
                applyOf(1L, 100L, MY_CLUB, ME, 1, 1000L), applyOf(2L, 101L, MY_CLUB, ME, 0, 3000L)));
        when(coopClubApplyService.selectByClubIds(Collections.singletonList(MY_CLUB))).thenReturn(Arrays.asList(
                applyOf(2L, 101L, MY_CLUB, ME, 0, 3000L), applyOf(3L, 102L, MY_CLUB, 41L, 2, 2000L)));
        when(cmsTopicService.selectCmsTopicById(100L)).thenReturn(topic(100L, MERCHANT, 1, 99)); // 任意生命周期
        when(cmsTopicService.selectCmsTopicById(101L)).thenReturn(topic(101L, MERCHANT, 1, null));
        when(clubService.selectClubById(MY_CLUB)).thenReturn(clubOf(MY_CLUB, "夜跑团"));

        List<Map<String, Object>> rows = listData(controller.mine(null));
        assertEquals(Boolean.TRUE, rows.get(0).get("canWithdraw"), "待处理且在我第一个生效俱乐部上:能撤");
        assertEquals(Boolean.FALSE, rows.get(1).get("canWithdraw"), "已撤回的不能再撤");

        assertEquals(Arrays.asList(2L, 3L, 1L),
                Arrays.asList(rows.get(0).get("applyId"), rows.get(1).get("applyId"), rows.get(2).get("applyId")),
                "去重 + 按申请时间倒序;前任主理人发的申请现任也看得见");
        assertEquals("夜跑团", rows.get(0).get("clubName"));
    }

    @Test
    void missing_topic_id_is_rejected() {
        AjaxResult r = controller.apply(new HashMap<>());
        assertEquals(500, r.get("code"));
        assertNotNull(r.get("msg"));
        assertNull(((Map<?, ?>) r).get("data"));
    }
}

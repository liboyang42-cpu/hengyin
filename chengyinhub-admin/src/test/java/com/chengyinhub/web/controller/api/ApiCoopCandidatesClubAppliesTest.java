package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopClubApply;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopClubApplyService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一候选池扩展(P0-4):/api/coop/candidates 除邀约候选、商家报名候选外,
 * 还要返回「俱乐部承接申请」(开放报名路径),并带俱乐部名与「是否已对该俱乐部发过邀约」派生态,
 * 供发布者婉拒或直接跳发 type1 带条款邀约。
 */
class ApiCoopCandidatesClubAppliesTest {

    private static final Long PUBLISHER = 9L;
    private static final Long TOPIC = 200L;
    private static final Long APPLY_CLUB = 7L;

    private ApiCoopController controller;
    private ICoopClubApplyService coopClubApplyService;
    private ICoopInviteService coopInviteService;
    private IClubService clubService;
    private ICmsTopicService cmsTopicService;
    private ICmsRegistrationMerchantService regService;
    private IMmsMerchantService merchantService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiCoopController());
        doReturn(PUBLISHER).when(controller).getAppUserId();

        cmsTopicService = mock(ICmsTopicService.class);
        coopInviteService = mock(ICoopInviteService.class);
        clubService = mock(IClubService.class);
        coopClubApplyService = mock(ICoopClubApplyService.class);
        regService = mock(ICmsRegistrationMerchantService.class);
        merchantService = mock(IMmsMerchantService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(controller, "coopInviteService", coopInviteService);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "coopClubApplyService", coopClubApplyService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", regService);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(PUBLISHER);
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(coopInviteService.selectCoopInviteList(any(CoopInvite.class))).thenReturn(Collections.emptyList());
        when(regService.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.emptyList());

        Club club = new Club();
        club.setId(APPLY_CLUB);
        club.setName("城市漫游俱乐部");
        club.setLogo("logo.png");
        when(clubService.selectClubById(APPLY_CLUB)).thenReturn(club);
    }

    private CoopInvite query() {
        CoopInvite q = new CoopInvite();
        q.setTopicId(TOPIC);
        return q;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> clubApplies(AjaxResult r) {
        assertEquals(200, r.get("code"));
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        return (List<Map<String, Object>>) data.get("clubApplies");
    }

    @SuppressWarnings("unchecked")
    private List<CoopInvite> invites(AjaxResult r) {
        assertEquals(200, r.get("code"));
        return (List<CoopInvite>) ((Map<String, Object>) r.get("data")).get("invites");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> registrations(AjaxResult r) {
        assertEquals(200, r.get("code"));
        return (List<Map<String, Object>>) ((Map<String, Object>) r.get("data")).get("registrations");
    }

    @Test
    void registration_candidate_carries_real_merchant_identity_without_private_profile() {
        CmsRegistrationMerchant registration = new CmsRegistrationMerchant();
        registration.setId(81L);
        registration.setMemberId(42L);
        registration.setAuditStatus(0L);
        registration.setAddressName("中山公园站");
        when(regService.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.singletonList(registration));
        MmsMerchant merchant = new MmsMerchant();
        merchant.setMemberId(42L);
        merchant.setName("漫步咖啡");
        merchant.setLogo("merchant-logo.png");
        merchant.setAddress("长宁区愚园路");
        merchant.setPhone("13800000000");
        when(merchantService.selectMmsMerchantByMemberId(42L)).thenReturn(merchant);

        List<Map<String, Object>> rows = registrations(controller.candidates(query()));

        assertEquals(1, rows.size());
        assertEquals("漫步咖啡", rows.get(0).get("merchantName"));
        assertEquals("merchant-logo.png", rows.get(0).get("merchantLogo"));
        assertEquals("长宁区愚园路", rows.get(0).get("merchantMeta"));
        org.junit.jupiter.api.Assertions.assertFalse(rows.get(0).containsKey("phone"),
                "候选池只返回邀请展示需要的公开身份，不得透出联系方式");
    }

    @Test
    void candidates_returns_club_applies_with_club_name() {
        CoopClubApply apply = new CoopClubApply();
        apply.setId(66L);
        apply.setTopicId(TOPIC);
        apply.setClubId(APPLY_CLUB);
        apply.setStatus(0);
        apply.setMessage("有带队经验");
        when(coopClubApplyService.selectByTopic(TOPIC)).thenReturn(Collections.singletonList(apply));

        List<Map<String, Object>> applies = clubApplies(controller.candidates(query()));

        assertNotNull(applies, "candidates 必须返回 clubApplies 字段");
        assertEquals(1, applies.size());
        assertEquals(66L, applies.get(0).get("id"));
        assertEquals(APPLY_CLUB, applies.get(0).get("clubId"));
        assertEquals("城市漫游俱乐部", applies.get(0).get("clubName"));
        assertEquals(0, applies.get(0).get("status"));
        assertEquals("有带队经验", applies.get(0).get("message"));
    }

    @Test
    void club_apply_carries_whether_invite_already_sent() {
        CoopClubApply apply = new CoopClubApply();
        apply.setId(66L);
        apply.setTopicId(TOPIC);
        apply.setClubId(APPLY_CLUB);
        apply.setStatus(0);
        when(coopClubApplyService.selectByTopic(TOPIC)).thenReturn(Collections.singletonList(apply));

        CoopInvite sent = new CoopInvite();
        sent.setTopicId(TOPIC);
        sent.setInviteType(1);
        sent.setToType("club");
        sent.setToId(APPLY_CLUB);
        sent.setStatus(0);
        when(coopInviteService.selectCoopInviteList(any(CoopInvite.class)))
                .thenReturn(Collections.singletonList(sent));

        List<Map<String, Object>> applies = clubApplies(controller.candidates(query()));

        assertEquals(0, applies.get(0).get("inviteStatus"), "已对该俱乐部发出的邀约状态须透出(0待确认)");
    }

    @Test
    void no_applies_returns_empty_list_not_null() {
        when(coopClubApplyService.selectByTopic(TOPIC)).thenReturn(Collections.emptyList());
        List<Map<String, Object>> applies = clubApplies(controller.candidates(query()));
        assertNotNull(applies);
        assertEquals(0, applies.size());
    }

    @Test
    void dissolved_club_apply_is_retained_for_audit_but_hidden_from_candidates() {
        CoopClubApply apply = new CoopClubApply();
        apply.setId(66L);
        apply.setTopicId(TOPIC);
        apply.setClubId(APPLY_CLUB);
        apply.setStatus(0);
        when(coopClubApplyService.selectByTopic(TOPIC)).thenReturn(Collections.singletonList(apply));
        when(clubService.selectClubById(APPLY_CLUB)).thenReturn(null);

        assertEquals(0, clubApplies(controller.candidates(query())).size(),
                "已解散俱乐部不能继续成为发布者可操作的承接候选");
    }

    @Test
    void dissolved_target_club_invite_is_hidden_from_candidates() {
        CoopInvite invite = new CoopInvite();
        invite.setTopicId(TOPIC);
        invite.setToType("club");
        invite.setToId(APPLY_CLUB);
        invite.setStatus(0);
        when(coopInviteService.selectCoopInviteList(any(CoopInvite.class)))
                .thenReturn(Collections.singletonList(invite));
        when(clubService.selectClubById(APPLY_CLUB)).thenReturn(null);

        assertEquals(0, invites(controller.candidates(query())).size(),
                "已解散目标俱乐部邀约不能继续成为可操作候选");
    }

    @Test
    void dissolved_topic_parent_rejectsCandidatesBeforeReadingInvites() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(PUBLISHER);
        topic.setClubId(77L);
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(clubService.selectClubById(77L)).thenReturn(null);

        AjaxResult result = controller.candidates(query());

        assertEquals(500, result.get("code"));
        assertEquals("主题所属俱乐部已解散，候选池不可用", result.get("msg"),
                "该红必须指认到候选池父俱乐部存活闸");
        verify(coopInviteService, never()).selectCoopInviteList(any(CoopInvite.class));
    }

    @Test
    void merchantScopedCandidatesUseProjectOwnerInsteadOfOperatorPrincipal() {
        Long owner = 10L;
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PUBLISHER, MerchantPermission.PROJECT_MANAGE)).thenReturn(
                new MerchantAccessContext(PUBLISHER, 77L, owner, MerchantRoleCode.MERCHANT_MANAGER));
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(owner);
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        CoopInvite query = query();
        query.setScope("MERCHANT");

        AjaxResult result = controller.candidates(query);

        assertEquals(200, result.get("code"));
        verify(access).require(PUBLISHER, MerchantPermission.PROJECT_MANAGE);
    }
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubAccessServiceImpl;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubRoleAssignmentMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.business.service.impl.GroupCodeServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D22 · T2b:主理人/获授权场次工作人员按带队场次出团码 → 商家扫 → 记「该商家接待了该团」。
 *
 * <p>① 是有领队产品,团整体走完全程 ⇒ 商家接待的是整个团,不是零散来客。这条路是组织槽有人时
 * 商家分账基数的**唯一**来源(settleCoopTopic 按 coop_group_verify 反查领队再算团的核销数)。
 *
 * <p>本类守两个越权面 + 幂等:
 *  · 出码方必须有本主题承接俱乐部对应场次的核销权限 —— 否则任何人都能造团码骗商家扫;
 *  · 扫码方必须在本主题有生效 merchant 合作单 —— 否则无关商家能往台账里塞行;
 *  · 重复扫不叠加基数(靠库级唯一键 + insert ignore,不靠先查后插 —— 读判挡不住并发双扫)。
 *
 * ⚠️ 负控已实测(见 D22 提交说明):
 *  - issue 去掉 ClubAccess 场次权限校验            ⇒ issue_nonOrganizer_rejected 红
 *  - redeem 去掉「有生效 merchant 合作单」校验     ⇒ redeem_merchantWithoutCoopOrder_rejected 红
 *  - redeem 去掉出码方资格复校验                   ⇒ redeem_leaderNoLongerOrganizer_rejected 红
 *  - redeem 不判 type 前缀                         ⇒ redeem_nonGroupCode_rejected 红
 *  - 台账写 coopOrderId=null / leaderId 取成扫码人 ⇒ redeem_persistsLedgerRow 红
 */
class ApiVerifyGroupCodeTest {

    private static final Long TOPIC = 300L;
    private static final Long ACTIVITY = 301L;
    private static final Long CLUB = 610L;
    private static final Long OTHER_ACTIVITY = 302L;
    private static final Long OTHER_CLUB = 611L;
    private static final Long LEADER = 42L;        // 主理人
    private static final Long ADMIN = 43L;         // 历史 role==1，另有 EVENT_CHECKIN assignment
    private static final Long MERCHANT = 77L;      // 扫团码的商家
    private static final Long MERCHANT_OPERATOR = 7002L;
    private static final Long OUTSIDER = 99L;
    private static final Long COOP_ORDER = 8001L;
    private static final String SECRET = "test-secret";

    private CoopOrderMapper coopOrderMapper;
    private CoopGroupVerifyMapper groupVerifyMapper;
    private ClubMapper clubMapper;
    private ClubMemberMapper clubMemberMapper;
    private ClubRoleAssignmentMapper clubRoleAssignmentMapper;
    private CmsActivityMapper cmsActivityMapper;
    private ICmsActivityService activityService;
    private CmsActivity activity;
    private IMerchantAccessService merchantAccessService;
    private BusinessAuditEventMapper auditMapper;
    private ApiVerifyController controller;

    @BeforeEach
    void setUp() {
        coopOrderMapper = mock(CoopOrderMapper.class);
        groupVerifyMapper = mock(CoopGroupVerifyMapper.class);
        clubMapper = mock(ClubMapper.class);
        clubMemberMapper = mock(ClubMemberMapper.class);
        clubRoleAssignmentMapper = mock(ClubRoleAssignmentMapper.class);
        cmsActivityMapper = mock(CmsActivityMapper.class);
        activityService = mock(ICmsActivityService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        auditMapper = mock(BusinessAuditEventMapper.class);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);

        controller = spy(new ApiVerifyController());
        when(merchantAccessService.require(anyLong(), eq(MerchantPermission.VERIFY)))
                .thenAnswer(invocation -> {
                    Long principal = invocation.getArgument(0);
                    return new MerchantAccessContext(principal, principal + 1000L,
                            principal, MerchantRoleCode.MERCHANT_OWNER);
                });
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        // 团码资格走真实 ClubAccessServiceImpl，避免把 assignment 撤销与场次作用域 mock 成假绿。
        ClubAccessServiceImpl clubAccessService = new ClubAccessServiceImpl();
        UmsMemberMapper umsMemberMapper = mock(UmsMemberMapper.class);
        ReflectionTestUtils.setField(clubAccessService, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(clubAccessService, "clubMemberMapper", clubMemberMapper);
        ReflectionTestUtils.setField(clubAccessService, "clubRoleAssignmentMapper", clubRoleAssignmentMapper);
        ReflectionTestUtils.setField(clubAccessService, "cmsActivityMapper", cmsActivityMapper);
        ReflectionTestUtils.setField(clubAccessService, "umsMemberMapper", umsMemberMapper);
        when(umsMemberMapper.selectRolePermissionSubject(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            com.chengyinhub.business.domain.UmsMember m = new com.chengyinhub.business.domain.UmsMember();
            m.setId(call.getArgument(0)); m.setRole("club"); m.setStatus(1L); m.setDelFlag(0); return m;
        });
        when(umsMemberMapper.lockActiveMemberById(anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        GroupCodeServiceImpl groupCodeService = new GroupCodeServiceImpl(activityService, clubAccessService,
                coopOrderMapper, groupVerifyMapper, stubbedQrcode(), SECRET);
        ReflectionTestUtils.setField(groupCodeService, "businessAuditEventMapper", auditMapper);
        // 2026-09-11:团码资格多了「自办场次」这一档(主题挂在本俱乐部名下就不必再有承接合作单),
        // 判据要读主题。本套用例测的是**承接**场景,主题不属于该俱乐部,所以这里桩一个别人的主题:
        // 不桩 = NPE,桩成本俱乐部 = 把「没有合作单也放行」当成默认值,两种都会让下面的断言失真。
        com.chengyinhub.business.mapper.CmsTopicMapper groupCodeTopicMapper =
                mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        com.chengyinhub.business.domain.CmsTopic foreignTopic = new com.chengyinhub.business.domain.CmsTopic();
        foreignTopic.setId(TOPIC);
        foreignTopic.setClubId(CLUB + 9999L);
        when(groupCodeTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(foreignTopic);
        ReflectionTestUtils.setField(groupCodeService, "cmsTopicMapper", groupCodeTopicMapper);
        ReflectionTestUtils.setField(controller, "groupCodeService", groupCodeService);

        // 该主题:一个承接俱乐部(组织槽)+ 一个 merchant 合作单(供给槽)
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Arrays.asList(
                coopOrder("club", CLUB, null),
                coopOrder("merchant", MERCHANT, COOP_ORDER)));
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(LEADER);
        club.setStatus(1L);
        club.setDelFlag(0);
        when(clubMapper.selectClubById(CLUB)).thenReturn(club);
        when(clubMemberMapper.selectByClubAndMember(CLUB, ADMIN)).thenReturn(clubMember(ADMIN, 1, 1));
        when(clubRoleAssignmentMapper.selectActiveForAccess(
                eq(CLUB), eq(ADMIN), eq(ACTIVITY), any(Date.class)))
                .thenReturn(Collections.singletonList(eventRole(ADMIN, ACTIVITY)));
        activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setClubId(CLUB);
        activity.setDelFlag(0);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(cmsActivityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        CmsActivity otherActivity = new CmsActivity();
        otherActivity.setId(OTHER_ACTIVITY);
        otherActivity.setTopicId(TOPIC);
        otherActivity.setClubId(OTHER_CLUB);
        otherActivity.setDelFlag(0);
        otherActivity.setStatus(1L);
        otherActivity.setPublishStatus(1L);
        when(activityService.selectCmsActivityById(OTHER_ACTIVITY)).thenReturn(otherActivity);
        when(cmsActivityMapper.selectCmsActivityById(OTHER_ACTIVITY)).thenReturn(otherActivity);
    }

    // ------------------------------------------------------------------ issue

    /** 主理人出码 ⇒ 成功,码里带 topicId、带队场次与他自己的 memberId(都在 HMAC 内)。 */
    @Test
    void issue_byLeader_succeeds() {
        doReturn(LEADER).when(controller).getAppUserId();

        AjaxResult r = controller.issueGroupCode(ACTIVITY);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertNotNull(data, "出码应返回 data");
        VerifyDynCodeUtil.Result v = VerifyDynCodeUtil.verify(
                String.valueOf(data.get("code")), System.currentTimeMillis(), SECRET);
        assertTrue(v.valid, "签发的码必须能被自己校验通过");
        assertEquals(TOPIC, v.regId, "码里的 regId = topicId");
        assertEquals("group_v2_" + ACTIVITY + "_" + LEADER, v.type,
                "码里的 type 必须绑定带队场次与领队,商家不可篡改团的归属");
    }

    /** 已分配俱乐部运营角色的成员同样能出码；legacy role=1 本身不再授权。 */
    @Test
    void issue_byAssignedCheckinOperator_succeeds() {
        doReturn(ADMIN).when(controller).getAppUserId();

        AjaxResult r = controller.issueGroupCode(ACTIVITY);

        assertNotNull(r.get("data"));
    }

    /** 主理人签发的当下有效团码可原样交给有合作单的商家兑换。 */
    @Test
    void issueThenRedeem_recordsGroupReception() {
        doReturn(LEADER).when(controller).getAppUserId();
        AjaxResult issued = controller.issueGroupCode(ACTIVITY);
        @SuppressWarnings("unchecked")
        Map<String, Object> issuedData = (Map<String, Object>) issued.get("data");

        doReturn(MERCHANT).when(controller).getAppUserId();
        when(groupVerifyMapper.insertIgnoreCoopGroupVerify(any())).thenReturn(1);
        AjaxResult redeemed = controller.redeemGroupCode(String.valueOf(issuedData.get("code")));

        assertEquals("已记录接待该团", redeemed.get("msg"));
        verify(groupVerifyMapper).insertIgnoreCoopGroupVerify(any());
    }

    /** ★越权面:不是本主题承接俱乐部的人 ⇒ 出不了码。漏了就是任何人都能造团码骗商家扫。 */
    @Test
    void issue_nonOrganizer_rejected() {
        doReturn(OUTSIDER).when(controller).getAppUserId();
        when(clubMemberMapper.selectByClubAndMember(CLUB, OUTSIDER)).thenReturn(null);

        AjaxResult r = controller.issueGroupCode(ACTIVITY);

        assertEquals(500, r.get("code"));
        assertTrue(String.valueOf(r.get("msg")).contains("团码核销权限"));
    }

    /** 普通成员(role=0)不是管理员 ⇒ 出不了码。 */
    @Test
    void issue_plainClubMember_rejected() {
        doReturn(OUTSIDER).when(controller).getAppUserId();
        when(clubMemberMapper.selectByClubAndMember(CLUB, OUTSIDER)).thenReturn(clubMember(OUTSIDER, 0, 1));

        AjaxResult r = controller.issueGroupCode(ACTIVITY);

        assertEquals(500, r.get("code"));
    }

    /** 已取消场次不能再签发团码，否则其账随后会被 360 隐去但仍参与主题结算。 */
    @Test
    void issue_cancelledActivity_rejected() {
        doReturn(LEADER).when(controller).getAppUserId();
        activity.setCancelTime(new Date());

        AjaxResult r = controller.issueGroupCode(ACTIVITY);

        assertTrue(String.valueOf(r.get("msg")).contains("不可带队核销"));
    }

    // ----------------------------------------------------------------- redeem

    /** ★主路径:商家扫合法团码 ⇒ 台账落一行,字段逐个对。 */
    @Test
    void redeem_persistsLedgerRow() {
        doReturn(MERCHANT).when(controller).getAppUserId();
        when(groupVerifyMapper.insertIgnoreCoopGroupVerify(any())).thenReturn(1);

        AjaxResult r = controller.redeemGroupCode(issuedBy(LEADER));

        assertEquals("已记录接待该团", r.get("msg"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals(Boolean.FALSE, data.get("alreadyVerified"));
        ArgumentCaptor<CoopGroupVerify> cap = ArgumentCaptor.forClass(CoopGroupVerify.class);
        verify(groupVerifyMapper).insertIgnoreCoopGroupVerify(cap.capture());
        CoopGroupVerify gv = cap.getValue();
        assertEquals(TOPIC, gv.getTopicId());
        assertEquals(ACTIVITY, gv.getActivityId(), "核销必须落到签名绑定的带队场次");
        assertEquals(MERCHANT, gv.getMerchantMemberId(), "接待方 = 扫码的商家");
        assertEquals(LEADER, gv.getLeaderMemberId(), "领队 = 出码人,不是扫码人");
        assertEquals(COOP_ORDER, gv.getCoopOrderId(), "必须挂上他的合作单 —— 结算按它取分账条款");
        assertNotNull(gv.getVerifyTime());
    }

    @Test
    void operatorRedeemPersistsOwnerMerchantScope() {
        doReturn(MERCHANT_OPERATOR).when(controller).getAppUserId();
        when(merchantAccessService.require(MERCHANT_OPERATOR, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(MERCHANT_OPERATOR, 11L,
                        MERCHANT, MerchantRoleCode.MERCHANT_CHECKIN));
        when(groupVerifyMapper.insertIgnoreCoopGroupVerify(any())).thenReturn(1);

        AjaxResult result = controller.redeemGroupCode(issuedBy(LEADER));

        assertEquals("已记录接待该团", result.get("msg"));
        ArgumentCaptor<CoopGroupVerify> captor = ArgumentCaptor.forClass(CoopGroupVerify.class);
        verify(groupVerifyMapper).insertIgnoreCoopGroupVerify(captor.capture());
        assertEquals(MERCHANT, captor.getValue().getMerchantMemberId());
        verify(merchantAccessService).require(MERCHANT_OPERATOR, MerchantPermission.VERIFY);
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals(MERCHANT, audit.getValue().getOwnerMemberId());
        assertEquals(MERCHANT_OPERATOR, audit.getValue().getActorMemberId());
        assertEquals(MerchantRoleCode.MERCHANT_CHECKIN, audit.getValue().getActorRoleCode());
    }

    @Test
    void groupCodeAuditFailureFailsTheTransactionalReceptionWrite() {
        doReturn(MERCHANT).when(controller).getAppUserId();
        when(groupVerifyMapper.insertIgnoreCoopGroupVerify(any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(0);

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> controller.redeemGroupCode(issuedBy(LEADER)));

        assertEquals("团码核销审计写入失败", error.getMessage());
    }

    /** 同一商家重复扫同一团码不重复记账，但前端必须能明确展示“已记录过”。 */
    @Test
    void redeem_repeatedCode_reportsAlreadyRecorded() {
        doReturn(MERCHANT).when(controller).getAppUserId();
        when(groupVerifyMapper.insertIgnoreCoopGroupVerify(any())).thenReturn(0);

        AjaxResult r = controller.redeemGroupCode(issuedBy(LEADER));

        assertEquals("已记录过", r.get("msg"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals(Boolean.TRUE, data.get("alreadyVerified"));
    }

    /** 同主题同领队的团必须按带队场次落账，不能再靠 topicId+leaderId 猜归属。 */
    @Test
    void redeem_v2CodePersistsActivityIdentity() {
        doReturn(MERCHANT).when(controller).getAppUserId();
        when(groupVerifyMapper.insertIgnoreCoopGroupVerify(any())).thenReturn(1);

        AjaxResult r = controller.redeemGroupCode(issuedByActivity(ACTIVITY, LEADER));

        assertEquals("已记录接待该团", r.get("msg"));
        ArgumentCaptor<CoopGroupVerify> cap = ArgumentCaptor.forClass(CoopGroupVerify.class);
        verify(groupVerifyMapper).insertIgnoreCoopGroupVerify(cap.capture());
        assertEquals(ACTIVITY, cap.getValue().getActivityId());
    }

    /** 同主题下另一家承接俱乐部的场次，不能因为领队在本俱乐部有资格就被串团核销。 */
    @Test
    void redeem_leaderFromOtherClubSession_rejected() {
        doReturn(MERCHANT).when(controller).getAppUserId();
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Arrays.asList(
                coopOrder("club", CLUB, null),
                coopOrder("club", OTHER_CLUB, null),
                coopOrder("merchant", MERCHANT, COOP_ORDER)));
        Club otherClub = new Club();
        otherClub.setId(OTHER_CLUB);
        otherClub.setMemberId(OUTSIDER);
        otherClub.setStatus(1L);
        otherClub.setDelFlag(0);
        when(clubMapper.selectClubById(OTHER_CLUB)).thenReturn(otherClub);
        when(clubMemberMapper.selectByClubAndMember(OTHER_CLUB, LEADER)).thenReturn(null);

        AjaxResult r = controller.redeemGroupCode(issuedByActivity(OTHER_ACTIVITY, LEADER));

        assertTrue(String.valueOf(r.get("msg")).contains("不再具备场次团码权限"));
        verify(groupVerifyMapper, never()).insertIgnoreCoopGroupVerify(any());
    }

    /** ★越权面:没有生效合作单的商家扫团码 ⇒ 拒,且不落台账。 */
    @Test
    void redeem_merchantWithoutCoopOrder_rejected() {
        doReturn(OUTSIDER).when(controller).getAppUserId();

        AjaxResult r = controller.redeemGroupCode(issuedBy(LEADER));

        assertTrue(String.valueOf(r.get("msg")).contains("没有生效的合作单"));
        verify(groupVerifyMapper, never()).insertIgnoreCoopGroupVerify(any());
    }

    /** 软删场次不可继续扫旧团码，避免写入无法在履约视图解释的结算台账。 */
    @Test
    void redeem_deletedActivity_rejected() {
        doReturn(MERCHANT).when(controller).getAppUserId();
        activity.setDelFlag(2);

        AjaxResult r = controller.redeemGroupCode(issuedBy(LEADER));

        assertTrue(String.valueOf(r.get("msg")).contains("团码场次无效"));
        verify(groupVerifyMapper, never()).insertIgnoreCoopGroupVerify(any());
    }

    /** ★出码方资格以【现在的库】为准:签名只证明「这串是我们发的」,不证明「他现在还是领队」。 */
    @Test
    void redeem_leaderNoLongerOrganizer_rejected() {
        doReturn(MERCHANT).when(controller).getAppUserId();
        String code = issuedBy(ADMIN);
        when(clubRoleAssignmentMapper.selectActiveForAccess(
                eq(CLUB), eq(ADMIN), eq(ACTIVITY), any(Date.class)))
                .thenReturn(Collections.emptyList());

        AjaxResult r = controller.redeemGroupCode(code);

        assertTrue(String.valueOf(r.get("msg")).contains("不再具备场次团码权限"));
        verify(groupVerifyMapper, never()).insertIgnoreCoopGroupVerify(any());
    }

    /** 拿玩家的据点码来当团码扫 ⇒ 拒(不判 type 前缀就会把别的码当团码解)。 */
    @Test
    void redeem_nonGroupCode_rejected() {
        doReturn(MERCHANT).when(controller).getAppUserId();
        String citynode = VerifyDynCodeUtil.issue(
                TOPIC, "citynode_" + LEADER, System.currentTimeMillis(), 60_000L, 1L, SECRET);

        AjaxResult r = controller.redeemGroupCode(citynode);

        assertTrue(String.valueOf(r.get("msg")).contains("非团核销码"));
        verify(groupVerifyMapper, never()).insertIgnoreCoopGroupVerify(any());
    }

    /** 旧团码没有场次身份，升级后必须要求重新出码，不能继续以 topic+leader 猜归属。 */
    @Test
    void redeem_legacyGroupCode_rejectedForReissue() {
        doReturn(MERCHANT).when(controller).getAppUserId();
        String legacy = VerifyDynCodeUtil.issue(
                TOPIC, "group_" + LEADER, System.currentTimeMillis(), 60_000L, 1L, SECRET);

        AjaxResult r = controller.redeemGroupCode(legacy);

        assertTrue(String.valueOf(r.get("msg")).contains("版本已过期"));
        verify(groupVerifyMapper, never()).insertIgnoreCoopGroupVerify(any());
    }

    /** 伪造/篡改的码过不了 HMAC。 */
    @Test
    void redeem_forgedCode_rejected() {
        doReturn(MERCHANT).when(controller).getAppUserId();
        String forged = VerifyDynCodeUtil.issue(
                TOPIC, "group_v2_" + ACTIVITY + "_" + LEADER, System.currentTimeMillis(), 60_000L, 1L, "wrong-secret");

        AjaxResult r = controller.redeemGroupCode(forged);

        verify(groupVerifyMapper, never()).insertIgnoreCoopGroupVerify(any());
        assertEquals(500, r.get("code"));
    }

    /** 过期的团码 ⇒ 拒(TTL 防拍照转发)。 */
    @Test
    void redeem_expiredCode_rejected() {
        doReturn(MERCHANT).when(controller).getAppUserId();
        String stale = VerifyDynCodeUtil.issue(
                TOPIC, "group_v2_" + ACTIVITY + "_" + LEADER, System.currentTimeMillis() - 600_000L, 60_000L, 1L, SECRET);

        AjaxResult r = controller.redeemGroupCode(stale);

        verify(groupVerifyMapper, never()).insertIgnoreCoopGroupVerify(any());
        assertEquals(500, r.get("code"));
    }

    /** 未登录 ⇒ 拒。 */
    @Test
    void redeem_notLoggedIn_rejected() {
        doReturn(0L).when(controller).getAppUserId();

        AjaxResult r = controller.redeemGroupCode(issuedBy(LEADER));

        assertEquals(500, r.get("code"));
        verify(groupVerifyMapper, never()).insertIgnoreCoopGroupVerify(any());
    }

    /** 没有承接俱乐部的主题(组织槽为空)⇒ 压根不该有团码这回事。 */
    @Test
    void redeem_topicWithoutOrganizer_rejected() {
        doReturn(MERCHANT).when(controller).getAppUserId();
        String code = issuedBy(LEADER);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC))
                .thenReturn(Collections.singletonList(coopOrder("merchant", MERCHANT, COOP_ORDER)));

        AjaxResult r = controller.redeemGroupCode(code);

        assertTrue(String.valueOf(r.get("msg")).contains("不再具备场次团码权限"));
        verify(groupVerifyMapper, never()).insertIgnoreCoopGroupVerify(any());
    }

    // ------------------------------------------------------------------ 夹具

    /** 用指定领队身份签一个当下有效的团码。 */
    private String issuedBy(Long leaderMemberId) {
        return VerifyDynCodeUtil.issue(TOPIC, "group_v2_" + ACTIVITY + "_" + leaderMemberId,
                System.currentTimeMillis(), 300_000L, 7L, SECRET);
    }

    private String issuedByActivity(Long activityId, Long leaderMemberId) {
        return VerifyDynCodeUtil.issue(TOPIC, "group_v2_" + activityId + "_" + leaderMemberId,
                System.currentTimeMillis(), 300_000L, 8L, SECRET);
    }

    private static CoopOrder coopOrder(String toType, Long toId, Long id) {
        CoopOrder o = new CoopOrder();
        o.setId(id);
        o.setToType(toType);
        o.setToId(toId);
        return o;
    }

    private static ClubMember clubMember(Long memberId, int role, int status) {
        ClubMember cm = new ClubMember();
        cm.setId(memberId + 1000L);
        cm.setClubId(CLUB);
        cm.setMemberId(memberId);
        cm.setRole(role);
        cm.setStatus(status);
        cm.setDelFlag(0);
        return cm;
    }

    private static ClubRoleAssignment eventRole(Long memberId, Long activityId) {
        ClubRoleAssignment assignment = new ClubRoleAssignment();
        assignment.setId(990L);
        assignment.setClubId(CLUB);
        assignment.setTargetMemberId(memberId);
        assignment.setRoleCode(ClubRoleCode.EVENT_CHECKIN);
        assignment.setScopeType(ClubRoleAssignment.SCOPE_EVENT);
        assignment.setScopeId(activityId);
        assignment.setStatus(ClubRoleAssignment.STATUS_ACTIVE);
        return assignment;
    }

    /** CU-C-78:签发团码必须真出图,出图失败现在直接报错;单测里给一个可用的出码服务。 */
    private static IQrcodeService stubbedQrcode() {
        IQrcodeService qrcode = org.mockito.Mockito.mock(IQrcodeService.class);
        org.mockito.Mockito.when(qrcode.generateInlineQrcode(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("https://oss.test/qr/group.png");
        return qrcode;
    }
}

package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.domain.ViewRegistrationMerchant;
import com.chengyinhub.business.service.impl.ProjectHomeReadServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CU-M-92:只接了合作邀约、还没有自己站点的受邀商家,在 /api/project/home 里算不算承接方。
 *
 * <p>「接受邀约」真正落的是 coop_invite.status=1 + 一张 coop_order(商务条款),
 * 从不建 cms_registration_merchant —— 所以这层关系既不能继续被「你既不是该主题的发布者,
 * 也没有承接记录」挡在门外,也不许反过来给他编一条并不存在的报名/点位。
 * 两头都要看:认出关系 + 不造数据 + 别人别跟着沾光。
 *
 * ⚠️ 邀约查询用 thenAnswer 按探针条件过滤内存表,模拟 mapper 的 WHERE:
 * 少了 topicId / toType / toId / status 里任何一个条件,对应的负控用例当场转红。
 */
class ProjectHomeInvitedPartnerTest {

    private static final Long HOST = 10L;
    private static final Long INVITED = 20L;
    private static final Long OTHER_MERCHANT = 21L;
    private static final Long STRANGER = 99L;
    private static final Long TOPIC = 300L;
    private static final Long CLUB_ACCEPTED = 400L;
    private static final Long CLUB_PENDING = 401L;
    private static final Long INVITE_ID = 990026L;
    private static final Long OTHER_INVITE_ID = 900001L;

    private ICmsTopicService topicSvc;
    private ICmsTopicNodeService nodeSvc;
    private ICmsRegistrationMerchantService regMerchantSvc;
    private ICmsRegistrationService regSvc;
    private ICoopInviteService inviteSvc;
    private IClubService clubSvc;
    private IMmsMerchantService merchantSvc;
    private IMerchantRegistrationDetailReadService detailSvc;
    private IMerchantChapterApplicationService chapterApplicationSvc;
    private ProjectHomeReadServiceImpl service;

    private final List<CoopInvite> invites = new ArrayList<CoopInvite>();
    private final List<CmsRegistrationMerchant> myRegistrations = new ArrayList<CmsRegistrationMerchant>();

    @BeforeEach
    void setUp() {
        topicSvc = mock(ICmsTopicService.class);
        nodeSvc = mock(ICmsTopicNodeService.class);
        regMerchantSvc = mock(ICmsRegistrationMerchantService.class);
        regSvc = mock(ICmsRegistrationService.class);
        inviteSvc = mock(ICoopInviteService.class);
        clubSvc = mock(IClubService.class);
        merchantSvc = mock(IMmsMerchantService.class);
        detailSvc = mock(IMerchantRegistrationDetailReadService.class);
        chapterApplicationSvc = mock(IMerchantChapterApplicationService.class);
        service = new ProjectHomeReadServiceImpl(topicSvc, nodeSvc, regMerchantSvc, regSvc,
                inviteSvc, clubSvc, merchantSvc, detailSvc, chapterApplicationSvc);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setName("外滩夜行");
        topic.setMemberId(HOST);
        topic.setStartDate(new Date(System.currentTimeMillis() + 5 * 86400000L));
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(nodeSvc.selectCmsTopicNodeList(any())).thenReturn(Collections.<CmsTopicNode>emptyList());
        when(regSvc.selectCmsRegistrationList(any())).thenReturn(Collections.<CmsRegistration>emptyList());
        when(chapterApplicationSvc.selectMine(anyLong())).thenReturn(Collections.<MerchantChapterApplication>emptyList());
        when(detailSvc.getRegistrationDetail(anyLong(), anyLong())).thenReturn(new ViewRegistrationMerchant());
        when(clubSvc.selectClubById(CLUB_ACCEPTED)).thenReturn(club(CLUB_ACCEPTED, "夜行者俱乐部", "13800000001"));
        when(clubSvc.selectClubById(CLUB_PENDING)).thenReturn(club(CLUB_PENDING, "待定俱乐部", "13800000002"));

        invites.add(acceptedMerchantInvite(INVITE_ID, INVITED, "20"));
        invites.add(acceptedMerchantInvite(OTHER_INVITE_ID, OTHER_MERCHANT, "30"));
        invites.add(clubInvite(CLUB_ACCEPTED, 1));
        invites.add(clubInvite(CLUB_PENDING, 0));

        when(inviteSvc.selectCoopInviteList(any())).thenAnswer(call -> matchInvites(call.getArgument(0)));
        when(regMerchantSvc.selectCmsRegistrationMerchantList(any()))
                .thenAnswer(call -> matchRegistrations(call.getArgument(0)));
    }

    private CoopInvite acceptedMerchantInvite(Long id, Long toId, String shareRate) {
        CoopInvite invite = merchantInvite(id, toId, 1);
        invite.setFromType("merchant");
        invite.setFromId(HOST);
        invite.setShareMode(1);
        invite.setShareRate(new BigDecimal(shareRate));
        invite.setHandleTime(new Date(1_700_000_000_000L));
        return invite;
    }

    /** inviteType=0:主题发布者 → 商家(整主题商务条款,不带点位)。 */
    private CoopInvite merchantInvite(Long id, Long toId, int status) {
        CoopInvite invite = new CoopInvite();
        invite.setId(id);
        invite.setInviteType(0);
        invite.setToType("merchant");
        invite.setToId(toId);
        invite.setTopicId(TOPIC);
        invite.setStatus(status);
        return invite;
    }

    private CoopInvite clubInvite(Long clubId, int status) {
        CoopInvite invite = new CoopInvite();
        invite.setId(clubId * 100);
        invite.setToType("club");
        invite.setToId(clubId);
        invite.setTopicId(TOPIC);
        invite.setStatus(status);
        return invite;
    }

    private Club club(Long id, String name, String phone) {
        Club club = new Club();
        club.setId(id);
        club.setName(name);
        club.setPhone(phone);
        return club;
    }

    private CmsRegistrationMerchant registration(Long id, Long memberId) {
        CmsRegistrationMerchant row = new CmsRegistrationMerchant();
        row.setId(id);
        row.setMemberId(memberId);
        row.setTopicId(TOPIC);
        row.setStatus(1L);
        row.setDelFlag(0);
        return row;
    }

    /** 按探针上「非空的等值条件」过滤内存表,模拟 mapper 的 WHERE。 */
    private List<CoopInvite> matchInvites(CoopInvite probe) {
        List<CoopInvite> out = new ArrayList<CoopInvite>();
        for (CoopInvite row : invites) {
            if (probe.getTopicId() != null && !Objects.equals(probe.getTopicId(), row.getTopicId())) continue;
            if (probe.getToType() != null && !Objects.equals(probe.getToType(), row.getToType())) continue;
            if (probe.getToId() != null && !Objects.equals(probe.getToId(), row.getToId())) continue;
            if (probe.getStatus() != null && !Objects.equals(probe.getStatus(), row.getStatus())) continue;
            out.add(row);
        }
        return out;
    }

    private List<CmsRegistrationMerchant> matchRegistrations(CmsRegistrationMerchant probe) {
        List<CmsRegistrationMerchant> out = new ArrayList<CmsRegistrationMerchant>();
        for (CmsRegistrationMerchant row : myRegistrations) {
            if (probe.getTopicId() != null && !Objects.equals(probe.getTopicId(), row.getTopicId())) continue;
            if (probe.getMemberId() != null && !Objects.equals(probe.getMemberId(), row.getMemberId())) continue;
            out.add(row);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> joinOf(Map<String, Object> home) {
        return (Map<String, Object>) home.get("join");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> coopInviteOf(Map<String, Object> home) {
        return joinOf(home) == null ? null : (Map<String, Object>) joinOf(home).get("coopInvite");
    }

    // ==================== 认出这层关系 ====================

    @Test
    void acceptedInvite_alone_makesHimAJoinPartner_withoutFabricatingAStation() {
        Map<String, Object> home = service.getProjectHome(INVITED, TOPIC);

        assertEquals("join", home.get("role"), "已接受的邀约=承接方,该落承接视图而不是被拒");
        Map<String, Object> join = joinOf(home);
        assertNotNull(join, "承接方必须带 join 段");
        assertNull(join.get("registration"), "邀约不产生报名行,一条都不能编");
        assertNull(join.get("registrations"), "同上:凭空的多站列表也不行");
        assertNull(join.get("chapterApplications"), "没有章节申请就别造章节申请");

        Map<String, Object> coopInvite = coopInviteOf(home);
        assertNotNull(coopInvite, "受邀方这层关系要能被前端认出来");
        assertEquals(INVITE_ID, coopInvite.get("inviteId"));
        assertEquals(HOST, coopInvite.get("fromId"));
        assertTrue(String.valueOf(coopInvite.get("terms")).contains("20"), "条款取他自己那张邀约上的真值");
        // 日期在服务端定格式:塞进 Map 的 Date 走不到字段上的 @JsonFormat,
        // 到前端就是一串毫秒数(不写这条,页面上「达成时间」会渲染成 1700000000000)。
        assertTrue(String.valueOf(coopInvite.get("handleTime")).matches("\\d{4}-\\d{2}-\\d{2}"),
                "达成时间必须是 yyyy-MM-dd 字符串,实际: " + coopInvite.get("handleTime"));
        // 日期在服务端定格式:塞进 Map 的 Date 走不到字段上的 @JsonFormat,前端拿到的是串而不是毫秒数
        assertEquals("2023-11-15", String.valueOf(coopInvite.get("handleTime")));
    }

    @Test
    void inviteHandleDateUsesBeijingTimeRegardlessOfServerTimezone() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            for (String zone : new String[] {"UTC", "Asia/Shanghai", "America/Los_Angeles"}) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
                Map<String, Object> invite = coopInviteOf(service.getProjectHome(INVITED, TOPIC));
                assertEquals("2023-11-15", invite.get("handleTime"), zone);
            }
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    void acceptedInvite_joinExposesOnlySettledClubs() {
        Map<String, Object> join = joinOf(service.getProjectHome(INVITED, TOPIC));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clubs = (List<Map<String, Object>>) join.get("clubs");

        assertEquals(1, clubs.size(), "只有已谈成的俱乐部算合作关系");
        assertEquals(CLUB_ACCEPTED, clubs.get(0).get("clubId"));
        assertEquals("13800000001", clubs.get(0).get("phone"));
    }

    // ==================== 负控:不该沾光的人 ====================

    @Test
    void pendingInvite_doesNotOpenTheProject() {
        replaceInviteStatus(INVITE_ID, 0);
        assertRejected(INVITED);
    }

    @Test
    void rejectedInvite_doesNotOpenTheProject() {
        replaceInviteStatus(INVITE_ID, 2);
        assertRejected(INVITED);
    }

    @Test
    void withdrawnInvite_doesNotOpenTheProject() {
        replaceInviteStatus(INVITE_ID, 3);
        assertRejected(INVITED);
    }

    @Test
    void supersededInvite_doesNotOpenTheProject() {
        replaceInviteStatus(INVITE_ID, 4);
        assertRejected(INVITED);
    }

    @Test
    void expiredInvite_doesNotOpenTheProject() {
        replaceInviteStatus(INVITE_ID, 5);
        assertRejected(INVITED);
    }

    @Test
    void legacyMerchantNodeInvite_staysReadOnly_andDoesNotOpenTheProject() {
        invites.removeIf(row -> Objects.equals(INVITE_ID, row.getId()));
        CoopInvite legacy = merchantInvite(INVITE_ID, INVITED, 1);
        legacy.setInviteType(CoopInvite.TYPE_LEGACY_MERCHANT_NODE);
        invites.add(legacy);
        assertRejected(INVITED);
    }

    @Test
    void stranger_isStillRejected() {
        assertRejected(STRANGER);
    }

    @Test
    void anotherMerchantsInvite_isNotMine() {
        // 同主题另一家受邀商家拿的是他自己的那张单:两条邀约互不串,条款各是各的。
        assertEquals(OTHER_INVITE_ID, coopInviteOf(service.getProjectHome(OTHER_MERCHANT, TOPIC)).get("inviteId"));
        invites.removeIf(row -> Objects.equals(OTHER_INVITE_ID, row.getId()));
        assertRejected(OTHER_MERCHANT);
    }

    // ==================== 既有身份不被改动 ====================

    @Test
    void publisher_staysHost_evenIfHeAlsoAcceptedAnInvite() {
        invites.add(acceptedMerchantInvite(900002L, HOST, "10"));
        Map<String, Object> home = service.getProjectHome(HOST, TOPIC);

        assertEquals("host", home.get("role"));
        assertNotNull(home.get("host"));
        assertNull(home.get("join"), "主办方不因受邀方关系被降级成 join");
    }

    @Test
    void merchantWithRegistration_keepsTheExistingJoinShape() {
        myRegistrations.add(registration(777L, INVITED));
        Map<String, Object> home = service.getProjectHome(INVITED, TOPIC);

        assertEquals("join", home.get("role"));
        Map<String, Object> join = joinOf(home);
        assertNotNull(join.get("registration"), "有报名行时仍走原来那条完整承接链路");
        assertNull(join.get("coopInvite"), "承接视图已经有内容,不再多挂一层邀约事实");
    }

    private void replaceInviteStatus(Long id, int status) {
        invites.removeIf(row -> Objects.equals(id, row.getId()));
        invites.add(merchantInvite(id, INVITED, status));
    }

    private void assertRejected(Long memberId) {
        RuntimeException error = assertThrows(RuntimeException.class, () -> service.getProjectHome(memberId, TOPIC));
        assertEquals("你既不是该主题的发布者,也没有承接记录", error.getMessage());
    }
    @Test
    void freeChapterAcceptedLivePointsCountWithoutLegacyRegistrations() {
        CmsTopic topic = topicSvc.selectCmsTopicById(TOPIC);
        topic.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        MerchantChapterApplication live = new MerchantChapterApplication();
        live.setChapterId(51L); live.setMerchantId(INVITED); live.setStatus(1); live.setOfferActive(true);
        MerchantChapterApplication pending = new MerchantChapterApplication();
        pending.setChapterId(52L); pending.setMerchantId(OTHER_MERCHANT); pending.setStatus(0);
        MerchantChapterApplication withdrawn = new MerchantChapterApplication();
        withdrawn.setChapterId(53L); withdrawn.setMerchantId(OTHER_MERCHANT); withdrawn.setStatus(1); withdrawn.setOfferActive(false);
        when(chapterApplicationSvc.selectForTopic(TOPIC, HOST)).thenReturn(java.util.Arrays.asList(live, pending, withdrawn));
        CmsTopicNode accepted = new CmsTopicNode();
        accepted.setId(71L); accepted.setChapterId(51L); accepted.setMerchantMemberId(INVITED); accepted.setNodeAuditStatus(1);
        CmsTopicNode unreviewed = new CmsTopicNode();
        unreviewed.setId(72L); unreviewed.setChapterId(51L); unreviewed.setMerchantMemberId(INVITED); unreviewed.setNodeAuditStatus(0);
        CmsTopicNode stale = new CmsTopicNode();
        stale.setId(73L); stale.setChapterId(53L); stale.setMerchantMemberId(OTHER_MERCHANT); stale.setNodeAuditStatus(1);
        when(nodeSvc.selectCmsTopicNodeList(any())).thenReturn(java.util.Arrays.asList(accepted, unreviewed, stale));
        Map<String, Object> host = (Map<String, Object>) service.getProjectHome(HOST, TOPIC).get("host");
        Map<String, Object> recruit = (Map<String, Object>) host.get("recruit");
        assertEquals(1, recruit.get("nodeFilled"));
        assertEquals(2, recruit.get("pendingCount")); // 章节申请 + 既有待确认俱乐部邀请
        assertEquals(2, ((List<?>) recruit.get("openNodes")).size());
    }

}

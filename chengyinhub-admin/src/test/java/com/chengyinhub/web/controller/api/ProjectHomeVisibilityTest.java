package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.domain.ViewRegistrationMerchant;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMerchantRegistrationDetailReadService;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.impl.ProjectHomeReadServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 项目主页的身份判定与联系方式可见范围。
 *
 * 这个接口一次把主办方/承接方两套数据连同**别人的电话号码**吐出去,
 * 判据不是「有没有掩码」而是「号是谁的、谁在看」:
 *   主办方 → 他招来的商家和俱乐部,给
 *   承接商家 → 只给已谈成的俱乐部;同场其他商家一律不给(2026-08-04 用户决策)
 *   任何人 → 玩家个人信息,不给
 *
 * ⚠️ 负控已实测:逐条放开对应闸 ⇒ 对应用例转红。
 */
class ProjectHomeVisibilityTest {

    private static final Long HOST = 10L;
    private static final Long MERCHANT_A = 20L;
    private static final Long MERCHANT_B = 21L;
    private static final Long STRANGER = 99L;
    private static final Long TOPIC = 300L;
    private static final Long CLUB_ACCEPTED = 400L;
    private static final Long CLUB_PENDING = 401L;

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

        CmsTopicNode n1 = node(501L, "第 1 站");
        CmsTopicNode n2 = node(502L, "第 2 站");
        when(nodeSvc.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(n1, n2));
        when(nodeSvc.selectCmsTopicNodeById(501L)).thenReturn(n1);
        when(nodeSvc.selectCmsTopicNodeById(502L)).thenReturn(n2);

        when(regSvc.selectCmsRegistrationList(any())).thenReturn(Collections.<CmsRegistration>emptyList());
        when(inviteSvc.selectCoopInviteList(any())).thenReturn(Arrays.asList(
                invite("club", CLUB_ACCEPTED, 1, 1, 20),
                invite("club", CLUB_PENDING, 0, 0, null),
                invite("club", 402L, 2, 1, 30)      // 已拒绝 → 不算合作方
        ));
        when(clubSvc.selectClubById(CLUB_ACCEPTED)).thenReturn(club(CLUB_ACCEPTED, "夜行者俱乐部", "张明", "13800000001"));
        when(clubSvc.selectClubById(CLUB_PENDING)).thenReturn(club(CLUB_PENDING, "待定俱乐部", "李四", "13800000002"));
        when(clubSvc.selectClubById(402L)).thenReturn(club(402L, "被拒俱乐部", "王五", "13800000003"));

        when(merchantSvc.selectMmsMerchantByMemberId(MERCHANT_A)).thenReturn(merchant("幸会咖啡", "13900000001"));
        when(merchantSvc.selectMmsMerchantByMemberId(MERCHANT_B)).thenReturn(merchant("隔壁书店", "13900000002"));
        when(detailSvc.getRegistrationDetail(anyLong(), anyLong())).thenReturn(new ViewRegistrationMerchant());
        when(chapterApplicationSvc.selectMine(anyLong())).thenReturn(Collections.<MerchantChapterApplication>emptyList());
    }

    private CmsTopicNode node(Long id, String name) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id);
        n.setTopicId(TOPIC);
        n.setName(name);
        return n;
    }

    private Club club(Long id, String name, String leader, String phone) {
        Club c = new Club();
        c.setId(id);
        c.setName(name);
        c.setLeaderName(leader);
        c.setPhone(phone);
        return c;
    }

    private MmsMerchant merchant(String name, String phone) {
        MmsMerchant m = new MmsMerchant();
        m.setName(name);
        m.setPhone(phone);
        return m;
    }

    private CoopInvite invite(String toType, Long toId, Integer status, Integer shareMode, Integer rate) {
        CoopInvite i = new CoopInvite();
        i.setTopicId(TOPIC);
        i.setToType(toType);
        i.setToId(toId);
        i.setStatus(status);
        i.setShareMode(shareMode);
        if (rate != null) {
            i.setShareRate(new java.math.BigDecimal(rate));
        }
        return i;
    }

    private CmsRegistrationMerchant reg(Long id, Long memberId, Long nodeId, Long status) {
        CmsRegistrationMerchant r = new CmsRegistrationMerchant();
        r.setId(id);
        r.setMemberId(memberId);
        r.setTopicId(TOPIC);
        r.setNodeId(nodeId);
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    private MerchantChapterApplication chapterApplication(Long id, Long topicId, Long chapterId,
                                                           int status, boolean offerActive) {
        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setId(id);
        application.setTopicId(topicId);
        application.setChapterId(chapterId);
        application.setMerchantId(MERCHANT_A);
        application.setStatus(status);
        application.setOfferActive(offerActive);
        return application;
    }

    /** 全主题的报名(候选池口径),以及「某人自己的报名」两种查询都从这里出。 */
    private void givenRegistrations(List<CmsRegistrationMerchant> all) {
        // 用 doAnswer 而不是 when(...).thenAnswer:同一个用例里二次打桩时,
        // when(mock.method(any())) 会先真的调一次已有的桩(argument=null)导致 NPE。
        org.mockito.Mockito.doAnswer(inv -> {
            CmsRegistrationMerchant probe = inv.getArgument(0);
            if (probe == null || probe.getMemberId() == null) {
                return all;
            }
            List<CmsRegistrationMerchant> mine = new ArrayList<>();
            for (CmsRegistrationMerchant r : all) {
                if (probe.getMemberId().equals(r.getMemberId())) {
                    mine.add(r);
                }
            }
            return mine;
        }).when(regMerchantSvc).selectCmsRegistrationMerchantList(any());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sub(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    // ==================== 身份判定 ====================

    @Test
    @Timeout(5)
    void publisher_getsHostRole() {
        givenRegistrations(Collections.<CmsRegistrationMerchant>emptyList());
        Map<String, Object> out = service.getProjectHome(HOST, TOPIC);
        assertEquals("host", out.get("role"));
        assertNotNull(out.get("host"));
        assertNull(out.get("join"), "没接过这条路线就不该有承接段");
    }

    @Test
    @Timeout(5)
    void merchantOwnedTopic_hostOwnerTypeIsMerchant() {
        givenRegistrations(Collections.<CmsRegistrationMerchant>emptyList());
        Map<String, Object> out = service.getProjectHome(HOST, TOPIC);
        assertEquals("merchant", sub(out, "host").get("ownerType"),
                "商家/个人主办的主题,host 段必须标 ownerType=merchant");
    }

    @Test
    @Timeout(5)
    void clubOwnedTopic_hostOwnerTypeIsClub() {
        givenRegistrations(Collections.<CmsRegistrationMerchant>emptyList());
        CmsTopic clubTopic = new CmsTopic();
        clubTopic.setId(TOPIC);
        clubTopic.setName("外滩夜行");
        clubTopic.setMemberId(HOST);
        clubTopic.setClubId(9L);
        clubTopic.setStartDate(new Date(System.currentTimeMillis() + 5 * 86400000L));
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(clubTopic);
        Map<String, Object> out = service.getProjectHome(HOST, TOPIC);
        assertEquals("club", sub(out, "host").get("ownerType"),
                "俱乐部主办的主题,host 段必须标 ownerType=club(前端据此隐藏俱乐部协作区块)");
    }

    @Test
    @Timeout(5)
    void merchantWithRegistration_getsJoinRole() {
        givenRegistrations(Collections.singletonList(reg(900L, MERCHANT_A, 501L, 1L)));
        Map<String, Object> out = service.getProjectHome(MERCHANT_A, TOPIC);
        assertEquals("join", out.get("role"));
        assertNull(out.get("host"), "承接方不得拿到主办段");
        assertNotNull(out.get("join"));
    }

    @Test
    @Timeout(5)
    void historicalProjectWithApprovedActiveChapterOffer_getsJoinRoleWithoutLegacyRegistration() {
        givenRegistrations(Collections.<CmsRegistrationMerchant>emptyList());
        CmsTopic historical = new CmsTopic();
        historical.setId(TOPIC);
        historical.setName("已结束的历史项目");
        historical.setMemberId(HOST);
        historical.setStatus(1);
        historical.setUserStatus(0);
        historical.setLifecycle(6);
        historical.setStartDate(new Date(System.currentTimeMillis() - 7 * 86400000L));
        historical.setRecruitDeadline(new Date(System.currentTimeMillis() - 14 * 86400000L));
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(historical);
        MerchantChapterApplication application = chapterApplication(700L, TOPIC, 701L,
                MerchantChapterApplication.STATUS_APPROVED, true);
        when(chapterApplicationSvc.selectMine(MERCHANT_A)).thenReturn(Collections.singletonList(application));

        Map<String, Object> out = service.getProjectHome(MERCHANT_A, TOPIC);

        assertEquals("join", out.get("role"));
        assertNull(out.get("host"));
        assertEquals(Collections.singletonList(application), list(sub(out, "join"), "chapterApplications"));
        assertNull(sub(out, "join").get("registration"), "章节承接不能伪造成旧报名记录");
    }

    @Test
    @Timeout(5)
    void approvedApplicationCanEnterRecruitingTopicBeforeOfferExists() {
        givenRegistrations(Collections.<CmsRegistrationMerchant>emptyList());
        CmsTopic recruiting = new CmsTopic();
        recruiting.setId(TOPIC);
        recruiting.setName("E2E 探店日一期");
        recruiting.setMemberId(HOST);
        recruiting.setStatus(1);
        recruiting.setUserStatus(1);
        recruiting.setMerchantStatus(1);
        recruiting.setLifecycle(1);
        recruiting.setStartDate(new Date(System.currentTimeMillis() + 7 * 86400000L));
        recruiting.setRecruitDeadline(new Date(System.currentTimeMillis() + 2 * 86400000L));
        recruiting.setMerchantSignUpEndDate(new Date(System.currentTimeMillis() + 2 * 86400000L));
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(recruiting);
        MerchantChapterApplication application = chapterApplication(5L, TOPIC, 701L,
                MerchantChapterApplication.STATUS_APPROVED, false);
        when(chapterApplicationSvc.selectMine(MERCHANT_A)).thenReturn(Collections.singletonList(application));

        Map<String, Object> out = service.getProjectHome(MERCHANT_A, TOPIC);

        assertEquals("join", out.get("role"));
        assertEquals(Collections.singletonList(application), list(sub(out, "join"), "chapterApplications"));

        recruiting.setStartDate(new Date(System.currentTimeMillis() - 86400000L));
        recruiting.setLifecycle(7);
        assertEquals("join", service.getProjectHome(MERCHANT_A, TOPIC).get("role"),
                "仍在招商的立项待审主题不能按计划日期冻结已批准商家的供给入口");
        recruiting.setLifecycle(1);
        assertEquals("join", service.getProjectHome(MERCHANT_A, TOPIC).get("role"));
        recruiting.setLifecycle(2);
        assertRejectedByVisibilityGate(MERCHANT_A, "已锁价主题不能靠无 offer 申请补建供给");
    }

    @Test
    @Timeout(5)
    void approvedApplicationWithoutOfferDoesNotReopenUnavailableHistoricalTopic() {
        givenRegistrations(Collections.<CmsRegistrationMerchant>emptyList());
        MerchantChapterApplication application = chapterApplication(5L, TOPIC, 701L,
                MerchantChapterApplication.STATUS_APPROVED, false);
        when(chapterApplicationSvc.selectMine(MERCHANT_A)).thenReturn(Collections.singletonList(application));

        CmsTopic unavailable = new CmsTopic();
        unavailable.setId(TOPIC);
        unavailable.setName("历史项目");
        unavailable.setMemberId(HOST);
        unavailable.setStatus(1);
        unavailable.setUserStatus(0);
        unavailable.setLifecycle(1);
        unavailable.setStartDate(new Date(System.currentTimeMillis() + 7 * 86400000L));
        unavailable.setRecruitDeadline(new Date(System.currentTimeMillis() + 2 * 86400000L));
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(unavailable);
        assertRejectedByVisibilityGate(MERCHANT_A, "已下架项目不能靠一条无 offer 的旧申请重开");

        unavailable.setUserStatus(1);
        unavailable.setRecruitDeadline(new Date(System.currentTimeMillis() - 1));
        assertRejectedByVisibilityGate(MERCHANT_A, "已过招商截止不能靠无 offer 申请继续进入");

        unavailable.setRecruitDeadline(new Date(System.currentTimeMillis() + 2 * 86400000L));
        unavailable.setLifecycle(4);
        unavailable.setStartDate(new Date(System.currentTimeMillis() - 86400000L));
        assertRejectedByVisibilityGate(MERCHANT_A, "已开始项目不能靠无 offer 申请补建供给");
    }

    @Test
    @Timeout(5)
    void inactiveOrUnapprovedChapterOffer_doesNotGrantProjectHome() {
        givenRegistrations(Collections.<CmsRegistrationMerchant>emptyList());
        when(chapterApplicationSvc.selectMine(MERCHANT_A)).thenReturn(Arrays.asList(
                chapterApplication(700L, TOPIC, 701L, MerchantChapterApplication.STATUS_PENDING, true),
                chapterApplication(701L, TOPIC, 702L, MerchantChapterApplication.STATUS_APPROVED, false)));

        assertRejectedByVisibilityGate(MERCHANT_A, "未生效或未通过的章节 offer 不给项目主页");
    }

    /**
     * 只断言"抛了 RuntimeException"挡不住东西:被测方法里一个 NPE 同样让它绿。
     * 必须断言是哪道闸 —— 可见性拒绝的文案是固定的那一句。
     */
    private void assertRejectedByVisibilityGate(Long merchantId, String why) {
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.getProjectHome(merchantId, TOPIC), why);
        assertEquals("你既不是该主题的发布者,也没有承接记录", thrown.getMessage(), why);
    }

    /** 自己发的主题自己也接了一站:按 host,承接信息作为其中一块。 */
    @Test
    @Timeout(5)
    void publisherWhoAlsoRegistered_getsHostWithJoinBlock() {
        givenRegistrations(Collections.singletonList(reg(900L, HOST, 501L, 1L)));
        Map<String, Object> out = service.getProjectHome(HOST, TOPIC);
        assertEquals("host", out.get("role"));
        assertNotNull(out.get("host"));
        assertNotNull(out.get("join"), "自己接的那一站也要给出来");
    }

    /** 既不是发布者也没报名 = 外人,项目主页是管理页不是浏览页。 */
    @Test
    @Timeout(5)
    void stranger_rejected() {
        givenRegistrations(Collections.<CmsRegistrationMerchant>emptyList());
        assertThrows(RuntimeException.class, () -> service.getProjectHome(STRANGER, TOPIC));
    }

    // ==================== 联系方式可见范围 ====================

    /** 主办方看得到他招来的商家和俱乐部的电话 —— 履约要靠他去催。 */
    @Test
    @Timeout(5)
    void host_seesMerchantAndClubPhones() {
        givenRegistrations(Arrays.asList(reg(900L, MERCHANT_A, 501L, 1L), reg(901L, MERCHANT_B, 502L, 0L)));
        Map<String, Object> host = sub(service.getProjectHome(HOST, TOPIC), "host");

        List<Map<String, Object>> merchants = list(host, "merchants");
        assertEquals(2, merchants.size());
        assertEquals("13900000001", merchants.get(0).get("phone"));

        List<Map<String, Object>> clubs = list(host, "clubs");
        assertEquals(2, clubs.size(), "已接受 + 待确认都要列给主办方,已拒绝的不列");
        assertEquals("13800000001", clubs.get(0).get("phone"));
        assertEquals("张明", clubs.get(0).get("leaderName"));
    }

    /**
     * ★承接商家看不到同场其他商家 —— 用户 2026-08-04 决策。
     * 这是本接口最容易漏的一条:host 段里有全部商家电话,一旦身份判错就是整张通讯录泄出去。
     */
    @Test
    @Timeout(5)
    void joinMerchant_cannotSeeOtherMerchants() {
        givenRegistrations(Arrays.asList(reg(900L, MERCHANT_A, 501L, 1L), reg(901L, MERCHANT_B, 502L, 1L)));
        Map<String, Object> out = service.getProjectHome(MERCHANT_A, TOPIC);

        assertNull(out.get("host"), "承接方拿到 host 段就等于拿到同场所有商家的电话");
        assertFalse(String.valueOf(out).contains("13900000002"), "隔壁商家的号码不得出现在承接方的响应里");
    }

    /** 承接商家只看得到已谈成的俱乐部;待确认的还没答应,不能把人家号码给出去。 */
    @Test
    @Timeout(5)
    void joinMerchant_seesOnlyAcceptedClubPhone() {
        givenRegistrations(Collections.singletonList(reg(900L, MERCHANT_A, 501L, 1L)));
        Map<String, Object> join = sub(service.getProjectHome(MERCHANT_A, TOPIC), "join");

        List<Map<String, Object>> clubs = list(join, "clubs");
        assertEquals(1, clubs.size(), "只有已接受的那家");
        assertEquals("夜行者俱乐部", clubs.get(0).get("name"));
        assertEquals("13800000001", clubs.get(0).get("phone"));
        assertFalse(String.valueOf(join).contains("13800000002"), "待确认俱乐部的号码不得给出");
    }

    /** 玩家侧只给聚合量,不给个人信息。 */
    @Test
    @Timeout(5)
    void host_playersAggregateOnly() {
        givenRegistrations(Collections.<CmsRegistrationMerchant>emptyList());
        CmsRegistration p1 = new CmsRegistration();
        p1.setId(1L);
        when(regSvc.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(p1));

        Map<String, Object> players = sub(sub(service.getProjectHome(HOST, TOPIC), "host"), "players");
        assertEquals(1, players.get("paidCount"));
        assertEquals(1, players.size(), "玩家段只该有聚合量;多一个键就要重新审是不是把个人信息带出来了");
    }

    // ==================== 招商进度 / 自审标记 ====================

    @Test
    @Timeout(5)
    void recruitCountsFilledNodesAndPending() {
        givenRegistrations(Arrays.asList(
                reg(900L, MERCHANT_A, 501L, 1L),   // 已通过 → 501 占掉
                reg(901L, MERCHANT_B, 502L, 0L)    // 审核中 → 502 还空着,计入待处理
        ));
        Map<String, Object> recruit = sub(sub(service.getProjectHome(HOST, TOPIC), "host"), "recruit");

        assertEquals(2, recruit.get("nodeTotal"));
        assertEquals(1, recruit.get("nodeFilled"));
        assertEquals(2, recruit.get("pendingCount"), "1 条待审报名 + 1 条待确认邀约");
        assertEquals(1, list(recruit, "openNodes").size());
        assertEquals("第 2 站", list(recruit, "openNodes").get(0).get("nodeName"));
    }

    /** 自己报名自己的主题现码允许,但审的人就是被审的人,前端要能标出来。 */
    @Test
    @Timeout(5)
    void selfRegistrationIsFlagged() {
        givenRegistrations(Arrays.asList(reg(900L, HOST, 501L, 0L), reg(901L, MERCHANT_A, 502L, 0L)));
        List<Map<String, Object>> merchants = list(sub(service.getProjectHome(HOST, TOPIC), "host"), "merchants");

        assertEquals(Boolean.TRUE, merchants.get(0).get("isSelf"));
        assertEquals(Boolean.FALSE, merchants.get(1).get("isSelf"));
    }

    // ==================== 编辑窗口(与 /update 同口径) ====================

    @Test
    @Timeout(5)
    void canEditMirrorsUpdateGate() {
        givenRegistrations(Collections.singletonList(reg(900L, MERCHANT_A, 501L, 0L)));
        assertTrue((Boolean) sub(service.getProjectHome(MERCHANT_A, TOPIC), "join").get("canEdit"));

        givenRegistrations(Collections.singletonList(reg(900L, MERCHANT_A, 501L, 1L)));
        Map<String, Object> approved = sub(service.getProjectHome(MERCHANT_A, TOPIC), "join");
        assertEquals(Boolean.FALSE, approved.get("canEdit"));
        assertTrue(String.valueOf(approved.get("editReason")).contains("已通过审核"));

        CmsTopic started = new CmsTopic();
        started.setId(TOPIC);
        started.setMemberId(HOST);
        started.setStartDate(new Date(System.currentTimeMillis() - 86400000L));
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(started);
        givenRegistrations(Collections.singletonList(reg(900L, MERCHANT_A, 501L, 0L)));
        Map<String, Object> late = sub(service.getProjectHome(MERCHANT_A, TOPIC), "join");
        assertEquals(Boolean.FALSE, late.get("canEdit"));
        assertTrue(String.valueOf(late.get("editReason")).contains("主题已开始"));
    }
    @Test
    void chapterProjectCarriesServerLifecycleAndSaleState() {
        CmsTopic topic = topicSvc.selectCmsTopicById(TOPIC);
        topic.setLifecycle(3);
        topic.setStatus(1);
        topic.setUserStatus(0);
        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setId(701L);
        application.setTopicId(TOPIC);
        application.setChapterId(601L);
        application.setMerchantId(MERCHANT_A);
        application.setStatus(1);
        application.setOfferActive(true);
        when(chapterApplicationSvc.selectMine(MERCHANT_A)).thenReturn(Collections.singletonList(application));
        when(regMerchantSvc.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.emptyList());
        Map<String, Object> home = service.getProjectHome(MERCHANT_A, TOPIC);
        assertEquals("join", home.get("role"));
        Map<?, ?> mappedTopic = (Map<?, ?>) home.get("topic");
        assertEquals(3, mappedTopic.get("lifecycle"));
        assertEquals(0, mappedTopic.get("userStatus"));
        topic.setUserStatus(1);
        assertEquals(1, ((Map<?, ?>) service.getProjectHome(MERCHANT_A, TOPIC).get("topic")).get("userStatus"));
    }

}

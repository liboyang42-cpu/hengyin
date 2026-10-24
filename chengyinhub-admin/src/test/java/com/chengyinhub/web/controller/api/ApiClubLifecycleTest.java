package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPendingSettlement;
import com.chengyinhub.business.domain.ClubPendingDeposit;
import com.chengyinhub.business.domain.ClubDissolutionBlockers;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.ClubDissolutionDepositException;
import com.chengyinhub.business.service.ClubDissolutionSettlementException;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubLifecycleCommandService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.domain.vo.ClubMembershipAccessEndResultVO;
import com.chengyinhub.business.domain.vo.ClubLifecycleCommandResultVO;
import com.chengyinhub.business.service.impl.ClubLifecycleCommandServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Controller wiring contract：待审不能伪装成已加入，解散确认事实必须原样传给事务服务。 */
class ApiClubLifecycleTest {

    private static final Long CLUB_ID = 73L;
    private static final Long MEMBER_ID = 42L;

    private IClubService clubService;
    private IClubMemberService memberService;
    private IClubLifecycleCommandService lifecycleService;
    private ApiClubController controller;

    @Test
    void controllerMustCatchLifecycleFailuresOutsideTheTransactionBoundary() throws Exception {
        assertNull(ApiClubController.class.getMethod("removeMember", ClubMember.class)
                .getAnnotation(Transactional.class));
        assertNull(ApiClubController.class.getMethod("dissolve", Club.class)
                .getAnnotation(Transactional.class));
        assertNull(ApiClubController.class.getMethod("quit", Club.class)
                .getAnnotation(Transactional.class));
    }

    @BeforeEach
    void setUp() {
        clubService = mock(IClubService.class);
        memberService = mock(IClubMemberService.class);
        lifecycleService = mock(IClubLifecycleCommandService.class);
        ICapabilityService capabilityService = mock(ICapabilityService.class);
        when(capabilityService.hasCapability(anyLong(), org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        controller = spy(new ApiClubController());
        doReturn(MEMBER_ID).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubMemberService", memberService);
        ReflectionTestUtils.setField(controller, "clubLifecycleCommandService", lifecycleService);
        ReflectionTestUtils.setField(controller, "capabilityService", capabilityService);
        ReflectionTestUtils.setField(controller, "umsMemberService", org.mockito.Mockito.mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", org.mockito.Mockito.mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
    }

    private Club activeClub() {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(9L);
        club.setStatus(1L);
        return club;
    }

    @Test
    void clubListsPreserveApprovalPolicyInsteadOfShowingImmediateJoin() {
        ReflectionTestUtils.setField(controller, "clubLeaderService",
                mock(com.chengyinhub.business.service.IClubLeaderService.class));
        ReflectionTestUtils.setField(controller, "cmsTopicService",
                mock(com.chengyinhub.business.service.ICmsTopicService.class));
        Club owned = activeClub();
        Club nearby = activeClub();
        nearby.setId(74L);
        Club joined = activeClub();
        joined.setId(75L);
        when(clubService.selectClubsByMemberId(MEMBER_ID)).thenReturn(Arrays.asList(owned));
        when(memberService.selectClubIdsByMember(MEMBER_ID)).thenReturn(Arrays.asList(75L));
        when(clubService.selectClubById(75L)).thenReturn(joined);
        when(clubService.selectClubList(org.mockito.ArgumentMatchers.any(Club.class)))
                .thenReturn(Arrays.asList(nearby));

        for (boolean supported : new boolean[] {true, false}) {
            when(clubService.isJoinPolicySupported()).thenReturn(supported);
            when(clubService.selectJoinPolicy(anyLong())).thenReturn(1);
            int expected = supported ? 1 : 0;
            nearby.setJoinPolicy(null);
            List<?> listed = (List<?>) controller.list(new Club()).get("data");
            com.chengyinhub.business.domain.vo.PublicClubVO card =
                    (com.chengyinhub.business.domain.vo.PublicClubVO) listed.get(0);
            assertEquals(expected, card.getJoinPolicy());
            assertEquals(supported, card.getJoinPolicySupported());

            owned.setJoinPolicy(null);
            joined.setJoinPolicy(null);
            Map<?, ?> mine = (Map<?, ?>) controller.my().get("data");
            assertEquals(expected, ((Club) ((List<?>) mine.get("owned")).get(0)).getJoinPolicy());
            assertEquals(expected, ((Club) ((List<?>) mine.get("joined")).get(0)).getJoinPolicy());

            owned.setJoinPolicy(null);
            nearby.setJoinPolicy(null);
            joined.setJoinPolicy(null);
            Map<?, ?> home = (Map<?, ?>) controller.home().get("data");
            assertEquals(expected, ((Club) ((List<?>) home.get("owned")).get(0)).getJoinPolicy());
            assertEquals(expected, ((Club) ((List<?>) home.get("nearby")).get(0)).getJoinPolicy());
            assertEquals(expected, ((Club) ((List<?>) home.get("joined")).get(0)).getJoinPolicy());
        }
    }

    /** postCreate 在闸之后还要走 openid / 内容安全 / 落库,把那几个依赖补上。 */
    private void wirePostCreateDependencies() {
        // 发帖闸的第一道是「必须是成员」。除非某条用例专门测这一条,否则先把成员身份坐实,
        // 免得别的断言全被第一道闸挡下、测不到它自己要测的东西。
        when(memberService.selectByClubAndMember(anyLong(), anyLong())).thenReturn(new ClubMember());
        ReflectionTestUtils.setField(controller, "clubParticipationGuard",
                mock(com.chengyinhub.business.service.IClubParticipationGuard.class));
        com.chengyinhub.business.service.IUmsMemberService umsMemberService =
                mock(com.chengyinhub.business.service.IUmsMemberService.class);
        when(umsMemberService.selectUmsMemberById(anyLong())).thenReturn(null);
        ReflectionTestUtils.setField(controller, "umsMemberService", umsMemberService);
        com.chengyinhub.business.service.IWxContentSecurityService security =
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class);
        when(security.checkText(anyString(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(null);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(controller, "clubPostService",
                mock(com.chengyinhub.business.service.IClubPostService.class));
    }

    /** 稿 M 268:223「允许成员发帖」。★ 闸必须在接口上,前端藏入口不是权限。 */
    /** ★ 2026-09-10 用户裁决:在俱乐部发帖必须是俱乐部成员,商家在俱乐部内部发不了帖。 */
    @Test
    void postCreate_requiresMembership_soAMerchantOrStrangerCannotPostIntoAnyClub() {
        wirePostCreateDependencies();
        Club club = activeClub();                       // owner = 9L,当前登录是 42L
        when(clubService.selectClubById(CLUB_ID)).thenReturn(club);
        when(memberService.selectByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(null);   // 不是成员

        com.chengyinhub.business.domain.ClubPost post = new com.chengyinhub.business.domain.ClubPost();
        post.setClubId(CLUB_ID);
        post.setContent("我是路过的");

        assertEquals("加入俱乐部后才能在这里发帖", controller.postCreate(post).get("msg"),
                "此前这道闸只在前端,直接打接口谁都能往任意俱乐部发帖");

        // 正式成员照常发
        when(memberService.selectByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(new ClubMember());
        assertEquals(200, controller.postCreate(post).get("code"));
    }

    @Test
    void postCreate_ownerIsNotInClubMemberTable_soTheOwnerMustBeRecognisedSeparately() {
        wirePostCreateDependencies();
        Club club = activeClub();
        club.setMemberId(MEMBER_ID);                    // 当前登录就是主理人
        when(clubService.selectClubById(CLUB_ID)).thenReturn(club);
        when(memberService.selectByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(null);

        com.chengyinhub.business.domain.ClubPost post = new com.chengyinhub.business.domain.ClubPost();
        post.setClubId(CLUB_ID);
        post.setContent("今晚夜行");

        assertEquals(200, controller.postCreate(post).get("code"),
                "主理人不在 club_member 里,只按成员表判会把他自己挡在门外");
    }

    /** ★ 稿 M「俱乐部公开可见」关掉后:非成员搜不到、也打不开。只藏列表不挡详情等于没挡。 */
    @Test
    void publicVisibleOff_hidesTheClubFromListAndBlocksDetailForNonMembers() {
        Club closed = activeClub();                 // owner = 9L,当前登录 42L 不是成员
        closed.setPublicVisible(0);
        when(clubService.selectClubById(CLUB_ID)).thenReturn(closed);
        when(clubService.selectClubList(org.mockito.ArgumentMatchers.any(Club.class)))
                .thenReturn(new java.util.ArrayList<>(java.util.Arrays.asList(closed)));
        when(memberService.selectMembershipRecord(CLUB_ID, MEMBER_ID)).thenReturn(null);

        // list 里每条都会 attachLeaderLevel,那条路要的依赖跟本用例无关,给个桩免得 NPE
        ReflectionTestUtils.setField(controller, "clubLeaderService",
                mock(com.chengyinhub.business.service.IClubLeaderService.class));

        Club probe = new Club();
        probe.setId(CLUB_ID);
        assertEquals("这个俱乐部只对成员开放", controller.detail(probe).get("msg"));
        assertEquals(0, ((List<?>) controller.list(new Club()).get("data")).size(),
                "关掉公开可见之后,非成员的列表里不该还看得见它");

        // 在册成员照常看得见
        ClubMember active = new ClubMember();
        active.setStatus(1);
        when(memberService.selectMembershipRecord(CLUB_ID, MEMBER_ID)).thenReturn(active);
        assertEquals(1, ((List<?>) controller.list(new Club()).get("data")).size());

        // 列读到 null = 加这列之前的行为,必须仍然可见
        Club legacy = activeClub();
        when(clubService.selectClubList(org.mockito.ArgumentMatchers.any(Club.class)))
                .thenReturn(new java.util.ArrayList<>(java.util.Arrays.asList(legacy)));
        when(memberService.selectMembershipRecord(CLUB_ID, MEMBER_ID)).thenReturn(null);
        assertEquals(1, ((List<?>) controller.list(new Club()).get("data")).size(),
                "null 按可见算,否则迁移一落库存量俱乐部会集体消失");
    }

    /**
     * CU-C-98:小程序建团入口的 payload 里没有 leaderName ⇒ club.leader_name 为 NULL,
     * 俱乐部详情的「主理人」一格只剩占位符 —— 而主理人档案里本来就有名字。
     * 读出口兜底(与 level/tags 同一处),自己填过的名字不被覆盖。
     */
    @Test
    void leaderName_fallsBackToTheLeaderProfileWhenTheClubRowHasNone() {
        Club stored = activeClub();   // memberId = 9L,leader_name 没设过
        when(clubService.selectClubList(org.mockito.ArgumentMatchers.any(Club.class)))
                .thenReturn(new java.util.ArrayList<>(java.util.Arrays.asList(stored)));

        com.chengyinhub.business.service.IClubLeaderService leaderService =
                mock(com.chengyinhub.business.service.IClubLeaderService.class);
        com.chengyinhub.business.domain.ClubLeader leader = new com.chengyinhub.business.domain.ClubLeader();
        leader.setMemberId(9L);
        leader.setLeaderName("执行俱乐部主理人");
        when(leaderService.selectClubLeaderByMemberId(9L)).thenReturn(leader);
        ReflectionTestUtils.setField(controller, "clubLeaderService", leaderService);

        List<?> rows = (List<?>) controller.list(new Club()).get("data");
        assertEquals("执行俱乐部主理人",
                ((com.chengyinhub.business.domain.vo.PublicClubVO) rows.get(0)).getLeaderName(),
                "档案里有名字却显示占位符,用户会以为这个俱乐部没有负责人");

        // 自己填过的名字以 club 行为准,不被档案反向覆盖
        stored.setLeaderName("自己写的名字");
        rows = (List<?>) controller.list(new Club()).get("data");
        assertEquals("自己写的名字",
                ((com.chengyinhub.business.domain.vo.PublicClubVO) rows.get(0)).getLeaderName());
    }

    @Test
    void openSettings_merchantCoop_onlyOwnerAndTheResponseIsReadBackFromTheDatabase() {
        Club body = new Club();
        body.setId(CLUB_ID);
        body.setMerchantUndertakeOpen(0);

        Club after = activeClub();
        after.setMerchantUndertakeOpen(0);
        when(clubService.selectClubById(CLUB_ID)).thenReturn(after);

        AjaxResult ok = controller.updateMerchantUndertakeOpen(body);
        verify(clubService).updateMerchantUndertakeOpen(CLUB_ID, MEMBER_ID, false);
        assertEquals(0, ((Map<?, ?>) ok.get("data")).get("merchantUndertakeOpen"),
                "回的必须是回读到的值,不是请求里那个");

        Club bad = new Club();
        bad.setId(CLUB_ID);
        bad.setMerchantUndertakeOpen(7);
        assertEquals("开关值不合法", controller.updateMerchantUndertakeOpen(bad).get("msg"));
    }

    @Test
    void memberPostDisabled_blocksOrdinaryMemberButKeepsGovernorsWriting() {
        wirePostCreateDependencies();

        Club closed = activeClub();
        closed.setMemberPostAllowed(0);
        when(clubService.selectClubById(CLUB_ID)).thenReturn(closed);

        com.chengyinhub.business.domain.ClubPost post = new com.chengyinhub.business.domain.ClubPost();
        post.setClubId(CLUB_ID);
        post.setContent("今晚夜行");

        when(memberService.canGovernClub(closed, MEMBER_ID)).thenReturn(false);
        AjaxResult denied = controller.postCreate(post);
        assertEquals("这个俱乐部当前只有管理员能发帖", denied.get("msg"));

        // 管理者不受影响:同一份 body、同一个开关,只是身份不同
        when(memberService.canGovernClub(closed, MEMBER_ID)).thenReturn(true);
        AjaxResult allowed = controller.postCreate(post);
        assertEquals(200, allowed.get("code"), "关掉开关不该把管理员也一起挡住");
    }

    @Test
    void memberPostAllowedNullMeansOpen_soTheMigrationDoesNotSilentlyMuteEveryone() {
        wirePostCreateDependencies();

        Club legacy = activeClub();      // 旧行:这一列读回来是 null
        when(clubService.selectClubById(CLUB_ID)).thenReturn(legacy);
        when(memberService.canGovernClub(legacy, MEMBER_ID)).thenReturn(false);

        com.chengyinhub.business.domain.ClubPost post = new com.chengyinhub.business.domain.ClubPost();
        post.setClubId(CLUB_ID);
        post.setContent("今晚夜行");

        assertEquals(200, controller.postCreate(post).get("code"),
                "null 必须按开放算 —— 否则迁移一落库,全站成员当场发不了帖");
    }

    @Test
    void welcomeNote_trimmedLengthCheckedScannedSavedAndReadBack() {
        Club body = new Club();
        body.setId(CLUB_ID);
        body.setWelcomeNote("  欢迎！周六晚上一起夜行  ");
        when(clubService.selectWelcomeNote(CLUB_ID)).thenReturn("欢迎！周六晚上一起夜行");

        AjaxResult ok = controller.updateWelcomeNote(body);
        assertEquals(200, ok.get("code"));
        verify(clubService).updateWelcomeNote(CLUB_ID, MEMBER_ID, "欢迎！周六晚上一起夜行");
        assertEquals("欢迎！周六晚上一起夜行", ((Map<?, ?>) ok.get("data")).get("welcomeNote"), "回的是回读到的值");

        Club tooLong = new Club();
        tooLong.setId(CLUB_ID);
        tooLong.setWelcomeNote("长".repeat(201));
        assertEquals("欢迎语最多 200 字", controller.updateWelcomeNote(tooLong).get("msg"));

        Club empty = new Club();
        empty.setId(CLUB_ID);
        empty.setWelcomeNote("   ");
        assertEquals(200, controller.updateWelcomeNote(empty).get("code"), "清空欢迎语是合法操作");
        verify(clubService).updateWelcomeNote(CLUB_ID, MEMBER_ID, "");
    }

    @Test
    void welcomeNote_rejectedByContentSecurityIsNotSaved() {
        com.chengyinhub.business.service.IWxContentSecurityService security =
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IWxContentSecurityService.class);
        when(security.checkText(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn("内容含违规信息");
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        Club body = new Club();
        body.setId(CLUB_ID);
        body.setWelcomeNote("违规内容");
        assertEquals("内容含违规信息", controller.updateWelcomeNote(body).get("msg"));
        verify(clubService, org.mockito.Mockito.never()).updateWelcomeNote(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void welcomeNote_nonOwnerErrorFromServiceIsReturned() {
        org.mockito.Mockito.doThrow(new IllegalStateException("只有主理人可以修改"))
                .when(clubService).updateWelcomeNote(CLUB_ID, MEMBER_ID, "你好");
        Club body = new Club();
        body.setId(CLUB_ID);
        body.setWelcomeNote("你好");
        assertEquals("只有主理人可以修改", controller.updateWelcomeNote(body).get("msg"));
    }

    @Test
    void openSettings_onlyOwnerAndTheResponseIsReadBackFromTheDatabase() {
        Club body = new Club();
        body.setId(CLUB_ID);
        body.setMemberPostAllowed(0);

        Club after = activeClub();
        after.setMemberPostAllowed(0);
        when(clubService.selectClubById(CLUB_ID)).thenReturn(after);

        AjaxResult ok = controller.updateMemberPostAllowed(body);
        verify(clubService).updateMemberPostAllowed(CLUB_ID, MEMBER_ID, false);
        assertEquals(0, ((Map<?, ?>) ok.get("data")).get("memberPostAllowed"),
                "回的必须是回读到的值,不是请求里那个");

        Club bad = new Club();
        bad.setId(CLUB_ID);
        bad.setMemberPostAllowed(7);
        assertEquals("开关值不合法", controller.updateMemberPostAllowed(bad).get("msg"));

        org.mockito.Mockito.doThrow(new IllegalArgumentException("只有主理人可以修改开放设置"))
                .when(clubService).updateMemberPostAllowed(eq(CLUB_ID), eq(MEMBER_ID), eq(true));
        Club on = new Club();
        on.setId(CLUB_ID);
        on.setMemberPostAllowed(1);
        assertEquals("只有主理人可以修改开放设置", controller.updateMemberPostAllowed(on).get("msg"));
    }

    @Test
    void privateClubJoinReturnsPendingStateInsteadOfSuccessOnlyCopy() {
        when(clubService.selectClubById(CLUB_ID)).thenReturn(activeClub());
        when(memberService.requestJoin(CLUB_ID, MEMBER_ID, "朋友推荐来的")).thenReturn(IClubMemberService.JOIN_STATE_PENDING);

        Club body = new Club();
        body.setId(CLUB_ID);
        body.setJoinMessage("朋友推荐来的");
        AjaxResult result = controller.join(body);

        assertEquals(200, result.get("code"));
        assertEquals("申请已提交，等待主理人审核", result.get("msg"));
        assertEquals("pending", ((Map<?, ?>) result.get("data")).get("state"));
        verify(memberService).requestJoin(CLUB_ID, MEMBER_ID, "朋友推荐来的");
    }

    @Test
    void dissolvePassesBothConfirmationsAndReportsAffectedMemberCount() {
        ClubMembershipAccessEndResultVO accessEnd = new ClubMembershipAccessEndResultVO();
        accessEnd.setCancelledTermCount(2);
        when(lifecycleService.dissolve(eq(CLUB_ID), eq(MEMBER_ID), eq(true), eq(true), anyString()))
                .thenReturn(lifecycleResult(3, accessEnd));
        Club body = new Club();
        body.setId(CLUB_ID);
        body.setDissolveConfirmed(true);
        body.setMemberConsequencesConfirmed(true);

        AjaxResult result = controller.dissolve(body);

        assertEquals(200, result.get("code"));
        assertEquals(3, ((Map<?, ?>) result.get("data")).get("membersAffected"));
        assertEquals(accessEnd, ((Map<?, ?>) result.get("data")).get("membershipAccessEnd"));
        verify(lifecycleService).dissolve(eq(CLUB_ID), eq(MEMBER_ID), eq(true), eq(true), anyString());
    }

    @Test
    void memberQuitReturnsFrozenMembershipReceiptWithoutClaimingPayout() {
        when(clubService.selectClubById(CLUB_ID)).thenReturn(activeClub());
        ClubMembershipAccessEndResultVO accessEnd = new ClubMembershipAccessEndResultVO();
        accessEnd.setPendingReviewCount(1);
        accessEnd.setPayoutCompleted(false);
        when(lifecycleService.quit(eq(CLUB_ID), eq(MEMBER_ID), anyString()))
                .thenReturn(lifecycleResult(1, accessEnd));
        Club body = new Club();
        body.setId(CLUB_ID);

        AjaxResult result = controller.quit(body);

        assertEquals(200, result.get("code"));
        assertEquals(accessEnd, ((Map<?, ?>) result.get("data")).get("membershipAccessEnd"));
        assertEquals(false, accessEnd.isPayoutCompleted());
        verify(lifecycleService).quit(eq(CLUB_ID), eq(MEMBER_ID), anyString());
    }

    @Test
    void ownerRemovalUsesDifferentLifecycleEventAndReturnsReceipt() {
        Club owned = activeClub();
        owned.setMemberId(MEMBER_ID);
        Long target = 99L;
        ClubMember existing = new ClubMember();
        existing.setClubId(CLUB_ID);
        existing.setMemberId(target);
        when(clubService.selectClubById(CLUB_ID)).thenReturn(owned);
        when(memberService.selectByClubAndMember(CLUB_ID, target)).thenReturn(existing);
        ClubMembershipAccessEndResultVO accessEnd = new ClubMembershipAccessEndResultVO();
        accessEnd.setNonRefundableCount(1);
        when(lifecycleService.removeMember(eq(CLUB_ID), eq(target), eq(MEMBER_ID), anyString()))
                .thenReturn(lifecycleResult(1, accessEnd));
        ClubMember body = new ClubMember();
        body.setClubId(CLUB_ID);
        body.setMemberId(target);

        AjaxResult result = controller.removeMember(body);

        assertEquals(200, result.get("code"));
        assertEquals(accessEnd, ((Map<?, ?>) result.get("data")).get("membershipAccessEnd"));
        verify(lifecycleService).removeMember(eq(CLUB_ID), eq(target), eq(MEMBER_ID), anyString());
    }

    @Test
    void lifecycleFacadeOwnsTheTransactionOutsideControllerErrorTranslation() throws Exception {
        assertTransactional("quit", Long.class, Long.class, String.class);
        assertTransactional("removeMember", Long.class, Long.class, Long.class, String.class);
        assertTransactional("dissolve", Long.class, Long.class, boolean.class, boolean.class, String.class);
    }

    private void assertTransactional(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ClubLifecycleCommandServiceImpl.class.getMethod(methodName, parameterTypes);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertNotNull(annotation, methodName + " facade 必须让成员/IM/会费收尾处于同一事务");
        assertEquals(Exception.class, annotation.rollbackFor()[0]);
    }

    @Test
    void legacyDepositProvidesQueryOnlyOrExistingPlatformContactExit() {
        ClubPendingDeposit query=pendingDeposit(81L,4);query.setRetryable(true);
        ClubPendingDeposit manual=pendingDeposit(82L,4);
        when(lifecycleService.dissolve(eq(CLUB_ID), eq(MEMBER_ID), eq(true), eq(false), anyString()))
                .thenThrow(new ClubDissolutionDepositException("历史退款待核验",Arrays.asList(query,manual)));
        Club body=new Club();body.setId(CLUB_ID);body.setDissolveConfirmed(true);
        Map<?,?> data=(Map<?,?>)controller.dissolve(body).get("data");
        List<?> actions=(List<?>)data.get("actionItems");
        assertEquals("核对退款结果",((Map<?,?>)actions.get(0)).get("actionText"));
        assertEquals("历史退款待核验",((Map<?,?>)actions.get(0)).get("statusText"));
        assertEquals("/pages/club/dissolution-blockers/index?clubId=73&type=deposit&id=81",((Map<?,?>)actions.get(0)).get("url"));
        assertEquals("联系平台核验",((Map<?,?>)actions.get(1)).get("actionText"));
        assertEquals("/pages/shezhi/about/index",((Map<?,?>)actions.get(1)).get("url"));
    }

    @Test
    void depositBlockReturnsClickableRouteToExactInvites() {
        when(lifecycleService.dissolve(eq(CLUB_ID), eq(MEMBER_ID), eq(true), eq(false), anyString()))
                .thenThrow(new ClubDissolutionDepositException(
                        "俱乐部还有 2 笔合作保证金待处理，请先到合作管理逐笔处理",
                        Arrays.asList(pendingDeposit(81L, 1), pendingDeposit(82L, 2))));
        Club body = new Club();
        body.setId(CLUB_ID);
        body.setDissolveConfirmed(true);

        AjaxResult result = controller.dissolve(body);

        assertEquals(500, result.get("code"));
        assertEquals("俱乐部还有 2 笔合作保证金待处理，请先到合作管理逐笔处理", result.get("msg"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        List<?> actions = (List<?>) data.get("actionItems");
        assertEquals("保证金 #81 · 支付中", ((Map<?, ?>) actions.get(0)).get("label"));
        assertEquals("/pages/club/dissolution-blockers/index?clubId=73&type=deposit&id=81",
                ((Map<?, ?>) actions.get(0)).get("url"));
        assertEquals("保证金 #82 · 已缴冻结，待退或待扣", ((Map<?, ?>) actions.get(1)).get("label"));
    }

    @Test
    void settlementBlockReturnsDirectionalClickableRows() {
        ClubPendingSettlement incoming = pendingSettlement(51L, 7L, "incoming");
        ClubPendingSettlement outgoing = pendingSettlement(52L, 8L, "outgoing");
        when(lifecycleService.dissolve(eq(CLUB_ID), eq(MEMBER_ID), eq(true), eq(false), anyString()))
                .thenThrow(new ClubDissolutionSettlementException(
                        "俱乐部还有 2 笔未打款结算（待收 1 笔、待付 1 笔），请先逐笔处理",
                        Arrays.asList(incoming, outgoing)));
        Club body = new Club();
        body.setId(CLUB_ID);
        body.setDissolveConfirmed(true);

        AjaxResult result = controller.dissolve(body);

        assertEquals(500, result.get("code"));
        List<?> actions = (List<?>) ((Map<?, ?>) result.get("data")).get("actionItems");
        assertEquals("待收进来 · 结算 #51", ((Map<?, ?>) actions.get(0)).get("label"));
        assertEquals("/pages/club/dissolution-blockers/index?clubId=73&type=settlement&id=51",
                ((Map<?, ?>) actions.get(0)).get("url"));
        assertEquals("待付出去 · 结算 #52", ((Map<?, ?>) actions.get(1)).get("label"));
        assertEquals("/pages/club/dissolution-blockers/index?clubId=73&type=settlement&id=52",
                ((Map<?, ?>) actions.get(1)).get("url"));
    }

    @Test
    void ownerScopedBlockerEndpointReturnsSoftDeletedFinancialFactsFromService() {
        ClubDissolutionBlockers blockers = new ClubDissolutionBlockers();
        blockers.setDeposits(Arrays.asList(pendingDeposit(81L, 2)));
        blockers.setSettlements(Arrays.asList(pendingSettlement(51L, 7L, "incoming")));
        when(clubService.selectDissolutionBlockers(CLUB_ID, MEMBER_ID)).thenReturn(blockers);
        Club body = new Club();
        body.setId(CLUB_ID);

        AjaxResult result = controller.dissolutionBlockers(body);

        assertEquals(200, result.get("code"));
        assertEquals(blockers, result.get("data"));
        verify(clubService).selectDissolutionBlockers(CLUB_ID, MEMBER_ID);
    }

    private ClubPendingSettlement pendingSettlement(Long id, Long topicId, String direction) {
        ClubPendingSettlement result = new ClubPendingSettlement();
        result.setId(id);
        result.setTopicId(topicId);
        result.setDirection(direction);
        return result;
    }

    private ClubPendingDeposit pendingDeposit(Long id, Integer status) {
        ClubPendingDeposit result = new ClubPendingDeposit();
        result.setId(id);
        result.setTopicId(7L);
        result.setDepositStatus(status);
        return result;
    }

    private ClubLifecycleCommandResultVO lifecycleResult(
            int affectedRows, ClubMembershipAccessEndResultVO accessEnd) {
        ClubLifecycleCommandResultVO result = new ClubLifecycleCommandResultVO();
        result.setAffectedRows(affectedRows);
        result.setMembershipAccessEnd(accessEnd);
        return result;
    }

    @Test
    void clubChatOutsiderGetsForbiddenWithoutCreatingConversation() {
        com.chengyinhub.business.service.IImService im = mock(com.chengyinhub.business.service.IImService.class);
        ReflectionTestUtils.setField(controller, "imService", im);
        ReflectionTestUtils.setField(controller, "clubParticipationGuard",
                mock(com.chengyinhub.business.service.IClubParticipationGuard.class));
        when(clubService.selectClubById(CLUB_ID)).thenReturn(activeClub());
        Club body = new Club();
        body.setId(CLUB_ID);
        AjaxResult response = controller.chat(body);
        assertEquals(403, response.get("code"));
        assertEquals("加入俱乐部后才能进群聊", response.get("msg"));
        verify(im, org.mockito.Mockito.never()).getOrCreateClubGroup(anyLong(), anyLong());
    }
}

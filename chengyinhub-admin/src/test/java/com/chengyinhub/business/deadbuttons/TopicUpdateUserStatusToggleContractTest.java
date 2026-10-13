package com.chengyinhub.business.deadbuttons;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiTopicController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * D6 的<b>裁决留痕</b>:{@code /api/topic/update_user_status} 是<b>翻转</b>语义,这是设计,不是缺陷。
 *
 * <p>E2E 报告说「对已上架主题传 {@code userStatus=1} 反而下架了」。核实结论:这个接口**从来没有**
 * 声明过 {@code userStatus} 入参 —— 方法签名只有 {@code String id},多传的参数被 Spring 直接忽略。
 * 唯一的调用方是小程序 {@code subpackageA/pages/myproject/index.js:250-256}
 * (注释原文「上/下架切换(按 bizType 派发,单接口翻转)」),它只送 {@code {id}},期望的正是翻转。
 * 改成设值语义会让这个唯一消费方失去它唯一的开关 ⇒ <b>不改</b>。
 *
 * <p>本文件的作用是把这个契约钉住:下一个人照着那份 E2E 报告去「修」成设值语义时,这里当场红。
 */
class TopicUpdateUserStatusToggleContractTest {

    private static final Long ME = 19L;
    private static final Long TOPIC = 990016L;

    private static final Long CLUB = 4001L;
    private static final Long OTHER_CLUB = 4002L;
    private static final Long TOPIC_OWNER = 777L;

    private ApiTopicController controller;
    private ICmsTopicService topicService;
    private IQuotaService quotaService;
    private com.chengyinhub.business.access.club.IClubAccessService clubAccessService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicController());
        topicService = mock(ICmsTopicService.class);
        quotaService = mock(IQuotaService.class);
        clubAccessService = mock(com.chengyinhub.business.access.club.IClubAccessService.class);
        doReturn(ME).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
        ReflectionTestUtils.setField(controller, "clubAccessService", clubAccessService);
        // 额度闸放行:原样执行写动作
        doAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get())
                .when(quotaService).runWithThemeOnlineGuard(anyLong(), any(), any());
        when(topicService.updateCmsTopic(any())).thenReturn(1);
        // 默认:CAS 命中(库中态 == 期望态)。不符的场景由各用例自己改桩。
        when(topicService.casUserStatus(anyLong(), any(), any())).thenReturn(1);
    }

    private void stubTopic(Integer userStatus) {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(ME);
        t.setUserStatus(userStatus);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(t);
    }

    /** 落库动作现在走 CAS：读它的 target 入参,顺带证明「写」确实是有条件的那一条。 */
    private Integer writtenUserStatus() {
        ArgumentCaptor<Integer> target = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(topicService)
                .casUserStatus(org.mockito.ArgumentMatchers.eq(TOPIC), any(), target.capture());
        // 无条件写的老路径必须彻底让位,否则 CAS 只是并排多了一条、没堵住任何东西
        org.mockito.Mockito.verify(topicService, org.mockito.Mockito.never()).updateCmsTopic(any());
        return target.getValue();
    }

    @Test
    void online_topic_isTakenOffline_thatIsTheToggleContract() {
        stubTopic(1);

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), null, null);

        assertEquals("下架成功", String.valueOf(r.get(AjaxResult.MSG_TAG)));
        assertEquals(Integer.valueOf(0), writtenUserStatus(), "已上架 → 翻转成下架");
    }

    @Test
    void offline_topic_isPutOnline() {
        stubTopic(0);

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), null, null);

        assertEquals("上架成功", String.valueOf(r.get(AjaxResult.MSG_TAG)));
        assertEquals(Integer.valueOf(1), writtenUserStatus(), "未上架 → 翻转成上架");
    }

    /** 归属闸:不是本人一律拒,且一个字段都不许写(IDOR)。 */
    @Test
    void otherMember_cannotToggle() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(777L);
        t.setUserStatus(1);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(t);

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), null, null);

        assertEquals("无权操作该主题", String.valueOf(r.get(AjaxResult.MSG_TAG)));
        org.mockito.Mockito.verify(topicService, org.mockito.Mockito.never()).updateCmsTopic(any());
    }

    // ---------- CAS:期望态与库中实际态不符时拒绝,并告知当前态 ----------

    /**
     * 客户端列表陈旧:渲染时看到的是「已上架」(按钮写着「下架」),但服务端此刻已是未上架。
     * 旧行为会把它**重新上架**并提示「操作成功」;现在必须拒绝,且文案要说清现在到底是什么态。
     */
    @Test
    void staleExpectation_isRejected_andTellsTheCurrentState() {
        stubTopic(0);   // 库里其实已经是「未上架」

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), "1", null); // 客户端以为是「已上架」

        String msg = String.valueOf(r.get(AjaxResult.MSG_TAG));
        assertTrue(msg.contains("未上架"),
                "拒绝时必须告知**当前**是什么态,否则用户只知道失败、不知道该怎么办:" + msg);
        org.mockito.Mockito.verify(topicService, org.mockito.Mockito.never())
                .casUserStatus(anyLong(), any(), any());
        org.mockito.Mockito.verify(topicService, org.mockito.Mockito.never()).updateCmsTopic(any());
    }

    /**
     * 竞态:内容安全回调命中违规,已把主题自动下架(user_status 1→0);
     * 与此同时运营手上那一屏还停在「已上架」,点了「下架」。
     *
     * <p>★ 这条真正要防的不是「下架被重复执行」,而是**反向**:期望态 1 与库中 0 不符 ⇒ 必须拒,
     * 不能按翻转语义算出 target=1 把违规主题**重新推上架**。
     */
    @Test
    void contentSafetyAutoTakedown_isNotOverwrittenByStaleManualClick() {
        stubTopic(0);   // 内容安全回调已经下架了它

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), "1", null);

        assertEquals(500, r.get(AjaxResult.CODE_TAG), "必须是失败,不能静默改成别的动作");
        // 一次「置 1」都不许发生 —— 违规主题被陈旧点击重新上架是内容安全事故
        org.mockito.Mockito.verify(topicService, org.mockito.Mockito.never())
                .casUserStatus(anyLong(), any(), org.mockito.ArgumentMatchers.eq(1));
        org.mockito.Mockito.verify(topicService, org.mockito.Mockito.never()).updateCmsTopic(any());
    }

    /** 期望态与库中一致 ⇒ 照旧翻转,并且 CAS 的 expected 必须真的带上去(否则等于无条件写)。 */
    @Test
    void matchingExpectation_flipsAndPassesExpectedIntoTheCas() {
        stubTopic(1);

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), "1", null);

        assertEquals("下架成功", String.valueOf(r.get(AjaxResult.MSG_TAG)));
        org.mockito.Mockito.verify(topicService).casUserStatus(TOPIC, 1, 0);
    }

    /**
     * 读到态之后、写下去之前被别人抢改 ⇒ CAS 返回 0 行。
     * 应用层的早检查关不掉这个窗口(它在库里),只有 CAS 能;此时必须报失败而不是「操作成功」。
     */
    @Test
    void lostRace_afterTheEarlyCheck_stillFailsLoudly() {
        stubTopic(1);
        when(topicService.casUserStatus(anyLong(), any(), any())).thenReturn(0); // 写的时候已经不匹配了

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), "1", null);

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(r.get(AjaxResult.MSG_TAG)).contains("刷新"),
                "输给竞态时要让用户知道刷新重试,实际:" + r.get(AjaxResult.MSG_TAG));
    }

    /**
     * 向后兼容:老客户端不传期望态。
     *
     * <p>判定是**宽松**(用服务端刚读到的态兜底)而不是拒绝 —— 依据见报告 §1.3。
     * 但兜底**不等于退回无条件写**:CAS 仍然发生,expected 用的就是刚读到的值,
     * 于是「读到之后、写之前」那个窗口对老客户端也是关着的。
     */
    @Test
    void legacyCallerWithoutExpectation_stillGoesThroughCas() {
        stubTopic(1);

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), null, null);

        assertEquals("下架成功", String.valueOf(r.get(AjaxResult.MSG_TAG)));
        org.mockito.Mockito.verify(topicService).casUserStatus(TOPIC, 1, 0);
        org.mockito.Mockito.verify(topicService, org.mockito.Mockito.never()).updateCmsTopic(any());
    }

    /** 期望态传了垃圾值(非 0/1)⇒ 当作没传走兜底,不能拿它去跟库比出个恒不符。 */
    @Test
    void garbageExpectation_isTreatedAsAbsent() {
        stubTopic(1);

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), "yes", null);

        assertEquals("下架成功", String.valueOf(r.get(AjaxResult.MSG_TAG)));
        org.mockito.Mockito.verify(topicService).casUserStatus(TOPIC, 1, 0);
    }

    /** 存量脏数据 user_status = NULL：按「未上架」口径,期望态 0 要能匹配上(与 mapper 的 COALESCE 同口径)。 */
    @Test
    void nullUserStatus_isTreatedAsOffline() {
        stubTopic(null);

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), "0", null);

        assertEquals("上架成功", String.valueOf(r.get(AjaxResult.MSG_TAG)));
        org.mockito.Mockito.verify(topicService).casUserStatus(TOPIC, 0, 1);
    }

    // ---------- 2026-09-09 归属放宽:该主题所属俱乐部里持 ACTIVITY_MANAGE 的岗位也能上下架 ----------
    //
    // 为什么要这几条:放宽的是一道 IDOR 闸,唯一挡住越权的是「必须是**这条主题自己挂着的**俱乐部」
    // 加「必须有 ACTIVITY_MANAGE」。这两个限定各写一条负控 —— 少了任何一条,放宽就变成
    // 「随便哪个俱乐部的随便哪个成员都能下架别人的主题」。

    /** 造一条别人创建、挂在 CLUB 名下的主题。 */
    private void stubClubTopic(Long clubId, Integer userStatus) {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(TOPIC_OWNER);
        t.setClubId(clubId);
        t.setUserStatus(userStatus);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(t);
    }

    /** ME 在 clubId 里担任 roleCode。 */
    private void stubClubRole(Long clubId, String roleCode) {
        when(clubAccessService.resolve(ME, clubId, null)).thenReturn(
                new com.chengyinhub.business.access.club.ClubAccessContext(
                        ME, clubId, TOPIC_OWNER, null,
                        java.util.Collections.singleton(roleCode)));
    }

    /** 放行支:共同主理人有 ACTIVITY_MANAGE,不是创建者也能停售。 */
    @Test
    void clubCoOwner_canToggle_evenThoughSomeoneElseCreatedTheTopic() {
        stubClubTopic(CLUB, 1);
        stubClubRole(CLUB, com.chengyinhub.business.access.club.ClubRoleCode.CLUB_CO_OWNER);

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), "1", null);

        assertEquals("下架成功", String.valueOf(r.get(AjaxResult.MSG_TAG)));
        assertEquals(Integer.valueOf(0), writtenUserStatus());
    }

    /** 负控一:同一个俱乐部,但岗位只有 ACTIVITY_READ ⇒ 仍然拒。放宽给的是 MANAGE,不是「进了俱乐部就行」。 */
    @Test
    void clubOperator_withoutActivityManage_stillCannotToggle() {
        stubClubTopic(CLUB, 1);
        stubClubRole(CLUB, com.chengyinhub.business.access.club.ClubRoleCode.CLUB_OPERATOR);

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), "1", null);

        assertEquals("无权操作该主题", String.valueOf(r.get(AjaxResult.MSG_TAG)));
        org.mockito.Mockito.verify(topicService, org.mockito.Mockito.never())
                .casUserStatus(anyLong(), any(), any());
    }

    /** 负控二:在**别的**俱乐部当共同主理人,够不到这条主题 —— 放宽钉在主题自己的 clubId 上。 */
    @Test
    void coOwnerOfAnotherClub_cannotToggle() {
        stubClubTopic(OTHER_CLUB, 1);
        stubClubRole(CLUB, com.chengyinhub.business.access.club.ClubRoleCode.CLUB_CO_OWNER);

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), "1", null);

        assertEquals("无权操作该主题", String.valueOf(r.get(AjaxResult.MSG_TAG)));
        org.mockito.Mockito.verify(topicService, org.mockito.Mockito.never())
                .casUserStatus(anyLong(), any(), any());
    }

    /** 上架额度记在主题主人头上,不是点按钮的人 —— 否则管理员一点就去扣了自己的在架额度。 */
    @Test
    void onlineQuota_isChargedToTheTopicOwner_notTheClickingStaff() {
        stubClubTopic(CLUB, 0);
        stubClubRole(CLUB, com.chengyinhub.business.access.club.ClubRoleCode.CLUB_CO_OWNER);

        AjaxResult r = controller.topicUpdateUserStatus(String.valueOf(TOPIC), "0", null);

        assertEquals("上架成功", String.valueOf(r.get(AjaxResult.MSG_TAG)));
        ArgumentCaptor<Long> charged = ArgumentCaptor.forClass(Long.class);
        org.mockito.Mockito.verify(quotaService)
                .runWithThemeOnlineGuard(charged.capture(), any(), any());
        assertEquals(TOPIC_OWNER, charged.getValue(), "额度算主题主人的,不算 actor");
    }
}

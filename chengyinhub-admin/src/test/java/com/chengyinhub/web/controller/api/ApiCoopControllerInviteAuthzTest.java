package com.chengyinhub.web.controller.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopClubApplyService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/coop/invite} 的发起人闸(P0,2026-07-17)。
 *
 * <p><b>洞</b>:此前身份校验只有两条 —— fromId 强制为登录人(防伪造发起方)、
 * 禁止自邀自接(toOwner != memberId)。**没有任何一条校验发起人与 topicId 的关系**
 * ⇒ 任何登录会员可为**任意主题**向**任意他人**发邀约;对方接受即 status=1
 * ⇒ 伪造出「已接受邀约」,并经 acceptInviteWithLock 落 coop_order **塞进 ① 的结算阵容**。
 * 两个小号即可完成(自邀自接那道闸只挡「发给自己」,挡不住「A 发给 B」)。
 *
 * <p><b>闸</b>:现役 type0/type1 统一 —— 发起人必须是该主题的发布者({@code cms_topic.member_id})。
 * 证据(2026-07-17 核实):前端 {@code pages/coop/invite/index.js} 对两类一视同仁,topicId 只能来自
 * {@code /api/topic/list {is_my:1}},而后端 {@code ApiTopicController:786} 对 is_my=1
 * 的处理就是 {@code cmsTopic.setMemberId(登录人)} ⇒ **两类的发起人本来就都是主题发布者**。
 * type1 的真实语义是:主题发布主体拿**自己的**主题去请俱乐部承接,不是往别人主题上贴。
 *
 * <p>⚠️ ③-自营(平台后台发)的 member_id 不是发起人 ⇒ 被本闸拒是**对的**:
 * 它的商家不走邀约,走报名/中标(见 MerchantOfferServiceImpl#assertWonBid)。
 */
class ApiCoopControllerInviteAuthzTest {

    private static final Long ME = 500L;
    private static final Long OTHER = 999L;
    private static final Long TOPIC = 200L;
    private static final Long INVITEE = 88L;

    private ICoopInviteService coopInviteService;
    private ICoopClubApplyService coopClubApplyService;
    private ICmsTopicService cmsTopicService;
    private INotifyService notifyService;
    private IMerchantAccessService merchantAccessService;
    private com.chengyinhub.business.service.IClubService clubService;
    private ApiCoopController c;

    @BeforeEach
    void setUp() {
        coopInviteService = mock(ICoopInviteService.class);
        coopClubApplyService = mock(ICoopClubApplyService.class);
        cmsTopicService = mock(ICmsTopicService.class);
        notifyService = mock(INotifyService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        clubService = mock(com.chengyinhub.business.service.IClubService.class);

        c = spy(new ApiCoopController());
        doReturn(ME).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "coopInviteService", coopInviteService);
        ReflectionTestUtils.setField(c, "coopClubApplyService", coopClubApplyService);
        ReflectionTestUtils.setField(c, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(c, "clubService", clubService);
        ReflectionTestUtils.setField(c, "notifyService", notifyService);
        ReflectionTestUtils.setField(c, "merchantAccessService", merchantAccessService);
        ReflectionTestUtils.setField(c, "wxContentSecurityService", org.mockito.Mockito.mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        ReflectionTestUtils.setField(c, "umsMemberService",
                mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(c, "mmsMerchantService",
                mock(com.chengyinhub.business.service.IMmsMerchantService.class));
        when(coopInviteService.selectCoopInviteList(any())).thenReturn(Collections.<CoopInvite>emptyList());
        when(merchantAccessService.require(ME, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(ME, 9001L, ME, "owner"));
    }

    /** 主题的发布者 = publisherId。 */
    private void givenTopicPublishedBy(Long publisherId) {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(publisherId);
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(t);
    }

    private void givenClubTopicPublishedBy(Long publisherId, Long clubId) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(publisherId);
        topic.setClubId(clubId);
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        com.chengyinhub.business.domain.Club publisherClub = new com.chengyinhub.business.domain.Club();
        publisherClub.setId(clubId);
        publisherClub.setMemberId(publisherId);
        when(clubService.selectClubById(clubId)).thenReturn(publisherClub);
    }

    /** type:0 主题发布者→商家 / 1 主题发布主体→俱乐部。受邀方恒为他人(避开自邀自接那道闸)。 */
    private CoopInvite invite(int type) {
        CoopInvite b = new CoopInvite();
        b.setInviteType(type);
        b.setToType(type == 0 ? "merchant" : "club");
        b.setToId(INVITEE);
        b.setTopicId(TOPIC);
        return b;
    }

    private static String msg(AjaxResult r) {
        return String.valueOf(r.get("msg"));
    }

    @Test
    void acceptedCooperationDoesNotReplacePublisherAuthorization() {
        givenTopicPublishedBy(OTHER);
        CoopInvite accepted = invite(0);
        accepted.setStatus(1);
        accepted.setFromId(OTHER);
        accepted.setToId(ME);
        when(coopInviteService.selectCoopInviteList(any())).thenReturn(Collections.singletonList(accepted));
        for (int type : new int[] {0, 1}) {
            CoopInvite command = invite(type);
            command.setFromId(OTHER);
            assertEquals(500, c.invite(command).get("code"));
        }
        verify(coopInviteService, never()).insertCoopInvite(any());
    }

    @Test
    void inviteChapterCardsArePublisherOnlyAndDoNotExposeEditableContent() {
        ApiTopicController topicController = spy(new ApiTopicController());
        doReturn(ME).when(topicController).getAppUserId();
        ReflectionTestUtils.setField(topicController, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(topicController, "merchantAccessService", merchantAccessService);
        com.chengyinhub.business.service.ICmsTopicChapterService chapters =
                mock(com.chengyinhub.business.service.ICmsTopicChapterService.class);
        ReflectionTestUtils.setField(topicController, "cmsTopicChapterService", chapters);
        com.chengyinhub.business.domain.CmsTopicChapter chapter = new com.chengyinhub.business.domain.CmsTopicChapter();
        chapter.setId(17L); chapter.setName("第一章"); chapter.setImgArr("/profile/chapter.jpg");
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));

        givenTopicPublishedBy(OTHER);
        assertEquals(500, topicController.inviteChapters(String.valueOf(TOPIC), null).get("code"));
        verify(chapters, never()).selectCmsTopicChapterList(any());
        givenTopicPublishedBy(ME);
        AjaxResult result = topicController.inviteChapters(String.valueOf(TOPIC), null);
        assertEquals(200, result.get("code"));
        Map<?, ?> data = (Map<?, ?>) result.get("data");
        List<?> cards = (List<?>) data.get("chapters");
        assertEquals(1, cards.size());
        Map<?, ?> card = (Map<?, ?>) cards.get(0);
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("id", "name", "imgArr")), card.keySet());
    }

    @Test
    void inviteChapterMerchantScopeRequiresProjectPermissionAndPublisherOwnership() {
        ApiTopicController topicController = spy(new ApiTopicController());
        doReturn(ME).when(topicController).getAppUserId();
        ReflectionTestUtils.setField(topicController, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(topicController, "merchantAccessService", merchantAccessService);
        com.chengyinhub.business.service.ICmsTopicChapterService chapters =
                mock(com.chengyinhub.business.service.ICmsTopicChapterService.class);
        ReflectionTestUtils.setField(topicController, "cmsTopicChapterService", chapters);
        Long owner = 501L;
        when(merchantAccessService.require(ME, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(ME, 9001L, owner, "manager"));
        givenTopicPublishedBy(owner);
        assertEquals(200, topicController.inviteChapters(String.valueOf(TOPIC), "MERCHANT").get("code"));
        givenTopicPublishedBy(OTHER);
        assertEquals(500, topicController.inviteChapters(String.valueOf(TOPIC), "MERCHANT").get("code"));
        when(merchantAccessService.require(ME, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException("无项目管理权限", 403));
        assertEquals(403, topicController.inviteChapters(String.valueOf(TOPIC), "MERCHANT").get("code"));
    }

    // ------------------------------------------------- 洞:别人的主题

    /**
     * ★ 两类统一:不是自己发布的主题 ⇒ 拒。
     */
    @Test
    void invite_type0_onSomeoneElsesTopic_rejected() {
        givenTopicPublishedBy(OTHER);

        AjaxResult r = c.invite(invite(0));

        assertEquals(500, r.get("code"), "应当拒绝");
        assertTrue(msg(r).contains("自己发布"), "错误文案须指明是发起人闸,实际:" + msg(r));
        verify(coopInviteService, never()).insertCoopInvite(any());
    }

    @Test
    void invite_type1_onSomeoneElsesTopic_rejected() {
        givenTopicPublishedBy(OTHER);

        AjaxResult r = c.invite(invite(1));

        assertEquals(500, r.get("code"));
        assertTrue(msg(r).contains("自己发布"), "错误文案须指明是发起人闸,实际:" + msg(r));
        verify(coopInviteService, never()).insertCoopInvite(any());
    }

    @Test
    void invite_type2_isRejectedByRetirementGateBeforeTopicAuthorization() {
        givenTopicPublishedBy(OTHER);

        AjaxResult r = c.invite(invite(2));

        assertEquals(500, r.get("code"));
        assertTrue(msg(r).contains("邀请类型不合法"), "type2 必须先被撤销闸拒绝,实际:" + msg(r));
        verify(coopInviteService, never()).insertCoopInvite(any());
    }

    /**
     * ★ 主题没有发布者(member_id 为空,如 ③-自营/存量脏数据)⇒ 拒(fail-closed)。
     * 判不出归属就别放行:放行等于把「归属不明」当成「归属是我」。
     */
    @Test
    void invite_topicWithoutPublisher_rejected() {
        givenTopicPublishedBy(null);

        AjaxResult r = c.invite(invite(0));

        assertEquals(500, r.get("code"));
        verify(coopInviteService, never()).insertCoopInvite(any());
    }

    /** 主题不存在 ⇒ 拒。 */
    @Test
    void invite_topicNotFound_rejected() {
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(null);

        AjaxResult r = c.invite(invite(0));

        assertEquals(500, r.get("code"));
        verify(coopInviteService, never()).insertCoopInvite(any());
    }

    // ------------------------------------------------- 正控:别把闸修成恒拒

    /** 自己发布的主题照常放行 —— 现役 type0/type1 都要能走通。 */
    @Test
    void invite_onMyOwnTopic_passes() {
        givenTopicPublishedBy(ME);

        for (int type = 0; type <= 1; type++) {
            AjaxResult r = c.invite(invite(type));
            assertEquals(200, r.get("code"), "type=" + type + " 应放行,实际:" + msg(r));
        }

        verify(coopInviteService, org.mockito.Mockito.times(2)).insertCoopInvite(any());
    }

    @Test
    void invite_rejectsTypeToTargetMatrixMismatch() {
        givenTopicPublishedBy(ME);
        CoopInvite forgedType0 = invite(0);
        forgedType0.setToType("club");
        CoopInvite forgedType1 = invite(1);
        forgedType1.setToType("merchant");

        assertEquals(500, c.invite(forgedType0).get("code"));
        assertEquals(500, c.invite(forgedType1).get("code"));

        verify(coopInviteService, never()).insertCoopInvite(any());
    }

    @Test
    void type1_usesActiveMerchantOwnerAsContractParty() {
        Long operator = ME;
        Long owner = 501L;
        when(merchantAccessService.require(operator, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(operator, 9001L, owner, "manager"));
        givenTopicPublishedBy(owner);
        CoopInvite body = invite(1);

        AjaxResult result = c.invite(body);

        assertEquals(200, result.get("code"));
        assertEquals(owner, body.getFromId());
        assertEquals("merchant", body.getFromType());
        verify(coopInviteService).insertCoopInvite(body);
    }

    @Test
    void type1_clubPublishedTopicUsesPublisherClubEvenWhenPublisherAlsoMerchant() {
        Long publisherClubId = 7001L;
        givenClubTopicPublishedBy(ME, publisherClubId);
        com.chengyinhub.business.domain.Club invitedClub = new com.chengyinhub.business.domain.Club();
        invitedClub.setId(INVITEE);
        invitedClub.setMemberId(OTHER);
        when(clubService.selectClubById(INVITEE)).thenReturn(invitedClub);
        CoopInvite body = invite(1);
        body.setFromType("merchant");
        body.setFromId(123456L);

        AjaxResult result = c.invite(body);

        assertEquals(200, result.get("code"));
        assertEquals("club", body.getFromType());
        assertEquals(publisherClubId, body.getFromId());
        verify(merchantAccessService, never()).require(ME, MerchantPermission.COOP_MANAGE);
        verify(coopInviteService).insertCoopInvite(body);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listIncludesClubPublishedOutgoingInviteAndShowsExecutionClubAsPartner() {
        Long publisherClubId = 7001L;
        givenClubTopicPublishedBy(ME, publisherClubId);
        com.chengyinhub.business.domain.Club publisherClub = clubService.selectClubById(publisherClubId);
        com.chengyinhub.business.domain.Club executionClub = new com.chengyinhub.business.domain.Club();
        executionClub.setId(INVITEE);
        executionClub.setMemberId(OTHER);
        executionClub.setName("执行俱乐部");
        when(clubService.selectClubById(INVITEE)).thenReturn(executionClub);
        when(clubService.selectClubsByMemberId(ME)).thenReturn(Collections.singletonList(publisherClub));
        CoopInvite outgoing = invite(1);
        outgoing.setFromType("club");
        outgoing.setFromId(publisherClubId);
        outgoing.setStatus(0);
        when(coopInviteService.selectCoopInviteListReadOnly(any())).thenAnswer(call -> {
            CoopInvite query = call.getArgument(0);
            return "club".equals(query.getFromType()) && publisherClubId.equals(query.getFromId())
                    ? Collections.singletonList(outgoing) : Collections.emptyList();
        });

        AjaxResult result = c.list(null);

        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<CoopInvite> sent = (List<CoopInvite>) data.get("sent");
        assertEquals(1, sent.size());
        assertEquals("执行俱乐部", sent.get(0).getPartner().getName());
    }

    @Test
    void type1_clubPublisherCannotInviteItsOwnClub() {
        Long publisherClubId = 7001L;
        givenClubTopicPublishedBy(ME, publisherClubId);
        CoopInvite body = invite(1);
        body.setToId(publisherClubId);

        AjaxResult result = c.invite(body);

        assertEquals(500, result.get("code"));
        assertTrue(msg(result).contains("不能向自己"));
        verify(coopInviteService, never()).insertCoopInvite(any());
    }

    @Test
    void type0_explicitMerchantScopeUsesProjectOwnerAsContractParty() {
        Long operator = ME;
        Long owner = 501L;
        when(merchantAccessService.require(operator, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(operator, 9001L, owner, "manager"));
        givenTopicPublishedBy(owner);
        CoopInvite body = invite(0);
        body.setScope("MERCHANT");

        AjaxResult result = c.invite(body);

        assertEquals(200, result.get("code"));
        assertEquals(owner, body.getFromId());
        assertEquals("merchant", body.getFromType());
        verify(merchantAccessService).require(operator, MerchantPermission.PROJECT_MANAGE);
        verify(coopInviteService).insertCoopInvite(body);
    }

    @Test
    void merchantManagerRejectsOfficialInviteAsOwnerContractParty() {
        Long owner = 501L;
        when(merchantAccessService.require(ME, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(ME, 9001L, owner, "manager"));
        CoopInvite invite = new CoopInvite();
        invite.setId(41L);
        invite.setStatus(0);
        invite.setInviteType(CoopInvite.TYPE_OFFICIAL_MERCHANT);
        invite.setFromType(CoopInvite.FROM_OFFICIAL);
        invite.setFromId(1L);
        invite.setToType(CoopInvite.TO_MERCHANT);
        invite.setToId(owner);
        when(coopInviteService.selectCoopInviteById(41L)).thenReturn(invite);
        CoopInvite command = new CoopInvite();
        command.setId(41L);
        command.setStatus(2);
        command.setHandleReason("档期冲突");

        AjaxResult result = c.handle(command);

        assertEquals(200, result.get("code"));
        verify(coopInviteService).rejectOfficialInviteWithLock(41L, owner, "档期冲突");
    }

    @Test
    void merchantManagerCancelsPendingInviteAsOwnerContractParty() {
        Long owner = 501L;
        when(merchantAccessService.require(ME, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(ME, 9001L, owner, "manager"));
        CoopInvite invite = new CoopInvite();
        invite.setId(42L);
        invite.setStatus(0);
        invite.setInviteType(1);
        invite.setFromType("merchant");
        invite.setFromId(owner);
        invite.setToType("club");
        invite.setToId(88L);
        when(coopInviteService.selectCoopInviteById(42L)).thenReturn(invite);
        CoopInvite command = new CoopInvite();
        command.setId(42L);
        command.setStatus(3);
        command.setMessage(" 场地临时关闭 ");

        AjaxResult result = c.handle(command);

        assertEquals(200, result.get("code"));
        verify(coopInviteService).cancelPendingInviteWithLock(42L, owner, " 场地临时关闭 ");
        // CU-C-86:撤回要通知受邀方,并带上理由(页面写明「会发给对方」)
        verify(notifyService).notifyCoopStatus(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("已撤回"),
                org.mockito.ArgumentMatchers.eq("场地临时关闭"));
    }

    @Test
    void merchantManagerCancelsAcceptedInviteAsOwnerContractParty() {
        Long owner = 501L;
        when(merchantAccessService.require(ME, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(ME, 9001L, owner, "manager"));
        CoopInvite invite = new CoopInvite();
        invite.setId(43L);
        invite.setStatus(1);
        invite.setInviteType(1);
        invite.setFromType("merchant");
        invite.setFromId(owner);
        invite.setToType("club");
        invite.setToId(88L);
        when(coopInviteService.selectCoopInviteById(43L)).thenReturn(invite);
        doThrow(new IllegalStateException("stop-before-refund"))
                .when(coopInviteService).cancelAcceptedInvite(43L, owner, "停止合作", 2);
        CoopInvite command = new CoopInvite();
        command.setId(43L);
        command.setStatus(3);
        command.setMessage("停止合作");

        AjaxResult result = c.handle(command);

        assertEquals(500, result.get("code"));
        verify(coopInviteService).cancelAcceptedInvite(43L, owner, "停止合作", 2);
    }

    @Test
    void inviteNotificationFailureIsObservableWithoutRollingBackInvite() {
        givenTopicPublishedBy(ME);
        doThrow(new IllegalStateException("provider unavailable"))
                .when(notifyService)
                .notifyCoopInvited(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        Logger logger = (Logger) LoggerFactory.getLogger(ApiCoopController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CoopInvite body = invite(0);

            AjaxResult result = c.invite(body);

            assertEquals(200, result.get("code"), "通知异常不得回滚已写入的邀约");
            verify(coopInviteService).insertCoopInvite(body);
            assertTrue(appender.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("templateKey=tplCoopInvited")
                            && message.contains("businessEvent=coop_invited")
                            && message.contains("memberId=" + INVITEE)
                            && message.contains("cause=IllegalStateException")),
                    "控制器 catch 必须留下可检索的订阅通知异常记录");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    // ------------------------------------------------- 开放报名溯源:不能由客户端伪造付款方向

    @Test
    void invite_originApplyFromAnotherClub_rejected() {
        givenTopicPublishedBy(ME);
        CoopInvite body = invite(1);
        body.setToType("club");
        body.setToId(7001L);
        body.setOriginApplyId(77L);
        doThrow(new IllegalStateException("开放报名溯源不匹配"))
                .when(coopInviteService).insertCoopInvite(body);

        AjaxResult r = c.invite(body);

        assertEquals(500, r.get("code"));
        assertTrue(msg(r).contains("溯源不匹配"));
        verify(coopInviteService).insertCoopInvite(body);
    }

    @Test
    void invite_validOriginApply_passesAndKeepsProvenance() {
        givenTopicPublishedBy(ME);
        CoopInvite body = invite(1);
        body.setToType("club");
        body.setToId(7001L);
        body.setOriginApplyId(77L);
        AjaxResult r = c.invite(body);

        assertEquals(200, r.get("code"));
        verify(coopInviteService).insertCoopInvite(body);
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_marksCompletedHeldDepositAsRefundPending() {
        CoopInvite held = invite(0);
        held.setFromId(ME);
        held.setStatus(1);
        held.setDepositStatus(2);
        when(coopInviteService.selectCoopInviteListReadOnly(any())).thenReturn(Collections.singletonList(held));
        CmsTopic settled = new CmsTopic();
        settled.setId(TOPIC); settled.setMemberId(ME); settled.setIsSettlement(1);
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(settled);

        AjaxResult r = c.list(null);

        Map<String, Object> data = (Map<String, Object>) r.get("data");
        List<CoopInvite> sent = (List<CoopInvite>) data.get("sent");
        assertTrue(sent.get(0).getDepositRefundPending(), "主题已结算但退款仍冻结时必须给付款方重试入口");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listPreservesSupplyStationUnknownConfirmedAndNotApplicableStates() {
        Club receivingClub = new Club();
        receivingClub.setId(7001L); receivingClub.setMemberId(ME); receivingClub.setName("当前主理人的俱乐部"); receivingClub.setDelFlag(0);
        Club invitedClub = new Club();
        invitedClub.setId(INVITEE); invitedClub.setMemberId(OTHER); invitedClub.setName("受邀俱乐部"); invitedClub.setDelFlag(0);
        when(clubService.selectClubsByMemberId(ME)).thenReturn(Collections.singletonList(receivingClub));
        when(clubService.selectClubById(receivingClub.getId())).thenReturn(receivingClub);
        when(clubService.selectClubById(invitedClub.getId())).thenReturn(invitedClub);
        for (Long member : Arrays.asList(ME, INVITEE, OTHER)) {
            MmsMerchant merchant = new MmsMerchant();
            merchant.setId(ME.equals(member) ? 9001L : member + 9000L);
            merchant.setMemberId(member); merchant.setStatus(1L); merchant.setName("商家" + member);
            when(((com.chengyinhub.business.service.IMmsMerchantService) ReflectionTestUtils.getField(c, "mmsMerchantService"))
                    .selectMmsMerchantByMemberId(member)).thenReturn(merchant);
        }

        Map<Long, CmsTopic> topics = new LinkedHashMap<>();
        Map<Long, CmsRegistrationMerchant> stations = new LinkedHashMap<>();
        Map<Long, CmsTopicNode> nodes = new LinkedHashMap<>();
        List<CoopInvite> stored = new ArrayList<>();
        for (int direction = 0; direction < 2; direction++) {
            Long publisher = direction == 0 ? ME : OTHER;
            Long merchantMember = direction == 0 ? INVITEE : ME;
            for (int state = 0; state < 5; state++) {
                Long topicId = TOPIC + direction * 10L + state + 1L;
                CmsTopic topic = new CmsTopic();
                topic.setId(topicId); topic.setMemberId(publisher); topic.setStatus(1); topic.setDelFlag(0);
                topic.setName("原路线" + topicId);
                // An existing historical topic without product type is unknown, not a deleted/hidden topic.
                if (state != 0) topic.setProductType(ProductType.ORIENTEERING);
                topics.put(topicId, topic);
                CoopInvite row = invite(state == 3 ? 1 : state == 4 ? CoopInvite.TYPE_LEGACY_MERCHANT_NODE : 0);
                row.setId(701L + direction * 10L + state); row.setTopicId(topicId);
                row.setFromType("merchant"); row.setFromId(publisher);
                row.setToType(state == 3 ? "club" : CoopInvite.TO_MERCHANT);
                row.setToId(state == 3 ? (direction == 0 ? invitedClub.getId() : receivingClub.getId()) : merchantMember);
                row.setStatus(1); row.setDelFlag(0);
                if (state == 1) {
                    CmsRegistrationMerchant station = new CmsRegistrationMerchant();
                    station.setId(601L + direction); station.setTopicId(topicId); station.setMemberId(merchantMember);
                    station.setAuditStatus(1L); station.setDelFlag(0); station.setAddressName("原确认据点" + station.getId());
                    stations.put(station.getId(), station);
                    row.setSupplyStationId(station.getId());
                } else if (state == 4) {
                    CmsTopicNode node = new CmsTopicNode();
                    node.setId(901L + direction); node.setTopicId(topicId); node.setMerchantMemberId(merchantMember); node.setStatus(1L);
                    nodes.put(node.getId(), node); row.setNodeId(node.getId());
                }
                stored.add(row);
            }
        }
        when(cmsTopicService.selectCmsTopicById(any())).thenAnswer(call -> topics.get(call.getArgument(0)));

        // The actual read owner attaches station facts; only its mapper boundary supplies detached stored rows.
        CoopInviteMapper mapper = mock(CoopInviteMapper.class);
        when(mapper.selectCoopInviteListReadOnly(any(), any())).thenAnswer(call -> {
            CoopInvite query = call.getArgument(0);
            List<CoopInvite> rows = new ArrayList<>();
            for (CoopInvite source : stored) {
                if ((query.getFromId() != null && !query.getFromId().equals(source.getFromId()))
                        || (query.getFromType() != null && !query.getFromType().equals(source.getFromType()))
                        || (query.getToId() != null && !query.getToId().equals(source.getToId()))
                        || (query.getToType() != null && !query.getToType().equals(source.getToType()))
                        || (query.getTopicId() != null && !query.getTopicId().equals(source.getTopicId()))
                        || (query.getStatus() != null && !query.getStatus().equals(source.getStatus()))) continue;
                CoopInvite detached = new CoopInvite();
                org.springframework.beans.BeanUtils.copyProperties(source, detached);
                rows.add(detached);
            }
            return rows;
        });
        when(mapper.selectSupplyStationNames(anyList())).thenAnswer(call -> {
            List<CmsRegistrationMerchant> matches = new ArrayList<>();
            for (CoopInvite original : (List<CoopInvite>) call.getArgument(0)) {
                CmsRegistrationMerchant station = stations.get(original.getSupplyStationId());
                if (station != null && Objects.equals(station.getTopicId(), original.getTopicId())
                        && Objects.equals(station.getMemberId(), original.getToId())) matches.add(station);
            }
            return matches;
        });
        CoopInviteServiceImpl reader = spy(new CoopInviteServiceImpl());
        ReflectionTestUtils.setField(reader, "coopInviteMapper", mapper);
        ReflectionTestUtils.setField(c, "coopInviteService", reader);

        AjaxResult result = c.list(null);

        assertEquals(200, result.get("code"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        for (int direction = 0; direction < 2; direction++) {
            List<CoopInvite> views = (List<CoopInvite>) data.get(direction == 0 ? "sent" : "received");
            assertEquals(5, views.size());
            Map<Long, CoopInvite> byId = new LinkedHashMap<>();
            for (CoopInvite view : views) assertNull(byId.put(view.getId(), view), "独立邀约不得被错误合并或重复");
            long first = 701L + direction * 10L;
            CoopInvite legacy = byId.get(first);
            CoopInvite confirmed = byId.get(first + 1L);
            CoopInvite classicUnbound = byId.get(first + 2L);
            CoopInvite club = byId.get(first + 3L);
            CoopInvite node = byId.get(first + 4L);
            assertNull(legacy.getRequiresSupplyStation());
            assertEquals(Boolean.TRUE, confirmed.getRequiresSupplyStation());
            assertEquals(Boolean.FALSE, club.getRequiresSupplyStation());
            assertNull(legacy.getSupplyStationId());
            assertNull(legacy.getSupplyStationName());
            assertEquals(Long.valueOf(601L + direction), confirmed.getSupplyStationId());
            assertEquals(stations.get(confirmed.getSupplyStationId()).getAddressName(), confirmed.getSupplyStationName());
            assertEquals(Boolean.TRUE, classicUnbound.getRequiresSupplyStation());
            assertNull(classicUnbound.getSupplyStationId(), "经典主题需站不等于已经确认原站");
            assertNull(classicUnbound.getSupplyStationName());
            assertEquals(Boolean.FALSE, node.getRequiresSupplyStation());
            assertNull(node.getSupplyStationId(), "旧节点邀约不得自动变为新站点绑定");
            assertNull(node.getSupplyStationName());
            assertEquals(nodes.get(node.getNodeId()).getTopicId(), node.getTopicId());
            assertNull(club.getSupplyStationId());
            assertNull(club.getSupplyStationName());
            for (int state = 0; state < 5; state++) {
                CoopInvite source = stored.get(direction * 5 + state);
                CoopInvite view = byId.get(source.getId());
                assertNotSame(source, view);
                assertEquals(source.getTopicId(), view.getTopicId());
                assertEquals(source.getFromId(), view.getFromId());
                assertEquals(source.getFromType(), view.getFromType());
                assertEquals(source.getToId(), view.getToId());
                assertEquals(source.getToType(), view.getToType());
                assertEquals(source.getNodeId(), view.getNodeId());
                assertEquals(source.getSupplyStationId(), view.getSupplyStationId());
                assertEquals(topics.get(source.getTopicId()).getName(), view.getTopicName());
                assertEquals(source.getFromId(), topics.get(source.getTopicId()).getMemberId());
                assertNull(source.getRequiresSupplyStation(), "卡面投影不得当成原持久化事实回写");
                assertNull(source.getSupplyStationName());
                assertNull(source.getTopicName());
                assertEquals(Integer.valueOf(1), source.getStatus());
                assertEquals(Integer.valueOf(0), source.getDelFlag());
            }
        }
        verify(merchantAccessService).require(ME, MerchantPermission.COOP_MANAGE);
        verify(mapper, never()).insertCoopInvite(any());
        verify(mapper, never()).updateCoopInvite(any());
        verify(mapper, never()).expirePendingInvites(any());
        verifyNoInteractions(notifyService);
    }

    /**
     * ★ club 发布的邀约里 from_id 存的是 clubId,不是 memberId(F-53 的语义分叉)。
     * 受邀方接受后,订阅消息与 1:1 会话必须打给俱乐部主理人;把 clubId 当 memberId 用
     * 会打到"id 恰好等于 clubId 的那个成员"身上,而两处调用都裹在 catch-ignore 里,
     * 打错了零告警 —— 所以这道闸只能由断言守。
     */
    @Test
    void acceptingClubPublishedInvite_notifiesClubOwnerNotClubId() {
        Long clubId = 7001L;
        Long clubOwner = 4242L;
        com.chengyinhub.business.domain.Club club = new com.chengyinhub.business.domain.Club();
        club.setId(clubId);
        club.setMemberId(clubOwner);
        when(clubService.selectClubById(clubId)).thenReturn(club);

        com.chengyinhub.business.service.IImService imService =
                mock(com.chengyinhub.business.service.IImService.class);
        ReflectionTestUtils.setField(c, "imService", imService);

        CoopInvite stored = new CoopInvite();
        stored.setId(31L);
        stored.setStatus(0);
        stored.setInviteType(0);
        stored.setFromType("club");
        stored.setFromId(clubId);
        stored.setToType("merchant");
        stored.setToId(ME);
        stored.setTopicId(TOPIC);
        when(coopInviteService.selectCoopInviteById(31L)).thenReturn(stored);

        CoopInvite body = new CoopInvite();
        body.setId(31L);
        body.setStatus(1);

        AjaxResult r = c.handle(body);

        assertEquals("已接受", msg(r));
        verify(notifyService).notifyCoopStatus(
                org.mockito.ArgumentMatchers.eq(clubOwner), any(), any(),
                org.mockito.ArgumentMatchers.eq("已接受"), any());
        verify(notifyService, never()).notifyCoopStatus(
                org.mockito.ArgumentMatchers.eq(clubId), any(), any(), any(), any());
        verify(imService).startSingle(clubOwner, ME);
    }
    private com.chengyinhub.business.domain.dto.CoopInviteRequestResult receiptResult(CoopInvite body, boolean replayed) {
        com.chengyinhub.business.domain.CoopInviteRequestReceipt row = new com.chengyinhub.business.domain.CoopInviteRequestReceipt();
        row.setRequestId(body.getRequestId()); row.setInviteId(9007199254740993L);
        row.setInviteType(body.getInviteType()); row.setTopicId(body.getTopicId());
        row.setToType(body.getToType()); row.setToId(body.getToId());
        row.setScope(body.getScope() == null ? "" : body.getScope());
        return com.chengyinhub.business.domain.dto.CoopInviteRequestResult.created(row, replayed, body.getSupplyStationId());
    }

    private void runFirstOriginalRequestCallback() {
        org.mockito.Mockito.doAnswer(call -> {
            CoopInvite input = call.getArgument(1);
            ((Runnable) call.getArgument(2)).run();
            input.setId(9007199254740993L);
            return receiptResult(input, false);
        }).when(coopInviteService).createOrReplayInvite(org.mockito.ArgumentMatchers.eq(ME), any(), any());
    }

    @Test
    void stationCreationKeepsOperatorPrincipalSeparateFromAuthorizedContractOwnerForBothCallers() {
        for (boolean originalRequest : new boolean[] { false, true }) {
            org.mockito.Mockito.clearInvocations(coopInviteService, merchantAccessService, notifyService);
            CmsTopic topic = new CmsTopic(); topic.setId(TOPIC); topic.setMemberId(OTHER); topic.setProductType(1);
            when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
            when(merchantAccessService.require(ME, MerchantPermission.PROJECT_MANAGE))
                    .thenReturn(new MerchantAccessContext(ME, 9001L, OTHER, "manager"));
            CmsRegistrationMerchant station = new CmsRegistrationMerchant();
            station.setId(601L); station.setTopicId(TOPIC); station.setMemberId(INVITEE);
            when(coopInviteService.listSupplyStations(TOPIC, INVITEE, OTHER)).thenReturn(List.of(station));
            org.mockito.Mockito.doAnswer(call -> { ((CoopInvite) call.getArgument(0)).setId(77L); return 1; })
                    .when(coopInviteService).insertCoopInvite(any());
            runFirstOriginalRequestCallback();
            CoopInvite request = invite(0); request.setScope("MERCHANT"); request.setSupplyStationId(601L);
            request.setFromId(12345L); request.setFromType("talent"); request.setNodeId(555L);
            if (originalRequest) request.setRequestId("vr_delegated_station");

            AjaxResult result = c.invite(request);

            assertEquals(200, result.get("code"));
            assertEquals(OTHER, request.getFromId()); assertEquals("merchant", request.getFromType());
            assertEquals(Long.valueOf(601L), request.getSupplyStationId()); assertNull(request.getNodeId());
            verify(merchantAccessService).require(ME, MerchantPermission.PROJECT_MANAGE);
            verify(coopInviteService).listSupplyStations(TOPIC, INVITEE, OTHER);
            verify(coopInviteService, never()).listSupplyStations(TOPIC, INVITEE, ME);
            if (originalRequest) {
                verify(coopInviteService).createOrReplayInvite(org.mockito.ArgumentMatchers.eq(ME),
                        org.mockito.ArgumentMatchers.same(request), any());
                verify(coopInviteService, never()).insertCoopInvite(any());
                com.chengyinhub.business.domain.dto.CoopInviteRequestResult receipt =
                        (com.chengyinhub.business.domain.dto.CoopInviteRequestResult) result.get("data");
                assertEquals("601", receipt.getSupplyStationId()); assertEquals("MERCHANT", receipt.getScope());
            } else {
                verify(coopInviteService).insertCoopInvite(request);
                verify(coopInviteService, never()).createOrReplayInvite(any(), any(), any());
            }
            verify(notifyService).notifyCoopInvited(org.mockito.ArgumentMatchers.eq(INVITEE), any(), any());
        }
    }

    @Test
    void stationValidationRejectionsRemainFailuresBeforePersistenceAndNotificationForBothCallers() {
        for (boolean originalRequest : new boolean[] { false, true }) {
            for (String failure : List.of("missing", "foreign", "exception", "null-message", "unsupported", "club")) {
                org.mockito.Mockito.clearInvocations(coopInviteService, notifyService);
                CmsTopic topic = new CmsTopic(); topic.setId(TOPIC); topic.setMemberId(ME);
                topic.setProductType("unsupported".equals(failure) ? 2 : 1);
                when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
                org.mockito.Mockito.doReturn(List.of()).when(coopInviteService).listSupplyStations(TOPIC, INVITEE, ME);
                if ("exception".equals(failure) || "null-message".equals(failure))
                    org.mockito.Mockito.doThrow(new IllegalStateException("exception".equals(failure) ? "原据点不可用" : null))
                            .when(coopInviteService).listSupplyStations(TOPIC, INVITEE, ME);
                runFirstOriginalRequestCallback();
                CoopInvite request = invite("club".equals(failure) ? 1 : 0);
                if (!"missing".equals(failure)) request.setSupplyStationId(601L);
                if (originalRequest) request.setRequestId("vr_station_" + failure.replace('-', '_'));

                AjaxResult result = c.invite(request);

                assertEquals(500, result.get("code"), failure);
                String expected = switch (failure) {
                    case "missing" -> "请选择本主题该商家的真实核销据点";
                    case "foreign" -> "所选据点不属于本主题受邀商家或已绑定原合作单";
                    case "exception" -> "原据点不可用";
                    case "null-message" -> "核销据点无法确认";
                    default -> "该邀约不支持据点计酬确认";
                };
                assertEquals(expected, msg(result));
                verify(coopInviteService, never()).insertCoopInvite(any());
                verifyNoInviteNotification();
                if ("missing".equals(failure) || "unsupported".equals(failure) || "club".equals(failure))
                    verify(coopInviteService, never()).listSupplyStations(any(), any(), any());
            }
        }
    }

    @Test
    void originalRequestOnlyFirstCreationRunsContentChecksAndNotification() {
        givenTopicPublishedBy(ME);
        CoopInvite first = invite(0); first.setRequestId("vr_original");
        CoopInvite replay = invite(0); replay.setRequestId("vr_original"); replay.setGameId(55L);
        when(coopInviteService.createOrReplayInvite(org.mockito.ArgumentMatchers.eq(ME), any(), any()))
                .thenAnswer(call -> {
                    CoopInvite input = call.getArgument(1);
                    if (input == first) ((Runnable) call.getArgument(2)).run();
                    input.setId(9007199254740993L);
                    return receiptResult(input, input != first);
                });
        assertEquals(200, c.invite(first).get("code"));
        assertEquals(200, c.invite(replay).get("code"));
        verify(notifyService, org.mockito.Mockito.times(1)).notifyCoopInvited(org.mockito.ArgumentMatchers.eq(INVITEE), any(), any());
        verify(coopInviteService, never()).insertCoopInvite(any());
        verify(coopInviteService, never()).selectCoopInviteList(any());
        verify(coopInviteService, never()).selectCoopInviteListReadOnly(any());
    }

    @Test
    void originalRequestFirstCreateStillAppliesContentSecurityAndConflictStays409() {
        givenTopicPublishedBy(ME);
        CoopInvite request = invite(0); request.setRequestId("vr_original"); request.setMessage("unsafe");
        when(coopInviteService.createOrReplayInvite(org.mockito.ArgumentMatchers.eq(ME), any(), any()))
                .thenAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return receiptResult(request, false); });
        com.chengyinhub.business.service.IWxContentSecurityService security =
                (com.chengyinhub.business.service.IWxContentSecurityService) ReflectionTestUtils.getField(c, "wxContentSecurityService");
        when(security.checkText(org.mockito.ArgumentMatchers.eq("unsafe"), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn("内容审核暂时不可用");
        assertEquals(500, c.invite(request).get("code"));
        verify(security).checkText(org.mockito.ArgumentMatchers.eq("unsafe"), org.mockito.ArgumentMatchers.isNull());
        verify(coopInviteService, never()).createOrReplayInvite(any(), any(), any());
        verifyNoInviteNotification();
        when(security.checkText(org.mockito.ArgumentMatchers.eq("unsafe"), org.mockito.ArgumentMatchers.isNull())).thenReturn(null);
        when(coopInviteService.createOrReplayInvite(org.mockito.ArgumentMatchers.eq(ME), any(), any()))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException("COOP_REQUEST_CONFLICT", 409));
        assertEquals(409, c.invite(request).get("code"));
        verifyNoInviteNotification();
    }

    @Test
    void originalRequestContentSecurityRunsBeforeReceiptTransactionNotInsideCallback() {
        givenTopicPublishedBy(ME);
        CoopInvite request = invite(0); request.setRequestId("vr_original"); request.setMessage("hello");
        com.chengyinhub.business.service.IWxContentSecurityService security =
                (com.chengyinhub.business.service.IWxContentSecurityService) ReflectionTestUtils.getField(c, "wxContentSecurityService");
        when(coopInviteService.createOrReplayInvite(org.mockito.ArgumentMatchers.eq(ME), any(), any()))
                .thenAnswer(call -> {
                    // 进入原请求事务时内容安全已完成;回调(回执插入之后)不得再发起网络审核。
                    verify(security, org.mockito.Mockito.times(1)).checkText(org.mockito.ArgumentMatchers.eq("hello"), org.mockito.ArgumentMatchers.isNull());
                    ((Runnable) call.getArgument(2)).run();
                    verify(security, org.mockito.Mockito.times(1)).checkText(org.mockito.ArgumentMatchers.eq("hello"), org.mockito.ArgumentMatchers.isNull());
                    return receiptResult(request, false);
                });
        assertEquals(200, c.invite(request).get("code"));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(security, coopInviteService);
        order.verify(security).checkText(org.mockito.ArgumentMatchers.eq("hello"), org.mockito.ArgumentMatchers.isNull());
        order.verify(coopInviteService).createOrReplayInvite(org.mockito.ArgumentMatchers.eq(ME), any(), any());
    }

    private void verifyNoInviteNotification() {
        verify(notifyService, never()).notifyCoopInvited(any(), any(), any());
    }

    @Test
    void originalRequestGetAndEnumerationAreDelegatedWithOriginalPrincipalAndNoNotification() {
        when(coopInviteService.findInviteRequest(ME, "vr_original")).thenReturn(
                com.chengyinhub.business.domain.dto.CoopInviteRequestResult.state("NOT_FOUND", "vr_original"));
        when(coopInviteService.listInviteRequests(ME, 9L, 20)).thenReturn(Collections.emptyMap());
        assertEquals(200, c.inviteRequest("vr_original").get("code"));
        assertEquals(200, c.inviteRequests(9L, 20).get("code"));
        verify(coopInviteService).findInviteRequest(ME, "vr_original");
        verify(coopInviteService).listInviteRequests(ME, 9L, 20);
        verifyNoInviteNotification();
        verify(coopInviteService, never()).insertCoopInvite(any());
    }

}

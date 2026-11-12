package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GiftPlayFact;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMarketingGiftPlayEligibility;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 礼品对真实 {@code GET /api/play/nodes} 准入的影响（走 PlayEntryApplication，不是 helper 直调）。
 *
 * <p>单行语义：买家已付的 {@code cms_registration} 仍是唯一真源，钱/退款归属不动；
 * 可玩权按礼品事实判定 ——
 * <ul>
 *   <li>待领取 ⇒ 买家 402、领取人 402（无人可玩）；</li>
 *   <li>已领取给领取人 ⇒ 领取人 200，买家 402；</li>
 *   <li>无礼品 ⇒ 买家 200（旧行为不变）。</li>
 * </ul>
 *
 * <p>负控：把 {@code validSelfPlayPass} 里的礼品判定撤掉，第 1、2、3 条立刻红。
 */
class ApiPlayNodesGiftGateTest {

    private static final Long BUYER = 71L;
    private static final Long RECIPIENT = 96L;
    private static final Long TOPIC = 73L;
    private static final Long REGISTRATION = 79L;

    private ApiPlayProgressController controller;
    private ICmsRegistrationService registrationService;
    private ICmsTopicNodeService nodeService;
    private IMarketingGiftPlayEligibility giftEligibility;
    private CmsTopic topic;
    private IOmsTicketService ticketService;

    @BeforeEach
    void setUp() {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        registrationService = mock(ICmsRegistrationService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        giftEligibility = mock(IMarketingGiftPlayEligibility.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(BUYER).when(controller).getAppUserId();
        PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activityService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        PlayNodeAccessTestFixtures.setField(controller, "npcProfileService", mock(NpcProfileService.class));
        PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService",
                mock(IPlayerNodeProgressService.class));
        ICmsPointsSettingService hintSettings = mock(ICmsPointsSettingService.class);
        PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService",
                mock(ICmsMemberTemplateService.class));
        ticketService = mock(IOmsTicketService.class);
        PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", ticketService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService", mock(IPuzzlePlayService.class));
        PlayNodeAccessTestFixtures.setField(controller, "topicOnsiteMetricsMapper",
                mock(com.chengyinhub.business.mapper.TopicOnsiteMetricsMapper.class));
        PlayNodeAccessTestFixtures.setField(controller, "topicTemplateMetaMapper",
                mock(com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper.class));
        PlayNodeAccessTestFixtures.setField(controller, "merchantChapterOfferMapper",
                mock(com.chengyinhub.business.mapper.MerchantChapterOfferMapper.class));
        PlayNodeAccessTestFixtures.setField(controller, "coopPerkMapper",
                mock(com.chengyinhub.business.mapper.CoopPerkMapper.class));
        // 真实入口必须拿到同一个受控事实；null 会退化成"礼品行可玩"。
        PlayNodeAccessTestFixtures.setField(controller, "giftPlayEligibility", giftEligibility);

        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(2);
        topic.setStartDate(new Date(System.currentTimeMillis() - 86_400_000L));
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(101L);
        node.setTopicId(TOPIC);
        node.setName("幸会咖啡");
        node.setStatus(1L);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        NodeHintUnlockTestFixtures.pricing(controller, hintSettings);
    }

    @Test
    void pendingGiftDeniesBuyerSoNobodyCanPlay() {
        buyerRows();
        when(giftEligibility.playFacts(BUYER, 1L, TOPIC))
                .thenReturn(List.of(fact(GiftPlayFact.STATUS_PENDING, null)));

        AjaxResult r = controller.nodes(null, TOPIC);

        assertEquals(402, r.get(AjaxResult.CODE_TAG), "礼品待领取时买家不得读内容");
        verify(giftEligibility).playFacts(BUYER, 1L, TOPIC);
        assertEquals("请先购买自玩通行证", r.get(AjaxResult.MSG_TAG));
        verify(nodeService, never()).selectCmsTopicNodeList(any());
    }

    @Test
    void pendingGiftDeniesRecipientToo() {
        buyerRows();
        // actor 口径：待领取时领取人那边查不到任何相关来源（SQL 只回 buyer 行）。
        when(giftEligibility.playFacts(RECIPIENT, 1L, TOPIC)).thenReturn(Collections.emptyList());

        doReturn(RECIPIENT).when(controller).getAppUserId();
        AjaxResult r = controller.nodes(null, TOPIC);

        assertEquals(402, r.get(AjaxResult.CODE_TAG), "没人领取前领取人也不能玩");
        verify(nodeService, never()).selectCmsTopicNodeList(any());
    }

    @Test
    void claimedGiftAdmitsRecipientOnTheBuyersRow() {
        buyerRowLookup();
        when(giftEligibility.playFacts(RECIPIENT, 1L, TOPIC))
                .thenReturn(List.of(fact(GiftPlayFact.STATUS_CLAIMED, RECIPIENT)));

        doReturn(RECIPIENT).when(controller).getAppUserId();
        AjaxResult r = controller.nodes(null, TOPIC);

        assertEquals(200, r.get(AjaxResult.CODE_TAG), "领取人必须能进；否则礼品等于没生效");
        verify(nodeService).selectCmsTopicNodeList(any());
    }

    @Test
    void claimedGiftDeniesBuyer() {
        buyerRows();
        when(giftEligibility.playFacts(BUYER, 1L, TOPIC))
                .thenReturn(List.of(fact(GiftPlayFact.STATUS_CLAIMED, RECIPIENT)));

        AjaxResult r = controller.nodes(null, TOPIC);

        assertEquals(402, r.get(AjaxResult.CODE_TAG), "送出后买家不能再玩同一份准入");
        verify(nodeService, never()).selectCmsTopicNodeList(any());
    }

    @Test
    void legacyPassWithoutGiftStillWorksForBuyer() {
        buyerRows();
        when(giftEligibility.playFacts(BUYER, 1L, TOPIC)).thenReturn(Collections.emptyList());

        assertEquals(200, controller.nodes(null, TOPIC).get(AjaxResult.CODE_TAG),
                "无礼品的旧行为必须保持");
    }

    @Test
    void nodesProjectsFreeExploreProductTypeOverContradictoryTicketMode() {
        assertNodesProductType(2, 1, 2);
    }

    @Test
    void nodesProjectsCityProductTypeOverContradictoryTicketMode() {
        assertNodesProductType(1, 2, 1);
    }

    @Test
    void nodesProjectsLegacyFreeExploreTicketFallback() {
        assertNodesProductType(null, 2, 2);
    }

    @Test
    void nodesProjectsLegacyCityTicketFallback() {
        assertNodesProductType(null, 1, 1);
    }

    private void assertNodesProductType(Integer productType, int ticketMode, int expected) {
        topic.setProductType(productType);
        OmsTicket ticket = new OmsTicket();
        ticket.setMode(ticketMode);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.singletonList(ticket));
        buyerRows();

        AjaxResult result = controller.nodes(null, TOPIC);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(expected, data.get("productType"));
        assertEquals(expected, data.get("mode"), "The existing gameplay mode must remain unchanged");
    }

    // ------------------------------------------------------------------ helpers

    private CmsRegistration livePass() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REGISTRATION);
        r.setMemberId(BUYER);
        r.setOwnerType(1L);
        r.setOwnerId(TOPIC);
        r.setRegistrationStatus(2);
        r.setPaymentStatus(2);
        r.setPurchaseKind(2);
        r.setExpiresAt(new Date(System.currentTimeMillis() + 86_400_000L));
        return r;
    }

    /** 买家本人查询能取到该行；其他人查不到（行仍属买家）。 */
    private void buyerRows() {
        when(registrationService.selectCmsRegistrationList(any())).thenAnswer(inv -> {
            CmsRegistration probe = inv.getArgument(0);
            return BUYER.equals(probe.getMemberId()) ? List.of(livePass()) : Collections.emptyList();
        });
        when(registrationService.selectCmsRegistrationById(REGISTRATION)).thenReturn(livePass());
    }

    private void buyerRowLookup() {
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        when(registrationService.selectCmsRegistrationById(eq(REGISTRATION))).thenReturn(livePass());
    }

    private GiftPlayFact fact(int status, Long claimedMemberId) {
        GiftPlayFact fact = new GiftPlayFact();
        fact.setRegistrationId(REGISTRATION);
        fact.setStatus(status);
        fact.setClaimedMemberId(claimedMemberId);
        return fact;
    }
}

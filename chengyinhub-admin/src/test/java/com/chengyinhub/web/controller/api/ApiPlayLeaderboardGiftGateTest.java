package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.GiftPlayFact;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.chengyinhub.web.service.play.PlayPlayerQueryApplication;

/**
 * 排行榜 gate 与 /nodes 同源：礼品未领取前无人可看，领取后只有领取人。
 *
 * <p>真实入口是 {@code GET /api/play/leaderboard} → PlayPlayerQueryApplication，
 * 它此前对自玩只用 {@code validSelfPlayPass(memberId,...)}（只认买家 member_id），
 * 与 /nodes 同一个漏洞。本类钉住两端一致。
 */
class ApiPlayLeaderboardGiftGateTest {

    private static final Long BUYER = 71L;
    private static final Long RECIPIENT = 96L;
    private static final Long TOPIC = 73L;
    private static final Long REGISTRATION = 79L;

    private ApiPlayProgressController controller;
    private ICmsRegistrationService registrationService;
    private IPlayerNodeProgressService progressService;
    private ICmsTopicService topicService;
    private IMarketingGiftPlayEligibility giftEligibility;

    @BeforeEach
    void setUp() {
        registrationService = mock(ICmsRegistrationService.class);
        progressService = mock(IPlayerNodeProgressService.class);
        topicService = mock(ICmsTopicService.class);
        giftEligibility = mock(IMarketingGiftPlayEligibility.class);

        controller = spy(new ApiPlayProgressController());
        doReturn(BUYER).when(controller).getAppUserId();
        PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", mock(ICmsActivityService.class));
        PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", mock(ICmsTopicNodeService.class));
        PlayNodeAccessTestFixtures.setField(controller, "npcProfileService", mock(NpcProfileService.class));
        PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        ICmsPointsSettingService hintSettings = mock(ICmsPointsSettingService.class);
        PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService",
                mock(ICmsMemberTemplateService.class));
        PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
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
        PlayNodeAccessTestFixtures.setField(controller, "giftPlayEligibility", giftEligibility);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(2);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(progressService.selectActivityLeaderboard(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(progressService.selectRouteLeaderboard(anyLong(), anyLong(), anyInt()))
                .thenReturn(Collections.emptyList());
        NodeHintUnlockTestFixtures.pricing(controller, hintSettings);
    }

    @Test
    void pendingGiftBlocksBuyerLeaderboard() {
        buyerRows();
        when(giftEligibility.playFacts(BUYER, 1L, TOPIC))
                .thenReturn(List.of(fact(GiftPlayFact.STATUS_PENDING, null)));

        AjaxResult r = controller.leaderboard(null, String.valueOf(TOPIC));

        assertEquals(402, r.get(AjaxResult.CODE_TAG), "待领取时买家不得看榜");
        verify(progressService, never()).selectActivityLeaderboard(any(), any(), anyInt());
    }

    @Test
    void claimedGiftLetsRecipientSeeLeaderboard() {
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        when(registrationService.selectCmsRegistrationById(REGISTRATION)).thenReturn(livePass());
        when(giftEligibility.playFacts(RECIPIENT, 1L, TOPIC))
                .thenReturn(List.of(fact(GiftPlayFact.STATUS_CLAIMED, RECIPIENT)));

        doReturn(RECIPIENT).when(controller).getAppUserId();
        AjaxResult r = controller.leaderboard(null, String.valueOf(TOPIC));

        assertEquals(200, r.get(AjaxResult.CODE_TAG), "领取人必须能看榜");
    }

    @Test
    void claimedGiftBlocksBuyerLeaderboard() {
        buyerRows();
        when(giftEligibility.playFacts(BUYER, 1L, TOPIC))
                .thenReturn(List.of(fact(GiftPlayFact.STATUS_CLAIMED, RECIPIENT)));

        AjaxResult r = controller.leaderboard(null, String.valueOf(TOPIC));

        assertEquals(402, r.get(AjaxResult.CODE_TAG), "送出后买家不能再当作持有者");
    }

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

    private void buyerRows() {
        when(registrationService.selectCmsRegistrationList(any())).thenAnswer(inv -> {
            CmsRegistration probe = inv.getArgument(0);
            return BUYER.equals(probe.getMemberId()) ? List.of(livePass()) : Collections.emptyList();
        });
        when(registrationService.selectCmsRegistrationById(REGISTRATION)).thenReturn(livePass());
    }

    private GiftPlayFact fact(int status, Long claimedMemberId) {
        GiftPlayFact fact = new GiftPlayFact();
        fact.setRegistrationId(REGISTRATION);
        fact.setStatus(status);
        fact.setClaimedMemberId(claimedMemberId);
        return fact;
    }
}

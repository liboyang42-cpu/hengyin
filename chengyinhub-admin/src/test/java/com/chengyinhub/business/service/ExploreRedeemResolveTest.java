package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.mapper.RegistrationEntitlementMapper;
import com.chengyinhub.business.service.impl.PerkLedgerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-14 扫码反推章节 —— {该商家在本主题的生效供给章节} ∩ {该票尚欠的章节}。
 *
 * <p><b>为什么 chapterId 必须反推而不是让前端传</b>:核销码是**玩家**在票夹点「出示入场码」时
 * 对自己的 registrationId 签的,**那一刻玩家还没走进哪家店** —— chapter 是到店那一刻由
 * **商家身份**决定的,天然属于扫码方上下文,不属于码。反推方案同时绕开了另一个存量断链:
 * 商家扫码端 {@code JSON.parse(res.result)} 取 {type, code},而玩家侧渲染的是裸串
 * {@code v1.{regId}...} ⇒ 动态码永远送不进商家端。
 *
 * <p>⚠️ 负控已实测(注入→红→撤销→绿):
 * <ul>
 *   <li>反推不按 merchantId 过滤 offer ⇒ otherMerchantsChapters_excluded 红(越权核销别人的章)</li>
 *   <li>反推不按 status=0 过滤 entitlement ⇒ redeemedChapter_excluded 红(重复核销)</li>
 * </ul>
 */
class ExploreRedeemResolveTest {

    private static final Long REG = 900L;
    private static final Long TOPIC = 10L;
    private static final Long CH1 = 101L;
    private static final Long CH2 = 102L;
    private static final Long CH3 = 103L;
    private static final Long ME = 500L;
    private static final Long OTHER = 600L;

    private MerchantChapterOfferMapper offerMapper;
    private RegistrationEntitlementMapper entitlementMapper;
    private PerkLedgerServiceImpl service;

    @BeforeEach
    void setUp() {
        offerMapper = mock(MerchantChapterOfferMapper.class);
        entitlementMapper = mock(RegistrationEntitlementMapper.class);

        service = new PerkLedgerServiceImpl();
        ReflectionTestUtils.setField(service, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(service, "entitlementMapper", entitlementMapper);
        // [P0-14] 撤销核销时清 verification_status(与「首次核销就置位」成对);本类不测该链路,注入空 mock。
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper",
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "chapterMapper", mock(CmsTopicChapterMapper.class));
        ReflectionTestUtils.setField(service, "redemptionMapper", mock(RedemptionMapper.class));
    }

    private MerchantChapterOffer offer(Long chapterId, Long merchantId) {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setTopicId(TOPIC);
        o.setChapterId(chapterId);
        o.setMerchantId(merchantId);
        return o;
    }

    private RegistrationEntitlement ent(Long chapterId, int status) {
        RegistrationEntitlement e = new RegistrationEntitlement();
        e.setRegistrationId(REG);
        e.setChapterId(chapterId);
        e.setTopicId(TOPIC);
        e.setStatus(status);
        return e;
    }

    private List<Long> resolve() {
        return service.resolveRedeemableChapters(REG, TOPIC, ME);
    }

    // ------------------------------------------------- 交集恰好 1 个

    @Test
    void singleMatch_returnsThatChapter() {
        when(offerMapper.selectByTopic(TOPIC)).thenReturn(Collections.singletonList(offer(CH1, ME)));
        when(entitlementMapper.selectByRegistration(REG))
                .thenReturn(Arrays.asList(ent(CH1, 0), ent(CH2, 0)));

        assertEquals(Collections.singletonList(CH1), resolve());
    }

    // ------------------------------------------------- 交集 ≥2:必须让商家选,不许默默取第一个

    /**
     * ★ 一个商家在同一主题承接多章是**真实可能**的:uk_offer_merchant_chapter 只约束
     * 「一商家一章一行」,**不阻止一商家多章**。这时玩家两章都欠 ⇒ 必须有分支,
     * 不能假设唯一(默默取第一个 = 核销错章 = 玩家在另一章白跑一趟)。
     */
    @Test
    void multipleMatches_returnsAllForMerchantToChoose() {
        when(offerMapper.selectByTopic(TOPIC))
                .thenReturn(Arrays.asList(offer(CH1, ME), offer(CH2, ME)));
        when(entitlementMapper.selectByRegistration(REG))
                .thenReturn(Arrays.asList(ent(CH1, 0), ent(CH2, 0)));

        assertEquals(Arrays.asList(CH1, CH2), resolve());
    }

    /** 升序返回,口径稳定(商家侧列表顺序不随 mapper 返回顺序抖动)。 */
    @Test
    void resultIsSortedAscending() {
        when(offerMapper.selectByTopic(TOPIC))
                .thenReturn(Arrays.asList(offer(CH3, ME), offer(CH1, ME), offer(CH2, ME)));
        when(entitlementMapper.selectByRegistration(REG))
                .thenReturn(Arrays.asList(ent(CH3, 0), ent(CH1, 0), ent(CH2, 0)));

        assertEquals(Arrays.asList(CH1, CH2, CH3), resolve());
    }

    // ------------------------------------------------- 交集 0

    /** ★ 别家的章节不许出现在我的候选里 —— 否则商家 A 能核销商家 B 那一章。 */
    @Test
    void otherMerchantsChapters_excluded() {
        when(offerMapper.selectByTopic(TOPIC))
                .thenReturn(Arrays.asList(offer(CH1, OTHER), offer(CH2, OTHER)));
        when(entitlementMapper.selectByRegistration(REG))
                .thenReturn(Arrays.asList(ent(CH1, 0), ent(CH2, 0)));

        assertTrue(resolve().isEmpty(), "该商家在本路线没有供给 ⇒ 候选必须为空");
    }

    /** ★ 已核销(status=1)的章不许再出现 —— 否则同一章能被重复核销。 */
    @Test
    void redeemedChapter_excluded() {
        when(offerMapper.selectByTopic(TOPIC)).thenReturn(Collections.singletonList(offer(CH1, ME)));
        when(entitlementMapper.selectByRegistration(REG))
                .thenReturn(Collections.singletonList(ent(CH1, 1)));   // 已核销

        assertTrue(resolve().isEmpty());
    }

    /** 已失效(status=2,退款)的章同样不许出现。 */
    @Test
    void invalidatedChapter_excluded() {
        when(offerMapper.selectByTopic(TOPIC)).thenReturn(Collections.singletonList(offer(CH1, ME)));
        when(entitlementMapper.selectByRegistration(REG))
                .thenReturn(Collections.singletonList(ent(CH1, 2)));   // 已退款失效

        assertTrue(resolve().isEmpty());
    }

    /** 商家有供给但玩家这张票没买那一章 ⇒ 空。 */
    @Test
    void merchantChapterNotInTicket_excluded() {
        when(offerMapper.selectByTopic(TOPIC)).thenReturn(Collections.singletonList(offer(CH3, ME)));
        when(entitlementMapper.selectByRegistration(REG))
                .thenReturn(Arrays.asList(ent(CH1, 0), ent(CH2, 0)));

        assertTrue(resolve().isEmpty());
    }

    // ------------------------------------------------- 边界

    @Test
    void nullArgs_returnEmptyNotNpe() {
        assertTrue(service.resolveRedeemableChapters(null, TOPIC, ME).isEmpty());
        assertTrue(service.resolveRedeemableChapters(REG, null, ME).isEmpty());
        assertTrue(service.resolveRedeemableChapters(REG, TOPIC, null).isEmpty());
    }

    /** mapper 返 null 不该炸(与本仓既有 nz/emptyList 兜底口径一致)。 */
    @Test
    void nullMapperResults_returnEmptyNotNpe() {
        when(offerMapper.selectByTopic(TOPIC)).thenReturn(null);
        assertTrue(resolve().isEmpty());

        when(offerMapper.selectByTopic(TOPIC)).thenReturn(Collections.singletonList(offer(CH1, ME)));
        when(entitlementMapper.selectByRegistration(REG)).thenReturn(null);
        assertTrue(resolve().isEmpty());
    }

    /**
     * 走的是 selectByTopic(已含 liveOffer 两条轴:status=1 且 valid_end 未过期),
     * 不是裸查 —— 过期供给不该让商家还能核销一个他其实已经退出的章节。
     */
    @Test
    void usesLiveOfferQuery() {
        when(offerMapper.selectByTopic(TOPIC)).thenReturn(Collections.<MerchantChapterOffer>emptyList());
        resolve();
        org.mockito.Mockito.verify(offerMapper).selectByTopic(TOPIC);
    }
}

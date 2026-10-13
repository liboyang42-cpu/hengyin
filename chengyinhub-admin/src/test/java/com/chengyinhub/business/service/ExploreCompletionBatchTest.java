package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.mapper.RegistrationEntitlementMapper;
import com.chengyinhub.business.service.impl.PerkLedgerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-17 批量通关口径(俱乐部看板专用)。
 *
 * <p><b>为什么必须批量</b>:ApiStatsController.club 是唯一「一次算 M 玩家 × N 主题」的场景 ——
 * 手上既没 registrationId、也**没有任何一个 member 是固定的** ⇒ 循环调 isExploreCompleted
 * = 2 × M玩家 × N主题(20 主题 × 200 玩家 = 8000 次查询)会把看板拖垮。
 *
 * <p>⚠️ 负控已实测(注入→红→撤销→绿):
 * <ul>
 *   <li>{@code required.isEmpty()} 改成返「全员通关」⇒ topicWithoutRequiredChapters_nobodyCompleted 红</li>
 *   <li>把 {@code cnt >= required.size()} 改成 {@code cnt > 0} ⇒ partialRedeem_notCompleted 红</li>
 * </ul>
 */
class ExploreCompletionBatchTest {

    private static final Long TOPIC = 10L;
    private static final Long CH1 = 101L;
    private static final Long CH2 = 102L;
    private static final Long M1 = 1L;
    private static final Long M2 = 2L;

    private CmsTopicChapterMapper chapterMapper;
    private RedemptionMapper redemptionMapper;
    private PerkLedgerServiceImpl service;

    @BeforeEach
    void setUp() {
        chapterMapper = mock(CmsTopicChapterMapper.class);
        redemptionMapper = mock(RedemptionMapper.class);
        service = new PerkLedgerServiceImpl();
        ReflectionTestUtils.setField(service, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "topicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(service, "redemptionMapper", redemptionMapper);
        // [P0-14] 撤销核销时清 verification_status(与「首次核销就置位」成对);本类不测该链路,注入空 mock。
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper",
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "offerMapper", mock(MerchantChapterOfferMapper.class));
        ReflectionTestUtils.setField(service, "entitlementMapper", mock(RegistrationEntitlementMapper.class));
    }

    private CmsTopicChapter chapter(Long id) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(id);
        c.setTopicId(TOPIC);
        c.setRequired(1);
        return c;
    }

    private void givenRequired(Long... ids) {
        List<CmsTopicChapter> list = new ArrayList<>();
        for (Long id : ids) list.add(chapter(id));
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(list);
    }

    private Map<String, Object> row(Long memberId, Long regId, int cnt) {
        Map<String, Object> m = new HashMap<>();
        m.put("memberId", memberId);
        m.put("registrationId", regId);
        m.put("cnt", cnt);
        return m;
    }

    // ------------------------------------------------- 正控

    /** 核销满全部必选章 ⇒ 通关。 */
    @Test
    void allRequiredRedeemed_completed() {
        givenRequired(CH1, CH2);
        when(redemptionMapper.selectRedeemedCountsByChapters(any()))
                .thenReturn(Collections.singletonList(row(M1, 900L, 2)));

        assertEquals(Collections.singleton(M1), service.exploreCompletion(TOPIC).completed);
    }

    /** ★ 只核销一半 ⇒ **不通关**。这是本次改造的核心行为。 */
    @Test
    void partialRedeem_notCompleted() {
        givenRequired(CH1, CH2);
        when(redemptionMapper.selectRedeemedCountsByChapters(any()))
                .thenReturn(Collections.singletonList(row(M1, 900L, 1)));   // 3 章里只核销 1 章

        assertTrue(service.exploreCompletion(TOPIC).completed.isEmpty());
        // 但他是 participant —— 有过核销就算参与
        assertEquals(Collections.singleton(M1), service.exploreCompletion(TOPIC).participants);
    }

    @Test
    void mixedMembers_onlyFullyRedeemedAreCompleted() {
        givenRequired(CH1, CH2);
        when(redemptionMapper.selectRedeemedCountsByChapters(any()))
                .thenReturn(Arrays.asList(row(M1, 900L, 2), row(M2, 901L, 1)));

        assertEquals(Collections.singleton(M1), service.exploreCompletion(TOPIC).completed);
        // participants 是「有过核销的人」的全集,不是「通关的人」—— 分母口径必须比分子宽
        assertEquals(2, service.exploreCompletion(TOPIC).participants.size());
    }

    // ------------------------------------------------- empty 语义(批量版最容易写错的地方)

    /**
     * ★ 没有必选章 ⇒ **全员 false**(不是全员 true)。
     *
     * <p>与 isExploreCompleted 同口径:「没有必选章就没有『通关』可言,别误发勋章」。
     * 顺手返 allMembers 就会把没配章的 ③ 主题全判成通关。
     * ⚠️ 同一个输入,topicSellableCapacity 返 MAX_VALUE(放行)、这里返 false(不通关)——
     * 两者语义**刻意相反且都正确**(能卖 ≠ 能通关),别当统一分支处理。
     */
    @Test
    void topicWithoutRequiredChapters_nobodyCompleted() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(Collections.<CmsTopicChapter>emptyList());

        assertTrue(service.exploreCompletion(TOPIC).completed.isEmpty());
        assertTrue(service.exploreCompletion(TOPIC).participants.isEmpty());
    }

    /**
     * ★ 空 chapterIds 绝不能进 foreach —— MyBatis 会生成 `in ()`,是 SQL 语法错。
     * 必须在 Java 侧提前 return。
     */
    @Test
    void emptyChapterIds_neverHitsSqlNoInParens() {
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(Collections.<CmsTopicChapter>emptyList());

        service.exploreCompletion(TOPIC);

        verify(redemptionMapper, never()).selectRedeemedCountsByChapters(any());
    }

    // ------------------------------------------------- N+1 守卫

    /**
     * ★ 查询次数**与玩家数无关**。
     * 有人把批量改回循环调 isExploreCompleted 时,这条红。
     */
    @Test
    void queryCount_isConstantInPlayerCount() {
        givenRequired(CH1, CH2);

        // 2 个玩家
        when(redemptionMapper.selectRedeemedCountsByChapters(any()))
                .thenReturn(Arrays.asList(row(M1, 900L, 2), row(M2, 901L, 2)));
        service.exploreCompletion(TOPIC);
        verify(redemptionMapper, times(1)).selectRedeemedCountsByChapters(any());

        // 20 个玩家 —— 查询次数必须一样
        org.mockito.Mockito.reset(redemptionMapper);
        List<Map<String, Object>> many = new ArrayList<>();
        for (long i = 0; i < 20; i++) many.add(row(i, 900L + i, 2));
        when(redemptionMapper.selectRedeemedCountsByChapters(any())).thenReturn(many);
        service.exploreCompletion(TOPIC);
        verify(redemptionMapper, times(1)).selectRedeemedCountsByChapters(any());
    }

    @Test
    void nullMapperResult_returnsEmptyNotNpe() {
        givenRequired(CH1);
        when(redemptionMapper.selectRedeemedCountsByChapters(any())).thenReturn(null);

        assertTrue(service.exploreCompletion(TOPIC).completed.isEmpty());
    }
}

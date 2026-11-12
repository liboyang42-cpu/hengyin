package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.PublicCatalogQuery;
import com.chengyinhub.business.domain.dto.PublicContentCatalogRow;
import com.chengyinhub.business.domain.vo.PublicContentCatalogDetailVO;
import com.chengyinhub.business.domain.vo.PublicContentCatalogItemVO;
import com.chengyinhub.business.domain.vo.PublicContentCatalogMapPointVO;
import com.chengyinhub.business.mapper.PublicContentCatalogMapper;
import com.chengyinhub.business.service.impl.PublicContentCatalogServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M7 公开目录投影的纯逻辑测试：语言回退、价格口径、售卖状态、交付形态、售前预览与隐私边界。
 * 真库公开闸与分页由 PublicContentCatalogMyBatisIT 覆盖。
 */
class PublicContentCatalogServiceTest {

    private PublicContentCatalogMapper mapper;
    private PublicContentCatalogServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(PublicContentCatalogMapper.class);
        service = new PublicContentCatalogServiceImpl();
        ReflectionTestUtils.setField(service, "catalogMapper", mapper);
        when(mapper.selectChapterCounts(anyList())).thenReturn(new ArrayList<Map<String, Object>>());
        when(mapper.selectTicketAggregates(anyList())).thenReturn(new ArrayList<Map<String, Object>>());
        when(mapper.selectSaleRevisions(anyList())).thenReturn(new ArrayList<Map<String, Object>>());
        when(mapper.selectApprovedLocales(anyList())).thenReturn(new ArrayList<Map<String, Object>>());
        when(mapper.selectCityNames(anyList())).thenReturn(new ArrayList<Map<String, Object>>());
    }

    // ---------------------------------------------------------------- 语言回退

    @Test
    void missingTranslationFallsBackToSourceLocaleAndSaysSo() {
        PublicContentCatalogRow row = row(1L, "上海夜行", "副标题", "这里是一段很长的中文简介，讲清这一局要做什么。");
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row));

        PublicContentCatalogItemVO item = service.list(new PublicCatalogQuery()).get(0);

        assertEquals("上海夜行", item.getName());
        assertEquals("zh-CN", item.getRequestedLocale());
        assertEquals("zh-CN", item.getActualLocale());
        assertEquals("SOURCE", item.getTranslationState());
        assertEquals("FALLBACK", item.getTranslationFields().get("title"));
    }

    @Test
    void requestedEnglishWithoutApprovedRowIsReportedAsFallbackNotTranslated() {
        PublicContentCatalogRow row = row(1L, "上海夜行", "副标题", "简介");
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row));

        PublicCatalogQuery query = new PublicCatalogQuery();
        query.setLocale("en-US");
        PublicContentCatalogItemVO item = service.list(query).get(0);

        assertEquals("en-US", item.getRequestedLocale());
        // 实际返回的是中文原文，不能报成 en-US。
        assertEquals("zh-CN", item.getActualLocale());
        assertEquals("FALLBACK", item.getTranslationState());
        assertEquals("FALLBACK", item.getTranslationFields().get("title"));
        assertEquals("FALLBACK", item.getTranslationFields().get("summary"));
    }

    @Test
    void partiallyTranslatedItemIsPartialAndMarksEachField() {
        PublicContentCatalogRow row = row(1L, "上海夜行", "副标题", "简介");
        row.setTranslatedTitle("Shanghai Night Walk");
        // 副标题与摘要没译 → 不得整体标成 TRANSLATED
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row));

        PublicCatalogQuery query = new PublicCatalogQuery();
        query.setLocale("en");
        PublicContentCatalogItemVO item = service.list(query).get(0);

        assertEquals("Shanghai Night Walk", item.getName());
        assertEquals("副标题", item.getSubtitle());
        assertEquals("PARTIAL", item.getTranslationState());
        assertEquals("TRANSLATED", item.getTranslationFields().get("title"));
        assertEquals("FALLBACK", item.getTranslationFields().get("subtitle"));
        assertEquals("FALLBACK", item.getTranslationFields().get("summary"));
    }

    @Test
    void fullyTranslatedItemReportsTranslatedAndKeepsRequestedLocale() {
        PublicContentCatalogRow row = row(1L, "上海夜行", "副标题", "简介");
        row.setTranslatedTitle("Shanghai Night Walk");
        row.setTranslatedSubtitle("Sub");
        row.setTranslatedSummary("Summary");
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row));

        PublicCatalogQuery query = new PublicCatalogQuery();
        query.setLocale("en-us");
        PublicContentCatalogItemVO item = service.list(query).get(0);

        assertEquals("TRANSLATED", item.getTranslationState());
        assertEquals("en-US", item.getActualLocale());
    }

    // ---------------------------------------------------------------- 价格口径

    @Test
    void cheapestSelfPlayPriceWinsAndIsLabelledAsPass() {
        PublicContentCatalogRow row = row(1L, "A", null, null);
        row.setSelfPlay(1);
        row.setSelfPlayPrice(new BigDecimal("29.00"));
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row));
        when(mapper.selectTicketAggregates(anyList())).thenReturn(ticketAggregate(1L, "59.00", 1, 0, 1));

        PublicContentCatalogItemVO item = service.list(new PublicCatalogQuery()).get(0);

        assertEquals(new BigDecimal("29.00"), item.getPriceFrom());
        assertEquals("SELF_PLAY_PASS", item.getPriceUnit());
        assertEquals("KNOWN", item.getPriceState());
    }

    @Test
    void ticketOnlyPriceIsLabelledAsTicketNotPass() {
        PublicContentCatalogRow row = row(1L, "A", null, null);
        row.setSelfPlay(0);
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row));
        when(mapper.selectTicketAggregates(anyList())).thenReturn(ticketAggregate(1L, "59.00", 1, 0, 1));

        PublicContentCatalogItemVO item = service.list(new PublicCatalogQuery()).get(0);

        assertEquals(new BigDecimal("59.00"), item.getPriceFrom());
        assertEquals("TICKET", item.getPriceUnit());
    }

    @Test
    void unreadablePriceIsUnknownInsteadOfStaleValue() {
        PublicContentCatalogRow row = row(1L, "A", null, null);
        row.setSelfPlay(0);
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row));

        PublicContentCatalogItemVO item = service.list(new PublicCatalogQuery()).get(0);

        assertNull(item.getPriceFrom());
        assertNull(item.getPriceUnit());
        assertEquals("UNKNOWN", item.getPriceState());
    }

    // ---------------------------------------------------------------- 售卖状态

    @Test
    void saleStateFollowsLifecycleAndSaleEnd() {
        long future = System.currentTimeMillis() + 86_400_000L;
        long past = System.currentTimeMillis() - 86_400_000L;

        assertEquals("ON_SALE", saleState(3, new Date(future)));
        assertEquals("SALE_ENDED", saleState(3, new Date(past)));
        assertEquals("SALE_ENDED", saleState(4, null));
        assertEquals("SALE_ENDED", saleState(6, null));
        assertEquals("NOT_ON_SALE", saleState(2, new Date(future)));
        assertEquals("NOT_ON_SALE", saleState(null, new Date(future)));
        // SELLING 但售止缺失：既有售卖闸会拒绝，目录也不能声称在售。
        assertEquals("NOT_ON_SALE", saleState(3, null));
    }

    @Test
    void freeSelfPlayNeedsZeroPriceAndUnpassedDeadline() {
        long future = System.currentTimeMillis() + 86_400_000L;
        long past = System.currentTimeMillis() - 86_400_000L;

        assertTrue(freeSelfPlay(1, "0.00", new Date(future)));
        assertFalse(freeSelfPlay(1, "0.01", new Date(future)));
        assertFalse(freeSelfPlay(0, "0.00", new Date(future)));
        assertFalse(freeSelfPlay(1, "0.00", new Date(past)));
        // 无截止视为长期开放(与免费主题在架判定一致)
        assertTrue(freeSelfPlay(1, "0.00", null));
    }

    // ---------------------------------------------------------------- 交付形态与落地配置

    @Test
    void deliveryModesComeFromRealConfig() {
        PublicContentCatalogRow row = row(1L, "A", null, null);
        row.setSelfPlay(1);
        row.setClubId(9L);
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row));
        when(mapper.selectTicketAggregates(anyList())).thenReturn(ticketAggregate(1L, "59.00", 1, 0, 1));

        PublicContentCatalogItemVO item = service.list(new PublicCatalogQuery()).get(0);

        assertEquals(Arrays.asList("SELF_PLAY", "GUIDED_SESSION", "CLUB_HOSTED"), item.getDeliveryModes());
    }

    @Test
    void missingLandingMetadataStaysUnknownInsteadOfFalse() {
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row(1L, "A", null, null)));

        PublicContentCatalogItemVO item = service.list(new PublicCatalogQuery()).get(0);

        // 「没填」不能显示成「不接待团体」。
        assertNull(item.getGroupEligible());
        assertNull(item.getSchoolEligible());
        assertNull(item.getAgeMin());
        assertNull(item.getAccessibilityNote());
    }

    @Test
    void landingMetadataIsExposedWhenPresent() {
        PublicContentCatalogRow row = row(1L, "A", null, null);
        row.setGroupEligible(1);
        row.setSchoolEligible(0);
        row.setGroupMinPeople(8);
        row.setGroupMaxPeople(30);
        row.setAgeMin(8);
        row.setAgeMax(14);
        row.setAccessibilityNote("全程坡道，雨天改期");
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row));

        PublicContentCatalogItemVO item = service.list(new PublicCatalogQuery()).get(0);

        assertEquals(Boolean.TRUE, item.getGroupEligible());
        assertEquals(Boolean.FALSE, item.getSchoolEligible());
        assertEquals(Integer.valueOf(8), item.getGroupMinPeople());
        assertEquals(Integer.valueOf(30), item.getGroupMaxPeople());
        assertEquals(Integer.valueOf(8), item.getAgeMin());
        assertEquals("全程坡道，雨天改期", item.getAccessibilityNote());
    }

    // ---------------------------------------------------------------- 修订身份

    @Test
    void saleSnapshotMarksRevisionAsSaleFrozen() {
        PublicContentCatalogRow row = row(1L, "A", null, null);
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row));
        Map<String, Object> snapshot = new HashMap<String, Object>();
        snapshot.put("topicId", 1L);
        snapshot.put("saleSnapshotId", 77L);
        snapshot.put("frozenAt", new Date(1_700_000_000_000L));
        when(mapper.selectSaleRevisions(anyList())).thenReturn(Collections.singletonList(snapshot));

        PublicContentCatalogItemVO item = service.list(new PublicCatalogQuery()).get(0);

        assertEquals("SALE_FROZEN", item.getRevision().getState());
        assertEquals(Long.valueOf(77L), item.getRevision().getSaleSnapshotId());
    }

    @Test
    void noSnapshotAndNoPublishedConfigIsNotClaimedAsSellableRevision() {
        PublicContentCatalogRow row = row(1L, "A", null, null);
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row));

        PublicContentCatalogItemVO item = service.list(new PublicCatalogQuery()).get(0);

        assertEquals("NONE", item.getRevision().getState());
        assertNull(item.getRevision().getSaleSnapshotId());
    }

    // ---------------------------------------------------------------- 预览与隐私

    @Test
    void chapterWithNullNameKeepsItsSlotWithUnnamedLabelInsteadOfNpe() {
        // MyBatis 对「唯一一列 name 为 NULL」的行返回 null map(f-rehearsal 冒烟 /info 500 的复现)。
        PublicContentCatalogRow row = row(1L, "A", null, null);
        when(mapper.selectCatalogItemById(eq(1L), any())).thenReturn(row);
        when(mapper.selectChapterNames(eq(1L))).thenReturn(Arrays.<Map<String, Object>>asList(
                mapOf("chapterName", "第一章"), null, mapOf("chapterName", null)));
        when(mapper.selectFirstChapter(eq(1L))).thenReturn(mapOf("chapterId", 5L, "chapterName", "第一章"));
        when(mapper.selectPreviewStops(eq(1L), anyInt())).thenReturn(new ArrayList<Map<String, Object>>());

        PublicContentCatalogDetailVO detail = service.detail(1L, "zh-CN");

        assertEquals(Arrays.asList("第一章", "未命名章节", "未命名章节"), detail.getChapterNames());
    }

    @Test
    void previewContainsOnlyFirstChapterHooksAndNeverAnswersOrFragments() throws Exception {
        PublicContentCatalogRow row = row(1L, "A", null, null);
        when(mapper.selectCatalogItemById(eq(1L), any())).thenReturn(row);
        when(mapper.selectChapterNames(eq(1L))).thenReturn(Arrays.<Map<String, Object>>asList(
                mapOf("chapterName", "第一章"), mapOf("chapterName", "第二章")));
        when(mapper.selectFirstChapter(eq(1L))).thenReturn(mapOf("chapterId", 5L, "chapterName", "第一章"));
        when(mapper.selectPreviewStops(eq(1L), anyInt())).thenReturn(Arrays.<Map<String, Object>>asList(
                mapOf("name", "外滩", "hookTeaser", "看那块表停在几点"),
                mapOf("name", "弄堂", "hookTeaser", "谁在这里等你")));

        PublicContentCatalogDetailVO detail = service.detail(1L, "en");

        assertEquals(Arrays.asList("第一章", "第二章"), detail.getChapterNames());
        assertEquals(2, detail.getPreview().getStops().size());
        assertEquals("第一章", detail.getPreview().getFirstChapterName());
        assertTrue(detail.getPreview().getStops().get(0).getHookTeaser().startsWith("看那块表"));

        String json = new ObjectMapper().writeValueAsString(detail);
        // 隐私负例：答案/提示/核销码/剧情碎片/联系方式任何字段名都不允许出现在响应里。
        for (String forbidden : Arrays.asList("questionAnswer", "correctAnswer", "hint1", "hint2",
                "answerReveal", "checkinCode", "fragmentText", "email", "phone", "blocksJson",
                "routeGraphJson", "canonicalJson", "merchantGuide")) {
            assertFalse(json.contains("\"" + forbidden + "\""), "响应不得包含 " + forbidden);
        }
    }

    @Test
    void unpublicDetailIsReportedAsNotFoundNotAsForbidden() {
        when(mapper.selectCatalogItemById(eq(9L), any())).thenReturn(null);

        ServiceException failure = assertThrows(ServiceException.class, () -> service.detail(9L, null));
        assertEquals(Integer.valueOf(404), failure.getCode());
    }

    // ---------------------------------------------------------------- 批量形态

    @Test
    void byIdsKeepsRequestedOrderAndDropsUnpublicIds() {
        PublicContentCatalogRow first = row(1L, "A", null, null);
        PublicContentCatalogRow third = row(3L, "C", null, null);
        when(mapper.selectCatalogItemsByIds(anyList(), any()))
                .thenReturn(Arrays.asList(third, first));

        List<PublicContentCatalogItemVO> items = service.byIds(Arrays.asList(1L, 2L, 3L), null);

        assertEquals(2, items.size());
        assertEquals(Long.valueOf(1L), items.get(0).getId());
        assertEquals(Long.valueOf(3L), items.get(1).getId());
    }

    @Test
    void byIdsRejectsOversizedBatch() {
        List<Long> ids = new ArrayList<Long>();
        for (long i = 1; i <= 101; i++) ids.add(i);
        ServiceException failure = assertThrows(ServiceException.class, () -> service.byIds(ids, null));
        assertEquals(Integer.valueOf(400), failure.getCode());
    }

    @Test
    void mapProjectionKeepsTopicWithoutPublicCoordinates() {
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row(1L, "A", null, null)));
        when(mapper.selectMapPoints(anyList())).thenReturn(new ArrayList<PublicContentCatalogMapPointVO>());

        List<PublicContentCatalogMapPointVO> points = service.mapPoints(new PublicCatalogQuery());

        assertEquals(1, points.size());
        assertNull(points.get(0).getLatitude());
        assertEquals("/city/310100/quest/1", points.get(0).getCanonicalPath());
    }

    // ---------------------------------------------------------------- 参数校验

    @Test
    void invalidEnumsFailClosedInsteadOfFallingBackToAll() {
        assertBadRequest(() -> service.list(queryWith("SELF_PLAY_ONLY", null, null, null, null, null)));
        assertBadRequest(() -> service.list(queryWith(null, 3, null, null, null, null)));
        assertBadRequest(() -> service.list(queryWith(null, null, 2, null, null, null)));
        assertBadRequest(() -> service.list(queryWith(null, null, null, "YESTERDAY", null, null)));
        assertBadRequest(() -> service.list(queryWith(null, null, null, null, 40, 10)));
        PublicCatalogQuery badCity = new PublicCatalogQuery();
        badCity.setCityCode("3101");
        assertBadRequest(() -> service.list(badCity));
    }

    @Test
    void invalidLocaleIsRejectedNotSilentlyReplaced() {
        PublicCatalogQuery query = new PublicCatalogQuery();
        query.setLocale("zh_CN!!");
        assertBadRequest(() -> service.list(query));
    }

    @Test
    void routeIdIsStableFromTopicIdAndCityCode() {
        assertEquals("/city/310100/quest/42", PublicContentCatalogServiceImpl.canonicalPath("310100", 42L));
        assertEquals("/quest/42", PublicContentCatalogServiceImpl.canonicalPath(null, 42L));
        assertNotEquals(PublicContentCatalogServiceImpl.canonicalPath("310100", 42L),
                PublicContentCatalogServiceImpl.canonicalPath("440300", 42L));
    }

    // ---------------------------------------------------------------- helpers

    private String saleState(Integer lifecycle, Date saleEnd) {
        PublicContentCatalogRow row = row(1L, "A", null, null);
        row.setLifecycle(lifecycle);
        row.setSaleEndAt(saleEnd);
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row));
        return service.list(new PublicCatalogQuery()).get(0).getSaleState();
    }

    private boolean freeSelfPlay(Integer selfPlay, String price, Date deadline) {
        PublicContentCatalogRow row = row(1L, "A", null, null);
        row.setSelfPlay(selfPlay);
        row.setSelfPlayPrice(price == null ? null : new BigDecimal(price));
        row.setSaleEndAt(deadline);
        when(mapper.selectCatalogItems(any(PublicCatalogQuery.class)))
                .thenReturn(Collections.singletonList(row));
        return Boolean.TRUE.equals(service.list(new PublicCatalogQuery()).get(0).getFreeSelfPlay());
    }

    private PublicCatalogQuery queryWith(String deliveryMode, Integer productType, Integer groupEligible,
                                         String sort, Integer minMinutes, Integer maxMinutes) {
        PublicCatalogQuery query = new PublicCatalogQuery();
        query.setDeliveryMode(deliveryMode);
        query.setProductType(productType);
        query.setGroupEligible(groupEligible == null ? null : groupEligible);
        query.setSort(sort);
        query.setMinMinutes(minMinutes);
        query.setMaxMinutes(maxMinutes);
        return query;
    }

    private static void assertBadRequest(org.junit.jupiter.api.function.Executable action) {
        ServiceException failure = assertThrows(ServiceException.class, action);
        assertEquals(Integer.valueOf(400), failure.getCode());
    }

    private static PublicContentCatalogRow row(Long id, String name, String subtitle, String description) {
        PublicContentCatalogRow row = new PublicContentCatalogRow();
        row.setId(id);
        row.setCityCode("310100");
        row.setName(name);
        row.setSubtitle(subtitle);
        row.setDescription(description);
        row.setLifecycle(3);
        row.setSaleEndAt(new Date(System.currentTimeMillis() + 86_400_000L));
        return row;
    }

    private static List<Map<String, Object>> ticketAggregate(Long topicId, String minPrice, int guided,
                                                             int self, int live) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("topicId", topicId);
        row.put("minTicketPrice", new BigDecimal(minPrice));
        row.put("guidedCount", guided);
        row.put("selfSoldCount", self);
        row.put("liveTicketCount", live);
        return Collections.singletonList(row);
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}

package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.mapper.MerchantDeskMapper;
import com.chengyinhub.business.service.impl.MerchantDeskServiceImpl;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantDeskServiceImplTest {

    private static final long MERCHANT_ID = 11L;
    private static final long OWNER = 501L;
    private static final long STAFF = 777L;

    private MerchantDeskMapper mapper;
    private MerchantDeskServiceImpl service;
    private MerchantAccessContext access;

    @BeforeEach
    void setUp() {
        // MyBatis 查不到单行时返回 null;Mockito 默认给空 Map 会把「不存在」伪装成「存在」
        mapper = mock(MerchantDeskMapper.class, withSettings().defaultAnswer(invocation ->
                Map.class.equals(invocation.getMethod().getReturnType()) ? null
                        : RETURNS_DEFAULTS.answer(invocation)));
        AppMediaReferenceGuard media = mock(AppMediaReferenceGuard.class);
        when(media.requireAttachment(anyLong(), any(), eq(false))).thenAnswer(i -> "media-asset:1");
        when(media.requireAttachments(anyLong(), any(), eq(false))).thenAnswer(i -> "media-asset:1;media-asset:2");
        service = new MerchantDeskServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "mediaReferences", media);
        access = new MerchantAccessContext(STAFF, MERCHANT_ID, OWNER, MerchantRoleCode.MERCHANT_MANAGER);
        Map<String, Object> store = new HashMap<>();
        store.put("status", 1);
        store.put("accountStatus", 1);
        when(mapper.selectStore(MERCHANT_ID)).thenReturn(store);
    }

    private static Map<String, Object> product(String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("productName", "下午茶双人套餐");
        body.put("price", 88);
        body.put("stock", 20);
        return body;
    }

    @Test
    void createProduct_writesUnderOwnStore_andRecordsReceipt() {
        when(mapper.insertProduct(anyMap())).thenAnswer(i -> {
            ((Map<String, Object>) i.getArgument(0)).put("id", 900L);
            return 1;
        });
        Map<String, Object> result = service.saveProduct(access, product("req-product-0001"));
        assertEquals(900L, result.get("targetId"));
        assertEquals(false, result.get("replayed"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(mapper).insertProduct(row.capture());
        assertEquals(MERCHANT_ID, row.getValue().get("merchantId"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> receipt = ArgumentCaptor.forClass(Map.class);
        verify(mapper).insertReceipt(receipt.capture());
        assertEquals(OWNER, receipt.getValue().get("ownerMemberId"));
        assertEquals(STAFF, receipt.getValue().get("actorMemberId"));
        assertEquals("PRODUCT_SAVE", receipt.getValue().get("action"));
    }

    @Test
    void replaySameRequest_returnsOriginalReceipt_withoutWritingAgain() {
        Map<String, Object> body = product("req-product-0002");
        String fp = (String) ReflectionTestUtils.invokeMethod(MerchantDeskServiceImpl.class, "fingerprint",
                "PRODUCT_SAVE", canonicalProduct(body));
        Map<String, Object> existing = new HashMap<>();
        existing.put("action", "PRODUCT_SAVE");
        existing.put("fingerprint", fp);
        existing.put("targetType", "pms_product");
        existing.put("targetId", 900L);
        when(mapper.selectReceipt(OWNER, "req-product-0002")).thenReturn(existing);
        Map<String, Object> result = service.saveProduct(access, body);
        assertEquals(true, result.get("replayed"));
        assertEquals(900L, result.get("targetId"));
        verify(mapper, never()).insertProduct(anyMap());
        verify(mapper, never()).insertReceipt(anyMap());
    }

    @Test
    void sameRequestIdDifferentBody_isIdempotencyConflict() {
        Map<String, Object> existing = new HashMap<>();
        existing.put("action", "PRODUCT_SAVE");
        existing.put("fingerprint", "0".repeat(64));
        when(mapper.selectReceipt(OWNER, "req-product-0003")).thenReturn(existing);
        MerchantDeskException e = assertThrows(MerchantDeskException.class,
                () -> service.saveProduct(access, product("req-product-0003")));
        assertEquals(MerchantDeskException.IDEMPOTENCY_CONFLICT, e.getErrorCode());
        verify(mapper, never()).insertProduct(anyMap());
    }

    @Test
    void missingRequestId_isRejectedBeforeAnyWrite() {
        MerchantDeskException e = assertThrows(MerchantDeskException.class,
                () -> service.saveProduct(access, product("short")));
        assertEquals(MerchantDeskException.BAD_REQUEST, e.getErrorCode());
        verify(mapper, never()).selectReceipt(any(), any());
    }

    @Test
    void editingLiveProduct_isLocked() {
        Map<String, Object> live = new HashMap<>();
        live.put("publishStatus", 1);
        when(mapper.selectProductForUpdate(MERCHANT_ID, 900L)).thenReturn(live);
        Map<String, Object> body = product("req-product-0004");
        body.put("productId", 900L);
        MerchantDeskException e = assertThrows(MerchantDeskException.class, () -> service.saveProduct(access, body));
        assertEquals(MerchantDeskException.PRODUCT_LIVE_LOCKED, e.getErrorCode());
        verify(mapper, never()).updateProduct(anyMap());
        verify(mapper, never()).insertReceipt(anyMap());
    }

    @Test
    void foreignProduct_lookupIsScopedToOwnStore() {
        Map<String, Object> body = product("req-product-0005");
        body.put("productId", 42L);
        MerchantDeskException e = assertThrows(MerchantDeskException.class, () -> service.saveProduct(access, body));
        assertEquals(MerchantDeskException.NOT_OWNER, e.getErrorCode());
        verify(mapper).selectProductForUpdate(MERCHANT_ID, 42L);
    }

    @Test
    void skuOnLiveProduct_isLocked() {
        Map<String, Object> live = new HashMap<>();
        live.put("publishStatus", 1);
        when(mapper.selectProductForUpdate(MERCHANT_ID, 900L)).thenReturn(live);
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-sku-00001");
        body.put("productId", 900L);
        body.put("skuName", "周末场");
        body.put("price", "88.00");
        body.put("stock", 5);
        body.put("bookable", true);
        body.put("capacityPerSlot", 6);
        MerchantDeskException e = assertThrows(MerchantDeskException.class, () -> service.saveSku(access, body));
        assertEquals(MerchantDeskException.PRODUCT_LIVE_LOCKED, e.getErrorCode());
        verify(mapper, never()).insertSku(anyMap());
    }

    @Test
    void bookableSkuNeedsCapacity_andSlotMultipleOf15() {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-sku-00002");
        body.put("productId", 900L);
        body.put("skuName", "周末场");
        body.put("price", "88");
        body.put("stock", 5);
        body.put("bookable", true);
        assertThrows(MerchantDeskException.class, () -> service.saveSku(access, body));
        body.put("capacityPerSlot", 6);
        body.put("slotMinutes", 50);
        assertThrows(MerchantDeskException.class, () -> service.saveSku(access, body));
    }

    @Test
    void evidenceOnClosedReview_isRejected() {
        Map<String, Object> closed = new HashMap<>();
        closed.put("status", 1);
        when(mapper.selectReviewForUpdate(OWNER, 66L)).thenReturn(closed);
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-evidence-01");
        body.put("reviewId", 66L);
        body.put("note", "当天是团建,集中核销");
        MerchantDeskException e = assertThrows(MerchantDeskException.class,
                () -> service.submitReviewEvidence(access, body));
        assertEquals(MerchantDeskException.REVIEW_CLOSED, e.getErrorCode());
        verify(mapper, never()).insertEvidence(anyMap());
    }

    @Test
    void evidenceOnOwnPendingReview_isStoredWithOwnerAndActor() {
        Map<String, Object> pending = new HashMap<>();
        pending.put("status", 0);
        when(mapper.selectReviewForUpdate(OWNER, 66L)).thenReturn(pending);
        when(mapper.selectEvidence(OWNER, 66L)).thenReturn(Collections.emptyList());
        when(mapper.insertEvidence(anyMap())).thenAnswer(i -> {
            ((Map<String, Object>) i.getArgument(0)).put("id", 3L);
            return 1;
        });
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-evidence-02");
        body.put("reviewId", 66L);
        body.put("media", Arrays.asList("media-asset:1", "media-asset:2"));
        Map<String, Object> result = service.submitReviewEvidence(access, body);
        assertEquals(3L, result.get("targetId"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(mapper).insertEvidence(row.capture());
        assertEquals(OWNER, row.getValue().get("ownerMemberId"));
        assertEquals(STAFF, row.getValue().get("actorMemberId"));
    }

    @Test
    void anchorUpdateWithoutVersion_isConflict() {
        Map<String, Object> current = new HashMap<>();
        current.put("id", 5L);
        when(mapper.selectAnchor(MERCHANT_ID)).thenReturn(current);
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-anchor-0001");
        body.put("anchorLat", "31.2304");
        body.put("anchorLng", "121.4737");
        body.put("radiusM", 80);
        MerchantDeskException e = assertThrows(MerchantDeskException.class, () -> service.saveAnchor(access, body));
        assertEquals(MerchantDeskException.VERSION_CONFLICT, e.getErrorCode());
        verify(mapper, never()).updateAnchor(anyMap());
    }

    @Test
    void anchorFarFromStore_isRejected() {
        Map<String, Object> store = new HashMap<>();
        store.put("status", 1);
        store.put("accountStatus", 1);
        store.put("locationLat", new BigDecimal("31.2304000"));
        store.put("locationLng", new BigDecimal("121.4737000"));
        when(mapper.selectStore(MERCHANT_ID)).thenReturn(store);
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-anchor-0002");
        body.put("anchorLat", "31.2504");
        body.put("anchorLng", "121.4737");
        body.put("radiusM", 80);
        MerchantDeskException e = assertThrows(MerchantDeskException.class, () -> service.saveAnchor(access, body));
        assertEquals("F4_ANCHOR_TOO_FAR", e.getErrorCode());
        verify(mapper, never()).insertAnchor(anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconciliation_listsFourBases_andReportsUndocumentedRoutesSeparately() {
        Map<String, Object> offer = new HashMap<>();
        offer.put("route", "CHAPTER_OFFER");
        offer.put("status", "ACTIVE");
        offer.put("claimCount", 3L);
        Map<String, Object> clubDirect = new HashMap<>();
        clubDirect.put("route", "CLUB_DIRECT");
        clubDirect.put("status", "ACTIVE");
        clubDirect.put("claimCount", 2L);
        when(mapper.selectClaimSummary(OWNER)).thenReturn(Arrays.asList(offer, clubDirect));
        Map<String, Object> view = service.reconciliationSummary(access);
        List<Map<String, Object>> bases = (List<Map<String, Object>>) view.get("bases");
        assertEquals(Arrays.asList("CHAPTER_OFFER", "COOP_ORDER", "REGISTRATION_SPLIT", "NONE"),
                bases.stream().map(b -> b.get("route")).toList());
        assertEquals(3L, bases.get(0).get("claimCount"));
        List<Map<String, Object>> outside = (List<Map<String, Object>>) view.get("outsideFourBases");
        assertEquals(1, outside.size());
        assertEquals("CLUB_DIRECT", outside.get(0).get("route"));
        assertEquals(true, view.get("readOnly"));
    }

    @Test
    void receiptReadBack_unknownMeansNotApplied() {
        assertEquals("NOT_FOUND", service.receipt(access, "req-unknown-01").get("state"));
        verify(mapper).selectReceipt(OWNER, "req-unknown-01");
    }

    @Test
    @SuppressWarnings("unchecked")
    void businessHoursParsedFromExistingFormat() {
        Map<String, Object> all = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                MerchantDeskServiceImpl.class, "parseBusinessHours", "周一至周日 10:00-次日02:00");
        assertEquals(true, all.get("parsed"));
        assertEquals(7, ((List<?>) all.get("weekdays")).size());
        assertEquals(true, all.get("overnight"));
        Map<String, Object> some = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                MerchantDeskServiceImpl.class, "parseBusinessHours", "周一、三、日 09:30-18:00");
        assertEquals(Arrays.asList(1, 3, 7), some.get("weekdays"));
        Map<String, Object> bad = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                MerchantDeskServiceImpl.class, "parseBusinessHours", "随缘");
        assertEquals(false, bad.get("parsed"));
    }

    @Test
    void storesReturnsSingleOwnStore_withEditEndpoints() {
        List<Map<String, Object>> stores = service.stores(access);
        assertEquals(1, stores.size());
        assertTrue(((Map<?, ?>) stores.get(0).get("editEndpoints")).containsKey("anchor"));
        verify(mapper).selectStore(MERCHANT_ID);
    }

    private static Map<String, Object> canonicalProduct(Map<String, Object> body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", null);
        payload.put("productName", body.get("productName"));
        payload.put("price", body.get("price"));
        payload.put("originalPrice", null);
        payload.put("stock", body.get("stock"));
        payload.put("unit", null);
        payload.put("pic", null);
        payload.put("albumPics", Collections.emptyList());
        return payload;
    }
    @Test
    void receiptRecoversSkuParentWithinCurrentMerchantScope() {
        when(mapper.selectReceipt(OWNER, "original-sku-request"))
                .thenReturn(Map.of("action", "SKU_SAVE", "targetType", "pms_sku", "targetId", 9007199254740993L));
        when(mapper.selectReceiptSkuProductId(MERCHANT_ID, 9007199254740993L)).thenReturn(9007199254740995L);
        Map<String, Object> result = service.receipt(access, "original-sku-request");
        assertEquals("APPLIED", result.get("state"));
        assertEquals(9007199254740995L, result.get("productId"));
        verify(mapper).selectReceiptSkuProductId(MERCHANT_ID, 9007199254740993L);
    }

    @Test
    void receiptRecoversEvidenceReviewAndKeepsAppliedWhenParentNoLongerVisible() {
        when(mapper.selectReceipt(OWNER, "original-evidence-request"))
                .thenReturn(Map.of("action", "REVIEW_EVIDENCE", "targetType", "verification_review_evidence", "targetId", 91L));
        when(mapper.selectReceiptEvidenceReviewId(OWNER, 91L)).thenReturn(81L);
        assertEquals(81L, service.receipt(access, "original-evidence-request").get("reviewId"));
        when(mapper.selectReceiptEvidenceReviewId(OWNER, 91L)).thenReturn(null);
        Map<String, Object> result = service.receipt(access, "original-evidence-request");
        assertEquals("APPLIED", result.get("state"));
        assertEquals(null, result.get("reviewId"));
    }

    @Test
    void absentReceiptDoesNotLocateAnotherResource() {
        assertEquals("NOT_FOUND", service.receipt(access, "missing-request").get("state"));
        verify(mapper, never()).selectReceiptSkuProductId(anyLong(), anyLong());
        verify(mapper, never()).selectReceiptEvidenceReviewId(anyLong(), anyLong());
    }

    @Test
    void capacityPagesUseMerchantScopeAndOneExtraRowWithoutTruncatingFourteenDays() {
        long start = java.time.Instant.parse("2026-10-25T16:00:00Z").toEpochMilli();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            rows.add(Map.of("slotId", 9007199254740993L + i, "skuId", 8L,
                    "slotStart", new java.util.Date(start + i * 60000L)));
        }
        when(mapper.selectCapacitySlotPage(eq(MERCHANT_ID), eq(8L), any(), any(), any(), any())).thenReturn(rows);
        Map<String, Object> result = service.capacitySlotPage(access, 8L, "2026-10-26", 14, null, null);
        assertEquals(100, ((List<?>) result.get("rows")).size());
        assertEquals(true, result.get("hasMore"));
        Map<?, ?> next = (Map<?, ?>) result.get("next");
        assertEquals(9007199254741092L, next.get("afterSlotId"));
        assertEquals(start + 99 * 60000L, next.get("afterStartAt"));
        service.capacitySlotPage(access, 8L, "2026-10-26", 14,
                (Long) next.get("afterStartAt"), (Long) next.get("afterSlotId"));
        verify(mapper).selectCapacitySlotPage(MERCHANT_ID, 8L, new java.util.Date(start),
                new java.util.Date(start + 14 * 86400000L), new java.util.Date(start + 99 * 60000L), 9007199254741092L);
    }

    @Test
    void capacityPagesRejectInvalidRangeAndIncompleteOrOutsideCursorBeforeQuery() {
        long start = java.time.Instant.parse("2026-10-25T16:00:00Z").toEpochMilli();
        assertThrows(RuntimeException.class, () -> service.capacitySlotPage(access, 8L, "2026-10-26", 15, null, null));
        assertThrows(RuntimeException.class, () -> service.capacitySlotPage(access, 8L, "2026-10-26", 14, start, null));
        assertThrows(RuntimeException.class, () -> service.capacitySlotPage(access, 8L, "2026-10-26", 14, null, 1L));
        assertThrows(RuntimeException.class, () -> service.capacitySlotPage(access, 8L, "2026-10-26", 14, start - 1, 1L));
        assertThrows(RuntimeException.class, () -> service.capacitySlotPage(access, 8L, "2026-10-26", 14, start + 14 * 86400000L, 1L));
        verify(mapper, never()).selectCapacitySlotPage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void frozenHistoryUsesOwnerScopeAndSentinelWithoutMutatingFinancialRows() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) rows.add(Map.of("batchId", 9007199254741099L - i,
                "periodYm", "2026-10", "amountTotal", new BigDecimal("12.34")));
        when(mapper.selectFrozenBatchPage(OWNER, null, null)).thenReturn(rows);
        Map<String, Object> result = service.frozenBatchPage(access, null, null);
        assertEquals(24, ((List<?>) result.get("rows")).size());
        assertEquals(true, result.get("hasMore"));
        assertEquals(Map.of("afterPeriodYm", "2026-10", "afterBatchId", 9007199254741076L), result.get("next"));
        assertEquals(null, rows.get(0).get("holdState"));
        when(mapper.selectFrozenBatchPage(OWNER, "2026-10", 9007199254741076L)).thenReturn(Collections.emptyList());
        Map<String, Object> end = service.frozenBatchPage(access, "2026-10", 9007199254741076L);
        assertEquals(false, end.get("hasMore")); assertEquals(null, end.get("next"));
        verify(mapper).selectFrozenBatchPage(OWNER, "2026-10", 9007199254741076L);
    }

    @Test
    void frozenHistoryRejectsIncompleteAndInvalidCursorsBeforeQuery() {
        assertThrows(RuntimeException.class, () -> service.frozenBatchPage(access, "2026-10", null));
        assertThrows(RuntimeException.class, () -> service.frozenBatchPage(access, null, 1L));
        assertThrows(RuntimeException.class, () -> service.frozenBatchPage(access, "2026-13", 1L));
        assertThrows(RuntimeException.class, () -> service.frozenBatchPage(access, "2026-10", 0L));
        verify(mapper, never()).selectFrozenBatchPage(any(), any(), any());
    }

}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.map.MapPublicException;
import com.chengyinhub.business.domain.map.MapPublicItemFacts;
import com.chengyinhub.business.domain.map.MapPublicLimits;
import com.chengyinhub.business.domain.map.MapPublicQuery;
import com.chengyinhub.business.domain.map.MapPublicResult;
import com.chengyinhub.business.domain.map.MapPublicRow;
import com.chengyinhub.business.service.map.MapPublicSourceAdapter;
import com.chengyinhub.business.service.map.StoryNodeMapPublicSourceAdapter;
import com.chengyinhub.business.service.map.ThemeMapPublicSourceAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MapPublicServiceTest {

    private MapPublicSourceAdapter roam;
    private MapPublicSourceAdapter store;
    private MapPublicSourceAdapter activity;
    private MapPublicServiceImpl service;

    @BeforeEach
    void setUp() {
        roam = mock(MapPublicSourceAdapter.class);
        store = mock(MapPublicSourceAdapter.class);
        activity = mock(MapPublicSourceAdapter.class);
        when(roam.sourceType()).thenReturn("roam_poi");
        when(store.sourceType()).thenReturn("store");
        when(activity.sourceType()).thenReturn("activity");
        when(roam.available()).thenReturn(true);
        when(store.available()).thenReturn(true);
        when(activity.available()).thenReturn(true);
        service = new MapPublicServiceImpl(List.of(roam, store, activity,
                new ThemeMapPublicSourceAdapter(), new StoryNodeMapPublicSourceAdapter()));
    }

    @Test
    void keepsSameNumericIdOnDifferentSourcesAndLeavesThemeUnread() {
        when(roam.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of(row("roam_poi", 4)));
        when(store.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of(row("store", 4)));
        MapPublicQuery query = query(List.of("roam_poi", "store", "theme"), 2);

        MapPublicResult result = service.query(77L, query);

        assertEquals("GCJ-02", result.getDatum());
        assertFalse(result.isAggregatedQueryEnabled());
        assertFalse(result.getCluster().isSupported());
        assertEquals(List.of("roam_poi", "store", "theme"),
                result.getLayers().stream().map(MapPublicResult.Layer::getSourceType).toList());
        assertEquals(4L, result.getLayers().get(0).getItems().get(0).getSourceId());
        assertEquals("roam_poi", result.getLayers().get(0).getItems().get(0).getSourceType());
        assertEquals("v-roam_poi-4", result.getLayers().get(0).getItems().get(0).getVisibilityVersion());
        assertEquals("unfound", result.getLayers().get(0).getItems().get(0).getVisualState());
        assertTrue(result.getLayers().get(0).getItems().get(0).getActions().isEmpty());
        assertEquals(4L, result.getLayers().get(1).getItems().get(0).getSourceId());
        assertEquals("store", result.getLayers().get(1).getItems().get(0).getSourceType());
        assertEquals("UNAVAILABLE", result.getLayers().get(2).getStatus());
        assertEquals(ThemeMapPublicSourceAdapter.REASON, result.getLayers().get(2).getReason());
        assertTrue(result.getLayers().get(2).getItems().isEmpty());
        assertFalse(result.getLayers().get(2).getCapabilities().isRead());
        assertFalse(result.getLayers().get(0).getCapabilities().isCount());
        assertFalse(result.getLayers().get(0).getCapabilities().isCluster());
        assertNull(result.getNextCursor());
    }

    @Test
    void storyNodeStaysUnavailable() {
        MapPublicResult result = service.query(77L, query(List.of("story_node"), 1));
        assertEquals("UNAVAILABLE", result.getLayers().get(0).getStatus());
        assertEquals(StoryNodeMapPublicSourceAdapter.REASON, result.getLayers().get(0).getReason());
        assertTrue(result.getLayers().get(0).getItems().isEmpty());
    }

    @Test
    void rejectsUnknownLayerInsteadOfReadingEveryRow() {
        MapPublicException ex = assertThrows(MapPublicException.class,
                () -> service.query(77L, query(List.of("registration_merchant"), 1)));
        assertEquals("LAYERS_REJECTED", ex.getMessage());
        ex = assertThrows(MapPublicException.class, () -> service.query(77L, query(List.of("w20"), 1)));
        assertEquals("LAYERS_REJECTED", ex.getMessage());
        ex = assertThrows(MapPublicException.class, () -> service.query(77L, query(List.of("sideQuest"), 1)));
        assertEquals("LAYERS_REJECTED", ex.getMessage());
        assertEquals("story_node", new StoryNodeMapPublicSourceAdapter().sourceType());
        verify(roam, never()).page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt());
        verify(store, never()).page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt());
        verify(activity, never()).page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt());
    }

    @Test
    void pagesBySourceKeyAndRepeatsTheSameCursor() {
        when(roam.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(5) == null
                        ? List.of(row("roam_poi", 4), row("roam_poi", 5))
                        : List.of(row("roam_poi", 6)));
        MapPublicQuery query = query(List.of("roam_poi"), 2);

        MapPublicResult first = service.query(77L, query);
        MapPublicResult firstAgain = service.query(77L, query);
        assertEquals(List.of(4L, 5L), ids(first));
        assertEquals(ids(first), ids(firstAgain));
        assertTrue(first.getNextCursor() != null);

        query.setCursor(first.getNextCursor());
        MapPublicResult second = service.query(77L, query);
        MapPublicResult secondAgain = service.query(77L, query);
        assertEquals(List.of(6L), ids(second));
        assertEquals(ids(second), ids(secondAgain));
        assertNull(second.getNextCursor());
        verify(roam, times(2)).page(eq(77L), any(), any(), any(), any(), isNull(), eq(2));
        verify(roam, times(2)).page(eq(77L), any(), any(), any(), any(), eq(5L), eq(2));
    }

    @Test
    void doesNotRereadAnExhaustedSourceOnTheNextPage() {
        when(roam.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of(row("roam_poi", 4), row("roam_poi", 5)));
        when(store.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of());
        MapPublicQuery query = query(List.of("roam_poi", "store"), 2);
        MapPublicResult first = service.query(77L, query);
        query.setCursor(first.getNextCursor());
        when(roam.page(anyLong(), any(), any(), any(), any(), eq(5L), anyInt())).thenReturn(List.of());

        service.query(77L, query);

        verify(store, times(1)).page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt());
    }

    @Test
    void rejectsCursorWhenAccountBboxOrLimitChanges() {
        when(roam.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of(row("roam_poi", 4), row("roam_poi", 5)));
        MapPublicQuery query = query(List.of("roam_poi"), 2);
        String cursor = service.query(77L, query).getNextCursor();

        query.setCursor(cursor);
        assertEquals("CURSOR_REJECTED", assertThrows(MapPublicException.class,
                () -> service.query(88L, query)).getMessage());

        MapPublicQuery moved = query(List.of("roam_poi"), 2);
        moved.setCursor(cursor);
        moved.setMaxLat(new BigDecimal("31.25"));
        assertEquals("CURSOR_REJECTED", assertThrows(MapPublicException.class,
                () -> service.query(77L, moved)).getMessage());

        MapPublicQuery resized = query(List.of("roam_poi"), 1);
        resized.setCursor(cursor);
        assertEquals("CURSOR_REJECTED", assertThrows(MapPublicException.class,
                () -> service.query(77L, resized)).getMessage());

        MapPublicQuery reordered = query(List.of("store", "roam_poi"), 2);
        when(store.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of(row("store", 1), row("store", 2)));
        String both = service.query(77L, query(List.of("roam_poi", "store"), 2)).getNextCursor();
        reordered.setCursor(both);
        assertEquals("CURSOR_REJECTED", assertThrows(MapPublicException.class,
                () -> service.query(77L, reordered)).getMessage());
    }

    @Test
    void rejectsMissingOrBlankCursor() {
        MapPublicQuery query = query(List.of("roam_poi"), 1);
        query.setCursor("");
        assertEquals("CURSOR_REJECTED", assertThrows(MapPublicException.class,
                () -> service.query(77L, query)).getMessage());
        query.setCursor("not-a-cursor");
        assertEquals("CURSOR_REJECTED", assertThrows(MapPublicException.class,
                () -> service.query(77L, query)).getMessage());
        verify(roam, never()).page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt());
    }

    @Test
    void failedSourceDoesNotHideAnotherSourceOrIssueACursor() {
        when(roam.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenThrow(new IllegalStateException("bad SQL grammar"));
        when(store.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of(row("store", 4), row("store", 5)));
        MapPublicQuery query = query(List.of("roam_poi", "store"), 2);

        MapPublicResult result = service.query(77L, query);
        MapPublicResult retry = service.query(77L, query);

        assertEquals("FAILED", result.getLayers().get(0).getStatus());
        assertEquals("SOURCE_FAILED", result.getLayers().get(0).getReason());
        assertTrue(result.getLayers().get(0).getItems().isEmpty());
        assertEquals(List.of(4L, 5L), result.getLayers().get(1).getItems().stream()
                .map(MapPublicResult.Item::getSourceId).toList());
        assertNull(result.getNextCursor());
        assertEquals("FAILED", retry.getLayers().get(0).getStatus());
        verify(roam, times(2)).page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt());
        verify(store, times(2)).page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt());
    }

    @Test
    void publishesPersistedVersionAndOnlyProvenActions() {
        MapPublicRow poi = row("roam_poi", 5);
        poi.setFound(true);
        poi.setPendingRedeem(true);
        poi.setVisibilityVersion("4100000005");
        MapPublicRow closed = row("store", 4);
        closed.setBusinessStatus(0);
        closed.setLocationVerified(0);
        closed.setVisibilityVersion("2026-03-01T08:00:00");
        MapPublicRow pinned = row("store", 8);
        pinned.setBusinessStatus(1);
        pinned.setLocationVerified(1);
        pinned.setVisibilityVersion("2026-03-02T09:00:00");
        MapPublicRow later = row("activity", 16);
        later.setStartDate(new Date(System.currentTimeMillis() - 86_400_000L));
        later.setEndDate(new Date(System.currentTimeMillis() + 86_400_000L));
        later.setVisibilityVersion("2026-04-02T11:00:00");
        when(roam.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of(poi));
        when(store.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of(closed, pinned));
        when(activity.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of(later));

        MapPublicResult result = service.query(77L, query(List.of("roam_poi", "store", "activity"), 10));

        MapPublicResult.Item roamItem = result.getLayers().get(0).getItems().get(0);
        assertEquals("4100000005", roamItem.getVisibilityVersion());
        assertEquals("pending_redeem", roamItem.getVisualState());
        assertFalse(roamItem.getActions().contains(MapPublicItemFacts.NAVIGATE));
        assertFalse(roamItem.getActions().contains(MapPublicItemFacts.REGISTER));
        assertEquals("closed", result.getLayers().get(1).getItems().get(0).getVisualState());
        assertTrue(result.getLayers().get(1).getItems().get(0).getActions().isEmpty());
        assertEquals(List.of(MapPublicItemFacts.NAVIGATE), result.getLayers().get(1).getItems().get(1).getActions());
        assertEquals("running", result.getLayers().get(2).getItems().get(0).getVisualState());
        assertFalse(result.getLayers().get(2).getItems().get(0).getActions().contains(MapPublicItemFacts.REGISTER));
        assertFalse(result.isAggregatedQueryEnabled());
    }

    @Test
    void refusesAPageWhenARowHasNoPersistedVersionOrVisualState() {
        MapPublicRow missing = row("store", 4);
        missing.setVisibilityVersion(null);
        when(store.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of(missing));
        when(roam.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of(row("roam_poi", 1)));
        MapPublicResult result = service.query(77L, query(List.of("roam_poi", "store"), 10));
        assertEquals("OK", result.getLayers().get(0).getStatus());
        assertEquals("v-roam_poi-1", result.getLayers().get(0).getItems().get(0).getVisibilityVersion());
        assertEquals("FAILED", result.getLayers().get(1).getStatus());
        assertEquals("VISIBILITY_VERSION_ABSENT", result.getLayers().get(1).getReason());
        assertTrue(result.getLayers().get(1).getItems().isEmpty());
        assertNull(result.getNextCursor());

        MapPublicRow odd = row("store", 9);
        odd.setBusinessStatus(9);
        when(store.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of(odd));
        MapPublicResult visual = service.query(77L, query(List.of("store"), 10));
        assertEquals("VISUAL_STATE_ABSENT", visual.getLayers().get(0).getReason());
        assertTrue(visual.getLayers().get(0).getItems().isEmpty());
    }

    @Test
    void dropsAPageThatIsNotOrderedBySourceId() {
        when(roam.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of(row("roam_poi", 5), row("roam_poi", 4)));
        MapPublicResult result = service.query(77L, query(List.of("roam_poi"), 2));
        assertEquals("FAILED", result.getLayers().get(0).getStatus());
        assertTrue(result.getLayers().get(0).getItems().isEmpty());
    }

    @Test
    void requiresExplicitDatumBboxLimitAndRejectsOversizedArea() {
        MapPublicQuery missing = query(List.of("roam_poi"), 1);
        missing.setDatum(null);
        assertEquals("DATUM_REQUIRED", assertThrows(MapPublicException.class,
                () -> service.query(77L, missing)).getMessage());
        MapPublicQuery otherDatum = query(List.of("roam_poi"), 1);
        otherDatum.setDatum("WGS84");
        assertEquals("DATUM_REJECTED", assertThrows(MapPublicException.class,
                () -> service.query(77L, otherDatum)).getMessage());

        MapPublicQuery noBox = query(List.of("roam_poi"), 1);
        noBox.setMinLat(null);
        assertEquals("COORDINATE_REJECTED", assertThrows(MapPublicException.class,
                () -> service.query(77L, noBox)).getMessage());

        MapPublicQuery halfDegree = query(List.of("roam_poi"), 1);
        halfDegree.setMinLat(new BigDecimal("31.00"));
        halfDegree.setMaxLat(new BigDecimal("31.50"));
        halfDegree.setMinLng(new BigDecimal("121.00"));
        halfDegree.setMaxLng(new BigDecimal("121.50"));
        when(roam.page(anyLong(), any(), any(), any(), any(), nullable(Long.class), anyInt()))
                .thenReturn(List.of());
        assertEquals("OK", service.query(77L, halfDegree).getLayers().get(0).getStatus());

        MapPublicQuery huge = query(List.of("roam_poi"), 1);
        huge.setMinLat(new BigDecimal("30"));
        huge.setMaxLat(new BigDecimal("33"));
        huge.setMinLng(new BigDecimal("120"));
        huge.setMaxLng(new BigDecimal("123"));
        assertEquals("AREA_REJECTED", assertThrows(MapPublicException.class,
                () -> service.query(77L, huge)).getMessage());

        assertEquals("LIMIT_REJECTED", assertThrows(MapPublicException.class,
                () -> service.query(77L, query(List.of("roam_poi"), 51))).getMessage());
        assertEquals("VIEWER_REJECTED", assertThrows(MapPublicException.class,
                () -> service.query(0L, query(List.of("roam_poi"), 1))).getMessage());
        assertEquals(50, MapPublicLimits.effectivePageLimit());
        assertEquals(100, MapPublicLimits.cap(200, MapPublicLimits.HARD_PAGE_LIMIT));
        assertEquals(new BigDecimal("1"), MapPublicLimits.effectiveMaxArea());
        verify(roam, never()).page(eq(0L), any(), any(), any(), any(), nullable(Long.class), anyInt());
    }

    private static MapPublicQuery query(List<String> layers, int limit) {
        MapPublicQuery query = new MapPublicQuery();
        query.setDatum("GCJ-02");
        query.setMinLat(new BigDecimal("31.22"));
        query.setMaxLat(new BigDecimal("31.24"));
        query.setMinLng(new BigDecimal("121.46"));
        query.setMaxLng(new BigDecimal("121.49"));
        query.setLimit(limit);
        query.setLayers(layers);
        return query;
    }

    private static MapPublicRow row(String sourceType, long id) {
        MapPublicRow row = new MapPublicRow();
        row.setSourceType(sourceType);
        row.setSourceId(id);
        row.setName("n" + id);
        row.setLatitude(new BigDecimal("31.23"));
        row.setLongitude(new BigDecimal("121.47"));
        row.setVisibilityVersion("v-" + sourceType + "-" + id);
        if ("roam_poi".equals(sourceType)) {
            row.setFound(false);
            row.setPendingRedeem(false);
        } else if ("store".equals(sourceType)) {
            row.setBusinessStatus(1);
        } else if ("activity".equals(sourceType)) {
            row.setStartDate(new Date(System.currentTimeMillis() + 86_400_000L));
            row.setEndDate(new Date(System.currentTimeMillis() + 172_800_000L));
        }
        return row;
    }

    private static List<Long> ids(MapPublicResult result) {
        return result.getLayers().get(0).getItems().stream().map(MapPublicResult.Item::getSourceId).toList();
    }
}

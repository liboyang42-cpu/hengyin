package com.chengyinhub.business.discovery;

import com.chengyinhub.business.discovery.mapper.DiscoveryMapper;
import com.chengyinhub.business.domain.MemberInterestProfile;
import com.chengyinhub.business.mapper.MemberInterestProfileMapper;
import com.chengyinhub.business.marketplace.MarketplaceDealService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * K2 discovery (MKT-10): the "near you" list. Switch off keeps today's nearest-first order; sponsored rows never enter
 * the list; every item carries one plain reason; nothing paid changes the order.
 */
class DiscoveryNearYouServiceTest {
    private static final long MEMBER = 42L;
    private static final double MIN_LAT = 31.20, MAX_LAT = 31.26, MIN_LNG = 121.44, MAX_LNG = 121.50;
    private static final Date NOW = new Date(1_800_000_000_000L);
    private static final Date OLD = new Date(NOW.getTime() - 90L * 24 * 3600 * 1000);
    private static final Date RECENT = new Date(NOW.getTime() - 2L * 24 * 3600 * 1000);

    private final DiscoveryMapper mapper = mock(DiscoveryMapper.class);
    private final MarketplaceDealService deals = mock(MarketplaceDealService.class);
    private final MemberInterestProfileMapper interests = mock(MemberInterestProfileMapper.class);

    private DiscoveryNearYouService service(boolean scoring) {
        when(mapper.selectPlacesInBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(places());
        when(mapper.selectEventsInBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyInt())).thenReturn(events());
        when(deals.dealsInBox(any(), any(), any(), any(), any())).thenReturn(dealsInOperatorOrder());
        MemberInterestProfile profile = new MemberInterestProfile();
        profile.setCategoryWeightsJson("{\"activity\":8,\"roam\":2}");
        when(interests.selectMemberInterestProfileByMemberId(anyLong())).thenReturn(profile);
        DiscoveryNearYouService s = new DiscoveryNearYouService(mapper, deals, interests);
        s.setScoringEnabled(scoring);
        return s;
    }

    @Test
    void switchOffKeepsTodaysNearestFirstOrderAndNeverReadsTheProfile() {
        DiscoveryNearYouService s = service(false);
        assertFalse(s.scoringEnabled());
        Map<String, Object> result = s.nearYou(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG, MEMBER, NOW);
        assertEquals("DISTANCE", result.get("ordering"));
        List<Map<String, Object>> items = items(result);
        List<Long> distances = items.stream().map(i -> (Long) i.get("distanceM")).collect(Collectors.toList());
        List<Long> sorted = new ArrayList<>(distances);
        sorted.sort(Long::compare);
        assertEquals(sorted, distances, "nearest first, like the roam pins today");
        assertEquals(List.of("PLACE:1", "DEAL:900", "EVENT:70", "EVENT:71", "PLACE:2", "DEAL:901"), keys(items));
        verify(interests, never()).selectMemberInterestProfileByMemberId(anyLong());
        // Same input, same output: the order is stable.
        assertEquals(keys(items), keys(items(service(false).nearYou(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG, MEMBER, NOW))));
    }

    @Test
    void sponsoredRowsNeverEnterTheListSoNothingAppearsUnlabelled() {
        for (boolean scoring : new boolean[] {false, true}) {
            List<Map<String, Object>> items = items(service(scoring).nearYou(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG, MEMBER, NOW));
            for (Map<String, Object> item : items) {
                assertFalse(item.containsKey("sponsored"), "no sponsored field on " + item);
                assertFalse(item.containsKey("label"));
                assertNotEquals("Sponsored stop", item.get("title"));
                assertNotEquals("Paid deal", item.get("title"));
                assertNotEquals("Labelled quest", item.get("title"));
            }
            assertEquals(6, items.size());
        }
        assertTrue(DiscoveryNearYouService.sponsored(row("sponsored", 1)));
        assertTrue(DiscoveryNearYouService.sponsored(row("sponsored", "true")));
        assertTrue(DiscoveryNearYouService.sponsored(row("label", "赞助")));
        assertTrue(DiscoveryNearYouService.sponsored(null));
        assertFalse(DiscoveryNearYouService.sponsored(row("sponsored", 0)));
    }

    @Test
    void everyItemCarriesOnePlainReason() {
        List<Map<String, Object>> items = items(service(false).nearYou(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG, MEMBER, NOW));
        Map<String, Object> reasons = new LinkedHashMap<>();
        for (Map<String, Object> item : items) reasons.put(item.get("kind") + ":" + item.get("id"), item.get("reason"));
        assertEquals("NEW", reasons.get("PLACE:2"));
        assertEquals("NEAR_YOU", reasons.get("PLACE:1"));
        assertEquals("YOUR_CLUB", reasons.get("EVENT:71"));
        assertEquals("NEAR_YOU", reasons.get("EVENT:70"));
        assertEquals("NEAR_YOU", reasons.get("DEAL:900"));
        for (Object reason : reasons.values()) assertTrue(List.of("NEAR_YOU", "YOUR_CLUB", "NEW").contains(reason));
    }

    @Test
    void switchOnScoresClubAndInterestButKeepsEveryItem() {
        Map<String, Object> result = service(true).nearYou(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG, MEMBER, NOW);
        assertEquals("SCORED", result.get("ordering"));
        List<String> keys = keys(items(result));
        assertEquals("EVENT:71", keys.get(0), "your club's event, which the player's interests also favour");
        assertEquals(6, keys.size());
        verify(interests).selectMemberInterestProfileByMemberId(MEMBER);
    }

    @Test
    void operatorOrderOfOffersNeverChangesTheList() {
        List<String> first = keys(items(service(false).nearYou(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG, MEMBER, NOW)));
        List<Map<String, Object>> reversed = dealsInOperatorOrder();
        java.util.Collections.reverse(reversed);
        when(deals.dealsInBox(any(), any(), any(), any(), any())).thenReturn(reversed);
        DiscoveryNearYouService s = new DiscoveryNearYouService(mapper, deals, interests);
        assertEquals(first, keys(items(s.nearYou(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG, MEMBER, NOW))));
    }

    @Test
    void rejectsBoxesBiggerThanACity() {
        DiscoveryNearYouService s = service(false);
        assertThrows(IllegalArgumentException.class, () -> s.nearYou(30.0, 31.0, 121.0, 121.2, MEMBER, NOW));
        assertThrows(IllegalArgumentException.class, () -> s.nearYou(null, 31.0, 121.0, 121.2, MEMBER, NOW));
        assertThrows(IllegalArgumentException.class, () -> s.nearYou(31.2, 31.1, 121.0, 121.2, MEMBER, NOW));
    }

    private static List<Map<String, Object>> places() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(place(1, "Old Lane", 31.2301, 121.4701, OLD));
        rows.add(place(2, "New Gallery", 31.2500, 121.4900, RECENT));
        Map<String, Object> paid = place(3, "Sponsored stop", 31.2300, 121.4700, OLD);
        paid.put("sponsored", 1);
        rows.add(paid);
        return rows;
    }

    private static List<Map<String, Object>> events() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(event(70, "Open Walk", 31.2350, 121.4750, null));
        rows.add(event(71, "Club Night", 31.2400, 121.4800, 9L));
        Map<String, Object> labelled = event(72, "Labelled quest", 31.2300, 121.4700, null);
        labelled.put("label", "赞助");
        rows.add(labelled);
        return rows;
    }

    /** Operator sort: farthest first, plus a paid row. The list must not keep this order. */
    private static List<Map<String, Object>> dealsInOperatorOrder() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(deal(901, "Far Coffee", 31.2590, 121.4990));
        Map<String, Object> paid = deal(902, "Paid deal", 31.2300, 121.4700);
        paid.put("sponsored", true);
        rows.add(paid);
        rows.add(deal(900, "Near Tea", 31.2310, 121.4710));
        return rows;
    }

    private static Map<String, Object> place(long id, String name, double lat, double lng, Date created) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("lat", lat);
        row.put("lng", lng);
        row.put("createTime", created);
        return row;
    }

    private static Map<String, Object> event(long id, String name, double lat, double lng, Long clubId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("lat", String.valueOf(lat));
        row.put("lng", String.valueOf(lng));
        row.put("createTime", OLD);
        row.put("startDate", new Date(NOW.getTime() + 3600_000L));
        row.put("clubId", clubId);
        return row;
    }

    private static Map<String, Object> deal(long id, String name, double lat, double lng) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("couponId", id);
        row.put("name", name);
        row.put("merchantName", "Shop " + id);
        row.put("lat", lat);
        row.put("lng", lng);
        return row;
    }

    private static Map<String, Object> row(String key, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(key, value);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }

    private static List<String> keys(List<Map<String, Object>> items) {
        return items.stream().map(i -> i.get("kind") + ":" + i.get("id")).collect(Collectors.toList());
    }
}

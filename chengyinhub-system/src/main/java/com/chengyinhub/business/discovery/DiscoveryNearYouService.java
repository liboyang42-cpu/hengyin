package com.chengyinhub.business.discovery;

import com.chengyinhub.business.discovery.mapper.DiscoveryMapper;
import com.chengyinhub.business.domain.MemberInterestProfile;
import com.chengyinhub.business.mapper.MemberInterestProfileMapper;
import com.chengyinhub.business.marketplace.MarketplaceDealService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K2 discovery (MKT-10): one personal "near you" list mixing places, operator opted-in offers and events around the
 * area the player is looking at, each with one plain reason (near you, your club, new).
 *
 * <ul>
 *   <li>Order: nearest first (today's pin order) unless {@code chengyin.discovery.scoring.enabled} is true, then the
 *       {@link DiscoveryScorer} score. The switch defaults to false.</li>
 *   <li>Paid placement never changes it: no paid input exists in {@link DiscoveryItem}; offers are re-ordered by
 *       distance or score, never by the operator sort they arrive in.</li>
 *   <li>Sponsored quests are not part of this list. They stay in their own labelled section; any source row marked
 *       sponsored is dropped here so it can never appear unlabelled.</li>
 * </ul>
 */
@Service
public class DiscoveryNearYouService {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryNearYouService.class);
    /** Same city-sized box limit as the offers layer. */
    public static final double MAX_SPAN_DEGREES = MarketplaceDealService.MAX_SPAN_DEGREES;
    static final int MAX_PLACES = 30;
    static final int MAX_DEALS = 20;
    static final int MAX_EVENTS = 20;
    public static final int MAX_ITEMS = 40;

    private final DiscoveryMapper mapper;
    private final MarketplaceDealService dealService;
    private final MemberInterestProfileMapper interestMapper;

    @Value("${chengyin.discovery.scoring.enabled:false}")
    private boolean scoringEnabled;

    @Autowired
    public DiscoveryNearYouService(DiscoveryMapper mapper, MarketplaceDealService dealService,
                                   MemberInterestProfileMapper interestMapper) {
        this.mapper = mapper;
        this.dealService = dealService;
        this.interestMapper = interestMapper;
    }

    void setScoringEnabled(boolean enabled) { this.scoringEnabled = enabled; }

    public boolean scoringEnabled() { return scoringEnabled; }

    public Map<String, Object> nearYou(Double minLat, Double maxLat, Double minLng, Double maxLng, Long memberId) {
        return nearYou(minLat, maxLat, minLng, maxLng, memberId, new Date());
    }

    Map<String, Object> nearYou(Double minLat, Double maxLat, Double minLng, Double maxLng, Long memberId, Date now) {
        requireBox(minLat, maxLat, minLng, maxLng);
        Long member = memberId != null && memberId > 0 ? memberId : null;
        double cLat = (minLat + maxLat) / 2;
        double cLng = (minLng + maxLng) / 2;
        List<DiscoveryItem> items = new ArrayList<>();
        for (Map<String, Object> row : safe(mapper.selectPlacesInBox(minLat, maxLat, minLng, maxLng, MAX_PLACES))) {
            DiscoveryItem item = place(row);
            if (item != null) items.add(item);
        }
        List<DiscoveryItem> deals = new ArrayList<>();
        for (Map<String, Object> row : safe(dealService.dealsInBox(minLat, maxLat, minLng, maxLng, member))) {
            DiscoveryItem item = deal(row);
            if (item != null) deals.add(item);
        }
        for (Map<String, Object> row : safe(mapper.selectEventsInBox(minLat, maxLat, minLng, maxLng, member, MAX_EVENTS))) {
            DiscoveryItem item = event(row);
            if (item != null) items.add(item);
        }
        for (DiscoveryItem item : deals) item.distanceM = DiscoveryScorer.distanceM(cLat, cLng, item.lat, item.lng);
        // Offers arrive in the operator's sort; keep the nearest ones regardless of that sort.
        deals.sort(DiscoveryScorer.DISTANCE_ORDER);
        items.addAll(deals.subList(0, Math.min(MAX_DEALS, deals.size())));
        for (DiscoveryItem item : items) {
            item.distanceM = DiscoveryScorer.distanceM(cLat, cLng, item.lat, item.lng);
            item.reason = DiscoveryScorer.reason(item, now);
        }
        double reach = DiscoveryScorer.distanceM(cLat, cLng, maxLat, maxLng);
        DiscoveryScorer.order(items, scoringEnabled, reach, scoringEnabled ? interests(member) : null, now);
        List<Map<String, Object>> views = new ArrayList<>();
        for (DiscoveryItem item : items.subList(0, Math.min(MAX_ITEMS, items.size()))) views.add(item.toView());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", views);
        result.put("ordering", scoringEnabled ? "SCORED" : "DISTANCE");
        return result;
    }

    private String interests(Long member) {
        if (member == null) return null;
        try {
            MemberInterestProfile profile = interestMapper.selectMemberInterestProfileByMemberId(member);
            return profile == null ? null : profile.getCategoryWeightsJson();
        } catch (RuntimeException e) {
            // A failed profile read only removes the interest term; the list itself still works.
            log.warn("[discovery/near-you] interest profile read failed, errorType={}", e.getClass().getName());
            return null;
        }
    }

    static void requireBox(Double minLat, Double maxLat, Double minLng, Double maxLng) {
        if (minLat == null || maxLat == null || minLng == null || maxLng == null
                || !Double.isFinite(minLat) || !Double.isFinite(maxLat) || !Double.isFinite(minLng) || !Double.isFinite(maxLng)
                || minLat < -90 || maxLat > 90 || minLng < -180 || maxLng > 180 || minLat >= maxLat || minLng >= maxLng
                || maxLat - minLat > MAX_SPAN_DEGREES + 1e-9 || maxLng - minLng > MAX_SPAN_DEGREES + 1e-9) {
            throw new IllegalArgumentException("请选择一个城市范围内的区域");
        }
    }

    private static DiscoveryItem place(Map<String, Object> row) {
        if (sponsored(row)) return null;
        Long id = longValue(row.get("id"));
        Double lat = doubleValue(row.get("lat"));
        Double lng = doubleValue(row.get("lng"));
        String name = text(row.get("name"));
        if (id == null || lat == null || lng == null || name == null) return null;
        return new DiscoveryItem(DiscoveryItem.Kind.PLACE, id, name, text(row.get("address")), lat, lng,
                date(row.get("createTime")), false, longValue(row.get("merchantId")), id, text(row.get("coverImg")), null);
    }

    private static DiscoveryItem deal(Map<String, Object> row) {
        if (sponsored(row)) return null;
        Long id = longValue(row.get("couponId"));
        Double lat = doubleValue(row.get("lat"));
        Double lng = doubleValue(row.get("lng"));
        String name = text(row.get("name"));
        if (id == null || lat == null || lng == null || name == null) return null;
        return new DiscoveryItem(DiscoveryItem.Kind.DEAL, id, name, text(row.get("merchantName")), lat, lng,
                null, false, longValue(row.get("merchantId")), longValue(row.get("poiId")), text(row.get("coverImg")), null);
    }

    private static DiscoveryItem event(Map<String, Object> row) {
        if (sponsored(row)) return null;
        Long id = longValue(row.get("id"));
        Double lat = doubleValue(row.get("lat"));
        Double lng = doubleValue(row.get("lng"));
        String name = text(row.get("name"));
        if (id == null || lat == null || lng == null || name == null) return null;
        return new DiscoveryItem(DiscoveryItem.Kind.EVENT, id, name, text(row.get("addressName")), lat, lng,
                date(row.get("createTime")), row.get("clubId") != null, null, null, text(row.get("coverImg")),
                date(row.get("startDate")));
    }

    /** A source row that says it is sponsored never enters this list; sponsored items live in their own labelled section. */
    static boolean sponsored(Map<String, Object> row) {
        if (row == null) return true;
        Object flag = row.get("sponsored");
        return Boolean.TRUE.equals(flag) || (flag instanceof Number && ((Number) flag).intValue() != 0)
                || "true".equalsIgnoreCase(String.valueOf(flag)) || row.containsKey("label");
    }

    private static <T> List<T> safe(List<T> list) { return list == null ? new ArrayList<>() : list; }

    private static Long longValue(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static Double doubleValue(Object v) {
        double d;
        if (v instanceof Number) d = ((Number) v).doubleValue();
        else if (v instanceof String) {
            try { d = Double.parseDouble(((String) v).trim()); } catch (NumberFormatException e) { return null; }
        } else return null;
        return Double.isFinite(d) && d != 0 ? d : null;
    }

    private static String text(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Date date(Object v) {
        if (v instanceof Date) return (Date) v;
        if (v instanceof java.time.LocalDateTime) {
            return Date.from(((java.time.LocalDateTime) v).atZone(java.time.ZoneId.systemDefault()).toInstant());
        }
        return null;
    }
}

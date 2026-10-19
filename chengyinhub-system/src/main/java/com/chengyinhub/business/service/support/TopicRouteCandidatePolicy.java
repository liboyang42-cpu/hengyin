package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 以服务端事实判定下一站候选；UNKNOWN 与 UNAVAILABLE 都会从引擎候选集中移除。 */
public class TopicRouteCandidatePolicy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern BUSINESS_TIME = Pattern.compile(
            "^(.*?)\\s*(\\d{1,2}):(\\d{2})\\s*[-~—至]\\s*(次日)?(\\d{1,2}):(\\d{2})$");
    private static final Pattern WEEK_RANGE = Pattern.compile("^周([一二三四五六日天])至周?([一二三四五六日天])$");
    private static final Pattern WEEK_LIST = Pattern.compile("^周([一二三四五六日天](?:[、,，]周?[一二三四五六日天])*)$");
    private static final double EARTH_RADIUS_METERS = 6371008.8D;

    private final Clock clock;
    private final RouteReachabilityProvider reachabilityProvider;

    public TopicRouteCandidatePolicy(Clock clock, RouteReachabilityProvider reachabilityProvider) {
        this.clock = clock == null ? Clock.system(ZoneId.of("Asia/Shanghai")) : clock;
        this.reachabilityProvider = reachabilityProvider;
    }

    public Evaluation evaluate(String configJson, Facts facts, Long activityId,
                               CmsTopicNode currentNode, List<CmsTopicNode> liveNodes) {
        JsonNode config = read(configJson);
        Map<Long, JsonNode> requirements = requirements(config.path("nodeRequirements"));
        Map<Long, Status> statuses = new LinkedHashMap<Long, Status>();
        Map<Long, List<String>> reasons = new LinkedHashMap<Long, List<String>>();
        Set<Long> unavailable = new LinkedHashSet<Long>();
        List<CmsTopicNode> candidates = liveNodes == null ? Collections.<CmsTopicNode>emptyList() : liveNodes;
        for (CmsTopicNode candidate : candidates) {
            JsonNode requirement = requirements.get(candidate.getId());
            List<String> nodeReasons = new ArrayList<String>();
            Status status = requirement == null ? Status.AVAILABLE
                    : evaluateRequirement(requirement, facts, activityId, currentNode, candidate, nodeReasons);
            statuses.put(candidate.getId(), status);
            reasons.put(candidate.getId(), Collections.unmodifiableList(nodeReasons));
            if (status != Status.AVAILABLE) unavailable.add(candidate.getId());
        }
        return new Evaluation(statuses, reasons, unavailable);
    }

    private Status evaluateRequirement(JsonNode requirement, Facts facts, Long activityId,
                                       CmsTopicNode currentNode, CmsTopicNode candidate,
                                       List<String> reasons) {
        boolean unavailable = false;
        boolean unknown = false;
        Facts actual = facts == null ? Facts.unknown() : facts;

        if (requirement.path("allowedTicketIds").isArray()) {
            FactMatch match = matchesLong(requirement.path("allowedTicketIds"), actual.ticketIds);
            if (match == FactMatch.UNKNOWN) {
                reasons.add("TICKET_UNKNOWN"); unknown = true;
            } else if (match == FactMatch.REJECTED) {
                reasons.add("TICKET_NOT_ALLOWED"); unavailable = true;
            }
        }
        if (requirement.path("allowedPurchaseKinds").isArray()) {
            List<Long> purchaseKinds = new ArrayList<Long>();
            for (Integer value : actual.purchaseKinds) purchaseKinds.add(value == null ? null : value.longValue());
            FactMatch match = matchesLong(requirement.path("allowedPurchaseKinds"), purchaseKinds);
            if (match == FactMatch.UNKNOWN) {
                reasons.add("PURCHASE_KIND_UNKNOWN"); unknown = true;
            } else if (match == FactMatch.REJECTED) {
                reasons.add("PURCHASE_KIND_NOT_ALLOWED"); unavailable = true;
            }
        }
        if (requirement.path("requiredRoleCodes").isArray()) {
            if (!actual.roleKnown) {
                reasons.add("ROLE_UNKNOWN"); unknown = true;
            } else if (blank(actual.roleCode)
                    || !containsText(requirement.path("requiredRoleCodes"), actual.roleCode)) {
                reasons.add("ROLE_NOT_ALLOWED"); unavailable = true;
            }
        }
        if (requirement.path("requireOpen").asBoolean(false)) {
            Boolean open = isOpen(candidate == null ? null : candidate.getBusinessTime());
            if (open == null) {
                reasons.add("BUSINESS_TIME_UNKNOWN"); unknown = true;
            } else if (!open) {
                reasons.add("OUTSIDE_BUSINESS_HOURS"); unavailable = true;
            }
        }
        if (requirement.has("maxDistanceMeters")) {
            Double meters = segmentDistanceMeters(currentNode, candidate);
            if (meters == null) {
                reasons.add("SEGMENT_DISTANCE_UNKNOWN"); unknown = true;
            } else if (meters > requirement.path("maxDistanceMeters").asDouble()) {
                reasons.add("SEGMENT_DISTANCE_EXCEEDED"); unavailable = true;
            }
        }
        if (requirement.path("requireReachable").asBoolean(false)) {
            RouteReachabilityProvider.Reachability reachability = reachability(activityId, currentNode, candidate);
            if (reachability == RouteReachabilityProvider.Reachability.UNKNOWN) {
                reasons.add("REACHABILITY_UNKNOWN"); unknown = true;
            } else if (reachability == RouteReachabilityProvider.Reachability.UNREACHABLE) {
                reasons.add("UNREACHABLE"); unavailable = true;
            }
        }
        if (unavailable) return Status.UNAVAILABLE;
        return unknown ? Status.UNKNOWN : Status.AVAILABLE;
    }

    private RouteReachabilityProvider.Reachability reachability(Long activityId,
                                                                 CmsTopicNode from, CmsTopicNode to) {
        if (reachabilityProvider == null) return RouteReachabilityProvider.Reachability.UNKNOWN;
        try {
            RouteReachabilityProvider.Reachability result = reachabilityProvider.evaluate(activityId, from, to);
            return result == null ? RouteReachabilityProvider.Reachability.UNKNOWN : result;
        } catch (RuntimeException failedOrTimedOut) {
            return RouteReachabilityProvider.Reachability.UNKNOWN;
        }
    }

    private Boolean isOpen(String raw) {
        if (blank(raw)) return null;
        Matcher matcher = BUSINESS_TIME.matcher(raw.trim());
        if (!matcher.matches()) return null;
        Integer start = minute(matcher.group(2), matcher.group(3));
        Integer end = minute(matcher.group(5), matcher.group(6));
        if (start == null || end == null) return null;
        // 商家经营时间编辑器明确标记跨夜；旧值可省略，但不能把矛盾标记解释成普通当日或全天营业。
        if (matcher.group(4) != null && end >= start) return null;
        int now = LocalTime.now(clock).getHour() * 60 + LocalTime.now(clock).getMinute();
        LocalDate businessDate = LocalDate.now(clock);
        if (end < start && now <= end) businessDate = businessDate.minusDays(1L);
        Boolean dayOpen = isBusinessDay(matcher.group(1).trim(), businessDate.getDayOfWeek().getValue());
        if (dayOpen == null) return null;
        if (!dayOpen) return false;
        return end >= start ? now >= start && now <= end : now >= start || now <= end;
    }

    private Boolean isBusinessDay(String prefix, int today) {
        if (prefix.isEmpty() || "每日".equals(prefix) || "周一至周日".equals(prefix)
                || "周一至周天".equals(prefix)) return true;
        Matcher range = WEEK_RANGE.matcher(prefix);
        if (range.matches()) {
            int start = weekDay(range.group(1)); int end = weekDay(range.group(2));
            if (start <= end) return today >= start && today <= end;
            return today >= start || today <= end;
        }
        Matcher list = WEEK_LIST.matcher(prefix);
        if (!list.matches()) return null;
        String[] days = list.group(1).replace("周", "").split("[、,，]");
        for (String day : days) if (weekDay(day) == today) return true;
        return false;
    }

    private int weekDay(String value) {
        switch (value) {
            case "一": return DayOfWeek.MONDAY.getValue();
            case "二": return DayOfWeek.TUESDAY.getValue();
            case "三": return DayOfWeek.WEDNESDAY.getValue();
            case "四": return DayOfWeek.THURSDAY.getValue();
            case "五": return DayOfWeek.FRIDAY.getValue();
            case "六": return DayOfWeek.SATURDAY.getValue();
            case "日": case "天": return DayOfWeek.SUNDAY.getValue();
            default: return -1;
        }
    }

    private Integer minute(String hour, String minute) {
        try {
            int h = Integer.parseInt(hour); int m = Integer.parseInt(minute);
            return h >= 0 && h <= 23 && m >= 0 && m <= 59 ? h * 60 + m : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** 这是当前路线节点到候选节点的地理线段距离，不代表玩家实时位置或道路里程。 */
    private Double segmentDistanceMeters(CmsTopicNode from, CmsTopicNode to) {
        if (from == null || to == null) return null;
        Double fromLng = coordinate(from.getLongitude(), -180D, 180D);
        Double fromLat = coordinate(from.getLatitude(), -90D, 90D);
        Double toLng = coordinate(to.getLongitude(), -180D, 180D);
        Double toLat = coordinate(to.getLatitude(), -90D, 90D);
        if (fromLng == null || fromLat == null || toLng == null || toLat == null) return null;
        double lat1 = Math.toRadians(fromLat); double lat2 = Math.toRadians(toLat);
        double deltaLat = lat2 - lat1; double deltaLng = Math.toRadians(toLng - fromLng);
        double a = Math.sin(deltaLat / 2D) * Math.sin(deltaLat / 2D)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLng / 2D) * Math.sin(deltaLng / 2D);
        return EARTH_RADIUS_METERS * 2D * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
    }

    private Double coordinate(String raw, double min, double max) {
        if (blank(raw)) return null;
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) && value >= min && value <= max ? value : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private Map<Long, JsonNode> requirements(JsonNode raw) {
        Map<Long, JsonNode> result = new LinkedHashMap<Long, JsonNode>();
        if (raw.isArray()) for (JsonNode item : raw) result.put(item.path("nodeId").asLong(), item);
        return result;
    }

    private FactMatch matchesLong(JsonNode allowed, List<? extends Number> actual) {
        if (actual == null || actual.isEmpty()) return FactMatch.UNKNOWN;
        boolean hasUnknown = false;
        for (Number fact : actual) {
            if (fact == null) { hasUnknown = true; continue; }
            for (JsonNode item : allowed) if (item.asLong(Long.MIN_VALUE) == fact.longValue()) return FactMatch.MATCHED;
        }
        return hasUnknown ? FactMatch.UNKNOWN : FactMatch.REJECTED;
    }

    private boolean containsText(JsonNode array, String expected) {
        for (JsonNode item : array) if (expected.equals(item.asText())) return true;
        return false;
    }

    private JsonNode read(String json) {
        try { return JSON.readTree(json); }
        catch (Exception e) { throw new IllegalArgumentException("路线配置 JSON 无效", e); }
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    public enum Status { AVAILABLE, UNAVAILABLE, UNKNOWN }
    private enum FactMatch { MATCHED, REJECTED, UNKNOWN }

    public static final class Facts {
        private final List<Long> ticketIds;
        private final List<Integer> purchaseKinds;
        private final boolean roleKnown;
        private final String roleCode;

        public Facts(Long ticketId, Integer purchaseKind, boolean roleKnown, String roleCode) {
            this(Collections.singletonList(ticketId), Collections.singletonList(purchaseKind), roleKnown, roleCode);
        }

        public Facts(List<Long> ticketIds, List<Integer> purchaseKinds, boolean roleKnown, String roleCode) {
            this.ticketIds = ticketIds == null ? Collections.<Long>emptyList() : new ArrayList<Long>(ticketIds);
            this.purchaseKinds = purchaseKinds == null ? Collections.<Integer>emptyList()
                    : new ArrayList<Integer>(purchaseKinds);
            this.roleKnown = roleKnown; this.roleCode = roleCode;
        }

        public static Facts unknown() {
            return new Facts(Collections.<Long>emptyList(), Collections.<Integer>emptyList(), false, null);
        }
    }

    public static final class Evaluation {
        private final Map<Long, Status> nodeStatuses;
        private final Map<Long, List<String>> reasonCodes;
        private final Set<Long> unavailableNodeIds;

        private Evaluation(Map<Long, Status> nodeStatuses, Map<Long, List<String>> reasonCodes,
                           Set<Long> unavailableNodeIds) {
            this.nodeStatuses = Collections.unmodifiableMap(new LinkedHashMap<Long, Status>(nodeStatuses));
            this.reasonCodes = Collections.unmodifiableMap(new LinkedHashMap<Long, List<String>>(reasonCodes));
            this.unavailableNodeIds = Collections.unmodifiableSet(new LinkedHashSet<Long>(unavailableNodeIds));
        }

        public Map<Long, Status> getNodeStatuses() { return nodeStatuses; }
        public Map<Long, List<String>> getReasonCodes() { return reasonCodes; }
        public Set<Long> getUnavailableNodeIds() { return unavailableNodeIds; }
    }
}

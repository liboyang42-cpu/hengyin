package com.chengyinhub.business.gamemech;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.gamemech.mapper.GameMechanicsMapper;
import com.chengyinhub.business.locationtrust.LocationFix;
import com.chengyinhub.business.locationtrust.LocationTrustKind;
import com.chengyinhub.business.locationtrust.LocationTrustMessages;
import com.chengyinhub.business.locationtrust.LocationTrustService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * G2 / GAM-13 walk-test gate. Before publishing, a creator records a desk rehearsal (played through in the app's
 * rehearsal) and an on-site test (a location-trusted fix within {@link #ONSITE_RADIUS_M} of one of the journey's
 * nodes). The server stores both. The publish check shows the gate; with {@code creator.walktest.gate=ENFORCE}
 * publishing is refused until both are recorded or an operator waives the gate with a note.
 *
 * <p>Mode default is ADVISE (shown, not blocking) until the owner decides. Missing tables or config fail open
 * (the gate reads as OFF and an error is logged), so publishing never breaks because of a deploy order.
 */
@Service
public class WalkTestService {
    private static final Logger log = LoggerFactory.getLogger(WalkTestService.class);
    public static final String CONFIG_KEY = "creator.walktest.gate";
    public static final String DESK = "DESK", ONSITE = "ONSITE";
    public static final String CODE_WALK_TEST_MISSING = "WALK_TEST_MISSING";
    static final double ONSITE_RADIUS_M = 300d;

    public enum Mode { OFF, ADVISE, ENFORCE }

    private final GameMechanicsMapper mapper;
    private final LocationTrustService trust;
    private final ISysConfigService config;

    @Autowired
    public WalkTestService(GameMechanicsMapper mapper, LocationTrustService trust,
                           @Autowired(required = false) ISysConfigService config) {
        this.mapper = mapper;
        this.trust = trust;
        this.config = config;
    }

    public Mode mode() {
        try {
            String raw = config == null ? null : config.selectConfigByKey(CONFIG_KEY);
            if (raw == null || raw.isBlank()) return Mode.ADVISE;
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Mode.ADVISE;
        } catch (RuntimeException e) {
            log.error("walk-test gate config unreadable reason={}", e.getClass().getSimpleName());
            return Mode.OFF;
        }
    }

    /** Gate state for one topic: what is recorded, whether it is waived, and whether publishing is blocked. */
    public Map<String, Object> status(Long topicId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Mode mode = mode();
        out.put("mode", mode.name());
        boolean desk = false, onsite = false;
        List<Map<String, Object>> records = new ArrayList<>();
        Map<String, Object> waiver = null;
        boolean readable = true;
        try {
            for (Map<String, Object> row : mapper.selectWalkTests(topicId)) {
                records.add(row);
                if (DESK.equals(row.get("kind"))) desk = true;
                if (ONSITE.equals(row.get("kind")) && "ACCEPT".equals(row.get("fixDecision"))) onsite = true;
            }
            waiver = mapper.selectWaiver(topicId);
        } catch (RuntimeException e) {
            readable = false;
            log.error("walk-test records unreadable topic={} reason={}", topicId, e.getClass().getSimpleName());
        }
        out.put("readable", readable);
        out.put("deskRecorded", desk);
        out.put("onsiteRecorded", onsite);
        out.put("records", records);
        if (waiver != null) out.put("waiver", waiver);
        boolean satisfied = (desk && onsite) || waiver != null;
        out.put("satisfied", satisfied);
        out.put("blocksPublishing", readable && mode == Mode.ENFORCE && !satisfied);
        return out;
    }

    /** Publish path: refuse when the gate is enforced and not satisfied. */
    public void assertPublishable(CmsTopic topic) {
        if (topic == null || topic.getId() == null) return;
        if (mode() != Mode.ENFORCE) return;
        Map<String, Object> s = status(topic.getId());
        if (Boolean.TRUE.equals(s.get("blocksPublishing"))) throw new ServiceException(missingMessage(s), 409);
    }

    /** Publish-check issue text, or null when nothing is missing / the gate is off. */
    public String checkMessage(Long topicId) {
        if (topicId == null || mode() == Mode.OFF) return null;
        Map<String, Object> s = status(topicId);
        if (!Boolean.TRUE.equals(s.get("readable")) || Boolean.TRUE.equals(s.get("satisfied"))) return null;
        return missingMessage(s);
    }

    static String missingMessage(Map<String, Object> s) {
        boolean desk = Boolean.TRUE.equals(s.get("deskRecorded")), onsite = Boolean.TRUE.equals(s.get("onsiteRecorded"));
        if (!desk && !onsite) return "发布前请先完成一次桌面排练和一次实地试走";
        return desk ? "发布前请到路线上的任一地点完成一次实地试走" : "发布前请先在应用里完成一次桌面排练";
    }

    public Map<String, Object> recordDesk(Long memberId, CmsTopic topic, int nodesChecked, String note) {
        requireOwner(memberId, topic);
        int total = mapper.countEnabledNodes(topic.getId());
        if (nodesChecked < 1 || nodesChecked > Math.max(1, total)) throw new ServiceException("排练记录的地点数不正确", 400);
        Map<String, Object> row = base(topic.getId(), DESK, memberId, nodesChecked, total, note);
        mapper.insertWalkTest(row);
        return status(topic.getId());
    }

    public Map<String, Object> recordOnsite(Long memberId, CmsTopic topic, LocationFix fix, String note) {
        requireOwner(memberId, topic);
        if (fix == null || !fix.validCoordinates()) throw new ServiceException("请先打开定位", 400);
        Long nearNode = null;
        double best = Double.MAX_VALUE;
        for (Map<String, Object> node : mapper.selectNodePoints(topic.getId())) {
            Double lat = GeoDistance.number(node.get("lat")), lng = GeoDistance.number(node.get("lng"));
            if (lat == null || lng == null) continue;
            double d = GeoDistance.meters(fix.lat(), fix.lng(), lat, lng);
            if (d < best) { best = d; nearNode = TreasureHuntService.asLong(node.get("id")); }
        }
        if (nearNode == null || best > ONSITE_RADIUS_M) throw new ServiceException("你离路线上的地点还远,到了再记录", 409);
        LocationTrustService.Assessment a = trust.assess(memberId, null, LocationTrustKind.WALK_TEST_ONSITE,
                "topic:" + topic.getId(), fix);
        if (a.rejected()) throw new ServiceException(LocationTrustMessages.rejected(a.reasons()), 409);
        Map<String, Object> row = base(topic.getId(), ONSITE, memberId, 1, mapper.countEnabledNodes(topic.getId()), note);
        row.put("fixDecision", a.applied().name());
        row.put("fixId", a.fixId());
        row.put("nearNodeId", nearNode);
        mapper.insertWalkTest(row);
        Map<String, Object> out = status(topic.getId());
        if (a.withheld()) out.put("trustMessage", LocationTrustMessages.withheld(a.reasons()));
        return out;
    }

    public List<Map<String, Object>> adminSummary() {
        return mapper.selectWalkTestSummary();
    }

    public Map<String, Object> waive(Long topicId, String note, String operator) {
        String clean = note == null ? "" : note.trim();
        if (topicId == null || topicId <= 0) throw new ServiceException("参数有误", 400);
        if (clean.isEmpty() || clean.length() > 300) throw new ServiceException("请写明豁免理由(300 字以内)", 400);
        if (mapper.selectTopicBrief(topicId) == null) throw new ServiceException("路线不存在", 404);
        mapper.upsertWaiver(topicId, operator == null ? "operator" : operator, clean);
        return status(topicId);
    }

    public Map<String, Object> unwaive(Long topicId) {
        mapper.deleteWaiver(topicId);
        return status(topicId);
    }

    private static Map<String, Object> base(Long topicId, String kind, Long memberId, int checked, int total, String note) {
        String clean = note == null ? null : note.trim();
        if (clean != null && clean.length() > 300) throw new ServiceException("备注不能超过 300 字", 400);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("topicId", topicId);
        row.put("kind", kind);
        row.put("memberId", memberId);
        row.put("nodesChecked", checked);
        row.put("nodesTotal", total);
        row.put("note", clean == null || clean.isEmpty() ? null : clean);
        row.put("fixDecision", null);
        row.put("fixId", null);
        row.put("nearNodeId", null);
        return row;
    }

    private static void requireOwner(Long memberId, CmsTopic topic) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", 401);
        if (topic == null || !memberId.equals(topic.getMemberId())) throw new ServiceException("只有路线作者可以记录试走", 403);
    }
}

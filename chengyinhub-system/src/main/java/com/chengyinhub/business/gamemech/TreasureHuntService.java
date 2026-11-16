package com.chengyinhub.business.gamemech;

import com.chengyinhub.business.gamemech.mapper.GameMechanicsMapper;
import com.chengyinhub.business.locationtrust.LocationFix;
import com.chengyinhub.business.locationtrust.LocationTrustKind;
import com.chengyinhub.business.locationtrust.LocationTrustMessages;
import com.chengyinhub.business.locationtrust.LocationTrustService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * G2 / GAM-06 treasure hunt: hidden items across several places. Players see clues and a rough search area,
 * never the exact spot. A find is a location-trusted check-in near an unfound item; finding all of them
 * completes the hunt and pays the 寻宝完成 points rule once (event 41, idempotent per hunt).
 *
 * <p>Status: 0 draft, 1 in review, 2 live, 3 sent back, 4 ended. Players and creators write drafts; only
 * operators make a hunt live (the normal content review).
 */
@Service
public class TreasureHuntService {
    public static final long POINTS_EVENT = 41L;
    public static final int DRAFT = 0, IN_REVIEW = 1, LIVE = 2, SENT_BACK = 3, ENDED = 4;
    static final int MIN_ITEMS = 2, MAX_ITEMS = 20;
    static final double ACCURACY_ALLOWANCE_M = 50d;

    private final GameMechanicsMapper mapper;
    private final LocationTrustService trust;
    private final IPointsAwardService points;

    @Autowired
    public TreasureHuntService(GameMechanicsMapper mapper, LocationTrustService trust, IPointsAwardService points) {
        this.mapper = mapper;
        this.trust = trust;
        this.points = points;
    }

    // ------------------------------------------------------------------ player

    public List<Map<String, Object>> liveList(String cityCode, Long memberId) {
        List<Map<String, Object>> rows = mapper.selectLiveHunts(cityCode, 50);
        Map<Long, Map<String, Object>> mine = new LinkedHashMap<>();
        if (memberId != null && memberId > 0) {
            for (Map<String, Object> p : mapper.selectMemberProgress(memberId)) mine.put(asLong(p.get("huntId")), p);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = publicHunt(row);
            Map<String, Object> p = mine.get(asLong(row.get("id")));
            item.put("foundCount", p == null ? 0 : asInt(p.get("foundCount")));
            item.put("completed", p != null && p.get("completedAt") != null);
            out.add(item);
        }
        return out;
    }

    public Map<String, Object> playerDetail(Long huntId, Long memberId) {
        Map<String, Object> hunt = mapper.selectHunt(huntId);
        if (hunt == null || asInt(hunt.get("status")) != LIVE) throw new ServiceException("这场寻宝不存在或已结束", 404);
        Set<Long> found = foundIds(huntId, memberId);
        Map<String, Object> out = publicHunt(hunt);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> raw : mapper.selectItems(huntId)) {
            Long id = asLong(raw.get("id"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("sort", asInt(raw.get("sort")));
            item.put("clue", raw.get("clue"));
            item.put("radiusM", asInt(raw.get("radiusM")));
            Double lat = GeoDistance.number(raw.get("lat")), lng = GeoDistance.number(raw.get("lng"));
            if (lat != null && lng != null) {
                item.put("areaLat", GeoDistance.area(lat));
                item.put("areaLng", GeoDistance.area(lng));
            }
            boolean isFound = found.contains(id);
            item.put("found", isFound);
            if (isFound) item.put("name", raw.get("name"));
            items.add(item);
        }
        out.put("items", items);
        out.put("itemCount", items.size());
        Map<String, Object> progress = memberId == null ? null : mapper.selectProgress(huntId, memberId);
        out.put("foundCount", (int) items.stream().filter(i -> Boolean.TRUE.equals(i.get("found"))).count());
        out.put("completed", progress != null && progress.get("completedAt") != null);
        out.put("rewardPoints", progress == null ? 0 : asInt(progress.get("rewardPoints")));
        return out;
    }

    /** One location-trusted find attempt. Never throws for "nothing here": that is an ordinary answer with a hint. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> find(Long huntId, Long memberId, LocationFix fix) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", 401);
        if (fix == null || !fix.validCoordinates()) throw new ServiceException("请先打开定位", 400);
        Map<String, Object> hunt = mapper.selectHunt(huntId);
        if (hunt == null || asInt(hunt.get("status")) != LIVE || !liveNow(hunt)) throw new ServiceException("这场寻宝不存在或已结束", 404);
        // W7 GAME-16: the creator saw the exact spots, so their own hunt is not theirs to collect.
        if (memberId.equals(asLong(hunt.get("creatorMemberId")))) throw new ServiceException("这是你自己藏的宝", 403);
        LocationTrustService.Assessment assessment = trust.assess(memberId, null, LocationTrustKind.TREASURE_FIND,
                "hunt:" + huntId, fix);
        if (assessment.rejected()) throw new ServiceException(LocationTrustMessages.rejected(assessment.reasons()), 409);
        Map<String, Object> out = new LinkedHashMap<>();
        if (assessment.withheld()) {
            out.put("found", false);
            out.put("uncredited", true);
            out.put("trustCode", LocationTrustMessages.code(assessment.reasons()));
            out.put("trustMessage", LocationTrustMessages.withheld(assessment.reasons()));
            return out;
        }
        List<Map<String, Object>> items = mapper.selectItems(huntId);
        Set<Long> found = foundIds(huntId, memberId);
        double allowance = fix.accuracyMeters() == null || !Double.isFinite(fix.accuracyMeters()) ? 0d
                : Math.min(ACCURACY_ALLOWANCE_M, Math.max(0d, fix.accuracyMeters()));
        Map<String, Object> hit = null;
        double nearest = Double.MAX_VALUE;
        for (Map<String, Object> item : items) {
            if (found.contains(asLong(item.get("id")))) continue;
            Double lat = GeoDistance.number(item.get("lat")), lng = GeoDistance.number(item.get("lng"));
            if (lat == null || lng == null) continue;
            double d = GeoDistance.meters(fix.lat(), fix.lng(), lat, lng);
            if (d <= asInt(item.get("radiusM")) + allowance && (hit == null || d < nearest)) hit = item;
            nearest = Math.min(nearest, d);
        }
        if (hit == null) {
            out.put("found", false);
            out.put("hint", nearest == Double.MAX_VALUE ? "NONE" : nearest < 150 ? "NEAR" : nearest < 500 ? "CLOSE" : "FAR");
            return out;
        }
        Long itemId = asLong(hit.get("id"));
        mapper.insertFind(huntId, itemId, memberId, assessment.fixId());
        // W7 GAME-16: only finds of items that are still in the hunt count toward completion.
        int foundCount = activeFound(foundIds(huntId, memberId), items);
        mapper.upsertProgress(huntId, memberId, foundCount);
        boolean completed = foundCount >= items.size() && !items.isEmpty();
        int reward = 0;
        if (completed) {
            Map<String, Object> progress = mapper.selectProgress(huntId, memberId);
            if (progress != null && progress.get("completedAt") == null) {
                reward = points == null ? 0 : points.award(memberId, POINTS_EVENT, huntId, "寻宝完成:" + hunt.get("title"));
                mapper.markCompleted(huntId, memberId, reward);
            } else if (progress != null) {
                reward = asInt(progress.get("rewardPoints"));
            }
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", itemId);
        item.put("name", hit.get("name"));
        item.put("clue", hit.get("clue"));
        out.put("found", true);
        out.put("item", item);
        out.put("foundCount", foundCount);
        out.put("itemCount", items.size());
        out.put("completed", completed);
        out.put("rewardPoints", reward);
        return out;
    }

    // ------------------------------------------------------------------ creator (player app)

    public List<Map<String, Object>> creatorList(Long memberId) {
        requireMember(memberId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : mapper.selectHuntsByCreator(memberId)) {
            Map<String, Object> item = publicHunt(row);
            item.put("status", asInt(row.get("status")));
            item.put("reviewNote", row.get("reviewNote"));
            out.add(item);
        }
        return out;
    }

    public Map<String, Object> creatorDetail(Long memberId, Long huntId) {
        requireMember(memberId);
        Map<String, Object> hunt = mapper.selectHunt(huntId);
        if (hunt == null || !memberId.equals(asLong(hunt.get("creatorMemberId")))) throw new ServiceException("寻宝不存在", 404);
        return fullDetail(hunt);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> creatorSave(Long memberId, Map<String, Object> body) {
        requireMember(memberId);
        Long id = asLong(body.get("id"));
        if (id != null && id > 0) {
            Map<String, Object> hunt = mapper.selectHuntForUpdate(id);
            if (hunt == null || !memberId.equals(asLong(hunt.get("creatorMemberId")))) throw new ServiceException("寻宝不存在", 404);
            int status = asInt(hunt.get("status"));
            if (status != DRAFT && status != SENT_BACK) throw new ServiceException("审核中或已上线的寻宝不能修改", 409);
        }
        Map<String, Object> row = validated(body);
        row.put("creatorMemberId", memberId);
        Long saved = write(id, row, "member:" + memberId);
        if (id != null && id > 0) mapper.updateHuntStatus(saved, DRAFT, null, "member:" + memberId);
        return creatorDetail(memberId, saved);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> creatorSubmit(Long memberId, Long huntId) {
        requireMember(memberId);
        Map<String, Object> hunt = mapper.selectHuntForUpdate(huntId);
        if (hunt == null || !memberId.equals(asLong(hunt.get("creatorMemberId")))) throw new ServiceException("寻宝不存在", 404);
        int status = asInt(hunt.get("status"));
        if (status == IN_REVIEW) return creatorDetail(memberId, huntId);
        if (status != DRAFT && status != SENT_BACK) throw new ServiceException("这场寻宝不能再提交", 409);
        int count = mapper.selectItems(huntId).size();
        if (count < MIN_ITEMS) throw new ServiceException("至少需要 " + MIN_ITEMS + " 件宝藏才能提交", 400);
        mapper.updateHuntStatus(huntId, IN_REVIEW, null, "member:" + memberId);
        return creatorDetail(memberId, huntId);
    }

    // ------------------------------------------------------------------ operators

    public List<Map<String, Object>> adminList(Integer status) {
        List<Map<String, Object>> rows = mapper.selectAdminHunts(status);
        for (Map<String, Object> row : rows) {
            Long id = asLong(row.get("id"));
            row.put("players", mapper.countPlayers(id));
            row.put("finishers", mapper.countFinishers(id));
        }
        return rows;
    }

    public Map<String, Object> adminDetail(Long huntId) {
        Map<String, Object> hunt = mapper.selectHunt(huntId);
        if (hunt == null) throw new ServiceException("寻宝不存在", 404);
        Map<String, Object> out = fullDetail(hunt);
        out.put("players", mapper.countPlayers(huntId));
        out.put("finishers", mapper.countFinishers(huntId));
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adminSave(Map<String, Object> body, String operator) {
        Long id = asLong(body.get("id"));
        Map<String, Object> row = validated(body);
        if (id != null && id > 0) {
            Map<String, Object> hunt = mapper.selectHuntForUpdate(id);
            if (hunt == null) throw new ServiceException("寻宝不存在", 404);
            row.put("creatorMemberId", hunt.get("creatorMemberId"));
        }
        return adminDetail(write(id, row, operator));
    }

    @Transactional(rollbackFor = Exception.class)
    public void adminDecide(Long huntId, String decision, String note, String operator) {
        Map<String, Object> hunt = mapper.selectHuntForUpdate(huntId);
        if (hunt == null) throw new ServiceException("寻宝不存在", 404);
        int status = asInt(hunt.get("status"));
        String cleanNote = note == null ? null : note.trim();
        switch (decision == null ? "" : decision) {
            case "approve" -> {
                if (status != IN_REVIEW && status != DRAFT && status != ENDED) throw new ServiceException("当前状态不能上线", 409);
                if (mapper.selectItems(huntId).size() < MIN_ITEMS) throw new ServiceException("至少需要 " + MIN_ITEMS + " 件宝藏", 400);
                mapper.updateHuntStatus(huntId, LIVE, cleanNote, operator);
            }
            case "reject" -> {
                if (cleanNote == null || cleanNote.isEmpty() || cleanNote.length() > 300) throw new ServiceException("请写明退回原因(300 字以内)", 400);
                if (status != IN_REVIEW && status != LIVE) throw new ServiceException("当前状态不能退回", 409);
                mapper.updateHuntStatus(huntId, SENT_BACK, cleanNote, operator);
            }
            case "end" -> {
                if (status != LIVE) throw new ServiceException("只有进行中的寻宝可以结束", 409);
                mapper.updateHuntStatus(huntId, ENDED, cleanNote, operator);
            }
            default -> throw new ServiceException("未知操作", 400);
        }
    }

    // ------------------------------------------------------------------ helpers

    Map<String, Object> validated(Map<String, Object> body) {
        String title = text(body.get("title"));
        if (title.isEmpty() || title.length() > 64) throw new ServiceException("标题须为 1 至 64 字", 400);
        String intro = text(body.get("intro"));
        if (intro.length() > 1000) throw new ServiceException("介绍不能超过 1000 字", 400);
        String cover = text(body.get("coverImg"));
        if (!cover.isEmpty() && (cover.length() > 500 || !(cover.startsWith("https://") || cover.startsWith("/")))) {
            throw new ServiceException("封面必须是 https 链接或站内图片", 400);
        }
        String city = text(body.get("cityCode"));
        if (city.length() > 16) throw new ServiceException("城市编码不正确", 400);
        Object rawItems = body.get("items");
        if (!(rawItems instanceof List<?> list) || list.size() < MIN_ITEMS || list.size() > MAX_ITEMS) {
            throw new ServiceException("宝藏数量须为 " + MIN_ITEMS + " 至 " + MAX_ITEMS + " 件", 400);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int sort = 0;
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> m)) throw new ServiceException("宝藏格式不正确", 400);
            String name = text(m.get("name")), clue = text(m.get("clue"));
            if (name.isEmpty() || name.length() > 40) throw new ServiceException("宝藏名须为 1 至 40 字", 400);
            if (clue.isEmpty() || clue.length() > 300) throw new ServiceException("线索须为 1 至 300 字", 400);
            Double lat = GeoDistance.number(m.get("lat")), lng = GeoDistance.number(m.get("lng"));
            if (!GeoDistance.validChina(lat, lng)) throw new ServiceException("宝藏「" + name + "」的位置不正确", 400);
            Double radius = GeoDistance.number(m.get("radiusM"));
            int r = radius == null ? 60 : (int) Math.round(radius);
            if (r < 20 || r > 300) throw new ServiceException("寻找半径须为 20 至 300 米", 400);
            Map<String, Object> item = new LinkedHashMap<>();
            Long itemId = asLong(m.get("id"));
            if (itemId != null && itemId > 0) item.put("id", itemId);
            item.put("sort", sort++);
            item.put("name", name);
            item.put("clue", clue);
            item.put("lat", lat);
            item.put("lng", lng);
            item.put("radiusM", r);
            items.add(item);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("intro", intro.isEmpty() ? null : intro);
        row.put("coverImg", cover.isEmpty() ? null : cover);
        row.put("cityCode", city.isEmpty() ? null : city);
        row.put("aiAssisted", Boolean.TRUE.equals(body.get("aiAssisted")) || "1".equals(String.valueOf(body.get("aiAssisted"))) ? 1 : 0);
        row.put("startTime", parseTime(body.get("startTime")));
        row.put("endTime", parseTime(body.get("endTime")));
        row.put("items", items);
        return row;
    }

    @SuppressWarnings("unchecked")
    private Long write(Long id, Map<String, Object> row, String by) {
        Long huntId = id;
        if (huntId == null || huntId <= 0) {
            row.put("status", DRAFT);
            row.put("createBy", by);
            mapper.insertHunt(row);
            huntId = asLong(row.get("id"));
        } else {
            row.put("id", huntId);
            row.put("updateBy", by);
            mapper.updateHunt(row);
        }
        // W7 GAME-16: an edit keeps item ids. An item sent back with its id is updated in place (players keep that
        // find); a new item is inserted; an item left out is removed, and its old finds stop counting.
        Set<Long> existing = new HashSet<>();
        if (id != null && id > 0) for (Map<String, Object> old : mapper.selectItems(huntId)) existing.add(asLong(old.get("id")));
        Set<Long> kept = new HashSet<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) row.get("items")) {
            item.put("huntId", huntId);
            Long itemId = asLong(item.get("id"));
            if (itemId != null && existing.contains(itemId) && kept.add(itemId)) {
                mapper.updateItem(item);
            } else {
                item.remove("id");
                mapper.insertItem(item);
            }
        }
        for (Long old : existing) if (!kept.contains(old)) mapper.deleteItem(huntId, old);
        return huntId;
    }

    private Map<String, Object> fullDetail(Map<String, Object> hunt) {
        Map<String, Object> out = publicHunt(hunt);
        out.put("status", asInt(hunt.get("status")));
        out.put("reviewNote", hunt.get("reviewNote"));
        out.put("cityCode", hunt.get("cityCode"));
        out.put("creatorMemberId", hunt.get("creatorMemberId"));
        out.put("startTime", hunt.get("startTime"));
        out.put("endTime", hunt.get("endTime"));
        out.put("items", mapper.selectItems(asLong(hunt.get("id"))));
        return out;
    }

    private Map<String, Object> publicHunt(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", asLong(row.get("id")));
        out.put("title", row.get("title"));
        out.put("intro", row.get("intro"));
        out.put("coverImg", row.get("coverImg"));
        out.put("aiAssisted", asInt(row.get("aiAssisted")) == 1);
        if (row.containsKey("itemCount")) out.put("itemCount", asInt(row.get("itemCount")));
        out.put("endTime", row.get("endTime"));
        out.put("official", row.get("creatorMemberId") == null);
        return out;
    }

    /** Finds that point at an item still in the hunt. */
    static int activeFound(Set<Long> found, List<Map<String, Object>> items) {
        int n = 0;
        for (Map<String, Object> item : items) if (found.contains(asLong(item.get("id")))) n++;
        return n;
    }

    private Set<Long> foundIds(Long huntId, Long memberId) {
        Set<Long> out = new HashSet<>();
        if (memberId == null || memberId <= 0) return out;
        for (Map<String, Object> f : mapper.selectFinds(huntId, memberId)) out.add(asLong(f.get("itemId")));
        return out;
    }

    private static boolean liveNow(Map<String, Object> hunt) {
        long now = System.currentTimeMillis();
        Date start = asDate(hunt.get("startTime")), end = asDate(hunt.get("endTime"));
        if (start != null && start.getTime() > now) return false;
        return end == null || end.getTime() > now;
    }

    /** Accepts epoch millis or "yyyy-MM-dd HH:mm:ss"; blank means open-ended. */
    static Date parseTime(Object raw) {
        if (raw == null || raw.toString().isBlank()) return null;
        if (raw instanceof Number n) return new Date(n.longValue());
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(raw.toString().trim());
        } catch (java.text.ParseException e) {
            throw new ServiceException("时间格式应为 yyyy-MM-dd HH:mm:ss", 400);
        }
    }

    private static void requireMember(Long memberId) {
        if (memberId == null || memberId <= 0) throw new ServiceException("请先登录", 401);
    }

    static String text(Object raw) { return raw == null ? "" : raw.toString().trim(); }

    static Long asLong(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.longValue();
        try { return Long.valueOf(raw.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    /** MyBatis map rows may carry Date, Timestamp or LocalDateTime depending on the driver. */
    static Date asDate(Object raw) {
        if (raw instanceof Date d) return d;
        if (raw instanceof java.time.LocalDateTime t) return Date.from(t.atZone(java.time.ZoneId.systemDefault()).toInstant());
        return null;
    }

    static int asInt(Object raw) {
        Long v = asLong(raw);
        return v == null ? 0 : v.intValue();
    }
}

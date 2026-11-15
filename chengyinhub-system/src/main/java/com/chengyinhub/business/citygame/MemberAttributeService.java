package com.chengyinhub.business.citygame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Attributes v2 persistence: idempotent AXP grants (UK member/attr/source/source_id), daily soft cap, levels,
 * titles (member_title, category ATTR) and the display archetype. Only server-trusted facts call {@link #grant}.
 */
@Service
public class MemberAttributeService {
    private final JdbcTemplate jdbc;
    private final CityMonopolyConfigService configs;
    private final LongSupplier clock;

    @Autowired
    public MemberAttributeService(JdbcTemplate jdbc, CityMonopolyConfigService configs) {
        this(jdbc, configs, System::currentTimeMillis);
    }

    MemberAttributeService(JdbcTemplate jdbc, CityMonopolyConfigService configs, LongSupplier clock) {
        this.jdbc = jdbc;
        this.configs = configs;
        this.clock = clock;
    }

    private LocalDate today(CityMonopolyConfig c) {
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock.getAsLong()), CityMonopolyRules.zone(c)).toLocalDate();
    }

    /**
     * Grants AXP for one trusted source event. Returns the effective AXP added (0 on replay or when capped).
     * {@code units}: km, thousands of steps or likes for per-unit sources, otherwise 1.
     */
    @Transactional(rollbackFor = Exception.class)
    public long grant(long memberId, AttributeRules.Source source, String sourceId, double units, Double recentAccuracy) {
        if (memberId <= 0 || source == null || sourceId == null || sourceId.isEmpty()) return 0;
        CityMonopolyConfig c = configs.global();
        LocalDate day = today(c);
        String attr = source.attr;
        long alreadyUnits = 0;
        if (source == AttributeRules.Source.STEPS_1000 || source == AttributeRules.Source.PHOTO_LIKE) {
            Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(raw),0) FROM member_attribute_ledger WHERE member_id = ? AND attr_key = ? AND source = ? AND day = ?",
                    Long.class, memberId, attr, source.name(), Date.valueOf(day));
            double per = c.num("attributes.v2." + source.configKey);
            alreadyUnits = per <= 0 || sum == null ? 0 : Math.round(sum / per);
        }
        long raw = AttributeRules.rawAxp(c, source, units, recentAccuracy, alreadyUnits);
        if (raw <= 0) return 0;
        jdbc.update("INSERT IGNORE INTO member_attribute(member_id, attr_key, axp, level, today_day) VALUES (?,?,0,1,?)",
                memberId, attr, Date.valueOf(day));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT axp, level, today_raw, today_eff, today_day FROM member_attribute WHERE member_id = ? AND attr_key = ? FOR UPDATE",
                memberId, attr);
        boolean sameDay = row.get("today_day") != null && day.equals(((java.sql.Date) row.get("today_day")).toLocalDate());
        long rawBefore = sameDay ? ((Number) row.get("today_raw")).longValue() : 0;
        long effBefore = sameDay ? ((Number) row.get("today_eff")).longValue() : 0;
        long eff = AttributeRules.effectiveGrant(c, attr, rawBefore, raw);
        try {
            jdbc.update("INSERT INTO member_attribute_ledger(member_id, attr_key, raw, eff, source, source_id, day) VALUES (?,?,?,?,?,?,?)",
                    memberId, attr, raw, eff, source.name(), sourceId, Date.valueOf(day));
        } catch (DuplicateKeyException replay) {
            return 0;
        }
        long axp = ((Number) row.get("axp")).longValue() + eff;
        int oldLevel = ((Number) row.get("level")).intValue();
        int level = AttributeRules.levelFor(c, axp);
        jdbc.update("UPDATE member_attribute SET axp = ?, level = ?, season_axp = season_axp + ?, today_raw = ?, today_eff = ?, today_day = ? "
                        + "WHERE member_id = ? AND attr_key = ?",
                axp, level, eff, rawBefore + raw, effBefore + eff, Date.valueOf(day), memberId, attr);
        if (level > oldLevel) awardTitles(c, memberId, attr, level);
        return eff;
    }

    public long grant(long memberId, AttributeRules.Source source, String sourceId) {
        return grant(memberId, source, sourceId, 1, null);
    }

    private void awardTitles(CityMonopolyConfig c, long memberId, String attr, int level) {
        for (AttributeRules.Title t : AttributeRules.titlesUpTo(c, attr, level)) {
            jdbc.update("INSERT IGNORE INTO member_title(member_id, title_code, title_name, category, source_id) VALUES (?,?,?,'ATTR',?)",
                    memberId, t.code(), t.name(), attr + ":" + t.level());
        }
    }

    /** Current levels (missing attributes are Lv1). */
    public Map<String, Integer> levels(long memberId) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String k : AttributeRules.KEYS) out.put(k, 1);
        if (memberId <= 0) return out;
        for (Map<String, Object> r : jdbc.queryForList("SELECT attr_key, level FROM member_attribute WHERE member_id = ?", memberId)) {
            out.put(String.valueOf(r.get("attr_key")), ((Number) r.get("level")).intValue());
        }
        return out;
    }

    public int level(long memberId, String attr) {
        return AttributeRules.effectiveLevel(configs.global(), attr, levels(memberId).getOrDefault(attr, 1));
    }

    /** GET /api/member/attributes payload. */
    public Map<String, Object> view(long memberId) {
        CityGameSupport.requireMember(memberId);
        CityMonopolyConfig c = configs.global();
        LocalDate day = today(c);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT attr_key, axp, level, season_axp, today_raw, today_eff, today_day FROM member_attribute WHERE member_id = ?", memberId)) {
            rows.put(String.valueOf(r.get("attr_key")), r);
        }
        List<Map<String, Object>> attrs = new ArrayList<>();
        Map<String, Integer> levels = new LinkedHashMap<>();
        Map<String, Object> seasonDelta = new LinkedHashMap<>();
        for (String k : AttributeRules.KEYS) {
            Map<String, Object> r = rows.get(k);
            long axp = r == null ? 0 : ((Number) r.get("axp")).longValue();
            int level = AttributeRules.levelFor(c, axp);
            boolean sameDay = r != null && r.get("today_day") != null && day.equals(((java.sql.Date) r.get("today_day")).toLocalDate());
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("key", k);
            a.put("level", level);
            a.put("effectiveLevel", AttributeRules.effectiveLevel(c, k, level));
            a.put("axp", axp);
            a.put("levelAxp", axp - AttributeRules.cumulative(c, level));
            a.put("need", AttributeRules.need(c, level));
            a.put("todayAxp", sameDay ? ((Number) r.get("today_eff")).longValue() : 0L);
            a.put("softCap", (long) AttributeRules.softCap(c, k));
            a.put("nextUnlock", AttributeRules.nextUnlock(k, level));
            attrs.add(a);
            levels.put(k, level);
            seasonDelta.put(k, r == null ? 0L : ((Number) r.get("season_axp")).longValue());
        }
        AttributeRules.Archetype arch = AttributeRules.archetype(c, levels);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attrs", attrs);
        out.put("archetype", Map.of("code", arch.code(), "name", arch.name()));
        List<Map<String, Object>> equipped = jdbc.queryForList(
                "SELECT title_code AS code, title_name AS name, category FROM member_title WHERE member_id = ? AND equipped = 1 LIMIT 1", memberId);
        out.put("equippedTitle", equipped.isEmpty() ? null : equipped.get(0));
        out.put("titles", jdbc.queryForList(
                "SELECT title_code AS code, title_name AS name, category, equipped FROM member_title WHERE member_id = ? ORDER BY FIELD(category,'THEME','SEASON','CLUB','ATTR'), obtained_at", memberId));
        out.put("seasonDelta", seasonDelta);
        return out;
    }

    public List<Map<String, Object>> entries(long memberId, String attr, Long cursor, Integer limit) {
        CityGameSupport.requireMember(memberId);
        int n = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        StringBuilder sql = new StringBuilder("SELECT id, attr_key AS attrKey, raw, eff, source, source_id AS sourceId, day, created_at AS createdAt "
                + "FROM member_attribute_ledger WHERE member_id = ?");
        List<Object> args = new ArrayList<>(List.of(memberId));
        if (attr != null && !attr.isEmpty()) {
            if (!AttributeRules.KEYS.contains(attr)) throw new CityGameException(400, "ATTR_INVALID", "未知属性");
            sql.append(" AND attr_key = ?");
            args.add(attr);
        }
        if (cursor != null && cursor > 0) { sql.append(" AND id < ?"); args.add(cursor); }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(n);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** Equip one owned title (owner check in WHERE); null code unequips. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> equip(long memberId, String titleCode) {
        CityGameSupport.requireMember(memberId);
        if (titleCode != null && !titleCode.isEmpty()) {
            Integer owned = jdbc.queryForObject("SELECT COUNT(*) FROM member_title WHERE member_id = ? AND title_code = ?", Integer.class, memberId, titleCode);
            if (owned == null || owned == 0) throw new CityGameException(404, "TITLE_NOT_OWNED", "尚未获得该称号");
        }
        jdbc.update("UPDATE member_title SET equipped = 0 WHERE member_id = ? AND equipped = 1", memberId);
        if (titleCode != null && !titleCode.isEmpty()) {
            jdbc.update("UPDATE member_title SET equipped = 1 WHERE member_id = ? AND title_code = ?", memberId, titleCode);
        }
        return Map.of("equippedTitle", titleCode == null ? "" : titleCode);
    }

    /** Recompute and store the archetype; returns true when it changed (the caller pushes once). */
    @Transactional(rollbackFor = Exception.class)
    public boolean recomputeArchetype(long memberId) {
        AttributeRules.Archetype a = AttributeRules.archetype(configs.global(), levels(memberId));
        List<Map<String, Object>> prev = jdbc.queryForList("SELECT code, name FROM member_archetype WHERE member_id = ?", memberId);
        boolean changed = prev.isEmpty() || !a.name().equals(prev.get(0).get("name"));
        jdbc.update("INSERT INTO member_archetype(member_id, primary_key, secondary_key, code, name, computed_at) VALUES (?,?,?,?,?,NOW(3)) "
                        + "ON DUPLICATE KEY UPDATE primary_key = VALUES(primary_key), secondary_key = VALUES(secondary_key), code = VALUES(code), name = VALUES(name), computed_at = NOW(3)",
                memberId, a.primary(), a.secondary(), a.code(), a.name());
        return changed;
    }

    /** Theme attribute initial value with the (default OFF) imprint applied; reads cms_topic.persona_imprint. */
    public int imprintInitial(long memberId, Long topicId, String preset, int baseInitial, int presetMax) {
        Boolean topicFlag = null;
        if (topicId != null) {
            try {
                List<Map<String, Object>> r = jdbc.queryForList("SELECT persona_imprint FROM cms_topic WHERE id = ?", topicId);
                if (!r.isEmpty() && r.get(0).get("persona_imprint") instanceof Number) {
                    topicFlag = ((Number) r.get(0).get("persona_imprint")).intValue() == 1;
                }
            } catch (RuntimeException e) {
                topicFlag = false;
            }
        }
        return AttributeRules.imprintInitial(configs.global(), topicFlag, preset, baseInitial, presetMax, levels(memberId));
    }

    /** One-time launch backfill per member/attribute (idempotent via source BACKFILL). */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Long> backfill(long memberId, AttributeRules.BackfillFacts facts) {
        CityMonopolyConfig c = configs.global();
        Map<String, Long> amounts = AttributeRules.backfill(c, facts);
        Map<String, Long> applied = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : amounts.entrySet()) {
            if (e.getValue() <= 0) continue;
            jdbc.update("INSERT IGNORE INTO member_attribute(member_id, attr_key, axp, level) VALUES (?,?,0,1)", memberId, e.getKey());
            try {
                jdbc.update("INSERT INTO member_attribute_ledger(member_id, attr_key, raw, eff, source, source_id, day) VALUES (?,?,?,?, 'BACKFILL', 'launch', ?)",
                        memberId, e.getKey(), e.getValue(), e.getValue(), Date.valueOf(today(c)));
            } catch (DuplicateKeyException replay) {
                continue;
            }
            Long axp = jdbc.queryForObject("SELECT axp FROM member_attribute WHERE member_id = ? AND attr_key = ? FOR UPDATE", Long.class, memberId, e.getKey());
            long next = (axp == null ? 0 : axp) + e.getValue();
            int level = AttributeRules.levelFor(c, next);
            jdbc.update("UPDATE member_attribute SET axp = ?, level = ?, backfilled = 1 WHERE member_id = ? AND attr_key = ?", next, level, memberId, e.getKey());
            awardTitles(c, memberId, e.getKey(), level);
            applied.put(e.getKey(), e.getValue());
        }
        return applied;
    }
}

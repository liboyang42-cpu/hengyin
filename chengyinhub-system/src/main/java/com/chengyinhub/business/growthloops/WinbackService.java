package com.chengyinhub.business.growthloops;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.growthloops.GrowthLoopsSettings.Mode;
import com.chengyinhub.business.growthloops.WinbackPolicy.Trigger;
import com.chengyinhub.business.mapper.growthloops.GrowthLoopsMapper;
import com.chengyinhub.business.push.MemberNotice;
import com.chengyinhub.business.service.IPushFanoutService;

/**
 * GRO-09 召回任务：连续天数快断了 / 走过的地方有新地点 / 附近有俱乐部活动。
 * 通过 links-push 的推送扇出发 APNs；频率、没回应、免打扰、退订见 {@link WinbackPolicy}。
 * 定时任务 {@code growthWinbackTask.run()}（sys_job，每小时）。模式 OFF 时整轮跳过。
 */
@Service
public class WinbackService
{
    private static final Logger log = LoggerFactory.getLogger(WinbackService.class);
    static final int MIN_STREAK = 3;
    static final int LAPSED_WINDOW_DAYS = 90;
    static final int NEW_PLACE_DAYS = 14;
    static final int EVENT_DAYS = 7;
    static final long HOUR_MS = 3600L * 1000L;

    private final GrowthLoopsMapper mapper;
    private final GrowthLoopsSettings settings;

    @Autowired(required = false)
    private IPushFanoutService push;

    @Autowired
    public WinbackService(GrowthLoopsMapper mapper, GrowthLoopsSettings settings)
    {
        this.mapper = mapper;
        this.settings = settings;
    }

    WinbackService(GrowthLoopsMapper mapper, GrowthLoopsSettings settings, IPushFanoutService push)
    {
        this(mapper, settings);
        this.push = push;
    }

    /** 一轮的结果，任务日志用。 */
    public record Summary(String mode, int resolved, int evaluated, int sent, int shadow, int skipped, String note) {}

    public Summary runDue(Date now)
    {
        Date clock = now == null ? new Date() : now;
        Mode mode = settings.winbackMode();
        if (mode == Mode.OFF) return new Summary("OFF", 0, 0, 0, 0, 0, "growth.winback.mode=OFF");
        if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode())
            return new Summary(mode.name(), 0, 0, 0, 0, 0, "restore mode");
        if (mode == Mode.ON && (push == null || !push.apnsConfigured()))
            return new Summary("ON", 0, 0, 0, 0, 0, "APNs not configured");
        ZonedDateTime local = clock.toInstant().atZone(settings.zone());
        if (WinbackPolicy.inQuiet(local.toLocalTime(), settings.quietStart(), settings.quietEnd()))
            return new Summary(mode.name(), 0, 0, 0, 0, 0, "quiet hours");

        int gap = settings.minGapDays();
        int maxIgnored = settings.maxIgnored();
        int resolved = resolvePending(clock, gap);

        Date sentBefore = new Date(clock.getTime() - gap * WinbackPolicy.DAY_MS);
        Date evalBefore = new Date(clock.getTime() - 20L * HOUR_MS);
        int batch = settings.batch();
        Map<Long, Integer> streaks = new LinkedHashMap<>();
        if (local.getHour() >= settings.streakFromHour()) {
            LocalDate today = local.toLocalDate();
            Date todayStart = Date.from(today.atStartOfDay(settings.zone()).toInstant());
            Date yesterdayStart = Date.from(today.minusDays(1).atStartOfDay(settings.zone()).toInstant());
            for (Map<String, Object> row : nz(mapper.selectStreakAtRisk(yesterdayStart, todayStart, MIN_STREAK, sentBefore,
                    maxIgnored, evalBefore, batch)))
                streaks.put(number(row.get("memberId")), (int) number(row.get("streak")));
        }
        Map<Long, Date> lapsed = new LinkedHashMap<>();
        Date lapsedBefore = new Date(clock.getTime() - settings.lapsedDays() * WinbackPolicy.DAY_MS);
        Date lapsedAfter = new Date(clock.getTime() - LAPSED_WINDOW_DAYS * WinbackPolicy.DAY_MS);
        for (Map<String, Object> row : nz(mapper.selectLapsed(lapsedBefore, lapsedAfter, sentBefore, maxIgnored, evalBefore, batch)))
            lapsed.put(number(row.get("memberId")), row.get("lastActive") instanceof Date d ? d : null);

        Context context = lapsed.isEmpty() ? null : new Context(
                nz(mapper.selectNewPois(new Date(clock.getTime() - NEW_PLACE_DAYS * WinbackPolicy.DAY_MS), 500)),
                nz(mapper.selectUpcomingActivities(clock, new Date(clock.getTime() + EVENT_DAYS * WinbackPolicy.DAY_MS), 500)));

        int evaluated = 0, sent = 0, shadow = 0, skipped = 0;
        Set<Long> members = new java.util.LinkedHashSet<>(streaks.keySet());
        members.addAll(lapsed.keySet());
        for (Long member : members) {
            evaluated++;
            try {
                Candidate pick = pick(member, streaks.get(member), lapsed.containsKey(member), lapsed.get(member), context, clock);
                if (pick == null) { mapper.touchEvaluated(member, clock); skipped++; continue; }
                if (mode == Mode.SHADOW) {
                    mapper.touchEvaluated(member, clock);
                    mapper.insertLog(member, pick.trigger.name(), pick.ref, "SHADOW", 0, clock);
                    shadow++;
                    continue;
                }
                if (send(member, pick, clock, sentBefore, maxIgnored)) sent++; else skipped++;
            } catch (RuntimeException failure) {
                skipped++;
                log.warn("event=WINBACK_MEMBER_SKIPPED member={} reason={}", member, failure.getClass().getSimpleName());
            }
        }
        return new Summary(mode.name(), resolved, evaluated, sent, shadow, skipped, null);
    }

    record Candidate(Trigger trigger, String ref, MemberNotice notice) {}

    record Context(List<Map<String, Object>> pois, List<Map<String, Object>> activities) {}

    Candidate pick(long member, Integer streak, boolean isLapsed, Date lastActive, Context context, Date now)
    {
        Candidate event = null, places = null;
        if (isLapsed && context != null) {
            Set<String> explored = new HashSet<>(nz(mapper.selectExploredPrefixes(member, 400)));
            event = eventFor(member, explored, context.activities());
            places = placesFor(explored, lastActive, context.pois());
        }
        Trigger t = WinbackPolicy.choose(streak != null && streak >= MIN_STREAK, event != null, places != null);
        if (t == null) return null;
        return switch (t) {
            case STREAK_AT_RISK -> new Candidate(t, "streak:" + streak, WinbackPolicy.streakNotice(streak));
            case CLUB_EVENT -> event;
            case NEW_PLACES -> places;
        };
    }

    private Candidate eventFor(long member, Set<String> explored, List<Map<String, Object>> activities)
    {
        if (activities.isEmpty()) return null;
        Set<Long> clubs = new HashSet<>(nz(mapper.selectMemberClubIds(member)));
        for (Map<String, Object> a : activities) {
            Object clubId = a.get("clubId");
            boolean myClub = clubId instanceof Number n && n.longValue() > 0 && clubs.contains(n.longValue());
            String cell = GeoCell.encode(a.get("lat"), a.get("lng"), 5);
            boolean near = cell != null && explored.contains(cell);
            if (!myClub && !near) continue;
            long id = number(a.get("id"));
            String when = a.get("startDate") instanceof Date d ? new SimpleDateFormat("MM-dd HH:mm").format(d) : null;
            return new Candidate(Trigger.CLUB_EVENT, "activity:" + id,
                    WinbackPolicy.clubEventNotice(id, a.get("name") == null ? "" : a.get("name").toString(), when));
        }
        return null;
    }

    private Candidate placesFor(Set<String> explored, Date lastActive, List<Map<String, Object>> pois)
    {
        if (explored.isEmpty() || pois.isEmpty()) return null;
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> p : pois) {
            if (lastActive != null && p.get("createTime") instanceof Date created && !created.after(lastActive)) continue;
            String cell = GeoCell.encode(p.get("lat"), p.get("lng"), 5);
            if (cell != null && explored.contains(cell)) hits.add(p);
        }
        if (hits.isEmpty()) return null;
        Map<String, Object> first = hits.get(0);
        long id = number(first.get("id"));
        return new Candidate(Trigger.NEW_PLACES, "poi:" + id,
                WinbackPolicy.newPlacesNotice(id, first.get("name") == null ? "" : first.get("name").toString(), hits.size()));
    }

    private boolean send(long member, Candidate pick, Date now, Date sentBefore, int maxIgnored)
    {
        mapper.ensureState(member);
        Map<String, Object> before = mapper.selectState(member);
        if (mapper.claimSend(member, now, sentBefore, maxIgnored, pick.trigger.name(), pick.ref) != 1) return false;
        int accepted = 0;
        try {
            accepted = push.notifyMemberNow(member, pick.notice);
        } finally {
            if (accepted <= 0) {
                Date prevSent = before == null || !(before.get("lastSent") instanceof Date d) ? null : d;
                int prevAwaiting = before == null ? 0 : (int) number(before.get("awaiting"));
                mapper.releaseSend(member, now, prevSent, prevAwaiting);
            }
        }
        mapper.insertLog(member, pick.trigger.name(), pick.ref, accepted > 0 ? "SENT" : "NOT_DELIVERED", accepted, now);
        return accepted > 0;
    }

    int resolvePending(Date now, int gap)
    {
        int changed = 0;
        for (Map<String, Object> row : nz(mapper.selectPendingResolution(500))) {
            long member = number(row.get("memberId"));
            boolean awaiting = number(row.get("awaiting")) == 1;
            int ignored = (int) number(row.get("ignored"));
            WinbackPolicy.Resolution r = WinbackPolicy.resolve(awaiting, ignored,
                    row.get("lastSent") instanceof Date s ? s : null, row.get("lastActive") instanceof Date a ? a : null, now, gap);
            if (r.awaiting() != awaiting || r.ignored() != ignored) {
                mapper.updateResolution(member, r.awaiting() ? 1 : 0, r.ignored());
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------ player preference

    /** 玩家自己的召回提醒设置。 */
    public Map<String, Object> preference(long member)
    {
        Map<String, Object> state = mapper.selectState(member);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", state == null || number(state.get("optedOut")) != 1);
        out.put("serviceMode", settings.winbackMode().name());
        out.put("pushConfigured", push != null && push.apnsConfigured());
        out.put("maxPerWeek", 1);
        out.put("lastSentAt", state != null && state.get("lastSent") instanceof Date d ? d.getTime() : null);
        return out;
    }

    public Map<String, Object> setEnabled(long member, boolean enabled)
    {
        mapper.upsertOptOut(member, enabled ? 0 : 1, new Date());
        return preference(member);
    }

    private static <T> List<T> nz(List<T> list) { return list == null ? List.of() : list; }

    static long number(Object value)
    {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof Boolean b) return b ? 1 : 0;
        try { return value == null ? 0 : Long.parseLong(value.toString().trim()); }
        catch (NumberFormatException invalid) { return 0; }
    }
}

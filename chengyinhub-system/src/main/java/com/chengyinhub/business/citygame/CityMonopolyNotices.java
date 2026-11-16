package com.chengyinhub.business.citygame;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.chengyinhub.business.growthloops.ICityEventNotifyService;

/**
 * W7 CITY-11: tells holders what happened to their City Monopoly places (takeover, defence, decay, release, season).
 *
 * <p>Never notifies the member who acted. At most one push per member and place per 6 hours (season results are
 * per member and not throttled; {@code settleSeason} sends each once). The push itself is sent after commit by the
 * notifier and never fails the caller.</p>
 */
final class CityMonopolyNotices {
    static final long THROTTLE_MS = 6L * 3_600_000L;
    private static final int MAX_KEYS = 100_000;

    private volatile ICityEventNotifyService notify;
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();

    void setNotify(ICityEventNotifyService notify) { this.notify = notify; }

    static String ref(String territoryId) { return "terr:" + (territoryId == null ? "" : territoryId); }

    void lost(long holder, long actor, String placeKey, String territoryId, String title, long now, long revengeHours, double revengeRate) {
        place(holder, actor, placeKey, now, n -> n.monopolyLost(holder, ref(territoryId), title, revengeHours, revengeRate));
    }

    void defended(long holder, long actor, String placeKey, String territoryId, String title, long now, int total, int dc) {
        place(holder, actor, placeKey, now, n -> n.defended(holder, ref(territoryId), title, total, dc));
    }

    void decaying(long holder, String placeKey, String territoryId, String title, long now, long days) {
        place(holder, 0L, placeKey, now, n -> n.decaying(holder, ref(territoryId), title, days));
    }

    void released(long holder, String placeKey, String territoryId, String title, long now) {
        place(holder, 0L, placeKey, now, n -> n.released(holder, ref(territoryId), title));
    }

    void seasonResult(long member, String seasonId, int rank, int participants, int xp) {
        ICityEventNotifyService n = notify;
        if (n == null || member <= 0) return;
        try { n.seasonResult(member, seasonId, rank, participants, xp); }
        catch (RuntimeException ignored) { /* a push never changes the season result */ }
    }

    private void place(long holder, long actor, String placeKey, long now, Consumer<ICityEventNotifyService> call) {
        ICityEventNotifyService n = notify;
        if (n == null || holder <= 0 || holder == actor) return;
        String key = holder + "|" + (placeKey == null ? "" : placeKey);
        Long last = lastSent.get(key);
        if (last != null && now - last < THROTTLE_MS) return;
        if (lastSent.size() > MAX_KEYS) lastSent.clear();
        lastSent.put(key, now);
        try { call.accept(n); }
        catch (RuntimeException ignored) { /* a push never changes a territory outcome */ }
    }
}

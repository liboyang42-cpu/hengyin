package com.chengyinhub.business.appgaps;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * App {@code /api/club/activity/join|cancel} 只发 {@code {activityId}}，候补却按票种排队。这里把票种补出来，规则只取唯一解：
 * 加入 = 该场次唯一的票种，或唯一一个已售罄（可候补）的票种；取消 = 本人在该场次唯一一条进行中的候补。
 * 有多个候选时不猜，回 400 {@code TICKET_REQUIRED} 并列出候选 ticketIds；其余判定（库存、截止、成员资格）仍由候补服务做。
 */
@Service
public class ClubWaitlistTickets {
    private final JdbcTemplate jdbc;

    public ClubWaitlistTickets(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long forJoin(long activityId) {
        List<Map<String, Object>> tickets = jdbc.queryForList("SELECT id, remaining_inventory AS remaining FROM oms_ticket"
                + " WHERE owner_type = 2 AND owner_id = ? AND del_flag = 0 ORDER BY id", activityId);
        if (tickets.isEmpty()) throw AppGapException.notFound("TICKET_NOT_FOUND");
        if (tickets.size() == 1) return id(tickets.get(0));
        List<Long> soldOut = new ArrayList<>();
        for (Map<String, Object> t : tickets) {
            Object remaining = t.get("remaining");
            if (remaining instanceof Number && ((Number) remaining).longValue() == 0L) soldOut.add(id(t));
        }
        if (soldOut.size() == 1) return soldOut.get(0);
        List<Long> all = new ArrayList<>();
        for (Map<String, Object> t : tickets) all.add(id(t));
        throw required(soldOut.isEmpty() ? all : soldOut);
    }

    public long forCancel(long memberId, long activityId) {
        List<Long> active = jdbc.queryForList("SELECT DISTINCT ticket_id FROM club_event_waitlist WHERE member_id = ? AND activity_id = ?"
                + " AND state IN ('REQUESTED','WAITING','OFFERED') ORDER BY ticket_id", Long.class, memberId, activityId);
        if (active.size() == 1) return active.get(0);
        if (active.isEmpty()) return forJoin(activityId);
        throw required(active);
    }

    private static AppGapException required(List<Long> candidates) {
        return new AppGapException(400, "TICKET_REQUIRED", "该场次有多个票种，请指定票种", Map.of("ticketIds", candidates));
    }

    private static long id(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }
}

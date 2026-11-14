package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.SupportTicketReadMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * App 客服工单只读：/api/support/tickets/list（cursor=上一页最小 id，0 为首页）与 /detail（消息 cursor=已读最大 id；
 * 带 event_cursor 时附事件时间线，倒序）。只读本人工单；内部备注不出库；时间按 Asia/Shanghai 本地时间字符串。
 */
@Service
public class SupportTicketReads {
    static final int TICKETS = 20, MESSAGES = 50, EVENTS = 50;
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final SupportTicketReadMapper mapper;

    public SupportTicketReads(SupportTicketReadMapper mapper) { this.mapper = mapper; }

    static long cursor(Object value) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0) throw new ServiceException("cursor 无效", 400);
        return ((Number) value).longValue();
    }

    static String ticketNo(Object value) {
        if (!(value instanceof String s) || !s.startsWith("CS") || s.length() <= 2 || s.length() > 24 || !s.matches("[0-9A-Za-z_-]+"))
            throw new ServiceException("工单号无效", 400);
        return s;
    }

    public Map<String, Object> list(long member, Map<String, Object> body) {
        if (body == null || !body.keySet().equals(Set.of("cursor"))) throw new ServiceException("参数无效", 400);
        long before = cursor(body.get("cursor"));
        List<Map<String, Object>> rows = mapper.selectTickets(member, before, TICKETS + 1);
        boolean more = rows.size() > TICKETS;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), TICKETS))) list.add(ticket(row, member));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list); out.put("has_more", more);
        out.put("next_cursor", more ? ((Number) rows.get(TICKETS - 1).get("id")).longValue() : 0L);
        return out;
    }

    public Map<String, Object> detail(long member, Map<String, Object> body) {
        if (body == null) throw new ServiceException("参数无效", 400);
        boolean timeline = body.containsKey("event_cursor");
        if (!body.keySet().equals(timeline ? Set.of("ticket_no", "cursor", "event_cursor") : Set.of("ticket_no", "cursor")))
            throw new ServiceException("参数无效", 400);
        String no = ticketNo(body.get("ticket_no"));
        long after = cursor(body.get("cursor"));
        long eventBefore = timeline ? cursor(body.get("event_cursor")) : 0;
        if (timeline && after != 0) throw new ServiceException("时间线读取时 cursor 必须为 0", 400);
        Map<String, Object> row = mapper.selectTicket(member, no);
        if (row == null || row.isEmpty()) throw new ServiceException("工单不存在", 404);
        long ticketId = ((Number) row.get("id")).longValue();
        List<Map<String, Object>> messages = mapper.selectMessages(ticketId, after, MESSAGES + 1);
        boolean more = messages.size() > MESSAGES;
        List<Map<String, Object>> page = new ArrayList<>();
        for (Map<String, Object> m : messages.subList(0, Math.min(messages.size(), MESSAGES))) page.add(message(m));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticket", ticket(row, member)); out.put("messages", page); out.put("has_more", more);
        out.put("next_cursor", page.isEmpty() ? after : (Long) page.get(page.size() - 1).get("id"));
        out.put("business_result", "READ");
        if (timeline) {
            List<Map<String, Object>> events = mapper.selectEvents(ticketId, eventBefore, EVENTS + 1);
            boolean moreEvents = events.size() > EVENTS;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> e : events.subList(0, Math.min(events.size(), EVENTS))) rows.add(event(e));
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("resource_type", "SUPPORT_TICKET"); t.put("ticket_no", no); t.put("audience", "MEMBER"); t.put("source", "support_ticket_event");
            t.put("events", rows); t.put("has_more", moreEvents);
            t.put("next_before", moreEvents ? (Long) rows.get(rows.size() - 1).get("id") : 0L);
            out.put("timeline", t);
        } else out.put("timeline", null);
        return out;
    }

    static Map<String, Object> ticket(Map<String, Object> r, long member) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("ticket_no", r.get("ticket_no")); t.put("requester_role", r.get("requester_role")); t.put("category", r.get("category"));
        t.put("subject", r.get("subject")); t.put("status", r.get("status")); t.put("version", ((Number) r.get("version")).longValue());
        t.put("timezone", r.get("timezone")); t.put("locale", r.get("locale")); t.put("sla_state", r.get("sla_state"));
        t.put("sla_first_due_at", time(r.get("sla_first_due_at"))); t.put("sla_resolve_due_at", time(r.get("sla_resolve_due_at")));
        t.put("create_time", time(r.get("create_time"))); t.put("resolved_at", time(r.get("resolved_at"))); t.put("closed_at", time(r.get("closed_at")));
        Map<String, Object> ref = null;
        if (r.get("related_domain_type") != null && r.get("related_resource_id") instanceof Number id) {
            ref = new LinkedHashMap<>();
            ref.put("source", r.get("related_source")); ref.put("domain_type", r.get("related_domain_type"));
            ref.put("resource_id", String.valueOf(id.longValue())); ref.put("owner_type", "MEMBER"); ref.put("owner_member_id", String.valueOf(member));
        }
        t.put("related_reference", ref);
        return t;
    }

    static Map<String, Object> message(Map<String, Object> m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", ((Number) m.get("id")).longValue()); o.put("sender_type", m.get("sender_type")); o.put("content", m.get("content"));
        o.put("content_type", m.get("content_type")); o.put("create_time", time(m.get("create_time")));
        List<Long> attachments = new ArrayList<>();
        try {
            for (Object a : AppOriginalRequestLedger.JSON.readValue(String.valueOf(m.get("attachments_json")), List.class))
                if (a instanceof Number n && n.longValue() > 0 && !attachments.contains(n.longValue())) attachments.add(n.longValue());
        } catch (Exception ignored) { /* 附件列坏数据按无附件下发，不把非法 id 透给客户端 */ }
        o.put("attachments", attachments);
        return o;
    }

    static Map<String, Object> event(Map<String, Object> e) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", ((Number) e.get("id")).longValue()); o.put("event_type", e.get("event_type")); o.put("actor_type", e.get("actor_type"));
        o.put("created_at", time(e.get("created_at"))); o.put("result_state", e.get("result_state"));
        if (e.get("historical_status") != null) o.put("historical_status", e.get("historical_status"));
        if (e.get("historical_sla_state") != null) o.put("historical_sla_state", e.get("historical_sla_state"));
        if (e.get("reason") != null) o.put("reason", e.get("reason"));
        if (e.get("request_id") != null && "MEMBER".equals(e.get("actor_type"))) o.put("request_id", e.get("request_id"));
        if (e.get("historical_version") instanceof Number v && v.longValue() > 0) o.put("historical_version", v.longValue());
        if (e.get("message_id") instanceof Number m && m.longValue() > 0) o.put("message_id", m.longValue());
        return o;
    }

    static String time(Object o) {
        if (o instanceof LocalDateTime t) return TIME.format(t);
        if (o instanceof Date d) return TIME.format(LocalDateTime.ofInstant(d.toInstant(), java.time.ZoneId.of("Asia/Shanghai")));
        return null;
    }
}

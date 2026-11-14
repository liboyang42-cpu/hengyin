package com.chengyinhub.business.appgaps;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 俱乐部代报名（App {@code POST /api/club/events/proxy-enroll}）。
 *
 * <p>管理者替本俱乐部的在籍成员占一个「待本人完成报名」的位置。这里<b>不</b>建报名单、不动钱、不收实名/手机号：
 * 报名单的单独同意（{@code hasCurrentSignupDataSharingConsent}）与付款只能由成员本人在
 * {@code /api/registration/*} 完成。状态由事实推导：成员已有该场次的有效报名（registration_status = 2）即 REGISTERED，
 * 否则 INVITED。权限（{@code club:activity:manage}）由调用方先按俱乐部访问上下文判过。
 */
@Service
public class ClubProxyEnrollService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.of("Asia/Shanghai"));
    static final int MINE_LIMIT = 50;

    /** DATETIME 一律按 Timestamp 读：驱动写入时按会话时区换算，读回 LocalDateTime 却不换算，两边会差一个时区。 */
    private static final ColumnMapRowMapper ROWS = new ColumnMapRowMapper() {
        @Override
        protected Object getColumnValue(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
            Object value = super.getColumnValue(rs, index);
            return value instanceof java.time.LocalDateTime ? rs.getTimestamp(index) : value;
        }
    };

    private final JdbcTemplate jdbc;
    private final LongSupplier clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ClubProxyEnrollService(JdbcTemplate jdbc) {
        this(jdbc, System::currentTimeMillis);
    }

    ClubProxyEnrollService(JdbcTemplate jdbc, LongSupplier clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 记一条代报名邀请；同一场次同一成员重复提交读回原行（replayed = true）。 */
    public Map<String, Object> propose(Long actor, Long clubId, Long activityId, Long memberId) {
        long me = requireMember(actor);
        long club = requireId(clubId, "clubId");
        long activity = requireId(activityId, "activityId");
        long target = requireId(memberId, "memberId");
        List<Map<String, Object>> clubs = jdbc.queryForList(
                "SELECT member_id AS ownerId FROM club WHERE id = ? AND status = 1 AND del_flag = 0", club);
        if (clubs.isEmpty()) throw AppGapException.notFound("CLUB_NOT_FOUND");
        long owner = ((Number) clubs.get(0).get("ownerId")).longValue();
        List<Map<String, Object>> activities = jdbc.query(
                "SELECT status, publish_status AS publishStatus, cancel_time AS cancelTime, end_date AS endDate"
                        + " FROM cms_activity WHERE id = ? AND club_id = ? AND del_flag = 0", ROWS, activity, club);
        if (activities.isEmpty()) throw AppGapException.notFound("ACTIVITY_NOT_FOUND");
        if (!open(activities.get(0))) throw AppGapException.conflict("ACTIVITY_NOT_OPEN", "该场次已结束或未开放报名");
        if (target != owner && jdbc.queryForObject("SELECT COUNT(*) FROM club_member WHERE club_id = ? AND member_id = ?"
                + " AND status = 1 AND del_flag = 0", Integer.class, club, target) == 0) {
            throw AppGapException.conflict("PROXY_ENROLL_NOT_MEMBER", "只能替本俱乐部的在籍成员代报名");
        }
        if (registered(target, activity)) {
            throw AppGapException.conflict("PROXY_ENROLL_ALREADY_REGISTERED", "该成员已报名这一场");
        }
        boolean replayed = false;
        try {
            jdbc.update("INSERT INTO club_proxy_enroll(club_id, activity_id, member_id, proposer_member_id, created_at) VALUES (?,?,?,?,?)",
                    club, activity, target, me, new Timestamp(clock.getAsLong()));
        } catch (DuplicateKeyException exists) {
            replayed = true;
        }
        Map<String, Object> row = jdbc.queryForObject("SELECT id, club_id AS clubId, activity_id AS activityId, member_id AS memberId,"
                + " proposer_member_id AS proposerMemberId, created_at AS createdAt FROM club_proxy_enroll WHERE activity_id = ? AND member_id = ?",
                ROWS, activity, target);
        Map<String, Object> out = item(row, false);
        out.put("replayed", replayed);
        return out;
    }

    /** 成员本人收到的代报名邀请（最新在前），用来引导本人去报名。 */
    public Map<String, Object> mine(Long viewer) {
        long me = requireMember(viewer);
        List<Map<String, Object>> rows = jdbc.query("SELECT id, club_id AS clubId, activity_id AS activityId, member_id AS memberId,"
                + " proposer_member_id AS proposerMemberId, created_at AS createdAt FROM club_proxy_enroll WHERE member_id = ?"
                + " ORDER BY id DESC LIMIT ?", ROWS, me, MINE_LIMIT);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) items.add(item(row, true));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    private Map<String, Object> item(Map<String, Object> row, boolean withProposer) {
        long member = ((Number) row.get("memberId")).longValue();
        long activity = ((Number) row.get("activityId")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", ((Number) row.get("id")).longValue());
        out.put("clubId", ((Number) row.get("clubId")).longValue());
        out.put("activityId", activity);
        out.put("memberId", member);
        if (withProposer) out.put("proposerMemberId", ((Number) row.get("proposerMemberId")).longValue());
        out.put("state", registered(member, activity) ? "REGISTERED" : "INVITED");
        out.put("registrationRequired", true);
        out.put("createdAt", iso(row.get("createdAt")));
        return out;
    }

    private boolean registered(long member, long activity) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cms_registration WHERE member_id = ? AND owner_type = 2 AND owner_id = ?"
                + " AND registration_status = 2", Integer.class, member, activity) > 0;
    }

    private boolean open(Map<String, Object> activity) {
        if (!one(activity.get("status")) || !one(activity.get("publishStatus")) || activity.get("cancelTime") != null) return false;
        Object end = activity.get("endDate");
        return end == null || millis(end) > clock.getAsLong();
    }

    private static boolean one(Object value) {
        return value instanceof Number && ((Number) value).longValue() == 1L;
    }

    private static long requireMember(Long member) {
        if (member == null || member <= 0) throw AppGapException.unauthorized();
        return member;
    }

    private static long requireId(Long value, String field) {
        if (value == null || value <= 0) throw AppGapException.badRequest(field);
        return value;
    }

    private static String iso(Object value) {
        return value == null ? null : ISO.format(Instant.ofEpochMilli(millis(value)));
    }

    private static long millis(Object value) {
        return ((java.util.Date) value).getTime();
    }
}

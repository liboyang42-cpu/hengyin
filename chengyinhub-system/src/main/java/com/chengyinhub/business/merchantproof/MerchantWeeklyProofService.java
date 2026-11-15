package com.chengyinhub.business.merchantproof;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantgap.MerchantGapSupport;
import com.chengyinhub.business.merchantproof.MerchantWeeklyProof.Kind;
import com.chengyinhub.business.merchantproof.MerchantWeeklyProof.Touch;

/**
 * Q1 merchant-reach (/api/merchant/proof/weekly): reads committed rows only, writes nothing, moves no money.
 * Sources, all scoped to the signed-in member's own shop:
 * <ul>
 *   <li>ticket redemptions: {@code redemption} (ACTIVE) by the shop owner, player from {@code cms_registration};</li>
 *   <li>coupon redemptions: {@code sms_coupon_history} used, on a coupon the shop owner published;</li>
 *   <li>map-node redemptions: {@code business_audit_event} CITY_NODE_REDEEM owned by the shop;</li>
 *   <li>node check-ins: {@code roam_poi_reward_log} at the shop's {@code roam_poi} rows;</li>
 *   <li>CITY board entries: {@code city_value_visit} at {@code city_game_point} rows the shop operates.</li>
 * </ul>
 */
@Service
public class MerchantWeeklyProofService {
    private final JdbcTemplate jdbc;
    private final IMerchantAccessService access;

    public MerchantWeeklyProofService(JdbcTemplate jdbc, IMerchantAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public Map<String, Object> weekly(Long principal, Map<String, Object> body) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.BASIC_READ);
        LocalDate weekStart = weekStart(body, LocalDate.now(MerchantWeeklyProof.ZONE));
        Instant start = MerchantWeeklyProof.startOf(weekStart);
        Instant end = MerchantWeeklyProof.startOf(weekStart.plusDays(7));
        Instant lookback = MerchantWeeklyProof.startOf(weekStart.minusDays(MerchantWeeklyProof.LOOKBACK_DAYS));
        long owner = ctx.getOwnerMemberId();
        long merchant = ctx.getMerchantId();
        List<Touch> week = touches(owner, merchant, start, end);
        Set<Long> earlier = new HashSet<>();
        for (Touch t : touches(owner, merchant, lookback, start)) earlier.add(t.memberId());
        Map<String, Object> out = MerchantWeeklyProof.compute(weekStart, week, earlier, hasNode(merchant));
        out.put("merchantId", merchant);
        return out;
    }

    /** {@code weekStart} is optional ("yyyy-MM-dd", any day of the week); missing means this week. No future weeks. */
    static LocalDate weekStart(Map<String, Object> body, LocalDate today) {
        if (body != null) {
            for (String key : body.keySet()) {
                if (!"weekStart".equals(key)) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
            }
        }
        Object raw = body == null ? null : body.get("weekStart");
        LocalDate day = today;
        if (raw != null) {
            String text = String.valueOf(raw).trim();
            if (!text.matches("\\d{4}-\\d{2}-\\d{2}")) throw new MerchantGapException(400, "INVALID_WEEK", "周起始日期无效");
            try {
                day = LocalDate.parse(text);
            } catch (DateTimeParseException e) {
                throw new MerchantGapException(400, "INVALID_WEEK", "周起始日期无效");
            }
            if (day.isAfter(today)) throw new MerchantGapException(400, "INVALID_WEEK", "不能查询未来的周");
            if (day.isBefore(today.minusDays(366))) throw new MerchantGapException(400, "INVALID_WEEK", "最多查询一年内的周");
        }
        return MerchantWeeklyProof.weekStart(day);
    }

    boolean hasNode(long merchant) {
        Integer pois = jdbc.queryForObject("SELECT COUNT(*) FROM roam_poi WHERE merchant_id = ?", Integer.class, merchant);
        if (pois != null && pois > 0) return true;
        Integer points = jdbc.queryForObject("SELECT COUNT(*) FROM city_game_point WHERE merchant_id = ? AND del_flag = 0", Integer.class, merchant);
        return points != null && points > 0;
    }

    /** Also read by S3a visit billing, so a billed visit is exactly a verified visit in the proof. */
    public List<Touch> touches(long owner, long merchant, Instant from, Instant to) {
        Timestamp a = Timestamp.from(from), b = Timestamp.from(to);
        List<Touch> out = new ArrayList<>();
        collect(out, Kind.TICKET_REDEMPTION, "SELECT reg.member_id AS member_id, r.create_time AS at FROM redemption r"
                + " JOIN cms_registration reg ON reg.id = r.registration_id"
                + " WHERE r.merchant_id = ? AND r.status = 'ACTIVE' AND r.create_time >= ? AND r.create_time < ?", owner, a, b);
        collect(out, Kind.COUPON_REDEMPTION, "SELECT h.member_id AS member_id, h.use_time AS at FROM sms_coupon_history h"
                + " JOIN sms_coupon c ON c.id = h.coupon_id"
                + " WHERE c.member_id = ? AND h.use_status = 1 AND h.use_time >= ? AND h.use_time < ?", owner, a, b);
        collect(out, Kind.NODE_REDEMPTION, "SELECT relation_id AS member_id, create_time AS at FROM business_audit_event"
                + " WHERE domain_type = 'CITY_NODE' AND action = 'CITY_NODE_REDEEM' AND owner_member_id = ?"
                + " AND create_time >= ? AND create_time < ?", owner, a, b);
        collect(out, Kind.NODE_CHECKIN, "SELECT l.member_id AS member_id, l.create_time AS at FROM roam_poi_reward_log l"
                + " JOIN roam_poi p ON p.id = l.poi_id"
                + " WHERE p.merchant_id = ? AND l.create_time >= ? AND l.create_time < ?", merchant, a, b);
        collect(out, Kind.CITY_POINT_VISIT, "SELECT v.member_id AS member_id, v.entered_at AS at FROM city_value_visit v"
                + " JOIN city_game_point pt ON pt.board_id = v.board_id AND pt.point_id = v.point_id"
                + " WHERE pt.merchant_id = ? AND v.entered_at >= ? AND v.entered_at < ?", merchant, a, b);
        return out;
    }

    private void collect(List<Touch> out, Kind kind, String sql, Object... args) {
        for (Map<String, Object> row : jdbc.queryForList(sql, args)) {
            Instant at = instant(row.get("at"));
            if (row.get("member_id") instanceof Number member && at != null) out.add(new Touch(member.longValue(), at, kind));
        }
    }

    static Instant instant(Object value) {
        if (value instanceof java.util.Date d) return d.toInstant();
        if (value instanceof java.time.LocalDateTime l) return l.atZone(MerchantWeeklyProof.ZONE).toInstant();
        return null;
    }
}

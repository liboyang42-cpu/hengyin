package com.chengyinhub.business.citygame;

import com.chengyinhub.business.mapper.CityGameMapper;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared CITY projections: board wire object, membership derivation, request validation. */
final class CityGameSupport {
    private CityGameSupport() {}

    static final class Membership {
        final Map<String, Object> participation;
        final Map<String, Object> team;

        Membership(Map<String, Object> participation, Map<String, Object> team) {
            this.participation = participation;
            this.team = team;
        }

        boolean present() { return participation != null && flag(participation.get("present")); }
        boolean confirmed() { return participation != null && flag(participation.get("confirmed")); }
        String sessionId() { return team == null ? null : (String) team.get("sessionId"); }
        String teamId() { return team == null ? null : (String) team.get("teamId"); }
        long teamVersion() { return team == null ? 0 : ((Number) team.get("version")).longValue(); }

        /** JOINED = present and confirmed in a locked team (the r491 rule the app validates). */
        boolean joined() { return present() && confirmed() && sessionId() != null; }

        String wireStatus() { return joined() ? "JOINED" : "NOT_JOINED"; }

        /** Read participation: the locked team session and team version. */
        Map<String, Object> wireParticipation() {
            if (!joined()) return null;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("participationId", sessionId());
            out.put("membershipVersion", teamVersion());
            return out;
        }
    }

    static Membership membership(CityGameMapper mapper, String boardId, long memberId) {
        Map<String, Object> participation = mapper.selectParticipation(boardId, memberId);
        Map<String, Object> team = participation == null ? null : mapper.selectTeam((String) participation.get("teamId"));
        return new Membership(participation, team);
    }

    static Map<String, Object> wireBoard(Map<String, Object> board) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameId", board.get("gameId"));
        out.put("boardId", board.get("boardId"));
        out.put("regionId", board.get("regionId"));
        out.put("seasonId", board.get("seasonId"));
        out.put("rulesReleaseId", board.get("rulesReleaseId"));
        out.put("rulesHash", board.get("rulesHash"));
        out.put("lifecycle", board.get("lifecycle"));
        out.put("title", board.get("title"));
        out.put("revision", ((Number) board.get("revision")).longValue());
        return out;
    }

    static boolean flag(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        return value != null && ((Number) value).intValue() != 0;
    }

    static long millis(Object value) {
        if (value == null) return 0;
        if (value instanceof Date) return ((Date) value).getTime();
        if (value instanceof java.time.LocalDateTime) {
            return ((java.time.LocalDateTime) value).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        throw new IllegalStateException("not a time: " + value.getClass());
    }

    static Date date(Object value) { return value == null ? null : new Date(millis(value)); }

    static String iso(Object value) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis(value)));
    }

    static String requireId(String value, String field) {
        if (!CityGameRules.id(value)) throw new CityGameException(400, field, field + " invalid");
        return value;
    }

    static long requireVersion(String value, String field) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,15}")) throw new CityGameException(400, field, field + " invalid");
        long parsed = Long.parseLong(value);
        if (parsed > CityGameRules.MAX_SAFE) throw new CityGameException(400, field, field + " invalid");
        return parsed;
    }

    static void requireMember(Long memberId) {
        if (memberId == null || memberId <= 0) throw new CityGameException(401, "UNAUTHORIZED", "请先登录");
    }

    /** The client-held board tuple must still be the current board, else the read must restart. */
    static void requireSameBoard(Map<String, Object> board, String boardId, String seasonId, String rulesReleaseId, long revision) {
        if (board == null || !board.get("boardId").equals(boardId) || !board.get("seasonId").equals(seasonId)
                || !board.get("rulesReleaseId").equals(rulesReleaseId) || ((Number) board.get("revision")).longValue() != revision) {
            throw new CityGameException(409, "CITY_SNAPSHOT_CHANGED", "城市棋盘已更新");
        }
    }
}

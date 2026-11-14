package com.chengyinhub.business.citygame;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.citygame.CityGameSupport.Membership;
import com.chengyinhub.business.mapper.CityGameMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Shared plumbing for the r491 CITY surfaces: the current board of a region, the caller's team, the frozen
 * participation binding, and original-request receipts. A command runs once inside one transaction with its
 * receipt; a retry with the same body replays the stored reply, a reused requestId with another body is 409.
 */
@Component
public class CityR491Store {
    final CityGameMapper mapper;
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;
    final LongSupplier clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CityR491Store(CityGameMapper mapper, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(mapper, jdbc, transactions, System::currentTimeMillis);
    }

    CityR491Store(CityGameMapper mapper, JdbcTemplate jdbc, PlatformTransactionManager transactions, LongSupplier clock) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    long now() { return clock.getAsLong(); }

    /** The region's current board; readable while OPEN or FROZEN (one lifecycle for every route). */
    Map<String, Object> board(String regionId) {
        CityGameSupport.requireId(regionId, "regionId");
        Map<String, Object> board = mapper.selectCurrentBoard(regionId);
        if (!CityGameReadService.readable(board)) throw new CityGameException(404, "CITY_NO_CURRENT_BOARD", "当前区域没有城市棋盘");
        return board;
    }

    Membership membership(Map<String, Object> board, long memberId) {
        return CityGameSupport.membership(mapper, (String) board.get("boardId"), memberId);
    }

    static Map<String, Object> binding(Map<String, Object> board) { return CityGameReadService.binding(board); }

    static boolean open(Map<String, Object> board) { return "OPEN".equals(board.get("lifecycle")); }

    /** The frozen release tuple every r491 command names. */
    static boolean sameRelease(Map<String, Object> board, Long releaseId, String contentHash) {
        return releaseId != null && releaseId == ((Number) board.get("releaseId")).longValue() && board.get("rulesHash").equals(contentHash);
    }

    static String policyHash(Object policy) { return CityGameRules.sha256(com.chengyinhub.business.appgaps.AppGapSupport.canonical(policy)); }

    // ---- receipts ----

    /** One command's outcome: HTTP status, errorCode (null on 200) and the reply data. */
    static final class Outcome {
        final int status;
        final String errorCode;
        final Map<String, Object> data;

        Outcome(int status, String errorCode, Map<String, Object> data) {
            this.status = status;
            this.errorCode = errorCode;
            this.data = data;
        }
    }

    static Outcome ok(Map<String, Object> data) { return new Outcome(200, null, data); }

    static Outcome refused(int status, String errorCode, String requestId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("requestStatus", "COMPLETE");
        return new Outcome(status, errorCode, data);
    }

    Outcome stored(long memberId, String domain, String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT request_hash, http_status, error_code, body_json FROM city_r491_request WHERE member_id = ? AND domain = ? AND request_id = ?",
                memberId, domain, requestId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        Map<String, Object> data = JSON.parseObject(String.valueOf(row.get("body_json")));
        data.put("__hash", row.get("request_hash"));
        return new Outcome(((Number) row.get("http_status")).intValue(), (String) row.get("error_code"), data);
    }

    static Outcome strip(Outcome stored) {
        Map<String, Object> data = new LinkedHashMap<>(stored.data);
        data.remove("__hash");
        return new Outcome(stored.status, stored.errorCode, data);
    }

    /**
     * Runs a command exactly once per (member, domain, requestId). The work and its receipt commit together; an
     * exception from the work (a malformed or unauthorised call) stores nothing, so it is not a receipt.
     */
    Outcome once(long memberId, String domain, String requestId, String hash, Supplier<Outcome> work) {
        Outcome previous = stored(memberId, domain, requestId);
        if (previous != null) return replay(previous, hash);
        try {
            return tx.execute(status -> {
                Outcome outcome = work.get();
                Map<String, Object> body = new LinkedHashMap<>(outcome.data);
                jdbc.update("INSERT INTO city_r491_request(member_id, domain, request_id, request_hash, http_status, error_code, body_json) VALUES (?,?,?,?,?,?,?)",
                        memberId, domain, requestId, hash, outcome.status, outcome.errorCode, JSON.toJSONString(body));
                // The first reply is the stored body, so it is byte-identical to every later replay.
                return new Outcome(outcome.status, outcome.errorCode, JSON.parseObject(JSON.toJSONString(body)));
            });
        } catch (DuplicateKeyException concurrent) {
            Outcome winner = stored(memberId, domain, requestId);
            if (winner == null) throw concurrent;
            return replay(winner, hash);
        }
    }

    private static Outcome replay(Outcome stored, String hash) {
        if (!hash.equals(stored.data.get("__hash"))) throw new CityGameException(409, "CITY_REQUEST_REUSED", "请求编号已用于其他指令");
        return strip(stored);
    }

    /** Receipt read: the stored reply, or 404 when this member never stored one. */
    Outcome receipt(long memberId, String domain, String requestId) {
        Outcome stored = stored(memberId, domain, requestId);
        if (stored == null) throw new CityGameException(404, "CITY_RECEIPT_NOT_FOUND", "没有这条请求的回执");
        return strip(stored);
    }

    static String requestHash(Map<String, Object> body) { return CityGameRules.requestHash(body); }

    static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v instanceof String ? (String) v : null;
    }

    static Long num(Map<String, Object> body, String key) {
        return com.chengyinhub.business.appgaps.AppGapSupport.integer(body, key);
    }

    static <T> T run(Supplier<T> supplier) { return supplier.get(); }
}

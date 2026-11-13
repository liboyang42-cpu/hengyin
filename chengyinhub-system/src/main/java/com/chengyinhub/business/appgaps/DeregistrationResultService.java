package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.domain.vo.DeregistrationStatusVO;
import com.chengyinhub.business.service.IAccountDeregistrationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * GET /api/user/deregister/result. Applying revokes every session of the account, so a result read cannot rely
 * on the session. Each successful apply reply carries a result token shown once; only its SHA-256 is stored.
 * The token reads that member's deregistration status and nothing else. A still-logged-in caller may read
 * their own result without a token.
 */
@Service
public class DeregistrationResultService {
    static final long TOKEN_TTL_MS = 45L * 24 * 3600 * 1000;

    private final JdbcTemplate jdbc;
    private final IAccountDeregistrationService deregistration;
    private final LongSupplier clock;

    public DeregistrationResultService(JdbcTemplate jdbc, IAccountDeregistrationService deregistration) {
        this(jdbc, deregistration, System::currentTimeMillis);
    }

    DeregistrationResultService(JdbcTemplate jdbc, IAccountDeregistrationService deregistration, LongSupplier clock) {
        this.jdbc = jdbc;
        this.deregistration = deregistration;
        this.clock = clock;
    }

    /** Issues a result token for a PENDING apply reply; returns the plaintext once. */
    public String issue(Long memberId, String requestId) {
        AppGapSupport.requireMember(memberId);
        String token = AppGapSupport.randomHex(32);
        long now = clock.getAsLong();
        jdbc.update("INSERT INTO account_deregistration_result_token(token_hash, member_id, request_id, create_time, expires_at) VALUES (?,?,?,?,?)",
                AppGapSupport.sha256(token), memberId, requestId, new Date(now), new Date(now + TOKEN_TTL_MS));
        return token;
    }

    public Map<String, Object> result(Long caller, String token) {
        Long memberId;
        String verifiedBy;
        if (token != null) {
            if (!AppGapSupport.hash(token)) throw new AppGapException(400, "DEREGISTER_RESULT_TOKEN_INVALID", "结果凭证无效");
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT member_id, expires_at FROM account_deregistration_result_token WHERE token_hash = ?", AppGapSupport.sha256(token));
            // Unknown and expired tokens look the same so a token cannot be probed for its account.
            if (rows.isEmpty() || AppGapSupport.millis(rows.get(0).get("expires_at")) <= clock.getAsLong()) {
                throw new AppGapException(404, "DEREGISTER_RESULT_NOT_FOUND", "没有可查询的注销结果");
            }
            memberId = ((Number) rows.get(0).get("member_id")).longValue();
            if (caller != null && !caller.equals(memberId)) throw new AppGapException(404, "DEREGISTER_RESULT_NOT_FOUND", "没有可查询的注销结果");
            verifiedBy = "RESULT_TOKEN";
        } else {
            if (caller == null) throw AppGapException.unauthorized();
            memberId = caller;
            verifiedBy = "SESSION";
        }
        DeregistrationStatusVO status = deregistration.status(memberId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status.getStatus());
        out.put("executeAfter", status.getExecuteAfter() == null ? null : AppGapSupport.iso(status.getExecuteAfter().getTime()));
        out.put("final", "COMPLETED".equals(status.getStatus()) || "CANCELLED".equals(status.getStatus()) || "FAILED".equals(status.getStatus()));
        out.put("verifiedBy", verifiedBy);
        return out;
    }
}

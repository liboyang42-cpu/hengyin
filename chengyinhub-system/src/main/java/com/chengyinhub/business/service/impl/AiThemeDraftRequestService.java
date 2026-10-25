package com.chengyinhub.business.service.impl;

import java.util.regex.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.chengyinhub.business.domain.AiThemeDraftIntentRow;
import com.chengyinhub.business.mapper.AiThemeDraftIntentMapper;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftPrepareReq;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftExecuteReq;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentState;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentView;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftRecoveryItem;
import com.chengyinhub.business.service.ai.AiThemeDraftPlan;
import com.chengyinhub.business.service.ai.AiThemeDraftPlanRejectedException;
import com.chengyinhub.business.service.ai.AiProviderResultReceivedException;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftRequestState;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException;
import com.chengyinhub.business.service.ai.AiServiceException;

/** Theme draft v2: durable identity and once-only claim; Redis stores only 24h bodies.
 * Legacy submit is denied. Legacy reads cannot turn missing state into proof of not sent.
 */
@Service
public class AiThemeDraftRequestService
{
    static final String KEY_PREFIX = "ai:theme:draft:request:";
    /** 长于网关单次调用上限;超过即判 UNKNOWN。 */
    static final long LEASE_MILLIS = 3 * 60 * 1000L;
    static final long RETENTION_HOURS = 24;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final String GENERIC_FAILURE = "AI 起草没有成功,请稍后重试";

    @Autowired
    private IAiCreatorService aiCreatorService;

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    @Autowired
    private AiThemeDraftIntentMapper intentMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Value("${ai.themeDraftIntent.enabled:false}")
    private boolean intentEnabled;
    private Clock intentClock = Clock.systemUTC();

    private static final ZoneId INTENT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration BODY_RETENTION = Duration.ofHours(24);
    private static final String BODY_PREFIX = "ai:theme:draft:v2:";
    private static final StringRedisSerializer BODY_STRING = new StringRedisSerializer();
    private static final GenericToStringSerializer<Long> BODY_LONG = new GenericToStringSerializer<>(Long.class);
    // SET and expiry are atomic. TIME + an absolute deadline avoids extending TTL on a delayed command.
    private static final DefaultRedisScript<Long> WRITE_BODY = new DefaultRedisScript<>(
            "local t=redis.call('TIME'); local n=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000); " +
            "local d=tonumber(ARGV[2]); if not d or d<=n then return 0 end; " +
            "local old=redis.call('GET',KEYS[1]); if old then if old~=ARGV[1] then return -1 end; " +
            "local ttl=redis.call('PTTL',KEYS[1]); if ttl<0 or ttl>d-n then redis.call('PEXPIREAT',KEYS[1],d) end; return 1 end; " +
            "redis.call('SET',KEYS[1],ARGV[1],'PX',d-n,'NX'); return 1;", Long.class);
    private static final DefaultRedisScript<String> READ_BODY = new DefaultRedisScript<>(
            "local t=redis.call('TIME'); local n=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000); " +
            "if n>=tonumber(ARGV[1]) then redis.call('DEL',KEYS[1]); return nil end; return redis.call('GET',KEYS[1]);", String.class);
    private static final DefaultRedisScript<Long> PURGE_BODY = new DefaultRedisScript<>(
            "local t=redis.call('TIME'); local n=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000); " +
            "if n<tonumber(ARGV[1]) then return 0 end; redis.call('DEL',KEYS[1]); " +
            "if redis.call('EXISTS',KEYS[1])==0 then return 1 end; return 0;", Long.class);

    /** 状态存储读写失败。调用方据此回 STATE_UNAVAILABLE,而不是把它当成「没有这条请求」。 */
    public static class StateUnavailableException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        StateUnavailableException(Throwable cause) { super("AI 草稿状态暂时无法确认", cause); }
    }

    /** Exact protocol refusal, independent of the final common ServiceException. */
    public static class IntentRejectedException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        private final Integer code;
        private final String errorCode;
        private final AiThemeDraftIntentView intent;

        public IntentRejectedException(int code, String errorCode, AiThemeDraftIntentView intent)
        {
            super(errorCode);
            if (!(code == 400 || code == 403 || code == 404 || code == 409 || code == 503)
                    || errorCode == null || !errorCode.matches("AI_DRAFT_[A-Z_]{1,55}"))
                throw new IllegalArgumentException("Invalid intent protocol refusal");
            this.code = code; this.errorCode = errorCode; this.intent = intent;
        }
        public Integer getCode() { return code; }
        public String getErrorCode() { return errorCode; }
        public AiThemeDraftIntentView getIntent() { return intent; }
    }

    public static boolean isValidRequestId(String requestId)
    {
        return requestId != null && UUID_PATTERN.matcher(requestId.trim()).matches();
    }

    /**
     * Legacy execution is closed, including old UUIDs and missing request IDs.
     * Kept only for source compatibility; callers must use explicit prepare/execute.
     */
    public AiThemeDraftRequestState submit(AiThemeDraftReq req, Long memberId)
    {
        throw new IntentRejectedException(400, "AI_DRAFT_PREPARE_REQUIRED", null);
    }

    /**
     * Legacy read only. Missing state is UNKNOWN, never evidence that a provider was not sent.
     *
     * @throws StateUnavailableException 存储读失败
     */
    public AiThemeDraftRequestState read(Long memberId, String requestId)
    {
        requireMember(memberId);
        if (!isValidRequestId(requestId)) throw rejected(400, "AI_DRAFT_INVALID_REQUEST", null);
        String rid = requestId.trim();
        Object raw;
        try
        {
            raw = redisTemplate.opsForValue().get(key(memberId, rid));
        }
        catch (RuntimeException e)
        {
            throw new StateUnavailableException(e);
        }
        AiThemeDraftRequestState out = new AiThemeDraftRequestState();
        out.setRequestId(rid);
        if (raw == null)
        {
            out.setState(AiThemeDraftRequestState.UNKNOWN);
            out.setMessage("LEGACY_UNBOUND");
            return out;
        }
        JSONObject stored;
        try
        {
            stored = JSON.parseObject(String.valueOf(raw));
        }
        catch (RuntimeException e)
        {
            stored = null;
        }
        String state = stored == null ? null : stored.getString("state");
        if (AiThemeDraftRequestState.SUCCEEDED.equals(state))
        {
            AiThemeDraftResp result;
            try { result = stored.getObject("result", AiThemeDraftResp.class); }
            catch (RuntimeException corrupt) { result = null; }
            if (result != null && result.getDraft() != null)
            {
                out.setState(state);
                out.setResult(result);
                return out;
            }
        }
        else if (AiThemeDraftRequestState.FAILED.equals(state))
        {
            out.setState(state);
            String message = stored.getString("message");
            out.setMessage(message == null || message.trim().isEmpty() ? GENERIC_FAILURE : message);
            return out;
        }
        else if (AiThemeDraftRequestState.PROCESSING.equals(state))
        {
            long startedAt = stored.getLongValue("startedAt");
            out.setState(System.currentTimeMillis() - startedAt > LEASE_MILLIS
                    ? AiThemeDraftRequestState.UNKNOWN : AiThemeDraftRequestState.PROCESSING);
            return out;
        }
        // 记录存在但解析不出终态:结果不可证明,按 UNKNOWN,绝不当作「没有生成」。
        out.setState(AiThemeDraftRequestState.UNKNOWN);
        return out;
    }

    /** First prepare persists the identity and original plan before writing a TTL body. */
    public AiThemeDraftIntentView prepare(Long memberId, AiThemeDraftPrepareReq req)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        // Match the current simple caller boundary; preserve the original bytes for context/hash.
        if (req == null || req.idea() == null || req.idea().length() > 300 || req.idea().trim().isEmpty())
            throw rejected(400, "AI_DRAFT_INVALID_REQUEST", null);
        if (!validProductType(req.productType())) throw rejected(400, "AI_DRAFT_INVALID_REQUEST", null);
        requireIntentAdmission(); requireNoIntentTransaction(); requireMember(memberId);
        if (!isValidRequestId(req.prepareKey())) throw rejected(400, "AI_DRAFT_INVALID_REQUEST", null);
        String prepareKey = req.prepareKey().trim().toLowerCase(Locale.ROOT);
        String payload = contextPayload(req);
        String requestHash = sha256(payload);
        AiThemeDraftIntentRow existing = intentTx(() -> intentMapper.selectByPrepareKey(memberId, prepareKey));
        if (existing != null) return samePrepare(existing, memberId, prepareKey, requestHash);

        Instant now = intentNow();
        AiThemeDraftPlan plan;
        try { plan = aiCreatorService.planThemeDraft(memberId, uuid()); }
        catch (AiThemeDraftPlanRejectedException refused)
        {
            throw switch (refused.getCode()) {
                case AUTHORITY_CHANGED -> rejected(403, "AI_DRAFT_AUTHORITY_CHANGED", null);
                case PLAN_EXPIRED -> rejected(409, "AI_DRAFT_PLAN_EXPIRED", null);
                case CONFIG_CHANGED -> rejected(409, "AI_DRAFT_CONFIG_CHANGED", null);
                case PLAN_INVALID, CHECK_UNAVAILABLE -> unavailable("Original planning unavailable");
            };
        }
        catch (RuntimeException planning) { throw unavailable("Original planning unavailable"); }
        AiThemeDraftIntentRow row = new AiThemeDraftIntentRow();
        row.setMemberId(memberId); row.setPrepareKey(prepareKey); row.setRequestId("td2_" + uuid());
        row.setRequestHash(requestHash); row.setProductType(req.productType()); copyPlan(row, plan); row.setPlanHash(planHash(plan));
        row.setState("PREPARED"); row.setCreatedAt(now); row.setContextExpiresAt(now.plus(BODY_RETENTION));
        validateIntent(row);
        if (!plan.originalDay().equals(LocalDate.ofInstant(now, INTENT_ZONE).format(DateTimeFormatter.BASIC_ISO_DATE)))
            throw unavailable("Original plan day mismatch");
        try
        {
            intentTx(() -> { if (intentMapper.insertPrepared(row) != 1) throw unavailable("Prepare insert failed"); return null; });
        }
        catch (DuplicateKeyException conflict)
        {
            AiThemeDraftIntentRow winner = intentTx(() -> intentMapper.selectByPrepareKeyForUpdate(memberId, prepareKey));
            if (winner == null) throw unavailable("Original identity collision");
            return samePrepare(winner, memberId, prepareKey, requestHash);
        }
        try
        {
            if (!writeBody(row, "context", payload, requestHash, row.getContextExpiresAt()))
                throw unavailable("Original context expired before storage");
        }
        catch (RuntimeException storage)
        {
            rejectPrepared(row, "AI_DRAFT_CONTEXT_UNAVAILABLE");
            throw unavailable("Original context storage unavailable");
        }
        return intentView(ownedIntent(memberId, row.getRequestId()), false);
    }

    /** Only a positively committed PREPARED -> PROCESSING winner may leave for Creator. */
    public AiThemeDraftIntentView execute(Long memberId, AiThemeDraftExecuteReq req)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        requireIntentAdmission(); requireNoIntentTransaction(); requireMember(memberId);
        if (req == null || !serverId(req.requestId())) throw rejected(400, "AI_DRAFT_PREPARE_REQUIRED", null);
        AiThemeDraftIntentRow row = ownedIntent(memberId, req.requestId());
        if (!"PREPARED".equals(row.getState())) return projectExecution(intentView(row, true));
        if (!intentNow().isBefore(row.getExecuteBefore()) || !intentNow().isBefore(row.getContextExpiresAt()))
        {
            rejectPrepared(row, "AI_DRAFT_PLAN_EXPIRED");
            return projectExecution(intentView(ownedIntent(memberId, row.getRequestId()), true));
        }
        String payload;
        try { payload = readBody(row, "context", row.getRequestHash(), row.getContextExpiresAt()); }
        catch (RuntimeException failure) { throw unavailable("Original context read unavailable"); }
        if (payload == null)
        {
            rejectPrepared(row, "AI_DRAFT_CONTEXT_UNAVAILABLE");
            return projectExecution(intentView(ownedIntent(memberId, row.getRequestId()), true));
        }
        AiThemeDraftReq command = decodeContext(row, payload);
        String claim = uuid();
        boolean won = Boolean.TRUE.equals(intentTx(() -> intentMapper.claim(row.getId(), memberId,
                row.getRequestHash(), row.getPlanHash(), claim, intentNow()) == 1));
        if (!won) return projectExecution(intentView(ownedIntent(memberId, row.getRequestId()), true));
        row.setClaimToken(claim); row.setState("PROCESSING");

        AiThemeDraftResp result = null;
        String state = "FAILED", provider = null, reason = "AI_DRAFT_FAILED";
        try
        {
            requireNoIntentTransaction();
            result = aiCreatorService.themeDraftPrepared(command, memberId, originalPlan(row));
            if (result != null && result.getDraft() != null)
            { state = "SUCCEEDED"; provider = "RECEIVED"; reason = null; }
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused)
        { throw paused; }
        catch (AiThemeDraftPlanRejectedException preflight)
        {
            provider = "NOT_SENT";
            reason = switch (preflight.getCode()) {
                case AUTHORITY_CHANGED -> "AI_DRAFT_AUTHORITY_CHANGED";
                case PLAN_EXPIRED -> "AI_DRAFT_PLAN_EXPIRED";
                case CONFIG_CHANGED -> "AI_DRAFT_CONFIG_CHANGED";
                case PLAN_INVALID, CHECK_UNAVAILABLE -> "AI_DRAFT_STATE_UNAVAILABLE";
            };
        }
        catch (AiProviderOutcomeUnknownException unknown)
        { state = "UNKNOWN"; provider = "UNKNOWN"; reason = "AI_DRAFT_OUTCOME_UNKNOWN"; }
        catch (AiProviderResultReceivedException received)
        { provider = "RECEIVED"; reason = "AI_DRAFT_FAILED"; }
        catch (AiServiceException known)
        { reason = "AI_DRAFT_FAILED"; }
        catch (RuntimeException failure)
        {
            // No provider-stage inference from generic reason/cause/message. No raw input or error text logged.
            reason = "AI_DRAFT_FAILED";
        }
        row.setState(state); row.setProviderState(provider); row.setReasonCode(reason); row.setCompletedAt(intentNow());
        if ("SUCCEEDED".equals(state))
        {
            String resultPayload = JSON.toJSONString(result);
            row.setResultHash(sha256(resultPayload)); row.setResultExpiresAt(row.getCompletedAt().plus(BODY_RETENTION));
            try { writeBody(row, "result", resultPayload, row.getResultHash(), row.getResultExpiresAt()); }
            catch (RuntimeException missingBody) { /* Persist the known fact even when Redis cannot retain the body. */ }
        }
        row.setOutcomeHash(outcomeHash(row));
        intentTx(() -> { if (intentMapper.complete(row) != 1) throw unavailable("Original outcome CAS failed"); return null; });
        AiThemeDraftIntentView view = intentView(ownedIntent(memberId, row.getRequestId()), false);
        if ("AI_DRAFT_STATE_UNAVAILABLE".equals(reason)) throw unavailable("Original preflight unavailable");
        return projectExecution(view);
    }

    public AiThemeDraftIntentView readIntent(Long memberId, String requestId)
    {
        requireMember(memberId);
        if (serverId(requestId)) return intentView(ownedIntent(memberId, requestId), true);
        if (!isValidRequestId(requestId)) throw rejected(400, "AI_DRAFT_INVALID_REQUEST", null);
        AiThemeDraftRequestState legacy = read(memberId, requestId);
        AiThemeDraftIntentState state = "NOT_FOUND".equals(legacy.getState()) ? AiThemeDraftIntentState.UNKNOWN
                : AiThemeDraftIntentState.valueOf(legacy.getState());
        return new AiThemeDraftIntentView(legacy.getRequestId(), state, true, false,
                state == AiThemeDraftIntentState.SUCCEEDED && legacy.getResult() != null,
                legacy.getResult(), state == AiThemeDraftIntentState.UNKNOWN ? "LEGACY_UNBOUND" : null, null, null, null);
    }

    public AiThemeDraftIntentView readPrepared(Long memberId, String prepareKey)
    {
        requireMember(memberId);
        if (!isValidRequestId(prepareKey)) throw rejected(400, "AI_DRAFT_INVALID_REQUEST", null);
        AiThemeDraftIntentRow row = intentTx(() -> intentMapper.selectByPrepareKey(memberId,
                prepareKey.trim().toLowerCase(Locale.ROOT)));
        if (row != null) requirePrepareBinding(row, memberId, prepareKey.trim().toLowerCase(Locale.ROOT));
        return row == null ? new AiThemeDraftIntentView(null, AiThemeDraftIntentState.UNKNOWN, true, false,
                false, null, "AI_DRAFT_PREPARE_UNCONFIRMED", null, null, null) : intentView(row, true);
    }

    /** Fixed newest-first owner metadata lookahead, including known terminal facts. No body access. */
    public List<AiThemeDraftRecoveryItem> readRecovery(Long memberId, Long beforeId)
    {
        requireMember(memberId);
        if (beforeId != null && beforeId <= 0) throw rejected(400, "AI_DRAFT_INVALID_REQUEST", null);
        List<AiThemeDraftIntentRow> rows = intentTx(() -> intentMapper.selectRecovery(memberId, beforeId));
        if (rows == null || rows.size() > 21) throw unavailable("Recovery read unavailable");
        Long previous = beforeId;
        java.util.ArrayList<AiThemeDraftRecoveryItem> items = new java.util.ArrayList<>();
        for (AiThemeDraftIntentRow row : rows)
        {
            if (!Objects.equals(memberId, row.getMemberId()) || row.getId() == null || row.getId() <= 0
                    || (previous != null && row.getId() >= previous)
                    || !serverId(row.getRequestId()) || !isValidRequestId(row.getPrepareKey())
                    || row.getCreatedAt() == null || row.getExecuteBefore() == null || !validProductType(row.getProductType()))
                throw unavailable("Recovery projection invalid");
            AiThemeDraftIntentState state;
            try { state = AiThemeDraftIntentState.valueOf(row.getState()); }
            catch (RuntimeException corrupt) { throw unavailable("Recovery fact invalid"); }
            if (state == AiThemeDraftIntentState.SUCCEEDED && row.getResultExpiresAt() == null)
                throw unavailable("Recovery result deadline missing");
            previous = row.getId();
            items.add(new AiThemeDraftRecoveryItem(row.getId(), row.getPrepareKey(), row.getRequestId(), state,
                    row.getReasonCode(), row.getCreatedAt(), row.getExecuteBefore(), row.getResultExpiresAt(), row.getProductType()));
        }
        return List.copyOf(items);
    }

    /** Total bound counts exact body keys, not rows. Works while new admission is disabled. */
    public int purgeExpiredBodies(int limit)
    {
        requireNoIntentTransaction();
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("body purge limit must be 1..100");
        int cleaned = 0, attempted = 0;
        for (AiThemeDraftIntentRow row : intentTx(() -> intentMapper.selectExpiredContexts(limit, intentNow())))
        {
            validateIntent(row); attempted++;
            if (purgeBody(row, "context", row.getContextExpiresAt()))
            {
                intentTx(() -> intentMapper.markContextPurged(row.getId(), row.getRequestId(), row.getContextExpiresAt(), intentNow())); cleaned++;
            }
        }
        int remaining = limit - attempted;
        if (remaining > 0)
            for (AiThemeDraftIntentRow row : intentTx(() -> intentMapper.selectExpiredResults(remaining, intentNow())))
            {
                validateIntent(row);
                if (purgeBody(row, "result", row.getResultExpiresAt()))
                {
                    intentTx(() -> intentMapper.markResultPurged(row.getId(), row.getRequestId(), row.getResultExpiresAt(), intentNow())); cleaned++;
                }
            }
        return cleaned;
    }

    private AiThemeDraftIntentView samePrepare(AiThemeDraftIntentRow row, Long memberId, String prepareKey, String requestHash)
    {
        requirePrepareBinding(row, memberId, prepareKey);
        validateIntent(row);
        if (!requestHash.equals(row.getRequestHash())) throw rejected(409, "AI_DRAFT_REQUEST_CONFLICT", null);
        return intentView(row, true);
    }

    private static void requirePrepareBinding(AiThemeDraftIntentRow row, Long memberId, String prepareKey)
    {
        if (!Objects.equals(memberId, row.getMemberId()) || !prepareKey.equals(row.getPrepareKey()))
            throw unavailable("Original prepare binding invalid");
    }

    private AiThemeDraftIntentRow ownedIntent(Long memberId, String requestId)
    {
        AiThemeDraftIntentRow row = intentTx(() -> intentMapper.selectOwned(memberId, requestId));
        if (row == null || !Objects.equals(memberId, row.getMemberId()) || !requestId.equals(row.getRequestId()))
            throw rejected(404, "AI_DRAFT_INTENT_UNAVAILABLE", null);
        validateIntent(row);
        return row;
    }

    private AiThemeDraftIntentView intentView(AiThemeDraftIntentRow row, boolean replayed)
    {
        validateIntent(row);
        AiThemeDraftIntentState state = AiThemeDraftIntentState.valueOf(row.getState());
        String reason = row.getReasonCode();
        if (state == AiThemeDraftIntentState.PROCESSING && row.getClaimedAt() != null
                && !intentNow().isBefore(row.getClaimedAt().plusMillis(LEASE_MILLIS)))
        { state = AiThemeDraftIntentState.UNKNOWN; reason = "AI_DRAFT_OUTCOME_UNKNOWN"; }
        boolean context = false;
        if (state == AiThemeDraftIntentState.PREPARED && intentNow().isBefore(row.getContextExpiresAt()))
            try { context = readBody(row, "context", row.getRequestHash(), row.getContextExpiresAt()) != null; }
            catch (RuntimeException ignored) { reason = "AI_DRAFT_CONTEXT_UNAVAILABLE"; }
        AiThemeDraftResp result = null;
        if (state == AiThemeDraftIntentState.SUCCEEDED)
        {
            if (!intentNow().isBefore(row.getResultExpiresAt())) reason = "AI_DRAFT_RESULT_EXPIRED";
            else
            {
                try
                {
                    String payload = readBody(row, "result", row.getResultHash(), row.getResultExpiresAt());
                    if (payload != null) result = JSON.parseObject(payload, AiThemeDraftResp.class);
                    if (result != null && result.getDraft() == null) result = null;
                }
                catch (RuntimeException ignored) { result = null; }
                if (result == null) reason = "AI_DRAFT_RESULT_UNAVAILABLE";
            }
        }
        return new AiThemeDraftIntentView(row.getRequestId(), state, replayed, context, result != null,
                result, reason, row.getExecuteBefore(), row.getResultExpiresAt(), row.getProductType());
    }

    private AiThemeDraftIntentView projectExecution(AiThemeDraftIntentView view)
    {
        if (view.state() == AiThemeDraftIntentState.PROCESSING) throw rejected(409, "AI_DRAFT_IN_PROGRESS", view);
        if (view.state() == AiThemeDraftIntentState.UNKNOWN) throw rejected(409, "AI_DRAFT_OUTCOME_UNKNOWN", view);
        if ("AI_DRAFT_RESULT_EXPIRED".equals(view.reasonCode())) throw rejected(409, view.reasonCode(), view);
        if ("AI_DRAFT_RESULT_UNAVAILABLE".equals(view.reasonCode())) throw rejected(503, view.reasonCode(), view);
        if ("AI_DRAFT_AUTHORITY_CHANGED".equals(view.reasonCode())) throw rejected(403, view.reasonCode(), view);
        if ("AI_DRAFT_PLAN_EXPIRED".equals(view.reasonCode()) || "AI_DRAFT_CONFIG_CHANGED".equals(view.reasonCode()))
            throw rejected(409, view.reasonCode(), view);
        if ("AI_DRAFT_STATE_UNAVAILABLE".equals(view.reasonCode())) throw unavailable("Original preflight unavailable");
        return view;
    }

    private void rejectPrepared(AiThemeDraftIntentRow row, String reason)
    {
        row.setReasonCode(reason); row.setState("FAILED"); row.setProviderState("NOT_SENT"); row.setCompletedAt(intentNow());
        row.setOutcomeHash(outcomeHash(row));
        intentTx(() -> intentMapper.rejectPrepared(row)); // A concurrent claim wins; never overwrite it.
    }

    private boolean writeBody(AiThemeDraftIntentRow row, String kind, String payload, String hash, Instant expiry)
    {
        JSONObject envelope = new JSONObject();
        envelope.put("member", row.getMemberId().toString()); envelope.put("requestId", row.getRequestId());
        envelope.put("hash", hash); envelope.put("expiresAt", expiry.toEpochMilli()); envelope.put("payload", payload);
        Long result = redisTemplate.execute(WRITE_BODY, BODY_STRING, BODY_LONG,
                List.<Object>of(bodyKey(row, kind)), envelope.toJSONString(), Long.toString(expiry.toEpochMilli()));
        if (result == null || result < 0) throw unavailable("Original body collision");
        return result == 1L;
    }

    private String readBody(AiThemeDraftIntentRow row, String kind, String hash, Instant expiry)
    {
        if (!intentNow().isBefore(expiry)) return null;
        String raw = redisTemplate.execute(READ_BODY, BODY_STRING, BODY_STRING,
                List.<Object>of(bodyKey(row, kind)), Long.toString(expiry.toEpochMilli()));
        if (raw == null) return null;
        try
        {
            JSONObject envelope = JSON.parseObject(raw);
            String payload = envelope.getString("payload");
            if (!row.getMemberId().toString().equals(envelope.getString("member"))
                    || !row.getRequestId().equals(envelope.getString("requestId"))
                    || !hash.equals(envelope.getString("hash")) || envelope.getLongValue("expiresAt") != expiry.toEpochMilli()
                    || payload == null || !hash.equals(sha256(payload))) return null;
            return payload;
        }
        catch (RuntimeException corrupt) { return null; }
    }

    private boolean purgeBody(AiThemeDraftIntentRow row, String kind, Instant expiry)
    {
        if (expiry == null || intentNow().isBefore(expiry)) return false;
        Long result = redisTemplate.execute(PURGE_BODY, BODY_STRING, BODY_LONG,
                List.<Object>of(bodyKey(row, kind)), Long.toString(expiry.toEpochMilli()));
        if (result == null) throw unavailable("Body purge readback unavailable");
        return result == 1L;
    }

    private static String bodyKey(AiThemeDraftIntentRow row, String kind)
    { return BODY_PREFIX + kind + ":" + row.getMemberId() + ":" + row.getRequestId(); }

    private static String contextPayload(AiThemeDraftPrepareReq req)
    {
        JSONArray payload = new JSONArray(); payload.add("theme_draft.request.v1"); payload.add(req.idea());
        payload.add(req.productType()); payload.add(req.existingNodeNames()); return payload.toJSONString();
    }

    private static AiThemeDraftReq decodeContext(AiThemeDraftIntentRow row, String payload)
    {
        try
        {
            JSONArray original = JSON.parseArray(payload);
            if (original.size() != 4 || !"theme_draft.request.v1".equals(original.getString(0)))
                throw unavailable("Original context invalid");
            AiThemeDraftReq req = new AiThemeDraftReq(); req.setRequestId(row.getRequestId());
            req.setIdea(original.getString(1)); req.setProductType(original.getInteger(2));
            if (!Objects.equals(req.getProductType(), row.getProductType())) throw unavailable("Original product type binding invalid");
            if (original.get(3) != null) req.setExistingNodeNames(original.getJSONArray(3).toJavaList(String.class));
            return req;
        }
        catch (RuntimeException corrupt) { throw unavailable("Original context invalid"); }
    }

    private static void copyPlan(AiThemeDraftIntentRow row, AiThemeDraftPlan plan)
    {
        if (plan == null || !Objects.equals(row.getMemberId(), plan.memberId())) throw unavailable("Invalid planned owner");
        row.setRole(plan.role()); row.setPool(plan.pool()); row.setOriginalDay(plan.originalDay());
        row.setCounterKey(plan.counterKey()); row.setNonce(plan.nonce()); row.setReceiptKey(plan.receiptKey());
        row.setReservation(plan.reservation()); row.setCreatorConfigHash(plan.creatorConfigHash());
        row.setGatewayConfigHash(plan.gatewayConfigHash()); row.setExecuteBefore(plan.executeBefore());
    }

    private static AiThemeDraftPlan originalPlan(AiThemeDraftIntentRow row)
    { return new AiThemeDraftPlan(row.getMemberId(), row.getRole(), row.getPool(), row.getOriginalDay(), row.getCounterKey(),
            row.getNonce(), row.getReceiptKey(), row.getReservation(), row.getCreatorConfigHash(), row.getGatewayConfigHash(), row.getExecuteBefore()); }

    private static String planHash(AiThemeDraftPlan plan)
    {
        JSONArray a = new JSONArray(); a.add("theme_draft.plan.v1"); a.add(plan.memberId()); a.add(plan.role()); a.add(plan.pool());
        a.add(plan.originalDay()); a.add(plan.counterKey()); a.add(plan.nonce()); a.add(plan.receiptKey()); a.add(plan.reservation());
        a.add(plan.creatorConfigHash()); a.add(plan.gatewayConfigHash());
        a.add(plan.executeBefore() == null ? null : plan.executeBefore().toEpochMilli()); return sha256(a.toJSONString());
    }

    private static String outcomeHash(AiThemeDraftIntentRow row)
    {
        JSONArray a = new JSONArray(); a.add("theme_draft.outcome.v1"); a.add(row.getRequestId()); a.add(row.getRequestHash());
        a.add(row.getPlanHash()); a.add(row.getState()); a.add(row.getProviderState()); a.add(row.getReasonCode()); a.add(row.getResultHash());
        return sha256(a.toJSONString());
    }

    private static void validateIntent(AiThemeDraftIntentRow row)
    {
        try
        {
            if (row == null || row.getMemberId() == null || row.getMemberId() <= 0 || !serverId(row.getRequestId())
                    || !isValidRequestId(row.getPrepareKey()) || !hash(row.getRequestHash()) || !hash(row.getPlanHash())
                    || !hash(row.getCreatorConfigHash()) || !hash(row.getGatewayConfigHash()) || !validProductType(row.getProductType())
                    || row.getRole() == null || row.getRole().isBlank() || row.getRole().length() > 32
                    || row.getNonce() == null || !row.getNonce().matches("[a-f0-9]{32}")
                    || row.getCreatedAt() == null || row.getContextExpiresAt() == null || row.getExecuteBefore() == null)
                throw unavailable("Invalid durable intent");
            LocalDate day = LocalDate.parse(row.getOriginalDay(), DateTimeFormatter.BASIC_ISO_DATE);
            boolean player = "player".equals(row.getRole());
            String counter = (player ? "ai:theme:draft:player:" : "ai:gen:limit:gen:") + row.getMemberId() + ":" + row.getOriginalDay();
            String receipt = (player ? "ai:player:theme:draft:reservation:" : "ai:gen:reservation:") + counter + ":" + row.getNonce();
            if (!(player ? "player-draft" : "gen").equals(row.getPool()) || !counter.equals(row.getCounterKey())
                    || !receipt.equals(row.getReceiptKey()) || !(counter + "|" + row.getNonce()).equals(row.getReservation())
                    || !day.plusDays(1).atStartOfDay(INTENT_ZONE).toInstant().equals(row.getExecuteBefore())
                    || !row.getContextExpiresAt().equals(row.getCreatedAt().plus(BODY_RETENTION))
                    || !row.getPlanHash().equals(planHash(originalPlan(row)))) throw unavailable("Original plan binding invalid");
            AiThemeDraftIntentState.valueOf(row.getState());
            if ("SUCCEEDED".equals(row.getState()) && (!hash(row.getResultHash()) || row.getResultExpiresAt() == null))
                throw unavailable("Stored result fact invalid");
            if (("PROCESSING".equals(row.getState()) || "UNKNOWN".equals(row.getState()))
                    && (row.getClaimToken() == null || !row.getClaimToken().matches("[a-f0-9]{32}") || row.getClaimedAt() == null))
                throw unavailable("Original claim missing");
        }
        catch (StateUnavailableException invalid) { throw invalid; }
        catch (RuntimeException invalid) { throw unavailable("Durable intent malformed"); }
    }

    private <T> T intentTx(Supplier<T> action)
    {
        if (transactionManager == null || intentMapper == null) throw unavailable("Intent storage not wired");
        try
        {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tx.execute(status -> action.get());
        }
        catch (DuplicateKeyException duplicate) { throw duplicate; }
        catch (StateUnavailableException unavailable) { throw unavailable; }
        catch (RuntimeException failure) { throw new StateUnavailableException(failure); }
    }

    private Instant intentNow() { return intentClock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private static String uuid() { return UUID.randomUUID().toString().replace("-", ""); }
    private static boolean serverId(String id) { return id != null && id.matches("td2_[a-f0-9]{32}"); }
    private static boolean validProductType(Integer value) { return value == null || value == 1 || value == 2; }
    private static boolean hash(String s) { return s != null && s.matches("[a-f0-9]{64}"); }
    private static String sha256(String text)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private void requireIntentAdmission() { if (!intentEnabled) throw rejected(503, "AI_DRAFT_DISABLED", null); }
    private static void requireNoIntentTransaction()
    { if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable("Intent entry requires no business transaction"); }
    private static void requireMember(Long member)
    { if (member == null || member <= 0) throw rejected(400, "AI_DRAFT_INVALID_REQUEST", null); }
    private static StateUnavailableException unavailable(String reason)
    { return new StateUnavailableException(new IllegalStateException(reason)); }
    private static IntentRejectedException rejected(int code, String reason, AiThemeDraftIntentView view)
    { return new IntentRejectedException(code, reason, view); }

    static String key(Long memberId, String requestId)
    {
        return KEY_PREFIX + memberId + ":" + requestId.toLowerCase();
    }
}

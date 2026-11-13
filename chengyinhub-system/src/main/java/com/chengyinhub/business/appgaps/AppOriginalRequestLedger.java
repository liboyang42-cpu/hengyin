package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.AppOriginalRequestMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * App「原始请求」台账。一个 (protocol, actor, requestId) 只执行一次原写入：
 * <ul>
 *   <li>首次：RUNNING 行与业务写在同一事务里，成功即 COMMITTED（结果投影同事务落库）。</li>
 *   <li>业务拒绝（ServiceException）：业务写整体回滚，另起事务记 REJECTED 与错误码，原 ID 永不再执行。</li>
 *   <li>其他异常：整体回滚、不留行，读回为 NOT_FOUND，客户端可用同一 ID 安全重试。</li>
 *   <li>重放：同摘要直接回放已存结果；摘要或作用域不同的同 ID 一律 409，不覆盖。</li>
 * </ul>
 */
@Service
public class AppOriginalRequestLedger {
    public static final String RUNNING = "RUNNING", COMMITTED = "COMMITTED", REJECTED = "REJECTED", NOT_FOUND = "NOT_FOUND";
    static final ObjectMapper JSON = new ObjectMapper();
    private final AppOriginalRequestMapper mapper;
    private final TransactionTemplate tx;

    public AppOriginalRequestLedger(AppOriginalRequestMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper; this.tx = new TransactionTemplate(manager);
    }

    /** 一次原写入的不可变事实。state 为 COMMITTED/REJECTED/RUNNING/NOT_FOUND。 */
    public record Fact(String state, String requestId, String scope, String payloadSha256, Map<String, Object> result,
                       Integer errorCode, String errorMessage, boolean replayed, Long ledgerId, Long recordedAt) {
        public Fact(String state, String requestId, String scope, String payloadSha256, Map<String, Object> result,
                    Integer errorCode, String errorMessage, boolean replayed) {
            this(state, requestId, scope, payloadSha256, result, errorCode, errorMessage, replayed, null, null);
        }
        public boolean committed() { return COMMITTED.equals(state); }
    }

    /** 旧写返回非 200 即业务拒绝：抛出带原码的 ServiceException，交给台账回滚并记 REJECTED。 */
    public static com.chengyinhub.common.core.domain.AjaxResult requireOk(com.chengyinhub.common.core.domain.AjaxResult result) {
        Object code = result.get(com.chengyinhub.common.core.domain.AjaxResult.CODE_TAG);
        if (!(code instanceof Number n) || n.intValue() != 200)
            throw new ServiceException(String.valueOf(result.get(com.chengyinhub.common.core.domain.AjaxResult.MSG_TAG)),
                    code instanceof Number m ? m.intValue() : 500);
        return result;
    }

    public Fact run(String protocol, long actor, String requestId, String scope, String sha, Supplier<Map<String, Object>> writer) {
        return run(protocol, actor, requestId, scope, sha, (java.util.function.LongFunction<Map<String, Object>>) ledgerId -> writer.get());
    }

    /** writer 收到本次台账行 id（同事务、已分配），可作为不可变审计编号写进结果。 */
    public Fact run(String protocol, long actor, String requestId, String scope, String sha, java.util.function.LongFunction<Map<String, Object>> writer) {
        validate(protocol, actor, requestId, sha);
        for (int attempt = 1; ; attempt++) {
            try { return runOnce(protocol, actor, requestId, scope, sha, writer); }
            catch (org.springframework.dao.PessimisticLockingFailureException lost) {
                // 同键并发且首个执行者回滚时，InnoDB 只会让一个等待者成为死锁牺牲者；整体回滚后按同一 ID 重来。
                if (attempt >= 3) throw lost;
            }
        }
    }

    private Fact runOnce(String protocol, long actor, String requestId, String scope, String sha, java.util.function.LongFunction<Map<String, Object>> writer) {
        String scopeKey = scope == null ? "" : scope;
        try {
            return tx.execute(status -> {
                Map<String, Object> row = row(protocol, actor, requestId, scopeKey, sha, RUNNING);
                if (mapper.insertIgnore(row) != 1) {
                    // 已提交的台账行不可变；insert ignore 已持有该键的共享锁，这里用非锁定读，
                    // 避免多个等待者同时把 S 锁升级成 X 锁而互相死锁。
                    Map<String, Object> existing = mapper.select(protocol, actor, requestId);
                    if (existing == null) throw new ServiceException("REQUEST_ID_STATE_UNKNOWN", 409);
                    return replay(existing, scopeKey, sha);
                }
                Long id = ((Number) row.get("id")).longValue();
                Map<String, Object> result = writer.apply(id);
                if (mapper.finish(id, COMMITTED, write(result), null, null) != 1)
                    throw new IllegalStateException("original request finish lost");
                return new Fact(COMMITTED, requestId, scopeKey, sha, result, null, null, false, id, System.currentTimeMillis());
            });
        } catch (ServiceException rejected) {
            if (Integer.valueOf(409).equals(rejected.getCode()) && rejected.getMessage() != null
                    && rejected.getMessage().startsWith("REQUEST_ID_")) throw rejected;
            Integer code = rejected.getCode() == null ? 500 : rejected.getCode();
            String message = trim(rejected.getMessage());
            Fact stored = tx.execute(status -> {
                Map<String, Object> row = row(protocol, actor, requestId, scopeKey, sha, REJECTED);
                row.put("errorCode", code); row.put("errorMessage", message);
                if (mapper.insertIgnore(row) == 1) return new Fact(REJECTED, requestId, scopeKey, sha, null, code, message, false,
                        ((Number) row.get("id")).longValue(), System.currentTimeMillis());
                return replay(mapper.select(protocol, actor, requestId), scopeKey, sha);
            });
            return stored;
        }
    }

    /** 只读回原结果；不存在返回 NOT_FOUND，摘要/作用域不符同样 409。 */
    public Fact read(String protocol, long actor, String requestId, String scope, String sha) {
        validate(protocol, actor, requestId, sha);
        Map<String, Object> row = mapper.select(protocol, actor, requestId);
        if (row == null) return new Fact(NOT_FOUND, requestId, scope == null ? "" : scope, sha, null, null, null, false);
        return replay(row, scope == null ? "" : scope, sha);
    }

    /** 只按 ID 读回（不校验摘要），用于客户端只持有 requestId 的读回协议。 */
    public Fact readById(String protocol, long actor, String requestId) {
        if (protocol == null || actor <= 0 || !requestKey(requestId)) throw new ServiceException("请求编号无效", 400);
        Map<String, Object> row = mapper.select(protocol, actor, requestId);
        if (row == null) return new Fact(NOT_FOUND, requestId, "", null, null, null, null, false);
        return fact(row, true);
    }

    private Fact replay(Map<String, Object> row, String scope, String sha) {
        if (!Objects.equals(row.get("payloadSha256"), sha)) throw new ServiceException("REQUEST_ID_REUSED_WITH_DIFFERENT_PAYLOAD", 409);
        if (!Objects.equals(row.get("scopeKey"), scope)) throw new ServiceException("REQUEST_ID_REUSED_IN_DIFFERENT_SCOPE", 409);
        return fact(row, true);
    }

    private Fact fact(Map<String, Object> row, boolean replayed) {
        Number code = (Number) row.get("errorCode");
        Object created = row.get("updateTime");
        Long at = created instanceof java.util.Date d ? d.getTime() : created instanceof java.time.LocalDateTime t
                ? t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
        return new Fact((String) row.get("state"), (String) row.get("requestId"), (String) row.get("scopeKey"),
                (String) row.get("payloadSha256"), read((String) row.get("resultJson")), code == null ? null : code.intValue(),
                (String) row.get("errorMessage"), replayed, ((Number) row.get("id")).longValue(), at);
    }

    static void validate(String protocol, long actor, String requestId, String sha) {
        if (protocol == null || protocol.isEmpty() || actor <= 0) throw new ServiceException("请先登录", 401);
        if (!requestKey(requestId)) throw new ServiceException("请求编号无效", 400);
        if (!sha256(sha)) throw new ServiceException("请求摘要无效", 400);
    }

    public static boolean requestId(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    /** 台账键：小写 UUID 之外，也接受 App 社区协议的 [A-Za-z0-9_.:-]{1,64} 幂等键。 */
    public static boolean requestKey(String value) { return value != null && value.matches("[A-Za-z0-9_.:-]{1,64}"); }

    public static boolean sha256(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder s = new StringBuilder();
            for (byte b : d) s.append(String.format("%02x", b));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static String sha256Hex(String text) { return sha256Hex(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    private static Map<String, Object> row(String protocol, long actor, String requestId, String scope, String sha, String state) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("protocol", protocol); row.put("actor", actor); row.put("requestId", requestId); row.put("scope", scope);
        row.put("sha", sha); row.put("state", state); row.put("resultJson", null); row.put("errorCode", null); row.put("errorMessage", null);
        return row;
    }

    private static String trim(String message) {
        if (message == null) return null;
        return message.length() > 255 ? message.substring(0, 255) : message;
    }

    static String write(Map<String, Object> value) {
        try { return value == null ? null : JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("original result not serializable", e); }
    }

    static Map<String, Object> read(String json) {
        try { return json == null ? null : JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("original result unreadable", e); }
    }
}

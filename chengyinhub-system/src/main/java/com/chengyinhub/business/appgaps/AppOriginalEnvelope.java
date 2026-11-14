package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

/**
 * App「原始请求信封」协议（payloadBase64Chunks + payloadSHA256 + 小写 UUID requestId）：
 * <ul>
 *   <li>original-activity-create-request-v1：/api/activity/create-requests/submit|read|resume-post-processing，原写 = /api/activity/publish</li>
 *   <li>original-template-author-request-v1：/api/template/author-requests/submit|read，原写 = /api/template/draft|publish</li>
 * </ul>
 * 服务端从 base64 原字节重算摘要，不信任客户端；同 requestId 只执行一次原写（台账），回执是原结果的不可变投影。
 * 没有后置异步处理（媒体/抽检在原写里同步完成），所以 processing/postProcessing 恒为 null，resume 等同 read。
 */
@Service
public class AppOriginalEnvelope {
    public static final String ACTIVITY = "original-activity-create-request-v1";
    public static final String TEMPLATE = "original-template-author-request-v1";
    public static final String PROJECT = "original-project-state-request-v1";
    static final int MAX_BYTES = 2_097_152, MAX_CHUNKS = 86, CHUNK = 32_768;
    private final AppOriginalRequestLedger ledger;

    public AppOriginalEnvelope(AppOriginalRequestLedger ledger) { this.ledger = ledger; }

    /** 已校验的原始请求：原字节解出的 JSON 对象。 */
    public record Original(String protocol, String requestId, String sha, Map<String, Object> payload) {}

    static boolean nullOrAbsent(Map<String, Object> body, String key) { return !body.containsKey(key) || body.get(key) == null; }

    public static Original parseSubmit(String protocol, Map<String, Object> body) {
        if (body == null || !protocol.equals(body.get("protocolVersion"))) throw new ServiceException("协议版本不符", 400);
        Set<String> allowed = new HashSet<>(Set.of("protocolVersion", "requestId", "payloadSHA256", "payloadBase64Chunks"));
        if (ACTIVITY.equals(protocol)) allowed.addAll(Set.of("merchantId", "ownerId", "actorId"));
        if (!allowed.containsAll(body.keySet())) throw new ServiceException("原始请求含未知字段", 400);
        if (ACTIVITY.equals(protocol) && !(nullOrAbsent(body, "merchantId") && nullOrAbsent(body, "ownerId") && nullOrAbsent(body, "actorId")))
            throw new ServiceException("只支持本人俱乐部活动创建", 400);
        String rid = body.get("requestId") instanceof String s ? s : null, sha = body.get("payloadSHA256") instanceof String h ? h : null;
        if (!AppOriginalRequestLedger.requestId(rid) || !AppOriginalRequestLedger.sha256(sha)) throw new ServiceException("请求编号或摘要无效", 400);
        if (!(body.get("payloadBase64Chunks") instanceof List<?> chunks) || chunks.isEmpty() || chunks.size() > MAX_CHUNKS)
            throw new ServiceException("原始请求体无效", 400);
        StringBuilder joined = new StringBuilder();
        for (Object c : chunks) {
            if (!(c instanceof String s) || s.isEmpty() || s.length() > CHUNK) throw new ServiceException("原始请求体无效", 400);
            joined.append(s);
        }
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(joined.toString()); }
        catch (IllegalArgumentException e) { throw new ServiceException("原始请求体不是 base64", 400); }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new ServiceException("原始请求体大小无效", 400);
        if (!AppOriginalRequestLedger.sha256Hex(bytes).equals(sha)) throw new ServiceException("请求摘要与内容不符", 400);
        Map<String, Object> payload;
        try { payload = AppOriginalRequestLedger.JSON.readValue(bytes, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new ServiceException("原始请求体不是 JSON 对象", 400); }
        if (ACTIVITY.equals(protocol) && (payload.containsKey("id") || payload.containsKey("scope") || payload.containsKey("requestId")))
            throw new ServiceException("原始活动请求不得指定 id/scope/requestId", 400);
        if (PROJECT.equals(protocol) && ProjectStateRequests.command(payload) == null)
            throw new ServiceException("原始状态请求不符合协议", 400);
        if (TEMPLATE.equals(protocol)) {
            if (!payload.keySet().equals(Set.of("originalPath", "fields")) || !(payload.get("fields") instanceof Map)
                    || !("api/template/draft".equals(payload.get("originalPath")) || "api/template/publish".equals(payload.get("originalPath"))))
                throw new ServiceException("原始模板请求只支持个人草稿/发布", 400);
        }
        return new Original(protocol, rid, sha, payload);
    }

    /** 执行原写：writer 返回旧 AjaxResult，200 视为提交，其余为业务拒绝。 */
    public Map<String, Object> submit(long actor, Original original, Function<Original, AjaxResult> writer) {
        AppOriginalRequestLedger.Fact fact = ledger.run(original.protocol(), actor, original.requestId(), "", original.sha(), () -> {
            Object data = AppOriginalRequestLedger.requireOk(writer.apply(original)).get(AjaxResult.DATA_TAG);
            Long id = data instanceof Number n ? n.longValue() : data instanceof String s && s.matches("\\d{1,18}") ? Long.valueOf(s) : null;
            if (id == null || id <= 0) throw new IllegalStateException("original writer returned no resource id");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(ACTIVITY.equals(original.protocol()) ? "activityId" : "memberTemplateId", id);
            return result;
        });
        return receipt(original.protocol(), original.requestId(), original.sha(), fact);
    }

    public Map<String, Object> read(long actor, String protocol, Map<String, Object> body) {
        if (body == null || !protocol.equals(body.get("protocolVersion"))) throw new ServiceException("协议版本不符", 400);
        Set<String> allowed = new HashSet<>(Set.of("protocolVersion", "requestId", "payloadSHA256"));
        if (ACTIVITY.equals(protocol)) allowed.addAll(Set.of("merchantId", "ownerId", "actorId"));
        if (!allowed.containsAll(body.keySet())) throw new ServiceException("读回参数含未知字段", 400);
        String rid = body.get("requestId") instanceof String s ? s : null, sha = body.get("payloadSHA256") instanceof String h ? h : null;
        if (!AppOriginalRequestLedger.requestId(rid) || !AppOriginalRequestLedger.sha256(sha)) throw new ServiceException("请求编号或摘要无效", 400);
        return receipt(protocol, rid, sha, ledger.read(protocol, actor, rid, "", sha));
    }

    static Map<String, Object> receipt(String protocol, String rid, String sha, AppOriginalRequestLedger.Fact fact) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", protocol); r.put("requestId", rid); r.put("payloadSHA256", sha);
        if (ACTIVITY.equals(protocol)) { r.put("merchantId", null); r.put("ownerId", null); r.put("actorId", null); }
        else r.put("merchant", null);
        String state = fact.committed() ? "COMMITTED" : AppOriginalRequestLedger.REJECTED.equals(fact.state()) ? "REJECTED" : "NOT_FOUND";
        r.put("state", state);
        if (fact.committed()) r.put("originalResult", fact.result());
        else if ("REJECTED".equals(state)) {
            Map<String, Object> rejected = new LinkedHashMap<>();
            rejected.put("code", fact.errorCode()); rejected.put("msg", fact.errorMessage());
            r.put("originalResult", rejected);
        } else r.put("originalResult", null);
        r.put("auditState", null); r.put("auditActions", null); r.put("postProcessing", null); r.put("processing", null);
        return r;
    }
}

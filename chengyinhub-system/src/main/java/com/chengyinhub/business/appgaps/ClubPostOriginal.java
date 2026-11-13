package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 俱乐部动态/评论「原始操作」协议 club-post-v1：
 * /api/club/post/operation/{create,update,receipt}、/api/club/post/operation/comment/{create,receipt}。
 * 写入复用现有 /api/club/post/create|update、/post/comment/create 的全部校验，原 requestId 只执行一次；
 * 读回只认本协议台账，不把旧事件或当前帖子冒充原始回执——查不到即 PENDING。
 */
@Service
public class ClubPostOriginal {
    public static final String NAMESPACE = "club-post-v1";
    static final List<String> IDENTITY = List.of("receiptNamespace", "operationId", "requestId", "operationKind", "clubId", "postId", "commentId", "version");
    private final AppOriginalRequestLedger ledger;

    public ClubPostOriginal(AppOriginalRequestLedger ledger) { this.ledger = ledger; }

    /** 现有写接口（由控制器提供），返回旧 AjaxResult。 */
    public interface Writers {
        AjaxResult create(long clubId, String content, String images);
        AjaxResult update(long postId, String content, String images, int version, String requestId);
        AjaxResult comment(long postId, String content);
    }

    record Identity(String operationId, String requestId, String kind, long clubId, long postId, int version) {
        Map<String, Object> echo() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("receiptNamespace", NAMESPACE); m.put("operationId", operationId); m.put("requestId", requestId);
            m.put("operationKind", kind); m.put("clubId", clubId); m.put("postId", postId); m.put("commentId", 0); m.put("version", version);
            return m;
        }
        String scope() { return "club:" + clubId + ":post:" + postId + ":" + kind + ":" + operationId; }
    }

    /** suffix: create/update/receipt（动态）或 comment-create/comment-receipt（评论）。 */
    static Identity identity(String suffix, Map<String, Object> body) {
        if (body == null || !NAMESPACE.equals(body.get("receiptNamespace"))) return null;
        Set<String> keys = new HashSet<>(IDENTITY);
        boolean comment = suffix.startsWith("comment-");
        if (suffix.endsWith("create") || "update".equals(suffix)) { keys.add("content"); if (!comment) keys.add("images"); }
        if (!body.keySet().equals(keys)) return null;
        if (!(body.get("operationId") instanceof String op) || !op.matches("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")) return null;
        if (!(body.get("requestId") instanceof String rid) || !AppOriginalRequestLedger.requestKey(rid)) return null;
        if (!(body.get("operationKind") instanceof String kind)) return null;
        Long club = nonNegative(body.get("clubId")), post = nonNegative(body.get("postId")), commentId = nonNegative(body.get("commentId")),
                version = nonNegative(body.get("version"));
        if (club == null || club <= 0 || post == null || commentId == null || commentId != 0 || version == null || version > Integer.MAX_VALUE) return null;
        if (comment) { if (!"comment".equals(kind) || post <= 0) return null; }
        else if ("create".equals(kind)) { if (post != 0 || version != 0 || !("create".equals(suffix) || "receipt".equals(suffix))) return null; }
        else if ("edit".equals(kind)) { if (post <= 0 || !("update".equals(suffix) || "receipt".equals(suffix))) return null; }
        else return null;
        if (body.containsKey("content") && !(body.get("content") instanceof String)) return null;
        if (body.containsKey("images") && !(body.get("images") instanceof String)) return null;
        return new Identity(op, rid, kind, club, post, version.intValue());
    }

    public AjaxResult write(long actor, String suffix, Map<String, Object> body, Writers writers) {
        Identity id = identity(suffix, body);
        if (id == null || "receipt".equals(suffix) || "comment-receipt".equals(suffix)) return AjaxResult.error(400, "原始操作参数无效");
        String content = (String) body.get("content");
        String images = body.containsKey("images") ? (String) body.get("images") : "";
        if ("comment".equals(id.kind()) && (content.isEmpty() || content.length() > 300 || !content.equals(content.strip())))
            return AjaxResult.error(400, "评论内容无效");
        String sha = AppOriginalRequestLedger.sha256Hex(String.join("\u001f", NAMESPACE, id.kind(), String.valueOf(id.clubId()),
                String.valueOf(id.postId()), String.valueOf(id.version()), AppOriginalRequestLedger.sha256Hex(content), AppOriginalRequestLedger.sha256Hex(images)));
        AppOriginalRequestLedger.Fact fact;
        try {
            fact = ledger.run(NAMESPACE, actor, id.requestId(), id.scope(), sha, () -> {
                Map<String, Object> r = new LinkedHashMap<>();
                switch (id.kind()) {
                    case "create" -> {
                        Map<?, ?> data = data(AppOriginalRequestLedger.requireOk(writers.create(id.clubId(), content, images)));
                        r.put("resultPostId", num(data.get("id"))); r.put("resultVersion", data.get("version") == null ? 0L : num(data.get("version")));
                    }
                    case "edit" -> {
                        Object updated = AppOriginalRequestLedger.requireOk(writers.update(id.postId(), content, images, id.version(), id.requestId()))
                                .get(AjaxResult.DATA_TAG);
                        Map<?, ?> data = updated instanceof Map<?, ?> m ? m : AppOriginalRequestLedger.JSON.convertValue(updated, Map.class);
                        r.put("resultPostId", num(data.get("id"))); r.put("resultVersion", num(data.get("version")));
                    }
                    default -> {
                        Map<?, ?> data = data(AppOriginalRequestLedger.requireOk(writers.comment(id.postId(), content)));
                        r.put("resultPostId", id.postId()); r.put("resultVersion", (long) id.version()); r.put("resultCommentId", num(data.get("id")));
                    }
                }
                if (r.get("resultPostId") == null || r.get("resultVersion") == null) throw new IllegalStateException("legacy writer returned no result identity");
                return r;
            });
        } catch (ServiceException conflict) {
            return AjaxResult.error(conflict.getCode() == null ? 409 : conflict.getCode(), conflict.getMessage());
        }
        return AjaxResult.success(present(id, fact));
    }

    public AjaxResult receipt(long actor, String suffix, Map<String, Object> body) {
        Identity id = identity(suffix, body);
        if (id == null) return AjaxResult.error(400, "原始操作参数无效");
        AppOriginalRequestLedger.Fact fact = ledger.readById(NAMESPACE, actor, id.requestId());
        if (!AppOriginalRequestLedger.NOT_FOUND.equals(fact.state()) && !id.scope().equals(fact.scope()))
            return AjaxResult.error(409, "REQUEST_ID_REUSED_IN_DIFFERENT_SCOPE");
        return AjaxResult.success(present(id, fact));
    }

    static Map<String, Object> present(Identity id, AppOriginalRequestLedger.Fact fact) {
        Map<String, Object> out = id.echo();
        if (fact.committed()) {
            out.put("status", "ACKNOWLEDGED"); out.put("sourceSHA256", fact.payloadSha256()); out.putAll(fact.result());
        } else if (AppOriginalRequestLedger.REJECTED.equals(fact.state())) {
            int code = fact.errorCode() == null || fact.errorCode() <= 0 || fact.errorCode() == 200 ? 500 : fact.errorCode();
            out.put("status", "REJECTED"); out.put("sourceSHA256", fact.payloadSha256()); out.put("rejectedCode", code);
        } else {
            out.put("status", "PENDING");
        }
        return out;
    }

    private static Map<?, ?> data(AjaxResult r) {
        Object d = r.get(AjaxResult.DATA_TAG);
        if (!(d instanceof Map<?, ?> m)) throw new IllegalStateException("legacy writer returned no data");
        return m;
    }

    private static Long num(Object o) { return o instanceof Number n ? n.longValue() : null; }

    private static Long nonNegative(Object o) {
        if (o instanceof Integer || o instanceof Long) { long v = ((Number) o).longValue(); return v >= 0 ? v : null; }
        return null;
    }
}

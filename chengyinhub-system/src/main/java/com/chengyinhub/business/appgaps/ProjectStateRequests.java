package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 创作者「项目状态原始请求」：/api/project/state-requests/context|submit|read。
 * context 从 /api/project/my 同一份行投影算 title/online 与基线摘要；submit 在执行前重算基线，
 * 不一致即拒绝（不对陈旧列表执行删除/上下架）；同 requestId 只执行一次原写（topic/activity/template 的 delete 与上下架）。
 */
@Service
public class ProjectStateRequests {
    public static final String PROTOCOL = AppOriginalEnvelope.PROJECT;
    static final ObjectMapper CANON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final AppOriginalRequestLedger ledger;

    public ProjectStateRequests(AppOriginalRequestLedger ledger) { this.ledger = ledger; }

    /** /api/project/my 的行（bizType+id 定位）与原表单写。 */
    public interface Projects {
        Map<String, Object> row(String kind, long id);
        AjaxResult write(String originalPath, Map<String, String> form);
    }

    public record Command(String originalPath, long resourceId, String baselineHash, boolean expectedOnline, Map<String, Object> fields) {
        String kind() { return ProjectStateRequests.kind(originalPath); }
    }

    static String kind(String path) {
        return switch (path == null ? "" : path) {
            case "api/template/delete", "api/template/updateLibraryStatus" -> "template";
            case "api/topic/delete", "api/topic/update_user_status" -> "topic";
            case "api/activity/delete", "api/activity/update_publish_status" -> "activity";
            default -> null;
        };
    }

    /** 与 App ProjectStateRequestContract.Command.valid 同一规则。 */
    static Command command(Map<String, Object> p) {
        if (p == null || !p.keySet().equals(Set.of("originalPath", "resourceId", "baselineHash", "expectedOnline", "fields"))) return null;
        if (!(p.get("originalPath") instanceof String path) || kind(path) == null) return null;
        if (!(p.get("resourceId") instanceof Number n) || n.longValue() <= 0 || !(p.get("baselineHash") instanceof String hash)
                || !AppOriginalRequestLedger.sha256(hash) || !(p.get("expectedOnline") instanceof Boolean online) || !(p.get("fields") instanceof Map<?, ?> f)) return null;
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("template".equals(kind(path)) ? "template_id" : "id", String.valueOf(n.longValue()));
        if ("api/topic/update_user_status".equals(path)) expected.put("expectedUserStatus", online ? "1" : "0");
        if ("api/template/updateLibraryStatus".equals(path)) expected.put("publish_status", online ? "0" : "1");
        if (!expected.equals(f)) return null;
        @SuppressWarnings("unchecked") Map<String, Object> fields = (Map<String, Object>) f;
        return new Command(path, n.longValue(), hash, online, fields);
    }

    static boolean online(Map<String, Object> row) {
        Object state = row.get("state");
        return "online".equals(String.valueOf(row.get("publishStatus"))) || "running".equals(state) || "notStarted".equals(state);
    }

    static String baseline(Map<String, Object> row) {
        try { return AppOriginalRequestLedger.sha256Hex(CANON.writeValueAsBytes(row)); }
        catch (Exception e) { throw new IllegalStateException("project row not serializable", e); }
    }

    public Map<String, Object> context(Map<String, Object> body, Projects projects) {
        if (body == null || !body.keySet().equals(Set.of("kind", "resourceId")) || !(body.get("kind") instanceof String kind)
                || !Set.of("topic", "activity", "template").contains(kind) || !(body.get("resourceId") instanceof Number n) || n.longValue() <= 0)
            throw new ServiceException("参数无效", 400);
        Map<String, Object> row = projects.row(kind, n.longValue());
        if (row == null) throw new ServiceException("项目不存在或不属于你", 404);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("protocolVersion", PROTOCOL); c.put("kind", kind); c.put("resourceId", n.longValue());
        c.put("title", row.get("title") == null ? "" : String.valueOf(row.get("title")));
        c.put("online", online(row)); c.put("baselineHash", baseline(row));
        return c;
    }

    public Map<String, Object> submit(long actor, Map<String, Object> body, Projects projects) {
        AppOriginalEnvelope.Original original = AppOriginalEnvelope.parseSubmit(PROTOCOL, body);
        Command cmd = command(original.payload());
        AppOriginalRequestLedger.Fact fact = ledger.run(PROTOCOL, actor, original.requestId(), cmd.kind() + ":" + cmd.resourceId(), original.sha(), () -> {
            Map<String, Object> row = projects.row(cmd.kind(), cmd.resourceId());
            if (row == null) throw new ServiceException("项目不存在或不属于你", 404);
            if (!baseline(row).equals(cmd.baselineHash()) || online(row) != cmd.expectedOnline())
                throw new ServiceException("项目状态已变化，原请求未执行", 409);
            Map<String, String> form = new LinkedHashMap<>();
            cmd.fields().forEach((k, v) -> form.put(k, String.valueOf(v)));
            if ("api/template/updateLibraryStatus".equals(cmd.originalPath())) {
                // 现有接口参数是 status（1 上架 / 3 下架），App 原始字段 publish_status 是「目标是否下线」。
                form.remove("publish_status"); form.put("status", cmd.expectedOnline() ? "3" : "1");
            }
            AjaxResult ok = AppOriginalRequestLedger.requireOk(projects.write(cmd.originalPath(), form));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("applied", true); r.put("msg", ok.get(AjaxResult.MSG_TAG));
            return r;
        });
        return receipt(original.requestId(), original.sha(), cmd, fact);
    }

    public Map<String, Object> read(long actor, Map<String, Object> body) {
        if (body == null || !body.keySet().equals(Set.of("protocolVersion", "requestId", "payloadSHA256", "originalPath", "resourceId", "baselineHash", "expectedOnline"))
                || !PROTOCOL.equals(body.get("protocolVersion"))) throw new ServiceException("读回参数无效", 400);
        String rid = body.get("requestId") instanceof String s ? s : null, sha = body.get("payloadSHA256") instanceof String h ? h : null;
        Object path = body.get("originalPath");
        if (!AppOriginalRequestLedger.requestId(rid) || !AppOriginalRequestLedger.sha256(sha) || !(path instanceof String p) || kind(p) == null
                || !(body.get("resourceId") instanceof Number n) || n.longValue() <= 0) throw new ServiceException("读回参数无效", 400);
        Command cmd = new Command(p, n.longValue(), String.valueOf(body.get("baselineHash")), Boolean.TRUE.equals(body.get("expectedOnline")), Map.of());
        return receipt(rid, sha, cmd, ledger.read(PROTOCOL, actor, rid, cmd.kind() + ":" + cmd.resourceId(), sha));
    }

    static Map<String, Object> receipt(String rid, String sha, Command cmd, AppOriginalRequestLedger.Fact fact) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", PROTOCOL); r.put("requestId", rid); r.put("payloadSHA256", sha);
        r.put("originalPath", cmd.originalPath()); r.put("kind", cmd.kind()); r.put("resourceId", cmd.resourceId());
        if (fact.committed()) { r.put("state", "COMMITTED"); r.put("originalResult", fact.result()); }
        else if (AppOriginalRequestLedger.REJECTED.equals(fact.state())) {
            Map<String, Object> rejected = new LinkedHashMap<>();
            rejected.put("applied", false); rejected.put("code", fact.errorCode()); rejected.put("msg", fact.errorMessage());
            r.put("state", "REJECTED"); r.put("originalResult", rejected);
        } else { r.put("state", "NOT_FOUND"); r.put("originalResult", null); }
        return r;
    }
}

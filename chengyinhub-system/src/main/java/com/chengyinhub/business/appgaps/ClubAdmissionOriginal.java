package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * App 入会/退会「原始回执」（/api/club/join、/api/club/quit 带 X-Club-Admission-* 头，/api/club/admission/receipt 读回）。
 * 不带头的旧调用不经过这里。回执是不可变审计投影，不是当前成员关系的观察。
 */
@Service
public class ClubAdmissionOriginal {
    public static final String PROTOCOL = "club-admission-v1";
    private final AppOriginalRequestLedger ledger;

    public ClubAdmissionOriginal(AppOriginalRequestLedger ledger) { this.ledger = ledger; }

    public static String digest(String action, String message) {
        return AppOriginalRequestLedger.sha256Hex(action + "\n" + (message == null ? "" : message));
    }

    /** 带原始头的写：同 requestId 只执行一次 legacy 写；旧写返回非 200 视为业务拒绝并记录。 */
    public AjaxResult write(long actor, Long clubId, boolean quit, String requestId, String action, String sha,
                            String joinMessage, Supplier<AjaxResult> legacy) {
        if (!AppOriginalRequestLedger.requestId(requestId)) return AjaxResult.error(400, "请求编号无效");
        if (clubId == null || clubId <= 0) return AjaxResult.error(400, "缺少俱乐部ID");
        if (!("join".equals(action) || "apply".equals(action) || "leave".equals(action)) || quit != "leave".equals(action))
            return AjaxResult.error(400, "入会动作与接口不符");
        String message = "apply".equals(action) ? (joinMessage == null ? "" : joinMessage) : "";
        if (!"apply".equals(action) && joinMessage != null && !joinMessage.isEmpty()) return AjaxResult.error(400, "该动作不接受申请留言");
        if (!AppOriginalRequestLedger.sha256(sha) || !digest(action, message).equals(sha)) return AjaxResult.error(400, "请求摘要与内容不符");
        AppOriginalRequestLedger.Fact fact;
        try {
            fact = ledger.run(PROTOCOL, actor, requestId, "club:" + clubId, sha, () -> {
                AjaxResult result = legacy.get();
                Object code = result.get(AjaxResult.CODE_TAG);
                if (!(code instanceof Number n) || n.intValue() != 200)
                    throw new ServiceException(String.valueOf(result.get(AjaxResult.MSG_TAG)), code instanceof Number m ? m.intValue() : 500);
                String membership = quit ? "left" : stateOf(result.get(AjaxResult.DATA_TAG));
                Map<String, Object> receipt = receipt(actor, clubId, requestId, action, "APPLIED", membership, sha);
                receipt.put("message", result.get(AjaxResult.MSG_TAG));
                return receipt;
            });
        } catch (ServiceException conflict) {
            return AjaxResult.error(conflict.getCode() == null ? 409 : conflict.getCode(), conflict.getMessage());
        }
        return present(fact);
    }

    public AjaxResult receipt(long actor, Map<String, Object> body) {
        if (body == null || body.size() != 4) return AjaxResult.error(400, "读回参数无效");
        Long clubId = positive(body.get("clubId"));
        Object rid = body.get("requestId"), action = body.get("action"), sha = body.get("payloadSHA256");
        if (clubId == null || !(rid instanceof String r) || !(action instanceof String a) || !(sha instanceof String s)
                || !AppOriginalRequestLedger.requestId(r)
                || !("join".equals(a) || "apply".equals(a) || "leave".equals(a))) return AjaxResult.error(400, "读回参数无效");
        AppOriginalRequestLedger.Fact fact;
        try { fact = ledger.read(PROTOCOL, actor, r, "club:" + clubId, s); }
        catch (ServiceException e) { return AjaxResult.error(e.getCode() == null ? 409 : e.getCode(), e.getMessage()); }
        if (AppOriginalRequestLedger.NOT_FOUND.equals(fact.state()))
            return AjaxResult.success(receipt(actor, clubId, r, a, "UNKNOWN", null, s));
        if (!a.equals(fact.result() == null ? null : fact.result().get("action")) && fact.committed())
            return AjaxResult.error(409, "REQUEST_ID_REUSED_WITH_DIFFERENT_ACTION");
        return present(fact);
    }

    private static AjaxResult present(AppOriginalRequestLedger.Fact fact) {
        if (fact.committed()) {
            Map<String, Object> data = new LinkedHashMap<>(fact.result());
            Object message = data.remove("message");
            return new AjaxResult(200, message == null ? "操作成功" : String.valueOf(message), data);
        }
        return AjaxResult.error(fact.errorCode() == null ? 500 : fact.errorCode(), fact.errorMessage());
    }

    static Map<String, Object> receipt(long actor, long clubId, String requestId, String action, String state, String membership, String sha) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actorMemberId", actor); m.put("clubId", clubId); m.put("requestId", requestId); m.put("action", action);
        m.put("state", state); m.put("membershipState", membership); m.put("payloadSHA256", sha);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static String stateOf(Object data) {
        Object state = data instanceof Map<?, ?> m ? ((Map<String, Object>) m).get("state") : null;
        if (!(state instanceof String s)) throw new IllegalStateException("legacy join result missing state");
        return s;
    }

    static Long positive(Object value) {
        if (value instanceof Integer || value instanceof Long) { long v = ((Number) value).longValue(); return v > 0 ? v : null; }
        return null;
    }
}

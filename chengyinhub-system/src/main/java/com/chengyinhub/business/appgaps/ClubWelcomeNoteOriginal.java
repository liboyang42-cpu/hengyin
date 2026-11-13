package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 俱乐部欢迎语原始回执（X-Club-Welcome-Request-Id / X-Club-Welcome-Source-SHA256，
 * /api/club/open-settings/welcome-note-receipt）。APPLIED 带回读后的欢迎语，REJECTED 带原拒绝文案，
 * 两者都带不可变 auditId（台账行）与 recordedAt。未登记的请求只回身份字段 + UNKNOWN。
 */
@Service
public class ClubWelcomeNoteOriginal {
    public static final String PROTOCOL = "club-welcome-note-v1";
    private final AppOriginalRequestLedger ledger;

    public ClubWelcomeNoteOriginal(AppOriginalRequestLedger ledger) { this.ledger = ledger; }

    public static String source(long clubId, String text) {
        return AppOriginalRequestLedger.sha256Hex("CLUB_WELCOME_NOTE_V1\u001f" + clubId + "\u001f" + AppOriginalRequestLedger.sha256Hex(text));
    }

    public AjaxResult write(long actor, Long clubId, String note, String requestId, String sourceHeader, Supplier<AjaxResult> legacy) {
        if (!AppOriginalRequestLedger.requestId(requestId)) return AjaxResult.error(400, "请求编号无效");
        if (clubId == null || clubId <= 0) return AjaxResult.error(400, "缺少俱乐部ID");
        String text = note == null ? "" : note.trim();
        if (!source(clubId, text).equals(sourceHeader)) return AjaxResult.error(400, "请求摘要与内容不符");
        AppOriginalRequestLedger.Fact fact;
        try {
            fact = ledger.run(PROTOCOL, actor, requestId, "club:" + clubId, sourceHeader, () -> {
                AjaxResult ok = AppOriginalRequestLedger.requireOk(legacy.get());
                Object saved = ((Map<?, ?>) ok.get(AjaxResult.DATA_TAG)).get("welcomeNote");
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("welcomeNote", saved);
                return r;
            });
        } catch (ServiceException conflict) {
            return AjaxResult.error(conflict.getCode() == null ? 409 : conflict.getCode(), conflict.getMessage());
        }
        if (fact.committed()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("welcomeNote", fact.result().get("welcomeNote"));
            return AjaxResult.success("已保存", data);
        }
        return AjaxResult.error(fact.errorCode() == null ? 500 : fact.errorCode(), fact.errorMessage());
    }

    public AjaxResult receipt(long actor, Map<String, Object> body) {
        if (body == null || !body.keySet().equals(Set.of("clubId", "requestId", "sourceSHA256"))) return AjaxResult.error(400, "读回参数无效");
        Long clubId = ClubAdmissionOriginal.positive(body.get("clubId"));
        if (clubId == null || !(body.get("requestId") instanceof String rid) || !(body.get("sourceSHA256") instanceof String sha))
            return AjaxResult.error(400, "读回参数无效");
        AppOriginalRequestLedger.Fact fact;
        try { fact = ledger.read(PROTOCOL, actor, rid, "club:" + clubId, sha); }
        catch (ServiceException e) { return AjaxResult.error(e.getCode() == null ? 409 : e.getCode(), e.getMessage()); }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", 1); r.put("actorMemberId", actor); r.put("clubId", clubId);
        r.put("requestId", rid); r.put("sourceSHA256", sha);
        if (fact.committed()) {
            r.put("state", "APPLIED"); r.put("welcomeNote", fact.result().get("welcomeNote"));
            r.put("auditId", fact.ledgerId()); r.put("recordedAt", fact.recordedAt());
        } else if (AppOriginalRequestLedger.REJECTED.equals(fact.state())) {
            String message = fact.errorMessage() == null || fact.errorMessage().isEmpty() ? "操作失败" : fact.errorMessage();
            r.put("state", "REJECTED"); r.put("message", message);
            r.put("auditId", fact.ledgerId()); r.put("recordedAt", fact.recordedAt());
        } else {
            r.put("state", "UNKNOWN");
        }
        return AjaxResult.success(r);
    }
}

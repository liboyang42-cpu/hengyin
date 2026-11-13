package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * 俱乐部勋章创建/颁发「原始回执」（X-Club-Badge-Request-Id / X-Club-Badge-Source-SHA256 头，
 * /api/club/badge/original-receipt 读回）。sourceSHA256 由服务端按 App 同一公式从请求体重算，不信任客户端值。
 */
@Service
public class ClubBadgeOriginal {
    public static final String PROTOCOL = "club-badge-v1";
    private final AppOriginalRequestLedger ledger;

    public ClubBadgeOriginal(AppOriginalRequestLedger ledger) { this.ledger = ledger; }

    /** 结果投影所需的旧写事实。 */
    public interface Facts {
        Long badgeIdByCode(String code);
    }

    public static String sourceHash(String operation, Map<String, Object> fields) {
        Long club = ClubAdmissionOriginal.positive(fields.get("clubId"));
        if (club == null) throw new ServiceException("缺少俱乐部ID", 400);
        StringBuilder pieces = new StringBuilder("CLUB_BADGE_V1\u001f").append(operation).append('\u001f').append(club);
        if ("CREATE".equals(operation)) {
            Set<String> keys = fields.containsKey("badgeDesc") ? Set.of("clubId", "badgeName", "badgeDesc") : Set.of("clubId", "badgeName");
            Object name = fields.get("badgeName"), desc = fields.get("badgeDesc");
            if (!fields.keySet().equals(keys) || !(name instanceof String n) || n.isEmpty() || n.length() > 120 || !n.equals(n.strip())
                    || (desc != null && !(desc instanceof String))) throw new ServiceException("勋章参数无效", 400);
            String d = desc == null ? "" : (String) desc;
            if (d.length() > 255 || !d.equals(d.strip())) throw new ServiceException("勋章说明无效", 400);
            pieces.append('\u001f').append(AppOriginalRequestLedger.sha256Hex(n)).append('\u001f').append(AppOriginalRequestLedger.sha256Hex(d));
        } else if ("GRANT".equals(operation)) {
            Long member = ClubAdmissionOriginal.positive(fields.get("memberId"));
            Object code = fields.get("badgeCode");
            if (!fields.keySet().equals(Set.of("clubId", "memberId", "badgeCode")) || member == null || !(code instanceof String c) || !validCode(c))
                throw new ServiceException("颁发参数无效", 400);
            pieces.append('\u001f').append(member).append('\u001f').append(AppOriginalRequestLedger.sha256Hex(c));
        } else throw new ServiceException("未知操作", 400);
        return AppOriginalRequestLedger.sha256Hex(pieces.toString());
    }

    static boolean validCode(String v) {
        return !v.isEmpty() && v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255 && v.equals(v.strip())
                && v.codePoints().noneMatch(Character::isISOControl);
    }

    public AjaxResult write(long actor, String operation, String requestId, String sourceHeader, Map<String, Object> body,
                            Facts facts, Supplier<AjaxResult> legacy) {
        if (!AppOriginalRequestLedger.requestId(requestId)) return AjaxResult.error(400, "请求编号无效");
        String sha;
        try { sha = sourceHash(operation, body == null ? Map.of() : body); }
        catch (ServiceException e) { return AjaxResult.error(400, e.getMessage()); }
        if (!sha.equals(sourceHeader)) return AjaxResult.error(400, "请求摘要与内容不符");
        long clubId = ClubAdmissionOriginal.positive(body.get("clubId"));
        AppOriginalRequestLedger.Fact fact;
        try {
            fact = ledger.run(PROTOCOL, actor, requestId, scope(clubId, operation), sha, (LongFunction<Map<String, Object>>) auditId -> {
                AjaxResult result = legacy.get();
                Object code = result.get(AjaxResult.CODE_TAG);
                if (!(code instanceof Number n) || n.intValue() != 200)
                    throw new ServiceException(String.valueOf(result.get(AjaxResult.MSG_TAG)), code instanceof Number m ? m.intValue() : 500);
                Map<String, Object> r = base(actor, clubId, requestId, operation, sha);
                if ("CREATE".equals(operation)) {
                    Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
                    r.put("state", "CREATED"); r.put("badgeId", ((Number) data.get("id")).longValue());
                    r.put("badgeCode", data.get("badgeCode")); r.put("memberId", null);
                } else {
                    String badgeCode = (String) body.get("badgeCode");
                    r.put("state", "已颁发过".equals(result.get(AjaxResult.MSG_TAG)) ? "ALREADY_GRANTED" : "GRANTED");
                    r.put("badgeId", facts.badgeIdByCode(badgeCode)); r.put("badgeCode", badgeCode);
                    r.put("memberId", ClubAdmissionOriginal.positive(body.get("memberId")));
                }
                r.put("appliedAt", System.currentTimeMillis()); r.put("auditId", auditId);
                return r;
            });
        } catch (ServiceException conflict) {
            return AjaxResult.error(conflict.getCode() == null ? 409 : conflict.getCode(), conflict.getMessage());
        }
        return present(fact);
    }

    public AjaxResult receipt(long actor, Map<String, Object> body) {
        if (body == null || !body.keySet().equals(Set.of("clubId", "requestId", "operation", "sourceSHA256"))) return AjaxResult.error(400, "读回参数无效");
        Long clubId = ClubAdmissionOriginal.positive(body.get("clubId"));
        if (clubId == null || !(body.get("requestId") instanceof String rid) || !(body.get("operation") instanceof String op)
                || !("CREATE".equals(op) || "GRANT".equals(op)) || !(body.get("sourceSHA256") instanceof String sha)) return AjaxResult.error(400, "读回参数无效");
        AppOriginalRequestLedger.Fact fact;
        try { fact = ledger.read(PROTOCOL, actor, rid, scope(clubId, op), sha); }
        catch (ServiceException e) { return AjaxResult.error(e.getCode() == null ? 409 : e.getCode(), e.getMessage()); }
        if (AppOriginalRequestLedger.NOT_FOUND.equals(fact.state())) {
            Map<String, Object> r = base(actor, clubId, rid, op, sha);
            r.put("state", "UNKNOWN"); r.put("badgeId", null); r.put("badgeCode", null); r.put("memberId", null);
            r.put("appliedAt", null); r.put("auditId", null);
            return AjaxResult.success(r);
        }
        return present(fact);
    }

    private static AjaxResult present(AppOriginalRequestLedger.Fact fact) {
        if (fact.committed()) return AjaxResult.success(fact.result());
        return AjaxResult.error(fact.errorCode() == null ? 500 : fact.errorCode(), fact.errorMessage());
    }

    private static String scope(long clubId, String op) { return "club:" + clubId + ":" + op; }

    private static Map<String, Object> base(long actor, long clubId, String rid, String op, String sha) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", 1); r.put("clubId", clubId); r.put("actorMemberId", actor); r.put("requestId", rid);
        r.put("operation", op); r.put("sourceSHA256", sha);
        return r;
    }
}

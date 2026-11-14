package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.ClubOriginalReceiptMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 委派/撤销角色的原始结果读回（/api/club/roles/assign-receipt、/revoke-receipt）。
 * 写入侧已有：/api/club/roles/assign|revoke 以 requestId 把结果写进 business_audit_event（同事务、带重放校验），
 * 这里只按原 requestId 读这份审计事实，不读当前委派状态，不新建任何表。
 */
@Service
public class ClubRoleOriginalReceipts {
    private final ClubOriginalReceiptMapper mapper;

    public ClubRoleOriginalReceipts(ClubOriginalReceiptMapper mapper) { this.mapper = mapper; }

    static Set<String> roles(Long activity) {
        return activity == null ? Set.of("CLUB_CO_OWNER", "CLUB_OPERATOR") : Set.of("EVENT_LEAD", "EVENT_CHECKIN");
    }

    public AjaxResult assign(long principal, Map<String, Object> body) {
        if (body == null) return bad();
        Set<String> keys = new HashSet<>(Set.of("clubId", "targetMemberId", "roleCode", "requestId", "expiresAtEpochMs"));
        Long club = ClubAdmissionOriginal.positive(body.get("clubId")), activity = null;
        if (body.containsKey("activityId")) { activity = ClubAdmissionOriginal.positive(body.get("activityId")); keys.add("activityId"); if (activity == null) return bad(); }
        Long target = ClubAdmissionOriginal.positive(body.get("targetMemberId"));
        if (!body.keySet().equals(keys) || club == null || target == null || body.get("expiresAtEpochMs") != null
                || !(body.get("roleCode") instanceof String role) || !roles(activity).contains(role)
                || !(body.get("requestId") instanceof String rid) || !AppOriginalRequestLedger.requestId(rid)) return bad();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("clubId", club); r.put("activityId", activity); r.put("targetMemberId", target); r.put("requestId", rid);
        r.put("roleCode", role); r.put("state", "UNKNOWN"); r.put("expiresAtEpochMs", null);
        r.put("assignmentId", null); r.put("version", null); r.put("auditId", null); r.put("appliedAt", null);
        Map<String, Object> audit = mapper.selectAudit(club, "CLUB_ROLE_ASSIGNED", rid);
        if (audit == null) return AjaxResult.success(r);
        if (!Objects.equals(num(audit.get("actorMemberId")), principal)) return AjaxResult.error(403, "无权读取他人的原始请求");
        Long assignmentId = num(audit.get("relationId"));
        Map<String, Object> a = assignmentId == null ? null : mapper.selectAssignment(assignmentId);
        if (a == null || !Objects.equals(num(a.get("clubId")), club) || !Objects.equals(num(a.get("targetMemberId")), target)
                || !Objects.equals(num(audit.get("targetId")), target) || !role.equals(a.get("roleCode"))
                || !scopeMatches(a, club, activity) || a.get("expiresAt") != null)
            return AjaxResult.error(409, "REQUEST_ID_REUSED_WITH_DIFFERENT_PAYLOAD");
        r.put("state", "APPLIED"); r.put("assignmentId", assignmentId); r.put("version", 0);
        r.put("auditId", num(audit.get("id"))); r.put("appliedAt", millis(audit.get("createTime")));
        return AjaxResult.success(r);
    }

    public AjaxResult revoke(long principal, Map<String, Object> body) {
        if (body == null) return bad();
        Set<String> keys = new HashSet<>(Set.of("clubId", "assignmentId", "version", "requestId", "targetMemberId", "roleCode", "reasonSHA256"));
        Long club = ClubAdmissionOriginal.positive(body.get("clubId")), activity = null;
        if (body.containsKey("activityId")) { activity = ClubAdmissionOriginal.positive(body.get("activityId")); keys.add("activityId"); if (activity == null) return bad(); }
        Long assignmentId = ClubAdmissionOriginal.positive(body.get("assignmentId")), target = ClubAdmissionOriginal.positive(body.get("targetMemberId"));
        Object version = body.get("version");
        if (!body.keySet().equals(keys) || club == null || assignmentId == null || target == null
                || !(version instanceof Integer v) || v < 0
                || !(body.get("roleCode") instanceof String role) || !roles(activity).contains(role)
                || !(body.get("reasonSHA256") instanceof String reasonSha) || !AppOriginalRequestLedger.sha256(reasonSha)
                || !(body.get("requestId") instanceof String rid) || !AppOriginalRequestLedger.requestId(rid)) return bad();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("clubId", club); r.put("activityId", activity); r.put("assignmentId", assignmentId); r.put("targetMemberId", target);
        r.put("version", v); r.put("requestId", rid); r.put("roleCode", role); r.put("reasonSHA256", reasonSha);
        r.put("state", "UNKNOWN"); r.put("auditId", null); r.put("appliedAt", null);
        Map<String, Object> audit = mapper.selectAudit(club, "CLUB_ROLE_REVOKED", rid);
        if (audit == null) return AjaxResult.success(r);
        if (!Objects.equals(num(audit.get("actorMemberId")), principal)) return AjaxResult.error(403, "无权读取他人的原始请求");
        Map<String, Object> a = mapper.selectAssignment(assignmentId);
        String reason = audit.get("reason") == null ? "" : String.valueOf(audit.get("reason"));
        if (!Objects.equals(num(audit.get("relationId")), assignmentId) || !Objects.equals(num(audit.get("targetId")), target)
                || !AppOriginalRequestLedger.sha256Hex(reason).equals(reasonSha) || a == null || !role.equals(a.get("roleCode"))
                || !Objects.equals(num(a.get("clubId")), club) || !scopeMatches(a, club, activity))
            return AjaxResult.error(409, "REQUEST_ID_REUSED_WITH_DIFFERENT_PAYLOAD");
        r.put("state", "APPLIED"); r.put("auditId", num(audit.get("id"))); r.put("appliedAt", millis(audit.get("createTime")));
        return AjaxResult.success(r);
    }

    private static boolean scopeMatches(Map<String, Object> a, long club, Long activity) {
        return activity == null ? "CLUB".equals(a.get("scopeType")) && Objects.equals(num(a.get("scopeId")), club)
                : "EVENT".equals(a.get("scopeType")) && Objects.equals(num(a.get("scopeId")), activity);
    }

    static Long num(Object o) { return o instanceof Number n ? n.longValue() : null; }

    static Long millis(Object o) {
        if (o instanceof Date d) return d.getTime();
        if (o instanceof java.time.LocalDateTime t) return t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        return null;
    }

    private static AjaxResult bad() { return AjaxResult.error(400, "读回参数无效"); }
}

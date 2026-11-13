package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.AppOriginalRequestMapper;
import com.chengyinhub.business.appgaps.mapper.ClubOriginalReceiptMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 俱乐部原始回执真库 IT：角色回执读 business_audit_event/club_role_assignment 真表，欢迎语/入会走真台账。 */
@EnabledIfEnvironmentVariable(named = "APPGAPS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClubOriginalsMySqlIT {
    static final long CLUB = 990001L, OWNER = 990101L, TARGET = 990102L;
    AppGapsMySql my; ClubRoleOriginalReceipts roles; AppOriginalRequestLedger ledger;

    @BeforeAll void setup() throws Exception {
        my = new AppGapsMySql("migration_20261113_appgaps_original_request.sql");
        roles = new ClubRoleOriginalReceipts(my.mapper(ClubOriginalReceiptMapper.class, "mapper/business/appgaps/ClubOriginalReceiptMapper.xml"));
        ledger = new AppOriginalRequestLedger(my.mapper(AppOriginalRequestMapper.class, "mapper/business/appgaps/AppOriginalRequestMapper.xml"), my.tx);
    }
    @BeforeEach @AfterAll void clean() {
        my.db.update("delete from business_audit_event where domain_type='CLUB' and domain_id=?", CLUB);
        my.db.update("delete from club_role_assignment where club_id=?", CLUB);
        my.db.update("delete from app_original_request where actor_member_id in (?,?)", OWNER, TARGET);
    }

    long assignment(String role, String scopeType, long scopeId) {
        my.db.update("insert into club_role_assignment(club_id,target_member_id,role_code,scope_type,scope_id,status,assigned_by_member_id,assigned_at,version,create_time)"
                + " values(?,?,?,?,?,'ACTIVE',?,now(),0,now())", CLUB, TARGET, role, scopeType, scopeId, OWNER);
        return my.db.queryForObject("select max(id) from club_role_assignment where club_id=?", Long.class, CLUB);
    }
    long audit(String action, long relation, String reason, String rid) {
        my.db.update("insert into business_audit_event(domain_type,domain_id,owner_member_id,actor_type,actor_member_id,actor_role_code,action,target_type,target_id,relation_id,reason,request_id,create_time)"
                + " values('CLUB',?,?,'MEMBER',?,'CLUB_OWNER',?,'MEMBER',?,?,?,?,now())", CLUB, OWNER, OWNER, action, TARGET, relation, reason, rid);
        return my.db.queryForObject("select max(id) from business_audit_event where domain_id=?", Long.class, CLUB);
    }

    @SuppressWarnings("unchecked")
    @Test void assignReceiptReadsCommittedAuditFactAndStaysUnknownOtherwise() {
        String rid = UUID.randomUUID().toString();
        Map<String, Object> body = new HashMap<>(Map.of("clubId", (int) CLUB, "targetMemberId", (int) TARGET, "roleCode", "CLUB_OPERATOR", "requestId", rid));
        body.put("expiresAtEpochMs", null);
        assertEquals("UNKNOWN", ((Map<String, Object>) roles.assign(OWNER, body).get("data")).get("state"));
        long a = assignment("CLUB_OPERATOR", "CLUB", CLUB); long e = audit("CLUB_ROLE_ASSIGNED", a, null, rid);
        Map<String, Object> d = (Map<String, Object>) roles.assign(OWNER, body).get("data");
        assertEquals("APPLIED", d.get("state")); assertEquals(a, d.get("assignmentId")); assertEquals(e, d.get("auditId"));
        assertEquals(0, d.get("version")); assertTrue((Long) d.get("appliedAt") > 0); assertTrue(d.containsKey("expiresAtEpochMs"));
        assertEquals(403, roles.assign(TARGET, body).get("code"), "非原发起人不可读");
        body.put("roleCode", "CLUB_CO_OWNER");
        assertEquals(409, roles.assign(OWNER, body).get("code"), "同 ID 不同角色是冲突");
    }

    @SuppressWarnings("unchecked")
    @Test void revokeReceiptChecksReasonDigestAgainstAudit() {
        String rid = UUID.randomUUID().toString();
        long a = assignment("EVENT_CHECKIN", "EVENT", 77L); long e = audit("CLUB_ROLE_REVOKED", a, "撤销委派", rid);
        Map<String, Object> body = new HashMap<>(Map.of("clubId", (int) CLUB, "activityId", 77, "assignmentId", (int) a, "version", 0,
                "requestId", rid, "targetMemberId", (int) TARGET, "roleCode", "EVENT_CHECKIN", "reasonSHA256", AppOriginalRequestLedger.sha256Hex("撤销委派")));
        Map<String, Object> d = (Map<String, Object>) roles.revoke(OWNER, body).get("data");
        assertEquals("APPLIED", d.get("state")); assertEquals(e, d.get("auditId")); assertEquals(77L, d.get("activityId"));
        body.put("reasonSHA256", "0".repeat(64));
        assertEquals(409, roles.revoke(OWNER, body).get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test void welcomeNoteAndAdmissionOriginalsPersistAndReplayOnRealLedger() {
        ClubWelcomeNoteOriginal welcome = new ClubWelcomeNoteOriginal(ledger);
        String rid = UUID.randomUUID().toString(), sha = ClubWelcomeNoteOriginal.source(CLUB, "你好");
        int[] calls = {0};
        AjaxResult w = welcome.write(OWNER, CLUB, "你好", rid, sha, () -> { calls[0]++; return AjaxResult.success("已保存", Map.of("welcomeNote", "你好")); });
        assertEquals(200, w.get("code"));
        welcome.write(OWNER, CLUB, "你好", rid, sha, () -> { calls[0]++; return AjaxResult.success(); });
        assertEquals(1, calls[0], "同 requestId 不重放旧写");
        Map<String, Object> d = (Map<String, Object>) welcome.receipt(OWNER, Map.of("clubId", (int) CLUB, "requestId", rid, "sourceSHA256", sha)).get("data");
        assertEquals("APPLIED", d.get("state")); assertTrue((Long) d.get("auditId") > 0); assertTrue((Long) d.get("recordedAt") > 0);

        ClubAdmissionOriginal admission = new ClubAdmissionOriginal(ledger);
        String rid2 = UUID.randomUUID().toString(), sha2 = ClubAdmissionOriginal.digest("apply", "带我");
        AjaxResult rejected = admission.write(TARGET, CLUB, false, rid2, "apply", sha2, "带我", () -> AjaxResult.error("俱乐部不存在或未开放"));
        assertEquals(500, rejected.get("code"));
        AjaxResult receipt = admission.receipt(TARGET, Map.of("clubId", (int) CLUB, "requestId", rid2, "action", "apply", "payloadSHA256", sha2));
        assertEquals(500, receipt.get("code"), "被拒的原请求读回同一拒绝");
        assertEquals("REJECTED", my.db.queryForObject("select state from app_original_request where request_id=?", String.class, rid2));
    }
}

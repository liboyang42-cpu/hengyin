package com.chengyinhub.business.merchantgap;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.IAuditTaskService;

/**
 * Store-NPC resources the app calls under /api/merchant/npc: the voice recording script, voice revoke,
 * avatar generation and status, and the knowledge-review submission with its request read-back.
 * Every route acts on the caller's own store (PROFILE_WRITE); no client-supplied merchant id is trusted.
 */
@Service
public class MerchantNpcGapService {
    static final String KNOWLEDGE_AUDIT = "MERCHANT_NPC_KNOWLEDGE";
    static final String KNOWLEDGE_SOURCE = "merchant_npc_knowledge_review";
    /** Five lines read aloud for voice cloning; line 0 is the consent statement (consentIndex 0). */
    static final List<String> VOICE_SCRIPT = List.of(
            "我是这家店的店主（或已获店主授权），同意城隐使用这段录音为本店分身复刻声音。",
            "欢迎光临，今天想先看看哪一样？",
            "这条街走到头有个小广场，傍晚的时候特别热闹。",
            "如果需要帮忙，随时叫我一声就好。",
            "谢谢你的到访，下次再来坐坐。");
    private static final int VOICE_NONE = 0;
    private static final Set<String> KNOWLEDGE_KEYS = Set.of("requestId", "name", "greeting", "persona", "knowledge");

    private final IMerchantAccessService access;
    private final NpcProfileMapper npcProfiles;
    private final IAuditTaskService audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MerchantAvatarProvider avatars;

    @Autowired
    public MerchantNpcGapService(IMerchantAccessService access, NpcProfileMapper npcProfiles, IAuditTaskService audit,
            JdbcTemplate jdbc, PlatformTransactionManager transactions,
            @Autowired(required = false) MerchantAvatarProvider avatars) {
        this.access = access;
        this.npcProfiles = npcProfiles;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.avatars = avatars;
    }

    // ---- voice ----

    public Map<String, Object> voiceScript(Long principal) {
        MerchantGapSupport.merchant(access, principal, MerchantPermission.PROFILE_WRITE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("script", VOICE_SCRIPT);
        out.put("consentIndex", 0);
        return out;
    }

    /** Clears the cloned voice of the caller's store NPC; repeating it is a no-op. requestId is only echoed. */
    public Map<String, Object> voiceRevoke(Long principal, Map<String, Object> body) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.PROFILE_WRITE);
        if (body == null || !body.keySet().equals(Set.of("requestId"))) throw invalid("请求无效");
        String requestId = MerchantGapSupport.text(body, "requestId");
        if (requestId == null || !MerchantGapSupport.ANY_UUID.matcher(requestId).matches()) throw invalid("请求编号无效");
        NpcProfile mine = ownNpc(ctx.getMerchantId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        if (mine == null || mine.getVoiceStatus() == null || mine.getVoiceStatus() == VOICE_NONE) {
            out.put("voiceStatus", VOICE_NONE);
            out.put("changed", false);
            return out;
        }
        NpcProfile upd = new NpcProfile();
        upd.setId(mine.getId());
        upd.setScopeId(ctx.getMerchantId());
        upd.setVoiceVersion(mine.getVoiceVersion());
        upd.setVoiceId(null);
        upd.setVoiceStatus(VOICE_NONE);
        if (npcProfiles.updateMerchantNpcVoice(upd) != 1) {
            throw new MerchantGapException(409, "VOICE_CHANGED", "声音状态刚发生变化，请刷新后重试");
        }
        out.put("voiceStatus", VOICE_NONE);
        out.put("changed", true);
        return out;
    }

    private NpcProfile ownNpc(Long merchantId) {
        List<NpcProfile> list = npcProfiles.selectMerchantNpcList(merchantId, null);
        if (list == null || list.isEmpty()) return null;
        NpcProfile mine = list.get(0);
        return mine.getScopeId() != null && mine.getScopeId().equals(merchantId) ? mine : null;
    }

    // ---- avatar ----

    public Map<String, Object> avatarStatus(Long principal) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.PROFILE_WRITE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", avatars != null);
        out.put("styles", avatars == null ? List.of() : avatars.styles());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM merchant_npc_avatar_job WHERE merchant_id = ? ORDER BY id DESC LIMIT 1", ctx.getMerchantId());
        out.put("job", rows.isEmpty() ? null : jobView(rows.get(0)));
        return out;
    }

    public Map<String, Object> avatarGenerate(Long principal, Map<String, Object> body) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.PROFILE_WRITE);
        if (body == null || !body.keySet().equals(Set.of("imageUrl", "style"))) throw invalid("请求无效");
        String imageUrl = MerchantGapSupport.text(body, "imageUrl");
        String style = MerchantGapSupport.text(body, "style");
        if (imageUrl == null || imageUrl.length() > 1024 || !imageUrl.startsWith("https://") || imageUrl.contains("@")
                || imageUrl.contains("#")) throw invalid("形象图片地址无效");
        if (avatars == null) throw new MerchantGapException(503, "AVATAR_PROVIDER_NOT_CONFIGURED", "形象生成暂未开放");
        if (style == null || !avatars.styles().contains(style)) throw invalid("形象风格无效");
        if (ownNpc(ctx.getMerchantId()) == null) throw new MerchantGapException(409, "NPC_PROFILE_REQUIRED", "请先保存形象，再生成分身");
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Long id = tx.execute(status -> {
            jdbc.update("INSERT INTO merchant_npc_avatar_job(merchant_id, member_id, image_url, style, provider, status, create_time, update_time) VALUES (?,?,?,?,?,?,?,?)",
                    ctx.getMerchantId(), principal, imageUrl, style, avatars.id(), "PENDING", now, now);
            return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        });
        MerchantAvatarProvider.Result result;
        try {
            result = avatars.generate(ctx.getMerchantId(), imageUrl, style);
        } catch (RuntimeException e) {
            result = new MerchantAvatarProvider.Result("FAILED", null, null, "PROVIDER_ERROR");
        }
        if (result != null && !"PENDING".equals(result.status)) {
            String status = "SUCCEEDED".equals(result.status) ? "SUCCEEDED" : "FAILED";
            jdbc.update("UPDATE merchant_npc_avatar_job SET status = ?, model_url = ?, thumb_url = ?, fail_reason = ?, update_time = ? WHERE id = ? AND status = 'PENDING'",
                    status, result.modelUrl, result.thumbUrl, "FAILED".equals(status) ? nz(result.failReason, "PROVIDER_FAILED") : null,
                    new Timestamp(System.currentTimeMillis()), id);
        }
        return jobView(jdbc.queryForMap("SELECT * FROM merchant_npc_avatar_job WHERE id = ?", id));
    }

    private static Map<String, Object> jobView(Map<String, Object> row) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobId", ((Number) row.get("id")).longValue());
        job.put("status", row.get("status"));
        job.put("style", row.get("style"));
        job.put("modelUrl", row.get("model_url"));
        job.put("thumbUrl", row.get("thumb_url"));
        job.put("failReason", row.get("fail_reason"));
        return job;
    }

    // ---- knowledge review ----

    public Map<String, Object> submitKnowledge(Long principal, Map<String, Object> body) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.PROFILE_WRITE);
        if (body == null || !body.keySet().equals(KNOWLEDGE_KEYS)) throw invalid("请求无效");
        String requestId = MerchantGapSupport.text(body, "requestId");
        if (requestId == null || !MerchantGapSupport.ANY_UUID.matcher(requestId).matches()) throw invalid("请求编号无效");
        String name = bounded(body, "name", 64);
        String greeting = bounded(body, "greeting", 500);
        String persona = bounded(body, "persona", 4000);
        String knowledge = bounded(body, "knowledge", 16000);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", name);
        fields.put("greeting", greeting);
        fields.put("persona", persona);
        fields.put("knowledge", knowledge);
        String hash = MerchantGapSupport.sha256(MerchantGapSupport.appleSortedJson(fields));
        Map<String, Object> existing = knowledgeRow(principal, requestId);
        if (existing != null) return replay(existing, hash);
        try {
            tx.execute(status -> {
                jdbc.update("INSERT INTO merchant_npc_knowledge_review(member_id, merchant_id, request_id, payload_hash, name, greeting, persona, knowledge, status, create_time) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        principal, ctx.getMerchantId(), requestId, hash, name, greeting, persona, knowledge, "PENDING_REVIEW",
                        new Timestamp(System.currentTimeMillis()));
                Long rowId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                Map<String, Object> snapshot = new LinkedHashMap<>(fields);
                snapshot.put("merchantId", ctx.getMerchantId());
                snapshot.put("requestId", requestId);
                snapshot.put("payloadHash", hash);
                Long taskId = audit.submit(KNOWLEDGE_AUDIT, rowId, principal, KNOWLEDGE_SOURCE, MerchantGapSupport.appleSortedJson(snapshot));
                jdbc.update("UPDATE merchant_npc_knowledge_review SET audit_task_id = ? WHERE id = ?", taskId, rowId);
                return rowId;
            });
        } catch (DuplicateKeyException e) {
            Map<String, Object> raced = knowledgeRow(principal, requestId);
            if (raced == null) throw e;
            return replay(raced, hash);
        }
        return knowledgeView(knowledgeRow(principal, requestId));
    }

    public Map<String, Object> knowledgeRequest(Long principal, String requestId) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.PROFILE_WRITE);
        if (requestId == null || !MerchantGapSupport.ANY_UUID.matcher(requestId).matches()) throw invalid("请求编号无效");
        Map<String, Object> row = knowledgeRow(principal, requestId);
        if (row == null || !ctx.getMerchantId().equals(((Number) row.get("merchant_id")).longValue())) {
            throw new MerchantGapException(404, "REQUEST_NOT_FOUND", "没有这条送审请求");
        }
        return knowledgeView(row);
    }

    private Map<String, Object> knowledgeRow(long member, String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM merchant_npc_knowledge_review WHERE member_id = ? AND request_id = ?", member, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> replay(Map<String, Object> row, String hash) {
        if (!hash.equals(row.get("payload_hash"))) {
            throw new MerchantGapException(409, "REQUEST_ID_CONFLICT", "请求编号已用于其他内容");
        }
        return knowledgeView(row);
    }

    private static Map<String, Object> knowledgeView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", row.get("request_id"));
        out.put("memberId", ((Number) row.get("member_id")).longValue());
        out.put("merchantId", ((Number) row.get("merchant_id")).longValue());
        out.put("name", row.get("name"));
        out.put("greeting", row.get("greeting"));
        out.put("persona", row.get("persona"));
        out.put("knowledge", row.get("knowledge"));
        out.put("status", row.get("status"));
        out.put("auditTaskId", row.get("audit_task_id") == null ? null : ((Number) row.get("audit_task_id")).longValue());
        out.put("published", false);
        return out;
    }

    private static String bounded(Map<String, Object> body, String key, int max) {
        String value = MerchantGapSupport.text(body, key);
        if (value == null || value.isBlank() || value.length() > max) throw invalid("字段 " + key + " 为空或过长");
        return value;
    }

    private static MerchantGapException invalid(String message) {
        return new MerchantGapException(400, "INVALID_REQUEST", message);
    }

    private static String nz(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static List<String> script() { return new ArrayList<>(VOICE_SCRIPT); }
}

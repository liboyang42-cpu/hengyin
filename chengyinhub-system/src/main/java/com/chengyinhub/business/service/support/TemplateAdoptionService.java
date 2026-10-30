package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.dto.TemplateAdoptionCommand;
import com.chengyinhub.business.mapper.TemplateAdoptionReceiptMapper;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class TemplateAdoptionService {
    private final TemplateAdoptionReceiptMapper mapper;
    private final ITemplatePublishService drafts;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();
    public TemplateAdoptionService(TemplateAdoptionReceiptMapper mapper, ITemplatePublishService drafts,
                                   PlatformTransactionManager manager) {
        this.mapper = mapper; this.drafts = drafts;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public static Long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new ServiceException("模板编号不规范", 400);
        try { return Long.valueOf(value); } catch (NumberFormatException e) { throw new ServiceException("模板编号越界", 400); }
    }
    private static String requestId(String v) {
        if (v == null || !v.matches("[A-Za-z0-9_-]{16,96}")) throw new ServiceException("采用请求号不规范", 400);
        return v;
    }
    private static void principal(Long actor, Long owner) {
        if (actor == null || owner == null || actor <= 0 || owner <= 0) throw new ServiceException("请先登录", 401);
    }
    private static String scope(String s) {
        if (s == null || s.isEmpty()) return "";
        if ("MERCHANT".equals(s)) return s;
        throw new ServiceException("采用范围不规范", 400);
    }
    private static boolean available(CmsTemplateLibrary s) {
        return s != null && Integer.valueOf(0).equals(s.getDelFlag())
            && Long.valueOf(1).equals(s.getStatus()) && Long.valueOf(1).equals(s.getPublishStatus());
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private JsonNode canonical(JsonNode n) {
        if (n.isObject()) {
            ObjectNode result = json.createObjectNode(); List<String> keys = new ArrayList<>(); n.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys); for (String k : keys) result.set(k, canonical(n.get(k))); return result;
        }
        if (n.isArray()) { var result = json.createArrayNode(); n.forEach(v -> result.add(canonical(v))); return result; }
        return n;
    }
    // Versioned fingerprint of author fields copied by the existing adoption service, not counters or audit timestamps.
    String sourceVersion(CmsTemplateLibrary source) {
        CmsTemplatePublishRequest copy = new CmsTemplatePublishRequest();
        BeanUtils.copyProperties(source, copy, "id", "scope", "originalTemplateId", "duration");
        copy.setDuration(source.getDuration() == null ? null : Math.toIntExact(source.getDuration()));
        copy.setOriginalTemplateId(source.getId()); copy.setIsSync(0);
        return hash("template-adoption-source-v1\n" + canonical(json.valueToTree(copy)).toString());
    }
    private record SourceSnapshot(CmsTemplateLibrary source, String version) {}
    private SourceSnapshot lockedSource(Long id) {
        Long canonicalId = mapper.sourceCanonicalId(id);
        var canonical = canonicalId == null ? null : mapper.lockDraft(canonicalId);
        CmsTemplateLibrary source = mapper.lockSource(id);
        if (source == null) return new SourceSnapshot(null, "");
        if (!Objects.equals(canonicalId,source.getMemberTemplateId()))
            throw new ServiceException("采用来源关联已变化，请重查原请求",409);
        String original = canonical == null ? "missing" : draftVersion(canonical) + "\n" + canonical.getInteractionType()
            + "\n" + canonical.getMemberId() + "\n" + canonical.getDelFlag() + "\n" + canonical.getStatus();
        return new SourceSnapshot(source,hash("template-adoption-complete-source-v1\n" + sourceVersion(source) + "\n" + original));
    }
    private String draftVersion(com.chengyinhub.business.domain.CmsMemberTemplate draft) {
        CmsTemplatePublishRequest copy = new CmsTemplatePublishRequest();
        BeanUtils.copyProperties(draft, copy, "id", "scope", "duration");
        copy.setDuration(draft.getDuration() == null ? null : Math.toIntExact(draft.getDuration()));
        return hash("template-adoption-draft-v1\n" + canonical(json.valueToTree(copy)).toString() + "\ninteractionType=" + draft.getInteractionType());
    }
    public boolean matchesDraft(Map<String,Object> receipt, com.chengyinhub.business.domain.CmsMemberTemplate draft) {
        return draft != null && String.valueOf(draft.getId()).equals(receipt.get("draftId"))
            && draftVersion(draft).equals(receipt.get("draftVersion"));
    }
    public Map<String, Object> source(Long actor, Long owner, String sourceId) {
        principal(actor, owner); Long id = id(sourceId);
        return tx.execute(t -> {
            SourceSnapshot snapshot = lockedSource(id);
            if (!available(snapshot.source())) throw new ServiceException("源模板不存在或已下架", 404);
            return Map.of("originalTemplateId", sourceId, "sourceVersion", snapshot.version(), "title", Objects.toString(snapshot.source().getTitle(),""));
        });
    }
    public Map<String, Object> adopt(Long actor, Long owner, TemplateAdoptionCommand command, String username) {
        principal(actor, owner);
        if (command == null) throw new ServiceException("采用参数缺失", 400);
        String key = requestId(command.getRequestId()), scope = scope(command.getScope()); Long sourceId = id(command.getOriginalTemplateId());
        String version = command.getSourceVersion();
        if (version == null || !version.matches("[a-f0-9]{64}")) throw new ServiceException("采用来源版本缺失", 409);
        String fingerprint = hash("adopt-v1\n" + actor + "\n" + owner + "\n" + scope + "\n" + sourceId + "\n" + version);
        Map<String, Object> facts = new HashMap<>(); facts.put("actor", actor); facts.put("owner", owner);
        facts.put("requestId", key); facts.put("scope", scope); facts.put("sourceId", sourceId);
        facts.put("sourceVersion", version); facts.put("requestHash", fingerprint);
        // Durable reservation survives process loss; retries cannot exchange original parameters.
        tx.execute(t -> { mapper.reserve(facts); check(mapper.lock(actor, key), owner, scope, fingerprint); return null; });
        try { return tx.execute(t -> {
            Map<String, Object> row = mapper.lock(actor, key); check(row, owner, scope, fingerprint);
            if (!"PENDING".equals(row.get("status"))) return view(row);
            SourceSnapshot source = lockedSource(sourceId);
            if (!available(source.source()) || !version.equals(source.version())) {
                finish(actor, key, "REJECTED", null, "SOURCE_VERSION_CONFLICT", null); return view(mapper.lock(actor, key));
            }
            // Existing service retains qualification, media, coupon/reward and ownership validation.
            // Canonical + library locks remain held until draft + receipt commit; RC reads cannot use an old snapshot.
            CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
            request.setOriginalTemplateId(sourceId); request.setScope(scope); request.setAdoptSourceOnly(true);
            Long draftId = drafts.saveOrUpdateDraft(request, owner, username);
            if (draftId == null || draftId <= 0) throw new IllegalStateException("采用草稿编号缺失");
            var created = mapper.lockDraft(draftId);
            if (created == null || !owner.equals(created.getMemberId())) throw new IllegalStateException("采用草稿归属回读失败");
            finish(actor, key, "COMPLETED", draftId, null, draftVersion(created));
            return view(mapper.lock(actor, key));
        }); } catch (IllegalArgumentException rejected) {
            // The execution transaction has rolled back. Persist only a definite validation rejection.
            return tx.execute(t -> {
                Map<String,Object> row = mapper.lock(actor,key); check(row,owner,scope,fingerprint);
                if ("PENDING".equals(row.get("status"))) finish(actor,key,"REJECTED",null,rejectionCode(rejected),null);
                return view(mapper.lock(actor,key));
            });
        }
    }
    /** 身份能力闸(CapabilityGuard「当前身份未解锁…」)单独给码,其余仍归通用校验拒绝;原文不入库(列宽 64)。 */
    static String rejectionCode(IllegalArgumentException rejected) {
        String message = rejected == null ? null : rejected.getMessage();
        return message != null && message.contains("未解锁") ? "ADOPTION_CAPABILITY_LOCKED" : "ADOPTION_VALIDATION_REJECTED";
    }
    private void finish(Long actor, String key, String state, Long draft, String reason, String draftVersion) {
        if (mapper.finish(actor, key, state, draft, reason, draftVersion) != 1) throw new IllegalStateException("采用回执更新失败");
    }
    private void check(Map<String, Object> row, Long owner, String scope, String fingerprint) {
        if (row == null || !owner.toString().equals(String.valueOf(row.get("owner"))) || !scope.equals(row.get("scope")))
            throw new ServiceException("采用原身份不匹配", 403);
        if (fingerprint != null && !fingerprint.equals(row.get("requestHash"))) throw new ServiceException("ADOPTION_REQUEST_CONFLICT", 409);
    }
    public Map<String, Object> receipt(Long actor, Long owner, String scope, String key) {
        principal(actor, owner); requestId(key); scope = scope(scope);
        Map<String, Object> row = mapper.read(actor, key);
        if (row == null) return Map.of("requestId", key, "status", "UNKNOWN");
        check(row, owner, scope, null); return view(row);
    }
    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String k : List.of("requestId", "sourceVersion", "draftVersion", "status")) result.put(k, row.get(k));
        result.put("originalTemplateId", String.valueOf(row.get("sourceId")));
        result.put("draftId", row.get("draftId") == null ? null : String.valueOf(row.get("draftId")));
        result.put("reason", row.get("reason")); return result;
    }
}

package com.chengyinhub.business.service.merchantauthoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantgap.MerchantGapSupport;
import com.chengyinhub.business.service.support.TemplateAuditSnapshot;

/**
 * Owner-only read of saved W05 merchant drafts for the professional node chooser
 * (POST /api/merchant/authoring/draft-selection/{list,resolve}). A source row links one W05 source
 * (MERCHANT_AI_TEMPLATE_SOURCE_V1) to the owner's own CMS member template. READY rows carry the current
 * template title and content hash; anything else is UNAVAILABLE with ids only. Neither response grants usage,
 * approval or publication.
 */
@Service
public class MerchantDraftSelectionService {
    public static final String SOURCE_KIND = "MERCHANT_AI_TEMPLATE_SOURCE_V1";
    static final int PAGE = 20;
    private static final Set<String> SOURCE_KEYS = Set.of("kind", "sourceId", "sourceVersion", "contentHash");

    private final IMerchantAccessService access;
    private final CmsMemberTemplateMapper templates;
    private final JdbcTemplate jdbc;

    public MerchantDraftSelectionService(IMerchantAccessService access, CmsMemberTemplateMapper templates, JdbcTemplate jdbc) {
        this.access = access;
        this.templates = templates;
        this.jdbc = jdbc;
    }

    private MerchantAccessContext owner(Long principal) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.PROJECT_MANAGE);
        if (!ctx.getOwnerMemberId().equals(principal)) throw new MerchantGapException(403, "OWNER_ONLY", "仅店主可选择已保存草稿");
        return ctx;
    }

    public Map<String, Object> list(Long principal, Map<String, Object> body) {
        MerchantAccessContext ctx = owner(principal);
        if (body == null || !body.keySet().equals(Set.of("beforeSourceId"))) throw invalid();
        Object raw = body.get("beforeSourceId");
        Long before = null;
        if (raw != null) {
            before = MerchantGapSupport.positive(body, "beforeSourceId");
            if (before == null) throw invalid();
        }
        List<Map<String, Object>> found = before == null
                ? jdbc.queryForList("SELECT * FROM merchant_authoring_source WHERE owner_member_id = ? AND merchant_row_id = ? ORDER BY id DESC LIMIT ?",
                        ctx.getOwnerMemberId(), ctx.getMerchantId(), PAGE + 1)
                : jdbc.queryForList("SELECT * FROM merchant_authoring_source WHERE owner_member_id = ? AND merchant_row_id = ? AND id < ? ORDER BY id DESC LIMIT ?",
                        ctx.getOwnerMemberId(), ctx.getMerchantId(), before, PAGE + 1);
        boolean more = found.size() > PAGE;
        List<Map<String, Object>> rows = new ArrayList<>();
        java.util.Set<Long> seenTemplates = new java.util.HashSet<>();
        for (Map<String, Object> source : found.subList(0, Math.min(PAGE, found.size()))) {
            Map<String, Object> row = row(source, ctx);
            if (!seenTemplates.add(((Number) row.get("memberTemplateId")).longValue())) continue;
            rows.add(row);
        }
        Map<String, Object> out = common(principal, ctx, "MERCHANT_AUTHORING_DRAFT_SELECTION_PAGE_V1", "PAGE_CURRENT_NOT_SNAPSHOT");
        out.put("rows", rows);
        out.put("nextBeforeSourceId", more && rows.size() == PAGE ? rows.get(rows.size() - 1).get("sourceId") : null);
        return out;
    }

    public Map<String, Object> resolve(Long principal, Map<String, Object> body) {
        MerchantAccessContext ctx = owner(principal);
        if (body == null || !body.keySet().equals(Set.of("source", "memberTemplateId", "templateContentHash"))
                || !(body.get("source") instanceof Map<?, ?> rawSource) || !SOURCE_KEYS.equals(rawSource.keySet())) throw invalid();
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) rawSource;
        Long sourceId = MerchantGapSupport.positive(source, "sourceId");
        Long templateId = MerchantGapSupport.positive(body, "memberTemplateId");
        String hash = MerchantGapSupport.text(body, "templateContentHash");
        String contentHash = MerchantGapSupport.text(source, "contentHash");
        if (!SOURCE_KIND.equals(source.get("kind")) || !Integer.valueOf(1).equals(source.get("sourceVersion")) || sourceId == null
                || templateId == null || hash == null || !MerchantGapSupport.HEX64.matcher(hash).matches()
                || contentHash == null || !MerchantGapSupport.HEX64.matcher(contentHash).matches()) throw invalid();
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT * FROM merchant_authoring_source WHERE id = ? AND owner_member_id = ? AND merchant_row_id = ?",
                sourceId, ctx.getOwnerMemberId(), ctx.getMerchantId());
        if (found.isEmpty()) throw new MerchantGapException(403, "SOURCE_FORBIDDEN", "不是本店的草稿来源");
        Map<String, Object> row = row(found.get(0), ctx);
        if (!"READY".equals(row.get("state")) || !templateId.equals(((Number) row.get("memberTemplateId")).longValue())
                || !hash.equals(row.get("templateContentHash"))
                || !contentHash.equals(((Map<?, ?>) row.get("source")).get("contentHash"))) {
            throw new MerchantGapException(409, "SOURCE_CHANGED", "草稿已变化，请刷新后重选");
        }
        Map<String, Object> out = common(principal, ctx, "MERCHANT_AUTHORING_DRAFT_SELECTION_RESOLVE_V1", "CURRENT_AT_READ_ONLY");
        out.put("row", row);
        return out;
    }

    private Map<String, Object> row(Map<String, Object> source, MerchantAccessContext ctx) {
        long sourceId = ((Number) source.get("id")).longValue();
        long templateId = ((Number) source.get("actual_template_id")).longValue();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourceId", sourceId);
        row.put("memberTemplateId", templateId);
        CmsMemberTemplate template = templates.selectCmsMemberTemplateById(templateId);
        String title = template == null ? null : template.getTitle();
        boolean ready = template != null && ctx.getOwnerMemberId().equals(template.getMemberId())
                && !Integer.valueOf(2).equals(template.getDelFlag()) && title != null && !title.isBlank() && title.length() <= 512;
        if (!ready) {
            row.put("state", "UNAVAILABLE");
            return row;
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("kind", SOURCE_KIND);
        ref.put("sourceId", sourceId);
        ref.put("sourceVersion", 1);
        ref.put("contentHash", source.get("content_hash"));
        row.put("state", "READY");
        row.put("source", ref);
        row.put("templateTitle", title);
        row.put("templateContentHash", MerchantGapSupport.sha256(TemplateAuditSnapshot.personal(template)));
        return row;
    }

    private static Map<String, Object> common(Long principal, MerchantAccessContext ctx, String profile, String currentness) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", profile);
        out.put("ownerMemberId", principal);
        out.put("merchantRowId", ctx.getMerchantId());
        out.put("templateIdNamespace", "CMS_MEMBER_TEMPLATE");
        out.put("currentness", currentness);
        out.put("merchantFactsFreshness", "HISTORICAL_INPUT_ONLY");
        out.put("approvalProof", false);
        out.put("usageRightsProof", false);
        out.put("publicationAuthority", false);
        return out;
    }

    private static MerchantGapException invalid() {
        return new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
    }
}

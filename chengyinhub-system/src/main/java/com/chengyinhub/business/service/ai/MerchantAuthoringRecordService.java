package com.chengyinhub.business.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.dto.ai.*;
import com.chengyinhub.business.domain.vo.ai.AiTemplateFillResp;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.support.TemplateAuditSnapshot;
import com.chengyinhub.common.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server-owned candidate and immutable source evidence. No approval or publish operation. */
@org.springframework.context.annotation.Profile("w05-merchant-authoring")
@Service
public class MerchantAuthoringRecordService {
    public static final String SOURCE_KIND = "MERCHANT_AI_TEMPLATE_SOURCE_V1";
    public static final String TEMPLATE_HASH_PROFILE = "TEMPLATE_AUDIT_PERSONAL_V1";
    private final MerchantAuthoringRecordMapper records;
    private final MerchantAuthoringFactsService facts;
    private final IMerchantAccessService access;
    private final UmsMemberMapper members;
    private final MerchantOperatorMapper operators;
    private final ITemplatePublishService templates;
    private final CmsMemberTemplateMapper templateRows;
    public MerchantAuthoringRecordService(MerchantAuthoringRecordMapper records, MerchantAuthoringFactsService facts,
            IMerchantAccessService access, UmsMemberMapper members,
            MerchantOperatorMapper operators, ITemplatePublishService templates, CmsMemberTemplateMapper templateRows) {
        this.records=records; this.facts=facts; this.access=access; this.members=members;
        this.operators=operators; this.templates=templates; this.templateRows=templateRows;
    }

    /** Called after the model returns, in a short transaction, not while the provider is running. */
    @Transactional(rollbackFor = Exception.class)
    public MerchantAuthoringCandidate record(Long principal, Map<String,Object> verified,
            Map<String,Object> constraints, AiTemplateFillResp generated) {
        MerchantAuthoringFactsReference reference = reference(verified);
        LockedAuthority authority = lockAuthority(principal);
        MerchantAccessContext context = authority.context;
        Map<String,Object> current = lockFacts(reference, authority);
        if (!context.getMerchantId().equals(number(current.get("merchantRowId")))
                || !context.getOwnerMemberId().equals(number(current.get("ownerMemberId")))) throw changed();
        if (generated == null || generated.getTemplate() == null || generated.getTraceId() == null
                || generated.getTraceId().trim().isEmpty() || generated.getTraceId().length() > 200) throw invalid();
        MerchantAuthoringCandidate value = new MerchantAuthoringCandidate();
        value.setPrincipalMemberId(principal); value.setOwnerMemberId(context.getOwnerMemberId());
        value.setMerchantRowId(context.getMerchantId()); value.setFactsJson(canonical(current));
        value.setFactsHash(reference.getFactsHash()); value.setConstraintsJson(canonical(constraints));
        value.setConstraintsHash(hash(value.getConstraintsJson())); value.setAiTraceId(generated.getTraceId());
        value.setTemplateJson(JSON.toJSONString(generated.getTemplate())); value.setCreatedAt(new Date());
        if (records.insertCandidate(value) != 1 || value.getId() == null || value.getId() <= 0) throw failed();
        return value;
    }

    /** New MERCHANT draft plus source are atomic; retrying the same confirmation returns the same receipt. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> confirm(Long principal, MerchantAuthoringConfirmation request) {
        if (request == null || request.getCandidateId() == null || request.getCandidateId() <= 0) throw invalid();
        Map<String,String> fields = confirmedFields(request.getAcceptedFields());
        String confirmationHash = hash(canonical(fields));
        LockedAuthority authority = lockAuthority(principal);
        MerchantAccessContext context = authority.context;
        MerchantAuthoringCandidate candidate = records.lockCandidate(request.getCandidateId());
        if (candidate == null || !principal.equals(candidate.getPrincipalMemberId())
                || !context.getOwnerMemberId().equals(candidate.getOwnerMemberId())
                || !context.getMerchantId().equals(candidate.getMerchantRowId())) throw forbidden();
        MerchantAuthoringSource existing = records.sourceForCandidate(candidate.getId());
        if (existing != null) {
            if (!confirmationHash.equals(existing.getConfirmationHash())) throw changed();
            return receipt(existing);
        }
        JSONObject captured = JSON.parseObject(candidate.getFactsJson());
        MerchantAuthoringFactsReference ref = reference(captured);
        lockFacts(ref, authority);
        JSONObject constraints = JSON.parseObject(candidate.getConstraintsJson());
        JSONObject generated = JSON.parseObject(candidate.getTemplateJson());
        Integer method = generated.getInteger("validationMethod");
        if (method == null || method < 0 || method > 5
                || (method == 2 && !Boolean.TRUE.equals(constraints.getBoolean("filmingAllowed")))) throw invalid();
        CmsTemplatePublishRequest draft = draft(fields, constraints, method);
        // The ordinary draft service owns capability validation and writes draftStatus=0,
        // status=0, publishStatus=0. Never forward a client/template ID or publish call.
        Long templateId = templates.saveOrUpdateDraft(draft, context.getOwnerMemberId(), String.valueOf(principal));
        if (templateId == null || templateId <= 0) throw failed();
        CmsMemberTemplate saved = templateRows.selectCmsMemberTemplateById(templateId);
        if (saved == null || !context.getOwnerMemberId().equals(saved.getMemberId())
                || !Integer.valueOf(0).equals(saved.getDraftStatus())
                || !Long.valueOf(0).equals(saved.getStatus()) || !Long.valueOf(0).equals(saved.getPublishStatus())) throw failed();
        String snapshot = TemplateAuditSnapshot.personal(saved);
        MerchantAuthoringSource source = new MerchantAuthoringSource();
        source.setCandidateId(candidate.getId()); source.setOwnerMemberId(context.getOwnerMemberId());
        source.setMerchantRowId(context.getMerchantId()); source.setActualTemplateId(templateId);
        source.setConfirmationHash(confirmationHash); source.setTemplateSnapshotJson(snapshot);
        source.setTemplateContentHash(hash(snapshot)); source.setCreatedAt(new Date());
        Map<String,Object> provenance = new TreeMap<>();
        provenance.put("kind", SOURCE_KIND); provenance.put("sourceVersion", 1);
        provenance.put("candidateId", candidate.getId()); provenance.put("actualTemplateId", templateId);
        provenance.put("merchantRowId", context.getMerchantId()); provenance.put("ownerMemberId", context.getOwnerMemberId());
        provenance.put("factsHash", candidate.getFactsHash()); provenance.put("constraintsHash", candidate.getConstraintsHash());
        provenance.put("aiTraceId", candidate.getAiTraceId()); provenance.put("confirmationHash", confirmationHash);
        provenance.put("templateHashProfile", TEMPLATE_HASH_PROFILE); provenance.put("templateContentHash", source.getTemplateContentHash());
        source.setSourceJson(canonical(provenance)); source.setContentHash(hash(source.getSourceJson()));
        if (records.insertSource(source) != 1 || source.getId() == null || source.getId() <= 0) throw failed();
        return receipt(source);
    }

    private LockedAuthority lockAuthority(Long principal) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || principal == null || principal <= 0 || !principal.equals(members.lockActiveMemberById(principal))) throw forbidden();
        // This existing resolver only discovers the proposed scope. Under REPEATABLE READ it
        // may be stale; every final authority decision below uses returned current locked rows.
        MerchantAccessContext captured = access.require(principal, MerchantPermission.PROJECT_MANAGE);
        if (captured == null || captured.getMerchantId() == null || captured.getOwnerMemberId() == null) throw forbidden();
        if (!captured.getMerchantId().equals(records.lockMerchant(captured.getMerchantId()))) throw forbidden();
        MmsMerchant merchant = records.lockMerchantSnapshot(captured.getMerchantId());
        if (!activeMerchant(merchant) || !captured.getOwnerMemberId().equals(merchant.getMemberId())) throw changed();
        List<MmsMerchant> ownerRows = records.lockAuthorityMerchants(principal, captured.getOwnerMemberId());
        int activeOwnerRows = 0;
        for (MmsMerchant row : ownerRows) {
            if (!activeMerchant(row)) continue;
            if (captured.getOwnerMemberId().equals(row.getMemberId())) {
                activeOwnerRows++;
                if (!merchant.getId().equals(row.getId())) throw changed();
            }
            if (!principal.equals(captured.getOwnerMemberId()) && principal.equals(row.getMemberId())) throw changed();
        }
        if (activeOwnerRows != 1) throw changed();
        String role = MerchantRoleCode.MERCHANT_OWNER;
        if (!principal.equals(merchant.getMemberId())) {
            MerchantOperator operator = operators.selectActiveByMemberIdForUpdate(principal);
            if (operator == null || operator.getId() == null || !principal.equals(operator.getOperatorMemberId())
                    || !merchant.getId().equals(operator.getMerchantId())
                    || !MerchantOperator.STATUS_ACTIVE.equals(operator.getStatus())
                    || !MerchantRoleCode.isOperatorRole(operator.getRoleCode())) throw forbidden();
            role = operator.getRoleCode();
        }
        MerchantAccessContext current = new MerchantAccessContext(principal, merchant.getId(), merchant.getMemberId(), role);
        // Reuse the sole existing preset-role permission policy, not a copied grant table.
        if (!current.hasPermission(MerchantPermission.PROJECT_MANAGE)
                || !Objects.equals(captured.getRoleCode(), current.getRoleCode())) throw forbidden();
        return new LockedAuthority(current, merchant);
    }
    private static boolean activeMerchant(MmsMerchant row) {
        return row != null && row.getId() != null && row.getMemberId() != null
                && Long.valueOf(1).equals(row.getStatus()) && Long.valueOf(1).equals(row.getAccountStatus())
                && Integer.valueOf(0).equals(row.getDelFlag());
    }
    private Map<String,Object> lockFacts(MerchantAuthoringFactsReference ref, LockedAuthority authority) {
        PmsProduct product = records.lockProductSnapshot(ref.getProductId(), authority.context.getMerchantId());
        PmsSku sku = records.lockSkuSnapshot(ref.getSkuId(), ref.getProductId());
        return facts.requireCurrentSnapshot(ref, authority.context, authority.merchant, product, sku);
    }
    private static final class LockedAuthority {
        final MerchantAccessContext context;
        final MmsMerchant merchant;
        LockedAuthority(MerchantAccessContext context, MmsMerchant merchant) { this.context=context; this.merchant=merchant; }
    }
    private static Map<String,String> confirmedFields(Map<String,String> value) {
        Set<String> allowed = new HashSet<>(Arrays.asList("title","description","ruleInstructions","questionName",
                "questionAnswer","optionA","optionB","optionC","optionD","correctAnswer","hint1","hint2","answerReveal","storyText"));
        if (value == null || !allowed.containsAll(value.keySet())) throw invalid();
        Map<String,String> result = new TreeMap<>();
        for (Map.Entry<String,String> entry : value.entrySet()) {
            if (entry.getValue() == null || entry.getValue().length() > 20000) throw invalid();
            result.put(entry.getKey(), entry.getValue());
        }
        for (String key : Arrays.asList("title","description","ruleInstructions")) {
            if (!result.containsKey(key) || result.get(key).trim().isEmpty()) throw invalid();
        }
        if (result.get("title").length() > 20 || result.get("description").length() > 30) throw invalid();
        return result;
    }
    private static CmsTemplatePublishRequest draft(Map<String,String> text, JSONObject constraints, Integer method) {
        CmsTemplatePublishRequest draft = new CmsTemplatePublishRequest();
        draft.setScope("MERCHANT"); draft.setId(null); draft.setIsSync(0); draft.setOriginalTemplateId(null);
        draft.setTitle(text.get("title")); draft.setDescription(text.get("description"));
        draft.setRuleInstructions(text.get("ruleInstructions")); draft.setValidationMethod(method);
        draft.setQuestionName(text.get("questionName")); draft.setQuestionAnswer(text.get("questionAnswer"));
        draft.setQuestionA(text.get("optionA")); draft.setQuestionB(text.get("optionB"));
        draft.setQuestionC(text.get("optionC")); draft.setQuestionD(text.get("optionD"));
        draft.setCorrectAnswer(text.get("correctAnswer")); draft.setHint1(text.get("hint1")); draft.setHint2(text.get("hint2"));
        draft.setAnswerReveal(text.get("answerReveal")); draft.setStoryText(text.get("storyText"));
        draft.setDuration(constraints.getInteger("durationMinutes")); draft.setPlayers(String.valueOf(constraints.getInteger("participantLimit")));
        // Staff-only operational constraints; provenance is kept in its own table.
        draft.setMerchantGuide("营业时段："+constraints.getString("openingHours")+"\n需店员协助："+constraints.getBoolean("staffRequired")
                +"\n允许拍摄："+constraints.getBoolean("filmingAllowed")+"\n噪音规则："+constraints.getString("noisePolicy")
                +"\n无障碍替代："+constraints.getString("accessibleAlternative")+"\n离线替代："+constraints.getString("offlineAlternative")
                +"\n失败退出："+constraints.getString("failureExit"));
        return draft;
    }
    private static Map<String,Object> receipt(MerchantAuthoringSource source) {
        Map<String,Object> ref = new LinkedHashMap<>(); ref.put("kind",SOURCE_KIND); ref.put("sourceId",source.getId());
        ref.put("sourceVersion",1); ref.put("contentHash",source.getContentHash());
        Map<String,Object> value = new LinkedHashMap<>(); value.put("templateId",source.getActualTemplateId()); value.put("source",ref);
        value.put("templateHashProfile",TEMPLATE_HASH_PROFILE); value.put("templateContentHash",source.getTemplateContentHash());
        value.put("draftOnly",true); value.put("approved",false); value.put("offerConfigured",false); return value;
    }
    private static MerchantAuthoringFactsReference reference(Map<String,Object> value) {
        MerchantAuthoringFactsReference ref = new MerchantAuthoringFactsReference();
        ref.setProductId(number(value.get("productId"))); ref.setSkuId(number(value.get("skuId")));
        ref.setFactsHash((String)value.get("factsHash")); return ref;
    }
    private static Long number(Object value) {
        if (!(value instanceof Number) || ((Number)value).longValue() <= 0) throw invalid();
        return ((Number)value).longValue();
    }
    private static String canonical(Map<?,?> value) { return JSON.toJSONString(new TreeMap<>(value)); }
    private static String hash(String value) {
        try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(64); for(byte b:bytes) out.append(String.format("%02x",b&255)); return out.toString();
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ServiceException invalid() { return new ServiceException("请逐字段确认草稿内容",400); }
    private static ServiceException changed() { return new ServiceException("来源或权限已变化，请重新确认",409); }
    private static ServiceException forbidden() { return new ServiceException("无权保存该店铺草稿",403); }
    private static ServiceException failed() { return new ServiceException("草稿保存失败，请查看原请求状态",500); }
}

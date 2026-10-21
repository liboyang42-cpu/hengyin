package com.chengyinhub.business.citygame.operations;

import com.chengyinhub.business.citygame.operations.mapper.CityGameOperationsMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 运营诊断、人审和纠错。先留下 UNKNOWN 请求，已知结果才回写；同一键不再次执行。
 * 审计只追加。资金执行走资产适配，适配不调用积分发放。
 */
@Service
public class CityGameOperationsService {

    public static final String CONTRACT = "CITY_OPS_TRACE_V1";
    public static final String TRANSPORT = "NOT_APPROVED";
    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    private static final Pattern ID = Pattern.compile("\\A[A-Za-z0-9][A-Za-z0-9_-]{0,95}\\z");
    private static final Set<String> KINDS = Set.of(
            "COMPENSATE", "REVOKE", "DISABLE", "RESTORE", "CORRECT", "PAUSE_MERCHANT");
    private static final Set<String> ASSET_TYPES = Set.of("LEDGER", "POINTS", "WALLET", "TEAM_ASSET", "QUOTE");
    private static final Set<String> ASSET_KEYS = Set.of(
            "pointRate", "feeRate", "probability", "threshold", "amount", "amountMinor", "currency",
            "points", "pointsDelta", "wallet", "award", "teamAsset", "teamId", "assetOwnerId");
    private static final Set<String> TRACE_FIELDS = Set.of(
            "requestId", "expectedVersion", "actionId", "regionId", "sourceRequestId");
    private static final Set<String> PROPOSE_FIELDS = Set.of(
            "requestId", "expectedVersion", "caseId", "reason", "originalRequestId", "objectType",
            "objectId", "ownerUserId", "objectRole", "kind", "merchantId", "classification", "opsNote");
    private static final Set<String> REVIEW_FIELDS = Set.of(
            "requestId", "expectedVersion", "caseId", "reason", "decision");
    private static final Set<String> APPLY_FIELDS = Set.of("requestId", "expectedVersion", "caseId");

    private final CityGameOperationsMapper mapper;
    private final CityGameOperationsFactPort facts;
    private final CityGameOperationsPermissionPort permissions;
    private final CityGameOperationsAssetExecution assets;
    private final List<CityGameOperationsCommand> commands;
    private final AtomicLong ids = new AtomicLong(2_000_000L);

    public CityGameOperationsService(CityGameOperationsMapper mapper, CityGameOperationsFactPort facts,
            CityGameOperationsPermissionPort permissions, CityGameOperationsAssetExecution assets,
            List<CityGameOperationsCommand> commands) {
        this.mapper = mapper;
        this.facts = facts;
        this.permissions = permissions;
        this.assets = assets;
        this.commands = commands;
    }

    @Transactional
    public CityGameOperationsOutcome trace(long actorId, JsonNode body) {
        return handle("TRACE", CityGameOperationsPermissions.TRACE, actorId, body, TRACE_FIELDS, false, this::traceWork);
    }

    @Transactional
    public CityGameOperationsOutcome propose(long actorId, JsonNode body) {
        return handle("PROPOSE", CityGameOperationsPermissions.PROPOSE, actorId, body, PROPOSE_FIELDS, false, this::proposeWork);
    }

    @Transactional
    public CityGameOperationsOutcome review(long actorId, JsonNode body) {
        return handle("REVIEW", CityGameOperationsPermissions.REVIEW, actorId, body, REVIEW_FIELDS, true, this::reviewWork);
    }

    @Transactional
    public CityGameOperationsOutcome apply(long actorId, JsonNode body) {
        return handle("APPLY", CityGameOperationsPermissions.APPLY, actorId, body, APPLY_FIELDS, false, this::applyWork);
    }

    public CityGameOperationsOutcome readCase(long actorId, long caseId) {
        if (!canRead(actorId)) {
            return denied();
        }
        CityGameOperationsCaseRow row = mapper.selectCaseById(caseId);
        if (row == null) {
            return fail(404, "NOT_FOUND", "诊断不存在", null);
        }
        Map<String, Object> data = view(row, actorId);
        data.put("chain", summaries(caseAudits(caseId)));
        return CityGameOperationsOutcome.ok(data);
    }

    public CityGameOperationsOutcome readRequest(long actorId, String requestId) {
        if (!canRead(actorId)) {
            return denied();
        }
        if (!isId(requestId)) {
            return fail(400, "MISSING_PARAM", "缺少必要参数", null);
        }
        CityGameOperationsRequestRow row = mapper.selectRequest(requestId);
        if (row == null) {
            return fail(404, "NOT_FOUND", "原请求不存在", null);
        }
        CityGameOperationsOutcome outcome = restore(row);
        if (outcome.data != null) {
            outcome.data.put("audit", summaries(mapper.selectAuditsByRequestId(requestId)));
            outcome.data.put("resent", Boolean.FALSE);
        }
        return outcome;
    }

    public CityGameOperationsOutcome readStore(long actorId, String merchantId) {
        if (!canRead(actorId)) {
            return denied();
        }
        if (!isId(merchantId)) {
            return fail(400, "MISSING_PARAM", "缺少必要参数", null);
        }
        Map<String, Object> data = envelope(actorId);
        data.put("merchantId", merchantId);
        CityGameOperationsStoreFactRow store = mapper.selectStore(merchantId);
        if (store == null) {
            data.put("status", "MISSING");
            data.put("missing", Boolean.TRUE);
            data.put("storeFact", null);
            return CityGameOperationsOutcome.ok(data);
        }
        data.put("status", "PRESENT");
        data.put("missing", Boolean.FALSE);
        data.put("storeFact", storeView(store));
        return CityGameOperationsOutcome.ok(data);
    }

    private CityGameOperationsOutcome handle(String action, String permission, long actorId, JsonNode body,
            Set<String> allowed, boolean review, Work work) {
        if (!permissions.allows(actorId, permission)) {
            return denied();
        }
        if (body == null || !body.isObject()) {
            return fail(400, "MISSING_PARAM", "缺少必要参数", null);
        }
        String requestId = id(body.get("requestId"));
        CityGameOperationsOutcome invalid = invalid(body, allowed, review);
        if (invalid != null) {
            return requestId == null ? invalid : remember(action, actorId, body, requestId, invalid);
        }
        if (requestId == null) {
            return fail(400, "MISSING_PARAM", "缺少必要参数", null);
        }
        Long version = optionalLong(body.get("expectedVersion"));
        if (version == null) {
            return remember(action, actorId, body, requestId, fail(400, "MISSING_PARAM", "缺少必要参数", null));
        }
        String hash = CityGameOperationsTexts.sha256(CityGameOperationsTexts.canonical(body));
        CityGameOperationsRequestRow existing = mapper.selectRequest(requestId);
        if (existing != null) {
            if (!existing.getActorUserId().equals(actorId)) {
                return denied();
            }
            if (!hash.equals(existing.getRequestHash())) {
                return conflictRequest(existing);
            }
            return restore(existing);
        }
        CityGameOperationsRequestRow claim = claim(action, actorId, body, requestId, hash);
        if (claim == null) {
            CityGameOperationsRequestRow raced = mapper.selectRequest(requestId);
            if (raced != null && hash.equals(raced.getRequestHash()) && raced.getActorUserId().equals(actorId)) {
                return restore(raced);
            }
            return raced == null ? unknown(requestId) : conflictRequest(raced);
        }
        CityGameOperationsOutcome outcome;
        try {
            outcome = work.run(new Opened(actorId, action, version, body, claim));
        } catch (RuntimeException ex) {
            return restore(mapper.selectRequest(requestId));
        }
        return settle(claim, outcome);
    }

    private CityGameOperationsOutcome traceWork(Opened opened) {
        String actionId = id(opened.body.get("actionId"));
        String regionId = id(opened.body.get("regionId"));
        String sourceRequestId = id(opened.body.get("sourceRequestId"));
        if (actionId == null || regionId == null || sourceRequestId == null) {
            return fail(400, "MISSING_PARAM", "缺少必要参数", null);
        }
        CityGameOperationsTrace captured = CityGameOperationsTrace.capture(facts, actionId, regionId, sourceRequestId);
        CityGameOperationsCaseRow existing = mapper.selectCaseByActionId(actionId);
        if (existing == null) {
            if (opened.expectedVersion != 0L) {
                return fail(409, "VERSION_CONFLICT", "版本冲突", null);
            }
            CityGameOperationsCaseRow created = newCase(actionId, regionId, sourceRequestId, captured);
            if (mapper.insertCase(created) != 1) {
                return fail(409, "VERSION_CONFLICT", "版本冲突", null);
            }
            opened.claim.setCaseId(created.getId());
            return CityGameOperationsOutcome.ok(view(created, opened.actorId));
        }
        if (!existing.getVersion().equals(opened.expectedVersion)) {
            return conflict(existing, opened.actorId);
        }
        if ("IN_REVIEW".equals(existing.getStatus()) || "APPROVED".equals(existing.getStatus())) {
            return fail(409, "INVALID_STATE", "审核中的动作不能改诊断", view(existing, opened.actorId));
        }
        if (!regionId.equals(existing.getRegionId()) || !sourceRequestId.equals(existing.getSourceRequestId())) {
            return fail(409, "BINDING_MISMATCH", "原请求或区域与已记录动作不一致", view(existing, opened.actorId));
        }
        existing.setTraceJson(captured.json);
        existing.setBindingStatus(captured.bindingStatus);
        existing.setOwnerUserId(captured.ownerUserId);
        existing.setObjectRole(captured.objectRole);
        if (!cas(existing)) {
            return conflict(mapper.selectCaseById(existing.getId()), opened.actorId);
        }
        opened.claim.setCaseId(existing.getId());
        return CityGameOperationsOutcome.ok(view(existing, opened.actorId));
    }

    private CityGameOperationsOutcome proposeWork(Opened opened) {
        if (optionalLong(opened.body.get("caseId")) == null) {
            return fail(400, "MISSING_PARAM", "缺少必要参数", null);
        }
        CityGameOperationsCaseRow current = required(opened);
        if (current == null) {
            return fail(404, "NOT_FOUND", "诊断不存在", null);
        }
        if (!current.getVersion().equals(opened.expectedVersion)) {
            return conflict(current, opened.actorId);
        }
        if ("IN_REVIEW".equals(current.getStatus()) || "APPROVED".equals(current.getStatus())) {
            return fail(409, "INVALID_STATE", "已有待审核的纠错", view(current, opened.actorId));
        }
        String reason = text(opened.body.get("reason"), 500);
        String original = id(opened.body.get("originalRequestId"));
        String objectType = id(opened.body.get("objectType"));
        String objectId = id(opened.body.get("objectId"));
        Long ownerUserId = optionalLong(opened.body.get("ownerUserId"));
        String objectRole = id(opened.body.get("objectRole"));
        String kind = id(opened.body.get("kind"));
        if (reason == null || original == null || objectType == null || objectId == null || ownerUserId == null
                || objectRole == null || kind == null || !KINDS.contains(kind)) {
            return fail(400, reason == null ? "REASON_REQUIRED" : "MISSING_PARAM",
                    reason == null ? "原因必填" : "缺少必要参数", view(current, opened.actorId));
        }
        if (!original.equals(current.getSourceRequestId())) {
            return fail(409, "BINDING_MISMATCH", "原请求与诊断记录不一致", view(current, opened.actorId));
        }
        if (!"VERIFIED".equals(current.getBindingStatus()) || current.getOwnerUserId() == null
                || !current.getOwnerUserId().equals(ownerUserId) || !objectRole.equals(current.getObjectRole())) {
            return fail(403, "NO_AUTHORITY", "对象归属未核验", view(current, opened.actorId));
        }
        if (opened.actorId == ownerUserId.longValue()) {
            return fail(403, "NO_AUTHORITY", "对象归属人不能提交纠错", view(current, opened.actorId));
        }
        if (!CityGameOperationsTrace.ownershipMatches(facts, current.getActionId(), current.getRegionId(),
                current.getSourceRequestId(), current.getOwnerUserId(), current.getObjectRole())) {
            return fail(403, "NO_AUTHORITY", "对象归属未核验", view(current, opened.actorId));
        }
        String scope = scope(current, opened.body, kind, objectType, objectId);
        if (scope != null) {
            return fail(scopeStatus(scope), scope, scopeMessage(scope), view(current, opened.actorId));
        }
        current.setKind(kind);
        current.setObjectType(objectType);
        current.setObjectId(objectId);
        current.setReason(reason);
        current.setProposerUserId(opened.actorId);
        current.setReviewerUserId(null);
        current.setProposalJson(proposal(opened.body, kind));
        current.setStatus("IN_REVIEW");
        if (!cas(current)) {
            return conflict(mapper.selectCaseById(current.getId()), opened.actorId);
        }
        return CityGameOperationsOutcome.ok(view(current, opened.actorId));
    }

    private CityGameOperationsOutcome reviewWork(Opened opened) {
        if (optionalLong(opened.body.get("caseId")) == null) {
            return fail(400, "MISSING_PARAM", "缺少必要参数", null);
        }
        CityGameOperationsCaseRow current = required(opened);
        if (current == null) {
            return fail(404, "NOT_FOUND", "诊断不存在", null);
        }
        if (!current.getVersion().equals(opened.expectedVersion)) {
            return conflict(current, opened.actorId);
        }
        String reason = text(opened.body.get("reason"), 500);
        String decision = id(opened.body.get("decision"));
        if (reason == null || decision == null) {
            return fail(400, reason == null ? "REASON_REQUIRED" : "MISSING_PARAM",
                    reason == null ? "原因必填" : "缺少必要参数", view(current, opened.actorId));
        }
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            return fail(400, "INVALID_DECISION", "审核决定无效", view(current, opened.actorId));
        }
        if (!"IN_REVIEW".equals(current.getStatus()) || current.getProposerUserId() == null) {
            return fail(409, "INVALID_STATE", "当前状态不能审核", view(current, opened.actorId));
        }
        if (opened.actorId == current.getProposerUserId()
                || (current.getOwnerUserId() != null && opened.actorId == current.getOwnerUserId())) {
            return fail(403, "SELF_APPROVAL", "提交人或归属人不能审核", view(current, opened.actorId));
        }
        String proposedKind = current.getKind();
        current.setReason(reason);
        current.setReviewerUserId(opened.actorId);
        if ("APPROVE".equals(decision)) {
            current.setStatus("APPROVED");
        } else if (current.getCorrectionState() == null) {
            current.setStatus("REJECTED");
            current.setKind(null);
        } else {
            current.setStatus("NON_EXECUTABLE".equals(current.getExecution()) ? "RECORDED" : "APPLIED");
            current.setKind(current.getCorrectionState());
        }
        if (!cas(current)) {
            return conflict(mapper.selectCaseById(current.getId()), opened.actorId);
        }
        Map<String, Object> data = view(current, opened.actorId);
        data.put("decision", decision);
        if ("REJECT".equals(decision)) {
            data.put("rejectedKind", proposedKind);
        }
        return CityGameOperationsOutcome.ok(data);
    }

    private CityGameOperationsOutcome applyWork(Opened opened) {
        if (optionalLong(opened.body.get("caseId")) == null) {
            return fail(400, "MISSING_PARAM", "缺少必要参数", null);
        }
        CityGameOperationsCaseRow current = required(opened);
        if (current == null) {
            return fail(404, "NOT_FOUND", "诊断不存在", null);
        }
        if (!current.getVersion().equals(opened.expectedVersion)) {
            return conflict(current, opened.actorId);
        }
        if (!"APPROVED".equals(current.getStatus()) || current.getReviewerUserId() == null || current.getProposerUserId() == null) {
            return fail(409, "INVALID_STATE", "纠错还没有人审", view(current, opened.actorId));
        }
        if (opened.actorId == current.getProposerUserId()) {
            return fail(403, "SELF_APPROVAL", "提交人不能执行自己的纠错", view(current, opened.actorId));
        }
        if (current.getOwnerUserId() != null && opened.actorId == current.getOwnerUserId()) {
            return fail(403, "NO_AUTHORITY", "对象归属人不能执行纠错", view(current, opened.actorId));
        }
        if (!CityGameOperationsTrace.ownershipMatches(facts, current.getActionId(), current.getRegionId(),
                current.getSourceRequestId(), current.getOwnerUserId(), current.getObjectRole())) {
            return fail(403, "NO_AUTHORITY", "对象归属未核验", view(mapper.selectCaseById(current.getId()), opened.actorId));
        }
        CityGameOperationsCommand command = null;
        for (CityGameOperationsCommand candidate : commands) {
            if (candidate.supports(current.getKind())) {
                command = candidate;
                break;
            }
        }
        if (command == null) {
            return fail(409, "INVALID_STATE", "没有对应的纠错命令", view(current, opened.actorId));
        }
        CityGameOperationsCommandResult result = command.execute(current, mapper, assets);
        if (result.errorCode != null) {
            return fail(result.httpStatus, result.errorCode, result.message, view(mapper.selectCaseById(current.getId()), opened.actorId));
        }
        if (!cas(current)) {
            return conflict(mapper.selectCaseById(current.getId()), opened.actorId);
        }
        // ponytail: case CAS then store write; a crash between them needs the DB transaction, no second lock
        if (result.storeMerchantId != null
                && !CityGameOperationsStoreWrites.write(mapper, result.storeMerchantId, result.storeFactStatus, current)) {
            return unknown(opened.claim.getRequestId());
        }
        return CityGameOperationsOutcome.ok(view(current, opened.actorId));
    }

    private CityGameOperationsOutcome remember(String action, long actorId, JsonNode body, String requestId,
            CityGameOperationsOutcome invalid) {
        String hash = CityGameOperationsTexts.sha256(CityGameOperationsTexts.canonical(body));
        CityGameOperationsRequestRow existing = mapper.selectRequest(requestId);
        if (existing != null) {
            if (!existing.getActorUserId().equals(actorId)) {
                return denied();
            }
            return hash.equals(existing.getRequestHash()) ? restore(existing) : conflictRequest(existing);
        }
        CityGameOperationsRequestRow claim = claim(action, actorId, body, requestId, hash);
        if (claim == null) {
            return unknown(requestId);
        }
        return settle(claim, invalid);
    }

    private CityGameOperationsRequestRow claim(String action, long actorId, JsonNode body, String requestId, String hash) {
        Date now = new Date();
        CityGameOperationsRequestRow row = new CityGameOperationsRequestRow();
        row.setId(ids.incrementAndGet());
        row.setRequestId(requestId);
        row.setRequestHash(hash);
        row.setBodyJson(CityGameOperationsTexts.write(body));
        row.setAction(action);
        row.setActorUserId(actorId);
        row.setRoleKeys(permissions.roleKeys(actorId));
        row.setState("UNKNOWN");
        row.setHttpStatus(409);
        row.setResultJson(CityGameOperationsTexts.write(stored(unknown(requestId))));
        row.setCreateTime(now);
        row.setUpdateTime(now);
        if (mapper.insertRequest(row) != 1) {
            return null;
        }
        append(row, "CLAIM", unknown(requestId));
        return row;
    }

    private CityGameOperationsOutcome settle(CityGameOperationsRequestRow claim, CityGameOperationsOutcome outcome) {
        if (outcome.data != null) {
            List<Map<String, Object>> chain = new ArrayList<Map<String, Object>>(summaries(mapper.selectAuditsByRequestId(claim.getRequestId())));
            chain.add(entry(claim, outcome));
            outcome.data.put("chain", chain);
            outcome.data.put("resent", Boolean.FALSE);
        }
        claim.setState("RECORDED");
        claim.setHttpStatus(outcome.httpStatus);
        claim.setResultJson(CityGameOperationsTexts.write(stored(outcome)));
        claim.setUpdateTime(new Date());
        if (mapper.updateRequestState(claim) != 1) {
            CityGameOperationsRequestRow current = mapper.selectRequest(claim.getRequestId());
            return current == null ? unknown(claim.getRequestId()) : restore(current);
        }
        append(claim, "RESULT", outcome);
        return outcome;
    }

    private void append(CityGameOperationsRequestRow claim, String eventType, CityGameOperationsOutcome outcome) {
        CityGameOperationsAuditRow audit = new CityGameOperationsAuditRow();
        audit.setId(ids.incrementAndGet());
        audit.setRequestId(claim.getRequestId());
        audit.setCaseId(claim.getCaseId());
        audit.setAction(claim.getAction());
        audit.setEventType(eventType);
        audit.setActorUserId(claim.getActorUserId());
        audit.setRoleKeys(claim.getRoleKeys());
        audit.setRequestHash(claim.getRequestHash());
        audit.setHttpStatus(outcome.httpStatus);
        audit.setResultJson(CityGameOperationsTexts.write(stored(outcome)));
        audit.setCreateTime(new Date());
        mapper.insertAudit(audit);
    }

    private CityGameOperationsCaseRow required(Opened opened) {
        Long caseId = optionalLong(opened.body.get("caseId"));
        if (caseId == null) {
            return null;
        }
        CityGameOperationsCaseRow row = mapper.selectCaseById(caseId);
        if (row != null) {
            opened.claim.setCaseId(row.getId());
        }
        return row;
    }

    private boolean cas(CityGameOperationsCaseRow row) {
        row.setExpectedVersion(row.getVersion());
        row.setVersion(row.getVersion() + 1);
        row.setUpdateTime(new Date());
        return mapper.updateCase(row) == 1;
    }

    private CityGameOperationsCaseRow newCase(String actionId, String regionId, String sourceRequestId, CityGameOperationsTrace captured) {
        Date now = new Date();
        CityGameOperationsCaseRow row = new CityGameOperationsCaseRow();
        row.setId(ids.incrementAndGet());
        row.setActionId(actionId);
        row.setRegionId(regionId);
        row.setVersion(1L);
        row.setStatus("OPEN");
        row.setBindingStatus(captured.bindingStatus);
        row.setSourceRequestId(sourceRequestId);
        row.setOwnerUserId(captured.ownerUserId);
        row.setObjectRole(captured.objectRole);
        row.setTraceJson(captured.json);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        return row;
    }

    private String scope(CityGameOperationsCaseRow row, JsonNode body, String kind, String objectType, String objectId) {
        String merchantId = body.has("merchantId") ? id(body.get("merchantId")) : null;
        if (body.has("merchantId") && merchantId == null) {
            return "MISSING_PARAM";
        }
        boolean storeKind = "PAUSE_MERCHANT".equals(kind) || "RESTORE".equals(kind)
                || ("DISABLE".equals(kind) && "MERCHANT_STORE".equals(objectType));
        if (storeKind) {
            if (!"MERCHANT_STORE".equals(objectType) || merchantId == null || !merchantId.equals(objectId)) {
                return "OBJECT_SCOPE";
            }
            String place = placeMerchant(row);
            if (place != null && !place.equals(merchantId)) {
                return "OBJECT_SCOPE";
            }
            if (row.getMerchantId() != null && !row.getMerchantId().equals(merchantId)) {
                return "STORE_SCOPE";
            }
            if (body.has("classification") || body.has("opsNote")) {
                return "OBJECT_SCOPE";
            }
            if ("RESTORE".equals(kind)) {
                CityGameOperationsStoreFactRow store = mapper.selectStore(merchantId);
                if (store == null || (!"PAUSED".equals(store.getFactStatus()) && !"DISABLED".equals(store.getFactStatus()))) {
                    return "INVALID_STATE";
                }
            }
            return null;
        }
        if (body.has("merchantId")) {
            return "OBJECT_SCOPE";
        }
        if (ASSET_TYPES.contains(objectType)) {
            return "ASSET_EXECUTION_DISABLED";
        }
        if (!"ACTION".equals(objectType) || !objectId.equals(row.getActionId())) {
            return "OBJECT_SCOPE";
        }
        if ("REVOKE".equals(kind) && !"APPLIED".equals(row.getStatus()) && !"RECORDED".equals(row.getStatus())) {
            return "INVALID_STATE";
        }
        if ("CORRECT".equals(kind)) {
            return text(body.get("classification"), 120) == null && text(body.get("opsNote"), 500) == null
                    ? "MISSING_PARAM" : null;
        }
        if (body.has("classification") || body.has("opsNote")) {
            return "OBJECT_SCOPE";
        }
        return null;
    }

    private static String proposal(JsonNode body, String kind) {
        ObjectNode node = CityGameOperationsTexts.mapper().createObjectNode();
        node.put("kind", kind);
        if (body.has("merchantId")) {
            node.put("merchantId", body.get("merchantId").asText());
        }
        String classification = text(body.get("classification"), 120);
        String note = text(body.get("opsNote"), 500);
        if (classification != null) {
            node.put("classification", classification);
        }
        if (note != null) {
            node.put("opsNote", note);
        }
        return CityGameOperationsTexts.write(node);
    }

    private Map<String, Object> view(CityGameOperationsCaseRow row, long actorId) {
        Map<String, Object> data = envelope(actorId);
        data.put("caseId", row.getId());
        data.put("actionId", row.getActionId());
        data.put("regionId", row.getRegionId());
        data.put("version", row.getVersion());
        data.put("status", row.getStatus());
        data.put("correctionState", row.getCorrectionState());
        data.put("execution", row.getExecution());
        data.put("bindingStatus", row.getBindingStatus());
        data.put("sourceRequestId", row.getSourceRequestId());
        data.put("ownerUserId", row.getOwnerUserId());
        data.put("objectRole", row.getObjectRole());
        data.put("kind", row.getKind());
        data.put("merchantId", row.getMerchantId());
        data.put("reason", row.getReason());
        data.put("classification", row.getClassification());
        data.put("opsNote", row.getOpsNote());
        data.put("blocker", row.getBlocker());
        data.put("proposerUserId", row.getProposerUserId());
        data.put("reviewerUserId", row.getReviewerUserId());
        data.put("roleKeys", permissions.roleKeys(actorId));
        data.put("trace", CityGameOperationsTexts.map(row.getTraceJson()));
        data.put("storeOverlay", row.getMerchantId() == null ? null : storeOverlay(row.getMerchantId()));
        if ("IN_REVIEW".equals(row.getStatus()) || "APPROVED".equals(row.getStatus())) {
            data.put("pendingKind", row.getKind());
        }
        data.put("effectiveState", row.getCorrectionState());
        return data;
    }

    private Map<String, Object> storeOverlay(String merchantId) {
        CityGameOperationsStoreFactRow store = mapper.selectStore(merchantId);
        if (store == null) {
            Map<String, Object> missing = new LinkedHashMap<String, Object>();
            missing.put("merchantId", merchantId);
            missing.put("status", "MISSING");
            missing.put("missing", Boolean.TRUE);
            return missing;
        }
        return storeView(store);
    }

    private static Map<String, Object> storeView(CityGameOperationsStoreFactRow store) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("merchantId", store.getMerchantId());
        view.put("factStatus", store.getFactStatus());
        view.put("version", store.getVersion());
        view.put("caseId", store.getCaseId());
        view.put("actionId", store.getActionId());
        return view;
    }

    private Map<String, Object> envelope(long actorId) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("contract", CONTRACT);
        data.put("candidate", Boolean.TRUE);
        data.put("transport", TRANSPORT);
        data.put("actions", actions(actorId));
        return data;
    }

    private List<String> actions(long actorId) {
        List<String> actions = new ArrayList<String>();
        if (permissions.allows(actorId, CityGameOperationsPermissions.TRACE)) {
            actions.add("TRACE");
        }
        if (permissions.allows(actorId, CityGameOperationsPermissions.PROPOSE)) {
            actions.add("PROPOSE");
        }
        if (permissions.allows(actorId, CityGameOperationsPermissions.REVIEW)) {
            actions.add("REVIEW");
        }
        if (permissions.allows(actorId, CityGameOperationsPermissions.APPLY)) {
            actions.add("APPLY");
        }
        if (permissions.allows(actorId, CityGameOperationsPermissions.QUERY)) {
            actions.add("QUERY");
        }
        return actions;
    }

    private boolean canRead(long actorId) {
        return !actions(actorId).isEmpty();
    }

    private List<CityGameOperationsAuditRow> caseAudits(long caseId) {
        List<CityGameOperationsAuditRow> rows = new ArrayList<CityGameOperationsAuditRow>(mapper.selectAuditsByCaseId(caseId));
        Set<Long> seen = new LinkedHashSet<Long>();
        Set<String> requests = new LinkedHashSet<String>();
        for (CityGameOperationsAuditRow row : rows) {
            seen.add(row.getId());
            if (row.getRequestId() != null) {
                requests.add(row.getRequestId());
            }
        }
        for (String requestId : requests) {
            for (CityGameOperationsAuditRow row : mapper.selectAuditsByRequestId(requestId)) {
                if (seen.add(row.getId())) {
                    rows.add(row);
                }
            }
        }
        rows.sort(Comparator.comparing(CityGameOperationsAuditRow::getId));
        return rows;
    }

    private List<Map<String, Object>> summaries(List<CityGameOperationsAuditRow> rows) {
        List<Map<String, Object>> chain = new ArrayList<Map<String, Object>>();
        if (rows == null) {
            return chain;
        }
        for (CityGameOperationsAuditRow row : rows) {
            Map<String, Object> stored = CityGameOperationsTexts.map(row.getResultJson());
            Object data = stored.get("data");
            Map<?, ?> payload = data instanceof Map ? (Map<?, ?>) data : Map.of();
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("requestId", row.getRequestId());
            entry.put("action", row.getAction());
            entry.put("eventType", row.getEventType());
            entry.put("actorUserId", row.getActorUserId());
            entry.put("roleKeys", row.getRoleKeys());
            entry.put("httpStatus", row.getHttpStatus());
            entry.put("at", row.getCreateTime() == null ? null : row.getCreateTime().toInstant().toString());
            entry.put("errorCode", stored.get("errorCode"));
            entry.put("status", payload.get("status"));
            entry.put("correctionState", payload.get("correctionState"));
            entry.put("execution", payload.get("execution"));
            chain.add(entry);
        }
        return chain;
    }

    private static Map<String, Object> entry(CityGameOperationsRequestRow claim, CityGameOperationsOutcome outcome) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("requestId", claim.getRequestId());
        entry.put("action", claim.getAction());
        entry.put("eventType", "RESULT");
        entry.put("actorUserId", claim.getActorUserId());
        entry.put("roleKeys", claim.getRoleKeys());
        entry.put("httpStatus", outcome.httpStatus);
        entry.put("errorCode", outcome.errorCode);
        entry.put("status", outcome.data == null ? null : outcome.data.get("status"));
        entry.put("correctionState", outcome.data == null ? null : outcome.data.get("correctionState"));
        entry.put("execution", outcome.data == null ? null : outcome.data.get("execution"));
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static String placeMerchant(CityGameOperationsCaseRow row) {
        Object slots = CityGameOperationsTexts.map(row.getTraceJson()).get("slots");
        if (!(slots instanceof Map)) {
            return null;
        }
        Object place = ((Map<String, Object>) slots).get("placeFacts");
        if (!(place instanceof Map)) {
            return null;
        }
        Object value = ((Map<String, Object>) place).get("value");
        if (!(value instanceof Map)) {
            return null;
        }
        Object merchant = ((Map<String, Object>) value).get("merchantId");
        return merchant instanceof String ? (String) merchant : null;
    }

    private static CityGameOperationsOutcome invalid(JsonNode body, Set<String> allowed, boolean review) {
        if (body.has("approvalHash") || body.has("approved") || body.has("approval") || (!review && body.has("decision"))) {
            return fail(400, "CLIENT_APPROVAL_REJECTED", "客户端不能提交审批", null);
        }
        for (String key : ASSET_KEYS) {
            if (body.has(key)) {
                return fail(403, "ASSET_SCOPE_REJECTED", "不能修改费率或他队资产", null);
            }
        }
        if (body.has("fulfilled") || body.has("fulfilment") || body.has("evidence")) {
            return fail(400, "CLIENT_EVIDENCE_REJECTED", "不能提交履约", null);
        }
        if (body.has("owner") || body.has("role") || body.has("contentHash")) {
            return fail(400, "PROTECTED_FIELD", "该字段受保护", null);
        }
        List<String> unknown = new ArrayList<String>();
        Iterator<String> names = body.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("fields", unknown);
            return fail(400, "UNKNOWN_FIELD", "字段不在授权范围", data);
        }
        return null;
    }

    private CityGameOperationsOutcome denied() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("actions", List.of());
        data.put("candidate", Boolean.TRUE);
        data.put("transport", TRANSPORT);
        return fail(403, "NO_AUTHORITY", "没有该操作权限", data);
    }

    private static CityGameOperationsOutcome unknown(String requestId) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("requestId", requestId);
        data.put("state", "UNKNOWN");
        data.put("resent", Boolean.FALSE);
        data.put("candidate", Boolean.TRUE);
        data.put("transport", TRANSPORT);
        return fail(409, "UNKNOWN", "执行结果未知，不会再次发送", data);
    }

    private static CityGameOperationsOutcome conflictRequest(CityGameOperationsRequestRow existing) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("requestId", existing.getRequestId());
        data.put("state", existing.getState());
        data.put("original", restore(existing).data);
        return fail(409, "CONFLICT", "同一请求键的内容不一致", data);
    }

    private CityGameOperationsOutcome conflict(CityGameOperationsCaseRow row, long actorId) {
        return fail(409, "VERSION_CONFLICT", "版本冲突", row == null ? null : view(row, actorId));
    }

    private static CityGameOperationsOutcome restore(CityGameOperationsRequestRow row) {
        if (row == null || "UNKNOWN".equals(row.getState())) {
            return unknown(row == null ? null : row.getRequestId());
        }
        JsonNode stored = CityGameOperationsTexts.read(row.getResultJson());
        int status = stored.path("httpStatus").asInt(409);
        JsonNode code = stored.get("errorCode");
        String errorCode = code == null || code.isNull() ? null : code.asText();
        String message = stored.path("message").asText("操作成功");
        JsonNode dataNode = stored.get("data");
        Map<String, Object> data = dataNode == null || dataNode.isNull() ? null : CityGameOperationsTexts.mapper().convertValue(dataNode, LinkedHashMap.class);
        return status == 200 ? CityGameOperationsOutcome.ok(data) : fail(status, errorCode, message, data);
    }

    private static Map<String, Object> stored(CityGameOperationsOutcome outcome) {
        Map<String, Object> stored = new LinkedHashMap<String, Object>();
        stored.put("httpStatus", outcome.httpStatus);
        stored.put("errorCode", outcome.errorCode);
        stored.put("message", outcome.message);
        stored.put("data", outcome.data);
        return stored;
    }

    private static int scopeStatus(String code) {
        if ("NO_AUTHORITY".equals(code) || "ASSET_EXECUTION_DISABLED".equals(code)) {
            return 403;
        }
        if ("INVALID_STATE".equals(code) || "OBJECT_SCOPE".equals(code) || "STORE_SCOPE".equals(code) || "BINDING_MISMATCH".equals(code)) {
            return 409;
        }
        return 400;
    }

    private static String scopeMessage(String code) {
        return switch (code) {
            case "ASSET_EXECUTION_DISABLED" -> "资金和资产操作不能执行";
            case "OBJECT_SCOPE", "STORE_SCOPE" -> "纠错范围超出本店";
            case "INVALID_STATE" -> "当前状态不能执行";
            case "MISSING_PARAM" -> "缺少必要参数";
            default -> "字段不在授权范围";
        };
    }

    private static String id(JsonNode node) {
        if (node == null || !node.isTextual() || !ID.matcher(node.asText()).matches()) {
            return null;
        }
        return node.asText();
    }

    private static boolean isId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    private static String text(JsonNode node, int max) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        if (value.isEmpty() || value.length() > max) {
            return null;
        }
        return value;
    }

    private static Long optionalLong(JsonNode node) {
        if (node == null || node.isNull() || !node.isIntegralNumber() || !node.canConvertToLong()) {
            return null;
        }
        long value = node.longValue();
        if (value < 0 || value > MAX_SAFE) {
            return null;
        }
        return value;
    }

    private static CityGameOperationsOutcome fail(int status, String code, String message, Map<String, Object> data) {
        return CityGameOperationsOutcome.fail(status, code, message, data);
    }

    private interface Work {
        CityGameOperationsOutcome run(Opened opened);
    }

    private static final class Opened {
        final long actorId;
        final String action;
        final long expectedVersion;
        final JsonNode body;
        final CityGameOperationsRequestRow claim;

        Opened(long actorId, String action, long expectedVersion, JsonNode body, CityGameOperationsRequestRow claim) {
            this.actorId = actorId;
            this.action = action;
            this.expectedVersion = expectedVersion;
            this.body = body;
            this.claim = claim;
        }
    }
}

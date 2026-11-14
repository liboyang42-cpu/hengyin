package com.chengyinhub.business.service.merchantauthoring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.PmsSku;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Approval;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Draft;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Environment;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Job;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Release;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Revision;
import com.chengyinhub.business.mapper.merchantauthoring.MerchantAuthoringMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IPmsProductService;
import com.chengyinhub.business.service.IPmsSkuService;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Merchant AI draft, human review, and immutable public release.
 * Generation goes through {@link AiGatewayService} after the job row commits.
 */
@Service
public class MerchantAuthoringService implements PublicReleaseFacts {
    static final String FACT = "MERCHANT_FACT";
    static final String CONTENT = "THEME_CONTENT";
    private static final String AUDIT_FACT = "MERCHANT_AUTHORING_FACT";
    private static final String AUDIT_CONTENT = "MERCHANT_AUTHORING_CONTENT";
    private static final String AUDIT_SOURCE = "merchant_authoring";
    private static final String GATEWAY_BIZ = "theme_draft";
    private static final Set<String> MODEL_KEYS = Set.of(
            "canonical_plot", "allowed_variables", "approved_branches", "server_action_rules",
            "duration_minutes", "headcount", "business_segment", "staff_need", "noise_limit", "photo_limit",
            "accessibility_alternative", "offline_alternative", "fail_exit", "reward_rule_ref");
    private static final Set<String> GAME_KEYS = Set.of(
            "duration_minutes", "headcount", "business_segment", "staff_need", "noise_limit", "photo_limit",
            "accessibility_alternative", "offline_alternative", "fail_exit", "reward_rule_ref");
    private static final Set<String> FORBIDDEN = Set.of(
            "price", "stock", "inventory", "cash", "prize", "coupon", "order", "customer", "cost",
            "settlement", "reward_amount", "approved", "phone", "address");
    private static final Set<String> FACT_KEYS = Set.of(
            "product_id", "sku_id", "business_segment", "staff_need", "noise_limit", "photo_limit",
            "accessibility_alternative", "duration_minutes", "headcount", "reward_rule_ref");
    private static final Set<String> CONTENT_KEYS = Set.of(
            "canonical_plot", "allowed_variables", "approved_branches", "server_action_rules",
            "offline_alternative", "fail_exit");
    private static final Set<String> PUBLIC_KEYS = Set.of(
            "canonical_plot", "allowed_variables", "approved_branches", "accessibility_alternative",
            "offline_alternative", "fail_exit", "business_segment", "duration_minutes", "headcount",
            "noise_limit", "photo_limit");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuthoringTx tx;
    private final AiGatewayService gateway;
    private final IAuditTaskService audit;
    private final IPmsProductService products;
    private final IPmsSkuService skus;
    private final IMerchantAccessService access;
    private final ICmsTopicService topics;
    private final IMmsMerchantService merchants;
    private final MerchantAuthoringPolicy policy;
    private final EnvironmentObservationSource observations;
    Clock clock = Clock.systemUTC();

    @Autowired
    public MerchantAuthoringService(AuthoringTx tx, AiGatewayService gateway, IAuditTaskService audit,
            IPmsProductService products, IPmsSkuService skus, IMerchantAccessService access,
            ICmsTopicService topics, IMmsMerchantService merchants,
            @Autowired(required = false) MerchantAuthoringPolicy policy,
            @Autowired(required = false) EnvironmentObservationSource observations) {
        this.tx = tx;
        this.gateway = gateway;
        this.audit = audit;
        this.products = products;
        this.skus = skus;
        this.access = access;
        this.topics = topics;
        this.merchants = merchants;
        this.policy = policy;
        this.observations = observations;
    }

    public Map<String, Object> generate(long principal, Request request) {
        requireModel();
        MerchantAccessContext ctx = store(principal);
        Prepared prepared = prepare(ctx, request);
        if (prepared.requestId == null) throw fail("INPUT", HttpStatus.BAD_REQUEST);
        String fingerprint = fingerprint(ctx.getMerchantId(), prepared);
        Claim claim;
        try {
            claim = tx.call(mapper -> claim(mapper, principal, ctx, prepared, fingerprint));
        } catch (RuntimeException ex) {
            if (!duplicate(ex)) throw ex;
            Job existing = tx.call(mapper -> mapper.findJob(principal, prepared.requestId));
            if (existing == null) throw ex;
            if (!fingerprint.equals(existing.payloadFingerprint)) throw conflict(null);
            claim = new Claim(existing, false);
        }
        if (!claim.send) return jobView(claim.job);
        AiGatewayResult result;
        try {
            result = gateway.generate(principal, "merchant", GATEWAY_BIZ, systemPrompt(), userPrompt(prepared));
        } catch (AiProviderOutcomeUnknownException ex) {
            return mark(claim.job, "UNKNOWN", "PROVIDER_UNKNOWN", null, null);
        } catch (AiServiceException ex) {
            return mark(claim.job, "FAILED", ex.getReason().name(), null, null);
        }
        if (result == null || result.getText() == null || result.getText().isBlank() || !result.isSafetyOk()) {
            return mark(claim.job, "FAILED", "MODEL_EMPTY", result == null ? null : result.getTraceId(), null);
        }
        if (!policy.getModelVersion().equals(result.getModel())) {
            return mark(claim.job, "FAILED", "MODEL_UNAPPROVED", result.getTraceId(), null);
        }
        final ObjectNode body;
        try {
            body = acceptBody(readModel(result.getText()), claim.job.scene, claim.job.productId, claim.job.skuId, true);
        } catch (ServiceException ex) {
            return mark(claim.job, "FAILED", ex.getMessage(), result.getTraceId(), null);
        }
        try {
            return finish(claim.job, result, body);
        } catch (ServiceException ex) {
            return mark(claim.job, "FAILED", ex.getMessage(), result.getTraceId(), null);
        } catch (RuntimeException ex) {
            return mark(claim.job, "UNKNOWN", "LOCAL_UNKNOWN", result.getTraceId(), null);
        }
    }

    public Map<String, Object> findJob(long principal, String requestId) {
        store(principal);
        Job job = tx.call(mapper -> mapper.findJob(principal, required(requestId, 64)));
        if (job == null) throw fail("NOT_FOUND", HttpStatus.NOT_FOUND);
        return jobView(job);
    }

    public Map<String, Object> cancel(long principal, String requestId) {
        store(principal);
        Job job = tx.call(mapper -> {
            Job found = mapper.findJob(principal, required(requestId, 64));
            if (found == null) throw fail("NOT_FOUND", HttpStatus.NOT_FOUND);
            if ("CLAIMED".equals(found.status)) mapper.cancelJob(principal, found.id, clock.millis());
            return mapper.findJob(principal, found.requestId);
        });
        Map<String, Object> view = jobView(job);
        view.put("publishOutcome", "NONE");
        return view;
    }

    public Map<String, Object> createDraft(long principal, Request request) {
        MerchantAccessContext ctx = store(principal);
        Prepared prepared = prepare(ctx, request);
        Draft draft = newDraft(principal, ctx, prepared, 0);
        tx.call(mapper -> {
            mapper.insertDraft(draft);
            return draft;
        });
        return draftView(draft, null, "NONE");
    }

    public List<Map<String, Object>> listDrafts(long principal) {
        MerchantAccessContext ctx = store(principal);
        return tx.call(mapper -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Draft draft : mapper.listDrafts(ctx.getMerchantId())) {
                Release release = mapper.latestReleaseForDraft(draft.id);
                rows.add(draftView(draft, null, publishOutcome(release)));
            }
            return rows;
        });
    }

    public Map<String, Object> readDraft(long principal, String draftId) {
        MerchantAccessContext ctx = store(principal);
        return tx.call(mapper -> {
            Draft draft = mapper.lockDraft(required(draftId, 64), ctx.getMerchantId());
            if (draft == null) throw fail("NOT_FOUND", HttpStatus.NOT_FOUND);
            Revision revision = draft.currentVersion == null || draft.currentVersion == 0
                    ? null : mapper.findRevision(draft.id, draft.currentVersion);
            return draftView(draft, revision, publishOutcome(mapper.latestReleaseForDraft(draft.id)));
        });
    }

    public Map<String, Object> save(long principal, String draftId, Integer expectedVersion, JsonNode body) {
        MerchantAccessContext ctx = store(principal);
        if (expectedVersion == null || expectedVersion < 0) throw fail("VERSION_REQUIRED", HttpStatus.BAD_REQUEST);
        try {
            return tx.call(mapper -> {
                Draft draft = mapper.lockDraft(required(draftId, 64), ctx.getMerchantId());
                if (draft == null) throw fail("NOT_FOUND", HttpStatus.NOT_FOUND);
                if (!expectedVersion.equals(draft.currentVersion)) throw conflict(draft.currentVersion);
                ObjectNode stored = acceptBody(body, draft.scene, draft.productId, draft.skuId, false);
                Revision revision = revision(draft.id, expectedVersion + 1, stored, principal);
                mapper.insertRevision(revision);
                if (mapper.casDraftVersion(draft.id, ctx.getMerchantId(), expectedVersion, revision.version) != 1) {
                    throw conflict(draft.currentVersion);
                }
                mapper.invalidateApprovals(draft.id);
                draft.currentVersion = revision.version;
                return draftView(draft, revision, publishOutcome(mapper.latestReleaseForDraft(draft.id)));
            });
        } catch (RuntimeException ex) {
            if (!duplicate(ex)) throw ex;
            Draft latest = tx.call(mapper -> mapper.lockDraft(draftId, ctx.getMerchantId()));
            throw conflict(latest == null ? null : latest.currentVersion);
        }
    }

    public Map<String, Object> confirmFacts(long principal, String draftId, int version) {
        if (policy == null || !Boolean.TRUE.equals(policy.getMerchantReview())) throw fail("CONFIG_REVIEW", HttpStatus.FORBIDDEN);
        MerchantAccessContext ctx = store(principal);
        return review(ctx, principal, draftId, version, FACT, true);
    }

    public Map<String, Object> approveContent(long principal, String draftId, int version) {
        if (policy == null || !Boolean.TRUE.equals(policy.getThemeReview())) throw fail("CONFIG_REVIEW", HttpStatus.FORBIDDEN);
        return review(null, principal, draftId, version, CONTENT, false);
    }

    public Map<String, Object> reviewStatus(long principal, String draftId) {
        MerchantAccessContext ctx = store(principal);
        return tx.call(mapper -> {
            Draft draft = mapper.lockDraft(required(draftId, 64), ctx.getMerchantId());
            if (draft == null) throw fail("NOT_FOUND", HttpStatus.NOT_FOUND);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("draftId", draft.id);
            view.put("currentVersion", draft.currentVersion);
            view.put("merchantFact", approvalView(mapper.findApproval(draft.id, draft.currentVersion, FACT)));
            view.put("themeContent", approvalView(mapper.findApproval(draft.id, draft.currentVersion, CONTENT)));
            return view;
        });
    }

    public Map<String, Object> publish(long principal, String requestId, String draftId, int version) {
        if (policy == null || policy.getVisibility() == null || policy.getVisibility().isBlank()) {
            throw fail("CONFIG_VISIBILITY", HttpStatus.FORBIDDEN);
        }
        MerchantAccessContext ctx = store(principal);
        String key = required(requestId, 64);
        return tx.call(mapper -> {
            Draft draft = mapper.lockDraft(required(draftId, 64), ctx.getMerchantId());
            if (draft == null) throw fail("NOT_FOUND", HttpStatus.NOT_FOUND);
            String fingerprint = sha256(draft.id + ":" + version + ":" + ctx.getMerchantId() + ":" + draft.themeId);
            Release existing = mapper.findRelease(principal, key);
            if (existing != null) {
                if (!fingerprint.equals(existing.payloadFingerprint)) throw conflict(draft.currentVersion);
                return releaseView(existing);
            }
            if (!Integer.valueOf(version).equals(draft.currentVersion)) throw conflict(draft.currentVersion);
            Revision revision = mapper.findRevision(draft.id, version);
            if (revision == null) throw fail("NOT_FOUND", HttpStatus.NOT_FOUND);
            requireStillOnShelf(draft);
            Approval fact = requireApproval(mapper, draft.id, version, FACT, revision.factHash);
            requireConfirmerStillOnStore(fact.actorId, draft.merchantId);
            if (draft.themeId != null) {
                Approval content = requireApproval(mapper, draft.id, version, CONTENT, revision.contentHash);
                CmsTopic topic = topics.selectCmsTopicById(draft.themeId);
                if (topic == null || !content.actorId.equals(topic.getMemberId())) throw fail("THEME_FORBIDDEN", HttpStatus.FORBIDDEN);
                requireAudit(fact, AUDIT_FACT, revision.factHash);
                requireAudit(content, AUDIT_CONTENT, revision.contentHash);
            } else if (hasContent(revision.bodyJson)) {
                throw fail("THEME_REQUIRED", HttpStatus.FORBIDDEN);
            } else {
                requireAudit(fact, AUDIT_FACT, revision.factHash);
            }
            ObjectNode snapshot = publicSnapshot(revision.bodyJson);
            Release release = new Release();
            release.ownerId = principal;
            release.requestId = key;
            release.payloadFingerprint = fingerprint;
            release.draftId = draft.id;
            release.revisionId = revision.id;
            release.revisionVersion = version;
            release.merchantId = draft.merchantId;
            release.themeId = draft.themeId;
            release.runId = blankToNull(draft.runId);
            release.bindingKey = draft.bindingKey;
            release.snapshotJson = snapshot.toString();
            release.snapshotHash = sha256(canonical(snapshot));
            release.factHash = revision.factHash;
            release.contentHash = revision.contentHash;
            release.status = "PUBLISHED";
            release.visibility = policy.getVisibility();
            release.publishedAtMs = clock.millis();
            mapper.insertRelease(release);
            mapper.insertEnvironment(environmentRow(release, segment(snapshot)));
            return releaseView(release);
        });
    }

    public Map<String, Object> withdraw(long principal, long releaseId, String requestId) {
        MerchantAccessContext ctx = store(principal);
        String key = required(requestId, 64);
        return tx.call(mapper -> {
            Release release = mapper.findReleaseById(releaseId);
            if (release == null || !ctx.getMerchantId().equals(release.merchantId)) throw fail("NOT_FOUND", HttpStatus.NOT_FOUND);
            if (!principalEquals(release.ownerId, principal)) throw fail("FORBIDDEN", HttpStatus.FORBIDDEN);
            String before = release.snapshotJson;
            if ("PUBLISHED".equals(release.status)) {
                if (mapper.withdrawRelease(principal, releaseId, clock.millis(), key) != 1) {
                    throw fail("CONFLICT", HttpStatus.CONFLICT);
                }
            }
            Release current = mapper.findReleaseById(releaseId);
            if (current == null || !before.equals(current.snapshotJson) || !"WITHDRAWN".equals(current.status)) {
                throw fail("CONFLICT", HttpStatus.CONFLICT);
            }
            return releaseView(current);
        });
    }

    public Map<String, Object> findRelease(long principal, String requestId) {
        store(principal);
        Release release = tx.call(mapper -> mapper.findRelease(principal, required(requestId, 64)));
        if (release == null) throw fail("NOT_FOUND", HttpStatus.NOT_FOUND);
        return releaseView(release);
    }

    public Map<String, Object> readPublic(Long merchantId, Long themeId, Long releaseId, String runId, String bindingKey) {
        if (merchantId == null || bindingKey == null || bindingKey.isBlank()) return none();
        Release release = tx.call(mapper -> {
            if (releaseId != null) return mapper.findReleaseById(releaseId);
            return mapper.findPublic(merchantId, themeId, null, blankToNull(runId), bindingKey);
        });
        if (release == null || !merchantId.equals(release.merchantId) || !bindingKey.equals(release.bindingKey)) return none();
        if (themeId == null ? release.themeId != null : !themeId.equals(release.themeId)) return none();
        if (blankToNull(runId) == null ? release.runId != null : !blankToNull(runId).equals(release.runId)) return none();
        if (!"PUBLISHED".equals(release.status)) {
            Map<String, Object> denied = new LinkedHashMap<>();
            denied.put("outcome", release.status);
            denied.put("releaseId", release.id);
            denied.put("settled", false);
            return denied;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("outcome", "PUBLISHED");
        view.put("settled", true);
        view.put("releaseId", release.id);
        view.put("revisionVersion", release.revisionVersion);
        view.put("facts", project(release.snapshotJson));
        return view;
    }

    public Map<String, Object> environment(long principal, long releaseId) {
        MerchantAccessContext ctx = store(principal);
        Release release = tx.call(mapper -> mapper.findReleaseById(releaseId));
        if (release == null || !ctx.getMerchantId().equals(release.merchantId)) throw fail("NOT_FOUND", HttpStatus.NOT_FOUND);
        return environmentView(release);
    }

    public Map<String, Object> mainLine(Long merchantId, Long themeId) {
        Release release = tx.call(mapper -> mapper.latestPublished(merchantId, themeId));
        if (release == null) return none();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("outcome", "PUBLISHED");
        view.put("settled", true);
        view.put("releaseId", release.id);
        view.put("revisionVersion", release.revisionVersion);
        view.put("facts", project(release.snapshotJson));
        return view;
    }

    @Override
    public String publicPrompt(Long merchantId, Long themeId, Long releaseId, String runId, String bindingKey) {
        Map<String, Object> read = readPublic(merchantId, themeId, releaseId, runId, bindingKey);
        if (!"PUBLISHED".equals(read.get("outcome"))) return null;
        return promptLine(read.get("facts"));
    }

    @Override
    public String publicPromptForShopMember(Long merchantMemberId, Long themeId) {
        if (merchantMemberId == null || themeId == null) return null;
        MmsMerchant merchant = merchants.selectMmsMerchantByMemberId(merchantMemberId);
        if (merchant == null || merchant.getId() == null) return null;
        return publicPrompt(merchant.getId(), themeId, null, null, "THEME");
    }

    private Map<String, Object> review(MerchantAccessContext storeCtx, long principal, String draftId, int version,
            String kind, boolean storeActor) {
        return tx.call(mapper -> {
            Draft draft = storeActor
                    ? mapper.lockDraft(required(draftId, 64), storeCtx.getMerchantId())
                    : lockAny(mapper, required(draftId, 64));
            if (draft == null) throw fail("NOT_FOUND", HttpStatus.NOT_FOUND);
            if (!storeActor) {
                if (draft.themeId == null) throw fail("THEME_REQUIRED", HttpStatus.FORBIDDEN);
                CmsTopic topic = topics.selectCmsTopicById(draft.themeId);
                if (topic == null || topic.getMemberId() == null || !topic.getMemberId().equals(principal)) {
                    throw fail("THEME_FORBIDDEN", HttpStatus.FORBIDDEN);
                }
            }
            if (!Integer.valueOf(version).equals(draft.currentVersion)) throw conflict(draft.currentVersion);
            Revision revision = mapper.findRevision(draft.id, version);
            if (revision == null) throw fail("NOT_FOUND", HttpStatus.NOT_FOUND);
            String hash = FACT.equals(kind) ? revision.factHash : revision.contentHash;
            Approval existing = mapper.findApproval(draft.id, version, kind);
            if (existing != null) {
                if (!hash.equals(existing.subjectHash) || !principalEquals(existing.actorId, principal)) {
                    throw conflict(version);
                }
                return approvalView(existing);
            }
            String snapshot = auditSnapshot(kind, draft.id, version, hash);
            Long taskId = audit.submit(FACT.equals(kind) ? AUDIT_FACT : AUDIT_CONTENT, revision.id, principal,
                    AUDIT_SOURCE, snapshot);
            AuditReviewDTO decision = new AuditReviewDTO();
            decision.setStatus(AuditTask.STATUS_APPROVED);
            decision.setRequestId(draft.id + ":" + version + ":" + kind);
            decision.setActorRoleCode(kind);
            if (audit.review(taskId, principal, decision) != 1) throw fail("AUDIT_UNCONFIRMED", HttpStatus.CONFLICT);
            requireTask(taskId, principal, FACT.equals(kind) ? AUDIT_FACT : AUDIT_CONTENT, hash);
            Approval approval = new Approval();
            approval.draftId = draft.id;
            approval.revisionId = revision.id;
            approval.revisionVersion = version;
            approval.kind = kind;
            approval.actorId = principal;
            approval.subjectHash = hash;
            approval.auditTaskId = taskId;
            approval.status = "APPROVED";
            approval.createdAtMs = clock.millis();
            mapper.insertApproval(approval);
            return approvalView(approval);
        });
    }

    private Draft lockAny(MerchantAuthoringMapper mapper, String draftId) {
        Draft found = mapper.findDraftById(draftId);
        if (found == null) return null;
        return mapper.lockDraft(found.id, found.merchantId);
    }

    private Claim claim(MerchantAuthoringMapper mapper, long principal, MerchantAccessContext ctx,
            Prepared prepared, String fingerprint) {
        Job existing = mapper.findJob(principal, prepared.requestId);
        if (existing != null) {
            if (!fingerprint.equals(existing.payloadFingerprint)) throw conflict(null);
            return new Claim(existing, false);
        }
        Integer expected = 0;
        if (prepared.draftId != null) {
            Draft draft = mapper.lockDraft(prepared.draftId, ctx.getMerchantId());
            if (draft == null) throw fail("NOT_FOUND", HttpStatus.NOT_FOUND);
            expected = draft.currentVersion;
        }
        Job job = new Job();
        job.ownerId = principal;
        job.requestId = prepared.requestId;
        job.payloadFingerprint = fingerprint;
        job.merchantId = ctx.getMerchantId();
        job.themeId = prepared.themeId;
        job.draftId = prepared.draftId;
        job.productId = prepared.productId;
        job.skuId = prepared.skuId;
        job.templateId = prepared.templateId;
        job.factVersion = prepared.factVersion;
        job.promptVersion = policy.getPromptVersion();
        job.modelVersion = policy.getModelVersion();
        job.status = "CLAIMED";
        job.scene = prepared.scene;
        job.runId = prepared.runId;
        job.bindingKey = prepared.bindingKey;
        job.expectedVersion = expected;
        job.createdAtMs = clock.millis();
        job.updatedAtMs = job.createdAtMs;
        mapper.insertJob(job);
        return new Claim(job, true);
    }

    private Map<String, Object> finish(Job claimed, AiGatewayResult result, ObjectNode body) {
        String hash = sha256(canonical(body));
        return tx.call(mapper -> {
            Job job = mapper.findJob(claimed.ownerId, claimed.requestId);
            if (job == null || !"CLAIMED".equals(job.status)) return jobView(job);
            Draft draft;
            int nextVersion;
            if (job.draftId == null) {
                draft = new Draft();
                draft.id = "d" + UUID.randomUUID().toString().replace("-", "");
                draft.ownerId = job.ownerId;
                draft.merchantId = job.merchantId;
                draft.themeId = job.themeId;
                draft.productId = job.productId;
                draft.skuId = job.skuId;
                draft.templateId = job.templateId;
                draft.scene = job.scene;
                draft.runId = job.runId;
                draft.bindingKey = job.bindingKey;
                draft.factVersion = job.factVersion;
                draft.currentVersion = 0;
                draft.createdAtMs = clock.millis();
                mapper.insertDraft(draft);
                nextVersion = 1;
            } else {
                draft = mapper.lockDraft(job.draftId, job.merchantId);
                if (draft == null || !job.expectedVersion.equals(draft.currentVersion)) {
                    job.status = "FAILED";
                    job.errorCode = "REVISION_CONFLICT";
                    job.costRef = result.getTraceId();
                    job.updatedAtMs = clock.millis();
                    mapper.updateClaimedJob(job);
                    return jobView(mapper.findJob(job.ownerId, job.requestId));
                }
                nextVersion = draft.currentVersion + 1;
            }
            Revision revision = revision(draft.id, nextVersion, body, job.ownerId);
            mapper.insertRevision(revision);
            if (draft.currentVersion != 0 || job.draftId != null) {
                if (mapper.casDraftVersion(draft.id, draft.merchantId, draft.currentVersion, nextVersion) != 1) {
                    throw fail("REVISION_CONFLICT", HttpStatus.CONFLICT);
                }
                mapper.invalidateApprovals(draft.id);
            } else {
                mapper.casDraftVersion(draft.id, draft.merchantId, 0, 1);
            }
            job.draftId = draft.id;
            job.status = "SUCCEEDED";
            job.costRef = result.getTraceId();
            job.outputHash = hash;
            job.modelVersion = result.getModel();
            job.promptVersion = policy.getPromptVersion();
            job.errorCode = null;
            job.updatedAtMs = clock.millis();
            if (mapper.updateClaimedJob(job) != 1) return jobView(mapper.findJob(job.ownerId, job.requestId));
            return jobView(job);
        });
    }

    private Map<String, Object> mark(Job claimed, String status, String error, String costRef, String hash) {
        return tx.call(mapper -> {
            Job job = mapper.findJob(claimed.ownerId, claimed.requestId);
            if (job != null && "CLAIMED".equals(job.status)) {
                job.status = status;
                job.errorCode = error;
                job.costRef = costRef;
                job.outputHash = hash;
                job.updatedAtMs = clock.millis();
                mapper.updateClaimedJob(job);
            }
            return jobView(mapper.findJob(claimed.ownerId, claimed.requestId));
        });
    }

    private Prepared prepare(MerchantAccessContext ctx, Request request) {
        if (request == null) throw fail("INPUT", HttpStatus.BAD_REQUEST);
        Prepared prepared = new Prepared();
        prepared.requestId = request.requestId == null ? null : required(request.requestId, 64);
        prepared.factVersion = required(request.factVersion, 64);
        prepared.templateId = blankToNull(request.templateId);
        prepared.productId = request.productId;
        prepared.skuId = request.skuId;
        prepared.themeId = request.themeId;
        prepared.runId = blankToNull(request.runId);
        prepared.draftId = blankToNull(request.draftId);
        if (prepared.runId != null && prepared.runId.length() > 64) throw fail("INPUT", HttpStatus.BAD_REQUEST);
        boolean askedInStore = "IN_STORE_GAME".equals(request.scene);
        boolean approvedInStore = prepared.templateId != null && policy != null && policy.getInStoreTemplateIds() != null
                && policy.getInStoreTemplateIds().contains(prepared.templateId);
        if (askedInStore && !approvedInStore) throw fail("CONFIG_TEMPLATE", HttpStatus.FORBIDDEN);
        if (prepared.templateId != null) {
            if (policy == null || policy.getTemplateIds() == null || !policy.getTemplateIds().contains(prepared.templateId)) {
                throw fail("CONFIG_TEMPLATE", HttpStatus.FORBIDDEN);
            }
        }
        prepared.scene = approvedInStore ? "IN_STORE_GAME" : "PRODUCT";
        if (prepared.productId == null && !"IN_STORE_GAME".equals(prepared.scene)) throw fail("PRODUCT_REQUIRED", HttpStatus.BAD_REQUEST);
        if (prepared.productId != null) requireProduct(ctx.getMerchantId(), prepared.productId, prepared.skuId);
        if (prepared.themeId != null && topics.selectCmsTopicById(prepared.themeId) == null) {
            throw fail("THEME_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        prepared.bindingKey = prepared.themeId == null ? "STORE" : "THEME";
        return prepared;
    }

    private void requireProduct(Long merchantId, Long productId, Long skuId) {
        PmsProduct product = products.selectPmsProductById(productId);
        if (product == null || !merchantId.equals(product.getMerchantId()) || !Integer.valueOf(0).equals(product.getDelFlag())) {
            throw fail("PRODUCT_FORBIDDEN", HttpStatus.FORBIDDEN);
        }
        if (skuId == null) return;
        PmsSku sku = skus.selectPmsSkuById(skuId);
        if (sku == null || !productId.equals(sku.getProductId())) throw fail("PRODUCT_FORBIDDEN", HttpStatus.FORBIDDEN);
    }

    private void requireStillOnShelf(Draft draft) {
        if (draft.productId != null) requireProduct(draft.merchantId, draft.productId, draft.skuId);
    }

    private void requireModel() {
        if (policy == null || policy.getModelVersion() == null || policy.getModelVersion().isBlank()
                || policy.getPromptVersion() == null || policy.getPromptVersion().isBlank()) {
            throw fail("CONFIG_MODEL", HttpStatus.FORBIDDEN);
        }
        if (policy.getCostLimit() == null) throw fail("CONFIG_COST", HttpStatus.FORBIDDEN);
    }

    private MerchantAccessContext store(long principal) {
        return access.require(principal, MerchantPermission.PROJECT_MANAGE);
    }

    private void requireConfirmerStillOnStore(Long actorId, Long merchantId) {
        MerchantAccessContext confirmer = access.resolve(actorId);
        if (confirmer == null || !merchantId.equals(confirmer.getMerchantId())
                || !confirmer.hasPermission(MerchantPermission.PROJECT_MANAGE)) {
            throw fail("STAFF_REVOKED", HttpStatus.FORBIDDEN);
        }
    }

    private Approval requireApproval(MerchantAuthoringMapper mapper, String draftId, int version, String kind, String hash) {
        Approval approval = mapper.findApproval(draftId, version, kind);
        if (approval == null || !"APPROVED".equals(approval.status) || !hash.equals(approval.subjectHash)) {
            throw fail("REVIEW_REQUIRED", HttpStatus.FORBIDDEN);
        }
        return approval;
    }

    private void requireAudit(Approval approval, String bizType, String hash) {
        requireTask(approval.auditTaskId, approval.actorId, bizType, hash);
    }

    private void requireTask(Long taskId, Long actorId, String bizType, String hash) {
        AuditTask task = audit.selectAuditTaskById(taskId);
        if (task == null || !AuditTask.STATUS_APPROVED.equals(task.getStatus()) || !bizType.equals(task.getBizType())
                || !actorId.equals(task.getSubmitterId()) || task.getSnapshotJson() == null
                || !task.getSnapshotJson().contains(hash)) {
            throw fail("AUDIT_UNCONFIRMED", HttpStatus.CONFLICT);
        }
    }

    private ObjectNode acceptBody(JsonNode raw, String scene, Long productId, Long skuId, boolean fromModel) {
        if (raw == null || !raw.isObject()) throw fail(fromModel ? "MODEL_JSON" : "INPUT", HttpStatus.BAD_REQUEST);
        ObjectNode out = JSON.createObjectNode();
        Iterator<String> names = raw.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            if (FORBIDDEN.contains(key)) throw fail(fromModel ? "MODEL_FORBIDDEN" : "CLIENT_SALE_FIELD", HttpStatus.BAD_REQUEST);
            if (!MODEL_KEYS.contains(key)) throw fail(fromModel ? "MODEL_FIELD" : "INPUT", HttpStatus.BAD_REQUEST);
            JsonNode value = raw.get(key);
            if ("approved_branches".equals(key)) out.set(key, branches(value));
            else if ("allowed_variables".equals(key)) out.set(key, variables(value));
            else if ("server_action_rules".equals(key)) out.set(key, rules(value));
            else if ("duration_minutes".equals(key) || "headcount".equals(key)) out.put(key, positiveInt(value));
            else if (value != null && value.isTextual()) out.put(key, value.asText());
            else throw fail(fromModel ? "MODEL_FIELD" : "INPUT", HttpStatus.BAD_REQUEST);
        }
        if (out.has("business_segment")) {
            String segment = out.get("business_segment").asText();
            if (policy == null || policy.getBranches() == null) throw fail("CONFIG_BRANCH", HttpStatus.FORBIDDEN);
            if (!policy.getBranches().contains(segment)) throw fail("UNAPPROVED_BRANCH", HttpStatus.BAD_REQUEST);
        }
        if ("IN_STORE_GAME".equals(scene)) {
            for (String key : GAME_KEYS) {
                JsonNode value = out.get(key);
                if (value == null || value.asText().isBlank()) throw fail("INCOMPLETE_GAME", HttpStatus.BAD_REQUEST);
            }
        }
        if (productId != null) out.put("product_id", productId);
        if (skuId != null) out.put("sku_id", skuId);
        return out;
    }

    private ArrayNode branches(JsonNode value) {
        if (value == null || !value.isArray()) throw fail("UNAPPROVED_BRANCH", HttpStatus.BAD_REQUEST);
        if (policy == null || policy.getBranches() == null) throw fail("CONFIG_BRANCH", HttpStatus.FORBIDDEN);
        ArrayNode out = JSON.createArrayNode();
        for (JsonNode item : value) {
            if (!item.isTextual() || !policy.getBranches().contains(item.asText())) throw fail("UNAPPROVED_BRANCH", HttpStatus.BAD_REQUEST);
            out.add(item.asText());
        }
        return out;
    }

    private ArrayNode variables(JsonNode value) {
        if (value == null || !value.isArray() || value.size() > 32) throw fail("MODEL_FIELD", HttpStatus.BAD_REQUEST);
        ArrayNode out = JSON.createArrayNode();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 64) throw fail("MODEL_FIELD", HttpStatus.BAD_REQUEST);
            out.add(item.asText());
        }
        return out;
    }

    private ObjectNode rules(JsonNode value) {
        if (value == null || !value.isObject()) throw fail("MODEL_FIELD", HttpStatus.BAD_REQUEST);
        ObjectNode out = JSON.createObjectNode();
        Iterator<String> names = value.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            if (FORBIDDEN.contains(key)) throw fail("MODEL_FORBIDDEN", HttpStatus.BAD_REQUEST);
            if (!"fail_exit".equals(key) && !"offline_alternative".equals(key)) throw fail("MODEL_FIELD", HttpStatus.BAD_REQUEST);
            if (!value.get(key).isTextual()) throw fail("MODEL_FIELD", HttpStatus.BAD_REQUEST);
            out.put(key, value.get(key).asText());
        }
        return out;
    }

    private int positiveInt(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || value.asInt() <= 0) throw fail("INCOMPLETE_GAME", HttpStatus.BAD_REQUEST);
        return value.asInt();
    }

    private JsonNode readModel(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start < 0 || end <= start) throw fail("MODEL_JSON", HttpStatus.BAD_REQUEST);
            trimmed = trimmed.substring(start + 1, end).trim();
        }
        try {
            return JSON.readTree(trimmed);
        } catch (Exception ex) {
            throw fail("MODEL_JSON", HttpStatus.BAD_REQUEST);
        }
    }

    private Revision revision(String draftId, int version, ObjectNode body, long actor) {
        Revision revision = new Revision();
        revision.draftId = draftId;
        revision.version = version;
        revision.bodyJson = body.toString();
        revision.bodyHash = sha256(canonical(body));
        revision.factHash = sha256(canonical(subset(body, FACT_KEYS)));
        revision.contentHash = sha256(canonical(subset(body, CONTENT_KEYS)));
        revision.createdBy = actor;
        revision.createdAtMs = clock.millis();
        return revision;
    }

    private ObjectNode publicSnapshot(String bodyJson) {
        try {
            JsonNode body = JSON.readTree(bodyJson);
            ObjectNode out = JSON.createObjectNode();
            Iterator<String> names = body.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                if (FORBIDDEN.contains(key) || "reward_rule_ref".equals(key) || "staff_need".equals(key)) continue;
                if (PUBLIC_KEYS.contains(key) || "product_id".equals(key) || "sku_id".equals(key)) out.set(key, body.get(key));
            }
            return out;
        } catch (Exception ex) {
            throw fail("INPUT", HttpStatus.BAD_REQUEST);
        }
    }

    private Map<String, Object> storedBody(String bodyJson) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            JsonNode node = JSON.readTree(bodyJson);
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                if (!FORBIDDEN.contains(key)) body.put(key, JSON.convertValue(node.get(key), Object.class));
            }
        } catch (Exception ex) {
            return body;
        }
        return body;
    }

    private Map<String, Object> project(String snapshotJson) {
        Map<String, Object> facts = new LinkedHashMap<>();
        try {
            JsonNode node = JSON.readTree(snapshotJson);
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                if (PUBLIC_KEYS.contains(key)) facts.put(key, JSON.convertValue(node.get(key), Object.class));
            }
        } catch (Exception ex) {
            return facts;
        }
        return facts;
    }

    private boolean hasContent(String bodyJson) {
        try {
            JsonNode body = JSON.readTree(bodyJson);
            for (String key : CONTENT_KEYS) {
                JsonNode value = body.get(key);
                if (value != null && !value.isNull() && !(value.isTextual() && value.asText().isBlank())
                        && !(value.isArray() && value.isEmpty()) && !(value.isObject() && value.isEmpty())) return true;
            }
        } catch (Exception ex) {
            return true;
        }
        return false;
    }

    private Environment environmentRow(Release release, String authoredSegment) {
        Live live = live(release.merchantId, authoredSegment);
        Environment row = new Environment();
        row.releaseId = release.id;
        row.provider = live.provider;
        row.source = live.source;
        row.observedAtMs = live.observedAtMs;
        row.expiresAtMs = live.expiresAtMs;
        row.timezone = live.timezone;
        row.accuracy = live.accuracy;
        row.branch = live.branch;
        row.weather = live.weather;
        row.action = live.action;
        row.storeHours = live.storeHours;
        row.createdAtMs = clock.millis();
        return row;
    }

    private Map<String, Object> environmentView(Release release) {
        Environment stored = tx.call(mapper -> mapper.findEnvironment(release.id));
        String authored = stored == null ? null : stored.branch;
        Live live = live(release.merchantId, authored);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("releaseId", release.id);
        view.put("recordedBranch", stored == null ? null : stored.branch);
        view.put("recordedAction", stored == null ? null : stored.action);
        view.put("provider", live.provider);
        view.put("source", live.source);
        view.put("observedAt", live.observedAtMs);
        view.put("expiresAt", live.expiresAtMs);
        view.put("timezone", live.timezone);
        view.put("accuracy", live.accuracy);
        view.put("branch", live.branch);
        view.put("weather", live.weather);
        view.put("action", live.action);
        view.put("storeHours", live.storeHours);
        return view;
    }

    private Live live(Long merchantId, String authoredSegment) {
        MmsMerchant merchant = merchantId == null ? null : merchants.selectMmsMerchantById(merchantId);
        Integer businessStatus = merchant == null ? null : merchant.getBusinessStatus();
        String businessTime = merchant == null ? null : blankToNull(merchant.getBusinessTime());
        Live live = new Live();
        live.storeHours = businessTime;
        live.source = "STORE_CLOCK";
        live.accuracy = "STORE_FACT";
        EnvironmentObservationSource.Observation observation = observation(merchantId);
        if (observation != null) {
            live.provider = policy.getEnvironmentProvider();
            live.source = observation.source;
            live.observedAtMs = observation.observedAtMs;
            live.expiresAtMs = observation.expiresAtMs;
            live.timezone = observation.timezone;
            live.accuracy = observation.accuracy;
            live.weather = observation.condition;
        }
        if (businessStatus != null && businessStatus == 0) {
            live.branch = "CLOSED";
            live.action = "EXIT";
            return danger(live);
        }
        String zone = policy == null ? null : blankToNull(policy.getTimezone());
        LocalTime now = null;
        if (zone != null) {
            try {
                now = ZonedDateTime.ofInstant(clock.instant(), ZoneId.of(zone)).toLocalTime();
                if (live.timezone == null) live.timezone = zone;
            } catch (Exception ex) {
                now = null;
            }
        }
        if (now == null || !insideHours(businessTime, now)) {
            live.branch = now == null || businessTime == null ? "UNAVAILABLE" : "CLOSED";
            live.action = "EXIT";
            return danger(live);
        }
        String segment = now.getHour() < 12 ? "MORNING" : now.getHour() < 18 ? "AFTERNOON" : "EVENING";
        if (policy == null || policy.getBranches() == null || !policy.getBranches().contains(segment)) {
            live.branch = "UNAVAILABLE";
            live.action = "EXIT";
            return danger(live);
        }
        live.branch = segment;
        live.action = authoredSegment != null && dayPart(authoredSegment) && !authoredSegment.equals(segment) ? "EXIT" : "CONTINUE";
        return danger(live);
    }

    private Live danger(Live live) {
        if (!"DANGEROUS".equals(live.weather)) return live;
        boolean safe = policy != null && policy.getWeatherSafeBranches() != null
                && policy.getWeatherSafeBranches().contains(live.branch);
        if (!safe) live.action = "EXIT";
        return live;
    }

    private EnvironmentObservationSource.Observation observation(Long merchantId) {
        if (policy == null || policy.getEnvironmentProvider() == null || policy.getEnvironmentProvider().isBlank()
                || observations == null || !policy.getEnvironmentProvider().equals(observations.providerId())) return null;
        EnvironmentObservationSource.Observation current = observations.current(merchantId);
        if (current == null || current.observedAtMs == null || current.expiresAtMs == null
                || current.expiresAtMs <= clock.millis()) return null;
        return current;
    }

    private static boolean insideHours(String businessTime, LocalTime now) {
        if (businessTime == null || !businessTime.matches("\\d{2}:\\d{2}-\\d{2}:\\d{2}")) return false;
        LocalTime open = LocalTime.parse(businessTime.substring(0, 5));
        LocalTime close = LocalTime.parse(businessTime.substring(6));
        return !now.isBefore(open) && now.isBefore(close);
    }

    private static boolean dayPart(String branch) {
        return "MORNING".equals(branch) || "AFTERNOON".equals(branch) || "EVENING".equals(branch);
    }

    private static String segment(ObjectNode snapshot) {
        JsonNode node = snapshot.get("business_segment");
        return node == null || !node.isTextual() ? null : node.asText();
    }

    private Draft newDraft(long principal, MerchantAccessContext ctx, Prepared prepared, int version) {
        Draft draft = new Draft();
        draft.id = "d" + UUID.randomUUID().toString().replace("-", "");
        draft.ownerId = principal;
        draft.merchantId = ctx.getMerchantId();
        draft.themeId = prepared.themeId;
        draft.productId = prepared.productId;
        draft.skuId = prepared.skuId;
        draft.templateId = prepared.templateId;
        draft.scene = prepared.scene;
        draft.runId = prepared.runId;
        draft.bindingKey = prepared.bindingKey;
        draft.factVersion = prepared.factVersion;
        draft.currentVersion = version;
        draft.createdAtMs = clock.millis();
        return draft;
    }

    private Map<String, Object> jobView(Job job) {
        Map<String, Object> view = new LinkedHashMap<>();
        String outcome = job == null ? "UNKNOWN" : "CLAIMED".equals(job.status) ? "UNKNOWN" : job.status;
        view.put("outcome", outcome);
        view.put("settled", "SUCCEEDED".equals(outcome) || "FAILED".equals(outcome) || "CANCELLED".equals(outcome));
        if (job == null) return view;
        view.put("jobId", job.id);
        view.put("requestId", job.requestId);
        view.put("status", job.status);
        view.put("draftId", job.draftId);
        view.put("errorCode", job.errorCode);
        view.put("costRef", job.costRef);
        view.put("outputHash", job.outputHash);
        view.put("modelVersion", job.modelVersion);
        view.put("promptVersion", job.promptVersion);
        view.put("publishOutcome", "NONE");
        return view;
    }

    private Map<String, Object> draftView(Draft draft, Revision revision, String publishOutcome) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("outcome", "DRAFT");
        view.put("draftId", draft.id);
        view.put("currentVersion", draft.currentVersion);
        view.put("merchantId", draft.merchantId);
        view.put("themeId", draft.themeId);
        view.put("scene", draft.scene);
        view.put("publishOutcome", publishOutcome);
        view.put("published", "PUBLISHED".equals(publishOutcome));
        if (revision != null) {
            view.put("version", revision.version);
            view.put("bodyHash", revision.bodyHash);
            view.put("body", storedBody(revision.bodyJson));
        }
        return view;
    }

    private Map<String, Object> releaseView(Release release) {
        Map<String, Object> view = new LinkedHashMap<>();
        String outcome = "CLAIMED".equals(release.status) ? "UNKNOWN" : release.status;
        view.put("outcome", outcome);
        view.put("settled", "PUBLISHED".equals(outcome) || "WITHDRAWN".equals(outcome));
        view.put("releaseId", release.id);
        view.put("requestId", release.requestId);
        view.put("draftId", release.draftId);
        view.put("revisionVersion", release.revisionVersion);
        view.put("snapshotHash", release.snapshotHash);
        view.put("publishOutcome", outcome);
        view.put("facts", project(release.snapshotJson));
        return view;
    }

    private Map<String, Object> approvalView(Approval approval) {
        Map<String, Object> view = new LinkedHashMap<>();
        if (approval == null) {
            view.put("outcome", "NONE");
            return view;
        }
        view.put("outcome", approval.status);
        view.put("kind", approval.kind);
        view.put("actorId", approval.actorId);
        view.put("version", approval.revisionVersion);
        view.put("subjectHash", approval.subjectHash);
        view.put("auditTaskId", approval.auditTaskId);
        return view;
    }

    private static String publishOutcome(Release release) {
        if (release == null) return "NONE";
        if ("CLAIMED".equals(release.status)) return "UNKNOWN";
        return release.status;
    }

    private static Map<String, Object> none() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("outcome", "NONE");
        view.put("settled", false);
        view.put("facts", Map.of());
        return view;
    }

    private String promptLine(Object facts) {
        if (!(facts instanceof Map<?, ?> map) || map.isEmpty()) return null;
        StringBuilder line = new StringBuilder();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!PUBLIC_KEYS.contains(key)) continue;
            if (line.length() > 0) line.append('；');
            line.append(key).append('=').append(entry.getValue());
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static String systemPrompt() {
        return "只输出一个 JSON 对象，键只能是 canonical_plot、allowed_variables、approved_branches、server_action_rules、"
                + "duration_minutes、headcount、business_segment、staff_need、noise_limit、photo_limit、"
                + "accessibility_alternative、offline_alternative、fail_exit、reward_rule_ref。"
                + "不要写价格、库存、订单、客户、成本、现金、奖品或未批准分支。";
    }

    private String userPrompt(Prepared prepared) {
        ObjectNode node = JSON.createObjectNode();
        node.put("scene", prepared.scene);
        node.put("factVersion", prepared.factVersion);
        if (prepared.templateId != null) node.put("templateId", prepared.templateId);
        if (prepared.productId != null) node.put("productId", prepared.productId);
        if (policy.getBranches() != null) node.set("approvedBranches", JSON.valueToTree(policy.getBranches()));
        return node.toString();
    }

    private String fingerprint(Long merchantId, Prepared prepared) {
        ObjectNode node = JSON.createObjectNode();
        node.put("merchantId", merchantId);
        if (prepared.draftId != null) node.put("draftId", prepared.draftId);
        if (prepared.productId != null) node.put("productId", prepared.productId);
        if (prepared.skuId != null) node.put("skuId", prepared.skuId);
        if (prepared.templateId != null) node.put("templateId", prepared.templateId);
        if (prepared.themeId != null) node.put("themeId", prepared.themeId);
        node.put("factVersion", prepared.factVersion);
        node.put("scene", prepared.scene);
        if (prepared.runId != null) node.put("runId", prepared.runId);
        return sha256(canonical(node));
    }

    private static ObjectNode subset(ObjectNode body, Set<String> keys) {
        ObjectNode out = JSON.createObjectNode();
        for (String key : keys) if (body.has(key)) out.set(key, body.get(key));
        return out;
    }

    private static String canonical(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) {
            TreeMap<String, String> ordered = new TreeMap<>();
            node.fields().forEachRemaining(entry -> ordered.put(entry.getKey(), canonical(entry.getValue())));
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> entry : ordered.entrySet()) {
                if (!first) builder.append(',');
                first = false;
                builder.append('"').append(entry.getKey()).append('"').append(':').append(entry.getValue());
            }
            return builder.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) builder.append(',');
                builder.append(canonical(node.get(i)));
            }
            return builder.append(']').toString();
        }
        return node.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) builder.append(String.format("%02x", item));
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String auditSnapshot(String kind, String draftId, int version, String hash) {
        return "{\"kind\":\"" + kind + "\",\"draftId\":\"" + draftId + "\",\"version\":" + version + ",\"hash\":\"" + hash + "\"}";
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isWhitespace)) {
            throw fail("INPUT", HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean principalEquals(Long left, long right) {
        return left != null && left.longValue() == right;
    }

    private static boolean duplicate(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            String message = cursor.getMessage();
            if (message != null && (message.contains("Unique") || message.contains("UNIQUE")
                    || message.contains("Duplicate") || message.contains("duplicate"))) return true;
        }
        return false;
    }

    private static ServiceException fail(String code, int http) {
        return new ServiceException(code, http);
    }

    private static ServiceException conflict(Integer version) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outcome", "CONFLICT");
        data.put("currentVersion", version);
        return new ServiceException("CONFLICT", HttpStatus.CONFLICT).setData(data);
    }

    private static final class Claim {
        final Job job;
        final boolean send;
        Claim(Job job, boolean send) { this.job = job; this.send = send; }
    }

    private static final class Prepared {
        String requestId;
        String factVersion;
        String templateId;
        Long productId;
        Long skuId;
        Long themeId;
        String runId;
        String draftId;
        String scene;
        String bindingKey;
    }

    private static final class Live {
        String provider;
        String source;
        Long observedAtMs;
        Long expiresAtMs;
        String timezone;
        String accuracy;
        String branch;
        String weather;
        String action;
        String storeHours;
    }

    /** Caller-supplied generation or draft input. Approval fields are not accepted. */
    public static final class Request {
        public String requestId;
        public String factVersion;
        public String templateId;
        public Long productId;
        public Long skuId;
        public Long themeId;
        public String runId;
        public String draftId;
        public String scene;
    }
}

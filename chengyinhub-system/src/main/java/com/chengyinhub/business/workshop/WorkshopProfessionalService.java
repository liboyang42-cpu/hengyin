package com.chengyinhub.business.workshop;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.review.ReviewStateMachine;
import com.chengyinhub.business.service.impl.WorkshopService;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * W18 professional materialization of a paid, installed workshop text (app Core/WorkshopPaidProfessionalService.swift).
 * The owner names one of their own topics and confirms its mode; the server re-checks the license, the exact purchased
 * version, the installed private draft and the topic, then creates one guarded private template: unpublished, not
 * attached to a node, not executable, with the professional inputs (players, duration, category, ...) left for the owner.
 * <p>
 * Each submission is a review item on the shared {@link ReviewStateMachine}: PENDING / ESCALATED show to the app as
 * PREPARED, APPROVED as CREATED, and REJECTED / CANCELLED as themselves. With the default
 * {@code chengyin.workshop.professional.review=AUTO} the server is the reviewer (actor SYSTEM) inside the submit
 * transaction, so the app gets a terminal state; with {@code MANUAL} the submission waits for an admin under
 * /business/workshop-professional. Every transition appends one workshop_professional_event row. No money moves here.
 * <p>
 * App license ids are {@code w18-paid-{workshop_order.id}}.
 */
@Service
public class WorkshopProfessionalService {
    public static final String COMMAND_SCHEMA = "w18-paid-professional-text-materialization-command-v1";
    public static final String UNAVAILABLE = "CURRENT_SOURCE_OR_TARGET_UNAVAILABLE";
    static final String FINGERPRINT_PROFILE = "W18_CONTENT_OWNER_MODE_V1";
    static final List<String> COMMAND_KEYS = List.of("schema", "requestId", "installationRequestId", "licenseId", "moduleId",
            "purchasedVersionId", "contentHash", "termsHash", "componentHash", "ownedDraftId", "currentDraftRevision",
            "currentDraftPayloadHash", "targetTopicId", "targetOwnerModeFingerprint", "confirmedMode");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern LICENSE = Pattern.compile("w18-paid-([1-9][0-9]{0,18})");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PAGE = 50;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkshopService workshop;
    private final LongSupplier clock;
    private final boolean autoReview;

    @Autowired
    public WorkshopProfessionalService(JdbcTemplate jdbc, PlatformTransactionManager transactions, WorkshopService workshop,
                                       @Value("${chengyin.workshop.professional.review:AUTO}") String review) {
        this(jdbc, transactions, workshop, System::currentTimeMillis, !"MANUAL".equalsIgnoreCase(review));
    }

    public WorkshopProfessionalService(JdbcTemplate jdbc, PlatformTransactionManager transactions, WorkshopService workshop,
                                       LongSupplier clock, boolean autoReview) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.workshop = workshop;
        this.clock = clock;
        this.autoReview = autoReview;
    }

    public boolean autoReview() { return autoReview; }

    // ================= owner routes =================

    /** {@code professional-targets/list}: the owner's own topics, newest id first, 50 per page. */
    public Map<String, Object> targets(long owner, String raw) {
        JsonNode body = body(raw, Set.of("licenseId"), Set.of("beforeTopicId"));
        long line = license(body.path("licenseId").asText(null));
        Long before = optionalPositive(body, "beforeTopicId");
        return tx.execute(status -> {
            requireLicense(owner, line);
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name, product_type, config_version FROM cms_topic"
                    + " WHERE member_id = ? AND del_flag = 0 AND id < ? ORDER BY id DESC LIMIT ?", owner,
                    before == null ? Long.MAX_VALUE : before, PAGE + 1);
            boolean more = rows.size() > PAGE;
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> row : rows.subList(0, Math.min(PAGE, rows.size()))) items.add(target(owner, row));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", "w18-owned-professional-topic-targets-v1");
            result.put("scope", "INDIVIDUAL_CONTENT_OWNER_AND_PUBLISHER_METADATA_ONLY");
            result.put("licenseId", "w18-paid-" + line);
            result.put("checkedAt", Instant.ofEpochMilli(clock.getAsLong()).toString());
            result.put("items", items);
            result.put("hasMore", more);
            result.put("nextBeforeTopicId", more ? items.get(PAGE - 1).get("topicId") : null);
            return result;
        });
    }

    /** {@code professional-targets/detail}: one of the owner's topics; anyone else's is 404. */
    public Map<String, Object> target(long owner, String raw) {
        JsonNode body = body(raw, Set.of("licenseId", "topicId"), Set.of());
        long line = license(body.path("licenseId").asText(null));
        long topicId = positive(body, "topicId");
        return tx.execute(status -> {
            requireLicense(owner, line);
            Map<String, Object> row = ownedTopic(owner, topicId);
            if (row == null) throw AppGapException.notFound("TOPIC_NOT_FOUND");
            return target(owner, row);
        });
    }

    /** {@code professional-operations/submit}. Same request id + same command replays; a different command is 409. */
    public Map<String, Object> submit(long owner, String username, String raw) {
        Command command = command(raw);
        Map<String, Object> view = tx.execute(status -> {
            Map<String, Object> prior = lockOperation(command.requestId);
            if (prior != null) return replay(owner, prior, command);
            long id = insert(owner, command, ReviewStateMachine.PENDING);
            event(id, null, ReviewStateMachine.PENDING, "AUTHOR", owner, "SUBMITTED");
            if (autoReview) review(id, owner, username, "SYSTEM", null, null);
            return view(load(id));
        });
        return view;
    }

    /** {@code professional-operations/cancel}: an open submission is cancelled; one the server never saw is recorded as
     * CANCELLED so a late submit with the same request id cannot create anything; a terminal one is returned as is. */
    public Map<String, Object> cancel(long owner, String raw) {
        Command command = command(raw);
        return tx.execute(status -> {
            Map<String, Object> prior = lockOperation(command.requestId);
            if (prior == null) {
                long id = insert(owner, command, ReviewStateMachine.CANCELLED);
                event(id, null, ReviewStateMachine.CANCELLED, "AUTHOR", owner, "CANCELLED_BEFORE_SUBMIT");
                return view(load(id));
            }
            replay(owner, prior, command);
            String current = (String) prior.get("review_status");
            if (ReviewStateMachine.open(current)) {
                move(((Number) prior.get("id")).longValue(), current, ReviewStateMachine.CANCELLED, "AUTHOR", owner, "CANCELLED_BY_AUTHOR", null);
            }
            return view(load(((Number) prior.get("id")).longValue()));
        });
    }

    /** {@code professional-operations/status}: the owner's operation by request id, or NOT_FOUND. */
    public Map<String, Object> status(long owner, String raw) {
        JsonNode body = body(raw, Set.of("requestId"), Set.of());
        String requestId = body.path("requestId").asText("");
        if (!UUID.matcher(requestId).matches()) throw AppGapException.badRequest("requestId");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM workshop_professional_operation WHERE request_id = ?", requestId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("owner_member_id")).longValue() != owner) return notFound(requestId);
        return view(rows.get(0));
    }

    /** {@code professional-operations/history}: the owner's intents on one license, operation key descending. */
    public Map<String, Object> history(long owner, String raw) {
        JsonNode body = body(raw, Set.of("licenseId"), Set.of("beforeOperationKey"));
        long line = license(body.path("licenseId").asText(null));
        String before = body.hasNonNull("beforeOperationKey") ? body.get("beforeOperationKey").asText() : null;
        if (before != null && !HASH.matcher(before).matches()) throw AppGapException.badRequest("beforeOperationKey");
        String licenseId = "w18-paid-" + line;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT operation_key, command_hash, command_json, create_time"
                + " FROM workshop_professional_operation WHERE owner_member_id = ? AND license_id = ? AND operation_key < ?"
                + " ORDER BY operation_key DESC LIMIT ?", owner, licenseId, before == null ? "g" : before, PAGE + 1);
        boolean more = rows.size() > PAGE;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(PAGE, rows.size()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("operationKey", row.get("operation_key"));
            item.put("commandHash", row.get("command_hash"));
            item.put("createdAt", iso(row.get("create_time")));
            item.put("command", commandObject((String) row.get("command_json")));
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "w18-paid-professional-operation-history-v1");
        result.put("scope", "OWNER_OPERATION_INTENT_REFERENCES_ONLY");
        result.put("licenseId", licenseId);
        result.put("items", items);
        result.put("scannedCount", items.size());
        result.put("hasMore", more);
        result.put("nextBeforeOperationKey", more ? items.get(PAGE - 1).get("operationKey") : null);
        return result;
    }

    // ================= admin review =================

    public Map<String, Object> adminQueue() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM workshop_professional_operation WHERE review_status IN ('PENDING','ESCALATED')"
                + " ORDER BY (review_status = 'ESCALATED') DESC, create_time ASC, id ASC LIMIT 200");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) items.add(adminView(row));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("autoReview", autoReview);
        return result;
    }

    /** Approval re-runs every server check and creates the template; a failed check rejects instead. */
    public Map<String, Object> approve(long admin, long id, String note) {
        return tx.execute(status -> {
            Map<String, Object> row = lockById(id);
            if (!ReviewStateMachine.allowed((String) row.get("review_status"), ReviewStateMachine.APPROVED)) throw invalid(row, ReviewStateMachine.APPROVED);
            review(id, ((Number) row.get("owner_member_id")).longValue(), null, "ADMIN", admin, note);
            return adminView(load(id));
        });
    }

    public Map<String, Object> reject(long admin, long id, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw AppGapException.badRequest("reason");
        return tx.execute(status -> {
            Map<String, Object> row = lockById(id);
            String current = (String) row.get("review_status");
            if (!ReviewStateMachine.allowed(current, ReviewStateMachine.REJECTED)) throw invalid(row, ReviewStateMachine.REJECTED);
            move(id, current, ReviewStateMachine.REJECTED, "ADMIN", admin, reason.trim(), UNAVAILABLE);
            return adminView(load(id));
        });
    }

    public Map<String, Object> escalate(long admin, long id, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw AppGapException.badRequest("reason");
        return tx.execute(status -> {
            Map<String, Object> row = lockById(id);
            String current = (String) row.get("review_status");
            if (!ReviewStateMachine.allowed(current, ReviewStateMachine.ESCALATED)) throw invalid(row, ReviewStateMachine.ESCALATED);
            move(id, current, ReviewStateMachine.ESCALATED, "ADMIN", admin, reason.trim(), null);
            return adminView(load(id));
        });
    }

    // ================= core =================

    /** Server checks, then APPROVED + template, or REJECTED with the app's only source/target rejection code. */
    private void review(long id, long owner, String username, String actorType, Long actorId, String note) {
        Map<String, Object> row = load(id);
        Command command = command((String) row.get("command_json"));
        String current = (String) row.get("review_status");
        WorkshopService.ProfessionalSource source;
        Map<String, Object> topic;
        try {
            source = workshop.professionalSource(owner, license(command.licenseId), command.installationRequestId);
            topic = ownedTopic(owner, command.targetTopicId);
        } catch (ServiceException | AppGapException unavailable) {
            move(id, current, ReviewStateMachine.REJECTED, actorType, actorId, "CHECK_FAILED:" + unavailable.getMessage(), UNAVAILABLE);
            return;
        }
        String problem = mismatch(command, source, topic, owner);
        if (problem != null) {
            move(id, current, ReviewStateMachine.REJECTED, actorType, actorId, "CHECK_FAILED:" + problem, UNAVAILABLE);
            return;
        }
        CmsTemplatePublishRequest template = source.snapshot();
        List<String> missing = new ArrayList<>(List.of("players", "duration", "category"));
        template.setPlayers(null);
        template.setDuration(null);
        template.setCategoryId(null);
        if (blank(template.getDescription())) missing.add("description");
        if (blank(template.getQuestionName())) missing.add("questionName");
        if (blank(template.getQuestionAnswer())) missing.add("questionAnswer");
        String contentHash = sha(canonical(template));
        long templateId = workshop.createGuardedTemplate(template, owner, username == null ? "w18-professional" : username);
        Timestamp now = now();
        jdbc.update("UPDATE workshop_professional_operation SET template_id = ?, template_content_hash = ?, missing_inputs_json = ?,"
                + " committed_time = ? WHERE id = ?", templateId, contentHash, json(missing), now, id);
        move(id, current, ReviewStateMachine.APPROVED, actorType, actorId, note == null ? "GUARDED_PRIVATE_TEMPLATE_CREATED" : note, null);
    }

    private String mismatch(Command c, WorkshopService.ProfessionalSource s, Map<String, Object> topic, long owner) {
        if (!c.purchasedVersionId.equals(s.versionId())) return "purchasedVersionId";
        if (!c.moduleId.equals(String.valueOf(s.moduleId()))) return "moduleId";
        if (!c.contentHash.equals(s.contentHash())) return "contentHash";
        if (!c.termsHash.equals(s.termsHash())) return "termsHash";
        if (c.ownedDraftId != s.ownedDraftId()) return "ownedDraftId";
        if (c.currentDraftRevision != s.draftRevision() || !c.currentDraftPayloadHash.equals(s.draftPayloadHash())) return "currentDraft";
        if (topic == null) return "targetTopicId";
        Map<String, Object> target = target(owner, topic);
        if (!c.targetOwnerModeFingerprint.equals(target.get("ownerModeFingerprint"))) return "targetOwnerModeFingerprint";
        if (!c.confirmedMode.equals(target.get("mode"))) return "confirmedMode";
        return null;
    }

    private Map<String, Object> replay(long owner, Map<String, Object> prior, Command command) {
        if (((Number) prior.get("owner_member_id")).longValue() != owner || !command.hash.equals(prior.get("command_hash"))) {
            throw new AppGapException(409, "REQUEST_ID_CONFLICT", "该请求号已用于另一条指令");
        }
        return view(prior);
    }

    private long insert(long owner, Command c, String status) {
        Timestamp now = now();
        try {
            jdbc.update("INSERT INTO workshop_professional_operation(request_id, operation_key, owner_member_id, license_id, command_json,"
                    + " command_hash, review_status, target_topic_id, owned_draft_id, confirmed_mode, purchased_version_id, create_time, update_time)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", c.requestId, sha("w18-professional-operation|" + c.requestId), owner,
                    c.licenseId, c.json, c.hash, status, c.targetTopicId, c.ownedDraftId, c.confirmedMode, c.purchasedVersionId, now, now);
        } catch (DuplicateKeyException raced) {
            throw new AppGapException(409, "REQUEST_ID_CONFLICT", "该请求号已在处理");
        }
        return jdbc.queryForObject("SELECT id FROM workshop_professional_operation WHERE request_id = ?", Long.class, c.requestId);
    }

    private void move(long id, String from, String to, String actorType, Long actorId, String reason, String rejectionCode) {
        if (!ReviewStateMachine.allowed(from, to)) throw new IllegalStateException(from + " -> " + to);
        Timestamp now = now();
        int n = jdbc.update("UPDATE workshop_professional_operation SET review_status = ?, rejection_code = ?, update_time = ?,"
                + " row_version = row_version + 1 WHERE id = ? AND review_status = ?", to, rejectionCode, now, id, from);
        if (n != 1) throw new AppGapException(409, "INVALID_TRANSITION", "当前状态不允许该操作", Map.of("status", from, "target", to));
        event(id, from, to, actorType, actorId, reason);
    }

    private void event(long id, String from, String to, String actorType, Long actorId, String reason) {
        jdbc.update("INSERT INTO workshop_professional_event(operation_id, from_status, to_status, actor_type, actor_id, reason, create_time)"
                + " VALUES (?,?,?,?,?,?,?)", id, from, to, actorType, actorId, reason == null ? null : truncate(reason, 500), now());
    }

    // ================= views =================

    private Map<String, Object> view(Map<String, Object> row) {
        String status = (String) row.get("review_status");
        String state = switch (status) {
            case ReviewStateMachine.PENDING, ReviewStateMachine.ESCALATED -> "PREPARED";
            case ReviewStateMachine.APPROVED -> "CREATED";
            default -> status;
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "w18-paid-professional-operation-v1");
        result.put("scope", "OWNER_OPERATION_METADATA_ONLY");
        result.put("requestId", row.get("request_id"));
        result.put("state", state);
        result.put("safeToReplace", "REJECTED".equals(state) || "CANCELLED".equals(state));
        result.put("commandHash", row.get("command_hash"));
        result.put("createdAt", iso(row.get("create_time")));
        result.put("updatedAt", iso(row.get("update_time")));
        result.put("rejectionCode", "REJECTED".equals(state) ? row.get("rejection_code") : null);
        result.put("creation", "CREATED".equals(state) ? creation(row) : null);
        return result;
    }

    private static Map<String, Object> creation(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "w18-paid-professional-materialization-status-v1");
        result.put("scope", "OWNER_HISTORICAL_CREATION_REFERENCE_ONLY");
        result.put("requestId", row.get("request_id"));
        result.put("state", "CREATION_COMMITTED");
        result.put("templateId", row.get("template_id"));
        result.put("targetTopicId", row.get("target_topic_id"));
        result.put("ownedDraftId", row.get("owned_draft_id"));
        result.put("confirmedMode", row.get("confirmed_mode"));
        result.put("licenseId", row.get("license_id"));
        result.put("purchasedVersionId", row.get("purchased_version_id"));
        result.put("templateContentHash", row.get("template_content_hash"));
        result.put("createdAt", iso(row.get("committed_time")));
        result.put("currentTemplateState", "NOT_CHECKED");
        result.put("currentUseAuthority", "NOT_GRANTED_BY_HISTORY");
        return result;
    }

    private static Map<String, Object> notFound(String requestId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "w18-paid-professional-operation-v1");
        result.put("scope", "OWNER_OPERATION_METADATA_ONLY");
        result.put("requestId", requestId);
        result.put("state", "NOT_FOUND");
        result.put("safeToReplace", false);
        return result;
    }

    private Map<String, Object> adminView(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(view(row));
        result.put("id", row.get("id"));
        result.put("reviewStatus", row.get("review_status"));
        result.put("ownerMemberId", row.get("owner_member_id"));
        result.put("templateId", row.get("template_id"));
        result.put("missingProfessionalInputs", row.get("missing_inputs_json"));
        result.put("events", jdbc.queryForList("SELECT from_status AS fromStatus, to_status AS toStatus, actor_type AS actorType,"
                + " actor_id AS actorId, reason, create_time AS at FROM workshop_professional_event WHERE operation_id = ? ORDER BY id", row.get("id")));
        return result;
    }

    private Map<String, Object> target(long owner, Map<String, Object> row) {
        long topicId = ((Number) row.get("id")).longValue();
        Long productType = row.get("product_type") == null ? null : ((Number) row.get("product_type")).longValue();
        Long routeConfigVersion = row.get("config_version") == null ? null : ((Number) row.get("config_version")).longValue();
        String mode = productType == null ? "UNRESOLVED" : productType == 1 ? "CITY_ORIENTATION" : productType == 2 ? "FREE_EXPLORATION" : "UNRESOLVED";
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("topicId", topicId);
        item.put("name", row.get("name") == null ? "" : row.get("name"));
        item.put("productType", productType);
        item.put("mode", mode);
        item.put("routeConfigVersion", routeConfigVersion);
        item.put("ownerModeFingerprint", framedSha(FINGERPRINT_PROFILE, String.valueOf(topicId), String.valueOf(owner),
                String.valueOf(productType), String.valueOf(routeConfigVersion), mode));
        item.put("fingerprintProfile", FINGERPRINT_PROFILE);
        item.put("materializationStatus", "PROTECTED_TEMPLATE_ADAPTER_REQUIRED");
        return item;
    }

    private Map<String, Object> ownedTopic(long owner, long topicId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name, product_type, config_version FROM cms_topic"
                + " WHERE id = ? AND member_id = ? AND del_flag = 0", topicId, owner);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void requireLicense(long owner, long line) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT buyer_id, paid_at, state, license_status FROM workshop_order WHERE id = ?", line);
        if (rows.isEmpty() || ((Number) rows.get(0).get("buyer_id")).longValue() != owner || rows.get(0).get("paid_at") == null) {
            throw AppGapException.notFound("LICENSE_NOT_FOUND");
        }
        if (!"PAID".equals(rows.get(0).get("state")) || !"ACTIVE".equals(rows.get(0).get("license_status"))) {
            throw new AppGapException(403, "LICENSE_DISABLED", "许可已停用");
        }
    }

    private Map<String, Object> lockOperation(String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM workshop_professional_operation WHERE request_id = ? FOR UPDATE", requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> lockById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM workshop_professional_operation WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) throw AppGapException.notFound("OPERATION_NOT_FOUND");
        return rows.get(0);
    }

    private Map<String, Object> load(long id) {
        return jdbc.queryForList("SELECT * FROM workshop_professional_operation WHERE id = ?", id).get(0);
    }

    private static AppGapException invalid(Map<String, Object> row, String target) {
        return new AppGapException(409, "INVALID_TRANSITION", "当前状态不允许该操作", Map.of("status", row.get("review_status"), "target", target));
    }

    // ================= command =================

    /** The app's command, exact keys and formats (WorkshopPaidProfessionalCommand). */
    static final class Command {
        String json, hash, requestId, installationRequestId, licenseId, moduleId, purchasedVersionId, contentHash, termsHash,
                componentHash, currentDraftPayloadHash, targetOwnerModeFingerprint, confirmedMode;
        long ownedDraftId, currentDraftRevision, targetTopicId;
    }

    static Command command(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 4096) throw AppGapException.badRequest("command");
        JsonNode node = body(raw, Set.copyOf(COMMAND_KEYS), Set.of());
        if (!COMMAND_SCHEMA.equals(node.path("schema").asText())) throw AppGapException.badRequest("schema");
        Command c = new Command();
        c.requestId = text(node, "requestId");
        c.installationRequestId = text(node, "installationRequestId");
        c.licenseId = text(node, "licenseId");
        c.moduleId = text(node, "moduleId");
        c.purchasedVersionId = text(node, "purchasedVersionId");
        c.contentHash = text(node, "contentHash");
        c.termsHash = text(node, "termsHash");
        c.componentHash = text(node, "componentHash");
        c.currentDraftPayloadHash = text(node, "currentDraftPayloadHash");
        c.targetOwnerModeFingerprint = text(node, "targetOwnerModeFingerprint");
        c.confirmedMode = text(node, "confirmedMode");
        c.ownedDraftId = positive(node, "ownedDraftId");
        c.currentDraftRevision = positive(node, "currentDraftRevision");
        c.targetTopicId = positive(node, "targetTopicId");
        if (!UUID.matcher(c.requestId).matches()) throw AppGapException.badRequest("requestId");
        if (!UUID.matcher(c.installationRequestId).matches()) throw AppGapException.badRequest("installationRequestId");
        license(c.licenseId);
        for (String v : List.of(c.moduleId, c.purchasedVersionId)) {
            if (v.isEmpty() || v.getBytes(StandardCharsets.UTF_8).length > 128 || !v.equals(v.strip())) throw AppGapException.badRequest("moduleId");
        }
        for (String v : List.of(c.contentHash, c.termsHash, c.componentHash, c.currentDraftPayloadHash, c.targetOwnerModeFingerprint)) {
            if (!HASH.matcher(v).matches()) throw AppGapException.badRequest("hash");
        }
        if (!"CITY_ORIENTATION".equals(c.confirmedMode) && !"FREE_EXPLORATION".equals(c.confirmedMode)) throw AppGapException.badRequest("confirmedMode");
        c.json = canonicalCommand(c);
        c.hash = commandHash(c);
        return c;
    }

    /** The app's commandHash: UTF-8 fields, each with a 4-byte big-endian length prefix, in this fixed order. */
    static String commandHash(Command c) {
        return framedSha(COMMAND_SCHEMA, c.requestId, c.installationRequestId, String.valueOf(c.ownedDraftId), c.licenseId, c.moduleId,
                c.purchasedVersionId, c.contentHash, c.termsHash, c.componentHash, String.valueOf(c.currentDraftRevision),
                c.currentDraftPayloadHash, String.valueOf(c.targetTopicId), c.targetOwnerModeFingerprint, c.confirmedMode);
    }

    private static String canonicalCommand(Command c) {
        Map<String, Object> m = new java.util.TreeMap<>();
        m.put("schema", COMMAND_SCHEMA);
        m.put("requestId", c.requestId);
        m.put("installationRequestId", c.installationRequestId);
        m.put("licenseId", c.licenseId);
        m.put("moduleId", c.moduleId);
        m.put("purchasedVersionId", c.purchasedVersionId);
        m.put("contentHash", c.contentHash);
        m.put("termsHash", c.termsHash);
        m.put("componentHash", c.componentHash);
        m.put("ownedDraftId", c.ownedDraftId);
        m.put("currentDraftRevision", c.currentDraftRevision);
        m.put("currentDraftPayloadHash", c.currentDraftPayloadHash);
        m.put("targetTopicId", c.targetTopicId);
        m.put("targetOwnerModeFingerprint", c.targetOwnerModeFingerprint);
        m.put("confirmedMode", c.confirmedMode);
        return json(m);
    }

    private static Map<String, Object> commandObject(String json) {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> m = JSON.readValue(json, LinkedHashMap.class);
            return m;
        } catch (Exception broken) {
            throw new IllegalStateException("stored command", broken);
        }
    }

    // ================= helpers =================

    private static JsonNode body(String raw, Set<String> required, Set<String> optional) {
        JsonNode node;
        try {
            node = raw == null ? null : JSON.readTree(raw);
        } catch (Exception bad) {
            throw AppGapException.badRequest("body");
        }
        if (node == null || !node.isObject()) throw AppGapException.badRequest("body");
        Set<String> keys = new TreeSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) keys.add(it.next());
        for (String key : keys) if (!required.contains(key) && !optional.contains(key)) throw AppGapException.badRequest(key);
        for (String key : required) if (!node.hasNonNull(key)) throw AppGapException.badRequest(key);
        return node;
    }

    static long license(String licenseId) {
        var m = licenseId == null ? null : LICENSE.matcher(licenseId);
        if (m == null || !m.matches()) throw AppGapException.badRequest("licenseId");
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException tooLarge) {
            throw AppGapException.badRequest("licenseId");
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual()) throw AppGapException.badRequest(key);
        return value.asText();
    }

    private static long positive(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.canConvertToLong() || !value.isIntegralNumber() || value.asLong() <= 0) throw AppGapException.badRequest(key);
        return value.asLong();
    }

    private static Long optionalPositive(JsonNode node, String key) {
        return node.hasNonNull(key) ? positive(node, key) : null;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }

    private Timestamp now() { return new Timestamp(clock.getAsLong()); }

    private static String iso(Object value) {
        if (value instanceof Timestamp t) return t.toInstant().toString();
        if (value instanceof java.time.LocalDateTime l) return Timestamp.valueOf(l).toInstant().toString();
        if (value instanceof java.util.Date d) return d.toInstant().toString();
        return value == null ? null : value.toString();
    }

    private static String canonical(Object value) {
        return json(canonicalTree(JSON.valueToTree(value)));
    }

    private static JsonNode canonicalTree(JsonNode node) {
        if (node.isObject()) {
            var sorted = JSON.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            java.util.Collections.sort(keys);
            for (String key : keys) sorted.set(key, canonicalTree(node.get(key)));
            return sorted;
        }
        return node;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception broken) {
            throw new IllegalStateException(broken);
        }
    }

    static String framedSha(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                int size = bytes.length;
                digest.update(new byte[]{(byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size});
                digest.update(bytes);
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String sha(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return out.toString();
    }
}

package com.chengyinhub.business.marketplace.source;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.reservation.TextModuleDraftCodec;
import com.chengyinhub.business.service.support.TemplateAuditSnapshot;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Unregistered server-only source reader. Loads actual personal-template and review rows, never
 * accepts caller-provided approval/owner/content DTOs. Not a public/owner API, publication issuer,
 * copyright reviewer or license grant. The future authorized ingestion service must re-capture
 * current evidence and separately verify rights, portability/privacy and marketplace approval.
 */
public final class ReviewedTemplatePackageSource {
    private static final int MAX_SNAPSHOT_CHARS = 262144, MAX_TEXT_BYTES = 262144;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] TEXT = {"title", "description", "ruleInstructions", "merchantGuide",
            "questionName", "questionAnswer", "hint1", "hint2", "answerReveal"};
    private static final String[] UNSUPPORTED_TEXT = {"usageLocation", "requiredMaterials", "players", "difficulty", "packType", "imgUrl", "sensorType", "sensorConfig", "feedbackText",
            "medalImg", "medalName", "rewardRulesJson", "storyText", "storyImg", "storyJson", "photoRequireDesc",
            "audioUrl", "questionA", "questionB", "questionC", "questionD", "correctAnswer", "questionImg",
            "questionAudio", "questionOptionMediaJson", "preferenceJson", "advancedConfigJson", "interactionType"};
    private final SqlSessionTemplate session;
    private final DataSourceTransactionManager manager;
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final CmsMemberTemplateMapper templates;
    private final AuditTaskMapper audits;

    public ReviewedTemplatePackageSource(SqlSessionTemplate session, DataSourceTransactionManager manager) {
        this.session = Objects.requireNonNull(session, "session");
        this.manager = Objects.requireNonNull(manager, "manager");
        dataSource = Objects.requireNonNull(manager.getDataSource(), "dataSource");
        requireEnvironment();
        templates = session.getMapper(CmsMemberTemplateMapper.class);
        audits = session.getMapper(AuditTaskMapper.class);
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Expected hash/version are stale-read guards, not evidence of approval or authorization. */
    public Evidence capture(long templateId, long auditId, int expectedAuditVersion, String expectedAuditSnapshotHash) {
        require(templateId > 0 && auditId > 0 && expectedAuditVersion > 0, "INVALID_SOURCE_REFERENCE");
        require(expectedAuditSnapshotHash != null && expectedAuditSnapshotHash.matches("[0-9a-f]{64}"), "INVALID_SOURCE_HASH");
        requireEnvironment();
        return transactions.execute(status -> {
            requireEnvironment();
            require(TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.hasResource(dataSource)
                    && Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED).equals(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()), "SOURCE_TRANSACTION_REQUIRED");
            // Same template -> audit lock order as AuditTaskServiceImpl and professional editing.
            List<CmsMemberTemplate> rows = templates.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(templateId));
            require(rows != null && rows.size() == 1 && Long.valueOf(templateId).equals(rows.get(0).getId()), "SOURCE_UNAVAILABLE");
            CmsMemberTemplate template = rows.get(0);
            AuditTask audit = audits.selectAuditTaskByIdForUpdate(auditId);
            require(audit != null && Long.valueOf(auditId).equals(audit.getId())
                    && AuditTask.BIZ_TEMPLATE.equals(audit.getBizType()) && "auto_gate".equals(audit.getSourceType())
                    && Long.valueOf(templateId).equals(audit.getBizId())
                    && template.getMemberId() != null && template.getMemberId() > 0
                    && template.getMemberId().equals(audit.getSubmitterId()), "SOURCE_REVIEW_MISMATCH");
            require(AuditTask.STATUS_APPROVED.equals(audit.getStatus())
                    && Integer.valueOf(expectedAuditVersion).equals(audit.getVersion())
                    && Long.valueOf(1).equals(template.getStatus()) && Integer.valueOf(1).equals(template.getCheckStatus())
                    && Long.valueOf(1).equals(template.getPublishStatus()) && Integer.valueOf(1).equals(template.getDraftStatus())
                    && Integer.valueOf(0).equals(template.getDelFlag()), "SOURCE_NOT_CURRENTLY_APPROVED");
            String submitted = audit.getSnapshotJson();
            JsonNode reviewed = flatSnapshot(submitted);
            require(expectedAuditSnapshotHash.equals(sha(submitted.getBytes(StandardCharsets.UTF_8))), "SOURCE_HASH_CHANGED");
            String current = TemplateAuditSnapshot.personal(template);
            require(reviewed.equals(flatSnapshot(current)), "SOURCE_CONTENT_CHANGED");
            byte[] text = compileText(reviewed);
            String auditReference = "template-audit:" + auditId + ":v" + audit.getVersion();
            ModuleVersionSnapshot version = new ModuleVersionSnapshot(
                    new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL, template.getMemberId()),
                    "member-template:" + templateId, auditReference, "audit-" + audit.getVersion(), 1,
                    ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE, templateId, expectedAuditSnapshotHash,
                    text, sha(text), auditReference + ":" + expectedAuditSnapshotHash,
                    Collections.<String, ModuleVersionSnapshot.BindingKind>emptyMap());
            TextModuleDraftCodec.validateReviewedSource(text, version);
            return new Evidence(version, text, expectedAuditSnapshotHash);
        });
    }

    private void requireEnvironment() {
        Environment environment = session.getSqlSessionFactory().getConfiguration().getEnvironment();
        require(environment != null && manager.getDataSource() == dataSource && environment.getDataSource() == dataSource
                && environment.getTransactionFactory() instanceof SpringManagedTransactionFactory, "SOURCE_DATASOURCE_MISMATCH");
    }

    /** Frozen bytes remain historical evidence; returning this object is not a renewable approval. */
    public static final class Evidence {
        private final ModuleVersionSnapshot version;
        private final byte[] text;
        private final String auditSnapshotHash;
        private Evidence(ModuleVersionSnapshot version, byte[] text, String auditSnapshotHash) {
            this.version = version; this.text = text.clone(); this.auditSnapshotHash = auditSnapshotHash;
        }
        // Deliberately no bean getters: this contains private answers and is never a public projection.
        public ModuleVersionSnapshot version() { return version; }
        public byte[] reviewedText() { return text.clone(); }
        public String auditSnapshotHash() { return auditSnapshotHash; }
        /**
         * Structural candidate only. Rights digest/policy come from a separate trusted issuer; this
         * source reader does not prove them. No term expiry, subscription or update rights are invented.
         */
        public ModulePackageManifest manifestCandidate(LicenseTermsSnapshot terms, String authorRightsEvidenceHash,
                                                        int minimumRuntime, int maximumRuntime) {
            return new ModulePackageManifest(version, ModulePackageManifest.Type.GAMEPLAY, minimumRuntime, maximumRuntime,
                    Collections.singleton(ModulePackageManifest.Capability.TEXT), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyMap(), Objects.requireNonNull(terms, "terms"), authorRightsEvidenceHash);
        }
    }

    private static byte[] compileText(JsonNode reviewed) {
        require(reviewed.path("validationMethod").isIntegralNumber() && reviewed.path("validationMethod").asInt() == 1,
                "UNSUPPORTED_SOURCE_KIND");
        for (String field : UNSUPPORTED_TEXT) require(empty(reviewed.path(field)), "UNSUPPORTED_SOURCE_FIELD:" + field);
        for (String field : new String[]{"duration", "couponId", "xpValue", "photoReview", "audioDuration", "originalTemplateId"}) {
            JsonNode value = reviewed.path(field);
            require(value.isNull() || (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() == 0),
                    "UNSUPPORTED_SOURCE_FIELD:" + field);
        }
        ObjectNode content = JSON.createObjectNode();
        content.put("schema", "w18-member-text-v1");
        for (String field : TEXT) {
            JsonNode value = reviewed.path(field);
            require(value.isNull() || value.isTextual(), "UNSUPPORTED_SOURCE_FIELD:" + field);
            if (value.isTextual()) content.put(field, value.textValue());
        }
        require(content.path("title").isTextual() && !content.path("title").asText().trim().isEmpty()
                && content.path("questionAnswer").isTextual() && !content.path("questionAnswer").asText().trim().isEmpty(),
                "INCOMPLETE_TEXT_SOURCE");
        // Venue/material/player/duration requirements cannot be faithfully mapped to this text codec.
        // They are rejected above, never silently erased or inferred into binding permissions.
        content.putObject("bindings");
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= MAX_TEXT_BYTES, "SOURCE_CONTENT_TOO_LARGE");
        return bytes;
    }
    private static boolean empty(JsonNode value) { return value.isNull() || (value.isTextual() && value.textValue().isEmpty()); }
    private static JsonNode flatSnapshot(String snapshot) {
        require(snapshot != null && snapshot.length() <= MAX_SNAPSHOT_CHARS, "INVALID_REVIEW_SNAPSHOT");
        try (JsonParser parser = JSON.getFactory().createParser(snapshot)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            require(parser.nextToken() == JsonToken.START_OBJECT, "INVALID_REVIEW_SNAPSHOT");
            int fields = 0;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.FIELD_NAME && ++fields <= 128, "INVALID_REVIEW_SNAPSHOT");
                validUtf16(parser.currentName());
                JsonToken token = parser.nextToken();
                require(token != null && token.isScalarValue(), "INVALID_REVIEW_SNAPSHOT");
                if (token == JsonToken.VALUE_STRING) validUtf16(parser.getText());
            }
            require(parser.nextToken() == null, "INVALID_REVIEW_SNAPSHOT");
            return JSON.readTree(snapshot);
        } catch (IOException invalid) { throw new IllegalStateException("INVALID_REVIEW_SNAPSHOT", invalid); }
    }
    private static void validUtf16(String value) {
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if (Character.isHighSurrogate(c)) require(++i<value.length() && Character.isLowSurrogate(value.charAt(i)), "INVALID_REVIEW_SNAPSHOT");
            else require(!Character.isLowSurrogate(c), "INVALID_REVIEW_SNAPSHOT");
        }
    }
    private static String sha(byte[] bytes) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(Locale.ROOT,"%02x",b&255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}

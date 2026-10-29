package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.domain.PhotoCheckIntentRow;
import com.chengyinhub.business.mapper.PhotoCheckIntentMapper;
import com.chengyinhub.business.service.ConsentGuard;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

/** 原命令先提交、单次外部执行、原结果独立提交；没有租约重发或后台猜测补发。 */
@Service
public class PhotoCheckIntentService {
    private final PhotoCheckIntentMapper mapper;
    private final AiGatewayService gateway;
    private final PhotoCheckVisionService vision;
    private final ConsentGuard consent;
    private final AppMediaReferenceGuard media;
    private final TransactionTemplate independent;
    private final ObjectMapper json = new ObjectMapper();

    public PhotoCheckIntentService(PhotoCheckIntentMapper mapper, AiGatewayService gateway,
            PhotoCheckVisionService vision, ConsentGuard consent, AppMediaReferenceGuard media,
            PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.vision = vision;
        this.consent = consent;
        this.media = media;
        independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public PhotoCheckIntentView prepare(PhotoCheckIntentCommand command) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        requireTransaction();
        validate(command);
        PhotoCheckIntentRow existing = mapper.selectByKey(command.memberId(), command.sessionId(), command.actionKey());
        if (existing != null) return sameOriginal(existing, command.requestFingerprint());

        // All security refusals precede any intent insert, Redis use or provider invocation.
        consent.requireCurrentAgreement(command.memberId(), "photo_model_processing", "object_photo");
        String canonical = media.requireAttachment(command.memberId(), command.imageReference(), true);
        if (canonical == null || !canonical.matches("media-asset:[1-9][0-9]{0,18}")) throw conflict();
        media.processingUrl(canonical, command.memberId(), "IMAGE"); // discard transient processing URL
        PhotoCheckIntentCommand original = new PhotoCheckIntentCommand(command.memberId(), command.sessionId(),
                command.activityId(), command.topicId(), command.nodeId(), command.ownerId(),
                command.originalGameSessionId(), command.originalRouteSessionId(), command.ownerType(),
                command.expectedVersion(), command.authorityEpoch(), command.playInstanceId(), command.actionKey(),
                command.requestFingerprint(), canonical, command.requirement(), command.configSnapshotHash());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String reservation = gateway.planQuotaReservation(command.memberId(), "photo_check", nonce);
        String prefix = "ai:gen:limit:photo:" + command.memberId() + ":";
        if (reservation == null || !reservation.matches(java.util.regex.Pattern.quote(prefix) + "[0-9]{8}\\|" + nonce)) {
            throw new IllegalStateException("Invalid planned photo quota identity");
        }
        PhotoCheckIntentRow row = new PhotoCheckIntentRow();
        row.setMemberId(command.memberId()); row.setSessionId(command.sessionId()); row.setActionKey(command.actionKey());
        row.setRequestFingerprint(command.requestFingerprint()); row.setContextJson(encode(original));
        row.setImageReference(canonical); row.setNonce(nonce); row.setReservation(reservation);
        row.setOriginalDay(reservation.substring(prefix.length(), prefix.length() + 8));
        mapper.insertPrepared(row);
        // A competing prepare may have won the unique identity. Never replace its context or nonce.
        PhotoCheckIntentRow saved = mapper.selectByKeyForUpdate(command.memberId(), command.sessionId(), command.actionKey());
        if (saved == null) throw new IllegalStateException("Photo intent insert unavailable");
        return sameOriginal(saved, command.requestFingerprint());
    }

    @Transactional(propagation = Propagation.NEVER)
    public PhotoCheckIntentView executeOnce(Long memberId, Long intentId, String requestFingerprint) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Photo execution requires no active transaction");
        }
        // NEVER can still establish an empty synchronization. Keep this method's
        // locking read/session inside the existing short independent transaction,
        // so its connection is released before provider work and later commits.
        PhotoCheckIntentRow original = independent.execute(status -> owned(memberId, intentId, requestFingerprint));
        if (!"PREPARED".equals(original.getState())) return view(original);
        PhotoCheckIntentCommand command = decode(original);
        boolean claimed = Boolean.TRUE.equals(independent.execute(status ->
                mapper.claim(intentId, memberId, requestFingerprint) == 1));
        if (!claimed) return independent.execute(status -> read(memberId, intentId, requestFingerprint));

        PhotoCheckVisionService.PreparedReview review;
        try {
            review = vision.reviewPrepared(memberId, command.imageReference(), command.requirement(),
                    original.getReservation(), snapshot -> saveSnapshot(original, snapshot));
        } catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch (RuntimeException failure) {
            // A thrown security refusal is never converted to a degraded result. A failed call is
            // conservatively uncertain; neither restart nor elapsed time grants another execution.
            try { independent.execute(status -> mapper.markUnknown(intentId, requestFingerprint)); }
            catch (RuntimeException persistenceFailure) { failure.addSuppressed(persistenceFailure); }
            throw failure;
        }
        if (review == null || review.result() == null
                || !("NOT_SENT".equals(review.providerState()) || "UNKNOWN".equals(review.providerState())
                || "RECEIVED".equals(review.providerState()))
                || review.reasonCode() == null || !review.reasonCode().matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalStateException("Invalid photo review outcome");
        }
        PhotoCheckVisionResult result = review.result();
        original.setProviderState(review.providerState()); original.setReasonCode(review.reasonCode());
        original.setResultPass(result.isPass()); original.setResultConfidence(result.getConfidence());
        original.setResultReason(result.getReason()); original.setResultDegraded(result.isDegraded());
        independent.execute(status -> {
            if (mapper.saveResult(original) != 1) throw new IllegalStateException("Photo result persistence failed");
            return null;
        });
        return independent.execute(status -> read(memberId, intentId, requestFingerprint));
    }

    /** No nonce allocation, permission mutation, quota or provider call on reads. */
    public PhotoCheckIntentView read(Long memberId, Long intentId, String requestFingerprint) {
        return view(owned(memberId, intentId, requestFingerprint));
    }

    /**
     * Recover a lost prepare response using the original command identity. Exact absence is
     * null; a bound key with a different hash is a conflict. In C's short transaction the
     * caller locks the original session first; this method only locks the intent, never a session.
     */
    public PhotoCheckIntentView read(Long memberId, Long sessionId, String actionKey, String requestFingerprint) {
        if (memberId == null || memberId <= 0 || sessionId == null || sessionId <= 0
                || actionKey == null || actionKey.isBlank() || actionKey.length() > 128
                || !hash(requestFingerprint)) throw conflict();
        PhotoCheckIntentRow row = mapper.selectByKeyForUpdate(memberId, sessionId, actionKey);
        if (row == null) return null;
        if (!Objects.equals(memberId, row.getMemberId()) || !Objects.equals(sessionId, row.getSessionId())
                || !Objects.equals(actionKey, row.getActionKey())) throw conflict();
        return sameOriginal(row, requestFingerprint);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void markApplied(Long intentId, String requestFingerprint, Long attemptLogId) {
        requireTransaction();
        if (intentId == null || attemptLogId == null || attemptLogId <= 0 || !hash(requestFingerprint)) throw conflict();
        PhotoCheckIntentRow row = mapper.selectForUpdate(intentId, requestFingerprint);
        if (row == null || mapper.countMatchingReceipt(intentId, requestFingerprint, attemptLogId) != 1) throw conflict();
        if ("APPLIED".equals(row.getState()) && Objects.equals(attemptLogId, row.getAttemptLogId())) return;
        if (!"RESULT_READY".equals(row.getState()) || mapper.markApplied(intentId, requestFingerprint, attemptLogId) != 1) {
            throw conflict();
        }
    }

    private void saveSnapshot(PhotoCheckIntentRow original, PhotoCheckVisionService.InputSnapshot snapshot) {
        if (snapshot == null || !hash(snapshot.encodedInputSha256()) || !version(snapshot.modelName())
                || !version(snapshot.promptVersion()) || !version(snapshot.encoderVersion())) {
            throw new IllegalStateException("Invalid photo input snapshot");
        }
        PhotoCheckIntentRow row = new PhotoCheckIntentRow(); row.setId(original.getId());
        row.setRequestFingerprint(original.getRequestFingerprint()); row.setEncodedInputSha256(snapshot.encodedInputSha256());
        row.setModelName(snapshot.modelName()); row.setPromptVersion(snapshot.promptVersion()); row.setEncoderVersion(snapshot.encoderVersion());
        independent.execute(status -> {
            if (mapper.saveSnapshot(row) != 1) throw new IllegalStateException("Photo input snapshot persistence failed");
            return null;
        });
    }

    private PhotoCheckIntentRow owned(Long memberId, Long intentId, String fingerprint) {
        if (memberId == null || memberId <= 0 || intentId == null || intentId <= 0 || !hash(fingerprint)) throw conflict();
        PhotoCheckIntentRow row = mapper.selectOwned(intentId, memberId, fingerprint);
        if (row == null || !Objects.equals(row.getMemberId(), memberId)
                || !Objects.equals(row.getId(), intentId) || !fingerprint.equals(row.getRequestFingerprint())) throw conflict();
        decode(row); // fail closed on damaged or mismatched original binding
        return row;
    }

    private PhotoCheckIntentView sameOriginal(PhotoCheckIntentRow row, String fingerprint) {
        if (!fingerprint.equals(row.getRequestFingerprint())) throw conflict();
        decode(row);
        return view(row);
    }

    private PhotoCheckIntentCommand decode(PhotoCheckIntentRow row) {
        try {
            PhotoCheckIntentCommand command = json.readValue(row.getContextJson(), PhotoCheckIntentCommand.class);
            if (!Objects.equals(command.memberId(), row.getMemberId()) || !Objects.equals(command.sessionId(), row.getSessionId())
                    || !Objects.equals(command.actionKey(), row.getActionKey())
                    || !Objects.equals(command.requestFingerprint(), row.getRequestFingerprint())
                    || !Objects.equals(command.imageReference(), row.getImageReference())) throw conflict();
            return command;
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException("Photo original context unavailable", corrupt);
        }
    }

    private String encode(PhotoCheckIntentCommand command) {
        try { return json.writeValueAsString(command); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Photo context serialization failed", failure); }
    }

    private PhotoCheckIntentView view(PhotoCheckIntentRow row) {
        PhotoCheckIntentState state = PhotoCheckIntentState.valueOf(row.getState());
        PhotoCheckVisionResult result = null;
        if (state == PhotoCheckIntentState.RESULT_READY || state == PhotoCheckIntentState.APPLIED) {
            if (row.getResultDegraded() == null || row.getResultPass() == null || row.getResultConfidence() == null
                    || row.getResultConfidence() < 0 || row.getResultConfidence() > 100
                    || (row.getResultDegraded() && (row.getResultPass() || row.getResultConfidence() != 0))) {
                throw new IllegalStateException("Photo stored result unavailable");
            }
            result = row.getResultDegraded() ? PhotoCheckVisionResult.degraded(row.getResultReason())
                    : PhotoCheckVisionResult.concluded(row.getResultPass(), row.getResultConfidence(), row.getResultReason());
        }
        return new PhotoCheckIntentView(row.getId(), row.getMemberId(), row.getSessionId(), row.getAttemptLogId(),
                row.getActionKey(), row.getRequestFingerprint(), state, result, row.getReasonCode(), row.getProviderState());
    }

    private static void validate(PhotoCheckIntentCommand command) {
        if (command == null || command.memberId() == null || command.memberId() <= 0 || command.sessionId() == null
                || command.sessionId() <= 0 || command.expectedVersion() == null || command.expectedVersion() < 0
                || command.actionKey() == null || command.actionKey().isBlank() || command.actionKey().length() > 128
                || !hash(command.requestFingerprint()) || !hash(command.configSnapshotHash())
                || command.imageReference() == null || command.imageReference().isBlank()) throw conflict();
    }

    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean version(String value) { return value != null && !value.isBlank() && value.length() <= 255; }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Photo intent requires active transaction");
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw new IllegalStateException("Photo intent requires writable transaction");
    }
    private static ServiceException conflict() { return new ServiceException("照片审核原请求不匹配或状态不可用", 409); }
}

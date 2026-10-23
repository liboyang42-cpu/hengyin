package com.chengyinhub.business.service.notify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * C09 独占：消息原请求的不可变回执生产者。概念上复用既有 IM writer/journal
 * （clientMessageId + requestHash 幂等），但不改公共 send/receipt 协议、不写 Mapper。
 *
 * <p>回复 / mention / 编辑 / 撤回 / 置顶 / 搜索 → 原回执。断网重开与 UNKNOWN 恢复只读原回执，
 * 不造新 request。{@code APPLIED+} 当前投影不是原请求证明。
 */
public final class ImmutableReceiptProducer {

    /** 当前投影把原请求标成已落地时的标记；回执生产者必须忽略它。 */
    public static final String PROJECTION_APPLIED = "APPLIED+CURRENT";
    public static final String OUTCOME_ACCEPTED = "ACCEPTED";
    public static final String OUTCOME_UNKNOWN = "UNKNOWN";
    public static final String UNKNOWN_NOT_RECOVERED = "UNKNOWN_NOT_RECOVERED";

    public enum Kind {
        REPLY,
        MENTION,
        EDIT,
        WITHDRAW,
        PIN,
        SEARCH
    }

    private final OriginalReceiptStore store;

    public ImmutableReceiptProducer(OriginalReceiptStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 接受原请求并落不可变回执。同 requestId + 同指纹返回原回执；指纹冲突拒绝覆盖。
     */
    public OriginalReceipt accept(OriginalRequest request) {
        OriginalRequest required = requireRequest(request);
        String fingerprint = fingerprint(required);
        OriginalReceipt existing = store.find(required.requestId);
        if (existing != null) {
            if (!fingerprint.equals(existing.payloadHash)) {
                throw new IllegalStateException("原请求指纹冲突 requestId=" + required.requestId);
            }
            return existing;
        }
        OriginalReceipt receipt = new OriginalReceipt(
                required.requestId, required.kind, fingerprint, required.outcome, required.body);
        store.insert(receipt);
        return receipt;
    }

    /** 断网重开：只按原 requestId 读回执，不按当前投影补成功。 */
    public OriginalReceipt offlineReopen(String requestId, AppliedProjection projection) {
        return recover(requestId, projection);
    }

    /**
     * UNKNOWN 恢复：原结果仍是 UNKNOWN 时保持 UNKNOWN，不造新 request，
     * 也不把 APPLIED 投影当成原成功。
     */
    public OriginalReceipt unknownRecover(String requestId, AppliedProjection projection) {
        String id = parseRequestId(requestId);
        if (id == null) {
            return unknownReceipt(null, projection);
        }
        OriginalReceipt original = store.find(id);
        if (original == null) {
            return unknownReceipt(id, projection);
        }
        boolean projectionClaimsApplied = projection != null
                && id.equals(projection.requestId)
                && PROJECTION_APPLIED.equals(projection.appliedState);
        OriginalReceipt recovered = original.withProjectionClaim(projectionClaimsApplied);
        if (OUTCOME_UNKNOWN.equals(recovered.outcome) || isBlank(recovered.outcome)) {
            return recovered.withNoted(UNKNOWN_NOT_RECOVERED);
        }
        return recovered;
    }

    /**
     * 对齐 iOS {@code recoverUnknown}/{@code recoverOriginal}:只读原 requestId。
     * 未见过的 id 不 insert。缺 requestId 保持 Java null。
     */
    public OriginalReceipt recoverUnknown(String requestId, AppliedProjection projection) {
        return unknownRecover(requestId, projection);
    }

    public OriginalReceipt recoverOriginal(String requestId, AppliedProjection projection) {
        return unknownRecover(requestId, projection);
    }

    public OriginalReceipt recover(String requestId, AppliedProjection projection) {
        String id = requireRequestId(requestId);
        OriginalReceipt original = store.find(id);
        if (original == null) {
            throw new IllegalStateException("原回执不存在 requestId=" + id);
        }
        boolean projectionClaimsApplied = projection != null
                && id.equals(projection.requestId)
                && PROJECTION_APPLIED.equals(projection.appliedState);
        return original.withProjectionClaim(projectionClaimsApplied);
    }

    /** APPLIED 当前投影永远不是原请求证明。 */
    public boolean treatsAppliedProjectionAsOriginalProof(AppliedProjection projection) {
        return false;
    }

    static OriginalRequest requireRequest(OriginalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("原请求不能为空");
        }
        requireRequestId(request.requestId);
        if (request.kind == null) {
            throw new IllegalArgumentException("原请求 kind 不能为空");
        }
        String outcome = isBlank(request.outcome) ? OUTCOME_UNKNOWN : request.outcome.trim();
        return new OriginalRequest(request.requestId.trim(), request.kind, request.body, outcome);
    }

    static String parseRequestId(String requestId) {
        if (isBlank(requestId)) {
            return null;
        }
        String id = requestId.trim();
        if (id.length() > 64) {
            return null;
        }
        return id;
    }

    static String requireRequestId(String requestId) {
        if (isBlank(requestId)) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        String id = requestId.trim();
        if (id.length() > 64) {
            throw new IllegalArgumentException("requestId 长度不能超过 64");
        }
        return id;
    }

    static OriginalReceipt unknownReceipt(String requestId, AppliedProjection projection) {
        boolean claimed = projection != null && PROJECTION_APPLIED.equals(projection.appliedState);
        return new OriginalReceipt(requestId, null, null, OUTCOME_UNKNOWN, null, claimed, UNKNOWN_NOT_RECOVERED);
    }

    static String fingerprint(OriginalRequest request) {
        String canonical = request.requestId + "\n" + request.kind.name() + "\n"
                + nullToEmpty(request.body) + "\n" + request.outcome;
        return sha256Hex(canonical);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return hex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 原请求。body 是原报文指纹材料，不是公共 IM 协议字段。 */
    public static final class OriginalRequest {
        public final String requestId;
        public final Kind kind;
        public final String body;
        public final String outcome;

        public OriginalRequest(String requestId, Kind kind, String body, String outcome) {
            this.requestId = requestId;
            this.kind = kind;
            this.body = body;
            this.outcome = outcome;
        }
    }

    /** 当前读模型，不是原请求证明。 */
    public static final class AppliedProjection {
        public final String requestId;
        public final String appliedState;

        public AppliedProjection(String requestId, String appliedState) {
            this.requestId = requestId;
            this.appliedState = appliedState;
        }
    }

    public static final class OriginalReceipt {
        public final String requestId;
        public final Kind kind;
        public final String payloadHash;
        public final String outcome;
        public final String originalBody;
        public final boolean currentProjectionClaimsApplied;
        public final String recoveryNote;

        OriginalReceipt(String requestId, Kind kind, String payloadHash, String outcome, String originalBody) {
            this(requestId, kind, payloadHash, outcome, originalBody, false, null);
        }

        private OriginalReceipt(String requestId, Kind kind, String payloadHash, String outcome,
                                String originalBody, boolean currentProjectionClaimsApplied, String recoveryNote) {
            this.requestId = requestId;
            this.kind = kind;
            this.payloadHash = payloadHash;
            this.outcome = outcome;
            this.originalBody = originalBody;
            this.currentProjectionClaimsApplied = currentProjectionClaimsApplied;
            this.recoveryNote = recoveryNote;
        }

        OriginalReceipt withProjectionClaim(boolean claimed) {
            return new OriginalReceipt(requestId, kind, payloadHash, outcome, originalBody, claimed, recoveryNote);
        }

        OriginalReceipt withNoted(String note) {
            return new OriginalReceipt(requestId, kind, payloadHash, outcome, originalBody,
                    currentProjectionClaimsApplied, note);
        }

        public boolean originalIsNotProjectionSuccess() {
            return !PROJECTION_APPLIED.equals(outcome);
        }

        public Map<String, Object> asRequeryableView() {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("requestId", requestId);
            view.put("kind", kind == null ? null : kind.name());
            view.put("payloadHash", payloadHash);
            view.put("outcome", outcome);
            view.put("usedCurrentProjectionAsSuccess", Boolean.FALSE);
            view.put("recoveryNote", recoveryNote);
            return Collections.unmodifiableMap(view);
        }
    }

    /** 只存原回执；实现不得用当前投影表冒充原行。 */
    public interface OriginalReceiptStore {
        OriginalReceipt find(String requestId);

        void insert(OriginalReceipt receipt);
    }
}

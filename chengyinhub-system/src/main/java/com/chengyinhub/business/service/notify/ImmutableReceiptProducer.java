package com.chengyinhub.business.service.notify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.service.IImService;

/**
 * C09 独占：消息原请求的不可变回执生产者。概念上复用既有 IM writer/journal
 * （clientMessageId + requestHash 幂等），但不改公共 send/receipt 协议、不写 Mapper。
 *
 * <p>回复 / mention / 编辑 / 撤回 / 置顶 / 搜索 → 原回执。断网重开与 UNKNOWN 恢复只读原回执，
 * 不造新 request。{@code APPLIED+} 当前投影不是原请求证明。
 *
 * <p>原发送仍走 {@link IImService#send}。路由字段（member、conversation、target、msgType）
 * 必须由调用方传入；缺任一字段不发送。本类不实现第二套 send，也不写 Mapper。
 * 缺 journal / send 端口为 WAITING_SOURCE。
 */
public final class ImmutableReceiptProducer {

    /** 当前投影把原请求标成已落地时的标记；回执生产者必须忽略它。 */
    public static final String PROJECTION_APPLIED = "APPLIED+CURRENT";
    public static final String OUTCOME_ACCEPTED = "ACCEPTED";
    public static final String OUTCOME_UNKNOWN = "UNKNOWN";
    public static final String UNKNOWN_NOT_RECOVERED = "UNKNOWN_NOT_RECOVERED";
    public static final String IM_READ_SENT = "im.readSentMessage";
    public static final String IM_SEND = "im.send";
    /** 真实发消息命令：content、msgType、extraJson、身份。指纹 body 不是正文。 */
    public static final String IM_SEND_COMMAND = "im.send.originalCommand";
    /** 原 send 的 reply_to_id 或 mention_* 字段。任意 mention 夹具不是合同。 */
    public static final String IM_SEND_WIRE = "im.send.originalWire";
    /** 频道回复/提及的原 writer 是 ChannelMessageService.send，不是 IImService.send。公开树还没有这个类。 */
    public static final String CHANNEL_MESSAGE_SEND = "channelMessage.send";
    public static final String NOT_A_SEND = "IM_OPERATION_NOT_SEND";

    public enum Kind {
        REPLY,
        MENTION,
        EDIT,
        WITHDRAW,
        PIN,
        SEARCH
    }

    private final OriginalReceiptStore store;
    private final OriginalReceiptJournal journal;
    private final OriginalReceiptPersist persist;

    public ImmutableReceiptProducer(OriginalReceiptStore store) {
        this(store, null, null);
    }

    public ImmutableReceiptProducer(OriginalReceiptStore store, OriginalReceiptJournal journal,
            OriginalReceiptPersist persist) {
        this.store = Objects.requireNonNull(store, "store");
        this.journal = journal;
        this.persist = persist;
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

    /**
     * 把原请求交给既有 {@link IImService#send}。本独占生产者不发 IM；缺端口 WAITING_SOURCE。
     */
    public OriginalReceipt persistOriginal(OriginalRequest request) {
        requireRequest(request);
        if (persist == null) {
            throw new WaitingSourceException(IM_SEND);
        }
        return persist.persist(request);
    }

    /**
     * 频道上的 REPLY/MENTION 走原 {@code ChannelMessageService.send} 字段，不调用 {@link IImService#send}。
     * 原调度对非频道的回复/提及直接拒绝，不会落到普通 send。
     * SEARCH/EDIT/WITHDRAW/PIN 不发送。{@link OriginalRequest#body} 是指纹材料，不能当正文。
     * 缺命令字段为 {@link #IM_SEND_COMMAND}；缺 reply/mention 字段为 {@link #IM_SEND_WIRE}；
     * 缺频道原 writer 为 {@link #CHANNEL_MESSAGE_SEND}。不写入内存 store，不复制私有类。
     */
    public OriginalReceipt sendOriginal(OriginalRequest request, Long actorId, Long conversationId,
            Integer msgType, String content, String extraJson, Long replyToId, List<Long> mentionMemberIds,
            List<Long> mentionRoleIds, boolean mentionEveryone) {
        OriginalRequest required = requireRequest(request);
        if (required.kind != Kind.REPLY && required.kind != Kind.MENTION) {
            throw new NotASendException(required.kind);
        }
        if (content == null || content.isBlank() || content.equals(required.body) || msgType == null
                || actorId == null || actorId.longValue() <= 0L
                || conversationId == null || conversationId.longValue() <= 0L) {
            throw new WaitingSourceException(IM_SEND_COMMAND);
        }
        if (required.kind == Kind.REPLY && (replyToId == null || replyToId.longValue() <= 0L)) {
            throw new WaitingSourceException(IM_SEND_WIRE);
        }
        if (required.kind == Kind.MENTION && !hasMention(mentionMemberIds, mentionRoleIds, mentionEveryone)) {
            throw new WaitingSourceException(IM_SEND_WIRE);
        }
        if (extraJson != null) {
            // 字段已按原 Send 分开。extraJson 只是原命令里的附加串，不能在这里变成第二条发送。
        }
        throw new WaitingSourceException(CHANNEL_MESSAGE_SEND);
    }

    private static boolean hasMention(List<Long> members, List<Long> roles, boolean everyone) {
        return everyone || members != null && !members.isEmpty() || roles != null && !roles.isEmpty();
    }

    public static final class NotASendException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final Kind kind;

        public NotASendException(Kind kind) {
            super(NOT_A_SEND + " " + kind);
            this.kind = kind;
        }

        public Kind getKind() {
            return kind;
        }
    }

    public static ImSendSource fromIm(IImService im) {
        if (im == null) {
            return (memberId, conversationId, targetMemberId, msgType, content, extraJson, clientMessageId) -> {
                throw new WaitingSourceException(IM_SEND);
            };
        }
        return im::send;
    }

    /**
     * 新实例从既有 {@link IImService#readSentMessage} 回读。缺端口 WAITING_SOURCE。
     * 未见过的 clientMessageId 返回 UNKNOWN，不写入内存 store。缺/空 requestId 保持 Java {@code null}。
     * 行上的会话或发送者与本次请求不一致时，正文保持 {@code null}。
     * 不把 IM {@code msgType} 翻译成本组 Kind。
     */
    public OriginalReceipt recoverFromJournal(Long memberId, Long conversationId, String clientMessageId) {
        String id = parseRequestId(clientMessageId);
        if (id == null) {
            return unknownReceipt(null, null);
        }
        if (journal == null) {
            throw new WaitingSourceException(IM_READ_SENT);
        }
        if (memberId == null || memberId.longValue() <= 0L
                || conversationId == null || conversationId.longValue() <= 0L) {
            return unknownReceipt(id, null);
        }
        ImMessage original = journal.readSent(memberId, conversationId, id);
        if (original == null || isBlank(original.getRequestHash())
                || !id.equals(parseRequestId(original.getClientMessageId()))
                || original.getConversationId() == null || !conversationId.equals(original.getConversationId())
                || original.getSenderId() == null || !memberId.equals(original.getSenderId())) {
            return unknownReceipt(id, null);
        }
        return fromMessage(original);
    }

    static OriginalReceipt fromMessage(ImMessage message) {
        if (message == null) {
            return unknownReceipt(null, null);
        }
        String id = parseRequestId(message.getClientMessageId());
        if (id == null || isBlank(message.getRequestHash())) {
            return unknownReceipt(id, null);
        }
        return new OriginalReceipt(id, null, message.getRequestHash(), OUTCOME_UNKNOWN, message.getContent());
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

    /**
     * 原 {@code sendReceipt} 的只读投影。只认 request 行上的 digest、actor、channel、request、
     * operation，以及 result 里的 message 身份。不重算正文或 mention，不调用 send、claim、complete。
     * LEGACY、PENDING、NOT_FOUND、错 actor/channel/key/operation 都不是原 SEND 证明。
     */
    public static OriginalSendProof projectOriginalSend(Long actor, Long channel, String requestId,
            Map<String, Object> row, boolean legacyCommitted) {
        if (actor == null || actor.longValue() <= 0L || channel == null || channel.longValue() <= 0L
                || !validSendKey(requestId)) {
            return sendProof(actor, channel, requestId, null, null, null, "NOT_FOUND", false);
        }
        if (row == null) {
            return sendProof(actor, channel, requestId, null, null, null,
                    legacyCommitted ? "LEGACY" : "NOT_FOUND", false);
        }
        Long rowActor = firstLong(row.get("actorMemberId"), row.get("actor"));
        if (rowActor != null && !actor.equals(rowActor)) {
            return sendProof(actor, channel, requestId, null, null, null, "ACTOR_MISMATCH", false);
        }
        Long rowChannel = longOrNull(row.get("channelId"));
        if (rowChannel == null || !channel.equals(rowChannel)) {
            return sendProof(actor, channel, requestId, null, null, null, "CHANNEL_MISMATCH", false);
        }
        String operation = row.get("operation") == null ? null : String.valueOf(row.get("operation"));
        if (!"SEND".equals(operation)) {
            return sendProof(actor, channel, requestId, operation, null, null, "WRONG_OPERATION", false);
        }
        String digest = row.get("digest") == null ? null : String.valueOf(row.get("digest")).trim();
        if (digest.isEmpty()) {
            return sendProof(actor, channel, requestId, operation, null, null, "NOT_FOUND", false);
        }
        String state = row.get("state") == null ? "" : String.valueOf(row.get("state"));
        if (!"APPLIED".equals(state)) {
            return sendProof(actor, channel, requestId, operation, digest, null,
                    "PENDING".equals(state) ? "PENDING" : "NOT_FOUND", false);
        }
        Long messageId = messageIdOf(row.get("result"));
        String clientId = clientIdOf(row.get("result"));
        if (messageId == null || messageId.longValue() <= 0L || !requestId.equals(clientId)) {
            return sendProof(actor, channel, requestId, operation, digest, null, "NOT_FOUND", false);
        }
        return sendProof(actor, channel, requestId, operation, digest, messageId, "APPLIED", true);
    }

    private static boolean validSendKey(String requestId) {
        return requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}");
    }

    private static OriginalSendProof sendProof(Long actor, Long channel, String requestId, String operation,
            String digest, Long messageId, String state, boolean outcomeConfirmed) {
        return new OriginalSendProof(actor, channel, requestId, operation, digest, messageId, state, outcomeConfirmed);
    }

    private static Long firstLong(Object primary, Object fallback) {
        Long value = longOrNull(primary);
        return value == null ? longOrNull(fallback) : value;
    }

    private static Long longOrNull(Object value) {
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        if (value instanceof String && ((String) value).matches("-?\\d+")) {
            return Long.valueOf((String) value);
        }
        return null;
    }

    private static Long messageIdOf(Object result) {
        String raw = fieldOf(result, "message_id");
        if (raw == null || !raw.matches("\\d+")) {
            return null;
        }
        return Long.valueOf(raw);
    }

    private static String clientIdOf(Object result) {
        return fieldOf(result, "client_message_id");
    }

    private static String fieldOf(Object result, String key) {
        if (result instanceof Map) {
            Object value = ((Map<?, ?>) result).get(key);
            return value == null ? null : String.valueOf(value);
        }
        if (!(result instanceof String)) {
            return null;
        }
        String json = (String) result;
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) {
            return null;
        }
        int cursor = colon + 1;
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= json.length()) {
            return null;
        }
        if (json.charAt(cursor) == '"') {
            int end = json.indexOf('"', cursor + 1);
            return end < 0 ? null : json.substring(cursor + 1, end);
        }
        int end = cursor;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return end == cursor ? null : json.substring(cursor, end);
    }

    /** 原 SEND request 行的证明。没有正文，也没有 mention。 */
    public static final class OriginalSendProof {
        public final Long actor;
        public final Long channel;
        public final String requestId;
        public final String operation;
        public final String digest;
        public final Long messageId;
        public final String state;
        public final boolean outcomeConfirmed;

        OriginalSendProof(Long actor, Long channel, String requestId, String operation, String digest,
                Long messageId, String state, boolean outcomeConfirmed) {
            this.actor = actor;
            this.channel = channel;
            this.requestId = requestId;
            this.operation = operation;
            this.digest = digest;
            this.messageId = messageId;
            this.state = state;
            this.outcomeConfirmed = outcomeConfirmed;
        }

        public Map<String, Object> asView() {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("actor", actor);
            view.put("channel", channel);
            view.put("requestId", requestId);
            view.put("operation", operation);
            view.put("digest", digest);
            view.put("messageId", messageId);
            view.put("state", state);
            view.put("outcomeConfirmed", Boolean.valueOf(outcomeConfirmed));
            return Collections.unmodifiableMap(view);
        }
    }

    /** 只存原回执；实现不得用当前投影表冒充原行。 */
    public interface OriginalReceiptStore {
        OriginalReceipt find(String requestId);

        void insert(OriginalReceipt receipt);
    }

    /** 只读既有 IM 原发送收据；不发、不改 journal。 */
    public interface OriginalReceiptJournal {
        ImMessage readSent(Long memberId, Long conversationId, String clientMessageId);
    }

    /** 既有 {@link IImService#send}；本生产者不另写一套发送。 */
    public interface ImSendSource {
        ImMessage send(Long memberId, Long conversationId, Long targetMemberId, Integer msgType,
                String content, String extraJson, String clientMessageId);
    }

    /** D5/dot 持有的原发送；本生产者不实现第二套 send。 */
    public interface OriginalReceiptPersist {
        OriginalReceipt persist(OriginalRequest request);
    }

    public static final class ImServiceOriginalReceiptJournal implements OriginalReceiptJournal {
        private final IImService im;

        public ImServiceOriginalReceiptJournal(IImService im) {
            this.im = im;
        }

        @Override
        public ImMessage readSent(Long memberId, Long conversationId, String clientMessageId) {
            if (im == null) {
                throw new WaitingSourceException(IM_READ_SENT);
            }
            return im.readSentMessage(memberId, conversationId, clientMessageId);
        }
    }

    public static final class WaitingSourceException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String waitingCapability;

        public WaitingSourceException(String waitingCapability) {
            super("WAITING_SOURCE " + waitingCapability);
            this.waitingCapability = waitingCapability;
        }

        public String getWaitingCapability() {
            return waitingCapability;
        }
    }
}

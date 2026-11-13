package com.chengyinhub.business.service.channel.media;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.media.ImMediaMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelGovernanceMapper;
import com.chengyinhub.business.service.IImMediaAttachGate;
import com.chengyinhub.business.service.channel.media.ImMediaPolicy.Detected;
import com.chengyinhub.business.service.channel.media.ImMediaPolicy.Kind;
import com.chengyinhub.business.service.channel.platform.ChannelMessageService;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * IM 文件/视频/语音（docs/channels/contract.md §6.14）：上传 → 登记待审（app_media_asset，与小程序待审媒体同一审核态）
 * → 审核通过后由 /api/im/send 挂到消息（{@link IImMediaAttachGate}）→ 收件人逐次授权读取 300 秒地址；未挂接的上传可删除。
 * 不转码、不探测：时长/封面/宽高一律 null（未知）。
 */
@Service
public class ImMediaService implements IImMediaAttachGate
{
    private static final Logger log = LoggerFactory.getLogger(ImMediaService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int URL_TTL_SEC = 300;

    @Autowired
    private ImMediaMapper media;
    @Autowired(required = false)
    private ImMediaStore store;
    @Autowired(required = false)
    private ChannelMessageService channels;
    @Autowired
    private ChannelGovernanceMapper conversations;

    // ================================================================ upload ledger

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> upload(long viewer, Kind kind, String conversationId, String uploadRequestId, String sourceSha256,
            String fileName, byte[] bytes)
    {
        long conv = id(conversationId);
        String requestId = ImMediaPolicy.requestId(uploadRequestId);
        String hash = ImMediaPolicy.hash(sourceSha256);
        requireWrite(viewer, conv);
        Map<String, Object> existing = media.lockByRequest(viewer, kind.name(), requestId);
        if (existing != null) return replay(kind, existing, conv, hash);
        if (!hash.equals(ImMediaPolicy.sha256(bytes == null ? new byte[0] : bytes)))
            throw new ChannelRejection(400, "SOURCE_HASH_MISMATCH", "上传字节与声明的摘要不一致");
        Detected detected = ImMediaPolicy.detect(kind, fileName, bytes);
        if (store == null) throw new IllegalStateException("media store unavailable");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind.name());
        row.put("ownerMemberId", viewer);
        row.put("conversationId", conv);
        row.put("uploadRequestId", requestId);
        row.put("sourceSha256", hash);
        row.put("fileName", kind == Kind.FILE ? fileName : kind == Kind.VIDEO ? "video." + detected.ext() : "voice.m4a");
        row.put("ext", detected.ext());
        row.put("mime", detected.mime());
        row.put("byteSize", (long) bytes.length);
        try
        {
            media.insertUpload(row);
        }
        catch (RuntimeException e)
        {
            if (!ChannelRejection.isDuplicateKey(e)) throw e;
            Map<String, Object> raced = media.lockByRequest(viewer, kind.name(), requestId);
            if (raced == null) throw e;
            return replay(kind, raced, conv, hash);
        }
        long uploadId = toLong(row.get("id"));
        long assetId = store.put(viewer, bytes, detected.ext(), detected.mime());
        if (media.markUploaded(uploadId, assetId) != 1) throw new IllegalStateException("upload ledger lost");
        return receipt(kind, media.selectById(uploadId), viewer, conv, requestId, hash);
    }

    /** 原请求回读（GET receipt / POST file/scan）：只读台账与审核态，不重新送审、不重传。 */
    public Map<String, Object> receipt(long viewer, Kind kind, String conversationId, String uploadRequestId, String sourceSha256)
    {
        long conv = id(conversationId);
        String requestId = ImMediaPolicy.requestId(uploadRequestId);
        String hash = ImMediaPolicy.hash(sourceSha256);
        requireRead(viewer, conv);
        Map<String, Object> row = media.selectByRequest(viewer, kind.name(), requestId);
        if (row != null && toLong(row.get("conversationId")) == conv && !hash.equals(row.get("sourceSha256")))
            throw new ChannelRejection(409, "REQUEST_ID_CONFLICT", "该上传标识已用于其他文件");
        if (row != null && toLong(row.get("conversationId")) != conv) row = null;
        return receipt(kind, row, viewer, conv, requestId, hash);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> delete(long viewer, Kind kind, String conversationId, String uploadRequestId)
    {
        long conv = id(conversationId);
        String requestId = ImMediaPolicy.requestId(uploadRequestId);
        Map<String, Object> row = media.lockByRequest(viewer, kind.name(), requestId);
        if (row == null || toLong(row.get("conversationId")) != conv) throw new ChannelRejection(404, "MEDIA_NOT_FOUND", "上传不存在");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "original-im-" + kind.wire() + "-delete-v1");
        out.put("upload_request_id", requestId);
        out.put("conversation_id", String.valueOf(conv));
        if ("DELETED".equals(row.get("state")))
        {
            out.put("state", "DELETED");
            out.put("changed", false);
            return out;
        }
        if (row.get("messageId") != null) throw new ChannelRejection(409, "MEDIA_ATTACHED", "已发送的媒体不能删除，请撤回消息");
        if (media.markDeleted(toLong(row.get("id"))) != 1) throw new IllegalStateException("delete lost");
        try
        {
            if (store != null && row.get("assetId") != null) store.delete(toLong(row.get("assetId")));
        }
        catch (RuntimeException e)
        {
            // 台账已不可引用；对象残留只影响存储，不影响授权读（读路径先看台账状态）。
            log.warn("IM 媒体对象删除失败，台账已标记删除 uploadId={}", row.get("id"), e);
        }
        out.put("state", "DELETED");
        out.put("changed", true);
        return out;
    }

    public Map<String, Object> videoPolicy(long viewer, String conversationId)
    {
        requireRead(viewer, id(conversationId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "original-im-video-policy-v1");
        out.put("maximumSourceBytes", Kind.VIDEO.maxBytes);
        out.put("maximumOutputBytes", Kind.VIDEO.maxBytes);
        out.put("maximumDurationMS", ImMediaPolicy.VIDEO_MAX_DURATION_MS);
        out.put("outputMIME", "video/mp4");
        out.put("transcoding", false);
        return out;
    }

    // ================================================================ recipient reads

    public Map<String, Object> authorization(long viewer, Kind kind, long messageId)
    {
        Map<String, Object> msg = approvedMessage(viewer, kind, messageId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "original-im-" + kind.wire() + "-authorization-v1");
        out.put("message", project(msg));
        return out;
    }

    public Map<String, Object> preview(long viewer, Kind kind, long messageId)
    {
        Map<String, Object> msg = approvedMessage(viewer, kind, messageId);
        Map<String, Object> up = media.selectByMessage(messageId);
        String url = store.shortLivedUrl(toLong(up.get("assetId")), URL_TTL_SEC);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "original-im-" + kind.wire() + "-preview-v1");
        out.put("message_id", String.valueOf(messageId));
        out.put("conversation_id", String.valueOf(toLong(msg.get("conversationId"))));
        out.put("reference", reference(kind, up));
        out.put("state", "READY");
        if (kind == Kind.VIDEO)
        {
            out.put("videoURL", url);
            out.put("coverURL", null);
        }
        else out.put("url", url);
        out.put("expires_in_seconds", URL_TTL_SEC);
        return out;
    }

    /** 语音原字节（audio/mp4）由服务端取回再给出，不把对象存储地址暴露给带会话令牌的请求。 */
    public byte[] voiceBytes(long viewer, long messageId)
    {
        approvedMessage(viewer, Kind.VOICE, messageId);
        Map<String, Object> up = media.selectByMessage(messageId);
        return fetch(store.shortLivedUrl(toLong(up.get("assetId")), 60), Kind.VOICE.maxBytes, toLong(up.get("byteSize")));
    }

    /** 单条原消息读取（GET /api/im/message）：与附件描述同一可见性口径。 */
    public Map<String, Object> message(long viewer, String messageId)
    {
        long id = id(messageId);
        Map<String, Object> msg = media.selectMessage(id);
        if (msg == null) throw new ChannelRejection(404, "MESSAGE_NOT_FOUND", "消息不存在");
        long conv = toLong(msg.get("conversationId"));
        requireRead(viewer, conv);
        if (toLong(msg.get("status")) == 0 && toLong(msg.get("checkStatus")) != 1 && toLong(msg.get("senderId")) != viewer)
            throw new ChannelRejection(404, "MESSAGE_NOT_FOUND", "消息不存在");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "original-im-message-v1");
        out.put("message", project(msg));
        return out;
    }

    private Map<String, Object> approvedMessage(long viewer, Kind kind, long messageId)
    {
        Map<String, Object> msg = media.selectMessage(messageId);
        if (msg == null) throw new ChannelRejection(404, "MESSAGE_NOT_FOUND", "消息不存在");
        requireRead(viewer, toLong(msg.get("conversationId")));
        if (toLong(msg.get("status")) != 0) throw new ChannelRejection(409, "MEDIA_UNAVAILABLE", "消息已撤回或不可见");
        if (toLong(msg.get("msgType")) != kind.msgType) throw new ChannelRejection(404, "MEDIA_NOT_FOUND", "该消息不是此类媒体");
        Map<String, Object> up = media.selectByMessage(messageId);
        if (up == null || !"UPLOADED".equals(up.get("state")) || !kind.name().equals(up.get("kind")))
            throw new ChannelRejection(409, "MEDIA_UNAVAILABLE", "媒体不可用");
        String review = String.valueOf(up.get("reviewState"));
        if ("REJECTED".equals(review)) throw new ChannelRejection(409, "MEDIA_REJECTED", "媒体未通过审核");
        if (!"APPROVED".equals(review)) throw new ChannelRejection(409, "MEDIA_NOT_APPROVED", "媒体审核中");
        return msg;
    }

    // ================================================================ attach gate for /api/im/send

    @Override
    public boolean handles(int msgType) { return Kind.ofMsgType(msgType) != null; }

    @Override
    public long prepare(long sender, long conversationId, int msgType, String content, String extraJson)
    {
        Kind kind = Kind.ofMsgType(msgType);
        try
        {
            return prepareOrReject(sender, conversationId, kind, content, extraJson);
        }
        catch (ChannelRejection e)
        {
            throw new ServiceException(e.getMessage(), e.getStatus()).setDetailMessage(e.getErrorCode());
        }
    }

    long prepareOrReject(long sender, long conv, Kind kind, String content, String extraJson)
    {
        JsonNode card;
        try { card = extraJson == null || extraJson.length() > 4096 ? null : JSON.readTree(extraJson); }
        catch (Exception e) { card = null; }
        if (card == null || !card.isObject() || !("original-im-" + kind.wire() + "-v1").equals(card.path("source").asText(null)))
            throw new ChannelRejection(400, "MEDIA_CARD_INVALID", "媒体卡片格式不正确");
        String requestId = text(card, kind == Kind.VIDEO ? "uploadRequestID" : "uploadRequestId");
        Map<String, Object> up = requestId == null ? null : media.lockByRequest(sender, kind.name(), requestId);
        if (up == null || toLong(up.get("conversationId")) != conv || !"UPLOADED".equals(up.get("state")))
            throw new ChannelRejection(404, "MEDIA_NOT_FOUND", "上传不存在或不属于该会话");
        String asset = String.valueOf(toLong(up.get("assetId")));
        boolean same = switch (kind)
        {
            case FILE -> asset.equals(text(card, "attachmentId")) && up.get("sourceSha256").equals(text(card, "sha256"))
                    && up.get("mime").equals(text(card, "mime")) && up.get("ext").equals(text(card, "ext"))
                    && card.path("size").asLong(-1) == toLong(up.get("byteSize")) && up.get("fileName").equals(content);
            case VIDEO -> asset.equals(text(card, "attachmentID")) && up.get("sourceSha256").equals(text(card, "sha256"))
                    && card.path("size").asLong(-1) == toLong(up.get("byteSize")) && Kind.VIDEO.summary.equals(content);
            case VOICE -> asset.equals(text(card, "assetId")) && "audio/mp4".equals(text(card, "mime"))
                    && card.path("size").asLong(-1) == toLong(up.get("byteSize")) && Kind.VOICE.summary.equals(content);
        };
        if (!same) throw new ChannelRejection(409, "MEDIA_CARD_MISMATCH", "媒体卡片与上传记录不一致");
        if (up.get("messageId") != null) throw new ChannelRejection(409, "MEDIA_ALREADY_SENT", "该媒体已发送过");
        String review = String.valueOf(up.get("reviewState"));
        if ("REJECTED".equals(review)) throw new ChannelRejection(409, "MEDIA_REJECTED", "媒体未通过审核");
        if (!"APPROVED".equals(review)) throw new ChannelRejection(409, "MEDIA_NOT_APPROVED", "媒体审核通过后才能发送");
        return toLong(up.get("id"));
    }

    @Override
    public void bind(long uploadId, long messageId)
    {
        if (media.bindMessage(uploadId, messageId) != 1) throw new ServiceException("媒体挂接失败，请重试", 409).setDetailMessage("MEDIA_ALREADY_SENT");
    }

    @Override
    public String summary(int msgType)
    {
        Kind kind = Kind.ofMsgType(msgType);
        return kind == null ? "[消息]" : kind.summary;
    }

    // ================================================================ projections

    Map<String, Object> receipt(Kind kind, Map<String, Object> row, long owner, long conv, String requestId, String hash)
    {
        boolean found = row != null && !"DELETED".equals(row.get("state"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "original-im-" + kind.wire() + "-upload-v1");
        out.put("owner_member_id", String.valueOf(owner));
        out.put("conversation_id", String.valueOf(conv));
        out.put("upload_request_id", requestId);
        out.put("source_sha256", hash);
        String state = !found ? "NOT_FOUND" : String.valueOf(row.get("state"));
        String review = !found ? null : reviewState(row);
        switch (kind)
        {
            case FILE ->
            {
                out.put("state", state);
                out.put("scan_state", review == null ? null : "APPROVED".equals(review) ? "CLEAN" : "HOLD".equals(review) ? "PENDING" : "UNKNOWN");
                out.put("review_state", review);
                out.put("reference", found ? reference(kind, row) : null);
            }
            case VIDEO ->
            {
                out.put("state", state);
                out.put("video_review_state", review);
                out.put("cover_review_state", null);
                out.put("reference", found ? reference(kind, row) : null);
            }
            case VOICE ->
            {
                out.put("state", "RESERVED".equals(state) ? "PENDING" : state);
                out.put("asset_id", found && row.get("assetId") != null ? String.valueOf(toLong(row.get("assetId"))) : null);
                out.put("review_state", review);
                out.put("duration_ms", null);
                out.put("size", found ? toLong(row.get("byteSize")) : null);
                out.put("waveform", null);
                out.put("asr_state", found ? "NOT_AVAILABLE" : null);
            }
        }
        out.put("transcoded", false);
        return out;
    }

    static Map<String, Object> reference(Kind kind, Map<String, Object> row)
    {
        Map<String, Object> ref = new LinkedHashMap<>();
        String asset = row.get("assetId") == null ? null : String.valueOf(toLong(row.get("assetId")));
        if (kind == Kind.FILE)
        {
            ref.put("uploadRequestID", row.get("uploadRequestId"));
            ref.put("attachmentID", asset);
            ref.put("name", row.get("fileName"));
            ref.put("size", toLong(row.get("byteSize")));
            ref.put("mime", row.get("mime"));
            ref.put("ext", row.get("ext"));
            ref.put("sha256", row.get("sourceSha256"));
        }
        else if (kind == Kind.VIDEO)
        {
            ref.put("uploadRequestID", row.get("uploadRequestId"));
            ref.put("attachmentID", asset);
            ref.put("sha256", row.get("sourceSha256"));
            ref.put("coverSHA256", null);
            ref.put("size", toLong(row.get("byteSize")));
            ref.put("coverSize", null);
            ref.put("durationMS", row.get("durationMs"));
            ref.put("width", row.get("width"));
            ref.put("height", row.get("height"));
        }
        else
        {
            ref.put("uploadRequestId", row.get("uploadRequestId"));
            ref.put("assetId", asset);
            ref.put("size", toLong(row.get("byteSize")));
            ref.put("mime", "audio/mp4");
            ref.put("durationMS", row.get("durationMs"));
        }
        return ref;
    }

    /** app_media_asset 只有 HOLD/APPROVED/REJECTED 三态对外；其他（如 UNAVAILABLE 待重提）按 HOLD。 */
    static String reviewState(Map<String, Object> row)
    {
        Object state = row.get("reviewState");
        if ("APPROVED".equals(state) || "REJECTED".equals(state)) return (String) state;
        return "HOLD";
    }

    static Map<String, Object> project(Map<String, Object> msg)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", toLong(msg.get("id")));
        out.put("conversationId", toLong(msg.get("conversationId")));
        long status = toLong(msg.get("status"));
        out.put("status", status);
        out.put("createTime", msg.get("createTime") == null ? null : String.valueOf(msg.get("createTime")));
        if (status == 0)
        {
            out.put("senderId", toLong(msg.get("senderId")));
            out.put("msgType", toLong(msg.get("msgType")));
            out.put("content", msg.get("content"));
            out.put("extraJson", msg.get("extraJson"));
            out.put("senderName", msg.get("senderName"));
            out.put("senderAvatar", msg.get("senderAvatar"));
        }
        return out;
    }

    private Map<String, Object> replay(Kind kind, Map<String, Object> row, long conv, String hash)
    {
        if (toLong(row.get("conversationId")) != conv || !hash.equals(row.get("sourceSha256")))
            throw new ChannelRejection(409, "REQUEST_ID_CONFLICT", "该上传标识已用于其他文件");
        if ("DELETED".equals(row.get("state"))) throw new ChannelRejection(409, "MEDIA_DELETED", "该上传已删除，请换新的上传标识");
        return receipt(kind, row, toLong(row.get("ownerMemberId")), conv, (String) row.get("uploadRequestId"), hash);
    }

    // ================================================================ access

    void requireWrite(long viewer, long conv)
    {
        if (channels != null && channels.isChannel(conv))
        {
            try { channels.requireAttach(viewer, conv); }
            catch (ServiceException e) { throw rejection(e); }
            return;
        }
        if (conversations.countConversationMember(conv, viewer) == 0) throw new ChannelRejection(404, "CONVERSATION_NOT_FOUND", "会话不存在");
    }

    void requireRead(long viewer, long conv)
    {
        if (channels != null && channels.isChannel(conv))
        {
            try { channels.requireRead(viewer, conv); }
            catch (ServiceException e) { throw rejection(e); }
            return;
        }
        if (conversations.countConversationMember(conv, viewer) == 0) throw new ChannelRejection(404, "MESSAGE_NOT_FOUND", "消息不存在");
    }

    private static ChannelRejection rejection(ServiceException e)
    {
        int code = e.getCode() == null ? 403 : e.getCode();
        String detail = e.getDetailMessage();
        return new ChannelRejection(code >= 400 && code < 500 ? code : 403,
                detail != null && detail.matches("[A-Z][A-Z0-9_]{0,127}") ? detail : "CHANNEL_FORBIDDEN", e.getMessage());
    }

    static byte[] fetch(String url, long maxBytes, long expected)
    {
        try
        {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
            java.net.http.HttpResponse<java.io.InputStream> response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream in = response.body())
            {
                if (response.statusCode() != 200) throw new IllegalStateException("object read status " + response.statusCode());
                byte[] bytes = in.readNBytes((int) maxBytes + 1);
                if (bytes.length > maxBytes || bytes.length != expected) throw new IllegalStateException("object size changed");
                return bytes;
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("object read interrupted", e);
        }
        catch (java.io.IOException e)
        {
            throw new IllegalStateException("object read failed", e);
        }
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    static long id(String raw)
    {
        if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) throw ChannelRejection.badField("id");
        return Long.parseLong(raw);
    }

    static long toLong(Object value)
    {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return 0L;
        return Long.parseLong(value.toString());
    }
}

package com.chengyinhub.business.appgaps;

import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;

/**
 * Author-only topic cover pipeline (/api/topic/cover/**). The upload is decoded and re-encoded as a plain JPEG
 * (metadata dropped) and kept in a private directory outside the public /profile mapping; the bytes are served
 * only to their owner through the content route, pinned by sourceVersion and contentHash. Selecting a cover is a
 * topic configuration change: it is a compare-and-set on the topic config version, the selection version and the
 * content slot, it advances the topic config version by one, and it is refused while the topic is sale-frozen.
 * Player-facing publication of a cover stays with the existing review/release flow; nothing here makes it public.
 */
@Service
public class TopicCoverService {
    static final String POLICY = "OWNED_TOPIC_COVER_V1";
    static final int MAX_UPLOAD = 10 * 1024 * 1024;
    static final int MAX_EDGE = 8192;
    private static final Set<String> SELECT_KEYS = Set.of("topicId", "expectedConfigVersion", "expectedSelectionVersion", "expectedContentSlotId",
            "requestId", "assetId", "sourceVersion", "contentHash");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String privateDir;
    private final LongPredicate saleFrozen;

    @org.springframework.beans.factory.annotation.Autowired
    public TopicCoverService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                             @Value("${chengyin.topic-cover.private-dir:}") String privateDir,
                             com.chengyinhub.business.service.support.TopicAuthorGate gate) {
        this(jdbc, transactions, privateDir, topicId -> gate.frozen(gate.topic(topicId)));
    }

    TopicCoverService(JdbcTemplate jdbc, PlatformTransactionManager transactions, String privateDir, LongPredicate saleFrozen) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.privateDir = privateDir == null ? "" : privateDir.trim();
        this.saleFrozen = saleFrozen;
    }

    /** The topic row when the caller authored it; not-found and not-yours look the same. */
    private Map<String, Object> ownTopic(long memberId, long topicId, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, member_id, config_version FROM cms_topic WHERE id = ? AND (del_flag IS NULL OR del_flag <> 2)"
                + (lock ? " FOR UPDATE" : ""), topicId);
        if (rows.isEmpty() || rows.get(0).get("member_id") == null || AppGapSupport.num(rows.get(0).get("member_id")) != memberId) {
            throw new AppGapException(403, "COVER_FORBIDDEN", "无权操作该主题");
        }
        return rows.get(0);
    }

    private Map<String, Object> slot(long topicId) {
        jdbc.update("INSERT IGNORE INTO topic_cover_selection(topic_id) VALUES (?)", topicId);
        return jdbc.queryForMap("SELECT * FROM topic_cover_selection WHERE topic_id = ?", topicId);
    }

    static Map<String, Object> assetReference(String assetId, String sourceVersion, String hash, long owner) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", POLICY);
        out.put("id", assetId);
        out.put("assetId", assetId);
        out.put("sourceVersion", sourceVersion);
        out.put("contentHash", hash);
        out.put("owner", owner);
        out.put("ownerMemberId", owner);
        out.put("policyVersion", POLICY);
        return out;
    }

    // ---- reads ----

    public Map<String, Object> current(Long memberId, String topicParam) {
        AppGapSupport.requireMember(memberId);
        long topicId = positive(topicParam, "topicId");
        Map<String, Object> topic = ownTopic(memberId, topicId, false);
        Map<String, Object> slot = slot(topicId);
        long config = AppGapSupport.num(topic.get("config_version"));
        long selection = AppGapSupport.num(slot.get("selection_version"));
        Object selectedAt = slot.get("selected_at_config_version");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topicId", topicId);
        out.put("topicConfigVersion", config);
        out.put("selectionVersion", selection);
        out.put("contentSlotId", AppGapSupport.num(slot.get("content_slot_id")));
        out.put("selectedAtConfigVersion", selectedAt == null ? null : AppGapSupport.num(selectedAt));
        if (selection == 0 || slot.get("asset_id") == null) {
            out.put("selectionState", "NONE");
            out.put("assetAvailability", "NONE");
            out.put("display", null);
            return out;
        }
        out.put("selectionState", AppGapSupport.num(selectedAt) == config ? "CURRENT" : "STALE");
        List<Map<String, Object>> assets = jdbc.queryForList("SELECT * FROM topic_cover_asset WHERE asset_id = ?", slot.get("asset_id"));
        boolean available = !assets.isEmpty() && "AVAILABLE".equals(assets.get(0).get("status")) && Files.isRegularFile(path((String) assets.get(0).get("storage_key")));
        out.put("assetAvailability", available ? "AVAILABLE" : "UNAVAILABLE");
        if (!available) {
            out.put("display", null);
            return out;
        }
        Map<String, Object> asset = assets.get(0);
        String id = (String) asset.get("asset_id"), version = (String) asset.get("source_version"), hash = (String) asset.get("content_hash");
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("kind", "OWNED_TOPIC_COVER_AUTHENTICATED_CONTENT_V1");
        display.put("audience", "AUTHOR_ONLY");
        display.put("path", "/api/topic/cover/" + id + "/content?sourceVersion=" + version + "&contentHash=" + hash);
        display.put("contentHash", hash);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("assetReference", assetReference(id, version, hash, memberId));
        wrapper.put("displayReference", display);
        out.put("display", wrapper);
        return out;
    }

    public byte[] content(Long memberId, String assetId, String sourceVersion, String contentHash) {
        AppGapSupport.requireMember(memberId);
        if (!AppGapSupport.lowerUuid(assetId) || !AppGapSupport.lowerUuid(sourceVersion) || !AppGapSupport.hash(contentHash)) {
            throw new AppGapException(404, "COVER_NOT_FOUND", "封面不存在");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM topic_cover_asset WHERE asset_id = ? AND source_version = ? AND content_hash = ? AND owner_member_id = ? AND status = 'AVAILABLE'",
                assetId, sourceVersion, contentHash, memberId);
        if (rows.isEmpty()) throw new AppGapException(404, "COVER_NOT_FOUND", "封面不存在");
        try {
            byte[] bytes = Files.readAllBytes(path((String) rows.get(0).get("storage_key")));
            if (!contentHash.equals(AppGapSupport.sha256(bytes))) throw new AppGapException(404, "COVER_NOT_FOUND", "封面不存在");
            return bytes;
        } catch (IOException e) {
            throw new AppGapException(404, "COVER_NOT_FOUND", "封面不存在");
        }
    }

    // ---- writes ----

    public Map<String, Object> upload(Long memberId, byte[] original) {
        AppGapSupport.requireMember(memberId);
        if (privateDir.isEmpty()) throw new AppGapException(503, "COVER_STORAGE_UNAVAILABLE", "封面存储未配置");
        if (original == null || original.length == 0 || original.length > MAX_UPLOAD) throw new AppGapException(400, "COVER_IMAGE_INVALID", "图片无效");
        byte[] clean = sanitize(original);
        String assetId = UUID.randomUUID().toString(), sourceVersion = UUID.randomUUID().toString();
        String hash = AppGapSupport.sha256(clean);
        String key = memberId + "/" + assetId + "-" + sourceVersion + ".jpg";
        Path target = path(key);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "cover-", ".part");
            Files.write(temp, clean);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new AppGapException(503, "COVER_STORAGE_UNAVAILABLE", "封面存储不可用");
        }
        jdbc.update("INSERT INTO topic_cover_asset(asset_id, source_version, owner_member_id, content_hash, byte_size, storage_key) VALUES (?,?,?,?,?,?)",
                assetId, sourceVersion, memberId, hash, clean.length, key);
        return assetReference(assetId, sourceVersion, hash, memberId);
    }

    /** Decode and re-encode as baseline RGB JPEG: drops EXIF/GPS and any non-image payload. */
    static byte[] sanitize(byte[] original) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(original));
        } catch (IOException | RuntimeException e) {
            image = null;
        }
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1 || image.getWidth() > MAX_EDGE || image.getHeight() > MAX_EDGE) {
            throw new AppGapException(400, "COVER_IMAGE_INVALID", "图片无效");
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, java.awt.Color.WHITE, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(rgb, "jpg", out)) throw new AppGapException(400, "COVER_IMAGE_INVALID", "图片无效");
        } catch (IOException e) {
            throw new AppGapException(400, "COVER_IMAGE_INVALID", "图片无效");
        }
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> select(Long memberId, Map<String, Object> body) {
        AppGapSupport.requireMember(memberId);
        Command c = command(body);
        Map<String, Object> stored = stored(memberId, c.requestId);
        if (stored != null) return replay(stored, c.hash);
        try {
            return tx.execute(status -> {
                Map<String, Object> topic = ownTopic(memberId, c.topicId, true);
                if (saleFrozen.test(c.topicId)) throw new AppGapException(409, "TOPIC_FROZEN", "这个主题现在不能修改。");
                Map<String, Object> slot = jdbc.queryForMap("SELECT * FROM topic_cover_selection WHERE topic_id = ? FOR UPDATE", c.topicId);
                long config = AppGapSupport.num(topic.get("config_version")), selection = AppGapSupport.num(slot.get("selection_version"));
                if (config != c.expectedConfig || selection != c.expectedSelection || AppGapSupport.num(slot.get("content_slot_id")) != c.slot) {
                    throw new AppGapException(409, "COVER_VERSION_CONFLICT", "主题已被修改，请刷新后重试");
                }
                List<Map<String, Object>> asset = jdbc.queryForList(
                        "SELECT 1 FROM topic_cover_asset WHERE asset_id = ? AND source_version = ? AND content_hash = ? AND owner_member_id = ? AND status = 'AVAILABLE'",
                        c.assetId, c.sourceVersion, c.contentHash, memberId);
                if (asset.isEmpty()) throw new AppGapException(409, "COVER_ASSET_UNAVAILABLE", "封面不可用");
                jdbc.update("UPDATE cms_topic SET config_version = config_version + 1 WHERE id = ? AND config_version = ?", c.topicId, config);
                jdbc.update("UPDATE topic_cover_selection SET selection_version = ?, selected_at_config_version = ?, asset_id = ? WHERE topic_id = ?",
                        selection + 1, config + 1, c.assetId, c.topicId);
                Map<String, Object> receipt = new LinkedHashMap<>();
                receipt.put("topicId", c.topicId);
                receipt.put("topicConfigVersion", config + 1);
                receipt.put("selectionVersion", selection + 1);
                receipt.put("contentSlotId", c.slot);
                receipt.put("cover", assetReference(c.assetId, c.sourceVersion, c.contentHash, memberId));
                String json = JSON.toJSONString(receipt);
                jdbc.update("INSERT INTO topic_cover_request(member_id, request_id, request_hash, receipt_json) VALUES (?,?,?,?)",
                        memberId, c.requestId, c.hash, json);
                // The first reply is the stored receipt, identical to every replay.
                return (Map<String, Object>) JSON.parseObject(json);
            });
        } catch (DuplicateKeyException concurrent) {
            Map<String, Object> winner = stored(memberId, c.requestId);
            if (winner == null) throw concurrent;
            return replay(winner, c.hash);
        }
    }

    /** POST /selection/status: the stored outcome of that exact command, else 404 (nothing committed). */
    public Map<String, Object> status(Long memberId, Map<String, Object> body) {
        AppGapSupport.requireMember(memberId);
        Command c = command(body);
        Map<String, Object> stored = stored(memberId, c.requestId);
        if (stored == null) throw new AppGapException(404, "COVER_REQUEST_NOT_FOUND", "没有这条选择请求");
        return replay(stored, c.hash);
    }

    private Map<String, Object> stored(long memberId, String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT request_hash, receipt_json FROM topic_cover_request WHERE member_id = ? AND request_id = ?", memberId, requestId);
        if (rows.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hash", rows.get(0).get("request_hash"));
        out.put("receipt", JSON.parseObject(String.valueOf(rows.get(0).get("receipt_json"))));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> replay(Map<String, Object> stored, String hash) {
        if (!hash.equals(stored.get("hash"))) throw new AppGapException(409, "COVER_REQUEST_REUSED", "请求编号已用于其他选择");
        return (Map<String, Object>) stored.get("receipt");
    }

    private static final class Command {
        long topicId, expectedConfig, expectedSelection, slot;
        String requestId, assetId, sourceVersion, contentHash, hash;
    }

    private static Command command(Map<String, Object> body) {
        Command c = new Command();
        Long topic = AppGapSupport.integer(body, "topicId"), config = AppGapSupport.integer(body, "expectedConfigVersion");
        Long selection = AppGapSupport.integer(body, "expectedSelectionVersion"), slot = AppGapSupport.integer(body, "expectedContentSlotId");
        c.requestId = AppGapSupport.str(body, "requestId");
        c.assetId = AppGapSupport.str(body, "assetId");
        c.sourceVersion = AppGapSupport.str(body, "sourceVersion");
        c.contentHash = AppGapSupport.str(body, "contentHash");
        if (!AppGapSupport.exactKeys(body, SELECT_KEYS) || topic == null || topic <= 0 || config == null || config < 0 || selection == null || selection < 0
                || slot == null || slot <= 0 || !AppGapSupport.anyUuid(c.requestId) || !AppGapSupport.lowerUuid(c.assetId)
                || !AppGapSupport.lowerUuid(c.sourceVersion) || !AppGapSupport.hash(c.contentHash)) {
            throw new AppGapException(400, "COVER_SELECTION_INVALID", "封面选择请求无效");
        }
        c.topicId = topic;
        c.expectedConfig = config;
        c.expectedSelection = selection;
        c.slot = slot;
        c.hash = AppGapSupport.sha256(AppGapSupport.canonical(body));
        return c;
    }

    private Path path(String key) {
        Path base = Paths.get(privateDir).toAbsolutePath().normalize();
        Path target = base.resolve(key).normalize();
        if (!target.startsWith(base)) throw new AppGapException(404, "COVER_NOT_FOUND", "封面不存在");
        return target;
    }

    private static long positive(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,17}")) throw AppGapException.badRequest(field);
        return Long.parseLong(value);
    }
}

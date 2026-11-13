package com.chengyinhub.business.appgaps;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicAuthorGate;
import com.chengyinhub.business.service.support.TopicPublishCheckService;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned attribute-author contract (/api/topic/attribute-author, /validate, /references, /request) and the
 * module preflight read, on the existing state-key registry. configVersion is the topic's attribute registry
 * version (theme_attribute_registry_version). Every read and write names the version it was based on; a write
 * advances it by exactly one, stores an original-request receipt in the same transaction, and is replayed (never
 * re-applied) for the same requestId. Author and sale-freeze checks are the existing TopicAuthorGate ones.
 */
@Service
public class AttributeAuthorService {
    public static final String PROTOCOL = "original-attribute-author-request-v1";
    static final String PATH = "api/topic/attribute-author";
    static final Set<String> FIELDS = Set.of("stateKey", "keyType", "scope", "label", "initialValue", "minValue", "maxValue",
            "display", "color", "icon", "sortOrder", "visible", "persistScope", "rules");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TopicAuthorGate gate;
    private final ThemeStateKeyService keys;
    private final TopicStateKeyRefService references;
    private final TopicPublishCheckService publishCheck;

    public AttributeAuthorService(JdbcTemplate jdbc, PlatformTransactionManager transactions, TopicAuthorGate gate, ThemeStateKeyService keys,
                                  TopicStateKeyRefService references, TopicPublishCheckService publishCheck) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.gate = gate;
        this.keys = keys;
        this.references = references;
        this.publishCheck = publishCheck;
    }

    /** One request's original identity, from headers and the raw body bytes. */
    public static final class Original {
        public final String requestId, sha, epoch;

        public Original(String protocol, String requestId, String sha, String epoch) {
            if (!PROTOCOL.equals(protocol) || !AppGapSupport.lowerUuid(requestId) || !AppGapSupport.hash(sha) || epoch == null || !epoch.matches("[0-9]{1,20}")) {
                throw new AppGapException(400, "ATTRIBUTE_REQUEST_INVALID", "缺少原始请求标识");
            }
            this.requestId = requestId;
            this.sha = sha;
            this.epoch = epoch;
        }
    }

    // ---- shared ----

    private CmsTopic topic(Long memberId, String topicParam, String scope, boolean write) {
        AppGapSupport.requireMember(memberId);
        if (topicParam == null || !topicParam.matches("[1-9][0-9]{0,17}")) throw AppGapException.badRequest("topicId");
        CmsTopic topic = gate.topic(Long.parseLong(topicParam));
        String denial = gate.denial(memberId, topic, scope);
        if (denial != null) throw new AppGapException(403, "TOPIC_FORBIDDEN", denial);
        if (write && gate.frozen(topic)) throw new AppGapException(409, TopicAuthorGate.ERR_FROZEN, TopicAuthorGate.MSG_FROZEN);
        return topic;
    }

    long version(long topicId, boolean lock) {
        jdbc.update("INSERT IGNORE INTO theme_attribute_registry_version(topic_id, registry_version) VALUES (?, 1)", topicId);
        return jdbc.queryForObject("SELECT registry_version FROM theme_attribute_registry_version WHERE topic_id = ?" + (lock ? " FOR UPDATE" : ""), Long.class, topicId);
    }

    private static void expect(String expected, long current) {
        if (expected == null || !expected.matches("0|[1-9][0-9]{0,17}")) throw AppGapException.badRequest("expectedConfigVersion");
        if (Long.parseLong(expected) != current) {
            throw new AppGapException(409, "CONFIG_VERSION_CHANGED", "属性配置已被修改，请刷新后重试", Map.of("configVersion", current));
        }
    }

    static Map<String, Object> wire(ThemeStateKey key) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stateKey", key.getStateKey());
        out.put("keyType", key.getKeyType());
        out.put("scope", key.getScope());
        out.put("label", key.getLabel());
        out.put("initialValue", key.getInitialValue() == null ? 0 : key.getInitialValue());
        out.put("minValue", key.getMinValue() == null ? ThemeStateKey.DEFAULT_MIN : key.getMinValue());
        out.put("maxValue", key.getMaxValue() == null ? ThemeStateKey.DEFAULT_MAX : key.getMaxValue());
        out.put("display", key.getDisplayType() == null ? ThemeStateKey.DISPLAY_NUMBER : key.getDisplayType());
        if (key.getColor() != null && !key.getColor().isEmpty()) out.put("color", key.getColor());
        if (key.getIcon() != null && !key.getIcon().isEmpty()) out.put("icon", key.getIcon());
        out.put("sortOrder", key.getSortOrder() == null ? 0 : key.getSortOrder());
        out.put("visible", key.getVisible() == null || key.getVisible());
        out.put("persistScope", key.getPersistScope() == null ? ThemeStateKey.PERSIST_RUN : key.getPersistScope());
        out.put("rules", key.getRulesJson() == null || key.getRulesJson().isEmpty() ? new JSONArray() : JSON.parseArray(key.getRulesJson()));
        return out;
    }

    private List<Map<String, Object>> rows(long topicId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ThemeStateKey key : keys.listByTopic(topicId)) out.add(wire(key));
        return out;
    }

    private static Map<String, Object> root(long topicId, long version, Object data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("msg", "操作成功");
        out.put("topicId", topicId);
        out.put("configVersion", version);
        out.put("data", data);
        return out;
    }

    // ---- reads ----

    public Map<String, Object> list(Long memberId, String topicParam, String scope, String expected) {
        CmsTopic topic = topic(memberId, topicParam, scope, false);
        long version = version(topic.getId(), false);
        if (expected != null) expect(expected, version);
        return root(topic.getId(), version, rows(topic.getId()));
    }

    public Map<String, Object> references(Long memberId, String topicParam, String scope, String expected, String key) {
        CmsTopic topic = topic(memberId, topicParam, scope, false);
        long version = version(topic.getId(), false);
        expect(expected, version);
        if (key == null || !ThemeStateKey.KEY_NAME.matcher(key).matches()) throw AppGapException.badRequest("key");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stateKey", key);
        List<Map<String, Object>> positions = new ArrayList<>();
        for (String position : references.referencesOf(topic, key)) positions.add(Map.of("text", position));
        data.put("positions", positions);
        return root(topic.getId(), version, data);
    }

    public Map<String, Object> modulePreflight(Long memberId, String topicParam, String scope, String expected) {
        CmsTopic topic = topic(memberId, topicParam, scope, false);
        long version = version(topic.getId(), false);
        expect(expected, version);
        List<Map<String, Object>> issues = new ArrayList<>();
        boolean ready = true;
        for (TopicPublishCheckService.PublishCheckIssue issue : publishCheck.check(topic)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", issue.getLevel());
            row.put("code", issue.getCode());
            row.put("nodeId", issue.getNodeId());
            row.put("message", issue.getMessage());
            row.put("field", issue.getField() == null ? "" : issue.getField());
            row.put("chapterId", issue.getChapterId());
            row.put("blockId", issue.getBlockId());
            issues.add(row);
            if ("error".equals(issue.getLevel())) ready = false;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("topicId", topic.getId());
        report.put("configVersion", version);
        report.put("ready", ready);
        report.put("issues", issues);
        return root(topic.getId(), version, report);
    }

    // ---- validate / save / delete ----

    private static final class Batch {
        long topicId, expected;
        String scope;
        List<JSONObject> patches = new ArrayList<>();
    }

    private Batch batch(String raw) {
        JSONObject body;
        try {
            body = JSON.parseObject(raw);
        } catch (RuntimeException e) {
            body = null;
        }
        if (body == null || !body.keySet().equals(Set.of("topicId", "expectedConfigVersion", "scope", "keys"))) {
            throw new AppGapException(400, "ATTRIBUTE_REQUEST_INVALID", "属性请求无效");
        }
        Batch b = new Batch();
        Object topic = body.get("topicId"), expected = body.get("expectedConfigVersion");
        if (!(topic instanceof Number) || !(expected instanceof Number) || !(body.get("keys") instanceof JSONArray) || !(body.get("scope") instanceof String)) {
            throw new AppGapException(400, "ATTRIBUTE_REQUEST_INVALID", "属性请求无效");
        }
        b.topicId = ((Number) topic).longValue();
        b.expected = ((Number) expected).longValue();
        b.scope = body.getString("scope");
        JSONArray rows = body.getJSONArray("keys");
        if (rows.isEmpty() || rows.size() > 32) throw new AppGapException(400, "ATTRIBUTE_REQUEST_INVALID", "属性请求无效");
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row == null || !FIELDS.containsAll(row.keySet()) || row.getString("stateKey") == null) {
                throw new AppGapException(400, "ATTRIBUTE_REQUEST_INVALID", "属性请求无效");
            }
            b.patches.add(row);
        }
        return b;
    }

    /** Applies the patches onto the current definitions; returns the complete definition catalog after the batch. */
    private List<ThemeStateKey> merged(long topicId, Batch b, List<ThemeStateKey> created, List<ThemeStateKey> updated) {
        Map<String, ThemeStateKey> current = new LinkedHashMap<>();
        for (ThemeStateKey key : keys.listByTopic(topicId)) current.put(key.getStateKey(), key);
        for (JSONObject patch : b.patches) {
            String name = patch.getString("stateKey");
            ThemeStateKey base = current.get(name);
            ThemeStateKey next = base == null ? new ThemeStateKey() : copy(base);
            next.setTopicId(topicId);
            next.setStateKey(name);
            if (patch.containsKey("keyType")) next.setKeyType(patch.getString("keyType"));
            if (patch.containsKey("scope")) next.setScope(patch.getString("scope"));
            if (base != null && (!eq(base.getKeyType(), next.getKeyType()) || !eq(base.getScope(), next.getScope()))) {
                throw new AppGapException(400, ThemeStateKeyService.ERR_INVALID, "状态键的类型与作用域不可修改:" + name);
            }
            if (patch.containsKey("label")) next.setLabel(patch.getString("label"));
            if (patch.containsKey("initialValue")) next.setInitialValue(patch.getInteger("initialValue"));
            if (patch.containsKey("minValue")) next.setMinValue(patch.getInteger("minValue"));
            if (patch.containsKey("maxValue")) next.setMaxValue(patch.getInteger("maxValue"));
            if (patch.containsKey("display")) next.setDisplayType(patch.getString("display"));
            if (patch.containsKey("color")) next.setColor(patch.getString("color"));
            if (patch.containsKey("icon")) next.setIcon(patch.getString("icon"));
            if (patch.containsKey("sortOrder")) next.setSortOrder(patch.getInteger("sortOrder"));
            if (patch.containsKey("visible")) next.setVisible(patch.getBoolean("visible"));
            if (patch.containsKey("persistScope")) next.setPersistScope(patch.getString("persistScope"));
            if (patch.containsKey("rules")) next.setRulesJson(patch.get("rules") == null ? null : JSON.toJSONString(patch.get("rules")));
            (base == null ? created : updated).add(next);
            current.put(name, next);
        }
        List<ThemeStateKey> all = new ArrayList<>(current.values());
        try {
            List<String> problems = keys.check(all);
            if (problems != null && !problems.isEmpty()) throw new AppGapException(400, ThemeStateKeyService.ERR_INVALID, problems.get(0));
        } catch (ServiceException e) {
            throw new AppGapException(400, e.getDetailMessage() == null ? ThemeStateKeyService.ERR_INVALID : e.getDetailMessage(), e.getMessage());
        }
        return all;
    }

    public Map<String, Object> validate(Long memberId, String raw) {
        Batch b = batch(raw);
        CmsTopic topic = topic(memberId, String.valueOf(b.topicId), b.scope, false);
        long version = version(topic.getId(), false);
        expect(String.valueOf(b.expected), version);
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (ThemeStateKey key : merged(topic.getId(), b, new ArrayList<>(), new ArrayList<>())) definitions.add(wire(key));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("definitions", definitions);
        data.put("warnings", new ArrayList<>());
        return root(topic.getId(), version, data);
    }

    static String payloadSha(String method, byte[] payload) {
        byte[] head = (method + "\n" + PATH + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + payload.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(payload, 0, all, head.length, payload.length);
        return AppGapSupport.sha256(all);
    }

    public Map<String, Object> save(Long memberId, String raw, Original original) {
        Batch b = batch(raw);
        if (!original.sha.equals(payloadSha("POST", raw.getBytes(StandardCharsets.UTF_8)))) {
            throw new AppGapException(400, "ATTRIBUTE_REQUEST_DIGEST_MISMATCH", "请求摘要不一致");
        }
        CmsTopic topic = topic(memberId, String.valueOf(b.topicId), b.scope, true);
        Map<String, Object> stored = stored(memberId, original.requestId);
        if (stored != null) return replay(stored, original.sha);
        return commit(memberId, original, topic.getId(), b.expected, "POST", () -> {
            List<ThemeStateKey> created = new ArrayList<>(), updated = new ArrayList<>();
            merged(topic.getId(), b, created, updated);
            try {
                if (!created.isEmpty()) keys.register(topic.getId(), created);
                for (ThemeStateKey key : updated) keys.updateDefinition(topic.getId(), key.getStateKey(), key);
            } catch (ServiceException e) {
                throw new AppGapException(400, e.getDetailMessage() == null ? ThemeStateKeyService.ERR_INVALID : e.getDetailMessage(), e.getMessage());
            }
            List<Map<String, Object>> rows = rows(topic.getId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("keys", rows);
            data.put("definitions", rows);
            data.put("warnings", new ArrayList<>());
            return data;
        });
    }

    public Map<String, Object> delete(Long memberId, String topicParam, String scope, String expected, String key, Original original) {
        CmsTopic topic = topic(memberId, topicParam, scope, true);
        if (key == null || !ThemeStateKey.KEY_NAME.matcher(key).matches()) throw AppGapException.badRequest("key");
        if (expected == null || !expected.matches("0|[1-9][0-9]{0,17}")) throw AppGapException.badRequest("expectedConfigVersion");
        String payload = topic.getId() + "\n" + expected + "\n" + scope + "\n" + key;
        if (!original.sha.equals(payloadSha("DELETE", payload.getBytes(StandardCharsets.UTF_8)))) {
            throw new AppGapException(400, "ATTRIBUTE_REQUEST_DIGEST_MISMATCH", "请求摘要不一致");
        }
        Map<String, Object> stored = stored(memberId, original.requestId);
        if (stored != null) return replay(stored, original.sha);
        List<String> positions = references.referencesOf(topic, key);
        if (!positions.isEmpty()) {
            // Referenced keys are never deleted; the reply names where they are used and changes nothing.
            long version = version(topic.getId(), false);
            expect(expected, version);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deleted", false);
            data.put("positions", positions);
            data.put("keys", rows(topic.getId()));
            Map<String, Object> out = root(topic.getId(), version, data);
            out.put("requestReceipt", receipt(original, "DELETE", topic.getId(), Long.parseLong(expected), "COMMITTED"));
            return out;
        }
        return commit(memberId, original, topic.getId(), Long.parseLong(expected), "DELETE", () -> {
            if (!keys.delete(topic.getId(), key)) throw new AppGapException(404, ThemeStateKeyService.ERR_NOT_FOUND, "状态键不存在");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deleted", true);
            data.put("keys", rows(topic.getId()));
            return data;
        });
    }

    private Map<String, Object> commit(long memberId, Original original, long topicId, long expected, String method,
                                       java.util.function.Supplier<Map<String, Object>> apply) {
        try {
            return tx.execute(status -> {
                long version = version(topicId, true);
                if (version != expected) {
                    throw new AppGapException(409, "CONFIG_VERSION_CHANGED", "属性配置已被修改，请刷新后重试", Map.of("configVersion", version));
                }
                Map<String, Object> data = apply.get();
                // Exactly one step per original request, however many rows it touched.
                jdbc.update("UPDATE theme_attribute_registry_version SET registry_version = ? WHERE topic_id = ?", expected + 1, topicId);
                Map<String, Object> out = root(topicId, expected + 1, data);
                out.put("requestReceipt", receipt(original, method, topicId, expected, "COMMITTED"));
                jdbc.update("INSERT INTO topic_attribute_author_request(member_id, request_id, topic_id, expected_config_version, method, payload_sha256, state, response_json)"
                        + " VALUES (?,?,?,?,?,?,'COMMITTED',?)", memberId, original.requestId, topicId, expected, method, original.sha, JSON.toJSONString(out));
                return out;
            });
        } catch (DuplicateKeyException concurrent) {
            Map<String, Object> winner = stored(memberId, original.requestId);
            if (winner == null) throw concurrent;
            return replay(winner, original.sha);
        }
    }

    private static Map<String, Object> receipt(Original original, String method, long topicId, long expected, String state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL);
        out.put("requestId", original.requestId);
        out.put("payloadSHA256", original.sha);
        out.put("method", method);
        out.put("topicId", topicId);
        out.put("expectedConfigVersion", expected);
        out.put("state", state);
        return out;
    }

    /** GET /request: the stored original reply, or a NOT_FOUND receipt for that exact identity. */
    public Map<String, Object> request(Long memberId, Map<String, String> q) {
        CmsTopic topic = topic(memberId, q.get("topicId"), q.get("scope"), false);
        String method = q.get("method");
        Original original = new Original(q.get("protocolVersion"), q.get("requestId"), q.get("payloadSHA256"), q.get("sessionEpoch"));
        if (!"POST".equals(method) && !"DELETE".equals(method)) throw AppGapException.badRequest("method");
        String expected = q.get("expectedConfigVersion");
        if (expected == null || !expected.matches("0|[1-9][0-9]{0,17}")) throw AppGapException.badRequest("expectedConfigVersion");
        Map<String, Object> stored = stored(memberId, original.requestId);
        if (stored != null) {
            Map<String, Object> reply = replay(stored, original.sha);
            if (!method.equals(stored.get("method")) || AppGapSupport.num(stored.get("topicId")) != topic.getId()) {
                throw new AppGapException(409, "ATTRIBUTE_REQUEST_REUSED", "请求编号已用于其他修改");
            }
            return reply;
        }
        Map<String, Object> out = root(topic.getId(), version(topic.getId(), false), null);
        out.put("requestReceipt", receipt(original, method, topic.getId(), Long.parseLong(expected), "NOT_FOUND"));
        return out;
    }

    private Map<String, Object> stored(long memberId, String requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT topic_id, method, payload_sha256, response_json FROM topic_attribute_author_request WHERE member_id = ? AND request_id = ?", memberId, requestId);
        if (rows.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topicId", rows.get(0).get("topic_id"));
        out.put("method", rows.get(0).get("method"));
        out.put("sha", rows.get(0).get("payload_sha256"));
        out.put("response", JSON.parseObject(String.valueOf(rows.get(0).get("response_json"))));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> replay(Map<String, Object> stored, String sha) {
        if (!sha.equals(stored.get("sha"))) throw new AppGapException(409, "ATTRIBUTE_REQUEST_REUSED", "请求编号已用于其他修改");
        return (Map<String, Object>) stored.get("response");
    }

    private static ThemeStateKey copy(ThemeStateKey k) {
        ThemeStateKey c = new ThemeStateKey();
        c.setId(k.getId());
        c.setTopicId(k.getTopicId());
        c.setStateKey(k.getStateKey());
        c.setKeyType(k.getKeyType());
        c.setScope(k.getScope());
        c.setLabel(k.getLabel());
        c.setInitialValue(k.getInitialValue());
        c.setMinValue(k.getMinValue());
        c.setMaxValue(k.getMaxValue());
        c.setDisplayType(k.getDisplayType());
        c.setColor(k.getColor());
        c.setIcon(k.getIcon());
        c.setSortOrder(k.getSortOrder());
        c.setVisible(k.getVisible());
        c.setPersistScope(k.getPersistScope());
        c.setRulesJson(k.getRulesJson());
        return c;
    }

    private static boolean eq(Object a, Object b) { return a == null ? b == null : a.equals(b); }
}

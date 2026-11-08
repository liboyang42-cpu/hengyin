package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.vo.ContentManifestVO;
import com.chengyinhub.business.domain.vo.TopicContentRevisionView;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.chengyinhub.business.util.QuestionOptionMedia;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内容清单投影(BE-10)。
 *
 * <p>输入只有两样:①同一修订的 {@code publicManifestJson}(BE-06 产出的结构白名单);
 * ②该修订的冻结内容(服务端内部读取,<b>只提取媒介引用与端能力</b>,正文答案一律不复制)。
 * 输出是 {@link ContentManifestVO},不包含 {@code snapshotJson}、{@code nodeAnswers}、
 * {@code themeConfig} 等私有段。
 *
 * <p>为什么资源清单不用 BE-06 的 manifest 直接透传:manifest 是「结构」,不含真实媒介引用;
 * 预取需要从同一份冻结内容里按稳定身份列出 URL。只借用既有白名单
 * ({@link AdvancedGamePublicProjection} 与 {@link AppMediaSchema}),不复制私有 payload,
 * 也不在请求里抓取任何 URL。
 *
 * <p>完整性口径:{@code sha256}/{@code bytes}/{@code mime} 没有权威值时保持 null、
 * {@code verified=false};{@code assetKey} 是稳定身份(章节/节点/模板 + 字段),
 * 签名 URL 刷新不改变身份。{@code priority} 只是预取顺序提示,不参与授权或结算。
 */
public final class ContentManifestProjection {

    public static final int MANIFEST_VERSION = 1;
    public static final String SOURCE_LOCALE = "zh-CN";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 公共清单里绝不允许出现的键(归一化:小写 + 去掉非字母数字)。 */
    private static final Set<String> FORBIDDEN_KEYS = new HashSet<String>(java.util.Arrays.asList(
            "aliases", "alias", "canonical", "canonicaljson",
            "correct", "correctanswer", "correctoption",
            "answer", "answers", "answertext", "answerorder", "answerreveal",
            "questionanswer", "hint", "hint1", "hint2", "hinttext",
            "merchantguide", "merchantmemberid", "checkincode",
            "snapshotjson", "nodeanswers", "nodeconfigs", "themeconfig", "themedefinition",
            "journeyrules", "journeystory", "cluecontent", "contentcontractjson",
            "secret", "secrets", "secretkey", "apikey", "privatekey", "privatepayload",
            "token", "accesstoken", "signature"));

    /** 已物化章节块里出现媒介 URL 的块类型。 */
    private static final Set<String> URL_BLOCK_TYPES = new HashSet<String>(java.util.Arrays.asList("image", "audio"));

    private ContentManifestProjection() {
    }

    /** 明确修订:结构读 {@code publicManifestJson},资源与端能力读同一修订的冻结内容。 */
    public static ContentManifestVO fromRevision(TopicContentRevisionView revision, JsonNode frozen) {
        if (revision == null || revision.getId() == null || revision.getTopicId() == null)
            throw new IllegalStateException("内容修订身份缺失");
        ObjectNode manifest = sanitizePublicTree(parseObject(revision.getPublicManifestJson(), "公共清单不可解析"));
        requireSame(manifest, "topicId", revision.getTopicId(), "公共清单主题不一致");
        if (manifest.has("revisionNo") && revision.getRevisionNo() != null)
            requireSame(manifest, "revisionNo", revision.getRevisionNo(), "公共清单修订号不一致");
        if (manifest.has("contentSha256") && StringUtils.isNotBlank(revision.getContentSha256()))
            requireSame(manifest, "contentSha256", revision.getContentSha256(), "公共清单内容哈希不一致");

        ContentManifestVO vo = base(revision.getTopicId(), null, frozen);
        vo.setStatus(ContentManifestVO.STATUS_REVISION);
        vo.setContentRevisionId(revision.getId());
        vo.setRevisionNo(revision.getRevisionNo());
        vo.setContentSha256(revision.getContentSha256());
        vo.setManifestVersion(manifest.path("manifestVersion").asInt(MANIFEST_VERSION));
        vo.setSchemaVersion(manifest.has("schemaVersion")
                ? Integer.valueOf(manifest.path("schemaVersion").asInt(0)) : revision.getSchemaVersion());
        vo.setRouteMode(text(manifest, "routeMode"));
        vo.setOpeningChapterId(longOrNull(manifest.get("openingChapterId")));
        vo.setOpeningChapterName(text(manifest, "openingChapterName"));
        vo.setStations(stationsFromManifest(manifest, frozen));
        return vo;
    }

    /** 已开局:按本局自己的冻结快照给清单,不读当前发布修订。 */
    public static ContentManifestVO fromRunSnapshot(Long topicId, Long activityId, JsonNode frozen,
            Long contentRevisionId) {
        if (frozen == null || !frozen.isObject()) throw new IllegalStateException("本局内容快照不可用");
        ContentManifestVO vo = base(topicId, activityId, frozen);
        vo.setStatus(ContentManifestVO.STATUS_RUN_SNAPSHOT);
        // 只有快照自己声明的修订号才可信;没有就是 null,不从当前发布头补一个。
        vo.setContentRevisionId(contentRevisionId);
        vo.setRevisionNo(null);
        vo.setContentSha256(null);
        vo.setManifestVersion(MANIFEST_VERSION);
        vo.setSchemaVersion(frozen.has("runtimeSnapshotVersion")
                ? Integer.valueOf(frozen.path("runtimeSnapshotVersion").asInt(0)) : null);
        vo.setRouteMode(text(frozen, "routeMode"));
        JsonNode opening = frozen.path("openingChapter");
        if (opening.isObject()) {
            vo.setOpeningChapterId(longOrNull(opening.get("chapterId")));
            vo.setOpeningChapterName(text(opening, "name"));
        }
        vo.setStations(stationsFromSnapshot(frozen));
        return vo;
    }

    /** 老内容:没有修订清单,明确声明继续在线读取;不拼一份假修订。 */
    public static ContentManifestVO onlineLegacy(Long topicId, Long activityId) {
        ContentManifestVO vo = new ContentManifestVO();
        vo.setStatus(ContentManifestVO.STATUS_ONLINE_LEGACY);
        vo.setTopicId(topicId);
        vo.setActivityId(activityId);
        vo.setManifestVersion(null);
        vo.setStations(new ArrayList<ContentManifestVO.Station>());
        vo.setAssets(new ArrayList<ContentManifestVO.Asset>());
        vo.setRequiredCapabilities(null);
        vo.setAssetVerification(ContentManifestVO.VERIFICATION_NONE);
        vo.setExpiresAt(null);
        vo.setOnlineRequired(true);
        vo.setSourceLocale(SOURCE_LOCALE);
        return vo;
    }

    private static ContentManifestVO base(Long topicId, Long activityId, JsonNode frozen) {
        ContentManifestVO vo = new ContentManifestVO();
        vo.setTopicId(topicId);
        vo.setActivityId(activityId);
        vo.setRequiredCapabilities(capabilities(frozen));
        List<ContentManifestVO.Asset> assets = Assets.collect(frozen);
        vo.setAssets(assets);
        vo.setAssetVerification(assets.isEmpty()
                ? ContentManifestVO.VERIFICATION_NONE : ContentManifestVO.VERIFICATION_UNVERIFIED);
        vo.setExpiresAt(null);
        vo.setOnlineRequired(false);
        vo.setSourceLocale(SOURCE_LOCALE);
        return vo;
    }

    /**
     * 端能力由内容自己推导(复用 {@link ContentCapabilityRequirements}),不采信客户端声明。
     * 内容形状还不是新快照时返回 null,而不是空数组冒充「无要求」。
     */
    public static List<ContentManifestVO.Requirement> capabilities(JsonNode frozen) {
        if (frozen == null || !frozen.isObject()) return null;
        if (!frozen.has(NodeJourneySnapshot.NODE_ANSWERS_KEY) && !frozen.has("routeStations")
                && !frozen.has("nodeConfigs")) return null;
        ArrayNode rows = ContentCapabilityRequirements.required(frozen);
        List<ContentManifestVO.Requirement> result = new ArrayList<ContentManifestVO.Requirement>(rows.size());
        for (JsonNode row : rows) {
            String key = text(row, "key");
            if (StringUtils.isBlank(key)) continue;
            ContentManifestVO.Requirement item = new ContentManifestVO.Requirement();
            item.setKey(key);
            item.setMinVersion(row.has("minVersion") ? Integer.valueOf(row.path("minVersion").asInt(1)) : null);
            result.add(item);
        }
        return result;
    }

    /**
     * <b>补充防线,不是唯一防线。</b>结构字段本身是逐字段允许清单
     * ({@link #stationsFromManifest} 只取 nodeId/nodeName/chapterId/scanReady,能力只取 key/minVersion),
     * 新增字段默认不会自动公开。本方法只在这一层再剥一遍答案 / 别名 / 提示 / 商家秘密,
     * 防的是上游投影写漏;只删不加,不改写其它值。
     */
    public static ObjectNode sanitizePublicTree(JsonNode source) {
        if (source == null || !source.isObject()) throw new IllegalStateException("公共清单不是对象");
        ObjectNode copy = (ObjectNode) source.deepCopy();
        scrub(copy);
        return copy;
    }

    private static void scrub(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> names = new ArrayList<String>();
            Iterator<String> fields = object.fieldNames();
            while (fields.hasNext()) names.add(fields.next());
            for (String name : names) {
                if (FORBIDDEN_KEYS.contains(normalizeKey(name))) object.remove(name);
                else scrub(object.get(name));
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) scrub(child);
        }
    }

    private static String normalizeKey(String name) {
        if (name == null) return "";
        StringBuilder normalized = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) normalized.append(Character.toLowerCase(c));
        }
        return normalized.toString();
    }

    /** 逐字段允许清单:manifest 里即使多出 aliases/secret 等键,这里也一个都不会被带出去。 */
    private static List<ContentManifestVO.Station> stationsFromManifest(ObjectNode manifest, JsonNode frozen) {
        Map<Long, String> chapterScope = chapterScopes(frozen);
        List<ContentManifestVO.Station> stations = new ArrayList<ContentManifestVO.Station>();
        JsonNode rows = manifest.path("stations");
        if (!rows.isArray()) return stations;
        for (JsonNode row : rows) {
            ContentManifestVO.Station station = station(row, chapterScope);
            if (station != null) stations.add(station);
        }
        return stations;
    }

    private static List<ContentManifestVO.Station> stationsFromSnapshot(JsonNode frozen) {
        Map<Long, String> chapterScope = chapterScopes(frozen);
        List<ContentManifestVO.Station> stations = new ArrayList<ContentManifestVO.Station>();
        for (JsonNode row : frozen.path("routeStations")) {
            ContentManifestVO.Station station = station(row, chapterScope);
            if (station != null) stations.add(station);
        }
        return stations;
    }

    private static ContentManifestVO.Station station(JsonNode row, Map<Long, String> chapterScope) {
        Long nodeId = longOrNull(row.get("nodeId"));
        if (nodeId == null || nodeId <= 0L) return null;
        ContentManifestVO.Station station = new ContentManifestVO.Station();
        station.setNodeId(nodeId);
        station.setNodeName(text(row, "nodeName"));
        Long chapterId = longOrNull(row.get("chapterId"));
        station.setChapterId(chapterId);
        station.setScanReady(row.path("scanReady").asBoolean(false));
        station.setScope(scopeOf(chapterId, chapterScope));
        return station;
    }

    /** 章节分类只来自发布内容本身:开局章 = START,非必选章 / 结局章 = OPTIONAL,其余 = ROUTE。 */
    private static Map<Long, String> chapterScopes(JsonNode frozen) {
        Map<Long, String> scopes = new HashMap<Long, String>();
        if (frozen == null || !frozen.isObject()) return scopes;
        JsonNode opening = frozen.path("openingChapter");
        if (opening.isObject()) mergeScope(scopes, longOrNull(opening.get("chapterId")), ContentManifestVO.SCOPE_START);
        for (JsonNode chapter : frozen.path("thoughtChapters")) {
            Long chapterId = longOrNull(chapter.get("chapterId"));
            if (chapterId == null) continue;
            boolean isOpening = chapter.path("opening").asBoolean(false);
            boolean required = chapter.path("required").asBoolean(true);
            mergeScope(scopes, chapterId, isOpening ? ContentManifestVO.SCOPE_START
                    : required ? ContentManifestVO.SCOPE_ROUTE : ContentManifestVO.SCOPE_OPTIONAL);
        }
        Iterator<Map.Entry<String, JsonNode>> endings = frozen.path("endingChapters").fields();
        while (endings.hasNext()) {
            Map.Entry<String, JsonNode> ending = endings.next();
            Long chapterId = longOrNull(ending.getValue().get("id"));
            if (chapterId == null) {
                try { chapterId = Long.valueOf(ending.getKey()); }
                catch (NumberFormatException ignored) { chapterId = null; }
            }
            mergeScope(scopes, chapterId, ContentManifestVO.SCOPE_OPTIONAL);
        }
        return scopes;
    }

    private static void mergeScope(Map<Long, String> scopes, Long chapterId, String scope) {
        if (chapterId == null) return;
        String existing = scopes.get(chapterId);
        if (existing == null || rank(scope) < rank(existing)) scopes.put(chapterId, scope);
    }

    private static String scopeOf(Long chapterId, Map<Long, String> chapterScope) {
        if (chapterId != null) {
            String scope = chapterScope.get(chapterId);
            if (scope != null) return scope;
        }
        // 站点内容默认是路线内容;只有实际来自开局章 / 结局章 / 非必选章时才改判。
        return ContentManifestVO.SCOPE_ROUTE;
    }

    private static int rank(String scope) {
        if (ContentManifestVO.SCOPE_START.equals(scope)) return 0;
        if (ContentManifestVO.SCOPE_ROUTE.equals(scope)) return 1;
        return 2;
    }

    private static int priority(String scope) {
        return rank(scope) + 1;
    }

    private static void requireSame(JsonNode node, String field, Object expected, String message) {
        JsonNode actual = node.get(field);
        if (actual == null || actual.isNull()) throw new IllegalStateException(message);
        boolean same;
        if (expected instanceof Long) same = actual.isIntegralNumber() && actual.asLong() == ((Long) expected).longValue();
        else same = expected != null && expected.toString().equals(actual.asText());
        if (!same) throw new IllegalStateException(message);
    }

    private static JsonNode parseObject(String json, String message) {
        if (StringUtils.isBlank(json)) throw new IllegalStateException(message);
        try {
            JsonNode parsed = JSON.readTree(json);
            if (parsed == null || !parsed.isObject()) throw new IllegalStateException(message);
            return parsed;
        } catch (IllegalStateException keep) {
            throw keep;
        } catch (Exception unusable) {
            throw new IllegalStateException(message, unusable);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) return null;
        String text = value.asText();
        return StringUtils.isBlank(text) ? null : text.trim();
    }

    private static Long longOrNull(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong() ? value.asLong() : null;
    }

    /**
     * 资源收集器:只把「内容里真实引用的 URL」变成稳定 assetKey,不推断 mime / 大小 / 哈希。
     */
    private static final class Assets {

        private final Map<String, ContentManifestVO.Asset> byKey = new LinkedHashMap<String, ContentManifestVO.Asset>();
        private final Map<Long, String> chapterScope;

        private Assets(Map<Long, String> chapterScope) {
            this.chapterScope = chapterScope;
        }

        static List<ContentManifestVO.Asset> collect(JsonNode frozen) {
            Assets collector = new Assets(chapterScopes(frozen));
            if (frozen != null && frozen.isObject()) collector.collectFrozen(frozen);
            return new ArrayList<ContentManifestVO.Asset>(collector.byKey.values());
        }

        private void collectFrozen(JsonNode frozen) {
            JsonNode opening = frozen.path("openingChapter");
            if (opening.isObject()) chapter(opening, ContentManifestVO.SCOPE_START, "opening");
            for (JsonNode chapter : frozen.path("thoughtChapters")) {
                Long chapterId = longOrNull(chapter.get("chapterId"));
                String scope = chapterId == null ? ContentManifestVO.SCOPE_ROUTE : scopeOf(chapterId, chapterScope);
                chapter(chapter, scope, chapterId == null ? "thought" : String.valueOf(chapterId));
            }
            Iterator<Map.Entry<String, JsonNode>> endings = frozen.path("endingChapters").fields();
            while (endings.hasNext()) {
                Map.Entry<String, JsonNode> ending = endings.next();
                chapter(ending.getValue(), ContentManifestVO.SCOPE_OPTIONAL, ending.getKey());
            }
            nodeAnswers(frozen.path(NodeJourneySnapshot.NODE_ANSWERS_KEY), frozen.path("nodeConfigs"));
        }

        private void chapter(JsonNode chapter, String scope, String keyLabel) {
            if (chapter == null || !chapter.isObject()) return;
            String prefix = "chapter:" + keyLabel;
            String imgArr = text(chapter, "imgArr");
            if (imgArr != null) {
                List<String> images = splitMediaList(imgArr);
                for (int i = 0; i < images.size(); i++) put(prefix + ":imgArr[" + i + "]", images.get(i), scope, null);
            }
            put(prefix + ":audio", text(chapter, "audioUrl"), scope, null);
            JsonNode blocks = chapter.get("blocks");
            if (blocks == null || !blocks.isArray()) return;
            int index = 0;
            for (JsonNode block : blocks) {
                String blockKey = text(block, "key");
                if (blockKey == null) blockKey = String.valueOf(index);
                String type = text(block, "type");
                if (URL_BLOCK_TYPES.contains(type)) {
                    put(prefix + ":block:" + blockKey + ":url", text(block, "url"), scope, text(block, "altText"));
                } else if ("dream".equals(type)) {
                    int imageIndex = 0;
                    for (JsonNode image : block.path("images")) {
                        put(prefix + ":block:" + blockKey + ":image[" + imageIndex + "]",
                                text(image, "url"), scope, text(image, "line"));
                        imageIndex++;
                    }
                }
                index++;
            }
        }

        private void nodeAnswers(JsonNode answers, JsonNode configs) {
            if (answers == null || !answers.isObject()) return;
            Iterator<Map.Entry<String, JsonNode>> rows = answers.fields();
            while (rows.hasNext()) {
                Map.Entry<String, JsonNode> row = rows.next();
                String nodeKey = row.getKey();
                JsonNode answer = row.getValue();
                if (answer == null || !answer.isObject()) continue;
                JsonNode presentation = answer.path("nodePresentation");
                Long chapterId = longOrNull(presentation.get("chapterId"));
                String scope = scopeOf(chapterId, chapterScope);
                put("node:" + nodeKey + ":img", text(presentation, "imgUrl"), scope, null);

                JsonNode template = answer.path("templatePresentation");
                Long templateId = longOrNull(answer.get("templateId"));
                if (templateId == null) templateId = longOrNull(template.get("id"));
                String templatePrefix = "template:" + (templateId == null ? nodeKey : templateId);
                if (template.isObject()) {
                    put(templatePrefix + ":img", text(template, "imgUrl"), scope, null);
                    put(templatePrefix + ":storyImg", text(template, "storyImg"), scope, null);
                    put(templatePrefix + ":questionImg", text(template, "questionImg"), scope, null);
                    put(templatePrefix + ":medalImg", text(template, "medalImg"), scope, null);
                    put(templatePrefix + ":audio", text(template, "audioUrl"), scope, null);
                    put(templatePrefix + ":questionAudio", text(template, "questionAudio"), scope, null);
                    optionMedia(templatePrefix, text(template, "questionOptionMediaJson"), scope);
                }

                JsonNode config = configs == null ? null : configs.get(nodeKey);
                if (config != null && config.isObject() && !config.isEmpty()) {
                    // 先过既有玩家公开投影,再从投影结果里取 URL —— 顺序不能反。
                    String safe = AdvancedGamePublicProjection.safeConfigJson(config.toString(), false);
                    if (StringUtils.isNotBlank(safe)) {
                        try {
                            collectMedia(JSON.readTree(safe), "node:" + nodeKey + ":config", scope);
                        } catch (Exception unreadable) {
                            // 投影结果本来就不可能坏;坏了下发清单也不该带猜测值。
                        }
                    }
                }
            }
        }

        private void optionMedia(String templatePrefix, String json, String scope) {
            Map<String, Map<String, String>> options = QuestionOptionMedia.parse(json);
            for (Map.Entry<String, Map<String, String>> option : options.entrySet()) {
                for (Map.Entry<String, String> media : option.getValue().entrySet()) {
                    put(templatePrefix + ":option:" + option.getKey() + ":" + media.getKey(), media.getValue(), scope, null);
                }
            }
        }

        private void collectMedia(JsonNode node, String path, String scope) {
            if (node == null) return;
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String name = field.getKey();
                    if (AppMediaSchema.MEDIA_FIELDS.contains(name) || "url".equals(name)) {
                        media(name, field.getValue(), path + "." + name, scope);
                    } else {
                        collectMedia(field.getValue(), path + "." + name, scope);
                    }
                }
            } else if (node.isArray()) {
                int index = 0;
                for (JsonNode child : node) {
                    collectMedia(child, path + "[" + index + "]", scope);
                    index++;
                }
            }
        }

        private void media(String field, JsonNode value, String path, String scope) {
            if (value == null) return;
            if (value.isTextual()) {
                List<String> urls = AppMediaSchema.MULTI_MEDIA_FIELDS.contains(field)
                        ? splitMediaList(value.asText()) : java.util.Collections.singletonList(value.asText());
                for (int i = 0; i < urls.size(); i++) {
                    put(urls.size() == 1 ? path : path + "[" + i + "]", urls.get(i), scope, null);
                }
            } else if (value.isArray()) {
                int index = 0;
                for (JsonNode child : value) {
                    if (child.isTextual()) put(path + "[" + index + "]", child.asText(), scope, null);
                    else collectMedia(child, path + "[" + index + "]", scope);
                    index++;
                }
            }
        }

        private void put(String assetKey, String url, String scope, String textAlternative) {
            if (StringUtils.isBlank(url)) return;
            String normalizedUrl = url.trim();
            String normalizedScope = scope == null ? ContentManifestVO.SCOPE_ROUTE : scope;
            ContentManifestVO.Asset existing = byKey.get(assetKey);
            if (existing != null) {
                // 同一身份在多站复用:保留更强的那一档;URL 不一致时保留首次出现的真实引用,
                // 不因为签名地址刷新就把同一内容算成两份。
                if (rank(normalizedScope) < rank(existing.getScope())) {
                    existing.setScope(normalizedScope);
                    existing.setPriority(priority(normalizedScope));
                }
                return;
            }
            ContentManifestVO.Asset asset = new ContentManifestVO.Asset();
            asset.setAssetKey(assetKey);
            asset.setUrl(normalizedUrl);
            asset.setSha256(null);
            asset.setBytes(null);
            asset.setMime(null);
            asset.setScope(normalizedScope);
            asset.setPriority(priority(normalizedScope));
            asset.setTextAlternative(StringUtils.isBlank(textAlternative) ? null : textAlternative.trim());
            asset.setVerified(false);
            asset.setUnverifiedReason("HASH_AND_BYTES_UNKNOWN");
            byKey.put(assetKey, asset);
        }

        private List<String> splitMediaList(String raw) {
            List<String> values = new ArrayList<String>();
            if (StringUtils.isBlank(raw)) return values;
            for (String part : raw.split("[;\\n]")) {
                if (StringUtils.isNotBlank(part)) values.add(part.trim());
            }
            return values;
        }
    }
}

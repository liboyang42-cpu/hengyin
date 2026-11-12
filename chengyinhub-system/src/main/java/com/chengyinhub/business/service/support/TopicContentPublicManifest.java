package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 内容修订的公共 manifest 投影(BE-06)。
 *
 * <p>本类是「玩家/缓存能看到的修订说明」的唯一白名单闸门。原则与
 * {@link com.chengyinhub.business.util.AdvancedGamePublicProjection} 一致:<b>没写进来的字段
 * 一律不下发</b>。冻结 payload 里有 {@code nodeAnswers}(正确答案)、{@code nodeConfigs} /
 * {@code themeConfig}(判题与条件)、{@code journeyRules}(数值规则),这些<b>全部不投影</b>。
 *
 * <p>v1 只声明「结构 + 导航骨架」:版本、修订号、内容 hash、路线模式、开局章节、站点清单。
 * 节点正文/媒体清单/端能力分别由既有玩家读面、BE-10、BE-07 负责 —— 本类不臆造字段,
 * 也不把 BE-07 尚未产出的 {@code requiredCapabilities} 写成空数组冒充「无要求」。
 *
 * <p>为什么 manifest 里带 {@code revisionNo} 而不带自增 {@code id}:修订行是不可变的,
 * 自增 id 只有落库后才知道,写进 manifest 就得再 UPDATE 一次(违反「永不改写」)。
 * {@code (topicId, revisionNo)} 在插入前就确定且唯一,自增 id 由
 * {@link com.chengyinhub.business.domain.vo.TopicContentRevisionView#getId()} 同行返回。
 */
public final class TopicContentPublicManifest {

    public static final int MANIFEST_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TopicContentPublicManifest() {
    }

    /**
     * 旧调用:没有冻结地理。存量测试与不关心地理的调用方继续用这个重载。
     */
    public static ObjectNode build(JsonNode frozen, Long topicId, long revisionNo, String contentSha256) {
        return build(frozen, topicId, revisionNo, contentSha256, null);
    }

    /**
     * @param frozen         编译器产出的精确冻结 payload(含 private 段,本方法只读白名单字段)
     * @param topicId        主题 id
     * @param revisionNo     本条修订号
     * @param contentSha256  冻结 payload 的字节哈希
     * @param geography      本次发布冻结的地理身份;null 或全空 = UNKNOWN,不写该键
     * @return 可直接下发的 manifest;输入不可解析时抛错而不是返回半成品
     */
    public static ObjectNode build(JsonNode frozen, Long topicId, long revisionNo, String contentSha256,
                                   TopicContentGeography geography) {
        if (frozen == null || !frozen.isObject())
            throw new IllegalStateException("冻结内容不是对象，无法生成公共 manifest");
        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("manifestVersion", MANIFEST_VERSION);
        manifest.put("topicId", topicId);
        manifest.put("revisionNo", revisionNo);
        manifest.put("contentSha256", contentSha256);

        // 真实 schema version:直接读冻结 payload 自己声明的 runtimeSnapshotVersion。
        // 存量发布头没有该字段时记 0,表示「历史无版本标记」,不猜成 1。
        manifest.put("schemaVersion", frozen.path("runtimeSnapshotVersion").asInt(0));
        if (frozen.has("r5AuthorValidationVersion"))
            manifest.put("authorValidationVersion", frozen.path("r5AuthorValidationVersion").asInt(0));

        if (frozen.path("routeMode").isTextual() && !frozen.path("routeMode").asText().trim().isEmpty())
            manifest.put("routeMode", frozen.path("routeMode").asText());

        JsonNode opening = frozen.path("openingChapter");
        if (opening.isObject() && opening.has("chapterId")) {
            manifest.put("openingChapterId", opening.path("chapterId").asLong());
            if (opening.path("name").isTextual()) manifest.put("openingChapterName", opening.path("name").asText());
        }

        ArrayNode stations = manifest.putArray("stations");
        for (JsonNode station : frozen.path("routeStations")) {
            if (!station.isObject() || !station.has("nodeId")) continue;
            ObjectNode row = stations.addObject();
            row.put("nodeId", station.path("nodeId").asLong());
            if (station.path("nodeName").isTextual()) row.put("nodeName", station.path("nodeName").asText());
            if (station.has("chapterId") && !station.path("chapterId").isNull())
                row.put("chapterId", station.path("chapterId").asLong());
            // 只下发「是否需要到店扫码」这一条玩家必须提前知道的事实;
            // merchantMemberId 属运营归属,不进公共 manifest。
            row.put("scanReady", station.path("scanReady").asBoolean(false));
        }

        // F1:冻结地理(城市/国家)随本次发布进入 manifest 白名单投影;排行榜消费冻结值,
        // 不读 live 主题城市。冻结 payload 内的唯一路径是 themeConfig.contentGeography。
        if (geography != null) geography.writeIntoManifest(manifest);

        // BE-07 产出端能力后由内容自己声明。这里只放行 {key,minVersion} 两个字段:
        // 即便将来冻结 payload 里带上别的能力注释/内部字段,也不会随 manifest 外发。
        copyCapabilities(frozen, manifest, "requiredCapabilities");
        copyCapabilities(frozen, manifest, "optionalCapabilities");
        return manifest;
    }

    private static void copyCapabilities(JsonNode source, ObjectNode target, String key) {
        JsonNode declared = source.path(key);
        if (!declared.isArray()) return; // 未声明 ≠ 声明空数组
        ArrayNode rows = target.putArray(key);
        for (JsonNode row : declared) {
            if (!row.isObject() || !row.path("key").isTextual()) continue;
            ObjectNode copy = rows.addObject();
            copy.put("key", row.path("key").asText());
            if (row.path("minVersion").isNumber()) copy.put("minVersion", row.path("minVersion").asInt());
        }
    }

    /** 序列化为落库字符串。 */
    public static String buildJson(JsonNode frozen, Long topicId, long revisionNo, String contentSha256) {
        return buildJson(frozen, topicId, revisionNo, contentSha256, null);
    }

    public static String buildJson(JsonNode frozen, Long topicId, long revisionNo, String contentSha256,
                                   TopicContentGeography geography) {
        return build(frozen, topicId, revisionNo, contentSha256, geography).toString();
    }
}

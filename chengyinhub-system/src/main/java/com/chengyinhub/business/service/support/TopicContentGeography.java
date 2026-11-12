package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 一次发布冻结下来的地理身份(BE-06 F1，消费路径以 BE-14 冻结口径为准)。
 *
 * <p><b>唯一位置</b>：冻结 payload 的 {@code $.themeConfig.contentGeography}
 * = {@code {schemaVersion:1, countryCode, cityCode}}。不是任意 header 根键 ——
 * 排行/榜单只按这条路径读；根键会让消费方分裂成两套。
 *
 * <p>为什么必须冻结而不是运行时读 {@code cms_topic.city_code}：作者发布后城市/国家可以变，
 * 已发布修订与已开出的局必须保留发布当时的事实。读 live 主题会把老局算进新城市。
 *
 * <p>取值口径：
 * <ul>
 *   <li>{@code countryCode}：explicit ISO 3166-1 alpha-2 大写。只认作者显式填写的
 *       {@code contentContract.placeCountryCode}（或冻结图里已有的值）；缺失/非法一律 null = UNKNOWN，
 *       <b>绝不推断成 CN</b>。</li>
 *   <li>{@code cityCode}：本次发布时实际使用的 {@code cms_topic.city_code}
 *       （冻结图已声明则优先）。</li>
 * </ul>
 *
 * <p>本类不是第二套存储：冻结值只写进既有的发布头/修订 payload 与其公共 manifest 投影。
 * 旧 published 图没有该键就是 UNKNOWN，不按 live 主题补历史。
 */
public final class TopicContentGeography {

    /** {@code themeConfig} 下的键名(BE-14 冻结口径)。 */
    public static final String KEY = "contentGeography";
    /** 完整路径,供消费方/文档引用。 */
    public static final String PATH = "themeConfig." + KEY;
    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String countryCode;
    private final String cityCode;

    private TopicContentGeography(String countryCode, String cityCode) {
        this.countryCode = countryCode;
        this.cityCode = cityCode;
    }

    /**
     * 从本次正式发布的两个真源派生。冻结图里已有的值优先(编译器/BE-14 口径),
     * 否则用发布时行锁读到的作者值。
     *
     * @param frozenPublication          精确冻结 payload
     * @param authoredCityCode           本次发布时的 {@code cms_topic.city_code}
     * @param authoredContentContractJson 本次发布时的 contentContract(取 placeCountryCode)
     */
    public static TopicContentGeography of(JsonNode frozenPublication, String authoredCityCode,
                                           String authoredContentContractJson) {
        JsonNode declared = frozenPublication == null ? null : frozenPublication.path("themeConfig").path(KEY);
        String city = text(declared, "cityCode");
        if (city == null) city = trimToNull(authoredCityCode);
        String country = normalizeCountry(text(declared, "countryCode"));
        if (country == null) country = normalizeCountry(explicitAuthoredCountry(authoredContentContractJson));
        return new TopicContentGeography(country, city);
    }

    /**
     * 正式发布时把地理写进冻结 payload（供发布头与不可变修订共用同一份字节）。
     *
     * <p>三条硬边界：
     * <ol>
     *   <li>{@code themeConfig} 不存在时<b>不创建</b>它 —— 凭空多一个 themeConfig 会改变
     *       「是否旅程配置/共享玩法」的既有判定，是行为变更，不是补字段；</li>
     *   <li>冻结图里已有地理值时原样返回，字节都不动（编译器口径优先）；</li>
     *   <li>任何解析异常都返回原串：地理是附加信息，不能把正式发布挡下来。</li>
     * </ol>
     */
    public static String decoratePublication(String frozenSnapshot, CmsTopic topic) {
        if (frozenSnapshot == null || frozenSnapshot.trim().isEmpty()) return frozenSnapshot;
        try {
            JsonNode parsed = MAPPER.readTree(frozenSnapshot);
            if (parsed == null || !parsed.isObject()) return frozenSnapshot;
            ObjectNode root = (ObjectNode) parsed;
            JsonNode theme = root.path("themeConfig");
            if (!theme.isObject()) return frozenSnapshot;
            if (hasValues(theme.path(KEY))) return frozenSnapshot;
            TopicContentGeography geography = of(root,
                    topic == null ? null : topic.getCityCode(),
                    topic == null ? null : topic.getContentContractJson());
            if (geography.isEmpty()) return frozenSnapshot;
            ((ObjectNode) theme).set(KEY, geography.toNode());
            return MAPPER.writeValueAsString(root);
        } catch (Exception unusable) {
            return frozenSnapshot;
        }
    }

    /** 公共 manifest 的白名单投影(manifest 是独立文档,故在其根上给出同一形状)。 */
    public ObjectNode writeIntoManifest(ObjectNode manifest) {
        if (manifest == null || isEmpty()) return manifest;
        manifest.set(KEY, toNode());
        return manifest;
    }

    /**
     * 新局冻结快照头：只从不可变修订(or 冻结图)取，绝不回读当前可编辑主题。
     * {@code themeConfig} 不存在则不创建（理由同 {@link #decoratePublication}）。
     */
    public void writeIntoRunSnapshot(ObjectNode runSnapshot) {
        if (runSnapshot == null || isEmpty()) return;
        JsonNode theme = runSnapshot.path("themeConfig");
        if (!theme.isObject() || hasValues(theme.path(KEY))) return;
        ((ObjectNode) theme).set(KEY, toNode());
    }

    /** 从已落库的公共 manifest 读回；没有/不可解析返回 null。 */
    public static TopicContentGeography fromManifestJson(String publicManifestJson) {
        if (publicManifestJson == null || publicManifestJson.trim().isEmpty()) return null;
        try {
            JsonNode parsed = MAPPER.readTree(publicManifestJson);
            JsonNode declared = parsed == null ? null : parsed.path(KEY);
            if (declared == null || !declared.isObject()) return null;
            TopicContentGeography geography = new TopicContentGeography(
                    normalizeCountry(text(declared, "countryCode")), text(declared, "cityCode"));
            return geography.isEmpty() ? null : geography;
        } catch (Exception unusable) {
            // 存量/手工数据不是合法 JSON:当作没有冻结地理,不阻断开局,也不猜。
            return null;
        }
    }

    public boolean isEmpty() {
        return countryCode == null && cityCode == null;
    }

    public String getCountryCode() { return countryCode; }

    public String getCityCode() { return cityCode; }

    @Override
    public String toString() {
        return "TopicContentGeography{countryCode=" + countryCode + ", cityCode=" + cityCode + '}';
    }

    private ObjectNode toNode() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("schemaVersion", SCHEMA_VERSION);
        node.put("countryCode", countryCode);
        node.put("cityCode", cityCode);
        return node;
    }

    private static boolean hasValues(JsonNode node) {
        return node != null && node.isObject()
                && (trimToNull(node.path("countryCode").asText(null)) != null
                    || trimToNull(node.path("cityCode").asText(null)) != null);
    }

    /** 作者显式填写的 country;它是内容合同里的一个字段,不是主题列。 */
    private static String explicitAuthoredCountry(String contentContractJson) {
        if (contentContractJson == null || contentContractJson.trim().isEmpty()) return null;
        try {
            JsonNode contract = MAPPER.readTree(contentContractJson);
            return contract == null ? null : trimToNull(contract.path("placeCountryCode").asText(null));
        } catch (Exception unusable) {
            return null;
        }
    }

    /** 只接受真正的 alpha-2;`CN` 不会因为「中国产品」被默认填上。 */
    private static String normalizeCountry(String raw) {
        String value = trimToNull(raw);
        if (value == null) return null;
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        return upper.matches("[A-Z]{2}") ? upper : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.path(field);
        return value.isTextual() ? trimToNull(value.asText()) : null;
    }

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

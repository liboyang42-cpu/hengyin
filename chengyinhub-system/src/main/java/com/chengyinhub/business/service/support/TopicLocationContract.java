package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主题地点合同：把「模板要求什么地点」与「本次城市实际绑定哪个节点」分开。
 *
 * <p>落库列 {@code cms_topic.content_contract_json}，结构：
 * <ul>
 *   <li>{@code locationSlots}：作者/模板要求（要看见什么、站位、开放条件、替代策略），<b>不含</b>坐标/地址；</li>
 *   <li>{@code locationBindings}：城市实例绑定（nodeId/cityCode/revision/绑定状态），
 *       坐标与地址的唯一真源仍是 {@link CmsTopicNode}；</li>
 *   <li>{@code adoption}：来源模板 id、来源 {@code config_version}、采用方式与目标城市。</li>
 * </ul>
 *
 * <p><b>不设 {@code VERIFIED}</b>：本仓没有现场核验服务，服务端只做结构确认
 * （节点属于本主题、有地址/坐标、城市一致），{@code confirmedAt/confirmedBy} 只表示这一步。
 *
 * <p><b>旧版兼容</b>：{@code LEGACY} 采用（旧客户端不带 bindingIntent）写下的要求与绑定
 * 一律是提示级（{@code LEGACY_UNVERIFIED}），<b>不拦发布</b>；只有显式 {@code NEW_CITY}
 * 采用且必填槽仍未绑定，才是发布缺口。理由：旧主题从来没有这份合同，新规则不能倒着拦存量。
 *
 * <p>版本 {@value #SCHEMA_VERSION}；未知字段原样保留（只校验已知字段），避免后续消费者加键时丢数据。
 */
public final class TopicLocationContract {

    public static final int SCHEMA_VERSION = 1;
    public static final String INTENT_LEGACY = "LEGACY";
    public static final String INTENT_SAME_CITY = "SAME_CITY";
    public static final String INTENT_NEW_CITY = "NEW_CITY";

    public static final String STATUS_UNBOUND = "UNBOUND";
    public static final String STATUS_BOUND_UNVERIFIED = "BOUND_UNVERIFIED";
    public static final String STATUS_REBIND_REQUIRED = "REBIND_REQUIRED";
    public static final String STATUS_LEGACY_UNVERIFIED = "LEGACY_UNVERIFIED";

    public static final String LEVEL_ERROR = "error";
    public static final String LEVEL_WARN = "warn";

    public static final String ISSUE_INVALID = "LOCATION_CONTRACT_INVALID";
    public static final String ISSUE_UNBOUND = "LOCATION_SLOT_UNBOUND";
    public static final String ISSUE_REBIND = "LOCATION_REBIND_REQUIRED";
    public static final String ISSUE_LEGACY = "LOCATION_BINDING_LEGACY_UNVERIFIED";
    public static final String ISSUE_CITY_MISMATCH = "LOCATION_BINDING_CITY_MISMATCH";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SLOT_KEY = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern CITY_CODE = Pattern.compile("[0-9]{6}");
    /** BE-14：国家码是发布者填写的 ISO 3166-1 alpha-2（大写两位），不接受自由文本、不从城市反推。 */
    private static final Pattern ISO_COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final Pattern DECIMAL_ID = Pattern.compile("[1-9][0-9]{0,18}");

    private static final Set<String> PURPOSES = set("EVIDENCE", "SPACE", "EVENT", "RELATION");
    private static final Set<String> ALTERNATIVE_POLICIES =
            set("NONE", "ALTERNATE_EVIDENCE", "ALTERNATE_NODE", "SKIP_ALLOWED");
    private static final Set<String> STATUSES =
            set(STATUS_UNBOUND, STATUS_BOUND_UNVERIFIED, STATUS_REBIND_REQUIRED, STATUS_LEGACY_UNVERIFIED);
    private static final Set<String> INTENTS = set(INTENT_LEGACY, INTENT_SAME_CITY, INTENT_NEW_CITY);

    /**
     * BE-14：合同根可以携带发布者填写的国家归属 {@value #KEY_PLACE_COUNTRY_CODE}（ISO 3166-1 alpha-2）。
     * 列进来变成「已知键」是为了走严格校验（大写两位），不再是过去的未知键原样透传。
     */
    public static final String KEY_PLACE_COUNTRY_CODE = "placeCountryCode";

    private static final Set<String> KNOWN_ROOT_KEYS =
            set("schemaVersion", "locationSlots", "locationBindings", "adoption", KEY_PLACE_COUNTRY_CODE);
    private static final Set<String> KNOWN_SLOT_KEYS = set("slotKey", "title", "purpose", "required",
            "observationTarget", "observationAction", "accessRequirements", "hostRequired",
            "propRequirements", "alternativePolicy", "derived", "sourceNodeId");
    private static final Set<String> KNOWN_BINDING_KEYS = set("slotKey", "nodeId", "cityCode",
            "bindingRevision", "verificationStatus", "confirmedAt", "confirmedBy",
            "approvedFallbackNodeIds", "openingHours", "observationNote", "referenceMediaIds",
            "previousBinding");
    private static final Set<String> KNOWN_PREVIOUS_BINDING_KEYS =
            set("cityCode", "nodeId", "verificationStatus", "confirmedAt");
    private static final Set<String> KNOWN_ADOPTION_KEYS = set("sourceTemplateTopicId", "sourceConfigVersion", "sourceContentRevisionId",
            "adoptedAt", "bindingIntent", "targetCityCode", "legacy");
    /** 城市事实字段不得出现在合同里：坐标/地址/门牌唯一真源是实例节点。 */
    private static final Set<String> CITY_FACT_KEYS =
            set("latitude", "longitude", "lat", "lng", "address", "coordinates", "coords", "geo");

    private static final int MAX_SLOTS = 100;
    private static final int MAX_BINDINGS = 100;
    private static final int MAX_TEXT = 300;
    private static final int MAX_NOTE = 1000;
    private static final int MAX_MEDIA_IDS = 20;

    private TopicLocationContract() {
    }

    /** 空合同；采用来源完全没有地点要求时也写一份，保证来源血缘可回读。 */
    public static ObjectNode empty() {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.set("locationSlots", JSON.createArrayNode());
        root.set("locationBindings", JSON.createArrayNode());
        return root;
    }

    /** 严格解析并规范化已知字段；非法内容抛 {@link IllegalArgumentException}，调用方不得静默降级。 */
    public static ObjectNode parse(String raw) {
        if (StringUtils.isBlank(raw)) throw new IllegalArgumentException("地点合同为空");
        JsonNode tree;
        try {
            tree = JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("地点合同不是合法 JSON", e);
        }
        ObjectNode root = object(tree, "地点合同必须是对象");
        if (!root.has("schemaVersion") || root.get("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
            throw new IllegalArgumentException("地点合同 schemaVersion 仅支持 " + SCHEMA_VERSION);
        }
        ObjectNode normalized = JSON.createObjectNode();
        normalized.put("schemaVersion", SCHEMA_VERSION);
        normalized.set("locationSlots", normalizeSlots(root.path("locationSlots")));
        normalized.set("locationBindings",
                normalizeBindings(root.path("locationBindings"), normalized.path("locationSlots")));
        if (root.hasNonNull("adoption")) normalized.set("adoption", normalizeAdoption(root.path("adoption")));
        if (root.hasNonNull(KEY_PLACE_COUNTRY_CODE)) {
            normalized.put(KEY_PLACE_COUNTRY_CODE, isoCountry(root.get(KEY_PLACE_COUNTRY_CODE).asText(), KEY_PLACE_COUNTRY_CODE));
        }
        carryUnknown(root, normalized, KNOWN_ROOT_KEYS);
        return normalized;
    }

    public static String canonical(String raw) {
        return parse(raw).toString();
    }

    /**
     * 语义相等（键序无关）。
     *
     * <p>回读校验不能用字符串相等：{@code cms_topic.content_contract_json} 是 MySQL JSON 列，
     * 服务端返回的文本会重排对象键。这里按解析后的对象比较，避免把「JSON 列格式化」误判成写入失败。
     */
    public static boolean same(String left, String right) {
        if (StringUtils.isBlank(left) || StringUtils.isBlank(right)) return false;
        try {
            return parse(left).equals(parse(right));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static boolean isBlank(String raw) {
        return StringUtils.isBlank(raw);
    }

    public static String cityCode(String raw, String field) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (!CITY_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 必须是 6 位城市 adcode");
        }
        return value;
    }

    /**
     * BE-14：发布者填写的国家归属（ISO 3166-1 alpha-2）。未填返回 null（= UNKNOWN，不得推断）；
     * 填了但不合规则抛 {@link IllegalArgumentException}，由保存入口拒绝，不静默丢弃。
     */
    public static String isoCountry(String raw, String field) {
        if (raw == null) return null;
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        if (!ISO_COUNTRY.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 必须是 ISO 3166-1 alpha-2 两位大写国家码");
        }
        return value;
    }

    /** 从合同原文读国家码；缺失或合同不可解析时返回 null（=UNKNOWN），供只读投影使用，不抛异常。 */
    public static String placeCountryCode(String raw) {
        if (StringUtils.isBlank(raw)) return null;
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception invalid) {
            return null;
        }
        JsonNode value = root.path(KEY_PLACE_COUNTRY_CODE);
        if (!value.isTextual()) return null;
        try {
            return isoCountry(value.asText(), KEY_PLACE_COUNTRY_CODE);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** 不传 = LEGACY；只接受显式 SAME_CITY / NEW_CITY。 */
    public static String intent(String raw) {
        if (StringUtils.isBlank(raw)) return INTENT_LEGACY;
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!INTENTS.contains(value)) {
            throw new IllegalArgumentException("bindingIntent 仅支持 SAME_CITY / NEW_CITY");
        }
        if (INTENT_LEGACY.equals(value)) {
            throw new IllegalArgumentException("不传 bindingIntent 即旧版采用；不接受显式 LEGACY");
        }
        return value;
    }

    public static boolean isNewCity(String intent) {
        return INTENT_NEW_CITY.equals(intent);
    }

    // ---------------------------------------------------------------------
    // 采用：把来源合同/来源节点翻译成副本合同
    // ---------------------------------------------------------------------

    /**
     * 生成副本的地点合同。
     *
     * @param source         源主题（调用方已在同一 fence 下读到）
     * @param sourceNodes    与本次复制同一事务读到的来源节点
     * @param nodeIdMap      源节点 id → 副本节点 id
     * @param intent         LEGACY / SAME_CITY / NEW_CITY
     * @param targetCityCode NEW_CITY 必填的 6 位城市
     */
    public static String buildAdoptionContract(CmsTopic source, List<CmsTopicNode> sourceNodes,
                                               Map<Long, Long> nodeIdMap, String intent,
                                               String targetCityCode, Date now, Long sourceContentRevisionId) {
        if (source == null || source.getId() == null) throw new IllegalArgumentException("来源主题缺失");
        if (!INTENTS.contains(intent)) throw new IllegalArgumentException("采用方式不合法");
        String targetCity = cityCode(targetCityCode, "targetCityCode");
        if (INTENT_NEW_CITY.equals(intent) && targetCity == null) {
            throw new IllegalArgumentException("跨城采用必须指定 targetCityCode");
        }
        if (INTENT_NEW_CITY.equals(intent) && targetCity != null
                && source.getCityCode() != null && source.getCityCode().equals(targetCity)) {
            throw new IllegalArgumentException("目标城市与来源相同，请使用同城复用");
        }
        if (INTENT_SAME_CITY.equals(intent) && targetCity != null
                && source.getCityCode() != null && !source.getCityCode().equals(targetCity)) {
            throw new IllegalArgumentException("同城复用要求目标城市与来源一致；跨城请使用 NEW_CITY");
        }

        boolean explicitSlots = hasLocationSlots(source.getContentContractJson());
        ObjectNode sourceContract = explicitSlots ? parse(source.getContentContractJson()) : empty();
        ArrayNode slots = explicitSlots
                ? (ArrayNode) sourceContract.path("locationSlots").deepCopy()
                : deriveSlots(sourceNodes);
        Map<String, Long> sourceNodeBySlot = slotNodeIndex(slots, sourceContract.path("locationBindings"));

        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.set("locationSlots", slots);
        result.set("locationBindings", transitionBindings(sourceContract.path("locationBindings"), slots,
                sourceNodeBySlot, nodeIdMap, intent, targetCity, source.getCityCode()));

        ObjectNode adoption = JSON.createObjectNode();
        adoption.put("sourceTemplateTopicId", String.valueOf(source.getId()));
        if (source.getConfigVersion() == null) adoption.putNull("sourceConfigVersion");
        else adoption.put("sourceConfigVersion", source.getConfigVersion());
        if (sourceContentRevisionId == null) adoption.putNull("sourceContentRevisionId");
        else adoption.put("sourceContentRevisionId", String.valueOf(sourceContentRevisionId));
        adoption.put("adoptedAt", DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, now));
        adoption.put("bindingIntent", intent);
        if (targetCity == null) adoption.putNull("targetCityCode");
        else adoption.put("targetCityCode", targetCity);
        adoption.put("legacy", INTENT_LEGACY.equals(intent));
        result.set("adoption", adoption);
        return canonical(result.toString());
    }

    public static String buildAdoptionContract(CmsTopic source, List<CmsTopicNode> sourceNodes,
                                               Map<Long, Long> nodeIdMap, String intent,
                                               String targetCityCode, Date now) {
        return buildAdoptionContract(source, sourceNodes, nodeIdMap, intent, targetCityCode, now, null);
    }

    private static boolean hasLocationSlots(String raw) {
        if (StringUtils.isBlank(raw)) return false;
        try {
            JsonNode tree = JSON.readTree(raw);
            return tree != null && tree.isObject() && tree.has("locationSlots");
        } catch (Exception e) {
            throw new IllegalArgumentException("来源主题地点合同不是合法 JSON", e);
        }
    }

    /**
     * 只有真正带地址/坐标的节点才生成地点槽；无地点故事节点不生成虚假地点槽。
     * 槽 key 与 {@code sourceNodeId} 一起保留来源节点身份，跨城重绑时仍知道「这个槽原来对应哪个节点」。
     */
    private static ArrayNode deriveSlots(List<CmsTopicNode> sourceNodes) {
        ArrayNode slots = JSON.createArrayNode();
        if (sourceNodes == null) return slots;
        for (CmsTopicNode node : sourceNodes) {
            if (node == null || node.getId() == null || !hasCityFact(node)) continue;
            ObjectNode slot = JSON.createObjectNode();
            slot.put("slotKey", "loc-" + node.getId());
            slot.put("title", truncate(StringUtils.isBlank(node.getName()) ? "地点" : node.getName(), 80));
            slot.put("purpose", "SPACE");
            slot.put("required", true);
            slot.put("observationTarget", "");
            slot.put("observationAction", "");
            slot.put("accessRequirements", "");
            slot.put("hostRequired", false);
            slot.put("propRequirements", "");
            slot.put("alternativePolicy", "NONE");
            slot.put("derived", true);
            slot.put("sourceNodeId", String.valueOf(node.getId()));
            slots.add(slot);
        }
        return slots;
    }

    private static boolean hasCityFact(CmsTopicNode node) {
        return StringUtils.isNotBlank(node.getAddress())
                || (StringUtils.isNotBlank(node.getLongitude()) && StringUtils.isNotBlank(node.getLatitude()));
    }

    /** 槽 → 来源节点：优先显式 {@code sourceNodeId}，其次推导 key，最后绑定的 nodeId。 */
    private static Map<String, Long> slotNodeIndex(ArrayNode slots, JsonNode bindings) {
        Map<String, Long> index = new LinkedHashMap<String, Long>();
        for (JsonNode slot : slots) {
            String key = slot.path("slotKey").asText();
            Long explicit = parseId(slot.path("sourceNodeId"), null);
            if (explicit != null) {
                index.put(key, explicit);
                continue;
            }
            if (slot.path("derived").asBoolean(false) && key.startsWith("loc-")) {
                try {
                    index.put(key, Long.valueOf(key.substring(4)));
                } catch (NumberFormatException ignore) {
                    // 手工合同的同名 slotKey 不是推导来源
                }
            }
        }
        for (JsonNode binding : bindings) {
            Long nodeId = parseId(binding.path("nodeId"), null);
            if (nodeId != null) index.putIfAbsent(binding.path("slotKey").asText(), nodeId);
        }
        return index;
    }

    private static ArrayNode transitionBindings(JsonNode sourceBindings, ArrayNode slots,
                                                Map<String, Long> sourceNodeBySlot,
                                                Map<Long, Long> nodeIdMap, String intent,
                                                String targetCity, String sourceCity) {
        ArrayNode result = JSON.createArrayNode();
        for (JsonNode slot : slots) {
            String slotKey = slot.path("slotKey").asText();
            JsonNode sourceBinding = null;
            for (JsonNode candidate : sourceBindings) {
                if (slotKey.equals(candidate.path("slotKey").asText())) {
                    sourceBinding = candidate;
                    break;
                }
            }
            Long sourceNodeId = sourceNodeBySlot.get(slotKey);
            boolean hadBinding = sourceBinding != null || sourceNodeId != null;
            if (!INTENT_LEGACY.equals(intent) || hadBinding) {
                result.add(transitionOne(slotKey, sourceBinding, sourceNodeId, hadBinding, nodeIdMap,
                        intent, targetCity, sourceCity));
            }
        }
        return result;
    }

    private static ObjectNode transitionOne(String slotKey, JsonNode sourceBinding, Long sourceNodeId,
                                            boolean hadBinding, Map<Long, Long> nodeIdMap, String intent,
                                            String targetCity, String sourceCity) {
        ObjectNode binding = JSON.createObjectNode();
        binding.put("slotKey", slotKey);
        Long copiedNodeId = sourceNodeId == null ? null : nodeIdMap.get(sourceNodeId);
        String previousStatus = sourceBinding == null ? null : sourceBinding.path("verificationStatus").asText(null);
        String previousCity = sourceBinding == null ? null : sourceBinding.path("cityCode").asText(null);
        // 没有显式绑定（由节点推导的槽）时，上一次事实城市就是来源主题城市；重绑清单要能显示它。
        if (previousCity == null) previousCity = sourceCity;
        String previousConfirmedAt = sourceBinding == null ? null : sourceBinding.path("confirmedAt").asText(null);

        if (INTENT_NEW_CITY.equals(intent)) {
            binding.putNull("nodeId");
            binding.put("cityCode", targetCity);
            binding.put("bindingRevision", 0);
            binding.put("verificationStatus", hadBinding ? STATUS_REBIND_REQUIRED : STATUS_UNBOUND);
            binding.putNull("confirmedAt");
            binding.putNull("confirmedBy");
            binding.set("approvedFallbackNodeIds", JSON.createArrayNode());
            binding.putNull("openingHours");
            binding.putNull("observationNote");
            binding.set("referenceMediaIds", JSON.createArrayNode());
        } else {
            if (copiedNodeId == null) {
                binding.putNull("nodeId");
                binding.put("bindingRevision", 0);
                binding.put("verificationStatus", STATUS_UNBOUND);
            } else {
                binding.put("nodeId", String.valueOf(copiedNodeId));
                binding.put("bindingRevision", 1);
                binding.put("verificationStatus", INTENT_LEGACY.equals(intent)
                        ? STATUS_LEGACY_UNVERIFIED : STATUS_BOUND_UNVERIFIED);
            }
            String city = targetCity != null ? targetCity : previousCity != null ? previousCity : sourceCity;
            if (city == null) binding.putNull("cityCode"); else binding.put("cityCode", city);
            binding.putNull("confirmedAt");
            binding.putNull("confirmedBy");
            ArrayNode fallbacks = JSON.createArrayNode();
            if (sourceBinding != null) {
                for (JsonNode raw : sourceBinding.path("approvedFallbackNodeIds")) {
                    Long mapped = nodeIdMap.get(parseId(raw, null));
                    if (mapped != null && !mapped.equals(copiedNodeId)) fallbacks.add(String.valueOf(mapped));
                }
            }
            binding.set("approvedFallbackNodeIds", fallbacks);
            if (sourceBinding != null && sourceBinding.hasNonNull("openingHours")) {
                binding.put("openingHours", sourceBinding.path("openingHours").asText());
            } else {
                binding.putNull("openingHours");
            }
            // 现场备注/媒体属于来源实例作者私有资料：采用者一律不继承，只保留元数据供比对。
            binding.putNull("observationNote");
            binding.set("referenceMediaIds", JSON.createArrayNode());
        }

        if (hadBinding) {
            ObjectNode previous = JSON.createObjectNode();
            if (previousCity == null) previous.putNull("cityCode"); else previous.put("cityCode", previousCity);
            if (sourceNodeId == null) previous.putNull("nodeId");
            else previous.put("nodeId", String.valueOf(sourceNodeId));
            if (previousStatus == null) previous.putNull("verificationStatus");
            else previous.put("verificationStatus", previousStatus);
            if (previousConfirmedAt == null) previous.putNull("confirmedAt");
            else previous.put("confirmedAt", previousConfirmedAt);
            binding.set("previousBinding", previous);
        } else {
            binding.putNull("previousBinding");
        }
        return binding;
    }

    // ---------------------------------------------------------------------
    // 绑定写（作者在目标城市选点；调用方必须先过编辑窗口与 fence）
    // ---------------------------------------------------------------------

    /**
     * 写入/解绑一个地点槽。服务端写 revision / confirmedAt / confirmedBy，不采信前端“已核验”。
     *
     * @param expectedRevision 乐观锁；null = 不校验
     */
    public static String applyBinding(String raw, String slotKey, Long nodeId, List<Long> fallbackNodeIds,
                                      String openingHours, String observationNote, List<String> mediaIds,
                                      Integer expectedRevision, String topicCityCode, Long confirmedBy, Date now) {
        if (StringUtils.isBlank(raw)) {
            throw new ServiceException("主题尚未建立地点合同，请先从模板采用或补齐合同", 400);
        }
        ObjectNode root = parse(raw);
        String key = slotKey == null ? "" : slotKey.trim();
        if (!SLOT_KEY.matcher(key).matches()) throw new ServiceException("地点槽标识不合法", 400);
        if (!containsSlot(root, key)) throw new ServiceException("地点槽不存在", 404);

        ArrayNode bindings = (ArrayNode) root.path("locationBindings");
        ObjectNode binding = null;
        int index = -1;
        for (int i = 0; i < bindings.size(); i++) {
            if (key.equals(bindings.get(i).path("slotKey").asText())) {
                binding = (ObjectNode) bindings.get(i);
                index = i;
                break;
            }
        }
        if (binding == null) {
            binding = JSON.createObjectNode();
            binding.put("slotKey", key);
            binding.putNull("nodeId");
            binding.put("bindingRevision", 0);
            binding.put("verificationStatus", STATUS_UNBOUND);
            binding.putNull("confirmedAt");
            binding.putNull("confirmedBy");
            binding.set("approvedFallbackNodeIds", JSON.createArrayNode());
            binding.putNull("openingHours");
            binding.putNull("observationNote");
            binding.set("referenceMediaIds", JSON.createArrayNode());
            binding.putNull("previousBinding");
        }
        int currentRevision = binding.path("bindingRevision").asInt(0);
        if (expectedRevision != null && expectedRevision != currentRevision) {
            throw new ServiceException("地点绑定已被其他操作更新，请刷新后重试", 409);
        }
        String previousStatus = binding.path("verificationStatus").asText(STATUS_UNBOUND);
        Long previousNodeId = parseId(binding.path("nodeId"), null);
        String previousCity = binding.path("cityCode").asText(null);
        String previousConfirmedAt = binding.path("confirmedAt").asText(null);

        if (nodeId == null) {
            binding.putNull("nodeId");
            binding.put("bindingRevision", currentRevision + 1);
            binding.put("verificationStatus", STATUS_UNBOUND);
            binding.putNull("confirmedAt");
            binding.putNull("confirmedBy");
            binding.set("approvedFallbackNodeIds", JSON.createArrayNode());
            binding.putNull("openingHours");
            binding.putNull("observationNote");
            binding.set("referenceMediaIds", JSON.createArrayNode());
            if (previousNodeId != null) binding.set("previousBinding",
                    previous(previousCity, previousNodeId, previousStatus, previousConfirmedAt));
        } else {
            String cityCode = cityCode(topicCityCode, "主题城市");
            if (cityCode != null && previousCity != null && !cityCode.equals(previousCity)) {
                throw new ServiceException(ISSUE_CITY_MISMATCH + ": 地点绑定城市与主题城市不一致", 409);
            }
            binding.put("nodeId", String.valueOf(nodeId));
            binding.put("bindingRevision", currentRevision + 1);
            binding.put("verificationStatus", STATUS_BOUND_UNVERIFIED);
            if (cityCode != null) binding.put("cityCode", cityCode);
            String confirmedAt = DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, now);
            binding.put("confirmedAt", confirmedAt);
            binding.put("confirmedBy", confirmedBy == null ? null : String.valueOf(confirmedBy));
            ArrayNode fallbacks = JSON.createArrayNode();
            Set<Long> seen = new LinkedHashSet<Long>();
            if (fallbackNodeIds != null) {
                for (Long fallback : fallbackNodeIds) {
                    if (fallback == null || fallback <= 0L || fallback.equals(nodeId) || !seen.add(fallback)) continue;
                    fallbacks.add(String.valueOf(fallback));
                }
            }
            binding.set("approvedFallbackNodeIds", fallbacks);
            if (StringUtils.isBlank(openingHours)) binding.putNull("openingHours");
            else binding.put("openingHours", truncate(openingHours.trim(), 120));
            if (StringUtils.isBlank(observationNote)) binding.putNull("observationNote");
            else binding.put("observationNote", truncate(observationNote.trim(), MAX_NOTE));
            ArrayNode media = JSON.createArrayNode();
            if (mediaIds != null) {
                Set<String> seenMedia = new LinkedHashSet<String>();
                for (String mediaId : mediaIds) {
                    if (StringUtils.isBlank(mediaId)) continue;
                    String value = mediaId.trim();
                    if (value.length() > 64) throw new ServiceException("媒体引用编号过长", 400);
                    if (seenMedia.add(value)) media.add(value);
                    if (media.size() > MAX_MEDIA_IDS) throw new ServiceException("媒体引用过多", 400);
                }
            }
            binding.set("referenceMediaIds", media);
            if (previousNodeId != null && !previousNodeId.equals(nodeId)) {
                binding.set("previousBinding",
                        previous(previousCity, previousNodeId, previousStatus, previousConfirmedAt));
            }
        }
        if (index >= 0) bindings.set(index, binding); else bindings.add(binding);
        return canonical(root.toString());
    }

    private static boolean containsSlot(ObjectNode root, String slotKey) {
        for (JsonNode slot : root.path("locationSlots")) {
            if (slotKey.equals(slot.path("slotKey").asText())) return true;
        }
        return false;
    }

    /**
     * 只读：某个真实节点在地点合同里<b>已经批准</b>的 fallback 节点编号。
     *
     * <p>冻结时复制进 routeStations，到达拒绝响应据此告诉端上「有获批替代」。
     * 只认合同里已写入的 approvedFallbackNodeIds；作者新声明的 fallbackMode 不在这里授权。
     * 合同脏数据/编号不合法按「没有获批替代」处理，不让旧主题因为合同格式问题冻结失败。
     */
    public static List<String> approvedFallbacksForNode(String raw, Long nodeId, Set<Long> knownNodeIds) {
        if (StringUtils.isBlank(raw) || nodeId == null || nodeId.longValue() <= 0L) return Collections.emptyList();
        final JsonNode root;
        try {
            root = parse(raw);
        } catch (IllegalArgumentException unusableContract) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode binding : root.path("locationBindings")) {
            if (binding == null || !binding.isObject()) continue;
            Long bound;
            try {
                bound = parseId(binding.path("nodeId"), "nodeId");
            } catch (IllegalArgumentException invalidId) {
                continue;
            }
            if (bound == null || !bound.equals(nodeId)) continue;
            for (JsonNode rawFallback : binding.path("approvedFallbackNodeIds")) {
                Long fallback;
                try {
                    fallback = parseId(rawFallback, "approvedFallbackNodeIds");
                } catch (IllegalArgumentException invalidId) {
                    continue;
                }
                if (fallback == null || fallback.longValue() <= 0L || fallback.equals(nodeId)) continue;
                if (knownNodeIds != null && !knownNodeIds.contains(fallback)) continue;
                String value = String.valueOf(fallback);
                if (seen.add(value)) out.add(value);
            }
        }
        return out;
    }

    private static ObjectNode previous(String city, Long nodeId, String status, String confirmedAt) {
        ObjectNode previous = JSON.createObjectNode();
        if (city == null) previous.putNull("cityCode"); else previous.put("cityCode", city);
        previous.put("nodeId", String.valueOf(nodeId));
        previous.put("verificationStatus", status);
        if (confirmedAt == null) previous.putNull("confirmedAt"); else previous.put("confirmedAt", confirmedAt);
        return previous;
    }

    // ---------------------------------------------------------------------
    // 读：缺口列表与摘要（B3/B4 复用）
    // ---------------------------------------------------------------------

    /**
     * 发布缺口列表。只有 {@code NEW_CITY} 采用才可能给 {@code error}；
     * {@code LEGACY}/{@code SAME_CITY} 的要求缺口一律 {@code warn}，不得倒着拦存量副本。
     */
    public static List<Map<String, Object>> bindingIssues(String raw, String topicCityCode) {
        List<Map<String, Object>> issues = new ArrayList<Map<String, Object>>();
        if (StringUtils.isBlank(raw)) return issues;
        ObjectNode root;
        try {
            root = parse(raw);
        } catch (IllegalArgumentException invalid) {
            Map<String, Object> issue = new LinkedHashMap<String, Object>();
            issue.put("slotKey", null);
            issue.put("code", ISSUE_INVALID);
            issue.put("level", LEVEL_WARN);
            issue.put("required", true);
            issue.put("message", "地点合同损坏，需重新建立");
            issues.add(issue);
            return issues;
        }
        boolean newCity = INTENT_NEW_CITY.equals(root.path("adoption").path("bindingIntent").asText(""));
        Map<String, JsonNode> bindingBySlot = new LinkedHashMap<String, JsonNode>();
        for (JsonNode binding : root.path("locationBindings")) {
            bindingBySlot.put(binding.path("slotKey").asText(), binding);
        }
        for (JsonNode slot : root.path("locationSlots")) {
            String slotKey = slot.path("slotKey").asText();
            boolean required = slot.path("required").asBoolean(false);
            JsonNode binding = bindingBySlot.get(slotKey);
            String status = binding == null ? STATUS_UNBOUND : binding.path("verificationStatus").asText(STATUS_UNBOUND);
            String code = null;
            String message = null;
            boolean blocking = false;
            if (STATUS_REBIND_REQUIRED.equals(status)) {
                code = ISSUE_REBIND;
                message = "跨城采用后需在目标城市重新绑定";
                blocking = required && newCity;
            } else if (STATUS_UNBOUND.equals(status) && required) {
                code = ISSUE_UNBOUND;
                message = "必填地点尚未绑定";
                blocking = newCity;
            } else if (STATUS_LEGACY_UNVERIFIED.equals(status) && required) {
                code = ISSUE_LEGACY;
                message = "从旧模板沿用，尚未重新确认（仅提示）";
                blocking = false;
            }
            String bindingCity = binding == null ? null : binding.path("cityCode").asText(null);
            if (code == null && required && binding != null && topicCityCode != null
                    && bindingCity != null && !topicCityCode.equals(bindingCity)) {
                code = ISSUE_CITY_MISMATCH;
                message = "地点绑定城市与主题城市不一致";
                blocking = newCity;
            }
            if (code != null) {
                Map<String, Object> issue = new LinkedHashMap<String, Object>();
                issue.put("slotKey", slotKey);
                issue.put("code", code);
                issue.put("level", blocking ? LEVEL_ERROR : LEVEL_WARN);
                issue.put("required", required);
                issue.put("message", message);
                issues.add(issue);
            }
        }
        return issues;
    }

    /** {@code /use} 响应增量与 {@code /binding-status} 共用；不含作者私有内容。 */
    public static Map<String, Object> summary(String raw) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("sourceTemplateTopicId", null);
        summary.put("sourceConfigVersion", null);
        summary.put("sourceContentRevisionId", null);
        summary.put("bindingIntent", INTENT_LEGACY);
        summary.put("targetCityCode", null);
        summary.put("pendingRebindSlotKeys", new ArrayList<String>());
        summary.put("bindingIssues", new ArrayList<Map<String, Object>>());
        if (StringUtils.isBlank(raw)) return summary;
        ObjectNode root;
        try {
            root = parse(raw);
        } catch (IllegalArgumentException invalid) {
            summary.put("bindingIssues", bindingIssues(raw, null));
            return summary;
        }
        JsonNode adoption = root.path("adoption");
        if (!adoption.isMissingNode()) {
            summary.put("sourceTemplateTopicId", adoption.path("sourceTemplateTopicId").asText(null));
            summary.put("sourceContentRevisionId", adoption.path("sourceContentRevisionId").asText(null));
            if (adoption.hasNonNull("sourceConfigVersion")) {
                summary.put("sourceConfigVersion", adoption.path("sourceConfigVersion").asLong());
            }
            summary.put("bindingIntent", adoption.path("bindingIntent").asText(INTENT_LEGACY));
            summary.put("targetCityCode", adoption.path("targetCityCode").asText(null));
        }
        List<String> pending = new ArrayList<String>();
        for (JsonNode binding : root.path("locationBindings")) {
            if (STATUS_REBIND_REQUIRED.equals(binding.path("verificationStatus").asText())) {
                pending.add(binding.path("slotKey").asText());
            }
        }
        summary.put("pendingRebindSlotKeys", pending);
        return summary;
    }

    /** 作者只读视图：解析后的合同（规范化），不含运行期答案。 */
    public static ObjectNode view(String raw) {
        return StringUtils.isBlank(raw) ? empty() : parse(raw);
    }

    // ---------------------------------------------------------------------
    // normalization
    // ---------------------------------------------------------------------

    private static ArrayNode normalizeSlots(JsonNode raw) {
        ArrayNode slots = JSON.createArrayNode();
        if (raw.isMissingNode() || raw.isNull()) return slots;
        if (!raw.isArray()) throw new IllegalArgumentException("locationSlots 必须是数组");
        if (raw.size() > MAX_SLOTS) throw new IllegalArgumentException("地点槽过多");
        Set<String> keys = new HashSet<String>();
        for (JsonNode item : raw) {
            ObjectNode slot = object(item, "地点槽必须是对象");
            rejectCityFacts(slot, "地点槽");
            String slotKey = text(slot, "slotKey", 64, true);
            if (!SLOT_KEY.matcher(slotKey).matches()) throw new IllegalArgumentException("slotKey 不合法");
            if (!keys.add(slotKey)) throw new IllegalArgumentException("slotKey 重复:" + slotKey);
            String purpose = text(slot, "purpose", 16, true);
            if (!PURPOSES.contains(purpose)) throw new IllegalArgumentException("purpose 不支持:" + purpose);
            String policy = text(slot, "alternativePolicy", 24, false);
            if (policy == null) policy = "NONE";
            if (!ALTERNATIVE_POLICIES.contains(policy)) {
                throw new IllegalArgumentException("alternativePolicy 不支持:" + policy);
            }
            ObjectNode out = JSON.createObjectNode();
            out.put("slotKey", slotKey);
            out.put("title", text(slot, "title", 80, false));
            out.put("purpose", purpose);
            out.put("required", bool(slot, "required", false));
            out.put("observationTarget", text(slot, "observationTarget", MAX_TEXT, false));
            out.put("observationAction", text(slot, "observationAction", MAX_TEXT, false));
            out.put("accessRequirements", text(slot, "accessRequirements", MAX_TEXT, false));
            out.put("hostRequired", bool(slot, "hostRequired", false));
            out.put("propRequirements", text(slot, "propRequirements", MAX_TEXT, false));
            out.put("alternativePolicy", policy);
            out.put("derived", bool(slot, "derived", false));
            Long sourceNodeId = parseId(slot.path("sourceNodeId"), "sourceNodeId");
            if (sourceNodeId == null) out.putNull("sourceNodeId");
            else out.put("sourceNodeId", String.valueOf(sourceNodeId));
            carryUnknown(slot, out, KNOWN_SLOT_KEYS);
            slots.add(out);
        }
        return slots;
    }

    private static ArrayNode normalizeBindings(JsonNode raw, JsonNode slots) {
        ArrayNode bindings = JSON.createArrayNode();
        if (raw.isMissingNode() || raw.isNull()) return bindings;
        if (!raw.isArray()) throw new IllegalArgumentException("locationBindings 必须是数组");
        if (raw.size() > MAX_BINDINGS) throw new IllegalArgumentException("地点绑定过多");
        Set<String> slotKeys = new HashSet<String>();
        for (JsonNode slot : slots) slotKeys.add(slot.path("slotKey").asText());
        Set<String> bound = new HashSet<String>();
        for (JsonNode item : raw) {
            ObjectNode binding = object(item, "地点绑定必须是对象");
            rejectCityFacts(binding, "地点绑定");
            String slotKey = text(binding, "slotKey", 64, true);
            if (!slotKeys.contains(slotKey)) throw new IllegalArgumentException("绑定引用了不存在的地点槽:" + slotKey);
            if (!bound.add(slotKey)) throw new IllegalArgumentException("地点槽重复绑定:" + slotKey);
            String status = text(binding, "verificationStatus", 24, true);
            if (!STATUSES.contains(status)) throw new IllegalArgumentException("verificationStatus 不支持:" + status);
            Long nodeId = parseId(binding.path("nodeId"), "nodeId");
            if ((STATUS_BOUND_UNVERIFIED.equals(status) || STATUS_LEGACY_UNVERIFIED.equals(status))
                    && nodeId == null) {
                throw new IllegalArgumentException("已绑定状态必须带 nodeId");
            }
            if ((STATUS_UNBOUND.equals(status) || STATUS_REBIND_REQUIRED.equals(status)) && nodeId != null) {
                throw new IllegalArgumentException("未绑定/待重绑状态不得带 nodeId");
            }
            ObjectNode out = JSON.createObjectNode();
            out.put("slotKey", slotKey);
            if (nodeId == null) out.putNull("nodeId"); else out.put("nodeId", String.valueOf(nodeId));
            String city = cityCode(binding.path("cityCode").asText(null), "cityCode");
            if (city == null) out.putNull("cityCode"); else out.put("cityCode", city);
            int revision = binding.path("bindingRevision").asInt(0);
            if (revision < 0 || revision > 1_000_000) throw new IllegalArgumentException("bindingRevision 越界");
            out.put("bindingRevision", nodeId == null ? 0 : revision);
            out.put("verificationStatus", status);
            out.put("confirmedAt", text(binding, "confirmedAt", 19, false));
            out.put("confirmedBy", text(binding, "confirmedBy", 64, false));
            ArrayNode fallbacks = JSON.createArrayNode();
            Set<Long> seen = new LinkedHashSet<Long>();
            for (JsonNode rawFallback : binding.path("approvedFallbackNodeIds")) {
                Long id = parseId(rawFallback, "approvedFallbackNodeIds");
                if (id != null && seen.add(id)) fallbacks.add(String.valueOf(id));
            }
            out.set("approvedFallbackNodeIds", fallbacks);
            out.put("openingHours", text(binding, "openingHours", 120, false));
            out.put("observationNote", text(binding, "observationNote", MAX_NOTE, false));
            ArrayNode media = JSON.createArrayNode();
            for (JsonNode rawMedia : binding.path("referenceMediaIds")) {
                if (rawMedia == null || !rawMedia.isTextual()) {
                    throw new IllegalArgumentException("referenceMediaIds 必须是字符串数组");
                }
                String value = rawMedia.asText().trim();
                if (value.isEmpty()) continue;
                if (value.length() > 64) throw new IllegalArgumentException("referenceMediaIds 过长");
                media.add(value);
                if (media.size() > MAX_MEDIA_IDS) throw new IllegalArgumentException("referenceMediaIds 过多");
            }
            out.set("referenceMediaIds", media);
            if (binding.hasNonNull("previousBinding")) {
                out.set("previousBinding", normalizePreviousBinding(binding.path("previousBinding")));
            } else {
                out.putNull("previousBinding");
            }
            carryUnknown(binding, out, KNOWN_BINDING_KEYS);
            bindings.add(out);
        }
        return bindings;
    }

    private static ObjectNode normalizePreviousBinding(JsonNode raw) {
        ObjectNode previous = object(raw, "previousBinding 必须是对象");
        ObjectNode out = JSON.createObjectNode();
        String city = cityCode(previous.path("cityCode").asText(null), "previousBinding.cityCode");
        if (city == null) out.putNull("cityCode"); else out.put("cityCode", city);
        Long nodeId = parseId(previous.path("nodeId"), "previousBinding.nodeId");
        if (nodeId == null) out.putNull("nodeId"); else out.put("nodeId", String.valueOf(nodeId));
        String status = text(previous, "verificationStatus", 24, false);
        if (status != null && !STATUSES.contains(status)) {
            throw new IllegalArgumentException("previousBinding.verificationStatus 不支持:" + status);
        }
        out.put("verificationStatus", status);
        out.put("confirmedAt", text(previous, "confirmedAt", 19, false));
        carryUnknown(previous, out, KNOWN_PREVIOUS_BINDING_KEYS);
        return out;
    }

    private static ObjectNode normalizeAdoption(JsonNode raw) {
        ObjectNode adoption = object(raw, "adoption 必须是对象");
        ObjectNode out = JSON.createObjectNode();
        Long sourceId = parseId(adoption.path("sourceTemplateTopicId"), "sourceTemplateTopicId");
        if (sourceId == null) throw new IllegalArgumentException("adoption.sourceTemplateTopicId 缺失");
        out.put("sourceTemplateTopicId", String.valueOf(sourceId));
        JsonNode version = adoption.path("sourceConfigVersion");
        if (version.isMissingNode() || version.isNull()) out.putNull("sourceConfigVersion");
        else if (version.isIntegralNumber() && version.asLong() >= 0) out.put("sourceConfigVersion", version.asLong());
        else throw new IllegalArgumentException("sourceConfigVersion 必须是非负整数");
        Long sourceRevisionId = parseId(adoption.path("sourceContentRevisionId"), "sourceContentRevisionId");
        if (sourceRevisionId == null) out.putNull("sourceContentRevisionId");
        else out.put("sourceContentRevisionId", String.valueOf(sourceRevisionId));
        String adoptedAt = text(adoption, "adoptedAt", 19, true);
        if (DateUtils.parseDate(adoptedAt) == null) throw new IllegalArgumentException("adoptedAt 时间格式不合法");
        out.put("adoptedAt", adoptedAt);
        String intent = text(adoption, "bindingIntent", 16, true);
        if (!INTENTS.contains(intent)) throw new IllegalArgumentException("adoption.bindingIntent 不支持");
        out.put("bindingIntent", intent);
        String targetCity = cityCode(adoption.path("targetCityCode").asText(null), "adoption.targetCityCode");
        if (targetCity == null) out.putNull("targetCityCode"); else out.put("targetCityCode", targetCity);
        out.put("legacy", bool(adoption, "legacy", INTENT_LEGACY.equals(intent)));
        carryUnknown(adoption, out, KNOWN_ADOPTION_KEYS);
        return out;
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static void rejectCityFacts(ObjectNode node, String where) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (CITY_FACT_KEYS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(where + " 不得包含城市事实字段:" + name
                        + "（坐标/地址唯一真源是实例节点）");
            }
        }
    }

    /** 未知字段原样带回：后续消费者加键时不被本体丢掉。 */
    private static void carryUnknown(ObjectNode source, ObjectNode target, Set<String> known) {
        Iterator<String> names = source.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!known.contains(name)) target.set(name, source.get(name));
        }
    }

    private static ObjectNode object(JsonNode node, String message) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(message);
        return (ObjectNode) node;
    }

    private static String text(ObjectNode node, String field, int max, boolean required) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            if (required) throw new IllegalArgumentException(field + " 缺失");
            return null;
        }
        if (!value.isTextual()) throw new IllegalArgumentException(field + " 必须是字符串");
        String text = value.asText();
        if (required && text.trim().isEmpty()) throw new IllegalArgumentException(field + " 不能为空");
        if (text.length() > max) throw new IllegalArgumentException(field + " 过长");
        return text;
    }

    private static boolean bool(ObjectNode node, String field, boolean defaultValue) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return defaultValue;
        if (!value.isBoolean()) throw new IllegalArgumentException(field + " 必须是布尔值");
        return value.asBoolean();
    }

    private static Long parseId(JsonNode value, String field) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        String text = value.isTextual() ? value.asText() : value.isIntegralNumber() ? value.asText() : null;
        if (text == null || !DECIMAL_ID.matcher(text).matches()) {
            throw new IllegalArgumentException((field == null ? "编号" : field) + " 必须是正整数字符串");
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException((field == null ? "编号" : field) + " 越界");
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }
}

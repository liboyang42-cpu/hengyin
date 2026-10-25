package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.AnalyticsBatchDTO;
import com.chengyinhub.business.domain.dto.AnalyticsEventDTO;
import com.chengyinhub.business.service.IAnalyticsEventService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.regex.Pattern;

/**
 * P3 行为事件采集接口。
 */
@RestController
@Tag(name = "P3行为事件")
@RequestMapping("/api/analytics")
public class ApiAnalyticsController extends BaseController {

    private static final int MAX_BATCH_SIZE = 50;
    private static final int MAX_PROPERTIES_LENGTH = 4000;
    private static final long MAX_EVENT_AGE_MILLIS = 14L * 24 * 60 * 60 * 1000;
    private static final long MAX_FUTURE_SKEW_MILLIS = 5L * 60 * 1000;
    private static final Pattern BIZ_TYPE_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> SENSITIVE_PROPERTY_KEYS = new HashSet<>(Arrays.asList(
            "phone", "mobile", "bank", "card", "identity", "idcard", "realname", "name", "email",
            "address", "location", "latitude", "longitude", "openid", "unionid", "session", "token",
            "keyword", "content", "comment", "message", "text", "title", "description"
    ));
    private static final Set<String> ALLOWED_EVENTS = new HashSet<>(Arrays.asList(
            "search_submit",
            "content_view",
            "content_like",
            "content_favorite",
            "content_share",
            "community_post_impression",
            "community_post_open",
            "community_post_qualified_view",
            "signup_start",
            "signup_submit",
            "payment_success",
            "coupon_receive",
            "coupon_verify",
            "topic_publish_success",
            "activity_publish_success",
            "template_reuse",
            "mission_complete",
            "withdrawal_submit",
            "solo_ticket_purchase",
            "play_arrive_success",
            "play_arrive_fallback",
            "node_abandon",
            // 2026-09-09:途中彩蛋整条下线(接口已删、小程序与 App 的入口都撤了),
            //   彩蛋曝光事件一并从白名单摘掉 —— 留一个再也不会有人发的事件名,
            //   只会让后来的人以为这条链路还活着。
            // ⚠️ 这段注释里不许再出现被双引号包起来的字面量:两端的白名单合同
            //   都是拿正则扫这一整段里的双引号字面量,写进注释等于没删,
            //   还会凭空多出一个事件名把合同判红。
            "drop_shown",
            "citymap_view",
            "citymap_share",
            "track_record_start",
            "track_record_finish",
            "track_to_topic",
            "xp_alloc_done",
            "become_leader_cta",
            "play_rec_swap",
            "play_rec_accept",
            "finish_route_recommendation_shown",
            "finish_route_recommendation_click",
            "roam_start",
            "roam_poi_found",
            "roam_finish",
            "roam_discover_reward_click",
            "roam_exploreday_reco_shown",
            "roam_exploreday_reco_click",
            "roam_share_channel",
            // U-171 分享回执 / P1-021 章节块流回落:properties 只放渠道码、原因码。
            "roam_share_complete",
            "roam_share_fail",
            "square_share_complete",
            "square_share_fail",
            "chapter_blocks_parse_failed",
            // 无形陪伴 V1：仅表面展示/关闭，properties 不得带话术、地点或轨迹。
            "roam_route_story_shown",
            "roam_poi_meaning_shown",
            "roam_footprint_hint_shown",
            "roam_footprint_hint_dismissed",
            // 漫游 NPC Event 轨(补充方案 §14.1):properties 只放 eventType/lineLen 等非敏感键,
            // 不放话术原文(FORBIDDEN_KEYS 已拦 text/content/message 等键名,但原文更不该发)。
            "npc_profile_loaded",
            "npc_bubble_impression",
            "npc_chat_open",
            "npc_chat_submit",
            "npc_chat_complete",
            "npc_chat_cancel"
    ));
    private static final Set<String> ROAM_AI_SURFACE_EVENTS = new HashSet<>(Arrays.asList(
            "roam_route_story_shown",
            "roam_poi_meaning_shown",
            "roam_footprint_hint_shown",
            "roam_footprint_hint_dismissed"
    ));
    @Autowired
    private IAnalyticsEventService analyticsEventService;

    @Operation(summary = "批量上报行为事件")
    @PostMapping("/events/batch")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    public AjaxResult recordBatch(@RequestBody AnalyticsBatchDTO request) {
        String error = validateBatch(request);
        if (error != null) {
            return error(error);
        }

        Long memberId = null;
        try {
            memberId = getAppUserId();
        } catch (Exception ignored) {
        }
        if (memberId == null) {
            return error("行为采集需要登录");
        }

        String expectedActorScope = "member:" + memberId;
        Map<Long, Long> visiblePostAuthors = new HashMap<>();
        Set<Long> invisiblePostIds = new HashSet<>();
        for (AnalyticsEventDTO item : request.getEvents()) {
            boolean namedCommunityEvent = item.getEventName().startsWith("community_post_");
            if (namedCommunityEvent && !"community_post".equals(item.getBizType())) {
                return error("社区事件业务类型必须为 community_post");
            }
            boolean communityEvent = namedCommunityEvent
                    || "community_post".equals(item.getBizType());
            if ((communityEvent || StringUtils.isNotBlank(item.getActorScope()))
                    && !expectedActorScope.equals(item.getActorScope())) {
                return error("登录状态已变化，请丢弃旧埋点");
            }
            if (communityEvent && StringUtils.isBlank(item.getIdempotencyKey())) {
                return error("社区埋点必须携带幂等键");
            }
            if (communityEvent && (item.getBizId() == null || item.getBizId() <= 0)) {
                return error("社区埋点必须关联有效帖文");
            }
            if (communityEvent
                    && !visiblePostAuthors.containsKey(item.getBizId())
                    && !invisiblePostIds.contains(item.getBizId())) {
                Long authorId = analyticsEventService.visibleCommunityPostAuthor(memberId, item.getBizId());
                if (authorId == null) {
                    invisiblePostIds.add(item.getBizId());
                } else {
                    visiblePostAuthors.put(item.getBizId(), authorId);
                }
            }
            if (communityEvent && invisiblePostIds.contains(item.getBizId())) {
                return error("帖文不存在或不可见");
            }
            if ("community_post_qualified_view".equals(item.getEventName())) {
                if (memberId.equals(visiblePostAuthors.get(item.getBizId()))) {
                    return error("帖文不可见或不能计入合格阅读");
                }
            }
        }

        int accepted;
        try {
            accepted = analyticsEventService.recordBatch(memberId, request);
        } catch (IllegalArgumentException conflict) {
            if ("成交埋点必须关联原报名事实".equals(conflict.getMessage())) {
                return error("成交埋点必须关联原报名事实");
            }
            return error("事件幂等键已用于不同内容或未确认落库");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("accepted", accepted);
        return success(data);
    }

    private String validateBatch(AnalyticsBatchDTO request) {
        if (request == null || request.getEvents() == null || request.getEvents().isEmpty()) {
            return "事件不能为空";
        }
        if (request.getEvents().size() > MAX_BATCH_SIZE) {
            return "单次最多上报50条事件";
        }
        for (AnalyticsEventDTO item : request.getEvents()) {
            if (item == null || StringUtils.isBlank(item.getEventName())) {
                return "事件名称不能为空";
            }
            if (!ALLOWED_EVENTS.contains(item.getEventName())) {
                return "不支持的埋点事件：" + item.getEventName();
            }
            if (StringUtils.isBlank(item.getIdempotencyKey())) {
                return item.getEventName().startsWith("community_post_")
                        ? "社区埋点必须携带幂等键" : "埋点必须携带幂等键";
            }
            if (item.getBizType() != null) {
                String normalizedBizType = item.getBizType().trim().toLowerCase(Locale.ROOT);
                if (normalizedBizType.isEmpty()) {
                    item.setBizType(null);
                } else if (!BIZ_TYPE_PATTERN.matcher(normalizedBizType).matches()) {
                    return "业务类型格式不合法";
                } else {
                    item.setBizType(normalizedBizType);
                }
            }
            if (item.getPropertiesJson() != null && item.getPropertiesJson().length() > MAX_PROPERTIES_LENGTH) {
                return "事件属性过长";
            }
            if (tooLong(item.getAnonymousId(), 80)
                    || tooLong(item.getSessionId(), 80)
                    || tooLong(item.getSource(), 80)
                    || tooLong(item.getPagePath(), 255)
                    || tooLong(item.getBizType(), 40)
                    || tooLong(item.getCityCode(), 40)
                    || tooLong(item.getIdempotencyKey(), 120)
                    || tooLong(item.getActorScope(), 96)) {
                return "事件字段超过长度限制";
            }
            if (containsSensitiveProperties(item.getPropertiesJson())) {
                return "事件属性不得包含敏感个人信息";
            }
            if (ROAM_AI_SURFACE_EVENTS.contains(item.getEventName()) && !isMinimalRoamAiSurfaceEvent(item)) {
                return "漫游 AI 表面埋点不得关联业务对象或携带属性";
            }
            if (item.getOccurredAt() != null) {
                long now = System.currentTimeMillis();
                long occurredAt = item.getOccurredAt().getTime();
                if (occurredAt < now - MAX_EVENT_AGE_MILLIS
                        || occurredAt > now + MAX_FUTURE_SKEW_MILLIS) {
                    return "事件时间超出允许窗口";
                }
            }
        }
        return null;
    }

    private boolean tooLong(String value, int maxLength) {
        return value != null && value.length() > maxLength;
    }

    private boolean containsSensitiveProperties(String propertiesJson) {
        if (StringUtils.isBlank(propertiesJson)) {
            return false;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(propertiesJson);
            return root == null || !root.isObject() || containsSensitiveNode(root);
        } catch (Exception e) {
            return true;
        }
    }

    private boolean containsSensitiveNode(JsonNode node) {
        Iterator<Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Entry<String, JsonNode> field = fields.next();
            String normalizedKey = field.getKey().replaceAll("[^A-Za-z0-9]", "").toLowerCase();
            for (String sensitiveKey : SENSITIVE_PROPERTY_KEYS) {
                if (normalizedKey.contains(sensitiveKey)) {
                    return true;
                }
            }
            JsonNode value = field.getValue();
            if (value.isContainerNode()) {
                return true;
            }
            if (value.isTextual() && isSensitiveValue(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean isSensitiveValue(String value) {
        return value.matches("^1[3-9]\\d{9}$")
                || value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                || value.matches("^\\d{15,19}$");
    }

    /** 四个 V1 表面只记“发生过”，避免 session/POI 可被反查成路线或地点。 */
    private boolean isMinimalRoamAiSurfaceEvent(AnalyticsEventDTO item) {
        Long bizId = item.getBizId();
        return (bizId == null || bizId == 0L) && hasEmptyProperties(item.getPropertiesJson());
    }

    private boolean hasEmptyProperties(String propertiesJson) {
        if (StringUtils.isBlank(propertiesJson)) {
            return true;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(propertiesJson);
            return root != null && root.isObject() && root.size() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}

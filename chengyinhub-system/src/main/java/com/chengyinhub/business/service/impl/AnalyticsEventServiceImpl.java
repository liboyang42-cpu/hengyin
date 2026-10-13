package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BizEventLog;
import com.chengyinhub.business.domain.dto.AnalyticsBatchDTO;
import com.chengyinhub.business.domain.dto.AnalyticsEventDTO;
import com.chengyinhub.business.mapper.BizEventLogMapper;
import com.chengyinhub.business.mapper.BizFunnelDailyMapper;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.IAnalyticsEventService;
import com.chengyinhub.business.service.IRecommendationService;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * P3 行为事件服务实现。
 */
@Service
public class AnalyticsEventServiceImpl implements IAnalyticsEventService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId ANALYTICS_ZONE = ZoneId.of("Asia/Shanghai");
    @Autowired
    private BizEventLogMapper bizEventLogMapper;

    @Autowired
    private BizFunnelDailyMapper bizFunnelDailyMapper;

    @Autowired
    private IRecommendationService recommendationService;

    @Autowired
    private CommunityPostMapper communityPostMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recordBatch(Long memberId, AnalyticsBatchDTO request) {
        if (request == null || request.getEvents() == null || request.getEvents().isEmpty()) {
            return 0;
        }
        int accepted = 0;
        for (AnalyticsEventDTO item : request.getEvents()) {
            Date occurredAt = item.getOccurredAt() == null ? new Date() : item.getOccurredAt();
            BizEventLog event = new BizEventLog();
            event.setEventName(item.getEventName());
            event.setMemberId(memberId);
            // 成员归因使用认证态 memberId，不持久化客户端可控的 openid/anonymousId。
            event.setAnonymousId("");
            event.setSessionId(item.getSessionId());
            event.setSource(item.getSource());
            event.setPagePath(item.getPagePath());
            event.setBizType(item.getBizType());
            event.setBizId(item.getBizId());
            event.setCityCode(item.getCityCode());
            event.setPropertiesJson(item.getPropertiesJson());
            if ("community_post_qualified_view".equals(item.getEventName())) {
                // 原始事件按 user-post-day 去重；跨日消费保留，试点窗口分母再按 user-post 去重。
                String statDate = occurredAt.toInstant().atZone(ANALYTICS_ZONE)
                        .toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
                event.setIdempotencyKey("community-post-qualified-" + item.getBizId() + "-" + statDate);
            } else {
                event.setIdempotencyKey(item.getIdempotencyKey());
            }
            event.setActorScope(memberId == null ? "ANONYMOUS" : "MEMBER:" + memberId);
            event.setOccurredAt(occurredAt);
            if (isRegistrationOutcome(item.getEventName())) {
                bindRegistrationFact(memberId, event);
            }
            event.setCreateTime(DateUtils.getNowDate());
            int inserted = bizEventLogMapper.insertBizEventLog(event);
            if (inserted == 1) {
                recommendationService.updateProfileFromEvent(event);
            }
            if (inserted != 1) {
                BizEventLog existing = bizEventLogMapper.selectIdempotentEventForUpdate(event);
                if (existing == null || !sameEvent(existing, event, item.getOccurredAt() == null)) {
                    throw new IllegalArgumentException("事件幂等键已用于不同内容或未确认落库");
                }
            }
            // 持久化重复事件同样得到确认，但不重复画像副作用。
            accepted++;
        }
        return accepted;
    }

    private boolean isRegistrationOutcome(String eventName) {
        return "signup_submit".equals(eventName) || "payment_success".equals(eventName);
    }

    private void bindRegistrationFact(Long memberId, BizEventLog event) {
        Long registrationId;
        try {
            JsonNode properties = JSON.readTree(event.getPropertiesJson() == null ? "{}" : event.getPropertiesJson());
            JsonNode rawId = properties == null ? null : properties.get("registrationId");
            String value = rawId == null ? "" : rawId.asText();
            if (!value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            registrationId = Long.valueOf(value);
        } catch (Exception invalidId) {
            throw new IllegalArgumentException("成交埋点必须关联原报名事实");
        }
        BizEventLog fact = bizEventLogMapper.selectRegistrationEventFact(memberId, registrationId, event.getEventName());
        if (fact == null || fact.getOccurredAt() == null
                || !Objects.equals(fact.getBizId(), event.getBizId())
                || !Objects.equals(fact.getBizType(), event.getBizType())) {
            throw new IllegalArgumentException("成交埋点必须关联原报名事实");
        }
        // 客户端成功观察不能造订单或金额；原报名身份防止换幂等键重复计权。
        event.setIdempotencyKey("registration-" + registrationId);
        event.setOccurredAt(fact.getOccurredAt());
        event.setPropertiesJson("{\"registrationId\":" + registrationId + "}");
    }

    private boolean sameEvent(BizEventLog existing, BizEventLog requested, boolean serverAssignedTime) {
        if (!Objects.equals(existing.getMemberId(), requested.getMemberId())
                || !Objects.equals(existing.getEventName(), requested.getEventName())
                || !Objects.equals(existing.getBizId(), requested.getBizId())
                || !Objects.equals(emptyToNull(existing.getBizType()), emptyToNull(requested.getBizType()))) {
            return false;
        }
        // 合格阅读的已确定身份为 user/post/day，不以另一会话或阅读时刻重复计数。
        if ("community_post_qualified_view".equals(requested.getEventName())
                || isRegistrationOutcome(requested.getEventName())) return true;
        return Objects.equals(emptyToNull(existing.getSessionId()), emptyToNull(requested.getSessionId()))
                && Objects.equals(emptyToNull(existing.getSource()), emptyToNull(requested.getSource()))
                && Objects.equals(emptyToNull(existing.getPagePath()), emptyToNull(requested.getPagePath()))
                && Objects.equals(emptyToNull(existing.getCityCode()), emptyToNull(requested.getCityCode()))
                && sameProperties(existing.getPropertiesJson(), requested.getPropertiesJson())
                && (serverAssignedTime || (existing.getOccurredAt() != null && requested.getOccurredAt() != null
                && existing.getOccurredAt().getTime() / 1000 == requested.getOccurredAt().getTime() / 1000));
    }

    private String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }

    private boolean sameProperties(String left, String right) {
        try {
            return Objects.equals(JSON.readTree(left == null || left.isEmpty() ? "{}" : left),
                    JSON.readTree(right == null || right.isEmpty() ? "{}" : right));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Long visibleCommunityPostAuthor(Long memberId, Long postId) {
        if (memberId == null || postId == null || postId <= 0) {
            return null;
        }
        com.chengyinhub.business.domain.community.CommunityPost post =
                communityPostMapper.selectById(postId, memberId);
        return post == null ? null : post.getAuthorId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int aggregateDaily(String statDate) {
        bizFunnelDailyMapper.deleteByStatDate(statDate);
        return bizFunnelDailyMapper.insertFromEventLog(statDate);
    }
}

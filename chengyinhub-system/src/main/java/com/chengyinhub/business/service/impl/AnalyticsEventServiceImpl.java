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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * P3 行为事件服务实现。
 */
@Service
public class AnalyticsEventServiceImpl implements IAnalyticsEventService {
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
            event.setCreateTime(DateUtils.getNowDate());
            int inserted = bizEventLogMapper.insertBizEventLog(event);
            if (inserted == 1) {
                recommendationService.updateProfileFromEvent(event);
            }
            accepted += inserted;
        }
        return accepted;
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

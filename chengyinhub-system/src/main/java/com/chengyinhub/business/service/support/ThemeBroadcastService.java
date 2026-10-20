package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.ThemeBroadcastMapper;
import com.chengyinhub.business.service.IImService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

/** 使用既有站内系统消息。剧情后果与待发送记录同事务；投递失败只重试消息。 */
@Service
public class ThemeBroadcastService {
    private final ThemeBroadcastMapper mapper;
    private final IImService im;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.chengyinhub.business.service.ITopicRouteRuntimeService routeRuntime;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.mapper.TopicRouteSessionMapper routeMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ThemeRuntimeFeaturePolicy themeRuntimePolicy = new ThemeRuntimeFeaturePolicy();
    public ThemeBroadcastService(ThemeBroadcastMapper mapper,IImService im) {this.mapper=mapper;this.im=im;}
    public void enqueue(TopicRouteSession session,TopicRouteEngine.State state) {
        if (state == null || state.getThemeRuntime().isEmpty()) return;
        themeRuntimePolicy.requireEnabled();
        themeRuntimePolicy.requireSessionEnabled(session);
        if(session.getId()==null)return;
        if(routeRuntime!=null)routeRuntime.assertCurrentPlayInstance(session.getMemberId(),session.getTopicId(),session.getActivityId(),session.getPlayInstanceId());
        else if(ThemeTeamRunCoordinator.isTeamInstance(session.getPlayInstanceId()))throw new IllegalStateException("整队广播缺少本局校验");
        for(JsonNode event:state.getThemeRuntime().path("events"))
            mapper.enqueue(session.getId(),session.getMemberId(),event.path("id").asText(),event.path("content").asText());
    }
    @Transactional(rollbackFor=Exception.class)
    public List<Map<String,Object>> deliver(Long sessionId,Long memberId) {
        themeRuntimePolicy.requireEnabled();
        if(routeRuntime!=null) {
            TopicRouteSession session=routeMapper.selectAccessibleById(sessionId,memberId);
            if(session==null)throw new com.chengyinhub.common.exception.ServiceException("本局不存在或无权查看",403);
            routeRuntime.assertCurrentPlayInstance(memberId,session.getTopicId(),session.getActivityId(),session.getPlayInstanceId());
        }
        for(Map<String,Object> row:mapper.lockDeliveries(sessionId,memberId)) {
            Long id=((Number)row.get("id")).longValue();Long message;
            try {
                String extra="{\"topicRouteSessionId\":"+sessionId+",\"themeDeliveryId\":"+id+"}";
                message=im.sendSystemInNestedTransaction(memberId,ImMessage.MSG_TEXT,String.valueOf(row.get("content")),extra);
                if(message==null)throw new IllegalStateException("广播未生成消息");
            } catch(RuntimeException failure) {
                if(mapper.failed(id,memberId)!=1)throw new IllegalStateException("广播失败状态保存失败");
                continue;
            }
            if(mapper.reached(id,memberId,message)!=1)throw new IllegalStateException("广播送达状态保存失败");
        }
        return mapper.receipts(sessionId,memberId);
    }
}

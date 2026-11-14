package com.chengyinhub.web.controller.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.channel.platform.ChannelProgressService;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 频道进度榜（App docs/channels/contract.md §3）；只读投影 + 管理者绑定。 */
@RestController
@Tag(name = "频道进度榜")
public class ApiChannelProgressController extends BaseController
{
    @Autowired
    private ChannelProgressService progress;

    @Operation(summary = "频道榜单定义")
    @GetMapping("/api/channel/{channelId}/board")
    public ResponseEntity<AjaxResult> boards(@PathVariable("channelId") String channelId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> progress.definitions(me, ChannelHttpResponse.id(channelId)));
    }

    @Operation(summary = "绑定频道榜单(MANAGE_PROGRESS)")
    @PostMapping("/api/channel/{channelId}/board")
    public ResponseEntity<AjaxResult> bind(@PathVariable("channelId") String channelId,
            @RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> progress.bind(me, ChannelHttpResponse.id(channelId), body));
    }

    @Operation(summary = "频道进度概览")
    @GetMapping("/api/channel/{channelId}/progress/overview")
    public ResponseEntity<AjaxResult> overview(@PathVariable("channelId") String channelId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> progress.overview(me, ChannelHttpResponse.id(channelId)));
    }

    @Operation(summary = "榜单前列")
    @GetMapping("/api/channel/{channelId}/board/{boardKey}/top")
    public ResponseEntity<AjaxResult> top(@PathVariable("channelId") String channelId, @PathVariable("boardKey") String boardKey,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "limit", defaultValue = "20") int limit)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> progress.page(me, ChannelHttpResponse.id(channelId), boardKey, period, limit, false, null, null));
    }

    @Operation(summary = "榜单明细(可筛选/分页)")
    @GetMapping("/api/channel/{channelId}/board/{boardKey}/detail")
    public ResponseEntity<AjaxResult> detail(@PathVariable("channelId") String channelId, @PathVariable("boardKey") String boardKey,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "filter[completed]", required = false) String completed,
            @RequestParam(value = "filter[team_id]", required = false) String teamId,
            @RequestParam(value = "cursor", required = false) String cursor)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> {
            if (teamId != null) throw new ChannelRejection(400, "FILTER_UNSUPPORTED", "暂不支持按队伍筛选");
            Boolean done = completed == null ? null : Boolean.valueOf("true".equals(completed) || "1".equals(completed));
            return progress.page(me, ChannelHttpResponse.id(channelId), boardKey, period, limit, true, done, cursor);
        });
    }

    @Operation(summary = "本人最佳")
    @GetMapping("/api/channel/{channelId}/board/{boardKey}/me/best")
    public ResponseEntity<AjaxResult> best(@PathVariable("channelId") String channelId, @PathVariable("boardKey") String boardKey,
            @RequestParam(value = "period", required = false) String period)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> progress.best(me, ChannelHttpResponse.id(channelId), boardKey, period));
    }

    @Operation(summary = "本人最近")
    @GetMapping("/api/channel/{channelId}/board/{boardKey}/me/recent")
    public ResponseEntity<AjaxResult> recent(@PathVariable("channelId") String channelId, @PathVariable("boardKey") String boardKey,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "limit", defaultValue = "20") int limit)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> progress.recent(me, ChannelHttpResponse.id(channelId), boardKey, period, limit));
    }

    @Operation(summary = "榜单版本")
    @GetMapping("/api/channel/{channelId}/board/{boardKey}/version")
    public ResponseEntity<AjaxResult> version(@PathVariable("channelId") String channelId, @PathVariable("boardKey") String boardKey)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> progress.version(me, ChannelHttpResponse.id(channelId), boardKey));
    }

    @Operation(summary = "成员进度计")
    @GetMapping("/api/progress/meter")
    public ResponseEntity<AjaxResult> meter(@RequestParam("channel_id") String channelId, @RequestParam("topic_id") long topicId,
            @RequestParam("activity_id") long activityId, @RequestParam("subject_type") String subjectType,
            @RequestParam("subject_id") long subjectId)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> progress.meter(me, ChannelHttpResponse.id(channelId), topicId, activityId, subjectType, subjectId));
    }
}

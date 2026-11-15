package com.chengyinhub.web.controller.api;

import java.util.Date;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.channelevent.ChannelEventDraft;
import com.chengyinhub.business.channelevent.ChannelEventService;
import com.chengyinhub.business.channelevent.ChannelEventService.PostTarget;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * C3 channel events (app contract: chengyin-app {@code docs/roadmap/channels-events/RESULT.md}).
 * Same HTTP shell as the other channel routes: a refusal's HTTP status equals {@code body.code} and carries a stable
 * {@code errorCode}. Posting runs the existing {@code /api/activity/publish} first, so identity, quota, content safety
 * and review are exactly those of any club activity.
 */
@RestController
@Tag(name = "频道活动")
public class ApiChannelEventController extends BaseController
{
    @Autowired
    private ChannelEventService events;

    @Autowired
    private ApiActivityController activities;

    @Operation(summary = "频道里的活动(进行中/即将开始在前)")
    @GetMapping("/api/channels/{id}/events")
    public ResponseEntity<AjaxResult> list(@PathVariable("id") String id)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> events.list(me, ChannelHttpResponse.id(id)));
    }

    @Operation(summary = "在频道发活动(建俱乐部活动 + 发活动卡片)")
    @PostMapping("/api/channels/{id}/events")
    public ResponseEntity<AjaxResult> create(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> {
            ChannelEventDraft draft = ChannelEventDraft.parse(body, new Date());
            PostTarget target = events.preparePost(me, ChannelHttpResponse.id(id), draft);
            CmsActivityPublishRequest request = ChannelEventService.toPublishRequest(target, draft);
            AjaxResult published = activities.publish(request);
            long activityId = publishedActivityId(published);
            return events.attach(me, target, draft, activityId);
        });
    }

    /** The existing publish route answers 200 with the activity id, or a refusal written for the host. */
    static long publishedActivityId(AjaxResult published)
    {
        Object code = published == null ? null : published.get(AjaxResult.CODE_TAG);
        Object data = published == null ? null : published.get(AjaxResult.DATA_TAG);
        if (code instanceof Number n && n.intValue() == 200 && data != null)
        {
            try
            {
                long activityId = Long.parseLong(data.toString());
                if (activityId > 0) return activityId;
            }
            catch (NumberFormatException ignored)
            {
                // fall through to the refusal
            }
        }
        Object msg = published == null ? null : published.get(AjaxResult.MSG_TAG);
        String text = msg == null || msg.toString().isBlank() ? "活动发布失败，请稍后重试" : msg.toString();
        throw new ChannelRejection(409, "EVENT_PUBLISH_REFUSED", text);
    }

    @Operation(summary = "活动卡片详情(人数、我的回应、可做的事)")
    @GetMapping("/api/channel-events/{id}")
    public ResponseEntity<AjaxResult> detail(@PathVariable("id") String id)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> events.detail(me, ChannelHttpResponse.id(id)));
    }

    @Operation(summary = "回应:去 / 可能去 / 不去")
    @PostMapping("/api/channel-events/{id}/rsvp")
    public ResponseEntity<AjaxResult> rsvp(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        Object status = body == null ? null : body.get("status");
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> events.rsvp(me, ChannelHttpResponse.id(id), status == null ? null : status.toString()));
    }

    @Operation(summary = "报名入口(走既有报名/购票)")
    @GetMapping("/api/channel-events/{id}/enrol-target")
    public ResponseEntity<AjaxResult> enrolTarget(@PathVariable("id") String id)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> events.enrolTarget(me, ChannelHttpResponse.id(id)));
    }

    @Operation(summary = "参加的人与可能去的人")
    @GetMapping("/api/channel-events/{id}/attendees")
    public ResponseEntity<AjaxResult> attendees(@PathVariable("id") String id)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> events.attendees(me, ChannelHttpResponse.id(id)));
    }

    @Operation(summary = "打开活动讨论(活动频道)")
    @PostMapping("/api/channel-events/{id}/thread")
    public ResponseEntity<AjaxResult> thread(@PathVariable("id") String id)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> events.thread(me, ChannelHttpResponse.id(id)));
    }

    @Operation(summary = "活动相册")
    @GetMapping("/api/channel-events/{id}/photos")
    public ResponseEntity<AjaxResult> photos(@PathVariable("id") String id,
            @RequestParam(value = "before_id", required = false) String beforeId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> events.photos(me, ChannelHttpResponse.id(id),
                beforeId == null || beforeId.isBlank() ? null : ChannelHttpResponse.id(beforeId)));
    }

    @Operation(summary = "上传照片到活动相册(asset_id 或本人已上传图片的 image_url;先过媒体审核)")
    @PostMapping("/api/channel-events/{id}/photos")
    public ResponseEntity<AjaxResult> addPhoto(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body)
    {
        Object asset = body == null ? null : body.get("asset_id");
        Object url = body == null ? null : body.get("image_url");
        return ChannelHttpResponse.run(this::getAppUserId, me -> asset == null && url != null
                ? events.addPhotoByUrl(me, ChannelHttpResponse.id(id), url.toString())
                : events.addPhoto(me, ChannelHttpResponse.id(id), asset == null ? 0L : ChannelHttpResponse.id(asset.toString())));
    }

    @Operation(summary = "从活动相册移除照片")
    @PostMapping("/api/channel-event-photos/{id}/remove")
    public ResponseEntity<AjaxResult> removePhoto(@PathVariable("id") String id)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> events.removePhoto(me, ChannelHttpResponse.id(id)));
    }
}

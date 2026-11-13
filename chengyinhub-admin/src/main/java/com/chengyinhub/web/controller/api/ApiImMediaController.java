package com.chengyinhub.web.controller.api;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.chengyinhub.business.service.channel.media.ImMediaPolicy.Kind;
import com.chengyinhub.business.service.channel.media.ImMediaService;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * IM 文件/视频/语音（docs/channels/contract.md §6.14）与单条原消息读取。信封同频道合同：HTTP 状态 == body.code + errorCode。
 * 字节走既有私有 OSS + app_media_asset 待审链路；审核通过后才可发送（/api/im/send）与读取。
 */
@RestController
@Tag(name = "IM 媒体")
public class ApiImMediaController extends BaseController
{
    @Autowired
    private ImMediaService media;

    @Operation(summary = "上传 IM 文件/视频/语音（原请求 upload_request_id 幂等）")
    @PostMapping("/api/im/{kind:file|video|voice}/upload")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    public ResponseEntity<AjaxResult> upload(@PathVariable("kind") String kind,
            @RequestParam(value = "conversation_id", required = false) String conversationId,
            @RequestParam(value = "upload_request_id", required = false) String uploadRequestId,
            @RequestParam(value = "source_sha256", required = false) String sourceSha256,
            @RequestParam(value = "file", required = false) MultipartFile file)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> {
            if (file == null || file.isEmpty()) throw new ChannelRejection(400, "MEDIA_EMPTY", "文件不能为空");
            byte[] bytes;
            try { bytes = file.getBytes(); }
            catch (IOException e) { throw new ChannelRejection(400, "MEDIA_UNREADABLE", "文件读取失败，请重试"); }
            return media.upload(me, Kind.of(kind), conversationId, uploadRequestId, sourceSha256, file.getOriginalFilename(), bytes);
        });
    }

    @Operation(summary = "上传原请求回读")
    @GetMapping("/api/im/{kind:file|video|voice}/receipt")
    public ResponseEntity<AjaxResult> receipt(@PathVariable("kind") String kind, @RequestParam("conversation_id") String conversationId,
            @RequestParam("upload_request_id") String uploadRequestId, @RequestParam("source_sha256") String sourceSha256)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> media.receipt(me, Kind.of(kind), conversationId, uploadRequestId, sourceSha256));
    }

    @Operation(summary = "文件审核状态（与小程序待审媒体同一审核态；不另起扫描）")
    @PostMapping("/api/im/file/scan")
    public ResponseEntity<AjaxResult> scan(@RequestParam("conversation_id") String conversationId,
            @RequestParam("upload_request_id") String uploadRequestId, @RequestParam("source_sha256") String sourceSha256)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> media.receipt(me, Kind.FILE, conversationId, uploadRequestId, sourceSha256));
    }

    @Operation(summary = "删除尚未发送的上传")
    @PostMapping("/api/im/{kind:file|video|voice}/delete")
    public ResponseEntity<AjaxResult> delete(@PathVariable("kind") String kind, @RequestParam("conversation_id") String conversationId,
            @RequestParam("upload_request_id") String uploadRequestId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> media.delete(me, Kind.of(kind), conversationId, uploadRequestId));
    }

    @Operation(summary = "视频策略（不转码）")
    @GetMapping("/api/im/video/policy")
    public ResponseEntity<AjaxResult> videoPolicy(@RequestParam("conversation_id") String conversationId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> media.videoPolicy(me, conversationId));
    }

    @Operation(summary = "媒体消息逐次授权")
    @GetMapping("/api/im/{kind:file|video|voice}/message/{id:[0-9]+}/authorization")
    public ResponseEntity<AjaxResult> authorization(@PathVariable("kind") String kind, @PathVariable("id") String id)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> media.authorization(me, Kind.of(kind), ChannelHttpResponse.id(id)));
    }

    @Operation(summary = "文件/视频 300 秒读取地址")
    @GetMapping("/api/im/{kind:file|video}/message/{id:[0-9]+}/preview")
    public ResponseEntity<AjaxResult> preview(@PathVariable("kind") String kind, @PathVariable("id") String id)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> media.preview(me, Kind.of(kind), ChannelHttpResponse.id(id)));
    }

    @Operation(summary = "语音原字节（audio/mp4）")
    @GetMapping("/api/im/voice/message/{id:[0-9]+}")
    public ResponseEntity<?> voice(@PathVariable("id") String id)
    {
        Object[] bytes = new Object[1];
        ResponseEntity<AjaxResult> result = ChannelHttpResponse.run(this::getAppUserId, me -> {
            bytes[0] = media.voiceBytes(me, ChannelHttpResponse.id(id));
            return null;
        });
        if (bytes[0] == null || !result.getStatusCode().is2xxSuccessful()) return result;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mp4"))
                .header("Cache-Control", "no-store").body((byte[]) bytes[0]);
    }

    @Operation(summary = "单条原消息")
    @GetMapping("/api/im/message")
    public ResponseEntity<AjaxResult> message(@RequestParam("message_id") String messageId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> media.message(me, messageId));
    }
}

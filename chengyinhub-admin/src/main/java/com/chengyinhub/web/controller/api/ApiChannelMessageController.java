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

import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.channel.platform.ChannelMessageService;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 频道消息变更/置顶/检索/上下文/修订（App docs/channels/contract.md §2）。发送与历史仍走 ApiImController 原链路。
 */
@RestController
@Tag(name = "频道消息")
public class ApiChannelMessageController extends BaseController
{
    @Autowired
    private ChannelMessageService channelMessages;
    @Autowired
    private IImService imService;

    @Operation(summary = "编辑频道消息(CAS)")
    @PostMapping("/api/im/message/edit")
    public ResponseEntity<AjaxResult> edit(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channelMessages.edit(me, body));
    }

    @Operation(summary = "撤回频道消息(CAS)")
    @PostMapping("/api/im/message/recall")
    public ResponseEntity<AjaxResult> recall(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channelMessages.recall(me, body));
    }

    @Operation(summary = "消息变更原请求回执")
    @GetMapping("/api/im/message/request")
    public ResponseEntity<AjaxResult> request(@RequestParam("request_id") String requestId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channelMessages.receipt(me, requestId));
    }

    @Operation(summary = "消息变更流")
    @GetMapping("/api/im/message/mutations")
    public ResponseEntity<AjaxResult> mutations(@RequestParam("conversation_id") String conversationId,
            @RequestParam("mutation_cursor") String cursor, @RequestParam(value = "limit", defaultValue = "50") int limit)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> channelMessages.mutations(me, ChannelHttpResponse.id(conversationId), cursor, limit));
    }

    @Operation(summary = "消息上下文")
    @GetMapping("/api/im/message/context")
    public ResponseEntity<AjaxResult> context(@RequestParam("conversation_id") String conversationId,
            @RequestParam("message_id") String messageId, @RequestParam(value = "limit", defaultValue = "20") int limit)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channelMessages.context(me,
                ChannelHttpResponse.id(conversationId), ChannelHttpResponse.id(messageId), limit));
    }

    @Operation(summary = "消息修订历史")
    @GetMapping("/api/im/message/{id}/revisions")
    public ResponseEntity<AjaxResult> revisions(@PathVariable("id") String id,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> channelMessages.revisions(me, ChannelHttpResponse.id(id), cursor, limit));
    }

    @Operation(summary = "置顶列表")
    @GetMapping("/api/im/pins")
    public ResponseEntity<AjaxResult> pins(@RequestParam("conversation_id") String conversationId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channelMessages.pins(me, ChannelHttpResponse.id(conversationId)));
    }

    @Operation(summary = "置顶消息")
    @PostMapping("/api/im/pin")
    public ResponseEntity<AjaxResult> pin(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channelMessages.pin(me, body, true));
    }

    @Operation(summary = "取消置顶")
    @PostMapping("/api/im/unpin")
    public ResponseEntity<AjaxResult> unpin(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channelMessages.pin(me, body, false));
    }

    @Operation(summary = "置顶排序")
    @PostMapping("/api/im/pins/sort")
    public ResponseEntity<AjaxResult> sortPins(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channelMessages.sortPins(me, body));
    }

    @Operation(summary = "频道内检索")
    @GetMapping("/api/im/search")
    public ResponseEntity<AjaxResult> search(@RequestParam("conversation_id") String conversationId,
            @RequestParam("keyword") String keyword, @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "author_id", required = false) String authorId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channelMessages.search(me,
                ChannelHttpResponse.id(conversationId), keyword, from, to, authorId, cursor, limit));
    }

    @Operation(summary = "频道已读推进")
    @PostMapping("/api/native/channels/read-through")
    public ResponseEntity<AjaxResult> readThrough(@RequestParam("conversation_id") String conversationId,
            @RequestParam("read_to") String readTo)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> {
            long conv = ChannelHttpResponse.id(conversationId);
            if (!channelMessages.isChannel(conv)) throw ChannelRejection.notFound();
            long to = readTo != null && readTo.matches("0|[1-9][0-9]{0,18}") ? Long.parseLong(readTo) : -1L;
            if (to < 0) throw ChannelRejection.badField("read_to 不合法");
            try
            {
                imService.markRead(me, conv, to);
            }
            catch (ServiceException e)
            {
                int code = e.getCode() == null ? 403 : e.getCode();
                throw new ChannelRejection(code >= 400 && code < 500 ? code : 403,
                        e.getDetailMessage() != null && e.getDetailMessage().matches("[A-Z][A-Z0-9_]{0,127}")
                                ? e.getDetailMessage() : "CHANNEL_FORBIDDEN", e.getMessage());
            }
            return channelMessages.readState(me, conv, to);
        });
    }
}

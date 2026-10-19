package com.chengyinhub.web.controller.api;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 站内消息(IM)
 */
@RestController
@Tag(name = "站内消息")
@RequestMapping("/api/im")
public class ApiImController extends BaseController
{
    @Autowired
    private IImService imService;

    @Operation(summary = "我的会话列表")
    @PostMapping("/conversations")
    public AjaxResult conversations()
    {
        try
        {
            return AjaxResult.success(imService.listConversations(getAppUserId()));
        }
        catch (Exception e)
        {
            logger.error("IM 会话列表异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }

<<<<<<< public
    public AjaxResult messages(String conversation_id, String cursor_id, String size)
    {
        return messages(conversation_id, cursor_id, size, null);
=======
    public AjaxResult messages(String conversation_id, String cursor_id, String after_id,
                               String watermark_id, String size)
    {
        return messages(conversation_id, cursor_id, after_id, watermark_id, size, null);
>>>>>>> private-codex/batch1
    }

    @Operation(summary = "会话消息分页")
    @PostMapping("/messages")
<<<<<<< public
    public AjaxResult messages(String conversation_id, String cursor_id, String size, String after_id)
=======
    public AjaxResult messages(String conversation_id, String cursor_id, String after_id,
                               String watermark_id, String size,
                               @RequestParam(value = "client_message_id", required = false) String client_message_id)
>>>>>>> private-codex/batch1
    {
        try
        {
            Long cid = Convert.toLong(conversation_id, 0L);
            Long cursor = Convert.toLong(cursor_id, 0L);
            Long after = Convert.toLong(after_id, 0L);
            Long watermark = Convert.toLong(watermark_id, 0L);
            int sz = Convert.toInt(size, 20);
<<<<<<< public
            return AjaxResult.success(after_id == null ? imService.listMessages(getAppUserId(), cid, cursor, sz)
                    : imService.listMessages(getAppUserId(), cid, cursor, sz, Convert.toLong(after_id, -1L)));
=======
            if (client_message_id != null)
            {
                return AjaxResult.success(imService.findOwnMessage(getAppUserId(), cid, client_message_id));
            }
            if (cursor > 0 && after > 0)
            {
                return AjaxResult.error("cursor_id 与 after_id 不能同时使用");
            }
            if (after_id != null)
            {
                return AjaxResult.success(imService.listMessagesAfter(getAppUserId(), cid, after,
                        watermark > 0 ? watermark : null, sz));
            }
            return AjaxResult.success(imService.listMessages(getAppUserId(), cid, cursor, sz));
>>>>>>> private-codex/batch1
        }
        catch (ServiceException e)
        {
            return bizError(e);
        }
        catch (Exception e)
        {
            logger.error("IM 会话消息分页异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }

    /** 供既有直接调用/测试保留的旧签名。 */
    public AjaxResult messages(String conversation_id, String cursor_id, String size)
    {
        return messages(conversation_id, cursor_id, null, null, size);
    }

    public AjaxResult send(String conversation_id, String target_member_id, String msg_type, String content, String extra_json)
    {
        return send(conversation_id, target_member_id, msg_type, content, extra_json, null);
    }

    @Operation(summary = "发送消息")
    @PostMapping("/send")
    public AjaxResult send(@RequestParam(value = "conversation_id", required = false) String conversation_id,
                           @RequestParam(value = "target_member_id", required = false) String target_member_id,
                           @RequestParam(value = "msg_type", required = false) String msg_type,
                           @RequestParam(value = "content", required = false) String content,
                           @RequestParam(value = "extra_json", required = false) String extra_json,
                           @RequestParam(value = "client_message_id", required = false) String client_message_id)
    {
        try
        {
            Long cid = Convert.toLong(conversation_id, 0L);
            Long target = Convert.toLong(target_member_id, 0L);
            Integer mt = Convert.toInt(msg_type, 1);
            ImMessage m = client_message_id == null
                    ? imService.send(getAppUserId(), cid, target > 0 ? target : null, mt, content, extra_json)
                    : imService.send(getAppUserId(), cid, target > 0 ? target : null, mt, content, extra_json, client_message_id);
            return AjaxResult.success(m);
        }
        catch (ServiceException e)
        {
            return bizError(e);
        }
        catch (Exception e)
        {
            logger.error("IM 发送消息异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }

    @Operation(summary = "回读原消息发送收据")
    @PostMapping("/send-receipt")
    public AjaxResult sendReceipt(String conversation_id, String client_message_id)
    {
        try {
            ImMessage receipt = imService.readSentMessage(getAppUserId(),
                    Convert.toLong(conversation_id, 0L), client_message_id);
            Map<String, Object> result = new HashMap<>();
            result.put("found", receipt != null);
            result.put("message", receipt);
            return AjaxResult.success(result);
        } catch (ServiceException e) { return bizError(e); }
        catch (Exception e) { logger.error("IM 原消息回读异常", e); return AjaxResult.error("原消息结果待确认"); }
    }

    public AjaxResult read(String conversation_id)
    {
        return read(conversation_id, null);
    }

    @Operation(summary = "标记已读")
    @PostMapping("/read")
    public AjaxResult read(String conversation_id, String read_to)
    {
        try
        {
<<<<<<< public
            if (read_to == null) imService.markRead(getAppUserId(), Convert.toLong(conversation_id, 0L));
            else imService.markRead(getAppUserId(), Convert.toLong(conversation_id, 0L), Convert.toLong(read_to, -1L));
=======
            Long cid = Convert.toLong(conversation_id, 0L);
            if (read_to == null)
            {
                imService.markRead(getAppUserId(), cid);
            }
            else
            {
                imService.markRead(getAppUserId(), cid, Convert.toLong(read_to, 0L));
            }
>>>>>>> private-codex/batch1
            return AjaxResult.success();
        }
        catch (ServiceException e)
        {
            return bizError(e);
        }
        catch (Exception e)
        {
            logger.error("IM 标记已读异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }

    /** 供既有直接调用保留的旧签名。 */
    public AjaxResult read(String conversation_id)
    {
        return read(conversation_id, null);
    }

    @Operation(summary = "删除会话")
    @PostMapping("/delete")
    public AjaxResult delete(String conversation_id)
    {
        try
        {
            imService.deleteConversation(getAppUserId(), Convert.toLong(conversation_id, 0L));
            return AjaxResult.success();
        }
        catch (Exception e)
        {
            logger.error("IM 删除会话异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }

    @Operation(summary = "设置会话免打扰")
    @PostMapping("/mute")
    public AjaxResult mute(String conversation_id, String muted)
    {
        try
        {
            Long cid = Convert.toLong(conversation_id, 0L);
            imService.setMuted(getAppUserId(), cid, Convert.toInt(muted, 0) == 1);
            return AjaxResult.success();
        }
        catch (Exception e)
        {
            logger.error("IM 设置免打扰异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }

    @Operation(summary = "未读总数")
    @PostMapping("/unread-total")
    public AjaxResult unreadTotal()
    {
        try
        {
            return AjaxResult.success(imService.unreadTotal(getAppUserId()));
        }
        catch (Exception e)
        {
            logger.error("IM 未读总数异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }

    @Operation(summary = "发起/获取单聊会话")
    @PostMapping("/start")
    public AjaxResult start(String target_member_id)
    {
        try
        {
            Long cid = imService.startSingle(getAppUserId(), Convert.toLong(target_member_id, 0L));
            Map<String, Object> d = new HashMap<>();
            d.put("conversationId", cid);
            return AjaxResult.success(d);
        }
        catch (ServiceException e)
        {
            return bizError(e);
        }
        catch (Exception e)
        {
            logger.error("IM 发起单聊异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }

    @Operation(summary = "拉黑")
    @PostMapping("/block")
    public AjaxResult block(String target_member_id)
    {
        try
        {
            imService.block(getAppUserId(), Convert.toLong(target_member_id, 0L));
            return AjaxResult.success("已拉黑");
        }
        catch (ServiceException e)
        {
            return bizError(e);
        }
        catch (Exception e)
        {
            logger.error("IM 拉黑异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }

    @Operation(summary = "取消拉黑")
    @PostMapping("/unblock")
    public AjaxResult unblock(String target_member_id)
    {
        try
        {
            imService.unblock(getAppUserId(), Convert.toLong(target_member_id, 0L));
            return AjaxResult.success("已取消");
        }
        catch (ServiceException e)
        {
            return bizError(e);
        }
        catch (Exception e)
        {
            logger.error("IM 取消拉黑异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }

    @Operation(summary = "举报消息")
    @PostMapping("/report")
    public AjaxResult report(String message_id, String reason)
    {
        try
        {
            imService.report(getAppUserId(), Convert.toLong(message_id, 0L), reason);
            return AjaxResult.success("举报已提交");
        }
        catch (ServiceException e)
        {
            return bizError(e);
        }
        catch (Exception e)
        {
            logger.error("IM 举报消息异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }

    /** 只透传服务端已知终态，普通业务拒绝仍保留原消息。 */
    private static AjaxResult bizError(ServiceException e)
    {
        AjaxResult result = new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        String detail = e.getDetailMessage();
        if ("HANGOUT_CLOSED".equals(detail) || "IM_CONVERSATION_CLOSED".equals(detail))
        {
            result.put("errorCode", detail);
            result.put("returnPath", "/subpackageB/pages/im/list/index");
        }
        return result;
    }
}

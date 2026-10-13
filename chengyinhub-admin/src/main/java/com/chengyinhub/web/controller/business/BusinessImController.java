package com.chengyinhub.web.controller.business;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.IImService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.BusinessType;

/**
 * 后台站内消息·客服台（与商家/用户沟通 + 拉黑用户）
 */
@RestController
@RequestMapping("/business/im")
public class BusinessImController extends BaseController
{
    @Autowired
    private IImService imService;

    @PreAuthorize("@ss.hasPermi('business:im:list')")
    @GetMapping("/conversations")
    public AjaxResult conversations()
    {
        return success(imService.adminListConversations());
    }

    @PreAuthorize("@ss.hasPermi('business:im:list')")
    @GetMapping("/messages")
    public AjaxResult messages(String conversationId, String cursorId, String size)
    {
        Long cid = Convert.toLong(conversationId, 0L);
        Long cursor = Convert.toLong(cursorId, 0L);
        int sz = Convert.toInt(size, 30);
        return success(imService.adminListMessages(cid, cursor, sz));
    }

    @PreAuthorize("@ss.hasPermi('business:im:send')")
    @Log(title = "客服发送消息", businessType = BusinessType.INSERT)
    @PostMapping("/send")
    public AjaxResult send(String memberId, String msgType, String content, String extraJson)
    {
        try
        {
            Long mid = Convert.toLong(memberId, 0L);
            Integer mt = Convert.toInt(msgType, 1);
            Long id = imService.sendFromAdmin(mid, mt, content, extraJson);
            return success(id);
        }
        catch (Exception e)
        {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:im:block')")
    @Log(title = "拉黑用户", businessType = BusinessType.UPDATE)
    @PostMapping("/block")
    public AjaxResult block(String memberId)
    {
        try
        {
            imService.adminBlock(Convert.toLong(memberId, 0L));
            return success("已拉黑");
        }
        catch (Exception e)
        {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:im:block')")
    @Log(title = "解除拉黑", businessType = BusinessType.UPDATE)
    @PostMapping("/unblock")
    public AjaxResult unblock(String memberId)
    {
        try
        {
            imService.adminUnblock(Convert.toLong(memberId, 0L));
            return success("已解除");
        }
        catch (Exception e)
        {
            return error(e.getMessage());
        }
    }
}

package com.chengyinhub.web.controller.business;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.channel.platform.ChannelModerationAdminService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;

/** R10 BE-23 平台频道治理：搜频道与消息、看举报、屏蔽/恢复消息、禁言、移出空间、撤销处置。 */
@RestController
@RequestMapping("/business/channelModeration")
public class ChannelModerationAdminController extends BaseController
{
    @Autowired
    private ChannelModerationAdminService service;

    @PreAuthorize("@ss.hasPermi('business:channelModeration:list')")
    @GetMapping("/channels")
    public AjaxResult channels(String keyword, Integer guildKind, Long guildId, Integer status)
    {
        return success(service.channels(keyword, guildKind, guildId, status));
    }

    @PreAuthorize("@ss.hasPermi('business:channelModeration:list')")
    @GetMapping("/messages")
    public AjaxResult messages(Long channelId, Long senderId, String keyword, Integer status, Long beforeId)
    {
        return success(service.messages(channelId, senderId, keyword, status, beforeId));
    }

    @PreAuthorize("@ss.hasPermi('business:channelModeration:list')")
    @GetMapping("/reports")
    public AjaxResult reports(String status, Long channelId, Long beforeId)
    {
        return success(service.reports(status, channelId, beforeId));
    }

    @PreAuthorize("@ss.hasPermi('business:channelModeration:list')")
    @GetMapping("/actions")
    public AjaxResult actions(Integer guildKind, Long guildId, Long targetMemberId, String status)
    {
        return success(service.actions(guildKind, guildId, targetMemberId, status));
    }

    @PreAuthorize("@ss.hasPermi('business:channelModeration:message')")
    @Log(title = "平台屏蔽频道消息", businessType = BusinessType.UPDATE)
    @PostMapping("/messages/{id}/hide")
    public AjaxResult hide(@PathVariable long id, @RequestBody Map<String, Object> body)
    {
        return success(service.hideMessage(getUserId(), id, RewardPledgeAdminController.str(body, "reason")));
    }

    @PreAuthorize("@ss.hasPermi('business:channelModeration:message')")
    @Log(title = "平台恢复频道消息", businessType = BusinessType.UPDATE)
    @PostMapping("/messages/{id}/restore")
    public AjaxResult restore(@PathVariable long id, @RequestBody Map<String, Object> body)
    {
        return success(service.restoreMessage(getUserId(), id, RewardPledgeAdminController.str(body, "reason")));
    }

    @PreAuthorize("@ss.hasPermi('business:channelModeration:message')")
    @Log(title = "驳回频道举报", businessType = BusinessType.UPDATE)
    @PostMapping("/reports/{id}/dismiss")
    public AjaxResult dismiss(@PathVariable long id, @RequestBody Map<String, Object> body)
    {
        return success(service.dismissReport(getUserId(), id, RewardPledgeAdminController.str(body, "reason")));
    }

    @PreAuthorize("@ss.hasPermi('business:channelModeration:member')")
    @Log(title = "平台禁言频道成员", businessType = BusinessType.UPDATE)
    @PostMapping("/mute")
    public AjaxResult mute(@RequestBody Map<String, Object> body)
    {
        return success(service.mute(getUserId(), num(body, "channelId"), num(body, "memberId"), (int) num(body, "durationSec"),
                RewardPledgeAdminController.str(body, "reasonCode"), RewardPledgeAdminController.str(body, "reasonText")));
    }

    @PreAuthorize("@ss.hasPermi('business:channelModeration:member')")
    @Log(title = "平台移出空间成员", businessType = BusinessType.UPDATE)
    @PostMapping("/remove")
    public AjaxResult remove(@RequestBody Map<String, Object> body)
    {
        return success(service.remove(getUserId(), (int) num(body, "guildKind"), num(body, "guildId"), num(body, "memberId"),
                (int) num(body, "durationSec"), RewardPledgeAdminController.str(body, "reasonCode"),
                RewardPledgeAdminController.str(body, "reasonText")));
    }

    @PreAuthorize("@ss.hasPermi('business:channelModeration:member')")
    @Log(title = "撤销频道处置", businessType = BusinessType.UPDATE)
    @PostMapping("/actions/{id}/revoke")
    public AjaxResult revoke(@PathVariable long id, @RequestBody Map<String, Object> body)
    {
        return success(service.revoke(getUserId(), id, RewardPledgeAdminController.str(body, "reason")));
    }

    private static long num(Map<String, Object> body, String key)
    {
        String v = RewardPledgeAdminController.str(body, key);
        if (v == null || !v.trim().matches("-?[0-9]{1,18}")) throw new com.chengyinhub.common.exception.ServiceException(key + " 必须是整数", 400);
        return Long.parseLong(v.trim());
    }
}

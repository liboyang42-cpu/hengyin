package com.chengyinhub.web.controller.business;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.channelevent.ChannelEventService;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;

/**
 * C3 channel events, admin console (频道活动): events posted in club channels with their live RSVP counts, and album
 * moderation. Photo review itself stays in the existing media review queue; here an operator removes or restores a
 * photo from one album. Permissions come from the migration and are granted to no role.
 */
@RestController
@RequestMapping("/business/channel-event")
public class ChannelEventAdminController extends BaseController
{
    private final ChannelEventService events;

    public ChannelEventAdminController(ChannelEventService events)
    {
        this.events = events;
    }

    @PreAuthorize("@ss.hasPermi('business:channelEvent:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(value = "clubId", required = false) Long clubId,
            @RequestParam(value = "beforeId", required = false) Long beforeId)
    {
        return success(events.adminEvents(clubId, beforeId));
    }

    @PreAuthorize("@ss.hasPermi('business:channelEvent:list')")
    @GetMapping("/{id}/photos")
    public AjaxResult photos(@PathVariable("id") Long id)
    {
        return success(events.adminPhotos(id));
    }

    @PreAuthorize("@ss.hasPermi('business:channelEvent:moderate')")
    @Log(title = "频道活动相册", businessType = BusinessType.UPDATE)
    @PostMapping("/photo/{id}/remove")
    public AjaxResult remove(@PathVariable("id") Long id)
    {
        return setRemoved(id, true);
    }

    @PreAuthorize("@ss.hasPermi('business:channelEvent:moderate')")
    @Log(title = "频道活动相册", businessType = BusinessType.UPDATE)
    @PostMapping("/photo/{id}/restore")
    public AjaxResult restore(@PathVariable("id") Long id)
    {
        return setRemoved(id, false);
    }

    private AjaxResult setRemoved(Long id, boolean removed)
    {
        try
        {
            return events.adminSetPhotoRemoved(id == null ? 0 : id, removed, getUserId()) ? success() : error("照片状态未变化");
        }
        catch (ChannelRejection e)
        {
            return error(e.getMessage());
        }
    }
}

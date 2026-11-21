package com.chengyinhub.web.controller.business;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.opsconsole.RoamPlayOpsService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/** R10 BE-25 漫游与游玩运营只读视图：会话、揭雾计数、队伍、游玩局。不出任何个人轨迹或格子位置。 */
@RestController
@RequestMapping("/business/roamOps")
public class RoamPlayOpsController extends BaseController
{
    @Autowired
    private RoamPlayOpsService service;

    @PreAuthorize("@ss.hasPermi('business:roamOps:list')")
    @GetMapping("/sessions")
    public AjaxResult sessions(Long memberId, Integer status, String since, String until)
    {
        return success(service.sessions(memberId, status, since, until));
    }

    @PreAuthorize("@ss.hasPermi('business:roamOps:list')")
    @GetMapping("/fog")
    public AjaxResult fog(Long memberId)
    {
        return success(service.fog(memberId));
    }

    @PreAuthorize("@ss.hasPermi('business:roamOps:list')")
    @GetMapping("/teams")
    public AjaxResult teams(Integer ownerType, Long ownerId, Integer status)
    {
        return success(service.teams(ownerType, ownerId, status));
    }

    @PreAuthorize("@ss.hasPermi('business:roamOps:list')")
    @GetMapping("/runs")
    public AjaxResult runs(Long memberId, Long topicId, String runState, Integer ownerType, Long ownerId, Integer status)
    {
        return success(service.runs(memberId, topicId, runState, ownerType, ownerId, status));
    }
}

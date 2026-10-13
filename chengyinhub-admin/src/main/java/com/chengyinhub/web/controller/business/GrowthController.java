package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.GrowthMission;
import com.chengyinhub.business.domain.MemberGrowth;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3 玩家成长后台。
 */
@RestController
@RequestMapping("/business/growth")
public class GrowthController extends BaseController {

    @Autowired
    private IGrowthService growthService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @PreAuthorize("@ss.hasPermi('business:growth:list')")
    @GetMapping("/list")
    public TableDataInfo list(MemberGrowth growth) {
        startPage();
        return getDataTable(growthService.selectAdminGrowthList(growth));
    }

    /** 用户 360 的成长调证详情；只读，不因查看而创建成长账户。 */
    @PreAuthorize("@ss.hasPermi('business:member:query')")
    @GetMapping("/member/{memberId}")
    public AjaxResult memberCenter(@PathVariable Long memberId) {
        if (memberId == null || memberId <= 0) {
            return error("用户编号无效");
        }
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        if (member == null) {
            return error("用户不存在");
        }
        return success(growthService.getCenter(memberId));
    }

    @PreAuthorize("@ss.hasPermi('business:growth:list')")
    @GetMapping("/mission/list")
    public TableDataInfo missionList(GrowthMission mission) {
        startPage();
        return getDataTable(growthService.selectAdminMissionList(mission));
    }

    @PreAuthorize("@ss.hasPermi('business:growth:edit')")
    @Log(title = "P3成长任务", businessType = BusinessType.UPDATE)
    @PostMapping("/mission")
    public AjaxResult saveMission(@RequestBody GrowthMission mission) {
        return toAjax(growthService.saveMission(mission));
    }
}

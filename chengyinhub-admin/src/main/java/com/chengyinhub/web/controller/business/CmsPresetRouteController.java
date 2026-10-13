package com.chengyinhub.web.controller.business;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.business.domain.CmsPresetRoute;
import com.chengyinhub.business.service.ICmsPresetRouteService;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 建议版城市路线预设Controller(含站点)
 *
 * @author chengyin
 */
@RestController
@RequestMapping("/business/presetRoute")
public class CmsPresetRouteController extends BaseController
{
    @Autowired
    private ICmsPresetRouteService cmsPresetRouteService;

    @PreAuthorize("@ss.hasPermi('business:presetRoute:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsPresetRoute cmsPresetRoute)
    {
        startPage();
        List<CmsPresetRoute> list = cmsPresetRouteService.selectCmsPresetRouteList(cmsPresetRoute);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:presetRoute:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(cmsPresetRouteService.selectCmsPresetRouteById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:presetRoute:add')")
    @Log(title = "建议版路线", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsPresetRoute cmsPresetRoute)
    {
        return toAjax(cmsPresetRouteService.insertCmsPresetRoute(cmsPresetRoute));
    }

    @PreAuthorize("@ss.hasPermi('business:presetRoute:edit')")
    @Log(title = "建议版路线", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsPresetRoute cmsPresetRoute)
    {
        return toAjax(cmsPresetRouteService.updateCmsPresetRoute(cmsPresetRoute));
    }

    @PreAuthorize("@ss.hasPermi('business:presetRoute:remove')")
    @Log(title = "建议版路线", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(cmsPresetRouteService.deleteCmsPresetRouteByIds(ids));
    }
}

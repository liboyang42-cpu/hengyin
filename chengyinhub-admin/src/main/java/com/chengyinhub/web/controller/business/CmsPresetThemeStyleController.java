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
import com.chengyinhub.business.domain.CmsPresetThemeStyle;
import com.chengyinhub.business.service.ICmsPresetThemeStyleService;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 建议版主题风格预设Controller
 *
 * @author chengyin
 */
@RestController
@RequestMapping("/business/presetStyle")
public class CmsPresetThemeStyleController extends BaseController
{
    @Autowired
    private ICmsPresetThemeStyleService cmsPresetThemeStyleService;

    @PreAuthorize("@ss.hasPermi('business:presetStyle:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsPresetThemeStyle cmsPresetThemeStyle)
    {
        startPage();
        List<CmsPresetThemeStyle> list = cmsPresetThemeStyleService.selectCmsPresetThemeStyleList(cmsPresetThemeStyle);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:presetStyle:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(cmsPresetThemeStyleService.selectCmsPresetThemeStyleById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:presetStyle:add')")
    @Log(title = "建议版风格", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsPresetThemeStyle cmsPresetThemeStyle)
    {
        return toAjax(cmsPresetThemeStyleService.insertCmsPresetThemeStyle(cmsPresetThemeStyle));
    }

    @PreAuthorize("@ss.hasPermi('business:presetStyle:edit')")
    @Log(title = "建议版风格", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsPresetThemeStyle cmsPresetThemeStyle)
    {
        return toAjax(cmsPresetThemeStyleService.updateCmsPresetThemeStyle(cmsPresetThemeStyle));
    }

    @PreAuthorize("@ss.hasPermi('business:presetStyle:remove')")
    @Log(title = "建议版风格", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(cmsPresetThemeStyleService.deleteCmsPresetThemeStyleByIds(ids));
    }
}

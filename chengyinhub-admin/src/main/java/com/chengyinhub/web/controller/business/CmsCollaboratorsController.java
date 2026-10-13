package com.chengyinhub.web.controller.business;

import java.util.List;
import javax.servlet.http.HttpServletResponse;
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
import com.chengyinhub.business.domain.CmsCollaborators;
import com.chengyinhub.business.service.ICmsCollaboratorsService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 活动/主题合作者Controller
 * 
 * @author webxh
 * @date 2025-09-02
 */
@RestController
@RequestMapping("/business/collaborators")
public class CmsCollaboratorsController extends BaseController
{
    @Autowired
    private ICmsCollaboratorsService cmsCollaboratorsService;

    /**
     * 查询活动/主题合作者列表
     */
    @PreAuthorize("@ss.hasPermi('business:collaborators:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsCollaborators cmsCollaborators)
    {
        startPage();
        List<CmsCollaborators> list = cmsCollaboratorsService.selectCmsCollaboratorsList(cmsCollaborators);
        return getDataTable(list);
    }

    /**
     * 导出活动/主题合作者列表
     */
    @PreAuthorize("@ss.hasPermi('business:collaborators:export')")
    @Log(title = "活动/主题合作者", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsCollaborators cmsCollaborators)
    {
        List<CmsCollaborators> list = cmsCollaboratorsService.selectCmsCollaboratorsList(cmsCollaborators);
        ExcelUtil<CmsCollaborators> util = new ExcelUtil<CmsCollaborators>(CmsCollaborators.class);
        util.exportExcel(response, list, "活动、主题合作者数据");
    }

    /**
     * 获取活动/主题合作者详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:collaborators:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(cmsCollaboratorsService.selectCmsCollaboratorsById(id));
    }

    /**
     * 新增活动/主题合作者
     */
    @PreAuthorize("@ss.hasPermi('business:collaborators:add')")
    @Log(title = "活动/主题合作者", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsCollaborators cmsCollaborators)
    {
        return toAjax(cmsCollaboratorsService.insertCmsCollaborators(cmsCollaborators));
    }

    /**
     * 修改活动/主题合作者
     */
    @PreAuthorize("@ss.hasPermi('business:collaborators:edit')")
    @Log(title = "活动/主题合作者", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsCollaborators cmsCollaborators)
    {
        return toAjax(cmsCollaboratorsService.updateCmsCollaborators(cmsCollaborators));
    }

    /**
     * 删除活动/主题合作者
     */
    @PreAuthorize("@ss.hasPermi('business:collaborators:remove')")
    @Log(title = "活动/主题合作者", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(cmsCollaboratorsService.deleteCmsCollaboratorsByIds(ids));
    }
}

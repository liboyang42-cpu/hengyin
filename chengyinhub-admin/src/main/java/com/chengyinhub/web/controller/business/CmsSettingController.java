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
import com.chengyinhub.business.domain.CmsSetting;
import com.chengyinhub.business.service.ICmsSettingService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 参数设置Controller
 * 
 * @author ruoyi
 * @date 2025-11-17
 */
@RestController
@RequestMapping("/business/setting")
public class CmsSettingController extends BaseController
{
    @Autowired
    private ICmsSettingService cmsSettingService;

    /**
     * 查询参数设置列表
     */
    @PreAuthorize("@ss.hasPermi('business:setting:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsSetting cmsSetting)
    {
        startPage();
        List<CmsSetting> list = cmsSettingService.selectCmsSettingList(cmsSetting);
        return getDataTable(list);
    }

    /**
     * 导出参数设置列表
     */
    @PreAuthorize("@ss.hasPermi('business:setting:export')")
    @Log(title = "参数设置", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsSetting cmsSetting)
    {
        List<CmsSetting> list = cmsSettingService.selectCmsSettingList(cmsSetting);
        ExcelUtil<CmsSetting> util = new ExcelUtil<CmsSetting>(CmsSetting.class);
        util.exportExcel(response, list, "参数设置数据");
    }

    /**
     * 获取参数设置详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:setting:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(cmsSettingService.selectCmsSettingById(id));
    }

    /**
     * 新增参数设置
     */
    @PreAuthorize("@ss.hasPermi('business:setting:add')")
    @Log(title = "参数设置", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsSetting cmsSetting)
    {
        return toAjax(cmsSettingService.insertCmsSetting(cmsSetting));
    }

    /**
     * 修改参数设置
     */
    @PreAuthorize("@ss.hasPermi('business:setting:edit')")
    @Log(title = "参数设置", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsSetting cmsSetting)
    {
        return toAjax(cmsSettingService.updateCmsSetting(cmsSetting));
    }

    /**
     * 删除参数设置
     */
    @PreAuthorize("@ss.hasPermi('business:setting:remove')")
    @Log(title = "参数设置", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(cmsSettingService.deleteCmsSettingByIds(ids));
    }
}

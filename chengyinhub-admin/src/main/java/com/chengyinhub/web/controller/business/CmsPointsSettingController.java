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
import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 积分规则与分润设置Controller
 * 
 * @author ruoyi
 * @date 2026-01-31
 */
@RestController
@RequestMapping("/business/pointsetting")
public class CmsPointsSettingController extends BaseController
{
    @Autowired
    private ICmsPointsSettingService cmsPointsSettingService;

    /**
     * 查询积分规则与分润设置列表
     */
    @PreAuthorize("@ss.hasPermi('business:pointsetting:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsPointsSetting cmsPointsSetting)
    {
        startPage();
        List<CmsPointsSetting> list = cmsPointsSettingService.selectCmsPointsSettingList(cmsPointsSetting);
        return getDataTable(list);
    }

    /**
     * 导出积分规则与分润设置列表
     */
    @PreAuthorize("@ss.hasPermi('business:pointsetting:export')")
    @Log(title = "积分规则与分润设置", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsPointsSetting cmsPointsSetting)
    {
        List<CmsPointsSetting> list = cmsPointsSettingService.selectCmsPointsSettingList(cmsPointsSetting);
        ExcelUtil<CmsPointsSetting> util = new ExcelUtil<CmsPointsSetting>(CmsPointsSetting.class);
        util.exportExcel(response, list, "积分规则与分润设置数据");
    }

    /**
     * 获取积分规则与分润设置详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:pointsetting:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(cmsPointsSettingService.selectCmsPointsSettingById(id));
    }

    /**
     * 新增积分规则与分润设置
     */
    @PreAuthorize("@ss.hasPermi('business:pointsetting:add')")
    @Log(title = "积分规则与分润设置", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsPointsSetting cmsPointsSetting)
    {
        return toAjax(cmsPointsSettingService.insertCmsPointsSetting(cmsPointsSetting));
    }

    /**
     * 修改积分规则与分润设置
     */
    @PreAuthorize("@ss.hasPermi('business:pointsetting:edit')")
    @Log(title = "积分规则与分润设置", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsPointsSetting cmsPointsSetting)
    {
        return toAjax(cmsPointsSettingService.updateCmsPointsSetting(cmsPointsSetting));
    }

    /**
     * 删除积分规则与分润设置
     */
    @PreAuthorize("@ss.hasPermi('business:pointsetting:remove')")
    @Log(title = "积分规则与分润设置", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(cmsPointsSettingService.deleteCmsPointsSettingByIds(ids));
    }
}

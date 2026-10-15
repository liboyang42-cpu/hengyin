package com.chengyinhub.web.controller.system;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
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
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 活动、主题等分类Controller
 * 
 * @author webxh
 * @date 2025-08-31
 */
@RestController
@RequestMapping("/system/category")
public class SysCategoryController extends BaseController
{
    @Autowired
    private ISysCategoryService sysCategoryService;

    /**
     * 查询活动、主题等分类列表
     */
    @PreAuthorize("@ss.hasPermi('system:category:list')")
    @GetMapping("/list")
    public TableDataInfo list(SysCategory sysCategory)
    {
        startPage();
        List<SysCategory> list = sysCategoryService.selectSysCategoryList(sysCategory);
        return getDataTable(list);
    }

    /**
     * 导出活动、主题等分类列表
     */
    @PreAuthorize("@ss.hasPermi('system:category:export')")
    @Log(title = "活动、主题等分类", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, SysCategory sysCategory)
    {
        List<SysCategory> list = sysCategoryService.selectSysCategoryList(sysCategory);
        ExcelUtil<SysCategory> util = new ExcelUtil<SysCategory>(SysCategory.class);
        util.exportExcel(response, list, "活动、主题等分类数据");
    }

    /**
     * 获取活动、主题等分类详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:category:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(sysCategoryService.selectSysCategoryById(id));
    }

    /**
     * 新增活动、主题等分类
     */
    @PreAuthorize("@ss.hasPermi('system:category:add')")
    @Log(title = "活动、主题等分类", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody SysCategory sysCategory)
    {
        return toAjax(sysCategoryService.insertSysCategory(sysCategory));
    }

    /**
     * 修改活动、主题等分类
     */
    @PreAuthorize("@ss.hasPermi('system:category:edit')")
    @Log(title = "活动、主题等分类", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody SysCategory sysCategory)
    {
        return toAjax(sysCategoryService.updateSysCategory(sysCategory));
    }

    /**
     * 删除活动、主题等分类
     */
    @PreAuthorize("@ss.hasPermi('system:category:remove')")
    @Log(title = "活动、主题等分类", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(sysCategoryService.deleteSysCategoryByIds(ids));
    }
}

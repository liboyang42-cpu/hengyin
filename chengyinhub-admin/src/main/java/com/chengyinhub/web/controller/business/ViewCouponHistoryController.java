package com.chengyinhub.web.controller.business;

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
import com.chengyinhub.system.domain.ViewCouponHistory;
import com.chengyinhub.system.service.IViewCouponHistoryService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * VIEWController
 * 
 * @author ruoyi
 * @date 2025-10-16
 */
@RestController
@RequestMapping("/system/history")
public class ViewCouponHistoryController extends BaseController
{
    @Autowired
    private IViewCouponHistoryService viewCouponHistoryService;

    /**
     * 查询VIEW列表
     */
    @PreAuthorize("@ss.hasPermi('system:history:list')")
    @GetMapping("/list")
    public TableDataInfo list(ViewCouponHistory viewCouponHistory)
    {
        startPage();
        List<ViewCouponHistory> list = viewCouponHistoryService.selectViewCouponHistoryList(viewCouponHistory);
        return getDataTable(list);
    }

    /**
     * 导出VIEW列表
     */
    @PreAuthorize("@ss.hasPermi('system:history:export')")
    @Log(title = "VIEW", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, ViewCouponHistory viewCouponHistory)
    {
        List<ViewCouponHistory> list = viewCouponHistoryService.selectViewCouponHistoryList(viewCouponHistory);
        ExcelUtil<ViewCouponHistory> util = new ExcelUtil<ViewCouponHistory>(ViewCouponHistory.class);
        util.exportExcel(response, list, "VIEW数据");
    }

    /**
     * 获取VIEW详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:history:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(viewCouponHistoryService.selectViewCouponHistoryById(id));
    }

    /**
     * 新增VIEW
     */
    @PreAuthorize("@ss.hasPermi('system:history:add')")
    @Log(title = "VIEW", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ViewCouponHistory viewCouponHistory)
    {
        return toAjax(viewCouponHistoryService.insertViewCouponHistory(viewCouponHistory));
    }

    /**
     * 修改VIEW
     */
    @PreAuthorize("@ss.hasPermi('system:history:edit')")
    @Log(title = "VIEW", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ViewCouponHistory viewCouponHistory)
    {
        return toAjax(viewCouponHistoryService.updateViewCouponHistory(viewCouponHistory));
    }

    /**
     * 删除VIEW
     */
    @PreAuthorize("@ss.hasPermi('system:history:remove')")
    @Log(title = "VIEW", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(viewCouponHistoryService.deleteViewCouponHistoryByIds(ids));
    }
}

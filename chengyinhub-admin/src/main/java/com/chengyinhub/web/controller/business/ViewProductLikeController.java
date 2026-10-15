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
import com.chengyinhub.business.domain.ViewProductLike;
import com.chengyinhub.business.service.IViewProductLikeService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * VIEWController
 * 
 * @author webxh
 * @date 2025-08-30
 */
@RestController
@RequestMapping("/business/viewproductlike")
public class ViewProductLikeController extends BaseController
{
    @Autowired
    private IViewProductLikeService viewProductLikeService;

    /**
     * 查询VIEW列表
     */
    @PreAuthorize("@ss.hasPermi('business:viewproductlike:list')")
    @GetMapping("/list")
    public TableDataInfo list(ViewProductLike viewProductLike)
    {
        startPage();
        List<ViewProductLike> list = viewProductLikeService.selectViewProductLikeList(viewProductLike);
        return getDataTable(list);
    }

    /**
     * 导出VIEW列表
     */
    @PreAuthorize("@ss.hasPermi('business:viewproductlike:export')")
    @Log(title = "VIEW", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, ViewProductLike viewProductLike)
    {
        List<ViewProductLike> list = viewProductLikeService.selectViewProductLikeList(viewProductLike);
        ExcelUtil<ViewProductLike> util = new ExcelUtil<ViewProductLike>(ViewProductLike.class);
        util.exportExcel(response, list, "VIEW数据");
    }

    /**
     * 获取VIEW详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:viewproductlike:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(viewProductLikeService.selectViewProductLikeById(id));
    }

    /**
     * 新增VIEW
     */
    @PreAuthorize("@ss.hasPermi('business:viewproductlike:add')")
    @Log(title = "VIEW", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ViewProductLike viewProductLike)
    {
        return toAjax(viewProductLikeService.insertViewProductLike(viewProductLike));
    }

    /**
     * 修改VIEW
     */
    @PreAuthorize("@ss.hasPermi('business:viewproductlike:edit')")
    @Log(title = "VIEW", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ViewProductLike viewProductLike)
    {
        return toAjax(viewProductLikeService.updateViewProductLike(viewProductLike));
    }

    /**
     * 删除VIEW
     */
    @PreAuthorize("@ss.hasPermi('business:viewproductlike:remove')")
    @Log(title = "VIEW", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(viewProductLikeService.deleteViewProductLikeByIds(ids));
    }
}

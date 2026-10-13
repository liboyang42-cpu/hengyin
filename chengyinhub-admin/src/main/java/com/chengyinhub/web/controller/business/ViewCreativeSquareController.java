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
import com.chengyinhub.business.domain.ViewCreativeSquare;
import com.chengyinhub.business.service.IViewCreativeSquareService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * VIEWController
 * 
 * @author ruoyi
 * @date 2025-08-29
 */
@RestController
@RequestMapping("/business/viewcreativesquare")
public class ViewCreativeSquareController extends BaseController
{
    @Autowired
    private IViewCreativeSquareService viewCreativeSquareService;

    /**
     * 查询VIEW列表
     */
    @PreAuthorize("@ss.hasPermi('business:creativesquare:list')")
    @GetMapping("/list")
    public TableDataInfo list(ViewCreativeSquare viewCreativeSquare)
    {
        startPage();
        List<ViewCreativeSquare> list = viewCreativeSquareService.selectViewCreativeSquareList(viewCreativeSquare);
        return getDataTable(list);
    }

    /**
     * 导出VIEW列表
     */
    @PreAuthorize("@ss.hasPermi('business:creativesquare:export')")
    @Log(title = "VIEW", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, ViewCreativeSquare viewCreativeSquare)
    {
        List<ViewCreativeSquare> list = viewCreativeSquareService.selectViewCreativeSquareList(viewCreativeSquare);
        ExcelUtil<ViewCreativeSquare> util = new ExcelUtil<ViewCreativeSquare>(ViewCreativeSquare.class);
        util.exportExcel(response, list, "VIEW数据");
    }

    /**
     * 获取VIEW详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:creativesquare:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(viewCreativeSquareService.selectViewCreativeSquareById(id));
    }

    /**
     * 新增VIEW
     */
    @PreAuthorize("@ss.hasPermi('business:creativesquare:add')")
    @Log(title = "VIEW", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ViewCreativeSquare viewCreativeSquare)
    {
        return toAjax(viewCreativeSquareService.insertViewCreativeSquare(viewCreativeSquare));
    }

    /**
     * 修改VIEW
     */
    @PreAuthorize("@ss.hasPermi('business:creativesquare:edit')")
    @Log(title = "VIEW", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ViewCreativeSquare viewCreativeSquare)
    {
        return toAjax(viewCreativeSquareService.updateViewCreativeSquare(viewCreativeSquare));
    }

    /**
     * 删除VIEW
     */
    @PreAuthorize("@ss.hasPermi('business:creativesquare:remove')")
    @Log(title = "VIEW", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(viewCreativeSquareService.deleteViewCreativeSquareByIds(ids));
    }
}

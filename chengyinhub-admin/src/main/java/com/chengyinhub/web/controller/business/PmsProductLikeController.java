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
import com.chengyinhub.business.domain.PmsProductLike;
import com.chengyinhub.business.service.IPmsProductLikeService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 商品收藏Controller
 * 
 * @author webxh
 * @date 2025-08-30
 */
@RestController
@RequestMapping("/business/productlike")
public class PmsProductLikeController extends BaseController
{
    @Autowired
    private IPmsProductLikeService pmsProductLikeService;

    /**
     * 查询商品收藏列表
     */
    @PreAuthorize("@ss.hasPermi('business:productlike:list')")
    @GetMapping("/list")
    public TableDataInfo list(PmsProductLike pmsProductLike)
    {
        startPage();
        List<PmsProductLike> list = pmsProductLikeService.selectPmsProductLikeList(pmsProductLike);
        return getDataTable(list);
    }

    /**
     * 导出商品收藏列表
     */
    @PreAuthorize("@ss.hasPermi('business:productlike:export')")
    @Log(title = "商品收藏", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, PmsProductLike pmsProductLike)
    {
        List<PmsProductLike> list = pmsProductLikeService.selectPmsProductLikeList(pmsProductLike);
        ExcelUtil<PmsProductLike> util = new ExcelUtil<PmsProductLike>(PmsProductLike.class);
        util.exportExcel(response, list, "商品收藏数据");
    }

    /**
     * 获取商品收藏详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:productlike:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(pmsProductLikeService.selectPmsProductLikeById(id));
    }

    /**
     * 新增商品收藏
     */
    @PreAuthorize("@ss.hasPermi('business:productlike:add')")
    @Log(title = "商品收藏", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody PmsProductLike pmsProductLike)
    {
        return toAjax(pmsProductLikeService.insertPmsProductLike(pmsProductLike));
    }

    /**
     * 修改商品收藏
     */
    @PreAuthorize("@ss.hasPermi('business:productlike:edit')")
    @Log(title = "商品收藏", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody PmsProductLike pmsProductLike)
    {
        return toAjax(pmsProductLikeService.updatePmsProductLike(pmsProductLike));
    }

    /**
     * 删除商品收藏
     */
    @PreAuthorize("@ss.hasPermi('business:productlike:remove')")
    @Log(title = "商品收藏", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(pmsProductLikeService.deletePmsProductLikeByIds(ids));
    }
}

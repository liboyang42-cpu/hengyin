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
import com.chengyinhub.business.domain.UmsCreativeSquareLike;
import com.chengyinhub.business.service.IUmsCreativeSquareLikeService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 创意广场点赞Controller
 * 
 * @author ruoyi
 * @date 2025-08-29
 */
@RestController
@RequestMapping("/business/creativesquareLike")
public class UmsCreativeSquareLikeController extends BaseController
{
    @Autowired
    private IUmsCreativeSquareLikeService umsCreativeSquareLikeService;

    /**
     * 查询创意广场点赞列表
     */
    @PreAuthorize("@ss.hasPermi('business:creativesquareLike:list')")
    @GetMapping("/list")
    public TableDataInfo list(UmsCreativeSquareLike umsCreativeSquareLike)
    {
        startPage();
        List<UmsCreativeSquareLike> list = umsCreativeSquareLikeService.selectUmsCreativeSquareLikeList(umsCreativeSquareLike);
        return getDataTable(list);
    }

    /**
     * 导出创意广场点赞列表
     */
    @PreAuthorize("@ss.hasPermi('business:creativesquareLike:export')")
    @Log(title = "创意广场点赞", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UmsCreativeSquareLike umsCreativeSquareLike)
    {
        List<UmsCreativeSquareLike> list = umsCreativeSquareLikeService.selectUmsCreativeSquareLikeList(umsCreativeSquareLike);
        ExcelUtil<UmsCreativeSquareLike> util = new ExcelUtil<UmsCreativeSquareLike>(UmsCreativeSquareLike.class);
        util.exportExcel(response, list, "创意广场点赞数据");
    }

    /**
     * 获取创意广场点赞详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:creativesquareLike:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(umsCreativeSquareLikeService.selectUmsCreativeSquareLikeById(id));
    }

    /**
     * 新增创意广场点赞
     */
    @PreAuthorize("@ss.hasPermi('business:creativesquareLike:add')")
    @Log(title = "创意广场点赞", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody UmsCreativeSquareLike umsCreativeSquareLike)
    {
        return toAjax(umsCreativeSquareLikeService.insertUmsCreativeSquareLike(umsCreativeSquareLike));
    }

    /**
     * 修改创意广场点赞
     */
    @PreAuthorize("@ss.hasPermi('business:creativesquareLike:edit')")
    @Log(title = "创意广场点赞", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody UmsCreativeSquareLike umsCreativeSquareLike)
    {
        return toAjax(umsCreativeSquareLikeService.updateUmsCreativeSquareLike(umsCreativeSquareLike));
    }

    /**
     * 删除创意广场点赞
     */
    @PreAuthorize("@ss.hasPermi('business:creativesquareLike:remove')")
    @Log(title = "创意广场点赞", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(umsCreativeSquareLikeService.deleteUmsCreativeSquareLikeByIds(ids));
    }
}

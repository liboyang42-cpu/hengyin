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
import com.chengyinhub.business.domain.CmsActivityLike;
import com.chengyinhub.business.service.ICmsActivityLikeService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 活动点赞Controller
 * 
 * @author webxh
 * @date 2025-08-31
 */
@RestController
@RequestMapping("/business/activitylike")
public class CmsActivityLikeController extends BaseController
{
    @Autowired
    private ICmsActivityLikeService cmsActivityLikeService;

    /**
     * 查询活动点赞列表
     */
    @PreAuthorize("@ss.hasPermi('business:activitylike:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsActivityLike cmsActivityLike)
    {
        startPage();
        List<CmsActivityLike> list = cmsActivityLikeService.selectCmsActivityLikeList(cmsActivityLike);
        return getDataTable(list);
    }

    /**
     * 导出活动点赞列表
     */
    @PreAuthorize("@ss.hasPermi('business:activitylike:export')")
    @Log(title = "活动点赞", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsActivityLike cmsActivityLike)
    {
        List<CmsActivityLike> list = cmsActivityLikeService.selectCmsActivityLikeList(cmsActivityLike);
        ExcelUtil<CmsActivityLike> util = new ExcelUtil<CmsActivityLike>(CmsActivityLike.class);
        util.exportExcel(response, list, "活动点赞数据");
    }

    /**
     * 获取活动点赞详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:activitylike:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(cmsActivityLikeService.selectCmsActivityLikeById(id));
    }

    /**
     * 新增活动点赞
     */
    @PreAuthorize("@ss.hasPermi('business:activitylike:add')")
    @Log(title = "活动点赞", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsActivityLike cmsActivityLike)
    {
        return toAjax(cmsActivityLikeService.insertCmsActivityLike(cmsActivityLike));
    }

    /**
     * 修改活动点赞
     */
    @PreAuthorize("@ss.hasPermi('business:activitylike:edit')")
    @Log(title = "活动点赞", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsActivityLike cmsActivityLike)
    {
        return toAjax(cmsActivityLikeService.updateCmsActivityLike(cmsActivityLike));
    }

    /**
     * 删除活动点赞
     */
    @PreAuthorize("@ss.hasPermi('business:activitylike:remove')")
    @Log(title = "活动点赞", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(cmsActivityLikeService.deleteCmsActivityLikeByIds(ids));
    }
}

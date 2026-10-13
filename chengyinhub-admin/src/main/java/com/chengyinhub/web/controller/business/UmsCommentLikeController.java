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
import com.chengyinhub.business.domain.UmsCommentLike;
import com.chengyinhub.business.service.IUmsCommentLikeService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 评论点赞Controller
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
@RestController
@RequestMapping("/business/commentlike")
public class UmsCommentLikeController extends BaseController
{
    @Autowired
    private IUmsCommentLikeService umsCommentLikeService;

    /**
     * 查询评论点赞列表
     */
    @PreAuthorize("@ss.hasPermi('business:commentlike:list')")
    @GetMapping("/list")
    public TableDataInfo list(UmsCommentLike umsCommentLike)
    {
        startPage();
        List<UmsCommentLike> list = umsCommentLikeService.selectUmsCommentLikeList(umsCommentLike);
        return getDataTable(list);
    }

    /**
     * 导出评论点赞列表
     */
    @PreAuthorize("@ss.hasPermi('business:commentlike:export')")
    @Log(title = "评论点赞", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UmsCommentLike umsCommentLike)
    {
        List<UmsCommentLike> list = umsCommentLikeService.selectUmsCommentLikeList(umsCommentLike);
        ExcelUtil<UmsCommentLike> util = new ExcelUtil<UmsCommentLike>(UmsCommentLike.class);
        util.exportExcel(response, list, "评论点赞数据");
    }

    /**
     * 获取评论点赞详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:commentlike:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(umsCommentLikeService.selectUmsCommentLikeById(id));
    }

    /**
     * 新增评论点赞
     */
    @PreAuthorize("@ss.hasPermi('business:commentlike:add')")
    @Log(title = "评论点赞", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody UmsCommentLike umsCommentLike)
    {
        return toAjax(umsCommentLikeService.insertUmsCommentLike(umsCommentLike));
    }

    /**
     * 修改评论点赞
     */
    @PreAuthorize("@ss.hasPermi('business:commentlike:edit')")
    @Log(title = "评论点赞", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody UmsCommentLike umsCommentLike)
    {
        return toAjax(umsCommentLikeService.updateUmsCommentLike(umsCommentLike));
    }

    /**
     * 删除评论点赞
     */
    @PreAuthorize("@ss.hasPermi('business:commentlike:remove')")
    @Log(title = "评论点赞", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(umsCommentLikeService.deleteUmsCommentLikeByIds(ids));
    }
}

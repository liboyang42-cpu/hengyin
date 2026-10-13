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
import com.chengyinhub.business.domain.ViewComment;
import com.chengyinhub.business.service.IViewCommentService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * VIEWController
 * 
 * @author ruoyi
 * @date 2025-08-29
 */
@RestController
@RequestMapping("/business/viewcomment")
public class ViewCommentController extends BaseController
{
    @Autowired
    private IViewCommentService viewCommentService;

    /**
     * 查询VIEW列表
     */
    @PreAuthorize("@ss.hasPermi('business:comment:list')")
    @GetMapping("/list")
    public TableDataInfo list(ViewComment viewComment)
    {
        startPage();
        List<ViewComment> list = viewCommentService.selectViewCommentList(viewComment);
        return getDataTable(list);
    }

    /**
     * 导出VIEW列表
     */
    @PreAuthorize("@ss.hasPermi('business:comment:export')")
    @Log(title = "VIEW", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, ViewComment viewComment)
    {
        List<ViewComment> list = viewCommentService.selectViewCommentList(viewComment);
        ExcelUtil<ViewComment> util = new ExcelUtil<ViewComment>(ViewComment.class);
        util.exportExcel(response, list, "VIEW数据");
    }

    /**
     * 获取VIEW详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:comment:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(viewCommentService.selectViewCommentById(id));
    }

    /**
     * 新增VIEW
     */
    @PreAuthorize("@ss.hasPermi('business:comment:add')")
    @Log(title = "VIEW", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ViewComment viewComment)
    {
        return toAjax(viewCommentService.insertViewComment(viewComment));
    }

    /**
     * 修改VIEW
     */
    @PreAuthorize("@ss.hasPermi('business:comment:edit')")
    @Log(title = "VIEW", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ViewComment viewComment)
    {
        return toAjax(viewCommentService.updateViewComment(viewComment));
    }

    /**
     * 删除VIEW
     */
    @PreAuthorize("@ss.hasPermi('business:comment:remove')")
    @Log(title = "VIEW", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(viewCommentService.deleteViewCommentByIds(ids));
    }
}

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
import com.chengyinhub.business.domain.ViewCollaborators;
import com.chengyinhub.business.security.AdminPiiProtector;
import com.chengyinhub.business.service.IViewCollaboratorsService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 合作者视图Controller
 * 
 * @author webxh
 * @date 2025-09-04
 */
@RestController
@RequestMapping("/business/viewcollaborators")
public class ViewCollaboratorsController extends BaseController
{
    @Autowired
    private IViewCollaboratorsService viewCollaboratorsService;

    /**
     * 查询合作者视图列表
     */
    @PreAuthorize("@ss.hasPermi('business:viewcollaborators:list')")
    @GetMapping("/list")
    public TableDataInfo list(ViewCollaborators viewCollaborators)
    {
        startPage();
        List<ViewCollaborators> list = viewCollaboratorsService.selectViewCollaboratorsList(viewCollaborators);
        return getDataTable(list);
    }

    /**
     * 导出合作者视图列表
     */
    @PreAuthorize("@ss.hasPermi('business:viewcollaborators:export')")
    @Log(title = "合作者视图", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, ViewCollaborators viewCollaborators)
    {
        List<ViewCollaborators> list = viewCollaboratorsService.selectViewCollaboratorsList(viewCollaborators);
        ExcelUtil<ViewCollaborators> util = new ExcelUtil<ViewCollaborators>(ViewCollaborators.class);
        util.exportExcel(response, list, "合作者视图数据");
    }

    /**
     * 获取合作者视图详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:viewcollaborators:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(viewCollaboratorsService.selectViewCollaboratorsById(id));
    }

    /**
     * 新增合作者视图
     */
    @PreAuthorize("@ss.hasPermi('business:viewcollaborators:add')")
    @Log(title = "合作者视图", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ViewCollaborators viewCollaborators)
    {
        return toAjax(viewCollaboratorsService.insertViewCollaborators(viewCollaborators));
    }

    /**
     * 修改合作者视图
     */
    @PreAuthorize("@ss.hasPermi('business:viewcollaborators:edit')")
    @Log(title = "合作者视图", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ViewCollaborators viewCollaborators)
    {
        // getInfo 返回的 memberPhone/memberRealName 已在 service 层脱敏,原样 PUT 回来会把掩码
        // 写进 view_collaborators 并传导到基表 ums_member,真手机号当场毁掉。这两列由聚合维护,
        // 本就不该经后台编辑写入,故一律清掉。详见 AdminPiiProtector.stripPii(ViewCollaborators)。
        AdminPiiProtector.stripPii(viewCollaborators);
        return toAjax(viewCollaboratorsService.updateViewCollaborators(viewCollaborators));
    }

    /**
     * 删除合作者视图
     */
    @PreAuthorize("@ss.hasPermi('business:viewcollaborators:remove')")
    @Log(title = "合作者视图", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(viewCollaboratorsService.deleteViewCollaboratorsByIds(ids));
    }
}

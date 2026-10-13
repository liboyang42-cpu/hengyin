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
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 用户积分明细Controller
 * 
 * @author ruoyi
 * @date 2025-08-26
 */
@RestController
@RequestMapping("/business/pointsdetail")
public class UmsMemberPointsDetailController extends BaseController
{
    @Autowired
    private IUmsMemberPointsDetailService umsMemberPointsDetailService;

    /**
     * 查询用户积分明细列表
     */
    @PreAuthorize("@ss.hasPermi('business:pointsdetail:list')")
    @GetMapping("/list")
    public TableDataInfo list(UmsMemberPointsDetail umsMemberPointsDetail)
    {
        umsMemberPointsDetail.setDelFlag(0);
        startPage();
        List<UmsMemberPointsDetail> list = umsMemberPointsDetailService.selectUmsMemberPointsDetailList(umsMemberPointsDetail);
        return getDataTable(list);
    }

    /**
     * 导出用户积分明细列表
     */
    @PreAuthorize("@ss.hasPermi('business:pointsdetail:export')")
    @Log(title = "用户积分明细", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UmsMemberPointsDetail umsMemberPointsDetail)
    {
        umsMemberPointsDetail.setDelFlag(0);
        List<UmsMemberPointsDetail> list = umsMemberPointsDetailService.selectUmsMemberPointsDetailList(umsMemberPointsDetail);
        ExcelUtil<UmsMemberPointsDetail> util = new ExcelUtil<UmsMemberPointsDetail>(UmsMemberPointsDetail.class);
        util.exportExcel(response, list, "用户积分明细数据");
    }

    /**
     * 获取用户积分明细详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:pointsdetail:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(umsMemberPointsDetailService.selectUmsMemberPointsDetailById(id));
    }

    /**
     * 新增用户积分明细
     */
    @PreAuthorize("@ss.hasPermi('business:pointsdetail:add')")
    @Log(title = "用户积分明细", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody UmsMemberPointsDetail umsMemberPointsDetail)
    {
        return toAjax(umsMemberPointsDetailService.insertUmsMemberPointsDetail(umsMemberPointsDetail));
    }

    /**
     * 修改用户积分明细
     */
    @PreAuthorize("@ss.hasPermi('business:pointsdetail:edit')")
    @Log(title = "用户积分明细", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody UmsMemberPointsDetail umsMemberPointsDetail)
    {
        return toAjax(umsMemberPointsDetailService.updateUmsMemberPointsDetail(umsMemberPointsDetail));
    }

    /**
     * 删除用户积分明细
     */
    @PreAuthorize("@ss.hasPermi('business:pointsdetail:remove')")
    @Log(title = "用户积分明细", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(umsMemberPointsDetailService.deleteUmsMemberPointsDetailByIds(ids));
    }
}

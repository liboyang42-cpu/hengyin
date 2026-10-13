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
import com.chengyinhub.business.domain.UmsCrowdfunding;
import com.chengyinhub.business.service.IUmsCrowdfundingService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 用户众筹Controller
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
@RestController
@RequestMapping("/business/crowdfunding")
public class UmsCrowdfundingController extends BaseController
{
    @Autowired
    private IUmsCrowdfundingService umsCrowdfundingService;

    /**
     * 查询用户众筹列表
     */
    @PreAuthorize("@ss.hasPermi('business:crowdfunding:list')")
    @GetMapping("/list")
    public TableDataInfo list(UmsCrowdfunding umsCrowdfunding)
    {
        startPage();
        List<UmsCrowdfunding> list = umsCrowdfundingService.selectUmsCrowdfundingList(umsCrowdfunding);
        return getDataTable(list);
    }

    /**
     * 导出用户众筹列表
     */
    @PreAuthorize("@ss.hasPermi('business:crowdfunding:export')")
    @Log(title = "用户众筹", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UmsCrowdfunding umsCrowdfunding)
    {
        List<UmsCrowdfunding> list = umsCrowdfundingService.selectUmsCrowdfundingList(umsCrowdfunding);
        ExcelUtil<UmsCrowdfunding> util = new ExcelUtil<UmsCrowdfunding>(UmsCrowdfunding.class);
        util.exportExcel(response, list, "用户众筹数据");
    }

    /**
     * 获取用户众筹详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:crowdfunding:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(umsCrowdfundingService.selectUmsCrowdfundingById(id));
    }

    /**
     * 新增用户众筹
     */
    @PreAuthorize("@ss.hasPermi('business:crowdfunding:add')")
    @Log(title = "用户众筹", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody UmsCrowdfunding umsCrowdfunding)
    {
        return toAjax(umsCrowdfundingService.insertUmsCrowdfunding(umsCrowdfunding));
    }

    /**
     * 修改用户众筹
     */
    @PreAuthorize("@ss.hasPermi('business:crowdfunding:edit')")
    @Log(title = "用户众筹", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody UmsCrowdfunding umsCrowdfunding)
    {
        return toAjax(umsCrowdfundingService.updateUmsCrowdfunding(umsCrowdfunding));
    }

    /**
     * 删除用户众筹
     */
    @PreAuthorize("@ss.hasPermi('business:crowdfunding:remove')")
    @Log(title = "用户众筹", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(umsCrowdfundingService.deleteUmsCrowdfundingByIds(ids));
    }
}

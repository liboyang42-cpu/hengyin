package com.chengyinhub.web.controller.business;

import java.util.List;
import javax.servlet.http.HttpServletResponse;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.ITemplatePublishService;
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
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.service.ICmsTemplateLibraryService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 模板库Controller
 * 
 * @author webxh
 * @date 2025-09-02
 */
@RestController
@RequestMapping("/business/templatelibrary")
public class CmsTemplateLibraryController extends BaseController
{
    @Autowired
    private ICmsTemplateLibraryService cmsTemplateLibraryService;


    @Autowired
    private ISmsCouponService smsCouponService;

    @Autowired
    private ITemplatePublishService templatePublishService;

    /**
     * 查询模板库列表
     */
    @PreAuthorize("@ss.hasPermi('business:templatelibrary:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsTemplateLibrary cmsTemplateLibrary)
    {
        startPage();
        cmsTemplateLibrary.setDelFlag(0);
        List<CmsTemplateLibrary> list = cmsTemplateLibraryService.selectCmsTemplateLibraryList(cmsTemplateLibrary);
        return getDataTable(list);
    }

    /**
     * 导出模板库列表
     */
    @PreAuthorize("@ss.hasPermi('business:templatelibrary:export')")
    @Log(title = "模板库", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsTemplateLibrary cmsTemplateLibrary)
    {
        List<CmsTemplateLibrary> list = cmsTemplateLibraryService.selectCmsTemplateLibraryList(cmsTemplateLibrary);
        ExcelUtil<CmsTemplateLibrary> util = new ExcelUtil<CmsTemplateLibrary>(CmsTemplateLibrary.class);
        util.exportExcel(response, list, "模板库数据");
    }

    /**
     * 获取模板库详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:templatelibrary:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {

        CmsTemplateLibrary cmsTemplateLibrary= cmsTemplateLibraryService.selectCmsTemplateLibraryById(id);

        if (cmsTemplateLibrary!=null){
            if(cmsTemplateLibrary.getCouponId()>0){
                SmsCoupon smsCoupon=   smsCouponService.selectSmsCouponById(cmsTemplateLibrary.getCouponId());
                cmsTemplateLibrary.setSmsCoupon(smsCoupon);
            }
        }
        return success(cmsTemplateLibrary);
    }

    /**
     * 新增模板库
     */
    @PreAuthorize("@ss.hasPermi('business:templatelibrary:add')")
    @Log(title = "模板库", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsTemplateLibrary cmsTemplateLibrary)
    {
        return AjaxResult.error(HttpStatus.FORBIDDEN, "模板库仅接受关联模板真源的审核投影，不能独立创建");
    }

    /**
     * 修改模板库
     */
    @PreAuthorize("@ss.hasPermi('business:templatelibrary:edit')")
    @Log(title = "模板库", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsTemplateLibrary cmsTemplateLibrary)
    {
        return toAjax(templatePublishService.updateTemplateFromLibraryProjection(cmsTemplateLibrary, getUsername()));
    }

    /**
     * 删除模板库
     */
    @PreAuthorize("@ss.hasPermi('business:templatelibrary:remove')")
    @Log(title = "模板库", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(templatePublishService.deleteTemplateLibraryProjections(ids, getUsername()));
    }
}

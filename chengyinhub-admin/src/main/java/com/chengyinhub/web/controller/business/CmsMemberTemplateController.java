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
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 我的模板Controller
 * 
 * @author webxh
 * @date 2025-09-02
 */
@RestController
@RequestMapping("/business/membertemplate")
public class CmsMemberTemplateController extends BaseController
{
    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;

    @Autowired
    private ISmsCouponService smsCouponService;

    @Autowired
    private ITemplatePublishService templatePublishService;

    /**
     * 查询我的模板列表
     */
    @PreAuthorize("@ss.hasPermi('business:membertemplate:list')")
    @GetMapping("/list")
    public TableDataInfo list(CmsMemberTemplate cmsMemberTemplate)
    {
        startPage();
        List<CmsMemberTemplate> list = cmsMemberTemplateService.selectCmsMemberTemplateList(cmsMemberTemplate);
        return getDataTable(list);
    }

    /**
     * 导出我的模板列表
     */
    @PreAuthorize("@ss.hasPermi('business:membertemplate:export')")
    @Log(title = "我的模板", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, CmsMemberTemplate cmsMemberTemplate)
    {
        List<CmsMemberTemplate> list = cmsMemberTemplateService.selectCmsMemberTemplateList(cmsMemberTemplate);
        ExcelUtil<CmsMemberTemplate> util = new ExcelUtil<CmsMemberTemplate>(CmsMemberTemplate.class);
        util.exportExcel(response, list, "我的模板数据");
    }

    /**
     * 获取我的模板详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:membertemplate:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {

        CmsMemberTemplate cmsTemplateLibrary= cmsMemberTemplateService.selectCmsMemberTemplateById(id);

        if (cmsTemplateLibrary!=null){
            if(cmsTemplateLibrary.getCouponId()>0){
                SmsCoupon smsCoupon=   smsCouponService.selectSmsCouponById(cmsTemplateLibrary.getCouponId());
                cmsTemplateLibrary.setSmsCoupon(smsCoupon);
            }
        }
        return success(cmsTemplateLibrary);
    }

    /**
     * 新增我的模板
     */
    @PreAuthorize("@ss.hasPermi('business:membertemplate:add')")
    @Log(title = "我的模板", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody CmsMemberTemplate cmsMemberTemplate)
    {
        return toAjax(templatePublishService.createAdminTemplate(cmsMemberTemplate, getUsername()));
    }

    /**
     * 修改我的模板
     */
    @PreAuthorize("@ss.hasPermi('business:membertemplate:edit')")
    @Log(title = "我的模板", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CmsMemberTemplate cmsMemberTemplate)
    {
        return toAjax(templatePublishService.updateAdminTemplate(cmsMemberTemplate, getUsername()));
    }

    /** 把存量模板送入统一审核台；已有未结任务时复用。 */
    @PreAuthorize("@ss.hasPermi('business:membertemplate:edit')")
    @Log(title = "我的模板-提交统一审核", businessType = BusinessType.INSERT)
    @PostMapping("/{id}/review")
    public AjaxResult submitReview(@PathVariable Long id)
    {
        return success(templatePublishService.submitTemplateForReview(id));
    }

    /**
     * 删除我的模板
     */
    @PreAuthorize("@ss.hasPermi('business:membertemplate:remove')")
    @Log(title = "我的模板", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(templatePublishService.deleteAdminTemplates(ids));
    }
}

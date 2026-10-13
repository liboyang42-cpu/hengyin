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
import org.springframework.web.multipart.MultipartFile;

import com.chengyinhub.business.domain.MerchantLead;
import com.chengyinhub.business.domain.dto.MerchantLeadImportResult;
import com.chengyinhub.business.domain.dto.MerchantLeadImportRow;
import com.chengyinhub.business.service.IMerchantLeadService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.utils.poi.ExcelUtil;

/**
 * 商家目录/线索(含未注册商家)后台管理。
 */
@RestController
@RequestMapping("/business/merchantlead")
public class MerchantLeadController extends BaseController {

    @Autowired
    private IMerchantLeadService merchantLeadService;

    @Autowired
    private MerchantLeadImportParser merchantLeadImportParser;

    @PreAuthorize("@ss.hasPermi('business:merchantlead:list')")
    @GetMapping("/list")
    public TableDataInfo list(MerchantLead merchantLead) {
        startPage();
        List<MerchantLead> list = merchantLeadService.selectMerchantLeadList(merchantLead);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:merchantlead:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(merchantLeadService.selectMerchantLeadById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:merchantlead:add')")
    @Log(title = "商家目录", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody MerchantLead merchantLead) {
        return toAjax(merchantLeadService.insertMerchantLead(merchantLead));
    }

    @PreAuthorize("@ss.hasPermi('business:merchantlead:add')")
    @Log(title = "商家目录批量导入", businessType = BusinessType.IMPORT)
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file) throws Exception {
        List<MerchantLeadImportRow> rows = merchantLeadImportParser.parse(file);
        MerchantLeadImportResult result = merchantLeadService.importMerchantLeads(rows);
        String message = "导入完成：成功 " + result.getSuccessCount() + " 行，失败 " + result.getFailureCount() + " 行";
        return AjaxResult.success(message, result);
    }

    @PreAuthorize("@ss.hasPermi('business:merchantlead:add')")
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil<MerchantLead> util = new ExcelUtil<>(MerchantLead.class);
        util.importTemplateExcel(response, "商家线索导入模板");
    }

    @PreAuthorize("@ss.hasPermi('business:merchantlead:edit')")
    @Log(title = "商家目录", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody MerchantLead merchantLead) {
        return toAjax(merchantLeadService.updateMerchantLead(merchantLead));
    }

    @PreAuthorize("@ss.hasPermi('business:merchantlead:remove')")
    @Log(title = "商家目录", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(merchantLeadService.deleteMerchantLeadByIds(ids));
    }

}

package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.RoamAiContent;
import com.chengyinhub.business.service.RoamAiContentAdminService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 漫游无形陪伴 V1 内容审核后台；业务状态机在 RoamAiContentAdminService。 */
@RestController
@RequestMapping("/business/roamAiContent")
public class CmsRoamAiContentController extends BaseController {

    @Autowired
    private RoamAiContentAdminService roamAiContentAdminService;

    @PreAuthorize("@ss.hasPermi('business:roamAiContent:list')")
    @GetMapping("/list")
    public AjaxResult list(String contentType, Integer auditStatus, Long poiId) {
        return success(roamAiContentAdminService.list(contentType, auditStatus, poiId));
    }

    @PreAuthorize("@ss.hasPermi('business:roamAiContent:query')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable Long id) {
        return roamAiContentAdminService.getInfo(id);
    }

    @PreAuthorize("@ss.hasPermi('business:roamAiContent:add')")
    @Log(title = "漫游陪伴内容", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody RoamAiContent content) {
        return roamAiContentAdminService.add(content);
    }

    @PreAuthorize("@ss.hasPermi('business:roamAiContent:edit')")
    @Log(title = "漫游陪伴内容", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody RoamAiContent content) {
        return roamAiContentAdminService.edit(content);
    }

    @PreAuthorize("@ss.hasPermi('business:roamAiContent:audit')")
    @Log(title = "漫游陪伴内容审核", businessType = BusinessType.UPDATE)
    @PutMapping("/audit/{id}/{status}")
    public AjaxResult audit(@PathVariable Long id, @PathVariable Integer status) {
        return roamAiContentAdminService.audit(id, status);
    }

    @PreAuthorize("@ss.hasPermi('business:roamAiContent:remove')")
    @Log(title = "漫游陪伴内容", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable Long id) {
        return roamAiContentAdminService.remove(id);
    }
}

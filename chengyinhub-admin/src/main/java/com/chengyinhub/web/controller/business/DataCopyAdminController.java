package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.MemberDataCopyRequest;
import com.chengyinhub.business.service.IMemberDataCopyService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.BusinessType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * ONB-10 (PIPL Art. 45) back office: the queue of members' data-copy requests.
 * List is {@code business:dataCopy:list}; status changes are {@code business:dataCopy:edit} and go to sys_oper_log.
 * No Vue page yet: the menu row is created hidden by migration_20261116_x_disclosures_data_copy.
 */
@RestController
@Tag(name = "个人信息副本申请(后台)")
@RequestMapping("/business/dataCopy")
public class DataCopyAdminController extends BaseController {

    @Autowired
    private IMemberDataCopyService dataCopyService;

    @Operation(summary = "申请列表(status 可选:PENDING/PROCESSING/DELIVERED/REJECTED)")
    @PreAuthorize("@ss.hasPermi('business:dataCopy:list')")
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam(required = false) String status) {
        startPage();
        List<MemberDataCopyRequest> list = dataCopyService.list(status);
        return getDataTable(list);
    }

    @Operation(summary = "更新申请状态(PENDING→PROCESSING→DELIVERED,或→REJECTED 并写明原因)")
    @PreAuthorize("@ss.hasPermi('business:dataCopy:edit')")
    @Log(title = "个人信息副本申请", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/status")
    public AjaxResult changeStatus(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        String status = body == null ? null : Convert.toStr(body.get("status"));
        String note = body == null ? null : Convert.toStr(body.get("note"));
        return success(dataCopyService.changeStatus(id, status, note, getUserId()));
    }
}

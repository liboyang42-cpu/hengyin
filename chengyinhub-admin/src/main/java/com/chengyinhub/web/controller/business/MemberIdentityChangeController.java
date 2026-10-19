package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.impl.MemberIdentityChangeService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * X036 发布者实名改绑人工审核(后台)。
 *
 * <p>列表与入参都不含姓名与身份证号:审核依据是申请原因、线下沟通与「新证是否已绑其他账号」的盲索引核对,
 * 所以这里挂 {@code @Log} 不会把 PII 写进 sys_oper_log(入参只有申请单 id 与审核意见)。
 */
@RestController
@RequestMapping("/business/identity-change")
public class MemberIdentityChangeController extends BaseController {

    @Autowired
    private MemberIdentityChangeService memberIdentityChangeService;

    @PreAuthorize("@ss.hasPermi('business:identity:review')")
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam(required = false) String status) {
        startPage();
        return getDataTable(memberIdentityChangeService.adminList(status));
    }

    @PreAuthorize("@ss.hasPermi('business:identity:review')")
    @Log(title = "发布者实名改绑审核通过", businessType = BusinessType.UPDATE)
    @PostMapping("/approve")
    public AjaxResult approve(@RequestParam("id") Long id, @RequestParam(value = "note", required = false) String note) {
        memberIdentityChangeService.approve(getUserId(), id, note);
        return AjaxResult.success("已通过,新实名已生效,旧登记失效");
    }

    @PreAuthorize("@ss.hasPermi('business:identity:review')")
    @Log(title = "发布者实名改绑审核驳回", businessType = BusinessType.UPDATE)
    @PostMapping("/reject")
    public AjaxResult reject(@RequestParam("id") Long id, @RequestParam("note") String note) {
        memberIdentityChangeService.reject(getUserId(), id, note);
        return AjaxResult.success("已驳回");
    }
}

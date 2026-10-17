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
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.business.domain.UmsMemberWithdrawal;
import com.chengyinhub.business.domain.dto.WithdrawalPayoutDTO;
import com.chengyinhub.business.domain.dto.WithdrawalReviewDTO;
import com.chengyinhub.business.service.IUmsMemberWithdrawalService;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 用户提现申请Controller
 * 
 * @author webxh
 * @date 2025-10-20
 */
@RestController
@RequestMapping("/business/memberwithdrawal")
public class UmsMemberWithdrawalController extends BaseController
{
    @Autowired
    private IUmsMemberWithdrawalService umsMemberWithdrawalService;

    /**
     * 查询用户提现申请列表
     */
    @PreAuthorize("@ss.hasPermi('business:memberwithdrawal:list')")
    @GetMapping("/list")
    public TableDataInfo list(UmsMemberWithdrawal umsMemberWithdrawal)
    {
        startPage();
        List<UmsMemberWithdrawal> list = umsMemberWithdrawalService.selectUmsMemberWithdrawalList(umsMemberWithdrawal);
        return getDataTable(list);
    }

    /**
     * 导出用户提现申请列表
     */
    @PreAuthorize("@ss.hasPermi('business:memberwithdrawal:export')")
    @Log(title = "用户提现申请", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, UmsMemberWithdrawal umsMemberWithdrawal)
    {
        List<UmsMemberWithdrawal> list = umsMemberWithdrawalService.selectUmsMemberWithdrawalList(umsMemberWithdrawal);
        ExcelUtil<UmsMemberWithdrawal> util = new ExcelUtil<UmsMemberWithdrawal>(UmsMemberWithdrawal.class);
        util.exportExcel(response, list, "用户提现申请数据");
    }

    /**
     * 获取用户提现申请详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:memberwithdrawal:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(umsMemberWithdrawalService.selectUmsMemberWithdrawalById(id));
    }

    /**
     * 查看提现真实收款资料（解密），供财务打款。列表/详情/导出一律脱敏，仅此接口返回明文；
     * 权限同审核，且每次调用写审计日志。
     */
    @PreAuthorize("@ss.hasPermi('business:memberwithdrawal:viewpii')")
    @Log(title = "查看提现收款资料", businessType = BusinessType.OTHER)
    @GetMapping(value = "/payout/{id}")
    public AjaxResult payoutInfo(@PathVariable("id") Long id)
    {
        return success(umsMemberWithdrawalService.selectWithdrawalPayoutInfo(id));
    }

    /**
     * 新增用户提现申请
     */
    @PreAuthorize("@ss.hasPermi('business:memberwithdrawal:add')")
    @Log(title = "用户提现申请", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody UmsMemberWithdrawal umsMemberWithdrawal)
    {
        return AjaxResult.error(HttpStatus.FORBIDDEN, "提现申请仅允许用户端提交");
    }

    /**
     * 修改用户提现申请
     */
    @PreAuthorize("@ss.hasPermi('business:memberwithdrawal:edit')")
    @Log(title = "用户提现申请", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody UmsMemberWithdrawal umsMemberWithdrawal)
    {
        return AjaxResult.error(HttpStatus.FORBIDDEN, "提现资料不支持后台编辑，请使用审核接口");
    }

    /**
     * 审核用户提现申请。财务审核必须走状态机，避免重复驳回导致重复退款。
     */
    @PreAuthorize("@ss.hasPermi('business:memberwithdrawal:audit')")
    @Log(title = "用户提现申请审核", businessType = BusinessType.UPDATE)
    @PutMapping("/review")
    public AjaxResult review(@RequestBody WithdrawalReviewDTO request)
    {
        // A1-2 残余(问责):以服务端登录态记录审批人到 audit_by,别再传 null 让审批人身份丢失
        // (照 RefundController.add 的 getUserId() 范式;若日后审批改为自动触发打款,这里正是 maker≠checker 的落点)。
        return toAjax(umsMemberWithdrawalService.reviewWithdrawal(request, getUserId()));
    }

    /**
     * 审核通过后确认银行侧已打款。沿用审核权限，不增加第二人确认。
     */
    @PreAuthorize("@ss.hasPermi('business:memberwithdrawal:audit')")
    @Log(title = "用户提现已打款确认", businessType = BusinessType.UPDATE)
    @PutMapping("/payout")
    public AjaxResult confirmPayout(@RequestBody WithdrawalPayoutDTO request)
    {
        return toAjax(umsMemberWithdrawalService.confirmPayout(request, getUserId()));
    }

    /**
     * 提现申请是财务审计记录，不允许删除。
     */
    @PreAuthorize("@ss.hasPermi('business:memberwithdrawal:remove')")
    @Log(title = "用户提现申请", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return AjaxResult.error(HttpStatus.FORBIDDEN, "提现申请不支持删除，请通过审核流程处理");
    }
}

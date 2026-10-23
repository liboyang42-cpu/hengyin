package com.chengyinhub.web.controller.business;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.service.ICashWithdrawService;

/**
 * 微信零钱提现(通道A)后台管理 + 人工补单队列。
 * status:0处理中 1成功 2失败已退款 3失败待人工 4人工回补待复核 5未扣款(终态,不进人工回补)。
 */
@RestController
@RequestMapping("/business/cashwithdraw")
public class CashWithdrawAdminController extends BaseController {

    @Autowired
    private ICashWithdrawService cashWithdrawService;

    /** 列表(可按 status 过滤,status=3 即人工补单队列) */
    @PreAuthorize("@ss.hasPermi('business:cashwithdraw:list')")
    @GetMapping("/list")
    public TableDataInfo list(MemberCashWithdraw query) {
        startPage();
        return getDataTable(cashWithdrawService.adminList(query));
    }

    /** 详情 */
    @PreAuthorize("@ss.hasPermi('business:cashwithdraw:query')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(cashWithdrawService.getById(id));
    }

    /** 手动重新查询微信最终状态(用于卡在处理中的单)。安全操作,不移动资金。 */
    @PreAuthorize("@ss.hasPermi('business:cashwithdraw:manage')")
    @Log(title = "提现手动重查", businessType = BusinessType.UPDATE)
    @PostMapping("/requery/{id}")
    public AjaxResult requery(@PathVariable("id") Long id) {
        return success(cashWithdrawService.requery(id));
    }

    /** 人工补单：扣款及渠道事实校验通过后，第一人登记(3→4)，第二个不同账号复核回补(4→2)。 */
    @PreAuthorize("@ss.hasPermi('business:cashwithdraw:manage')")
    @Log(title = "提现人工补单", businessType = BusinessType.UPDATE)
    @PostMapping("/manual-refund")
    public AjaxResult manualRefund(@RequestBody MemberCashWithdraw body) {
        if (body == null || body.getId() == null || body.getStatus() == null) {
            return AjaxResult.error("缺少提现单 id 或确认状态");
        }
        if (body.getStatus() != MemberCashWithdraw.STATUS_FAILED_MANUAL
                && body.getStatus() != MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW) {
            return AjaxResult.error("确认状态仅支持待人工或待复核");
        }
        if (StringUtils.isBlank(body.getManualNote())) {
            return AjaxResult.error("请填写处理说明");
        }
        MemberCashWithdraw before = cashWithdrawService.getById(body.getId());
        if (before == null || !body.getStatus().equals(before.getStatus())) {
            return AjaxResult.error("状态已变化，请刷新后重新确认");
        }
        boolean verifying = body.getStatus() == MemberCashWithdraw.STATUS_REFUND_PENDING_REVIEW;
        boolean ok;
        try {
            ok = cashWithdrawService.manualRefund(body.getId(), getUsername(), body.getManualNote(), body.getStatus());
        } catch (IllegalStateException investigation) {
            return AjaxResult.error(investigation.getMessage());
        }
        if (!ok) {
            return AjaxResult.error("状态已变化或回补失败，请刷新后重试");
        }
        return verifying ? AjaxResult.success("复核完成，余额已退回")
                : AjaxResult.success("已登记，等待另一名财务复核");
    }

    /** 冻结:暂停该提现单的自动化处理(定时查询/自动退款均跳过),不移动任何资金。 */
    @PreAuthorize("@ss.hasPermi('business:cashwithdraw:freeze')")
    @Log(title = "提现冻结", businessType = BusinessType.UPDATE)
    @PostMapping("/freeze")
    public AjaxResult freeze(@RequestBody MemberCashWithdraw body) {
        if (body == null || body.getId() == null) {
            return AjaxResult.error("缺少提现单 id");
        }
        if (StringUtils.isBlank(body.getFrozenReason())) {
            return AjaxResult.error("请填写冻结原因");
        }
        try {
            return cashWithdrawService.freezeWithdraw(body.getId(), getUsername(), body.getFrozenReason())
                    ? AjaxResult.success("已冻结")
                    : AjaxResult.error("冻结失败:该提现单已在冻结中");
        } catch (IllegalStateException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /** 解冻:恢复该提现单的自动化处理。 */
    @PreAuthorize("@ss.hasPermi('business:cashwithdraw:freeze')")
    @Log(title = "提现解冻", businessType = BusinessType.UPDATE)
    @PostMapping("/unfreeze/{id}")
    public AjaxResult unfreeze(@PathVariable("id") Long id) {
        try {
            return cashWithdrawService.unfreezeWithdraw(id, getUsername())
                    ? AjaxResult.success("已解冻")
                    : AjaxResult.error("解冻失败:该提现单本就未冻结");
        } catch (IllegalStateException e) {
            return AjaxResult.error(e.getMessage());
        }
    }
}

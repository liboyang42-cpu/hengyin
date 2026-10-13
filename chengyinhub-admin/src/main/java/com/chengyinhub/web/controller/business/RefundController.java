package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.ManualPayoutDTO;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/business/refund")
public class RefundController extends BaseController {
    @Autowired
    private IRefundService refundService;

    @Autowired
    private com.chengyinhub.business.service.IWechatPayService wechatPayService;

    @Autowired
    private com.chengyinhub.business.service.IRefundPayoutService refundPayoutService;

    @PreAuthorize("@ss.hasPermi('business:refund:list')")
    @GetMapping("/list")
    public TableDataInfo list(RefundApplication query) {
        startPage();
        return getDataTable(refundService.selectRefundList(query));
    }

    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @Log(title = "退款申请", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody RefundApplication refund) {
        // A1-1(P0):记后台建单人,供 reviewRefund 的 maker≠checker 闸判定。
        // 客户端传入的 createdByUserId 一律以服务端登录态覆盖,防伪造成"别人建的"来绕过双人。
        refund.setCreatedByUserId(getUserId());
        return toAjax(refundService.saveBackOfficeRefund(refund));
    }

    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @Log(title = "退款审核", businessType = BusinessType.UPDATE)
    @PutMapping("/review")
    public AjaxResult review(@RequestBody RefundReviewDTO request) {
        // HF-03(A-31): 记录真实审核人,退款为移动真金的动作必须可追责(原传 null 导致 reviewer_id 恒为空)
        int rows = refundService.reviewRefund(request, getUserId());
        // [D-2] 审核通过后真正把钱退出去。reviewRefund 只动账(审核态/分账冲销/标记报名),不动钱 ——
        // 此处不接线的后果:运营点「通过」,用户 payment_status=4、分账已冲、库存已回补,钱一分没退,
        // 且 payout_status 永远停在 0(处理中),待人工队列也捞不到它,零告警。
        // 打款刻意放在事务外(controller 层):reviewRefund 是 @Transactional,微信 HTTP 调用不该进它的事务。
        if (rows > 0 && Integer.valueOf(1).equals(request.getStatus())) {
            refundPayoutService.payoutAfterReview(request.getId());
        }
        return toAjax(rows);
    }

    // ===== §6.1 退款失败人工兜底 =====

    /** 人工退款待办队列(原路失败待人工 / 人工已退待复核)。 */
    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @GetMapping("/manual/list")
    public TableDataInfo manualList() {
        startPage();
        return getDataTable(refundService.selectManualPayoutQueue());
    }

    /** 处理人:登记人工退款方式+回执 → 待复核(2→3)。 */
    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @Log(title = "退款人工处理", businessType = BusinessType.UPDATE)
    @PostMapping("/manual/handle")
    public AjaxResult manualHandle(@RequestBody ManualPayoutDTO dto) {
        if (dto == null || dto.getRefundId() == null) {
            return error("退款单不能为空");
        }
        int rows;
        try {
            rows = refundService.handleManualPayout(dto.getRefundId(), dto.getMethod(),
                    dto.getReceiptUrl(), dto.getRemark(), getUserId(), dto.getLeaseToken());
        } catch (IllegalArgumentException e) {
            // [R6] 回执 URL 等入参合同由服务端权威判定;这里只把原因如实回给财务,不做兜底改写
            return error(e.getMessage());
        }
        return rows > 0 ? success("已登记,待复核") : error("退款单状态已变化(非待人工),请刷新");
    }

    /** 钱移动前先领取 owner+lease+fence；两名操作员只能有一人成功。 */
    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @PostMapping("/manual/claim")
    public AjaxResult manualClaim(@RequestBody ManualPayoutDTO dto) {
        if (dto == null || dto.getRefundId() == null) {
            return error("退款单不能为空");
        }
        RefundApplication refund = refundService.getRefundById(dto.getRefundId());
        if (refund == null) {
            return error("退款单不存在");
        }
        // 先问微信自己产生的终态，再签发人工钱移动授权。UNKNOWN/null 时从未授予 token，不能先付款再查。
        // [H-A] refund_no 为空 ⟹ 本单从未向微信提交过原路退款(submitRefund 全仓唯一调用点在派发
        // 编排里,必须先把本行 refund_no 持久化成预期值才会发起,且 refund_no 一旦写入不会被清空)。
        // 业务号冲突单正是这样进的人工队列:拿 null 去问微信恒无终态,会把它永久挡在人工处理之外,
        // 而占号方已写进 payout_remark 供人工核对 —— 只有已提交过微信的单才需要终态闸。
        if (hasSubmittedRefundNo(refund)) {
            String wxStatus = wechatPayService.refreshPayoutFromWx(refund.getRefundNo());
            if (!isDefinitiveManualPayoutStatus(wxStatus)) {
                if ("SUCCESS".equalsIgnoreCase(wxStatus)) {
                    return error("微信原路退款已到账,无需人工再退(已自动刷新状态)");
                }
                if ("PROCESSING".equalsIgnoreCase(wxStatus)) {
                    return error("微信原路退款处理中,禁止人工再退,请待其终态");
                }
                return error("微信退款终态无法确认,禁止人工打款");
            }
        }
        String token = refundService.claimManualPayout(dto.getRefundId(), getUserId());
        return token == null ? error("退款单已被其他操作员领取")
                : AjaxResult.success("已领取人工处理权", token);
    }

    /**
     * 放弃人工处理权，把单子放回队列。释放不动钱，所以过期也允许释放 ——
     * 旧合同「claim 要求 token 为空、release 又要求未过期」会让过期后的单永久卡死。
     */
    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @Log(title = "退款人工处理权释放", businessType = BusinessType.UPDATE)
    @PostMapping("/manual/release")
    public AjaxResult manualRelease(@RequestBody ManualPayoutDTO dto) {
        if (dto == null || dto.getRefundId() == null) {
            return error("退款单不能为空");
        }
        return refundService.releaseManualPayoutClaim(dto.getRefundId(), getUserId(), dto.getLeaseToken())
                ? success("已释放人工处理权")
                : error("释放失败:处理权不属于你或已变更,请刷新");
    }

    /**
     * 接管他人已过期的人工处理权。和 claim 走同一条「先问微信终态再签发 token」的闸；
     * 权限与 claim 同级(business:refund:review),但只对已过期租约生效并把前一个 owner 写进备注留痕。
     */
    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @Log(title = "退款人工处理权接管", businessType = BusinessType.UPDATE)
    @PostMapping("/manual/takeover")
    public AjaxResult manualTakeover(@RequestBody ManualPayoutDTO dto) {
        if (dto == null || dto.getRefundId() == null) {
            return error("退款单不能为空");
        }
        RefundApplication refund = refundService.getRefundById(dto.getRefundId());
        if (refund == null) {
            return error("退款单不存在");
        }
        // [H-A] 与 claim 同一条不变量:无 refund_no = 从未提交微信,终态闸没有可查询的对象。
        if (hasSubmittedRefundNo(refund)) {
            String wxStatus = wechatPayService.refreshPayoutFromWx(refund.getRefundNo());
            if (!isDefinitiveManualPayoutStatus(wxStatus)) {
                return error("微信退款终态无法确认,禁止接管后人工打款");
            }
        }
        String token = refundService.takeoverExpiredManualPayout(dto.getRefundId(), getUserId());
        return token == null ? error("接管失败:租约未过期、无人持有或本就是你自己")
                : AjaxResult.success("已接管人工处理权", token);
    }

    private boolean isDefinitiveManualPayoutStatus(String wxStatus) {
        return "CLOSED".equalsIgnoreCase(wxStatus) || "ABNORMAL".equalsIgnoreCase(wxStatus);
    }

    private boolean hasSubmittedRefundNo(RefundApplication refund) {
        return refund.getRefundNo() != null && !refund.getRefundNo().trim().isEmpty();
    }

    /** 复核人:双人复核确认 → 退款闭环(3→4);复核人须≠处理人。 */
    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @Log(title = "退款双人复核", businessType = BusinessType.UPDATE)
    @PostMapping("/manual/verify")
    public AjaxResult manualVerify(@RequestBody ManualPayoutDTO dto) {
        if (dto == null || dto.getRefundId() == null) {
            return error("退款单不能为空");
        }
        try {
            int rows = refundService.verifyManualPayout(dto.getRefundId(), getUserId());
            return rows > 0 ? success("复核完成,退款已闭环") : error("退款单状态已变化(非待复核),请刷新");
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    /** 刷新微信真实退款状态并回写(P2:P3 回调的拉取兜底,处理前先确认微信到底退没退)。 */
    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @GetMapping("/manual/refresh")
    public AjaxResult manualRefresh(String refundNo) {
        if (refundNo == null || refundNo.trim().isEmpty()) {
            return error("退款单号不能为空");
        }
        String status = wechatPayService.refreshPayoutFromWx(refundNo);
        return AjaxResult.success("已刷新", status == null ? "查询失败" : status);
    }
}

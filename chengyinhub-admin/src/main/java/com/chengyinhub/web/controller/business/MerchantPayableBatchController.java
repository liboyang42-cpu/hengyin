package com.chengyinhub.web.controller.business;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.service.IMerchantPayableService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;

/**
 * ③ 计酬档应付**月结批次**(2026-07-17 拍板:自然月,次月结上月)。
 *
 * <p><b>为什么是运营手点,不挂定时任务</b>:打款本来就是人工动作(对公打款 + 商家开票),
 * 定时生成一批没人处置的批次没有意义;且本项目反复栽在「任务没注册 = 静默不跑、零告警」上,
 * 能不引入这个风险就不引入。
 *
 * <p><b>正常流转</b>:生成批次(PENDING)→ 与商家对完账(CONFIRMED)→ 对公打款出账(PAID)。
 * 发票是<b>独立的一条轴</b>,与打款不互为前置 —— 混成一条就表达不了「已打款但未开票」。
 *
 * <p>⚠️ 本控制器<b>只记账,不打款</b>。{@code markPaid} 记的是「钱已经从对公账户打出去了」这个
 * <b>事实</b>,不是「把钱打出去」这个<b>动作</b>。打款能力在系统1(PR#78 已收口),
 * 这里出现任何转账/加余额都是在新造第三个打款方 —— 那正是 #78 刚根治掉的病。
 */
@RestController
@RequestMapping("/business/payablebatch")
public class MerchantPayableBatchController extends BaseController {

    @Autowired
    private IMerchantPayableService merchantPayableService;

    /**
     * 某个自然月的全部批次。
     *
     * @param periodYm 自然月 {@code YYYY-MM}
     */
    @PreAuthorize("@ss.hasPermi('business:payablebatch:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam String periodYm) {
        return AjaxResult.success(merchantPayableService.listBatches(periodYm));
    }

    /**
     * 生成上月(或更早)的月结批次 —— 每个有应付发生的商家一张。
     *
     * <p>⚠️ <b>只能结已经过完的月份</b>,service 会拒当月与未来月。批次成员是由
     * (商家, create_time 所在自然月) 推导的,月中生成会让之后落进该月的台账行**无处可去**:
     * 它们不属于任何批次、永远不会被打款,且零告警。
     *
     * <p>重复点击安全:已生成的商家会被跳过(库层 uk_batch_merchant_period 是真防线)。
     */
    @PreAuthorize("@ss.hasPermi('business:payablebatch:edit')")
    @Log(title = "③应付月结批次生成", businessType = BusinessType.INSERT)
    @PostMapping("/generate")
    public AjaxResult generate(@RequestBody Map<String, Object> body) {
        String periodYm = body.get("periodYm") == null ? null : String.valueOf(body.get("periodYm"));
        List<MerchantPayableBatch> created = merchantPayableService.generateMonthlyBatches(periodYm);
        return AjaxResult.success("本次新生成 " + created.size() + " 个批次", created);
    }

    /**
     * 对账确认:PENDING → CONFIRMED。金额自此锁定。
     *
     * <p>返回 0 行 = 该批次已不是待结态(已确认过 / 已打款)。说清楚,别让运营对着失败猜。
     */
    @PreAuthorize("@ss.hasPermi('business:payablebatch:edit')")
    @Log(title = "③应付批次对账确认", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/confirm")
    public AjaxResult confirm(@PathVariable Long id) {
        return merchantPayableService.confirmBatch(id, getUsername())
                ? AjaxResult.success()
                : AjaxResult.error("确认失败:该批次已不是「待结」状态(可能已确认或已打款)");
    }

    /**
     * 记录打款事实:CONFIRMED → PAID。
     *
     * <p>⚠️ <b>这不是「打款」,是「记录钱已经打出去了」</b> —— 转账动作在对公网银,不在这里。
     * ⚠️ 带 CONFIRMED 前置:这是「先对账再打款」的唯一强制点,别放宽。
     */
    @PreAuthorize("@ss.hasPermi('business:payablebatch:edit')")
    @Log(title = "③应付批次记录打款", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/paid")
    public AjaxResult markPaid(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String voucherNo = body.get("payVoucherNo") == null ? null : String.valueOf(body.get("payVoucherNo"));
        try {
            return merchantPayableService.markBatchPaid(id, getUsername(), voucherNo)
                    ? AjaxResult.success()
                    : AjaxResult.error("记录打款失败:该批次未经对账确认、已记过打款,或已被冻结");
        } catch (IllegalStateException e) {
            // ★双人复核:打款人与对账确认人为同一人
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 记录已开蓝票:NONE → ISSUED。
     *
     * <p>发票轴与打款轴**独立**:开票不要求已打款,打款也不要求已开票。
     */
    @PreAuthorize("@ss.hasPermi('business:payablebatch:edit')")
    @Log(title = "③应付批次记录开票", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/invoice")
    public AjaxResult markInvoiceIssued(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String invoiceNo = body.get("invoiceNo") == null ? null : String.valueOf(body.get("invoiceNo"));
        return merchantPayableService.markBatchInvoiceIssued(id, invoiceNo)
                ? AjaxResult.success()
                : AjaxResult.error("记录开票失败:该批次已开过票或已红冲");
    }

    /**
     * 记录已红冲:ISSUED → RED。
     *
     * <p><b>什么时候用</b>:已开票批次里的核销被撤销了。光追加 REVERSAL 台账行不够 ——
     * 税务上必须有红字发票与之对应(法定,不是流程偏好)。冲正发生时后端会打一条
     * {@code [③应付] 冲正命中**已开票**批次} 的 warn,那就是要来这里回填的信号。
     */
    @PreAuthorize("@ss.hasPermi('business:payablebatch:edit')")
    @Log(title = "③应付批次记录红冲", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/invoice-red")
    public AjaxResult markInvoiceRed(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String redNo = body.get("redInvoiceNo") == null ? null : String.valueOf(body.get("redInvoiceNo"));
        return merchantPayableService.markBatchInvoiceRed(id, redNo)
                ? AjaxResult.success()
                : AjaxResult.error("记录红冲失败:该批次没有开过蓝票(没开票就谈不上红冲)");
    }

    /** 冻结:暂停该批次的对账确认/打款,不移动任何资金。独立权限点,不与日常 edit 权限捆绑。 */
    @PreAuthorize("@ss.hasPermi('business:payablebatch:freeze')")
    @Log(title = "③应付批次冻结", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/freeze")
    public AjaxResult freeze(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String reason = body.get("frozenReason") == null ? null : String.valueOf(body.get("frozenReason"));
        if (reason == null || reason.trim().isEmpty()) {
            return AjaxResult.error("请填写冻结原因");
        }
        try {
            return merchantPayableService.freezeBatch(id, getUsername(), reason)
                    ? AjaxResult.success("已冻结")
                    : AjaxResult.error("冻结失败:该批次已在冻结中");
        } catch (IllegalStateException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /** 解冻:恢复该批次的对账确认/打款。 */
    @PreAuthorize("@ss.hasPermi('business:payablebatch:freeze')")
    @Log(title = "③应付批次解冻", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/unfreeze")
    public AjaxResult unfreeze(@PathVariable Long id) {
        try {
            return merchantPayableService.unfreezeBatch(id, getUsername())
                    ? AjaxResult.success("已解冻")
                    : AjaxResult.error("解冻失败:该批次本就未冻结");
        } catch (IllegalStateException e) {
            return AjaxResult.error(e.getMessage());
        }
    }
}

package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.SettlementBatch;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.dto.SettlementGenerateDTO;
import com.chengyinhub.business.service.ISettlementService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/business/settlement")
public class SettlementController extends BaseController {
    @Autowired
    private ISettlementService settlementService;

    @Autowired
    private com.chengyinhub.business.service.impl.CoopSettlementManualCaseReadService manualCases;

    @PreAuthorize("@ss.hasPermi('business:settlement:list')")
    @GetMapping("/manual-case/list")
    public TableDataInfo manualCaseList(@RequestParam(required=false) Long topicId,
            @RequestParam(required=false) String state) {
        startPage();
        try {
            return getDataTable(manualCases.list(topicId,state));
        } finally {
            clearPage();
        }
    }

    @PreAuthorize("@ss.hasPermi('business:settlement:list')")
    @GetMapping("/manual-case/{id}")
    public AjaxResult manualCase(@org.springframework.web.bind.annotation.PathVariable Long id) {
        var record=manualCases.detail(id);
        return record==null?AjaxResult.error(HttpStatus.NOT_FOUND,"人工记录不存在"):success(record);
    }

    @PreAuthorize("@ss.hasPermi('business:settlement:list')")
    @GetMapping("/list")
    public TableDataInfo list(SettlementBatch query) {
        startPage();
        return getDataTable(settlementService.selectBatchList(query));
    }

    @PreAuthorize("@ss.hasPermi('business:settlement:list')")
    @GetMapping("/detail/list")
    public TableDataInfo detailList(SettlementDetail query) {
        startPage();
        return getDataTable(settlementService.selectDetailList(query));
    }

    @PreAuthorize("@ss.hasPermi('business:settlement:list')")
    @GetMapping("/summary")
    public AjaxResult summary(@RequestParam Long merchantId) {
        return success(settlementService.getSummary(merchantId));
    }

    @PreAuthorize("@ss.hasPermi('business:settlement:list')")
    @GetMapping("/reconciliation")
    public AjaxResult reconciliation(@RequestParam Long merchantId) {
        return success(settlementService.getReconciliation(merchantId));
    }

    @PreAuthorize("@ss.hasPermi('business:settlement:generate')")
    @Log(title = "商家结算生成", businessType = BusinessType.INSERT)
    @PostMapping("/generate")
    public AjaxResult generate(@RequestBody SettlementGenerateDTO request) {
        // A3-1(P0):手工生成结算批次已停用。generateBatch 全仓仅此端点调用、UI 零调用;
        // 真实结算由系统1(settleActivity/settleTopic)自建批次,保留此手工旁路 = 与系统1并行的重复生成/双花隐患。
        // 对账确认走 confirm 接口,不经由此端点凭空造账目事实。
        return AjaxResult.error(HttpStatus.FORBIDDEN, "手工生成结算批次已停用,结算由系统自动生成,对账确认请用 confirm 接口");
    }

    /**
     * 确认对账(不打款)。把批次与其明细置为已结算,仅流转账本状态。
     *
     * <p>⚠️ 真实打款由系统1(CmsActivityServiceImpl.settleActivity / CmsTopicServiceImpl.settleTopic)
     * 独占承担 —— 系统2 的打款能力已拆除防双花,见 SettlementPayoutDecommissionedTest。
     * 操作日志标题据此从「商家结算打款」改为「商家结算对账确认」,避免日志被当成打款凭证。
     */
    @PreAuthorize("@ss.hasPermi('business:settlement:confirm')")
    @Log(title = "商家结算对账确认", businessType = BusinessType.UPDATE)
    @PostMapping("/confirm")
    public AjaxResult confirm(@RequestParam Long batchId) {
        Long operatorId = getUserId();
        return toAjax(settlementService.confirmBatch(batchId, operatorId));
    }
}

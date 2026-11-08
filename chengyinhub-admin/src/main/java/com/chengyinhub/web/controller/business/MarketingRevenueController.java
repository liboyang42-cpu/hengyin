package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.RevenueRuleDraftRequest;
import com.chengyinhub.business.domain.dto.RevenueTrialRequest;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocationLine;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.service.IMarketingRevenueAllocationService;
import com.chengyinhub.business.service.IMarketingRevenuePostingService;
import com.chengyinhub.business.service.IMarketingRevenueRuleService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import com.chengyinhub.business.domain.dto.RevenueAccrualRequest;
import com.chengyinhub.business.domain.dto.RevenueReversalRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 创作者/推荐分配规则与快照后台接口。
 *
 * <p>权限串沿用既有资金域命名风格，但<b>不新增 sys_menu 授权</b>：
 * 新权限点属 RBAC 变更，需 root 单独批准；在批准前这些接口对现有运营角色默认不可用。
 *
 * <p>本控制器只做规则配置、试算、快照查询与结算状态推进；<b>不执行任何打款、退款或改价</b>。
 *
 * <p><b>应计/冲销入口</b>：这两个动作正常应由支付事实与退款审批在事务内驱动（见交给 owner 的接线 patch）。
 * 这里保留受权限门保护的手工入口，只用于运营补录与对账复核 —— 幂等键是 (order_type, order_id)
 * 与 refundId，重复调用不会二次应计或二次冲销。
 */
@RestController
@RequestMapping("/business/revenue")
public class MarketingRevenueController extends BaseController {

    private final IMarketingRevenueRuleService ruleService;
    private final IMarketingRevenueAllocationService allocationService;
    private final IMarketingRevenuePostingService postingService;

    public MarketingRevenueController(IMarketingRevenueRuleService ruleService,
                                      IMarketingRevenueAllocationService allocationService,
                                      IMarketingRevenuePostingService postingService) {
        this.ruleService = ruleService;
        this.allocationService = allocationService;
        this.postingService = postingService;
    }

    @PreAuthorize("@ss.hasPermi('business:revenue:list')")
    @GetMapping("/rule/list")
    public AjaxResult ruleList(@RequestParam(required = false) String ruleKey,
                               @RequestParam(required = false) Integer scenario,
                               @RequestParam(required = false) Integer status) {
        return success(ruleService.list(ruleKey, scenario, status));
    }

    @PreAuthorize("@ss.hasPermi('business:revenue:list')")
    @GetMapping("/rule/{id}")
    public AjaxResult ruleDetail(@PathVariable Long id) {
        return success(ruleService.get(id));
    }

    /** 保存草稿。草稿永不参与分配；只有 publish 之后才可能被应计命中。 */
    @PreAuthorize("@ss.hasPermi('business:revenue:edit')")
    @Log(title = "创作者分配规则草稿", businessType = BusinessType.UPDATE)
    @PostMapping("/rule/draft")
    public AjaxResult saveDraft(@RequestBody RevenueRuleDraftRequest request) {
        return success(ruleService.saveDraft(getUserId(), request));
    }

    /** 发布：先退役同键现行版本再激活该草稿，同一规则键同时只有一个生效版本。 */
    @PreAuthorize("@ss.hasPermi('business:revenue:edit')")
    @Log(title = "创作者分配规则发布", businessType = BusinessType.UPDATE)
    @PostMapping("/rule/{ruleKey}/publish")
    public AjaxResult publish(@PathVariable String ruleKey) {
        return success(ruleService.publish(getUserId(), ruleKey));
    }

    /** 退役现行版本；已应计的原单不受影响（冻结版本继续可追溯）。 */
    @PreAuthorize("@ss.hasPermi('business:revenue:edit')")
    @Log(title = "创作者分配规则退役", businessType = BusinessType.UPDATE)
    @PostMapping("/rule/{ruleKey}/retire")
    public AjaxResult retire(@PathVariable String ruleKey) {
        return success(ruleService.retire(getUserId(), ruleKey));
    }

    /**
     * 该原单的投递任务：可靠队列里的应计/冲销任务及其 status/attempts/reasonCode。
     * 已启用订单缺 rule/金额/来源时可据此定位并补录，不会静默丢。
     */
    @PreAuthorize("@ss.hasPermi('business:revenue:list')")
    @GetMapping("/allocation/{orderId}/tasks")
    public AjaxResult postingTasks(@PathVariable Long orderId) {
        return success(postingService.tasksOf(orderId));
    }

    /** 手动触发一轮消费（正常由调度任务驱动）；只处理到期任务。 */
    @PreAuthorize("@ss.hasPermi('business:revenue:edit')")
    @Log(title = "收入分配任务消费", businessType = BusinessType.UPDATE)
    @PostMapping("/posting/consume")
    public AjaxResult consume(@RequestParam(defaultValue = "50") int limit) {
        return success(postingService.consumeDue(Math.min(Math.max(limit, 1), 200)));
    }

    /** 试算：只算数不落库，返回净额分项与三方金额并声明守恒。 */
    @PreAuthorize("@ss.hasPermi('business:revenue:list')")
    @PostMapping("/trial")
    public AjaxResult trial(@RequestBody RevenueTrialRequest request) {
        return success(ruleService.trial(request));
    }

    @PreAuthorize("@ss.hasPermi('business:revenue:list')")
    @GetMapping("/allocation/{orderType}/{orderId}")
    public AjaxResult allocation(@PathVariable Integer orderType, @PathVariable Long orderId) {
        MarketingRevenueAllocation allocation = allocationService.getByOrder(orderType, orderId);
        if (allocation == null) {
            return success(null);
        }
        AjaxResult result = success(allocation);
        List<MarketingRevenueAllocationLine> lines = allocationService.linesOf(allocation.getId());
        result.put("lines", lines);
        return result;
    }

    @PreAuthorize("@ss.hasPermi('business:revenue:list')")
    @GetMapping("/allocation/author/{authorMemberId}")
    public AjaxResult authorAllocations(@PathVariable Long authorMemberId) {
        return success(allocationService.listByAuthor(authorMemberId));
    }

    /** 把已过等待期的应计推进为可结算（只改状态，不派发资金）。 */
    @PreAuthorize("@ss.hasPermi('business:revenue:edit')")
    @Log(title = "分配应计转可结算", businessType = BusinessType.UPDATE)
    @PostMapping("/allocation/advance-settleable")
    public AjaxResult advanceSettleable() {
        return success(allocationService.advanceSettleable());
    }

    /**
     * 手工补录应计：实付/币种来自已验证支付事实，收款人来自受审合同；
     * 客户端既不能传 sharerId/creatorId，也不能传实付金额。扣费必须带 feeBasisRef 证据。
     */
    @PreAuthorize("@ss.hasPermi('business:revenue:edit')")
    @Log(title = "创作者收入应计", businessType = BusinessType.UPDATE)
    @PostMapping("/allocation/accrue")
    public AjaxResult accrue(@RequestBody RevenueAccrualRequest request) {
        return success(allocationService.accrue(request));
    }

    /**
     * 手工冲销：只接受 refundId，金额/币种/原单全部由退款单 + 通道退款事实读取。
     * 后台批准不等于通道退款成功，缺事实会被拒。正常应由退款审批在事务内调用。
     */
    @PreAuthorize("@ss.hasPermi('business:revenue:edit')")
    @Log(title = "创作者收入冲销", businessType = BusinessType.UPDATE)
    @PostMapping("/allocation/reverse")
    public AjaxResult reverse(@RequestBody RevenueReversalRequest request) {
        return success(allocationService.reverse(request));
    }

    @ExceptionHandler(MarketingRevenueException.class)
    public AjaxResult handleRevenueRejection(MarketingRevenueException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }
}

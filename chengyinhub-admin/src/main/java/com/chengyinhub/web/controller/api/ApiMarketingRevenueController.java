package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocationLine;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.service.IMarketingRevenueAllocationService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 创作者/推荐人收入只读接口(小程序端收益详情用)。
 *
 * <p><b>只读本域快照，不接受任何写操作</b>：没有应计、退款、打款入口 —— 那些由服务端订单/退款事实驱动。
 * 因此客户端无法凭 sharerId、金额或 creatorId 给自己授佣。
 *
 * <p>归属校验：只能读自己作为作者或被推荐人的分配，读他人一律 403；未登录如实 401，不降级成假身份。
 */
@Tag(name = "创作者收益")
@RestController
@RequestMapping("/api/marketing/revenue")
public class ApiMarketingRevenueController extends BaseController {

    private final IMarketingRevenueAllocationService allocationService;

    public ApiMarketingRevenueController(IMarketingRevenueAllocationService allocationService) {
        this.allocationService = allocationService;
    }

    @Operation(summary = "我作为内容作者的分配快照(应计/可结算/已打款/冲销)")
    @GetMapping("/author/mine")
    public AjaxResult myAuthorAllocations() {
        long memberId = requireAppMemberId();
        List<MarketingRevenueAllocation> rows = allocationService.listByAuthor(memberId);
        List<Map<String, Object>> views = new ArrayList<>(rows.size());
        for (MarketingRevenueAllocation row : rows) {
            views.add(project(row, allocationService.linesOf(row.getId()), memberId));
        }
        return success(views);
    }

    @Operation(summary = "某订单的分配明细(仅限本人参与的单)")
    @GetMapping("/order")
    public AjaxResult orderAllocation(@RequestParam Integer orderType, @RequestParam Long orderId) {
        long memberId = requireAppMemberId();
        MarketingRevenueAllocation row = allocationService.getByOrder(orderType, orderId);
        if (row == null) {
            return success(null);
        }
        List<MarketingRevenueAllocationLine> lines = allocationService.linesOf(row.getId());
        if (!involves(row, lines, memberId)) {
            throw MarketingRevenueException.forbidden("NOT_PARTICIPANT", "该订单的收入分配与当前账号无关");
        }
        return success(project(row, lines, memberId));
    }

    @ExceptionHandler(MarketingRevenueException.class)
    public AjaxResult handleRevenueRejection(MarketingRevenueException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }

    private long requireAppMemberId() {
        Long memberId = getAppUserId();
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED);
        }
        return memberId;
    }

    private boolean involves(MarketingRevenueAllocation row, List<MarketingRevenueAllocationLine> lines,
                             long memberId) {
        if (row.getAuthorMemberId() != null && row.getAuthorMemberId() == memberId) {
            return true;
        }
        if (row.getSharerMemberId() != null && row.getSharerMemberId() == memberId) {
            return true;
        }
        for (MarketingRevenueAllocationLine line : lines) {
            if (line.getRecipientMemberId() != null && line.getRecipientMemberId() == memberId) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只投影本人相关金额：平台分录对个人不展示，避免把"平台留存"误解成个人收入。
     * 状态用数字码 + 稳定文本，客户端按码分支。
     */
    private Map<String, Object> project(MarketingRevenueAllocation row,
                                        List<MarketingRevenueAllocationLine> lines, long memberId) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("allocationId", row.getId());
        view.put("orderType", row.getOrderType());
        view.put("orderId", row.getOrderId());
        view.put("scenario", row.getScenario());
        view.put("currency", row.getCurrency());
        view.put("status", row.getStatus());
        view.put("statusText", statusText(row.getStatus()));
        view.put("settleableAt", row.getSettleableAt());
        view.put("reversedAmount", row.getReversedAmount());
        view.put("contractRef", row.getContractRef());
        view.put("ruleVersion", row.getRuleVersion());
        List<Map<String, Object>> mine = new ArrayList<>();
        for (MarketingRevenueAllocationLine line : lines) {
            if (MarketingRevenueAllocationLine.ROLE_PLATFORM.equals(line.getLineRole())
                    || line.getRecipientMemberId() == null
                    || line.getRecipientMemberId().longValue() != memberId) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", line.getLineRole());
            item.put("entryType", line.getEntryType());
            item.put("amount", line.getAmount());
            item.put("amountFen", line.getAmountFen());
            item.put("rate", line.getRate());
            item.put("sourceFact", line.getSourceFact());
            mine.add(item);
        }
        view.put("lines", mine);
        return view;
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "unknown";
        }
        switch (status) {
            case MarketingRevenueAllocation.STATUS_ACCRUED:
                return "accrued";
            case MarketingRevenueAllocation.STATUS_SETTLEABLE:
                return "settleable";
            case MarketingRevenueAllocation.STATUS_PAID:
                return "paid";
            case MarketingRevenueAllocation.STATUS_PARTIALLY_REVERSED:
                return "partially_reversed";
            case MarketingRevenueAllocation.STATUS_REVERSED:
                return "reversed";
            default:
                return "unknown";
        }
    }
}

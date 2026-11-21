package com.chengyinhub.web.controller.business;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 拓展人激活赏金审核队列(R9 BE-13)。
 *
 * <p>达成激活条件的赏金先进队列(status=4),运营审核通过才入账;驳回为终态。
 * <b>审核通过会给拓展人余额加钱</b>,所以服务层先查资金开关
 * ({@code chengyin.release.money-payments} 与 {@code chengyin.release.payout} 同开),关闭时拒绝且不改状态。
 *
 * <p>权限沿用 {@code @ss.hasPermi},不 seed 菜单:超管 {@code *:*:*} 可直接用,普通运营默认拒绝(403)。
 */
@Tag(name = "拓展人赏金审核(运营)")
@RestController
@RequestMapping("/business/referralbounty")
public class ReferralBountyAdminController extends BaseController
{
    private static final String PERM_VIEW = "@ss.hasPermi('business:referralbounty:list')";
    private static final String PERM_REVIEW = "@ss.hasPermi('business:referralbounty:review')";

    private final MerchantReferralBountyService bountyService;

    public ReferralBountyAdminController(MerchantReferralBountyService bountyService)
    {
        this.bountyService = bountyService;
    }

    @Operation(summary = "赏金队列(默认看待审核)")
    @PreAuthorize(PERM_VIEW)
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam(value = "status", required = false) Integer status,
                              @RequestParam(value = "inviterMemberId", required = false) Long inviterMemberId,
                              @RequestParam(value = "merchantMemberId", required = false) Long merchantMemberId)
    {
        startPage();
        return getDataTable(bountyService.queue(status, inviterMemberId, merchantMemberId));
    }

    @Operation(summary = "赏金详情")
    @PreAuthorize(PERM_VIEW)
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable("id") Long id)
    {
        Object row = bountyService.detail(id);
        return row == null ? AjaxResult.error(404, "赏金记录不存在") : success(row);
    }

    @Operation(summary = "资金开关状态(决定能否审核通过)")
    @PreAuthorize(PERM_VIEW)
    @GetMapping("/money-switch")
    public AjaxResult moneySwitch()
    {
        return success(Map.of("open", bountyService.moneyOpen()));
    }

    @Operation(summary = "审核通过并入账(资金开关关闭时拒绝)")
    @PreAuthorize(PERM_REVIEW)
    @Log(title = "拓展人赏金审核通过", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/approve")
    public AjaxResult approve(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, String> body)
    {
        return success(bountyService.approve(id, operatorRef(), body == null ? null : body.get("note")));
    }

    @Operation(summary = "驳回(原因必填,终态,不入账)")
    @PreAuthorize(PERM_REVIEW)
    @Log(title = "拓展人赏金驳回", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/reject")
    public AjaxResult reject(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, String> body)
    {
        return success(bountyService.reject(id, operatorRef(), body == null ? null : body.get("note")));
    }

    private String operatorRef()
    {
        return "sys:" + getUserId();
    }
}

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
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.dto.CouponRuleChangeDTO;
import com.chengyinhub.business.service.ICouponRuleGovernanceService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 优惠券Controller
 * 
 * @author webxh
 * @date 2025-09-01
 */
@RestController
@RequestMapping("/business/coupon")
public class SmsCouponController extends BaseController
{
    @Autowired
    private ISmsCouponService smsCouponService;

    @Autowired
    private ICouponRuleGovernanceService couponRuleGovernanceService;

    /**
     * 查询优惠券列表
     */
    @PreAuthorize("@ss.hasPermi('business:coupon:list')")
    @GetMapping("/list")
    public TableDataInfo list(SmsCoupon smsCoupon)
    {
        startPage();
        List<SmsCoupon> list = smsCouponService.selectSmsCouponList(smsCoupon);
        return getDataTable(list);
    }

    /**
     * 导出优惠券列表
     */
    @PreAuthorize("@ss.hasPermi('business:coupon:export')")
    @Log(title = "优惠券", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, SmsCoupon smsCoupon)
    {
        List<SmsCoupon> list = smsCouponService.selectSmsCouponList(smsCoupon);
        ExcelUtil<SmsCoupon> util = new ExcelUtil<SmsCoupon>(SmsCoupon.class);
        util.exportExcel(response, list, "优惠券数据");
    }

    /**
     * 获取优惠券详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:coupon:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        // F0572:详情带 ruleVersion,编辑表单据此做乐观锁提交
        return success(couponRuleGovernanceService.getWithRuleVersion(id));
    }

    /**
     * 新增优惠券
     */
    @PreAuthorize("@ss.hasPermi('business:coupon:add')")
    @Log(title = "优惠券", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody SmsCoupon smsCoupon)
    {
        return toAjax(smsCouponService.insertSmsCoupon(smsCoupon));
    }

    /**
     * 修改优惠券规则(F0572)。
     *
     * <p>只接受白名单字段(名称/说明/面额/门槛/有效期/可领取日期/发放总量),其余字段整单拒绝;
     * 必须携带 expectedVersion,版本已变返回 409 + currentVersion。
     * 发放总量遵循「不低于已领取」;领取/核销计数、状态、范围、归属都不能经此改写
     * (A3-4 计数器封口由白名单延续)。改动只影响之后的领取,已领取的券保持领取时规则。
     */
    @PreAuthorize("@ss.hasPermi('business:coupon:edit')")
    @Log(title = "优惠券规则变更", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody CouponRuleChangeDTO change)
    {
        try
        {
            return success(couponRuleGovernanceService.applyChange(change, getUserId()));
        }
        catch (ServiceException e)
        {
            return governanceError(e);
        }
    }

    /** 规则变更影响预览(只读):新领取 vs 已领取、字段差异、版本冲突 */
    @PreAuthorize("@ss.hasPermi('business:coupon:query')")
    @PostMapping("/rule-impact")
    public AjaxResult ruleImpact(@RequestBody CouponRuleChangeDTO change)
    {
        try
        {
            return success(couponRuleGovernanceService.previewChange(change));
        }
        catch (ServiceException e)
        {
            return governanceError(e);
        }
    }

    /** 停发:只拦新领取,已领取的券照常可用 */
    @PreAuthorize("@ss.hasPermi('business:coupon:edit')")
    @Log(title = "优惠券停发", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/stop-issue")
    public AjaxResult stopIssue(@PathVariable("id") Long id, @RequestBody CouponRuleChangeDTO request)
    {
        try
        {
            return success(couponRuleGovernanceService.stopIssue(id,
                    request == null ? null : request.getExpectedVersion(),
                    request == null ? null : request.getReason(), getUserId()));
        }
        catch (ServiceException e)
        {
            return governanceError(e);
        }
    }

    /** 平台失效:必须独立填写原因;返回受影响持券人 */
    @PreAuthorize("@ss.hasPermi('business:coupon:edit')")
    @Log(title = "优惠券平台失效", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/invalidate")
    public AjaxResult invalidate(@PathVariable("id") Long id, @RequestBody CouponRuleChangeDTO request)
    {
        try
        {
            return success(couponRuleGovernanceService.invalidate(id,
                    request == null ? null : request.getExpectedVersion(),
                    request == null ? null : request.getReason(), getUserId()));
        }
        catch (ServiceException e)
        {
            return governanceError(e);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:coupon:query')")
    @GetMapping("/{id}/rule-versions")
    public AjaxResult ruleVersions(@PathVariable("id") Long id)
    {
        return success(couponRuleGovernanceService.listVersions(id));
    }

    @PreAuthorize("@ss.hasPermi('business:coupon:query')")
    @GetMapping("/{id}/affected-holders")
    public AjaxResult affectedHolders(@PathVariable("id") Long id)
    {
        return success(couponRuleGovernanceService.affectedHolders(id));
    }

    private static AjaxResult governanceError(ServiceException e)
    {
        int code = e.getCode() == null ? HttpStatus.ERROR : e.getCode();
        AjaxResult result = new AjaxResult(code, e.getMessage());
        if (e.getData() != null)
        {
            result.put(AjaxResult.DATA_TAG, e.getData());
        }
        return result;
    }

    /**
     * 删除优惠券
     */
    @PreAuthorize("@ss.hasPermi('business:coupon:remove')")
    @Log(title = "优惠券", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(smsCouponService.deleteSmsCouponByIds(ids));
    }
}

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
import com.chengyinhub.business.service.ISmsCouponService;
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
        return success(smsCouponService.selectSmsCouponById(id));
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
     * 修改优惠券
     */
    @PreAuthorize("@ss.hasPermi('business:coupon:edit')")
    @Log(title = "优惠券", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody SmsCoupon smsCoupon)
    {
        // A3-4(P0):发行/领取/使用量是原子计数器(tryIncrementReceiveCount / incrementUseCount)维护的真实统计,
        // 后台经通用 PUT 裸改 = 伪造发放/使用数据。置 null 让 mapper 动态 <if> 跳过=保库中真值。
        stripStatCounters(smsCoupon);
        return toAjax(smsCouponService.updateSmsCoupon(smsCoupon));
    }

    /**
     * A3-4(P0):剥离优惠券的运行时统计计数器,使其无法经通用 PUT 被篡改。
     * 这些只应由领取/核销的原子自增维护;后台编辑只允许改券的配置字段(名称/面额/有效期等)。
     */
    private static void stripStatCounters(SmsCoupon c)
    {
        c.setPublishCount(null);
        c.setReceiveCount(null);
        c.setUseCount(null);
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

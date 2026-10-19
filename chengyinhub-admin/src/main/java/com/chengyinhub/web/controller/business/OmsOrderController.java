package com.chengyinhub.web.controller.business;

import java.util.List;
import java.util.Set;
import jakarta.servlet.http.HttpServletResponse;

import com.chengyinhub.business.domain.OmsOrderItem;
import com.chengyinhub.business.domain.ViewOrder;
import com.chengyinhub.business.service.IOmsOrderItemService;
import com.chengyinhub.business.service.IViewOrderService;
import com.chengyinhub.business.service.impl.OmsOrderServiceImpl;
import com.chengyinhub.business.service.impl.OrderPaymentFactService;
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
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.security.AdminPiiProtector;
import com.chengyinhub.business.service.IOmsOrderService;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * 订单Controller
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
@RestController
@RequestMapping("/business/order")
public class OmsOrderController extends BaseController
{
    @Autowired
    private IOmsOrderService omsOrderService;

    @Autowired
    private IViewOrderService viewOrderService;

    @Autowired
    private IOmsOrderItemService omsOrderItemService;

    @Autowired
    private OrderPaymentFactService orderPaymentFactService;

    /**
     * 「看全量 PII」权限。无此权限者,收件人姓名/电话/详细地址与下单人手机一律脱敏后才出后端。
     * 超管持 *:*:*,天然匹配本权限,看到的仍是全量。
     */
    private static final String PERM_VIEW_PII = "business:order:pii";

    /** 为何不调 PermissionService.hasPermi:见 CmsRegistrationController.canViewPii 的说明(会污染 DataScope 的 context)。 */
    private boolean canViewPii()
    {
        Set<String> permissions = SecurityUtils.getLoginUser().getPermissions();
        return permissions != null
                && (permissions.contains(Constants.ALL_PERMISSION) || permissions.contains(PERM_VIEW_PII));
    }

    /**
     * 查询订单列表
     */
    @PreAuthorize("@ss.hasPermi('business:order:list')")
    @GetMapping("/list")
    public TableDataInfo list(ViewOrder omsOrder)
    {
        boolean full = canViewPii();
        if (!full)
        {
            // 先清检索条件:selectViewOrderList 对下单人 phone 做明文等值匹配,
            // 不清掉就能枚举中间四位把脱敏反推回去。
            AdminPiiProtector.stripPii(omsOrder);
        }
        startPage();
        List<ViewOrder> list = viewOrderService.selectViewOrderList(omsOrder);
        if (!full)
        {
            list.forEach(AdminPiiProtector::maskInPlace);
        }
        return getDataTable(list);
    }

    /**
     * 导出订单列表
     */
    @PreAuthorize("@ss.hasPermi('business:order:export')")
    @Log(title = "订单", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, OmsOrder omsOrder)
    {
        boolean full = canViewPii();
        if (!full)
        {
            // selectOmsOrderList 对 receiverPhone 做盲索引精确匹配,同样是反推口
            AdminPiiProtector.stripPii(omsOrder);
        }
        List<OmsOrder> list = omsOrderService.selectOmsOrderList(omsOrder);
        if (!full)
        {
            // 导出是真正的批量外泄口,不脱敏的话前面几道都白做
            list.forEach(AdminPiiProtector::maskInPlace);
        }
        ExcelUtil<OmsOrder> util = new ExcelUtil<OmsOrder>(OmsOrder.class);
        util.exportExcel(response, list, "订单数据");
    }

    /**
     * 获取订单详细信息
     */
    @PreAuthorize("@ss.hasPermi('business:order:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        ViewOrder order = viewOrderService.selectViewOrderById(id);
        if (order == null)
        {
            // 不判空的话,下面 order.getId() 直接 NPE → 任意不存在的 id 都能把这个端点打成 500
            return error("订单不存在");
        }

        OmsOrderItem item = new OmsOrderItem();
        item.setOrderId(order.getId());
        order.setProducts(omsOrderItemService.selectOmsOrderItemList(item));

        if (!canViewPii())
        {
            AdminPiiProtector.maskInPlace(order);
        }
        return success(order);
    }

    /**
     * F0535 支付事实:积分来源与现金来源分开;现金来源只认渠道查询回执(未确认/已确认/冲突 + 最后查询时间),
     * 本地保存的订单状态不当作已核验事实。
     */
    @PreAuthorize("@ss.hasPermi('business:order:query')")
    @GetMapping(value = "/{id}/payment-facts")
    public AjaxResult paymentFacts(@PathVariable("id") Long id)
    {
        java.util.Map<String, Object> facts = orderPaymentFactService.facts(id);
        return facts == null ? error("订单不存在") : success(facts);
    }

    /** F0535 向原支付渠道查询一次并落只追加回执;只读渠道,不改订单与资金。 */
    @PreAuthorize("@ss.hasPermi('business:order:query')")
    @Log(title = "订单支付事实渠道查询", businessType = BusinessType.OTHER)
    @PostMapping(value = "/{id}/payment-facts/query")
    public AjaxResult queryPaymentFacts(@PathVariable("id") Long id)
    {
        try
        {
            java.util.Map<String, Object> facts = orderPaymentFactService.queryChannel(id, getUserId());
            return facts == null ? error("订单不存在") : success(facts);
        }
        catch (IllegalArgumentException e)
        {
            return error(e.getMessage());
        }
    }

    /**
     * 新增订单
     */
    @PreAuthorize("@ss.hasPermi('business:order:add')")
    @Log(title = "订单", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody OmsOrder omsOrder)
    {
        return toAjax(omsOrderService.insertOmsOrder(omsOrder));
    }

    /**
     * 修改订单
     */
    @PreAuthorize("@ss.hasPermi('business:order:edit')")
    @Log(title = "订单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody OmsOrder omsOrder)
    {
        if (!canViewPii())
        {
            // 看到的是脱敏值,提交回来的也只能是脱敏值。置 null 让 update 的动态 <if> 跳过这几列,
            // 否则 "138****1234" 会被当真值加密写回,真实收件电话当场没了。
            AdminPiiProtector.stripPii(omsOrder);
        }
        return toAjax(omsOrderService.updateOmsOrder(omsOrder));
    }

    /**
     * 删除订单
     */
    @PreAuthorize("@ss.hasPermi('business:order:remove')")
    @Log(title = "订单", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(omsOrderService.deleteOmsOrderByIds(ids));
    }
}

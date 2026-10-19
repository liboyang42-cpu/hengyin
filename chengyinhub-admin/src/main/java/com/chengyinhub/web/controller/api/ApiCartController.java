package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ViewMemberCart;
import com.chengyinhub.business.service.IUmsMemberCartService;
import com.chengyinhub.business.service.IViewMemberCartService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.core.page.TableDataInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


/**
 * 积分商品
 *
 * @author webxh
 * @date 2025-08-30
 */
@RestController
@Tag(name = "购物车")
@RequestMapping("/api/cart")
public class ApiCartController extends BaseController {

    @Autowired
    private IViewMemberCartService viewMemberCartService;

    @Autowired
    private IUmsMemberCartService umsMemberCartService;

    @PostMapping("/list")
    @Operation(summary = "购物车列表")
    @Parameters({
            @Parameter(name = "type", description = "类型 1-online shopping Mall 2-Repurchase Mall"),
            @Parameter(name = "pageNum", description = "页码"),
            @Parameter(name = "pageSize", description = "每页显示记录数")
    })
    public AjaxResult getCartList() {
        LoginUser loginUser = getLoginUser();

        ViewMemberCart cart = new ViewMemberCart();
        cart.setMemberId(loginUser.getUserId());
        cart.setIsBuy(Long.valueOf(0));
        startPage();
        List<ViewMemberCart> viewMemberCartList = viewMemberCartService.selectViewMemberCartList(cart);

        TableDataInfo data = getDataTable(viewMemberCartList);
        return AjaxResult.success(data);
    }


    @PostMapping("/delete")
    @Operation(summary = "购物车删除")
    @Parameters({
            @Parameter(name = "cartids", description = "购物车ID 多数据逗号分开")
    })
    public AjaxResult cartDelete(String cartids) {
        LoginUser loginUser = getLoginUser();
        try {
            umsMemberCartService.deleteCarts(loginUser.getUserId(), cartids);
            return success();
        } catch (com.chengyinhub.common.exception.ServiceException exception) {
            return error(exception.getMessage());
        }
    }

    @PostMapping("/cart/add")
    @Operation(summary = "购物车添加")
    @Parameters({
            @Parameter(name = "product_id", description = "商品ID"),
            @Parameter(name = "sku_id", description = "所选规格ID"),
            @Parameter(name = "quantity", description = "数量"),
            @Parameter(name = "is_buy", description = "默认0 ，1是直接购买")
    })
    public AjaxResult cartAdd(String product_id, String sku_id, String quantity, String is_buy) {
        LoginUser loginUser = getLoginUser();
        try {
            return success(umsMemberCartService.addCart(loginUser.getUserId(), product_id, sku_id, quantity, is_buy));
        } catch (com.chengyinhub.common.exception.ServiceException exception) {
            return error(exception.getMessage());
        }
    }

    @PostMapping("/update")
    @Operation(summary = "更新购物车")
    @Parameters({
            @Parameter(name = "cart_id", description = "购物车ID"),
            @Parameter(name = "quantity", description = "数量"),
            @Parameter(name = "sku_id", description = "所选规格ID")
    })
    public AjaxResult cartUpdate(String cart_id, String quantity, String sku_id) {
        LoginUser loginUser = getLoginUser();
        try {
            return success(umsMemberCartService.updateCart(loginUser.getUserId(), cart_id, quantity, sku_id));
        } catch (com.chengyinhub.common.exception.ServiceException exception) {
            return error(exception.getMessage());
        }
    }

    @PostMapping("/settlement")
    @Operation(summary = "购物车结算")
    @Parameters({
            @Parameter(name = "cartids", description = "购物车ID 多数据逗号分开")
    })
    public AjaxResult cartSettlement(String cartids) {
        LoginUser loginUser = getLoginUser();

        try {
            return success(umsMemberCartService.previewSettlement(loginUser.getUserId(), cartids));
        } catch (com.chengyinhub.common.exception.ServiceException exception) {
            return error(exception.getMessage());
        }
    }


    @PostMapping("/order/settlement")
    @Operation(summary = "订单结算保存")
    @Parameters({
            @Parameter(name = "cartids", description = "购物车ID 多数据逗号分开"),
            @Parameter(name = "remark", description = "订单备注"),
            @Parameter(name = "addressid", description = "收货地址")
    })
    public AjaxResult orderSettlement(String cartids, String remark, String addressid, String requestId) {
        LoginUser loginUser = getLoginUser();
        try {
            if (requestId == null) {
                return success(umsMemberCartService.settleCart(loginUser.getUserId(), loginUser.getUsername(),
                        cartids, remark, addressid));
            }
            return success(umsMemberCartService.settleCart(loginUser.getUserId(), loginUser.getUsername(),
                    requestId, cartids, remark, addressid));
        } catch (com.chengyinhub.common.exception.ServiceException exception) {
            return error(exception.getMessage());
        }
    }

    /** 保留旧 Controller 三参数直接调用合同；HTTP 映射由上方可选 requestId 方法承接。 */
    public AjaxResult orderSettlement(String cartids, String remark, String addressid) {
        return orderSettlement(cartids, remark, addressid, null);
    }

    @GetMapping("/order/settlement/receipt")
    @Operation(summary = "回读本人积分兑换结果")
    public AjaxResult settlementReceipt(String requestId) {
        try {
            return success(umsMemberCartService.getSettlementReceipt(getLoginUser().getUserId(), requestId));
        } catch (com.chengyinhub.common.exception.ServiceException exception) {
            return error(exception.getMessage());
        }
    }
}

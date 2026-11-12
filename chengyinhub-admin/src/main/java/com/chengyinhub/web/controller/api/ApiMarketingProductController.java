package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.GiftClaimRequest;
import com.chengyinhub.business.domain.dto.MarketingProductOrderRequest;
import com.chengyinhub.business.domain.dto.MarketingProductQuoteRequest;
import com.chengyinhub.business.exception.MarketingProductException;
import com.chengyinhub.business.service.IGiftEntitlementService;
import com.chengyinhub.business.service.IMarketingProductCatalogService;
import com.chengyinhub.business.service.IMarketingProductPurchaseService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品包/通行证/礼品/席位（C06）。
 *
 * <p>写操作的会员身份只来自 {@link #getAppUserId()}，不接收客户端传的 memberId。
 * 现金结算与退款撤销不在本控制器暴露：它们只接受 C07 已验证事实的跨域调用。
 */
@Tag(name = "营销商品与授权")
@RestController
@RequestMapping("/api/marketing/product")
public class ApiMarketingProductController extends BaseController {

    private final IMarketingProductCatalogService catalogService;
    private final IMarketingProductPurchaseService purchaseService;
    private final IGiftEntitlementService giftService;

    public ApiMarketingProductController(IMarketingProductCatalogService catalogService,
                                         IMarketingProductPurchaseService purchaseService,
                                         IGiftEntitlementService giftService) {
        this.catalogService = catalogService;
        this.purchaseService = purchaseService;
        this.giftService = giftService;
    }

    @Operation(summary = "可售商品列表（服务端价）")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String city,
                           @RequestParam(required = false) Integer kind,
                           @RequestParam(required = false, defaultValue = "20") Integer limit) {
        return success(catalogService.listSellable(city, kind, limit == null ? 20 : limit));
    }

    @Operation(summary = "商品详情（公开字段，不含付费正文）")
    @GetMapping("/{productId}")
    public AjaxResult detail(@PathVariable Long productId) {
        return success(catalogService.detail(productId));
    }

    @Operation(summary = "报价：返回服务端签名，下单只回传签名")
    @PostMapping("/quote")
    public AjaxResult quote(@RequestBody(required = false) MarketingProductQuoteRequest request) {
        return success(catalogService.quote(requireAppMemberId(), request));
    }

    @Operation(summary = "下单：积分即时可用；现金单只落待支付，不在本域伪造已付")
    @PostMapping("/order")
    public AjaxResult order(@RequestBody(required = false) MarketingProductOrderRequest request) {
        return success(purchaseService.purchase(requireAppMemberId(), request));
    }

    @Operation(summary = "我的商品订单")
    @GetMapping("/orders")
    public AjaxResult myOrders(@RequestParam(required = false, defaultValue = "20") Integer limit) {
        return success(purchaseService.myOrders(requireAppMemberId(), limit == null ? 20 : limit));
    }

    @Operation(summary = "现金预下单（需现金通道已启用且 MKO 路由已合入）")
    @PostMapping("/orders/{orderNo}/pay")
    public AjaxResult pay(@PathVariable String orderNo,
                          @RequestParam(required = false) String payChannel) {
        return success(purchaseService.prepareCashPayment(requireAppMemberId(), orderNo, payChannel));
    }

    @Operation(summary = "取消待支付现金单")
    @PostMapping("/orders/{orderNo}/cancel")
    public AjaxResult cancel(@PathVariable String orderNo) {
        return success(purchaseService.cancel(requireAppMemberId(), orderNo));
    }

    @Operation(summary = "首次使用激活：先原子写 expires_at，再由原注册服务放行可玩")
    @PostMapping("/activate")
    public AjaxResult activate(@RequestParam Long topicId) {
        return success(purchaseService.activateOnUse(requireAppMemberId(), topicId));
    }

    @Operation(summary = "领取礼品（一次生效，重复同人返回原授权）")
    @PostMapping("/gifts/claim")
    public AjaxResult claimGift(@RequestBody(required = false) GiftClaimRequest request) {
        return success(giftService.claim(requireAppMemberId(), request));
    }

    @Operation(summary = "我送出的礼品")
    @GetMapping("/gifts/bought")
    public AjaxResult giftsBought(@RequestParam(required = false, defaultValue = "20") Integer limit) {
        return success(giftService.giftsBoughtBy(requireAppMemberId(), limit == null ? 20 : limit));
    }

    @Operation(summary = "我领取的礼品")
    @GetMapping("/gifts/received")
    public AjaxResult giftsReceived(@RequestParam(required = false, defaultValue = "20") Integer limit) {
        return success(giftService.giftsClaimedBy(requireAppMemberId(), limit == null ? 20 : limit));
    }

    @ExceptionHandler(MarketingProductException.class)
    public AjaxResult handleProductRejection(MarketingProductException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        if (rejection.getData() != null) result.putAll(rejection.getData());
        return result;
    }

    private long requireAppMemberId() {
        Long memberId = getAppUserId();
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED);
        }
        return memberId;
    }
}

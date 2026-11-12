package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.MarketingTier;
import com.chengyinhub.business.exception.MarketingPromotionException;
import com.chengyinhub.business.service.IMarketingPromotionService;
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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家侧促销投影(只读)。
 *
 * <p><b>只读就是承诺</b>:这里不创建订单、不改价、不发资格、不发券。玩家拿到的每一个数
 * 都必须与 {@code /api/registration/quote} 的促销分项一致 —— 因为两者调用的是同一个服务、
 * 同一份配置;不一致就是报价链出 bug,应当报错而不是"以显示为准"。
 *
 * <p><b>为什么单独给一个目录接口</b>:报价接口需要完整购物车才能算钱,
 * 而"我有什么优惠可用"要在加购之前就能展示。所以这里只下发<b>候选 + 资格 + 价格</b>,
 * 不下发抵扣结果。
 */
@Tag(name = "营销促销")
@RestController
@RequestMapping("/api/marketing/promotions")
public class ApiMarketingPromotionController extends BaseController {

    private final IMarketingPromotionService promotionService;

    public ApiMarketingPromotionController(IMarketingPromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @Operation(summary = "本对象的促销目录(组合包、触发式优惠、人数阶梯;只读)")
    @GetMapping("/catalog")
    public AjaxResult catalog(@RequestParam("ownerType") Integer ownerType,
                              @RequestParam("ownerId") Long ownerId) {
        long memberId = requireAppMemberId();
        Date now = new Date();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverNow", now.getTime());
        data.put("offers", promotionService.listOffers(memberId, ownerType, ownerId, now));
        data.put("promotions", promotionService.listPromotions(memberId, ownerType, ownerId, now));
        List<MarketingTier> tiers = promotionService.listTiers(ownerType, ownerId, now);
        data.put("tiers", tiers == null ? new ArrayList<>() : tiers);
        return success(data);
    }

    /** 理由码随响应返回,客户端按码分支;文案可改。 */
    @ExceptionHandler(MarketingPromotionException.class)
    public AjaxResult handleRejection(MarketingPromotionException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }

    /**
     * 取真实登录身份。{@link #getAppUserId()} 未登录时抛 401,原样向上;
     * 这里只兜"认证通过但 principal 无 userId",仍按 401 处理,绝不返回伪身份。
     */
    private long requireAppMemberId() {
        Long memberId = getAppUserId();
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED);
        }
        return memberId;
    }
}

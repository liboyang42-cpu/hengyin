package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.MarketingOffer;
import com.chengyinhub.business.domain.MarketingPromotion;
import com.chengyinhub.business.domain.MarketingTier;
import com.chengyinhub.business.domain.dto.MarketingOfferSaveRequest;
import com.chengyinhub.business.exception.MarketingPromotionException;
import com.chengyinhub.business.service.IMarketingPromotionConfigService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 促销配置后台(组合包、人数阶梯、触发式优惠)。
 *
 * <p><b>权限是双闸</b>:菜单权限({@code @ss.hasPermi})之外,服务层还必须过对象级范围校验
 * —— 只有主题/活动归属方或全局账号能写。仅有菜单权限读不到别人的配置也改不了。
 *
 * <p><b>默认关闭</b>:新增权限串未获批准前不会落 menu SQL,因此这些接口对现有角色一律
 * 403 —— 这是刻意的 fail-closed,不是"已上线"。
 */
@Tag(name = "促销配置")
@RestController
@RequestMapping("/business/marketing")
public class MarketingPromotionController extends BaseController {

    private final IMarketingPromotionConfigService configService;

    public MarketingPromotionController(IMarketingPromotionConfigService configService) {
        this.configService = configService;
    }

    // ------------------------------------------------------------------ 组合包

    @Operation(summary = "组合包列表")
    @PreAuthorize("@ss.hasPermi('business:marketing:list')")
    @GetMapping("/offer/list")
    public TableDataInfo listOffers(MarketingOffer query) {
        startPage();
        return getDataTable(configService.listOffers(query));
    }

    @Operation(summary = "组合包详情(含成员行)")
    @PreAuthorize("@ss.hasPermi('business:marketing:query')")
    @GetMapping("/offer/{id}")
    public AjaxResult getOffer(@PathVariable("id") Long id) {
        return success(configService.getOffer(id));
    }

    @Operation(summary = "新增组合包")
    @PreAuthorize("@ss.hasPermi('business:marketing:add')")
    @Log(title = "促销配置-优惠供给", businessType = BusinessType.INSERT)
    @PostMapping("/offer")
    public AjaxResult addOffer(@RequestBody MarketingOfferSaveRequest request) {
        return toAjax(configService.createOffer(request, request.getItems()));
    }

    @Operation(summary = "修改组合包(成员整体替换,规则版本自增)")
    @PreAuthorize("@ss.hasPermi('business:marketing:edit')")
    @Log(title = "促销配置-优惠供给", businessType = BusinessType.UPDATE)
    @PutMapping("/offer")
    public AjaxResult editOffer(@RequestBody MarketingOfferSaveRequest request) {
        return toAjax(configService.updateOffer(request, request.getItems()));
    }

    @Operation(summary = "删除组合包(软删)")
    @PreAuthorize("@ss.hasPermi('business:marketing:remove')")
    @Log(title = "促销配置-优惠供给", businessType = BusinessType.DELETE)
    @DeleteMapping("/offer/{ids}")
    public AjaxResult removeOffers(@PathVariable Long[] ids) {
        return toAjax(configService.deleteOffers(ids));
    }

    // ------------------------------------------------------------------ 阶梯价

    @Operation(summary = "人数阶梯价列表")
    @PreAuthorize("@ss.hasPermi('business:marketing:list')")
    @GetMapping("/tier/list")
    public TableDataInfo listTiers(MarketingTier query) {
        startPage();
        return getDataTable(configService.listTiers(query));
    }

    @Operation(summary = "新增人数阶梯价")
    @PreAuthorize("@ss.hasPermi('business:marketing:add')")
    @Log(title = "促销配置-人数阶梯", businessType = BusinessType.INSERT)
    @PostMapping("/tier")
    public AjaxResult addTier(@RequestBody MarketingTier tier) {
        return toAjax(configService.createTier(tier));
    }

    @Operation(summary = "修改人数阶梯价(规则版本自增)")
    @PreAuthorize("@ss.hasPermi('business:marketing:edit')")
    @Log(title = "促销配置-人数阶梯", businessType = BusinessType.UPDATE)
    @PutMapping("/tier")
    public AjaxResult editTier(@RequestBody MarketingTier tier) {
        return toAjax(configService.updateTier(tier));
    }

    @Operation(summary = "删除人数阶梯价(软删)")
    @PreAuthorize("@ss.hasPermi('business:marketing:remove')")
    @Log(title = "促销配置-人数阶梯", businessType = BusinessType.DELETE)
    @DeleteMapping("/tier/{ids}")
    public AjaxResult removeTiers(@PathVariable Long[] ids) {
        return toAjax(configService.deleteTiers(ids));
    }

    // ------------------------------------------------------------------ 触发式优惠

    @Operation(summary = "触发式优惠列表")
    @PreAuthorize("@ss.hasPermi('business:marketing:list')")
    @GetMapping("/promotion/list")
    public TableDataInfo listPromotions(MarketingPromotion query) {
        startPage();
        return getDataTable(configService.listPromotions(query));
    }

    @Operation(summary = "新增触发式优惠(完局限时/首关)")
    @PreAuthorize("@ss.hasPermi('business:marketing:add')")
    @Log(title = "促销配置-触发优惠", businessType = BusinessType.INSERT)
    @PostMapping("/promotion")
    public AjaxResult addPromotion(@RequestBody MarketingPromotion promotion) {
        return toAjax(configService.createPromotion(promotion));
    }

    @Operation(summary = "修改触发式优惠(规则版本自增)")
    @PreAuthorize("@ss.hasPermi('business:marketing:edit')")
    @Log(title = "促销配置-触发优惠", businessType = BusinessType.UPDATE)
    @PutMapping("/promotion")
    public AjaxResult editPromotion(@RequestBody MarketingPromotion promotion) {
        return toAjax(configService.updatePromotion(promotion));
    }

    @Operation(summary = "删除触发式优惠(软删)")
    @PreAuthorize("@ss.hasPermi('business:marketing:remove')")
    @Log(title = "促销配置-触发优惠", businessType = BusinessType.DELETE)
    @DeleteMapping("/promotion/{ids}")
    public AjaxResult removePromotions(@PathVariable Long[] ids) {
        return toAjax(configService.deletePromotions(ids));
    }

    /** 理由码随响应返回,后台按码分支,不解析人工文案。 */
    @ExceptionHandler(MarketingPromotionException.class)
    public AjaxResult handleRejection(MarketingPromotionException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }
}

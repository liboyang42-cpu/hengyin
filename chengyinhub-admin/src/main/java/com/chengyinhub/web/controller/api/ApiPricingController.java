package com.chengyinhub.web.controller.api;

import java.math.BigDecimal;
import java.util.Map;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.chengyinhub.business.domain.dto.PricingResult;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPricingService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.common.utils.OwnershipUtils;
/**
 * 定价(报告§5.4 · B2)。招募锁定(lifecycle=2)后进入定价:系统算底价 P_min,发布者定终价开售(lifecycle=3)。
 */
@RestController
@Tag(name = "主题定价")
@RequestMapping("/api/topic/pricing")
public class ApiPricingController extends BaseController {

    @Autowired
    private IPricingService pricingService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    @Autowired
    private com.chengyinhub.business.service.ICmsActivityService cmsActivityService;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private com.chengyinhub.business.service.impl.MemberIdentityService memberIdentityService;

    @Autowired
    private com.chengyinhub.business.service.ITopicCommercialReviewService topicCommercialReviewService;

    @Operation(summary = "定价预览(阵容+成本构成+底价P_min)")
    @PostMapping("/preview")
    public AjaxResult preview(@RequestBody Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        Long topicId = asLong(body.get("topicId"));
        if (topicId == null) return error("缺少主题");
        AjaxResult owns = requireOwner(topicId, memberId, asStr(body.get("scope")));
        if (owns != null) return owns;
        try {
            PricingResult r = pricingService.previewTopic(topicId,
                    asBd(body.get("leadCost")), asInt(body.get("teamSize")),
                    asStr(body.get("subType")));
            CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
            // D-16:前端据此区分自由探索(只有门票价)与城市定向
            r.setProductType(topic == null ? null : topic.getProductType());
            return AjaxResult.success(r);
        } catch (com.chengyinhub.business.exception.TopicPricingRuleMissingException e) {
            return error("平台费率暂未配置，请联系平台后重试");
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "确认终价并提交开售审核")
    @PostMapping("/confirm")
    public AjaxResult confirm(@RequestBody Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        Long topicId = asLong(body.get("topicId"));
        if (topicId == null) return error("缺少主题");
        AjaxResult owns = requireOwner(topicId, memberId, asStr(body.get("scope")));
        if (owns != null) return owns;
        try {
            // 双票:guidedPrice(有人带)+ selfPrice(无人带,复用自玩票);兼容旧字段 finalPrice→guided
            BigDecimal guidedPrice = body.get("guidedPrice") != null ? asBd(body.get("guidedPrice")) : asBd(body.get("finalPrice"));
            BigDecimal selfPrice = asBd(body.get("selfPrice"));
            // 第四轮(多票价):作者发了几张票时逐张确认;归属/覆盖/地板在服务层按本主题的票校验。
            Map<Long, BigDecimal> ticketPrices = ticketPrices(body.get("ticketPrices"));
            if (ticketPrices != null) guidedPrice = lowest(ticketPrices);
            // 2026-10-31 业主:开了承接、还在招商的主题可以先不定价,招商结束后在这里定终价。
            // 发布时票价是 0 ⇒ 按免费主题豁免了实名;终价一旦收费,实名闸必须在这一步补上(电商法 §27),
            // 否则「先发免费、后定收费」就是绕过发布者实名的口子。
            if ((isPositive(guidedPrice) || isPositive(selfPrice)) && !memberIdentityService.isRegistered(ownerOf(topicId))) {
                return error("收费主题要先登记发布者实名(姓名与身份证号),再确认终价");
            }
            if (ticketPrices == null) {
                pricingService.confirmPrice(topicId, guidedPrice, selfPrice,
                        asInt(body.get("guidedInventory")), asBd(body.get("leadCost")),
                        asInt(body.get("teamSize")));
            } else {
                pricingService.confirmPrice(topicId, guidedPrice, selfPrice,
                        asInt(body.get("guidedInventory")), asBd(body.get("leadCost")),
                        asInt(body.get("teamSize")), ticketPrices);
            }
            return AjaxResult.success(afterConfirmMessage(topicId, memberId));
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    /**
     * P-01：城市定向没有四店 canonical15 人工事实，锁价后直接提交开售审，回给作者真实的下一步；
     * 自由探索仍等运营保存 canonical15 草稿后提审(原口径不变)。
     * F-04(j2)：收款闸 assertCollectible 自 2026-11-02 起对有主题的票照常收款(走平台商户号)，
     * 锁价回执不再附「平台正在办理支付资质…玩家还不能在线付款」；付费与否只影响上面的实名闸。
     */
    private String afterConfirmMessage(Long topicId, Long memberId) {
        return saleReviewMessage(topicId, memberId);
    }

    private String saleReviewMessage(Long topicId, Long memberId) {
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        if (topic == null || !Integer.valueOf(com.chengyinhub.common.constant.ProductType.ORIENTEERING)
                .equals(topic.getProductType())) {
            return "已锁价，等待运营开售";
        }
        try {
            com.chengyinhub.business.domain.vo.TopicSaleReviewProgress progress =
                    topicCommercialReviewService.submitSaleReviewIfReady(topicId, memberId);
            if (progress != null && progress.getTaskId() != null) return "终价已确认，已提交开售审核，审核通过后开售";
            return "终价已确认。" + (progress == null || progress.getNextStep() == null
                    ? "请等待开售审核" : progress.getNextStep());
        } catch (IllegalArgumentException | IllegalStateException e) {
            logger.warn("[P-01] 城市定向锁价后提交开售审失败 topicId={}: {}", topicId, e.getMessage());
            return "终价已确认，开售审核暂未提交：" + e.getMessage();
        }
    }

    /**
     * 仅主题发布者可定价。商家岗位(scope=merchant)与 /api/topic/template/use 同一口径:
     * 按岗位解析出店主作为主题 owner(需 PROJECT_MANAGE 权限),再校验主题确属该 owner;个人视角不变。
     */
    private AjaxResult requireOwner(Long topicId, Long memberId, String scope) {
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        if (topic == null) return error("主题不存在");
        Long owner = memberId;
        if (MerchantOperationScope.isMerchant(scope)) {
            try {
                owner = merchantAccessService.require(memberId, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId();
            } catch (ServiceException e) {
                return new AjaxResult(e.getCode(), e.getMessage());
            }
        }
        if (OwnershipUtils.notOwner(topic.getMemberId(), owner)) return error("仅主题发布者可定价");
        return null;
    }

    /**
     * D-19:主办方自己的自由探索场次里「结算待处理」的清单(跨俱乐部/四店探店日暂无结算路径,分钱规则待业主拍板)。
     * 只读,仅本人(或其商家岗位解析出的店主)名下;返回 [{activityId,activityName,topicId,money,state:"结算待处理",reason}]。
     */
    @Operation(summary = "结算待处理的自由探索场次")
    @GetMapping("/settlement-pending")
    public AjaxResult settlementPending(@RequestParam(value = "scope", required = false) String scope) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        Long owner = memberId;
        if (MerchantOperationScope.isMerchant(scope)) {
            try {
                owner = merchantAccessService.require(memberId, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId();
            } catch (ServiceException e) {
                return new AjaxResult(e.getCode(), e.getMessage());
            }
        }
        return AjaxResult.success(cmsActivityService.listStrandedFreeExploreSettlements(owner));
    }

    /**
     * D-23:自由探索锁价后的状态入口。仅主题发布者(或其商家岗位)可读。
     * 返回 {topicId, productType, lifecycle, priced, status, message};
     * status: PRICING(未锁价) / LOCKED_AWAITING_OPS(已锁价,等待运营开售,自由探索) / IN_REVIEW(城市定向已提开售审) / ON_SALE。
     */
    @Operation(summary = "定价后状态(已锁价/等待开售)")
    @GetMapping("/sale-status")
    public AjaxResult saleStatus(@RequestParam("topicId") Long topicId,
                                 @RequestParam(value = "scope", required = false) String scope) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        AjaxResult owns = requireOwner(topicId, memberId, scope);
        if (owns != null) return owns;
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        Integer lifecycle = topic.getLifecycle();
        boolean priced = lifecycle != null && lifecycle > 2;
        String status;
        String message;
        if (!priced) {
            status = "PRICING";
            message = "尚未锁价";
        } else if (lifecycle != null && lifecycle >= 4) {
            status = "ON_SALE";
            message = "已开售";
        } else if (Integer.valueOf(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            status = "LOCKED_AWAITING_OPS";
            message = "已锁价，等待运营开售";
        } else {
            status = "IN_REVIEW";
            message = "已锁价，开售审核中";
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("topicId", topicId);
        data.put("productType", topic.getProductType());
        data.put("lifecycle", lifecycle);
        data.put("priced", priced);
        data.put("status", status);
        data.put("message", message);
        return AjaxResult.success(data);
    }

    private Long ownerOf(Long topicId) {
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        return topic == null ? null : topic.getMemberId();
    }

    /** ticketPrices: [{ticketId, price}];缺省/空数组返回 null(单价老路径)。格式不对按 IllegalStateException 原话退回。 */
    static Map<Long, BigDecimal> ticketPrices(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof java.util.List)) throw new IllegalStateException("票价格式不正确");
        java.util.List<?> rows = (java.util.List<?>) raw;
        if (rows.isEmpty()) return null;
        Map<Long, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (Object row : rows) {
            if (!(row instanceof Map)) throw new IllegalStateException("票价格式不正确");
            Map<?, ?> m = (Map<?, ?>) row;
            Long id;
            BigDecimal price;
            try {
                id = asLong(m.get("ticketId"));
                price = asBd(m.get("price"));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("票价格式不正确");
            }
            if (id == null || price == null) throw new IllegalStateException("每张票都要填写终价");
            if (out.put(id, price) != null) throw new IllegalStateException("票价格式不正确");
        }
        return out;
    }

    private static BigDecimal lowest(Map<Long, BigDecimal> prices) {
        BigDecimal low = null;
        for (BigDecimal p : prices.values()) if (low == null || p.compareTo(low) < 0) low = p;
        return low;
    }

    private static boolean isPositive(BigDecimal v) { return v != null && v.signum() > 0; }

    private static Long asLong(Object o) { return o == null ? null : Long.valueOf(String.valueOf(o).trim()); }
    private static Integer asInt(Object o) { return o == null ? null : Integer.valueOf(String.valueOf(o).trim()); }
    private static String asStr(Object o) { return o == null ? null : String.valueOf(o); }
    private static BigDecimal asBd(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) return null;
        return new BigDecimal(String.valueOf(o).trim());
    }
}

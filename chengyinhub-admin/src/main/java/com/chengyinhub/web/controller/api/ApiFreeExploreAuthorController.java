package com.chengyinhub.web.controller.api;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.service.IMerchantOfferService;
import com.chengyinhub.business.service.ITopicAuthorSaleService;
import com.chengyinhub.business.service.impl.MemberIdentityService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F10(FE-G13/FE-G14)自由探索作者路径:自己承接商家位置 → 位置全部承接 → 编辑器填价 → 直接发布开售。
 *
 * <p>所有写接口都按 {@code topic.member_id} 校验作者归属(在服务层的行锁内判定)。
 */
@RestController
@Tag(name = "自由探索作者发布")
@RequestMapping("/api/topic")
public class ApiFreeExploreAuthorController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ApiFreeExploreAuthorController.class);

    @Autowired
    private IMerchantOfferService merchantOfferService;
    @Autowired
    private ITopicAuthorSaleService topicAuthorSaleService;
    @Autowired
    private MemberIdentityService memberIdentityService;

    @Operation(summary = "作者自己承接一个商家位置(权益卡)")
    @PostMapping("/chapter/self-supply")
    public AjaxResult selfSupply(@RequestBody Map<String, Object> body) {
        Long memberId = currentMember();
        if (memberId == null) return error("请先登录");
        try {
            Long topicId = asLong(body.get("topicId"));
            Long chapterId = asLong(body.get("chapterId"));
            if (topicId == null || chapterId == null) return error("缺少主题或章节");
            Long offerId = merchantOfferService.authorSelfSupply(memberId, topicId, chapterId, perk(body.get("perk")));
            Map<String, Object> data = new HashMap<>();
            data.put("offerId", offerId);
            data.put("readiness", topicAuthorSaleService.readiness(topicId, memberId));
            return AjaxResult.success(data);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "撤回自己承接(开售前)")
    @PostMapping("/chapter/self-supply/withdraw")
    public AjaxResult withdrawSelfSupply(@RequestBody Map<String, Object> body) {
        Long memberId = currentMember();
        if (memberId == null) return error("请先登录");
        try {
            Long topicId = asLong(body.get("topicId"));
            Long offerId = asLong(body.get("offerId"));
            if (topicId == null || offerId == null) return error("缺少主题或承接记录");
            Integer lifecycle = merchantOfferService.withdrawAuthorSelfSupply(memberId, topicId, offerId);
            Map<String, Object> data = new HashMap<>();
            data.put("lifecycle", lifecycle);
            return AjaxResult.success(data);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "自由探索作者填价发布前的位置承接状态")
    @GetMapping("/fe/sale-readiness")
    public AjaxResult saleReadiness(@RequestParam("topicId") Long topicId) {
        Long memberId = currentMember();
        if (memberId == null) return error("请先登录");
        try {
            return AjaxResult.success(topicAuthorSaleService.readiness(topicId, memberId));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "自由探索作者填完票价直接发布开售")
    @PostMapping("/fe/publish-for-sale")
    public AjaxResult publishForSale(@RequestBody Map<String, Object> body) {
        Long memberId = currentMember();
        if (memberId == null) return error("请先登录");
        try {
            Long topicId = asLong(body.get("topicId"));
            if (topicId == null) return error("缺少主题");
            Map<Long, BigDecimal> prices = ticketPrices(body.get("ticketPrices"));
            if (prices == null || prices.isEmpty()) return error("请填写票价");
            // 电商法 §27:收费主题发布者必须先实名;0 元(免费)跳过,与定价页同一道闸。
            boolean charged = prices.values().stream().anyMatch(p -> p != null && p.signum() > 0);
            if (charged && !memberIdentityService.isRegistered(memberId)) {
                return error("收费主题要先登记发布者实名(姓名与身份证号)，再发布开售");
            }
            long sellingAt = topicAuthorSaleService.publishForSale(topicId, memberId, prices);
            Map<String, Object> data = new HashMap<>();
            data.put("topicId", topicId);
            data.put("lifecycle", 3);
            data.put("sellingStartedAt", sellingAt);
            return AjaxResult.success("已发布开售", data);
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.info("[F10] 作者发布开售被拒 topicId={} reason={}", body.get("topicId"), e.getMessage());
            return error(e.getMessage());
        }
    }

    private Long currentMember() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0 ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    static IMerchantOfferService.AuthorSelfSupplyPerk perk(Object raw) {
        if (!(raw instanceof Map)) throw new IllegalStateException("请填写权益卡");
        Map<?, ?> m = (Map<?, ?>) raw;
        IMerchantOfferService.AuthorSelfSupplyPerk perk = new IMerchantOfferService.AuthorSelfSupplyPerk();
        perk.setName(asStr(m.get("name")));
        perk.setRedeemRule(asStr(m.get("redeemRule")));
        try {
            perk.setCapacity(asInt(m.get("capacity")));
            perk.setPerkType(asInt(m.get("perkType")));
            perk.setRetailValue(asBd(m.get("retailValue")));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("权益卡格式不正确");
        }
        String validEnd = asStr(m.get("validEnd"));
        if (validEnd != null && !validEnd.trim().isEmpty()) {
            try {
                perk.setValidEnd(java.sql.Date.valueOf(java.time.LocalDate.parse(validEnd.trim().substring(0, Math.min(10, validEnd.trim().length())))));
            } catch (RuntimeException e) {
                throw new IllegalStateException("权益有效期格式不正确");
            }
        }
        return perk;
    }

    /** 接受 {"<ticketId>": price} 或 [{ticketId, price}] 两种形态。 */
    static Map<Long, BigDecimal> ticketPrices(Object raw) {
        if (raw == null) return null;
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        try {
            if (raw instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                    Long id = asLong(e.getKey());
                    BigDecimal price = asBd(e.getValue());
                    if (id == null || price == null) throw new IllegalStateException("每张票都要填写票价");
                    out.put(id, price);
                }
            } else if (raw instanceof List) {
                for (Object row : (List<?>) raw) {
                    if (!(row instanceof Map)) throw new IllegalStateException("票价格式不正确");
                    Map<?, ?> m = (Map<?, ?>) row;
                    Long id = asLong(m.get("ticketId"));
                    BigDecimal price = asBd(m.get("price"));
                    if (id == null || price == null) throw new IllegalStateException("每张票都要填写票价");
                    if (out.put(id, price) != null) throw new IllegalStateException("票价格式不正确");
                }
            } else {
                throw new IllegalStateException("票价格式不正确");
            }
        } catch (NumberFormatException e) {
            throw new IllegalStateException("票价格式不正确");
        }
        return out;
    }

    private static Long asLong(Object o) {
        return o == null || String.valueOf(o).trim().isEmpty() ? null : Long.valueOf(String.valueOf(o).trim());
    }
    private static Integer asInt(Object o) {
        return o == null || String.valueOf(o).trim().isEmpty() ? null : Integer.valueOf(String.valueOf(o).trim());
    }
    private static String asStr(Object o) { return o == null ? null : String.valueOf(o); }
    private static BigDecimal asBd(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) return null;
        return new BigDecimal(String.valueOf(o).trim());
    }
}

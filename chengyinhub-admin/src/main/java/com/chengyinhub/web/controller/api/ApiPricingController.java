package com.chengyinhub.web.controller.api;

import java.math.BigDecimal;
import java.util.Map;

import com.chengyinhub.business.domain.CmsTopic;
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
    private ICmsTopicService cmsTopicService;

    @Autowired
    private com.chengyinhub.business.service.impl.MemberIdentityService memberIdentityService;

    @Operation(summary = "定价预览(阵容+成本构成+底价P_min)")
    @PostMapping("/preview")
    public AjaxResult preview(@RequestBody Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        Long topicId = asLong(body.get("topicId"));
        if (topicId == null) return error("缺少主题");
        AjaxResult owns = requireOwner(topicId, memberId);
        if (owns != null) return owns;
        try {
            PricingResult r = pricingService.previewTopic(topicId,
                    asBd(body.get("leadCost")), asInt(body.get("teamSize")),
                    asStr(body.get("subType")));
            return AjaxResult.success(r);
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
        AjaxResult owns = requireOwner(topicId, memberId);
        if (owns != null) return owns;
        try {
            // 双票:guidedPrice(有人带)+ selfPrice(无人带,复用自玩票);兼容旧字段 finalPrice→guided
            BigDecimal guidedPrice = body.get("guidedPrice") != null ? asBd(body.get("guidedPrice")) : asBd(body.get("finalPrice"));
            BigDecimal selfPrice = asBd(body.get("selfPrice"));
            // 2026-10-31 业主:开了承接、还在招商的主题可以先不定价,招商结束后在这里定终价。
            // 发布时票价是 0 ⇒ 按免费主题豁免了实名;终价一旦收费,实名闸必须在这一步补上(电商法 §27),
            // 否则「先发免费、后定收费」就是绕过发布者实名的口子。
            if ((isPositive(guidedPrice) || isPositive(selfPrice)) && !memberIdentityService.isRegistered(ownerOf(topicId))) {
                return error("收费主题要先登记发布者实名(姓名与身份证号),再确认终价");
            }
            pricingService.confirmPrice(topicId, guidedPrice, selfPrice,
                    asInt(body.get("guidedInventory")), asBd(body.get("leadCost")),
                    asInt(body.get("teamSize")));
            return AjaxResult.success("终价已确认，请等待开售审核");
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    /** 仅主题发布者可定价 */
    private AjaxResult requireOwner(Long topicId, Long memberId) {
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        if (topic == null) return error("主题不存在");
        if (OwnershipUtils.notOwner(topic.getMemberId(), memberId)) return error("仅主题发布者可定价");
        return null;
    }

    private Long ownerOf(Long topicId) {
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        return topic == null ? null : topic.getMemberId();
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

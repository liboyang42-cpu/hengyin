package com.chengyinhub.web.controller.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Three legacy app names that the served backend already answers under another path. The request (method, headers,
 * body) is forwarded unchanged, so the target keeps its own auth, validation and envelope:
 * <ul>
 *   <li>POST /api/merchant/insight → POST /api/ai/merchant/insight (store insight; merchant only);</li>
 *   <li>POST /api/merchant/poi/detail → POST /api/merchant/public-detail (public store detail, JSON {id});</li>
 *   <li>POST /api/merchant/shop/apply → POST /api/merchant/merchant_registration (store application, JSON).</li>
 * </ul>
 */
@Controller
public class ApiMerchantLegacyAliasController {
    @PostMapping("/api/merchant/insight")
    public String insight() { return "forward:/api/ai/merchant/insight"; }

    @PostMapping("/api/merchant/poi/detail")
    public String poiDetail() { return "forward:/api/merchant/public-detail"; }

    @PostMapping("/api/merchant/shop/apply")
    public String shopApply() { return "forward:/api/merchant/merchant_registration"; }
}

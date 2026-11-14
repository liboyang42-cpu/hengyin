package com.chengyinhub.web.controller.api;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.citygame.MerchantCityValueReportService;
import com.chengyinhub.business.merchantgap.MarketingConsentResultService;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantgap.MerchantNpcGapService;
import com.chengyinhub.business.merchantgap.MerchantOfferWithdrawalRequestService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * Merchant routes the app calls that had no backend (be-gap-merchant): store-NPC resources, the supply-exit
 * request protocol, the marketing-consent original result and the CITY merchant value report.
 * Every route acts as the logged-in member. A refusal answers HTTP status == code with {@code errorCode};
 * none of these routes moves money.
 */
@RestController
public class ApiMerchantGapController extends BaseController {
    private final MerchantNpcGapService npc;
    private final MerchantOfferWithdrawalRequestService offers;
    private final MarketingConsentResultService consents;
    private final MerchantCityValueReportService cityValue;

    public ApiMerchantGapController(MerchantNpcGapService npc, MerchantOfferWithdrawalRequestService offers,
            MarketingConsentResultService consents, MerchantCityValueReportService cityValue) {
        this.npc = npc;
        this.offers = offers;
        this.consents = consents;
        this.cityValue = cityValue;
    }

    private Long member() {
        try {
            Long id = getAppUserId();
            if (id == null || id <= 0) throw new MerchantGapException(401, "UNAUTHORIZED", "请先登录");
            return id;
        } catch (MerchantGapException e) {
            throw e;
        } catch (Exception e) {
            throw new MerchantGapException(401, "UNAUTHORIZED", "请先登录");
        }
    }

    static ResponseEntity<AjaxResult> answer(Supplier<Object> call) {
        try {
            return ResponseEntity.ok(AjaxResult.success(call.get()));
        } catch (MerchantGapException e) {
            AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
            body.put("errorCode", e.getErrorCode());
            return ResponseEntity.status(e.getStatus()).body(body);
        }
    }

    // ---- store NPC ----

    @PostMapping("/api/merchant/npc/voice/script")
    public ResponseEntity<AjaxResult> voiceScript(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> {
            if (body != null && !body.isEmpty()) throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
            return npc.voiceScript(member());
        });
    }

    @PostMapping("/api/merchant/npc/voice/revoke")
    public ResponseEntity<AjaxResult> voiceRevoke(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> npc.voiceRevoke(member(), body));
    }

    @PostMapping("/api/merchant/npc/avatar/generate")
    public ResponseEntity<AjaxResult> avatarGenerate(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> npc.avatarGenerate(member(), body));
    }

    @PostMapping("/api/merchant/npc/avatar/status")
    public ResponseEntity<AjaxResult> avatarStatus(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> npc.avatarStatus(member()));
    }

    @PostMapping("/api/merchant/npc/knowledge-review")
    public ResponseEntity<AjaxResult> knowledgeReview(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> npc.submitKnowledge(member(), body));
    }

    @GetMapping("/api/merchant/npc/knowledge-review/request")
    public ResponseEntity<AjaxResult> knowledgeRequest(@RequestParam(required = false) String requestId) {
        return answer(() -> npc.knowledgeRequest(member(), requestId));
    }

    // ---- ordinary chapter-supply exit, original request protocol ----

    @GetMapping("/api/merchant/offer-withdrawal/request/current")
    public ResponseEntity<AjaxResult> offerCurrent(@RequestParam(required = false) Long merchantId, @RequestParam(required = false) Long offerId) {
        return answer(() -> offers.current(member(), merchantId, offerId));
    }

    @GetMapping("/api/merchant/offer-withdrawal/request/result")
    public ResponseEntity<AjaxResult> offerResult(@RequestParam(required = false) Long merchantId, @RequestParam(required = false) Long offerId,
            @RequestParam(required = false) String requestId) {
        return answer(() -> offers.result(member(), merchantId, offerId, requestId));
    }

    @PostMapping("/api/merchant/offer-withdrawal/request/withdraw")
    public ResponseEntity<AjaxResult> offerWithdraw(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> offers.withdraw(member(), body));
    }

    // ---- marketing consent original result ----

    @GetMapping("/api/merchant/crm/marketing-consents/result")
    public ResponseEntity<AjaxResult> consentResult(@RequestParam(required = false) Long merchantRowId,
            @RequestParam(required = false) Long merchantOwnerMemberId, @RequestParam(required = false) String channel,
            @RequestParam(required = false) String requestId) {
        return answer(() -> consents.result(member(), merchantRowId, merchantOwnerMemberId, channel, requestId));
    }

    // ---- CITY merchant value ----

    @GetMapping("/api/merchant/city-value/report")
    public ResponseEntity<AjaxResult> cityValueReport(@RequestParam Map<String, String> query) {
        return answer(() -> cityValue.report(member(), query));
    }

    @GetMapping("/api/merchant/city-value/result/{id}")
    public ResponseEntity<AjaxResult> cityValueResult(@PathVariable long id, @RequestParam Map<String, String> query) {
        return answer(() -> cityValue.result(member(), id, query));
    }

    @GetMapping("/api/merchant/city-value/events")
    public ResponseEntity<AjaxResult> cityValueEvents(@RequestParam Map<String, String> query) {
        return answer(() -> cityValue.events(member(), query));
    }

    @GetMapping("/api/merchant/city-value/event/{eventId}/benefit")
    public ResponseEntity<AjaxResult> cityValueBenefit(@PathVariable long eventId, @RequestParam Map<String, String> query) {
        return answer(() -> cityValue.benefit(member(), eventId, query));
    }
}

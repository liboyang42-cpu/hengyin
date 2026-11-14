package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.citygame.*;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * r491 CITY surfaces on the one board engine (gaps A pass 2): action budget, self-funded acquisition, store-attested
 * arrival evidence, neutral and onsite tasks, personal task history, merchant operation intents, player value
 * visits and the walking target. Every route acts as the logged-in member only. A command reply and its receipt
 * read are the same stored body with the same HTTP status (code == status), plus errorCode on refusal.
 */
@RestController
public class ApiCityGameR491Controller extends BaseController {
    private final CityActionBudgetService budget;
    private final CityAcquisitionService acquisition;
    private final CityArrivalService arrival;
    private final CityTaskService tasks;
    private final CityMerchantValueService merchantValue;
    private final CityGameReadService reads;

    public ApiCityGameR491Controller(CityActionBudgetService budget, CityAcquisitionService acquisition, CityArrivalService arrival, CityTaskService tasks,
                                     CityMerchantValueService merchantValue, CityGameReadService reads) {
        this.budget = budget;
        this.acquisition = acquisition;
        this.arrival = arrival;
        this.tasks = tasks;
        this.merchantValue = merchantValue;
        this.reads = reads;
    }

    private Long member() {
        try {
            return getAppUserId();
        } catch (Exception e) {
            throw new CityGameException(401, "UNAUTHORIZED", "请先登录");
        }
    }

    private static ResponseEntity<AjaxResult> outcome(CityR491Outcomes.View view) {
        AjaxResult body = new AjaxResult(view.status, view.status == 200 ? "操作成功" : "指令未生效", view.data);
        if (view.errorCode != null) body.put("errorCode", view.errorCode);
        return ResponseEntity.status(view.status).body(body);
    }

    // ---- action budget ----

    @GetMapping("/api/city-game/action-budget/state")
    public AjaxResult budgetState(@RequestParam String regionId) { return success(budget.state(member(), regionId)); }

    @PostMapping("/api/city-game/action-budget/command")
    public ResponseEntity<AjaxResult> budgetCommand(@RequestBody Map<String, Object> body) { return outcome(CityR491Outcomes.view(budget.command(member(), body))); }

    @GetMapping("/api/city-game/action-budget/request/{requestId}")
    public ResponseEntity<AjaxResult> budgetReceipt(@PathVariable String requestId) { return outcome(CityR491Outcomes.view(budget.receipt(member(), requestId))); }

    // ---- acquisition ----

    @GetMapping("/api/city-game/acquisition/state")
    public AjaxResult acquisitionState(@RequestParam String regionId) { return success(acquisition.state(member(), regionId)); }

    @GetMapping("/api/city-game/acquisition/titles")
    public AjaxResult acquisitionTitles(@RequestParam String regionId) { return success(acquisition.titlesRead(member(), regionId)); }

    @GetMapping("/api/city-game/acquisition/quote/{quoteId}")
    public AjaxResult acquisitionQuote(@PathVariable String quoteId) { return success(acquisition.quoteRead(member(), quoteId)); }

    @PostMapping("/api/city-game/acquisition/command")
    public ResponseEntity<AjaxResult> acquisitionCommand(@RequestBody Map<String, Object> body) {
        return outcome(CityR491Outcomes.view(acquisition.command(member(), body)));
    }

    @GetMapping("/api/city-game/acquisition/request/{requestId}")
    public ResponseEntity<AjaxResult> acquisitionReceipt(@PathVariable String requestId) {
        return outcome(CityR491Outcomes.view(acquisition.receipt(member(), requestId)));
    }

    // ---- arrival evidence ----

    @GetMapping("/api/city-game/arrival-evidence/state")
    public AjaxResult arrivalState(@RequestParam String regionId) { return success(arrival.state(member(), regionId)); }

    @GetMapping("/api/city-game/arrival-evidence/merchant")
    public AjaxResult arrivalMerchant() { return success(arrival.merchant(member())); }

    @PostMapping("/api/city-game/arrival-evidence/command")
    public ResponseEntity<AjaxResult> arrivalCommand(@RequestBody Map<String, Object> body) { return outcome(CityR491Outcomes.view(arrival.command(member(), body))); }

    @GetMapping("/api/city-game/arrival-evidence/request/{requestId}")
    public ResponseEntity<AjaxResult> arrivalReceipt(@PathVariable String requestId) { return outcome(CityR491Outcomes.view(arrival.receipt(member(), requestId))); }

    @GetMapping("/api/city-game/arrival-evidence/evidence/{evidenceId}")
    public AjaxResult arrivalEvidence(@PathVariable String evidenceId) { return success(arrival.evidence(member(), evidenceId)); }

    // ---- neutral and onsite tasks, history ----

    @GetMapping("/api/city-game/neutral-tasks/state")
    public AjaxResult neutralState(@RequestParam String regionId) { return success(tasks.state(CityTaskService.NEUTRAL, member(), regionId)); }

    @PostMapping("/api/city-game/neutral-tasks/command")
    public ResponseEntity<AjaxResult> neutralCommand(@RequestBody Map<String, Object> body) {
        return outcome(CityR491Outcomes.view(tasks.command(CityTaskService.NEUTRAL, member(), body)));
    }

    @GetMapping("/api/city-game/neutral-tasks/request/{requestId}")
    public ResponseEntity<AjaxResult> neutralReceipt(@PathVariable String requestId) {
        return outcome(CityR491Outcomes.view(tasks.receipt(CityTaskService.NEUTRAL, member(), requestId)));
    }

    @GetMapping("/api/city-game/onsite-tasks/state")
    public AjaxResult onsiteState(@RequestParam String regionId) { return success(tasks.state(CityTaskService.ONSITE, member(), regionId)); }

    @PostMapping("/api/city-game/onsite-tasks/command")
    public ResponseEntity<AjaxResult> onsiteCommand(@RequestBody Map<String, Object> body) {
        return outcome(CityR491Outcomes.view(tasks.command(CityTaskService.ONSITE, member(), body)));
    }

    @GetMapping("/api/city-game/onsite-tasks/request/{requestId}")
    public ResponseEntity<AjaxResult> onsiteReceipt(@PathVariable String requestId) {
        return outcome(CityR491Outcomes.view(tasks.receipt(CityTaskService.ONSITE, member(), requestId)));
    }

    @GetMapping("/api/city-game/onsite-tasks/result/{resultId}")
    public AjaxResult onsiteResult(@PathVariable String resultId) { return success(tasks.onsiteResult(member(), resultId)); }

    @GetMapping("/api/city-game/task-history")
    public AjaxResult history(@RequestParam String regionId, @RequestParam(required = false) String beforeInstanceId) {
        return success(tasks.history(member(), regionId, beforeInstanceId));
    }

    @GetMapping("/api/city-game/task-history/instance/{instanceId}")
    public AjaxResult historyDetail(@RequestParam String regionId, @PathVariable String instanceId) {
        return success(tasks.historyDetail(member(), regionId, instanceId));
    }

    @GetMapping("/api/city-game/task-history/instance/{instanceId}/request/{requestId}")
    public ResponseEntity<AjaxResult> historyReceipt(@RequestParam String regionId, @PathVariable String instanceId, @PathVariable String requestId) {
        return outcome(CityR491Outcomes.view(tasks.historyReceipt(member(), regionId, instanceId, requestId)));
    }

    // ---- merchant operations ----

    @GetMapping("/api/city-game/merchant/operations/availability")
    public AjaxResult merchantAvailability(@RequestParam String regionId) { return success(merchantValue.availability(member(), regionId)); }

    @PostMapping("/api/city-game/merchant/operations/intent")
    public ResponseEntity<AjaxResult> merchantIntent(@RequestBody Map<String, Object> body) {
        return outcome(CityR491Outcomes.view(merchantValue.intent(member(), body)));
    }

    @GetMapping("/api/city-game/merchant/operations/request/{requestId}")
    public ResponseEntity<AjaxResult> merchantReceipt(@PathVariable String requestId) {
        return outcome(CityR491Outcomes.view(merchantValue.merchantReceipt(member(), requestId)));
    }

    // ---- city value visits ----

    @GetMapping("/api/city-value/visits/services")
    public AjaxResult valueServices(@RequestParam Map<String, String> query) { return success(merchantValue.services(member(), query, reads)); }

    @PostMapping("/api/city-value/visits")
    public AjaxResult valueBegin(@RequestBody Map<String, Object> body) { return success(merchantValue.begin(member(), body, reads)); }

    @PostMapping("/api/city-value/visits/feedback")
    public AjaxResult valueFeedback(@RequestBody Map<String, Object> body) { return success(merchantValue.feedback(member(), body)); }

    @GetMapping("/api/city-value/visits/{visitId}")
    public AjaxResult valueVisit(@PathVariable String visitId) { return success(merchantValue.visit(member(), visitId)); }

    // ---- walking target (bare JSON, no envelope: the reader decodes the target object itself) ----

    @GetMapping("/api/city/walking/target")
    public Map<String, Object> walkingTarget(@RequestParam("poi_id") String poiId) { return merchantValue.walkingTarget(member(), poiId); }

    @ExceptionHandler(CityGameException.class)
    public ResponseEntity<AjaxResult> failed(CityGameException e) {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}

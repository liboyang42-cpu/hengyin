package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.citygame.CityFairnessService;
import com.chengyinhub.business.citygame.CityGameActionService;
import com.chengyinhub.business.citygame.CityGameException;
import com.chengyinhub.business.citygame.CityGameParticipationService;
import com.chengyinhub.business.citygame.CityGameReadService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resident CITY game (player). Every endpoint acts as the logged-in member only; ids in the query
 * or body are checked against server state and never select another member. Errors keep the HTTP
 * status, envelope code and errorCode the app contracts branch on.
 */
@RestController
@RequestMapping("/api/city-game")
public class ApiCityGameController extends BaseController {
    private final CityGameReadService reads;
    private final CityGameParticipationService participation;
    private final CityGameActionService actions;
    private final CityFairnessService fairness;

    public ApiCityGameController(CityGameReadService reads, CityGameParticipationService participation,
                                 CityGameActionService actions, CityFairnessService fairness) {
        this.reads = reads;
        this.participation = participation;
        this.actions = actions;
        this.fairness = fairness;
    }

    private Long member() {
        try {
            return getAppUserId();
        } catch (Exception e) {
            throw new CityGameException(401, "UNAUTHORIZED", "请先登录");
        }
    }

    // ---- reads ----

    @GetMapping("/current")
    public AjaxResult current(@RequestParam String regionId) { return success(reads.current(member(), regionId)); }

    @GetMapping("/participation")
    public AjaxResult participation(@RequestParam String regionId, @RequestParam String boardId, @RequestParam String seasonId,
                                    @RequestParam String rulesReleaseId, @RequestParam String revision) {
        return success(reads.participation(member(), regionId, boardId, seasonId, rulesReleaseId, revision));
    }

    @GetMapping("/points")
    public AjaxResult points(@RequestParam String regionId, @RequestParam String boardId, @RequestParam String seasonId,
                             @RequestParam String rulesReleaseId, @RequestParam String revision) {
        return success(reads.points(member(), regionId, boardId, seasonId, rulesReleaseId, revision));
    }

    @GetMapping("/state/snapshot")
    public AjaxResult stateSnapshot(@RequestParam Map<String, String> query) { return success(reads.stateSnapshot(member(), query)); }

    @GetMapping("/player-rules")
    public AjaxResult playerRules(@RequestParam Map<String, String> query) { return success(reads.playerRules(member(), query)); }

    @GetMapping("/team-participation/state")
    public AjaxResult teamState(@RequestParam String regionId) { return success(reads.teamState(member(), regionId)); }

    @GetMapping("/team-participation/invite")
    public AjaxResult invite(@RequestParam String regionId, @RequestParam String code) { return success(reads.invite(member(), regionId, code)); }

    // ---- team participation commands ----

    @PostMapping("/team-participation/command")
    public ResponseEntity<AjaxResult> command(@RequestBody Map<String, Object> body) {
        return outcome(participation.command(member(), body));
    }

    /** Original-command receipts by domain; only team-participation commands are produced here. */
    @GetMapping("/{domain}/request/{requestId}")
    public ResponseEntity<AjaxResult> originalReceipt(@PathVariable String domain, @PathVariable String requestId) {
        if (!"team-participation".equals(domain)) throw new CityGameException(404, "CITY_RECEIPT_NOT_FOUND", "没有这条请求的回执");
        return outcome(participation.receipt(member(), requestId));
    }

    private static ResponseEntity<AjaxResult> outcome(CityGameParticipationService.Outcome outcome) {
        AjaxResult body = new AjaxResult(outcome.status, outcome.status == 200 ? "操作成功" : "指令未生效", outcome.data);
        if (outcome.errorCode != null) body.put("errorCode", outcome.errorCode);
        return ResponseEntity.status(outcome.status).body(body);
    }

    // ---- game loop: snapshot, deltas, quote, action, receipt ----

    @GetMapping("/game/snapshot")
    public AjaxResult gameSnapshot(@RequestParam String boardId) { return success(actions.snapshot(member(), boardId)); }

    @GetMapping("/game/deltas")
    public AjaxResult gameDeltas(@RequestParam String boardId, @RequestParam String seasonId, @RequestParam long after) {
        return success(actions.deltas(member(), boardId, seasonId, after));
    }

    @PostMapping("/game/quote")
    public AjaxResult quote(@RequestBody Map<String, Object> body) { return success(actions.quote(member(), body)); }

    @PostMapping("/game/action")
    public AjaxResult action(@RequestBody Map<String, Object> body) { return success(actions.submit(member(), body)); }

    @GetMapping("/game/action/{requestId}")
    public AjaxResult actionReceipt(@PathVariable String requestId) { return success(actions.receipt(member(), requestId)); }

    // ---- fairness ----

    @GetMapping("/fairness/report")
    public ResponseEntity<Map<String, Object>> fairnessReport(@RequestParam Map<String, String> query) {
        return candidate(200, "操作成功", fairness.report(member(), query));
    }

    @GetMapping("/fairness/outing")
    public ResponseEntity<Map<String, Object>> fairnessOuting(@RequestParam Map<String, String> query) {
        return candidate(200, "操作成功", fairness.outing(member(), query));
    }

    private static ResponseEntity<Map<String, Object>> candidate(int status, String msg, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status);
        body.put("msg", msg);
        body.put("data", data);
        body.put("candidate", true);
        return ResponseEntity.status(status).header("X-Chengyin-Candidate", "city-fairness").body(body);
    }

    @ExceptionHandler(CityGameException.class)
    public ResponseEntity<?> failed(CityGameException e, HttpServletRequest request) {
        if (request.getRequestURI().contains("/api/city-game/fairness/")) {
            Map<String, Object> data = new LinkedHashMap<>();
            if (e.getData() instanceof Map) data.putAll((Map<String, Object>) e.getData());
            data.put("errorCode", e.getErrorCode());
            return candidate(e.getStatus(), e.getMessage(), data);
        }
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}

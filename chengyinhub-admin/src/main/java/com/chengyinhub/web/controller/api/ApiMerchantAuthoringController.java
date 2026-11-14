package com.chengyinhub.web.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantgap.MerchantGapSupport;
import com.chengyinhub.business.service.impl.NpcChatService;
import com.chengyinhub.business.service.merchantauthoring.MerchantAuthoringService;
import com.chengyinhub.business.service.merchantauthoring.MerchantAuthoringService.Request;
import com.chengyinhub.business.service.merchantauthoring.MerchantDraftSelectionService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Merchant authoring workbench (W05), the owner draft chooser and the player reads of a published store game.
 * The client cannot submit an approval conclusion. Every non-200 answer uses the same HTTP status as its
 * {@code code} (202 = outcome unknown, 409 = version conflict), which is what the app branches on.
 */
@RestController
@RequestMapping("/api/merchant/authoring")
public class ApiMerchantAuthoringController extends BaseController {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MerchantAuthoringService authoring;

    @Autowired
    private MerchantDraftSelectionService draftSelection;

    @Autowired
    private NpcChatService npcChat;

    @PostMapping("/jobs")
    public AjaxResult generate(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        return run(response, false, () -> authoring.generate(getAppUserId(), request(body)));
    }

    @GetMapping("/jobs")
    public AjaxResult job(@RequestParam String requestId, HttpServletResponse response) {
        return run(response, false, () -> authoring.findJob(getAppUserId(), requestId));
    }

    @PostMapping("/jobs/cancel")
    public AjaxResult cancel(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        return run(response, false, () -> authoring.cancel(getAppUserId(), text(body, "requestId")));
    }

    @GetMapping("/drafts")
    public AjaxResult drafts(HttpServletResponse response) {
        noStore(response);
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("publishSucceeded", false);
            data.put("drafts", authoring.listDrafts(getAppUserId()));
            return AjaxResult.success(data);
        } catch (ServiceException ex) {
            return fail(response, ex);
        }
    }

    @PostMapping("/drafts")
    public AjaxResult create(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        return run(response, false, () -> authoring.createDraft(getAppUserId(), request(body)));
    }

    @GetMapping("/drafts/{draftId}")
    public AjaxResult draft(@PathVariable String draftId, HttpServletResponse response) {
        return run(response, false, () -> authoring.readDraft(getAppUserId(), draftId));
    }

    @PostMapping("/drafts/{draftId}")
    public AjaxResult save(@PathVariable String draftId, @RequestBody Map<String, Object> body, HttpServletResponse response) {
        return run(response, false, () -> authoring.save(getAppUserId(), draftId, integer(body, "expectedVersion"),
                JSON.valueToTree(body.get("body"))));
    }

    @PostMapping("/drafts/{draftId}/facts")
    public AjaxResult facts(@PathVariable String draftId, @RequestBody Map<String, Object> body, HttpServletResponse response) {
        return run(response, false, () -> authoring.confirmFacts(getAppUserId(), draftId, integer(body, "version")));
    }

    @PostMapping("/drafts/{draftId}/content")
    public AjaxResult content(@PathVariable String draftId, @RequestBody Map<String, Object> body, HttpServletResponse response) {
        return run(response, false, () -> authoring.approveContent(getAppUserId(), draftId, integer(body, "version")));
    }

    @GetMapping("/drafts/{draftId}/review")
    public AjaxResult review(@PathVariable String draftId, HttpServletResponse response) {
        return run(response, false, () -> authoring.reviewStatus(getAppUserId(), draftId));
    }

    @PostMapping("/releases")
    public AjaxResult publish(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        return run(response, false, () -> authoring.publish(getAppUserId(), text(body, "requestId"),
                text(body, "draftId"), integer(body, "version")));
    }

    @PostMapping("/releases/{releaseId}/withdraw")
    public AjaxResult withdraw(@PathVariable long releaseId, @RequestBody Map<String, Object> body, HttpServletResponse response) {
        return run(response, false, () -> authoring.withdraw(getAppUserId(), releaseId, text(body, "requestId")));
    }

    @GetMapping("/releases")
    public AjaxResult release(@RequestParam String requestId, HttpServletResponse response) {
        return run(response, false, () -> authoring.findRelease(getAppUserId(), requestId));
    }

    @GetMapping("/public")
    public AjaxResult published(@RequestParam Long merchantId, @RequestParam(required = false) Long themeId,
            @RequestParam(required = false) Long releaseId, @RequestParam(required = false) String runId,
            @RequestParam String binding, HttpServletResponse response) {
        getAppUserId();
        return run(response, true, () -> authoring.readPublic(merchantId, themeId, releaseId, runId, binding));
    }

    @GetMapping("/environment")
    public AjaxResult environment(@RequestParam long releaseId, HttpServletResponse response) {
        return run(response, false, () -> authoring.environment(getAppUserId(), releaseId));
    }

    @GetMapping("/main-line")
    public AjaxResult mainLine(@RequestParam Long merchantId, @RequestParam(required = false) Long themeId,
            HttpServletResponse response) {
        getAppUserId();
        return run(response, true, () -> authoring.mainLine(merchantId, themeId));
    }

    // ---- owner draft chooser (professional node template reference) ----

    @PostMapping("/draft-selection/list")
    public ResponseEntity<AjaxResult> draftSelectionList(@RequestBody(required = false) Map<String, Object> body) {
        return ApiMerchantGapController.answer(() -> draftSelection.list(appUser(), body));
    }

    @PostMapping("/draft-selection/resolve")
    public ResponseEntity<AjaxResult> draftSelectionResolve(@RequestBody(required = false) Map<String, Object> body) {
        return ApiMerchantGapController.answer(() -> draftSelection.resolve(appUser(), body));
    }

    // ---- player reads: the published store game and the original store-chat request ----

    @GetMapping("/player/{merchantId}/game")
    public ResponseEntity<AjaxResult> playerGame(@PathVariable long merchantId, HttpServletResponse response) {
        noStore(response);
        return ApiMerchantGapController.answer(() -> {
            appUser();
            if (merchantId <= 0) throw new MerchantGapException(400, "INVALID_REQUEST", "门店无效");
            return authoring.playerGame(merchantId);
        });
    }

    @GetMapping("/player/{merchantId}/requests/{requestId}")
    public ResponseEntity<AjaxResult> playerRequest(@PathVariable long merchantId, @PathVariable String requestId,
            HttpServletResponse response) {
        noStore(response);
        return ApiMerchantGapController.answer(() -> {
            Long member = appUser();
            if (merchantId <= 0 || requestId == null || !MerchantGapSupport.ANY_UUID.matcher(requestId).matches()) {
                throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
            }
            NpcChatResp original = npcChat.readOriginalRequest("merchant", merchantId, requestId, member);
            if (original == null || NpcChatService.STATUS_NOT_FOUND.equals(original.getOutcomeStatus())) {
                throw new MerchantGapException(404, "REQUEST_NOT_FOUND", "没有这条原请求");
            }
            if ("INVALID_REQUEST".equals(original.getErrorCode())) {
                throw new MerchantGapException(404, "REQUEST_NOT_FOUND", "没有这条原请求");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("merchantId", merchantId);
            out.put("requestId", original.getRequestId());
            out.put("outcomeStatus", original.getOutcomeStatus());
            out.put("safetyDecision", original.getSafetyDecision());
            out.put("safeText", original.getSafeText());
            out.put("errorCode", original.getErrorCode());
            out.put("retryable", original.isRetryable());
            out.put("ai", original.isAi());
            return out;
        });
    }

    private Long appUser() {
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

    private AjaxResult run(HttpServletResponse response, boolean publicRead, java.util.function.Supplier<Map<String, Object>> call) {
        noStore(response);
        try {
            AjaxResult result = present(call.get(), publicRead);
            status(response, result);
            return result;
        } catch (ServiceException ex) {
            return fail(response, ex);
        }
    }

    private static AjaxResult present(Map<String, Object> body, boolean publicRead) {
        String outcome = String.valueOf(body.get("outcome"));
        if ("UNKNOWN".equals(outcome)) return error(HttpStatus.ACCEPTED, "UNKNOWN", body);
        if (publicRead && !"PUBLISHED".equals(outcome) && !"NONE".equals(outcome)) {
            return error(HttpStatus.FORBIDDEN, outcome, body);
        }
        if ("FAILED".equals(outcome)) {
            return error(HttpStatus.BAD_REQUEST, String.valueOf(body.get("errorCode")), body);
        }
        return AjaxResult.success(body);
    }

    private static AjaxResult fail(HttpServletResponse response, ServiceException ex) {
        int code = ex.getCode() == null ? HttpStatus.ERROR : ex.getCode();
        AjaxResult result = error(code, ex.getMessage(), ex.getData());
        status(response, result);
        return result;
    }

    private static void status(HttpServletResponse response, AjaxResult result) {
        Object code = result.get(AjaxResult.CODE_TAG);
        if (code instanceof Integer value && value != HttpStatus.SUCCESS && value >= 200 && value <= 599) response.setStatus(value);
    }

    private static AjaxResult error(int code, String msg, Object data) {
        AjaxResult result = AjaxResult.error(code, msg);
        result.put("errorCode", msg);
        if (data != null) result.put(AjaxResult.DATA_TAG, data);
        return result;
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    private Request request(Map<String, Object> body) {
        rejectConclusion(body);
        Request request = new Request();
        if (body == null) return request;
        request.requestId = textOrNull(body, "requestId");
        request.factVersion = textOrNull(body, "factVersion");
        request.templateId = textOrNull(body, "templateId");
        request.productId = longOrNull(body, "productId");
        request.skuId = longOrNull(body, "skuId");
        request.themeId = longOrNull(body, "themeId");
        request.runId = textOrNull(body, "runId");
        request.draftId = textOrNull(body, "draftId");
        request.scene = textOrNull(body, "scene");
        return request;
    }

    private static void rejectConclusion(Map<String, Object> body) {
        if (containsApproved(body)) throw new ServiceException("CLIENT_CONCLUSION", HttpStatus.BAD_REQUEST);
    }

    private static boolean containsApproved(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("approved")) return true;
            for (Object child : map.values()) if (containsApproved(child)) return true;
        }
        if (value instanceof Iterable<?> items) {
            for (Object child : items) if (containsApproved(child)) return true;
        }
        return false;
    }

    private static String text(Map<String, Object> body, String field) {
        rejectConclusion(body);
        Object value = body == null ? null : body.get(field);
        if (!(value instanceof String text) || text.isBlank()) throw new ServiceException("INPUT", HttpStatus.BAD_REQUEST);
        return text;
    }

    private static String textOrNull(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOrNull(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        throw new ServiceException("INPUT", HttpStatus.BAD_REQUEST);
    }

    private static int integer(Map<String, Object> body, String field) {
        rejectConclusion(body);
        Object value = body == null ? null : body.get(field);
        if (!(value instanceof Number number)) throw new ServiceException("INPUT", HttpStatus.BAD_REQUEST);
        return number.intValue();
    }
}

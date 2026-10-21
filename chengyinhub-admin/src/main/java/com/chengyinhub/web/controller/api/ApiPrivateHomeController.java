package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.home.PrivateHomeContracts.*;
import com.chengyinhub.business.home.PrivateHomeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Owner transport only. Existing /api authentication applies. New return/restore routes are private-home.return.v1, not /verify or /restore. */
@RestController
@RequestMapping("/api/native/home")
@ConditionalOnProperty(name="app.private-home.owner-api-enabled", havingValue="true")
public class ApiPrivateHomeController {
    @Autowired private PrivateHomeService service;
    @ModelAttribute public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }
    @GetMapping public AjaxResult get() { return call(() -> service.getMyHome()); }
    @PutMapping public AjaxResult set(@RequestBody SetHome body) {
        return call(() -> {
            if (body == null || body.expectedVersion == null) throw new ServiceException("HOME_REQUEST_INVALID");
            return view(service.setMyHome(body.requestId, body.expectedVersion, body.label,
                new Point(body.latitude,body.longitude,body.datum)));
        });
    }
    @DeleteMapping public AjaxResult delete(@RequestBody DeleteHome body) {
        return call(() -> {
            if (body == null || body.expectedVersion == null) throw new ServiceException("HOME_REQUEST_INVALID");
            return view(service.deleteMyHome(body.requestId,body.expectedVersion));
        });
    }
    @PostMapping("/return-verifications") public AjaxResult verifyReturnHome(@RequestBody VerifyReturnHome body) {
        return call(() -> {
            if (body == null || body.expectedVersion == null || body.observation == null) throw new ServiceException("HOME_REQUEST_INVALID");
            Observation observation = new Observation();
            observation.latitude = body.observation.latitude; observation.longitude = body.observation.longitude;
            observation.datum = body.observation.datum; observation.permission = body.observation.permission;
            observation.network = body.observation.network; observation.accuracyMeters = body.observation.accuracyMeters;
            observation.observedAtEpochMillis = body.observation.observedAtEpochMillis; observation.dwellMillis = body.observation.dwellMillis;
            return proofView(service.verifyReturnHome(body.requestId, body.expectedVersion, body.scope, body.evidenceHandle, observation));
        });
    }
    @GetMapping("/return-verifications/{requestId}") public AjaxResult readVerification(@PathVariable String requestId) {
        return call(() -> proofView(service.readMyProof(requestId)));
    }
    @PostMapping("/resource-restores") public AjaxResult restoreResources(@RequestBody RestoreHomeResources body) {
        return call(() -> {
            if (body == null) throw new ServiceException("HOME_REQUEST_INVALID");
            return receiptView(service.restore(body.requestId, body.proofId, body.scope));
        });
    }
    @GetMapping("/resource-restores/{requestId}") public AjaxResult readRestore(@PathVariable String requestId) {
        return call(() -> receiptView(service.readMyReceipt(requestId)));
    }
    /** Binding failures may include raw JSON values; do not route them into generic stack-trace logging. */
    @ExceptionHandler(Exception.class)
    public AjaxResult invalidBody(Exception ignored) { return AjaxResult.error("HOME_REQUEST_INVALID"); }
    private static Map<String,Object> view(Mutation m) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("requestId",m.requestId); result.put("decision",m.decision); result.put("version",m.resultVersion);
        return result;
    }
    private static Map<String,Object> proofView(Proof proof) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("requestId", proof.requestId);
        result.put("decision", "READY".equals(proof.status) ? "VERIFIED" : proof.status);
        result.put("proofId", proof.proofId); result.put("scope", proof.scope); result.put("status", proof.status);
        result.put("issuedAt", proof.issuedAt); result.put("expiresAt", proof.expiresAt); result.put("version", proof.homeVersion);
        return result;
    }
    private static Map<String,Object> receiptView(Receipt receipt) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("requestId", receipt.requestId); result.put("receiptId", receipt.receiptId);
        result.put("proofId", receipt.proofId); result.put("scope", receipt.scope); result.put("decision", receipt.decision);
        result.put("version", receipt.homeVersion);
        result.put("beforeValue", receipt.beforeValue); result.put("afterValue", receipt.afterValue); result.put("amount", receipt.amount);
        return result;
    }
    private AjaxResult call(Supplier<Object> action) {
        try { return AjaxResult.success(action.get()); }
        catch (ServiceException e) {
            // Only service-owned symbolic codes can cross the boundary. Never echo provider/SQL details.
            String code=e.getMessage();
            return AjaxResult.error(code != null && code.matches("(?:HOME|RESOURCE|REQUEST|ARRIVAL|AUTHORITY)_[A-Z_]+") ? code : "HOME_UNAVAILABLE");
        } catch (RuntimeException e) { return AjaxResult.error("HOME_UNAVAILABLE"); }
    }
    public static class DeleteHome extends Redacted {
        public String requestId;
        public Long expectedVersion;
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("HOME_REQUEST_INVALID"); }
    }
    public static final class SetHome extends DeleteHome {
        public String label, datum;
        public BigDecimal latitude, longitude;
    }
    public static final class VerifyReturnHome extends Redacted {
        public String requestId, scope, evidenceHandle;
        public Long expectedVersion;
        public ObservationBody observation;
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("HOME_REQUEST_INVALID"); }
        public static final class ObservationBody extends Redacted {
            public BigDecimal latitude, longitude;
            public String datum, permission, network;
            public Long accuracyMeters, observedAtEpochMillis, dwellMillis;
            @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("HOME_REQUEST_INVALID"); }
        }
    }
    public static final class RestoreHomeResources extends Redacted {
        public String requestId, proofId, scope;
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("HOME_REQUEST_INVALID"); }
    }
}

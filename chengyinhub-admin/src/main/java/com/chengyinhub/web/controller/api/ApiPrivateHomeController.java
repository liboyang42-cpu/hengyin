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

/** Owner transport only. Existing /api authentication applies. No proof/restore HTTP routes or mini-program UI. */
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
    /** Binding failures may include raw JSON values; do not route them into generic stack-trace logging. */
    @ExceptionHandler(Exception.class)
    public AjaxResult invalidBody(Exception ignored) { return AjaxResult.error("HOME_REQUEST_INVALID"); }
    private static Map<String,Object> view(Mutation m) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("requestId",m.requestId); result.put("decision",m.decision); result.put("version",m.resultVersion);
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
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.NativeAppAttestService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.function.Supplier;

/** Native device verification transport. Existing app authentication supplies the account; all gates default off. */
@RestController
@RequestMapping("/api/native/device")
public class ApiNativeDeviceController extends BaseController {
    @Autowired private NativeAppAttestService service;
    @GetMapping("/status")
    public AjaxResult status(@RequestParam(required=false) String deviceKeyId) {
        return call(()->service.status(getAppUserId(),deviceKeyId));
    }
    @PostMapping("/enrollment-challenge")
    public AjaxResult challenge(@RequestBody Map<String,Object> body) {
        return call(()->service.challenge(getAppUserId(),body));
    }
    @PostMapping("/enroll")
    public AjaxResult enroll(@RequestBody Map<String,Object> body) {
        return call(()->service.enroll(getAppUserId(),body));
    }
    @PostMapping("/revoke")
    public AjaxResult revoke(@RequestBody Map<String,Object> body) {
        return call(()->service.revoke(getAppUserId(),body));
    }
    private AjaxResult call(Supplier<Map<String,Object>> operation) {
        try { return success(operation.get()); }
        catch(ServiceException expected) { throw expected; }
        catch(RuntimeException unavailable) { return error("设备验证暂不可用，请稍后重试"); }
    }
}

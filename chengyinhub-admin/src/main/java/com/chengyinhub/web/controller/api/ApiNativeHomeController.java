package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.begapmisc.home.HomeRejection;
import com.chengyinhub.business.begapmisc.home.NativeHomeReturnService;
import com.chengyinhub.business.begapmisc.home.NativeHomeService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/**
 * Native private home ({@code Core/PrivateHomeService}) and "I'm back" HP restore ({@code Core/NativeHomeReturnService}).
 * The owner is the authenticated app account only. Responses are {@code no-store}; refusals are {@code AjaxResult} with the
 * fixed reason code as {@code msg} (the app maps only those codes and never shows server text). Request bodies are read as
 * raw JSON so decimal scale and unknown keys can be checked exactly. Both features default off.
 */
@RestController
@Tag(name = "原生私密住所与回家恢复（be-gap-misc）")
public class ApiNativeHomeController extends BaseController {
    private final NativeHomeService homes;
    private final NativeHomeReturnService returns;

    @Autowired
    public ApiNativeHomeController(NativeHomeService homes, NativeHomeReturnService returns) {
        this.homes = homes;
        this.returns = returns;
    }

    @Operation(summary = "我的私密住所")
    @GetMapping("/api/native/home")
    public AjaxResult home(HttpServletResponse response) {
        return call(response, () -> homes.snapshot(getAppUserId()));
    }

    @Operation(summary = "设置私密住所")
    @PutMapping("/api/native/home")
    public AjaxResult setHome(@RequestBody(required = false) String body, HttpServletResponse response) {
        return call(response, () -> homes.set(getAppUserId(), body));
    }

    @Operation(summary = "删除私密住所")
    @DeleteMapping("/api/native/home")
    public AjaxResult deleteHome(@RequestBody(required = false) String body, HttpServletResponse response) {
        return call(response, () -> homes.delete(getAppUserId(), body));
    }

    @Operation(summary = "回家恢复：策略")
    @GetMapping("/api/native/home/return/policy")
    public AjaxResult policy(HttpServletResponse response) {
        getAppUserId();
        return call(response, returns::policy);
    }

    @Operation(summary = "回家恢复：可恢复的进行中对局")
    @GetMapping("/api/native/home/return/runs")
    public AjaxResult runs(@RequestParam(value = "beforeRunId", required = false) String beforeRunId, HttpServletResponse response) {
        return call(response, () -> returns.runs(getAppUserId(), beforeRunId));
    }

    @Operation(summary = "回家恢复：对局体力账户")
    @GetMapping("/api/native/home/return/account")
    public AjaxResult account(@RequestParam(value = "runId", required = false) String runId, HttpServletResponse response) {
        return call(response, () -> returns.account(getAppUserId(), runId));
    }

    @Operation(summary = "回家恢复：到家核验")
    @PostMapping("/api/native/home/return/verify")
    public AjaxResult verify(@RequestBody(required = false) String body, HttpServletResponse response) {
        return call(response, () -> returns.verify(getAppUserId(), body));
    }

    @Operation(summary = "回家恢复：恢复体力")
    @PostMapping("/api/native/home/return/restore")
    public AjaxResult restore(@RequestBody(required = false) String body, HttpServletResponse response) {
        return call(response, () -> returns.restore(getAppUserId(), body));
    }

    @Operation(summary = "回家恢复：恢复回执")
    @GetMapping("/api/native/home/return/receipt")
    public AjaxResult receipt(@RequestParam(value = "requestId", required = false) String requestId, HttpServletResponse response) {
        return call(response, () -> returns.receipt(getAppUserId(), requestId));
    }

    private static AjaxResult call(HttpServletResponse response, Supplier<Object> action) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }
        try {
            Object data = action.get();
            return data == null ? AjaxResult.success() : AjaxResult.success(data);
        } catch (HomeRejection refused) {
            return new AjaxResult(refused.code, refused.getMessage());
        }
    }
}

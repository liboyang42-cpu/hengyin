package com.chengyinhub.framework.web.exception;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.alibaba.fastjson2.JSON;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.BusinessRefusal;
import com.chengyinhub.common.exception.ErrorCode;
import com.chengyinhub.common.exception.ErrorContract;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.framework.security.handle.AuthenticationEntryPointImpl;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * R5 error-contract:小程序(不带 {@code X-Error-Contract})看到的响应与迁移前逐字节一致;
 * 带 {@code X-Error-Contract: 2} 的 App 得到 4xx 拒绝 / 5xx 故障与 errorCode。
 */
class ErrorContractCompatibilityTest
{
    private static final AjaxResult SHARED = AjaxResult.error("共享实例");

    /** 迁移前后成对的端点:legacy* 是今天的写法,typed* 是迁移后的写法。 */
    @RestController
    static class Probe extends BaseController
    {
        @GetMapping("/legacy/signin") public AjaxResult legacySignIn() { return error("请先登录"); }
        @GetMapping("/typed/signin") public AjaxResult typedSignIn() { return refuse(ErrorCode.SIGN_IN_REQUIRED); }
        @GetMapping("/typed/signin-msg") public AjaxResult typedSignInMsg() { return refuse(ErrorCode.SIGN_IN_REQUIRED, "请先登录"); }
        @GetMapping("/legacy/forbidden") public AjaxResult legacyForbidden() { return AjaxResult.error(403, "无权操作"); }
        @GetMapping("/typed/forbidden") public AjaxResult typedForbidden() { return AjaxResult.refuse(403, ErrorCode.FORBIDDEN, "无权操作"); }
        @GetMapping("/legacy/data") public AjaxResult legacyData() { return AjaxResult.error("本次漫游已结算", Map.of("sessionId", 7)); }
        @GetMapping("/typed/data") public AjaxResult typedData() { return AjaxResult.refuse(500, ErrorCode.ROAM_ALREADY_SETTLED, "本次漫游已结算", Map.of("sessionId", 7)); }
        @GetMapping("/legacy/thrown") public AjaxResult legacyThrown() { throw new ServiceException("地点不存在或已下线"); }
        @GetMapping("/typed/thrown") public AjaxResult typedThrown() { throw new BusinessRefusal(ErrorCode.ROAM_PLACE_NOT_FOUND); }
        @GetMapping("/legacy/coded-thrown") public AjaxResult legacyCodedThrown() { throw new ServiceException("状态已变化", 409); }
        @GetMapping("/typed/coded-thrown") public AjaxResult typedCodedThrown() { throw new BusinessRefusal(ErrorCode.CONFLICT, "状态已变化", 409, 409, null); }
        @GetMapping("/untyped/refusal") public AjaxResult untypedRefusal() { return error("参数有误"); }
        @GetMapping("/untyped/failure") public AjaxResult untypedFailure() { return error("系统繁忙，请稍后重试"); }
        @GetMapping("/broken") public AjaxResult broken() { throw new IllegalStateException("boom"); }
        @GetMapping("/ok") public AjaxResult ok() { return success(Map.of("a", 1)); }
        @GetMapping("/domain-code") public AjaxResult domainCode() { return AjaxResult.error("请先完成本站 D20 检定").put("errorCode", "CHECK_REQUIRED"); }
        @GetMapping("/shared") public AjaxResult shared() { return SHARED; }
        @GetMapping("/list") public List<String> list() { return List.of("x"); }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new GlobalExceptionHandler(), new ErrorContractAdvice()).build();

    private MockHttpServletResponse call(String path, String header) throws Exception
    {
        MockHttpServletRequestBuilder request = get(path);
        if (header != null) request.header(ErrorContract.HEADER, header);
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        response.setCharacterEncoding("UTF-8");
        return response;
    }

    // ---------- 旧契约:小程序形状逐字节不变 ----------

    @ParameterizedTest
    @ValueSource(strings = {"signin", "signin-msg", "forbidden", "data", "thrown", "coded-thrown"})
    void withoutHeaderMigratedEndpointIsByteIdenticalToLegacy(String name) throws Exception
    {
        String legacyName = name.equals("signin-msg") ? "signin" : name;
        MockHttpServletResponse legacy = call("/legacy/" + legacyName, null);
        MockHttpServletResponse typed = call("/typed/" + name, null);
        assertEquals(200, legacy.getStatus());
        assertEquals(legacy.getStatus(), typed.getStatus(), "HTTP status must not change without the header");
        if (!name.equals("thrown"))
        {
            assertEquals(legacy.getContentAsString(), typed.getContentAsString(), "body must be byte-identical");
        }
        assertFalse(typed.getContentAsString().contains("errorCode"), "no errorCode without the header");
        assertEquals(legacy.getHeaderNames(), typed.getHeaderNames());
    }

    @Test
    void withoutHeaderThrownRefusalKeepsLegacyKeysAndCode() throws Exception
    {
        // ServiceException 与 BusinessRefusal 的文案不同于同名端点,这里只比形状。
        Map<?, ?> legacy = json.readValue(call("/legacy/thrown", null).getContentAsString(), Map.class);
        Map<?, ?> typed = json.readValue(call("/typed/thrown", null).getContentAsString(), Map.class);
        assertEquals(legacy, typed);
        assertEquals(Map.of("code", 500, "msg", "地点不存在或已下线", "errorKind", "BUSINESS"), typed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/untyped/refusal", "/untyped/failure", "/broken", "/ok", "/domain-code", "/typed/signin", "/list"})
    void withoutHeaderOrWithOtherVersionsNothingChanges(String path) throws Exception
    {
        MockHttpServletResponse none = call(path, null);
        assertEquals(200, none.getStatus());
        for (String other : new String[] {"", "1", "3", "v2", "2.0"})
        {
            MockHttpServletResponse response = call(path, other);
            assertEquals(none.getStatus(), response.getStatus(), "header value '" + other + "'");
            assertEquals(none.getContentAsString(), response.getContentAsString(), "header value '" + other + "'");
        }
        if (!path.equals("/domain-code"))
        {
            assertFalse(none.getContentAsString().contains("errorCode"));
        }
    }

    @Test
    void serializersNeverEmitTheTransientErrorCode() throws Exception
    {
        AjaxResult legacy = AjaxResult.error("请先登录");
        AjaxResult typed = AjaxResult.refuse(ErrorCode.SIGN_IN_REQUIRED).withErrorCode(ErrorCode.SIGN_IN_REQUIRED, 401);
        assertEquals(JSON.toJSONString(legacy), JSON.toJSONString(typed), "fastjson2 shape");
        assertEquals(json.writeValueAsString(legacy), json.writeValueAsString(typed), "jackson shape");
        assertEquals(legacy, typed, "map equality");
        assertTrue(typed.isError());
    }

    @Test
    void authenticationEntryPointUnchangedWithoutHeader() throws Exception
    {
        AuthenticationEntryPointImpl entry = new AuthenticationEntryPointImpl();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/userInfo");
        MockHttpServletResponse plain = new MockHttpServletResponse();
        entry.commence(request, plain, null);
        assertEquals(401, plain.getStatus());
        assertEquals(JSON.toJSONString(AjaxResult.error(401, "登录状态已失效，请重新登录")), plain.getContentAsString());

        MockHttpServletRequest v2 = new MockHttpServletRequest("GET", "/api/userInfo");
        v2.addHeader(ErrorContract.HEADER, "2");
        MockHttpServletResponse typed = new MockHttpServletResponse();
        typed.setCharacterEncoding("UTF-8");
        entry.commence(v2, typed, null);
        assertEquals(401, typed.getStatus());
        assertEquals("AUTH.SESSION_EXPIRED", json.readTree(typed.getContentAsString()).get("errorCode").asText());
    }

    @Test
    void miniProgramNeverOptsIntoTheNewContract() throws Exception
    {
        // 小程序源码(chengyinhub-xcx)不发送 X-Error-Contract,因此永远走旧形状。
        java.nio.file.Path xcx = java.nio.file.Path.of("..", "chengyinhub-xcx");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(xcx), "mini-program sources not checked out");
        try (var files = java.nio.file.Files.walk(xcx))
        {
            List<java.nio.file.Path> offenders = files
                    .filter(path -> path.toString().endsWith(".js") || path.toString().endsWith(".ts"))
                    .filter(path -> !path.toString().contains("node_modules"))
                    .filter(path -> {
                        try { return java.nio.file.Files.readString(path).contains(ErrorContract.HEADER); }
                        catch (java.io.IOException | java.io.UncheckedIOException e) { return false; }
                    })
                    .toList();
            assertEquals(List.of(), offenders);
        }
    }

    // ---------- 新契约 v2 ----------

    private void assertV2(String path, int status, String errorCode, String msg) throws Exception
    {
        MockHttpServletResponse response = call(path, "2");
        assertEquals(status, response.getStatus(), path);
        var body = json.readTree(response.getContentAsString());
        assertEquals(errorCode, body.get("errorCode").asText(), path);
        if (msg != null) assertEquals(msg, body.get("msg").asText(), path);
    }

    @Test
    void v2RefusalsAre4xxWithErrorCode() throws Exception
    {
        assertV2("/typed/signin", 401, "AUTH.SIGN_IN_REQUIRED", "请先登录");
        assertV2("/typed/forbidden", 403, "COMMON.FORBIDDEN", "无权操作");
        assertV2("/legacy/forbidden", 403, "COMMON.FORBIDDEN", "无权操作");
        assertV2("/typed/thrown", 404, "ROAM.PLACE_NOT_FOUND", "地点不存在或已下线");
        assertV2("/typed/coded-thrown", 409, "COMMON.CONFLICT", "状态已变化");
        assertV2("/legacy/coded-thrown", 409, "COMMON.CONFLICT", "状态已变化");
        assertV2("/legacy/thrown", 422, "COMMON.REFUSED", "地点不存在或已下线");
        assertV2("/untyped/refusal", 422, "COMMON.UNTYPED_REFUSAL", "参数有误");
        assertV2("/typed/data", 409, "ROAM.ALREADY_SETTLED", "本次漫游已结算");
        assertEquals(7, json.readTree(call("/typed/data", "2").getContentAsString()).get("data").get("sessionId").asInt());
    }

    @Test
    void v2GenuineFailuresAre5xx() throws Exception
    {
        assertV2("/broken", 500, "SYSTEM.FAILURE", null);
        assertV2("/untyped/failure", 500, "SYSTEM.UNTYPED_FAILURE", "系统繁忙，请稍后重试");
    }

    @Test
    void v2KeepsBodyCodeSuccessAndDomainCodes() throws Exception
    {
        MockHttpServletResponse ok = call("/ok", " 2 ");
        assertEquals(200, ok.getStatus());
        assertFalse(ok.getContentAsString().contains("errorCode"));
        assertEquals(500, json.readTree(call("/typed/signin", "2").getContentAsString()).get("code").asInt(),
                "body code stays the legacy value; HTTP status carries the new meaning");
        assertV2("/domain-code", 422, "CHECK_REQUIRED", null);
        assertEquals(200, call("/list", "2").getStatus());
    }

    @Test
    void v2DoesNotLeakIntoSharedInstances() throws Exception
    {
        assertV2("/shared", 422, "COMMON.UNTYPED_REFUSAL", "共享实例");
        assertFalse(SHARED.containsKey("errorCode"));
        assertFalse(call("/shared", null).getContentAsString().contains("errorCode"));
    }

    // ---------- 判据 ----------

    @Test
    void classifierTable()
    {
        assertNull(ErrorContract.decide(200, "ok", null, null));
        assertNull(ErrorContract.decide(601, "warn", null, null));
        assertEquals(401, ErrorContract.decide(401, "x", null, null).httpStatus());
        assertEquals(400, ErrorContract.decide(400, "x", null, null).httpStatus());
        assertEquals(503, ErrorContract.decide(503, "x", null, null).httpStatus());
        assertEquals(ErrorCode.UNTYPED_FAILURE, ErrorContract.decide(500, null, null, null).errorCode());
        assertEquals(ErrorCode.UNTYPED_FAILURE, ErrorContract.decide(500, "删除失败", null, null).errorCode());
        assertEquals(ErrorCode.UNTYPED_REFUSAL, ErrorContract.decide(500, "俱乐部不存在", null, null).errorCode());
        assertEquals(418, ErrorContract.decide(500, "x", ErrorCode.FORBIDDEN, 418).httpStatus());
        assertEquals(403, ErrorContract.decide(500, "x", ErrorCode.FORBIDDEN, null).httpStatus());
    }

    @Test
    void catalogueCodesAreStableUniqueAndDomainScoped()
    {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ErrorCode code : ErrorCode.values())
        {
            assertTrue(code.code().matches("(COMMON|AUTH|ROAM|PLAY|CLUB|TICKET|MERCHANT|SYSTEM)\\.[A-Z_]+"), code.code());
            assertTrue(seen.add(code.code()), "duplicate " + code.code());
            assertTrue(code.messageKey().startsWith("error."), code.name());
            assertTrue(code.httpStatus() >= 400 && code.httpStatus() < 600, code.name());
            assertEquals(code.code().startsWith("SYSTEM."), !code.isRefusal(), code.name());
            assertSame(code, ErrorCode.fromCode(code.code()));
        }
    }
}

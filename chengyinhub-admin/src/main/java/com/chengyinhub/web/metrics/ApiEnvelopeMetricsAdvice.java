package com.chengyinhub.web.metrics;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import com.chengyinhub.common.core.domain.AjaxResult;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * R9 BE-27: error-rate metric that sees through the envelope.
 *
 * <p>This API answers most failures as HTTP 200 with {@code code} in the body, so {@code http.server.requests}
 * alone calls them successes. Every {@link AjaxResult} body increments
 * {@value #METRIC}{@code {uri, code_class, kind}}: {@code uri} is the route template (low cardinality),
 * {@code code_class} is 2xx/4xx/5xx/other from the envelope code, {@code kind} is the {@code errorKind} marker
 * (BUSINESS, SYSTEM, NOT_BUILT, POLICY_CLOSED) or NONE. Error rate = 5xx with kind SYSTEM over all.
 */
@RestControllerAdvice
public class ApiEnvelopeMetricsAdvice implements ResponseBodyAdvice<Object>
{
    public static final String METRIC = "cy_api_envelope_total";

    private final MeterRegistry registry;

    public ApiEnvelopeMetricsAdvice(MeterRegistry registry)
    {
        this.registry = registry;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType)
    {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request, ServerHttpResponse response)
    {
        if (body instanceof AjaxResult)
        {
            try
            {
                record((AjaxResult) body, request);
            }
            catch (RuntimeException ignored)
            {
                // Metrics never change a response.
            }
        }
        return body;
    }

    void record(AjaxResult body, ServerHttpRequest request)
    {
        String uri = "UNKNOWN";
        if (request instanceof ServletServerHttpRequest)
        {
            HttpServletRequest servlet = ((ServletServerHttpRequest) request).getServletRequest();
            Object pattern = servlet.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern != null) uri = pattern.toString();
        }
        Object kind = body.get("errorKind");
        registry.counter(METRIC, "uri", uri, "code_class", codeClass(body.get(AjaxResult.CODE_TAG)),
                "kind", kind == null ? "NONE" : kind.toString()).increment();
    }

    static String codeClass(Object code)
    {
        int value;
        try
        {
            value = code instanceof Number ? ((Number) code).intValue() : Integer.parseInt(String.valueOf(code));
        }
        catch (NumberFormatException e)
        {
            return "other";
        }
        if (value >= 200 && value < 300) return "2xx";
        if (value >= 400 && value < 500) return "4xx";
        if (value >= 500 && value < 600) return "5xx";
        return "other";
    }
}

package com.chengyinhub.framework.web.exception;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ErrorContract;

/**
 * 错误契约 v2(R5 error-contract / BE-14、BE-05)。
 *
 * <p>只对带 {@code X-Error-Contract: 2} 的请求生效:非成功的 {@link AjaxResult} 得到
 * 真实的 HTTP 状态(拒绝 4xx,故障 5xx)和 {@code errorCode} 字段。其它请求(小程序、管理后台、
 * 旧版 App)原样放行:不改状态、不改响应体、不加字段。
 */
@RestControllerAdvice
public class ErrorContractAdvice implements ResponseBodyAdvice<Object>
{
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType)
    {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
            ServerHttpResponse response)
    {
        if (!(body instanceof AjaxResult result) || !ErrorContract.isV2(request.getHeaders().getFirst(ErrorContract.HEADER)))
        {
            return body;
        }
        return apply(result, response);
    }

    /** v2 下改写一个结果;成功/警告原样返回。 */
    static Object apply(AjaxResult result, ServerHttpResponse response)
    {
        Object rawCode = result.get(AjaxResult.CODE_TAG);
        if (!(rawCode instanceof Number number))
        {
            return result;
        }
        Object rawMsg = result.get(AjaxResult.MSG_TAG);
        ErrorContract.Decision decision = ErrorContract.decide(number.intValue(),
                rawMsg == null ? null : rawMsg.toString(), result.errorCode(), result.suggestedHttpStatus());
        if (decision == null)
        {
            return result;
        }
        // 复制一份再写:控制器可能返回共享实例,不能把 v2 字段漏进别的(旧契约)响应。
        AjaxResult out = new AjaxResult();
        out.putAll(result);
        // 已有领域错误码(如 PlayCommandErrorContract 的 CHECK_REQUIRED)更精确,保留;只补 HTTP 状态。
        if (out.get(AjaxResult.ERROR_CODE_TAG) == null)
        {
            out.put(AjaxResult.ERROR_CODE_TAG, decision.code());
        }
        response.setStatusCode(HttpStatusCode.valueOf(decision.httpStatus()));
        return out;
    }
}

package com.chengyinhub.web.controller.api;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * 频道合同的 HTTP 外壳：确定性拒绝 HTTP 状态 == body.code + errorCode + outcome_known=true；
 * 其余异常一律 500 + OUTCOME_UNKNOWN + outcome_known=false（客户端据此保留原请求待回执）。
 */
final class ChannelHttpResponse
{
    private static final Logger log = LoggerFactory.getLogger(ChannelHttpResponse.class);

    private ChannelHttpResponse() { }

    static ResponseEntity<AjaxResult> run(Supplier<Long> viewer, java.util.function.Function<Long, Object> body)
    {
        Long memberId;
        try
        {
            memberId = viewer.get();
        }
        catch (Exception notLoggedIn)
        {
            memberId = null;
        }
        if (memberId == null || memberId <= 0) return reject(401, "UNAUTHORIZED", "请先登录");
        try
        {
            return ResponseEntity.ok(AjaxResult.success(body.apply(memberId)));
        }
        catch (ChannelRejection e)
        {
            return reject(e.getStatus(), e.getErrorCode(), e.getMessage());
        }
        catch (Exception e)
        {
            log.error("频道接口结果待确认", e);
            AjaxResult result = new AjaxResult(500, "结果待确认，请稍后查询原请求");
            result.put("errorCode", "OUTCOME_UNKNOWN");
            result.put("outcome_known", false);
            return ResponseEntity.status(500).body(result);
        }
    }

    static ResponseEntity<AjaxResult> reject(int status, String errorCode, String message)
    {
        AjaxResult result = new AjaxResult(status, message);
        result.put("errorCode", errorCode);
        result.put("outcome_known", true);
        return ResponseEntity.status(status).body(result);
    }

    static long id(String raw)
    {
        if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) throw ChannelRejection.notFound();
        return Long.parseLong(raw);
    }

}

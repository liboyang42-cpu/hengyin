package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.impl.CastRunService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * 角色(Cast)v1 玩家侧接口。业务都在 {@link CastRunService};这里只取登录身份、拆参数、把业务拒绝翻成 AjaxResult。
 * 业务拒绝带客户端可识别的错误码时放在 data.errorCode(如 CAST_TTS_NOT_CONFIGURED)。
 */
@RestController
@Tag(name = "角色(Cast)")
public class ApiCastController extends BaseController
{
    @Autowired
    private CastRunService castRunService;

    Long memberId()
    {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    @Operation(summary = "设置本局玩家档案(名字 / 身份)")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/api/play/run/profile")
    public AjaxResult profile(@RequestBody Map<String, Object> body)
    {
        Long memberId = memberId();
        if (memberId == null || memberId <= 0) return error("请先登录");
        try
        {
            return success(castRunService.saveProfile(memberId, Convert.toLong(body.get("runId")),
                    body.get("name") == null ? null : String.valueOf(body.get("name")),
                    body.get("identityKey") == null ? null : String.valueOf(body.get("identityKey"))));
        }
        catch (ServiceException e)
        {
            return refusal(e);
        }
    }

    @Operation(summary = "记录剧情分支块的选择(服务端结算角色信任)")
    @RateLimiter(time = 10, count = 60, limitType = LimitType.MEMBER)
    @PostMapping("/api/play/run/branch")
    public AjaxResult branch(@RequestBody Map<String, Object> body)
    {
        Long memberId = memberId();
        if (memberId == null || memberId <= 0) return error("请先登录");
        try
        {
            return success(castRunService.recordBranch(memberId, Convert.toLong(body.get("runId")),
                    Convert.toLong(body.get("chapterId")),
                    body.get("blockKey") == null ? null : String.valueOf(body.get("blockKey")),
                    Convert.toInt(body.get("optionIndex"))));
        }
        catch (ServiceException e)
        {
            return refusal(e);
        }
    }

    @Operation(summary = "台词配音地址(块自带配音优先,否则按角色预设声音合成并缓存)")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @GetMapping("/api/topic/cast/voice")
    public AjaxResult voice(Long topicId, Long chapterId, String blockKey, Long runId)
    {
        Long memberId = memberId();
        if (memberId == null || memberId <= 0) return error("请先登录");
        try
        {
            return success(castRunService.lineVoice(memberId, topicId, chapterId, blockKey, runId));
        }
        catch (ServiceException e)
        {
            return refusal(e);
        }
    }

    static AjaxResult refusal(ServiceException e)
    {
        Integer code = e.getCode();
        AjaxResult result = AjaxResult.error(code != null && code >= 400 && code < 600 ? code : 400, e.getMessage());
        String errorCode = e.getDetailMessage();
        if (errorCode != null && errorCode.startsWith("CAST_"))
            result.put(AjaxResult.DATA_TAG, Collections.singletonMap("errorCode", errorCode));
        return result;
    }
}

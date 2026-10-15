package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.RoamShareSnapshotMapper;
import com.chengyinhub.business.service.support.RoamSharePrivacyService;
import com.chengyinhub.business.service.support.RoamShareSnapshotService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.UserSafeMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Collections;
import java.util.regex.Pattern;

/**
 * 第二轮拍板 17:足迹分享卡点开直接看那条足迹。
 *
 * <p>轨迹只在分享者本机,服务端 roam_session 没有轨迹。分享时客户端上传已裁剪的真实坐标,
 * 服务端在落库前统一量化到 3 位小数;接收方凭随机令牌匿名只读,读时再经同一隐私闸。
 * 服务端不保存原始精确轨迹,不下发分享者身份;时间只保留到小时。
 * 失效 = 删行:分享者清空本机足迹时调 revoke;单人超 50 条删最旧。
 */
@RestController
@Tag(name = "漫游足迹分享快照")
@RequestMapping("/api/roam/share")
public class ApiRoamShareController extends BaseController {

    static final int MAX_TRACK_POINTS = RoamSharePrivacyService.MAX_TRACK_POINTS;
    static final String GONE = "这条足迹已不再公开";
    private static final Pattern TOKEN = Pattern.compile("[a-f0-9]{32}");
    @Autowired
    private RoamShareSnapshotMapper snapshotMapper;
    @Autowired
    private RoamSharePrivacyService privacyService;
    @Autowired
    private RoamShareSnapshotService snapshotService;

    /** 包级可见 + 非 final:测试用子类覆写注入身份。 */
    Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    @Operation(summary = "发布足迹分享快照(同一条足迹复用令牌)")
    @PostMapping("/snapshot")
    public AjaxResult publish(Long clientTs, String snapshot) {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (clientTs == null || clientTs <= 0) return error("足迹记录无效");
        String normalized;
        try {
            normalized = privacyService.normalize(clientTs, snapshot).toJSONString();
        } catch (RuntimeException e) {
            return error("足迹数据无效");
        }
        String token = snapshotService.publish(memberId, clientTs, normalized);
        if (token == null) return error("足迹分享暂时不可用，请重试");
        return success(Collections.singletonMap("token", token));
    }

    @Operation(summary = "作废本人全部足迹分享快照")
    @PostMapping("/revoke")
    public AjaxResult revoke() {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        return success(Collections.singletonMap("revoked", snapshotMapper.deleteByMember(memberId)));
    }

    @Operation(summary = "读取足迹分享快照(未登录可读)")
    @GetMapping("/snapshot")
    public AjaxResult read(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) return error(GONE);
        String json;
        try {
            json = snapshotMapper.selectSnapshotJsonByToken(token);
        } catch (DataAccessException e) {
            // A failed lookup does not prove the share is gone. Preserve a
            // transport failure so existing readers offer retry instead.
            throw new ShareReadUnavailableException(e);
        }
        if (json == null) return error(GONE);
        try {
            return success(privacyService.normalizeStored(json));
        } catch (RuntimeException e) {
            return error(GONE);
        }
    }

    @ExceptionHandler(ShareReadUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public AjaxResult handleShareReadUnavailable() {
        return error(UserSafeMessage.GENERIC);
    }

    private static final class ShareReadUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ShareReadUnavailableException(DataAccessException cause) {
            super(UserSafeMessage.GENERIC, cause);
        }
    }
}

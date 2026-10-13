package com.chengyinhub.web.controller.api;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.mapper.RoamShareSnapshotMapper;
import com.chengyinhub.business.service.support.RoamSharePrivacyService;
import com.chengyinhub.business.service.support.RoamShareSnapshotService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 第二轮拍板 17:足迹分享卡点开直接看那条足迹。
 *
 * <p>轨迹只在分享者本机,服务端 roam_session 没有轨迹。分享时客户端上传已裁剪的真实坐标,
 * 服务端在落库前统一量化到 3 位小数;接收方凭随机令牌匿名只读,读时再经同一隐私闸。
 * 服务端不保存原始精确轨迹,不下发分享者身份;时间只保留到小时。
 * 失效 = 删行:分享者清空本机足迹时调 revoke(全撤);带 token 时只撤本人这一条(F0384);单人超 50 条删最旧。
 * 版本(M0117):同一足迹每次重新发布 version+1;带 expectedVersion 时按版本条件更新,冲突不覆盖。
 * 读侧标明「分享者自报的漫游回忆」,不是服务端履约凭证。
 */
@RestController
@Tag(name = "漫游足迹分享快照")
@RequestMapping("/api/roam/share")
public class ApiRoamShareController extends BaseController {

    static final int MAX_TRACK_POINTS = RoamSharePrivacyService.MAX_TRACK_POINTS;
    static final String GONE = "这条足迹已不再公开";
    static final String CONFLICT = "这条足迹分享已在别处更新，请刷新后重试";
    static final String NOT_OWNED = "这条分享不存在或不属于你";
    /** 读侧来源标识:分享内容是分享者本机自报的回忆,不能当官方完成证据。 */
    static final String MEMORY_KIND = "self_reported";
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

    /** 旧客户端/测试入口:不带版本条件。 */
    public AjaxResult publish(Long clientTs, String snapshot) {
        return publish(clientTs, snapshot, null);
    }

    @Operation(summary = "发布足迹分享快照(同一条足迹复用令牌;可带 expectedVersion 做版本条件更新)")
    @PostMapping("/snapshot")
    public AjaxResult publish(Long clientTs, String snapshot, Integer expectedVersion) {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (clientTs == null || clientTs <= 0) return error("足迹记录无效");
        String normalized;
        try {
            normalized = privacyService.normalize(clientTs, snapshot).toJSONString();
        } catch (RuntimeException e) {
            return error("足迹数据无效");
        }
        if (expectedVersion != null && expectedVersion <= 0) return error("足迹版本无效");
        RoamShareSnapshotService.Result result = snapshotService.publish(memberId, clientTs, normalized, expectedVersion);
        if (result != null && result.conflict) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("conflict", true);
            data.put("version", result.version);
            return AjaxResult.error(CONFLICT, data);
        }
        if (result == null || result.token == null) return error("足迹分享暂时不可用，请重试");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", result.token);
        data.put("version", result.version);
        return success(data);
    }

    /** 旧客户端/测试入口:全撤。 */
    public AjaxResult revoke() {
        return revoke(null);
    }

    @Operation(summary = "作废足迹分享快照:不带 token 撤本人全部;带 token 只撤本人这一条")
    @PostMapping("/revoke")
    public AjaxResult revoke(String token) {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (token == null || token.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("revoked", snapshotMapper.deleteByMember(memberId));
            data.put("scope", "all");
            return success(data);
        }
        if (!TOKEN.matcher(token).matches()) return error(NOT_OWNED);
        // 归属校验在 WHERE 上:member_id + share_token,他人令牌删 0 行。
        int revoked = snapshotMapper.deleteByMemberToken(memberId, token);
        if (revoked != 1) return error(NOT_OWNED);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("revoked", revoked);
        data.put("scope", "one");
        return success(data);
    }

    @Operation(summary = "读取足迹分享快照(未登录可读)")
    @GetMapping("/snapshot")
    public AjaxResult read(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) return error(GONE);
        Map<String, Object> row = snapshotMapper.selectSnapshotByToken(token);
        if (row == null || !(row.get("snapshotJson") instanceof String) || !(row.get("version") instanceof Number)) return error(GONE);
        try {
            JSONObject shared = privacyService.normalizeStored((String) row.get("snapshotJson"));
            shared.put("version", ((Number) row.get("version")).intValue());
            shared.put("memoryKind", MEMORY_KIND);
            return success(shared);
        } catch (RuntimeException e) {
            return error(GONE);
        }
    }
}

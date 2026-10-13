package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IRoamHangoutService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 漫游·附近的局(玩家自组局)。免费、无票、零资金流;加入 = 进群。
 * 七接口:GET /nearby(不再返回搭子局图层) · POST /create /join /report(2026-09-15 起下线,恒返回 410)
 * · GET /detail · POST /leave /close(存量局读取与退出保留,存量清零后另行拆除)。
 * 闸全在 {@link IRoamHangoutService},这里只做登录与参数形状;错误文案由 service 指明是哪道闸。
 */
@RestController
@Tag(name = "漫游·附近的局")
@RequestMapping("/api/roam/hangout")
public class ApiRoamHangoutController extends BaseController {

    @Autowired
    private IRoamHangoutService roamHangoutService;

    private Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    private static AjaxResult fail(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }

    /** 搭子局(A)下线的统一终态:create/join/report 同文案同 errorCode,先于登录与参数校验。 */
    private static AjaxResult hangoutOffline() {
        AjaxResult offline = new AjaxResult(410, "附近的局已下线，可在地图上查看附近的队伍");
        offline.put("errorCode", "HANGOUT_OFFLINE");
        return offline;
    }

    @Operation(summary = "附近的局 + 有坐标的活动(只读叠加)")
    @GetMapping("/nearby")
    public AjaxResult nearby(Double lat, Double lng, Integer radius) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (lat == null || lng == null) return error("缺少定位");
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return error("经纬度格式不正确");
        return success(roamHangoutService.nearby(memberId, lat, lng, radius == null ? 0 : radius));
    }

    @Operation(summary = "开一个局(已下线)")
    @PostMapping("/create")
    public AjaxResult create(String title, String description, String coverUrl,
                             BigDecimal latitude, BigDecimal longitude, String addressName, String startAt) {
        // 2026-09-15 裁决:搭子局(A)下架,地图只展示票务组队(/api/team/nearby)。老客户端先于登录拿到下线终态。
        // 存量局的读取/退出/关局仍保留,待存量清零后另行拆除(方案 §5 第 4 步)。
        return hangoutOffline();
    }

    @Operation(summary = "局详情(含头像堆与我的关系)")
    @GetMapping("/detail")
    public AjaxResult detail(Long id) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (id == null || id <= 0) return error("参数有误");
        try {
            return success(roamHangoutService.detail(memberId, id));
        } catch (ServiceException e) {
            return fail(e);
        }
    }

    @Operation(summary = "加入 = 进群(已下线,存量局不再收人)")
    @PostMapping("/join")
    public AjaxResult join(Long id) {
        return hangoutOffline();
    }

    @Operation(summary = "退出局(局主不能退,只能关)")
    @PostMapping("/leave")
    public AjaxResult leave(Long id) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (id == null || id <= 0) return error("参数有误");
        try {
            roamHangoutService.leave(memberId, id);
            return success();
        } catch (ServiceException e) {
            return fail(e);
        }
    }

    @Operation(summary = "关局(仅局主)")
    @PostMapping("/close")
    public AjaxResult close(Long id) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (id == null || id <= 0) return error("参数有误");
        try {
            roamHangoutService.close(memberId, id);
            return success();
        } catch (ServiceException e) {
            return fail(e);
        }
    }

    @Operation(summary = "举报局(已下线)")
    @PostMapping("/report")
    public AjaxResult report(Long id, String reason) {
        // 存量局举报已无「下架现场」可保,统一按下线终态返回,与 create/join 同口径。
        return hangoutOffline();
    }
}

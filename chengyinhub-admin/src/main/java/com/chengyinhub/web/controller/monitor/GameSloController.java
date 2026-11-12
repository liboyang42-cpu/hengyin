package com.chengyinhub.web.controller.monitor;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.metrics.GameSloProjection;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OPS-01 内部只读 SLO 读取口。
 *
 * <p>不新开 Actuator 暴露、不改 SecurityConfig、不新增权限字符串：复用现有
 * {@code monitor:server:list} 的服务监控权限。只读、无查询参数、无写操作；
 * 返回内容只有固定路由的计数/比率/时延与进程启动边界，不返回会员、订单、位置或密钥。
 *
 * <p>这是「缺展示口时最窄的读取」：既没有把 /actuator/** 放公网，也没有给任何角色
 * 新授权限。没有该既有权限的账号仍然读不到。
 */
@RestController
@RequestMapping("/monitor/game-slo")
public class GameSloController
{
    private final GameSloProjection projection;

    public GameSloController(GameSloProjection projection)
    {
        this.projection = projection;
    }

    @PreAuthorize("@ss.hasPermi('monitor:server:list')")
    @GetMapping
    public AjaxResult snapshot()
    {
        return AjaxResult.success(projection.snapshot());
    }
}

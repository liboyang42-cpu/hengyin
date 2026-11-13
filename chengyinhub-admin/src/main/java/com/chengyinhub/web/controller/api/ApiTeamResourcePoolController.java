package com.chengyinhub.web.controller.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.team.pool.TeamResourcePoolService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 队伍共享资源池（App docs/channels/contract.md §4；UI优化施工清单 §25.1）。 */
@RestController
@Tag(name = "队伍共享资源池")
public class ApiTeamResourcePoolController extends BaseController
{
    @Autowired
    private TeamResourcePoolService pool;

    @Operation(summary = "全队资源池(首次读取时按已发布配置冻结)")
    @GetMapping("/api/team/pool")
    public ResponseEntity<AjaxResult> get(@RequestParam("teamId") long teamId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> pool.pool(me, teamId));
    }

    @Operation(summary = "全队提示(同节点只扣一次)")
    @PostMapping("/api/team/pool/hint")
    public ResponseEntity<AjaxResult> hint(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> pool.consume(me, "HINT", body));
    }

    @Operation(summary = "全队跳过(同节点只扣一次)")
    @PostMapping("/api/team/pool/skip")
    public ResponseEntity<AjaxResult> skip(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> pool.consume(me, "SKIP", body));
    }

    @Operation(summary = "开始全队计时(幂等)")
    @PostMapping("/api/team/pool/timer/start")
    public ResponseEntity<AjaxResult> startTimer(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> pool.startTimer(me, body));
    }

    @Operation(summary = "全队资源原请求回执")
    @GetMapping("/api/team/pool/requests/{requestId}")
    public ResponseEntity<AjaxResult> receipt(@PathVariable("requestId") String requestId, @RequestParam("teamId") long teamId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> pool.receipt(me, teamId, requestId));
    }
}

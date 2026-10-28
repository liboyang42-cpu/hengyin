package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.dto.TeamEventCommand;
import com.chengyinhub.business.mapper.TeamEventCommandMapper;
import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.business.service.IOfficialEventService;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Domain-scoped receipts, not a replacement for session/financial command infrastructure. */
@Service
public class TeamEventCommandService {
    @org.springframework.beans.factory.annotation.Autowired
    private OfficialEventAttendanceService attendance;
    private final TeamEventCommandMapper mapper;
    private final IPlayTeamService teams;
    private final IOfficialEventV2Service events;
    private final IOfficialEventService signups;
    private final TransactionTemplate transaction;

    public TeamEventCommandService(TeamEventCommandMapper mapper, IPlayTeamService teams,
                                   IOfficialEventV2Service events, IOfficialEventService signups, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.teams = teams;
        this.events = events;
        this.signups = signups;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> query(Long actor, String domain, String requestId) {
        requireActorKey(actor, requestId);
        Map<String, Object> row = mapper.read(actor, requestId);
        // Absence is not proof that a delayed submission cannot arrive.
        if (row == null || !domain.equals(row.get("domain"))) return state(requestId, "UNKNOWN");
        return view(row);
    }

    public Map<String, Object> execute(Long actor, String domain, TeamEventCommand input) {
        String fingerprint = validate(actor, domain, input);
        reserve(actor, domain, input, fingerprint);
        try {
            return transaction.execute(status -> {
                Map<String, Object> row = locked(actor, domain, input, fingerprint);
                if (!"PENDING".equals(row.get("state"))) return view(row);
                Map<String,Object> attendanceResult = null;
                String rejection = null;
                if (OfficialEventAttendanceService.handles(input.getAction())) attendanceResult = attendance.execute(actor,input);
                else rejection = invoke(actor, domain, input);
                Map<String, Object> result = state(input.getRequestId(), rejection == null ? "APPLIED" : "REJECTED");
                // Service-returned rejection text is the existing public response contract; never persist raw infrastructure exceptions.
                result.put("message", rejection == null ? "原请求已执行" : rejection);
                if (attendanceResult != null) result.put("data", attendanceResult);
                finish(actor, input.getRequestId(), result);
                return result;
            });
        } catch (IllegalArgumentException | IllegalStateException | ServiceException rejected) {
            // The business transaction has rolled back before this new transaction starts.
            // A concurrent exact retry may have completed; never overwrite its terminal receipt.
            return transaction.execute(status -> {
                Map<String, Object> row = locked(actor, domain, input, fingerprint);
                if (!"PENDING".equals(row.get("state"))) return view(row);
                Map<String, Object> result = state(input.getRequestId(), "REJECTED");
                result.put("message", "原请求被拒绝，请重新读取业务状态");
                if (OfficialEventAttendanceService.handles(input.getAction()) && rejected instanceof ServiceException) {
                    result.put("message", rejected.getMessage());
                }
                if (rejected instanceof IPlayTeamService.TeamRejection teamRejection) {
                    result.put("errorCode", teamRejection.getErrorCode());
                    result.put("message", teamRejection.getMessage());
                }
                finish(actor, input.getRequestId(), result);
                return result;
            });
        }
        // Infrastructure/commit errors propagate. The client retains the original frozen request.
    }

    /** Stop an unexecuted request, not quit a team or cancel an event. A late writer locks this same row. */
    public Map<String, Object> cancel(Long actor, String domain, TeamEventCommand input) {
        String fingerprint = validate(actor, domain, input);
        reserve(actor, domain, input, fingerprint);
        return transaction.execute(status -> {
            Map<String, Object> row = locked(actor, domain, input, fingerprint);
            if (!"PENDING".equals(row.get("state"))) return view(row);
            Map<String, Object> result = state(input.getRequestId(), "CANCELLED");
            result.put("message", "原请求已终止，未执行业务命令");
            finish(actor, input.getRequestId(), result);
            return result;
        });
    }

    private void reserve(Long actor, String domain, TeamEventCommand input, String fingerprint) {
        transaction.execute(status -> {
            mapper.reserve(actor, input.getRequestId(), domain, input.getTargetId(), input.getAction(), fingerprint);
            locked(actor, domain, input, fingerprint);
            return null;
        });
    }

    private Map<String, Object> locked(Long actor, String domain, TeamEventCommand input, String fingerprint) {
        Map<String, Object> row = mapper.lock(actor, input.getRequestId());
        if (row == null) throw new IllegalStateException("原请求登记失败");
        if (!domain.equals(row.get("domain")) || !fingerprint.equals(row.get("fingerprint"))) {
            throw new ServiceException("原请求号与输入不一致");
        }
        return row;
    }

    private void finish(Long actor, String key, Map<String, Object> result) {
        if (mapper.finish(actor, key, String.valueOf(result.get("state")), JSON.toJSONString(result)) != 1) {
            throw new IllegalStateException("原请求回执写入失败");
        }
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Object json = row.get("result_json");
        if (json != null) return JSON.parseObject(String.valueOf(json));
        return state(String.valueOf(row.get("request_id")), "UNKNOWN");
    }

    private static Map<String, Object> state(String key, String state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", key);
        result.put("state", state);
        return result;
    }

    private static void requireActorKey(Long actor, String key) {
        if (actor == null || actor <= 0) throw new ServiceException("请先登录");
        if (key == null || !key.matches("[a-f0-9]{32}")) throw new ServiceException("原请求号无效");
    }

    private static String validate(Long actor, String domain, TeamEventCommand input) {
        if (input == null) throw new ServiceException("命令不能为空");
        requireActorKey(actor, input.getRequestId());
        Set<String> allowed = "TEAM".equals(domain) ? Set.of("quit", "kick", "disband", "join-mode", "withdraw", "handle", "join", "apply")
                : "EVENT".equals(domain) ? Set.of("schedule", "pause", "resume", "attendance-config", "attendance-verify") : "SIGNUP".equals(domain) ? Set.of("signup", "attendance-claim", "attendance-rotate") : Set.of();
        if (input.getAction() == null || !allowed.contains(input.getAction()) || input.getTargetId() == null || input.getTargetId() <= 0)
            throw new ServiceException("命令参数无效");
        for (String text : Arrays.asList(input.getReason(), input.getImpactScope(), input.getAlternativePlan(), input.getNotificationPlan())) {
            if (text != null && text.length() > 500) throw new ServiceException("命令说明过长");
        }
        try {
            List<Object> fields = new ArrayList<>(Arrays.asList(1, domain, input.getAction(), input.getTargetId(),
                    input.getMemberId(), input.getJoinMode(), input.getApproved(), input.getReason(),
                    input.getImpactScope(), input.getAlternativePlan(), input.getNotificationPlan()));
            // Preserve the original fingerprint shape for existing management commands.
            if ("join".equals(input.getAction()) || "apply".equals(input.getAction())) {
                fields.add(input.getInviteCode()); fields.add(input.getMessage());
            }
            if (OfficialEventAttendanceService.handles(input.getAction())) {
                fields.add(input.getAttendanceConfig()); fields.add(input.getConfigVersion()); fields.add(input.getCredentialCode());
            }
            String canonical = JSON.toJSONString(fields);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String invoke(Long actor, String domain, TeamEventCommand input) {
        Long id = input.getTargetId();
        if ("SIGNUP".equals(domain)) return signups.signup(id, actor);
        if ("TEAM".equals(domain)) {
            switch (input.getAction()) {
                case "join":
                    PlayTeam joined = teams.join(input.getInviteCode(), actor);
                    // Same transaction: a changed invitation must never join a different frozen target.
                    if (joined == null || !id.equals(joined.getId())) throw new IllegalStateException("邀请队伍已变化");
                    break;
                case "apply": teams.apply(id, actor, input.getMessage()); break;
                case "quit": teams.quit(id, actor); break;
                case "kick": teams.kick(id, actor, input.getMemberId()); break;
                case "disband": teams.disband(id, actor); break;
                case "join-mode": teams.updateJoinMode(id, actor, input.getJoinMode()); break;
                case "withdraw": teams.withdraw(id, actor); break;
                case "handle":
                    if (input.getApproved() == null) throw new IllegalArgumentException("审批结果不能为空");
                    teams.handle(id, actor, input.getMemberId(), input.getApproved()); break;
                default: throw new IllegalArgumentException("未知队伍命令");
            }
            return null;
        }
        switch (input.getAction()) {
            case "schedule": return events.schedule(id, actor);
            case "pause": return events.pause(id, actor, input.getReason(), input.getImpactScope(), input.getAlternativePlan(), input.getNotificationPlan());
            case "resume": return events.resume(id, actor, input.getReason());
            default: throw new IllegalArgumentException("未知赛事命令");
        }
    }
}

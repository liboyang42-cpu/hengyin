package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.PlayerActivityRole;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 团队对抗 / 白红 / 被动投影。不改 natAuto、不另造骰子。
 * <ul>
 *   <li>WHITE:沿用已有幸运重掷(luck.enabled);</li>
 *   <li>RED:投影 rerollAllowed=false,运行入口 hunk 据此拒绝第二次幸运重掷;</li>
 *   <li>PASSIVE:标记被动,仍走 {@code GameplayCheckEngine.roll};</li>
 *   <li>对抗:对方角色在场时挂上作者已写进 disadvantageIf 的状态,不改 DC。</li>
 * </ul>
 */
public class TeamOppositionAdapter {
    public static final String LANE_WHITE = "WHITE";
    public static final String LANE_RED = "RED";
    public static final String LANE_PASSIVE = "PASSIVE";

    public Map<String, Object> apply(RpgAdapterContext context) {
        Map<String, Object> fact = new LinkedHashMap<String, Object>();
        if (RpgAdapterSupport.inactive(context) || context.getSpec().getTeamOpposition() == null) {
            fact.put("applied", Boolean.FALSE);
            return fact;
        }
        RpgAdapterSpec.TeamOpposition team = context.getSpec().getTeamOpposition();
        String lane = team.getLane() == null ? "" : team.getLane().trim().toUpperCase();
        fact.put("lane", lane.isEmpty() ? null : lane);
        boolean luckOn = context.getRules() != null && context.getRules().getLuck() != null
                && context.getRules().getLuck().isEnabled();
        if (LANE_RED.equals(lane)) {
            fact.put("rerollAllowed", Boolean.FALSE);
        } else if (LANE_WHITE.equals(lane)) {
            fact.put("rerollAllowed", Boolean.valueOf(luckOn));
        }
        fact.put("passive", Boolean.valueOf(LANE_PASSIVE.equals(lane)));
        boolean opposed = false;
        String oppose = team.getOpposeRoleCode();
        if (oppose != null && !oppose.isEmpty()) {
            Long self = context.getMemberId();
            for (PlayerActivityRole row : context.getPartyRoles()) {
                if (row == null || oppose.equals(row.getRoleCode()) == false) continue;
                if (self != null && self.equals(row.getMemberId())) continue;
                opposed = true;
                break;
            }
        }
        fact.put("opposed", Boolean.valueOf(opposed));
        if (opposed && team.getOpposedCondition() != null && !team.getOpposedCondition().isEmpty()) {
            context.getState().getStatusConditions().add(team.getOpposedCondition());
            fact.put("opposedCondition", team.getOpposedCondition());
        }
        fact.put("natAutoUnchanged", Boolean.TRUE);
        fact.put("applied", Boolean.TRUE);
        context.getFacts().put("teamOpposition", fact);
        return fact;
    }

    public void contribute(RpgAdapterContext context, PlayCheckReceipt receipt) {
        if (RpgAdapterSupport.inactive(context)) return;
        Object fact = context.getFacts().get("teamOpposition");
        if (fact != null) RpgAdapterSupport.putExtra(receipt, "teamOpposition", fact);
    }
}

package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.PlayerActivityRole;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把已有活动角色源({@link PlayerActivityRole})投影成本局 {@code actorRoleCode},
 * 供现有 ROLE_IN 条件读取。角色源缺失 fail-closed:不发明成员、不写第二张 party 表。
 */
public class PartyFactAdapter {

    public Map<String, Object> apply(RpgAdapterContext context) {
        Map<String, Object> fact = new LinkedHashMap<String, Object>();
        if (RpgAdapterSupport.inactive(context) || context.getSpec().getParty() == null) {
            fact.put("applied", Boolean.FALSE);
            return fact;
        }
        PlayerActivityRole actor = context.getActorRole();
        String roleCode = actor == null ? null : actor.getRoleCode();
        if (roleCode != null && !roleCode.isEmpty()) {
            context.getState().setActorRoleCode(roleCode);
        } else if (context.getSpec().getParty().isRequireRole()) {
            context.getState().setActorRoleCode(null);
            fact.put("roleKnown", Boolean.FALSE);
        }
        List<Map<String, Object>> members = new ArrayList<Map<String, Object>>();
        List<PlayerActivityRole> roles = context.getPartyRoles();
        if (roles != null) {
            for (PlayerActivityRole row : roles) {
                if (row == null || row.getMemberId() == null) continue;
                Map<String, Object> member = new LinkedHashMap<String, Object>();
                member.put("memberId", row.getMemberId());
                member.put("roleCode", row.getRoleCode());
                members.add(member);
            }
        }
        fact.put("actorRoleCode", context.getState().getActorRoleCode());
        fact.put("members", members);
        fact.put("applied", Boolean.TRUE);
        context.getFacts().put("party", fact);
        return fact;
    }

    public void contribute(RpgAdapterContext context, PlayCheckReceipt receipt) {
        if (RpgAdapterSupport.inactive(context)) return;
        Object fact = context.getFacts().get("party");
        if (fact != null) RpgAdapterSupport.putExtra(receipt, "party", fact);
    }
}

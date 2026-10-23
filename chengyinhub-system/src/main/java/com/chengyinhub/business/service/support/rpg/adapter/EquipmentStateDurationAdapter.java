package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.service.support.TopicRouteEngine;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 装备槽与 statusConditions 的服务器期限。到期后卸下装备 / 摘掉状态,
 * 现有 ITEM_EQUIPPED / HAS_CONDITION 消费者自然不再成立。不另造背包账。
 */
public class EquipmentStateDurationAdapter {
    private final Clock clock;

    public EquipmentStateDurationAdapter(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Map<String, Object> apply(RpgAdapterContext context) {
        Map<String, Object> fact = new LinkedHashMap<String, Object>();
        fact.put("clockMillis", Long.valueOf(RpgAdapterSupport.serverNow(clock)));
        fact.put("ignoredClientNowMillis", context == null ? null : context.getClientNowMillis());
        if (RpgAdapterSupport.inactive(context)) {
            fact.put("applied", Boolean.FALSE);
            return fact;
        }
        TopicRouteEngine.State state = context.getState();
        long now = RpgAdapterSupport.serverNow(clock);
        List<String> unequipped = new ArrayList<String>();
        for (RpgAdapterSpec.EquipmentDuration row : context.getSpec().getEquipmentDurations()) {
            if (row == null || row.getExpiresAtEpochMillis() <= 0L || now < row.getExpiresAtEpochMillis()) continue;
            String itemId = row.getItemId();
            if (itemId == null || itemId.isEmpty()) continue;
            String slot = row.getSlot();
            Iterator<Map.Entry<String, String>> it = state.getEquipped().entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> entry = it.next();
                if (!itemId.equals(entry.getValue())) continue;
                if (slot != null && !slot.isEmpty() && !slot.equals(entry.getKey())) continue;
                it.remove();
                unequipped.add(itemId);
            }
        }
        List<String> cleared = new ArrayList<String>();
        for (RpgAdapterSpec.StatusDuration row : context.getSpec().getStatusDurations()) {
            if (row == null || row.getExpiresAtEpochMillis() <= 0L || now < row.getExpiresAtEpochMillis()) continue;
            String condition = row.getCondition();
            if (condition == null || condition.isEmpty()) continue;
            if (state.getStatusConditions().remove(condition)) cleared.add(condition);
        }
        fact.put("unequipped", unequipped);
        fact.put("clearedConditions", cleared);
        fact.put("applied", Boolean.TRUE);
        context.getFacts().put("equipmentDuration", fact);
        return fact;
    }

    public void contribute(RpgAdapterContext context, PlayCheckReceipt receipt) {
        if (RpgAdapterSupport.inactive(context)) return;
        Object fact = context.getFacts().get("equipmentDuration");
        if (fact != null) RpgAdapterSupport.putExtra(receipt, "equipmentDuration", fact);
    }
}

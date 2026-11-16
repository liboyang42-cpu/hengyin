package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.ThemeStateKey;

/**
 * TEST-only deterministic AI NPC dialogue stand-in when external LLM keys are absent.
 */
@Component
@Profile("e2e-test")
@ConditionalOnProperty(name = "chengyin.e2e.test-providers", havingValue = "true")
public class E2eTestNpcDialogueResponder implements com.chengyinhub.business.service.NpcDialogueTestResponder
{
    @Override
    public String generateJson(NpcProfile profile, String utterance, List<ThemeStateKey> allowedKeys)
    {
        String name = profile.getName() != null ? profile.getName() : "NPC";
        List<Map<String, Object>> effects = new ArrayList<Map<String, Object>>();
        String relationKey = profile.getRelationKey();
        if (relationKey == null || !relationKey.startsWith("relation."))
        {
            for (ThemeStateKey item : allowedKeys)
            {
                if (item.getStateKey() != null && item.getStateKey().startsWith("relation."))
                {
                    relationKey = item.getStateKey();
                    break;
                }
            }
        }
        if (relationKey != null && relationKey.startsWith("relation."))
        {
            Map<String, Object> inc = new LinkedHashMap<String, Object>();
            inc.put("type", "INC");
            inc.put("key", relationKey);
            inc.put("delta", 1);
            effects.add(inc);
        }
        String cleanUtterance = utterance == null ? "" : utterance.replace("\"", "'");
        String safeReply = name + "听到了你的话：「" + cleanUtterance + "」。我会记住这份线索，但关键谜底仍需你自己探明。";
        StringBuilder sb = new StringBuilder();
        sb.append("{\"reply\":\"").append(escape(safeReply)).append("\",\"effects\":[");
        for (int i = 0; i < effects.size(); i++)
        {
            if (i > 0) sb.append(',');
            Map<String, Object> ef = effects.get(i);
            sb.append("{\"type\":\"").append(escape(String.valueOf(ef.get("type"))))
              .append("\",\"key\":\"").append(escape(String.valueOf(ef.get("key"))))
              .append("\",\"delta\":").append(ef.get("delta")).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String text)
    {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}

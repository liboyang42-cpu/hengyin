package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyStory;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色 Cast v1 的纯规则(无 IO,便于单测):信任结算、按信任挑结局、台词变量替换、玩家档案视图。
 */
public final class CastRunRules
{
    public static final String VAR_PLAYER = "{玩家}";
    public static final String DEFAULT_PLAYER_NAME = "你";

    private CastRunRules()
    {
    }

    /** 把分支选项上的 trust:[{castId,delta}] 累加进本局信任。只认 castConfig 里存在的角色,delta 夹在 -2..2。 */
    public static void applyTrust(Map<String, Integer> trust, JsonNode optionTrust, java.util.Set<String> castIds)
    {
        if (trust == null || optionTrust == null || !optionTrust.isArray()) return;
        for (JsonNode one : optionTrust)
        {
            String castId = one.path("castId").asText("");
            if (castId.isEmpty() || castIds == null || !castIds.contains(castId)) continue;
            int delta = Math.max(-2, Math.min(2, one.path("delta").asInt(0)));
            if (delta == 0) continue;
            Integer before = trust.get(castId);
            trust.put(castId, (before == null ? 0 : before) + delta);
        }
    }

    /**
     * 按信任挑结局:只看配了 endingFor 的结局章,取信任最高的那位;最高值必须 &gt; 0 且唯一。
     * 平局 / 没有任何信任 / 没配 endingFor → 返回 null,由调用方走原有的 when / fallback 规则。
     */
    public static JourneyStory.EndingCase pickByTrust(List<JourneyStory.EndingCase> endings, Map<String, Integer> trust)
    {
        if (endings == null || trust == null || trust.isEmpty()) return null;
        JourneyStory.EndingCase best = null;
        int bestScore = 0;
        boolean tie = false;
        for (JourneyStory.EndingCase item : endings)
        {
            if (item == null || item.getEndingFor() == null || item.getEndingFor().isEmpty()) continue;
            Integer value = trust.get(item.getEndingFor());
            int score = value == null ? 0 : value;
            if (score <= 0) continue;
            if (best == null || score > bestScore)
            {
                best = item;
                bestScore = score;
                tie = false;
            }
            else if (score == bestScore && !item.getEndingFor().equals(best.getEndingFor()))
            {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    /** 台词里的故事变量:{玩家} → 本局玩家名,没填名字时 → 你。 */
    public static String resolveVars(String content, String playerName)
    {
        if (content == null) return "";
        String name = playerName == null || playerName.trim().isEmpty() ? DEFAULT_PLAYER_NAME : playerName.trim();
        return content.replace(VAR_PLAYER, name);
    }

    /**
     * 下发给客户端的 playerProfile:{name, identityKey, identityTitle};本局没设过档案 → null。
     * identityTitle 在设档案时按当时的 castConfig 快照进本局状态,作者后改身份名不影响进行中的局。
     */
    public static Map<String, Object> profileView(Map<String, String> profile)
    {
        if (profile == null || profile.isEmpty()) return null;
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("name", profile.get("name"));
        view.put("identityKey", profile.get("identityKey"));
        view.put("identityTitle", profile.get("identityTitle"));
        return view;
    }
}

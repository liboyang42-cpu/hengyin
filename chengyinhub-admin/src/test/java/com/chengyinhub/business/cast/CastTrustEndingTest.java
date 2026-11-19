package com.chengyinhub.business.cast;

import com.chengyinhub.business.domain.JourneyStory;
import com.chengyinhub.business.service.impl.PlayJourneyStoryServiceImpl;
import com.chengyinhub.business.service.support.CastRunRules;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 角色 Cast v1(D2):信任结算与按信任挑结局,含平局 / 没信任 / 没配 endingFor 走原规则。 */
class CastTrustEndingTest
{
    private static final ObjectMapper JSON = new ObjectMapper();

    static JourneyStory.EndingCase ending(String code, String endingFor, boolean fallback, String whenJson) throws Exception
    {
        JourneyStory.EndingCase e = new JourneyStory.EndingCase();
        e.setCode(code);
        e.setEndingFor(endingFor);
        e.setFallback(fallback ? Boolean.TRUE : null);
        e.setWhen(whenJson == null ? null : JSON.readTree(whenJson));
        return e;
    }

    @Test
    void 信任只累加已配置角色_delta夹在正负2() throws Exception
    {
        Map<String, Integer> trust = new LinkedHashMap<>();
        HashSet<String> cast = new HashSet<>(Arrays.asList("c_abc123", "c_xyz789"));
        CastRunRules.applyTrust(trust, JSON.readTree("[{\"castId\":\"c_abc123\",\"delta\":2},{\"castId\":\"c_ghost1\",\"delta\":2},{\"castId\":\"c_xyz789\",\"delta\":-9}]"), cast);
        CastRunRules.applyTrust(trust, JSON.readTree("[{\"castId\":\"c_abc123\",\"delta\":1}]"), cast);
        assertEquals(3, trust.get("c_abc123"));
        assertEquals(-2, trust.get("c_xyz789"));
        assertEquals(2, trust.size());
        CastRunRules.applyTrust(trust, null, cast);
        assertEquals(2, trust.size());
    }

    @Test
    void 按信任挑结局_最高者胜_平局与无信任与未配置返回null() throws Exception
    {
        JourneyStory.EndingCase lin = ending("ending1", "c_abc123", false, "[{\"op\":\"HAS_TAG\",\"value\":\"tag.never\"}]");
        JourneyStory.EndingCase zhou = ending("ending2", "c_xyz789", false, "[{\"op\":\"HAS_TAG\",\"value\":\"tag.never\"}]");
        JourneyStory.EndingCase fallback = ending("ending3", null, true, null);
        List<JourneyStory.EndingCase> endings = Arrays.asList(lin, zhou, fallback);

        Map<String, Integer> trust = new LinkedHashMap<>();
        trust.put("c_abc123", 1);
        trust.put("c_xyz789", 3);
        assertSame(zhou, CastRunRules.pickByTrust(endings, trust));

        trust.put("c_abc123", 3);
        assertNull(CastRunRules.pickByTrust(endings, trust), "平局走原规则");
        assertNull(CastRunRules.pickByTrust(endings, new LinkedHashMap<>()), "没信任走原规则");
        trust.clear();
        trust.put("c_abc123", -1);
        assertNull(CastRunRules.pickByTrust(endings, trust), "只有负信任不算");
        trust.put("c_abc123", 5);
        assertNull(CastRunRules.pickByTrust(Arrays.asList(ending("e", null, false, "[]"), fallback), trust), "没配 endingFor 走原规则");
    }

    @Test
    void 结局求值_信任优先_平局与未配置回到when与fallback() throws Exception
    {
        PlayJourneyStoryServiceImpl service = new PlayJourneyStoryServiceImpl(null, null, null, null, null, null);
        Method pick = PlayJourneyStoryServiceImpl.class.getDeclaredMethod("pickEnding", List.class, TopicRouteEngine.State.class);
        pick.setAccessible(true);

        JourneyStory.EndingCase tagged = ending("ending1", null, false, "[{\"op\":\"HAS_TAG\",\"value\":\"tag.brave\"}]");
        JourneyStory.EndingCase lin = ending("ending2", "c_abc123", false, "[{\"op\":\"HAS_TAG\",\"value\":\"tag.never\"}]");
        JourneyStory.EndingCase zhou = ending("ending3", "c_xyz789", false, "[{\"op\":\"HAS_TAG\",\"value\":\"tag.never\"}]");
        JourneyStory.EndingCase fallback = ending("ending4", null, true, null);
        List<JourneyStory.EndingCase> endings = Arrays.asList(tagged, lin, zhou, fallback);

        TopicRouteEngine.State state = new TopicRouteEngine.State();
        state.getTags().add("tag.brave");
        state.getTrust().put("c_xyz789", 2);
        assertSame(zhou, pick.invoke(service, endings, state), "信任唯一最高 → 该角色结局");

        state.getTrust().put("c_abc123", 2);
        assertSame(tagged, pick.invoke(service, endings, state), "平局 → 原 when 规则");

        TopicRouteEngine.State plain = new TopicRouteEngine.State();
        assertSame(fallback, pick.invoke(service, endings, plain), "没信任没标签 → 兜底");
    }

    @Test
    void 台词变量与档案视图()
    {
        assertEquals("小雨,你来了", CastRunRules.resolveVars("{玩家},你来了", " 小雨 "));
        assertEquals("你,你来了", CastRunRules.resolveVars("{玩家},你来了", null));
        assertNull(CastRunRules.profileView(new LinkedHashMap<>()));
        Map<String, String> p = new LinkedHashMap<>();
        p.put("name", "小雨");
        assertEquals("小雨", CastRunRules.profileView(p).get("name"));
        assertNull(CastRunRules.profileView(p).get("identityKey"));
    }

    @Test
    void 状态序列化_空字段不写出_有值能读回() throws Exception
    {
        TopicRouteEngine.State state = new TopicRouteEngine.State();
        String empty = JSON.writeValueAsString(state);
        assertEquals(false, empty.contains("trust") || empty.contains("playerProfile") || empty.contains("storyChoices"));
        state.getTrust().put("c_abc123", 2);
        state.getPlayerProfile().put("name", "小雨");
        state.getStoryChoices().put("5:b1", 0);
        TopicRouteEngine.State back = JSON.readValue(JSON.writeValueAsString(state), TopicRouteEngine.State.class);
        assertEquals(2, back.getTrust().get("c_abc123"));
        assertEquals("小雨", back.getPlayerProfile().get("name"));
        assertEquals(0, back.getStoryChoices().get("5:b1"));
        assertEquals(2, back.copy().getTrust().get("c_abc123"));
    }
}

package com.chengyinhub.business.service;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.NpcLineCache;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcLineCacheMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.impl.NpcEventService;
import com.chengyinhub.business.domain.vo.ai.NpcLineVO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * cacheOnly 语义:缓存未命中时不得回落 greeting(否则 = 开场白复读机)。
 *
 * <p>病理:漫游 Event 轨靠「line 为空」判「无预审话术 → 静默」。若未命中回落 greeting,
 * line 恒非空 → 静默分支永不成立:种子没落库 / eventType 差一字,用户被同一句复读五次,
 * 每次还各自扣配额、发 impression 埋点。而「话术全预审」验收抓不到 —— greeting 也是预审的。
 */
class NpcEventCacheOnlyTest {

    private NpcEventService svc;
    private NpcLineCacheMapper lineMapper;
    private NpcProfileMapper profileMapper;

    private static final String GREETING = "我是小瘾,你的城市探索向导。";

    @BeforeEach
    void setUp() {
        svc = new NpcEventService();
        lineMapper = mock(NpcLineCacheMapper.class);
        profileMapper = mock(NpcProfileMapper.class);
        ReflectionTestUtils.setField(svc, "npcLineCacheMapper", lineMapper);
        ReflectionTestUtils.setField(svc, "npcProfileMapper", profileMapper);

        NpcProfile p = new NpcProfile();
        p.setId(9L);
        p.setName("小瘾");
        p.setAvatar("");
        p.setGreeting(GREETING);
        when(profileMapper.selectGlobalNpc()).thenReturn(p);
    }

    /** 库里没有该 event_type 的话术(生产实况:npc_line_cache 全表 0 行)。 */
    private void cacheMiss() {
        when(lineMapper.selectByProfileAndEvent(anyLong(), anyString(), any()))
                .thenReturn(Collections.<NpcLineCache>emptyList());
    }

    @Test
    void cacheOnly_miss_returnsEmptyLine_notGreeting() {
        cacheMiss();
        NpcLineVO vo = svc.getEventLine(9L, "roam_start", null, true);
        assertEquals("", vo.getLine(), "cacheOnly 未命中必须返回空 line —— 非空即复读机");
        assertNull(vo.getName(), "cacheOnly 漫游提示不得携带角色名");
        assertNull(vo.getAvatar(), "cacheOnly 漫游提示不得携带头像");
    }

    /**
     * cacheOnly=false 仍回落 greeting —— /event 的 cacheOnly 默认 false,
     * 任何不显式传 true 的调用方(含旧客户端)都走这条路,语义不许变。
     */
    @Test
    void cacheOnlyFalse_miss_stillFallsBackToGreeting() {
        cacheMiss();
        NpcLineVO vo = svc.getEventLine(9L, "roam_start", null, false);
        assertEquals(GREETING, vo.getLine(), "cacheOnly=false 的兜底语义不许变");
        assertEquals("小瘾", vo.getName());
    }

    @Test
    void cacheOnly_hit_returnsTheLine() {
        NpcLineCache c = new NpcLineCache();
        c.setVariantsJson("[\"出发!走到哪,地图就亮到哪\"]");
        when(lineMapper.selectByProfileAndEvent(anyLong(), anyString(), any()))
                .thenReturn(Collections.singletonList(c));   // Java 8 API(仓库 target=1.8,禁 List.of)
        NpcLineVO vo = svc.getEventLine(9L, "roam_start", null, true);
        assertEquals("出发!走到哪,地图就亮到哪", vo.getLine(), "命中时 cacheOnly 不该影响正常返回");
        assertNull(vo.getName(), "cacheOnly 命中也不得回传角色名");
        assertNull(vo.getAvatar(), "cacheOnly 命中也不得回传头像");
    }

    /** profile 也为空(库全空):两条路径都返回空串,不 NPE。 */
    @Test
    void cacheOnly_noProfile_noNpe() {
        cacheMiss();
        when(profileMapper.selectGlobalNpc()).thenReturn(null);
        assertEquals("", svc.getEventLine(9L, "roam_start", null, true).getLine());
        assertEquals("", svc.getEventLine(9L, "roam_start", null, false).getLine());
    }
}

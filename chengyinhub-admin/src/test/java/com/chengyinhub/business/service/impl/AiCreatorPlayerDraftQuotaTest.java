package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftQuotaResp;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.ai.AiNotSentGatewayFixture;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.AiThemeDraftPlan;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Creator/Gateway orchestration; Redis/Lua, ledger, member/role and model are unit doubles. */
class AiCreatorPlayerDraftQuotaTest
{
    private static final Long MEMBER = 7L;
    private static final String VALID = "{\"plans\":[{\"title\":\"night\",\"storyline\":\"walk\",\"nodes\":[{\"merchantName\":\"place\"}]}]}";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void clubGenUsageSurvivesRoleChangeButPlayerDisplayAdmissionAndReplyUseOnlyDraftPool(boolean prepared)
    {
        AiNotSentGatewayFixture fixture = new AiNotSentGatewayFixture(MEMBER);
        AiCreatorServiceImpl creator = new AiCreatorServiceImpl();
        UmsMember member = new UmsMember();
        member.setId(MEMBER); member.setRole("club"); member.setStatus(1L); member.setDelFlag(0);
        IUmsMemberService members = mock(IUmsMemberService.class);
        IRoleService roles = mock(IRoleService.class);
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        when(roles.resolveRole(any(UmsMember.class))).thenAnswer(inv -> ((UmsMember) inv.getArgument(0)).getRole());
        ReflectionTestUtils.setField(creator, "umsMemberService", members);
        ReflectionTestUtils.setField(creator, "roleService", roles);
        ReflectionTestUtils.setField(creator, "gateway", fixture.gateway);
        ReflectionTestUtils.setField(creator, "redisTemplate", fixture.redis);
        ReflectionTestUtils.setField(creator, "quotaRecovery", fixture.recovery);
        ReflectionTestUtils.setField(fixture.gateway, "umsMemberService", members);
        Object config = ReflectionTestUtils.getField(fixture.gateway, "aiModelConfig");
        ReflectionTestUtils.setField(config, "enabled", true);
        ReflectionTestUtils.setField(config, "timeoutMs", 1000);
        Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));
        ReflectionTestUtils.setField(creator, "themeDraftClock", clock);
        ReflectionTestUtils.setField(fixture.gateway, "themeDraftClock", clock);
        when(fixture.model.chat(anyString(), anyString())).thenReturn(VALID);
        AiThemeDraftReq request = new AiThemeDraftReq(); request.setIdea("night walk");

        // Pool counts are unit doubles, not Redis execution or evidence of a real role-change transaction.
        AtomicInteger genUsed = new AtomicInteger(19), playerUsed = new AtomicInteger();
        AtomicInteger genReads = new AtomicInteger();
        List<List<Object>> reservations = new ArrayList<>();
        ValueOperations<Object, Object> values = mock(ValueOperations.class);
        when(fixture.redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.startsWith("ai:gen:limit:gen:" + MEMBER + ":")) {
                genReads.incrementAndGet(); return Long.valueOf(genUsed.get());
            }
            assertTrue(key.startsWith("ai:theme:draft:player:" + MEMBER + ":"));
            return Long.valueOf(playerUsed.get());
        });
        when(fixture.redis.execute(any(RedisScript.class), anyList(), anyInt(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    List<Object> keys = inv.getArgument(1);
                    reservations.add(new ArrayList<>(keys));
                    String counter = String.valueOf(keys.get(0));
                    assertEquals(counter + ":generation", keys.get(1));
                    if (counter.startsWith("ai:gen:limit:gen:" + MEMBER + ":")) {
                        assertEquals(20, (Integer) inv.getArgument(3));
                        assertEquals(0, (Integer) inv.getArgument(4));
                        return Long.valueOf(genUsed.incrementAndGet());
                    }
                    assertTrue(counter.startsWith("ai:theme:draft:player:" + MEMBER + ":"));
                    assertEquals(3, (Integer) inv.getArgument(3));
                    assertEquals(1, (Integer) inv.getArgument(4));
                    return playerUsed.get() >= 3 ? -2L : Long.valueOf(playerUsed.incrementAndGet());
                });

        // A club's last legal gen use is charged through the real Gateway before the role changes.
        AiThemeDraftResp club = creator.themeDraft(request, MEMBER);
        assertNotNull(club.getDraft()); assertNull(club.getRemainingQuota());
        assertEquals(20, genUsed.get()); assertEquals(0, playerUsed.get());
        AiThemeDraftQuotaResp clubQuota = creator.themeDraftQuota(MEMBER);
        assertTrue(clubQuota.isLimited()); assertEquals(20, clubQuota.getLimit());
        assertEquals(0, clubQuota.getRemaining()); assertEquals(1, genReads.get());

        // Model the committed result of an authorized club->player change, not a front-end role flag.
        member.setRole("player");
        for (int used = 0; used < 3; used++) {
            AiThemeDraftQuotaResp before = creator.themeDraftQuota(MEMBER);
            assertTrue(before.isLimited()); assertEquals(3, before.getLimit());
            assertEquals(3 - used, before.getRemaining());
            AiThemeDraftPlan plan = prepared ? creator.planThemeDraft(MEMBER, String.format("%032x", used + 1)) : null;
            AiThemeDraftResp reply = prepared
                    ? creator.themeDraftPrepared(request, MEMBER, plan)
                    : creator.themeDraft(request, MEMBER);
            assertNotNull(reply.getDraft()); assertEquals(2 - used, reply.getRemainingQuota());
            assertEquals(reply.getRemainingQuota(), creator.themeDraftQuota(MEMBER).getRemaining());
            assertEquals(20, genUsed.get()); assertEquals(used + 1, playerUsed.get());
            if (prepared) {
                assertEquals("player-draft", plan.pool());
                assertEquals(plan.nonce(), reply.getTraceId());
                assertEquals(List.of(plan.counterKey(), plan.counterKey() + ":generation", plan.receiptKey()),
                        reservations.get(used + 1));
            }
        }
        assertEquals(0, creator.themeDraftQuota(MEMBER).getRemaining());
        AiThemeDraftPlan fourth = prepared ? creator.planThemeDraft(MEMBER, "f".repeat(32)) : null;
        assertThrows(AiServiceException.class, () -> {
            if (prepared) creator.themeDraftPrepared(request, MEMBER, fourth);
            else creator.themeDraft(request, MEMBER);
        });
        assertEquals(20, genUsed.get()); assertEquals(3, playerUsed.get());
        assertEquals(1, genReads.get(), "player projection never reads the exhausted gen pool");
        verify(fixture.model, times(4)).chat(anyString(), anyString());
        verify(fixture.recovery, times(1)).prepare(eq(MEMBER), eq("theme_draft"), eq("gen"), anyString(), anyString());
        verify(fixture.recovery, times(4)).prepare(eq(MEMBER), eq("theme_draft"), eq("player-draft"), anyString(), anyString());
        verify(fixture.recovery, times(3)).retain(anyString(), eq("VALID_PLAYER_DRAFT"));
        verify(fixture.recovery, never()).requireRefund(anyString(), anyString());
    }
}

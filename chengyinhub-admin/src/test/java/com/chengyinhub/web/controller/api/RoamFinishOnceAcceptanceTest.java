package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SC-ROAM-END 验收(Mockito 层,本机可跑):一次结算发一次探索值、落一次结算快照;
 * 已结算的会话再调 finish 返回「本次漫游已结算」(不是回放),不会二次发放。
 * 真库全链路见 RoamSessionWriteMybatisIntegrationTest#finishSnapshotCanBeReadExactlyWithoutAwardingAgain
 * (CHENGYIN_FUNDS_MYSQL_IT=1 才跑)。
 */
class RoamFinishOnceAcceptanceTest {

    @Test
    void finishAwardsOnceAndRetryIsRejectedWithoutSecondAward() {
        ApiRoamController controller = spy(new ApiRoamController());
        RoamMapper roam = mock(RoamMapper.class);
        IGrowthService growth = mock(IGrowthService.class);
        ReflectionTestUtils.setField(controller, "roamMapper", roam);
        ReflectionTestUtils.setField(controller, "growthService", growth);
        ReflectionTestUtils.setField(controller, "playerBadgeMapper", mock(PlayerBadgeMapper.class));
        // 移植分支结算回包带原徽章投递状态(badgeDelivery)，需装配投递服务替身。
        ReflectionTestUtils.setField(controller, "roamBadgeDeliveryService",
                mock(com.chengyinhub.business.service.impl.RoamBadgeDeliveryService.class));
        ReflectionTestUtils.setField(controller, "growthBadgeMapper", mock(GrowthBadgeMapper.class));
        ReflectionTestUtils.setField(controller, "ruleEvaluator", new RuleEvaluator());
        ReflectionTestUtils.setField(controller, "npcFeatureFlags",
                mock(NpcFeatureFlags.class));
        doReturn(77L).when(controller).getAppUserId();

        RoamSession row = new RoamSession();
        row.setId(9L);
        row.setMemberId(77L);
        row.setStatus(0);
        // 锁读返回库里那一行;CAS 成功才把状态翻成 1(与 XML 的 WHERE status = 0 同义)
        when(roam.selectOwnedSessionForUpdate(9L, 77L)).thenAnswer(i -> {
            RoamSession c = new RoamSession();
            c.setId(row.getId()); c.setMemberId(row.getMemberId()); c.setStatus(row.getStatus());
            return c;
        });
        when(roam.updateSessionFinish(any(RoamSession.class))).thenReturn(1);
        when(roam.selectFoundPoisBySession(9L)).thenReturn(Collections.emptyList());
        when(roam.countTilesBySession(9L)).thenReturn(3);
        when(roam.countTilesTodayByMember(77L)).thenReturn(3);
        when(roam.countTilesEverByMember(77L)).thenReturn(3);
        when(roam.saveFinishResult(eq(9L), eq(77L), anyString())).thenAnswer(i -> { row.setStatus(1); return 1; });

        AjaxResult first = controller.finish("9", "", 120);
        assertEquals(200, first.get(AjaxResult.CODE_TAG), first.toString());
        verify(growth, times(1)).addExp(eq(77L), anyInt(), eq("城市漫游"));

        AjaxResult retry = controller.finish("9", "", 120);
        assertEquals("本次漫游已结算", retry.get(AjaxResult.MSG_TAG));
        AjaxResult again = controller.finish("9", "", 999);
        assertEquals("本次漫游已结算", again.get(AjaxResult.MSG_TAG));

        verify(growth, times(1)).addExp(anyLong(), anyInt(), anyString());
        verify(roam, times(1)).updateSessionFinish(any(RoamSession.class));
        verify(roam, times(1)).saveFinishResult(eq(9L), eq(77L), anyString());
    }

    @Test
    void anotherMembersSessionCannotBeFinished() {
        ApiRoamController controller = spy(new ApiRoamController());
        RoamMapper roam = mock(RoamMapper.class);
        IGrowthService growth = mock(IGrowthService.class);
        ReflectionTestUtils.setField(controller, "roamMapper", roam);
        ReflectionTestUtils.setField(controller, "growthService", growth);
        doReturn(78L).when(controller).getAppUserId();
        // 纵深:即便锁读意外返回了别人的会话(SQL 归属条件失效),控制器的 memberId 比对也要拒
        RoamSession foreign = new RoamSession();
        foreign.setId(9L); foreign.setMemberId(77L); foreign.setStatus(0);
        when(roam.selectOwnedSessionForUpdate(9L, 78L)).thenReturn(foreign);
        AjaxResult r = controller.finish("9", "", 120);
        assertEquals("会话不存在", r.get(AjaxResult.MSG_TAG));
        verify(roam, times(0)).updateSessionFinish(any());
        verify(growth, times(0)).addExp(anyLong(), anyInt(), anyString());
    }
}

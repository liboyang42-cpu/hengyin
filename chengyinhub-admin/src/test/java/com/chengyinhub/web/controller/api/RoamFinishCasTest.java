package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.common.core.domain.AjaxResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoamFinishCasTest
{
    @Test
    void losingConcurrentFinishDoesNotAwardXp()
    {
        ApiRoamController controller = spy(new ApiRoamController());
        RoamMapper roamMapper = mock(RoamMapper.class);
        IGrowthService growthService = mock(IGrowthService.class);
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "growthService", growthService);
        ReflectionTestUtils.setField(controller, "playerBadgeMapper", mock(PlayerBadgeMapper.class));
        ReflectionTestUtils.setField(controller, "growthBadgeMapper", mock(GrowthBadgeMapper.class));
        ReflectionTestUtils.setField(controller, "ruleEvaluator", new RuleEvaluator());
        doReturn(77L).when(controller).getAppUserId();

        RoamSession session = new RoamSession();
        session.setId(9L);
        session.setMemberId(77L);
        session.setStatus(0);
        when(roamMapper.selectOwnedSessionForUpdate(9L, 77L)).thenReturn(session);
        when(roamMapper.selectFoundPoisBySession(9L)).thenReturn(Collections.emptyList());
        when(roamMapper.countTilesBySession(9L)).thenReturn(10);
        when(roamMapper.countTilesTodayByMember(77L)).thenReturn(10);
        when(roamMapper.countTilesEverByMember(77L)).thenReturn(10);
        when(roamMapper.updateSessionFinish(any(RoamSession.class))).thenReturn(0);

        AjaxResult result = controller.finish("9", "", 100);

        assertEquals("本次漫游已结算", result.get(AjaxResult.MSG_TAG));
        verify(growthService, never()).addExp(anyLong(), anyInt(), anyString());
    }

    @Test
    void finishUpdateIsConditionalOnOpenStatus() throws Exception
    {
        String xml = new String(Files.readAllBytes(Paths.get(System.getProperty("user.dir"),
                "../chengyinhub-system/src/main/resources/mapper/business/RoamMapper.xml")), StandardCharsets.UTF_8);
        int start = xml.indexOf("<update id=\"updateSessionFinish\"");
        int end = xml.indexOf("</update>", start);
        String update = xml.substring(start, end).replaceAll("\\s+", " ");

        assertTrue(update.contains("where id = #{id} and status = 0"));
    }
}

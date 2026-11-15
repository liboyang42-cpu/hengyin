package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.NodeTaskPool;
import com.chengyinhub.business.domain.NodeTaskPoolItem;
import com.chengyinhub.business.domain.RewardDropRule;
import com.chengyinhub.business.service.IGameRandomService;
import com.chengyinhub.business.service.IRandomDispatchService;
import com.chengyinhub.business.service.IRewardDropRuleService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RandomSystemControllerTest {

    @Test
    void rewardDropAdminControllerHandlesBaseProbAndPreview() {
        RewardDropAdminController controller = new RewardDropAdminController();
        IRewardDropRuleService ruleService = mock(IRewardDropRuleService.class);
        IGameRandomService randomService = mock(IGameRandomService.class);
        ReflectionTestUtils.setField(controller, "ruleService", ruleService);
        ReflectionTestUtils.setField(controller, "gameRandomService", randomService);

        when(ruleService.getBaseProbBp()).thenReturn(1500);
        AjaxResult prob = controller.getBaseProb();
        assertTrue(prob.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) prob.get("data");
        assertEquals(1500, data.get("baseProbBp"));

        AjaxResult missing = controller.updateBaseProb(Map.of());
        assertFalse(missing.isSuccess());

        when(ruleService.updateBaseProbBp(2200, "admin")).thenReturn(2200);
        AjaxResult updated = controller.updateBaseProb(Map.of("baseProbBp", 2200));
        assertTrue(updated.isSuccess());

        when(ruleService.insertRule(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("weight 必须大于 0"));
        AjaxResult invalid = controller.addRule(new RewardDropRule());
        assertFalse(invalid.isSuccess());
    }

    @Test
    void nodeTaskPoolControllerReturnsPoolWithItems() {
        NodeTaskPoolController controller = new NodeTaskPoolController();
        IRandomDispatchService dispatchService = mock(IRandomDispatchService.class);
        ReflectionTestUtils.setField(controller, "dispatchService", dispatchService);

        NodeTaskPool pool = new NodeTaskPool();
        pool.setId(3L);
        pool.setPoolName("手冲工坊");
        NodeTaskPoolItem item = new NodeTaskPoolItem();
        item.setId(9L);
        item.setPoolId(3L);
        item.setNodeId(55L);
        when(dispatchService.selectPoolById(3L)).thenReturn(pool);
        when(dispatchService.selectItemsByPoolId(3L)).thenReturn(List.of(item));

        AjaxResult info = controller.getInfo(3L);
        assertTrue(info.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) info.get("data");
        assertSame(pool, data.get("pool"));
        assertEquals(1, ((List<?>) data.get("items")).size());

        AjaxResult notFound = controller.getInfo(999L);
        assertFalse(notFound.isSuccess());
    }
}

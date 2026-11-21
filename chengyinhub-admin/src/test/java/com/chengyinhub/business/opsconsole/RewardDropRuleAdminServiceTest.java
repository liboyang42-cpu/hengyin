package com.chengyinhub.business.opsconsole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.RewardDropRule;
import com.chengyinhub.business.mapper.RewardDropRuleMapper;
import com.chengyinhub.common.exception.ServiceException;

class RewardDropRuleAdminServiceTest
{
    private static RewardDropRule rule(Integer scope, Long topicId, String scene, Integer type, Integer weight, String param)
    {
        RewardDropRule r = new RewardDropRule();
        r.setScope(scope);
        r.setTopicId(topicId);
        r.setSceneKey(scene);
        r.setDropType(type);
        r.setWeight(weight);
        r.setParamJson(param);
        return r;
    }

    @Test
    void normalizesEachDropTypeToItsOwnFieldOnly()
    {
        assertEquals("{\"mult\":3}", RewardDropRuleAdminService.normalize(rule(0, null, null, 1, 10, " {\"mult\": 3} ")).getParamJson());
        assertEquals("{\"badgeCode\":\"FRAGMENT\"}", RewardDropRuleAdminService.normalize(rule(0, null, null, 2, 10, "{\"badgeCode\":\"FRAGMENT\"}")).getParamJson());
        assertEquals("{\"couponTemplateId\":12}", RewardDropRuleAdminService.normalize(rule(0, null, null, 3, 10, "{\"couponTemplateId\":12}")).getParamJson());
    }

    @Test
    void scopeDecidesWhichTargetIsKept()
    {
        RewardDropRule global = RewardDropRuleAdminService.normalize(rule(0, 9L, "roam_merchant_poi", 1, 5, "{\"mult\":2}"));
        assertNull(global.getTopicId());
        assertNull(global.getSceneKey());
        RewardDropRule topic = RewardDropRuleAdminService.normalize(rule(1, 9L, "roam_merchant_poi", 1, 5, "{\"mult\":2}"));
        assertEquals(9L, topic.getTopicId());
        assertNull(topic.getSceneKey());
        RewardDropRule scene = RewardDropRuleAdminService.normalize(rule(2, 9L, " roam_merchant_poi ", 1, 5, "{\"mult\":2}"));
        assertNull(scene.getTopicId());
        assertEquals("roam_merchant_poi", scene.getSceneKey());
    }

    @Test
    void rejectsBadRules()
    {
        assertThrows(ServiceException.class, () -> RewardDropRuleAdminService.normalize(rule(3, null, null, 1, 5, "{\"mult\":2}")));
        assertThrows(ServiceException.class, () -> RewardDropRuleAdminService.normalize(rule(1, null, null, 1, 5, "{\"mult\":2}")));
        assertThrows(ServiceException.class, () -> RewardDropRuleAdminService.normalize(rule(2, null, "unknown_scene", 1, 5, "{\"mult\":2}")));
        assertThrows(ServiceException.class, () -> RewardDropRuleAdminService.normalize(rule(0, null, null, 4, 5, "{}")));
        assertThrows(ServiceException.class, () -> RewardDropRuleAdminService.normalize(rule(0, null, null, 1, 0, "{\"mult\":2}")));
        assertThrows(ServiceException.class, () -> RewardDropRuleAdminService.normalize(rule(0, null, null, 1, 10001, "{\"mult\":2}")));
        assertThrows(ServiceException.class, () -> RewardDropRuleAdminService.normalize(rule(0, null, null, 1, 5, "{\"mult\":1}")));
        assertThrows(ServiceException.class, () -> RewardDropRuleAdminService.normalize(rule(0, null, null, 1, 5, "{\"mult\":2.5}")));
        assertThrows(ServiceException.class, () -> RewardDropRuleAdminService.normalize(rule(0, null, null, 1, 5, "{\"mult\":2,\"x\":1}")));
        assertThrows(ServiceException.class, () -> RewardDropRuleAdminService.normalize(rule(0, null, null, 2, 5, "{\"badgeCode\":\"has space\"}")));
        assertThrows(ServiceException.class, () -> RewardDropRuleAdminService.normalize(rule(0, null, null, 3, 5, "{\"couponTemplateId\":0}")));
        assertThrows(ServiceException.class, () -> RewardDropRuleAdminService.normalize(rule(0, null, null, 3, 5, "not json")));
    }

    @Test
    void createIsDisabledUntilEnabledAndAudited()
    {
        RewardDropRuleMapper mapper = mock(RewardDropRuleMapper.class);
        OpsConsoleAudit audit = mock(OpsConsoleAudit.class);
        when(mapper.insertRule(any())).thenReturn(1);
        RewardDropRule saved = new RewardDropRuleAdminService(mapper, audit).create(7L, rule(0, null, null, 1, 5, "{\"mult\":2}"));
        assertEquals(0, saved.getStatus());
        verify(audit).record(eq(7L), eq("REWARD_DROP_RULE_CREATE"), eq("REWARD_DROP_RULE"), any(), isNull(), any(), isNull());
    }

    @Test
    void brokenStoredRuleCannotBeEnabledDirectly()
    {
        RewardDropRuleMapper mapper = mock(RewardDropRuleMapper.class);
        OpsConsoleAudit audit = mock(OpsConsoleAudit.class);
        RewardDropRule stored = rule(0, null, null, 1, 5, "{\"mult\":0}");
        stored.setId(3L);
        stored.setStatus(0);
        when(mapper.selectById(3L)).thenReturn(stored);
        assertThrows(ServiceException.class, () -> new RewardDropRuleAdminService(mapper, audit).setStatus(7L, 3L, 1));
        verify(mapper, never()).updateStatus(any(), any());
        verify(audit, never()).record(any(), anyString(), anyString(), any(), any(), any(), any());
    }
}

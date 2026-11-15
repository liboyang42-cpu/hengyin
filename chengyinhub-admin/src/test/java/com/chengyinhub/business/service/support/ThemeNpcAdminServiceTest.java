package com.chengyinhub.business.service.support;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.common.config.AiModelConfig;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class ThemeNpcAdminServiceTest
{
    @Test
    void addThemeNpcValidatesRelationKeyAndUpsertsInitialRelation()
    {
        NpcProfileMapper npcMapper = mock(NpcProfileMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        ThemeStateKeyMapper keyMapper = mock(ThemeStateKeyMapper.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        CmsTopic topic = new CmsTopic();
        topic.setId(12L);
        topic.setDelFlag(0);
        when(topicMapper.selectCmsTopicById(12L)).thenReturn(topic);
        when(npcMapper.insertThemeNpc(any())).thenReturn(1);
        when(keyMapper.selectByTopicIdAndStateKey(12L, "relation.keeper")).thenReturn(null);

        ThemeNpcAdminService service = new ThemeNpcAdminService(npcMapper, topicMapper, keyMapper, jdbc);
        NpcProfile body = new NpcProfile();
        body.setScopeId(12L);
        body.setName("老陈");
        body.setRelationKey("relation.keeper");
        body.setInitialRelation(2);
        body.setPersona("古玩店掌柜");

        assertEquals(1, service.addThemeNpc(body));
        verify(keyMapper).upsertThemeStateKey(argThat(k ->
                "relation.keeper".equals(k.getStateKey()) && Integer.valueOf(2).equals(k.getInitialValue())));

        NpcProfile badKey = new NpcProfile();
        badKey.setScopeId(12L);
        badKey.setName("坏角色");
        badKey.setRelationKey("reward.gold");
        assertThrows(ServiceException.class, () -> service.addThemeNpc(badKey));
    }

    @Test
    void providerStatusNeverExposesRawSecretKeys()
    {
        ThemeNpcAdminService service = new ThemeNpcAdminService(
                mock(NpcProfileMapper.class), mock(CmsTopicMapper.class),
                mock(ThemeStateKeyMapper.class), mock(JdbcTemplate.class));
        AiModelConfig model = new AiModelConfig();
        ReflectionTestUtils.setField(model, "enabled", true);
        ReflectionTestUtils.setField(model, "apiKey", "sk-super-secret-value-123456");
        ReflectionTestUtils.setField(model, "baseUrl", "https://api.deepseek.com/v1");
        ReflectionTestUtils.setField(model, "model", "deepseek-chat");
        ReflectionTestUtils.setField(service, "aiModelConfig", model);
        ReflectionTestUtils.setField(service, "avatarProvider", "http");
        ReflectionTestUtils.setField(service, "avatarApiKey", "av-secret-xyz");

        List<Map<String, Object>> statuses = service.providerKeyStatuses();
        String serialized = statuses.toString();
        assertFalse(serialized.contains("sk-super-secret"));
        assertFalse(serialized.contains("av-secret-xyz"));
        assertEquals(true, statuses.get(0).get("keyConfigured"));
        assertEquals("api.deepseek.com", statuses.get(0).get("baseUrlHost"));
    }

    @Test
    void rejectAvatarRequiresReason()
    {
        ThemeNpcAdminService service = new ThemeNpcAdminService(
                mock(NpcProfileMapper.class), mock(CmsTopicMapper.class),
                mock(ThemeStateKeyMapper.class), mock(JdbcTemplate.class));
        assertThrows(ServiceException.class, () -> service.reviewAvatarJob(1L, "REJECTED", "  "));
    }
}

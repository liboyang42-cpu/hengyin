package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [R5] 主题模板复制必须带上 journeyRules(生命/幸运/难度/恢复)。
 *
 * <p>{@code copyTopicBasicInfo} 是逐字段手抄清单,B10 加列时没补这一行:
 * 复制出的副本静默丢掉数值规则,玩家侧只读下发变回「未配置」,零报错。
 */
class TopicCopyJourneyRulesTest {

    private static final Long SOURCE_TOPIC = 41L;
    private static final Long NEW_TOPIC = 42L;
    private static final Long MEMBER = 20088L;
    private static final Long PLATFORM_MEMBER = 100000L;
    private static final String RULES = "{\"schemaVersion\":1,\"checkEnabled\":true,"
            + "\"hp\":{\"max\":6},\"luck\":{\"max\":2},\"difficulty\":{\"modCap\":6},"
            + "\"recovery\":{\"exhaustTag\":\"EXHAUSTED\"}}";

    private CmsTopicMapper topicMapper;
    private IProductRoleService productRoleService;
    private CmsTopicServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        productRoleService = mock(IProductRoleService.class);

        service = new CmsTopicServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        ReflectionTestUtils.setField(service, "platformMemberId", PLATFORM_MEMBER);

        doAnswer(invocation -> {
            ((CmsTopic) invocation.getArgument(0)).setId(NEW_TOPIC);
            return 1;
        }).when(topicMapper).insertCmsTopic(any(CmsTopic.class));
    }

    private CmsTopic sourceTopic(String journeyRulesJson) {
        CmsTopic source = new CmsTopic();
        source.setId(SOURCE_TOPIC);
        source.setMemberId(PLATFORM_MEMBER);
        source.setName("源主题");
        source.setProductType(ProductType.FREE_EXPLORE);
        source.setGameModuleConfigJson("{\"modules\":[\"GAME\"]}");
        source.setJourneyRulesJson(journeyRulesJson);
        when(topicMapper.selectCmsTopicById(SOURCE_TOPIC)).thenReturn(source);
        return source;
    }

    private CmsTopic capturedCopy() {
        ArgumentCaptor<CmsTopic> captor = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).insertCmsTopic(captor.capture());
        return captor.getValue();
    }

    @Test
    void copyTemplate_carriesJourneyRulesJson_andKeepsOtherFieldCopyBehavior() {
        sourceTopic(RULES).setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":6,\"replayEnabled\":true}");
        Map<String, Object> params = new HashMap<>();
        params.put("copyType", 1);
        params.put("memberId", MEMBER);

        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, params);

        CmsTopic copy = capturedCopy();
        assertEquals("{\"schemaVersion\":1,\"capacity\":6,\"replayEnabled\":true}",copy.getThemeRuntimeConfigJson());
        assertEquals(RULES, copy.getJourneyRulesJson(), "数值规则必须随模板复制,否则副本静默丢规则");
        // 其余既有复制行为回归守卫
        assertEquals("源主题", copy.getName());
        assertEquals(ProductType.FREE_EXPLORE, copy.getProductType());
        assertEquals("{\"modules\":[\"GAME\"]}", copy.getGameModuleConfigJson());
        assertEquals(MEMBER, copy.getMemberId());
        assertEquals("LINEAR", copy.getRouteMode());
        assertEquals(Integer.valueOf(0), copy.getStatus(), "副本状态重置不受本次改动影响");
        assertEquals(Integer.valueOf(0), copy.getUserStatus());
    }

    @Test
    void copyTemplate_withoutJourneyRules_leavesTargetNull() {
        sourceTopic(null);
        Map<String, Object> params = new HashMap<>();
        params.put("copyType", 1);
        params.put("memberId", MEMBER);

        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, params);

        assertNull(capturedCopy().getJourneyRulesJson(), "源主题未配置时副本不得凭空生成规则");
    }

    @Test
    void copyOfficial_carriesJourneyRulesJson() {
        sourceTopic(RULES);
        when(productRoleService.isPlatformOwned(SOURCE_TOPIC)).thenReturn(true);
        ProductRole platformSlot = new ProductRole();
        platformSlot.setEntityType(RoleEntityType.PLATFORM);
        when(productRoleService.activeContentSlot(NEW_TOPIC)).thenReturn(platformSlot);

        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyOfficialCmsTopic(SOURCE_TOPIC, new HashMap<String, Object>());

        CmsTopic copy = capturedCopy();
        assertEquals(RULES, copy.getJourneyRulesJson(), "官方复制与模板 /use 共用 copyTopicBasicInfo,必须同样带上");
        assertEquals(PLATFORM_MEMBER, copy.getMemberId());
    }
}

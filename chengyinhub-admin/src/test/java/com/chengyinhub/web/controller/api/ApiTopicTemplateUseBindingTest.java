package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicLocationBindingCommand;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.support.TopicLocationBindingService;
import com.chengyinhub.business.service.support.TopicLocationContract;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模板采用入口的跨城合同：旧响应键保持不变，增量键可读；绑定写路由走既有 owner/scope。
 */
class ApiTopicTemplateUseBindingTest {

    private static final long PRINCIPAL = 901L;
    private static final long OWNER = 900L;
    private static final long TEMPLATE_ID = 42L;
    private static final long NEW_TOPIC = 7002L;

    private ApiTopicTemplateController controller;
    private ICmsTopicService topicService;
    private IMerchantAccessService merchantAccessService;
    private TopicLocationBindingService bindingService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicTemplateController());
        topicService = mock(ICmsTopicService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        bindingService = mock(TopicLocationBindingService.class);
        doReturn(PRINCIPAL).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        ReflectionTestUtils.setField(controller, "locationBindingService", bindingService);
        when(topicService.selectTopicIsTemplate(TEMPLATE_ID)).thenReturn(1);
        when(topicService.copyCmsTopic(eq(TEMPLATE_ID), any())).thenReturn(NEW_TOPIC);
    }

    private CmsTopic createdTopic(String cityCode, String contract) {
        CmsTopic topic = new CmsTopic();
        topic.setId(NEW_TOPIC);
        topic.setMemberId(PRINCIPAL);
        topic.setCityCode(cityCode);
        topic.setContentContractJson(contract);
        return topic;
    }

    private String newCityContract() {
        CmsTopic source = new CmsTopic();
        source.setId(100L);
        source.setCityCode("310100");
        source.setConfigVersion(7L);
        return TopicLocationContract.buildAdoptionContract(source,
                Collections.singletonList(node(11L)), Collections.singletonMap(11L, 111L),
                TopicLocationContract.INTENT_NEW_CITY, "320100", new Date());
    }

    private com.chengyinhub.business.domain.CmsTopicNode node(long id) {
        com.chengyinhub.business.domain.CmsTopicNode node = new com.chengyinhub.business.domain.CmsTopicNode();
        node.setId(id);
        node.setTopicId(100L);
        node.setAddress("上海市黄浦区");
        node.setLongitude("121.49");
        node.setLatitude("31.24");
        return node;
    }

    @SuppressWarnings("unchecked")
    @Test
    void usePassesAdoptionIntentAndKeepsTopicIdShape() {
        when(topicService.selectCmsTopicById(NEW_TOPIC))
                .thenReturn(createdTopic("320100", newCityContract()));

        AjaxResult result = controller.use(String.valueOf(TEMPLATE_ID), null, "NEW_CITY", "320100");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(NEW_TOPIC, data.get("topicId"), "旧客户端依赖 data.topicId 这个键");
        assertEquals("100", data.get("sourceTemplateTopicId"));
        assertEquals(7L, data.get("sourceConfigVersion"));
        assertEquals("NEW_CITY", data.get("bindingIntent"));
        assertEquals("320100", data.get("targetCityCode"));
        assertEquals("320100", data.get("cityCode"));
        assertEquals(Collections.singletonList("loc-11"), data.get("pendingRebindSlotKeys"));
        List<Map<String, Object>> issues = (List<Map<String, Object>>) data.get("bindingIssues");
        assertEquals(1, issues.size());
        assertEquals("error", issues.get(0).get("level"));
        assertEquals("LOCATION_REBIND_REQUIRED", issues.get(0).get("code"));

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(topicService).copyCmsTopic(eq(TEMPLATE_ID), params.capture());
        assertEquals("NEW_CITY", params.getValue().get("bindingIntent"));
        assertEquals("320100", params.getValue().get("targetCityCode"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void legacyUseSendsNoIntentAndReportsWarningsOnly() {
        when(topicService.selectCmsTopicById(NEW_TOPIC)).thenReturn(createdTopic("310100",
                TopicLocationContract.buildAdoptionContract(legacySource(),
                        Collections.singletonList(node(11L)), Collections.singletonMap(11L, 111L),
                        TopicLocationContract.INTENT_LEGACY, null, new Date())));

        AjaxResult result = controller.use(String.valueOf(TEMPLATE_ID), null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals("LEGACY", data.get("bindingIntent"));
        for (Map<String, Object> issue : (List<Map<String, Object>>) data.get("bindingIssues")) {
            assertEquals("warn", issue.get("level"), "旧版采用不能让既有副本被新规则拦下");
        }
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(topicService).copyCmsTopic(eq(TEMPLATE_ID), params.capture());
        assertTrue(!params.getValue().containsKey("bindingIntent"), "旧调用不得凭空注入采用方式");
    }

    private CmsTopic legacySource() {
        CmsTopic source = new CmsTopic();
        source.setId(100L);
        source.setCityCode("310100");
        return source;
    }

    @Test
    void invalidAdoptionParametersReturnBusinessErrorNot500() {
        when(topicService.copyCmsTopic(eq(TEMPLATE_ID), any()))
                .thenThrow(new IllegalArgumentException("跨城采用必须指定 targetCityCode"));

        AjaxResult result = controller.use(String.valueOf(TEMPLATE_ID), null, "NEW_CITY", null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("targetCityCode"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void bindingRouteLooksUpOwnerForMerchantScope() {
        MerchantAccessContext context = new MerchantAccessContext(PRINCIPAL, 77L, OWNER,
                MerchantRoleCode.MERCHANT_MANAGER);
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE)).thenReturn(context);
        Map<String, Object> serviceResult = new LinkedHashMap<String, Object>();
        serviceResult.put("topicId", String.valueOf(NEW_TOPIC));
        when(bindingService.bind(any(), eq(OWNER), eq(PRINCIPAL))).thenReturn(serviceResult);

        AjaxResult result = controller.bindLocation(String.valueOf(NEW_TOPIC), "MERCHANT", "loc-11",
                "501", "502,503", "09:00-18:00", "站在东侧", "m-1,m-2", "1");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        ArgumentCaptor<TopicLocationBindingCommand> command =
                ArgumentCaptor.forClass(TopicLocationBindingCommand.class);
        verify(bindingService).bind(command.capture(), eq(OWNER), eq(PRINCIPAL));
        assertEquals(Long.valueOf(NEW_TOPIC), command.getValue().getTopicId());
        assertEquals("loc-11", command.getValue().getSlotKey());
        assertEquals(Long.valueOf(501L), command.getValue().getNodeId());
        assertEquals(2, command.getValue().getApprovedFallbackNodeIds().size());
        assertEquals(2, command.getValue().getReferenceMediaIds().size());
        assertEquals(Integer.valueOf(1), command.getValue().getExpectedRevision());
    }

    @Test
    void bindingConflictKeepsItsCode() {
        when(bindingService.bind(any(), anyLong(), anyLong()))
                .thenThrow(new ServiceException("地点绑定已被其他操作更新，请刷新后重试", 409));
        AjaxResult result = controller.bindLocation(String.valueOf(NEW_TOPIC), null, "loc-11",
                "501", null, null, null, null, "0");
        assertEquals(409, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void bindingStatusIsOwnerOnly() {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("cityCode", "320100");
        when(bindingService.status(NEW_TOPIC, PRINCIPAL)).thenReturn(status);
        AjaxResult result = controller.bindingStatus(String.valueOf(NEW_TOPIC), null);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(bindingService).status(NEW_TOPIC, PRINCIPAL);
    }

    @Test
    void anonymousCallerCannotBind() {
        doReturn(null).when(controller).getAppUserId();
        AjaxResult result = controller.bindLocation(String.valueOf(NEW_TOPIC), null, "loc-11",
                "501", null, null, null, null, null);
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(bindingService, org.mockito.Mockito.never()).bind(any(), any(), any());
    }

    @Test
    void notATemplateIsRejectedBeforeCopy() {
        when(topicService.selectTopicIsTemplate(TEMPLATE_ID)).thenReturn(0);
        AjaxResult result = controller.use(String.valueOf(TEMPLATE_ID), null, "NEW_CITY", "320100");
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(topicService, org.mockito.Mockito.never()).copyCmsTopic(anyLong(), any());
    }

    @Test
    void nonMerchantScopeUsesPrincipalAsOwner() {
        Map<String, Object> serviceResult = new LinkedHashMap<String, Object>();
        when(bindingService.bind(any(), eq(PRINCIPAL), eq(PRINCIPAL))).thenReturn(serviceResult);
        controller.bindLocation(String.valueOf(NEW_TOPIC), null, "loc-11", "501",
                null, null, null, null, "0");
        verify(bindingService).bind(any(), eq(PRINCIPAL), eq(PRINCIPAL));
        assertNotNull(serviceResult);
    }
}

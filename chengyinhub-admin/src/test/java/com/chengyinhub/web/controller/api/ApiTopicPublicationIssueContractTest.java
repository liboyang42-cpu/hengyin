package com.chengyinhub.web.controller.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.exception.TopicPublicationContentException;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.MemberIdentityService;
import com.chengyinhub.business.service.support.TopicPublicationContentGuard;
import com.chengyinhub.business.service.support.TopicPublicationContentGuard.PublicationProblem;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiTopicPublicationIssueContractTest {
    private static final long TOPIC = 7001L;
    private static final long OWNER = 901L;
    private ApiTopicController controller;
    private ICmsTopicService topics;
    private IQuotaService quota;
    private CmsTopic topic;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiTopicController());
        doReturn(OWNER).when(controller).getAppUserId();
        doReturn("member901").when(controller).getAppUserName();
        topics = mock(ICmsTopicService.class);
        quota = mock(IQuotaService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        ReflectionTestUtils.setField(controller, "quotaService", quota);
        ReflectionTestUtils.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        ReflectionTestUtils.setField(controller, "memberIdentityService", mock(MemberIdentityService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        TopicContentSafetyTestFixtures.wire(controller, security);
        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(OWNER);
        topic.setPublishMode("pro");
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topics.selectTopicIsDraft(TOPIC)).thenReturn(1);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();
    }

    private TopicPublicationContentException readinessFailure() {
        CmsTopic invalid = new CmsTopic();
        TopicPublicationContentGuard guard = new TopicPublicationContentGuard();
        return assertThrows(TopicPublicationContentException.class, () -> guard.assertPublishReady(invalid));
    }

    private TopicCreateDTO createDto() {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setName("待发布主题");
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setPublishMode("pro");
        return dto;
    }

    @Test
    void actualPublishMvcRetainsLegacyCodeMessageAndHttpStatusWithStableIssues() throws Exception {
        TopicPublicationContentException failure = readinessFailure();
        when(topics.publishDraftWithReview(TOPIC, OWNER, null)).thenThrow(failure);
        mvc.perform(post("/api/topic/publish-draft").param("id", Long.toString(TOPIC)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value(failure.getMessage()))
                .andExpect(jsonPath("$.data.issues[0].code").value("TOPIC_NAME_REQUIRED"))
                .andExpect(jsonPath("$.data.issues[0].field").value("name"));
        verifyNoInteractions(quota);
    }

    @Test
    void existingServiceExceptionDataSurvivesTheActualHandler() throws Exception {
        ServiceException failure = TopicPublicationContentException.withIssues(
                new ServiceException("故事念头未定义"), List.of(PublicationProblem.at(
                        PublicationProblem.CODE_THOUGHT_REF_UNRESOLVED, null, "journeyRulesJson.thoughts", "故事念头未定义")));
        when(topics.publishDraftWithReview(TOPIC, OWNER, null)).thenThrow(failure);
        mvc.perform(post("/api/topic/publish-draft").param("id", Long.toString(TOPIC)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("故事念头未定义"))
                .andExpect(jsonPath("$.data.issues[0].code").value("THOUGHT_REF_UNRESOLVED"));
    }

    @Test
    void directCreatePreservesTheDifferentLegacyServiceAndStateErrorCodes() {
        when(topics.createTopic(any(), eq(OWNER), anyString())).thenThrow(readinessFailure());
        AjaxResult state = controller.createTopic(createDto());
        assertEquals(500, state.get("code"));
        assertEquals(readinessFailure().getMessage(), state.get("msg"));
        assertInstanceOf(TopicPublicationContentException.Issues.class, state.get("data"));

        ServiceException service = TopicPublicationContentException.withIssues(new ServiceException("票种未填写完整"),
                List.of(PublicationProblem.at(PublicationProblem.CODE_TICKET_INVALID, null, "tickets[id=7]", "票种未填写完整")));
        when(topics.createTopic(any(), eq(OWNER), anyString())).thenThrow(service);
        AjaxResult business = controller.createTopic(createDto());
        assertEquals(400, business.get("code"));
        assertEquals("票种未填写完整", business.get("msg"));
        assertSame(service.getData(), business.get("data"));
    }

    @Test
    void genericRuntimeCreateFailureKeepsItsOriginalGenericMessage() {
        when(topics.createTopic(any(), eq(OWNER), anyString())).thenThrow(
                new TopicPublicationContentException.InvalidContent("原业务拒绝", List.of(PublicationProblem.at(
                        PublicationProblem.CODE_RECRUIT_DEADLINE_REQUIRED, null, "recruitDeadline", "缺少招募截止时间"))));
        AjaxResult result = controller.createTopic(createDto());
        assertEquals(500, result.get("code"));
        assertEquals("主题发布失败，请稍后重试", result.get("msg"));
        assertInstanceOf(TopicPublicationContentException.Issues.class, result.get("data"));
    }

    @Test
    void directUpdatePreservesTheLegacyServiceAndRuntimeRefusals() {
        var dto = new com.chengyinhub.business.domain.dto.TopicUpdateDTO();
        dto.setId(TOPIC); dto.setName("待发布主题");
        when(topics.updateTopicByOwner(any(), eq(OWNER), anyString())).thenThrow(readinessFailure());
        AjaxResult state = controller.updateTopic(dto);
        assertEquals(500, state.get("code"));
        assertInstanceOf(TopicPublicationContentException.Issues.class, state.get("data"));
        ServiceException failure = TopicPublicationContentException.withIssues(new ServiceException("票种未填写完整"),
                List.of(PublicationProblem.at(PublicationProblem.CODE_TICKET_INVALID, null, "tickets[id=7]", "票种未填写完整")));
        when(topics.updateTopicByOwner(any(), eq(OWNER), anyString())).thenThrow(failure);
        AjaxResult business = controller.updateTopic(dto);
        assertEquals(400, business.get("code"));
        assertEquals("票种未填写完整", business.get("msg"));
        assertSame(failure.getData(), business.get("data"));
    }

    @Test
    void foreignOwnerAndAnonymousAreDeniedBeforeReadingPublicationIssues() throws Exception {
        topic.setMemberId(999L);
        mvc.perform(post("/api/topic/publish-draft").param("id", Long.toString(TOPIC)))
                .andExpect(jsonPath("$.msg").value("无权操作该主题"))
                .andExpect(jsonPath("$.data").doesNotExist());
        doReturn(0L).when(controller).getAppUserId();
        mvc.perform(post("/api/topic/publish-draft").param("id", Long.toString(TOPIC)))
                .andExpect(jsonPath("$.msg").value("请先登录"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(topics, never()).publishDraftWithReview(anyLong(), anyLong(), any());
        verify(topics, never()).selectTopicIsDraft(anyLong());
    }

    @Test
    void staleAttributeBindingKeepsTheOriginal409ContractWithoutPublicationIssues() throws Exception {
        when(topics.publishDraftWithBinding(TOPIC, OWNER, 1L, "old-registry", null)).thenThrow(
                com.chengyinhub.business.service.support.AuthorAttributeBinding.conflict(
                        "ATTRIBUTE_REGISTRY_VERSION_CONFLICT", "发布版本与已确认草稿不一致，请重新读取后确认", 409));
        mvc.perform(post("/api/topic/publish-draft").param("id", Long.toString(TOPIC))
                        .param("configVersion", "1").param("registryVersion", "old-registry"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data.errorCode").value("ATTRIBUTE_REGISTRY_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.data.issues").doesNotExist());
        verify(topics, never()).publishDraftWithReview(anyLong(), anyLong(), any());
    }

    @Test
    void ordinaryRuntimeFailuresDoNotAcquireContentIssues() throws Exception {
        when(topics.publishDraftWithReview(TOPIC, OWNER, null))
                .thenThrow(new IllegalStateException("数据库连接不可用"));
        mvc.perform(post("/api/topic/publish-draft").param("id", Long.toString(TOPIC)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("数据库连接不可用"))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertNull(TopicPublicationContentException.dataOf(new ServiceException("普通业务拒绝")
                .setData(Map.of("issues", Collections.emptyList()))));
    }

    @Test
    void issueMessagesUseTheExistingSafeMessagePolicy() {
        var failure = new TopicPublicationContentException("配置无效", List.of(PublicationProblem.at(
                PublicationProblem.CODE_TICKET_INVALID, null, "tickets[id=7]", "java.sql.SQLException password=private")));
        assertEquals("配置未通过发布检查", failure.getData().getIssues().get(0).getMessage());
        assertThrows(UnsupportedOperationException.class, () -> failure.getData().getIssues().clear());
    }
}

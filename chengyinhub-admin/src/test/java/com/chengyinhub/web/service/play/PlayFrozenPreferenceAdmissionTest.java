package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.PreferenceSubmitRequest;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.PlayNodeAccessService;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.web.service.PreferencePlayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real application/context; access and original preference judgement are explicit unit leaves. */
class PlayFrozenPreferenceAdmissionTest {
    private final PlayNodeAccessService access = mock(PlayNodeAccessService.class);
    private final ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
    private final ICmsMemberTemplateService templates = mock(ICmsMemberTemplateService.class);
    private final ITopicRouteRuntimeService routes = mock(ITopicRouteRuntimeService.class);
    private final PreferencePlayService preference = mock(PreferencePlayService.class);
    private PlayPreferenceApplication application;
    private CmsTopicNode node;

    @BeforeEach void fixture() {
        application = new PlayPreferenceApplication(); PlayNodeContext context = new PlayNodeContext();
        ReflectionTestUtils.setField(context, "playNodeAccessService", access);
        ReflectionTestUtils.setField(context, "cmsMemberTemplateService", templates);
        ReflectionTestUtils.setField(context, "topicRouteRuntimeService", routes);
        ReflectionTestUtils.setField(application, "playNodeContext", context);
        ReflectionTestUtils.setField(application, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(application, "cmsMemberTemplateService", templates);
        ReflectionTestUtils.setField(application, "preferencePlayService", preference);
        node = new CmsTopicNode(); node.setId(11L); node.setTopicId(21L); node.setStatus(1L);
        when(nodes.selectCmsTopicNodeById(11L)).thenReturn(node);
        when(access.resolveContext(eq(7L), isNull(), eq("21"), any())).thenReturn(
                new PlayNodeAccessService.Context(0L, 21L, true, false, true, 91L, 2, null));
        ObjectNode content = new ObjectMapper().createObjectNode().put("contentSnapshotVersion", 1).put("templateId", 31L)
                .put("validationMethod", ValidationMethod.PREFERENCE_GROUP).put("preferenceJson", "original-preference-config");
        content.putObject("nodePresentation").put("id", 11L).put("topicId", 21L).put("templateId", 31L);
        content.putObject("templatePresentation").put("title", "original");
        when(routes.nodeAnswers(7L, 21L, 0L, 11L)).thenReturn(content);
        when(preference.load(eq(7L), eq(21L), eq(11L), any())).thenReturn(Map.of("original", true));
    }

    @Test void originalPreferenceSurvivesUnbindAfterOriginalScopeAndRouteAdmission() {
        assertEquals(200, application.preference(7L, 11L, null, "21").get("code"));
        ArgumentCaptor<CmsMemberTemplate> original = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(preference).load(eq(7L), eq(21L), eq(11L), original.capture());
        assertEquals(31L, original.getValue().getId());
        assertEquals("original-preference-config", original.getValue().getPreferenceJson());
        verify(access).resolveContext(eq(7L), isNull(), eq("21"), any());
        verify(access).routeAccessError(eq(7L), eq(0L), eq(21L), any(), isNull());
        verifyNoInteractions(templates);
    }

    @Test void frozenContentDoesNotOverrideTheOriginalOwnerDenial() {
        when(access.resolveContext(eq(8L), isNull(), eq("21"), any())).thenReturn(
                new PlayNodeAccessService.Context(null, null, true, false, false, null, null,
                        new PlayNodeAccessService.Failure(403, "original owner denial")));
        assertEquals(403, application.preference(8L, 11L, null, "21").get("code"));
        verifyNoInteractions(routes, preference, templates);
    }

    @Test void originalCurrentDelistingStillPreventsANewPreferenceMutation() {
        when(access.isDelistedNode(any())).thenReturn(true);
        PreferenceSubmitRequest request = new PreferenceSubmitRequest(); request.setTopicId("21");
        assertEquals(500, application.submitPreference(7L, 11L, request).get("code"));
        verifyNoInteractions(preference);
    }
}

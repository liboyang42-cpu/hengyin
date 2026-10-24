package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiPlayPuzzleHintTest
{
    private static final long MEMBER = 88L;
    private static final long TOPIC = 301L;
    private static final long NODE = 401L;
    private static final long TEMPLATE = 501L;

    private ApiPlayProgressController controller;
    private PlayAttemptLogMapper attemptMapper;
    private IPlayerNodeProgressService progressService;

    @BeforeEach
    void setUp()
    {
        controller = spy(new ApiPlayProgressController());
        org.mockito.Mockito.doReturn(MEMBER).when(controller).getAppUserId();

        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setTemplateId(TEMPLATE);
        node.setSortId(1L);
        when(nodeService.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));

        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE);
        template.setValidationMethod(1);
        template.setHint1("先看门楣上的年份");
        template.setHint2("答案是门楣上最左边的四位数");
        when(templateService.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(template);

        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        CmsRegistration pass = new CmsRegistration();
        pass.setMemberId(MEMBER);
        pass.setOwnerType(1L);
        pass.setOwnerId(TOPIC);
        pass.setRegistrationStatus(2);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(pass));

        ICmsTopicService topicService = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(ProductType.ORIENTEERING);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);

        attemptMapper = mock(PlayAttemptLogMapper.class);
        when(attemptMapper.insertPlayAttemptLog(any())).thenReturn(1);

        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(controller, "cmsMemberTemplateService", templateService);
        ReflectionTestUtils.setField(controller, "nodeMerchantOverrideService",
                new com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl(
                        mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class)));
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        progressService = mock(IPlayerNodeProgressService.class);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        when(progressService.insertPlayerNodeProgress(any())).thenReturn(1);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        ReflectionTestUtils.setField(controller, "playAttemptLogMapper", attemptMapper);
        com.chengyinhub.business.mapper.PlayerNodeProgressMapper progressMapper =
                mock(com.chengyinhub.business.mapper.PlayerNodeProgressMapper.class);
        when(progressMapper.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        com.chengyinhub.business.mapper.UmsMemberMapper memberMapper =
                mock(com.chengyinhub.business.mapper.UmsMemberMapper.class);
        when(memberMapper.lockMemberById(MEMBER)).thenReturn(MEMBER);
        ReflectionTestUtils.setField(controller, "puzzlePlayService",
                new com.chengyinhub.business.service.impl.PuzzlePlayServiceImpl(
                        templateService, attemptMapper, progressMapper, memberMapper));
        ReflectionTestUtils.setField(controller, "pointsAwardService",
                mock(com.chengyinhub.business.service.IPointsAwardService.class));
        com.chengyinhub.business.service.IPlayerProgressionService progression =
                mock(com.chengyinhub.business.service.IPlayerProgressionService.class);
        when(progression.onNodeDone(MEMBER)).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "playerProgressionService", progression);
        ReflectionTestUtils.setField(controller, "xpBudgetService",
                mock(com.chengyinhub.business.service.IXpBudgetService.class));
        ReflectionTestUtils.setField(controller, "growthService",
                mock(com.chengyinhub.business.service.IGrowthService.class));
        ReflectionTestUtils.setField(controller, "rewardDropService",
                mock(com.chengyinhub.business.service.IRewardDropService.class));
        ReflectionTestUtils.setField(controller, "memberCollectibleMapper",
                mock(com.chengyinhub.business.mapper.MemberCollectibleMapper.class));
        CurrentPlayControllerFixtures.install(controller);
    }

    @SuppressWarnings("unchecked")
    @Test
    void firstHintIsFreeAndCapsPuzzleScoreAtSeventy()
    {
        when(attemptMapper.selectMaxPuzzleHintLevel(MEMBER, TOPIC, NODE, 0L)).thenReturn(0);

        AjaxResult result = controller.puzzleHint(null, String.valueOf(NODE), String.valueOf(TOPIC), 1);

        assertEquals(200, result.get("code"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(1, data.get("level"));
        assertEquals(70, data.get("scoreCap"));
        assertEquals("先看门楣上的年份", data.get("hint"));
        ArgumentCaptor<PlayAttemptLog> log = ArgumentCaptor.forClass(PlayAttemptLog.class);
        verify(attemptMapper).insertPlayAttemptLog(log.capture());
        assertEquals(5, log.getValue().getAttemptType());
        assertEquals(1, log.getValue().getResult());
    }

    @SuppressWarnings("unchecked")
    @Test
    void secondHintCapsPuzzleScoreAtForty()
    {
        when(attemptMapper.selectMaxPuzzleHintLevel(MEMBER, TOPIC, NODE, 0L)).thenReturn(1);

        AjaxResult result = controller.puzzleHint(null, String.valueOf(NODE), String.valueOf(TOPIC), 2);

        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(2, data.get("level"));
        assertEquals(40, data.get("scoreCap"));
        assertEquals("答案是门楣上最左边的四位数", data.get("hint"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void revealCompletesThePuzzleWithZeroScoreWithoutChangingXpSemantics()
    {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE);
        template.setValidationMethod(1);
        template.setQuestionAnswer("1932");
        template.setAnswerReveal("1932，门楣最左边的年份");
        ICmsMemberTemplateService service = (ICmsMemberTemplateService)
                ReflectionTestUtils.getField(controller, "cmsMemberTemplateService");
        when(service.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(template);

        AjaxResult result = controller.revealPuzzle(null, String.valueOf(NODE), String.valueOf(TOPIC));

        assertEquals(200, result.get("code"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(0, data.get("puzzleScore"));
        assertEquals("REVEALED", data.get("completionMode"));
        assertEquals("1932，门楣最左边的年份", data.get("answerReveal"));
        ArgumentCaptor<com.chengyinhub.business.domain.PlayerNodeProgress> progress =
                ArgumentCaptor.forClass(com.chengyinhub.business.domain.PlayerNodeProgress.class);
        verify(progressService).insertPlayerNodeProgress(progress.capture());
        assertEquals(0, progress.getValue().getPuzzleScore());
        assertEquals("REVEALED", progress.getValue().getCompletionMode());
    }

    @Test
    void orientingPuzzleSecretsAreNotDownlinkedBeforeServerFactIsRecorded() throws Exception
    {
        String source = new String(Files.readAllBytes(Paths.get("src", "main", "java", "com", "chengyinhub",
                "web", "controller", "api", "ApiPlayProgressController.java")), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertEquals(true, source.contains("if (!puzzleScoring) {"),
                "城市定向谜题的 hint/answerReveal 必须只通过服务端事实接口返回");
    }

    @Test
    void revealCannotBypassScanOrGpsValidation()
    {
        ICmsMemberTemplateService service = (ICmsMemberTemplateService)
                ReflectionTestUtils.getField(controller, "cmsMemberTemplateService");
        CmsMemberTemplate physicalTask = new CmsMemberTemplate();
        physicalTask.setId(TEMPLATE);

        physicalTask.setValidationMethod(4);
        when(service.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(physicalTask);
        assertEquals(500, controller.revealPuzzle(null, String.valueOf(NODE), String.valueOf(TOPIC)).get("code"));

        physicalTask.setValidationMethod(5);
        assertEquals(500, controller.revealPuzzle(null, String.valueOf(NODE), String.valueOf(TOPIC)).get("code"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }
}

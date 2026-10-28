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
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", templateService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService",
                new com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl(
                        mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", topicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        progressService = mock(IPlayerNodeProgressService.class);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        when(progressService.insertPlayerNodeProgress(any())).thenReturn(1);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper", attemptMapper);
        com.chengyinhub.business.mapper.PlayerNodeProgressMapper progressMapper =
                mock(com.chengyinhub.business.mapper.PlayerNodeProgressMapper.class);
        when(progressMapper.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        com.chengyinhub.business.mapper.UmsMemberMapper memberMapper =
                mock(com.chengyinhub.business.mapper.UmsMemberMapper.class);
        when(memberMapper.lockMemberById(MEMBER)).thenReturn(MEMBER);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "puzzlePlayService",
                new com.chengyinhub.business.service.impl.PuzzlePlayServiceImpl(
                        templateService, attemptMapper, progressMapper, memberMapper,
                mock(com.chengyinhub.business.service.ICmsTopicNodeService.class),
                mock(com.chengyinhub.business.service.support.PlayNodeAccessService.class),
                mock(com.chengyinhub.business.service.ICmsPointsSettingService.class),
                mock(com.chengyinhub.business.service.IUmsMemberService.class),
                mock(com.chengyinhub.business.service.IUmsMemberPointsDetailService.class)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "pointsAwardService",
                mock(com.chengyinhub.business.service.IPointsAwardService.class));
        com.chengyinhub.business.service.IPlayerProgressionService progression =
                mock(com.chengyinhub.business.service.IPlayerProgressionService.class);
        when(progression.onNodeDone(MEMBER)).thenReturn(Collections.emptyList());
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerProgressionService", progression);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "xpBudgetService",
                mock(com.chengyinhub.business.service.IXpBudgetService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "growthService",
                mock(com.chengyinhub.business.service.IGrowthService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "rewardDropService",
                mock(com.chengyinhub.business.service.IRewardDropService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "memberCollectibleMapper",
                mock(com.chengyinhub.business.mapper.MemberCollectibleMapper.class));
        CurrentPlayControllerFixtures.install(controller);
        BasicPlayCommandFixtures.scope(controller, TOPIC);
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
                com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "cmsMemberTemplateService");
        when(service.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(template);

        AjaxResult result = BasicPlayCommandFixtures.revealPuzzle(controller, null, String.valueOf(NODE), String.valueOf(TOPIC));

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
        // The production /nodes projection moved out of the HTTP facade; keep the same secret guard.
        String source = new String(Files.readAllBytes(Paths.get("src", "main", "java", "com", "chengyinhub",
                "web", "service", "play", "PlayNodeQueryApplication.java")), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertEquals(true, source.contains("if (!puzzleScoring) {"),
                "城市定向谜题的 hint/answerReveal 必须只通过服务端事实接口返回");
    }

    @Test
    void revealCannotBypassScanOrGpsValidation()
    {
        ICmsMemberTemplateService service = (ICmsMemberTemplateService)
                com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "cmsMemberTemplateService");
        CmsMemberTemplate physicalTask = new CmsMemberTemplate();
        physicalTask.setId(TEMPLATE);

        physicalTask.setValidationMethod(4);
        when(service.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(physicalTask);
        assertEquals(500, BasicPlayCommandFixtures.revealPuzzle(controller, null, String.valueOf(NODE), String.valueOf(TOPIC)).get("code"));

        physicalTask.setValidationMethod(5);
        assertEquals(500, BasicPlayCommandFixtures.revealPuzzle(controller, null, String.valueOf(NODE), String.valueOf(TOPIC)).get("code"));
        verify(progressService, never()).insertPlayerNodeProgress(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void admittedUnregisteredBrowseUsesPublishedOrdinaryAndNonTemplateContentWithoutRuntimeEntry() throws Exception
    {
        ICmsTopicNodeService nodes=(ICmsTopicNodeService)PlayNodeAccessTestFixtures.getField(controller,"cmsTopicNodeService");
        ICmsTopicService topics=(ICmsTopicService)PlayNodeAccessTestFixtures.getField(controller,"cmsTopicService");
        ICmsMemberTemplateService templates=(ICmsMemberTemplateService)PlayNodeAccessTestFixtures.getField(controller,"cmsMemberTemplateService");
        ICmsRegistrationService registrations=(ICmsRegistrationService)PlayNodeAccessTestFixtures.getField(controller,"cmsRegistrationService");
        CmsTopic topic=topics.selectCmsTopicById(TOPIC); topic.setPublishedConfigVersion(1L);
        when(topics.selectCmsTopicDetailById(TOPIC,MEMBER)).thenReturn(topic);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        CmsTopicNode current=nodes.selectCmsTopicNodeById(NODE);current.setTemplateId(null);current.setName("later author name");current.setStatus(1L);
        CmsTopicNode noTemplate=new CmsTopicNode();noTemplate.setId(402L);noTemplate.setTopicId(TOPIC);noTemplate.setStatus(1L);
        CmsTopicNode notPublished=new CmsTopicNode();notPublished.setId(403L);notPublished.setTopicId(TOPIC);notPublished.setTemplateId(TEMPLATE);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(java.util.List.of(current,noTemplate,notPublished));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var publication=json.createObjectNode().put("runtimeSnapshotVersion",1).put("routeMode","LINEAR");
        publication.putObject("journeyRules").put("schemaVersion",1);
        publication.put("journeyStory","");
        var frozen=publication.putObject("nodeAnswers").putObject(String.valueOf(NODE));
        frozen.put("contentSnapshotVersion",1).put("templateId",TEMPLATE).put("validationMethod",1).put("questionAnswer","secret-answer");
        frozen.putObject("nodePresentation").put("id",NODE).put("topicId",TOPIC).put("templateId",TEMPLATE).put("name","original node");
        ((com.fasterxml.jackson.databind.node.ObjectNode)frozen.path("nodePresentation")).put("chapterId",201L);
        frozen.put("chapterAudioUrl","/original/chapter.mp3");
        frozen.putObject("templatePresentation").put("title","original game").put("questionName","original question");
        frozen.put("hint1","secret-unused-hint");
        var plain=publication.with("nodeAnswers").putObject("402").put("contentSnapshotVersion",1);
        plain.putNull("templateId");plain.putObject("templatePresentation");
        plain.putObject("nodePresentation").put("id",402L).put("topicId",TOPIC).putNull("templateId").put("name","original non-template node");
        publication.putObject("nodeConfigs").putObject(String.valueOf(NODE)).put("schemaVersion",1);
        topic.setPublishedRouteGraphJson(publication.toString());
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":[{\"key\":\"counter.later\",\"label\":\"later\"}]}");
        var activity=new com.chengyinhub.business.domain.CmsActivity();activity.setId(700L);activity.setTopicId(TOPIC);
        var activities=mock(com.chengyinhub.business.service.ICmsActivityService.class);
        when(activities.selectCmsActivityById(700L)).thenReturn(activity);
        var runtime=mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        var shared=mock(com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        PlayNodeAccessTestFixtures.setField(controller,"cmsActivityService",activities);
        PlayNodeAccessTestFixtures.setField(controller,"topicRouteRuntimeService",runtime);
        PlayNodeAccessTestFixtures.setField(controller,"gameSessionRuntimeService",shared);
        var chapter=new com.chengyinhub.business.domain.CmsTopicChapter();chapter.setId(201L);chapter.setTopicId(TOPIC);chapter.setAudioUrl("/later/chapter.mp3");
        var chapters=mock(com.chengyinhub.business.service.ICmsTopicChapterService.class);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(java.util.List.of(chapter));
        PlayNodeAccessTestFixtures.setField(controller,"cmsTopicChapterService",chapters);
        PlayNodeAccessTestFixtures.setField(controller,"umsMemberPointsDetailService",mock(com.chengyinhub.business.service.IUmsMemberPointsDetailService.class));
        org.mockito.Mockito.clearInvocations(templates);

        AjaxResult result=controller.nodes(700L,null);

        assertEquals(200,result.get("code"));Map<String,Object> data=(Map<String,Object>)result.get("data");
        assertEquals(false,data.get("registered"));assertEquals(2,data.get("total"));
        var projected=(java.util.List<Map<String,Object>>)data.get("nodes");
        assertEquals("original question",projected.get(0).get("question"));
        assertEquals("original non-template node",projected.get(1).get("name"));
        assertEquals(false,projected.get(1).containsKey("gameTitle"));
        assertEquals(false,json.writeValueAsString(data).contains("secret-"));
        assertEquals(null,data.get("vitalsOn"));
        assertEquals("/original/chapter.mp3",((java.util.List<Map<String,Object>>)data.get("chapters")).get(0).get("audioUrl"));
        verify(templates,never()).selectCmsMemberTemplateById(any());
        verify(runtime,never()).state(any(),any(),any());
        verify(runtime,never()).nodeRuntimeConfig(any(),any(),any(),any());
        verify(runtime,never()).queryNodeContentSnapshot(any(),any(),any(),any());
        org.mockito.Mockito.verifyNoInteractions(runtime,shared);
        verify(progressService,never()).insertPlayerNodeProgress(any());
        verify(attemptMapper,never()).insertPlayAttemptLog(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void originalHintCallerUsesFrozenContentAfterTemplateUnbindAndKeepsHintFact() {
        ICmsTopicNodeService nodes=(ICmsTopicNodeService)PlayNodeAccessTestFixtures.getField(controller,"cmsTopicNodeService");
        ICmsMemberTemplateService templates=(ICmsMemberTemplateService)PlayNodeAccessTestFixtures.getField(controller,"cmsMemberTemplateService");
        nodes.selectCmsTopicNodeById(NODE).setTemplateId(null);
        var runtime=mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        when(runtime.canPlay(MEMBER,TOPIC,0L,NODE,null)).thenReturn(true);
        var frozen=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        frozen.put("contentSnapshotVersion",1).put("templateId",TEMPLATE).put("validationMethod",1);
        frozen.putObject("nodePresentation").put("id",NODE).put("topicId",TOPIC).put("templateId",TEMPLATE);
        frozen.putObject("templatePresentation");
        frozen.put("hint1","冻结第一提示").put("hint2","未使用第二提示");
        when(runtime.nodeAnswers(MEMBER,TOPIC,0L,NODE)).thenReturn(frozen);
        PlayNodeAccessTestFixtures.setField(controller,"topicRouteRuntimeService",runtime);
        AjaxResult result=controller.puzzleHint(null,String.valueOf(NODE),String.valueOf(TOPIC),1);
        assertEquals(200,result.get("code"));Map<String,Object> data=(Map<String,Object>)result.get("data");
        assertEquals("冻结第一提示",data.get("hint"));assertEquals(70,data.get("scoreCap"));
        assertEquals(Collections.singletonList("冻结第一提示"),data.get("hints"));
        ArgumentCaptor<PlayAttemptLog> attempt=ArgumentCaptor.forClass(PlayAttemptLog.class);
        verify(attemptMapper).insertPlayAttemptLog(attempt.capture());
        assertEquals(MEMBER,attempt.getValue().getMemberId());assertEquals(TOPIC,attempt.getValue().getTopicId());
        assertEquals(NODE,attempt.getValue().getNodeId());assertEquals(0L,attempt.getValue().getActivityId());
        verify(templates,never()).selectCmsMemberTemplateById(any());
        verify(progressService,never()).insertPlayerNodeProgress(any());
    }
}

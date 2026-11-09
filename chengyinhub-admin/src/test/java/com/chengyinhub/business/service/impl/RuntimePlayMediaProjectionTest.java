package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * qx-c1 #3: the runtime session view used to pass QA option {@code img} and suspect {@code avatar} as the stored
 * {@code media-asset:{id}} reference. They now go through the reviewed-media resolver like other play media:
 * approved assets become their URL, unapproved or unresolvable ones are dropped (with their alt), alt text stays.
 */
class RuntimePlayMediaProjectionTest {
    private static final long MEMBER_ID = 7L, ACTIVITY_ID = 9L, TOPIC_ID = 19L, NODE_ID = 29L, TEMPLATE_ID = 39L, SESSION_ID = 51L;
    private static final String APPROVED_URL = "https://img.example.test/profile/upload/2026/11/09/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png";

    private AdvancedGameRuntimeServiceImpl service;
    private GameRuntimeSessionMapper sessionMapper;
    private AppMediaReferenceGuard media;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(GameRuntimeSessionMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        PlayTeamMapper teamMapper = mock(PlayTeamMapper.class);
        service = new AdvancedGameRuntimeServiceImpl();
        AdvancedActionTestFixtures.transactions(service);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "activityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "registrationMapper", mock(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "teamMapper", teamMapper);
        ReflectionTestUtils.setField(service, "attemptMapper", mock(PlayAttemptLogMapper.class));
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", mock(ITopicRouteRuntimeService.class));
        Date now = new Date();
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> now);
        ReflectionTestUtils.setField(service, "random", (AdvancedGameRandom) bound -> 0);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID); node.setTopicId(TOPIC_ID); node.setTemplateId(TEMPLATE_ID);
        when(nodeMapper.selectCmsTopicNodeById(NODE_ID)).thenReturn(node);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID); activity.setTopicId(TOPIC_ID);
        activity.setStartDate(new Date(now.getTime() - 86_400_000L)); activity.setEndDate(new Date(now.getTime() + 86_400_000L));
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);
        when(teamMapper.hasPaidRegistration(MEMBER_ID, 2, ACTIVITY_ID)).thenReturn(1);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);

        // The real guard with its asset registry mocked: 11 is APPROVED, 12 is still PENDING (uploaded by the viewer).
        media = new AppMediaReferenceGuard();
        AppMediaAssetMapper assets = mock(AppMediaAssetMapper.class);
        AppMediaAssetService assetService = mock(AppMediaAssetService.class);
        when(assets.select(11L)).thenReturn(Map.of("id", 11L, "reviewState", "APPROVED", "memberId", 99L));
        when(assets.select(12L)).thenReturn(Map.of("id", 12L, "reviewState", "PENDING", "memberId", MEMBER_ID));
        when(assetService.approvedUrl(11L)).thenReturn(APPROVED_URL);
        when(assetService.ownerPreview(eq(12L), anyLong())).thenReturn("https://preview.example.test/should-not-leak.png");
        ReflectionTestUtils.setField(media, "mapper", assets);
        ReflectionTestUtils.setField(media, "assets", assetService);
        ReflectionTestUtils.setField(service, "mediaReferences", media);
    }

    private static final String CONFIG = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"title\":\"谁是凶手\","
            + "\"options\":["
            + "{\"id\":\"o1\",\"label\":\"张医生\",\"correct\":true,\"img\":\"media-asset:11\",\"alt\":\"穿白大褂的人\"},"
            + "{\"id\":\"o2\",\"label\":\"李师傅\",\"img\":\"media-asset:12\",\"alt\":\"待审的图\"},"
            + "{\"id\":\"o3\",\"label\":\"周老板\"}]},"
            + "\"accusation\":{\"enabled\":true,\"suspects\":["
            + "{\"id\":\"o1\",\"name\":\"张医生\",\"avatar\":\"media-asset:11\"},"
            + "{\"id\":\"o2\",\"name\":\"李师傅\",\"avatar\":\"media-asset:12\"},"
            + "{\"id\":\"o3\",\"name\":\"周老板\"}],"
            + "\"winScreen\":{\"title\":\"真相大白\",\"body\":\"你找到了他\"},\"loseScreen\":{\"title\":\"再想想\",\"body\":\"证据不足\"}}}";

    @SuppressWarnings("unchecked")
    @Test
    void optionImagesAndSuspectAvatarsAreProjectedOnlyForApprovedAssets() {
        GameRuntimeSession session = new GameRuntimeSession();
        session.setId(SESSION_ID); session.setActivityId(ACTIVITY_ID); session.setTopicId(TOPIC_ID); session.setNodeId(NODE_ID);
        session.setTemplateId(TEMPLATE_ID); session.setOwnerType(GameRuntimeSession.OWNER_PLAYER); session.setOwnerId(MEMBER_ID);
        session.setConfigSnapshotJson(CONFIG); session.setStateJson("{}"); session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setVersion(0); session.setScore(0); session.setStartedAt(new Date());
        when(sessionMapper.selectAccessibleById(SESSION_ID, MEMBER_ID)).thenReturn(session);
        when(sessionMapper.updateStateCas(any())).thenReturn(1);

        Map<String, Object> view = service.state(MEMBER_ID, SESSION_ID);
        Map<String, Object> qa = (Map<String, Object>) ((Map<String, Object>) view.get("playKit")).get("qa");
        List<Map<String, Object>> options = (List<Map<String, Object>>) qa.get("options");
        assertEquals(APPROVED_URL, options.get(0).get("img"));
        assertEquals("穿白大褂的人", options.get(0).get("alt"), "alt text is kept");
        assertFalse(options.get(1).containsKey("img"), "pending asset is not shown, not even to its uploader");
        assertFalse(options.get(1).containsKey("alt"));
        assertFalse(options.get(2).containsKey("img"));
        assertFalse(options.get(0).containsKey("correct"), "answers stay secret");

        Map<String, Object> accusation = (Map<String, Object>) view.get("accusation");
        List<Map<String, Object>> suspects = (List<Map<String, Object>>) accusation.get("suspects");
        assertEquals(APPROVED_URL, suspects.get(0).get("avatar"));
        assertFalse(suspects.get(1).containsKey("avatar"));
        String dumped = String.valueOf(view);
        assertFalse(dumped.contains("media-asset:"), dumped);
        assertFalse(dumped.contains("should-not-leak"), dumped);
    }

    @Test
    void publishedPlayUrlNeverPreviewsUnapprovedAssetsButKeepsNonRegistryReferences() {
        assertEquals(APPROVED_URL, media.publishedPlayUrl("media-asset:11", MEMBER_ID));
        assertEquals("", media.publishedPlayUrl("media-asset:12", MEMBER_ID));
        assertEquals("", media.publishedPlayUrl("media-asset:404", MEMBER_ID));
        assertTrue(service.playMediaUrl("media-asset:11", MEMBER_ID).startsWith("https://"));
    }
}

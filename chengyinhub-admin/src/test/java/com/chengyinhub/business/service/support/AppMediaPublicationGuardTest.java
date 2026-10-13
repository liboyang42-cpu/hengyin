package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppMediaPublicationGuardTest {
    private AppMediaPublicationGuard guard;
    private AppMediaReferenceGuard references;
    private CmsTopicMapper topics;
    private CmsTopicNodeMapper nodes;
    private CmsMemberTemplateMapper templates;
    private AppMediaAssetMapper assets;
    private static final String IMAGE_KEY = "upload/0123456789abcdef0123456789abcdef.jpg";
    private static final String AUDIO_KEY = "upload/0123456789abcdef0123456789abcdef.mp3";
    private void mediaState(String state) {
        for (long id : new long[]{7,8}) {
            String key = id == 7 ? IMAGE_KEY : AUDIO_KEY;
            java.util.Map<String,Object> row = java.util.Map.of("id",id,"memberId",9L,"ownerType","APP",
                    "objectKey",key,"reviewState",state,"mediaType",id == 7 ? "IMAGE" : "AUDIO");
            when(assets.select(id)).thenReturn(row); when(assets.selectByObjectKey(key)).thenReturn(row);
        }
    }
    @BeforeEach void setup() {
        guard = new AppMediaPublicationGuard(); references = new AppMediaReferenceGuard();
        assets = mock(AppMediaAssetMapper.class);
        ReflectionTestUtils.setField(references,"mapper",assets);
        ReflectionTestUtils.setField(references,"assets",mock(com.chengyinhub.business.service.impl.AppMediaAssetService.class));
        topics = mock(CmsTopicMapper.class); nodes = mock(CmsTopicNodeMapper.class); templates = mock(CmsMemberTemplateMapper.class);
        ReflectionTestUtils.setField(guard,"references",references);
        ReflectionTestUtils.setField(guard,"topics",topics);
        ReflectionTestUtils.setField(guard,"nodes",nodes);
        ReflectionTestUtils.setField(guard,"templates",templates);
        ReflectionTestUtils.setField(guard,"chapters",mock(CmsTopicChapterMapper.class));
        mediaState("HOLD");
    }
    @Test void ownerDraftKeepsCanonicalHeldAssetAndPublishingRechecksRealState() {
        CmsMemberTemplate template = new CmsMemberTemplate();template.setImgUrl("https://owned/"+IMAGE_KEY);
        guard.requireTemplate(9L,template,false);
        assertEquals("media-asset:7",template.getImgUrl());
        assertThrows(ServiceException.class,()->guard.requireTemplate(9L,template,true));
        mediaState("APPROVED");
        guard.requireTemplate(9L,template,true);
        assertEquals("media-asset:7",template.getImgUrl());
    }
    @Test void advancedQaAlbumAndOptionMediaUseExplicitSchemaAndLeaveNavigationAndText() throws Exception {
        String source="{\"qa\":{\"options\":[{\"fbImgs\":[\"https://owned/"+IMAGE_KEY+"\"]}]},"
                +"\"album\":{\"images\":[{\"url\":\"https://owned/"+IMAGE_KEY+"\",\"line\":\"原配文\"}]},"
                +"\"A\":{\"img\":\"https://owned/"+IMAGE_KEY+"\",\"audio\":\"https://owned/"+AUDIO_KEY+"\"},"
                +"\"navigation\":{\"url\":\"https://normal/navigation\"}}";
        String result=guard.requireJson(9L,source,false);
        assertFalse(result.contains("https://owned")); assertTrue(result.contains("https://normal/navigation"));
        assertTrue(result.contains("原配文"));
        verify(assets,atLeastOnce()).selectByObjectKey(IMAGE_KEY);
        assertEquals("media-asset:8",new ObjectMapper().readTree(result).path("A").path("audio").asText());
    }
    @Test void topicPublicationChecksActuallyReferencedNodeTemplateAndRejectsHeldMedia() {
        CmsTopic topic=new CmsTopic();topic.setId(1L);topic.setMemberId(9L);
        when(topics.selectCmsTopicById(1L)).thenReturn(topic);
        CmsTopicNode node=new CmsTopicNode();node.setTemplateId(3L);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(List.of(node));
        CmsMemberTemplate template=new CmsMemberTemplate();template.setMemberId(9L);template.setAudioUrl("media-asset:8");
        when(templates.selectCmsMemberTemplateById(3L)).thenReturn(template);
        assertThrows(ServiceException.class,()->guard.requireTopicPublication(1L));
        verify(assets).select(8L);
    }
    @Test void missingReferencedTemplateCannotBecomePublicEvenWithNoMediaFields() {
        CmsTopic topic = new CmsTopic(); topic.setId(1L); topic.setMemberId(9L);
        when(topics.selectCmsTopicById(1L)).thenReturn(topic);
        CmsTopicNode node = new CmsTopicNode(); node.setTemplateId(99L);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(List.of(node));
        ServiceException error = assertThrows(ServiceException.class, () -> guard.requireTopicPublication(1L));
        assertTrue(error.getMessage().contains("节点模板不存在"));
        verify(templates).selectCmsMemberTemplateById(99L);
        verifyNoInteractions(assets);
    }
    @Test void staleMediaReferenceCannotBecomePublicJustBecauseTheBusinessReviewIsApproved() {
        CmsMemberTemplate current=new CmsMemberTemplate();current.setStoryImg("https://unregistered/image.jpg");
        assertThrows(ServiceException.class,()->guard.requireTemplate(9L,current,true));
    }
    @Test void exactPlayerRhythmAssetsKeepOriginalDraftAndPublishedTemplateBytes() {
        for (String prefix : new String[]{"/audio/merchant-games/","/pages/play/audio/merchant-games/"}) {
            for (String clip : new String[]{"one","two","three"}) {
                String reference = prefix+"rhythm-"+clip+".mp3";
                String config = "{ \"random\": {\"items\":[{\"audioUrl\":\""+reference+"\",\"content\":\"原说明\"}]}}";
                for (boolean publish : new boolean[]{false,true}) {
                    CmsMemberTemplate template = new CmsMemberTemplate(); template.setAdvancedConfigJson(config);
                    guard.requireTemplate(9L,template,publish);
                    assertEquals(config,template.getAdvancedConfigJson());
                    CmsTopic topic = new CmsTopic(); topic.setAudioUrl(reference);
                    guard.requireTopic(9L,topic,publish); assertEquals(reference,topic.getAudioUrl());
                }
            }
        }
        verifyNoInteractions(assets);
    }
    @Test void unknownRemoteTraversalAndQueryAliasesCannotClaimFirstPartyAudioAdmission() {
        for (String reference : new String[]{"/audio/merchant-games/unknown.mp3",
                "/audio/merchant-games/../rhythm-one.mp3", "/audio/merchant-games/%72hythm-one.mp3",
                "/audio/merchant-games/rhythm-one.mp3?anything=1", "/pages/play/audio/merchant-games/rhythm-one.mp3.evil",
                "https://www.chengyinhub.com/audio/merchant-games/rhythm-one.mp3",
                "https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/rhythm-blind-guess.jpg"}) {
            assertFalse(AppMediaSchema.isBundledMerchantGameAudio(reference));
            assertThrows(ServiceException.class,()->guard.requireMedia(9L,reference,true));
        }
    }

}

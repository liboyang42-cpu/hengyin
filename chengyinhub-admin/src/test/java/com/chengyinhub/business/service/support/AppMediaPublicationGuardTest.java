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
    // ===== V-13 / V-11(2026-10-29):公开模板自带的图,采用的人直接能用 =====
    private static final String OFFICIAL_COVER = "https://www.chengyinhub.com/prod-api/profile/template-covers/p03-capture-this-moment.webp";
    private static final String LEGACY_STORY = "https://xianghuioss.oss-cn-shanghai.aliyuncs.com/legacy/story.png";
    private CmsTemplateLibraryMapper sourceLibrary(Long status) {
        com.chengyinhub.business.domain.CmsTemplateLibrary source = new com.chengyinhub.business.domain.CmsTemplateLibrary();
        source.setId(5L); source.setDelFlag(0); source.setStatus(status);
        source.setImgUrl(OFFICIAL_COVER); source.setStoryImg(LEGACY_STORY); source.setQuestionImg("media-asset:7");
        source.setAdvancedConfigJson("{\"album\":{\"images\":[{\"url\":\"" + LEGACY_STORY + "\",\"line\":\"配文\"}]}}");
        CmsTemplateLibraryMapper libraries = mock(CmsTemplateLibraryMapper.class);
        when(libraries.selectCmsTemplateLibraryById(5L)).thenReturn(source);
        ReflectionTestUtils.setField(guard, "libraries", libraries);
        return libraries;
    }
    private CmsMemberTemplate adopted(String storyImg) {
        CmsMemberTemplate template = new CmsMemberTemplate(); template.setOriginalTemplateId(5L);
        template.setImgUrl(OFFICIAL_COVER); template.setStoryImg(storyImg);
        template.setAdvancedConfigJson("{\"album\":{\"images\":[{\"url\":\"" + LEGACY_STORY + "\",\"line\":\"配文\"}]}}");
        return template;
    }
    @Test void adoptedTemplateKeepsTheSourceTemplatesOwnCoverAndGameplayImages() {
        sourceLibrary(1L);
        for (boolean publish : new boolean[]{false, true}) {
            CmsMemberTemplate template = adopted(LEGACY_STORY);
            guard.requireTemplate(22L, template, publish);
            assertEquals(OFFICIAL_COVER, template.getImgUrl());
            assertEquals(LEGACY_STORY, template.getStoryImg());
            assertTrue(template.getAdvancedConfigJson().contains(LEGACY_STORY));
        }
    }
    @Test void inheritanceCoversOnlyTheSourcesImagesAndNeverRegisteredMediaOrAWithdrawnSource() {
        sourceLibrary(1L);
        assertThrows(ServiceException.class, () -> guard.requireTemplate(22L,
                adopted("https://xianghuioss.oss-cn-shanghai.aliyuncs.com/legacy/other.png"), false));
        // 来源里的已登记媒体仍按审核事实与归属判:别人的待审图不因「沿用」放行
        CmsMemberTemplate held = adopted(LEGACY_STORY); held.setQuestionImg("media-asset:7");
        assertThrows(ServiceException.class, () -> guard.requireTemplate(22L, held, false));
        // 没有来源编号的普通稿,同一张旧图照旧拒
        CmsMemberTemplate own = adopted(LEGACY_STORY); own.setOriginalTemplateId(0L);
        assertThrows(ServiceException.class, () -> guard.requireTemplate(22L, own, false));
        sourceLibrary(0L);
        assertThrows(ServiceException.class, () -> guard.requireTemplate(22L, adopted(LEGACY_STORY), false));
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
                "https://www.chengyinhub.com/audio/merchant-games/rhythm-one.mp3"}) {
            assertFalse(AppMediaSchema.isBundledMerchantGameAudio(reference));
            assertThrows(ServiceException.class,()->guard.requireMedia(9L,reference,true));
        }
        // 2026-10-29 业主口径:官方模板封面谁都能用,但它仍不是自带音频
        String cover = "https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/rhythm-blind-guess.jpg";
        assertFalse(AppMediaSchema.isBundledMerchantGameAudio(cover));
        assertEquals(cover, guard.requireMedia(9L, cover, true));
    }
    @Test void publishingRefusesAbsoluteExternalLinkInNonMediaField() {
        for (String link : new String[]{"https://evil.example/landing", "HTTP://evil.example", " https://evil.example ",
                "看这里 https://evil.example/x", "https:\\\\evil.example"}) {
            CmsMemberTemplate template = new CmsMemberTemplate();
            template.setAdvancedConfigJson("{\"navigation\":{\"url\":" + quote(link) + "}}");
            ServiceException error = assertThrows(ServiceException.class, () -> guard.requireTemplate(9L, template, true), link);
            assertTrue(error.getMessage().contains("暂不支持外部链接"), link);
        }
        verifyNoInteractions(assets);
    }
    @Test void publishingRefusesProtocolRelativeLinkInNonMediaField() {
        CmsTopic topic = new CmsTopic();
        topic.setThemeRuntimeConfigJson("{\"entry\":{\"link\":\"//evil.example/landing\"}}");
        ServiceException error = assertThrows(ServiceException.class, () -> guard.requireTopic(9L, topic, true));
        assertTrue(error.getMessage().contains("暂不支持外部链接"));
        assertThrows(ServiceException.class, () -> guard.requireJson(9L, "{\"blocks\":[{\"type\":\"text\",\"text\":\"\\\\\\\\evil.example\"}]}", true));
    }
    @Test void publishingKeepsInternalRoutesAndPlainTextThatMentionsADomain() {
        String config = "{\"navigation\":{\"url\":\"/pages/topic/detail?id=3\"},\"tip\":\"关注 chengyinhub.com 获取更多\","
                + "\"blocks\":[{\"type\":\"text\",\"text\":\"第 1/2 站：老街\"}],\"mode\":\"switchTab\"}";
        CmsMemberTemplate template = new CmsMemberTemplate(); template.setAdvancedConfigJson(config);
        guard.requireTemplate(9L, template, true);
        assertEquals(config, template.getAdvancedConfigJson());
        verifyNoInteractions(assets);
    }
    @Test void publishingStillResolvesMediaReferencesInsteadOfRefusingThemAsLinks() throws Exception {
        mediaState("APPROVED");
        String source = "{\"A\":{\"img\":\"https://owned/" + IMAGE_KEY + "\",\"audio\":\"https://owned/" + AUDIO_KEY + "\"},"
                + "\"album\":{\"images\":[{\"url\":\"https://owned/" + IMAGE_KEY + "\"}]},\"cover\":\"/images/a.png\"}";
        String result = guard.requireJson(9L, source, true);
        com.fasterxml.jackson.databind.JsonNode tree = new ObjectMapper().readTree(result);
        assertEquals("media-asset:7", tree.path("A").path("img").asText());
        assertEquals("media-asset:8", tree.path("A").path("audio").asText());
        assertEquals("media-asset:7", tree.path("album").path("images").path(0).path("url").asText());
        assertEquals("/images/a.png", tree.path("cover").asText());
        mediaState("HOLD");
        ServiceException held = assertThrows(ServiceException.class, () -> guard.requireJson(9L, source, true));
        assertFalse(held.getMessage().contains("暂不支持外部链接"));
    }
    private static String quote(String text) {
        try { return new ObjectMapper().writeValueAsString(text); } catch (Exception e) { throw new IllegalStateException(e); }
    }

}

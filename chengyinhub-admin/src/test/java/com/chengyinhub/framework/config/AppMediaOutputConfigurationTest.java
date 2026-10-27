package com.chengyinhub.framework.config;

import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppMediaOutputConfigurationTest {
    private AppMediaReferenceGuard guard;
    private ObjectMapper mapper;

    @BeforeEach void setUp() {
        guard = mock(AppMediaReferenceGuard.class);
        when(guard.visibleUrl(anyString(), isNull())).thenAnswer(call -> call.getArgument(0).equals("media-asset:1")
                ? "https://real/upload/approved?signature=stable" : "");
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new ApplicationConfig().jacksonObjectMapperCustomization().customize(builder);
        new AppMediaOutputConfiguration(guard).reviewedMediaOutput().customize(builder);
        mapper = builder.build();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test void onlyTheServerCaptchaTopLevelJpegBypassesUploadedMediaProjection() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/captchaImage");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        com.chengyinhub.common.core.domain.AjaxResult result = com.chengyinhub.common.core.domain.AjaxResult.success();
        result.put("captchaEnabled", true);
        result.put("img", "/9j/server-generated-jpeg");
        result.put("imgUrl", "https://unknown/upload.jpg");
        result.put("data", Map.of("img", "/9j/user-content"));
        com.fasterxml.jackson.databind.JsonNode output = mapper.readTree(mapper.writeValueAsString(result));
        assertEquals("/9j/server-generated-jpeg", output.path("img").asText());
        assertEquals("", output.path("imgUrl").asText());
        assertEquals("", output.path("data").path("img").asText());
        verify(guard, never()).visibleUrl(eq("/9j/server-generated-jpeg"), any());
        verify(guard).visibleUrl("https://unknown/upload.jpg", null);
        verify(guard).visibleUrl("/9j/user-content", null);
    }

    @Test void CaptchaShapedUploadResponseAndForwardedHeaderCannotBypassReview() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/api/template");
        request.addHeader("X-Forwarded-Uri", "/captchaImage");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        com.chengyinhub.common.core.domain.AjaxResult result = com.chengyinhub.common.core.domain.AjaxResult.success();
        result.put("captchaEnabled", true);
        result.put("img", "/9j/unreviewed-upload");
        assertEquals("", mapper.readTree(mapper.writeValueAsString(result)).path("img").asText());
        verify(guard).visibleUrl("/9j/unreviewed-upload", null);
    }

    @Test void officialPlaceGalleryJsonIsProjectedPerImageLikeTheCover() throws Exception {
        String json = mapper.writeValueAsString(Map.of("galleryJson", "[\"media-asset:1\",\"chengyin-test/raw.jpg\"]",
                "coverImg", "chengyin-test/raw.jpg"));
        com.fasterxml.jackson.databind.JsonNode out = mapper.readTree(json);
        com.fasterxml.jackson.databind.JsonNode gallery = mapper.readTree(out.path("galleryJson").asText());
        assertEquals("https://real/upload/approved?signature=stable", gallery.get(0).asText());
        assertEquals("", gallery.get(1).asText());
        assertEquals("", out.path("coverImg").asText());
    }

    /** M-29:门店相册存 JSON 字符串数组,逐张投影且保持数组形状,不能按分隔符切碎。 */
    @Test void merchantGalleryJsonArrayKeepsShapeAndHidesUnknownImages() throws Exception {
        String json = mapper.writeValueAsString(Map.of("gallery", "[\"media-asset:1\",\"https://unknown/a.jpg\"]",
                "albumPics", "media-asset:1;https://unknown/b.jpg"));
        com.fasterxml.jackson.databind.JsonNode out = mapper.readTree(json);
        com.fasterxml.jackson.databind.JsonNode gallery = mapper.readTree(out.path("gallery").asText());
        assertEquals(2, gallery.size());
        assertEquals("https://real/upload/approved?signature=stable", gallery.get(0).asText());
        assertEquals("", gallery.get(1).asText());
        assertEquals("https://real/upload/approved?signature=stable;", out.path("albumPics").asText());
        assertEquals("", mapper.readTree(mapper.writeValueAsString(Map.of("gallery", "[\"broken"))).path("gallery").asText());
    }

    @Test void canonicalArraysAndRealMapFieldsShareTheRegistryGateAndKeepOrdinaryText() throws Exception {
        String json = mapper.writeValueAsString(Map.of("avatar", "https://unregistered/photo.png",
                "frames", List.of("media-asset:1", "media-asset:2"), "url", "https://normal/navigation",
                "description", "long text https://unregistered/photo.png is just text", "name", "https://nickname"));
        assertEquals("", mapper.readTree(json).path("avatar").asText());
        assertEquals("https://real/upload/approved?signature=stable", mapper.readTree(json).path("frames").get(0).asText());
        assertEquals("", mapper.readTree(json).path("frames").get(1).asText());
        assertTrue(json.contains("https://normal/navigation"));
        assertTrue(json.contains("long text https://unregistered/photo.png is just text"));
        assertTrue(json.contains("https://nickname"));
    }

    @Test void namedGameJsonUsesExactFieldsAndAlbumPathWithoutGuessingNavigationUrls() throws Exception {
        String config = "{\"qa\":{\"options\":[{\"fbImgs\":[\"media-asset:1\",\"https://unknown/a.jpg\"]}]},"
                + "\"photoCheck\":{\"frameUrl\":\"https://unknown/frame.png\"},"
                + "\"album\":{\"images\":[{\"url\":\"https://unknown/album.jpg\",\"line\":\"原配文\"}]},"
                + "\"navigation\":{\"url\":\"https://normal/link\"},\"label\":\"原名字\"}";
        String result = mapper.readTree(mapper.writeValueAsString(Map.of("advancedConfigJson", config))).path("advancedConfigJson").asText();
        assertFalse(result.contains("https://unknown"));
        assertTrue(result.contains("signature=stable"));
        assertTrue(result.contains("https://normal/link"));
        assertTrue(result.contains("原配文"));
        assertTrue(result.contains("原名字"));
    }

    @Test void requestCacheAvoidsRepeatQueriesAndNeverCrossesARequest() throws Exception {
        mapper.writeValueAsString(Map.of("avatar", "media-asset:1", "cover", "media-asset:1"));
        verify(guard, times(1)).visibleUrl("media-asset:1", null);
        mapper.writeValueAsString(Map.of("avatar", "https://real/upload/approved?signature=stable"));
        verify(guard, times(1)).visibleUrl(anyString(), isNull());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        mapper.writeValueAsString(Map.of("avatar", "media-asset:1"));
        verify(guard, times(2)).visibleUrl("media-asset:1", null);
    }

    @Test void ordinaryJsonAndBundleAssetsAreUnchangedAndExactUrnIsAProtocolValue() throws Exception {
        String ordinary = "{\"imageUrl\":\"https://normal/link\",\"description\":\"原文本\"}";
        String json = mapper.writeValueAsString(Map.of("preferences", ordinary, "avatar", "/images/avatar/default.png",
                "name", "media-asset:1", "description", "prefix media-asset:1 suffix"));
        assertEquals(ordinary, mapper.readTree(json).path("preferences").asText());
        assertEquals("/images/avatar/default.png", mapper.readTree(json).path("avatar").asText());
        assertEquals("https://real/upload/approved?signature=stable", mapper.readTree(json).path("name").asText());
        assertEquals("prefix media-asset:1 suffix", mapper.readTree(json).path("description").asText());
    }
    @Test void thirtyFiveActualDomainSchemasResolveApprovedAndHideUnknownMedia() throws Exception {
        String[][] fields = {
            {"Club", "logo"}, {"ClubLeader", "avatar"}, {"ClubPost", "images"},
            {"CmsActivity", "imgUrl"}, {"CmsBetaGame", "imgUrl"}, {"CmsMemberTemplate", "imgUrl"},
            {"CmsTemplateLibrary", "imgUrl"}, {"CmsTopic", "imgUrl"}, {"CmsTopicChapter", "audioUrl"},
            {"CmsTopicNode", "imgUrl"}, {"CmsPresetRoute", "previewImg"}, {"CmsPresetRouteNode", "imgUrl"},
            {"CmsPresetThemeStyle", "coverImg"}, {"CmsRegistrationMerchant", "picUrl"},
            {"CreatorProfile", "avatarUrl"}, {"IpCharacter", "avatarUrl"}, {"IpCityChapter", "coverUrl"},
            {"IpWorld", "coverUrl"}, {"MmsMerchant", "logo"}, {"NpcProfile", "avatar"},
            {"OfficialEvent", "coverImg"}, {"OmsOrderItem", "pic"}, {"PlayerNodeProgress", "picUrl"},
            {"PmsGift", "picUrl"}, {"PmsProduct", "pic"}, {"PmsSku", "pic"},
            {"RoamHangout", "coverUrl"}, {"RoamPoi", "coverImg"}, {"RoamStamp", "picUrl"},
            {"UmsComment", "imgArr"}, {"UmsCreativeSquare", "pics"}, {"UmsMember", "avatar"},
            {"PlayerObjectCard", "sourceUrl"}, {"GrowthBadge", "iconUrl"}, {"PlayerBadge", "iconUrl"}
        };
        assertEquals(35, fields.length);
        for (String[] schema : fields) {
            Class<?> type = Class.forName("com.chengyinhub.business.domain." + schema[0]);
            Object bean = type.getDeclaredConstructor().newInstance();
            java.lang.reflect.Field field = type.getDeclaredField(schema[1]);
            field.setAccessible(true);
            field.set(bean, "https://unregistered/unsafe.jpg");
            assertFalse(mapper.writeValueAsString(bean).contains("https://unregistered"), schema[0]);
            field.set(bean, "media-asset:1");
            assertTrue(mapper.writeValueAsString(bean).contains("signature=stable"), schema[0]);
        }
    }
    @Test void actualJsonNodeOutputAndPixelPresetsUseTheSameSchema() throws Exception {
        com.fasterxml.jackson.databind.JsonNode tree = new ObjectMapper().readTree(
                "{\"album\":{\"images\":[{\"url\":\"https://unknown/album.jpg\"}]},\"fbImgs\":[\"media-asset:1\"],\"url\":\"https://normal/navigation\"}");
        String result = mapper.writeValueAsString(Map.of("config", tree, "avatar", "px1:p01"));
        assertFalse(result.contains("https://unknown"));
        assertTrue(result.contains("signature=stable"));
        assertTrue(result.contains("https://normal/navigation"));
        assertTrue(result.contains("px1:p01"));
    }
    @Test void stockRhythmAliasesSurviveStringAndJsonNodeProjectionButRemoteAndUnknownDoNot() throws Exception {
        for (String prefix : new String[]{"/audio/merchant-games/","/pages/play/audio/merchant-games/"}) {
            for (String clip : new String[]{"one","two","three"}) {
                String reference = prefix+"rhythm-"+clip+".mp3";
                String config = "{ \"random\":{\"items\":[{\"audioUrl\":\""+reference+"\"}]}}";
                String encoded = mapper.writeValueAsString(Map.of("audioUrl",reference,"advancedConfigJson",config));
                assertEquals(reference,mapper.readTree(encoded).path("audioUrl").asText());
                assertEquals(config,mapper.readTree(encoded).path("advancedConfigJson").asText());
                String tree = mapper.writeValueAsString(mapper.readTree(config));
                assertEquals(reference,mapper.readTree(tree).path("random").path("items").get(0).path("audioUrl").asText());
            }
        }
        verifyNoInteractions(guard);
        for (String reference : new String[]{"/audio/merchant-games/unknown.mp3",
                "/audio/merchant-games/../rhythm-one.mp3", "/audio/merchant-games/%72hythm-one.mp3",
                "/audio/merchant-games/rhythm-one.mp3?anything=1",
                "https://www.chengyinhub.com/audio/merchant-games/rhythm-one.mp3",
                "https://www.chengyinhub.com/prod-api/profile/merchant-game-covers/rhythm-blind-guess.jpg"}) {
            assertEquals("",mapper.readTree(mapper.writeValueAsString(Map.of("audioUrl",reference))).path("audioUrl").asText());
        }
    }

}

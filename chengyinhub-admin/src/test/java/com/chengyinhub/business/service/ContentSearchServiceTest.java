package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.ContentSearchItemVO;
import com.chengyinhub.business.mapper.ContentSearchMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContentSearchServiceTest {
    private ContentSearchMapper mapper;
    private ContentSearchService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ContentSearchMapper.class);
        service = new ContentSearchService();
        ReflectionTestUtils.setField(service, "contentSearchMapper", mapper);
    }

    @Test
    void refreshUpsertsPublicRowsBeforeHidingStaleOnesPerType() {
        service.refreshProjection();
        InOrder order = inOrder(mapper);
        order.verify(mapper).upsertVisibleTopics(any(Date.class));
        order.verify(mapper).hideStaleTopics(any(Date.class));
        order.verify(mapper).upsertVisibleActivities(any(Date.class));
        order.verify(mapper).hideStaleActivities(any(Date.class));
        order.verify(mapper).upsertVisiblePosts(any(Date.class));
        order.verify(mapper).hideStalePosts(any(Date.class));
    }

    @Test
    void searchNormalizesInputsAndEscapesLikeWildcards() {
        List<ContentSearchItemVO> rows = Collections.singletonList(new ContentSearchItemVO());
        when(mapper.selectUnifiedSearch(eq("100\\%\\_a\\\\b"), eq(Arrays.asList("TOPIC", "POST")),
                eq("310100"), eq(7L))).thenReturn(rows);

        assertEquals(rows, service.search("  100%_a\\b ", "topic, POST,topic", " 310100 ", 7L));
    }

    @Test
    void defaultTypesCoverTopicsActivitiesAndPosts() {
        assertEquals(Arrays.asList("TOPIC", "ACTIVITY", "POST"), ContentSearchService.documentTypes(null));
        assertEquals(Arrays.asList("TOPIC", "ACTIVITY", "POST"), ContentSearchService.documentTypes(" "));
        assertNull(ContentSearchService.cityCode(""));
    }

    @Test
    void invalidInputsAreRejectedWith400BeforeTouchingTheDatabase() {
        assertBadRequest(() -> service.search(" ", null, null, 1L));
        assertBadRequest(() -> service.search(repeat("城", 51), null, null, 1L));
        assertBadRequest(() -> service.search("咖啡", "topic,member", null, 1L));
        assertBadRequest(() -> service.search("咖啡", ",", null, 1L));
        assertBadRequest(() -> service.search("咖啡", null, "上海", 1L));
        assertBadRequest(() -> ContentSearchService.validate("咖啡", null, "31010"));
        verifyNoInteractions(mapper);
    }

    @Test
    void projectionAndQueryKeepPrivateContentOut() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get(
                "../chengyinhub-system/src/main/resources/mapper/business/ContentSearchMapper.xml")),
                StandardCharsets.UTF_8);
        // 会员专属主题、会员专属主题下的场次、非 PUBLIC 受众帖文都不能进统一搜索
        assertTrue(xml.contains("coalesce(cms_topic.club_member_only, 0) = 0"));
        assertTrue(xml.contains("coalesce(member_only_parent.club_member_only, 0) = 1"));
        assertTrue(xml.contains("p.audience = 'PUBLIC'"));
        assertTrue(xml.contains("post_sd.visibility_state = 'VISIBLE'"));
        // 读侧对每种类型都按源表实时复核，且 LIKE 使用转义
        String query = xml.substring(xml.indexOf("<select id=\"selectUnifiedSearch\""));
        assertTrue(query.contains("refid=\"topicStaticPublicGate\""));
        assertTrue(query.contains("refid=\"activityStaticPublicGate\""));
        assertTrue(query.contains("refid=\"postStaticPublicGate\""));
        assertTrue(query.contains("im_block"));
        assertTrue(query.contains("escape '\\\\'"));
        // 下线内容不留正文快照
        assertTrue(xml.contains("d.visibility_state = 'HIDDEN', d.title = null, d.search_text = ''"));
    }

    private static void assertBadRequest(Runnable call) {
        ServiceException e = assertThrows(ServiceException.class, call::run);
        assertEquals(Integer.valueOf(400), e.getCode());
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}

package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.FreeExploreVisitConfig;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FE-STORY 7.10:作者逐个地点选 GPS(300–500 米)/ 扫码 / 仅线上;只有主题作者本人可读写。 */
class FreeExploreVisitConfigRulesTest {

    private static final long AUTHOR = 7L;
    private static final long TOPIC = 70L;
    private static final long NODE = 700L;

    private FreeExploreVisitConfigRules app;
    private CmsTopicNodeMapper mapper;
    private CmsTopic topic;
    private CmsTopicNode node;

    @BeforeEach
    void setUp() {
        ICmsTopicService topics = mock(ICmsTopicService.class);
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        mapper = mock(CmsTopicNodeMapper.class);
        app = new FreeExploreVisitConfigRules(topics, nodes, mapper);
        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(AUTHOR);
        topic.setProductType(2);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        node.setName("示例站点");
        node.setLatitude("31.23");
        node.setLongitude("121.47");
        when(nodes.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        when(mapper.updateVisitConfig(anyLong(), any(), any())).thenReturn(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void authorListsPlacesWithDefaultGps() {
        when(mapper.selectVisitConfigByTopicId(TOPIC)).thenReturn(Collections.singletonList(new FreeExploreVisitConfig(NODE, 0, null)));
        AjaxResult result = app.list(AUTHOR, TOPIC);
        Map<String, Object> item = ((List<Map<String, Object>>) result.get("data")).get(0);
        assertEquals("GPS", item.get("visitCheck"));
        assertEquals(null, item.get("visitRadius"));
        assertEquals(Boolean.TRUE, item.get("hasCoordinates"));
    }

    @Test
    void authorSavesGpsRadiusWithinBounds() {
        assertEquals(200, app.update(AUTHOR, NODE, "GPS", 450).get("code"));
        verify(mapper).updateVisitConfig(NODE, 0, 450);
    }

    @Test
    void radiusOutsideThreeToFiveHundredIsRejected() {
        assertEquals("VISIT_RADIUS_INVALID", app.update(AUTHOR, NODE, "GPS", 600).get("errorCode"));
        assertEquals("VISIT_RADIUS_INVALID", app.update(AUTHOR, NODE, "GPS", 100).get("errorCode"));
        verify(mapper, never()).updateVisitConfig(anyLong(), any(), any());
    }

    @Test
    void scanAndOnlineClearTheRadius() {
        assertEquals(200, app.update(AUTHOR, NODE, "SCAN", 400).get("code"));
        verify(mapper).updateVisitConfig(NODE, 1, null);
        assertEquals(200, app.update(AUTHOR, NODE, "online", null).get("code"));
        verify(mapper).updateVisitConfig(NODE, 2, null);
    }

    @Test
    void gpsNeedsCoordinates() {
        node.setLatitude(null);
        assertEquals("VISIT_GPS_COORD_REQUIRED", app.update(AUTHOR, NODE, "GPS", null).get("errorCode"));
        assertEquals(200, app.update(AUTHOR, NODE, "SCAN", null).get("code"), "没坐标可以改为扫码");
    }

    @Test
    void unknownMethodIsRejected() {
        assertEquals("VISIT_CHECK_INVALID", app.update(AUTHOR, NODE, "BLUETOOTH", null).get("errorCode"));
    }

    @Test
    void onlyTheTopicAuthorMayReadOrWrite() {
        assertEquals(403, app.update(99L, NODE, "SCAN", null).get("code"));
        assertEquals(403, app.list(99L, TOPIC).get("code"));
        assertEquals(401, app.list(null, TOPIC).get("code"));
        verify(mapper, never()).updateVisitConfig(anyLong(), any(), any());
    }

    @Test
    void cityRoutesHaveNoVisitConfig() {
        topic.setProductType(1);
        assertEquals("NOT_FREE_EXPLORE", app.update(AUTHOR, NODE, "SCAN", null).get("errorCode"));
    }
}

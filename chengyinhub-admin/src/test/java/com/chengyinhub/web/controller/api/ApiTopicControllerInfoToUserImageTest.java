package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.TopicChapterVO;
import com.chengyinhub.business.domain.vo.TopicNodeVO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** F07:/api/topic/info-to-user 购买前只给城市定向第一站的站图;自由探索不遮;承接方看得到自己那一站。 */
class ApiTopicControllerInfoToUserImageTest {

    private static TopicNodeVO node(long id, long sort) {
        TopicNodeVO vo = new TopicNodeVO();
        vo.setId(id);
        vo.setSortId(sort);
        vo.setImgUrl("/img-" + id + ".jpg");
        return vo;
    }

    private static List<TopicChapterVO> route() {
        TopicChapterVO c1 = new TopicChapterVO();
        c1.setNodes(Arrays.asList(node(11L, 2L), node(10L, 1L)));
        TopicChapterVO c2 = new TopicChapterVO();
        c2.setNodes(Collections.singletonList(node(12L, 3L)));
        return Arrays.asList(c1, c2);
    }

    @Test
    void anonymousSeesOnlyTheFirstStationPhoto() {
        List<TopicChapterVO> chapters = route();
        ApiTopicController.hideLaterStationImages(chapters, 1, Collections.<Long>emptySet());
        assertEquals("/img-10.jpg", chapters.get(0).getNodes().get(1).getImgUrl());
        assertNull(chapters.get(0).getNodes().get(1).getImgHidden());
        assertNull(chapters.get(0).getNodes().get(0).getImgUrl());
        assertEquals(Boolean.TRUE, chapters.get(0).getNodes().get(0).getImgHidden());
        assertNull(chapters.get(1).getNodes().get(0).getImgUrl());
    }

    @Test
    void fulfillingMerchantSeesOwnStation() {
        List<TopicChapterVO> chapters = route();
        ApiTopicController.hideLaterStationImages(chapters, 1, Collections.singleton(12L));
        assertEquals("/img-12.jpg", chapters.get(1).getNodes().get(0).getImgUrl());
    }

    @Test
    void freeExploreIsNeverHidden() {
        List<TopicChapterVO> chapters = route();
        ApiTopicController.hideLaterStationImages(chapters, 2, Collections.<Long>emptySet());
        assertEquals("/img-11.jpg", chapters.get(0).getNodes().get(0).getImgUrl());
        assertEquals("/img-12.jpg", chapters.get(1).getNodes().get(0).getImgUrl());
    }
}

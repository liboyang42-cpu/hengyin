package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 节点名额只数地点节点(2026-09-23 用户裁决:故事玩法不算节点名额)。
 *
 * <p>故事玩法 = 章节块里引用它的 node 块 {@code locationRequired=false} 且节点没有坐标
 * (与游玩页 _isStoryGame 同一判据)。带坐标的节点即使块上写了 false 仍按地点算 ——
 * 否则改一个布尔就能绕过商家的节点名额。</p>
 */
class ApiTopicNodeQuotaCountTest {

    @Test
    void storyGamesDoNotCountTowardNodeQuota() {
        ChapterDTO chapter = chapter(
                Arrays.asList(node(null, null), node(null, null), node("31.23", "121.47")),
                Arrays.asList(nodeBlock(0, false), nodeBlock(1, false), nodeBlock(2, null)));
        assertEquals(1, ApiTopicController.countNodes(topic(chapter)), "两个故事玩法不占名额,只数那个地点");
    }

    @Test
    void nodeWithCoordinatesStillCountsEvenIfBlockSaysNoLocation() {
        ChapterDTO chapter = chapter(Arrays.asList(node("31.23", "121.47")),
                Arrays.asList(nodeBlock(0, false)));
        assertEquals(1, ApiTopicController.countNodes(topic(chapter)), "有坐标就是地点,改块上的布尔绕不过名额");
    }

    @Test
    void nodeNotReferencedByAnyBlockCountsAsPlace() {
        ChapterDTO chapter = chapter(Arrays.asList(node(null, null)), new ArrayList<ChapterBlockDTO>());
        assertEquals(1, ApiTopicController.countNodes(topic(chapter)), "老数据没有块流:照旧按节点数算");
    }

    private static TopicCreateDTO topic(ChapterDTO... chapters) {
        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setChapters(Arrays.asList(chapters));
        return dto;
    }

    private static ChapterDTO chapter(List<NodeDTO> nodes, List<ChapterBlockDTO> blocks) {
        ChapterDTO c = new ChapterDTO();
        c.setNodes(nodes);
        c.setBlocks(blocks);
        return c;
    }

    private static NodeDTO node(String lat, String lng) {
        NodeDTO n = new NodeDTO();
        n.setLatitude(lat);
        n.setLongitude(lng);
        return n;
    }

    private static ChapterBlockDTO nodeBlock(int index, Boolean locationRequired) {
        ChapterBlockDTO b = new ChapterBlockDTO();
        b.setType("node");
        b.setNodeIndex(index);
        b.setLocationRequired(locationRequired);
        return b;
    }
}

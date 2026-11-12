package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨城采用后的「草稿可存 / 发布由地点缺口拒」分流。
 *
 * <p>NEW_CITY 会清空节点地址与坐标。这不能把作者卡在草稿保存上（那是「还没到现场」的正常状态），
 * 也不能在发布时以空指针或「坐标缺失」这种与地点合同无关的错冒出来。
 * 判据是：草稿分支允许空坐标；发布分支明确拒绝；缺口由地点合同以 error 级别表达，发布检查据此归因。
 *
 * <p>负例：把 {@code requireConfirmed=false}（草稿）也要求坐标 → 第一个用例红。
 */
class TopicLocationDraftPublishSplitTest {

    private static ChapterDTO chapterWithBlankCoordinates() {
        NodeDTO node = new NodeDTO();
        node.setName("新城待绑定地点");
        node.setAddress(null);
        node.setLatitude(null);
        node.setLongitude(null);
        ChapterDTO chapter = new ChapterDTO();
        chapter.setNodes(Collections.singletonList(node));
        return chapter;
    }

    @Test
    void draftSaveToleratesClearedCityFacts() {
        TopicPublicationContentGuard guard = new TopicPublicationContentGuard();
        List<ChapterDTO> chapters = Collections.singletonList(chapterWithBlankCoordinates());
        assertDoesNotThrow(() -> guard.assertAiSimpleNodeLocations(chapters, false),
                "跨城后地点尚未重绑是合法草稿状态，草稿保存不得要求坐标");
    }

    @Test
    void publishStillRejectsUnconfirmedCoordinates() {
        TopicPublicationContentGuard guard = new TopicPublicationContentGuard();
        List<ChapterDTO> chapters = Collections.singletonList(chapterWithBlankCoordinates());
        assertThrows(IllegalArgumentException.class,
                () -> guard.assertAiSimpleNodeLocations(chapters, true),
                "发布必须要求地点已确认");
    }

    @Test
    void locationContractAttributesThePublishGapInsteadOfANullPointer() {
        // 跨城复制清空坐标后，缺口只由合同表达：publish-check 消费 error 级问题即可，
        // 不依赖节点坐标字段是否被谁读了又没判空。
        CmsTopic source = new CmsTopic();
        source.setId(100L);
        source.setCityCode("310100");
        source.setConfigVersion(4L);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(11L);
        node.setTopicId(100L);
        node.setName("旧城观景台");
        node.setAddress("上海市黄浦区");

        String contract = TopicLocationContract.buildAdoptionContract(source,
                Collections.singletonList(node), Collections.singletonMap(11L, 111L),
                TopicLocationContract.INTENT_NEW_CITY, "320100", new Date());

        List<Map<String, Object>> issues = TopicLocationContract.bindingIssues(contract, "320100");
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(i -> "error".equals(i.get("level"))
                        && TopicLocationContract.ISSUE_REBIND.equals(i.get("code"))),
                "跨城未重绑必须是 error 级地点缺口，供发布检查直接归因");
        assertTrue(TopicLocationContract.parse(contract).path("locationSlots").get(0)
                .path("sourceNodeId").asText().equals("11"), "重绑清单仍指向原节点");
    }

    @Test
    void legacyAdoptionNeverProducesABlockingGap() {
        CmsTopic source = new CmsTopic();
        source.setId(100L);
        source.setCityCode("310100");
        CmsTopicNode node = new CmsTopicNode();
        node.setId(11L);
        node.setTopicId(100L);
        node.setAddress("上海市黄浦区");

        String contract = TopicLocationContract.buildAdoptionContract(source,
                Collections.singletonList(node), Collections.singletonMap(11L, 111L),
                TopicLocationContract.INTENT_LEGACY, null, new Date());

        for (Map<String, Object> issue : TopicLocationContract.bindingIssues(contract, "310100")) {
            assertTrue("warn".equals(issue.get("level")),
                    "旧版采用（旧 /use 不带参数）即使有未复核项也只能提示，不能倒着拦存量副本");
        }
    }
}

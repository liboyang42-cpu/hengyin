package com.chengyinhub.business.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.vo.TopicChapterVO;
import com.chengyinhub.business.domain.vo.TopicNodeVO;
import com.chengyinhub.common.utils.bean.BeanUtils;

/** 章节地点数/玩法数按随章下发的节点现算，不读整题保存时落库的过期列。 */
class TopicChapterVOCountTest {

    private static TopicNodeVO node(Long templateId) {
        TopicNodeVO node = new TopicNodeVO();
        node.setTemplateId(templateId);
        return node;
    }

    @Test
    void chapterCountsFollowReturnedNodesInsteadOfStaleStoredColumns() {
        CmsTopicChapter stored = new CmsTopicChapter();
        stored.setId(5L);
        stored.setLocationCount(7L);
        stored.setTemplateCount(4L);

        TopicChapterVO chapter = new TopicChapterVO();
        BeanUtils.copyBeanProp(chapter, stored);
        chapter.setNodes(Arrays.asList(node(11L), node(null), node(0L)));

        assertEquals(Long.valueOf(3L), chapter.getLocationCount());
        assertEquals(Long.valueOf(1L), chapter.getTemplateCount());
    }

    @Test
    void emptyNodeListReportsZeroInsteadOfStoredCount() {
        CmsTopicChapter stored = new CmsTopicChapter();
        stored.setLocationCount(3L);
        stored.setTemplateCount(2L);

        TopicChapterVO chapter = new TopicChapterVO();
        BeanUtils.copyBeanProp(chapter, stored);
        chapter.setNodes(java.util.Collections.<TopicNodeVO>emptyList());

        assertEquals(Long.valueOf(0L), chapter.getLocationCount());
        assertEquals(Long.valueOf(0L), chapter.getTemplateCount());
    }
}

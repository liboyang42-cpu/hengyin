package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.util.JourneyReviewTexts;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R12:审核详情的主题文本 = 主题级剧情配置 + 各启用节点挂的模板叙事段(带节点名)。
 */
class JourneyReviewTextServiceTest {

    @Test
    void topicTextsIncludeStoryAndEnabledNodeTemplateNarratives() {
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        JourneyReviewTextService service = new JourneyReviewTextService(nodeMapper, templateMapper,
                mock(CmsTopicChapterMapper.class));

        CmsTopic topic = new CmsTopic();
        topic.setId(20L);
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"call\":{\"npcId\":1,\"opener\":\"开场白\"}}");
        CmsTopicNode node = new CmsTopicNode();
        node.setId(5L);
        node.setName("阿荣的摊");
        node.setTemplateId(9L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node));
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(9L);
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"enter\":{\"npcId\":1,\"aside\":\"旁白\"}}");
        when(templateMapper.selectCmsMemberTemplateById(9L)).thenReturn(template);

        List<JourneyReviewTexts.Entry> entries = service.topicTexts(topic);

        ArgumentCaptor<CmsTopicNode> probe = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(nodeMapper).selectCmsTopicNodeList(probe.capture());
        assertEquals(20L, probe.getValue().getTopicId());
        assertEquals(Long.valueOf(1L), probe.getValue().getStatus(), "只看启用节点");
        assertEquals(2, entries.size(), entries.toString());
        assertEquals("journeyStory.call.opener", entries.get(0).getField());
        assertTrue(entries.get(1).getField().contains("节点「阿荣的摊」"), entries.get(1).getField());
        assertTrue(entries.get(1).getField().contains("blockConfig.enter.aside"), entries.get(1).getField());
        assertEquals("旁白", entries.get(1).getText());
    }

    @Test
    void templateTextsCollectBlockNarrative() {
        JourneyReviewTextService service = new JourneyReviewTextService(
                mock(CmsTopicNodeMapper.class), mock(CmsMemberTemplateMapper.class),
                mock(CmsTopicChapterMapper.class));
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"unlock\":{\"hook\":\"引子\"}}");

        List<JourneyReviewTexts.Entry> entries = service.templateTexts(template);

        assertEquals(1, entries.size());
        assertEquals("blockConfig.unlock.hook", entries.get(0).getField());
        assertEquals("引子", entries.get(0).getText());
    }

    /**
     * R19:叙事搬进章节故事流后,审核详情必须看到 beat 块正文(带章序号与章名)——
     * 审核员不必自己解 blocks JSON。
     *
     * <p>负控:删掉 topicTexts 里的 entries.addAll(chapterTexts(...)),本用例立刻红。
     */
    @Test
    void topicTextsIncludeChapterStoryFlowBeatTexts() {
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        JourneyReviewTextService service = new JourneyReviewTextService(nodeMapper,
                mock(CmsMemberTemplateMapper.class), chapterMapper);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.<CmsTopicNode>emptyList());
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(7L);
        chapter.setTopicId(20L);
        chapter.setName("消失的第七码头");
        chapter.setBlocksJson("[{\"type\":\"text\",\"content\":\"章节旁白\"},"
                + "{\"type\":\"node\",\"nodeId\":5},"
                + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"opener\",\"nodeId\":5,"
                + "\"content\":\"到店开场\"},"
                + "{\"type\":\"text\",\"beat\":\"enter\",\"field\":\"q\",\"qid\":\"night\",\"nodeId\":5,"
                + "\"content\":\"账本上写着什么\"}]");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter));

        CmsTopic topic = new CmsTopic();
        topic.setId(20L);
        List<JourneyReviewTexts.Entry> entries = service.topicTexts(topic);

        // 需求变更(F-L5C-03):非 beat 的章节旁白同样是玩家直接读到的文字,审核详情一并列出。
        assertEquals(3, entries.size(), entries.toString());
        assertEquals("第1章「消失的第七码头」 storyFlow.chapter1.text.content", entries.get(0).getField());
        assertEquals("章节旁白", entries.get(0).getText());
        assertEquals("第1章「消失的第七码头」 storyFlow.chapter1.enter.opener", entries.get(1).getField());
        assertEquals("到店开场", entries.get(1).getText());
        assertEquals("第1章「消失的第七码头」 storyFlow.chapter1.enter.q(night)", entries.get(2).getField());
        assertEquals("账本上写着什么", entries.get(2).getText());
    }
}

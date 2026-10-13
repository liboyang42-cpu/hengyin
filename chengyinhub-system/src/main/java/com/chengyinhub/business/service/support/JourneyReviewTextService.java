package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.util.JourneyReviewTexts;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台审核详情的「可读叙事文本」单一入口(R12)。
 *
 * <p>只读:主题 → 主题级剧情配置 + 章节故事流 beat 块(R19)+ 各启用节点挂的模板叙事段;
 * 模板 → 自身块配置。
 * 文本抽取规则在 {@link JourneyReviewTexts};本类只负责把行挂起来,不动任何状态。
 */
@Service
public class JourneyReviewTextService {

    private final CmsTopicNodeMapper cmsTopicNodeMapper;
    private final CmsMemberTemplateMapper cmsMemberTemplateMapper;
    private final CmsTopicChapterMapper cmsTopicChapterMapper;

    public JourneyReviewTextService(CmsTopicNodeMapper cmsTopicNodeMapper,
                                    CmsMemberTemplateMapper cmsMemberTemplateMapper,
                                    CmsTopicChapterMapper cmsTopicChapterMapper) {
        this.cmsTopicNodeMapper = cmsTopicNodeMapper;
        this.cmsMemberTemplateMapper = cmsMemberTemplateMapper;
        this.cmsTopicChapterMapper = cmsTopicChapterMapper;
    }

    /** 主题级剧情配置 + 章节故事流 + 全部启用节点块配置的可读文本;文本带章节 / 节点名,便于审核员定位。 */
    public List<JourneyReviewTexts.Entry> topicTexts(CmsTopic topic) {
        List<JourneyReviewTexts.Entry> entries = new ArrayList<JourneyReviewTexts.Entry>();
        if (topic == null) {
            return entries;
        }
        entries.addAll(JourneyReviewTexts.collectJourneyStory(topic.getJourneyStoryJson()));
        entries.addAll(chapterTexts(topic.getId()));
        for (CmsTopicNode node : enabledNodes(topic.getId())) {
            Long templateId = node.getTemplateId();
            if (templateId == null || templateId <= 0L) {
                continue;
            }
            CmsMemberTemplate template = cmsMemberTemplateMapper.selectCmsMemberTemplateById(templateId);
            if (template == null) {
                continue;
            }
            entries.addAll(prefixed(nodeLabel(node), JourneyReviewTexts.collectBlocks(template.getAdvancedConfigJson())));
        }
        return entries;
    }

    /** 单个模板(送审的节点模板)块配置的可读文本。 */
    public List<JourneyReviewTexts.Entry> templateTexts(CmsMemberTemplate template) {
        if (template == null) {
            return new ArrayList<JourneyReviewTexts.Entry>();
        }
        return JourneyReviewTexts.collectBlocks(template.getAdvancedConfigJson());
    }

    /**
     * R19:章节故事流 beat 块的正文(叙事搬进故事流后的真源)。带章序号与章名,
     * 审核员不必自己解 blocks JSON;坏 blocks 由收集器按「没收到」跳过,不影响其余章节。
     */
    private List<JourneyReviewTexts.Entry> chapterTexts(Long topicId) {
        List<JourneyReviewTexts.Entry> entries = new ArrayList<JourneyReviewTexts.Entry>();
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topicId);
        List<CmsTopicChapter> chapters = cmsTopicChapterMapper.selectCmsTopicChapterList(probe);
        if (chapters == null) {
            return entries;
        }
        for (int index = 0; index < chapters.size(); index++) {
            CmsTopicChapter chapter = chapters.get(index);
            if (chapter == null) {
                continue;
            }
            entries.addAll(prefixed(chapterLabel(index + 1, chapter),
                    JourneyReviewTexts.collectChapterBlocks(chapter.getBlocksJson(), index + 1)));
        }
        return entries;
    }

    private static String chapterLabel(int chapterNumber, CmsTopicChapter chapter) {
        String name = chapter.getName();
        return StringUtils.isEmpty(name) ? "第" + chapterNumber + "章"
                : "第" + chapterNumber + "章「" + name + "」";
    }

    private List<CmsTopicNode> enabledNodes(Long topicId) {        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setStatus(1L);
        List<CmsTopicNode> nodes = cmsTopicNodeMapper.selectCmsTopicNodeList(probe);
        return nodes == null ? new ArrayList<CmsTopicNode>() : nodes;
    }

    private static List<JourneyReviewTexts.Entry> prefixed(String prefix,
                                                           List<JourneyReviewTexts.Entry> entries) {
        List<JourneyReviewTexts.Entry> prefixed = new ArrayList<JourneyReviewTexts.Entry>();
        for (JourneyReviewTexts.Entry entry : entries) {
            prefixed.add(new JourneyReviewTexts.Entry(prefix + " " + entry.getField(), entry.getText()));
        }
        return prefixed;
    }

    private static String nodeLabel(CmsTopicNode node) {
        return StringUtils.isEmpty(node.getName()) ? "节点#" + node.getId() : "节点「" + node.getName() + "」";
    }
}

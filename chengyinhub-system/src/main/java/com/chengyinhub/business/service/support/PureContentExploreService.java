package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.constant.ProductType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/** 零商家自由探索只认必选章的服务端玩法完成事实，不制造商家权益或核销。 */
@Service
public class PureContentExploreService {
    @Autowired private CmsTopicMapper topicMapper;
    @Autowired private CmsTopicChapterMapper chapterMapper;
    @Autowired private CmsTopicNodeMapper nodeMapper;
    @Autowired private MerchantChapterOfferMapper offerMapper;
    @Autowired private CmsRegistrationMapper registrationMapper;
    @Autowired private CmsActivityMapper activityMapper;
    @Autowired private PlayerNodeProgressMapper progressMapper;

    public boolean isPureContent(Long topicId) {
        CmsTopic topic = topicId == null ? null : topicMapper.selectCmsTopicById(topicId);
        if (topic == null || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())
                || (topic.getCompleteRuleJson() != null && !topic.getCompleteRuleJson().trim().isEmpty())) return false;
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topicId);
        List<CmsTopicChapter> chapters = chapterMapper.selectCmsTopicChapterList(probe);
        if (chapters == null || chapters.isEmpty()) return false;
        for (CmsTopicChapter chapter : chapters) {
            if (chapter == null || !Integer.valueOf(0).equals(chapter.getRecruitEnabled())
                    || "PERK".equals(chapter.getTermsMode())
                    || offerMapper.countAllByChapter(chapter.getId()) != 0) return false;
        }
        List<CmsTopicNode> nodes = nodes(topicId);
        if (nodes == null || nodes.isEmpty()) return false;
        for (CmsTopicNode node : nodes) {
            if (node.getMerchantMemberId() != null && node.getMerchantMemberId() > 0) return false;
        }
        List<CmsTopicChapter> required = requiredChapters(topicId);
        if (required.isEmpty()) return false;
        for (CmsTopicChapter chapter : required) {
            if (gameNodes(nodes, chapter.getId()).isEmpty()) return false;
        }
        return true;
    }

    public List<CmsTopicChapter> requiredChapters(Long topicId) {
        List<CmsTopicChapter> rows = chapterMapper.selectRequiredByTopic(topicId);
        return rows == null ? Collections.emptyList() : rows;
    }

    public boolean isCompleted(Long registrationId, Long topicId) {
        List<CmsTopicChapter> required = requiredChapters(topicId);
        return !required.isEmpty() && completedChapters(registrationId, topicId).size() == required.size();
    }

    public List<Map<String, Object>> completionFacts(Long topicId) {
        List<Map<String, Object>> rows = registrationMapper.selectPureContentExploreCompletion(topicId);
        return rows == null ? Collections.emptyList() : rows;
    }

    public Set<Long> completedChapters(Long registrationId, Long topicId) {
        Set<Long> completed = new LinkedHashSet<>();
        CmsRegistration registration = registrationId == null ? null
                : registrationMapper.selectCmsRegistrationById(registrationId);
        if (!isPureContent(topicId) || registration == null
                || !Integer.valueOf(3).equals(registration.getPurchaseKind())
                || !Integer.valueOf(2).equals(registration.getPaymentStatus())
                || !Integer.valueOf(2).equals(registration.getRegistrationStatus())
                || !Long.valueOf(2).equals(registration.getOwnerType())) return completed;
        CmsActivity activity = activityMapper.selectCmsActivityById(registration.getOwnerId());
        if (activity == null || !topicId.equals(activity.getTopicId())) return completed;
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(registration.getMemberId());
        probe.setActivityId(activity.getId());
        probe.setTopicId(topicId);
        Set<Long> done = new HashSet<>();
        List<PlayerNodeProgress> progress = progressMapper.selectPlayerNodeProgressList(probe);
        if (progress != null) for (PlayerNodeProgress row : progress) {
            if (Integer.valueOf(1).equals(row.getStatus())) done.add(row.getNodeId());
        }
        List<CmsTopicNode> nodes = nodes(topicId);
        for (CmsTopicChapter chapter : requiredChapters(topicId)) {
            Set<Long> games = gameNodes(nodes, chapter.getId());
            if (!games.isEmpty() && done.containsAll(games)) completed.add(chapter.getId());
        }
        return completed;
    }

    private List<CmsTopicNode> nodes(Long topicId) {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setStatus(1L);
        return nodeMapper.selectCmsTopicNodeList(probe);
    }

    private Set<Long> gameNodes(List<CmsTopicNode> nodes, Long chapterId) {
        Set<Long> games = new HashSet<>();
        if (nodes != null) for (CmsTopicNode node : nodes) {
            if (chapterId.equals(node.getChapterId()) && node.getId() != null
                    && node.getTemplateId() != null && node.getTemplateId() > 0) games.add(node.getId());
        }
        return games;
    }
}

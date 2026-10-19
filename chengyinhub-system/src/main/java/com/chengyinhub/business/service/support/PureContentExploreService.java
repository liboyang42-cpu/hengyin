package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.exception.ServiceException;
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
    @Autowired private TopicRouteSessionMapper routeSessionMapper;
    @Autowired private ITopicRouteRuntimeService routeRuntimeService;

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
        if (!isPureContent(topicId)) return Collections.emptyList();
        Map<Long, Integer> byMember = new LinkedHashMap<>();
        Map<String, TopicRouteSession> observed = new LinkedHashMap<>();
        Long after = null;
        final int pageSize = 100;
        while (true) {
            List<CmsRegistration> page = registrationMapper.selectPureContentExploreScopes(topicId, after, pageSize);
            if (page == null || page.size() > pageSize) throw unreadable();
            for (CmsRegistration row : page) {
                if (row == null || row.getId() == null || row.getId() <= 0
                        || (after != null && row.getId() <= after) || !validRegistration(row)) throw unreadable();
                after = row.getId();
                Completion result = completion(row, topicId, observed);
                if (result.participant) byMember.merge(row.getMemberId(), result.complete ? 1 : 0, Math::max);
            }
            if (page.size() < pageSize) break;
        }
        requireUnchanged(observed);
        List<Map<String, Object>> facts = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : byMember.entrySet()) {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("memberId", entry.getKey()); fact.put("completed", entry.getValue()); facts.add(fact);
        }
        return facts;
    }

    public Set<Long> completedChapters(Long registrationId, Long topicId) {
        CmsRegistration registration = registrationId == null ? null
                : registrationMapper.selectCmsRegistrationById(registrationId);
        if (!isPureContent(topicId) || !validRegistration(registration)) return Collections.emptySet();
        Map<String, TopicRouteSession> observed = new LinkedHashMap<>();
        Completion result = completion(registration, topicId, observed);
        requireUnchanged(observed);
        return result.chapters;
    }

    private boolean validRegistration(CmsRegistration row) {
        return row != null && row.getMemberId() != null && row.getMemberId() > 0
                && row.getOwnerId() != null && row.getOwnerId() > 0
                && Integer.valueOf(3).equals(row.getPurchaseKind())
                && Integer.valueOf(2).equals(row.getPaymentStatus())
                && Integer.valueOf(2).equals(row.getRegistrationStatus())
                && Long.valueOf(2).equals(row.getOwnerType());
    }

    private Completion completion(CmsRegistration registration, Long topicId,
                                  Map<String, TopicRouteSession> observed) {
        Completion result = new Completion();
        CmsActivity activity = activityMapper.selectCmsActivityById(registration.getOwnerId());
        if (activity == null || !registration.getOwnerId().equals(activity.getId()) || !topicId.equals(activity.getTopicId())
                || !Integer.valueOf(0).equals(activity.getDelFlag())) return result;
        if (routeRuntimeService == null || routeSessionMapper == null) throw unreadable();
        TopicRouteSession current = TopicRouteSessionScope.find(routeSessionMapper,
                registration.getMemberId(), topicId, activity.getId());
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        // Also observe absence. An explicit start racing this read must not silently change its scope.
        boolean unstartedTheme = current == null && ThemeRuntimeFeaturePolicy.isConfigured(
                topic == null ? null : topic.getPublishedThemeRuntimeConfigJson());
        String instance = unstartedTheme ? null
                : routeRuntimeService.playInstance(registration.getMemberId(), topicId, activity.getId());
        if (!unstartedTheme && (instance == null || instance.trim().isEmpty())) throw unreadable();
        TopicRouteSession scope = new TopicRouteSession();
        scope.setMemberId(registration.getMemberId()); scope.setTopicId(topicId);
        scope.setActivityId(activity.getId()); scope.setPlayInstanceId(instance);
        String key = ITopicRouteRuntimeService.sessionKey(scope.getMemberId(), topicId, activity.getId());
        TopicRouteSession earlier = observed.putIfAbsent(key, scope);
        if (earlier != null && !java.util.Objects.equals(instance, earlier.getPlayInstanceId())) throw changed();
        if (unstartedTheme) return result;
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(registration.getMemberId());
        probe.setActivityId(activity.getId());
        probe.setTopicId(topicId);
        Set<Long> done = new HashSet<>();
        List<PlayerNodeProgress> progress = progressMapper.selectPlayerNodeProgressListForInstance(probe, instance);
        if (progress == null) throw unreadable();
        for (PlayerNodeProgress row : progress) {
            if (row == null || !probe.getMemberId().equals(row.getMemberId())
                    || !topicId.equals(row.getTopicId()) || !activity.getId().equals(row.getActivityId())
                    || !instance.equals(row.getPlayInstanceId()) || !Integer.valueOf(0).equals(row.getDelFlag())) throw unreadable();
            if (Integer.valueOf(1).equals(row.getStatus())) done.add(row.getNodeId());
        }
        result.participant = !done.isEmpty();
        List<CmsTopicNode> nodes = nodes(topicId);
        List<CmsTopicChapter> required = requiredChapters(topicId);
        for (CmsTopicChapter chapter : required) {
            Set<Long> games = gameNodes(nodes, chapter.getId());
            if (!games.isEmpty() && done.containsAll(games)) result.chapters.add(chapter.getId());
        }
        result.complete = !required.isEmpty() && result.chapters.size() == required.size();
        return result;
    }

    private void requireUnchanged(Map<String, TopicRouteSession> observed) {
        for (TopicRouteSession scope : observed.values()) {
            CmsActivity activity = activityMapper.selectCmsActivityById(scope.getActivityId());
            if (activity == null || !scope.getActivityId().equals(activity.getId())
                    || !scope.getTopicId().equals(activity.getTopicId())
                    || !Integer.valueOf(0).equals(activity.getDelFlag())) throw changed();
            if (scope.getPlayInstanceId() == null) {
                CmsTopic topic = topicMapper.selectCmsTopicById(scope.getTopicId());
                if (TopicRouteSessionScope.find(routeSessionMapper, scope.getMemberId(), scope.getTopicId(),
                        scope.getActivityId()) != null || !ThemeRuntimeFeaturePolicy.isConfigured(
                        topic == null ? null : topic.getPublishedThemeRuntimeConfigJson())) throw changed();
            } else if (!scope.getPlayInstanceId().equals(routeRuntimeService.playInstance(
                    scope.getMemberId(), scope.getTopicId(), scope.getActivityId()))) throw changed();
        }
    }

    private static final class Completion {
        final Set<Long> chapters = new LinkedHashSet<>();
        boolean participant;
        boolean complete;
    }

    private ServiceException unreadable() { return new ServiceException("探索完成事实不可读取"); }
    private ServiceException changed() { return new ServiceException("本局已更新，请刷新", 409); }

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

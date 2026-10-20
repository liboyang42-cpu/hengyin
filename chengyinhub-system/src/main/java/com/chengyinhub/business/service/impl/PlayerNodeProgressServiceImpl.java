package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.exception.ServiceException;

/**
 * 玩家节点打卡进度 Service 实现
 *
 * @author cy
 * @date 2026-06-19
 */
@Service
public class PlayerNodeProgressServiceImpl implements IPlayerNodeProgressService
{
    private static final int RESOLVED_SCOPE_BATCH_SIZE = 100;
    @Autowired
    private PlayerNodeProgressMapper playerNodeProgressMapper;

    @Autowired(required = false)
    private com.chengyinhub.business.mapper.TopicRouteSessionMapper routeSessions;

    @Autowired(required = false)
    private com.chengyinhub.business.service.support.ThemeRuntimeFeaturePolicy themeRuntimePolicy =
            new com.chengyinhub.business.service.support.ThemeRuntimeFeaturePolicy();

    /** Required in production; manually constructed legacy fixtures may omit the runtime. */
    @Autowired
    private ITopicRouteRuntimeService routeRuntimeService;

    @Autowired
    private CmsActivityMapper cmsActivityMapper;

    private boolean hasScope(PlayerNodeProgress p) {
        return p.getMemberId() != null && p.getTopicId() != null && p.getActivityId() != null;
    }

    /** The request may assert a token, but only the guarded runtime chooses the instance. */
    private String instance(PlayerNodeProgress p, boolean write) {
        String expected = "default";
        if (hasScope(p)) {
            if (routeRuntimeService != null) {
                expected = routeRuntimeService.playInstance(p.getMemberId(), p.getTopicId(), p.getActivityId());
                if (expected == null || expected.trim().isEmpty()) {
                    throw new ServiceException("本局不可读取，请刷新", 409);
                }
            } else {
                // Compatibility for manually constructed old services, never an alternate theme path.
                com.chengyinhub.business.domain.TopicRouteSession current = routeSessions == null ? null
                        : com.chengyinhub.business.service.support.TopicRouteSessionScope.find(routeSessions,
                            p.getMemberId(), p.getTopicId(), p.getActivityId());
                themeRuntimePolicy.requireSessionEnabled(current);
                if (com.chengyinhub.business.service.support.ThemeRuntimeFeaturePolicy.hasFrozenTheme(current)
                        || (current != null && !"default".equals(current.getPlayInstanceId()))) {
                    throw new ServiceException("主题运行时不可用");
                }
            }
        }
        if (p.getPlayInstanceId() != null && !expected.equals(p.getPlayInstanceId())) {
            throw new ServiceException("本局已更新，请刷新", 409);
        }
        if (write && hasScope(p) && routeRuntimeService != null) {
            // This lock is held by the surrounding transaction through the progress write.
            boolean theme = routeRuntimeService.assertCurrentPlayInstance(p.getMemberId(), p.getTopicId(),
                    p.getActivityId(), expected);
            if (!"default".equals(expected) && !theme) {
                throw new ServiceException("本局不可读取，请刷新", 409);
            }
        }
        p.setPlayInstanceId(expected);
        return expected;
    }

    @Override
    public List<PlayerNodeProgress> selectPlayerNodeProgressList(PlayerNodeProgress playerNodeProgress)
    {
        if (playerNodeProgress.getMemberId() != null && !hasScope(playerNodeProgress)) {
            return selectMemberCurrentProgress(playerNodeProgress);
        }
        String current = instance(playerNodeProgress, false);
        if (hasScope(playerNodeProgress)) {
            return playerNodeProgressMapper.selectPlayerNodeProgressListForInstance(playerNodeProgress, current);
        }
        return playerNodeProgressMapper.selectPlayerNodeProgressList(playerNodeProgress);
    }

    /** 跨场次本人列表只枚举归属；每个三元组仍单独通过同一个受保护的运行时选局。 */
    private List<PlayerNodeProgress> selectMemberCurrentProgress(PlayerNodeProgress probe) {
        if (probe.getMemberId() <= 0 || probe.getPlayInstanceId() != null) {
            throw new ServiceException("场次参数有误");
        }
        List<PlayerNodeProgress> scopes = resolveProgressScopes(probe);
        List<PlayerNodeProgress> rows = new ArrayList<>();
        for (int start = 0; start < scopes.size(); start += RESOLVED_SCOPE_BATCH_SIZE) {
            List<PlayerNodeProgress> batch = scopes.subList(start, Math.min(start + RESOLVED_SCOPE_BATCH_SIZE, scopes.size()));
            List<PlayerNodeProgress> found = playerNodeProgressMapper.selectProgressForResolvedScopes(batch, probe.getNodeId());
            if (found == null) throw new ServiceException("打卡进度不可读取");
            Map<String, String> expected = instanceIndex(batch);
            for (PlayerNodeProgress row : found) {
                if (row == null || !Integer.valueOf(0).equals(row.getDelFlag())
                        || (probe.getNodeId() != null && !probe.getNodeId().equals(row.getNodeId()))) {
                    throw new ServiceException("打卡进度不可读取");
                }
                requireResolvedRow(expected, row.getMemberId(), row.getTopicId(), row.getActivityId(), row.getPlayInstanceId());
            }
            rows.addAll(found);
        }
        requireUnchangedInstances(scopes);
        return rows;
    }

    private List<PlayerNodeProgress> resolveProgressScopes(PlayerNodeProgress probe) {
        if (routeRuntimeService == null) throw new ServiceException("路线运行时不可用");
        List<PlayerNodeProgress> candidates = playerNodeProgressMapper.selectProgressScopes(probe);
        if (candidates == null) throw new ServiceException("打卡场次不可读取");
        Map<String, PlayerNodeProgress> unique = new LinkedHashMap<>();
        for (PlayerNodeProgress scope : candidates) {
            if (scope == null || !hasScope(scope) || scope.getMemberId() <= 0 || scope.getTopicId() <= 0
                    || scope.getActivityId() < 0
                    || (probe.getMemberId() != null && !Objects.equals(probe.getMemberId(), scope.getMemberId()))
                    || (probe.getTopicId() != null && !Objects.equals(probe.getTopicId(), scope.getTopicId()))
                    || (probe.getActivityId() != null && !Objects.equals(probe.getActivityId(), scope.getActivityId()))) {
                throw new ServiceException("打卡场次参数有误");
            }
            PlayerNodeProgress owner = new PlayerNodeProgress();
            owner.setMemberId(scope.getMemberId());
            owner.setTopicId(scope.getTopicId());
            owner.setActivityId(scope.getActivityId());
            unique.put(ITopicRouteRuntimeService.sessionKey(owner.getMemberId(), owner.getTopicId(), owner.getActivityId()), owner);
        }
        List<PlayerNodeProgress> scopes = new ArrayList<>(unique.values());
        Map<String, String> instances = routeRuntimeService.playInstances(routeScopes(scopes));
        for (PlayerNodeProgress scope : scopes) {
            scope.setPlayInstanceId(requiredInstance(instances, scope));
        }
        return scopes;
    }

    private Map<String, String> instanceIndex(List<PlayerNodeProgress> scopes) {
        Map<String, String> result = new LinkedHashMap<>();
        for (PlayerNodeProgress scope : scopes) {
            result.put(ITopicRouteRuntimeService.sessionKey(scope.getMemberId(), scope.getTopicId(), scope.getActivityId()), scope.getPlayInstanceId());
        }
        return result;
    }

    private void requireResolvedRow(Map<String, String> expected, Long memberId, Long topicId, Long activityId, String instance) {
        if (memberId == null || topicId == null || activityId == null || instance == null
                || !instance.equals(expected.get(ITopicRouteRuntimeService.sessionKey(memberId, topicId, activityId)))) {
            throw new ServiceException("无权访问这一局", 403);
        }
    }

    private List<TopicRouteSession> routeScopes(List<PlayerNodeProgress> scopes) {
        List<TopicRouteSession> result = new ArrayList<>();
        for (PlayerNodeProgress scope : scopes) {
            TopicRouteSession owner = new TopicRouteSession();
            owner.setMemberId(scope.getMemberId());
            owner.setTopicId(scope.getTopicId());
            owner.setActivityId(scope.getActivityId());
            result.add(owner);
        }
        return result;
    }

    private String requiredInstance(Map<String, String> instances, PlayerNodeProgress scope) {
        String current = instances == null ? null : instances.get(ITopicRouteRuntimeService.sessionKey(
                scope.getMemberId(), scope.getTopicId(), scope.getActivityId()));
        if (current == null || current.trim().isEmpty()) throw new ServiceException("本局不可读取，请刷新", 409);
        return current;
    }

    /** A replay during the read invalidates the whole result; never combine old progress and new status. */
    private void requireUnchangedInstances(List<PlayerNodeProgress> scopes) {
        Map<String, String> current = routeRuntimeService.playInstances(routeScopes(scopes));
        for (PlayerNodeProgress scope : scopes) {
            if (!scope.getPlayInstanceId().equals(requiredInstance(current, scope))) {
                throw new ServiceException("本局已更新，请刷新", 409);
            }
        }
    }

    @Override
    public PlayerNodeProgress selectPlayerNodeProgressById(Long id)
    {
        return playerNodeProgressMapper.selectPlayerNodeProgressById(id);
    }

    @Override
    public Long countPlayersByTopicId(Long topicId)
    {
        return playerNodeProgressMapper.countPlayersByTopicId(topicId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public int insertPlayerNodeProgress(PlayerNodeProgress playerNodeProgress)
    {
        instance(playerNodeProgress, true);
        return playerNodeProgressMapper.insertPlayerNodeProgress(playerNodeProgress);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public int markPhotoMachineApproved(Long id)
    {
        PlayerNodeProgress row = playerNodeProgressMapper.selectPlayerNodeProgressById(id);
        if (row == null) return 0;
        if (!hasScope(row)) throw new ServiceException("打卡场次参数有误");
        String current = instance(row, true);
        return playerNodeProgressMapper.markPhotoMachineApprovedForInstance(id, row.getMemberId(),
                row.getActivityId(), row.getTopicId(), current);
    }

    @Override
    public Integer selectPuzzlePersonalBest(Long memberId, Long topicId)
    {
        return playerNodeProgressMapper.selectPuzzlePersonalBest(memberId, topicId);
    }

    @Override
    public List<LeaderboardItemVO> selectActivityLeaderboard(Long activityId, Long topicId, int limit)
    {
        if (topicId == null && activityId != null && activityId > 0) {
            // Public callers already supply the verified topic; standalone callers must resolve it too.
            if (routeRuntimeService == null) {
                return playerNodeProgressMapper.selectActivityLeaderboard(activityId, null, limit);
            }
            CmsActivity activity = cmsActivityMapper == null ? null : cmsActivityMapper.selectCmsActivityById(activityId);
            if (activity == null || activity.getTopicId() == null) throw new ServiceException("活动不存在");
            topicId = activity.getTopicId();
        }
        if (topicId == null || topicId <= 0 || (activityId != null && activityId < 0)) {
            throw new ServiceException("场次参数有误");
        }
        if (limit <= 0) return new ArrayList<>();
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setTopicId(topicId);
        probe.setActivityId(activityId == null ? 0L : activityId);
        List<PlayerNodeProgress> scopes = resolveProgressScopes(probe);
        List<PlayerNodeProgressMapper.LeaderboardScopeScore> scores = new ArrayList<>();
        Set<Long> seenMembers = new HashSet<>();
        for (int start = 0; start < scopes.size(); start += RESOLVED_SCOPE_BATCH_SIZE) {
            List<PlayerNodeProgress> batch = scopes.subList(start, Math.min(start + RESOLVED_SCOPE_BATCH_SIZE, scopes.size()));
            List<PlayerNodeProgressMapper.LeaderboardScopeScore> found = playerNodeProgressMapper.selectLeaderboardForResolvedScopes(
                    probe.getActivityId(), topicId, batch);
            if (found == null) throw new ServiceException("排行榜不可读取");
            Map<String, String> expected = instanceIndex(batch);
            for (PlayerNodeProgressMapper.LeaderboardScopeScore row : found) {
                if (row == null || row.getScore() == null || row.getScore() <= 0 || !seenMembers.add(row.getMemberId())) {
                    throw new ServiceException("排行榜不可读取");
                }
                requireResolvedRow(expected, row.getMemberId(), row.getTopicId(), row.getActivityId(), row.getPlayInstanceId());
            }
            scores.addAll(found);
        }
        requireUnchangedInstances(scopes);
        // Match MySQL's prior ORDER BY, including NULL first for an ascending completion timestamp.
        scores.sort(Comparator.comparing(PlayerNodeProgressMapper.LeaderboardScopeScore::getScore,
                    Comparator.reverseOrder())
                .thenComparing(PlayerNodeProgressMapper.LeaderboardScopeScore::getLastCheckinTime,
                    Comparator.nullsFirst(Comparator.<Date>naturalOrder()))
                .thenComparing(PlayerNodeProgressMapper.LeaderboardScopeScore::getMemberId));
        List<LeaderboardItemVO> result = new ArrayList<>();
        for (PlayerNodeProgressMapper.LeaderboardScopeScore score : scores.subList(0, Math.min(limit, scores.size()))) {
            // Do not expose the internal tie-break field or progress details in the public DTO.
            LeaderboardItemVO row = new LeaderboardItemVO();
            row.setMemberId(score.getMemberId());
            row.setNickname(score.getNickname());
            row.setAvatar(score.getAvatar());
            row.setScore(score.getScore());
            result.add(row);
        }
        return result;
    }
}

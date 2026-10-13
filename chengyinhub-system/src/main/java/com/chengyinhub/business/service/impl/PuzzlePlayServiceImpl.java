package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.vo.PuzzleHintResult;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.business.util.PuzzleScorePolicy;
import com.chengyinhub.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PuzzlePlayServiceImpl implements IPuzzlePlayService
{
    private static final Logger log = LoggerFactory.getLogger(PuzzlePlayServiceImpl.class);

    private final ICmsMemberTemplateService templateService;
    private final PlayAttemptLogMapper attemptLogMapper;
    private final PlayerNodeProgressMapper progressMapper;
    private final UmsMemberMapper memberMapper;

    public PuzzlePlayServiceImpl(ICmsMemberTemplateService templateService, PlayAttemptLogMapper attemptLogMapper,
            PlayerNodeProgressMapper progressMapper, UmsMemberMapper memberMapper)
    {
        this.templateService = templateService;
        this.attemptLogMapper = attemptLogMapper;
        this.progressMapper = progressMapper;
        this.memberMapper = memberMapper;
    }

    @Override
    public CmsMemberTemplate selectPuzzleTemplate(CmsTopicNode node)
    {
        if (node == null || node.getTemplateId() == null) return null;
        CmsMemberTemplate template = templateService.selectCmsMemberTemplateById(node.getTemplateId());
        if (template == null || template.getValidationMethod() == null) return null;
        int validationMethod = template.getValidationMethod();
        return validationMethod == 1 || validationMethod == 3 ? template : null;
    }

    @Override
    public PuzzleHintResult requestHint(Long memberId, Long topicId, CmsTopicNode node, Long activityId, int level)
    {
        CmsMemberTemplate template = selectPuzzleTemplate(node);
        if (template == null) throw new IllegalArgumentException("该节点未配置文字或选项谜题");
        List<Integer> configuredLevels = new ArrayList<>();
        List<String> configuredHints = new ArrayList<>();
        if (StringUtils.isNotBlank(template.getHint1())) {
            configuredLevels.add(1);
            configuredHints.add(template.getHint1());
        }
        if (StringUtils.isNotBlank(template.getHint2())) {
            configuredLevels.add(2);
            configuredHints.add(template.getHint2());
        }
        if (level < 1 || level > configuredHints.size()) throw new IllegalArgumentException("该级提示未配置");

        lockMember(memberId);
        int currentLevel = readHintLevel(memberId, topicId, node.getId(), activityId, "提示状态暂时不可用,请重试");
        int shownCount = 0;
        for (Integer configuredLevel : configuredLevels) {
            if (configuredLevel <= currentLevel) shownCount++;
        }
        if (level > shownCount + 1) throw new IllegalArgumentException("请先查看上一条提示");
        int requestedLevel = level <= shownCount ? currentLevel : configuredLevels.get(level - 1);
        if (requestedLevel > currentLevel) {
            PlayAttemptLog attempt = new PlayAttemptLog();
            attempt.setMemberId(memberId);
            attempt.setTopicId(topicId);
            attempt.setNodeId(node.getId());
            attempt.setActivityId(activityId);
            attempt.setAttemptType(PlayAttemptLog.ATTEMPT_TYPE_PUZZLE_HINT);
            attempt.setResult(requestedLevel);
            attempt.setCreatedAt(new Date());
            try {
                if (attemptLogMapper.insertPlayAttemptLog(attempt) <= 0) {
                    throw new IllegalStateException("提示状态暂时不可用,请重试");
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                log.error("谜题提示记录失败 member={} topic={} node={} activity={} level={}",
                        memberId, topicId, node.getId(), activityId, requestedLevel, e);
                throw new IllegalStateException("提示状态暂时不可用,请重试");
            }
            currentLevel = requestedLevel;
        }
        List<String> shownHints = revealedHints(configuredLevels, configuredHints, currentLevel);
        return new PuzzleHintResult(currentLevel, shownHints, configuredHints.size(),
                PuzzleScorePolicy.solvedScore(currentLevel));
    }

    @Override
    public Map<Long, Integer> selectHintLevels(Long memberId, Long topicId, Long activityId)
    {
        Map<Long, Integer> levels = new HashMap<>();
        List<Map<String, Object>> rows = attemptLogMapper.selectPuzzleHintLevels(memberId, topicId, activityId);
        if (rows == null) return levels;
        for (Map<String, Object> row : rows) {
            Object nodeId = row.get("nodeId");
            Object hintLevel = row.get("hintLevel");
            if (nodeId instanceof Number && hintLevel instanceof Number) {
                levels.put(((Number) nodeId).longValue(), ((Number) hintLevel).intValue());
            }
        }
        return levels;
    }

    @Override
    public int solvedScore(Long memberId, Long topicId, Long nodeId, Long activityId)
    {
        lockMember(memberId);
        int hintLevel = readHintLevel(memberId, topicId, nodeId, activityId, "解谜分状态暂时不可用,请重试");
        return PuzzleScorePolicy.solvedScore(hintLevel);
    }

    private List<String> revealedHints(List<Integer> levels, List<String> hints, int currentLevel)
    {
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i) <= currentLevel) shown.add(hints.get(i));
        }
        return shown;
    }

    private void lockMember(Long memberId)
    {
        if (memberMapper.lockMemberById(memberId) == null) {
            throw new IllegalStateException("用户状态不可用,请重新登录");
        }
    }

    private int readHintLevel(Long memberId, Long topicId, Long nodeId, Long activityId, String userMessage)
    {
        try {
            return attemptLogMapper.selectMaxPuzzleHintLevel(memberId, topicId, nodeId, activityId);
        } catch (Exception e) {
            log.error("谜题提示状态读取失败 member={} topic={} node={} activity={}",
                    memberId, topicId, nodeId, activityId, e);
            throw new IllegalStateException(userMessage);
        }
    }

    @Override
    public String revealText(CmsMemberTemplate template)
    {
        if (template == null) return null;
        if (StringUtils.isNotBlank(template.getAnswerReveal())) return template.getAnswerReveal().trim();
        if (Integer.valueOf(1).equals(template.getValidationMethod())) return template.getQuestionAnswer();
        if (!Integer.valueOf(3).equals(template.getValidationMethod())) return null;
        String key = template.getCorrectAnswer() == null ? "" : template.getCorrectAnswer().trim().toUpperCase();
        String option = "A".equals(key) ? template.getQuestionA()
                : "B".equals(key) ? template.getQuestionB()
                : "C".equals(key) ? template.getQuestionC()
                : "D".equals(key) ? template.getQuestionD() : null;
        return StringUtils.isBlank(option) ? key : key + ". " + option;
    }

    @Override
    public void recordReveal(Long memberId, Long topicId, Long nodeId, Long activityId)
    {
        lockMember(memberId);
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        probe.setTopicId(topicId);
        probe.setNodeId(nodeId);
        probe.setActivityId(activityId);
        List<PlayerNodeProgress> existing = progressMapper.selectPlayerNodeProgressList(probe);
        // R10-09 收尾:只有 status==1 正式完成才是「该节点已完成」;mode2 到店/凭证 status=0 行
        // 只是到达事实,不能挡玩家在到店后揭示答案(揭示记录本身只落 PlayAttemptLog)。
        // 已揭示完成的行按既有幂等合同直接返回,不重复落 abandon 流水。
        if (existing != null) {
            for (PlayerNodeProgress row : existing) {
                if (PuzzleScorePolicy.COMPLETION_REVEALED.equals(row.getCompletionMode())) return;
                if (Integer.valueOf(1).equals(row.getStatus())) {
                    throw new IllegalStateException("该节点已完成");
                }
            }
        }
        PlayAttemptLog attempt = new PlayAttemptLog();
        attempt.setMemberId(memberId);
        attempt.setTopicId(topicId);
        attempt.setNodeId(nodeId);
        attempt.setActivityId(activityId);
        attempt.setAttemptType(PlayAttemptLog.ATTEMPT_TYPE_ANSWER);
        attempt.setResult(PlayAttemptLog.RESULT_ABANDONED);
        attempt.setCreatedAt(new Date());
        try {
            if (attemptLogMapper.insertPlayAttemptLog(attempt) <= 0) {
                throw new IllegalStateException("答案揭示状态暂时不可用,请重试");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("谜题答案揭示记录失败 member={} topic={} node={} activity={}",
                    memberId, topicId, nodeId, activityId, e);
            throw new IllegalStateException("答案揭示状态暂时不可用,请重试");
        }
    }
}

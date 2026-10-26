package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.domain.dto.NodeHintUnlockCommand;
import com.chengyinhub.business.domain.vo.NodeHintUnlockResult;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.support.PlayNodeAccessService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    private final ICmsTopicNodeService nodeService;
    private final PlayNodeAccessService accessService;
    private final ICmsPointsSettingService settingsService;
    private final IUmsMemberService memberService;
    private final IUmsMemberPointsDetailService detailService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chengyinhub.business.service.ITopicRouteRuntimeService topicRouteRuntimeService;

    public PuzzlePlayServiceImpl(ICmsMemberTemplateService templateService, PlayAttemptLogMapper attemptLogMapper,
            PlayerNodeProgressMapper progressMapper, UmsMemberMapper memberMapper,
            ICmsTopicNodeService nodeService, PlayNodeAccessService accessService,
            ICmsPointsSettingService settingsService, IUmsMemberService memberService,
            IUmsMemberPointsDetailService detailService)
    {
        this.templateService = templateService;
        this.attemptLogMapper = attemptLogMapper;
        this.progressMapper = progressMapper;
        this.memberMapper = memberMapper;
        this.nodeService=nodeService; this.accessService=accessService; this.settingsService=settingsService;
        this.memberService=memberService; this.detailService=detailService;
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
        CmsMemberTemplate template = originalHintTemplate(memberId, topicId, node, activityId);
        if (template != null && !Integer.valueOf(1).equals(template.getValidationMethod())
                && !Integer.valueOf(3).equals(template.getValidationMethod())) template = null;
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

    /** Hint/reveal bodies remain server-only and keep the original unlock/attempt ledger. */
    private CmsMemberTemplate originalHintTemplate(Long memberId, Long topicId, CmsTopicNode node, Long activityId) {
        if (node == null) return null;
        com.fasterxml.jackson.databind.JsonNode answer = topicRouteRuntimeService == null ? null
                : topicRouteRuntimeService.nodeAnswers(memberId, topicId, activityId, node.getId());
        if (com.chengyinhub.business.service.support.NodeJourneySnapshot.hasContent(answer))
            return com.chengyinhub.business.service.support.NodeJourneySnapshot.contentTemplate(answer);
        return node.getTemplateId() == null ? null : templateService.selectCmsMemberTemplateById(node.getTemplateId());
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
        recordReveal(memberId, topicId, nodeId, activityId, "default");
    }

    @Override
    public void recordReveal(Long memberId, Long topicId, Long nodeId, Long activityId,
            String originalPlayInstance)
    {
        if (StringUtils.isBlank(originalPlayInstance)) throw new IllegalArgumentException("原局身份缺失");
        lockMember(memberId);
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        probe.setTopicId(topicId);
        probe.setNodeId(nodeId);
        probe.setActivityId(activityId);
        probe.setPlayInstanceId(originalPlayInstance);
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

    /** Complete node-hint unlock. The original ledger claim must precede member debit. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public NodeHintUnlockResult unlockNodeHint(NodeHintUnlockCommand command)
    {
        Long memberId = command == null ? null : command.memberId();
        Long nodeId = command == null ? null : command.nodeId();
        String activityId = command == null ? null : command.activityId();
        String topicId = command == null ? null : command.topicId();
        if (memberId == null || memberId <= 0) return hintFailure("请先登录");
        if (nodeId == null || nodeId <= 0) return hintFailure("缺少节点ID");
        CmsTopicNode node = nodeService.selectCmsTopicNodeById(nodeId);
        if (node == null) return hintFailure("该节点无提示");
        if (node.getTemplateId() == null) {
            Long originalActivity;
            try { originalActivity = StringUtils.isBlank(activityId) ? 0L : Long.valueOf(activityId); }
            catch (NumberFormatException invalid) { return hintFailure("该节点无提示"); }
            if (originalHintTemplate(memberId, node.getTopicId(), node, originalActivity) == null)
                return hintFailure("该节点无提示");
        }
        // ★ 与 /puzzle/hint 同口径的准入闸(2026-09-16 补):原来只验登录,任何登录用户拿到
        //   nodeId 就能读走任意节点(含别人场次/付费内容)的提示正文,规则没开时还免费直出。
        //   老客户端只发 nodeId:用节点自己的 topicId 兜底当自玩上下文(仍要持该主题通行证),
        //   缺参数不是「有权读」,只是参数形态兼容。
        String ctxTopicId = topicId;
        if (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId)) {
            ctxTopicId = node.getTopicId() == null ? null : String.valueOf(node.getTopicId());
        }
        PlayNodeAccessService.Context ctx = accessService.resolveContext(memberId, activityId, ctxTopicId, nodeScope(node));
        if (ctx.failure() != null) return new NodeHintUnlockResult(null, new NodeHintUnlockResult.Failure(ctx.failure().originalAjaxCode(), ctx.failure().message()));
        String leadErr = accessService.leadGateError(ctx.activityId(), nodeScope(node));
        if (leadErr != null) return hintFailure(leadErr);
        String routeErr = accessService.routeAccessError(memberId, ctx.activityId(), ctx.topicId(), nodeScope(node), null);
        if (routeErr != null) return hintFailure(routeErr);
        CmsMemberTemplate tpl = originalHintTemplate(memberId, ctx.topicId(), node, ctx.activityId());
        if (tpl == null || (StringUtils.isEmpty(tpl.getHint1()) && StringUtils.isEmpty(tpl.getHint2()))) {
            return hintFailure("该节点无提示");
        }

        int cost = hintUnlockCost();
        // 无规则/规则为0 = 功能未开闸,免费直出(兼容老版本客户端与灰度期)
        if (cost <= 0) {
            return hintResult(tpl, 0);
        }
        UmsMember member = memberService.selectUmsMemberById(memberId);
        long before = (member != null && member.getPoint() != null) ? member.getPoint() : 0L;
        if (before < cost) {
            return hintFailure("积分不足");
        }
        // [资损] uk_member_event_once 是并发幂等真闸:先抢占唯一流水,成功者才有资格扣分。
        // 不能先扣再插,否则后到请求即使撞唯一键也已经重复扣分。
        UmsMemberPointsDetail d = new UmsMemberPointsDetail();
        d.setMemberId(memberId);
        d.setCurrentPoints(before);
        d.setAfterPoints(before - cost);
        d.setChangePoints((long) cost);
        d.setChangeReason("解锁提示");
        d.setEventType(14L);
        d.setEventId(nodeId);
        d.setChangeType(2L); // 2=支出
        d.setDelFlag(0);
        d.setCreateTime(new Date());
        try {
            detailService.insertUmsMemberPointsDetail(d);
        } catch (DuplicateKeyException e) {
            return hintResult(tpl, 0);
        }

        // 原子扣减(point >= cost 才扣)。若并发消费使余额不足，回滚刚抢到的流水后返回业务文案。
        try {
            memberService.deductPoints(memberId, cost);
        } catch (RuntimeException e) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            return hintFailure("积分不足,请重试");
        }

        return hintResult(tpl, cost);

    }

    @Override
    public int hintUnlockCost()
    {
        CmsPointsSetting q = new CmsPointsSetting();
        q.setType(1L);
        q.setEventType(14L);
        List<CmsPointsSetting> rows = settingsService.selectCmsPointsSettingList(q);
        if (rows == null || rows.isEmpty()) return 0;
        CmsPointsSetting s = rows.get(0);
        // 后台「积分规则」停用(status!=1)= 关闸免费:mapper 的 where 不带 status,必须在这里判,
        // 否则管理员点了停用仍在收费(开关语义与展示不一致)
        if (s.getStatus() != null && s.getStatus() != 1L) return 0;
        if (s.getPointsNum() != null) return s.getPointsNum();
        if (s.getValue() != null) return s.getValue().intValue();
        return 0;
    }

    private static NodeHintUnlockResult hintFailure(String message) {
        return new NodeHintUnlockResult(null, new NodeHintUnlockResult.Failure(500, message));
    }
    private static NodeHintUnlockResult hintResult(CmsMemberTemplate template, int cost) {
        return new NodeHintUnlockResult(new NodeHintUnlockResult.Hints(template.getHint1(), template.getHint2(), cost), null);
    }
    private static PlayNodeAccessService.NodeScope nodeScope(CmsTopicNode node) {
        return new PlayNodeAccessService.NodeScope(node.getId(),node.getTopicId(),node.getChapterId(),node.getSortId(),node.getStatus(),node.getUnlockAfterNodeId());
    }
}

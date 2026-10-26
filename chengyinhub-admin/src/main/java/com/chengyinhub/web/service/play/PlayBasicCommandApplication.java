package com.chengyinhub.web.service.play;

import com.chengyinhub.common.utils.GeoDistanceUtils;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.dto.SensorResultRequest;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ISensorChallengeService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.IPlayCheckinCodeService;
import com.chengyinhub.business.service.PlayScanEntry;
import com.chengyinhub.business.service.impl.PlayScanEntryService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.util.PuzzleScorePolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.chengyinhub.common.core.domain.AjaxResult.error;
import com.chengyinhub.web.service.play.PlayNodeContext.PlayCtx;

/** SCAN, ANSWER, GPS and sensor command orchestration. */
@Service
public class PlayBasicCommandApplication {
    private static final double GEOFENCE_METERS = 50d;
    private static final double GEOFENCE_ACCURACY_METERS = 80d;
    private static final Logger logger = LoggerFactory.getLogger(PlayBasicCommandApplication.class);

    @Autowired
    private com.chengyinhub.business.service.IGameSessionRuntimeService gameSessionRuntimeService;
    @Autowired(required = false)
    private com.chengyinhub.business.service.support.ArriveLocationGuard arriveLocationGuard;
    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;
    @Autowired
    private com.chengyinhub.business.service.IPuzzlePlayService puzzlePlayService;
    @Autowired
    private com.chengyinhub.business.mapper.CmsRegistrationMapper cmsRegistrationMapper;
    @Autowired(required = false)
    private ITopicRouteRuntimeService topicRouteRuntimeService;
    @Autowired
    private IShopDayProofService shopDayProofService;
    @Autowired(required = false)
    private IPlayCheckinCodeService playCheckinCodeService;
    @Autowired
    private PlayScanEntryService playScanEntryService;
    @Autowired
    private ISensorChallengeService sensorChallengeService;
    @Autowired
    private PlayBasicIntentApplication playBasicIntentApplication;
    @Autowired
    private PlayCompletionFacts playCompletionFacts;
    @Autowired
    private PlayNodeCompletionApplication playNodeCompletionApplication;
    @Autowired
    private PlayNodeContext playNodeContext;
    @Autowired
    private PlayTaskPresentation playTaskPresentation;

    /** 扫码打卡：原节点、输入与前置状态属于同一意图。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult checkin(Long actorMemberId, String activityId, String code, String topicId,
            String routeActionId, Integer expectedRouteVersion, Long registrationId,
            String nodeId, Long routeSessionId, String runtimeInstance, Integer authorityEpoch)
    {
        return playBasicIntentApplication.executeBasicIntent(actorMemberId, "SCAN", activityId, topicId, nodeId, routeActionId, expectedRouteVersion,
                registrationId, routeSessionId, runtimeInstance, authorityEpoch, java.util.Arrays.asList(code),
                originalRun -> checkinOnce(actorMemberId, activityId, code, topicId, routeActionId, expectedRouteVersion, registrationId, nodeId, runtimeInstance, authorityEpoch, originalRun));
    }

    public AjaxResult checkin(String activityId, String code, String topicId,
            String routeActionId, Integer expectedRouteVersion, Long registrationId) {
        return error("必须提供原目标节点和运行时身份");
    }

    public AjaxResult checkinOnce(Long actorMemberId, String activityId, String code, String topicId,
            String routeActionId, Integer expectedRouteVersion, Long registrationId, String intendedNodeId,
            String runtimeInstance, Integer authorityEpoch, PlayOriginalRun originalRun)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        if (StringUtils.isBlank(code) || (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId))) return error("参数有误");

        // 1) 码 → 节点：一次性游戏打卡码优先；签名无效才回落店内静态 checkin_code
        String trimmedCode = code.trim();
        CmsTopicNode node = null;
        boolean liveCode = false;
        if (playCheckinCodeService != null) {
            IPlayCheckinCodeService.ConsumeResult live = playCheckinCodeService.inspect(trimmedCode);
            if (live.kind == IPlayCheckinCodeService.ConsumeResult.Kind.USED
                    || live.kind == IPlayCheckinCodeService.ConsumeResult.Kind.EXPIRED) {
                return error(live.message);
            }
            if (live.kind == IPlayCheckinCodeService.ConsumeResult.Kind.OK) {
                liveCode = true;
                node = cmsTopicNodeService.selectCmsTopicNodeById(live.nodeId);
                if (node == null) return error("节点不存在");
            }
        }
        if (node == null) {
            CmsTopicNode nodeProbe = new CmsTopicNode();
            nodeProbe.setCheckinCode(trimmedCode);
            List<CmsTopicNode> nodeList = cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe);
            if (nodeList == null || nodeList.isEmpty()) return error("无效的打卡码");
            // 静态码应全局唯一(uk_cms_topic_node_checkin_code);库里一码多站时不猜取第一条,
            // 否则会把到店记到别的站/别的主题上 —— 拒绝并留日志给运营排查。
            if (nodeList.size() > 1) {
                logger.warn("打卡码命中多个节点,拒绝打卡 nodeCount={}", nodeList.size());
                return error("该打卡码对应多个站点，请联系商家处理");
            }
            node = nodeList.get(0);
        }
        if (!String.valueOf(node.getId()).equals(intendedNodeId)) return error("扫码节点与本次意图目标不一致");
        // 规则16:普通下架/停用节点停止新发现,旧码/旧链接不得再产生新完成;
        // 已正式完成者的幂等重试仍走原回执(与 photo 同口径,§7-2)
        if (playNodeContext.isDelistedNode(node) && !playNodeContext.hasCompletedNodeFact(memberId, activityId, node)) {
            return error("该站已下架，暂时无法完成");
        }

        // 2-3) 统一上下文校验(活动会话/自玩会话:归属+报名/通行证+时效)
        PlayCtx ctx = playNodeContext.resolveCtx(memberId, activityId, topicId, node, registrationId);
        if (ctx.error != null) return ctx.error;
        Long actId = ctx.actId;
        int playMode = playNodeContext.resolveMode(actId, ctx.topicId);
        if (playMode == PlayMode.FREE_COLLECT) {
            if (playCompletionFacts.isExploreChapterRedeemed(ctx.registration, node.getChapterId())) {
                return error("该章节已核销，不能补写到店时间");
            }
            if (liveCode) {
                IPlayCheckinCodeService.ConsumeResult consumed = playCheckinCodeService.consume(trimmedCode);
                if (consumed.kind != IPlayCheckinCodeService.ConsumeResult.Kind.OK) return error(consumed.message);
            }
            PlayerNodeProgress proof = shopDayProofService.markArrived(memberId, actId, node);
            recordFirstSuccessfulScan(memberId, ctx);
            return AjaxResult.success("已记录到店", playTaskPresentation.shopDayProofData(node, proof));
        }
        // R18-FREEZE:完成方式闸按开局快照判定(冻结优先,老局快照回退实时模板)
        CmsMemberTemplate checkinTemplate = playNodeContext.effectiveTemplate(memberId, ctx.topicId, ctx.actId, node, originalRun);
        if (playNodeContext.isAppSensorNode(node, checkinTemplate)) return error("该站需通过 App 传感器挑战完成");
        if (playNodeContext.isPhotoFilterNode(node, checkinTemplate)) return error("该站需通过 App 滤镜相机完成");
        // P1 带领场闸:集合中不可推进;行进中章节须队长已放行
        String leadErr = playNodeContext.leadGateError(actId, node);
        if (leadErr != null) return error(leadErr);

        // 3.5) 线性解锁校验（仅 mode=1 经典定向，防扫后面的码跳关）
        String unlockErr = playNodeContext.routeAccessError(memberId, actId, ctx.topicId, node, routeActionId);
        if (unlockErr != null) return error(unlockErr);

        com.fasterxml.jackson.databind.JsonNode sharedRoute = originalRun.runtimeInstance() == null || gameSessionRuntimeService == null ? null
                : gameSessionRuntimeService.sharedRouteSnapshot(memberId, ctx.topicId, actId, node.getId());
        if (liveCode) {
            IPlayCheckinCodeService.ConsumeResult consumed = playCheckinCodeService.consume(trimmedCode);
            if (consumed.kind != IPlayCheckinCodeService.ConsumeResult.Kind.OK) return error(consumed.message);
        }
        boolean personalGameplay=originalRun.session()!=null && topicRouteRuntimeService!=null && topicRouteRuntimeService.genericPlayer(memberId,ctx.topicId,actId);
        if (sharedRoute != null || personalGameplay) {
            PlayerNodeProgress proof = shopDayProofService.markArrived(memberId, actId, node);
            Map<String,Object> arrivalData=playTaskPresentation.shopDayProofData(node,proof);
            if(personalGameplay)arrivalData.put("themeRuntime",topicRouteRuntimeService.recordPlayerArrival(memberId,ctx.topicId,actId,node.getId(),routeActionId,expectedRouteVersion));
            if(sharedRoute!=null)arrivalData.put("sharedRuntime",gameSessionRuntimeService.recordSharedBasicArrival(memberId,ctx.topicId,actId,node.getId(),routeActionId,"SCAN",runtimeInstance,authorityEpoch));
            recordFirstSuccessfulScan(memberId, ctx);
            return AjaxResult.success(personalGameplay?"已记录到达，请继续当前任务":"已记录到达，请继续本队任务", arrivalData);
        }
        // 4-5) 记录进度 + 发分 + 通关判定（与答题完成共用）
        playNodeCompletionApplication.logAttempt(memberId, node, actId, 2, 1, null); // M9 扫码打卡成功
        Map<String, Object> data = playNodeCompletionApplication.advanceProgress(memberId, node, actId, null, null, null,
                RewardPolicy.FULL, routeActionId, expectedRouteVersion, null, null, originalRun);
        recordFirstSuccessfulScan(memberId, ctx);
        if (ctx.nightWarning) data.put("nightWarning", true); // M2 自玩夜间提示(不阻断)
        boolean firstTime = Boolean.TRUE.equals(data.get("firstTime"));
        return AjaxResult.success(firstTime ? "打卡成功" : "该节点已打卡", data);
    }

    public AjaxResult checkin(String activityId, String code, String topicId,
            String routeActionId, Integer expectedRouteVersion) {
        return checkin(activityId, code, topicId, routeActionId, expectedRouteVersion, null);
    }

    public AjaxResult checkin(String activityId, String code, String topicId) {
        return checkin(activityId, code, topicId, null, null, null);
    }

    public void recordFirstSuccessfulScan(Long memberId, PlayCtx ctx) {
        CmsRegistration ticket = ctx.registration;
        if (ticket == null || ticket.getId() == null) {
            throw new com.chengyinhub.common.exception.ServiceException("扫码订单资格缺失，请从票夹重新进入");
        }
        Long ownerType = ctx.selfPlay ? 1L : 2L;
        Long ownerId = ctx.selfPlay ? ctx.topicId : ctx.actId;
        Date scanTime = new Date();
        if (cmsRegistrationMapper.markFirstSuccessfulScan(ticket.getId(), memberId,
                ownerType, ownerId, scanTime) == 1) return;
        // CAS 失败必须当前读；重复扫码保留首次时间，资格变化则回滚本次扫码。
        CmsRegistration current = cmsRegistrationMapper.selectCmsRegistrationByIdForUpdate(ticket.getId());
        if (current == null || !memberId.equals(current.getMemberId())
                || !ownerType.equals(current.getOwnerType()) || !ownerId.equals(current.getOwnerId())
                || !Integer.valueOf(2).equals(current.getRegistrationStatus())
                || !Integer.valueOf(2).equals(current.getPaymentStatus())
                || (ctx.selfPlay && !ctx.continuing && current.getExpiresAt() != null && !current.getExpiresAt().after(scanTime))
                || current.getFirstSuccessfulScanAt() == null) {
            throw new com.chengyinhub.common.exception.ServiceException("订单资格已变化，请刷新票夹后重试");
        }
    }

    /**
     * 门口游戏码冷启动：未报名去主题购买页，已报名进游玩。不写打卡进度。
     */
    public AjaxResult scanEntry(Long actorMemberId, String code)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        try {
            PlayScanEntry entry = playScanEntryService.resolve(memberId, code);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("action", entry.action);
            data.put("topicId", entry.topicId);
            data.put("nodeId", entry.nodeId);
            if (entry.activityId != null) data.put("activityId", entry.activityId);
            return AjaxResult.success(data);
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 答题完成：节点绑定模板的验证方式为 文字(1)/选项(3) 时，提交答案校验通过即推进进度。
     * 复用现有模板引擎（questionAnswer / correctAnswer / feedbackText），不另建节点类型表。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult answer(Long actorMemberId, String activityId, String nodeId, String answer, String topicId,
            String routeActionId, Integer expectedRouteVersion, Long routeSessionId, String runtimeInstance, Integer authorityEpoch)
    {
        return playBasicIntentApplication.executeBasicIntent(actorMemberId, "ANSWER", activityId, topicId, nodeId, routeActionId, expectedRouteVersion,
                null, routeSessionId, runtimeInstance, authorityEpoch, java.util.Arrays.asList(answer),
                originalRun -> answerOnce(actorMemberId, activityId, nodeId, answer, topicId, routeActionId, expectedRouteVersion, runtimeInstance, authorityEpoch, originalRun));
    }

    public AjaxResult answer(String activityId, String nodeId, String answer, String topicId,
            String routeActionId, Integer expectedRouteVersion) {
        return error("必须提供原运行时身份");
    }

    public AjaxResult answerOnce(Long actorMemberId, String activityId, String nodeId, String answer, String topicId,
            String routeActionId, Integer expectedRouteVersion, String runtimeInstance, Integer authorityEpoch, PlayOriginalRun originalRun)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        if (StringUtils.isBlank(nodeId) || (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId))) return error("参数有误");
        Long nId;
        try { nId = Long.valueOf(nodeId); }
        catch (Exception e) { return error("参数有误"); }

        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nId);
        if (node == null) return error("节点不存在");
        // 规则16 下架闸 + 已完成幂等豁免(与 photo 同口径,§7-2):未完成的新提交仍被拦
        if (playNodeContext.isDelistedNode(node) && !playNodeContext.hasCompletedNodeFact(memberId, activityId, node)) {
            return error("该站已下架，暂时无法完成");
        }

        // M2 统一上下文:活动会话或自玩会话(topicId 二选一);校验/时效/通行证在此收口
        PlayCtx ctx = playNodeContext.resolveCtx(memberId, activityId, topicId, node);
        if (ctx.error != null) return ctx.error;
        Long actId = ctx.actId;
        int playMode = playNodeContext.resolveMode(actId, ctx.topicId);
        // P1 带领场闸:集合中不可推进;行进中章节须队长已放行
        String leadErr = playNodeContext.leadGateError(actId, node);
        if (leadErr != null) return error(leadErr);

        // B5 收窄(2026-08-15):mode2 答题是「扫码进店后的体验层」——未进店不得作答
        // (题面在 /nodes 与 checkin 回包也按进店态下发,这里是防远程作答的硬闸)。
        if (playMode == PlayMode.FREE_COLLECT
                && !playNodeContext.isPureOnlineNode(node)
                && !shopDayProofService.hasArrived(memberId, actId, node.getId())) {
            return error("先扫店内静态码进店，再开始任务");
        }

        // 线性解锁校验（仅 mode=1 经典定向）
        String unlockErr = playNodeContext.routeAccessError(memberId, actId, ctx.topicId, node, routeActionId);
        if (unlockErr != null) return error(unlockErr);

        // 读节点绑定模板的验证方式 + 校验答案(R18-FREEZE:对错判定按开局快照,新发布不改写
        // 进行中的一局;老局快照没有 nodeAnswers 键时回退实时模板)
        com.fasterxml.jackson.databind.JsonNode frozenAnswer = playNodeContext.frozenAnswer(
                memberId, ctx.topicId, ctx.actId, node.getId(), originalRun);
        if (node.getTemplateId() == null && !playNodeContext.hasFrozenContent(frozenAnswer)) return error("该节点未配置答题");
        if (playNodeContext.hasFrozenContent(frozenAnswer)) playNodeContext.frozenNodeView(node, frozenAnswer);
        int vm;
        String questionAnswer;
        String correctAnswer;
        if (frozenAnswer != null) {
            vm = frozenAnswer.path("validationMethod").asInt(0);
            if (vm == 0) return error("该节点未配置答题");
            questionAnswer = frozenAnswer.path("questionAnswer").asText(null);
            correctAnswer = frozenAnswer.path("correctAnswer").asText(null);
        } else {
            CmsMemberTemplate liveTpl = cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
            if (liveTpl == null || liveTpl.getValidationMethod() == null) return error("该节点未配置答题");
            vm = liveTpl.getValidationMethod();
            questionAnswer = liveTpl.getQuestionAnswer();
            correctAnswer = liveTpl.getCorrectAnswer();
        }
        String ans = answer == null ? "" : answer.trim();
        boolean ok;
        if (vm == 1) { // 文字验证：去全部空白 + 全角→半角归一后比对(中文 equalsIgnoreCase 无意义但无害)
            ok = StringUtils.isNotBlank(questionAnswer)
                    && playNodeContext.normalizeAnswer(questionAnswer).equalsIgnoreCase(playNodeContext.normalizeAnswer(ans));
        } else if (vm == 3) { // 选项验证(A/B/C/D)
            ok = StringUtils.isNotBlank(correctAnswer)
                    && correctAnswer.trim().equalsIgnoreCase(ans);
        } else {
            // 扩展点：收集/占领/小游戏等后续作为「单独的游戏模板」接入，
            // 届时新增对应 validationMethod(或独立完成接口)走自己的校验，
            // 校验通过后同样调用 advanceProgress 推进进度，复用幂等/发分/通关逻辑。
            return error("该节点不支持答题校验");
        }
        if (!ok) {
            playNodeCompletionApplication.logAttempt(memberId, node, actId, 1, 0, ans); // M9 答错(带用户答案供AI评估)
            // 明确标记「这是答案错」:前端据此才计一次错 / 才可能解锁揭示。业务拒绝
            // (活动已结束/未报名/先扫码进店/本章未放行…)不带这个标记,不得被当成答错。
            return AjaxResult.error("答案不正确",
                    java.util.Collections.singletonMap("answerWrong", true));
        }

        boolean sharedAnswerGameplay = originalRun.runtimeInstance() != null;
        boolean personalAnswerGameplay = originalRun.session() != null && topicRouteRuntimeService != null && topicRouteRuntimeService.genericPlayer(memberId, ctx.topicId, actId);
        if (personalAnswerGameplay || sharedAnswerGameplay) {
            PlayerNodeProgress proof = shopDayProofService.markArrived(memberId, actId, node);
            Map<String,Object> completion = playTaskPresentation.shopDayProofData(node, proof);
            if (personalAnswerGameplay) completion.put("themeRuntime", topicRouteRuntimeService.recordPlayerArrival(memberId,ctx.topicId,actId,node.getId(),routeActionId,expectedRouteVersion,"ANSWER"));
            if (sharedAnswerGameplay) completion.put("sharedRuntime", gameSessionRuntimeService.recordSharedBasicArrival(memberId,ctx.topicId,actId,node.getId(),routeActionId,"ANSWER",runtimeInstance,authorityEpoch));
            return AjaxResult.success("答案已验证，请继续当前任务", completion);
        }
        playNodeCompletionApplication.logAttempt(memberId, node, actId, 1, 1, null); // M9 答对
        Map<String, Object> data;
        if (playMode == PlayMode.LINEAR_ORIENTEERING) {
            int puzzleScore;
            try {
                puzzleScore = puzzlePlayService.solvedScore(memberId, ctx.topicId, node.getId(), actId);
            } catch (IllegalStateException e) {
                return error(e.getMessage());
            }
            data = playNodeCompletionApplication.advanceProgress(memberId, node, actId, null,
                    puzzleScore, PuzzleScorePolicy.COMPLETION_SOLVED, RewardPolicy.FULL,
                    routeActionId, expectedRouteVersion, null, null, originalRun);
        } else {
            data = playNodeCompletionApplication.advanceProgress(memberId, node, actId, null, null, null, RewardPolicy.FULL,
                    routeActionId, expectedRouteVersion, null, null, originalRun);
        }
        if (ctx.nightWarning) data.put("nightWarning", true); // M2 自玩夜间提示(不阻断)
        boolean firstTime = Boolean.TRUE.equals(data.get("firstTime"));
        return AjaxResult.success(firstTime ? "答对了！" : "该节点已完成", data);
    }

    public AjaxResult answer(String activityId, String nodeId, String answer, String topicId) {
        return answer(activityId, nodeId, answer, topicId, null, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult sensorResult(Long actorMemberId, SensorResultRequest request)
    {
        if (request == null) return error("参数有误");
        return playBasicIntentApplication.executeBasicIntent(actorMemberId, "SENSOR",
                request.getActivityId() == null ? null : request.getActivityId().toString(),
                request.getTopicId() == null ? null : request.getTopicId().toString(),
                request.getNodeId() == null ? null : request.getNodeId().toString(),
                request.getRouteActionId(), request.getExpectedRouteVersion(), null,
                request.getRouteSessionId(), request.getRuntimeInstance(), request.getAuthorityEpoch(),
                java.util.Arrays.asList(request.getSensorType(), request.getPayload()),
                originalRun -> sensorResultOnce(actorMemberId, request, originalRun));
    }

    private AjaxResult sensorResultOnce(Long actorMemberId, SensorResultRequest request,
            PlayOriginalRun originalRun)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        if (request == null || request.getNodeId() == null || request.getNodeId() <= 0) return error("参数有误");
        boolean hasActivity = request.getActivityId() != null && request.getActivityId() > 0;
        boolean hasTopic = request.getTopicId() != null && request.getTopicId() > 0;
        if (hasActivity == hasTopic) return error("activityId 与 topicId 必须且只能传一个");

        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(request.getNodeId());
        if (node == null) return error("节点不存在或未配置玩法");
        if (node.getTemplateId() == null && !playNodeContext.hasFrozenContent(playNodeContext.frozenAnswer(
                memberId, node.getTopicId(), hasActivity ? request.getActivityId() : 0L, node.getId(), originalRun)))
            return error("节点不存在或未配置玩法");
        // 规则16 下架闸 + 已完成幂等豁免(与 photo 同口径,§7-2):未完成的新提交仍被拦
        String activityIdArg = hasActivity ? String.valueOf(request.getActivityId()) : null;
        if (playNodeContext.isDelistedNode(node) && !playNodeContext.hasCompletedNodeFact(memberId, activityIdArg, node)) {
            return error("该站已下架，暂时无法完成");
        }

        PlayCtx ctx = playNodeContext.resolveCtx(memberId,
                activityIdArg,
                hasTopic ? String.valueOf(request.getTopicId()) : null, node);
        if (ctx.error != null) return ctx.error;
        // canPlay may initialize a route: refuse lead state before entering that writable context.
        String leadErr = playNodeContext.leadGateError(ctx.actId, node);
        if (leadErr != null) return error(leadErr);
        String unlockErr = playNodeContext.routeAccessError(memberId, ctx.actId, ctx.topicId, node, request.getRouteActionId());
        if (unlockErr != null) return error(unlockErr);
        int mode = playNodeContext.resolveMode(ctx.actId, ctx.topicId);
        if (mode == PlayMode.FREE_COLLECT && !playNodeContext.isPureOnlineNode(node)
                && !shopDayProofService.hasArrived(memberId, ctx.actId, node.getId())) {
            return error("先扫店内静态码进店，再开始任务");
        }
        // R18-FREEZE:传感器判定配置按开局快照,老局快照没有该键时回退实时模板
        com.fasterxml.jackson.databind.JsonNode frozenAnswer = playNodeContext.frozenAnswer(memberId, ctx.topicId, ctx.actId, node.getId(), originalRun);
        CmsMemberTemplate template = frozenAnswer == null
                ? cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId())
                : playNodeContext.frozenTemplateView(node, frozenAnswer);
        String sensorError = sensorChallengeService.validateAndAcquire(memberId, ctx.actId, node, template,
                request.getSensorType(), request.getPayload());
        if (sensorError != null) return error(sensorError);

        Map<String, Object> data = playNodeCompletionApplication.advanceProgress(memberId, node, ctx.actId, null, null, null,
                RewardPolicy.XP_BADGE_ONLY, request.getRouteActionId(),
                request.getExpectedRouteVersion(), null, null, originalRun);
        if (ctx.nightWarning) data.put("nightWarning", true);
        boolean firstTime = Boolean.TRUE.equals(data.get("firstTime"));
        return AjaxResult.success(firstTime ? "挑战完成" : "该节点已完成", data);
    }

    /**
     * GPS 到达 / 到点即完成：validationMethod=0(无需验证)或 5(GPS到达)的节点走此接口完成。
     * 修「到点即完成」节点此前无完成路径的阻断 bug。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult arrive(Long actorMemberId, String activityId, String nodeId, String longitude, String latitude, String topicId,
            String routeActionId, Integer expectedRouteVersion, String accuracy, Long routeSessionId,
            String runtimeInstance, Integer authorityEpoch)
    {
        return playBasicIntentApplication.executeBasicIntent(actorMemberId, "GPS", activityId, topicId, nodeId, routeActionId, expectedRouteVersion,
                null, routeSessionId, runtimeInstance, authorityEpoch, java.util.Arrays.asList(longitude, latitude, accuracy),
                originalRun -> arriveOnce(actorMemberId, activityId, nodeId, longitude, latitude, topicId, routeActionId, expectedRouteVersion, accuracy, runtimeInstance, authorityEpoch, originalRun));
    }

    public AjaxResult arrive(String activityId, String nodeId, String longitude, String latitude, String topicId,
            String routeActionId, Integer expectedRouteVersion, String accuracy) {
        return error("必须提供原运行时身份");
    }

    public AjaxResult arriveOnce(Long actorMemberId, String activityId, String nodeId, String longitude, String latitude, String topicId,
            String routeActionId, Integer expectedRouteVersion, String accuracy, String runtimeInstance, Integer authorityEpoch, PlayOriginalRun originalRun)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        if (StringUtils.isBlank(nodeId) || (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId))) return error("参数有误");
        Long nId;
        try { nId = Long.valueOf(nodeId); }
        catch (Exception e) { return error("参数有误"); }

        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nId);
        if (node == null) return error("节点不存在");
        // 规则16 下架闸 + 已完成幂等豁免(与 photo 同口径,§7-2):未完成的新提交仍被拦
        if (playNodeContext.isDelistedNode(node) && !playNodeContext.hasCompletedNodeFact(memberId, activityId, node)) {
            return error("该站已下架，暂时无法完成");
        }

        // M2 统一上下文:活动会话或自玩会话(topicId 二选一)
        PlayCtx ctx = playNodeContext.resolveCtx(memberId, activityId, topicId, node);
        if (ctx.error != null) return ctx.error;
        Long actId = ctx.actId;
        int mode = playNodeContext.resolveMode(actId, ctx.topicId);
        if (mode == PlayMode.FREE_COLLECT) {
            // C1「进店用玩家扫店内静态码，不用 GPS(不准)」：③ 的进店事实只有 /checkin 一条来源。
            //
            // ⚠️ 这里曾经写过到店凭证。删掉的理由不是「GPS 不够准」，而是**它给同一个事实开了第二条来源**：
            //    半径校验用的是客户端传的 lat/lng(可伪造)，而 player_node_progress.checkin_time 正是
            //    G2 硬门 queue_joined_at 的取值来源 ⇒「手机说它在附近」会被当成「玩家在店门口扫了码」，
            //    把单店等待 P90 抬高数小时、把 in_store_load 与漏斗 A 一并撑虚，全程零告警零资损。
            //    漫游免费层就是这条 GPS 路径的现成调用方(审计 5 S1)。
            // ①② 的到点即完成 / GPS 到达一字不改，下面的原路径继续走。
            //
            // ★ 纯线上节点例外(2026-08-20 用户拍板「模板不能把通关绑死在商家扫码上」):
            //   无承接商家、无坐标的节点(如《生活不掉线》G0—G6 游戏节)没有「店」可扫,
            //   也不存在可被伪造的到店事实 —— queue_joined_at / in_store_load 全挂在商家
            //   节点上,这类节点没有商家。放行语义与 /checkin 的 ③ 分支逐字一致:
            //   只记 shop-day 凭证、不发分、不判通关(③ 的完成经济仍由章节核销承担)。
            //   有商家或有坐标的节点一字不动,仍只认 /checkin。
            if (!playNodeContext.isPureOnlineNode(node)) {
                return error("请扫店内的码");
            }
            if (playCompletionFacts.isExploreChapterRedeemed(memberId, actId, node.getChapterId())) {
                return error("该章节已核销，不能补写到店时间");
            }
            PlayerNodeProgress proof = shopDayProofService.markArrived(memberId, actId, node);
            return AjaxResult.success("已完成本节", playTaskPresentation.shopDayProofData(node, proof));
        }
        // P1 带领场闸:集合中不可推进;行进中章节须队长已放行
        String leadErr = playNodeContext.leadGateError(actId, node);
        if (leadErr != null) return error(leadErr);

        // 线性解锁校验（仅 mode=1 经典定向；mode=2 自由定向跳过）
        String unlockErr = playNodeContext.routeAccessError(memberId, actId, ctx.topicId, node, routeActionId);
        if (unlockErr != null) return error(unlockErr);

        // 验证方式闸：答题(1)/拍照(2)/选项(3)/扫码(4) 类节点必须走对应接口完成，
        // 不能用"到达/到点"绕过任务白拿分+券+勋章+通关(反作弊)。仅 0/null(到点即完成) 或 5(GPS) 放行。
        Integer arriveValidationMethod = null;
        if (node.getTemplateId() != null && node.getTemplateId() > 0L) {
            // R18-FREEZE:完成方式按开局快照判定;老局快照没有该键时回退实时模板
            CmsMemberTemplate vmTpl = playNodeContext.effectiveTemplate(memberId, ctx.topicId, ctx.actId, node, originalRun);
            // G-R1:悬空 templateId(行读不到)是配置损坏,必须缺合同失败关闭。
            // 否则 arriveValidationMethod 停 null ⇒ vm=5 节点被当成普通无验证节点,缺坐标也放行完成。
            if (vmTpl == null) {
                return error("该站配置异常，暂时无法打卡");
            }
            arriveValidationMethod = vmTpl.getValidationMethod();
            if (!ValidationMethod.completesOnArrive(vmTpl.getValidationMethod())) {
                // 白名单判据见 ValidationMethod#completesOnArrive:
                // 原来这里逐个列"要拒的 vm",新增完成方式会默认从这条路放行,
                // 等于绕过它自己的判定且零报错。
                return error("该站需完成对应任务后才能打卡");
            }
        }

        // GPS 围栏：节点有坐标才校验（gcj02，Haversine 50m）；无坐标则按到点即完成放行（前端引导走扫码）。
        // ⚠️ GPS(vm=5)节点是例外:它必须靠站点坐标才能判定"到没到",缺经或缺纬不能退化成
        //    普通无验证线上节点直接放行(否则漏配坐标 = 任意地点都算到达,发进度发奖)。
        com.fasterxml.jackson.databind.JsonNode sharedArrival=originalRun.runtimeInstance()==null || gameSessionRuntimeService==null?null:gameSessionRuntimeService.sharedRouteSnapshot(memberId,ctx.topicId,actId,node.getId());
        boolean personalArrival=originalRun.session()!=null && topicRouteRuntimeService!=null && topicRouteRuntimeService.genericPlayer(memberId,ctx.topicId,actId);
        String targetLongitude=node.getLongitude(),targetLatitude=node.getLatitude();
        if((personalArrival || sharedArrival!=null) && topicRouteRuntimeService!=null) {com.fasterxml.jackson.databind.JsonNode target=topicRouteRuntimeService.nodeArrivalConfig(memberId,ctx.topicId,actId,node.getId());if(target==null)return error("本局缺少冻结到达配置");targetLongitude=target.path("longitude").asText(null);targetLatitude=target.path("latitude").asText(null);}
        boolean hasNodeCoord = StringUtils.isNotBlank(targetLongitude) && StringUtils.isNotBlank(targetLatitude);
        boolean gpsNode = Integer.valueOf(5).equals(arriveValidationMethod);
        if (gpsNode && !hasNodeCoord) return error("站点未设坐标，暂时无法打卡");
        if (hasNodeCoord) {
            if (StringUtils.isBlank(longitude) || StringUtils.isBlank(latitude)) return error("请开启定位");
            // R10-10:Double.parseDouble 接受 "NaN"/"Infinity",而 NaN 与任何数比较恒 false,
            // 旧写法让无效坐标绕过围栏直接落入 advanceProgress 拿进度和奖励。玩家输入与站点配置
            // 都必须有限且在经纬范围内;距离本身也要有限,任一不满足一律失败关闭。
            Double playerLat = PlayNodeContext.parseGeoCoordinate(latitude, -90d, 90d);
            Double playerLng = PlayNodeContext.parseGeoCoordinate(longitude, -180d, 180d);
            Double nodeLat = PlayNodeContext.parseGeoCoordinate(targetLatitude, -90d, 90d);
            Double nodeLng = PlayNodeContext.parseGeoCoordinate(targetLongitude, -180d, 180d);
            if (playerLat == null || playerLng == null || nodeLat == null || nodeLng == null) return error("定位无效");
            double dist = GeoDistanceUtils.haversineMeters(playerLat, playerLng, nodeLat, nodeLng);
            if (!Double.isFinite(dist)) return error("定位无效");
            // B1:定位精度闸(仅坐标判定路径)。accuracy 为端上 wx.getLocation 的水平精度(米);
            // 空白=未上报,拒(ARRIVE_ACCURACY_MISSING);非有限/负数/坏文本=定位无效(ARRIVE_ACCURACY_INVALID);
            // >80m 要求去开阔处或扫码,先于距离判断。
            // O-01(owner 2026-10-23 拍板:尚无真实用户,不设过渡期):原「空精度放行」与开关
            // play.arrive.require_accuracy 已删除。这两种拒绝不是作弊判定,不入守卫表、不可申诉,重新定位或扫码即可。
            if (StringUtils.isBlank(accuracy)) {
                return error("未获取到定位精度，请重新定位后重试或扫码完成")
                        .put("errorCode", com.chengyinhub.business.service.support.ArriveLocationGuard.ACCURACY_MISSING)
                        .put("appealable", false);
            }
            Double acc = PlayNodeContext.parseGeoCoordinate(accuracy, 0d, Double.MAX_VALUE);
            if (acc == null) {
                return error("定位无效")
                        .put("errorCode", com.chengyinhub.business.service.support.ArriveLocationGuard.ACCURACY_INVALID)
                        .put("appealable", false);
            }
            if (acc > GEOFENCE_ACCURACY_METERS) return error("定位精度不足，请到开阔处或扫码完成");
            if (dist > GEOFENCE_METERS) return error("还没到这一站附近");
            // O-01 第 4–7 层(节流/速度/窗口位移/不确定性扣减):开关 off 时不读不写,行为与改动前一致。
            if (arriveLocationGuard != null) {
                com.chengyinhub.business.service.support.ArriveLocationGuard.Result guard =
                        arriveLocationGuard.check(memberId, actId, ctx.topicId, nId, nodeLat, nodeLng, acc);
                if (guard.rejected()) {
                    AjaxResult rejected = error(guard.message()).put("errorCode", guard.errorCode())
                            .put("appealable", guard.appealable());
                    if (guard.guardLogId() != null) rejected.put("guardLogId", guard.guardLogId().toString());
                    rejected.put("routeSessionId", originalRun.routeSessionId().toString());
                    rejected.put("nodeId", nId.toString());
                    if (originalRun.runtimeInstance() != null) {
                        rejected.put("runtimeInstance", originalRun.runtimeInstance());
                        rejected.put("authorityEpoch", originalRun.authorityEpoch());
                    }
                    if (guard.appealable()) rejected.put("appealBizType",
                            com.chengyinhub.business.service.support.ArriveLocationGuard.APPEAL_BIZ_TYPE);
                    return rejected;
                }
            }
        }

        if(personalArrival || sharedArrival!=null) {PlayerNodeProgress proof=shopDayProofService.markArrived(memberId,actId,node);Map<String,Object> data=playTaskPresentation.shopDayProofData(node,proof);
            if(personalArrival)data.put("themeRuntime",topicRouteRuntimeService.recordPlayerArrival(memberId,ctx.topicId,actId,node.getId(),routeActionId,expectedRouteVersion,"GPS"));
            if(sharedArrival!=null)data.put("sharedRuntime",gameSessionRuntimeService.recordSharedBasicArrival(memberId,ctx.topicId,actId,node.getId(),routeActionId,"GPS",runtimeInstance,authorityEpoch));
            recordFirstSuccessfulScan(memberId,ctx);return AjaxResult.success("已记录本局到达，请继续当前任务或操作",data);}
        playNodeCompletionApplication.logAttempt(memberId, node, actId, 3, 1, null); // M9 GPS到达成功
        Map<String, Object> data = playNodeCompletionApplication.advanceProgress(memberId, node, actId, null, null, null,
                RewardPolicy.FULL, routeActionId, expectedRouteVersion, null, null, originalRun);
        if (ctx.nightWarning) data.put("nightWarning", true); // M2 自玩夜间提示(不阻断)
        boolean firstTime = Boolean.TRUE.equals(data.get("firstTime"));
        return AjaxResult.success(firstTime ? "已到达，打卡成功" : "该节点已完成", data);
    }

    public AjaxResult arrive(String activityId, String nodeId, String longitude, String latitude, String topicId,
            String routeActionId, Integer expectedRouteVersion) {
        return arrive(activityId, nodeId, longitude, latitude, topicId, routeActionId, expectedRouteVersion, null);
    }

    public AjaxResult arrive(String activityId, String nodeId, String longitude, String latitude, String topicId) {
        return arrive(activityId, nodeId, longitude, latitude, topicId, null, null, null);
    }
}

package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.vo.SpoilerAccessSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.service.support.SensorChallengePolicy;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.ISpoilerProtectionService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.TopicRouteModePolicy;
import com.chengyinhub.business.util.ChapterBlockReadSupport;
import com.chengyinhub.business.util.ChapterAtmospherePreset;
import com.chengyinhub.business.util.QuestionOptionMedia;
import com.chengyinhub.business.util.PlayNarrativeSupport;
import com.chengyinhub.business.util.PuzzleScorePolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.chengyinhub.common.core.domain.AjaxResult.error;

/** Reads and projects existing node facts and frozen Entry results; never initializes or refreshes runtime state. */
@Service
public class PlayNodeQueryApplication {
    private static final Logger logger = LoggerFactory.getLogger(PlayNodeQueryApplication.class);

    @Autowired(required = false)
    private com.chengyinhub.business.mapper.CmsTopicMapper cmsTopicMapper;
    @Autowired
    private NpcProfileService npcProfileService;
    @Autowired
    private com.chengyinhub.business.service.IXpBudgetService xpBudgetService;
    @Autowired
    private com.chengyinhub.business.service.ICmsTopicService cmsTopicService;
    @Autowired
    private com.chengyinhub.business.service.ISmsCouponService smsCouponService;
    @Autowired
    private ICmsTopicChapterService cmsTopicChapterService;
    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;
    @Autowired
    private ISpoilerProtectionService spoilerProtectionService;
    @Autowired
    private com.chengyinhub.business.service.IPuzzlePlayService puzzlePlayService;
    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;
    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;
    @Autowired(required = false)
    private ITopicRouteRuntimeService topicRouteRuntimeService;
    @Autowired
    private com.chengyinhub.business.mapper.TopicOnsiteMetricsMapper topicOnsiteMetricsMapper;
    @Autowired
    private com.chengyinhub.business.mapper.MerchantChapterOfferMapper merchantChapterOfferMapper;
    @Autowired
    private com.chengyinhub.business.mapper.CoopPerkMapper coopPerkMapper;
    @Autowired
    private com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper topicTemplateMetaMapper;
    @Autowired
    private com.chengyinhub.business.mapper.GameRuntimeSessionMapper gameRuntimeSessionMapper;
    @Autowired
    private com.chengyinhub.business.service.INodeMerchantOverrideService nodeMerchantOverrideService;
    @Autowired
    private PlayCompletionFacts playCompletionFacts;
    @Autowired
    private PlayNodeContext playNodeContext;

    /** 单条主键取券;查不到/查挂了一律 null ⇒ 调用方整块不下发,不让奖励拖垮 /nodes */
    public com.chengyinhub.business.domain.SmsCoupon resolveCoupon(Long couponId) {
        if (couponId == null || couponId <= 0) return null;
        try {
            return smsCouponService.selectSmsCouponById(couponId);
        } catch (Exception e) {
            return null;
        }
    }

    /** Read existing list facts after Entry has obtained the original frozen route view. */
    AjaxResult prepareNodes(NodeProjection read) {
        boolean branchGraph = TopicRouteModePolicy.isEffectiveBranchGraph(read.routeTopic, read.routeState);
        if (branchGraph) {
            if (!read.registered) {
                // 未报名没有权威路线局；返回注册空态，但绝不把未选支线素材塞进响应。
                read.nodeList = new ArrayList<>();
            } else if (read.routeState == null || !TopicRouteState.BRANCH_GRAPH.equals(read.routeState.getRouteMode())) {
                return error("路线状态暂时不可用,请重试");
            } else {
                read.nodeList = visibleBranchNodes(read.nodeList, read.routeState);
            }
        }
        read.puzzleHintLevelByNode = new HashMap<>();
        if (read.routeState != null && read.routeState.getOpening() != null
                && !Boolean.TRUE.equals(read.routeState.getOpening().get("complete"))) {
            read.nodeList = visibleBranchNodes(read.nodeList, read.routeState);
        }
        if (read.playMode == PlayMode.LINEAR_ORIENTEERING) {
            try {
                read.puzzleHintLevelByNode = puzzlePlayService.selectHintLevels(read.memberId, read.tId, read.progActId);
                if (read.puzzleHintLevelByNode == null) read.puzzleHintLevelByNode = new HashMap<>();
            } catch (Exception e) {
                logger.error("谜题提示状态批量读取失败 member={} topic={} activity={}", read.memberId, read.tId, read.progActId, e);
                return error("谜题提示状态暂时不可用,请重试");
            }
        }
        read.inStoreLoadByChapter = new HashMap<>();
        read.capacityByChapter = new HashMap<>();
        read.loadUnavailable = false;
        read.capacityUnavailable = false;
        if (read.playMode == PlayMode.FREE_COLLECT) {
            try {
                for (com.chengyinhub.business.domain.dto.TopicChapterLoadFact load
                        : topicOnsiteMetricsMapper.selectInStoreLoads(read.tId)) {
                    read.inStoreLoadByChapter.put(load.getChapterId(), load.getInStoreLoad());
                }
            } catch (Exception e) {
                read.loadUnavailable = true;
                logger.warn("探店日店内负载读取失败 topic={}", read.tId, e);
            }
            try {
                for (com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta capacity
                        : topicTemplateMetaMapper.selectChapterMetaByTopicId(read.tId)) {
                    read.capacityByChapter.put(capacity.getChapterId(), capacity);
                }
            } catch (Exception e) {
                read.capacityUnavailable = true;
                logger.warn("探店日服务容量读取失败 topic={}", read.tId, e);
            }
        }
        // 本店权益:承接商家在报名时挂的 CoopPerk。节点上此前只有券(coupon),而探店日把券整条置空
        // (normNode 里 shopDay ? null : coupon),于是「本店权益」在玩家端没有任何数据源。
        // 一次按章批量取 offer,再按 perkId 去重取券面 —— 四到六家的量,不用再做批量 in 查询。
        read.perkByChapterMerchant = new HashMap<>();
        if (read.playMode == PlayMode.FREE_COLLECT) {
            try {
                List<Long> chapterIds = new ArrayList<>();
                for (CmsTopicNode n : read.nodeList) {
                    if (n.getChapterId() != null && !chapterIds.contains(n.getChapterId())) chapterIds.add(n.getChapterId());
                }
                if (!chapterIds.isEmpty()) {
                    Map<Long, com.chengyinhub.business.domain.CoopPerk> perkCache = new HashMap<>();
                    for (com.chengyinhub.business.domain.MerchantChapterOffer offer
                            : merchantChapterOfferMapper.selectLiveByChapterIds(chapterIds)) {
                        if (offer == null || offer.getPerkId() == null
                                || offer.getChapterId() == null || offer.getMerchantId() == null) continue;
                        com.chengyinhub.business.domain.CoopPerk perk = perkCache.get(offer.getPerkId());
                        if (perk == null && !perkCache.containsKey(offer.getPerkId())) {
                            perk = coopPerkMapper.selectById(offer.getPerkId());
                            perkCache.put(offer.getPerkId(), perk);
                        }
                        if (perk != null) {
                            read.perkByChapterMerchant.put(offer.getChapterId() + ":" + offer.getMerchantId(), perk);
                        }
                    }
                }
            } catch (Exception e) {
                // 权益取不到只让那一段不显示,不能把整个节点列表打挂
                logger.warn("探店日本店权益读取失败 topic={}", read.tId, e);
            }
        }
        // 节点 NPC 按 nodeId 记一次(CR-927):节点级内容逐节点不同,且一章一商家、四到六家的量,
        // 逐节点 resolve 一条 selectEnabledByScope 是可接受的;同一节点在循环里只出一次。
        read.nodeNpcCache = new HashMap<>();
        read.entitlementByChapter = new HashMap<>();
        if (read.playMode == PlayMode.FREE_COLLECT && read.progActId != null && read.progActId > 0) {
            CmsRegistration explore = playCompletionFacts.exploreRegistration(read.memberId, read.progActId);
            if (explore != null) {
                for (RegistrationEntitlement entitlement : perkLedgerService.listEntitlements(explore.getId())) {
                    read.entitlementByChapter.put(entitlement.getChapterId(), entitlement);
                }
            }
        }
        Calendar nowCal = Calendar.getInstance();
        read.nowMinuteOfDay = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE); // 服务端当前"当日分钟数",算营业态三态用
        read.playableNodeIds = new java.util.HashSet<>();
        // 节点探索值真源缓存(模板 xp 按 templateId 去重查询),与 advanceProgress 的 resolveNodeXp 同一口径
        read.nodeXpCache = new HashMap<>();
        return null;
    }

    NodeTask projectNodeBeforeRuntime(NodeProjection read, CmsTopicNode n) {
        return projectNodeBeforeRuntime(read, n, null);
    }

    NodeTask projectNodeBeforeRuntime(NodeProjection read, CmsTopicNode source,
            com.fasterxml.jackson.databind.JsonNode content) {
        boolean frozenContent = content != null && content.has("contentSnapshotVersion");
        CmsTopicNode n = frozenContent ? playNodeContext.frozenNodeView(source, content) : source;
        if (frozenContent) read.presentedContents.put(n.getId(),content.deepCopy());
        read.playableNodeIds.add(n.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", n.getId());
        m.put("name", n.getName());
        m.put("address", n.getAddress());
        m.put("sortId", n.getSortId());
        // 地图/故事素材（实体已有，前端做地图化/章节叙事用）
        m.put("longitude", n.getLongitude());
        m.put("latitude", n.getLatitude());
        m.put("imgUrl", n.getImgUrl());
        m.put("description", n.getDescription());
        // 钩子一句话。实体与 NodeDTO 早就有这个字段,只是这里一直没下发 ——
        // 前端 hero 层读 node.hookText 读了个空,恒回落 description,于是章节正文被重复念一遍。
        m.put("hookText", n.getHookText());
        // 店铺卡长钩子 / 完成碎片:都挂在节点行上(CmsTopicNode.cardHookLong/fragmentText),前端
        // 地点大卡与「完成翻碎片」一直在读,主循环却从没下发过 ⇒ 编辑器里配了也永远不渲染,零报错。
        // (historyNodes 那支早已下发 fragmentText,两条路的读方是同一个前端字段。)
        m.put("cardHookLong", n.getCardHookLong());
        m.put("fragmentText", n.getFragmentText());
        m.put("nodeTime", n.getNodeTime());
        m.put("businessTime", n.getBusinessTime());
        m.put("chapterId", n.getChapterId());
        // 节点级前置解锁提示位:前置未完成 → locked=true(硬闸在 checkin/answer/arrive/photo 服务端)
        m.put("unlockAfterNodeId", n.getUnlockAfterNodeId());
        m.put("locked", n.getUnlockAfterNodeId() != null && n.getUnlockAfterNodeId() > 0
                && !read.completedNodeIds.contains(n.getUnlockAfterNodeId()));
        if (read.playMode == PlayMode.FREE_COLLECT) {
            // 玩家只对当前门店逐店授权；这是对面 scope id，不是玩家身份或玩法字段。
            m.put("merchantId", n.getMerchantMemberId());
            // 门店分身摘要(scope_type=2 挂 merchantId)。必须挂在节点上,不能让前端另调
            // /api/ai/npc/profile —— 那个端点 scope=activity 只走 resolveForActivity(剧本角色
            // scope_type=1),取不到门店分身;而自玩通行证形态连 activityId 都没有(progActId=0)。
            // 只放展示真用得上的两项:persona 是喂模型的提示词,永远不下发;
            // avatar 也不下发 —— 对话页中央那个圆是程序化 3D 形象(utils/npc-figure.js),
            // 没有任何位置渲染头像图,下发了就是没人读的字段。
            //
            // 2026-09-15(CR-927):商家给自己的点位配了节点 NPC(scope_type=4)时**优先出场**;
            // 没配才回落门店分身 —— 存量点位一行都不用改,店分身照旧。两条都只取已过审版本
            // (selectEnabledByScope 自带 audit_status=1 AND enabled=1 闸),缓存按 nodeId 记
            // (节点级内容逐节点不同,不能再按 merchantId 去重)。
            NpcProfileVO shopNpc = read.nodeNpcCache.get(n.getId());
            if (shopNpc == null && !read.nodeNpcCache.containsKey(n.getId())) {
                shopNpc = npcProfileService.resolveForNodeOrMerchant(n.getId(), n.getMerchantMemberId());
                read.nodeNpcCache.put(n.getId(), shopNpc);
            }
            if (shopNpc != null) {
                Map<String, Object> npc = new LinkedHashMap<>();
                npc.put("name", shopNpc.getName());
                npc.put("greeting", shopNpc.getGreeting());
                m.put("npc", npc);
            }
            com.chengyinhub.business.domain.CoopPerk perk =
                    read.perkByChapterMerchant.get(n.getChapterId() + ":" + n.getMerchantMemberId());
            if (perk != null) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", perk.getName());
                pm.put("redeemRule", perk.getRedeemRule());
                pm.put("validEnd", perk.getValidEnd());
                m.put("perk", pm);      // 单价/成本(unitCost/retailValue)不下发:那是结算面的数
            }
        }
        // 节点验证方式（来自模板）：0/null 无需(到点即完成) 1文字 3选项 4扫码 5GPS到达
        Integer validationMethod = null;
        CmsMemberTemplate tpl = null;
        if (n.getTemplateId() != null) {
            tpl = frozenContent ? playNodeContext.frozenTemplateView(n, content)
                    : cmsMemberTemplateService.selectCmsMemberTemplateById(n.getTemplateId());
            if (tpl != null) {
                validationMethod = tpl.getValidationMethod();
                // 答题型节点下发题面（不下发答案/正确项，防作弊）
                if (validationMethod != null && (validationMethod == 1 || validationMethod == 3)) {
                    m.put("question", tpl.getQuestionName());
                    // 题干配图/配音是题面的一部分（看图答题 / 听音答题），与 question 同一档可见性：
                    // 不答题就看不到，答题型节点才下发。答案 questionAnswer/correctAnswer 仍不下发。
                    m.put("questionImg", tpl.getQuestionImg());
                    m.put("questionAudio", tpl.getQuestionAudio());
                }
                if (validationMethod != null && validationMethod == 3) {
                    Map<String, Object> opts = new LinkedHashMap<>();
                    opts.put("A", tpl.getQuestionA());
                    opts.put("B", tpl.getQuestionB());
                    opts.put("C", tpl.getQuestionC());
                    opts.put("D", tpl.getQuestionD());
                    m.put("options", opts);
                    // ⚠️ 另发一个 map，不动 options 的形状 —— play 页已经在消费 {A:"文本"}，
                    //    把它改成对象会让所有旧客户端把 [object Object] 当选项文本渲染出来。
                    m.put("optionMedia", QuestionOptionMedia.parse(tpl.getQuestionOptionMediaJson()));
                }
                // 游戏模板「呈现字段」(游戏启动页/进行页渲染用；敏感答案 questionAnswer/correctAnswer 不下发)
                m.put("gameTitle", tpl.getTitle());
                m.put("duration", tpl.getDuration());
                m.put("difficulty", tpl.getDifficulty());
                m.put("players", tpl.getPlayers());
                m.put("requiredMaterials", tpl.getRequiredMaterials());
                // 承接方现场层:规则怎么讲是商家的事(他的店、他的动线),没配才回落主办方模板。
                // 玩法本身(题/答案/验证方式)不在此列 —— 那是主办方的,见 INodeMerchantOverrideService。
                m.put("ruleInstructions",
                        nodeMerchantOverrideService.forNode(n.getId())
                                .ruleInstructionsOr(tpl.getRuleInstructions()));
                m.put("storyText", tpl.getStoryText());
                m.put("storyImg", tpl.getStoryImg());
                boolean hasHint = StringUtils.isNotEmpty(tpl.getHint1()) || StringUtils.isNotEmpty(tpl.getHint2());
                int configuredHintCount = (StringUtils.isNotEmpty(tpl.getHint1()) ? 1 : 0)
                        + (StringUtils.isNotEmpty(tpl.getHint2()) ? 1 : 0);
                boolean puzzleScoring = read.playMode == PlayMode.LINEAR_ORIENTEERING
                        && validationMethod != null && (validationMethod == 1 || validationMethod == 3);
                if (puzzleScoring) {
                    int usedHintLevel = read.puzzleHintLevelByNode.getOrDefault(n.getId(), 0);
                    List<String> usedHints = new ArrayList<>();
                    if (usedHintLevel >= 1 && StringUtils.isNotEmpty(tpl.getHint1())) usedHints.add(tpl.getHint1());
                    if (usedHintLevel >= 2 && StringUtils.isNotEmpty(tpl.getHint2())) usedHints.add(tpl.getHint2());
                    m.put("puzzleScoring", true);
                    m.put("hintCount", configuredHintCount);
                    m.put("puzzleHintLevel", usedHintLevel);
                    m.put("puzzleScoreCap", PuzzleScorePolicy.solvedScore(usedHintLevel));
                    m.put("usedHints", usedHints);
                }
                if (!puzzleScoring) {
                    if (read.hintCost > 0 && hasHint && !read.hintUnlockedNodeIds.contains(n.getId())) {
                        // 收费开闸且未解锁:hint 明文不下发(同上方答案不下发的防作弊口径),只给锁态元数据
                        m.put("hintLocked", true);
                        m.put("hintCost", read.hintCost);
                        m.put("hintCount", configuredHintCount);
                    } else {
                        m.put("hint1", tpl.getHint1());
                        m.put("hint2", tpl.getHint2());
                    }
                }
                // ★ 2026-09-02 安全修复:答案**不再由剧透状态放行**。
                //
                // 本文件下面(FREE_COLLECT 支)早就写死了这条原则:
                //   「剧透保护管的是**剧情文本**该不该提前看见,不是答案该不该提前拿到 —— 两件事。」
                // 但那句无条件 removeAll 只落在 FREE_COLLECT 分支里,经典定向这一支
                // 走的是 project(),答案由 SpoilerAccessSnapshot 放行 —— 同一条原则只落实了一半。
                //
                // 而 shouldReveal() = revealAll || revealedIds.contains(id),
                // revealAll 来自 POST /api/spoiler/preference,那个端点**只校验登录**
                // (ApiSpoilerController:41-46),零成本、无需报名、与该团毫无关系也能设。
                // ⇒ 任何登录会员翻一下偏好,再 GET /api/play/nodes 就能拿光整团答案,
                //   不扣分、不留痕。而正规路径 POST /api/spoiler/reveal 会 recordReveal
                //   并把本题解谜分记 0(见本文件 revealAnswer:1088-1097)—— 付费闸等于虚设。
                // 更糟的是兜底链 answerReveal → questionAnswer → correctAnswer:
                //   连没写揭示文案的节点也会漏出**标准答案本身**。
                //
                // 列表接口一律只声明「这题受剧透保护 / 你揭晓过没有」,**不带答案正文**。
                // 要正文只有一条路:走 /reveal 端点,记 0 分、留痕。
                if (spoilerProtectionService != null) {
                    Map<String, Object> spoilerState =
                            spoilerProtectionService.project(read.spoilerAccess, n.getId(), null);
                    spoilerState.remove("answerReveal");   // 双保险:传 null 已不会带正文
                    m.putAll(spoilerState);
                }
                m.put("feedbackText", tpl.getFeedbackText());
                m.put("photoRequireDesc", tpl.getPhotoRequireDesc());
                m.put("audioUrl", tpl.getAudioUrl());
                m.put("audioDuration", tpl.getAudioDuration());
                m.put("medalName", tpl.getMedalName());
                m.put("medalStyle", tpl.getMedalStyle() == null ? "glow" : tpl.getMedalStyle());
                m.put("medalImg", tpl.getMedalImg());
                m.put("couponId", tpl.getCouponId());
            }
        }
        return new NodeTask(n, m, validationMethod, tpl);
    }

    void projectFrozenTemplate(NodeProjection read, NodeTask task,
            com.fasterxml.jackson.databind.JsonNode themeNodeConfig) {
        CmsTopicNode n = task.node();
        Map<String, Object> m = task.view();
        Integer validationMethod = task.validationMethod();
        CmsMemberTemplate tpl = task.template();
        m.put("advancedConfigJson", com.chengyinhub.business.util.AdvancedGamePublicProjection
                .safeConfigJson(themeNodeConfig==null?tpl.getAdvancedConfigJson():themeNodeConfig.toString(),
                        read.bargainOffered));
        if (validationMethod != null && (validationMethod == ValidationMethod.APP_SENSOR
                || SensorChallengePolicy.isPlayablePhotoFilter(validationMethod,
                tpl.getSensorType(), tpl.getSensorConfig()))) {
            m.put("sensorType", tpl.getSensorType());
            m.put("sensorConfig", tpl.getSensorConfig());
        }
        // 光有 couponId 说不出「这是什么券」,主题介绍只能写「有优惠券」这种空话 ⇒ 补公开投影
        com.chengyinhub.business.domain.vo.PublicCouponSummaryVO nodeCoupon =
                com.chengyinhub.business.domain.vo.PublicCouponSummaryVO.from(resolveCoupon(tpl.getCouponId()));
        if (nodeCoupon != null) m.put("coupon", nodeCoupon);
    }

    void finishNode(NodeProjection read, NodeTask task) {
        finishNode(read, task, task.node());
    }

    void finishNode(NodeProjection read, NodeTask task, CmsTopicNode currentNode) {
        CmsTopicNode n = task.node();
        Map<String, Object> m = task.view();
        Integer validationMethod = task.validationMethod();
        m.put("validationMethod", validationMethod);
        m.put("needScan", validationMethod != null && validationMethod == 4);
        m.put("needAnswer", validationMethod != null && (validationMethod == 1 || validationMethod == 3));
        m.put("needGps", validationMethod != null && validationMethod == 5);
        m.put("done", read.doneNodeIds.contains(n.getId()));
        m.put("doneAt", read.doneAtMap.get(n.getId())); // 完成时间(epoch),手记按完成顺序排列用
        // 节点完成实际入账探索值:与 advanceProgress 用同一个 resolveNodeXp 真源。
        // 前端节点卡「+N 分」原来写死 12,配了更高探索值的节点会被少报成 12。
        m.put("xp", xpBudgetService == null ? 0 : xpBudgetService.resolveNodeXp(currentNode, read.nodeXpCache));
        PlayerNodeProgress currentProgress = read.progressByNode.get(n.getId());
        m.put("picUrl", currentProgress == null ? null : currentProgress.getPicUrl());
        if (currentProgress != null && currentProgress.getPuzzleScore() != null) {
            m.put("puzzleScore", currentProgress.getPuzzleScore());
            m.put("completionMode", currentProgress.getCompletionMode());
        }
        if (read.playMode == PlayMode.FREE_COLLECT) {
            m.keySet().removeAll(Arrays.asList("answerReveal", "feedbackText", "medalName", "medalStyle",
                    "medalImg", "couponId", "coupon"));
        }
        if (read.playMode == 2 && !playNodeContext.isPureOnlineNode(n)) {
            // S4: 服务端算营业态三态(营业中/即将打烊/已打烊/空),前端地点大卡直接显示(展示字段,与评分排序解耦)
            m.put("openStatus", PlayNarrativeSupport.computeOpenStatus(n.getBusinessTime(), read.nowMinuteOfDay));
            PlayerNodeProgress proof = read.progressByNode.get(n.getId());
            boolean arrived = proof != null && proof.getCheckinTime() != null;
            boolean selfReported = arrived && StringUtils.isNotBlank(proof.getPicUrl());
            RegistrationEntitlement entitlement = read.entitlementByChapter.get(n.getChapterId());
            boolean redeemed = entitlement != null && Integer.valueOf(1).equals(entitlement.getStatus());
            m.put("arrived", arrived);
            m.put("arrivedAt", arrived ? proof.getCheckinTime() : null);
            m.put("selfReported", selfReported);
            // ★status 在 mode2 承载的是「游戏答完没有」(markShopDayGameDone 把 0→1)，不是节点通关。
            //   下发它是为了让答题写操作有权威回读：响应丢了以后，前端只能靠这一位判断
            //   「刚才那道题到底记上没有」，否则只能让用户重发写请求去试。
            m.put("gameDone", proof != null && Integer.valueOf(1).equals(proof.getStatus()));
            m.put("done", redeemed);
            m.put("doneAt", redeemed ? entitlement.getRedeemedAt() : null);
            Integer inStoreLoad = read.loadUnavailable ? null : read.inStoreLoadByChapter.getOrDefault(n.getChapterId(), 0);
            m.put("inStoreLoad", inStoreLoad);
            com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta capacity =
                    read.capacityByChapter.get(n.getChapterId());
            Double perHour = capacity == null ? null : shopDayServiceCapacityPerHour(
                    capacity.getBatchSize(), capacity.getBatchIntervalMinutes());
            boolean capacityComplete = perHour != null
                    && capacity.getEffortMaxConcurrent() != null;
            if (capacityComplete) {
                Map<String, Object> serviceCapacity = new LinkedHashMap<>();
                serviceCapacity.put("batchSize", capacity.getBatchSize());
                serviceCapacity.put("batchIntervalMinutes", capacity.getBatchIntervalMinutes());
                serviceCapacity.put("maxConcurrent", capacity.getEffortMaxConcurrent());
                serviceCapacity.put("perHour", perHour);
                m.put("serviceCapacity", serviceCapacity);
                m.put("expectedWaitMinutes", shopDayExpectedWaitMinutes(inStoreLoad, perHour));
            } else {
                m.put("serviceCapacity", null);
                m.put("expectedWaitMinutes", null);
            }
            if (capacity == null || capacity.getEffortMaxConcurrent() == null) {
                logger.warn("探店日服务并发缺失，拥挤提示回落 4 人 topic={} chapter={}", read.tId, n.getChapterId());
            }
            m.put("crowded", inStoreLoad == null ? null
                    : inStoreLoad >= shopDayCrowdedThreshold(capacity == null
                            ? null : capacity.getEffortMaxConcurrent()));
            m.put("quantityUnavailable", read.loadUnavailable || read.capacityUnavailable || !capacityComplete);
            // B5 收窄(2026-08-15 用户裁决):配了答题模板的节点保留玩法字段,作「扫码进店后的体验层」;
            // 通关判据不变(票侧 isTopicCompleted);未配游戏的节点照旧整组剥离——凭证与记录之外不多给一个字。
            Object vmRaw = m.get("validationMethod");
            int vmVal = vmRaw instanceof Number ? ((Number) vmRaw).intValue() : 0;
            boolean hasGame = StringUtils.isNotBlank((String) m.get("advancedConfigJson"))
                    || vmVal == ValidationMethod.APP_SENSOR
                    || vmVal == ValidationMethod.PREFERENCE_GROUP
                    || SensorChallengePolicy.isPlayablePhotoFilter(vmVal,
                    (String) m.get("sensorType"), (String) m.get("sensorConfig"))
                    || ((vmVal == 1 || vmVal == 3) && m.get("question") != null
                    && StringUtils.isNotBlank(String.valueOf(m.get("question"))));
            if (!hasGame) {
                m.keySet().removeAll(Arrays.asList("question", "options", "gameTitle", "duration", "difficulty",
                        "players", "requiredMaterials", "storyText", "storyImg", "hintLocked", "hintCost",
                        "hintCount", "hint1", "hint2", "answerReveal", "feedbackText", "audioUrl",
                        "audioDuration", "medalName", "medalStyle", "medalImg", "couponId", "coupon",
                        "validationMethod", "needScan", "needAnswer", "needGps", "sensorType", "sensorConfig",
                        "advancedConfigJson"));
            }
            m.put("hasGame", hasGame);
            // ★ 答案类机密对 ③ 一律剥,与进店与否无关(fail-closed)。
            //   TemplateSecrets 把 answerReveal 与 questionAnswer/correctAnswer/hint1/hint2 并列为机密;
            //   B5 只批了「题面与选项」,没批揭示文案 —— 进店后下发等于答题前抓包就能读到答案揭示。
            //   feedbackText(答错时的话术)同源同理,答错时由 /answer 单发,不预置在列表里。
            //   ⚠️ 2026-08-28:本行一度被剧透保护改成 `if (!revealed) remove(...)`,而上面这段
            //   「一律剥 / fail-closed」的注释原样留着 —— 注释说的和代码做的对不上。
            //   `revealed` 来自 member_spoiler_reveal,是**玩家自己**点「我要看」写出来的,
            //   于是玩家点一下揭示就能在作答前拿到答案揭示,这条付费内容闸等于自己开了。
            //   剧透保护管的是**剧情文本**该不该提前看见,不是答案该不该提前拿到 —— 两件事。
            m.keySet().removeAll(Arrays.asList("answerReveal", "feedbackText"));
            // 探店日通关资产由期级奖励链发放；经典节点模板里的勋章/券不是这条产品的承诺。
            m.keySet().removeAll(Arrays.asList("medalName", "medalStyle", "medalImg", "couponId", "coupon"));
            // C6 延伸:未进店不下发规则/拍摄要求,也不下发题面(防转发店内码远程看题;作答另有硬闸)
            if (!arrived) m.keySet().removeAll(Arrays.asList("ruleInstructions", "photoRequireDesc",
                    "question", "options", "gameTitle", "hint1", "hint2", "storyText",
                    "validationMethod", "sensorType", "sensorConfig"));
        }
        read.nodes.add(m);
    }

    Map<String, Object> finishNodes(NodeProjection read) {
        if (read.playMode == PlayMode.FREE_COLLECT) {
            read.nodes.sort(Comparator.comparing(m -> Boolean.TRUE.equals(m.get("crowded"))));
        }

        com.chengyinhub.business.domain.CmsTopic topicForFlow = cmsTopicService.selectCmsTopicById(read.tId);
        // 条件正文 / 条件内心声音按本局标签下发(2026-09-24「选择有回响」)
        java.util.Set<String> storyTags;
        if (read.contentSnapshot != null) {
            storyTags = new java.util.LinkedHashSet<>();
            for (com.fasterxml.jackson.databind.JsonNode tag : read.contentSnapshot.path("contentTags"))
                if (tag.isTextual()) storyTags.add(tag.asText());
        } else storyTags = topicRouteRuntimeService == null ? java.util.Collections.<String>emptySet()
                : topicRouteRuntimeService.tags(read.memberId, read.tId, read.progActId);
        boolean resolveBlocks = ChapterBlockReadSupport.shouldResolveBlocks(
                topicForFlow == null ? null : topicForFlow.getProductType(), null);

        // 章节列表（章节首次过场/叙事用）；routeGeometry 由前端另调 /api/topic/info-to-user 取（决策 B2）
        CmsTopicChapter chapterProbe = new CmsTopicChapter();
        chapterProbe.setTopicId(read.tId);
        List<CmsTopicChapter> chapterList = cmsTopicChapterService.selectCmsTopicChapterList(chapterProbe);
        List<Map<String, Object>> chapters = new ArrayList<>();
        Map<Long,String> originalChapterAudio = new LinkedHashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode content : read.presentedContents.values()) {
            if (!com.chengyinhub.business.service.support.NodeJourneySnapshot.hasContent(content)
                    || !content.path("nodePresentation").hasNonNull("chapterId")) continue;
            Long chapterId=content.path("nodePresentation").path("chapterId").asLong();
            String audio=content.path("chapterAudioUrl").asText(null);
            if (originalChapterAudio.containsKey(chapterId) && !java.util.Objects.equals(originalChapterAudio.get(chapterId),audio))
                throw new IllegalStateException("章节音频快照不一致");
            originalChapterAudio.put(chapterId,audio);
        }
        Map<String, Object> opening = read.routeState == null ? null : read.routeState.getOpening();
        // 结局章不是路线的一章:不进章节列表(不剧透、不被当成下一章),通关后读 /journey/ending 才播
        java.util.Set<Long> endingChapterIds = com.chengyinhub.business.domain.JourneyStory.endingChapterIds(
                read.contentSnapshot != null ? com.chengyinhub.business.service.support.NodeJourneySnapshot.journeyStory(read.contentSnapshot.toString())
                        : topicForFlow == null ? null : topicForFlow.getJourneyStoryJson());
        for (CmsTopicChapter c : chapterList) {
            if (endingChapterIds.contains(c.getId())) continue;
            if (opening != null && String.valueOf(c.getId()).equals(String.valueOf(opening.get("chapterId")))) continue;
            if (opening != null && !Boolean.TRUE.equals(opening.get("complete"))) continue;
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("chapterId", c.getId());
            cm.put("name", c.getName());
            cm.put("description", c.getDescription());
            cm.put("imgArr", c.getImgArr());
            cm.put("totalMileage", c.getTotalMileage());
            cm.put("totalTime", c.getTotalTime());
            cm.put("sortId", c.getSortId());
            cm.put("category", c.getCategory());   // 选章弹窗要显示章节类别(用户裁决)
            cm.put("atmospherePreset", read.playMode == PlayMode.LINEAR_ORIENTEERING
                    ? ChapterAtmospherePreset.normalizeForRead(c.getAtmospherePreset())
                    : ChapterAtmospherePreset.DEFAULT);
            // 章节背景旁白:玩家端进入本章自动播放(2026-09-04 起音频不再是 blocks 里的块)
            cm.put("audioUrl", originalChapterAudio.containsKey(c.getId()) ? originalChapterAudio.get(c.getId()) : c.getAudioUrl());
            if (resolveBlocks && ChapterBlockReadSupport.isRequiredChapter(c)) {
                List<Object> blocks;
                if (read.contentSnapshot == null) blocks=ChapterBlockReadSupport.resolveFromTopicNodes(c,read.nodeList);
                else {
                    blocks=java.util.Collections.emptyList();
                    for (com.fasterxml.jackson.databind.JsonNode chapter : read.contentSnapshot.path("thoughtChapters"))
                        if (chapter.path("chapterId").asLong()==c.getId() && chapter.path("blocks").isArray()) {
                            blocks=new com.fasterxml.jackson.databind.ObjectMapper().convertValue(chapter.path("blocks"),
                                    new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {});
                            break;
                        }
                }
                cm.put("blocks", ChapterBlockReadSupport.projectForPlayer(
                        blocks, read.completedNodeIds, storyTags));
            }
            chapters.add(cm);
        }
        if (opening != null) chapters.add(0, new LinkedHashMap<>(opening));

        // timeErr 已在会话解析时按活动/自玩分别求得
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topicId", read.tId);
        // 通行证首屏那段简介必须是**主题**的,不是第一章的。
        // 自由探索一章一商家,拿 chapters[0].description 当简介 ⇒ 首屏在讲第一家店的事,
        // 而这一屏说的是「四家店、30 天、没有顺序」这件整体的事。
        // topicForFlow 在同一作用域里已经无条件取过同一个 tId,不再查第二次
        if (topicForFlow != null) data.put("topicDesc", topicForFlow.getDescription());
        // 自由探索卡包首屏的底图:主题长图(列表里的封面也是它),前端虚化铺底,没有就退回章节封面
        if (topicForFlow != null) data.put("topicCover", topicForFlow.getImgUrl());
        // 故事开局的「初始状态」页只列作者真开了的精力 / 幸运:回执里的 hp/luck 没开也有值(取配置初始值),看不出开没开
        Map<String, Object> vitalsOn = read.contentSnapshot != null
                ? vitalsOn(com.chengyinhub.business.service.support.NodeJourneySnapshot.journeyRules(read.contentSnapshot.toString()))
                : topicForFlow == null ? null : vitalsOn(topicForFlow.getJourneyRulesJson());
        if (vitalsOn != null) data.put("vitalsOn", vitalsOn);
        com.chengyinhub.business.domain.CmsTopic circleTopic = cmsTopicService.selectCmsTopicById(read.tId);
        if (circleTopic != null && StringUtils.isNotEmpty(circleTopic.getCircleThemeCode())) {
            data.put("circleThemeCode", circleTopic.getCircleThemeCode());
        }
        // 主题画面风格:风格 / 参数 / 转场 / 故事字体,前端 theme-look.js 读不懂就回落默认
        if (cmsTopicMapper != null) data.put("look", cmsTopicMapper.selectLookJsonById(read.tId));
        data.put("mode", read.playMode); // 1经典定向(线性) 2自由定向(全开)
        if (read.playMode == PlayMode.LINEAR_ORIENTEERING) {
            Integer personalBest = playerNodeProgressService.selectPuzzlePersonalBest(read.memberId, read.tId);
            if (personalBest != null) data.put("puzzlePersonalBest", personalBest);
        }
        data.put("playable", read.timeErr == null);
        data.put("timeNote", read.timeErr);
        data.put("total", read.nodeList.size());
        // 自由探索同时包含纯内容完成与商家核销；汇总与本次下发节点的 done 保持一致。
        data.put("doneCount", read.playMode == PlayMode.FREE_COLLECT
                ? read.nodes.stream().filter(n -> Boolean.TRUE.equals(n.get("done"))).count()
                : read.doneNodeIds.size());
        data.put("registered", read.registered); // 前端据此显示"请先报名/购票"空态(不阻断只读查看)
        // 旁观带队:未报名但确是本场领队。前端据此放行页面渲染,不据此发任何玩家权益。
        data.put("leadSpectator", read.leadSpectator);
        // M2 自玩会话:标记 + 通行证有效期(前端顶部"自由定向中·有效期至 M/D")
        data.put("selfPlay", read.selfPlay);
        if (read.selfPlay) {
            data.put("expiresAt", read.passExpiresAt);
        }
        data.put("chapters", chapters);
        data.put("nodes", read.nodes);
        // 规则16:当前可玩集合(nodes/total/doneCount)保持原样;本人已正式完成、但已普通下架的节点
        // 另放 historyNodes 供手记/剧情卡只读回看——不进入 total/完成率/解锁/推荐/地图,也不含任何
        // 可写字段(题面/答案/模板/checkinCode),历史卡没有重新挑战入口。
        List<Map<String, Object>> historyNodes = new ArrayList<>();
        if (!read.completedNodeIds.isEmpty() && !playCompletionFacts.topicHistoryBlocked(read.routeTopic)) {
            List<Long> orderedHistory = new ArrayList<>(read.completedNodeIds);
            orderedHistory.sort(Comparator.comparing(
                    (Long id) -> read.doneAtMap.get(id), Comparator.nullsLast(Comparator.naturalOrder())));
            for (Long completedId : orderedHistory) {
                if (completedId == null || read.playableNodeIds.contains(completedId)) continue;
                CmsTopicNode historyNode = playCompletionFacts.historyReadableNode(completedId, read.routeTopic);
                if (historyNode == null) continue;
                Map<String, Object> hm = new LinkedHashMap<>();
                hm.put("nodeId", historyNode.getId());
                hm.put("name", historyNode.getName());
                hm.put("description", historyNode.getDescription());
                hm.put("imgUrl", historyNode.getImgUrl());
                hm.put("sortId", historyNode.getSortId());
                hm.put("chapterId", historyNode.getChapterId());
                hm.put("fragmentText", historyNode.getFragmentText());
                hm.put("done", true);
                hm.put("doneAt", read.doneAtMap.get(completedId));
                hm.put("history", true);
                historyNodes.add(hm);
            }
        }
        data.put("historyNodes", historyNodes);
        if (read.routeState != null) data.put("routeState", read.routeState);
        // 主题介绍「这趟能带走什么」:主题级通关奖励(徽章 + 券)。
        // 节点级的 medalName/medalImg/couponId 已经在上面每个 node 里下发,这里只补主题这一层。
        // 三项都空就整块不下发 ⇒ 前端不渲染,不出「奖励」标题下面一片空。
        if (read.playMode != PlayMode.FREE_COLLECT) try {
            com.chengyinhub.business.domain.CmsTopic topicForReward = topicForFlow;
            if (topicForReward != null) {
                Map<String, Object> reward = new LinkedHashMap<>();
                if (StringUtils.isNotEmpty(topicForReward.getFinishMedalName())) {
                    reward.put("finishMedalName", topicForReward.getFinishMedalName());
                    reward.put("finishMedalImg", topicForReward.getFinishMedalImg());
                }
                com.chengyinhub.business.domain.vo.PublicCouponSummaryVO finishCoupon =
                        com.chengyinhub.business.domain.vo.PublicCouponSummaryVO.from(
                                resolveCoupon(topicForReward.getCompleteRewardCouponId()));
                if (finishCoupon != null) reward.put("finishCoupon", finishCoupon);
                if (!reward.isEmpty()) data.put("reward", reward);
            }
        } catch (Exception ignore) {
            // 奖励取不到不影响开玩:整块不下发即可,别把 /nodes 拖成 500
        }
        // 故事变量 {名字}(契约 §3.1):B 的替换点是章节全文与节点 story,它们都来自这条回包,
        // 所以同一份 vars 必须挂在这里(只挂 /api/play/advanced 的会话视图,B 拿不到)。
        // 真源 = StoryVars 一个构造函数,两处共用;这里只捞状态,不各拼一份。
        try {
            Map<String, Object> vars = com.chengyinhub.business.util.StoryVars.fromStateJsons(
                    gameRuntimeSessionMapper.selectStateJsonsByTopic(read.memberId, read.tId, read.progActId));
            if (!vars.isEmpty()) data.put("vars", vars);
        } catch (Exception e) {
            // 变量读不出来只是那几处 {xxx} 不替换,不该把整个节点列表拖成 500;warn 留痕,不静默
            logger.warn("故事变量读取失败 member={} topic={} activity={}", read.memberId, read.tId, read.progActId, e);
        }
        // 咔哒(建档题被改写)那句内心声音按站下发(2026-09-24 存档修复):以前只在打开那一站的玩法时
        // 随会话视图回来,重新进页面后故事流里那一句就消失了。best-effort,读不出来只是少一句话。
        try {
            Map<String, Object> voices = new LinkedHashMap<>();
            List<com.chengyinhub.business.domain.GameRuntimeSession> own =
                    gameRuntimeSessionMapper.selectPlayerSessionsByTopic(read.progActId, read.tId, read.memberId);
            for (com.chengyinhub.business.domain.GameRuntimeSession s : own == null
                    ? java.util.Collections.<com.chengyinhub.business.domain.GameRuntimeSession>emptyList() : own) {
                if (s.getNodeId() == null || StringUtils.isBlank(s.getStateJson())) continue;
                com.fasterxml.jackson.databind.JsonNode hits = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(s.getStateJson()).path("profile").path("overrides");
                if (hits.isArray() && hits.size() > 0) voices.put(String.valueOf(s.getNodeId()), hits);
            }
            if (!voices.isEmpty()) data.put("storyVoices", voices);
        } catch (Exception e) {
            logger.warn("咔哒内心声音读取失败 member={} topic={} activity={}", read.memberId, read.tId, read.progActId, e);
        }
        return data;
    }

    /** Request-owned projection values only: no service references or executable continuations. */
    static final class NodeProjection {
        com.fasterxml.jackson.databind.JsonNode contentSnapshot;
        final Map<Long,com.fasterxml.jackson.databind.JsonNode> presentedContents = new LinkedHashMap<>();
        final Long memberId;
        final boolean selfPlay;
        final Long tId;
        final Long progActId;
        final String timeErr;
        final boolean registered;
        final boolean leadSpectator;
        final Date passExpiresAt;
        List<CmsTopicNode> nodeList;
        final List<Long> doneNodeIds;
        final Map<Long, Date> doneAtMap;
        final Map<Long, PlayerNodeProgress> progressByNode;
        final Set<Long> completedNodeIds;
        final int hintCost;
        final Set<Long> hintUnlockedNodeIds;
        final List<Map<String, Object>> nodes;
        final SpoilerAccessSnapshot spoilerAccess;
        final int playMode;
        final com.chengyinhub.business.domain.CmsTopic routeTopic;
        final boolean bargainOffered;
        final TopicRouteState routeState;
        Map<Long, Integer> puzzleHintLevelByNode;
        Map<Long, Integer> inStoreLoadByChapter;
        Map<Long, com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta> capacityByChapter;
        boolean loadUnavailable;
        boolean capacityUnavailable;
        Map<String, com.chengyinhub.business.domain.CoopPerk> perkByChapterMerchant;
        Map<Long, NpcProfileVO> nodeNpcCache;
        Map<Long, RegistrationEntitlement> entitlementByChapter;
        int nowMinuteOfDay;
        java.util.Set<Long> playableNodeIds;
        Map<Long, Integer> nodeXpCache;

        NodeProjection(
                Long memberId,
                boolean selfPlay,
                Long tId,
                Long progActId,
                String timeErr,
                boolean registered,
                boolean leadSpectator,
                Date passExpiresAt,
                List<CmsTopicNode> nodeList,
                List<Long> doneNodeIds,
                Map<Long, Date> doneAtMap,
                Map<Long, PlayerNodeProgress> progressByNode,
                Set<Long> completedNodeIds,
                int hintCost,
                Set<Long> hintUnlockedNodeIds,
                List<Map<String, Object>> nodes,
                SpoilerAccessSnapshot spoilerAccess,
                int playMode,
                com.chengyinhub.business.domain.CmsTopic routeTopic,
                boolean bargainOffered,
                TopicRouteState routeState) {
            this.memberId = memberId;
            this.selfPlay = selfPlay;
            this.tId = tId;
            this.progActId = progActId;
            this.timeErr = timeErr;
            this.registered = registered;
            this.leadSpectator = leadSpectator;
            this.passExpiresAt = passExpiresAt;
            this.nodeList = nodeList;
            this.doneNodeIds = doneNodeIds;
            this.doneAtMap = doneAtMap;
            this.progressByNode = progressByNode;
            this.completedNodeIds = completedNodeIds;
            this.hintCost = hintCost;
            this.hintUnlockedNodeIds = hintUnlockedNodeIds;
            this.nodes = nodes;
            this.spoilerAccess = spoilerAccess;
            this.playMode = playMode;
            this.routeTopic = routeTopic;
            this.bargainOffered = bargainOffered;
            this.routeState = routeState;
        }
    }

    record NodeTask(CmsTopicNode node, Map<String, Object> view,
            Integer validationMethod, CmsMemberTemplate template) { }

    record AssignedRole(String code, String name) { }

    void appendAssignedRole(Map<String, Object> data, AssignedRole role) {
        Map<String, Object> roleView = new LinkedHashMap<>();
        roleView.put("roleCode", role.code());
        roleView.put("roleName", role.name());
        data.put("myRole", roleView);
    }

    /** 分支节点缺少明确可见状态时与 HIDDEN 等价，服务端不把剧情素材交给客户端。 */
    public List<CmsTopicNode> visibleBranchNodes(List<CmsTopicNode> source, TopicRouteState routeState)
    {
        List<CmsTopicNode> visible = new ArrayList<>();
        Map<Long, String> states = routeState == null ? null : routeState.getNodeStates();
        if (source == null || states == null) return visible;
        for (CmsTopicNode node : source) {
            String state = node == null ? null : states.get(node.getId());
            if (TopicRouteState.PLAYABLE.equals(state)
                    || TopicRouteState.DISCOVERED_LOCKED.equals(state)
                    || TopicRouteState.COMPLETED.equals(state)) {
                visible.add(node);
            }
        }
        return visible;
    }

    public static int shopDayCrowdedThreshold(Integer maxConcurrent){
        return maxConcurrent == null ? 4 : Math.max(2, maxConcurrent * 2);
    }

    public static Double shopDayServiceCapacityPerHour(Integer batchSize, Integer batchIntervalMinutes){
        if (batchSize == null || batchSize <= 0 || batchIntervalMinutes == null || batchIntervalMinutes <= 0) {
            return null;
        }
        return batchSize * 60d / batchIntervalMinutes;
    }

    public static Double shopDayExpectedWaitMinutes(Integer inStoreLoad, Double perHour){
        if (inStoreLoad == null || perHour == null || perHour <= 0d) return null;
        return Math.ceil(inStoreLoad / perHour * 60d);
    }

    /** 初始状态开着时,精力 / 幸运各开没开;没开初始状态或规则读不出 → null(前端不出初始状态页里的这两行)。 */
    public static Map<String, Object> vitalsOn(String journeyRulesJson){
        try {
            com.chengyinhub.business.domain.JourneyRules rules = com.chengyinhub.business.domain.JourneyRules.parse(journeyRulesJson);
            if (!rules.isStateEnabled()) return null;
            Map<String, Object> on = new LinkedHashMap<>();
            on.put("hp", rules.getHp().isEnabled());
            on.put("luck", rules.getLuck().isEnabled());
            return on;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}

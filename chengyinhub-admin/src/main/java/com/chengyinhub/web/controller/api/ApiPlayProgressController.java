package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
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
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.UncategorizedDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.PlayerGrowth;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.domain.dto.SensorResultRequest;
import com.chengyinhub.business.domain.dto.PreferenceSubmitRequest;
import com.chengyinhub.business.domain.dto.PreferenceTagCorrectionRequest;
import com.chengyinhub.business.service.support.SensorChallengePolicy;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IPlayerTopicTagService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ISensorChallengeService;
import com.chengyinhub.business.service.ISpoilerProtectionService;
import com.chengyinhub.business.service.ITopicCompletionCouponRewardService;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.TopicRouteModePolicy;
import com.chengyinhub.business.service.IPlayCheckinCodeService;
import com.chengyinhub.business.service.PlayScanEntry;
import com.chengyinhub.business.service.impl.PlayScanEntryService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.business.util.ChapterBlockReadSupport;
import com.chengyinhub.business.util.ChapterAtmospherePreset;
import com.chengyinhub.business.util.QuestionOptionMedia;
import com.chengyinhub.business.util.PlayNarrativeSupport;
import com.chengyinhub.business.util.PuzzleScorePolicy;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import com.chengyinhub.web.service.PreferencePlayService;
import com.chengyinhub.web.service.TopicRouteCompletionCoordinator;

import io.swagger.v3.oas.annotations.Operation;

/**
 * 玩家游玩进度（扫码打卡）
 *
 * @author cy
 * @date 2026-06-19
 */
@RestController
@RequestMapping("/api/play")
public class ApiPlayProgressController extends BaseController
{
    private final TopicCompletionRulePolicy topicCompletionRulePolicy = new TopicCompletionRulePolicy();

    /**
     * 拍照上传事实证明缝:生产校验 picUrl 来自现行上传链、对象真实存在,且上传归属==当前登录身份;
     * 单测可注入替身,但任何注入都不得降级为“跳过校验”。
     */
    private PlayPhotoUploadFactVerifier photoUploadFactVerifier = FileOssUploadUtils::verifyUploadedPhoto;

    @FunctionalInterface
    interface PlayPhotoUploadFactVerifier
    {
        void verify(String picUrl, Long memberId);
    }

    @Autowired
    private com.chengyinhub.business.access.club.ClubEventLeadResolver clubEventLeadResolver;
    @Autowired
    private NpcProfileService npcProfileService;

    @Autowired
    private IAdvancedGameRuntimeService advancedGameRuntimeService;

    @Autowired
    private ICmsActivityService cmsActivityService;

    // M9 埋点:节点级尝试日志(服务端,不依赖客户端)
    @Autowired(required = false)
    private com.chengyinhub.business.mapper.WxContentCallbackInboxMapper wxContentCallbackInboxMapper;

    @Autowired
    private com.chengyinhub.business.mapper.PlayAttemptLogMapper playAttemptLogMapper;

    // M6 随机掉落
    @Autowired
    private com.chengyinhub.business.service.IRewardDropService rewardDropService;

    // M7 XP 预算/节点探索值解析
    @Autowired
    private com.chengyinhub.business.service.IXpBudgetService xpBudgetService;

    // M5 途中彩蛋池

    // S3 途中台词:NPC 人设(活动剧本角色/全局兜底) + 事件话术缓存(位置槽 explore_first/middle/last)
    @Autowired
    private com.chengyinhub.business.mapper.NpcProfileMapper npcProfileMapper;
    @Autowired
    private com.chengyinhub.business.mapper.NpcLineCacheMapper npcLineCacheMapper;

    // P1 俱乐部带领:章节放行强校验
    @Autowired
    private com.chengyinhub.business.mapper.ClubLeadMapper clubLeadMapper;
    @Autowired
    private com.chengyinhub.business.service.IGameSessionRuntimeService gameSessionRuntimeService;

    // 拍板6:节点模板券发给玩家时留核销审计(操作人=玩家,归属=出券商家)
    @Autowired
    private com.chengyinhub.business.mapper.BusinessAuditEventMapper businessAuditEventMapper;

    // M7 探索值入账(双轨:member_growth) + 通关勋章
    @Autowired
    private com.chengyinhub.business.service.IGrowthService growthService;

    /** 宾果:主题级九宫格。⚠️ 只能旁路调用,见下方 refreshBingo 的注释 */
    @Autowired(required = false)
    private com.chengyinhub.business.service.IPlayBingoService playBingoService;
    @Autowired
    private com.chengyinhub.business.service.ICmsTopicService cmsTopicService;
    @Autowired
    private com.chengyinhub.business.mapper.PlayerBadgeMapper playerBadgeMapper;

    /** 主题介绍「这趟能带走什么」要说得出券的名字与面额;投影见 PublicCouponSummaryVO(不下发 code) */
    @Autowired
    private com.chengyinhub.business.service.ISmsCouponService smsCouponService;

    /** 单条主键取券;查不到/查挂了一律 null ⇒ 调用方整块不下发,不让奖励拖垮 /nodes */
    private com.chengyinhub.business.domain.SmsCoupon resolveCoupon(Long couponId) {
        if (couponId == null || couponId <= 0) return null;
        try {
            return smsCouponService.selectSmsCouponById(couponId);
        } catch (Exception e) {
            return null;
        }
    }

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;

    @Autowired
    private ICmsTopicChapterService cmsTopicChapterService;

    @Autowired
    private IOmsTicketService omsTicketService;

    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;

    @Autowired
    private ISpoilerProtectionService spoilerProtectionService;

    @Autowired
    private com.chengyinhub.business.service.IPuzzlePlayService puzzlePlayService;

    @Autowired
    private IPlayerTopicTagService playerTopicTagService;

    @Autowired
    private PreferencePlayService preferencePlayService;

    @Autowired
    private ICmsRegistrationService cmsRegistrationService;

    @Autowired
    private com.chengyinhub.business.mapper.CmsRegistrationMapper cmsRegistrationMapper;

    @Autowired
    private com.chengyinhub.business.mapper.CmsActivityMapper currentPlayActivityMapper;

    /** [P0-17] ③ 通关口径:认核销不认打卡。 */
    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;

    /** 官方活动 V2 只消费本次服务端通关事实，不扫描历史 badge 回填。 */
    @Autowired
    private IOfficialEventV2Service officialEventV2Service;

    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;

    /** 主题级地点路线权威状态；与节点级高级玩法 session 分离。 */
    @Autowired(required = false)
    private ITopicRouteRuntimeService topicRouteRuntimeService;

    @Autowired(required = false)
    private TopicRouteCompletionCoordinator topicRouteCompletionCoordinator;

    @Autowired
    private com.chengyinhub.business.service.IPlayerRoleService playerRoleService;

    @Autowired
    private IPlayerProgressionService playerProgressionService;

    @Autowired
    private IPointsAwardService pointsAwardService;

    @Autowired
    private ISmsCouponHistoryService smsCouponHistoryService;

    @Autowired
    private ITopicCompletionCouponRewardService topicCompletionCouponRewardService;

    /** 节点通关与探店日最终核销共用同一原子副作用闸。 */
    @Autowired
    private ITopicCompletionAwardService topicCompletionAwardService;

    @Autowired
    private IShopDayProofService shopDayProofService;

    @Autowired(required = false)
    private IPlayCheckinCodeService playCheckinCodeService;

    @Autowired
    private PlayScanEntryService playScanEntryService;

    @Autowired
    private ISensorChallengeService sensorChallengeService;

    @Autowired
    private com.chengyinhub.business.mapper.TopicOnsiteMetricsMapper topicOnsiteMetricsMapper;

    @Autowired
    private com.chengyinhub.business.mapper.MerchantChapterOfferMapper merchantChapterOfferMapper;

    @Autowired
    private com.chengyinhub.business.mapper.CoopPerkMapper coopPerkMapper;
    @Autowired
    private com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper topicTemplateMetaMapper;

    /**
     * 故事变量 {名字} 的读取口:某玩家本场次本主题下各节点玩法会话的 state。
     * 变量本身的构造在 {@link com.chengyinhub.business.util.StoryVars}(与 /api/play/advanced
     * 会话视图共用同一个构造函数),这里只负责把状态捞出来喂进去。
     */
    @Autowired
    private com.chengyinhub.business.mapper.GameRuntimeSessionMapper gameRuntimeSessionMapper;

    // 积分解锁提示:规则(cms_points_setting)/余额/消费流水(流水即解锁记录,不建新表)
    @Autowired
    private com.chengyinhub.business.service.ICmsPointsSettingService cmsPointsSettingService;
    @Autowired
    private com.chengyinhub.business.service.IUmsMemberService umsMemberService;
    @Autowired
    private com.chengyinhub.business.service.IUmsMemberPointsDetailService umsMemberPointsDetailService;

    // FE-01 玩家收集(碎片/彩蛋)持久化
    @Autowired
    private com.chengyinhub.business.mapper.MemberCollectibleMapper memberCollectibleMapper;

    // 节点名次奖牌:全平台头三个完成某节点的人拿金/银/铜
    @Autowired
    private com.chengyinhub.business.mapper.NodeMedalClaimMapper nodeMedalClaimMapper;

    // FE-01:幂等收集,同 (member, type, ref) 只存一次;并发唯一键冲突静默兜底。
    private void collect(Long memberId, int type, Long topicId, Long refId, String content) {
        if (memberId == null || memberId <= 0 || refId == null) return;
        if (memberCollectibleMapper.countOne(memberId, type, refId) > 0) return;
        com.chengyinhub.business.domain.MemberCollectible c = new com.chengyinhub.business.domain.MemberCollectible();
        c.setMemberId(memberId);
        c.setCollType(type);
        c.setTopicId(topicId);
        c.setRefId(refId);
        c.setContent(content);
        c.setCreateTime(new Date());
        try { memberCollectibleMapper.insertMemberCollectible(c); } catch (DuplicateKeyException ignore) {}
    }

    /**
     * 某活动对应主题的节点列表 + 玩家每节点打卡状态
     */
    @Operation(summary = "游玩节点列表+进度")
    @GetMapping("/nodes")
    public AjaxResult nodes(Long activityId, Long topicId)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");

        // M2 会话解析:活动会话(activityId>0)或自玩会话(topicId,activity_id=0 主题通行证)
        boolean selfPlay = (activityId == null || activityId == 0L) && topicId != null;
        Long tId;
        Long progActId;
        String timeErr;
        boolean registered;
        boolean leadSpectator = false;
        Date passExpiresAt = null;
        Long clubId = null; // 升级2:俱乐部局判据,响应尾部按它发 myRole
        if (!selfPlay) {
            if (activityId == null) return error("参数有误");
            CmsActivity activity = cmsActivityService.selectCmsActivityById(activityId);
            if (activity == null || activity.getTopicId() == null) return error("活动或主题不存在");
            tId = activity.getTopicId();
            progActId = activityId;
            timeErr = activityTimeError(activity);
            registered = isRegistered(memberId, activityId);
            clubId = activity.getClubId();
            // [F-37] 俱乐部领队可能没报名本场(运营身份,不是玩家)。原先它和普通路人一样被
            // 前端的「你还没有报名这个场次」空态挡在门外,于是发车/广播/放行章节都够不着。
            // 这里只给一个「旁观带队」标记让页面能渲染;registered 仍保持 false ——
            // 奖励、完成度、名额这些都挂在它身上,运营权不等于玩家领奖权。
            leadSpectator = !registered && clubId != null
                    && memberId.equals(clubEventLeadResolver.resolveLeaderMemberId(activity));
        } else {
            tId = topicId;
            progActId = 0L;
            CmsRegistration pass = validSelfPlayPass(memberId, tId);
            // 已开局的本局：通行证开局时有效即可打完，节点列表照常下发。
            if (pass == null && topicRouteRuntimeService != null && topicRouteRuntimeService.hasRunningSession(memberId, tId, 0L)) {
                try { pass = com.chengyinhub.business.service.support.CurrentPlayRegistration.requireToContinue(
                        currentPlayActivityMapper,cmsRegistrationMapper,memberId,1L,tId,null,new Date()); }
                catch (IllegalStateException denied) { pass = null; }
            }
            // C-26:没有(或过期的)自玩通行证不是「未报名」——自玩本来就没有报名这个动作。
            // 回 402 让前端落 needPass 空态并给出获取通行证的出路(与 /api/play 下各写入口同一语义),
            // 不再落到 registered=false 的「你还没有报名这个场次」+「去报名」错误指引。
            if (pass == null) return new AjaxResult(402, "请先购买自玩通行证");
            registered = true;
            passExpiresAt = pass.getExpiresAt();
            timeErr = null; // 自玩无场次时效;有效期由通行证 expires_at 管
        }

        // 节点列表
        CmsTopicNode nodeProbe = new CmsTopicNode();
        nodeProbe.setTopicId(tId);
        nodeProbe.setStatus(1L);
        List<CmsTopicNode> nodeList = cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe);

        // 玩家已打卡集合
        PlayerNodeProgress progProbe = new PlayerNodeProgress();
        progProbe.setMemberId(memberId);
        progProbe.setActivityId(progActId);
        List<PlayerNodeProgress> doneList = playerNodeProgressService.selectPlayerNodeProgressList(progProbe);
        List<Long> doneNodeIds = new ArrayList<>();
        Map<Long, Date> doneAtMap = new LinkedHashMap<>(); // P0 剧情成书:完成时间(前端按"我的完成顺序"排手记)
        Map<Long, PlayerNodeProgress> progressByNode = new HashMap<>();
        Set<Long> completedNodeIds = new HashSet<>(); // 仅 status=1 完成(mode2 进店 status=0 行不算)
        for (PlayerNodeProgress p : doneList) {
            // 完成事实只认 status=1(与 F1/R10-09 同口径):拍照待审(photo_review=1 提交的 status=0 行)
            // 不是完成,不得进 done/doneCount,也不得点亮 pin —— 解锁下一站由 completedNodeIds 管。
            if (p.getStatus() == null || p.getStatus() != 1) continue;
            doneNodeIds.add(p.getNodeId());
            progressByNode.put(p.getNodeId(), p);
            if (p.getCheckinTime() != null) doneAtMap.put(p.getNodeId(), p.getCheckinTime());
            completedNodeIds.add(p.getNodeId());
        }
        // 待审/驳回行仍要进 progressByNode(下发 picUrl 与审核态),但不计入完成
        for (PlayerNodeProgress p : doneList) {
            if (p.getStatus() == null || p.getStatus() != 1) {
                progressByNode.putIfAbsent(p.getNodeId(), p);
            }
        }

        // 积分解锁提示:规则(type=1,event_type=14)分值>0 时对未解锁节点扣发 hint 明文;
        // 已解锁节点(有 eventType=14 消费流水)照常下发。无规则/0分=未开闸,保持免费明文
        // (老客户端提示按钮由 !!hint1 驱动,扣发后按钮自然隐藏=优雅降级,发版窗口不炸)。
        int hintCost = hintUnlockCost();
        java.util.Set<Long> hintUnlockedNodeIds = new java.util.HashSet<>();
        if (hintCost > 0) {
            UmsMemberPointsDetail hp = new UmsMemberPointsDetail();
            hp.setMemberId(memberId);
            hp.setEventType(14L);
            List<UmsMemberPointsDetail> unlockRows = umsMemberPointsDetailService.selectUmsMemberPointsDetailList(hp);
            if (unlockRows != null) {
                for (UmsMemberPointsDetail r : unlockRows) {
                    if (r.getEventId() != null) hintUnlockedNodeIds.add(r.getEventId());
                }
            }
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        SpoilerAccessSnapshot spoilerAccess = spoilerProtectionService == null ? null
                : spoilerProtectionService.snapshot(memberId, ISpoilerProtectionService.CONTENT_NODE);
        int playMode = resolveMode(progActId, tId); // 复用既有 helper,循环内 + 出参共用
        TopicRouteState routeState = null;
        com.chengyinhub.business.domain.CmsTopic routeTopic = cmsTopicService.selectCmsTopicById(tId);
        boolean hasJourneyState = routeTopic != null
                && com.chengyinhub.business.domain.JourneyRules.parse(routeTopic.getJourneyRulesJson()).hasRuntimeState();
        if (registered && topicRouteRuntimeService != null
                && (playMode == PlayMode.LINEAR_ORIENTEERING || hasJourneyState)) {
            try {
                routeState = topicRouteRuntimeService.state(memberId, tId, progActId);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return error(e.getMessage());
            }
        }
        boolean branchGraph = TopicRouteModePolicy.isEffectiveBranchGraph(routeTopic, routeState);
        if (branchGraph) {
            if (!registered) {
                // 未报名没有权威路线局；返回注册空态，但绝不把未选支线素材塞进响应。
                nodeList = new ArrayList<>();
            } else if (routeState == null || !TopicRouteState.BRANCH_GRAPH.equals(routeState.getRouteMode())) {
                return error("路线状态暂时不可用,请重试");
            } else {
                nodeList = visibleBranchNodes(nodeList, routeState);
            }
        }
        Map<Long, Integer> puzzleHintLevelByNode = new HashMap<>();
        if (routeState != null && routeState.getOpening() != null
                && !Boolean.TRUE.equals(routeState.getOpening().get("complete"))) {
            nodeList = visibleBranchNodes(nodeList, routeState);
        }
        if (playMode == PlayMode.LINEAR_ORIENTEERING) {
            try {
                puzzleHintLevelByNode = puzzlePlayService.selectHintLevels(memberId, tId, progActId);
                if (puzzleHintLevelByNode == null) puzzleHintLevelByNode = new HashMap<>();
            } catch (Exception e) {
                logger.error("谜题提示状态批量读取失败 member={} topic={} activity={}", memberId, tId, progActId, e);
                return error("谜题提示状态暂时不可用,请重试");
            }
        }
        Map<Long, Integer> inStoreLoadByChapter = new HashMap<>();
        Map<Long, com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta> capacityByChapter = new HashMap<>();
        boolean loadUnavailable = false;
        boolean capacityUnavailable = false;
        if (playMode == PlayMode.FREE_COLLECT) {
            try {
                for (com.chengyinhub.business.domain.dto.TopicChapterLoadFact load
                        : topicOnsiteMetricsMapper.selectInStoreLoads(tId)) {
                    inStoreLoadByChapter.put(load.getChapterId(), load.getInStoreLoad());
                }
            } catch (Exception e) {
                loadUnavailable = true;
                logger.warn("探店日店内负载读取失败 topic={}", tId, e);
            }
            try {
                for (com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta capacity
                        : topicTemplateMetaMapper.selectChapterMetaByTopicId(tId)) {
                    capacityByChapter.put(capacity.getChapterId(), capacity);
                }
            } catch (Exception e) {
                capacityUnavailable = true;
                logger.warn("探店日服务容量读取失败 topic={}", tId, e);
            }
        }
        // 本店权益:承接商家在报名时挂的 CoopPerk。节点上此前只有券(coupon),而探店日把券整条置空
        // (normNode 里 shopDay ? null : coupon),于是「本店权益」在玩家端没有任何数据源。
        // 一次按章批量取 offer,再按 perkId 去重取券面 —— 四到六家的量,不用再做批量 in 查询。
        Map<String, com.chengyinhub.business.domain.CoopPerk> perkByChapterMerchant = new HashMap<>();
        if (playMode == PlayMode.FREE_COLLECT) {
            try {
                List<Long> chapterIds = new ArrayList<>();
                for (CmsTopicNode n : nodeList) {
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
                            perkByChapterMerchant.put(offer.getChapterId() + ":" + offer.getMerchantId(), perk);
                        }
                    }
                }
            } catch (Exception e) {
                // 权益取不到只让那一段不显示,不能把整个节点列表打挂
                logger.warn("探店日本店权益读取失败 topic={}", tId, e);
            }
        }
        // 节点 NPC 按 nodeId 记一次(CR-927):节点级内容逐节点不同,且一章一商家、四到六家的量,
        // 逐节点 resolve 一条 selectEnabledByScope 是可接受的;同一节点在循环里只出一次。
        Map<Long, NpcProfileVO> nodeNpcCache = new HashMap<>();
        Map<Long, RegistrationEntitlement> entitlementByChapter = new HashMap<>();
        if (playMode == PlayMode.FREE_COLLECT && progActId != null && progActId > 0) {
            CmsRegistration explore = exploreRegistration(memberId, progActId);
            if (explore != null) {
                for (RegistrationEntitlement entitlement : perkLedgerService.listEntitlements(explore.getId())) {
                    entitlementByChapter.put(entitlement.getChapterId(), entitlement);
                }
            }
        }
        Calendar nowCal = Calendar.getInstance();
        int nowMinuteOfDay = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE); // 服务端当前"当日分钟数",算营业态三态用
        java.util.Set<Long> playableNodeIds = new java.util.HashSet<>();
        // 节点探索值真源缓存(模板 xp 按 templateId 去重查询),与 advanceProgress 的 resolveNodeXp 同一口径
        Map<Long, Integer> nodeXpCache = new HashMap<>();
        for (CmsTopicNode n : nodeList) {
            playableNodeIds.add(n.getId());
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
                    && !completedNodeIds.contains(n.getUnlockAfterNodeId()));
            if (playMode == PlayMode.FREE_COLLECT) {
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
                NpcProfileVO shopNpc = nodeNpcCache.get(n.getId());
                if (shopNpc == null && !nodeNpcCache.containsKey(n.getId())) {
                    shopNpc = npcProfileService.resolveForNodeOrMerchant(n.getId(), n.getMerchantMemberId());
                    nodeNpcCache.put(n.getId(), shopNpc);
                }
                if (shopNpc != null) {
                    Map<String, Object> npc = new LinkedHashMap<>();
                    npc.put("name", shopNpc.getName());
                    npc.put("greeting", shopNpc.getGreeting());
                    m.put("npc", npc);
                }
                com.chengyinhub.business.domain.CoopPerk perk =
                        perkByChapterMerchant.get(n.getChapterId() + ":" + n.getMerchantMemberId());
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
            if (n.getTemplateId() != null) {
                CmsMemberTemplate tpl = cmsMemberTemplateService.selectCmsMemberTemplateById(n.getTemplateId());
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
                    boolean puzzleScoring = playMode == PlayMode.LINEAR_ORIENTEERING
                            && validationMethod != null && (validationMethod == 1 || validationMethod == 3);
                    if (puzzleScoring) {
                        int usedHintLevel = puzzleHintLevelByNode.getOrDefault(n.getId(), 0);
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
                        if (hintCost > 0 && hasHint && !hintUnlockedNodeIds.contains(n.getId())) {
                            // 收费开闸且未解锁:hint 明文不下发(同上方答案不下发的防作弊口径),只给锁态元数据
                            m.put("hintLocked", true);
                            m.put("hintCost", hintCost);
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
                                spoilerProtectionService.project(spoilerAccess, n.getId(), null);
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
                    com.fasterxml.jackson.databind.JsonNode themeNodeConfig=topicRouteRuntimeService==null?null:topicRouteRuntimeService.nodeRuntimeConfig(memberId,n.getTopicId(),n.getId(),progActId);
                    m.put("advancedConfigJson", com.chengyinhub.business.util.AdvancedGamePublicProjection
                            .safeConfigJson(themeNodeConfig==null?tpl.getAdvancedConfigJson():themeNodeConfig.toString()));
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
            }
            m.put("validationMethod", validationMethod);
            m.put("needScan", validationMethod != null && validationMethod == 4);
            m.put("needAnswer", validationMethod != null && (validationMethod == 1 || validationMethod == 3));
            m.put("needGps", validationMethod != null && validationMethod == 5);
            m.put("done", doneNodeIds.contains(n.getId()));
            m.put("doneAt", doneAtMap.get(n.getId())); // 完成时间(epoch),手记按完成顺序排列用
            // 节点完成实际入账探索值:与 advanceProgress 用同一个 resolveNodeXp 真源。
            // 前端节点卡「+N 分」原来写死 12,配了更高探索值的节点会被少报成 12。
            m.put("xp", xpBudgetService == null ? 0 : xpBudgetService.resolveNodeXp(n, nodeXpCache));
            PlayerNodeProgress currentProgress = progressByNode.get(n.getId());
            m.put("picUrl", currentProgress == null ? null : currentProgress.getPicUrl());
            if (currentProgress != null && currentProgress.getPuzzleScore() != null) {
                m.put("puzzleScore", currentProgress.getPuzzleScore());
                m.put("completionMode", currentProgress.getCompletionMode());
            }
            if (playMode == PlayMode.FREE_COLLECT) {
                m.keySet().removeAll(Arrays.asList("answerReveal", "feedbackText", "medalName", "medalStyle",
                        "medalImg", "couponId", "coupon"));
            }
            if (playMode == 2 && !isPureOnlineNode(n)) {
                // S4: 服务端算营业态三态(营业中/即将打烊/已打烊/空),前端地点大卡直接显示(展示字段,与评分排序解耦)
                m.put("openStatus", PlayNarrativeSupport.computeOpenStatus(n.getBusinessTime(), nowMinuteOfDay));
                PlayerNodeProgress proof = progressByNode.get(n.getId());
                boolean arrived = proof != null && proof.getCheckinTime() != null;
                boolean selfReported = arrived && StringUtils.isNotBlank(proof.getPicUrl());
                RegistrationEntitlement entitlement = entitlementByChapter.get(n.getChapterId());
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
                Integer inStoreLoad = loadUnavailable ? null : inStoreLoadByChapter.getOrDefault(n.getChapterId(), 0);
                m.put("inStoreLoad", inStoreLoad);
                com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta capacity =
                        capacityByChapter.get(n.getChapterId());
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
                    logger.warn("探店日服务并发缺失，拥挤提示回落 4 人 topic={} chapter={}", tId, n.getChapterId());
                }
                m.put("crowded", inStoreLoad == null ? null
                        : inStoreLoad >= shopDayCrowdedThreshold(capacity == null
                                ? null : capacity.getEffortMaxConcurrent()));
                m.put("quantityUnavailable", loadUnavailable || capacityUnavailable || !capacityComplete);
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
            nodes.add(m);
        }
        if (playMode == PlayMode.FREE_COLLECT) {
            nodes.sort(Comparator.comparing(m -> Boolean.TRUE.equals(m.get("crowded"))));
        }

        com.chengyinhub.business.domain.CmsTopic topicForFlow = cmsTopicService.selectCmsTopicById(tId);
        // 条件正文 / 条件内心声音按本局标签下发(2026-09-24「选择有回响」)
        java.util.Set<String> storyTags = topicRouteRuntimeService == null ? java.util.Collections.<String>emptySet()
                : topicRouteRuntimeService.tags(memberId, tId, progActId);
        boolean resolveBlocks = ChapterBlockReadSupport.shouldResolveBlocks(
                topicForFlow == null ? null : topicForFlow.getProductType(), null);

        // 章节列表（章节首次过场/叙事用）；routeGeometry 由前端另调 /api/topic/info-to-user 取（决策 B2）
        CmsTopicChapter chapterProbe = new CmsTopicChapter();
        chapterProbe.setTopicId(tId);
        List<CmsTopicChapter> chapterList = cmsTopicChapterService.selectCmsTopicChapterList(chapterProbe);
        List<Map<String, Object>> chapters = new ArrayList<>();
        Map<String, Object> opening = routeState == null ? null : routeState.getOpening();
        // 结局章不是路线的一章:不进章节列表(不剧透、不被当成下一章),通关后读 /journey/ending 才播
        java.util.Set<Long> endingChapterIds = com.chengyinhub.business.domain.JourneyStory.endingChapterIds(
                topicForFlow == null ? null : topicForFlow.getJourneyStoryJson());
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
            cm.put("atmospherePreset", playMode == PlayMode.LINEAR_ORIENTEERING
                    ? ChapterAtmospherePreset.normalizeForRead(c.getAtmospherePreset())
                    : ChapterAtmospherePreset.DEFAULT);
            // 章节背景旁白:玩家端进入本章自动播放(2026-09-04 起音频不再是 blocks 里的块)
            cm.put("audioUrl", c.getAudioUrl());
            if (resolveBlocks && ChapterBlockReadSupport.isRequiredChapter(c)) {
                cm.put("blocks", ChapterBlockReadSupport.projectForPlayer(
                        ChapterBlockReadSupport.resolveFromTopicNodes(c, nodeList), completedNodeIds, storyTags));
            }
            chapters.add(cm);
        }
        if (opening != null) chapters.add(0, new LinkedHashMap<>(opening));

        // timeErr 已在会话解析时按活动/自玩分别求得
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topicId", tId);
        // 通行证首屏那段简介必须是**主题**的,不是第一章的。
        // 自由探索一章一商家,拿 chapters[0].description 当简介 ⇒ 首屏在讲第一家店的事,
        // 而这一屏说的是「四家店、30 天、没有顺序」这件整体的事。
        // topicForFlow 在同一作用域里已经无条件取过同一个 tId,不再查第二次
        if (topicForFlow != null) data.put("topicDesc", topicForFlow.getDescription());
        // 自由探索卡包首屏的底图:主题长图(列表里的封面也是它),前端虚化铺底,没有就退回章节封面
        if (topicForFlow != null) data.put("topicCover", topicForFlow.getImgUrl());
        // 故事开局的「初始状态」页只列作者真开了的精力 / 幸运:回执里的 hp/luck 没开也有值(取配置初始值),看不出开没开
        Map<String, Object> vitalsOn = topicForFlow == null ? null : vitalsOn(topicForFlow.getJourneyRulesJson());
        if (vitalsOn != null) data.put("vitalsOn", vitalsOn);
        com.chengyinhub.business.domain.CmsTopic circleTopic = cmsTopicService.selectCmsTopicById(tId);
        if (circleTopic != null && StringUtils.isNotEmpty(circleTopic.getCircleThemeCode())) {
            data.put("circleThemeCode", circleTopic.getCircleThemeCode());
        }
        data.put("mode", playMode); // 1经典定向(线性) 2自由定向(全开)
        if (playMode == PlayMode.LINEAR_ORIENTEERING) {
            Integer personalBest = playerNodeProgressService.selectPuzzlePersonalBest(memberId, tId);
            if (personalBest != null) data.put("puzzlePersonalBest", personalBest);
        }
        data.put("playable", timeErr == null);
        data.put("timeNote", timeErr);
        data.put("total", nodeList.size());
        // 自由探索同时包含纯内容完成与商家核销；汇总与本次下发节点的 done 保持一致。
        data.put("doneCount", playMode == PlayMode.FREE_COLLECT
                ? nodes.stream().filter(n -> Boolean.TRUE.equals(n.get("done"))).count()
                : doneNodeIds.size());
        data.put("registered", registered); // 前端据此显示"请先报名/购票"空态(不阻断只读查看)
        // 旁观带队:未报名但确是本场领队。前端据此放行页面渲染,不据此发任何玩家权益。
        data.put("leadSpectator", leadSpectator);
        // M2 自玩会话:标记 + 通行证有效期(前端顶部"自由定向中·有效期至 M/D")
        data.put("selfPlay", selfPlay);
        if (selfPlay) {
            data.put("expiresAt", passExpiresAt);
        }
        data.put("chapters", chapters);
        data.put("nodes", nodes);
        // 规则16:当前可玩集合(nodes/total/doneCount)保持原样;本人已正式完成、但已普通下架的节点
        // 另放 historyNodes 供手记/剧情卡只读回看——不进入 total/完成率/解锁/推荐/地图,也不含任何
        // 可写字段(题面/答案/模板/checkinCode),历史卡没有重新挑战入口。
        List<Map<String, Object>> historyNodes = new ArrayList<>();
        if (!completedNodeIds.isEmpty() && !topicHistoryBlocked(routeTopic)) {
            List<Long> orderedHistory = new ArrayList<>(completedNodeIds);
            orderedHistory.sort(Comparator.comparing(
                    (Long id) -> doneAtMap.get(id), Comparator.nullsLast(Comparator.naturalOrder())));
            for (Long completedId : orderedHistory) {
                if (completedId == null || playableNodeIds.contains(completedId)) continue;
                CmsTopicNode historyNode = historyReadableNode(completedId, routeTopic);
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
                hm.put("doneAt", doneAtMap.get(completedId));
                hm.put("history", true);
                historyNodes.add(hm);
            }
        }
        data.put("historyNodes", historyNodes);
        if (routeState != null) data.put("routeState", routeState);
        // 主题介绍「这趟能带走什么」:主题级通关奖励(徽章 + 券)。
        // 节点级的 medalName/medalImg/couponId 已经在上面每个 node 里下发,这里只补主题这一层。
        // 三项都空就整块不下发 ⇒ 前端不渲染,不出「奖励」标题下面一片空。
        if (playMode != PlayMode.FREE_COLLECT) try {
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
                    gameRuntimeSessionMapper.selectStateJsonsByTopic(memberId, tId, progActId));
            if (!vars.isEmpty()) data.put("vars", vars);
        } catch (Exception e) {
            // 变量读不出来只是那几处 {xxx} 不替换,不该把整个节点列表拖成 500;warn 留痕,不静默
            logger.warn("故事变量读取失败 member={} topic={} activity={}", memberId, tId, progActId, e);
        }
        // 咔哒(建档题被改写)那句内心声音按站下发(2026-09-24 存档修复):以前只在打开那一站的玩法时
        // 随会话视图回来,重新进页面后故事流里那一句就消失了。best-effort,读不出来只是少一句话。
        try {
            Map<String, Object> voices = new LinkedHashMap<>();
            List<com.chengyinhub.business.domain.GameRuntimeSession> own =
                    gameRuntimeSessionMapper.selectPlayerSessionsByTopic(progActId, tId, memberId);
            for (com.chengyinhub.business.domain.GameRuntimeSession s : own == null
                    ? java.util.Collections.<com.chengyinhub.business.domain.GameRuntimeSession>emptyList() : own) {
                if (s.getNodeId() == null || StringUtils.isBlank(s.getStateJson())) continue;
                com.fasterxml.jackson.databind.JsonNode hits = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(s.getStateJson()).path("profile").path("overrides");
                if (hits.isArray() && hits.size() > 0) voices.put(String.valueOf(s.getNodeId()), hits);
            }
            if (!voices.isEmpty()) data.put("storyVoices", voices);
        } catch (Exception e) {
            logger.warn("咔哒内心声音读取失败 member={} topic={} activity={}", memberId, tId, progActId, e);
        }
        // 升级2:俱乐部局自动分配角色(已报名才发;自玩/公域局不发)。best-effort,不拖垮 /nodes
        if (!selfPlay && clubId != null && registered
                && (gameSessionRuntimeService == null || gameSessionRuntimeService.routeSessionStatus(progActId) == null)) {
            try {
                com.chengyinhub.business.domain.PlayerActivityRole myRole =
                        playerRoleService.assignOrGet(memberId, progActId);
                if (myRole != null) {
                    Map<String, Object> roleView = new LinkedHashMap<>();
                    roleView.put("roleCode", myRole.getRoleCode());
                    roleView.put("roleName", playerRoleService.roleName(myRole.getRoleCode()));
                    data.put("myRole", roleView);
                }
            } catch (Exception e) {
                logger.warn("俱乐部局角色分配失败 activity={} member={}", progActId, memberId, e);
            }
        }
        return AjaxResult.success(data);
    }

    /** 分支节点缺少明确可见状态时与 HIDDEN 等价，服务端不把剧情素材交给客户端。 */
    private List<CmsTopicNode> visibleBranchNodes(List<CmsTopicNode> source, TopicRouteState routeState)
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

    /** 幂等只读恢复 seam；不接受 targetNodeId 或 outcomeCode。 */
    @Operation(summary = "读取主题地点路线状态")
    @GetMapping("/route-state")
    public AjaxResult routeState(@RequestParam(name = "topicId", required = false) Long topicId,
            @RequestParam(name = "activityId", required = false) Long activityId,
            @RequestParam(name = "actId", required = false) Long actId)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        if (topicRouteRuntimeService == null) return error("参数有误");
        try {
            Long effectiveActivityId = activityId != null ? activityId : actId;
            Long effectiveTopicId = topicId;
            if ((effectiveTopicId == null || effectiveTopicId <= 0) && effectiveActivityId != null && effectiveActivityId > 0) {
                CmsActivity activity = cmsActivityService.selectCmsActivityById(effectiveActivityId);
                effectiveTopicId = activity == null ? null : activity.getTopicId();
            }
            if (effectiveTopicId == null || effectiveTopicId <= 0) return error("参数有误");
            return AjaxResult.success(topicRouteRuntimeService.state(memberId, effectiveTopicId,
                    effectiveActivityId == null ? 0L : effectiveActivityId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    public AjaxResult routeState(Long topicId, Long actId) {
        return routeState(topicId, null, actId);
    }

    @Operation(summary = "自由定向:终章结局信(按完成顺序拼合碎片)")
    @GetMapping("/ending")
    public AjaxResult ending(Long activityId, Long topicId)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        // 会话解析(镜像 /nodes:活动会话 or 自玩会话)
        boolean selfPlay = (activityId == null || activityId == 0L) && topicId != null;
        Long tId;
        Long progActId;
        if (!selfPlay) {
            if (activityId == null) return error("参数有误");
            CmsActivity activity = cmsActivityService.selectCmsActivityById(activityId);
            if (activity == null || activity.getTopicId() == null) return error("活动或主题不存在");
            tId = activity.getTopicId();
            progActId = activityId;
        } else {
            tId = topicId;
            progActId = 0L;
        }
        // R10-09 收尾:主题探针(与其它读侧一致)。历史履约:不按公开状态/报名时效/下架字段挡
        // 已有参与者——碎片唯一真源是本人完成行;只有主题行取不到(参数/数据损坏)才清晰失败。
        com.chengyinhub.business.domain.CmsTopic historyTopic = cmsTopicService.selectCmsTopicById(tId);
        if (historyTopic == null) return error("主题不存在");
        // 节点表(sort_id asc)→ 建 id 索引
        CmsTopicNode nodeProbe = new CmsTopicNode();
        nodeProbe.setTopicId(tId);
        nodeProbe.setStatus(1L);
        List<CmsTopicNode> nodeList = cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe);
        Map<Long, CmsTopicNode> byId = new HashMap<>();
        for (CmsTopicNode n : nodeList) byId.put(n.getId(), n);
        // 完成进度:只认 status==1。到店/凭证 status=0 是独立事实(在 /nodes 有 arrived 展示位),
        // 不得拼进「按完成顺序」的碎片;自玩(activity_id=0)按 topicId 收窄,避免多主题共享探针。
        PlayerNodeProgress progProbe = new PlayerNodeProgress();
        progProbe.setMemberId(memberId);
        progProbe.setActivityId(progActId);
        if (selfPlay) progProbe.setTopicId(tId);
        List<PlayerNodeProgress> doneList = playerNodeProgressService.selectPlayerNodeProgressList(progProbe);
        // 访问判断:只看本人事实。无参与事实(无事前通行证/报名、也无本人进度行)→ 空信,
        // 不泄露任何未获得碎片;有过通行证(哪怕已过期)或报名即历史参与者,不被公开资格挡。
        // 治理处置/违规下架的主题:历史读取不得把处置内容重新开放(与普通下架区分)。
        boolean historyBlocked = topicHistoryBlocked(historyTopic);
        boolean participated = !historyBlocked && (!doneList.isEmpty() || (selfPlay
                ? hasSelfPlayRegistration(memberId, tId) : isRegistered(memberId, progActId)));
        List<CmsTopicNode> completedInOrder = new ArrayList<>();
        if (participated) {
            doneList.sort(Comparator.comparing(PlayerNodeProgress::getCheckinTime,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            Set<Long> seenNodes = new HashSet<>();
            for (PlayerNodeProgress p : doneList) {
                if (p.getStatus() == null || p.getStatus() != 1) continue;
                if (p.getNodeId() == null || !seenNodes.add(p.getNodeId())) continue;
                CmsTopicNode n = byId.get(p.getNodeId());
                if (n == null) {
                    // 规则16:普通下架(节点 status=0,行仍在)不挡历史参与者读自己的履约内容;
                    // 只对本人已完成节点按 id 复用已存内容,陌生人/未完成玩家仍不可达。
                    n = historyReadableNode(p.getNodeId(), historyTopic);
                }
                if (n != null) completedInOrder.add(n);
            }
        }
        return AjaxResult.success(PlayNarrativeSupport.buildEnding(completedInOrder));
    }

    /**
     * 规则16 历史履约内容:已完成节点不在在线列表(普通下架/停用 status=0)时,按 id 复用已存节点内容。
     *
     * <p>边界(与删除/违规处置分开):
     * <ul>
     *   <li>物理删除(行取不到)或跨主题节点 → null,不猜内容;</li>
     *   <li>商家节点沿用列表可见性闸同判据(merchant_member_id is null or node_audit_status=1):
     *       待审 0 / 驳回 2 不向历史读取公开,防止草稿与违规内容借历史复活;</li>
     *   <li>只应由「本人 status==1 完成行」驱动调用,未参与玩家不触发此读取。</li>
     * </ul>
     * 内容真源是节点行自身(fragmentText/story 等),不读模板、不重算奖励、不改任何进度。
     */
    private CmsTopicNode historyReadableNode(Long nodeId, com.chengyinhub.business.domain.CmsTopic topic)
    {
        if (topic == null || topicHistoryBlocked(topic)) {
            return null;
        }
        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nodeId);
        if (node == null || node.getTopicId() == null || !node.getTopicId().equals(topic.getId())) {
            return null;
        }
        if (node.getMerchantMemberId() != null) {
            return Integer.valueOf(1).equals(node.getNodeAuditStatus()) ? node : null;
        }
        return node;
    }

    /**
     * 主题是否处于「审核驳回/治理处置/内容安全违规」侧——历史读取必须与之分开:
     * 普通下架(下架但无处置事实)按规则16对历史参与者可读;处置/违规不得借历史读取复活。
     *
     * <p>可靠既有事实:①审核台处置写 status=2 + check_status=2(+reason,见 AuditTaskServiceImpl);
     * ②内容安全回调命中违规自动下架会留 wx_content_callback_inbox(verdict=1)持久记录
     * (WxContentSecurityServiceImpl 仅改 user_status,故必须查该处置事实,不能只看 status)。
     * 处置事实读不到时失败关闭,不把可能违规的内容重新开放。
     */
    private boolean topicHistoryBlocked(com.chengyinhub.business.domain.CmsTopic topic)
    {
        if (topic == null) return true;
        if (Integer.valueOf(2).equals(topic.getStatus())
                || Integer.valueOf(2).equals(topic.getCheckStatus())) {
            return true;
        }
        // 内容安全自动下架是「可逆,仅改状态」:只在当前确处于下架态(user_status=0)时查违规事实;
        // 运营/申诉把 user_status 恢复为 1 后,历史读取随之恢复,不把已撤销的处置当成永久封禁。
        if (!Integer.valueOf(0).equals(topic.getUserStatus())) {
            return false;
        }
        // 下架态必须读处置事实:读取件缺失(mapper 未注入/装配失败)或主题主键缺失
        // 都属于「读不到处置事实」⇒ 失败关闭,不把可能违规的内容借历史读取重新开放(§7-3)。
        if (wxContentCallbackInboxMapper == null || topic.getId() == null) {
            logger.error("内容安全处置事实读取件不可用 topic={},历史读取按失败关闭", topic.getId());
            return true;
        }
        try {
            return wxContentCallbackInboxMapper.countRiskyCallback(
                    IWxContentSecurityService.BIZ_TYPE_TOPIC, topic.getId()) > 0;
        } catch (Exception e) {
            logger.error("内容安全处置事实读取失败 topic={},历史读取按失败关闭", topic.getId(), e);
            return true;
        }
    }

    /** 规则16:普通下架/停用(status 显式非 1)节点不得再产生新完成;无 status 的存量行不在此列。 */
    private boolean isDelistedNode(CmsTopicNode node)
    {
        return node != null && node.getStatus() != null && !Long.valueOf(1L).equals(node.getStatus());
    }

    /**
     * 规则16 下架闸的幂等豁免:本人对该节点已有正式完成行(status=1)时,重试走既有幂等回执。
     * 只用于「已完成节点」的重复提交(回读同一事实、不产生新完成);未完成的首次写入仍被下架闸拦住。
     * 到店/凭证行(status=0,如 FREE_COLLECT 的 recordedVoucher)不算完成,不豁免(维持 §7-1 现状)。
     */
    private boolean hasCompletedNodeFact(Long memberId, String activityId, CmsTopicNode node)
    {
        Long actId = 0L;
        if (StringUtils.isNotBlank(activityId) && !"0".equals(activityId.trim())) {
            try { actId = Long.valueOf(activityId.trim()); }
            catch (NumberFormatException invalid) { return false; }
        }
        return hasCompletedNodeFact(memberId, actId, node);
    }

    /** 已完成事实(Long 版):路线 seam 豁免与入口下架闸必须同源,见 {@link #routeAccessError}。 */
    private boolean hasCompletedNodeFact(Long memberId, Long actId, CmsTopicNode node)
    {
        if (memberId == null || node == null || node.getId() == null) return false;
        PlayerNodeProgress row = findNodeProgressRow(memberId, actId == null ? 0L : actId,
                node.getTopicId(), node.getId());
        return row != null && Integer.valueOf(1).equals(row.getStatus());
    }

    /**
     * 自玩历史履约事实:持有过该主题通行证(owner_type=1 + 报名成功)即算参与者,
     * 不判 expires_at —— 过期仍是历史履约,不能挡住已有完成者读自己的碎片。
     */
    private boolean hasSelfPlayRegistration(Long memberId, Long topicId)
    {
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(1L);
        probe.setOwnerId(topicId);
        probe.setRegistrationStatus(2);
        List<CmsRegistration> regs = cmsRegistrationService.selectCmsRegistrationList(probe);
        return regs != null && !regs.isEmpty();
    }

    /**
     * S3 自由定向途中台词(v1 纯模板,零 LLM):按玩家进度(doneCount/total,与 /nodes 同源)挑位置槽
     * (explore_first/explore_middle/explore_last),查 npc_line_cache 已审话术随机取一句气泡文案。
     * profile 解析:活动会话优先取该活动挂的剧本角色(npc_profile scope_type=1,按 sort 取主角);
     * 自玩会话(无活动挂靠)或该活动未配角色时,兜底用全局 NPC(scope_type=3)。
     * 查不到缓存/未配 NPC 时优雅降级为默认文案,不 500(前端气泡幂等只读展示)。
     */
    @Operation(summary = "自由定向途中台词(NPC气泡)")
    @GetMapping("/companionLine")
    public AjaxResult companionLine(Long activityId, Long topicId)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");

        // 会话解析(镜像 /nodes):活动会话(activityId>0)或自玩会话(topicId,activity_id=0 主题通行证)
        boolean selfPlay = (activityId == null || activityId == 0L) && topicId != null;
        Long tId;
        Long progActId;
        if (!selfPlay) {
            if (activityId == null) return error("参数有误");
            CmsActivity activity = cmsActivityService.selectCmsActivityById(activityId);
            if (activity == null || activity.getTopicId() == null) return error("活动或主题不存在");
            tId = activity.getTopicId();
            progActId = activityId;
        } else {
            if (topicId == null) return error("参数有误");
            tId = topicId;
            progActId = 0L;
        }

        // 进度(与 /nodes 里 doneCount/total 同源)
        CmsTopicNode nodeProbe = new CmsTopicNode();
        nodeProbe.setTopicId(tId);
        nodeProbe.setStatus(1L);
        int total = cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe).size();

        PlayerNodeProgress doneProbe = new PlayerNodeProgress();
        doneProbe.setMemberId(memberId);
        doneProbe.setActivityId(progActId);
        // 自玩会话 activity_id 全部=0,多主题共享同一探针会互相污染 done 计数,须按 topicId 再收窄
        // (与上面 total 的 topic 口径保持一致);活动会话 activityId 天然一对一绑定主题,无需再加
        if (selfPlay) doneProbe.setTopicId(tId);
        // R10-09 收尾:完成槽位只数 status==1 正式完成。到店/凭证 status=0 在 /nodes 有 arrived
        // 展示位,但不能推进「完成比例」槽位(否则只到店就播 middle/last 档台词)。
        int done = 0;
        for (PlayerNodeProgress doneRow : playerNodeProgressService.selectPlayerNodeProgressList(doneProbe)) {
            if (doneRow.getStatus() != null && doneRow.getStatus() == 1) done++;
        }

        String slot = PlayNarrativeSupport.pickExploreSlot(done, total);

        // profile 解析:活动会话取剧本角色(取 sort 最小=主角),否则/查不到则兜底全局 NPC
        Long profileId = null;
        try {
            if (progActId != null && progActId > 0) {
                List<com.chengyinhub.business.domain.NpcProfile> roles =
                        npcProfileMapper.selectEnabledByScope(1, progActId);
                if (roles != null && !roles.isEmpty()) profileId = roles.get(0).getId();
            }
            if (profileId == null) {
                com.chengyinhub.business.domain.NpcProfile global = npcProfileMapper.selectGlobalNpc();
                if (global != null) profileId = global.getId();
            }
        } catch (Exception ignore) {
            // 查表失败:profileId 留空,走下面的默认文案兜底
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("slot", slot);
        data.put("line", defaultCompanionLine(slot));

        if (profileId != null) {
            try {
                List<com.chengyinhub.business.domain.NpcLineCache> caches =
                        npcLineCacheMapper.selectByProfileAndEvent(profileId, slot, null);
                if (caches != null && !caches.isEmpty()) {
                    com.alibaba.fastjson2.JSONArray arr =
                            com.alibaba.fastjson2.JSON.parseArray(caches.get(0).getVariantsJson());
                    if (arr != null && !arr.isEmpty()) {
                        data.put("line", arr.getString(new java.util.Random().nextInt(arr.size())));
                    }
                }
            } catch (Exception ignore) {
                // 查表/解析 variants_json 失败:保留上面的默认兜底文案,不 500
            }
        }
        return AjaxResult.success(data);
    }

    /** S3 途中台词兜底默认文案(查不到 npc_line_cache 或未配 NPC 时使用,别 500) */
    private String defaultCompanionLine(String slot)
    {
        if (PlayNarrativeSupport.SLOT_EXPLORE_MIDDLE.equals(slot)) return "走了一半啦,前面还有好东西";
        if (PlayNarrativeSupport.SLOT_EXPLORE_LAST.equals(slot)) return "就快集齐了,最后一程";
        return "路才刚开始,慢慢逛";
    }

    /**
     * 扫码打卡：扫节点二维码(内容=checkin_code)推进进度
     */
    @Operation(summary = "扫码打卡")
    @PostMapping("/checkin")
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult checkin(String activityId, String code, String topicId,
            String routeActionId, Integer expectedRouteVersion, Long registrationId)
    {
        Long memberId = currentMemberId();
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
            node = nodeList.get(0);
        }
        // 规则16:普通下架/停用节点停止新发现,旧码/旧链接不得再产生新完成;
        // 已正式完成者的幂等重试仍走原回执(与 photo 同口径,§7-2)
        if (isDelistedNode(node) && !hasCompletedNodeFact(memberId, activityId, node)) {
            return error("该站已下架，暂时无法完成");
        }

        // 2-3) 统一上下文校验(活动会话/自玩会话:归属+报名/通行证+时效)
        PlayCtx ctx = resolveCtx(memberId, activityId, topicId, node, registrationId);
        if (ctx.error != null) return ctx.error;
        Long actId = ctx.actId;
        int playMode = resolveMode(actId, ctx.topicId);
        if (playMode == PlayMode.FREE_COLLECT) {
            if (isExploreChapterRedeemed(ctx.registration, node.getChapterId())) {
                return error("该章节已核销，不能补写到店时间");
            }
            if (liveCode) {
                IPlayCheckinCodeService.ConsumeResult consumed = playCheckinCodeService.consume(trimmedCode);
                if (consumed.kind != IPlayCheckinCodeService.ConsumeResult.Kind.OK) return error(consumed.message);
            }
            PlayerNodeProgress proof = shopDayProofService.markArrived(memberId, actId, node);
            recordFirstSuccessfulScan(memberId, ctx);
            return AjaxResult.success("已记录到店", shopDayProofData(node, proof));
        }
        // R18-FREEZE:完成方式闸按开局快照判定(冻结优先,老局快照回退实时模板)
        CmsMemberTemplate checkinTemplate = effectiveTemplate(memberId, ctx.topicId, ctx.actId, node);
        if (isAppSensorNode(node, checkinTemplate)) return error("该站需通过 App 传感器挑战完成");
        if (isPhotoFilterNode(node, checkinTemplate)) return error("该站需通过 App 滤镜相机完成");
        // P1 带领场闸:集合中不可推进;行进中章节须队长已放行
        String leadErr = leadGateError(actId, node);
        if (leadErr != null) return error(leadErr);

        // 3.5) 线性解锁校验（仅 mode=1 经典定向，防扫后面的码跳关）
        String unlockErr = routeAccessError(memberId, actId, ctx.topicId, node, routeActionId);
        if (unlockErr != null) return error(unlockErr);

        if (liveCode) {
            IPlayCheckinCodeService.ConsumeResult consumed = playCheckinCodeService.consume(trimmedCode);
            if (consumed.kind != IPlayCheckinCodeService.ConsumeResult.Kind.OK) return error(consumed.message);
        }
        // 4-5) 记录进度 + 发分 + 通关判定（与答题完成共用）
        logAttempt(memberId, node, actId, 2, 1, null); // M9 扫码打卡成功
        Map<String, Object> data = advanceProgress(memberId, node, actId, null, null, null,
                RewardPolicy.FULL, routeActionId, expectedRouteVersion, null);
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

    private void recordFirstSuccessfulScan(Long memberId, PlayCtx ctx) {
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
    @Operation(summary = "门口游戏码落地")
    @PostMapping("/scan-entry")
    public AjaxResult scanEntry(String code)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        try {
            PlayScanEntry entry = playScanEntryService.resolve(memberId, code);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("action", entry.action);
            data.put("topicId", entry.topicId);
            data.put("nodeId", entry.nodeId);
            if (entry.activityId != null) data.put("activityId", entry.activityId);
            return success(data);
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 答题完成：节点绑定模板的验证方式为 文字(1)/选项(3) 时，提交答案校验通过即推进进度。
     * 复用现有模板引擎（questionAnswer / correctAnswer / feedbackText），不另建节点类型表。
     */
    @Operation(summary = "答题完成(文字/选项)")
    @PostMapping("/answer")
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult answer(String activityId, String nodeId, String answer, String topicId,
            String routeActionId, Integer expectedRouteVersion)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        if (StringUtils.isBlank(nodeId) || (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId))) return error("参数有误");
        Long nId;
        try { nId = Long.valueOf(nodeId); }
        catch (Exception e) { return error("参数有误"); }

        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nId);
        if (node == null) return error("节点不存在");
        // 规则16 下架闸 + 已完成幂等豁免(与 photo 同口径,§7-2):未完成的新提交仍被拦
        if (isDelistedNode(node) && !hasCompletedNodeFact(memberId, activityId, node)) {
            return error("该站已下架，暂时无法完成");
        }

        // M2 统一上下文:活动会话或自玩会话(topicId 二选一);校验/时效/通行证在此收口
        PlayCtx ctx = resolveCtx(memberId, activityId, topicId, node);
        if (ctx.error != null) return ctx.error;
        Long actId = ctx.actId;
        int playMode = resolveMode(actId, ctx.topicId);
        // P1 带领场闸:集合中不可推进;行进中章节须队长已放行
        String leadErr = leadGateError(actId, node);
        if (leadErr != null) return error(leadErr);

        // B5 收窄(2026-08-15):mode2 答题是「扫码进店后的体验层」——未进店不得作答
        // (题面在 /nodes 与 checkin 回包也按进店态下发,这里是防远程作答的硬闸)。
        if (playMode == PlayMode.FREE_COLLECT
                && !isPureOnlineNode(node)
                && !shopDayProofService.hasArrived(memberId, actId, node.getId())) {
            return error("先扫店内静态码进店，再开始任务");
        }

        // 线性解锁校验（仅 mode=1 经典定向）
        String unlockErr = routeAccessError(memberId, actId, ctx.topicId, node, routeActionId);
        if (unlockErr != null) return error(unlockErr);

        // 读节点绑定模板的验证方式 + 校验答案(R18-FREEZE:对错判定按开局快照,新发布不改写
        // 进行中的一局;老局快照没有 nodeAnswers 键时回退实时模板)
        if (node.getTemplateId() == null) return error("该节点未配置答题");
        com.fasterxml.jackson.databind.JsonNode frozenAnswer = frozenAnswer(memberId, ctx.topicId, ctx.actId, node.getId());
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
                    && normalizeAnswer(questionAnswer).equalsIgnoreCase(normalizeAnswer(ans));
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
            logAttempt(memberId, node, actId, 1, 0, ans); // M9 答错(带用户答案供AI评估)
            // 反馈话术也归承接方 —— 玩家是站在他店里听这句话的;展示类不冻结,仍读实时模板
            CmsMemberTemplate feedbackTpl = cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
            String feedback = nodeMerchantOverrideService.forNode(node.getId())
                    .feedbackTextOr(feedbackTpl == null ? null : feedbackTpl.getFeedbackText());
            // 明确标记「这是答案错」:前端据此才计一次错 / 才可能解锁揭示。业务拒绝
            // (活动已结束/未报名/先扫码进店/本章未放行…)不带这个标记,不得被当成答错。
            return AjaxResult.error(StringUtils.isNotBlank(feedback) ? feedback : "答案不正确",
                    java.util.Collections.singletonMap("answerWrong", true));
        }

        logAttempt(memberId, node, actId, 1, 1, null); // M9 答对
        Map<String, Object> data;
        if (playMode == PlayMode.LINEAR_ORIENTEERING) {
            int puzzleScore;
            try {
                puzzleScore = puzzlePlayService.solvedScore(memberId, ctx.topicId, node.getId(), actId);
            } catch (IllegalStateException e) {
                return error(e.getMessage());
            }
            data = advanceProgress(memberId, node, actId, null,
                    puzzleScore, PuzzleScorePolicy.COMPLETION_SOLVED, RewardPolicy.FULL,
                    routeActionId, expectedRouteVersion, null);
        } else {
            data = advanceProgress(memberId, node, actId, null, null, null, RewardPolicy.FULL,
                    routeActionId, expectedRouteVersion, null);
        }
        if (ctx.nightWarning) data.put("nightWarning", true); // M2 自玩夜间提示(不阻断)
        boolean firstTime = Boolean.TRUE.equals(data.get("firstTime"));
        return AjaxResult.success(firstTime ? "答对了！" : "该节点已完成", data);
    }

    public AjaxResult answer(String activityId, String nodeId, String answer, String topicId) {
        return answer(activityId, nodeId, answer, topicId, null, null);
    }

    /** 查看答案以 0 解谜分辅助完成；仅适用于城市定向文字/单选谜题。 */
    @Operation(summary = "城市定向谜题查看答案并辅助完成")
    @PostMapping("/puzzle/reveal")
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult revealPuzzle(String activityId, String nodeId, String topicId,
            String routeActionId, Integer expectedRouteVersion)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        if (StringUtils.isBlank(nodeId) || (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId))) {
            return error("参数有误");
        }
        Long nId;
        try { nId = Long.valueOf(nodeId); }
        catch (Exception e) { return error("参数有误"); }

        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nId);
        if (node == null) return error("节点不存在");
        PlayCtx ctx = resolveCtx(memberId, activityId, topicId, node);
        if (ctx.error != null) return ctx.error;
        if (resolveMode(ctx.actId, ctx.topicId) != PlayMode.LINEAR_ORIENTEERING) {
            return error("该玩法不能查看答案辅助完成");
        }
        String leadErr = leadGateError(ctx.actId, node);
        if (leadErr != null) return error(leadErr);
        String routeErr = routeAccessError(memberId, ctx.actId, ctx.topicId, node, routeActionId);
        if (routeErr != null) return error(routeErr);

        // R18-FREEZE:谜题判定/揭示配置按开局快照,老局快照没有该键时回退实时模板
        com.fasterxml.jackson.databind.JsonNode frozenAnswer = frozenAnswer(memberId, ctx.topicId, ctx.actId, node.getId());
        CmsMemberTemplate tpl = frozenAnswer == null ? puzzlePlayService.selectPuzzleTemplate(node)
                : frozenTemplateView(node, frozenAnswer);
        if (tpl != null && !Integer.valueOf(1).equals(tpl.getValidationMethod())
                && !Integer.valueOf(3).equals(tpl.getValidationMethod())) {
            tpl = null; // 与 selectPuzzleTemplate 同口径:冻结成非文字/选项玩法 = 不是谜题
        }
        if (tpl == null) return error("该节点未配置文字或选项谜题");
        String reveal = puzzlePlayService.revealText(tpl);
        if (StringUtils.isBlank(reveal)) return error("该谜题未配置答案解析");

        try {
            puzzlePlayService.recordReveal(memberId, ctx.topicId, node.getId(), ctx.actId);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        Map<String, Object> data = advanceProgress(memberId, node, ctx.actId, null,
                PuzzleScorePolicy.revealedScore(), PuzzleScorePolicy.COMPLETION_REVEALED,
                RewardPolicy.FULL, routeActionId, expectedRouteVersion, null);
        data.put("answerReveal", reveal);
        if (ctx.nightWarning) data.put("nightWarning", true);
        return AjaxResult.success("已查看答案,本题解谜分为 0", data);
    }

    public AjaxResult revealPuzzle(String activityId, String nodeId, String topicId) {
        return revealPuzzle(activityId, nodeId, topicId, null, null);
    }

    /**
     * 城市定向谜题逐级提示。提示免费，但服务端先记录提示等级，再返回该级正文；
     * 解谜分据这份服务端事实计算，不能由客户端自报。
     */
    @Operation(summary = "城市定向谜题逐级提示")
    @PostMapping("/puzzle/hint")
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult puzzleHint(String activityId, String nodeId, String topicId, Integer level)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        if (StringUtils.isBlank(nodeId) || (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId))
                || level == null || level < 1 || level > 2) return error("参数有误");
        Long nId;
        try { nId = Long.valueOf(nodeId); }
        catch (Exception e) { return error("参数有误"); }

        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nId);
        if (node == null) return error("节点不存在");
        PlayCtx ctx = resolveCtx(memberId, activityId, topicId, node);
        if (ctx.error != null) return ctx.error;
        if (resolveMode(ctx.actId, ctx.topicId) != PlayMode.LINEAR_ORIENTEERING) {
            return error("该玩法暂不支持解谜分提示");
        }
        String leadErr = leadGateError(ctx.actId, node);
        if (leadErr != null) return error(leadErr);
        String routeErr = routeAccessError(memberId, ctx.actId, ctx.topicId, node, null);
        if (routeErr != null) return error(routeErr);

        com.chengyinhub.business.domain.vo.PuzzleHintResult hint;
        try {
            hint = puzzlePlayService.requestHint(memberId, ctx.topicId, node, ctx.actId, level);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", hint.getLevel());
        data.put("hint", hint.getHint());
        data.put("hints", hint.getHints());
        data.put("hintCount", hint.getHintCount());
        data.put("scoreCap", hint.getScoreCap());
        return AjaxResult.success(data);
    }

    @Operation(summary = "下发偏好题组")
    @GetMapping("/preference/{nodeId}")
    public AjaxResult preference(@PathVariable Long nodeId, String activityId, String topicId)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        PreferencePlayCtx pctx = resolvePreferencePlayCtx(memberId, nodeId, activityId, topicId, null);
        if (pctx.error != null) return pctx.error;
        try {
            return AjaxResult.success(preferencePlayService.load(
                    memberId, pctx.playCtx.topicId, nodeId, pctx.template));
        } catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @Operation(summary = "提交偏好题组")
    @PostMapping("/preference/{nodeId}/submit")
    public AjaxResult submitPreference(@PathVariable Long nodeId, @RequestBody PreferenceSubmitRequest request)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        if (request == null) return error("参数有误");
        PreferencePlayCtx pctx = resolvePreferencePlayCtx(memberId, nodeId,
                request.getActivityId(), request.getTopicId(), request.getRouteActionId());
        if (pctx.error != null) return pctx.error;
        // 规则16 下架闸 + 已完成幂等豁免(与 photo 同口径,§7-2):未完成的新提交仍被拦
        if (isDelistedNode(pctx.node)
                && !hasCompletedNodeFact(memberId, request.getActivityId(), pctx.node)) {
            return error("该站已下架，暂时无法完成");
        }
        try {
            Map<String, Object> data = preferencePlayService.submit(memberId, pctx.playCtx.topicId,
                    pctx.playCtx.actId, nodeId, pctx.template, request,
                    outcomeCode -> advanceProgress(memberId, pctx.node, pctx.playCtx.actId,
                            null, null, null, RewardPolicy.FULL, request.getRouteActionId(),
                            request.getExpectedRouteVersion(), outcomeCode));
            return AjaxResult.success(Boolean.TRUE.equals(data.get("needsTiebreak"))
                    ? "请选择一道取舍题" : "偏好结果已生成", data);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "确认偏好标签")
    @PostMapping("/tag/{tagId}/confirm")
    public AjaxResult confirmPreferenceTag(@PathVariable Long tagId)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        try { return AjaxResult.success(playerTopicTagService.confirm(memberId, tagId)); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @Operation(summary = "撤回偏好标签")
    @PostMapping("/tag/{tagId}/revoke")
    public AjaxResult revokePreferenceTag(@PathVariable Long tagId)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        try { return AjaxResult.success(playerTopicTagService.revoke(memberId, tagId)); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @Operation(summary = "确认前修正偏好标签档位")
    @PostMapping("/tag/{tagId}/correct")
    public AjaxResult correctPreferenceTag(@PathVariable Long tagId,
                                           @RequestBody PreferenceTagCorrectionRequest request)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        if (request == null) return error("参数有误");
        try { return AjaxResult.success(preferencePlayService.correctPending(memberId, tagId, request.getTagValue())); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @Operation(summary = "我的新生活操作系统")
    @GetMapping("/os/{topicId}")
    public AjaxResult newLifeOperatingSystem(@PathVariable Long topicId)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        try { return AjaxResult.success(preferencePlayService.operatingSystem(memberId, topicId)); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    private PreferencePlayCtx resolvePreferencePlayCtx(Long memberId, Long nodeId, String activityId, String topicId,
                                                        String routeActionId) {
        PreferencePlayCtx out = new PreferencePlayCtx();
        if (nodeId == null || (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId))) {
            out.error = error("参数有误");
            return out;
        }
        out.node = cmsTopicNodeService.selectCmsTopicNodeById(nodeId);
        if (out.node == null) { out.error = error("节点不存在"); return out; }
        out.playCtx = resolveCtx(memberId, activityId, topicId, out.node);
        if (out.playCtx.error != null) { out.error = out.playCtx.error; return out; }
        String unlockErr = unlockGateError(memberId, out.playCtx.actId, out.node);
        if (unlockErr == null) {
            unlockErr = routeAccessError(memberId, out.playCtx.actId, out.playCtx.topicId, out.node, routeActionId);
        }
        if (unlockErr != null) { out.error = error(unlockErr); return out; }
        String leadErr = leadGateError(out.playCtx.actId, out.node);
        if (leadErr != null) { out.error = error(leadErr); return out; }
        int mode = resolveMode(out.playCtx.actId, out.playCtx.topicId);
        if (mode == PlayMode.FREE_COLLECT
                && !isPureOnlineNode(out.node)
                && !shopDayProofService.hasArrived(memberId, out.playCtx.actId, out.node.getId())) {
            out.error = error("先扫店内静态码进店，再开始任务");
            return out;
        }
        if (out.node.getTemplateId() == null) { out.error = error("该节点未配置偏好题"); return out; }
        // R18-FREEZE:偏好题判定配置(preferenceJson)按开局快照,老局快照没有该键时回退实时模板
        com.fasterxml.jackson.databind.JsonNode frozenAnswer =
                frozenAnswer(memberId, out.playCtx.topicId, out.playCtx.actId, out.node.getId());
        out.template = frozenAnswer == null
                ? cmsMemberTemplateService.selectCmsMemberTemplateById(out.node.getTemplateId())
                : frozenTemplateView(out.node, frozenAnswer);
        if (out.template == null || !Integer.valueOf(ValidationMethod.PREFERENCE_GROUP)
                .equals(out.template.getValidationMethod())) {
            out.error = error("该节点未配置偏好题");
            return out;
        }
        return out;
    }

    private static final class PreferencePlayCtx {
        private CmsTopicNode node;
        private CmsMemberTemplate template;
        private PlayCtx playCtx;
        private AjaxResult error;
    }

    /** 文字答案归一化：去全部空白 + 全角字符→半角，降低误判(选项题不走此归一，保持精确) */
    private String normalizeAnswer(String s)
    {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", "");
        StringBuilder sb = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (c >= '！' && c <= '～') sb.append((char) (c - 0xFEE0)); // 全角ASCII→半角
            else if (c == '　') sb.append(' '); // 全角空格(已被strip,兜底)
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 本局开局的模板判定字段快照(R18-FREEZE):对错 / 揭示 / 偏好 / 传感器配置只看建局冻结值,
     * 创作者之后改模板不影响进行中的局。没有本局(非旅程主题)或老局快照没有 nodeAnswers 键时
     * 返回 null,调用方回退实时模板(兼容,老局行为不变)。
     */
    private com.fasterxml.jackson.databind.JsonNode frozenAnswer(Long memberId, Long topicId, Long activityId, Long nodeId)
    {
        if (topicRouteRuntimeService == null || topicId == null || nodeId == null) return null;
        return topicRouteRuntimeService.nodeAnswers(memberId, topicId, activityId, nodeId);
    }

    /**
     * 用冻结判定字段重建只读模板视图(仅内存对象,不落库):冻结里没有的字段保持 null =
     * 建局时就没有,不回落实时。validationMethod 缺失时留 null,由调用方按原校验拒绝。
     */
    private CmsMemberTemplate frozenTemplateView(CmsTopicNode node,
            com.fasterxml.jackson.databind.JsonNode frozenAnswer)
    {
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setId(node.getTemplateId());
        if (frozenAnswer.hasNonNull("validationMethod")) {
            tpl.setValidationMethod(Integer.valueOf(frozenAnswer.path("validationMethod").asInt()));
        }
        if (frozenAnswer.hasNonNull("photoReview")) {
            tpl.setPhotoReview(Integer.valueOf(frozenAnswer.path("photoReview").asInt()));
        }
        tpl.setQuestionAnswer(frozenAnswer.path("questionAnswer").asText(null));
        tpl.setCorrectAnswer(frozenAnswer.path("correctAnswer").asText(null));
        tpl.setAnswerReveal(frozenAnswer.path("answerReveal").asText(null));
        tpl.setQuestionA(frozenAnswer.path("questionA").asText(null));
        tpl.setQuestionB(frozenAnswer.path("questionB").asText(null));
        tpl.setQuestionC(frozenAnswer.path("questionC").asText(null));
        tpl.setQuestionD(frozenAnswer.path("questionD").asText(null));
        tpl.setPreferenceJson(frozenAnswer.path("preferenceJson").asText(null));
        tpl.setSensorType(frozenAnswer.path("sensorType").asText(null));
        tpl.setSensorConfig(frozenAnswer.path("sensorConfig").asText(null));
        return tpl;
    }

    /** 本局适用的模板判定视图:开局冻结优先,老局快照没有该键时回退实时模板。 */
    private CmsMemberTemplate effectiveTemplate(Long memberId, Long topicId, Long activityId, CmsTopicNode node)
    {
        if (node == null || node.getTemplateId() == null) return null;
        com.fasterxml.jackson.databind.JsonNode frozen = frozenAnswer(memberId, topicId, activityId, node.getId());
        return frozen == null ? cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId())
                : frozenTemplateView(node, frozen);
    }

    @Operation(summary = "App 传感器挑战完成上报")
    @PostMapping("/sensor-result")
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult sensorResult(@RequestBody SensorResultRequest request)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        if (request == null || request.getNodeId() == null || request.getNodeId() <= 0) return error("参数有误");
        boolean hasActivity = request.getActivityId() != null && request.getActivityId() > 0;
        boolean hasTopic = request.getTopicId() != null && request.getTopicId() > 0;
        if (hasActivity == hasTopic) return error("activityId 与 topicId 必须且只能传一个");

        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(request.getNodeId());
        if (node == null || node.getTemplateId() == null) return error("节点不存在或未配置玩法");
        // 规则16 下架闸 + 已完成幂等豁免(与 photo 同口径,§7-2):未完成的新提交仍被拦
        String activityIdArg = hasActivity ? String.valueOf(request.getActivityId()) : null;
        if (isDelistedNode(node) && !hasCompletedNodeFact(memberId, activityIdArg, node)) {
            return error("该站已下架，暂时无法完成");
        }

        PlayCtx ctx = resolveCtx(memberId,
                activityIdArg,
                hasTopic ? String.valueOf(request.getTopicId()) : null, node);
        if (ctx.error != null) return ctx.error;
        String unlockErr = routeAccessError(memberId, ctx.actId, ctx.topicId, node, request.getRouteActionId());
        if (unlockErr != null) return error(unlockErr);
        int mode = resolveMode(ctx.actId, ctx.topicId);
        String leadErr = leadGateError(ctx.actId, node);
        if (leadErr != null) return error(leadErr);
        if (mode == PlayMode.FREE_COLLECT && !isPureOnlineNode(node)
                && !shopDayProofService.hasArrived(memberId, ctx.actId, node.getId())) {
            return error("先扫店内静态码进店，再开始任务");
        }
        // R18-FREEZE:传感器判定配置按开局快照,老局快照没有该键时回退实时模板
        com.fasterxml.jackson.databind.JsonNode frozenAnswer = frozenAnswer(memberId, ctx.topicId, ctx.actId, node.getId());
        CmsMemberTemplate template = frozenAnswer == null
                ? cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId())
                : frozenTemplateView(node, frozenAnswer);
        String sensorError = sensorChallengeService.validateAndAcquire(memberId, ctx.actId, node, template,
                request.getSensorType(), request.getPayload());
        if (sensorError != null) return error(sensorError);

        Map<String, Object> data = advanceProgress(memberId, node, ctx.actId, null, null, null,
                RewardPolicy.XP_BADGE_ONLY, request.getRouteActionId(),
                request.getExpectedRouteVersion(), null);
        if (ctx.nightWarning) data.put("nightWarning", true);
        boolean firstTime = Boolean.TRUE.equals(data.get("firstTime"));
        return AjaxResult.success(firstTime ? "挑战完成" : "该节点已完成", data);
    }

    /**
     * GPS 到达 / 到点即完成：validationMethod=0(无需验证)或 5(GPS到达)的节点走此接口完成。
     * 修「到点即完成」节点此前无完成路径的阻断 bug。
     */
    @Operation(summary = "GPS到达/到点即完成")
    @PostMapping("/arrive")
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult arrive(String activityId, String nodeId, String longitude, String latitude, String topicId,
            String routeActionId, Integer expectedRouteVersion, String accuracy)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        if (StringUtils.isBlank(nodeId) || (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId))) return error("参数有误");
        Long nId;
        try { nId = Long.valueOf(nodeId); }
        catch (Exception e) { return error("参数有误"); }

        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nId);
        if (node == null) return error("节点不存在");
        // 规则16 下架闸 + 已完成幂等豁免(与 photo 同口径,§7-2):未完成的新提交仍被拦
        if (isDelistedNode(node) && !hasCompletedNodeFact(memberId, activityId, node)) {
            return error("该站已下架，暂时无法完成");
        }

        // M2 统一上下文:活动会话或自玩会话(topicId 二选一)
        PlayCtx ctx = resolveCtx(memberId, activityId, topicId, node);
        if (ctx.error != null) return ctx.error;
        Long actId = ctx.actId;
        int mode = resolveMode(actId, ctx.topicId);
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
            if (!isPureOnlineNode(node)) {
                return error("请扫店内的码");
            }
            if (isExploreChapterRedeemed(memberId, actId, node.getChapterId())) {
                return error("该章节已核销，不能补写到店时间");
            }
            PlayerNodeProgress proof = shopDayProofService.markArrived(memberId, actId, node);
            return AjaxResult.success("已完成本节", shopDayProofData(node, proof));
        }
        // P1 带领场闸:集合中不可推进;行进中章节须队长已放行
        String leadErr = leadGateError(actId, node);
        if (leadErr != null) return error(leadErr);

        // 线性解锁校验（仅 mode=1 经典定向；mode=2 自由定向跳过）
        String unlockErr = routeAccessError(memberId, actId, ctx.topicId, node, routeActionId);
        if (unlockErr != null) return error(unlockErr);

        // 验证方式闸：答题(1)/拍照(2)/选项(3)/扫码(4) 类节点必须走对应接口完成，
        // 不能用"到达/到点"绕过任务白拿分+券+勋章+通关(反作弊)。仅 0/null(到点即完成) 或 5(GPS) 放行。
        Integer arriveValidationMethod = null;
        if (node.getTemplateId() != null && node.getTemplateId() > 0L) {
            // R18-FREEZE:完成方式按开局快照判定;老局快照没有该键时回退实时模板
            CmsMemberTemplate vmTpl = effectiveTemplate(memberId, ctx.topicId, ctx.actId, node);
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
        boolean hasNodeCoord = StringUtils.isNotBlank(node.getLongitude()) && StringUtils.isNotBlank(node.getLatitude());
        boolean gpsNode = Integer.valueOf(5).equals(arriveValidationMethod);
        if (gpsNode && !hasNodeCoord) return error("站点未设坐标，暂时无法打卡");
        if (hasNodeCoord) {
            if (StringUtils.isBlank(longitude) || StringUtils.isBlank(latitude)) return error("请开启定位");
            // R10-10:Double.parseDouble 接受 "NaN"/"Infinity",而 NaN 与任何数比较恒 false,
            // 旧写法让无效坐标绕过围栏直接落入 advanceProgress 拿进度和奖励。玩家输入与站点配置
            // 都必须有限且在经纬范围内;距离本身也要有限,任一不满足一律失败关闭。
            Double playerLat = parseGeoCoordinate(latitude, -90d, 90d);
            Double playerLng = parseGeoCoordinate(longitude, -180d, 180d);
            Double nodeLat = parseGeoCoordinate(node.getLatitude(), -90d, 90d);
            Double nodeLng = parseGeoCoordinate(node.getLongitude(), -180d, 180d);
            if (playerLat == null || playerLng == null || nodeLat == null || nodeLng == null) return error("定位无效");
            double dist = haversine(playerLat, playerLng, nodeLat, nodeLng);
            if (!Double.isFinite(dist)) return error("定位无效");
            // B1:定位精度闸(仅坐标判定路径)。accuracy 为端上 wx.getLocation 的水平精度(米);
            // 空白=旧版小程序未上报,放行并留痕;非有限/负数=定位无效;>80m 要求去开阔处或扫码,先于距离判断。
            if (StringUtils.isBlank(accuracy)) {
                logger.info("arrive 未上报定位精度 memberId={} nodeId={}", memberId, nId);
            } else {
                Double acc = parseGeoCoordinate(accuracy, 0d, Double.MAX_VALUE);
                if (acc == null) return error("定位无效");
                if (acc > GEOFENCE_ACCURACY_METERS) return error("定位精度不足，请到开阔处或扫码完成");
            }
            if (dist > GEOFENCE_METERS) return error("还没到这一站附近");
        }

        logAttempt(memberId, node, actId, 3, 1, null); // M9 GPS到达成功
        Map<String, Object> data = advanceProgress(memberId, node, actId, null, null, null,
                RewardPolicy.FULL, routeActionId, expectedRouteVersion, null);
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

    /**
     * 校验提交的 picUrl 是否来自现行上传链且对象真实存在(只用于**首次写入前**)。
     * 无效 → 业务错误(不落进度/不发奖/不清历史);存储/配置类不可用 → 503 可恢复(带栈),
     * 存储恢复后重试同一 URL 幂等放行;其余编程异常按 500 暴露并留栈,不得伪装成可恢复错误
     * 诱导无限重试。
     */
    private AjaxResult verifyPhotoUploadFactOrError(String picUrl, Long memberId)
    {
        try {
            photoUploadFactVerifier.verify(picUrl, memberId);
            return null;
        }
        catch (IllegalArgumentException invalid) {
            String message = StringUtils.isBlank(invalid.getMessage())
                    ? "照片无效，请重新拍摄上传" : invalid.getMessage();
            return error(message);
        }
        catch (IllegalStateException unavailable) {
            logger.error("照片上传事实校验暂不可用", unavailable);
            return AjaxResult.error(503, "照片校验服务暂不可用，请稍后重试");
        }
        catch (RuntimeException unexpected) {
            logger.error("照片上传事实校验失败", unexpected);
            return error("照片校验失败，请稍后重试");
        }
    }

    /**
     * 当前用户在该节点上的权威进度行。自玩(activity_id=0)必须按 topic+node 一并收窄,
     * 防多主题共享同一 node 探针互相污染;活动行由 activityId 天然绑定主题。
     */
    private PlayerNodeProgress findNodeProgressRow(Long memberId, Long actId, Long topicId, Long nodeId)
    {
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        probe.setActivityId(actId == null ? 0L : actId);
        probe.setNodeId(nodeId);
        if (actId == null || actId == 0L) {
            probe.setTopicId(topicId);
        }
        List<PlayerNodeProgress> rows = playerNodeProgressService.selectPlayerNodeProgressList(probe);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 拍照打卡：节点绑定模板验证方式为拍照(2)时，上传照片即完成。
     * 提交即完成 + 存照片 + 置 reviewStatus(自动过=1，留人工审核位)。
     */
    @Operation(summary = "拍照打卡(提交即完成,审核后置)")
    @PostMapping("/photo")
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult photo(String activityId, String nodeId, String picUrl, String topicId,
            String routeActionId, Integer expectedRouteVersion)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        if (StringUtils.isBlank(nodeId) || (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId))) return error("参数有误");
        if (StringUtils.isBlank(picUrl)) return error("请先上传照片");
        Long nId;
        try { nId = Long.valueOf(nodeId); }
        catch (Exception e) { return error("参数有误"); }

        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nId);
        if (node == null) return error("节点不存在");
        // 规则16:下架节点停止新完成;但本人已有正式完成行(照片已完成)时,重试只是回读同一事实,
        // 必须保留幂等回执,不能被下架闸拦成新错误(否则响应丢失后的重发从「该节点已完成」变成失败)。
        if (isDelistedNode(node) && !hasCompletedNodeFact(memberId, activityId, node)) {
            return error("该站已下架，暂时无法完成");
        }
        // M2 统一上下文:活动会话或自玩会话(topicId 二选一)
        PlayCtx ctx = resolveCtx(memberId, activityId, topicId, node);
        if (ctx.error != null) return ctx.error;
        Long actId = ctx.actId;
        // R18-FREEZE:完成方式与审核开关按开局快照判定;老局快照没有该键时回退实时模板
        CmsMemberTemplate photoTemplate = effectiveTemplate(memberId, ctx.topicId, ctx.actId, node);
        boolean photoFilter = photoTemplate != null && SensorChallengePolicy.isPhotoFilter(
                photoTemplate.getValidationMethod(), photoTemplate.getSensorType());
        if (photoFilter) {
            try {
                SensorChallengePolicy.validatePhotoFilterConfig(photoTemplate.getSensorConfig());
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            }
        }
        if (resolveMode(actId, ctx.topicId) == PlayMode.FREE_COLLECT) {
            PlayerNodeProgress proof;
            try {
                if (photoFilter) {
                    String leadErr = leadGateError(actId, node);
                    if (leadErr != null) return error(leadErr);
                    String unlockErr = unlockGateError(memberId, actId, node);
                    if (unlockErr != null) return error(unlockErr);
                }
                if (isExploreChapterRedeemed(memberId, actId, node.getChapterId())) {
                    return error("该章节已核销，不能改写到店凭证");
                }
                if (photoFilter && !shopDayProofService.hasArrived(memberId, actId, node.getId())) {
                    return error("先扫店内静态码进店，再开始任务");
                }
                // 已记录的到店凭证是权威事实:重试(同/异 URL)交给 submitPhoto 既有的幂等/不可覆盖
                // 合同,不再用来源校验打断对象过期或密钥轮换后的正常恢复;首次记录(无 picUrl)仍严验来源。
                PlayerNodeProgress recordedVoucher = findNodeProgressRow(
                        memberId, actId, node.getTopicId(), node.getId());
                if (recordedVoucher == null || StringUtils.isBlank(recordedVoucher.getPicUrl())) {
                    AjaxResult voucherFactError = verifyPhotoUploadFactOrError(picUrl, memberId);
                    if (voucherFactError != null) return voucherFactError;
                }
                proof = shopDayProofService.submitPhoto(memberId, actId, node.getId(), picUrl);
            } catch (IllegalStateException e) {
                return error(e.getMessage());
            }
            if (photoFilter) {
                logAttempt(memberId, node, actId, 4, 1, null);
                // 奖励或路线推进异常必须越过 controller 边界触发事务回滚，不能转成正常 AjaxResult。
                Map<String, Object> data = advanceProgress(memberId, node, actId, picUrl,
                        null, null, RewardPolicy.XP_BADGE_ONLY, routeActionId,
                        expectedRouteVersion, null);
                boolean firstTime = Boolean.TRUE.equals(data.get("firstTime"));
                return AjaxResult.success(firstTime ? "已提交,任务完成" : "该节点已完成", data);
            }
            return AjaxResult.success("到店照片已记录", shopDayProofData(node, proof));
        }
        if (isAppSensorNode(node, photoTemplate)) return error("该站需通过 App 传感器挑战完成");
        if (photoTemplate == null || !Integer.valueOf(ValidationMethod.PHOTO)
                .equals(photoTemplate.getValidationMethod())) {
            return error("该节点不使用拍照完成，请按节点要求验证");
        }
        // P1 带领场闸:集合中不可推进;行进中章节须队长已放行
        String leadErr = leadGateError(actId, node);
        if (leadErr != null) return error(leadErr);
        String unlockErr = routeAccessError(memberId, actId, ctx.topicId, node, routeActionId);
        if (unlockErr != null) return error(unlockErr);
        // 已证明完成的节点允许幂等回执(advanceProgress 的 DuplicateKey→firstTime=false 天然防重发),
        // 不再重复校验来源;未完成必须严验,任意 picUrl 不能首次产生进度或奖励。
        PlayerNodeProgress completedPhotoNode = findNodeProgressRow(memberId, actId, ctx.topicId, node.getId());
        boolean photoNodeAlreadyCompleted = completedPhotoNode != null
                && Integer.valueOf(1).equals(completedPhotoNode.getStatus());
        // 2026-09-17 用户拍板:拍照任务不再有人工审核,一律机审(提交即完成,与 photo_review=0 原行为一致)。
        // 存量待审行(人工审核时代落下的 status=0)在这里按机审通过转正,玩家不再卡「审核中」。
        Long machineApproveProgressId = null;
        if (completedPhotoNode != null && !photoNodeAlreadyCompleted) {
            machineApproveProgressId = completedPhotoNode.getId();
        }
        if (!photoNodeAlreadyCompleted) {
            AjaxResult photoFactError = verifyPhotoUploadFactOrError(picUrl, memberId);
            if (photoFactError != null) return photoFactError;
        }

        // 复用 advanceProgress(幂等/发分/通关),并在首次写入时回填照片与审核位
        logAttempt(memberId, node, actId, 4, 1, null); // M9 拍照打卡成功
        RewardPolicy rewardPolicy = photoFilter
                ? RewardPolicy.XP_BADGE_ONLY : RewardPolicy.FULL;
        Map<String, Object> data = advanceProgress(memberId, node, actId, picUrl, null, null, rewardPolicy,
                routeActionId, expectedRouteVersion, null, machineApproveProgressId);
        if (ctx.nightWarning) data.put("nightWarning", true); // M2 自玩夜间提示(不阻断)
        boolean firstTime = Boolean.TRUE.equals(data.get("firstTime"));
        return AjaxResult.success(firstTime ? "已提交,打卡成功" : "该节点已完成", data);
    }



    public AjaxResult photo(String activityId, String nodeId, String picUrl, String topicId) {
        return photo(activityId, nodeId, picUrl, topicId, null, null);
    }


    private Map<String, Object> shopDayProofData(CmsTopicNode node, PlayerNodeProgress proof) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeId", node.getId());
        data.put("arrived", proof != null && proof.getCheckinTime() != null);
        data.put("arrivedAt", proof == null ? null : proof.getCheckinTime());
        data.put("selfReported", proof != null && StringUtils.isNotBlank(proof.getPicUrl()));
        data.put("done", false);
        if (node.getTemplateId() != null) {
            CmsMemberTemplate template = cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
            if (template != null) {
                // ★ 直接发客户端读的归一化名。这条回包在前端是被裸 Object.assign 合并进 node 的
                //   (merchant/index.js 与 play/index.js 各一处),不过 normNode ——
                //   发原始名 ⇒ 归一化键 rule/photoDesc 仍停在进店前的空串 ⇒ 任务卡与三步条第 2 步恒空,
                //   而没配游戏的店整张任务卡就只有这两样,等于弹一张空卡。
                //   一个字段一个名字:归一化点要么是 normNode(列表),要么是这里(进店回包),没有第三处。
                data.put("rule", nodeMerchantOverrideService.forNode(node.getId())
                        .ruleInstructionsOr(template.getRuleInstructions()));
                data.put("photoDesc", template.getPhotoRequireDesc());
                // B5 收窄:进店回包直接带题面,商家页扫码成功即可弹游戏,不必等 /nodes 重拉。
                // 答案字段(questionAnswer/correctAnswer)照旧绝不下发;收费 hint 不在此下发。
                Integer vm = template.getValidationMethod();
                boolean appGame = SensorChallengePolicy.isAppSensor(vm)
                        || SensorChallengePolicy.isPlayablePhotoFilter(vm,
                        template.getSensorType(), template.getSensorConfig());
                boolean questionGame = vm != null && (vm == 1 || vm == 3)
                        && StringUtils.isNotBlank(template.getQuestionName());
                // 偏好题组也是一类可玩内容，和 App 传感器/答题并列
                boolean preferenceGame = vm != null && vm == ValidationMethod.PREFERENCE_GROUP;
                boolean hasGame = appGame || questionGame || preferenceGame;
                data.put("hasGame", hasGame);
                if (preferenceGame) {
                    // 偏好题组只公开「本站有这么个玩法」；题干与评分分支走进店后的 GET /preference，
                    // 不在这里提前发 preferenceJson（评分口径属于玩家私有，不进公开投影）。
                    data.put("validationMethod", vm);
                }
                if (appGame) {
                    data.put("validationMethod", vm);
                    data.put("sensorType", template.getSensorType());
                    data.put("sensorConfig", template.getSensorConfig());
                }
                if (questionGame) {
                    data.put("gameTitle", template.getTitle());
                    data.put("question", template.getQuestionName());
                    data.put("validationMethod", vm);
                    if (vm == 3) {
                        Map<String, Object> options = new LinkedHashMap<>();
                        options.put("A", template.getQuestionA());
                        options.put("B", template.getQuestionB());
                        options.put("C", template.getQuestionC());
                        options.put("D", template.getQuestionD());
                        data.put("options", options);
                    }
                }
            }
        }
        return data;
    }

    /**
     * 纯线上节点 = 无承接商家 + 无坐标。判据必须三条同时看:
     * 商家节点即使暂缺坐标也不算(到店事实归 /checkin);主办方节点只要有坐标就仍走扫码/围栏。
     */
    private boolean isPureOnlineNode(CmsTopicNode node) {
        return node.getMerchantMemberId() == null
                && StringUtils.isBlank(node.getLongitude())
                && StringUtils.isBlank(node.getLatitude());
    }

    private boolean isExploreChapterRedeemed(Long memberId, Long activityId, Long chapterId) {
        if (activityId == null || activityId <= 0 || chapterId == null) return false;
        return isExploreChapterRedeemed(exploreRegistration(memberId, activityId), chapterId);
    }

    private boolean isExploreChapterRedeemed(CmsRegistration registration, Long chapterId) {
        if (registration == null || chapterId == null
                || !Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(registration.getPurchaseKind())) return false;
        for (RegistrationEntitlement entitlement : perkLedgerService.listEntitlements(registration.getId())) {
            if (chapterId.equals(entitlement.getChapterId()) && Integer.valueOf(1).equals(entitlement.getStatus())) {
                return true;
            }
        }
        return false;
    }

    /** 判定方式只看本局模板视图(开局冻结优先,由 effectiveTemplate 解析),不另读实时模板。 */
    private boolean isAppSensorNode(CmsTopicNode node, CmsMemberTemplate template) {
        if (node == null || node.getTemplateId() == null) return false;
        return template != null && template.getValidationMethod() != null
                && template.getValidationMethod() == ValidationMethod.APP_SENSOR;
    }

    private boolean isPhotoFilterNode(CmsTopicNode node, CmsMemberTemplate template) {
        if (node == null || node.getTemplateId() == null) return false;
        return template != null && SensorChallengePolicy.isPhotoFilter(
                template.getValidationMethod(), template.getSensorType());
    }

    static int shopDayCrowdedThreshold(Integer maxConcurrent) {
        return maxConcurrent == null ? 4 : Math.max(2, maxConcurrent * 2);
    }

    static Double shopDayServiceCapacityPerHour(Integer batchSize, Integer batchIntervalMinutes) {
        if (batchSize == null || batchSize <= 0 || batchIntervalMinutes == null || batchIntervalMinutes <= 0) {
            return null;
        }
        return batchSize * 60d / batchIntervalMinutes;
    }

    static Double shopDayExpectedWaitMinutes(Integer inStoreLoad, Double perHour) {
        if (inStoreLoad == null || perHour == null || perHour <= 0d) return null;
        return Math.ceil(inStoreLoad / perHour * 60d);
    }

    /** GPS 到达围栏半径（米） */
    private static final double GEOFENCE_METERS = 50d;
    /** B1:水平定位精度上限(米),超出则坐标判定不可信。 */
    private static final double GEOFENCE_ACCURACY_METERS = 80d;

    /** mode=1 线性：该节点之前(sortId 更小)的同主题节点是否都已完成（防扫后面的码跳关） */
    private boolean isPrevDone(Long memberId, Long actId, CmsTopicNode node)
    {
        long mySort = node.getSortId() == null ? 0L : node.getSortId();
        CmsTopicNode allProbe = new CmsTopicNode();
        allProbe.setTopicId(node.getTopicId());
        allProbe.setStatus(1L);
        List<CmsTopicNode> all = cmsTopicNodeService.selectCmsTopicNodeList(allProbe);

        PlayerNodeProgress doneProbe = new PlayerNodeProgress();
        doneProbe.setMemberId(memberId);
        doneProbe.setActivityId(actId);
        List<PlayerNodeProgress> done = playerNodeProgressService.selectPlayerNodeProgressList(doneProbe);
        java.util.Set<Long> doneIds = new java.util.HashSet<>();
        // 完成事实只认 status==1:拍照待审(photo_review=1 的 status=0 行)不得据此放行下一站。
        for (PlayerNodeProgress p : done) {
            if (p.getStatus() != null && p.getStatus() == 1) doneIds.add(p.getNodeId());
        }

        for (CmsTopicNode n : all) {
            long s = n.getSortId() == null ? 0L : n.getSortId();
            if (s < mySort && !doneIds.contains(n.getId())) return false;
        }
        return true;
    }

    /** mode=1 的统一准入 seam：LINEAR 适配旧 sortId，BRANCH_GRAPH 只认服务端 routeState。 */
    private String routeAccessError(Long memberId, Long actId, Long topicId, CmsTopicNode node,
                                    String routeActionId)
    {
        // §7-2 幂等豁免必须先于路线 seam:canPlay 的活节点集只取 status=1(TopicRouteRuntimeServiceImpl.nodes),
        // 已被本人正式完成的下架节点会被判「不属于当前主题」⇒ 幂等回执被路线闸吃掉。已完成事实判定放在
        // canPlay 之前;未完成的首次提交在更早的下架闸已被拒,不会借这条路放行新完成。
        if (isDelistedNode(node) && hasCompletedNodeFact(memberId, actId, node)) return null;
        if (resolveMode(actId, topicId) == PlayMode.LINEAR_ORIENTEERING) {
            if (topicRouteRuntimeService != null) {
                // 业务异常交由统一异常处理返回，不能吞掉已将事务标为回滚的异常。
                return topicRouteRuntimeService.canPlay(memberId, topicId, actId, node.getId(), routeActionId)
                        ? null : "该地点尚未解锁";
            }
            if (!isPrevDone(memberId, actId, node)) return "请先完成上一站";
        }
        return unlockGateError(memberId, actId, node);
    }

    /**
     * 节点级前置解锁(unlock_after_node_id):配置了前置的节点,必须先把前置节点玩到「完成」
     * (player_node_progress.status=1;mode2 扫码进店建的 status=0 行不算完成)才能推进本节点。
     * 与 isPrevDone(mode=1 整题线性)互补,任意模式生效。返回 null=放行,非 null=拦截文案。
     */
    private String unlockGateError(Long memberId, Long actId, CmsTopicNode node)
    {
        if (node.getUnlockAfterNodeId() == null || node.getUnlockAfterNodeId() <= 0) return null;
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        probe.setActivityId(actId);
        probe.setNodeId(node.getUnlockAfterNodeId());
        List<PlayerNodeProgress> rows = playerNodeProgressService.selectPlayerNodeProgressList(probe);
        for (PlayerNodeProgress p : rows) {
            if (p.getStatus() != null && p.getStatus() == 1) return null;
        }
        CmsTopicNode prev = cmsTopicNodeService.selectCmsTopicNodeById(node.getUnlockAfterNodeId());
        return prev != null && StringUtils.isNotBlank(prev.getName())
                ? "先完成「" + prev.getName() + "」才能解锁本关"
                : "本关尚未解锁,先完成前置关卡";
    }

    /** 两坐标球面距离（米，Haversine） */
    private double haversine(double lat1, double lng1, double lat2, double lng2)
    {
        double R = 6371000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * 经纬度文本 → 有限且在范围内的 double;NaN、Infinity、越界、坏文本一律 null(调用方失败关闭)。
     * R10-10:Java 的 {@code Double.parseDouble} 接受 "NaN"/"Infinity",而 NaN 的距离比较恒 false。
     */
    private static Double parseGeoCoordinate(String value, double min, double max)
    {
        if (StringUtils.isBlank(value)) return null;
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed) || parsed < min || parsed > max) return null;
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 活动时间闸：未到开始/已过结束返回提示语，可玩返回 null（C5 有效期 gating） */
    private String activityTimeError(CmsActivity activity)
    {
        if (activity == null) return null;
        Date now = new Date();
        if (activity.getStartDate() != null && now.before(activity.getStartDate())) return "活动未开始";
        if (activity.getEndDate() != null && !now.before(activity.getEndDate())) return "活动已结束";
        return null;
    }

    /**
     * 解析主题模式：1=经典定向(线性闯关) 2=自由定向(全开)。
     *
     * <p>[P0-02] 真源是 {@code cms_topic.product_type}。旧实现取「第一张有 mode 的票」,而票列表
     * 当时无 order by ⇒ 玩法模式实际由 InnoDB 扫描路径决定,混票主题下 mode=2 的买家可能被线性闸拦。
     *
     * <p>过渡期双读:product_type 为空(存量未回填 / 混票待人工定型,迁移刻意不静默回填这批)时
     * 回落旧的票 mode 推断,故本方法在迁移落库前后行为都正确。存量清完后可删 fallback。
     */
    private int resolveMode(Long activityId, Long topicId)
    {
        if (topicId != null) {
            com.chengyinhub.business.domain.CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
            if (topic != null && topic.getProductType() != null) {
                return topic.getProductType();
            }
        }
        // fallback:存量未回填 —— 与 P0-02 之前完全一致的行为
        Integer mode = firstTicketMode(2L, activityId);
        if (mode == null && topicId != null) mode = firstTicketMode(1L, topicId);
        return mode == null ? PlayMode.LINEAR_ORIENTEERING : mode;
    }

    private Integer firstTicketMode(Long ownerType, Long ownerId)
    {
        if (ownerId == null) return null;
        OmsTicket probe = new OmsTicket();
        probe.setOwnerType(ownerType);
        probe.setOwnerId(ownerId);
        List<OmsTicket> tickets = omsTicketService.selectOmsTicketList(probe);
        if (tickets == null) return null;
        for (OmsTicket t : tickets) {
            if (t.getMode() != null) return t.getMode();
        }
        return null;
    }

    /**
     * [P0-17] ★ 通关口径的**单一真源**。五个判定点全部经它,别在任何一处重写 `done >= total`。
     *
     * <p><b>③ 认核销不认打卡</b>:一张 ③ 通票的「通关」= 全部必选章都在商家那儿核销过,
     * 与他打了多少卡无关。①(经典定向)与 ②(自玩)按主题 complete_rule_json 判定，空规则仍是全节点。
     *
     * <p>分流判据用 {@code resolveMode}(主题级)而不是 purchase_kind:advanceProgress 手上只有
     * memberId/node/actId,拿 purchase_kind 要多查一次 registration;而 ProductType 的值域
     * 刻意与 PlayMode 同值(1/2),派生是恒等映射 ⇒ resolveMode==FREE_COLLECT 就等价于「主题是 ③」。
     * fallback 兜底方向对本处是安全的:product_type 为 NULL 时兜底返 1(线性)= 判不清就走
     * 主题规则为空时回落全节点老口径，不会误把存量主题切到 isExploreCompleted 而永不通关。
     * ⚠️ 已知残留风险(生产实测只涉及 3 个主题):product_type 为 NULL 的**混票**主题里,
     * firstTicketMode 可能返 2 ⇒ ① 单被判成 ③ ⇒ 若 redemption 链没通就永不通关。
     *
     * <p><b>拿不到 ③ 的报名单时回落老口径</b>,不是判 false —— 判 false 会让「主题是 ③ 但这人
     * 走的是别的路子进来玩」的场景永久卡死;回落老口径至少行为与今天一致。
     */
    private boolean isTopicCompleted(Long memberId, Long actId, Long topicId, int total, int done)
    {
        if (resolveMode(actId, topicId) != PlayMode.FREE_COLLECT) {
            com.chengyinhub.business.domain.CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
            return topicCompletionRulePolicy.isCompleted(
                    topic == null ? null : topic.getCompleteRuleJson(), total, done);
        }
        return isTopicCompletedFor(exploreRegistration(memberId, actId), topicId, total, done);
    }

    /**
     * [P0-17] 同一口径的**列表版**:③ 的报名单由调用方批量捞好后传进来。
     *
     * <p>为什么列表场景不走 {@link #isTopicCompleted}:那个版本内部会调 resolveMode(每个不同主题
     * 一次 selectCmsTopicById)+ exploreRegistration(每个活动一次查询)= 妥妥的 N+1。
     * 而列表侧**为了拿 registrationId 本来就要读 registration** ⇒ purchase_kind 随行免费
     * ⇒ 「explore != null」本身就等价于「purchase_kind == EXPLORE_PASS」,不必再查主题。
     * (这与 advanceProgress 那处的结论相反,因为成本方向反了 —— 那处手上有 topicId 没 registration。)
     *
     * <p>⚠️ 已知回落:purchase_kind 为 NULL 的单在这里会被当 ①(explore 取不到)⇒ 走主题节点规则。
     * 可接受且方向正确 —— NULL 的三个来源(存量未回填 / 后台手工建单 / 独立活动无 topicId)
     * 都不会产生「带 entitlement 的 ③ 活动单」:后台手工建单走 insertCmsRegistration,
     * 压根不经 reserveEntitlements ⇒ 它没有 entitlement ⇒ 就算判成 ③ 也恒不通关,回落老口径反而更对。
     */
    private boolean isTopicCompletedFor(CmsRegistration explore, Long topicId, int total, int done)
    {
        if (explore == null) {
            com.chengyinhub.business.domain.CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
            return topicCompletionRulePolicy.isCompleted(
                    topic == null ? null : topic.getCompleteRuleJson(), total, done);
        }
        return perkLedgerService.isExploreCompleted(explore.getId(), topicId);
    }

    /**
     * /my-completed 读侧完成事实的单一入口(独立审查 S1):活动与自玩两个循环共用分流,
     * 分支图/会话在线一律看 TopicRouteSession.COMPLETED,线性回落 {@link #isTopicCompletedFor}
     * (③ 看核销,其余看主题 complete_rule_json)。别再各写一份分流,加口径时只改这一处。
     */
    private boolean isCompletedCompletionFact(com.chengyinhub.business.domain.CmsTopic topic,
            Integer routeStatus, CmsRegistration explore, Long topicId, int total, int done)
    {
        if (topic != null && Integer.valueOf(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE)
                .equals(topic.getProductType())
                && !TopicRouteModePolicy.isEffectiveBranchGraph(topic, null)) {
            return isTopicCompletedFor(explore, topicId, total, done);
        }
        if (TopicRouteModePolicy.isEffectiveBranchGraph(topic, null) || routeStatus != null) {
            return Integer.valueOf(TopicRouteSession.STATUS_COMPLETED).equals(routeStatus);
        }
        return isTopicCompletedFor(explore, topicId, total, done);
    }

    /**
     * [P0-17] 该玩家全部 ③ 报名单,按 activityId 索引。**一次查询,与活动数无关**。
     *
     * <p>selectCmsRegistrationList 的 where 全是 &lt;if&gt; ⇒ 支持只给 memberId + ownerType +
     * registrationStatus 而不给 ownerId。⚠️ 别循环调 isRegistered(它加了 ownerId 且只返 boolean)
     * —— 那才是 N+1。
     *
     * <p>同一 (member, activity) 可能有多行(跨订单重复购买;整单闸只压住「同一单多张」)
     * ⇒ 口径定死:**任一 ③ 单通关即算通关**,这里保留先到的那张。
     */
    /**
     * [P0-17] 该玩家在该活动下的 ③ 报名单;无则 null。
     *
     * <p>③ 定死是 {@code owner_type=2 / owner_id=activityId} 的活动单 —— createRegistration
     * 全程不 setTopicId(全类只有 createSelfPlayPass 设它),且 ApiRegistrationController 把
     * ownerType==1 无条件 early-return 进 createSelfPlayPass ⇒ 「owner_type=1 + ticketId」
     * 那条路从 API 根本不可达。所以探针固定 ownerType=2 就够。
     *
     * <p>⚠️ 同一 (member, activity) 可能有**多行** registration(跨订单重复购买;决策 A 的整单闸
     * 只压住「同一单多张」)⇒ 口径定死:**任一 ③ 单通关即算通关**(与 validSelfPlayPass
     * 「遍历取第一个有效」同风格)。这里返回第一张 ③ 单。
     */
    private CmsRegistration exploreRegistration(Long memberId, Long actId)
    {
        if (memberId == null || actId == null || actId == 0L) {
            return null;   // 自玩会话 actId 恒 0,不走 ③
        }
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(2L);
        probe.setOwnerId(actId);
        probe.setRegistrationStatus(2); // 2=报名成功(已支付)
        List<CmsRegistration> regs = cmsRegistrationService.selectCmsRegistrationList(probe);
        if (regs == null) {
            return null;
        }
        for (CmsRegistration r : regs) {
            if (r.getPurchaseKind() != null && r.getPurchaseKind() == PurchaseKind.EXPLORE_PASS) {
                return r;
            }
        }
        return null;
    }

    /** 玩家是否已报名成功该活动 */
    private boolean isRegistered(Long memberId, Long actId)
    {
        CmsRegistration regProbe = new CmsRegistration();
        regProbe.setMemberId(memberId);
        regProbe.setOwnerType(2L); // 2=活动
        regProbe.setOwnerId(actId);
        regProbe.setRegistrationStatus(2); // 2=报名成功
        List<CmsRegistration> regs = cmsRegistrationService.selectCmsRegistrationList(regProbe);
        return regs != null && !regs.isEmpty();
    }

    /** 旧入口无指定票时沿用报名查询的 id desc 顺序；有指定票则仅用本次票夹中的本人订单。 */
    private CmsRegistration activityRegistration(Long memberId, Long actId, Long registrationId) {
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(2L);
        probe.setOwnerId(actId);
        probe.setRegistrationStatus(2);
        List<CmsRegistration> registrations = cmsRegistrationService.selectCmsRegistrationList(probe);
        if (registrations != null) {
            for (CmsRegistration registration : registrations) {
                if (registration != null && (registrationId == null || registrationId.equals(registration.getId()))) {
                    return registration;
                }
            }
        }
        return null;
    }

    /** M2 自玩:返回该主题的有效通行证(owner_type=1 主题通行证 + 报名成功 + 未过期);无则 null */
    private CmsRegistration validSelfPlayPass(Long memberId, Long topicId)
    {
        return validSelfPlayPass(memberId, topicId, null);
    }

    private CmsRegistration validSelfPlayPass(Long memberId, Long topicId, Long registrationId)
    {
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(1L); // 1=主题(自玩通行证)
        probe.setOwnerId(topicId);
        probe.setRegistrationStatus(2); // 报名成功=已支付
        List<CmsRegistration> regs = cmsRegistrationService.selectCmsRegistrationList(probe);
        if (regs == null) return null;
        Date now = new Date();
        for (CmsRegistration r : regs) {
            // expires_at 空=不过期(兼容旧数据);非空须晚于现在
            if ((registrationId == null || registrationId.equals(r.getId()))
                    && (r.getExpiresAt() == null || r.getExpiresAt().after(now))) return r;
        }
        return null;
    }

    /** M2 夜间(22:00-06:00)提示位:自玩会话返回给前端提示不阻断 */
    private boolean isNightNow()
    {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 22 || h < 6;
    }

    /** M2 播放上下文:活动会话(actId>0)或自玩会话(actId=0,校验主题通行证)。error 非空即应直接返回。 */
    private static class PlayCtx {
        Long actId;
        Long topicId;
        boolean selfPlay;
        boolean nightWarning;
        boolean continuing; // 自玩本局已开局：通行证中途到期不打断
        CmsRegistration registration;
        AjaxResult error;
    }

    /**
     * 统一解析播放上下文(M2 topicId 二选一,activityId 优先)。
     * 活动分支:完整复用原活动会话校验(存在/归属/报名/时效)。
     * 自玩分支:校验主题通行证(未过期),actId=0,夜间打提示位。
     */
    private PlayCtx resolveCtx(Long memberId, String activityId, String topicId, CmsTopicNode node)
    {
        return resolveCtx(memberId, activityId, topicId, node, null);
    }

    private PlayCtx resolveCtx(Long memberId, String activityId, String topicId, CmsTopicNode node,
            Long registrationId)
    {
        PlayCtx c = new PlayCtx();
        if (StringUtils.isNotBlank(activityId) && !"0".equals(activityId.trim())) {
            // 活动会话(原逻辑,原样保留)
            Long actId;
            try { actId = Long.valueOf(activityId); } catch (Exception e) { c.error = error("参数有误"); return c; }
            CmsActivity activity = cmsActivityService.selectCmsActivityById(actId);
            if (activity == null || activity.getTopicId() == null) { c.error = error("活动不存在"); return c; }
            if (!activity.getTopicId().equals(node.getTopicId())) { c.error = error("该节点不属于本次活动"); return c; }
            CmsRegistration ticket;
            try { ticket=com.chengyinhub.business.service.support.CurrentPlayRegistration.require(
                    currentPlayActivityMapper,cmsRegistrationMapper,memberId,2L,actId,registrationId,new Date()); }
            catch(IllegalStateException denied) { c.error=error(denied.getMessage());return c; }
            if (ticket == null) { c.error = error("请先报名并完成支付"); return c; }
            c.registration = ticket;
            activity=currentPlayActivityMapper.selectCmsActivityByIdForUpdate(actId);
            if(activity==null || !node.getTopicId().equals(activity.getTopicId())) {
                c.error=error("该节点不属于本次活动");return c;
            }
            String timeErr = activityTimeError(activity);
            if (timeErr != null) { c.error = error(timeErr); return c; }
            c.actId = actId;
            c.topicId = activity.getTopicId();
            c.selfPlay = false;
            return c;
        }
        // 自玩会话(activity_id=0 + topic 通行证)
        if (StringUtils.isBlank(topicId)) { c.error = error("参数有误"); return c; }
        Long tId;
        try { tId = Long.valueOf(topicId); } catch (Exception e) { c.error = error("参数有误"); return c; }
        if (node.getTopicId() == null || !node.getTopicId().equals(tId)) { c.error = error("该节点不属于本主题"); return c; }
        CmsRegistration pass;
        // 已开局的自玩路线：通行证开局时有效即可打完本局(中途到期不打断)；未开局仍需有效通行证。
        // 「已开局」由路线运行时判定：有进行中的路线局，或不建局的普通线性路线已记过节点进度。
        boolean continuing = topicRouteRuntimeService != null && topicRouteRuntimeService.hasRunningSession(memberId, tId, 0L);
        try { pass = continuing
                ? com.chengyinhub.business.service.support.CurrentPlayRegistration.requireToContinue(
                        currentPlayActivityMapper,cmsRegistrationMapper,memberId,1L,tId,registrationId,new Date())
                : com.chengyinhub.business.service.support.CurrentPlayRegistration.require(
                        currentPlayActivityMapper,cmsRegistrationMapper,memberId,1L,tId,registrationId,new Date()); }
        catch(IllegalStateException denied) { c.error=new AjaxResult(402,denied.getMessage());return c; }
        if (pass == null) { c.error = new AjaxResult(402, "请先购买自玩通行证"); return c; }
        c.registration = pass;
        c.actId = 0L;
        c.topicId = tId;
        c.selfPlay = true;
        c.continuing = continuing;
        c.nightWarning = isNightNow();
        return c;
    }

    /**
     * 推进单节点进度（幂等）+ 节点积分 + 通关判定与通关积分。
     * checkin 与 answer 共用。返回 nodeId/firstTime/done/total/completed。
     */
    /**
     * P1 带领场推进闸(服务端强校验,不靠前端禁按钮):
     * 集合中(status=1)一律不可推进;行进中(status=2)节点所属章节须已被队长放行(sortId ≤ 当前章)。
     * 非带领场(无 state)/已结算(3)/自玩会话(actId=0)不拦。
     */
    private String leadGateError(Long actId, CmsTopicNode node) {
        if (actId == null || actId <= 0) return null; // 自玩会话无带领
        String routeStatus = gameSessionRuntimeService == null ? null
                : gameSessionRuntimeService.routeSessionStatus(actId);
        if (routeStatus != null) {
            return "RUNNING".equals(routeStatus) ? null : "本场带队尚未开局或已结束";
        }
        com.chengyinhub.business.domain.ClubLeadState st;
        try { st = clubLeadMapper.selectByActivity(actId); } catch (Exception e) { return null; }
        if (st == null || st.getStatus() == null || st.getStatus() == 3) return null;
        if (st.getStatus() == 1) return "队伍集合中,等队长开始行程";
        if (node == null || node.getChapterId() == null || st.getCurrentChapterId() == null) return null;
        if (node.getChapterId().equals(st.getCurrentChapterId())) return null;
        CmsTopicChapter cur = cmsTopicChapterService.selectCmsTopicChapterById(st.getCurrentChapterId());
        CmsTopicChapter mine = cmsTopicChapterService.selectCmsTopicChapterById(node.getChapterId());
        if (cur == null || mine == null || cur.getSortId() == null || mine.getSortId() == null) return null;
        return mine.getSortId() > cur.getSortId() ? "本章还没开启,等队长放行" : null;
    }

    /** M9 埋点:记录一次节点尝试(best-effort,失败不影响主流程)。attemptType 1答题2扫码3GPS4拍照;result 1过0错2放弃 */
    private void logAttempt(Long memberId, CmsTopicNode node, Long actId, int attemptType, int result, String wrongAnswer) {
        try {
            com.chengyinhub.business.domain.PlayAttemptLog log = new com.chengyinhub.business.domain.PlayAttemptLog();
            log.setMemberId(memberId);
            log.setTopicId(node != null ? node.getTopicId() : null);
            log.setNodeId(node != null ? node.getId() : null);
            log.setActivityId(actId);
            log.setAttemptType(attemptType);
            log.setResult(result);
            if (wrongAnswer != null && wrongAnswer.length() > 120) wrongAnswer = wrongAnswer.substring(0, 120);
            log.setWrongAnswer(wrongAnswer);
            log.setCreatedAt(new java.util.Date());
            playAttemptLogMapper.insertPlayAttemptLog(log);
        } catch (Exception e) {
            logger.warn("埋点写入失败 member={} activity={} topic={} node={} attemptType={} result={} errorType={}",
                    memberId, actId,
                    node != null ? node.getTopicId() : null,
                    node != null ? node.getId() : null,
                    attemptType, result,
                    e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 节点名次奖牌:全平台头三个完成该节点的人拿金/银/铜。返回夺得或本已持有的名次,三档满返回 null。
     *
     * 并发裁判是 node_medal_claim 的 PRIMARY KEY (node_id, medal_rank),不是应用层。
     * ⚠️ 不许改成「先查最大名次再写下一名」:本方法在 READ_COMMITTED 里,两个同秒事务会读到
     *    同一快照、双发金牌;而 checkin_time 是 DATETIME(秒精度),事后也判不出谁先。
     * ⚠️ 必须留在外层 @Transactional 内(不要 REQUIRES_NEW):拆出去 = progress 提交了、claim 没写成,
     *    而档位已被后来者占走 —— 这个洞不可修复。宁可承受排队。
     * ⚠️ 依赖 MySQL/InnoDB:失败的 INSERT 不毒化当前事务,循环里可以接着发下一条(Postgres 需 SAVEPOINT)。
     * ⚠️ 依赖 READ_COMMITTED:撞 1062 时冲突行必然已提交(未提交只会阻塞),所以回读一定看得见它。
     *    改成 REPEATABLE_READ 会把「我其实持牌」误报成「无牌」(uk_node_member 仍兜住不双持,只是显示错)。
     *
     * 【行锁持有多久 —— 没解决,只是量过了,结论是今天不用管】
     * 抢占 INSERT 的 PK 行锁从这里一直持有到【外层事务提交】,后面还有 total 查询、done 查询、
     * topicRouteCompletionCoordinator.advance(有写)、topicCompletionAwardService.grantOnce(有写+发徽章)。
     * 「放最后一行所以锁得短」只是相对链首短,不是解决。真正让它不痛的是【争抢面极窄】:
     *   · 只有【冲突行尚未提交】才会阻塞。某 node 的三个档位一旦被提交占满,后来者撞的是已提交行,
     *     1062 立即返回、一微秒都不等 ⇒ 一个 node 一生只有【头三次完成】可能排队,不随表长大而变多。
     *   · 排队上界 = 前面赢家的事务尾时长(2 条查询 + 2 处写),几十毫秒量级;
     *     innodb_lock_wait_timeout 默认 50s,差三个数量级。
     * 触发阈值(到了才需要动手):【同一个 node 的前三次完成落在同一个事务尾窗口(~50ms)内】,
     * 现实形态 = 开场时几十上百人同时扫同一个打卡点。今天生产 player_node_progress 全表 1 行
     * (1 玩家 1 节点),不存在这种场景。
     * 真到那天的改法【不是】挪去 REQUIRES_NEW(见上面第二条 ⚠️,那会造出不可修复的洞),
     * 而是把事务尾那两处写挪到抢占【之前】:抢占仍在同一事务内,但行锁只持有到 commit 那一小段。
     */
    Integer claimNodeMedal(Long nodeId, Long memberId, Long activityId, Long topicId, Long progressId) {
        // Spring 运行态该依赖是 required;null 仅兼容直接 new Controller 的存量纯单测。
        if (nodeMedalClaimMapper == null || nodeId == null || memberId == null) return null;
        for (int rank = 1; rank <= 3; rank++) {
            try {
                if (nodeMedalClaimMapper.insertClaim(nodeId, rank, memberId,
                        activityId == null ? 0L : activityId,
                        topicId == null ? 0L : topicId,
                        progressId == null ? 0L : progressId) == 1) {
                    return rank;
                }
            } catch (DuplicateKeyException dup) {
                // 撞的是 PK(该档被别人占)还是 uk_node_member(自己早已持牌)?
                // 不解析错误文本 —— 回读一次点查,让库自己说。
                Integer mine = nodeMedalClaimMapper.selectRankByNodeAndMember(nodeId, memberId);
                if (mine != null) return mine;   // 换局重玩/重放:幂等返回原名次,不再往下抢
                // 否则该档被别人占了,试下一档
            } catch (DeadlockLoserDataAccessException deadlock) {
                // 1213 死锁:MySQL 回滚的是【整个事务】,不是这一条语句。吞掉它 = 后面的路线推进 /
                // 通关发奖会落在一个新的隐式事务里提交,而打卡那条 INSERT 已经没了 —— 静默丢打卡
                // 比报错严重得多。必须往外抛,让外层 rollbackFor 整单回滚、由前端重试。
                throw deadlock;
            } catch (TransientDataAccessException transientFailure) {
                // 1205 锁等待超时(CannotAcquireLockException)这类【短暂性】写失败:
                // innodb_rollback_on_timeout 默认 OFF ⇒ 只回滚这一条 INSERT,外层事务仍可用,
                // 所以咽下去当「没抢到牌」收场是安全的。⚠️ 哪天把该参数改成 ON,这个前提就没了。
                // 为什么切在 TransientDataAccessException 而不是 DataAccessException / Exception:
                // 编程错(NPE)、列截断(DataIntegrityViolationException)是 NonTransient,照旧穿透。
                // ⚠️ 表不存在 / SQL 语法错(BadSqlGrammarException)同样是 NonTransient、这支也接不住,
                //    它由下面那支【部署窗口兜底】单独接管(2026-09-02 补),别再照这句话推断它会穿透。
                // 夺牌是锦上添花,不该有「把玩家打卡完成一起回滚」的权力;但也不该把真 bug 藏起来。
                logger.warn("claimNodeMedal transient failure nodeId={} memberId={} rank={}: {}",
                        nodeId, memberId, rank, transientFailure.getMessage());
                return null;
            } catch (BadSqlGrammarException | UncategorizedDataAccessException notReady) {
                // 【部署窗口兜底】jar 先于 migration_20260902_node_medal_claim.sql 上线时,这里必然抛
                // BadSqlGrammarException(1146 Table 'node_medal_claim' doesn't exist)。它是 NonTransient,
                // 上面那支 catch 接不住 ⇒ 穿透到外层 rollbackFor = Exception.class ⇒ 每次节点完成整单回滚 500,
                // 整条游玩链断在「奖牌」这个锦上添花的功能上。读取侧 ApiCreativeSquareController
                // .enrichSportFeedBatch 已经为同一个窗口降级成 0 枚,写侧不能继续裸奔。
                //
                // 吞这两类:
                //   · BadSqlGrammarException —— 表/列/语法不成立(1146/1054/1064)。整条语句永远不会成功,
                //     所以直接 return 而不是接着试下一档(否则一次打卡刷三行同样的 warn)。
                //   · UncategorizedDataAccessException —— mapper 语句没被加载时 MyBatis 抛 BindingException
                //     (Invalid bound statement),mybatis-spring 翻成 MyBatisSystemException,正是它的子类。
                // 放行这三类(刻意不接):
                //   · DataIntegrityViolationException(列截断/约束冲突)= 数据本身不合法,是真 bug,
                //     必须整单回滚暴露出来(NodeMedalClaimTest#nonDuplicateFailure_propagates 钉着这条)。
                //   · DeadlockLoserDataAccessException(1213)= MySQL 已经回滚了整个事务,吞掉 = 静默丢打卡。
                //   · NPE / 参数错等非 DataAccessException —— 压根不在本 catch 的类型范围内,照旧穿透。
                // 这一刀切的是「功能还不存在」与「功能被用错了」:前者是部署顺序的必然状态,
                // 拿它拖垮打卡不可接受;后者是 bug,藏起来更贵。
                //
                // 到期条件:迁移在生产落库后,本支 catch 就只剩防御意义(回滚 / 建新环境忘跑迁移)。
                // 可以留着,但别指望它报警——它只打 warn。表在不在只有迁移对账能回答,不是这行日志。
                logger.warn("claimNodeMedal skipped, node_medal_claim not ready nodeId={} memberId={}: {}",
                        nodeId, memberId, notReady.getMessage());
                return null;
            }
        }
        return null;   // 头三名已满 = 第四位起无牌
    }

    private Map<String, Object> advanceProgress(Long memberId, CmsTopicNode node, Long actId)
    {
        return advanceProgress(memberId, node, actId, null);
    }

    /**
     * 推进单节点进度（幂等）+ 节点积分 + 通关判定。带 picUrl 时(拍照打卡)首次写入回填照片与审核位。
     */
    private Map<String, Object> advanceProgress(Long memberId, CmsTopicNode node, Long actId, String picUrl)
    {
        return advanceProgress(memberId, node, actId, picUrl, null, null);
    }

    /** 谜题完成字段只描述表现，不参与现有 XP/积分发奖。 */
    private Map<String, Object> advanceProgress(Long memberId, CmsTopicNode node, Long actId, String picUrl,
            Integer puzzleScore, String completionMode)
    {
        return advanceProgress(memberId, node, actId, picUrl, puzzleScore, completionMode, RewardPolicy.FULL);
    }

    private Map<String, Object> advanceProgress(Long memberId, CmsTopicNode node, Long actId, String picUrl,
            Integer puzzleScore, String completionMode, RewardPolicy rewardPolicy)
    {
        return advanceProgress(memberId, node, actId, picUrl, puzzleScore, completionMode, rewardPolicy,
                null, null, null, null);
    }

    private Map<String, Object> advanceProgress(Long memberId, CmsTopicNode node, Long actId, String picUrl,
            Integer puzzleScore, String completionMode, RewardPolicy rewardPolicy,
            String routeActionId, Integer expectedRouteVersion, String trustedOutcomeCode)
    {
        return advanceProgress(memberId, node, actId, picUrl, puzzleScore, completionMode, rewardPolicy,
                routeActionId, expectedRouteVersion, trustedOutcomeCode, null);
    }

    /**
     * @param approvedProgressId 拍照人工审核通过时传入待审进度行 id(null=普通完成)。
     *        非 null 时只有该行 CAS 0→1 命中才算 firstTime,发奖/通关从此唯一一次成立,
     *        重复通过/并发通过拿不到 rows⇒firstTime=false,不重发奖。
     */
    private Map<String, Object> advanceProgress(Long memberId, CmsTopicNode node, Long actId, String picUrl,
            Integer puzzleScore, String completionMode, RewardPolicy rewardPolicy,
            String routeActionId, Integer expectedRouteVersion, String trustedOutcomeCode,
            Long approvedProgressId)
    {
        // 高级玩法是节点原验证方式的前置闸。只有这里通过后才进入既有幂等、发奖和通关链，
        // 避免六类玩法各自复制一份积分/券/勋章逻辑。
        // Spring 运行态该依赖是 required；null 仅兼容直接 new Controller 的存量纯单测。
        String advancedOutcomeCode = advancedGameRuntimeService == null ? null
                : advancedGameRuntimeService.completeGate(memberId, actId, node.getTopicId(), node.getId());
        Map<String, Object> conditionalReward = null;
        // ③ 与 ①② 在本方法里有两处分歧(首完判据 + 发奖范围),求一次共用,免得两处各判各的再漂开。
        boolean shopDay = resolveMode(actId, node.getTopicId()) == PlayMode.FREE_COLLECT;
        // 幂等：原子 insert + DuplicateKeyException 精确捕获并发冲突；
        // 捕获后按 uk_progress 四列回查确认确为重复，非重复异常必须 rethrow。
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setMemberId(memberId);
        p.setActivityId(actId);
        p.setTopicId(node.getTopicId());
        p.setNodeId(node.getId());
        p.setStatus(1);
        p.setCheckinTime(new Date());
        p.setPuzzleScore(puzzleScore);
        p.setCompletionMode(completionMode);
        if (StringUtils.isNotBlank(picUrl)) {
            p.setPicUrl(picUrl);
            p.setReviewStatus(0);
        }
        p.setDelFlag(0);
        p.setCreateTime(new Date());
        int rows;
        Integer persistedPuzzleScore = puzzleScore;
        String persistedCompletionMode = completionMode;
        try {
            rows = playerNodeProgressService.insertPlayerNodeProgress(p);
        } catch (DuplicateKeyException e) {
            PlayerNodeProgress probe = new PlayerNodeProgress();
            probe.setMemberId(memberId);
            probe.setActivityId(actId);
            probe.setTopicId(node.getTopicId());
            probe.setNodeId(node.getId());
            List<PlayerNodeProgress> found = playerNodeProgressService.selectPlayerNodeProgressList(probe);
            if (found == null || found.isEmpty()) {
                throw e; // 不是 uk_progress 冲突，不伪装成已完成
            }
            PlayerNodeProgress existing = found.get(0);
            p.setId(existing.getId());
            if (existing.getPuzzleScore() != null) persistedPuzzleScore = existing.getPuzzleScore();
            if (StringUtils.isNotBlank(existing.getCompletionMode())) {
                persistedCompletionMode = existing.getCompletionMode();
            }
            rows = 0;
            // ★拍照一律机审(2026-09-17 用户拍板):存量待审行(status=0,人工审核时代落下的)
            //   在这里 0→1 条件更新转正 —— CAS 命中 = 完成事实自此刻成立,继续走下面的发奖/通关链;
            //   重复/并发提交恒 0 行,落回 firstTime=false 的幂等路径。
            if (approvedProgressId != null && approvedProgressId.equals(existing.getId())
                    && playerNodeProgressService.markPhotoMachineApproved(existing.getId()) > 0) {
                rows = 1;
            }
            // B5 收窄:mode2 的行由扫码进店先建(status=0)⇒ 答题推进在这里必撞重复键。
            // 游戏完成 = 条件更新 0→1,更新到即本次首次完成;已是 1 = 真重复。
            // mode1 的行恒 status=1,条件更新恒 0 行,不受影响。
            if (rows == 0 && shopDay && shopDayProofService.markGameDone(memberId, actId, node.getTopicId(), node.getId())) {
                rows = 1;
            }
        }
        boolean firstTime = (rows > 0);

        List<PlayerBadge> newBadges = new ArrayList<>();
        Map<String, Object> dropResult = null;
        int earnedXp = 0;
        // 名次奖牌本次夺得的名次(1/2/3);没抢到(三档满 / 表未就绪 / 非首次)恒 null。
        // 前端原来只看节点配了 medalName 就弹「专属徽章」,第 4 名起弹的是假奖 —— 必须回传事实。
        Integer medalRank = null;
        if (firstTime && (p.getPlayInstanceId()==null || "default".equals(p.getPlayInstanceId()))) {
            // 重玩生成本局进度与主题内容，但不再次派发平台积分、券、成长值或排名奖牌。
            // ★ ③ 只发探索值与图鉴收集。B5(2026-08-15 修订)原文授权的是「玩完发**探索值**,纯体验」——
            //   抵扣积分 / 模板券 / 承接商家券 / 随机掉落券 / 经典节点徽章都不在授权里,不得靠
            //   「复用 advanceProgress」顺带继承过来。抵扣积分尤其不行:裁决表 D2 关掉 ③ 票的积分抵扣,
            //   理由是「不关则 G_cash = 69 × N_cash 在代码上是假的」;买票侧关了、玩法侧发,
            //   这些积分照样能花在 ①② 上,同一个经济口径被从另一头捅开。
            //   ①② 的形态一字不改 —— 由 classicOrienteeringStillGrantsTheFullNodeRewardSet 守着。
            if (!shopDay && rewardPolicy == RewardPolicy.FULL) {
                pointsAwardService.award(memberId, 12L, node.getId(), "节点打卡");
                // 任务触发发券：节点绑定模板配了 couponId 则给玩家发一张(仅首次完成，天然防重发)
                // C5 防薅券:拍照完成(picUrl!=null)凭任意图不足以证真,不自动发真金券;真金券走扫码核销(C2)/后台审核。非拍照完成(GPS到达/答题/扫码)照常发。
                if (StringUtils.isBlank(picUrl)) {
                    grantTemplateCoupon(memberId, node, actId);
                }
            }
            // 条件奖励:商家配的「达到某个结果才发这份」。守与上面无条件奖励**同一套闸**——
            // ③ 自由探索不发券(B5 只授权探索值)、拍照不发券(C5 防薅)、非 FULL 不发券。
            // 探索值不受这两条限制,它本来就是 ③ 唯一被授权的奖励。
            conditionalReward = grantConditionalReward(memberId, node,
                    advancedOutcomeCode != null ? advancedOutcomeCode : trustedOutcomeCode,
                    shopDay, rewardPolicy, picUrl);
            // FE-01:完成节点若有碎片文,持久化到 member 级收集(幂等,供成就大屏聚合)
            if (rewardPolicy == RewardPolicy.FULL && StringUtils.isNotBlank(node.getFragmentText())) {
                collect(memberId, com.chengyinhub.business.domain.MemberCollectible.TYPE_FRAGMENT,
                        node.getTopicId(), node.getId(), node.getFragmentText());
            }
            // P2 成长:节点完成累计+连续打卡+首枚/连续徽章
            if (!shopDay || rewardPolicy == RewardPolicy.XP_BADGE_ONLY) {
                newBadges.addAll(playerProgressionService.onNodeDone(memberId));
            }
            // M7 节点探索值入账(双轨:只进 member_growth,不碰抵扣积分;幂等=progress uk 首次)
            // 发奖与进度、路线推进共用外层 @Transactional 边界；失败必须向外抛出，
            // 让本次节点完成整体回滚后可安全重试，不能留下“路线已走、奖励待人工”的裂缝。
            earnedXp = xpBudgetService.resolveNodeXp(node);
            if (earnedXp <= 0) earnedXp = 12;
            growthService.addExp(memberId, earnedXp, "节点探索");
            // M6 随机掉落:首次完成掷骰(内部 uk 幂等,重放/并发不多掉);命中即入账(granting)
            // ③ 整块跳过:dropType=3 会再发一张真金券,dropType=1 的翻倍探索值也不在 B5 的「纯体验」里。
            if (shopDay || rewardPolicy == RewardPolicy.XP_BADGE_ONLY) dropResult = null;
            else dropResult = rewardDropService.rollDrop(memberId, node.getTopicId(), node.getId(), actId, earnedXp > 0 ? earnedXp : 12L);
            if (dropResult != null) {
                Integer dt = (Integer) dropResult.get("dropType");
                if (dt != null && dt == 1 && dropResult.get("extraXp") instanceof Number) {
                    int extra = ((Number) dropResult.get("extraXp")).intValue();
                    if (extra > 0) { growthService.addExp(memberId, extra, "掉落·探索值翻倍"); earnedXp += extra; }
                } else if (dt != null && dt == 3 && dropResult.get("couponTemplateId") instanceof Number) {
                    long cid = ((Number) dropResult.get("couponTemplateId")).longValue();
                    if (cid > 0) grantCouponById(memberId, cid);   // 彩蛋券真落袋(SmsCouponHistory)
                }
            }
            // 名次奖牌:放发奖链最后一行。抢不到(档位被占 / 三档满 / 短暂写失败 / 表尚未建)都是正常结果,不影响打卡;
            // 行锁到底持有多久、什么时候才需要改,见 claimNodeMedal 的方法注释(已量化 + 写了触发阈值)。
            medalRank = claimNodeMedal(node.getId(), memberId, actId, node.getTopicId(), p.getId());
        }

        // 进度统计 + 通关判定
        CmsTopicNode allProbe = new CmsTopicNode();
        allProbe.setTopicId(node.getTopicId());
        allProbe.setStatus(1L);
        int total = cmsTopicNodeService.selectCmsTopicNodeList(allProbe).size();

        PlayerNodeProgress doneProbe = new PlayerNodeProgress();
        doneProbe.setMemberId(memberId);
        doneProbe.setActivityId(actId);
        // 自玩会话 activity_id 全部=0,多主题共享同一探针会互相污染 done 计数,须按 topicId 再收窄
        // (与上面 total 的 topic 口径保持一致);活动会话 activityId 天然一对一绑定主题,无需再加。
        // ⚠️ 这与 nodes() 里 :400 的收窄是同一件事 —— 那处已修并写了注释,这处(通关判定)漏了:
        // done 被别的自玩主题的进度撑大 ⇒ 提前判 completed ⇒ 提前发通关积分/XP/通关勋章。
        if (actId == null || actId == 0L) {
            doneProbe.setTopicId(node.getTopicId());
        }
        int done = 0;
        for (PlayerNodeProgress doneRow : playerNodeProgressService.selectPlayerNodeProgressList(doneProbe)) {
            // F1:完成事实只认 status==1。mode2 自由收集的到店/凭证行 status=0(由 markGameDone 0→1),
            // 按行数全算会让「只到店没玩」的节点凑满 total ⇒ 提前判通关发奖。
            // 口径与 /nodes completedNodeIds、TopicRouteRuntimeServiceImpl.completed() 一致。
            if (doneRow.getStatus() != null && doneRow.getStatus() == 1) done++;
        }

        TopicRouteState routeState = null;
        com.chengyinhub.business.domain.CmsTopic journeyTopic = cmsTopicService.selectCmsTopicById(node.getTopicId());
        boolean journeyEnabled = journeyTopic != null
                && com.chengyinhub.business.domain.JourneyRules.parse(journeyTopic.getJourneyRulesJson()).hasRuntimeState();
        if ((!shopDay || journeyEnabled) && topicRouteCompletionCoordinator != null) {
            String serverReceiptId = p.getId() == null ? null : "player-node-progress:" + p.getId();
            routeState = topicRouteCompletionCoordinator.advance(memberId, node.getTopicId(), actId, node.getId(),
                    routeActionId, expectedRouteVersion, serverReceiptId, advancedOutcomeCode, trustedOutcomeCode);
        } else if ((!shopDay || journeyEnabled) && topicRouteRuntimeService != null) {
            routeState = topicRouteRuntimeService.state(memberId, node.getTopicId(), actId);
        }

        // [P0-17] 通关口径:③ 认**核销**不认打卡。
        // ⚠️ 五个判定点必须同口径(本处 + /my-completed + 广场 enrichSportFeed + 广场 summary
        //    + ApiStatsController.club)—— 只切一处就会出现「打卡端说没通关、广场说通关了」的分叉。
        boolean completed = routeState != null && TopicRouteState.BRANCH_GRAPH.equals(routeState.getRouteMode())
                ? TopicRouteState.COMPLETED.equals(routeState.getStatus())
                : isTopicCompleted(memberId, actId, node.getTopicId(), total, done);
        if (routeState != null && routeState.getOpening() != null
                && !Boolean.TRUE.equals(routeState.getOpening().get("complete"))) completed = false;
        if (completed) {
            com.chengyinhub.business.domain.vo.TopicCompletionAwardVO award = rewardPolicy == RewardPolicy.FULL
                    ? topicCompletionAwardService.grantOnce(memberId, node.getTopicId(), actId, null)
                    : topicCompletionAwardService.grantOnce(memberId, node.getTopicId(), actId, null, rewardPolicy);
            newBadges.addAll(award.getBadges());
            earnedXp += award.getEarnedXp();
        }

        List<Map<String, Object>> badgeView = new ArrayList<>();
        for (PlayerBadge b : newBadges) {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("code", b.getBadgeCode());
            bm.put("name", b.getBadgeName());
            bm.put("iconUrl", b.getIconUrl());
            badgeView.add(bm);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeId", node.getId());
        data.put("firstTime", firstTime);
        data.put("done", done);
        data.put("total", total);
        data.put("completed", completed);
        if (routeState != null) data.put("routeState", routeState);
        data.put("newBadges", badgeView); // 本次新得徽章(前端弹"+徽章")
        refreshBingo(memberId, node.getTopicId(), data);
        data.put("xp", earnedXp);         // M7 本次入账探索值(前端飞字"+N 探索值")
        data.put("medalRank", medalRank); // 名次奖牌本次夺得的名次(null=没抢到,前端不得弹「专属徽章」)
        // 条件奖励:前端据此把「命中 · +50 探索值 · 得到一张券」这一屏渲染出来。
        // 没配或没命中就没有这个键,前端按不存在处理。
        if (conditionalReward != null) data.put("conditionalReward", conditionalReward);
        if (persistedPuzzleScore != null) data.put("puzzleScore", persistedPuzzleScore);
        if (StringUtils.isNotBlank(persistedCompletionMode)) data.put("completionMode", persistedCompletionMode);
        if (completed && !shopDay) {
            Integer personalBest = playerNodeProgressService.selectPuzzlePersonalBest(memberId, node.getTopicId());
            if (personalBest != null) data.put("puzzlePersonalBest", personalBest);
        }
        if (dropResult != null) {
            data.put("drop", dropResult); // M6 随机掉落(命中已入账:XP倍率→addExp / 彩蛋券→SmsCouponHistory)
        }
        return data;
    }

    /**
     * 我已通关的活动列表(广场发布「选择活动」用)：
     * 取当前用户全部节点打卡进度,按 activityId 聚合 done,与主题节点总数比对,
     * 仅返回按主题节点规则已通关的活动,带活动名/封面/主题ID/节点进度。
     */
    @Operation(summary = "我已通关的活动列表(广场发布选择)")
    @PostMapping("/my-completed")
    public AjaxResult myCompleted()
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");

        // 当前用户全部打卡进度
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        List<PlayerNodeProgress> all = playerNodeProgressService.selectPlayerNodeProgressList(probe);

        // 按 activityId 聚合已打卡节点数;自玩(activity_id=0)按 topicId 聚合并按节点去重
        Map<Long, Integer> doneByActivity = new LinkedHashMap<>();
        Map<Long, java.util.LinkedHashSet<Long>> selfPlayNodesByTopic = new LinkedHashMap<>();
        for (PlayerNodeProgress p : all) {
            if (p.getActivityId() == null) continue;
            // F1:完成事实只认 status==1。mode2 自由收集的到店/凭证行 status=0(markGameDone 0→1),
            // 按全部行数聚合会让「只到店没玩」的主题被列成已完成(与 advanceProgress/nodes/route 同口径)。
            if (p.getStatus() == null || p.getStatus() != 1) continue;
            if (p.getActivityId() == 0L) {
                // R10-09:自玩会话 activity_id 恒 0,必须按 topicId 收窄聚合(与 /nodes、advanceProgress 同口径),
                // 否则多主题互相污染;同节点重复行只算一次。
                if (p.getTopicId() != null && p.getNodeId() != null) {
                    selfPlayNodesByTopic.computeIfAbsent(p.getTopicId(), k -> new java.util.LinkedHashSet<>())
                            .add(p.getNodeId());
                }
                continue;
            }
            doneByActivity.merge(p.getActivityId(), 1, Integer::sum);
        }

        // [P0-17] ③ 的报名单,一次批量捞(**新增查询恒 1 次,与活动数无关**)。
        // ⚠️ 别循环调 isRegistered(它加了 ownerId 且只返 boolean)—— 那才是 N+1。
        // 口径真源在 ICmsRegistrationService.selectExplorePassesByActivity,三个读侧判定点共用。
        Map<Long, CmsRegistration> exploreByActivity = cmsRegistrationService.selectExplorePassesByActivity(memberId);

        // ★ 驱动集合必须是「打卡过的活动 ∪ 有 ③ 票的活动」,不能只用 doneByActivity ——
        // ③ 认核销不认打卡,一个只核销、没打过卡的 ③ 玩家在 doneByActivity 里压根没有键,
        // 连循环都进不去 ⇒ 他永远看不到自己已通关的路线。这是切口径时最容易漏的一处。
        java.util.Set<Long> candidates = new java.util.LinkedHashSet<>(doneByActivity.keySet());
        candidates.addAll(exploreByActivity.keySet());

        Map<Long, CmsActivity> candidateActivities = new LinkedHashMap<>();
        List<TopicRouteSession> routeScopes = new ArrayList<>();
        for (Long activityId : candidates) {
            CmsActivity activity = cmsActivityService.selectCmsActivityById(activityId);
            if (activity == null || activity.getTopicId() == null) continue;
            candidateActivities.put(activityId, activity);
            TopicRouteSession scope = new TopicRouteSession();
            scope.setMemberId(memberId);
            scope.setTopicId(activity.getTopicId());
            scope.setActivityId(activityId);
            routeScopes.add(scope);
        }
        // 自玩(activity_id=0)路线状态与活动共用同一次 sessionStatuses 批量读回(不新增 N+1)
        for (Long topicId : selfPlayNodesByTopic.keySet()) {
            TopicRouteSession scope = new TopicRouteSession();
            scope.setMemberId(memberId);
            scope.setTopicId(topicId);
            scope.setActivityId(0L);
            routeScopes.add(scope);
        }
        Map<String, Integer> routeStatuses = topicRouteRuntimeService == null
                ? java.util.Collections.<String, Integer>emptyMap()
                : topicRouteRuntimeService.sessionStatuses(routeScopes);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Long activityId : candidates) {
            int done = doneByActivity.getOrDefault(activityId, 0);

            CmsActivity activity = candidateActivities.get(activityId);
            // 自玩会话 activity_id 恒 0 ⇒ selectCmsActivityById(0) 返 null ⇒ 在此被静默排除。
            // ⚠️ 这是**既有行为**(/my-completed 今天只覆盖活动单),切 ③ 口径时别顺手「修」。
            if (activity == null || activity.getTopicId() == null) continue;

            CmsTopicNode nodeProbe = new CmsTopicNode();
            nodeProbe.setTopicId(activity.getTopicId());
            nodeProbe.setStatus(1L);
            int total = cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe).size();

            com.chengyinhub.business.domain.CmsTopic completionTopic =
                    cmsTopicService.selectCmsTopicById(activity.getTopicId());
            Integer routeStatus = routeStatuses.get(ITopicRouteRuntimeService.sessionKey(
                    memberId, activity.getTopicId(), activityId));
            boolean routeCompleted = isCompletedCompletionFact(completionTopic, routeStatus,
                    exploreByActivity.get(activityId), activity.getTopicId(), total, done);
            if (!routeCompleted) {
                continue; // 仅已通关
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("activityId", activityId);
            m.put("topicId", activity.getTopicId());
            m.put("dataType", 1);          // 1=活动场次
            m.put("dataId", activityId);   // 统一关联 ID:dataType=1 时指向活动
            m.put("name", activity.getName());
            m.put("cover", activity.getImgUrl());
            m.put("total", total);
            m.put("doneCount", done);
            m.put("completed", true);
            result.add(m);
        }

        // R10-09:自玩(activity_id=0)已完成主题,与活动完成对象并列返回,类型/ID 明确。
        // 完成判定与 /nodes、/route-state、advanceProgress 同口径:分支图看会话状态,线性看主题通关规则。
        // 未完成、主题不可读(含已删/已下架取不到)一律不返回;不伪造名称/封面/activityId。
        for (Map.Entry<Long, java.util.LinkedHashSet<Long>> entry : selfPlayNodesByTopic.entrySet()) {
            Long topicId = entry.getKey();
            int done = entry.getValue().size();
            com.chengyinhub.business.domain.CmsTopic selfTopic = cmsTopicService.selectCmsTopicById(topicId);
            if (selfTopic == null) {
                continue;
            }
            CmsTopicNode selfNodeProbe = new CmsTopicNode();
            selfNodeProbe.setTopicId(topicId);
            selfNodeProbe.setStatus(1L);
            int total = cmsTopicNodeService.selectCmsTopicNodeList(selfNodeProbe).size();

            Integer selfRouteStatus = routeStatuses.get(ITopicRouteRuntimeService.sessionKey(
                    memberId, topicId, 0L));
            boolean selfCompleted = isCompletedCompletionFact(selfTopic, selfRouteStatus, null,
                    topicId, total, done);
            if (!selfCompleted) {
                continue; // 仅已通关
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("activityId", null);     // 自玩没有活动场次:明确 null,不伪造
            m.put("topicId", topicId);
            m.put("dataType", 2);          // 2=主题(自玩)
            m.put("dataId", topicId);      // 统一关联 ID:dataType=2 时指向主题
            m.put("name", selfTopic.getName());
            m.put("cover", selfTopic.getImgUrl());
            m.put("total", total);
            m.put("doneCount", done);
            m.put("completed", true);
            result.add(m);
        }
        return AjaxResult.success(result);
    }

    /**
     * 活动排行榜(升级3):分队竞速/摄影任务/集章任务共用一个口径 ——
     * score=完成节点数(status=1),同数先完成者(最后完成时间早)靠前。
     * activityId 优先(同一活动=同一场比赛);仅 topicId=自玩跨场合计。
     */
    @Operation(summary = "活动排行榜(完成数降序,同数先完成者靠前)")
    @GetMapping("/leaderboard")
    public AjaxResult leaderboard(String activityId, String topicId)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        Long actId = com.chengyinhub.common.core.text.Convert.toLong(activityId, 0L);
        Long tId = com.chengyinhub.common.core.text.Convert.toLong(topicId, 0L);
        if (actId <= 0 && tId <= 0) return error("参数有误");
        if (actId > 0) {
            CmsActivity activity = cmsActivityService.selectCmsActivityById(actId);
            if (activity == null || activity.getTopicId() == null) return error("活动不存在");
            if (tId > 0 && !activity.getTopicId().equals(tId)) return error("活动与主题不匹配");
            if (!isRegistered(memberId, actId)) return error("请先报名并完成支付");
            if (gameSessionRuntimeService != null && gameSessionRuntimeService.isRouteLeaderboardHidden(actId)) {
                return error("主理人暂未开放本场排行榜");
            }
        } else if (validSelfPlayPass(memberId, tId) == null) {
            return new AjaxResult(402, "请先购买自玩通行证");
        }
        List<LeaderboardItemVO> list = playerNodeProgressService.selectActivityLeaderboard(
                actId > 0 ? actId : null, actId > 0 ? null : tId, 50);
        if (list == null) list = new ArrayList<>();
        LeaderboardItemVO me = null;
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setRank(i + 1);
            if (memberId.equals(list.get(i).getMemberId())) me = list.get(i);
        }
        if (me == null) {
            // 榜外:补我的完成数,rank 留空(不猜名次)
            PlayerNodeProgress probe = new PlayerNodeProgress();
            probe.setMemberId(memberId);
            if (actId > 0) {
                probe.setActivityId(actId);
            } else {
                probe.setActivityId(0L);
                probe.setTopicId(tId);
            }
            long myDone = 0;
            for (PlayerNodeProgress p : playerNodeProgressService.selectPlayerNodeProgressList(probe)) {
                if (p.getStatus() != null && p.getStatus() == 1) myDone++; // 探针无 status 过滤,Java 侧只数完成行
            }
            me = new LeaderboardItemVO();
            me.setMemberId(memberId);
            me.setScore(myDone);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("me", me);
        return AjaxResult.success(data);
    }

    /**
     * 我的成长 + 徽章墙(P2):等级/累计里程/连续打卡 + 已获徽章列表。
     */
    @Operation(summary = "我的成长+徽章")
    @PostMapping("/growth")
    public AjaxResult growth()
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");

        // BE-01 勋章单真源:growth 只返游玩进度,勋章统一走 /api/medal/wall(去掉此处重复的 badges/badgeCount)。
        PlayerGrowth g = playerProgressionService.getGrowth(memberId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", g.getLevel());
        data.put("totalCheckins", g.getTotalCheckins());
        data.put("totalMileage", g.getTotalMileage());
        data.put("streakDays", g.getStreakDays());
        return AjaxResult.success(data);
    }

    /**
     * FE-01 成就聚合:XP/等级 + 已收集碎片 + 已收集彩蛋。
     * 城市勋章走 /api/medal/wall、成就徽章走 /api/growth/center(两套概念不合并,前端分区展示)。
     */
    @PostMapping("/achievements")
    public AjaxResult achievements()
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        PlayerGrowth g = playerProgressionService.getGrowth(memberId);
        java.util.List<com.chengyinhub.business.domain.MemberCollectible> fragments =
                memberCollectibleMapper.selectListByMember(memberId, com.chengyinhub.business.domain.MemberCollectible.TYPE_FRAGMENT);
        java.util.List<com.chengyinhub.business.domain.MemberCollectible> eggs =
                memberCollectibleMapper.selectListByMember(memberId, com.chengyinhub.business.domain.MemberCollectible.TYPE_EGG);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", g.getLevel());
        data.put("totalCheckins", g.getTotalCheckins());
        data.put("totalMileage", g.getTotalMileage());
        data.put("streakDays", g.getStreakDays());
        data.put("fragmentCount", fragments.size());
        data.put("fragments", fragments);
        data.put("eggCount", eggs.size());
        data.put("eggs", eggs);
        return AjaxResult.success(data);
    }

    /**
     * 积分解锁本节点游玩提示。
     * 收费口径:cms_points_setting(type=1, event_type=14) 分值>0 才收费;
     * 解锁记录=消费流水本身 (memberId, eventType=14, eventId=nodeId)。
     */
    @Operation(summary = "积分解锁节点提示")
    @PostMapping("/hint/unlock")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public AjaxResult unlockHint(Long nodeId, String activityId, String topicId)
    {
        Long memberId = currentMemberId();
        if (memberId <= 0) return error("请先登录");
        if (nodeId == null || nodeId <= 0) return error("缺少节点ID");
        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nodeId);
        if (node == null || node.getTemplateId() == null) return error("该节点无提示");
        // ★ 与 /puzzle/hint 同口径的准入闸(2026-09-16 补):原来只验登录,任何登录用户拿到
        //   nodeId 就能读走任意节点(含别人场次/付费内容)的提示正文,规则没开时还免费直出。
        //   老客户端只发 nodeId:用节点自己的 topicId 兜底当自玩上下文(仍要持该主题通行证),
        //   缺参数不是「有权读」,只是参数形态兼容。
        String ctxTopicId = topicId;
        if (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId)) {
            ctxTopicId = node.getTopicId() == null ? null : String.valueOf(node.getTopicId());
        }
        PlayCtx ctx = resolveCtx(memberId, activityId, ctxTopicId, node);
        if (ctx.error != null) return ctx.error;
        String leadErr = leadGateError(ctx.actId, node);
        if (leadErr != null) return error(leadErr);
        String routeErr = routeAccessError(memberId, ctx.actId, ctx.topicId, node, null);
        if (routeErr != null) return error(routeErr);
        CmsMemberTemplate tpl = cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
        if (tpl == null || (StringUtils.isEmpty(tpl.getHint1()) && StringUtils.isEmpty(tpl.getHint2()))) {
            return error("该节点无提示");
        }

        int cost = hintUnlockCost();
        // 无规则/规则为0 = 功能未开闸,免费直出(兼容老版本客户端与灰度期)
        if (cost <= 0) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hint1", tpl.getHint1());
            data.put("hint2", tpl.getHint2());
            data.put("cost", 0);
            return AjaxResult.success(data);
        }
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        long before = (member != null && member.getPoint() != null) ? member.getPoint() : 0L;
        if (before < cost) {
            return error("积分不足");
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
            umsMemberPointsDetailService.insertUmsMemberPointsDetail(d);
        } catch (DuplicateKeyException e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hint1", tpl.getHint1());
            data.put("hint2", tpl.getHint2());
            data.put("cost", 0);
            return AjaxResult.success(data);
        }

        // 原子扣减(point >= cost 才扣)。若并发消费使余额不足，回滚刚抢到的流水后返回业务文案。
        try {
            umsMemberService.deductPoints(memberId, cost);
        } catch (RuntimeException e) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            return error("积分不足,请重试");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hint1", tpl.getHint1());
        data.put("hint2", tpl.getHint2());
        data.put("cost", cost);
        return AjaxResult.success(data);
    }

    /** 兼容入口:旧调用方(含存量单测)只传 nodeId —— 走上面那支的「节点 topicId 兜底」,
     *  仍然要持该主题通行证才会返回提示正文,不是免检通道。 */
    public AjaxResult unlockHint(Long nodeId)
    {
        return unlockHint(nodeId, null, null);
    }

    /** 解锁提示的积分价:读积分规则(type=1, event_type=14),无规则/0=免费(功能关) */
    private int hintUnlockCost()
    {
        CmsPointsSetting q = new CmsPointsSetting();
        q.setType(1L);
        q.setEventType(14L);
        List<CmsPointsSetting> rows = cmsPointsSettingService.selectCmsPointsSettingList(q);
        if (rows == null || rows.isEmpty()) return 0;
        CmsPointsSetting s = rows.get(0);
        // 后台「积分规则」停用(status!=1)= 关闸免费:mapper 的 where 不带 status,必须在这里判,
        // 否则管理员点了停用仍在收费(开关语义与展示不一致)
        if (s.getStatus() != null && s.getStatus() != 1L) return 0;
        if (s.getPointsNum() != null) return s.getPointsNum();
        if (s.getValue() != null) return s.getValue().intValue();
        return 0;
    }

    /**
     * 任务触发发券：节点绑定模板若配了 couponId，向玩家发一张 SmsCouponHistory。
     * 字段填法与 ApiRegistrationController 扫码发券保持一致（getType=0 赠送 / useStatus=0 未用 /
     * couponCode "COU"+时间戳+随机 / 二维码内容 {"type":"coupon","code":...}），
     * 商家用现有 /api/coupon/verification 扫该券核销。仅在节点首次完成时调用。
     */
    /**
     * 刷新宾果九宫格。
     *
     * <p>⚠️ **整段包在 try-catch 里,失败只记 error**。宾果是主流程之外的一层:
     * 它出问题不能让节点完成失败,否则玩家会卡在这一站反复重试,而原因是一个
     * 跟他这次动作毫无关系的九宫格。少填一格是可修的(evaluate 返回的是「当前
     * 该是什么样」,下一次完成会把漏掉的补上),让整条游玩链路挂掉不可修。
     */
    private void refreshBingo(Long memberId, Long topicId, Map<String, Object> data)
    {
        if (playBingoService == null) return;   // 纯单测直接 new Controller 时为空
        try {
            com.chengyinhub.business.domain.PlayBingoCard card =
                    playBingoService.onNodeDone(memberId, topicId);
            if (card != null) {
                Map<String, Object> bingo = new LinkedHashMap<>();
                bingo.put("filledMask", card.getFilledMask());
                bingo.put("rewardMask", card.getRewardMask());
                data.put("bingo", bingo);
            }
        } catch (Exception e) {
            logger.error("宾果刷新失败 member={} topic={},节点完成不受影响,下次完成会自愈",
                    memberId, topicId, e);
        }
    }

    /**
     * 条件奖励:按本次结果码发商家配的那一份。
     *
     * <p>{@code reward_rules_json} 是叠加在平铺奖励列之上的,平铺的仍表示「完成即发」。
     * 没有结果码、没配规则、或没匹配上,本方法什么也不做。
     *
     * @return 发出去的东西(给前端展示用);什么都没发返回 null
     */
    private Map<String, Object> grantConditionalReward(Long memberId, CmsTopicNode node,
            String outcomeCode, boolean shopDay, RewardPolicy rewardPolicy, String picUrl)
    {
        if (StringUtils.isBlank(outcomeCode) || node.getTemplateId() == null || node.getTemplateId() <= 0) {
            return null;
        }
        CmsMemberTemplate tpl = cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
        if (tpl == null) return null;
        com.fasterxml.jackson.databind.JsonNode rule =
                rewardRulesValidator.ruleFor(tpl.getRewardRulesJson(), outcomeCode);
        if (rule == null) return null;

        Map<String, Object> granted = new LinkedHashMap<>();
        granted.put("outcomeCode", outcomeCode);

        int xp = rule.path("xp").asInt(0);
        if (xp > 0) {
            growthService.addExp(memberId, xp, "玩法结果奖励");
            granted.put("xp", xp);
        }
        String text = rule.path("feedbackText").asText("").trim();
        if (!text.isEmpty()) granted.put("feedbackText", text);

        long couponId = rule.path("couponId").asLong(0L);
        // 券的三道闸与无条件奖励逐条对齐,不另立一套口径
        boolean couponAllowed = couponId > 0
                && !shopDay
                && rewardPolicy == RewardPolicy.FULL
                && StringUtils.isBlank(picUrl);
        if (couponAllowed && grantCouponById(memberId, couponId)) {
            granted.put("couponId", couponId);
        }
        return granted.size() > 1 ? granted : null;
    }

    private final com.chengyinhub.business.service.support.RewardRulesValidator rewardRulesValidator
            = new com.chengyinhub.business.service.support.RewardRulesValidator();

    private void grantTemplateCoupon(Long memberId, CmsTopicNode node, Long actId)
    {
        // 到店券优先用承接商家自己的:玩家在他店里完成互动,成本也该由他承担。
        // 原来无论谁承接都发主办方模板上那张券 —— 商家出了场地和人力,却没有自己的权益出口。
        CmsMemberTemplate tpl = null;
        if (node.getTemplateId() != null && node.getTemplateId() > 0) {
            tpl = cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
        }
        com.chengyinhub.business.domain.vo.NodeMerchantOverride override =
                nodeMerchantOverrideService.forNode(node.getId());
        Long merchantCouponId = override.couponIdOr(null);
        Long couponId = merchantCouponId != null ? merchantCouponId : (tpl == null ? null : tpl.getCouponId());
        if (couponId == null || couponId <= 0) return;
        // 第二轮拍板第 6 条:「商家出游戏码、玩家扫码答题、券发给玩家本人」的唯一发券路径就在这里
        // (商家侧 /api/registration/verify_answer 已停用)。核销审计:操作人=玩家,归属=出这张券的商家。
        Long ownerMemberId = merchantCouponId != null ? override.getMerchantMemberId()
                : (tpl == null ? null : tpl.getMemberId());
        if (ownerMemberId == null) ownerMemberId = node.getMerchantMemberId();
        if (ownerMemberId == null) {
            // 总控复查裁定:查不出出券商家 = 数据坏了。节点照常完成(不回滚玩家),这张券不发,留 error 供核查。
            logger.error("节点模板券缺归属商家,不发券 nodeId={} templateId={} memberId={} couponId={}",
                    node.getId(), node.getTemplateId(), memberId, couponId);
            return;
        }
        if (!grantCouponById(memberId, couponId)) return;
        // 有归属才发券 + 审计,与本请求同事务:审计写不进就整体回滚 —— 发了券却查不到发给谁、因为哪一站,等于没留痕。
        com.chengyinhub.business.domain.BusinessAuditEvent audit =
                new com.chengyinhub.business.domain.BusinessAuditEvent();
        audit.setDomainType("TOPIC_NODE");
        audit.setDomainId(node.getId());
        audit.setOwnerMemberId(ownerMemberId);
        audit.setActorType(com.chengyinhub.business.domain.BusinessAuditEvent.ACTOR_TYPE_MEMBER);
        audit.setActorMemberId(memberId);
        audit.setActorRoleCode("PLAYER");
        audit.setAction("NODE_COUPON_GRANT");
        audit.setTargetType("SMS_COUPON");
        audit.setTargetId(couponId);
        audit.setRelationId(actId);
        audit.setBeforeJson("{\"couponGranted\":false}");
        audit.setAfterJson("{\"couponGranted\":true}");
        audit.setRequestId("node-coupon-" + memberId + "-" + actId + "-" + node.getId());
        audit.setCreateTime(new Date());
        if (businessAuditEventMapper.insertBusinessAuditEvent(audit) != 1) {
            throw new IllegalStateException("节点发券审计写入失败");
        }
    }

    /**
     * 按券模板 ID 发一张到玩家券包(节点任务券 / M6 彩蛋券掉落共用)。
     * @return true=已发券;false=券不存在或库存耗尽(服务层返回 false),券没落袋
     */
    private boolean grantCouponById(Long memberId, Long couponId)
    {
        // BE-06 下沉:发券逻辑收口到 ISmsCouponHistoryService.grantCouponById(与扫码核销发券同一实现)
        // C-30:券已失效(3)/已停发(4) 不可发放 → 跳过发券,节点进度/掉落照常落袋,不再误导人工补发
        if (smsCouponHistoryService.isCouponNotIssuable(couponId)) {
            logger.warn("COUPON_DISABLED_SKIP caller=playProgress member={} coupon={}", memberId, couponId);
            return false;
        }
        boolean granted = smsCouponHistoryService.grantCouponById(memberId, couponId);
        if (!granted) {
            // C3:节点进度/掉落键已先落库且本方法非事务,发券失败不可静默(丢券零告警),
            // 记 error 供运营人工补发/核查券模板库存;仅券不存在/库存耗尽走到这里
            logger.error("节点发券失败(券不存在或库存耗尽) member={} coupon={},需人工补发/核查", memberId, couponId);
        }
        return granted;
    }

    @Autowired
    private com.chengyinhub.business.service.INodeMerchantOverrideService nodeMerchantOverrideService;

    private Long currentMemberId()
    {
        try {
            Long id = getAppUserId();
            return id == null ? 0L : id;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 初始状态开着时,精力 / 幸运各开没开;没开初始状态或规则读不出 → null(前端不出初始状态页里的这两行)。 */
    static Map<String, Object> vitalsOn(String journeyRulesJson) {
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

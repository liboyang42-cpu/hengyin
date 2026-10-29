package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.util.ChapterAtmospherePreset;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.ChapterBlockDTO;
import com.chengyinhub.business.domain.dto.CmsTopicCreateDTO;
import com.chengyinhub.business.domain.dto.CoopGroupSettlementBasis;
import com.chengyinhub.business.domain.dto.CoopSettlementPlan;
import com.chengyinhub.business.domain.dto.CoopSettlementOriginLine;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.domain.dto.TopicProductTypeResolver;
import com.chengyinhub.business.domain.dto.TopicSettlementDTO;
import com.chengyinhub.business.domain.dto.TopicSettlementCashBasis;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO;
import com.chengyinhub.business.exception.ChapterFlowClientUpgradeRequiredException;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.support.ChapterRecruitmentRules;
import com.chengyinhub.business.support.CircleThemePublishPolicy;
import com.chengyinhub.business.service.ITopicLifecycleService;
import com.chengyinhub.business.service.ITopicCommercialReviewService;
import com.chengyinhub.business.service.ITopicSaleFinalizationService;
import com.chengyinhub.business.service.ITopicSaleCanonicalMutationService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.support.ChapterNarrativeBlocks;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.business.service.support.PendingRecruitWorkflowTerminator;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.chengyinhub.business.service.support.TopicRoutePublishGate;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.business.support.OmsTicketWriteValidator;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.RoleEntityType;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.common.constant.ValidationMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.DistanceCalculatorUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.bean.BeanUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.ICoopSettlementClaimService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.ITopicRouteService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

/**
 * 主题Service业务层处理
 *
 * @author webxh
 * @date 2025-09-12
 */
@Service
public class CmsTopicServiceImpl implements ICmsTopicService {

    @Autowired
    private TopicQueryService topicQueryService;

    @Autowired
    private com.chengyinhub.business.service.impl.TopicPublicationService topicPublicationService;
    @Autowired
    private com.chengyinhub.business.service.support.TopicPublicationContentGuard publicationContentGuard;
    @Autowired
    private com.chengyinhub.business.service.support.TopicSelfHostActivityService selfHostActivityService;


    @Autowired
    private com.chengyinhub.business.service.ITopicContentCopyService topicContentCopyService;

    @Autowired
    private com.chengyinhub.business.service.support.TopicPlatformContentSlotGuard platformContentSlotGuard;

    @Autowired
    private com.chengyinhub.business.service.ITopicContentGraphAuthoringService contentGraphAuthoringService;
    @Autowired
    private com.chengyinhub.business.service.support.TopicContentWriteGuard contentWriteGuard;


    private final TopicCompletionRulePolicy topicCompletionRulePolicy = new TopicCompletionRulePolicy();




    /**
     * [P0-35] 角色槽。建题时声明内容槽 —— 票款归内容槽持有者(D8),
     * 且 P0-50 的 REVSHARE 范围闸读的就是它。
     */
    @Autowired
    private com.chengyinhub.business.service.IProductRoleService productRoleService;


    /**
     * [Task 2] 平台自营主题的收款会员账号。
     *
     * <p>它就是 {@code copyTopicBasicInfo} 里那句裸魔数 {@code setMemberId(100000L)} 的那个 100000
     * —— 全仓只出现过、无名无注释。给它一个名字和一个可配置的位置,是为了两件事:
     * ① 换环境(测试库/新生产)时不用改代码;② 让「平台收款账号是谁」这件事在配置里可见,
     * 而不是散落在几处字面量里靠人记。
     *
     * <p>⚠️ 它【不是】判断「是不是平台自营」的依据 —— 那个判据是 product_role 的内容槽。
     * 别再拿 memberId==100000 反推自营(P0-02/P0-35 反复治过的病)。
     */
    @Value("${chengyin.platform.member-id:100000}")
    private Long platformMemberId;
    private static final Logger logger = LoggerFactory.getLogger(CmsTopicServiceImpl.class);

    /** 俱乐部主办的主题结算规则(settlement_rule.biz_type)。没配这条时回落到全局 topic 规则。 */
    private static final String BIZ_TYPE_TOPIC_CLUB = "topic_club";

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    // A3-7(P0):每主题独立事务用(照抄活动线),替代原整批单 @Transactional。
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CoopInviteMapper coopInviteMapper;

    @Autowired
    private ICoopInviteService coopInviteService;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("wechatPaymentSdkGateway")
    private com.chengyinhub.business.service.WechatPaymentOperations wechatPayService;

    @Autowired
    private AlarmService alarmService;

    @Autowired
    private com.chengyinhub.business.service.IAuditTaskService auditTaskService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.support.TopicSupplyReviewService supplyReviewService;

    @Autowired
    private com.chengyinhub.business.service.support.TopicBundleReviewService topicBundleReviewService;



    /** H3/H4：发布主链落完章节/票后，经客观商业事实判据进入立项审或 UGC 直通。 */
    @Autowired
    private ITopicCommercialReviewService topicCommercialReviewService;

    /** 9-15 拍板:已发布免费主题不能在编辑中转成商业主题。 */
    @Autowired
    private com.chengyinhub.business.service.support.CommercialTopicPolicy commercialTopicPolicy;

    // P0.5 XP 自动分配 + 途中彩蛋
    @Autowired
    private com.chengyinhub.business.service.IXpBudgetService xpBudgetService;

    @Autowired
    private com.chengyinhub.business.service.IClubService clubService;

    @Autowired
    private DistanceCalculatorUtils distanceCalculatorUtils;

    @Autowired
    private CmsTopicChapterMapper cmsTopicChapterMapper;

    @Autowired
    private MerchantCircleSupplyProfileMapper merchantCircleSupplyProfileMapper;



    @Autowired
    private CmsTopicNodeMapper cmsTopicNodeMapper;


    /** B1 发布期状态键闸。手工 new 的旧单测不注入该字段时闸跳过;Spring 运行态必注入。 */
    @Autowired
    private com.chengyinhub.business.service.support.ThemeStateKeyService themeStateKeyService;

    /** B1/R8 状态键引用收集(节点块 / 数值规则 / 剧情配置),与发布前试检查共用同一份。 */


    /** R8-STORY 发布期剧情 NPC 引用闸。手工 new 的旧单测不注入该字段时该侧引用不核对。 */


    /** R2-B10 主题数值规则校验器。无状态,直接 new,与 AdvancedGameConfigValidator 的用法一致。 */
    private final com.chengyinhub.business.service.support.JourneyRulesValidator journeyRulesValidator =
            new com.chengyinhub.business.service.support.JourneyRulesValidator();

    /** R8-STORY 主题级剧情配置校验器。无状态,直接 new;引用核对上下文由调用处给。 */
    private final com.chengyinhub.business.service.support.JourneyStoryValidator journeyStoryValidator =
            new com.chengyinhub.business.service.support.JourneyStoryValidator();

    /** R22-SEC S16 块配置节点引用闸:无状态,直接 new,与其它校验器用法一致。 */


    @Autowired
    private CmsCollaboratorsMapper cmsCollaboratorsMapper;

    @Autowired
    private OmsTicketMapper omsTicketMapper;



    @Autowired
    private CmsActivityMapper cmsActivityMapper;

    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    // P0-28 抽成收敛:结算费率单一真源(小数),存量主题分支从 cms_setting 百分数列迁到 settlement_rule。
    // 原 cmsSettingMapper 在本类仅结算路径使用,收敛后已无消费点,随本次改动一并移除。
    @Autowired
    private com.chengyinhub.business.service.ISettlementRuleService settlementRuleService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private com.chengyinhub.business.service.IClubLeaderService clubLeaderService;

    @Autowired
    private UmsMemberMapper umsMemberMapper;

    @Autowired
    private IUmsMemberBalanceDetailService umsMemberBalanceDetailService;

    @Autowired
    private IPointsAwardService pointsAwardService;

    @Autowired
    private CoopOrderMapper coopOrderMapper;

    @Autowired
    private CoopSettlementMapper coopSettlementMapper;

    /** 合同四写侧必须存在；缺失时不得退回 live 字段和主题级聚合。 */
    @Autowired
    private ICoopSettlementClaimService coopSettlementClaimService;

    @Autowired
    private CoopSettlementEvidenceMapper coopSettlementEvidenceMapper;

    @Autowired(required = false)
    private CoopSettlementAdjustmentMapper coopSettlementAdjustmentMapper;

    @Autowired
    private com.chengyinhub.business.mapper.CoopGroupVerifyMapper coopGroupVerifyMapper;

    @Autowired
    private com.chengyinhub.business.mapper.CoopComplaintMapper coopComplaintMapper;

    /** 收款方欠款:coop 打款入账先抵欠款;逾期未清不能自办上架新活动。 */
    @Autowired
    private com.chengyinhub.business.service.support.SettlementDebtLedger settlementDebtLedger;

    @Autowired
    private com.chengyinhub.business.service.IPricingService pricingService;

    @Autowired
    private com.chengyinhub.business.service.INotifyService notifyService;

    /** [6-09] 主题发布时自动同步到广场的帖子;主题可见性变化(下架/删除/驳回/恢复)时一起联动。 */
    @Autowired
    private com.chengyinhub.business.service.IUmsCreativeSquareService umsCreativeSquareService;

    @Autowired
    private ITopicLifecycleService topicLifecycleService;

    @Autowired
    private ITopicSaleFinalizationService topicSaleFinalizationService;

    @Autowired
    private CoopSettlementOriginalMapper coopSettlementOriginalMapper;
    @Autowired
    private CoopSettlementOriginalProducer coopSettlementOriginalProducer;

    @Autowired(required = false)
    private ITopicSaleCanonicalMutationService canonicalMutationService;

    @Autowired
    private CoopOrderLineupSource coopOrderLineupSource;

    @Autowired
    private ObjectProvider<PendingRecruitWorkflowTerminator> pendingRecruitWorkflowTerminatorProvider;

    @Autowired
    private MerchantChapterOfferMapper merchantChapterOfferMapper;

    @Autowired
    private TopicSaleLifecycleAdvancer topicSaleLifecycleAdvancer;

    // T7 主题模板:路线计算(腾讯地图API) + 模板查询(节点绑定玩法)
    @Autowired
    private ITopicRouteService topicRouteService;


    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;

    @Autowired
    private ICouponRewardAuthorizationService couponRewardAuthorizationService;

    /**
     * 查询需要结算的主题列表
     */
    @Override
    public List<CmsTopic> selectTopicsForSettlement() {
        return cmsTopicMapper.selectTopicsForSettlement();
    }

    @Override
    public int processRecruitDeadlines() {
        Date now = DateUtils.getNowDate();
        List<CmsTopic> topics = cmsTopicMapper.selectRecruitExpiredTopics(now);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        int handled = 0;
        int poisoned = 0;
        for (CmsTopic topic : topics) {
            Integer accepted;
            try {
            accepted = transactionTemplate.execute(status -> {
                // 与接受工作流同锁主题行：相等即截止，先终结 pending，再只读规范化阵容。
                CmsTopic locked = cmsTopicMapper.selectCmsTopicByIdForUpdate(topic.getId());
                if (locked == null || locked.getLifecycle() == null || locked.getLifecycle() != 1
                        || locked.getRecruitDeadline() == null || locked.getRecruitDeadline().after(now)) {
                    return null;
                }
                PendingRecruitWorkflowTerminator terminator = pendingRecruitWorkflowTerminatorProvider.getIfAvailable();
                if (terminator == null) {
                    // ★ seam 缺失时【不许动状态】,只许响。
                    //
                    // 原来这里是 `transitionRecruitingToDraft(...)` —— 无条件把主题打回草稿。
                    // 而 PendingRecruitWorkflowTerminator 在 main 代码里【一个实现都没有】
                    // (全仓只有接口 + 这里和 TopicSaleFinalizationServiceImpl 两处 ObjectProvider),
                    // 所以生产上这一支是【唯一】会走到的分支:招到 4 家商家的探店日,招募一到期
                    // 照样被判「招募未成,已回草稿」,handled 还 +1、订阅消息还去通知发布者,全程零错误日志。
                    // 2026-08-18 真库实测:4 行有效 offer + 0 行 coop_order 的主题 lifecycle 1 → 0。
                    //
                    // 孪生调用点 TopicSaleFinalizationServiceImpl:140-142 对同一个缺失 seam 是
                    // 抛异常拒绝开售。同一个洞,一处响一处哑 —— 而哑的那处还顺手把状态毁了。
                    // 统一成响的那种:抛出后被下面的 catch 就地隔离成 poisoned,
                    // 主题原地留在 RECRUITING(可重试、不误伤后续主题),每轮扫描留一条 warn。
                    throw new IllegalStateException(
                            "pending 工作流终结 seam 尚未由 W2 接入，拒绝处理招募截止 topicId=" + topic.getId());
                }
                terminator.terminatePendingUnderTopicLock(topic.getId(), now);
                CoopOrderLineupSource.ReadResult orderLineup = coopOrderLineupSource.readEffectiveLineup(topic.getId());
                List<MerchantChapterOffer> offers = effectiveOffers(topic.getId(), now);
                int acceptedCount = orderLineup.getEntries().size() + offers.size();
                if (!orderLineup.getRepairReasons().isEmpty() || acceptedCount == 0) {
                    return transitionRecruitingToDraft(topic.getId()) ? acceptedCount : null;
                }
                boolean hasCashObligation = hasCashObligation(orderLineup, offers);
                if (!topicLifecycleService.transition(topic.getId(), TopicLifecycleState.RECRUITING,
                        TopicLifecycleState.PRICING)) {
                    return null;
                }
                if (!hasCashObligation) {
                    // 自动路径只省人工成本确认；商业局仍必须把完整参数提交开售审，不能直奔 SELLING。
                    //
                    // 费率仍按既有零现金路径冻结；canonical15 人工事实留给运营后续显式补齐。
                    pricingService.freezeFeeSnapshot(topic.getId());
                    topicCommercialReviewService.submitSaleReviewIfReady(topic.getId(), locked.getMemberId());
                    // canonical15 含人工录入事实，自动截止任务无权伪造；停在 PRICING 等显式提审。
                }
                return acceptedCount;
            });
            } catch (RuntimeException e) {
                // ★ 单个主题失败必须就地隔离,不许冲出本循环。
                //
                // 为什么:单个主题的合法业务拒绝一旦冲出去,事务回滚 ⇒
                // lifecycle 仍是 1、recruit_deadline 仍过期 ⇒ 下一轮再选中、再抛,而且
                // **它后面所有主题永远处理不到**,连 advanceSaleLifecycle() 都到不了 ——
                // 一个招商不足的主题就能把全平台的招商截止与开售推进永久冻住,且零告警
                // (任务顶层只留一行日志)。2026-08-14 自审发现,存量缺陷。
                //
                // 只接 RuntimeException:OOM / StackOverflow 这类 Error 必须继续上抛,
                // 那是整个进程的问题,吞掉才是真的把故障藏起来。
                poisoned++;
                logger.error("双死线-主题 {} 招募到期处理失败,已跳过该主题继续扫描:{}",
                        topic.getId(), e.toString(), e);
                continue;
            }
            if (accepted == null) {
                logger.info("双死线-主题 {} 状态或截止时间已变化,跳过本次招募到期处理", topic.getId());
                continue;
            }
            handled++;
            // 订阅消息:通知发布者招募结果(best-effort)
            try {
                String tip = accepted == 0 ? "招募未成,已回草稿,可延期重招" : "招募结束,已进入定价阶段";
                String atime = topic.getStartDate() != null
                        ? DateUtils.parseDateToStr("yyyy-MM-dd HH:mm", topic.getStartDate())
                        : DateUtils.parseDateToStr("yyyy-MM-dd HH:mm", now);
                notifyService.notifyRecruit(topic.getMemberId(), topic.getName(), atime, tip);
            } catch (Exception ignore) {}
        }
        if (handled > 0 || poisoned > 0) {
            logger.info("双死线-招募到期处理完成,共处理 {} 个主题,失败跳过 {} 个", handled, poisoned);
        }
        // 失败数单独再喊一次:上面那行是 info,容易淹在日志里;卡住的主题会每轮复现,
        // 这条 warn 就是它唯一的可发现信号,别删。
        if (poisoned > 0) {
            logger.warn("双死线-有 {} 个主题连续处理失败,它们会在每轮扫描重复出现,需人工介入", poisoned);
        }
        return handled;
    }

    private boolean transitionRecruitingToDraft(Long topicId) {
        return topicLifecycleService.transition(topicId, TopicLifecycleState.RECRUITING, TopicLifecycleState.DRAFT);
    }

    /** ①已接受 invite / ②审核通过报名都不是阵容；这里只接受规范 source 的 effective order/offer。 */
    private List<MerchantChapterOffer> effectiveOffers(Long topicId, Date now) {
        List<MerchantChapterOffer> result = new ArrayList<>();
        List<MerchantChapterOffer> offers = merchantChapterOfferMapper.selectByTopic(topicId);
        if (offers == null) return result;
        for (MerchantChapterOffer offer : offers) {
            if (offer != null && topicId.equals(offer.getTopicId()) && Integer.valueOf(1).equals(offer.getStatus())
                    && (offer.getValidEnd() == null || offer.getValidEnd().after(now))) {
                result.add(offer);
            }
        }
        return result;
    }

    private boolean hasCashObligation(CoopOrderLineupSource.ReadResult orderLineup,
                                      List<MerchantChapterOffer> offers) {
        for (CoopOrderLineupSource.LineupEntry entry : orderLineup.getEntries()) {
            if (entry.isCashObligation()) return true;
        }
        for (MerchantChapterOffer offer : offers) {
            if (TermsMode.REVSHARE.equals(offer.getTermsMode())) return true;
        }
        return false;
    }

    @Override
    public int advanceSaleLifecycle() {
        return topicSaleLifecycleAdvancer.advance(DateUtils.getNowDate());
    }


    /**
     * 通用复制：只有请求明确声明 memberId 时才可判定为会员内容主体；其余来源不猜归属。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copyCmsTopic(Long id, Map<String, Object> params) {
        return topicContentCopyService.copyCmsTopic(id, params);
    }

    /** 复制完整路线给承接俱乐部并下架原主题,避免任一步失败后留下两条在售主题。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long transferToClub(Long id, Long memberId, Long clubId) {
        Map<String, Object> params = new HashMap<>();
        params.put("memberId", memberId);
        // 2 = 主题 + 章节 + 节点;票不复制,由发起人重新配置城市定向票种。
        params.put("copyType", 2);
        Long newTopicId = copyCmsTopic(id, params);
        if (newTopicId == null) {
            throw new ServiceException("移交复制失败");
        }

        CmsTopic clubTopic = new CmsTopic();
        clubTopic.setId(newTopicId);
        clubTopic.setClubId(clubId);
        if (updateCmsTopic(clubTopic) != 1) {
            throw new ServiceException("移交失败:新主题绑定俱乐部失败");
        }

        CmsTopic originalOffline = new CmsTopic();
        originalOffline.setId(id);
        originalOffline.setUserStatus(0);
        if (updateCmsTopic(originalOffline) != 1) {
            throw new ServiceException("移交失败:原主题下架失败");
        }
        // [6-09] 原主题下架 = 不公开,同步帖一起收起来。
        syncCreativeSquarePostVisibility(id, false);
        return newTopicId;
    }


    /** CMS 复制是官方创作入口，不复用面向会员/俱乐部的通用复制归属。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copyOfficialCmsTopic(Long id, Map<String, Object> params) {
        return topicContentCopyService.copyOfficialCmsTopic(id, params);
    }







    /**
     * 官方新建/复制必须在同一事务内读回 PLATFORM 槽。底层声明服务为兼容历史链路会记录并吞掉
     * 数据库异常；这里若没有读回真实槽，必须抛错让主题插入一并回滚。
     */
    private void declareRequiredPlatformContentSlot(Long officialTopicId) {
        platformContentSlotGuard.declareRequiredPlatformContentSlot(officialTopicId);
    }

















    /**
     * 执行主题结算
     */
    @Override
    // A3-7(P0):去掉整批单 @Transactional,改每主题独立事务(见下 tx.execute)。
    // 原「@Transactional 整批 + per-topic 吞异常」= 一个主题炸回滚整批、且失败主题部分写入随批提交、
    // 无 is_settlement=0 领取闸 ⇒ 并发/重入重复入账。照抄活动线 HF-02。
    public int executeTopicSettlement() {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        int settledCount = 0;

        // 1. 获取需要结算的主题列表
        List<CmsTopic> topics = selectTopicsForSettlement();
        if (topics.isEmpty()) {
            logger.info("没有需要结算的主题");
            return 0;
        }

        // 2. 获取并验证结算费率(P0-28 单一真源:settlement_rule,小数)
        SettlementRule rule = settlementRuleService.getActiveRule(0L, "topic");
        if (!validateCommissionSettings(rule)) {
            logger.error("主题结算费率配置异常，拒结整批，请检查 settlement_rule(biz_type=topic)");
            throw new ServiceException("主题结算费率配置异常，拒结整批，请检查 settlement_rule(biz_type=topic)");
        }

        // 4. 遍历每个主题进行结算 —— 每主题独立事务:失败仅回滚该主题(含 CAS 领取),可重试。
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<String> failures = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException();
        for (CmsTopic topic : topics) {
            try {
                reconcileTopicPendingDepositPayments(topic.getId());
                Boolean settled = tx.execute(status -> settleTopic(topic, rule));
                if (Boolean.TRUE.equals(settled)) {
                    settledCount++;
                }
            } catch (Exception e) {
                logger.error("主题结算失败, topicId: " + topic.getId(), e);
                // 继续处理下一个主题，不中断整个结算过程
                failures.add("topicId=" + topic.getId() + ": " + e.getMessage());
                failure.addSuppressed(e);
            }
        }
        // R9-46 §4:逐主题失败不能被吞成 SUCCESS。其余主题已各自提交,末端再抛让 Quartz 记 FAIL 并点名。
        if (!failures.isEmpty()) {
            IllegalStateException visible = new IllegalStateException("主题结算失败 " + failures.size()
                    + " 个(成功 " + settledCount + " 个): " + String.join("; ", failures));
            for (Throwable t : failure.getSuppressed()) visible.addSuppressed(t);
            throw visible;
        }

        logger.info("主题结算完成，共结算 {} 个主题", settledCount);
        return settledCount;
    }

    /**
     * 验证主题结算费率(P0-28:读 settlement_rule 小数,不再读 cms_setting 百分数)。
     * rule 缺失 → 拒结整批(绝不静默把发布者/商家份额算错)。
     * <p>★ 真闸(拆掉原空闸):存量主题分支平台抽成是幻影(算了不出账,由平台"永不派发"隐式留存),
     * 真正会超发的是「商家池 + 发布者 > 销售额」。故不变量是 <b>merchant + creator(发布者) ≤ 1.0</b>,
     * 与平台率无关。原实现只 logger.warn 且 return false 被注释=形同无闸,50/50/50 会真发 150%。
     * 邀请人(inviter)佣金从商家份额中抠,不增加总额,不参与本约束。
     */
    /**
     * 按主办方身份取该主题的结算规则。
     *
     * <p>背景:俱乐部主办与商家主办「分润有差别」一直只是意图 —— 代码里
     * {@code getActiveRule(0L, "topic")} 写死了 0L,取的永远是同一条全局规则,
     * 主办方是谁根本不进入计算。这里把它接通。
     *
     * <p><b>为什么按「类型」而不是按「具体某个俱乐部」</b>:{@code getActiveRule} 的
     * merchantId 维度本来就能给单个主体配专属费率,但那要人工逐个配 —— 新俱乐部一旦漏配
     * 就静默回落到商家费率,算错了也不报错。改成按 biz_type 区分,类型是系统从
     * {@code club_id} 算出来的,不依赖谁记得去配。想给某个俱乐部谈特殊价,
     * merchantId 维度仍然在,两者不冲突。
     *
     * <p><b>存量零影响</b>:没配 {@code topic_club} 这条规则时回落到全局 topic 规则,
     * 行为与改造前逐字相同。
     *
     * @return 该主题适用的规则;俱乐部规则存在但费率非法时返回 null(调用方跳过该主题)
     */
    private SettlementRule resolveRuleFor(CmsTopic topic, SettlementRule fallback) {
        Long clubId = topic.getClubId();
        if (clubId == null || clubId == 0L) {
            return fallback;
        }
        SettlementRule clubRule = settlementRuleService.getActiveRule(0L, BIZ_TYPE_TOPIC_CLUB);
        if (clubRule == null) {
            // 回落是对的(存量零影响),但**不能不出声**:2026-08-07 查生产库,俱乐部主办的主题
            // 当时是 0 个 —— 也就是说这条回落分支至今没被真正走过一次。等第一个俱乐部主题来结算时,
            // 若那条规则还没配,它会按商家费率把钱发出去,而且账面上一切正常、零告警。
            // 这行日志是那一刻唯一的信号。
            logger.warn("俱乐部主办主题按【商家费率】结算:settlement_rule 里没有 biz_type={} 的启用规则, topicId={}, clubId={}",
                    BIZ_TYPE_TOPIC_CLUB, topic.getId(), clubId);
            return fallback;
        }
        // ⚠️ 同一道闸:俱乐部规则也得过 merchant+creator ≤ 1.0,否则等于给超发开了条侧门
        return validateCommissionSettings(clubRule) ? clubRule : null;
    }

    private boolean validateCommissionSettings(SettlementRule rule) {
        if (rule == null) {
            logger.error("主题结算费率缺失(settlement_rule biz_type=topic),拒结整批");
            return false;
        }
        BigDecimal merchantRate = rule.getMerchantRate();
        BigDecimal publisherRate = rule.getCreatorRate();

        if (merchantRate == null || publisherRate == null) {
            logger.error("主题结算费率不完整(merchant/creator 有空值),拒结整批");
            return false;
        }

        if (publisherRate.compareTo(BigDecimal.ZERO) != 0) {
            logger.error("H8 要求主题 creator_rate=0，拒绝结算，当前值: {}", publisherRate);
            return false;
        }

        BigDecimal payoutRate = merchantRate.add(publisherRate);
        if (payoutRate.compareTo(BigDecimal.ONE) > 0) {
            logger.error("主题结算商家+发布者份额之和 {} 超过销售额100%，拒结整批防超发", payoutRate);
            return false;
        }

        logger.info("主题结算费率验证通过: 商家{}, 发布者{}, 合计{}(邀请人从商家份额内抠)",
                merchantRate, publisherRate, payoutRate);
        return true;
    }

    /**
     * 结算单个主题
     */
    private boolean settleTopic(CmsTopic topic, SettlementRule rule) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        // 扫描结果只提供定位身份；在任何状态/合同/资金读取前取得共同 topic 锁并使用当前行。
        topic = lockSettlementTopic(topic == null ? null : topic.getId());
        if (Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            throw new ServiceException("FREE_EXPLORE_LEGACY_SETTLEMENT_FORBIDDEN: topicId="
                    + topic.getId() + ", productType=" + topic.getProductType());
        }
        rule = resolveRuleFor(topic, rule);
        if (rule == null) throw new IllegalStateException("主题结算费率无效 topicId=" + topic.getId());
        // A3-7(P0)CAS 原子领取:is_settlement 0→1 抢占,抢不到=已被结算/正在结算 ⇒ 跳过防重复入账。
        // 领取在本主题事务内,失败随事务回滚可重试(照抄活动线 claimActivityForSettlement)。
        if (cmsTopicMapper.claimTopicForSettlement(topic.getId()) == 0) {
            logger.info("主题 {} 已结算或正被并发结算,跳过(CAS 未领取)", topic.getId());
            return false;
        }
        // 这里只用于缺产品类型/非 coop 的早拒绝；普通 RR 聚合绝不用于应付计算或零销售终态。
        // 正式现金基数在下方按报名当前锁读重建。
        BigDecimal totalSales = cmsRegistrationMapper.selectTotalSalesByTopicId(topic.getId());
        if (topic.getProductType() == null && totalSales != null && totalSales.signum() > 0) {
            throw new ServiceException("NULL_PRODUCT_TYPE_WITH_SALES_FORBIDDEN: topicId="
                    + topic.getId() + ", totalSales=" + totalSales);
        }
        List<CoopOrder> effectiveOrders = coopOrderMapper.selectEffectiveOrdersByTopicId(topic.getId());
        if (effectiveOrders.isEmpty() && totalSales != null && totalSales.signum() > 0) {
            throw new ServiceException("NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN: topicId=" + topic.getId());
        }
        requireClassicTopicCashBasis(topic.getId());
        TopicSettlementCashBasis cashBasis = lockTopicCashBasis(topic.getId());
        totalSales = cashBasis.getTotalSales();
        if (topic.getProductType() == null && totalSales.signum() > 0) {
            throw new ServiceException("NULL_PRODUCT_TYPE_WITH_SALES_FORBIDDEN: topicId="
                    + topic.getId() + ", totalSales=" + totalSales);
        }
        if (totalSales.signum() == 0) {
            validateZeroCashCoopContracts(topic, effectiveOrders);
            logger.info("主题 {} 没有销售额，跳过结算", topic.getId());
            refundTopicDeposits(topic.getId());
            // is_settlement 已由入口 claimTopicForSettlement 置 1,无需再标记
            return true;
        }
        // 2/3/4/5. 现金结算:coop 主题走分项应付；无 coop 时现码没有可回读的 merchant 收款人/出款写侧。
        //
        // ★ 2026-07-17 拆除 distributeMerchantCommissions —— 它是一层假账,不是分账:
        //   · 它写的 sharing_amount 在**全仓零消费者**(只有本类写它;domain/resultMap 是机械搬运,
        //     ui 与 xcx 都不读)⇒ 「商家分了多少」这个数从来没人看过,商家也从没因它拿到过钱。
        //   · 它顺带干的唯一真事,是从这笔假金额里抽 2% 给商家邀请人打进余额 ——
        //     **假基数算出的真钱**。经济上也不成立:那是 GMV×1.4%、约等于平台净利(~2.4%)的 58%,
        //     且每次结算都给、永久。
        //   · 它还会污染业务表:sharing_rate 全为 0 时回落「平均分」并把 100/N 写回
        //     cms_registration_merchant,结算跑一次污染一次(生产 topic 2/21 的 sharing_rate=100
        //     就是这么来的,不是谁报的价;topic 19 的 0 才是没人填过的原样)。
        //   拓展人激励改由 MerchantReferralBounty 承接:只认「核销」这个真实信号,与结算完全解耦。
        //   生产实况(2026-07-17 只读查库):ums_member_balance_detail **整张表 0 行**
        //   ⇒ 这条路径从没给任何人发过一分钱 ⇒ 拆除无存量影响。
        //
        //   H8 已删除 distributePublisherCommission；发布资格改由下方 event 15 积分承接。
        if (effectiveOrders.isEmpty()) {
            // H8 不允许把“纯计算 DTO”冒充现金成功。未拍板 merchant 归属前持续回滚 CAS，
            // 不恢复 publisher 现金，也不偷发积分。
            throw new ServiceException("NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN: topicId=" + topic.getId());
        }
        settleCoopTopicFromCashBasis(topic, cashBasis);


        // H8:现金/应付台账写完后才发作品结算积分；失败向外抛，回滚本主题 CAS 与全部结算写入。
        pointsAwardService.awardPublisherSettlement(topic.getMemberId(), topic.getId());

        // 6. is_settlement 已由入口 claimTopicForSettlement 置 1(CAS),无需重复标记
        logger.info("主题结算成功: topicId={}, 总销售额={}", topic.getId(), totalSales);

        return true;
    }

    /**
     * 计算各项佣金
     */
    private TopicSettlementDTO calculateCommissions(CmsTopic topic, SettlementRule rule, BigDecimal totalSales) {
        TopicSettlementDTO settlement = new TopicSettlementDTO();
        settlement.setTopicId(topic.getId());
        settlement.setTopicName(topic.getName());
        settlement.setTotalSales(totalSales);

        // 设置佣金比例（P0-28:小数,来自 settlement_rule;发布者→creator、邀请→inviter 映射）
        settlement.setPlatformCommissionRate(rule.getPlatformCommissionRate());
        settlement.setMerchantCommissionRate(rule.getMerchantRate());
        settlement.setPublisherCommissionRate(rule.getCreatorRate());
        // ⚠️ inviteCommissionRate 自 2026-07-17 起在存量主题路**已无任何消费者** ——
        //    唯一读它的 distributeMerchantInviterCommission 已随假账路径一起拆除。
        //    这里仍然搬运它,是因为 TopicSettlementDTO 是共享结构、coop 路还在读别的字段;
        //    留一个不再有人读的比例比悄悄改 DTO 形状安全。真的拓展人激励见 MerchantReferralBountyService。
        settlement.setInviteCommissionRate(rule.getInviterRate());


        // 计算佣金金额（比例为小数,直接相乘,不再 ÷100）
        settlement.setPlatformCommissionAmount(
                totalSales.multiply(settlement.getPlatformCommissionRate()).setScale(2, RoundingMode.HALF_UP)
        );

        settlement.setMerchantTotalCommissionAmount(
                totalSales.multiply(settlement.getMerchantCommissionRate()).setScale(2, RoundingMode.HALF_UP)
        );

        settlement.setPublisherCommissionAmount(
                totalSales.multiply(settlement.getPublisherCommissionRate()).setScale(2, RoundingMode.HALF_UP)
        );

        // 验证佣金分配是否合理
        validateCommissionDistribution(settlement, totalSales);

        return settlement;
    }

    /**
     * 验证佣金分配
     */
    private void validateCommissionDistribution(TopicSettlementDTO settlement, BigDecimal totalSales) {
        BigDecimal distributedAmount = settlement.getPlatformCommissionAmount()
                .add(settlement.getMerchantTotalCommissionAmount())
                .add(settlement.getPublisherCommissionAmount());
        // 注意：邀请人佣金不参与总比例验证，因为它是从商家分润中抽取的

        BigDecimal difference = totalSales.subtract(distributedAmount);

        if (difference.abs().compareTo(new BigDecimal("0.01")) > 0) {
            logger.warn("佣金分配存在差异: 总销售额={}, 已分配金额={}, 差异={}",
                    totalSales, distributedAmount, difference);
        }
    }

    /**
     * B4:coop 主题结算(有合作单)。平台留佣金,商家按核销分润挂 T+7 结算单(不即时打款),
     * 发起人保底/毛利同样挂 T+7 应付。分成型=share_rate×核销销售额,固定型=¥F×核销人头,引流型=0。
     * 只在 settleTopic 里对"有生效 coop_order 的主题"调用,存量主题不受影响。
     */
    // 保留包内组合测试调用形状；传入的旧聚合只作调用兼容，绝不决定任何现金金额。
    void settleCoopTopic(CmsTopic topic, BigDecimal ignoredLegacySales) {
        settleCoopTopicFromCashBasis(topic, null);
    }

    private void settleCoopTopicFromCashBasis(CmsTopic topic, TopicSettlementCashBasis cashBasis) {
        // 幂等:该主题已分项结算过(存在 PLATFORM 行)→ 跳过。平台/发起人行无 coop_order_id,不受 countByOrderId 防重覆盖,
        // 若外层重跑(如结算后标记失败被 per-topic catch 吞掉)会双挂平台/保底/毛利行,此处 topic 级去重兜住。
        // ⚠️ 这条语义覆盖①经典定向/②漫游全部 coop 主题,不是 H8 的裁决范围,不得为 H8 改成硬失败。
        for (CoopSettlement ex : coopSettlementMapper.selectByTopicId(topic.getId())) {
            if ("PLATFORM".equals(ex.getLineType())) return;
        }
        requireClassicTopicCashBasis(topic.getId());
        coopSettlementOriginalProducer.requireNoManualCase(topic.getId());
        if (coopSettlementClaimService == null) {
            throw new IllegalStateException("MANUAL_REPAIR:COOP_CLAIM_SERVICE_REQUIRED");
        }
        if (cashBasis == null) cashBasis = lockTopicCashBasis(topic.getId());
        BigDecimal totalSales = cashBasis.getTotalSales();
        if (totalSales.signum() <= 0) {
            throw new IllegalStateException("MANUAL_REPAIR:POSITIVE_COOP_CASH_BASIS_REQUIRED");
        }
        // [P0-28 批1] 读**主题锁价时冻的快照**,不是 live 值 —— 用 live 值 = 调一次费率
        // 追溯改写所有已售未结订单,平台多抽 → 发起人 margin<0 → 守恒断言拒结 → 款项悬空。
        BigDecimal platformRate = pricingService.platformRateOf(topic);
        BigDecimal platformAmount = totalSales.multiply(platformRate).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal verifiedSales = cashBasis.getVerifiedSales();
        int verifiedHeads = cashBasis.getVerifiedHeads();
        // T9 审计与现金使用同一批锁读行，避免 RR 旧视图与商家当前履约选集混用。
        String verifiedRegIds = joinVerifiedRegIds(cashBasis.getVerifiedRegistrationIds());

        Date now = new Date();
        Date payable = topic.getEndDate() != null
                ? new Date(topic.getEndDate().getTime() + 7L * 24 * 3600 * 1000)
                : new Date(now.getTime() + 7L * 24 * 3600 * 1000);

        // 第一遍:只算不写。承接方/商家分项行(LEAD_FEE=俱乐部带队 / MERCHANT_VERIFY=商家核销),
        // 累计应收 + 统计"有真实核销的被邀商家数"(发起人保底 +2n% 挂钩)。跳过已挂单(防重复)。
        // 守恒断言必须在任何落库/打款之前完成——executeTopicSettlement 单事务且 per-topic 吞异常,
        // 先插单再抛异常时已插单不回滚,故先算齐五分项、守恒过了再落库。
        BigDecimal merchantTotal = BigDecimal.ZERO;
        int verifiedMerchantCount = 0;
        List<CoopSettlement> pending = new ArrayList<>();
        List<CoopSettlementPlan> noneClaimPlans = new ArrayList<>();
        Map<CoopSettlement, CoopSettlementPlan> claimedOriginPlans = new IdentityHashMap<>();
        // 固定本轮有效合作单列表，逐单按冻结合同准备履约计划。
        List<CoopOrder> effectiveOrders = coopOrderMapper.selectEffectiveOrdersByTopicId(topic.getId());
        // Prepare every frozen original before choosing any economic write. A zero/NONE
        // contract must not reserve a payee key and silently discard a later paid order.
        if (effectiveOrders == null) throw new IllegalStateException("MANUAL_REPAIR:EFFECTIVE_COOP_ORDERS_REQUIRED");
        Map<Long, CoopSettlementPlan> plansByOrder = new LinkedHashMap<>();
        for (CoopOrder order : effectiveOrders) {
            CoopSettlementPlan plan = coopSettlementClaimService.prepare(order, now);
            if (plan == null || plan.getSnapshot() == null || order == null || order.getId() == null
                    || !order.getId().equals(plan.getSnapshot().getOrderId()))
                throw new IllegalStateException("MANUAL_REPAIR:COOP_CLAIM_PLAN_IDENTITY_CONFLICT");
            if (!topic.getId().equals(plan.getSnapshot().getTopicId()) || topic.getMemberId() == null
                    || !topic.getMemberId().equals(plan.getSnapshot().getInitiatorMemberId()))
                throw new IllegalStateException("MANUAL_REPAIR:TOPIC_INITIATOR_SNAPSHOT_CONFLICT");
            if (plansByOrder.putIfAbsent(order.getId(), plan) != null)
                throw new IllegalStateException("MANUAL_REPAIR:DUPLICATE_EFFECTIVE_COOP_ORDER");
        }
        com.chengyinhub.business.service.support.CoopSettlementContract.requireIndependentOrderScopes(
                new ArrayList<>(plansByOrder.values()));
        Set<Long> verifiedMerchantPayees = new HashSet<>();
        for (CoopOrder o : effectiveOrders) {
            CoopSettlementPlan claimPlan = plansByOrder.get(o.getId());
            if (coopSettlementMapper.countByOrderId(o.getId()) > 0) continue; // Already validated, never silently exempt a contract.
            boolean isClub = "club".equals(o.getToType());
            if ("NONE".equals(claimPlan.getRoute())) {
                noneClaimPlans.add(claimPlan);
                continue;
            }
            // 金额、履约选集和原始明细来自同一个冻结合同计划，不再回退到 live 聚合。
            int orderHeads = claimPlan.getVerifiedHeads();
            BigDecimal orderSales = claimPlan.getEligibleSales();
            BigDecimal amount = claimPlan.getOriginAmount();
            Long payee = resolveOrderPayee(o);
            if (payee == null) {
                logger.error("【结算越界告警】coop受款人解析为空(俱乐部可能已删) topicId={} coopOrderId={} toType={} toId={},拒绝结算交人工",
                        topic.getId(), o.getId(), o.getToType(), o.getToId());
                throw new IllegalStateException("coop结算受款人为空 topicId=" + topic.getId() + " coopOrderId=" + o.getId());
            }
            // 快照与金额同口径,否则对账时「记着全主题 100 人头却只拿 0」对不上
            CoopSettlement s = newSettlementLine(topic, payee, amount, isClub ? "LEAD_FEE" : "MERCHANT_VERIFY",
                    isClub ? "club" : "merchant", 0, payable, now, orderHeads, orderSales,
                    joinVerifiedRegIds(claimPlan.getOriginLines().stream().map(CoopSettlementOriginLine::getRegistrationId).toList()));
            s.setCoopOrderId(o.getId());
            if ("SALES_PERCENT".equals(claimPlan.getSnapshot().getCompensationMode())) {
                s.setShareMode(1);
                s.setShareRate(claimPlan.getSnapshot().getShareRate());
            } else {
                s.setShareMode(2);
                s.setFixedFee(claimPlan.getSnapshot().getFixedFee());
            }
            pending.add(s);
            claimedOriginPlans.put(s, claimPlan);
            merchantTotal = merchantTotal.add(amount);
            if (!isClub && amount.compareTo(BigDecimal.ZERO) > 0) verifiedMerchantPayees.add(payee);
            verifiedMerchantCount = verifiedMerchantPayees.size(); // +2% remains per merchant, not per original order.
        }

        // 发起人保底 = 销售额 × (5% + 2%×有核销商家数,封顶 15%);发起人毛利 = 残差(定价赚的钱)。
        BigDecimal guaranteeRate = pricingService.initiatorGuaranteeRateOf(topic, verifiedMerchantCount);
        BigDecimal guaranteeAmount = totalSales.multiply(guaranteeRate).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal marginAmount = totalSales.subtract(platformAmount).subtract(guaranteeAmount).subtract(merchantTotal);

        // Retain negative fixed-cost economics only after rollback; never debit/offset or mark paid.
        if (marginAmount.signum() < 0) coopSettlementOriginalProducer.retainNegativeMargin(
                topic,totalSales,platformAmount,guaranteeAmount,merchantTotal,marginAmount,List.copyOf(pending));
        // 五分项守恒 + 越界(超拆→毛利为负)断言,先于任何落库/打款。
        assertSettlementConservation(topic, totalSales, platformAmount, guaranteeAmount, merchantTotal, marginAmount);

        // 平台行(对账;status=1 已入账不放款,payout 只取 status=0 故跳过它)。
        pending.add(0, newSettlementLine(topic, null, platformAmount, "PLATFORM", "platform", 1, payable, now, verifiedHeads, verifiedSales, verifiedRegIds));
        // 发起人保底 + 毛利行(T+7 放款给发起人;取代旧的即时 payPublisher)。
        // 发起人保底+毛利占销售额大部分;memberId 为空(存量脏数据)则无受款人 → 拒绝结算交人工,
        // 不静默跳过(否则守恒已按含这两笔通过,款项悬空丢钱,与 payee==null 同类资损)。
        if (topic.getMemberId() == null) {
            logger.error("【结算越界告警】主题无发起人 memberId,保底+毛利无受款人 topicId={},拒绝结算交人工", topic.getId());
            throw new IllegalStateException("coop结算主题无发起人 memberId topicId=" + topic.getId());
        }
        pending.add(newSettlementLine(topic, topic.getMemberId(), guaranteeAmount, "INITIATOR_GUARANTEE", "initiator", 0, payable, now, verifiedHeads, verifiedSales, verifiedRegIds));
        if (marginAmount.compareTo(BigDecimal.ZERO) > 0) {
            pending.add(newSettlementLine(topic, topic.getMemberId(), marginAmount, "INITIATOR_MARGIN", "initiator", 0, payable, now, verifiedHeads, verifiedSales, verifiedRegIds));
        }

        // 保证金抵扣不参与票款五分项守恒:它只是把应缴方已有的应收改记为平台已收保证金。
        // 守恒已在上方按票款口径校验;这里绝不能从非应缴方或平台行扣款。
        lockedDepositSetoffInvites(topic); // Unknown/in-flight payment refuses before the first economic write.

        // 第二遍:守恒通过后落库。
        for (CoopSettlementPlan nonePlan : noneClaimPlans) {
            coopSettlementClaimService.persist(nonePlan, null);
        }
        for (CoopSettlement s : pending) {
            CoopSettlementPlan originPlan = claimedOriginPlans.get(s);
            if (originPlan == null) {
                if (coopSettlementMapper.insertCoopSettlement(s) != 1) {
                    throw new IllegalStateException("coop结算应付写入失败 topicId=" + topic.getId()
                            + " lineType=" + s.getLineType());
                }
            } else {
                if (s.getAmount().compareTo(originPlan.getOriginAmount()) != 0) {
                    throw new IllegalStateException("MANUAL_REPAIR:coop 原始头被保证金抵扣改写,须改为 claim 调整事件 orderId="
                            + originPlan.getSnapshot().getOrderId());
                }
                coopSettlementClaimService.persist(originPlan, s);
            }
        }
        applyUnpaidDepositSetoffs(topic, pending, payable, now, verifiedHeads, verifiedSales, verifiedRegIds);
        if (pending.stream().anyMatch(line -> "DEPOSIT_DEDUCTION".equals(line.getLineType()))) {
            BigDecimal net = BigDecimal.ZERO;
            for (CoopSettlement line : pending) net = net.add(line.getCoopOrderId() == null ? line.getAmount() : currentCoopHeadNet(line));
            if (net.compareTo(totalSales) != 0) throw new IllegalStateException("DEPOSIT_SETOFF_CASH_CONSERVATION");
        }
        // Freeze original sales components before inserting their separate deposit reclassification receipts.
        // Every original head, negative adjustment, deposit credit and invite CAS commits in this same transaction.
        coopSettlementOriginalProducer.freezeOriginals(topic,cashBasis,now);
        for (CoopSettlement line : pending) {
            if (!"DEPOSIT_DEDUCTION".equals(line.getLineType())) continue;
            if (coopSettlementMapper.insertCoopSettlement(line) != 1 || line.getId() == null)
                throw new IllegalStateException("DEPOSIT_SETOFF_PLATFORM_RECEIPT_WRITE_CONFLICT");
        }
        logger.info("coop分项结算: topicId={} 平台={} 保底={}(率{}) 承接商家={} 毛利={} 核销商家数={}",
                topic.getId(), platformAmount, guaranteeAmount, guaranteeRate, merchantTotal, marginAmount, verifiedMerchantCount);

        // 保证金:活动结算=履约完成,原路退还该主题所有已接受合作的已缴冻结保证金。
        refundTopicDeposits(topic.getId());
    }

    /** 注册锁先于退款事实锁；已批准退款仍不猜净额，更不以零元跳过退款证据。 */
    private TopicSettlementCashBasis lockTopicCashBasis(Long topicId) {
        List<CmsRegistration> topicRows = coopSettlementEvidenceMapper.selectTopicCashRowsForUpdate(topicId);
        List<CmsRegistration> activityRows = coopSettlementEvidenceMapper.selectTopicActivityCashRowsForUpdate(topicId);
        if (topicRows == null || activityRows == null) {
            throw new IllegalStateException("MANUAL_REPAIR:CURRENT_CASH_ROWS_REQUIRED");
        }
        List<CmsRegistration> locked = new ArrayList<>(topicRows);
        locked.addAll(activityRows);
        CoopSettlementOriginalLedger.requireNoApprovedRefundsForOriginalClose(coopSettlementOriginalMapper, topicId);
        TopicSettlementCashBasis basis = TopicSettlementCashBasis.fromLockedRows(topicId, locked);
        List<Long> refunds = coopSettlementEvidenceMapper.selectCashBasisBlockingRefundIdsForUpdate(topicId);
        if (refunds == null || !refunds.isEmpty()) {
            throw new IllegalStateException("MANUAL_REPAIR:REFUND_NET_BASIS_UNPROVEN");
        }
        return basis;
    }

    /** 零现金也必须校验冻结来源、合法路径与封窗；未证正报酬不以零终态消耗合同。 */
    private void validateZeroCashCoopContracts(CmsTopic topic, List<CoopOrder> orders) {
        if (orders.isEmpty()) return;
        if (coopSettlementClaimService == null) {
            throw new IllegalStateException("MANUAL_REPAIR:COOP_CLAIM_SERVICE_REQUIRED");
        }
        List<CoopSettlementPlan> plans = new ArrayList<>();
        Date now = new Date();
        for (CoopOrder order : orders) {
            CoopSettlementPlan plan = coopSettlementClaimService.prepare(order, now);
            if (plan == null) throw new IllegalStateException("MANUAL_REPAIR:COOP_CLAIM_PLAN_REQUIRED");
            if (plan.getSnapshot() == null || !order.getId().equals(plan.getSnapshot().getOrderId())
                    || !topic.getId().equals(plan.getSnapshot().getTopicId()) || topic.getMemberId() == null
                    || !topic.getMemberId().equals(plan.getSnapshot().getInitiatorMemberId())) {
                throw new IllegalStateException("MANUAL_REPAIR:TOPIC_INITIATOR_SNAPSHOT_CONFLICT");
            }
            if (!"NONE".equals(plan.getRoute())) {
                throw new IllegalStateException("MANUAL_REPAIR:ZERO_CASH_COMPENSATION_UNPROVEN");
            }
            plans.add(plan);
        }
        com.chengyinhub.business.service.support.CoopSettlementContract.requireIndependentOrderScopes(plans);
        for (CoopSettlementPlan plan : plans) coopSettlementClaimService.persist(plan, null);
    }

    /** 旧主题票现金总额不覆盖活动壳；不得把活动履约归入 TOPIC_EDITION 或按零元关账。 */
    private void requireClassicTopicCashBasis(Long topicId) {
        if (coopSettlementEvidenceMapper == null) {
            throw new IllegalStateException("MANUAL_REPAIR:COOP_EVIDENCE_MAPPER_REQUIRED");
        }
    }

    /**
     * 锁价后仍未缴的保证金,从实际应缴方本主题的应收中抵扣。
     *
     * <p>逐行锁邀约,与微信支付回调的锁顺序一致:支付先成功就转冻结并在结算后退款;
     * 结算先拿到行锁就按当时仍为待缴的余额抵扣。可抵扣金额不足时保留状态5和剩余金额,
     * 不跨合作方扣款、更不改原头金额；负调整与平台保证金凭证在原主题事务提交。</p>
     */
    private void applyUnpaidDepositSetoffs(CmsTopic topic, List<CoopSettlement> pending,
            Date payable, Date now, int verifiedHeads, BigDecimal verifiedSales, String verifiedRegIds) {
        List<CoopInvite> due = lockedDepositSetoffInvites(topic);
        if (due.isEmpty()) return;
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("DEPOSIT_SETOFF_REQUIRES_ORIGINAL_TRANSACTION");
        List<CoopSettlement> sources = pending.stream()
                .filter(row -> row.getCoopOrderId() != null && Integer.valueOf(0).equals(row.getStatus()))
                .sorted(Comparator.comparing(CoopSettlement::getCoopOrderId).thenComparing(CoopSettlement::getId))
                .toList();
        for (CoopInvite invite : due) {
            Long payer = coopInviteService.depositOwedParty(invite);
            if (payer == null || payer <= 0) throw new IllegalStateException("保证金应缴方无法解析 inviteId=" + invite.getId());
            BigDecimal remaining = depositSetoffMoney(invite.getDepositAmount());
            if (remaining.signum() < 0) throw new IllegalStateException("保证金欠款金额无效 inviteId=" + invite.getId());
            BigDecimal collected = BigDecimal.ZERO.setScale(2);
            for (CoopSettlement source : sources) {
                if (remaining.signum() == 0) break;
                if (!payer.equals(source.getMerchantId())) continue;
                CoopSettlement head = coopSettlementMapper.selectByIdForUpdate(source.getId());
                if (head == null || !topic.getId().equals(head.getTopicId()) || !payer.equals(head.getMerchantId())
                        || !source.getCoopOrderId().equals(head.getCoopOrderId()) || !Integer.valueOf(0).equals(head.getStatus())
                        || !("MERCHANT_VERIFY".equals(head.getLineType()) || "LEAD_FEE".equals(head.getLineType())))
                    throw new IllegalStateException("DEPOSIT_SETOFF_ORIGINAL_HEAD_CONFLICT");
                List<CoopSettlementDetail> details = coopSettlementOriginalMapper.selectOriginalDetailsForHeadForUpdate(head.getId());
                if (details == null || details.isEmpty()) throw new IllegalStateException("DEPOSIT_SETOFF_ORIGINAL_CLAIMS_REQUIRED");
                List<CoopSettlementDetail> ordered = new ArrayList<>(details);
                Set<Long> claims = new HashSet<>();
                BigDecimal original = BigDecimal.ZERO.setScale(2);
                for (CoopSettlementDetail detail : ordered) {
                    if (detail == null || detail.getClaimId() == null || detail.getRegistrationId() == null
                            || !head.getId().equals(detail.getSettlementId()) || !claims.add(detail.getClaimId())
                            || depositSetoffMoney(detail.getOriginAmount()).signum() < 0)
                        throw new IllegalStateException("DEPOSIT_SETOFF_ORIGINAL_DETAIL_CONFLICT");
                    original = original.add(detail.getOriginAmount());
                }
                if (original.compareTo(head.getAmount()) != 0) throw new IllegalStateException("DEPOSIT_SETOFF_GROSS_HEAD_CHANGED");
                ordered.sort(Comparator.comparing(CoopSettlementDetail::getRegistrationId).thenComparing(CoopSettlementDetail::getClaimId));
                for (CoopSettlementDetail detail : ordered) {
                    if (remaining.signum() == 0) break;
                    String event = "UNPAID_DEPOSIT:" + topic.getId() + ":" + invite.getId() + ":" + head.getCoopOrderId() + ":" + head.getId();
                    CoopSettlementAdjustment prior = coopSettlementAdjustmentMapper.selectByClaimEventForUpdate(detail.getClaimId(), "ADJUSTMENT", event);
                    if (prior != null) {
                        requireDepositSetoffReceipt(prior, head, detail, event);
                        continue; // Same invite/claim event is never collected or credited twice.
                    }
                    BigDecimal available = depositSetoffMoney(detail.getOriginAmount());
                    List<CoopSettlementAdjustment> adjustments = coopSettlementAdjustmentMapper.selectExecutedBySettlementForUpdate(head.getId());
                    if (adjustments == null) throw new IllegalStateException("DEPOSIT_SETOFF_CURRENT_ADJUSTMENTS_REQUIRED");
                    for (CoopSettlementAdjustment adjustment : adjustments) {
                        if (adjustment == null || !head.getId().equals(adjustment.getSettlementId())
                                || !claims.contains(adjustment.getClaimId()) || !"EXECUTED".equals(adjustment.getStatus()))
                            throw new IllegalStateException("DEPOSIT_SETOFF_ADJUSTMENT_SOURCE_CONFLICT");
                        if (detail.getClaimId().equals(adjustment.getClaimId())) available = available.add(depositSetoffMoney(adjustment.getAmount()));
                    }
                    available = available.min(detail.getOriginAmount()).min(currentCoopHeadNet(head));
                    if (available.signum() <= 0) continue;
                    BigDecimal offset = available.min(remaining);
                    coopSettlementClaimService.appendExecutedAdjustment(detail.getClaimId(), event, offset.negate());
                    CoopSettlementAdjustment receipt = coopSettlementAdjustmentMapper.selectByClaimEventForUpdate(detail.getClaimId(), "ADJUSTMENT", event);
                    requireDepositSetoffReceipt(receipt, head, detail, event);
                    if (receipt.getAmount().compareTo(offset.negate()) != 0) throw new IllegalStateException("DEPOSIT_SETOFF_RECEIPT_AMOUNT_CONFLICT");
                    CoopSettlement credit = newSettlementLine(topic, null, offset, "DEPOSIT_DEDUCTION", "platform", 1,
                            payable, now, verifiedHeads, verifiedSales, verifiedRegIds);
                    // The original order/head/claim and invite are reached through this immutable adjustment.
                    credit.setSettlementRoute("DEPOSIT_SETOFF"); credit.setSourceContractType("DEPOSIT_SETOFF");
                    credit.setSourceContractId(receipt.getId()); credit.setCommercialContextType("TOPIC_EDITION");
                    credit.setCommercialContextId(topic.getId()); credit.setBeneficiaryRole("PLATFORM");
                    credit.setServiceUnit("UNPAID_DEPOSIT"); pending.add(credit);
                    remaining = remaining.subtract(offset); collected = collected.add(offset);
                }
            }
            if (collected.signum() == 0) continue;
            if (coopInviteMapper.updateDepositIfCurrent(invite.getId(), 5, invite.getDepositAmount(),
                    remaining.signum() == 0 ? 3 : 5, remaining.signum() == 0 ? invite.getDepositAmount() : remaining) != 1)
                throw new IllegalStateException("保证金抵扣状态冲突 inviteId=" + invite.getId());
        }
    }

    /** Original caller already owns topic; lock invite IDs in one deterministic order, no provider I/O. */
    private List<CoopInvite> lockedDepositSetoffInvites(CmsTopic topic) {
        CoopInvite query = new CoopInvite(); query.setTopicId(topic.getId()); query.setStatus(1);
        List<CoopInvite> accepted = coopInviteMapper.selectCoopInviteList(query);
        if (accepted == null) throw new IllegalStateException("DEPOSIT_SETOFF_INVITES_REQUIRED");
        List<CoopInvite> ordered = new ArrayList<>(accepted);
        if (ordered.stream().anyMatch(row -> row == null || row.getId() == null || row.getId() <= 0))
            throw new IllegalStateException("DEPOSIT_SETOFF_INVITE_ID_REQUIRED");
        ordered.sort(Comparator.comparing(CoopInvite::getId));
        List<CoopInvite> due = new ArrayList<>();
        for (CoopInvite snapshot : ordered) {
            if (Integer.valueOf(CoopInvite.TYPE_LEGACY_MERCHANT_NODE).equals(snapshot.getInviteType())) continue;
            CoopInvite current = coopInviteMapper.selectCoopInviteByIdForUpdate(snapshot.getId());
            if (current == null || !topic.getId().equals(current.getTopicId()) || !Integer.valueOf(1).equals(current.getStatus()) || Integer.valueOf(1).equals(current.getDelFlag()))
                throw new IllegalStateException("DEPOSIT_SETOFF_INVITE_SCOPE_CONFLICT");
            if (Integer.valueOf(1).equals(current.getDepositStatus()))
                throw new IllegalStateException("MANUAL_REPAIR:DEPOSIT_PAYMENT_RECONCILIATION_REQUIRED inviteId=" + current.getId());
            if (Integer.valueOf(5).equals(current.getDepositStatus())) {
                if (!(Integer.valueOf(0).equals(current.getInviteType()) || Integer.valueOf(1).equals(current.getInviteType()))
                        || current.getDepositRefundState() != null || current.getDepositRefundNo() != null
                        || current.getDepositPaidTransactionId() != null)
                    throw new IllegalStateException("DEPOSIT_SETOFF_UNPAID_IDENTITY_CONFLICT");
                depositSetoffMoney(current.getDepositAmount());
                due.add(current);
            }
        }
        return due;
    }

    private static BigDecimal depositSetoffMoney(BigDecimal amount) {
        if (amount == null) throw new IllegalStateException("DEPOSIT_SETOFF_AMOUNT_REQUIRED");
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static void requireDepositSetoffReceipt(CoopSettlementAdjustment receipt, CoopSettlement head,
            CoopSettlementDetail detail, String event) {
        if (receipt == null || receipt.getId() == null || !head.getId().equals(receipt.getSettlementId())
                || !detail.getClaimId().equals(receipt.getClaimId()) || !event.equals(receipt.getEventKey())
                || !"ADJUSTMENT".equals(receipt.getEntryKind()) || !"EXECUTED".equals(receipt.getStatus())
                || depositSetoffMoney(receipt.getAmount()).signum() >= 0
                || receipt.getAmount().negate().compareTo(detail.getOriginAmount()) > 0)
            throw new IllegalStateException("DEPOSIT_SETOFF_RECEIPT_IDENTITY_CONFLICT");
    }

    /**
     * 结算撞上支付中时,必须先问支付渠道的真实状态,不能把本地 status=1 猜成未缴。
     * 已支付转冻结(随后统一退款);未支付先关闭微信订单再转待缴,才可安全从应收抵扣。
     */
    private void reconcileTopicPendingDepositPayments(Long topicId) {
        requireDepositProofOutsideTransaction();
        CoopInvite query = new CoopInvite(); query.setTopicId(topicId); query.setStatus(1);
        List<CoopInvite> candidates = coopInviteMapper.selectCoopInviteList(query);
        if (candidates == null) throw new IllegalStateException("保证金查证范围缺失 topicId=" + topicId);
        for (CoopInvite candidate : candidates) {
            if (candidate == null || !topicId.equals(candidate.getTopicId()) || !Integer.valueOf(1).equals(candidate.getStatus()))
                throw new IllegalStateException("保证金查证范围变化 topicId=" + topicId);
            if (!Integer.valueOf(CoopInvite.TYPE_LEGACY_MERCHANT_NODE).equals(candidate.getInviteType())
                    && Integer.valueOf(1).equals(candidate.getDepositStatus())) resolvePendingDepositPaymentForSettlement(candidate);
        }
    }

    private static void requireDepositProofOutsideTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("保证金渠道查证必须在业务事务与行锁之外执行");
    }

    private boolean resolvePendingDepositPaymentForSettlement(CoopInvite invite) {
        requireDepositProofOutsideTransaction();
        if (invite == null || invite.getId() == null || invite.getTopicId() == null
                || invite.getDepositOutTradeNo() == null || invite.getDepositOutTradeNo().isBlank()
                || invite.getDepositAmount() == null || !Integer.valueOf(1).equals(invite.getDepositStatus()))
            throw new IllegalStateException("保证金支付原身份缺失");
        // Freeze the observation; a later current read must never retarget an old provider result.
        CoopInvite observed = new CoopInvite(); observed.setId(invite.getId()); observed.setTopicId(invite.getTopicId());
        observed.setDepositOutTradeNo(invite.getDepositOutTradeNo()); observed.setDepositAmount(invite.getDepositAmount());
        Map<String, String> queried = wechatPayService.queryOrderStatus(observed.getDepositOutTradeNo());
        if (queried == null || !observed.getDepositOutTradeNo().equals(queried.get("out_trade_no")))
            throw new IllegalStateException("保证金支付查单归属不匹配 inviteId=" + observed.getId());
        Map<String, String> payment = new HashMap<>(queried);
        String attempt = payment.get("wx_out_trade_no");
        if (attempt == null || attempt.isBlank()) throw new IllegalStateException("保证金支付尝试身份缺失 inviteId=" + observed.getId());
        String state = payment.get("trade_state");
        if ("NOTPAY".equalsIgnoreCase(state) || "USERPAYING".equalsIgnoreCase(state)
                || "PAYERROR".equalsIgnoreCase(state) || "REVOKED".equalsIgnoreCase(state)) {
            try {
                wechatPayService.closePaymentAttempt(observed.getDepositOutTradeNo(), attempt);
            } catch (RuntimeException closeUnknown) {
                logger.warn("保证金原支付尝试关单未确认，按原号回读 inviteId={}", observed.getId(), closeUnknown);
            }
            // Close acceptance/failure is not a terminal fact. Requery the observed original attempt,
            // outside all business transactions; never resolve the business alias to a successor here.
            Map<String, String> readback = wechatPayService.queryOrderStatus(attempt);
            if (readback == null || !observed.getDepositOutTradeNo().equals(readback.get("out_trade_no"))
                    || !attempt.equals(readback.get("wx_out_trade_no")))
                throw new IllegalStateException("保证金关单回读归属不匹配 inviteId=" + observed.getId());
            if ("SUCCESS".equalsIgnoreCase(readback.get("trade_state"))) {
                // A state string alone cannot authorize a paid CAS. Require the
                // original typed reference; the existing short transaction below
                // verifies its persisted fact/current attempt before consuming it.
                // Proven SUCCESS is paid immediately, never classified as unpaid.
                CoopInviteServiceImpl.checkedDepositPaymentReference(readback);
            }
            payment = new HashMap<>(readback);
        }
        if (!"SUCCESS".equalsIgnoreCase(payment.get("trade_state"))
                && !"CLOSED".equalsIgnoreCase(payment.get("trade_state")))
            throw new IllegalStateException("保证金支付状态无法确认 inviteId=" + observed.getId());
        Map<String, String> observedPayment = payment;
        TransactionTemplate mutation = new TransactionTemplate(transactionManager);
        mutation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Boolean unpaid = mutation.execute(status -> applyObservedDepositPaymentForSettlement(observed, observedPayment));
        if (Boolean.TRUE.equals(unpaid)) invite.setDepositStatus(5); // Only after the short business transaction commits.
        return Boolean.TRUE.equals(unpaid);
    }

    /** No provider I/O. Current topic -> invite -> observed attempt fence precedes the original local CAS. */
    private boolean applyObservedDepositPaymentForSettlement(CoopInvite observed, Map<String, String> payment) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("保证金状态复核必须加入原业务短事务");
        lockSettlementTopic(observed.getTopicId());
        CoopInvite current = coopInviteMapper.selectCoopInviteByIdForUpdate(observed.getId());
        if (current == null || !observed.getId().equals(current.getId())
                || !observed.getTopicId().equals(current.getTopicId()) || !Integer.valueOf(1).equals(current.getStatus())
                || Integer.valueOf(CoopInvite.TYPE_LEGACY_MERCHANT_NODE).equals(current.getInviteType())
                || !observed.getDepositOutTradeNo().equals(current.getDepositOutTradeNo())
                || current.getDepositAmount() == null || observed.getDepositAmount().compareTo(current.getDepositAmount()) != 0)
            throw new IllegalStateException("保证金原身份或金额已变化 inviteId=" + observed.getId());
        String business = observed.getDepositOutTradeNo(); String attempt = payment.get("wx_out_trade_no");
        if ("SUCCESS".equalsIgnoreCase(payment.get("trade_state"))) {
            var reference = CoopInviteServiceImpl.checkedDepositPaymentReference(payment);
            if (!"CNY".equals(payment.get("currency"))) throw new IllegalStateException("保证金币种不匹配");
            Long paidCents;
            try { paidCents = Long.valueOf(payment.get("total")); }
            catch (RuntimeException invalid) { throw new IllegalStateException("保证金支付金额无法确认", invalid); }
            if (!wechatPayService.lockPaymentAttemptForUpdate(business, attempt)
                    || !coopInviteService.markDepositPaid(business, paidCents, payment.get("transaction_id"), reference))
                throw new IllegalStateException("保证金支付事实不匹配 inviteId=" + observed.getId());
            CoopInvite confirmed = coopInviteMapper.selectCoopInviteByIdForUpdate(observed.getId());
            if (confirmed == null || !Integer.valueOf(2).equals(confirmed.getDepositStatus())
                    || !business.equals(confirmed.getDepositOutTradeNo())
                    || !java.util.Objects.equals(payment.get("transaction_id"), confirmed.getDepositPaidTransactionId()))
                throw new IllegalStateException("保证金支付状态读回失败 inviteId=" + observed.getId());
            return false;
        }
        if (!Integer.valueOf(1).equals(current.getDepositStatus())
                || !"CLOSED".equalsIgnoreCase(payment.get("trade_state"))
                || !wechatPayService.lockClosablePaymentAttemptForUpdate(business, attempt))
            throw new IllegalStateException("保证金支付尝试已变化 inviteId=" + observed.getId());
        if (coopInviteMapper.updateDepositIfCurrent(current.getId(), 1, current.getDepositAmount(), 5, current.getDepositAmount()) != 1)
            throw new IllegalStateException("保证金关闭支付后状态冲突 inviteId=" + current.getId());
        return true;
    }

    /** 构建一条分项结算行(公共字段);order 特有字段(coopOrderId/shareMode 等)由调用方补。 */
    private CoopSettlement newSettlementLine(CmsTopic topic, Long payee, BigDecimal amount, String lineType, String payeeType,
                                             int status, Date payable, Date now, int verifiedHeads, BigDecimal verifiedSales, String verifiedRegIds) {
        CoopSettlement s = new CoopSettlement();
        s.setCashHeadKind("platform".equals(payeeType) ? "ACCOUNTING" : "MEMBER");
        s.setTopicId(topic.getId());
        s.setMerchantId(payee);
        s.setLineType(lineType);
        s.setPayeeType(payeeType);
        s.setAmount(amount);
        s.setVerifiedHeads(verifiedHeads);
        s.setVerifiedSales(verifiedSales);
        s.setVerifiedRegIds(verifiedRegIds);
        s.setStatus(status);
        s.setPayableTime(payable);
        s.setSettleTime(now);
        s.setDelFlag(0);
        s.setCreateTime(now);
        return s;
    }

    /**
     * coop 结算受款人解析:合作单受邀方 toType=club 时,受款人是俱乐部主理人(Club.memberId),而非 club_id 本身;
     * 否则 toId 即商家 member_id。与 CoopInviteServiceImpl.resolveInviteeOwner / ApiCoopController.resolveToOwner 同款。
     * 修复:此前 settleCoopTopic 直接 setMerchantId(o.getToId()),toType=club 的结算单 merchantId=club_id,
     * payoutDueCoopSettlements 的 selectUmsMemberById(club_id) 取不到人 → 俱乐部分成永远打不出去(资损+静默失败)。
     */
    private Long resolveOrderPayee(CoopOrder o) {
        if ("club".equals(o.getToType()) && o.getToId() != null) {
            com.chengyinhub.business.domain.Club c = clubService.selectClubById(o.getToId());
            return c == null ? null : c.getMemberId();
        }
        return o.getToId();
    }

    /**
     * 五分项结算守恒校验:平台 + 发起人保底 + 承接/商家应收 + 发起人毛利 必须精确等于总销售额,
     * 且不得超拆(毛利为负=平台+保底+商家 > 总销售额=资损)。破坏任一 → 越界告警 + 抛异常(动钱之前),交人工核查。
     */
    private void assertSettlementConservation(CmsTopic topic, BigDecimal totalSales,
            BigDecimal platformAmount, BigDecimal guaranteeAmount, BigDecimal merchantTotal, BigDecimal marginAmount) {
        BigDecimal sum = platformAmount.add(guaranteeAmount).add(merchantTotal).add(marginAmount);
        if (sum.compareTo(totalSales) != 0) {
            logger.error("【结算越界告警】守恒被破坏 topicId={}: 平台{}+保底{}+承接商家{}+毛利{}={} ≠ 总销售额{}",
                    topic.getId(), platformAmount, guaranteeAmount, merchantTotal, marginAmount, sum, totalSales);
            throw new IllegalStateException("coop结算守恒校验失败 topicId=" + topic.getId()
                    + ", 四项之和=" + sum + ", 总销售额=" + totalSales);
        }
        if (marginAmount.compareTo(BigDecimal.ZERO) < 0) {
            logger.error("【结算越界告警】拆分>100% topicId={}: 平台{}+保底{}+承接商家{} > 总销售额{},发起人毛利为负{},拒绝结算",
                    topic.getId(), platformAmount, guaranteeAmount, merchantTotal, totalSales, marginAmount);
            throw new IllegalStateException("coop结算超拆(拆分>100%) topicId=" + topic.getId()
                    + ", 毛利=" + marginAmount + ", 平台=" + platformAmount + ", 保底=" + guaranteeAmount + ", 商家=" + merchantTotal);
        }
    }

    /** 完成退保证金:退还该主题所有已接受合作的冻结(2)保证金。失败保留冻结态并告警,可由退款重试入口重放。 */
    private void refundTopicDeposits(Long topicId) {
        // Includes the zero-sales path: a new prepay in the observation gap rolls back this whole close.
        List<CoopInvite> current = coopInviteMapper.selectAcceptedByTopicForUpdate(topicId);
        if (current == null || current.stream().anyMatch(row -> row == null
                || (!Integer.valueOf(CoopInvite.TYPE_LEGACY_MERCHANT_NODE).equals(row.getInviteType())
                    && Integer.valueOf(1).equals(row.getDepositStatus()))))
            throw new IllegalStateException("MANUAL_REPAIR:DEPOSIT_PAYMENT_RECONCILIATION_REQUIRED topicId=" + topicId);
        coopInviteService.requestTopicDepositRefunds(topicId);
    }

    private void alarmPendingDepositRefund(Long inviteId, String refundNo, String state) {
        try {
            alarmService.alarm("合作保证金退款待处理",
                    "inviteId=" + (inviteId == null ? "unknown" : inviteId)
                            + " refundNo=" + (refundNo == null ? "unknown" : refundNo) + " state=" + state);
        } catch (Exception e) {
            logger.error("合作保证金退款告警发送失败 inviteId={}", inviteId, e);
        }
    }

    @Override
    public int payoutDueCoopSettlements() {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        Date now = new Date();
        List<CoopSettlement> due = coopSettlementMapper.selectDuePayouts(now);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int paid = 0;
        List<String> failures = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("coop 打款失败");
        for (CoopSettlement snapshot : due) {
            try {
                CoopSettlement paidSettlement = tx.execute(status -> payoutOneDueCoopSettlement(
                        snapshot.getId(), snapshot.getTopicId(), now));
                if (paidSettlement == null) continue;
                paid++;
                // 订阅消息:通知商家结算到账(best-effort)
                try {
                    String desc = "主题#" + paidSettlement.getTopicId() + " 核销"
                            + (paidSettlement.getVerifiedHeads() == null ? 0 : paidSettlement.getVerifiedHeads()) + "人";
                    String when = DateUtils.parseDateToStr("yyyy-MM-dd HH:mm", now);
                    notifyService.notifyCoopSettle(paidSettlement.getMerchantId(),
                            "¥" + paidSettlement.getAmount(), when, desc, String.valueOf(paidSettlement.getId()));
                } catch (Exception ignore) {}
            } catch (Exception e) {
                // 异常已先越过 REQUIRES_NEW 事务边界并触发整行回滚；这里只负责隔离后续结算行。
                logger.error("coop 打款失败 settlementId={}", snapshot.getId(), e);
                failures.add("settlementId=" + snapshot.getId() + ": " + e.getMessage());
                failure.addSuppressed(e);
            }
        }
        // 1-04:同 R9-46 §4 —— 逐行失败不能被吞成 SUCCESS。其余行已各自提交,末端再抛让 Quartz 记 FAIL 并点名。
        if (!failures.isEmpty()) {
            IllegalStateException visible = new IllegalStateException("coop 打款失败 " + failures.size()
                    + " 笔(成功 " + paid + " 笔): " + String.join("; ", failures));
            for (Throwable t : failure.getSuppressed()) visible.addSuppressed(t);
            throw visible;
        }
        if (paid > 0) logger.info("coop T+7 打款完成,共 {} 笔", paid);
        return paid;
    }

    /** 单张结算独立事务内完成领取、余额、流水和终态；返回 null 表示本轮未发生正额打款。 */
    private CoopSettlement payoutOneDueCoopSettlement(Long settlementId, Long observedTopicId, Date now) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        CmsTopic topic = lockSettlementTopic(observedTopicId);
        // 同topic先按头ID锁定并读取全部当前净额。未决定承担方的负头不能靠先付别的头耗尽资金。
        List<CoopSettlement> topicHeads = coopSettlementOriginalMapper.selectTopicHeadsForUpdate(topic.getId());
        if (topicHeads == null) throw new IllegalStateException("CURRENT_TOPIC_HEADS_REQUIRED");
        for (CoopSettlement head : topicHeads) {
            if (head == null || !topic.getId().equals(head.getTopicId())) {
                throw new IllegalStateException("CURRENT_TOPIC_HEAD_IDENTITY_CONFLICT");
            }
            if (!Integer.valueOf(2).equals(head.getStatus()) && currentCoopHeadNet(head).signum() < 0) {
                throw new IllegalStateException("NEGATIVE_SETTLEMENT_HEAD_POLICY_REQUIRED:settlementId=" + head.getId());
            }
        }
        CoopSettlement s = coopSettlementMapper.selectDuePayoutForUpdate(settlementId, now);
        if (s == null) {
            return null;
        }
        if (!topic.getId().equals(s.getTopicId())) {
            throw new IllegalStateException("coop结算主题归属已变化 settlementId=" + settlementId);
        }
        // 客诉冻结:该主题有未结工单 → 暂缓放款(含 0 元行也不置已打款),工单关闭后下轮再放。
        if (s.getTopicId() != null && coopComplaintMapper.countOpenByTopicId(s.getTopicId()) > 0) {
            return null;
        }
        BigDecimal payoutAmount = currentCoopHeadNet(s);
        if (payoutAmount.signum() < 0) {
            throw new IllegalStateException("NEGATIVE_SETTLEMENT_HEAD_POLICY_REQUIRED:settlementId=" + s.getId());
        }
        if (payoutAmount.signum() == 0) {
            requireExactlyOne(coopSettlementMapper.markPaidIfPending(s.getId(), now), "置零元结算为已打款", s.getId());
            return null;
        }

        Long lockedMemberId = umsMemberMapper.lockMemberById(s.getMerchantId());
        if (lockedMemberId == null || !lockedMemberId.equals(s.getMerchantId())) {
            // 存量脏数据(merchantId 为空/俱乐部已删)兜底:不再静默跳过,打告警让对账/运维发现
            throw new IllegalStateException("coop结算受款人不存在,分成打不出(受款人为空/俱乐部已删),需人工核查 settlementId="
                    + s.getId() + " merchantId=" + s.getMerchantId() + " amount=" + s.getAmount());
        }
        UmsMember member = umsMemberMapper.selectUmsMemberById(s.getMerchantId());
        if (member == null) {
            throw new IllegalStateException("coop结算受款人锁定后无法读取 settlementId=" + s.getId());
        }
        BigDecimal currentBalance = member.getBalance() != null ? member.getBalance() : BigDecimal.ZERO;
        requireExactlyOne(umsMemberMapper.addBalance(s.getMerchantId(), payoutAmount), "增加受款人余额", s.getId());
        UmsMember after = umsMemberMapper.selectUmsMemberById(s.getMerchantId());
        if (after == null || after.getBalance() == null) {
            throw new IllegalStateException("coop结算余额更新后无法读取 settlementId=" + s.getId());
        }

        UmsMemberBalanceDetail detail = new UmsMemberBalanceDetail();
        detail.setMemberId(s.getMerchantId());
        detail.setCurrentBalance(currentBalance);
        detail.setAfterBalance(after.getBalance());
        detail.setChangeBalance(payoutAmount);
        detail.setChangeReason("Coop结算打款 - 主题" + s.getTopicId());
        detail.setEventType(5L);
        detail.setEventId(s.getId());
        detail.setChangeType(1L);
        detail.setDelFlag(0L);
        detail.setCreateTime(now);
        requireExactlyOne(umsMemberBalanceDetailService.insertUmsMemberBalanceDetail(detail), "写入余额流水", s.getId());
        requireExactlyOne(coopSettlementMapper.markPaidIfPending(s.getId(), now), "置结算为已打款", s.getId());
        // 9-16 拍板第 2 条:客诉期后退款/扣划留下的欠款,从这笔结算入账里先抵。
        settlementDebtLedger.offsetFromSettlement(s.getMerchantId(), payoutAmount, s.getId());
        // 只改返回对象供通知显示净打款；数据库原始头 amount 永不被调整覆盖。
        s.setAmount(payoutAmount);
        return s;
    }

    /** 已持有topic和head锁；不使用RR快照SUM或MyBatis一级缓存作为当前出款基数。 */
    private BigDecimal currentCoopHeadNet(CoopSettlement head) {
        if (head.getId() == null || head.getAmount() == null) throw new IllegalStateException("CURRENT_HEAD_AMOUNT_REQUIRED");
        BigDecimal net = head.getAmount();
        List<CoopSettlementAdjustment> legacy = coopSettlementAdjustmentMapper.selectExecutedBySettlementForUpdate(head.getId());
        if (legacy == null) throw new IllegalStateException("CURRENT_LEGACY_ADJUSTMENTS_REQUIRED");
        for (CoopSettlementAdjustment row : legacy) {
            if (row == null || !head.getId().equals(row.getSettlementId()) || row.getAmount() == null
                    || !"EXECUTED".equals(row.getStatus())) throw new IllegalStateException("CURRENT_LEGACY_ADJUSTMENT_CONFLICT");
            net = net.add(row.getAmount());
        }
        List<CoopSettlementOriginalAdjustment> originals = coopSettlementOriginalMapper.selectAdjustmentsForSettlementForUpdate(head.getId());
        if (originals == null) throw new IllegalStateException("ORIGINAL_REFUND_ADJUSTMENTS_UNAVAILABLE");
        for (CoopSettlementOriginalAdjustment row : originals) {
            if (row == null || !head.getId().equals(row.getSettlementId()) || row.getAmount() == null) {
                throw new IllegalStateException("ORIGINAL_REFUND_ADJUSTMENT_IDENTITY_CONFLICT");
            }
            net = net.add(row.getAmount());
        }
        return net;
    }

    private CmsTopic lockSettlementTopic(Long topicId) {
        if (topicId == null) throw new IllegalStateException("SETTLEMENT_TOPIC_REQUIRED");
        CmsTopic locked = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (locked == null || !topicId.equals(locked.getId())) {
            throw new IllegalStateException("SETTLEMENT_TOPIC_NOT_FOUND:" + topicId);
        }
        return locked;
    }

    private void requireExactlyOne(int rows, String operation, Long settlementId) {
        if (rows != 1) {
            throw new IllegalStateException(operation + "影响行数异常 settlementId=" + settlementId + " rows=" + rows);
        }
    }

    /** T9:把已核销报名 id 列表拼成 CSV 快照(空/null → null,不落库) */
    private String joinVerifiedRegIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (id == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** coop 核销销售额:优先取已核销;若尚无核销数据(如线上未接核销)回退 0 */
    private BigDecimal coopVerifiedSales(Long topicId) {
        BigDecimal v = cmsRegistrationMapper.selectVerifiedSalesByTopicId(topicId);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** [资损⑨] 某商家在该主题下【自己核销的】销售额(走 scan registration_type=1,认得出谁接待了谁)。 */
    private BigDecimal merchantVerifiedSales(Long topicId, Long merchantId) {
        BigDecimal v = cmsRegistrationMapper.selectVerifiedSalesByTopicAndMerchant(topicId, merchantId);
        return v == null ? BigDecimal.ZERO : v;
    }

    // A3-7:updateTopicSettlementStatus 已删 —— is_settlement 置位改由入口 claimTopicForSettlement(CAS)承担。

    // 以下原有方法保持不变...

    /**
     * 查询主题
     *
     * @param id 主题主键
     * @return 主题
     */
    @Override
    public CmsTopic selectCmsTopicById(Long id) {
        return cmsTopicMapper.selectCmsTopicById(id);
    }

    @Override
    public Map<Long, java.util.Date> selectTodoTopicEndDates(java.util.Collection<Long> topicIds) {
        Map<Long, java.util.Date> dates = new java.util.HashMap<>();
        if (topicIds == null || topicIds.isEmpty()) return dates;
        for (CmsTopic topic : cmsTopicMapper.selectTodoTopicEndDates(topicIds)) {
            dates.put(topic.getId(), topic.getEndDate());
        }
        return dates;
    }

    @Override
    public Integer selectRoamReferralEnabledById(Long id) {
        return cmsTopicMapper.selectRoamReferralEnabledById(id);
    }

    @Override
    public CmsTopic selectPublicCmsTopicById(Long id) {
        return topicQueryService.selectPublicCmsTopicById(id);
    }

    @Override
    public CmsTopic selectCmsTopicDetailById(Long id, Long viewerMemberId) {
        return topicQueryService.selectCmsTopicDetailById(id, viewerMemberId);
    }

    @Override
    public CmsTopic selectMerchantVisibleCmsTopicById(Long id) {
        return topicQueryService.selectMerchantVisibleCmsTopicById(id);
    }

    @Override
    public CmsTopic selectMerchantManagedCmsTopicById(Long id, Long merchantId) {
        return topicQueryService.selectMerchantManagedCmsTopicById(id, merchantId);
    }

    /**
     * 查询主题列表
     *
     * @param cmsTopic 主题
     * @return 主题
     */
    @Override
    public List<CmsTopic> selectCmsTopicList(CmsTopic cmsTopic) {
        return topicQueryService.selectCmsTopicList(cmsTopic);
    }

    @Override
    public Map<Long, Integer> selectUpcomingTopicCounts(List<Long> clubIds) {
        return topicQueryService.selectUpcomingTopicCounts(clubIds);
    }

    @Override
    public List<CmsTopic> selectPublicCmsTopicList(CmsTopic cmsTopic) {
        return topicQueryService.selectPublicCmsTopicList(cmsTopic);
    }



    /**
     * 新增主题
     *
     * @param cmsTopic 主题
     * @return 结果
     */
    @Override
    public int insertCmsTopic(CmsTopic cmsTopic) {
        if (platformMemberId == null || platformMemberId <= 0L) {
            throw new IllegalStateException("平台主题发布者会员ID未配置");
        }
        cmsTopic.setMemberId(platformMemberId);
        cmsTopic.setCreateTime(DateUtils.getNowDate());
        int rows = cmsTopicMapper.insertCmsTopic(cmsTopic);
        // [P0-35] 后台建题 = 平台自己发 ⇒ 内容槽 = PLATFORM(③-自营,首轮标的 D12)。
        // 判据是**入口**不是数据:能过 @PreAuthorize('business:topic:add') 的就是平台运营,
        // 小程序创作者走的是另一条 createTopic。这比拿 member_id 反推可靠 —— 后者正是
        // CmsTopicServiceImpl:208 那句无名裸魔数 setMemberId(100000L) 干的事。
        //
        // ⚠️ [Task 2] 这条路只适用于「官方自己发」。要让后台【代俱乐部/代会员发】,走
        // createTopicByPublisher(CmsTopicCreateDTO) —— 那里发布者是显式声明的,内容槽跟着发布者走。
        // 入口判归属在「入口只有一种用法」时成立,一旦同一入口能代发就失效了。
        productRoleService.declareContentSlot(cmsTopic.getId(), RoleEntityType.PLATFORM, null);
        return rows;
    }

    /**
     * [Task 2] 后台发主题 —— 发布者身份决定归属。
     *
     * <p>「官方发的就是官方发的,俱乐部发的就是俱乐部发的。」(用户 2026-07-17 口述)
     * 这是本轨的核心不变式:内容槽 = 发布者,票款归内容槽持有者(定稿 §6.5-B)。
     *
     * <p>发布者【显式声明】,不从 memberId 反推 —— 反推正是 setMemberId(100000L) 那句裸魔数的病根。
     * 建题与声明槽同事务:槽是票款归属的唯一真源,建了题却没有槽 ⇒ REVSHARE 会 fail-closed 拒,
     * 而那时题已经建出来了,只能靠人工补 —— 所以宁可整个回滚。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTopicByPublisher(CmsTopicCreateDTO req, String operator) {
        if (req == null || StringUtils.isBlank(req.getName())) {
            throw new IllegalArgumentException("主题名称不能为空");
        }
        String type = req.getPublisherType() == null ? null : req.getPublisherType().trim().toUpperCase();
        // ⚠️ 这道闸原本的理由是「CmsTopic.memberId 的注释写着商家不能发布」——
        // 而那句注释 2026-08-07 已核实过时(小程序链路放行商家,生产权限位 can_create_theme=1)。
        // 两处互相引用、谁也没重新核过。
        // 闸暂时保留:后台代建与商家自建是两回事,是否对齐属产品决策,未拍板前不擅改。
        // 要改的话请连同 CmsTopic.memberId 的注释一起改,别只改一边。
        if (!RoleEntityType.PLATFORM.equals(type) && !RoleEntityType.CLUB.equals(type)
                && !RoleEntityType.MEMBER.equals(type)) {
            throw new IllegalArgumentException("publisherType 须为 PLATFORM / CLUB / MEMBER 之一");
        }
        // 与库里的 ck_role_entity_id 同口径:PLATFORM 不带 entityId,其余必须带。
        // 先在这儿拦,别等 DB 的 CHECK 抛一个运营看不懂的错。
        if (!RoleEntityType.entityIdConsistent(type, req.getPublisherId())) {
            throw new IllegalArgumentException(RoleEntityType.PLATFORM.equals(type)
                    ? "官方发布不得指定 publisherId(平台不是某个 member)"
                    : "publisherType=" + type + " 必须指定 publisherId");
        }

        // H8 后 topic.memberId 是 event 15 结算积分的资格承接人；不得落空，否则有销售的主题会 fail-closed。
        Long payee = resolvePayeeMemberId(type, req);
        if (payee == null || payee <= 0) {
            throw new IllegalArgumentException(RoleEntityType.CLUB.equals(type)
                    ? "俱乐部发布必须指定 payeeMemberId(= 该俱乐部主理人);俱乐部本身不是一个会员账号,收不了款"
                    : "必须指定 payeeMemberId");
        }
        if (RoleEntityType.CLUB.equals(type)) {
            Club club = clubService.selectClubByIdForUpdate(req.getPublisherId());
            if (club == null) {
                throw new IllegalArgumentException("发布俱乐部已解散或不存在");
            }
            if (club.getMemberId() == null || !club.getMemberId().equals(payee)) {
                throw new IllegalArgumentException("payeeMemberId 必须是该俱乐部主理人");
            }
        }

        CmsTopic topic = new CmsTopic();
        topic.setName(req.getName());
        topic.setSubtitle(req.getSubtitle());
        topic.setDescription(req.getDescription());
        topic.setImgUrl(req.getImgUrl());
        topic.setImgArr(req.getImgArr());
        topic.setCategoryIds(req.getCategoryIds());
        topic.setProductType(req.getProductType() == null ? 1 : req.getProductType());
        topic.setStartDate(req.getStartDate());
        topic.setEndDate(req.getEndDate());
        topic.setSaleEndAt(req.getSaleEndAt());
        topic.setMerchantSignUpStartDate(req.getMerchantSignUpStartDate());
        topic.setMerchantSignUpEndDate(req.getMerchantSignUpEndDate());
        topic.setAddressName(req.getAddressName());
        topic.setMemberId(payee);
        topic.setClubId(RoleEntityType.CLUB.equals(type) ? req.getPublisherId() : null);
        // 新建题一律从头走审核/上架/生命周期,别让「后台建的」自带特权。
        topic.setStatus(0);
        topic.setCheckStatus(0);
        topic.setUserStatus(0);
        topic.setMerchantStatus(0);
        topic.setIsSettlement(0);
        topic.setIsRecommend(0);
        topic.setIsSync(0);
        topic.setLifecycle(0);
        topic.setDelFlag(0);
        topic.setCreateBy(operator);
        topic.setCreateTime(DateUtils.getNowDate());
        cmsTopicMapper.insertCmsTopic(topic);

        if (RoleEntityType.PLATFORM.equals(type)) {
            declareRequiredPlatformContentSlot(topic.getId());
            if (Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
                productRoleService.declareRequiredOrganizerSlot(topic.getId(),
                        com.chengyinhub.common.utils.SecurityUtils.getUserId());
            }
        } else {
            productRoleService.declareContentSlot(topic.getId(), type, req.getPublisherId());
        }
        logger.info("后台发主题: topicId={}, publisherType={}, publisherId={}, payeeMemberId={}, by={}",
                topic.getId(), type, req.getPublisherId(), payee, operator);
        return topic.getId();
    }

    /**
     * PLATFORM 的收款人只能来自部署配置；CLUB 必须显式指定(俱乐部不是会员账号)；
     * MEMBER 留空则取 publisherId 本人。
     */
    private Long resolvePayeeMemberId(String type, CmsTopicCreateDTO req) {
        if (RoleEntityType.PLATFORM.equals(type)) {
            if (req.getPayeeMemberId() != null) {
                throw new IllegalArgumentException("官方主题不得指定 payeeMemberId；收款主体固定为平台配置账号");
            }
            return platformMemberId;
        }
        if (req.getPayeeMemberId() != null) {
            return req.getPayeeMemberId();
        }
        if (RoleEntityType.MEMBER.equals(type)) {
            return req.getPublisherId();
        }
        return null;   // CLUB:主理人无从推断,必须显式给
    }

    /**
     * 修改主题
     *
     * @param cmsTopic 主题
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateCmsTopic(CmsTopic cmsTopic) {
        CmsTopic current = cmsTopic == null || cmsTopic.getId() == null
                ? null : cmsTopicMapper.selectCmsTopicLifecycleById(cmsTopic.getId());
        TopicLifecycleState currentLifecycle = null;
        if (current != null) {
            try {
                currentLifecycle = TopicLifecycleState.fromCode(current.getLifecycle());
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("主题生命周期未知或缺失，拒绝通用更新", ex);
            }
            if (currentLifecycle.isCommercialTermsFrozen()) {
                assertSellingFrozenPatch(cmsTopic);
            }
        }
        if (Integer.valueOf(1).equals(cmsTopic.getUserStatus())) {
            assertOrdinarySupplyRestored(cmsTopicMapper.selectCmsTopicByIdForUpdate(cmsTopic.getId()));
        }
        cmsTopic.setUpdateTime(DateUtils.getNowDate());
        int rows = cmsTopicMapper.updateCmsTopic(cmsTopic);
        if (rows > 0 && (currentLifecycle == null || !currentLifecycle.isCommercialTermsFrozen())) {
            invalidateApprovedCanonical(cmsTopic.getId(), "topic-update");
        }
        return rows;
    }

    /** SELLING 后只留显式运营字段；结构、内容、时间和售卖目录不得从通用 update 借道。 */
    /**
     * 上下架 CAS：库中 user_status 等于 expected 才翻转，返回受影响行数（0 = 已被别人改掉）。
     *
     * <p>为什么不走 {@link #updateCmsTopic}：那条是无条件写 —— 「读到的态」与「写下去的态」之间
     * 有一个真实窗口，窗口里真有别的写者（内容安全回调命中违规会自动下架）。无条件写会让一次
     * 陈旧的人工点击**覆盖掉**那次自动下架，把违规主题重新推上架。
     *
     * <p>不复查 SELLING 冻结：{@code assertSellingFrozenPatch} 的冻结清单里**没有** user_status
     * —— 售票中主题允许下架是有意设计（下架 = 停售的手段），这里保持同一口径。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public int casUserStatus(Long topicId, Integer expected, Integer target) {
        if (topicId == null || expected == null || target == null) {
            return 0;
        }
        if (Integer.valueOf(1).equals(target)) {
            CmsTopic current = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
            if (current == null) return 0;
            assertOrdinarySupplyRestored(current);
            com.chengyinhub.business.service.support.CancellationReceiptStore.forTopics(cmsTopicMapper).requireRelistable(topicId);
        }
        int rows = cmsTopicMapper.casUserStatus(topicId, expected, target, DateUtils.getNowDate());
        if (rows > 0) {
            // [6-09] 上下架是主题可见性的两个方向(下架=不公开,上架=公开),同步帖一起走。
            // 加事务是为了让「翻转 + 联动」原子:链接动失败时主题状态一并回滚,不留「已下架、帖还公开」。
            syncCreativeSquarePostVisibility(topicId, Integer.valueOf(1).equals(target));
        }
        return rows;
    }

    /** 退出后的恢复只认商家实际确认的有效供给；先主题再供给，不反向锁章节。 */
    private void assertOrdinarySupplyRestored(CmsTopic topic) {
        publicationContentGuard.assertOrdinarySupplyRestored(topic);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public String topicSupplyAutoOnlineBlockReason(Long topicId) {
        return ordinarySupplyResumeBlockReason(cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId), false);
    }

    private String ordinarySupplyResumeBlockReason(CmsTopic topic, boolean explicitRestore) {
        return publicationContentGuard.ordinarySupplyResumeBlockReason(topic, explicitRestore);
    }

    /**
     * [6-09] 主题可见性 → 广场同步帖联动。所有改主题可见性的入口都从这里走,别各自拼 SQL。
     *
     * <p>失败必须抛出(同事务回滚),否则会出现「主题已下架、帖子还公开」的静默泄漏 ——
     * 这正是本卡要修的 bug。判空只为兼容单测里手工 new 出来的实例。
     */
    @Override
    public void syncCreativeSquarePostVisibility(Long topicId, boolean visible) {
        if (umsCreativeSquareService == null) {
            return;
        }
        umsCreativeSquareService.syncTopicPostVisibility(topicId, visible);
    }

    private void assertSellingFrozenPatch(CmsTopic patch) {
        if (patch.getMemberId() != null || patch.getClubId() != null || patch.getTopicType() != null
                || patch.getProductType() != null || patch.getTopicId() != null || patch.getSourceTopicId() != null
                || patch.getName() != null || patch.getSubtitle() != null || patch.getDescription() != null
                || patch.getAddressName() != null || patch.getTotalTime() != null || patch.getTotalMileage() != null
                || patch.getLocationCount() != null || patch.getTemplateCount() != null
                || patch.getStartDate() != null || patch.getEndDate() != null || patch.getSaleEndAt() != null
                || patch.getSellingStartedAt() != null || patch.getImgUrl() != null || patch.getImgArr() != null
                || patch.getCategoryIds() != null || patch.getMerchantStatus() != null || patch.getOpenClubPool() != null
                || patch.getSelfPlay() != null || patch.getSelfPlayPrice() != null || patch.getSelfPlayQuota() != null
                || patch.getRecruitDeadline() != null || patch.getLifecycle() != null) {
            throw new IllegalStateException("主题已进入 SELLING，路线/内容/章节/节点/条款/SKU/价格/时间/角色槽均已冻结");
        }
    }

    /**
     * 批量删除主题
     *
     * @param ids 需要删除的主题主键
     * @return 结果
     */
    @Override
    public int deleteCmsTopicByIds(Long[] ids) {
        return cmsTopicMapper.deleteCmsTopicByIds(ids);
    }

    /**
     * 删除主题信息
     *
     * @param id 主题主键
     * @return 结果
     */
    @Override
    public int deleteCmsTopicById(Long id) {
        return cmsTopicMapper.deleteCmsTopicById(id);
    }

    /**
     * 查询用户发布主题数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    @Override
    public int selectCmsTopicNumByMemberId(Long member_id) {
        return cmsTopicMapper.selectCmsTopicNumByMemberId(member_id);
    }

    @Override
    public int selectOnlineThemeNumByMemberId(Long memberId, Long excludeId) {
        return cmsTopicMapper.selectOnlineThemeNumByMemberId(memberId, excludeId);
    }

    @Override
    public java.util.List<CmsTopic> selectTopicTemplateList() {
        return topicQueryService.selectTopicTemplateList();
    }

    @Override
    public java.util.List<com.chengyinhub.business.domain.vo.TopicTemplateListItemVO> buildTopicTemplateListVO() {
        return topicQueryService.buildTopicTemplateListVO();
    }

    @Override
    public java.util.List<com.chengyinhub.business.domain.vo.TopicTemplateListItemVO> buildTopicTemplateListVO(boolean recommendedOnly) {
        return topicQueryService.buildTopicTemplateListVO(recommendedOnly);
    }

    @Override
    public com.chengyinhub.business.domain.vo.TopicTemplateDetailVO buildTopicTemplateDetail(Long id, boolean viewerIsMerchant) {
        return topicQueryService.buildTopicTemplateDetail(id, viewerIsMerchant);
    }

    @Override
    public com.chengyinhub.business.domain.vo.TopicTemplateDetailVO buildTopicTemplateDetail(
            Long id, boolean viewerCanViewRecruitment, Integer merchantCategoryId) {
        return topicQueryService.buildTopicTemplateDetail(id, viewerCanViewRecruitment, merchantCategoryId);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public com.chengyinhub.business.domain.vo.TopicTemplateDetailVO buildPublicTopicTemplateDetail(
            Long id, Long viewerMemberId, boolean viewerIsMerchant, Integer merchantCategoryId) {
        return topicQueryService.buildPublicTopicTemplateDetail(id, viewerMemberId, viewerIsMerchant, merchantCategoryId);
    }















    @Override
    public Integer selectTopicIsTemplate(Long id) {
        return cmsTopicMapper.selectTopicIsTemplate(id);
    }

    @Override
    public int saveAsTemplate(Long id, Long memberId, Integer flag) {
        return cmsTopicMapper.updateTopicIsTemplate(id, memberId, flag);
    }

    @Override
    public int updateTopicDraftState(Long id, Integer flag) {
        return cmsTopicMapper.updateTopicDraftState(id, flag);
    }

    @Override
    public java.util.List<Long> selectDraftTopicIds(Long memberId) {
        return cmsTopicMapper.selectDraftTopicIds(memberId);
    }

    @Override
    public Integer selectTopicIsDraft(Long id) {
        return cmsTopicMapper.selectTopicIsDraft(id);
    }

    @Override
    public void markContentSafetyHold(Long topicId) {
        if (topicId == null || topicId <= 0L) return;
        cmsTopicMapper.markContentSafetyHold(topicId);
    }

    @Override
    public void markReviewFlag(Long topicId, boolean flagged) {
        if (topicId == null || topicId <= 0L) return;
        cmsTopicMapper.updateReviewFlag(topicId, flagged ? 1 : 0);
    }

    @Override
    public int graduateBeta(Long topicId) {
        // 竞态安全:仅当前确在 Beta 期(beta_flag=1)才清,重复点击/并发只成功一次
        return cmsTopicMapper.graduateBetaIfBeta(topicId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean publishDraft(Long id) {
        return publishDraftInternal(id, null, false) != null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicBundleSubmitResultVO publishDraftWithReview(Long id) {
        return publishDraftInternal(id, null, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean publishDraft(Long id, Long expectedOwnerId) {
        if (expectedOwnerId == null || expectedOwnerId <= 0L) throw new ServiceException("缺少主办身份，请重新登录", 403);
        return publishDraftInternal(id, expectedOwnerId, true) != null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicBundleSubmitResultVO publishDraftWithReview(Long id, Long expectedOwnerId) {
        if (expectedOwnerId == null || expectedOwnerId <= 0L) throw new ServiceException("缺少主办身份，请重新登录", 403);
        return publishDraftInternal(id, expectedOwnerId, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicBundleSubmitResultVO publishDraftWithBinding(Long id, Long expectedOwnerId,
            Long expectedConfigVersion, String expectedRegistryVersion) {
        if (expectedOwnerId == null || expectedOwnerId <= 0L) throw new ServiceException("缺少主办身份，请重新登录", 403);
        return publishDraftInternal(id, expectedOwnerId, true, expectedConfigVersion, expectedRegistryVersion);
    }

    @Override
    public boolean canResubmitSupplyReview(Long id, Long ownerId) {
        return topicPublicationService.canResubmitSupplyReview(id, ownerId);
    }

    @Autowired
    private com.chengyinhub.business.service.support.AppMediaPublicationGuard mediaPublicationGuard;

    private void requireChapterDraftMedia(java.util.List<com.chengyinhub.business.domain.dto.ChapterDTO> chapterRows,
                                           Long owner, boolean publish) {
        if (chapterRows == null) return;
        for (com.chengyinhub.business.domain.dto.ChapterDTO chapter : chapterRows) {
            mediaPublicationGuard.requireChapter(owner, chapter, publish);
            if (chapter.getBlocks() != null) for (com.chengyinhub.business.domain.dto.ChapterBlockDTO block : chapter.getBlocks()) {
                if ("image".equals(block.getType()) || "audio".equals(block.getType()))
                    block.setUrl(mediaPublicationGuard.requireMedia(owner, block.getUrl(), publish));
                if ("dream".equals(block.getType()) && block.getImages() != null)
                    for (com.chengyinhub.business.domain.dto.ChapterBlockDTO.DreamImageDTO image : block.getImages())
                        image.setUrl(mediaPublicationGuard.requireMedia(owner, image.getUrl(), publish));
            }
            if (chapter.getNodes() != null) for (com.chengyinhub.business.domain.dto.NodeDTO node : chapter.getNodes())
                mediaPublicationGuard.requireNode(owner, node, publish);
        }
    }

    private TopicBundleSubmitResultVO publishDraftInternal(Long id, Long expectedOwnerId, boolean allowSupplyResubmission) {
        return publishDraftInternal(id, expectedOwnerId, allowSupplyResubmission, null, null);
    }

    private TopicBundleSubmitResultVO publishDraftInternal(Long id, Long expectedOwnerId, boolean allowSupplyResubmission,
            Long expectedConfigVersion, String expectedRegistryVersion) {
        CmsTopic topic = supplyReviewService.lockTopic(id);
        if (expectedOwnerId != null && (topic == null || !expectedOwnerId.equals(topic.getMemberId())))
            throw new ServiceException("主题归属已变化，无权提交", 403);
        if (topic != null) {
            long version = topic.getConfigVersion() == null ? 1L : topic.getConfigVersion();
            if (com.chengyinhub.business.service.support.AuthorAttributeBinding.hasBinding(topic.getJourneyRulesJson())) {
                com.chengyinhub.business.domain.JourneyRules rules = com.chengyinhub.business.domain.JourneyRules.parse(topic.getJourneyRulesJson());
                String boundVersion = rules.getAttributeBinding().path("registryVersion").asText();
                if (expectedConfigVersion == null || expectedConfigVersion != version
                        || !boundVersion.equals(expectedRegistryVersion)) {
                    throw com.chengyinhub.business.service.support.AuthorAttributeBinding.conflict(
                            "ATTRIBUTE_REGISTRY_VERSION_CONFLICT", "发布版本与已确认草稿不一致，请重新读取后确认", 409);
                }
            }
            // 建局按 published_config_version 核对冻结定义，定义须标本次发布将产生的发布版本，而非编辑版本。
            long publishedVersion = (topic.getPublishedConfigVersion() == null ? 0L : topic.getPublishedConfigVersion()) + 1L;
            freezeAuthorAttributes(topic, expectedConfigVersion == null ? version : expectedConfigVersion, version, publishedVersion, false);
        }
        return topicPublicationService.publishDraftInternal(id, expectedOwnerId, allowSupplyResubmission);
    }









    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishApprovedRouteSnapshot(Long topicId) {
        topicPublicationService.publishApprovedRouteSnapshot(topicId);
    }



    /**
     * B1 发布期状态键闸:主题下所有启用节点(status=1)挂的玩法模板,其 advancedConfigJson 里
     * requires/effects/check 引用到的每一个状态键,都必须已在该 topic 的 theme_state_key 注册过。
     *
     * <p>R2-B10 扩口:主题级数值规则启用时,{@code journeyRules.recovery.exhaustTag} 同样是必须
     * 已注册的键(见 {@link TopicStateKeyRefService#collect})。
     *
     * <p>R19 扩口:章节故事流 beat 块上的 {@code when}(叙事搬进故事流后的真源)一并核对。
     *
     * <p>R8:引用收集下沉到 {@link TopicStateKeyRefService},与发布前试检查共用同一份代码;
     * 本方法只负责「报错并拦住」,拦截语义与以前一字不差。
     *
     * <p>没有任何引用(老主题)时直接返回,连注册表都不查 —— 行为与以前完全一致。
     * 有坏键时把坏键与所在节点一起报出来,不静默放过。
     */
    private void freezeAuthorAttributes(CmsTopic topic, Long requestVersion, long currentVersion,
                                        long targetVersion, boolean draft) {
        String resolved = com.chengyinhub.business.service.support.AuthorAttributeBinding.freeze(
                themeStateKeyService, topic.getId(), requestVersion, currentVersion, targetVersion,
                topic.getJourneyRulesJson(), draft);
        if (java.util.Objects.equals(resolved, topic.getJourneyRulesJson())) return;
        CmsTopic attributePatch = new CmsTopic(); attributePatch.setId(topic.getId());
        attributePatch.setJourneyRulesJson(resolved);
        if (cmsTopicMapper.updateCmsTopic(attributePatch) != 1) throw new IllegalStateException("属性定义保存失败");
        topic.setJourneyRulesJson(resolved);
    }









    // ==================== R8-STORY 主题级剧情配置 ====================

    /**
     * R8-STORY 保存期引用上下文:可用章节 = 本主题现存章节 id(编辑器回填的 chapterId 就是它们)。
     *
     * <p>NPC 一侧不为 null 会误伤:节点内容重建后节点换新 id,旧节点 NPC 行会暂时对不上
     * (这是既有的节点 NPC 归属口径,不是本任务引入的),保存期强校验会让作者连改文案都存不下。
     * NPC 引用留给发布期 {@link #assertJourneyStoryReferences} 兜底。
     */
    private com.chengyinhub.business.service.support.JourneyStoryValidator.Context journeyStorySaveContext(Long topicId) {
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topicId);
        Set<Long> chapterIds = new LinkedHashSet<Long>();
        for (CmsTopicChapter chapter : nullToEmpty(cmsTopicChapterMapper.selectCmsTopicChapterList(probe))) {
            if (chapter.getId() != null) chapterIds.add(chapter.getId());
        }
        return com.chengyinhub.business.service.support.JourneyStoryValidator.Context.of(chapterIds, null);
    }



    /** 节点叙事段里带 npcId 的段;两段都按「谁在说话」解析 NPC。 */














    /**
     * R8-STORY 发布期剧情引用闸:stages[].chapterId 必须属于本主题现存章节,
     * call / epilogues 的 npcId 必须是本主题可用 NPC。解析不了 fail-closed。
     */
    private void assertThemeRuntimeReferences(CmsTopic topic) {
        publicationContentGuard.assertThemeRuntimeReferences(topic);
    }













    private String remapTemplateRuleIdentities(String raw,Map<Long,Long> nodes) {
        return com.chengyinhub.business.service.support.TopicTemplateRuleIdentityRemapper.remap(raw, nodes);
    }



    private boolean requiresFullRuntimeSnapshot(CmsTopic topic) {
        return publicationContentGuard.requiresFullRuntimeSnapshot(topic);
    }

    /** 服务端发布边界重验完整图，并用确定性状态夹具证明每条普通边真实可选。 */
    private String normalizedBranchRouteForPublish(CmsTopic topic) {
        return publicationContentGuard.normalizedBranchRouteForPublish(topic);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int configureCircleCandidates(Long topicId, Long memberId, List<String> candidateCodes) {
        if (topicId == null || memberId == null) {
            throw new IllegalStateException("缺少圈层城市实例");
        }
        CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null) throw new IllegalStateException("圈层城市实例不存在");
        ProductRole contentSlot = productRoleService.activeContentSlot(topicId);
        if (contentSlot == null || !RoleEntityType.MEMBER.equals(contentSlot.getEntityType())
                || !memberId.equals(contentSlot.getEntityId())) {
            throw new IllegalStateException("只有城市实例的内容主体本人可以配置供给角色");
        }
        if (topic.getCircleThemeCode() == null || topic.getSourceTemplateTopicId() == null) {
            throw new IllegalStateException("该主题不是可配置的圈层城市实例");
        }
        Set<String> selected = new HashSet<>();
        if (candidateCodes != null) {
            for (String raw : candidateCodes) {
                String code = raw == null ? null : raw.trim();
                if (code == null || code.isEmpty() || !selected.add(code)) {
                    throw new IllegalStateException("供给角色候选不能为空或重复");
                }
            }
        }

        CmsTopicChapter query = new CmsTopicChapter();
        query.setTopicId(topicId);
        List<CmsTopicChapter> chapters = cmsTopicChapterMapper.selectCmsTopicChapterList(query);
        Set<String> known = new HashSet<>();
        boolean selectionChanged = false;
        for (CmsTopicChapter chapter : nullToEmpty(chapters)) {
            if (chapter.getCircleCandidateCode() != null) known.add(chapter.getCircleCandidateCode());
            int targetEnabled = selected.contains(chapter.getCircleCandidateCode()) ? 1 : 0;
            if (!Integer.valueOf(targetEnabled).equals(chapter.getCircleEnabled())) {
                selectionChanged = true;
            }
            chapter.setCircleEnabled(targetEnabled);
        }
        if (!known.containsAll(selected)) {
            throw new IllegalStateException("供给角色候选不属于该圈层模板");
        }
        CircleThemePublishPolicy.assertPublishable(topic, chapters);

        int changed = 0;
        Date now = DateUtils.getNowDate();
        for (CmsTopicChapter chapter : chapters) {
            CmsTopicChapter patch = new CmsTopicChapter();
            patch.setId(chapter.getId());
            patch.setCircleEnabled(chapter.getCircleEnabled());
            patch.setUpdateTime(now);
            changed += cmsTopicChapterMapper.updateCmsTopicChapter(patch);
        }
        CmsTopicChapter readbackQuery = new CmsTopicChapter();
        readbackQuery.setTopicId(topicId);
        CircleThemePublishPolicy.assertPublishable(topic,
                cmsTopicChapterMapper.selectCmsTopicChapterList(readbackQuery));
        if (selectionChanged) {
            cmsTopicMapper.clearCircleReviewedAt(topicId, now);
            CmsTopic invalidated = cmsTopicMapper.selectCmsTopicById(topicId);
            if (invalidated == null || invalidated.getCircleReviewedAt() != null) {
                throw new IllegalStateException("城市实例旧复核状态清除失败");
            }
        }
        return changed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Date reviewCircleSupplies(Long topicId, Long memberId) {
        if (topicId == null || memberId == null) throw new IllegalStateException("缺少圈层城市实例");
        CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || StringUtils.isEmpty(topic.getCircleThemeCode())) {
            throw new IllegalStateException("圈层城市实例不存在");
        }
        ProductRole contentSlot = productRoleService.activeContentSlot(topicId);
        if (contentSlot == null || !RoleEntityType.MEMBER.equals(contentSlot.getEntityType())
                || !memberId.equals(contentSlot.getEntityId())) {
            throw new IllegalStateException("只有城市实例的内容主体本人可以复核供给");
        }
        CmsTopicChapter query = new CmsTopicChapter();
        query.setTopicId(topicId);
        CircleThemePublishPolicy.assertSupplied(topic,
                cmsTopicChapterMapper.selectCmsTopicChapterList(query),
                merchantCircleSupplyProfileMapper.selectEffectiveForPublishByTopicId(topicId));
        Date reviewedAt = DateUtils.getNowDate();
        CmsTopic patch = new CmsTopic();
        patch.setId(topicId);
        patch.setCircleReviewedAt(reviewedAt);
        patch.setUpdateTime(reviewedAt);
        if (cmsTopicMapper.updateCmsTopic(patch) != 1) {
            throw new IllegalStateException("城市实例复核时间保存失败");
        }
        CmsTopic readback = cmsTopicMapper.selectCmsTopicById(topicId);
        if (readback == null || readback.getCircleReviewedAt() == null
                || Math.abs(readback.getCircleReviewedAt().getTime() - reviewedAt.getTime()) > 1000L) {
            throw new IllegalStateException("城市实例复核时间读回失败");
        }
        return readback.getCircleReviewedAt();
    }

    /**
     * 创建主题
     *
     * @param dto      TopicCreateDTO
     * @param memberId 会员ID
     * @param username 会员名称
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTopic(TopicCreateDTO dto, Long memberId, String username) {
        ChapterFlowCompiler.assertNoBlockPayload(dto == null ? null : dto.getChapters(),
                "旧版保存接口不接受章节 blocks，请使用 /api/topic/v2/create");
        return createTopicInternal(dto, memberId, username, false).getTopicId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTopicV2(TopicCreateDTO dto, Long memberId, String username) {
        return createTopicV2WithReview(dto, memberId, username).getTopicId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicBundleSubmitResultVO createTopicV2WithReview(
            TopicCreateDTO dto, Long memberId, String username) {
        return createTopicInternal(dto, memberId, username, true);
    }

    /**
     * 商家池开关的唯一落值口 —— **只有俱乐部主理人能开商家池**(2026-08-11 用户拍板)。
     *
     * <p><b>为什么要这道闸</b>:{@code merchant_status} 此前只有 getter/setter,全仓零身份校验 ——
     * 任何登录会员发个主题就能开商家池、招商家来出权益,再把票卖掉。商家的分润确实会入账
     * ({@code coop_settlement} → 个人余额),而平台从没审过这个发布者有没有履约能力。
     *
     * <p><b>为什么商家也不放行</b>:定稿 D18 的「商家 ✅ 自发自招商」说的是**商家自己同时占内容槽与供给槽**
     * (自己发、自己供),而 {@code merchant_status=1} 是**开池让别的商家来报名** —— 两件事。
     * 前者不经过本方法,后者没有任何定稿条款授权。
     *
     * <p><b>为什么放 service 不放 controller</b>:与本文件既有约定一致 ——
     * {@code getAppUserId()} 不可 mock,闸留在 controller 就写不出行为测试。
     *
     * <p><b>为什么是抛异常而不是静默置 0</b>:静默置 0 = 发布者以为开了、商家永远看不到这个主题,
     * 且零告警。宁可当场拒绝并说清原因。小程序侧同理:见
     * {@code pages/publish/fabu/index.js#_syncMerchantPoolEligibility} ——
     * 它只在**确证无资格**时清值,判不出资格时交本方法裁决。
     *
     * @param openMerchantPool DTO 传入值;null 或非 1 一律视为不开放,**不触发身份查询**
     * @param memberId         发布者
     * @return 落库的 merchant_status(1 开放 / 0 自办)
     */
    private Integer resolveMerchantPoolFlag(Integer openMerchantPool, Long memberId) {
        boolean wantOpen = openMerchantPool != null && openMerchantPool == 1;
        if (!wantOpen) {
            return 0;
        }
        ClubLeader leader = memberId == null ? null : clubLeaderService.selectClubLeaderByMemberId(memberId);
        // status 为空按生效处理,与 ApiTopicController 经典定向门禁的既有口径一致
        boolean isClubLeader = leader != null && (leader.getStatus() == null || leader.getStatus() == 1);
        if (!isClubLeader) {
            throw new IllegalStateException("只有俱乐部主理人可以开放商家招商;如需招商请先注册主理人并绑定俱乐部");
        }
        return 1;
    }

    /**
     * 公开承接池(open_club_pool)建题落值口 —— 2026-09-15 裁决 12B:专业版与简易版统一默认开,
     * 主办仍可手动关。
     *
     * <p><b>为什么从「默认关」翻成「默认开」</b>:裁决前专业版硬编码送 0、简易版硬编码送 1,
     * 同一字段两条口径。合作池只让俱乐部**申请**承接(条款随邀约走、发布者有否决权,见
     * {@code ApiCoopPoolController} 类注释),默认开不替主办做决定,只是不让「不会主动去邀」的
     * 发布者被静默看不见。显式传 0 仍会被如实落库 = 主办关掉。
     *
     * <p><b>自由探索恒 0</b>:{@code assertClubLeadOnlyForCityOrienteering} 已拒绝「自由探索 + 显式 1」,
     * 未传时这里也不能替自由探索打开 —— 否则默认值自己就成了那道闸的绕过口。
     *
     * <p><b>归属俱乐部的主题恒 0(2026-09-15 补充裁决,审查D §1 第 4 条)</b>:俱乐部自办团
     * (club_id 非空,含主理人发团时的自动挂靠)不开放公开承接池 —— 池的读侧一直把
     * club_id 非空的主题排除在外({@code ApiCoopPoolController.list}、
     * {@code CoopClubApplyServiceImpl.apply}),写侧不能落一个名不副实的 1。
     *
     * <p><b>存量不回填</b>:本方法只在建题这一处写入;已落库的 0 不因本次改动被改写,
     * 编辑已有主题走的是 DTO 显式值(编辑回填后上送),不会被这里改写。
     *
     * @param requested   DTO 传入值;null = 未传(按开),显式 0 = 主办手动关,其余按开
     * @param productType 主题产品类型(1=经典定向 / 2=自由探索)
     * @param clubId      归属俱乐部;非空 = 俱乐部自办团,恒不开放
     * @return 落库的 open_club_pool(1 开放 / 0 不开放)
     */
    private Integer resolveOpenClubPool(Integer requested, Integer productType, Long clubId) {
        if (productType == null || productType != ProductType.ORIENTEERING) {
            return 0;
        }
        if (clubId != null) {
            return 0;
        }
        return requested != null && requested == 0 ? 0 : 1;
    }

    private TopicBundleSubmitResultVO createTopicInternal(TopicCreateDTO dto, Long memberId, String username,
                                                          boolean chapterFlowV2) {
        if (memberId == null || memberId <= 0L) {
            throw new IllegalArgumentException("主题发布者会员ID不能为空");
        }
        // R19-DRAFT2:名称非空是草稿也保留的最小身份(不属完整度)——「还没想好叫什么」不构成存不下草稿的理由,
        // 但「一条没有名字的主题」在作者的项目列表里连自己都认不出来。
        if (dto == null || StringUtils.isBlank(dto.getName())) {
            throw new IllegalArgumentException("主题名称不能为空");
        }
        assertCollaboratorIdShape(dto.getCollaboratorIds());
        // R19-DRAFT2:完整度校验按草稿/发布分流,先在这里定型,后面所有闸都用同一个判据
        // (客户端自称不算,与 R18-DRAFT 的活动壳/送审两道副作用闸同源)。
        boolean saveDraft = dto.getSaveDraft() != null && dto.getSaveDraft() == 1;
        boolean aiSimple = "ai_simple".equalsIgnoreCase(dto.getPublishMode());
        if (aiSimple) {
            // 简单 AI 的「至少一个已确认地点 + 坐标合法」属完整度:草稿允许地点还没确认,
            // 但已经填进来的坐标仍必须合法(填了就要合法,没填可以空)。
            assertAiSimpleNodeLocations(dto.getChapters(), !saveDraft);
            assertAiSimpleNoGameConfiguration(dto.getChapters());
        }
        Long completeRewardCouponId = dto.getCompleteRewardCouponId() != null && dto.getCompleteRewardCouponId() > 0
                ? dto.getCompleteRewardCouponId() : null;
        couponRewardAuthorizationService.assertOwnedRewardCoupon(completeRewardCouponId, memberId);
        // 宾果九格里每一格也能挂券:同一道归属闸逐张过,漏掉就等于「配别人的券发给自己玩家」
        assertBingoCouponsOwned(dto.getCompleteRuleJson(), memberId);

        // 1. 提取所有节点用于距离计算
        List<NodeDTO> allNodes = extractNodesForStatistics(dto.getChapters(), chapterFlowV2);
        // 2. 计算总距离
        BigDecimal totalMileage = calculateTotalDistance(nodesForDistance(allNodes, dto.getChapters(), chapterFlowV2));

        // 3. 保存主题基本信息
        CmsTopic topic = new CmsTopic();
        BeanUtils.copyProperties(dto, topic);
        mediaPublicationGuard.requireTopic(memberId, topic, !saveDraft);
        requireChapterDraftMedia(dto.getChapters(), memberId, !saveDraft);
        normalizeTeamConfig(topic);
        topic.setMemberId(memberId);
        topic.setTopicType(2L);
        // [P0-02] 产品真源上收:主题定型,票的 mode 由它派生(见 saveTickets)。
        // 原先这里是 `tickets.anyMatch(mode == 1)` —— 从票列表反推主题模式,本身就是「真源在票不在主题」
        // 的活证据(用 anyMatch 而非 get(0),说明后端自己知道票之间 mode 可能不一致,却没拦)。
        Integer productType = resolveProductType(dto);
        // V1 章节招商只服务自由探索。前端虽已隐藏①②入口，但发布端也必须在任何写入前拒绝
        // 直调，不能等到商家申请/入驻时才发现这是一条永远不可用的伪招商配置。
        assertChapterRecruitmentOnlyForFreeExplore(dto.getChapters(), productType);
        assertClubLeadOnlyForCityOrienteering(dto.getOpenClubPool(), productType);
        topic.setProductType(productType);
        topic.setCompleteRuleJson(topicCompletionRulePolicy.validateAndNormalize(
                dto.getCompleteRuleJson(), allNodes.size(), productType));
        // R2-B10 主题数值规则:带才校验落库,不带保持 NULL(= 未启用)。非法配置在这里就抛,
        // 不静默落一份运行期读不懂的 JSON。
        topic.setJourneyRulesJson(journeyRulesValidator.validateAndNormalize(dto.getJourneyRules()));
        String runtimeRaw = mediaPublicationGuard.requireJson(memberId, dto.getThemeRuntimeConfig(), !saveDraft);
        topic.setThemeRuntimeConfigJson(com.chengyinhub.business.service.support.ThemeRuntimeConfig.withoutClientBindings(runtimeRaw));
        com.chengyinhub.business.service.support.ThemeRuntimeConfig.assertJourneyReferences(
                com.chengyinhub.business.service.support.ThemeRuntimeConfig.parseForNodeSave(runtimeRaw),
                com.chengyinhub.business.domain.JourneyRules.parse(topic.getJourneyRulesJson()));
        // R8-STORY 主题剧情:建题时章节/NPC 尚无 id,这里只校验形状与自身自洽,
        // 章节/NPC 引用由发布期带上下文重验(assertJourneyStoryReferences)。
        topic.setJourneyStoryJson(journeyStoryValidator.validate(dto.getJourneyStory(),
                com.chengyinhub.business.service.support.JourneyStoryValidator.Context.none()));
        boolean isCityOrienteering = productType == ProductType.ORIENTEERING;

        // 归属俱乐部(方案B,C1多俱乐部):显式传 clubId 时任何 mode 均可挂靠(校验归属本人)
        // —— 2026-07-02 拍板:自由定向团(mode=2)也可挂俱乐部(opt-in),否则主理人发的自由团在俱乐部视图永远看不到。
        // 经典定向(mode=1)未传则兼容取该会员俱乐部(旧兜底);自由定向未传显式留 NULL(防 copyProperties 透传)。
        if (dto.getClubId() != null) {
            Club club = clubService.selectClubByIdForUpdate(dto.getClubId());
            if (club == null || club.getMemberId() == null || !club.getMemberId().equals(memberId)) {
                throw new RuntimeException("无权将活动发布到该俱乐部");
            }
            topic.setClubId(club.getId());
        } else if (isCityOrienteering) {
            Club club = clubService.selectClubByMemberId(memberId);
            Club lockedClub = club == null ? null : clubService.selectClubByIdForUpdate(club.getId());
            topic.setClubId(lockedClub != null && memberId.equals(lockedClub.getMemberId())
                    ? lockedClub.getId() : null);
        } else {
            topic.setClubId(null);
        }
        // 发布来源:建议版(simple)/专业版(pro,默认),供免审分支与数据分析区分
        topic.setPublishMode(dto.getPublishMode() != null && !dto.getPublishMode().trim().isEmpty()
                ? dto.getPublishMode().trim() : "pro");

        // M2 自玩票:仅「经典定向」(mode=1)可开;自由定向本就一人玩,无自玩票。
        if (isCityOrienteering && dto.getSelfPlay() != null && dto.getSelfPlay() == 1
                && dto.getSelfPlayPrice() != null && dto.getSelfPlayPrice().compareTo(java.math.BigDecimal.ZERO) > 0) {
            topic.setSelfPlay(1);
            topic.setSelfPlayPrice(dto.getSelfPlayPrice());
            topic.setSelfPlayQuota(dto.getSelfPlayQuota());
        } else {
            topic.setSelfPlay(0);
        }
        // M7 主题探索值预算(未传用默认 3000)
        topic.setXpBudget(dto.getXpBudget() != null && dto.getXpBudget() > 0 ? dto.getXpBudget() : 3000);
        // P0.5 通关勋章(空=通关发放时默认"主题名·通关"兜底)
        topic.setFinishMedalName(dto.getFinishMedalName());
        topic.setFinishMedalImg(dto.getFinishMedalImg());
        topic.setCompleteRewardCouponId(completeRewardCouponId);

        Long totalTime = calculateTotalTime(allNodes);
        topic.setTotalTime(totalTime);
        topic.setTotalMileage(totalMileage);
        topic.setLocationCount(Convert.toLong(allNodes.size()));

        Long templateCount = calculateTemplateCount(allNodes);
        topic.setTemplateCount(templateCount);

        // 草稿保持私有；专业版先完成内容落库，随后在同一事务中上架并送后审。
        boolean simpleMode = isSimplePublishMode(topic.getPublishMode());
        if (saveDraft) {
            topic.setStatus(0);      // 草稿:不提审、不上架,仅作者可见(is_draft=1 在插入后隔离置位)
            topic.setUserStatus(0);
        } else if (simpleMode) {
            topic.setStatus(1);
            topic.setUserStatus(1);
            // 简易版免人工审核:文本已在控制器过机审门禁 ⇒ check_status=1(机审通过/免审),
            // 与专业版「已发布、后审中」的 0 区分开。机审存疑的另挂 post_review_flag 抽检单,以未结单为准。
            topic.setCheckStatus(1);
        } else {
            topic.setStatus(0);      // 内容落库期间尚未公开，事务末尾统一发布
            topic.setUserStatus(0);  // 用户端未上架
        }
        // 商家池开关：默认自办不进池(0)；仅当发布时勾选"允许主题进入商家池"才开放商家报名(1)
        topic.setMerchantStatus(resolveMerchantPoolFlag(dto.getOpenMerchantPool(), memberId));
        // 开放范围·俱乐部承接(P0-4):2026-09-15 裁决 12B 起默认开,主办可手动关(未传=开,显式 0=关);
        // 归属俱乐部的主题(含自动挂靠)恒 0 —— 俱乐部自办团不进公开承接池(补充裁决)。
        topic.setOpenClubPool(resolveOpenClubPool(dto.getOpenClubPool(), productType, topic.getClubId()));
        // [P0-50/D21] ③ 自由探索**必须走招募链**(2026-07-16 拍板)。
        //
        // 为什么是硬性的:③ 的四道闸 —— 地板价、锁价费率快照(P0-28)、每必选章 SUPPLY≥1、
        // 计酬「全通关必亏」—— **全都挂在 confirmPrice 上**,而 confirmPrice 的第一行就是
        // `lifecycle != 2 → throw`。不走招募 ⇒ lifecycle 停在库默认的 0 ⇒ 永不经过 confirmPrice
        // ⇒ 那四道闸一道都不生效,票由 saveTickets 直接插、直接开卖。
        //
        // ⚠️ 这不是假想的绕过路径,**它是今天唯一在用的路径**:2026-07-16 查生产库,
        // 17 个主题 lifecycle 全是 0,零个走过 confirmPrice。把闸挂上去而不堵这条,
        // 等于写了四道从第一天起就没人走到的死代码。
        //
        // 零存量代价:现网 17 个主题全是 ①(product_type=1),不受本闸影响;③ 一个都还没建。
        // ①② 行为完全不变 —— 它们的招募仍是可选的。
        // R19-DRAFT2:招募截止属完整度(自由探索的定价与开卖要经招商/锁价链)⇒ 只在发布时要求;
        // 草稿可以先空着 —— 原来这条把「刚起个名字的自由探索草稿」直接堵死,存不下服务端 id。
        if (!saveDraft && ProductType.FREE_EXPLORE == (productType == null ? -1 : productType)
                && dto.getRecruitDeadline() == null) {
            throw new RuntimeException("自由探索主题必须设置招募截止时间:它的定价与开卖要经过招商与锁价"
                    + "(地板价、费率快照、每必选章至少 1 家商家、计酬成本上限四道校验都在锁价环节)");
        }
        // 先只保存招募截止；生命周期保持 DRAFT。章节/票尚未落库前无法客观判断 H4 三条商业事实，
        // 因此绝不能在这里直接写 lifecycle=1 绕过立项审。
        if (dto.getRecruitDeadline() != null) {
            topic.setRecruitDeadline(dto.getRecruitDeadline());
        }
        topic.setDelFlag(0);
        // 首次写入先落兼容的 LINEAR；BRANCH_GRAPH 必须等节点取得 DB id 后再同事务重映射并发布。
        topic.setRouteMode("LINEAR");
        topic.setRouteGraphJson(null);
        topic.setConfigVersion(1L);
        if (!saveDraft && simpleMode) topic.setPublishedThemeRuntimeConfigJson(topic.getThemeRuntimeConfigJson());
        topic.setPublishedConfigVersion(!saveDraft && simpleMode
                && !"BRANCH_GRAPH".equalsIgnoreCase(dto.getRouteMode())
                ? 1L : 0L);
        topic.setCreateBy(username);
        topic.setCreateTime(new Date());

        // R19-DRAFT2:chapters 缺席(null)不再是错误 —— 只填了名字的草稿也要能存。显式 [] 与缺席
        // 在这一步等价(都是「这次没有地址可落」),完整性由发布闸管(见 assertPublishReady)。
        topic.setAddressName(firstLocationAddress(dto.getChapters()));

        if (cmsTopicMapper.insertCmsTopic(topic) != 1 || topic.getId() == null || topic.getId() <= 0L) {
            throw new IllegalStateException("主题创建失败，未生成有效主题身份");
        }
        freezeAuthorAttributes(topic, dto.getConfigVersion(), 1L, 1L, saveDraft);
        Long topicId = topic.getId();

        // 内容槽按已校验的发布身份落：CLUB可承诺自身直付，MEMBER不得替俱乐部或平台承诺。
        productRoleService.declareContentSlot(topicId,
                topic.getClubId() != null ? RoleEntityType.CLUB : RoleEntityType.MEMBER,
                topic.getClubId() != null ? topic.getClubId() : memberId);

        // 草稿:隔离置位 is_draft=1(不进通用 insert,迁移落库前不影响存量建主题)
        if (saveDraft) {
            cmsTopicMapper.updateTopicDraftState(topicId, 1);
        }

        // 旧版专业主题发布后送后台审核；草稿不提审
        if (!chapterFlowV2 && !simpleMode && !saveDraft) {
            try {
                String snap = "{\"bizType\":\"TOPIC\",\"bizId\":" + topicId
                        + ",\"name\":\"" + (topic.getName() == null ? "" : topic.getName().replace("\"", "'")) + "\"}";
                auditTaskService.submit(com.chengyinhub.business.domain.AuditTask.BIZ_TOPIC, topicId, memberId, "user_publish", snap);
            } catch (Exception ignore) {
                logger.error("主题发布审核任务提交失败 topicId={}", topicId, ignore);
            }
        }

        // 4. 保存章节和节点（同时计算章节距离）
        com.chengyinhub.business.service.support.TopicAuthoredGraphIds routeNodeMaps = contentGraphAuthoringService.saveTopicChapters(dto.getChapters(), topicId, username, memberId,
                productType, chapterFlowV2, chapterFlowV2 && (saveDraft || !simpleMode));
        contentGraphAuthoringService.remapRebuiltTemplateNodes(topicId,routeNodeMaps.oldToNew,memberId);
        contentGraphAuthoringService.persistJourneyStoryAfterChapterRebuild(topicId, topic.getJourneyStoryJson(), routeNodeMaps, username);
        contentGraphAuthoringService.saveRouteDefinition(topicId, dto.getRouteMode(), dto.getRouteGraphJson(), routeNodeMaps,
                null, 1L, 1L, productType);
        if (topic.getThemeRuntimeConfigJson() != null) {
            contentGraphAuthoringService.persistCreatedRuntimeBindings(topicId, runtimeRaw, routeNodeMaps);
            if (!saveDraft && simpleMode) {
                assertThemeRuntimeReferences(cmsTopicMapper.selectCmsTopicById(topicId));
            }
        }
        contentGraphAuthoringService.persistTemplateInteractionRules(topicId);
        if (!saveDraft && simpleMode && !"BRANCH_GRAPH".equalsIgnoreCase(dto.getRouteMode())) {
            if (topicPublicationService.persistCreatedRouteSnapshot(topicId) != 1) throw new IllegalStateException("主题模板规则发布失败");
        }
        if (!saveDraft && simpleMode && "BRANCH_GRAPH".equalsIgnoreCase(dto.getRouteMode())) {
            if (topicPublicationService.persistCreatedRouteSnapshot(topicId) != 1) {
                throw new IllegalStateException("路线发布快照保存失败");
            }
        }

        // P0.5 XP 自动分配(奖励契约:创作者零配置):按验证方式加权拆 budget×0.6 到节点,余额=通关 XP
        try { xpBudgetService.autoAllocate(topicId); } catch (Exception ignore) {
            logger.error("XP预算自动分配失败 topicId={}", topicId, ignore);
        }

        // P0.5 途中彩蛋(创作者手配,≤15):status=0 先审,随主题过审整体放行(applyModeration 0→1)

        // 5. 保存合作者和票务信息
        saveCollaborators(dto.getCollaboratorIds(), topicId, username);
        saveTickets(dto.getTickets(), topicId, memberId, username, productType, saveDraft);

        // DRAFT→RECRUITING 唯一受控出口：商业主题转 PENDING_REVIEW，纯 UGC 才直通。
        if (!saveDraft) {
            mediaPublicationGuard.requireTopicPublication(topicId);
            submitPublishedProjectReview(topicId, memberId, dto.getRecruitDeadline());
        }

        // 5.1 自办主题(不进商家池)：没有商家承接，自动以发布者本人为承接方生成一条可游玩活动，
        //     让自办主题也能像最基础的个人办活动一样购票/验券/游玩。
        //     进池主题(openMerchantPool==1)不在此生成，由后台调配中标时生成商家活动。
        // ⚠️ 草稿跳过(R18-DRAFT):生成的活动壳直接 status=1/publish_status=1,会立刻出现在公开活动
        //     列表并且可下单 —— 那「草稿」就成了名不副实的公开售卖。活动壳改在发布时补建(见 publishDraft)。
        // ⚠️ 未过审跳过(R19-PENDINGSHELL):同上,pro 建题即待审(status=0),壳必须是「审核通过」之后
        //     才存在的公开售卖面;simple 免审直发(status=1)照旧在这里建。判据统一收在 ensureSelfHostActivityShell。
        boolean selfHost = dto.getOpenMerchantPool() == null || dto.getOpenMerchantPool() != 1;
        if (selfHost && !saveDraft) {
            // 同步 master(#1078)后总控裁定 C:pro 建题即待审、不在此建壳,欠款逾期闸(原在 generateSelfHostActivity 内)
            // 就碰不到了 —— 提交时显式拦,主理人在提交那一刻看到原因(与合并前「建题即拦」一致;simple 在建壳时会再判一次)。
            settlementDebtLedger.requireNoOverdueDebt(memberId);
            ensureSelfHostActivityShell(topic, topicId, memberId, username);
        }

        // 6. 更新总时长等其他统计信息
        updateTopicStatistics(topicId);

        if (chapterFlowV2 && !simpleMode && !saveDraft) {
            return submitTopicBundle(topicId, memberId);
        }
        if (!saveDraft && !simpleMode) publishBeforeReview(topicId, memberId);
        return noBundleReview(topicId, saveDraft ? "DRAFT" : "NOT_REQUIRED");
    }

    /**
     * [B3b] 创作者编辑自己的主题。
     *
     * <p><b>为什么会有这个方法</b>:{@code publish/fabu} 三个入口(项目列表的草稿/未通过卡、
     * 模板「使用此模板」)都带 {@code ?id=} 跳进编辑器,但页面从不读 {@code options.id},
     * 保存又只调 {@code /api/topic/create} ⇒ 「编辑草稿」实际是在空白表单上<b>再新建一条</b>,
     * 原草稿纹丝不动,越编越多重复主题。补上这条写回路径才让那个入口是真的。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long updateTopicByOwner(com.chengyinhub.business.domain.dto.TopicUpdateDTO dto,
                                   Long memberId, String username) {
        return updateTopicByOwnerInternal(dto, memberId, username, false).getTopicId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long updateTopicByOwnerV2(com.chengyinhub.business.domain.dto.TopicUpdateDTO dto,
                                     Long memberId, String username) {
        return updateTopicByOwnerV2WithReview(dto, memberId, username).getTopicId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicBundleSubmitResultVO updateTopicByOwnerV2WithReview(
            com.chengyinhub.business.domain.dto.TopicUpdateDTO dto, Long memberId, String username) {
        return updateTopicByOwnerInternal(dto, memberId, username, true);
    }

    private TopicBundleSubmitResultVO updateTopicByOwnerInternal(
            com.chengyinhub.business.domain.dto.TopicUpdateDTO dto,
            Long memberId, String username, boolean chapterFlowV2) {
        Long topicId = dto == null ? null : dto.getId();
        if (topicId == null || topicId <= 0L) {
            throw new RuntimeException("参数不合规");
        }
        CmsTopic exist = cmsTopicMapper.selectCmsTopicById(topicId);
        if (exist == null || (exist.getDelFlag() != null && exist.getDelFlag() == 2)) {
            throw new RuntimeException("主题不存在");
        }
        // 归属校验(IDOR):只有主题创建者本人能编辑。与 delete / publish-draft / transfer 同口径。
        if (exist.getMemberId() == null || !exist.getMemberId().equals(memberId)) {
            throw new RuntimeException("无权编辑该主题");
        }
        boolean hasOpenBundleReview = false;
        if (!chapterFlowV2 && (StringUtils.isNotBlank(dto.getJourneyRules()) || StringUtils.isNotBlank(exist.getJourneyRulesJson()))) {
            exist = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
            if (exist == null || Integer.valueOf(2).equals(exist.getDelFlag()) || !memberId.equals(exist.getMemberId())) {
                throw new RuntimeException("主题不存在或无权编辑");
            }
        }
        if (chapterFlowV2) {
            supplyReviewService.lockTopic(topicId);
            hasOpenBundleReview = auditTaskService.lockOpenTopicBundleTask(topicId);
            exist = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
            if (exist == null || Integer.valueOf(2).equals(exist.getDelFlag())
                    || !memberId.equals(exist.getMemberId())) {
                throw new RuntimeException("主题不存在或无权编辑");
            }
        }
        // R19-DRAFT2:名称非空是草稿也保留的最小身份。字段缺席(null)=不改原值(mapper 的动态
        // update 会跳过该列),但显式送一个空串 = 要把名字清掉 —— 那条不许(项目列表里会认不出来)。
        if (dto.getName() != null && dto.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("主题名称不能为空");
        }
        if (!chapterFlowV2) {
            ChapterFlowCompiler.assertNoBlockPayload(dto.getChapters(),
                    "旧版保存接口不接受章节 blocks，请使用 /api/topic/v2/update");
            contentWriteGuard.assertLegacyChapterFlowWritable(topicId);
        }
        // is_draft 不在实体上,只能单查(见 TopicEditWindow 注释)
        Integer isDraft = cmsTopicMapper.selectTopicIsDraft(topicId);
        boolean keptDraft = Integer.valueOf(1).equals(isDraft);
        TopicBundleSubmitResultVO reviewResult = null;
        com.chengyinhub.business.util.TopicEditWindow.Scope scope =
                com.chengyinhub.business.util.TopicEditWindow.resolve(exist, isDraft, new Date());
        if (scope == com.chengyinhub.business.util.TopicEditWindow.Scope.SELLING_FROZEN) {
            throw new IllegalStateException("主题已进入 SELLING，创作者接口不能修改路线/内容/章节/节点/条款/SKU/价格/时间/角色槽");
        }
        if (hasOpenBundleReview && isSimplePublishMode(dto.getPublishMode())) {
            throw new IllegalStateException("主题组合审核中，不能切换为简易版");
        }
        if (scope == com.chengyinhub.business.util.TopicEditWindow.Scope.WHITELIST) {
            applyWhitelistEdit(exist, dto, username);
            if (hasOpenBundleReview) {
                String snapshot = topicBundleReviewService.freezeForSubmission(topicId, memberId).getSnapshotJson();
                auditTaskService.submitOrRefreshTopicBundle(topicId, memberId, snapshot);
            }
        } else {
            // 草稿态是服务端事实(is_draft=1),不是客户端自称 —— R18-DRAFT 的两道副作用闸按它判。
            boolean submitDraftV2 = chapterFlowV2 && Integer.valueOf(1).equals(isDraft)
                    && Integer.valueOf(0).equals(dto.getSaveDraft());
            boolean keepDraft = Integer.valueOf(1).equals(isDraft) && !submitDraftV2;
            keptDraft = keepDraft;
            rebuildTopicContent(exist, dto, memberId, username, chapterFlowV2,
                    keepDraft);
            if (submitDraftV2 && cmsTopicMapper.updateTopicDraftState(topicId, 0) != 1) {
                throw new IllegalStateException("主题草稿提交状态保存失败");
            }
            boolean submitV2 = chapterFlowV2 && !keepDraft
                    && !isSimplePublishMode(dto.getPublishMode());
            if (submitV2) {
                reviewResult = submitTopicBundle(topicId, memberId);
            } else if (!keepDraft && (submitDraftV2 || !isSimplePublishMode(dto.getPublishMode()))) {
                publishBeforeReview(topicId, memberId);
            }
        }
        return reviewResult != null ? reviewResult
                : noBundleReview(topicId, keptDraft ? "DRAFT" : "NOT_REQUIRED");
    }

    private TopicBundleSubmitResultVO submitTopicBundle(Long topicId, Long memberId) {
        return topicPublicationService.submitTopicBundle(topicId, memberId);
    }

    @Override
    public Long requestContentSafetyRelistReview(Long topicId, Long submitterId) {
        return topicPublicationService.requestContentSafetyRelistReview(topicId, submitterId);
    }



    /**
     * 内容先发布、后台后审；审核状态独立保留，不能伪装为已过审。
     *
     * <p>AUDIT-G2-1:内容安全图片回调下架留置中的主题,编辑/重发照常保存内容并送审,但不回到在架、
     * 不清留置 —— 只确保有人工复核单,过审由审核台恢复在架。
     *
     * @return 是否真的上架了(留置中为 false)
     */
    private boolean publishBeforeReview(Long topicId, Long memberId) {
        return topicPublicationService.publishBeforeReview(topicId, memberId);
    }

    private void submitPublishedProjectReview(Long topicId, Long memberId, Date recruitDeadline) {
        topicPublicationService.submitPublishedProjectReview(topicId, memberId, recruitDeadline);
    }



    private TopicBundleSubmitResultVO noBundleReview(Long topicId, String state) {
        return topicPublicationService.noBundleReview(topicId, state);
    }

    /**
     * 已过审开卖(status=1)的主题:只收白名单文案与图。
     *
     * <p><b>为什么是「拒收」而不是「静默忽略」</b>:前端仍可能整包上送,静默忽略会长成一条
     * 「我改了、也提示成功了、但没生效」的假成功 —— 而它盖住的恰好是日期/结构/票价这三样资损面。
     * 拒收让前端必须显式地只送白名单,错了当场红。
     */
    private void applyWhitelistEdit(CmsTopic exist, com.chengyinhub.business.domain.dto.TopicUpdateDTO dto,
                                    String username) {
        List<String> locked = new ArrayList<>();
        if(dto.getThemeRuntimeConfig()!=null && !java.util.Objects.equals(com.chengyinhub.business.service.support.ThemeRuntimeConfig.parseDraft(dto.getThemeRuntimeConfig()),com.chengyinhub.business.service.support.ThemeRuntimeConfig.parseDraft(exist.getThemeRuntimeConfigJson())))locked.add("背包/场景/广播/终局规则");
        if (dto.getRecruitDeadline() != null
                && !java.util.Objects.equals(dto.getRecruitDeadline(), exist.getRecruitDeadline())) {
            locked.add("招募截止时间");
        }
        if (dto.getStartDate() != null || dto.getEndDate() != null || dto.getSaleEndAt() != null) {
            locked.add("开始/结束日期/售止时间");
        }
        if (!CollectionUtils.isEmpty(dto.getChapters())) {
            locked.add("章节站点结构");
        }
        if (!CollectionUtils.isEmpty(dto.getTickets())) {
            locked.add("票种与价格");
        }
        if (!locked.isEmpty()) {
            throw new IllegalStateException("主题已过审开卖，不能修改：" + String.join("、", locked)
                    + "。此时可改的只有名称/副标题/简介/封面/轮播图/类别；要改上面这些只能先下架 → 退款 → 重发");
        }
        CmsTopic patch = new CmsTopic();
        patch.setId(exist.getId());
        patch.setName(dto.getName());
        patch.setSubtitle(dto.getSubtitle());
        patch.setDescription(dto.getDescription());
        patch.setImgUrl(dto.getImgUrl());
        patch.setImgArr(dto.getImgArr());
        patch.setCategoryIds(dto.getCategoryIds());
        patch.setUpdateBy(username);
        patch.setUpdateTime(new Date());
        mediaPublicationGuard.requireTopic(exist.getMemberId(), patch, true);
        // ⚠️ 白名单里没有「营销类型」—— 它是 CoopInvite.shareMode(每条邀约一份),cms_topic 上没有这一列,
        //    改它走邀约侧接口。别为了对齐文档措辞在这儿造一个不存在的字段。
        cmsTopicMapper.updateCmsTopic(patch);
    }

    /**
     * 草稿/待审/驳回:全字段重建(主题本体 + 章节 + 节点 + 票 + 合作者 + 彩蛋)。
     *
     * <p><b>为什么敢整体删了重插</b>:这一档的主题从未 {@code status=1} 过 ⇒ 商家报名闸
     * ({@code status=1 && merchant_status=1})从来没开过 ⇒ 不存在挂在节点上的商家报名。
     * 唯一的例外是「曾经过审、后被后台驳回」的主题,那条路可能带着已售票 —— 由
     * {@link #assertTopicDeletable} 挡住(与删除主题同一道钱闸)。
     */
    private void rebuildTopicContent(CmsTopic exist, com.chengyinhub.business.domain.dto.TopicUpdateDTO dto,
                                     Long memberId, String username, boolean chapterFlowV2, boolean draft) {
        Long topicId = exist.getId();
        // 2026-10-20 分级审核:写前记下开售条款指纹,重建后只有价格/条款变了才重置开售审核。
        java.util.Map<String, Object> saleTermsBefore = saleTerms(exist);
        preserveOmittedAuthorConfig(exist, dto);
        // 9-15 拍板:写前记下「已发布免费主题」,整包重建后复核;变商业即抛出,整个事务回滚。
        boolean conversionProtected = commercialTopicPolicy.isConversionProtected(exist);
        // 招募截止会把主题送进 RECRUITING(商业轨道),之后不再受保护 ⇒ 同样算转商业。
        if (conversionProtected && dto.getRecruitDeadline() != null) {
            throw new IllegalStateException(com.chengyinhub.business.service.support.CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED);
        }
        // 结构重建会物理删掉章节/节点/票 —— 只要钱在场(已付/待付)就一律不许动
        assertTopicDeletable(topicId);
        // 整体替换的入场闸:本次没带的子集合若库里现有行,拒收并点名缺什么(见方法注释)
        assertRebuildPayloadComplete(topicId, dto);
        assertCollaboratorIdShape(dto.getCollaboratorIds());
        // NodeDTO 不开放前置解锁编辑；重建前保存服务端既有关系，等全部新节点生成后翻译 ID。
        Map<Long, Long> existingUnlocks = contentGraphAuthoringService.existingNodeUnlocks(topicId);
        Long existingOpeningId = JourneyStory.openingChapterId(exist.getJourneyStoryJson());
        if (existingOpeningId != null && dto.getChapters() != null) {
            for (ChapterDTO chapter : dto.getChapters()) {
                if (chapter != null && chapter.getOpening() == null)
                    chapter.setOpening(existingOpeningId.equals(chapter.getId()));
            }
            if (!chapterFlowV2) ChapterFlowCompiler.assertNoBlockPayload(dto.getChapters(), "开局流程请使用 v2 编辑器保存");
        }

        boolean aiSimple = "ai_simple".equalsIgnoreCase(dto.getPublishMode());
        if (aiSimple) {
            // 草稿(draft=true)与发布同口径分流,见 createTopicInternal 的同一行:R19-DRAFT2。
            assertAiSimpleNodeLocations(dto.getChapters(), !draft);
            assertAiSimpleNoGameConfiguration(dto.getChapters());
        }
        Long completeRewardCouponId = dto.getCompleteRewardCouponId() != null && dto.getCompleteRewardCouponId() > 0
                ? dto.getCompleteRewardCouponId() : null;
        couponRewardAuthorizationService.assertOwnedRewardCoupon(completeRewardCouponId, memberId);
        // 宾果九格里每一格也能挂券:同一道归属闸逐张过,漏掉就等于「配别人的券发给自己玩家」
        assertBingoCouponsOwned(dto.getCompleteRuleJson(), memberId);

        List<NodeDTO> allNodes = extractNodesForStatistics(dto.getChapters(), chapterFlowV2);

        // ⚠️【已知重复,给下一个人】下面这段「DTO → 主题字段」的映射与 createTopic 里那段是同形的两份。
        //    没有抽成一个方法,是因为 createTopic 是生产建题主链路且**没有行为单测兜底**
        //    (见 ProductSourceWiringTest 的注释:「这两个 service 的依赖多到无法单测组装」),
        //    现在动它属于无网重构。
        //    ⇒ **给 TopicCreateDTO 加字段时,这里和 createTopic 两处都要补**,否则「新建能设、编辑设不了」。
        //    productType 这一列由 ProductSourceWiringTest 两处计数盯着,其余字段没有门禁。
        CmsTopic patch = new CmsTopic();
        BeanUtils.copyProperties(dto, patch);
        mediaPublicationGuard.requireTopic(memberId, patch, !draft);
        requireChapterDraftMedia(dto.getChapters(), memberId, !draft);
        // 路线图引用节点；必须等本次节点重建取得 nodeIdMap 后由专用 CAS 写口落库。
        patch.setRouteMode(null);
        patch.setRouteGraphJson(null);
        patch.setConfigVersion(null);
        normalizeTeamConfig(patch);
        patch.setId(topicId);
        patch.setMemberId(memberId);

        Integer productType = resolveProductType(dto);
        assertChapterRecruitmentOnlyForFreeExplore(dto.getChapters(), productType);
        assertClubLeadOnlyForCityOrienteering(dto.getOpenClubPool(), productType);
        patch.setProductType(productType);
        patch.setCompleteRuleJson(topicCompletionRulePolicy.validateAndNormalize(
                dto.getCompleteRuleJson(), allNodes.size(), productType));
        // R2-B10 与 createTopicInternal 同形(上面的"已知重复"同样适用):空白/未传 → null,
        // 动态 update 的 <if> 会跳过这一列 ⇒ 老编辑端整包上送不会抹掉已配的规则。
        patch.setJourneyRulesJson(journeyRulesValidator.validateAndNormalize(dto.getJourneyRules()));
        String runtimeRaw = mediaPublicationGuard.requireJson(memberId, dto.getThemeRuntimeConfig() == null ? exist.getThemeRuntimeConfigJson() : dto.getThemeRuntimeConfig(), !draft);
        patch.setThemeRuntimeConfigJson(com.chengyinhub.business.service.support.ThemeRuntimeConfig.withoutClientBindings(dto.getThemeRuntimeConfig()));
        com.chengyinhub.business.service.support.ThemeRuntimeConfig.assertJourneyReferences(
                com.chengyinhub.business.service.support.ThemeRuntimeConfig.parseForNodeSave(runtimeRaw),
                com.chengyinhub.business.domain.JourneyRules.parse(patch.getJourneyRulesJson() == null ? exist.getJourneyRulesJson() : patch.getJourneyRulesJson()));
        // R8-STORY 主题剧情:空白/未传 → null(同 journeyRules 的「不改原值」语义)。校验用
        // 本次提交前的章节/NPC 引用;章节重建后 chapterId 全部换新,patch 落库后再按
        // old→new 重映射回填(见 persistJourneyStoryAfterChapterRebuild)。
        patch.setJourneyStoryJson(StringUtils.isBlank(dto.getJourneyStory()) ? null
                : journeyStoryValidator.validate(dto.getJourneyStory(), journeyStorySaveContext(topicId)));
        boolean isCityOrienteering = productType == ProductType.ORIENTEERING;

        // 归属俱乐部:与 createTopic 同口径(显式传则校验归属本人;经典定向未传兜底取本人俱乐部)
        if (dto.getClubId() != null) {
            Club club = clubService.selectClubByIdForUpdate(dto.getClubId());
            if (club == null || club.getMemberId() == null || !club.getMemberId().equals(memberId)) {
                throw new RuntimeException("无权将活动发布到该俱乐部");
            }
            patch.setClubId(club.getId());
        } else if (isCityOrienteering) {
            Club club = clubService.selectClubByMemberId(memberId);
            Club lockedClub = club == null ? null : clubService.selectClubByIdForUpdate(club.getId());
            patch.setClubId(lockedClub != null && memberId.equals(lockedClub.getMemberId())
                    ? lockedClub.getId() : null);
        }

        patch.setPublishMode(dto.getPublishMode() != null && !dto.getPublishMode().trim().isEmpty()
                ? dto.getPublishMode().trim() : "pro");

        if (isCityOrienteering && dto.getSelfPlay() != null && dto.getSelfPlay() == 1
                && dto.getSelfPlayPrice() != null && dto.getSelfPlayPrice().compareTo(BigDecimal.ZERO) > 0) {
            patch.setSelfPlay(1);
            patch.setSelfPlayPrice(dto.getSelfPlayPrice());
            patch.setSelfPlayQuota(dto.getSelfPlayQuota());
        } else {
            patch.setSelfPlay(0);
        }
        patch.setXpBudget(dto.getXpBudget() != null && dto.getXpBudget() > 0 ? dto.getXpBudget() : 3000);
        patch.setFinishMedalName(dto.getFinishMedalName());
        patch.setFinishMedalImg(dto.getFinishMedalImg());
        patch.setCompleteRewardCouponId(completeRewardCouponId);
        patch.setTotalTime(calculateTotalTime(allNodes));
        patch.setTotalMileage(calculateTotalDistance(nodesForDistance(allNodes, dto.getChapters(), chapterFlowV2)));
        patch.setLocationCount(Convert.toLong(allNodes.size()));
        patch.setTemplateCount(calculateTemplateCount(allNodes));
        patch.setMerchantStatus(resolveMerchantPoolFlag(dto.getOpenMerchantPool(), memberId));
        // 归属俱乐部的主题(含自动挂靠)恒 0:与建题同口径 —— 俱乐部自办团不进公开承接池
        // (2026-09-15 补充裁决,审查D §1 第 4 条);其余沿用编辑侧原语义(显式 1 才开)。
        patch.setOpenClubPool(patch.getClubId() != null ? 0
                : dto.getOpenClubPool() != null && dto.getOpenClubPool() == 1 ? 1 : 0);

        // R19-DRAFT2:与 createTopicInternal 同一道闸同一分流 —— 招募截止属完整度,草稿可空。
        if (!draft && ProductType.FREE_EXPLORE == (productType == null ? -1 : productType)
                && dto.getRecruitDeadline() == null) {
            throw new RuntimeException("自由探索主题必须设置招募截止时间:它的定价与开卖要经过招商与锁价"
                    + "(地板价、费率快照、每必选章至少 1 家商家、计酬成本上限四道校验都在锁价环节)");
        }
        if (dto.getRecruitDeadline() != null) {
            patch.setRecruitDeadline(dto.getRecruitDeadline());
        }
        patch.setAddressName(firstLocationAddress(dto.getChapters()));
        patch.setUpdateBy(username);
        patch.setUpdateTime(new Date());

        // 驳回后编辑重发：内容重建后重新上架并送后审；草稿仍保持私有。
        boolean wasRejected = exist.getStatus() != null && exist.getStatus() == 2;
        if (wasRejected && !chapterFlowV2) {
            patch.setStatus(0);
            patch.setReason("");
        }
        if (cmsTopicMapper.updateCmsTopic(patch) != 1) {
            throw new IllegalStateException("主题内容保存失败，未重建章节与节点");
        }
        if (patch.getJourneyRulesJson() == null) patch.setJourneyRulesJson(exist.getJourneyRulesJson());
        if (com.chengyinhub.business.service.support.AuthorAttributeBinding.hasBinding(exist.getJourneyRulesJson())
                && !com.chengyinhub.business.service.support.AuthorAttributeBinding.hasBinding(patch.getJourneyRulesJson())) {
            throw com.chengyinhub.business.service.support.AuthorAttributeBinding.conflict(
                    "ATTRIBUTE_DEFINITION_CONFLICT", "旧客户端不能覆盖注册属性引用", 400);
        }
        long attributeVersion = exist.getConfigVersion() == null ? 1L : exist.getConfigVersion();
        freezeAuthorAttributes(patch, dto.getConfigVersion(), attributeVersion, attributeVersion + 1L, draft);

        boolean allowBundledDraft = chapterFlowV2
                && (draft || !isSimplePublishMode(patch.getPublishMode()));
        // 重建会删掉全部节点再插回(插回一律 status=1);先记下后台隐藏的,插回后按旧 id 还原,作者重存不得取消隐藏
        Set<Long> hiddenNodeIds = contentGraphAuthoringService.selectHiddenNodeIds(topicId);
        com.chengyinhub.business.service.support.TopicAuthoredGraphIds routeNodeMaps;
        if (contentGraphAuthoringService.shouldCompileChapterFlow(chapterFlowV2, productType, dto.getChapters())) {
            deleteTopicEditableNonFlowChildren(topicId);
            routeNodeMaps = contentGraphAuthoringService.compileChapterFlow(dto.getChapters(), topicId, username, memberId, true,
                    allowBundledDraft);
        } else {
            deleteTopicEditableChildren(topicId);
            routeNodeMaps = contentGraphAuthoringService.saveTopicChapters(dto.getChapters(), topicId, username, memberId,
                    productType, chapterFlowV2, allowBundledDraft);
        }
        contentGraphAuthoringService.restoreHiddenNodes(hiddenNodeIds, routeNodeMaps.oldToNew);
        contentGraphAuthoringService.remapRebuiltTemplateNodes(topicId,routeNodeMaps.oldToNew,memberId);
        contentGraphAuthoringService.restoreNodeUnlocks(existingUnlocks, routeNodeMaps);
        contentGraphAuthoringService.persistJourneyStoryAfterChapterRebuild(topicId,
                patch.getJourneyStoryJson() == null ? exist.getJourneyStoryJson() : patch.getJourneyStoryJson(),
                routeNodeMaps, username);
        if (runtimeRaw != null) {
            contentGraphAuthoringService.persistRebuiltRuntimeBindings(topicId, runtimeRaw, routeNodeMaps);
        }
        contentGraphAuthoringService.persistTemplateInteractionRules(topicId);
        long expectedRouteVersion = exist.getConfigVersion() == null ? 1L : exist.getConfigVersion();
        String requestedRouteMode = dto.getRouteMode();
        String requestedRouteGraph = dto.getRouteGraphJson();
        boolean legacyRoutePayload = StringUtils.isBlank(requestedRouteMode)
                && StringUtils.isBlank(requestedRouteGraph) && dto.getConfigVersion() == null;
        if (legacyRoutePayload && "BRANCH_GRAPH".equalsIgnoreCase(exist.getRouteMode())) {
            // 旧客户端没有路线字段，但本方法会物理重建节点。若直接 return，既有图仍引用已删除的
            // nodeId，保存表面成功、下一次开局才失败。这里把原图作为本次 CAS 输入：映射不全即
            // 抛错回滚整个事务，映射完整才以新 nodeId 和新 configVersion 原子替换。
            requestedRouteMode = exist.getRouteMode();
            requestedRouteGraph = exist.getRouteGraphJson();
        }
        contentGraphAuthoringService.saveRouteDefinition(topicId, requestedRouteMode, requestedRouteGraph, routeNodeMaps,
                dto.getConfigVersion(), expectedRouteVersion, expectedRouteVersion + 1L, productType);
        try {
            xpBudgetService.autoAllocate(topicId);
        } catch (Exception ignore) {
            logger.error("XP预算重分配失败 topicId={}", topicId, ignore);
        }
        saveCollaborators(dto.getCollaboratorIds(), topicId, username);
        saveTickets(dto.getTickets(), topicId, memberId, username, productType, draft);
        commercialTopicPolicy.assertNotConvertedToCommercial(topicId, conversionProtected);
        if (!draft) mediaPublicationGuard.requireTopicPublication(topicId);

        // 版本必须先递增，再创建新审核快照；否则本次编辑刚提交的任务会立刻被判为旧批准。
        // 2026-10-20 分级审核:开售审核与内容审核解耦 —— 只有价格/条款(TopicSaleTerms)变化才递增版本
        // (= 重置开售审核);只改故事/名称/简介时版本不动,待审的开售审核单原地刷新快照,不丢进度。
        if (com.chengyinhub.business.service.support.TopicSaleTerms.changed(saleTermsBefore,
                saleTerms(cmsTopicMapper.selectCmsTopicById(topicId)))) {
            invalidateApprovedCanonical(topicId, "topic-content-rebuild");
        } else {
            refreshPendingSaleReview(topicId, memberId);
        }

        // R18-DRAFT:草稿保存不得触发任何审核 —— 起草稿只是存内容。这里原来按「带 recruitDeadline
        // 即送立项审」走,对草稿等于一次保存就把主题推进 RECRUITING(商业轨道);草稿要等作者点发布
        // 才走上架/审核路径(与 createTopicInternal 里 !saveDraft 的那道门同口径)。
        if (!draft && Integer.valueOf(TopicLifecycleState.DRAFT.getCode()).equals(exist.getLifecycle())) {
            submitPublishedProjectReview(topicId, memberId, dto.getRecruitDeadline());
        }

        if (wasRejected && !chapterFlowV2) {
            try {
                String snap = "{\"bizType\":\"TOPIC\",\"bizId\":" + topicId
                        + ",\"name\":\"" + (patch.getName() == null ? "" : patch.getName().replace("\"", "'")) + "\"}";
                auditTaskService.submit(com.chengyinhub.business.domain.AuditTask.BIZ_TOPIC, topicId, memberId,
                        "user_republish", snap);
            } catch (Exception ignore) {
                logger.error("驳回主题编辑重发审核任务提交失败 topicId={}", topicId, ignore);
            }
        }

        // R18-DRAFT:草稿保存不建自办活动壳 —— 壳是公开可下单的(status=1/publish_status=1),
        // 第一次保存草稿就建等于草稿立即可售。发布时由 publishDraft 补建。
        if (!draft) {
            refreshSelfHostActivity(topicId, memberId, username);
        }
    }

    /** 开售条款指纹;任何读不到/算不出的情况返回 null,由 {@code TopicSaleTerms.changed} 当作已变化。 */
    private java.util.Map<String, Object> saleTerms(CmsTopic topic) {
        if (topic == null || topic.getId() == null || commercialTopicPolicy == null) return null;
        com.chengyinhub.business.service.support.CommercialTopicPolicy.Evaluation evaluation =
                commercialTopicPolicy.evaluate(topic.getId());
        if (evaluation == null) return null;
        return com.chengyinhub.business.service.support.TopicSaleTerms.of(
                commercialTopicPolicy.saleParameters(topic, evaluation));
    }

    /**
     * 条款没变时,待审(PENDING/ESCALATED)的开售审核单用最新内容刷新快照(同一张单,不重排队),
     * 审核员通过时看到并冻结的就是当前内容。没有待审开售单、或不在 PRICING,什么都不做。
     */
    private void refreshPendingSaleReview(Long topicId, Long memberId) {
        if (topicCommercialReviewService == null || auditTaskService == null) return;
        CmsTopic current = cmsTopicMapper.selectCmsTopicById(topicId);
        if (current == null || !Integer.valueOf(TopicLifecycleState.PRICING.getCode()).equals(current.getLifecycle())) {
            return;
        }
        AuditTask query = new AuditTask();
        query.setBizType(AuditTask.BIZ_TOPIC_SALE_REVIEW);
        query.setBizId(topicId);
        List<AuditTask> tasks = auditTaskService.selectAuditTaskList(query);
        boolean open = false;
        if (tasks != null) for (AuditTask task : tasks) {
            if (AuditTask.STATUS_PENDING.equals(task.getStatus()) || AuditTask.STATUS_ESCALATED.equals(task.getStatus())) {
                open = true;
                break;
            }
        }
        if (open) topicCommercialReviewService.submitSaleReview(topicId, memberId);
    }





    /** 更新沿用已有作者配置；创建时的默认值不能把旧编辑端未认识的字段重置。 */
    private void preserveOmittedAuthorConfig(CmsTopic exist, TopicCreateDTO dto) {
        if (dto.getPublishMode() == null) dto.setPublishMode(exist.getPublishMode());
        if (dto.getTeamMode() == null) dto.setTeamMode(exist.getTeamMode());
        if (dto.getTeamMaxMembers() == null) dto.setTeamMaxMembers(exist.getTeamMaxMembers());
        if (dto.getXpBudget() == null) dto.setXpBudget(exist.getXpBudget());
        if (dto.getSelfPlay() == null) {
            dto.setSelfPlay(exist.getSelfPlay());
            if (dto.getSelfPlayPrice() == null) dto.setSelfPlayPrice(exist.getSelfPlayPrice());
            if (dto.getSelfPlayQuota() == null) dto.setSelfPlayQuota(exist.getSelfPlayQuota());
        }
        // 旧客户端仍可用票的 mode 选产品类型；只有请求完全没有模式时才沿用存量真源。
        boolean hasTicketMode = false;
        if (dto.getTickets() != null) {
            for (ActivityTicketRequest ticket : dto.getTickets()) {
                if (ticket != null && ticket.getMode() != null) {
                    hasTicketMode = true;
                    break;
                }
            }
        }
        if (dto.getProductType() == null && !hasTicketMode && ProductType.isValid(exist.getProductType())) {
            dto.setProductType(exist.getProductType());
        }
    }

    private void invalidateApprovedCanonical(Long topicId, String mutation) {
        if (canonicalMutationService != null && topicId != null) {
            canonicalMutationService.invalidateApprovedCanonical(topicId, mutation);
        }
    }

    /**
     * 整体替换的入场闸:本次请求没带的子集合,库里现有行就拒收,并点名缺的是哪几个字段。
     *
     * <p><b>为什么拒收而不是「尽力保留」</b>:{@link #rebuildTopicContent} 的契约是<b>全量替换</b>
     * ——「先删可编辑子表,再照 DTO 重建」。DTO 少一个字段就等于提交了一份空列表,于是一次
     * {@code {"id":x,"openMerchantPool":1}} 的局部保存会把章节/节点/票/合作者/彩蛋整批清空,
     * 接口照旧返回「保存成功」。2026-08-18 真跑实测:990016 的 4 章 4 节点被清成 0/0,
     * 同期未被动过的 990015 仍是 4+4 作对照,全程零报错零告警。
     * 猜着保留一半是更糟的第三种语义:调用方永远不知道这次到底替换了什么。这与
     * {@link #applyWhitelistEdit} 「拒收不静默忽略」同一口径。
     *
     * <p>⚠️ 判据是 <b>null(字段缺席)</b> 而不是空列表:显式传 {@code []} 就是「我要清空」,照办 ——
     * 否则「删掉最后一章」这个合法操作也一起被堵死。Jackson 对缺席字段给 null、对 {@code []}
     * 给空 List,这个区分是这道闸唯一的依据。
     */
    private void assertRebuildPayloadComplete(Long topicId, TopicCreateDTO dto) {
        List<String> missing = new ArrayList<>();
        if (dto.getChapters() == null) {
            CmsTopicChapter chapterQ = new CmsTopicChapter();
            chapterQ.setTopicId(topicId);
            if (!nullToEmpty(cmsTopicChapterMapper.selectCmsTopicChapterList(chapterQ)).isEmpty()) {
                missing.add("章节与节点(chapters)");
            }
        }
        if (dto.getTickets() == null) {
            OmsTicket ticketQ = new OmsTicket();
            ticketQ.setOwnerType(1L);
            ticketQ.setOwnerId(topicId);
            ticketQ.setDelFlag(0);
            if (!nullToEmpty(omsTicketMapper.selectOmsTicketList(ticketQ)).isEmpty()) {
                missing.add("票种(tickets)");
            }
        }
        if (dto.getCollaboratorIds() == null) {
            CmsCollaborators collabQ = new CmsCollaborators();
            collabQ.setDataType(1L);
            collabQ.setDataId(topicId);
            if (!nullToEmpty(cmsCollaboratorsMapper.selectCmsCollaboratorsList(collabQ)).isEmpty()) {
                missing.add("合作者(collaboratorIds)");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("保存主题是整体替换:本次请求没有携带 " + String.join("、", missing)
                    + ",直接提交会把它们清空。请回传完整内容;确实要清空请显式传空数组 []");
        }
    }





    /**
     * 整体重建前清掉可编辑子表。
     *
     * <p>⚠️ 只清「由发布器写、且这一档下不可能被别人引用」的四张:章节 / 节点 / 合作者 / 彩蛋。
     * 主题票<b>不在这里删</b> —— 它归 {@link #saveTickets} 独占，因为那一步要按业务键把没变的票原地留住。
     * 商家报名、订单、结算一概不碰 —— 它们在这一档下不该存在,真存在了上面那道钱闸已经拦住。
     */
    private void deleteTopicEditableChildren(Long topicId) {
        contentGraphAuthoringService.deleteTopicChapterFlow(topicId);
        deleteTopicEditableNonFlowChildren(topicId);
    }



    /**
     * ⚠️ 主题票<b>不</b>在这里删。它由 {@link #saveTickets} 独占:那一步要按业务键把没变的票原地留住,
     * 因为活动壳票的 {@code source_ticket_id} 就钉在主题票 id 上,先删后插等于在同步之前销毁同步键。
     * 两条调用路径({@link #deleteTopicEditableChildren} 与 V2 分支)之后都必定跑到 {@code saveTickets},
     * 所以「提交里没有的票要删掉」这条语义没有丢。
     */
    private void deleteTopicEditableNonFlowChildren(Long topicId) {
        CmsCollaborators collabQ = new CmsCollaborators();
        collabQ.setDataType(1L);
        collabQ.setDataId(topicId);
        for (CmsCollaborators collaborator : nullToEmpty(cmsCollaboratorsMapper.selectCmsCollaboratorsList(collabQ))) {
            cmsCollaboratorsMapper.deleteCmsCollaboratorsById(collaborator.getId());
        }
    }

    private static <T> List<T> nullToEmpty(List<T> rows) {
        return rows == null ? Collections.<T>emptyList() : rows;
    }

    /**
     * 自办主题的执行活动跟着改。
     *
     * <p><b>不做这步就是资损</b>:自办主题的票是被复制到活动上(ownerType=2)卖的。
     * 只重建主题票、不刷活动票,编辑器里改完价格看着生效了,玩家买到的还是旧价。
     *
     * <p>⚠️ 「本来自办、这次改成进商家池」的情况:既有活动<b>保留不删</b>(删活动会牵动它自己的
     * 报名与核销,不在编辑范围内)。真要停售走主题下架。
     */
    private void refreshSelfHostActivity(Long topicId, Long memberId, String username) {
        selfHostActivityService.refreshSelfHostActivity(topicId, memberId, username);
    }

    /**
     * 自办主题缺活动壳时补建一条(已存在 / 进商家池 / 未过审的一律不动)。
     *
     * <p>抽出来是因为有两个调用时点:①普通保存后的 {@link #refreshSelfHostActivity};
     * ②草稿发布 {@link #publishDraft} —— 草稿期不预建壳(R18-DRAFT,壳是公开可下单的),
     * 发布时必须补上,否则自办主题发布后无票可卖。
     *
     * <p>★R19-PENDINGSHELL:壳 status=1/publish_status=1,正好命中公开活动列表 SQL 的四道 Gate
     * ({@code CmsActivityMapper.selectPublicCmsActivityList}:status=1 and publish_status=1 and
     * del_flag=0 and cancel_time is null)。pro 主题发布后进内容审核(status=0 待审),这时建壳
     * 等于审核通过前玩家已能下单付款;驳回(status=2)同理。⇒ 只有主题确已过审上架(status=1)才允许
     * 建壳。过审时由 {@code AuditTaskServiceImpl.applyModeration} 回调 {@link #ensureSelfHostActivityShell(Long)}
     * 补建;待审/驳回期间没有壳,自然不可见不可下单。
     */
    private void ensureSelfHostActivityShell(CmsTopic fresh, Long topicId, Long memberId, String username) {
        selfHostActivityService.ensureSelfHostActivityShell(fresh, topicId, memberId, username);
    }

    /**
     * 内容审核通过后的补建入口({@code AuditTaskServiceImpl.applyModeration} 过审分支调用)。
     *
     * <p>幂等:已有壳(含商家壳)不重复建,未过审/进商家池主题不动 —— 判据全在
     * {@link #ensureSelfHostActivityShell(CmsTopic, Long, Long, String)} 里,这里只负责按 id 读回最新态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureSelfHostActivityShell(Long topicId) {
        selfHostActivityService.ensureSelfHostActivityShell(topicId);
    }







    // 其他原有私有方法保持不变...
    private List<NodeDTO> extractNodesForStatistics(List<ChapterDTO> chapters, boolean chapterFlowV2) {
        if (chapterFlowV2 && ChapterFlowCompiler.hasBlockPayload(chapters)) {
            return ChapterFlowCompiler.compileChapterFlow(chapters).getNodesInFlowOrder();
        }
        return extractAllNodes(chapters);
    }

    private List<NodeDTO> extractAllNodes(List<ChapterDTO> chapters) {
        return publicationContentGuard.extractAllNodes(chapters);
    }

    /** 仅V2明确标为故事玩法的节点不参与地点距离；缺坐标实体地点仍保留原0边语义。 */
    private List<NodeDTO> nodesForDistance(List<NodeDTO> allNodes, List<ChapterDTO> chapters, boolean chapterFlowV2) {
        if (!chapterFlowV2 || chapters == null) return allNodes;
        Set<NodeDTO> storyGames = Collections.newSetFromMap(new IdentityHashMap<NodeDTO, Boolean>());
        for (ChapterDTO chapter : chapters) {
            if (chapter == null || chapter.getNodes() == null || chapter.getBlocks() == null) continue;
            for (ChapterBlockDTO block : chapter.getBlocks()) {
                if (block == null || !"node".equals(block.getType())
                        || !Boolean.FALSE.equals(block.getLocationRequired())) continue;
                Integer index = block.getNodeIndex();
                if (index != null && index >= 0 && index < chapter.getNodes().size()) {
                    storyGames.add(chapter.getNodes().get(index));
                }
            }
        }
        if (storyGames.isEmpty()) return allNodes;
        List<NodeDTO> locations = new ArrayList<NodeDTO>();
        for (NodeDTO node : allNodes) {
            if (!storyGames.contains(node)) locations.add(node);
        }
        return locations;
    }

    private boolean isSimplePublishMode(String publishMode) {
        return TopicPublicationService.isSimplePublishMode(publishMode);
    }

    /**
     * 简单 AI 主题的地点闸。
     *
     * <p>{@code requireConfirmed=true}(发布):至少一个地点,且每个地点都必须带合法经纬度 ——
     * 不信任前端 confirmed 标志。{@code false}(存草稿):地点可以还没确认(一个都没有、或坐标留空),
     * 但<b>填了</b>的坐标必须合法 —— 「填了就要合法,没填可以空」(R19-DRAFT2)。
     */
    private void assertAiSimpleNodeLocations(List<ChapterDTO> chapters, boolean requireConfirmed) {
        publicationContentGuard.assertAiSimpleNodeLocations(chapters, requireConfirmed);
    }

    /** 简单 AI 流程只允许文字路线；玩法配置必须先复制成新的专业草稿后再编辑。 */
    private void assertAiSimpleNoGameConfiguration(List<ChapterDTO> chapters) {
        List<NodeDTO> nodes = extractAllNodes(chapters);
        for (int i = 0; i < nodes.size(); i++) {
            NodeDTO node = nodes.get(i);
            if (node != null && node.getTemplateId() != null && node.getTemplateId() > 0) {
                throw new IllegalArgumentException("简单 AI 主题不能配置玩法，请先复制为专业草稿后编辑");
            }
        }
    }



    public BigDecimal calculateTotalDistance(List<NodeDTO> nodes) {
        if (CollectionUtils.isEmpty(nodes) || nodes.size() < 2) {
            return BigDecimal.ZERO;
        }

        double totalDistance = 0.0;
        for (int i = 0; i < nodes.size() - 1; i++) {
            NodeDTO current = nodes.get(i);
            NodeDTO next = nodes.get(i + 1);

            double distance = distanceCalculatorUtils.calculateDistance(
                    current.getLongitude(), current.getLatitude(),
                    next.getLongitude(), next.getLatitude()
            );
            totalDistance += distance;
        }

        return BigDecimal.valueOf(totalDistance);
    }

    public Long calculateTotalTime(List<NodeDTO> nodes) {
        if (CollectionUtils.isEmpty(nodes)) {
            return 0L;
        }

        long totalTime = 0L;
        for (NodeDTO node : nodes) {
            Integer nodeTime = node.getNodeTime();
            if (nodeTime != null) totalTime += nodeTime;
        }

        return totalTime;
    }

    public Long calculateTemplateCount(List<NodeDTO> nodes) {
        if (CollectionUtils.isEmpty(nodes)) {
            return 0L;
        }

        long totalCount = 0L;
        for (NodeDTO node : nodes) {
            if (Convert.toLong(node.getTemplateId(), 0L) > 0) {
                totalCount++;
            }
        }

        return totalCount;
    }

    public BigDecimal calculateChapterDistance(List<NodeDTO> nodes) {
        return calculateTotalDistance(nodes);
    }

    public BigDecimal calculateCrossChapterDistance(NodeDTO lastNode, NodeDTO firstNode) {
        if (lastNode == null || firstNode == null) {
            return BigDecimal.ZERO;
        }

        double distance = distanceCalculatorUtils.calculateDistance(
                lastNode.getLongitude(), lastNode.getLatitude(),
                firstNode.getLongitude(), firstNode.getLatitude()
        );

        return BigDecimal.valueOf(distance);
    }

    // 章节写入及其规模限制已移入内容图 owner；门面只保留业务编排。






























    private String firstLocationAddress(List<ChapterDTO> chapters) {
        if (chapters == null) return "";
        for (ChapterDTO chapter : chapters) {
            if (chapter == null || Boolean.TRUE.equals(chapter.getOpening()) || chapter.getNodes() == null) continue;
            if (chapter.getBlocks() != null && !chapter.getBlocks().isEmpty()) {
                for (com.chengyinhub.business.domain.dto.ChapterBlockDTO block : chapter.getBlocks()) {
                    if (block == null || !"node".equals(block.getType())
                            || Boolean.FALSE.equals(block.getLocationRequired())) continue;
                    Integer index = block.getNodeIndex();
                    if (index == null || index < 0 || index >= chapter.getNodes().size()) continue;
                    NodeDTO node = chapter.getNodes().get(index);
                    if (node != null && StringUtils.isNotBlank(node.getAddress())) return node.getAddress();
                }
                continue; // 块化章节的路线顺序来自 blocks，不再回退到 DTO 节点数组顺序。
            }
            for (NodeDTO node : chapter.getNodes()) {
                if (node != null && StringUtils.isNotBlank(node.getAddress())) return node.getAddress();
            }
        }
        return "";
    }



    /**
     * [C3] 主题创建者删除自己的主题:归属校验 + 有付费报名即拒。
     *
     * <p><b>为什么要这道闸</b>:{@code topicDelete} 只软删 del_flag=2,<b>不碰 registration、
     * 不退款、不回补库存</b>。删完主题,报名单还在、钱还在平台,但主题查不到了 ⇒ 钱原地悬空;
     * 而且主题侧<b>压根没有「取消」接口</b>(C3 的另一半,尚未做)⇒ 连补救路径都没有。
     * 理由与活动侧那道闸({@code ApiActivityController:441},C2)一字不差,主题侧一直漏着。
     *
     * <p><b>判据</b>(★2026-07-17 W9 改写,原来那版拦 {1,2} 是假闸,详见 {@link #assertTopicDeletable}):
     * <ul>
     *   <li>2 已支付 —— 拦。钱在平台手里,必须先退</li>
     *   <li>0 待支付 —— 拦。单还活着,钱随时可能进来(过期单照样付得了)。
     *       解堵靠 {@code orderCloseTask} 关单成 3,不靠这里放行</li>
     *   <li>3 支付失败/已关单 —— <b>不拦</b>:单已死,无副作用</li>
     *   <li>4 已退款 —— <b>不拦</b>:钱已结清,报名单本身还在当对账凭证,软删主题不毁它。
     *       (F7 拦 4 是因为那是 <b>物理</b> 删报名单 = 毁证,场景不同)</li>
     * </ul>
     *
     * <p>⚠️ 拒了之后主题创建者怎么办:目前<b>只能等报名自然核销/退完</b>。
     * 「主题取消 → 批量全额退」是 P0-11 的另一半,尚未实现 —— 本方法不假装解决了它。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public boolean deleteTopicByOwner(Long topicId, Long memberId) {
        CmsTopic exist = cmsTopicMapper.selectCmsTopicById(topicId);
        if (exist == null || (exist.getDelFlag() != null && exist.getDelFlag() == 2)) {
            throw new RuntimeException("主题不存在");
        }
        if (exist.getMemberId() == null || !exist.getMemberId().equals(memberId)) {
            throw new RuntimeException("无权操作该主题");
        }
        assertTopicDeletable(topicId);

        CmsActivity activityQuery = new CmsActivity();
        activityQuery.setTopicId(topicId);
        for (CmsActivity activity : nullToEmpty(cmsActivityMapper.selectCmsActivityList(activityQuery))) {
            for (int paymentStatus : new int[]{0, 1, 2}) {
                CmsRegistration registrationQuery = new CmsRegistration();
                registrationQuery.setOwnerType(2L);
                registrationQuery.setOwnerId(activity.getId());
                registrationQuery.setPaymentStatus(paymentStatus);
                if (!nullToEmpty(cmsRegistrationMapper.selectCmsRegistrationList(registrationQuery)).isEmpty()) {
                    throw new RuntimeException("该主题的场次仍有待支付或已支付报名，请先处理场次报名，再删除主题");
                }
            }
        }

        CmsTopic patch = new CmsTopic();
        patch.setId(topicId);
        patch.setDelFlag(2);
        patch.setMemberId(memberId);
        boolean deleted = cmsTopicMapper.updateCmsTopic(patch) > 0;
        if (deleted) {
            // [6-09] 删除 = 不再公开,同步帖一起收起来(否则删掉的主题名/封面仍在广场可见)。
        syncCreativeSquarePostVisibility(topicId, false);
        }
        return deleted;
    }

    /**
     * [C3/W9] 该主题下「钱在平台手里」或「单还活着(待支付)」的报名即拒删。
     *
     * <p><b>★ W9 修正:本闸原先瞄准了一个不存在的状态。</b>原判据是 {@code payment_status ∈ {1,2}},
     * 但<b>全仓没有任何地方写入 1</b>(2026-07-17 grep {@code setPaymentStatus} 实证:只写 0/2/3/4)
     * ⇒ {@code paying} 恒 0 ⇒ 闸实际只拦 2。而下单主通路写的是 <b>0</b>
     * ({@code CmsRegistrationServiceImpl:406})⇒ <b>真正的支付窗口原样敞着</b>:玩家拉起支付 →
     * 创建者此刻删主题 → 放行 → 微信回调到达把单置 2 ⇒ 钱收了、单成了、主题没了。
     *
     * <p><b>为什么无条件拦 0、不按 {@code pay_expire_time} 划线</b>(2026-07-17 拍板):
     * 「窗口已过 ⇒ 弃单 ⇒ 放行」这个推论<b>不成立</b> —— {@code pay_expire_time} 后端<b>没有任何强制力</b>:
     * 支付口从不校验它({@code ApiRegistrationController:385-405} 只拦 {@code payment_status ∈ {2,4}};
     * requestId 幂等分支 :205 的注释自己写明「陈年待支付单也能走到这」)。拿它当闸的判据,
     * 与本卡要拆的病同类:一个是不存在的状态,一个是没有强制力的字段。
     *
     * <p><b>那弃单怎么解堵</b>:靠 {@code orderCloseTask}(sys_job #109,每 5 分钟)把过期未支付单
     * {@code 0 → 3} 并回补库存/退积分({@code CmsRegistrationMapper.closeExpired})。
     * ⇒ 弃单最多堵约 35 分钟(30 分窗口 + 5 分任务间隔),自愈,不是永久堵死。
     *
     * <p>⚠️ <b>本闸的前提是该任务处于启用态</b>。它一旦停(现为 {@code status='1'} 暂停),
     * 弃单永远停在 0 ⇒ 主题<b>永久删不掉</b>,且零告警。
     * ⚠️ {@code pay_expire_time} 为 NULL 的单(存量/后台手工建单)该任务<b>永远扫不到</b>
     * ({@code selectExpiredUnpaidIds} 带 {@code pay_expire_time is not null})⇒ 会永久堵住其主题,
     * 需人工处置该单。
     */
    private void assertTopicDeletable(Long topicId) {
        // 1(支付中)全仓无人写入,一并计入只是防御:将来若真接入支付中间态,它自动被拦。
        // ⚠️ 别把它当保护 —— 真正拦住「钱在路上」的是下面那道 status=0。
        int paid = countTopicRegistrationsByPaymentStatus(topicId, 1)
                + countTopicRegistrationsByPaymentStatus(topicId, 2);
        if (paid > 0) {
            throw new RuntimeException("该主题已有用户付费报名(" + paid
                    + " 笔),不能直接删除。请先为这些报名完成退款,再删除主题");
        }
        int pending = countTopicRegistrationsByPaymentStatus(topicId, 0);
        if (pending > 0) {
            throw new RuntimeException("该主题有 " + pending + " 笔待支付报名,此刻删除会导致"
                    + "「钱收了、主题没了」。未支付的单会在支付窗口过期后由系统自动关单(约 30 分钟),"
                    + "届时即可删除主题");
        }
    }

    private int countTopicRegistrationsByPaymentStatus(Long topicId, Integer paymentStatus) {
        CmsRegistration q = new CmsRegistration();
        q.setOwnerType(1L);      // 1=主题(含自玩通行证)
        q.setOwnerId(topicId);
        q.setPaymentStatus(paymentStatus);
        List<CmsRegistration> rows = cmsRegistrationMapper.selectCmsRegistrationList(q);
        return rows == null ? 0 : rows.size();
    }



    /** 仅保护署名 ID 形状；缺席/空集合与重复正 ID 的既有语义不变。 */
    private void assertCollaboratorIdShape(List<Long> collaboratorIds) {
        if (collaboratorIds == null) {
            return;
        }
        for (Long memberId : collaboratorIds) {
            if (memberId == null || memberId <= 0L) {
                throw new IllegalArgumentException("合作者用户ID必须为正整数");
            }
        }
    }

    private void saveCollaborators(List<Long> collaboratorIds, Long topicId, String username) {
        if (CollectionUtils.isEmpty(collaboratorIds)) {
            return;
        }

        for (Long memberId : collaboratorIds) {
            CmsCollaborators collaborator = new CmsCollaborators();
            collaborator.setDataType(1L); // 类型1:主题
            collaborator.setDataId(topicId);
            collaborator.setMemberId(memberId);
            collaborator.setCreateBy(username);
            collaborator.setCreateTime(new Date());
            cmsCollaboratorsMapper.insertCmsCollaborators(collaborator);
        }
    }

    /**
     * [P0-02] 解析主题产品类型(产品真源)。优先级:
     * ① 前端显式传 productType(新版前端 = entryMode)—— 传了就以它为准,票里的 mode 一概不看;
     * ② 未传(旧版前端)⇒ 从票的 mode 推断:
     *    · 全部票 mode 一致 ⇒ 取该值
     *    · 混票 ⇒ **取 tickets[0].mode 并告警**,不拒。理由见下。
     *    · 无票 / mode 全空(草稿、纯自玩主题)⇒ 经典定向,与 PlayMode 契约的历史兜底同口径。
     *
     * <p><b>为什么混票不能抛异常</b>(初版这么写过,是错的):小程序走微信审核独立发布,而后端合并
     * master 即自动部署 ECS ⇒ 「新后端 + 旧版小程序」是**结构性存在数天的窗口**,不是假设。
     * 旧版 fabu 的 applyEntryMode 只改 tickets[0]、新建票硬编码 mode:1 ⇒ 用户从自由定向入口加第二张票
     * 就是混票、且不上送 productType ⇒ 抛异常会让这几天里**自由定向多票主题一条都发不出去**。
     *
     * <p><b>为什么取 tickets[0] 而不是别的</b>:新旧两版前端的 applyEntryMode 都把 tickets[0].mode
     * 设成用户选的入口模式,所以 tickets[0] 恰好携带用户的真实意图。取它 + saveTickets 全量覆盖 =
     * 混票被归一成用户想要的那个,**比旧代码的 anyMatch(mode==1) 更准**(那个会把自由定向入口的主题
     * 判成经典定向)。等小程序新版覆盖率到位后,这条兜底可以收紧成拒绝。
     */
    private void normalizeTeamConfig(CmsTopic topic) {
        Integer mode = topic.getTeamMode();
        if (mode == null) mode = 0;
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("组队入口参数有误");
        Integer maxMembers = topic.getTeamMaxMembers();
        if (maxMembers == null) maxMembers = 4;
        if (maxMembers < 2 || maxMembers > 4) {
            throw new IllegalArgumentException("队伍人数上限须为 2–4 人");
        }
        topic.setTeamMode(mode);
        topic.setTeamMaxMembers(maxMembers);
    }

    private Integer resolveProductType(TopicCreateDTO dto) {
        Integer resolved = TopicProductTypeResolver.resolve(dto);
        if (!ProductType.isValid(dto.getProductType()) && TopicProductTypeResolver.hasMixedTicketModes(dto)) {
            // 混票兜底:取入口模式(tickets[0])。打点是为了能观测旧版前端的残余量 ——
            // 归零了才可以把这条改成拒绝。
            logger.warn("混票主题:按入口模式(tickets[0])归一为 productType={};"
                    + "来源多半是旧版小程序(applyEntryMode 只改 tickets[0] + 新建票硬编码 mode:1)", resolved);
        }
        return resolved;
    }

    /** V1：章节开放商家承接的主题必须是自由探索；这是发布端反向闸，不依赖小程序展示层。 */
    private void assertChapterRecruitmentOnlyForFreeExplore(List<ChapterDTO> chapters, Integer productType) {
        if (productType != null && productType == ProductType.FREE_EXPLORE) {
            return;
        }
        if (CollectionUtils.isEmpty(chapters)) {
            return;
        }
        for (ChapterDTO chapter : chapters) {
            if (chapter != null && Integer.valueOf(1).equals(chapter.getRecruitEnabled())) {
                throw new IllegalStateException("仅自由探索主题可开启章节商家承接");
            }
        }
    }

    /**
     * spec §2.1：「邀请俱乐部带队」只属于城市定向；自由探索按定义没有俱乐部带队。
     *
     * <p>小程序侧该开关在自由探索下不露面（step3.wxml），但发布端必须自己判：
     * 主题从城市定向改成自由探索时，旧的 open_club_pool=1 会随编辑回填一路带回来，
     * 只靠展示层「看不见」并不会让它变 0。落成 1 之后 {@code CoopClubApplyServiceImpl}
     * 那道闸就会放俱乐部进来申请承接一条本不该有带队方的自由探索主题。
     */
    private void assertClubLeadOnlyForCityOrienteering(Integer openClubPool, Integer productType) {
        if (!Integer.valueOf(1).equals(openClubPool)) {
            return;
        }
        if (productType != null && productType == ProductType.ORIENTEERING) {
            return;
        }
        throw new IllegalStateException("仅城市定向主题可开启邀请俱乐部带队");
    }

    /**
     * 主题票落库(建题与整体重建共用)。
     *
     * <p><b>为什么这里要「就地更新」而不是「删了重插」</b>:活动壳票(ownerType=2)的
     * {@code source_ticket_id} 记的就是主题票 id —— 它是壳票与主题票之间唯一的稳定键。
     * 而 {@link #rebuildTopicContent} 在同一个方法里、同一个事务里,先重建票、末尾才调
     * {@link #refreshSelfHostActivity} → {@code syncTopicTicketsToActivity}。所以只要这一步换了新 id,
     * 同步就在自己被调用之前先失去了键:每张壳票都被判成 orphan,已售的那张直接抛
     * {@code ORPHAN_ALREADY_SOLD} ⇒ 自办主题一旦卖出过一张票,编辑就永久 409、产品内无自救路径。
     *
     * <p><b>新旧票怎么配对</b>:按 {@code (name, price, start_time, end_time)} —— 与 R6 回填
     * ({@code migration_20260817_tandianri_r6_product_contracts.sql}) 给存量壳票认领来源票时用的
     * 是同一把业务键。同键多张按 id 升序做多重集配对,所以重复票不会把主题永久卡成不可编辑。
     * 复用哪一行完全由服务端决定:候选集来自服务端按 {@code ownerType=1 + ownerId=topicId + delFlag=0}
     * 查出来的行,DTO 里根本没有 id 字段,客户端无从指定;更新语句的 where 还会再判一次归属,
     * 命中不了就 fail-closed(不静默插一张新的)。
     *
     * <p>配不上的提交票 ⇒ 新插;没被复用的存量票 ⇒ 删(壳侧由同步按「未售才允许删、已售一律红」处置)。
     *
     * @param productType 主题产品真源 —— 票的 mode 由它派生并**覆盖** DTO 传入值。
     *                    在此之前 mode 靠 BeanUtils.copyProperties 从前端隐式透传且零校验,
     *                    前端 addTicket 又硬编码 mode:1 ⇒ 自由定向入口加一张票即造出混票主题。
     */
    private void saveTickets(List<ActivityTicketRequest> tickets, Long topicId, Long memberId, String username,
                             Integer productType, boolean draft) {
        OmsTicket query = new OmsTicket();
        query.setOwnerType(1L);
        query.setOwnerId(topicId);
        query.setDelFlag(0);
        List<OmsTicket> existing = nullToEmpty(omsTicketMapper.selectOmsTicketList(query));
        Map<Long, OmsTicket> existingById = new HashMap<>();
        Map<String, Deque<OmsTicket>> reusableByKey = new HashMap<>();
        for (OmsTicket old : existing) {
            existingById.put(old.getId(), old);
            String key = topicTicketPairingKey(old.getName(), old.getPrice(), old.getStartTime(), old.getEndTime());
            Deque<OmsTicket> bucket = reusableByKey.get(key);
            if (bucket == null) {
                bucket = new ArrayDeque<>();
                reusableByKey.put(key, bucket);
            }
            bucket.add(old);
        }
        Set<Long> reusedIds = new HashSet<>();
        Set<Long> explicitIds = new HashSet<>();
        for (ActivityTicketRequest requested : nullToEmpty(tickets)) {
            if (requested.getId() != null && existingById.containsKey(requested.getId())
                    && !explicitIds.add(requested.getId())) {
                throw new IllegalArgumentException("票种 id 重复:" + requested.getId());
            }
        }

        for (ActivityTicketRequest ticketDTO : nullToEmpty(tickets)) {
            OmsTicket ticket = new OmsTicket();
            BeanUtils.copyProperties(ticketDTO, ticket);

            // [P0-02/03] 覆盖而非透传:票 mode 已降级为 product_type 的派生列。
            // 放在 copyProperties 之后 —— 否则会被 DTO 里的值盖回去。
            ticket.setMode(ProductType.toTicketMode(productType));
            ticket.setMemberId(memberId);
            ticket.setOwnerType(1L); // 类型1:主题
            ticket.setOwnerId(topicId);
            ticket.setTotalInventory(Convert.toLong(ticketDTO.getTotalStock()));
            ticket.setRemainingInventory(Convert.toLong(ticketDTO.getTotalStock())); // 初始剩余库存等于总库存
            ticket.setDelFlag(0);
            ticket.setStartTime(ticketDTO.getStartTime());
            ticket.setEndTime(ticketDTO.getEndTime());
            ticket.setPrice(ticketDTO.getPrice());
            ticket.setCreateBy(username);
            ticket.setCreateTime(new Date());

            // 成团已下线(2026-07-15):此处原有 min_people/signup_deadline 兜底,随成团引擎一并移除。
            // 两列保留不物理删(回滚/审计),但不再写入、不再被任何代码读取。

            // R19-DRAFT2:票的起止时间属「票种完整度」⇒ 草稿允许半填(时间还没定),发布时必须齐
            // (发布闸见 assertPublishReady);票价/库存是「已填字段的合法性」,草稿同样拒负数 ——
            // 半成品可以空,但已经写进来的坏值不能存下来。
            if (draft) {
                OmsTicketWriteValidator.validatePrice(ticket.getPrice());
                OmsTicketWriteValidator.validateTotalInventory(ticket.getTotalInventory());
            } else {
                OmsTicketWriteValidator.validateNewTicket(ticket);
            }

            Deque<OmsTicket> reusable = reusableByKey.get(topicTicketPairingKey(
                    ticket.getName(), ticket.getPrice(), ticket.getStartTime(), ticket.getEndTime()));
            OmsTicket reuse = ticketDTO.getId() == null ? null : existingById.get(ticketDTO.getId());
            if (ticketDTO.getId() == null && reusable != null) {
                // 旧调用仍按业务键配对，但不能占用本次其他条目显式指定的既有票。
                while (!reusable.isEmpty()) {
                    OmsTicket candidate = reusable.poll();
                    if (!reusedIds.contains(candidate.getId()) && !explicitIds.contains(candidate.getId())) {
                        reuse = candidate;
                        break;
                    }
                }
            }
            if (reuse == null) {
                // 显式旧 id 已删除/不属于本主题时沿原 DTO 合同新建，不借同键的另一行身份。
                ticket.setId(null);
                omsTicketMapper.insertOmsTicket(ticket);
                continue;
            }
            ticket.setId(reuse.getId());
            ticket.setRemainingInventory(
                    OmsTicketWriteValidator.remainingKeepingOccupiedSeats(reuse, ticket.getTotalInventory()));
            ticket.setUpdateBy(username);
            ticket.setUpdateTime(new Date());
            if (omsTicketMapper.updateTopicTicketOnRebuild(ticket) != 1) {
                throw new ServiceException("主题票同步冲突[TOPIC_TICKET_REBUILD_RACE] topicId=" + topicId
                        + " ticketId=" + reuse.getId() + "：该票在本次编辑期间被改动，请刷新后重试",
                        HttpStatus.CONFLICT);
            }
            reusedIds.add(reuse.getId());
        }

        for (OmsTicket old : existing) {
            if (!reusedIds.contains(old.getId())) {
                omsTicketMapper.deleteOmsTicketById(old.getId());
            }
        }
    }

    /** 拼键分隔符:票名里可以有空格,拿空格拼会让 ("A 1.00", null) 与 ("A", 1.00) 撞成同一个键。 */
    private static final String PAIRING_KEY_SEP = "\u0001";

    /** 主题票 ↔ 活动壳票的业务配对键,口径与 R6 存量回填一致(NULL 参与相等,与 SQL 的 {@code <=>} 同义)。 */
    private static String topicTicketPairingKey(String name, BigDecimal price, Date startTime, Date endTime) {
        return name + PAIRING_KEY_SEP + (price == null ? "" : price.stripTrailingZeros().toPlainString())
                + PAIRING_KEY_SEP + (startTime == null ? "" : String.valueOf(startTime.getTime()))
                + PAIRING_KEY_SEP + (endTime == null ? "" : String.valueOf(endTime.getTime()));
    }

    private void updateTopicStatistics(Long topicId) {
        // 计算总时长、总里程等统计信息并更新主题表
        // 这里需要根据实际业务逻辑实现
    }



    /**
     * 九格里配的券必须都是发布者自己的。
     *
     * <p>与 completeRewardCouponId 同一道闸 —— 券是钱,少一处校验就是一条
     * 「引用任意券发给自己玩家」的路径。放在写入口而不是纯函数校验器里,
     * 是因为归属要查库,而校验器一被复用就绕过去了。
     */
    private void assertBingoCouponsOwned(String completeRuleJson, Long memberId) {
        if (com.chengyinhub.common.utils.StringUtils.isBlank(completeRuleJson)) return;
        for (Long couponId : new com.chengyinhub.business.service.support.TopicCompletionRulePolicy()
                .bingoCouponIds(completeRuleJson)) {
            couponRewardAuthorizationService.assertOwnedRewardCoupon(couponId, memberId);
        }
    }
}

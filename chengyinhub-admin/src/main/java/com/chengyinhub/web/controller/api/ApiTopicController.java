package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.exception.TopicPublicationContentException;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.enums.PublicActivityScope;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.domain.dto.TopicProductTypeResolver;
import com.chengyinhub.business.exception.ChapterFlowClientUpgradeRequiredException;
import com.chengyinhub.business.exception.ChapterFlowValidationException;
import com.chengyinhub.business.domain.vo.ActivityInfoVO;
import com.chengyinhub.business.domain.vo.TopicChapterVO;
import com.chengyinhub.business.domain.vo.TopicInfoVO;
import com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO;
import com.chengyinhub.business.domain.vo.TopicNodeVO;
import com.chengyinhub.business.domain.vo.TopicTemplateChapterVO;
import com.chengyinhub.business.domain.vo.TopicTemplateDetailVO;
import com.chengyinhub.business.domain.vo.PublicActivitySummaryVO;
import com.chengyinhub.business.domain.vo.PublicTicketVO;
import com.chengyinhub.business.domain.vo.PublicCollaboratorVO;
import com.chengyinhub.business.domain.vo.PublicMerchantRegistrationVO;
import com.chengyinhub.business.domain.vo.PublicTopicListVO;
import com.chengyinhub.business.domain.vo.PublicMemberSummaryVO;
import com.chengyinhub.business.service.support.TopicRouteModePolicy;
import com.chengyinhub.business.mapper.CmsRegistrationStatisticsMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicLikeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.TmsBrowseRecordMapper;
import com.chengyinhub.business.mapper.UmsCommentMapper;
import com.chengyinhub.business.mapper.UmsMemberFollowMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.TopicTextSafety;
import com.chengyinhub.business.util.ChapterBlockReadSupport;
import com.chengyinhub.business.util.JourneyReviewTexts;
import com.chengyinhub.business.service.support.ClubCoopWindowPolicy;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.RecruitmentWindowPolicy;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.UserSafeMessage;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.bean.BeanUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;
import java.util.*;

import com.chengyinhub.common.utils.OwnershipUtils;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
/**
 * 主题
 *
 * @author webxh
 * @date 2025-09-12
 */
@RestController
@Tag(name = "主题")
@RequestMapping("/api/topic")
public class ApiTopicController extends BaseController {
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper responseObjectMapper;

    @Value("${community.post.legacy-writes-enabled:false}")
    private boolean legacyCommunityWritesEnabled;

    /** 首次存草稿的请求意图键 → 已建草稿号(丢回包后的重试据此找回,不重复建题)。 */
    @Autowired
    private com.chengyinhub.common.core.redis.RedisCache redisCache;

    @Autowired
    private ClubCoopWindowPolicy clubCoopWindowPolicy;

    @Autowired
    private ICmsTopicService cmsTopicService;
    @Autowired
    private com.chengyinhub.business.service.IOrientTaskService orientTaskService;
    @Autowired(required = false)
    private com.chengyinhub.business.mapper.CmsTopicMapper cmsTopicMapper;

    /**
     * 「来源」栏投影（官方 / 俱乐部 / 会员）。
     * ⚠️ 不要换成 ITopicListProjectionService —— 那个带后台超管闸且会带出运营账本。
     */
    @Autowired
    private ITopicContentPublisherProjectionService topicContentPublisherProjectionService;

    /** 章节招商统计,用于把章节级名额收敛成主题级「已满」判据。 */
    @Autowired
    private com.chengyinhub.business.service.IMerchantChapterApplicationService merchantChapterApplicationService;

    /** [P0-16] ③ 权益容量/覆盖率(展示口径)。 */
    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;

    /** 拍板 2026-09-16 #10:营业状态门禁,给下单入口下发「商家暂停营业」提示。 */
    @Autowired
    private IMerchantBusinessStatusService merchantBusinessStatusService;

    /** 主题介绍要说清「商家给的是哪张券」,券实体从这里查(投影见 PublicCouponSummaryVO,不下发 code) */
    @Autowired
    private com.chengyinhub.business.service.ISmsCouponService smsCouponService;

    /**
     * 按 id 取券实体给 PublicMerchantRegistrationVO 用。
     * 单条主键查询,与本方法里既有的「每个报名查一次商家/模板」同量级,不另做批量。
     * 查不到或查挂了都返回 null ⇒ 前端整块不渲染;奖励取不到不该把整个主题详情拖垮。
     */
    private com.chengyinhub.business.domain.SmsCoupon resolveCoupon(Long couponId) {
        if (couponId == null || couponId <= 0) return null;
        try {
            return smsCouponService.selectSmsCouponById(couponId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 存量 productType=NULL 的读侧模式必须回落到最早一张有 mode 的票；无票默认经典定向。 */
    private List<OmsTicket> selectTopicTickets(Long topicId) {
        OmsTicket query = new OmsTicket();
        query.setDelFlag(0);
        query.setOwnerType(1L);
        query.setOwnerId(topicId);
        return omsTicketService.selectOmsTicketList(query);
    }

    /**
     * 本人在本主题的**中标**承接单(auditStatus=1 调配状态中标 + 未删)。
     *
     * <p>判据与 {@code MerchantFulfillmentReadServiceImpl.listUpcomingRuns} 同源 —— 那条是
     * 「商家即将到店的场次」的入口闸,两处必须是同一个「我承接了这个主题」的定义,
     * 否则会出现「场次列表里有这一站、主题详情里看不到这一站」这种自相矛盾。
     *
     * <p>⚠️ 用 auditStatus(调配状态:0待调配/1中标/2落选)而不是 status(后台审核状态)。
     * status=1 只说明报名材料过审,还没被主办方选中;只有中标才谈得上「他要接待玩家」。
     *
     * <p>拿不到 memberId(未登录)一律返回空集 —— 空集会让上层退回 2026-09-05 之前的
     * teaser 行为,这是本次放行的 fail-closed 边界。
     */
    private List<CmsRegistrationMerchant> selectOwnFulfillments(Long topicId, Long memberId) {
        if (topicId == null || memberId == null || memberId <= 0) {
            return Collections.<CmsRegistrationMerchant>emptyList();
        }
        CmsRegistrationMerchant probe = new CmsRegistrationMerchant();
        probe.setTopicId(topicId);
        probe.setMemberId(memberId);
        probe.setAuditStatus(1L);
        probe.setDelFlag(0);
        List<CmsRegistrationMerchant> rows = cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(probe);
        return rows == null ? Collections.<CmsRegistrationMerchant>emptyList() : rows;
    }

    // M7 XP 预算
    @Autowired
    private com.chengyinhub.business.service.IXpBudgetService xpBudgetService;

    @Autowired
    private com.chengyinhub.business.service.IQuotaService quotaService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    /** [C8-05] 主办方取消主题的退款核心(与活动取消同一个)。 */
    @Autowired
    private com.chengyinhub.business.service.ITeamGroupService teamGroupService;

    @Autowired
    private IRoleService roleService;

    @Autowired
    private IClubLeaderService clubLeaderService;

    @Autowired
    private IClubService clubService;

    /** 俱乐部岗位权限。上下架的归属校验从「仅创建者」放宽到「或该主题所属俱乐部里有 ACTIVITY_MANAGE」。 */
    @Autowired
    private com.chengyinhub.business.access.club.IClubAccessService clubAccessService;

    @Autowired
    private ICoopInviteService coopInviteService;

    @Autowired
    private ICmsTopicLikeService cmsTopicLikeService;

    @Autowired
    private IUmsMemberFollowService umsMemberFollowService;

    @Autowired
    private IViewCommentService commentService;

    @Autowired
    private IUmsCommentService umsCommentService;

    @Autowired
    private ICmsMemberTemplateService memberTemplateService;

    @Autowired
    private IViewCollaboratorsService viewCollaboratorsService;

    @Autowired
    private IOmsTicketService omsTicketService;

    @Autowired
    private ICmsRegistrationMerchantService cmsRegistrationMerchantService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    @Autowired
    private IContentSafetyGate contentSafetyGate;

    @Autowired(required = false)
    private com.chengyinhub.business.service.IContentSafetyRecheckService recheckService;

    @Autowired
    private IAuditTaskService auditTaskService;

    @Autowired
    private ISysCategoryService sysCategoryService;

    @Autowired
    private ICmsTopicChapterService cmsTopicChapterService;

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;

    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private ITopicRouteService topicRouteService;

    @Autowired
    private CmsRegistrationStatisticsMapper cmsRegistrationStatisticsMapper;

    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    @Autowired
    private CmsTopicLikeMapper cmsTopicLikeMapper;

    @Autowired
    private UmsMemberFollowMapper umsMemberFollowMapper;

    @Autowired
    private OmsTicketMapper omsTicketMapper;

    @Autowired
    private UmsCommentMapper umsCommentMapper;

    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;

    @Autowired
    private TmsBrowseRecordMapper tmsBrowseRecordMapper;

    @Autowired
    private ITmsBrowseRecordService tmsBrowseRecordService;


    @Autowired
    private IViewRegistrationService viewRegistrationService;

    @Autowired
    private IMmsMerchantService mmsMerchantService;

    @Autowired
    private ICapabilityService capabilityService;

    @Autowired
    private IUmsCreativeSquareService umsCreativeSquareService;

    /** 编辑器回填要带彩蛋(重建会整体重写),没有 service 层,直连 mapper —— 与本类既有 mapper 注入同例。 */


    /** 建题失败时对用户的稳定兜底文案(业务闸文案由 UserSafeMessage 决定是否透出)。 */
    private static final String TOPIC_CREATE_GENERIC_MSG = "主题发布失败，请稍后重试";

    /* 稿 E2(99:6181 / 99:6679)记的是一处**假保证**:「我的权益」里写着「未解锁勋章设计」,
       创作端的通关勋章弹窗却三端都能设。ICapabilityGuard#checkDesignMedal 这道闸本来就有,
       只挂在俱乐部勋章(ApiClubController)和模板发布(TemplatePublishServiceImpl)上,
       **主题保存这条路径没人拦** —— 前端加个只读也没用,改客户端就绕过去了。
       稿的原话:「真要落代码时有两层要一起做,只做前端就是假保证」。这是后端那一层。 */
    @Autowired
    private com.chengyinhub.business.service.ICapabilityGuard capabilityGuard;

    private static boolean requiresPublisherIdentity(TopicCreateDTO dto) {
        if (dto.getTickets() == null || dto.getTickets().isEmpty()) return true;
        for (com.chengyinhub.business.domain.dto.ActivityTicketRequest ticket : dto.getTickets()) {
            if (ticket == null || ticket.getPrice() == null || ticket.getPrice().signum() != 0) return true;
        }
        return Integer.valueOf(1).equals(dto.getSelfPlay())
                && (dto.getSelfPlayPrice() == null || dto.getSelfPlayPrice().signum() != 0);
    }

    /** RUN-52 发布者实名:发布闸只查 ums_member_identity 里有没有这个发布主体的一条登记。 */
    @Autowired
    private com.chengyinhub.business.service.impl.MemberIdentityService memberIdentityService;

    /**
     * 保存主题时的勋章资格闸。只在**这次请求真的要写勋章**时才查 ——
     * ⚠️ 不能无条件拦:存量主题可能是有资格时设的勋章,持有者身份变了之后再编辑别的字段
     *    (比如改个描述)会被这道闸拦死,等于把他锁在自己的主题外面。
     *    与前端的取舍一致:锁态只读不清空,不替用户删图。
     */
    private java.util.List<JourneyReviewTexts.Entry> combinedThemeReviewTexts(TopicCreateDTO dto) {
        java.util.List<JourneyReviewTexts.Entry> entries=JourneyReviewTexts.collectJourneyStory(dto.getJourneyStory());entries.addAll(JourneyReviewTexts.collectThemeRuntime(dto.getThemeRuntimeConfig()));entries.addAll(JourneyReviewTexts.collectChapters(dto.getChapters()));
        // F-L5C-03:简易版免审直发时,完成勋章名、票种文案、念头规则、宾果格子同样直接给玩家看。
        entries.addAll(JourneyReviewTexts.collectJourneyRules(dto.getJourneyRules()));
        entries.addAll(JourneyReviewTexts.collectCompletionRule(dto.getCompleteRuleJson()));
        if (StringUtils.isNotBlank(dto.getFinishMedalName())) entries.add(new JourneyReviewTexts.Entry("finishMedalName", dto.getFinishMedalName().trim()));
        if (dto.getTickets() != null) for (com.chengyinhub.business.domain.dto.ActivityTicketRequest ticket : dto.getTickets()) {
            if (ticket == null) continue;
            String ticketText = IWxContentSecurityService.joinText(ticket.getName(), ticket.getDescription(),
                    ticket.getMeetingPoint(), ticket.getMeetingPointAddress(), ticket.getRefundRule());
            if (StringUtils.isNotBlank(ticketText)) entries.add(new JourneyReviewTexts.Entry("tickets[]", ticketText));
        }
        return entries;
    }
    private String checkThemeMedalCapability(Long memberId,String raw) { return checkThemeMedalCapability(memberId,raw,null); }
    private String checkThemeMedalCapability(Long memberId, String raw, String previousRaw) {
        com.fasterxml.jackson.databind.node.ObjectNode config = com.chengyinhub.business.service.support.ThemeRuntimeConfig.parseForNodeSave(raw);
        if (config == null) return null;
        com.fasterxml.jackson.databind.node.ObjectNode previous=com.chengyinhub.business.service.support.ThemeRuntimeConfig.parseDraft(previousRaw);
        for (com.fasterxml.jackson.databind.JsonNode item : config.path("items")) {
            if ("MEDAL".equals(item.path("kind").asText())) {
                boolean unchanged=false;
                if(previous!=null)for(com.fasterxml.jackson.databind.JsonNode old:previous.path("items"))if(item.equals(old)){unchanged=true;break;}
                if(unchanged)continue;
                String denied = checkMedalCapability(memberId,item.path("name").asText(),item.path("image").asText());
                if (denied != null) return denied;
            }
        }
        return null;
    }

    private String checkMedalCapability(Long memberId, String medalName, String medalImg) {
        boolean wantsMedal = StringUtils.isNotBlank(medalName) || StringUtils.isNotBlank(medalImg);
        if (!wantsMedal) {
            return null;
        }
        return capabilityGuard.checkDesignMedal(memberId);
    }

    @Operation(summary = "新增主题")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    @PostMapping("/create")
    public AjaxResult createTopic(@Validated @RequestBody TopicCreateDTO dto) {
        return createTopic(dto, false);
    }

    @Operation(summary = "V2 新增主题（章节故事流）")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    @PostMapping("/v2/create")
    public AjaxResult createTopicV2(@Validated @RequestBody TopicCreateDTO dto) {
        return createTopic(dto, true);
    }


    /**
     * 统计占节点名额的节点数(X03)。2026-09-23 用户裁决:故事玩法不算节点名额 ——
     * 故事玩法 = 章节块里引用它的 node 块 locationRequired=false 且节点没有坐标(与游玩页同一判据)。
     * 带坐标的仍按地点算,改块上的布尔绕不过名额。null 安全。
     */
    static int countNodes(com.chengyinhub.business.domain.dto.TopicCreateDTO dto) {
        if (dto == null || dto.getChapters() == null) {
            return 0;
        }
        int n = 0;
        for (com.chengyinhub.business.domain.dto.ChapterDTO chapter : dto.getChapters()) {
            if (chapter == null || chapter.getNodes() == null) continue;
            java.util.Set<Integer> storyGameIndexes = new java.util.HashSet<Integer>();
            if (chapter.getBlocks() != null) {
                for (com.chengyinhub.business.domain.dto.ChapterBlockDTO block : chapter.getBlocks()) {
                    if (block != null && "node".equals(block.getType()) && block.getNodeIndex() != null
                            && Boolean.FALSE.equals(block.getLocationRequired())) {
                        storyGameIndexes.add(block.getNodeIndex());
                    }
                }
            }
            for (int i = 0; i < chapter.getNodes().size(); i++) {
                com.chengyinhub.business.domain.dto.NodeDTO node = chapter.getNodes().get(i);
                boolean storyGame = storyGameIndexes.contains(Integer.valueOf(i)) && node != null
                        && isBlank(node.getLatitude()) && isBlank(node.getLongitude());
                if (!storyGame) n++;
            }
        }
        return n;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty() || "0".equals(value.trim());
    }

    private AjaxResult createTopic(TopicCreateDTO dto, boolean chapterFlowV2) {
        Long principalMemberId = null;
        Long memberId = null;
        Long topicId = null;
        String draftRequestKey = null;
        try {
            // principal 是实际操作者；memberId 是主题发布主体。商家员工代办时两者不能混为一人。
            principalMemberId = getAppUserId();
            memberId = principalMemberId;
            // 参数校验
            if (memberId == null || memberId <= 0) {
                return error("请先登录");
            }
            try {
                memberId = resolveProjectOwnerMemberId(principalMemberId, dto.getScope());
            } catch (ServiceException e) {
                return new AjaxResult(e.getCode(), e.getMessage());
            }

            // 经典定向发布门禁必须与服务层共用 productType 真源；仅旧版请求未传时才回退票 mode。
            boolean isCityOrienteering = TopicProductTypeResolver.resolve(dto) == ProductType.ORIENTEERING;
            if (isCityOrienteering) {
                // D1(2026-07-04):经典定向发布门禁从"仅主理人独占"放开为两条通路,都必须绑定一个俱乐部:
                //   ① 该俱乐部的主理人本人(既有路径,身份模型重构后按 club_leader 档判定);
                //   ② 商家:对该俱乐部有已接受(type1 status=1)的合作邀约 → 可借该俱乐部之名发团。
                // D2(2026-07-07,用户决策B):纯玩家(非商家/非主理人)可"无俱乐部直发"经典定向,clubId 允许为空。
                // D3(2026-09-05,用户裁决「商家城市定向只能自己玩,不能邀约就好了」):
                //   ★ 商家也可以无俱乐部直发 —— 他自办、自己带,不牵扯俱乐部。
                //   在此之前商家是被硬拒的,而且这是一条**看不见的死路**:商家端营销中心的
                //   「主题」入口(pages/merchant/marketing/index.js CONTENT_ROUTES.topic)
                //   不带 clubId 进发布页,而发布页的归属俱乐部选择器只在 myClubs(= /api/club/my
                //   的 owned,自己创建的俱乐部)非空时才渲染 —— 商家通常一个都没有。
                //   结果是:界面上没有那个选择器,发布时却回一句「请选择发起经典定向团的合作俱乐部」。
                //   ⚠️ 放开的只是「不绑俱乐部」这一种情况,不是放开邀约:
                //      · 绑了俱乐部仍走下面 ② 那条闸(必须有已接受的 type1 合作邀约);
                //      · 「开放给俱乐部承接」(open_club_pool)仍恒 0、界面上没有入口,
                //        它的方向是「自己发了再等俱乐部来申请」,与本裁决相反。
                //   主理人**仍须绑俱乐部**:他本来就有,选一个是明确归属,不是障碍。
                // 商家身份不再参与这道闸(D3 放开后玩家与商家同一条口径),所以不再查角色。
                ClubLeader leader = clubLeaderService.selectClubLeaderByMemberId(memberId);
                boolean isLeaderPub = leader != null && (leader.getStatus() == null || leader.getStatus() == 1);

                if (dto.getClubId() == null) {
                    // 无俱乐部直发:玩家与商家放行,主理人须绑自己的俱乐部
                    if (isLeaderPub) {
                        return error("请选择发起经典定向团的合作俱乐部");
                    }
                } else {
                    Club club = clubService.selectClubById(dto.getClubId());
                    if (club == null) {
                        return error("俱乐部不存在");
                    }
                    boolean isLeaderOfOwnClub = OwnershipUtils.isOwner(club.getMemberId(), memberId)
                            && leader != null && (leader.getStatus() == null || leader.getStatus() == 1);
                    if (!isLeaderOfOwnClub) {
                        CoopInvite coopQ = new CoopInvite();
                        coopQ.setFromId(memberId);
                        coopQ.setInviteType(1);
                        coopQ.setToType("club");
                        coopQ.setToId(dto.getClubId());
                        coopQ.setStatus(1);
                        boolean hasAcceptedClubCoop = !coopInviteService.selectCoopInviteList(coopQ).isEmpty();
                        if (!hasAcceptedClubCoop) {
                            return error("需为该俱乐部主理人,或先获得该俱乐部的合作邀约通过,才能发起经典定向团");
                        }
                    }
                }
            }

            // 稿 E2:通关勋章设计是俱乐部主理人的权益,player/merchant 的 can_design_medal=0。
            // 这条路径此前没人拦 —— 前端弹窗三端都能设,保存也照收。
            String medalCapErr = checkThemeMedalCapability(memberId, dto.getThemeRuntimeConfig());
            if (medalCapErr == null) medalCapErr = checkMedalCapability(memberId, dto.getFinishMedalName(), dto.getFinishMedalImg());
            if (medalCapErr != null) {
                return error(medalCapErr);
            }
            // RBAC R3：校验创建权限(canCreateTheme);草稿/待审无限,不再按创建总数拦截
            String quotaErr = quotaService.checkCreate(memberId, com.chengyinhub.business.service.IQuotaService.TYPE_THEME);
            if (quotaErr != null) {
                return error(quotaErr);
            }
            // 节点数量不限，保留发布主体与权限配置校验。
            String nodeQuotaErr = quotaService.checkNodesPerTheme(memberId, countNodes(dto));
            if (nodeQuotaErr != null) {
                return error(nodeQuotaErr);
            }
            // 上架额度：简易版在下方 guard 校验；专业版先发布、后台后审，由 service 在同事务内校验。
            // 存草稿(saveDraft)也不上架,不占在架额度。在架校验与建题写库在下方 guard 内同事务原子完成(防并发超发)。
            boolean saveDraft = dto.getSaveDraft() != null && dto.getSaveDraft() == 1;
            boolean guardOnline = isSimplePublishMode(dto.getPublishMode()) && !saveDraft;


            // 全免费发布免实名；收费发布仍登记收款主体（员工代办时为商家 owner）。
            if (!saveDraft && requiresPublisherIdentity(dto)) {
                String identityErr = memberIdentityService.checkRegistered(memberId);
                if (identityErr != null) {
                    return error(identityErr);
                }
            }

            // UGC 内容安全(2026-10-20 分级审核 P1):主题内容先发后审 —— 统一门禁 POST_REVIEW。
            // 明确违规拒绝保存;机审要人工/审不了 → 保存后下架留置并送人工复核(见 flagForSpotCheck)。
            // 内容送检使用实际操作者的微信身份；主题、额度与结算归发布主体。
            // R12/R19:剧情配置、章节故事流 beat 块等叙事文本同一道闸;命中时提示里带字段名。
            String openid = resolveOpenid(principalMemberId);
            String secText = IWxContentSecurityService.joinText(dto.getName(), dto.getSubtitle(), dto.getDescription());
            TopicTextSafety.Verdict textVerdict = TopicTextSafety.check(contentSafetyGate, memberId, openid, null,
                    secText, topicReviewTexts(dto, chapterFlowV2));
            if (textVerdict.isBlocked()) {
                return AjaxResult.error(HttpStatus.BAD_REQUEST, textVerdict.getBlockMessage());
            }
            // P-17(owner 2026-11-02「封面应该是必须的 不能有没有封面的活动」):发布必须带封面。
            // D-78(owner 2026-11-11「没有封面不能保存」):存草稿同样必须带封面。
            // 只看作者提交的封面地址是否为空;封面已上传、仍在内容安全复核中的照常放行(读侧会先隐藏它)。
            if (StringUtils.isBlank(dto.getImgUrl())) {
                return error("请上传主题封面");
            }

            // 首次存草稿带请求意图键:同一账号同一键已建过 → 直接回那份草稿(丢回包后的重试);在途 → 请稍后。
            // 键在全部校验之后、真正建题之前才占,校验失败不留痕。
            if (saveDraft) {
                draftRequestKey = draftRequestKey(memberId, dto.getRequestId());
                if (draftRequestKey != null) {
                    AjaxResult recovered = claimDraftRequest(draftRequestKey, memberId);
                    if (recovered != null) return recovered; // 已建过(找回)或同键在途;都不是本次占的,不释放
                }
            }

            String username = getAppUserName();
            // simple 免审直发会立即占在架额度:锁会员 → 复查在架数 → 建题,同事务原子,防并发绕过 maxThemes。
            // 超限时 guard 抛 ServiceException,由外层 catch 统一转 error 文案。草稿/专业版不占额度,直接建题。
            final Long fMemberId = memberId;
            final String fUsername = username;
            TopicBundleSubmitResultVO v2Result = null;
            try {
                if (chapterFlowV2) {
                    v2Result = guardOnline
                            ? quotaService.runWithThemeOnlineGuard(memberId, null,
                                () -> cmsTopicService.createTopicV2WithReview(dto, fMemberId, fUsername))
                            : cmsTopicService.createTopicV2WithReview(dto, memberId, username);
                    topicId = v2Result.getTopicId();
                } else {
                    topicId = guardOnline
                            ? quotaService.runWithThemeOnlineGuard(memberId, null,
                                () -> cmsTopicService.createTopic(dto, fMemberId, fUsername))
                            : cmsTopicService.createTopic(dto, memberId, username);
                }
            } catch (ServiceException e) {
                // 只包建题这一笔事务:这里抛出 = 整笔回滚、主题没建出来,可以给确定的 4xx。
                // 建题之后的送检/同步仍走外层(那时主题已落库,不能让客户端当成没建而重发)。
                releaseDraftRequest(draftRequestKey);
                AjaxResult attributeError = authorAttributeError(e);
                if (attributeError != null) return attributeError;
                logger.warn("主题发布被业务闸拒绝: memberId={}, reason={}", memberId, e.getMessage());
                return publicationError(e, businessRefusal(e, UserSafeMessage.of(e.getMessage(), TOPIC_CREATE_GENERIC_MSG)));
            }
            // 建题已提交:立刻把意图键落成主题号 —— 之后的送检/同步即便抛错也不能释放它,否则重试会再建一份
            if (draftRequestKey != null) {
                redisCache.setCacheObject(draftRequestKey, topicId, DRAFT_REQUEST_DAYS, java.util.concurrent.TimeUnit.DAYS);
                draftRequestKey = null;
            }

            boolean held = false;
            if (textVerdict.isFlagged()) {
                held = flagForSpotCheck(topicId, memberId, textVerdict.getFlagReason(),
                        recheckText(secText, topicReviewTexts(dto, chapterFlowV2)), dto.getImgUrl(), dto.getImgArr());
            } else if (mediaUncheckable(dto.getImgUrl(), dto.getImgArr(), openid)) {
                held = flagForSpotCheck(topicId, memberId, "MEDIA_UNCHECKED", null, dto.getImgUrl(), dto.getImgArr());
            }
            if (held && !saveDraft && v2Result != null) v2Result.markHeldForReview();

            // UGC 图片内容安全:异步送检封面/图集,命中违规由消息推送回调自动下架(best-effort,不阻断)
            wxContentSecurityService.submitMediaCheck(
                    IWxContentSecurityService.BIZ_TYPE_TOPIC,
                    topicId,
                    StringUtils.isNotEmpty(dto.getImgArr()) ? dto.getImgArr() : dto.getImgUrl(),
                    openid);

            // 同步发布到创意广场(开关开启时):用主题名+简介+封面生成一条 dataType=2 的关联帖。
            // best-effort:失败不阻断主题发布;主题文本已过内容安全,无需复检。复用 ums_creative_square
            // 现有 dataType(2=主题)+dataId 字段,enrichSportFeed 会自动渲染成主题运动 feed,无需建表/加列。
            // ⚠️ 存草稿一律不同步:创意广场是公开 feed,而草稿在任何公开面都不可见(R18-DRAFT)。
            // 客户端即便传了 publishToCreative=1(表单开关状态),也不能让未发布的主题漏出去。
            if (legacyCommunityWritesEnabled
                    && !saveDraft && dto.getPublishToCreative() != null && dto.getPublishToCreative() == 1) {
                try {
                    UmsCreativeSquare post = new UmsCreativeSquare();
                    post.setMemberId(memberId);
                    String contents = dto.getName();
                    if (StringUtils.isNotEmpty(dto.getDescription())) {
                        contents = contents + "\n" + dto.getDescription();
                    }
                    post.setContents(contents);
                    post.setPics(StringUtils.isNotEmpty(dto.getImgArr()) ? dto.getImgArr() : dto.getImgUrl());
                    post.setDataType(2L);          // 2=主题
                    post.setDataId(topicId);       // 关联主题
                    post.setStatus(1);             // 1=立即可见(与手动发帖一致)
                    post.setDelFlag(0);
                    post.setIsCrowdfunding(2);     // 2=不参与众筹
                    umsCreativeSquareService.insertUmsCreativeSquare(post);
                } catch (Exception ex) {
                    // 创意广场同步失败不影响主题发布主流程
                }
            }

            // saveDraft 时返回的真实语义是「草稿」而不是「发布」——前端直接用 msg 弹提示时会说反。
            return AjaxResult.success(saveDraft ? "草稿已保存"
                            : held ? "已提交审核"
                            : v2Result != null && !v2Result.isPublished() && ("PENDING".equals(v2Result.getReviewState())
                                    || "ESCALATED".equals(v2Result.getReviewState()))
                                    ? "已提交审核" : "主题发布成功",
                    v2Result == null ? topicId : v2Result);
        } catch (ChapterFlowValidationException e) {
            releaseDraftRequest(draftRequestKey);
            return AjaxResult.error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ServiceException | IllegalStateException | IllegalArgumentException e) {
            releaseDraftRequest(draftRequestKey);
            AjaxResult attributeError = authorAttributeError(e);
            if (attributeError != null) return attributeError;
            // 业务闸抛的中文短句原样透出(仍过 UserSafeMessage 掩码,业务异常类型也可能裹着 SQL 片段)。
            // 这些失败是用户自己能改的:「只有俱乐部主理人可以开放商家招商」「仅城市定向主题可开启邀请
            // 俱乐部带队」「在架额度已满」压成通用句 = 真人在后台点一次失败一次,只能找人翻服务器日志。
            // ⚠️ 通用兜底不动:技术异常(Jackson 解析失败 / NPE / SQL)仍只给下面那句稳定文案。
            logger.warn("主题发布被业务闸拒绝: memberId={}, topicId={}, reason={}", memberId, topicId, e.getMessage());
            return publicationError(e, AjaxResult.error(UserSafeMessage.of(e.getMessage(), TOPIC_CREATE_GENERIC_MSG)));
        } catch (Exception e) {
            releaseDraftRequest(draftRequestKey);
            logger.warn("主题发布失败: memberId={}, topicId={}, chapterId=未生成或未知", memberId, topicId, e);
            return e instanceof RuntimeException
                    ? publicationError((RuntimeException) e, AjaxResult.error(createFailureMessage(e)))
                    : AjaxResult.error(createFailureMessage(e));
        }
    }

    /**
     * P-15/C-15：业务闸以裸 RuntimeException 抛出的中文原因(如「玩法还在审核中」)与 GlobalExceptionHandler 同口径
     * 过 UserSafeMessage 后透出；NPE/解析失败等其它异常类型仍只给稳定兜底句。
     */
    static String createFailureMessage(Exception e) {
        if (e != null && e.getClass() == RuntimeException.class) {
            return UserSafeMessage.of(e.getMessage(), TOPIC_CREATE_GENERIC_MSG);
        }
        return TOPIC_CREATE_GENERIC_MSG;
    }

    private static final String DRAFT_REQUEST_PENDING = "PENDING";
    private static final int DRAFT_REQUEST_DAYS = 7;

    static String draftRequestKey(Long memberId, String requestId) {
        if (memberId == null || memberId <= 0 || requestId == null) return null;
        String rid = requestId.trim();
        return rid.matches("[A-Za-z0-9_-]{16,64}") ? "topic:draft-request:" + memberId + ":" + rid : null;
    }

    /**
     * 占用首次存草稿的意图键。返回 null = 已占到,继续建题;
     * 返回 success(recovered) = 这个键已建过且仍是本人的未删除主题(丢回包后的重试);返回 error = 同键请求在途。
     * 已建主题被删除时释放旧键,当作新的一次建题。
     */
    private AjaxResult claimDraftRequest(String key, Long memberId) {
        Object prior = redisCache.getCacheObject(key);
        if (prior != null && !DRAFT_REQUEST_PENDING.equals(String.valueOf(prior))) {
            Long priorId = Convert.toLong(prior, 0L);
            CmsTopic existing = priorId > 0 ? cmsTopicService.selectCmsTopicById(priorId) : null;
            if (existing != null && OwnershipUtils.isOwner(existing.getMemberId(), memberId)
                    && (existing.getDelFlag() == null || existing.getDelFlag() != 2)) {
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("topicId", priorId);
                data.put("recovered", Boolean.TRUE);
                data.put("isDraft", Integer.valueOf(1).equals(cmsTopicService.selectTopicIsDraft(priorId)));
                return AjaxResult.success("草稿已保存", data);
            }
            redisCache.deleteObject(key);
        }
        if (!Boolean.TRUE.equals(redisCache.setIfAbsent(key, DRAFT_REQUEST_PENDING, 10, java.util.concurrent.TimeUnit.MINUTES))) {
            return error("草稿保存结果确认中，请稍后重试");
        }
        return null;
    }

    private void releaseDraftRequest(String key) {
        if (key == null) return;
        try {
            Object current = redisCache.getCacheObject(key);
            if (DRAFT_REQUEST_PENDING.equals(String.valueOf(current))) redisCache.deleteObject(key);
        } catch (Exception ignored) {
            // 释放失败只会让该键在 10 分钟后自然过期
        }
    }

    /**
     * 员工同时保留个人发布身份；只有请求显式声明 MERCHANT 才切换到商家 Owner 主体。
     */
    private Long resolveProjectOwnerMemberId(Long principalMemberId, String scope) {
        if (!MerchantOperationScope.isMerchant(scope)) {
            return principalMemberId;
        }
        return merchantAccessService.require(
                principalMemberId, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId();
    }

    /**
     * 该主题挂着的俱乐部里,这个人有没有 ACTIVITY_MANAGE。
     * 没挂俱乐部的主题(clubId 为空)一律 false —— 放宽只对俱乐部主题生效。
     */
    private boolean canManageAsClubStaff(Long memberId, Long clubId) {
        if (memberId == null || memberId <= 0 || clubId == null || clubId <= 0) return false;
        com.chengyinhub.business.access.club.ClubAccessContext access =
                clubAccessService.resolve(memberId, clubId, null);
        return access != null
                && access.hasPermission(com.chengyinhub.business.access.club.ClubPermission.ACTIVITY_MANAGE);
    }

    @Operation(summary = "编辑主题(创作者本人;三档编辑窗口见 TopicEditWindow)")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @PostMapping("/update")
    public AjaxResult updateTopic(@Validated @RequestBody com.chengyinhub.business.domain.dto.TopicUpdateDTO dto) {
        return updateTopic(dto, false);
    }

    @Operation(summary = "V2 编辑主题（章节故事流）")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @PostMapping("/v2/update")
    public AjaxResult updateTopicV2(
            @Validated @RequestBody com.chengyinhub.business.domain.dto.TopicUpdateDTO dto) {
        return updateTopic(dto, true);
    }

    private AjaxResult updateTopic(com.chengyinhub.business.domain.dto.TopicUpdateDTO dto,
                                   boolean chapterFlowV2) {
        Long principalMemberId;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (principalMemberId == null || principalMemberId <= 0) {
            return error("请先登录");
        }
        Long memberId;
        try {
            memberId = resolveProjectOwnerMemberId(principalMemberId, dto.getScope());
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        // UGC 内容安全:与 /create 同一道闸(先发后审,POST_REVIEW),改文案也要过检。
        // 2026-10-20 分级审核:只复检本次改动过的文本 —— 名称/副标题/简介任一变了才送主文本,
        // 叙事文本只送已保存版本里没有的段落;改价/改票这类不碰文本的编辑不再重跑机审。
        // 已保存版本只认本人主题(归属闸在 service 内),非本人一律按全量送检。
        CmsTopic previousTopic=cmsTopicService.selectCmsTopicById(dto.getId());
        boolean ownPrevious = previousTopic != null && java.util.Objects.equals(memberId, previousTopic.getMemberId());
        String openid = resolveOpenid(principalMemberId);
        boolean mainTextChanged = !ownPrevious
                || TopicTextSafety.changed(dto.getName(), previousTopic.getName())
                || TopicTextSafety.changed(dto.getSubtitle(), previousTopic.getSubtitle())
                || TopicTextSafety.changed(dto.getDescription(), previousTopic.getDescription());
        String secText = mainTextChanged
                ? IWxContentSecurityService.joinText(dto.getName(), dto.getSubtitle(), dto.getDescription()) : null;
        java.util.List<JourneyReviewTexts.Entry> storyTexts = topicReviewTexts(dto, chapterFlowV2);
        if (ownPrevious) {
            storyTexts = TopicTextSafety.changedOnly(storyTexts, savedReviewTexts(previousTopic));
        }
        TopicTextSafety.Verdict textVerdict = TopicTextSafety.check(contentSafetyGate, memberId, openid, dto.getId(),
                secText, storyTexts);
        if (textVerdict.isBlocked()) {
            return AjaxResult.error(HttpStatus.BAD_REQUEST, textVerdict.getBlockMessage());
        }
        // D-78(owner 2026-11-11「没有封面不能保存」):编辑保存同样要有封面。本次没传封面(null)按「不改」处理,
        // 看已存的封面;只对本人主题判定,归属校验仍由 service 负责。
        if (ownPrevious) {
            String effectiveCover = dto.getImgUrl() != null ? dto.getImgUrl() : previousTopic.getImgUrl();
            if (StringUtils.isBlank(effectiveCover)) {
                return error("请上传主题封面");
            }
        }
        // 稿 E2:编辑同样要拦 —— 否则建的时候不填勋章、回头编辑再填就绕过去了。
        String previousRuntime=ownPrevious?previousTopic.getThemeRuntimeConfigJson():null;
        String medalCapErr = checkThemeMedalCapability(memberId, dto.getThemeRuntimeConfig(),previousRuntime);
        if (medalCapErr == null) medalCapErr = checkMedalCapability(memberId, dto.getFinishMedalName(), dto.getFinishMedalImg());
        if (medalCapErr != null) {
            return error(medalCapErr);
        }
        // X03:编辑同样要拦 —— 否则先建个小主题再编辑加节点就绕过了创建时的校验。
        String nodeQuotaErr = quotaService.checkNodesPerTheme(memberId, countNodes(dto));
        if (nodeQuotaErr != null) {
            return error(nodeQuotaErr);
        }
        Long topicId;
        TopicBundleSubmitResultVO v2Result = null;
        try {
            // 归属闸 + 编辑窗口闸都在 service 内(getAppUserId 不可 mock,闸留 controller 就测不到)
            if (chapterFlowV2) {
                v2Result = cmsTopicService.updateTopicByOwnerV2WithReview(
                        dto, memberId, getAppUserName());
                topicId = v2Result.getTopicId();
            } else {
                topicId = cmsTopicService.updateTopicByOwner(dto, memberId, getAppUserName());
            }
        } catch (ChapterFlowClientUpgradeRequiredException e) {
            return AjaxResult.error(HttpStatus.UPGRADE_REQUIRED, e.getMessage());
        } catch (ChapterFlowValidationException e) {
            return AjaxResult.error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ServiceException e) {
            AjaxResult attributeError = authorAttributeError(e);
            if (attributeError != null) return attributeError;
            logger.warn("主题保存被业务闸拒绝: topicId={}, reason={}", dto.getId(), e.getMessage());
            return publicationError(e, businessRefusal(e, e.getMessage()));
        } catch (IllegalArgumentException e) {
            // 第十三轮 m1 P2:发布闸 / 配置校验拒绝(事务已回滚、什么也没写)是确定拒绝,按 400 回;
            // 原来落到下面的 500,作者端只能按「结果未知」处理,改好了也存不进去、一直停在待确认。
            logger.warn("主题保存被校验拒绝: topicId={}, reason={}", dto.getId(), e.getMessage());
            return publicationError(e, AjaxResult.error(HttpStatus.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            AjaxResult attributeError = authorAttributeError(e);
            if (attributeError != null) return attributeError;
            logger.warn("主题保存失败: topicId={}, chapterId=未生成或未知", dto.getId(), e);
            return publicationError(e, error(e.getMessage()));
        }
        boolean held = false;
        if (textVerdict.isFlagged()) {
            held = flagForSpotCheck(topicId, memberId, textVerdict.getFlagReason(), recheckText(secText, storyTexts),
                    dto.getImgUrl(), dto.getImgArr());
        } else if (mediaUncheckable(dto.getImgUrl(), dto.getImgArr(), openid)) {
            held = flagForSpotCheck(topicId, memberId, "MEDIA_UNCHECKED", null, dto.getImgUrl(), dto.getImgArr());
        }
        if (held && v2Result != null) v2Result.markHeldForReview();
        wxContentSecurityService.submitMediaCheck(
                IWxContentSecurityService.BIZ_TYPE_TOPIC,
                topicId,
                StringUtils.isNotEmpty(dto.getImgArr()) ? dto.getImgArr() : dto.getImgUrl(),
                openid);
        return AjaxResult.success(held || v2Result != null && !v2Result.isPublished() && ("PENDING".equals(v2Result.getReviewState())
                        || "ESCALATED".equals(v2Result.getReviewState())) ? "已提交审核" : "保存成功",
                v2Result == null ? topicId : v2Result);
    }

    @Autowired
    private com.chengyinhub.business.service.IPlayerWxacodeService playerWxacodeService;

    /**
     * 走查 v2 R15(业主 2026-10-30):主办方的「本主题二维码」—— 玩法要求扫码参与时贴在现场,
     * 玩家微信扫一扫直接进这个主题(承办商家那张是自己那一站的海报码,另走 /api/merchant/chapter-node/poster-code)。
     * scene = {@code tp.<主题id>},落地页 pages/index/index 按前缀转到主题详情;码永久复用(服务内按 scene 缓存)。
     */
    @Operation(summary = "主办方取本主题小程序码(仅发布者)")
    @PostMapping("/host-qrcode")
    public AjaxResult hostQrcode(String id, String scope) {
        Long principal;
        try { principal = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (principal == null || principal <= 0) return error("请先登录");
        Long topicId = Convert.toLong(id, 0L);
        if (topicId <= 0) return error("参数不合规");
        Long owner;
        try {
            owner = resolveProjectOwnerMemberId(principal, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() != 0)
                || OwnershipUtils.notOwner(topic.getMemberId(), owner)) return error("主题不存在或无权查看");
        Map<String, Object> data = new HashMap<>();
        try {
            data.put("qrcodeUrl", playerWxacodeService.getOrCreateScene("tp." + topicId));
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        data.put("topicId", topicId);
        data.put("topicName", topic.getName());
        return success(data);
    }

    @Autowired
    private IPuzzlePlayService puzzlePlayService;

    /**
     * 2026-11-01 业主:商家打开主题卡也能看剧情、玩法与答案,和俱乐部那边(/api/club/topic-node-answer)一样。
     * 准入 fail-closed:主题发布者本人,或这一站已中标(audit_status=1)的承接商家;站点必须属于这个主题。
     * 答案文本同样只走 IPuzzlePlayService(只放行文字问答 / 选择题),不另写判据。
     */
    @Operation(summary = "商家查看本主题某站点的玩法答案(发布者 / 该站承接商家)")
    @PostMapping("/host-node-answer")
    public AjaxResult hostNodeAnswer(@RequestBody Map<String, Object> body) {
        Long principal;
        try { principal = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (principal == null || principal <= 0) return error("请先登录");
        if (body == null) return error("缺少参数");
        Long topicId = Convert.toLong(body.get("topicId"), 0L);
        Long nodeId = Convert.toLong(body.get("nodeId"), 0L);
        if (topicId <= 0 || nodeId <= 0) return error("缺少主题或站点");
        Long owner;
        try {
            owner = resolveProjectOwnerMemberId(principal, body.get("scope") == null ? null : String.valueOf(body.get("scope")));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() != 0)) return error("主题不存在");
        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nodeId);
        if (node == null || !topicId.equals(node.getTopicId())) return error("该站点不属于本主题");
        boolean publisher = !OwnershipUtils.notOwner(topic.getMemberId(), owner);
        boolean undertaker = false;
        if (!publisher) {
            for (CmsRegistrationMerchant row : selectOwnFulfillments(topicId, owner)) {
                if (nodeId.equals(row.getNodeId())) { undertaker = true; break; }
            }
        }
        if (!publisher && !undertaker) return error("只有主办方或这一站的承接商家能看答案");
        CmsMemberTemplate template = puzzlePlayService.selectPuzzleTemplate(node);
        if (template == null) return error("该站点没有可查看的答案");
        List<String> hints = new ArrayList<>();
        if (StringUtils.isNotBlank(template.getHint1())) hints.add(template.getHint1());
        if (StringUtils.isNotBlank(template.getHint2())) hints.add(template.getHint2());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeId", node.getId());
        data.put("gameTitle", template.getTitle());
        data.put("question", template.getQuestionName());
        data.put("answerReveal", puzzlePlayService.revealText(template));
        data.put("hints", hints);
        data.put("feedbackText", template.getFeedbackText());
        return success(data);
    }

    /**
     * 通用分享页卡片右下的小程序码(2026-10-31 业主批准 P-8 ⑤:主题卡 / 勋章卡都放码)。
     * 主题:只给已上架、未删除的主题出码(scene = tp.<id>,落地页转主题详情,与主办方的本主题码同一张);
     * 不带主题 id(勋章卡)出首页码。码按 scene 永久复用(服务内缓存),不会每次分享都生成。
     */
    @Operation(summary = "分享卡片的小程序码(主题 / 首页)")
    @PostMapping("/share-code")
    public AjaxResult shareCode(String id) {
        Long principal;
        try { principal = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (principal == null || principal <= 0) return error("请先登录");
        Long topicId = Convert.toLong(id, 0L);
        String scene = "home";
        if (topicId > 0) {
            CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
            if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() != 0)
                    || !Integer.valueOf(1).equals(topic.getStatus())) return error("主题还没上架，暂时不能生成小程序码");
            scene = "tp." + topicId;
        }
        Map<String, Object> data = new HashMap<>();
        try {
            data.put("qrcodeUrl", playerWxacodeService.getOrCreateScene(scene));
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        return success(data);
    }

    /** 发布者邀约选章用的只读卡片，不返回章节编辑内容。 */
    @Operation(summary = "邀约选章卡片（仅发布者，只读，不受编辑锁限制）")
    @PostMapping("/invite-chapters")
    public AjaxResult inviteChapters(String id, String scope) {
        Long principal;
        try { principal = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (principal == null || principal <= 0) return error("请先登录");
        Long topicId = Convert.toLong(id, 0L);
        if (topicId <= 0) return error("参数不合规");
        Long owner;
        try {
            owner = resolveProjectOwnerMemberId(principal, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() != 0)
                || OwnershipUtils.notOwner(topic.getMemberId(), owner)) return error("主题不存在或无权查看");
        CmsTopicChapter query = new CmsTopicChapter();
        query.setTopicId(topicId);
        List<Map<String, Object>> cards = new ArrayList<>();
        List<CmsTopicChapter> chapters = cmsTopicChapterService.selectCmsTopicChapterList(query);
        if (chapters != null) for (CmsTopicChapter chapter : chapters) {
            Map<String, Object> card = new java.util.LinkedHashMap<>();
            card.put("id", chapter.getId());
            card.put("name", chapter.getName());
            card.put("imgArr", chapter.getImgArr());
            cards.add(card);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("chapters", cards);
        return success(data);
    }

    /**
     * 编辑器回填用的主题全量详情。
     *
     * <p>为什么不复用 {@code /info-to-user}:那条是玩家视角的公开投影(票走 PublicTicketVO 脱敏、
     * 不带彩蛋/库存),回填不回来就等于编辑一次静默丢一批字段。这条只给主题本人,返回原始形状。
     */
    @Operation(summary = "编辑器回填:主题全量详情(仅创作者本人)")
    @PostMapping("/author-edit-detail")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AjaxResult authorEditDetail(String id, String scope) {
        return authorDetailWire(topicEditDetail(id, scope));
    }

    /** 保存/发布回读仍校验创作者归属，但不授予已开始主题的编辑权。 */
    @PostMapping("/author-saved-detail")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AjaxResult authorSavedDetail(String id, String scope) {
        return authorDetailWire(topicEditDetail(id, scope, true));
    }

    private AjaxResult authorDetailWire(AjaxResult result) {
        if (Integer.valueOf(200).equals(result.get(AjaxResult.CODE_TAG))) {
            Map<String, Object> data = new HashMap<>((Map<String, Object>) result.get(AjaxResult.DATA_TAG));
            CmsTopic topic = (CmsTopic) data.get("topic");
            data.put("nodeAlternativeConfigs", java.util.Collections.emptyMap());
            data.put("nodeAlternativeConfigsLoaded", false);
            if (Integer.valueOf(ProductType.ORIENTEERING).equals(topic.getProductType())) {
                try {
                    Long principal = getAppUserId();
                    if (principal != null && principal.equals(topic.getMemberId())) {
                        Map<String, List<Map<String, Object>>> configs = orientTaskService.authorAlternativeConfigs(principal, topic.getId());
                        if (configs != null) {
                            data.put("nodeAlternativeConfigs", configs);
                            data.put("nodeAlternativeConfigsLoaded", true);
                        }
                    }
                } catch (RuntimeException unavailable) {
                    logger.warn("主题替代完成配置读取失败: topicId={}", topic.getId());
                }
            }
            data.put("topic", new com.chengyinhub.business.domain.dto.TopicAuthorEditDTO(topic));
            result.put(AjaxResult.DATA_TAG, com.chengyinhub.business.service.support.TemplateAdoptionWire.strings(data, "templateId", responseObjectMapper));
        }
        return result;
    }

    @PostMapping("/edit-detail")
    @Parameters({@Parameter(name = "id", description = "主题id")})
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AjaxResult topicEditDetail(String id, String scope) {
        return topicEditDetail(id, scope, false);
    }

    private AjaxResult topicEditDetail(String id, String scope, boolean readback) {
        Long topicId = Convert.toLong(id, 0L);
        if (topicId < 1) {
            return error("参数不合规");
        }
        Long principalMemberId;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (principalMemberId == null || principalMemberId <= 0) {
            return error("请先登录");
        }
        Long memberId;
        try {
            memberId = resolveProjectOwnerMemberId(principalMemberId, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() == 2)) {
            return error("主题不存在");
        }
        if (OwnershipUtils.notOwner(topic.getMemberId(), memberId)) {
            return error("无权编辑该主题");
        }
        com.chengyinhub.business.util.TopicEditWindow.Scope editWindowScope;
        Integer isDraft = cmsTopicService.selectTopicIsDraft(topicId);
        try {
            editWindowScope = com.chengyinhub.business.util.TopicEditWindow.resolve(
                    topic, isDraft, new Date());
        } catch (RuntimeException e) {
            if (!readback) return error(e.getMessage());
            editWindowScope = null;
        }

        CmsTopicChapter chapterQ = new CmsTopicChapter();
        chapterQ.setTopicId(topicId);
        List<CmsTopicChapter> chapters = cmsTopicChapterService.selectCmsTopicChapterList(chapterQ);
        if (chapters == null) {
            chapters = new ArrayList<>();
        }
        List<OmsTicket> topicTickets = selectTopicTickets(topicId);
        boolean resolveBlocks = ChapterBlockReadSupport.shouldResolveBlocks(
                topic.getProductType(), topicTickets);
        for (CmsTopicChapter chapter : chapters) {
            CmsTopicNode nodeQ = new CmsTopicNode();
            nodeQ.setChapterId(chapter.getId());
            List<CmsTopicNode> chapterNodes = cmsTopicNodeService.selectCmsTopicNodeList(nodeQ);
            chapter.setCmsTopicNodeList(chapterNodes);
            if (resolveBlocks && ChapterBlockReadSupport.isRequiredChapter(chapter)) {
                chapter.setBlocks(ChapterBlockReadSupport.resolve(chapter, chapterNodes));
            }
        }

        ViewCollaborators collabQ = new ViewCollaborators();
        collabQ.setDataType(1L);
        collabQ.setDataId(topicId);
        List<ViewCollaborators> collaborators = viewCollaboratorsService.selectViewCollaboratorsList(collabQ);
        List<Long> collaboratorIds = new ArrayList<>();
        if (collaborators != null) {
            for (ViewCollaborators c : collaborators) {
                collaboratorIds.add(c.getMemberId());
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("topic", topic);
        // R2-B10 编辑器回显:顶层单列一份 journeyRules(与保存入参同名),老编辑器不读它也不受影响。
        data.put("journeyRules", topic.getJourneyRulesJson());
        data.put("themeRuntimeConfig", topic.getThemeRuntimeConfigJson());
        data.put("look", cmsTopicMapper == null ? null : cmsTopicMapper.selectLookJsonById(topic.getId()));
        // R8-STORY 同款回显:主题级剧情配置与保存入参同名
        data.put("journeyStory", topic.getJourneyStoryJson());
        data.put("openingChapterId", com.chengyinhub.business.domain.JourneyStory.openingChapterId(topic.getJourneyStoryJson()));
        data.put("chapters", chapters);
        data.put("tickets", topicTickets);
        data.put("collaboratorIds", collaboratorIds);
        // 前端据此决定「结构/票价能不能编」:WHITELIST 档只许改文案与图
        data.put("editScope", editWindowScope == null ? "NONE" : editWindowScope.name());
        // 服务端草稿（is_draft=1）：编辑器据此继续「保存草稿 / 发布草稿」，而不是按已发布主题处理
        data.put("isDraft", Integer.valueOf(1).equals(isDraft));
        return success(data);
    }

    private boolean isSimplePublishMode(String publishMode) {
        return "simple".equalsIgnoreCase(publishMode) || "ai_simple".equalsIgnoreCase(publishMode);
    }

    /** 内容安全送检需要发表者 openid:按 memberId 查会员;查不到返回空串(降级 scene 检测)。 */
    /** 送检的叙事文本:主题级剧情配置/章节/票种等(R12),V2 再加章节故事流 beat 块(R19)。 */
    private java.util.List<JourneyReviewTexts.Entry> topicReviewTexts(TopicCreateDTO dto, boolean chapterFlowV2) {
        java.util.List<JourneyReviewTexts.Entry> entries = combinedThemeReviewTexts(dto);
        if (chapterFlowV2) {
            entries.addAll(JourneyReviewTexts.collectChapterNarrativeBlocks(dto.getChapters()));
        }
        java.util.Set<Long> templateIds = new java.util.LinkedHashSet<Long>();
        if (dto.getChapters() != null) for (com.chengyinhub.business.domain.dto.ChapterDTO chapter : dto.getChapters()) {
            if (chapter == null || chapter.getNodes() == null) continue;
            for (com.chengyinhub.business.domain.dto.NodeDTO node : chapter.getNodes()) {
                if (node != null && node.getTemplateId() != null && node.getTemplateId() > 0L) templateIds.add(node.getTemplateId());
            }
        }
        entries.addAll(boundTemplateReviewTexts(templateIds));
        return entries;
    }

    /**
     * check-1104 j5 B1:主题节点绑定的节点玩法文字(名称/规则/题干/选项/提示/反馈/故事块)随主题一起过同一道
     * 内容安全闸。玩法草稿只随主题公开,原先只走媒体闸 ⇒ 违规词经主题直接给全部玩家看。
     * 只读取文字送检,不改归属判定(归属仍由 service 内 assertTemplateOwned 管)。
     */
    private java.util.List<JourneyReviewTexts.Entry> persistedBoundTemplateReviewTexts(Long topicId) {
        CmsTopicNode query = new CmsTopicNode();
        query.setTopicId(topicId);
        java.util.Set<Long> templateIds = new java.util.LinkedHashSet<Long>();
        for (CmsTopicNode node : cmsTopicNodeService.selectCmsTopicNodeList(query)) {
            if (node != null && node.getTemplateId() != null && node.getTemplateId() > 0L) templateIds.add(node.getTemplateId());
        }
        return boundTemplateReviewTexts(templateIds);
    }

    private java.util.List<JourneyReviewTexts.Entry> boundTemplateReviewTexts(java.util.Collection<Long> templateIds) {
        java.util.List<JourneyReviewTexts.Entry> entries = new java.util.ArrayList<JourneyReviewTexts.Entry>();
        if (templateIds == null || templateIds.isEmpty()) return entries;
        for (com.chengyinhub.business.domain.CmsMemberTemplate template
                : memberTemplateService.selectCmsMemberTemplateByIds(new java.util.ArrayList<Long>(templateIds))) {
            if (template == null) continue;
            for (JourneyReviewTexts.Entry entry : JourneyReviewTexts.collectBoundTemplate(template)) {
                entries.add(new JourneyReviewTexts.Entry("节点玩法「" + template.getTitle() + "」." + entry.getField(), entry.getText()));
            }
        }
        return entries;
    }

    /**
     * 已保存版本里的全部可读文本(主题字段 + 剧情/规则 JSON + 章节/节点 + 票种),
     * 用来判定编辑时哪些叙事段落是新写的。读不全只会让更多文本被送检(fail-safe),不会漏检。
     */
    private java.util.Set<String> savedReviewTexts(CmsTopic topic) {
        java.util.Set<String> known = new java.util.HashSet<String>();
        TopicTextSafety.addText(known, topic.getFinishMedalName());
        TopicTextSafety.addJsonTexts(known, topic.getJourneyStoryJson());
        TopicTextSafety.addJsonTexts(known, topic.getThemeRuntimeConfigJson());
        TopicTextSafety.addJsonTexts(known, topic.getJourneyRulesJson());
        TopicTextSafety.addJsonTexts(known, topic.getCompleteRuleJson());
        try {
            CmsTopicChapter chapterQ = new CmsTopicChapter();
            chapterQ.setTopicId(topic.getId());
            java.util.List<CmsTopicChapter> chapters = cmsTopicChapterService.selectCmsTopicChapterList(chapterQ);
            if (chapters != null) for (CmsTopicChapter chapter : chapters) {
                TopicTextSafety.addText(known, chapter.getName());
                TopicTextSafety.addText(known, chapter.getDescription());
                TopicTextSafety.addText(known, chapter.getCategory());
                TopicTextSafety.addJsonTexts(known, chapter.getBlocksJson());
            }
            CmsTopicNode nodeQ = new CmsTopicNode();
            nodeQ.setTopicId(topic.getId());
            java.util.List<CmsTopicNode> nodes = cmsTopicNodeService.selectCmsTopicNodeList(nodeQ);
            if (nodes != null) for (CmsTopicNode node : nodes) {
                TopicTextSafety.addText(known, node.getName());
                TopicTextSafety.addText(known, node.getDescription());
                TopicTextSafety.addText(known, node.getAddress());
                TopicTextSafety.addText(known, node.getBusinessTime());
                TopicTextSafety.addText(known, node.getHookText());
                TopicTextSafety.addText(known, node.getCardHookLong());
                TopicTextSafety.addText(known, node.getFragmentText());
            }
            // 票种送检文本是「名称+说明+集合点+地址+退改」的拼接;票实体不落说明,带说明的票会被重新送检(fail-safe)
            for (OmsTicket ticket : selectTopicTickets(topic.getId())) {
                TopicTextSafety.addText(known, IWxContentSecurityService.joinText(ticket.getName(),
                        ticket.getMeetingPoint(), ticket.getMeetingPointAddress(), ticket.getRefundRule()));
            }
        } catch (Exception e) {
            logger.warn("读取主题已保存文本失败,叙事文本按全量送检 topicId={}", topic.getId(), e);
        }
        return known;
    }

    /**
     * Round5B-3:机审没给出结论的主题不公开。保存照常,随后复用内容安全留置(下架 + check_status=2 + 原因),
     * 非草稿立即挂复核上架单(content_safety_relist),审核台通过后自动上架;草稿在发布草稿时挂单。
     * (旧口径是「照常发布 + post_review_flag 抽检单」,没审过的内容会先公开。)
     */
    /**
     * 机审没结论:主题留置为仅作者可见,并进自动复检队列(Round5C)。复检通过自动解除留置上架,
     * 违规或耗尽自动驳回并通知作者,全程不需要人。
     */
    /** @return 主题已留置为仅作者可见(回执要按审核中报)。 */
    private boolean flagForSpotCheck(Long topicId, Long ownerMemberId, String reason, String recheckText,
                                  String imgUrl, String imgArr) {
        if (topicId == null) return false;
        logger.warn("主题机审未完成,留置为仅作者可见并自动复检 topicId={} reason={}", topicId, reason);
        boolean held = false;
        try {
            cmsTopicService.holdUncheckedTopicForReview(topicId, ownerMemberId);
            held = true;
        } catch (Exception e) {
            logger.error("主题待复检留置失败 topicId={}", topicId, e);
        }
        if (recheckService != null) {
            try {
                recheckService.enqueue(com.chengyinhub.business.domain.AuditTask.BIZ_TOPIC, topicId, ownerMemberId,
                        recheckText, recheckMedia(imgUrl, imgArr), false);
            } catch (Exception e) {
                logger.error("主题自动复检进队失败 topicId={}", topicId, e);
            }
        }
        return held;
    }

    static String recheckText(String mainText, java.util.List<JourneyReviewTexts.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        if (mainText != null && !mainText.trim().isEmpty()) sb.append(mainText);
        if (entries != null) for (JourneyReviewTexts.Entry e : entries) {
            if (e == null || e.getText() == null || e.getText().trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getText());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static java.util.List<String> recheckMedia(String imgUrl, String imgArr) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String raw : new String[]{imgArr, imgUrl}) {
            if (raw == null) continue;
            for (String one : raw.split(",")) {
                String v = one.trim();
                if (!v.isEmpty() && !out.contains(v)) out.add(v);
            }
        }
        return out;
    }

    /** Round5B-3:带封面/图集但图片送不了检(总开关关 / 缺 openid)= 没人审过这张图,不能直接公开。 */
    private boolean mediaUncheckable(String imgUrl, String imgArr, String openid) {
        boolean hasMedia = StringUtils.isNotEmpty(imgUrl) || StringUtils.isNotEmpty(imgArr);
        return hasMedia && !wxContentSecurityService.canSubmitMedia(openid);
    }

    private String resolveOpenid(Long memberId) {
        try {
            UmsMember m = umsMemberService.selectUmsMemberById(memberId);
            return m == null ? "" : m.getWxOpenId();
        } catch (Exception e) {
            return "";
        }
    }

    @Operation(summary = "删除主题")
    @PostMapping("/delete")
    @Parameters({
            @Parameter(name = "id", description = "主题id")
    })
    public AjaxResult topicDelete(String id, String scope) {
        Long tid = Convert.toLong(id, 0L);
        if (tid < 1) {
            return error("参数不合规");
        }
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            logger.warn("topicDelete 获取用户ID异常", e);
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        try {
            memberId = resolveProjectOwnerMemberId(memberId, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }

        try {
            // [C3] 归属校验(IDOR)+ 付费报名闸一并下沉到 service —— 闸留在 controller 就测不到
            // (getAppUserId 不可 mock),而「闸在路径上」只有行为测试证得了。
            return cmsTopicService.deleteTopicByOwner(tid, memberId)
                    ? success("删除成功") : error("删除失败");
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    /**
     * [C8-05] 主题主办方(天气/场地原因)取消主题并全额退款:停售 + 主题级已付款票全额退 + 逐场取消场次。
     * 个人主办传空 scope;商家主办传 MERCHANT(店主或持 PROJECT_MANAGE 的员工)。俱乐部主题走「结束主题」。
     */
    @Operation(summary = "主办方取消主题(全额退款给所有已付款玩家)")
    @PostMapping("/cancel")
    @Parameters({
            @Parameter(name = "id", description = "主题id"),
            @Parameter(name = "reason", description = "取消原因(展示给已付款玩家)"),
            @Parameter(name = "scope", description = "MERCHANT=以商家主办身份;空=个人主办"),
            @Parameter(name = "cancellationEpoch", description = "原预览返回的取消轮次，重试必须保持原值")
    })
    public AjaxResult topicCancel(String id,String reason,String scope,Long cancellationEpoch) {
        Long tid = Convert.toLong(id, 0L);
        if (tid < 1) {
            return error("参数不合规");
        }
        if (reason == null || reason.trim().isEmpty()) {
            return error("请填写取消原因,它会展示给已付款的玩家");
        }
        Long actorMemberId = 0L;
        try {
            actorMemberId = getAppUserId();
        } catch (Exception e) {
            logger.warn("topicCancel 获取用户ID异常", e);
        }
        if (actorMemberId == null || actorMemberId <= 0) {
            return error("请先登录");
        }
        // F-L5C-02:取消原因会展示给全部已付款玩家,写入前送检。
        String reasonSecMsg = wxContentSecurityService.checkText(reason, resolveOpenid(actorMemberId));
        if (reasonSecMsg != null) {
            return error(reasonSecMsg);
        }
        try {
            Long hostMemberId = resolveProjectOwnerMemberId(actorMemberId, scope);
            com.chengyinhub.business.domain.dto.ActivityCancelResult res =
                    cancellationEpoch==null?teamGroupService.cancelTopicByHost(tid,hostMemberId,actorMemberId,reason.trim())
                            :teamGroupService.cancelTopicByHost(tid,hostMemberId,actorMemberId,reason.trim(),cancellationEpoch);
            StringBuilder msg = new StringBuilder(res.getRefundedOrders() > 0
                    ? ("主题已取消,已为 " + res.getRefundedOrders() + " 笔订单全额退款(原路退回,预计1-3个工作日)")
                    : "主题已取消(无可自动退款的已付款订单)");
            if (res.getManualOrders() > 0) {
                msg.append(";另有 ").append(res.getManualOrders())
                        .append(" 笔订单含已核销的票,无法自动退款,平台将人工跟进处理");
            }
            Map<String,Object> original=new HashMap<>();original.put("refundedOrders",res.getRefundedOrders());
            original.put("manualOrders",res.getManualOrders());original.put("closedPendingOrders",res.getClosedPendingOrders());
            original.put("cancellationEpoch",cancellationEpoch==null?0L:cancellationEpoch);
            original.put("cancellationState","DONE");
            return AjaxResult.success(msg.toString(),original);
        } catch (ServiceException se) {
            return AjaxResult.error(se.getMessage());
        } catch (Exception e) {
            logger.error("主题取消失败: topicId={}, memberId={}", tid, actorMemberId, e);
            return AjaxResult.error("系统繁忙，请稍后重试");
        }
    }

    /** Source-compatible caller for the legacy first epoch, not another HTTP route. */
    public AjaxResult topicCancel(String id,String reason,String scope) {return topicCancel(id,reason,scope,null);}

    @Operation(summary = "取消主题前预览:将被全额退款的已付款玩家人数")
    @PostMapping("/cancel_preview")
    public AjaxResult topicCancelPreview(String id, String scope) {
        Long tid = Convert.toLong(id, 0L);
        if (tid < 1) {
            return error("参数不合规");
        }
        Long actorMemberId = 0L;
        try {
            actorMemberId = getAppUserId();
        } catch (Exception e) {
            logger.warn("topicCancelPreview 获取用户ID异常", e);
        }
        if (actorMemberId == null || actorMemberId <= 0) {
            return error("请先登录");
        }
        try {
            Long hostMemberId = resolveProjectOwnerMemberId(actorMemberId, scope);
            Map<String, Object> out = new HashMap<>();
            out.put("paidPlayers", teamGroupService.countPaidPlayersForTopicCancel(tid, hostMemberId));
            out.put("cancellationEpoch",teamGroupService.topicCancellationEpoch(tid,hostMemberId));
            return AjaxResult.success(out);
        } catch (ServiceException se) {
            return AjaxResult.error(se.getMessage());
        } catch (Exception e) {
            logger.error("主题取消预览失败: topicId={}, memberId={}", tid, actorMemberId, e);
            return AjaxResult.error("系统繁忙，请稍后重试");
        }
    }

    /** user_status 的唯一归一口径:NULL(存量脏数据)按「未上架」算,与 mapper 的 COALESCE 一致。 */
    private static Integer normalizeUserStatus(Integer raw) {
        return raw != null && raw == 1 ? 1 : 0;
    }

    private static String userStatusLabel(Integer normalized) {
        return normalized == 1 ? "已上架" : "未上架";
    }

    @Operation(summary = "主题上下架(翻转;传 expectedUserStatus 做 CAS,防陈旧请求覆盖新状态)")
    @PostMapping("/update_user_status")
    @Parameters({
            @Parameter(name = "id", description = "主题id"),
            @Parameter(name = "expectedUserStatus",
                    description = "客户端渲染按钮时看到的上架态(0/1)。传了就做 CAS:与库中不符即拒并告知当前态。"
                            + "老客户端不传时用服务端刚读到的态兜底(仍走 CAS,只是关不掉「客户端列表陈旧」那一类)")
    })
    public AjaxResult topicUpdateUserStatus(String id, String expectedUserStatus, String scope) {
        Long cid = Convert.toLong(id, 0L);
        if (cid < 1) {
            return error("参数不合规");
        }
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            logger.warn("topicUpdateUserStatus 获取用户ID异常", e);
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        try {
            memberId = resolveProjectOwnerMemberId(memberId, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CmsTopic cmsTopic = cmsTopicService.selectCmsTopicById(cid);
        if (cmsTopic == null) {
            return error("活动不存在");
        }
        // 归属校验(IDOR):创建者本人,或**该主题所属俱乐部**里持 ACTIVITY_MANAGE 的岗位。
        // 放宽的边界写死在 clubId 上:必须是这条主题自己挂着的俱乐部,拿别的俱乐部的岗位过不来。
        // 2026-09-09 放宽原因:俱乐部的「结束主题」需要管理员也能停售,不能只有主理人本人点得动。
        if (OwnershipUtils.notOwner(cmsTopic.getMemberId(), memberId)
                && !canManageAsClubStaff(memberId, cmsTopic.getClubId())) {
            return error("无权操作该主题");
        }

        //用户端状态：0未上架  1已上架
        Integer current = normalizeUserStatus(cmsTopic.getUserStatus());
        // 期望态:客户端渲染那一刻看到的态。垃圾值当没传(别拿它比出个恒不符,那会让按钮彻底点不动)。
        Integer expected = "0".equals(expectedUserStatus) ? Integer.valueOf(0)
                : "1".equals(expectedUserStatus) ? Integer.valueOf(1) : null;
        if (expected != null && !expected.equals(current)) {
            // 客户端那一屏已经过时。旧行为会按翻转算出一个**反向**动作照做并提示「操作成功」——
            // 最危险的一支是:内容安全回调刚把违规主题自动下架,一次陈旧点击又把它推回上架。
            return error("主题当前是「" + userStatusLabel(current) + "」，与你点击时看到的不一致，请刷新后重试");
        }
        Integer casExpected = expected != null ? expected : current;
        int target = casExpected == 1 ? 0 : 1;
        // AUDIT-G2-1:内容安全图片回调下架的主题(留置中)不能由作者直接翻回上架 ——
        // 改为提交人工复核,复核通过由审核台恢复在架。下架方向不受影响。
        if (target == 1 && com.chengyinhub.business.util.TopicContentSafetyHold.isHeld(cmsTopic)) {
            try {
                cmsTopicService.requestContentSafetyRelistReview(cid, cmsTopic.getMemberId());
            } catch (Exception e) {
                logger.error("内容安全下架主题复核单提交失败 topicId={}", cid, e);
                return error("主题因图片未通过内容安全检测已被下架，复核申请提交失败，请稍后重试");
            }
            return error(com.chengyinhub.business.util.TopicContentSafetyHold.RELIST_REFUSED);
        }

        // 上架额度:目标为上架(0→1)时,锁会员 → 复查在架数 → 置上架,三步同事务原子完成,防并发绕过上限
        //(排除自身保证重复上架幂等);超限时 guard 抛 ServiceException,由全局异常处理转 error 文案。
        // 下架(1→0)释放额度,无需校验。草稿与 selfPlay 不占。
        // ⚠️ 两个方向不对称:下架恒成功,上架可能被额度闸拒 ⇒ 误点下架未必回得去。CAS 只能减少
        //   「误点」的发生率,不能让下架变可逆;真要治得在下架前给确认提示(未做,见报告 §1.5)。
        // ⚠️ 额度算主题主人的,不算点按钮的人 —— 放宽之前两者恒等(只有创建者点得动),
        // 放宽之后俱乐部管理员也能点,再用 actor 就会去扣他自己的在架额度。
        final Long fMemberId = cmsTopic.getMemberId();
        final Long fCid = cid;
        final Integer fExpected = casExpected;
        if (target == 1) {
            Integer rows = quotaService.runWithThemeOnlineGuard(fMemberId, fCid,
                    () -> cmsTopicService.casUserStatus(fCid, fExpected, 1));
            if (rows != null && rows > 0) {
                return success("上架成功");
            }
            // CAS 落空 = 读到态之后、写下去之前被别人抢改了。这个窗口在库里,应用层的早检查关不掉。
            return error("上架失败，主题状态已变化，请刷新后重试");
        }
        int rows = cmsTopicService.casUserStatus(fCid, fExpected, 0);
        return rows > 0 ? success("下架成功") : error("下架失败，主题状态已变化，请刷新后重试");
    }

    @Operation(summary = "举报主题(进统一审核队列,不立即下架;同一人同一轮只记一次)")
    @PostMapping("/report")
    @Parameters({
            @Parameter(name = "id", description = "主题id"),
            @Parameter(name = "reason", description = "举报原因")
    })
    public AjaxResult topicReport(String id, String reason) {
        Long cid = Convert.toLong(id, 0L);
        if (cid < 1) {
            return error("参数不合规");
        }
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            logger.warn("topicReport 获取用户ID异常", e);
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        // 只开放公开可见的主题:草稿/下架/驳回的主题不对外,也不能借举报给它们建审核单。
        CmsTopic topic = cmsTopicService.selectCmsTopicById(cid);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())
                || !Integer.valueOf(1).equals(topic.getStatus()) || !Integer.valueOf(1).equals(topic.getUserStatus())
                || Integer.valueOf(1).equals(cmsTopicService.selectTopicIsDraft(cid))) {
            return error("主题不存在");
        }
        try {
            boolean recorded = auditTaskService.reportContentOnce(
                    com.chengyinhub.business.domain.AuditTask.BIZ_TOPIC, cid, memberId, reason);
            return success(recorded ? "举报已提交，内容将进入审核" : "你已举报过该主题，我们会尽快处理");
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (ServiceException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            logger.error("主题举报失败 topicId={}", cid, e);
            return error("举报失败");
        }
    }

    @Operation(summary = "移交主题给俱乐部承接(复制一份新草稿挂该俱乐部,原主题下架;发起人保留所有权)")
    @PostMapping("/transfer-to-club")
    public AjaxResult transferToClub(String topicId, String clubId, String scope) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        try {
            memberId = resolveProjectOwnerMemberId(memberId, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        Long tid = Convert.toLong(topicId, 0L);
        Long cid = Convert.toLong(clubId, 0L);
        if (tid <= 0 || cid <= 0) return error("参数不完整");
        CmsTopic topic = cmsTopicService.selectCmsTopicById(tid);
        if (topic == null) return error("主题不存在");
        // 归属校验:仅主题创建者本人可移交(IDOR),防越权移交他人主题
        if (OwnershipUtils.notOwner(topic.getMemberId(), memberId)) return error("无权移交该主题");
        // 承接俱乐部合法性:须本人主理该俱乐部,或本人已获该俱乐部通过的合作邀约——与 /create 城市定向绑定门禁一致,
        // 否则移交(挂 club_id 后 publish-draft 不复验 coop)会成为绕过 coop 门禁把主题假挂他人俱乐部的口子。
        Club club = clubService.selectClubById(cid);
        if (club == null) return error("俱乐部不存在");
        ClubLeader leader = clubLeaderService.selectClubLeaderByMemberId(memberId);
        boolean isLeaderOfOwnClub = OwnershipUtils.isOwner(club.getMemberId(), memberId)
                && leader != null && (leader.getStatus() == null || leader.getStatus() == 1);
        if (!isLeaderOfOwnClub) {
            CoopInvite coopQ = new CoopInvite();
            coopQ.setFromId(memberId);
            coopQ.setInviteType(1);
            coopQ.setToType("club");
            coopQ.setToId(cid);
            coopQ.setStatus(1);
            if (coopInviteService.selectCoopInviteList(coopQ).isEmpty()) {
                return error("需为该俱乐部主理人,或先获得该俱乐部的合作邀约通过,才能移交承接");
            }
        }
        // 新草稿归属仍为发起人本人(所有权/核心编辑/换俱乐部权留发起人),由发起人配置为城市定向
        // (mode=1 票种·有人带)后邀该俱乐部承接带队。
        // 这就是槽模型:内容槽留发起人(策划权+票款),组织槽给俱乐部(只带队,不能改内容——D20
        // 「内容槽持有者独占编辑权,谈不拢就不接」);带队费由发起人在 type1 邀约里报价,俱乐部接受或拒绝(D15)。
        Long newTopicId = cmsTopicService.transferToClub(tid, memberId, cid);
        java.util.Map<String, Object> d = new java.util.HashMap<>();
        d.put("newTopicId", newTopicId);
        return AjaxResult.success(d);
    }

    /**
     * 发布/保存事务内的业务拒绝(整笔已回滚):给 4xx。客户端发布流程只把 4xx 当「确定没成功」并显示原因;
     * 5xx 一律当「结果未知」,锁住重发让人去项目列表核对 —— 业务闸的拒绝原因会被藏起来,作者无从下手。
     */
    private AjaxResult businessRefusal(ServiceException e, String message) {
        Integer code = e.getCode();
        return AjaxResult.error(code != null && code >= 400 && code < 500 ? code : HttpStatus.BAD_REQUEST, message);
    }

    private AjaxResult publicationError(RuntimeException failure, AjaxResult legacy) {
        TopicPublicationContentException.Issues data = TopicPublicationContentException.dataOf(failure);
        if (data != null) legacy.put(AjaxResult.DATA_TAG, data);
        return legacy;
    }

    private AjaxResult authorAttributeError(RuntimeException error) {
        if (!(error instanceof ServiceException)) return null;
        ServiceException service = (ServiceException) error;
        String code = service.getDetailMessage();
        if (code == null || !(code.startsWith("ATTRIBUTE_") || code.startsWith("STATE_KEY_"))) return null;
        int status = service.getCode() == null ? 400 : service.getCode();
        org.springframework.web.context.request.RequestAttributes attributes =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes) {
            jakarta.servlet.http.HttpServletResponse response =
                    ((org.springframework.web.context.request.ServletRequestAttributes) attributes).getResponse();
            if (response != null) response.setStatus(status);
        }
        return new AjaxResult(status,
                UserSafeMessage.of(service.getMessage(), "属性配置未保存"),
                java.util.Collections.singletonMap("errorCode", code));
    }

    public AjaxResult publishDraft(String id, String scope) {
        return publishDraft(id, scope, null, null, null, null);
    }

    @Operation(summary = "发布草稿")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    @PostMapping("/publish-draft")
    public AjaxResult publishDraft(String id, String scope, Long configVersion, String registryVersion,
                                   String publishRequestId, String expectedContentSha256) {
        try { return publishDraftBound(id, scope, configVersion, registryVersion, publishRequestId, expectedContentSha256); }
        catch (TopicPublicationContentException | TopicPublicationContentException.InvalidArgument error) {
            return publicationError(error, AjaxResult.error(UserSafeMessage.of(error.getMessage())));
        }
        catch (ServiceException error) {
            AjaxResult attributeError = authorAttributeError(error);
            if (attributeError != null) return attributeError;
            throw error;
        }
        catch (IllegalArgumentException refusal) {
            // 发布内容闸(如通用运行规则对某一站的要求)的拒绝:整笔已回滚,给 4xx 和原因(指名哪一站),
            // 不让它落到通用 500 分支被客户端当成「网络中断,结果不明」(第十三轮 m2)。
            logger.warn("发布被内容闸拒绝 topic={}: {}", id, refusal.getMessage());
            return AjaxResult.error(HttpStatus.BAD_REQUEST, UserSafeMessage.of(refusal.getMessage(), "发布内容未通过检查，请检查章节和站点设置"));
        }
    }

    private AjaxResult publishDraftBound(String id, String scope, Long configVersion, String registryVersion,
                                         String publishRequestId, String expectedContentSha256) {
        Long cid = Convert.toLong(id, 0L);
        if (cid < 1) {
            return error("参数不合规");
        }
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        final Long principalMemberId = memberId;
        try {
            memberId = resolveProjectOwnerMemberId(memberId, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(cid);
        if (topic == null) {
            return error("主题不存在");
        }
        if (OwnershipUtils.notOwner(topic.getMemberId(), memberId)) {
            return error("无权操作该主题");
        }
        // F1:已完成的发布意图重试先收口。发布成功后 is_draft 已被清掉,超时重发在草稿闸会假失败,
        // 且不能再占额度/重开审核。同作者 + 同 publishRequestId + 同 payload 才命中;其余继续原流程。
        if (publishRequestId != null && !publishRequestId.trim().isEmpty()) {
            TopicBundleSubmitResultVO replayed;
            try {
                replayed = cmsTopicService.replayPublishedRevision(cid, memberId, publishRequestId, expectedContentSha256);
            } catch (ServiceException conflict) {
                // 同号异 payload:409,不覆盖历史,也不假装成功。
                return businessRefusal(conflict, conflict.getMessage());
            }
            if (replayed != null) {
                return isSimplePublishMode(topic.getPublishMode())
                        ? success("已发布")            // 简易版沿用原有标量回执
                        : AjaxResult.success("已发布", replayed);
            }
        }
        // 门禁:必须当前确为草稿,防 rejected(审核未通过)/已上架 主题借道发布绕过审核
        Integer isDraft = cmsTopicService.selectTopicIsDraft(cid);
        if ((isDraft == null || isDraft != 1) && !cmsTopicService.canResubmitSupplyReview(cid, memberId)) {
            return error("该主题不是草稿，无法发布");
        }
        // check-1104 j5 B1:草稿发布口按库内节点实际绑定的节点玩法再过一次文字闸(玩法可能在主题存草稿后才改)。
        java.util.List<JourneyReviewTexts.Entry> templateTexts = persistedBoundTemplateReviewTexts(cid);
        TopicTextSafety.Verdict templateVerdict = TopicTextSafety.check(contentSafetyGate, memberId,
                resolveOpenid(principalMemberId), cid, null, templateTexts);
        if (templateVerdict.isBlocked()) {
            return AjaxResult.error(HttpStatus.BAD_REQUEST, templateVerdict.getBlockMessage());
        }
        TopicCreateDTO publication = new TopicCreateDTO();
        publication.setSelfPlay(topic.getSelfPlay());
        publication.setSelfPlayPrice(topic.getSelfPlayPrice());
        java.util.List<com.chengyinhub.business.domain.dto.ActivityTicketRequest> persistedTickets =
                new java.util.ArrayList<>();
        for (OmsTicket ticket : selectTopicTickets(cid)) {
            com.chengyinhub.business.domain.dto.ActivityTicketRequest persistedTicket =
                    new com.chengyinhub.business.domain.dto.ActivityTicketRequest();
            persistedTicket.setPrice(ticket.getPrice());
            persistedTickets.add(persistedTicket);
        }
        publication.setTickets(persistedTickets);
        if (requiresPublisherIdentity(publication)) {
            String identityErr = memberIdentityService.checkRegistered(memberId);
            if (identityErr != null) {
                return error(identityErr);
            }
        }
        // 简易版免审直发会立即占在架额度:锁会员 → 复查在架数 → publishDraft,同事务原子,防并发绕过上限
        //(publishDraft 内部再以 WHERE is_draft=1 竞态安全兜底防重复发布);超限时 guard 抛 ServiceException。
        // 专业版由 service 同事务校验在架额度、发布内容并保留后台审核任务。
        if (isSimplePublishMode(topic.getPublishMode())) {
            final Long fMemberId = memberId;
            final Long fCid = cid;
            Boolean ok = quotaService.runWithThemeOnlineGuard(fMemberId, fCid,
                    () -> configVersion == null && registryVersion == null
                            ? cmsTopicService.publishDraft(fCid, fMemberId, publishRequestId)
                            : cmsTopicService.publishDraftWithBinding(fCid, fMemberId, configVersion, registryVersion,
                                    publishRequestId) != null);
            boolean held = ok != null && ok && templateVerdict.isFlagged()
                    && flagForSpotCheck(cid, memberId, templateVerdict.getFlagReason(), recheckText(null, templateTexts),
                        topic.getImgUrl(), topic.getImgArr());
            return (ok != null && ok) ? success(held ? "已提交审核" : "已发布") : error("该主题不是草稿，无法发布");
        }
        com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO result =
                configVersion == null && registryVersion == null
                        ? cmsTopicService.publishDraftWithReview(cid, memberId, publishRequestId)
                        : cmsTopicService.publishDraftWithBinding(cid, memberId, configVersion, registryVersion,
                                publishRequestId);
        if (result == null) {
            return error("该主题不是草稿，无法发布");
        }
        if (result.isPublished() && templateVerdict.isFlagged()
                && flagForSpotCheck(cid, memberId, templateVerdict.getFlagReason(), recheckText(null, templateTexts),
                    topic.getImgUrl(), topic.getImgArr())) {
            result.markHeldForReview();
        }
        return AjaxResult.success(result.isPublished() ? "已发布" : "已提交审核", result);
    }

    @Operation(summary = "Beta 转正式(作者本人;仅 beta_flag=1 的主题可转)")
    @PostMapping("/beta/graduate")
    @Parameters({
            @Parameter(name = "topicId", description = "主题id")
    })
    public AjaxResult betaGraduate(String topicId, String scope) {
        Long cid = Convert.toLong(topicId, 0L);
        if (cid < 1) {
            return error("参数不合规");
        }
        Long memberId = 0L;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
        }
        if (memberId == null || memberId <= 0) {
            return error("请先登录");
        }
        try {
            memberId = resolveProjectOwnerMemberId(memberId, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(cid);
        if (topic == null) {
            return error("主题不存在");
        }
        // 归属校验:仅主题作者本人可把 Beta 主题转正式(IDOR)
        if (OwnershipUtils.notOwner(topic.getMemberId(), memberId)) {
            return error("仅主题作者可转正式");
        }
        // ponytail: V1 作者自决转正,后续如需「N 次完赛才可转正」在此加闸
        // 竞态安全:WHERE beta_flag=1 才清,重复点击/并发只成功一次
        if (cmsTopicService.graduateBeta(cid) <= 0) {
            return error("该主题不在 Beta 试玩期");
        }
        return success("已转为正式主题");
    }

    @Operation(summary = "主题点赞")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    @PostMapping("/like")
    @Parameters({
            @Parameter(name = "id", description = "主题ID"),
            @Parameter(name = "type", description = "类型 1点赞")
    })
    public AjaxResult topicLike(String id, String type) {
        Long memberId = getAppUserId();
        Long topicId = Convert.toLong(id, 0L);
        Integer desired = type == null || type.trim().isEmpty() ? 1 : Convert.toInt(type, -1);
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (topicId < 1 || (desired != 0 && desired != 1)) return error("参数不合规");
        if (desired == 1) {
            CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
            if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() != 0)) {
                return error("主题不存在");
            }
        }
        cmsTopicLikeService.setLiked(memberId, topicId, desired == 1);
        return success(desired == 1 ? "点赞成功" : "取消点赞成功");
    }


    /**
     * 商家在自由探索主题中选择承接章节。章节清单必须经服务层按商家品类、主题上架状态、
     * 招募开关、锁价和容量过滤，不能复用普通主题详情里的原始章节字段自行推断。
     */
    @Operation(summary = "商家可申请的自由探索章节")
    @PostMapping("/merchant-recruitment-chapters")
    public AjaxResult merchantRecruitmentChapters(String id, String scope) {
        Long principalMemberId;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (principalMemberId == null || principalMemberId <= 0) {
            return error("请先登录");
        }
        Long topicId = Convert.toLong(id, 0L);
        if (topicId <= 0) {
            return error("参数不合规");
        }
        MmsMerchant merchant;
        try {
            if (MerchantOperationScope.isMerchant(scope)) {
                MerchantAccessContext access = merchantAccessService.require(
                        principalMemberId, MerchantPermission.COOP_MANAGE);
                merchant = mmsMerchantService.selectMmsMerchantById(access.getMerchantId());
            } else {
                if (!capabilityService.hasCapability(principalMemberId, MemberCapability.CAP_MERCHANT)) {
                    return error("仅商家可查看章节承接");
                }
                merchant = mmsMerchantService.selectMmsMerchantByMemberId(principalMemberId);
            }
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        if (merchant == null || merchant.getCategoryId() == null || merchant.getCategoryId() <= 0) {
            return error("请先选择行业类型"); // M-16:与入驻、门店信息同一个词
        }

        TopicTemplateDetailVO detail = cmsTopicService.buildTopicTemplateDetail(
                topicId, true, merchant.getCategoryId());
        if (detail == null) {
            return error("主题不存在");
        }
        if (!Integer.valueOf(ProductType.FREE_EXPLORE).equals(detail.getProductType())) {
            return error("仅自由探索主题支持章节承接");
        }
        List<TopicTemplateChapterVO> eligibleChapters = new ArrayList<>();
        if (detail.getChapters() != null) {
            for (TopicTemplateChapterVO chapter : detail.getChapters()) {
                if (chapter != null && chapter.getRecruitStatus() != null
                        && Boolean.TRUE.equals(chapter.getRecruitStatus().getIsOpen())) {
                    eligibleChapters.add(chapter);
                }
            }
        }
        AjaxResult result = success(eligibleChapters);
        // 2026-11-03 业主:零章节 ≠ 章节都没开。前者商家只带玩法也能报名(服务端建章),
        // 后者按主办方的招商走,弹层出关闭空态。前端据这两个字段分开两种空。
        CmsTopicChapter chapterProbe = new CmsTopicChapter();
        chapterProbe.setTopicId(topicId);
        List<CmsTopicChapter> allChapters = cmsTopicChapterService.selectCmsTopicChapterList(chapterProbe);
        int chapterCount = allChapters == null ? 0 : allChapters.size();
        result.put("chapterCount", chapterCount);
        result.put("emptyTopicJoinable", chapterCount == 0
                && com.chengyinhub.business.support.EmptyTopicChapterPolicy.blocker(
                        cmsTopicService.selectCmsTopicById(topicId), merchant.getMemberId(),
                        merchant.getCategoryId(), com.chengyinhub.common.utils.DateUtils.getNowDate()) == null);
        return result;
    }

    /**
     * 主办方开关某一章的**商家承接**(cms_topic_chapter.recruit_enabled)。
     *
     * <p>稿 468:1126「章节控制 → 开放报名」那一行。2026-09-10 用户确认这一行说的是
     * 「开放商家承接这一章」,不是玩家买票 —— 玩家票在本仓是主题级(cms_registration 不带
     * chapter_id),章节层根本没有售票这回事。
     *
     * <p>★ 本端点只负责**鉴权 + 转调**,一条规则都不自己写:招商开关有一整套既有约束
     * (合法值、已有申请/供给时不许关、条款档与门槛值、容量、父主题上架与软删),
     * 全在 {@link ICmsTopicChapterService#updateCmsTopicChapter} 里。
     * 在这儿复制一份判断 = 两套规则迟早分叉,而分叉的那一侧就是绕过闸的入口。
     *
     * <p>⚠️ patch 只带 id 与 recruitEnabled:mapper 是 {@code <if test="x != null">} 的部分更新,
     * 多带一个字段就会把它一起写进去。
     */
    @Operation(summary = "主办方开关章节的商家承接")
    @PostMapping("/chapter/recruit")
    @Parameters({
            @Parameter(name = "chapterId", description = "章节id"),
            @Parameter(name = "enabled", description = "1开放商家承接 0关闭"),
            @Parameter(name = "scope", description = "MERCHANT 时按商家主体解析归属")
    })
    public AjaxResult setChapterRecruit(String chapterId, Integer enabled, String scope) {
        Long principalMemberId;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (principalMemberId == null || principalMemberId <= 0) {
            return error("请先登录");
        }
        Long cid = Convert.toLong(chapterId, 0L);
        if (cid <= 0 || enabled == null || (enabled != 0 && enabled != 1)) {
            return error("参数不合规");
        }
        Long memberId;
        try {
            memberId = resolveProjectOwnerMemberId(principalMemberId, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CmsTopicChapter chapter = cmsTopicChapterService.selectCmsTopicChapterById(cid);
        if (chapter == null || chapter.getTopicId() == null) {
            return error("章节不存在");
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(chapter.getTopicId());
        if (topic == null) {
            return error("主题不存在");
        }
        if (OwnershipUtils.notOwner(topic.getMemberId(), memberId)) {
            return error("无权操作该主题");
        }
        // 结束是不可逆的:已结束的章节不能靠这条开关「复活」——
        // 否则「结束本章」就退化成了一个可以来回拨的招商开关。
        // ⚠️ 这里只是提前给一句人话,**真正的闸在 SQL 的 WHERE 里**(见下)。
        if (chapter.getFinishTime() != null) {
            return error("本章已结束，不能再改商家承接");
        }
        try {
            /* ⚠️ 不走 updateCmsTopicChapter:那条是裸 where id = #{id}。
               上面的归属判断是「先读后写」,两次请求之间归属可能已经变了;
               AGENTS.md 的已知坑写着新增写接口的 WHERE 必须带 owner。
               setChapterRecruitAsOwner 把 owner 与「未结束」一起压进 WHERE,
               返回 0 就是没写成 —— 不当成功。 */
            int rows = cmsTopicChapterService.setChapterRecruitAsOwner(cid, memberId, enabled);
            if (rows <= 0) {
                return error("没有改动，可能主题归属已变或本章已结束");
            }
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException e) {
            // 服务层的招商约束用的是 IllegalState/IllegalArgument,理由写得比「操作失败」有用得多,
            // 原样回给前端(例如「章节已有 N 条商家申请,不能关闭商家承接」)
            return error(e.getMessage());
        }
        return success(enabled == 1 ? "已开放商家承接" : "已关闭商家承接");
    }

    /**
     * 主办方在工作台改某一站的档位(2026-10-31 业主:招商在发布之后做,每一站一个档位,商家只能接受或拒绝)。
     *
     * <p>★ 本端点只负责登录 + 解析归属主体 + 转调;归属校验、改档闸(有生效供给不许改)、容量与计酬闸
     * 全在 {@link ICmsTopicChapterService#configureSupplyTermsAsOwner} 的锁内完成 —— 先读后写的归属判断会被并发绕过。
     * 主办侧只有权益 / 引流两档,俱乐部自己发的自由探索才多一档计酬(服务端判定,不信客户端)。
     */
    @Operation(summary = "主办方设置某一站的招商档位")
    @PostMapping("/chapter/supply-terms")
    @Parameters({
            @Parameter(name = "chapterId", description = "章节id"),
            @Parameter(name = "termsMode", description = "PERK 权益档 / TRAFFIC 引流档 / REVSHARE 计酬档"),
            @Parameter(name = "perkMinValue", description = "权益档门槛(元),空=不设"),
            @Parameter(name = "maxPerHeadFee", description = "计酬档每人次上限(元),计酬档必填"),
            @Parameter(name = "maxMerchant", description = "商家数上限,0=不限"),
            @Parameter(name = "scope", description = "MERCHANT 时按商家主体解析归属")
    })
    public AjaxResult setChapterSupplyTerms(String chapterId, String termsMode, BigDecimal perkMinValue,
                                            BigDecimal maxPerHeadFee, Integer maxMerchant, String scope) {
        Long principalMemberId;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (principalMemberId == null || principalMemberId <= 0) {
            return error("请先登录");
        }
        Long cid = Convert.toLong(chapterId, 0L);
        if (cid <= 0) {
            return error("参数不合规");
        }
        Long memberId;
        try {
            memberId = resolveProjectOwnerMemberId(principalMemberId, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        com.chengyinhub.business.domain.dto.ChapterSupplyTermsDTO terms = new com.chengyinhub.business.domain.dto.ChapterSupplyTermsDTO();
        terms.setTermsMode(termsMode);
        terms.setPerkMinValue(perkMinValue);
        terms.setMaxPerHeadFee(maxPerHeadFee);
        terms.setMaxMerchant(maxMerchant == null ? 0 : maxMerchant);
        try {
            cmsTopicChapterService.configureSupplyTermsAsOwner(cid, memberId, terms);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        return success("这一站的档位已保存");
    }

    /**
     * 稿 468:1126「章节控制 → 结束本章」。2026-09-10 用户定的闸:**只有发起人能点**。
     *
     * <p>发起人 = 主题归属人(cms_topic.member_id),与同组的「开放报名」同一把锁 ——
     * 那一组的标题本来就写着「只有主办方能动」。承接这一章的商家看得见这一行,
     * 但点不动:结束整章不是承接方的权限。
     *
     * <p>三件事让它不至于变成一颗危险的按钮:
     * ① **不可逆但幂等安全**:只在 finish_time 仍为 NULL 时写。再点一次原样拒掉,
     *    不覆盖已经记下的时刻 —— 结算要按那个时间对账。
     * ② **结束不动已售票**:这里只写章节的结束时刻,不碰任何报名或订单。
     *    稿上那句「已售票不受影响」是承诺,所以这条语句必须干净到能兑现它。
     * ③ patch 只带 id 与 finishTime:mapper 是部分更新,多带一个字段就会一起写库。
     */
    @Operation(summary = "发起人结束章节(不可逆)")
    @PostMapping("/chapter/finish")
    @Parameters({
            @Parameter(name = "chapterId", description = "章节id"),
            @Parameter(name = "scope", description = "MERCHANT 时按商家主体解析归属")
    })
    public AjaxResult finishChapter(String chapterId, String scope) {
        Long principalMemberId;
        try {
            principalMemberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (principalMemberId == null || principalMemberId <= 0) {
            return error("请先登录");
        }
        Long cid = Convert.toLong(chapterId, 0L);
        if (cid <= 0) {
            return error("参数不合规");
        }
        Long memberId;
        try {
            memberId = resolveProjectOwnerMemberId(principalMemberId, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CmsTopicChapter chapter = cmsTopicChapterService.selectCmsTopicChapterById(cid);
        if (chapter == null || chapter.getTopicId() == null) {
            return error("章节不存在");
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(chapter.getTopicId());
        if (topic == null) {
            return error("主题不存在");
        }
        if (OwnershipUtils.notOwner(topic.getMemberId(), memberId)) {
            return error("只有发起人能结束本章");
        }
        // 提前给一句人话;**真正的幂等闸在 SQL 的 finish_time is null 里**(见下)。
        if (chapter.getFinishTime() != null) {
            return error("本章已经结束过了");
        }
        try {
            /* ⚠️ 上面那句 if 是「先读后写」,两个并发请求都能过它,
               第二次会覆盖掉第一次记下的时刻 —— 而结算按那个时刻对账,
               覆盖之后「动作看上去成功了、对账基准悄悄变了」。
               finishChapterAsOwner 把 owner 与 finish_time is null 一起压进 WHERE,
               并发两次只能成一次;返回 0 就是没写成。 */
            int rows = cmsTopicChapterService.finishChapterAsOwner(cid, memberId);
            if (rows <= 0) {
                return error("没有改动，可能已经结束过或主题归属已变");
            }
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
        return success("本章已结束");
    }

    @Operation(summary = "主题详情--对用户显示")
    private int countJoinedShopMerchants(Long topicId) {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setStatus(1L);
        java.util.Set<Long> merchants = new java.util.HashSet<>();
        List<CmsTopicNode> nodes = cmsTopicNodeService.selectCmsTopicNodeList(probe);
        if (nodes != null) for (CmsTopicNode node : nodes) {
            // 商家节点只认审核通过的(与 selectCmsTopicNodeList 的玩家侧口径一致,这里再显式核一次)
            if (node != null && node.getMerchantMemberId() != null && Integer.valueOf(1).equals(node.getNodeAuditStatus())) {
                merchants.add(node.getMerchantMemberId());
            }
        }
        return merchants.size();
    }

    /** j2 F-06:查看者在这些(未结束、公开可见的)场次里有没有报名成功的票;游客(0)一律否。 */
    static boolean signedUpForAnySession(IViewRegistrationService registrations, List<CmsActivity> sessions, Long memberId) {
        if (registrations == null || sessions == null || memberId == null || memberId <= 0) return false;
        for (CmsActivity session : sessions) {
            if (session == null || session.getId() == null) continue;
            ViewRegistration mine = new ViewRegistration();
            mine.setOwnerType(2L);
            mine.setOwnerId(session.getId());
            mine.setMemberId(memberId);
            mine.setRegistrationStatus(2);
            List<ViewRegistration> rows = registrations.selectRegistrationSummaries(mine);
            if (rows != null && !rows.isEmpty()) return true;
        }
        return false;
    }

    @PostMapping("/info-to-user")
    @Parameters({
            @Parameter(name = "id", description = "主题id")
    })
    // owner 2026-10-24「游客可浏览，写入时弹窗登录」:主题详情/封面对游客开放只读。
    // 游客 memberId=0:可见性只走公开闸(status/user_status/圈子公开闸),本人态全部为否
    // (isSignUp/isRegistrationMerchant/isOwner=0、不下发驳回原因、多章剧情只解锁首章、不放行承接站)。
    @Anonymous
    public AjaxResult topicInfo(String id) {
        CmsTopic cmsTopic = new CmsTopic();


        Long memberId = optionalAppUserId();

        Long topicId = Convert.toLong(id, 0L);

        if (topicId > 0) {
            cmsTopic.setId(topicId);
        } else {
            return new AjaxResult(400, "参数不合规");
        }

        cmsTopic = cmsTopicService.selectCmsTopicDetailById(topicId, memberId);
        if (cmsTopic != null) {
            TopicInfoVO topicInfoVO = new TopicInfoVO();
            BeanUtils.copyBeanProp(topicInfoVO, cmsTopic);
            topicInfoVO.setCanResubmitSupplyReview(cmsTopicService.canResubmitSupplyReview(topicId, memberId));
            // R2-B10 玩家侧只读下发:归一化后的主题数值规则(保存时已归一化,这里原样透传)。
            // 未配置 = null(不下发);作者关了检定 = 下发 checkEnabled=false。新增字段,不改已有字段。
            topicInfoVO.setJourneyRules(com.chengyinhub.business.service.support.JourneyInitialState.publicRules(cmsTopic.getJourneyRulesJson()));
            // R22-SEC S1:主题级剧情配置整段不下发 —— journey_story_json 含结局条件(when)、
            // optionalClues 与 relationKey,属于剧透;阶段结算 / 结局 / 尾声由 /api/play/journey 下的接口
            // 按本局状态专门给出。作者侧的编辑回显走 topicEditDetail 的显式字段,不受影响。
            // 拍板 2026-09-16 #10:发布者已打烊时,购票入口直接提示「商家暂停营业」,不必等下单被拒。
            topicInfoVO.setMerchantClosed(
                    merchantBusinessStatusService.isTargetMerchantClosed(1, topicId));
            List<OmsTicket> listOmsTicket = selectTopicTickets(topicId);
            boolean resolveBlocks = ChapterBlockReadSupport.shouldResolveBlocks(
                    cmsTopic.getProductType(), listOmsTicket);
            boolean branchGraphTeaser = TopicRouteModePolicy.isEffectiveBranchGraph(cmsTopic, null);

            //主题下的章节  章节下节点（多个），每个节点下可以最多添加一个模版
            CmsTopicChapter cmsTopicChapter = new CmsTopicChapter();
            cmsTopicChapter.setTopicId(topicId);
            List<CmsTopicChapter> listCmsTopicChapter = cmsTopicChapterService.selectCmsTopicChapterList(cmsTopicChapter);
            int totalChapterCount = listCmsTopicChapter == null ? 0 : listCmsTopicChapter.size();
            // 本人在本主题的中标承接单。两处都要用(story 解锁 + 分支图节点放行),所以只查一次;
            // 单章且非分支图时两处都用不上,不查。
            List<CmsRegistrationMerchant> ownFulfillments =
                    (branchGraphTeaser || totalChapterCount > 1)
                            ? selectOwnFulfillments(topicId, memberId)
                            : Collections.<CmsRegistrationMerchant>emptyList();
            Set<Long> ownFulfilledNodeIds = new HashSet<>();
            for (CmsRegistrationMerchant own : ownFulfillments) {
                if (own.getNodeId() != null && own.getNodeId() > 0) ownFulfilledNodeIds.add(own.getNodeId());
            }
            // hasActiveTopicStoryAccess 查的是 cms_registration(玩家报名单)。承接商家没有报名单,
            // 这条对他恒假 —— 多章主题会把承接方锁在第 1 章,连自己那一章的剧情都读不到。
            // 承接方要照着本章剧情接待玩家,这不是剧透而是他的工作内容(2026-09-05 修)。
            boolean hasFullStoryAccess = totalChapterCount <= 1
                    || cmsRegistrationMapper.hasActiveTopicStoryAccess(topicId, memberId)
                    || !ownFulfillments.isEmpty();
            int unlockedChapterCount = hasFullStoryAccess ? totalChapterCount : 1;
            topicInfoVO.setTotalChapterCount(totalChapterCount);
            topicInfoVO.setUnlockedChapterCount(unlockedChapterCount);
            topicInfoVO.setStoryLocked(unlockedChapterCount < totalChapterCount);
            List<TopicChapterVO> chapterVOList = new ArrayList<>();
            for (int chapterIndex = 0; chapterIndex < unlockedChapterCount; chapterIndex++) {
                CmsTopicChapter chapter = listCmsTopicChapter.get(chapterIndex);
                TopicChapterVO chapterVO = new TopicChapterVO();
                BeanUtils.copyBeanProp(chapterVO, chapter);

                // 公共主题详情没有权威玩家路线局，章节只保留 teaser；节点素材和完整路线几何
                // 必须从 /api/play/nodes 的路线感知投影获取，不能在这里旁路泄露 HIDDEN 分支。
                // 2026-09-05:这道闸是**防玩家剧透**的，之前无差别打到了承接方自己 ——
                // 分支图主题的商家，点位过审了也永远看不到自己承接的那一站。现在按 nodeId 白名单
                // 放行「本人 + 本主题 + auditStatus=1(中标)」的节点，其余节点仍然一个不给。
                // ⚠️ 白名单为空 = 不是承接方，此时行为与本次修改前逐字一致，这是 fail-closed 边界。
                if (branchGraphTeaser && ownFulfilledNodeIds.isEmpty()) {
                    chapterVO.setNodes(Collections.emptyList());
                    chapterVO.setBlocks(Collections.emptyList());
                    chapterVO.setBlocksJson(null);
                    chapterVOList.add(chapterVO);
                    continue;
                }

                CmsTopicNode cmsTopicNode = new CmsTopicNode();
                cmsTopicNode.setChapterId(chapter.getId());
                // 后台「隐藏」(status=0)的节点玩家侧不下发;站数口径(locationCount / nodeCount / play 节点)同为 status=1
                cmsTopicNode.setStatus(1L);
                List<CmsTopicNode> listCmsTopicNode = cmsTopicNodeService.selectCmsTopicNodeList(cmsTopicNode);

                List<TopicNodeVO> nodeVOList = new ArrayList<>();
                for (CmsTopicNode node : listCmsTopicNode) {
                    // teaser 下只放行自己承接的那一站;非 teaser 下白名单不参与判断
                    if (branchGraphTeaser && !ownFulfilledNodeIds.contains(node.getId())) continue;
                    TopicNodeVO nodeVO = new TopicNodeVO();
                    BeanUtils.copyBeanProp(nodeVO, node);
                    if (node.getTemplateId() != null && node.getTemplateId() > 0) {
                        //查询模版
                        CmsMemberTemplate memberTemplate = memberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
                        nodeVO.setCmsMemberTemplate(com.chengyinhub.business.util.TemplateSecrets.strip(memberTemplate));

                        //查询该模版报名的商家
                        CmsRegistrationMerchant cmsRegistrationMerchant = new CmsRegistrationMerchant();
                        cmsRegistrationMerchant.setTopicId(topicId);
                        cmsRegistrationMerchant.setNodeId(node.getId());
                        cmsRegistrationMerchant.setTemplateId(node.getTemplateId());
                        cmsRegistrationMerchant.setStatus(1L);
                        cmsRegistrationMerchant.setDelFlag(0);
                        List<CmsRegistrationMerchant> listCmsRegistrationMerchant = cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(cmsRegistrationMerchant);
                        if (listCmsRegistrationMerchant.size() > 0) {
                            for (int i = 0; i < listCmsRegistrationMerchant.size(); i++) {
                                MmsMerchant mmsMerchant = new MmsMerchant();
                                mmsMerchant = mmsMerchantService.selectMmsMerchantByMemberId(listCmsRegistrationMerchant.get(i).getMemberId());
                                listCmsRegistrationMerchant.get(i).setMmsMerchant(mmsMerchant);
                                com.chengyinhub.business.util.TemplateSecrets.strip(listCmsRegistrationMerchant.get(i));
                                listCmsRegistrationMerchant.get(i).setCmsTemplateLibrary(com.chengyinhub.business.util.TemplateSecrets.strip(memberTemplateService.selectCmsMemberTemplateById(listCmsRegistrationMerchant.get(i).getTemplateId()))); // [防作弊] 主题详情/商家列表不带答案/提示
                            }
                            List<PublicMerchantRegistrationVO> publicMerchants = new ArrayList<>();
                            for (CmsRegistrationMerchant registration : listCmsRegistrationMerchant) {
                                // 商家在本节点给出的奖励券:主题介绍要说得出「满50减10」,只给 couponId 说不出
                                publicMerchants.add(PublicMerchantRegistrationVO.from(registration, resolveCoupon(registration.getCouponId())));
                            }
                            nodeVO.setRegistrationMerchantList(publicMerchants);
                        }
                    }
                    nodeVOList.add(nodeVO);
                }

                chapterVO.setNodes(nodeVOList);
                if (branchGraphTeaser) {
                    // 放行的只是「自己那一站」。章节 blocks 与完整路线几何仍然不给 ——
                    // 那两样才是 HIDDEN 分支会整条漏出去的地方。
                    chapterVO.setBlocks(Collections.emptyList());
                    chapterVO.setBlocksJson(null);
                    chapterVOList.add(chapterVO);
                    continue;
                }
                if (resolveBlocks && ChapterBlockReadSupport.isRequiredChapter(chapter)) {
                    chapterVO.setBlocks(ChapterBlockReadSupport.projectForPlayer(
                            ChapterBlockReadSupport.resolve(chapter, listCmsTopicNode),
                            java.util.Collections.<Long>emptySet()));
                }
                // 计算沿城市道路的路线坐标（未配置腾讯Key时返回null，前端用节点直连兜底）
                chapterVO.setRouteGeometry(topicRouteService.buildGeometryJson(listCmsTopicNode));
                chapterVOList.add(chapterVO);
            }

            topicInfoVO.setChaptersList(chapterVOList);
            topicInfoVO.setPlayerCount(playerNodeProgressService.countPlayersByTopicId(topicId));

            //查询主题的分类信息
            String categoryIds = cmsTopic.getCategoryIds();
            if (!StringUtils.isEmpty(categoryIds)) {
                String[] ids = categoryIds.split(",");
                List<SysCategory> listCategory = sysCategoryService.selectSysCategoryListByIds(ids);
                if (listCategory.size() > 0) {
                    topicInfoVO.setSysCategoryList(listCategory);
                }
            }

            //主题阵容
            ViewCollaborators viewCollaborators = new ViewCollaborators();
            viewCollaborators.setDataType(1L);
            viewCollaborators.setDataId(topicId);
            List<ViewCollaborators> listViewCollaborators = viewCollaboratorsService.selectViewCollaboratorsList(viewCollaborators);
            if (listViewCollaborators.size() > 0) {
                List<PublicCollaboratorVO> collaborators = new ArrayList<>();
                for (ViewCollaborators collaborator : listViewCollaborators) {
                    collaborators.add(PublicCollaboratorVO.from(collaborator));
                }
                topicInfoVO.setCollaboratorsList(collaborators);
            }


            CmsRegistrationMerchant cmsRegistrationMerchant = new CmsRegistrationMerchant();
            cmsRegistrationMerchant.setTopicId(topicId);
            cmsRegistrationMerchant.setStatus(1L);
            List<CmsRegistrationMerchant> cmsRegistrationMerchantList = cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(cmsRegistrationMerchant);
            topicInfoVO.setRegistrationMerchantCount(cmsRegistrationMerchantList.size());
            // P-21:自由探索的商家是以「承接章节的商家节点」加入的,不写 cms_registration_merchant;
            // 按已上架(status=1)且审核通过的商家节点去重计数,与玩家实际能去的店一致。
            if (Integer.valueOf(ProductType.FREE_EXPLORE).equals(cmsTopic.getProductType())) {
                topicInfoVO.setRegistrationMerchantCount(countJoinedShopMerchants(topicId));
            }

            //主题评价
            ViewComment viewComment = new ViewComment();
            viewComment.setOwnerType(1L);
            viewComment.setOwnerId(topicId);
            viewComment.setStatus(1);
            viewComment.setDelFlag(0);

            List<ViewComment> list = commentService.selectViewCommentList(viewComment);
            topicInfoVO.setCommentCount(list.size());

            UmsComment umsComment = new UmsComment();
            umsComment.setOwnerType(1L);
            umsComment.setOwnerId(topicId);
            umsComment.setStatus(1);
            umsComment.setDelFlag(0);

            Double rating = umsCommentService.selectAvgRatingByCondition(umsComment);
            topicInfoVO.setAverageRating(rating);
            topicInfoVO.setRatingDistribution(commentService.summarizeRatingDistribution(list));

            //主题评价取5条
            if (list.size() > 0) {
                List<ViewComment> commentList = new ArrayList<>();
                for (int i = 0; i < Math.min(5, list.size()); i++) {
                    commentList.add(list.get(i));
                }
                topicInfoVO.setCommentList(commentList);
            }

            //主题票务
            if (listOmsTicket.size() > 0) {
                List<PublicTicketVO> publicTickets = new ArrayList<>();
                for (int i = 0; i < listOmsTicket.size(); i++) {
                    ViewRegistration viewRegistration = new ViewRegistration();
                    viewRegistration.setTicketId(listOmsTicket.get(i).getId());
                    viewRegistration.setRegistrationStatus(2);
                    List<ViewRegistration> viewRegistrationList = viewRegistrationService.selectRegistrationSummaries(viewRegistration);
                    publicTickets.add(PublicTicketVO.fromWithoutRegistrationIdentities(
                            listOmsTicket.get(i), viewRegistrationList));
                }
                if (Integer.valueOf(ProductType.FREE_EXPLORE).equals(cmsTopic.getProductType())) {
                    omsTicketService.applyActivityShellRemaining(topicId, publicTickets);
                }
                topicInfoVO.setOmsTicketList(publicTickets);
            }

            // 回填当前用户主题级报名状态(ownerType=1 已废弃,仅兼容历史报名);新报名一律走活动层 activityList
            ViewRegistration myTopicSign = new ViewRegistration();
            myTopicSign.setOwnerType(1L);
            myTopicSign.setOwnerId(topicId);
            myTopicSign.setMemberId(memberId);
            myTopicSign.setRegistrationStatus(2);
            List<ViewRegistration> myTopicSignList = viewRegistrationService.selectRegistrationSummaries(myTopicSign);
            topicInfoVO.setIsSignUp(myTopicSignList != null && !myTopicSignList.isEmpty() ? 1 : 0);


            // 发布者只下发 id/昵称/头像白名单，避免 UmsMember 新增字段自动进入公开响应。
            UmsMember publisher = umsMemberService.selectUmsMemberById(topicInfoVO.getMemberId());
            topicInfoVO.setUmsMember(PublicMemberSummaryVO.from(publisher));


            //查询是否报名
            CmsRegistrationMerchant registrationMerchant = new CmsRegistrationMerchant();
            registrationMerchant.setTopicId(topicId);
            registrationMerchant.setMemberId(memberId);
            List<CmsRegistrationMerchant> joinList = cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(registrationMerchant);
            topicInfoVO.setIsRegistrationMerchant(0);
            if (joinList.size() > 0) {
                topicInfoVO.setIsRegistrationMerchant(1);
            }

            // 该主题已上架的可参加活动(用户在活动层报名/游玩,替代已废弃的主题级直接报名)
            CmsActivity activityProbe = new CmsActivity();
            activityProbe.setTopicId(topicId);
            // 原先这里还设了 status/publishStatus/delFlag —— 公开 Mapper 把这三道写死在 SQL、
            // 压根不读调用方传的值,所以那三行是看着像闸的空操作,删掉免得误导下一个人。
            // TOPIC_SHELF:社群活动要出(俱乐部承接的场次正是要在主题下被看到),但只出未结束的
            // —— 已结束的不叫「可参加」。
            List<CmsActivity> visibleActivities =
                    cmsActivityService.selectPublicCmsActivityList(activityProbe, PublicActivityScope.TOPIC_SHELF);
            List<PublicActivitySummaryVO> activitySummaries = new ArrayList<>();
            if (visibleActivities != null) {
                Map<Long, String> clubNames = new HashMap<>();
                for (CmsActivity activity : visibleActivities) {
                    PublicActivitySummaryVO summary = PublicActivitySummaryVO.from(activity, cmsTopic);
                    // P-19:场次卡写执行俱乐部(不是主题的内容俱乐部),与报名门卡指向的俱乐部一致
                    Long clubId = activity.getClubId();
                    if (!summary.isSelfHostShell() && clubId != null && clubId > 0) {
                        summary.setClubName(clubNames.computeIfAbsent(clubId, cid -> {
                            Club club = clubService.selectClubById(cid);
                            return club == null ? "" : club.getName();
                        }));
                    }
                    activitySummaries.add(summary);
                }
            }
            topicInfoVO.setActivityList(activitySummaries);
            // j2 F-06:已付本主题某个未结束场次(活动层报名)的玩家同样是「已报名」,主按钮去票夹进场,不再推「随时开玩」。
            if (!Integer.valueOf(1).equals(topicInfoVO.getIsSignUp())
                    && signedUpForAnySession(viewRegistrationService, visibleActivities, memberId)) {
                topicInfoVO.setIsSignUp(1);
            }

            // 双署名 + 移交入口:回填承接俱乐部名(clubId→clubName),标记当前查看者是否为主题创建者(移交按钮门控)
            if (topicInfoVO.getClubId() != null && topicInfoVO.getClubId() > 0) {
                Club club = clubService.selectClubById(topicInfoVO.getClubId());
                if (club != null) {
                    topicInfoVO.setClubName(club.getName());
                }
            }
            topicInfoVO.setIsOwner(OwnershipUtils.isOwner(topicInfoVO.getMemberId(), memberId) ? 1 : 0);
            // 驳回原因只在「本人是主题创建者」时下发:审核态走 status(0/1/2),原因只能给作者看。
            if (Integer.valueOf(1).equals(topicInfoVO.getIsOwner())) {
                topicInfoVO.setRejectReason(cmsTopic.getReason());
            }

            // [P0-16] ③ 的权益容量与覆盖率 —— **仅 ③ 计算**,①② 留 null(前端据此不显示该区块)。
            //
            // ⚠️ 展示口径,不是购票硬校验:这里走普通读(快照),而购票的真防线只有
            //    reserveEntitlements 抛不抛(当前读)。两者差别就是超卖与不超卖 ——
            //    **绝不能**因为这里显示「还有 3 张」就在下单口跳过 reserveEntitlements。
            //
            // ⚠️ 为什么「缺口硬停售」不在这里也不在下单口再加一道闸:
            //    coverage < 1.0 ⟺ supply < held ⟺ capacity < 0,而 reserveEntitlements 已在
            //    capacity <= 0 时硬拒 ⇒ **新单的停售已经被覆盖了**,再加一道是重复的闸。
            //    缺口档位在这里的价值是**让前端/运营看见**(以及 topicHasCoverageGap 打的 warn 日志),
            //    真正的缺口**处置**(替代商家 / 整单全额退)是独立工作流,不在本次范围。
            if (cmsTopic.getProductType() != null
                    && cmsTopic.getProductType() == ProductType.FREE_EXPLORE) {
                topicInfoVO.setPerkSellableCapacity(perkLedgerService.topicSellableCapacity(topicId));
                topicInfoVO.setPerkCoverageStatus(perkLedgerService.topicCoverageStatus(topicId));
            }

            return success(topicInfoVO);

        } else {
            return new AjaxResult(404, "主题活动不存在");
        }
    }


    /** 游客(无登录态)= 0L,只用于公开只读投影;写接口一律继续用 getAppUserId() 硬要求登录。 */
    private Long optionalAppUserId() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0 ? 0L : id;
        } catch (Exception notLoggedIn) {
            return 0L;
        }
    }

    @Operation(summary = "M7 主题XP预算总览(publishCheck奖励总览/budget-bar)")
    @PostMapping("/xp-budget")
    @Parameters({@Parameter(name = "topic_id", description = "主题ID")})
    public AjaxResult xpBudget(String topic_id) {
        Long topicId = Convert.toLong(topic_id, 0L);
        if (topicId <= 0) {
            return error("参数不合规");
        }
        return success(xpBudgetService.computeBudget(topicId));
    }

    @Operation(summary = "主题列表--对用户展示/我发布的主题")
    @PostMapping("/list")
    @Parameters({
            @Parameter(name = "is_my", description = "0所有 1我发布的"),
            @Parameter(name = "keyword", description = "搜索关键词"),
            @Parameter(name = "category_id", description = "分类ID"),
            @Parameter(name = "is_recommend", description = "1推荐"),
            @Parameter(name = "self_play", description = "M2 1=只看开放自玩票的主题(首页'随时开走'/自玩路线列表用)"),
            @Parameter(name = "invite_target", description = "仅 is_my=1:club/merchant,回填 inviteWindowOpen(能否发起协作邀约)")
    })
    public AjaxResult topicList(String is_my, String keyword, String category_id,
                                String is_recommend, String self_play, String scope,
                                String product_type, String city_keyword, String invite_target) {

        int isMy = Convert.toInt(is_my, 0);

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
            logger.warn("topicList 获取用户ID异常", e);
        }

//        UmsMember umsMember = new UmsMember();
//        umsMember = umsMemberService.selectUmsMemberById(member_id);
//        if(umsMember==null){
//            return error("用户不存在");
//        }
//        if(umsMember.getStatus()!=1){
//            return error("会员已禁用，请联系管理员");
//        }
//        if(umsMember.getUserType()!=2){
//            return error("商家才能报名");
//        }

        CmsTopic cmsTopic = new CmsTopic();
        if (StringUtils.isNotEmpty(keyword)) {
            cmsTopic.setName(keyword);
        }
        try {
            cmsTopic.setCategoryIds(com.chengyinhub.business.service.DiscoveryFilterContract.categoryId(category_id));
            cmsTopic.setCityKeyword(com.chengyinhub.business.service.DiscoveryFilterContract.cityKeyword(city_keyword));
            cmsTopic.setProductType(com.chengyinhub.business.service.DiscoveryFilterContract.productType(product_type));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        try {
            cmsTopic.setIsRecommend(com.chengyinhub.business.service.DiscoveryFilterContract.only(
                    "is_recommend", is_recommend));
            cmsTopic.setSelfPlay(com.chengyinhub.business.service.DiscoveryFilterContract.only(
                    "self_play", self_play));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        // M2 自玩路线列表:self_play=1 只返回开放自玩票的主题(首页'随时开走'入口/票夹)
        // self_play 已由冻结 query contract 统一解析；保留 1 的兼容输入。


        // 查询我发布的主题复用既有 owner seam；其他分支走 SQL 固定的公开 Gate。
        boolean publicList = isMy != 1;
        if (isMy == 1) {
            if (member_id > 0) {
                try {
                    // 邀请俱乐部选题与合作写闸同口径；不改变普通项目管理权限。
                    if (MerchantOperationScope.isMerchant(scope) && "club".equals(invite_target)) {
                        cmsTopic.setMemberId(merchantAccessService.require(
                                member_id, MerchantPermission.COOP_MANAGE).getOwnerMemberId());
                    } else {
                        cmsTopic.setMemberId(resolveProjectOwnerMemberId(member_id, scope));
                    }
                } catch (ServiceException e) {
                    return new AjaxResult(e.getCode(), e.getMessage());
                }
            } else {
                // 第十二轮 k9(j6 N8):会话过期时答业务 401,小程序请求层才会静默重登并重试这一次;
                // 原来答 500「请先登录」,「我的」页这一块空到下次进来。
                return AjaxResult.error(401, "请先登录");
            }
        }
        // 公开分支原先设 isNew=1(→ SQL 加 end_date > now())。那道闸对主题是错的:
        // 活动是场次、有起止天经地义;主题是内容/路线,常青。实测代价 ——
        // 全库 end_date 在未来的主题为 0 条(12 条为 NULL、7 条已过期),
        // 于是「推荐主题」和底部「主题」页签长期静默全空,开发版还被 mock 盖住看不出来。
        // 上下架改由 status / user_status / lifecycle 表达,不再借 end_date。


        cmsTopic.setDelFlag(0);
        cmsTopic.setActiveClubOnly(true);
        // 俱乐部把主题设成「只对成员可见」后,它照样是条公开主题,会从这个口出去。
        // 闸在 SQL 里(clubMemberOnlyGate),这里只负责告诉它「看的人是谁」;没登录就是 null。
        cmsTopic.setViewerMemberId(member_id > 0 ? member_id : null);

        List<CmsTopic> list;
        startPage();
        list = publicList
                ? cmsTopicService.selectPublicCmsTopicList(cmsTopic)
                : cmsTopicService.selectCmsTopicList(cmsTopic);
        // 「来源」栏：一次批量查生效 CONTENT 槽，不进下面的逐条循环（那里已经是 N+1 了，别再加一条）。
        topicContentPublisherProjectionService.project(list);
        // Batch decoration — collect IDs, query once per type, map back
        List<Long> topicIds = new ArrayList<>();
        Set<Long> publisherIds = new LinkedHashSet<>();
        Set<String> allCategoryIds = new LinkedHashSet<>();
        for (CmsTopic topic : list) {
            if (topic.getId() != null) topicIds.add(topic.getId());
            if (topic.getMemberId() != null) publisherIds.add(topic.getMemberId());
            if (topic.getCategoryIds() != null) {
                for (String part : topic.getCategoryIds().split(",")) {
                    if (!part.trim().isEmpty()) allCategoryIds.add(part.trim());
                }
            }
        }

        Map<Long, Boolean> likeMap = new HashMap<>();
        Map<Long, Boolean> followMap = new HashMap<>();
        if (member_id > 0 && !topicIds.isEmpty()) {
            for (CmsTopicLike like : cmsTopicLikeMapper.selectCmsTopicLikesByMemberAndDataIds(member_id, topicIds)) {
                if (like.getDataId() != null) likeMap.put(like.getDataId(), true);
            }
            if (!publisherIds.isEmpty()) {
                for (UmsMemberFollow f : umsMemberFollowMapper.selectUmsMemberFollowsByMemberAndFollowMemberIds(member_id, new ArrayList<>(publisherIds))) {
                    if (f.getFollowMemberId() != null) followMap.put(f.getFollowMemberId(), true);
                }
            }
        }

        Map<Long, BigDecimal> salesMap = new HashMap<>();
        if (!topicIds.isEmpty()) {
            for (Map<String, Object> row : cmsRegistrationStatisticsMapper.statisticsSalesAmoutByOwners(1, topicIds)) {
                Long oid = (Long) row.get("owner_id");
                if (oid != null) salesMap.put(oid, (BigDecimal) row.getOrDefault("totalTicketPrice", BigDecimal.ZERO));
            }
        }

        // ROUND(AVG(rating),1) 经 JDBC 回来是 BigDecimal，强转 Double 必炸；收敛到 RatingSummaryAggregator。
        RatingSummaryAggregator ratingSummary = topicIds.isEmpty()
                ? RatingSummaryAggregator.from(null)
                : RatingSummaryAggregator.from(umsCommentMapper.selectRatingSummaryByOwners(1L, topicIds));
        Map<Long, Double> ratingMap = ratingSummary.average;
        Map<Long, Integer> ratingTotalMap = ratingSummary.total;

        Map<Long, BigDecimal> priceMap = new HashMap<>();
        if (!topicIds.isEmpty()) {
            for (Map<String, Object> row : omsTicketMapper.selectMinTicketPrices(1L, topicIds)) {
                Long oid = (Long) row.get("owner_id");
                if (oid != null) priceMap.put(oid, (BigDecimal) row.getOrDefault("minTicketPrice", BigDecimal.ZERO));
            }
        }

        Map<Long, Long> browseMap = new HashMap<>();
        if (!topicIds.isEmpty()) {
            for (Map<String, Object> row : tmsBrowseRecordMapper.countBrowseByTypeAndTargets(1, topicIds)) {
                Long tid = (Long) row.get("target_id");
                if (tid != null) browseMap.put(tid, (Long) row.getOrDefault("browse_count", 0L));
            }
        }

        Map<String, SysCategory> categoryMap = new HashMap<>();
        if (!allCategoryIds.isEmpty()) {
            for (SysCategory cat : sysCategoryService.selectSysCategoryListByIds(allCategoryIds.toArray(new String[0]))) {
                if (cat != null && cat.getId() != null) categoryMap.put(String.valueOf(cat.getId()), cat);
            }
        }

        for (CmsTopic topic : list) {
            topic.setIsLike(member_id > 0 && likeMap.containsKey(topic.getId()) ? 1 : 0);
            topic.setIsFollowTheUser(member_id > 0 && followMap.containsKey(topic.getMemberId()) ? 1 : 0);
            topic.setSalesAmout(salesMap.getOrDefault(topic.getId(), BigDecimal.ZERO));
            topic.setMinAmout(priceMap.getOrDefault(topic.getId(), BigDecimal.ZERO));
            if (topic.getCategoryIds() != null) {
                List<SysCategory> cats = new ArrayList<>();
                for (String id : topic.getCategoryIds().split(",")) {
                    SysCategory cat = categoryMap.get(id.trim());
                    if (cat != null) cats.add(cat);
                }
                if (!cats.isEmpty()) topic.setSysCategoryList(cats);
            }
            Double r = ratingMap.get(topic.getId());
            if (r != null && r > 0) {
                topic.setRating(r);
                topic.setRatingTotal(ratingTotalMap.getOrDefault(topic.getId(), 0));
            } else {
                topic.setRating(0.0);
                topic.setRatingTotal(0);
            }
            topic.setViewCount(browseMap.getOrDefault(topic.getId(), 0L));
        }
        // CU-M-29:发起协作邀约页按目标类型标出「不在招商期」的主题,别让商家选完填完才被写闸拒。
        // 判据与 CoopInviteServiceImpl.assertInviteWindowOpen 同一份:俱乐部走 ClubCoopWindowPolicy
        // (免费开放池主题豁免),商家走 RecruitmentWindowPolicy。只有「我的主题」才算,公开列表不碰。
        if (!publicList && ("club".equals(invite_target) || "merchant".equals(invite_target))) {
            Date now = com.chengyinhub.common.utils.DateUtils.getNowDate();
            for (CmsTopic topic : list) {
                boolean open;
                if ("club".equals(invite_target)) {
                    open = clubCoopWindowPolicy.isOpen(topic, now);
                } else {
                    try {
                        RecruitmentWindowPolicy.assertOpen(topic.getLifecycle(), topic.getStatus(), topic.getUserStatus(), topic.getRecruitDeadline(), now);
                        open = true;
                    } catch (IllegalStateException closed) {
                        open = false;
                    }
                }
                topic.setInviteWindowOpen(open);
            }
        }
        // F-W5-03:「我的」页项目预览读这条列表,草稿(is_draft=1)要与 /api/project/my 同一判据标出来,
        // 否则草稿按档期被写成「进行中」。只富化本人列表,公开列表不出这个字段。
        if (!publicList && cmsTopic.getMemberId() != null && !list.isEmpty()) {
            Set<Long> draftIds = new java.util.HashSet<>(cmsTopicService.selectDraftTopicIds(cmsTopic.getMemberId()));
            for (CmsTopic topic : list) {
                topic.setIsDraft(topic.getId() != null && draftIds.contains(topic.getId()));
            }
        }
        TableDataInfo data = getDataTable(list);
        if (publicList) {
            List<PublicTopicListVO> publicRows = new ArrayList<>();
            for (CmsTopic topic : list) {
                publicRows.add(PublicTopicListVO.from(topic));
            }
            data.setRows(publicRows);
        }
        return AjaxResult.success(data);
    }


    @Operation(summary = "主题列表--我收藏")
    @PostMapping("/like_list")
    public AjaxResult likeList() {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
            logger.warn("likeList 获取用户ID异常", e);
        }

        CmsTopic cmsTopic = new CmsTopic();
        cmsTopic.setIsLike(1);
        cmsTopic.setIsLikeMemberId(member_id);
        cmsTopic.setViewerMemberId(member_id > 0 ? member_id : null);

        List<CmsTopic> list;
        startPage();
        list = cmsTopicService.selectPublicCmsTopicList(cmsTopic);
        // 「来源」栏：收藏列表与 /list 同口径，缺了会让同一张卡在两个入口显示不一致。
        topicContentPublisherProjectionService.project(list);
        // Batch decoration — collect IDs, query once per type, map back
        List<Long> topicIds = new ArrayList<>();
        Set<Long> publisherIds = new LinkedHashSet<>();
        Set<String> allCategoryIds = new LinkedHashSet<>();
        for (CmsTopic topic : list) {
            if (topic.getId() != null) topicIds.add(topic.getId());
            if (topic.getMemberId() != null) publisherIds.add(topic.getMemberId());
            if (topic.getCategoryIds() != null) {
                for (String part : topic.getCategoryIds().split(",")) {
                    if (!part.trim().isEmpty()) allCategoryIds.add(part.trim());
                }
            }
        }

        Map<Long, Boolean> likeMap = new HashMap<>();
        Map<Long, Boolean> followMap = new HashMap<>();
        if (member_id > 0 && !topicIds.isEmpty()) {
            for (CmsTopicLike like : cmsTopicLikeMapper.selectCmsTopicLikesByMemberAndDataIds(member_id, topicIds)) {
                if (like.getDataId() != null) likeMap.put(like.getDataId(), true);
            }
            if (!publisherIds.isEmpty()) {
                for (UmsMemberFollow f : umsMemberFollowMapper.selectUmsMemberFollowsByMemberAndFollowMemberIds(member_id, new ArrayList<>(publisherIds))) {
                    if (f.getFollowMemberId() != null) followMap.put(f.getFollowMemberId(), true);
                }
            }
        }

        Map<Long, BigDecimal> salesMap = new HashMap<>();
        if (!topicIds.isEmpty()) {
            for (Map<String, Object> row : cmsRegistrationStatisticsMapper.statisticsSalesAmoutByOwners(1, topicIds)) {
                Long oid = (Long) row.get("owner_id");
                if (oid != null) salesMap.put(oid, (BigDecimal) row.getOrDefault("totalTicketPrice", BigDecimal.ZERO));
            }
        }

        // ROUND(AVG(rating),1) 经 JDBC 回来是 BigDecimal，强转 Double 必炸；收敛到 RatingSummaryAggregator。
        RatingSummaryAggregator ratingSummary = topicIds.isEmpty()
                ? RatingSummaryAggregator.from(null)
                : RatingSummaryAggregator.from(umsCommentMapper.selectRatingSummaryByOwners(1L, topicIds));
        Map<Long, Double> ratingMap = ratingSummary.average;
        Map<Long, Integer> ratingTotalMap = ratingSummary.total;

        Map<Long, BigDecimal> priceMap = new HashMap<>();
        if (!topicIds.isEmpty()) {
            for (Map<String, Object> row : omsTicketMapper.selectMinTicketPrices(1L, topicIds)) {
                Long oid = (Long) row.get("owner_id");
                if (oid != null) priceMap.put(oid, (BigDecimal) row.getOrDefault("minTicketPrice", BigDecimal.ZERO));
            }
        }

        Map<Long, Long> browseMap = new HashMap<>();
        if (!topicIds.isEmpty()) {
            for (Map<String, Object> row : tmsBrowseRecordMapper.countBrowseByTypeAndTargets(1, topicIds)) {
                Long tid = (Long) row.get("target_id");
                if (tid != null) browseMap.put(tid, (Long) row.getOrDefault("browse_count", 0L));
            }
        }

        Map<String, SysCategory> categoryMap = new HashMap<>();
        if (!allCategoryIds.isEmpty()) {
            for (SysCategory cat : sysCategoryService.selectSysCategoryListByIds(allCategoryIds.toArray(new String[0]))) {
                if (cat != null && cat.getId() != null) categoryMap.put(String.valueOf(cat.getId()), cat);
            }
        }

        for (CmsTopic topic : list) {
            topic.setIsLike(member_id > 0 && likeMap.containsKey(topic.getId()) ? 1 : 0);
            topic.setIsFollowTheUser(member_id > 0 && followMap.containsKey(topic.getMemberId()) ? 1 : 0);
            topic.setSalesAmout(salesMap.getOrDefault(topic.getId(), BigDecimal.ZERO));
            topic.setMinAmout(priceMap.getOrDefault(topic.getId(), BigDecimal.ZERO));
            if (topic.getCategoryIds() != null) {
                List<SysCategory> cats = new ArrayList<>();
                for (String id : topic.getCategoryIds().split(",")) {
                    SysCategory cat = categoryMap.get(id.trim());
                    if (cat != null) cats.add(cat);
                }
                if (!cats.isEmpty()) topic.setSysCategoryList(cats);
            }
            Double r = ratingMap.get(topic.getId());
            if (r != null && r > 0) {
                topic.setRating(r);
                topic.setRatingTotal(ratingTotalMap.getOrDefault(topic.getId(), 0));
            } else {
                topic.setRating(0.0);
                topic.setRatingTotal(0);
            }
            topic.setViewCount(browseMap.getOrDefault(topic.getId(), 0L));
        }
        TableDataInfo data = getDataTable(list);
        List<PublicTopicListVO> publicRows = new ArrayList<>();
        for (CmsTopic topic : list) {
            publicRows.add(PublicTopicListVO.from(topic));
        }
        data.setRows(publicRows);
        return AjaxResult.success(data);
    }

    /** 保留 Java 调用方的既有接口；HTTP 入口使用上面的扩展签名。 */
    public AjaxResult topicList(String is_my, String keyword, String category_id,
                                String is_recommend, String self_play, String scope) {
        return topicList(is_my, keyword, category_id, is_recommend, self_play, scope, null, null, null);
    }

    @Operation(summary = "主题列表-商家可报名主题")
    @PostMapping("/list-to-merchant")
    public AjaxResult topicListToMerchant() {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }

        UmsMember umsMember = new UmsMember();
        umsMember = umsMemberService.selectUmsMemberById(member_id);
        if (umsMember == null) {
            return error("用户不存在");
        }
        if (!Long.valueOf(1L).equals(umsMember.getStatus())) {
            return error("会员已禁用，请联系管理员");
        }
        if (!IRoleService.ROLE_MERCHANT.equals(umsMember.getRole())) {
            return error("商家才能报名");
        }

        CmsTopic cmsTopic = new CmsTopic();
        cmsTopic.setDelFlag(0);
        //后台审核状态：0->待审核；1->审核通过；2->审核失败；
        cmsTopic.setStatus(1);

        //商家端状态：0未上架  1已上架
        cmsTopic.setMerchantStatus(1);
        cmsTopic.setActiveClubOnly(true);

        List<CmsTopic> list;
        startPage();

        list = cmsTopicService.selectCmsTopicList(cmsTopic);
        // 招募广场卡片的「来源」:一次批量查生效 CONTENT 槽,不进下面的逐条循环(那里已经是 N+1)。
        // ⚠️ 必须在下面包装成 TopicInfoVO 之前调用 —— 包装是按字段名复制的快照,
        // 之后再填 CmsTopic 不会反映到已经产出的 VO 上。
        topicContentPublisherProjectionService.project(list);
        // Batch decoration — collect IDs, query once per type, map back
        List<Long> topicIds = new ArrayList<>();
        Set<Long> publisherIds = new LinkedHashSet<>();
        Set<String> allCategoryIds = new LinkedHashSet<>();
        for (CmsTopic topic : list) {
            if (topic.getId() != null) topicIds.add(topic.getId());
            if (topic.getMemberId() != null) publisherIds.add(topic.getMemberId());
            if (topic.getCategoryIds() != null) {
                for (String part : topic.getCategoryIds().split(",")) {
                    if (!part.trim().isEmpty()) allCategoryIds.add(part.trim());
                }
            }
        }

        Map<Long, Boolean> likeMap = new HashMap<>();
        Map<Long, Boolean> followMap = new HashMap<>();
        if (member_id > 0 && !topicIds.isEmpty()) {
            for (CmsTopicLike like : cmsTopicLikeMapper.selectCmsTopicLikesByMemberAndDataIds(member_id, topicIds)) {
                if (like.getDataId() != null) likeMap.put(like.getDataId(), true);
            }
            if (!publisherIds.isEmpty()) {
                for (UmsMemberFollow f : umsMemberFollowMapper.selectUmsMemberFollowsByMemberAndFollowMemberIds(member_id, new ArrayList<>(publisherIds))) {
                    if (f.getFollowMemberId() != null) followMap.put(f.getFollowMemberId(), true);
                }
            }
        }

        // ROUND(AVG(rating),1) 经 JDBC 回来是 BigDecimal，强转 Double 必炸；收敛到 RatingSummaryAggregator。
        RatingSummaryAggregator ratingSummary = topicIds.isEmpty()
                ? RatingSummaryAggregator.from(null)
                : RatingSummaryAggregator.from(umsCommentMapper.selectRatingSummaryByOwners(1L, topicIds));
        Map<Long, Double> ratingMap = ratingSummary.average;
        Map<Long, Integer> ratingTotalMap = ratingSummary.total;

        Map<Long, BigDecimal> priceMap = new HashMap<>();
        if (!topicIds.isEmpty()) {
            for (Map<String, Object> row : omsTicketMapper.selectMinTicketPrices(1L, topicIds)) {
                Long oid = (Long) row.get("owner_id");
                if (oid != null) priceMap.put(oid, (BigDecimal) row.getOrDefault("minTicketPrice", BigDecimal.ZERO));
            }
        }

        Map<String, SysCategory> categoryMap = new HashMap<>();
        if (!allCategoryIds.isEmpty()) {
            for (SysCategory cat : sysCategoryService.selectSysCategoryListByIds(allCategoryIds.toArray(new String[0]))) {
                if (cat != null && cat.getId() != null) categoryMap.put(String.valueOf(cat.getId()), cat);
            }
        }

        // 各主题的章节先攒起来,循环结束后一次性批量统计名额(禁止逐主题查)。
        java.util.Map<Long, List<CmsTopicChapter>> chaptersByTopicId = new java.util.LinkedHashMap<>();
        for (CmsTopic topic : list) {
            topic.setIsLike(member_id > 0 && likeMap.containsKey(topic.getId()) ? 1 : 0);
            topic.setIsFollowTheUser(member_id > 0 && followMap.containsKey(topic.getMemberId()) ? 1 : 0);
            topic.setMinAmout(priceMap.getOrDefault(topic.getId(), BigDecimal.ZERO));
            if (topic.getCategoryIds() != null) {
                List<SysCategory> cats = new ArrayList<>();
                for (String id : topic.getCategoryIds().split(",")) {
                    SysCategory cat = categoryMap.get(id.trim());
                    if (cat != null) cats.add(cat);
                }
                if (!cats.isEmpty()) topic.setSysCategoryList(cats);
            }
            Double r = ratingMap.get(topic.getId());
            if (r != null && r > 0) {
                topic.setRating(r);
                topic.setRatingTotal(ratingTotalMap.getOrDefault(topic.getId(), 0));
            } else {
                topic.setRating(0.0);
                topic.setRatingTotal(0);
            }

            //主题下的章节  章节下节点（多个），每个节点下可以最多添加一个模版
            CmsTopicChapter cmsTopicChapter = new CmsTopicChapter();
            cmsTopicChapter.setTopicId(topic.getId());
            List<CmsTopicChapter> listCmsTopicChapter = cmsTopicChapterService.selectCmsTopicChapterList(cmsTopicChapter);
            List<TopicChapterVO> chapterVOList = new ArrayList<>();
            for (CmsTopicChapter chapter : listCmsTopicChapter) {
                TopicChapterVO chapterVO = new TopicChapterVO();
                BeanUtils.copyBeanProp(chapterVO, chapter);

                CmsTopicNode cmsTopicNode = new CmsTopicNode();
                cmsTopicNode.setChapterId(chapter.getId());
                List<CmsTopicNode> listCmsTopicNode = cmsTopicNodeService.selectCmsTopicNodeList(cmsTopicNode);

                List<TopicNodeVO> nodeVOList = new ArrayList<>();
                for (CmsTopicNode node : listCmsTopicNode) {
                    TopicNodeVO nodeVO = new TopicNodeVO();
                    BeanUtils.copyBeanProp(nodeVO, node);
                    if (node.getTemplateId() != null && node.getTemplateId() > 0) {
                        //查询模版
                        CmsMemberTemplate memberTemplate = memberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
                        nodeVO.setCmsMemberTemplate(com.chengyinhub.business.util.TemplateSecrets.strip(memberTemplate));

                        //查询该模版报名的商家
                        CmsRegistrationMerchant cmsRegistrationMerchant = new CmsRegistrationMerchant();
                        cmsRegistrationMerchant.setTopicId(topic.getId());
                        cmsRegistrationMerchant.setNodeId(node.getId());
                        cmsRegistrationMerchant.setTemplateId(node.getTemplateId());
                        cmsRegistrationMerchant.setStatus(1L);
                        cmsRegistrationMerchant.setDelFlag(0);
                        List<CmsRegistrationMerchant> listCmsRegistrationMerchant = cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(cmsRegistrationMerchant);
                        if (listCmsRegistrationMerchant.size() > 0) {
                            for (int i = 0; i < listCmsRegistrationMerchant.size(); i++) {
                                MmsMerchant mmsMerchant = new MmsMerchant();
                                mmsMerchant = mmsMerchantService.selectMmsMerchantByMemberId(listCmsRegistrationMerchant.get(i).getMemberId());
                                listCmsRegistrationMerchant.get(i).setMmsMerchant(mmsMerchant);
                                com.chengyinhub.business.util.TemplateSecrets.strip(listCmsRegistrationMerchant.get(i));
                                listCmsRegistrationMerchant.get(i).setCmsTemplateLibrary(com.chengyinhub.business.util.TemplateSecrets.strip(memberTemplateService.selectCmsMemberTemplateById(listCmsRegistrationMerchant.get(i).getTemplateId()))); // [防作弊] 主题详情/商家列表不带答案/提示
                            }
                            List<PublicMerchantRegistrationVO> publicMerchants = new ArrayList<>();
                            for (CmsRegistrationMerchant registration : listCmsRegistrationMerchant) {
                                // 商家在本节点给出的奖励券:主题介绍要说得出「满50减10」,只给 couponId 说不出
                                publicMerchants.add(PublicMerchantRegistrationVO.from(registration, resolveCoupon(registration.getCouponId())));
                            }
                            nodeVO.setRegistrationMerchantList(publicMerchants);
                        }
                    }
                    nodeVOList.add(nodeVO);
                }

                chapterVO.setNodes(nodeVOList);
                chapterVOList.add(chapterVO);
            }

            topic.setChaptersList(chapterVOList);
            // 名额按**章节**配置,卡片按**主题**展示,收敛放在循环外做一次批量统计,见下。
            chaptersByTopicId.put(topic.getId(), listCmsTopicChapter);
        }

        // 招募广场「已满」角标。⚠️ 统计**只查一次**(整页所有章节一起),
        // 逐主题调服务会变成 N×3 次查询,列表页扛不住。
        java.util.Map<Long, Integer> maxMerchantByChapterId = new java.util.LinkedHashMap<>();
        for (List<CmsTopicChapter> topicChapters : chaptersByTopicId.values()) {
            for (CmsTopicChapter chapter : topicChapters) {
                if (chapter.getId() == null
                        || !com.chengyinhub.business.support.ChapterRecruitmentRules
                                .isEnabled(chapter.getRecruitEnabled())) {
                    continue;
                }
                // 容量越界时统计会抛异常。列表页不该因为一条脏章节整个 500 ——
                // 跳过即可,缺章会让聚合返回 null(不下发角标),比编一个数安全。
                if (!com.chengyinhub.business.support.ChapterRecruitmentRules
                        .isMaxMerchantValid(chapter.getMaxMerchant())) {
                    continue;
                }
                maxMerchantByChapterId.put(chapter.getId(), chapter.getMaxMerchant());
            }
        }
        java.util.Map<Long, com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats>
                chapterStats = merchantChapterApplicationService
                        .getRecruitmentStatsByChapters(maxMerchantByChapterId);
        for (CmsTopic topic : list) {
            topic.setRemainingMerchantCount(
                    com.chengyinhub.business.support.TopicMerchantCapacity.remainingMerchantCount(
                            chaptersByTopicId.get(topic.getId()), chapterStats));
        }

        TableDataInfo data = getDataTable(list);
        List<TopicInfoVO> publicRows = new ArrayList<>();
        for (CmsTopic topic : list) {
            TopicInfoVO view = new TopicInfoVO();
            BeanUtils.copyBeanProp(view, topic);
            view.setChaptersList(topic.getChaptersList());
            publicRows.add(view);
        }
        data.setRows(publicRows);
        return AjaxResult.success(data);
    }

    @Operation(summary = "主题详情--对商家显示")
    @PostMapping("/info-to-merchant")
    @Parameters({
            @Parameter(name = "id", description = "主题id")
    })
    public AjaxResult topicInfoToMerchant(String id) {
        CmsTopic cmsTopic = new CmsTopic();

        Long topicId = Convert.toLong(id, 0L);

        if (topicId > 0) {
            cmsTopic.setId(topicId);
        } else {
            return error("参数不合规");
        }

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }

        UmsMember umsMember = new UmsMember();
        umsMember = umsMemberService.selectUmsMemberById(member_id);
        if (umsMember == null) {
            return error("用户不存在");
        }
        if (umsMember.getStatus() != 1) {
            return error("会员已禁用，请联系管理员");
        }
        MerchantAccessContext access;
        try {
            // user_type 已不是商家身份真源；统一复用经营工作台的 mms_merchant + RBAC 权限链。
            access = merchantAccessService.resolve(member_id);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        // M-12:合作中心招商池浏览与「申请承接」同权(COOP_MANAGE);管理自己的项目才要 PROJECT_MANAGE。
        // 原先一律要 PROJECT_MANAGE,营销岗能在列表看到主题、点进详情却被拒。
        boolean canBrowsePool = access != null && access.hasPermission(MerchantPermission.COOP_MANAGE);
        boolean canManage = access != null && access.hasPermission(MerchantPermission.PROJECT_MANAGE);
        if (!canBrowsePool && !canManage) {
            return new AjaxResult(HttpStatus.FORBIDDEN, "商家身份无效或权限不足");
        }
        Long merchantOwnerMemberId = access.getOwnerMemberId();

        cmsTopic = cmsTopicService.selectMerchantVisibleCmsTopicById(topicId);
        if (cmsTopic == null && canManage) {
            cmsTopic = cmsTopicService.selectMerchantManagedCmsTopicById(topicId, merchantOwnerMemberId);
        }
        if (cmsTopic != null) {
            TopicInfoVO topicInfoVO = new TopicInfoVO();
            BeanUtils.copyBeanProp(topicInfoVO, cmsTopic);
            topicInfoVO.setCanResubmitSupplyReview(canManage && cmsTopicService.canResubmitSupplyReview(topicId, merchantOwnerMemberId));
            boolean resolveBlocks = cmsTopic.getProductType() == null
                    ? ChapterBlockReadSupport.shouldResolveBlocks(null, selectTopicTickets(topicId))
                    : ChapterBlockReadSupport.shouldResolveBlocks(cmsTopic.getProductType(), null);

            //主题下的章节  章节下节点（多个），每个节点下可以最多添加一个模版
            CmsTopicChapter cmsTopicChapter = new CmsTopicChapter();
            cmsTopicChapter.setTopicId(topicId);
            List<CmsTopicChapter> listCmsTopicChapter = cmsTopicChapterService.selectCmsTopicChapterList(cmsTopicChapter);
            List<TopicChapterVO> chapterVOList = new ArrayList<>();
            for (CmsTopicChapter chapter : listCmsTopicChapter) {
                TopicChapterVO chapterVO = new TopicChapterVO();
                BeanUtils.copyBeanProp(chapterVO, chapter);

                CmsTopicNode cmsTopicNode = new CmsTopicNode();
                cmsTopicNode.setChapterId(chapter.getId());
                List<CmsTopicNode> listCmsTopicNode = cmsTopicNodeService.selectCmsTopicNodeList(cmsTopicNode);

                List<TopicNodeVO> nodeVOList = new ArrayList<>();
                for (CmsTopicNode node : listCmsTopicNode) {
                    TopicNodeVO nodeVO = new TopicNodeVO();
                    BeanUtils.copyBeanProp(nodeVO, node);
                    if (node.getTemplateId() != null && node.getTemplateId() > 0) {
                        CmsMemberTemplate memberTemplate = memberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
                        nodeVO.setCmsMemberTemplate(com.chengyinhub.business.util.TemplateSecrets.strip(memberTemplate));
                    }
                    nodeVOList.add(nodeVO);
                }

                chapterVO.setNodes(nodeVOList);
                if (resolveBlocks && ChapterBlockReadSupport.isRequiredChapter(chapter)) {
                    chapterVO.setBlocks(ChapterBlockReadSupport.resolve(chapter, listCmsTopicNode));
                }
                // 计算沿城市道路的路线坐标（未配置腾讯Key时返回null，前端用节点直连兜底）
                chapterVO.setRouteGeometry(topicRouteService.buildGeometryJson(listCmsTopicNode));
                chapterVOList.add(chapterVO);
            }

            topicInfoVO.setChaptersList(chapterVOList);

            //查询主题的分类信息
            String categoryIds = cmsTopic.getCategoryIds();
            if (!StringUtils.isEmpty(categoryIds)) {
                String[] ids = categoryIds.split(",");
                List<SysCategory> listCategory = sysCategoryService.selectSysCategoryListByIds(ids);
                if (listCategory.size() > 0) {
                    topicInfoVO.setSysCategoryList(listCategory);
                }
            }

            //查询商家是否已报名
            CmsRegistrationMerchant cmsRegistrationMerchant = new CmsRegistrationMerchant();
            cmsRegistrationMerchant.setTopicId(topicId);
            cmsRegistrationMerchant.setMemberId(merchantOwnerMemberId);
            List<CmsRegistrationMerchant> listCmsRegistrationMerchant = cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(cmsRegistrationMerchant);
            if (listCmsRegistrationMerchant.size() > 0) {
                topicInfoVO.setIsSignUp(1);
            } else {
                topicInfoVO.setIsSignUp(0);
            }

            // 发布者只下发 id/昵称/头像白名单，避免 UmsMember 新增字段自动进入公开响应。
            UmsMember publisher = umsMemberService.selectUmsMemberById(topicInfoVO.getMemberId());
            topicInfoVO.setUmsMember(PublicMemberSummaryVO.from(publisher));


            return success(topicInfoVO);

        } else {
            return error("主题活动不存在");
        }

    }
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.text.Convert;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostComment;
import com.chengyinhub.business.domain.ClubPostLike;
import com.chengyinhub.business.domain.ClubPostRepost;
import com.chengyinhub.business.domain.ClubPendingSettlement;
import com.chengyinhub.business.domain.ClubPendingDeposit;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.ClubSessionOpenRequest;
import com.chengyinhub.business.domain.dto.ClubPostMutationRequest;
import com.chengyinhub.business.domain.dto.ClubPostUpdateResult;
import com.chengyinhub.business.domain.dto.ClubLeaderProfileRequest;
import com.chengyinhub.business.domain.vo.ClubLifecycleCommandResultVO;
import com.chengyinhub.business.domain.vo.ClubPostRepostItemVO;
import com.chengyinhub.business.domain.vo.ClubRegistrationRosterVO;
import com.chengyinhub.business.domain.vo.PublicClubVO;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.ViewRegistration;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.ClubDissolutionDepositException;
import com.chengyinhub.business.service.ClubDissolutionSettlementException;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubLifecycleCommandService;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.IClubPostCommentService;
import com.chengyinhub.business.service.IClubPostLikeService;
import com.chengyinhub.business.service.IClubPostRepostService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IViewCreativeSquareService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IClubSessionService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPuzzlePlayService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IGameSessionRuntimeService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IViewRegistrationService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.business.service.support.ClubPostReferenceGuard;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.common.utils.OwnershipUtils;
import com.chengyinhub.business.service.ICapabilityGuard;
/**
 * 俱乐部（小程序端）
 *
 * RBAC R2：申请成为俱乐部、俱乐部目录/详情/成员、加入(即时)/退出、我的俱乐部。
 */
@RestController
@Tag(name = "俱乐部")
@RequestMapping("/api/club")
public class ApiClubController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ApiClubController.class);

    @Autowired

    private ICapabilityGuard capabilityGuard;


    @Autowired
    private IClubService clubService;

    /** F0098:帖子引用「我的游玩记录」的写入资格与读取可见性。 */
    @Autowired
    private ClubPostReferenceGuard clubPostReferenceGuard;

    @Autowired
    private IClubSessionService clubSessionService;

    @Autowired
    private IClubLeaderService clubLeaderService;

    @Autowired
    private IClubMemberService clubMemberService;

    @Autowired
    private IClubLifecycleCommandService clubLifecycleCommandService;

    @Autowired
    private IClubParticipationGuard clubParticipationGuard;

    @Autowired
    private IClubAccessService clubAccessService;

    @Autowired
    private IClubRoleAssignmentService clubRoleAssignmentService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired(required = false)
    private com.chengyinhub.business.service.IPlayerProgressionService playerProgressionService;

    @Autowired
    private IRoleService roleService;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;

    @Autowired
    private IPuzzlePlayService puzzlePlayService;

    @Autowired
    private IGameSessionRuntimeService gameSessionRuntimeService;

    @Autowired
    private IClubPostService clubPostService;

    /** 帖子引用的展示字段直接复用广场那两条摘要查询,不在俱乐部这边另写一份 —— 两份迟早分叉。 */
    @Autowired
    private IViewCreativeSquareService viewCreativeSquareService;

    @Autowired
    private ICmsRegistrationService cmsRegistrationService;

    @Autowired
    private IOmsTicketService omsTicketService;

    @Autowired
    private com.chengyinhub.business.service.ICmsActivityService cmsActivityService;

    @Autowired
    private IClubPostLikeService clubPostLikeService;

    @Autowired
    private IClubPostRepostService clubPostRepostService;

    @Autowired
    private com.chengyinhub.business.service.ICoopInviteService coopInviteService;

    @Autowired
    private IClubPostCommentService clubPostCommentService;

    @Autowired
    private IMmsMerchantService mmsMerchantService;

    @Autowired
    private IAuditTaskService auditTaskService;

    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    @Autowired
    private com.chengyinhub.business.service.support.AppMediaReferenceGuard mediaReferences;

    @Autowired
    private com.chengyinhub.business.mapper.AppMediaAssetMapper leaderCertificateAssets;

    private String reviewedMedia(Long memberId, String reference, boolean multiple) {
        if (reference == null || reference.trim().isEmpty()) return reference;
        return multiple ? mediaReferences.requireAttachments(memberId, reference, true)
                : mediaReferences.requireAttachment(memberId, reference, true);
    }

    @Autowired
    private IImService imService;

    @Autowired
    private com.chengyinhub.business.mapper.GrowthBadgeMapper growthBadgeMapper;

    @Autowired
    private com.chengyinhub.business.mapper.PlayerBadgeMapper playerBadgeMapper;

    @Autowired
    private IViewRegistrationService viewRegistrationService;

    @Autowired
    private com.chengyinhub.business.service.ICapabilityService capabilityService;

    private Long currentMemberId() {
        return getAppUserId();
    }

    private Long badgeLong(Object o) { return o == null ? null : com.chengyinhub.common.core.text.Convert.toLong(o); }
    private String badgeStr(Object o) { return o == null ? null : o.toString(); }

    private boolean isMerchant(Long memberId) {
        // P3 能力模型:判商家能力读 mms_merchant 记录而非单值 role
        return capabilityService.hasCapability(memberId, MemberCapability.CAP_MERCHANT);
    }

    /** 取会员 openid(内容安全送检用) */
    private String openidOf(Long memberId) {
        UmsMember m = umsMemberService.selectUmsMemberById(memberId);
        return m == null ? null : m.getWxOpenId();
    }

    /** 内容治理权限:作者本人 / 新俱乐部访问上下文中的 club:write。 */
    private boolean canModerate(Long clubId, Long authorMemberId, Long memberId) {
        if (memberId == null) return false;
        if (authorMemberId != null && memberId.equals(authorMemberId)) return true;
        if (clubId == null) return false;
        try {
            ClubAccessContext access = clubAccessService.resolve(memberId, clubId, null);
            return access != null && memberId.equals(access.getPrincipalMemberId())
                    && clubId.equals(access.getClubId())
                    && (access.hasPermission(ClubPermission.CLUB_WRITE)
                        || access.hasPermission(ClubPermission.CONTENT_MANAGE));
        } catch (ServiceException e) {
            return false;
        }
    }

    /** 成员管理统一走 Service 判据：owner / 在册 legacy role=1 / 当前 club 作用域 member:manage。 */
    private boolean canManageMembers(Club club, Long memberId) {
        if (club == null || club.getId() == null || club.getMemberId() == null || memberId == null) {
            return false;
        }
        if (memberId.equals(club.getMemberId())) {
            return true;
        }
        if (clubMemberService.canGovernClub(club, memberId)) {
            return true;
        }
        try {
            ClubAccessContext access = clubAccessService.resolve(memberId, club.getId(), null);
            return access != null && memberId.equals(access.getPrincipalMemberId())
                    && club.getId().equals(access.getClubId())
                    && OwnershipUtils.isOwner(club.getMemberId(), access.getOwnerMemberId())
                    && access.hasPermission(ClubPermission.MEMBER_MANAGE);
        } catch (ServiceException e) {
            return false;
        }
    }

    /** 目标是否持有任何活跃角色委派(俱乐部或活动作用域)。判不清就按持有算:宁可挡住一次移除,也不放低权剥高权。 */
    private boolean holdsActiveRoleAssignment(Long clubId, Long targetMemberId) {
        try {
            ClubAccessContext access = clubAccessService.resolveWithEventAccesses(targetMemberId, clubId);
            if (access == null) {
                return false;
            }
            for (String roleCode : access.getRoleCodes()) {
                if (!ClubRoleCode.CLUB_MEMBER.equals(roleCode)
                        && !ClubRoleCode.CLUB_LEGACY_ADMIN.equals(roleCode)) {
                    return true;
                }
            }
            return !access.getEventAccesses().isEmpty();
        } catch (ServiceException e) {
            return true;
        }
    }

    /** 成员数统一口径(方案B):判据收在 {@link IClubMemberService#countEffectiveMembers},全端一处。 */
    private int effectiveMemberCount(Club club) {
        return clubMemberService.countEffectiveMembers(club);
    }

    private static final int MAX_CLUBS_PER_LEADER = 2;
    private static final int PLAYER_BADGE_SOURCE_CLUB = 6;

    /** 旧 apply 入口兜底:从 club 表单种子化最小主理人档案(完整能力档案走 /become-leader) */
    private ClubLeader leaderSeedFromClub(Club body) {
        ClubLeader p = new ClubLeader();
        p.setLeaderName(body.getLeaderName());
        p.setPhone(body.getPhone());
        p.setExperience(body.getExperience());
        p.setMaxEventSize(body.getMaxEventSize());
        p.setAvgEventSize(body.getAvgEventSize());
        p.setCanDesignRoute(body.getCanDesignRoute());
        p.setCanDesignTask(body.getCanDesignTask());
        p.setCanNpc(body.getCanNpc());
        p.setCanMerchantCoop(body.getCanMerchantCoop());
        p.setHasGuideCert(body.getHasGuideCert());
        p.setCertImages(body.getCertImages());
        return p;
    }

    /** 小程序可编辑字段显式投影；请求对象不得直接进入持久化实体。 */
    private ClubLeader leaderProfilePatch(ClubLeaderProfileRequest body) {
        ClubLeader profile = new ClubLeader();
        profile.setLeaderName(body.getLeaderName());
        profile.setPhone(body.getPhone());
        profile.setIdentity(body.getIdentity());
        profile.setCoFounders(body.getCoFounders());
        profile.setHasExperience(body.getHasExperience());
        profile.setExperience(body.getExperience());
        profile.setMaxEventSize(body.getMaxEventSize());
        profile.setAvgEventSize(body.getAvgEventSize());
        profile.setCanDesignRoute(body.getCanDesignRoute());
        profile.setCanDesignTask(body.getCanDesignTask());
        profile.setCanNpc(body.getCanNpc());
        profile.setCanMerchantCoop(body.getCanMerchantCoop());
        profile.setHasGuideCert(body.getHasGuideCert());
        profile.setCertImages(body.getCertImages());
        return profile;
    }

    /** 把多个图片字段(单图/CSV)合成一段 ; 分隔串供 submitMediaCheck(空字段忽略) */
    private String mediaCsv(String... imgs) {
        StringBuilder sb = new StringBuilder();
        for (String s : imgs) if (s != null && s.trim().length() > 0) { if (sb.length() > 0) sb.append(";"); sb.append(s); }
        return sb.toString();
    }

    /** 建团(status=1 填完即有),受每人 MAX_CLUBS_PER_LEADER 配额约束 */
    private AjaxResult createClubForMember(Long memberId, Club body, ClubLeader onboardingProfile) {
        // X03 能力位:权益清单里的「建群」= canCreateGroup。本产品的「群」就是俱乐部自带的群聊,
        // 所以闸落在建俱乐部这一步。放在这个共用 seam 上,/club/create 与 /club/become-leader
        // 两条路都覆盖 —— 只拦其中一条等于没拦。
        String capErr = capabilityGuard.checkCreateGroup(memberId);
        if (capErr != null) return error(capErr);
        // 内容安全:俱乐部名/简介/关键词等文本命中违规即拒(俱乐部资料 status=1 填完即在 /club/list、/club/detail 公开)
        String secMsg = wxContentSecurityService.checkText(
                IWxContentSecurityService.joinText(body.getName(), body.getDescription(), body.getKeywords(),
                        body.getLeaderName(), body.getJoinReason(), body.getStyle(), body.getAddress()),
                openidOf(memberId));
        if (secMsg != null) return error(secMsg);
        Club club = new Club();
        club.setMemberId(memberId);
        club.setName(body.getName());
        club.setLogo(reviewedMedia(memberId, body.getLogo(), false));
        club.setCover(reviewedMedia(memberId, body.getCover(), false));
        club.setDescription(body.getDescription());
        club.setClubType(body.getClubType());
        club.setClubTypes(body.getClubTypes());
        club.setActivityPrefs(body.getActivityPrefs());
        club.setCity(body.getCity());
        club.setKeywords(body.getKeywords());
        club.setStyle(body.getStyle());
        club.setJoinReason(body.getJoinReason());
        club.setCaseImages(reviewedMedia(memberId, body.getCaseImages(), true));
        club.setCategoryId(body.getCategoryId());
        club.setAddress(body.getAddress());
        club.setLeaderName(body.getLeaderName());
        club.setPhone(body.getPhone());
        club.setStatus(1L);   // 填完即有,不走待审
        club.setMemberCount(0);
        club.setDelFlag(0);
        int clubCount;
        try {
            clubCount = onboardingProfile == null
                    ? clubService.insertClubWithinOwnerQuota(club, MAX_CLUBS_PER_LEADER)
                    : clubService.onboardLeaderAndInsertClubWithinIdentityMutex(
                            memberId, onboardingProfile, club, MAX_CLUBS_PER_LEADER);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        // logo/封面/案例图异步内容安全送检,违规经回调自动清除(BIZ_TYPE_CLUB_PROFILE)
        try {
            wxContentSecurityService.submitMediaCheck(IWxContentSecurityService.BIZ_TYPE_CLUB_PROFILE, club.getId(),
                    mediaCsv(club.getLogo(), club.getCover(), club.getCaseImages()), openidOf(memberId));
        } catch (Exception ignore) {}
        Map<String, Object> d = new HashMap<>();
        d.put("clubId", club.getId());
        d.put("clubCount", clubCount);
        d.put("status", club.getStatus());
        return AjaxResult.success(Long.valueOf(0L).equals(club.getStatus()) ? "已重新提交,等待审核" : "已创建", d);
    }

    /** 列表/详情统一补齐入会方式与主理人展示信息。 */
    private void attachClubDisplay(Club club) {
        if (club == null) return;
        boolean joinPolicySupported = clubService.isJoinPolicySupported();
        club.setJoinPolicySupported(joinPolicySupported);
        club.setJoinPolicy(joinPolicySupported ? clubService.selectJoinPolicy(club.getId()) : 0);
        if (club.getMemberId() == null) return;
        ClubLeader leader = clubLeaderService.selectClubLeaderByMemberId(club.getMemberId());
        if (leader != null) {
            club.setLevel(leader.getLevel());
            club.setTags(leader.getTags());
            // CU-C-98:小程序建团入口的 payload 里没有 leaderName ⇒ club.leader_name 一直是 NULL,
            // 详情页「主理人」只能显示占位符 —— 而主理人档案里本来就有名字。
            // 不写库,只在读出时兜底(与 level/tags 同一处),与同一屏的成员名单(合成 owner 行)口径一致。
            if (StringUtils.isEmpty(club.getLeaderName())) {
                club.setLeaderName(leader.getLeaderName());
            }
        }
    }

    private void attachTopicCounts(List<Club> clubs) {
        if (clubs == null || clubs.isEmpty()) return;
        List<Long> clubIds = new java.util.ArrayList<>();
        for (Club club : clubs) if (club != null && club.getId() != null) clubIds.add(club.getId());
        Map<Long, Integer> counts = cmsTopicService.selectUpcomingTopicCounts(clubIds);
        for (Club club : clubs) {
            if (club != null) club.setTopicCount(counts.getOrDefault(club.getId(), 0));
        }
    }

    @Operation(summary = "成为主理人并创建俱乐部(填完即有,兼容旧入口)")
    @PostMapping("/apply")
    public AjaxResult apply(@Validated @RequestBody Club body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (StringUtils.isEmpty(body.getName())) return error("请填写俱乐部名称");
        return createClubForMember(memberId, body, leaderSeedFromClub(body));
    }

    @Operation(summary = "成为俱乐部主理人(填完即有·身份档案)")
    @PostMapping("/become-leader")
    public AjaxResult becomeLeader(@RequestBody(required = false) ClubLeaderProfileRequest body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null) return error("请填写主理人资料");
        // 与小程序 canNext 同一组必填:前端可被绕过,服务端缺一项就拒,不建档、不发角色
        if (StringUtils.isBlank(body.getLeaderName())) return error("请填写主理人姓名");
        if (StringUtils.isBlank(body.getPhone())) return error("请填写联系电话");
        if (StringUtils.isBlank(body.getExperience())) return error("请选择组织经验");
        // F-L5C-02:主理人名展示在俱乐部列表/详情,身份与经历给成员看,写入前送检。
        String leaderRisk = wxContentSecurityService.checkText(IWxContentSecurityService.joinText(
                body.getLeaderName(), body.getIdentity(), body.getCoFounders(), body.getExperience()), openidOf(memberId));
        if (leaderRisk != null) return error(leaderRisk);
        // 创建身份不代表收费；实名在收费发布入口检查。
        try {
            ClubLeader profile = leaderProfilePatch(body);
            String certImages = reviewedMedia(memberId, body.getCertImages(), true);
            // 公开媒体可被复用，但资质证明必须由申请人本人上传。
            if (StringUtils.isNotBlank(certImages)) {
                for (String reference : certImages.split(";")) {
                    Map<String, Object> certificate = leaderCertificateAssets.select(
                            Long.valueOf(reference.substring("media-asset:".length())));
                    if (certificate == null || !"APP".equals(certificate.get("ownerType"))
                            || !(certificate.get("memberId") instanceof Number)
                            || memberId.longValue() != ((Number) certificate.get("memberId")).longValue()) {
                        throw new ServiceException("资质图片不属于当前账号，请重新上传", 403);
                    }
                }
            }
            profile.setCertImages(certImages);
            ClubLeader stored = clubService.saveLeaderProfileWithinIdentityMutex(memberId, profile);
            return AjaxResult.success("已成为主理人", stored);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "创建我的俱乐部(需已是主理人,最多2个)")
    @PostMapping("/create")
    public AjaxResult create(@RequestBody Club body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (StringUtils.isEmpty(body.getName())) return error("请填写俱乐部名称");
        return createClubForMember(memberId, body, null);
    }

    @Operation(summary = "我的俱乐部申请状态")
    @PostMapping("/my-apply")
    public AjaxResult myApply() {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        List<Club> clubs = clubService.selectClubsByMemberId(memberId);   // 最新的一条在前
        return AjaxResult.success(clubs == null || clubs.isEmpty() ? null : clubs.get(0));
    }

    @Operation(summary = "更新我的俱乐部资料(白名单字段)")
    @PostMapping("/update-mine")
    public AjaxResult updateMine(@RequestBody Club body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        // 多俱乐部:带 id 则改指定团(校验归属);否则兼容旧单团前端取第一个
        Club club;
        if (body.getId() != null) {
            club = clubService.selectClubById(body.getId());
            // 2026-08-26:展示资料放开给管理员(canGovernClub = owner 或 role=1)。
            // 真闸在 clubService.updateOwnedClubProfile 的行锁内再算一次,这里只是早退。
            if (club == null || !clubMemberService.canGovernClub(club, memberId)) {
                return error("无权修改该俱乐部");
            }
        } else {
            club = clubService.selectClubByMemberId(memberId);
            if (club == null) return error("你还不是俱乐部");
        }
        boolean actorIsOwner = OwnershipUtils.isOwner(club.getMemberId(), memberId);
        // ★ 经营配置(成员优先报名 / 保留名额 / 折扣价)决定开卖时票怎么分、钱怎么收,只有主理人能改。
        //   管理员带着这几个字段来必须**报错**,不能悄悄忽略 —— 悄悄忽略会让他以为改成功了。
        if (!actorIsOwner && Boolean.TRUE.equals(body.getOperationConfigUpdated())) {
            return error("只有主理人可以修改会员经营配置");
        }
        if (!actorIsOwner && body.getJoinPolicy() != null) {
            return error("只有主理人可以修改加入方式");
        }
        if (StringUtils.isEmpty(body.getName())) return error("请填写俱乐部名称");
        String operationConfigError = validateOperationConfig(body);
        if (operationConfigError != null) return error(operationConfigError);
        if (body.getJoinPolicy() != null && body.getJoinPolicy() != 0 && body.getJoinPolicy() != 1) {
            return error("加入方式仅支持公开或需审批");
        }
        // 内容安全:改资料同样过文本检测,命中违规即拒
        String secMsg = wxContentSecurityService.checkText(
                IWxContentSecurityService.joinText(body.getName(), body.getDescription(), body.getKeywords(),
                        body.getLeaderName(), body.getJoinReason(), body.getStyle(), body.getAddress()),
                openidOf(memberId));
        if (secMsg != null) return error(secMsg);
        // 仅白名单字段可改;status / memberId / memberCount / level 不允许前端篡改
        club.setName(body.getName());
        club.setLogo(reviewedMedia(memberId, body.getLogo(), false));
        club.setCover(reviewedMedia(memberId, body.getCover(), false));
        club.setDescription(body.getDescription());
        club.setClubType(body.getClubType());
        club.setClubTypes(body.getClubTypes());
        club.setActivityPrefs(body.getActivityPrefs());
        club.setCity(body.getCity());
        club.setKeywords(body.getKeywords());
        club.setStyle(body.getStyle());
        club.setCategoryId(body.getCategoryId());
        club.setAddress(body.getAddress());
        club.setLeaderName(body.getLeaderName());
        club.setPhone(body.getPhone());
        if (Boolean.TRUE.equals(body.getOperationConfigUpdated())) {
            club.setOperationConfigUpdated(true);
            club.setPrioritySignupEnabled(body.getPrioritySignupEnabled());
            club.setMemberDiscountPrice(body.getMemberDiscountPrice());
            club.setMemberReservedQuota(body.getMemberReservedQuota());
        }
        boolean joinPolicySupported;
        try {
            joinPolicySupported = clubService.updateOwnedClubProfile(club, memberId, body.getJoinPolicy());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
        try {
            wxContentSecurityService.submitMediaCheck(IWxContentSecurityService.BIZ_TYPE_CLUB_PROFILE, club.getId(),
                    mediaCsv(club.getLogo(), club.getCover(), club.getCaseImages()), openidOf(memberId));
        } catch (Exception ignore) {}
        Map<String, Object> saved = new HashMap<>();
        saved.put("joinPolicySupported", joinPolicySupported);
        saved.put("joinPolicy", joinPolicySupported ? clubService.selectJoinPolicy(club.getId()) : 0);
        return AjaxResult.success("已保存", saved);
    }

    private String validateOperationConfig(Club club) {
        if (!Boolean.TRUE.equals(club.getOperationConfigUpdated())) return null;
        Integer priority = club.getPrioritySignupEnabled();
        if (priority == null || (priority != 0 && priority != 1)) return "成员优先报名仅支持开启或关闭";
        if (club.getMemberReservedQuota() == null || club.getMemberReservedQuota() < 0) return "成员保留名额不能小于 0";
        if (club.getMemberDiscountPrice() != null
                && club.getMemberDiscountPrice().compareTo(java.math.BigDecimal.ZERO) < 0) return "成员优惠价不能小于 0";
        if (club.getMemberDiscountPrice() != null && club.getMemberDiscountPrice().scale() > 2) {
            return "成员优惠价最多保留两位小数";
        }
        return null;
    }

    @Operation(summary = "俱乐部目录(已通过)")
    @PostMapping("/list")
    // owner 2026-10-24「游客可浏览」:目录对游客只读开放。游客只看得到公开可见的团(canViewClub),
    // 不打 isJoined/isOwner,出参走 PublicClubVO 白名单。
    @Anonymous
    public AjaxResult list(@RequestBody(required = false) Club query) {
        if (query == null) query = new Club();
        query.setStatus(1L); // 仅展示审核通过
        List<Club> clubs = clubService.selectClubList(query);
        clubs = visibleToViewer(clubs);

        for (Club c : clubs) attachClubDisplay(c); // 带出主理人等级/标签(继承展示)

        // 标记当前用户是否已加入
        for (Club c : clubs) c.setMemberCount(effectiveMemberCount(c)); // 口径统一(含主理人)
        try {
            Long memberId = currentMemberId();
            List<Long> joined = clubMemberService.selectClubIdsByMember(memberId);
            for (Club c : clubs) {
                c.setIsJoined(joined.contains(c.getId()));
                c.setIsOwner(OwnershipUtils.isOwner(c.getMemberId(), memberId));
            }
        } catch (Exception ignore) { /* 未登录则不标记 */ }

        List<PublicClubVO> result = new ArrayList<>();
        for (Club club : clubs) result.add(PublicClubVO.from(club));
        return AjaxResult.success(result);
    }

    @Operation(summary = "俱乐部详情")
    @PostMapping("/detail")
    // owner 2026-10-24「游客可浏览」:公开资料对游客只读开放。非公开团对游客仍「只对成员开放」;
    // 游客的 isOwner/isJoined/viewerIsAdmin=false、myJoinReviewReason=""、待审数=0,出参走 PublicClubVO 白名单。
    @Anonymous
    public AjaxResult detail(@RequestBody Club body) {
        if (body.getId() == null) return error("缺少俱乐部ID");
        Club club = clubService.selectClubById(body.getId());
        if (club == null) return error("俱乐部不存在");
        // 稿 M「俱乐部公开可见」关掉之后:非成员不但搜不到,连详情也打不开。
        // ⚠️ 只藏列表不挡详情等于没挡 —— 链接分享出去照样能进。
        if (!canViewClub(club)) return error("这个俱乐部只对成员开放");
        attachClubDisplay(club); // 带出主理人等级/标签
        club.setMemberCount(effectiveMemberCount(club)); // 口径统一(含主理人)
        // 该俱乐部办的经典定向团数(按 club_id)
        CmsTopic tProbe = new CmsTopic();
        tProbe.setClubId(club.getId());
        tProbe.setDelFlag(0);
        club.setTopicCount(cmsTopicService.selectCmsTopicList(tProbe).size());
        club.setPendingJoinRequestCount(0);
        int nonOwnerMemberCount = clubMemberService.countByClub(club.getId());
        ClubMember ownerMembership = clubMemberService.selectMembershipRecord(club.getId(), club.getMemberId());
        if (ownerMembership != null && Integer.valueOf(1).equals(ownerMembership.getStatus())) {
            nonOwnerMemberCount = Math.max(0, nonOwnerMemberCount - 1);
        }
        club.setNonOwnerMemberCount(nonOwnerMemberCount);
        club.setViewerIsAdmin(false);
        club.setViewerIdentityUnavailable(false);
        club.setIsOwner(false);
        club.setMyJoinReviewReason("");
        try {
            Long memberId = currentMemberId();
            boolean owner = OwnershipUtils.isOwner(club.getMemberId(), memberId);
            club.setIsOwner(owner);
            try {
                ClubMember membership = clubMemberService.selectMembershipRecord(club.getId(), memberId);
                boolean activeMember = membership != null && Integer.valueOf(1).equals(membership.getStatus());
                club.setIsJoined(activeMember);
                club.setMyJoinStatus(membership == null ? null : membership.getStatus());
                club.setMyJoinReviewReason(membership != null && Integer.valueOf(2).equals(membership.getStatus())
                        ? membership.getReviewReason() : "");
                club.setViewerIsAdmin(activeMember && Integer.valueOf(1).equals(membership.getRole()));
                // 待审条数跟着「谁能审」走,不是跟着「谁是主理人」走:管理员点进去办得了,
                // 却永远看到 0 条,等于这个红点对他不存在。
                // ⚠️ 被委派 club:member:approve 但不是 role=1 的人仍会看到 0 ——
                //    那要把 canApproveMembers 提成公开判据,不在本次范围内。
                if (owner || Boolean.TRUE.equals(club.getViewerIsAdmin())) {
                    club.setPendingJoinRequestCount(clubMemberService.countPendingJoinRequests(club.getId()));
                }
            } catch (RuntimeException e) {
                club.setViewerIdentityUnavailable(true);
                log.warn("俱乐部访问者身份加载失败，工具保持关闭，clubId={}, memberId={}",
                        club.getId(), memberId, e);
            }
        } catch (Exception ignore) { }
        // 2026-10-22 业主:入会欢迎语(主理人自定),新成员加入后的欢迎弹层最上面显示。单独读,读不到当没写。
        club.setWelcomeNote(clubService.selectWelcomeNote(club.getId()));
        return AjaxResult.success(PublicClubVO.from(club));
    }

    @Operation(summary = "俱乐部成员列表")
    @PostMapping("/members")
    public AjaxResult members(@RequestBody ClubMember body) {
        if (body.getClubId() == null) return error("缺少俱乐部ID");
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Club club = clubService.selectClubById(body.getClubId());
        if (club == null) return error("俱乐部不存在");
        boolean isOwner = OwnershipUtils.isOwner(club.getMemberId(), memberId);
        boolean isMember = clubMemberService.selectByClubAndMember(body.getClubId(), memberId) != null;
        if (!isOwner && !isMember && !canBrowsePublicRoster(club, memberId)) {
            return error(isPublicJoinClub(club) ? "仅俱乐部成员可查看成员名单" : "通过审核后可看成员名单");
        }
        ClubMember q = new ClubMember();
        q.setClubId(body.getClubId());
        q.setStatus(1);
        List<ClubMember> list = clubMemberService.selectClubMemberList(q);
        for (ClubMember cm : list) {
            cm.setIsOwner(OwnershipUtils.isOwner(club.getMemberId(), cm.getMemberId()));
        }
        // 主理人(owner)若不在 club_member 里,合成一条置顶(方案B union owner)
        if (club.getMemberId() != null) {
            boolean ownerIn = false;
            for (ClubMember cm : list) {
                if (OwnershipUtils.isOwner(club.getMemberId(), cm.getMemberId())) { ownerIn = true; break; }
            }
            if (!ownerIn) {
                ClubMember owner = new ClubMember();
                owner.setClubId(club.getId());
                owner.setMemberId(club.getMemberId());
                owner.setRole(2);
                owner.setStatus(1);
                owner.setIsOwner(true);
                UmsMember om = umsMemberService.selectUmsMemberById(club.getMemberId());
                if (om != null) {
                    owner.setNickname(om.getNickname());
                    owner.setAvatar(om.getAvatar());
                    // RUN-13(拍板 2026-09-19 第4条):名单里主理人合成行与 mapper join 同口径 ——
                    // 等级取 player_growth(无行兜底 1),不再把旧 ums_member.level_id 塞进名单。
                    Integer ownerLv = playerProgressionService == null ? null
                            : playerProgressionService.levelsByMemberIds(
                                    java.util.Collections.singletonList(club.getMemberId()))
                                    .get(club.getMemberId());
                    owner.setLevelId(ownerLv == null ? om.getLevelId() : ownerLv.longValue());
                }
                list.add(0, owner);
            }
        }
        return AjaxResult.success(list);
    }

    @Operation(summary = "移除俱乐部成员(创建者)")
    @PostMapping("/remove-member")
    public AjaxResult removeMember(@RequestBody ClubMember body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getClubId() == null) return error("缺少俱乐部ID");
        if (body.getMemberId() == null) return error("缺少成员ID");

        Club club = clubService.selectClubById(body.getClubId());
        if (club == null) return error("俱乐部不存在");
        if (!canManageMembers(club, memberId)) {
            return error("无权管理该俱乐部");
        }
        if (OwnershipUtils.isOwner(club.getMemberId(), body.getMemberId())) {
            return error("不能移除俱乐部创建者");
        }
        ClubMember existing = clubMemberService.selectByClubAndMember(body.getClubId(), body.getMemberId());
        if (existing == null) {
            return error("成员不存在");
        }
        // ★ 管理员之间不能互相移除 —— 否则两个管理员可以把对方踢掉,等于绕过「提/撤管理员仅主理人」。
        //   CLUB_CO_OWNER/CLUB_OPERATOR 等新角色走 club_role_assignment、role 是 0,只查 role==1
        //   会漏掉他们:非 owner 也不得移除任何持有活跃角色委派的成员(移除即全权限归零)。
        boolean actorIsOwner = OwnershipUtils.isOwner(club.getMemberId(), memberId);
        if (!actorIsOwner && Integer.valueOf(1).equals(existing.getRole())) {
            return error("只有主理人可以移除管理员");
        }
        if (!actorIsOwner && holdsActiveRoleAssignment(club.getId(), body.getMemberId())) {
            return error("只有主理人可以移除持有角色的成员");
        }
        ClubLifecycleCommandResultVO lifecycle;
        try {
            lifecycle = clubLifecycleCommandService.removeMember(
                    body.getClubId(), body.getMemberId(), memberId,
                    lifecycleRequestId("club-member-removed", body.getClubId(), body.getMemberId()));
        } catch (RuntimeException e) {
            log.error("移除俱乐部成员失败，业务关系与 IM 清理已回滚，clubId={}, memberId={}",
                    body.getClubId(), body.getMemberId(), e);
            return error("移除失败，群聊权限清理未完成");
        }
        if (lifecycle == null || lifecycle.getAffectedRows() <= 0) return error("移除失败");
        Map<String, Object> result = new HashMap<>();
        result.put("membershipAccessEnd", lifecycle.getMembershipAccessEnd());
        return AjaxResult.success("已移除成员", result);
    }

    @Operation(summary = "申请加入俱乐部(公开直进/主理人审批)")
    @PostMapping("/join")
    public AjaxResult join(@RequestBody Club body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getId() == null) return error("缺少俱乐部ID");
        // 拍板 2026-10-22:商家可以加入俱乐部(不能报名俱乐部活动,闸在报名/候补侧),这里不再拦商家。

        Club club = clubService.selectClubById(body.getId());
        if (club == null || club.getStatus() == null || club.getStatus() != 1L) {
            return error("俱乐部不存在或未开放");
        }
        // 拍板 2026-10-22:需审核的团,申请留言必填、最多 60 字(前端同限);公开团不读留言。
        if (!isPublicJoinClub(club)) {
            String message = body.getJoinMessage() == null ? "" : body.getJoinMessage().trim();
            if (message.isEmpty()) return error("请先给主理人写一句申请留言");
            if (message.codePointCount(0, message.length()) > JOIN_MESSAGE_MAX) {
                return error("申请留言最多 " + JOIN_MESSAGE_MAX + " 字");
            }
        }
        // F-L5C-02:入会申请留言给主理人看,写入前送检。
        String joinRisk = wxContentSecurityService.checkText(body.getJoinMessage(), openidOf(memberId));
        if (joinRisk != null) return error(joinRisk);
        try {
            // 稿 P3 30:2:申请人写的那句话。前端限 60 字,服务端仍按列宽 200 截断兜底。
            String state = clubMemberService.requestJoin(club.getId(), memberId, body.getJoinMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("state", state);
            return AjaxResult.success(IClubMemberService.JOIN_STATE_PENDING.equals(state)
                    ? "申请已提交，等待主理人审核" : "加入成功", result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "开放设置:允许成员发帖")
    @PostMapping("/open-settings/member-post")
    public AjaxResult updateMemberPostAllowed(@RequestBody Club body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null || body.getId() == null) return error("缺少俱乐部ID");
        Integer enabled = body.getMemberPostAllowed();
        if (enabled == null || (enabled != 0 && enabled != 1)) return error("开关值不合法");
        try {
            clubService.updateMemberPostAllowed(body.getId(), memberId, enabled == 1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
        // 回读一次再下发:让前端渲染的是库里的事实,不是它自己刚发出去的那个值。
        Club saved = clubService.selectClubById(body.getId());
        Map<String, Object> data = new HashMap<>();
        data.put("memberPostAllowed", saved == null ? null : saved.getMemberPostAllowed());
        return AjaxResult.success("已保存", data);
    }

    private static final int WELCOME_NOTE_MAX = 200;

    @Operation(summary = "开放设置:入会欢迎语")
    @PostMapping("/open-settings/welcome-note")
    public AjaxResult updateWelcomeNote(@RequestBody Club body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null || body.getId() == null) return error("缺少俱乐部ID");
        String note = body.getWelcomeNote() == null ? "" : body.getWelcomeNote().trim();
        if (note.codePointCount(0, note.length()) > WELCOME_NOTE_MAX) {
            return error("欢迎语最多 " + WELCOME_NOTE_MAX + " 字");
        }
        // 欢迎语会展示给所有新成员,写入前送检(清空不用检)。
        if (!note.isEmpty()) {
            String risk = wxContentSecurityService.checkText(note, openidOf(memberId));
            if (risk != null) return error(risk);
        }
        try {
            clubService.updateWelcomeNote(body.getId(), memberId, note);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
        // 回读再下发:前端渲染库里的事实。
        String saved = clubService.selectWelcomeNote(body.getId());
        Map<String, Object> data = new HashMap<>();
        data.put("welcomeNote", saved == null ? "" : saved);
        return AjaxResult.success("已保存", data);
    }

    private static final int JOIN_MESSAGE_MAX = 60;

    /** join_policy=0(公开加入)。列未落库时按既有「公开直进」口径;读取失败按需审核(宁可少给)。 */
    private boolean isPublicJoinClub(Club club) {
        if (club == null || club.getId() == null) return false;
        try {
            if (!clubService.isJoinPolicySupported()) return true;
            Integer policy = clubService.selectJoinPolicy(club.getId());
            return Integer.valueOf(0).equals(policy);
        } catch (RuntimeException e) {
            log.warn("读取 join_policy 失败,按需审核处理 clubId={}", club.getId(), e);
            return false;
        }
    }

    /**
     * 拍板 2026-10-22:公开加入的团,登录用户不入团也能看成员名单与贡献榜。
     * 仍守:俱乐部在营业(status=1)、对外可见(publicVisible)、本人未被本团/平台封禁。
     * 需审核的团保持「通过审核后可看」。名单字段与隐私口径与成员看到的完全相同。
     */
    private boolean canBrowsePublicRoster(Club club, Long viewerId) {
        if (club == null || viewerId == null || viewerId <= 0) return false;
        if (club.getStatus() == null || club.getStatus() != 1L) return false;
        if (!canViewClub(club) || !isPublicJoinClub(club)) return false;
        try {
            clubParticipationGuard.requireAllowed(club.getId(), viewerId);
        } catch (RuntimeException blocked) {
            return false;
        }
        return true;
    }

    /**
     * 稿 M「俱乐部公开可见」的读侧闸。关掉之后只有主理人与在册成员看得见。
     * 未登录 / 身份读取失败一律按「看不见」处理 —— 可见性判不出来时,宁可少给,不可多给。
     * ⚠️ 列读到 null 按可见算:与加这列之前的行为一致,存量俱乐部不会因为迁移集体消失。
     */
    private boolean canViewClub(Club club) {
        if (club == null) return false;
        if (club.getPublicVisible() == null || !Integer.valueOf(0).equals(club.getPublicVisible())) return true;
        Long viewer;
        try { viewer = currentMemberId(); } catch (Exception notLoggedIn) { return false; }
        if (viewer == null) return false;
        if (OwnershipUtils.isOwner(club.getMemberId(), viewer)) return true;
        try {
            ClubMember membership = clubMemberService.selectMembershipRecord(club.getId(), viewer);
            return membership != null && Integer.valueOf(1).equals(membership.getStatus());
        } catch (RuntimeException e) {
            log.warn("判定俱乐部可见性时身份读取失败，按不可见处理，clubId={}", club.getId(), e);
            return false;
        }
    }

    private List<Club> visibleToViewer(List<Club> clubs) {
        List<Club> out = new ArrayList<>();
        if (clubs == null) return out;
        for (Club c : clubs) if (canViewClub(c)) out.add(c);
        return out;
    }

    @Operation(summary = "开放设置:俱乐部公开可见")
    @PostMapping("/open-settings/public-visible")
    public AjaxResult updatePublicVisible(@RequestBody Club body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null || body.getId() == null) return error("缺少俱乐部ID");
        Integer enabled = body.getPublicVisible();
        if (enabled == null || (enabled != 0 && enabled != 1)) return error("开关值不合法");
        try {
            clubService.updatePublicVisible(body.getId(), memberId, enabled == 1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
        Club saved = clubService.selectClubById(body.getId());
        Map<String, Object> data = new HashMap<>();
        data.put("publicVisible", saved == null ? null : saved.getPublicVisible());
        return AjaxResult.success("已保存", data);
    }

    @Operation(summary = "开放设置:开放商家承接")
    @PostMapping("/open-settings/merchant-coop")
    public AjaxResult updateMerchantUndertakeOpen(@RequestBody Club body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null || body.getId() == null) return error("缺少俱乐部ID");
        Integer enabled = body.getMerchantUndertakeOpen();
        if (enabled == null || (enabled != 0 && enabled != 1)) return error("开关值不合法");
        try {
            clubService.updateMerchantUndertakeOpen(body.getId(), memberId, enabled == 1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
        Club saved = clubService.selectClubById(body.getId());
        Map<String, Object> data = new HashMap<>();
        data.put("merchantUndertakeOpen", saved == null ? null : saved.getMerchantUndertakeOpen());
        return AjaxResult.success("已保存", data);
    }

    @Operation(summary = "主理人查看待审入会申请")
    @PostMapping("/join-requests")
    public AjaxResult joinRequests(@RequestBody ClubMember body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getClubId() == null) return error("缺少俱乐部ID");
        try {
            return AjaxResult.success(clubMemberService.selectPendingJoinRequests(body.getClubId(), memberId));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "主理人通过入会申请")
    @PostMapping("/join-request/approve")
    public AjaxResult approveJoinRequest(@RequestBody ClubMember body) {
        return reviewJoinRequest(body, true);
    }

    @Operation(summary = "主理人拒绝入会申请")
    @PostMapping("/join-request/reject")
    public AjaxResult rejectJoinRequest(@RequestBody ClubMember body) {
        return reviewJoinRequest(body, false);
    }

    private AjaxResult reviewJoinRequest(ClubMember body, boolean approve) {
        Long ownerId;
        try { ownerId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null || body.getClubId() == null || body.getMemberId() == null) return error("缺少俱乐部或申请人ID");
        String why = body.getReviewReason() == null ? "" : body.getReviewReason().trim();
        if (why.length() > 500) return error("审核理由不能超过500字");
        if (!why.isEmpty()) {
            String risk = wxContentSecurityService.checkText(why, openidOf(ownerId));
            if (risk != null) return error(risk);
        }
        try {
            clubMemberService.reviewJoinRequest(body.getClubId(), body.getMemberId(), ownerId, approve, why);
            return success(approve ? "已通过入会申请" : "已拒绝入会申请");
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "主理人解散俱乐部")
    @PostMapping("/dissolve")
    public AjaxResult dissolve(@RequestBody Club body) {
        Long ownerId;
        try { ownerId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getId() == null) return error("缺少俱乐部ID");
        try {
            ClubLifecycleCommandResultVO lifecycle = clubLifecycleCommandService.dissolve(body.getId(), ownerId,
                    Boolean.TRUE.equals(body.getDissolveConfirmed()),
                    Boolean.TRUE.equals(body.getMemberConsequencesConfirmed()),
                    lifecycleRequestId("club-dissolved", body.getId(), ownerId));
            Map<String, Object> result = new HashMap<>();
            result.put("membersAffected", lifecycle.getAffectedRows());
            result.put("membershipAccessEnd", lifecycle.getMembershipAccessEnd());
            return AjaxResult.success("俱乐部已解散", result);
        } catch (ClubDissolutionDepositException e) {
            Map<String, Object> data = new HashMap<>();
            data.put("blockerType", "deposit");
            data.put("sectionTitle", "合作保证金");
            data.put("deposits", e.getDeposits());
            List<Map<String, Object>> actions = new ArrayList<>();
            for (ClubPendingDeposit deposit : e.getDeposits()) {
                Map<String, Object> action = new HashMap<>();
                // 稿 P6 31:38 每条是「标题 / 状态 / 金额 · 主题 / 动作钮」四段。
                // label 保留成同样的一行,给还没跟上的旧客户端兜底。
                action.put("title", "保证金 #" + deposit.getId());
                action.put("statusText", depositStatusText(deposit.getDepositStatus()));
                action.put("metaText", blockerMeta(deposit.getAmount(), deposit.getTopicId()));
                boolean historical = Integer.valueOf(4).equals(deposit.getDepositStatus());
                action.put("actionText", historical ? (deposit.isRetryable() ? "核对退款结果" : "联系平台核验") : "重试原路退款");
                action.put("label", "保证金 #" + deposit.getId() + " · "
                        + depositStatusText(deposit.getDepositStatus()));
                action.put("url", "/pages/club/dissolution-blockers/index?clubId=" + body.getId()
                        + "&type=deposit&id=" + deposit.getId());
                if (historical && !deposit.isRetryable()) action.put("url", "/pages/shezhi/about/index");
                actions.add(action);
            }
            data.put("actionItems", actions);
            return AjaxResult.error(e.getMessage(), data);
        } catch (ClubDissolutionSettlementException e) {
            Map<String, Object> data = new HashMap<>();
            data.put("blockerType", "settlement");
            data.put("sectionTitle", "待结算款项");
            data.put("settlements", e.getSettlements());
            List<Map<String, Object>> actions = new ArrayList<>();
            for (ClubPendingSettlement settlement : e.getSettlements()) {
                String direction = "incoming".equals(settlement.getDirection()) ? "待收进来"
                        : ("outgoing".equals(settlement.getDirection()) ? "待付出去" : "待确认归属");
                Map<String, Object> action = new HashMap<>();
                action.put("title", "结算单 #" + settlement.getId());
                action.put("statusText", direction);
                action.put("metaText", blockerMeta(settlement.getAmount(), settlement.getTopicId()));
                action.put("actionText", "联系平台逐笔处理");
                action.put("label", direction + " · 结算 #" + settlement.getId());
                action.put("url", "/pages/club/dissolution-blockers/index?clubId=" + body.getId()
                        + "&type=settlement&id=" + settlement.getId());
                actions.add(action);
            }
            data.put("actionItems", actions);
            return AjaxResult.error(e.getMessage(), data);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "主理人查看解散资金阻断逐笔明细")
    @PostMapping("/dissolution-blockers")
    public AjaxResult dissolutionBlockers(@RequestBody Club body) {
        Long ownerId;
        try { ownerId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null || body.getId() == null) return error("缺少俱乐部ID");
        try {
            return AjaxResult.success(clubService.selectDissolutionBlockers(body.getId(), ownerId));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /** 稿 P6 的第二行:「¥500.00 · 主题 #11」。金额一律服务端定死小数位,前端不算钱。 */
    private String blockerMeta(java.math.BigDecimal amount, Long topicId) {
        StringBuilder sb = new StringBuilder();
        if (amount != null) sb.append('\u00A5').append(amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        if (topicId != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("主题 #").append(topicId);
        }
        return sb.toString();
    }

    private String depositStatusText(Integer status) {
        if (Integer.valueOf(1).equals(status)) return "支付中";
        if (Integer.valueOf(2).equals(status)) return "已缴冻结，待退或待扣";
        if (Integer.valueOf(4).equals(status)) return "历史退款待核验";
        if (Integer.valueOf(5).equals(status)) return "应缴未缴";
        return "状态待确认";
    }

    @Operation(summary = "退出俱乐部")
    @PostMapping("/quit")
    public AjaxResult quit(@RequestBody Club body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getId() == null) return error("缺少俱乐部ID");
        Club club = clubService.selectClubById(body.getId());
        if (club != null && OwnershipUtils.isOwner(club.getMemberId(), memberId)) {
            return error("主理人不能直接退出；请先在成员治理中转让主理人，或解散俱乐部");
        }
        ClubLifecycleCommandResultVO lifecycle;
        try {
            lifecycle = clubLifecycleCommandService.quit(
                    body.getId(), memberId,
                    lifecycleRequestId("club-member-quit", body.getId(), memberId));
        } catch (RuntimeException e) {
            log.error("退出俱乐部失败，业务关系与 IM 清理已回滚，clubId={}, memberId={}",
                    body.getId(), memberId, e);
            return error("退出失败，群聊权限清理未完成");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("membershipAccessEnd", lifecycle.getMembershipAccessEnd());
        return AjaxResult.success(lifecycle.getAffectedRows() > 0 ? "已退出" : "未加入", result);
    }

    private String lifecycleRequestId(String prefix, Long clubId, Long memberId) {
        return prefix + "-" + clubId + "-" + memberId + "-" + System.currentTimeMillis();
    }

    @Operation(summary = "我的俱乐部(我创建的+我加入的)")
    @PostMapping("/my")
    public AjaxResult my() {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }

        Map<String, Object> data = new HashMap<>();
        List<Club> owned = clubService.selectClubsByMemberId(memberId); // 多俱乐部:owned 为列表
        for (Club c : owned) {
            attachClubDisplay(c);
            c.setMemberCount(effectiveMemberCount(c)); // 口径统一(含主理人)
            c.setIsOwner(true);
        }
        data.put("owned", owned);

        List<Long> joinedIds = clubMemberService.selectClubIdsByMember(memberId);
        java.util.List<Club> joinedClubs = new java.util.ArrayList<>();
        for (Long cid : joinedIds) {
            Club c = clubService.selectClubById(cid);
            if (c != null) {
                attachClubDisplay(c);
                c.setMemberCount(effectiveMemberCount(c));
                c.setIsJoined(true);
                joinedClubs.add(c);
            }
        }
        data.put("joined", joinedClubs);
        List<Club> myClubs = new java.util.ArrayList<>(owned);
        myClubs.addAll(joinedClubs);
        attachTopicCounts(myClubs);
        return AjaxResult.success(data);
    }

    @Operation(summary = "俱乐部首页聚合(我的/加入/附近/活动/主理人态)")
    @PostMapping("/home")
    public AjaxResult home() {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }

        Map<String, Object> data = new HashMap<>();

        // 我创建的
        List<Club> owned = clubService.selectClubsByMemberId(memberId);
        for (Club c : owned) {
            attachClubDisplay(c);
            c.setMemberCount(effectiveMemberCount(c));
            c.setIsOwner(true);
        }
        data.put("owned", owned);

        // 我加入的
        List<Long> joinedIds = clubMemberService.selectClubIdsByMember(memberId);
        List<Club> joined = new java.util.ArrayList<>();
        for (Long cid : joinedIds) {
            Club c = clubService.selectClubById(cid);
            if (c != null) {
                attachClubDisplay(c);
                c.setMemberCount(effectiveMemberCount(c));
                c.setIsJoined(true);
                joined.add(c);
            }
        }
        data.put("joined", joined);

        // 附近/发现:已通过俱乐部,排除已 owned/joined,取前 10(真实地理"附近"待接定位,先按目录发现)
        java.util.Set<Long> mine = new java.util.HashSet<>();
        for (Club c : owned) mine.add(c.getId());
        mine.addAll(joinedIds);
        Club discoverQuery = new Club();
        discoverQuery.setStatus(1L);
        List<Club> nearby = new java.util.ArrayList<>();
        for (Club c : clubService.selectClubList(discoverQuery)) {
            // O-08:关了「公开可见」的俱乐部只给主理人/成员看 —— 发现列表下发的是整行(含地点),必须过同一道 canViewClub。
            if (c.getId() != null && !mine.contains(c.getId()) && canViewClub(c)) {
                attachClubDisplay(c);
                c.setMemberCount(effectiveMemberCount(c));
                nearby.add(c);
                if (nearby.size() >= 10) break;
            }
        }
        List<Club> homeClubs = new java.util.ArrayList<>(owned);
        homeClubs.addAll(joined);
        homeClubs.addAll(nearby);
        attachTopicCounts(homeClubs);
        data.put("nearby", nearby);

        // 俱乐部活动:我创建俱乐部下的经典定向团(topic.clubId),取前 20
        List<Map<String, Object>> events = new java.util.ArrayList<>();
        for (Club c : owned) {
            if (events.size() >= 20) break;
            CmsTopic tq = new CmsTopic();
            tq.setClubId(c.getId());
            tq.setDelFlag(0);
            for (CmsTopic t : cmsTopicService.selectCmsTopicList(tq)) {
                Map<String, Object> e = new HashMap<>();
                e.put("id", t.getId());
                e.put("title", t.getName());
                e.put("cover", t.getImgUrl());
                e.put("clubId", c.getId());
                e.put("signupCount", t.getSignupCount() == null ? 0 : t.getSignupCount());
                events.add(e);
                if (events.size() >= 20) break;
            }
        }
        data.put("events", events);

        // 主理人态(身份互斥):商家不可创建;玩家/主理人可建至上限
        ClubLeader leader = clubLeaderService.selectClubLeaderByMemberId(memberId);
        boolean isClubLeader = capabilityService.hasCapability(memberId, MemberCapability.CAP_CLUB_LEADER);
        boolean merchant = isMerchant(memberId);
        Map<String, Object> leaderStatus = new HashMap<>();
        leaderStatus.put("isClubLeader", isClubLeader);
        leaderStatus.put("ownedCount", owned.size());
        leaderStatus.put("maxOwned", MAX_CLUBS_PER_LEADER);
        leaderStatus.put("canCreate", !merchant && owned.size() < MAX_CLUBS_PER_LEADER);
        data.put("leaderStatus", leaderStatus);

        return AjaxResult.success(data);
    }

    @Operation(summary = "可对接商家列表(承接方,coop_open=1)")
    @PostMapping("/merchants")
    public AjaxResult merchants(@RequestBody(required = false) MmsMerchant query) {
        if (query == null) query = new MmsMerchant();
        query.setCoopOpen(1);   // 开放对接
        query.setStatus(1L);    // 审核通过
        query.setDelFlag(0);
        return AjaxResult.success(mmsMerchantService.selectMmsMerchantList(query));
    }

    @Operation(summary = "俱乐部群聊(进入/创建,成员可进)")
    @PostMapping("/chat")
    public AjaxResult chat(@RequestBody Club body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getId() == null) return error("缺少俱乐部ID");
        try {
            clubParticipationGuard.requireAllowed(body.getId(), memberId);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
        Club club = clubService.selectClubById(body.getId());
        if (club == null) return error("俱乐部不存在");
        boolean isOwner = OwnershipUtils.isOwner(club.getMemberId(), memberId);
        boolean isMember = clubMemberService.selectByClubAndMember(club.getId(), memberId) != null;
        if (!isOwner && !isMember) return new AjaxResult(403, "加入俱乐部后才能进群聊");
        // Service 锁住 club 后重查正常成员并自愈群成员，不能信任锁外收集的旧名单。
        Long convId = imService.getOrCreateClubGroup(club.getId(), memberId);
        Map<String, Object> d = new HashMap<>();
        d.put("conversationId", convId);
        return AjaxResult.success(d);
    }

    @Operation(summary = "俱乐部自建勋章(主理人上传名+图)")
    @PostMapping("/badge/create")
    public AjaxResult badgeCreate(@RequestBody Map<String, Object> body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long clubId = badgeLong(body.get("clubId"));
        String name = badgeStr(body.get("badgeName"));
        if (clubId == null || StringUtils.isEmpty(name)) return error("缺少俱乐部或勋章名");
        Club club = clubService.selectClubById(clubId);
        if (club == null) return error("俱乐部不存在");
        if (OwnershipUtils.notOwner(club.getMemberId(), memberId)) return error("只有主理人可创建勋章");
        // X03 能力位:权益清单里的「勋章设计」= canDesignMedal。主理人身份与该能力位是两件事,
        // 前者判「是不是这个俱乐部的主人」,后者判「这个身份解锁了勋章设计没有」。
        String medalCapErr = capabilityGuard.checkDesignMedal(memberId);
        if (medalCapErr != null) return error(medalCapErr);
        String badgeDesc = badgeStr(body.get("badgeDesc"));
        String risk = wxContentSecurityService.checkText(name + "\n" + (badgeDesc == null ? "" : badgeDesc), openidOf(memberId));
        if (risk != null) return error(risk);
        GrowthBadge gb = new GrowthBadge();
        gb.setClubId(clubId);
        gb.setBadgeCode("CLUB" + clubId + "_" + System.currentTimeMillis());
        gb.setBadgeName(name.length() > 120 ? name.substring(0, 120) : name);
        gb.setIconUrl(reviewedMedia(memberId, badgeStr(body.get("iconUrl")), false));
        gb.setBadgeDesc(badgeStr(body.get("badgeDesc")));
        gb.setStatus("0");
        gb.setSortNo(0);
        growthBadgeMapper.insertGrowthBadge(gb);
        Map<String, Object> d = new HashMap<>();
        d.put("id", gb.getId());
        d.put("badgeCode", gb.getBadgeCode());
        return AjaxResult.success("已创建", d);
    }

    @Operation(summary = "俱乐部勋章列表(主理人自建)")
    @PostMapping("/badge/list")
    public AjaxResult badgeList(@RequestBody Club body) {
        try { currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getId() == null) return error("缺少俱乐部ID");
        if (clubService.selectClubById(body.getId()) == null) return error("俱乐部不存在");
        return AjaxResult.success(growthBadgeMapper.selectByClub(body.getId()));
    }

    @Operation(summary = "颁发勋章给俱乐部成员")
    @PostMapping("/badge/grant")
    public AjaxResult badgeGrant(@RequestBody Map<String, Object> body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long clubId = badgeLong(body.get("clubId"));
        Long target = badgeLong(body.get("memberId"));
        String code = badgeStr(body.get("badgeCode"));
        if (clubId == null || target == null || StringUtils.isEmpty(code)) return error("参数不完整");
        Club club = clubService.selectClubById(clubId);
        if (club == null) return error("俱乐部不存在");
        if (OwnershipUtils.notOwner(club.getMemberId(), memberId)) return error("只有主理人可颁发");
        GrowthBadge gb = growthBadgeMapper.selectByCode(code);
        if (gb == null || gb.getClubId() == null || !gb.getClubId().equals(clubId)) return error("勋章不存在或不属于本俱乐部");
        boolean isMember = OwnershipUtils.isOwner(club.getMemberId(), target) || clubMemberService.selectByClubAndMember(clubId, target) != null;
        if (!isMember) return error("对方不是本俱乐部成员");
        if (playerBadgeMapper.countByMemberAndCode(target, code) > 0) return AjaxResult.success("已颁发过");
        PlayerBadge awardedBadge = new PlayerBadge();
        awardedBadge.setMemberId(target);
        awardedBadge.setBadgeCode(code);
        awardedBadge.setBadgeName(gb.getBadgeName());
        awardedBadge.setIconUrl(gb.getIconUrl());
        awardedBadge.setObtainTime(new Date());
        awardedBadge.setSourceType(PLAYER_BADGE_SOURCE_CLUB);
        awardedBadge.setSourceId(clubId);
        awardedBadge.setDelFlag(0);
        awardedBadge.setCreateTime(new Date());
        playerBadgeMapper.insertPlayerBadge(awardedBadge);
        return AjaxResult.success("已颁发");
    }

    @Operation(summary = "俱乐部办的经典定向团")
    @PostMapping("/topics")
    public AjaxResult topics(@RequestBody Club body) {
        if (body.getId() == null) return error("缺少俱乐部ID");
        Club club = clubService.selectClubById(body.getId());
        if (club == null) return error("俱乐部不存在");
        if (!canViewClub(club)) return error("这个俱乐部只对成员开放");
        CmsTopic probe = new CmsTopic();
        probe.setClubId(body.getId());
        // 这个端点不要求登录(游客也能看俱乐部主页),拿不到人就当非成员 —— fail-closed。
        Long viewerId = null;
        try {
            viewerId = currentMemberId();
        } catch (Exception notLoggedIn) {
            viewerId = null;
        }
        probe.setViewerMemberId(viewerId);
        List<CmsTopic> topics = cmsTopicService.selectPublicCmsTopicList(probe);
        // 富化每团已报名人数(已支付报名)
        for (CmsTopic t : topics) {
            t.setGameModuleEnabled(gameSessionRuntimeService.isGameSessionEnabled(t));
            CmsRegistration rq = new CmsRegistration();
            rq.setOwnerType(1L);
            rq.setOwnerId(t.getId());
            rq.setPaymentStatus(2);
            int signup = cmsRegistrationService.selectCmsRegistrationList(rq).size();
            // CU-C-19:玩家买的多是场次票(owner_type=2,主题票复制件),只数主题票 ⇒ 团卡恒「已报名 0 人」,
            // 展开名册却有人。与 /topic-registrations 名册同口径:本俱乐部名下该主题各场次的已支付报名一起算。
            CmsActivity sessionProbe = new CmsActivity();
            sessionProbe.setTopicId(t.getId());
            sessionProbe.setClubId(body.getId());
            sessionProbe.setDelFlag(0);
            List<CmsActivity> sessions = cmsActivityService.selectCmsActivityList(sessionProbe);
            if (sessions != null) {
                for (CmsActivity session : sessions) {
                    if (session == null || session.getId() == null) continue;
                    CmsRegistration sessionRq = new CmsRegistration();
                    sessionRq.setOwnerType(2L);
                    sessionRq.setOwnerId(session.getId());
                    sessionRq.setPaymentStatus(2);
                    signup += cmsRegistrationService.selectCmsRegistrationList(sessionRq).size();
                }
            }
            t.setSignupCount(signup);
            // 富化成团状态:取该团经典定向(mode=1)票的 team_status(0募集中/1已成团/2未成团已散)
            OmsTicket tq = new OmsTicket();
            tq.setOwnerType(1L);
            tq.setOwnerId(t.getId());
            for (OmsTicket tk : omsTicketService.selectOmsTicketList(tq)) {
                if (PlayMode.isLinear(tk.getMode())) { t.setTeamStatus(tk.getTeamStatus()); break; }
            }
        }
        return AjaxResult.success(topics);
    }

    @Operation(summary = "主理人查询可开团的生效合作主题")
    @PostMapping("/open-session-topics")
    public AjaxResult openSessionTopics(@RequestBody Map<String, Object> body) {
        Long memberId;
        try {
            memberId = currentMemberId();
        } catch (Exception e) {
            return error("请先登录");
        }
        try {
            Long clubId = body.get("clubId") == null ? null : Long.valueOf(body.get("clubId").toString());
            return AjaxResult.success(clubSessionService.selectOpenableTopics(clubId, memberId));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "主理人选择合作主题与日期开一场")
    @PostMapping("/open-session")
    public AjaxResult openSession(@RequestBody ClubSessionOpenRequest body) {
        Long memberId;
        String username;
        try {
            memberId = currentMemberId();
            username = getAppUserName();
        } catch (Exception e) {
            return error("请先登录");
        }
        try {
            Long activityId = clubSessionService.open(body, memberId, username);
            Map<String, Object> data = new HashMap<>();
            data.put("activityId", activityId);
            return AjaxResult.success("开团成功", data);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 俱乐部治理视角:查看本团某站点玩法的答案 / 提示 / 答错话术。
     *
     * <p>★ 为什么必须是一条**独立端点**,不能复用 {@code /api/play/nodes}:
     * 那条是**玩家**视角的游玩投影。它上面的 answerReveal 走 {@code SpoilerProtectionService},
     * 判据是「这个玩家自己写过一行 member_spoiler_reveal」(而且揭示即 0 分);
     * 而且它对答案的剥离只写在 {@code playMode == PlayMode.FREE_COLLECT} 那一支里,
     * 经典定向那一支根本不经过。两件事的**主体、判据、后果**都不一样 ——
     * 把主理人的「我要核对答案」接到玩家链路上,等于用玩家身份去开玩家的付费内容闸。
     *
     * <p>本端点的判据只有一条:{@code canGovernClub}(主理人或 status=1&role=1 的管理员),
     * 再叠一层「团必须属于本俱乐部 / 站点必须属于本团」的越权交叉校验。
     * ⚠️ **不能**用 {@code topic.getMemberId()}:transfer-to-club 之后内容槽仍挂在原创作者名下,
     * 用 owner 判据会把真正带队的俱乐部管理员挡在外面(而且判的也不是俱乐部身份)。
     *
     * <p>拿不准一律 fail-closed:任何一步取不到对象、对不上归属、模板不是答题类,都直接拒。
     */
    @Operation(summary = "俱乐部治理:查看本团某站点的玩法答案(仅主理人/管理员)")
    @PostMapping("/topic-node-answer")
    public AjaxResult topicNodeAnswer(@RequestBody Map<String, Object> body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null) return error("缺少参数");
        Long clubId = badgeLong(body.get("clubId"));
        Long topicId = badgeLong(body.get("topicId"));
        Long nodeId = badgeLong(body.get("nodeId"));
        if (clubId == null || topicId == null || nodeId == null) return error("缺少俱乐部、团或站点ID");
        Club club = clubService.selectClubById(clubId);
        if (club == null) return error("俱乐部不存在");
        if (!clubMemberService.canGovernClub(club, memberId)) {
            return error("仅主理人或管理员可查看玩法答案");
        }
        // 团归属校验:防管理员拿别的俱乐部的 topicId 来读答案
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        if (topic == null || topic.getClubId() == null || !topic.getClubId().equals(clubId)) {
            return error("该团不属于本俱乐部");
        }
        // 站点归属校验:防拿别的团的 nodeId 搭本团的权限便车
        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nodeId);
        if (node == null || node.getTopicId() == null || !node.getTopicId().equals(topicId)) {
            return error("该站点不属于本团");
        }
        // 答案文本一律走 IPuzzlePlayService:selectPuzzleTemplate 只放行 validationMethod 1/3
        // (文字问答 / 选择题),revealText 负责 A/B/C/D 的展开 —— 不在这里另写一套判据。
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

    @Operation(summary = "团报名名册(owner):某团各票种报名者明细")
    @PostMapping("/topic-registrations")
    public AjaxResult topicRegistrations(@RequestBody Map<String, Object> body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Long clubId = badgeLong(body.get("clubId"));
        Long topicId = badgeLong(body.get("topicId"));
        if (clubId == null || topicId == null) return error("缺少俱乐部或团ID");
        Club club = clubService.selectClubById(clubId);
        if (club == null) return error("俱乐部不存在");
        // 2026-08-26:名册放开给管理员**只读**(带队要按名单点人)。
        // ⚠️ 清退退款是另一个接口,仍然 owner-only,别一起放。
        if (!clubMemberService.canGovernClub(club, memberId)) {
            return error("仅主理人或管理员可查看报名名册");
        }
        // 团归属校验:发布俱乐部认 topic.club_id,执行俱乐部认生效合作单的 ORGANIZER 权限链。
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        if (topic == null || (!clubId.equals(topic.getClubId())
                && !clubMemberService.isTopicOrganizer(topicId, clubId, memberId))) {
            return error("该团不属于本俱乐部");
        }
        // 主题票(owner_type=1):自玩通行证与「整团报名」落在这上面。
        OmsTicket tq = new OmsTicket();
        tq.setOwnerType(1L);
        tq.setOwnerId(topicId);
        tq.setDelFlag(0);
        List<OmsTicket> tickets = new ArrayList<>();
        List<OmsTicket> topicTickets = omsTicketService.selectOmsTicketList(tq);
        if (topicTickets != null) tickets.addAll(topicTickets);

        // ★ 场次票(owner_type=2)必须一起查,否则这张名册在结构上恒为空。
        //   主理人开一场 → ClubSessionServiceImpl 建 cms_activity → copyTopicTicketsToActivity
        //   把主题票**复制**成 owner_type=2 的新票行,玩家报名买的是复制件。
        //   只查 owner_type=1 就只剩自玩通行证,而它压根不带 ticketId ⇒ 一行都捞不到。
        //   同一份事实 CRM 那条链路(ClubCrmMapper)两档都处理,名册这条没跟上。
        //   票名前缀带上场次日期:一个主题可以开很多场,不标就分不清这二十个人是哪天的。
        CmsActivity sessionProbe = new CmsActivity();
        sessionProbe.setTopicId(topicId);
        sessionProbe.setClubId(clubId);
        sessionProbe.setDelFlag(0);
        List<CmsActivity> sessions = cmsActivityService.selectCmsActivityList(sessionProbe);
        List<String> sessionIds = new ArrayList<>();
        Map<String, String> sessionLabelById = new LinkedHashMap<>();
        if (sessions != null) {
            for (CmsActivity session : sessions) {
                if (session == null || session.getId() == null) continue;
                String label = sessionLabel(session);
                if (label == null) continue;
                String key = String.valueOf(session.getId());
                sessionIds.add(key);
                sessionLabelById.put(key, label);
            }
        }
        // CU-C-153:同日多场只写「9月22日」时,几张不同的场次票在台账里逐字同名,工作人员不知道点哪一组。
        // 连集合时刻都一样(存量 00:00 场次没回填过时刻)就再补场次序号,把"同名"这件事判死。
        Map<String, Integer> labelHits = new LinkedHashMap<>();
        for (String id : sessionIds) {
            String label = sessionLabelById.get(id);
            labelHits.put(label, labelHits.getOrDefault(label, 0) + 1);
        }
        Map<String, Integer> labelUsed = new HashMap<>();
        Map<String, String> sessionLabels = new LinkedHashMap<>();
        for (String id : sessionIds) {
            String label = sessionLabelById.get(id);
            if (labelHits.get(label) > 1) {
                int nth = labelUsed.getOrDefault(label, 0) + 1;
                labelUsed.put(label, nth);
                label = label + " 第" + nth + "场";
            }
            sessionLabels.put(id, label);
        }
        if (sessions != null) {
            for (CmsActivity session : sessions) {
                if (session == null || session.getId() == null) continue;
                OmsTicket sq = new OmsTicket();
                sq.setOwnerType(2L);
                sq.setOwnerId(session.getId());
                sq.setDelFlag(0);
                List<OmsTicket> sessionTickets = omsTicketService.selectOmsTicketList(sq);
                if (sessionTickets == null) continue;
                String label = sessionLabels.get(String.valueOf(session.getId()));
                for (OmsTicket tk : sessionTickets) {
                    if (tk == null) continue;
                    if (label != null) tk.setName(label + " · " + (tk.getName() == null ? "票" : tk.getName()));
                    tickets.add(tk);
                }
            }
        }
        List<ClubRegistrationRosterVO.Ticket> safeTickets = new ArrayList<>();
        for (OmsTicket tk : tickets) {
            ViewRegistration rq = new ViewRegistration();
            rq.setTicketId(tk.getId());
            rq.setRegistrationStatus(2); // 已支付
            List<ViewRegistration> registrations = viewRegistrationService.selectRegistrationSummaries(rq);
            List<ClubRegistrationRosterVO.Registration> safeRegistrations = new ArrayList<>();
            if (registrations != null) {
                for (ViewRegistration registration : registrations) {
                    ClubRegistrationRosterVO.Registration safe = new ClubRegistrationRosterVO.Registration();
                    safe.setId(registration.getId());
                    safe.setMemberId(registration.getMemberId());
                    safe.setAvatar(registration.getAvatar());
                    safe.setNickname(registration.getNickname());
                    safe.setPaymentStatus(registration.getPaymentStatus());
                    safe.setVerificationStatus(registration.getVerificationStatus());
                    safeRegistrations.add(safe);
                }
            }
            ClubRegistrationRosterVO.Ticket safeTicket = new ClubRegistrationRosterVO.Ticket();
            safeTicket.setId(tk.getId());
            safeTicket.setName(tk.getName());
            safeTicket.setMode(tk.getMode());
            safeTicket.setTotalInventory(tk.getTotalInventory());
            safeTicket.setSignupDeadline(tk.getSignupDeadline());
            safeTicket.setTeamStatus(tk.getTeamStatus());
            safeTicket.setCmsRegistrationList(safeRegistrations);
            safeTickets.add(safeTicket);
        }
        ClubRegistrationRosterVO roster = new ClubRegistrationRosterVO();
        roster.setClubId(clubId);
        roster.setTopicId(topicId);
        // canRefund 是权限声明不是摆设:清退退款 owner-only,管理员回包写 true 就是假按钮。
        roster.setCanRefund(club.getMemberId() != null && club.getMemberId().equals(memberId));
        roster.setOmsTicketList(safeTickets);
        return AjaxResult.success(roster);
    }

    /**
     * 名册里区分场次用的短标签:日期必给,集合时刻只在**真的填过**时才补。
     * CU-C-153:只给日期时,同一天的两场在台账里逐字同名(「9月22日 · 单人票」×2),
     * 工作人员不知道点哪一组核销。开一场时 startDate 就是集合时刻,带上它就足够区分;
     * 但存量场次没回填过时刻,库里存的是 00:00 —— 那是「没填」不是「半夜集合」,
     * 显示出来等于伪造一个时间,所以 00:00 仍只显日期,同名由调用方补场次序号。
     */
    private String sessionLabel(CmsActivity session) {
        java.util.Date start = session.getStartDate();
        if (start == null) return null;
        java.time.ZonedDateTime local = java.time.Instant.ofEpochMilli(start.getTime())
                .atZone(java.time.ZoneId.of("Asia/Shanghai"));
        String pattern = local.getHour() == 0 && local.getMinute() == 0 ? "M月d日" : "M月d日 HH:mm";
        return local.format(java.time.format.DateTimeFormatter.ofPattern(pattern));
    }

    /** 俱乐部动态流单页非置顶条数上限 */
    private static final int POST_PAGE_MAX = 50;

    @Operation(summary = "俱乐部动态列表(公开)")
    @PostMapping("/post/list")
    public AjaxResult postList(@RequestBody ClubPost body) {
        if (body.getClubId() == null) return error("缺少俱乐部ID");
        Club club = clubService.selectClubById(body.getClubId());
        if (club == null) return error("俱乐部不存在");
        if (!canViewClub(club)) return error("这个俱乐部只对成员开放");
        // P0-82:按页取,不再整俱乐部全量拉。首页=置顶帖+最新 limit 条;翻页带 beforeId(上一页最后一条非置顶帖)。
        // 回包仍是数组,老客户端不传 limit 时按上限取首页。
        int limit = body.getLimit() == null ? POST_PAGE_MAX : Math.max(1, Math.min(POST_PAGE_MAX, body.getLimit()));
        Long beforeId = body.getBeforeId() != null && body.getBeforeId() > 0 ? body.getBeforeId() : null;
        List<ClubPost> posts = clubPostService.selectClubPostPage(body.getClubId(), beforeId, limit);
        // dw29 L8:本俱乐部成员的转发按转发时间插进同一页。时间窗跟着帖文游标走 ——
        // 上界 = 游标帖的发帖时间(不含),下界 = 本页最后一条非置顶帖的发帖时间(含,页不满则不设界),
        // 前后两页的窗首尾相接,转发不会重复也不会漏,老客户端的 beforeId 用法不变。
        List<ClubPost> pinned = new ArrayList<>();
        List<ClubPost> rest = new ArrayList<>();
        for (ClubPost p : posts) {
            if (beforeId == null && Integer.valueOf(1).equals(p.getIsPinned())) pinned.add(p); else rest.add(p);
        }
        Date until = null;
        if (beforeId != null) {
            ClubPost cursor = clubPostService.selectClubPostById(beforeId);
            until = cursor == null ? null : cursor.getCreateTime();
        }
        Date since = rest.size() >= limit ? rest.get(rest.size() - 1).getCreateTime() : null;
        List<ClubPostRepostItemVO> reposts = repostItems(clubPostRepostService.selectFeedReposts(
                java.util.Collections.singletonList(body.getClubId()), since, until, POST_PAGE_MAX));
        List<ClubPost> all = withRepostOriginals(posts, reposts);
        projectPostReferences(all);
        try {
            Long memberId = currentMemberId();
            for (ClubPost p : all) {
                p.setIsLiked(clubPostLikeService.selectByPostAndMember(p.getId(), memberId) != null);
            }
            markViewerReposted(all, memberId);
        } catch (Exception ignore) { /* 未登录不标 isLiked / viewerReposted */ }
        List<Object> items = new ArrayList<>(pinned);
        items.addAll(mergeByTime(rest, reposts));
        return AjaxResult.success(items);
    }

    /** 帖文 + 转发条目里嵌的原帖,一起做引用投影与查看者状态标注。 */
    private List<ClubPost> withRepostOriginals(List<ClubPost> posts, List<ClubPostRepostItemVO> reposts) {
        List<ClubPost> all = new ArrayList<>(posts);
        for (ClubPostRepostItemVO r : reposts) all.add(r.getPost());
        return all;
    }

    /** 当前查看者对这批帖文的转发态(一次批量查询)。 */
    private void markViewerReposted(List<ClubPost> posts, Long memberId) {
        if (memberId == null || posts.isEmpty()) return;
        List<Long> ids = new ArrayList<>();
        for (ClubPost p : posts) if (p.getId() != null && !ids.contains(p.getId())) ids.add(p.getId());
        Set<Long> reposted = clubPostRepostService.selectRepostedPostIds(memberId, ids);
        for (ClubPost p : posts) p.setViewerReposted(reposted.contains(p.getId()));
    }

    /**
     * 转发行 → 动态流条目。原帖已删、所在俱乐部已解散,或查看者现在看不了那个俱乐部(转私密、被移出),
     * 整条略去 —— 不出「原帖不可用」的空壳,也不暗示是哪种原因。每条原帖一份独立对象,标注互不串。
     */
    private List<ClubPostRepostItemVO> repostItems(List<ClubPostRepost> reposts) {
        List<ClubPostRepostItemVO> out = new ArrayList<>();
        if (reposts == null || reposts.isEmpty()) return out;
        List<Long> postIds = new ArrayList<>();
        for (ClubPostRepost r : reposts) if (r.getPostId() != null && !postIds.contains(r.getPostId())) postIds.add(r.getPostId());
        Map<Long, ClubPost> originals = new HashMap<>();
        for (ClubPost p : clubPostRepostService.selectOriginalPosts(postIds)) {
            if (p != null && p.getId() != null) originals.put(p.getId(), p);
        }
        Map<Long, Boolean> readableClub = new HashMap<>();
        Set<Long> used = new java.util.HashSet<>();
        for (ClubPostRepost r : reposts) {
            ClubPost original = originals.get(r.getPostId());
            if (original == null) continue;
            Boolean readable = readableClub.get(original.getClubId());
            if (readable == null) {
                readable = canViewClub(clubService.selectClubById(original.getClubId()));
                readableClub.put(original.getClubId(), readable);
            }
            if (!readable) continue;
            ClubPostRepostItemVO item = new ClubPostRepostItemVO();
            item.setRepostId(r.getId());
            item.setReposterMemberId(r.getMemberId());
            item.setReposterNick(r.getNickname());
            item.setReposterAvatar(r.getAvatar());
            item.setRepostTime(r.getCreateTime());
            // 同一帖被两人转发:第二条起复制一份,免得两条目共用一个对象。
            item.setPost(used.add(original.getId()) ? original : copyPost(original));
            out.add(item);
        }
        return out;
    }

    private ClubPost copyPost(ClubPost src) {
        ClubPost p = new ClubPost();
        org.springframework.beans.BeanUtils.copyProperties(src, p);
        return p;
    }

    /** 帖文按发帖时间、转发按转发时间,统一倒序合并;同一时刻帖文在前。两路各自已倒序。 */
    private List<Object> mergeByTime(List<ClubPost> posts, List<ClubPostRepostItemVO> reposts) {
        List<Object> out = new ArrayList<>(posts.size() + reposts.size());
        int i = 0, j = 0;
        while (i < posts.size() || j < reposts.size()) {
            if (j >= reposts.size()) { out.add(posts.get(i++)); continue; }
            if (i >= posts.size()) { out.add(reposts.get(j++)); continue; }
            Date pt = posts.get(i).getCreateTime(), rt = reposts.get(j).getRepostTime();
            boolean repostFirst = rt != null && (pt == null || rt.after(pt));
            out.add(repostFirst ? reposts.get(j++) : posts.get(i++));
        }
        return out;
    }

    /**
     * 把带引用的帖子投影成前端 variant 判定认得的形状。
     *
     * ★ 两条摘要查询是**批量**的,整页固定两次,不随帖子条数增长 —— 逐条查会变成 N+1。
     * ★ 引用当前不可见(被引对象已删/下架/转私密)时只标 refUnavailable=true,不投影标题与封面;
     *   前端显示中性的「引用不可用」,不暗示是哪一种原因。
     */
    private void projectPostReferences(List<ClubPost> posts) {
        if (posts == null || posts.isEmpty()) return;
        List<Long> activityIds = new ArrayList<>();
        List<Long> topicIds = new ArrayList<>();
        for (ClubPost p : posts) {
            if (p == null || p.getRefType() == null || p.getRefId() == null) continue;
            if (Integer.valueOf(1).equals(p.getRefType())) {
                if (!activityIds.contains(p.getRefId())) activityIds.add(p.getRefId());
            } else if (!topicIds.contains(p.getRefId())) {
                topicIds.add(p.getRefId());
            }
        }
        if (activityIds.isEmpty() && topicIds.isEmpty()) return;

        // F0098:引用卡按匿名口径重判当前可见性(帖子列表是公开接口)。摘要查询本身不看可见性,不能直接拿来公开。
        activityIds.removeIf(id -> !clubPostReferenceGuard.isPubliclyVisible(ClubPostReferenceGuard.REF_ACTIVITY, id));
        topicIds.removeIf(id -> !clubPostReferenceGuard.isPubliclyVisible(ClubPostReferenceGuard.REF_TOPIC, id));

        Map<Long, Map<String, Object>> byActivity = indexSummaries(
                activityIds.isEmpty() ? null : viewCreativeSquareService.selectSportActivitySummaries(activityIds),
                "activityId");
        Map<Long, Map<String, Object>> byTopic = indexSummaries(
                topicIds.isEmpty() ? null : viewCreativeSquareService.selectSportTopicSummaries(topicIds),
                "topicId");

        for (ClubPost p : posts) {
            if (p == null || p.getRefType() == null || p.getRefId() == null) continue;
            boolean isActivity = Integer.valueOf(1).equals(p.getRefType());
            Map<String, Object> summary = isActivity ? byActivity.get(p.getRefId()) : byTopic.get(p.getRefId());
            if (summary == null) {
                p.setRefUnavailable(true);
                continue;
            }
            p.setSportName(summary.get("sportName") == null ? null : String.valueOf(summary.get("sportName")));
            p.setSportCover(summary.get("sportCover") == null ? null : String.valueOf(summary.get("sportCover")));
            p.setSportTopicId(isActivity ? Convert.toLong(summary.get("topicId")) : p.getRefId());
            p.setIsTopicTemplate(!isActivity && Integer.valueOf(1).equals(Convert.toInt(summary.get("isTemplate"))));
        }
    }

    private Map<Long, Map<String, Object>> indexSummaries(List<Map<String, Object>> rows, String key) {
        Map<Long, Map<String, Object>> out = new HashMap<>();
        if (rows == null) return out;
        for (Map<String, Object> row : rows) {
            if (row == null) continue;
            Long id = Convert.toLong(row.get(key));
            if (id != null) out.put(id, row);
        }
        return out;
    }

    /**
     * 聚合动态流。俱乐部页的「帖文」tab 吃这个 —— 在此之前动态只活在单个俱乐部
     * 详情页里,没有跨俱乐部的流,前端想做 feed 只能自己一个个俱乐部拉。
     *
     * 范围按身份给,不是全站公开流:
     *   玩家 → 我加入的俱乐部
     *   商家 → 我合作过(邀约已接受)的俱乐部
     * 一个都没有就返回空列表 —— 那是正常状态,不是错误。全站流会让「关注」
     * 这件事失去意义,也会把没有任何关系的俱乐部内容推给不相干的人。
     */
    @Operation(summary = "聚合动态流(我加入/我合作过的俱乐部)")
    @PostMapping("/post/feed")
    public AjaxResult postFeed(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }

        // limit 是外部输入,边界收在入口这层:解析不了用默认,越界夹到 [1,50]。
        // 放在 service 里会被 controller 测试的 mock 绕过 —— 那道闸测不到就等于没有。
        int limit = 20;
        if (body != null && body.get("limit") != null) {
            try { limit = Integer.parseInt(String.valueOf(body.get("limit")).trim()); } catch (Exception ignored) { }
        }
        if (limit <= 0) limit = 20;
        if (limit > 50) limit = 50;

        Set<Long> scope = new LinkedHashSet<>();
        // 我加入的(含我自己建的 —— 创建者也是成员)
        List<Long> joined = clubMemberService.selectClubIdsByMember(memberId);
        if (joined != null) scope.addAll(joined);
        // 我作为商家谈成过的俱乐部
        CoopInvite iq = new CoopInvite();
        iq.setFromId(memberId);
        iq.setToType("club");
        iq.setStatus(1);
        for (CoopInvite i : coopInviteService.selectCoopInviteList(iq)) {
            if (i.getToId() != null && clubService.selectClubById(i.getToId()) != null) {
                scope.add(i.getToId());
            }
        }

        List<ClubPost> posts = clubPostService.selectFeedByClubIds(new ArrayList<>(scope), limit);
        // dw29 L8:范围内俱乐部成员的转发与帖文按时间合并,整体仍只取 limit 条。
        List<ClubPostRepostItemVO> reposts = repostItems(
                clubPostRepostService.selectFeedReposts(new ArrayList<>(scope), null, null, limit));
        List<ClubPost> all = withRepostOriginals(posts, reposts);
        // 聚合流与俱乐部页是同一批帖子,引用投影也得给 —— 不给的话前端拿不到 sportName,
        // variant 判定恒假,成绩卡/模板卡在达人页永远不出现(俱乐部页里却出现),同一条帖两种样子。
        projectPostReferences(all);
        markViewerReposted(all, memberId);
        Map<Long, Boolean> contentManageByClub = new HashMap<>();
        for (ClubPost p : all) {
            p.setIsLiked(clubPostLikeService.selectByPostAndMember(p.getId(), memberId) != null);
            Boolean canManage = contentManageByClub.get(p.getClubId());
            if (canManage == null) {
                ClubAccessContext access = clubAccessService.resolve(memberId, p.getClubId(), null);
                canManage = access != null && access.hasPermission(ClubPermission.CONTENT_MANAGE);
                contentManageByClub.put(p.getClubId(), canManage);
            }
            p.setViewerCanManage(canManage);
        }
        List<Object> rows = mergeByTime(posts, reposts);
        Map<String, Object> d = new HashMap<>();
        d.put("rows", rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows);
        d.put("clubCount", scope.size());
        return AjaxResult.success(d);
    }

    /** 当前点赞状态快照，不是原写请求的最终回执。沿用动态列表的俱乐部可见性。 */
    @Operation(summary = "读取本人的动态点赞状态")
    @PostMapping("/post/like-state")
    public AjaxResult postLikeState(@RequestBody(required = false) ClubPost body) {
        if (body == null || body.getId() == null || body.getId() <= 0
                || body.getClubId() == null || body.getClubId() <= 0) return error("动态或俱乐部ID不合法");
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId <= 0) return error("请先登录");
        Club club = clubService.selectClubById(body.getClubId());
        if (club == null) return error("俱乐部不存在");
        if (!canViewClub(club)) return error("这个俱乐部只对成员开放");
        ClubPost post = clubPostService.selectClubPostById(body.getId());
        if (post == null || !body.getId().equals(post.getId()) || !body.getClubId().equals(post.getClubId())
                || !Integer.valueOf(0).equals(post.getDelFlag())) return error("动态不存在");
        Map<String, Object> data = new HashMap<>();
        data.put("id", post.getId().toString());
        data.put("clubId", post.getClubId().toString());
        data.put("isLiked", clubPostLikeService.selectByPostAndMember(post.getId(), memberId) != null);
        data.put("likeCount", post.getLikeCount());
        return AjaxResult.success(data);
    }

    @Operation(summary = "动态点赞/取消")
    @PostMapping("/post/like")
    // 锁前的帖子读取不能让锁后的成员与封禁检查沿用旧快照。
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public AjaxResult postLike(@RequestBody ClubPostLike body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getPostId() == null) return error("缺少动态ID");
        ClubPost targetPost = clubPostService.selectClubPostById(body.getPostId());
        if (targetPost == null || Integer.valueOf(2).equals(targetPost.getDelFlag())) {
            return error("动态不存在");
        }
        Club club = clubService.selectClubByIdForUpdate(targetPost.getClubId());
        if (club == null) return error("动态不存在");
        targetPost = clubPostService.selectClubPostByIdForUpdate(body.getPostId());
        if (targetPost == null || Integer.valueOf(2).equals(targetPost.getDelFlag())
                || !club.getId().equals(targetPost.getClubId())) {
            return error("动态不存在");
        }
        if (!canViewClub(club)) return error("这个俱乐部只对成员开放");
        clubParticipationGuard.requireAllowed(club.getId(), memberId);
        // P1-023:带 liked 时按目标态幂等(重试/重复请求不会来回翻转);不带时保持旧切换语义。
        boolean current = clubPostLikeService.selectByPostAndMember(body.getPostId(), memberId) != null;
        boolean liked = body.getLiked() == null ? !current : body.getLiked();
        if (liked != current && !liked) {
            clubPostLikeService.deleteByPostAndMember(body.getPostId(), memberId);
            clubPostService.incrementLikeCount(body.getPostId(), -1);
        } else if (liked != current) {
            ClubPostLike like = new ClubPostLike();
            like.setPostId(body.getPostId());
            like.setMemberId(memberId);
            clubPostLikeService.insertClubPostLike(like);
            clubPostService.incrementLikeCount(body.getPostId(), 1);
        }
        Map<String, Object> d = new HashMap<>();
        d.put("liked", liked);
        return AjaxResult.success(d);
    }

    @Operation(summary = "转发帖文(dw29 L8)")
    @PostMapping("/post/repost")
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public AjaxResult postRepost(@RequestBody(required = false) ClubPostRepost body) {
        return changeRepost(body, true);
    }

    @Operation(summary = "撤销转发(dw29 L8)")
    @PostMapping("/post/repost/undo")
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public AjaxResult postRepostUndo(@RequestBody(required = false) ClubPostRepost body) {
        return changeRepost(body, false);
    }

    /**
     * 转发与点赞同一套闸与锁序:先锁俱乐部、再锁帖文,锁后复核帖文归属与删除态。
     * 转发要求此刻读得到原帖(俱乐部可见 + 未被封禁);转自己的帖允许。
     * 撤销只动本人那一行,不要求原帖仍可读 —— 帖子转私密/被移出后,本人仍能把自己的转发撤掉。
     */
    private AjaxResult changeRepost(ClubPostRepost body, boolean reposted) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (body == null || body.getPostId() == null || body.getPostId() <= 0) return error("缺少动态ID");
        ClubPost targetPost = clubPostService.selectClubPostById(body.getPostId());
        if (targetPost == null) return error("动态不存在");
        if (reposted && !Integer.valueOf(0).equals(targetPost.getDelFlag())) return error("动态不存在");
        Club club = clubService.selectClubByIdForUpdate(targetPost.getClubId());
        if (club == null && reposted) return error("动态不存在");
        targetPost = clubPostService.selectClubPostByIdForUpdate(body.getPostId());
        if (targetPost == null || (club != null && !club.getId().equals(targetPost.getClubId()))) {
            return error("动态不存在");
        }
        if (reposted) {
            if (!Integer.valueOf(0).equals(targetPost.getDelFlag())) return error("动态不存在");
            if (!canViewClub(club)) return error("这个俱乐部只对成员开放");
            clubParticipationGuard.requireAllowed(club.getId(), memberId);
        }
        clubPostRepostService.setReposted(targetPost.getId(), memberId, reposted);
        ClubPost after = clubPostService.selectClubPostById(targetPost.getId());
        Map<String, Object> d = new HashMap<>();
        d.put("reposted", clubPostRepostService.isReposted(targetPost.getId(), memberId));
        d.put("repostCount", after == null || after.getRepostCount() == null ? 0 : after.getRepostCount());
        return AjaxResult.success(d);
    }

    @Operation(summary = "兼容旧成员角色入口(映射为俱乐部运营委派)")
    @PostMapping("/set-member-role")
    public AjaxResult setMemberRole(@RequestBody ClubMember body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getClubId() == null || body.getMemberId() == null || body.getRole() == null) return error("缺少参数");
        if (body.getRole() != 0 && body.getRole() != 1) return error("角色参数不合法");
        try {
            if (body.getRole() == 1) {
                List<ClubRoleAssignment> assignments = clubRoleAssignmentService.list(
                        memberId, body.getClubId(), null);
                ClubRoleAssignment current = findLegacyOperatorAssignment(
                        assignments, body.getClubId(), body.getMemberId());
                // #817↔#845 缝合:治理入口(canGovernClub / detail 的 viewerIsAdmin)读的是
                // club_member.role,只写 assignment 等于设了个不生效的管理员。这里复用后台
                // 同款带上限闸的 Service 同步写 role=1;撤销侧由 revokeLegacyOperator 归 0,
                // 写 role 的口子只有这两个,不打架。上面 list() 已确认调用者是 owner。
                // cap 闸必须先于 assign:两步各自独立提交,若先 assign 再撞上限,会留下
                // 「报错但 assignment 已生效」的持久半态;反过来 role=1 而 assign 失败,
                // 落在 legacy 分支上治理权完整,重试即可补齐 assignment。
                if (!clubMemberService.setMemberRoleWithAdminCap(
                        body.getClubId(), body.getMemberId(), 1)) {
                    return error("管理员最多 2 个，请先取消其他管理员");
                }
                if (current == null) {
                    clubRoleAssignmentService.assign(memberId, body.getClubId(), body.getMemberId(),
                            ClubRoleCode.CLUB_OPERATOR, null, null, null);
                }
                return success("已设为俱乐部运营");
            }
            clubRoleAssignmentService.revokeLegacyOperator(memberId, body.getClubId(),
                    body.getMemberId(), "通过旧成员角色入口撤销", null);
            return success("已取消俱乐部运营");
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }

    private ClubRoleAssignment findLegacyOperatorAssignment(List<ClubRoleAssignment> assignments,
                                                             Long clubId, Long targetMemberId) {
        if (assignments == null) return null;
        for (ClubRoleAssignment assignment : assignments) {
            if (assignment != null && clubId.equals(assignment.getClubId())
                    && targetMemberId.equals(assignment.getTargetMemberId())
                    && ClubRoleCode.CLUB_OPERATOR.equals(assignment.getRoleCode())
                    && ClubRoleAssignment.SCOPE_CLUB.equals(assignment.getScopeType())
                    && clubId.equals(assignment.getScopeId())
                    && ClubRoleAssignment.STATUS_ACTIVE.equals(assignment.getStatus())) {
                return assignment;
            }
        }
        return null;
    }

    @Operation(summary = "发俱乐部动态(成员/群主)")
    @PostMapping("/post/create")
    public AjaxResult postCreate(@RequestBody ClubPost body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getClubId() == null) return error("缺少俱乐部ID");
        try {
            clubParticipationGuard.requireAllowed(body.getClubId(), memberId);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
        if (StringUtils.isEmpty(body.getContent()) && StringUtils.isEmpty(body.getImages())) {
            return error("说点什么,或加张图");
        }
        Club postClub = clubService.selectClubById(body.getClubId());
        if (postClub == null) return error("俱乐部不存在");
        // ★ 2026-09-10 用户裁决:在俱乐部发帖必须是俱乐部成员,商家在俱乐部内部发不了帖。
        // ⚠️ 这道闸此前只在前端(发帖框藏在 canSeeMembers 后面),接口上一个字都没判 ——
        //    任何没被封禁的人直接打这个接口,都能往任意俱乐部发帖。藏入口不是权限。
        //    主理人不在 club_member 里(owner = club.member_id),所以要单独认。
        boolean postOwner = memberId != null && memberId.equals(postClub.getMemberId());
        if (!postOwner && clubMemberService.selectByClubAndMember(body.getClubId(), memberId) == null) {
            return error("加入俱乐部后才能在这里发帖");
        }
        // 稿 M 268:223「允许成员发帖」关掉之后,成员发不了,只剩管理者能发。
        if (Integer.valueOf(0).equals(postClub.getMemberPostAllowed())
                && !clubMemberService.canGovernClub(postClub, memberId)) {
            return error("这个俱乐部当前只有管理员能发帖");
        }
        String memberOpenid = openidOf(memberId);
        String secMsg = wxContentSecurityService.checkText(body.getContent(), memberOpenid);
        if (secMsg != null) return error(secMsg);

        // 引用:1=活动完赛 2=主题/模板(口径同广场帖 dataType)。
        // ⚠️ 只认这两种。3(自由漫游成绩)全仓还没有任何地方写得出来,现在放行等于开一个
        //    没有生产者的入口;前端的 variant 判定已经认得 3,等真有人写再放。
        // F0098:被引对象当前公开可见 + 发帖人本人确有报名成功记录,才算「我的游玩记录」。
        Integer refType = body.getRefType();
        Long refId = body.getRefId();
        if (refType != null || refId != null) {
            if (refType == null || refId == null || refId <= 0) return error("引用信息不完整");
            if (refType != 1 && refType != 2) return error("暂不支持这种引用");
            try {
                clubPostReferenceGuard.requireCitable(memberId, refType, refId);
            } catch (ServiceException e) {
                int code = e.getCode() == null ? 500 : e.getCode();
                return new AjaxResult(code, e.getMessage()).put("errorCode", ClubPostReferenceGuard.errorCodeOf(code));
            }
        }

        ClubPost post = new ClubPost();
        post.setClubId(body.getClubId());
        post.setAuthorMemberId(memberId);
        post.setContent(body.getContent());
        post.setImages(reviewedMedia(memberId, body.getImages(), true));
        post.setRefType(refType);
        post.setRefId(refId);
        post.setType(body.getType() != null ? body.getType() : 0);
        try {
            clubPostService.insertClubPostInActiveClub(post, memberId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
        return AjaxResult.success("已发布");
    }

    @Operation(summary = "编辑动态并保留公开历史")
    @PostMapping("/post/update")
    public AjaxResult postUpdate(@RequestBody ClubPostMutationRequest body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null || body.getId() == null) return error("缺少动态ID");
        if (StringUtils.isEmpty(body.getContent()) && StringUtils.isEmpty(body.getImages())) {
            return error("动态内容不能为空");
        }
        String secMsg = wxContentSecurityService.checkText(body.getContent(), openidOf(memberId));
        if (secMsg != null) return error(secMsg);
        try {
            ClubPostUpdateResult result = clubPostService.updatePost(memberId, body.getId(), body.getContent(),
                    reviewedMedia(memberId, body.getImages(), true), body.getVersion(), body.getRequestId());
            ClubPost updated = result.getPost();
            return AjaxResult.success("已更新", updated);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "置顶或取消置顶公告")
    @PostMapping("/post/pin")
    public AjaxResult postPin(@RequestBody ClubPostMutationRequest body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body == null || body.getId() == null || body.getPinned() == null) return error("缺少参数");
        try {
            ClubPost updated = clubPostService.setPinned(memberId, body.getId(), body.getPinned(),
                    body.getVersion(), body.getRequestId());
            return AjaxResult.success(body.getPinned() ? "已置顶" : "已取消置顶", updated);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "动态公开编辑历史")
    @PostMapping("/post/history")
    public AjaxResult postHistory(@RequestBody ClubPostMutationRequest body) {
        if (body == null || body.getId() == null) return error("缺少动态ID");
        // F0119:历史按「当前」帖子所在俱乐部的可见性投影 —— 转仅成员可见后,旧历史直链同样拒。
        ClubPost historyPost = clubPostService.selectClubPostById(body.getId());
        if (historyPost != null && !canViewClub(clubService.selectClubById(historyPost.getClubId()))) {
            return error("这个俱乐部只对成员开放");
        }
        try {
            return AjaxResult.success(clubPostService.listRevisionHistory(body.getId()));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "动态评论列表(公开)")
    @PostMapping("/post/comment/list")
    public AjaxResult commentList(@RequestBody ClubPostComment body) {
        if (body.getPostId() == null) return error("缺少动态ID");
        ClubPost post = clubPostService.selectClubPostById(body.getPostId());
        if (post == null) return error("动态不存在");
        Club commentClub = clubService.selectClubById(post.getClubId());
        if (commentClub == null) return error("动态不存在");
        if (!canViewClub(commentClub)) return error("这个俱乐部只对成员开放");
        ClubPostComment q = new ClubPostComment();
        q.setPostId(body.getPostId());
        return AjaxResult.success(clubPostCommentService.selectClubPostCommentList(q));
    }

    @Operation(summary = "发表动态评论(成员/群主)")
    @PostMapping("/post/comment/create")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult commentCreate(@RequestBody ClubPostComment body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getPostId() == null) return error("缺少动态ID");
        if (StringUtils.isEmpty(body.getContent())) return error("评论内容不能为空");
        ClubPost post = clubPostService.selectClubPostById(body.getPostId());
        if (post == null || Integer.valueOf(2).equals(post.getDelFlag())) return error("动态不存在");
        try {
            clubParticipationGuard.requireAllowed(post.getClubId(), memberId);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
        String secMsg = wxContentSecurityService.checkText(body.getContent(), openidOf(memberId));
        if (secMsg != null) return error(secMsg);
        Club club = clubService.selectClubByIdForUpdate(post.getClubId());
        if (club == null) return error("俱乐部不存在或已解散");
        post = clubPostService.selectClubPostByIdForUpdate(body.getPostId());
        if (post == null || Integer.valueOf(2).equals(post.getDelFlag())) return error("动态不存在");
        boolean isOwner = OwnershipUtils.isOwner(club.getMemberId(), memberId);
        boolean isMember = clubMemberService.selectByClubAndMember(post.getClubId(), memberId) != null;
        if (!isOwner && !isMember) return error("加入俱乐部后才能评论");

        ClubPostComment c = new ClubPostComment();
        c.setPostId(body.getPostId());
        c.setMemberId(memberId);
        c.setContent(body.getContent());
        clubPostCommentService.insertClubPostComment(c);
        clubPostService.incrementCommentCount(body.getPostId(), 1);
        return AjaxResult.success("已评论");
    }

    @Operation(summary = "删除动态(作者/群主/管理员)")
    @PostMapping("/post/delete")
    public AjaxResult postDelete(@RequestBody ClubPost body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getId() == null) return error("缺少动态ID");
        ClubPost post = clubPostService.selectClubPostById(body.getId());
        if (post == null) return error("动态不存在");
        if (!canModerate(post.getClubId(), post.getAuthorMemberId(), memberId)) return error("无权删除");
        clubPostService.deleteClubPostById(post.getId());
        return AjaxResult.success("已删除");
    }

    @Operation(summary = "删除评论(作者/群主/管理员)")
    @PostMapping("/post/comment/delete")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult commentDelete(@RequestBody ClubPostComment body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getId() == null) return error("缺少评论ID");
        ClubPostComment c = clubPostCommentService.selectClubPostCommentById(body.getId());
        if (c == null || (c.getDelFlag() != null && c.getDelFlag() == 2)) return error("评论不存在");
        ClubPost post = clubPostService.selectClubPostById(c.getPostId());
        Long clubId = post == null ? null : post.getClubId();
        if (!canModerate(clubId, c.getMemberId(), memberId)) return error("无权删除");
        clubPostCommentService.deleteClubPostCommentById(c.getId());
        clubPostService.incrementCommentCount(c.getPostId(), -1);
        return AjaxResult.success("已删除");
    }

    @Operation(summary = "举报动态")
    @PostMapping("/post/report")
    public AjaxResult postReport(@RequestBody ClubPost body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getId() == null) return error("缺少动态ID");
        ClubPost post = clubPostService.selectClubPostById(body.getId());
        if (post == null || (post.getDelFlag() != null && post.getDelFlag() == 2)) return error("动态不存在");
        // 举报统一走 reportContent 进审核队列(与 comment/square 同法);同内容多次举报的幂等汇聚由服务层 reportContent 负责
        try {
            auditTaskService.reportContent("CLUB_POST", post.getId(), memberId, "用户举报");
            return AjaxResult.success("已举报,平台将尽快处理");
        } catch (Exception e) {
            return error("举报失败");
        }
    }

    @Operation(summary = "举报评论")
    @PostMapping("/post/comment/report")
    public AjaxResult commentReport(@RequestBody ClubPostComment body) {
        Long memberId;
        try { memberId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        if (body.getId() == null) return error("缺少评论ID");
        ClubPostComment c = clubPostCommentService.selectClubPostCommentById(body.getId());
        if (c == null || (c.getDelFlag() != null && c.getDelFlag() == 2)) return error("评论不存在");
        // CU-C-138:作者本人不给自己开审核单。前端已按作者隐藏入口,这里兜住绕过页面的直调
        //(与 /post/report 的动态作者闸、治理工单的「不能举报自己」同一口径)。删除入口不受影响。
        if (memberId.equals(c.getMemberId())) return error("不能举报自己的评论");
        try {
            auditTaskService.reportContent("CLUB_POST_COMMENT", c.getId(), memberId, "用户举报");
            return AjaxResult.success("已举报,平台将尽快处理");
        } catch (Exception e) {
            return error("举报失败");
        }
    }

    /**
     * 本团贡献榜。sortBy: composite(综合分,默认) / mileage(里程) / pace(配速,升序)。
     * sortBy 只在 Service 里挑 Comparator、不进 SQL,故不存在注入面。
     *
     * ★2026-09-11 补上成员校验。原注释说「加校验会改变现有可见性」—— 事实相反:
     * 俱乐部详情页的榜单本来就藏在 {@code canSeeMembers} 后面,稿上也写着「加入后可看贡献榜」。
     * 前端遮住了,接口却对任意 clubId 敞着 —— 非成员直调就能拿到全团昵称/头像/memberId/里程。
     * 同一页的成员名单是服务端闸(见 /members),榜单没有,两者口径不一致的那一半才是 bug。
     * 判据与 /members 逐字相同:主理人或在册成员。
     */
    @Operation(summary = "俱乐部本团贡献榜")
    @PostMapping("/leaderboard")
    public AjaxResult leaderboard(@RequestBody Map<String, Object> body) {
        Long clubId = badgeLong(body.get("id"));
        if (clubId == null) return error("缺少俱乐部ID");
        Long viewerId;
        try { viewerId = currentMemberId(); } catch (Exception e) { return error("请先登录"); }
        Club leaderboardClub = clubService.selectClubById(clubId);
        if (leaderboardClub == null) return error("俱乐部不存在");
        boolean boardOwner = OwnershipUtils.isOwner(leaderboardClub.getMemberId(), viewerId);
        boolean boardMember = clubMemberService.selectByClubAndMember(clubId, viewerId) != null;
        if (!boardOwner && !boardMember && !canBrowsePublicRoster(leaderboardClub, viewerId)) {
            return error(isPublicJoinClub(leaderboardClub) ? "加入俱乐部后才能看贡献榜" : "通过审核后可看贡献榜");
        }
        Object sortBy = body.get("sortBy");
        return AjaxResult.success(
                clubMemberService.selectClubLeaderboard(clubId, sortBy == null ? null : sortBy.toString()));
    }
}

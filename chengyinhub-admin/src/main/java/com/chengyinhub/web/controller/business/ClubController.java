package com.chengyinhub.web.controller.business;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.dto.ClubFulfillmentWorkspaceDTO;
import com.chengyinhub.business.domain.vo.ClubMembershipAccessEndResultVO;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubFulfillmentWorkspaceService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubMembershipService;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.utils.poi.ExcelUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 俱乐部管理（后台）
 */
@RestController
@RequestMapping("/business/club")
public class ClubController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ClubController.class);

    @Autowired
    private IClubService clubService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IRoleService roleService;

    @Autowired
    private ICapabilityService capabilityService;

    @Autowired
    private IClubLeaderService clubLeaderService;

    @Autowired
    private IClubFulfillmentWorkspaceService fulfillmentWorkspaceService;

    @Autowired
    private IClubMemberService clubMemberService;

    @Autowired
    private IClubMembershipService clubMembershipService;

    @Autowired
    private IClubRoleAssignmentService clubRoleAssignmentService;

    @Autowired
    private IClubPostService clubPostService;

    @Autowired
    private IAuditTaskService auditTaskService;

    @PreAuthorize("@ss.hasPermi('business:club:list')")
    @GetMapping("/list")
    public TableDataInfo list(Club club) {
        startPage();
        List<Club> list = clubService.selectClubList(club);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:club:export')")
    @Log(title = "俱乐部", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, Club club) {
        List<Club> list = clubService.selectClubList(club);
        ExcelUtil<Club> util = new ExcelUtil<Club>(Club.class);
        util.exportExcel(response, list, "俱乐部数据");
    }

    @PreAuthorize("@ss.hasPermi('business:club:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(clubService.selectClubById(id));
    }

    /**
     * 俱乐部运营工作区基础档案：俱乐部申请 + 主理人能力档案。
     * 跨域的主题、成员和内容列表仍由前端按各自权限调用，避免 club:query 扩大数据可见面。
     */
    @PreAuthorize("@ss.hasPermi('business:club:query')")
    @GetMapping(value = "/{id}/workspace")
    public AjaxResult workspace(@PathVariable("id") Long id) {
        Club club = clubService.selectClubById(id);
        if (club == null) {
            return error("俱乐部不存在");
        }
        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("club", club);
        workspace.put("leader", club.getMemberId() == null
            ? null : clubLeaderService.selectClubLeaderByMemberId(club.getMemberId()));
        return success(workspace);
    }

    /**
     * 俱乐部已承接主题的带队、签到与团核销只读证据。
     * 主题名称属于跨域事实，必须同时拥有俱乐部与主题读取权限。
     */
    @PreAuthorize("@ss.hasPermi('business:club:query') and @ss.hasPermi('business:topic:list')")
    @GetMapping(value = "/{id}/fulfillment")
    public AjaxResult fulfillment(@PathVariable("id") Long id) {
        ClubFulfillmentWorkspaceDTO snapshot = fulfillmentWorkspaceService.getFulfillment(id);
        return snapshot == null ? error("俱乐部不存在") : success(snapshot);
    }

    /**
     * 俱乐部成员与动态只读调证。治理动作仍走下方专用端点；动态处置统一进入审核台。
     */
    @PreAuthorize("@ss.hasPermi('business:club:query')")
    @GetMapping(value = "/{id}/governance")
    public AjaxResult governance(@PathVariable("id") Long id) {
        if (clubService.selectClubById(id) == null) {
            return error("俱乐部不存在");
        }
        ClubMember memberQuery = new ClubMember();
        memberQuery.setClubId(id);
        memberQuery.setStatus(1);
        ClubPost postQuery = new ClubPost();
        postQuery.setClubId(id);
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("members", clubMemberService.selectClubMemberList(memberQuery));
        governance.put("posts", clubPostService.selectClubPostList(postQuery));
        return success(governance);
    }

    /** 平台治理成员角色。管理员上限仍由带 club 行锁的 Service 统一裁决。 */
    @PreAuthorize("@ss.hasPermi('business:club:audit')")
    @Log(title = "俱乐部成员角色", businessType = BusinessType.UPDATE)
    @PutMapping("/{clubId}/members/{memberId}/role")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult updateMemberRole(@PathVariable Long clubId, @PathVariable Long memberId,
                                       @RequestBody ClubMember request) {
        if (request == null || request.getRole() == null
                || (request.getRole() != 0 && request.getRole() != 1)) {
            return error("成员角色仅支持成员或管理员");
        }
        Club club = clubService.selectClubById(clubId);
        if (club == null) {
            return error("俱乐部不存在");
        }
        if (memberId.equals(club.getMemberId())) {
            return error("主理人不是普通成员，不能修改成员角色");
        }
        if (clubMemberService.selectByClubAndMember(clubId, memberId) == null) {
            return error("成员不存在或不属于该俱乐部");
        }
        try {
            if (!clubMemberService.setMemberRoleWithAdminCap(clubId, memberId, request.getRole())) {
                return error("管理员最多 2 个，请先取消其他管理员");
            }
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        clubRoleAssignmentService.setPlatformOperatorRole(
                getUserId(), clubId, memberId, request.getRole() == 1, null);
        return success(request.getRole() == 1 ? "已设为管理员" : "已取消管理员");
    }

    /** 平台移除普通成员；原子维护成员关系与 member_count，主理人不能从此入口移除。 */
    @PreAuthorize("@ss.hasPermi('business:club:audit')")
    @Log(title = "俱乐部成员移除", businessType = BusinessType.DELETE)
    @DeleteMapping("/{clubId}/members/{memberId}")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult removeMember(@PathVariable Long clubId, @PathVariable Long memberId) {
        Club club = clubService.selectClubById(clubId);
        if (club == null) {
            return error("俱乐部不存在");
        }
        if (memberId.equals(club.getMemberId())) {
            return error("主理人不能从成员治理入口移除");
        }
        if (clubMemberService.selectByClubAndMember(clubId, memberId) == null) {
            return error("成员不存在或不属于该俱乐部");
        }
        try {
            int rows = clubMemberService.quitMemberAndLeaveGroup(
                    clubId, memberId, getUserId(), "PLATFORM_ADMIN", "MEMBER_REMOVED_BY_PLATFORM");
            if (rows <= 0) return error("成员状态已变化，请刷新");
            ClubMembershipAccessEndResultVO membershipAccessEnd =
                    clubMembershipService.handleMemberAccessEnded(
                            clubId, memberId, "MEMBER_REMOVED",
                            "platform-club-member-removed-" + clubId + "-" + memberId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("membershipAccessEnd", membershipAccessEnd);
            return AjaxResult.success("已移除成员", result);
        } catch (RuntimeException e) {
            log.error("后台移除俱乐部成员失败，业务关系、角色、会费与 IM 清理已回滚，clubId={}, memberId={}",
                    clubId, memberId, e);
            throw e;
        }
    }

    /**
     * 将动态提交到统一审核台。这里只建审核任务，不在业务页直接改 del_flag。
     */
    @PreAuthorize("@ss.hasPermi('business:club:audit')")
    @Log(title = "俱乐部动态送审", businessType = BusinessType.INSERT)
    @PostMapping("/{clubId}/posts/{postId}/review")
    public AjaxResult submitPostForReview(@PathVariable Long clubId, @PathVariable Long postId) {
        if (clubService.selectClubById(clubId) == null) {
            return error("俱乐部不存在");
        }
        ClubPost post = clubPostService.selectClubPostById(postId);
        if (post == null || post.getClubId() == null || !clubId.equals(post.getClubId())
                || Integer.valueOf(2).equals(post.getDelFlag())) {
            return error("动态不存在或不属于该俱乐部");
        }
        Long taskId = auditTaskService.reportContent(AuditTask.BIZ_CLUB_POST, postId, getUserId(),
            "后台俱乐部工作区发起复核");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        return success(result);
    }

    @PreAuthorize("@ss.hasPermi('business:club:add')")
    @Log(title = "俱乐部", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody Club club) {
        return toAjax(clubService.insertClub(club));
    }

    @PreAuthorize("@ss.hasPermi('business:club:edit')")
    @Log(title = "俱乐部", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Club club) {
        if (club.getId() == null) {
            return error("缺少俱乐部标识");
        }
        String operationConfigError = validateOperationConfig(club);
        if (operationConfigError != null) {
            return error(operationConfigError);
        }
        // 普通编辑只允许维护申请档案。审核、定级、归属和删除状态必须走各自的专用权限入口。
        Club editable = new Club();
        editable.setId(club.getId());
        editable.setName(club.getName());
        editable.setLogo(club.getLogo());
        editable.setCover(club.getCover());
        editable.setDescription(club.getDescription());
        editable.setClubTypes(club.getClubTypes());
        editable.setActivityPrefs(club.getActivityPrefs());
        editable.setExperience(club.getExperience());
        editable.setJoinReason(club.getJoinReason());
        editable.setCaseImages(club.getCaseImages());
        editable.setClubType(club.getClubType());
        editable.setCity(club.getCity());
        editable.setKeywords(club.getKeywords());
        editable.setStyle(club.getStyle());
        editable.setMaxEventSize(club.getMaxEventSize());
        editable.setAvgEventSize(club.getAvgEventSize());
        editable.setCanDesignRoute(club.getCanDesignRoute());
        editable.setCanDesignTask(club.getCanDesignTask());
        editable.setCanNpc(club.getCanNpc());
        editable.setCanMerchantCoop(club.getCanMerchantCoop());
        editable.setHasGuideCert(club.getHasGuideCert());
        editable.setCertImages(club.getCertImages());
        editable.setCategoryId(club.getCategoryId());
        editable.setAddress(club.getAddress());
        editable.setLeaderName(club.getLeaderName());
        editable.setPhone(club.getPhone());
        if (Boolean.TRUE.equals(club.getOperationConfigUpdated())) {
            editable.setOperationConfigUpdated(true);
            editable.setPrioritySignupEnabled(club.getPrioritySignupEnabled());
            editable.setMemberDiscountPrice(club.getMemberDiscountPrice());
            editable.setMemberReservedQuota(club.getMemberReservedQuota());
        }
        if (!hasEditableProfileField(editable)) {
            return error("没有可更新的俱乐部档案字段");
        }
        return toAjax(clubService.updateClub(editable));
    }

    private boolean hasEditableProfileField(Club club) {
        return club.getName() != null || club.getLogo() != null || club.getCover() != null
            || club.getDescription() != null || club.getClubTypes() != null || club.getActivityPrefs() != null
            || club.getExperience() != null || club.getJoinReason() != null || club.getCaseImages() != null
            || club.getClubType() != null || club.getCity() != null || club.getKeywords() != null
            || club.getStyle() != null || club.getMaxEventSize() != null || club.getAvgEventSize() != null
            || club.getCanDesignRoute() != null || club.getCanDesignTask() != null || club.getCanNpc() != null
            || club.getCanMerchantCoop() != null || club.getHasGuideCert() != null || club.getCertImages() != null
            || club.getCategoryId() != null || club.getAddress() != null || club.getLeaderName() != null
            || club.getPhone() != null || Boolean.TRUE.equals(club.getOperationConfigUpdated());
    }

    private String validateOperationConfig(Club club) {
        if (!Boolean.TRUE.equals(club.getOperationConfigUpdated())) {
            return null;
        }
        Integer priority = club.getPrioritySignupEnabled();
        if (priority == null || (priority != 0 && priority != 1)) {
            return "成员优先报名仅支持开启或关闭";
        }
        if (club.getMemberReservedQuota() == null || club.getMemberReservedQuota() < 0) {
            return "成员保留名额不能小于 0";
        }
        if (club.getMemberDiscountPrice() != null
                && club.getMemberDiscountPrice().compareTo(java.math.BigDecimal.ZERO) < 0) {
            return "成员优惠价不能小于 0";
        }
        if (club.getMemberDiscountPrice() != null && club.getMemberDiscountPrice().scale() > 2) {
            return "成员优惠价最多保留两位小数";
        }
        return null;
    }

    /**
     * 审核：status 1通过 / 2驳回。通过则把拥有者会员 role 置为 club（仍是玩家，userType 不变）。
     */
    @PreAuthorize("@ss.hasPermi('business:club:audit')")
    @Log(title = "俱乐部审核", businessType = BusinessType.UPDATE)
    @PutMapping("/audit")
    public AjaxResult audit(@RequestBody Club club) {
        if (club.getId() == null || club.getStatus() == null) {
            return error("缺少参数");
        }
        if (club.getStatus() != 1L && club.getStatus() != 2L) {
            return error("审核状态仅支持通过或驳回");
        }
        if (club.getStatus() == 2L && StringUtils.isBlank(club.getRejectReason())) {
            return error("请填写驳回原因");
        }
        Club db = clubService.selectClubById(club.getId());
        if (db == null) {
            return error("俱乐部不存在");
        }
        // 账户互斥(2026-07-05 恢复严格分离):已是商户者不能审核为俱乐部主理人。
        if (club.getStatus() == 1L && capabilityService.hasCapability(db.getMemberId(), MemberCapability.CAP_MERCHANT)) {
            return error("该会员已是商户,一个账户不能同时是商户与主理人");
        }
        db.setStatus(club.getStatus());
        db.setRejectReason(club.getRejectReason());
        clubService.updateClub(db);

        if (club.getStatus() == 1L) {
            // P1 能力模型:授予 club_leader 能力(幂等);role 列双写作默认视角(过渡期保留)
            capabilityService.grant(db.getMemberId(), MemberCapability.CAP_CLUB_LEADER, getUserId());
            UmsMember m = new UmsMember();
            m.setId(db.getMemberId());
            m.setRole(IRoleService.ROLE_CLUB); // 通过 → 默认视角(玩家身份保留，userType 不动)
            umsMemberService.updateUmsMember(m);
        } else if (club.getStatus() == 2L) {
            // 驳回 → 仅当该主理人名下已无其它在营(status=1)俱乐部时,才回收 club 能力
            // (多俱乐部:下架其中一个团不应连锅端掉整个主理人能力)
            boolean hasActiveClub = false;
            for (Club c : clubService.selectClubsByMemberId(db.getMemberId())) {
                if (c.getStatus() != null && c.getStatus() == 1L) { hasActiveClub = true; break; }
            }
            if (!hasActiveClub) {
                capabilityService.revoke(db.getMemberId(), MemberCapability.CAP_CLUB_LEADER);
                UmsMember owner = umsMemberService.selectUmsMemberById(db.getMemberId());
                if (owner != null && IRoleService.ROLE_CLUB.equals(roleService.resolveRole(owner))) {
                    UmsMember m = new UmsMember();
                    m.setId(db.getMemberId());
                    m.setRole(IRoleService.ROLE_PLAYER); // 默认视角降回玩家
                    umsMemberService.updateUmsMember(m);
                }
            }
        }
        return toAjax(1);
    }

    /**
     * 主理人定级(D15/Option B):对 club_leader 写 level/tags + 审计;不再做 role 升降(role 已在 apply 授予)。
     * 复用 business:club:audit 权限,无需新菜单。入参带 id 或 memberId 定位主理人档案。
     */
    @PreAuthorize("@ss.hasPermi('business:club:audit')")
    @Log(title = "主理人定级", businessType = BusinessType.UPDATE)
    @PutMapping("/leader-audit")
    public AjaxResult leaderAudit(@RequestBody ClubLeader body) {
        if (body.getId() == null && body.getMemberId() == null) {
            return error("缺少主理人标识");
        }
        if (body.getLevel() == null || body.getLevel() < 1 || body.getLevel() > 5) {
            return error("主理人等级必须为 L1-L5");
        }
        ClubLeader leader = body.getId() != null
            ? clubLeaderService.selectClubLeaderById(body.getId())
            : clubLeaderService.selectClubLeaderByMemberId(body.getMemberId());
        if (leader == null) {
            return error("主理人档案不存在");
        }
        ClubLeader upd = new ClubLeader();
        upd.setId(leader.getId());
        upd.setLevel(body.getLevel());
        upd.setTags(body.getTags());
        upd.setAuditBy(getUsername());
        upd.setAuditTime(DateUtils.getNowDate());
        return toAjax(clubLeaderService.updateClubLeader(upd));
    }

    @PreAuthorize("@ss.hasPermi('business:club:remove')")
    @Log(title = "俱乐部", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult remove(@PathVariable Long[] ids) {
        LinkedHashSet<Long> existingIds = new LinkedHashSet<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id != null && clubService.selectClubById(id) != null) existingIds.add(id);
            }
        }
        int rows = clubService.deleteClubByIds(ids);
        for (Long id : existingIds) {
            clubMembershipService.handleClubAccessEnded(
                    id, "CLUB_DISSOLVED", "platform-club-dissolved-" + id);
        }
        return toAjax(rows);
    }
}

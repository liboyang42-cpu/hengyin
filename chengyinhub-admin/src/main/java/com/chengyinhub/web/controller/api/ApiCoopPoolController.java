package com.chengyinhub.web.controller.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopClubApply;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopClubApplyService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.chengyinhub.common.utils.OwnershipUtils;
/**
 * 俱乐部承接合作池(P0-4 开放范围·「开放报名」承接路径)。
 *
 * 链路:商家发布时开放(cms_topic.open_club_pool=1)→ 俱乐部在本池申请(/apply)→
 * 商家在候选池(/api/coop/candidates)婉拒(/decline)或向该俱乐部发 type1 带条款邀约(现成 /api/coop/invite)→
 * 俱乐部接受(含保证金)→ acceptInviteWithLock 生成 coop_order。申请本身不成单(§3.6:
 * 条款随邀约走,定价权归发布者、对方有否决权,报名时点报价信息还不存在)。
 */
@RestController
@Tag(name = "俱乐部承接合作池")
@RequestMapping("/api/coop/pool")
public class ApiCoopPoolController extends BaseController {

    /** 合作池单次返回上限:开放主题量级尚小,先内存过滤 + 截断,量起来再下推 SQL 分页 */
    private static final int POOL_LIMIT = 50;

    @Autowired
    private ICoopClubApplyService coopClubApplyService;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ICoopInviteService coopInviteService;

    @Autowired
    private IClubService clubService;

    @Autowired
    private INotifyService notifyService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    @Autowired
    private com.chengyinhub.business.service.support.ClubCoopWindowPolicy clubCoopWindowPolicy;

    @Operation(summary = "合作池:可承接主题列表(俱乐部视角)")
    @PostMapping("/list")
    public AjaxResult list(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }

        // 我的生效俱乐部(玩家也能看池,但状态派生只对主理人有意义)
        Set<Long> myClubIds = new HashSet<>();
        List<Club> myClubs = clubService.selectClubsByMemberId(memberId);
        if (myClubs != null) {
            for (Club c : myClubs) {
                if (c != null && c.getId() != null && c.getStatus() != null && c.getStatus() == 1L) {
                    myClubIds.add(c.getId());
                }
            }
        }
        // 我的俱乐部发出的申请:topicId → 申请行
        Map<Long, CoopClubApply> myApplyByTopic = new HashMap<>();
        if (!myClubIds.isEmpty()) {
            for (CoopClubApply a : coopClubApplyService.selectByClubIds(new ArrayList<>(myClubIds))) {
                if (a != null && a.getTopicId() != null && !myApplyByTopic.containsKey(a.getTopicId())) {
                    myApplyByTopic.put(a.getTopicId(), a);
                }
            }
        }

        CmsTopic probe = new CmsTopic();
        probe.setOpenClubPool(1);
        probe.setStatus(1);
        probe.setUserStatus(1);
        probe.setDelFlag(0);
        List<CmsTopic> topics = cmsTopicService.selectCmsTopicList(probe);
        java.util.Date now = com.chengyinhub.common.utils.DateUtils.getNowDate();

        List<Map<String, Object>> rows = new ArrayList<>();
        if (topics != null) {
            for (CmsTopic t : topics) {
                if (rows.size() >= POOL_LIMIT) break;
                if (t == null || t.getId() == null) continue;
                if (t.getMemberId() == null || OwnershipUtils.isOwner(t.getMemberId(), memberId)) continue; // 归属不明/我发布的不进池
                if (t.getClubId() != null) continue; // 俱乐部主题不进池(§9.2/9.3 只覆盖玩家/商家发布;其三态按对手方=商家派生)
                if (com.chengyinhub.business.domain.enums.TopicLifecycleState
                        .commercialTermsFrozen(t.getLifecycle())) continue;               // 已锁价关门
                if (!clubCoopWindowPolicy.isOpen(t, now)) continue;                    // F04 与申请/邀约同一招商期
                rows.add(poolRow(t, myClubIds, myApplyByTopic.get(t.getId())));
            }
        }
        Map<String, Object> d = new HashMap<>();
        d.put("rows", rows);
        d.put("hasClub", !myClubIds.isEmpty());
        return AjaxResult.success(d);
    }

    /** 单行装配:主题摘要 + 俱乐部视角承接状态(open/applied/invited/taken/cooped/declined/withdrawn) */
    private Map<String, Object> poolRow(CmsTopic t, Set<Long> myClubIds, CoopClubApply myApply) {
        Map<String, Object> m = new HashMap<>();
        m.put("topicId", t.getId());
        m.put("name", t.getName());
        m.put("cover", t.getImgUrl());
        m.put("subtitle", t.getSubtitle());
        m.put("startDate", t.getStartDate());
        m.put("endDate", t.getEndDate());
        m.put("recruitDeadline", t.getRecruitDeadline());
        m.put("merchantNick", nickOf(t.getMemberId()));

        // 该主题的合作现状(每主题一查:池量小,见 POOL_LIMIT 注释)
        CoopInvite q = new CoopInvite();
        q.setTopicId(t.getId());
        List<CoopInvite> invites = coopInviteService.selectCoopInviteList(q);
        boolean acceptedByOthers = false;
        boolean acceptedByMine = false;
        boolean pendingInviteToMine = false;
        if (invites != null) {
            for (CoopInvite i : invites) {
                if (!"club".equals(i.getToType()) || i.getStatus() == null) continue;
                boolean mine = i.getToId() != null && myClubIds.contains(i.getToId());
                if (i.getStatus() == 1) {
                    if (mine) acceptedByMine = true; else acceptedByOthers = true;
                } else if (i.getStatus() == 0 && mine) {
                    pendingInviteToMine = true;
                }
            }
        }

        String state;
        if (acceptedByMine) state = "cooped";
        else if (acceptedByOthers) state = "taken";
        // invited 先于 applied:申请后商家回邀约时申请行仍是待处理,若 applied 优先,
        // 「商家确认」这一跳(去接受邀约)俱乐部在池里永远看不见
        else if (pendingInviteToMine) state = "invited";
        else if (myApply != null && myApply.getStatus() != null && myApply.getStatus() == 0) state = "applied";
        else if (myApply != null && myApply.getStatus() != null && myApply.getStatus() == 1) state = "declined";
        else if (myApply != null && myApply.getStatus() != null && myApply.getStatus() == 2) state = "withdrawn";
        // 3 = 已转为邀约:主办方同意了,球在俱乐部这边(去邀约列表确认条款)。
        // ⚠️ 少这一条会掉进下面的 else 显示成「可申请」—— 明明已经被通过了,却像没人理,
        // 而且会诱导他再申请一次。上面 invited 优先那条防的是同一类病。
        else if (myApply != null && myApply.getStatus() != null && myApply.getStatus() == 3) state = "converted";
        else state = "open";
        m.put("state", state);
        if (myApply != null) m.put("applyId", myApply.getId());
        return m;
    }

    @Operation(summary = "发起承接申请(俱乐部主理人)")
    @PostMapping("/apply")
    public AjaxResult apply(@RequestBody Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        Long topicId = toLong(body == null ? null : body.get("topicId"));
        if (topicId == null) return error("缺少主题");
        Long clubId = toLong(body.get("clubId"));
        String message = body.get("message") == null ? null : String.valueOf(body.get("message"));

        CoopClubApply row;
        try {
            row = coopClubApplyService.apply(topicId, memberId, clubId, message);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        // 订阅消息:通知主题发布者收到承接申请(best-effort,未授权/未配模板静默跳过)
        try {
            CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
            if (topic != null && topic.getMemberId() != null) {
                notifyService.notifyCoopStatus(topic.getMemberId(),
                        topic.getName() == null ? "" : topic.getName(),
                        clubNameOf(row.getClubId()), "承接申请", "俱乐部申请承接,请到候选池处理");
            }
        } catch (Exception ignore) {}
        Map<String, Object> d = new HashMap<>();
        d.put("applyId", row.getId());
        return AjaxResult.success("已申请,等商家确认", d);
    }

    @Operation(summary = "撤回承接申请(俱乐部)")
    @PostMapping("/withdraw")
    public AjaxResult withdraw(@RequestBody Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        Long topicId = toLong(body == null ? null : body.get("topicId"));
        if (topicId == null) return error("缺少主题");
        try {
            coopClubApplyService.withdraw(topicId, memberId);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        return AjaxResult.success("已撤回");
    }

    @Operation(summary = "婉拒承接申请(主题发布者)")
    @PostMapping("/decline")
    public AjaxResult decline(@RequestBody Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        Long applyId = toLong(body == null ? null : body.get("applyId"));
        if (applyId == null) return error("缺少申请");
        Long memberId = principalMemberId;
        try {
            String scope = body == null || body.get("scope") == null
                    ? null : String.valueOf(body.get("scope"));
            if (MerchantOperationScope.isMerchant(scope)) {
                memberId = merchantAccessService.require(
                        principalMemberId, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId();
            }
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        CoopClubApply declined;
        try {
            declined = coopClubApplyService.decline(applyId, memberId);
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        // 订阅消息:通知申请方主理人被婉拒(best-effort)
        try {
            if (declined.getMemberId() != null) {
                notifyService.notifyCoopStatus(declined.getMemberId(),
                        topicNameOf(declined.getTopicId()), nickOf(memberId), "已婉拒", "商家婉拒了本次承接申请");
            }
        } catch (Exception ignore) {}
        return AjaxResult.success("已婉拒");
    }

    /**
     * 发布者「收到的」:别人对我发布的主题提交的俱乐部申请,跨主题聚合、含已处理/已锁价历史。
     *
     * <p>归属闸 = SQL join cms_topic.member_id。口径与 /decline 对齐:登录人本人发布的主题,
     * 加上(登录人有商家 PROJECT_MANAGE 权限时)商家 owner 发布的主题 —— 后者行上带 scope=MERCHANT,
     * 前端原样回传给 /decline 与带条款邀约,员工账号才处理得了 owner 主题上的申请。
     * 显式传 scope=MERCHANT 时只看 owner 那一份(无权限直接透出后端错误)。
     * <p>§3.8:只下发俱乐部名称/Logo 与申请留言,不下发主理人联系方式 —— 联系方式只在邀约接受后给。
     */
    @Operation(summary = "发布者收到的俱乐部承接申请(跨主题)")
    @PostMapping("/received")
    public AjaxResult received(@RequestBody(required = false) Map<String, Object> body) {
        Long principalMemberId;
        try { principalMemberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        String scope = body == null || body.get("scope") == null ? null : String.valueOf(body.get("scope"));
        Long ownerMemberId = null;
        try {
            ownerMemberId = merchantAccessService.require(
                    principalMemberId, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId();
        } catch (ServiceException e) {
            // 不是商家成员:只看本人发布的主题;但显式要商家视角时必须把拒绝透出去,不能静默降级成个人视角
            if (MerchantOperationScope.isMerchant(scope)) return new AjaxResult(e.getCode(), e.getMessage());
        }
        Map<Long, CmsTopic> topics = new HashMap<>();
        Map<Long, Club> clubs = new HashMap<>();
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        if (!MerchantOperationScope.isMerchant(scope)) collectReceived(principalMemberId, null, byId, topics, clubs);
        if (ownerMemberId != null && !ownerMemberId.equals(principalMemberId)) {
            collectReceived(ownerMemberId, MerchantOperationScope.MERCHANT, byId, topics, clubs);
        } else if (ownerMemberId != null && MerchantOperationScope.isMerchant(scope)) {
            collectReceived(ownerMemberId, null, byId, topics, clubs);
        }
        return AjaxResult.success(new ArrayList<>(byId.values()));
    }

    /**
     * 申请人「我发出的」:我提交的俱乐部承接申请,含已处理/已锁价历史。
     *
     * <p>不复用 /list:那里只列仍开放、未锁价的主题且截断 50,锁价后申请会从发件箱消失。
     * 口径 = member_id 是我(申请人)∪ club_id 是我现在的生效俱乐部(主理人转让后新主理人也看得见)。
     */
    @Operation(summary = "我发出的俱乐部承接申请")
    @PostMapping("/mine")
    public AjaxResult mine(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        List<Long> myClubIds = new ArrayList<>();
        List<Club> myClubs = clubService.selectClubsByMemberId(memberId);
        if (myClubs != null) {
            for (Club c : myClubs) {
                if (c != null && c.getId() != null && c.getStatus() != null && c.getStatus() == 1L) myClubIds.add(c.getId());
            }
        }
        Map<Long, CoopClubApply> byId = new LinkedHashMap<>();
        for (CoopClubApply a : coopClubApplyService.selectByApplicant(memberId)) byId.put(a.getId(), a);
        for (CoopClubApply a : coopClubApplyService.selectByClubIds(myClubIds)) byId.putIfAbsent(a.getId(), a);
        Map<Long, CmsTopic> topics = new HashMap<>();
        Map<Long, Club> clubs = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<CoopClubApply> applies = new ArrayList<>(byId.values());
        applies.sort(java.util.Comparator.comparing(CoopClubApply::getCreateTime,
                java.util.Comparator.nullsLast(java.util.Comparator.<java.util.Date>reverseOrder())));
        for (CoopClubApply a : applies) {
            CmsTopic topic = cached(topics, a.getTopicId(), cmsTopicService::selectCmsTopicById);
            Map<String, Object> row = applyRow(a, topic);
            Club club = cached(clubs, a.getClubId(), clubService::selectClubById);
            row.put("clubName", club == null || club.getName() == null ? "" : club.getName());
            row.put("merchantNick", topic == null ? "" : nickOf(topic.getMemberId()));
            // /withdraw 按「申请人第一个生效俱乐部」定位申请(CoopClubApplyServiceImpl.resolveMyActiveClub),
            // 已转让出去的俱乐部或第二个俱乐部上的申请撤不了 —— 不给按钮,别让人点了必失败
            row.put("canWithdraw", a.getStatus() != null && a.getStatus() == 0
                    && !myClubIds.isEmpty() && myClubIds.get(0).equals(a.getClubId()));
            rows.add(row);
        }
        return AjaxResult.success(rows);
    }

    private void collectReceived(Long ownerId, String scope, Map<Long, Map<String, Object>> byId,
                                 Map<Long, CmsTopic> topics, Map<Long, Club> clubs) {
        for (CoopClubApply a : coopClubApplyService.selectByTopicOwner(ownerId)) {
            if (byId.containsKey(a.getId())) continue;
            Club club = cached(clubs, a.getClubId(), clubService::selectClubById);
            if (club == null) continue; // 父俱乐部已解散:行留审计,不再作为可处理记录展示(同 /candidates)
            Map<String, Object> row = applyRow(a, cached(topics, a.getTopicId(), cmsTopicService::selectCmsTopicById));
            row.put("clubName", club.getName() == null ? "" : club.getName());
            row.put("clubLogo", club.getLogo());
            if (scope != null) row.put("scope", scope);
            byId.put(a.getId(), row);
        }
    }

    /** 两个收发件箱共用的行形状:申请事实 + 主题摘要。不含任何联系方式。 */
    private Map<String, Object> applyRow(CoopClubApply a, CmsTopic topic) {
        Map<String, Object> m = new HashMap<>();
        m.put("applyId", a.getId());
        m.put("topicId", a.getTopicId());
        m.put("clubId", a.getClubId());
        m.put("status", a.getStatus());
        m.put("message", a.getMessage());
        m.put("createTime", a.getCreateTime());
        m.put("handleTime", a.getHandleTime());
        m.put("topicName", topic == null || topic.getName() == null ? "" : topic.getName());
        m.put("topicCover", topic == null ? null : topic.getImgUrl());
        m.put("startDate", topic == null ? null : topic.getStartDate());
        return m;
    }

    private static <T> T cached(Map<Long, T> cache, Long id, java.util.function.Function<Long, T> load) {
        if (id == null) return null;
        if (!cache.containsKey(id)) cache.put(id, load.apply(id));
        return cache.get(id);
    }

    private String nickOf(Long memberId) {
        try {
            if (memberId == null) return "";
            UmsMember m = umsMemberService.selectUmsMemberById(memberId);
            return m == null || m.getNickname() == null ? "" : m.getNickname();
        } catch (Exception e) { return ""; }
    }

    private String clubNameOf(Long clubId) {
        try {
            if (clubId == null) return "";
            Club c = clubService.selectClubById(clubId);
            return c == null || c.getName() == null ? "" : c.getName();
        } catch (Exception e) { return ""; }
    }

    private String topicNameOf(Long topicId) {
        try {
            if (topicId == null) return "";
            CmsTopic t = cmsTopicService.selectCmsTopicById(topicId);
            return t == null || t.getName() == null ? "" : t.getName();
        } catch (Exception e) { return ""; }
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }
}

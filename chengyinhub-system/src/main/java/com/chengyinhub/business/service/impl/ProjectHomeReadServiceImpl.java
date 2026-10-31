package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.domain.ViewRegistrationMerchant;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMerchantRegistrationDetailReadService;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IProjectHomeReadService;
import com.chengyinhub.common.constant.RecruitmentWindowPolicy;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProjectHomeReadServiceImpl implements IProjectHomeReadService {

    /** 邀约状态:0 待确认 1 已接受。其余(拒绝/取消/顶替/过期)不算合作方。 */
    private static final int INVITE_PENDING = 0;
    private static final int INVITE_ACCEPTED = 1;

    private final ICmsTopicService topicService;
    private final ICmsTopicNodeService topicNodeService;
    private final ICmsRegistrationMerchantService registrationMerchantService;
    private final ICmsRegistrationService registrationService;
    private final ICoopInviteService coopInviteService;
    private final IClubService clubService;
    private final IMmsMerchantService merchantService;
    private final IMerchantRegistrationDetailReadService detailReadService;
    private final IMerchantChapterApplicationService merchantChapterApplicationService;
    /** 只读「开了商家承接的站有几个」给工作台的「招商」按钮定灰;缺席时不下发该字段,前端按可用处理(真闸在写接口)。 */
    @Autowired(required = false)
    private CmsTopicChapterMapper cmsTopicChapterMapper;

    public ProjectHomeReadServiceImpl(ICmsTopicService topicService,
                                      ICmsTopicNodeService topicNodeService,
                                      ICmsRegistrationMerchantService registrationMerchantService,
                                      ICmsRegistrationService registrationService,
                                      ICoopInviteService coopInviteService,
                                      IClubService clubService,
                                      IMmsMerchantService merchantService,
                                      IMerchantRegistrationDetailReadService detailReadService,
                                      IMerchantChapterApplicationService merchantChapterApplicationService) {
        this.topicService = topicService;
        this.topicNodeService = topicNodeService;
        this.registrationMerchantService = registrationMerchantService;
        this.registrationService = registrationService;
        this.coopInviteService = coopInviteService;
        this.clubService = clubService;
        this.merchantService = merchantService;
        this.detailReadService = detailReadService;
        this.merchantChapterApplicationService = merchantChapterApplicationService;
    }

    @Override
    public Map<String, Object> getProjectHome(Long memberId, Long topicId) {
        if (topicId == null || topicId <= 0L) {
            throw new RuntimeException("缺少主题");
        }
        CmsTopic topic = topicService.selectCmsTopicById(topicId);
        if (topic == null) {
            throw new RuntimeException("主题不存在");
        }

        boolean isHost = topic.getMemberId() != null && topic.getMemberId().equals(memberId);
        List<CmsRegistrationMerchant> myRegistrations = findMyRegistrations(memberId, topicId);
        CmsRegistrationMerchant myRegistration = myRegistrations.isEmpty() ? null : myRegistrations.get(0);
        List<MerchantChapterApplication> myChapterApplications = findActiveChapterApplications(memberId, topic);
        CoopInvite acceptedInvite = findAcceptedMerchantInvite(memberId, topicId);
        if (!isHost && myRegistration == null && myChapterApplications.isEmpty() && acceptedInvite == null) {
            // 浏览态不走这个接口:项目主页是「管理页」,外人看主题走原有的主题详情。
            throw new RuntimeException("你既不是该主题的发布者,也没有承接记录");
        }

        Map<String, Object> out = new HashMap<>();
        // 同时是发布者和承接方时按 host —— 他自己接的那一站作为 host 页里的一块给出。
        out.put("role", isHost ? "host" : "join");
        out.put("topic", mapTopic(topic));
        if (isHost) {
            out.put("host", buildHost(topic));
        }
        if (myRegistration != null) {
            Map<String, Object> join = buildJoin(memberId, topic, myRegistration, myRegistrations);
            if (!myChapterApplications.isEmpty()) {
                join.put("chapterApplications", myChapterApplications);
            }
            putHostInvite(join, acceptedInvite);
            out.put("join", join);
        } else if (!myChapterApplications.isEmpty()) {
            Map<String, Object> join = new HashMap<>();
            join.put("chapterApplications", myChapterApplications);
            putHostInvite(join, acceptedInvite);
            out.put("join", join);
        } else if (!isHost && acceptedInvite != null) {
            // 只接了合作邀约、还没有自己的站点:他也是这条路线的承接方,给承接视图,
            // 但点位/章节一条都不编 —— 邀约落的是商务条款,站点要靠他另外报名或申请章节才有。
            out.put("join", buildCoopOnlyJoin(topicId, acceptedInvite));
        }
        return out;
    }

    /**
     * 「已接受的协作邀约」这层关系。判据是接受动作真正落的那行邀约
     * (受邀方=商家、状态=已接受),不是前端传来的标志。
     *
     * <p>type0 邀约经 {@code CoopInviteServiceImpl.acceptInviteWithLock} 落的是
     * coop_invite.status=1 + 一张 coop_order(商务条款),**从不建 cms_registration_merchant**
     * —— 见 ApiRegistrationController 里 D22 那段:邀约带的是条款,报名行要的是履约细节。
     * 所以这里只能认出「他是合作方」,认不出「他接了哪一站」。
     */
    private CoopInvite findAcceptedMerchantInvite(Long memberId, Long topicId) {
        if (memberId == null) {
            return null;
        }
        CoopInvite query = new CoopInvite();
        query.setTopicId(topicId);
        query.setToType("merchant");
        query.setToId(memberId);
        query.setStatus(INVITE_ACCEPTED);
        List<CoopInvite> invites = coopInviteService.selectCoopInviteList(query);
        if (invites == null) {
            return null;
        }
        for (CoopInvite invite : invites) {
            // 历史商家节点邀约(inviteType=2)只读:它对任何人都不再构成合作关系,
            // 与 selectEffectiveOrdersByTopicId 排除它的口径一致。
            if (invite != null && invite.getId() != null
                    && !Integer.valueOf(CoopInvite.TYPE_LEGACY_MERCHANT_NODE).equals(invite.getInviteType())) {
                return invite;
            }
        }
        return null;
    }

    /** 走查 v2 R15(业主 2026-10-30):承办视图底部「联系主办」复用 /api/coop/contact,它要已接受的那张邀约。
        没有已接受邀约(还在报名 / 待确认)时不给,前端就不出这个按钮。 */
    private static void putHostInvite(Map<String, Object> join, CoopInvite acceptedInvite) {
        if (acceptedInvite != null) {
            join.put("hostInviteId", acceptedInvite.getId());
        }
    }

    private Map<String, Object> buildCoopOnlyJoin(Long topicId, CoopInvite acceptedInvite) {
        Map<String, Object> join = new HashMap<>();
        Map<String, Object> invite = new HashMap<>();
        invite.put("inviteId", acceptedInvite.getId());
        invite.put("fromId", acceptedInvite.getFromId());
        invite.put("terms", termsOf(acceptedInvite));
        // 日期在服务端定格式:塞进 Map 的 Date 走不到 @JsonFormat(那是字段上的注解),
        // 到了前端就是一串毫秒数。
        invite.put("handleTime", acceptedInvite.getHandleTime() == null ? null
                : acceptedInvite.getHandleTime().toInstant().atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .toLocalDate().toString());
        join.put("coopInvite", invite);
        join.put("clubs", buildJoinClubs(topicId));
        return join;
    }

    private List<MerchantChapterApplication> findActiveChapterApplications(Long memberId, CmsTopic topic) {
        List<MerchantChapterApplication> mine = merchantChapterApplicationService.selectMine(memberId);
        List<MerchantChapterApplication> active = new ArrayList<>();
        boolean offerCanStillBeCompleted = canCompleteApprovedOffer(topic, DateUtils.getNowDate());
        for (MerchantChapterApplication application
                : mine == null ? new ArrayList<MerchantChapterApplication>() : mine) {
            if (application != null
                    && topic.getId().equals(application.getTopicId())
                    && Integer.valueOf(MerchantChapterApplication.STATUS_APPROVED).equals(application.getStatus())
                    && (Boolean.TRUE.equals(application.getOfferActive()) || offerCanStillBeCompleted)) {
                active.add(application);
            }
        }
        return active;
    }

    /** 已通过但尚未形成 offer 的申请，只在仍能真实调用 enroll 的上架、未锁价招商窗内充当项目关系。 */
    private boolean canCompleteApprovedOffer(CmsTopic topic, Date now) {
        if (!Integer.valueOf(1).equals(topic.getStatus())
                || !Integer.valueOf(1).equals(topic.getUserStatus())
                || TopicLifecycleState.commercialTermsFrozen(topic.getLifecycle())) {
            return false;
        }
        try {
            RecruitmentWindowPolicy.assertOpen(topic.getLifecycle(), topic.getStatus(), topic.getUserStatus(), topic.getRecruitDeadline(), now);
            return true;
        } catch (IllegalStateException closed) {
            return false;
        }
    }

    // ==================== host ====================

    private Map<String, Object> buildHost(CmsTopic topic) {
        List<CoopInvite> invites = livingInvites(topic.getId());

        Map<String, Object> host = new HashMap<>();
        // 主办方身份:俱乐部主办的主题不需要「俱乐部」协作区块(俱乐部不邀俱乐部,
        // 俱乐部管理人属后续需求),前端按此隐藏;商家/个人主办照常。
        host.put("ownerType", topic.getClubId() != null ? "club" : "merchant");
        host.put("recruit", buildRecruit(topic, invites));
        host.put("clubs", buildHostClubs(invites));
        host.put("merchants", buildHostMerchants(topic));
        host.put("players", buildPlayers(topic.getId()));
        // 2026-10-31 业主:俱乐部 / 商家招商在发布之后做,发布时的开关决定工作台能用什么 —— 关着的那颗按钮灰掉。
        // 城市定向看公开承接池;自由探索看有没有开了商家承接的站(章节 recruit_enabled)。
        host.put("openClubPool", topic.getOpenClubPool() != null && topic.getOpenClubPool() == 1);
        Integer recruitChapters = countRecruitChapters(topic.getId());
        if (recruitChapters != null) host.put("recruitChapterCount", recruitChapters);
        // 主页入口与编辑/保存共用现有编辑窗口，避免主办方点击后才发现内容已冻结。
        try {
            com.chengyinhub.business.util.TopicEditWindow.Scope scope =
                    com.chengyinhub.business.util.TopicEditWindow.resolve(topic,
                            topicService.selectTopicIsDraft(topic.getId()), DateUtils.getNowDate());
            boolean canEdit = scope != com.chengyinhub.business.util.TopicEditWindow.Scope.SELLING_FROZEN;
            host.put("canEdit", canEdit);
            host.put("editScope", scope.name());
            host.put("editReason", canEdit ? null : "主题已开售，创作者不能修改已冻结的内容与条款");
        } catch (IllegalStateException closed) {
            host.put("canEdit", Boolean.FALSE);
            host.put("editReason", closed.getMessage());
        }
        return host;
    }

    private Integer countRecruitChapters(Long topicId) {
        if (cmsTopicChapterMapper == null || topicId == null) return null;
        CmsTopicChapter q = new CmsTopicChapter();
        q.setTopicId(topicId);
        int n = 0;
        for (CmsTopicChapter c : cmsTopicChapterMapper.selectCmsTopicChapterList(q)) {
            if (c != null && c.getRecruitEnabled() != null && c.getRecruitEnabled() == 1) n++;
        }
        return n;
    }

    /** 招商进度:有几个站、几个站已经有人接、还有几家在等确认。 */
    private Map<String, Object> buildRecruit(CmsTopic topic, List<CoopInvite> invites) {
        CmsTopicNode nodeQuery = new CmsTopicNode();
        nodeQuery.setTopicId(topic.getId());
        List<CmsTopicNode> nodes = topicNodeService.selectCmsTopicNodeList(nodeQuery);
        int nodeTotal = nodes == null ? 0 : nodes.size();

        CmsRegistrationMerchant rq = new CmsRegistrationMerchant();
        rq.setTopicId(topic.getId());
        rq.setDelFlag(0);
        List<CmsRegistrationMerchant> registrations = registrationMerchantService.selectCmsRegistrationMerchantList(rq);

        Set<Long> filledNodes = new HashSet<>();
        int pendingRegistrations = 0;
        for (CmsRegistrationMerchant r : registrations == null ? new ArrayList<CmsRegistrationMerchant>() : registrations) {
            long status = r.getStatus() == null ? 0L : r.getStatus();
            if (status == 1L && r.getNodeId() != null) {
                filledNodes.add(r.getNodeId());
            } else if (status == 0L) {
                pendingRegistrations++;
            }
        }
        if (Integer.valueOf(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            List<MerchantChapterApplication> applications = merchantChapterApplicationService.selectForTopic(
                    topic.getId(), topic.getMemberId());
            for (MerchantChapterApplication application : applications == null
                    ? new ArrayList<MerchantChapterApplication>() : applications) {
                if (Integer.valueOf(MerchantChapterApplication.STATUS_PENDING).equals(application.getStatus())) {
                    pendingRegistrations++;
                }
                if (!Integer.valueOf(MerchantChapterApplication.STATUS_APPROVED).equals(application.getStatus())
                        || !Boolean.TRUE.equals(application.getOfferActive())) continue;
                for (CmsTopicNode node : nodes == null ? new ArrayList<CmsTopicNode>() : nodes) {
                    if (node.getId() != null && Integer.valueOf(1).equals(node.getNodeAuditStatus())
                            && application.getChapterId().equals(node.getChapterId())
                            && application.getMerchantId().equals(node.getMerchantMemberId())) {
                        filledNodes.add(node.getId());
                    }
                }
            }
        }
        int pendingInvites = 0;
        for (CoopInvite i : invites) {
            if (i.getStatus() != null && i.getStatus() == INVITE_PENDING) {
                pendingInvites++;
            }
        }

        List<Map<String, Object>> openNodes = new ArrayList<>();
        for (CmsTopicNode n : nodes == null ? new ArrayList<CmsTopicNode>() : nodes) {
            if (n.getId() != null && !filledNodes.contains(n.getId())) {
                Map<String, Object> row = new HashMap<>();
                row.put("nodeId", n.getId());
                row.put("nodeName", n.getName());
                openNodes.add(row);
            }
        }

        Map<String, Object> recruit = new HashMap<>();
        recruit.put("nodeTotal", nodeTotal);
        recruit.put("nodeFilled", filledNodes.size());
        recruit.put("pendingCount", pendingRegistrations + pendingInvites);
        recruit.put("openNodes", openNodes);
        return recruit;
    }

    /** 主办方看得到合作俱乐部的联系方式 —— 是他自己招来的人。 */
    private List<Map<String, Object>> buildHostClubs(List<CoopInvite> invites) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (CoopInvite i : invites) {
            if (!"club".equals(i.getToType()) || i.getToId() == null || !seen.add(i.getToId())) {
                continue;
            }
            Club club = clubService.selectClubById(i.getToId());
            Map<String, Object> row = new HashMap<>();
            row.put("clubId", i.getToId());
            row.put("name", club == null ? "" : club.getName());
            row.put("logo", club == null ? null : club.getLogo());
            row.put("leaderName", club == null ? null : club.getLeaderName());
            row.put("phone", club == null ? null : club.getPhone());
            row.put("status", i.getStatus());
            row.put("terms", termsOf(i));
            out.add(row);
        }
        return out;
    }

    /** 主办方看得到承接商家的联系方式 —— 履约要靠他去催。 */
    private List<Map<String, Object>> buildHostMerchants(CmsTopic topic) {
        CmsRegistrationMerchant rq = new CmsRegistrationMerchant();
        rq.setTopicId(topic.getId());
        rq.setDelFlag(0);
        List<CmsRegistrationMerchant> registrations = registrationMerchantService.selectCmsRegistrationMerchantList(rq);

        List<Map<String, Object>> out = new ArrayList<>();
        for (CmsRegistrationMerchant r : registrations == null ? new ArrayList<CmsRegistrationMerchant>() : registrations) {
            MmsMerchant m = r.getMemberId() == null ? null
                    : merchantService.selectMmsMerchantByMemberId(r.getMemberId());
            CmsTopicNode node = r.getNodeId() == null || r.getNodeId() == 0L ? null
                    : topicNodeService.selectCmsTopicNodeById(r.getNodeId());

            Map<String, Object> row = new HashMap<>();
            row.put("registrationId", r.getId());
            row.put("memberId", r.getMemberId());
            row.put("name", m == null ? r.getAddressName() : m.getName());
            row.put("logo", m == null ? null : m.getLogo());
            row.put("phone", m == null ? null : m.getPhone());
            row.put("nodeName", node == null ? null : node.getName());
            row.put("status", r.getStatus());
            row.put("auditStatus", r.getAuditStatus());
            // 2026-08-05 起 registration/merchant/create 已加自接闸,新报名不会再出现自己接自己。
            // 这个标记留着是为了**存量**:闸之前落库的自接记录仍在,前端要能标出来,
            // 别让它混在别人的报名里看不出。
            row.put("isSelf", r.getMemberId() != null && r.getMemberId().equals(topic.getMemberId()));
            out.add(row);
        }
        return out;
    }

    /**
     * 玩家侧只给「卖了多少、核销了多少」这类聚合量。
     * 姓名手机一律不给:玩家跟主办方之间没有需要电话联系的契约,核销靠扫码。
     */
    private Map<String, Object> buildPlayers(Long topicId) {
        CmsRegistration probe = new CmsRegistration();
        probe.setPaymentStatus(2);
        probe.setOwnerType(1L);
        probe.setOwnerId(topicId);
        List<CmsRegistration> paid = registrationService.selectCmsRegistrationList(probe);

        Map<String, Object> players = new HashMap<>();
        players.put("paidCount", paid == null ? 0 : paid.size());
        return players;
    }

    // ==================== join ====================

    private Map<String, Object> buildJoin(Long memberId, CmsTopic topic, CmsRegistrationMerchant registration,
                                          List<CmsRegistrationMerchant> allMine) {
        Map<String, Object> join = new HashMap<>();
        ViewRegistrationMerchant detail = detailReadService.getRegistrationDetail(memberId, registration.getId());
        join.put("registration", detail);
        // 承接多站时的全量。registration 保留为第一条,老前端不受影响;
        // 想看全部的走 registrations —— 少了它,第二站往后在页面上根本不存在(2026-08-07 现网已有 2 例)。
        List<ViewRegistrationMerchant> all = new ArrayList<>();
        for (CmsRegistrationMerchant r : allMine) {
            ViewRegistrationMerchant d = detailReadService.getRegistrationDetail(memberId, r.getId());
            if (d != null) {
                all.add(d);
            }
        }
        join.put("registrations", all);
        join.put("clubs", buildJoinClubs(topic.getId()));

        boolean started = hasStarted(topic);
        long status = registration.getStatus() == null ? 0L : registration.getStatus();
        boolean canEdit = !started && (status == 0L || status == 2L);
        join.put("canEdit", canEdit);
        join.put("editReason", canEdit ? null
                : (started ? "主题已开始,承接内容不能再修改" : "该报名已通过审核,内容不能再修改"));
        return join;
    }

    /**
     * 承接商家只看得到「已经谈成的」俱乐部 —— 待确认的邀约还不是合作关系,
     * 这时候把对方电话给出去等于把还没答应的人的号码泄出去。
     * 同场其他商家一律不给(2026-08-04 用户决策)。
     */
    private List<Map<String, Object>> buildJoinClubs(Long topicId) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (CoopInvite i : livingInvites(topicId)) {
            if (!"club".equals(i.getToType()) || i.getToId() == null) {
                continue;
            }
            if (i.getStatus() == null || i.getStatus() != INVITE_ACCEPTED || !seen.add(i.getToId())) {
                continue;
            }
            Club club = clubService.selectClubById(i.getToId());
            Map<String, Object> row = new HashMap<>();
            row.put("clubId", i.getToId());
            row.put("name", club == null ? "" : club.getName());
            row.put("logo", club == null ? null : club.getLogo());
            row.put("leaderName", club == null ? null : club.getLeaderName());
            row.put("phone", club == null ? null : club.getPhone());
            out.add(row);
        }
        return out;
    }

    // ==================== 公共 ====================

    /**
     * 我在这个主题下的**全部**承接记录。
     *
     * <p>2026-08-07:PR #585 放开「一个商家可承接同一路线多站」后,原来那句 {@code list.get(0)}
     * 就成了现网 bug —— 查生产库,memberId=100096 在 topic 19 与 21 各有 2 条承接记录,
     * 项目主页只显示第一站,另一站在页面上**根本不存在**,且没有任何报错。
     *
     * <p>返回顺序保持 mapper 的原始顺序,第一条仍是原来那条,以免下游「主记录」语义漂移。
     */
    private List<CmsRegistrationMerchant> findMyRegistrations(Long memberId, Long topicId) {
        CmsRegistrationMerchant probe = new CmsRegistrationMerchant();
        probe.setMemberId(memberId);
        probe.setTopicId(topicId);
        probe.setDelFlag(0);
        List<CmsRegistrationMerchant> list = registrationMerchantService.selectCmsRegistrationMerchantList(probe);
        return list == null ? new ArrayList<CmsRegistrationMerchant>() : list;
    }

    /** 只有待确认和已接受算「还活着」;拒绝/取消/顶替/过期都不是合作方。 */
    private List<CoopInvite> livingInvites(Long topicId) {
        CoopInvite q = new CoopInvite();
        q.setTopicId(topicId);
        List<CoopInvite> all = coopInviteService.selectCoopInviteList(q);
        List<CoopInvite> out = new ArrayList<>();
        for (CoopInvite i : all == null ? new ArrayList<CoopInvite>() : all) {
            if (i.getStatus() != null && (i.getStatus() == INVITE_PENDING || i.getStatus() == INVITE_ACCEPTED)) {
                out.add(i);
            }
        }
        return out;
    }

    /** 营销类型三档:0 引流 / 1 分成 / 2 固定。 */
    private String termsOf(CoopInvite i) {
        Integer mode = i.getShareMode();
        if (mode == null) {
            return "";
        }
        if (mode == 1) {
            return "分成 " + (i.getShareRate() == null ? "?" : i.getShareRate()) + "%";
        }
        if (mode == 2) {
            return "固定 ¥" + (i.getFixedFee() == null ? "?" : i.getFixedFee()) + "/人";
        }
        return "引流型";
    }

    private Map<String, Object> mapTopic(CmsTopic topic) {
        Map<String, Object> t = new HashMap<>();
        t.put("id", topic.getId());
        t.put("name", topic.getName());
        t.put("subtitle", topic.getSubtitle());
        t.put("imgUrl", topic.getImgUrl());
        t.put("startDate", topic.getStartDate());
        t.put("endDate", topic.getEndDate());
        t.put("productType", topic.getProductType());
        t.put("circleThemeCode", topic.getCircleThemeCode());
        t.put("circleReviewedAt", topic.getCircleReviewedAt());
        t.put("status", topic.getStatus());
        t.put("lifecycle", topic.getLifecycle());
        t.put("userStatus", topic.getUserStatus());
        t.put("started", hasStarted(topic));
        return t;
    }

    /** 与报名编辑闸同一口径:开始当天 00:00 起就算已开始。 */
    private boolean hasStarted(CmsTopic topic) {
        Date start = topic.getStartDate();
        if (start == null) {
            return false;
        }
        return !new Date().before(DateUtils.convertToStartOfDay(start));
    }
}

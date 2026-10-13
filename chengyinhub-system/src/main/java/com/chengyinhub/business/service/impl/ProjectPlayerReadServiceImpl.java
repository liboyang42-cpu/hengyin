package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IProjectPlayerReadService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IShopDayMerchantRosterService;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.constant.ProductType;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.MerchantCustomerQuery;
import com.chengyinhub.business.domain.vo.ClubCustomerVO;
import com.chengyinhub.business.service.IClubCrmAppService;
import com.chengyinhub.business.service.ICmsActivityService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProjectPlayerReadServiceImpl implements IProjectPlayerReadService {

    private static final Logger log = LoggerFactory.getLogger(ProjectPlayerReadServiceImpl.class);
    private static final String SIGNUP_DATA_SHARING_DOC = "activity_host_data_sharing";
    private static final String SIGNUP_DATA_SHARING_SCENE = "activity_signup";

    private final ICmsTopicService topicService;
    private final ICmsRegistrationService registrationService;
    private final ICmsRegistrationMerchantService registrationMerchantService;
    private final ICoopInviteService coopInviteService;
    private final IUmsMemberService memberService;

    @Autowired
    private IConsentRecordService consentRecordService;

    @Autowired
    private ComplianceProperties complianceProperties;

    // 字段注入而不是进构造器:构造器签名是既有测试的接缝,加参数会把它们全打断
    @Autowired(required = false)
    private ICmsActivityService activityService;

    @Autowired(required = false)
    private IShopDayMerchantRosterService shopDayMerchantRosterService;

    /* CU-C-67:俱乐部视角的主题客户读数(与 /api/club/crm/topic-customers 同一份实现)。
       选填注入:拿不到就不走俱乐部分支,不影响现有的商家/个人主办路径。 */
    @Autowired(required = false)
    private IClubCrmAppService clubCrmAppService;

    /* 稿 471:6605 的客户抽屉要分四档:已售 / 待核销 / 已接洽 / 已核销。
       「已接洽」不是新状态,是一直存在却从没被单独说出来的那一档 ——
       玩家已经扫过码、等商家确认(cms_registration_scan.status=0)。
       原来它和「还没来」一起被印成「待核销」,商家分不出「该去催」和「人已经在店里了」。
       商家工作台 todo-summary 早就在数这一档(pendingScanConfirm),两处同源。 */
    @Autowired(required = false)
    private com.chengyinhub.business.service.ICmsRegistrationScanService registrationScanService;

    public ProjectPlayerReadServiceImpl(ICmsTopicService topicService,
                                        ICmsRegistrationService registrationService,
                                        ICmsRegistrationMerchantService registrationMerchantService,
                                        ICoopInviteService coopInviteService,
                                        IUmsMemberService memberService) {
        this.topicService = topicService;
        this.registrationService = registrationService;
        this.registrationMerchantService = registrationMerchantService;
        this.coopInviteService = coopInviteService;
        this.memberService = memberService;
    }

    @Override
    public Map<String, Object> listPlayers(Long memberId, Long topicId) {
        return listPlayers(memberId, topicId, false);
    }

    @Override
    public Map<String, Object> listPlayers(Long memberId, Long topicId,
                                           boolean sensitiveContactVisible) {
        if (topicId == null || topicId <= 0L) {
            throw new RuntimeException("缺少主题");
        }
        CmsTopic topic = topicService.selectCmsTopicById(topicId);
        if (topic == null) {
            throw new RuntimeException("主题不存在");
        }

        if (Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            /* CU-C-67:俱乐部自己发布的探店日主题,这份名单不是「探店日门店名单」——那是商家口径。
               主理人从前一律被转进商家 roster,必然撞「无权查看该探店日门店名单」(P1 走查实证),
               而俱乐部侧本来就有同源的客户读数。这里改走它,并整形回本方法的 roster 契约,
               前端那张客户抽屉不用按身份分两套渲染。
               只对「主题创建者 + 主题挂在俱乐部上」放行,岗位权限仍由俱乐部服务自己校(它校不过会
               抛自己的错,而不是被当成商家身份拒掉)。 */
            if (clubCrmAppService != null
                    && topic.getClubId() != null && topic.getClubId() > 0L
                    && memberId != null && memberId.equals(topic.getMemberId())) {
                return clubTopicRoster(memberId, topic.getClubId(), topicId);
            }
            if (shopDayMerchantRosterService == null) {
                throw new RuntimeException("探店日商家名单服务未就绪");
            }
            return shopDayMerchantRosterService.list(memberId, topicId, sensitiveContactVisible);
        }

        boolean isHost = topic.getMemberId() != null && topic.getMemberId().equals(memberId);
        if (!isHost && !hasRegistration(memberId, topicId)) {
            throw new RuntimeException("你既不是该主题的发布者,也没有承接记录");
        }

        // ★ 联系方式的可见范围(2026-08-05 用户二次拍板)。判据是「谁组织、谁负责联系」,
        //   不是「谁级别高」:
        //     有俱乐部带团 → 都不给。玩家是俱乐部带来的,绕过组织方直接联系会把关系搞乱,
        //                    要联系走俱乐部负责人。
        //     没有俱乐部   → 主办方和承接商家都给。没有组织方时,谁履约谁就得能联系上人。
        //   判在服务端,前端不做二次过滤 —— 前端过滤等于把号码先发出去再假装没发。
        boolean hasOrganizer = hasOrganizerClub(topic);
        boolean contactVisible = sensitiveContactVisible && !hasOrganizer;

        // 已支付(2)与已退款(4)一起取。
        // ★ 原来只取 2,退款的玩家直接从名单里消失 —— 对商家来说「昨天还在、今天没了」
        //   和「退款了」是两件完全不同的事,前者只会让人以为自己看错了。钱退了要看得见,
        //   而不是把人抹掉。到店/待核销的口径不变:退款的人不进这两档。
        CmsRegistration paidProbe = new CmsRegistration();
        paidProbe.setOwnerType(1L);
        paidProbe.setOwnerId(topicId);
        paidProbe.setPaymentStatus(2);
        List<CmsRegistration> paid = registrationService.selectCmsRegistrationList(paidProbe);

        CmsRegistration refundedProbe = new CmsRegistration();
        refundedProbe.setOwnerType(1L);
        refundedProbe.setOwnerId(topicId);
        refundedProbe.setPaymentStatus(4);
        List<CmsRegistration> refundedList = registrationService.selectCmsRegistrationList(refundedProbe);

        List<CmsRegistration> all = new ArrayList<>();
        if (paid != null) all.addAll(paid);
        if (refundedList != null) all.addAll(refundedList);

        /* 待商家确认的扫码:registrationId 集合。取不到就当没有(这一档退回「待核销」),
           不因为一个附加维度读不出来就让整张名单打不开。 */
        java.util.Set<Long> scanPendingIds = new java.util.HashSet<>();
        if (registrationScanService != null) {
            try {
                com.chengyinhub.business.domain.CmsRegistrationScan scanProbe =
                        new com.chengyinhub.business.domain.CmsRegistrationScan();
                scanProbe.setStatus(0L);
                List<com.chengyinhub.business.domain.CmsRegistrationScan> pendingScans =
                        registrationScanService.selectCmsRegistrationScanList(scanProbe);
                if (pendingScans != null) {
                    for (com.chengyinhub.business.domain.CmsRegistrationScan sc : pendingScans) {
                        if (sc.getRegistrationId() != null) scanPendingIds.add(sc.getRegistrationId());
                    }
                }
            } catch (Exception ignored) {
                // 读不到就是没有这一档,名单照出
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int arrived = 0, refunded = 0, paidTotal = 0, contacted = 0;
        for (CmsRegistration r : all) {
            boolean isRefunded = r.getPaymentStatus() != null && r.getPaymentStatus() == 4;
            boolean verified = !isRefunded && r.getVerificationStatus() != null && r.getVerificationStatus() == 1;
            boolean contactedRow = !isRefunded && !verified && r.getId() != null
                    && scanPendingIds.contains(r.getId());
            if (isRefunded) {
                refunded++;
            } else {
                paidTotal++;
                if (verified) arrived++;
                else if (contactedRow) contacted++;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("registrationId", r.getId());
            row.put("ticketId", r.getTicketId());
            row.put("ticketName", r.getTicketName());
            row.put("ticketNo", r.getRegistrationNo());
            row.put("participateDate", r.getParticipateDate());
            // state 是这一行的真实状态,arrived 只留给旧调用方作布尔兼容
            row.put("state", isRefunded ? "refunded"
                    : (verified ? "arrived" : (contactedRow ? "contacted" : "pending")));
            row.put("arrived", verified);
            row.put("arrivedTime", verified ? r.getVerificationTime() : null);
            row.put("avatar", avatarOf(r.getMemberId()));
            // 姓名一律给全 —— 掩码已去掉(2026-08-05 用户定):
            // 给一个可复制的完整电话、同时把名字打码,是自欺欺人的隐私剧场。
            // 真正的闸是「电话给不给」这个二元判断,不是给一半。
            // 名字本身要给:到店叫人得叫得出名字,这跟能不能打电话是两件事。
            row.put("name", nameOf(r));
            row.put("phone", contactVisible && hasCurrentDataSharingConsent(r.getMemberId())
                    ? r.getPhone() : null);
            rows.add(row);
        }

        // paidCount 仍然只算真付了钱的:退款的人不该把「卖了多少」撑大
        Map<String, Object> summary = new HashMap<>();
        summary.put("paidCount", paidTotal);
        summary.put("arrivedCount", arrived);
        // 待核销 = 付了钱、没核销、也还没扫过码。扫过码等确认的那批单独算「已接洽」。
        summary.put("pendingCount", paidTotal - arrived - contacted);
        summary.put("contactedCount", contacted);
        summary.put("refundedCount", refunded);

        Map<String, Object> out = new HashMap<>();
        out.put("rows", rows);
        out.put("summary", summary);
        out.put("contactVisible", contactVisible);
        // 不能只给一个 false 就完事:商家会以为是 bug。说清为什么、以及该找谁。
        out.put("contactHint", contactVisible ? null
                : (!sensitiveContactVisible ? "当前账号无查看联系方式权限"
                : "这条路线由俱乐部带团,玩家联系方式请找俱乐部负责人"));
        return out;
    }

    /**
     * CU-C-67:俱乐部主理人看自己主题的客户名单。
     *
     * <p>探店日主题原来是「一律转商家 roster」,俱乐部身份必然被<br>shop_day 那套
     * 「本店 live offer + 商家主体」挡在外面。实测就是 500「无权查看该探店日门店名单」。
     *
     * <p>这里不另写一套查询:直接用俱乐部 CRM 的主题客户读数(与
     * {@code /api/club/crm/topic-customers} 同一份实现、同一道岗位权限闸),
     * 只把结果整形回本方法对外的 roster 契约({@code rows} / {@code summary}),
     * 前端那张客户抽屉就不用按身份分两套渲染。
     */
    private Map<String, Object> clubTopicRoster(Long memberId, Long clubId, Long topicId) {
        ClubCustomerVO.TopicPage page = clubCrmAppService.topicCustomers(memberId, clubId, topicId, null);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (page != null && page.getSessions() != null) {
            for (ClubCustomerVO.Session session : page.getSessions()) {
                if (session == null || session.getRows() == null) continue;
                for (ClubCustomerVO.TopicRow row : session.getRows()) {
                    if (row == null) continue;
                    String state = clubRowState(row.getStatusCode());
                    Map<String, Object> out = new HashMap<>();
                    out.put("registrationId", registrationIdOf(row.getKey()));
                    out.put("name", row.getDisplayName());
                    out.put("avatar", row.getAvatar());
                    out.put("state", state);
                    out.put("arrived", "arrived".equals(state));
                    // 场次就是分组头:前端按 sessionLabel 分队,没有就归到「未指定场次」
                    out.put("sessionLabel", session.getTimeText());
                    // 联系方式:俱乐部侧下发的是服务端渲染好的 phoneText(脱敏串或替代说明),
                    // 抳进会带「复制」钮的 phone 字段只会让人复制到一串打不通的字。不拿下。
                    out.put("phone", null);
                    rows.add(out);
                }
            }
        }
        Map<String, Object> summary = new HashMap<>();
        summary.put("paidCount", page == null || page.getSoldCount() == null ? 0 : page.getSoldCount());
        summary.put("pendingCount", page == null || page.getPendingCount() == null ? 0 : page.getPendingCount());
        summary.put("contactedCount", page == null || page.getContactedCount() == null ? 0 : page.getContactedCount());
        summary.put("arrivedCount", page == null || page.getVerifiedCount() == null ? 0 : page.getVerifiedCount());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("viewType", "CLUB_TOPIC_ROSTER");
        out.put("rows", rows);
        out.put("summary", summary);
        out.put("contactVisible", false);
        out.put("contactHint", "联系方式按俱乐部岗位权限在俱乐部客户页查看");
        return out;
    }

    /** 俱乐部侧的五档状态码 → 本接口行的 state。与 ClubCrmAppServiceImpl 的产出对齐。 */
    private static String clubRowState(String statusCode) {
        if ("REFUNDED".equals(statusCode)) return "refunded";
        if ("CONTACTED".equals(statusCode)) return "contacted";
        if ("VERIFIED".equals(statusCode) || "REVIEWED".equals(statusCode)) return "arrived";
        return "pending";
    }

    /** 俱乐部行的 key 形如 {@code reg-<id>}。拿不到数字就给 null,不编一个 id 出来。 */
    private static Long registrationIdOf(String key) {
        if (key == null || !key.startsWith("reg-")) return null;
        try {
            return Long.valueOf(key.substring(4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean hasCurrentDataSharingConsent(Long playerMemberId) {
        String currentVersion = complianceProperties == null
                ? null : complianceProperties.versionOf(SIGNUP_DATA_SHARING_DOC);
        if (currentVersion == null || currentVersion.trim().length() == 0 || consentRecordService == null) {
            log.error("报名信息单独同意校验未就绪，拒绝下发手机号，memberId={}", playerMemberId);
            return false;
        }
        try {
            return consentRecordService.hasCurrentAgreement(playerMemberId, SIGNUP_DATA_SHARING_DOC,
                    SIGNUP_DATA_SHARING_SCENE, currentVersion);
        } catch (RuntimeException e) {
            log.error("报名信息单独同意读取失败，拒绝下发手机号，memberId={}", playerMemberId, e);
            return false;
        }
    }

    private boolean hasRegistration(Long memberId, Long topicId) {
        CmsRegistrationMerchant probe = new CmsRegistrationMerchant();
        probe.setMemberId(memberId);
        probe.setTopicId(topicId);
        probe.setDelFlag(0);
        probe.setAuditStatus(1L);
        List<CmsRegistrationMerchant> list = registrationMerchantService.selectCmsRegistrationMerchantList(probe);
        return list != null && !list.isEmpty();
    }

    /**
     * 这条路线有没有俱乐部在组织。**两条来源都要看**:
     *   ① 已接受的 to_type=club 邀约(商家/玩家发的主题请俱乐部带团)
     *   ② cms_topic.club_id(俱乐部自己发的主题,挂靠即成立,不会有邀约)
     *
     * ⚠️ 只看①会漏掉②:俱乐部自办的主题没有任何邀约,判成「没有俱乐部」⇒
     * 把玩家电话发给承接商家,而那种情况下俱乐部就是组织方。
     *
     * ⚠️ 这里的口径**故意与结算侧不同**。结算(CmsTopicServiceImpl:838)只认
     * CoopOrder,明确不看 club_id —— 因为它要的是「谁该拿 LEAD_FEE」,挂靠不产生
     * 合作单也就不产生分账。而这里要的是「玩家归谁组织」,挂靠就已经归了。
     * 两个问题不同,口径不该强行统一。
     */
    private boolean hasOrganizerClub(CmsTopic topic) {
        if (topic.getClubId() != null && topic.getClubId() != 0L) {
            return true;
        }
        CoopInvite q = new CoopInvite();
        q.setTopicId(topic.getId());
        q.setToType("club");
        q.setStatus(1);
        List<CoopInvite> list = coopInviteService.selectCoopInviteList(q);
        return list != null && !list.isEmpty();
    }

    private String nameOf(CmsRegistration r) {
        if (r.getRealName() != null && r.getRealName().trim().length() > 0) {
            return r.getRealName().trim();
        }
        UmsMember m = r.getMemberId() == null ? null : memberService.selectUmsMemberById(r.getMemberId());
        return m == null || m.getNickname() == null ? "" : m.getNickname();
    }

    private String avatarOf(Long memberId) {
        UmsMember m = memberId == null ? null : memberService.selectUmsMemberById(memberId);
        return m == null ? null : m.getAvatar();
    }

    // ===================== 商家客户名册(跨项目按人归拢)=====================

    @Override
    public Map<String, Object> listMerchantCustomers(Long memberId, MerchantCustomerQuery query,
                                                     boolean sensitiveContactVisible) {
        MerchantCustomerQuery q = query == null ? new MerchantCustomerQuery() : query;
        String keyword = q.safeKeyword();
        // 商家能看到的项目范围,与工作台待办同口径:
        //   ① 我中标承接的主题(auditStatus=1);一商家在同一主题承接多章是常态,必须按 topicId 去重
        //   ② 我自己发布的活动
        LinkedHashSet<Long> wonTopicIds = new LinkedHashSet<>();
        CmsRegistrationMerchant mProbe = new CmsRegistrationMerchant();
        mProbe.setMemberId(memberId);
        mProbe.setAuditStatus(1L);
        mProbe.setDelFlag(0);
        List<CmsRegistrationMerchant> won = registrationMerchantService.selectCmsRegistrationMerchantList(mProbe);
        if (won != null) {
            for (CmsRegistrationMerchant w : won) {
                if (w.getTopicId() != null) wonTopicIds.add(w.getTopicId());
            }
        }
        // 我主办的主题也算 —— 自己发的主题,客户当然是我的
        CmsTopic topicProbe = new CmsTopic();
        topicProbe.setMemberId(memberId);
        topicProbe.setDelFlag(0);
        List<CmsTopic> myTopics = topicService.selectCmsTopicList(topicProbe);
        if (myTopics != null) {
            for (CmsTopic t : myTopics) {
                if (t.getId() != null) wonTopicIds.add(t.getId());
            }
        }

        Map<Long, Customer> byMember = new LinkedHashMap<>();
        for (Long topicId : wonTopicIds) {
            // 联系方式按项目判:同一个人可能既来过我自办的场(能给电话),
            // 又来过俱乐部带团的场(不能给)。只要有一个场是我负责联系的,就给。
            CmsTopic topic = topicService.selectCmsTopicById(topicId);
            // 探店日只能走本店现场窄投影，禁止并入跨项目 CRM。
            if (topic == null || Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) continue;
            boolean contactVisible = !hasOrganizerClub(topic);
            collectInto(byMember, 1L, topicId, contactVisible);
        }
        for (Long activityId : myActivityIds(memberId)) {
            // 活动是商家自己发布的,没有「俱乐部带团」这一层,主办方即联系人
            collectInto(byMember, 2L, activityId, true);
        }

        String needle = keyword == null ? "" : keyword.trim().toLowerCase();
        Date now = new Date();
        List<Map<String, Object>> rows = new ArrayList<>();
        int customerCount = 0, arrivedTotal = 0, pendingTotal = 0, refundedTotal = 0;
        Map<String, Integer> segmentCounts = new LinkedHashMap<>();
        for (String key : Customer.SEGMENT_KEYS) segmentCounts.put(key, 0);

        List<Customer> sorted = new ArrayList<>(byMember.values());
        // 最近来往的排前面 —— 名册是拿来找人的,不是按字典序背名单
        sorted.sort((a, b) -> {
            if (a.lastTime == null && b.lastTime == null) return 0;
            if (a.lastTime == null) return 1;
            if (b.lastTime == null) return -1;
            return b.lastTime.compareTo(a.lastTime);
        });

        for (Customer c : sorted) {
            String name = c.displayName(memberService);
            if (!needle.isEmpty() && !name.toLowerCase().contains(needle)) continue;

            customerCount++;
            arrivedTotal += c.arrived;
            pendingTotal += c.pending;
            refundedTotal += c.refunded;

            Map<String, Object> row = new HashMap<>();
            row.put("memberId", c.memberId);
            row.put("name", name);
            row.put("avatar", avatarOf(c.memberId));
            row.put("arrivedCount", c.arrived);
            row.put("pendingCount", c.pending);
            row.put("refundedCount", c.refunded);
            row.put("lastTime", c.lastTime);
            row.put("lastProjectTitle", c.lastProjectTitle);
            // 这一行的主状态 = 最近一张票的状态,跟 listPlayers 的 state 同一套词
            row.put("state", c.lastState);
            // 「最近发生了什么」一句话。相对时间不在这儿拼 —— 服务端算「3 天前」会
            // 按服务器时区算,跨时区必错;后端只给动词+项目名,时间交给前端按本地时区渲。
            row.put("lastAction", c.lastAction());
            // 客户分层:跟着人走,不是跟着某一张票走。同一个人可以同时属于多个段
            // (复购的人也可能有一张票没来),所以 segments 是集合;tier 只是行上
            // 那枚徽章,按「哪个最该被跟进」取一个。
            List<String> segments = c.segments(now);
            row.put("segments", segments);
            row.put("tier", Customer.tierOf(segments));
            // 电话只在「我负责联系」且玩家给过当前版本单独同意时给。两个条件缺一不给,
            // 前端不做二次过滤(前端过滤 = 号码已经发出去了再假装没发)。
            boolean giveContact = sensitiveContactVisible && c.contactVisible
                    && hasCurrentDataSharingConsent(c.memberId);
            row.put("phone", giveContact ? c.phone : null);
            // ★ 光给 null 不行。listPlayers 那边给了 contactHint,理由是「商家会以为是 bug」;
            //   名册这边不说明,商家看到的是「这人没留电话」——同一道闸退化成静默失败。
            //   两种不给的原因对商家的下一步动作完全不同,所以分开说。
            row.put("contactHint", giveContact ? null
                    : (!sensitiveContactVisible ? "当前账号无查看联系方式权限"
                    : (!c.contactVisible ? "由俱乐部带团,联系请找俱乐部负责人"
                                         : "该玩家未同意向商家提供联系方式")));
            rows.add(row);

            segmentCounts.put("all", segmentCounts.get("all") + 1);
            for (String s : segments) segmentCounts.put(s, segmentCounts.getOrDefault(s, 0) + 1);
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("customerCount", customerCount);
        summary.put("arrivedCount", arrivedTotal);
        summary.put("pendingCount", pendingTotal);
        summary.put("refundedCount", refundedTotal);

        // 分段筛 + 分页都在这里收口。放到 controller 去做的话,它就得把 rows 拆开重拼,
        // 还得知道 segments 这个字段的存在 —— 那是 service 的业务,不是 controller 的
        // (AGENTS.md:「Controller 薄、Service 写业务」)。
        String segment = q.safeSegment();
        if (segment != null) {
            List<Map<String, Object>> hit = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object segs = row.get("segments");
                if (segs instanceof List && ((List<?>) segs).contains(segment)) hit.add(row);
            }
            rows = hit;
        }
        int total = rows.size();
        int from = Math.min((q.safePageNum() - 1) * q.safePageSize(), total);
        int to = Math.min(from + q.safePageSize(), total);

        Map<String, Object> out = new HashMap<>();
        out.put("rows", new ArrayList<>(rows.subList(from, to)));
        out.put("total", total);
        out.put("summary", summary);
        // 分段计数恒取全量 —— 切到「复购」后其它段的数字不能跟着塌成 0,
        // 否则用户切一次就再也看不出别的段还有多少人。
        out.put("segmentCounts", segmentCounts);
        return out;
    }

    /**
     * 把一个项目的报名并进按人归拢的表。
     *
     * 口径与 listPlayers 逐字一致:已支付(2)与已退款(4)都取 —— 退款的人必须看得见,
     * 「昨天还在今天没了」和「他退款了」是两件事;到店/待到店只在已支付里分。
     */
    private void collectInto(Map<Long, Customer> byMember, Long ownerType, Long ownerId, boolean contactVisible) {
        for (int paymentStatus : new int[] { 2, 4 }) {
            CmsRegistration probe = new CmsRegistration();
            probe.setOwnerType(ownerType);
            probe.setOwnerId(ownerId);
            probe.setPaymentStatus(paymentStatus);
            List<CmsRegistration> list = registrationService.selectCmsRegistrationList(probe);
            if (list == null) continue;
            for (CmsRegistration r : list) {
                if (r.getMemberId() == null) continue;
                Customer c = byMember.computeIfAbsent(r.getMemberId(), Customer::new);
                c.absorb(r, paymentStatus == 4, contactVisible);
            }
        }
    }

    private List<Long> myActivityIds(Long memberId) {
        List<Long> ids = new ArrayList<>();
        if (activityService == null) {
            // 生产里这个 bean 一定在;真为 null 说明装配出了问题,而后果是「我自己发布的
            // 活动」的客户整批从名册里消失 —— 静默少一批人比报错难查得多,留一条日志。
            log.warn("ICmsActivityService 未注入,商家客户名册将不含自办活动的客户,memberId={}", memberId);
            return ids;
        }
        CmsActivity probe = new CmsActivity();
        probe.setMemberId(memberId);
        List<CmsActivity> list = activityService.selectCmsActivityList(probe);
        if (list != null) {
            for (CmsActivity a : list) {
                CmsTopic topic = a.getTopicId() == null ? null : topicService.selectCmsTopicById(a.getTopicId());
                if (topic != null && Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) continue;
                if (a.getId() != null) ids.add(a.getId());
            }
        }
        return ids;
    }

    /** 一个客户在我这儿的全部往来。ponytail: 在内存里归拢,商家的客户量级(几十~几百)撑得住;真到万级再下沉成 SQL 聚合。 */
    private static final class Customer {
        private final Long memberId;
        private int arrived, pending, refunded;
        private BigDecimal amount = BigDecimal.ZERO;
        private Date lastTime;
        private Date lastArrivedTime;   // 只记「到过店」的时间,用来判沉睡
        private String lastState;
        private String lastProjectTitle;
        private String realName;
        private Date realNameTime;   // 取最新姓名用的时间尺
        private String phone;
        private Date phoneTime;      // 同上,电话各比各的
        private boolean contactVisible;

        private Customer(Long memberId) { this.memberId = memberId; }

        private void absorb(CmsRegistration r, boolean isRefunded, boolean topicContactVisible) {
            boolean verified = !isRefunded && r.getVerificationStatus() != null && r.getVerificationStatus() == 1;
            String state;
            if (isRefunded) {
                refunded++;
                state = "refunded";
            } else if (verified) {
                arrived++;
                state = "arrived";
            } else {
                pending++;
                state = "pending";
            }
            // 金额只累计真付了钱的:退款的不该把「他在我这儿花了多少」撑大
            if (!isRefunded) {
                BigDecimal paid = r.getPayableAmount() != null ? r.getPayableAmount() : r.getTotalAmount();
                if (paid != null) amount = amount.add(paid);
            }
            // 只要有一个项目是我负责联系的,就允许给电话(见调用处注释)
            if (topicContactVisible) contactVisible = true;

            Date t = r.getCreateTime();
            if (t != null && (lastTime == null || t.after(lastTime))) {
                lastTime = t;
                lastState = state;
                lastProjectTitle = r.getActivityTitle();
            }
            // 沉睡看的是「上次真到店」,不是「上次下单」——报了名没来不算来过
            if (verified) {
                Date v = r.getVerificationTime() != null ? r.getVerificationTime() : t;
                if (v != null && (lastArrivedTime == null || v.after(lastArrivedTime))) lastArrivedTime = v;
            }
            // 姓名/电话取**最近**一张有值的票:同一个人不同场可能留不同联系人,
            // 合并会拼出现实中不存在的人。
            // ⚠️ 必须按 createTime 比,不能写成 `if (realName == null)` —— 那取的是遍历
            // 撞见的第一张,顺序由 topicId 插入序 × paymentStatus × mapper 返回序决定,
            // 跟时间无关;结果就是换过号码的人长期显示旧号,而这正是这张卡的主内容。
            String name = r.getRealName() == null ? null : r.getRealName().trim();
            if (name != null && !name.isEmpty() && isNewerThan(t, realNameTime)) {
                realName = name;
                realNameTime = t;
            }
            if (topicContactVisible && r.getPhone() != null && isNewerThan(t, phoneTime)) {
                phone = r.getPhone();
                phoneTime = t;
            }
        }

        /** 没有时间戳的行排在最后:有时间的一律赢,免得脏数据顶掉真实最新值。 */
        private static boolean isNewerThan(Date candidate, Date current) {
            if (candidate == null) return current == null;
            return current == null || candidate.after(current);
        }

        private String displayName(IUmsMemberService memberService) {
            if (realName != null && realName.length() > 0) return realName;
            UmsMember m = memberId == null ? null : memberService.selectUmsMemberById(memberId);
            String nickname = m == null ? null : m.getNickname();
            return nickname == null || nickname.trim().isEmpty() ? "未留姓名" : nickname.trim();
        }

        /**
         * 「最近发生了什么」。只给动词 + 项目名,相对时间由前端按本地时区渲染。
         *
         * 项目名可能为空(存量数据 activity_title 没回填)。那时**换一句能独立成立的话**,
         * 不能让「退了」「核销了」这种带宾语的动词吊在那儿 —— 少个书名号不显眼,
         * 少个宾语一眼就看出来是数据缺了。
         */
        private String lastAction() {
            if (lastState == null) return "";
            boolean hasTitle = lastProjectTitle != null && !lastProjectTitle.trim().isEmpty();
            if (!hasTitle) {
                if ("arrived".equals(lastState)) return "已到店核销";
                if ("refunded".equals(lastState)) return "已退款";
                return "已报名";
            }
            String title = "「" + lastProjectTitle.trim() + "」";
            if ("arrived".equals(lastState)) return "核销了" + title;
            if ("refunded".equals(lastState)) return "退了" + title;
            return "报名了" + title;
        }

        /** 全部 / 待核销 / 复购 / 沉睡 / 异常。all 由调用方单独计,不进这里。 */
        private static final String[] SEGMENT_KEYS = { "all", "pending", "repeat", "dormant", "abnormal" };

        /** 沉睡线:超过这些天没到过店。2026-08-10 用户拍板取 60 天,真实数据起来后再调。 */
        private static final long DORMANT_DAYS = 60L;

        /**
         * 一个人可以同时属于多个段 —— 复购客户手上也可能有一张没核销的票。
         * 段是「筛选用的集合」,不是互斥分区;强行分区会让「复购」这个数字
         * 莫名其妙地少掉一批人。
         */
        private List<String> segments(Date now) {
            List<String> out = new ArrayList<>();
            if (pending > 0) out.add("pending");
            if (arrived >= 2) out.add("repeat");
            if (refunded > 0) out.add("abnormal");
            // 沉睡的前提是「来过、且现在手上没有待核销的票」——手上有票的人不算失联,
            // 他只是还没来。把这种人标成沉睡会让商家去做无意义的召回。
            if (arrived >= 1 && pending == 0 && lastArrivedTime != null) {
                long days = (now.getTime() - lastArrivedTime.getTime()) / (24L * 3600_000L);
                if (days >= DORMANT_DAYS) out.add("dormant");
            }
            return out;
        }

        /**
         * 行上那枚徽章只显示一个,按「哪个最该被跟进」排:
         * 待核销(有票没来,今天就能催)> 异常(退过款,得知道为什么)>
         * 沉睡(得召回)> 复购(好消息,不需要动作)> 新客。
         */
        private static String tierOf(List<String> segments) {
            if (segments.contains("pending")) return "pending";
            if (segments.contains("abnormal")) return "abnormal";
            if (segments.contains("dormant")) return "dormant";
            if (segments.contains("repeat")) return "repeat";
            return "new";
        }
    }

}

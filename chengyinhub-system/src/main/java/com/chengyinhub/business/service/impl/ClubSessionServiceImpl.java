package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.dto.ClubSessionOpenRequest;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IClubSessionService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.support.ActivityAuditSnapshot;
import com.chengyinhub.business.service.support.ActivityReviewPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Calendar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TimeZone;

/** 俱乐部主动开团：合作合同或本俱乐部发布的主题是资格真源，活动行是场次事实真源。 */
@Service
public class ClubSessionServiceImpl implements IClubSessionService {

    private static final Logger log = LoggerFactory.getLogger(ClubSessionServiceImpl.class);

    private final IClubService clubService;
    private final ICmsTopicService topicService;
    private final ICmsActivityService activityService;
    private final IOmsTicketService ticketService;
    private final CoopOrderMapper orderMapper;
    private final com.chengyinhub.business.service.support.SettlementDebtLedger settlementDebtLedger;
    private final IContentSafetyGate contentSafetyGate;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chengyinhub.business.service.IContentSafetyRecheckService recheckService;
    private final ActivityReviewPolicy activityReviewPolicy;
    private final IAuditTaskService auditTaskService;
    private final IUmsMemberService umsMemberService;

    public ClubSessionServiceImpl(IClubService clubService, ICmsTopicService topicService,
                                  ICmsActivityService activityService, IOmsTicketService ticketService,
                                  CoopOrderMapper orderMapper,
                                  com.chengyinhub.business.service.support.SettlementDebtLedger settlementDebtLedger,
                                  IContentSafetyGate contentSafetyGate, ActivityReviewPolicy activityReviewPolicy,
                                  IAuditTaskService auditTaskService, IUmsMemberService umsMemberService) {
        this.settlementDebtLedger = settlementDebtLedger;
        this.contentSafetyGate = contentSafetyGate;
        this.activityReviewPolicy = activityReviewPolicy;
        this.auditTaskService = auditTaskService;
        this.umsMemberService = umsMemberService;
        this.clubService = clubService;
        this.topicService = topicService;
        this.activityService = activityService;
        this.ticketService = ticketService;
        this.orderMapper = orderMapper;
    }

    @Override
    public List<CmsTopic> selectOpenableTopics(Long clubId, Long memberId) {
        requireOwner(clubId, memberId);
        LinkedHashSet<Long> topicIds = new LinkedHashSet<Long>();
        List<Long> coopIds = orderMapper.selectEffectiveTopicIdsByClubId(clubId);
        if (coopIds != null) topicIds.addAll(coopIds);
        CmsTopic probe = new CmsTopic();
        probe.setClubId(clubId);
        // 主理人自己开团:不喂 viewerMemberId 的话,设成「只对成员可见」的主题
        // 会从他自己的可开团列表里消失(requireOwner 已经证明他是主理人)。
        probe.setViewerMemberId(memberId);
        List<CmsTopic> owned = topicService.selectPublicCmsTopicList(probe);
        if (owned != null) {
            for (CmsTopic row : owned) {
                if (row != null && row.getId() != null) topicIds.add(row.getId());
            }
        }
        if (topicIds.isEmpty()) return Collections.emptyList();
        List<CmsTopic> topics = new ArrayList<>();
        for (Long topicId : topicIds) {
            CmsTopic topic = topicId == null ? null : topicService.selectCmsTopicById(topicId);
            if (topic != null && (topic.getDelFlag() == null || topic.getDelFlag() == 0)) {
                topics.add(topic);
            }
        }
        // C-02 / F-04(j2):主理人开场前就要知道付费场次能不能在线收款(与 assertCollectible 同一判据,不改收款闸本身)。
        for (CmsTopic topic : topics) topic.setOnlinePaymentAvailable(onlinePaymentAvailable(topic));
        // C-17:开一场页只读显示票价与集合点(玩家结账页看到的就是票上的这两项)。
        for (CmsTopic topic : topics) applySessionTicketFacts(topic);
        return topics;
    }

    static void requireTopicOpenForSessions(Integer lifecycle) {
        if (lifecycle == null || lifecycle == 0 || lifecycle == 3 || lifecycle == 4) return;
        if (lifecycle == 5 || lifecycle == 6) {
            throw new IllegalArgumentException("主题已结束售卖，不能再开一场");
        }
        throw new IllegalArgumentException("主题还没开售，开售后才能开一场");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long open(ClubSessionOpenRequest request, Long memberId, String username) {
        if (request == null || request.getClubId() == null || request.getTopicId() == null
                || request.getSessionDate() == null) {
            throw new IllegalArgumentException("请选择俱乐部、主题和日期");
        }
        // 5.9(2026-09-15 拍板):集合时刻是场次退款钟(集合前 24h)的锚点,缺省直接拒绝开场。
        // 静默落 00:00 会让实际截止比页面承诺早 14+ 小时(晚场最多近 24h),是对外承诺与
        // 实际不符的根因;存量 00:00 场次不回溯改写,仍按现实现计算。
        Date assemblyMoment = assemblyMoment(request);
        if (!assemblyMoment.after(new Date())) {
            throw new IllegalArgumentException("集合时间必须晚于当前时间");
        }
        requireOwnerForUpdate(request.getClubId(), memberId);
        // C-18:同一俱乐部、同一主题、同一集合时刻已有一场(未取消)时不再静默再建一场。
        // 放在俱乐部行锁之后,两次并发提交也会串行看到对方;系列按 requestId 的回执重放在调用方更早返回,不经过这里。
        requireNoSameMomentSession(request.getClubId(), request.getTopicId(), assemblyMoment);
        // 9-16 拍板第 2 条:欠款逾期未补足 ⇒ 不能开新场次(场次票款结算给开场主理人)。
        settlementDebtLedger.requireNoOverdueDebt(memberId);
        CmsTopic topic = topicService.selectCmsTopicById(request.getTopicId());
        if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() != 0)) {
            throw new IllegalArgumentException("主题不存在或已失效");
        }
        // AUDIT-G1-3:父主题被驳回 / 作者下架 / 违规下架后不能再开场次(公开读与下单同口径,见 livePublicParentTopic)。
        if (!Integer.valueOf(1).equals(topic.getStatus()) || !Integer.valueOf(1).equals(topic.getUserStatus())) {
            throw new IllegalArgumentException("主题未上架或已下架，不能开团");
        }
        // 第十一轮 h4 L5:商业主题在立项待审 / 招募中 / 定价与开售审核中时玩家还买不了票,不能先开一场;
        // 结算中 / 已关闭也不再开。口径与俱乐部页「开一场」入口(pages/club/detail sellable)一致:
        // 无生命周期(老主题、纯 UGC)、草稿、售票中、进行中可开。
        requireTopicOpenForSessions(topic.getLifecycle());
        boolean clubOwned = topic.getClubId() != null && topic.getClubId().equals(request.getClubId());
        if (!clubOwned && !hasEffectiveClubOrder(request.getTopicId(), request.getClubId())) {
            throw new IllegalArgumentException("该俱乐部对本主题没有生效的合作单");
        }
        // AUDIT-G1-3:场次是一条新的公开售卖面,与 /api/activity/publish 同一道门禁 + 同一套分级。
        // 收费场的实名闸在 copyTopicTicketsToActivity(OmsTicketServiceImpl)里,同事务,拒了整笔回滚。
        CmsActivityPublishRequest policyView = new CmsActivityPublishRequest();
        policyView.setName(topic.getName());
        policyView.setDescription(topic.getDescription());
        policyView.setImgUrl(topic.getImgUrl());
        policyView.setCategoryIds(topic.getCategoryIds());
        policyView.setClubId(request.getClubId());
        policyView.setTickets(topicTicketView(request.getTopicId()));
        PublishSafetyRequest safety = new PublishSafetyRequest();
        safety.setContentType(AuditTask.BIZ_ACTIVITY);
        safety.setMemberId(memberId);
        safety.setOpenid(resolveOpenid(memberId));
        safety.setText(IWxContentSecurityService.joinText(topic.getName(), topic.getDescription()));
        safety.setHasPrice(activityReviewPolicy.hasCharge(policyView));
        safety.setHasSignup(true);
        safety.setReviewMode(activityReviewPolicy.modeOf(policyView,
                new ActivityReviewPolicy.Publisher(false, false)).name());
        SafetyDecision decision = contentSafetyGate.decide(safety);
        if (decision == null || decision.isBlock()) {
            throw new IllegalArgumentException("主题内容包含违规信息，不能开团");
        }
        boolean preReview = decision.isHold();

        CmsActivity activity = new CmsActivity();
        activity.setMemberId(memberId);
        activity.setTopicId(request.getTopicId());
        activity.setClubId(request.getClubId());
        activity.setName(topic.getName());
        activity.setDescription(topic.getDescription());
        activity.setImgUrl(topic.getImgUrl());
        activity.setCategoryIds(topic.getCategoryIds());
        activity.setAddressName(topic.getAddressName());
        // C-03:集合签到(ClubGatherArrivalPolicy)只认场次自己的经纬度;开场时把票种上的集合点
        // 抄到场次上,否则「我到了」恒为「集合点未配置」。系列开场也走这里。
        applyGatherPoint(activity, request.getTopicId());
        activity.setStartDate(assemblyMoment);
        activity.setEndDate(dayBoundary(assemblyMoment, true));
        // 先审 ⇒ 待审(status=0/publish_status=0),审核台通过后 applyModeration 上架;先发后审 ⇒ 直接上架。
        activity.setStatus(preReview ? 0L : 1L);
        activity.setPublishStatus(preReview ? 0L : 1L);
        activity.setDelFlag(0);
        activity.setCreateBy(username);
        activity.setCreateTime(new Date());
        activity.setUpdateBy(username);
        activity.setUpdateTime(new Date());
        if (activityService.insertCmsActivity(activity) != 1 || activity.getId() == null) {
            throw new IllegalStateException("开团场次创建失败");
        }
        ticketService.copyTopicTicketsToActivity(request.getTopicId(), activity.getId(), memberId);
        // Round5C:机审没结论的开团场次进自动复检(通过自动上架;先审的业务审批仍留人工)。
        if (recheckService != null && com.chengyinhub.business.service.IContentSafetyRecheckService.needsRecheck(decision)) {
            recheckService.enqueue(safety, activity.getId());
        }
        if (preReview || decision.isFlagged()) {
            String snap = ActivityAuditSnapshot.of(activity.getId(), activity,
                    activityTickets(activity.getId()), decision.isFlagged() ? decision.getFlagReason() : null);
            if (preReview) {
                // 待审场次必须有单可审:建单失败整笔回滚,不留「待审却无人审」的场次。
                auditTaskService.submitOrRefreshActivity(activity.getId(), memberId, "user_publish", snap);
            } else {
                try {
                    auditTaskService.submitOrRefreshActivity(activity.getId(), memberId, "post_review_flag", snap);
                } catch (Exception e) {
                    log.error("开团场次抽检单提交失败 activityId={}", activity.getId(), e);
                }
            }
        }
        return activity.getId();
    }

    /**
     * 与 CmsRegistrationServiceImpl.assertCollectible 的主题分支同一判据:自 2026-11-02 起收款闸只拒「查不到票款归谁」,
     * 主题能解析出来就照常走平台商户号收款,不再按「是否平台自营」区分(F-04:原先按自营判,主理人被误告知收不了款)。
     */
    static Boolean onlinePaymentAvailable(CmsTopic topic) {
        return topic != null;
    }

    @Override
    public String themeWindowWarning(Long topicId, List<LocalDate> dates) {
        if (topicId == null || dates == null || dates.isEmpty()) return null;
        CmsTopic topic = topicService.selectCmsTopicById(topicId);
        if (topic == null || (topic.getStartDate() == null && topic.getEndDate() == null)) return null;
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate start = topic.getStartDate() == null ? null : topic.getStartDate().toInstant().atZone(zone).toLocalDate();
        LocalDate end = topic.getEndDate() == null ? null : topic.getEndDate().toInstant().atZone(zone).toLocalDate();
        int outside = 0;
        for (LocalDate date : dates) {
            if (date == null) continue;
            if ((start != null && date.isBefore(start)) || (end != null && date.isAfter(end))) outside++;
        }
        if (outside == 0) return null;
        DateTimeFormatter label = DateTimeFormatter.ofPattern("M月d日");
        String window = (start == null ? "" : start.format(label)) + "—" + (end == null ? "" : end.format(label));
        return (dates.size() == 1 ? "这一场" : "有 " + outside + " 场") + "不在主题日期（" + window + "）内，请确认";
    }

    private void requireNoSameMomentSession(Long clubId, Long topicId, Date assemblyMoment) {
        CmsActivity probe = new CmsActivity();
        probe.setTopicId(topicId);
        probe.setClubId(clubId);
        probe.setDelFlag(0);
        probe.setExcludeCancelled(1);
        List<CmsActivity> existing = activityService.selectCmsActivityList(probe);
        if (existing == null) return;
        for (CmsActivity activity : existing) {
            if (activity != null && activity.getCancelTime() == null && activity.getStartDate() != null
                    && activity.getStartDate().getTime() == assemblyMoment.getTime()) {
                throw new IllegalArgumentException("这个时间已有一场，请换个时间或直接修改那一场");
            }
        }
    }

    void applySessionTicketFacts(CmsTopic topic) {
        if (topic == null || topic.getId() == null) return;
        OmsTicket query = new OmsTicket();
        query.setOwnerType(1L);
        query.setOwnerId(topic.getId());
        query.setDelFlag(0);
        List<OmsTicket> tickets = ticketService.selectOmsTicketList(query);
        if (tickets == null) return;
        java.math.BigDecimal min = null;
        String meeting = null;
        for (OmsTicket ticket : tickets) {
            if (ticket == null) continue;
            if (ticket.getPrice() != null && (min == null || ticket.getPrice().compareTo(min) < 0)) min = ticket.getPrice();
            if (meeting == null) {
                String name = ticket.getMeetingPoint() == null ? "" : ticket.getMeetingPoint().trim();
                String address = ticket.getMeetingPointAddress() == null ? "" : ticket.getMeetingPointAddress().trim();
                String text = (name + (name.isEmpty() || address.isEmpty() ? "" : " · ") + address).trim();
                if (!text.isEmpty()) meeting = text;
            }
        }
        topic.setSessionPrice(min);
        topic.setSessionMeetingPoint(meeting);
    }

    /** 取主题票(owner_type=1)里第一张带完整集合点坐标的票,写到场次 latitude/longitude。 */
    void applyGatherPoint(CmsActivity activity, Long topicId) {
        OmsTicket query = new OmsTicket();
        query.setOwnerType(1L);
        query.setOwnerId(topicId);
        query.setDelFlag(0);
        List<OmsTicket> tickets = ticketService.selectOmsTicketList(query);
        if (tickets == null) return;
        for (OmsTicket ticket : tickets) {
            if (ticket == null || ticket.getGatherLat() == null || ticket.getGatherLng() == null) continue;
            activity.setLatitude(ticket.getGatherLat().stripTrailingZeros().toPlainString());
            activity.setLongitude(ticket.getGatherLng().stripTrailingZeros().toPlainString());
            return;
        }
    }

    private List<ActivityTicketRequest> topicTicketView(Long topicId) {
        OmsTicket query = new OmsTicket();
        query.setOwnerType(1L);
        query.setOwnerId(topicId);
        query.setDelFlag(0);
        List<ActivityTicketRequest> view = new ArrayList<>();
        List<OmsTicket> tickets = ticketService.selectOmsTicketList(query);
        if (tickets == null) return view;
        for (OmsTicket ticket : tickets) {
            if (ticket == null) continue;
            ActivityTicketRequest row = new ActivityTicketRequest();
            row.setName(ticket.getName());
            row.setPrice(ticket.getPrice());
            view.add(row);
        }
        return view;
    }

    private List<OmsTicket> activityTickets(Long activityId) {
        OmsTicket query = new OmsTicket();
        query.setOwnerType(2L);
        query.setOwnerId(activityId);
        query.setDelFlag(0);
        return ticketService.selectOmsTicketList(query);
    }

    private String resolveOpenid(Long memberId) {
        try {
            UmsMember member = umsMemberService.selectUmsMemberById(memberId);
            return member == null || member.getWxOpenId() == null ? "" : member.getWxOpenId();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 「出发日期(Asia/Shanghai 本地日)+ 集合时刻(HH:mm)」→ 精确到分钟的绝对时刻。
     * sessionDate 由 Jackson 按 GMT+8 解析,取其上海本地日,避免 JVM 默认时区把日期挪走。
     */
    private Date assemblyMoment(ClubSessionOpenRequest request) {
        String raw = request.getSessionTime() == null ? "" : request.getSessionTime().trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("请选择集合时刻(HH:mm)");
        }
        LocalTime time;
        try {
            time = LocalTime.parse(raw, DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT));
        } catch (DateTimeException malformed) {
            throw new IllegalArgumentException("集合时刻格式应为 HH:mm,实际:" + raw);
        }
        LocalDate date = request.getSessionDate().toInstant()
                .atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        return Date.from(LocalDateTime.of(date, time).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
    }

    private void requireOwner(Long clubId, Long memberId) {
        Club club = clubId == null ? null : clubService.selectClubById(clubId);
        if (club == null || club.getMemberId() == null || !club.getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("只有该俱乐部主理人可以开团");
        }
    }

    private void requireOwnerForUpdate(Long clubId, Long memberId) {
        Club club = clubId == null ? null : clubService.selectClubByIdForUpdate(clubId);
        if (club == null || club.getMemberId() == null || !club.getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("只有在营俱乐部的主理人可以开团");
        }
    }

    private boolean hasEffectiveClubOrder(Long topicId, Long clubId) {
        List<CoopOrder> orders = orderMapper.selectEffectiveOrdersByTopicId(topicId);
        if (orders == null) return false;
        for (CoopOrder order : orders) {
            if (order != null && "club".equals(order.getToType()) && clubId.equals(order.getToId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 场次日终边界(只给 end_date 用)。
     *
     * <p>⚠️ 2026-09-15(5.9):**start_date 不再走这里**。它是场次集合时间真源,退款钟
     * ({@code RefundPolicy.evaluate(2L,...)})读的就是它 —— 原来的 {@code dayBoundary(...,false)}
     * 会把调用方给的集合时刻(如 14:30)压成当天 00:00,还依赖 JVM 默认时区重算,
     * 等于把退款窗口凭空挪走半天。调用方只给日期时自然落 00:00,无需在这里改写。
     */
    private Date dayBoundary(Date source, boolean endOfDay) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        calendar.setTime(source);
        calendar.set(Calendar.HOUR_OF_DAY, endOfDay ? 23 : 0);
        calendar.set(Calendar.MINUTE, endOfDay ? 59 : 0);
        calendar.set(Calendar.SECOND, endOfDay ? 59 : 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }
}

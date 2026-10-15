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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    private final IClubService clubService;
    private final ICmsTopicService topicService;
    private final ICmsActivityService activityService;
    private final IOmsTicketService ticketService;
    private final CoopOrderMapper orderMapper;
    private final com.chengyinhub.business.service.support.SettlementDebtLedger settlementDebtLedger;

    public ClubSessionServiceImpl(IClubService clubService, ICmsTopicService topicService,
                                  ICmsActivityService activityService, IOmsTicketService ticketService,
                                  CoopOrderMapper orderMapper,
                                  com.chengyinhub.business.service.support.SettlementDebtLedger settlementDebtLedger) {
        this.settlementDebtLedger = settlementDebtLedger;
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
        return topics;
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
        // 9-16 拍板第 2 条:欠款逾期未补足 ⇒ 不能开新场次(场次票款结算给开场主理人)。
        settlementDebtLedger.requireNoOverdueDebt(memberId);
        CmsTopic topic = topicService.selectCmsTopicById(request.getTopicId());
        if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() != 0)) {
            throw new IllegalArgumentException("主题不存在或已失效");
        }
        boolean clubOwned = topic.getClubId() != null && topic.getClubId().equals(request.getClubId());
        if (!clubOwned && !hasEffectiveClubOrder(request.getTopicId(), request.getClubId())) {
            throw new IllegalArgumentException("该俱乐部对本主题没有生效的合作单");
        }

        CmsActivity activity = new CmsActivity();
        activity.setMemberId(memberId);
        activity.setTopicId(request.getTopicId());
        activity.setClubId(request.getClubId());
        activity.setName(topic.getName());
        activity.setDescription(topic.getDescription());
        activity.setImgUrl(topic.getImgUrl());
        activity.setCategoryIds(topic.getCategoryIds());
        activity.setAddressName(topic.getAddressName());
        activity.setStartDate(assemblyMoment);
        activity.setEndDate(dayBoundary(assemblyMoment, true));
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        activity.setCreateBy(username);
        activity.setCreateTime(new Date());
        activity.setUpdateBy(username);
        activity.setUpdateTime(new Date());
        if (activityService.insertCmsActivity(activity) != 1 || activity.getId() == null) {
            throw new IllegalStateException("开团场次创建失败");
        }
        ticketService.copyTopicTicketsToActivity(request.getTopicId(), activity.getId(), memberId);
        return activity.getId();
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
            time = LocalTime.parse(raw, DateTimeFormatter.ofPattern("HH:mm"));
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

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMerchantFulfillmentReadService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MerchantFulfillmentReadServiceImpl implements IMerchantFulfillmentReadService {
    private final ICmsRegistrationMerchantService registrationMerchantService;
    private final ICmsTopicService topicService;
    private final IClubService clubService;
    private final ICmsTopicNodeService topicNodeService;
    private final IOmsTicketService ticketService;
    private final ICmsRegistrationService registrationService;
    private final CmsRegistrationMapper registrationMapper;
    private final ICmsActivityService activityService;

    public MerchantFulfillmentReadServiceImpl(ICmsRegistrationMerchantService registrationMerchantService,
                                               ICmsTopicService topicService, IClubService clubService,
                                               ICmsTopicNodeService topicNodeService, IOmsTicketService ticketService,
                                               ICmsRegistrationService registrationService,
                                               CmsRegistrationMapper registrationMapper,
                                               ICmsActivityService activityService) {
        this.registrationMerchantService = registrationMerchantService;
        this.topicService = topicService;
        this.clubService = clubService;
        this.topicNodeService = topicNodeService;
        this.ticketService = ticketService;
        this.registrationService = registrationService;
        this.registrationMapper = registrationMapper;
        this.activityService = activityService;
    }

    @Override
    public List<Map<String, Object>> listUpcomingRuns(Long memberId, Long topicIdFilter) {
        CmsRegistrationMerchant probe = new CmsRegistrationMerchant();
        probe.setMemberId(memberId);
        probe.setAuditStatus(1L);
        probe.setDelFlag(0);
        List<CmsRegistrationMerchant> won = registrationMerchantService.selectCmsRegistrationMerchantList(probe);

        List<Map<String, Object>> runs = new ArrayList<>();
        Date now = new Date();
        for (CmsRegistrationMerchant registration : won) {
            if (registration.getTopicId() == null) continue;
            if (topicIdFilter != null && !topicIdFilter.equals(registration.getTopicId())) continue;
            runs.addAll(buildRunsForTopic(registration, now));
        }
        runs.sort((left, right) -> Long.compare(runStartMillis(left), runStartMillis(right)));
        return runs;
    }

    private static long runStartMillis(Map<String, Object> run) {
        Object value = run.get("startTime");
        return value instanceof Date ? ((Date) value).getTime() : Long.MAX_VALUE;
    }

    private List<Map<String, Object>> buildRunsForTopic(CmsRegistrationMerchant won, Date now) {
        List<Map<String, Object>> runs = new ArrayList<>();
        Long topicId = won.getTopicId();

        CmsTopic topic = topicService.selectCmsTopicById(topicId);
        if (topic == null) return runs;

        String clubName = null;
        if (topic.getClubId() != null && topic.getClubId() > 0) {
            Club club = clubService.selectClubById(topic.getClubId());
            if (club != null) clubName = club.getName();
        }

        int nodeOrder = 0;
        int nodeTotal = 0;
        CmsTopicNode nodeProbe = new CmsTopicNode();
        nodeProbe.setTopicId(topicId);
        List<CmsTopicNode> nodes = topicNodeService.selectCmsTopicNodeList(nodeProbe);
        if (nodes != null) {
            nodeTotal = nodes.size();
            for (int index = 0; index < nodes.size(); index++) {
                if (nodes.get(index).getId() != null && nodes.get(index).getId().equals(won.getNodeId())) {
                    nodeOrder = index + 1;
                    break;
                }
            }
        }

        // 2026-09-23 用户裁决:工作台承接卡要写「哪个俱乐部、什么时候来」。俱乐部「开一场」落在 cms_activity
        // (topic_id=本主题),场次票是复制件 owner_type=2,不在下面的主题票里 —— 不补就看不到俱乐部场次。
        addClubSessionRuns(runs, won, topic, nodeOrder, nodeTotal, now);

        OmsTicket ticketProbe = new OmsTicket();
        ticketProbe.setOwnerType(1L);
        ticketProbe.setOwnerId(topicId);
        List<OmsTicket> tickets = ticketService.selectOmsTicketList(ticketProbe);
        if (tickets.isEmpty()) return runs;

        Map<Long, Integer> paidCountByTicket = new HashMap<>();
        List<Map<String, Object>> paidRows = registrationMapper
                .selectPaidCountsByTopicTicketIncludingActivityShell(topicId);
        if (paidRows != null) for (Map<String, Object> row : paidRows) {
            Object ticketId = row.get("ticketId");
            Object count = row.get("paidCount");
            if (ticketId instanceof Number && count instanceof Number) {
                paidCountByTicket.put(((Number) ticketId).longValue(), ((Number) count).intValue());
            }
        }
        // 存量 owner_type=1 主题报名兼容；活动壳投影只要返回票行（即使 count=0）就不走此分支。
        if (paidRows == null || paidRows.isEmpty()) {
            CmsRegistration registrationProbe = new CmsRegistration();
            registrationProbe.setPaymentStatus(2);
            registrationProbe.setOwnerType(1L);
            registrationProbe.setOwnerId(topicId);
            for (CmsRegistration registration : registrationService.selectCmsRegistrationList(registrationProbe)) {
                if (registration.getTicketId() != null) {
                    Integer count = paidCountByTicket.get(registration.getTicketId());
                    paidCountByTicket.put(registration.getTicketId(), count == null ? 1 : count + 1);
                }
            }
        }

        for (OmsTicket ticket : tickets) {
            if (ticket.getDelFlag() != null && ticket.getDelFlag() == 2) continue;
            Date start = ticket.getStartTime();
            if (start == null) continue;
            Date end = ticket.getEndTime();
            if ((end != null ? end : start).before(now)) continue;

            Map<String, Object> run = new HashMap<>();
            run.put("topicId", topicId);
            run.put("topicName", topic.getName());
            run.put("ticketId", ticket.getId());
            run.put("ticketName", ticket.getName());
            run.put("startTime", start);
            run.put("endTime", end);
            run.put("clubId", topic.getClubId());
            run.put("clubName", clubName);
            run.put("teamStatus", ticket.getTeamStatus());
            run.put("meetingPoint", ticket.getMeetingPoint());
            Integer paidCount = paidCountByTicket.get(ticket.getId());
            run.put("paidCount", paidCount == null ? 0 : paidCount);
            run.put("nodeOrder", nodeOrder > 0 ? nodeOrder : null);
            run.put("nodeTotal", nodeTotal > 0 ? nodeTotal : null);
            run.put("estimated", Boolean.TRUE);
            Date[] window = estimateArrivalWindow(start, end, nodeOrder, nodeTotal);
            run.put("arrivalStart", window == null ? null : window[0]);
            run.put("arrivalEnd", window == null ? null : window[1]);
            runs.add(run);
        }
        return runs;
    }

    private void addClubSessionRuns(List<Map<String, Object>> runs, CmsRegistrationMerchant won, CmsTopic topic,
                                    int nodeOrder, int nodeTotal, Date now) {
        CmsActivity probe = new CmsActivity();
        probe.setTopicId(topic.getId());
        probe.setDelFlag(0);
        // 列表查询不回 cancel_time 列,不能在 Java 里判 getCancelTime();取消过滤必须下沉到 SQL。
        probe.setExcludeCancelled(1);
        List<CmsActivity> sessions = activityService.selectCmsActivityList(probe);
        if (sessions == null) return;
        Map<Long, String> clubNames = new HashMap<>();
        for (CmsActivity session : sessions) {
            // 只要俱乐部开的场次:主题自己的活动壳(generateSelfHostActivity,无 club_id)票款已算进上面的主题票,
            // 再算一遍就是重复行、人数翻倍。
            if (session == null || session.getId() == null
                    || session.getClubId() == null || session.getClubId() <= 0) continue;
            Date start = session.getStartDate();
            if (start == null) continue;
            Date end = session.getEndDate();
            if ((end != null ? end : start).before(now)) continue;
            String sessionClubName = null;
            Long clubId = session.getClubId();
            if (!clubNames.containsKey(clubId)) {
                Club club = clubService.selectClubById(clubId);
                clubNames.put(clubId, club == null ? null : club.getName());
            }
            sessionClubName = clubNames.get(clubId);
            CmsRegistration paidProbe = new CmsRegistration();
            paidProbe.setPaymentStatus(2);
            paidProbe.setRegistrationStatus(2);   // 与主题票人数同口径(payment_status=2 且 registration_status=2)
            paidProbe.setOwnerType(2L);
            paidProbe.setOwnerId(session.getId());
            List<CmsRegistration> paid = registrationService.selectCmsRegistrationList(paidProbe);

            Map<String, Object> run = new HashMap<>();
            run.put("topicId", topic.getId());
            run.put("topicName", topic.getName());
            run.put("activityId", session.getId());
            run.put("activityName", session.getName());
            run.put("startTime", start);
            run.put("endTime", end);
            run.put("clubId", clubId);
            run.put("clubName", sessionClubName);
            run.put("paidCount", paid == null ? 0 : paid.size());
            run.put("nodeOrder", nodeOrder > 0 ? nodeOrder : null);
            run.put("nodeTotal", nodeTotal > 0 ? nodeTotal : null);
            run.put("estimated", Boolean.TRUE);
            Date[] window = estimateArrivalWindow(start, end, nodeOrder, nodeTotal);
            run.put("arrivalStart", window == null ? null : window[0]);
            run.put("arrivalEnd", window == null ? null : window[1]);
            runs.add(run);
        }
    }

    private static Date[] estimateArrivalWindow(Date start, Date end, int nodeOrder, int nodeTotal) {
        if (start == null || end == null || nodeOrder <= 0 || nodeTotal <= 0 || nodeOrder > nodeTotal) {
            return null;
        }
        long span = end.getTime() - start.getTime();
        if (span <= 0) return null;
        long slot = span / nodeTotal;
        return new Date[]{
                new Date(start.getTime() + slot * (nodeOrder - 1)),
                new Date(start.getTime() + slot * nodeOrder)
        };
    }
}

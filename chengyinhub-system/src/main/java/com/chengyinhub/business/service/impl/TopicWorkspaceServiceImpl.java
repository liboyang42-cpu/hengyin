package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CoopClubApply;
import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.TopicWorkspaceDTO;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CoopClubApplyMapper;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMerchantOfferService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ITopicWorkspaceService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TopicWorkspaceServiceImpl implements ITopicWorkspaceService {

    private final ICmsTopicService topicService;
    private final ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;
    private final ICmsTopicChapterService chapterService;
    private final ICmsTopicNodeService nodeService;
    private final IOmsTicketService ticketService;
    private final ICmsRegistrationMerchantService candidateService;
    private final IProductRoleService productRoleService;
    private final MmsMerchantMapper merchantMapper;
    private final CmsRegistrationMapper registrationMapper;
    private final TopicSettlementEvidenceService settlementEvidenceService;
    private final CoopClubApplyMapper clubApplyMapper;
    private final CoopInviteMapper inviteMapper;
    private final CoopOrderMapper orderMapper;
    private final CoopGroupVerifyMapper groupVerifyMapper;
    private final CoopSettlementMapper settlementMapper;
    private final CoopComplaintMapper complaintMapper;
    private final ClubLeadMapper clubLeadMapper;
    private final ClubMapper clubMapper;
    private final UmsMemberMapper memberMapper;
    private final IMerchantOfferService merchantOfferService;

    public TopicWorkspaceServiceImpl(ICmsTopicService topicService,
                                     ICmsTopicChapterService chapterService,
                                     ICmsTopicNodeService nodeService,
                                     IOmsTicketService ticketService,
                                     ICmsRegistrationMerchantService candidateService,
                                     IProductRoleService productRoleService,
                                     MmsMerchantMapper merchantMapper,
                                     CmsRegistrationMapper registrationMapper,
                                     CoopClubApplyMapper clubApplyMapper,
                                     CoopInviteMapper inviteMapper,
                                     CoopOrderMapper orderMapper,
                                     CoopGroupVerifyMapper groupVerifyMapper,
                                     CoopSettlementMapper settlementMapper,
                                     CoopComplaintMapper complaintMapper,
                                     ClubLeadMapper clubLeadMapper,
                                     ClubMapper clubMapper,
                                     UmsMemberMapper memberMapper,
                                     IMerchantOfferService merchantOfferService,
                                     ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer,
                                     TopicSettlementEvidenceService settlementEvidenceService) {
        this.topicService = topicService;
        this.topicOperationsScopeAuthorizer = topicOperationsScopeAuthorizer;
        this.chapterService = chapterService;
        this.nodeService = nodeService;
        this.ticketService = ticketService;
        this.candidateService = candidateService;
        this.productRoleService = productRoleService;
        this.merchantMapper = merchantMapper;
        this.registrationMapper = registrationMapper;
        this.settlementEvidenceService = settlementEvidenceService;
        this.clubApplyMapper = clubApplyMapper;
        this.inviteMapper = inviteMapper;
        this.orderMapper = orderMapper;
        this.groupVerifyMapper = groupVerifyMapper;
        this.settlementMapper = settlementMapper;
        this.complaintMapper = complaintMapper;
        this.clubLeadMapper = clubLeadMapper;
        this.clubMapper = clubMapper;
        this.memberMapper = memberMapper;
        this.merchantOfferService = merchantOfferService;
    }

    @Override
    @Transactional(readOnly = true)
    public TopicWorkspaceDTO getWorkspace(Long topicId) {
        if (!topicOperationsScopeAuthorizer.canViewTopic(topicId)) {
            return null;
        }
        CmsTopic topic = topicService.selectCmsTopicById(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
            return null;
        }

        List<CmsTopicChapter> chapters = loadChapters(topicId);
        List<OmsTicket> tickets = loadTickets(topicId);
        int activeSelfPlayPasses = hasConfiguredSelfPlayPass(topic)
                ? registrationMapper.countActiveSelfPlayPasses(topicId) : 0;
        List<TopicWorkspaceDTO.Ticket> ticketProjection = projectTickets(
                topic, tickets, activeSelfPlayPasses);
        List<TopicWorkspaceDTO.Candidate> candidates = loadCandidates(topicId);
        List<MerchantChapterOffer> offers = safe(merchantOfferService.adminQueue(topicId, null));
        List<CoopOrder> effectiveOrders = safe(orderMapper.selectEffectiveOrdersByTopicId(topicId));
        List<CoopGroupVerify> groupVerifications = safe(groupVerifyMapper.selectByTopicId(topicId));
        TopicWorkspaceDTO.MerchantSupply merchantSupply = summarizeMerchantSupply(
                chapters, candidates, offers, effectiveOrders, groupVerifications);
        TopicWorkspaceDTO.ClubFulfillment clubFulfillment = loadClubFulfillment(topicId, effectiveOrders);
        clubFulfillment.setState(deriveClubFulfillmentState(tickets, clubFulfillment));
        List<TopicWorkspaceDTO.Cooperation> cooperations = loadCooperations(effectiveOrders, groupVerifications);
        List<TopicWorkspaceDTO.Settlement> settlements = loadSettlements(topicId);

        TopicWorkspaceDTO result = new TopicWorkspaceDTO();
        TopicWorkspaceDTO.Publisher publisher = loadPublisher(topicId);
        result.setTopic(projectTopic(topic));
        result.setPublisher(publisher);
        result.setOperation(loadOperation(topic, chapters, tickets, ticketProjection,
                merchantSupply, clubFulfillment, publisher));
        result.setCapabilities(buildCapabilities());
        result.setGovernance(loadGovernance(topic));
        result.setChapters(projectChapters(chapters));
        result.setTickets(ticketProjection);
        result.setCandidates(candidates);
        result.setMerchantSupply(merchantSupply);
        result.setFulfillment(loadFulfillment(topicId));
        result.setClubFulfillment(clubFulfillment);
        result.setCooperations(cooperations);
        result.setSettlements(settlements);
        return result;
    }

    /**
     * 可用性描述数据源能否被本次请求读取，不描述单一主题是否恰好存在业务记录。
     * 空章节、空票种、零销售额、空合作单都是已接通后的真实空态，必须保留给前端。
     */
    private static TopicWorkspaceDTO.Capabilities buildCapabilities() {
        Date observedAt = new Date();
        TopicWorkspaceDTO.Capabilities result = new TopicWorkspaceDTO.Capabilities();
        result.setStructure(TopicWorkspaceDTO.AxisCapability.available(
                "TOPIC_STRUCTURE_PROJECTED", observedAt));
        result.setAudit(TopicWorkspaceDTO.AxisCapability.available(
                "TOPIC_GOVERNANCE_PROJECTED", observedAt));
        result.setCandidates(TopicWorkspaceDTO.AxisCapability.available(
                "MERCHANT_CANDIDATES_PROJECTED", observedAt));
        result.setMerchantSupply(TopicWorkspaceDTO.AxisCapability.available(
                "MERCHANT_CHAPTER_SUPPLY_PROJECTED", observedAt));
        result.setFulfillment(TopicWorkspaceDTO.AxisCapability.available(
                "TOPIC_FULFILLMENT_PROJECTED", observedAt));
        result.setClubFulfillment(TopicWorkspaceDTO.AxisCapability.available(
                "CLUB_FULFILLMENT_PROJECTED", observedAt));
        result.setTickets(TopicWorkspaceDTO.AxisCapability.available(
                "TOPIC_TICKETS_PROJECTED", observedAt));
        result.setCooperation(TopicWorkspaceDTO.AxisCapability.available(
                "COOPERATION_PROJECTED", observedAt));
        result.setSettlement(TopicWorkspaceDTO.AxisCapability.available(
                "SETTLEMENT_PROJECTED", observedAt));
        result.setRoam(TopicWorkspaceDTO.AxisCapability.unavailable(
                TopicWorkspaceDTO.CapabilityState.NOT_INTEGRATED,
                "ROAM_RELATION_UNDEFINED"));
        return result;
    }

    private List<CmsTopicChapter> loadChapters(Long topicId) {
        CmsTopicChapter probe = new CmsTopicChapter();
        probe.setTopicId(topicId);
        List<CmsTopicChapter> chapters = safe(chapterService.selectCmsTopicChapterList(probe));
        CmsTopicNode nodeProbe = new CmsTopicNode();
        nodeProbe.setTopicId(topicId);
        Map<Long, List<CmsTopicNode>> nodesByChapter = new HashMap<>();
        for (CmsTopicNode node : safe(nodeService.selectCmsTopicNodeList(nodeProbe))) {
            if (node.getChapterId() == null) {
                continue;
            }
            nodesByChapter.computeIfAbsent(node.getChapterId(), key -> new ArrayList<CmsTopicNode>())
                    .add(node);
        }
        for (CmsTopicChapter chapter : chapters) {
            chapter.setCmsTopicNodeList(nodesByChapter.getOrDefault(
                    chapter.getId(), Collections.<CmsTopicNode>emptyList()));
        }
        return chapters;
    }

    private List<OmsTicket> loadTickets(Long topicId) {
        OmsTicket probe = new OmsTicket();
        probe.setOwnerType(1L);
        probe.setOwnerId(topicId);
        return safe(ticketService.selectOmsTicketList(probe));
    }


    private static boolean hasConfiguredSelfPlayPass(CmsTopic topic) {
        return topic != null && Integer.valueOf(1).equals(topic.getSelfPlay())
                && topic.getSelfPlayPrice() != null
                && topic.getSelfPlayPrice().compareTo(BigDecimal.ZERO) > 0;
    }

    private static boolean hasInvalidSelfPlayPassConfiguration(CmsTopic topic) {
        return topic != null && Integer.valueOf(1).equals(topic.getSelfPlay())
                && !hasConfiguredSelfPlayPass(topic);
    }

    private static boolean requiresClubFulfillment(List<OmsTicket> tickets) {
        for (OmsTicket ticket : tickets) {
            if (Integer.valueOf(1).equals(ticket.getMode())
                    && !"self".equalsIgnoreCase(ticket.getSubType())) {
                return true;
            }
        }
        return false;
    }


    private static TopicWorkspaceDTO.ClubFulfillmentState deriveClubFulfillmentState(
            List<OmsTicket> tickets, TopicWorkspaceDTO.ClubFulfillment clubFulfillment) {
        if (clubFulfillment.getActiveSessionCount() > 0) {
            return TopicWorkspaceDTO.ClubFulfillmentState.LEADING;
        }
        if (clubFulfillment.getSignedCooperationCount() > 0) {
            return TopicWorkspaceDTO.ClubFulfillmentState.SIGNED;
        }
        if (clubFulfillment.getPendingInviteCount() > 0) {
            return TopicWorkspaceDTO.ClubFulfillmentState.INVITED;
        }
        if (clubFulfillment.getPendingApplicationCount() > 0) {
            return TopicWorkspaceDTO.ClubFulfillmentState.APPLYING;
        }
        if (!requiresClubFulfillment(tickets)) {
            return TopicWorkspaceDTO.ClubFulfillmentState.NOT_REQUIRED;
        }
        return TopicWorkspaceDTO.ClubFulfillmentState.UNCONFIRMED;
    }

    private static TopicWorkspaceDTO.Topic projectTopic(CmsTopic source) {
        TopicWorkspaceDTO.Topic result = new TopicWorkspaceDTO.Topic();
        result.setId(source.getId());
        result.setName(source.getName());
        result.setAddressName(source.getAddressName());
        result.setImgUrl(source.getImgUrl());
        result.setIsSettlement(source.getIsSettlement());
        return result;
    }

    private static List<TopicWorkspaceDTO.Chapter> projectChapters(List<CmsTopicChapter> source) {
        List<TopicWorkspaceDTO.Chapter> result = new ArrayList<>();
        for (CmsTopicChapter chapter : source) {
            TopicWorkspaceDTO.Chapter projected = new TopicWorkspaceDTO.Chapter();
            projected.setId(chapter.getId());
            projected.setName(chapter.getName());
            projected.setDescription(chapter.getDescription());
            List<TopicWorkspaceDTO.Node> nodes = new ArrayList<>();
            for (CmsTopicNode node : safe(chapter.getCmsTopicNodeList())) {
                TopicWorkspaceDTO.Node projectedNode = new TopicWorkspaceDTO.Node();
                projectedNode.setId(node.getId());
                projectedNode.setName(node.getName());
                projectedNode.setAddress(node.getAddress());
                projectedNode.setNodeTime(node.getNodeTime());
                projectedNode.setStatus(node.getStatus());
                nodes.add(projectedNode);
            }
            projected.setNodes(nodes);
            result.add(projected);
        }
        return result;
    }

    private static List<TopicWorkspaceDTO.Ticket> projectTickets(CmsTopic topic,
                                                                    List<OmsTicket> source,
                                                                    int activeSelfPlayPasses) {
        List<TopicWorkspaceDTO.Ticket> result = new ArrayList<>();
        for (OmsTicket ticket : source) {
            TopicWorkspaceDTO.Ticket projected = new TopicWorkspaceDTO.Ticket();
            projected.setKind("STANDARD_TICKET");
            projected.setId(ticket.getId());
            projected.setName(ticket.getName());
            projected.setPrice(ticket.getPrice());
            projected.setStartTime(ticket.getStartTime());
            projected.setEndTime(ticket.getEndTime());
            projected.setTotalInventory(ticket.getTotalInventory());
            projected.setRemainingInventory(ticket.getRemainingInventory());
            result.add(projected);
        }
        if (hasConfiguredSelfPlayPass(topic)) {
            TopicWorkspaceDTO.Ticket selfPlayPass = new TopicWorkspaceDTO.Ticket();
            selfPlayPass.setKind("SELF_PLAY_PASS");
            selfPlayPass.setName("自玩通行证");
            selfPlayPass.setPrice(topic.getSelfPlayPrice());
            Integer quota = topic.getSelfPlayQuota();
            if (quota != null && quota > 0) {
                selfPlayPass.setTotalInventory(quota.longValue());
                selfPlayPass.setRemainingInventory((long) Math.max(0, quota - activeSelfPlayPasses));
            }
            result.add(selfPlayPass);
        }
        return result;
    }

    private List<TopicWorkspaceDTO.Candidate> loadCandidates(Long topicId) {
        CmsRegistrationMerchant probe = new CmsRegistrationMerchant();
        probe.setTopicId(topicId);
        probe.setDelFlag(0);
        List<CmsRegistrationMerchant> sources = safe(candidateService.selectCmsRegistrationMerchantList(probe));
        Set<Long> memberIds = new LinkedHashSet<>();
        for (CmsRegistrationMerchant source : sources) {
            if (source.getMemberId() != null) {
                memberIds.add(source.getMemberId());
            }
        }
        Map<Long, String> merchantNames = new HashMap<>();
        if (!memberIds.isEmpty()) {
            for (MmsMerchant merchant : safe(merchantMapper.selectMerchantNamesByMemberIds(
                    new ArrayList<Long>(memberIds)))) {
                if (merchant.getMemberId() != null) {
                    merchantNames.put(merchant.getMemberId(), merchant.getName());
                }
            }
        }
        List<TopicWorkspaceDTO.Candidate> result = new ArrayList<>();
        for (CmsRegistrationMerchant source : sources) {
            TopicWorkspaceDTO.Candidate target = new TopicWorkspaceDTO.Candidate();
            target.setId(source.getId());
            target.setNodeId(source.getNodeId());
            target.setAddressName(source.getAddressName());
            target.setStatus(source.getStatus());
            target.setAuditStatus(source.getAuditStatus());
            target.setCreateTime(source.getCreateTime());
            target.setMerchantName(merchantNames.get(source.getMemberId()));
            result.add(target);
        }
        return result;
    }

    private TopicWorkspaceDTO.Fulfillment loadFulfillment(Long topicId) {
        TopicWorkspaceDTO.Fulfillment result = new TopicWorkspaceDTO.Fulfillment();
        TopicSettlementEvidenceService.Summary evidence = settlementEvidenceService.readTopic(topicId);
        result.setTotalSales(zeroIfNull(evidence.getTotalSales()));
        result.setVerifiedHeads(evidence.getVerifiedHeads());
        result.setVerifiedSales(zeroIfNull(evidence.getVerifiedSales()));
        result.setOpenComplaintCount(complaintMapper.countOpenByTopicId(topicId));
        return result;
    }

    private TopicWorkspaceDTO.ClubFulfillment loadClubFulfillment(Long topicId,
                                                                    List<CoopOrder> effectiveOrders) {
        TopicWorkspaceDTO.ClubFulfillment result = new TopicWorkspaceDTO.ClubFulfillment();
        Map<Long, TopicWorkspaceDTO.ClubFulfillment.ClubRelationship> relationships =
                new LinkedHashMap<>();

        CoopClubApply applyProbe = new CoopClubApply();
        applyProbe.setTopicId(topicId);
        for (CoopClubApply application : safe(clubApplyMapper.selectCoopClubApplyList(applyProbe))) {
            if (application.getClubId() == null) {
                continue;
            }
            TopicWorkspaceDTO.ClubFulfillment.ClubRelationship row =
                    relationshipFor(relationships, application.getClubId());
            row.setApplicationStatus(application.getStatus());
            row.setApplicationMessage(application.getMessage());
            row.setApplicationTime(application.getCreateTime());
            if (Integer.valueOf(0).equals(application.getStatus())) {
                result.setPendingApplicationCount(result.getPendingApplicationCount() + 1);
            }
        }

        CoopInvite inviteProbe = new CoopInvite();
        inviteProbe.setTopicId(topicId);
        for (CoopInvite invite : safe(inviteMapper.selectCoopInviteList(inviteProbe))) {
            if (!"club".equals(invite.getToType()) || invite.getToId() == null) {
                continue;
            }
            TopicWorkspaceDTO.ClubFulfillment.ClubRelationship row =
                    relationshipFor(relationships, invite.getToId());
            row.setInviteId(invite.getId());
            row.setInviteStatus(invite.getStatus());
            row.setInviteMessage(invite.getMessage());
            row.setInviteTime(invite.getCreateTime());
            row.setDepositStatus(invite.getDepositStatus());
            row.setDepositAmount(invite.getDepositAmount());
            if (Integer.valueOf(1).equals(invite.getStatus())) {
                recordDeposit(result.getDepositSummary(), invite.getDepositStatus());
            }
            if (Integer.valueOf(0).equals(invite.getStatus())) {
                result.setPendingInviteCount(result.getPendingInviteCount() + 1);
            }
        }

        for (CoopOrder order : effectiveOrders) {
            if (!"club".equals(order.getToType()) || order.getToId() == null) {
                continue;
            }
            TopicWorkspaceDTO.ClubFulfillment.ClubRelationship row =
                    relationshipFor(relationships, order.getToId());
            row.setCooperationId(order.getId());
            row.setCooperationTime(order.getCreateTime());
            result.setSignedCooperationCount(result.getSignedCooperationCount() + 1);
        }

        List<TopicWorkspaceDTO.ClubFulfillment.Session> sessions =
                safe(clubLeadMapper.selectLeadSessionsByTopic(topicId));
        Map<Long, Integer> sessionCountByClub = new HashMap<>();
        int activeSessionCount = 0;
        for (TopicWorkspaceDTO.ClubFulfillment.Session session : sessions) {
            if (session.getClubId() != null) {
                sessionCountByClub.put(session.getClubId(),
                        sessionCountByClub.getOrDefault(session.getClubId(), 0) + 1);
            }
            if (isActiveLeadSession(session.getStatus())) {
                activeSessionCount++;
            }
        }
        result.setSessions(sessions);
        result.setActiveSessionCount(activeSessionCount);
        result.setAttendanceCount(safe(clubLeadMapper.selectLeadAttendanceByTopic(topicId)).size());

        Map<Long, Club> clubsById = new HashMap<>();
        if (!relationships.isEmpty()) {
            List<Long> clubIds = new ArrayList<>(relationships.keySet());
            for (Club club : safe(clubMapper.selectClubsByIds(clubIds))) {
                if (club.getId() != null) {
                    clubsById.put(club.getId(), club);
                }
            }
        }
        for (TopicWorkspaceDTO.ClubFulfillment.ClubRelationship row : relationships.values()) {
            Club club = clubsById.get(row.getClubId());
            if (club == null) {
                row.setClubName("俱乐部 #" + row.getClubId());
            } else {
                row.setClubName(club.getName());
                row.setLeaderName(club.getLeaderName());
                row.setCapabilityTags(club.getTags());
            }
            row.setSessionCount(sessionCountByClub.getOrDefault(row.getClubId(), 0));
        }
        result.setRelationships(new ArrayList<>(relationships.values()));
        return result;
    }

    /** 只有集合中或行进中的场次能证明当前正在带队；已结算仍保留在履约历史。 */
    private static boolean isActiveLeadSession(Integer status) {
        return Integer.valueOf(1).equals(status) || Integer.valueOf(2).equals(status);
    }

    private static void recordDeposit(TopicWorkspaceDTO.ClubFulfillment.DepositSummary summary,
                                      Integer depositStatus) {
        if (depositStatus == null) {
            summary.setUnknownCount(summary.getUnknownCount() + 1);
            return;
        }
        switch (depositStatus) {
            case 0:
                summary.setAwaitingPricingCount(summary.getAwaitingPricingCount() + 1);
                break;
            case 1:
                summary.setPayingCount(summary.getPayingCount() + 1);
                break;
            case 2:
                summary.setFrozenCount(summary.getFrozenCount() + 1);
                break;
            case 3:
                summary.setDeductedCount(summary.getDeductedCount() + 1);
                break;
            case 4:
                summary.setRefundedCount(summary.getRefundedCount() + 1);
                break;
            case 5:
                summary.setPayableCount(summary.getPayableCount() + 1);
                break;
            default:
                summary.setUnknownCount(summary.getUnknownCount() + 1);
                break;
        }
    }

    private static TopicWorkspaceDTO.ClubFulfillment.ClubRelationship relationshipFor(
            Map<Long, TopicWorkspaceDTO.ClubFulfillment.ClubRelationship> relationships, Long clubId) {
        TopicWorkspaceDTO.ClubFulfillment.ClubRelationship row = relationships.get(clubId);
        if (row == null) {
            row = new TopicWorkspaceDTO.ClubFulfillment.ClubRelationship();
            row.setClubId(clubId);
            relationships.put(clubId, row);
        }
        return row;
    }

    private List<TopicWorkspaceDTO.Cooperation> loadCooperations(List<CoopOrder> effectiveOrders,
                                                                   List<CoopGroupVerify> groupVerifications) {
        Map<Long, Integer> verifiedGroupCountByCooperation = new HashMap<>();
        for (CoopGroupVerify verification : groupVerifications) {
            if (verification.getCoopOrderId() != null) {
                verifiedGroupCountByCooperation.put(verification.getCoopOrderId(),
                        verifiedGroupCountByCooperation.getOrDefault(verification.getCoopOrderId(), 0) + 1);
            }
        }
        List<TopicWorkspaceDTO.Cooperation> result = new ArrayList<>();
        for (CoopOrder order : effectiveOrders) {
            TopicWorkspaceDTO.Cooperation row = new TopicWorkspaceDTO.Cooperation();
            row.setId(order.getId());
            row.setRegistrationId(order.getRegId());
            row.setCounterpartyId(order.getToId());
            row.setCounterpartyType(order.getToType());
            row.setShareMode(order.getShareMode());
            row.setShareRate(order.getShareRate());
            row.setFixedFee(order.getFixedFee());
            row.setStatus(order.getStatus());
            row.setCreateTime(order.getCreateTime());
            row.setVerifiedGroupCount(verifiedGroupCountByCooperation.getOrDefault(order.getId(), 0));
            result.add(row);
        }
        return result;
    }

    private List<TopicWorkspaceDTO.Settlement> loadSettlements(Long topicId) {
        List<TopicWorkspaceDTO.Settlement> result = new ArrayList<>();
        for (CoopSettlement settlement : safe(settlementMapper.selectByTopicId(topicId))) {
            TopicWorkspaceDTO.Settlement row = new TopicWorkspaceDTO.Settlement();
            row.setId(settlement.getId());
            row.setCooperationId(settlement.getCoopOrderId());
            row.setPayeeType(settlement.getPayeeType());
            row.setLineType(settlement.getLineType());
            row.setStatus(settlement.getStatus());
            row.setPayableTime(settlement.getPayableTime());
            row.setPayoutTime(settlement.getPayoutTime());
            result.add(row);
        }
        return result;
    }

    private static TopicWorkspaceDTO.MerchantSupply summarizeMerchantSupply(
            List<CmsTopicChapter> chapters, List<TopicWorkspaceDTO.Candidate> candidates,
            List<MerchantChapterOffer> offers, List<CoopOrder> effectiveOrders,
            List<CoopGroupVerify> groupVerifications) {
        TopicWorkspaceDTO.MerchantSupply result = new TopicWorkspaceDTO.MerchantSupply();
        Map<Long, Long> uniqueMerchantCooperationIds = new HashMap<>();
        Set<Long> merchantsWithMultipleCooperations = new LinkedHashSet<>();
        for (CoopOrder order : effectiveOrders) {
            if (!"merchant".equalsIgnoreCase(order.getToType())
                    || order.getToId() == null || order.getId() == null) {
                continue;
            }
            if (uniqueMerchantCooperationIds.containsKey(order.getToId())) {
                uniqueMerchantCooperationIds.remove(order.getToId());
                merchantsWithMultipleCooperations.add(order.getToId());
            } else if (!merchantsWithMultipleCooperations.contains(order.getToId())) {
                uniqueMerchantCooperationIds.put(order.getToId(), order.getId());
            }
        }
        Map<Long, Integer> verifiedGroupsByCooperation = new HashMap<>();
        for (CoopGroupVerify verification : groupVerifications) {
            if (verification.getCoopOrderId() != null) {
                verifiedGroupsByCooperation.put(verification.getCoopOrderId(),
                        verifiedGroupsByCooperation.getOrDefault(verification.getCoopOrderId(), 0) + 1);
            }
        }
        result.setCandidateCount(candidates.size());
        for (TopicWorkspaceDTO.Candidate candidate : candidates) {
            if (Long.valueOf(0L).equals(candidate.getStatus())) {
                result.setPendingReviewCount(result.getPendingReviewCount() + 1);
            }
            if (Long.valueOf(1L).equals(candidate.getStatus())) {
                result.setApprovedCount(result.getApprovedCount() + 1);
            }
            if (Long.valueOf(1L).equals(candidate.getAuditStatus())) {
                result.setAwardedCount(result.getAwardedCount() + 1);
            }
        }

        Map<Long, Long> chapterIdByNodeId = new HashMap<>();
        for (CmsTopicChapter chapter : chapters) {
            for (CmsTopicNode node : safe(chapter.getCmsTopicNodeList())) {
                if (node.getId() != null) {
                    chapterIdByNodeId.put(node.getId(), chapter.getId());
                }
            }
        }
        Map<Long, Integer> candidateCountByChapter = new HashMap<>();
        for (TopicWorkspaceDTO.Candidate candidate : candidates) {
            Long chapterId = chapterIdByNodeId.get(candidate.getNodeId());
            if (chapterId != null) {
                candidateCountByChapter.put(chapterId,
                        candidateCountByChapter.getOrDefault(chapterId, 0) + 1);
            }
        }
        Map<Long, List<MerchantChapterOffer>> offersByChapter = new HashMap<>();
        for (MerchantChapterOffer offer : offers) {
            if (offer.getChapterId() != null) {
                offersByChapter.computeIfAbsent(offer.getChapterId(),
                        key -> new ArrayList<MerchantChapterOffer>()).add(offer);
            }
        }

        Date now = new Date();
        List<TopicWorkspaceDTO.MerchantSupply.ChapterSupply> chapterSupplies = new ArrayList<>();
        for (CmsTopicChapter chapter : chapters) {
            TopicWorkspaceDTO.MerchantSupply.ChapterSupply supply =
                    new TopicWorkspaceDTO.MerchantSupply.ChapterSupply();
            supply.setChapterId(chapter.getId());
            supply.setChapterName(chapter.getName());
            supply.setRequired(isRequired(chapter));
            supply.setTargetMerchantCount(targetMerchantCount(chapter));
            supply.setCandidateCount(candidateCountByChapter.getOrDefault(chapter.getId(), 0));

            List<TopicWorkspaceDTO.MerchantSupply.Offer> projectedOffers = new ArrayList<>();
            for (MerchantChapterOffer offer : offersByChapter.getOrDefault(
                    chapter.getId(), Collections.<MerchantChapterOffer>emptyList())) {
                boolean active = isActiveOffer(offer, now);
                TopicWorkspaceDTO.MerchantSupply.Offer projected =
                        new TopicWorkspaceDTO.MerchantSupply.Offer();
                projected.setId(offer.getId());
                projected.setMerchantId(offer.getMerchantId());
                projected.setMerchantName(offer.getMerchantName());
                Long cooperationId = uniqueMerchantCooperationIds.get(offer.getMerchantId());
                projected.setCooperationId(cooperationId);
                projected.setVerifiedGroupCount(cooperationId == null ? 0
                        : verifiedGroupsByCooperation.getOrDefault(cooperationId, 0));
                projected.setStatus(offer.getStatus());
                projected.setActive(active);
                projected.setQuotaTotal(offer.getQuotaTotal());
                projected.setQuotaUsed(offer.getQuotaUsed());
                projected.setValidEnd(offer.getValidEnd());
                projected.setTermsMode(offer.getTermsMode());
                projectedOffers.add(projected);
                if (active) {
                    supply.setActiveOfferCount(supply.getActiveOfferCount() + 1);
                    result.setActiveOfferCount(result.getActiveOfferCount() + 1);
                } else {
                    supply.setInactiveOfferCount(supply.getInactiveOfferCount() + 1);
                    result.setInactiveOfferCount(result.getInactiveOfferCount() + 1);
                }
            }
            supply.setOffers(projectedOffers);
            if (supply.getTargetMerchantCount() != null) {
                supply.setMissingMerchantCount(Math.max(0,
                        supply.getTargetMerchantCount() - supply.getActiveOfferCount()));
            }

            if (supply.isRequired()) {
                result.setRequiredChapterCount(result.getRequiredChapterCount() + 1);
                if (supply.getMissingMerchantCount() != null
                        && supply.getMissingMerchantCount() == 0) {
                    result.setSuppliedRequiredChapterCount(
                            result.getSuppliedRequiredChapterCount() + 1);
                } else {
                    result.setMissingRequiredChapterCount(
                            result.getMissingRequiredChapterCount() + 1);
                }
            }
            chapterSupplies.add(supply);
        }
        result.setChapters(chapterSupplies);
        return result;
    }

    private static boolean isActiveOffer(MerchantChapterOffer offer, Date now) {
        return Integer.valueOf(1).equals(offer.getStatus())
                && (offer.getValidEnd() == null || offer.getValidEnd().after(now));
    }

    private static boolean isRequired(CmsTopicChapter chapter) {
        return Integer.valueOf(1).equals(chapter.getRequired());
    }

    private static Integer positiveOrNull(Integer value) {
        return value != null && value > 0 ? value : null;
    }


    private static Integer targetMerchantCount(CmsTopicChapter chapter) {
        Integer configured = positiveOrNull(chapter.getMaxMerchant());
        if (isRequired(chapter)) {
            return configured == null ? Integer.valueOf(1) : configured;
        }
        return configured;
    }

    private static TopicWorkspaceDTO.Operation loadOperation(CmsTopic topic,
                                                             List<CmsTopicChapter> chapters,
                                                             List<OmsTicket> tickets,
                                                             List<TopicWorkspaceDTO.Ticket> ticketProjection,
                                                             TopicWorkspaceDTO.MerchantSupply merchantSupply,
                                                             TopicWorkspaceDTO.ClubFulfillment clubFulfillment,
                                                             TopicWorkspaceDTO.Publisher publisher) {
        TopicWorkspaceDTO.Operation result = new TopicWorkspaceDTO.Operation();
        result.setProductType(topic.getProductType());
        result.setLifecycle(topic.getLifecycle());
        result.setMerchantPoolStatus(topic.getMerchantStatus());
        result.setClubPoolStatus(topic.getOpenClubPool());
        result.setMerchantSignUpStartDate(topic.getMerchantSignUpStartDate());
        result.setMerchantSignUpEndDate(topic.getMerchantSignUpEndDate());
        result.setRecruitDeadline(topic.getRecruitDeadline());
        result.setStartDate(topic.getStartDate());
        result.setEndDate(topic.getEndDate());
        result.setLastEditedBy(topic.getUpdateBy());
        result.setLastEditedAt(topic.getUpdateTime());

        List<TopicWorkspaceDTO.RunBlocker> blockers = deriveRunBlockers(
                topic, chapters, tickets, ticketProjection, merchantSupply, clubFulfillment);
        result.setRunBlockers(blockers);
        result.setRunReadiness(deriveRunReadiness(blockers));
        result.setNextAction(deriveNextAction(topic.getLifecycle(), blockers));
        result.setEditPolicy(deriveEditPolicy(topic.getLifecycle()));
        result.setResponsibility(deriveResponsibility(publisher, clubFulfillment, merchantSupply));
        return result;
    }

    private static TopicWorkspaceDTO.Responsibility deriveResponsibility(
            TopicWorkspaceDTO.Publisher publisher,
            TopicWorkspaceDTO.ClubFulfillment clubFulfillment,
            TopicWorkspaceDTO.MerchantSupply merchantSupply) {
        TopicWorkspaceDTO.Responsibility result = new TopicWorkspaceDTO.Responsibility();
        List<TopicWorkspaceDTO.ClubFulfillment.ClubRelationship> signedClubs = new ArrayList<>();
        for (TopicWorkspaceDTO.ClubFulfillment.ClubRelationship relationship
                : clubFulfillment.getRelationships()) {
            if (relationship.getCooperationId() != null) {
                signedClubs.add(relationship);
            }
        }

        if (signedClubs.isEmpty()) {
            result.setOrganizerLabel(publisher.isHistoricalPending() ? "主体待确认"
                    : publisher.getDisplayName());
            result.setOrganizerState(publisher.isHistoricalPending()
                    ? "CONTENT_SUBJECT_UNCONFIRMED" : "CONTENT_SUBJECT_DEFAULT");
        } else if (signedClubs.size() == 1 && signedClubs.get(0).getSessionCount() > 0) {
            result.setOrganizerLabel(signedClubs.get(0).getClubName());
            result.setOrganizerState("CLUB_LEADING");
        } else {
            result.setOrganizerLabel("待明确");
            result.setOrganizerState("RESPONSIBILITY_UNCONFIRMED");
        }

        Set<String> fulfillers = new LinkedHashSet<>();
        for (TopicWorkspaceDTO.ClubFulfillment.ClubRelationship relationship : signedClubs) {
            fulfillers.add("俱乐部 · " + (relationship.getClubName() == null
                    ? "信息待确认" : relationship.getClubName()));
        }
        for (TopicWorkspaceDTO.MerchantSupply.ChapterSupply chapter : merchantSupply.getChapters()) {
            for (TopicWorkspaceDTO.MerchantSupply.Offer offer : chapter.getOffers()) {
                if (offer.isActive()) {
                    fulfillers.add("商家 · " + (offer.getMerchantName() == null
                            ? "信息待确认" : offer.getMerchantName()));
                }
            }
        }
        result.setActualFulfillers(new ArrayList<>(fulfillers));
        return result;
    }

    private static List<TopicWorkspaceDTO.RunBlocker> deriveRunBlockers(
            CmsTopic topic, List<CmsTopicChapter> chapters, List<OmsTicket> tickets,
            List<TopicWorkspaceDTO.Ticket> ticketProjection,
            TopicWorkspaceDTO.MerchantSupply merchantSupply,
            TopicWorkspaceDTO.ClubFulfillment clubFulfillment) {
        return com.chengyinhub.business.service.support.TopicRunReadinessPolicy.derive(topic,
                chapters.isEmpty() || containsChapterWithoutNodes(chapters),
                ticketProjection.isEmpty(),
                hasInvalidSelfPlayPassConfiguration(topic),
                hasRequiredSupplyGap(merchantSupply),
                clubFulfillment.getPendingInviteCount() > 0,
                requiresClubFulfillment(tickets)
                        && clubFulfillment.getSignedCooperationCount() == 0);
    }

    private static boolean containsChapterWithoutNodes(List<CmsTopicChapter> chapters) {
        for (CmsTopicChapter chapter : chapters) {
            if (safe(chapter.getCmsTopicNodeList()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRequiredSupplyGap(TopicWorkspaceDTO.MerchantSupply merchantSupply) {
        for (TopicWorkspaceDTO.MerchantSupply.ChapterSupply supply : merchantSupply.getChapters()) {
            if (!supply.isRequired()) {
                continue;
            }
            if (supply.getActiveOfferCount() == 0
                    || (supply.getMissingMerchantCount() != null
                    && supply.getMissingMerchantCount() > 0)) {
                return true;
            }
        }
        return false;
    }

    private static TopicWorkspaceDTO.RunReadinessState deriveRunReadiness(
            List<TopicWorkspaceDTO.RunBlocker> blockers) {
        return com.chengyinhub.business.service.support.TopicRunReadinessPolicy.classify(blockers);
    }

    private static TopicWorkspaceDTO.NextAction deriveNextAction(Integer lifecycle,
                                                                  List<TopicWorkspaceDTO.RunBlocker> blockers) {
        com.chengyinhub.business.domain.enums.TopicLifecycleState state;
        try {
            state = com.chengyinhub.business.domain.enums.TopicLifecycleState.fromCode(lifecycle);
        } catch (RuntimeException e) {
            return TopicWorkspaceDTO.NextAction.VIEW_REVIEW;
        }
        if (state == com.chengyinhub.business.domain.enums.TopicLifecycleState.PENDING_REVIEW) {
            return TopicWorkspaceDTO.NextAction.VIEW_REVIEW;
        }
        if (state == com.chengyinhub.business.domain.enums.TopicLifecycleState.CLOSED) {
            return TopicWorkspaceDTO.NextAction.VIEW_REVIEW;
        }
        if (state == com.chengyinhub.business.domain.enums.TopicLifecycleState.SETTLING) {
            return TopicWorkspaceDTO.NextAction.SETTLE_AND_REVIEW;
        }
        if (state == com.chengyinhub.business.domain.enums.TopicLifecycleState.DRAFT
                || blockers.contains(TopicWorkspaceDTO.RunBlocker.TICKET_CONFIGURATION_MISSING)
                || blockers.contains(TopicWorkspaceDTO.RunBlocker.SELF_PLAY_PASS_CONFIGURATION_MISSING)) {
            return TopicWorkspaceDTO.NextAction.EDIT_CONTENT;
        }
        if (state == com.chengyinhub.business.domain.enums.TopicLifecycleState.RECRUITING) {
            return TopicWorkspaceDTO.NextAction.HANDLE_RECRUITMENT;
        }
        if (blockers.contains(TopicWorkspaceDTO.RunBlocker.REQUIRED_CHAPTER_SUPPLY_MISSING)
                || blockers.contains(TopicWorkspaceDTO.RunBlocker.CLUB_INVITE_PENDING)
                || blockers.contains(TopicWorkspaceDTO.RunBlocker.CLUB_FULFILLMENT_UNCONFIRMED)) {
            return TopicWorkspaceDTO.NextAction.RESOLVE_SUPPLY_GAPS;
        }
        if (state == com.chengyinhub.business.domain.enums.TopicLifecycleState.PRICING
                || state == com.chengyinhub.business.domain.enums.TopicLifecycleState.SELLING) {
            return TopicWorkspaceDTO.NextAction.LOCK_LINEUP;
        }
        if (state == com.chengyinhub.business.domain.enums.TopicLifecycleState.RUNNING) {
            return TopicWorkspaceDTO.NextAction.MONITOR_FULFILLMENT;
        }
        return TopicWorkspaceDTO.NextAction.VIEW_REVIEW;
    }

    private static TopicWorkspaceDTO.EditPolicy deriveEditPolicy(Integer lifecycle) {
        com.chengyinhub.business.domain.enums.TopicLifecycleState state;
        try {
            state = com.chengyinhub.business.domain.enums.TopicLifecycleState.fromCode(lifecycle);
        } catch (RuntimeException e) {
            return TopicWorkspaceDTO.EditPolicy.LOCKED;
        }
        if (state == com.chengyinhub.business.domain.enums.TopicLifecycleState.DRAFT
                || state == com.chengyinhub.business.domain.enums.TopicLifecycleState.RECRUITING
                || state == com.chengyinhub.business.domain.enums.TopicLifecycleState.PENDING_REVIEW) {
            return TopicWorkspaceDTO.EditPolicy.FULL;
        }
        if (state == com.chengyinhub.business.domain.enums.TopicLifecycleState.PRICING
                || state == com.chengyinhub.business.domain.enums.TopicLifecycleState.SELLING
                || state == com.chengyinhub.business.domain.enums.TopicLifecycleState.RUNNING) {
            return TopicWorkspaceDTO.EditPolicy.CONTENT_CORRECTION_ONLY;
        }
        return TopicWorkspaceDTO.EditPolicy.LOCKED;
    }

    private static TopicWorkspaceDTO.Governance loadGovernance(CmsTopic topic) {
        TopicWorkspaceDTO.Governance result = new TopicWorkspaceDTO.Governance();
        result.setCheckStatus(topic.getCheckStatus());
        result.setUserStatus(topic.getUserStatus());
        result.setReason(topic.getReason());
        return result;
    }

    private TopicWorkspaceDTO.Publisher loadPublisher(Long topicId) {
        TopicWorkspaceDTO.Publisher result = new TopicWorkspaceDTO.Publisher();
        ProductRole contentSlot = productRoleService.activeContentSlot(topicId);
        if (contentSlot == null) {
            return result;
        }
        result.setEntityType(contentSlot.getEntityType());
        result.setDisplayName(resolvePublisherDisplayName(contentSlot));
        result.setHistoricalPending(false);
        return result;
    }

    /**
     * 内容主体显示名只读取主体自己的公开展示字段；无法解析时只标记待确认，
     * 不能泄露主体内部标识，也不能用主题创建者或公开池状态猜测发布主体。
     */
    private String resolvePublisherDisplayName(ProductRole contentSlot) {
        if ("PLATFORM".equals(contentSlot.getEntityType())) {
            return "官方";
        }
        if ("CLUB".equals(contentSlot.getEntityType()) && contentSlot.getEntityId() != null) {
            Club club = clubMapper.selectClubById(contentSlot.getEntityId());
            return club == null || club.getName() == null ? "俱乐部信息待确认" : club.getName();
        }
        if ("MEMBER".equals(contentSlot.getEntityType()) && contentSlot.getEntityId() != null) {
            UmsMember member = memberMapper.selectUmsMemberById(contentSlot.getEntityId());
            if (member != null) {
                if (member.getNickname() != null && !member.getNickname().trim().isEmpty()) {
                    return member.getNickname();
                }
                if (member.getName() != null && !member.getName().trim().isEmpty()) {
                    return member.getName();
                }
            }
            return "会员信息待确认";
        }
        return "主体待确认";
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static <T> List<T> safe(List<T> value) {
        return value == null ? Collections.<T>emptyList() : value;
    }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopClubApply;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.dto.TopicOperationsSnapshotDTO;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopClubApplyService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ITopicOperationsSnapshotService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * P0 统一待办投影。
 *
 * <p>这里不调用调配、供给、邀约或结算的任何写服务。当前仓库尚无“后台账号 → 主题受托范围”的
 * 权威真源，故历史深链和所有写动作均保持禁用；这比以菜单权限、前端路由或 memberId 猜测放行安全。</p>
 */
@Service
public class TopicOperationsSnapshotServiceImpl implements ITopicOperationsSnapshotService {

    static final String ACTIONABLE = "ACTIONABLE";
    static final String WAITING_EXTERNAL = "WAITING_EXTERNAL";
    static final String BLOCKED = "BLOCKED";
    static final String READ_ONLY = "READ_ONLY";

    static final String BLOCKED_OBJECT_AUTHORIZATION_UNAVAILABLE =
            "BLOCKED_OBJECT_AUTHORIZATION_UNAVAILABLE";
    static final String BLOCKED_P1_COMMERCIAL_GATES = "BLOCKED_P1_COMMERCIAL_GATES";

    private final ICmsTopicService topicService;
    private final ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;
    private final IProductRoleService productRoleService;
    private final ICmsRegistrationMerchantService registrationMerchantService;
    private final MerchantChapterOfferMapper offerMapper;
    private final ICoopClubApplyService clubApplyService;
    private final CoopInviteMapper inviteMapper;
    private final CoopOrderMapper orderMapper;

    public TopicOperationsSnapshotServiceImpl(ICmsTopicService topicService,
                                               ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer,
                                               IProductRoleService productRoleService,
                                               ICmsRegistrationMerchantService registrationMerchantService,
                                               MerchantChapterOfferMapper offerMapper,
                                               ICoopClubApplyService clubApplyService,
                                               CoopInviteMapper inviteMapper,
                                               CoopOrderMapper orderMapper) {
        this.topicService = topicService;
        this.topicOperationsScopeAuthorizer = topicOperationsScopeAuthorizer;
        this.productRoleService = productRoleService;
        this.registrationMerchantService = registrationMerchantService;
        this.offerMapper = offerMapper;
        this.clubApplyService = clubApplyService;
        this.inviteMapper = inviteMapper;
        this.orderMapper = orderMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public TopicOperationsSnapshotDTO getSnapshot(Long topicId) {
        if (!topicOperationsScopeAuthorizer.canViewTopic(topicId)) {
            return TopicOperationsSnapshotDTO.unavailable("BLOCKED_TOPIC_SCOPE_UNAVAILABLE");
        }
        CmsTopic topic = topicService.selectCmsTopicById(topicId);
        if (topic == null || isDeleted(topic)) {
            return null;
        }

        TopicOperationsSnapshotDTO result = new TopicOperationsSnapshotDTO();
        result.setTopic(topicRef(topic));

        List<CoopInvite> clubInvites = findClubInvites(topicId);
        List<CoopOrder> clubCooperations = findClubCooperations(topicId);

        List<TopicOperationsSnapshotDTO.OperationItemDTO> items = new ArrayList<>();
        items.addAll(loadMerchantRegistrations(topicId));
        items.addAll(loadMerchantOffers(topicId));
        items.addAll(loadClubApplies(topicId, clubInvites, clubCooperations));
        items.addAll(loadClubInvites(topicId, clubInvites));
        items.addAll(loadClubCooperations(topicId, clubCooperations));
        sortByLatestAt(items);

        result.setItems(items);
        result.setSummary(summarize(items));
        return result;
    }

    private TopicOperationsSnapshotDTO.TopicRefDTO topicRef(CmsTopic topic) {
        TopicOperationsSnapshotDTO.TopicRefDTO result = new TopicOperationsSnapshotDTO.TopicRefDTO();
        result.setId(topic.getId());
        ProductRole content = productRoleService.activeContentSlot(topic.getId());
        if (content == null) {
            result.setContentOwnershipState("UNRESOLVED");
            result.setReadOnlyReasonCode("BLOCKED_CONTENT_OWNERSHIP_UNRESOLVED");
            return result;
        }
        result.setContentOwnershipState("DECLARED");
        result.setContentOwnerType(content.getEntityType());
        result.setContentOwnerId(content.getEntityId());
        return result;
    }

    private List<TopicOperationsSnapshotDTO.OperationItemDTO> loadMerchantRegistrations(Long topicId) {
        CmsRegistrationMerchant query = new CmsRegistrationMerchant();
        query.setTopicId(topicId);
        query.setDelFlag(0);

        List<TopicOperationsSnapshotDTO.OperationItemDTO> result = new ArrayList<>();
        for (CmsRegistrationMerchant source : safe(registrationMerchantService.selectCmsRegistrationMerchantList(query))) {
            String sourceState = merchantSourceState(source);
            String actionability = "PENDING_DISPATCH".equals(sourceState)
                    || "REGISTRATION_PENDING_REVIEW".equals(sourceState) ? BLOCKED : READ_ONLY;
            TopicOperationsSnapshotDTO.OperationItemDTO item = item("MERCHANT_REGISTRATION", source.getId(), topicId,
                    sourceState, merchantDisplayState(sourceState), actionability, latest(source.getUpdateTime(), source.getCreateTime()));
            if ("PENDING_DISPATCH".equals(sourceState)) {
                item.setAllowedActions(actions(
                        disabledAction("VIEW_EVIDENCE", BLOCKED_OBJECT_AUTHORIZATION_UNAVAILABLE),
                        disabledAction("OPEN_LEGACY_DISPATCH", BLOCKED_OBJECT_AUTHORIZATION_UNAVAILABLE)));
            } else {
                item.setAllowedActions(actions(disabledAction("VIEW_EVIDENCE", BLOCKED_OBJECT_AUTHORIZATION_UNAVAILABLE)));
            }
            result.add(item);
        }
        return result;
    }

    private List<TopicOperationsSnapshotDTO.OperationItemDTO> loadMerchantOffers(Long topicId) {
        List<TopicOperationsSnapshotDTO.OperationItemDTO> result = new ArrayList<>();
        for (MerchantChapterOffer source : safe(offerMapper.selectByTopic(topicId))) {
            TopicOperationsSnapshotDTO.OperationItemDTO item = item("MERCHANT_CHAPTER_OFFER", source.getId(), topicId,
                    "OFFER_ACTIVE", "生效供给", READ_ONLY, latest(source.getUpdateTime(), source.getCreateTime()));
            item.setAllowedActions(actions(disabledAction("OPEN_LEGACY_OFFER", BLOCKED_OBJECT_AUTHORIZATION_UNAVAILABLE)));
            result.add(item);
        }
        return result;
    }

    private List<TopicOperationsSnapshotDTO.OperationItemDTO> loadClubApplies(Long topicId,
                                                                                List<CoopInvite> clubInvites,
                                                                                List<CoopOrder> clubCooperations) {
        List<TopicOperationsSnapshotDTO.OperationItemDTO> result = new ArrayList<>();
        for (CoopClubApply source : safe(clubApplyService.selectByTopic(topicId))) {
            ClubApplyState derivedState = deriveClubApplyState(source, clubInvites, clubCooperations);
            TopicOperationsSnapshotDTO.OperationItemDTO item = item("CLUB_APPLICATION", source.getId(), topicId,
                    derivedState.getSourceState(), derivedState.getDisplayState(), derivedState.getActionability(),
                    derivedState.getLatestAt());
            item.setAllowedActions(actions(disabledAction("VIEW_EVIDENCE", BLOCKED_P1_COMMERCIAL_GATES)));
            result.add(item);
        }
        return result;
    }

    private List<TopicOperationsSnapshotDTO.OperationItemDTO> loadClubInvites(Long topicId, List<CoopInvite> sources) {
        List<TopicOperationsSnapshotDTO.OperationItemDTO> result = new ArrayList<>();
        for (CoopInvite source : sources) {
            String sourceState = clubInviteState(source.getStatus());
            String actionability = "PENDING".equals(sourceState) ? WAITING_EXTERNAL : READ_ONLY;
            TopicOperationsSnapshotDTO.OperationItemDTO item = item("CLUB_INVITE", source.getId(), topicId,
                    sourceState, clubInviteDisplayState(sourceState), actionability,
                    latest(source.getHandleTime(), latest(source.getUpdateTime(), source.getCreateTime())));
            item.setAllowedActions(actions(disabledAction("VIEW_EVIDENCE", BLOCKED_P1_COMMERCIAL_GATES)));
            result.add(item);
        }
        return result;
    }

    private List<CoopInvite> findClubInvites(Long topicId) {
        CoopInvite query = new CoopInvite();
        query.setTopicId(topicId);
        List<CoopInvite> result = new ArrayList<>();
        for (CoopInvite source : safe(inviteMapper.selectCoopInviteList(query))) {
            if (!Integer.valueOf(1).equals(source.getInviteType()) || !"club".equals(source.getToType())) {
                continue;
            }
            result.add(source);
        }
        return result;
    }

    private List<TopicOperationsSnapshotDTO.OperationItemDTO> loadClubCooperations(Long topicId,
                                                                                      List<CoopOrder> sources) {
        List<TopicOperationsSnapshotDTO.OperationItemDTO> result = new ArrayList<>();
        for (CoopOrder source : sources) {
            TopicOperationsSnapshotDTO.OperationItemDTO item = item("CLUB_COOPERATION", source.getId(), topicId,
                    "EFFECTIVE", "已成合作单", READ_ONLY, latest(source.getUpdateTime(), source.getCreateTime()));
            item.setAllowedActions(actions(disabledAction("VIEW_EVIDENCE", BLOCKED_P1_COMMERCIAL_GATES)));
            result.add(item);
        }
        return result;
    }

    private List<CoopOrder> findClubCooperations(Long topicId) {
        List<CoopOrder> result = new ArrayList<>();
        for (CoopOrder source : safe(orderMapper.selectEffectiveOrdersByTopicId(topicId))) {
            if (!"club".equals(source.getToType())) {
                continue;
            }
            result.add(source);
        }
        return result;
    }

    private static TopicOperationsSnapshotDTO.OperationItemDTO item(String sourceType, Long sourceId, Long topicId,
                                                                      String sourceState, String displayState,
                                                                      String actionability, Date latestAt) {
        TopicOperationsSnapshotDTO.SourceRefDTO sourceRef = new TopicOperationsSnapshotDTO.SourceRefDTO();
        sourceRef.setType(sourceType);
        sourceRef.setId(sourceId);
        sourceRef.setTopicId(topicId);

        TopicOperationsSnapshotDTO.OperationItemDTO item = new TopicOperationsSnapshotDTO.OperationItemDTO();
        item.setItemKey(sourceType + ":" + sourceId);
        item.setSourceRef(sourceRef);
        item.setSourceState(sourceState);
        item.setDisplayState(displayState);
        item.setActionability(actionability);
        item.setLatestAt(latestAt);
        return item;
    }

    private static TopicOperationsSnapshotDTO.AllowedActionDTO disabledAction(String code, String reasonCode) {
        TopicOperationsSnapshotDTO.AllowedActionDTO action = new TopicOperationsSnapshotDTO.AllowedActionDTO();
        action.setCode(code);
        action.setEnabled(false);
        action.setDisabledReasonCode(reasonCode);
        return action;
    }

    private static List<TopicOperationsSnapshotDTO.AllowedActionDTO> actions(
            TopicOperationsSnapshotDTO.AllowedActionDTO... actions) {
        List<TopicOperationsSnapshotDTO.AllowedActionDTO> result = new ArrayList<>();
        Collections.addAll(result, actions);
        return result;
    }

    private static TopicOperationsSnapshotDTO.SummaryDTO summarize(
            List<TopicOperationsSnapshotDTO.OperationItemDTO> items) {
        TopicOperationsSnapshotDTO.SummaryDTO result = new TopicOperationsSnapshotDTO.SummaryDTO();
        for (TopicOperationsSnapshotDTO.OperationItemDTO item : items) {
            if (ACTIONABLE.equals(item.getActionability())) {
                result.setActionableCount(result.getActionableCount() + 1);
            } else if (WAITING_EXTERNAL.equals(item.getActionability())) {
                result.setWaitingExternalCount(result.getWaitingExternalCount() + 1);
            } else if (BLOCKED.equals(item.getActionability())) {
                result.setBlockedCount(result.getBlockedCount() + 1);
            } else {
                result.setReadOnlyCount(result.getReadOnlyCount() + 1);
            }
        }
        return result;
    }

    private static void sortByLatestAt(List<TopicOperationsSnapshotDTO.OperationItemDTO> items) {
        Collections.sort(items, new Comparator<TopicOperationsSnapshotDTO.OperationItemDTO>() {
            @Override
            public int compare(TopicOperationsSnapshotDTO.OperationItemDTO left,
                               TopicOperationsSnapshotDTO.OperationItemDTO right) {
                Date leftAt = left.getLatestAt();
                Date rightAt = right.getLatestAt();
                if (leftAt == null && rightAt != null) {
                    return 1;
                }
                if (leftAt != null && rightAt == null) {
                    return -1;
                }
                if (leftAt != null) {
                    int timeOrder = rightAt.compareTo(leftAt);
                    if (timeOrder != 0) {
                        return timeOrder;
                    }
                }
                return left.getItemKey().compareTo(right.getItemKey());
            }
        });
    }

    private static boolean isDeleted(CmsTopic topic) {
        return topic.getDelFlag() != null && !Integer.valueOf(0).equals(topic.getDelFlag());
    }

    private static String merchantSourceState(CmsRegistrationMerchant source) {
        if (Long.valueOf(0L).equals(source.getStatus())) {
            return "REGISTRATION_PENDING_REVIEW";
        }
        if (Long.valueOf(2L).equals(source.getStatus())) {
            return "REGISTRATION_REJECTED";
        }
        if (!Long.valueOf(1L).equals(source.getStatus())) {
            return "REGISTRATION_STATUS_UNKNOWN";
        }
        if (Long.valueOf(0L).equals(source.getAuditStatus())) {
            return "PENDING_DISPATCH";
        }
        if (Long.valueOf(1L).equals(source.getAuditStatus())) {
            return "SELECTED";
        }
        if (Long.valueOf(2L).equals(source.getAuditStatus())) {
            return "REJECTED";
        }
        return "DISPATCH_STATUS_UNKNOWN";
    }

    private static String merchantDisplayState(String sourceState) {
        if ("REGISTRATION_PENDING_REVIEW".equals(sourceState)) {
            return "报名待审核";
        }
        if ("REGISTRATION_REJECTED".equals(sourceState)) {
            return "报名审核未通过";
        }
        if ("PENDING_DISPATCH".equals(sourceState)) {
            return "待调配";
        }
        if ("SELECTED".equals(sourceState)) {
            return "已中标";
        }
        if ("REJECTED".equals(sourceState)) {
            return "未中标";
        }
        return "报名状态待核验";
    }

    private static ClubApplyState deriveClubApplyState(CoopClubApply source, List<CoopInvite> clubInvites,
                                                        List<CoopOrder> clubCooperations) {
        Date latestAt = latest(source.getHandleTime(), latest(source.getUpdateTime(), source.getCreateTime()));
        String sourceState = clubApplySourceState(source.getStatus());
        if (!"PENDING".equals(sourceState)) {
            return new ClubApplyState(sourceState, clubApplyDisplayState(sourceState), READ_ONLY, latestAt);
        }

        CoopOrder cooperation = effectiveCooperationForApply(source.getId(), clubInvites, clubCooperations);
        if (cooperation != null) {
            return new ClubApplyState(sourceState, "已成合作单", READ_ONLY,
                    latest(latestAt, latest(cooperation.getUpdateTime(), cooperation.getCreateTime())));
        }

        CoopInvite invite = latestInviteForApply(source.getId(), clubInvites);
        if (invite == null) {
            return new ClubApplyState(sourceState, clubApplyDisplayState(sourceState), BLOCKED, latestAt);
        }
        latestAt = latest(latestAt, latest(invite.getHandleTime(), latest(invite.getUpdateTime(), invite.getCreateTime())));
        String inviteState = clubInviteState(invite.getStatus());
        String displayState = "ACCEPTED".equals(inviteState) ? "邀约已接受，合作单待核验"
                : clubInviteDisplayState(inviteState);
        String actionability = "PENDING".equals(inviteState) ? WAITING_EXTERNAL : READ_ONLY;
        return new ClubApplyState(sourceState, displayState, actionability, latestAt);
    }

    private static String clubApplySourceState(Integer status) {
        if (Integer.valueOf(0).equals(status)) {
            return "PENDING";
        }
        if (Integer.valueOf(1).equals(status)) {
            return "DECLINED";
        }
        if (Integer.valueOf(2).equals(status)) {
            return "WITHDRAWN";
        }
        return "UNKNOWN";
    }

    private static String clubApplyDisplayState(String sourceState) {
        if ("PENDING".equals(sourceState)) {
            return "待处理承接申请";
        }
        if ("DECLINED".equals(sourceState)) {
            return "申请已婉拒";
        }
        if ("WITHDRAWN".equals(sourceState)) {
            return "申请已撤回";
        }
        return "申请状态待核验";
    }

    private static String clubInviteState(Integer status) {
        if (Integer.valueOf(0).equals(status)) {
            return "PENDING";
        }
        if (Integer.valueOf(1).equals(status)) {
            return "ACCEPTED";
        }
        if (Integer.valueOf(2).equals(status)) {
            return "DECLINED";
        }
        if (Integer.valueOf(3).equals(status)) {
            return "CANCELED";
        }
        if (Integer.valueOf(4).equals(status)) {
            return "REPLACED";
        }
        if (Integer.valueOf(5).equals(status)) {
            return "EXPIRED";
        }
        return "UNKNOWN";
    }

    private static String clubInviteDisplayState(String sourceState) {
        if ("PENDING".equals(sourceState)) {
            return "已邀约，等待接受";
        }
        if ("ACCEPTED".equals(sourceState)) {
            return "邀约已接受";
        }
        if ("DECLINED".equals(sourceState)) {
            return "邀约已拒绝";
        }
        if ("CANCELED".equals(sourceState)) {
            return "邀约已取消";
        }
        if ("REPLACED".equals(sourceState)) {
            return "邀约已顶替";
        }
        if ("EXPIRED".equals(sourceState)) {
            return "邀约已过期";
        }
        return "邀约状态待核验";
    }

    private static Date latest(Date first, Date second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.after(second) ? first : second;
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? Collections.<T>emptyList() : rows;
    }

    private static CoopInvite latestInviteForApply(Long applyId, List<CoopInvite> clubInvites) {
        CoopInvite result = null;
        for (CoopInvite candidate : clubInvites) {
            if (applyId == null || !applyId.equals(candidate.getOriginApplyId())) {
                continue;
            }
            if (result == null || isAfter(inviteLatestAt(candidate), inviteLatestAt(result))) {
                result = candidate;
            }
        }
        return result;
    }

    private static CoopOrder effectiveCooperationForInvite(Long inviteId, List<CoopOrder> clubCooperations) {
        CoopOrder result = null;
        for (CoopOrder candidate : clubCooperations) {
            if (inviteId == null || !inviteId.equals(candidate.getInviteId())) {
                continue;
            }
            if (result == null || isAfter(cooperationLatestAt(candidate), cooperationLatestAt(result))) {
                result = candidate;
            }
        }
        return result;
    }

    private static CoopOrder effectiveCooperationForApply(Long applyId, List<CoopInvite> clubInvites,
                                                           List<CoopOrder> clubCooperations) {
        CoopOrder result = null;
        for (CoopInvite invite : clubInvites) {
            if (applyId == null || !applyId.equals(invite.getOriginApplyId())) {
                continue;
            }
            CoopOrder candidate = effectiveCooperationForInvite(invite.getId(), clubCooperations);
            if (candidate != null && (result == null || isAfter(cooperationLatestAt(candidate), cooperationLatestAt(result)))) {
                result = candidate;
            }
        }
        return result;
    }

    private static Date inviteLatestAt(CoopInvite invite) {
        return latest(invite.getHandleTime(), latest(invite.getUpdateTime(), invite.getCreateTime()));
    }

    private static Date cooperationLatestAt(CoopOrder cooperation) {
        return latest(cooperation.getUpdateTime(), cooperation.getCreateTime());
    }

    private static boolean isAfter(Date left, Date right) {
        return left != null && (right == null || left.after(right));
    }

    private static class ClubApplyState {
        private final String sourceState;
        private final String displayState;
        private final String actionability;
        private final Date latestAt;

        private ClubApplyState(String sourceState, String displayState, String actionability, Date latestAt) {
            this.sourceState = sourceState;
            this.displayState = displayState;
            this.actionability = actionability;
            this.latestAt = latestAt;
        }

        private String getSourceState() {
            return sourceState;
        }

        private Date getLatestAt() {
            return latestAt;
        }

        private String getDisplayState() {
            return displayState;
        }

        private String getActionability() {
            return actionability;
        }
    }
}

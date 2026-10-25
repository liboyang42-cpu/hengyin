package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.domain.dto.ClubEventCampaignRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.service.IClubEventNotificationService;
import com.chengyinhub.business.service.IClubEventNotificationTransactionService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ClubEventNotificationServiceImpl implements IClubEventNotificationService {
    private static final Logger logger = LoggerFactory.getLogger(ClubEventNotificationServiceImpl.class);
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.IWxContentSecurityService wxContentSecurityService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.IUmsMemberService umsMemberService;
    private static final long CLAIM_LEASE_MILLIS = 5L * 60L * 1000L;
    private static final Set<String> AUDIENCES;
    static {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Collections.addAll(values, "ALL_MEMBERS", "ADMINS", "REGISTERED", "WAITLIST", "NO_SHOW", "INACTIVE");
        AUDIENCES = Collections.unmodifiableSet(values);
    }

    protected final IClubAccessService accessService;
    protected final ClubEventOpsMapper mapper;
    protected final IClubEventNotificationTransactionService transactionService;

    public ClubEventNotificationServiceImpl(IClubAccessService accessService, ClubEventOpsMapper mapper,
                                            IClubEventNotificationTransactionService transactionService) {
        this.accessService = accessService;
        this.mapper = mapper;
        this.transactionService = transactionService;
    }

    @Override
    public Map<String, Object> preview(Long actorMemberId, ClubEventCampaignRequest request) {
        Validated validated = validate(actorMemberId, request, false);
        List<Long> recipients = recipients(validated.clubId, validated.activityId, validated.audience);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("audienceType", validated.audience);
        result.put("recipientCount", recipients.size());
        result.put("inApp", "AVAILABLE");
        result.put("wechatSubscription", "UNAVAILABLE");
        result.put("wechatReason", "WECHAT_TEMPLATE_NOT_CONFIGURED");
        result.put("phoneIncluded", false);
        return result;
    }

    @Override
    public ClubEventCampaign send(Long actorMemberId, ClubEventCampaignRequest request) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        Validated validated = validate(actorMemberId, request, true);
        ClubEventCampaign replay = mapper.selectCampaignByRequest(
                validated.clubId, actorMemberId, validated.requestId);
        if (replay != null) {
            requireEquivalentReplay(replay, validated);
            return replay;
        }
        com.chengyinhub.business.domain.UmsMember actor = umsMemberService.selectUmsMemberById(actorMemberId);
        String risk = wxContentSecurityService.checkText(validated.title + "\n" + validated.content,
                actor == null ? null : actor.getWxOpenId());
        if (risk != null) throw badRequest(risk);
        List<Long> recipients = recipients(validated.clubId, validated.activityId, validated.audience);
        Date now = new Date();
        ClubEventCampaign campaign = new ClubEventCampaign();
        campaign.setClubId(validated.clubId);
        campaign.setActivityId(validated.activityId);
        campaign.setAudienceType(validated.audience);
        campaign.setChannel("IN_APP");
        campaign.setTitle(validated.title);
        campaign.setContent(validated.content);
        campaign.setStatus(ClubEventCampaign.PROCESSING);
        campaign.setActorMemberId(actorMemberId);
        campaign.setRequestId(validated.requestId);
        campaign.setTotalCount(recipients.size());
        campaign.setSuccessCount(0);
        campaign.setFailedCount(0);
        campaign.setCreateTime(now);
        campaign.setUpdateTime(now);
        try {
            transactionService.create(campaign, recipients, now);
        } catch (DuplicateKeyException duplicate) {
            ClubEventCampaign concurrent = mapper.selectCampaignByRequest(
                    validated.clubId, actorMemberId, validated.requestId);
            if (concurrent == null) throw conflict("通知任务创建冲突，请重试");
            requireEquivalentReplay(concurrent, validated);
            return concurrent;
        }
        return dispatch(campaign, transactionService.claim(
                campaign, 500, new Date(System.currentTimeMillis() + CLAIM_LEASE_MILLIS)));
    }

    @Override
    public ClubEventCampaign campaign(Long actorMemberId, Long campaignId) {
        return loadAuthorizedCampaign(actorMemberId, campaignId);
    }

    @Override
    public ClubEventCampaign retryFailed(Long actorMemberId, Long campaignId) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        ClubEventCampaign campaign = loadAuthorizedCampaign(actorMemberId, campaignId);
        return dispatch(campaign, transactionService.claimAuthorized(actorMemberId, campaign, 500,
                new Date(System.currentTimeMillis() + CLAIM_LEASE_MILLIS)));
    }

    @Override
    public int retryDue() {
        if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode()) return 0;
        int processed = 0;
        List<Long> campaignIds = mapper.selectDueCampaignIds(new Date(), 10);
        if (campaignIds == null) return 0;
        for (Long campaignId : campaignIds) {
            try {
                ClubEventCampaign campaign = mapper.selectCampaignById(campaignId);
                if (campaign != null) {
                    dispatch(campaign, transactionService.claim(
                            campaign, 50, new Date(System.currentTimeMillis() + CLAIM_LEASE_MILLIS)));
                    processed++;
                }
            } catch (RuntimeException poison) {
                logger.error("俱乐部通知任务单项失败，已隔离并继续: campaignId={}", campaignId, poison);
            }
        }
        return processed;
    }

    /**
     * 读 + 鉴权一体:重试和刷新(CU-C-135)必须走同一道闸,否则「能读」比「能改」宽,
     * 拿别人的 campaignId 就能探到一个俱乐部的投递人数。
     */
    private ClubEventCampaign loadAuthorizedCampaign(Long actorMemberId, Long campaignId) {
        if (campaignId == null || campaignId <= 0) throw badRequest("campaignId 不合法");
        ClubEventCampaign campaign = mapper.selectCampaignById(campaignId);
        if (campaign == null || campaign.getClubId() == null) throw badRequest("通知任务不存在");
        ClubAccessContext access = accessService.require(actorMemberId, campaign.getClubId(),
                campaign.getActivityId(), notificationPermission(campaign.getActivityId()));
        requireExactAccess(access, actorMemberId, campaign.getClubId(), campaign.getActivityId());
        return campaign;
    }

    private ClubEventCampaign dispatch(ClubEventCampaign campaign, List<ClubEventDelivery> deliveries) {
        if (deliveries != null) for (ClubEventDelivery delivery : deliveries) {
            if (delivery == null || delivery.getId() == null || delivery.getRecipientMemberId() == null
                    || !campaign.getId().equals(delivery.getCampaignId())
                    || !(ClubEventDelivery.PENDING.equals(delivery.getStatus())
                    || ClubEventDelivery.FAILED.equals(delivery.getStatus()))
                    || delivery.getVersion() == null) throw conflict("通知 delivery 状态不一致");
            try {
                transactionService.deliver(campaign, delivery.getId());
            } catch (RuntimeException error) {
                Date nextAttempt = new Date(System.currentTimeMillis() + retryDelay(delivery.getAttemptCount()));
                try {
                    transactionService.recordFailure(campaign.getId(), delivery.getId(),
                            safeError(error), nextAttempt);
                } catch (RuntimeException receiptFailure) {
                    logger.error("俱乐部通知失败回执写入失败，已隔离并继续: campaignId={}, deliveryId={}",
                            campaign.getId(), delivery.getId(), receiptFailure);
                }
            }
        }
        if (mapper.refreshCampaignCounts(campaign.getId()) != 1) {
            throw conflict("通知任务汇总回执缺失，请刷新后重试");
        }
        ClubEventCampaign refreshed = mapper.selectCampaignById(campaign.getId());
        requireAuthoritativeReadback(campaign, refreshed);
        return refreshed;
    }

    private void requireAuthoritativeReadback(ClubEventCampaign expected, ClubEventCampaign actual) {
        if (actual == null || !same(expected.getId(), actual.getId())
                || !same(expected.getClubId(), actual.getClubId())
                || !same(expected.getActivityId(), actual.getActivityId())
                || !same(expected.getAudienceType(), actual.getAudienceType())
                || !same(expected.getChannel(), actual.getChannel())
                || !same(expected.getActorMemberId(), actual.getActorMemberId())
                || !same(expected.getRequestId(), actual.getRequestId())) {
            throw conflict("通知任务权威回读范围不一致");
        }
        Integer total = actual.getTotalCount();
        Integer success = actual.getSuccessCount();
        Integer failed = actual.getFailedCount();
        String status = actual.getStatus();
        if (total == null || success == null || failed == null
                || total < 0 || success < 0 || failed < 0 || success + failed > total
                || expected.getTotalCount() == null || !expected.getTotalCount().equals(total)
                || !(ClubEventCampaign.PROCESSING.equals(status)
                || ClubEventCampaign.COMPLETED.equals(status)
                || ClubEventCampaign.PARTIAL_FAILED.equals(status))
                || ClubEventCampaign.PARTIAL_FAILED.equals(status) && failed == 0
                || ClubEventCampaign.PROCESSING.equals(status) && (failed != 0 || success >= total)
                || ClubEventCampaign.COMPLETED.equals(status) && failed != 0) {
            throw conflict("通知任务权威回读计数或状态不一致");
        }
    }

    private Validated validate(Long actorMemberId, ClubEventCampaignRequest request, boolean sending) {
        if (request == null || actorMemberId == null || actorMemberId <= 0
                || request.getClubId() == null || request.getClubId() <= 0) throw badRequest("通知范围不完整");
        String suppliedRequestId = request.getRequestId() == null
                ? "" : request.getRequestId().trim().toLowerCase(Locale.ROOT);
        if (suppliedRequestId.startsWith("cancel-r-") || suppliedRequestId.startsWith("cancel-w-")) {
            throw badRequest("requestId 使用了系统保留前缀");
        }
        String audience = upper(request.getAudienceType());
        if (!AUDIENCES.contains(audience)) throw badRequest("通知受众不合法");
        boolean eventAudience = "REGISTERED".equals(audience) || "WAITLIST".equals(audience)
                || "NO_SHOW".equals(audience);
        Long activityId = request.getActivityId();
        if (eventAudience && (activityId == null || activityId <= 0)) throw badRequest("该受众必须选择活动");
        if (!eventAudience) activityId = null;
        ClubAccessContext access = accessService.require(
                actorMemberId, request.getClubId(), activityId, notificationPermission(activityId));
        requireExactAccess(access, actorMemberId, request.getClubId(), activityId);
        String channel = upper(request.getChannel());
        if (channel.isEmpty()) channel = "IN_APP";
        if (!"IN_APP".equals(channel)) {
            throw new ServiceException("WECHAT_SUBSCRIPTION_UNAVAILABLE: 订阅模板或授权未接入",
                    HttpStatus.NOT_IMPLEMENTED);
        }
        String title = normalize(request.getTitle(), 1, 80, "通知标题");
        String content = normalize(request.getContent(), 1, 1000, "通知内容");
        String requestId = sending ? normalize(request.getRequestId(), 1, 64, "requestId")
                : request.getRequestId();
        return new Validated(request.getClubId(), activityId, audience, title, content, requestId);
    }

    /**
     * R4 群发通知:每个受众分组各多少人。
     *
     * ★ 三个「本场」分组(已报名/候补/未到场)在没选活动时回 **null,不是 0**。
     *   0 会被读成「这个分群一个人都没有」,而真相是「还没选活动,算不了」——
     *   页面据此不显示数字,与稿一致。
     * ★ 复用 recipients() 而不是另写四条 count SQL:人数必须和真正发送时的名单同源,
     *   否则会出现「显示 42 人、发出去 40 人」这种对不上的账。
     *   代价是它带人数上限的抛出 —— 单个分组超限时整个计数接口会失败,
     *   这是可以接受的:那种规模本来就发不出去,早点说比选完再说好。
     */
    @Override
    public Map<String, Object> audienceCounts(Long actorMemberId, Long clubId, Long activityId) {
        if (actorMemberId == null || clubId == null || clubId <= 0) throw badRequest("通知范围不完整");
        Long scopedActivityId = activityId != null && activityId > 0 ? activityId : null;
        ClubAccessContext access = accessService.require(
                actorMemberId, clubId, scopedActivityId, notificationPermission(scopedActivityId));
        requireExactAccess(access, actorMemberId, clubId, scopedActivityId);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("ALL_MEMBERS", recipients(clubId, null, "ALL_MEMBERS").size());
        counts.put("ADMINS", recipients(clubId, null, "ADMINS").size());
        counts.put("INACTIVE", recipients(clubId, null, "INACTIVE").size());
        if (scopedActivityId == null) {
            counts.put("REGISTERED", null);
            counts.put("WAITLIST", null);
            counts.put("NO_SHOW", null);
        } else {
            counts.put("REGISTERED", recipients(clubId, scopedActivityId, "REGISTERED").size());
            counts.put("WAITLIST", recipients(clubId, scopedActivityId, "WAITLIST").size());
            counts.put("NO_SHOW", recipients(clubId, scopedActivityId, "NO_SHOW").size());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", counts);
        result.put("activityScoped", scopedActivityId != null);
        return result;
    }

    private List<Long> recipients(Long clubId, Long activityId, String audience) {
        List<Long> raw;
        if ("ALL_MEMBERS".equals(audience)) raw = mapper.selectAllMemberRecipients(clubId);
        else if ("ADMINS".equals(audience)) raw = mapper.selectAdminRecipients(clubId);
        else if ("REGISTERED".equals(audience)) raw = mapper.selectRegisteredRecipients(clubId, activityId);
        else if ("WAITLIST".equals(audience)) raw = mapper.selectWaitlistRecipients(clubId, activityId);
        else if ("NO_SHOW".equals(audience)) raw = mapper.selectNoShowRecipients(clubId, activityId);
        else raw = mapper.selectInactiveRecipients(clubId);
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        if (raw != null) for (Long id : raw) {
            if (id == null || id <= 0) throw conflict("通知受众存在无效成员");
            unique.add(id);
        }
        if (unique.size() > 500) throw conflict("单次通知最多 500 人，请缩小受众");
        return new ArrayList<>(unique);
    }

    private void requireEquivalentReplay(ClubEventCampaign row, Validated requested) {
        if (!requested.clubId.equals(row.getClubId()) || !same(requested.activityId, row.getActivityId())
                || !requested.audience.equals(row.getAudienceType()) || !"IN_APP".equals(row.getChannel())
                || !requested.title.equals(row.getTitle()) || !requested.content.equals(row.getContent())) {
            throw conflict("requestId 已用于不同通知内容");
        }
    }

    private void requireExactAccess(ClubAccessContext access, Long actor, Long clubId, Long activityId) {
        if (access == null || !actor.equals(access.getPrincipalMemberId()) || !clubId.equals(access.getClubId())
                || !same(activityId, access.getActivityId())) throw forbidden();
    }

    private String notificationPermission(Long activityId) {
        return activityId == null ? ClubPermission.NOTIFY_SEND : ClubPermission.EVENT_OPERATE;
    }

    private long retryDelay(Integer attempts) {
        int n = attempts == null ? 0 : Math.min(attempts, 5);
        return (1L << n) * 60_000L;
    }
    private String safeError(RuntimeException error) {
        String value = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
    private String normalize(String value, int min, int max, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < min || normalized.length() > max) {
            throw badRequest(field + "需为 " + min + " 至 " + max + " 字");
        }
        return normalized;
    }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private boolean same(Object left, Object right) { return left == null ? right == null : left.equals(right); }
    private ServiceException badRequest(String message) { return new ServiceException(message, HttpStatus.BAD_REQUEST); }
    private ServiceException forbidden() { return new ServiceException("俱乐部活动管理权限不足", HttpStatus.FORBIDDEN); }
    private ServiceException conflict(String message) { return new ServiceException(message, HttpStatus.CONFLICT); }

    private static final class Validated {
        final Long clubId;
        final Long activityId;
        final String audience;
        final String title;
        final String content;
        final String requestId;
        Validated(Long clubId, Long activityId, String audience, String title, String content, String requestId) {
            this.clubId = clubId;
            this.activityId = activityId;
            this.audience = audience;
            this.title = title;
            this.content = content;
            this.requestId = requestId;
        }
    }
}

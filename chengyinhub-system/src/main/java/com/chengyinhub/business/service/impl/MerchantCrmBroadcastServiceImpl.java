package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantCrmBroadcast;
import com.chengyinhub.business.domain.MerchantCrmBroadcastCandidate;
import com.chengyinhub.business.domain.MerchantCrmBroadcastRecipient;
import com.chengyinhub.business.domain.MerchantCrmMarketingConsent;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.MerchantCrmBroadcastRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.vo.MerchantCrmBroadcastPreviewVO;
import com.chengyinhub.business.domain.vo.MerchantCrmBroadcastVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmAppMapper;
import com.chengyinhub.business.mapper.MerchantCrmBroadcastMapper;
import com.chengyinhub.business.mapper.MerchantCrmMarketingConsentMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.IMerchantCrmBroadcastService;
import com.chengyinhub.business.service.IPlayerRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 商家定向广播:客户页「当前筛选」一次性通知。
 *
 * <p>为什么不是复用 merchant_crm_campaign:触达任务必须挂「保存分群」,
 * 而广播的名单是页面上的临时筛选(带队伍/角色收窄),两者受众来源不同;
 * 但同意过滤、日频控、逐人回执这些**数据口径**与触达任务完全共用:
 * 同意查 merchant_crm_marketing_consent、日频控走 merchant_crm_delivery_guard
 * 的 (merchant_id, member_id, channel, delivery_date) 唯一键 —— 同一客户在同一天
 * 不会被触达任务和广播各发一条。</p>
 *
 * <p>⚠️ 触达渠道:站内信(IM 卡片)。微信订阅消息未接 —— 仓库里没有商家→客户可用的
 * 订阅模板,且前端从未把 wx.requestSubscribeMessage 的授权结果回传后端,
 * 「按订阅授权记录过滤」无数据源。模板 ID 必须在微信公众平台新建后另行接线,
 * 见本任务报告「不确定清单」;绝不借用其它业务模板(语义错配 + 烧掉用户一次性授权配额)。</p>
 */
@Service
public class MerchantCrmBroadcastServiceImpl implements IMerchantCrmBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(MerchantCrmBroadcastServiceImpl.class);

    /** 单批冻结名单上限:与触达任务同档,同步投递必须封顶。 */
    static final int RECIPIENT_LIMIT = 200;
    /** 同一商家每天最多发几条广播(N=3,拍板定)。 */
    static final int MERCHANT_DAILY_BROADCAST_LIMIT = 3;
    static final int CONTENT_MAX = 120;
    static final int GROUP_PICK_MAX = 20;
    /** 广播只走站内信渠道;guard 的自然日唯一键按此渠道与触达任务共用额度。 */
    static final String CHANNEL = MerchantCrmMarketingConsent.IN_APP;

    private static final Set<String> SEGMENTS = new LinkedHashSet<>(Arrays.asList(
            "repeat", "new", "noted", "pending", "abnormal", "dormant"));
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{6,64}");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private IMerchantAccessService accessService;
    @Autowired private MerchantCrmAppMapper appMapper;
    @Autowired private MerchantCrmBroadcastMapper broadcastMapper;
    @Autowired private MerchantCrmMarketingConsentMapper consentMapper;
    @Autowired private MerchantCrmDeliveryGuardService deliveryGuardService;
    @Autowired private MerchantCrmNotificationAdapter notificationAdapter;
    @Autowired private MmsMerchantMapper merchantMapper;
    @Autowired private BusinessAuditEventMapper auditMapper;
    @Autowired private IWxContentSecurityService wxSec;
    @Autowired private IUmsMemberService umsMemberService;
    @Autowired private IPlayerRoleService playerRoleService;

    @Override
    public MerchantCrmBroadcastPreviewVO preview(Long principalMemberId, MerchantCrmBroadcastRequest request) {
        MerchantAccessContext access = accessService.require(principalMemberId, MerchantPermission.MARKETING_WRITE);
        String scope = scope(request == null ? null : request.getScope());
        Set<String> groups = groups(request == null ? null : request.getGroups(), scope);
        Set<Long> memberIds = memberIds(request == null ? null : request.getMemberIds(), scope);
        MerchantCrmCustomerQuery query = query(access.getMerchantId(), request);
        long filterTotal = appMapper.selectOwnedCustomerCount(access.getOwnerMemberId(), query);
        List<MerchantCrmBroadcastCandidate> candidates =
                audience(access.getOwnerMemberId(), query, scope, groups, memberIds);

        Reach reach = reach(access, candidates);
        int consented = reach.consented;
        int frequencyLimited = reach.frequencyLimited;
        int used = broadcastMapper.countTodayByMerchant(access.getMerchantId());
        MerchantCrmBroadcastPreviewVO out = new MerchantCrmBroadcastPreviewVO();
        out.setFilterTotalCount(filterTotal);
        out.setAudienceCount(candidates.size());
        out.setConsentedCount(consented);
        out.setNoConsentCount(candidates.size() - consented);
        out.setFrequencyLimitedCount(frequencyLimited);
        out.setDeliverableCount(reach.deliverable());
        out.setRecipientLimit(RECIPIENT_LIMIT);
        out.setMerchantDailyLimit(MERCHANT_DAILY_BROADCAST_LIMIT);
        out.setMerchantDailyUsed(used);
        out.setMerchantDailyRemaining(Math.max(0, MERCHANT_DAILY_BROADCAST_LIMIT - used));
        return out;
    }

    @Override
    public MerchantCrmBroadcastVO send(Long principalMemberId, MerchantCrmBroadcastRequest request) {
        MerchantAccessContext access = accessService.require(principalMemberId, MerchantPermission.MARKETING_WRITE);
        String scope = scope(request == null ? null : request.getScope());
        Set<String> groups = groups(request == null ? null : request.getGroups(), scope);
        Set<Long> memberIds = memberIds(request == null ? null : request.getMemberIds(), scope);
        String content = content(request == null ? null : request.getContent());
        String requestId = requestId(request == null ? null : request.getRequestId());
        MerchantCrmCustomerQuery query = query(access.getMerchantId(), request);

        MerchantCrmBroadcast replay = broadcastMapper.selectByRequestId(access.getMerchantId(), requestId);
        if (replay != null) return exactReplay(replay, access, content);

        // 内容安全:与发帖/组队同一把机审闸;拿不到审核结果按 fail-close 拦截。
        // 「审不了」(MSG_BUSY / MSG_UNAVAILABLE)不是违规,码分开(503 可重试 / 400 需改内容),别把用户当违规者。
        UmsMember actor = umsMemberService.selectUmsMemberById(principalMemberId);
        String securityError = wxSec.checkText(content, actor == null ? null : actor.getWxOpenId());
        if (securityError != null) {
            boolean unavailable = IWxContentSecurityService.isDetectionUnavailable(securityError);
            throw new ServiceException(securityError, unavailable ? 503 : 400);
        }

        MmsMerchant merchant = merchantMapper.selectMmsMerchantById(access.getMerchantId());
        if (merchant == null || !Objects.equals(access.getOwnerMemberId(), merchant.getMemberId())) {
            throw new ServiceException("商家主体已变化，请重新登录", 409);
        }
        if (!Long.valueOf(1L).equals(merchant.getStatus())
                || !Long.valueOf(1L).equals(merchant.getAccountStatus())
                || !Integer.valueOf(0).equals(merchant.getDelFlag())) {
            throw new ServiceException("商家主体已失效，无法发送广播", 403);
        }

        int used = broadcastMapper.countTodayByMerchant(access.getMerchantId());
        if (used >= MERCHANT_DAILY_BROADCAST_LIMIT) {
            throw new ServiceException("今天已经发过 " + MERCHANT_DAILY_BROADCAST_LIMIT + " 条广播，明天再发", 429);
        }
        List<MerchantCrmBroadcastCandidate> candidates =
                audience(access.getOwnerMemberId(), query, scope, groups, memberIds);
        // M-09:0 人可触达不建广播、不占当日次数(原先会冻结一份全跳过的名单并吃掉一个日配额)。
        if (reach(access, candidates).deliverable() <= 0) {
            throw new ServiceException("所选客户现在都收不到(未同意接收商家消息或今天已收过)，请换选已同意的客户", 400);
        }

        MerchantCrmBroadcast broadcast = new MerchantCrmBroadcast();
        broadcast.setMerchantId(access.getMerchantId());
        broadcast.setMerchantOwnerMemberId(access.getOwnerMemberId());
        broadcast.setMerchantNameSnapshot(merchant.getName() == null || merchant.getName().trim().isEmpty()
                ? "商家" : merchant.getName().trim());
        broadcast.setActorMemberId(principalMemberId);
        broadcast.setActorRoleCode(access.getRoleCode());
        broadcast.setRequestId(requestId);
        broadcast.setFilterJson(filterJson(scope, groups, memberIds, query));
        broadcast.setContent(content);
        broadcast.setStatus(MerchantCrmBroadcast.SENDING);
        broadcast.setAudienceCount(candidates.size());
        broadcast.setCreateTime(new Date());
        MerchantCrmBroadcast concurrentReplay = claimDailySlot(access.getMerchantId(), requestId, broadcast);
        if (concurrentReplay != null) return exactReplay(concurrentReplay, access, content);

        for (MerchantCrmBroadcastCandidate candidate : candidates) {
            MerchantCrmMarketingConsent consent = consentMapper.selectLatestOptIn(
                    candidate.getMemberId(), access.getOwnerMemberId(), access.getMerchantId(), CHANNEL);
            MerchantCrmBroadcastRecipient recipient = new MerchantCrmBroadcastRecipient();
            recipient.setBroadcastId(broadcast.getId());
            recipient.setMerchantId(access.getMerchantId());
            recipient.setMemberId(candidate.getMemberId());
            recipient.setCustomerNameSnapshot(candidate.getName());
            recipient.setConsentEventIdSnapshot(consent == null ? null : consent.getId());
            if (broadcastMapper.insertRecipient(recipient) != 1) {
                throw new ServiceException("广播名单冻结失败", 409);
            }
        }
        appendAudit(broadcast, "CRM_BROADCAST_CREATED", requestId, principalMemberId, access.getRoleCode());

        deliverAll(broadcast, access);
        return toVO(broadcast);
    }

    /** 名单来源与触达任务同源;scope/groups 只在这批候选内收窄(与单批上限同口径)。 */
    private List<MerchantCrmBroadcastCandidate> audience(Long ownerMemberId, MerchantCrmCustomerQuery query,
                                                         String scope, Set<String> groups, Set<Long> memberIds) {
        List<MerchantCrmBroadcastCandidate> rows =
                appMapper.selectOwnedBroadcastCandidates(ownerMemberId, query, RECIPIENT_LIMIT);
        List<MerchantCrmBroadcastCandidate> out = new ArrayList<>();
        if (rows == null) return out;
        for (MerchantCrmBroadcastCandidate row : rows) {
            if ("TEAM".equals(scope)) {
                if (row.getTeamName() != null && groups.contains(row.getTeamName())) out.add(row);
            } else if ("ROLE".equals(scope)) {
                String roleName = playerRoleService.roleName(row.getRoleCode());
                if (roleName != null && !roleName.isEmpty() && groups.contains(roleName)) out.add(row);
            } else if ("MEMBERS".equals(scope)) {
                if (memberIds.contains(row.getMemberId())) out.add(row);
            } else {
                out.add(row);
            }
        }
        return out;
    }

    /** 预览与发送同一口径的触达统计。 */
    private static final class Reach {
        final int consented;
        final int frequencyLimited;
        Reach(int consented, int frequencyLimited) { this.consented = consented; this.frequencyLimited = frequencyLimited; }
        int deliverable() { return consented - frequencyLimited; }
    }

    private Reach reach(MerchantAccessContext access, List<MerchantCrmBroadcastCandidate> candidates) {
        int consented = 0;
        int frequencyLimited = 0;
        for (MerchantCrmBroadcastCandidate candidate : candidates) {
            if (consentMapper.selectLatestOptIn(candidate.getMemberId(), access.getOwnerMemberId(),
                    access.getMerchantId(), CHANNEL) != null) {
                consented++;
                if (broadcastMapper.countGuardToday(access.getMerchantId(), candidate.getMemberId(), CHANNEL) > 0) {
                    frequencyLimited++;
                }
            }
        }
        return new Reach(consented, frequencyLimited);
    }

    /**
     * 每天最多 3 条由 (merchant_id, broadcast_date, daily_slot) 唯一键兜底:并发抢槽,抢不到就顺延。
     * 返回非 null = 同一 requestId 已被并发请求抢先落库,调用方按那条回放。
     */
    private MerchantCrmBroadcast claimDailySlot(Long merchantId, String requestId, MerchantCrmBroadcast broadcast) {
        int used = broadcastMapper.countTodayByMerchant(merchantId);
        for (int slot = used + 1; slot <= MERCHANT_DAILY_BROADCAST_LIMIT; slot++) {
            broadcast.setDailySlot(slot);
            try {
                if (broadcastMapper.insertBroadcast(broadcast) == 1) return null;
            } catch (DuplicateKeyException e) {
                MerchantCrmBroadcast replay = broadcastMapper.selectByRequestId(merchantId, requestId);
                if (replay != null) return replay;
            }
        }
        throw new ServiceException("今天已经发过 " + MERCHANT_DAILY_BROADCAST_LIMIT + " 条广播，明天再发", 429);
    }

    private void deliverAll(MerchantCrmBroadcast broadcast, MerchantAccessContext access) {
        List<MerchantCrmBroadcastRecipient> recipients = broadcastMapper.selectRecipients(
                broadcast.getMerchantId(), broadcast.getId(), RECIPIENT_LIMIT);
        if (recipients == null) recipients = new ArrayList<>();
        int delivered = 0;
        int noConsent = 0;
        int frequency = 0;
        int failed = 0;
        for (MerchantCrmBroadcastRecipient recipient : recipients) {
            String outcome = deliverOne(broadcast, recipient);
            if (MerchantCrmBroadcastRecipient.DELIVERED.equals(outcome)) delivered++;
            else if (MerchantCrmBroadcastRecipient.SKIPPED_NO_CONSENT.equals(outcome)) noConsent++;
            else if (MerchantCrmBroadcastRecipient.SKIPPED_FREQUENCY.equals(outcome)) frequency++;
            else failed++;
        }
        MerchantCrmBroadcast done = new MerchantCrmBroadcast();
        done.setId(broadcast.getId());
        done.setMerchantId(broadcast.getMerchantId());
        done.setDeliveredCount(delivered);
        done.setNoConsentCount(noConsent);
        done.setFrequencySkippedCount(frequency);
        done.setFailedCount(failed);
        done.setConsentedCount(recipients.size() - noConsent);
        done.setStatus(delivered <= 0 ? MerchantCrmBroadcast.FAILED
                : (noConsent + frequency + failed > 0 ? MerchantCrmBroadcast.PARTIAL_FAILED : MerchantCrmBroadcast.SUCCESS));
        requireOne(broadcastMapper.finalizeBroadcast(done));
        // 回写内存对象:调用方拿它转 VO,不额外查一次库(回读只发生在前端刷新时)。
        broadcast.setStatus(done.getStatus());
        broadcast.setConsentedCount(done.getConsentedCount());
        broadcast.setNoConsentCount(done.getNoConsentCount());
        broadcast.setFrequencySkippedCount(done.getFrequencySkippedCount());
        broadcast.setDeliveredCount(done.getDeliveredCount());
        broadcast.setFailedCount(done.getFailedCount());
        appendAudit(broadcast, "CRM_BROADCAST_SENT", broadcast.getRequestId(),
                broadcast.getActorMemberId(), broadcast.getActorRoleCode());
        log.info("[定向广播] broadcast={} 名单={} 送达={} 未同意={} 频控={} 失败={}",
                broadcast.getId(), recipients.size(), delivered, noConsent, frequency, failed);
    }

    /** 返回该收件人的最终状态;任何一步失败都落逐人回执,绝不静默。 */
    private String deliverOne(MerchantCrmBroadcast broadcast, MerchantCrmBroadcastRecipient recipient) {
        Long merchantId = broadcast.getMerchantId();
        Long broadcastId = broadcast.getId();
        MerchantCrmMarketingConsent consent = consentMapper.selectLatestOptIn(recipient.getMemberId(),
                broadcast.getMerchantOwnerMemberId(), merchantId, CHANNEL);
        if (consent == null) {
            return markSkipped(merchantId, broadcastId, recipient.getId(),
                    MerchantCrmBroadcastRecipient.SKIPPED_NO_CONSENT, "NO_CONSENT", "客户未同意或已退订");
        }
        MerchantCrmDeliveryGuardService.Claim claim = deliveryGuardService.claim(
                merchantId, broadcastId, recipient.getId(), recipient.getMemberId(), CHANNEL);
        MerchantCrmDeliveryGuardService.Outcome outcome = claim.getOutcome();
        if (outcome == MerchantCrmDeliveryGuardService.Outcome.DAILY_CAP) {
            return markSkipped(merchantId, broadcastId, recipient.getId(),
                    MerchantCrmBroadcastRecipient.SKIPPED_FREQUENCY, "DAILY_CAP",
                    "客户今日已收到或已占用该商家触达额度");
        }
        if (outcome == MerchantCrmDeliveryGuardService.Outcome.DELIVERED) {
            requireOne(broadcastMapper.markRecipientDelivered(merchantId, broadcastId, recipient.getId(),
                    claim.getMessageReceiptId()));
            return MerchantCrmBroadcastRecipient.DELIVERED;
        }
        if (outcome == MerchantCrmDeliveryGuardService.Outcome.UNCERTAIN) {
            return markFailed(merchantId, broadcastId, recipient.getId(),
                    "DELIVERY_OUTCOME_UNKNOWN", "投递结果未知，未自动重发");
        }
        if (outcome == MerchantCrmDeliveryGuardService.Outcome.BUSY) {
            return markFailed(merchantId, broadcastId, recipient.getId(),
                    "DELIVERY_BUSY", "投递正在处理中，请稍后重试");
        }
        MerchantCrmDeliveryGuardService.Claim dispatching = deliveryGuardService.beginDispatch(claim);
        Long receipt;
        try {
            receipt = notificationAdapter.sendBroadcast(recipient.getMemberId(), broadcast);
            deliveryGuardService.markDelivered(dispatching, receipt, null);
        } catch (RuntimeException e) {
            // 外部调用结果未知:先落 guard 未知态(阻止自动重发),再落逐人失败回执。
            try {
                deliveryGuardService.markOutcomeUnknown(dispatching, "DELIVERY_OUTCOME_UNKNOWN");
            } catch (RuntimeException guardError) {
                log.error("[定向广播] guard 未知态落库失败 broadcast={} recipient={}",
                        broadcastId, recipient.getId(), guardError);
            }
            log.warn("[定向广播] 站内信投递失败 broadcast={} recipient={} reason={}",
                    broadcastId, recipient.getId(), e.getClass().getSimpleName());
            return markFailed(merchantId, broadcastId, recipient.getId(),
                    "DELIVERY_FAILED", "投递失败，未自动重发");
        }
        requireOne(broadcastMapper.markRecipientDelivered(merchantId, broadcastId, recipient.getId(), receipt));
        return MerchantCrmBroadcastRecipient.DELIVERED;
    }

    private String markSkipped(Long merchantId, Long broadcastId, Long recipientId,
                               String status, String code, String message) {
        requireOne(broadcastMapper.markRecipientSkipped(merchantId, broadcastId, recipientId, status, code, message));
        return status;
    }

    private String markFailed(Long merchantId, Long broadcastId, Long recipientId, String code, String message) {
        requireOne(broadcastMapper.markRecipientSkipped(merchantId, broadcastId, recipientId,
                MerchantCrmBroadcastRecipient.FAILED, code, message));
        return MerchantCrmBroadcastRecipient.FAILED;
    }

    private MerchantCrmBroadcastVO exactReplay(MerchantCrmBroadcast row, MerchantAccessContext access, String content) {
        if (!Objects.equals(access.getMerchantId(), row.getMerchantId())
                || !Objects.equals(access.getPrincipalMemberId(), row.getActorMemberId())
                || !content.equals(row.getContent())) {
            throw new ServiceException("请求标识已用于其他广播", 409);
        }
        return toVO(row);
    }

    private MerchantCrmBroadcastVO toVO(MerchantCrmBroadcast row) {
        MerchantCrmBroadcastVO out = new MerchantCrmBroadcastVO();
        out.setId(row.getId());
        out.setStatus(row.getStatus());
        out.setAudienceCount(zero(row.getAudienceCount()));
        out.setConsentedCount(zero(row.getConsentedCount()));
        out.setNoConsentCount(zero(row.getNoConsentCount()));
        out.setFrequencySkippedCount(zero(row.getFrequencySkippedCount()));
        out.setDeliveredCount(zero(row.getDeliveredCount()));
        out.setFailedCount(zero(row.getFailedCount()));
        out.setCreateTime(row.getCreateTime());
        return out;
    }

    private MerchantCrmCustomerQuery query(Long merchantId, MerchantCrmBroadcastRequest request) {
        MerchantCrmCustomerQuery query = new MerchantCrmCustomerQuery();
        query.setMerchantId(merchantId);
        query.setPageNum(1);
        query.setPageSize(RECIPIENT_LIMIT);
        String keyword = request == null ? null : trimToNull(request.getKeyword());
        if (keyword != null) {
            if (keyword.length() > 60) throw new ServiceException("搜索词过长", 400);
            query.setKeyword(keyword);
        }
        String segment = request == null ? null : trimToNull(request.getSegment());
        if (segment != null && !"all".equals(segment)) {
            if (!SEGMENTS.contains(segment)) throw new ServiceException("客户分段无效", 400);
            query.setSegment(segment);
        }
        Long tagId = request == null ? null : request.getTagId();
        if (tagId != null) {
            if (tagId <= 0) throw new ServiceException("标签无效", 400);
            query.setTagId(tagId);
        }
        // 走查 v2 R07:主题工作台客户弹窗只发本主题买票玩家。归属不另判 —— 名册口径
        // (OwnedRegistrationScope)本来就只含本商家发布或承接的主题,别人的主题收窄出来是 0 人。
        Long topicId = request == null ? null : request.getTopicId();
        if (topicId != null) {
            if (topicId <= 0) throw new ServiceException("主题无效", 400);
            query.setTopicId(topicId);
        }
        Integer sourceType = request == null ? null : request.getSourceType();
        if (sourceType != null) {
            if (sourceType != 1 && sourceType != 2) throw new ServiceException("来源类型无效", 400);
            query.setSourceType(sourceType);
        }
        query.setSourceStart(sourceDate(request == null ? null : request.getSourceStart()));
        query.setSourceEnd(sourceDate(request == null ? null : request.getSourceEnd()));
        if (query.getSourceStart() != null) {
            query.setSourceStartTime(Timestamp.valueOf(LocalDate.parse(query.getSourceStart()).atStartOfDay()));
        }
        if (query.getSourceEnd() != null) {
            query.setSourceEndExclusive(Timestamp.valueOf(LocalDate.parse(query.getSourceEnd()).plusDays(1).atStartOfDay()));
        }
        return query;
    }

    private String sourceDate(String value) {
        String out = trimToNull(value);
        if (out == null) return null;
        if (!out.matches("\\d{4}-\\d{2}-\\d{2}")) throw new ServiceException("时间筛选格式无效", 400);
        try {
            LocalDate.parse(out);
        } catch (Exception e) {
            throw new ServiceException("时间筛选格式无效", 400);
        }
        return out;
    }

    private String scope(String value) {
        String out = value == null ? "ALL" : value.trim().toUpperCase();
        if (!"ALL".equals(out) && !"TEAM".equals(out) && !"ROLE".equals(out) && !"MEMBERS".equals(out)) {
            throw new ServiceException("范围选择无效", 400);
        }
        return out;
    }

    private Set<String> groups(List<String> values, String scope) {
        Set<String> out = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String trimmed = trimToNull(value);
                if (trimmed == null) continue;
                if (trimmed.length() > 40) throw new ServiceException("范围名称过长", 400);
                out.add(trimmed);
                if (out.size() > GROUP_PICK_MAX) throw new ServiceException("一次最多选 " + GROUP_PICK_MAX + " 个范围", 400);
            }
        }
        if (("TEAM".equals(scope) || "ROLE".equals(scope)) && out.isEmpty()) {
            throw new ServiceException("请选择要发送的队伍或角色", 400);
        }
        return out;
    }

    private Set<Long> memberIds(List<Long> values, String scope) {
        Set<Long> out = new LinkedHashSet<>();
        if (!"MEMBERS".equals(scope)) return out;
        if (values != null) {
            for (Long value : values) {
                if (value == null || value <= 0) throw new ServiceException("选择的客户无效", 400);
                out.add(value);
                if (out.size() > RECIPIENT_LIMIT) throw new ServiceException("一次最多选 " + RECIPIENT_LIMIT + " 位客户", 400);
            }
        }
        if (out.isEmpty()) throw new ServiceException("请先选择要发送的客户", 400);
        return out;
    }

    private String content(String value) {
        String out = trimToNull(value);
        if (out == null) throw new ServiceException("请填写广播内容", 400);
        if (out.length() > CONTENT_MAX) throw new ServiceException("广播内容最多 " + CONTENT_MAX + " 字", 400);
        return out;
    }

    private String requestId(String value) {
        String out = value == null ? "" : value.trim();
        if (!REQUEST_ID.matcher(out).matches()) throw new ServiceException("请求标识无效", 400);
        return out;
    }

    private String filterJson(String scope, Set<String> groups, Set<Long> memberIds, MerchantCrmCustomerQuery query) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("keyword", query.getKeyword());
        snapshot.put("segment", query.getSegment());
        snapshot.put("tagId", query.getTagId());
        snapshot.put("topicId", query.getTopicId());
        snapshot.put("sourceType", query.getSourceType());
        snapshot.put("sourceStart", query.getSourceStart());
        snapshot.put("sourceEnd", query.getSourceEnd());
        snapshot.put("scope", scope);
        snapshot.put("groups", new ArrayList<>(groups));
        snapshot.put("memberIds", new ArrayList<>(memberIds));
        try {
            return JSON.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new ServiceException("筛选快照保存失败", 500);
        }
    }

    private void appendAudit(MerchantCrmBroadcast broadcast, String action, String requestId,
                             Long actorMemberId, String actorRoleCode) {
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType("MERCHANT");
        audit.setDomainId(broadcast.getMerchantId());
        audit.setOwnerMemberId(broadcast.getMerchantOwnerMemberId());
        audit.setActorMemberId(actorMemberId);
        audit.setActorRoleCode(actorRoleCode);
        audit.setAction(action);
        audit.setTargetType("CRM_BROADCAST");
        audit.setTargetId(broadcast.getId());
        audit.setAfterJson("{\"channel\":\"" + CHANNEL + "\",\"audienceCount\":"
                + zero(broadcast.getAudienceCount()) + "}");
        audit.setRequestId(requestId);
        audit.setCreateTime(new Date());
        requireOne(auditMapper.insertBusinessAuditEvent(audit));
    }

    private void requireOne(int rows) {
        if (rows != 1) throw new ServiceException("广播状态已变化，请刷新", 409);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String out = value.trim();
        return out.isEmpty() ? null : out;
    }

    private int zero(Integer value) { return value == null ? 0 : value; }
}

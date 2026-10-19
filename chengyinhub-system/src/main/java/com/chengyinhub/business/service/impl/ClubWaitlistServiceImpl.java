package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistClaim;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubWaitlistOrderBridge;
import com.chengyinhub.business.service.IClubWaitlistService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.Date;
import java.util.List;
<<<<<<< public
import java.util.UUID;
=======
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
>>>>>>> private-codex/batch1
import java.util.function.Supplier;

@Service
public class ClubWaitlistServiceImpl implements IClubWaitlistService, IClubWaitlistOrderBridge {
    private static final Logger logger = LoggerFactory.getLogger(ClubWaitlistServiceImpl.class);
    private final ClubEventOpsMapper mapper;
    private final long[] maintenanceCursors = new long[4];
    private final OmsTicketMapper ticketMapper;
    private final IClubParticipationGuard participationGuard;
    private final ClubWaitlistTokenCodec tokenCodec;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    public ClubWaitlistServiceImpl(ClubEventOpsMapper mapper, OmsTicketMapper ticketMapper,
                                   IClubParticipationGuard participationGuard,
                                   ClubWaitlistTokenCodec tokenCodec,
                                   PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.ticketMapper = ticketMapper;
        this.participationGuard = participationGuard;
        this.tokenCodec = tokenCodec;
        this.transactionManager = transactionManager;
    }

    /** 纯单测构造；生产始终由上面的 Spring 构造注入事务管理器。 */
    public ClubWaitlistServiceImpl(ClubEventOpsMapper mapper, OmsTicketMapper ticketMapper,
                                   IClubParticipationGuard participationGuard,
                                   ClubWaitlistTokenCodec tokenCodec) {
        this(mapper, ticketMapper, participationGuard, tokenCodec, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubWaitlistEntry join(Long memberId, Long activityId, Long ticketId) {
        requirePositive(memberId, activityId, ticketId);
        ClubEventSeries policy = requirePolicy(activityId, true);
        participationGuard.requireAllowed(policy.getClubId(), memberId);
        if (mapper.isActiveParticipant(policy.getClubId(), memberId) != 1) {
            throw forbidden("仅俱乐部正常成员可候补");
        }
        OmsTicket ticket = requireScopedTicket(
                ticketMapper.selectOmsTicketByIdForUpdate(ticketId), activityId, ticketId);
        long remaining = ticket.getRemainingInventory() == null ? -1L : ticket.getRemainingInventory();
        if (remaining < 0) throw conflict("票库存状态未知");
        if (remaining > 0) throw conflict("当前仍有名额，请直接报名");
        if (mapper.countActiveRegistration(memberId, activityId, ticketId) > 0) {
            throw conflict("你已有该票种的有效报名");
        }
        if (mapper.selectActiveWaitlist(memberId, activityId, ticketId) != null) {
            throw conflict("你已在该票种候补队列中");
        }
        Date now = new Date();
        ClubWaitlistEntry entry = new ClubWaitlistEntry();
        entry.setClubId(policy.getClubId());
        entry.setActivityId(activityId);
        entry.setTicketId(ticketId);
        entry.setMemberId(memberId);
        entry.setState(ClubWaitlistEntry.WAITING);
        entry.setJoinedAt(now);
        entry.setVersion(0);
        try {
            if (mapper.insertWaitlist(entry) != 1 || entry.getId() == null) throw conflict("候补加入失败");
        } catch (DuplicateKeyException duplicate) {
            throw conflict("你已在该票种候补队列中");
        }
        return entry;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubWaitlistEntry myStatus(Long memberId, Long activityId, Long ticketId) {
        requirePositive(memberId, activityId, ticketId);
        ClubEventSeries policy = mapper.selectSeriesPolicyByActivity(activityId);
        if (policy == null || policy.getId() == null || policy.getClubId() == null
                || !ClubEventSeries.STATUS_ACTIVE.equals(policy.getStatus())) {
            return statusView(null, memberId, activityId, ticketId, null,
                    ClubWaitlistEntry.ELIGIBILITY_NO_SERIES, false);
        }
        if (!Boolean.TRUE.equals(policy.getWaitlistEnabled())) {
            ClubWaitlistEntry latest = mapper.selectLatestWaitlist(memberId, activityId, ticketId);
            latest = expireStaleOfferForStatus(latest, memberId, activityId, ticketId, new Date());
            return statusView(latest, memberId, activityId, ticketId, policy.getClubId(),
                    ClubWaitlistEntry.ELIGIBILITY_CLOSED, false);
        }
        if (mapper.isActiveParticipant(policy.getClubId(), memberId) != 1) {
            return statusView(null, memberId, activityId, ticketId, policy.getClubId(),
                    ClubWaitlistEntry.ELIGIBILITY_NOT_MEMBER, false);
        }
        try {
            participationGuard.requireAllowed(policy.getClubId(), memberId);
        } catch (ServiceException blocked) {
            return statusView(null, memberId, activityId, ticketId, policy.getClubId(),
                    ClubWaitlistEntry.ELIGIBILITY_BLOCKED, false);
        }
        ClubWaitlistEntry entry = mapper.selectLatestWaitlist(memberId, activityId, ticketId);
        entry = expireStaleOfferForStatus(entry, memberId, activityId, ticketId, new Date());
        if (mapper.countActiveRegistration(memberId, activityId, ticketId) > 0) {
            return statusView(entry, memberId, activityId, ticketId, policy.getClubId(),
                    ClubWaitlistEntry.ELIGIBILITY_REGISTERED, false);
        }
        entry = statusView(entry, memberId, activityId, ticketId, policy.getClubId(),
                ClubWaitlistEntry.ELIGIBILITY_ELIGIBLE, true);
        if (ClubWaitlistEntry.OFFERED.equals(entry.getState())) {
            String raw = tokenCodec.token(entry);
            if (!tokenCodec.hash(raw).equals(entry.getOfferTokenHash())) {
                throw conflict("候补 token 状态不一致");
            }
            entry.setOfferToken(raw);
        }
        return entry;
    }

    private ClubWaitlistEntry expireStaleOfferForStatus(ClubWaitlistEntry entry, Long memberId,
                                                         Long activityId, Long ticketId, Date now) {
        if (entry == null || !ClubWaitlistEntry.OFFERED.equals(entry.getState())
                || entry.getOfferExpiresAt() == null || entry.getOfferExpiresAt().after(now)) {
            return entry;
        }
        validateEntryScope(entry, memberId, activityId, ticketId);
        // myStatus 自身在事务内；这里沿用统一 series -> club -> ticket -> waitlist 锁序，
        // 成功则真实释放 hold，CAS 竞争失败也会重读并在响应层去掉付款能力。
        expireOffered(entry.getId(), now);
        ClubWaitlistEntry refreshed = mapper.selectLatestWaitlist(memberId, activityId, ticketId);
        if (refreshed == null) {
            entry.setState(ClubWaitlistEntry.EXPIRED);
            entry.setOfferToken(null);
            return entry;
        }
        validateEntryScope(refreshed, memberId, activityId, ticketId);
        if (refreshed != null && ClubWaitlistEntry.OFFERED.equals(refreshed.getState())
                && refreshed.getOfferExpiresAt() != null && !refreshed.getOfferExpiresAt().after(now)) {
            refreshed.setState(ClubWaitlistEntry.EXPIRED);
            refreshed.setOfferToken(null);
        }
        return refreshed;
    }

    private ClubWaitlistEntry statusView(ClubWaitlistEntry entry, Long memberId, Long activityId,
                                         Long ticketId, Long clubId, String eligibility,
                                         boolean joinAllowed) {
        ClubWaitlistEntry status = entry == null ? new ClubWaitlistEntry() : entry;
        if (entry != null) {
            validateEntryScope(entry, memberId, activityId, ticketId);
            if (clubId != null && !clubId.equals(entry.getClubId())) {
                throw forbidden("候补俱乐部范围不一致");
            }
        } else {
            status.setMemberId(memberId);
            status.setActivityId(activityId);
            status.setTicketId(ticketId);
            status.setClubId(clubId);
            status.setState(ClubWaitlistEntry.NONE);
        }
        status.setOfferToken(null);
        status.setEligibilityState(eligibility);
        status.setWaitlistJoinAllowed(joinAllowed);
        return status;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(Long memberId, Long activityId, Long ticketId, Date now) {
        requirePositive(memberId, activityId, ticketId);
        if (now == null) throw badRequest("候补取消时间不能为空");
        ClubEventSeries policy = requirePolicy(activityId, false);
        Long lockedOwnerMemberId = lockClubBeforeTicket(policy);
        OmsTicket ticket = requireScopedTicket(
                ticketMapper.selectOmsTicketByIdForUpdate(ticketId), activityId, ticketId);
        ClubWaitlistEntry entry = mapper.selectActiveWaitlistForUpdate(memberId, activityId, ticketId);
        if (entry == null) return false;
        validateEntryScope(entry, memberId, activityId, ticketId);
        if (!policy.getClubId().equals(entry.getClubId()) || entry.getVersion() == null) {
            throw conflict("候补记录范围不一致");
        }
        if (ClubWaitlistEntry.CLAIMED.equals(entry.getState())) {
            throw conflict("候补已生成报名单，请从报名订单取消");
        }
        if (!ClubWaitlistEntry.WAITING.equals(entry.getState())
                && !ClubWaitlistEntry.OFFERED.equals(entry.getState())) return false;
        if (mapper.cancelActiveWaitlistCas(entry.getId(), entry.getVersion(), entry.getState(),
                memberId, activityId, ticketId) != 1) throw conflict("候补状态已变化，请刷新");
        if (ClubWaitlistEntry.OFFERED.equals(entry.getState())) {
            if (ticketMapper.restoreInventory(ticket.getId(), 1L) != 1) {
                throw conflict("候补取消库存回补失败");
            }
            promoteNextLocked(policy, lockedOwnerMemberId, activityId, ticketId, now);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean promoteNext(Long activityId, Long ticketId, Date now) {
        requirePositive(activityId, ticketId);
        if (now == null) throw badRequest("晋级时间不能为空");
        ClubEventSeries policy = requirePolicy(activityId, false);
        Long lockedOwnerMemberId = lockClubBeforeTicket(policy);
        return promoteNextLocked(policy, lockedOwnerMemberId, activityId, ticketId, now);
    }

    private boolean promoteNextLocked(ClubEventSeries policy, Long lockedOwnerMemberId,
                                      Long activityId, Long ticketId, Date now) {
        if (!Boolean.TRUE.equals(policy.getWaitlistEnabled())) return false;
        OmsTicket ticket = requireScopedTicket(
                ticketMapper.selectOmsTicketByIdForUpdate(ticketId), activityId, ticketId);
        if (ticket.getRemainingInventory() == null || ticket.getRemainingInventory() <= 0) return false;
        Date deadline = offerDeadline(ticket, now, policy.getOfferMinutes());
        if (deadline == null || !deadline.after(now)) return false;
        for (int inspected = 0; inspected < 100; inspected++) {
            ClubWaitlistEntry entry = mapper.selectFirstWaitingForUpdate(activityId, ticketId);
            if (entry == null) return false;
            if (!ClubWaitlistEntry.WAITING.equals(entry.getState()) || entry.getVersion() == null
                    || !policy.getClubId().equals(entry.getClubId()) || !activityId.equals(entry.getActivityId())
                    || !ticketId.equals(entry.getTicketId()) || entry.getMemberId() == null) {
                throw conflict("候补队首状态不一致");
            }
            if (mapper.isActiveParticipant(entry.getClubId(), entry.getMemberId()) != 1) {
                cancelIneligibleWaiting(entry, activityId, ticketId);
                continue;
            }
            // club 行已在 ticket 之前锁定；再锁 member 复核，统一正常报名/退会锁序。
            if (!isActiveParticipantForPromotionLocked(
                    entry.getClubId(), entry.getMemberId(), lockedOwnerMemberId)) {
                cancelIneligibleWaiting(entry, activityId, ticketId);
                continue;
            }
            entry.setOfferedAt(now);
            entry.setOfferExpiresAt(deadline);
            String tokenHash = tokenCodec.hash(tokenCodec.token(entry));
            // 已按 club -> ticket 锁序持锁；占库存成功后才允许 WAITING -> OFFERED。
            if (ticketMapper.deductInventory(ticketId, 1L) != 1) throw conflict("候补库存占位失败");
            if (mapper.offerWaitingCas(entry.getId(), entry.getVersion(), tokenHash, now, deadline) != 1) {
                throw conflict("候补队首状态已变化");
            }
            enqueueWaitlistNotification(policy.getClubId(), lockedOwnerMemberId, activityId, entry.getMemberId(),
                    "WAITLIST", "候补名额通知", "你已获得活动候补名额，请在候补截止时间前完成报名。", now);
            return true;
        }
        return false;
    }

    private void enqueueWaitlistNotification(Long clubId, Long actorMemberId, Long activityId,
                                             Long recipientMemberId, String audienceType,
                                             String title, String content, Date now) {
        ClubEventCampaign campaign = new ClubEventCampaign();
        campaign.setClubId(clubId);
        campaign.setActivityId(activityId);
        campaign.setAudienceType(audienceType);
        campaign.setChannel("IN_APP");
        campaign.setTitle(title);
        campaign.setContent(content);
        campaign.setStatus(ClubEventCampaign.PROCESSING);
        campaign.setActorMemberId(actorMemberId);
        campaign.setRequestId(UUID.randomUUID().toString());
        campaign.setTotalCount(1);
        campaign.setSuccessCount(0);
        campaign.setFailedCount(0);
        campaign.setCreateTime(now);
        campaign.setUpdateTime(now);
        if (mapper.insertCampaign(campaign) != 1 || campaign.getId() == null || campaign.getId() <= 0) {
            throw conflict("候补通知任务创建失败");
        }

        ClubEventDelivery delivery = new ClubEventDelivery();
        delivery.setCampaignId(campaign.getId());
        delivery.setRecipientMemberId(recipientMemberId);
        delivery.setChannel("IN_APP");
        delivery.setStatus(ClubEventDelivery.PENDING);
        delivery.setAttemptCount(0);
        delivery.setVersion(0);
        if (mapper.insertDelivery(delivery) != 1 || delivery.getId() == null || delivery.getId() <= 0
                || mapper.insertOutbox(delivery.getId(), now) != 1) {
            throw conflict("候补通知收件回执创建失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean expireOffered(Long entryId, Date now) {
        if (entryId == null || entryId <= 0 || now == null) throw badRequest("候补过期参数不完整");
        ClubWaitlistEntry snapshot = mapper.selectWaitlistById(entryId);
        if (snapshot == null || !ClubWaitlistEntry.OFFERED.equals(snapshot.getState())
                || snapshot.getOfferExpiresAt() == null || snapshot.getOfferExpiresAt().after(now)) return false;
        ClubEventSeries lockedSeries = mapper.selectSeriesByActivityAnyStatusForUpdate(snapshot.getActivityId());
        if (lockedSeries == null || lockedSeries.getClubId() == null
                || !lockedSeries.getClubId().equals(snapshot.getClubId())) {
            throw conflict("候补系列范围不一致");
        }
        Long lockedOwnerMemberId = lockClubBeforeTicket(lockedSeries);
        requireScopedTicket(ticketMapper.selectOmsTicketByIdForUpdate(snapshot.getTicketId()),
                snapshot.getActivityId(), snapshot.getTicketId());
        ClubWaitlistEntry entry = mapper.selectWaitlistByIdForUpdate(entryId);
        if (entry == null || !ClubWaitlistEntry.OFFERED.equals(entry.getState())) return false;
        if (!snapshot.getActivityId().equals(entry.getActivityId())
                || !snapshot.getTicketId().equals(entry.getTicketId())
                || !snapshot.getClubId().equals(entry.getClubId())
                || entry.getOfferExpiresAt() == null || entry.getOfferExpiresAt().after(now)) return false;
        if (mapper.expireOfferedCas(entry.getId(), entry.getVersion(), now) != 1) return false;
        if (ticketMapper.restoreInventory(entry.getTicketId(), 1L) != 1) {
            throw conflict("候补过期库存回补失败");
        }
        enqueueWaitlistNotification(entry.getClubId(), lockedOwnerMemberId, entry.getActivityId(), entry.getMemberId(),
                "ALL_MEMBERS", "候补名额已过期", "你的活动候补名额已过期，如仍需参加，请重新加入候补。", now);
        ClubEventSeries activePolicy = mapper.selectSeriesPolicyByActivity(entry.getActivityId());
        if (activePolicy != null) {
            promoteNextLocked(activePolicy, lockedOwnerMemberId,
                    entry.getActivityId(), entry.getTicketId(), now);
        }
        return true;
    }

    @Override
    public synchronized int maintain(Date now) {
        if (now == null) throw badRequest("维护时间不能为空");
<<<<<<< public
        Long maxId = mapper.selectWaitlistMaintenanceMaxId();
        if (maxId == null) { java.util.Arrays.fill(maintenanceCursors, 0L); return 0; }
        int changed = 0;
        for (int kind = 0; kind < 4; kind++) {
            int scanned = 0;
            boolean reachedEnd = false;
            while (scanned < 500) {
                List<java.util.Map<String, Object>> page;
                try {
                    page = mapper.selectWaitlistMaintenancePage(kind, now, maintenanceCursors[kind],
                            maxId, Math.min(100, 500 - scanned));
                } catch (Exception pageFailure) {
                    logger.error("候补维护页读取失败，保留游标并继续其他域: kind={}", kind, pageFailure);
                    break;
                }
                if (page == null || page.isEmpty()) { reachedEnd = true; break; }
                for (java.util.Map<String, Object> row : page) {
                    Long scanId = scopedId(row, "scanId");
                    if (scanId == null || scanId <= maintenanceCursors[kind] || scanId > maxId) {
                        throw new IllegalStateException("候补维护游标页异常");
                    }
                    scanned++;
                    try {
                        final int action = kind;
                        Long targetId = kind == 3 ? null : scopedId(row, "targetId");
                        Long activityId = kind == 3 ? scopedId(row, "activityId") : null;
                        Long ticketId = kind == 3 ? scopedId(row, "ticketId") : null;
                        if (maintenanceRequiresNew(() -> {
                            if (action == 0) return expireOffered(targetId, now);
                            if (action == 1) return convertClaimedByRegistration(targetId);
                            if (action == 2) return expireClaimedTerminal(targetId, now);
                            return promoteNext(activityId, ticketId, now);
                        })) changed++;
                    } catch (Exception poison) {
                        logger.error("候补维护单项失败，已隔离并继续: kind={} scanId={}", kind, scanId, poison);
                    } finally {
                        maintenanceCursors[kind] = scanId;
                    }
                }
                if (maintenanceCursors[kind] >= maxId) { reachedEnd = true; break; }
            }
            if (reachedEnd) maintenanceCursors[kind] = 0L;
        }
        return changed;
=======
        final Date cutoff = new Date(now.getTime());
        final Long upperId = mapper.selectWaitlistScanUpperId();
        if (upperId == null) return 0;
        int changed = maintainPages("EXPIRE_OFFERED",
                cursor -> mapper.selectExpiredOfferedPage(cutoff, upperId,
                        cursor == null ? null : cursor.getOfferExpiresAt(),
                        cursor == null ? null : cursor.getId(), 100),
                entry -> expireOffered(entry.getId(), cutoff));
        changed += maintainPages("CONVERT_CLAIMED",
                cursor -> mapper.selectClaimedPaidPage(upperId, cursor == null ? null : cursor.getId(), 100),
                entry -> convertClaimedByRegistration(entry.getRegistrationId()));
        changed += maintainPages("EXPIRE_CLAIMED",
                cursor -> mapper.selectClaimedTerminalPage(upperId, cursor == null ? null : cursor.getId(), 100),
                entry -> expireClaimedTerminal(entry.getRegistrationId(), cutoff));
        Set<String> inspectedScopes = new HashSet<>();
        changed += maintainPages("PROMOTE",
                cursor -> mapper.selectPromotableWaitlistScopePage(cutoff, upperId,
                        cursor == null ? null : cursor.getJoinedAt(), cursor == null ? null : cursor.getId(), 100),
                entry -> {
                    requirePositive(entry.getActivityId(), entry.getTicketId());
                    if (!inspectedScopes.add(entry.getActivityId() + ":" + entry.getTicketId())) return false;
                    return promoteNext(entry.getActivityId(), entry.getTicketId(), cutoff);
                });
        return changed;
    }

    private int maintainPages(String kind,
                              Function<ClubWaitlistEntry, List<ClubWaitlistEntry>> query,
                              Function<ClubWaitlistEntry, Boolean> work) {
        int changed = 0;
        ClubWaitlistEntry cursor = null;
        while (true) {
            List<ClubWaitlistEntry> page = safe(query.apply(cursor));
            if (page.isEmpty()) return changed;
            for (ClubWaitlistEntry entry : page) {
                try {
                    if (maintenanceRequiresNew(() -> work.apply(entry))) changed++;
                } catch (Exception poison) {
                    logger.error("候补维护单项失败，已隔离并继续: kind={} entryId={}",
                            kind, entry == null ? null : entry.getId(), poison);
                }
            }
            if (page.size() < 100) return changed;
            ClubWaitlistEntry last = page.get(page.size() - 1);
            if (last == null || last.getId() == null || (cursor != null
                    && Objects.equals(cursor.getId(), last.getId())
                    && Objects.equals(cursor.getOfferExpiresAt(), last.getOfferExpiresAt())
                    && Objects.equals(cursor.getJoinedAt(), last.getJoinedAt()))) {
                throw conflict("候补扫描游标未推进");
            }
            // 失败行仍在库内，必须采用查询页游标，不能按成功数偏移。
            cursor = last;
        }
>>>>>>> private-codex/batch1
    }

    private boolean isActiveParticipantForPromotionLocked(Long clubId, Long memberId,
                                                           Long lockedOwnerMemberId) {
        if (lockedOwnerMemberId == null) return false;
        if (lockedOwnerMemberId.equals(memberId)) return true;
        return memberId.equals(mapper.selectActiveClubMemberForUpdate(clubId, memberId));
    }

    private Long lockClubBeforeTicket(ClubEventSeries policy) {
        if (policy == null || policy.getClubId() == null) throw conflict("候补俱乐部范围不一致");
        Long ownerMemberId = mapper.selectClubOwnerForUpdate(policy.getClubId());
        if (ownerMemberId == null) throw conflict("候补俱乐部不存在");
        return ownerMemberId;
    }

    private void cancelIneligibleWaiting(ClubWaitlistEntry entry, Long activityId, Long ticketId) {
        if (mapper.cancelActiveWaitlistCas(entry.getId(), entry.getVersion(), ClubWaitlistEntry.WAITING,
                entry.getMemberId(), activityId, ticketId) != 1) {
            throw conflict("候补队首状态已变化");
        }
    }

    private <T> T maintenanceRequiresNew(Supplier<T> work) {
        if (transactionManager == null) return work.get();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> work.get());
    }

    private boolean expireClaimedTerminal(Long registrationId, Date now) {
        ClubWaitlistEntry snapshot = mapper.selectWaitlistByRegistration(registrationId);
        if (snapshot == null || !ClubWaitlistEntry.CLAIMED.equals(snapshot.getState())) return false;
        ClubEventSeries lockedSeries = mapper.selectSeriesByActivityAnyStatusForUpdate(snapshot.getActivityId());
        if (lockedSeries == null || lockedSeries.getClubId() == null
            || !lockedSeries.getClubId().equals(snapshot.getClubId())) {
            throw conflict("候补系列范围不一致");
        }
        Long lockedOwnerMemberId = lockClubBeforeTicket(lockedSeries);
        requireScopedTicket(ticketMapper.selectOmsTicketByIdForUpdate(snapshot.getTicketId()),
                snapshot.getActivityId(), snapshot.getTicketId());
        ClubWaitlistEntry entry = mapper.selectWaitlistByRegistrationForUpdate(registrationId);
        if (entry == null || !ClubWaitlistEntry.CLAIMED.equals(entry.getState())
                || !snapshot.getActivityId().equals(entry.getActivityId())
                || !snapshot.getTicketId().equals(entry.getTicketId())
                || !snapshot.getClubId().equals(entry.getClubId())) return false;
        if (mapper.expireClaimedTerminalCas(registrationId) != 1) return false;
        // 报名单关单事务已经回补库存；这里只消费终态并晋级，绝不再次 restore。
        ClubEventSeries activePolicy = mapper.selectSeriesPolicyByActivity(entry.getActivityId());
        if (activePolicy != null) {
            promoteNextLocked(activePolicy, lockedOwnerMemberId,
                    entry.getActivityId(), entry.getTicketId(), now);
        }
        return true;
    }

    @Override
    public ClubWaitlistClaim validateForQuote(Long memberId, RegistrationRequest request, Date now) {
        ClubWaitlistEntry entry = validateOffered(memberId, request, now, false);
        return toClaim(entry);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubWaitlistClaim claimForOrder(Long memberId, RegistrationRequest request, Date now) {
        if (request == null) throw badRequest("报名参数不能为空");
        if (request.getWaitlistOfferId() == null && request.getWaitlistOfferToken() == null) return null;
        ClubWaitlistEntry entry = validateOffered(memberId, request, now, true);
        if (mapper.claimOfferCas(entry.getId(), entry.getVersion(), memberId,
                entry.getActivityId(), entry.getTicketId(), now) != 1) {
            throw conflict("候补 offer 已被使用");
        }
        return toClaim(entry);
    }

    private ClubWaitlistEntry validateOffered(Long memberId, RegistrationRequest request, Date now,
                                               boolean forUpdate) {
        if (request == null) throw badRequest("报名参数不能为空");
        Long offerId = request.getWaitlistOfferId();
        String rawToken = request.getWaitlistOfferToken();
        if (offerId == null || offerId <= 0 || rawToken == null || rawToken.length() > 256 || now == null) {
            throw badRequest("候补 offer 与 token 必须成对提交");
        }
        if (!Integer.valueOf(2).equals(request.getOwnerType()) || request.getOwnerId() == null
                || request.getTicketId() == null
                || request.getTickets() != null && !request.getTickets().isEmpty()) {
            throw badRequest("候补 offer 仅支持同活动同票种单票报名");
        }
        ClubEventSeries policy = forUpdate ? requirePolicy(request.getOwnerId(), true) : null;
        ClubWaitlistEntry entry = forUpdate
                ? mapper.selectWaitlistByIdForUpdate(offerId) : mapper.selectWaitlistById(offerId);
        if (entry == null || !ClubWaitlistEntry.OFFERED.equals(entry.getState()) || entry.getVersion() == null
                || !memberId.equals(entry.getMemberId()) || !request.getOwnerId().equals(entry.getActivityId())
                || !request.getTicketId().equals(entry.getTicketId()) || entry.getOfferExpiresAt() == null
                || !entry.getOfferExpiresAt().after(now) || !tokenCodec.matches(entry, rawToken)) {
            throw forbidden("候补 offer 无效、已过期或不属于当前报名");
        }
        if (!forUpdate) policy = requirePolicyForQuote(entry.getActivityId());
        if (!entry.getClubId().equals(policy.getClubId())) throw forbidden("候补俱乐部范围不一致");
        participationGuard.requireAllowed(entry.getClubId(), memberId);
        if (mapper.isActiveParticipant(entry.getClubId(), memberId) != 1) {
            throw forbidden("仅俱乐部正常成员可认领候补名额");
        }
        return entry;
    }

    private ClubWaitlistClaim toClaim(ClubWaitlistEntry entry) {
        return new ClubWaitlistClaim(entry.getId(), entry.getClubId(), entry.getMemberId(),
                entry.getActivityId(), entry.getTicketId(), entry.getOfferExpiresAt());
    }

    @Override
    public void bindRegistration(ClubWaitlistClaim claim, Long registrationId) {
        if (claim == null || registrationId == null || registrationId <= 0
                || mapper.bindClaimedRegistrationCas(claim.getOfferId(), registrationId) != 1) {
            throw conflict("候补与报名单绑定失败");
        }
    }

    @Override
    public void convertedByRegistration(Long registrationId) {
        if (registrationId != null && registrationId > 0) convertClaimedByRegistration(registrationId);
    }

    private boolean convertClaimedByRegistration(Long registrationId) {
        int rows = mapper.convertClaimedByRegistration(registrationId);
        if (rows > 1) throw conflict("候补与报名单状态不一致");
        return rows == 1;
    }

    private ClubEventSeries requirePolicy(Long activityId, boolean requireEnabled) {
        ClubEventSeries policy = mapper.selectSeriesPolicyByActivityForUpdate(activityId);
        if (policy == null || policy.getId() == null || policy.getClubId() == null
                || !ClubEventSeries.STATUS_ACTIVE.equals(policy.getStatus())) {
            throw conflict("场次候补策略不存在");
        }
        if (requireEnabled && !Boolean.TRUE.equals(policy.getWaitlistEnabled())) {
            throw conflict("该场次未开放候补");
        }
        return policy;
    }

    private ClubEventSeries requirePolicyForQuote(Long activityId) {
        ClubEventSeries policy = mapper.selectSeriesPolicyByActivity(activityId);
        if (policy == null || policy.getId() == null || policy.getClubId() == null
                || !ClubEventSeries.STATUS_ACTIVE.equals(policy.getStatus())
                || !Boolean.TRUE.equals(policy.getWaitlistEnabled())) {
            throw conflict("场次候补策略不存在或已关闭");
        }
        return policy;
    }

    private OmsTicket requireScopedTicket(OmsTicket ticket, Long activityId, Long ticketId) {
        if (ticket == null || !ticketId.equals(ticket.getId()) || !Long.valueOf(2L).equals(ticket.getOwnerType())
                || !activityId.equals(ticket.getOwnerId()) || !Integer.valueOf(0).equals(ticket.getDelFlag())
                || ticket.getTotalInventory() == null || ticket.getTotalInventory() <= 0) {
            throw forbidden("票种不属于当前活动");
        }
        return ticket;
    }

    private Date offerDeadline(OmsTicket ticket, Date now, Integer configuredMinutes) {
        // 2026-09-11 用户裁决:默认一天。俱乐部活动大多提前几天开卖,按分钟计没有道理 ——
        // 30 分钟意味着「手机没在身边就丢名额」。真正的护栏是下面那句 min(...):
        // 窗口再长也活不过这张票本身的报名截止/开场时间。
        int minutes = configuredMinutes == null ? 1440 : configuredMinutes;
        if (minutes < 5 || minutes > 1440) throw conflict("候补认领窗口配置无效");
        Date deadline = new Date(now.getTime() + minutes * 60_000L);
        Date saleDeadline = ticket.getSignupDeadline() != null ? ticket.getSignupDeadline() : ticket.getStartTime();
        Date selectedDeadline = saleDeadline != null && saleDeadline.before(deadline) ? saleDeadline : deadline;
        // offer_expires_at 是 DATETIME(0)：签名与写库共用可无损保存的秒值，向下对齐不延长认领窗口。
        return new Date(Math.floorDiv(selectedDeadline.getTime(), 1000L) * 1000L);
    }

    private void validateEntryScope(ClubWaitlistEntry entry, Long memberId, Long activityId, Long ticketId) {
        if (!memberId.equals(entry.getMemberId()) || !activityId.equals(entry.getActivityId())
                || !ticketId.equals(entry.getTicketId())) throw forbidden("候补记录范围不一致");
    }

    private void requirePositive(Long... values) {
        for (Long value : values) if (value == null || value <= 0) throw badRequest("候补参数不完整");
    }
    private <T> List<T> safe(List<T> values) {
        return values == null ? Collections.<T>emptyList() : values;
    }
    private ServiceException badRequest(String message) { return new ServiceException(message, HttpStatus.BAD_REQUEST); }
    private ServiceException forbidden(String message) { return new ServiceException(message, HttpStatus.FORBIDDEN); }
    private ServiceException conflict(String message) { return new ServiceException(message, HttpStatus.CONFLICT); }
}

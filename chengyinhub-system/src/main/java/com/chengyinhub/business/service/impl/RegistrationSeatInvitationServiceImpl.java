package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.GiftPlayFact;
import com.chengyinhub.business.domain.RegistrationSeatInvitation;
import com.chengyinhub.business.domain.RegistrationSeat;
import com.chengyinhub.business.domain.RegistrationSeatInvitationRequestRecord;
import com.chengyinhub.business.domain.dto.SeatInvitationActionRequest;
import com.chengyinhub.business.domain.dto.SeatInvitationClaimRequest;
import com.chengyinhub.business.domain.dto.SeatInvitationCreateRequest;
import com.chengyinhub.business.domain.vo.SeatInvitationOwnerViewVO;
import com.chengyinhub.business.domain.vo.SeatInvitationVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.RegistrationSeatMapper;
import com.chengyinhub.business.mapper.RegistrationSeatInvitationMapper;
import com.chengyinhub.business.mapper.RegistrationSeatInvitationRequestMapper;
import com.chengyinhub.business.service.IMarketingGiftPlayEligibility;
import com.chengyinhub.business.service.IRegistrationSeatInvitationService;
import com.chengyinhub.business.service.support.SeatInvitationTokenCodec;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 已付多人票「内含席位」邀请生命周期。
 *
 * <p>不可变事实：{@code cms_registration} 的 member_id / order_num / 金额 / 支付 / 退款归属
 * 一律不动；席号 1 是购票人自用（现有唯一可玩身份），可邀请 2..N，最多 N-1 个。
 * 邀请、领取、撤销、释放都写本域独立表，不写商户核销席位、不建第二购买。
 *
 * <p>锁序与既有席位/核销一致：activity/topic → cms_registration → invitation。
 * 容量由「原单行锁 + 唯一键 (registration_id, seat_no, active_flag)」双重保证；
 * 状态迁移全部条件更新，0 行即并发已变，返回 409 且不留半截事实。
 */
@Service
public class RegistrationSeatInvitationServiceImpl implements IRegistrationSeatInvitationService {

    private final CmsRegistrationMapper registrations;
    private final CmsActivityMapper activities;
    private final CmsTopicMapper topics;
    private final RegistrationSeatMapper seatFacts;
    private final RegistrationSeatInvitationMapper invitations;
    private final RegistrationSeatInvitationRequestMapper requestRecords;
    private final IMarketingGiftPlayEligibility giftEligibility;
    private final String secret;
    private final long ttlHours;

    public RegistrationSeatInvitationServiceImpl(CmsRegistrationMapper registrations,
                                                 CmsActivityMapper activities,
                                                 CmsTopicMapper topics,
                                                 RegistrationSeatMapper seatFacts,
                                                 RegistrationSeatInvitationMapper invitations,
                                                 RegistrationSeatInvitationRequestMapper requestRecords,
                                                 IMarketingGiftPlayEligibility giftEligibility,
                                                 @Value("${registration.seat-invitation-secret:${verify.dyncode-secret:${token.secret}}}") String secret,
                                                 @Value("${registration.seat-invitation-ttl-hours:168}") long ttlHours) {
        this.registrations = registrations;
        this.activities = activities;
        this.topics = topics;
        this.seatFacts = seatFacts;
        this.invitations = invitations;
        this.requestRecords = requestRecords;
        this.giftEligibility = giftEligibility;
        this.secret = secret;
        this.ttlHours = ttlHours;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public SeatInvitationVO invite(long callerMemberId, SeatInvitationCreateRequest request) {
        requireMember(callerMemberId);
        if (request == null || request.getRegistrationId() == null || request.getRegistrationId() <= 0L) {
            throw badRequest("参数有误");
        }
        String requestId = requireRequestId(request.getRequestId());
        Date now = new Date();
        int requestedSeat = request.getSeatNo() == null ? 0 : request.getSeatNo();
        if (requestedSeat < 0) throw badRequest("席位号无效");
        String fingerprint = fingerprint(RegistrationSeatInvitationRequestRecord.OP_INVITE,
                callerMemberId, request.getRegistrationId(), String.valueOf(requestedSeat));
        RegistrationSeatInvitationRequestRecord prior = replay(requestId, callerMemberId,
                RegistrationSeatInvitationRequestRecord.OP_INVITE, fingerprint);
        if (prior != null) {
            RegistrationSeatInvitation row = invitations.selectById(prior.getInvitationId());
            if (row == null) throw conflict("请求已记录但邀请不存在，请重新发起");
            SeatInvitationVO view = view(row, now, true, originalPaid(row.getRegistrationId()));
            view.setReplayed(Boolean.TRUE);
            return view;
        }

        LockedScope scope = lockScope(request.getRegistrationId());
        now = new Date();
        RegistrationSeatInvitationRequestRecord lockedReplay = replayLocked(requestId, callerMemberId,
                RegistrationSeatInvitationRequestRecord.OP_INVITE, fingerprint);
        if (lockedReplay != null) return replayed(lockedReplay, now);
        requireBuyer(scope.current, callerMemberId);
        requireOriginalShareable(scope, now);

        invitations.expirePending(scope.current.getId(), now);
        List<RegistrationSeatInvitation> active =
                invitations.selectActiveByRegistrationForUpdate(scope.current.getId());
        int seatCount = seatCount(scope.current);
        int invitable = seatCount - 1;
        if (active.size() >= invitable) throw conflict("该订单已无可用邀请席位");
        int seatNo = chooseSeat(active, invitable, requestedSeat);

        Date expiresAt = invitationExpiry(scope, now);
        int maxGeneration = invitations.maxGeneration(scope.current.getId(), seatNo);
        int generation = maxGeneration + 1;
        String nonce = SeatInvitationTokenCodec.nonce();
        String token = SeatInvitationTokenCodec.issue(nonce, expiresAt.getTime() / 1000L, secret);

        RegistrationSeatInvitation row = new RegistrationSeatInvitation();
        row.setRegistrationId(scope.current.getId());
        row.setSeatNo(seatNo);
        row.setGeneration(generation);
        row.setOwnerMemberId(scope.current.getMemberId());
        row.setOwnerType(scope.current.getOwnerType());
        row.setOwnerId(scope.current.getOwnerId());
        row.setTopicId(scope.topicId());
        row.setStatus(RegistrationSeatInvitation.STATUS_PENDING);
        row.setTokenNonce(nonce);
        row.setTokenHash(SeatInvitationTokenCodec.sha256Hex(token));
        row.setExpiresAt(expiresAt);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        row.setVersion(0);
        try {
            if (invitations.insert(row) != 1 || row.getId() == null) {
                throw new IllegalStateException("席位邀请保存失败");
            }
        } catch (DuplicateKeyException occupied) {
            throw conflict("该席位已有有效邀请，请刷新后重试");
        }
        recordRequest(requestId, callerMemberId, RegistrationSeatInvitationRequestRecord.OP_INVITE,
                scope.current.getId(), row.getId(), fingerprint, row.getStatus(), now);
        SeatInvitationVO view = view(row, now, true, true);
        view.setToken(token);
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public SeatInvitationOwnerViewVO ownerView(long callerMemberId, Long registrationId) {
        requireMember(callerMemberId);
        if (registrationId == null || registrationId <= 0L) throw badRequest("参数有误");
        CmsRegistration current = registrations.selectCmsRegistrationById(registrationId);
        if (current == null) throw denied("报名单不存在或无权读取");
        requireBuyer(current, callerMemberId);
        Date now = new Date();

        List<RegistrationSeatInvitation> rows = invitations.selectByRegistration(registrationId);
        if (rows == null) rows = List.of();

        int seatCount = seatCount(current);
        int invitable = seatCount - 1;
        int pending = 0, claimed = 0, expired = 0, cancelled = 0, released = 0;
        List<SeatInvitationVO> views = new ArrayList<>();
        boolean paid = paid(current);
        for (RegistrationSeatInvitation row : rows) {
            if (row == null) continue;
            int effective = SeatInvitationVO.effectiveStatus(row, now);
            if (effective == RegistrationSeatInvitation.STATUS_PENDING) pending++;
            else if (effective == RegistrationSeatInvitation.STATUS_CLAIMED) claimed++;
            else if (effective == RegistrationSeatInvitation.STATUS_EXPIRED) expired++;
            else if (effective == RegistrationSeatInvitation.STATUS_CANCELLED) cancelled++;
            else if (effective == RegistrationSeatInvitation.STATUS_RELEASED) released++;
            views.add(view(row, now, false, paid));
        }

        SeatInvitationOwnerViewVO out = new SeatInvitationOwnerViewVO();
        out.setRegistrationId(registrationId);
        out.setOwnerType(current.getOwnerType());
        out.setOwnerId(current.getOwnerId());
        out.setTopicId(scopeTopicId(current));
        out.setSeatCount(seatCount);
        out.setBuyerSeatNo(1);
        out.setInvitableSeats(invitable);
        out.setPendingSeats(pending);
        out.setClaimedSeats(claimed);
        out.setFreeSeats(Math.max(0, invitable - pending - claimed));
        out.setExpiredSeats(expired);
        out.setCancelledSeats(cancelled);
        out.setReleasedSeats(released);
        out.setServerNow(now.getTime());
        out.setFinancialOwner("BUYER");
        out.setReadOnly(Boolean.FALSE);
        out.setInvitations(views);
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public SeatInvitationVO status(long callerMemberId, Long invitationId) {
        requireMember(callerMemberId);
        if (invitationId == null || invitationId <= 0L) throw badRequest("参数有误");
        RegistrationSeatInvitation row = invitations.selectById(invitationId);
        if (row == null) throw notFound("邀请不存在");
        boolean owner = row.getOwnerMemberId() != null && row.getOwnerMemberId() == callerMemberId;
        boolean invitee = row.getInviteeMemberId() != null && callerMemberId == row.getInviteeMemberId();
        if (!owner && !invitee) throw denied("无权读取该邀请");
        Date now = new Date();
        return view(row, now, false, originalPaid(row.getRegistrationId()));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public SeatInvitationVO claim(long callerMemberId, SeatInvitationClaimRequest request) {
        requireMember(callerMemberId);
        if (request == null || request.getToken() == null || request.getToken().trim().isEmpty()) {
            throw badRequest("请提供席位邀请码");
        }
        String token = request.getToken().trim();
        String requestId = requireRequestId(request.getRequestId());
        Date now = new Date();
        String tokenHash = SeatInvitationTokenCodec.sha256Hex(token);
        String fingerprint = fingerprint(RegistrationSeatInvitationRequestRecord.OP_CLAIM,
                callerMemberId, 0L, tokenHash);
        RegistrationSeatInvitationRequestRecord prior = replay(requestId, callerMemberId,
                RegistrationSeatInvitationRequestRecord.OP_CLAIM, fingerprint);
        if (prior != null) {
            RegistrationSeatInvitation priorRow = invitations.selectById(prior.getInvitationId());
            if (priorRow == null) throw conflict("请求已记录但邀请不存在，请重新发起");
            SeatInvitationVO view = view(priorRow, now, false,
                    originalPaid(priorRow.getRegistrationId()));
            view.setReplayed(Boolean.TRUE);
            return view;
        }

        SeatInvitationTokenCodec.Result verified = SeatInvitationTokenCodec.verify(
                token, now.getTime() / 1000L, secret);
        if (!verified.valid) {
            throw conflict("TOKEN_EXPIRED".equals(verified.reason) ? "席位邀请码已过期" : "席位邀请码无效");
        }
        RegistrationSeatInvitation seed = invitations.selectByTokenHash(tokenHash);
        if (seed == null || seed.getRegistrationId() == null
                || !SeatInvitationTokenCodec.constEquals(seed.getTokenHash(), tokenHash)) {
            throw conflict("席位邀请码无效");
        }

        LockedScope scope = lockScope(seed.getRegistrationId());
        now = new Date();
        RegistrationSeatInvitationRequestRecord claimReplay = replayLocked(requestId, callerMemberId,
                RegistrationSeatInvitationRequestRecord.OP_CLAIM, fingerprint);
        if (claimReplay != null) return replayed(claimReplay, now);
        RegistrationSeatInvitation row = invitations.selectByIdForUpdate(seed.getId());
        if (row == null || !Objects.equals(row.getRegistrationId(), scope.current.getId())
                || !SeatInvitationTokenCodec.constEquals(row.getTokenHash(), tokenHash)
                || !SeatInvitationTokenCodec.constEquals(row.getTokenNonce(), verified.publicId)) {
            throw conflict("席位邀请码无效");
        }
        long tokenExpiresAt = verified.expiresAtEpochSec;
        if (row.getExpiresAt() == null
                || row.getExpiresAt().getTime() / 1000L != tokenExpiresAt) {
            throw conflict("席位邀请码与原邀请不匹配");
        }
        if (!Objects.equals(row.getOwnerMemberId(), scope.current.getMemberId())
                || row.getSeatNo() == null || row.getSeatNo() < 2
                || row.getSeatNo() > seatCount(scope.current)
                || row.getGeneration() == null || row.getGeneration() < 1
                || !Objects.equals(row.getOwnerType(), scope.current.getOwnerType())
                || !Objects.equals(row.getOwnerId(), scope.current.getOwnerId())) {
            throw conflict("席位邀请与原订单范围不一致");
        }
        if (!Objects.equals(row.getTopicId(), scope.topicId())) {
            throw conflict("席位邀请与原订单主题不一致");
        }
        if (row.claimed() && callerMemberId == row.getInviteeMemberId()) {
            return view(row, now, false, paid(scope.current));
        }
        if (!row.pending()) throw expiredMessage(row.getStatus());
        if (row.getExpiresAt() == null || !row.getExpiresAt().after(now)) {
            invitations.transition(row.getId(), RegistrationSeatInvitation.STATUS_PENDING,
                    RegistrationSeatInvitation.STATUS_EXPIRED, now);
            throw conflict("席位邀请码已过期");
        }
        if (callerMemberId == scope.current.getMemberId()) {
            throw conflict("购票人已占用原单第1席，不能再领取自己的邀请");
        }
        requireOriginalShareable(scope, now);
        if (invitations.countClaimedByRecipient(scope.current.getId(), callerMemberId) > 0) {
            throw conflict("该会员已领取本单另一个内含席位");
        }

        if (invitations.claim(row.getId(), callerMemberId, now) != 1) {
            throw conflict("邀请状态已变化，请刷新后重试");
        }
        row.setStatus(RegistrationSeatInvitation.STATUS_CLAIMED);
        row.setInviteeMemberId(callerMemberId);
        row.setClaimedAt(now);
        row.setUpdateTime(now);
        recordRequest(requestId, callerMemberId, RegistrationSeatInvitationRequestRecord.OP_CLAIM,
                scope.current.getId(), row.getId(), fingerprint, row.getStatus(), now);
        return view(row, now, false, true);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public SeatInvitationVO cancel(long callerMemberId, SeatInvitationActionRequest request) {
        return ownerTransition(callerMemberId, request,
                RegistrationSeatInvitationRequestRecord.OP_CANCEL,
                RegistrationSeatInvitation.STATUS_PENDING, RegistrationSeatInvitation.STATUS_CANCELLED,
                "只有待领取的邀请可以撤销；已领取的席位请使用释放");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public SeatInvitationVO release(long callerMemberId, SeatInvitationActionRequest request) {
        requireMember(callerMemberId);
        if (request == null || request.getInvitationId() == null || request.getInvitationId() <= 0L) {
            throw badRequest("参数有误");
        }
        Long invitationId = request.getInvitationId();
        String requestId = requireRequestId(request.getRequestId());
        String fingerprint = fingerprint(RegistrationSeatInvitationRequestRecord.OP_RELEASE,
                callerMemberId, invitationId, "");
        Date now = new Date();
        RegistrationSeatInvitationRequestRecord prior = replay(requestId, callerMemberId,
                RegistrationSeatInvitationRequestRecord.OP_RELEASE, fingerprint);
        if (prior != null) {
            RegistrationSeatInvitation priorRow = invitations.selectById(prior.getInvitationId());
            if (priorRow == null) throw conflict("请求已记录但邀请不存在，请重新发起");
            SeatInvitationVO view = view(priorRow, now, false, originalPaid(priorRow.getRegistrationId()));
            view.setReplayed(Boolean.TRUE);
            return view;
        }
        RegistrationSeatInvitation seed = invitations.selectById(invitationId);
        if (seed == null) throw notFound("邀请不存在");
        LockedScope scope = lockScope(seed.getRegistrationId());
        RegistrationSeatInvitationRequestRecord releaseReplay = replayLocked(requestId, callerMemberId,
                RegistrationSeatInvitationRequestRecord.OP_RELEASE, fingerprint);
        if (releaseReplay != null) return replayed(releaseReplay, now);
        requireBuyer(scope.current, callerMemberId);
        RegistrationSeatInvitation row = invitations.selectByIdForUpdate(invitationId);
        if (row == null) throw notFound("邀请不存在");
        if (!Objects.equals(row.getRegistrationId(), scope.current.getId())
                || !Objects.equals(row.getOwnerMemberId(), scope.current.getMemberId())
                || !Objects.equals(row.getOwnerType(), scope.current.getOwnerType())
                || !Objects.equals(row.getOwnerId(), scope.current.getOwnerId())
                || !Objects.equals(row.getTopicId(), scope.topicId())
                || row.getSeatNo() == null || row.getSeatNo() < 2
                || row.getSeatNo() > seatCount(scope.current)) {
            throw conflict("席位邀请与原订单范围不一致，暂不能释放");
        }
        if (Integer.valueOf(RegistrationSeatInvitation.STATUS_RELEASED).equals(row.getStatus())) {
            return view(row, now, false, paid(scope.current));
        }
        if (row.pending()) throw conflict("待领取的邀请请使用撤销，不需要释放");
        if (!row.claimed()) throw conflict(expiredMessage(row.getStatus()).getMessage());
        Long invitee = row.getInviteeMemberId();
        if (invitee == null || invitee <= 0L) throw conflict("已领取席位缺少受让人，暂不能释放");
        Long topicId = row.getTopicId();
        if (topicId == null || topicId <= 0L) {
            // 独立活动无主题时无法核对真实游玩状态；失败关闭，不凭空释放活跃玩家。
            throw conflict("无法确认该席位的游玩状态，暂不能释放");
        }
        Long activityId = Long.valueOf(2L).equals(row.getOwnerType()) ? row.getOwnerId() : 0L;
        if (verifiedSeat(row.getRegistrationId(), row.getSeatNo())) {
            throw conflict("该席位已有商家核销事实，不能释放");
        }
        // 原始开局事实永久占用席位；完成/超时也不能再次转给另一位玩家。
        Boolean used = invitations.hasOriginalUseFacts(invitee, topicId, activityId);
        if (!Boolean.FALSE.equals(used)) {
            throw conflict("该席位已使用或无法确认未使用，不能释放");
        }
        if (invitations.transition(row.getId(), RegistrationSeatInvitation.STATUS_CLAIMED,
                RegistrationSeatInvitation.STATUS_RELEASED, now) != 1) {
            throw conflict("邀请状态已变化，请刷新后重试");
        }
        row.setStatus(RegistrationSeatInvitation.STATUS_RELEASED);
        row.setUpdateTime(now);
        recordRequest(requestId, callerMemberId, RegistrationSeatInvitationRequestRecord.OP_RELEASE,
                scope.current.getId(), row.getId(), fingerprint, row.getStatus(), now);
        return view(row, now, false, paid(scope.current));
    }

    private SeatInvitationVO ownerTransition(long callerMemberId, SeatInvitationActionRequest request,
                                             String operation, int fromStatus, int toStatus,
                                             String wrongStateMessage) {
        requireMember(callerMemberId);
        if (request == null || request.getInvitationId() == null || request.getInvitationId() <= 0L) {
            throw badRequest("参数有误");
        }
        Long invitationId = request.getInvitationId();
        String requestId = requireRequestId(request.getRequestId());
        String fingerprint = fingerprint(operation, callerMemberId, invitationId, "");
        Date now = new Date();
        RegistrationSeatInvitationRequestRecord prior = replay(requestId, callerMemberId, operation, fingerprint);
        if (prior != null) {
            RegistrationSeatInvitation priorRow = invitations.selectById(prior.getInvitationId());
            if (priorRow == null) throw conflict("请求已记录但邀请不存在，请重新发起");
            SeatInvitationVO view = view(priorRow, now, false, originalPaid(priorRow.getRegistrationId()));
            view.setReplayed(Boolean.TRUE);
            return view;
        }
        RegistrationSeatInvitation seed = invitations.selectById(invitationId);
        if (seed == null) throw notFound("邀请不存在");
        LockedScope scope = lockScope(seed.getRegistrationId());
        RegistrationSeatInvitationRequestRecord lockedReplay = replayLocked(requestId, callerMemberId, operation, fingerprint);
        if (lockedReplay != null) return replayed(lockedReplay, now);
        requireBuyer(scope.current, callerMemberId);
        RegistrationSeatInvitation row = invitations.selectByIdForUpdate(invitationId);
        if (row == null) throw notFound("邀请不存在");
        if (Integer.valueOf(toStatus).equals(row.getStatus())) {
            return view(row, now, false, paid(scope.current));
        }
        if (!Integer.valueOf(fromStatus).equals(row.getStatus())) throw conflict(wrongStateMessage);
        if (invitations.transition(row.getId(), fromStatus, toStatus, now) != 1) {
            throw conflict("邀请状态已变化，请刷新后重试");
        }
        row.setStatus(toStatus);
        row.setUpdateTime(now);
        recordRequest(requestId, callerMemberId, operation, scope.current.getId(), row.getId(),
                fingerprint, toStatus, now);
        return view(row, now, false, paid(scope.current));
    }

    private RegistrationSeatInvitationRequestRecord replay(String requestId, long callerMemberId,
                                                           String operation, String fingerprint) {
        return validateReplay(requestRecords.selectByRequestId(callerMemberId, requestId),
                operation, fingerprint);
    }

    /** 持原单行锁后再查一次：并发同 requestId 的第二个请求在这里等到已提交的回执并重放。 */
    private RegistrationSeatInvitationRequestRecord replayLocked(String requestId, long callerMemberId,
                                                                 String operation, String fingerprint) {
        return validateReplay(requestRecords.selectByRequestIdForUpdate(callerMemberId, requestId),
                operation, fingerprint);
    }

    private RegistrationSeatInvitationRequestRecord validateReplay(
            RegistrationSeatInvitationRequestRecord prior, String operation, String fingerprint) {
        if (prior == null) return null;
        if (!operation.equals(prior.getOperation()) || !fingerprint.equals(prior.getRequestFingerprint())) {
            throw conflict("同一请求号对应了不同操作或内容，请使用新的请求号");
        }
        return prior;
    }

    private SeatInvitationVO replayed(RegistrationSeatInvitationRequestRecord prior, Date now) {
        RegistrationSeatInvitation row = invitations.selectById(prior.getInvitationId());
        if (row == null) throw conflict("请求已记录但邀请不存在，请重新发起");
        boolean withToken = RegistrationSeatInvitationRequestRecord.OP_INVITE.equals(prior.getOperation());
        SeatInvitationVO view = view(row, now, withToken, originalPaid(row.getRegistrationId()));
        view.setReplayed(Boolean.TRUE);
        return view;
    }

    private void recordRequest(String requestId, long callerMemberId, String operation,
                               Long registrationId, Long invitationId,
                               String fingerprint, int resultStatus, Date now) {
        RegistrationSeatInvitationRequestRecord record = new RegistrationSeatInvitationRequestRecord();
        record.setRequestId(requestId);
        record.setMemberId(callerMemberId);
        record.setOperation(operation);
        record.setRegistrationId(registrationId);
        record.setInvitationId(invitationId);
        record.setRequestFingerprint(fingerprint);
        record.setResultStatus(resultStatus);
        record.setCreateTime(now);
        try {
            if (requestRecords.insert(record) != 1) {
                throw new IllegalStateException("席位邀请请求回执保存失败");
            }
        } catch (DuplicateKeyException concurrent) {
            throw conflict("同一请求号正在处理，请通过状态查询确认结果");
        }
    }

    private LockedScope lockScope(Long registrationId) {
        CmsRegistration seed = registrations.selectCmsRegistrationById(registrationId);
        if (seed == null || seed.getOwnerId() == null) throw denied("报名单不存在或无权操作");
        CmsActivity activity = null;
        CmsTopic topic = null;
        if (Long.valueOf(2L).equals(seed.getOwnerType())) {
            activity = activities.selectCmsActivityByIdForUpdate(seed.getOwnerId());
            if (activity == null) throw conflict("活动不存在或已取消");
        } else if (Long.valueOf(1L).equals(seed.getOwnerType())) {
            topic = topics.selectCmsTopicByIdForUpdate(seed.getOwnerId());
            if (topic == null) throw conflict("主题不存在");
        } else {
            throw conflict("该订单不含主题席位");
        }
        CmsRegistration current = registrations.selectCmsRegistrationByIdForUpdate(registrationId);
        if (current == null || !Objects.equals(seed.getMemberId(), current.getMemberId())
                || !Objects.equals(seed.getOwnerType(), current.getOwnerType())
                || !Objects.equals(seed.getOwnerId(), current.getOwnerId())) {
            throw conflict("原订单归属已变化，请重新核验");
        }
        return new LockedScope(current, activity, topic);
    }

    private void requireBuyer(CmsRegistration current, long callerMemberId) {
        if (current == null || current.getMemberId() == null || current.getMemberId() != callerMemberId) {
            throw denied("席位邀请仅购票人可操作");
        }
    }

    private void requireOriginalShareable(LockedScope scope, Date now) {
        CmsRegistration current = scope.current;
        if (!(Long.valueOf(1L).equals(current.getOwnerType())
                || Long.valueOf(2L).equals(current.getOwnerType())) || current.getOwnerId() == null) {
            throw conflict("该订单不含主题席位");
        }
        if (!paid(current)) throw conflict("订单未完成支付或已退款，不能邀请或领取席位");
        if (Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(current.getPurchaseKind())) {
            throw conflict("探索票不含可邀请的内含席位");
        }
        if (Long.valueOf(1L).equals(current.getOwnerType())
                && current.getExpiresAt() != null && !current.getExpiresAt().after(now)) {
            throw conflict("原通行证已过期，不能邀请或领取席位");
        }
        if (Long.valueOf(2L).equals(current.getOwnerType())) {
            CmsActivity activity = scope.activity;
            if (activity == null || activity.getCancelTime() != null
                    || Integer.valueOf(2).equals(activity.getDelFlag())) {
                throw conflict("活动不存在或已取消");
            }
            if (activity.getEndDate() != null && !activity.getEndDate().after(now)) {
                throw conflict("活动已结束，不能邀请或领取席位");
            }
        }
        if (seatCount(current) <= 1) throw conflict("该订单不含可邀请的多人内含席位");
        if (giftControlled(current)) {
            throw conflict("该已付行受礼品领取状态约束，暂不能作为内含席位邀请");
        }
        if (registrations.hasActiveManualRefundCaseForUpdate(current.getId())) {
            throw conflict("订单正在人工退款处理中，暂不可邀请或领取");
        }
    }

    private boolean giftControlled(CmsRegistration current) {
        if (giftEligibility == null || current.getId() == null) return false;
        List<GiftPlayFact> facts = giftEligibility.playFacts(
                current.getMemberId(), current.getOwnerType(), current.getOwnerId());
        if (facts == null) return false;
        for (GiftPlayFact fact : facts) {
            if (fact != null && current.getId().equals(fact.getRegistrationId())
                    && !fact.claimedBy(current.getMemberId())) {
                return true;
            }
        }
        return false;
    }

    /** 只读既有商户核销事实：已核销席位不能被释放操作改写。 */
    private boolean verifiedSeat(Long registrationId, Integer seatNo) {
        if (seatFacts == null || registrationId == null || seatNo == null) {
            throw conflict("无法确认该席位的核销状态，暂不能释放");
        }
        List<RegistrationSeat> rows = seatFacts.selectByRegistration(registrationId);
        if (rows == null) throw conflict("无法确认该席位的核销状态，暂不能释放");
        for (RegistrationSeat row : rows) {
            if (row != null && seatNo.equals(row.getSeatNo()) && Integer.valueOf(1).equals(row.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private Date invitationExpiry(LockedScope scope, Date now) {
        long hours = ttlHours <= 0L ? 168L : ttlHours;
        Date bound = new Date(now.getTime() + hours * 60L * 60L * 1000L);
        if (scope.current.getExpiresAt() != null && scope.current.getExpiresAt().before(bound)) {
            bound = scope.current.getExpiresAt();
        }
        if (scope.activity != null && scope.activity.getEndDate() != null
                && scope.activity.getEndDate().before(bound)) {
            bound = scope.activity.getEndDate();
        }
        if (!bound.after(now)) throw conflict("原票已过期，不能邀请");
        return bound;
    }

    private int chooseSeat(List<RegistrationSeatInvitation> active, int invitable, int requestedSeat) {
        if (requestedSeat > 0) {
            if (requestedSeat == 1) throw conflict("原单第1席为购票人自用，不能邀请");
            if (requestedSeat > invitable + 1) throw conflict("席位号超出原订单数量");
            for (RegistrationSeatInvitation row : active) {
                if (row != null && Integer.valueOf(requestedSeat).equals(row.getSeatNo())) {
                    throw conflict("该席位已有有效邀请");
                }
            }
            return requestedSeat;
        }
        boolean[] occupied = new boolean[invitable + 2];
        for (RegistrationSeatInvitation row : active) {
            if (row != null && row.getSeatNo() != null && row.getSeatNo() >= 2
                    && row.getSeatNo() <= invitable + 1) {
                occupied[row.getSeatNo()] = true;
            }
        }
        for (int seatNo = 2; seatNo <= invitable + 1; seatNo++) {
            if (!occupied[seatNo]) return seatNo;
        }
        throw conflict("该订单已无可用邀请席位");
    }

    private int seatCount(CmsRegistration registration) {
        Long orderNum = registration == null ? null : registration.getOrderNum();
        if (orderNum == null || orderNum <= 1L) return 1;
        if (Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(registration.getPurchaseKind())) return 1;
        return (int) Math.min(Integer.MAX_VALUE, orderNum.longValue());
    }

    private Long scopeTopicId(CmsRegistration registration) {
        if (registration == null || registration.getOwnerId() == null) return null;
        if (Long.valueOf(1L).equals(registration.getOwnerType())) return registration.getOwnerId();
        if (!Long.valueOf(2L).equals(registration.getOwnerType())) return null;
        CmsActivity activity = activities.selectCmsActivityById(registration.getOwnerId());
        return activity == null ? null : activity.getTopicId();
    }

    private boolean paid(CmsRegistration registration) {
        return registration != null
                && Integer.valueOf(2).equals(registration.getRegistrationStatus())
                && Integer.valueOf(2).equals(registration.getPaymentStatus());
    }

    private boolean originalPaid(Long registrationId) {
        if (registrationId == null || registrationId <= 0L) return false;
        return paid(registrations.selectCmsRegistrationById(registrationId));
    }

    private SeatInvitationVO view(RegistrationSeatInvitation row, Date now, boolean withToken,
                                  boolean originalPaid) {
        SeatInvitationVO view = SeatInvitationVO.of(row, now, originalPaid);
        if (withToken) view.setToken(tokenOf(row));
        return view;
    }

    /** 同一行、同一 nonce 重算同一枚码：幂等重放不需要存明文。 */
    private String tokenOf(RegistrationSeatInvitation row) {
        if (row == null || row.getRegistrationId() == null || row.getSeatNo() == null
                || row.getGeneration() == null || row.getExpiresAt() == null
                || row.getTokenNonce() == null) {
            throw new IllegalStateException("席位邀请事实不完整");
        }
        return SeatInvitationTokenCodec.issue(row.getTokenNonce(),
                row.getExpiresAt().getTime() / 1000L, secret);
    }

    private String fingerprint(String operation, long callerMemberId, long scopeId, String payload) {
        return SeatInvitationTokenCodec.sha256Hex(operation + "|" + callerMemberId + "|"
                + scopeId + "|" + (payload == null ? "" : payload));
    }

    private String requireRequestId(String raw) {
        String value = raw == null ? null : raw.trim();
        if (value == null || value.isEmpty() || value.length() > 64
                || !value.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw badRequest("requestId 不合法");
        }
        return value;
    }

    private void requireMember(long callerMemberId) {
        if (callerMemberId <= 0L) throw denied("请先登录");
    }

    private ServiceException expiredMessage(int status) {
        if (status == RegistrationSeatInvitation.STATUS_EXPIRED) return conflict("席位邀请已过期");
        if (status == RegistrationSeatInvitation.STATUS_CANCELLED) return conflict("席位邀请已被撤销");
        if (status == RegistrationSeatInvitation.STATUS_RELEASED) return conflict("席位已释放，邀请码已失效");
        return conflict("席位邀请已失效");
    }

    private ServiceException badRequest(String message) { return new ServiceException(message, 400); }
    private ServiceException denied(String message) { return new ServiceException(message, 403); }
    private ServiceException notFound(String message) { return new ServiceException(message, 404); }
    private ServiceException conflict(String message) { return new ServiceException(message, 409); }

    private static final class LockedScope {
        private final CmsRegistration current;
        private final CmsActivity activity;
        private final CmsTopic topic;

        private LockedScope(CmsRegistration current, CmsActivity activity, CmsTopic topic) {
            this.current = current;
            this.activity = activity;
            this.topic = topic;
        }

        private Long topicId() {
            if (Long.valueOf(1L).equals(current.getOwnerType())) return current.getOwnerId();
            return activity == null ? null : activity.getTopicId();
        }
    }
}

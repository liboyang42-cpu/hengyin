package com.chengyinhub.business.growthloops;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.growthloops.InviteRewardPolicy.Decision;
import com.chengyinhub.business.growthloops.InviteRewardPolicy.Outcome;
import com.chengyinhub.business.mapper.growthloops.GrowthLoopsMapper;
import com.chengyinhub.business.push.MemberNotice;
import com.chengyinhub.business.push.PushMessage;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPushFanoutService;
import com.chengyinhub.business.service.support.DailyLoopSettings;

/**
 * GRO-13：被邀请人完成第一次可信漫游后，邀请人得到奖励（非现金：邀请徽章；城市币见 {@link InviteCityCoinHook}）。
 *
 * <p>防刷：</p>
 * <ul>
 * <li>每位被邀请人一辈子最多一次（growth_invite_reward 主键）。</li>
 * <li>同一手机号、同一台设备（App 推送 token）只算一次；被邀请人必须绑定手机号。</li>
 * <li>本次漫游必须达标（里程或新格子，沿用 daily-loop 门槛），且定位全部通过位置可信检查。</li>
 * <li>每位邀请人每个自然月最多得奖 {@code growth.invite.monthlyCapPerInviter} 次（默认 10）。</li>
 * </ul>
 * <p>漫游结算提交后另起事务执行；出错只记日志，绝不影响结算。</p>
 */
@Service
public class InviteRewardService
{
    private static final Logger log = LoggerFactory.getLogger(InviteRewardService.class);
    private static final DailyLoopSettings FALLBACK_WALK = new DailyLoopSettings(null);

    private final GrowthLoopsMapper mapper;
    private final GrowthLoopsSettings settings;

    @Autowired(required = false) private DailyLoopSettings walkSettings;
    @Autowired(required = false) private IPlayerProgressionService progression;
    @Autowired(required = false) private IPushFanoutService push;
    @Autowired(required = false) private List<InviteCityCoinHook> cityCoinHooks;
    @Autowired(required = false) private PlatformTransactionManager transactionManager;

    @Autowired
    public InviteRewardService(GrowthLoopsMapper mapper, GrowthLoopsSettings settings)
    {
        this.mapper = mapper;
        this.settings = settings;
    }

    InviteRewardService(GrowthLoopsMapper mapper, GrowthLoopsSettings settings, IPlayerProgressionService progression,
                        IPushFanoutService push, List<InviteCityCoinHook> hooks)
    {
        this(mapper, settings);
        this.progression = progression;
        this.push = push;
        this.cityCoinHooks = hooks;
    }

    /** 漫游结算后调用（ApiRoamController.finish）。sink 收到 {outcome, reason}，可为 null。 */
    public void afterWalkFinished(Long inviteeId, Long sessionId, Integer distanceM, Integer newCells, Consumer<Map<String, Object>> sink)
    {
        if (inviteeId == null || inviteeId <= 0 || sessionId == null) return;
        Runnable work = () -> {
            Decision d = evaluate(inviteeId, sessionId, distanceM, newCells, new Date());
            if (sink != null && d.outcome() != Outcome.NONE) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("outcome", d.outcome().name());
                out.put("reason", d.reason());
                sink.accept(out);
            }
        };
        Runnable guarded = () -> {
            try {
                if (transactionManager == null) { work.run(); return; }
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.executeWithoutResult(status -> work.run());
            } catch (RuntimeException e) {
                log.warn("event=INVITE_REWARD_SKIPPED invitee={} reason={}", inviteeId, e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { guarded.run(); }
            });
        } else {
            guarded.run();
        }
    }

    Decision evaluate(long inviteeId, long sessionId, Integer distanceM, Integer newCells, Date now)
    {
        if (!settings.inviteRewardEnabled()) return Decision.none("DISABLED");
        Map<String, Object> invitee = mapper.selectInvitee(inviteeId);
        if (invitee == null || WinbackService.number(invitee.get("status")) != 1 || WinbackService.number(invitee.get("delFlag")) != 0)
            return Decision.none("NO_MEMBER");
        Long inviterId = InviteRewardPolicy.inviterOf(invitee.get("inviteUserId"), inviteeId);
        if (inviterId == null) return Decision.none("NOT_INVITED");
        if (mapper.selectInviteReward(inviteeId) != null) return Decision.none("ALREADY_DONE");
        if (!(walkSettings != null ? walkSettings : FALLBACK_WALK).walkQualifies(distanceM, newCells))
            return Decision.waitFor("WALK_TOO_SHORT");

        Map<String, Object> trust;
        try { trust = mapper.selectSessionTrust(inviteeId, sessionId); }
        catch (RuntimeException unavailable) { return Decision.waitFor("TRUST_UNAVAILABLE"); }
        long clean = trust == null ? 0 : WinbackService.number(trust.get("clean"));
        long flagged = trust == null ? 0 : WinbackService.number(trust.get("flagged"));
        if (!InviteRewardPolicy.locationTrusted(clean, flagged)) return Decision.waitFor("LOCATION_NOT_TRUSTED");

        String phoneHash = InviteRewardPolicy.phoneHash(invitee.get("phone"));
        if (phoneHash == null) return Decision.waitFor("NO_PHONE");

        Decision decision = limits(inviteeId, inviterId, phoneHash, now);
        List<String> deviceHashes = deviceHashes(inviteeId);
        if (decision.outcome() == Outcome.GRANTED && !deviceHashes.isEmpty() && mapper.countGrantedByDeviceHashes(deviceHashes) > 0)
            decision = Decision.refuse("DUPLICATE_DEVICE");

        if (mapper.insertInviteReward(inviteeId, inviterId, decision.outcome().name(), decision.reason(), sessionId, phoneHash, now) != 1)
            return Decision.none("ALREADY_DONE");
        if (decision.outcome() != Outcome.GRANTED) {
            log.info("event=INVITE_REWARD_REFUSED invitee={} inviter={} reason={}", inviteeId, inviterId, decision.reason());
            return decision;
        }
        for (String h : deviceHashes) mapper.insertInviteDevice(h, inviteeId, now);
        int total = mapper.countGrantedForInviter(inviterId);
        List<PlayerBadge> badges = progression == null ? List.of() : progression.onInviteRewarded(inviterId, total);
        // ===== CITY-COIN HOOK: points-governance plugs the non-cashable city coin in here. =====
        if (cityCoinHooks != null) for (InviteCityCoinHook hook : cityCoinHooks) hook.grantInviteReward(inviterId, inviteeId);
        if (push != null) push.notifyMemberAfterCommit(inviterId, inviterNotice(badges));
        log.info("event=INVITE_REWARD_GRANTED invitee={} inviter={} total={} badges={}", inviteeId, inviterId, total, badges.size());
        return decision;
    }

    private Decision limits(long inviteeId, long inviterId, String phoneHash, Date now)
    {
        Map<String, Object> inviter = mapper.selectInvitee(inviterId);
        if (inviter == null || WinbackService.number(inviter.get("status")) != 1 || WinbackService.number(inviter.get("delFlag")) != 0)
            return Decision.refuse("INVITER_INACTIVE");
        if (phoneHash.equals(InviteRewardPolicy.phoneHash(inviter.get("phone")))) return Decision.refuse("SAME_PHONE");
        if (mapper.countGrantedByPhoneHash(phoneHash) > 0) return Decision.refuse("DUPLICATE_PHONE");
        LocalDate month = now.toInstant().atZone(settings.zone()).toLocalDate().withDayOfMonth(1);
        Date monthStart = Date.from(month.atStartOfDay(settings.zone()).toInstant());
        if (mapper.countGrantedForInviterSince(inviterId, monthStart) >= settings.inviteMonthlyCap())
            return Decision.refuse("INVITER_MONTHLY_CAP");
        return Decision.grant();
    }

    private List<String> deviceHashes(long inviteeId)
    {
        List<String> out = new ArrayList<>();
        List<String> tokens = mapper.selectInviteeDeviceTokens(inviteeId);
        if (tokens != null) for (String t : tokens) {
            String h = InviteRewardPolicy.deviceHash(t);
            if (h != null && !out.contains(h)) out.add(h);
        }
        return out;
    }

    static MemberNotice inviterNotice(List<PlayerBadge> badges)
    {
        String badge = badges == null || badges.isEmpty() ? null : badges.get(0).getBadgeName();
        return new MemberNotice(PushMessage.KIND_GROWTH, "invite", "/subpackageP3/pages/badge-wall/index/index",
                "你邀请的朋友走完了第一段路", badge == null ? "去看看你的邀请记录。" : "你得到了「" + badge + "」徽章。",
                "Your friend finished their first walk", badge == null ? "See your invites." : "You earned the " + badge + " badge.",
                3L * 24L * 3600L);
    }

    /** 邀请人看自己的邀请奖励（App / 小程序都可读）。 */
    public Map<String, Object> summary(long inviterId)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", settings.inviteRewardEnabled());
        out.put("rewardKind", "BADGE");
        out.put("cityCoin", cityCoinHooks != null && !cityCoinHooks.isEmpty());
        out.put("granted", mapper.countGrantedForInviter(inviterId));
        out.put("monthlyCap", settings.inviteMonthlyCap());
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> raw = mapper.selectInviteRewardsForInviter(inviterId, 50);
        if (raw != null) for (Map<String, Object> r : raw) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nickname", r.get("nickname"));
            row.put("status", r.get("status"));
            row.put("at", r.get("createTime") instanceof Date d ? d.getTime() : null);
            rows.add(row);
        }
        out.put("invites", rows);
        return out;
    }
}

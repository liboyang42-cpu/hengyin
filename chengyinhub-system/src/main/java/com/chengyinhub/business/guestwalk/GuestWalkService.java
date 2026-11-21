package com.chengyinhub.business.guestwalk;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.guestwalk.mapper.GuestWalkMapper;
import com.chengyinhub.business.locationtrust.LocationFix;
import com.chengyinhub.business.locationtrust.LocationTrustConfig;
import com.chengyinhub.business.locationtrust.LocationTrustDecision;
import com.chengyinhub.business.locationtrust.LocationTrustKind;
import com.chengyinhub.business.locationtrust.LocationTrustMessages;
import com.chengyinhub.business.locationtrust.LocationTrustService;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.impl.RoamFogCellService;
import com.chengyinhub.business.service.impl.RoamLocationConsentService;
import com.chengyinhub.business.service.support.RoamTrackSimplifier;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R4 guest-walk (GRO-12): saves the one walk a player made on this install before signing in.
 *
 * <p>Credits the revealed cells (as a finished roam session, so the walk shows in the player's history and on the
 * kept fog map) and the first-walk reward ({@link GuestWalkPolicy#FIRST_WALK_XP_CAP}). Nothing else: no POI, check-in,
 * shop, medal, presence or daily-loop credit. One walk per install and one per member; a retry returns the first
 * result. The roam location consent must be on record, exactly as for {@code /api/roam/reveal}.
 */
@Service
public class GuestWalkService {

    private static final Logger log = LoggerFactory.getLogger(GuestWalkService.class);

    public static final String CODE_DISABLED = "GUEST_WALK_DISABLED";
    public static final String CODE_INSTALL_USED = "GUEST_WALK_INSTALL_USED";
    public static final String CODE_MEMBER_USED = "GUEST_WALK_MEMBER_USED";
    public static final String CODE_CONSENT_REQUIRED = "CONSENT_REQUIRED";
    public static final String CODE_ACCOUNT_CHANGED = "ACCOUNT_CHANGED";
    static final String XP_REASON = "城市漫游(登录前第一次)";

    /** Refusal with a stable code; the message is plain player wording. */
    public static final class Refused extends RuntimeException {
        private final String code;
        private final boolean retryable;

        public Refused(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    @Autowired
    private GuestWalkMapper mapper;
    @Autowired
    private RoamMapper roamMapper;
    @Autowired
    private RoamLocationConsentService locationConsent;
    @Autowired
    private IGrowthService growthService;
    @Autowired(required = false)
    private RoamFogCellService fogCellService;
    @Autowired(required = false)
    private LocationTrustService locationTrust;

    /** Kill switch; off answers an honest refusal and stores nothing. */
    @Value("${roam.guestWalk.enabled:true}")
    private boolean enabled = true;

    public GuestWalkService() {
    }

    /** For tests. */
    GuestWalkService(GuestWalkMapper mapper, RoamMapper roamMapper, RoamLocationConsentService locationConsent,
                     IGrowthService growthService, RoamFogCellService fogCellService, LocationTrustService locationTrust,
                     boolean enabled) {
        this.mapper = mapper;
        this.roamMapper = roamMapper;
        this.locationConsent = locationConsent;
        this.growthService = growthService;
        this.fogCellService = fogCellService;
        this.locationTrust = locationTrust;
        this.enabled = enabled;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Long memberId, String body, long nowMs) {
        if (!enabled) throw new Refused(CODE_DISABLED, "登录前漫游暂时不能保存", false);
        GuestWalkPolicy.Upload upload = GuestWalkPolicy.parse(body);
        if (roamMapper.lockClaimOwner(memberId) == null) throw new Refused(CODE_ACCOUNT_CHANGED, "账号状态已变化", false);
        Map<String, Object> byInstall = mapper.selectByInstall(upload.installId());
        if (byInstall != null && !byInstall.isEmpty()) {
            if (memberId.equals(longValue(byInstall.get("memberId"))) && upload.walkId().equals(byInstall.get("walkId"))) {
                return replay(byInstall);
            }
            throw new Refused(CODE_INSTALL_USED, "这台手机上登录前的那次漫游已经保存过了", false);
        }
        Map<String, Object> byMember = mapper.selectByMember(memberId);
        if (byMember != null && !byMember.isEmpty()) {
            throw new Refused(CODE_MEMBER_USED, "这个账号已经保存过一次登录前的漫游了", false);
        }
        try {
            locationConsent.requireCurrentAgreement(memberId);
        } catch (ServiceException e) {
            throw new Refused(CODE_CONSENT_REQUIRED, e.getMessage(), true);
        }
        LocationTrustConfig cfg = locationTrust == null ? LocationTrustConfig.defaults() : locationTrust.config();
        GuestWalkPolicy.Outcome outcome = GuestWalkPolicy.evaluate(upload, nowMs, cfg);
        Date started = new Date(upload.startedAt());
        Date finished = new Date(upload.finishedAt());
        Long sessionId = null;
        int newly = 0;
        int xp = 0;
        if (!outcome.withheld() && !outcome.credited().isEmpty()) {
            RoamSession session = new RoamSession();
            session.setMemberId(memberId);
            session.setStartedAt(started);
            session.setStatus(0);
            session.setCreateTime(new Date(nowMs));
            roamMapper.insertSession(session);
            sessionId = session.getId();
            // Cells carry the walk's own time, so the rolling reveal cap of the player's next live walk is not spent.
            newly = Math.max(0, roamMapper.batchInsertTile(memberId, sessionId, outcome.credited(), finished));
            if (fogCellService != null) fogCellService.record(memberId, outcome.credited(), finished);
            xp = Math.min(GuestWalkPolicy.FIRST_WALK_XP_CAP, newly);
            session.setFinishedAt(finished);
            session.setDistanceM(outcome.distanceM());
            session.setTilesRevealed(newly);
            session.setPoisFound(0);
            session.setXpAwarded(xp);
            if (roamMapper.updateSessionFinish(session) != 1) throw new IllegalStateException("guest walk session not finished");
            String track = RoamTrackSimplifier.simplify(GuestWalkPolicy.trackJson(outcome.acceptedTrack()));
            if (track != null) roamMapper.saveSessionTrack(sessionId, memberId, track);
            if (xp > 0) growthService.addExp(memberId, xp, XP_REASON);
        }
        recordTrail(memberId, sessionId, upload, outcome);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId == null ? null : String.valueOf(sessionId));
        data.put("cellsSent", upload.cells().size());
        data.put("acceptedCells", outcome.credited().size());
        data.put("newlyRevealed", newly);
        data.put("uncreditedCells", upload.cells().size() - outcome.credited().size());
        data.put("xp", xp);
        data.put("distanceM", sessionId == null ? 0 : outcome.distanceM());
        data.put("finishedAt", upload.finishedAt());
        if (outcome.withheld()) {
            data.put("uncredited", true);
            data.put("trustCode", outcome.code());
            data.put("trustMessage", withheldMessage(outcome));
        }
        String resultJson = JSON.toJSONString(data);
        if (sessionId != null
                && roamMapper.saveFinishResult(sessionId, memberId, JSON.toJSONString(sessionResult(upload, newly, xp))) != 1) {
            throw new IllegalStateException("guest walk result not saved");
        }
        mapper.insert(upload.installId(), upload.walkId(), memberId, sessionId, started, finished,
                upload.cells().size(), newly, xp, outcome.withheld() ? 1 : 0,
                outcome.code().isEmpty() ? null : outcome.code(), resultJson);
        log.info("guest walk saved member={} session={} sent={} accepted={} newly={} xp={} withheld={} code={}",
                memberId, sessionId, upload.cells().size(), outcome.credited().size(), newly, xp, outcome.withheld(),
                outcome.code());
        data.put("duplicate", false);
        return data;
    }

    private void recordTrail(Long memberId, Long sessionId, GuestWalkPolicy.Upload upload, GuestWalkPolicy.Outcome outcome) {
        if (locationTrust == null || upload.fixes().isEmpty()) return;
        LocationFix last = upload.fixes().get(upload.fixes().size() - 1);
        String detail = "cells=" + upload.cells().size() + " credited=" + outcome.credited().size()
                + " farFromTrack=" + outcome.droppedFarFromTrack() + " overBudget=" + outcome.droppedOverBudget()
                + " droppedFixes=" + outcome.droppedFixes() + " fixes=" + upload.fixes().size();
        try {
            locationTrust.recordTrack(memberId, sessionId, LocationTrustKind.GUEST_WALK, upload.walkId(), last,
                    outcome.reasons(), outcome.withheld() ? LocationTrustDecision.ACCEPT_NO_REWARD : LocationTrustDecision.ACCEPT,
                    detail);
        } catch (RuntimeException e) {
            log.error("guest walk trail not written member={} reason={}", memberId, e.getClass().getSimpleName());
        }
    }

    /** Same shape as the {@code /api/roam/finish} receipt, so history and recovery read the walk like any other. */
    private static Map<String, Object> sessionResult(GuestWalkPolicy.Upload upload, int newly, int xp) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("finishedAt", upload.finishedAt());
        result.put("tileXp", xp);
        result.put("poiXp", 0);
        result.put("totalXp", xp);
        result.put("newTiles", newly);
        result.put("newPois", 0);
        result.put("medal", null);
        result.put("sessionShops", 0);
        result.put("shopMedal", null);
        result.put("guestWalk", true);
        return result;
    }

    static String withheldMessage(GuestWalkPolicy.Outcome outcome) {
        if (outcome.reasons().contains(GuestWalkPolicy.OUT_OF_WINDOW)) return "这次漫游超过 24 小时了,不能再保存";
        if (outcome.reasons().contains(GuestWalkPolicy.NO_TRACK)) return "这次漫游没有可用的定位记录,不计入地图";
        return LocationTrustMessages.withheld(outcome.reasons());
    }

    private static Map<String, Object> replay(Map<String, Object> row) {
        Object json = row.get("resultJson");
        Map<String, Object> data = new LinkedHashMap<>();
        if (json instanceof String text && !text.isBlank()) {
            Map<String, Object> parsed = JSON.parseObject(text);
            if (parsed != null) data.putAll(parsed);
        }
        data.put("duplicate", true);
        return data;
    }

    private static Long longValue(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}

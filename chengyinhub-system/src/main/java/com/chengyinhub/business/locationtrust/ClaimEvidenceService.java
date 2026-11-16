package com.chengyinhub.business.locationtrust;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.mapper.LocationTrustMapper;
import com.chengyinhub.common.exception.ServiceException;

/**
 * SEC-10: verifies CITY claim photo evidence and hands the claim flow a one-use evidence id.
 *
 * <p>The claim flow itself belongs to the coordinator. It should: (1) have the app call
 * {@code POST /api/location-trust/claim-evidence} right after the camera shot, (2) pass the returned
 * {@code evidenceId} with the claim, and (3) call {@link #consumeForClaim} inside the claim transaction. Only
 * ACCEPT evidence can be consumed; REVIEW evidence stays on record for a person to decide.
 *
 * <p>No photo bytes are stored, only SHA-256 and a 64-bit perceptual hash.
 */
@Service
public class ClaimEvidenceService {

    private static final Logger REVIEW_LOG = LoggerFactory.getLogger("com.chengyinhub.locationtrust.review");
    public static final int MAX_PHOTO_BYTES = 15 * 1024 * 1024;
    static final int HISTORY_FIXES = 50;

    @Autowired
    private LocationTrustService trustService;
    @Autowired(required = false)
    private LocationTrustMapper mapper;

    public ClaimEvidenceService() {
    }

    /** For tests. */
    public ClaimEvidenceService(LocationTrustService trustService, LocationTrustMapper mapper) {
        this.trustService = trustService;
        this.mapper = mapper;
    }

    public record Submission(Long memberId, String placeRef, Double placeLat, Double placeLng, String captureSource,
                             byte[] photo, Long photoTakenAtMs, Double photoLat, Double photoLng, LocationFix fix) {
    }

    public Map<String, Object> submit(Submission s) {
        if (s.memberId() == null || s.memberId() <= 0L) throw new ServiceException("请先登录");
        if (s.placeLat() == null || s.placeLng() == null || !LocationFix.of(s.placeLat(), s.placeLng()).validCoordinates())
            throw new ServiceException("地点坐标无效");
        if (s.photo() == null || s.photo().length == 0) throw new ServiceException("请用相机拍一张现场照片");
        if (s.photo().length > MAX_PHOTO_BYTES) throw new ServiceException("照片太大，请重新拍摄");
        if (mapper == null) throw new ServiceException("现场取证暂不可用，请稍后再试");
        String placeRef = s.placeRef() == null || s.placeRef().isBlank() ? null
                : s.placeRef().trim().length() > 64 ? s.placeRef().trim().substring(0, 64) : s.placeRef().trim();
        LocationTrustConfig cfg = trustService.config();
        long now = System.currentTimeMillis();
        String sha = sha256(s.photo());
        Date dupSince = new Date(now - cfg.claimDuplicateWindowDays() * 86_400_000L);

        // Same member resending the same shot for the same place = retry: answer with the earlier evidence.
        List<ClaimEvidenceRow> sameBytes = mapper.selectEvidenceBySha(sha, dupSince);
        boolean exactDuplicate = false;
        Long duplicateOf = null;
        for (ClaimEvidenceRow prior : sameBytes) {
            if (s.memberId().equals(prior.getMemberId()) && Objects.equals(placeRef, prior.getPlaceRef())
                    && prior.getConsumedAt() == null) {
                return response(prior);
            }
            exactDuplicate = true;
            if (duplicateOf == null) duplicateOf = prior.getId();
        }
        Long phash = PerceptualHash.dHash(s.photo());
        Integer nearest = null;
        if (phash != null) {
            Map<String, Object> near = mapper.selectNearestPhash(phash, dupSince, s.memberId(), placeRef);
            if (near != null && near.get("distance") instanceof Number n) {
                nearest = n.intValue();
                if (duplicateOf == null && nearest <= cfg.claimPhashReviewHamming() && near.get("id") instanceof Number id)
                    duplicateOf = id.longValue();
            }
        }
        LocationTrustService.Assessment fixAssessment = null;
        if (s.fix() != null) {
            fixAssessment = trustService.assess(s.memberId(), null, LocationTrustKind.CLAIM_EVIDENCE, placeRef, s.fix());
        }
        List<double[]> history = trustService.cleanFixesSince(s.memberId(),
                new Date(now - cfg.claimFixWindowMinutes() * 60_000L), HISTORY_FIXES);
        ClaimEvidencePolicy.Result result = ClaimEvidencePolicy.evaluate(new ClaimEvidencePolicy.Input(
                s.captureSource() == null ? null : s.captureSource().trim().toUpperCase(java.util.Locale.ROOT),
                phash, s.photoTakenAtMs(), s.photoLat(), s.photoLng(), s.placeLat(), s.placeLng(),
                fixAssessment == null ? null : fixAssessment.verdict().decision(), s.fix(), history,
                exactDuplicate, nearest), now, cfg);

        ClaimEvidenceRow row = new ClaimEvidenceRow();
        row.setMemberId(s.memberId());
        row.setPlaceRef(placeRef);
        row.setPlaceLat(scale6(s.placeLat()));
        row.setPlaceLng(scale6(s.placeLng()));
        row.setCaptureSource(s.captureSource() == null ? "UNKNOWN"
                : s.captureSource().trim().length() > 16 ? s.captureSource().trim().substring(0, 16) : s.captureSource().trim());
        row.setPhotoSha256(sha);
        row.setPhotoPhash(phash);
        row.setPhotoTakenAt(s.photoTakenAtMs() == null ? null : new Date(s.photoTakenAtMs()));
        row.setPhotoLat(s.photoLat() == null || s.photoLng() == null ? null : scale6(s.photoLat()));
        row.setPhotoLng(s.photoLat() == null || s.photoLng() == null ? null : scale6(s.photoLng()));
        row.setFixId(fixAssessment == null ? null : fixAssessment.fixId());
        row.setVerdict(result.verdict().name());
        row.setReasons(result.reasons().isEmpty() ? null : truncate(result.reasonText(), 255));
        row.setDuplicateOf(duplicateOf);
        row.setCreateTime(new Date(now));
        mapper.insertEvidence(row);
        if (result.verdict() != ClaimEvidencePolicy.Verdict.ACCEPT) {
            REVIEW_LOG.warn("event=CLAIM_EVIDENCE member={} place={} evidence={} verdict={} reasons={} duplicateOf={}",
                    s.memberId(), placeRef, row.getId(), result.verdict(), result.reasonText(), duplicateOf);
        }
        return response(row);
    }

    /**
     * For the claim flow: atomically marks ACCEPT evidence owned by the member as used by {@code claimRef}.
     * Returns false when the evidence is missing, not ACCEPT, for another place, or already used.
     */
    public boolean consumeForClaim(Long memberId, Long evidenceId, String placeRef, String claimRef) {
        if (mapper == null || memberId == null || evidenceId == null) return false;
        ClaimEvidenceRow row = mapper.selectEvidenceOwned(evidenceId, memberId);
        if (row == null || !"ACCEPT".equals(row.getVerdict()) || row.getConsumedAt() != null) return false;
        if (placeRef != null && !placeRef.equals(row.getPlaceRef())) return false;
        return mapper.consumeEvidence(evidenceId, memberId, truncate(claimRef, 64), new Date()) == 1;
    }

    /**
     * City Monopoly claim: like {@link #consumeForClaim} but REVIEW evidence may also be used once; the claim then goes
     * to human review. Never consumes REJECT evidence.
     */
    public boolean consumeForReviewedClaim(Long memberId, Long evidenceId, String claimRef) {
        if (mapper == null || memberId == null || evidenceId == null) return false;
        ClaimEvidenceRow row = mapper.selectEvidenceOwned(evidenceId, memberId);
        if (row == null || row.getConsumedAt() != null || !("ACCEPT".equals(row.getVerdict()) || "REVIEW".equals(row.getVerdict()))) return false;
        return mapper.consumeEvidenceForReview(evidenceId, memberId, truncate(claimRef, 64), new Date()) == 1;
    }

    /** Server-side photo facts of one evidence row owned by the member (no consumption). Null when not found. */
    public ClaimEvidenceRow readRow(Long memberId, Long evidenceId) {
        if (mapper == null || memberId == null || evidenceId == null) return null;
        return mapper.selectEvidenceOwned(evidenceId, memberId);
    }

    /** Read-only verdict for the claim flow (no consumption). Null when not found for this member. */
    public Map<String, Object> read(Long memberId, Long evidenceId) {
        if (mapper == null || memberId == null || evidenceId == null) return null;
        ClaimEvidenceRow row = mapper.selectEvidenceOwned(evidenceId, memberId);
        return row == null ? null : response(row);
    }

    static Map<String, Object> response(ClaimEvidenceRow row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evidenceId", row.getId() == null ? null : String.valueOf(row.getId()));
        data.put("verdict", row.getVerdict());
        data.put("reasons", row.getReasons() == null ? List.of() : List.of(row.getReasons().split(",")));
        data.put("placeRef", row.getPlaceRef());
        data.put("used", row.getConsumedAt() != null);
        return data;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal scale6(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}

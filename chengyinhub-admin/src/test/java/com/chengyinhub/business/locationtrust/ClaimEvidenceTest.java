package com.chengyinhub.business.locationtrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.chengyinhub.business.mapper.LocationTrustMapper;
import com.chengyinhub.common.exception.ServiceException;

/** P8 SEC-10: CITY claim photo evidence. */
class ClaimEvidenceTest {
    private static final LocationTrustConfig CFG = LocationTrustConfig.defaults();
    private static final long NOW = 1_800_000_000_000L;
    private static final double PLACE_LAT = 31.2304, PLACE_LNG = 121.4737;

    private static ClaimEvidencePolicy.Input input(String source, Long phash, Long takenAt, Double pLat, Double pLng,
                                                   LocationTrustDecision fixDecision, LocationFix fix,
                                                   List<double[]> history, boolean dup, Integer hamming) {
        return new ClaimEvidencePolicy.Input(source, phash, takenAt, pLat, pLng, PLACE_LAT, PLACE_LNG, fixDecision, fix,
                history, dup, hamming);
    }

    private static ClaimEvidencePolicy.Result eval(ClaimEvidencePolicy.Input in) {
        return ClaimEvidencePolicy.evaluate(in, NOW, CFG);
    }

    @Test
    void honest_freshCameraShotOnSite_isAccepted() {
        ClaimEvidencePolicy.Result r = eval(input("CAMERA", 42L, NOW - 30_000L, PLACE_LAT + 0.0005, PLACE_LNG,
                LocationTrustDecision.ACCEPT, LocationFix.of(PLACE_LAT + 0.0003, PLACE_LNG), List.of(), false, 20));
        assertEquals(ClaimEvidencePolicy.Verdict.ACCEPT, r.verdict());
        assertTrue(r.reasons().isEmpty());
    }

    @Test
    void honest_noFixSentButServerHistoryNearby_isAccepted() {
        ClaimEvidencePolicy.Result r = eval(input("CAMERA", 42L, NOW - 30_000L, null, null, null, null,
                List.<double[]>of(new double[] {PLACE_LAT, PLACE_LNG + 0.001}), false, null));
        assertEquals(ClaimEvidencePolicy.Verdict.ACCEPT, r.verdict());
    }

    @Test
    void libraryPick_isRejected() {
        ClaimEvidencePolicy.Result r = eval(input("LIBRARY", 42L, NOW, null, null,
                LocationTrustDecision.ACCEPT, LocationFix.of(PLACE_LAT, PLACE_LNG), List.of(), false, null));
        assertEquals(ClaimEvidencePolicy.Verdict.REJECT, r.verdict());
        assertTrue(r.reasons().contains(ClaimEvidencePolicy.NOT_CAMERA));
    }

    @Test
    void oldPhoto_orPhotoTakenElsewhere_isRejected() {
        assertTrue(eval(input("CAMERA", 1L, NOW - 20 * 60_000L, null, null, LocationTrustDecision.ACCEPT,
                LocationFix.of(PLACE_LAT, PLACE_LNG), List.of(), false, null)).reasons().contains(ClaimEvidencePolicy.PHOTO_TOO_OLD));
        assertTrue(eval(input("CAMERA", 1L, NOW, PLACE_LAT + 0.01, PLACE_LNG, LocationTrustDecision.ACCEPT,
                LocationFix.of(PLACE_LAT, PLACE_LNG), List.of(), false, null)).reasons().contains(ClaimEvidencePolicy.PHOTO_FAR_FROM_PLACE));
    }

    @Test
    void playerFixFarFromPlace_isRejected_simulatedFixGoesToReview() {
        assertEquals(ClaimEvidencePolicy.Verdict.REJECT, eval(input("CAMERA", 1L, NOW, null, null,
                LocationTrustDecision.ACCEPT, LocationFix.of(PLACE_LAT + 0.02, PLACE_LNG), List.of(), false, null)).verdict());
        ClaimEvidencePolicy.Result sim = eval(input("CAMERA", 1L, NOW, null, null,
                LocationTrustDecision.ACCEPT_NO_REWARD, LocationFix.of(PLACE_LAT, PLACE_LNG), List.of(), false, null));
        assertEquals(ClaimEvidencePolicy.Verdict.REVIEW, sim.verdict());
        assertEquals(List.of(ClaimEvidencePolicy.FIX_WITHHELD), sim.reasons());
    }

    @Test
    void missingMetadataOrFix_goesToReviewNotReject() {
        ClaimEvidencePolicy.Result r = eval(input("CAMERA", null, null, null, null, null, null, List.of(), false, null));
        assertEquals(ClaimEvidencePolicy.Verdict.REVIEW, r.verdict());
        assertTrue(r.reasons().containsAll(List.of(ClaimEvidencePolicy.PHOTO_UNREADABLE,
                ClaimEvidencePolicy.PHOTO_TIME_MISSING, ClaimEvidencePolicy.NO_FIX)));
    }

    @Test
    void duplicates_exactAndPerceptual() {
        LocationFix here = LocationFix.of(PLACE_LAT, PLACE_LNG);
        assertTrue(eval(input("CAMERA", 1L, NOW, null, null, LocationTrustDecision.ACCEPT, here, List.of(), true, 0))
                .reasons().contains(ClaimEvidencePolicy.DUPLICATE_PHOTO));
        assertEquals(ClaimEvidencePolicy.Verdict.REJECT,
                eval(input("CAMERA", 1L, NOW, null, null, LocationTrustDecision.ACCEPT, here, List.of(), false, 2)).verdict());
        assertEquals(ClaimEvidencePolicy.Verdict.REVIEW,
                eval(input("CAMERA", 1L, NOW, null, null, LocationTrustDecision.ACCEPT, here, List.of(), false, 7)).verdict());
        assertEquals(ClaimEvidencePolicy.Verdict.ACCEPT,
                eval(input("CAMERA", 1L, NOW, null, null, LocationTrustDecision.ACCEPT, here, List.of(), false, 12)).verdict());
    }

    // ---- perceptual hash ----

    private static BufferedImage scene(int w, int h, boolean variant) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int x = 0; x < w; x++) {
            int v = variant ? 255 - x * 255 / w : x * 255 / w;
            g.setColor(new Color(v, (v * 3) % 256, 255 - v));
            g.drawLine(x, 0, x, h);
        }
        g.setColor(variant ? Color.WHITE : Color.BLACK);
        g.fillOval(w / 3, h / 4, w / 3, h / 2);
        g.dispose();
        return img;
    }

    private static byte[] jpeg(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void dHash_resizedCopyIsClose_differentSceneIsFar() throws Exception {
        Long a = PerceptualHash.dHash(jpeg(scene(1200, 900, false)));
        Long resized = PerceptualHash.dHash(jpeg(scene(600, 450, false)));
        Long other = PerceptualHash.dHash(jpeg(scene(1200, 900, true)));
        assertNotNull(a);
        assertTrue(PerceptualHash.hamming(a, resized) <= 3, "resized copy");
        assertTrue(PerceptualHash.hamming(a, other) > 16, "different scene");
        assertNull(PerceptualHash.dHash("not an image".getBytes()));
        assertNull(PerceptualHash.dHash(new byte[0]));
    }

    // ---- service ----

    private static LocationTrustService trust(LocationTrustMapper mapper) {
        return new LocationTrustService(null, mapper, null);
    }

    @Test
    void submit_storesHashesOnly_andRetryOfSameShotReturnsSameEvidence() throws Exception {
        LocationTrustMapper mapper = mock(LocationTrustMapper.class);
        when(mapper.insertEvidence(any())).thenAnswer(inv -> {
            ((ClaimEvidenceRow) inv.getArgument(0)).setId(900L);
            return 1;
        });
        ClaimEvidenceService svc = new ClaimEvidenceService(trust(mapper), mapper);
        byte[] photo = jpeg(scene(800, 600, false));
        long now = System.currentTimeMillis();
        Map<String, Object> out = svc.submit(new ClaimEvidenceService.Submission(7L, "cell:1", PLACE_LAT, PLACE_LNG,
                "camera", photo, now - 10_000L, null, null, LocationFix.of(PLACE_LAT, PLACE_LNG)));
        assertEquals("900", out.get("evidenceId"));
        assertEquals("ACCEPT", out.get("verdict"));
        ArgumentCaptor<ClaimEvidenceRow> row = ArgumentCaptor.forClass(ClaimEvidenceRow.class);
        verify(mapper).insertEvidence(row.capture());
        assertEquals(64, row.getValue().getPhotoSha256().length());
        assertNotNull(row.getValue().getPhotoPhash());

        ClaimEvidenceRow prior = row.getValue();
        when(mapper.selectEvidenceBySha(eq(prior.getPhotoSha256()), any())).thenReturn(List.of(prior));
        Map<String, Object> again = svc.submit(new ClaimEvidenceService.Submission(7L, "cell:1", PLACE_LAT, PLACE_LNG,
                "CAMERA", photo, now - 10_000L, null, null, LocationFix.of(PLACE_LAT, PLACE_LNG)));
        assertEquals("900", again.get("evidenceId"));
        verify(mapper, org.mockito.Mockito.times(1)).insertEvidence(any());
    }

    @Test
    void submit_sameShotByAnotherPlayer_isRejectedAsDuplicate() throws Exception {
        LocationTrustMapper mapper = mock(LocationTrustMapper.class);
        ClaimEvidenceRow prior = new ClaimEvidenceRow();
        prior.setId(5L);
        prior.setMemberId(8L);
        prior.setPlaceRef("cell:1");
        when(mapper.selectEvidenceBySha(anyString(), any())).thenReturn(List.of(prior));
        ClaimEvidenceService svc = new ClaimEvidenceService(trust(mapper), mapper);
        Map<String, Object> out = svc.submit(new ClaimEvidenceService.Submission(7L, "cell:1", PLACE_LAT, PLACE_LNG,
                "CAMERA", jpeg(scene(400, 300, false)), System.currentTimeMillis(), null, null,
                LocationFix.of(PLACE_LAT, PLACE_LNG)));
        assertEquals("REJECT", out.get("verdict"));
        assertTrue(((List<?>) out.get("reasons")).contains(ClaimEvidencePolicy.DUPLICATE_PHOTO));
    }

    @Test
    void submit_withoutPhoto_isRefusedBeforeAnyWrite() {
        LocationTrustMapper mapper = mock(LocationTrustMapper.class);
        ClaimEvidenceService svc = new ClaimEvidenceService(trust(mapper), mapper);
        try {
            svc.submit(new ClaimEvidenceService.Submission(7L, null, PLACE_LAT, PLACE_LNG, "CAMERA", null, null, null, null, null));
        } catch (ServiceException expected) {
            verify(mapper, never()).insertEvidence(any());
            return;
        }
        throw new AssertionError("expected refusal");
    }

    @Test
    void consumeForClaim_onlyOnceAndOnlyAcceptForTheSamePlace() {
        LocationTrustMapper mapper = mock(LocationTrustMapper.class);
        ClaimEvidenceRow ok = new ClaimEvidenceRow();
        ok.setId(1L);
        ok.setMemberId(7L);
        ok.setPlaceRef("cell:1");
        ok.setVerdict("ACCEPT");
        when(mapper.selectEvidenceOwned(1L, 7L)).thenReturn(ok);
        when(mapper.consumeEvidence(eq(1L), eq(7L), anyString(), any(Date.class))).thenReturn(1);
        ClaimEvidenceService svc = new ClaimEvidenceService(trust(mapper), mapper);
        assertFalse(svc.consumeForClaim(7L, 1L, "cell:2", "claim:1"));
        assertTrue(svc.consumeForClaim(7L, 1L, "cell:1", "claim:1"));
        ok.setConsumedAt(new Date());
        assertFalse(svc.consumeForClaim(7L, 1L, "cell:1", "claim:2"));
        ClaimEvidenceRow review = new ClaimEvidenceRow();
        review.setVerdict("REVIEW");
        review.setMemberId(7L);
        when(mapper.selectEvidenceOwned(2L, 7L)).thenReturn(review);
        assertFalse(svc.consumeForClaim(7L, 2L, null, "claim:3"));
        verify(mapper, never()).consumeEvidence(eq(2L), anyLong(), anyString(), any());
    }
}

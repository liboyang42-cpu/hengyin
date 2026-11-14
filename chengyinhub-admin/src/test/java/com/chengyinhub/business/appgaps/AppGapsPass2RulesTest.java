package com.chengyinhub.business.appgaps;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Storage-free rules of the gaps A pass 2 player routes. */
class AppGapsPass2RulesTest {

    @Test
    void coarseSharingKeepsOnlyTheGridCellAndExactKeepsTheFix() {
        Map<String, Object> coarse = Map.of("mode", "COARSE", "mode_choice_id", "c");
        var modes = JSON.parseArray("[{\"id\":\"c\",\"mode\":\"COARSE\",\"latitudeStep\":0.01,\"longitudeStep\":0.05}]");
        BigDecimal[] cell = LocationShareService.snap(coarse, modes, 31.23456, -121.47891);
        assertEquals(0, new BigDecimal("31.23").compareTo(cell[0]));
        assertEquals(0, new BigDecimal("-121.50").compareTo(cell[1]), "floor, also west of Greenwich");
        BigDecimal[] exact = LocationShareService.snap(Map.of("mode", "EXACT"), modes, 31.23456, 121.47891);
        assertEquals(0, new BigDecimal("31.23456").compareTo(exact[0]));
    }

    @Test
    void voucherVersionDependsOnlyOnUnitStatusAndVersion() {
        List<Map<String, Object>> a = List.of(unit(2, "AVAILABLE", 0), unit(1, "RESERVED", 3));
        List<Map<String, Object>> sameReordered = List.of(unit(1, "RESERVED", 3), unit(2, "AVAILABLE", 0));
        assertEquals(BundleExtrasService.voucherVersion(a), BundleExtrasService.voucherVersion(sameReordered));
        assertEquals(16, BundleExtrasService.voucherVersion(a).length());
        assertNotEquals(BundleExtrasService.voucherVersion(a), BundleExtrasService.voucherVersion(List.of(unit(2, "AVAILABLE", 0), unit(1, "REDEEMED", 4))));
    }

    private static Map<String, Object> unit(long id, String status, int version) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", id);
        u.put("status", status);
        u.put("version", version);
        return u;
    }

    @Test
    void activationIsDerivedFromPaymentRegistrationAndValidity() {
        long now = 1_000_000L;
        assertEquals("ACTIVE", BundleExtrasService.activation(order(2, 2, now + 1), now));
        assertEquals("EXPIRED", BundleExtrasService.activation(order(2, 2, now), now));
        assertEquals("PENDING_PAYMENT", BundleExtrasService.activation(order(0, 1, now + 1), now));
        assertEquals("REFUNDED", BundleExtrasService.activation(order(4, 3, now + 1), now));
    }

    private static Map<String, Object> order(int payment, int registration, long validTo) {
        return Map.of("payment_status", payment, "registration_status", registration, "valid_to", new java.util.Date(validTo));
    }

    @Test
    void attributeAuthorDigestMatchesTheAppFormula() {
        byte[] body = "{\"keys\":[]}".getBytes(StandardCharsets.UTF_8);
        String expected = AppGapSupport.sha256(("POST\napi/topic/attribute-author\n{\"keys\":[]}").getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, AttributeAuthorService.payloadSha("POST", body));
        assertThrows(AppGapException.class, () -> new AttributeAuthorService.Original("other", "x", "y", "1"));
    }

    @Test
    void coverUploadIsDecodedAndReencodedOrRefused() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);
        byte[] jpeg = TopicCoverService.sanitize(png.toByteArray());
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
        assertEquals("COVER_IMAGE_INVALID", assertThrows(AppGapException.class, () -> TopicCoverService.sanitize("<svg/>".getBytes())).getErrorCode());
    }

    @Test
    void canonicalJsonIgnoresKeyOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 1);
        a.put("a", Map.of("y", 2, "x", 1));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", Map.of("x", 1, "y", 2));
        b.put("b", 1);
        assertEquals(AppGapSupport.canonical(a), AppGapSupport.canonical(b));
    }
}

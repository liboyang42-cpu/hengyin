package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 资损接线守卫：候补 hold 必须贯穿 fingerprint -> claim -> bind，且库存只扣一次。 */
class ClubWaitlistRegistrationWiringTest {
    @Test
    void claimedOfferCapsPaymentExpiryAndSkipsOrdinaryInventoryDeduction() throws Exception {
        String source = read("../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java");
        assertTrue(source.contains("clubWaitlistOrderBridge.claimForOrder(memberId, request, new Date())"));
        assertTrue(source.contains("clubWaitlistOrderBridge.bindRegistration(waitlistClaim, registration.getId())"));
        assertTrue(source.contains("waitlistClaim.getExpiresAt().before(expireTime)"));
        assertTrue(source.contains("if (waitlistClaim == null) {\n            long remainInv"),
                "普通库存扣减必须被 waitlistClaim == null 包住");
    }

    @Test
    void idempotencyFingerprintContainsOfferIdAndToken() throws Exception {
        String source = read("../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/RegistrationOrderServiceImpl.java");
        assertTrue(source.contains("append(canonical, request.getWaitlistOfferId())"));
        assertTrue(source.contains("append(canonical, normalize(request.getWaitlistOfferToken()))"));
    }

    private String read(String relative) throws Exception {
        Path path = Paths.get(relative);
        if (!Files.exists(path)) path = Paths.get("chengyinhub-admin").resolve(relative).normalize();
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}

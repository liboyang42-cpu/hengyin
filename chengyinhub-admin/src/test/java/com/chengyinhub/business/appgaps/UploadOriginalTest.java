package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UploadOriginalTest {
    static final String RID = "AAAAAAAA-2222-4222-8222-222222222222";

    @SuppressWarnings("unchecked")
    @Test void uploadedReceiptIsFlatAndDigestIsServerSide() {
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        byte[] bytes = {1, 2, 3};
        String sha = AppOriginalRequestLedger.sha256Hex(bytes);
        when(ledger.run(eq(UploadOriginal.PROTOCOL), eq(9L), eq(RID), eq(""), eq(sha), any(Supplier.class))).thenAnswer(a ->
                new AppOriginalRequestLedger.Fact("COMMITTED", RID, "", sha, ((Supplier<Map<String, Object>>) a.getArgument(5)).get(), null, null, false));
        AjaxResult legacy = AjaxResult.success(); legacy.put("fileName", "upload/0123456789abcdef0123456789abcdef.png");
        legacy.put("url", "https://oss.example/upload/0123456789abcdef0123456789abcdef.png"); legacy.put("reviewState", "APPROVED");
        AjaxResult r = new UploadOriginal(ledger).upload(9, RID, bytes, () -> legacy);
        assertEquals("UPLOADED", r.get("uploadState")); assertEquals(RID, r.get("uploadRequestId")); assertEquals(sha, r.get("sourceSHA256"));
        assertEquals("original-upload-request-v1", r.get("uploadProtocol")); assertEquals(legacy.get("url"), r.get("url"));
        assertEquals(400, new UploadOriginal(ledger).upload(9, RID.toLowerCase(), bytes, () -> legacy).get("code"), "App 发的是大写 uuidString");
    }

    @Test void heldUploadIsPendingWithoutUrlAndUnknownIsNotFound() {
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        String sha = "c".repeat(64);
        when(ledger.read(UploadOriginal.PROTOCOL, 9L, RID, "", sha)).thenReturn(new AppOriginalRequestLedger.Fact("COMMITTED", RID, "", sha,
                Map.of("fileName", "upload/x.png", "reviewState", "HOLD"), null, null, true));
        AjaxResult r = new UploadOriginal(ledger).receipt(9, RID, sha);
        assertEquals("PENDING", r.get("uploadState")); assertTrue(r.containsKey("url")); assertNull(r.get("url"));
        when(ledger.read(UploadOriginal.PROTOCOL, 9L, RID, "", sha)).thenReturn(new AppOriginalRequestLedger.Fact("NOT_FOUND", RID, "", sha, null, null, null, false));
        assertEquals("NOT_FOUND", new UploadOriginal(ledger).receipt(9, RID, sha).get("uploadState"));
    }
}

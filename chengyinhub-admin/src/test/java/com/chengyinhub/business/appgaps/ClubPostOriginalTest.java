package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClubPostOriginalTest {
    static final String OP = "AAAAAAAA-1111-4111-8111-111111111111";

    static Map<String, Object> identity(String kind, int post, int version) {
        Map<String, Object> m = new HashMap<>();
        m.put("receiptNamespace", "club-post-v1"); m.put("operationId", OP); m.put("requestId", "club-post:abc.1");
        m.put("operationKind", kind); m.put("clubId", 7); m.put("postId", post); m.put("commentId", 0); m.put("version", version);
        return m;
    }

    @Test void identityShapeFollowsAppRoute() {
        Map<String, Object> create = identity("create", 0, 0); create.put("content", "hi"); create.put("images", "");
        assertNotNull(ClubPostOriginal.identity("create", create));
        assertNull(ClubPostOriginal.identity("update", create), "create 只走 /create");
        Map<String, Object> bad = identity("create", 3, 0); bad.put("content", "hi"); bad.put("images", "");
        assertNull(ClubPostOriginal.identity("create", bad), "create 的 postId 必须为 0");
        Map<String, Object> lower = identity("edit", 3, 1); lower.put("operationId", OP.toLowerCase());
        assertNull(ClubPostOriginal.identity("receipt", lower), "operationId 为大写 UUID 原文");
        Map<String, Object> comment = identity("comment", 3, 2); comment.put("content", "nice");
        assertNotNull(ClubPostOriginal.identity("comment-create", comment));
        comment.put("images", "");
        assertNull(ClubPostOriginal.identity("comment-create", comment), "评论不带 images");
    }

    @SuppressWarnings("unchecked")
    @Test void acknowledgedCarriesExactIdentityAndResult() {
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        when(ledger.run(eq("club-post-v1"), eq(9L), eq("club-post:abc.1"), anyString(), anyString(), any(Supplier.class))).thenAnswer(a ->
                new AppOriginalRequestLedger.Fact("COMMITTED", "club-post:abc.1", a.getArgument(3), a.getArgument(4),
                        ((Supplier<Map<String, Object>>) a.getArgument(5)).get(), null, null, false));
        Map<String, Object> body = identity("create", 0, 0); body.put("content", "hi"); body.put("images", "a;b");
        ClubPostOriginal.Writers writers = mock(ClubPostOriginal.Writers.class);
        when(writers.create(7L, "hi", "a;b")).thenReturn(AjaxResult.success("已发布", Map.of("id", 41L, "version", 0)));
        Map<String, Object> d = (Map<String, Object>) new ClubPostOriginal(ledger).write(9, "create", body, writers).get("data");
        Set<String> keys = new HashSet<>(ClubPostOriginal.IDENTITY); keys.addAll(Set.of("status", "sourceSHA256", "resultPostId", "resultVersion"));
        assertEquals(keys, d.keySet());
        assertEquals("ACKNOWLEDGED", d.get("status")); assertEquals(41L, d.get("resultPostId")); assertEquals(0L, d.get("resultVersion"));
        assertEquals(OP, d.get("operationId")); assertEquals(0, d.get("commentId"));
    }

    @Test void unknownReceiptIsPendingAndRejectedHasPositiveCode() {
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        when(ledger.readById("club-post-v1", 9L, "club-post:abc.1")).thenReturn(new AppOriginalRequestLedger.Fact("NOT_FOUND", "club-post:abc.1", "", null, null, null, null, false));
        Map<?, ?> d = (Map<?, ?>) new ClubPostOriginal(ledger).receipt(9, "receipt", identity("edit", 3, 1)).get("data");
        assertEquals("PENDING", d.get("status")); assertEquals(9, d.size());
        var id = ClubPostOriginal.identity("receipt", identity("edit", 3, 1));
        when(ledger.readById("club-post-v1", 9L, "club-post:abc.1")).thenReturn(new AppOriginalRequestLedger.Fact("REJECTED", "club-post:abc.1", id.scope(), "f".repeat(64), null, 409, "版本已变化", true));
        d = (Map<?, ?>) new ClubPostOriginal(ledger).receipt(9, "receipt", identity("edit", 3, 1)).get("data");
        assertEquals("REJECTED", d.get("status")); assertEquals(409, d.get("rejectedCode"));
        when(ledger.readById("club-post-v1", 9L, "club-post:abc.1")).thenReturn(new AppOriginalRequestLedger.Fact("COMMITTED", "club-post:abc.1", "other", "f".repeat(64), Map.of(), null, null, true));
        assertEquals(409, new ClubPostOriginal(ledger).receipt(9, "receipt", identity("edit", 3, 1)).get("code"));
    }
}

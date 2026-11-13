package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.AppOriginalRequestMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CouponCommandReceiptsTest {
    static final String RID = "aaaaaaaa-1111-4111-8111-111111111111";

    /** 向量由独立实现（与 App CouponCommandCanonical.hash 同一字节布局）算出。 */
    @Test void canonicalHashMatchesAppBinaryLayout() {
        assertEquals("0215823720eed2648e0c13b81478e290c5030aaed1ea62ae86cb83ec47938f8f",
                CouponCommandReceipts.publishHash(7, 9, "周末饮品券", null, 1, 100, 1767196800000L, 1767283199000L));
        assertEquals("2e1352d89651a4a0176ad9df0df46195d7bbfae49b43aea1dfbb94ed60034d42", CouponCommandReceipts.stopHash(7, 9, 30));
        assertNotEquals(CouponCommandReceipts.publishHash(7, 9, "周末饮品券", "", 1, 100, 1767196800000L, 1767283199000L),
                CouponCommandReceipts.publishHash(7, 9, "周末饮品券", null, 1, 100, 1767196800000L, 1767283199000L), "空串与缺省不同");
    }

    /** 内存台账：insert ignore + select 回放，足以覆盖一次执行与重放。 */
    static AppOriginalRequestMapper memoryLedger() {
        AppOriginalRequestMapper m = mock(AppOriginalRequestMapper.class);
        Map<String, Map<String, Object>> rows = new HashMap<>();
        when(m.insertIgnore(any())).thenAnswer(a -> {
            Map<String, Object> r = a.getArgument(0);
            String key = r.get("protocol") + "|" + r.get("actor") + "|" + r.get("requestId");
            if (rows.containsKey(key)) return 0;
            r.put("id", (long) rows.size() + 1);
            Map<String, Object> row = new HashMap<>();
            row.put("id", r.get("id")); row.put("requestId", r.get("requestId")); row.put("scopeKey", r.get("scope"));
            row.put("payloadSha256", r.get("sha")); row.put("state", r.get("state")); rows.put(key, row);
            return 1;
        });
        when(m.finish(anyLong(), anyString(), any(), any(), any())).thenAnswer(a -> {
            for (Map<String, Object> row : rows.values()) if (row.get("id").equals(a.getArgument(0))) {
                row.put("state", a.getArgument(1)); row.put("resultJson", a.getArgument(2));
            }
            return 1;
        });
        when(m.select(anyString(), anyLong(), anyString())).thenAnswer(a -> rows.get(a.getArgument(0) + "|" + a.getArgument(1) + "|" + a.getArgument(2)));
        return m;
    }

    @SuppressWarnings("unchecked")
    @Test void publishExecutesOnceAndReceiptReadsBackSameEnvelope() {
        CouponCommandReceipts c = new CouponCommandReceipts(new AppOriginalRequestLedger(memoryLedger(), new AppOriginalRequestLedgerTest.NoTx()));
        String hash = CouponCommandReceipts.publishHash(7, 9, "券", null, 1, 10, 1L, 2L);
        AtomicInteger writes = new AtomicInteger();
        AjaxResult first = c.execute(41, RID, "PUBLISH", 7, 9, hash, () -> { writes.incrementAndGet(); return new CouponCommandReceipts.Outcome(true, "PUBLISHED", 501L, 1, "发布成功"); });
        AjaxResult again = c.execute(41, RID, "PUBLISH", 7, 9, hash, () -> { writes.incrementAndGet(); return null; });
        assertEquals(1, writes.get(), "同 requestId 只执行一次");
        assertEquals(200, first.get("code")); assertEquals("发布成功", first.get("msg"));
        Map<String, Object> data = (Map<String, Object>) first.get("data");
        assertEquals(501L, data.get("id"));
        Map<String, Object> receipt = (Map<String, Object>) data.get("commandReceipt");
        assertEquals("SUCCEEDED", receipt.get("outcome")); assertEquals("PUBLISHED", receipt.get("reasonCode"));
        assertEquals(1, receipt.get("couponStatusAtExecution")); assertEquals(hash, receipt.get("payloadHash"));
        assertEquals("MERCHANT", receipt.get("scope")); assertEquals(41L, ((Number) receipt.get("actorMemberId")).longValue());
        assertTrue(((String) receipt.get("completedAt")).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));
        assertEquals(AppOriginalRequestLedger.write(receipt),
                AppOriginalRequestLedger.write((Map<String, Object>) ((Map<?, ?>) again.get("data")).get("commandReceipt")), "重放回同一回执");

        AjaxResult read = c.read(41, 7, RID);
        assertEquals(200, read.get("code"));
        assertEquals(501L, ((Map<?, ?>) read.get("data")).get("id"));
        assertFalse(((Map<?, ?>) c.read(41, 8, RID).get("data")).containsKey("commandReceipt"), "别的商家读不到");
        assertFalse(((Map<?, ?>) c.read(42, 7, RID).get("data")).containsKey("commandReceipt"), "别的操作者读不到");
        assertEquals(409, c.execute(41, RID, "PUBLISH", 7, 9, "f".repeat(64), () -> null).get("code"), "同 ID 不同摘要冲突");
    }

    @SuppressWarnings("unchecked")
    @Test void stopRejectionIsTerminalReceiptWithStatusAndNoId() {
        CouponCommandReceipts c = new CouponCommandReceipts(new AppOriginalRequestLedger(memoryLedger(), new AppOriginalRequestLedgerTest.NoTx()));
        String hash = CouponCommandReceipts.stopHash(7, 9, 30);
        AjaxResult r = c.execute(41, RID, "STOP", 7, 9, hash,
                () -> CouponCommandReceipts.Outcome.rejected("COUPON_ALREADY_STOPPED", 30L, 4, "该优惠券已停发,无需重复操作"));
        assertEquals(500, r.get("code"));
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertFalse(data.containsKey("id"));
        Map<String, Object> receipt = (Map<String, Object>) data.get("commandReceipt");
        assertEquals("REJECTED", receipt.get("outcome")); assertEquals(4, receipt.get("couponStatusAtExecution"));
        assertEquals(30L, ((Number) receipt.get("couponId")).longValue());
        AjaxResult read = c.read(41, 7, RID);
        assertEquals(500, read.get("code")); assertEquals("该优惠券已停发,无需重复操作", read.get("msg"));
    }

    @Test void unknownRequestReadsAsEmptyAndMalformedIdIs400() {
        CouponCommandReceipts c = new CouponCommandReceipts(new AppOriginalRequestLedger(memoryLedger(), new AppOriginalRequestLedgerTest.NoTx()));
        AjaxResult r = c.read(41, 7, RID);
        assertEquals(200, r.get("code")); assertTrue(((Map<?, ?>) r.get("data")).isEmpty());
        assertEquals(400, c.read(41, 7, "AAAAAAAA-1111-4111-8111-111111111111").get("code"), "只接受小写 UUID");
    }
}

package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F11 参考结算 SQL:每条读写都带自己的归属;退款只作废未核销行;结清/异议是 CAS。 */
class FeReferenceSettlementSqlTest {
    private String sql(String id, Map<String, Object> p) throws Exception {
        Configuration cfg = new Configuration();
        String resource = "mapper/business/FeReferenceSettlementMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        return cfg.getMappedStatement("com.chengyinhub.business.mapper.FeReferenceSettlementMapper." + id)
                .getBoundSql(p).getSql().replaceAll("\\s+", " ").toLowerCase();
    }

    @Test void merchantReadIsScopedToTheShopAndHidesVoidByDefault() throws Exception {
        Map<String, Object> p = new HashMap<>(); p.put("merchantId", 7L);
        String all = sql("selectForMerchant", p);
        assertTrue(all.contains("where r.merchant_id = ?"));
        assertTrue(all.contains("r.status != 'void'"));
        p.put("status", "OPEN");
        assertTrue(sql("selectForMerchant", p).contains("and r.status = ?"));
    }

    @Test void payerReadAndSettleLockFollowTheSessionPayeeRule() throws Exception {
        Map<String, Object> p = new HashMap<>(); p.put("memberId", 9L); p.put("activityId", 3L);
        for (String q : new String[]{sql("selectForPayer", p), sql("selectOwnedByPayerForUpdate", withIds(p))}) {
            assertTrue(q.contains("r.payer_kind = 'member' and r.payer_id = ?"));
            assertTrue(q.contains("pb.member_id is not null and pb.member_id = ?"), "冻结的个人收款人优先");
            assertTrue(q.contains("pb.member_id is null and pc.member_id = ?"), "否则俱乐部当前主理人");
        }
        assertTrue(sql("selectOwnedByPayerForUpdate", withIds(p)).endsWith("for update"));
        assertTrue(sql("selectForPayer", p).contains("and r.activity_id = ?"));
    }

    @Test void writesAreCasAndRefundKeepsRedeemedRowsOpen() throws Exception {
        Map<String, Object> p = new HashMap<>(); p.put("id", 1L); p.put("memberId", 9L); p.put("merchantId", 7L);
        assertTrue(sql("markSettledOffline", p).contains("status in ('open', 'disputed')"));
        String dispute = sql("markDisputed", p);
        assertTrue(dispute.contains("merchant_id = ?"));
        assertTrue(dispute.contains("supplier_kind = 'merchant'"));
        p.put("registrationId", 5L);
        String refund = sql("voidUnredeemedByRegistration", p);
        assertTrue(refund.contains("f.status = 'open'"));
        assertTrue(refund.contains("not exists (select 1 from redemption d where d.id = f.redemption_id and d.status = 'active')"));
        p.put("redemptionId", 6L);
        assertFalse(sql("voidByRedemption", p).contains("merchant_id"));
        assertTrue(sql("voidByRedemption", p).contains("where redemption_id = ?"));
    }

    private static Map<String, Object> withIds(Map<String, Object> p) {
        Map<String, Object> copy = new HashMap<>(p); copy.put("ids", List.of(1L, 2L)); return copy;
    }
}

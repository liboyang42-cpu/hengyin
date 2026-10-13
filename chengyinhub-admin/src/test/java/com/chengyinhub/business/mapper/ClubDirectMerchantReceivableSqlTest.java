package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubDirectMerchantReceivable;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ClubDirectMerchantReceivableSqlTest {
    private String sql(String id, Map<String, Object> p) throws Exception {
        Configuration cfg = new Configuration();
        String resource = "mapper/business/ClubDirectMerchantReceivableMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        return cfg.getMappedStatement("com.chengyinhub.business.mapper.ClubDirectMerchantReceivableMapper." + id)
                .getBoundSql(p).getSql().replaceAll("\\s+", " ").toLowerCase();
    }

    @Test void clubAndMerchantReadQueriesCarryTheirOwnScope() throws Exception {
        Map<String, Object> p = new HashMap<>(); p.put("topicId", 31L); p.put("payerClubId", 61L);
        assertTrue(sql("selectScoped", p).contains("payer_club_id = ?"));
        p.remove("payerClubId"); p.put("merchantMemberId", 51L);
        assertTrue(sql("selectScoped", p).contains("merchant_member_id = ?"));
    }

    @Test void allFactWritesCarryFrozenOwnerAndCasStatus() throws Exception {
        Map<String, Object> p = new HashMap<>(); p.put("row", new ClubDirectMerchantReceivable());
        for (String statement : new String[]{"reverse", "recordSettled", "recordRefunded"}) {
            String query = sql(statement, p);
            assertTrue(query.contains("topic_id = ?")); assertTrue(query.contains("payer_club_id = ?"));
            assertTrue(query.contains("merchant_member_id = ?"));
            assertFalse(query.matches(".*set.*per_head_fee_snapshot\\s*=.*"));
        }
        assertTrue(sql("recordSettled", p).contains("status = 'open'"));
        assertTrue(sql("recordRefunded", p).contains("status = 'refund_due'"));
    }

    @Test void wholeLedgerDoesNotDropOwnerOrPermitUnscopedRead() throws Exception {
        Map<String, Object> p = new HashMap<>(); p.put("payerClubId", 61L);
        String club = sql("selectScoped", p);
        assertTrue(club.contains("where payer_club_id = ?"));
        assertFalse(club.contains("topic_id = ?"));
        p.clear(); p.put("merchantMemberId", 51L);
        assertTrue(sql("selectScoped", p).contains("where merchant_member_id = ?"));
        p.clear(); assertTrue(sql("selectScoped", p).contains("where 1 = 0"));
    }

    @Test void reversedPaidReceiptKeepsPaymentFactAndBecomesRefundDue() throws Exception {
        Map<String, Object> p = new HashMap<>(); p.put("row", new ClubDirectMerchantReceivable());
        String reverse = sql("reverse", p);
        assertTrue(reverse.contains("when 'settled' then 'refund_due' else 'void' end"));
        assertFalse(reverse.contains("pay_voucher_no =")); assertFalse(reverse.contains("settled_at ="));
    }
}

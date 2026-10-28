package com.chengyinhub.business.it;

import com.chengyinhub.business.mapper.CoopSettlementManualCaseReadMapper;
import com.chengyinhub.business.service.impl.CoopSettlementManualCaseReadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only production XML/service candidate; source only until root's consolidated approved MySQL run. */
class CoopSettlementManualCaseReadMySqlIT {
    final Wp04OriginalRefundMySqlIT schema=new Wp04OriginalRefundMySqlIT();
    CoopSettlementManualCaseReadService reader;
    @BeforeEach void cases() throws Exception {
        assertEquals("1",System.getenv("WP04_MANUAL_CASE_READ_DDL_APPROVED"));
        schema.isolatedOriginalSchema(); // Existing explicit all-connection/GitHub-hosted/UUID owner gates.
        P0AcceptanceSchema.migrations(schema.source,"migration_20261024_coop_negative_margin_manual.sql");
        reader=new CoopSettlementManualCaseReadService(P0AcceptanceSchema.sql(schema.source,"CoopSettlementManualCaseReadMapper").getMapper(CoopSettlementManualCaseReadMapper.class));
        schema.db.update("INSERT INTO ums_member(id,balance) VALUES(7,0)");
        schema.db.update("INSERT INTO cms_topic(id,name,product_type,member_id,is_settlement) VALUES(11,'原主题',1,7,0),(22,'其他主题',1,7,0)");
        schema.db.update("INSERT INTO coop_settlement_manual_case(id,case_key,topic_id,reason,negative_amount,source_snapshot,state) VALUES(1,?,11,'NEGATIVE_ORGANIZER_MARGIN',-2,?,'MANUAL_REQUIRED'),(2,?,22,'NEGATIVE_ORGANIZER_MARGIN',-3,'{}','MANUAL_REQUIRED')",
                "a".repeat(64),"{\"version\":\"CLASSIC_ORIGINAL_LARGEST_REMAINDER_V2\",\"payerRegistrationId\":31,\"originalCash\":10.01,\"paymentFactHash\":\"private-hash\",\"transactionId\":\"private-tx\"}","b".repeat(64));
    }
    @AfterEach void cleanupOnlyOwnedSchema(){schema.dropOnlyThisSuccessfullyCreatedDatabase();}
    @Test void actualListFiltersByOriginalTopicAndDoesNotMutateManualState() {
        var records=reader.list(11L,"MANUAL_REQUIRED");assertEquals(1,records.size());assertEquals(11L,records.get(0).getTopicId());assertEquals("原主题",records.get(0).getTopicName());
        assertEquals(2,schema.db.queryForObject("SELECT COUNT(*) FROM coop_settlement_manual_case WHERE state='MANUAL_REQUIRED'",Integer.class));
    }
    @Test void actualDetailPreservesBusinessSourceButExcludesRawPaymentIdentifiers() throws Exception {
        var detail=reader.detail(1L);assertEquals(31L,detail.getSource().getPayerRegistrationId());assertEquals("NEGATIVE_ORGANIZER_MARGIN",detail.getReason());
        var json=new ObjectMapper().writeValueAsString(detail);assertFalse(json.contains("private-hash"));assertFalse(json.contains("private-tx"));assertFalse(json.contains("sourceSnapshot"));
        assertEquals(1,schema.db.queryForObject("SELECT COUNT(*) FROM coop_settlement_manual_case WHERE id=1 AND source_snapshot LIKE '%private-hash%'",Integer.class));
    }
    @Test void missingTopicStillLeavesTheOriginalManualIdentityVisibleAndUnknownCaseStaysMissing() {
        schema.db.update("DELETE FROM cms_topic WHERE id=11");
        var detail=reader.detail(1L);assertEquals(11L,detail.getTopicId());assertNull(detail.getTopicName());assertEquals("MANUAL_REQUIRED",detail.getState());assertNull(reader.detail(99L));
        assertEquals(2,schema.db.queryForObject("SELECT COUNT(*) FROM coop_settlement_manual_case",Integer.class));
    }
}

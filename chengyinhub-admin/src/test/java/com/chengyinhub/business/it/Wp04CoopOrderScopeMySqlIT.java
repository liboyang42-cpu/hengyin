package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.domain.dto.CoopSettlementPlan;
import com.chengyinhub.business.domain.dto.CoopSettlementOriginLine;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real frozen lineup/reader -> prepare/evidence XML -> topic/claim/original producer.
 * Canonical scratch schema and original typed payment fixtures; no provider calls.
 * Topic/ticket adapters read actual JDBC rows; the topic CAS is explicit SQL within
 * the original transaction, not a claim that the Spring facade/proxy was exercised. */
class Wp04CoopOrderScopeMySqlIT {
    final Wp04OriginalRefundMySqlIT storage = new Wp04OriginalRefundMySqlIT();
    SqlSessionTemplate sql;
    CmsTopicMapper topics;
    CoopOrderMapper orders;
    CmsTopicServiceImpl topicOwner;
    FrozenCoopOrderSettlementSnapshotReader originalReader;
    CoopSettlementClaimServiceImpl claimOwner;

    @BeforeEach void canonicalScopeAndCash() throws Exception {
        storage.isolatedOriginalSchema(); // Retain explicit loopback/hosted/DDL/UUID cleanup gates.
        P0AcceptanceSchema.baseTables(storage.db, "coop_invite", "coop_order", "oms_ticket", "cms_topic_node",
                "cms_registration_scan", "coop_group_member_verify", "coop_group_verify");
        P0AcceptanceSchema.migrations(storage.source, Wp04OriginalRefundMySqlIT.MIGRATION,
                "migration_coop_deposit_refund_state_20260913.sql",
                "migration_20261024_registration_verified_seats.sql",
                "migration_20261024_coop_negative_margin_manual.sql");
        Wp04OriginalRefundMySqlIT.stationScopeSchema(storage.source);
        sql = P0AcceptanceSchema.sql(storage.source, "CmsRegistrationPaymentFactMapper", "CoopSettlementOriginalMapper",
                "CoopSettlementCashEvidenceMapper", "CoopSettlementEvidenceMapper", "CoopSettlementMapper",
                "CoopSettlementAdjustmentMapper", "SettlementClaimMapper", "CoopSettlementDetailMapper",
                "CoopInviteMapper", "CoopOrderMapper", "CmsActivityMapper", "RegistrationSeatMapper",
                "UmsMemberSettlementDebtMapper", "UmsMemberBalanceDetailMapper", "CoopSettlementManualCaseMapper",
                "CoopOrderStationFulfillmentMapper", "BusinessAuditEventMapper");
        storage.db.update("INSERT INTO ums_member(id,balance) VALUES(7,0),(77,0),(88,0),(99,0)");
        storage.db.update("INSERT INTO cms_topic(id,name,product_type,member_id,lifecycle,selling_started_at,del_flag,end_date,"
                + "is_settlement,fee_platform_rate,fee_guarantee_base_rate,fee_guarantee_step_rate,fee_guarantee_max_merchant) "
                + "VALUES(11,'G frozen order scopes',1,7,5,'2026-09-30 08:00:00',0,'2026-10-01',0,0.10,0.05,0.02,5)");
        storage.db.update("INSERT INTO oms_ticket(id,owner_type,owner_id,mode,sub_type,del_flag,start_time,end_time) "
                + "VALUES(21,1,11,1,'guided',0,'2026-10-01 09:00:00','2026-10-01 10:00:00')");
        var facts = WechatVerifiedFactTestSupport.realFacts(storage.source);
        var consumedRows = sql.getMapper(CmsRegistrationPaymentFactMapper.class);
        for (long id : List.of(1L, 2L)) {
            storage.db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,"
                    + "order_num,original_amount,total_amount,payable_amount,payment_status,registration_status,verification_status,"
                    + "verification_time,purchase_kind,pay_expire_time,wechat_payment_amount) "
                    + "VALUES(?,?,1,11,21,10,77,1,10,10,10,1,1,1,'2026-10-01 09:20:00',1,'2026-10-01 08:00:00',10)", id, "SCOPE" + id);
            var payment = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT, "merchant", "wx-original",
                    "SCOPE" + id, "SCOPE" + id, "SCOPE" + id, "TX" + id, "TX" + id, "APP", "CNY", 1000, 1000,
                    Instant.parse("2026-10-01T01:00:00Z"), null, "SUCCESS");
            var reference = facts.recordVerified(payment);
            storage.tx.executeWithoutResult(status -> {
                storage.db.update("UPDATE cms_registration SET payment_status=2,registration_status=2,transaction_id=? WHERE id=?", "TX" + id, id);
                assertEquals(1, consumedRows.insertConsumed(id, reference.id(), reference.hash()));
            });
            storage.db.update("INSERT INTO cms_registration_scan(registration_id,member_id,registration_type,status,create_time) "
                    + "VALUES(?,88,1,0,'2026-10-01 09:30:00')", id);
        }
        topics = mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenAnswer(call -> topic(true));
        when(topics.selectCmsTopicById(11L)).thenAnswer(call -> topic(false));
        OmsTicketMapper tickets = mock(OmsTicketMapper.class);
        when(tickets.selectOmsTicketList(any())).thenAnswer(call -> storage.db.query(
                "SELECT id,owner_type,owner_id,mode,sub_type,del_flag,start_time,end_time FROM oms_ticket WHERE owner_type=1 AND owner_id=11 AND del_flag=0 AND mode=?",
                (rs, n) -> {
                    OmsTicket ticket = new OmsTicket(); ticket.setId(rs.getLong("id")); ticket.setOwnerType(rs.getLong("owner_type"));
                    ticket.setOwnerId(rs.getLong("owner_id")); ticket.setMode(rs.getInt("mode")); ticket.setSubType(rs.getString("sub_type"));
                    ticket.setDelFlag(rs.getInt("del_flag")); ticket.setStartTime(rs.getTimestamp("start_time")); ticket.setEndTime(rs.getTimestamp("end_time"));
                    return ticket;
                }, ((OmsTicket) call.getArgument(0)).getMode()));
        orders = sql.getMapper(CoopOrderMapper.class);
        var invites = sql.getMapper(CoopInviteMapper.class);
        var evidenceRows = sql.getMapper(CoopSettlementEvidenceMapper.class);
        var evidence = new TopicSettlementEvidenceService(sql.getMapper(CmsActivityMapper.class), mock(CmsRegistrationMapper.class),
                mock(CoopGroupVerifyMapper.class), evidenceRows);
        var nodes = mock(CmsTopicNodeMapper.class);
        when(nodes.selectCmsTopicNodeById(anyLong())).thenAnswer(call -> storage.db.queryForObject(
                "SELECT id,topic_id FROM cms_topic_node WHERE id=?", (rs, n) -> {
                    CmsTopicNode node = new CmsTopicNode(); node.setId(rs.getLong("id")); node.setTopicId(rs.getLong("topic_id")); return node;
                }, (Long) call.getArgument(0)));
        originalReader = new FrozenCoopOrderSettlementSnapshotReader(new FailClosedCoopOrderLineupSource(orders, invites),
                topics, nodes, tickets);
        var claims = new CoopSettlementClaimServiceImpl(originalReader, new SnapshotFulfillmentWindowResolver(), evidence,
                new SettlementClaimServiceImpl(sql.getMapper(SettlementClaimMapper.class)), sql.getMapper(CoopSettlementMapper.class),
                sql.getMapper(CoopSettlementDetailMapper.class), sql.getMapper(CoopSettlementAdjustmentMapper.class));
        ReflectionTestUtils.setField(claims, "topicMapper", topics);
        claimOwner = claims;
        ReflectionTestUtils.setField(claims, "stationFulfillments", sql.getMapper(CoopOrderStationFulfillmentMapper.class));
        ReflectionTestUtils.setField(evidence, "stationFulfillments", sql.getMapper(CoopOrderStationFulfillmentMapper.class));
        var originalRows = sql.getMapper(CoopSettlementOriginalMapper.class);
        var cash = sql.getMapper(CoopSettlementCashEvidenceMapper.class);
        var consumption = new RegistrationPaymentConsumptionReadService(consumedRows);
        var members = mock(UmsMemberMapper.class); // No payout/refund in these tests; original balances remain zero.
        var debts = new SettlementDebtLedger(sql.getMapper(UmsMemberSettlementDebtMapper.class), members,
                sql.getMapper(UmsMemberBalanceDetailMapper.class));
        var originalOwner = new CoopSettlementOriginalLedger(topics, cash, originalRows, sql.getMapper(CoopSettlementMapper.class),
                sql.getMapper(CoopSettlementAdjustmentMapper.class), facts, consumption, members, mock(IUmsMemberBalanceDetailService.class), debts);
        ReflectionTestUtils.setField(originalOwner, "originalCloseEvidence", evidenceRows);
        var manual = new CoopSettlementManualCases(sql.getMapper(CoopSettlementManualCaseMapper.class), new DataSourceTransactionManager(storage.source));
        var producer = new CoopSettlementOriginalProducer(topics, originalRows, cash, originalOwner, consumption, facts,
                sql.getMapper(RegistrationSeatMapper.class), manual);
        var inviteOwner = new CoopInviteServiceImpl();
        ReflectionTestUtils.setField(inviteOwner, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(inviteOwner, "coopInviteMapper", invites);
        topicOwner = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(topicOwner, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(topicOwner, "coopOrderMapper", orders);
        ReflectionTestUtils.setField(topicOwner, "coopSettlementMapper", sql.getMapper(CoopSettlementMapper.class));
        ReflectionTestUtils.setField(topicOwner, "coopSettlementClaimService", claims);
        ReflectionTestUtils.setField(topicOwner, "coopSettlementEvidenceMapper", evidenceRows);
        ReflectionTestUtils.setField(topicOwner, "coopSettlementOriginalMapper", originalRows);
        ReflectionTestUtils.setField(topicOwner, "coopSettlementAdjustmentMapper", sql.getMapper(CoopSettlementAdjustmentMapper.class));
        ReflectionTestUtils.setField(topicOwner, "coopSettlementOriginalProducer", producer);
        ReflectionTestUtils.setField(topicOwner, "pricingService", new PricingServiceImpl());
        ReflectionTestUtils.setField(topicOwner, "coopInviteMapper", invites);
        ReflectionTestUtils.setField(topicOwner, "coopInviteService", inviteOwner);
    }

    @AfterEach void onlyOwnedCleanup() { storage.dropOnlyThisSuccessfullyCreatedDatabase(); }

    @Test void noneFirstCannotSilentlyDiscardOverlappingPaidOriginal() {
        order(701, 0, "NONE", 88); order(702, 1, "SALES_PERCENT", 88);
        rejectsWithoutEconomicWrites("ORIGINAL_ORDER_SCOPE_OVERLAP");
    }

    @Test void paidFirstCannotHideOverlappingNoneOriginalEither() {
        order(701, 1, "SALES_PERCENT", 88); order(702, 0, "NONE", 88);
        rejectsWithoutEconomicWrites("ORIGINAL_ORDER_SCOPE_OVERLAP");
    }

    @Test void twoPaidOriginalsCannotReuseSameRegistrationsSalesAndScans() {
        order(701, 1, "SALES_PERCENT", 88); order(702, 1, "SALES_PERCENT", 88);
        rejectsWithoutEconomicWrites("ORIGINAL_ORDER_SCOPE_OVERLAP");
    }

    @Test void laterInvalidFrozenContractNeverGetsSkippedByAnEarlierPayee() {
        order(701, 0, "NONE", 88); order(702, 1, "SALES_PERCENT", 88);
        storage.db.update("UPDATE coop_order SET terms_snapshot=NULL WHERE id=702");
        rejectsWithoutEconomicWrites("FROZEN_LINEUP_INVALID");
    }

    @Test void noneOriginalKeepsItsZeroClaimsWithoutInventingAPayableHead() {
        order(701, 0, "NONE", 88); settle();
        assertEquals(2, count("settlement_claim"));
        assertEquals(2, storage.db.queryForObject("SELECT COUNT(*) FROM settlement_claim WHERE settlement_route='NONE' AND source_contract_id=701", Integer.class));
        assertEquals(0, count("coop_settlement_detail"));
        assertEquals(0, storage.db.queryForObject("SELECT COUNT(*) FROM coop_settlement WHERE line_type='MERCHANT_VERIFY'", Integer.class));
        assertMoney("20", "SELECT SUM(original_amount) FROM coop_settlement_original_component");
        assertMoney("0", "SELECT SUM(balance) FROM ums_member");
    }

    @Test void onePaidOriginalFreezesNonzeroGrossComponentsAndExactReplayIsZeroEffect() {
        order(701, 1, "SALES_PERCENT", 88); settle();
        assertEquals(2, count("settlement_claim")); assertEquals(2, count("coop_settlement_detail"));
        assertMoney("10", "SELECT amount FROM coop_settlement WHERE coop_order_id=701");
        assertMoney("10", "SELECT SUM(original_amount) FROM coop_settlement_original_component c JOIN coop_settlement h ON h.id=c.settlement_id WHERE h.coop_order_id=701");
        assertEquals("1,2", storage.db.queryForObject("SELECT verified_reg_ids FROM coop_settlement WHERE coop_order_id=701", String.class));
        assertMoney("1.40", "SELECT amount FROM coop_settlement WHERE line_type='INITIATOR_GUARANTEE'");
        assertEquals(1, storage.db.queryForObject("SELECT original_verified_merchant_count FROM coop_settlement_cash_close", Integer.class));
        assertMoney("20", "SELECT SUM(original_amount) FROM coop_settlement_original_component");
        int heads = count("coop_settlement"), components = count("coop_settlement_original_component");
        settle(); assertEquals(heads, count("coop_settlement")); assertEquals(components, count("coop_settlement_original_component"));
        assertEquals(2, count("settlement_claim")); assertEquals(0, count("coop_settlement_adjustment"));
        assertMoney("0", "SELECT SUM(balance) FROM ums_member");
    }

    @Test void nodeIdCannotInventIndependentSalesOrFulfillmentForAPaidOriginal() {
        order(701, 0, "NONE", 88); order(702, 1, "SALES_PERCENT", 88);
        storage.db.update("INSERT INTO cms_topic_node(id,topic_id) VALUES(31,11)");
        storage.db.update("INSERT INTO oms_ticket(id,owner_type,owner_id,mode,del_flag,start_time,end_time) "
                + "VALUES(22,1,11,2,0,'2026-10-01 09:00:00','2026-10-01 10:00:00')");
        storage.db.update("UPDATE coop_order SET scope_type_snapshot='NODE',scope_id_snapshot=31,eligible_purchase_kinds_snapshot='EXPLORE_PASS' WHERE id=702");
        rejectsWithoutEconomicWrites("UNSUPPORTED_CASH_PATH");
    }

    @Test void completeClubScopePlanDoesNotEnableRealUnsupportedClubCashPrepare() {
        order(701, 1, "SALES_PERCENT", 88);
        storage.db.update("INSERT INTO coop_invite(id,invite_type,from_type,from_id,to_type,to_id,topic_id,status,del_flag) "
                + "VALUES(901,1,'talent',7,'club',88,11,1,0)");
        storage.db.update("UPDATE coop_order SET invite_id=901,to_type='club',role_snapshot='ORGANIZER',"
                + "fulfillment_evidence_rule_snapshot='COOP_GROUP_VERIFY' WHERE id=701");
        CoopSettlementSnapshot snapshot = storage.tx.execute(status -> {
            topics.selectCmsTopicByIdForUpdate(11L);
            return originalReader.read(orders.selectEffectiveOrdersByTopicId(11L).get(0));
        });
        assertNotNull(snapshot); assertEquals("CLUB", snapshot.getBeneficiaryRole());
        assertEquals("ORGANIZER_LEAD", snapshot.getServiceUnit());
        // Shape-only plan for the pure invariant guard; this is not a fulfillment/payment claim.
        assertDoesNotThrow(() -> CoopSettlementContract.requireIndependentOrderScopes(List.of(scopePlan(snapshot, 1L))));
        // The actual production prepare, reached through Topic, must still deny this cash route.
        rejectsWithoutEconomicWrites("UNSUPPORTED_CASH_PATH");
    }

    @Test void scopeGuardRejectsMissingSnapshotAndBrokenOriginalIdentities() {
        assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(null));
        assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(Collections.singletonList(null)));
        assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(
                List.of(new CoopSettlementPlan(null, "COOP_ORDER", List.of()))));
        List<java.util.function.Consumer<CoopSettlementSnapshot>> invalid = List.of(
                value -> value.setOrderId(null), value -> value.setOrderId(0L),
                value -> value.setTopicId(null), value -> value.setTopicId(-1L),
                value -> value.setInitiatorMemberId(null), value -> value.setInitiatorMemberId(0L),
                value -> value.setBeneficiaryId(null), value -> value.setBeneficiaryId(0L),
                value -> value.setCommercialContextId(null), value -> value.setCommercialContextId(0L),
                value -> value.setCommercialContextType("OTHER"), value -> value.setCommercialContextId(99L),
                value -> value.setScopeId(null), value -> value.setScopeId(0L),
                value -> value.setScopeType("OTHER"), value -> value.setScopeId(99L));
        for (var change : invalid) {
            var snapshot = scopeSnapshot(701L); change.accept(snapshot);
            assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(List.of(scopePlan(snapshot, 1L))));
        }
        var first = scopeSnapshot(701L); var otherTopic = scopeSnapshot(702L);
        otherTopic.setTopicId(12L); otherTopic.setCommercialContextId(12L); otherTopic.setScopeId(12L);
        assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(
                List.of(scopePlan(first, 1L), scopePlan(otherTopic, 2L))));
        var otherInitiator = scopeSnapshot(702L); otherInitiator.setInitiatorMemberId(99L);
        assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(
                List.of(scopePlan(first, 1L), scopePlan(otherInitiator, 2L))));
    }

    @Test void scopeGuardRejectsForgedRolesUnitsRoutesAndUnprovenLines() {
        List<java.util.function.Consumer<CoopSettlementSnapshot>> invalid = List.of(
                value -> value.setBeneficiaryRole(null), value -> value.setBeneficiaryRole("PLATFORM"),
                value -> value.setServiceUnit(null), value -> value.setServiceUnit("UNKNOWN"),
                value -> value.setServiceUnit("ORGANIZER_LEAD"), value -> value.setBeneficiaryRole("CLUB"),
                value -> value.setCompensationMode(null), value -> value.setCompensationMode("UNKNOWN"));
        for (var change : invalid) {
            var snapshot = scopeSnapshot(701L); change.accept(snapshot);
            assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(List.of(scopePlan(snapshot, 1L))));
        }
        var snapshot = scopeSnapshot(701L); var valid = scopePlan(snapshot, 1L);
        for (var malformed : List.of(new CoopSettlementPlan(snapshot, "UNKNOWN", valid.getOriginLines()),
                new CoopSettlementPlan(snapshot, "COOP_ORDER", null), new CoopSettlementPlan(snapshot, "COOP_ORDER", List.of())))
            assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(List.of(malformed)));
        List<java.util.function.Consumer<CoopSettlementOriginLine>> badLines = List.of(
                value -> value.setEligibleSalesSnapshot(null), value -> value.setEligibleSalesSnapshot(new BigDecimal("-1")),
                value -> value.setOriginAmount(null), value -> value.setOriginAmount(new BigDecimal("-1")),
                value -> value.setSourceContractId(999L), value -> value.setCommercialContextId(999L),
                value -> value.setServiceUnit("ORGANIZER_LEAD"));
        for (var change : badLines) {
            var plan = scopePlan(snapshot, 1L); change.accept(plan.getOriginLines().get(0));
            assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(List.of(plan)));
        }
        assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(List.of(
                new CoopSettlementPlan(snapshot, "COOP_ORDER", Collections.singletonList(null)))));
        assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(List.of(
                scopePlan(snapshot, 0L))));
        assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(List.of(
                scopePlan(snapshot, null))));
        var repeatedLine = scopePlan(snapshot, 1L).getOriginLines().get(0);
        assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(List.of(
                new CoopSettlementPlan(snapshot, "COOP_ORDER", List.of(repeatedLine, repeatedLine)))));
    }

    @Test void scopeGuardAllowsDisjointShapeButRejectsSharedSalesIncludingNoneInBothOrders() {
        var first = scopeSnapshot(701L); var second = scopeSnapshot(702L);
        assertDoesNotThrow(() -> CoopSettlementContract.requireIndependentOrderScopes(
                List.of(scopePlan(first, 1L), scopePlan(second, 2L))));
        assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(
                List.of(scopePlan(first, 1L), scopePlan(second, 1L))));
        first.setCompensationMode("NONE");
        var none = scopePlan(first, 1L); var paid = scopePlan(second, 1L);
        for (var sequence : List.of(List.of(none, paid), List.of(paid, none))) {
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> CoopSettlementContract.requireIndependentOrderScopes(sequence));
            assertTrue(rejected.getMessage().contains("ORIGINAL_ORDER_SCOPE_OVERLAP"));
        }
        assertThrows(IllegalStateException.class, () -> CoopSettlementContract.requireIndependentOrderScopes(
                List.of(scopePlan(second, 1L), scopePlan(second, 2L))));
    }

    private static CoopSettlementSnapshot scopeSnapshot(Long orderId) {
        var snapshot = new CoopSettlementSnapshot(); snapshot.setOrderId(orderId); snapshot.setTopicId(11L);
        snapshot.setInitiatorMemberId(7L); snapshot.setBeneficiaryRole("MERCHANT"); snapshot.setBeneficiaryId(88L);
        snapshot.setServiceUnit("MERCHANT_PARTICIPATION"); snapshot.setCommercialContextType("TOPIC_EDITION");
        snapshot.setCommercialContextId(11L); snapshot.setScopeType("TOPIC"); snapshot.setScopeId(11L);
        snapshot.setCompensationMode("SALES_PERCENT"); return snapshot;
    }

    private static CoopSettlementPlan scopePlan(CoopSettlementSnapshot snapshot, Long registrationId) {
        var line = new CoopSettlementOriginLine(registrationId,
                "NONE".equals(snapshot.getCompensationMode()) ? BigDecimal.ZERO : BigDecimal.ONE);
        line.setEligibleSalesSnapshot(new BigDecimal("10")); line.setSettlementRoute("COOP_ORDER");
        line.setSourceContractType("COOP_ORDER"); line.setSourceContractId(snapshot.getOrderId());
        line.setCommercialContextType(snapshot.getCommercialContextType()); line.setCommercialContextId(snapshot.getCommercialContextId());
        line.setBeneficiaryRole(snapshot.getBeneficiaryRole()); line.setBeneficiaryId(snapshot.getBeneficiaryId()); line.setServiceUnit(snapshot.getServiceUnit());
        return new CoopSettlementPlan(snapshot, "NONE".equals(snapshot.getCompensationMode()) ? "NONE" : "COOP_ORDER", List.of(line));
    }

    private void order(long id, int mode, String compensation, long merchant) {
        storage.db.update("INSERT INTO coop_order(id,topic_id,from_id,to_id,to_type,share_mode,share_rate,terms_version,role_snapshot,"
                + "scope_type_snapshot,scope_id_snapshot,eligible_purchase_kinds_snapshot,fulfillment_evidence_rule_snapshot,terms_snapshot,status,del_flag,create_time) "
                + "VALUES(?,11,7,?,'merchant',?,50,'v1','SUPPLY','TOPIC',11,'GUIDED_TICKET','REGISTRATION_SCAN',?,0,0,?)",
                id, merchant, mode, "{\"shareMode\":" + mode + ",\"compensationMode\":\"" + compensation + "\",\"shareRate\":50}",
                new Timestamp(1700000000000L + id));
    }

    private CmsTopic topic(boolean lock) {
        return storage.db.queryForObject("SELECT id,member_id,product_type,lifecycle,selling_started_at,del_flag,end_date,is_settlement,"
                + "fee_platform_rate,fee_guarantee_base_rate,fee_guarantee_step_rate,fee_guarantee_max_merchant FROM cms_topic WHERE id=11" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> {
                    CmsTopic row = new CmsTopic(); row.setId(rs.getLong("id")); row.setMemberId(rs.getLong("member_id"));
                    row.setProductType(rs.getInt("product_type")); row.setLifecycle(rs.getInt("lifecycle"));
                    row.setSellingStartedAt(rs.getTimestamp("selling_started_at")); row.setDelFlag(rs.getInt("del_flag"));
                    row.setEndDate(rs.getTimestamp("end_date")); row.setIsSettlement(rs.getInt("is_settlement"));
                    row.setFeePlatformRate(rs.getBigDecimal("fee_platform_rate")); row.setFeeGuaranteeBaseRate(rs.getBigDecimal("fee_guarantee_base_rate"));
                    row.setFeeGuaranteeStepRate(rs.getBigDecimal("fee_guarantee_step_rate")); row.setFeeGuaranteeMaxMerchant(rs.getInt("fee_guarantee_max_merchant"));
                    return row;
                });
    }

    private void settle() {
        storage.tx.executeWithoutResult(status -> {
            CmsTopic current = topics.selectCmsTopicByIdForUpdate(11L);
            storage.db.update("UPDATE cms_topic SET is_settlement=1 WHERE id=11 AND is_settlement=0");
            current.setIsSettlement(1);
            ReflectionTestUtils.invokeMethod(topicOwner, "settleCoopTopic", current, new BigDecimal("20"));
        });
    }

    private void rejectsWithoutEconomicWrites(String reason) {
        IllegalStateException rejected = assertThrows(IllegalStateException.class, this::settle);
        assertTrue(rejected.getMessage().contains(reason), rejected.getMessage());
        assertEquals(0, storage.db.queryForObject("SELECT is_settlement FROM cms_topic WHERE id=11", Integer.class));
        for (String table : List.of("coop_settlement", "settlement_claim", "coop_settlement_detail", "coop_settlement_cash_close",
                "coop_settlement_original_component", "coop_settlement_adjustment", "coop_settlement_original_adjustment")) assertEquals(0, count(table), table);
        assertEquals(2, count("cms_registration_payment_fact")); assertEquals(2, count("wx_verified_payment_fact"));
        assertMoney("0", "SELECT SUM(balance) FROM ums_member");
    }

    private int count(String table) { return storage.db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void assertMoney(String amount, String query) {
        assertEquals(0, new BigDecimal(amount).compareTo(storage.db.queryForObject(query, BigDecimal.class)));
    }
}

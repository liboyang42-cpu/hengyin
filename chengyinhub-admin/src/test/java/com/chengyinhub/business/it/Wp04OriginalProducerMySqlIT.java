package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual producer/ledger/mapper/transactions in an explicitly approved isolated schema; no provider calls. */
class Wp04OriginalProducerMySqlIT {
    final Wp04OriginalRefundMySqlIT db=new Wp04OriginalRefundMySqlIT();
    SqlSessionTemplate sql;
    CoopSettlementOriginalProducer producer;
    CoopSettlementManualCases manual;
    CoopSettlementOriginalMapper original;
    CmsTopic topic;
    List<CmsRegistration> registrations;
    @BeforeEach void originalFactsAndHeads() throws Exception {
        assertEquals("1",System.getenv("WP04_ORIGINAL_PRODUCER_DDL_APPROVED"));
        db.isolatedOriginalSchema(); // Original explicit host/port/user/password/suffix/GitHub-hosted gates and owned UUID cleanup.
        P0AcceptanceSchema.migrations(db.source,Wp04OriginalRefundMySqlIT.MIGRATION,
                "migration_20261024_registration_verified_seats.sql","migration_20261024_coop_negative_margin_manual.sql");
        sql=P0AcceptanceSchema.sql(db.source,"CmsRegistrationPaymentFactMapper","CoopSettlementOriginalMapper","CoopSettlementCashEvidenceMapper",
                "CoopSettlementEvidenceMapper","CoopSettlementMapper","CoopSettlementAdjustmentMapper","WechatVerifiedPaymentFactMapper",
                "UmsMemberSettlementDebtMapper","UmsMemberBalanceDetailMapper","RegistrationSeatMapper","CoopSettlementManualCaseMapper");
        db.db.update("INSERT INTO ums_member(id,balance) VALUES(7,0),(77,0)");
        db.db.update("INSERT INTO cms_topic(id,name,product_type,member_id,is_settlement,fee_platform_rate,fee_guarantee_base_rate,fee_guarantee_step_rate,fee_guarantee_max_merchant) VALUES(11,'G producer',1,7,0,0.10,0.05,0.02,5)");
        var facts=WechatVerifiedFactTestSupport.realFacts(db.source);
        db.facts=facts;
        var consumedRows=sql.getMapper(CmsRegistrationPaymentFactMapper.class);
        for(long id:List.of(1L,2L)) {
            db.db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,order_num,original_amount,total_amount,payable_amount,payment_status,registration_status,verification_status,purchase_kind,pay_expire_time,wechat_payment_amount) VALUES(?,?,1,11,21,10,77,1,10,10,10,1,1,0,1,'2026-10-01 08:00:00',10)",id,"REG"+id);
            var fact=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"merchant","wx-original","REG"+id,"REG"+id,"REG"+id,"TX"+id,"TX"+id,"APP","CNY",1000,1000,java.time.Instant.parse("2026-10-24T00:00:00Z"),null,"SUCCESS");
            var ref=facts.recordVerified(fact);
            db.tx.executeWithoutResult(status->{
                db.db.update("UPDATE cms_registration SET payment_status=2,registration_status=2,transaction_id=? WHERE id=?","TX"+id,id);
                assertEquals(1,consumedRows.insertConsumed(id,ref.id(),ref.hash()));
            });
        }
        db.db.update("INSERT INTO coop_settlement(id,topic_id,merchant_id,cash_head_kind,line_type,payee_type,status,amount,del_flag) VALUES(11,11,NULL,'ACCOUNTING','PLATFORM','platform',1,2,0),(12,11,7,'MEMBER','INITIATOR_GUARANTEE','initiator',0,1,0),(13,11,7,'MEMBER','INITIATOR_MARGIN','initiator',0,17,0)");
        CmsTopicMapper topics=mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenAnswer(call->db.db.queryForObject("SELECT id,member_id,fee_platform_rate,fee_guarantee_base_rate,fee_guarantee_step_rate,fee_guarantee_max_merchant,is_settlement,club_id FROM cms_topic WHERE id=11 FOR UPDATE",(rs,n)->{
            CmsTopic t=new CmsTopic();t.setId(rs.getLong(1));t.setMemberId(rs.getLong(2));t.setFeePlatformRate(rs.getBigDecimal(3));t.setFeeGuaranteeBaseRate(rs.getBigDecimal(4));t.setFeeGuaranteeStepRate(rs.getBigDecimal(5));t.setFeeGuaranteeMaxMerchant(rs.getInt(6));t.setIsSettlement(rs.getInt(7));t.setClubId(rs.getObject(8)==null?null:rs.getLong(8));return t;
        }));
        original=spy(sql.getMapper(CoopSettlementOriginalMapper.class));
        var cash=sql.getMapper(CoopSettlementCashEvidenceMapper.class);
        var consumed=new RegistrationPaymentConsumptionReadService(consumedRows);
        var memberRows=mock(UmsMemberMapper.class);
        when(memberRows.selectBalanceForUpdate(7L)).thenAnswer(call->db.db.queryForObject("SELECT balance FROM ums_member WHERE id=7 FOR UPDATE",BigDecimal.class));
        when(memberRows.deductBalance(eq(7L),any())).thenAnswer(call->{BigDecimal amount=call.getArgument(1);
            return db.db.update("UPDATE ums_member SET balance=balance-? WHERE id=7 AND balance>=?",amount,amount);});
        var balanceLedger=mock(IUmsMemberBalanceDetailService.class);
        when(balanceLedger.insertUmsMemberBalanceDetail(any())).thenAnswer(call->sql.getMapper(UmsMemberBalanceDetailMapper.class).insertUmsMemberBalanceDetail(call.getArgument(0)));
        var ledger=new CoopSettlementOriginalLedger(topics,cash,original,sql.getMapper(CoopSettlementMapper.class),sql.getMapper(CoopSettlementAdjustmentMapper.class),facts,consumed,
                memberRows,balanceLedger,new SettlementDebtLedger(sql.getMapper(UmsMemberSettlementDebtMapper.class),memberRows,sql.getMapper(UmsMemberBalanceDetailMapper.class)));
        db.owner=ledger;
        ReflectionTestUtils.setField(ledger,"originalCloseEvidence",sql.getMapper(CoopSettlementEvidenceMapper.class));
        manual=new CoopSettlementManualCases(sql.getMapper(CoopSettlementManualCaseMapper.class),new DataSourceTransactionManager(db.source));
        producer=new CoopSettlementOriginalProducer(topics,original,cash,ledger,consumed,facts,sql.getMapper(RegistrationSeatMapper.class),manual);
        topic=new CmsTopic();topic.setId(11L);
    }
    @AfterEach void cleanupOnlyOwnedSchema(){db.dropOnlyThisSuccessfullyCreatedDatabase();}
    @Test void actualProducerFreezesAllOriginalComponentsAndExactReplayCreatesNothing() {
        freeze();
        assertEquals(6,count("coop_settlement_original_component"));assertEquals(2,count("coop_settlement_cash_contribution"));
        assertEquals(new BigDecimal("20.00"),db.db.queryForObject("SELECT SUM(original_amount) FROM coop_settlement_original_component",BigDecimal.class));
        assertEquals(0,db.db.queryForObject("SELECT COUNT(*) FROM (SELECT payer_registration_id FROM coop_settlement_original_component GROUP BY payer_registration_id HAVING SUM(original_amount)<>10) bad",Integer.class));
        freeze();assertEquals(6,count("coop_settlement_original_component"));assertEquals(1,count("coop_settlement_cash_close"));
        assertEquals(new BigDecimal("0.00"),db.db.queryForObject("SELECT SUM(balance) FROM ums_member",BigDecimal.class));
    }
    @Test void failureAfterFirstRealComponentRollsBackEntireCloseButPreservesConsumedPaymentFacts() {
        doAnswer(call->{int result=sql.getMapper(CoopSettlementOriginalMapper.class).insertConsumedPaymentComponent(call.getArgument(0));
            CoopSettlementOriginalComponent row=call.getArgument(0);if(row.getPayerRegistrationId()==2L) throw new IllegalStateException("INJECTED_AFTER_ORIGINAL_COMPONENT");return result;
        }).when(original).insertConsumedPaymentComponent(any());
        assertThrows(IllegalStateException.class,this::freeze);
        assertEquals(0,count("coop_settlement_original_component"));assertEquals(0,count("coop_settlement_cash_close"));
        assertEquals(2,count("cms_registration_payment_fact"));assertEquals(2,count("wx_verified_payment_fact"));
        assertEquals(0,db.db.queryForObject("SELECT COUNT(*) FROM coop_settlement WHERE cash_close_id IS NOT NULL",Integer.class));
    }
    @Test void physicalRollbackLeavesOneDurableManualCaseAndNoEconomicsOrClaimedTopic() {
        for(int retry=0;retry<2;retry++) assertThrows(IllegalStateException.class,()->db.tx.executeWithoutResult(status->{
            db.db.update("UPDATE cms_topic SET is_settlement=1 WHERE id=11");manual.afterRollback(11L,"{\"originalLoss\":\"-1.00\"}",new BigDecimal("-1.00"));throw new IllegalStateException("rollback negative margin");
        }));
        assertEquals(1,count("coop_settlement_manual_case"));assertEquals(0,db.db.queryForObject("SELECT is_settlement FROM cms_topic WHERE id=11",Integer.class));
        assertEquals(0,count("coop_settlement_original_component"));
        assertThrows(IllegalStateException.class,()->db.tx.executeWithoutResult(status->manual.requireNoOpenCase(11L)));
        assertEquals(new BigDecimal("0.00"),db.db.queryForObject("SELECT SUM(balance) FROM ums_member",BigDecimal.class));
    }
    @Test void clubSelfPlaySettlementFreezesOriginalsAndPaidRefundDebitsPrincipalExactlyOnce() {
        db.db.update("DELETE FROM coop_settlement");
        db.db.update("UPDATE cms_topic SET club_id=42,is_settlement=1 WHERE id=11");
        db.db.update("UPDATE cms_registration SET purchase_kind=2,ticket_id=NULL WHERE owner_type=1 AND owner_id=11");
        topic.setClubId(42L);topic.setMemberId(7L);topic.setEndDate(new Date());
        var service=new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(service,"coopSettlementMapper",sql.getMapper(CoopSettlementMapper.class));
        ReflectionTestUtils.setField(service,"coopSettlementEvidenceMapper",sql.getMapper(CoopSettlementEvidenceMapper.class));
        ReflectionTestUtils.setField(service,"coopSettlementOriginalProducer",producer);
        ReflectionTestUtils.setField(service,"pointsAwardService",mock(IPointsAwardService.class));
        var pricing=mock(IPricingService.class);when(pricing.platformRateOf(topic)).thenReturn(new BigDecimal("0.10"));
        ReflectionTestUtils.setField(service,"pricingService",pricing);
        var payee=mock(ClubPrincipalPayee.class);when(payee.forTopic(topic)).thenReturn(7L);
        ReflectionTestUtils.setField(service,"clubPrincipalPayee",payee);
        db.tx.executeWithoutResult(status->assertEquals(Boolean.TRUE,ReflectionTestUtils.invokeMethod(service,"settleClubSelfPlayTopic",topic)));
        assertEquals(4,count("coop_settlement_original_component"));
        Long head=db.db.queryForObject("SELECT id FROM coop_settlement WHERE line_type='INITIATOR_MARGIN'",Long.class);
        assertEquals(new BigDecimal("18.00"),db.db.queryForObject("SELECT amount FROM coop_settlement WHERE id=?",BigDecimal.class,head));
        // Historical payout fixture; the actual original-refund owner below must verify this credit before debiting.
        db.db.update("UPDATE coop_settlement SET status=1 WHERE id=?",head);
        db.db.update("UPDATE ums_member SET balance=18 WHERE id=7");
        db.db.update("INSERT INTO ums_member_balance_detail(member_id,current_balance,after_balance,change_balance,change_reason,event_type,event_id,change_type,del_flag,create_time) VALUES(7,0,18,18,'Coop结算打款 - 主题11',5,?,1,0,NOW())",head);
        var fact=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,"merchant","wx-original","REG1","REG1","RF901","WXREF901","TX1","APP","CNY",1000,1000,java.time.Instant.parse("2026-10-24T02:00:00Z"),null,"REFUND");
        var reference=db.facts.recordVerified(fact);
        db.db.update("INSERT INTO refund_application(id,refund_no,source_type,source_id,owner_type,owner_id,status,payout_status,refund_amount,paid_amount,del_flag) VALUES(901,'RF901','registration',1,1,11,1,0,10,10,0)");
        String hash=WxPaymentCallbackInboxServiceImpl.sha256Hex("REFUND:RF901:SUCCESS");
        db.db.update("INSERT INTO wx_payment_callback_inbox(id,notify_hash,biz_type,biz_id,event_type,state) VALUES(901,UNHEX(?),2,901,'SUCCESS',0)",hash);
        db.facts.bindNewInbox(901,hash,reference,true);
        db.terminalSuccessAndHook(901L);db.terminalSuccessAndHook(901L);
        assertEquals(new BigDecimal("9.00"),db.balance());
        assertEquals(new BigDecimal("-10.00"),db.db.queryForObject("SELECT SUM(amount) FROM coop_settlement_original_adjustment",BigDecimal.class));
        assertEquals(1,count("coop_settlement_refund_receipt"));assertEquals(2,count("coop_settlement_original_adjustment"));
        assertEquals(1,db.db.queryForObject("SELECT COUNT(*) FROM ums_member_balance_detail WHERE event_type=94 AND change_balance=-9",Integer.class));
        assertEquals(0,count("ums_member_settlement_debt"));
    }
    private void freeze(){db.tx.executeWithoutResult(status->{db.db.update("UPDATE cms_topic SET is_settlement=1 WHERE id=11");var rows=original.selectOriginalRegistrationsForUpdate(11L);producer.freezeOriginals(topic,TopicSettlementCashBasis.fromLockedRows(11L,rows),Date.from(java.time.Instant.parse("2026-10-24T01:00:00Z")));});}
    private int count(String table){return db.db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
}

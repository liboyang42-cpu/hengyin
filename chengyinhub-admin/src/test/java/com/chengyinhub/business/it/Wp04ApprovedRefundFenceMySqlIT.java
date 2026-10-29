package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Reuses the existing guarded UUID MySQL fixture; no inherited duplicate cases.
 * Old-owner money rows are historical fixtures, not a claim to execute an old RefundService or provider SDK. */
class Wp04ApprovedRefundFenceMySqlIT {
    Wp04PaidHalfStateMySqlIT f=new Wp04PaidHalfStateMySqlIT();
    @BeforeEach void schema() throws Exception {f.originalSchemaAndTenYuanPaidHalfState(false);f.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    @AfterEach void cleanup(){f.dropOnlyOwnCreatedSchema();}

    @Test void legacyApprovedClawbackCannotBeFrozenIntoNewOriginalsAndChargedAgain() throws Exception {
        completeOriginalPaidHalfThroughVerifiedInbox();
        f.db.update("UPDATE cms_topic SET is_settlement=1,fee_platform_rate=0,fee_guarantee_base_rate=0,fee_guarantee_step_rate=0,fee_guarantee_max_merchant=0 WHERE id=11");
        f.db.update("INSERT INTO coop_settlement(id,topic_id,merchant_id,cash_head_kind,line_type,payee_type,amount,status) VALUES(12,11,7,'MEMBER','INITIATOR_MARGIN','initiator',10,1)");
        SqlSessionTemplate sql=P0AcceptanceSchema.sql(f.source,"CoopSettlementCashEvidenceMapper","CoopSettlementMapper","CoopSettlementAdjustmentMapper");
        CoopSettlementCashEvidenceMapper cash=sql.getMapper(CoopSettlementCashEvidenceMapper.class);
        CoopSettlementCashClose close=close();CoopSettlementCashContribution contribution=contribution();
        // Older source-evidence layer existed before original components; a replay could previously reuse this paid snapshot.
        f.tx.executeWithoutResult(status->{f.lockTopic();cash.recordClose(close);contribution.setCashCloseId(close.getId());cash.recordContribution(contribution);});
        P0AcceptanceSchema.baseTables(f.db,"settlement_detail");
        f.db.update("INSERT INTO refund_application(id,refund_no,source_type,source_id,owner_type,owner_id,status,payout_status,refund_amount,paid_amount,del_flag) VALUES(901,'RF901','registration',1,1,99,1,0,10,10,0)");
        f.db.update("INSERT INTO settlement_detail(batch_id,merchant_id,source_type,source_id,receiver_type,receiver_id,gross_amount,commission_amount,settlement_amount,status,idempotency_key) VALUES(0,7,'refund',901,'creator',7,-10,0,-10,1,'refund:901:creator:7')");
        f.db.update("INSERT INTO ums_member_balance_detail(member_id,current_balance,after_balance,change_balance,change_reason,event_type,event_id,change_type,del_flag,create_time) VALUES(7,0,10,10,'Coop结算打款 - 主题11',5,12,1,0,NOW()),(7,10,0,-10,'退款冲销',6,901,2,0,NOW())");
        f.db.update("UPDATE cms_registration SET payment_status=4,registration_status=3 WHERE id=1");
        CoopSettlementOriginalLedger owner=new CoopSettlementOriginalLedger(topicMapper(),cash,f.originals,sql.getMapper(CoopSettlementMapper.class),
                sql.getMapper(CoopSettlementAdjustmentMapper.class),f.facts,mock(com.chengyinhub.business.service.support.RegistrationPaymentConsumptionReadService.class),mock(UmsMemberMapper.class),mock(IUmsMemberBalanceDetailService.class),mock(SettlementDebtLedger.class));
        ReflectionTestUtils.setField(owner,"originalCloseEvidence",f.evidence);
        CoopSettlementOriginalComponent original=new CoopSettlementOriginalComponent();original.setSourceRegistrationId(1L);original.setPayerRegistrationId(1L);
        original.setSettlementId(12L);original.setReceiverKind("MEMBER");original.setReceiverMemberId(7L);original.setLineType("INITIATOR_MARGIN");
        original.setOriginalAmount(money("10"));original.setOriginalOrderCash(money("10"));original.setCalculationVersion(close.getOriginCalculationVersion());
        original.setPaymentFactId(f.reference.id());original.setPaymentFactHash(f.reference.hash());
        IllegalStateException error=assertThrows(IllegalStateException.class,()->f.tx.executeWithoutResult(status->owner.freezeOriginals(close,List.of(contribution),List.of(original))));
        assertTrue(error.getMessage().contains("APPROVED_REFUND_PRECEDES_ORIGINAL_CLOSE"));
        assertEquals(0,f.count("coop_settlement_original_component"));assertEquals(0,f.count("coop_settlement_original_adjustment"));assertEquals(0,f.count("coop_settlement_refund_receipt"));
        assertEquals(1,f.count("settlement_detail"));assertEquals(2,f.count("ums_member_balance_detail"));assertEquals(0,f.count("ums_member_settlement_debt"));
        assertEquals(0,money("0").compareTo(f.db.queryForObject("SELECT balance FROM ums_member WHERE id=7",BigDecimal.class)));
        assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM coop_settlement WHERE cash_close_id IS NOT NULL",Integer.class));
        assertEquals(1,f.count("coop_settlement_cash_close"));assertEquals(1,f.count("coop_settlement_cash_contribution"));
        assertEquals(1,f.db.queryForObject("SELECT status FROM refund_application WHERE id=901",Integer.class));
        assertEquals(2,f.inboxState());assertEquals(f.reference,f.facts.requireVerified(f.reference).reference());
    }

    @Test void approvedActivityRefundWithLegacyOwnerAndDeletedFlagsBlocksZeroClose() {
        f.db.update("UPDATE cms_registration SET payment_status=0 WHERE id=1");
        f.db.update("INSERT INTO cms_activity(id,topic_id,del_flag) VALUES(201,11,2),(11,99,0)");
        activityRegistration(2,201);activityRegistration(3,11);
        refund(902,2,2);refund(903,3,0);
        f.tx.executeWithoutResult(status->{f.lockTopic();f.evidence.selectTopicCashRowsForUpdate(11L);f.evidence.selectTopicActivityCashRowsForUpdate(11L);
            assertEquals(List.of(902L),f.originals.selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate(11L));});
        RuntimeException error=assertThrows(RuntimeException.class,()->f.tx.execute(status->ReflectionTestUtils.invokeMethod(f.settlement,"settleTopic",f.newTopic(),new SettlementRule())));
        assertTrue(error.getMessage().contains("APPROVED_REFUND_PRECEDES_ORIGINAL_CLOSE"));
        assertEquals(0,f.topicClosed());assertEquals(0,f.count("coop_settlement"));assertEquals(0,f.count("coop_settlement_cash_close"));
        assertEquals(2,f.db.queryForObject("SELECT del_flag FROM refund_application WHERE id=902",Integer.class));
        assertEquals(2,f.count("refund_application"));verifyNoInteractions(f.deposits);
    }

    @Test void foreignRealActivityParentCannotBeReplacedByMatchingActivityIdOrRefundOwner() {
        f.db.update("UPDATE cms_registration SET payment_status=0 WHERE id=1");
        f.db.update("INSERT INTO cms_activity(id,topic_id,del_flag) VALUES(11,99,0)");activityRegistration(2,11);refund(902,2,0);
        assertTrue(f.tx.execute(status->f.originals.selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate(11L)).isEmpty());
        assertEquals(Boolean.TRUE,f.tx.execute(status->ReflectionTestUtils.invokeMethod(f.settlement,"settleTopic",f.newTopic(),new SettlementRule())));
        assertEquals(1,f.topicClosed());assertEquals(1,f.count("refund_application"));assertEquals(0,f.count("coop_settlement"));
    }

    @Test void currentApprovedReaderSeesCommitAfterOlderRepeatableReadAndBypassesLocalCache() throws Exception {
        refund(902,1,0);refund(903,1,0);
        f.db.update("UPDATE refund_application SET status=0,payout_status=0,refund_amount=5 WHERE id IN (902,903)");
        ExecutorService writer=Executors.newSingleThreadExecutor();
        try {
            f.tx.executeWithoutResult(status->{
                assertEquals(0,f.db.queryForObject("SELECT status FROM refund_application WHERE id=902",Integer.class));
                try {assertEquals(1,writer.submit(()->f.tx.execute(inner->{f.lockTopic();f.registrations.selectCmsRegistrationByIdForUpdate(1L);return f.db.update("UPDATE refund_application SET status=1 WHERE id=902");})).get(5,TimeUnit.SECONDS));}
                catch(Exception failure){throw new IllegalStateException(failure);}
                f.lockTopic();f.evidence.selectTopicCashRowsForUpdate(11L);f.evidence.selectTopicActivityCashRowsForUpdate(11L);
                assertEquals(List.of(902L),f.originals.selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate(11L));
                assertEquals(0,f.db.queryForObject("SELECT status FROM refund_application WHERE id=902",Integer.class),"普通RR快照仍旧，不能当关账当前退款事实");
                f.db.update("UPDATE refund_application SET status=1 WHERE id=903");
                assertEquals(List.of(902L,903L),f.originals.selectApprovedRegistrationRefundIdsForOriginalCloseForUpdate(11L),"同事务写后不得命中旧L1结果");
            });
        } finally {writer.shutdownNow();assertTrue(writer.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void inlineParentChildRollbackRetainsOriginalFactWithoutCreatingStandardInboxOrDone() {
        f.parentCompleteChildHalfState();
        assertEquals(0,f.count("wx_payment_callback_inbox"));assertEquals(0,f.count("wx_payment_callback_fact_link"));
        ReflectionTestUtils.setField(f.payment,"cmsTopicMapper",topicMapper());
        ReflectionTestUtils.setField(f.payment,"cmsActivityMapper",mock(CmsActivityMapper.class));
        ReflectionTestUtils.setField(f.payment,"verifiedPaymentFacts",f.facts);
        CoopSettlementClaimServiceImpl claims=new CoopSettlementClaimServiceImpl(
                mock(ICoopOrderSettlementSnapshotReader.class),mock(FulfillmentWindowResolver.class),
                mock(TopicSettlementEvidenceService.class),mock(ISettlementClaimService.class),
                mock(CoopSettlementMapper.class),mock(CoopSettlementDetailMapper.class),mock(CoopSettlementAdjustmentMapper.class));
        ReflectionTestUtils.setField(claims,"closedPaymentRecovery",f.recovery);
        ReflectionTestUtils.setField(f.payment,"coopSettlementClaimService",proxy(claims));
        CmsRegistrationServiceImpl inline=proxy(f.payment);
        var proof=f.facts.requireVerified(f.reference).facts();
        Map<String,String> result=new HashMap<>();
        result.put("trade_state","SUCCESS"); result.put("out_trade_no",proof.businessOutTradeNo());
        result.put("wx_out_trade_no",proof.wxOutTradeNo()); result.put("transaction_id",proof.transactionId());
        result.put("mchid",proof.mchid()); result.put("appid",proof.appid());
        result.put("trade_type",proof.tradeType()); result.put("currency",proof.currency());
        result.put("total",Long.toString(proof.totalFen()));
        result.put("verified_fact_id",Long.toString(f.reference.id())); result.put("verified_fact_hash",f.reference.hash());
        IllegalStateException blocked=assertThrows(IllegalStateException.class,()->f.tx.executeWithoutResult(
                status->inline.applyConfirmedWechatPaymentWithinTransaction(result)));
        assertEquals("TOPIC_CLOSED_PAID_HALF_STATE_RECOVERY_REQUIRED",blocked.getMessage());
        assertEquals(1,f.count("wx_payment_callback_outbox"));
        assertEquals(1L,f.db.queryForObject("SELECT biz_id FROM wx_payment_callback_outbox",Long.class));
        assertEquals("COOP_PAID_HALF_LATE_SUCCESS:"+f.reference.id()+":"+f.reference.hash(),f.db.queryForObject("SELECT idempotency_key FROM wx_payment_callback_outbox",String.class));
        assertEquals(4,f.db.queryForObject("SELECT state FROM wx_payment_callback_outbox",Integer.class));
        assertEquals(1,f.db.queryForObject("SELECT money_affecting FROM wx_payment_callback_outbox",Integer.class));
        assertEquals(0,f.count("wx_payment_callback_inbox"));assertEquals(0,f.count("wx_payment_callback_fact_link"));
        assertEquals(f.reference,f.facts.requireVerified(f.reference).reference());
        assertEquals(1,f.db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=2",Integer.class));
        assertEquals(0,f.count("coop_settlement_original_adjustment"));assertEquals(0,f.count("ums_member_balance_detail"));
    }

    void completeOriginalPaidHalfThroughVerifiedInbox() throws Exception {
        var sql=P0AcceptanceSchema.sql(f.source,"WxPaymentCallbackInboxMapper");var mapper=sql.getMapper(WxPaymentCallbackInboxMapper.class);
        WxPaymentCallbackClaimService claim=new WxPaymentCallbackClaimService();ReflectionTestUtils.setField(claim,"mapper",mapper);
        WxPaymentCallbackMutationService mutation=new WxPaymentCallbackMutationService();ReflectionTestUtils.setField(mutation,"mapper",mapper);
        ReflectionTestUtils.setField(mutation,"registrationMapper",f.registrations);ReflectionTestUtils.setField(mutation,"topicMapper",topicMapper());
        ReflectionTestUtils.setField(mutation,"activityMapper",mock(CmsActivityMapper.class));ReflectionTestUtils.setField(mutation,"refundMapper",mock(RefundApplicationMapper.class));
        WxPaymentCallbackInboxServiceImpl inbox=new WxPaymentCallbackInboxServiceImpl();ReflectionTestUtils.setField(inbox,"mapper",mapper);
        ReflectionTestUtils.setField(inbox,"claimService",proxy(claim));ReflectionTestUtils.setField(inbox,"mutationService",proxy(mutation));
        ReflectionTestUtils.setField(inbox,"verifiedFacts",f.facts);ReflectionTestUtils.setField(inbox,"alarmService",mock(AlarmService.class));
        IWxPaymentCallbackInboxService guarded=proxy(inbox);String notify="PAY:WXTX1:SUCCESS";
        guarded.registerVerified(notify,1,1L,"SUCCESS",f.reference);
        assertEquals(1,f.count("wx_payment_callback_fact_link"));
        assertTrue(guarded.processVerified(notify,f.reference,new IWxPaymentCallbackInboxService.Mutation(){
            public boolean isCurrent(WxPaymentCallbackInbox row){return row.getBizId().equals(1L)&&row.getBizType()==1;}
            public boolean apply(WxPaymentCallbackInbox row){
                CmsRegistration before=f.registrations.selectCmsRegistrationByIdForUpdate(1L);
                CmsRegistration after=ReflectionTestUtils.invokeMethod(f.payment,"repairHistoricalPaidHalfState",before,"WXTX1",money("10"),new Date());
                return after!=null&&after.getPaymentStatus()==2&&after.getRegistrationStatus()==2&&"WXTX1".equals(after.getTransactionId());
            }
        }));
        assertEquals(2,f.inboxState());assertEquals(2,f.registrationStatus());
    }
    @SuppressWarnings("unchecked") <T>T proxy(T target){ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(f.source),new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();}
    CmsTopicMapper topicMapper(){return (CmsTopicMapper)ReflectionTestUtils.getField(f.settlement,"cmsTopicMapper");}
    void activityRegistration(int id,int activity){f.db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,order_num,original_amount,total_amount,payable_amount,wechat_payment_amount,payment_status,registration_status,verification_status,purchase_kind,pay_expire_time) VALUES(?,?,2,?,21,10,77,1,10,10,10,10,4,3,0,1,'2026-10-01 08:00:00')",id,"REG"+id,activity);}
    void refund(int id,int registration,int deleted){f.db.update("INSERT INTO refund_application(id,refund_no,source_type,source_id,owner_type,owner_id,status,payout_status,refund_amount,paid_amount,del_flag) VALUES(?,?,'registration',?,1,11,1,1,10,10,?)",id,"RF"+id,registration,deleted);}
    CoopSettlementCashClose close(){CoopSettlementCashClose c=new CoopSettlementCashClose();c.setTopicId(11L);c.setCommercialContextType("TOPIC_EDITION");c.setCommercialContextId(11L);c.setCloseKind("POSITIVE_SALES");c.setInitiatorMemberId(7L);
        c.setOriginalSalesBasis(money("10"));c.setOriginalContributionCount(1);c.setOriginalVerifiedMerchantCount(0);c.setPlatformRate(BigDecimal.ZERO);c.setGuaranteeBaseRate(BigDecimal.ZERO);c.setGuaranteeStepRate(BigDecimal.ZERO);c.setGuaranteeMaxMerchant(0);
        c.setOriginCalculationVersion("LEGACY_SINGLE_ORDER_V1");c.setContractSnapshot("{\"fixture\":\"prior-original-source\"}");c.setSourceFingerprint("a".repeat(64));c.setClosedAt(new Date(1700000000123L));return c;}
    CoopSettlementCashContribution contribution(){CoopSettlementCashContribution c=new CoopSettlementCashContribution();c.setRegistrationId(1L);c.setPayerRegistrationId(1L);c.setRegistrationNo("REG1");c.setPayerOrderNo("REG1");c.setOwnerType(1);c.setOwnerId(11L);c.setSourceTopicId(11L);
        c.setSnapshotPaymentStatus(2);c.setSnapshotRegistrationStatus(2);c.setSnapshotVerificationStatus(0);c.setSnapshotPayableAmount(money("10"));c.setSalesBasisAmount(money("10"));c.setRegistrationSnapshot("{\"wechatPaymentAmount\":10.00,\"transactionId\":\"WXTX1\"}");c.setSourceFingerprint("b".repeat(64));return c;}
    static BigDecimal money(String value){return new BigDecimal(value).setScale(2);}
}

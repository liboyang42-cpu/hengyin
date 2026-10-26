package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.RegistrationPaymentConsumptionReadService;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import com.wechat.pay.java.service.refund.model.Amount;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.Status;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.mybatis.spring.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.chengyinhub.business.service.RestoreProviderJournal.require;

/** Existing refund factory assembly on a supplied owned datasource; its destructive lifecycle is NEVER run on restore. */
public final class RestoreRefundReconciliationFixture {
    private final RefundRetryAndRemainderMySqlIT original=new RefundRetryAndRemainderMySqlIT();
    private final RestoreProviderJournal external=new RestoreProviderJournal();
    private final DriverManagerDataSource source;
    private final JdbcTemplate db;
    private final DataSourceTransactionManager tx;
    private final IRefundService refunds;
    private final RefundPayoutServiceImpl payout;
    private final IWechatPayService provider;
    private final WechatVerifiedPaymentFactService facts;
    private final RefundApplicationMapper mapper;
    private final String phase;
    private final boolean gap;
    private int originalQueries;
    private Throwable originalQueryFailure;
    private RestoreRefundReconciliationFixture() throws Exception {
        String scope=System.getenv("CHENGYIN_RESTORE_SCOPE");require(scope!=null&&scope.matches("[a-f0-9]{32}"),"owned scope");
        require("github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))&&"true".equals(System.getenv("CHENGYIN_CI_RESTORE_BUSINESS")),"hosted admission");
        String schema="cy_restore_"+scope+"_funds_core_it";
        String prefix="jdbc:mysql://127.0.0.1:"+System.getenv("CHENGYIN_TEST_MYSQL_PORT")+"/";
        source=new DriverManagerDataSource(prefix+schema+"?connectionTimeZone=UTC&useSSL=false&allowPublicKeyRetrieval=true",System.getenv("CHENGYIN_TEST_MYSQL_USER"),System.getenv("CHENGYIN_TEST_MYSQL_PASSWORD"));
        phase=System.getenv().getOrDefault("CHENGYIN_RESTORE_PHASE","SOURCE");gap="refund-gap".equals(System.getenv("CHENGYIN_RESTORE_COHORT"));
        if(phase.equals("SOURCE")) {
            RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_IDENTITY);
            var admin=new JdbcTemplate(new DriverManagerDataSource(prefix+"?useSSL=false&allowPublicKeyRetrieval=true",source.getUsername(),source.getPassword()));
            require(admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema)==0,"never adopt existing schema");
            require(System.getenv("CHENGYIN_RESTORE_SOURCE_UUID").equals(admin.queryForObject("SELECT @@server_uuid",String.class)),"source UUID");
            RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_SCHEMA);
            admin.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");
            String base=Files.readString(Path.of("../sql/base_schema_20260818.sql"));var initial=new JdbcTemplate(source);
            for(String table:List.of("cms_registration","cms_topic","cms_activity","refund_application")) {
                int from=base.indexOf("CREATE TABLE `"+table+"` (");int close=base.indexOf("\n) ENGINE",from);int end=base.indexOf(';',close);
                require(from>=0&&close>from&&end>close,"exact original DDL");initial.execute(base.substring(from,end+1));
            }
            for(String file:List.of("migration_20260821_topic_team_config.sql","migration_20260919_cms_registration_selfplay_ticket_columns_nullable.sql",
                    "migration_20261023_wx_pay_order_intent.sql","migration_20260930_registration_first_successful_scan.sql",
                    "migration_merchant_aftercare_timeout_20260917.sql","migration_wx_payment_callback_inbox_outbox_20260725.sql","migration_20261024_wx_verified_payment_fact.sql","migration_20261024_registration_payment_fact.sql"))
                new ResourceDatabasePopulator(new FileSystemResource(Path.of("sql",file))).execute(source);
            initial.update("INSERT INTO cms_activity(id,member_id,topic_id,is_settlement,del_flag) VALUES(20,3,NULL,0,0)");
        }
        RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_ASSEMBLY);
        db=new JdbcTemplate(source);require(System.getenv("CHENGYIN_RESTORE_SOURCE_UUID").equals(db.queryForObject("SELECT @@server_uuid",String.class)),"phase UUID");
        tx=new DataSourceTransactionManager(source);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsRegistrationMapper.xml"),new ClassPathResource("mapper/business/CmsRegistrationPaymentFactMapper.xml"),
                new ClassPathResource("mapper/business/RefundApplicationMapper.xml"),new ClassPathResource("mapper/business/WxPaymentCallbackInboxMapper.xml"),
                new ClassPathResource("mapper/business/CmsActivityMapper.xml"),new ClassPathResource("mapper/business/CmsTopicMapper.xml"));
        var sql=new SqlSessionTemplate(factory.getObject());mapper=sql.getMapper(RefundApplicationMapper.class);facts=WechatVerifiedFactTestSupport.realFacts(source);
        var target=new RefundServiceImpl();
        set(target,"refundApplicationMapper",mapper);set(target,"cmsRegistrationMapper",sql.getMapper(CmsRegistrationMapper.class));
        var settlements=mock(SettlementDetailMapper.class);when(settlements.selectSettledDetails(anyString(),anyLong())).thenReturn(Collections.emptyList());set(target,"settlementDetailMapper",settlements);
        set(target,"cmsActivityMapper",sql.getMapper(CmsActivityMapper.class));set(target,"cmsTopicMapper",sql.getMapper(CmsTopicMapper.class));set(target,"verifiedPaymentFacts",facts);
        var claims=mock(ICoopSettlementClaimService.class);when(claims.hasFrozenOriginalRefundComponents(anyLong())).thenReturn(false);set(target,"coopSettlementClaimService",claims);
        set(target,"perkLedgerService",mock(IPerkLedgerService.class));set(target,"firstReviewPointsClawback",mock(FirstReviewPointsClawbackService.class));
        set(target,"merchantReferralBountyService",mock(MerchantReferralBountyService.class));set(target,"notifyService",mock(INotifyService.class));set(target,"alarmService",mock(AlarmService.class));
        var inbox=new WxPaymentCallbackInboxServiceImpl();set(inbox,"mapper",sql.getMapper(WxPaymentCallbackInboxMapper.class));set(inbox,"verifiedFacts",facts);set(inbox,"alarmService",mock(AlarmService.class));
        var claim=new WxPaymentCallbackClaimService();set(claim,"mapper",sql.getMapper(WxPaymentCallbackInboxMapper.class));set(inbox,"claimService",proxy(claim));
        var mutation=new WxPaymentCallbackMutationService();set(mutation,"mapper",sql.getMapper(WxPaymentCallbackInboxMapper.class));set(mutation,"registrationMapper",sql.getMapper(CmsRegistrationMapper.class));
        set(mutation,"activityMapper",sql.getMapper(CmsActivityMapper.class));set(mutation,"topicMapper",sql.getMapper(CmsTopicMapper.class));set(mutation,"refundMapper",mapper);set(inbox,"mutationService",proxy(mutation));
        set(target,"wxPaymentCallbackInboxService",proxy(inbox));refunds=proxy(target);
        provider=mock(IWechatPayService.class);
        when(provider.submitRefund(any(WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),any())).thenAnswer(call->{
            var identity=identity(call.getArgument(2)); external.append("REFUND","original_refund","SUBMIT","ACCEPTED",identity);
            external.append("REFUND","original_refund","RESULT",gap?"UNKNOWN":"SUCCESS",identity);
            return RefundRequestResult.UNKNOWN;
        });
        when(provider.queryRefundFacts(any(WechatVerifiedPaymentFact.Reference.class),anyString())).thenAnswer(call->{
            try {
                WechatVerifiedPaymentFact.Reference payment=call.getArgument(0);
                String no=call.getArgument(1);var retained=external.original("REFUND","original_refund");
                var paid=facts.requireVerified(payment);
                requireOriginalRefund(paid,mapper.selectByRefundNo(no),retained,no);
                String state=external.state("REFUND","original_refund");
                external.append("REFUND","original_refund","QUERY",state,retained);originalQueries++;
                if(!"SUCCESS".equals(state))return Map.of("outRefundNo",no,"status","UNKNOWN");
                if(findRetainedRefund(retained)!=null)return retainedCallback(mapper.selectByRefundNo(no));
                return observeOriginalRefund(paid,retained,no,state);
            }catch(Exception unavailable){originalQueryFailure=unavailable;throw unavailable;}
        });
        payout=new RefundPayoutServiceImpl();set(payout,"refundApplicationMapper",mapper);set(payout,"cmsRegistrationMapper",sql.getMapper(CmsRegistrationMapper.class));
        set(payout,"refundService",refunds);set(payout,"wechatPayService",provider);set(payout,"payoutTransactions",tx);
        set(payout,"paymentConsumption",proxy(new RegistrationPaymentConsumptionReadService(sql.getMapper(CmsRegistrationPaymentFactMapper.class))));
        for(var entry:Map.of("db",db,"dataSource",source,"transactions",tx,"factsReader",facts,"consumptionMapper",sql.getMapper(CmsRegistrationPaymentFactMapper.class),
                "service",refunds,"payouts",payout,"wechat",provider,"settlements",settlements,"schemaRoot",Path.of("..")).entrySet())set(original,entry.getKey(),entry.getValue());
    }
    private Map<String,Object> identity(String no) {
        var row=db.queryForMap("SELECT id,source_id,refund_no,refund_amount FROM refund_application WHERE refund_no=?",no);
        var receipt=db.queryForMap("SELECT payment_fact_id,payment_fact_hash,registration_no,transaction_id FROM cms_registration_payment_fact WHERE registration_id=99");
        var paid=facts.requireVerified(new WechatVerifiedPaymentFact.Reference(((Number)receipt.get("payment_fact_id")).longValue(),(String)receipt.get("payment_fact_hash"))).facts();
        var value=new LinkedHashMap<String,Object>();value.put("refundId",((Number)row.get("id")).longValue());value.put("registrationId",99);
        value.put("refundNo",no);value.put("businessNo",paid.businessOutTradeNo());value.put("refundFen",new BigDecimal(row.get("refund_amount").toString()).movePointRight(2).longValueExact());
        value.put("paymentFactId",((Number)receipt.get("payment_fact_id")).longValue());value.put("paymentFactHash",receipt.get("payment_fact_hash"));value.put("paymentTransactionId",receipt.get("transaction_id"));
        value.put("providerRefundId","WX-"+no);value.put("mchid",paid.mchid());value.put("appid",paid.appid());value.put("currency",paid.currency());value.put("totalFen",paid.totalFen());
        return value;
    }
    /** The controlled provider journal is outside the backup. It is not itself a local verified fact. */
    static void requireOriginalRefund(WechatVerifiedPaymentFactService.Stored paid,RefundApplication refund,
            Map<String,Object> retained,String requestedNo) {
        require(paid!=null&&refund!=null,"original refund and payment must exist");
        var payment=paid.facts();payment.requireValid();
        require(payment.kind()==WechatVerifiedPaymentFact.Kind.PAYMENT
                &&paid.reference().hash().equals(payment.factHash()),"original payment provenance");
        require(refund.getId()!=null&&refund.getId()>0&&refund.getSourceId()!=null&&refund.getSourceId()>0&&refund.getRefundAmount()!=null
                &&"registration".equals(refund.getSourceType())
                &&requestedNo!=null&&requestedNo.equals(refund.getRefundNo())&&requestedNo.equals(retained.get("refundNo"))
                &&refund.getId()==originalLong(retained,"refundId")&&refund.getSourceId()==originalLong(retained,"registrationId")
                &&refund.getRefundAmount().movePointRight(2).longValueExact()==originalLong(retained,"refundFen"),"original refund identity/amount");
        require(paid.reference().id()==originalLong(retained,"paymentFactId")
                &&paid.reference().hash().equals(retained.get("paymentFactHash"))
                &&payment.businessOutTradeNo().equals(retained.get("businessNo"))
                &&payment.transactionId().equals(retained.get("paymentTransactionId"))
                &&payment.mchid().equals(retained.get("mchid"))&&payment.appid().equals(retained.get("appid"))
                &&payment.currency().equals(retained.get("currency"))&&payment.totalFen()==originalLong(retained,"totalFen"),"original payment/refund binding");
        require(retained.get("providerRefundId") instanceof String id&&id.matches("[A-Za-z0-9_-]{1,64}")
                &&originalLong(retained,"refundFen")>0&&originalLong(retained,"refundFen")<=payment.totalFen(),"original provider refund identity");
    }
    private static long originalLong(Map<String,Object> retained,String key) {
        Object value=retained.get(key);require(value instanceof Number,"original numeric identity required");
        return new BigDecimal(value.toString()).longValueExact();
    }
    /** SDK boundary fixture only: no success without the independently retained RESULT=SUCCESS. */
    static Refund originalRefundResponse(WechatVerifiedPaymentFactService.Stored paid,RefundApplication refund,
            Map<String,Object> retained,String requestedNo,String state) {
        requireOriginalRefund(paid,refund,retained,requestedNo);
        require("SUCCESS".equals(state),"UNKNOWN is not an original success observation");
        var response=new Refund();response.setOutRefundNo(requestedNo);response.setRefundId((String)retained.get("providerRefundId"));
        response.setOutTradeNo(paid.facts().wxOutTradeNo());response.setTransactionId(paid.facts().transactionId());
        response.setStatus(Status.SUCCESS);response.setSuccessTime(WechatVerifiedFactTestSupport.SUCCESS_TIME);
        var amount=new Amount();amount.setCurrency(paid.facts().currency());amount.setTotal(paid.facts().totalFen());amount.setRefund(originalLong(retained,"refundFen"));response.setAmount(amount);
        return response;
    }
    private Map<String,String> observeOriginalRefund(WechatVerifiedPaymentFactService.Stored paid,
            Map<String,Object> retained,String no,String state) {
        Refund response=originalRefundResponse(paid,mapper.selectByRefundNo(no),retained,no,state);
        var payments=mock(JsapiService.class);
        when(payments.queryOrderByOutTradeNo(any())).thenAnswer(call->{
            com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest request=call.getArgument(0);
            require(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"original SDK query outside business transaction");
            require(paid.facts().mchid().equals(request.getMchid())&&paid.facts().wxOutTradeNo().equals(request.getOutTradeNo()),"original payment SDK query");
            var original=new Transaction();original.setMchid(paid.facts().mchid());original.setAppid(paid.facts().appid());
            original.setOutTradeNo(paid.facts().wxOutTradeNo());original.setTransactionId(paid.facts().transactionId());
            original.setTradeType(Transaction.TradeTypeEnum.valueOf(paid.facts().tradeType()));original.setTradeState(Transaction.TradeStateEnum.REFUND);
            original.setSuccessTime(paid.facts().successAt().toString());var total=new TransactionAmount();total.setTotal(Math.toIntExact(paid.facts().totalFen()));total.setCurrency(paid.facts().currency());original.setAmount(total);return original;
        });
        var refundSdk=mock(com.wechat.pay.java.service.refund.RefundService.class);
        when(refundSdk.queryByOutRefundNo(any())).thenAnswer(call->{
            com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest request=call.getArgument(0);
            require(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"original refund SDK query outside business transaction");
            require(no.equals(request.getOutRefundNo()),"original refund SDK query");return response;
        });
        var gateway=WechatVerifiedFactTestSupport.realPaymentGuards(source,paid.facts().mchid(),facts);
        WechatVerifiedFactTestSupport.bind(gateway,payments,refundSdk,paid.facts().mchid());
        Map<String,String> observed=gateway.queryRefundFacts(paid.reference(),no);
        verify(payments).queryOrderByOutTradeNo(any());verify(refundSdk).queryByOutRefundNo(any());verifyNoMoreInteractions(payments,refundSdk);
        require(observed!=null&&"SUCCESS".equals(observed.get("status")),"original gateway observation unavailable; keep gap held");
        var stored=findRetainedRefund(retained);require(stored!=null,"original gateway observation must commit before callback replay");
        require(stored.reference().id()==Long.parseLong(observed.get("verified_fact_id"))
                &&stored.reference().hash().equals(observed.get("verified_fact_hash")),"original observation reference readback");
        return observed;
    }
    private WechatVerifiedPaymentFactService.Stored findRetainedRefund(Map<String,Object> retained) {
        return facts.findRefundSuccess((String)retained.get("mchid"),(String)retained.get("refundNo"),(String)retained.get("providerRefundId"));
    }
    public static void main(String[] args)throws Exception{
        try {
            require(args.length==0,"fixed child");var fixture=new RestoreRefundReconciliationFixture();
            RestoreChildDiagnostics.stage("SOURCE".equals(fixture.phase) ? RestoreChildDiagnostics.Stage.SOURCE_TIMELINE
                    : RestoreChildDiagnostics.Stage.OWNER_OBSERVATION);
            fixture.run();
        } catch (Throwable failure) { RestoreChildDiagnostics.report(failure); System.exit(1); }
    }
    private void run()throws Exception {
        if(phase.equals("SOURCE")) {
            ReflectionTestUtils.invokeMethod(original,"paid",99L,null,"68.00");
            RefundApplication request=ReflectionTestUtils.invokeMethod(original,"save","20.00");
            var review=new RefundReviewDTO();review.setId(request.getId());review.setStatus(1);review.setReviewReason("restore fixture original");
            require(refunds.reviewRefund(review,7L)==1,"real original refund approval");payout.payoutAfterReview(request.getId());
            if(!gap) {
                Map<String,String> result=ReflectionTestUtils.invokeMethod(original,"facts",mapper.selectRefundApplicationById(request.getId()));
                require(refunds.handleRefundNotify(result),"real verified original refund callback commits");
            }else {
                // Separate journal validation from the SQL predicate: failure here is not evidence of a REFUND row.
                var retained=external.original("REFUND","original_refund");
                var paid=facts.requireVerified(new WechatVerifiedPaymentFact.Reference(originalLong(retained,"paymentFactId"),(String)retained.get("paymentFactHash")));
                var persisted=mapper.selectRefundApplicationById(request.getId());
                requireOriginalRefund(paid,persisted,retained,persisted.getRefundNo());
                verify(provider).submitRefund(eq(paid.reference()),eq(paid.facts().businessOutTradeNo()),eq(persisted.getRefundNo()),
                        eq(persisted.getRefundAmount()),eq(BigDecimal.valueOf(paid.facts().totalFen(),2)),any());
                verify(provider,never()).queryRefundFacts(any(WechatVerifiedPaymentFact.Reference.class),anyString());
                require(originalQueries==0&&originalQueryFailure==null,"gap source must retain the first UNKNOWN without a recovery query");
                var stored=findRetainedRefund(retained);
                require(stored==null,"gap backup must not contain a local refund success");
                require("UNKNOWN".equals(external.state("REFUND","original_refund")),"gap success must arrive only after backup");
            }
            signal("BACKUP_READY");
            try(var input=new BufferedReader(new InputStreamReader(System.in))) {
                require("AFTER_BACKUP".equals(input.readLine()),"fixed timeline");
                if(gap)external.append("REFUND","original_refund","RESULT","SUCCESS",identity(request.getRefundNo()));
                signal("FAULT_READY");require("STOP".equals(input.readLine()),"fixed stop");
            }return;
        }
        var rows=db.queryForList("SELECT * FROM refund_application ORDER BY id");
        boolean restoring=Set.of("RESTORED_OWNER","RESTORED_INVALID_OWNER","RESTORED_EMPTY_OWNER").contains(phase);
        if(restoring) {
            RefundApplication request=new RefundApplication();request.setSourceType("registration");request.setSourceId(99L);request.setRefundAmount(BigDecimal.ONE);request.setReason("restore must refuse new intent");
            boolean blocked=false;try{refunds.saveBackOfficeRefund(request);}catch(RestoreDispatchGuard.Blocked failure){require("RECOVERY_DISPATCH_BLOCKED".equals(failure.getMessage()),"original Blocked code");blocked=true;}
            require(blocked,"actual refund new-intent owner must propagate restore Blocked");
            var payment=facts.findPaymentSuccess("REG99");require(payment!=null,"original payment reference");
            String refundNo=(String)rows.getFirst().get("refund_no");boolean consumerBlocked=false;
            try{WechatPayTestWiring.facade().submitRefund(payment.reference(),"REG99",refundNo,new BigDecimal("20.00"),new BigDecimal("68.00"),"restore original consumer guard");}
            catch(RestoreDispatchGuard.Blocked failure){require("RECOVERY_DISPATCH_BLOCKED".equals(failure.getMessage()),"original consumer Blocked code");consumerBlocked=true;}
            require(consumerBlocked,"actual refund SDK owner converted restore Blocked into NOT_SENT/UNKNOWN");
        }else require(phase.equals("NORMAL_READBACK")&&!gap,"normal only complete original");
        RefundApplication existing=mapper.selectRefundApplicationById(((Number)rows.getFirst().get("id")).longValue());
        // Original terminal payout and verified callback replay remain legal; cannot enter provider a second time.
        if(!gap || restoring) {
            payout.payoutAfterReview(existing.getId());
            if(gap&&restoring) {
                if(originalQueryFailure!=null)throw new IllegalStateException("original refund observation unavailable; gap remains held",originalQueryFailure);
                require(originalQueries>0,"gap owner must query its original payment reference before replay");
            }
            boolean ack=refunds.handleRefundNotify(retainedCallback(existing));
            require(ack==!restoring,"actual callback must pause after registration during restore, normal boot replays DONE");
        }
        require(rows.equals(db.queryForList("SELECT * FROM refund_application ORDER BY id")),"original economic refund full rows changed");
        var evidence=new LinkedHashMap<String,Object>(Map.of("owner","RefundServiceImpl/RefundPayoutServiceImpl","phase",phase,"newDispatchBlocked",restoring,"originalReadback",true,"sameTerminalRows",true));
        evidence.put("blockedMethods",restoring?List.of("RefundServiceImpl.saveBackOfficeRefund","WechatPaymentSdkGateway.submitRefund"):List.of());
        RestoreProviderJournal.evidence(evidence);
    }
    private Map<String,String> retainedCallback(RefundApplication original) throws Exception {
        var retained=external.original("REFUND","original_refund");
        var payment=facts.requireVerified(new WechatVerifiedPaymentFact.Reference(originalLong(retained,"paymentFactId"),(String)retained.get("paymentFactHash")));
        requireOriginalRefund(payment,original,retained,original.getRefundNo());
        var stored=findRetainedRefund(retained);
        return retainedRefundCallback(payment,original,retained,stored);
    }
    static Map<String,String> retainedRefundCallback(WechatVerifiedPaymentFactService.Stored payment,RefundApplication original,
            Map<String,Object> retained,WechatVerifiedPaymentFactService.Stored stored) {
        requireOriginalRefund(payment,original,retained,original.getRefundNo());
        require(stored!=null,"read original persisted refund fact; never recreate it for replay");
        var fact=stored.facts();fact.requireValid();
        require(fact.kind()==WechatVerifiedPaymentFact.Kind.REFUND&&stored.reference().hash().equals(fact.factHash())
                &&fact.mchid().equals(retained.get("mchid"))&&fact.appid().equals(retained.get("appid"))
                &&fact.merchantEventNo().equals(retained.get("refundNo"))&&fact.providerId().equals(retained.get("providerRefundId"))
                &&fact.businessOutTradeNo().equals(payment.facts().businessOutTradeNo())&&fact.wxOutTradeNo().equals(payment.facts().wxOutTradeNo())
                &&fact.transactionId().equals(payment.facts().transactionId())&&fact.tradeType().equals(payment.facts().tradeType())
                &&fact.currency().equals(payment.facts().currency())&&fact.amountFen()==originalLong(retained,"refundFen")
                &&fact.totalFen()==payment.facts().totalFen()&&Objects.equals(fact.intentRequestHash(),payment.facts().intentRequestHash()),"retained refund fact is not the original observation");
        var value=new LinkedHashMap<String,String>();
        value.put("outRefundNo",fact.merchantEventNo());value.put("outTradeNo",fact.businessOutTradeNo());value.put("status","SUCCESS");
        value.put("refund",Long.toString(fact.amountFen()));value.put("total",Long.toString(fact.totalFen()));value.put("currency",fact.currency());
        value.put("mchid",fact.mchid());value.put("appid",fact.appid());value.put("tradeType",fact.tradeType());value.put("transactionId",fact.transactionId());
        value.put("wxOutTradeNo",fact.wxOutTradeNo());value.put("refundId",fact.providerId());
        value.put("verified_fact_id",Long.toString(stored.reference().id()));value.put("verified_fact_hash",stored.reference().hash());return value;
    }
    private <T>T proxy(T target){var p=new ProxyFactory(target);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(tx,new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    private static void set(Object target,String field,Object value){ReflectionTestUtils.setField(target,field,value);}
    private static void signal(String p){System.out.println("RESTORE_BUSINESS_PHASE:"+p);System.out.flush();}
}

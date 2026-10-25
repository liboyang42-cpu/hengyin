package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.RefundReviewDTO;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.RegistrationPaymentConsumptionReadService;
import com.chengyinhub.common.config.RestoreDispatchGuard;
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
    private final WechatVerifiedPaymentFactService facts;
    private final RefundApplicationMapper mapper;
    private final String phase;
    private final boolean gap;
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
        var provider=mock(IWechatPayService.class);
        when(provider.submitRefund(any(WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),any())).thenAnswer(call->{
            var identity=identity(call.getArgument(2)); external.append("REFUND","original_refund","SUBMIT","ACCEPTED",identity);
            external.append("REFUND","original_refund","RESULT",gap?"UNKNOWN":"SUCCESS",identity);
            return RefundRequestResult.UNKNOWN;
        });
        when(provider.queryRefundFacts(any(WechatVerifiedPaymentFact.Reference.class),anyString())).thenAnswer(call->{
            String no=call.getArgument(1);var identity=identity(no);var retained=external.original("REFUND","original_refund");
            require(com.alibaba.fastjson2.JSON.toJSONString(identity,com.alibaba.fastjson2.JSONWriter.Feature.MapSortField).equals(
                    com.alibaba.fastjson2.JSON.toJSONString(retained,com.alibaba.fastjson2.JSONWriter.Feature.MapSortField)),"original refund binding");
            String state=external.state("REFUND","original_refund");external.append("REFUND","original_refund","QUERY",state,identity);
            if(!"SUCCESS".equals(state))return Map.of("outRefundNo",no,"status","UNKNOWN");
            if(facts.findRefundSuccess("merchant",no,"WX-"+no)!=null)return retainedCallback(mapper.selectByRefundNo(no));
            return ARefundFactFixture.bindSuccess(facts,Map.of("outRefundNo",no,"outTradeNo","REG99","status","SUCCESS","refund","2000","total","6800","currency","CNY"),"TX99");
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
        var value=new LinkedHashMap<String,Object>();value.put("refundId",((Number)row.get("id")).longValue());value.put("registrationId",99);
        value.put("refundNo",no);value.put("businessNo","REG99");value.put("refundFen",new BigDecimal(row.get("refund_amount").toString()).movePointRight(2).longValueExact());
        value.put("paymentFactId",((Number)receipt.get("payment_fact_id")).longValue());value.put("paymentFactHash",receipt.get("payment_fact_hash"));value.put("paymentTransactionId",receipt.get("transaction_id"));
        value.put("providerRefundId","WX-"+no);value.put("mchid","merchant");value.put("appid","wx-original");value.put("currency","CNY");value.put("totalFen",6800);
        return value;
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
            boolean ack=refunds.handleRefundNotify(retainedCallback(existing));
            require(ack==!restoring,"actual callback must pause after registration during restore, normal boot replays DONE");
        }
        require(rows.equals(db.queryForList("SELECT * FROM refund_application ORDER BY id")),"original economic refund full rows changed");
        var evidence=new LinkedHashMap<String,Object>(Map.of("owner","RefundServiceImpl/RefundPayoutServiceImpl","phase",phase,"newDispatchBlocked",restoring,"originalReadback",true,"sameTerminalRows",true));
        evidence.put("blockedMethods",restoring?List.of("RefundServiceImpl.saveBackOfficeRefund","WechatPaymentSdkGateway.submitRefund"):List.of());
        RestoreProviderJournal.evidence(evidence);
    }
    private Map<String,String> retainedCallback(RefundApplication original) {
        var stored=facts.findRefundSuccess("merchant",original.getRefundNo(),"WX-"+original.getRefundNo());
        require(stored!=null,"read original persisted refund fact; never recreate it for replay");
        var fact=stored.facts();var value=new LinkedHashMap<String,String>();
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

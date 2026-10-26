package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.config.WxPayConfig;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.chengyinhub.business.service.RestoreProviderJournal.require;

/** Fixed C bridge to B's EXACT Store, not a second withdrawal engine. Source dependency supplied by root. */
public final class RestoreWithdrawalReconciliationFixture {
    private final RestoreProviderJournal external = new RestoreProviderJournal();
    private final FundsRecoveryProcessProbe.Store store;
    private final String phase;
    private final boolean gap;
    private final String schema;
    private final DriverManagerDataSource source;
    private RestoreWithdrawalReconciliationFixture() throws Exception {
        require("github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT")) && "true".equals(System.getenv("CHENGYIN_CI_RESTORE_BUSINESS")),"hosted restore admission");
        String scope=System.getenv("CHENGYIN_RESTORE_SCOPE");
        schema="cy_restore_"+scope+"_funds_core_it";
        require(scope!=null && scope.matches("[a-f0-9]{32}"),"owned scope");
        int port=Integer.parseInt(System.getenv("CHENGYIN_TEST_MYSQL_PORT"));
        require("127.0.0.1".equals(System.getenv("CHENGYIN_TEST_MYSQL_HOST")),"loopback only");
        String url="jdbc:mysql://127.0.0.1:"+port+"/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
        source=new DriverManagerDataSource(url,System.getenv("CHENGYIN_TEST_MYSQL_USER"),System.getenv("CHENGYIN_TEST_MYSQL_PASSWORD"));
        phase=System.getenv().getOrDefault("CHENGYIN_RESTORE_PHASE","SOURCE");
        gap="withdrawal-gap".equals(System.getenv("CHENGYIN_RESTORE_COHORT"));
        if(phase.equals("SOURCE")) {
            RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_IDENTITY);
            var admin=new JdbcTemplate(new DriverManagerDataSource(url.replace("/"+schema+"?","/?"),source.getUsername(),source.getPassword()));
            require(admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema)==0,"never adopt a schema");
            require(System.getenv("CHENGYIN_RESTORE_SOURCE_UUID").equals(admin.queryForObject("SELECT @@server_uuid",String.class)),"source UUID");
            RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_SCHEMA);
            admin.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");
            try(var c=source.getConnection()) {
                ScriptUtils.executeSqlScript(c,new ClassPathResource("architecture-day-a-fixture.sql"));
                for(String migration:List.of("migration_20261023_cash_withdraw_fund_fact.sql","migration_20261024_cash_withdraw_transfer_intent.sql","migration_20261025_cash_withdraw_fund_request_compat.sql"))
                    ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("sql",migration)));
            }
        }
        RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_ASSEMBLY);
        store=new FundsRecoveryProcessProbe.Store(source);
        require(System.getenv("CHENGYIN_RESTORE_SOURCE_UUID").equals(store.db.queryForObject("SELECT @@server_uuid",String.class)),"exact phase UUID");
    }
    public static void main(String[] args) throws Exception {
        try {
            require(args.length==0,"fixed child"); var fixture=new RestoreWithdrawalReconciliationFixture();
            RestoreChildDiagnostics.stage("SOURCE".equals(fixture.phase) ? RestoreChildDiagnostics.Stage.SOURCE_TIMELINE
                    : RestoreChildDiagnostics.Stage.OWNER_OBSERVATION);
            fixture.run();
        } catch (Throwable failure) { RestoreChildDiagnostics.report(failure); System.exit(1); }
    }
    private ICashWithdrawService owner(long member) throws Exception {
        var observer=new FundsRecoveryProcessProbe.GateObserver(store,"restore-observation",FundsRecoveryProcessProbe.Gate.NONE,
                source.getUrl(),source.getUsername(),source.getPassword());
        var service=store.service(member,observer,false,false);
        var provider=mock(IWechatPayService.class);
        when(provider.transferToBalance(any(CashWithdrawTransferIntent.class))).thenAnswer(call->{
            CashWithdrawTransferIntent intent=call.getArgument(0);store.validateRequest(intent);
            require(Long.valueOf(member).equals(store.cash.selectById(intent.withdrawalId()).getMemberId()),"original provider actor");
            String name=name(intent.memberId()==null?member:intent.memberId());
            external.append("WITHDRAWAL",name,"SUBMIT","ACCEPTED",identity(intent));
            external.append("WITHDRAWAL",name,"RESULT",name.contains("unknown")?"UNKNOWN":"SUCCESS",identity(intent));
            return Map.of("ok","true","batchStatus","ACCEPTED","batchId",FundsRecoveryProcessProbe.batch(intent));
        });
        when(provider.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenAnswer(call->{
            CashWithdrawTransferIntent intent=call.getArgument(0); store.validateRequest(intent);
            String name=name(member); var original=external.original("WITHDRAWAL",name);
            requireSameProviderIdentity(identity(intent),original);
            String state=external.state("WITHDRAWAL",name);
            external.append("WITHDRAWAL",name,"QUERY",state,identity(intent));
            return "SUCCESS".equals(state)?FundsRecoveryProcessProbe.terminal(intent,"SUCCESS"):Map.of("result","UNKNOWN");
        });
        return bindProvider(service,provider);
    }

    static ICashWithdrawService bindProvider(ICashWithdrawService service,IWechatPayService provider) {
        require(service!=null && provider!=null && org.springframework.aop.support.AopUtils.isAopProxy(service),"original transactional owner required");
        // Keep generic unwrapping separate from setField's Object/Class overloads. An inline invocation
        // can infer Class and cast the real service before reflection even inspects its existing field.
        Object target=AopTestUtils.getUltimateTargetObject(service);
        require(target instanceof CashWithdrawServiceImpl,"original cash owner required");
        ReflectionTestUtils.setField(target,"wechatPayService",provider);
        require(ReflectionTestUtils.getField(target,"wechatPayService")==provider,"typed provider binding not installed");
        return service; // Retain ALL original advice/transaction boundaries; never return the unwrapped target.
    }

    static void requireSameProviderIdentity(Map<String,Object> expected,Map<String,Object> recorded) {
        require(expected!=null && recorded!=null && com.alibaba.fastjson2.JSON.toJSONString(expected,
                com.alibaba.fastjson2.JSONWriter.Feature.MapSortField).equals(com.alibaba.fastjson2.JSON.toJSONString(recorded,
                com.alibaba.fastjson2.JSONWriter.Feature.MapSortField)),"original provider identity");
    }
    private String name(long member) {return switch((int)member){case 61->"ledger_success";case 62->"master_success";case 63->"ledger_unknown";case 64->"lost_ledger_success";default->throw new IllegalArgumentException("fixed actor");};}
    private Map<String,Object> identity(CashWithdrawTransferIntent intent) {
        var value=new LinkedHashMap<String,Object>();
        value.put("withdrawalId",intent.withdrawalId());value.put("source",intent.originalSource().name());
        value.put("memberId",store.cash.selectById(intent.withdrawalId()).getMemberId());
        value.put("submissionIntentId",intent.submissionIntentId());value.put("requestVersion",intent.requestVersion());
        value.put("requestHash",intent.requestHash());value.put("wireHash",FundsRecoveryProcessProbe.wireHash(intent));
        value.put("mchid",intent.mchid());value.put("appid",intent.appid());value.put("outBatchNo",intent.outBatchNo());
        value.put("outDetailNo",intent.outDetailNo());value.put("amountFen",intent.amountFen());
        value.put("openid",intent.openid());value.put("remark",intent.effectiveRemark());
        var snapshot=store.intents.selectByWithdrawId(intent.withdrawalId());
        value.put("masterRequestHash",snapshot==null?null:snapshot.requestHash());
        value.put("origin",intent.hasDebitSnapshot()?"HISTORICAL_MASTER_ONLY:original-TransferIntent9/B-history-fixture":"REAL_ADMISSION_AND_SUBMIT");
        return value;
    }
    private void source() throws Exception {
        store.db.update("INSERT INTO ums_member(id,balance) VALUES(61,100),(62,100),(63,100),(64,100)");
        store.db.update("INSERT INTO merchant_referral_bounty(id,inviter_member_id,status,bounty_amount,frozen_until,del_flag) VALUES(1,61,0,10,DATE_ADD(NOW(),INTERVAL 1 DAY),'0'),(2,62,0,7,DATE_ADD(NOW(),INTERVAL 1 DAY),'0')");
        store.db.update("INSERT INTO player_withdrawal_cooldown(id,member_id,amount,cooldown_until) VALUES(1,61,5,DATE_ADD(NOW(),INTERVAL 1 DAY))");
        owner(61).withdraw(61L,new BigDecimal("30.00"));owner(61).requery(store.original(61).getId());
        // Explicit pre-integration original. Never manufacture FUND_FACT to pretend this is current admission.
        MemberCashWithdraw historical=new MemberCashWithdraw();historical.setMemberId(62L);historical.setOpenid("historical-openid");
        historical.setAmount(new BigDecimal("30.00"));historical.setStatus(0);historical.setFrozen(0);historical.setDelFlag(0);
        require(store.cash.insertMemberCashWithdraw(historical)==1 && historical.getId()!=null,"one explicit historical original master");
        historical.setOutBatchNo("CW"+historical.getId());historical.setOutDetailNo("CD"+historical.getId());
        require(store.cash.updateMemberCashWithdraw(historical)==1,"retain original historical CW/CD");
        var original=new TransactionTemplate(store.tx).execute(tx->{
            MemberCashWithdraw locked=store.cash.selectByIdForUpdate(historical.getId());
            require(store.members.deductWithdrawableBalance(62L,new BigDecimal("30.00"),new BigDecimal("7"))==1,"historical actual balance SQL with retained frozen basis");
            var record=CashWithdrawTransferIntent.create(locked,"historical-mch","historical-app","historical remark",new BigDecimal("100.00"),new BigDecimal("70.00"));
            require(store.intents.insert(record)==1,"historical real immutable mapper");
            require(store.intents.markSubmitted(record.withdrawalId(),record.requestHash())==1,"historical original submitted marker");
            return store.intents.selectByWithdrawId(record.withdrawalId());
        });
        external.append("WITHDRAWAL","master_success","HISTORICAL_ACCEPTANCE","SUCCESS",identity(original));
        owner(62).requery(original.withdrawalId());
        if(gap) owner(63).withdraw(63L,new BigDecimal("30.00"));
        signal("BACKUP_READY");
        try(var input=new BufferedReader(new InputStreamReader(System.in))) {
            require("AFTER_BACKUP".equals(input.readLine()),"fixed next phase");
            if(gap){owner(64).withdraw(64L,new BigDecimal("30.00"));owner(64).requery(store.original(64).getId());}
            signal("FAULT_READY");require("STOP".equals(input.readLine()),"fixed stop");
        }
    }
    private void run() throws Exception {
        if(phase.equals("SOURCE")){source();return;}
        var before=store.db.queryForList("SELECT * FROM member_cash_withdraw ORDER BY id");
        boolean restoring=Set.of("RESTORED_OWNER","RESTORED_INVALID_OWNER","RESTORED_EMPTY_OWNER").contains(phase);
        if(restoring) {
            // Must originate from the REAL production owner, not from this fixture calling a guard helper itself.
            boolean blocked=false;
            try{owner(64).withdraw(64L,new BigDecimal("30.00"));}
            catch(RestoreDispatchGuard.Blocked failure){require("RECOVERY_DISPATCH_BLOCKED".equals(failure.getMessage()),"original Blocked code");blocked=true;}
            require(blocked,"actual restored withdrawal owner did not propagate Blocked");
            boolean creditBlocked=false;
            try{owner(61).manualRefund(store.original(61).getId(),"restore-maker","must not credit",null);}
            catch(RestoreDispatchGuard.Blocked failure){require("RECOVERY_DISPATCH_BLOCKED".equals(failure.getMessage()),"original credit Blocked code");creditBlocked=true;}
            require(creditBlocked,"actual original credit admission was not paused");
            var original=store.cash.selectFundFacts(store.original(61).getId()).stream()
                    .filter(f->"SUBMIT_INTENT".equals(f.getKind())).findFirst().orElseThrow().transferIntent();
            var gateway=WechatPayTestWiring.facade();boolean consumerBlocked=false;
            try{gateway.transferToBalance(original);}catch(RestoreDispatchGuard.Blocked failure){
                require("RECOVERY_DISPATCH_BLOCKED".equals(failure.getMessage()),"original consumer Blocked code");consumerBlocked=true;}
            require(consumerBlocked,"actual transfer SDK owner converted restore Blocked into NOT_SENT/UNKNOWN");
        } else require(phase.equals("NORMAL_READBACK")&&!gap,"normal boot only complete cohort");
        owner(61).requery(store.original(61).getId()); owner(62).requery(store.original(62).getId());
        if(gap)require(owner(63).hasPendingWithdraw(63L),"original unresolved withdrawal remains readable/pending, never NOT_SENT or completed");
        require(before.equals(store.db.queryForList("SELECT * FROM member_cash_withdraw ORDER BY id")),"terminal original replay changed full rows");
        var proof=new LinkedHashMap<String,Object>();proof.put("owner","CashWithdrawServiceImpl");proof.put("phase",phase);
        proof.put("newDispatchBlocked",restoring);proof.put("originalReadback",true);proof.put("sameTerminalRows",true);
        proof.put("blockedMethods",restoring?List.of("CashWithdrawServiceImpl.withdraw","CashWithdrawServiceImpl.manualRefund","WechatPaymentSdkGateway.transferToBalance"):List.of());
        RestoreProviderJournal.evidence(proof);
    }
    private static void signal(String value){System.out.println("RESTORE_BUSINESS_PHASE:"+value);System.out.flush();}
}

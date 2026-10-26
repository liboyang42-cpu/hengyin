package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.RestoreProviderJournal;
import com.chengyinhub.business.service.RestoreChildDiagnostics;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.mybatis.spring.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static com.chengyinhub.business.service.RestoreProviderJournal.require;

/** Original enrollment identity/status; no invented provider search or payment-style fingerprint. */
public final class RestoreVoiceReconciliationFixture {
    private final RestoreProviderJournal external=new RestoreProviderJournal();
    private final JdbcTemplate db;
    private final NpcVoiceEnrollmentService owner;
    private final FixtureVoiceProvider provider;
    private final String phase;
    private final boolean gap;
    private RestoreVoiceReconciliationFixture()throws Exception {
        String scope=System.getenv("CHENGYIN_RESTORE_SCOPE");require(scope!=null&&scope.matches("[a-f0-9]{32}"),"owned scope");
        require("github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))&&"true".equals(System.getenv("CHENGYIN_CI_RESTORE_BUSINESS")),"hosted admission");
        String schema="cy_restore_"+scope+"_funds_core_it",prefix="jdbc:mysql://127.0.0.1:"+System.getenv("CHENGYIN_TEST_MYSQL_PORT")+"/";
        var source=new DriverManagerDataSource(prefix+schema+"?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC",System.getenv("CHENGYIN_TEST_MYSQL_USER"),System.getenv("CHENGYIN_TEST_MYSQL_PASSWORD"));
        phase=System.getenv().getOrDefault("CHENGYIN_RESTORE_PHASE","SOURCE");gap="voice-gap".equals(System.getenv("CHENGYIN_RESTORE_COHORT"));
        if(phase.equals("SOURCE")) {
            RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_IDENTITY);
            var admin=new JdbcTemplate(new DriverManagerDataSource(prefix+"?useSSL=false&allowPublicKeyRetrieval=true",source.getUsername(),source.getPassword()));
            require(admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema)==0,"never adopt schema");
            require(System.getenv("CHENGYIN_RESTORE_SOURCE_UUID").equals(admin.queryForObject("SELECT @@server_uuid",String.class)),"source UUID");
            RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_SCHEMA);
            admin.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");var initial=new JdbcTemplate(source);
            // Exact existing NpcVoiceEnrollmentMySqlIT profile storage; canonical existing migrations only.
            initial.execute("CREATE TABLE npc_profile(id BIGINT PRIMARY KEY,scope_type INT NOT NULL,scope_id BIGINT NOT NULL,del_flag CHAR(1) NOT NULL DEFAULT '0',voice_version BIGINT NOT NULL DEFAULT 0,voice_id VARCHAR(96),voice_status INT NOT NULL DEFAULT 0,voice_sample VARCHAR(255),audit_status INT NOT NULL DEFAULT 1,update_time DATETIME(6)) ENGINE=InnoDB");
            for(String file:List.of("migration_npc_voice_enrollment_intent_20261024.sql","migration_npc_voice_confirmed_attempt_20261024.sql"))
                new ResourceDatabasePopulator(new FileSystemResource(Path.of("sql",file))).execute(source);
            initial.update("INSERT INTO npc_profile(id,scope_type,scope_id) VALUES(555,2,31),(556,2,31),(557,2,31)");
        }
        RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_ASSEMBLY);
        db=new JdbcTemplate(source);require(System.getenv("CHENGYIN_RESTORE_SOURCE_UUID").equals(db.queryForObject("SELECT @@server_uuid",String.class)),"phase UUID");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/NpcProfileMapper.xml"),new ClassPathResource("mapper/business/NpcVoiceEnrollmentMapper.xml"));
        var sql=new SqlSessionTemplate(factory.getObject());
        provider=new FixtureVoiceProvider(db,external,"SOURCE".equals(phase));
        owner=new NpcVoiceEnrollmentService(sql.getMapper(NpcVoiceEnrollmentMapper.class),sql.getMapper(NpcProfileMapper.class),provider,new DataSourceTransactionManager(source));
    }
    static Map<String,Object> identity(Map<String,Object> row,String voice) {
        // Stable key order across JDBC -> journal JSON -> decoded original; never change the admission version.
        var value=new TreeMap<String,Object>();
        for(String key:List.of("id","request_id","npc_id","actor_member_id","scope_type","scope_id","sample_sha256","sample_reference","profile_version"))value.put(key,row.get(key));
        value.put("voiceId",voice);value.put("origin","REAL_VOICE_ENROLLMENT_INTENT");return value;
    }
    static String approvedInput(long npc) {
        require(npc==555||npc==556||npc==557,"fixed original NPC");
        return "approved-private-provider-input:"+npc;
    }
    private NpcProfile enroll(long npc){return owner.enroll(88L,2,31L,npc,"media-asset:73",()->approvedInput(npc));}

    enum ProviderStep { NONE, INPUT, SUBMIT_ROW, SUBMIT_JOURNAL, QUERY_ROW, QUERY_JOURNAL }
    public enum Operation { SOURCE_KNOWN, SOURCE_UNKNOWN, OWNER_KNOWN, NORMAL_REPLAY, OWNER_UNKNOWN }
    public enum IntentState { PREPARED, IN_FLIGHT, UNKNOWN, NOT_SENT, PENDING, READY, MISSING, UNREVIEWED }
    public enum ProfileState { EMPTY, PENDING, READY, FAILED, MISSING, UNREVIEWED }
    public enum Selection { SELECTED, SUPERSEDED, MISSING }

    /** Fixed typed observations only. No provider text/id, recording reference, URL or arbitrary message is exported. */
    public static final class VoiceFixtureFailure extends IllegalStateException {
        private final Map<String,String> observation;
        VoiceFixtureFailure(Operation operation,IntentState expected,NpcProfile actual,ProviderStep step,Throwable cause) {
            super("VOICE_FIXTURE_ASSERTION",cause);
            IntentState state=IntentState.MISSING;
            if(actual!=null&&actual.getVoiceIntentState()!=null) {
                try {state=IntentState.valueOf(actual.getVoiceIntentState());}
                catch(IllegalArgumentException unreviewed){state=IntentState.UNREVIEWED;}
            }
            Integer status=actual==null?null:actual.getVoiceStatus();
            ProfileState profile=status==null?ProfileState.MISSING:switch(status){case 0->ProfileState.EMPTY;case 1->ProfileState.PENDING;case 2->ProfileState.READY;case 3->ProfileState.FAILED;default->ProfileState.UNREVIEWED;};
            Boolean selected=actual==null?null:actual.getVoiceSelected();
            observation=Map.of("operation",operation.name(),"expected",expected.name(),"intentState",state.name(),
                    "profileState",profile.name(),"selection",selected==null?Selection.MISSING.name():selected?Selection.SELECTED.name():Selection.SUPERSEDED.name(),
                    "providerStep",step.name());
        }
        public Map<String,String> observation(){return observation;}
    }

    /** Provider boundary only; actual owner, SQL, transactions, CAS and terminal reads remain real. */
    static final class FixtureVoiceProvider implements VoiceCloneClient {
        static final String KNOWN_VOICE="fixture-original-voice-555";
        static final String ORIGINAL_ROW="SELECT * FROM npc_voice_enrollment_intent WHERE npc_id=? AND actor_member_id=88 AND scope_type=2 AND scope_id=31 AND request_id=? AND sample_sha256=? AND sample_reference='media-asset:73'";
        private final JdbcTemplate db;private final RestoreProviderJournal external;private final boolean source;
        private Throwable failure;private ProviderStep failureStep=ProviderStep.NONE;
        private int submits,queries;
        FixtureVoiceProvider(JdbcTemplate db,RestoreProviderJournal external,boolean source){this.db=db;this.external=external;this.source=source;}
        public boolean usable(){return true;}
        private Map<String,Object> original(long npc) {
            String hash=NpcVoiceEnrollmentService.fingerprint("media-asset:73");
            return db.queryForMap(ORIGINAL_ROW,npc,"sample:"+hash,hash);
        }
        private RuntimeException failed(ProviderStep step,Exception cause) {
            if(failure==null){failure=cause;failureStep=step;}
            return new IllegalStateException("VOICE_FIXTURE_PROVIDER_PROOF_FAILED",cause);
        }
        public String enroll(String input) {
            ProviderStep step=ProviderStep.INPUT;long npc;
            try {
                require(source,"fixture provider cannot create outside original SOURCE");
                require(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"provider outside business transaction");
                npc=approvedInput(555).equals(input)?555:approvedInput(556).equals(input)?556:0;
                require(npc!=0,"only original approved inputs");
                step=ProviderStep.SUBMIT_ROW;var row=original(npc);
                require("IN_FLIGHT".equals(row.get("state"))&&row.get("voice_id")==null,"original committed claim before provider");
                var original=identity(row,npc==555?KNOWN_VOICE:null);String name=npc==555?"known_voice":"unknown_voice";
                step=ProviderStep.SUBMIT_JOURNAL;
                external.append("VOICE",name,"SUBMIT","IN_FLIGHT",original);submits++;
                external.append("VOICE",name,"RESULT",npc==555?"READY":"UNKNOWN",original);
            } catch(Exception unavailable){throw failed(step,unavailable);}
            if(npc==556)throw new AiServiceException("controlled response unknown",new java.net.SocketTimeoutException());
            return KNOWN_VOICE;
        }
        public boolean isReady(String voice) {
            ProviderStep step=ProviderStep.QUERY_ROW;
            try {
                require(source&&KNOWN_VOICE.equals(voice),"query ONLY original known voice id");
                require(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"provider query outside business transaction");
                var pending=original(555);
                require("PENDING".equals(pending.get("state"))&&KNOWN_VOICE.equals(pending.get("voice_id")),"original id durably retained before query");
                step=ProviderStep.QUERY_JOURNAL;var original=external.original("VOICE","known_voice");
                require(KNOWN_VOICE.equals(original.get("voiceId")),"query ONLY original provider identity");
                for(String key:List.of("id","npc_id","actor_member_id","scope_type","scope_id"))
                    require(((Number)pending.get(key)).longValue()==((Number)original.get(key)).longValue(),"original query binding");
                for(String key:List.of("request_id","sample_sha256","sample_reference"))
                    require(Objects.equals(pending.get(key),original.get(key)),"original sample/request binding");
                require(((Number)pending.get("profile_version")).longValue()==((Number)original.get("profile_version")).longValue()+1,"PENDING retains original admission version progression");
                require("READY".equals(external.state("VOICE","known_voice")),"controlled provider must actually hold the original READY result");
                external.append("VOICE","known_voice","QUERY","READY",original);queries++;return true;
            } catch(Exception unavailable){throw failed(step,unavailable);}
        }
        public String synthesize(String voice,String text){throw new IllegalStateException("fixture never synthesizes audio");}
        void requireState(Operation operation,IntentState expected,NpcProfile result,String message) {
            if(failure!=null)throw new VoiceFixtureFailure(operation,expected,result,failureStep,failure);
            try {require(result!=null&&expected.name().equals(result.getVoiceIntentState()),message);}
            catch(IllegalStateException mismatch){throw new VoiceFixtureFailure(operation,expected,result,ProviderStep.NONE,mismatch);}
        }
        int submits(){return submits;}int queries(){return queries;}
    }
    public static void main(String[] args)throws Exception{
        try {
            require(args.length==0,"fixed child");var fixture=new RestoreVoiceReconciliationFixture();
            RestoreChildDiagnostics.stage("SOURCE".equals(fixture.phase) ? RestoreChildDiagnostics.Stage.SOURCE_TIMELINE
                    : RestoreChildDiagnostics.Stage.OWNER_OBSERVATION);
            fixture.run();
        } catch (Throwable failure) { RestoreChildDiagnostics.report(failure); System.exit(1); }
    }
    private void run()throws Exception {
        if(phase.equals("SOURCE")) {
            var known=enroll(555);
            provider.requireState(Operation.SOURCE_KNOWN,IntentState.READY,known,"original provider-ready persisted");
            requireOriginalProjection(555,IntentState.READY,known);
            if(gap) {
                var unknown=enroll(556);
                provider.requireState(Operation.SOURCE_UNKNOWN,IntentState.UNKNOWN,unknown,"unqueryable original remains UNKNOWN");
                requireOriginalProjection(556,IntentState.UNKNOWN,unknown);
            }
            require(provider.submits()==(gap?2:1)&&provider.queries()==1,"one original enrollment per voice; no UNKNOWN query");
            signal("BACKUP_READY");try(var input=new BufferedReader(new InputStreamReader(System.in))){
                require("AFTER_BACKUP".equals(input.readLine()),"fixed phase");signal("FAULT_READY");require("STOP".equals(input.readLine()),"fixed stop");}return;
        }
        var before=db.queryForList("SELECT * FROM npc_voice_enrollment_intent ORDER BY id");
        var profilesBefore=db.queryForList("SELECT * FROM npc_profile ORDER BY id");
        boolean restoring=Set.of("RESTORED_OWNER","RESTORED_INVALID_OWNER","RESTORED_EMPTY_OWNER").contains(phase);
        if(restoring) {
            boolean blocked=false;try{enroll(557);}catch(RestoreDispatchGuard.Blocked failure){require("RECOVERY_DISPATCH_BLOCKED".equals(failure.getMessage()),"original Blocked code");blocked=true;}
            require(blocked,"actual voice new dispatch owner must propagate Blocked");
        }else require(phase.equals("NORMAL_READBACK")&&!gap,"normal only complete original voice");
        var known=owner.status(88L,2,31L,555L,null);
        provider.requireState(Operation.OWNER_KNOWN,IntentState.READY,known,"known original status");
        requireOriginalProjection(555,IntentState.READY,known);
        if(!restoring)provider.requireState(Operation.NORMAL_REPLAY,IntentState.READY,enroll(555),"normal-boot original enrollment replay cannot submit again");
        if(gap) {
            var unknown=owner.status(88L,2,31L,556L,null);
            provider.requireState(Operation.OWNER_UNKNOWN,IntentState.UNKNOWN,unknown,"no prefix search or guessed provider result");
            requireOriginalProjection(556,IntentState.UNKNOWN,unknown);
        }
        require(before.equals(db.queryForList("SELECT * FROM npc_voice_enrollment_intent ORDER BY id")),"original intent rows changed on replay");
        require(profilesBefore.equals(db.queryForList("SELECT * FROM npc_profile ORDER BY id")),"original profile rows changed on replay");
        require(provider.submits()==0&&provider.queries()==0,"terminal/UNKNOWN readback never contacts provider");
        var evidence=new LinkedHashMap<String,Object>(Map.of("owner","NpcVoiceEnrollmentService","phase",phase,"newDispatchBlocked",restoring,"originalReadback",true,"sameTerminalRows",true));
        evidence.put("blockedMethods",restoring?List.of("NpcVoiceEnrollmentService.enroll"):List.of());
        RestoreProviderJournal.evidence(evidence);
    }
    private void requireOriginalProjection(long npc,IntentState expected,NpcProfile projected) {
        var row=provider.original(npc);var profile=db.queryForMap("SELECT * FROM npc_profile WHERE id=?",npc);
        require(expected.name().equals(row.get("state")),"original terminal intent must be independently persisted");
        require(projected.getVoiceIntentId()!=null&&projected.getVoiceIntentId().longValue()==((Number)row.get("id")).longValue(),"same durable intent identity");
        require(Objects.equals(projected.getVoiceRequestId(),row.get("request_id"))&&Boolean.TRUE.equals(projected.getVoiceSelected()),"same selected original request");
        require(((Number)profile.get("voice_version")).longValue()==((Number)row.get("profile_version")).longValue(),"original profile/intent version binding");
        require("media-asset:73".equals(profile.get("voice_sample"))&&"media-asset:73".equals(projected.getVoiceSample()),"canonical sample reference only");
        require(((Number)profile.get("audit_status")).intValue()==0,"enrollment keeps existing merchant review pending");
        String voice=expected==IntentState.READY?FixtureVoiceProvider.KNOWN_VOICE:null;
        require(Objects.equals(voice,row.get("voice_id"))&&Objects.equals(voice,profile.get("voice_id"))&&Objects.equals(voice,projected.getVoiceId()),"original known voice or unqueryable null only");
        require(((Number)profile.get("voice_status")).intValue()==(expected==IntentState.READY?2:1),"original usable versus pending profile projection");
        require(expected!=IntentState.UNKNOWN||"VOICE_PROVIDER_OUTCOME_UNKNOWN".equals(row.get("reason_code")),"UNKNOWN reason not substituted");
    }
    private static void signal(String p){System.out.println("RESTORE_BUSINESS_PHASE:"+p);System.out.flush();}
}

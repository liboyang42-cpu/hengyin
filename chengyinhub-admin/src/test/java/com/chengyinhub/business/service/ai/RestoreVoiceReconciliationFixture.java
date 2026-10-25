package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.RestoreProviderJournal;
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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.chengyinhub.business.service.RestoreProviderJournal.require;

/** Original enrollment identity/status; no invented provider search or payment-style fingerprint. */
public final class RestoreVoiceReconciliationFixture {
    private final RestoreProviderJournal external=new RestoreProviderJournal();
    private final JdbcTemplate db;
    private final NpcVoiceEnrollmentService owner;
    private final String phase;
    private final boolean gap;
    private RestoreVoiceReconciliationFixture()throws Exception {
        String scope=System.getenv("CHENGYIN_RESTORE_SCOPE");require(scope!=null&&scope.matches("[a-f0-9]{32}"),"owned scope");
        require("github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))&&"true".equals(System.getenv("CHENGYIN_CI_RESTORE_BUSINESS")),"hosted admission");
        String schema="cy_restore_"+scope+"_funds_core_it",prefix="jdbc:mysql://127.0.0.1:"+System.getenv("CHENGYIN_TEST_MYSQL_PORT")+"/";
        var source=new DriverManagerDataSource(prefix+schema+"?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC",System.getenv("CHENGYIN_TEST_MYSQL_USER"),System.getenv("CHENGYIN_TEST_MYSQL_PASSWORD"));
        phase=System.getenv().getOrDefault("CHENGYIN_RESTORE_PHASE","SOURCE");gap="voice-gap".equals(System.getenv("CHENGYIN_RESTORE_COHORT"));
        if(phase.equals("SOURCE")) {
            var admin=new JdbcTemplate(new DriverManagerDataSource(prefix+"?useSSL=false&allowPublicKeyRetrieval=true",source.getUsername(),source.getPassword()));
            require(admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,schema)==0,"never adopt schema");
            require(System.getenv("CHENGYIN_RESTORE_SOURCE_UUID").equals(admin.queryForObject("SELECT @@server_uuid",String.class)),"source UUID");
            admin.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");var initial=new JdbcTemplate(source);
            // Exact existing NpcVoiceEnrollmentMySqlIT profile storage; canonical existing migrations only.
            initial.execute("CREATE TABLE npc_profile(id BIGINT PRIMARY KEY,scope_type INT NOT NULL,scope_id BIGINT NOT NULL,del_flag CHAR(1) NOT NULL DEFAULT '0',voice_version BIGINT NOT NULL DEFAULT 0,voice_id VARCHAR(96),voice_status INT NOT NULL DEFAULT 0,voice_sample VARCHAR(255),audit_status INT NOT NULL DEFAULT 1,update_time DATETIME(6)) ENGINE=InnoDB");
            for(String file:List.of("migration_npc_voice_enrollment_intent_20261024.sql","migration_npc_voice_confirmed_attempt_20261024.sql"))
                new ResourceDatabasePopulator(new FileSystemResource(Path.of("sql",file))).execute(source);
            initial.update("INSERT INTO npc_profile(id,scope_type,scope_id) VALUES(555,2,31),(556,2,31),(557,2,31)");
        }
        db=new JdbcTemplate(source);require(System.getenv("CHENGYIN_RESTORE_SOURCE_UUID").equals(db.queryForObject("SELECT @@server_uuid",String.class)),"phase UUID");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/NpcProfileMapper.xml"),new ClassPathResource("mapper/business/NpcVoiceEnrollmentMapper.xml"));
        var sql=new SqlSessionTemplate(factory.getObject());var provider=mock(VoiceCloneClient.class);when(provider.usable()).thenReturn(true);
        when(provider.enroll(anyString())).thenAnswer(call->{
            var original=db.queryForMap("SELECT * FROM npc_voice_enrollment_intent WHERE state='IN_FLIGHT' ORDER BY id DESC LIMIT 1");
            long npc=((Number)original.get("npc_id")).longValue();String name=npc==555?"known_voice":"unknown_voice";
            var identity=identity(original,npc==555?"fixture-original-voice-555":null);
            external.append("VOICE",name,"SUBMIT","IN_FLIGHT",identity);
            if(npc!=555){external.append("VOICE",name,"RESULT","UNKNOWN",identity);throw new AiServiceException("controlled response unknown",new java.net.SocketTimeoutException());}
            external.append("VOICE",name,"RESULT","READY",identity);return "fixture-original-voice-555";
        });
        when(provider.isReady(anyString())).thenAnswer(call->{
            String id=call.getArgument(0);var original=external.original("VOICE","known_voice");require(id.equals(original.get("voiceId")),"query ONLY original known voice id");
            require("READY".equals(external.state("VOICE","known_voice")),"controlled provider must actually hold the original READY result");
            external.append("VOICE","known_voice","QUERY","READY",original);return true;
        });
        owner=new NpcVoiceEnrollmentService(sql.getMapper(NpcVoiceEnrollmentMapper.class),sql.getMapper(NpcProfileMapper.class),provider,new DataSourceTransactionManager(source));
    }
    private Map<String,Object> identity(Map<String,Object> row,String voice) {
        var value=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","request_id","npc_id","actor_member_id","scope_type","scope_id","sample_sha256","sample_reference","profile_version"))value.put(key,row.get(key));
        value.put("voiceId",voice);value.put("origin","REAL_VOICE_ENROLLMENT_INTENT");return value;
    }
    private NpcProfile enroll(long npc){return owner.enroll(88L,2,31L,npc,"media-asset:73",()->"approved-private-provider-input");}
    public static void main(String[] args)throws Exception{require(args.length==0,"fixed child");new RestoreVoiceReconciliationFixture().run();}
    private void run()throws Exception {
        if(phase.equals("SOURCE")) {
            require("READY".equals(enroll(555).getVoiceIntentState()),"original provider-ready persisted");
            if(gap)require("UNKNOWN".equals(enroll(556).getVoiceIntentState()),"unqueryable original remains UNKNOWN");
            signal("BACKUP_READY");try(var input=new BufferedReader(new InputStreamReader(System.in))){
                require("AFTER_BACKUP".equals(input.readLine()),"fixed phase");signal("FAULT_READY");require("STOP".equals(input.readLine()),"fixed stop");}return;
        }
        var before=db.queryForList("SELECT * FROM npc_voice_enrollment_intent ORDER BY id");
        boolean restoring=Set.of("RESTORED_OWNER","RESTORED_INVALID_OWNER","RESTORED_EMPTY_OWNER").contains(phase);
        if(restoring) {
            boolean blocked=false;try{enroll(557);}catch(RestoreDispatchGuard.Blocked failure){require("RECOVERY_DISPATCH_BLOCKED".equals(failure.getMessage()),"original Blocked code");blocked=true;}
            require(blocked,"actual voice new dispatch owner must propagate Blocked");
        }else require(phase.equals("NORMAL_READBACK")&&!gap,"normal only complete original voice");
        require("READY".equals(owner.status(88L,2,31L,555L,null).getVoiceIntentState()),"known original status");
        if(!restoring)require("READY".equals(enroll(555).getVoiceIntentState()),"normal-boot original enrollment replay cannot submit again");
        if(gap)require("UNKNOWN".equals(owner.status(88L,2,31L,556L,null).getVoiceIntentState()),"no prefix search or guessed provider result");
        require(before.equals(db.queryForList("SELECT * FROM npc_voice_enrollment_intent ORDER BY id")),"original intent rows changed on replay");
        var evidence=new LinkedHashMap<String,Object>(Map.of("owner","NpcVoiceEnrollmentService","phase",phase,"newDispatchBlocked",restoring,"originalReadback",true,"sameTerminalRows",true));
        evidence.put("blockedMethods",restoring?List.of("NpcVoiceEnrollmentService.enroll"):List.of());
        RestoreProviderJournal.evidence(evidence);
    }
    private static void signal(String p){System.out.println("RESTORE_BUSINESS_PHASE:"+p);System.out.flush();}
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.NpcChatRequest;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.business.service.impl.NpcChatService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual request writer/readback and asset CAS; vendor/OSS calls use explicit test seams. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NpcGeneratedAudioMySqlIT {
    JdbcTemplate admin,db;String database;NpcChatRequestMapper requests;AppMediaAssetMapper assets;AppMediaAssetService media;
    @BeforeAll void setup() throws Exception {
        MysqlIntegrationTestConfig c=MysqlIntegrationTestConfig.fromEnvironment("npc_audio");
        admin=new JdbcTemplate(new DriverManagerDataSource(c.adminUrl(),c.user(),c.password()));
        assertEquals("8.0.45",admin.queryForObject("select version()",String.class));
        database="cy_npc_audio_"+UUID.randomUUID().toString().replace("-","");admin.execute("create database "+database);
        DriverManagerDataSource source=new DriverManagerDataSource(c.databaseUrl().replace("/"+c.databaseName()+"?","/"+database+"?"),c.user(),c.password());
        db=new JdbcTemplate(source);
        try(java.sql.Connection connection=source.getConnection()) {
            for(int pass=0;pass<2;pass++) {
                for(String file:java.util.List.of("migration_npc_chat_request_20260722.sql","migration_20261012_npc_chat_media_asset.sql",
                        "migration_20261012_app_media_asset.sql","migration_20261012_app_media_asset_namespaces.sql"))
                    ScriptUtils.executeSqlScript(connection,new FileSystemResource("sql/"+file));
            }
        }
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/NpcChatRequestMapper.xml"),new ClassPathResource("mapper/business/AppMediaAssetMapper.xml"));
        SqlSessionTemplate session=new SqlSessionTemplate(factory.getObject());requests=session.getMapper(NpcChatRequestMapper.class);assets=session.getMapper(AppMediaAssetMapper.class);
        ObjectProvider<IAuditTaskService> provider=mock(ObjectProvider.class);IAuditTaskService audits=mock(IAuditTaskService.class);when(provider.getObject()).thenReturn(audits);
        when(audits.submitSafetyHold(eq("MEDIA_ASSET"),anyLong(),eq(99L),eq("NORMAL"),anyString())).thenReturn(101L);
        media=new AppMediaAssetService(assets,provider);
    }
    @BeforeEach void seed() {db.execute("delete from npc_chat_request");db.execute("delete from app_media_asset");}
    NpcChatRequest insert(String status,String safety) {
        NpcChatRequest row=new NpcChatRequest();row.setUserId(9L);row.setRequestId(UUID.randomUUID().toString());row.setSessionId(-42L);
        row.setStatus(status);row.setSafetyDecision(safety);row.setSafeText("推荐手冲。");row.setRetryable(false);row.setAi(true);
        assertEquals(1,requests.insertNpcChatRequest(row));return row;
    }
    @Test void actualAudioBindingIsOwnerRequestAndSafeTerminalScopedCas() {
        NpcChatRequest row=insert("SUCCEEDED","PASS");
        assertEquals(0,requests.bindApprovedTextAudio(10L,row.getRequestId(),12L));
        assertEquals(0,requests.bindApprovedTextAudio(9L,UUID.randomUUID().toString(),12L));
        assertEquals(1,requests.bindApprovedTextAudio(9L,row.getRequestId(),12L));
        assertEquals(0,requests.bindApprovedTextAudio(9L,row.getRequestId(),13L));
        assertEquals(12L,requests.selectByUserAndRequestId(9L,row.getRequestId()).getAudioAssetId());
        NpcChatRequest failed=insert("FAILED","PASS"),blocked=insert("SUCCEEDED","REJECT");
        assertEquals(0,requests.bindApprovedTextAudio(9L,failed.getRequestId(),12L));
        assertEquals(0,requests.bindApprovedTextAudio(9L,blocked.getRequestId(),12L));
    }
    @Test void persistedPendingAudioKeepsTextAndOnlyRealApprovalMakesSameRequestPlayable() {
        NpcChatRequest row=insert("SUCCEEDED","PASS");
        Long id=((Number)media.stage(99L,"upload/0123456789abcdef0123456789abcdef.mp3").get("assetId")).longValue();
        assertEquals(1,requests.bindApprovedTextAudio(9L,row.getRequestId(),id));
        NpcChatService chat=new NpcChatService();ReflectionTestUtils.setField(chat,"audioAssets",media);
        NpcChatRequest stored=requests.selectByUserAndRequestId(9L,row.getRequestId());
        NpcChatResp pending=ReflectionTestUtils.invokeMethod(chat,"fromStored",stored);
        assertEquals("推荐手冲。",pending.getSafeText());assertNull(pending.getAudioUrl());
        assertThrows(ServiceException.class,()->media.status(id,9L));assertEquals("HOLD",media.status(id,99L).get("reviewState"));
        assertEquals(0,assets.decide(id,1,101L,"APPROVED",null));assertEquals(1,assets.decide(id,0,101L,"APPROVED",null));
        try(org.mockito.MockedStatic<FileOssUploadUtils> oss=mockStatic(FileOssUploadUtils.class)) {
            oss.when(()->FileOssUploadUtils.generatePresignedUrl("upload/0123456789abcdef0123456789abcdef.mp3")).thenReturn("https://owned.invalid/approved.mp3");
            NpcChatResp approved=ReflectionTestUtils.invokeMethod(chat,"fromStored",requests.selectByUserAndRequestId(9L,row.getRequestId()));
            assertEquals("https://owned.invalid/approved.mp3",approved.getAudioUrl());
        }
    }
    @AfterAll void cleanup() {if(database!=null)admin.execute("drop database "+database);}
}

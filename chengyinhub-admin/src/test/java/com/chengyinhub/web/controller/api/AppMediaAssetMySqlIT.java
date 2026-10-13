package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real task-owned MySQL persistence; OSS and the audit inbox boundary remain separate tests. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppMediaAssetMySqlIT {
    JdbcTemplate admin,db;DriverManagerDataSource source;String database;
    AppMediaAssetMapper mapper;IAuditTaskService audits;
    @BeforeAll void setup() throws Exception {
        MysqlIntegrationTestConfig c=MysqlIntegrationTestConfig.fromEnvironment("media_asset");
        admin=new JdbcTemplate(new DriverManagerDataSource(c.adminUrl(),c.user(),c.password()));
        assertEquals("8.0.45",admin.queryForObject("select version()",String.class));
        database="cy_media_"+UUID.randomUUID().toString().replace("-","");admin.execute("create database "+database);
        source=new DriverManagerDataSource(c.databaseUrl().replace("/"+c.databaseName()+"?","/"+database+"?"),c.user(),c.password());
        db=new JdbcTemplate(source);
        try(java.sql.Connection connection=source.getConnection()) {
            for(int pass=0;pass<2;pass++) {
                ScriptUtils.executeSqlScript(connection,new FileSystemResource("sql/migration_20261012_app_media_asset.sql"));
                ScriptUtils.executeSqlScript(connection,new FileSystemResource("sql/migration_20261012_app_media_asset_namespaces.sql"));
            }
        }
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/AppMediaAssetMapper.xml"));
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(AppMediaAssetMapper.class);
    }
    @BeforeEach void seed() {
        db.execute("delete from app_media_asset");audits=mock(IAuditTaskService.class);
        when(audits.submitSafetyHold(eq("MEDIA_ASSET"),anyLong(),eq(9L),eq("NORMAL"),anyString())).thenReturn(101L);
    }
    AppMediaAssetService service(AppMediaAssetMapper target) {
        ObjectProvider<IAuditTaskService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(audits);
        AppMediaAssetService service=new AppMediaAssetService(target,provider);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"publicBaseUrl","https://server.invalid");
        ProxyFactory proxy=new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        return (AppMediaAssetService)proxy.getProxy();
    }
    Long stage(AppMediaAssetService service) {
        return ((Number)service.stage(9L,"upload/0123456789abcdef0123456789abcdef.mp3").get("assetId")).longValue();
    }
    AuditTask task(Long id,Long taskId,int version) {
        AuditTask task=new AuditTask();task.setId(taskId);task.setBizId(id);
        task.setSnapshotJson("{\"assetId\":"+id+",\"assetVersion\":"+version+"}");return task;
    }
    @Test void pendingIsPrivateAndRejectedRetryCannotBeApprovedByOldTaskOrVersion() {
        AppMediaAssetService service=service(mapper);Long id=stage(service);
        Map<String,Object> pending=service.status(id,9L);assertEquals("HOLD",pending.get("reviewState"));assertFalse(pending.containsKey("url"));
        assertThrows(ServiceException.class,()->service.status(id,10L));
        assertEquals(1,service.ownedRecent(9L).size());assertEquals(0,service.ownedRecent(10L).size());
        assertFalse(service.ownedRecent(9L).get(0).containsKey("objectKey"));
        assertFalse(service.ownedRecent(9L).get(0).containsKey("url"));
        assertThrows(ServiceException.class,()->service.approvedUrl(id));
        service.applyAudit(task(id,101L,0),false,"需要重审");
        when(audits.submitSafetyHold(eq("MEDIA_ASSET"),eq(id),eq(9L),eq("NORMAL"),anyString())).thenReturn(102L);
        assertEquals("HOLD",service.retry(id,9L).get("reviewState"));
        assertThrows(ServiceException.class,()->service.applyAudit(task(id,101L,0),true,null));
        assertEquals("HOLD",mapper.select(id).get("reviewState"));
        service.applyAudit(task(id,102L,1),true,null);
        assertEquals("APPROVED",mapper.select(id).get("reviewState"));
        assertThrows(ServiceException.class,()->service.applyAudit(task(id,102L,1),false,"stale"));
        assertEquals("APPROVED",mapper.select(id).get("reviewState"));
    }
    @Test void auditBindingCasFailureRollsBackActualInsertedObjectFact() {
        AppMediaAssetMapper fail=(AppMediaAssetMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{AppMediaAssetMapper.class},
                (p,m,a)->m.getName().equals("bindAuditTask")?0:m.invoke(mapper,a));
        assertThrows(ServiceException.class,()->stage(service(fail)));
        assertEquals(0,db.queryForObject("select count(*) from app_media_asset",Integer.class));
    }
    @Test void systemImageReceiptIsReusableButNeverAnAppAuthorWithSameNumericId() {
        AppMediaAssetService service=service(mapper);
        String key="/profile/upload/2026/10/12/0123456789abcdef0123456789abcdef.png";
        service.registerApprovedSystemImage(9L,key,"https://server.invalid"+key);
        Long id=((Number)mapper.selectByObjectKey(key).get("id")).longValue();
        assertEquals("SYSTEM",mapper.select(id).get("ownerType"));
        assertEquals(0,service.ownedRecent(9L).size());
        assertEquals("https://server.invalid"+key,service.approvedUrl(id));
        assertThrows(ServiceException.class,()->service.status(id,9L));
        assertThrows(ServiceException.class,()->service.ownerPreview(id,9L));
        assertThrows(ServiceException.class,()->service.retry(id,9L));
    }
    @Test void cursorFindsOldRejectedAssetAfterFiftyNewFilesAndExcludesOtherNamespaces() {
        for(int id=1;id<=70;id++)db.update("insert into app_media_asset(id,member_id,object_key,media_type,review_state,version,create_time,update_time) values(?,9,?,'AUDIO',?,0,now(),now())",id,"upload/"+String.format("%032x",id)+".mp3",id==1?"REJECTED":"HOLD");
        db.update("insert into app_media_asset(id,member_id,object_key,media_type,review_state,version,owner_type,create_time,update_time) values(100,10,'upload/foreign.mp3','AUDIO','HOLD',0,'APP',now(),now()),(101,9,'upload/system.mp3','AUDIO','HOLD',0,'SYSTEM',now(),now())");
        AppMediaAssetService service=service(mapper);
        java.util.List<Map<String,Object>> first=service.ownedRecent(9L,null);
        assertEquals(50,first.size());assertEquals("70",first.get(0).get("assetId"));assertEquals("21",first.get(49).get("assetId"));
        db.update("insert into app_media_asset(id,member_id,object_key,media_type,review_state,version,create_time,update_time) values(71,9,'upload/new.mp3','AUDIO','HOLD',0,now(),now())");
        java.util.List<Map<String,Object>> older=service.ownedRecent(9L,21L);
        assertEquals(20,older.size());assertEquals("20",older.get(0).get("assetId"));assertEquals("1",older.get(19).get("assetId"));
        assertEquals("REJECTED",older.get(19).get("reviewState"));assertFalse(older.get(19).containsKey("objectKey"));
        assertEquals(0,service.ownedRecent(10L,21L).size());assertEquals(0,service.ownedRecent(9L,1L).size());
        assertThrows(ServiceException.class,()->service.ownedRecent(9L,0L));
    }
    @AfterAll void cleanup() {if(database!=null)admin.execute("drop database "+database);}
}

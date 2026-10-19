package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.ai.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.common.exception.ServiceException;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** One expected security invariant on production service/mapper code at real MySQL RR.
 * Synthetic catalog/member fixtures use original result-map columns; candidate/source DDL is exact.
 * No AI provider, confirm/template write, production DB, service replacement or SQL rewrite.
 */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class MerchantAuthoringRevocationRaceMySqlIT {
    private static MysqlIntegrationTestConfig MYSQL;
    private static boolean created;
    DriverManagerDataSource db; JdbcTemplate jdbc; SqlSessionTemplate sql;
    MerchantAuthoringFactsService facts; MerchantAuthoringRecordService records;
    MerchantAccessServiceImpl authority;
    final CountDownLatch reachedMerchantLock=new CountDownLatch(1);
    @BeforeAll static void createDatabase() throws Exception {
        MYSQL=MerchantAuthoringMySqlTarget.from(System.getenv(),"w05_race");
        try(Connection c=DriverManager.getConnection(MYSQL.adminUrl(),MYSQL.user(),MYSQL.password());Statement s=c.createStatement()) {
            try(ResultSet rs=s.executeQuery("SELECT VERSION(),@@version_comment,@@transaction_isolation")) {
                assertTrue(rs.next());assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(rs.getString(1)));
                assertEquals("REPEATABLE-READ",rs.getString(3));System.out.println("W05_RACE_DB "+rs.getString(1)+" | "+rs.getString(2)+" | "+rs.getString(3));
            }
            s.execute("CREATE DATABASE "+MYSQL.databaseName()+" CHARACTER SET utf8mb4");created=true;
        }
    }
    @AfterAll static void dropDatabase() throws Exception {
        if(created)try(Connection c=DriverManager.getConnection(MYSQL.adminUrl(),MYSQL.user(),MYSQL.password());Statement s=c.createStatement()){s.execute("DROP DATABASE "+MYSQL.databaseName());}
    }
    @BeforeEach void setup() throws Exception {
        db=new DriverManagerDataSource(MYSQL.databaseUrl(),MYSQL.user(),MYSQL.password());jdbc=new JdbcTemplate(db);
        mappedTable("mms_merchant","MmsMerchantMapper","MmsMerchantResult",MmsMerchant.class,false);
        mappedTable("ums_member","UmsMemberMapper","UmsMemberResult",UmsMember.class,false);
        mappedTable("merchant_operator","MerchantOperatorMapper","MerchantOperatorResult",MerchantOperator.class,false);
        jdbc.execute("ALTER TABLE merchant_operator ADD COLUMN update_by VARCHAR(64)");
        mappedTable("pms_product","PmsProductMapper","PmsProductResult",PmsProduct.class,false);
        mappedTable("pms_sku","PmsSkuMapper","PmsSkuResult",PmsSku.class,false);
        Path root=Paths.get("").toAbsolutePath();while(root!=null&&!Files.isRegularFile(root.resolve("chengyinhub-admin/sql/migration_merchant_ai_authoring_source_v1.sql")))root=root.getParent();
        assertNotNull(root);
        String migration=new String(Files.readAllBytes(root.resolve("chengyinhub-admin/sql/migration_merchant_ai_authoring_source_v1.sql")),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
        for(String statement:migration.split(";"))if(!statement.trim().isEmpty())jdbc.execute(statement);
        jdbc.update("INSERT INTO ums_member(id,status,del_flag,role) VALUES(7,1,0,'merchant'),(17,1,0,'merchant')");
        jdbc.update("INSERT INTO mms_merchant(id,member_id,status,account_status,del_flag,name) VALUES(19,7,1,1,0,'Owned shop')");
        jdbc.update("INSERT INTO merchant_operator(id,merchant_id,operator_member_id,active_operator_member_id,role_code,status,version) VALUES(301,19,17,17,'MERCHANT_MANAGER','ACTIVE',1)");
        jdbc.update("INSERT INTO pms_product(id,merchant_id,product_name,publish_status,del_flag,price,stock,unit) VALUES(101,19,'Owned product',1,0,88,23,'piece')");
        jdbc.update("INSERT INTO pms_sku(id,product_id,sku_name,price,stock) VALUES(102,101,'Owned SKU',77,13)");
        org.apache.ibatis.session.Configuration c=new org.apache.ibatis.session.Configuration(new Environment("real-mysql",new SpringManagedTransactionFactory(),db));
        for(Class<?> type:new Class<?>[]{MmsMerchant.class,UmsMember.class,PmsProduct.class,PmsSku.class})c.getTypeAliasRegistry().registerAlias(type.getSimpleName(),type);
        c.addInterceptor(new LockSignal(reachedMerchantLock));
        for(String name:new String[]{"MmsMerchantMapper","UmsMemberMapper","MerchantOperatorMapper","PmsProductMapper","PmsSkuMapper","MerchantAuthoringRecordMapper"}) {
            String resource="/mapper/business/"+name+".xml";try(InputStream in=getClass().getResourceAsStream(resource)){assertNotNull(in);new XMLMapperBuilder(in,c,resource,c.getSqlFragments()).parse();}
        }
        sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c));
        MmsMerchantServiceImpl merchants=new MmsMerchantServiceImpl();ReflectionTestUtils.setField(merchants,"mmsMerchantMapper",sql.getMapper(MmsMerchantMapper.class));
        authority=new MerchantAccessServiceImpl();ReflectionTestUtils.setField(authority,"merchantService",merchants);
        ReflectionTestUtils.setField(authority,"umsMemberMapper",sql.getMapper(UmsMemberMapper.class));ReflectionTestUtils.setField(authority,"merchantOperatorMapper",sql.getMapper(MerchantOperatorMapper.class));
        PmsProductServiceImpl products=new PmsProductServiceImpl();ReflectionTestUtils.setField(products,"pmsProductMapper",sql.getMapper(PmsProductMapper.class));ReflectionTestUtils.setField(products,"pmsSkuMapper",sql.getMapper(PmsSkuMapper.class));
        PmsSkuServiceImpl skus=new PmsSkuServiceImpl();ReflectionTestUtils.setField(skus,"pmsSkuMapper",sql.getMapper(PmsSkuMapper.class));
        facts=new MerchantAuthoringFactsService(authority,merchants,products,skus);
        // record() does not invoke template confirmation. No synthetic writer is supplied.
        MerchantAuthoringRecordService real=new MerchantAuthoringRecordService(sql.getMapper(MerchantAuthoringRecordMapper.class),facts,authority,sql.getMapper(UmsMemberMapper.class),sql.getMapper(MerchantOperatorMapper.class),null,null);
        ProxyFactory proxy=new ProxyFactory(real);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db),new AnnotationTransactionAttributeSource()));
        records=(MerchantAuthoringRecordService)proxy.getProxy();
    }
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    public static final class LockSignal implements Interceptor {
        private final CountDownLatch signal;LockSignal(CountDownLatch signal){this.signal=signal;}
        @Override public Object intercept(Invocation call)throws Throwable {
            MappedStatement mapped=(MappedStatement)call.getArgs()[0];
            if(Thread.currentThread().getName().equals("w05-author")&&mapped.getId().endsWith("MerchantAuthoringRecordMapper.lockMerchant"))signal.countDown();
            return call.proceed();
        }
        @Override public Object plugin(Object target){return Plugin.wrap(target,this);}
        @Override public void setProperties(Properties properties){}
    }
    @Test void permissionRevokedWhileWaitingForMerchantLockMustNotPersistCandidate() throws Exception {
        Map<String,Object> verified=facts.facts(17L,101L,102L);
        AiTemplateContent content=new AiTemplateContent();content.setTitle("Candidate");content.setValidationMethod(1);
        AiTemplateFillResp generated=new AiTemplateFillResp();generated.setTraceId("fixture-generation-before-race");generated.setTemplate(content);
        CountDownLatch revokedUncommitted=new CountDownLatch(1),commitRevocation=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        Future<?> revoke=pool.submit(()->new TransactionTemplate(new DataSourceTransactionManager(db)).execute(status->{
            sql.getMapper(MerchantAuthoringRecordMapper.class).lockMerchant(19L);
            assertEquals(1,sql.getMapper(MerchantOperatorMapper.class).updateRoleCas(301L,19L,1,"MERCHANT_CHECKIN","7"));
            revokedUncommitted.countDown();
            try{if(!commitRevocation.await(15,TimeUnit.SECONDS))throw new AssertionError("Commit signal timeout");}catch(InterruptedException e){throw new RuntimeException(e);}
            return null;
        }));
        try {
            if(!revokedUncommitted.await(10,TimeUnit.SECONDS)){revoke.get(1,TimeUnit.SECONDS);fail("Revoker did not reach held update");}
            Future<?> save=pool.submit(()->{Thread.currentThread().setName("w05-author");return records.record(17L,verified,Collections.singletonMap("fixture",true),generated);});
            if(!reachedMerchantLock.await(10,TimeUnit.SECONDS)){save.get(1,TimeUnit.SECONDS);fail("Writer did not reach merchant lock");}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean observedWait=false;
            while(System.nanoTime()<deadline) {
                Integer waits=jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='mms_merchant'",Integer.class,MYSQL.databaseName());
                if(waits!=null&&waits>0){observedWait=true;break;}Thread.sleep(20);
            }
            assertTrue(observedWait,"Actual MySQL merchant row-lock wait must be observed");
            commitRevocation.countDown();revoke.get(10,TimeUnit.SECONDS);
            Throwable rejected=null;
            try{save.get(10,TimeUnit.SECONDS);}catch(ExecutionException error){rejected=error.getCause();}
            assertEquals("MERCHANT_CHECKIN",jdbc.queryForObject("SELECT role_code FROM merchant_operator WHERE id=301",String.class));
            assertThrows(ServiceException.class,()->authority.require(17L,MerchantPermission.PROJECT_MANAGE));
            int persisted=jdbc.queryForObject("SELECT COUNT(*) FROM merchant_authoring_candidate",Integer.class);
            System.out.println("W05_RACE_OBSERVED lock_wait=true committed_role=MERCHANT_CHECKIN fresh_authority=DENIED candidate_rows="+persisted+" service_rejected="+(rejected!=null));
            assertEquals(0,persisted,"Revoked PROJECT_MANAGE must not commit a candidate after the lock wait");
            assertNotNull(rejected,"Authoring must reject the changed permission");
        } finally {commitRevocation.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    void mappedTable(String table,String file,String resultMap,Class<?> bean,boolean generated)throws Exception{
        String source;try(InputStream in=getClass().getResourceAsStream("/mapper/business/"+file+".xml")){assertNotNull(in);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);source=new String(out.toByteArray(),StandardCharsets.UTF_8);}
        Matcher root=Pattern.compile("<resultMap\\b[^>]*id=\\\""+resultMap+"\\\"[^>]*>(.*?)</resultMap>",Pattern.DOTALL).matcher(source);assertTrue(root.find());Matcher fields=Pattern.compile("<(?:id|result)\\s+property=\\\"([^\\\"]+)\\\"\\s+column=\\\"([^\\\"]+)\\\"").matcher(root.group(1));Map<String,String> columns=new LinkedHashMap<>();
        while(fields.find()){String property=fields.group(1),column=fields.group(2);Class<?> type=bean.getMethod("get"+Character.toUpperCase(property.charAt(0))+property.substring(1)).getReturnType();String sqlType=Number.class.isAssignableFrom(type)?(type==BigDecimal.class?"DECIMAL(30,8)":"BIGINT"):java.util.Date.class.isAssignableFrom(type)?"DATETIME(3)":"TEXT";columns.put(column,column+" "+sqlType+(column.equals("id")?(generated?" AUTO_INCREMENT PRIMARY KEY":" PRIMARY KEY"):""));}
        assertFalse(columns.isEmpty());jdbc.execute("CREATE TABLE "+table+"("+String.join(",",columns.values())+") ENGINE=InnoDB");
    }
}

package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.ai.*;
import com.chengyinhub.business.domain.dto.ai.MerchantAuthoringConfirmation;
import com.chengyinhub.business.service.*;
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

/** Full locked-row candidate/confirmation races on disposable real MySQL REPEATABLE READ.
 * Actual authority, record, facts, template draft service and mapper SQL; explicit synthetic
 * capability/provider-output boundaries. No live AI or production database.
 */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class MerchantAuthoringCurrentRowsMySqlIT {
    private static MysqlIntegrationTestConfig MYSQL;
    private static boolean created;
    DriverManagerDataSource db; JdbcTemplate jdbc; SqlSessionTemplate sql;
    MerchantAuthoringFactsService facts; MerchantAuthoringRecordService records;
    MerchantAccessServiceImpl authority;
    final CountDownLatch reachedMerchantLock=new CountDownLatch(1);
    @BeforeAll static void createDatabase() throws Exception {
        MYSQL=MerchantAuthoringMySqlTarget.from(System.getenv(),"w05_current_rows");
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
        mappedTable("cms_member_template","CmsMemberTemplateMapper","CmsMemberTemplateResult",CmsMemberTemplate.class,true);
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
        for(Class<?> type:new Class<?>[]{MmsMerchant.class,UmsMember.class,PmsProduct.class,PmsSku.class,CmsMemberTemplate.class,CmsTemplateLibrary.class,com.chengyinhub.business.domain.vo.MemberTemplateVO.class})c.getTypeAliasRegistry().registerAlias(type.getSimpleName(),type);
        c.addInterceptor(new LockSignal(reachedMerchantLock));
        for(String name:new String[]{"MmsMerchantMapper","UmsMemberMapper","MerchantOperatorMapper","PmsProductMapper","PmsSkuMapper","CmsMemberTemplateMapper","MerchantAuthoringRecordMapper"}) {
            String resource="/mapper/business/"+name+".xml";try(InputStream in=getClass().getResourceAsStream(resource)){assertNotNull(in);new XMLMapperBuilder(in,c,resource,c.getSqlFragments()).parse();}
        }
        sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c));
        MmsMerchantServiceImpl merchants=new MmsMerchantServiceImpl();ReflectionTestUtils.setField(merchants,"mmsMerchantMapper",sql.getMapper(MmsMerchantMapper.class));
        authority=new MerchantAccessServiceImpl();ReflectionTestUtils.setField(authority,"merchantService",merchants);
        ReflectionTestUtils.setField(authority,"umsMemberMapper",sql.getMapper(UmsMemberMapper.class));ReflectionTestUtils.setField(authority,"merchantOperatorMapper",sql.getMapper(MerchantOperatorMapper.class));
        PmsProductServiceImpl products=new PmsProductServiceImpl();ReflectionTestUtils.setField(products,"pmsProductMapper",sql.getMapper(PmsProductMapper.class));ReflectionTestUtils.setField(products,"pmsSkuMapper",sql.getMapper(PmsSkuMapper.class));
        PmsSkuServiceImpl skus=new PmsSkuServiceImpl();ReflectionTestUtils.setField(skus,"pmsSkuMapper",sql.getMapper(PmsSkuMapper.class));
        facts=new MerchantAuthoringFactsService(authority,merchants,products,skus);
        CmsMemberTemplateServiceImpl templateRows=new CmsMemberTemplateServiceImpl();ReflectionTestUtils.setField(templateRows,"cmsMemberTemplateMapper",sql.getMapper(CmsMemberTemplateMapper.class));
        TemplatePublishServiceImpl publisher=new TemplatePublishServiceImpl();ReflectionTestUtils.setField(publisher,"memberTemplateService",templateRows);ReflectionTestUtils.setField(publisher,"memberTemplateMapper",sql.getMapper(CmsMemberTemplateMapper.class));
        ICapabilityGuard capability=(ICapabilityGuard)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ICapabilityGuard.class},(o,m,args)->{
            if(m.getDeclaringClass()==Object.class)return "synthetic-capability-boundary";
            if(!m.getName().equals("checkAdvancedMechanics"))throw new AssertionError("Unexpected economic/reward capability");return null;
        });ReflectionTestUtils.setField(publisher,"capabilityGuard",capability);
        records=(MerchantAuthoringRecordService)tx(new MerchantAuthoringRecordService(sql.getMapper(MerchantAuthoringRecordMapper.class),facts,authority,sql.getMapper(UmsMemberMapper.class),sql.getMapper(MerchantOperatorMapper.class),(ITemplatePublishService)tx(publisher),sql.getMapper(CmsMemberTemplateMapper.class)));
    }
    private Object tx(Object target) {
        ProxyFactory proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db),new AnnotationTransactionAttributeSource()));return proxy.getProxy();
    }
    @AfterEach void dropFixtureTables() {
        for(String table:new String[]{"merchant_authoring_source","merchant_authoring_candidate","cms_member_template","pms_sku","pms_product","merchant_operator","mms_merchant","ums_member"})jdbc.execute("DROP TABLE IF EXISTS "+table);
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
    @Test void unchangedManagerCanRecordAndConfirmRealDraftAndSource() {
        long id=records.record(17L,facts.facts(17L,101L,102L),constraints(),generated()).getId();
        Map<String,Object> receipt=records.confirm(17L,confirmation(id));
        assertNotNull(receipt.get("templateId"));assertEquals(1,count("merchant_authoring_candidate"));
        assertEquals(1,count("cms_member_template"));assertEquals(1,count("merchant_authoring_source"));
        assertEquals(7L,jdbc.queryForObject("SELECT member_id FROM cms_member_template",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT publish_status FROM cms_member_template",Long.class));
    }
    @Test void disabledShopCommittedDuringLockWaitCannotRecord() throws Exception {
        race(false,()->assertEquals(1,jdbc.update("UPDATE mms_merchant SET account_status=0 WHERE id=19")),403,409);
    }
    @Test void changedProductCommittedDuringLockWaitCannotRecord() throws Exception {
        race(false,()->assertEquals(1,jdbc.update("UPDATE pms_product SET product_name='Replacement' WHERE id=101")),409);
    }
    @Test void changedSKUCommittedDuringLockWaitCannotRecord() throws Exception {
        race(false,()->assertEquals(1,jdbc.update("UPDATE pms_sku SET sku_name='Replacement' WHERE id=102")),409);
    }
    @Test void changedShopNameCommittedDuringLockWaitCannotRecord() throws Exception {
        race(false,()->assertEquals(1,jdbc.update("UPDATE mms_merchant SET name='Replacement' WHERE id=19")),409);
    }
    @Test void revokedManagerCommittedDuringLockWaitCannotConfirmDraftOrSource() throws Exception {
        race(true,()->assertEquals(1,sql.getMapper(MerchantOperatorMapper.class).updateRoleCas(301L,19L,1,"MERCHANT_CHECKIN","7")),403,409);
    }
    @Test void changedProductCommittedDuringLockWaitCannotConfirmDraftOrSource() throws Exception {
        race(true,()->assertEquals(1,jdbc.update("UPDATE pms_product SET product_name='Replacement' WHERE id=101")),409);
    }
    private void race(boolean confirm,Runnable mutation,Integer... allowedCodes) throws Exception {
        Map<String,Object> verified=facts.facts(17L,101L,102L);
        Long candidate=confirm?records.record(17L,verified,constraints(),generated()).getId():null;
        CountDownLatch mutationUncommitted=new CountDownLatch(1),commitMutation=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        Future<?> change=pool.submit(()->new TransactionTemplate(new DataSourceTransactionManager(db)).execute(status->{
            sql.getMapper(MerchantAuthoringRecordMapper.class).lockMerchant(19L);mutation.run();mutationUncommitted.countDown();
            try{if(!commitMutation.await(15,TimeUnit.SECONDS))throw new AssertionError("Commit signal timeout");}catch(InterruptedException error){throw new RuntimeException(error);}return null;
        }));
        try {
            if(!mutationUncommitted.await(10,TimeUnit.SECONDS)){change.get(1,TimeUnit.SECONDS);fail("Mutation did not hold merchant lock");}
            Future<?> writer=pool.submit(()->{Thread.currentThread().setName("w05-author");return confirm?records.confirm(17L,confirmation(candidate)):records.record(17L,verified,constraints(),generated());});
            if(!reachedMerchantLock.await(10,TimeUnit.SECONDS)){writer.get(1,TimeUnit.SECONDS);fail("Writer did not reach merchant lock");}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean waited=false;
            while(System.nanoTime()<deadline){
                Integer waits=jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='mms_merchant'",Integer.class,MYSQL.databaseName());
                if(waits!=null&&waits>0){waited=true;break;}Thread.sleep(20);
            }
            assertTrue(waited,"Actual MySQL merchant row-lock wait required");commitMutation.countDown();change.get(10,TimeUnit.SECONDS);
            ExecutionException rejection=assertThrows(ExecutionException.class,()->writer.get(10,TimeUnit.SECONDS));
            assertTrue(rejection.getCause() instanceof ServiceException,"Must reject changed authority/facts explicitly: "+rejection.getCause());
            assertTrue(Arrays.asList(allowedCodes).contains(((ServiceException)rejection.getCause()).getCode()));
            assertEquals(confirm?1:0,count("merchant_authoring_candidate"));
            assertEquals(0,count("cms_member_template"));assertEquals(0,count("merchant_authoring_source"));
            System.out.println("W05_CURRENT_ROWS lock_wait=true confirm="+confirm+" explicit_rejection=true candidates="+count("merchant_authoring_candidate")+" templates=0 sources=0");
        } finally {commitMutation.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private AiTemplateFillResp generated(){
        AiTemplateContent text=new AiTemplateContent();text.setTitle("Synthetic candidate");text.setValidationMethod(1);
        AiTemplateFillResp result=new AiTemplateFillResp();result.setTraceId("synthetic-current-rows");result.setTemplate(text);return result;
    }
    private Map<String,Object> constraints(){
        Map<String,Object> value=new TreeMap<>();value.put("durationMinutes",10);value.put("participantLimit",3);
        value.put("openingHours","10:00-18:00");value.put("staffRequired",false);value.put("filmingAllowed",false);
        value.put("noisePolicy","Quiet voice");value.put("accessibleAlternative","Seated clue");value.put("offlineAlternative","Printed clue");value.put("failureExit","Ask staff");return value;
    }
    private MerchantAuthoringConfirmation confirmation(long id){
        MerchantAuthoringConfirmation value=new MerchantAuthoringConfirmation();value.setCandidateId(id);Map<String,String> fields=new TreeMap<>();
        fields.put("title","Human title");fields.put("description","Human description");fields.put("ruleInstructions","Ask staff");fields.put("questionName","Question");fields.put("questionAnswer","Answer");value.setAcceptedFields(fields);return value;
    }
    void mappedTable(String table,String file,String resultMap,Class<?> bean,boolean generated)throws Exception{
        String source;try(InputStream in=getClass().getResourceAsStream("/mapper/business/"+file+".xml")){assertNotNull(in);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);source=new String(out.toByteArray(),StandardCharsets.UTF_8);}
        Matcher root=Pattern.compile("<resultMap\\b[^>]*id=\\\""+resultMap+"\\\"[^>]*>(.*?)</resultMap>",Pattern.DOTALL).matcher(source);assertTrue(root.find());Matcher fields=Pattern.compile("<(?:id|result)\\s+property=\\\"([^\\\"]+)\\\"\\s+column=\\\"([^\\\"]+)\\\"").matcher(root.group(1));Map<String,String> columns=new LinkedHashMap<>();
        while(fields.find()){String property=fields.group(1),column=fields.group(2);Class<?> type=bean.getMethod("get"+Character.toUpperCase(property.charAt(0))+property.substring(1)).getReturnType();String sqlType=Number.class.isAssignableFrom(type)?(type==BigDecimal.class?"DECIMAL(30,8)":"BIGINT"):java.util.Date.class.isAssignableFrom(type)?"DATETIME(3)":"TEXT";columns.put(column,column+" "+sqlType+(column.equals("id")?(generated?" AUTO_INCREMENT PRIMARY KEY":" PRIMARY KEY"):""));}
        assertFalse(columns.isEmpty());jdbc.execute("CREATE TABLE "+table+"("+String.join(",",columns.values())+") ENGINE=InnoDB");
    }
}

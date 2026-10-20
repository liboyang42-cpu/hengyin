package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.service.support.LegacyCmsTemplateBodyReader;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.regex.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real Spring transaction proxy, JDBC and production MyBatis XML; H2 is not MySQL acceptance. */
class LegacyCmsTemplateBodyReaderMyBatisTest {
    private static final String LOCK = LegacyCmsTemplateReadMapper.class.getName()+".selectCanonicalStateForUpdate";
    private static final String BODY = CmsMemberTemplateMapper.class.getName()+".selectCmsMemberTemplateById";
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;
    private LegacyCmsTemplateBodyReader reader;
    private QueryTrace trace;

    @org.springframework.context.annotation.Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions { }

    @BeforeEach void setUp() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:legacy_body_"+UUID.randomUUID().toString().replace("-", "")
                +";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver"); jdbc = new JdbcTemplate(dataSource);
        String bodyResource="mapper/business/CmsMemberTemplateMapper.xml";
        String xml;
        try(InputStream input=Resources.getResourceAsStream(bodyResource)) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] chunk=new byte[4096]; int n;
            while((n=input.read(chunk))!=-1)bytes.write(chunk,0,n);
            xml=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        }
        Matcher projection=Pattern.compile("<sql id=\"selectCmsMemberTemplateVo\">\\s*select (.*?) from cms_member_template\\s*</sql>",Pattern.DOTALL).matcher(xml);
        assertTrue(projection.find(),"Use the existing complete production body projection");
        StringBuilder ddl=new StringBuilder("create table cms_member_template(");
        for(String token:projection.group(1).split(",")) {
            String column=token.trim(); assertTrue(column.matches("[a-z_][a-z0-9_]*"),"Unexpected projection expression");
            if("w18_provenance_kind".equals(column))continue; // Added below, including after the body projection learns it.
            ddl.append(column).append("id".equals(column)?" bigint primary key":" varchar(4096) null").append(',');
        }
        ddl.append("w18_provenance_kind varchar(32) null)"); jdbc.execute(ddl.toString());
        jdbc.execute("create table w18_cms_template_provenance(template_id bigint primary key)");
        Configuration cfg=new Configuration();
        cfg.setEnvironment(new Environment("body-reader-h2",new SpringManagedTransactionFactory(),dataSource));
        cfg.getTypeAliasRegistry().registerAlias("CmsMemberTemplate",CmsMemberTemplate.class);
        cfg.getTypeAliasRegistry().registerAlias("MemberTemplateVO",MemberTemplateVO.class);
        trace=new QueryTrace(dataSource); cfg.addInterceptor(trace);
        for(String resource:Arrays.asList(bodyResource,"mapper/business/LegacyCmsTemplateReadMapper.xml")) {
            try(InputStream input=Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input,cfg,resource,cfg.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
        context=new AnnotationConfigApplicationContext();
        context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(dataSource));
        context.registerBean(LegacyCmsTemplateReadMapper.class,()->session.getMapper(LegacyCmsTemplateReadMapper.class));
        context.registerBean(CmsMemberTemplateMapper.class,()->session.getMapper(CmsMemberTemplateMapper.class));
        context.register(Transactions.class,LegacyCmsTemplateBodyReader.class); context.refresh();
        reader=context.getBean(LegacyCmsTemplateBodyReader.class);
    }
    @AfterEach void close(){if(context!=null)context.close();}
    private void row(long id,String marker){jdbc.update("insert into cms_member_template(id,w18_provenance_kind,title,rule_instructions) values(?,?,?,?)",id,marker,"legacy title","legacy body");}
    @Test void proxyReadsLegacyBodyOnlyAfterLockWithinSameWriteCapableTransaction(){
        row(1,null); assertTrue(AopUtils.isAopProxy(reader));
        CmsMemberTemplate result=reader.findLegacy(1L); assertNotNull(result);
        assertEquals("legacy body",result.getRuleInstructions()); assertEquals(Arrays.asList(LOCK,BODY),trace.ids);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
    @Test void canonicalLockStillHeldWhenActualBodyQueryStarts(){
        row(1,null); trace.probeCanonicalLock=true;
        assertNotNull(reader.findLegacy(1L)); assertTrue(trace.lockContentionObserved);
    }
    @Test void anyMarkerBlocksWithoutExecutingBodyQuery(){
        long id=0;for(String marker:Arrays.asList("W18_MANAGED","","UNKNOWN"," ")){
            row(++id,marker);trace.ids.clear();assertNull(reader.findLegacy(id));assertEquals(Collections.singletonList(LOCK),trace.ids);
        }
    }
    @Test void sidecarBlocksWithoutExecutingBodyQuery(){
        row(1,null);jdbc.update("insert into w18_cms_template_provenance(template_id) values(1)");
        assertNull(reader.findLegacy(1L));assertEquals(Collections.singletonList(LOCK),trace.ids);
    }
    @Test void missingAndInvalidIdsNeverReadBody(){
        assertNull(reader.findLegacy(99L));assertEquals(Collections.singletonList(LOCK),trace.ids);trace.ids.clear();
        for(Long id:new Long[]{null,0L,-1L})assertNull(reader.findLegacy(id));assertTrue(trace.ids.isEmpty());
    }
    @Test void failedProvenanceQueryPropagatesAndNeverFallsBackToBody(){
        row(1,null);jdbc.execute("drop table w18_cms_template_provenance");
        assertThrows(DataAccessException.class,()->reader.findLegacy(1L));assertEquals(Collections.singletonList(LOCK),trace.ids);
    }
    @Test void failedBodyQueryPropagatesAfterLock(){
        row(1,null);jdbc.execute("alter table cms_member_template drop column rule_instructions");
        assertThrows(DataAccessException.class,()->reader.findLegacy(1L));assertEquals(Arrays.asList(LOCK,BODY),trace.ids);
    }
    @Test void blockedRowDoesNotNeedBodyColumns(){
        row(1,"W18_MANAGED");jdbc.execute("alter table cms_member_template drop column rule_instructions");
        assertNull(reader.findLegacy(1L));assertEquals(Collections.singletonList(LOCK),trace.ids);
    }

    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    public static class QueryTrace implements Interceptor {
        private final DriverManagerDataSource dataSource; final List<String> ids=new ArrayList<>();
        boolean probeCanonicalLock,lockContentionObserved; Connection firstConnection;
        QueryTrace(DriverManagerDataSource dataSource){this.dataSource=dataSource;}
        @Override public Object intercept(Invocation invocation)throws Throwable{
            String id=((MappedStatement)invocation.getArgs()[0]).getId();ids.add(id);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            Connection connection=((ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource)).getConnection();
            assertFalse(connection.getAutoCommit());
            if(LOCK.equals(id))firstConnection=connection;else assertSame(firstConnection,connection);
            if(BODY.equals(id)&&probeCanonicalLock){
                try(Connection other=dataSource.getConnection();Statement stmt=other.createStatement()){
                    stmt.execute("set lock_timeout 100");
                    SQLException failure=assertThrows(SQLException.class,()->stmt.executeUpdate("update cms_member_template set title='concurrent' where id=1"));
                    assertEquals(50200,failure.getErrorCode(),"H2 must report row lock timeout");lockContentionObserved=true;
                }
            }
            return invocation.proceed();
        }
        @Override public Object plugin(Object target){return Plugin.wrap(target,this);}
        @Override public void setProperties(Properties properties){}
    }
}

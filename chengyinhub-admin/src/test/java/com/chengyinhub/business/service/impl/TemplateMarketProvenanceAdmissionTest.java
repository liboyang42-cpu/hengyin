package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.LegacyCmsMutationGuard;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import org.springframework.util.ReflectionUtils;
import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full original publisher and actual canonical/OR MyBatis SQL under real Spring/H2 transactions.
 * Library persistence/audit ports are explicitly test-owned JDBC fixture edges, not full market
 * approval implementations. No W18 authority, production provider or remote operation is supplied. */
class TemplateMarketProvenanceAdmissionTest {
    JdbcTemplate jdbc;DataSourceTransactionManager manager;SqlSessionTemplate sql;
    TemplatePublishServiceImpl raw;ITemplatePublishService service;
    int projectionCalls,auditCalls;boolean failAudit;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:market_guard_"+System.nanoTime()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        ds.setDriverClassName("org.h2.Driver");jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        try(InputStream in=Resources.getResourceAsStream("workshop/market-template-columns.sql")){
            String ddl=new String(read(in),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","");
            for(String part:ddl.split(";"))if(!part.trim().isEmpty())jdbc.execute(part);
        }
        jdbc.execute("CREATE TABLE w18_cms_template_provenance(template_id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE fixture_market_projection(id BIGINT PRIMARY KEY,source_id BIGINT NOT NULL,body CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE fixture_market_audit(id BIGINT PRIMARY KEY,projection_id BIGINT NOT NULL,owner_id BIGINT NOT NULL,body CLOB NOT NULL)");
        jdbc.update("INSERT INTO cms_member_template(id,member_id,title,question_answer,draft_status,status,check_status,del_flag,validation_method,photo_review,medal_style) VALUES(41,7,'Synthetic market item','SYNTHETIC PROTECTED ANSWER',1,1,1,0,1,0,'glow')");
        Configuration c=new Configuration();c.setEnvironment(new Environment("market-guard",new SpringManagedTransactionFactory(),ds));
        c.getTypeAliasRegistry().registerAlias("CmsMemberTemplate",CmsMemberTemplate.class);
        c.getTypeAliasRegistry().registerAlias("MemberTemplateVO",com.chengyinhub.business.domain.vo.MemberTemplateVO.class);
        for(String path:Arrays.asList("mapper/business/CmsMemberTemplateMapper.xml","mapper/business/LegacyCmsTemplateReadMapper.xml")){
            try(InputStream in=Resources.getResourceAsStream(path)){new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}
        }
        sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c));raw=new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(raw,"memberTemplateMapper",sql.getMapper(CmsMemberTemplateMapper.class));
        // The unchanged red implementation lacks this field. Do not inject a replacement service.
        if(ReflectionUtils.findField(TemplatePublishServiceImpl.class,"legacyCmsMutationGuard")!=null)
            ReflectionTestUtils.setField(raw,"legacyCmsMutationGuard",new LegacyCmsMutationGuard(sql.getMapper(LegacyCmsTemplateReadMapper.class)));
        ReflectionTestUtils.setField(raw,"libraryMapper",port(CmsTemplateLibraryMapper.class,(method,args)->{
            if(!method.getName().equals("selectByMemberTemplateForUpdate"))throw new AssertionError(method);
            return jdbc.query("SELECT body FROM fixture_market_projection WHERE source_id=? ORDER BY id FOR UPDATE",(r,n)->JSON.parseObject(r.getString(1),CmsTemplateLibrary.class),args[0]);
        }));
        ReflectionTestUtils.setField(raw,"templateLibraryService",port(ICmsTemplateLibraryService.class,(method,args)->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());projectionCalls++;
            CmsTemplateLibrary value=(CmsTemplateLibrary)args[0];
            if(method.getName().equals("insertCmsTemplateLibrary")){
                value.setId(700L);return jdbc.update("INSERT INTO fixture_market_projection VALUES(?,?,?)",value.getId(),value.getMemberTemplateId(),JSON.toJSONString(value));
            }
            if(method.getName().equals("updateCmsTemplateLibrary"))return jdbc.update("UPDATE fixture_market_projection SET body=? WHERE id=?",JSON.toJSONString(value),value.getId());
            throw new AssertionError(method);
        }));
        ReflectionTestUtils.setField(raw,"auditTaskService",port(IAuditTaskService.class,(method,args)->{
            if(!method.getName().equals("submitOrRefreshTemplateLibrary"))throw new AssertionError(method);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());auditCalls++;
            if(failAudit)throw new ServiceException("Synthetic audit boundary failure");
            jdbc.update("MERGE INTO fixture_market_audit(id,projection_id,owner_id,body) KEY(id) VALUES(900,?,?,?)",args[0],args[1],args[2]);return 900L;
        }));
        ProxyFactory proxy=new ProxyFactory(raw);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));service=(ITemplatePublishService)proxy.getProxy();
    }
    private byte[] read(InputStream in)throws Exception{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}
    interface Call {Object invoke(Method method,Object[] args)throws Throwable;}
    @SuppressWarnings("unchecked") private static <T>T port(Class<T> type,Call call){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{if(m.getDeclaringClass()==Object.class)return m.getName().equals("toString")?"SyntheticMarketPort":m.getName().equals("hashCode")?System.identityHashCode(p):p==a[0];return call.invoke(m,a);});}
    private Long submit(){return service.submitTemplateToMarket(41L,7L,"synthetic-owner");}
    private int projections(){return jdbc.queryForObject("SELECT COUNT(*) FROM fixture_market_projection",Integer.class);}
    private int audits(){return jdbc.queryForObject("SELECT COUNT(*) FROM fixture_market_audit",Integer.class);}
    private void denied(){assertThrows(RuntimeException.class,this::submit);assertEquals(0,projectionCalls);assertEquals(0,auditCalls);assertEquals(0,projections());assertEquals(0,audits());}
    @Test void nonNullRecognizedEmptyWhitespaceAndUnknownMarkersCannotCopyOrSubmit(){
        for(String marker:Arrays.asList("W18_MANAGED",""," ","FUTURE_MANAGED")){
            jdbc.update("UPDATE cms_member_template SET w18_provenance_kind=? WHERE id=41",marker);denied();
        }
    }
    @Test void nullMarkerWithRealSidecarCannotCopyOrSubmit(){jdbc.update("INSERT INTO w18_cms_template_provenance VALUES(41)");denied();}
    @Test void ordinaryApprovedLegacyTemplateStillCreatesAndRefreshesSameProjection(){
        assertEquals(900L,submit());assertEquals(1,projections());assertEquals(1,audits());
        assertTrue(jdbc.queryForObject("SELECT body FROM fixture_market_projection",String.class).contains("SYNTHETIC PROTECTED ANSWER"));
        jdbc.update("UPDATE cms_member_template SET title='Synthetic revised ordinary item' WHERE id=41");assertEquals(900L,submit());
        assertEquals(1,projections());assertEquals(1,audits());assertEquals(2,projectionCalls);assertEquals(2,auditCalls);
        assertTrue(jdbc.queryForObject("SELECT body FROM fixture_market_projection",String.class).contains("Synthetic revised ordinary item"));
    }
    @Test void existingProjectionCannotBeOverwrittenAfterMarkerOrSidecarIsPresent(){
        assertEquals(900L,submit());String old=jdbc.queryForObject("SELECT body FROM fixture_market_projection",String.class);projectionCalls=0;auditCalls=0;
        jdbc.update("INSERT INTO w18_cms_template_provenance VALUES(41)");
        assertThrows(RuntimeException.class,this::submit);assertEquals(0,projectionCalls);assertEquals(0,auditCalls);
        assertEquals(old,jdbc.queryForObject("SELECT body FROM fixture_market_projection",String.class));assertEquals(1,audits());
    }
    @Test void missingSidecarSchemaFailsClosedBeforeProjection(){jdbc.execute("DROP TABLE w18_cms_template_provenance");denied();}
    @Test void missingGuardProviderFailsClosedEvenForOtherwiseValidTemplate(){
        if(ReflectionUtils.findField(TemplatePublishServiceImpl.class,"legacyCmsMutationGuard")!=null)ReflectionTestUtils.setField(raw,"legacyCmsMutationGuard",null);denied();
    }
    @Test void directInvocationWithoutTransactionCannotWriteProjection(){assertThrows(RuntimeException.class,()->raw.submitTemplateToMarket(41L,7L,"synthetic-owner"));assertEquals(0,projectionCalls);assertEquals(0,audits());}
    @Test void readOnlyOuterTransactionCannotBeUpgradedBySubmission(){TransactionTemplate tx=new TransactionTemplate(manager);tx.setReadOnly(true);assertThrows(RuntimeException.class,()->tx.execute(s->submit()));assertEquals(0,projectionCalls);assertEquals(0,audits());}
    @Test void ownerAndExistingReviewStateGatesAreRetained(){
        assertThrows(ServiceException.class,()->service.submitTemplateToMarket(41L,8L,"synthetic-other"));
        for(String column:Arrays.asList("draft_status","status","check_status")){
            jdbc.update("UPDATE cms_member_template SET "+column+"=0 WHERE id=41");denied();jdbc.update("UPDATE cms_member_template SET "+column+"=1 WHERE id=41");
        }
        jdbc.update("UPDATE cms_member_template SET del_flag=2 WHERE id=41");denied();assertEquals(0,projectionCalls);
    }
    @Test void downstreamAuditFailureStillRollsBackOrdinaryProjection(){failAudit=true;assertThrows(ServiceException.class,this::submit);assertEquals(1,projectionCalls);assertEquals(1,auditCalls);assertEquals(0,projections());assertEquals(0,audits());}
}

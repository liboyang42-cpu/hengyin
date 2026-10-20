package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.fasterxml.jackson.databind.*;
import java.lang.reflect.*;
import java.util.*;
import javax.sql.DataSource;
import org.mybatis.spring.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Actual40c shared services and audit/provenance XML; CMS/notification effects are explicit offline edges. */
public final class LegacyCmsApproval40cFixture {
 @Configuration @EnableTransactionManagement(proxyTargetClass=true) static class Config {
  @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
  @Bean org.apache.ibatis.session.SqlSessionFactory factory(DataSource ds)throws Exception {
   SqlSessionFactoryBean f=new SqlSessionFactoryBean();f.setDataSource(ds);f.setTypeAliases(new Class<?>[]{AuditTask.class,AuditRecord.class,CmsMemberTemplate.class,com.chengyinhub.business.domain.vo.MemberTemplateVO.class});
   f.setMapperLocations(new ClassPathResource("mapper/business/CmsMemberTemplateMapper.xml"),new ClassPathResource("mapper/business/LegacyCmsTemplateReadMapper.xml"),new ClassPathResource("mapper/business/AuditTaskMapper.xml"),new ClassPathResource("mapper/business/AuditRecordMapper.xml"),new ClassPathResource("mapper/business/BusinessAuditEventMapper.xml"));return f.getObject();
  }
  @Bean LegacyCmsTemplateApprovalBoundary guard(org.apache.ibatis.session.SqlSessionFactory f){return new LegacyCmsTemplateApprovalBoundary(new SqlSessionTemplate(f).getMapper(LegacyCmsTemplateReadMapper.class));}
 }
 AnnotationConfigApplicationContext context;JdbcTemplate db;AuditTaskServiceImpl audits;TopicBundleReviewService bundle;String snapshot;String supplyBlockReason;Integer lastUserStatus;Boolean lastVisibility;
 void setup()throws Exception {
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:legacy_40c_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");db=new JdbcTemplate(ds);
  db.execute("create table cms_member_template(id bigint primary key,w18_provenance_kind varchar(32),status int default 0)");db.update("insert into cms_member_template(id) values(501)");db.execute("create table w18_cms_template_provenance(template_id bigint primary key)");
  db.execute("create table audit_task(id bigint auto_increment primary key,biz_type varchar(60),biz_id bigint,submitter_id bigint,source_type varchar(80),risk_level varchar(40),status int,current_assignee bigint,submitted_at timestamp,deadline_at timestamp,snapshot_json clob,version int,create_by varchar(64),create_time timestamp,update_by varchar(64),update_time timestamp,remark varchar(200))");
  db.execute("create table audit_record(id bigint auto_increment primary key,task_id bigint,action varchar(40),operator_id bigint,reason_code varchar(80),reason_text varchar(400),snapshot_json clob,reverted_record_id bigint,create_time timestamp)");
  db.execute("create table business_audit_event(id bigint auto_increment primary key,domain_type varchar(40),domain_id bigint,owner_member_id bigint,actor_type varchar(30),actor_member_id bigint,actor_role_code varchar(80),action varchar(100),target_type varchar(60),target_id bigint,relation_id bigint,before_json clob,after_json clob,reason varchar(400),request_id varchar(128),create_time timestamp,unique(domain_type,domain_id,action,request_id))");
  db.execute("create table fixture_side_effect(id bigint auto_increment primary key,kind varchar(100))");db.execute("create alias SHA2 for \"com.chengyinhub.business.service.support.LegacyCmsApproval40cFixture.sha2\"");
  context=new AnnotationConfigApplicationContext();context.registerBean(DataSource.class,()->ds);context.register(Config.class);context.refresh();
  ObjectMapper json=new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);JsonNode raw=json.readTree(new ClassPathResource("legacy-cms-approval-40c/bundle.json").getInputStream());
  CmsTopic topic=json.treeToValue(raw.path("topic").path("content"),CmsTopic.class);topic.setDelFlag(0);
  CmsTopicChapter chapter=json.treeToValue(raw.path("topic").path("chapters").get(0),CmsTopicChapter.class);chapter.setBlocksJson(raw.path("topic").path("chapters").get(0).path("blocksJson").asText());
  CmsTopicNode node=json.treeToValue(raw.path("topic").path("chapters").get(0).path("nodes").get(0),CmsTopicNode.class);
  CmsMemberTemplate template=json.treeToValue(raw.path("bundledTemplates").get(0).path("content"),CmsMemberTemplate.class);template.setDraftStatus(1);template.setStatus(1L);template.setCheckStatus(1);template.setDelFlag(0);
  CmsTopicMapper topics=proxy(CmsTopicMapper.class,(m,a)->{if(m.getName().equals("updateCmsTopic")){lastUserStatus=((CmsTopic)a[0]).getUserStatus();db.update("insert into fixture_side_effect(kind) values('legacy-topic-state')");return 1;}if(m.getName().equals("selectCmsTopicById")||m.getName().equals("selectCmsTopicByIdForUpdate"))return topic;throw new AssertionError(m.getName());});
  CmsTopicChapterMapper chapters=proxy(CmsTopicChapterMapper.class,(m,a)->{if(m.getName().equals("selectCmsTopicChapterList")||m.getName().equals("selectByTopicForUpdate"))return Collections.singletonList(chapter);throw new AssertionError(m.getName());});
  CmsTopicNodeMapper nodes=proxy(CmsTopicNodeMapper.class,(m,a)->{if(m.getName().equals("selectCmsTopicNodeList")||m.getName().equals("selectByTopicForUpdate"))return Collections.singletonList(node);throw new AssertionError(m.getName());});
  CmsMemberTemplateMapper templates=proxy(CmsMemberTemplateMapper.class,(m,a)->{if(m.getName().equals("selectCmsMemberTemplateById"))return template;if(m.getName().equals("selectCmsMemberTemplateByIdsForUpdate"))return Collections.singletonList(template);if(m.getName().equals("approveBundledDraft")){db.update("insert into fixture_side_effect(kind) values('bundled-template')");return 1;}throw new AssertionError(m.getName());});
  bundle=new TopicBundleReviewService(topics,chapters,nodes,templates);ReflectionTestUtils.setField(bundle,"legacyTemplateApprovalBoundary",context.getBean(LegacyCmsTemplateApprovalBoundary.class));snapshot=bundle.freezeForSubmission(101L,7L).getSnapshotJson();
  SqlSessionTemplate sql=new SqlSessionTemplate(context.getBean(org.apache.ibatis.session.SqlSessionFactory.class));AuditTaskServiceImpl target=new AuditTaskServiceImpl();
  ReflectionTestUtils.setField(target,"auditTaskMapper",sql.getMapper(AuditTaskMapper.class));ReflectionTestUtils.setField(target,"auditRecordMapper",sql.getMapper(AuditRecordMapper.class));ReflectionTestUtils.setField(target,"businessAuditEventMapper",sql.getMapper(BusinessAuditEventMapper.class));ReflectionTestUtils.setField(target,"topicBundleReviewService",bundle);ReflectionTestUtils.setField(target,"legacyTemplateApprovalBoundary",context.getBean(LegacyCmsTemplateApprovalBoundary.class));ReflectionTestUtils.setField(target,"cmsTopicMapper",topics);
  ReflectionTestUtils.setField(target,"quotaService",proxy(IQuotaService.class,(m,a)->{if(m.getName().equals("lockAndCheckThemeOnline"))return null;throw new AssertionError(m.getName());}));
  ReflectionTestUtils.setField(target,"imServiceProvider",provider(proxy(IImService.class,(m,a)->{if(m.getName().equals("sendSystem")){db.update("insert into fixture_side_effect(kind) values('notification')");return null;}throw new AssertionError(m.getName());})));
  ReflectionTestUtils.setField(target,"cmsTopicServiceProvider",provider(proxy(ICmsTopicService.class,(m,a)->{if(m.getName().equals("topicSupplyAutoOnlineBlockReason"))return supplyBlockReason;if(m.getName().equals("syncCreativeSquarePostVisibility"))lastVisibility=(Boolean)a[1];if(Arrays.asList("publishApprovedRouteSnapshot","syncCreativeSquarePostVisibility","ensureSelfHostActivityShell").contains(m.getName())){db.update("insert into fixture_side_effect(kind) values(?)",m.getName());return null;}throw new AssertionError(m.getName());})));
  ProxyFactory advice=new ProxyFactory(target);advice.setProxyTargetClass(true);advice.addAdvice(new TransactionInterceptor(context.getBean(PlatformTransactionManager.class),new AnnotationTransactionAttributeSource()));audits=(AuditTaskServiceImpl)advice.getProxy();
 }
 void close(){org.springframework.security.core.context.SecurityContextHolder.clearContext();if(context!=null)context.close();}
 long submit(){return audits.submitOrRefreshTopicBundle(101L,7L,snapshot);}
 public static String sha2(String value,int bits)throws Exception{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format("%02x",b&255));return out.toString();}
 interface Call{Object invoke(Method method,Object[] args)throws Throwable;}
 @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,Call call){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m,a));}
 static <T>ObjectProvider<T> provider(T value){return new ObjectProvider<T>(){public T getObject(){return value;}public T getObject(Object...args){return value;}public T getIfAvailable(){return value;}public T getIfUnique(){return value;}public Iterator<T>iterator(){return Collections.singletonList(value).iterator();}};}
}

package com.chengyinhub.business.service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import java.lang.reflect.*;
import java.io.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mockito;
import org.junit.jupiter.api.Test;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import com.chengyinhub.web.controller.api.ApiCoopController;
import com.chengyinhub.common.exception.ServiceException;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class CoopListReadOnlyMybatisIntegrationTest {
 static final String ROOT=Paths.get("").toAbsolutePath().getParent().toString();
 static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("coop_list_read");
 static String db = MYSQL.databaseName();
 static String base = MYSQL.adminUrl().split("\\?")[0], params="?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
 static void sql(String q)throws Exception{try(Connection c=DriverManager.getConnection(base+db+params,MYSQL.user(),MYSQL.password());Statement s=c.createStatement()){s.execute(q);}}
 static String state()throws Exception{try(Connection c=DriverManager.getConnection(base+db+params,MYSQL.user(),MYSQL.password());Statement s=c.createStatement();ResultSet r=s.executeQuery("select group_concat(concat(id,':',status,':',if(update_time is null,'NULL','SET')) order by id) from coop_invite")){r.next();return r.getString(1);}}
 @Test
 void emptyListDoesNotWriteOtherMembersInvites()throws Exception{ runScenario(0); }
 @Test
 void sqlProjectionPreservesExpiryBoundariesAndFilters()throws Exception{ runScenario(1); }
 @Test
 void controllerScopesAndSlotsUseEffectiveStatus()throws Exception{ runScenario(2); }

 @Test
 void controllerPreservesExactInviteIdForJavascript() throws Exception { runScenario(4); }

 static void verifyExactId(ApiCoopController controller) throws Exception {
  verifyController(controller);
  sql("insert into coop_invite(id,invite_type,from_type,from_id,to_type,to_id,status,create_time) values"
   + "(9007199254740993,1,'merchant',100,'club',50,0,now())");
  String before = state();
  com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
  com.fasterxml.jackson.databind.JsonNode response = json.readTree(json.writeValueAsBytes(controller.list(null)));
  boolean found = false;
  for (com.fasterxml.jackson.databind.JsonNode row : response.path("data").path("sent")) {
   org.junit.jupiter.api.Assertions.assertEquals(row.path("id").asText(), row.path("inviteId").asText());
   org.junit.jupiter.api.Assertions.assertTrue(row.path("id").isIntegralNumber());
   org.junit.jupiter.api.Assertions.assertTrue(row.path("inviteId").isTextual());
   if ("9007199254740993".equals(row.path("inviteId").asText())) {
    found = true;
    org.junit.jupiter.api.Assertions.assertEquals(0, row.path("status").intValue());
   }
  }
  org.junit.jupiter.api.Assertions.assertTrue(found, "large invite must remain available through exact alias");
  org.junit.jupiter.api.Assertions.assertEquals(before, state(), "response alias must not write database");
 }
 @Test
 void jobAndLateWriteGuardsRetainAtomicExpiry()throws Exception{ runScenario(3); }
 void runScenario(int scenario)throws Exception{
  db=MysqlIntegrationTestConfig.fromEnvironment("coop_list_"+UUID.randomUUID().toString().replace("-","").substring(0,8)).databaseName();
  try(Connection c=DriverManager.getConnection(base+params,MYSQL.user(),MYSQL.password());Statement s=c.createStatement()){s.execute("create database `"+db+"`");}
  try{
   String ddl=new String(Files.readAllBytes(Paths.get(ROOT,"sql/base_schema_20260818.sql")),StandardCharsets.UTF_8);
   Matcher m=Pattern.compile("CREATE TABLE `coop_invite` \\(.*?^\\).*?;",Pattern.DOTALL|Pattern.MULTILINE).matcher(ddl);if(!m.find())throw new AssertionError("DDL");sql(m.group());
   sql("ALTER TABLE coop_invite ADD COLUMN supply_station_id BIGINT NULL"); // Scratch schema only; no historical backfill.
   String depositMigration=new String(Files.readAllBytes(Paths.get(ROOT,"chengyinhub-admin/sql/migration_coop_deposit_refund_state_20260913.sql")),StandardCharsets.UTF_8);
   sql(depositMigration.substring(depositMigration.indexOf("ALTER TABLE")));
   sql("insert into coop_invite(id,invite_type,from_type,from_id,to_type,to_id,status,create_time,expire_time,config_deadline) values "
    +"(1,0,'merchant',999,'club',999,0,now(),date_sub(now(),interval 1 hour),null),"
    +"(2,0,'merchant',999,'club',999,0,date_sub(now(),interval 49 hour),date_add(now(),interval 1 day),null),"
    +"(3,0,'merchant',999,'club',999,0,now(),date_add(now(),interval 1 day),null),"
    +"(4,2,'merchant',999,'merchant',999,0,date_sub(now(),interval 49 hour),null,null),"
    +"(5,0,'merchant',999,'club',999,1,now(),null,date_sub(now(),interval 1 hour))");
   UnpooledDataSource ds=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",base+db+params,MYSQL.user(),MYSQL.password());
   Configuration cfg=new Configuration();cfg.getTypeAliasRegistry().registerAlias("CoopInvite",CoopInvite.class);cfg.setEnvironment(new Environment("probe",new SpringManagedTransactionFactory(),ds));
   String resource=ROOT+"/chengyinhub-system/src/main/resources/mapper/business/CoopInviteMapper.xml";
   try(InputStream in=new FileInputStream(resource)){new XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse();}
   SqlSessionTemplate template=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
   CoopInviteServiceImpl actual=new CoopInviteServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "commercialReviewService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.ITopicCommercialReviewService.class));ReflectionTestUtils.setField(actual,"coopInviteMapper",template.getMapper(CoopInviteMapper.class));
   ProxyFactory proxy=new ProxyFactory(actual);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
   ICoopInviteService service=(ICoopInviteService)proxy.getProxy();
   ApiCoopController controller=new ApiCoopController(){@Override public Long getAppUserId(){return 1L;}};
   for(Field f:ApiCoopController.class.getDeclaredFields())if(f.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)){
    Object mock=Mockito.mock(f.getType(),inv->{if(inv.getMethod().getName().equals("require"))throw new ServiceException("No merchant role");return Mockito.RETURNS_DEFAULTS.answer(inv);});ReflectionTestUtils.setField(controller,f.getName(),mock);
   }
   ReflectionTestUtils.setField(controller,"coopInviteService",service);
   String before=state();Object result=controller.list(null);String after=state();
   System.out.println("BEFORE="+before);System.out.println("LIST_RESULT="+result);System.out.println("AFTER_LIST="+after);
   org.junit.jupiter.api.Assertions.assertEquals(before,after,"list must not commit expiry for unrelated members");
   if(scenario==1)verifyProjection(template.getMapper(CoopInviteMapper.class));
   if(scenario==2)verifyController(controller);
   if(scenario==3)verifyGuards(service);
   if(scenario==4)verifyExactId(controller);
  }finally{try(Connection c=DriverManager.getConnection(base+params,MYSQL.user(),MYSQL.password());Statement s=c.createStatement()){s.execute("drop database `"+db+"`");}System.out.println("OWN_SCRATCH_DROPPED=true");}
 }

 static List<Long> ids(List<CoopInvite> rows) {
  return rows.stream().map(CoopInvite::getId).sorted().collect(java.util.stream.Collectors.toList());
 }
 static void verifyProjection(CoopInviteMapper mapper)throws Exception {
  java.util.Date now=new java.util.Date(1800000000000L);
  try(Connection c=DriverManager.getConnection(base+db+params,MYSQL.user(),MYSQL.password());PreparedStatement p=c.prepareStatement(
   "update coop_invite set create_time=?, expire_time=case id when 1 then ? else date_add(?,interval 1 day) end")){
   for(int i=1;i<=3;i++)p.setTimestamp(i,new Timestamp(now.getTime()));p.executeUpdate();
  }
  sql("update coop_invite set create_time=date_sub(create_time,interval 48 hour) where id=2");
  sql("update coop_invite set create_time=null,expire_time=null where id=3");
  sql("update coop_invite set expire_time=null,create_time=date_sub(create_time,interval 49 hour) where id=4");
  String before=state();CoopInvite q=new CoopInvite();q.setFromId(999L);
  List<CoopInvite> all=mapper.selectCoopInviteListReadOnly(q,now);
  Map<Long,Integer> projected=new TreeMap<>();for(CoopInvite row:all){projected.put(row.getId(),row.getStatus());org.junit.jupiter.api.Assertions.assertNull(row.getUpdateTime());}
  q.setStatus(0);org.junit.jupiter.api.Assertions.assertEquals(Arrays.asList(3L,4L),ids(mapper.selectCoopInviteListReadOnly(q,now)));
  org.junit.jupiter.api.Assertions.assertEquals(Integer.valueOf(0),q.getStatus());org.junit.jupiter.api.Assertions.assertEquals(Long.valueOf(999),q.getFromId());
  q.setStatus(5);org.junit.jupiter.api.Assertions.assertEquals(Arrays.asList(1L,2L),ids(mapper.selectCoopInviteListReadOnly(q,now)));
  q.setStatus(1);org.junit.jupiter.api.Assertions.assertEquals(Collections.singletonList(5L),ids(mapper.selectCoopInviteListReadOnly(q,now)));
  q.setStatus(null);q.setToType("club");q.setToId(999L);q.setInviteType(0);q.setFromType("merchant");
  org.junit.jupiter.api.Assertions.assertEquals(Arrays.asList(1L,2L,3L,5L),ids(mapper.selectCoopInviteListReadOnly(q,now)));
  q.setToId(1L);org.junit.jupiter.api.Assertions.assertTrue(mapper.selectCoopInviteListReadOnly(q,now).isEmpty());q.setToId(999L);
  q.setToType("talent");org.junit.jupiter.api.Assertions.assertTrue(mapper.selectCoopInviteListReadOnly(q,now).isEmpty());q.setToType("club");
  q.setFromId(1L);org.junit.jupiter.api.Assertions.assertTrue(mapper.selectCoopInviteListReadOnly(q,now).isEmpty());q.setFromId(999L);
  q.setFromType("talent");org.junit.jupiter.api.Assertions.assertTrue(mapper.selectCoopInviteListReadOnly(q,now).isEmpty());
  org.junit.jupiter.api.Assertions.assertEquals(before,state());
  mapper.expirePendingInvites(now);q=new CoopInvite();q.setFromId(999L);
  Map<Long,Integer> persisted=new TreeMap<>();for(CoopInvite row:mapper.selectCoopInviteList(q))persisted.put(row.getId(),row.getStatus());
  org.junit.jupiter.api.Assertions.assertEquals(projected,persisted,"projection must equal original SQL expiry at exact boundaries");
  sql("update coop_invite set status=0,update_time=null where id in(1,2)");
  sql("update coop_invite set expire_time=date_add(expire_time,interval 1 second) where id=1");
  sql("update coop_invite set create_time=date_add(create_time,interval 1 second) where id=2");
  q.setStatus(0);org.junit.jupiter.api.Assertions.assertEquals(Arrays.asList(1L,2L,3L,4L),ids(mapper.selectCoopInviteListReadOnly(q,now)));
  sql("update coop_invite set del_flag=2 where id=2");q.setStatus(null);
  org.junit.jupiter.api.Assertions.assertFalse(ids(mapper.selectCoopInviteListReadOnly(q,now)).contains(2L));
  sql("update coop_invite set del_flag=0,status=0,update_time=null,create_time=now(),expire_time=date_sub(now(),interval 1 hour) where id in(1,2)");
 }
 static void verifyController(ApiCoopController controller)throws Exception {
  com.chengyinhub.business.access.merchant.IMerchantAccessService access=
   (com.chengyinhub.business.access.merchant.IMerchantAccessService)ReflectionTestUtils.getField(controller,"merchantAccessService");
  Mockito.doReturn(new com.chengyinhub.business.access.merchant.MerchantAccessContext(1L,77L,100L,
   com.chengyinhub.business.access.merchant.MerchantRoleCode.MERCHANT_MANAGER)).when(access).require(1L,
   com.chengyinhub.business.access.merchant.MerchantPermission.COOP_MANAGE);
  com.chengyinhub.business.service.IClubService clubs=(com.chengyinhub.business.service.IClubService)ReflectionTestUtils.getField(controller,"clubService");
  com.chengyinhub.business.domain.Club club=new com.chengyinhub.business.domain.Club();club.setId(50L);club.setMemberId(1L);
  Mockito.when(clubs.selectClubsByMemberId(1L)).thenReturn(Collections.singletonList(club));
  Mockito.when(clubs.selectClubById(50L)).thenReturn(club);
  com.chengyinhub.business.service.ICmsTopicService topics=(com.chengyinhub.business.service.ICmsTopicService)ReflectionTestUtils.getField(controller,"cmsTopicService");
  com.chengyinhub.business.domain.CmsTopic topic=new com.chengyinhub.business.domain.CmsTopic();topic.setId(7L);topic.setName("topic");
  Mockito.when(topics.selectCmsTopicById(7L)).thenReturn(topic);
  sql("update coop_invite set from_id=100,to_type='merchant',to_id=888,game_id=8 where id in(1,3,4)");
  sql("update coop_invite set to_type='merchant',to_id=100 where id=2");
  sql("update coop_invite set from_id=100,to_type='club',to_id=50,topic_id=7 where id=5");
  sql("insert into coop_invite(id,invite_type,from_type,from_id,to_type,to_id,status,create_time) values(6,1,'merchant',888,'club',50,0,now())");
  String before=state();Map data=(Map)controller.list(null).get("data");
  org.junit.jupiter.api.Assertions.assertEquals(Arrays.asList(1L,3L,4L,5L),ids((List<CoopInvite>)data.get("sent")));
  org.junit.jupiter.api.Assertions.assertEquals(Arrays.asList(2L,5L,6L),ids((List<CoopInvite>)data.get("received")));
  Map slots=(Map)data.get("slots");
  org.junit.jupiter.api.Assertions.assertEquals(1,((Map)((Map)slots.get("byGame")).get("8")).get("pending"));
  org.junit.jupiter.api.Assertions.assertEquals(1,((Map)((Map)slots.get("byTopic")).get("7")).get("accepted"));
  CoopInvite q=new CoopInvite();q.setStatus(5);Map expired=(Map)controller.list(q).get("data");
  org.junit.jupiter.api.Assertions.assertEquals(Collections.singletonList(1L),ids((List<CoopInvite>)expired.get("sent")));
  org.junit.jupiter.api.Assertions.assertEquals(Collections.singletonList(2L),ids((List<CoopInvite>)expired.get("received")));
  q.setStatus(0);Map pending=(Map)controller.list(q).get("data");
  org.junit.jupiter.api.Assertions.assertEquals(Arrays.asList(3L,4L),ids((List<CoopInvite>)pending.get("sent")));
  org.junit.jupiter.api.Assertions.assertEquals(Collections.singletonList(6L),ids((List<CoopInvite>)pending.get("received")));
  org.junit.jupiter.api.Assertions.assertEquals(before,state(),"all list branches must leave database unchanged");
 }
 static void verifyGuards(ICoopInviteService service)throws Exception {
  // 阻断#8 / 台账 1-11:行5=已接受+config_deadline 已过(存量 72h 死线),过期任务只处理挂起邀约,不得动它
  org.junit.jupiter.api.Assertions.assertEquals(2,service.expireStaleInvites());
  org.junit.jupiter.api.Assertions.assertEquals(0,service.expireStaleInvites());
  org.junit.jupiter.api.Assertions.assertTrue(state().contains("5:1:NULL"),"accepted invite must stay status=1: "+state());
  sql("insert into coop_invite(id,invite_type,from_type,from_id,to_type,to_id,status,create_time,expire_time) values "
   +"(7,0,'talent',777,'merchant',888,0,now(),date_sub(now(),interval 1 hour)),"
   +"(8,0,'talent',777,'merchant',888,0,now(),date_sub(now(),interval 1 hour)),"
   +"(9,0,'talent',777,'merchant',888,0,now(),date_sub(now(),interval 1 hour))");
  RuntimeException accept=org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,()->service.acceptInviteWithLock(7L,null));
  RuntimeException cancel=org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,()->service.cancelPendingInviteWithLock(8L,777L,null));
  RuntimeException reject=org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,()->service.rejectPendingInviteWithLock(9L,888L,"reason"));
  for(RuntimeException ex:Arrays.asList(accept,cancel,reject))org.junit.jupiter.api.Assertions.assertEquals("CoopInviteExpiredException",ex.getClass().getSimpleName());
  org.junit.jupiter.api.Assertions.assertTrue(state().endsWith("7:5:SET,8:5:SET,9:5:SET"));
 }
}

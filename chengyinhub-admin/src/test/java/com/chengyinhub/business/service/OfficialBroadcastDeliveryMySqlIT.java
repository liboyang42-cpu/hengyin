package com.chengyinhub.business.service;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.notify.*;
import com.chengyinhub.business.service.impl.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real broadcast mapper/locks/CAS transactions. IM uses an isolated SQL idempotency fixture,
 * not a claim that the full IM domain or external provider was integrated. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfficialBroadcastDeliveryMySqlIT {
 final MysqlIntegrationTestConfig mysql=MysqlIntegrationTestConfig.fromEnvironment("bc_delivery_it");
 JdbcTemplate jdbc;DataSourceTransactionManager manager;OfficialBroadcastMapper batches;OfficialBroadcastLogMapper logs;OfficialBroadcastDeliveryService service;IImService im;WxSubscribeChannel channel;OfficialBroadcast bc;
 @BeforeAll void setup()throws Exception{
  new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(),mysql.user(),mysql.password())).execute("CREATE DATABASE "+mysql.databaseName());
  DriverManagerDataSource source=new DriverManagerDataSource(mysql.databaseUrl(),mysql.user(),mysql.password());jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
  try(Connection c=source.getConnection()){for(String f:List.of("migration_official_broadcast.sql","migration_20261026_official_broadcast_delivery.sql"))ScriptUtils.executeSqlScript(c,new FileSystemResource("sql/"+f));}
  jdbc.execute("CREATE TABLE fixture_member_fence(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
  jdbc.execute("CREATE TABLE fixture_intent(id BIGINT PRIMARY KEY AUTO_INCREMENT,event_key VARCHAR(96) UNIQUE,delivered INT NOT NULL DEFAULT 0) ENGINE=InnoDB");
  Configuration cfg=new Configuration(new Environment("test",new SpringManagedTransactionFactory(),source));cfg.getTypeAliasRegistry().registerAlias(OfficialBroadcast.class);cfg.getTypeAliasRegistry().registerAlias(OfficialBroadcastLog.class);
  for(Class<?> type:List.of(OfficialBroadcastMapper.class,OfficialBroadcastLogMapper.class)){cfg.addMapper(type);String resource="mapper/business/"+type.getSimpleName()+".xml";try(InputStream xml=getClass().getClassLoader().getResourceAsStream(resource)){assertNotNull(xml);new XMLMapperBuilder(xml,cfg,resource,cfg.getSqlFragments()).parse();}}
  SqlSessionTemplate session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));batches=session.getMapper(OfficialBroadcastMapper.class);logs=session.getMapper(OfficialBroadcastLogMapper.class);
 }
 @BeforeEach void reset(){
  for(String table:List.of("official_broadcast_creation","official_broadcast_log","official_broadcast","fixture_intent","fixture_member_fence"))jdbc.update("DELETE FROM "+table);
  jdbc.update("INSERT INTO official_broadcast(id,title,audience,channels,status) VALUES(9,'frozen','player','inapp,subscribe',1)");bc=batches.selectOfficialBroadcastById(9L);
  jdbc.update("INSERT INTO fixture_member_fence(id) VALUES(7)");
  im=mock(IImService.class);channel=mock(WxSubscribeChannel.class);
  when(im.enqueueSystemNotification(anyLong(),anyInt(),anyString(),anyString(),anyString())).thenAnswer(c->{String key=c.getArgument(4);jdbc.update("INSERT IGNORE INTO fixture_intent(event_key) VALUES(?)",key);return jdbc.queryForObject("SELECT id FROM fixture_intent WHERE event_key=?",Long.class,key);});
  when(im.deliverSystemNotification(anyLong())).thenAnswer(c->{Long id=c.getArgument(0);jdbc.queryForMap("SELECT * FROM fixture_intent WHERE id=? FOR UPDATE",id);jdbc.update("UPDATE fixture_intent SET delivered=1 WHERE id=?",id);return id;});
  service=new OfficialBroadcastDeliveryService(logs,batches,im,channel,manager);
  // Same SQL/transaction resource as the real delivery mapper; this remains a member-fact adapter,
  // not evidence that the full member/IM domain was assembled.
  UmsMemberMapper members=mock(UmsMemberMapper.class);
  when(members.lockMemberById(anyLong())).thenAnswer(c->{
   List<Long> ids=jdbc.queryForList("SELECT id FROM fixture_member_fence WHERE id=? FOR UPDATE",Long.class,c.getArgument(0));
   return ids.isEmpty()?null:ids.get(0);
  });
  org.springframework.test.util.ReflectionTestUtils.setField(service,"members",members);
 }
 void freeze(String channel){service.prepare(bc,List.of(Map.of("member",7L,"role","player","channel",channel,"title","frozen","sub","original","state","READY")));}
 void parallel(Runnable a,Runnable b)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<?> x=pool.submit(a),y=pool.submit(b);x.get(10,TimeUnit.SECONDS);y.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}}
 @Test void falsePersistsUnknownAndConcurrentResumeDoesNotResend()throws Exception{freeze("subscribe");when(channel.sendOfficialBroadcast(anyLong(),anyString(),anyString())).thenReturn(new com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult(com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.State.UNKNOWN,com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.Reason.TRANSPORT_UNCONFIRMED,null));parallel(()->service.resume(bc),()->service.resume(bc));service.resume(bc);verify(channel,times(1)).sendOfficialBroadcast(anyLong(),anyString(),anyString());assertEquals("UNKNOWN",logs.deliveryRows(9L).get(0).get("delivery_state"));assertEquals(0,logs.countReached(9L));}
 @Test void failedInappRollsBackThenConcurrentRecoveryHasOneDurableEffect()throws Exception{
  freeze("inapp");when(im.deliverSystemNotification(anyLong())).thenAnswer(c->{jdbc.update("UPDATE fixture_intent SET delivered=1");throw new IllegalStateException("rollback fixture");});service.resume(bc);assertEquals("RETRYABLE",logs.deliveryRows(9L).get(0).get("delivery_state"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM fixture_intent",Integer.class));
  when(im.deliverSystemNotification(anyLong())).thenAnswer(c->{Long id=c.getArgument(0);jdbc.update("UPDATE fixture_intent SET delivered=1 WHERE id=?",id);return id;});parallel(()->service.resume(bc),()->service.resume(bc));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM fixture_intent WHERE delivered=1",Integer.class));assertEquals(1,logs.countReached(9L));
 }
 @Test void retryCannotExpandFrozenAudienceOrRewriteOriginalPayload(){freeze("inapp");service.prepare(bc,List.of(Map.of("member",8L,"role","merchant","channel","inapp","title","changed","sub","changed","state","READY")));assertEquals(1,logs.deliveryRows(9L).size());service.resume(bc);verify(im).enqueueSystemNotification(eq(7L),anyInt(),eq("frozen"),anyString(),eq("official_bc:9:7:inapp"));}
 @Test void legacyPartialBatchDoesNotAcquireCurrentAudience(){logs.snapshot(9L,7L,"player","inapp","old","old",null);service.prepare(bc,List.of(Map.of("member",8L,"role","player","channel","inapp","title","new","sub","new","state","READY")));assertEquals(1,logs.deliveryRows(9L).size());service.resume(bc);verifyNoInteractions(im,channel);assertEquals(1,((Map<?,?>)service.summary(9L).get("deliveryCounts")).get("LEGACY_UNCONFIRMED"));}
 @Test void creationCancellationSerializesWithLatePublisher()throws Exception{
  TransactionTemplate tx=new TransactionTemplate(manager);String key="a".repeat(32);
  tx.execute(s->{batches.reserveCreation("ADMIN:7",key);batches.lockCreation("ADMIN:7",key);batches.finishCreation("ADMIN:7",key,"CANCELLED",null);return null;});
  parallel(()->tx.execute(s->{batches.reserveCreation("ADMIN:7",key);assertEquals("CANCELLED",batches.lockCreation("ADMIN:7",key).get("state"));assertEquals(0,batches.finishCreation("ADMIN:7",key,"APPLIED",9L));return null;}),()->assertEquals("CANCELLED",batches.readCreation("ADMIN:7",key).get("state")));
 }
 @AfterAll void cleanup(){new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(),mysql.user(),mysql.password())).execute("DROP DATABASE "+mysql.databaseName());}
 @Test void typedNonSubmissionAndRejectionAreDurableWithoutLegacyReplay(){
  freeze("subscribe");when(channel.sendOfficialBroadcast(anyLong(),anyString(),anyString())).thenReturn(new com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult(com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.State.NOT_SUBMITTED,com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.Reason.TOKEN_MISSING,null)).thenReturn(new com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult(com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.State.REJECTED,com.chengyinhub.business.service.IWxSubscribeMsgService.OfficialBroadcastResult.Reason.PROVIDER_REJECTED,43101));
  service.resume(bc);assertEquals("NOT_SUBMITTED_TOKEN",logs.deliveryRows(9L).get(0).get("delivery_state"));service.resume(bc);service.resume(bc);assertEquals("REJECTED_43101",logs.deliveryRows(9L).get(0).get("delivery_state"));verify(channel,times(2)).sendOfficialBroadcast(anyLong(),anyString(),anyString());assertEquals(0,logs.countReached(9L));
 }
}

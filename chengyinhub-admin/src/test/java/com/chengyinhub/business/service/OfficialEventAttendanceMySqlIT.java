package com.chengyinhub.business.service;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.TeamEventCommand;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.system.service.ISysConfigService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.InputStream;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Actual attendance+receipt MyBatis SQL, unique keys and transactional concurrency.
 * Event/participant adapters read isolated SQL fixtures, not full official domain integration. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfficialEventAttendanceMySqlIT {
 final MysqlIntegrationTestConfig mysql=MysqlIntegrationTestConfig.fromEnvironment("event_attendance_it");
 JdbcTemplate jdbc;DataSourceTransactionManager manager;TeamEventCommandService commands;OfficialEventAttendanceMapper records;TeamEventCommandMapper receipts;
 @BeforeAll void setup()throws Exception{
  new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(),mysql.user(),mysql.password())).execute("CREATE DATABASE "+mysql.databaseName());
  DriverManagerDataSource source=new DriverManagerDataSource(mysql.databaseUrl(),mysql.user(),mysql.password());jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
  try(Connection c=source.getConnection()) {for(String f:List.of("team_event_command_receipt","official_event_attendance"))ScriptUtils.executeSqlScript(c,new FileSystemResource("sql/migration_20261026_"+f+".sql"));}
  jdbc.execute("CREATE TABLE fixture_event(id BIGINT PRIMARY KEY, state INT, control_state VARCHAR(20)) ENGINE=InnoDB");
  jdbc.execute("CREATE TABLE fixture_person(id BIGINT PRIMARY KEY, event_id BIGINT, member_id BIGINT, state VARCHAR(20)) ENGINE=InnoDB");
  Configuration cfg=new Configuration(new Environment("test",new SpringManagedTransactionFactory(),source));
  for(Class<?> type:List.of(OfficialEventAttendanceMapper.class,TeamEventCommandMapper.class)){
   cfg.addMapper(type);String resource="mapper/business/"+type.getSimpleName()+".xml";
   try(InputStream xml=getClass().getClassLoader().getResourceAsStream(resource)){assertNotNull(xml);new XMLMapperBuilder(xml,cfg,resource,cfg.getSqlFragments()).parse();}
  }
  SqlSessionTemplate session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));records=session.getMapper(OfficialEventAttendanceMapper.class);receipts=session.getMapper(TeamEventCommandMapper.class);
 }
 @BeforeEach void reset(){
  for(String t:List.of("official_event_attendance","official_event_attendance_config","team_event_command_receipt","fixture_person","fixture_event"))jdbc.update("DELETE FROM "+t);
  jdbc.update("INSERT INTO fixture_event VALUES(19,2,'NORMAL')");jdbc.update("INSERT INTO fixture_person VALUES(42,19,7,'ACTIVE')");
  long now=System.currentTimeMillis();jdbc.update("INSERT INTO official_event_attendance_config(event_id,config_json,updated_by) VALUES(19,?,8)","{\"checkinOpenAt\":"+(now-100000)+",\"checkinCloseAt\":"+(now+100000)+"}");
  wire(records);
 }
 void wire(OfficialEventAttendanceMapper attendanceMapper){
  OfficialEventMapper events=mock(OfficialEventMapper.class);OfficialEventV2Mapper people=mock(OfficialEventV2Mapper.class);OfficialEventV2ServiceImpl authority=mock(OfficialEventV2ServiceImpl.class);ISysConfigService settings=mock(ISysConfigService.class);
  when(events.selectOfficialEventByIdForUpdate(19L)).thenAnswer(call->{Map<String,Object> row=jdbc.queryForMap("SELECT * FROM fixture_event WHERE id=19 FOR UPDATE");OfficialEvent e=new OfficialEvent();e.setId(19L);e.setContractVersion(2);e.setStatus(((Number)row.get("state")).intValue());e.setControlState((String)row.get("control_state"));return e;});
  when(people.selectParticipation(19L,7L)).thenAnswer(call->{Map<String,Object> row=jdbc.queryForMap("SELECT * FROM fixture_person WHERE id=42");OfficialEventParticipation p=new OfficialEventParticipation();p.setId(42L);p.setMemberId(7L);p.setState((String)row.get("state"));return p;});
  when(settings.selectConfigByKey("official_publish_whitelist")).thenReturn("8");when(authority.canOperateAttendance(any(),eq(8L))).thenReturn(true);
  OfficialEventAttendanceService service=new OfficialEventAttendanceService(events,people,attendanceMapper,authority,settings);
  commands=new TeamEventCommandService(receipts,mock(IPlayTeamService.class),mock(IOfficialEventV2Service.class),mock(IOfficialEventService.class),manager);ReflectionTestUtils.setField(commands,"attendance",service);
 }
 TeamEventCommand input(String action,int n,String code){TeamEventCommand c=new TeamEventCommand();c.setTargetId(19L);c.setAction(action);c.setRequestId(String.format("%032x",n));c.setCredentialCode(code);return c;}
 Map<String,Object> run(String action,int n,String code){return commands.execute(action.equals("attendance-verify")?8L:7L,action.equals("attendance-verify")?"EVENT":"SIGNUP",input(action,n,code));}
 String code(){return jdbc.queryForObject("SELECT credential FROM official_event_attendance WHERE participation_id=42",String.class);}
 @Test void concurrentClaimsCreateOneCredentialAndTwoDurableReceipts()throws Exception{
  ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<?> a=pool.submit(()->assertEquals("APPLIED",run("attendance-claim",1,null).get("state")));Future<?> b=pool.submit(()->assertEquals("APPLIED",run("attendance-claim",2,null).get("state")));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM official_event_attendance",Integer.class));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM team_event_command_receipt WHERE state='APPLIED'",Integer.class));assertEquals(64,code().length());
 }
 @Test void rotateInvalidatesOldCodeAndSameRequestCannotRotateTwice(){
  run("attendance-claim",1,null);String old=code();run("attendance-rotate",2,null);String fresh=code();assertNotEquals(old,fresh);run("attendance-rotate",2,null);assertEquals(fresh,code());
  assertEquals("REJECTED",run("attendance-verify",3,old).get("state"));assertNull(jdbc.queryForMap("SELECT * FROM official_event_attendance").get("checked_at"));
  assertEquals("APPLIED",run("attendance-verify",4,fresh).get("state"));Object checked=jdbc.queryForMap("SELECT * FROM official_event_attendance").get("checked_at");
  jdbc.update("UPDATE fixture_event SET state=5");assertEquals("APPLIED",run("attendance-verify",5,fresh).get("state"));assertEquals(checked,jdbc.queryForMap("SELECT * FROM official_event_attendance").get("checked_at"));
 }
 @Test void pausedAndInvalidParticipationRejectWithoutAttendanceWrite(){
  jdbc.update("UPDATE fixture_event SET control_state='PAUSED'");assertEquals("REJECTED",run("attendance-claim",1,null).get("state"));
  jdbc.update("UPDATE fixture_event SET control_state='NORMAL'");jdbc.update("UPDATE fixture_person SET state='LEFT'");assertEquals("REJECTED",run("attendance-claim",2,null).get("state"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM official_event_attendance",Integer.class));
 }
 @Test void failedCheckinRollsBackFactBeforePersistingRejection(){
  run("attendance-claim",1,null);String code=code();OfficialEventAttendanceMapper failing=mock(OfficialEventAttendanceMapper.class,delegatesTo(records));
  doAnswer(call->{records.checkin(call.getArgument(0),call.getArgument(1),call.getArgument(2));throw new IllegalStateException("test rollback");}).when(failing).checkin(any(),any(),any());wire(failing);
  assertEquals("REJECTED",run("attendance-verify",2,code).get("state"));assertNull(jdbc.queryForMap("SELECT * FROM official_event_attendance").get("checked_at"));
 }
 @AfterAll void cleanup(){new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(),mysql.user(),mysql.password())).execute("DROP DATABASE "+mysql.databaseName());}
}

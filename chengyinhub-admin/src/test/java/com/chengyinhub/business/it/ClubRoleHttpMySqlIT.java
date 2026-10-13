package com.chengyinhub.business.it;

import com.chengyinhub.business.access.club.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.ClubRoleAssignmentServiceImpl;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.framework.config.SecurityConfig;
import com.chengyinhub.framework.config.properties.PermitAllUrlProperties;
import com.chengyinhub.framework.security.filter.JwtAuthenticationTokenFilter;
import com.chengyinhub.framework.security.handle.*;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import com.chengyinhub.framework.web.service.*;
import com.chengyinhub.web.controller.api.ApiClubRoleController;
import com.fasterxml.jackson.databind.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.*;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic accounts only. Real Tomcat HTTP, JWT/filter, transactions and production MyBatis XML.
 * Redis session storage/epoch and unrelated club projection are explicit in-memory seams.
 * The permission probe is test-only; it calls the real current-DB permission service.
 */
@SpringBootTest(classes=ClubRoleHttpMySqlIT.App.class,
    webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.address=127.0.0.1", "chengyin.jobs.enabled=false",
    "token.secret=synthetic-role-http-only-secret-synthetic-role-http-only",
    "spring.autoconfigure.exclude=com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceAutoConfigure,org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration"})
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
class ClubRoleHttpMySqlIT {
    static final String SUITE="role_http";
    static final ObjectMapper JSON=new ObjectMapper();
    static DataSource database;
    @Autowired TestRestTemplate http;
    @Autowired AppTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired IClubRoleAssignmentService roles;
    String owner, target;

    @BeforeEach void seed() {
        jdbc.update("DELETE FROM business_audit_event");
        jdbc.update("DELETE FROM club_role_assignment");
        jdbc.update("UPDATE club_member SET role=0");
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(roles));
        owner=token(7001); target=token(7002);
    }
    @AfterAll static void cleanup() throws Exception {
        if(database!=null) try(Connection c=database.getConnection();Statement s=c.createStatement()) {
            s.execute("DROP DATABASE `"+MybatisItDatabase.databaseName(SUITE)+"`");
        }
    }
    String token(long id) {
        MockHttpServletRequest request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            AppUser user=new AppUser();user.setUserId(id);
            LoginAppUser login=new LoginAppUser(id,user);login.setSessionEpoch(1L);
            return "Bearer "+tokens.createAppToken(login);
        } finally {RequestContextHolder.resetRequestAttributes();}
    }
    JsonNode post(String path,String token,String body) throws Exception {
        HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null) h.set("Authorization",token);
        ResponseEntity<String> r=http.postForEntity(path,new HttpEntity<>(body,h),String.class);
        assertEquals(token==null?401:200,r.getStatusCode().value(),"Production HTTP/envelope convention");
        return JSON.readTree(r.getBody());
    }
    String assign(String request) {return "{\"clubId\":71,\"targetMemberId\":7002,\"roleCode\":\"CLUB_OPERATOR\",\"requestId\":\""+request+"\"}";}
    String revoke(long id,String request) {return "{\"clubId\":71,\"assignmentId\":"+id+",\"version\":0,\"reason\":\"synthetic test\",\"requestId\":\""+request+"\"}";}
    int count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}

    @Test void replayAndRevokeUseRealTransactionsAndOldJwtLosesPermission() throws Exception {
        String path="/api/club/roles/assign";
        JsonNode first=post(path,owner,assign("assign-once"));assertEquals(200,first.path("code").asInt(),first.toString());
        long id=first.path("data").path("id").asLong();assertTrue(id>0);
        JsonNode again=post(path,owner,assign("assign-once"));assertEquals(200,again.path("code").asInt());
        assertEquals(id,again.path("data").path("id").asLong());assertEquals(1,count("club_role_assignment"));assertEquals(1,count("business_audit_event"));
        assertEquals(409,post(path,owner,assign("assign-once").replace("CLUB_OPERATOR","CLUB_CO_OWNER")).path("code").asInt());
        assertEquals(200,post("/api/synthetic-role-probe",target,"{\"clubId\":71}").path("code").asInt());
        assertEquals(403,post("/api/club/roles/revoke",target,revoke(id,"non-owner")).path("code").asInt());
        assertEquals(403,post("/api/club/roles/revoke",owner,revoke(id,"foreign").replace("\"clubId\":71","\"clubId\":72")).path("code").asInt());
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM club_role_assignment WHERE id=?",String.class,id));
        assertEquals(1,count("business_audit_event"));
        JsonNode before=post("/api/club/access/me",target,"{\"clubId\":71}");
        assertTrue(before.path("data").path("permissions").toString().contains(ClubPermission.CONTENT_MANAGE));
        assertEquals(200,post("/api/club/roles/revoke",owner,revoke(id,"revoke-once")).path("code").asInt());
        assertEquals(200,post("/api/club/roles/revoke",owner,revoke(id,"revoke-once")).path("code").asInt());
        assertEquals(1,jdbc.queryForObject("SELECT version FROM club_role_assignment WHERE id=?",Integer.class,id));
        assertEquals("REVOKED",jdbc.queryForObject("SELECT status FROM club_role_assignment WHERE id=?",String.class,id));
        assertEquals(2,count("business_audit_event"));
        assertEquals(403,post("/api/synthetic-role-probe",target,"{\"clubId\":71}").path("code").asInt());
        JsonNode after=post("/api/club/access/me",target,"{\"clubId\":71}");
        assertEquals(200,after.path("code").asInt());assertTrue(after.path("data").path("active").asBoolean());
        assertFalse(after.path("data").path("permissions").toString().contains(ClubPermission.CONTENT_MANAGE));
    }

    @Test void malformedAndExtraFieldsNeverReachRoleWrites() throws Exception {
        for(String body:List.of("{",assign("bad").replace("7002","\"not-an-id\""),
            assign("bad").replace("}",",\"ownerMemberId\":7002}"))) {
            assertEquals(400,post("/api/club/roles/assign",owner,body).path("code").asInt(),body);
            assertEquals(0,count("club_role_assignment"));assertEquals(0,count("business_audit_event"));
        }
        long id=post("/api/club/roles/assign",owner,assign("valid")).path("data").path("id").asLong();
        for(String body:List.of("{",revoke(id,"bad").replace("}",",\"force\":true}"))) {
            assertEquals(400,post("/api/club/roles/revoke",owner,body).path("code").asInt(),body);
            assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM club_role_assignment WHERE id=?",String.class,id));
            assertEquals(1,count("business_audit_event"));
        }
    }

    @Test void anonymousNonOwnerAndForeignScopeCannotAssign() throws Exception {
        assertEquals(401,post("/api/club/roles/assign",null,assign("anonymous")).path("code").asInt());
        assertEquals(403,post("/api/club/roles/assign",target,assign("non-owner")).path("code").asInt());
        assertEquals(403,post("/api/club/roles/assign",owner,assign("foreign").replace("71","72")).path("code").asInt());
        assertEquals(400,post("/api/club/roles/assign",owner,assign("")).path("code").asInt());
        assertEquals(0,count("club_role_assignment"));assertEquals(0,count("business_audit_event"));
    }

    @SpringBootConfiguration
    @EnableTransactionManagement
    @EnableAutoConfiguration(exclude={org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
        org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration.class,
        org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration.class})
    @Import({SecurityConfig.class,PermitAllUrlProperties.class,JwtAuthenticationTokenFilter.class,
        AuthenticationEntryPointImpl.class,AppTokenService.class,ApiClubRoleController.class,
        ClubAccessServiceImpl.class,ClubRoleAssignmentServiceImpl.class,GlobalExceptionHandler.class,PermissionProbe.class})
    static class App {
        @Bean DataSource dataSource() throws Exception {
            assertTrue(Set.of("127.0.0.1","localhost").contains(MybatisItDatabase.host()),"Only local disposable MySQL");
            database=new DriverManagerDataSource(MybatisItDatabase.url(SUITE),MybatisItDatabase.user(),MybatisItDatabase.password());
            JdbcTemplate j=new JdbcTemplate(database);
            // Synthetic supporting tables; role/audit DDL below comes from the shipped migrations.
            String xml=new String(new ClassPathResource("mapper/business/ClubMapper.xml").getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            String columns=xml.split("<sql id=\"selectClubVo\">",2)[1].split("from club",2)[0].replace("select","").trim();
            List<String> defs=new ArrayList<>();for(String col:columns.split(",")) defs.add(col.trim()+(col.trim().equals("id")?" BIGINT":" TEXT"));
            j.execute("CREATE TABLE club ("+String.join(",",defs)+", PRIMARY KEY(id)) ENGINE=InnoDB");
            j.execute("CREATE TABLE club_member(id BIGINT PRIMARY KEY,club_id BIGINT,member_id BIGINT,role INT,status INT,join_time DATETIME,del_flag INT) ENGINE=InnoDB");
            j.execute("CREATE TABLE ums_member(id BIGINT PRIMARY KEY,status INT,del_flag INT,role VARCHAR(16)) ENGINE=InnoDB");
            j.execute("CREATE TABLE cms_activity(id BIGINT PRIMARY KEY,club_id BIGINT,topic_id BIGINT,del_flag INT) ENGINE=InnoDB");
            for(String file:List.of("migration_club_role_assignment_20260823.sql","migration_business_audit_event_20260823.sql")) {
                String sql=Files.readString(Path.of("sql",file));int start=sql.indexOf("CREATE TABLE");int end=sql.indexOf(';',start);j.execute(sql.substring(start,end));
            }
            j.execute("ALTER TABLE business_audit_event ADD COLUMN actor_type VARCHAR(32) DEFAULT 'MEMBER'");
            j.update("INSERT INTO club(id,member_id,status,del_flag,name) VALUES('71','7001','1','0','synthetic'),('72','7003','1','0','foreign')");
            j.update("INSERT INTO ums_member VALUES(7001,1,0,'club'),(7002,1,0,'player'),(7003,1,0,'player')");
            j.update("INSERT INTO club_member VALUES(1,71,7002,0,1,NOW(),0)");return database;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource d){return new JdbcTemplate(d);}
        @Bean DataSourceTransactionManager transactionManager(DataSource d){return new DataSourceTransactionManager(d);}
        @Bean SqlSessionFactory sqlSessionFactory(DataSource d) throws Exception {
            SqlSessionFactoryBean f=new SqlSessionFactoryBean();f.setDataSource(d);f.setTypeAliasesPackage("com.chengyinhub.business.domain");
            f.setMapperLocations(Arrays.stream(new String[]{"Club","ClubMember","ClubRoleAssignment","UmsMember","CmsActivity","BusinessAuditEvent"})
                .map(n->new ClassPathResource("mapper/business/"+n+"Mapper.xml")).toArray(ClassPathResource[]::new));return f.getObject();
        }
        @Bean SqlSessionTemplate session(SqlSessionFactory f){return new SqlSessionTemplate(f);}
        @Bean ClubMapper clubMapper(SqlSessionTemplate s){return s.getMapper(ClubMapper.class);}
        @Bean ClubMemberMapper clubMemberMapper(SqlSessionTemplate s){return s.getMapper(ClubMemberMapper.class);}
        @Bean ClubRoleAssignmentMapper assignmentMapper(SqlSessionTemplate s){return s.getMapper(ClubRoleAssignmentMapper.class);}
        @Bean UmsMemberMapper memberMapper(SqlSessionTemplate s){return s.getMapper(UmsMemberMapper.class);}
        @Bean CmsActivityMapper activityMapper(SqlSessionTemplate s){return s.getMapper(CmsActivityMapper.class);}
        @Bean BusinessAuditEventMapper auditMapper(SqlSessionTemplate s){return s.getMapper(BusinessAuditEventMapper.class);}
        @Bean IClubService clubService(ClubMapper mapper){IClubService s=mock(IClubService.class);when(s.selectClubById(anyLong())).thenAnswer(i->mapper.selectClubById(i.getArgument(0)));return s;}
        @Bean RedisCache redisCache(){
            Map<String,Object> storage=new ConcurrentHashMap<>();RedisCache r=mock(RedisCache.class);
            doAnswer(i->{storage.put(i.getArgument(0),i.getArgument(1));return null;}).when(r).setCacheObject(anyString(),any(),anyInt(),any(TimeUnit.class));
            when(r.getCacheObject(anyString())).thenAnswer(i->storage.get(i.getArgument(0)));return r;
        }
        @Bean org.springframework.data.redis.core.RedisTemplate<Object,Object> redisTemplate(){return mock(org.springframework.data.redis.core.RedisTemplate.class);}
        @Bean IAppSessionRegistryService epochs(){IAppSessionRegistryService s=mock(IAppSessionRegistryService.class);when(s.currentOrCreateEpoch(anyLong())).thenReturn(1L);when(s.isCurrentEpoch(anyLong(),anyLong())).thenReturn(true);return s;}
        @Bean com.chengyinhub.system.mapper.SysUserMapper sysUserMapper(){return mock(com.chengyinhub.system.mapper.SysUserMapper.class);}
        @Bean com.chengyinhub.framework.web.service.SysPermissionService sysPermissionService(){return mock(com.chengyinhub.framework.web.service.SysPermissionService.class);}
        @Bean com.chengyinhub.system.service.ISysRoleService sysRoleService(){return mock(com.chengyinhub.system.service.ISysRoleService.class);}
        @Bean com.chengyinhub.system.service.ISysMenuService sysMenuService(){return mock(com.chengyinhub.system.service.ISysMenuService.class);}
        @Bean TokenService tokenService(){return mock(TokenService.class);}
        @Bean LogoutSuccessHandlerImpl logout(){return mock(LogoutSuccessHandlerImpl.class);}
        @Bean UserDetailsService userDetailsService(){return username->null;}
        @Bean CorsFilter corsFilter(){return new CorsFilter(new UrlBasedCorsConfigurationSource());}
    }
    @RestController
    static class PermissionProbe extends BaseController {
        @Autowired IClubAccessService access;
        @PostMapping("/api/synthetic-role-probe") AjaxResult probe(@RequestBody Map<String,Long> input){
            access.require(getAppUserId(),input.get("clubId"),null,ClubPermission.CONTENT_MANAGE);return success();
        }
    }
}

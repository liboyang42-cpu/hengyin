package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.web.controller.api.ApiRendezvousConsentController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;

/** Actual MVC + published service + MyBatis store + existing IM mapper, isolated H2 only.
 * SecurityContext uses the existing LoginAppUser model. No JWT issuer/filter is replaced
 * in production and these tests do not claim real token/session-lifecycle acceptance.
 */
class RendezvousConsentMvcTest {
    private JdbcTemplate jdbc;
    private RendezvousConsentService service;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    private final TestClock clock = new TestClock();
    private final Policy policy = new Policy();
    @BeforeEach void setup() throws Exception {
        SecurityContextHolder.clearContext();
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:w16_mvc_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        String ddl = new String(Files.readAllBytes(root().resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql")), StandardCharsets.UTF_8)
                .replace(" COLLATE utf8mb4_bin", "").replace(" STORED", "").replace(" GENERATED ALWAYS AS", " AS")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "").replace(" ENGINE=InnoDB", "");
        try (Connection connection = source.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)));
        }
        jdbc.execute("CREATE TABLE im_block(id BIGINT AUTO_INCREMENT PRIMARY KEY,member_id BIGINT NOT NULL,blocked_member_id BIGINT NOT NULL,create_time TIMESTAMP,UNIQUE(member_id,blocked_member_id))");
        Configuration config = new Configuration(new Environment("w16-mvc-isolated", new SpringManagedTransactionFactory(), source));
        config.getTypeAliasRegistry().registerAlias("ImBlock", ImBlock.class);
        for (String name : Arrays.asList("RendezvousConsentMapper.xml", "ImBlockMapper.xml")) {
            String path = "mapper/business/" + name;
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(input); new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        MyBatisRendezvousConsentStore store = new MyBatisRendezvousConsentStore(
                MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION, session, new DataSourceTransactionManager(source));
        service = new RendezvousConsentService(store, new RendezvousSafetyAdapter(session.getMapper(ImBlockMapper.class), policy), clock);
        mvc = MockMvcBuilders.standaloneSetup(new ApiRendezvousConsentController(service)).build();
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); jdbc.execute("SHUTDOWN"); }
    private View[] pair() {
        View a=service.join(1,"synthetic-pool","synthetic-policy",1000), b=service.join(2,"synthetic-pool","synthetic-policy",1000);
        service.propose(a.ticketId,b.ticketId,"synthetic-public-poi");return new View[]{a,b};
    }
    private static LoginAppUser login(long actor) {
        AppUser app=new AppUser();app.setUserId(actor);app.setUserName("synthetic-member");
        return new LoginAppUser(999L,app); // The nested App actor, not this unrelated outer ID, is authoritative.
    }
    private static void authenticate(long actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login(actor),null,Collections.emptyList()));
    }
    private JsonNode response(MockHttpServletRequestBuilder request,int status) throws Exception {
        MvcResult result=mvc.perform(request).andReturn();assertEquals(status,result.getResponse().getStatus(),result.getResponse().getContentAsString());
        assertEquals("no-store",result.getResponse().getHeader("Cache-Control"));
        JsonNode body=json.readTree(result.getResponse().getContentAsString());assertEquals(status,body.get("code").asInt());return body;
    }
    private String path(String ticket) {return "/api/rendezvous/tickets/"+ticket;}
    private MockHttpServletRequestBuilder accept(String ticket,String body) {return post(path(ticket)+"/accept").contentType(MediaType.APPLICATION_JSON).content(body);}
    @Test void registrationRequiresBothExplicitContractProfileAndServiceBean() {
        for(boolean profile:new boolean[]{false,true})for(boolean bean:new boolean[]{false,true}) {
            try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
                if(profile)context.getEnvironment().setActiveProfiles("w16-consent-contract");
                if(bean)context.registerBean(RendezvousConsentService.class,()->service);
                context.register(ApiRendezvousConsentController.class);context.refresh();
                assertEquals(profile&&bean,!context.getBeansOfType(ApiRendezvousConsentController.class).isEmpty());
            }
        }
    }
    @Test void authenticatedReadUsesExistingNestedAppActorAndMinimalProjection() throws Exception {
        View[] p=pair();authenticate(1);JsonNode data=response(get(path(p[0].ticketId)),200).get("data");
        Set<String> fields=new HashSet<>();data.fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<>(Arrays.asList("ticketId","matchId","publicPlace","ticketState","matchState","acceptedByMe","acceptedByOther","expiresAt","version")),fields);
        assertEquals(p[0].ticketId,data.get("ticketId").asText());assertFalse(data.get("acceptedByMe").asBoolean());
    }
    @Test void anonymousWrongPrincipalAndUnauthenticatedAppAreRejectedWithoutReads() throws Exception {
        View[] p=pair();
        assertEquals("AUTH_REQUIRED",response(get(path(p[0].ticketId)),401).get("errorCode").asText());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin",null,Collections.emptyList()));
        response(accept(p[0].ticketId,"{\"expectedVersion\":1}"),401);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login(1),null));
        response(post(path(p[0].ticketId)+"/cancel"),401);
        authenticate(0);response(get(path(p[0].ticketId)),401);
        assertEquals(TicketState.RESERVED,service.read(1,p[0].ticketId).ticketState);
    }
    @Test void strangerReadAcceptCancelMatchUnknownTicketErrorWithoutLeakingData() throws Exception {
        View[] p=pair();authenticate(3);
        JsonNode denied=response(get(path(p[0].ticketId)),404), missing=response(get(path("absent")),404);
        assertEquals(denied,missing);assertFalse(denied.has("data"));
        response(accept(p[0].ticketId,"{\"expectedVersion\":1}"),404);response(post(path(p[0].ticketId)+"/cancel"),404);
        assertEquals(TicketState.RESERVED,service.read(1,p[0].ticketId).ticketState);
    }
    @Test void twoPeopleAcceptIndependentlyAndStaleSecondReviewCannotGrantConsent() throws Exception {
        View[] p=pair();authenticate(1);JsonNode first=response(accept(p[0].ticketId,"{\"expectedVersion\":1}"),200).get("data");
        assertEquals("PROPOSED",first.get("matchState").asText());assertFalse(first.get("acceptedByOther").asBoolean());
        authenticate(2);assertEquals("STALE_PROPOSAL",response(accept(p[1].ticketId,"{\"expectedVersion\":1}"),409).get("errorCode").asText());
        JsonNode read=response(get(path(p[1].ticketId)),200).get("data");assertFalse(read.get("acceptedByMe").asBoolean());assertEquals(2,read.get("version").asInt());
        assertEquals("MUTUAL_ACCEPTED",response(accept(p[1].ticketId,"{\"expectedVersion\":2}"),200).get("data").get("matchState").asText());
    }
    @Test void interruptedClientCanReadBackAndRepeatWithoutNewReceipt() throws Exception {
        View[] p=pair();authenticate(1);JsonNode first=response(accept(p[0].ticketId,"{\"expectedVersion\":1}"),200).get("data");
        mvc=MockMvcBuilders.standaloneSetup(new ApiRendezvousConsentController(service)).build();
        assertEquals(first,response(get(path(p[0].ticketId)),200).get("data"));
        clock.now+=10;assertEquals(first,response(accept(p[0].ticketId,"{\"expectedVersion\":1}"),200).get("data"));
    }
    @Test void cancellationAfterMutualConsentIsIdempotentAndReleasesBothActors() throws Exception {
        View[] p=pair();authenticate(1);response(accept(p[0].ticketId,"{\"expectedVersion\":1}"),200);
        authenticate(2);response(accept(p[1].ticketId,"{\"expectedVersion\":2}"),200);
        JsonNode cancelled=response(post(path(p[1].ticketId)+"/cancel"),200).get("data");
        assertEquals("CANCELLED",cancelled.get("matchState").asText());assertTrue(cancelled.get("publicPlace").isNull());
        assertEquals(cancelled,response(post(path(p[1].ticketId)+"/cancel").contentType(MediaType.APPLICATION_JSON).content("{}"),200).get("data"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM w16_match_reservation",Integer.class));
    }
    @Test void expiredAcceptReturnsTerminalStateAndCannotGrantConsent() throws Exception {
        View[] p=pair();clock.now+=1000;authenticate(1);
        JsonNode data=response(accept(p[0].ticketId,"{\"expectedVersion\":1}"),200).get("data");
        assertEquals("EXPIRED",data.get("matchState").asText());assertFalse(data.get("acceptedByMe").asBoolean());assertTrue(data.get("publicPlace").isNull());
    }
    @Test void existingBidirectionalImBlockAuthorityStopsContactOnReadOrAccept() throws Exception {
        View[] p=pair();jdbc.update("INSERT INTO im_block(member_id,blocked_member_id) VALUES(2,1)");authenticate(1);
        JsonNode data=response(accept(p[0].ticketId,"{\"expectedVersion\":1}"),200).get("data");
        assertEquals("SAFETY_STOPPED",data.get("matchState").asText());assertFalse(data.get("acceptedByMe").asBoolean());assertTrue(data.get("publicPlace").isNull());
        jdbc.update("DELETE FROM im_block");p=pair();jdbc.update("INSERT INTO im_block(member_id,blocked_member_id) VALUES(1,2)");
        assertEquals("SAFETY_STOPPED",response(get(path(p[0].ticketId)),200).get("data").get("matchState").asText());
    }
    @Test void revokedEligibilityAndClosedPublicPlaceCannotBeOverriddenByHttpConsent() throws Exception {
        View[] p=pair();policy.eligible=false;authenticate(1);
        assertEquals("SAFETY_STOPPED",response(accept(p[0].ticketId,"{\"expectedVersion\":1}"),200).get("data").get("matchState").asText());
        policy.eligible=true;p=pair();policy.placeAllowed=false;
        assertEquals("SAFETY_STOPPED",response(get(path(p[0].ticketId)),200).get("data").get("matchState").asText());
    }
    @Test void actorSpoofingAndExtraFieldsCannotChangeOwnershipOrState() throws Exception {
        View[] p=pair();authenticate(1);
        response(get(path(p[0].ticketId)).param("actorId","2"),400);
        response(accept(p[0].ticketId,"{\"expectedVersion\":1,\"actorId\":2}"),400);
        response(accept(p[0].ticketId,"{\"expectedVersion\":1}").param("memberId","2"),400);
        response(post(path(p[0].ticketId)+"/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"actorId\":2}"),400);
        assertEquals(TicketState.RESERVED,service.read(1,p[0].ticketId).ticketState);
    }
    @Test void malformedOrCoercedVersionsAreRejectedWithoutMutation() throws Exception {
        View[] p=pair();authenticate(1);
        for(String body:Arrays.asList("{}","{\"expectedVersion\":null}","{\"expectedVersion\":true}","{\"expectedVersion\":\"1\"}","{\"expectedVersion\":1.5}","{\"expectedVersion\":0}","{\"expectedVersion\":-1}","{\"expectedVersion\":9223372036854775808}","{bad-json"))
            assertEquals("INVALID_REQUEST",response(accept(p[0].ticketId,body),400).get("errorCode").asText());
        assertEquals(TicketState.RESERVED,service.read(1,p[0].ticketId).ticketState);
    }
    @Test void databaseFailureIsUnavailableWithoutInternalDetailsOrFakeSuccess() throws Exception {
        View[] p=pair();authenticate(1);jdbc.update("DELETE FROM w16_consent_guard");
        JsonNode body=response(get(path(p[0].ticketId)),503);assertEquals("W16_UNAVAILABLE",body.get("errorCode").asText());
        assertFalse(body.has("data"));assertFalse(body.toString().contains("GUARD"));assertFalse(body.toString().contains(p[0].ticketId));
    }
    @Test void unapprovedPoolMeetingTeamAndSafetyAdministrativeRoutesDoNotExist() throws Exception {
        authenticate(1);
        for(String route:Arrays.asList("/api/rendezvous/join","/api/rendezvous/propose","/api/rendezvous/verify","/api/rendezvous/create-team","/api/rendezvous/stop-for-safety"))
            assertEquals(404,mvc.perform(post(route)).andReturn().getResponse().getStatus());
    }
    private static Path root() {
        for(Path p=Paths.get("").toAbsolutePath();p!=null;p=p.getParent())if(Files.isRegularFile(p.resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql")))return p;
        throw new IllegalStateException("W16_TEST_SOURCE_ROOT_MISSING");
    }
    private static final class TestClock extends Clock {
        long now=10000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    private static final class Policy implements RendezvousSafetyAdapter.ReviewedPolicy {
        boolean eligible=true,placeAllowed=true;
        public boolean eligible(long actor,String pool,String version,long now){return eligible;}
        public boolean compatible(long a,long b,String pool,long now){return true;}
        public boolean publicMeetingAllowed(String pool,String place,long now){return placeAllowed&&"synthetic-public-poi".equals(place);}
    }
}

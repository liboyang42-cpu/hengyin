package com.chengyinhub.business.reward;

import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.web.controller.api.ApiNonCashRewardReadController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static com.chengyinhub.business.reward.NonCashRewardContracts.*;

/** Actual Spring MVC -> owner-predicated MyBatis -> accepted ledger. Isolated H2, not JWT/production acceptance. */
class NonCashRewardReadMvcTest {
    JdbcDataSource ds;JdbcTemplate jdbc;DataSourceTransactionManager manager;NonCashRewardService writer;
    NonCashRewardReadService reader;MockMvc mvc;Time clock;Facts facts;ObjectMapper json=new ObjectMapper();
    static final String URL="/api/rewards/noncash";
    @BeforeEach void setup() throws Exception {
        SecurityContextHolder.clearContext();ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:reward_read_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(Connection c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("reward/noncash-fixture.sql"));}
        jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        jdbc.execute("CREATE TABLE mms_merchant(id BIGINT PRIMARY KEY,status BIGINT,account_status BIGINT,del_flag INT,business_status INT,name VARCHAR(100),phone VARCHAR(100))");
        jdbc.update("INSERT INTO mms_merchant VALUES(7,1,1,0,1,'private current name','private phone')");
        jdbc.update("INSERT INTO pms_product VALUES(11,7,1,0)");jdbc.update("INSERT INTO pms_sku VALUES(12,11,20)");
        clock=new Time();facts=new Facts();SqlSessionTemplate session=session();
        writer=new NonCashRewardService(NonCashRewardService.Mode.ISOLATED_PERSISTENCE_EVALUATION,session.getMapper(NonCashRewardMapper.class),facts,manager,clock);
        reader=new NonCashRewardReadService(NonCashRewardReadService.Mode.ISOLATED_READ_EVALUATION,session.getMapper(NonCashRewardReadMapper.class),manager,clock);
        mvc=MockMvcBuilders.standaloneSetup(new ApiNonCashRewardReadController(reader)).build();
        writer.importApprovedPledge("pledge");
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();jdbc.execute("SHUTDOWN");}
    SqlSessionTemplate session() throws Exception {
        Configuration c=new Configuration(new Environment("w17-read-isolated",new SpringManagedTransactionFactory(),ds));
        for(String file:Arrays.asList("NonCashRewardMapper.xml","NonCashRewardReadMapper.xml")){
            String path="mapper/business/"+file;try(InputStream in=getClass().getClassLoader().getResourceAsStream(path)){assertNotNull(in);new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}
        }
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c));
    }
    static Scope scope(){return new Scope(ScopeType.MAP,"board","release","season");}
    Pledge pledge(){return new Pledge("pledge","version","approval",scope(),"rules","coffee",7,8,11,12,10,100,1000,Allocation.FINITE,RewardKind.PHYSICAL,"Promised coffee","Original store 8 conditions");}
    Award grant(long member,String event){facts.events.put(event,new Qualification(scope(),member,event,"entitlement-"+event,"rules","coffee",200));return writer.award("pledge",member,event);}
    static LoginAppUser login(long actor){AppUser app=new AppUser();app.setUserId(actor);return new LoginAppUser(999L,app);}
    static void authenticate(long actor){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login(actor),null,Collections.emptyList()));}
    JsonNode response(MockHttpServletRequestBuilder request,int status) throws Exception {
        MvcResult result=mvc.perform(request).andReturn();assertEquals(status,result.getResponse().getStatus(),result.getResponse().getContentAsString());
        assertEquals("no-store",result.getResponse().getHeader("Cache-Control"));JsonNode body=json.readTree(result.getResponse().getContentAsByteArray());assertEquals(status,body.get("code").asInt());return body;
    }
    MockHttpServletRequestBuilder detail(String award){return get(URL+"/"+award).param("contextType","MAP").param("contextId","board").param("releaseId","release").param("instanceId","season");}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    @Test void routeRequiresExplicitProfileAndReadBean(){
        for(boolean profile:new boolean[]{false,true})for(boolean bean:new boolean[]{false,true})try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()){
            if(profile)context.getEnvironment().setActiveProfiles("w17-reward-read-contract");
            if(bean)context.registerBean(NonCashRewardReadService.class,()->reader);
            context.register(ApiNonCashRewardReadController.class);context.refresh();assertEquals(profile&&bean,!context.getBeansOfType(ApiNonCashRewardReadController.class).isEmpty());
        }
    }
    @Test void ownerListUsesNestedAppActorAndActualLedgerOnly() throws Exception {
        Award a=grant(1,"one"),b=grant(2,"other");authenticate(1);
        JsonNode page=response(get(URL).param("limit","10"),200).get("data");assertEquals(1,page.get("items").size());assertEquals(a.awardId,page.get("items").get(0).get("awardId").asText());assertTrue(page.get("nextCursor").isNull());assertEquals(300,page.get("asOf").asLong());
        assertFalse(page.toString().contains(b.awardId));
    }
    @Test void explicitPageBoundAndStableCursorDoNotDuplicateOwnerRows() throws Exception {
        List<String> ids=new ArrayList<>();for(int i=0;i<3;i++)ids.add(grant(1,"event-"+i).awardId);grant(2,"foreign");authenticate(1);
        Collections.sort(ids,Collections.reverseOrder());List<String> found=new ArrayList<>();String cursor=null;
        for(int i=0;i<3;i++){
            MockHttpServletRequestBuilder req=get(URL).param("limit","1");if(cursor!=null)req.param("cursor",cursor);
            JsonNode page=response(req,200).get("data");assertEquals(1,page.get("items").size());found.add(page.get("items").get(0).get("awardId").asText());
            cursor=page.get("nextCursor").isNull()?null:page.get("nextCursor").asText();if(i<2)assertEquals(found.get(i),cursor);else assertNull(cursor);
        }
        assertEquals(ids,found);
    }
    @Test void cursorOtherOwnerAndMissingHaveIdenticalError() throws Exception {
        Award foreign=grant(2,"foreign");authenticate(1);
        assertEquals(response(get(URL).param("limit","2").param("cursor",foreign.awardId),400),response(get(URL).param("limit","2").param("cursor",hash("missing")),400));
        assertEquals(0,response(get(URL).param("limit","2"),200).get("data").get("items").size());
    }
    @Test void detailForeignMissingAndWrongExactScopeAreIndistinguishable() throws Exception {
        Award a=grant(1,"one"),foreign=grant(2,"foreign");authenticate(1);JsonNode missing=response(detail(hash("missing")),404);
        assertEquals(missing,response(detail(foreign.awardId),404));
        String[] fields={"contextType","contextId","releaseId","instanceId"};String[] wrong={"THEME","different-board","different-release","different-season"};
        for(int i=0;i<fields.length;i++){
            MockHttpServletRequestBuilder q=get(URL+"/"+a.awardId);
            for(int j=0;j<fields.length;j++)q.param(fields[j],i==j?wrong[j]:new String[]{"MAP","board","release","season"}[j]);
            assertEquals(missing,response(q,404));
        }
        assertEquals(a.awardId,response(detail(a.awardId),200).get("data").get("awardId").asText());
    }
    @Test void exactPlayerProjectionContainsNoSourceProofOrCredentials() throws Exception {
        Award a=grant(1,"private-event");authenticate(1);JsonNode v=response(detail(a.awardId),200).get("data");
        Set<String> fields=new HashSet<>();v.fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<>(Arrays.asList("awardId","contextType","contextId","releaseId","instanceId","rulesVersion","merchantId","storeId","rewardKind","rewardTitle","quantity","validFrom","validUntil","redemptionConditions","state","awardedAt","asOf","validityStatus","fulfillmentStatus")),fields);
        assertEquals("7",v.get("merchantId").asText());assertTrue(v.get("merchantId").isTextual());assertTrue(v.get("validUntil").isIntegralNumber());
        assertEquals("UNVERIFIED",v.get("fulfillmentStatus").asText());assertEquals("IN_WINDOW",v.get("validityStatus").asText());
        for(String hidden:Arrays.asList("private-event","approval","fingerprint","private phone","payload","pledgeId","memberId","reservationId","staffActorId"))assertFalse(v.toString().contains(hidden));
    }
    @Test void expiredDisplayDoesNotMutatePersistedStateOrAudit() throws Exception {
        Award a=grant(1,"one");authenticate(1);long audit=count("w17_noncash_audit");clock.now=1000;
        JsonNode v=response(detail(a.awardId),200).get("data");assertEquals("AWARDED",v.get("state").asText());assertEquals("ELAPSED",v.get("validityStatus").asText());
        assertEquals("AWARDED",jdbc.queryForObject("SELECT state FROM w17_noncash_award WHERE award_id=?",String.class,a.awardId));assertEquals(audit,count("w17_noncash_audit"));
        writer.expire("pledge",a.awardId);assertEquals("EXPIRED",response(detail(a.awardId),200).get("data").get("state").asText());
    }
    @Test void terminalConsumedAndReversedStatesNeverBecomeAvailableOnRead() throws Exception {
        Award consumed=grant(1,"consumed"),reversed=grant(1,"reversed");writer.recordConsumption("pledge",consumed.awardId,9,"source-consumption");writer.reverse("pledge",reversed.awardId,"case");
        authenticate(1);clock.now=1000;assertEquals("REDEEMED",response(detail(consumed.awardId),200).get("data").get("state").asText());assertEquals("REVERSED",response(detail(reversed.awardId),200).get("data").get("state").asText());
        assertEquals(1,jdbc.queryForObject("SELECT consumed FROM w17_noncash_pledge",Long.class));
    }
    @Test void disabledDeletedMissingMerchantDoNotEraseOriginalTerms() throws Exception {
        Award a=grant(1,"one");authenticate(1);facts.open=false;
        jdbc.update("UPDATE mms_merchant SET account_status=2,name='Changed' WHERE id=7");JsonNode v=response(detail(a.awardId),200).get("data");
        assertEquals("MERCHANT_DISABLED",v.get("fulfillmentStatus").asText());assertEquals("Original store 8 conditions",v.get("redemptionConditions").asText());assertEquals("AWARDED",v.get("state").asText());
        jdbc.update("UPDATE mms_merchant SET del_flag=2 WHERE id=7");assertEquals("MERCHANT_UNAVAILABLE",response(detail(a.awardId),200).get("data").get("fulfillmentStatus").asText());
        jdbc.update("DELETE FROM mms_merchant WHERE id=7");assertEquals("MERCHANT_UNAVAILABLE",response(detail(a.awardId),200).get("data").get("fulfillmentStatus").asText());
    }
    @Test void businessDisplayAndUnknownFreezeNeverAuthorizeRedemption() throws Exception {
        Award a=grant(1,"one");authenticate(1);jdbc.update("UPDATE mms_merchant SET business_status=0 WHERE id=7");
        assertEquals("UNVERIFIED",response(detail(a.awardId),200).get("data").get("fulfillmentStatus").asText());
        facts.open=false;assertEquals("UNVERIFIED",response(detail(a.awardId),200).get("data").get("fulfillmentStatus").asText());
        assertThrows(IllegalStateException.class,()->writer.recordConsumption("pledge",a.awardId,9,"source-consumption"));
    }
    @Test void queryBoundsDuplicatesAndOwnerInjectionAreRejected() throws Exception {
        authenticate(1);response(get(URL),400);
        for(String n:Arrays.asList("0","51","-1","1.0","true","01","999999999999",""))response(get(URL).param("limit",n),400);
        response(get(URL).param("limit","1","2"),400);response(get(URL).param("limit","1").param("memberId","2"),400);
        response(get(URL).param("limit","1").param("cursor",""),400);response(get(URL).param("limit","1").param("cursor",hash("x").toUpperCase(Locale.ROOT)),400);
        Award a=grant(1,"one");response(detail(a.awardId).param("instanceId","other"),400);response(get(URL+"/"+a.awardId),400);
        response(detail(a.awardId).param("actorId","2"),400);
    }
    @Test void anonymousWrongPrincipalAndUnauthenticatedSessionNeverReadLedger() throws Exception {
        jdbc.execute("DROP TABLE w17_noncash_award"); // Authorized query would now fail; auth failures must still be401.
        response(get(URL).param("limit","1"),401);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin",null,Collections.emptyList()));response(get(URL).param("limit","1"),401);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login(1),null));response(get(URL).param("limit","1"),401);
        authenticate(0);response(get(URL).param("limit","1"),401);
    }
    @Test void malformedOrChangedSnapshotAndUnknownStateFailClosedWithoutLeak() throws Exception {
        Award a=grant(1,"one");authenticate(1);String snapshot=jdbc.queryForObject("SELECT payload FROM w17_noncash_pledge",String.class);
        jdbc.update("UPDATE w17_noncash_pledge SET payload='not-json-secret'");JsonNode bad=response(detail(a.awardId),503);assertEquals("REWARDS_UNAVAILABLE",bad.get("errorCode").asText());assertFalse(bad.toString().contains("not-json-secret"));
        jdbc.update("UPDATE w17_noncash_pledge SET payload=?",snapshot.replace("Promised coffee","Changed coffee"));assertEquals(bad,response(get(URL).param("limit","10"),503));
        jdbc.update("UPDATE w17_noncash_pledge SET payload=?",snapshot);jdbc.update("UPDATE w17_noncash_award SET state='SECRET_INTERNAL_STATE'");assertEquals(bad,response(detail(a.awardId),503));
    }
    @Test void defaultDisabledServiceAndDatabaseFailureAreSanitized() throws Exception {
        authenticate(1);mvc=MockMvcBuilders.standaloneSetup(new ApiNonCashRewardReadController(new NonCashRewardReadService())).build();JsonNode off=response(get(URL).param("limit","1"),503);
        mvc=MockMvcBuilders.standaloneSetup(new ApiNonCashRewardReadController(reader)).build();jdbc.execute("DROP TABLE w17_noncash_award");assertEquals(off,response(get(URL).param("limit","1"),503));assertFalse(off.has("data"));
    }
    @Test void noAwardOrConsumptionOrMutationHttpRouteExists() throws Exception {
        Award a=grant(1,"one");authenticate(1);long before=count("w17_noncash_audit");
        assertEquals(405,mvc.perform(post(URL)).andReturn().getResponse().getStatus());assertEquals(404,mvc.perform(post(URL+"/"+a.awardId+"/consume")).andReturn().getResponse().getStatus());
        assertEquals(405,mvc.perform(delete(URL+"/"+a.awardId)).andReturn().getResponse().getStatus());assertEquals(before,count("w17_noncash_audit"));
    }
    @Test void independentReaderRestartsWithSameOwnerAndImmutableTerms() throws Exception {
        Award a=grant(1,"one");NonCashRewardReadService restarted=new NonCashRewardReadService(NonCashRewardReadService.Mode.ISOLATED_READ_EVALUATION,session().getMapper(NonCashRewardReadMapper.class),manager,clock);
        assertEquals(a.redemptionConditions,restarted.detail(1,a.awardId,scope()).redemptionConditions);assertThrows(NonCashRewardReadService.Rejection.class,()->restarted.detail(2,a.awardId,scope()));
    }
    @Test void themeAndUnicodeExactContextRoundTripWithoutMapFallback() throws Exception {
        Scope theme=new Scope(ScopeType.THEME,"主题 雨\uD83D\uDE00","release:主题","run/二");
        facts.alternate=new Pledge("theme-pledge","v1","approval-theme",theme,"rules","coffee",7,8,11,12,1,100,1000,Allocation.FINITE,RewardKind.PHYSICAL,"Theme coffee","Theme terms");
        writer.importApprovedPledge("theme-pledge");facts.events.put("theme-event",new Qualification(theme,1,"theme-completion","theme-entitlement","rules","coffee",200));
        Award a=writer.award("theme-pledge",1,"theme-event");authenticate(1);
        JsonNode page=response(get(URL).param("limit","1"),200).get("data");assertEquals("THEME",page.get("items").get(0).get("contextType").asText());
        JsonNode v=response(get(URL+"/"+a.awardId).param("contextType","THEME").param("contextId",theme.contextId).param("releaseId",theme.releaseId).param("instanceId",theme.instanceId),200).get("data");
        assertEquals(theme.contextId,v.get("contextId").asText());assertEquals(theme.instanceId,v.get("instanceId").asText());response(detail(a.awardId),404);
    }
    @Test void malformedScopeBlankReferencesAndOversizeAreRejected() throws Exception {
        Award a=grant(1,"one");authenticate(1);
        for(String bad:Arrays.asList("", " ", String.join("",Collections.nCopies(513,"a")), "bad\uD800")){
            response(get(URL+"/"+a.awardId).param("contextType","MAP").param("contextId",bad).param("releaseId","release").param("instanceId","season"),400);
        }
        response(get(URL+"/"+a.awardId).param("contextType","CITY").param("contextId","board").param("releaseId","release").param("instanceId","season"),400);
    }
    @Test void eachPageUsesOneObservationTimeAndDetailCanAdvance() throws Exception {
        Award a=grant(1,"one");grant(1,"two");authenticate(1);
        JsonNode page=response(get(URL).param("limit","2"),200).get("data");for(JsonNode v:page.get("items"))assertEquals(page.get("asOf"),v.get("asOf"));
        clock.now=400;JsonNode v=response(detail(a.awardId),200).get("data");assertEquals(400,v.get("asOf").asLong());assertEquals(300,v.get("awardedAt").asLong());
    }
    @Test void emitsConcreteListDetailAndErrorContractFromActualMvc() throws Exception {
        Award a=grant(1,"contract-event");authenticate(1);
        System.out.println("W17_READ_LIST_JSON="+response(get(URL).param("limit","1"),200));
        System.out.println("W17_READ_DETAIL_JSON="+response(detail(a.awardId),200));
        System.out.println("W17_READ_NOT_FOUND_JSON="+response(detail(hash("missing")),404));
    }
    static final class Time extends Clock{long now=300;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}}
    final class Facts implements Authority{
        boolean open=true;Pledge alternate;Map<String,Qualification> events=new HashMap<>();
        public Pledge approvedPledge(String pledgeId){return alternate!=null&&alternate.pledgeId.equals(pledgeId)?alternate:pledge();}
        public void requireCurrentFulfillment(Pledge p,long now){require(open,"FROZEN_SOURCE");}
        public Qualification guaranteeEligibility(long memberId,String id){return events.get(id);}
        public Qualification qualifyingEvent(long memberId,String id){return events.get(id);}
        public void requireConsumption(long actor,String receipt,Award award,long now){require(actor==9,"DENIED");}
        public void requireReversal(String id,Award award,long now){require("case".equals(id),"DENIED");}
    }
}

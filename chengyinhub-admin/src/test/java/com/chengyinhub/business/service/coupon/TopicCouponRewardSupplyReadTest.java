package com.chengyinhub.business.service.coupon;
import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.MmsMerchantServiceImpl;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.web.config.TopicCouponRewardSupplyReadConfiguration;
import com.chengyinhub.web.controller.api.ApiTopicCouponRewardSupplyController;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real canonical MerchantAccessServiceImpl and full MmsMerchantServiceImpl + original mappers. */
public class TopicCouponRewardSupplyReadTest {
    JdbcDataSource db;JdbcTemplate jdbc;SqlSessionTemplate sql;MerchantAccessServiceImpl authority;
    TopicCouponRewardSupplyMapper mapper;TopicCouponRewardSupplyService service;MutableClock clock;
    MockMvc mvc;ReadOnlyWitness witness;final ObjectMapper json=new ObjectMapper();
    static class MutableClock extends Clock {
        long now=1000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    @Intercepts(@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}))
    static class ReadOnlyWitness implements Interceptor {
        int writes;public Object intercept(Invocation invocation)throws Throwable{writes++;throw new AssertionError("Supply read invoked a project write");}
        public Object plugin(Object target){return Plugin.wrap(target,this);}public void setProperties(Properties p){}
    }
    @BeforeEach void setup()throws Exception {
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:topic_supply_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(db);clock=new MutableClock();
        mappedTable("sms_coupon","SmsCouponMapper","SmsCouponResult",SmsCoupon.class);
        mappedTable("mms_merchant","MmsMerchantMapper","MmsMerchantResult",MmsMerchant.class);
        mappedTable("ums_member","UmsMemberMapper","UmsMemberResult",UmsMember.class);
        mappedTable("merchant_operator","MerchantOperatorMapper","MerchantOperatorResult",MerchantOperator.class);
        jdbc.update("INSERT INTO ums_member(id,status,del_flag,role,phone,password) VALUES(7,1,0,'creator','PRIVATE-PHONE','PRIVATE-PASSWORD'),(9,1,0,'merchant','OTHER-PHONE','OTHER-PASSWORD'),(17,1,0,'member',NULL,NULL)");
        jdbc.update("INSERT INTO mms_merchant(id,member_id,status,account_status,del_flag,name) VALUES(19,7,1,1,0,'Owned merchant'),(29,9,1,1,0,'Other merchant')");
        coupon(8,7,19,"Own terms");coupon(18,9,29,"Foreign terms");
        org.apache.ibatis.session.Configuration c=new org.apache.ibatis.session.Configuration(new Environment("isolated",new SpringManagedTransactionFactory(),db));
        c.getTypeAliasRegistry().registerAlias("SmsCoupon",SmsCoupon.class);c.getTypeAliasRegistry().registerAlias("MmsMerchant",MmsMerchant.class);c.getTypeAliasRegistry().registerAlias("UmsMember",UmsMember.class);
        witness=new ReadOnlyWitness();c.addInterceptor(witness);
        for(String name:new String[]{"SmsCouponMapper","MmsMerchantMapper","UmsMemberMapper","MerchantOperatorMapper","TopicCouponRewardSupplyMapper"}) {
            String resource="/mapper/business/"+name+".xml";
            try(InputStream input=getClass().getResourceAsStream(resource)){assertNotNull(input);new XMLMapperBuilder(input,c,resource,c.getSqlFragments()).parse();}
        }
        sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c));mapper=sql.getMapper(TopicCouponRewardSupplyMapper.class);
        MmsMerchantServiceImpl merchants=new MmsMerchantServiceImpl();ReflectionTestUtils.setField(merchants,"mmsMerchantMapper",sql.getMapper(MmsMerchantMapper.class));
        authority=new MerchantAccessServiceImpl();ReflectionTestUtils.setField(authority,"merchantService",merchants);
        ReflectionTestUtils.setField(authority,"umsMemberMapper",sql.getMapper(UmsMemberMapper.class));ReflectionTestUtils.setField(authority,"merchantOperatorMapper",sql.getMapper(MerchantOperatorMapper.class));
        service=new TopicCouponRewardSupplyService(authority,mapper,clock);mvc=MockMvcBuilders.standaloneSetup(new ApiTopicCouponRewardSupplyController(service)).build();
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();assertEquals(0,witness==null?0:witness.writes);jdbc.execute("SHUTDOWN");}
    void mappedTable(String table,String file,String resultMap,Class<?> bean)throws Exception {
        String source;try(InputStream in=getClass().getResourceAsStream("/mapper/business/"+file+".xml")){assertNotNull(in);ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)b.write(buffer,0,n);source=new String(b.toByteArray(),StandardCharsets.UTF_8);}
        Matcher root=Pattern.compile("<resultMap\\b[^>]*id=\\\""+resultMap+"\\\"[^>]*>(.*?)</resultMap>",Pattern.DOTALL).matcher(source);assertTrue(root.find());
        Matcher fields=Pattern.compile("<(?:id|result)\\s+property=\\\"([^\\\"]+)\\\"\\s+column=\\\"([^\\\"]+)\\\"").matcher(root.group(1));
        List<String> columns=new ArrayList<String>();while(fields.find()){
            String property=fields.group(1),column=fields.group(2);Class<?> t=bean.getMethod("get"+Character.toUpperCase(property.charAt(0))+property.substring(1)).getReturnType();
            String type=Number.class.isAssignableFrom(t)?(t==BigDecimal.class?"DECIMAL(30,8)":"BIGINT"):java.util.Date.class.isAssignableFrom(t)?"TIMESTAMP(3)":"VARCHAR(4096)";
            columns.add(column+" "+type+(column.equals("id")?" PRIMARY KEY":""));
        }
        assertFalse(columns.isEmpty());jdbc.execute("CREATE TABLE "+table+"("+String.join(",",columns)+")");
    }
    void coupon(long id,long owner,long merchant,String description){jdbc.update("INSERT INTO sms_coupon(id,member_id,merchant_id,scope_type,status,del_flag,name,description,coupon_type,publish_count,receive_count,start_time,end_time,code) VALUES(?,?,?,1,1,0,?,?,1,10,3,?,?,'PRIVATE-COUPON-CODE')",id,owner,merchant,"Coupon "+id,description,new java.sql.Timestamp(100),new java.sql.Timestamp(9000));}
    void login(long inner,long outer){AppUser app=new AppUser();app.setUserId(inner);LoginAppUser user=new LoginAppUser(outer,app);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"synthetic",Collections.emptyList()));}
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> page){return (List<Map<String,Object>>)page.get("items");}
    Map<String,Object> list(long actor){return service.list(actor,20,null);}
    @Test void realOwnerAuthorityAndSQLReturnOnlyOwnFixedCouponWithoutAIEntitlement(){Map<String,Object> page=list(7);assertEquals("TOPIC_COMPLETION_COUPON",page.get("purpose"));assertEquals(1,items(page).size());assertEquals("8",items(page).get(0).get("couponId"));assertEquals("19",items(page).get(0).get("merchantId"));assertEquals(7L,items(page).get(0).get("remainingCount"));assertTrue((Boolean)items(page).get(0).get("quantityLimited"));}
    @Test void otherOwnerHasOwnPageNotFirstOwnersData(){assertEquals("18",items(list(9)).get(0).get("couponId"));}
    @Test void noMerchantRelationCannotReadSupply(){assertThrows(RuntimeException.class,()->list(17));}
    @Test void inactiveMerchantRejectedByOriginalAuthority(){jdbc.update("UPDATE mms_merchant SET status=0 WHERE id=19");assertThrows(RuntimeException.class,()->list(7));}
    @Test void suspendedMerchantAccountRejectedByOriginalAuthority(){jdbc.update("UPDATE mms_merchant SET account_status=0 WHERE id=19");assertThrows(RuntimeException.class,()->list(7));}
    @Test void inactiveMemberRejectedByOriginalAuthority(){jdbc.update("UPDATE ums_member SET status=0 WHERE id=7");assertThrows(RuntimeException.class,()->list(7));}
    @Test void multipleActiveOwnerMerchantsFailClosed(){jdbc.update("INSERT INTO mms_merchant(id,member_id,status,account_status,del_flag) VALUES(39,7,1,1,0)");assertThrows(RuntimeException.class,()->list(7));}
    @Test void managerCouponPermissionDoesNotInventThemeIssuerOwnership(){jdbc.update("INSERT INTO merchant_operator(id,merchant_id,operator_member_id,active_operator_member_id,role_code,status,version) VALUES(1,19,17,17,'MERCHANT_MANAGER','ACTIVE',1)");assertTrue(authority.require(17L,MerchantPermission.COUPON_MANAGE).hasPermission(MerchantPermission.COUPON_MANAGE));assertThrows(IllegalArgumentException.class,()->list(17));}
    @Test void verificationStaffCannotUseCouponManagementPermission(){jdbc.update("INSERT INTO merchant_operator(id,merchant_id,operator_member_id,active_operator_member_id,role_code,status,version) VALUES(1,19,17,17,'MERCHANT_CHECKIN','ACTIVE',1)");assertThrows(RuntimeException.class,()->list(17));}
    @Test void revokedEmployeeCannotBorrowOwnerContext(){jdbc.update("INSERT INTO merchant_operator(id,merchant_id,operator_member_id,active_operator_member_id,role_code,status,version) VALUES(1,19,17,17,'MERCHANT_MANAGER','REVOKED',1)");assertThrows(RuntimeException.class,()->list(17));}
    @Test void stoppedInvalidExpiredDeletedAndExhaustedRowsAreNotOptions(){for(String set:new String[]{"status=4","status=3","status=2","del_flag=2","receive_count=10"}){jdbc.update("UPDATE sms_coupon SET "+set+" WHERE id=8");assertTrue(items(list(7)).isEmpty());jdbc.update("UPDATE sms_coupon SET status=1,del_flag=0,receive_count=3,end_time=? WHERE id=8",new java.sql.Timestamp(9000));}}
    @Test void missingDisclosedTermsOrValidityAreNotSelectable(){for(String set:new String[]{"description=NULL","name=''","start_time=NULL","end_time=NULL"}){jdbc.update("UPDATE sms_coupon SET "+set+" WHERE id=8");assertTrue(items(list(7)).isEmpty());jdbc.update("UPDATE sms_coupon SET name='Coupon 8',description='Own terms',start_time=?,end_time=? WHERE id=8",new java.sql.Timestamp(100),new java.sql.Timestamp(9000));}}
    @Test void unlimitedDefinitionDoesNotManufactureRemainingQuantity(){jdbc.update("UPDATE sms_coupon SET publish_count=0 WHERE id=8");Map<String,Object> row=items(list(7)).get(0);assertFalse((Boolean)row.get("quantityLimited"));assertNull(row.get("remainingCount"));}
    @Test void futureStartIsDisclosedWithoutInventingImmediateRedemption(){jdbc.update("UPDATE sms_coupon SET start_time=? WHERE id=8",new java.sql.Timestamp(2000));assertEquals(2000L,items(list(7)).get(0).get("validFrom"));}
    @Test void readDoesNotMutateExpiredStatusOrCreateHistory(){jdbc.update("UPDATE sms_coupon SET end_time=? WHERE id=8",new java.sql.Timestamp(999));assertTrue(items(list(7)).isEmpty());assertEquals(1,jdbc.queryForObject("SELECT status FROM sms_coupon WHERE id=8",Integer.class));assertEquals(3L,jdbc.queryForObject("SELECT receive_count FROM sms_coupon WHERE id=8",Long.class));assertEquals(0,witness.writes);}
    @Test void exactOpaqueDigestMatchesFrozenTermsCodec(){String digest=(String)items(list(7)).get(0).get("termsDigest");assertEquals(CouponTermsSnapshot.fromDefinition(sql.getMapper(SmsCouponMapper.class).selectSmsCouponById(8L)).getDigest(),digest);}
    @Test void pageCursorIsStableDefinitionIdWithNoCrossMerchantRows(){coupon(9,7,19,"Second");Map<String,Object>a=service.list(7,1,null);assertEquals("8",a.get("nextCursor"));Map<String,Object>b=service.list(7,1,8L);assertEquals("9",items(b).get(0).get("couponId"));assertNull(b.get("nextCursor"));}
    @Test void longIdsRemainLosslessDecimalStrings(){jdbc.update("UPDATE sms_coupon SET id=9007199254740993 WHERE id=8");assertEquals("9007199254740993",items(list(7)).get(0).get("couponId"));}
    @Test void currentRevocationDuringQueryRejectsResponse() {TopicCouponRewardSupplyMapper changing=(owner,merchant,cursor,limit,now)->{List<SmsCoupon> result=mapper.options(owner,merchant,cursor,limit,now);jdbc.update("UPDATE mms_merchant SET account_status=0 WHERE id=19");return result;};TopicCouponRewardSupplyService guarded=new TopicCouponRewardSupplyService(authority,changing,clock);assertThrows(RuntimeException.class,()->guarded.list(7,20,null));}
    @Test void clockCrossingEndDuringReadOmitsExpiredOption(){TopicCouponRewardSupplyMapper slow=(owner,merchant,cursor,limit,now)->{List<SmsCoupon> result=mapper.options(owner,merchant,cursor,limit,now);clock.now=9000;return result;};assertTrue(items(new TopicCouponRewardSupplyService(authority,slow,clock).list(7,20,null)).isEmpty());}
    @Test void controllerUsesNestedAuthenticatedActorAndNoStore()throws Exception {login(7,9);MvcResult r=mvc.perform(get("/api/rewards/topic-coupon-options").param("limit","20")).andExpect(status().isOk()).andReturn();assertEquals("no-store",r.getResponse().getHeader("Cache-Control"));JsonNode payload=json.readTree(r.getResponse().getContentAsString()).get("data");assertEquals("8",payload.get("items").get(0).get("couponId").asText());String body=r.getResponse().getContentAsString();for(String secret:new String[]{"PRIVATE-PHONE","PRIVATE-PASSWORD","PRIVATE-COUPON-CODE","Foreign terms"})assertFalse(body.contains(secret));}
    @Test void controllerAnonymousAndNonAppPrincipalsDenied()throws Exception {mvc.perform(get("/api/rewards/topic-coupon-options").param("limit","20")).andExpect(status().isUnauthorized());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin","synthetic",Collections.emptyList()));mvc.perform(get("/api/rewards/topic-coupon-options").param("limit","20")).andExpect(status().isUnauthorized());}
    @Test void controllerCannotTakeActorOrMerchantFromQuery()throws Exception {login(7,7);for(String key:new String[]{"memberId","actor","owner","merchantId","scope","couponId"})mvc.perform(get("/api/rewards/topic-coupon-options").param("limit","20").param(key,"9")).andExpect(status().isBadRequest());}
    @Test void controllerRejectsDuplicateAndMalformedPagination()throws Exception {login(7,7);mvc.perform(get("/api/rewards/topic-coupon-options").param("limit","20","21")).andExpect(status().isBadRequest());for(String value:new String[]{"0","01","51","-1","1.0"})mvc.perform(get("/api/rewards/topic-coupon-options").param("limit",value)).andExpect(status().isBadRequest());mvc.perform(get("/api/rewards/topic-coupon-options").param("limit","20").param("cursor","9223372036854775808")).andExpect(status().isBadRequest());}
    @Test void controllerHasNoWriteOrIssuanceRoute()throws Exception {login(7,7);mvc.perform(post("/api/rewards/topic-coupon-options")).andExpect(status().isMethodNotAllowed());mvc.perform(get("/api/rewards/topic-coupon-options/issue")).andExpect(status().isNotFound());assertEquals(0,witness.writes);}
    @Test void profileAbsentLeavesControllerAndFactoryUninstalled(){try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()){context.register(TopicCouponRewardSupplyReadConfiguration.class,ApiTopicCouponRewardSupplyController.class);context.refresh();assertEquals(0,context.getBeansOfType(TopicCouponRewardSupplyService.class).size());assertEquals(0,context.getBeansOfType(ApiTopicCouponRewardSupplyController.class).size());}}
    @Test void explicitProfileWiresRealExistingAuthorityAndMapper(){try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()){context.getEnvironment().setActiveProfiles("w17-topic-coupon-supply-read");context.getBeanFactory().registerSingleton("canonicalMerchantAuthority",authority);context.getBeanFactory().registerSingleton("realSupplyMapper",mapper);context.register(TopicCouponRewardSupplyReadConfiguration.class,ApiTopicCouponRewardSupplyController.class);context.refresh();assertNotNull(context.getBean(TopicCouponRewardSupplyService.class));assertNotNull(context.getBean(ApiTopicCouponRewardSupplyController.class));}}
}

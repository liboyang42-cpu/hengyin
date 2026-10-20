package com.chengyinhub.business.service.ai;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ai.*;
import com.chengyinhub.business.domain.vo.ai.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.common.exception.ServiceException;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;

/** Real authority, product/SKU, template-save implementations and original MyBatis SQL on H2. */
class MerchantAuthoringTransactionTest {
    JdbcDataSource db; JdbcTemplate jdbc; SqlSessionTemplate sql;
    MerchantAuthoringFactsService facts; MerchantAuthoringRecordService records; MerchantBoundTemplateDraftService bound;
    MerchantAccessServiceImpl authority; IAiCreatorService creator; int aiCalls, capabilityCalls; boolean rejectCapability;
    Runnable providerEffect = () -> {};  AiTemplateFillReq received;
    @BeforeEach void setup() throws Exception {
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:authoring_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(db);
        mappedTable("mms_merchant","MmsMerchantMapper","MmsMerchantResult",MmsMerchant.class,false);
        mappedTable("ums_member","UmsMemberMapper","UmsMemberResult",UmsMember.class,false);
        mappedTable("merchant_operator","MerchantOperatorMapper","MerchantOperatorResult",MerchantOperator.class,false);
        mappedTable("pms_product","PmsProductMapper","PmsProductResult",PmsProduct.class,false);
        mappedTable("pms_sku","PmsSkuMapper","PmsSkuResult",PmsSku.class,false);
        mappedTable("cms_member_template","CmsMemberTemplateMapper","CmsMemberTemplateResult",CmsMemberTemplate.class,true);
        String migration=new String(Files.readAllBytes(migrationPath()),StandardCharsets.UTF_8);
        migration=migration.replaceAll("(?m)^--.*$", "").replace(" CHARACTER SET ascii", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        for(String statement:migration.split(";"))if(!statement.trim().isEmpty())jdbc.execute(statement);
        jdbc.update("INSERT INTO ums_member(id,status,del_flag,role) VALUES(7,1,0,'merchant'),(9,1,0,'merchant'),(17,1,0,'merchant')");
        jdbc.update("INSERT INTO mms_merchant(id,member_id,status,account_status,del_flag,name) VALUES(19,7,1,1,0,'Owned shop'),(29,9,1,1,0,'Other shop')");
        jdbc.update("INSERT INTO pms_product(id,merchant_id,product_name,publish_status,del_flag,price,stock,unit) VALUES(101,19,'Owned product',1,0,88,23,'piece'),(201,29,'Foreign product',1,0,99,31,'piece')");
        jdbc.update("INSERT INTO pms_sku(id,product_id,sku_name,price,stock) VALUES(102,101,'Owned SKU',77,13),(202,201,'Foreign SKU',99,21)");
        org.apache.ibatis.session.Configuration c=new org.apache.ibatis.session.Configuration(new Environment("isolated",new SpringManagedTransactionFactory(),db));
        for(Class<?> type:new Class<?>[]{MmsMerchant.class,UmsMember.class,PmsProduct.class,PmsSku.class,CmsMemberTemplate.class,CmsTemplateLibrary.class,com.chengyinhub.business.domain.vo.MemberTemplateVO.class})c.getTypeAliasRegistry().registerAlias(type.getSimpleName(),type);
        com.github.pagehelper.PageInterceptor page=new com.github.pagehelper.PageInterceptor();Properties props=new Properties();props.setProperty("helperDialect","mysql");page.setProperties(props);c.addInterceptor(page);
        for(String name:new String[]{"MmsMerchantMapper","UmsMemberMapper","MerchantOperatorMapper","PmsProductMapper","PmsSkuMapper","CmsMemberTemplateMapper","MerchantAuthoringRecordMapper"}){
            String resource="/mapper/business/"+name+".xml";try(InputStream in=getClass().getResourceAsStream(resource)){assertNotNull(in);new XMLMapperBuilder(in,c,resource,c.getSqlFragments()).parse();}
        }
        sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c));
        MmsMerchantServiceImpl merchants=new MmsMerchantServiceImpl();ReflectionTestUtils.setField(merchants,"mmsMerchantMapper",sql.getMapper(MmsMerchantMapper.class));
        authority=new MerchantAccessServiceImpl();ReflectionTestUtils.setField(authority,"merchantService",merchants);
        ReflectionTestUtils.setField(authority,"umsMemberMapper",sql.getMapper(UmsMemberMapper.class));ReflectionTestUtils.setField(authority,"merchantOperatorMapper",sql.getMapper(MerchantOperatorMapper.class));
        PmsProductServiceImpl products=new PmsProductServiceImpl();ReflectionTestUtils.setField(products,"pmsProductMapper",sql.getMapper(PmsProductMapper.class));ReflectionTestUtils.setField(products,"pmsSkuMapper",sql.getMapper(PmsSkuMapper.class));
        PmsSkuServiceImpl skus=new PmsSkuServiceImpl();ReflectionTestUtils.setField(skus,"pmsSkuMapper",sql.getMapper(PmsSkuMapper.class));
        facts=new MerchantAuthoringFactsService(authority,merchants,products,skus);
        CmsMemberTemplateServiceImpl templateRows=new CmsMemberTemplateServiceImpl();ReflectionTestUtils.setField(templateRows,"cmsMemberTemplateMapper",sql.getMapper(CmsMemberTemplateMapper.class));
        TemplatePublishServiceImpl publisher=new TemplatePublishServiceImpl();ReflectionTestUtils.setField(publisher,"memberTemplateService",templateRows);ReflectionTestUtils.setField(publisher,"memberTemplateMapper",sql.getMapper(CmsMemberTemplateMapper.class));
        ICapabilityGuard capability=(ICapabilityGuard)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ICapabilityGuard.class},(o,m,a)->{if(m.getDeclaringClass()==Object.class)return "synthetic-capability-boundary";capabilityCalls++;if(!m.getName().equals("checkAdvancedMechanics"))throw new AssertionError("Unexpected reward/medal entitlement path");return rejectCapability?"Denied capability":null;});ReflectionTestUtils.setField(publisher,"capabilityGuard",capability);
        ITemplatePublishService transactionalPublisher=(ITemplatePublishService)tx(publisher);
        records=(MerchantAuthoringRecordService)tx(new MerchantAuthoringRecordService(sql.getMapper(MerchantAuthoringRecordMapper.class),facts,authority,sql.getMapper(UmsMemberMapper.class),sql.getMapper(MerchantOperatorMapper.class),transactionalPublisher,sql.getMapper(CmsMemberTemplateMapper.class)));
        IAiCreatorService provider=(IAiCreatorService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IAiCreatorService.class},(o,m,a)->{
            if(m.getDeclaringClass()==Object.class)return "synthetic-ai-boundary";
            if(m.getName().equals("resolveRoleFor"))return new RoleServiceImpl().resolveRole(sql.getMapper(UmsMemberMapper.class).selectUmsMemberById((Long)a[0]));
            if(!m.getName().equals("templateFill"))throw new AssertionError("Unexpected AI route");aiCalls++;received=(AiTemplateFillReq)a[0];providerEffect.run();
            AiTemplateContent text=new AiTemplateContent();text.setTitle("Store story");text.setDescription("Find a clue");text.setRuleInstructions("Ask staff, or read the printed clue");text.setValidationMethod(received.getValidationMethod());text.setQuestionName("Which word?");text.setQuestionAnswer("clue");
            AiTemplateFillResp result=new AiTemplateFillResp();result.setTraceId("synthetic-ai-trace");result.setTemplate(text);return result;
        });
        creator=provider; bound=new MerchantBoundTemplateDraftService(facts,provider,records);
    }
    Object tx(Object target){ProxyFactory p=new ProxyFactory(target);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db),new AnnotationTransactionAttributeSource()));return p.getProxy();}
    @AfterEach void cleanup() throws Exception {org.springframework.security.core.context.SecurityContextHolder.clearContext();assertEquals(23L,jdbc.queryForObject("SELECT stock FROM pms_product WHERE id=101",Long.class));assertEquals(13L,jdbc.queryForObject("SELECT stock FROM pms_sku WHERE id=102",Long.class));assertEquals(88,jdbc.queryForObject("SELECT price FROM pms_product WHERE id=101",Integer.class));try(java.sql.Connection connection=db.getConnection(); java.sql.Statement statement=connection.createStatement()){statement.execute("SHUTDOWN");}}
    void mappedTable(String table,String file,String resultMap,Class<?> bean,boolean generated)throws Exception{
        String source;try(InputStream in=getClass().getResourceAsStream("/mapper/business/"+file+".xml")){assertNotNull(in);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);source=new String(out.toByteArray(),StandardCharsets.UTF_8);}
        Matcher root=Pattern.compile("<resultMap\\b[^>]*id=\\\""+resultMap+"\\\"[^>]*>(.*?)</resultMap>",Pattern.DOTALL).matcher(source);assertTrue(root.find());Matcher fields=Pattern.compile("<(?:id|result)\\s+property=\\\"([^\\\"]+)\\\"\\s+column=\\\"([^\\\"]+)\\\"").matcher(root.group(1));Map<String,String> columns=new LinkedHashMap<>();
        while(fields.find()){String property=fields.group(1),column=fields.group(2);Class<?> type=bean.getMethod("get"+Character.toUpperCase(property.charAt(0))+property.substring(1)).getReturnType();String sqlType=Number.class.isAssignableFrom(type)?(type==BigDecimal.class?"DECIMAL(30,8)":"BIGINT"):java.util.Date.class.isAssignableFrom(type)?"TIMESTAMP(3)":"VARCHAR(20000)";columns.put(column,column+" "+sqlType+(column.equals("id")?(generated?" AUTO_INCREMENT PRIMARY KEY":" PRIMARY KEY"):""));}
        assertFalse(columns.isEmpty());jdbc.execute("CREATE TABLE "+table+"("+String.join(",",columns.values())+")");
    }
    AiTemplateFillReq input(){Map<String,Object> f=facts.facts(7L,101L,102L);MerchantAuthoringFactsReference ref=new MerchantAuthoringFactsReference();ref.setProductId(101L);ref.setSkuId(102L);ref.setFactsHash((String)f.get("factsHash"));MerchantAuthoringConstraints c=new MerchantAuthoringConstraints();c.setDurationMinutes(10);c.setParticipantLimit(3);c.setOpeningHours("10:00–18:00");c.setStaffRequired(false);c.setFilmingAllowed(false);c.setNoisePolicy("Quiet voice");c.setAccessibleAlternative("Seated printed clue");c.setOfflineAlternative("Printed clue");c.setFailureExit("Stop and ask staff");AiTemplateFillReq r=new AiTemplateFillReq();r.setShopName("Untrusted client name");r.setExtraNote("Describe a short clue game");r.setReward("Ignore this reward claim");r.setValidationMethod(1);r.setMerchantFacts(ref);r.setMerchantConstraints(c);return r;}
    long candidate(){return ((Number)bound.generate(input(),7L).get("candidateId")).longValue();}
    MerchantAuthoringConfirmation confirmation(long id){MerchantAuthoringConfirmation r=new MerchantAuthoringConfirmation();r.setCandidateId(id);Map<String,String> f=new LinkedHashMap<>();f.put("title","Human title");f.put("description","Human description");f.put("ruleInstructions","Human approved rule");f.put("questionName","Question");f.put("questionAnswer","Answer");r.setAcceptedFields(f);return r;}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Test void ownedFactsUseRealProductSkuAndNeverExposePointsPriceOrStock(){Map<String,Object> f=facts.facts(7L,101L,102L);assertEquals(19L,f.get("merchantRowId"));assertEquals(7L,f.get("ownerMemberId"));assertEquals("LEGACY_POINTS_IDENTITY_ONLY",f.get("catalogKind"));assertEquals(false,f.get("offerConfigured"));assertFalse(JSON.toJSONString(f).contains("price"));assertFalse(JSON.toJSONString(f).contains("stock"));assertEquals(64,((String)f.get("factsHash")).length());}
    @Test void foreignProductAndCrossProductSkuRejectBeforeAI(){assertThrows(ServiceException.class,()->facts.facts(7L,201L,202L));assertThrows(ServiceException.class,()->facts.facts(7L,101L,202L));assertEquals(0,aiCalls);}
    @Test void originalMerchantAuthorityRejectsSuspensionAndAmbiguousOwner(){jdbc.update("UPDATE mms_merchant SET account_status=2 WHERE id=19");assertThrows(ServiceException.class,()->facts.facts(7L,101L,102L));jdbc.update("UPDATE mms_merchant SET account_status=1 WHERE id=19");jdbc.update("INSERT INTO mms_merchant(id,member_id,status,account_status,del_flag) VALUES(39,7,1,1,0)");assertThrows(ServiceException.class,()->facts.listMine(7L,1));}
    @Test void providerReceivesServerFactsAndConfirmedConstraintsOnly(){Map<String,Object> result=bound.generate(input(),7L);assertEquals(1,aiCalls);assertEquals("Owned shop",received.getShopName());assertEquals("",received.getReward());assertTrue(received.getExtraNote().contains("offlineAlternative"));assertFalse(received.getExtraNote().contains("Untrusted client name"));assertTrue(((Number)result.get("candidateId")).longValue()>0);assertEquals(1,count("merchant_authoring_candidate"));assertEquals(0,count("cms_member_template"));}
    @Test void changedFactsBeforeProviderProduceZeroCalls(){AiTemplateFillReq r=input();jdbc.update("UPDATE pms_product SET product_name='Changed' WHERE id=101");assertThrows(ServiceException.class,()->bound.generate(r,7L));assertEquals(0,aiCalls);}
    @Test void changedFactsDuringProviderDoNotPersistCandidate(){providerEffect=()->jdbc.update("UPDATE pms_sku SET sku_name='Changed' WHERE id=102");assertThrows(ServiceException.class,()->bound.generate(input(),7L));assertEquals(1,aiCalls);assertEquals(0,count("merchant_authoring_candidate"));}
    @Test void filmingDeniedCannotGeneratePhotoGame(){AiTemplateFillReq r=input();r.setValidationMethod(2);assertThrows(ServiceException.class,()->bound.generate(r,7L));assertEquals(0,aiCalls);}
    @Test void realTemplateSaveAndSourceCommitTogetherWithoutPublication(){Map<String,Object> receipt=records.confirm(7L,confirmation(candidate()));long id=((Number)receipt.get("templateId")).longValue();CmsMemberTemplate saved=sql.getMapper(CmsMemberTemplateMapper.class).selectCmsMemberTemplateById(id);assertEquals(7L,saved.getMemberId());assertEquals(0,saved.getDraftStatus());assertEquals(0L,saved.getPublishStatus());assertEquals(0,saved.getIsSync());assertEquals("Human title",saved.getTitle());assertEquals(10L,saved.getDuration());assertEquals("3",saved.getPlayers());assertTrue(saved.getMerchantGuide().contains("Seated printed clue"));assertNull(saved.getCouponId());assertEquals(1,count("merchant_authoring_source"));assertEquals(false,receipt.get("approved"));assertEquals(1,capabilityCalls);}
    @Test void sameCandidateRetryReturnsSameTemplateAndChangedConfirmationRejects(){long id=candidate();MerchantAuthoringConfirmation r=confirmation(id);Map<String,Object> a=records.confirm(7L,r);assertEquals(a,records.confirm(7L,r));assertEquals(1,count("cms_member_template"));r.getAcceptedFields().put("title","Different");assertThrows(ServiceException.class,()->records.confirm(7L,r));assertEquals(1,count("cms_member_template"));}
    @Test void sourceInsertFailureRollsBackRealTemplateInsert(){long id=candidate();jdbc.execute("ALTER TABLE merchant_authoring_source ADD CONSTRAINT reject_source CHECK (actual_template_id < 0)");assertThrows(RuntimeException.class,()->records.confirm(7L,confirmation(id)));assertEquals(0,count("cms_member_template"));assertEquals(0,count("merchant_authoring_source"));assertEquals(1,count("merchant_authoring_candidate"));}
    @Test void capabilityRejectionLeavesCandidateAndCreatesNoDraftOrSource(){long id=candidate();rejectCapability=true;assertThrows(RuntimeException.class,()->records.confirm(7L,confirmation(id)));assertEquals(0,count("cms_member_template"));assertEquals(0,count("merchant_authoring_source"));}
    @Test void principalChangeCannotConsumeOldCandidate(){long id=candidate();assertThrows(ServiceException.class,()->records.confirm(9L,confirmation(id)));assertEquals(0,count("cms_member_template"));}
    @Test void priceInventoryOrTemplateIDFieldsAreRejected(){long id=candidate();for(String key:Arrays.asList("price","stock","id","couponId","isSync")){MerchantAuthoringConfirmation r=confirmation(id);r.getAcceptedFields().put(key,"1");assertThrows(ServiceException.class,()->records.confirm(7L,r));}assertEquals(0,count("cms_member_template"));}
    @Test void factChangeBeforeConfirmationDoesNotSave(){long id=candidate();jdbc.update("UPDATE pms_product SET product_name='Replacement' WHERE id=101");assertThrows(ServiceException.class,()->records.confirm(7L,confirmation(id)));assertEquals(0,count("cms_member_template"));}
    @Test void catalogPaginationNeverLimitsAuthorityUniquenessQuery(){for(int i=2;i<=22;i++)jdbc.update("INSERT INTO pms_product(id,merchant_id,product_name,publish_status,del_flag) VALUES(?,19,'Owned product',1,0)",1000+i);List<PmsProduct> rows=facts.listMine(7L,2);assertEquals(2,rows.size());assertEquals(2,facts.projectCatalog(7L,rows).size());}
    @Test void separatelyAIEligibleManagerUsesServerOwnerRatherThanPrincipalForNewDraft(){
        jdbc.update("INSERT INTO merchant_operator(id,merchant_id,operator_member_id,active_operator_member_id,role_code,status,version) VALUES(301,19,17,17,'MERCHANT_MANAGER','ACTIVE',1)");
        AiTemplateFillReq request=input();Map<String,Object> generated=bound.generate(request,17L);
        Map<String,Object> receipt=records.confirm(17L,confirmation(((Number)generated.get("candidateId")).longValue()));
        CmsMemberTemplate saved=sql.getMapper(CmsMemberTemplateMapper.class).selectCmsMemberTemplateById(((Number)receipt.get("templateId")).longValue());
        assertEquals(7L,saved.getMemberId());assertEquals("17",saved.getCreateBy());
    }
    @Test void revokedSeparatelyAIEligibleManagerCannotConfirmPreviouslyGeneratedCandidate(){
        jdbc.update("INSERT INTO merchant_operator(id,merchant_id,operator_member_id,active_operator_member_id,role_code,status,version) VALUES(301,19,17,17,'MERCHANT_MANAGER','ACTIVE',1)");
        long id=((Number)bound.generate(input(),17L).get("candidateId")).longValue();
        jdbc.update("UPDATE merchant_operator SET role_code='MERCHANT_CHECKIN',version=2 WHERE id=301");
        assertThrows(ServiceException.class,()->records.confirm(17L,confirmation(id)));assertEquals(0,count("cms_member_template"));
    }
    @Test void confirmationNeverRewritesExistingPublishedTemplate(){
        jdbc.update("INSERT INTO cms_member_template(id,member_id,title,status,publish_status,draft_status,del_flag) VALUES(900,7,'Published title',1,1,1,0)");
        Map<String,Object> receipt=records.confirm(7L,confirmation(candidate()));
        assertNotEquals(900L,((Number)receipt.get("templateId")).longValue());
        assertEquals("Published title",jdbc.queryForObject("SELECT title FROM cms_member_template WHERE id=900",String.class));
        assertEquals(1L,jdbc.queryForObject("SELECT publish_status FROM cms_member_template WHERE id=900",Long.class));
    }

    @Test void legacyNullDeletionFlagMatchesExistingProductMapperContract(){
        jdbc.update("UPDATE pms_product SET del_flag=NULL WHERE id=101");
        assertEquals(101L,facts.facts(7L,101L,102L).get("productId"));
        assertNotNull(records.confirm(7L,confirmation(candidate())).get("templateId"));
    }

    private org.springframework.test.web.servlet.MockMvc mvc() {
        com.chengyinhub.web.controller.api.ApiMerchantAuthoringController controller = new com.chengyinhub.web.controller.api.ApiMerchantAuthoringController();
        ReflectionTestUtils.setField(controller,"facts",facts);ReflectionTestUtils.setField(controller,"records",records);
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
    }
    private void login(Long inner,Long outer) {
        com.chengyinhub.common.core.domain.entity.AppUser app = new com.chengyinhub.common.core.domain.entity.AppUser(); app.setUserId(inner);
        com.chengyinhub.common.core.domain.model.LoginAppUser user = new com.chengyinhub.common.core.domain.model.LoginAppUser(outer,app);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user,"synthetic",Collections.emptyList()));
    }
    @Test void ordinaryHTTPUsesInnerAuthenticatedPrincipalAndActualSQL() throws Exception {
        login(7L,999L);
        String body=mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/merchant/authoring/catalog").param("pageNum","1").contentType("application/json").content("{}"))
            .andReturn().getResponse().getContentAsString();
        com.alibaba.fastjson2.JSONObject envelope=JSON.parseObject(body);assertEquals(200,envelope.getIntValue("code"));
        assertEquals(101L,envelope.getJSONObject("data").getJSONArray("rows").getJSONObject(0).getLongValue("productId"));
        assertFalse(body.contains("price"));assertFalse(body.contains("stock"));
    }
    @Test void ordinaryHTTPConfirmationUsesSameRealAtomicWriter() throws Exception {
        long id=candidate();login(7L,999L);
        String body=mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/merchant/authoring/draft").contentType("application/json").content(JSON.toJSONString(confirmation(id))))
            .andReturn().getResponse().getContentAsString();
        assertEquals(200,JSON.parseObject(body).getIntValue("code"));assertEquals(1,count("cms_member_template"));assertEquals(1,count("merchant_authoring_source"));
    }
    @Test void absentAndPartialHTTPIdentityFailBeforeFactsOrDraft() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        String body=mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/merchant/authoring/facts").contentType("application/json").content("{\"productId\":101,\"skuId\":102}"))
            .andReturn().getResponse().getContentAsString();assertEquals(401,JSON.parseObject(body).getIntValue("code"));
        login(null,7L);body=mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/merchant/authoring/catalog").param("pageNum","1"))
            .andReturn().getResponse().getContentAsString();assertEquals(401,JSON.parseObject(body).getIntValue("code"));assertEquals(0,aiCalls);assertEquals(0,count("cms_member_template"));
    }

    @Test void disabledProfileDoesNotMountTheNewEndpointsOrServices(){
        try(org.springframework.context.annotation.AnnotationConfigApplicationContext context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            context.register(MerchantAuthoringFactsService.class,MerchantBoundTemplateDraftService.class,MerchantAuthoringRecordService.class,com.chengyinhub.web.controller.api.ApiMerchantAuthoringController.class);
            context.refresh();assertTrue(context.getBeansOfType(MerchantAuthoringFactsService.class).isEmpty());assertTrue(context.getBeansOfType(MerchantAuthoringRecordService.class).isEmpty());assertTrue(context.getBeansOfType(com.chengyinhub.web.controller.api.ApiMerchantAuthoringController.class).isEmpty());
        }
    }
    @Test void existingAIControllerRefusesBoundPayloadWhenProfileIsOff(){
        login(7L,7L);com.chengyinhub.web.controller.api.ApiAiController controller=new com.chengyinhub.web.controller.api.ApiAiController();ReflectionTestUtils.setField(controller,"aiCreatorService",creator);
        Map<String,Object> result=controller.templateFill(input());assertEquals(503,result.get("code"));assertEquals(0,aiCalls);assertEquals(0,count("merchant_authoring_candidate"));
    }

    private Path migrationPath() {
        String override=System.getProperty("merchant.authoring.repoRoot");
        Path root=Paths.get(override==null?System.getProperty("user.dir"):override).toAbsolutePath();
        while(root!=null){Path file=root.resolve("chengyinhub-admin/sql/migration_merchant_ai_authoring_source_v1.sql");if(Files.isRegularFile(file))return file;root=root.getParent();}
        throw new AssertionError("Repository authoring migration is required; no synthesized schema fallback");
    }
    @Test void actualRoleServiceDoesNotPromotePlayerRoleEmployeeToAIEligibility(){
        jdbc.update("UPDATE ums_member SET role='player',user_type=1 WHERE id=17");
        jdbc.update("INSERT INTO merchant_operator(id,merchant_id,operator_member_id,active_operator_member_id,role_code,status,version) VALUES(301,19,17,17,'MERCHANT_MANAGER','ACTIVE',1)");
        assertEquals(7L,authority.require(17L,MerchantPermission.PROJECT_MANAGE).getOwnerMemberId());
        assertEquals("player",new RoleServiceImpl().resolveRole(sql.getMapper(UmsMemberMapper.class).selectUmsMemberById(17L)));
        ServiceException error=assertThrows(ServiceException.class,()->bound.generate(input(),17L));assertEquals(403,error.getCode());
        assertEquals(0,aiCalls);assertEquals(0,count("merchant_authoring_candidate"));
    }
    @Test void existingAIControllerKeepsActualPlayerEmployeeRoleBlockedBeforeProvider(){
        jdbc.update("UPDATE ums_member SET role='player',user_type=1 WHERE id=17");
        jdbc.update("INSERT INTO merchant_operator(id,merchant_id,operator_member_id,active_operator_member_id,role_code,status,version) VALUES(301,19,17,17,'MERCHANT_MANAGER','ACTIVE',1)");
        login(17L,17L);com.chengyinhub.web.controller.api.ApiAiController controller=new com.chengyinhub.web.controller.api.ApiAiController();
        ReflectionTestUtils.setField(controller,"aiCreatorService",creator);ReflectionTestUtils.setField(controller,"merchantBoundTemplateDraftService",bound);
        Map<String,Object> result=controller.templateFill(input());assertNotEquals(200,result.get("code"));assertEquals(0,aiCalls);assertEquals(0,count("merchant_authoring_candidate"));
    }

}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.marketplace.paid.*;
import com.chengyinhub.business.marketplace.contract.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.junit.jupiter.api.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic data, real Spring transactions/MyBatis/H2 and official SDK signature/AES verification. No network/provider is invoked. */
class WorkshopPaidLicenseTransactionTest {
    private static final Instant NOW=Instant.parse("2026-10-05T00:00:00Z");
    private ConnectionProbe connectionProbe;
    private JdbcTemplate jdbc;private DataSourceTransactionManager manager;private SqlSessionTemplate session;
    private PersistedPaidLicenseService service;private PaidPurchaseSnapshot snapshot;private Probe probe;
    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:paid_"+System.nanoTime()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        ds.setDriverClassName("org.h2.Driver");connectionProbe=new ConnectionProbe(ds);jdbc=new JdbcTemplate(connectionProbe);manager=new DataSourceTransactionManager(connectionProbe);
        // Exact queried OMS column names/types, not a replacement money ledger. The complete legacy schema is not bootstrapped here.
        jdbc.execute("create table ums_member(id bigint primary key,status int,del_flag int)");jdbc.update("insert into ums_member values(7,1,0),(8,1,0)");
        jdbc.execute("create table oms_order(id bigint primary key,order_sn varchar(80) unique,member_id bigint,mms_merchant_id bigint,biz_type varchar(50),status bigint,pay_type bigint,aftersale_status bigint,product_quantity bigint,del_flag int,entitlement_days int,entitlement_max_usage int,payment_transaction_id varchar(64),payment_time timestamp(0),update_time timestamp(6),pay_amount decimal(18,2),order_total_amount decimal(18,2),product_total_amount decimal(18,2),delivery_fee decimal(18,2),tax_fee decimal(18,2),points_amount decimal(18,2),coupon_amount decimal(18,2))");
        jdbc.execute("create table oms_order_item(id bigint primary key,order_id bigint,product_id bigint,sku_id bigint,quantity bigint,aftersale_order_id bigint,aftersale_status bigint,price decimal(18,2))");
        String ddl=new String(Files.readAllBytes(Paths.get("sql/migration_w18_paid_order_license_20261005.sql")),StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$","").replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","");
        for(String part:ddl.split(";"))if(!part.trim().isEmpty())jdbc.execute(part);
        Configuration c=new Configuration();c.setEnvironment(new Environment("paid-license",new SpringManagedTransactionFactory(),connectionProbe));
        String p="mapper/marketplace/PaidLicenseStore.xml";try(InputStream in=Resources.getResourceAsStream(p)){new XMLMapperBuilder(in,c,p,c.getSqlFragments()).parse();}
        String indexPath="mapper/marketplace/PurchasedLicenseIndexStore.xml";try(InputStream in=Resources.getResourceAsStream(indexPath)){new XMLMapperBuilder(in,c,indexPath,c.getSqlFragments()).parse();}
        for(String mapper:Arrays.asList("ModuleReservationStore","ModuleInstallationStore","FreeInstallTargetStore","PaidInstallCommandStore","PaidProfessionalTargetStore","PaidInstalledTextStore","PaidProfessionalMaterializationStore","PaidProfessionalTemplateStore","PaidProfessionalOperationStore")) {
            String path="mapper/marketplace/"+mapper+".xml";try(InputStream in=Resources.getResourceAsStream(path)){new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}
        }
        probe=new Probe();c.addInterceptor(probe);session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c));service=enabled(Clock.fixed(NOW,ZoneOffset.UTC));
        snapshot=snapshot();seed(1,11,"W18_synthetic_order_0001");
    }
    private PersistedPaidLicenseService enabled(Clock clock){return new PersistedPaidLicenseService(session,manager,clock,PersistedPaidLicenseService.Policy.VERIFIED_WECHAT_PURCHASED_VERSION_V1);}
    private PaidPurchaseSnapshot snapshot(){
        byte[] content="{\"schema\":\"w18-member-text-v1\",\"title\":\"Synthetic purchased content\",\"bindings\":{}}".getBytes(StandardCharsets.UTF_8),terms="Synthetic reviewed purchased-version rights, not production terms".getBytes(StandardCharsets.UTF_8);
        BuyerPrincipal seller=new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,8);
        LicenseTermsSnapshot rights=new LicenseTermsSnapshot("terms-1",terms,PaidPurchaseSnapshot.sha(terms),"reviewed:terms-1",
            LicenseTermsSnapshot.Permission.PROHIBITED,LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION,LicenseTermsSnapshot.Permission.PROHIBITED,
            LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION,LicenseTermsSnapshot.Redistribution.PROHIBITED,Collections.singleton("synthetic-region"),
            LicenseTermsSnapshot.Limit.atMost(1),LicenseTermsSnapshot.Limit.atMost(0),LicenseTermsSnapshot.Limit.atMost(10),LicenseTermsSnapshot.UseDuration.PERPETUAL_PURCHASED_VERSION);
        ModuleVersionSnapshot version=new ModuleVersionSnapshot(seller,"module-1","version-1","v1",1,ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE,12,"source-revision-1",content,PaidPurchaseSnapshot.sha(content),"review:source-1",Collections.emptyMap());
        LicenseOfferSnapshot offer=LicenseOfferSnapshot.oneTimePurchase("offer-1","offer-version-1","module-1",seller,Collections.singleton(BuyerPrincipal.Kind.INDIVIDUAL),1000,"CNY",rights);
        return new PaidPurchaseSnapshot(version,offer,content,terms,"review:offer-1");
    }
    private void seed(long order,long line,String number){
        jdbc.update("insert into oms_order(id,order_sn,member_id,biz_type,status,pay_type,aftersale_status,product_quantity,del_flag,pay_amount,order_total_amount,product_total_amount,delivery_fee,tax_fee,points_amount,coupon_amount) values(?,?,7,'workshop_module_v1',0,0,1,1,0,10.00,10.00,10.00,0,0,0,0)",order,number);
        jdbc.update("insert into oms_order_item(id,order_id,product_id,sku_id,quantity,aftersale_status,price) values(?,?,77,88,1,0,10.00)",line,order);
        String json=snapshot.encode();jdbc.update("insert into w18_paid_order_line_snapshot(order_line_id,order_id,buyer_member_id,product_id,sku_id,amount_minor,currency,provider_app_id,provider_merchant_id,snapshot_json,snapshot_hash,source_content,terms_document,state,frozen_at) values(?,?,7,77,88,1000,'CNY','synthetic_app','synthetic_merchant',?,?,?,?,'FROZEN',?)",
                line,order,json.getBytes(StandardCharsets.UTF_8),PaidPurchaseSnapshot.sha(json.getBytes(StandardCharsets.UTF_8)),snapshot.getContent(),snapshot.getTerms(),Timestamp.from(NOW.minusSeconds(60)));
    }
    private VerifiedWorkshopPayment event(){return event("W18_synthetic_order_0001","synthetic_transaction_1","synthetic_app","synthetic_merchant",1000,NOW);}
    private VerifiedWorkshopPayment event(String number,String transaction,String app,String merchant,long amount,Instant paid){return new VerifiedWorkshopPayment(number,transaction,app,merchant,"CNY",amount,paid);}
    private int count(){return jdbc.queryForObject("select count(*) from w18_paid_license",Integer.class);}
    private void none(){assertEquals(0,count());assertEquals(Long.valueOf(0),jdbc.queryForObject("select status from oms_order where id=1",Long.class));}
    @Test void defaultDisabledPerformsNoReadOrWrite(){PersistedPaidLicenseService off=new PersistedPaidLicenseService(session,manager,Clock.fixed(NOW,ZoneOffset.UTC));assertFalse(off.accept(event()));none();assertThrows(IllegalArgumentException.class,()->off.readOwned(7,"w18-paid-11"));}
    @Test void verifiedPaymentCommitsOneOrderMutationAndExactPerpetualGrant(){
        assertTrue(service.accept(event()));assertEquals(1,count());ModuleLicenseGrant grant=service.readOwned(7,"w18-paid-11");
        assertEquals(ModuleLicenseGrant.EvidenceKind.VERIFIED_PAID_ORDER_LINE,grant.getEvidenceKind());assertNull(grant.getValidUntil());
        assertEquals(LicenseTermsSnapshot.UseDuration.PERPETUAL_PURCHASED_VERSION,grant.getOffer().getTerms().getUseDuration());
        assertEquals(Collections.singletonMap("version-1",snapshot.getVersion().getContentHash()),grant.getGrantedVersionHashes());
        assertEquals(snapshot.getOffer().getTerms().getTermsHash(),grant.getOffer().getTerms().getTermsHash());assertTrue(grant.isActiveAt(NOW.plusSeconds(86400L*365*100)));
        assertEquals(Long.valueOf(1),jdbc.queryForObject("select status from oms_order where id=1",Long.class));
    }
    @Test void duplicateNotificationIsExactlyOneLicense(){assertTrue(service.accept(event()));assertTrue(service.accept(event()));assertTrue(service.accept(event()));assertEquals(1,count());}
    @Test void concurrentDuplicateNotificationsSerializeOnExistingOmsOrder() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(2);try{Future<Boolean>a=workers.submit(()->service.accept(event())),b=workers.submit(()->service.accept(event()));assertTrue(a.get(10,TimeUnit.SECONDS));assertTrue(b.get(10,TimeUnit.SECONDS));assertEquals(1,count());}finally{workers.shutdownNow();}
    }
    @Test void differentTransactionOnSameOrderIsRejected(){assertTrue(service.accept(event()));assertFalse(service.accept(event("W18_synthetic_order_0001","other_transaction","synthetic_app","synthetic_merchant",1000,NOW)));assertEquals(1,count());}
    @Test void sameTransactionCannotGrantDifferentOrderAndSecondOrderRollsBack(){
        assertTrue(service.accept(event()));seed(2,22,"W18_synthetic_order_0002");
        assertThrows(RuntimeException.class,()->service.accept(event("W18_synthetic_order_0002","synthetic_transaction_1","synthetic_app","synthetic_merchant",1000,NOW)));
        assertEquals(1,count());assertEquals(Long.valueOf(0),jdbc.queryForObject("select status from oms_order where id=2",Long.class));
    }
    @Test void failedLicenseInsertRollsBackPaidMutation(){probe.failInsert=true;Throwable error=assertThrows(org.mybatis.spring.MyBatisSystemException.class,()->service.accept(event()));
        while(error.getCause()!=null)error=error.getCause();assertEquals(IllegalStateException.class,error.getClass());assertEquals("synthetic storage failure",error.getMessage());none();assertNull(jdbc.queryForObject("select payment_transaction_id from oms_order where id=1",String.class));}
    @Test void orderStatusAndTimestampAloneNeverIssueLicense(){jdbc.update("update oms_order set status=1,pay_type=1,payment_transaction_id='synthetic_transaction_1',payment_time=? where id=1",Timestamp.from(NOW));assertFalse(service.accept(event()));assertEquals(0,count());assertThrows(IllegalArgumentException.class,()->service.readOwned(7,"w18-paid-11"));}
    @Test void wrongAmountApplicationAndMerchantAreRejected(){
        for(VerifiedWorkshopPayment e:Arrays.asList(event("W18_synthetic_order_0001","synthetic_transaction_1","other_app","synthetic_merchant",1000,NOW),event("W18_synthetic_order_0001","synthetic_transaction_1","synthetic_app","other_merchant",1000,NOW),event("W18_synthetic_order_0001","synthetic_transaction_1","synthetic_app","synthetic_merchant",999,NOW)))assertFalse(service.accept(e));none();
    }
    @Test void lateFreezeAndFuturePaymentFailBeforeAnyMutation(){assertFalse(service.accept(event("W18_synthetic_order_0001","synthetic_transaction_1","synthetic_app","synthetic_merchant",1000,NOW.plusSeconds(301))));jdbc.update("update w18_paid_order_line_snapshot set frozen_at=?",Timestamp.from(NOW.plusSeconds(1)));assertFalse(service.accept(event()));none();}
    @Test void subscriptionBusinessAndDurationCannotBePromotedToPurchasedVersion(){jdbc.update("update oms_order set biz_type='premium_template',entitlement_days=30");assertFalse(service.accept(event()));none();}
    @Test void frozenOwnerProductAndSkuMustMatchOrderLine(){
        jdbc.update("update w18_paid_order_line_snapshot set buyer_member_id=8");assertFalse(service.accept(event()));none();
        jdbc.update("update w18_paid_order_line_snapshot set buyer_member_id=7,sku_id=99");assertFalse(service.accept(event()));none();
    }
    @Test void pendingOrSuccessfulRefundBlocksFirstIssue(){for(int state:Arrays.asList(2,3,4)){jdbc.update("update oms_order set aftersale_status=?",state);assertFalse(service.accept(event()));none();}}
    @Test void refundAfterPaymentSuspendsUseAndDuplicateCannotRestoreIt(){
        assertTrue(service.accept(event()));jdbc.update("update oms_order set aftersale_status=4,status=5");
        assertTrue(service.accept(event()));assertEquals(ModuleLicenseGrant.Status.SUSPENDED,service.readOwned(7,"w18-paid-11").getStatus());assertEquals(1,count());
    }
    @Test void ExplicitRevocationSurvivesReplayAndFreshReader(){
        assertTrue(service.accept(event()));jdbc.update("update w18_paid_license set state='REVOKED'");assertTrue(service.accept(event()));
        assertEquals(ModuleLicenseGrant.Status.REVOKED,enabled(Clock.fixed(NOW.plusSeconds(1000),ZoneOffset.UTC)).readOwned(7,"w18-paid-11").getStatus());assertEquals(1,count());
    }
    @Test void sourceEmergencyRevocationPreservesReceiptAndDeniesUse(){assertTrue(service.accept(event()));jdbc.update("update w18_paid_order_line_snapshot set state='REVOKED'");assertTrue(service.accept(event()));assertEquals(ModuleLicenseGrant.Status.REVOKED,service.readOwned(7,"w18-paid-11").getStatus());}
    @Test void wrongOwnerAndInactiveMemberCannotRead(){assertTrue(service.accept(event()));assertThrows(IllegalArgumentException.class,()->service.readOwned(8,"w18-paid-11"));jdbc.update("update ums_member set status=0 where id=7");assertThrows(IllegalArgumentException.class,()->service.readOwned(7,"w18-paid-11"));}
    @Test void reconstructedServiceAndElapsedTimeDoNotExpirePurchasedVersion(){assertTrue(service.accept(event()));ModuleLicenseGrant old=service.readOwned(7,"w18-paid-11"),fresh=enabled(Clock.fixed(NOW.plusSeconds(315360000),ZoneOffset.UTC)).readOwned(7,"w18-paid-11");assertEquals(old.getLicenseId(),fresh.getLicenseId());assertTrue(fresh.isActiveAt(NOW.plusSeconds(315360000)));assertNull(fresh.getValidUntil());}
    @Test void tamperedSnapshotContentOrTermsNeverIssue(){jdbc.update("update w18_paid_order_line_snapshot set source_content=?","tampered".getBytes(StandardCharsets.UTF_8));assertFalse(service.accept(event()));none();}
    @Test void deeplyNestedHashConsistentSnapshotFailsWithoutStackOverflow(){String deep=new String(new char[8000]).replace('\0','[')+"0"+new String(new char[8000]).replace('\0',']');jdbc.update("update w18_paid_order_line_snapshot set snapshot_json=?,snapshot_hash=?",deep.getBytes(StandardCharsets.UTF_8),PaidPurchaseSnapshot.sha(deep.getBytes(StandardCharsets.UTF_8)));assertFalse(service.accept(event()));none();}
    @Test void canonicalSnapshotRejectsUnknownDuplicateAndMissingDuration(){
        String json=snapshot.encode();for(String corrupt:Arrays.asList(json.replace("{\"format\":","{\"unexpected\":true,\"format\":"),json.replace("{\"format\":","{\"format\":\"duplicate\",\"format\":"),json.replace("\"useDuration\":\"PERPETUAL_PURCHASED_VERSION\",",""))){assertThrows(IllegalArgumentException.class,()->PaidPurchaseSnapshot.decode(corrupt,snapshot.getContent(),snapshot.getTerms()));}
    }
    @Test void plainTermsAreNotParsedAsJson(){byte[] text="[[[[ not json at all".getBytes(StandardCharsets.UTF_8);assertThrows(IllegalArgumentException.class,()->PaidPurchaseSnapshot.decode(snapshot.encode(),snapshot.getContent(),text));assertArrayEquals(snapshot.getTerms(),PaidPurchaseSnapshot.decode(snapshot.encode(),snapshot.getContent(),snapshot.getTerms()).getTerms());}
    @Test void multipleLinesAndFeeOrPointsCannotChangePurchaseMeaning(){jdbc.update("insert into oms_order_item(id,order_id,product_id,quantity,aftersale_status,price) values(99,1,77,1,0,10)");assertFalse(service.accept(event()));none();jdbc.update("delete from oms_order_item where id=99");jdbc.update("update oms_order set points_amount=1");assertFalse(service.accept(event()));none();}
    @Test void realSignedEncryptedNotificationRoutesThroughTypedBridgeIntoOneDurableGrant() throws Exception {
        Signed signed=sign("SUCCESS","CNY","synthetic_app","synthetic_merchant",1000);
        assertTrue(WorkshopPaidNotificationRoute.dispatch(signed.parse(),service));assertEquals(1,count());
        assertTrue(WorkshopPaidNotificationRoute.dispatch(signed.parse(),service));assertEquals(1,count());
        assertNull(service.readOwned(7,"w18-paid-11").getValidUntil());
    }
    @Test void tamperedSignatureCannotReachDatabaseMutation() throws Exception {
        Signed signed=sign("SUCCESS","CNY","synthetic_app","synthetic_merchant",1000);
        byte[] bytes=Base64.getDecoder().decode(signed.signature);bytes[0]^=0x7f;signed.signature=Base64.getEncoder().encodeToString(bytes);
        assertThrows(com.wechat.pay.java.core.exception.ValidationException.class,()->WorkshopPaidNotificationRoute.dispatch(signed.parse(),service));none();
    }
    @Test void tamperedEncryptedBodyCannotReachDatabaseMutation() throws Exception {
        Signed signed=sign("SUCCESS","CNY","synthetic_app","synthetic_merchant",1000);signed.body=signed.body.replace("synthetic-event","changed-event");
        assertThrows(com.wechat.pay.java.core.exception.ValidationException.class,()->WorkshopPaidNotificationRoute.dispatch(signed.parse(),service));none();
    }
    @Test void genuinelySignedWrongCurrencyOrPayeeAndAmountStillReject() throws Exception {
        for(String[] item:new String[][]{{"USD","synthetic_app","synthetic_merchant","1000"},{"CNY","wrong_app","synthetic_merchant","1000"},{"CNY","synthetic_app","wrong_merchant","1000"},{"CNY","synthetic_app","synthetic_merchant","999"}}){
            Signed signed=sign("SUCCESS",item[0],item[1],item[2],Integer.parseInt(item[3]));assertFalse(WorkshopPaidNotificationRoute.dispatch(signed.parse(),service));none();
        }
    }
    @Test void missingHandlerAndNonSuccessRemainClosedAfterRealSignatureVerification() throws Exception {
        assertFalse(WorkshopPaidNotificationRoute.dispatch(sign("SUCCESS","CNY","synthetic_app","synthetic_merchant",1000).parse(),null));
        assertFalse(WorkshopPaidNotificationRoute.dispatch(sign("CLOSED","CNY","synthetic_app","synthetic_merchant",1000).parse(),service));none();
    }
    private static Signed sign(String state,String currency,String app,String merchant,int total) throws Exception {
        java.security.KeyPairGenerator generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(2048);java.security.KeyPair pair=generator.generateKeyPair();
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        // SDK takes a UTF-8 key string. Use random ASCII test bytes, never an external credential.
        for(int i=0;i<key.length;i++)key[i]=(byte)('A'+((key[i]&255)%26));
        String apiKey=new String(key,StandardCharsets.UTF_8),serial="synthetic-public-key",nonce="synthetic-nonce12",associated="transaction";
        com.wechat.pay.java.core.notification.NotificationParser parser=new com.wechat.pay.java.core.notification.NotificationParser(
                new com.wechat.pay.java.core.notification.RSAPublicKeyNotificationConfig.Builder().publicKey(pair.getPublic()).publicKeyId(serial).apiV3Key(apiKey).build());
        String plaintext=String.format(Locale.ROOT,"{\"out_trade_no\":\"W18_synthetic_order_0001\",\"transaction_id\":\"synthetic_transaction_1\",\"trade_state\":\"%s\",\"appid\":\"%s\",\"mchid\":\"%s\",\"success_time\":\"2026-10-05T00:00:00Z\",\"amount\":{\"total\":%d,\"currency\":\"%s\"}}",state,app,merchant,total,currency);
        String cipher=new com.wechat.pay.java.core.cipher.AeadAesCipher(key).encrypt(associated.getBytes(StandardCharsets.UTF_8),nonce.getBytes(StandardCharsets.UTF_8),plaintext.getBytes(StandardCharsets.UTF_8));
        Signed value=new Signed();value.parser=parser;value.serial=serial;value.timestamp=Long.toString(System.currentTimeMillis()/1000);value.nonce=UUID.randomUUID().toString();
        value.body=String.format(Locale.ROOT,"{\"id\":\"synthetic-event\",\"create_time\":\"2026-10-05T00:00:00Z\",\"event_type\":\"TRANSACTION.SUCCESS\",\"resource_type\":\"encrypt-resource\",\"resource\":{\"original_type\":\"transaction\",\"algorithm\":\"AEAD_AES_256_GCM\",\"ciphertext\":\"%s\",\"associated_data\":\"%s\",\"nonce\":\"%s\"}}",cipher,associated,nonce);
        java.security.Signature signer=java.security.Signature.getInstance("SHA256withRSA");signer.initSign(pair.getPrivate());signer.update((value.timestamp+"\n"+value.nonce+"\n"+value.body+"\n").getBytes(StandardCharsets.UTF_8));value.signature=Base64.getEncoder().encodeToString(signer.sign());return value;
    }
    private static final class Signed {
        com.wechat.pay.java.core.notification.NotificationParser parser;String serial,timestamp,nonce,body,signature;
        com.wechat.pay.java.service.payments.model.Transaction parse(){return parser.parse(new com.wechat.pay.java.core.notification.RequestParam.Builder()
                .serialNumber(serial).timestamp(timestamp).nonce(nonce).body(body).signature(signature).build(),com.wechat.pay.java.service.payments.model.Transaction.class);}
    }
    private com.chengyinhub.business.marketplace.paid.read.PersistedPurchasedLicenseReader metadata(){
        return new com.chengyinhub.business.marketplace.paid.read.PersistedPurchasedLicenseReader(session,manager,Clock.fixed(NOW,ZoneOffset.UTC),
                com.chengyinhub.business.marketplace.paid.read.PersistedPurchasedLicenseReader.Policy.OWNER_PURCHASED_VERSION_METADATA_V1);
    }
    @Test void purchasedMetadataDefaultOffAndUnpaidOrdersRemainEmpty(){
        com.chengyinhub.business.marketplace.paid.read.PersistedPurchasedLicenseReader off=new com.chengyinhub.business.marketplace.paid.read.PersistedPurchasedLicenseReader(session,manager,Clock.fixed(NOW,ZoneOffset.UTC));
        assertEquals(Integer.valueOf(503),assertThrows(com.chengyinhub.common.exception.ServiceException.class,()->off.list(7,null)).getCode());
        assertTrue(metadata().list(7,null).getItems().isEmpty());
    }
    @Test void purchasedMetadataProjectsExactDurableRightsButNoProviderOrProtectedBytes() throws Exception {
        assertTrue(service.accept(event()));com.chengyinhub.business.marketplace.paid.read.PurchasedLicenseMetadata value=metadata().detail(7,"w18-paid-11").getItem();
        assertEquals("PAID",value.getAcquisition());assertEquals("PERPETUAL_PURCHASED_VERSION",value.getUseDuration());assertEquals("version-1",value.getPurchasedVersionId());
        assertEquals(snapshot.getOffer().getTerms().getTermsHash(),value.getTermsHash());assertEquals("PROHIBITED",value.getRedistribution());assertEquals(1,value.getThemeLimit().getMaximum());
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata().list(7,null));
        for(String hidden:Arrays.asList("synthetic_transaction_1","synthetic_app","synthetic_merchant","reviewed:terms-1","sourceJSON","sourceContent","termsDocument","buyerMemberId","priceMinor"))assertFalse(json.contains(hidden));
        assertTrue(json.contains("CHANNEL_APPROVAL_REQUIRED"));assertTrue(json.contains("PAID_INSTALL_AUTHORITY_UNAVAILABLE"));
    }
    @Test void purchasedMetadataOwnerIsolationAndRefusalOfFreeClaimIdentifiers(){
        assertTrue(service.accept(event()));assertTrue(metadata().list(8,null).getItems().isEmpty());
        assertEquals(Integer.valueOf(404),assertThrows(com.chengyinhub.common.exception.ServiceException.class,()->metadata().detail(8,"w18-paid-11")).getCode());
        assertEquals(Integer.valueOf(400),assertThrows(com.chengyinhub.common.exception.ServiceException.class,()->metadata().detail(7,"claim-1")).getCode());
    }
    @Test void purchasedMetadataDisplaysRefundHoldAndRevocationWithoutConvertingToExpiry(){
        assertTrue(service.accept(event()));jdbc.update("update oms_order set aftersale_status=3");assertEquals("SUSPENDED",metadata().detail(7,"w18-paid-11").getItem().getStatus());
        jdbc.update("update w18_paid_license set state='REVOKED'");assertEquals("REVOKED",metadata().detail(7,"w18-paid-11").getItem().getStatus());
        assertEquals("PERPETUAL_PURCHASED_VERSION",metadata().detail(7,"w18-paid-11").getItem().getUseDuration());
    }
    @Test void purchasedMetadataRealDatabasePaginationHasNoOverlap(){
        assertTrue(service.accept(event()));for(int i=2;i<=52;i++){String number=String.format(Locale.ROOT,"W18_synthetic_order_%04d",i);seed(i,i+100,number);assertTrue(service.accept(event(number,"synthetic_transaction_"+i,"synthetic_app","synthetic_merchant",1000,NOW)));}
        com.chengyinhub.business.marketplace.paid.read.PurchasedLicenseMetadata.Page first=metadata().list(7,null);assertEquals(50,first.getItems().size());assertTrue(first.getHasMore());
        com.chengyinhub.business.marketplace.paid.read.PurchasedLicenseMetadata.Page last=metadata().list(7,first.getNextBeforeOrderLineId());assertEquals(2,last.getItems().size());assertFalse(last.getHasMore());assertNull(last.getNextBeforeOrderLineId());
        Set<String> all=new HashSet<String>();for(com.chengyinhub.business.marketplace.paid.read.PurchasedLicenseMetadata item:first.getItems())assertTrue(all.add(item.getLicenseId()));for(com.chengyinhub.business.marketplace.paid.read.PurchasedLicenseMetadata item:last.getItems())assertTrue(all.add(item.getLicenseId()));assertEquals(52,all.size());
    }
    private com.chengyinhub.web.controller.api.ApiWorkshopPurchasedController controller(){
        com.chengyinhub.web.controller.api.ApiWorkshopPurchasedController controller=new com.chengyinhub.web.controller.api.ApiWorkshopPurchasedController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"reader",metadata());
        com.chengyinhub.common.core.domain.entity.AppUser app=new com.chengyinhub.common.core.domain.entity.AppUser();app.setUserId(7L);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(new com.chengyinhub.common.core.domain.model.LoginAppUser(app),null,Collections.emptyList()));return controller;
    }
    @AfterEach void clearHttpIdentity(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    private org.springframework.mock.web.MockHttpServletRequest form(String value){
        org.springframework.mock.web.MockHttpServletRequest request=new org.springframework.mock.web.MockHttpServletRequest(){@Override public Map<String,String[]> getParameterMap(){throw new AssertionError("no unbounded servlet parsing");}};
        request.setContentType("application/x-www-form-urlencoded; charset=utf-8");request.setContent(value.getBytes(StandardCharsets.UTF_8));return request;
    }
    @Test void purchasedActualHttpControllerReadsOnlyAuthenticatedOwnerFromRealDatabase() throws Exception {
        assertTrue(service.accept(event()));com.chengyinhub.web.controller.api.ApiWorkshopPurchasedController controller=controller();
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        org.springframework.test.web.servlet.MvcResult list=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workshop/purchased/list").contentType("application/x-www-form-urlencoded; charset=utf-8").content("")).andReturn();
        assertEquals(200,list.getResponse().getStatus());assertEquals("no-store",list.getResponse().getHeader("Cache-Control"));assertTrue(list.getResponse().getContentAsString().contains("PERPETUAL_PURCHASED_VERSION"));
        assertEquals(200,controller.detail(form("license_id=w18-paid-11")).getStatusCodeValue());
        assertEquals(405,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/workshop/purchased/list")).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workshop/purchased/buy")).andReturn().getResponse().getStatus());
    }
    @Test void purchasedHttpUnknownLengthOversizedAndAmbiguousBodyFailClosed(){
        com.chengyinhub.web.controller.api.ApiWorkshopPurchasedController controller=controller();
        org.springframework.mock.web.MockHttpServletRequest unknown=new org.springframework.mock.web.MockHttpServletRequest(){@Override public long getContentLengthLong(){return -1;}@Override public jakarta.servlet.ServletInputStream getInputStream(){throw new AssertionError("unknown body must be rejected before reading");}};
        unknown.setContentType("application/x-www-form-urlencoded");assertEquals(400,controller.list(unknown).getStatusCodeValue());assertEquals(400,controller.detail(unknown).getStatusCodeValue());
        for(String value:Arrays.asList("owner=7","license_id=w18-paid-11&owner=7","license_id=%77%31%38-paid-11","license_id=w18-paid-11&license_id=w18-paid-11",new String(new char[513]).replace('\0','x')))
            assertEquals(400,controller.detail(form(value)).getStatusCodeValue());
        for(String value:Arrays.asList("before_order_line_id=0","before_order_line_id=01","before_order_line_id=9223372036854775808"))assertEquals(400,controller.list(form(value)).getStatusCodeValue());
        assertEquals(0,count());
    }
    @Test void purchasedHttpDefaultAbsentAndAnonymousHaveNoAuthority(){
        com.chengyinhub.web.controller.api.ApiWorkshopPurchasedController controller=controller();org.springframework.test.util.ReflectionTestUtils.setField(controller,"reader",null);
        assertEquals(503,controller.list(form("")).getStatusCodeValue());org.springframework.security.core.context.SecurityContextHolder.clearContext();assertEquals(401,controller.list(form("")).getStatusCodeValue());
    }
    private DriverManagerDataSource otherDataSource(){DriverManagerDataSource other=new DriverManagerDataSource("jdbc:h2:mem:other_"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");other.setDriverClassName("org.h2.Driver");return other;}
    @Test void changedManagerRejectsBeforeOrderCanAutocommitOutsideLicenseTransaction(){
        manager.setDataSource(otherDataSource());probe.failInsert=true;
        assertFalse(service.accept(event()));none();assertThrows(IllegalArgumentException.class,()->service.readOwned(7,"w18-paid-11"));
    }
    @Test void changedSessionEnvironmentRejectsExistingServiceAndNewReader(){
        session.getSqlSessionFactory().getConfiguration().setEnvironment(new Environment("changed",new SpringManagedTransactionFactory(),otherDataSource()));
        assertFalse(service.accept(event()));none();assertThrows(IllegalArgumentException.class,()->metadata().list(7,null));
    }
    @Test void nonSpringTransactionFactoryIsRejectedAtConstruction(){
        session.getSqlSessionFactory().getConfiguration().setEnvironment(new Environment("not-spring",new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),connectionProbe));
        assertThrows(IllegalArgumentException.class,()->enabled(Clock.fixed(NOW,ZoneOffset.UTC)));assertFalse(service.accept(event()));none();
    }
    @Test void managerSwitchAfterValidationInsideConnectionAcquisitionCannotBreakAtomicity(){
        connectionProbe.onConnect=()->manager.setDataSource(otherDataSource());
        assertFalse(service.accept(event()));none();assertEquals(0,probe.orderWrites);
    }
    @Test void environmentSwitchAfterValidationInsideConnectionAcquisitionCannotBreakAtomicity(){
        connectionProbe.onConnect=()->session.getSqlSessionFactory().getConfiguration().setEnvironment(new Environment("during-begin",new SpringManagedTransactionFactory(),otherDataSource()));
        assertFalse(service.accept(event()));none();assertEquals(0,probe.orderWrites);
    }
    @Test void environmentSwitchAfterPaidUpdateRollsBackTheCapturedOriginalConnection(){
        probe.afterUpdate=id->{if(id.endsWith("markPaid"))session.getSqlSessionFactory().getConfiguration().setEnvironment(new Environment("after-paid",new SpringManagedTransactionFactory(),otherDataSource()));};
        assertFalse(service.accept(event()));none();assertEquals(1,probe.orderWrites);
    }
    @Test void managerSwitchAfterPaidUpdateCannotRedirectRollbackOrAutocommit(){
        probe.afterUpdate=id->{if(id.endsWith("markPaid"))manager.setDataSource(otherDataSource());};
        assertFalse(service.accept(event()));none();assertEquals(1,probe.orderWrites);
    }
    @Test void currentMetadataReaderRejectsConfigurationSwitchBeforeIndexOrDetailQuery(){
        assertTrue(service.accept(event()));com.chengyinhub.business.marketplace.paid.read.PersistedPurchasedLicenseReader current=metadata();
        manager.setDataSource(otherDataSource());assertThrows(IllegalArgumentException.class,()->current.list(7,null));assertThrows(IllegalArgumentException.class,()->current.detail(7,"w18-paid-11"));
        assertEquals(1,count());
    }
    @Test void metadataReadSwitchBetweenValidationAndTransactionBeginFailsClosed(){
        assertTrue(service.accept(event()));com.chengyinhub.business.marketplace.paid.read.PersistedPurchasedLicenseReader current=metadata();
        connectionProbe.onConnect=()->manager.setDataSource(otherDataSource());assertThrows(IllegalArgumentException.class,()->current.list(7,null));assertEquals(1,count());
    }
    @Test void concurrentConfigurationSwitchAtCheckToBeginWindowIsRejectedBeforeQueries() throws Exception {
        Environment original=session.getSqlSessionFactory().getConfiguration().getEnvironment();
        for(boolean changeManager:new boolean[]{true,false}) {
            manager.setDataSource(connectionProbe);session.getSqlSessionFactory().getConfiguration().setEnvironment(original);
            CountDownLatch atConnection=new CountDownLatch(1),changed=new CountDownLatch(1);
            connectionProbe.onConnect=()->{atConnection.countDown();try{if(!changed.await(5,TimeUnit.SECONDS))throw new IllegalStateException("synthetic race timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}};
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> result=worker.submit(()->service.accept(event()));assertTrue(atConnection.await(5,TimeUnit.SECONDS));
                if(changeManager)manager.setDataSource(otherDataSource());
                else session.getSqlSessionFactory().getConfiguration().setEnvironment(new Environment("concurrent",new SpringManagedTransactionFactory(),otherDataSource()));
                changed.countDown();assertFalse(result.get(5,TimeUnit.SECONDS));none();assertEquals(0,probe.orderWrites);
            } finally {changed.countDown();worker.shutdownNow();}
        }
    }
    @Test void changeAfterLastConfigurationCheckStillCannotRedirectTheFixedCommit(){
        probe.afterUpdate=id->{if(id.endsWith("insertLicense"))org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){manager.setDataSource(otherDataSource());}});};
        // The later synchronization runs AFTER the boundary's check. Unlike an equals-only guard,
        // the private manager still commits BOTH rows on the original captured connection.
        assertTrue(service.accept(event()));assertEquals(1,count());assertEquals(Long.valueOf(1),jdbc.queryForObject("select status from oms_order where id=1",Long.class));
        assertThrows(IllegalArgumentException.class,()->service.readOwned(7,"w18-paid-11"));
    }
    private com.chengyinhub.business.marketplace.paid.PaidInstallationAuthority installAuthority;
    private com.chengyinhub.business.marketplace.reservation.ModuleInstallReservationService reserve;
    private com.chengyinhub.business.marketplace.reservation.ModuleTextDraftInstallationService installer;
    private final java.util.concurrent.atomic.AtomicReference<Instant> installTime=new java.util.concurrent.atomic.AtomicReference<Instant>(NOW);
    private final Clock installClock=new Clock(){public Instant instant(){return installTime.get();}public java.time.ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId zone){return this;}};
    private void installSetup() throws Exception {
        for(String name:Arrays.asList("content_draft_im_poll_20260828","w18_module_install_reservation_20261002","w18_owned_text_draft_install_20261002","w18_content_draft_install_scope_20261003")){
            String sql=new String(Files.readAllBytes(Paths.get("sql/migration_"+name+".sql")),StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","")
                    .replace(" CHARACTER SET ascii COLLATE ascii_bin","").replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4(?: COLLATE=utf8mb4_bin)?(?: COMMENT='[^']*')?","");
            for(String part:sql.split(";"))if(!part.trim().isEmpty()&&!part.trim().startsWith("SELECT"))jdbc.execute(part);
        }
        for(long target:Arrays.asList(91L,92L))jdbc.update("insert into content_draft(id,owner_member_id,business_type,client_draft_key,payload_json,payload_hash,status,version,updated_by_device) values(?,7,'TOPIC',?,'{}',?,'DRAFT',1,'synthetic')",target,"target-"+target,PaidPurchaseSnapshot.sha("{}".getBytes(StandardCharsets.UTF_8)));
        installAuthority=new com.chengyinhub.business.marketplace.paid.PaidInstallationAuthority(session,manager,installClock,com.chengyinhub.business.marketplace.paid.PaidInstallationAuthority.Policy.OWNER_PAID_TEXT_INSTALL_V1);
        reserve=installAuthority.reservationService();installer=installAuthority.installationService();
        installAuthority.prepareTarget(7,91,1,"synthetic-region");installAuthority.prepareTarget(7,92,1,"synthetic-region");
    }
    private ModuleInstallRequest installRequest(String request,long target){return new ModuleInstallRequest(new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,7),request,"w18-paid-11","module-1","version-1",snapshot.getVersion().getContentHash(),"offer-version-1",snapshot.getOffer().getTerms().getTermsHash(),target,1,false,Collections.emptySet(),Collections.emptyMap());}
    private Instant until(){return installTime.get().plusSeconds(600);}
    private int installedCount(){return jdbc.queryForObject("select count(*) from w18_module_owned_draft",Integer.class);}
    @Test void realSignedPaymentThenReserveAndConsumeCreatesPrivatePaidDraftWithFrozenPerpetualV2Rights() throws Exception {
        installSetup();assertTrue(WorkshopPaidNotificationRoute.dispatch(sign("SUCCESS","CNY","synthetic_app","synthetic_merchant",1000).parse(),service));
        ModuleInstallRequest request=installRequest("paid-install",91);reserve.reserve(7,request,until());String payload=jdbc.queryForObject("select payload_json from content_draft where id=91",String.class);
        com.chengyinhub.business.marketplace.reservation.ModuleInstallationReceipt receipt=installer.consume(7,request,until());
        assertEquals(1,installedCount());assertEquals("DRAFT",receipt.getState());assertFalse(receipt.isPublished());assertFalse(receipt.isExecutable());assertFalse(receipt.grantsPlayerAdmission());
        assertEquals(payload,jdbc.queryForObject("select payload_json from content_draft where id=91",String.class));assertEquals(Long.valueOf(1),jdbc.queryForObject("select version from content_draft where id=91",Long.class));
        Map<String,Object> row=jdbc.queryForMap("select * from w18_module_owned_draft where id=?",receipt.getOwnedDraftId());assertEquals("DRAFT",row.get("state"));
        assertEquals(0,((Number)row.get("review_status")).intValue());assertEquals(0,((Number)row.get("publish_status")).intValue());assertEquals(0,((Number)row.get("is_sync")).intValue());
        assertArrayEquals(snapshot.getContent(),jdbc.queryForObject("select source_content from w18_module_owned_draft",byte[].class));assertArrayEquals(snapshot.getTerms(),jdbc.queryForObject("select terms_document from w18_module_owned_draft",byte[].class));
        com.fasterxml.jackson.databind.JsonNode rights=new com.fasterxml.jackson.databind.ObjectMapper().readTree(jdbc.queryForObject("select frozen_rights from w18_module_owned_draft",String.class));
        assertEquals("w18-frozen-install-rights-v2",rights.path("schema").asText());assertEquals("PERPETUAL_PURCHASED_VERSION",rights.path("useDuration").asText());assertTrue(rights.path("validUntil").isNull());
        assertEquals("PAID",rights.path("acquisition").asText());assertEquals("VERIFIED_PAID_ORDER_LINE",rights.path("evidenceKind").asText());assertEquals("oms_order_item:11",rights.path("evidenceReference").asText());
        assertEquals(snapshot.getOffer().getTerms().getTermsHash(),rights.path("termsHash").asText());assertEquals("PROHIBITED",rights.path("redistribution").asText());
        assertEquals(Long.valueOf(8),jdbc.queryForObject("select seller_id from w18_module_install_reservation",Long.class));assertEquals("wechat-success:synthetic_transaction_1",jdbc.queryForObject("select source_event_id from w18_module_install_reservation",String.class));
        assertEquals("CONSUMED",jdbc.queryForObject("select state from w18_module_install_reservation",String.class));assertEquals(Integer.valueOf(1),jdbc.queryForObject("select count(*) from w18_module_install_usage",Integer.class));
    }
    @Test void paidInstallNeverUsesAFreeClaimOrAStatusOnlyOrder() throws Exception {
        installSetup();assertThrows(RuntimeException.class,()->reserve.reserve(7,installRequest("unpaid",91),until()));assertEquals(0,installedCount());
        // No w18_free_claim/publication tables exist in this fixture, even on the successful paid path.
        assertEquals(Integer.valueOf(0),jdbc.queryForObject("select count(*) from information_schema.tables where table_name in ('w18_free_claim','w18_free_publication')",Integer.class));
        assertTrue(service.accept(event()));ModuleInstallRequest request=installRequest("paid-no-free",91);reserve.reserve(7,request,until());installer.consume(7,request,until());assertEquals(1,installedCount());
    }
    @Test void paidInstallDefaultOffAndDetachedGrantLookupCannotAuthorize() throws Exception {
        installSetup();com.chengyinhub.business.marketplace.paid.PaidInstallationAuthority off=new com.chengyinhub.business.marketplace.paid.PaidInstallationAuthority(session,manager,installClock);
        assertThrows(IllegalStateException.class,off::reservationService);assertThrows(IllegalStateException.class,off::installationService);
        assertThrows(IllegalArgumentException.class,()->installAuthority.findVerifiedGrant("w18-paid-11"));assertEquals(0,installedCount());
    }
    @Test void paidInstalledReceiptRetryIsHistoricalAndDoesNotCreateMoreUsage() throws Exception {
        installSetup();assertTrue(service.accept(event()));ModuleInstallRequest request=installRequest("retry",91);reserve.reserve(7,request,until());
        long first=installer.consume(7,request,until()).getOwnedDraftId();jdbc.update("update w18_paid_license set state='REVOKED'");
        assertEquals(first,installer.consume(7,request,until()).getOwnedDraftId());assertEquals(1,installedCount());assertEquals(Integer.valueOf(1),jdbc.queryForObject("select count(*) from w18_module_install_usage",Integer.class));
    }
    @Test void refundOrRevocationBetweenHoldAndConsumeStopsNewInstallationAndAllowsHoldRelease() throws Exception {
        installSetup();assertTrue(service.accept(event()));ModuleInstallRequest request=installRequest("revoked-hold",91);reserve.reserve(7,request,until());jdbc.update("update oms_order set aftersale_status=4");
        assertThrows(RuntimeException.class,()->installer.consume(7,request,until()));assertEquals(0,installedCount());
        reserve.release(7,request.getBuyer(),request.getRequestId(),com.chengyinhub.business.marketplace.reservation.ModuleInstallReservationService.Release.CANCELLED);
        assertEquals("CANCELLED",jdbc.queryForObject("select state from w18_module_install_reservation",String.class));assertEquals(1,count());
    }
    @Test void wrongOwnerStaleTargetAndScopeHashCannotInstallPaidContent() throws Exception {
        installSetup();assertTrue(service.accept(event()));ModuleInstallRequest request=installRequest("wrong-owner",91);
        assertThrows(RuntimeException.class,()->reserve.reserve(8,request,until()));
        jdbc.update("update content_draft set owner_member_id=8 where id=91");assertThrows(RuntimeException.class,()->reserve.reserve(7,request,until()));
        jdbc.update("update content_draft set owner_member_id=7,version=2 where id=91");assertThrows(RuntimeException.class,()->reserve.reserve(7,request,until()));
        jdbc.update("update content_draft set version=1,payload_hash=? where id=91",String.join("",Collections.nCopies(64,"c")));assertThrows(RuntimeException.class,()->reserve.reserve(7,request,until()));assertEquals(0,installedCount());
    }
    @Test void changedTargetAfterReservationCannotConsumeFrozenScope() throws Exception {
        installSetup();assertTrue(service.accept(event()));ModuleInstallRequest request=installRequest("changed-target",91);reserve.reserve(7,request,until());jdbc.update("update content_draft set version=2 where id=91");
        assertThrows(RuntimeException.class,()->installer.consume(7,request,until()));assertEquals(0,installedCount());assertEquals("RESERVED",jdbc.queryForObject("select state from w18_module_install_reservation",String.class));
    }
    @Test void paidConcurrentThemeQuotaCannotBeExceededAcrossTargets() throws Exception {
        installSetup();assertTrue(service.accept(event()));ExecutorService workers=Executors.newFixedThreadPool(2);
        try{List<Future<Boolean>> futures=new ArrayList<Future<Boolean>>();for(long target:new long[]{91,92})futures.add(workers.submit(()->{try{reserve.reserve(7,installRequest("quota-"+target,target),until());return true;}catch(RuntimeException denied){return false;}}));int accepted=0;for(Future<Boolean> f:futures)if(f.get(10,TimeUnit.SECONDS))accepted++;assertEquals(1,accepted);assertEquals(Integer.valueOf(1),jdbc.queryForObject("select count(*) from w18_module_install_reservation where state='RESERVED'",Integer.class));}finally{workers.shutdownNow();}
    }
    @Test void cancelledHoldReleasesQuotaButConsumedHistoryStillCounts() throws Exception {
        installSetup();assertTrue(service.accept(event()));ModuleInstallRequest one=installRequest("cancelled",91);reserve.reserve(7,one,until());reserve.release(7,one.getBuyer(),one.getRequestId(),com.chengyinhub.business.marketplace.reservation.ModuleInstallReservationService.Release.CANCELLED);
        ModuleInstallRequest two=installRequest("consumed",92);reserve.reserve(7,two,until());installer.consume(7,two,until());
        assertThrows(RuntimeException.class,()->reserve.reserve(7,installRequest("cannot-reset",91),until()));assertEquals(1,installedCount());
    }
    @Test void componentFailureRollsBackDraftComponentUsageAndConsume() throws Exception {
        installSetup();assertTrue(service.accept(event()));ModuleInstallRequest request=installRequest("rollback-install",91);reserve.reserve(7,request,until());probe.failComponent=true;
        assertThrows(RuntimeException.class,()->installer.consume(7,request,until()));assertEquals(0,installedCount());assertEquals(Integer.valueOf(0),jdbc.queryForObject("select count(*) from w18_module_install_usage",Integer.class));assertEquals("RESERVED",jdbc.queryForObject("select state from w18_module_install_reservation",String.class));
    }
    @Test void externalManagerChangeDuringInstallCannotAutocommitPartialDraft() throws Exception {
        installSetup();assertTrue(service.accept(event()));ModuleInstallRequest request=installRequest("manager-change",91);reserve.reserve(7,request,until());
        probe.afterUpdate=id->{if(id.endsWith("insertOwnedDraft"))manager.setDataSource(otherDataSource());};
        assertThrows(RuntimeException.class,()->installer.consume(7,request,until()));assertEquals(0,installedCount());assertEquals(Integer.valueOf(0),jdbc.queryForObject("select count(*) from w18_module_install_usage",Integer.class));assertEquals("RESERVED",jdbc.queryForObject("select state from w18_module_install_reservation",String.class));
    }
    @Test void perpetualPurchasedVersionInstallsAfterElapsedYearsWithoutSubscriptionLookup() throws Exception {
        installSetup();assertTrue(service.accept(event()));installTime.set(NOW.plusSeconds(315360000));ModuleInstallRequest request=installRequest("after-years",91);reserve.reserve(7,request,until());installer.consume(7,request,until());assertEquals(1,installedCount());assertNull(service.readOwned(7,"w18-paid-11").getValidUntil());
    }
    @Test void installationSourceVersionOrTermsTamperingCannotBorrowAnotherGrant() throws Exception {
        installSetup();assertTrue(service.accept(event()));ModuleInstallRequest wrong=new ModuleInstallRequest(new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,7),"wrong-version","w18-paid-11","module-1","other-version",snapshot.getVersion().getContentHash(),"offer-version-1",snapshot.getOffer().getTerms().getTermsHash(),91,1,false,Collections.emptySet(),Collections.emptyMap());
        assertThrows(RuntimeException.class,()->reserve.reserve(7,wrong,until()));assertEquals(0,installedCount());
    }
    @Test void targetRegionIsExplicitAndNotInferredFromTopicOrActivity() throws Exception {
        installSetup();assertTrue(service.accept(event()));jdbc.update("delete from w18_content_draft_install_scope where draft_id=91");
        assertThrows(RuntimeException.class,()->reserve.reserve(7,installRequest("no-region",91),until()));
        installAuthority.prepareTarget(7,91,1,"not-licensed-region");assertThrows(RuntimeException.class,()->reserve.reserve(7,installRequest("wrong-region",91),until()));assertEquals(0,installedCount());
    }
    @Test void expiredHoldDoesNotExpirePurchasedLicenseOrConsumeDraft() throws Exception {
        installSetup();assertTrue(service.accept(event()));ModuleInstallRequest request=installRequest("expired-hold",91);Instant originalDeadline=until();reserve.reserve(7,request,originalDeadline);installTime.set(originalDeadline.plusSeconds(1));
        assertEquals(com.chengyinhub.business.marketplace.reservation.ModuleReservationException.Reason.RESERVATION_EXPIRED,
            assertThrows(com.chengyinhub.business.marketplace.reservation.ModuleReservationException.class,()->installer.consume(7,request,originalDeadline)).getReason());
        assertEquals(0,installedCount());assertNull(service.readOwned(7,"w18-paid-11").getValidUntil());
        ModuleInstallRequest fresh=installRequest("fresh-hold",91);reserve.reserve(7,fresh,until());installer.consume(7,fresh,until());assertEquals(1,installedCount());
    }
    @Test void commercialTranslationRedistributionAndBindingsNeverExceedFrozenPaidRights() throws Exception {
        installSetup();assertTrue(service.accept(event()));
        for(ModuleInstallRequest request:Arrays.asList(
            new ModuleInstallRequest(new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,7),"commercial","w18-paid-11","module-1","version-1",snapshot.getVersion().getContentHash(),"offer-version-1",snapshot.getOffer().getTerms().getTermsHash(),91,1,true,Collections.emptySet(),Collections.emptyMap()),
            new ModuleInstallRequest(new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,7),"translation","w18-paid-11","module-1","version-1",snapshot.getVersion().getContentHash(),"offer-version-1",snapshot.getOffer().getTerms().getTermsHash(),91,1,false,Collections.singleton(ModuleInstallRequest.Change.TRANSLATE),Collections.emptyMap()),
            new ModuleInstallRequest(new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,7),"redistribute","w18-paid-11","module-1","version-1",snapshot.getVersion().getContentHash(),"offer-version-1",snapshot.getOffer().getTerms().getTermsHash(),91,1,false,Collections.singleton(ModuleInstallRequest.Change.REDISTRIBUTE),Collections.emptyMap()),
            new ModuleInstallRequest(new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,7),"binding","w18-paid-11","module-1","version-1",snapshot.getVersion().getContentHash(),"offer-version-1",snapshot.getOffer().getTerms().getTermsHash(),91,1,false,Collections.emptySet(),Collections.singletonMap("site",new ModuleInstallRequest.ResourceRef(ModuleVersionSnapshot.BindingKind.LOCATION,99)))))
            assertThrows(RuntimeException.class,()->reserve.reserve(7,request,until()));
        assertEquals(0,installedCount());assertEquals(Integer.valueOf(0),jdbc.queryForObject("select count(*) from w18_module_install_reservation",Integer.class));
    }
    @Test void unsupportedButHashConsistentPaidPackageCannotEnterTextAdapter() throws Exception {
        installSetup();byte[] source="{\"schema\":\"w18-member-text-v1\",\"title\":\"Unsupported test\",\"bindings\":{},\"rewardRulesJson\":\"not-authorized\"}".getBytes(StandardCharsets.UTF_8);
        ModuleVersionSnapshot old=snapshot.getVersion();ModuleVersionSnapshot changed=new ModuleVersionSnapshot(old.getCreator(),old.getModuleId(),old.getVersionId(),old.getVersion(),old.getSchemaVersion(),old.getSourceKind(),old.getSourceId(),old.getSourceRevision(),source,PaidPurchaseSnapshot.sha(source),old.getApprovalReference(),old.getRequiredBindings());
        snapshot=new PaidPurchaseSnapshot(changed,snapshot.getOffer(),source,snapshot.getTerms(),snapshot.getReviewedOfferReference());String json=snapshot.encode();jdbc.update("update w18_paid_order_line_snapshot set snapshot_json=?,snapshot_hash=?,source_content=?",json.getBytes(StandardCharsets.UTF_8),PaidPurchaseSnapshot.sha(json.getBytes(StandardCharsets.UTF_8)),source);
        assertTrue(service.accept(event()));assertThrows(RuntimeException.class,()->reserve.reserve(7,installRequest("unsupported-package",91),until()));assertEquals(0,installedCount());assertEquals(1,count());
        assertArrayEquals(source,jdbc.queryForObject("select source_content from w18_paid_order_line_snapshot",byte[].class));
    }
    private static final class ConnectionProbe extends org.springframework.jdbc.datasource.AbstractDataSource {
        private final javax.sql.DataSource delegate;volatile Runnable onConnect=()->{};
        ConnectionProbe(javax.sql.DataSource delegate){this.delegate=delegate;}
        public java.sql.Connection getConnection()throws java.sql.SQLException{Runnable hook=onConnect;onConnect=()->{};hook.run();return delegate.getConnection();}
        public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{Runnable hook=onConnect;onConnect=()->{};hook.run();return delegate.getConnection(user,password);}
    }
    @Intercepts(@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}))
    private static final class Probe implements Interceptor {
        boolean failInsert,failComponent;int orderWrites;java.util.function.Consumer<String> afterUpdate=id->{};
        public Object intercept(Invocation call)throws Throwable{if(failComponent && ((MappedStatement)call.getArgs()[0]).getId().endsWith("insertTextComponent"))throw new IllegalStateException("synthetic component storage failure");if(failInsert && ((MappedStatement)call.getArgs()[0]).getId().endsWith("insertLicense"))throw new IllegalStateException("synthetic storage failure");Object result=call.proceed();String id=((MappedStatement)call.getArgs()[0]).getId();if(id.endsWith("markPaid"))orderWrites++;afterUpdate.accept(id);return result;}
        public Object plugin(Object target){return Plugin.wrap(target,this);}public void setProperties(Properties properties){}
    }
    private static final String COMMAND_ID="00000000-0000-4000-8000-000000000001";
    private PaidInstallCommandService commandService;
    private void commandSetup() throws Exception {
        installSetup();String sql=new String(Files.readAllBytes(Paths.get("sql/migration_w18_paid_install_command_20261005.sql")),StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$","").replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","");
        for(String part:sql.split(";"))if(!part.trim().isEmpty())jdbc.execute(part);
        commandService=new PaidInstallCommandService(session,manager,installClock,PaidInstallCommandService.Policy.OWNER_CONFIRMED_PAID_PRIVATE_TEXT_V1,Duration.ofMinutes(5));
        assertTrue(service.accept(event()));
    }
    private PaidInstallCommand command(){return command(COMMAND_ID,91,"TOPIC",PaidPurchaseSnapshot.sha("{}".getBytes(StandardCharsets.UTF_8)),"synthetic-region",false);}
    private PaidInstallCommand command(String id,long target,String business,String targetHash,String region,boolean commercial){
        return new PaidInstallCommand(id,"w18-paid-11","module-1","version-1",snapshot.getVersion().getContentHash(),snapshot.getOffer().getTerms().getTermsHash(),target,1,business,targetHash,region,commercial);
    }
    @Test void paidCommandTargetsAreActualOwnerDraftsWithoutModeOrEditorInference() throws Exception {
        commandSetup();jdbc.update("update content_draft set owner_member_id=8 where id=92");
        Map<String,Object> result=commandService.targets(7,"w18-paid-11",null);List<?> rows=(List<?>)result.get("items");assertEquals(1,rows.size());Map<?,?> row=(Map<?,?>)rows.get(0);
        assertEquals(91L,row.get("targetDraftId"));assertEquals("TOPIC",row.get("businessType"));assertEquals("UNRESOLVED",row.get("mode"));assertEquals("W18_PRIVATE_COMPONENT_ONLY",row.get("destinationKind"));
        assertFalse(row.containsKey("payloadJson"));assertFalse(row.containsKey("templateId"));assertEquals(0,installedCount());assertEquals(0,jdbc.queryForObject("select count(*) from w18_paid_install_command",Integer.class));
        assertThrows(RuntimeException.class,()->commandService.targets(8,"w18-paid-11",null));
    }
    @Test void actualCommandSubmitInstallsExactlyOnceAndFreshStatusRecoversLostResponse() throws Exception {
        commandSetup();String originalPayload=jdbc.queryForObject("select payload_json from content_draft where id=91",String.class);Map<String,Object> submitted=commandService.submit(7,command());assertEquals("INSTALLED_PRIVATE_DRAFT",submitted.get("state"));assertEquals(false,submitted.get("published"));assertEquals(false,submitted.get("executable"));assertEquals("MATERIALIZATION_REQUIRED",submitted.get("professionalTemplateStatus"));
        PaidInstallCommandService fresh=new PaidInstallCommandService(session,manager,installClock,PaidInstallCommandService.Policy.OWNER_CONFIRMED_PAID_PRIVATE_TEXT_V1,Duration.ofSeconds(10));
        assertEquals(submitted,fresh.status(7,COMMAND_ID));assertEquals(submitted,fresh.submit(7,command()));assertEquals(1,installedCount());assertEquals(1,jdbc.queryForObject("select count(*) from w18_paid_install_command",Integer.class));
        assertEquals(NOW.plusSeconds(300).toString(),submitted.get("holdDeadline"));assertEquals("NOT_FOUND",fresh.status(8,COMMAND_ID).get("state"));
        assertEquals(originalPayload,jdbc.queryForObject("select payload_json from content_draft where id=91",String.class));
    }
    @Test void sameUuidChangedConfirmationNeverCreatesSecondInstallOrRebindsTarget() throws Exception {
        commandSetup();commandService.submit(7,command());
        for(PaidInstallCommand changed:Arrays.asList(command(COMMAND_ID,92,"TOPIC",command().targetPayloadHash,"synthetic-region",false),command(COMMAND_ID,91,"ACTIVITY",command().targetPayloadHash,"synthetic-region",false),command(COMMAND_ID,91,"TOPIC",command().targetPayloadHash,"other-region",false),command(COMMAND_ID,91,"TOPIC",command().targetPayloadHash,"synthetic-region",true)))assertThrows(IllegalArgumentException.class,()->commandService.submit(7,changed));
        assertEquals(1,installedCount());assertEquals(91L,commandService.status(7,COMMAND_ID).get("targetDraftId"));
    }
    @Test void commandChecksPayloadHashAndBusinessTypeInAdditionToRevision() throws Exception {
        commandSetup();jdbc.update("update content_draft set payload_hash=? where id=91",PaidPurchaseSnapshot.sha("changed".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->commandService.submit(7,command()));assertEquals("NOT_FOUND",commandService.status(7,COMMAND_ID).get("state"));
        jdbc.update("update content_draft set payload_hash=?,business_type='ACTIVITY' where id=91",command().targetPayloadHash);
        assertThrows(IllegalArgumentException.class,()->commandService.submit(7,command()));assertEquals(0,installedCount());
    }
    @Test void commandAuthorityRechecksHashAfterHoldBeforeConsumeWithoutRevisionBump() throws Exception {
        commandSetup();probe.afterUpdate=id->{if(id.endsWith("insertReservation"))jdbc.update("update content_draft set payload_hash=? where id=91",PaidPurchaseSnapshot.sha("raced".getBytes(StandardCharsets.UTF_8)));};
        assertThrows(RuntimeException.class,()->commandService.submit(7,command()));assertEquals(0,installedCount());assertEquals("RESERVED",commandService.status(7,COMMAND_ID).get("state"));
    }
    @Test void commandRefundBetweenHoldAndConsumePreventsCopy() throws Exception {
        commandSetup();probe.afterUpdate=id->{if(id.endsWith("insertReservation"))jdbc.update("update oms_order set aftersale_status=4 where id=1");};
        assertThrows(RuntimeException.class,()->commandService.submit(7,command()));assertEquals(0,installedCount());assertEquals("RESERVED",commandService.status(7,COMMAND_ID).get("state"));
    }
    @Test void commandRetryAfterStorageFailureUsesOriginalHoldAndOneCopy() throws Exception {
        commandSetup();probe.failComponent=true;assertThrows(RuntimeException.class,()->commandService.submit(7,command()));assertEquals(0,installedCount());Map<String,Object> pending=commandService.status(7,COMMAND_ID);assertEquals("RESERVED",pending.get("state"));
        probe.failComponent=false;installTime.set(NOW.plusSeconds(100));Map<String,Object> done=commandService.submit(7,command());assertEquals("INSTALLED_PRIVATE_DRAFT",done.get("state"));assertEquals(pending.get("holdDeadline"),done.get("holdDeadline"));assertEquals(1,installedCount());
    }
    @Test void commandExpiredHoldDoesNotRenewOrExpirePermanentLicense() throws Exception {
        commandSetup();probe.failComponent=true;assertThrows(RuntimeException.class,()->commandService.submit(7,command()));probe.failComponent=false;installTime.set(NOW.plusSeconds(301));
        assertEquals("EXPIRED",commandService.status(7,COMMAND_ID).get("state"));assertThrows(RuntimeException.class,()->commandService.submit(7,command()));assertEquals(0,installedCount());assertNull(service.readOwned(7,"w18-paid-11").getValidUntil());
        PaidInstallCommand fresh=command("00000000-0000-4000-8000-000000000002",91,"TOPIC",command().targetPayloadHash,"synthetic-region",false);
        assertEquals("INSTALLED_PRIVATE_DRAFT",commandService.submit(7,fresh).get("state"));assertEquals(1,installedCount());
    }
    @Test void commandHistoryRemainsMetadataAfterRefundTargetEditAndLeaseExpiry() throws Exception {
        commandSetup();Map<String,Object> initial=commandService.submit(7,command());jdbc.update("update oms_order set aftersale_status=4");jdbc.update("update content_draft set version=2,payload_hash=? where id=91",PaidPurchaseSnapshot.sha("edit".getBytes(StandardCharsets.UTF_8)));installTime.set(NOW.plusSeconds(1000));
        assertEquals(initial,commandService.status(7,COMMAND_ID));assertEquals(initial,commandService.submit(7,command()));assertEquals(1,installedCount());
        assertThrows(RuntimeException.class,()->commandService.targets(7,"w18-paid-11",null));
    }
    @Test void concurrentSameCommandReturnsOneCommittedOwnedDraft() throws Exception {
        commandSetup();ExecutorService workers=Executors.newFixedThreadPool(2);try{Future<Map<String,Object>>a=workers.submit(()->commandService.submit(7,command())),b=workers.submit(()->commandService.submit(7,command()));assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}finally{workers.shutdownNow();}
        assertEquals(1,installedCount());assertEquals(1,jdbc.queryForObject("select count(*) from w18_module_install_usage",Integer.class));
    }
    @Test void commandDefaultDisabledAndForeignOwnerNeverWrite() throws Exception {
        commandSetup();PaidInstallCommandService off=new PaidInstallCommandService(session,manager,installClock);assertThrows(RuntimeException.class,()->off.submit(7,command()));assertThrows(RuntimeException.class,()->off.targets(7,"w18-paid-11",null));assertThrows(RuntimeException.class,()->off.status(7,COMMAND_ID));
        assertThrows(RuntimeException.class,()->commandService.submit(8,command()));assertEquals(0,installedCount());assertEquals(0,jdbc.queryForObject("select count(*) from w18_paid_install_command",Integer.class));
    }
    @Test void commandConfigurationRaceCannotAutocommitCommandOrTargetScope() throws Exception {
        commandSetup();jdbc.update("delete from w18_content_draft_install_scope where draft_id=91");
        probe.afterUpdate=id->{if(id.endsWith("PaidInstallCommandStore.insert"))manager.setDataSource(otherDataSource());};
        assertThrows(RuntimeException.class,()->commandService.submit(7,command()));assertEquals(0,installedCount());assertEquals(0,jdbc.queryForObject("select count(*) from w18_paid_install_command",Integer.class));assertEquals(0,jdbc.queryForObject("select count(*) from w18_content_draft_install_scope where draft_id=91",Integer.class));
    }
    @Test void commandStatusCorruptHistoricalJoinFailsClosed() throws Exception {
        commandSetup();commandService.submit(7,command());jdbc.update("update w18_module_install_reservation set content_hash=?",PaidPurchaseSnapshot.sha("corrupt".getBytes(StandardCharsets.UTF_8)));
        assertThrows(RuntimeException.class,()->commandService.status(7,COMMAND_ID));assertEquals(1,installedCount());
    }
    private com.chengyinhub.web.controller.api.ApiWorkshopPaidInstallController commandController(){
        controller();com.chengyinhub.web.controller.api.ApiWorkshopPaidInstallController value=new com.chengyinhub.web.controller.api.ApiWorkshopPaidInstallController();org.springframework.test.util.ReflectionTestUtils.setField(value,"commands",commandService);return value;
    }
    private String commandJson() throws Exception {
        Map<String,Object> value=new LinkedHashMap<>();PaidInstallCommand c=command();value.put("schema",PaidInstallCommand.SCHEMA);value.put("requestId",c.requestId);value.put("licenseId",c.licenseId);value.put("moduleId",c.moduleId);value.put("purchasedVersionId",c.versionId);value.put("contentHash",c.contentHash);value.put("termsHash",c.termsHash);value.put("targetDraftId",c.targetDraftId);value.put("targetRevision",c.targetRevision);value.put("businessType",c.businessType);value.put("targetPayloadHash",c.targetPayloadHash);value.put("planningRegion",c.planningRegion);value.put("commercialUse",c.commercialUse);return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    }
    private org.springframework.mock.web.MockHttpServletRequest json(String body){org.springframework.mock.web.MockHttpServletRequest r=new org.springframework.mock.web.MockHttpServletRequest();r.setContentType("application/json;charset=UTF-8");r.setContent(body.getBytes(StandardCharsets.UTF_8));return r;}
    @Test void actualHttpTargetsSubmitAndRecoveryReachRealDatabaseWithNoStore() throws Exception {
        commandSetup();org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(commandController()).build();
        for(String route:Arrays.asList("targets","submit","status")){
            String body=route.equals("targets")?"{\"licenseId\":\"w18-paid-11\"}":route.equals("submit")?commandJson():"{\"requestId\":\""+COMMAND_ID+"\"}";
            org.springframework.mock.web.MockHttpServletResponse response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workshop/purchased/install/"+route).contentType("application/json").content(body)).andReturn().getResponse();
            assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertFalse(response.getContentAsString().contains("sourceContent"));assertFalse(response.getContentAsString().contains("synthetic_transaction"));
        }
        assertEquals(1,installedCount());assertEquals(405,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/workshop/purchased/install/submit")).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workshop/purchased/install/publish")).andReturn().getResponse().getStatus());
    }
    @Test void commandHttpRejectsUnknownLengthOversizeDuplicateNestedAndOwnerInjectionBeforeMutation() throws Exception {
        commandSetup();com.chengyinhub.web.controller.api.ApiWorkshopPaidInstallController c=commandController();String valid=commandJson();
        for(String body:Arrays.asList(valid.replace("\"schema\":","\"ownerId\":7,\"schema\":"),valid.replace("\"schema\":","\"schema\":\"duplicate\",\"schema\":"),valid.replace("\"commercialUse\":false","\"commercialUse\":{}"),valid.replace("\"targetDraftId\":91","\"targetDraftId\":91.0"),valid+"{}",new String(new char[8000]).replace('\0','[')))assertEquals(400,c.submit(json(body)).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest unknown=new org.springframework.mock.web.MockHttpServletRequest(){public long getContentLengthLong(){return -1;}};unknown.setContentType("application/json");unknown.setContent(valid.getBytes(StandardCharsets.UTF_8));assertEquals(400,c.submit(unknown).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest mismatch=new org.springframework.mock.web.MockHttpServletRequest(){public long getContentLengthLong(){return 2;}};mismatch.setContentType("application/json");mismatch.setContent(new byte[5000]);assertEquals(400,c.submit(mismatch).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest query=json(valid);query.setQueryString("owner=7");assertEquals(400,c.submit(query).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest transfer=json(valid);transfer.addHeader("Transfer-Encoding","chunked");assertEquals(400,c.submit(transfer).getStatusCodeValue());
        assertEquals(0,installedCount());assertEquals(0,jdbc.queryForObject("select count(*) from w18_paid_install_command",Integer.class));
    }
    @Test void commandHttpMissingServiceAnonymousAndUnknownOutcomeAreHonest() throws Exception {
        commandSetup();com.chengyinhub.web.controller.api.ApiWorkshopPaidInstallController c=commandController();org.springframework.test.util.ReflectionTestUtils.setField(c,"commands",null);assertEquals(503,c.submit(json(commandJson())).getStatusCodeValue());
        org.springframework.security.core.context.SecurityContextHolder.clearContext();assertEquals(401,c.submit(json(commandJson())).getStatusCodeValue());c=commandController();probe.failComponent=true;
        org.springframework.http.ResponseEntity<com.chengyinhub.common.core.domain.AjaxResult> response=c.submit(json(commandJson()));assertEquals(503,response.getStatusCodeValue());assertEquals("INSTALL_OUTCOME_NOT_CONFIRMED",response.getBody().get("errorCode"));assertEquals("RESERVED",commandService.status(7,COMMAND_ID).get("state"));
        probe.failComponent=false;assertEquals(200,c.submit(json(commandJson())).getStatusCodeValue());assertEquals(1,installedCount());
    }

    @Test void commandStatusRequiresActualCommittedUsageNotOnlyConsumedFlag() throws Exception {
        commandSetup();commandService.submit(7,command());jdbc.update("delete from w18_module_install_usage");assertThrows(RuntimeException.class,()->commandService.status(7,COMMAND_ID));assertEquals(1,installedCount());
    }
    @Test void commandHttpInvalidUtf8AndUnpairedSurrogateNeverCreateRows() throws Exception {
        commandSetup();com.chengyinhub.web.controller.api.ApiWorkshopPaidInstallController c=commandController();org.springframework.mock.web.MockHttpServletRequest bytes=json("{}");bytes.setContent(new byte[]{'{','"','x','"',':','"',(byte)0xc0,(byte)0xaf,'"','}'});assertEquals(400,c.submit(bytes).getStatusCodeValue());
        String malformed=commandJson().replace("synthetic-region","\\uD800");assertEquals(400,c.submit(json(malformed)).getStatusCodeValue());assertEquals(0,jdbc.queryForObject("select count(*) from w18_paid_install_command",Integer.class));
    }

    @Test void freshClientFindsPriorCommandWithoutRememberingUuidAndReadDoesNotResumeIt() throws Exception {
        commandSetup();probe.failComponent=true;assertThrows(RuntimeException.class,()->commandService.submit(7,command()));probe.failComponent=false;
        PaidInstallCommandService fresh=new PaidInstallCommandService(session,manager,installClock,PaidInstallCommandService.Policy.OWNER_CONFIRMED_PAID_PRIVATE_TEXT_V1,Duration.ofSeconds(15));
        List<?> rows=(List<?>)fresh.history(7,"w18-paid-11",null).get("items");assertEquals(1,rows.size());Map<?,?> prior=(Map<?,?>)rows.get(0);assertEquals(COMMAND_ID,prior.get("requestId"));assertEquals("RESERVED",prior.get("state"));assertEquals(0,installedCount());
        assertEquals(0,((List<?>)fresh.history(8,"w18-paid-11",null).get("items")).size());assertEquals(0,((List<?>)fresh.history(7,"w18-paid-99",null).get("items")).size());
        com.chengyinhub.web.controller.api.ApiWorkshopPaidInstallController c=commandController();assertEquals(200,c.history(json("{\"licenseId\":\"w18-paid-11\"}")).getStatusCodeValue());assertEquals(0,installedCount());
        assertEquals("INSTALLED_PRIVATE_DRAFT",fresh.submit(7,command()).get("state"));
    }
    @Test void invalidRegionAndCommercialChoiceCannotPoisonPersistentTargetScope() throws Exception {
        commandSetup();jdbc.update("delete from w18_content_draft_install_scope where draft_id=91");
        for(PaidInstallCommand bad:Arrays.asList(command(COMMAND_ID,91,"TOPIC",command().targetPayloadHash,"not-granted",false),command(COMMAND_ID,91,"TOPIC",command().targetPayloadHash,"synthetic-region",true)))assertThrows(RuntimeException.class,()->commandService.submit(7,bad));
        assertEquals(0,jdbc.queryForObject("select count(*) from w18_content_draft_install_scope where draft_id=91",Integer.class));assertEquals(0,jdbc.queryForObject("select count(*) from w18_paid_install_command",Integer.class));assertEquals("INSTALLED_PRIVATE_DRAFT",commandService.submit(7,command()).get("state"));
    }

    @Test void commandHistoryAndTargetsUseExclusiveBoundedOwnerPaging() throws Exception {
        commandSetup();for(long id=100;id<151;id++)jdbc.update("insert into content_draft(id,owner_member_id,business_type,client_draft_key,payload_json,payload_hash,status,version,updated_by_device) values(?,7,'TOPIC',?,'{}',?,'DRAFT',1,'synthetic')",id,"paging-"+id,command().targetPayloadHash);
        Map<String,Object> page=commandService.targets(7,"w18-paid-11",null);assertEquals(50,((List<?>)page.get("items")).size());assertEquals(true,page.get("hasMore"));Map<String,Object> next=commandService.targets(7,"w18-paid-11",(Long)page.get("nextBeforeDraftId"));assertEquals(3,((List<?>)next.get("items")).size());assertEquals(false,next.get("hasMore"));
        commandService.submit(7,command());Map<String,Object> history=commandService.history(7,"w18-paid-11",null);assertEquals(1,((List<?>)history.get("items")).size());assertEquals(false,history.get("hasMore"));String key=jdbc.queryForObject("select command_key from w18_paid_install_command",String.class);assertEquals(0,((List<?>)commandService.history(7,"w18-paid-11",key).get("items")).size());
    }
    @Test void commandStreamingParserRejectsNestedValueWithinByteBoundBeforeAnyWrite() throws Exception {
        commandSetup();String nested=new String(new char[500]).replace('\0','[')+"0"+new String(new char[500]).replace('\0',']');String body=commandJson().replace("\"commercialUse\":false","\"commercialUse\":"+nested);assertTrue(body.getBytes(StandardCharsets.UTF_8).length<4096);
        assertEquals(400,commandController().submit(json(body)).getStatusCodeValue());assertEquals(0,jdbc.queryForObject("select count(*) from w18_paid_install_command",Integer.class));
    }

    private void lateCommitStatusBoundary(boolean history,boolean reserveOnly) throws Exception {
        commandSetup();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        probe.afterUpdate=id->{if(id.endsWith(reserveOnly?"insertReservation":"ModuleInstallationStore.consume"))org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void beforeCommit(boolean readOnly){installTime.set(NOW.plusSeconds(301));entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}});};
        ExecutorService workers=Executors.newFixedThreadPool(2);Future<Map<String,Object>> writer=null,reader=null;
        try{
            writer=workers.submit(()->commandService.submit(7,command()));assertTrue(entered.await(5,TimeUnit.SECONDS));
            reader=workers.submit(()->history?commandService.history(7,"w18-paid-11",null):commandService.status(7,COMMAND_ID));
            final Future<Map<String,Object>> pendingRead=reader;
            // Old unlocked projection returned EXPIRED here even though consume could still commit.
            // Stable status must wait on the same request/license mutexes through physical commit.
            assertThrows(TimeoutException.class,()->{Map<String,Object> early=pendingRead.get(200,TimeUnit.MILLISECONDS);System.out.println("EARLY_STATUS_BEFORE_PHYSICAL_COMMIT="+early.get("state"));});
            release.countDown();Map<String,Object> actual=reader.get(5,TimeUnit.SECONDS);
            if(history)actual=(Map<String,Object>)((List<?>)actual.get("items")).get(0);
            assertEquals(reserveOnly?"EXPIRED":"INSTALLED_PRIVATE_DRAFT",actual.get("state"));
            if(reserveOnly){final Future<Map<String,Object>> pendingWrite=writer;assertThrows(ExecutionException.class,()->pendingWrite.get(5,TimeUnit.SECONDS));assertEquals(0,installedCount());}
            else{assertEquals("INSTALLED_PRIVATE_DRAFT",writer.get(5,TimeUnit.SECONDS).get("state"));assertEquals(1,installedCount());}
        }finally{release.countDown();workers.shutdown();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void statusWaitsForPhysicalConsumeCommitBeforeDeclaringLeaseExpired() throws Exception {lateCommitStatusBoundary(false,false);}
    @Test void historyWaitsForPhysicalConsumeCommitBeforeDeclaringLeaseExpired() throws Exception {lateCommitStatusBoundary(true,false);}
    @Test void statusWaitsForFirstReserveCommitBeforeDeclaringPreparedCommandExpired() throws Exception {lateCommitStatusBoundary(false,true);}

    @Test void recoveryReadsUseExactExistingCoreKeysWithoutWritingCoordinationRows() throws Exception {
        commandSetup();commandService.submit(7,command());String key=com.chengyinhub.business.marketplace.reservation.ModuleReservationKeys.request(new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,7),"w18-paid-command:"+COMMAND_ID);
        assertEquals(key,jdbc.queryForObject("select reservation_key from w18_module_install_reservation",String.class));assertEquals(com.chengyinhub.business.marketplace.reservation.ModuleReservationKeys.license("w18-paid-11"),jdbc.queryForObject("select license_key from w18_module_install_reservation",String.class));
        int mutexes=jdbc.queryForObject("select count(*) from w18_module_install_mutex",Integer.class);for(int i=0;i<3;i++){commandService.status(7,COMMAND_ID);commandService.history(7,"w18-paid-11",null);}assertEquals(mutexes,jdbc.queryForObject("select count(*) from w18_module_install_mutex",Integer.class));assertEquals(1,installedCount());
    }
    @Test void missingRecoveryMutexFailsClosedRatherThanCreatingOneFromRead() throws Exception {
        commandSetup();commandService.submit(7,command());String key=com.chengyinhub.business.marketplace.reservation.ModuleReservationKeys.request(new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,7),"w18-paid-command:"+COMMAND_ID);jdbc.update("delete from w18_module_install_mutex where lock_key=?",key);
        int mutexes=jdbc.queryForObject("select count(*) from w18_module_install_mutex",Integer.class);assertThrows(RuntimeException.class,()->commandService.status(7,COMMAND_ID));assertThrows(RuntimeException.class,()->commandService.history(7,"w18-paid-11",null));assertEquals(mutexes,jdbc.queryForObject("select count(*) from w18_module_install_mutex",Integer.class));assertEquals(1,installedCount());
    }
    @Test void multiCommandHistoryAndConcurrentSubmitFinishWithoutMutexOrderInversion() throws Exception {
        commandSetup();commandService.submit(7,command());PaidInstallCommand second=command("00000000-0000-4000-8000-000000000002",91,"TOPIC",command().targetPayloadHash,"synthetic-region",false);commandService.submit(7,second);
        PaidInstallCommand third=command("00000000-0000-4000-8000-000000000003",91,"TOPIC",command().targetPayloadHash,"synthetic-region",false);ExecutorService workers=Executors.newFixedThreadPool(3);CountDownLatch start=new CountDownLatch(1);
        try{Future<?> a=workers.submit(()->{try{start.await();}catch(InterruptedException e){throw new RuntimeException(e);}return commandService.history(7,"w18-paid-11",null);});Future<?> c=workers.submit(()->{try{start.await();}catch(InterruptedException e){throw new RuntimeException(e);}return commandService.history(7,"w18-paid-11",null);});Future<?> writer=workers.submit(()->{try{start.await();}catch(InterruptedException e){throw new RuntimeException(e);}return commandService.submit(7,third);});start.countDown();a.get(5,TimeUnit.SECONDS);c.get(5,TimeUnit.SECONDS);writer.get(5,TimeUnit.SECONDS);assertEquals(3,installedCount());assertEquals(3,((List<?>)commandService.history(7,"w18-paid-11",null).get("items")).size());}finally{workers.shutdownNow();}
    }

    private PaidProfessionalTargetReader professionalTargets;
    private void professionalTargetSetup(){
        jdbc.execute("create table cms_topic(id bigint primary key,member_id bigint,name varchar(512),product_type int,config_version bigint,del_flag int)");
        // Deliberately omit the production singleton constraint to test corrupt duplicate CONTENT rows.
        jdbc.execute("create table product_role(id bigint primary key,topic_id bigint,slot varchar(32),entity_type varchar(32),entity_id bigint,status int,declared_by bigint,created_at timestamp(6))");
        jdbc.update("insert into cms_topic values(81,7,'Owned city',1,3,0),(82,7,'Owned free',2,4,0),(83,7,'Unresolved',null,null,0),(84,7,'Other content owner',1,1,0),(85,8,'Other publisher',1,1,0),(86,7,'Missing role',1,1,0)");
        jdbc.update("insert into product_role values(101,81,'CONTENT','MEMBER',7,1,null,?),(102,82,'CONTENT','MEMBER',7,1,null,?),(103,83,'CONTENT','MEMBER',7,1,null,?),(104,84,'CONTENT','MEMBER',8,1,null,?),(105,85,'CONTENT','MEMBER',7,1,null,?)",Timestamp.from(NOW),Timestamp.from(NOW),Timestamp.from(NOW),Timestamp.from(NOW),Timestamp.from(NOW));
        professionalTargets=new PaidProfessionalTargetReader(session,manager,installClock,PaidProfessionalTargetReader.Policy.INDIVIDUAL_OWN_CONTENT_AND_PUBLISHER_V1);assertTrue(service.accept(event()));
    }
    @Test void professionalTargetsUseActualContentOwnerAndSeparateProductMode() {
        professionalTargetSetup();Map<String,Object> result=professionalTargets.list(7,"w18-paid-11",null);List<?> rows=(List<?>)result.get("items");assertEquals(3,rows.size());
        assertEquals("UNRESOLVED",((Map<?,?>)rows.get(0)).get("mode"));assertEquals("FREE_EXPLORATION",((Map<?,?>)rows.get(1)).get("mode"));assertEquals("CITY_ORIENTATION",((Map<?,?>)rows.get(2)).get("mode"));
        assertEquals("PROTECTED_TEMPLATE_ADAPTER_REQUIRED",((Map<?,?>)rows.get(2)).get("materializationStatus"));assertFalse(((Map<?,?>)rows.get(2)).containsKey("entityId"));assertFalse(((Map<?,?>)rows.get(2)).containsKey("ownerId"));assertFalse(((Map<?,?>)rows.get(2)).containsKey("routeGraphJson"));
    }
    @Test void publisherIdIsNotContentOwnershipAndContentOwnerAloneDoesNotExpandLegacyEditScope(){
        professionalTargetSetup();for(long id:Arrays.asList(84L,85L,86L))assertThrows(RuntimeException.class,()->professionalTargets.detail(7,"w18-paid-11",id));assertThrows(RuntimeException.class,()->professionalTargets.detail(8,"w18-paid-11",81));
    }
    @Test void sameNumericClubOrPlatformSlotAndDeactivatedMemberCannotBorrowIndividualOwnership(){
        professionalTargetSetup();jdbc.update("update product_role set entity_type='CLUB' where id=101");assertThrows(RuntimeException.class,()->professionalTargets.detail(7,"w18-paid-11",81));jdbc.update("update product_role set entity_type='PLATFORM',entity_id=null where id=101");assertThrows(RuntimeException.class,()->professionalTargets.detail(7,"w18-paid-11",81));jdbc.update("update product_role set entity_type='MEMBER',entity_id=7,status=0 where id=101");assertThrows(RuntimeException.class,()->professionalTargets.detail(7,"w18-paid-11",81));
    }
    @Test void corruptDuplicateActiveContentSlotsDoNotTakeFirstRow(){
        professionalTargetSetup();jdbc.update("insert into product_role values(201,81,'CONTENT','CLUB',7,1,null,?)",Timestamp.from(NOW));assertThrows(RuntimeException.class,()->professionalTargets.detail(7,"w18-paid-11",81));assertEquals(2,((List<?>)professionalTargets.list(7,"w18-paid-11",null).get("items")).size());
    }
    @Test void contentOwnerABAHasNewFingerprintEvenWhenPublisherAndModeAreUnchanged(){
        professionalTargetSetup();Object before=professionalTargets.detail(7,"w18-paid-11",81).get("ownerModeFingerprint");jdbc.update("update product_role set status=0 where id=101");jdbc.update("insert into product_role values(201,81,'CONTENT','MEMBER',8,1,8,?)",Timestamp.from(NOW.plusSeconds(1)));assertThrows(RuntimeException.class,()->professionalTargets.detail(7,"w18-paid-11",81));jdbc.update("update product_role set status=0 where id=201");jdbc.update("insert into product_role values(202,81,'CONTENT','MEMBER',7,1,7,?)",Timestamp.from(NOW.plusSeconds(2)));assertNotEquals(before,professionalTargets.detail(7,"w18-paid-11",81).get("ownerModeFingerprint"));
    }
    @Test void modeAndRouteRevisionAreDistinctFingerprintInputsAndMissingModeStaysUnresolved(){
        professionalTargetSetup();Object before=professionalTargets.detail(7,"w18-paid-11",81).get("ownerModeFingerprint");jdbc.update("update cms_topic set product_type=2 where id=81");Map<String,Object> changed=professionalTargets.detail(7,"w18-paid-11",81);assertEquals("FREE_EXPLORATION",changed.get("mode"));assertNotEquals(before,changed.get("ownerModeFingerprint"));before=changed.get("ownerModeFingerprint");jdbc.update("update cms_topic set config_version=4 where id=81");assertNotEquals(before,professionalTargets.detail(7,"w18-paid-11",81).get("ownerModeFingerprint"));assertNull(professionalTargets.detail(7,"w18-paid-11",83).get("productType"));
    }
    @Test void professionalTargetReaderDefaultOffRevokedLicenseAndChangedDatasourceFailClosed(){
        professionalTargetSetup();PaidProfessionalTargetReader off=new PaidProfessionalTargetReader(session,manager,installClock);assertThrows(RuntimeException.class,()->off.list(7,"w18-paid-11",null));jdbc.update("update w18_paid_license set state='REVOKED'");assertThrows(RuntimeException.class,()->professionalTargets.list(7,"w18-paid-11",null));manager.setDataSource(otherDataSource());assertThrows(RuntimeException.class,()->professionalTargets.detail(7,"w18-paid-11",81));assertEquals(6,jdbc.queryForObject("select count(*) from cms_topic",Integer.class));
    }
    @Test void professionalTargetNamesAreBoundedRatherThanTruncatedIntoAcceptedCAS(){
        professionalTargetSetup();jdbc.update("update cms_topic set name=? where id=81",new String(new char[300]).replace('\0','x'));assertThrows(RuntimeException.class,()->professionalTargets.detail(7,"w18-paid-11",81));assertEquals(300,jdbc.queryForObject("select length(name) from cms_topic where id=81",Integer.class));
    }

    private com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalTargetController professionalTargetController(){
        controller();com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalTargetController value=new com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalTargetController();org.springframework.test.util.ReflectionTestUtils.setField(value,"reader",professionalTargets);return value;
    }
    @Test void professionalHttpListAndDetailReachActualOwnerRowsAndNeverWrite() throws Exception {
        professionalTargetSetup();org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(professionalTargetController()).build();
        probe.afterUpdate=id->{throw new AssertionError("professional target read cannot write: "+id);};
        for(String action:Arrays.asList("list","detail")){
            String body="list".equals(action)?"{\"licenseId\":\"w18-paid-11\"}":"{\"licenseId\":\"w18-paid-11\",\"topicId\":81}";
            org.springframework.mock.web.MockHttpServletResponse response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workshop/purchased/professional-targets/"+action).contentType("application/json").content(body)).andReturn().getResponse();
            assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("no-cache",response.getHeader("Pragma"));
            String actual=response.getContentAsString();assertTrue(actual.contains("CITY_ORIENTATION"));assertTrue(actual.contains("PROTECTED_TEMPLATE_ADAPTER_REQUIRED"));assertFalse(actual.contains("sourceContent"));assertFalse(actual.contains("synthetic_transaction"));assertFalse(actual.contains("entityId"));assertFalse(actual.contains("templateId"));
        }
        assertEquals(405,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/workshop/purchased/professional-targets/list")).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workshop/purchased/professional-targets/bind")).andReturn().getResponse().getStatus());
        assertEquals(6,jdbc.queryForObject("select count(*) from cms_topic",Integer.class));assertEquals(5,jdbc.queryForObject("select count(*) from product_role",Integer.class));
    }
    @Test void professionalHttpFailsClosedForForeignTopicRevokedLicenseAnonymousAndMissingReader(){
        professionalTargetSetup();com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalTargetController c=professionalTargetController();
        assertEquals(503,c.detail(json("{\"licenseId\":\"w18-paid-11\",\"topicId\":84}")).getStatusCodeValue());
        jdbc.update("update w18_paid_license set state='REVOKED'");assertEquals(503,c.list(json("{\"licenseId\":\"w18-paid-11\"}")).getStatusCodeValue());
        org.springframework.test.util.ReflectionTestUtils.setField(c,"reader",null);assertEquals("PAID_PROFESSIONAL_TARGET_NOT_ENABLED",c.list(json("{\"licenseId\":\"w18-paid-11\"}")).getBody().get("errorCode"));
        org.springframework.security.core.context.SecurityContextHolder.clearContext();assertEquals(401,c.list(json("{\"licenseId\":\"w18-paid-11\"}")).getStatusCodeValue());
    }
    @Test void professionalHttpRejectsInvalidFlatSchemaBeforeAnyDatabaseConnection(){
        professionalTargetSetup();com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalTargetController c=professionalTargetController();
        for(String body:Arrays.asList("{}","[]","{\"licenseId\":\"w18-paid-11\",\"ownerId\":7}","{\"licenseId\":\"w18-paid-11\",\"licenseId\":\"w18-paid-11\"}","{\"licenseId\":\"w18-paid-11\",\"beforeTopicId\":0}","{\"licenseId\":\"w18-paid-11\",\"beforeTopicId\":1.0}","{\"licenseId\":\"w18-paid-11\",\"beforeTopicId\":null}","{\"licenseId\":\"w18-paid-11\",\"beforeTopicId\":9223372036854775808}","{\"licenseId\":\"w18-paid-11\",\"beforeTopicId\":{}}","{\"licenseId\":\"w18-paid-11\"}{}")){
            connectionProbe.onConnect=()->{throw new AssertionError("invalid request must not query");};assertEquals(400,c.list(json(body)).getStatusCodeValue());
        }
        connectionProbe.onConnect=()->{throw new AssertionError("missing topic must not query");};assertEquals(400,c.detail(json("{\"licenseId\":\"w18-paid-11\"}")).getStatusCodeValue());connectionProbe.onConnect=()->{};
    }
    @Test void professionalHttpBoundsActualStreamAndRejectsUnknownLengthEncodingQueryAndTransfer(){
        professionalTargetSetup();com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalTargetController c=professionalTargetController();String valid="{\"licenseId\":\"w18-paid-11\"}";
        org.springframework.mock.web.MockHttpServletRequest unknown=new org.springframework.mock.web.MockHttpServletRequest(){public long getContentLengthLong(){return -1;}};unknown.setContentType("application/json");unknown.setContent(valid.getBytes(StandardCharsets.UTF_8));assertEquals(400,c.list(unknown).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest mismatch=new org.springframework.mock.web.MockHttpServletRequest(){public long getContentLengthLong(){return 2;}};mismatch.setContentType("application/json");mismatch.setContent(new byte[513]);assertEquals(400,c.list(mismatch).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest query=json(valid);query.setQueryString("topicId=81");assertEquals(400,c.list(query).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest transfer=json(valid);transfer.addHeader("Transfer-Encoding","chunked");assertEquals(400,c.list(transfer).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest encoding=json(valid);encoding.setContentType("application/json; charset=UTF-16");assertEquals(400,c.list(encoding).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest invalid=json(valid);invalid.setContent(new byte[]{'{','"','x','"',':','"',(byte)0xc0,(byte)0xaf,'"','}'});assertEquals(400,c.list(invalid).getStatusCodeValue());
        assertEquals(400,c.list(json("{\"licenseId\":\"\\uD800\"}")).getStatusCodeValue());
    }
    @Test void professionalTargetPagingIsBoundedExclusiveAndDoesNotExposeForeignContent(){
        professionalTargetSetup();for(long id=100;id<151;id++){jdbc.update("insert into cms_topic values(?,7,'Page',1,0,0)",id);jdbc.update("insert into product_role values(?,?,'CONTENT','MEMBER',7,1,null,?)",id+1000,id,Timestamp.from(NOW));}
        Map<String,Object> page=professionalTargets.list(7,"w18-paid-11",null);assertEquals(50,((List<?>)page.get("items")).size());assertEquals(true,page.get("hasMore"));assertEquals(101L,page.get("nextBeforeTopicId"));
        Map<String,Object> last=professionalTargets.list(7,"w18-paid-11",(Long)page.get("nextBeforeTopicId"));assertEquals(4,((List<?>)last.get("items")).size());assertEquals(false,last.get("hasMore"));assertNull(last.get("nextBeforeTopicId"));
    }
    @Test void caseInsensitiveDatabaseDoesNotTurnMalformedRoleLiteralsIntoOwnership(){
        professionalTargetSetup();jdbc.execute("alter table product_role alter column slot varchar_ignorecase(32)");jdbc.execute("alter table product_role alter column entity_type varchar_ignorecase(32)");
        jdbc.update("update product_role set slot='content' where id=101");assertThrows(RuntimeException.class,()->professionalTargets.detail(7,"w18-paid-11",81));
        jdbc.update("update product_role set slot='CONTENT',entity_type='member' where id=101");assertThrows(RuntimeException.class,()->professionalTargets.detail(7,"w18-paid-11",81));
    }

    private PaidInstalledTextReader paidBody(){return new PaidInstalledTextReader(session,manager,installClock,PaidInstalledTextReader.Policy.OWNER_PAID_COMMITTED_TEXT_READ_V1);}
    private long committedPaidText()throws Exception {commandSetup();return (Long)commandService.submit(7,command()).get("ownedDraftId");}
    @Test void actualPaidBodyReaderUsesCommittedOwnerSourceAndRedactsInternalPaymentEvidence()throws Exception{
        long owned=committedPaidText();probe.afterUpdate=id->{throw new AssertionError("body read cannot write: "+id);};Map<String,Object> result=paidBody().detail(7,COMMAND_ID,owned);
        assertEquals("w18-paid-installed-text-v1",result.get("schema"));assertEquals("OWNER_PAID_INSTALLED_TEXT_PROTECTED_READ_ONLY",result.get("scope"));assertEquals("MATERIALIZATION_REQUIRED",result.get("professionalTemplateStatus"));assertEquals(false,result.get("published"));assertEquals(false,result.get("executable"));assertEquals("UNRESOLVED",result.get("mode"));
        assertArrayEquals(snapshot.getContent(),((String)result.get("sourceJson")).getBytes(StandardCharsets.UTF_8));assertArrayEquals(snapshot.getTerms(),Base64.getDecoder().decode((String)result.get("termsDocumentBase64")));assertEquals("PERPETUAL_PURCHASED_VERSION",((Map<?,?>)result.get("rights")).get("useDuration"));
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);assertFalse(json.contains("synthetic_transaction"));assertFalse(json.contains("sourceEventId"));assertFalse(json.contains("authorityReference"));assertFalse(json.contains("priceMinor"));assertEquals(1,installedCount());
    }
    @Test void serializerRefactorRetainsActualPredecessorPaidComponentAndFrozenRightsBytes()throws Exception{
        committedPaidText();assertArrayEquals(Files.readAllBytes(Paths.get("src/test/resources/w18-paid-baseline/paid-installed-component-before-refactor.json")),jdbc.queryForObject("select content_json from w18_module_draft_component",String.class).getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(Files.readAllBytes(Paths.get("src/test/resources/w18-paid-baseline/paid-installed-rights-before-refactor.json")),jdbc.queryForObject("select frozen_rights from w18_module_owned_draft",String.class).getBytes(StandardCharsets.UTF_8));
        assertEquals(new String(Files.readAllBytes(Paths.get("src/test/resources/w18-paid-baseline/paid-installed-reservation-before-refactor.txt")),StandardCharsets.UTF_8),jdbc.queryForObject("select authority_hash from w18_module_install_reservation",String.class)+"\n"+jdbc.queryForObject("select payload_hash from w18_module_install_reservation",String.class));
    }
    @Test void consumedPaidBodySurvivesTechnicalHoldExpiryAndLaterOriginalTargetEdits()throws Exception{
        long owned=committedPaidText();installTime.set(NOW.plusSeconds(315360000));jdbc.update("update content_draft set owner_member_id=8,version=2,status='DELETED',payload_hash=? where id=91",PaidPurchaseSnapshot.sha("changed".getBytes(StandardCharsets.UTF_8)));
        Map<String,Object> result=paidBody().detail(7,COMMAND_ID,owned);assertEquals(1L,result.get("originalTargetRevision"));assertEquals("PERPETUAL_PURCHASED_VERSION",((Map<?,?>)result.get("rights")).get("useDuration"));assertEquals(owned,result.get("ownedDraftId"));
    }
    @Test void missingCommitForeignActorAndWrongOwnedRowNeverRevealBody()throws Exception{
        commandSetup();PaidInstalledTextReader reader=paidBody();assertThrows(RuntimeException.class,()->reader.detail(7,COMMAND_ID,1));long owned=(Long)commandService.submit(7,command()).get("ownedDraftId");assertThrows(RuntimeException.class,()->reader.detail(8,COMMAND_ID,owned));assertThrows(RuntimeException.class,()->reader.detail(7,COMMAND_ID,owned+1));assertThrows(RuntimeException.class,()->new PaidInstalledTextReader(session,manager,installClock).detail(7,COMMAND_ID,owned));
        jdbc.update("delete from w18_module_install_usage");assertThrows(RuntimeException.class,()->reader.detail(7,COMMAND_ID,owned));assertEquals(1,installedCount());
    }
    @Test void currentPaidRevocationOrRefundStopsProtectedBodyWithoutDeletingOwnedReceipt()throws Exception{
        long owned=committedPaidText();PaidInstalledTextReader reader=paidBody();jdbc.update("update w18_paid_license set state='REVOKED'");assertThrows(RuntimeException.class,()->reader.detail(7,COMMAND_ID,owned));jdbc.update("update w18_paid_license set state='ACTIVE'");jdbc.update("update oms_order set aftersale_status=4,status=5");assertThrows(RuntimeException.class,()->reader.detail(7,COMMAND_ID,owned));assertEquals(1,installedCount());
    }
    @Test void paidBodyRejectsChangedComponentAndExtraComponentsWithoutParsingDeepTrees()throws Exception{
        long owned=committedPaidText();PaidInstalledTextReader reader=paidBody();String original=jdbc.queryForObject("select content_json from w18_module_draft_component",String.class),deep=new String(new char[8000]).replace('\0','[')+"0"+new String(new char[8000]).replace('\0',']');
        jdbc.update("update w18_module_draft_component set content_json=?",deep);assertThrows(IllegalArgumentException.class,()->reader.detail(7,COMMAND_ID,owned));jdbc.update("update w18_module_draft_component set content_json=?",original);jdbc.update("insert into w18_module_draft_component values(?,'extra','TEXT_TEMPLATE',1,'{}')",owned);assertThrows(IllegalArgumentException.class,()->reader.detail(7,COMMAND_ID,owned));
    }
    @Test void paidBodyRejectsSourceTermsRightsAndPrivateStateTampering()throws Exception{
        long owned=committedPaidText();PaidInstalledTextReader reader=paidBody();String rights=jdbc.queryForObject("select frozen_rights from w18_module_owned_draft",String.class);
        for(String column:Arrays.asList("source_content","terms_document")){jdbc.update("update w18_module_owned_draft set "+column+"=?","changed".getBytes(StandardCharsets.UTF_8));assertThrows(IllegalArgumentException.class,()->reader.detail(7,COMMAND_ID,owned));jdbc.update("update w18_module_owned_draft set "+column+"=?","source_content".equals(column)?snapshot.getContent():snapshot.getTerms());}
        jdbc.update("update w18_module_owned_draft set frozen_rights=?",new String(new char[8000]).replace('\0','[')+"0"+new String(new char[8000]).replace('\0',']'));assertThrows(IllegalArgumentException.class,()->reader.detail(7,COMMAND_ID,owned));jdbc.update("update w18_module_owned_draft set frozen_rights=?",rights);
        jdbc.update("update w18_module_owned_draft set publish_status=1");assertThrows(IllegalArgumentException.class,()->reader.detail(7,COMMAND_ID,owned));
    }
    @Test void paidBodyChecksCanonicalCommandReservationAndCommittedUsageIdentities()throws Exception{
        long owned=committedPaidText();PaidInstalledTextReader reader=paidBody();String payload=jdbc.queryForObject("select payload_hash from w18_module_install_reservation",String.class),originalCommandHash=jdbc.queryForObject("select command_hash from w18_paid_install_command",String.class);
        jdbc.update("update w18_module_install_reservation set payload_hash=?",PaidPurchaseSnapshot.sha("other".getBytes(StandardCharsets.UTF_8)));assertThrows(IllegalArgumentException.class,()->reader.detail(7,COMMAND_ID,owned));jdbc.update("update w18_module_install_reservation set payload_hash=?",payload);
        jdbc.update("update w18_paid_install_command set command_hash=?",PaidPurchaseSnapshot.sha("other".getBytes(StandardCharsets.UTF_8)));assertThrows(IllegalArgumentException.class,()->reader.detail(7,COMMAND_ID,owned));jdbc.update("update w18_paid_install_command set command_hash=?",originalCommandHash);
        jdbc.update("update w18_module_install_usage set committed_at=?",Timestamp.from(NOW.plusSeconds(1)));assertThrows(IllegalArgumentException.class,()->reader.detail(7,COMMAND_ID,owned));
    }
    @Test void paidBodyUsesFixedDatabasePairAndDoesNotRepairMissingMutexes()throws Exception{
        long owned=committedPaidText();PaidInstalledTextReader reader=paidBody();String key=com.chengyinhub.business.marketplace.reservation.ModuleReservationKeys.request(new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,7),jdbc.queryForObject("select core_request_id from w18_paid_install_command",String.class));jdbc.update("delete from w18_module_install_mutex where lock_key=?",key);int count=jdbc.queryForObject("select count(*) from w18_module_install_mutex",Integer.class);assertThrows(IllegalArgumentException.class,()->reader.detail(7,COMMAND_ID,owned));assertEquals(count,jdbc.queryForObject("select count(*) from w18_module_install_mutex",Integer.class));manager.setDataSource(otherDataSource());assertThrows(IllegalArgumentException.class,()->reader.detail(7,COMMAND_ID,owned));
    }
    @Test void paidBodyBoundsStoredBytesInsteadOfAcceptingTruncatedData()throws Exception{
        long owned=committedPaidText();PaidInstalledTextReader reader=paidBody();jdbc.update("update w18_module_owned_draft set source_content=?",new byte[262145]);assertThrows(IllegalArgumentException.class,()->reader.detail(7,COMMAND_ID,owned));jdbc.update("update w18_module_owned_draft set source_content=?",snapshot.getContent());jdbc.update("update w18_module_owned_draft set frozen_rights=?",new String(new char[65537]).replace('\0','x'));assertThrows(IllegalArgumentException.class,()->reader.detail(7,COMMAND_ID,owned));
    }
    private com.chengyinhub.web.controller.api.ApiWorkshopPaidInstalledTextController paidBodyController(){controller();com.chengyinhub.web.controller.api.ApiWorkshopPaidInstalledTextController c=new com.chengyinhub.web.controller.api.ApiWorkshopPaidInstalledTextController();org.springframework.test.util.ReflectionTestUtils.setField(c,"reader",paidBody());return c;}
    @Test void paidProtectedBodyHasSeparateHttpProfileRealOwnerReadAndNoWriteRoutes()throws Exception{
        long owned=committedPaidText();org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(paidBodyController()).build();String body="{\"requestId\":\""+COMMAND_ID+"\",\"ownedDraftId\":"+owned+"}";
        org.springframework.mock.web.MockHttpServletResponse response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workshop/purchased/installed-text/detail").contentType("application/json").content(body)).andReturn().getResponse();assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertTrue(response.getContentAsString().contains("Synthetic purchased content"));
        assertEquals(404,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workshop/purchased/installed-text/publish")).andReturn().getResponse().getStatus());assertEquals(405,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/workshop/purchased/installed-text/detail")).andReturn().getResponse().getStatus());
    }
    @Test void paidProtectedBodyHttpRejectsMissingServiceAnonymousAndMalformedInputBeforeBodyRead()throws Exception{
        long owned=committedPaidText();com.chengyinhub.web.controller.api.ApiWorkshopPaidInstalledTextController c=paidBodyController();String valid="{\"requestId\":\""+COMMAND_ID+"\",\"ownedDraftId\":"+owned+"}";
        for(String invalid:Arrays.asList("{}",valid.replace("\"ownedDraftId\":","\"ownerId\":7,\"ownedDraftId\":"),valid.replace("\"ownedDraftId\":1","\"ownedDraftId\":1.0"),valid+"{}",valid.replace("\"ownedDraftId\":1","\"ownedDraftId\":null"))){connectionProbe.onConnect=()->{throw new AssertionError("malformed body queried");};assertEquals(400,c.detail(json(invalid)).getStatusCodeValue());}connectionProbe.onConnect=()->{};
        org.springframework.mock.web.MockHttpServletRequest unknown=new org.springframework.mock.web.MockHttpServletRequest(){public long getContentLengthLong(){return -1;}public jakarta.servlet.ServletInputStream getInputStream(){throw new AssertionError("unknown length parsed");}};unknown.setContentType("application/json");assertEquals(400,c.detail(unknown).getStatusCodeValue());
        org.springframework.test.util.ReflectionTestUtils.setField(c,"reader",null);assertEquals("PAID_INSTALLED_TEXT_NOT_ENABLED",c.detail(json(valid)).getBody().get("errorCode"));org.springframework.security.core.context.SecurityContextHolder.clearContext();assertEquals(401,c.detail(json(valid)).getStatusCodeValue());
    }

    @Test void installedPaidOriginalQuestionHintsAndMerchantGuideSurviveRealDatabaseRead()throws Exception{
        byte[] source="{\"schema\":\"w18-member-text-v1\",\"title\":\" Exact title 城市😀 \",\"description\":\"Exact description\",\"ruleInstructions\":\" Exact rules \",\"merchantGuide\":\" Secret staff guide \",\"questionName\":\" Exact question \",\"questionAnswer\":\" Exact answer \",\"hint1\":\" first \",\"hint2\":\" second \",\"answerReveal\":\" reveal \",\"bindings\":{}}".getBytes(StandardCharsets.UTF_8);
        ModuleVersionSnapshot old=snapshot.getVersion();ModuleVersionSnapshot changed=new ModuleVersionSnapshot(old.getCreator(),old.getModuleId(),old.getVersionId(),old.getVersion(),old.getSchemaVersion(),old.getSourceKind(),old.getSourceId(),old.getSourceRevision(),source,PaidPurchaseSnapshot.sha(source),old.getApprovalReference(),old.getRequiredBindings());snapshot=new PaidPurchaseSnapshot(changed,snapshot.getOffer(),source,snapshot.getTerms(),snapshot.getReviewedOfferReference());String frozen=snapshot.encode();jdbc.update("update w18_paid_order_line_snapshot set snapshot_json=?,snapshot_hash=?,source_content=?",frozen.getBytes(StandardCharsets.UTF_8),PaidPurchaseSnapshot.sha(frozen.getBytes(StandardCharsets.UTF_8)),source);
        long owned=committedPaidText();Map<String,Object> result=paidBody().detail(7,COMMAND_ID,owned);assertArrayEquals(source,((String)result.get("sourceJson")).getBytes(StandardCharsets.UTF_8));
        com.chengyinhub.business.marketplace.paid.materialization.PaidTemplateMaterializationFields fields=com.chengyinhub.business.marketplace.paid.materialization.PaidTemplateMaterializationFields.decode(((String)result.get("componentJson")).getBytes(StandardCharsets.UTF_8));
        assertEquals(" Secret staff guide ",fields.textFields().get("merchantGuide"));assertEquals(" Exact answer ",fields.textFields().get("questionAnswer"));assertEquals(" first ",fields.textFields().get("hint1"));assertEquals(" second ",fields.textFields().get("hint2"));assertEquals(" reveal ",fields.textFields().get("answerReveal"));
    }

    @Test void protectedPaidBodyWaitsForPhysicalInstallCommitAndIgnoresExpiredTechnicalHold()throws Exception{
        commandSetup();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);java.util.concurrent.atomic.AtomicLong owned=new java.util.concurrent.atomic.AtomicLong();
        probe.afterUpdate=id->{if(id.endsWith("ModuleInstallationStore.consume"))org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void beforeCommit(boolean readOnly){owned.set(jdbc.queryForObject("select id from w18_module_owned_draft",Long.class));installTime.set(NOW.plusSeconds(301));entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}});};
        ExecutorService workers=Executors.newFixedThreadPool(2);try{Future<Map<String,Object>> writer=workers.submit(()->commandService.submit(7,command()));assertTrue(entered.await(5,TimeUnit.SECONDS));Future<Map<String,Object>> reader=workers.submit(()->paidBody().detail(7,COMMAND_ID,owned.get()));assertThrows(TimeoutException.class,()->reader.get(200,TimeUnit.MILLISECONDS));release.countDown();assertEquals("INSTALLED_PRIVATE_DRAFT",writer.get(5,TimeUnit.SECONDS).get("state"));assertEquals(owned.get(),reader.get(5,TimeUnit.SECONDS).get("ownedDraftId"));}finally{release.countDown();workers.shutdown();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void protectedPaidBodyPreservesOpaqueFrozenTermsWithoutInventingTextEncoding()throws Exception{
        byte[] termsBytes=new byte[]{'P','D','F',0,(byte)0xff,(byte)0xfe};LicenseTermsSnapshot t=snapshot.getOffer().getTerms();
        LicenseTermsSnapshot newTerms=new LicenseTermsSnapshot(t.getTermsVersion(),termsBytes,PaidPurchaseSnapshot.sha(termsBytes),t.getTermsDocumentReference(),t.getCommercialUse(),t.getAdaptation(),t.getTranslation(),t.getUpdates(),t.getRedistribution(),t.getAllowedRegions(),t.getThemeLimit(),t.getMerchantLimit(),t.getRunLimit(),t.getUseDuration());LicenseOfferSnapshot o=snapshot.getOffer();LicenseOfferSnapshot newOffer=LicenseOfferSnapshot.oneTimePurchase(o.getOfferId(),o.getOfferVersion(),o.getModuleId(),o.getSeller(),o.getBuyerKinds(),o.getPriceMinor(),o.getCurrency(),newTerms);
        snapshot=new PaidPurchaseSnapshot(snapshot.getVersion(),newOffer,snapshot.getContent(),termsBytes,snapshot.getReviewedOfferReference());String frozen=snapshot.encode();jdbc.update("update w18_paid_order_line_snapshot set snapshot_json=?,snapshot_hash=?,terms_document=?",frozen.getBytes(StandardCharsets.UTF_8),PaidPurchaseSnapshot.sha(frozen.getBytes(StandardCharsets.UTF_8)),termsBytes);
        long owned=committedPaidText();Map<String,Object> actual=paidBody().detail(7,COMMAND_ID,owned);assertArrayEquals(termsBytes,Base64.getDecoder().decode((String)actual.get("termsDocumentBase64")));assertEquals(PaidPurchaseSnapshot.sha(termsBytes),actual.get("termsDocumentHash"));assertFalse(actual.containsKey("termsText"));
    }

    private PaidProfessionalMaterializationService materializer;
    private long materializedOwned;
    private void materializationSetup()throws Exception {
        commandSetup();professionalTargetSetup();
        // Actual pinned product_role has this active CONTENT/ORGANIZER singleton guard.
        // H2 1.4 uses AS(expression); MySQL uses GENERATED ALWAYS AS(expression) VIRTUAL.
        jdbc.execute("alter table product_role add singleton_key varchar(32) as (case when status=1 and slot in ('CONTENT','ORGANIZER') then concat(topic_id,'-',slot) else null end)");
        jdbc.execute("alter table product_role add constraint uk_product_role_singleton unique(singleton_key)");
        materializedOwned=(Long)commandService.submit(7,command()).get("ownedDraftId");
        String cms=new String(Files.readAllBytes(Paths.get("src/test/resources/materialization/cms-mapped-columns.sql")),StandardCharsets.UTF_8);
        jdbc.execute(cms);
        String ddl=new String(Files.readAllBytes(Paths.get("sql/migration_w18_paid_materialized_cms_provenance_20261006.sql")),StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","");
        for(String part:ddl.split(";"))if(!part.trim().isEmpty())jdbc.execute(part);
        String operations=new String(Files.readAllBytes(Paths.get("sql/migration_w18_paid_professional_operation_20261006.sql")),StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$","").replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","");
        for(String part:operations.split(";"))if(!part.trim().isEmpty())jdbc.execute(part);
        materializer=new PaidProfessionalMaterializationService(session,manager,installClock,PaidProfessionalMaterializationService.Policy.REVIEWED_GUARDED_CMS_TEXT_V1);
    }
    private static final String MATERIALIZATION_ID="10000000-0000-4000-8000-000000000001";
    private PaidProfessionalMaterializationCommand materialization(){return materialization(MATERIALIZATION_ID,81,PaidProfessionalMaterializationCommand.Mode.CITY_ORIENTATION);}
    private PaidProfessionalMaterializationCommand materialization(String id,long topic,PaidProfessionalMaterializationCommand.Mode mode){
        Map<String,Object> source=paidBody().detail(7,COMMAND_ID,materializedOwned);
        return new PaidProfessionalMaterializationCommand(id,COMMAND_ID,materializedOwned,"w18-paid-11","module-1","version-1",
                (String)source.get("contentHash"),(String)source.get("termsHash"),(String)source.get("componentHash"),
                jdbc.queryForObject("select version from content_draft where id=91",Long.class),jdbc.queryForObject("select payload_hash from content_draft where id=91",String.class),topic,
                (String)professionalTargets.detail(7,"w18-paid-11",topic).get("ownerModeFingerprint"),mode);
    }
    private int cmsCount(){return jdbc.queryForObject("select count(*) from cms_member_template",Integer.class);}
    private int provenanceCount(){return jdbc.queryForObject("select count(*) from w18_cms_template_provenance",Integer.class);}
    private void noMaterializedRows(){assertEquals(0,cmsCount());assertEquals(0,provenanceCount());assertEquals(1,installedCount());}
    @Test void materializerCreatesActualMarkedCmsAndImmutableProvenanceAtomically()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();Map<String,Object> result=materializer.materialize(7,c);
        assertEquals(1,cmsCount());assertEquals(1,provenanceCount());assertEquals("W18_MANAGED",jdbc.queryForObject("select w18_provenance_kind from cms_member_template",String.class));
        assertEquals("GUARDED_PRIVATE_TEMPLATE_CREATED",result.get("state"));assertEquals(false,result.get("nodeAttached"));assertEquals(false,result.get("published"));assertEquals(false,result.get("executable"));
        assertEquals(result.get("templateId"),jdbc.queryForObject("select template_id from w18_cms_template_provenance",Long.class));assertEquals(materializedOwned,jdbc.queryForObject("select owned_draft_id from w18_cms_template_provenance",Long.class));
        assertEquals(101L,jdbc.queryForObject("select content_role_id from w18_cms_template_provenance",Long.class));assertEquals(command().targetPayloadHash,jdbc.queryForObject("select payload_hash from content_draft where id=91",String.class));
    }
    @Test void materializerLostResponseReplayKeepsOneExactTemplateAndReceipt()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();Map<String,Object> first=materializer.materialize(7,c);installTime.set(NOW.plusSeconds(600));
        PaidProfessionalMaterializationService fresh=new PaidProfessionalMaterializationService(session,manager,installClock,PaidProfessionalMaterializationService.Policy.REVIEWED_GUARDED_CMS_TEXT_V1);
        assertEquals(first,fresh.materialize(7,c));assertEquals(1,cmsCount());assertEquals(1,provenanceCount());
    }
    @Test void materializerConcurrentReplayCommitsOnlyOneTemplate()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();ExecutorService workers=Executors.newFixedThreadPool(2);
        try{Future<Map<String,Object>>a=workers.submit(()->materializer.materialize(7,c)),b=workers.submit(()->materializer.materialize(7,c));assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}finally{workers.shutdownNow();}assertEquals(1,cmsCount());assertEquals(1,provenanceCount());
    }
    @Test void materializerFailureAfterSidecarInsertRollsBackBothRows()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();probe.afterUpdate=id->{if(id.endsWith("insertProvenance"))throw new IllegalStateException("synthetic sidecar failure");};
        assertThrows(RuntimeException.class,()->materializer.materialize(7,c));noMaterializedRows();probe.afterUpdate=id->{};assertNotNull(materializer.materialize(7,c));assertEquals(1,cmsCount());
    }
    @Test void materializerMissingSidecarTableRollsBackMarkedCmsInsert()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();jdbc.execute("drop table w18_cms_template_provenance");assertThrows(RuntimeException.class,()->materializer.materialize(7,c));assertEquals(0,cmsCount());
    }
    @Test void materializerChangedDatasourceAfterCmsInsertCannotAutocommitEitherRow()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();probe.afterUpdate=id->{if(id.endsWith("insertMarkedTemplate"))manager.setDataSource(otherDataSource());};
        assertThrows(RuntimeException.class,()->materializer.materialize(7,c));noMaterializedRows();
    }
    @Test void materializerDisabledAndForeignActorCannotInsert()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();assertThrows(RuntimeException.class,()->new PaidProfessionalMaterializationService(session,manager,installClock).materialize(7,c));assertThrows(RuntimeException.class,()->materializer.materialize(8,c));noMaterializedRows();
    }
    @Test void materializerFreshModeConfirmationRequiresActualMatchingMode()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization(MATERIALIZATION_ID,82,PaidProfessionalMaterializationCommand.Mode.CITY_ORIENTATION);assertThrows(RuntimeException.class,()->materializer.materialize(7,c));noMaterializedRows();
        assertEquals("FREE_EXPLORATION",materializer.materialize(7,materialization("10000000-0000-4000-8000-000000000002",82,PaidProfessionalMaterializationCommand.Mode.FREE_EXPLORATION)).get("confirmedMode"));
    }
    @Test void materializerUnresolvedModeCannotBeGuessedFromTopicBusinessType()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization(MATERIALIZATION_ID,83,PaidProfessionalMaterializationCommand.Mode.CITY_ORIENTATION);assertThrows(RuntimeException.class,()->materializer.materialize(7,c));noMaterializedRows();
    }
    @Test void materializerRoleAndModeAbaInvalidateCapturedTargetFingerprint()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();jdbc.update("update product_role set status=0 where id=101");jdbc.update("insert into product_role(id,topic_id,slot,entity_type,entity_id,status,declared_by,created_at) values(201,81,'CONTENT','MEMBER',7,1,null,?)",Timestamp.from(NOW));
        assertThrows(RuntimeException.class,()->materializer.materialize(7,c));noMaterializedRows();PaidProfessionalMaterializationCommand changed=materialization();jdbc.update("update cms_topic set config_version=4 where id=81");assertThrows(RuntimeException.class,()->materializer.materialize(7,changed));noMaterializedRows();
    }
    @Test void materializerDuplicateContentOrSameNumberClubIsNotMemberOwnership()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();jdbc.execute("alter table product_role drop constraint uk_product_role_singleton");jdbc.update("insert into product_role(id,topic_id,slot,entity_type,entity_id,status,declared_by,created_at) values(201,81,'CONTENT','CLUB',7,1,null,?)",Timestamp.from(NOW));assertThrows(RuntimeException.class,()->materializer.materialize(7,c));noMaterializedRows();
        jdbc.update("delete from product_role where id=201");jdbc.update("update product_role set entity_type='CLUB' where id=101");assertThrows(RuntimeException.class,()->materializer.materialize(7,c));noMaterializedRows();
    }
    @Test void materializerGenericCurrentCasIsSeparateFromOriginalInstallRevision()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();jdbc.update("update content_draft set version=2,payload_json=?,payload_hash=? where id=91","{\"local\":true}",PaidPurchaseSnapshot.sha("{\"local\":true}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(RuntimeException.class,()->materializer.materialize(7,c));noMaterializedRows();materializer.materialize(7,materialization("10000000-0000-4000-8000-000000000002",81,PaidProfessionalMaterializationCommand.Mode.CITY_ORIENTATION));assertEquals(1L,jdbc.queryForObject("select original_target_revision from w18_cms_template_provenance",Long.class));assertEquals(2L,jdbc.queryForObject("select confirmed_draft_revision from w18_cms_template_provenance",Long.class));
    }
    @Test void materializerSourceRefundOrRevokeBeforeWritePreservesInstalledHistory()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();jdbc.update("update oms_order set aftersale_status=4");assertThrows(RuntimeException.class,()->materializer.materialize(7,c));noMaterializedRows();
        jdbc.update("update oms_order set aftersale_status=1");jdbc.update("update w18_paid_license set state='REVOKED'");assertThrows(RuntimeException.class,()->materializer.materialize(7,c));noMaterializedRows();
    }
    @Test void materializerConsumedThemeCannotBindAnotherActualTopicWithNewRequest()throws Exception {
        materializationSetup();materializer.materialize(7,materialization());PaidProfessionalMaterializationCommand other=materialization("10000000-0000-4000-8000-000000000002",82,PaidProfessionalMaterializationCommand.Mode.FREE_EXPLORATION);
        assertThrows(RuntimeException.class,()->materializer.materialize(7,other));assertEquals(1,cmsCount());assertEquals(81L,jdbc.queryForObject("select target_topic_id from w18_cms_template_provenance",Long.class));
    }
    @Test void materializerSameRequestDifferentTargetCannotReplaceFrozenOrigin()throws Exception {
        materializationSetup();materializer.materialize(7,materialization());PaidProfessionalMaterializationCommand other=materialization(MATERIALIZATION_ID,82,PaidProfessionalMaterializationCommand.Mode.FREE_EXPLORATION);
        assertThrows(RuntimeException.class,()->materializer.materialize(7,other));assertEquals(1,cmsCount());assertEquals(1,provenanceCount());
    }
    @Test void materializerReplayNeverRepairsMissingMarker()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();materializer.materialize(7,c);jdbc.update("update cms_member_template set w18_provenance_kind=null");assertThrows(RuntimeException.class,()->materializer.materialize(7,c));assertEquals(1,cmsCount());assertEquals(1,provenanceCount());
    }
    @Test void materializerReplayRejectsCmsFieldOrSidecarChainTampering()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();materializer.materialize(7,c);jdbc.update("update cms_member_template set merchant_guide='changed'");assertThrows(RuntimeException.class,()->materializer.materialize(7,c));jdbc.update("update cms_member_template set merchant_guide=null");jdbc.update("update w18_cms_template_provenance set terms_hash=?",new String(new char[64]).replace('\0','0'));assertThrows(RuntimeException.class,()->materializer.materialize(7,c));assertEquals(1,cmsCount());
    }

    @Test void materializerBothRowsInvisibleUntilPhysicalTransactionCommit()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService workers=Executors.newSingleThreadExecutor();
        probe.afterUpdate=id->{if(id.endsWith("insertProvenance")){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){throw new RuntimeException(e);}}};
        try{Future<?> result=workers.submit(()->materializer.materialize(7,c));assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(0,cmsCount());assertEquals(0,provenanceCount());release.countDown();result.get(5,TimeUnit.SECONDS);assertEquals(1,cmsCount());assertEquals(1,provenanceCount());}finally{release.countDown();workers.shutdownNow();}
    }
    private void materializerBlocksTargetChange(String change)throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
        probe.afterUpdate=id->{if(id.endsWith("insertMarkedTemplate")){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){throw new RuntimeException(e);}}};
        try{Future<?> create=workers.submit(()->materializer.materialize(7,c));assertTrue(entered.await(5,TimeUnit.SECONDS));Future<?> mutation=workers.submit(()->jdbc.update(change));assertThrows(TimeoutException.class,()->mutation.get(200,TimeUnit.MILLISECONDS));release.countDown();create.get(5,TimeUnit.SECONDS);mutation.get(5,TimeUnit.SECONDS);assertEquals(1,cmsCount());assertThrows(RuntimeException.class,()->materializer.materialize(7,c));}finally{release.countDown();workers.shutdownNow();}
    }
    @Test void materializerLocksActualModeUntilCommit()throws Exception {materializerBlocksTargetChange("update cms_topic set product_type=2 where id=81");}
    @Test void materializerLocksExistingContentOwnershipUntilCommit()throws Exception {materializerBlocksTargetChange("update product_role set entity_id=8 where id=101");}
    @Test void materializerActualSingletonConstraintRejectsConcurrentDuplicateContent()throws Exception {
        materializationSetup();assertThrows(RuntimeException.class,()->jdbc.update("insert into product_role(id,topic_id,slot,entity_type,entity_id,status,declared_by,created_at) values(301,81,'CONTENT','MEMBER',8,1,null,?)",Timestamp.from(NOW)));assertNotNull(materializer.materialize(7,materialization()));
    }
    @Test void materializerCapturedProofCannotEscapeOriginalTransactionOrBoundary()throws Exception {
        materializationSetup();com.chengyinhub.business.marketplace.paid.PaidMaterializationCaptureProbe.assertLifetime(session,manager,installClock,COMMAND_ID,materializedOwned);noMaterializedRows();
    }
    private void usePurchasedComponent(String text){
        byte[] content=text.getBytes(StandardCharsets.UTF_8);ModuleVersionSnapshot v=new ModuleVersionSnapshot(new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,8),"module-1","version-1","v1",1,ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE,12,"source-revision-1",content,PaidPurchaseSnapshot.sha(content),"review:source-1",Collections.emptyMap());
        snapshot=new PaidPurchaseSnapshot(v,snapshot.getOffer(),content,snapshot.getTerms(),"review:offer-1");String encoded=snapshot.encode();jdbc.update("update w18_paid_order_line_snapshot set source_content=?,snapshot_json=?,snapshot_hash=? where order_line_id=11",content,encoded.getBytes(StandardCharsets.UTF_8),PaidPurchaseSnapshot.sha(encoded.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void materializerMerchantGuideAndWhitespaceReachActualCmsWithoutTruncation()throws Exception {
        usePurchasedComponent("{\"schema\":\"w18-member-text-v1\",\"title\":\"Title\",\"description\":\"Short\",\"merchantGuide\":\" Staff-only\\n exact \",\"ruleInstructions\":\" Player rule \",\"questionName\":\"Q\",\"questionAnswer\":\" A \",\"hint1\":\" H1 \",\"hint2\":\"H2\",\"answerReveal\":\" Reveal \",\"bindings\":{}}");
        materializationSetup();materializer.materialize(7,materialization());assertEquals(" Staff-only\n exact ",jdbc.queryForObject("select merchant_guide from cms_member_template",String.class));assertEquals(" Player rule ",jdbc.queryForObject("select rule_instructions from cms_member_template",String.class));assertEquals(" A ",jdbc.queryForObject("select question_answer from cms_member_template",String.class));assertEquals(0,jdbc.queryForObject("select photo_review from cms_member_template",Integer.class));assertEquals("glow",jdbc.queryForObject("select medal_style from cms_member_template",String.class));assertNull(jdbc.queryForObject("select medal_name from cms_member_template",String.class));
    }
    @Test void materializerRejectsRealVarcharOverflowBeforeInsertingCms()throws Exception {
        usePurchasedComponent("{\"schema\":\"w18-member-text-v1\",\"title\":\"Title\",\"merchantGuide\":\""+new String(new char[1001]).replace('\0','x')+"\",\"bindings\":{}}");
        materializationSetup();RuntimeException e=assertThrows(RuntimeException.class,()->materializer.materialize(7,materialization()));assertTrue(e.getMessage().contains("merchantGuide:cms-column-limit"));noMaterializedRows();
    }

    private com.chengyinhub.web.controller.api.ApiWorkshopPaidMaterializationController materializationController(){
        controller();com.chengyinhub.web.controller.api.ApiWorkshopPaidMaterializationController c=new com.chengyinhub.web.controller.api.ApiWorkshopPaidMaterializationController();org.springframework.test.util.ReflectionTestUtils.setField(c,"materialization",materializer);return c;
    }
    private String materializationJson()throws Exception {
        PaidProfessionalMaterializationCommand c=materialization();Map<String,Object> values=new LinkedHashMap<String,Object>();values.put("schema",PaidProfessionalMaterializationCommand.SCHEMA);values.put("requestId",c.requestId);values.put("installationRequestId",c.installationRequestId);values.put("ownedDraftId",c.ownedDraftId);values.put("licenseId",c.licenseId);values.put("moduleId",c.moduleId);values.put("purchasedVersionId",c.purchasedVersionId);values.put("contentHash",c.contentHash);values.put("termsHash",c.termsHash);values.put("componentHash",c.componentHash);values.put("currentDraftRevision",c.currentDraftRevision);values.put("currentDraftPayloadHash",c.currentDraftPayloadHash);values.put("targetTopicId",c.targetTopicId);values.put("targetOwnerModeFingerprint",c.targetOwnerModeFingerprint);values.put("confirmedMode",c.confirmedMode.name());return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(values);
    }
    private String materializationStatusJson(){return "{\"requestId\":\""+MATERIALIZATION_ID+"\"}";}
    @Test void materializerStatusIsOwnedHistoricalReferenceAfterTargetAndRightsChange()throws Exception {
        materializationSetup();assertEquals("NOT_FOUND",materializer.status(7,MATERIALIZATION_ID).get("state"));Map<String,Object> created=materializer.materialize(7,materialization());
        jdbc.update("update content_draft set version=2 where id=91");jdbc.update("update cms_topic set product_type=2 where id=81");jdbc.update("update product_role set entity_id=8 where id=101");jdbc.update("update oms_order set aftersale_status=4");jdbc.update("update w18_paid_license set state='REVOKED'");
        Map<String,Object> history=materializer.status(7,MATERIALIZATION_ID);assertEquals("CREATION_COMMITTED",history.get("state"));assertEquals(created.get("templateId"),history.get("templateId"));assertEquals("OWNER_HISTORICAL_CREATION_REFERENCE_ONLY",history.get("scope"));assertEquals("NOT_CHECKED",history.get("currentTemplateState"));assertEquals("NOT_GRANTED_BY_HISTORY",history.get("currentUseAuthority"));
        for(String absent:Arrays.asList("sourceJson","componentJson","termsDocumentBase64","published","executable","currentLicenseStatus"))assertFalse(history.containsKey(absent));assertEquals(1,cmsCount());assertEquals(1,provenanceCount());
    }
    @Test void materializerStatusNeverGrantsBodyWhenActualCmsMissing()throws Exception {
        materializationSetup();materializer.materialize(7,materialization());jdbc.update("delete from cms_member_template");assertEquals("CREATION_COMMITTED",materializer.status(7,MATERIALIZATION_ID).get("state"));assertEquals("NOT_CHECKED",materializer.status(7,MATERIALIZATION_ID).get("currentTemplateState"));assertEquals(0,cmsCount());assertEquals(1,provenanceCount());
    }
    @Test void materializerStatusForeignOwnerInactiveAccountAndDisabledPortFailClosed()throws Exception {
        materializationSetup();materializer.materialize(7,materialization());assertEquals("NOT_FOUND",materializer.status(8,MATERIALIZATION_ID).get("state"));jdbc.update("update ums_member set status=0 where id=7");assertThrows(RuntimeException.class,()->materializer.status(7,MATERIALIZATION_ID));assertThrows(RuntimeException.class,()->new PaidProfessionalMaterializationService(session,manager,installClock).status(7,MATERIALIZATION_ID));
    }
    @Test void materializerStatusMalformedImmutableReferenceIsNotReportedAsMissing()throws Exception {
        materializationSetup();materializer.materialize(7,materialization());jdbc.update("update w18_cms_template_provenance set command_hash=?",new String(new char[64]).replace('\0','0'));assertThrows(RuntimeException.class,()->materializer.status(7,MATERIALIZATION_ID));assertEquals(1,provenanceCount());
    }
    @Test void materializerHttpRealSubmitStatusRecoveryIsNoStoreAndExactlyOnce()throws Exception {
        materializationSetup();com.chengyinhub.web.controller.api.ApiWorkshopPaidMaterializationController c=materializationController();String body=materializationJson();org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(c).build();
        String prefix="/api/workshop/purchased/professional-materialization";
        org.springframework.mock.web.MockHttpServletResponse created=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(prefix+"/submit").contentType("application/json").content(body)).andReturn().getResponse();assertEquals(200,created.getStatus());assertEquals("no-store",created.getHeader("Cache-Control"));assertEquals(1,cmsCount());
        org.springframework.mock.web.MockHttpServletResponse status=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(prefix+"/status").contentType("application/json").content(materializationStatusJson())).andReturn().getResponse();assertEquals(200,status.getStatus());assertTrue(status.getContentAsString().contains("CREATION_COMMITTED"));assertEquals("no-cache",status.getHeader("Pragma"));
        assertEquals(200,c.submit(json(body)).getStatusCodeValue());assertEquals(1,cmsCount());assertEquals(405,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(prefix+"/submit")).andReturn().getResponse().getStatus());
    }
    @Test void materializerHttpLostResponseCanRecoverSameIdAfterModeChanged()throws Exception {
        materializationSetup();com.chengyinhub.web.controller.api.ApiWorkshopPaidMaterializationController c=materializationController();String body=materializationJson();
        org.springframework.test.util.ReflectionTestUtils.setField(c,"materialization",new PaidProfessionalMaterializationHandling(){public Map<String,Object> materialize(long actor,PaidProfessionalMaterializationCommand intent){materializer.materialize(actor,intent);throw new IllegalStateException("synthetic connection lost after commit");}public Map<String,Object> status(long actor,String id){return materializer.status(actor,id);}});
        org.springframework.http.ResponseEntity<com.chengyinhub.common.core.domain.AjaxResult> unknown=c.submit(json(body));assertEquals(503,unknown.getStatusCodeValue());assertEquals("MATERIALIZATION_OUTCOME_NOT_CONFIRMED",unknown.getBody().get("errorCode"));assertEquals(1,cmsCount());jdbc.update("update cms_topic set product_type=2 where id=81");
        assertEquals(200,c.status(json(materializationStatusJson())).getStatusCodeValue());assertEquals(1,cmsCount());
    }
    @Test void materializerHttpDefaultsAndIdentityDoNotReachWriter()throws Exception {
        materializationSetup();com.chengyinhub.web.controller.api.ApiWorkshopPaidMaterializationController c=materializationController();String body=materializationJson();org.springframework.test.util.ReflectionTestUtils.setField(c,"materialization",null);
        assertEquals("PAID_MATERIALIZATION_NOT_ENABLED",c.submit(json(body)).getBody().get("errorCode"));assertEquals(503,c.status(json(materializationStatusJson())).getStatusCodeValue());org.springframework.security.core.context.SecurityContextHolder.clearContext();assertEquals(401,c.submit(json(body)).getStatusCodeValue());noMaterializedRows();
    }
    @Test void materializerHttpRejectsUnknownNestedDuplicateNullAndCoercedFields()throws Exception {
        materializationSetup();com.chengyinhub.web.controller.api.ApiWorkshopPaidMaterializationController c=materializationController();String body=materializationJson();
        for(String bad:Arrays.asList(body.replaceFirst("\\{","{\"owner\":7,"),body.replaceFirst("\\{","{\"requestId\":\""+MATERIALIZATION_ID+"\","),body.replace("\"ownedDraftId\":"+materializedOwned,"\"ownedDraftId\":\""+materializedOwned+"\""),body.replace("\"currentDraftRevision\":1","\"currentDraftRevision\":1.0"),body.replace("\"confirmedMode\":\"CITY_ORIENTATION\"","\"confirmedMode\":null"),body.replace("\"confirmedMode\":\"CITY_ORIENTATION\"","\"confirmedMode\":{}"),body+"{}"))assertEquals(400,c.submit(json(bad)).getStatusCodeValue(),bad);
        noMaterializedRows();
    }
    @Test void materializerHttpBoundsDeclaredAndActualBodyBeforeParsing()throws Exception {
        materializationSetup();com.chengyinhub.web.controller.api.ApiWorkshopPaidMaterializationController c=materializationController();String body=materializationJson();
        org.springframework.mock.web.MockHttpServletRequest unknown=new org.springframework.mock.web.MockHttpServletRequest(){public long getContentLengthLong(){return -1;}public jakarta.servlet.ServletInputStream getInputStream(){throw new AssertionError("must reject before stream");}};unknown.setContentType("application/json");assertEquals(400,c.submit(unknown).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest mismatch=new org.springframework.mock.web.MockHttpServletRequest(){public long getContentLengthLong(){return 2;}};mismatch.setContentType("application/json");mismatch.setContent(new byte[4097]);assertEquals(400,c.submit(mismatch).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest query=json(body);query.setQueryString("owner=7");assertEquals(400,c.submit(query).getStatusCodeValue());org.springframework.mock.web.MockHttpServletRequest chunked=json(body);chunked.addHeader("Transfer-Encoding","chunked");assertEquals(400,c.submit(chunked).getStatusCodeValue());noMaterializedRows();
    }
    @Test void materializerHttpDeepMalformedUtf8OrInvalidModeCannotDispatch()throws Exception {
        materializationSetup();com.chengyinhub.web.controller.api.ApiWorkshopPaidMaterializationController c=materializationController();String body=materializationJson();String nested=new String(new char[1000]).replace('\0','[')+"0"+new String(new char[1000]).replace('\0',']');
        assertEquals(400,c.submit(json(body.replace("\"confirmedMode\":\"CITY_ORIENTATION\"","\"confirmedMode\":"+nested))).getStatusCodeValue());assertEquals(400,c.submit(json(body.replace("CITY_ORIENTATION","UNRESOLVED"))).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest invalid=json(body);invalid.setContent(new byte[]{'{','"','x','"',':','"',(byte)0xc0,(byte)0xaf,'"','}'});assertEquals(400,c.submit(invalid).getStatusCodeValue());noMaterializedRows();
    }

    private PaidProfessionalOperationService operations(){return new PaidProfessionalOperationService(session,manager,installClock,PaidProfessionalMaterializationService.Policy.REVIEWED_GUARDED_CMS_TEXT_V1);}
    @Test void operationNotFoundIsNeverPermissionToForgetOrReplace()throws Exception {
        materializationSetup();Map<String,Object> status=operations().status(7,MATERIALIZATION_ID);assertEquals("NOT_FOUND",status.get("state"));assertEquals(false,status.get("safeToReplace"));assertEquals(0,jdbc.queryForObject("select count(*) from w18_paid_professional_operation",Integer.class));noMaterializedRows();
    }
    @Test void operationCreateCommitsCmsSidecarAndCreatedStateExactlyOnce()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();Map<String,Object> first=operations().submit(7,c);assertEquals("CREATED",first.get("state"));assertEquals(false,first.get("safeToReplace"));assertEquals(first,operations().submit(7,c));assertEquals(1,cmsCount());assertEquals(1,provenanceCount());assertEquals(1,jdbc.queryForObject("select count(*) from w18_paid_professional_operation",Integer.class));assertEquals("CREATED",jdbc.queryForObject("select state from w18_paid_professional_operation",String.class));
    }
    @Test void operationCancellationTombstoneBeforeFirstSubmitBlocksEveryDelayedPath()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();Map<String,Object> cancelled=operations().cancel(7,c);assertEquals("CANCELLED",cancelled.get("state"));assertEquals(true,cancelled.get("safeToReplace"));assertEquals(cancelled,operations().cancel(7,c));assertEquals(cancelled,operations().submit(7,c));assertThrows(RuntimeException.class,()->materializer.materialize(7,c));noMaterializedRows();
    }
    @Test void operationCancelledIdCannotBeReboundButNewConfirmedIdCanCreate()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();operations().cancel(7,c);
        assertThrows(RuntimeException.class,()->operations().submit(7,materialization(MATERIALIZATION_ID,82,PaidProfessionalMaterializationCommand.Mode.FREE_EXPLORATION)));
        Map<String,Object> fresh=operations().submit(7,materialization("10000000-0000-4000-8000-000000000002",82,PaidProfessionalMaterializationCommand.Mode.FREE_EXPLORATION));assertEquals("CREATED",fresh.get("state"));assertEquals(1,cmsCount());assertEquals(2,jdbc.queryForObject("select count(*) from w18_paid_professional_operation",Integer.class));
    }
    @Test void operationCasRejectionIsDurableAndLateRetryCannotReviveIt()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();jdbc.update("update content_draft set version=2 where id=91");Map<String,Object> rejected=operations().submit(7,c);assertEquals("REJECTED",rejected.get("state"));assertEquals(true,rejected.get("safeToReplace"));assertEquals("CURRENT_SOURCE_OR_TARGET_UNAVAILABLE",rejected.get("rejectionCode"));noMaterializedRows();
        jdbc.update("update content_draft set version=1 where id=91");assertEquals(rejected,operations().submit(7,c));assertThrows(RuntimeException.class,()->materializer.materialize(7,c));noMaterializedRows();
        assertEquals("CREATED",operations().submit(7,materialization("10000000-0000-4000-8000-000000000002",81,PaidProfessionalMaterializationCommand.Mode.CITY_ORIENTATION)).get("state"));
    }
    @Test void operationStorageFailureKeepsPreparedUnknownRatherThanClaimingRejection()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();probe.afterUpdate=id->{if(id.endsWith("insertProvenance"))throw new IllegalStateException("synthetic storage interruption");};assertThrows(RuntimeException.class,()->operations().submit(7,c));noMaterializedRows();
        Map<String,Object> unknown=operations().status(7,MATERIALIZATION_ID);assertEquals("PREPARED",unknown.get("state"));assertEquals(false,unknown.get("safeToReplace"));probe.afterUpdate=id->{};assertEquals("CREATED",operations().submit(7,c).get("state"));assertEquals(1,cmsCount());
    }
    @Test void operationPreparedFailureCanBeCancelledWithoutUndoingLicenseOrQuota()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();probe.afterUpdate=id->{if(id.endsWith("insertProvenance"))throw new IllegalStateException("synthetic storage interruption");};assertThrows(RuntimeException.class,()->operations().submit(7,c));probe.afterUpdate=id->{};
        assertEquals("CANCELLED",operations().cancel(7,c).get("state"));assertEquals("CANCELLED",operations().submit(7,c).get("state"));assertEquals(1,count());assertEquals(1,installedCount());assertEquals(1,jdbc.queryForObject("select count(*) from w18_module_install_usage",Integer.class));noMaterializedRows();
    }
    @Test void operationCancelAfterCommittedCreationReportsCreatedAndDoesNotUndoCms()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();Map<String,Object> created=operations().submit(7,c);jdbc.update("update oms_order set aftersale_status=4");jdbc.update("update cms_topic set product_type=2 where id=81");assertEquals(created,operations().cancel(7,c));assertEquals(created,operations().status(7,MATERIALIZATION_ID));assertEquals(1,cmsCount());assertEquals(1,provenanceCount());
    }
    @Test void operationConcurrentCancelWaitsForPhysicalCreateCommitAndCannotLie()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();CountDownLatch changed=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
        probe.afterUpdate=id->{if(id.endsWith("PaidProfessionalOperationStore.finish")){changed.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){throw new RuntimeException(e);}}};
        try{Future<Map<String,Object>> create=workers.submit(()->operations().submit(7,c));assertTrue(changed.await(5,TimeUnit.SECONDS));assertEquals(0,cmsCount());assertEquals(0,provenanceCount());assertEquals("PREPARED",jdbc.queryForObject("select state from w18_paid_professional_operation",String.class));Future<Map<String,Object>> cancel=workers.submit(()->operations().cancel(7,c));assertThrows(TimeoutException.class,()->cancel.get(200,TimeUnit.MILLISECONDS));release.countDown();assertEquals(create.get(5,TimeUnit.SECONDS),cancel.get(5,TimeUnit.SECONDS));assertEquals(1,cmsCount());}finally{release.countDown();workers.shutdownNow();}
    }
    @Test void operationConcurrentDuplicateSubmitSharesOneOperationAndOneCreation()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();ExecutorService workers=Executors.newFixedThreadPool(2);try{Future<Map<String,Object>>a=workers.submit(()->operations().submit(7,c)),b=workers.submit(()->operations().submit(7,c));assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}finally{workers.shutdownNow();}assertEquals(1,cmsCount());assertEquals(1,provenanceCount());
    }
    @Test void operationForeignActorCannotCancelAnotherOwnerOrReadItsReceipt()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();operations().cancel(8,c);assertEquals("NOT_FOUND",operations().status(7,MATERIALIZATION_ID).get("state"));assertEquals("CREATED",operations().submit(7,c).get("state"));assertEquals("CANCELLED",operations().status(8,MATERIALIZATION_ID).get("state"));assertEquals(1,cmsCount());
    }
    @Test void operationDamagedRecordDoesNotBecomeMissingOrResettable()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();operations().cancel(7,c);jdbc.update("update w18_paid_professional_operation set command_data=?",new byte[]{127,127,127,127});assertThrows(RuntimeException.class,()->operations().status(7,MATERIALIZATION_ID));assertThrows(RuntimeException.class,()->operations().submit(7,c));noMaterializedRows();
    }
    @Test void operationDisabledOrInactiveAccountCannotEvenPrepare()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();assertThrows(RuntimeException.class,()->new PaidProfessionalOperationService(session,manager,installClock).submit(7,c));jdbc.update("update ums_member set status=0 where id=7");assertThrows(RuntimeException.class,()->operations().cancel(7,c));assertEquals(0,jdbc.queryForObject("select count(*) from w18_paid_professional_operation",Integer.class));noMaterializedRows();
    }

    private com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalOperationController operationController(){
        controller();com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalOperationController c=new com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalOperationController();org.springframework.test.util.ReflectionTestUtils.setField(c,"operations",operations());return c;
    }
    @Test void operationHttpCancelBeforeLateSubmitReturnsRetainedTerminalNoStore()throws Exception {
        materializationSetup();String body=materializationJson();com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalOperationController c=operationController();org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(c).build();String root="/api/workshop/purchased/professional-operations";
        org.springframework.mock.web.MockHttpServletResponse cancelled=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(root+"/cancel").contentType("application/json").content(body)).andReturn().getResponse();assertEquals(200,cancelled.getStatus());assertEquals("no-store",cancelled.getHeader("Cache-Control"));assertTrue(cancelled.getContentAsString().contains("CANCELLED"));
        assertEquals(200,c.submit(json(body)).getStatusCodeValue());assertEquals("CANCELLED",((Map<?,?>)c.status(json(materializationStatusJson())).getBody().get("data")).get("state"));noMaterializedRows();assertEquals(405,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(root+"/cancel")).andReturn().getResponse().getStatus());
    }
    @Test void operationHttpCommitThenTransportLossRecoversAuthoritativeCreation()throws Exception {
        materializationSetup();String body=materializationJson();com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalOperationController c=operationController();PaidProfessionalOperationService actual=operations();
        org.springframework.test.util.ReflectionTestUtils.setField(c,"operations",new PaidProfessionalOperationHandling(){public Map<String,Object>submit(long a,PaidProfessionalMaterializationCommand cmd){actual.submit(a,cmd);throw new IllegalStateException("synthetic post-commit response loss");}public Map<String,Object>cancel(long a,PaidProfessionalMaterializationCommand cmd){return actual.cancel(a,cmd);}public Map<String,Object>status(long a,String id){return actual.status(a,id);}public Map<String,Object>history(long a,String license,String before){return actual.history(a,license,before);}});
        assertEquals("OPERATION_OUTCOME_NOT_CONFIRMED",c.submit(json(body)).getBody().get("errorCode"));assertEquals(1,cmsCount());assertEquals("CREATED",((Map<?,?>)c.status(json(materializationStatusJson())).getBody().get("data")).get("state"));
    }
    @Test void operationHttpCancelDoesNotAcceptBareUuidOwnerOrUnknownBodyLength()throws Exception {
        materializationSetup();String body=materializationJson();com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalOperationController c=operationController();assertEquals(400,c.cancel(json(materializationStatusJson())).getStatusCodeValue());assertEquals(400,c.cancel(json(body.replaceFirst("\\{","{\"owner\":7,"))).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest unknown=new org.springframework.mock.web.MockHttpServletRequest(){public long getContentLengthLong(){return -1;}public jakarta.servlet.ServletInputStream getInputStream(){throw new AssertionError("unknown body must not be read");}};unknown.setContentType("application/json");assertEquals(400,c.cancel(unknown).getStatusCodeValue());
        String deep=new String(new char[1000]).replace('\0','[')+"0"+new String(new char[1000]).replace('\0',']');assertEquals(400,c.submit(json(body.replace("\"confirmedMode\":\"CITY_ORIENTATION\"","\"confirmedMode\":"+deep))).getStatusCodeValue());assertEquals(0,jdbc.queryForObject("select count(*) from w18_paid_professional_operation",Integer.class));
    }
    @Test void operationHttpAbsentHandlerAndUnauthenticatedRequestsCannotPrepare()throws Exception {
        materializationSetup();String body=materializationJson();com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalOperationController c=operationController();org.springframework.test.util.ReflectionTestUtils.setField(c,"operations",null);assertEquals("PAID_PROFESSIONAL_OPERATION_NOT_ENABLED",c.cancel(json(body)).getBody().get("errorCode"));org.springframework.security.core.context.SecurityContextHolder.clearContext();assertEquals(401,c.submit(json(body)).getStatusCodeValue());assertEquals(0,jdbc.queryForObject("select count(*) from w18_paid_professional_operation",Integer.class));
    }
    @Test void operationActualCmsIdIsGeneratedIndependentlyOfInstallationId()throws Exception {
        materializationSetup();jdbc.execute("alter table cms_member_template alter column id restart with 700");Map<String,Object> result=operations().submit(7,materialization());Map<?,?> created=(Map<?,?>)result.get("creation");assertEquals(700L,created.get("templateId"));assertNotEquals(materializedOwned,created.get("templateId"));assertEquals(materializedOwned,created.get("ownedDraftId"));
    }

    private void operationTerminalWinsBeforeFailedAttemptFinalizes(boolean cancel)throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();CountDownLatch rolledBack=new CountDownLatch(1),release=new CountDownLatch(1);java.util.concurrent.atomic.AtomicBoolean once=new java.util.concurrent.atomic.AtomicBoolean(true);ExecutorService workers=Executors.newFixedThreadPool(2);
        probe.afterUpdate=id->{if(id.endsWith("insertProvenance")&&once.compareAndSet(true,false)){
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCompletion(int status){if(status==STATUS_ROLLED_BACK){rolledBack.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){throw new RuntimeException(e);}}}});
            // Simulate a storage readback mismatch; the kernel then throws its own domain rejection,
            // rather than an interceptor exception wrapped as a database/unknown failure.
            jdbc.update("update cms_member_template set title='synthetic readback mismatch'");
        }};
        try{Future<Map<String,Object>> first=workers.submit(()->operations().submit(7,c));assertTrue(rolledBack.await(5,TimeUnit.SECONDS));assertEquals(0,cmsCount());assertEquals("PREPARED",jdbc.queryForObject("select state from w18_paid_professional_operation",String.class));Future<Map<String,Object>> winner=workers.submit(()->cancel?operations().cancel(7,c):operations().submit(7,c));Map<String,Object> terminal=winner.get(5,TimeUnit.SECONDS);assertEquals(cancel?"CANCELLED":"CREATED",terminal.get("state"));release.countDown();assertEquals(terminal,first.get(5,TimeUnit.SECONDS));assertEquals(cancel?0:1,cmsCount());}finally{release.countDown();workers.shutdownNow();}
    }
    @Test void operationRejectedAttemptCannotOverwriteConcurrentSuccessfulRetry()throws Exception {operationTerminalWinsBeforeFailedAttemptFinalizes(false);}
    @Test void operationRejectedAttemptCannotOverwriteConcurrentCancellation()throws Exception {operationTerminalWinsBeforeFailedAttemptFinalizes(true);}

    @Test void operationHistoryDiscoversDurableCreationWithoutLocalRequestId()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand c=materialization();operations().submit(7,c);jdbc.update("update oms_order set aftersale_status=4");jdbc.update("update cms_topic set product_type=2 where id=81");
        PaidProfessionalOperationService fresh=operations();Map<String,Object> page=fresh.history(7,"w18-paid-11",null);List<?> rows=(List<?>)page.get("items");assertEquals(1,rows.size());Map<?,?> item=(Map<?,?>)rows.get(0),command=(Map<?,?>)item.get("command");assertEquals(MATERIALIZATION_ID,command.get("requestId"));assertEquals(materializedOwned,command.get("ownedDraftId"));assertEquals("CREATED",fresh.status(7,(String)command.get("requestId")).get("state"));assertEquals("OWNER_OPERATION_INTENT_REFERENCES_ONLY",page.get("scope"));assertFalse(item.containsKey("state"));assertFalse(item.containsKey("safeToReplace"));
    }
    @Test void operationHistoryDiscoveryDoesNotDispatchOrReturnProtectedBody()throws Exception {
        materializationSetup();operations().cancel(7,materialization());int before=jdbc.queryForObject("select count(*) from w18_paid_professional_operation",Integer.class);String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(operations().history(7,"w18-paid-11",null));
        for(String absent:Arrays.asList("sourceJson","componentJson","merchantGuide","termsDocument","synthetic_transaction","synthetic_app"))assertFalse(json.contains(absent));assertEquals(before,jdbc.queryForObject("select count(*) from w18_paid_professional_operation",Integer.class));noMaterializedRows();
    }
    @Test void operationHistoryScannedCursorAdvancesThroughEmptyFilteredPages()throws Exception {
        materializationSetup();PaidProfessionalMaterializationCommand original=materialization();for(int i=1;i<=55;i++){
            String id=String.format(java.util.Locale.ROOT,"20000000-0000-4000-8000-%012d",i);PaidProfessionalMaterializationCommand other=new PaidProfessionalMaterializationCommand(id,original.installationRequestId,original.ownedDraftId,"w18-paid-22",original.moduleId,original.purchasedVersionId,original.contentHash,original.termsHash,original.componentHash,original.currentDraftRevision,original.currentDraftPayloadHash,original.targetTopicId,original.targetOwnerModeFingerprint,original.confirmedMode);operations().cancel(7,other);
        }
        Map<String,Object> first=operations().history(7,"w18-paid-11",null);assertEquals(0,((List<?>)first.get("items")).size());assertEquals(50,first.get("scannedCount"));assertEquals(true,first.get("hasMore"));String cursor=(String)first.get("nextBeforeOperationKey");assertNotNull(cursor);
        Map<String,Object> second=operations().history(7,"w18-paid-11",cursor);assertEquals(0,((List<?>)second.get("items")).size());assertEquals(5,second.get("scannedCount"));assertEquals(false,second.get("hasMore"));assertNull(second.get("nextBeforeOperationKey"));noMaterializedRows();
    }
    @Test void operationHistoryForeignOwnerAndForeignLicenseDoNotLeakReferences()throws Exception {
        materializationSetup();operations().cancel(7,materialization());Map<String,Object> foreign=operations().history(8,"w18-paid-11",null);assertEquals(0,foreign.get("scannedCount"));assertEquals(0,((List<?>)foreign.get("items")).size());assertEquals(0,((List<?>)operations().history(7,"w18-paid-22",null).get("items")).size());
    }
    @Test void operationHistoryCorruptRecordFailsInsteadOfSilentlySkippingPossibleMatch()throws Exception {
        materializationSetup();operations().cancel(7,materialization());jdbc.update("update w18_paid_professional_operation set command_hash=?",new String(new char[64]).replace('\0','0'));assertThrows(RuntimeException.class,()->operations().history(7,"w18-paid-11",null));assertEquals(1,jdbc.queryForObject("select count(*) from w18_paid_professional_operation",Integer.class));
    }
    @Test void operationHistoryDisabledInactiveAndMalformedCursorAreRejected()throws Exception {
        materializationSetup();assertThrows(RuntimeException.class,()->operations().history(7,"w18-paid-11","bad"));assertThrows(RuntimeException.class,()->new PaidProfessionalOperationService(session,manager,installClock).history(7,"w18-paid-11",null));jdbc.update("update ums_member set status=0 where id=7");assertThrows(RuntimeException.class,()->operations().history(7,"w18-paid-11",null));
    }
    @Test void operationHistoryHttpIsExactOwnerReadAndNoStore()throws Exception {
        materializationSetup();operations().submit(7,materialization());com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalOperationController c=operationController();org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(c).build();
        org.springframework.mock.web.MockHttpServletResponse response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workshop/purchased/professional-operations/history").contentType("application/json").content("{\"licenseId\":\"w18-paid-11\"}")).andReturn().getResponse();assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertTrue(response.getContentAsString().contains("w18-paid-professional-operation-history-v1"));assertFalse(response.getContentAsString().contains("Synthetic purchased content"));
    }
    @Test void operationHistoryHttpRejectsExtraOwnerNullCursorAndUnknownLength()throws Exception {
        materializationSetup();com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalOperationController c=operationController();for(String invalid:Arrays.asList("{}","{\"licenseId\":\"w18-paid-11\",\"owner\":7}","{\"licenseId\":\"w18-paid-11\",\"beforeOperationKey\":null}","{\"licenseId\":\"w18-paid-11\",\"licenseId\":\"w18-paid-22\"}"))assertEquals(400,c.history(json(invalid)).getStatusCodeValue());
        org.springframework.mock.web.MockHttpServletRequest unknown=new org.springframework.mock.web.MockHttpServletRequest(){public long getContentLengthLong(){return -1;}public jakarta.servlet.ServletInputStream getInputStream(){throw new AssertionError("no unbounded history body");}};unknown.setContentType("application/json");assertEquals(400,c.history(unknown).getStatusCodeValue());noMaterializedRows();
    }

    @Test void operationHistoryWritePortsRetainExistingMemberDraftRateAnnotation()throws Exception {
        for(Class<?> type:new Class<?>[]{com.chengyinhub.web.controller.api.ApiWorkshopPaidProfessionalOperationController.class,com.chengyinhub.web.controller.api.ApiWorkshopPaidMaterializationController.class}){
            for(String method:type.getSimpleName().contains("ProfessionalOperation")?Arrays.asList("submit","cancel"):Collections.singletonList("submit")){
                com.chengyinhub.common.annotation.RateLimiter rate=type.getMethod(method,jakarta.servlet.http.HttpServletRequest.class).getAnnotation(com.chengyinhub.common.annotation.RateLimiter.class);
                assertNotNull(rate);assertEquals(60,rate.time());assertEquals(30,rate.count());assertEquals(com.chengyinhub.common.enums.LimitType.MEMBER,rate.limitType());
            }
        }
    }

    // New regression scope uses ordinary typed input + isolated persistence only. These methods
    // do not call signed(), NotificationParser, callback handling, a network or any provider.
    @Test void precisionRejectsEveryUnsupportedFractionBeforeOpeningTransactionOrWriting(){
        java.util.concurrent.atomic.AtomicInteger connections=new java.util.concurrent.atomic.AtomicInteger();connectionProbe.onConnect=connections::incrementAndGet;
        for(int nanos:new int[]{1,999,1000,999999,123000000,999999999})assertFalse(service.accept(event("W18_synthetic_order_0001","synthetic_transaction_1","synthetic_app","synthetic_merchant",1000,NOW.plusNanos(nanos))));
        assertFalse(service.accept(event("W18_synthetic_order_0001","synthetic_transaction_1","synthetic_app","synthetic_merchant",1000,NOW.minusNanos(1))));
        assertEquals(0,connections.get());assertEquals(0,probe.orderWrites);connectionProbe.onConnect=()->{};none();assertNull(jdbc.queryForObject("select payment_time from oms_order where id=1",Timestamp.class));
    }
    @Test void precisionWholeSecondInputUsesActualLegacyOmsScaleZeroAndReplaysExactly(){
        assertEquals(0,jdbc.queryForObject("select datetime_precision from information_schema.columns where table_name='oms_order' and column_name='payment_time'",Integer.class));
        assertEquals(6,jdbc.queryForObject("select datetime_precision from information_schema.columns where table_name='w18_paid_license' and column_name='paid_at'",Integer.class));
        assertTrue(service.accept(event()));assertTrue(service.accept(event()));assertEquals(1,count());assertEquals(1,probe.orderWrites);
        assertEquals(NOW,jdbc.queryForObject("select payment_time from oms_order where id=1",Timestamp.class).toInstant());assertEquals(NOW,jdbc.queryForObject("select paid_at from w18_paid_license",Timestamp.class).toInstant());
        assertEquals(NOW,service.readOwned(7,"w18-paid-11").getValidFrom());assertNotNull(metadata().detail(7,"w18-paid-11"));assertEquals(1,metadata().list(7,null).getItems().size());
    }
    @Test void precisionIntegralOffsetTimeIsTheSameInstantWithoutCallbackParsing(){
        Instant offset=java.time.OffsetDateTime.parse("2026-10-05T08:00:00+08:00").toInstant();assertEquals(NOW,offset);
        assertTrue(service.accept(event("W18_synthetic_order_0001","synthetic_transaction_1","synthetic_app","synthetic_merchant",1000,offset)));assertTrue(service.accept(event()));assertEquals(1,count());
    }
    @Test void precisionRejectedFractionCannotMutateAlreadyValidReceipt(){
        assertTrue(service.accept(event()));Timestamp paid=jdbc.queryForObject("select paid_at from w18_paid_license",Timestamp.class);int writes=probe.orderWrites;
        assertFalse(service.accept(event("W18_synthetic_order_0001","synthetic_transaction_1","synthetic_app","synthetic_merchant",1000,NOW.plusNanos(123000000))));
        assertEquals(writes,probe.orderWrites);assertEquals(1,count());assertEquals(paid,jdbc.queryForObject("select paid_at from w18_paid_license",Timestamp.class));assertNotNull(service.readOwned(7,"w18-paid-11"));
    }
    @Test void precisionIssueClockKeepsExplicitMicrosWithoutChangingWholeSecondPayment(){
        PersistedPaidLicenseService fractionalClock=enabled(Clock.fixed(NOW.plusNanos(123456789),ZoneOffset.UTC));assertTrue(fractionalClock.accept(event()));
        assertEquals(NOW,jdbc.queryForObject("select payment_time from oms_order where id=1",Timestamp.class).toInstant());assertEquals(NOW.plusNanos(123456000),jdbc.queryForObject("select issued_at from w18_paid_license",Timestamp.class).toInstant());assertTrue(fractionalClock.accept(event()));
    }
    @Test void precisionHistoricalFractionalLicenseRemainsUnavailableAndUnchanged(){
        assertTrue(service.accept(event()));Timestamp historic=Timestamp.from(NOW.plusNanos(123000000));jdbc.update("update w18_paid_license set paid_at=?",historic);int writes=probe.orderWrites;
        assertFalse(service.accept(event()));assertThrows(IllegalArgumentException.class,()->service.readOwned(7,"w18-paid-11"));assertEquals(Integer.valueOf(404),assertThrows(com.chengyinhub.common.exception.ServiceException.class,()->metadata().detail(7,"w18-paid-11")).getCode());
        assertEquals(1,count());assertEquals(writes,probe.orderWrites);assertEquals(historic,jdbc.queryForObject("select paid_at from w18_paid_license",Timestamp.class));assertEquals(NOW,jdbc.queryForObject("select payment_time from oms_order where id=1",Timestamp.class).toInstant());
    }
    @Test void precisionHistoricalEqualFractionsDoNotBecomeSupportedByWiderOmsColumn(){
        assertTrue(service.accept(event()));jdbc.execute("alter table oms_order alter column payment_time timestamp(6)");Timestamp historic=Timestamp.from(NOW.plusNanos(123000000));jdbc.update("update oms_order set payment_time=?",historic);jdbc.update("update w18_paid_license set paid_at=?",historic);
        assertFalse(service.accept(event()));assertThrows(IllegalArgumentException.class,()->service.readOwned(7,"w18-paid-11"));assertEquals(historic,jdbc.queryForObject("select payment_time from oms_order where id=1",Timestamp.class));assertEquals(1,count());
    }
    @Test void precisionHistoricalWholeSecondMismatchIsNotRepaired(){
        assertTrue(service.accept(event()));jdbc.update("update oms_order set payment_time=?",Timestamp.from(NOW.plusSeconds(1)));
        assertFalse(service.accept(event()));assertThrows(IllegalArgumentException.class,()->service.readOwned(7,"w18-paid-11"));assertEquals(NOW.plusSeconds(1),jdbc.queryForObject("select payment_time from oms_order where id=1",Timestamp.class).toInstant());assertEquals(NOW,jdbc.queryForObject("select paid_at from w18_paid_license",Timestamp.class).toInstant());
    }
    @Test void precisionPhysicalReceiptMismatchBeforeReturnRollsBackBothWrites(){
        probe.afterUpdate=id->{if(id.endsWith("insertLicense"))jdbc.update("update w18_paid_license set paid_at=?",Timestamp.from(NOW.plusNanos(123000000)));};
        assertFalse(service.accept(event()));none();assertNull(jdbc.queryForObject("select payment_time from oms_order where id=1",Timestamp.class));
    }
    @Test void precisionPhysicalOmsMismatchBeforeReturnRollsBackBothWrites(){
        probe.afterUpdate=id->{if(id.endsWith("insertLicense"))jdbc.update("update oms_order set payment_time=?",Timestamp.from(NOW.plusSeconds(1)));};
        assertFalse(service.accept(event()));none();assertNull(jdbc.queryForObject("select payment_transaction_id from oms_order where id=1",String.class));
    }
    @Test void precisionPhysicalIssueTimePrecisionMismatchRollsBackBothWrites(){
        jdbc.execute("alter table w18_paid_license alter column issued_at timestamp(0)");PersistedPaidLicenseService fractionalClock=enabled(Clock.fixed(NOW.plusNanos(123456000),ZoneOffset.UTC));
        assertFalse(fractionalClock.accept(event()));none();
    }
    @Test void precisionFirstWriteReadbackStillChecksReceiptSnapshotAndStatus(){
        probe.afterUpdate=id->{if(id.endsWith("insertLicense"))jdbc.update("update w18_paid_license set state='REVOKED'");};assertFalse(service.accept(event()));none();
    }

}

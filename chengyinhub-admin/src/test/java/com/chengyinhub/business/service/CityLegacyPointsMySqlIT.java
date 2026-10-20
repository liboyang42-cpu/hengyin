package com.chengyinhub.business.service;

import com.chengyinhub.business.city.*;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.domain.dto.RegistrationOrderResult;
import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.impl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.city.CityContracts.*;
import static com.chengyinhub.business.city.CityRuntimeTransactionalService.*;

/** Real existing registration create/cancel and cart settlement services, production XML, real transactions.
 * Reuses established schema/service fixtures; ancillary WeChat, entitlement and growth services stay
 * mocked exactly as in the existing funds fixture. No providers are contacted and no live authority,
 * full refund-provider lifecycle, HTTP routing or production CITY event allocation is certified. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class CityLegacyPointsMySqlIT {
    private final IsolatedMySqlSchema mysql = new IsolatedMySqlSchema();
    private DataSource source;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private RegistrationFundsMybatisIntegrationTest funds;
    private CityRuntimeTransactionalService city;
    private final Instant now=Instant.parse("2026-10-02T12:00:00Z");
    private Participation team;
    private Property property;
    private Offer offer;

    @BeforeEach void setup() throws Exception {
        source=mysql.open("legacy"); jdbc=new JdbcTemplate(source); manager=new DataSourceTransactionManager(source);
        new PiiCryptoService("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","isolated-city-legacy-test-only").register();
        funds=new RegistrationFundsMybatisIntegrationTest(); funds.wireServices(source); funds.resetSchema();
        // The reusable funds fixture historically uses BIGINT; city intentionally requires actual signed INT.
        jdbc.execute("ALTER TABLE ums_member MODIFY point INT NOT NULL DEFAULT 0");
        jdbc.update("INSERT INTO ums_member(id,status,point,del_flag) VALUES(8,1,40,0),(9,1,0,0)");
        mysql.apply("20261002_city_visit_persistence");
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/city/CityRuntimeMapper.xml"),new ClassPathResource("mapper/city/CityPointsMutationMapper.xml"));
        SqlSessionTemplate sessions=new SqlSessionTemplate(factory.getObject());
        city=new CityRuntimeTransactionalService(Mode.ISOLATED_PERSISTENCE_EVALUATION,sessions.getMapper(CityRuntimeMapper.class),
                sessions.getMapper(CityPointsMutationMapper.class),manager,Clock.fixed(now,ZoneOffset.UTC),
                new CityLedgerMapping("CITY_VISIT_SETTLEMENT",210000001,"synthetic-test-allocation-only"));
        Rules rules=new Rules("rules",String.join("",Collections.nCopies(64,"a")),"policy",100,100,3,30);
        Board board=new Board("legacy-board",rules,BoardStatus.OPEN,1,now.minusSeconds(60),now.plusSeconds(600));
        team=new Participation(board.boardId,"visitors",1,new TreeSet<Long>(Arrays.asList(7L,8L)),true);
        Participation operators=new Participation(board.boardId,"operators",1,new TreeSet<Long>(Collections.singleton(9L)),true);
        property=new Property(board.boardId,"property","operators",1,now.minusSeconds(60),true);
        RoundGrant grant=new RoundGrant(board.boardId,"visitors",rules.releaseId,1,now.minusSeconds(30),now.plusSeconds(500),"dice-4");
        Map<Long,Long> rewards=new LinkedHashMap<>();rewards.put(7L,7L);rewards.put(8L,3L);
        offer=new Offer("offer",board.boardId,"property","operators",1,"visitors",1,"service","visit-scope","task","reward-scope",rules,"availability",7,12,rewards,
                new FeeAllocation("fixture-fee-approval","policy",operators,Collections.singletonMap(9L,12L)),now.plusSeconds(400));
        city.createBoard(board);city.createBudget(board.boardId,"budget","fixture-budget-approval",1,20);
        city.roll(board.boardId,team,7,1,0,"roll",grant);
        city.acceptVisit(board.boardId,team,7,1,"accept",offer,property,"availability",grant,
                new PayerConsent("consent",7,offer.fingerprint,12,now.plusSeconds(300)),"budget",1);
    }
    @AfterEach void cleanup() throws Exception {
        try {mysql.close();} finally {ReflectionTestUtils.setField(PiiCryptoService.class,"instance",null);}
    }
    private Result complete() {
        TaskProof proof=new TaskProof("fixture-proof","legacy-board","visitors","property","task",offer.visitKey(),7,1,now,now.plusSeconds(250),true,true);
        return city.completeVisit("legacy-board",team,7,1,2,"complete",offer.visitKey(),proof,property);
    }
    private long number(String sql) {return jdbc.queryForObject(sql,Long.class);}
    private void settled(long payer) {
        assertEquals(payer,number("SELECT point FROM ums_member WHERE id=7"));
        assertEquals(43,number("SELECT point FROM ums_member WHERE id=8"));assertEquals(12,number("SELECT point FROM ums_member WHERE id=9"));
        assertEquals(3,number("SELECT COUNT(*) FROM city_point_effect_member"));
        assertEquals(10,number("SELECT consumed FROM city_reward_budget"));assertEquals(0,number("SELECT reserved FROM city_reward_budget"));
        assertEquals(3,number("SELECT COUNT(*) FROM ums_member_points_detail WHERE event_type=210000001"));
    }
    private RegistrationRequest request(String id) {
        RegistrationRequest request=new RegistrationRequest();request.setOwnerType(2);request.setOwnerId(55L);request.setTicketId(9L);
        request.setIsUsePoint(1);request.setRealName("隔离测试");request.setPhone("13800000000");request.setRequestId(id);return request;
    }
    private void prepareTicket() {jdbc.update("UPDATE oms_ticket SET price=1,remaining_inventory=100 WHERE id=9");}
    /** Deterministically holds the real legacy point write until the city transaction is actually waiting. */
    private void legacyBeforeCity(Callable<?> work) throws Exception {
        CountDownLatch wrote=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> legacy=pool.submit(()->new TransactionTemplate(manager).execute(status->{
                try {Object result=work.call();wrote.countDown();await(release);return result;}catch(Exception error){throw new IllegalStateException(error);}
            }));
            assertTrue(wrote.await(10,TimeUnit.SECONDS));
            Future<Result> completing=pool.submit(()->{started.countDown();return complete();});
            assertTrue(started.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->completing.get(150,TimeUnit.MILLISECONDS));
            release.countDown();legacy.get(15,TimeUnit.SECONDS);assertFalse(completing.get(15,TimeUnit.SECONDS).replay);
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void registrationCreationHoldsPointsBeforeCityAndBothReceiptsPersist() throws Exception {
        prepareTicket();RegistrationRequest request=request("city-registration");
        legacyBeforeCity(()->funds.orderServiceForCityTest().createOrReplay(7L,request));
        settled(45);assertEquals(50,number("SELECT point_used FROM cms_registration WHERE request_id='city-registration'"));
        assertEquals(99,number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
        assertEquals(50,number("SELECT current_points FROM ums_member_points_detail WHERE member_id=7 AND event_type=210000001"));
        RegistrationOrderResult replay=funds.orderServiceForCityTest().createOrReplay(7L,request);
        assertNotNull(replay);settled(45);assertEquals(1,number("SELECT COUNT(*) FROM cms_registration WHERE request_id='city-registration'"));
    }
    @Test void actualCancellationReturnsPointsOnceWhileCityWaits() throws Exception {
        jdbc.update("UPDATE cms_registration SET owner_type=2,owner_id=55,ticket_id=9,point_used=20,points_returned=0 WHERE id=11");
        jdbc.update("UPDATE ums_member SET point=80 WHERE id=7");
        legacyBeforeCity(()->funds.registrationServiceForCityTest().cancelRegistration(11L,7L,"city-legacy-test"));
        settled(95);assertEquals(3,number("SELECT registration_status FROM cms_registration WHERE id=11"));
        assertEquals(1,number("SELECT points_returned FROM cms_registration WHERE id=11"));
        assertTrue(funds.registrationServiceForCityTest().cancelRegistration(11L,7L,"retry"));settled(95);
        assertEquals(1,number("SELECT remaining_inventory FROM oms_ticket WHERE id=9"));
    }
    @Test void actualCartRedemptionAndCitySerializeWithoutLostPointsOrStock() throws Exception {
        IUmsMemberCartService cart=cartFixture();
        legacyBeforeCity(()->cart.settleCart(7L,"test","301","","401"));
        settled(15);assertEquals(1,number("SELECT COUNT(*) FROM oms_order"));assertEquals(1,number("SELECT COUNT(*) FROM oms_order_item"));
        assertEquals(3,number("SELECT stock FROM pms_sku WHERE id=201"));assertEquals(0,number("SELECT COUNT(*) FROM ums_member_cart WHERE id=301"));
        assertEquals(20,number("SELECT current_points FROM ums_member_points_detail WHERE member_id=7 AND event_type=210000001"));
    }
    @Test void cartFailureAfterPointWriteRollsBackOrderStockAndPointsBeforeCity() throws Exception {
        IUmsMemberCartService cart=cartFixture();jdbc.update("INSERT INTO cart_delete_guard VALUES(301)");
        CountDownLatch started=new CountDownLatch(1),done=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            // Actual FK failure occurs at final cart delete, after all real cart/point/order/SKU writes.
            Future<?> broken=pool.submit(()->{started.countDown();assertThrows(RuntimeException.class,()->cart.settleCart(7L,"test","301","","401"));done.countDown();});
            assertTrue(started.await(5,TimeUnit.SECONDS));Future<Result> completed=pool.submit(this::complete);
            broken.get(15,TimeUnit.SECONDS);completed.get(15,TimeUnit.SECONDS);assertEquals(0,done.getCount());
            settled(95);assertEquals(0,number("SELECT COUNT(*) FROM oms_order"));assertEquals(0,number("SELECT COUNT(*) FROM oms_order_item"));
            assertEquals(5,number("SELECT stock FROM pms_sku WHERE id=201"));assertEquals(1,number("SELECT COUNT(*) FROM ums_member_cart WHERE id=301"));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    private IUmsMemberCartService cartFixture() throws Exception {
        CartSettlementTransactionalH2Test.Config config=new CartSettlementTransactionalH2Test.Config();
        // Reuse existing schema topology, not H2 connection or triggers. These are labelled prerequisite fixtures.
        for(String sql:CartSettlementTransactionalH2Test.Config.DDL.replace("clob","longtext")
                .replace("generated by default as identity","AUTO_INCREMENT").split(";")) if(!sql.trim().isEmpty())jdbc.execute(sql+" ENGINE=InnoDB");
        SqlSessionTemplate sessions=config.cartSqlSessionTemplate(config.cartSqlSessionFactory(source));
        UmsMemberCartServiceImpl target=config.umsMemberCartService(sessions.getMapper(UmsMemberCartMapper.class),
                config.viewMemberCartService(sessions.getMapper(ViewMemberCartMapper.class)),
                config.pmsProductService(sessions.getMapper(PmsProductMapper.class),sessions.getMapper(PmsSkuMapper.class)),
                config.pmsSkuService(sessions.getMapper(PmsSkuMapper.class),jdbc),
                config.umsMemberAddressService(sessions.getMapper(UmsMemberAddressMapper.class)),sessions.getMapper(UmsMemberAddressMapper.class),
                config.umsMemberService(sessions.getMapper(UmsMemberMapper.class)),config.omsOrderService(sessions.getMapper(OmsOrderMapper.class)),
                config.omsOrderItemService(sessions.getMapper(OmsOrderItemMapper.class)));
        ProxyFactory proxy=new ProxyFactory(target);proxy.setInterfaces(IUmsMemberCartService.class);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        jdbc.update("INSERT INTO pms_product(id,merchant_id,product_name,publish_status,price,original_price,stock,del_flag) VALUES(101,9,'fixture',1,40,50,5,'0')");
        jdbc.update("INSERT INTO pms_sku(id,product_id,sku_name,price,original_price,stock) VALUES(201,101,'fixture',40,50,5)");
        jdbc.update("INSERT INTO ums_member_address(id,member_id,full_name,mobile_phone,province,detail_address,del_flag) VALUES(401,7,'fixture','13800000000','fixture','fixture','0')");
        jdbc.update("INSERT INTO ums_member_cart(id,member_id,merchant_id,cart_type,product_id,sku_id,quantity,status,is_buy) VALUES(301,7,9,1,101,201,2,0,1)");
        jdbc.update("INSERT INTO view_member_cart(id,member_id,merchant_id,cart_type,product_id,sku_id,quantity,status,is_buy,product_name,price,original_price,sku_name) VALUES(301,7,9,1,101,201,2,0,1,'fixture',40,50,'fixture')");
        return (IUmsMemberCartService)proxy.getProxy();
    }
    private static void await(CountDownLatch latch) {try {assertTrue(latch.await(10,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}
}

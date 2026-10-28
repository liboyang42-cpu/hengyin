package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL/current Mapper writes + Spring transaction; deliberately lose adapter receipts after DB writes. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CartSettlementWriteReceiptMySqlIT {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("cart_write_receipt_it");
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private boolean created;
    private IUmsMemberCartService service;
    private Fault fault = Fault.NONE;
    private enum Fault { NONE, ORDER_ROWS, ORDER_KEY, ITEM_ROWS }

    @BeforeAll
    void schemaAndService() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("create database " + mysql.databaseName() + " character set utf8mb4");
        created = true;
        var source = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        db = new JdbcTemplate(source);
        Path root = Files.exists(Path.of("sql/base_schema_20260818.sql")) ? Path.of("") : Path.of("..");
        String ddl = Files.readString(root.resolve("sql/base_schema_20260818.sql"));
        for (String table : List.of("ums_member", "ums_member_cart", "oms_order", "oms_order_item", "pms_product", "pms_sku")) {
            int start = ddl.indexOf("CREATE TABLE `" + table + "` (");
            int end = ddl.indexOf(';', ddl.indexOf("\n) ENGINE", start));
            assertTrue(start >= 0 && end > start, "missing source DDL " + table);
            db.execute(ddl.substring(start, end + 1));
        }
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/UmsMemberMapper.xml"),
                new ClassPathResource("mapper/business/UmsMemberCartMapper.xml"),
                new ClassPathResource("mapper/business/OmsOrderMapper.xml"),
                new ClassPathResource("mapper/business/OmsOrderItemMapper.xml"),
                new ClassPathResource("mapper/business/PmsSkuMapper.xml"));
        var sql = new SqlSessionTemplate(factory.getObject());
        var target = new UmsMemberCartServiceImpl();
        ReflectionTestUtils.setField(target, "umsMemberCartMapper", sql.getMapper(UmsMemberCartMapper.class));
        var members = mock(IUmsMemberService.class);
        when(members.selectUmsMemberById(71L)).thenAnswer(call -> {
            var member = new UmsMember(); member.setId(71L);
            member.setPoint(db.queryForObject("select point from ums_member where id=71", Long.class));
            return member;
        });
        when(members.deductPoints(eq(71L), anyInt())).thenAnswer(call ->
                sql.getMapper(UmsMemberMapper.class).deductPoints(71L, call.getArgument(1)) == 1);
        ReflectionTestUtils.setField(target, "umsMemberService", members);
        var views = mock(IViewMemberCartService.class);
        when(views.selectViewMemberCartByIds(any())).thenAnswer(call -> {
            var row = new ViewMemberCart(); row.setId(31L); row.setMemberId(71L);
            row.setProductId(11L); row.setSkuId(21L); row.setMerchantId(81L);
            row.setCartType(2L); row.setQuantity(1L); row.setProductName("fixture product");
            return List.of(row);
        });
        ReflectionTestUtils.setField(target, "viewMemberCartService", views);
        var products = mock(IPmsProductService.class);
        var product = new PmsProduct(); product.setId(11L); product.setMerchantId(81L);
        product.setPublishStatus(1); product.setDelFlag(0);
        when(products.selectPmsProductById(11L)).thenReturn(product);
        ReflectionTestUtils.setField(target, "pmsProductService", products);
        var skus = mock(IPmsSkuService.class);
        when(skus.selectPmsSkuById(21L)).thenAnswer(call -> {
            var sku = new PmsSku(); sku.setId(21L); sku.setProductId(11L); sku.setPrice(BigDecimal.TEN);
            sku.setStock(db.queryForObject("select stock from pms_sku where id=21", Long.class));
            return sku;
        });
        when(skus.deductStockForProduct(21L, 11L, 1L)).thenAnswer(call ->
                sql.getMapper(PmsSkuMapper.class).deductStockForProduct(21L, 11L, 1L));
        ReflectionTestUtils.setField(target, "pmsSkuService", skus);
        // Address/read projections are doubles; this suite asserts persistence receipts and real funds rollback.
        var addresses = mock(IUmsMemberAddressService.class);
        var addressMapper = mock(UmsMemberAddressMapper.class);
        var address = new UmsMemberAddress(); address.setId(41L); address.setMemberId(71L);
        when(addresses.selectOwnedAddressById(41L, 71L)).thenReturn(address);
        when(addressMapper.lockActiveUmsMemberAddressByIdAndMemberId(41L, 71L)).thenReturn(address);
        ReflectionTestUtils.setField(target, "umsMemberAddressService", addresses);
        ReflectionTestUtils.setField(target, "umsMemberAddressMapper", addressMapper);
        var orders = new OmsOrderServiceImpl() {
            @Override public int insertOmsOrder(OmsOrder order) {
                int rows = super.insertOmsOrder(order);
                if (fault == Fault.ORDER_KEY) order.setId(null);
                return fault == Fault.ORDER_ROWS ? 0 : rows;
            }
        };
        ReflectionTestUtils.setField(orders, "omsOrderMapper", sql.getMapper(OmsOrderMapper.class));
        var items = new OmsOrderItemServiceImpl() {
            @Override public int insertOmsOrderItem(OmsOrderItem item) {
                int rows = super.insertOmsOrderItem(item);
                return fault == Fault.ITEM_ROWS ? 0 : rows;
            }
        };
        ReflectionTestUtils.setField(items, "omsOrderItemMapper", sql.getMapper(OmsOrderItemMapper.class));
        ReflectionTestUtils.setField(target, "omsOrderService", orders);
        ReflectionTestUtils.setField(target, "omsOrderItemService", items);
        var proxy = new ProxyFactory(target); proxy.setInterfaces(IUmsMemberCartService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        service = (IUmsMemberCartService) proxy.getProxy();
    }

    @BeforeEach
    void reset() {
        for (String table : List.of("oms_order_item", "oms_order", "ums_member_cart", "pms_sku", "pms_product", "ums_member")) {
            db.update("delete from " + table);
        }
        db.update("insert into ums_member(id,point) values(71,100)");
        db.update("insert into pms_product(id,product_name,publish_status,merchant_id,del_flag) values(11,'fixture product',1,81,0)");
        db.update("insert into pms_sku(id,product_id,price,stock) values(21,11,10,5)");
        db.update("insert into ums_member_cart(id,member_id,merchant_id,product_id,sku_id,quantity,cart_type) values(31,71,81,11,21,1,2)");
        fault = Fault.NONE;
    }

    @Test void zeroOrderReceiptRollsBackActualPointAndOrderWrites() { assertRollback(Fault.ORDER_ROWS); }
    @Test void lostGeneratedKeyRollsBackActualPointAndOrderWrites() { assertRollback(Fault.ORDER_KEY); }
    @Test void zeroItemReceiptRollsBackActualPointOrderAndItemWrites() { assertRollback(Fault.ITEM_ROWS); }

    private void assertRollback(Fault failure) {
        fault = failure;
        ServiceException error = assertThrows(ServiceException.class, () -> service.settleCart(71L, "fixture", "31", "", "41"));
        assertEquals(failure == Fault.ITEM_ROWS ? "订单明细保存失败" : "订单创建失败", error.getMessage());
        assertEquals(100L, db.queryForObject("select point from ums_member where id=71", Long.class));
        assertEquals(5L, db.queryForObject("select stock from pms_sku where id=21", Long.class));
        assertEquals(1, db.queryForObject("select count(*) from ums_member_cart", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from oms_order", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from oms_order_item", Integer.class));
        fault = Fault.NONE;
        Long orderId = service.settleCart(71L, "fixture", "31", "", "41");
        assertNotNull(orderId);
        assertEquals(90L, db.queryForObject("select point from ums_member where id=71", Long.class));
        assertEquals(4L, db.queryForObject("select stock from pms_sku where id=21", Long.class));
        assertEquals(0, db.queryForObject("select count(*) from ums_member_cart", Integer.class));
        assertEquals(1, db.queryForObject("select count(*) from oms_order where id=? and member_id=71", Integer.class, orderId));
        assertEquals(1, db.queryForObject("select count(*) from oms_order_item where order_id=?", Integer.class, orderId));
        assertEquals(0, BigDecimal.TEN.compareTo(db.queryForObject(
                "select pay_amount from oms_order where id=?", BigDecimal.class, orderId)));
        assertEquals(0, BigDecimal.TEN.compareTo(db.queryForObject(
                "select price from oms_order_item where order_id=?", BigDecimal.class, orderId)));
    }

    @AfterAll
    void cleanup() {
        if (admin != null && created) admin.execute("drop database " + mysql.databaseName());
    }
}

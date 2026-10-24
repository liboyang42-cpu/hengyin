package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.PmsSku;
import com.chengyinhub.business.mapper.OmsOrderItemMapper;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.mapper.PmsProductMapper;
import com.chengyinhub.business.mapper.PmsSkuMapper;
import com.chengyinhub.business.mapper.UmsMemberAddressMapper;
import com.chengyinhub.business.mapper.UmsMemberCartMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.ViewMemberCartMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.impl.OmsOrderItemServiceImpl;
import com.chengyinhub.business.service.impl.OmsOrderServiceImpl;
import com.chengyinhub.business.service.impl.PmsProductServiceImpl;
import com.chengyinhub.business.service.impl.PmsSkuServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberAddressServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberCartServiceImpl;
import com.chengyinhub.business.service.impl.UmsMemberServiceImpl;
import com.chengyinhub.business.service.impl.ViewMemberCartServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 真实 MyBatis XML + Spring 事务代理回归：拒绝靠 mock/spy 或 SQL 字符串断言伪造扣库与回滚证据。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CartSettlementTransactionalH2Test.Config.class)
class CartSettlementTransactionalH2Test {

    private static final long MEMBER_ID = 11L;
    private static final long PRODUCT_ID = 21L;
    private static final long SKU_ID = 31L;
    private static final long CART_ID = 41L;
    private static final long OTHER_CART_ID = 42L;

    @Autowired
    private IUmsMemberCartService cartService;

    @Autowired
    private PmsSkuMapper pmsSkuMapper;

    @Autowired
    private ControlledMemberService memberService;

    @Autowired
    private ControlledSkuService skuService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        StockDeductionObserver.reset();
        StockRaceTrigger.reset();
        CartDeleteRaceTrigger.reset();
        memberService.reset();
        skuService.reset();
        String[] tables = {"cart_delete_guard", "oms_order_item", "oms_order", "view_member_cart", "ums_member_cart",
                "ums_member_address", "ums_member", "pms_sku", "pms_product"};
        for (String table : tables) {
            jdbcTemplate.update("delete from " + table);
        }
        jdbcTemplate.update("insert into pms_product (id, merchant_id, product_name, publish_status, price, original_price, stock, del_flag) values (?, ?, ?, ?, ?, ?, ?, ?)",
                PRODUCT_ID, 7L, "事务商品", 1L, new BigDecimal("40.00"), new BigDecimal("50.00"), 5L, "0");
        jdbcTemplate.update("insert into pms_sku (id, product_id, sku_name, price, original_price, stock) values (?, ?, ?, ?, ?, ?)",
                SKU_ID, PRODUCT_ID, "默认规格", new BigDecimal("40.00"), new BigDecimal("50.00"), 5L);
        jdbcTemplate.update("insert into ums_member (id, point, del_flag) values (?, ?, ?)", MEMBER_ID, 100L, "0");
        jdbcTemplate.update("insert into ums_member_address (id, member_id, full_name, mobile_phone, province, detail_address, del_flag) values (?, ?, ?, ?, ?, ?, ?)",
                51L, MEMBER_ID, "测试收货人", "13800000000", "浙江", "西湖区", "0");
        jdbcTemplate.update("insert into ums_member_cart (id, member_id, merchant_id, cart_type, product_id, sku_id, quantity, status, is_buy) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                CART_ID, MEMBER_ID, 7L, 1L, PRODUCT_ID, SKU_ID, 2L, 0L, 1L);
        jdbcTemplate.update("insert into view_member_cart (id, member_id, merchant_id, cart_type, product_id, sku_id, quantity, status, is_buy, product_name, price, original_price, sku_name) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                CART_ID, MEMBER_ID, 7L, 1L, PRODUCT_ID, SKU_ID, 2L, 0L, 1L, "事务商品", new BigDecimal("1.00"), new BigDecimal("1.00"), "过期规格");
    }

    @Test
    void deductStockForProduct_executesMappedStatementWithSkuProductAndStockGuards() {
        assertEquals(0, pmsSkuMapper.deductStockForProduct(SKU_ID, PRODUCT_ID + 1, 1L));
        assertEquals(5L, stock());
        assertEquals(0, pmsSkuMapper.deductStockForProduct(SKU_ID, PRODUCT_ID, 6L));
        assertEquals(5L, stock());
        assertEquals(1, pmsSkuMapper.deductStockForProduct(SKU_ID, PRODUCT_ID, 2L));
        assertEquals(3L, stock());
    }

    @Test
    void settlement_rollsBackStockOrderAndPointsWhenDeleteFailsAfterSuccessfulDeduction() {
        jdbcTemplate.update("insert into cart_delete_guard (cart_id) values (?)", CART_ID);

        DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class,
                () -> cartService.settleCart(MEMBER_ID, "tester", String.valueOf(CART_ID), "", "51"));

        assertEquals(1, StockDeductionObserver.executions(), "异常必须发生在真实 SKU 扣库 SQL 已执行之后");
        assertTrue(rootCause(exception).getMessage().toLowerCase().contains("cart_delete_guard_fk"));
        assertEquals(5L, stock());
        assertEquals(100L, jdbcTemplate.queryForObject("select point from ums_member where id = ?", Long.class, MEMBER_ID));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order", Long.class));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order_item", Long.class));
        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from ums_member_cart where id = ?", Long.class, CART_ID));
    }

    /** D1(owner 10-23):积分商城单笔积分折合金额不得超过统一上限(缺省 ¥50 ÷ 0.02 = 2500 积分),超出先拦、零写入。 */
    @Test
    void settlementRejectsOrderAboveUnifiedPointsCapWithoutAnyWrite() {
        jdbcTemplate.update("update ums_member set point = ? where id = ?", 100_000L, MEMBER_ID);
        jdbcTemplate.update("update pms_sku set price = ? where id = ?", new BigDecimal("1250.50"), SKU_ID);
        jdbcTemplate.update("update pms_product set price = ? where id = ?", new BigDecimal("1250.50"), PRODUCT_ID);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> cartService.settleCart(MEMBER_ID, "tester", String.valueOf(CART_ID), "", "51"));

        assertEquals("单笔订单最多使用 2500 积分(折合 ¥50.00)", exception.getMessage());
        assertEquals(5L, stock());
        assertEquals(100_000L, jdbcTemplate.queryForObject("select point from ums_member where id = ?", Long.class, MEMBER_ID));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order", Long.class));
        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from ums_member_cart where id = ?", Long.class, CART_ID));
    }

    @Test
    void settlementAcceptsOrderExactlyAtUnifiedPointsCap() {
        jdbcTemplate.update("update ums_member set point = ? where id = ?", 2_600L, MEMBER_ID);
        jdbcTemplate.update("update pms_sku set price = ? where id = ?", new BigDecimal("1250.00"), SKU_ID);
        jdbcTemplate.update("update pms_product set price = ? where id = ?", new BigDecimal("1250.00"), PRODUCT_ID);

        cartService.settleCart(MEMBER_ID, "tester", String.valueOf(CART_ID), "", "51");

        assertEquals(100L, jdbcTemplate.queryForObject("select point from ums_member where id = ?", Long.class, MEMBER_ID));
        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from oms_order", Long.class));
    }

    @Test
    void settlementPersistsCurrentSkuPriceAndOriginalPriceSeparately() {
        cartService.settleCart(MEMBER_ID, "tester", String.valueOf(CART_ID), "", "51");

        assertEquals(new BigDecimal("40.00"), jdbcTemplate.queryForObject("select price from oms_order_item", BigDecimal.class));
        assertEquals(new BigDecimal("50.00"), jdbcTemplate.queryForObject("select original_price from oms_order_item", BigDecimal.class));
    }

    @Test
    void settlementUsesCurrentProductMerchantInsteadOfStaleCartMerchant() {
        jdbcTemplate.update("update view_member_cart set merchant_id = ? where id = ?", 999L, CART_ID);

        cartService.settleCart(MEMBER_ID, "tester", String.valueOf(CART_ID), "", "51");

        assertEquals(7L, jdbcTemplate.queryForObject("select mms_merchant_id from oms_order", Long.class));
    }

    @Test
    void settlementRejectsAnotherMembersAddressWithoutAnyWrite() {
        jdbcTemplate.update("insert into ums_member_address (id, member_id, full_name, mobile_phone, province, detail_address, del_flag) values (?, ?, ?, ?, ?, ?, ?)",
                52L, MEMBER_ID + 1, "他人地址", "13900000000", "浙江", "滨江区", "0");

        assertThrows(ServiceException.class, () -> cartService.settleCart(MEMBER_ID, "tester", String.valueOf(CART_ID), "", "52"));

        assertEquals(5L, stock());
        assertEquals(100L, jdbcTemplate.queryForObject("select point from ums_member where id = ?", Long.class, MEMBER_ID));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order", Long.class));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order_item", Long.class));
        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from ums_member_cart where id = ?", Long.class, CART_ID));
    }

    @Test
    void settlementRejectsDeletedAddressWithoutAnyWrite() {
        jdbcTemplate.update("update ums_member_address set del_flag = ? where id = ?", "2", 51L);

        assertThrows(ServiceException.class,
                () -> cartService.settleCart(MEMBER_ID, "tester", String.valueOf(CART_ID), "", "51"));

        assertEquals(5L, stock());
        assertEquals(100L, jdbcTemplate.queryForObject("select point from ums_member where id = ?", Long.class, MEMBER_ID));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order", Long.class));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order_item", Long.class));
        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from ums_member_cart where id = ?", Long.class, CART_ID));
    }

    @Test
    void settlementRollsBackWhenActualStockMapperReturnsZeroAfterRace() {
        StockRaceTrigger.arm();

        assertThrows(ServiceException.class,
                () -> cartService.settleCart(MEMBER_ID, "tester", String.valueOf(CART_ID), "", "51"));

        assertEquals(5L, stock());
        assertEquals(100L, jdbcTemplate.queryForObject("select point from ums_member where id = ?", Long.class, MEMBER_ID));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order", Long.class));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order_item", Long.class));
        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from ums_member_cart where id = ?", Long.class, CART_ID));
    }

    @Test
    void settlementRollsBackWhenConditionalCartDeleteActuallyAffectsZeroRows() {
        CartDeleteRaceTrigger.arm();

        assertThrows(ServiceException.class,
                () -> cartService.settleCart(MEMBER_ID, "tester", String.valueOf(CART_ID), "", "51"));

        assertEquals(1, StockDeductionObserver.executions());
        assertEquals(5L, stock());
        assertEquals(100L, jdbcTemplate.queryForObject("select point from ums_member where id = ?", Long.class, MEMBER_ID));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order", Long.class));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order_item", Long.class));
        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from ums_member_cart where id = ?", Long.class, CART_ID));
    }

    @Test
    void settlementRollsBackActualPointDeductionWhenControlledMemberServiceReturnsFalse() {
        memberService.deductThenReturnFalse();

        assertThrows(ServiceException.class,
                () -> cartService.settleCart(MEMBER_ID, "tester", String.valueOf(CART_ID), "", "51"));

        assertEquals(5L, stock());
        assertEquals(100L, jdbcTemplate.queryForObject("select point from ums_member where id = ?", Long.class, MEMBER_ID));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order", Long.class));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order_item", Long.class));
        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from ums_member_cart where id = ?", Long.class, CART_ID));
    }

    @Test
    void updateCartRollsBackWhenActualConditionalMapperUpdateAffectsZeroRows() {
        assertTrue(AopUtils.isAopProxy(cartService), "更新必须通过 Spring 事务代理执行");
        skuService.changeCartOwnerBeforeNextSkuRead();

        assertThrows(ServiceException.class,
                () -> cartService.updateCart(MEMBER_ID, String.valueOf(CART_ID), "1", String.valueOf(SKU_ID)));

        assertEquals(MEMBER_ID, jdbcTemplate.queryForObject("select member_id from ums_member_cart where id = ?", Long.class, CART_ID));
        assertEquals(2L, jdbcTemplate.queryForObject("select quantity from ums_member_cart where id = ?", Long.class, CART_ID));
    }

    @Test
    void activeAddressLockPreventsConcurrentSoftDeleteUntilSettlementCompletes() throws Exception {
        memberService.pauseAfterNextMemberRead();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> settlement = executor.submit(
                    () -> cartService.settleCart(MEMBER_ID, "tester", String.valueOf(CART_ID), "", "51"));
            assertTrue(memberService.awaitPause(), "必须在活动地址锁定后才开始并发删除");

            Future<Integer> delete = executor.submit(
                    () -> jdbcTemplate.update("update ums_member_address set del_flag = ? where id = ?", "2", 51L));
            assertThrows(TimeoutException.class, () -> delete.get(200, TimeUnit.MILLISECONDS));

            memberService.resume();
            assertTrue(settlement.get(10, TimeUnit.SECONDS) > 0);
            assertEquals(1, delete.get(10, TimeUnit.SECONDS));
            assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from oms_order", Long.class));
            assertEquals("2", jdbcTemplate.queryForObject("select del_flag from ums_member_address where id = ?", String.class, 51L));
        } finally {
            memberService.resume();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSettlementCommitsAtMostOnceForTheSameOwnedCart() throws Exception {
        assertTrue(AopUtils.isAopProxy(cartService), "结算必须通过 Spring 事务代理执行");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier checkoutStartBarrier = new CyclicBarrier(2);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    checkoutStartBarrier.await(10, TimeUnit.SECONDS);
                    return cartService.settleCart(MEMBER_ID, "tester", String.valueOf(CART_ID), "", "51");
                }));
            }

            int committed = 0;
            int rolledBack = 0;
            for (Future<Long> future : futures) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    committed++;
                } catch (ExecutionException exception) {
                    assertTrue(rootCause(exception).getClass().equals(ServiceException.class));
                    rolledBack++;
                }
            }

            assertEquals(1, committed);
            assertEquals(1, rolledBack);
            assertEquals(3L, stock());
            // 当前 SKU 价 40 × 数量 2，因此唯一成功事务扣 80 分，余额应为 20。
            assertEquals(20L, jdbcTemplate.queryForObject("select point from ums_member where id = ?", Long.class, MEMBER_ID));
            assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from oms_order", Long.class));
            assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from oms_order_item", Long.class));
            assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from ums_member_cart where id = ?", Long.class, CART_ID));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void settlementRejectsCombinedQuantityOverflowingGuardWithoutAnyWrite() {
        jdbcTemplate.update("update pms_sku set stock = ?, price = ? where id = ?", 6_000_000_000L, BigDecimal.ZERO, SKU_ID);
        jdbcTemplate.update("update ums_member_cart set quantity = ? where id = ?", 2_000_000_000L, CART_ID);
        jdbcTemplate.update("update view_member_cart set quantity = ?, price = ? where id = ?", 2_000_000_000L, BigDecimal.ZERO, CART_ID);
        jdbcTemplate.update("insert into ums_member_cart (id, member_id, merchant_id, cart_type, product_id, sku_id, quantity, status, is_buy) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                OTHER_CART_ID, MEMBER_ID, 7L, 1L, PRODUCT_ID, SKU_ID, 2_000_000_000L, 0L, 1L);
        jdbcTemplate.update("insert into view_member_cart (id, member_id, merchant_id, cart_type, product_id, sku_id, quantity, status, is_buy, product_name, price, original_price, sku_name) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                OTHER_CART_ID, MEMBER_ID, 7L, 1L, PRODUCT_ID, SKU_ID, 2_000_000_000L, 0L, 1L, "事务商品", BigDecimal.ZERO, BigDecimal.ZERO, "默认规格");

        assertThrows(ServiceException.class,
                () -> cartService.settleCart(MEMBER_ID, "tester", CART_ID + "," + OTHER_CART_ID, "", "51"));

        assertEquals(6_000_000_000L, stock());
        assertEquals(100L, jdbcTemplate.queryForObject("select point from ums_member where id = ?", Long.class, MEMBER_ID));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order", Long.class));
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from oms_order_item", Long.class));
        assertEquals(2L, jdbcTemplate.queryForObject("select count(*) from ums_member_cart where member_id = ?", Long.class, MEMBER_ID));
    }

    @Test
    void deleteCartsRejectsMissingOrAnotherMembersIdsWithoutDeletingAnything() {
        jdbcTemplate.update("insert into ums_member_cart (id, member_id, merchant_id, cart_type, product_id, sku_id, quantity, status, is_buy) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                OTHER_CART_ID, MEMBER_ID + 1, 7L, 1L, PRODUCT_ID, SKU_ID, 1L, 1L, 1L);

        assertThrows(ServiceException.class, () -> cartService.deleteCarts(MEMBER_ID, CART_ID + "," + OTHER_CART_ID));
        assertThrows(ServiceException.class, () -> cartService.deleteCarts(MEMBER_ID, CART_ID + ",999"));

        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from ums_member_cart where id = ?", Long.class, CART_ID));
        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from ums_member_cart where id = ?", Long.class, OTHER_CART_ID));
    }

    @Test
    void deleteCartsRejectsDuplicateIllegalAndOverflowIdsWithoutDeletingAnything() {
        assertThrows(ServiceException.class, () -> cartService.deleteCarts(MEMBER_ID, ""));
        assertThrows(ServiceException.class, () -> cartService.deleteCarts(MEMBER_ID, CART_ID + "," + CART_ID));
        assertThrows(ServiceException.class, () -> cartService.deleteCarts(MEMBER_ID, "0"));
        assertThrows(ServiceException.class, () -> cartService.deleteCarts(MEMBER_ID, "not-a-number"));
        assertThrows(ServiceException.class, () -> cartService.deleteCarts(MEMBER_ID, "9223372036854775808"));

        assertEquals(1L, jdbcTemplate.queryForObject("select count(*) from ums_member_cart where id = ?", Long.class, CART_ID));
    }

    @Test
    void deleteCartsDeletesCompleteOwnedBatch() {
        jdbcTemplate.update("insert into ums_member_cart (id, member_id, merchant_id, cart_type, product_id, sku_id, quantity, status, is_buy) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                OTHER_CART_ID, MEMBER_ID, 7L, 1L, PRODUCT_ID, SKU_ID, 1L, 1L, 1L);

        assertEquals(2, cartService.deleteCarts(MEMBER_ID, CART_ID + "," + OTHER_CART_ID));

        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from ums_member_cart where member_id = ?", Long.class, MEMBER_ID));
    }

    private long stock() {
        return jdbcTemplate.queryForObject("select stock from pms_sku where id = ?", Long.class, SKU_ID);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable result = throwable;
        while (result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan(basePackageClasses = PmsSkuMapper.class, sqlSessionTemplateRef = "cartSqlSessionTemplate")
    static class Config {

        @Bean
        DataSource dataSource() {
            org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:cart_transaction;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            for (String ddl : DDL.split(";")) {
                jdbc.execute(ddl);
            }
            jdbc.execute("create trigger if not exists cart_stock_deduction before update on pms_sku for each row call \"com.chengyinhub.business.service.CartSettlementTransactionalH2Test$StockDeductionObserver\"");
            jdbc.execute("create trigger if not exists cart_stock_race before insert on oms_order_item for each row call \"com.chengyinhub.business.service.CartSettlementTransactionalH2Test$StockRaceTrigger\"");
            jdbc.execute("create trigger if not exists cart_delete_race before update on pms_sku for each row call \"com.chengyinhub.business.service.CartSettlementTransactionalH2Test$CartDeleteRaceTrigger\"");
            return jdbc;
        }

        @Bean(name = "cartSqlSessionFactory")
        SqlSessionFactory cartSqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
            factory.setMapperLocations(
                    new ClassPathResource("mapper/business/PmsProductMapper.xml"),
                    new ClassPathResource("mapper/business/PmsSkuMapper.xml"),
                    new ClassPathResource("mapper/business/UmsMemberMapper.xml"),
                    new ClassPathResource("mapper/business/UmsMemberAddressMapper.xml"),
                    new ClassPathResource("mapper/business/UmsMemberCartMapper.xml"),
                    new ClassPathResource("mapper/business/ViewMemberCartMapper.xml"),
                    new ClassPathResource("mapper/business/OmsOrderMapper.xml"),
                    new ClassPathResource("mapper/business/OmsOrderItemMapper.xml"));
            return factory.getObject();
        }

        @Bean(name = "cartSqlSessionTemplate")
        SqlSessionTemplate cartSqlSessionTemplate(SqlSessionFactory cartSqlSessionFactory) {
            return new SqlSessionTemplate(cartSqlSessionFactory);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        PiiCryptoService piiCryptoService() {
            return new PiiCryptoService("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "h2-test-only");
        }

        @Bean
        ControlledSkuService pmsSkuService(PmsSkuMapper mapper, JdbcTemplate jdbcTemplate) {
            ControlledSkuService service = new ControlledSkuService(jdbcTemplate);
            ReflectionTestUtils.setField(service, "pmsSkuMapper", mapper);
            return service;
        }

        @Bean
        PmsProductServiceImpl pmsProductService(PmsProductMapper productMapper, PmsSkuMapper skuMapper) {
            PmsProductServiceImpl service = new PmsProductServiceImpl();
            ReflectionTestUtils.setField(service, "pmsProductMapper", productMapper);
            ReflectionTestUtils.setField(service, "pmsSkuMapper", skuMapper);
            return service;
        }

        @Bean
        ViewMemberCartServiceImpl viewMemberCartService(ViewMemberCartMapper mapper) {
            ViewMemberCartServiceImpl service = new ViewMemberCartServiceImpl();
            ReflectionTestUtils.setField(service, "viewMemberCartMapper", mapper);
            return service;
        }

        @Bean
        UmsMemberAddressServiceImpl umsMemberAddressService(UmsMemberAddressMapper mapper) {
            UmsMemberAddressServiceImpl service = new UmsMemberAddressServiceImpl();
            ReflectionTestUtils.setField(service, "umsMemberAddressMapper", mapper);
            return service;
        }

        @Bean
        ControlledMemberService umsMemberService(UmsMemberMapper mapper) {
            ControlledMemberService service = new ControlledMemberService();
            ReflectionTestUtils.setField(service, "umsMemberMapper", mapper);
            return service;
        }

        @Bean
        com.chengyinhub.business.service.IUmsMemberPointsDetailService pointsDetailService() {
            return org.mockito.Mockito.mock(com.chengyinhub.business.service.IUmsMemberPointsDetailService.class);
        }

        @Bean
        com.chengyinhub.business.service.ICmsPointsSettingService cmsPointsSettingService() {
            return org.mockito.Mockito.mock(com.chengyinhub.business.service.ICmsPointsSettingService.class);
        }

        @Bean
        com.chengyinhub.business.service.IUmsMemberBalanceDetailService balanceDetailService() {
            return org.mockito.Mockito.mock(com.chengyinhub.business.service.IUmsMemberBalanceDetailService.class);
        }

        @Bean
        com.chengyinhub.business.service.IAppSessionRegistryService appSessionRegistryService() {
            return org.mockito.Mockito.mock(com.chengyinhub.business.service.IAppSessionRegistryService.class);
        }

        @Bean
        OmsOrderServiceImpl omsOrderService(OmsOrderMapper mapper) {
            OmsOrderServiceImpl service = new OmsOrderServiceImpl();
            ReflectionTestUtils.setField(service, "omsOrderMapper", mapper);
            return service;
        }

        @Bean
        OmsOrderItemServiceImpl omsOrderItemService(OmsOrderItemMapper mapper) {
            OmsOrderItemServiceImpl service = new OmsOrderItemServiceImpl();
            ReflectionTestUtils.setField(service, "omsOrderItemMapper", mapper);
            return service;
        }

        @Bean
        UmsMemberCartServiceImpl umsMemberCartService(UmsMemberCartMapper cartMapper,
                                                       ViewMemberCartServiceImpl viewService,
                                                       PmsProductServiceImpl productService,
                                                       ControlledSkuService skuService,
                                                       UmsMemberAddressServiceImpl addressService,
                                                       UmsMemberAddressMapper addressMapper,
                                                       ControlledMemberService memberService,
                                                       OmsOrderServiceImpl orderService,
                                                       OmsOrderItemServiceImpl itemService) {
            UmsMemberCartServiceImpl service = new UmsMemberCartServiceImpl();
            ReflectionTestUtils.setField(service, "umsMemberCartMapper", cartMapper);
            ReflectionTestUtils.setField(service, "viewMemberCartService", viewService);
            ReflectionTestUtils.setField(service, "pmsProductService", productService);
            ReflectionTestUtils.setField(service, "pmsSkuService", skuService);
            ReflectionTestUtils.setField(service, "umsMemberAddressService", addressService);
            ReflectionTestUtils.setField(service, "umsMemberAddressMapper", addressMapper);
            ReflectionTestUtils.setField(service, "umsMemberService", memberService);
            ReflectionTestUtils.setField(service, "omsOrderService", orderService);
            ReflectionTestUtils.setField(service, "omsOrderItemService", itemService);
            return service;
        }

        private static final String DDL =
                "create table if not exists pms_product (id bigint primary key, merchant_id bigint, product_name varchar(255), pic varchar(255), album_pics varchar(255), publish_status bigint, sort_id bigint, price decimal(19,2), original_price decimal(19,2), stock bigint, unit varchar(32), weight double, detail_html clob, product_attr clob, del_flag varchar(2), create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp);" +
                "create table if not exists pms_sku (id bigint primary key, sku_snapshot_id bigint, product_id bigint, out_sku_id varchar(255), sku_name varchar(255), sku_name_ar varchar(255), price decimal(19,2), weight double, original_price decimal(19,2), pic varchar(255), stock bigint, sp_data varchar(255), create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp);" +
                "create table if not exists ums_member (id bigint primary key, user_type varchar(32), role varchar(255), phone varchar(255), password varchar(255), level_id bigint, name varchar(255), wechat varchar(255), status varchar(32), invite_user_id bigint, login_ip varchar(64), login_time timestamp, nickname varchar(255), avatar varchar(255), sex varchar(32), wx_open_id varchar(255), app_open_id varchar(255), union_id varchar(255), apple_id varchar(255), area_id bigint, birthday timestamp, mark varchar(255), tag_ids varchar(255), case_pics varchar(255), case_introducton varchar(255), website varchar(255), work_address varchar(255), introduction varchar(255), balance decimal(19,2), point bigint, del_flag varchar(2), create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp);" +
                "create table if not exists ums_member_address (id bigint primary key, member_id bigint, request_id varchar(64), full_name varchar(255), area_code varchar(32), mobile_phone varchar(255), province varchar(255), detail_address varchar(255), is_default bigint, del_flag varchar(2), create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp);" +
                "create table if not exists ums_member_cart (id bigint primary key, member_id bigint, merchant_id bigint, cart_type bigint, product_id bigint, sku_id bigint, sp_data varchar(255), quantity bigint, status bigint, is_buy bigint, create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp);" +
                "create table if not exists view_member_cart (id bigint primary key, member_id bigint, merchant_id bigint, cart_type bigint, product_id bigint, sku_id bigint, sp_data varchar(255), quantity bigint, status bigint, is_buy bigint, create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp, product_name varchar(255), product_pic varchar(255), price decimal(19,2), original_price decimal(19,2), sku_name varchar(255), sku_name_ar varchar(255), sku_pic varchar(255), weight double);" +
                "create table if not exists oms_order (id bigint generated by default as identity primary key, order_type bigint, order_sn varchar(255), pay_id bigint, member_id bigint, mms_merchant_id bigint, product_quantity bigint, product_total_amount decimal(19,2), delivery_fee decimal(19,2), pay_amount decimal(19,2), tax_fee decimal(19,2), order_total_amount decimal(19,2), points_amount decimal(19,2), pay_type bigint, status bigint, aftersale_status bigint, delivery_company varchar(255), delivery_sn varchar(255), auto_confirm_day bigint, receiver_name varchar(255), receiver_phone varchar(255), receiver_phone_hash varchar(255), receiver_phone_code varchar(64), receiver_province varchar(255), receiver_detail_address varchar(255), order_note varchar(255), merchant_note varchar(255), confirm_status bigint, payment_time timestamp, delivery_time timestamp, receive_time timestamp, member_coupon_id bigint, coupon_amount decimal(19,2), del_flag varchar(2), create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp);" +
                "create table if not exists oms_order_item (id bigint generated by default as identity primary key, order_id bigint, product_id bigint, out_product_id varchar(255), sku_id bigint, out_sku_id varchar(255), pic varchar(255), product_name varchar(255), price decimal(19,2), original_price decimal(19,2), quantity bigint, sp_data varchar(255), is_evaluate bigint, aftersale_order_id bigint, aftersale_status bigint, create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp);" +
                "create table if not exists cart_delete_guard (cart_id bigint primary key, constraint cart_delete_guard_fk foreign key (cart_id) references ums_member_cart(id));";
    }

    public static class StockDeductionObserver implements org.h2.api.Trigger {
        private static final AtomicInteger EXECUTIONS = new AtomicInteger();

        static void reset() {
            EXECUTIONS.set(0);
        }

        static int executions() {
            return EXECUTIONS.get();
        }

        @Override public void init(Connection connection, String schemaName, String triggerName, String tableName, boolean before, int type) { }
        @Override public void fire(Connection connection, Object[] oldRow, Object[] newRow) { EXECUTIONS.incrementAndGet(); }
        @Override public void close() { }
        @Override public void remove() { }
    }

    public static class StockRaceTrigger implements org.h2.api.Trigger {
        private static final AtomicBoolean ARMED = new AtomicBoolean();

        static void arm() {
            ARMED.set(true);
        }

        static void reset() {
            ARMED.set(false);
        }

        @Override public void init(Connection connection, String schemaName, String triggerName, String tableName, boolean before, int type) { }
        @Override public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            if (ARMED.compareAndSet(true, false)) {
                try (PreparedStatement statement = connection.prepareStatement("update pms_sku set stock = 0 where id = ?")) {
                    statement.setLong(1, SKU_ID);
                    statement.executeUpdate();
                }
            }
        }
        @Override public void close() { }
        @Override public void remove() { }
    }

    public static class CartDeleteRaceTrigger implements org.h2.api.Trigger {
        private static final AtomicBoolean ARMED = new AtomicBoolean();

        static void arm() {
            ARMED.set(true);
        }

        static void reset() {
            ARMED.set(false);
        }

        @Override public void init(Connection connection, String schemaName, String triggerName, String tableName, boolean before, int type) { }
        @Override public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            if (ARMED.compareAndSet(true, false)) {
                try (PreparedStatement statement = connection.prepareStatement("delete from ums_member_cart where id = ? and member_id = ?")) {
                    statement.setLong(1, CART_ID);
                    statement.setLong(2, MEMBER_ID);
                    statement.executeUpdate();
                }
            }
        }
        @Override public void close() { }
        @Override public void remove() { }
    }

    static class ControlledMemberService extends UmsMemberServiceImpl {
        private final AtomicBoolean failNextDeduction = new AtomicBoolean();
        private volatile CountDownLatch memberRead;
        private volatile CountDownLatch resume;

        void deductThenReturnFalse() {
            failNextDeduction.set(true);
        }

        void reset() {
            failNextDeduction.set(false);
            resume();
        }

        void pauseAfterNextMemberRead() {
            memberRead = new CountDownLatch(1);
            resume = new CountDownLatch(1);
        }

        boolean awaitPause() throws InterruptedException {
            return memberRead != null && memberRead.await(10, TimeUnit.SECONDS);
        }

        void resume() {
            CountDownLatch latch = resume;
            if (latch != null) {
                latch.countDown();
            }
            memberRead = null;
            resume = null;
        }

        @Override
        public com.chengyinhub.business.domain.UmsMember selectUmsMemberById(Long id) {
            CountDownLatch entered = memberRead;
            CountDownLatch waitForResume = resume;
            if (entered != null && waitForResume != null) {
                entered.countDown();
                try {
                    waitForResume.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            return super.selectUmsMemberById(id);
        }

        @Override
        public boolean deductPoints(Long id, Integer point) {
            if (failNextDeduction.compareAndSet(true, false)) {
                super.deductPoints(id, point);
                return false;
            }
            return super.deductPoints(id, point);
        }
    }

    static class ControlledSkuService extends PmsSkuServiceImpl {
        private final JdbcTemplate jdbcTemplate;
        private final AtomicBoolean changeCartOwnerBeforeSkuRead = new AtomicBoolean();

        ControlledSkuService(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        void changeCartOwnerBeforeNextSkuRead() {
            changeCartOwnerBeforeSkuRead.set(true);
        }

        void reset() {
            changeCartOwnerBeforeSkuRead.set(false);
        }

        @Override
        public PmsSku selectPmsSkuById(Long id) {
            if (changeCartOwnerBeforeSkuRead.compareAndSet(true, false)) {
                jdbcTemplate.update("update ums_member_cart set member_id = ? where id = ?", MEMBER_ID + 1, CART_ID);
            }
            return super.selectPmsSkuById(id);
        }
    }

}

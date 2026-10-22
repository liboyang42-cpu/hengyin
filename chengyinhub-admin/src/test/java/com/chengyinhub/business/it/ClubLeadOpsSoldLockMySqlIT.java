package com.chengyinhub.business.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;

/**
 * W3(2026-10-22,#334 F7 / PRIVATE-GROUP-backend-club-meeting-atomic):承接方领队改集合时间/地点
 * 必须是「仍未售出才生效」的单条条件 UPDATE。真 MySQL 两个事务交错,证明先数后写的下单窗口已关:
 * 在途下单让修改等待并以 0 行失败;修改在先则后到的下单排队到修改提交之后。
 */
class ClubLeadOpsSoldLockMySqlIT
{
    private static final Date NEW_START = Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 14, 30));

    private static String url;
    private static SqlSessionFactory factory;
    private static PooledDataSource dataSource;
    private static ExecutorService pool;

    @BeforeAll
    static void createSchema() throws Exception
    {
        url = MybatisItDatabase.url("club_ops_sold_lock");
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CmsActivity", CmsActivity.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", url,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/CmsActivityMapper.xml";
        try (InputStream xml = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(xml, cfg, resource, cfg.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(cfg);
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterAll
    static void shutdown()
    {
        if (pool != null) pool.shutdownNow();
        if (dataSource != null) dataSource.forceCloseAll();
    }

    @BeforeEach
    void resetTables() throws Exception
    {
        try (Connection c = connection(); Statement sql = c.createStatement())
        {
            sql.execute("drop table if exists cms_registration");
            sql.execute("drop table if exists cms_activity");
            sql.execute("create table cms_activity (id bigint primary key, address varchar(255),"
                    + " address_name varchar(255), start_date datetime, end_date datetime,"
                    + " day_note varchar(500), update_time datetime) engine=InnoDB");
            sql.execute("create table cms_registration (id bigint auto_increment primary key,"
                    + " owner_type bigint, owner_id bigint, payment_status int,"
                    + " key idx_owner (owner_type, owner_id)) engine=InnoDB");
            for (long id = 1; id <= 4; id++)
            {
                sql.execute("insert into cms_activity (id,address,start_date) values (" + id
                        + ",'旧集合点','2026-11-01 00:00:00')");
            }
        }
    }

    @Test
    void conditionalUpdateLocksOpenSalesOnlyInOneStatement() throws Exception
    {
        try (Connection c = connection(); Statement sql = c.createStatement())
        {
            sql.execute("insert into cms_registration (owner_type,owner_id,payment_status) values"
                    + " (2,2,2),(2,3,0),(2,3,1),(2,4,3),(2,4,4),(1,1,2)");
        }
        // 1: 无单(主题级同号单不算);2: 已支付;3: 待支付/支付中;4: 只有失败/已退款
        assertEquals(1, edit(1L));
        assertEquals(0, edit(2L));
        assertEquals(0, edit(3L));
        assertEquals(1, edit(4L));
        assertEquals("新集合点", address(1L));
        assertEquals("旧集合点", address(2L));
        assertEquals("旧集合点", address(3L));
        assertEquals("新集合点", address(4L));
    }

    @Test
    void inFlightSaleMakesEditWaitAndThenLoseWithZeroRows() throws Exception
    {
        try (Connection sale = transaction())
        {
            insertSale(sale, 1L);
            Future<Integer> editing = pool.submit(() -> edit(1L));
            assertBlocked(editing, "在途下单未提交时,改集合时间必须等它落定,不能按「未售出」抢先写入");
            sale.commit();
            assertEquals(0, editing.get(10, TimeUnit.SECONDS), "下单先提交 → 修改必须 0 行(已售锁定)");
        }
        assertEquals("旧集合点", address(1L));
    }

    @Test
    void rolledBackSaleLetsWaitingEditProceed() throws Exception
    {
        try (Connection sale = transaction())
        {
            insertSale(sale, 1L);
            Future<Integer> editing = pool.submit(() -> edit(1L));
            assertBlocked(editing, "在途下单未提交时,修改必须等待");
            sale.rollback();
            assertEquals(1, editing.get(10, TimeUnit.SECONDS));
        }
        assertEquals("新集合点", address(1L));
    }

    @Test
    void editFirstMakesConcurrentSaleQueueBehindItThenLocksFurtherEdits() throws Exception
    {
        try (SqlSession editing = factory.openSession(false))
        {
            assertEquals(1, editing.getMapper(CmsActivityMapper.class).updateOpsFieldsIfUnsold(ops(1L)));
            Future<Boolean> selling = pool.submit(() -> {
                try (Connection sale = transaction())
                {
                    insertSale(sale, 1L);
                    sale.commit();
                    return true;
                }
            });
            assertBlocked(selling, "修改未提交时,同场次新下单必须排在修改之后(否则买家买的是旧时间)");
            editing.commit();
            assertTrue(selling.get(10, TimeUnit.SECONDS));
        }
        assertEquals("新集合点", address(1L));
        assertEquals(1, count("select count(*) from cms_registration where owner_id=1"));
        // 卖出之后再改:锁定
        assertEquals(0, edit(1L));
    }

    private static int edit(Long activityId)
    {
        try (SqlSession session = factory.openSession(true))
        {
            return session.getMapper(CmsActivityMapper.class).updateOpsFieldsIfUnsold(ops(activityId));
        }
    }

    private static CmsActivity ops(Long activityId)
    {
        CmsActivity ops = new CmsActivity();
        ops.setId(activityId);
        ops.setAddress("新集合点");
        ops.setStartDate(NEW_START);
        ops.setUpdateTime(new Date());
        return ops;
    }

    private static void insertSale(Connection sale, long activityId) throws Exception
    {
        try (Statement sql = sale.createStatement())
        {
            sql.execute("insert into cms_registration (owner_type,owner_id,payment_status) values (2,"
                    + activityId + ",0)");
        }
    }

    private static void assertBlocked(Future<?> future, String why) throws Exception
    {
        try
        {
            future.get(700, TimeUnit.MILLISECONDS);
            assertFalse(true, why);
        }
        catch (TimeoutException expected)
        {
            assertFalse(future.isDone(), why);
        }
    }

    private static Connection connection() throws Exception
    {
        return DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password());
    }

    private static Connection transaction() throws Exception
    {
        Connection c = connection();
        c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        c.setAutoCommit(false);
        return c;
    }

    private static String address(long id) throws Exception
    {
        try (Connection c = connection(); Statement sql = c.createStatement();
             ResultSet rs = sql.executeQuery("select address from cms_activity where id=" + id))
        {
            rs.next();
            return rs.getString(1);
        }
    }

    private static int count(String query) throws Exception
    {
        try (Connection c = connection(); Statement sql = c.createStatement(); ResultSet rs = sql.executeQuery(query))
        {
            rs.next();
            return rs.getInt(1);
        }
    }
}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IActivityCancelManualRefundCaseService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IRefundPayoutService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R9-16 真库复现:免费(0 元)订单的主办取消不得被「父单守卫」拦住。
 *
 * <p>审查原文(第九轮,活动24/订单91、活动27/订单95)在旧守卫下报 500
 * 「父订单缺少原支付主单,请人工核对」:免费父单没有 payable&gt;0 的首单,
 * {@code snapshot.payer == null} 直接抛。当前基线已按「免费/全积分单取锁定查询首行、
 * 继续用 exactFen 校验现金快照」修复(见 TeamGroupServiceImpl.inspectPaidOrderForCancellation);
 * 本 IT 用真实 schema + 真实 Mapper + 真实 TeamGroupServiceImpl 复现审计的两个订单形状,
 * 把「单张票路径无库/接口复现证据」补齐(交接材料 20260915:不能反证已修)。
 *
 * <p>夹具按审计证据的 DB 实况落库:
 * <ul>
 *   <li>订单95(活动27,单张免费):1 行,order_num=1,parent_order_no 非空,payable=wechat=0;</li>
 *   <li>订单91(活动24,两张免费):1 行,order_num=2,parent_order_no 非空,payable=wechat=0;</li>
 *   <li>{@code chengyinhub-xcx} 无数量入口,审计系直接走 {@code /api/registration/create} 的
 *       tickets[{count}] 建单,故「两张」= 单行 order_num=2。</li>
 * </ul>
 *
 * <p>负控(付费单守卫必须保持 fail-closed,不能让「0 payable」把有现金的单吞进无资金分支):
 * payable 丢失但 wechat 有值 ⇒ 409 快照不一致;同一父单多个正 payable ⇒ 409 多个支付主单。
 * 正控:正常付费父单仍走退款单 + 打款一次,金额不被本修复改动。
 *
 * <p>真实 MySQL 8 scratch(唯一 UUID 库名,结束 DROP 后回读 information_schema=0);
 * 仅 {@code CHENGYIN_FUNDS_MYSQL_IT=1} 且本机 3317 scratch 可达时运行。
 * 通知/退款单/打款/人工案件是测试替身,不连微信、不产生真实退款。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActivityCancelFreeOrderMybatisIT {

    // 2026-09-17:3317/root/空密码兜底退役 —— 连接参数只认门禁注入的 CHENGYIN_TEST_MYSQL_*
    // (PR #1077 报告第 3 条);缺了直接失败(fail-closed),不再悄悄落到本机另一台库。
    private static final String HOST = requiredEnv("CHENGYIN_TEST_MYSQL_HOST");
    private static final String PORT = requiredEnv("CHENGYIN_TEST_MYSQL_PORT");
    private static final String USER = requiredEnv("CHENGYIN_TEST_MYSQL_USER");
    private static final String PASSWORD = System.getenv("CHENGYIN_TEST_MYSQL_PASSWORD");
    private static final String PARAMS =
            "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL";
    private static final String ROOT = resolveRoot();

    private static final long OWNER = 9000L;
    private static final long FREE_ACTIVITY = 27L;       // 审计活动27:免费单张(订单95)
    private static final long MULTI_ACTIVITY = 24L;      // 审计活动24:免费两张(订单91)
    private static final long PAID_ACTIVITY = 28L;
    private static final long FREE_TICKET = 105L;
    private static final long MULTI_TICKET = 101L;
    private static final long PAID_TICKET = 106L;

    private String database;
    private JdbcTemplate jdbc;
    private CmsRegistrationMapper regMapper;
    private CmsActivityMapper activityMapper;
    private OmsTicketMapper ticketMapper;
    private TransactionTemplate transaction;

    private IRefundService refundService;
    private IRefundPayoutService refundPayoutService;
    private IActivityCancelManualRefundCaseService manualRefundCaseService;
    private UmsMemberMapper umsMemberMapper;
    private TeamGroupServiceImpl service;

    @BeforeAll
    void createScratchSchema() throws Exception {
        database = "chengyin_r916_cancel_" + UUID.randomUUID().toString().replace("-", "");
        execAdmin("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
        for (String table : new String[]{"cms_registration", "cms_activity", "cms_topic", "oms_ticket"}) {
            exec(baseDdl(table));
        }
        // migration_20260930_registration_first_successful_scan.sql: 当前报名查询投影必读。
        exec("ALTER TABLE cms_registration ADD COLUMN first_successful_scan_at DATETIME NULL");
        // 活动查询带 cms_topic 子查询(product_type/team_mode/team_max_members);
        // team_* 两列来自 topic_team_config_20260821,base_schema 之后新增。
        exec("ALTER TABLE cms_topic ADD COLUMN team_mode TINYINT NOT NULL DEFAULT 0");
        exec("ALTER TABLE cms_topic ADD COLUMN team_max_members INT NOT NULL DEFAULT 4");

        DriverManagerDataSource ds = new DriverManagerDataSource(databaseUrl(), USER, PASSWORD);
        try (Connection connection = ds.getConnection()) {
            com.chengyinhub.business.it.ItSchema.applyMigrations(connection,
                    "sql/migration_20260930_registration_first_successful_scan.sql");
        }
        jdbc = new JdbcTemplate(ds);
        Configuration cfg = new Configuration(new Environment("r916-cancel",
                new SpringManagedTransactionFactory(), ds));
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (String mapper : new String[]{"CmsRegistrationMapper", "CmsActivityMapper", "OmsTicketMapper"}) {
            String path = ROOT + "/chengyinhub-system/src/main/resources/mapper/business/" + mapper + ".xml";
            try (InputStream in = new FileInputStream(path)) {
                new XMLMapperBuilder(in, cfg, path, cfg.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
        regMapper = sql.getMapper(CmsRegistrationMapper.class);
        activityMapper = sql.getMapper(CmsActivityMapper.class);
        ticketMapper = sql.getMapper(OmsTicketMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));

        System.out.println("R9-16 cancel MySQL version=" + jdbc.queryForObject("SELECT VERSION()", String.class));
    }

    /** 每个用例一套全新替身:PER_CLASS 下复用会让上一个用例的调用被下一个用例的 never() 撞见。 */
    @BeforeEach
    void wireService() {
        refundService = mock(IRefundService.class);
        refundPayoutService = mock(IRefundPayoutService.class);
        manualRefundCaseService = mock(IActivityCancelManualRefundCaseService.class);
        umsMemberMapper = mock(UmsMemberMapper.class);
        when(refundService.saveRefund(any(RefundApplication.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, RefundApplication.class).setId(999L);
            return 1;
        });

        service = new TeamGroupServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(service, "firstReviewPointsClawback",

                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ReflectionTestUtils.setField(service, "refundService", refundService);
        ReflectionTestUtils.setField(service, "umsMemberMapper", umsMemberMapper);
        ReflectionTestUtils.setField(service, "notifyService", mock(INotifyService.class));
        com.chengyinhub.business.service.IImService notices = mock(com.chengyinhub.business.service.IImService.class);
        when(notices.sendSystem(any(), any(), any(), any())).thenReturn(1L);
        ReflectionTestUtils.setField(service, "imService", notices);
        ReflectionTestUtils.setField(service, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(service, "refundPayoutService", refundPayoutService);
        ReflectionTestUtils.setField(service, "manualRefundCaseService", manualRefundCaseService);
        ReflectionTestUtils.setField(service, "clubMapper", mock(ClubMapper.class));
    }

    @AfterAll
    void dropScratchSchemaAndReadBack() throws Exception {
        String dropped = database;
        if (dropped != null) {
            execAdmin("DROP DATABASE IF EXISTS `" + dropped + "`");
        }
        int remaining;
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT + "/" + PARAMS, USER, PASSWORD);
             PreparedStatement p = c.prepareStatement(
                     "select count(*) from information_schema.schemata where schema_name=?")) {
            p.setString(1, dropped);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                remaining = rs.getInt(1);
            }
        }
        assertEquals(0, remaining, "UUID scratch 必须已销毁(结束读回 0)");
        System.out.println("OWN_SCRATCH_DROPPED=true schema=" + dropped);
    }

    @BeforeEach
    void seedBase() throws Exception {
        for (String table : new String[]{"cms_registration", "cms_activity", "cms_topic", "oms_ticket"}) {
            exec("DELETE FROM " + table);
        }
        exec("INSERT INTO cms_activity(id,member_id,name,del_flag,status,publish_status,start_date,end_date,create_time)"
                + " VALUES (" + FREE_ACTIVITY + "," + OWNER + ",'R9免费单张',0,1,1,"
                + " date_sub(now(), interval 1 hour),date_add(now(), interval 1 hour),now()),"
                + " (" + MULTI_ACTIVITY + "," + OWNER + ",'R9免费两张',0,1,1,"
                + " date_sub(now(), interval 1 hour),date_add(now(), interval 1 hour),now()),"
                + " (" + PAID_ACTIVITY + "," + OWNER + ",'R9付费单',0,1,1,"
                + " date_sub(now(), interval 1 hour),date_add(now(), interval 1 hour),now())");
        exec("INSERT INTO oms_ticket(id,member_id,owner_type,owner_id,name,price,total_inventory,remaining_inventory)"
                + " VALUES (" + FREE_TICKET + "," + OWNER + ",2," + FREE_ACTIVITY + ",'免费同行票',0,4,3),"
                + " (" + MULTI_TICKET + "," + OWNER + ",2," + MULTI_ACTIVITY + ",'免费同行票',0,4,2),"
                + " (" + PAID_TICKET + "," + OWNER + ",2," + PAID_ACTIVITY + ",'收费票',68,4,3)");
    }

    // ------------------------------------------------------------------ 免费单张(审计订单95)

    @Test
    void freeSingleTicketParentOrderCancelsWithoutPayoutAndRestoresInventory() throws Exception {
        seedReg(95L, "A20260913B66DBF70", "P202609134D242E16", FREE_ACTIVITY, FREE_TICKET, 9004L,
                1, "0.00", "0.00", 0, 0);

        ActivityCancelResult result = cancel(FREE_ACTIVITY);

        assertEquals(1, result.getRefundedOrders(), "免费单张必须算一单已退,不能被父单守卫拦下");
        assertEquals(0, result.getManualOrders());
        assertEquals(0, result.getClosedPendingOrders());
        assertCancelled(FREE_ACTIVITY);
        assertRegistrationRefunded(95L);
        assertEquals(4, inventory(FREE_TICKET), "取消必须回补 1 张库存");
        assertNoMoneyMoved();
    }

    // ------------------------------------------------------- 免费两张(审计订单91,单行 order_num=2)

    @Test
    void freeTwoTicketSingleRowParentOrderCancelsWithoutPayoutAndRestoresBothSeats() throws Exception {
        seedReg(91L, "A202609130B1D9F42", "P202609133A2AF4D6", MULTI_ACTIVITY, MULTI_TICKET, 9004L,
                2, "0.00", "0.00", 0, 0);

        ActivityCancelResult result = cancel(MULTI_ACTIVITY);

        assertEquals(1, result.getRefundedOrders(), "两张免费票是同一个父单,只算一单且必须能取消");
        assertCancelled(MULTI_ACTIVITY);
        assertRegistrationRefunded(91L);
        assertEquals(4, inventory(MULTI_TICKET), "order_num=2 必须回补 2 张");
        assertNoMoneyMoved();
    }

    @Test
    void freeTwoTicketTwoRowParentOrderCancelsWithoutPayout() throws Exception {
        seedReg(96L, "A20260913ROW1", "P20260913TWOROWS", MULTI_ACTIVITY, MULTI_TICKET, 9004L,
                1, "0.00", "0.00", 0, 0);
        seedReg(97L, "A20260913ROW2", "P20260913TWOROWS", MULTI_ACTIVITY, MULTI_TICKET, 9004L,
                1, "0.00", "0.00", 0, 0);

        ActivityCancelResult result = cancel(MULTI_ACTIVITY);

        assertEquals(1, result.getRefundedOrders());
        assertRegistrationRefunded(96L);
        assertRegistrationRefunded(97L);
        assertEquals(4, inventory(MULTI_TICKET));
        assertNoMoneyMoved();
    }

    @Test
    void freeSingleTicketWithoutParentOrderCancelsWithoutPayout() throws Exception {
        // 存量单票(无父单):走单票守卫分支,同样不得被「原支付主单」类校验拦住。
        seedReg(88L, "A20260913SINGLE", null, FREE_ACTIVITY, FREE_TICKET, 9004L,
                1, "0.00", "0.00", 0, 0);

        ActivityCancelResult result = cancel(FREE_ACTIVITY);

        assertEquals(1, result.getRefundedOrders());
        assertRegistrationRefunded(88L);
        assertEquals(4, inventory(FREE_TICKET));
        assertNoMoneyMoved();
    }

    @Test
    void pointsOnlyParentOrderCancelsWithoutPayoutAndReturnsPoints() throws Exception {
        seedReg(90L, "A20260913POINTS", "P20260913POINTS", FREE_ACTIVITY, FREE_TICKET, 9004L,
                1, "0.00", "0.00", 0, 200);
        when(umsMemberMapper.addPoints(9004L, 200)).thenReturn(1);

        ActivityCancelResult result = cancel(FREE_ACTIVITY);

        assertEquals(1, result.getRefundedOrders());
        assertRegistrationRefunded(90L);
        verify(umsMemberMapper).addPoints(9004L, 200);
        assertNoMoneyMoved();
    }

    // ------------------------------------------------------------------ 负控:付费单 fail-closed

    @Test
    void paidSnapshotMissingPayerFailsClosedWithoutSideEffects() throws Exception {
        // payable 丢失但 wechat 有值 = 有现金痕迹的付费单。必须 409,绝不能被当成免费单静默退 0。
        seedReg(99L, "A20260913PAIDLOST", "P20260913PAIDLOST", PAID_ACTIVITY, PAID_TICKET, 9005L,
                1, "0.00", "10.00", 0, 0);

        ServiceException conflict = assertThrows(ServiceException.class, () -> cancel(PAID_ACTIVITY));

        assertEquals(Integer.valueOf(409), conflict.getCode());
        assertActivityNotCancelled(PAID_ACTIVITY);
        assertEquals(2, paymentStatus(99L), "拦下后报名必须维持已支付,不能被改动");
        assertEquals(3, inventory(PAID_TICKET), "拦下后不得回补库存");
        assertNoMoneyMoved();
    }

    @Test
    void paidParentOrderWithMultiplePayerSnapshotsFailsClosed() throws Exception {
        seedReg(92L, "A20260913MULTIPAY1", "P20260913MULTIPAY", PAID_ACTIVITY, PAID_TICKET, 9005L,
                1, "68.00", "68.00", 0, 0);
        seedReg(93L, "A20260913MULTIPAY2", "P20260913MULTIPAY", PAID_ACTIVITY, PAID_TICKET, 9005L,
                1, "68.00", "68.00", 0, 0);

        ServiceException conflict = assertThrows(ServiceException.class, () -> cancel(PAID_ACTIVITY));

        assertEquals(Integer.valueOf(409), conflict.getCode());
        assertActivityNotCancelled(PAID_ACTIVITY);
        assertEquals(2, paymentStatus(92L), "拦下后两条报名都必须维持已支付");
        assertEquals(2, paymentStatus(93L), "拦下后两条报名都必须维持已支付");
        assertNoMoneyMoved();
    }

    // -------------------------------------------------------------- 正控:付费路径金额未被改动

    @Test
    void paidParentOrderStillRefundsThroughPayoutOnce() throws Exception {
        seedReg(94L, "A20260913PAIDOK", "P20260913PAIDOK", PAID_ACTIVITY, PAID_TICKET, 9005L,
                1, "68.00", "68.00", 0, 0);

        ActivityCancelResult result = cancel(PAID_ACTIVITY);

        assertEquals(1, result.getRefundedOrders());
        ArgumentCaptor<RefundApplication> captured = ArgumentCaptor.forClass(RefundApplication.class);
        verify(refundService).saveRefund(captured.capture());
        assertEquals(94L, captured.getValue().getSourceId(), "付费单必须仍按原支付主单建退款单");
        assertEquals(0, new BigDecimal("68.00").compareTo(captured.getValue().getRefundAmount()),
                "退款金额规则不得因免费单修复而改变");
        verify(refundPayoutService).payoutAfterReview(999L);
        assertCancelled(PAID_ACTIVITY);
        assertEquals(4, inventory(PAID_TICKET));
    }

    // ------------------------------------------------------------------ helpers

    private ActivityCancelResult cancel(long activityId) {
        return transaction.execute(status ->
                service.cancelActivityByOwner(activityId, OWNER, "无法履约"));
    }

    /** 免费/拦下的订单:不得建退款单、不得触发打款、不得登记人工案件。 */
    private void assertNoMoneyMoved() {
        verify(refundService, never()).saveRefund(any(RefundApplication.class));
        verifyNoInteractions(refundPayoutService, manualRefundCaseService);
    }

    private void seedReg(long id, String registrationNo, String parentOrderNo, long activityId,
                         long ticketId, long memberId, int orderNum,
                         String payable, String wechat, int verification, long pointUsed) throws Exception {
        exec("INSERT INTO cms_registration(id,registration_no,parent_order_no,owner_type,owner_id,ticket_id,"
                + "ticket_name,member_id,order_num,ticket_price,original_amount,total_amount,payable_amount,"
                + "wechat_payment_amount,point_used,point_deduct_amount,payment_status,payment_type,"
                + "registration_status,verification_status,pay_expire_time,create_time,update_time) VALUES ("
                + id + ",'" + registrationNo + "'," + (parentOrderNo == null ? "NULL" : "'" + parentOrderNo + "'")
                + ",2," + activityId + "," + ticketId + ",'票'," + memberId + "," + orderNum + ","
                + payable + "," + payable + "," + payable + "," + payable + "," + wechat + "," + pointUsed + ",0,"
                + "2,1,2," + verification + ",date_add(now(), interval 1 day),now(),now())");
    }

    private void assertCancelled(long activityId) {
        Integer cancelled = jdbc.queryForObject(
                "select count(*) from cms_activity where id=? and cancel_time is not null"
                        + " and status=0 and publish_status=0", Integer.class, activityId);
        assertEquals(1, cancelled, "活动必须落取消态并下架");
    }

    private void assertActivityNotCancelled(long activityId) {
        Integer cancelled = jdbc.queryForObject(
                "select count(*) from cms_activity where id=? and cancel_time is not null",
                Integer.class, activityId);
        assertEquals(0, cancelled, "守卫拦下时不得改活动状态");
    }

    private void assertRegistrationRefunded(long registrationId) {
        assertEquals(4, paymentStatus(registrationId), "报名必须落已退款 payment_status=4");
        Integer status = jdbc.queryForObject(
                "select registration_status from cms_registration where id=?", Integer.class, registrationId);
        assertEquals(3, status, "报名必须落已取消 registration_status=3");
    }

    private int paymentStatus(long registrationId) {
        return jdbc.queryForObject(
                "select payment_status from cms_registration where id=?", Integer.class, registrationId);
    }

    private int inventory(long ticketId) {
        return jdbc.queryForObject(
                "select remaining_inventory from oms_ticket where id=?", Integer.class, ticketId);
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(key + " 未设置:真库 IT 只认门禁注入的 CHENGYIN_TEST_MYSQL_*,不回落本机默认值");
        }
        return value;
    }

    private static String resolveRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.exists(cwd.resolve("sql/base_schema_20260818.sql"))) return cwd.toString();
        Path parent = cwd.getParent();
        if (parent != null && Files.exists(parent.resolve("sql/base_schema_20260818.sql"))) {
            return parent.toString();
        }
        throw new IllegalStateException("找不到仓库根(sql/base_schema_20260818.sql),cwd=" + cwd);
    }

    private static String baseDdl(String table) throws Exception {
        String source = Files.readString(Paths.get(ROOT, "sql/base_schema_20260818.sql"), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("CREATE TABLE `" + table + "` \\(.*?\\n\\) [^\\n]*;",
                Pattern.DOTALL).matcher(source);
        if (!matcher.find()) {
            throw new IllegalStateException("base_schema 缺少 " + table);
        }
        return matcher.group().replaceAll(";$", "");
    }

    private String databaseUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database + PARAMS;
    }

    private void execAdmin(String statement) throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT + "/" + PARAMS, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private void exec(String statement) throws Exception {
        try (Connection c = DriverManager.getConnection(databaseUrl(), USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }
}

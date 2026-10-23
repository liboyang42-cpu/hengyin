package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.MinorGuardianAuthorization;
import com.chengyinhub.business.domain.UserInvoiceApplication;
import com.chengyinhub.business.domain.UserInvoiceOrderLine;
import com.chengyinhub.business.domain.UserInvoiceTitle;
import com.chengyinhub.business.mapper.MinorGuardianAuthorizationMapper;
import com.chengyinhub.business.mapper.UserInvoiceMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DOC2 F7 在真 MySQL 8 上的证明：表结构取仓库真源（base_schema 的 cms_registration / refund_application
 * + 本批两份迁移，不手写），迁移可重跑，mapper SQL 真执行。
 *
 * <p>钉住：税号/抬头名落 v1: 密文且回读解密；可开票口径（本人、已支付、报名成功、有微信实付、无待审/已通过退款、
 * 未被占用）；唯一键让一单不能被两张申请同时占用，驳回释放后可再申请；监护授权的 CAS 激活、撤回带本人身份、
 * 有效授权只认当前政策版本与有效期。
 */
class ComplianceF7MyBatisTest {
    private static final String INVOICE_MIGRATION = "chengyinhub-admin/sql/migration_20261022_f7_user_invoice.sql";
    private static final String GUARDIAN_MIGRATION = "chengyinhub-admin/sql/migration_20261022_f7_minor_guardian.sql";
    /** 测试专用 32 字节全零 AES 密钥，运行时生成，不在源码里留密钥样式的字面量。 */
    private static final String KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);
    private static final long ME = 7L, OTHER = 8L;

    @Test
    void invoiceMigrationIsRerunnableAndMapperEnforcesOwnershipEncryptionAndSingleOccupancy() throws Exception {
        Object previous = ReflectionTestUtils.getField(PiiCryptoService.class, "instance");
        new PiiCryptoService(KEY, "f7-invoice-it-hmac").register();
        try (SqlSession session = openSession("compliance_f7_invoice")) {
            Connection c = session.getConnection();
            String base = read("sql/base_schema_20260818.sql");
            exec(c, "DROP TABLE IF EXISTS cms_registration", ddl(base, "cms_registration"),
                    "DROP TABLE IF EXISTS refund_application", ddl(base, "refund_application"));
            applyMigration(c, INVOICE_MIGRATION);
            applyMigration(c, INVOICE_MIGRATION);

            // 1 本人已付 / 2 本人已付 / 3 本人待付 / 4 别人已付 / 5 本人已付但退款待审 / 6 本人纯积分(无微信实付) / 7 本人已付但退款被驳回
            insertRegistration(c, 1, ME, 2, 2, "10.50");
            insertRegistration(c, 2, ME, 2, 2, "20.00");
            insertRegistration(c, 3, ME, 0, 1, "30.00");
            insertRegistration(c, 4, OTHER, 2, 2, "40.00");
            insertRegistration(c, 5, ME, 2, 2, "50.00");
            insertRegistration(c, 6, ME, 2, 2, "0.00");
            insertRegistration(c, 7, ME, 2, 2, "70.00");
            exec(c, "INSERT INTO refund_application(source_type,source_id,member_id,refund_amount,status,del_flag)"
                    + " VALUES ('registration',5,7,50.00,0,0),('registration',7,7,70.00,2,0)");

            UserInvoiceMapper mapper = session.getMapper(UserInvoiceMapper.class);
            List<UserInvoiceOrderLine> eligible = mapper.selectInvoiceableRegistrations(ME, null, 100);
            assertEquals(List.of(1L, 2L, 7L), eligible.stream().map(UserInvoiceOrderLine::getOrderId).sorted().toList(),
                    "只列本人、已支付、有微信实付、无待审/已通过退款的订单");
            assertEquals(0, mapper.selectInvoiceableRegistrationsForUpdate(ME, List.of(4L)).size(), "别人的订单查不到");

            UserInvoiceTitle title = new UserInvoiceTitle();
            title.setMemberId(ME);
            title.setTitleType("COMPANY");
            title.setTitleName("某某科技有限公司");
            title.setTaxNo("91310000MA1FL0XX3K");
            title.setIsDefault(1);
            title.setCreateTime(new Date());
            assertEquals(1, mapper.insertTitle(title));
            String storedTax = text(c, "SELECT tax_no FROM user_invoice_title WHERE id=" + title.getId());
            assertTrue(storedTax.startsWith("v1:"), "税号落密文");
            assertFalse(storedTax.contains("MA1FL0XX3K"));
            assertTrue(text(c, "SELECT title_name FROM user_invoice_title WHERE id=" + title.getId()).startsWith("v1:"));
            assertEquals("91310000MA1FL0XX3K", mapper.selectTitle(ME, title.getId()).getTaxNo(), "本人回读解密");
            assertNull(mapper.selectTitle(OTHER, title.getId()), "别人读不到");
            assertEquals(0, mapper.deleteTitle(OTHER, title.getId()), "别人删不掉");
            title.setMemberId(OTHER);
            title.setTitleName("篡改");
            assertEquals(0, mapper.updateTitle(title), "别人改不了");

            UserInvoiceApplication app = application("req-1", title.getId());
            assertEquals(1, mapper.insertApplication(app));
            assertEquals(2, mapper.insertOrderLines(List.of(line(app.getId(), 1L, "10.50"), line(app.getId(), 2L, "20.00"))));
            assertTrue(text(c, "SELECT tax_no FROM user_invoice_application WHERE id=" + app.getId()).startsWith("v1:"));
            assertEquals(List.of(7L), mapper.selectInvoiceableRegistrations(ME, null, 100).stream()
                    .map(UserInvoiceOrderLine::getOrderId).toList(), "已占用的订单不再可开票");
            assertEquals(List.of(1L), mapper.selectOccupiedOrderIds(ME, "registration", List.of(1L, 7L)));
            assertEquals(2, mapper.selectOrderLines(app.getId()).size());
            assertEquals("某某科技有限公司", mapper.selectApplication(ME, app.getId()).getTitleName(), "快照回读解密");
            assertNull(mapper.selectApplication(OTHER, app.getId()), "别人读不到申请单");

            // 一单不能被第二张申请同时占用：唯一键兜底
            UserInvoiceApplication second = application("req-2", title.getId());
            mapper.insertApplication(second);
            assertThrows(Exception.class, () -> mapper.insertOrderLines(List.of(line(second.getId(), 1L, "10.50"))));
            // 同一会员同一 requestId 只能落一张
            assertThrows(Exception.class, () -> mapper.insertApplication(application("req-1", title.getId())));

            assertEquals(0, mapper.cancelApplication(OTHER, app.getId(), new Date()), "别人撤不回");
            assertEquals(1, mapper.markRejected(app.getId(), "抬头有误", new Date()));
            assertEquals(0, mapper.markIssued(app.getId(), "X1", new Date()), "已驳回不能再开具");
            assertEquals(2, mapper.releaseOrderLines(app.getId()));
            assertEquals(1, mapper.insertOrderLines(List.of(line(second.getId(), 1L, "10.50"))), "释放后可再申请");
            assertEquals(1, mapper.markIssued(second.getId(), "24310000000012345678", new Date()));
            assertEquals("ISSUED", mapper.selectApplicationById(second.getId()).getStatus());
            assertEquals(0, mapper.cancelApplication(ME, second.getId(), new Date()), "已开具不能撤回");
        } finally {
            ReflectionTestUtils.setField(PiiCryptoService.class, "instance", previous);
        }
    }

    @Test
    void guardianMigrationIsRerunnableAndAuthorisationsBindPurposeVersionAndValidity() throws Exception {
        try (SqlSession session = openSession("compliance_f7_guardian")) {
            Connection c = session.getConnection();
            applyMigration(c, GUARDIAN_MIGRATION);
            applyMigration(c, GUARDIAN_MIGRATION);
            MinorGuardianAuthorizationMapper mapper = session.getMapper(MinorGuardianAuthorizationMapper.class);
            long guardian = 100L, ward = 200L, stranger = 300L;
            Date now = new Date();

            MinorGuardianAuthorization purchase = pending(guardian, "r-1", "PURCHASE", "a".repeat(64));
            assertEquals(1, mapper.insert(purchase));
            assertThrows(Exception.class, () -> mapper.insert(pending(guardian, "r-1", "PURCHASE", "b".repeat(64))),
                    "同一监护人同一 requestId 只能一条");
            assertEquals(purchase.getId(), mapper.selectByRequest(guardian, "r-1").getId());
            assertEquals(purchase.getId(), mapper.selectByInviteHashForUpdate("a".repeat(64)).getId());

            Date until = new Date(now.getTime() + 86_400_000L);
            assertEquals(0, mapper.activate(purchase.getId(), "c".repeat(64), ward, now, until, now), "邀请码不匹配不激活");
            assertEquals(0, mapper.activate(purchase.getId(), "a".repeat(64), guardian, now, until, now), "不能自己当自己的被监护人");
            assertEquals(1, mapper.activate(purchase.getId(), "a".repeat(64), ward, new Date(now.getTime() - 1000), until, now));
            assertEquals(0, mapper.activate(purchase.getId(), "a".repeat(64), ward, now, until, now), "只能激活一次");
            assertNull(mapper.selectByInviteHashForUpdate("a".repeat(64)), "激活后邀请码作废");

            Date probe = new Date(now.getTime() + 1000);
            assertEquals(1, mapper.countActive(ward, "PURCHASE", "minor-2026-10-22", probe));
            assertEquals(0, mapper.countActive(ward, "INFO_PROCESS", "minor-2026-10-22", probe), "用途互不代替");
            assertEquals(0, mapper.countActive(ward, "PURCHASE", "minor-2099-01-01", probe), "政策版本不同不认");
            assertEquals(0, mapper.countActive(ward, "PURCHASE", "minor-2026-10-22", new Date(until.getTime() + 1000)), "过期不认");
            assertEquals(1, mapper.countActiveBetween(guardian, ward, "minor-2026-10-22", probe));
            assertEquals(0, mapper.countActiveBetween(stranger, ward, "minor-2026-10-22", probe));

            MinorGuardianAuthorization renewed = pending(guardian, "r-2", "PURCHASE", "d".repeat(64));
            mapper.insert(renewed);
            mapper.activate(renewed.getId(), "d".repeat(64), ward, now, until, now);
            assertEquals(1, mapper.supersedeActive(ward, guardian, "PURCHASE", renewed.getId(), now));
            assertEquals("SUPERSEDED", mapper.selectById(purchase.getId()).getStatus());

            assertEquals(0, mapper.revoke(renewed.getId(), stranger, now), "旁人撤不回");
            assertEquals(1, mapper.revoke(renewed.getId(), ward, now), "被监护人可撤回");
            assertEquals(0, mapper.countActive(ward, "PURCHASE", "minor-2026-10-22", probe));
            assertEquals(2, mapper.selectByMember(ward).size());
            assertEquals(1, mapper.rotateInvite(insertPending(mapper, guardian, "r-3"), guardian, "e".repeat(64), until, now));
            assertEquals(0, mapper.rotateInvite(renewed.getId(), guardian, "f".repeat(64), until, now), "非 PENDING 不换码");
        }
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private static long insertPending(MinorGuardianAuthorizationMapper mapper, long guardian, String requestId) {
        MinorGuardianAuthorization row = pending(guardian, requestId, "INFO_PROCESS", "9".repeat(64));
        mapper.insert(row);
        return row.getId();
    }

    private static MinorGuardianAuthorization pending(long guardian, String requestId, String purpose, String hash) {
        MinorGuardianAuthorization row = new MinorGuardianAuthorization();
        row.setGuardianMemberId(guardian);
        row.setPurpose(purpose);
        row.setPolicyVersion("minor-2026-10-22");
        row.setRelation("PARENT");
        row.setEvidenceMethod(MinorGuardianAuthorization.EVIDENCE_MUTUAL_ACCOUNT_CONFIRM);
        row.setStatus("PENDING");
        row.setRequestId(requestId);
        row.setInviteTokenHash(hash);
        row.setInviteExpiresAt(new Date(System.currentTimeMillis() + 3_600_000L));
        row.setValidDays(30);
        row.setCreateTime(new Date());
        return row;
    }

    private static UserInvoiceApplication application(String requestId, Long titleId) {
        UserInvoiceApplication app = new UserInvoiceApplication();
        app.setApplicationNo("INV-" + requestId);
        app.setMemberId(ME);
        app.setRequestId(requestId);
        app.setTitleId(titleId);
        app.setTitleType("COMPANY");
        app.setTitleName("某某科技有限公司");
        app.setTaxNo("91310000MA1FL0XX3K");
        app.setTotalAmount(new BigDecimal("30.50"));
        app.setOrderCount(2);
        app.setStatus("SUBMITTED");
        app.setProvider("MANUAL");
        app.setCreateTime(new Date());
        return app;
    }

    private static UserInvoiceOrderLine line(Long applicationId, long orderId, String amount) {
        UserInvoiceOrderLine l = new UserInvoiceOrderLine();
        l.setApplicationId(applicationId);
        l.setMemberId(ME);
        l.setOrderSource("registration");
        l.setOrderId(orderId);
        l.setAmount(new BigDecimal(amount));
        l.setCreateTime(new Date());
        return l;
    }

    private static void insertRegistration(Connection c, long id, long member, int paymentStatus, int registrationStatus,
                                           String wechat) throws Exception {
        exec(c, "INSERT INTO cms_registration(id,registration_no,owner_id,ticket_id,ticket_price,member_id,original_amount,"
                + "total_amount,payable_amount,payment_status,registration_status,pay_expire_time,wechat_payment_amount,"
                + "payment_time,activity_title) VALUES (" + id + ",'R" + id + "',1,1,0," + member + ",0,0,0,"
                + paymentStatus + "," + registrationStatus + ",NOW()," + wechat + ",NOW(),'活动" + id + "')");
    }

    /** 按仓库执行器同样的方式逐条执行迁移(跳过注释行),并校验末尾的 scalar-true 断言。 */
    private static void applyMigration(Connection c, String relative) throws Exception {
        String text = read(relative);
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.trim().startsWith("--")) body.append(line).append('\n');
        }
        List<String> statements = ItSchema.splitStatements(body.toString());
        try (Statement st = c.createStatement()) {
            for (int i = 0; i < statements.size() - 1; i++) st.execute(statements.get(i));
            try (ResultSet rs = st.executeQuery(statements.get(statements.size() - 1))) {
                assertTrue(rs.next() && rs.getInt(1) == 1, "迁移末尾断言未通过:" + relative);
            }
        }
    }

    private SqlSession openSession(String db) throws Exception {
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MybatisItDatabase.url(db), MybatisItDatabase.user(), MybatisItDatabase.password())));
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (Class<?> mapper : new Class<?>[] {UserInvoiceMapper.class, MinorGuardianAuthorizationMapper.class}) {
            String resource = "mapper/business/" + mapper.getSimpleName() + ".xml";
            cfg.addMapper(mapper);
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(cfg).openSession(true);
    }

    private static String ddl(String sql, String table) {
        Matcher m = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?`?" + table + "`? \\(.*?\\) ENGINE=[^;]*;",
                Pattern.DOTALL).matcher(sql);
        if (!m.find()) throw new IllegalStateException("仓库里找不到 " + table + " 的建表语句");
        return m.group().replaceAll(";$", "");
    }

    private static String read(String relative) throws Exception {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("sql/base_schema_20260818.sql"))) dir = dir.getParent();
        if (dir == null) throw new IllegalStateException("找不到仓库根");
        return new String(Files.readAllBytes(dir.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static void exec(Connection c, String... sql) throws Exception {
        try (Statement st = c.createStatement()) {
            for (String s : sql) st.execute(s);
        }
    }

    private static String text(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) throw new IllegalStateException("无结果: " + sql);
            return rs.getString(1);
        }
    }
}

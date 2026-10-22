package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.DeregistrationStatusVO;
import com.chengyinhub.business.domain.vo.ShopVisitorAvatarVO;
import com.chengyinhub.business.mapper.AccountDeregistrationMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.mapper.PersonalExplorationExportMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IAccountDeregistrationService;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.business.service.IUmsMemberWithdrawalService;
import com.chengyinhub.business.service.PersonalExplorationExportService;
import com.chengyinhub.business.service.impl.AccountDeregistrationServiceImpl;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.business.service.impl.ConsentRecordServiceImpl;
import com.chengyinhub.business.service.impl.RoamLocationConsentService;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 隐私卡服务端(PR02 目的限定撤回 / PR04 历史可见性 / PR05 导出与注销)在真 MySQL 8 + 真 mapper XML 上。
 *
 * <p>建表全部来自仓库真实 DDL:生产 SHOW CREATE 快照 {@code it-schema/run41-prod-ddl.sql}
 * (cms_topic / club / ums_member 等)、基线 {@code sql/base_schema_20260818.sql} 中逐表摘出的
 * consent_record / roam_session / roam_shop_visit / account_deregistration / refund_application,
 * 再按序叠加其后的 migration(consent scope 列、presence 列、路线局表、圈层供给表)。不新增 it-schema 文件。
 *
 * <p>服务走生产实现 + Spring 事务代理;Mockito 只替身非存储协作者(会话注册表、资金/商家/俱乐部阻断项来源)。
 * 一类一库 {@code privacy_visibility_export},结束 DROP 并回读确认。
 *
 * <p>⚠️ PR04「撤回漫游定位 / 关闭附近的人后不应再出现在店铺访客头像里」<b>本类没有断言</b>:
 * 2026-10-22 本地实测 {@code RoamMapper.selectShopVisitorAvatars} 不读 consent_record 也不读
 * roam_session.presence_visible,撤回者头像照常返回(缺陷另报,不在测试里压红也不 @Disabled)。
 * 这里只提交已成立的正向口径:30 天窗口、按店隔离、来源类型隔离、已注销账号不出现。
 */
class PrivacyVisibilityExportMySqlIT {

    private static final String SUITE = "privacy_visibility_export";
    private static final String PRIVACY_VERSION = "pp-2026-10";
    private static final long MEMBER_A = 41L;
    private static final long MEMBER_B = 42L;
    private static final long MERCHANT = 7001L;
    private static final long SHOP = 501L;
    private static final long OTHER_SHOP = 502L;
    private static final long CLOCK = OffsetDateTime.parse("2026-10-12T12:00:00+08:00").toInstant().toEpochMilli();

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate db;
    private static DataSourceTransactionManager transactionManager;
    private static SqlSessionTemplate sql;
    private static String database;

    @BeforeAll
    static void start() throws Exception {
        assertEquals("127.0.0.1", MybatisItDatabase.host(), "只允许显式本机 scratch");
        dataSource = new DriverManagerDataSource(MybatisItDatabase.url(SUITE),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        database = MybatisItDatabase.databaseName(SUITE);
        db = new JdbcTemplate(dataSource);
        String version = db.queryForObject("select version()", String.class);
        assertTrue(version != null && version.startsWith("8.0."), "必须使用 MySQL 8.0,实际 " + version);
        try (Connection c = dataSource.getConnection()) {
            ItSchema.apply(c, "it-schema/run41-prod-ddl.sql");
            for (String table : Arrays.asList("consent_record", "roam_session", "roam_shop_visit",
                    "account_deregistration", "refund_application")) {
                db.execute("DROP TABLE IF EXISTS `" + table + "`");
                db.execute(createTable(repoFile("sql/base_schema_20260818.sql"), table));
            }
            db.execute("DROP TABLE IF EXISTS merchant_circle_supply_profile");
            db.execute(createTable(repoFile("chengyinhub-admin/sql/migration_circle_theme_batch2_20260821.sql"),
                    "merchant_circle_supply_profile"));
            ItSchema.applyMigrations(c, "sql/migration_merchant_onsite_consent_scope_20260815.sql",
                    "sql/migration_roam_presence_20260910.sql",
                    "sql/migration_20261012_roam_presence_privacy.sql",
                    "sql/migration_20260823_topic_route_graph.sql");
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ConsentRecordMapper.xml"),
                new ClassPathResource("mapper/business/RoamMapper.xml"),
                new ClassPathResource("mapper/business/AccountDeregistrationMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicMapper.xml"),
                new ClassPathResource("mapper/business/PersonalExplorationExportMapper.xml"));
        sql = new SqlSessionTemplate(factory.getObject());
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @AfterAll
    static void cleanup() {
        if (database == null) return;
        assertTrue(database.matches("chengyin_mybatis_privacy_visibility_export[a-z0-9_]*"), "只许 DROP 本套 scratch");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:"
                + MybatisItDatabase.port() + "/?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password()));
        admin.execute("DROP DATABASE `" + database + "`");
        assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?",
                Integer.class, database), "scratch 清理必须回读确认");
    }

    @BeforeEach
    void wipe() {
        for (String table : Arrays.asList("consent_record", "roam_shop_visit", "roam_session", "ums_member",
                "account_deregistration", "refund_application", "topic_route_session", "cms_topic")) {
            db.execute("delete from " + table);
        }
        for (long member = 41; member <= 48; member++) {
            db.update("insert into ums_member(id,nickname,avatar,del_flag,phone) values(?,?,?,0,?)",
                    member, "m" + member, "avatar-" + member, "1380000" + member);
        }
    }

    // ==================== PR02 目的限定撤回 ====================

    /**
     * PR02:撤回 roam_location 只影响 roam_location 本身 —— home_city_location、报名信息共享、
     * 对某商家的到店数据共享(scope=MERCHANT)最新态原样;撤回是追加一行,不改同意行;同 requestId 重放不再追加。
     */
    @Test
    void revokingRoamLocationLeavesOtherPurposesUnchangedAndIsAppendOnly() {
        RoamLocationConsentService location = locationConsent();
        ConsentRecordServiceImpl consents = consentService();
        assertEquals("AGREE", location.record(MEMBER_A, "roam_location", "AGREE", "a-roam-agree", "", "127.0.0.1")
                .getEventType());
        assertEquals("AGREE", location.record(MEMBER_A, "home_city_location", "AGREE", "a-home-agree", "", "127.0.0.1")
                .getEventType());
        assertEquals(1, consents.record(MEMBER_A, "activity_host_data_sharing", "ahds-v1", "activity_signup",
                "AGREE", "a-signup-agree", "127.0.0.1"));
        assertEquals(1, consents.record(MEMBER_A, "merchant_onsite_data_sharing", "mods-v1", "merchant_redeem",
                "MERCHANT", MERCHANT, "AGREE", "a-merchant-agree", "127.0.0.1"));
        location.record(MEMBER_B, "roam_location", "AGREE", "b-roam-agree", "", "127.0.0.1");
        db.update("insert into roam_session(id,member_id,status,started_at,presence_visible) values"
                + "(901,?,0,now(),1),(902,?,0,now(),1)", MEMBER_A, MEMBER_B);
        assertThrows(IllegalArgumentException.class, () -> consents.record(MEMBER_A, "privacy_policy", PRIVACY_VERSION,
                "roam_location", "REVOKE", "a-bypass", "127.0.0.1"), "前置:定位授权不得绕过当前态校验直写");

        List<Map<String, Object>> before = db.queryForList("select * from consent_record order by id");
        ConsentRecord homeBefore = consents.latest(MEMBER_A, "privacy_policy", "home_city_location");
        ConsentRecord signupBefore = consents.latest(MEMBER_A, "activity_host_data_sharing", "activity_signup");
        ConsentRecord merchantBefore = consents.latest(MEMBER_A, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", MERCHANT);
        assertNotNull(merchantBefore);

        ConsentRecord revoked = location.record(MEMBER_A, "roam_location", "REVOKE", "a-roam-revoke", null, "127.0.0.1");
        assertEquals("REVOKE", revoked.getEventType());

        // ── 追加,不改写:原有每一行逐列原样,只多出撤回那一行 ──
        List<Map<String, Object>> after = db.queryForList("select * from consent_record order by id");
        assertEquals(before.size() + 1, after.size(), "撤回只追加一行");
        assertEquals(before, after.subList(0, before.size()), "撤回不得 update 任何既有行(含 roam_location 的同意行)");
        assertEquals("a-roam-revoke", after.get(after.size() - 1).get("request_id"));
        assertEquals(1, db.queryForObject("select count(*) from consent_record where member_id=? and scene='roam_location'"
                + " and event_type='AGREE'", Integer.class, MEMBER_A), "同意行仍在");

        // ── roam_location 自己:最新态为 REVOKE,当前同意失效,在途会话的附近展示同一事务关闭 ──
        assertEquals("REVOKE", consents.latest(MEMBER_A, "privacy_policy", "roam_location").getEventType());
        assertFalse(consents.hasCurrentAgreement(MEMBER_A, "privacy_policy", "roam_location", PRIVACY_VERSION));
        assertThrows(ServiceException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> location.requireCurrentAgreement(MEMBER_A)), "撤回后不再接收新位置");
        assertEquals(0, db.queryForObject("select presence_visible from roam_session where id=901", Integer.class));

        // ── 其他目的:最新态逐项原样 ──
        assertSameRecord(homeBefore, consents.latest(MEMBER_A, "privacy_policy", "home_city_location"), "常驻城市定位");
        assertTrue(consents.hasCurrentAgreement(MEMBER_A, "privacy_policy", "home_city_location", PRIVACY_VERSION));
        assertSameRecord(signupBefore, consents.latest(MEMBER_A, "activity_host_data_sharing", "activity_signup"),
                "报名信息共享");
        assertTrue(consents.hasCurrentAgreement(MEMBER_A, "activity_host_data_sharing", "activity_signup", "ahds-v1"));
        assertSameRecord(merchantBefore, consents.latest(MEMBER_A, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", MERCHANT), "对商家的到店数据共享");
        assertTrue(consents.hasCurrentAgreement(MEMBER_A, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", MERCHANT, "mods-v1"));
        assertNull(consents.latest(MEMBER_A, "merchant_onsite_data_sharing", "merchant_redeem", "MERCHANT", MERCHANT + 1),
                "商家范围的同意不外溢到别的商家");

        // ── 另一名成员:同目的同意原样,在途会话展示原样 ──
        assertTrue(consents.hasCurrentAgreement(MEMBER_B, "privacy_policy", "roam_location", PRIVACY_VERSION));
        assertEquals(1, db.queryForObject("select presence_visible from roam_session where id=902", Integer.class),
                "别人的附近展示不被我的撤回关闭");

        // ── 同 requestId 重放撤回:返回原回执,不再追加 ──
        assertEquals(revoked.getId(), location.record(MEMBER_A, "roam_location", "REVOKE", "a-roam-revoke", null,
                "127.0.0.1").getId());
        assertEquals(after.size(), db.queryForObject("select count(*) from consent_record", Integer.class),
                "重放不追加");
    }

    // ==================== PR04 历史可见性(仅已成立的正向口径) ====================

    /**
     * PR04 正向口径:店铺访客头像只取 30 天内的局、只算这家店、只算这个来源类型、已注销账号不出现。
     * (撤回漫游定位 / 关闭附近的人的成员仍会出现 —— 缺陷另报,见类注释。)
     */
    @Test
    void shopVisitorAvatarsHonourWindowShopSourceAndDeletedAccounts() {
        db.update("update ums_member set del_flag=2 where id=47");
        db.update("insert into roam_session(id,member_id,status,started_at,finished_at,presence_visible) values"
                + "(1,43,1,now()-interval 3 day,now()-interval 3 day,0),"   // 30 天内结算 ⇒ 出现
                + "(2,44,1,now()-interval 40 day,now()-interval 40 day,0)," // 40 天前结算 ⇒ 不出现
                + "(3,45,0,now(),null,1),"                                  // 只去过另一家店
                + "(4,46,1,now()-interval 1 day,now()-interval 1 day,0),"   // 同 id 但来源类型 2
                + "(5,47,1,now()-interval 1 day,now()-interval 1 day,0),"   // 账号已注销(del_flag=2)
                + "(6,48,0,now(),null,1)");                                 // 进行中的局 ⇒ 出现
        db.update("insert into roam_shop_visit(member_id,session_id,source_type,source_id,shop_name,visited_at) values"
                + "(43,1,1,?,'阿荣的店',now()-interval 3 day),(44,2,1,?,'阿荣的店',now()-interval 40 day),"
                + "(45,3,1,?,'小满的店',now()),(46,4,2,?,'报名商家',now()-interval 1 day),"
                + "(47,5,1,?,'阿荣的店',now()-interval 1 day),(48,6,1,?,'阿荣的店',now())",
                SHOP, SHOP, OTHER_SHOP, SHOP, SHOP, SHOP);

        RoamMapper roam = sql.getMapper(RoamMapper.class);
        assertEquals(new TreeSet<String>(Arrays.asList("avatar-43", "avatar-48")),
                avatars(roam.selectShopVisitorAvatars(1, Arrays.asList(SHOP), 10), SHOP),
                "30 天外 / 别家店 / 别的来源类型 / 已注销账号都不出现");
        assertEquals(new TreeSet<String>(Collections.singletonList("avatar-45")),
                avatars(roam.selectShopVisitorAvatars(1, Arrays.asList(OTHER_SHOP), 10), OTHER_SHOP));
        assertEquals(new TreeSet<String>(Collections.singletonList("avatar-46")),
                avatars(roam.selectShopVisitorAvatars(2, Arrays.asList(SHOP), 10), SHOP), "来源类型 2 只看自己");
    }

    // ==================== PR05 导出:跨成员负控 ====================

    /**
     * PR05 导出:B 的 prepare/page 只含 B 自己的完成记录;A 签出的游标给 B 用被拒;
     * 把游标里的 owner 改成 B 再规范编码(游标只是位置不是授权),B 拿到的仍绝不含 A 的行。
     */
    @Test
    void exportNeverCrossesMembersAndForeignCursorIsRejected() {
        db.update("insert into cms_topic(id,member_id,name,status,user_status,del_flag,is_draft,is_template)"
                + " values(3,99,'公开路线',1,1,0,0,0)");
        db.update("insert into topic_route_session(id,activity_id,topic_id,member_id,config_version,config_snapshot_json,"
                + "state_json,status,seed,version,completed_at,create_time,update_time) values"
                + "(11,0,3,?,1,'{}','{}',1,1,0,'2026-10-12 08:00:00','2026-10-12 07:00:00','2026-10-12 08:00:00'),"
                + "(12,1,3,?,1,'{}','{}',1,1,0,'2026-10-12 09:00:00','2026-10-12 08:00:00','2026-10-12 09:00:00'),"
                + "(13,2,3,?,1,'{}','{}',1,1,0,'2026-10-12 10:00:00','2026-10-12 09:00:00','2026-10-12 10:00:00'),"
                + "(21,0,3,?,1,'{}','{}',1,1,0,'2026-10-12 08:30:00','2026-10-12 07:30:00','2026-10-12 08:30:00')",
                MEMBER_A, MEMBER_A, MEMBER_A, MEMBER_B);
        PersonalExplorationExportService export = exportService();

        Map<String, Object> preparedA = export.prepare(MEMBER_A, "2026-10-12", "2026-10-13", 1);
        assertEquals(3, preparedA.get("expectedRows"), "前置:A 有 3 条");
        Map<String, Object> preparedB = export.prepare(MEMBER_B, "2026-10-12", "2026-10-13", 100);
        assertEquals(1, preparedB.get("expectedRows"), "B 的 prepare 只数 B 自己的完成记录");
        Map<String, Object> pageB = export.page(MEMBER_B, (String) preparedB.get("cursor"));
        assertEquals(Collections.singletonList("21"), recordIds(pageB), "B 的分页只含 B 的行");
        assertEquals(Boolean.TRUE, pageB.get("complete"));

        // ── A 签出的游标(首页 + 翻页后)给 B 用:被拒 ──
        String cursorA = (String) preparedA.get("cursor");
        Map<String, Object> firstA = export.page(MEMBER_A, cursorA);
        assertEquals(Collections.singletonList("13"), recordIds(firstA), "前置:A 自己能翻到自己的行");
        String nextA = (String) firstA.get("nextCursor");
        assertNotNull(nextA);
        for (String foreign : Arrays.asList(cursorA, nextA)) {
            ServiceException rejected = assertThrows(ServiceException.class, () -> export.page(MEMBER_B, foreign));
            assertEquals("分页不属于当前账号", rejected.getMessage());
        }

        // ── 篡改 owner 后规范编码:位置换了人,数据仍按调用者取 —— 绝不出现 A 的行 ──
        for (String foreign : Arrays.asList(cursorA, nextA)) {
            String forged = withOwner(foreign, MEMBER_B);
            Map<String, Object> page = export.page(MEMBER_B, forged);
            for (String id : recordIds(page)) {
                assertTrue(id.equals("21"), "伪造游标翻出了别人的记录 " + id + ":" + page);
            }
        }
    }

    // ==================== PR05 注销:申请 → 幂等 → 撤销 ====================

    /**
     * PR05 注销:apply 落 PENDING(申请行 + ums_member 冻结标记);二次 apply 不新增行、冷静期截止不变;
     * 库里 PENDING 唯一键挡住并发的第二行;别人撤不了我的申请;本人撤销后恢复 NORMAL。
     */
    @Test
    void deregistrationApplyIsIdempotentAndOnlyOwnerCancelRestores() {
        IAccountDeregistrationService service = deregistrationService();
        assertEquals("NORMAL", service.status(MEMBER_A).getStatus());

        DeregistrationStatusVO applied = service.apply(MEMBER_A);
        assertEquals("PENDING", applied.getStatus());
        assertNotNull(applied.getExecuteAfter());
        assertEquals("PENDING", service.status(MEMBER_A).getStatus());
        assertEquals("PENDING", db.queryForObject("select deregister_status from ums_member where id=?", String.class,
                MEMBER_A));
        assertEquals(1, pendingRows(MEMBER_A));

        DeregistrationStatusVO again = service.apply(MEMBER_A);
        assertEquals("PENDING", again.getStatus(), "二次申请幂等");
        assertEquals(service.status(MEMBER_A).getExecuteAfter(), again.getExecuteAfter(), "冷静期截止不被重置");
        assertEquals(1, db.queryForObject("select count(*) from account_deregistration where member_id=?", Integer.class,
                MEMBER_A), "二次申请不新增行");
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> db.update(
                "insert into account_deregistration(member_id,status,apply_time,execute_after,create_time)"
                        + " values(?,'PENDING',now(),now()+interval 7 day,now())", MEMBER_A),
                "真 DDL 的 uk_dereg_pending_member 挡住并发的第二条 PENDING");

        // ── 别人撤不了我的申请,也不影响我的冻结状态 ──
        assertEquals("当前没有可撤销的注销申请",
                assertThrows(IllegalStateException.class, () -> service.cancel(MEMBER_B)).getMessage());
        assertEquals(1, pendingRows(MEMBER_A));
        assertEquals("NORMAL", service.status(MEMBER_B).getStatus());

        DeregistrationStatusVO cancelled = service.cancel(MEMBER_A);
        assertEquals("CANCELLED", cancelled.getStatus());
        assertEquals("CANCELLED", service.status(MEMBER_A).getStatus());
        assertEquals(0, pendingRows(MEMBER_A));
        Map<String, Object> member = db.queryForMap("select deregister_status,deregister_apply_time,"
                + "deregister_execute_after,del_flag,phone from ums_member where id=?", MEMBER_A);
        assertEquals("NORMAL", member.get("deregister_status"), "撤销后恢复 NORMAL");
        assertNull(member.get("deregister_apply_time"));
        assertNull(member.get("deregister_execute_after"));
        assertEquals(0, ((Number) member.get("del_flag")).intValue(), "撤销不得触碰账号存续");
        assertEquals("1380000" + MEMBER_A, member.get("phone"), "撤销不得匿名化");
        assertEquals("当前没有可撤销的注销申请",
                assertThrows(IllegalStateException.class, () -> service.cancel(MEMBER_A)).getMessage(), "撤销只能一次");
    }

    // ==================== 装配 ====================

    private static ComplianceProperties compliance() {
        ComplianceProperties properties = new ComplianceProperties();
        properties.setDocVersions(Collections.singletonMap("privacy_policy", PRIVACY_VERSION));
        return properties;
    }

    private static RoamLocationConsentService locationConsent() {
        RoamLocationConsentService target = new RoamLocationConsentService(sql.getMapper(RoamMapper.class),
                sql.getMapper(ConsentRecordMapper.class), compliance());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (RoamLocationConsentService) proxy.getProxy();
    }

    private static ConsentRecordServiceImpl consentService() {
        ConsentRecordServiceImpl service = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(service, "consentRecordMapper", sql.getMapper(ConsentRecordMapper.class));
        return service;
    }

    private static PersonalExplorationExportService exportService() {
        ProxyFactory proxy = new ProxyFactory(new FixedClockExport(sql.getMapper(PersonalExplorationExportMapper.class)));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (PersonalExplorationExportService) proxy.getProxy();
    }

    /** 真 AccountDeregistrationMapper;阻断项来源(资金/商家/俱乐部/订单/报名)与会话注册表是非存储替身,全部「无阻断」。 */
    private static IAccountDeregistrationService deregistrationService() {
        AccountDeregistrationServiceImpl target = new AccountDeregistrationServiceImpl();
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.selectUmsMemberById(anyLong())).thenAnswer(call -> {
            UmsMember member = new UmsMember();
            member.setId(call.getArgument(0));
            member.setDelFlag(0);
            member.setRole("player");
            member.setBalance(BigDecimal.ZERO);
            member.setPhone("1380000" + call.getArgument(0));
            return member;
        });
        ReflectionTestUtils.setField(target, "deregistrationMapper", sql.getMapper(AccountDeregistrationMapper.class));
        ReflectionTestUtils.setField(target, "memberMapper", members);
        ReflectionTestUtils.setField(target, "clubMapper", mock(ClubMapper.class));
        ReflectionTestUtils.setField(target, "merchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(target, "orderMapper", mock(OmsOrderMapper.class));
        ReflectionTestUtils.setField(target, "registrationMapper", mock(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(target, "cashWithdrawService", mock(ICashWithdrawService.class));
        ReflectionTestUtils.setField(target, "memberWithdrawalService", mock(IUmsMemberWithdrawalService.class));
        ReflectionTestUtils.setField(target, "appSessionRegistryService", mock(IAppSessionRegistryService.class));
        ReflectionTestUtils.setField(target, "alarmService", mock(AlarmService.class));
        ReflectionTestUtils.setField(target, "transactionManager", transactionManager);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(IAccountDeregistrationService.class);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (IAccountDeregistrationService) proxy.getProxy();
    }

    /** 导出 TTL 按 now() 判;固定时钟让游标在任何一天跑都有效。 */
    static class FixedClockExport extends PersonalExplorationExportService {
        FixedClockExport(PersonalExplorationExportMapper mapper) { super(mapper); }

        @Override
        protected long now() { return CLOCK; }
    }

    // ==================== 小工具 ====================

    /** 从仓库 DDL 文件里逐字摘出一张表的 CREATE TABLE(按引号感知的分号切分,先去掉整行注释)。 */
    private static String createTable(String text, String table) {
        StringBuilder stripped = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.startsWith("#")) continue;
            stripped.append(line).append('\n');
        }
        java.util.regex.Pattern head = java.util.regex.Pattern.compile(
                "(?is)^CREATE TABLE (IF NOT EXISTS )?`?" + table + "`?\\s*\\(.*");
        for (String statement : ItSchema.splitStatements(stripped.toString())) {
            if (head.matcher(statement).matches()) return statement;
        }
        throw new IllegalStateException("DDL 里找不到表 " + table);
    }

    private static String repoFile(String relativeToRepoRoot) throws Exception {
        Path path = Paths.get("..").resolve(relativeToRepoRoot);
        if (!Files.exists(path)) path = Paths.get(relativeToRepoRoot);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void assertSameRecord(ConsentRecord expected, ConsentRecord actual, String purpose) {
        assertNotNull(expected, "前置:" + purpose + "须先有同意");
        assertNotNull(actual, purpose + "的最新态不得消失");
        assertEquals(expected.getId(), actual.getId(), purpose + "的最新态必须仍是原来那一行");
        assertEquals("AGREE", actual.getEventType(), purpose + "仍为同意");
    }

    private static TreeSet<String> avatars(List<ShopVisitorAvatarVO> rows, long sourceId) {
        TreeSet<String> out = new TreeSet<String>();
        for (ShopVisitorAvatarVO row : rows) {
            assertEquals(sourceId, row.getSourceId().longValue(), "只返回所查的店");
            out.add(row.getAvatar());
        }
        return out;
    }

    private static List<String> recordIds(Map<String, Object> page) {
        List<String> ids = new ArrayList<String>();
        for (Object item : (List<?>) page.get("items")) ids.add(String.valueOf(((Map<?, ?>) item).get("recordId")));
        return ids;
    }

    private static String withOwner(String cursor, long owner) {
        String[] fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII).split("\\|", -1);
        assertEquals(10, fields.length, "前置:游标字段数");
        fields[1] = String.valueOf(owner);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.join("|", fields).getBytes(StandardCharsets.US_ASCII));
    }

    private static int pendingRows(long member) {
        return db.queryForObject("select count(*) from account_deregistration where member_id=? and status='PENDING'",
                Integer.class, member);
    }
}

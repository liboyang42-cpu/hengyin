package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.ExploreBundleOrder;
import com.chengyinhub.business.domain.ExploreBundleReservation;
import com.chengyinhub.business.domain.ExploreBundleUnit;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.exception.FreeExploreBundleException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ExploreBundleMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.SettlementClaimMapper;
import com.chengyinhub.business.mapper.SettlementRuleMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.support.FreeExploreBundleSwitches;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DOC2 F3 Phase 1 真库验收:本批账本迁移 + 真 ExploreBundleMapper.xml + 真 SettlementClaimMapper.xml。
 *
 * <ul>
 *   <li>迁移可重跑;表形状被改动后重跑必须 guard 报错(负控)。</li>
 *   <li>Phase 1 不动资金表 settlement_claim:不加 unit_code,7 列经济键唯一闸原样(单位维度留待 Phase 2 资金审批)。</li>
 *   <li>A、B 买同一套票:A 核销只动 A 的单位,B 余额不变;同一 registration 只冻结一张单;
 *       改产品不改已售快照;同单位同时只许一条在约;月度豁免唯一。</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreeExploreBundleLedgerMySqlIT {
    private static final String LEDGER = "sql/migration_doc2_f3_bundle_ledger_20261022.sql";
    private static final long TOPIC = 70L;
    private static final long CH1 = 701L;
    private static final long CH2 = 702L;
    private static final long MERCHANT = 9001L;
    private static final long OFFER1 = 801L;
    private static final long OFFER2 = 802L;

    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String databaseUrl;
    private String user;
    private String password;
    private ExploreBundleMapper bundleMapper;
    private SettlementClaimMapper claimMapper;
    private FreeExploreBundleServiceImpl service;
    private final Date now = new Date(1792000000000L);   // 固定时钟
    private final List<CmsTopicChapter> chapters = new ArrayList<>();
    private final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
    private final Map<Long, CmsRegistration> registrations = new HashMap<>();

    @BeforeAll
    void createScratchSchema() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("f3_bundle");
        if (!config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")) {
            throw new IllegalStateException("loopback task MySQL only");
        }
        database = "chengyin_f3_bundle_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        DriverManagerDataSource source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source);
        databaseUrl = config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?");
        user = config.user();
        password = config.password();
        db.execute(createTable(read("../sql/base_schema_20260818.sql"), "settlement_claim"));
        apply(LEDGER);
        apply(LEDGER);

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ExploreBundleMapper.xml"),
                new ClassPathResource("mapper/business/SettlementClaimMapper.xml"));
        SqlSessionTemplate template = new SqlSessionTemplate(factory.getObject());
        bundleMapper = template.getMapper(ExploreBundleMapper.class);
        claimMapper = template.getMapper(SettlementClaimMapper.class);
        service = newService();
    }

    @AfterAll
    void dropOnlyScratchDatabase() {
        if (database != null && admin != null) {
            admin.execute("DROP DATABASE " + database);
            assertEquals(0, admin.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, database));
        }
    }

    @Test
    void ledgerMigrationGuardRejectsChangedShape() throws Exception {
        db.execute("ALTER TABLE explore_bundle_waiver MODIFY waiver_month CHAR(8) NOT NULL");
        assertThrows(Exception.class, () -> apply(LEDGER), "形状被改的同名表不得当成已完成");
        db.execute("ALTER TABLE explore_bundle_waiver MODIFY waiver_month CHAR(7) NOT NULL COMMENT '自然月 yyyy-MM'");
        apply(LEDGER);
    }

    @Test
    void phase1LeavesSettlementClaimUntouched() {
        assertEquals(0, count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"
                + " AND table_name='settlement_claim' AND column_name='unit_code'"), "Phase 1 不给资金表加 unit_code");
        assertEquals(7, count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()"
                + " AND table_name='settlement_claim' AND index_name='uk_settlement_claim_key'"), "唯一键仍是存量 7 列");
        assertEquals(1, claimMapper.insertClaim(claim()));
        assertThrows(DataIntegrityViolationException.class, () -> claimMapper.insertClaim(claim()),
                "存量 7 列经济键唯一性不变");
    }

    @Test
    void twoHoldersOfSameBundleHaveIndependentBalancesAndSnapshotsAreFrozen() {
        CmsRegistration a = registration(1001L, 11L);
        CmsRegistration b = registration(1002L, 12L);
        ExploreBundleOrder orderA = service.freezeOnOrderCreated(a, topic(), activity());
        service.freezeOnOrderCreated(b, topic(), activity());
        // 同一张 registration(同一请求的重放)只会有一张套票单
        ExploreBundleOrder again = service.freezeOnOrderCreated(a, topic(), activity());
        assertEquals(orderA.getId(), again.getId());
        assertEquals(1, count("SELECT COUNT(*) FROM explore_bundle_order WHERE registration_id=1001"));
        assertEquals(2, count("SELECT COUNT(*) FROM explore_bundle_unit WHERE registration_id=1001"));

        service.onChapterRedeemed(1001L, CH1, MERCHANT, 5001L);
        assertEquals("REDEEMED", unitStatus(1001L, CH1));
        assertEquals("AVAILABLE", unitStatus(1002L, CH1), "A 的核销不影响 B 的余额");
        assertEquals("AVAILABLE", unitStatus(1001L, CH2));
        FreeExploreBundleException again2 = assertThrows(FreeExploreBundleException.class,
                () -> service.onChapterRedeemed(1001L, CH1, MERCHANT, 5002L));
        assertEquals("BUNDLE_NO_BALANCE", again2.getBusinessCode());
        Map<String, Object> viewB = service.orderView(12L, 1002L);
        assertTrue(String.valueOf(viewB).contains("AVAILABLE=1, RESERVED=0, REDEEMED=0"), String.valueOf(viewB));
        assertTrue(!String.valueOf(viewB).contains("REDEEMED=1"), String.valueOf(viewB));
        assertThrows(FreeExploreBundleException.class, () -> service.orderView(11L, 1002L), "A 读不到 B 的订单");

        // 撤销核销:单位退回可用
        service.onRedemptionReversed(5001L);
        assertEquals("AVAILABLE", unitStatus(1001L, CH1));

        // 编辑产品(章节改名)后新售单的产品版本变化,已售快照逐字不变
        String before = db.queryForObject("SELECT snapshot_json FROM explore_bundle_order WHERE registration_id=1001", String.class);
        chapters.get(0).setName("改过的章节名");
        service.freezeOnOrderCreated(registration(1003L, 13L), topic(), activity());
        assertEquals(before, db.queryForObject("SELECT snapshot_json FROM explore_bundle_order WHERE registration_id=1001", String.class));
        assertNotEquals(
                db.queryForObject("SELECT product_version FROM explore_bundle_order WHERE registration_id=1001", String.class),
                db.queryForObject("SELECT product_version FROM explore_bundle_order WHERE registration_id=1003", String.class));
        chapters.get(0).setName("第一章");
        assertEquals(0, count("SELECT COUNT(*) FROM explore_bundle_unit WHERE registration_id=1001 AND status='REDEEMED'"));
    }

    @Test
    void reservationsAreUniquePerUnitAndPenaltiesConsumeOnlyThatUnit() {
        service.freezeOnOrderCreated(registration(2001L, 21L), topic(), activity());
        Long unitId = unitId(2001L, CH1);
        Date in48h = new Date(now.getTime() + 48L * 3600_000L);
        Date in2h = new Date(now.getTime() + 2L * 3600_000L);
        ExploreBundleReservation first = service.reserve(21L, 2001L, unitId, OFFER1, in48h, "rsv-1");
        assertEquals("BOOKED", first.getStatus());
        assertEquals(first.getId(), service.reserve(21L, 2001L, unitId, OFFER1, in48h, "rsv-1").getId(), "同键重放回原预约");
        assertEquals("BUNDLE_IDEMPOTENCY_CONFLICT", assertThrows(FreeExploreBundleException.class,
                () -> service.reserve(21L, 2001L, unitId, OFFER1, in2h, "rsv-1")).getBusinessCode());
        assertThrows(DataIntegrityViolationException.class, () -> db.update(
                "INSERT INTO explore_bundle_reservation(unit_id,registration_id,holder_member_id,chapter_id,offer_id,"
                + "merchant_id,start_at,status,idempotency_key,request_fingerprint,create_time) VALUES(?,2001,21,?,?,?,"
                + "NOW(),'BOOKED','raw-dup',REPEAT('0',64),NOW())", unitId, CH1, OFFER1, MERCHANT),
                "同一单位同时只许一条在约");

        assertEquals("CANCELLED", service.cancelReservation(21L, first.getId(), false).getStatus(), ">=12h 免费取消");
        assertEquals("AVAILABLE", unitStatus(2001L, CH1));

        ExploreBundleReservation late = service.reserve(21L, 2001L, unitId, OFFER1, in2h, "rsv-2");
        assertEquals("WAIVED", service.cancelReservation(21L, late.getId(), true).getStatus(), "本月首次主动豁免");
        assertEquals("AVAILABLE", unitStatus(2001L, CH1));
        ExploreBundleReservation late2 = service.reserve(21L, 2001L, unitId, OFFER1, in2h, "rsv-3");
        assertEquals("BUNDLE_WAIVER_USED", assertThrows(FreeExploreBundleException.class,
                () -> service.cancelReservation(21L, late2.getId(), true)).getBusinessCode());
        assertEquals("LATE_CANCELLED", service.cancelReservation(21L, late2.getId(), false).getStatus());
        assertEquals("FORFEITED", unitStatus(2001L, CH1), "<12h 只消耗该单位");
        assertEquals("AVAILABLE", unitStatus(2001L, CH2), "其他单位不受影响");
        assertEquals(0, count("SELECT COUNT(*) FROM explore_bundle_unit WHERE registration_id=2001 AND redemption_id IS NOT NULL"),
                "罚没不是真实核销");
        Exception check = assertThrows(Exception.class, () -> db.update(
                "UPDATE explore_bundle_unit SET status='REDEEMED' WHERE id=?", unitId(2001L, CH2)));
        assertTrue(String.valueOf(check.getMessage()).contains("ck_bundle_unit_redeemed"),
                "REDEEMED 必须带真实 redemption_id:" + check.getMessage());
    }

    // ---------------------------------------------------------------- fixtures

    private FreeExploreBundleServiceImpl newService() {
        chapters.add(chapter(CH1, "第一章"));
        chapters.add(chapter(CH2, "第二章"));
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(chapters);
        MerchantChapterOfferMapper offerMapper = mock(MerchantChapterOfferMapper.class);
        List<MerchantChapterOffer> offers = Arrays.asList(offer(OFFER1, CH1), offer(OFFER2, CH2));
        when(offerMapper.selectByTopic(TOPIC)).thenReturn(offers);
        when(offerMapper.selectById(OFFER1)).thenReturn(offers.get(0));
        when(offerMapper.selectById(OFFER2)).thenReturn(offers.get(1));
        SettlementRuleMapper rules = mock(SettlementRuleMapper.class);
        when(rules.selectActiveRule(eq(0L), any())).thenReturn(null);
        when(registrationMapper.selectCmsRegistrationById(anyLong()))
                .thenAnswer(inv -> registrations.get(inv.<Long>getArgument(0)));
        FreeExploreBundleServiceImpl impl = new FreeExploreBundleServiceImpl();
        ReflectionTestUtils.setField(impl, "bundleMapper", bundleMapper);
        ReflectionTestUtils.setField(impl, "registrationMapper", registrationMapper);
        ReflectionTestUtils.setField(impl, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(impl, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(impl, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(impl, "settlementRuleMapper", rules);
        ReflectionTestUtils.setField(impl, "switches", new FreeExploreBundleSwitches(true, true, true));
        impl.clock = () -> now;
        return impl;
    }

    private CmsRegistration registration(Long id, Long memberId) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setMemberId(memberId);
        r.setPaymentStatus(2);
        r.setRegistrationStatus(2);
        r.setPayableAmount(new BigDecimal("99.00"));
        registrations.put(id, r);
        return r;
    }

    private CmsTopic topic() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setName("自由探索套票");
        t.setProductType(ProductType.FREE_EXPLORE);
        return t;
    }

    private CmsActivity activity() {
        CmsActivity a = new CmsActivity();
        a.setId(60L);
        a.setName("探索日");
        a.setEndDate(new Date(now.getTime() + 30L * 24 * 3600_000L));
        return a;
    }

    private static CmsTopicChapter chapter(Long id, String name) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(id);
        c.setTopicId(TOPIC);
        c.setName(name);
        c.setRequired(1);
        c.setTermsMode("REVSHARE");
        return c;
    }

    private MerchantChapterOffer offer(Long id, Long chapterId) {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setId(id);
        o.setTopicId(TOPIC);
        o.setChapterId(chapterId);
        o.setMerchantId(MERCHANT);
        o.setStatus(1);
        o.setTermsMode("REVSHARE");
        o.setPerHeadFee(new BigDecimal("15.00"));
        o.setQuotaTotal(0);
        return o;
    }

    private static SettlementClaim claim() {
        SettlementClaim c = new SettlementClaim();
        c.setCommercialContextType("ACTIVITY");
        c.setCommercialContextId(1L);
        c.setFulfillmentType("REDEMPTION");
        c.setFulfillmentId(9L);
        c.setBeneficiaryRole("MERCHANT");
        c.setBeneficiaryId(5L);
        c.setServiceUnit("CHAPTER_SUPPLY");
        c.setSettlementRoute("NONE");
        c.setSourceContractType("MERCHANT_CHAPTER_OFFER");
        c.setSourceContractId(3L);
        c.setStatus("ACTIVE");
        c.setCreateTime(new Date());
        return c;
    }

    private String unitStatus(Long registrationId, Long chapterId) {
        return db.queryForObject("SELECT status FROM explore_bundle_unit WHERE registration_id=? AND chapter_id=?",
                String.class, registrationId, chapterId);
    }

    private Long unitId(Long registrationId, Long chapterId) {
        for (ExploreBundleUnit unit : bundleMapper.selectUnitsByRegistration(registrationId)) {
            if (chapterId.equals(unit.getChapterId())) return unit.getId();
        }
        throw new IllegalStateException("unit missing");
    }

    private int count(String sql) {
        return db.queryForObject(sql, Integer.class);
    }

    /** 按仓库执行器同口径:去掉整行注释后按语句切分(单引号内的分号不切),逐条执行,末句是 scalar-true 断言。 */
    private void apply(String relative) throws Exception {
        List<String> statements = split(read(relative));
        // 迁移用临时表做 guard ⇒ 必须同一连接执行(与真执行器一致)。
        SingleConnectionDataSource one = new SingleConnectionDataSource(databaseUrl, user, password, true);
        try {
            JdbcTemplate session = new JdbcTemplate(one);
            for (int i = 0; i < statements.size() - 1; i++) session.execute(statements.get(i));
            assertEquals(Integer.valueOf(1), session.queryForObject(statements.get(statements.size() - 1), Integer.class),
                    "迁移末尾断言未通过:" + relative);
        } finally {
            one.destroy();
        }
    }

    private static List<String> split(String text) {
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.trim().startsWith("--")) body.append(line).append('\n');
        }
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        String s = body.toString();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'') quoted = !quoted;   // '' 转义 = 连续两次翻转,状态不变
            if (ch == ';' && !quoted) {
                if (!current.toString().trim().isEmpty()) out.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (!current.toString().trim().isEmpty()) out.add(current.toString().trim());
        return out;
    }

    private static String createTable(String sql, String table) {
        Matcher m = Pattern.compile("CREATE TABLE `" + table + "` \\(.*?\\) ENGINE=[^;]*;", Pattern.DOTALL).matcher(sql);
        if (!m.find()) throw new IllegalStateException("base schema 里找不到 " + table);
        return m.group().replaceAll(";$", "");
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(Path.of(relative)), StandardCharsets.UTF_8);
    }
}

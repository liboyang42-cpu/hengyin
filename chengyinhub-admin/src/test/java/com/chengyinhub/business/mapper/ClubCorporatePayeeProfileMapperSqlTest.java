package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubCorporatePayeeProfile;
import com.chengyinhub.business.domain.TopicSalePayeeSnapshot;
import com.chengyinhub.business.security.PiiCryptoService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4 收款人 mapper 的 H2 层覆盖：密文落库、两种 current 读法、审核 CAS 的三个条件、快照不可覆盖。
 *
 * <p>唯一 current 守卫是 MySQL 的生成列 + 唯一索引，H2 建不出来 ⇒ 那条由
 * {@code ClubCorporatePayeeMybatisIntegrationTest} 在真库证，本类不冒充。
 */
class ClubCorporatePayeeProfileMapperSqlTest {

    private static final long CLUB = 8L;
    private static final long TOPIC = 23L;
    private static final String LEGAL_NAME = "上海城瘾探索者体育文化有限公司";
    private static final String ACCOUNT_NO = "6222020200011112222";

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        // TypeHandler 由 MyBatis 实例化、靠静态单例拿密钥；不配就 fail-closed 拒写，那样证明不了密文。
        new PiiCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "club-payee-h2-hmac").register();
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:club_payee_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        configuration.addMapper(ClubCorporatePayeeProfileMapper.class);
        String resource = "mapper/business/ClubCorporatePayeeProfileMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            s.execute("create table club_corporate_payee_profile(id bigint auto_increment primary key,"
                    + "club_id bigint,profile_version bigint,is_current tinyint,legal_name varchar(512),"
                    + "unified_social_credit_code varchar(512),credit_code_index char(64),"
                    + "license_object_key varchar(512),license_hash char(64),bank_account_name varchar(512),"
                    + "bank_name varchar(255),corporate_bank_account_no varchar(512),invoice_type varchar(32),"
                    + "tax_no varchar(512),finance_contact_name varchar(512),finance_contact_phone varchar(512),"
                    + "facts_hash char(64),verify_status varchar(16),verified_by bigint,verified_at timestamp,"
                    + "verify_result varchar(32),created_by bigint,"
                    + "created_at timestamp default current_timestamp,unique(club_id,profile_version))");
            s.execute("create table topic_sale_payee_snapshot(id bigint auto_increment primary key,"
                    + "topic_id bigint,club_id bigint,profile_id bigint,profile_version bigint,"
                    + "facts_hash char(64),license_hash char(64),frozen_at timestamp,unique(topic_id,club_id))");
        }
    }

    /** ★敏感列只落密文：直查裸列不得出现任何原文。 */
    @Test
    void sensitiveColumnsNeverHoldPlaintext() throws Exception {
        insertVersion(1L, 1, ClubCorporatePayeeProfile.VERIFY_PENDING);

        String storedName = rawColumn("legal_name");
        String storedAccount = rawColumn("corporate_bank_account_no");
        assertFalse(storedName.contains(LEGAL_NAME), "法定名称明文落库了:" + storedName);
        assertFalse(storedAccount.contains(ACCOUNT_NO), "对公账号明文落库了:" + storedAccount);
        assertTrue(storedName.startsWith("v1:"), "必须是版本化密文，实际:" + storedName);

        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            assertEquals(LEGAL_NAME, mapper.selectCurrent(CLUB).getLegalName(), "读侧必须解回明文");
        }
    }

    /** 信用代码另起 blind index 列：同一个代码两次不同 nonce 的密文，index 必须相同。 */
    @Test
    void theCreditCodeBlindIndexIsStableAcrossCiphertexts() throws Exception {
        insertVersion(1L, 0, ClubCorporatePayeeProfile.VERIFY_PENDING);
        insertVersion(2L, 1, ClubCorporatePayeeProfile.VERIFY_PENDING);

        try (SqlSession session = factory.openSession(true);
             Statement s = session.getConnection().createStatement();
             ResultSet rs = s.executeQuery("select credit_code_index, unified_social_credit_code"
                     + " from club_corporate_payee_profile order by profile_version")) {
            rs.next();
            String firstIndex = rs.getString(1);
            String firstCipher = rs.getString(2);
            rs.next();
            assertEquals(firstIndex, rs.getString(1), "blind index 必须可比对");
            assertFalse(firstCipher.equals(rs.getString(2)), "两次密文不得相同（nonce 必须变）");
        }
    }

    /**
     * ★掩码视图用的 selectCurrent 不带 for update；写路径的 selectCurrentForUpdate 带。
     * 两条语句都必须存在且读到同一行 —— 否则要么读路径在 readOnly 事务里被 MySQL 拒（1792），
     * 要么写路径丢了行锁。
     */
    @Test
    void thereAreTwoCurrentReadsAndOnlyTheWriteOneLocks() throws Exception {
        insertVersion(1L, 1, ClubCorporatePayeeProfile.VERIFY_VERIFIED);

        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            assertNotNull(mapper.selectCurrent(CLUB));
            assertEquals(mapper.selectCurrentForUpdate(CLUB).getId(), mapper.selectCurrent(CLUB).getId());
        }
        String xml = resourceText("mapper/business/ClubCorporatePayeeProfileMapper.xml");
        String plainRead = between(xml, "<select id=\"selectCurrent\"", "</select>");
        String lockingRead = between(xml, "<select id=\"selectCurrentForUpdate\"", "</select>");
        assertFalse(plainRead.toLowerCase().contains("for update"),
                "掩码视图跑在 readOnly 事务里，锁定读会被 MySQL 拒 (ERROR 1792)");
        assertTrue(lockingRead.toLowerCase().contains("for update"), "写路径必须保留行锁");
    }

    /** 只有 current 版本会被 selectCurrent / selectVerifiedCurrent 读到。 */
    @Test
    void historicalVersionsAreInvisibleToTheCurrentReads() throws Exception {
        insertVersion(1L, 0, ClubCorporatePayeeProfile.VERIFY_VERIFIED);
        insertVersion(2L, 1, ClubCorporatePayeeProfile.VERIFY_PENDING);

        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            assertEquals(2L, mapper.selectCurrent(CLUB).getVersion());
            assertNull(mapper.selectVerifiedCurrent(CLUB),
                    "旧的已审核版本不得在换版之后继续给开售放行");
        }
    }

    /** ★审核 CAS 的三个条件都写在 where 里：current + PENDING + 执照 hash 未变。 */
    @Test
    void theVerifyCasRequiresCurrentPendingAndAnUnchangedLicenseHash() throws Exception {
        insertVersion(1L, 1, ClubCorporatePayeeProfile.VERIFY_PENDING);
        Long id = idOfVersion(1L);

        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            assertEquals(0, mapper.verifyVersionCas(id, CLUB, 72L, new Date(), "VERIFIED", "PASS",
                    "SOMEONE_SWAPPED_THE_LICENSE"), "执照 hash 变了就不许过审");
            assertEquals(0, mapper.verifyVersionCas(id, 999L, 72L, new Date(), "VERIFIED", "PASS",
                    "LICENSE_HASH"), "跨俱乐部的 profileId 不得被审核");

            assertEquals(1, mapper.verifyVersionCas(id, CLUB, 72L, new Date(), "VERIFIED", "PASS",
                    "LICENSE_HASH"));
            assertEquals(0, mapper.verifyVersionCas(id, CLUB, 73L, new Date(), "REJECTED", "REJECT",
                    "LICENSE_HASH"), "一次审核：并发的第二个人拿不到行");
            assertEquals(Long.valueOf(72L), mapper.selectCurrent(CLUB).getVerifiedBy());
        }
    }

    /** 换版：invalidateCurrent 把旧 current 清掉，历史行仍在（版本化，不是改写）。 */
    @Test
    void invalidatingTheCurrentVersionKeepsTheHistoryRow() throws Exception {
        insertVersion(1L, 1, ClubCorporatePayeeProfile.VERIFY_VERIFIED);

        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            assertEquals(1, mapper.invalidateCurrent(CLUB));
            assertNull(mapper.selectCurrent(CLUB));
            assertEquals(0, mapper.invalidateCurrent(CLUB), "没有 current 时拿不到行");
        }
        assertEquals(1, rowCount("select count(*) from club_corporate_payee_profile"));
    }

    /** 开售快照按 (topic, club) 唯一：重复冻结撞唯一键，历史事实不会被第二次开售改写。 */
    @Test
    void theSaleSnapshotIsUniquePerTopicAndClub() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            assertEquals(1, mapper.insertSaleSnapshot(snapshot()));

            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> mapper.insertSaleSnapshot(snapshot()));
            assertEquals("FACTS_HASH", mapper.selectSaleSnapshot(TOPIC, CLUB).getFactsHash());
            assertNull(mapper.selectSaleSnapshot(999L, CLUB));
        }
    }

    private static TopicSalePayeeSnapshot snapshot() {
        TopicSalePayeeSnapshot s = new TopicSalePayeeSnapshot();
        s.setTopicId(TOPIC);
        s.setClubId(CLUB);
        s.setProfileId(9L);
        s.setProfileVersion(1L);
        s.setFactsHash("FACTS_HASH");
        s.setLicenseHash("LICENSE_HASH");
        s.setFrozenAt(new Date());
        return s;
    }

    private void insertVersion(long version, int current, String verifyStatus) {
        try (SqlSession session = factory.openSession(true)) {
            ClubCorporatePayeeProfileMapper mapper = session.getMapper(ClubCorporatePayeeProfileMapper.class);
            ClubCorporatePayeeProfile p = new ClubCorporatePayeeProfile();
            p.setClubId(CLUB);
            p.setVersion(version);
            p.setCurrent(current);
            p.setLegalName(LEGAL_NAME);
            p.setUnifiedSocialCreditCode("91310000MA1FL0AB2C");
            p.setCreditCodeIndex("91310000MA1FL0AB2C");
            p.setLicenseObjectKey("private/club/8/license.pdf");
            p.setLicenseHash("LICENSE_HASH");
            p.setBankAccountName(LEGAL_NAME);
            p.setBankName("招商银行上海分行营业部");
            p.setCorporateBankAccountNo(ACCOUNT_NO);
            p.setInvoiceType("SPECIAL_VAT");
            p.setTaxNo("91310000MA1FL0AB2C");
            p.setFinanceContactName("周财务");
            p.setFinanceContactPhone("13800001234");
            p.setFactsHash("FACTS_HASH");
            p.setVerifyStatus(verifyStatus);
            p.setCreatedBy(71L);
            mapper.insertVersion(p);
        }
    }

    private Long idOfVersion(long version) throws Exception {
        try (SqlSession session = factory.openSession(true);
             Statement s = session.getConnection().createStatement();
             ResultSet rs = s.executeQuery("select id from club_corporate_payee_profile"
                     + " where profile_version = " + version)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String rawColumn(String column) throws Exception {
        try (SqlSession session = factory.openSession(true);
             Statement s = session.getConnection().createStatement();
             ResultSet rs = s.executeQuery("select " + column + " from club_corporate_payee_profile")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private int rowCount(String sql) throws Exception {
        try (SqlSession session = factory.openSession(true);
             Statement s = session.getConnection().createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String resourceText(String path) throws Exception {
        try (InputStream in = Resources.getResourceAsStream(path)) {
            java.util.Scanner scanner = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private static String between(String text, String from, String to) {
        int at = text.indexOf(from);
        assertTrue(at >= 0, "找不到语句:" + from);
        return text.substring(at, text.indexOf(to, at));
    }
}

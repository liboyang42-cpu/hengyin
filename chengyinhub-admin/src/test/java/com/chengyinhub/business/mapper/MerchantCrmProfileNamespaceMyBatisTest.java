package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantCustomerProfile;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerPageVO;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerRowVO;
import com.chengyinhub.business.domain.vo.MerchantCrmSegmentCountsVO;
import com.chengyinhub.business.domain.vo.MerchantCrmSummaryVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantCrmProfileNamespaceMyBatisTest {
    private static final String APP_XML = "mapper/business/MerchantCrmAppMapper.xml";
    private static final String PROFILE_XML = "mapper/business/MerchantCustomerProfileMapper.xml";

    @Test
    void legacyOwnerIdEqualToAnotherMerchantRowIdNeverUpgradesOrLeaks() throws Exception {
        try (SqlSession session = factory().openSession(false)) {
            createSchema(session);
            seedCollision(session);

            MerchantCrmAppMapper appMapper = session.getMapper(MerchantCrmAppMapper.class);
            MerchantCustomerProfileMapper profileMapper = session.getMapper(MerchantCustomerProfileMapper.class);
            assertEquals(1, appMapper.upsertOwnedCustomerProfile(77L, 88L, 41L));

            MerchantCustomerProfile query = new MerchantCustomerProfile();
            query.setMerchantId(77L);
            List<MerchantCustomerProfile> visible = profileMapper.selectCustomerList(query);
            assertEquals(1, visible.size(), "B 店不能读到 owner A 留在 merchant_id=77/version=0 的历史画像");
            assertEquals("B店客户", visible.get(0).getMemberNickname());

            MerchantCrmSummaryVO summary = profileMapper.selectSummaryByMerchantId(77L);
            assertEquals(Long.valueOf(1L), summary.getCustomerCount());
            assertEquals(new BigDecimal("12.00"), summary.getOrderAmount());

            try (Statement statement = session.getConnection().createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select merchant_scope_version, member_nickname from merchant_customer_profile "
                                 + "where merchant_id=77 order by merchant_scope_version")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt("merchant_scope_version"));
                assertEquals("A店历史客户", rows.getString("member_nickname"),
                        "新门店 upsert 不得把同数字 owner namespace 的旧行升级为 version=2");
                assertTrue(rows.next());
                assertEquals(2, rows.getInt("merchant_scope_version"));
                assertEquals("B店客户", rows.getString("member_nickname"));
                assertEquals(false, rows.next());
            }

            String migration = normalized(new String(Files.readAllBytes(Paths.get(
                    "sql/migration_merchant_operator_crm_followup_20260823.sql")), StandardCharsets.UTF_8));
            assertTrue(migration.contains("unique key `uk_merchant_scope_member` "
                    + "(`merchant_id`, `merchant_scope_version`, `member_id`)"));
            assertTrue(migration.contains("unique key `uk_merchant_scope_tag_code` "
                    + "(`merchant_id`, `merchant_scope_version`, `tag_code`)"));
            assertTrue(migration.contains("prepare merchant_customer_profile_scope_column_stmt"),
                    "scope 列必须按 information_schema 条件收敛，半迁移重跑不得 duplicate column");
            assertTrue(migration.contains("select coalesce(max(concat(lower(data_type), ':', is_nullable, ':', "
                            + "coalesce(column_default, '<null>'))), '') from information_schema.columns"),
                    "列不存在时预检必须返回空串，不能让标量子查询的 NULL 阻断首次迁移");
            assertTrue(migration.contains("prepare merchant_customer_profile_scope_unique_stmt"),
                    "namespace 唯一键必须原子替换且可重跑");
            assertTrue(migration.contains("exact_profile.merchant_scope_version = 2")
                            && migration.contains("exact_tag.merchant_scope_version = 2"),
                    "v0 回填撞到既有 v2 时必须在 UPDATE 前 fail closed");
            session.rollback();
        }
    }

    @Test
    void figmaSegmentsAndLatestNoteStayInsideExactMerchantRow() throws Exception {
        try (SqlSession session = factory().openSession(false)) {
            createSchema(session);
            seedCollision(session);
            try (Statement statement = session.getConnection().createStatement()) {
                statement.execute("insert into merchant_customer_note(id,merchant_id,merchant_scope_version,"
                        + "member_id,content,hidden,create_time) values "
                        + "(1,77,2,41,'本店备注',0,current_timestamp),"
                        + "(2,66,2,41,'别店备注',0,dateadd('minute',1,current_timestamp))");
            }

            MerchantCrmAppMapper mapper = session.getMapper(MerchantCrmAppMapper.class);
            MerchantCrmCustomerQuery noted = query(77L, "noted");
            List<MerchantCrmCustomerRowVO> rows = mapper.selectOwnedCustomerPage(88L, noted, 0, 20);
            assertEquals(1, rows.size());
            assertEquals("本店备注", rows.get(0).getLatestNote(), "最新备注必须按 exact merchant-row 隔离");

            MerchantCrmCustomerQuery otherStore = query(99L, "noted");
            assertEquals(0, mapper.selectOwnedCustomerPage(88L, otherStore, 0, 20).size(),
                    "别店备注不能把本店客户命中 noted 分群");

            assertEquals(1, mapper.selectOwnedCustomerPage(88L, query(77L, "new"), 0, 20).size(),
                    "近期首次核销客户应命中 Figma 新客筛选");
            MerchantCrmSegmentCountsVO counts = mapper.selectOwnedSegmentCounts(88L, query(77L, null));
            assertEquals(Long.valueOf(1), counts.getNew());
            assertEquals(Long.valueOf(1), counts.getNoted());
            assertEquals(Long.valueOf(1), counts.getMonthlyNew());
            session.rollback();
        }
    }

    @Test
    void customerPhoneProjectionExcludesClubOwnedAndAcceptedClubInviteTopics() throws Exception {
        try (SqlSession session = factory().openSession(false)) {
            createSchema(session);
            try (Statement statement = session.getConnection().createStatement()) {
                statement.execute("insert into ums_member(id,nickname,phone,del_flag) values "
                        + "(51,'俱乐部自办客户','13900000001',0),"
                        + "(52,'俱乐部受邀客户','13900000002',0),"
                        + "(53,'商家负责客户','13900000003',0)");
                statement.execute("insert into cms_topic(id,member_id,club_id,product_type,del_flag) values "
                        + "(71,88,77,null,0),(72,88,0,null,0),(73,88,0,null,0)");
                statement.execute("insert into coop_invite(id,topic_id,to_type,status,del_flag) "
                        + "values (1,72,'club',1,0)");
                statement.execute("insert into cms_registration values "
                        + "(401,51,'13900000001',2,1,10.00,10.00,current_timestamp,current_timestamp,1,71),"
                        + "(402,52,'13900000002',2,1,10.00,10.00,current_timestamp,current_timestamp,1,72),"
                        + "(403,53,'13900000003',2,1,10.00,10.00,current_timestamp,current_timestamp,1,73)");
            }

            List<MerchantCrmCustomerRowVO> rows = session.getMapper(MerchantCrmAppMapper.class)
                    .selectOwnedCustomerPage(88L, query(77L, null), 0, 20);

            assertEquals(3, rows.size(), "俱乐部带团客户仍属于名册，只收回联系方式");
            assertNull(rowFor(rows, 51L).getPhone(), "club_id 挂靠不得向商家投影电话");
            assertNull(rowFor(rows, 52L).getPhone(), "已接受俱乐部邀约不得向商家投影电话");
            assertEquals("13900000003", rowFor(rows, 53L).getPhone());
            assertFalse(Boolean.TRUE.equals(rowFor(rows, 51L).getMerchantResponsibleForContact()));
            assertFalse(Boolean.TRUE.equals(rowFor(rows, 52L).getMerchantResponsibleForContact()));
            assertTrue(Boolean.TRUE.equals(rowFor(rows, 53L).getMerchantResponsibleForContact()));
            session.rollback();
        }
    }

    @Test
    void encryptedCustomerPhoneIsDecodedBeforeListAndExportProjection() throws Exception {
        com.chengyinhub.business.security.PiiCryptoService previous =
                com.chengyinhub.business.security.PiiCryptoService.getInstance();
        com.chengyinhub.business.security.PiiCryptoService crypto =
                new com.chengyinhub.business.security.PiiCryptoService(
                        java.util.Base64.getEncoder().encodeToString(new byte[32]), "test-only");
        crypto.register();
        try (SqlSession session = factory().openSession(false)) {
            createSchema(session);
            seedCollision(session);
            try (Statement statement = session.getConnection().createStatement()) {
                statement.execute("alter table cms_registration alter column phone varchar(512)");
                statement.execute("alter table ums_member alter column phone varchar(512)");
            }
            try (java.sql.PreparedStatement statement = session.getConnection().prepareStatement(
                    "update cms_registration set phone=? where id=301")) {
                statement.setString(1, crypto.encrypt("13800000000"));
                statement.executeUpdate();
            }
            MerchantCrmAppMapper mapper = session.getMapper(MerchantCrmAppMapper.class);
            List<MerchantCrmCustomerRowVO> rows =
                    mapper.selectOwnedCustomerPage(88L, query(77L, null), 0, 20);
            assertEquals("13800000000", rows.get(0).getPhone(),
                    "报名密文必须经过真实Mapper解密，不能直接流入列表或导出");

            com.chengyinhub.business.service.impl.MerchantCrmExportServiceImpl export =
                    new com.chengyinhub.business.service.impl.MerchantCrmExportServiceImpl();
            com.chengyinhub.common.config.ComplianceProperties properties =
                    new com.chengyinhub.common.config.ComplianceProperties();
            properties.setDocVersions(java.util.Collections.singletonMap("activity_host_data_sharing", "v2.1"));
            com.chengyinhub.business.service.IConsentRecordService consent =
                    org.mockito.Mockito.mock(com.chengyinhub.business.service.IConsentRecordService.class);
            org.mockito.Mockito.when(consent.hasCurrentAgreement(41L,
                    "activity_host_data_sharing", "activity_signup", "v2.1")).thenReturn(true);
            org.springframework.test.util.ReflectionTestUtils.setField(export, "complianceProperties", properties);
            org.springframework.test.util.ReflectionTestUtils.setField(export, "consentRecordService", consent);
            List<com.chengyinhub.business.domain.vo.MerchantCrmExportRowVO> exported =
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(export, "exportRows", rows, true);
            assertEquals("138****0000", exported.get(0).getMaskedPhone());

            // 无报名电话时退到会员电话，仍须解密；历史明文与NULL继续兼容。
            try (Statement statement = session.getConnection().createStatement()) {
                statement.execute("update cms_registration set phone=null where id=301");
            }
            try (java.sql.PreparedStatement statement = session.getConnection().prepareStatement(
                    "update ums_member set phone=? where id=41")) {
                statement.setString(1, crypto.encrypt("13900000000"));
                statement.executeUpdate();
            }
            session.clearCache();
            assertEquals("13900000000",
                    mapper.selectOwnedCustomerPage(88L, query(77L, null), 0, 20).get(0).getPhone());
            session.rollback();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    com.chengyinhub.business.security.PiiCryptoService.class, "instance", previous);
        }
    }

    /**
     * F15 剩余缺口:列表服务层必须**解密后脱敏**再下发。Mapper 解密(上一条用同一份
     * mapper XML 验证)不代表服务层可以原样透出;导出与列表必须是同一套脱敏口径。
     */
    @Test
    void listCustomersServesMaskedPhoneInsteadOfPlaintextOrCiphertext() throws Exception {
        com.chengyinhub.business.security.PiiCryptoService previous =
                com.chengyinhub.business.security.PiiCryptoService.getInstance();
        com.chengyinhub.business.security.PiiCryptoService crypto =
                new com.chengyinhub.business.security.PiiCryptoService(
                        java.util.Base64.getEncoder().encodeToString(new byte[32]), "test-only");
        crypto.register();
        try (SqlSession session = factory().openSession(false)) {
            createSchema(session);
            seedCollision(session);
            try (Statement statement = session.getConnection().createStatement()) {
                statement.execute("alter table cms_registration alter column phone varchar(512)");
            }
            try (java.sql.PreparedStatement statement = session.getConnection().prepareStatement(
                    "update cms_registration set phone=? where id=301")) {
                statement.setString(1, crypto.encrypt("13800000000"));
                statement.executeUpdate();
            }

            com.chengyinhub.business.service.impl.MerchantCrmAppServiceImpl service =
                    crmAppService(session.getMapper(MerchantCrmAppMapper.class), true);

            MerchantCrmCustomerPageVO page = service.listCustomers(41L, query(77L, null));

            assertEquals("138****0000", page.getRows().get(0).getPhone(),
                    "有权且已同意时必须下发脱敏号,不得是明文或 v1 密文");
            session.rollback();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    com.chengyinhub.business.security.PiiCryptoService.class, "instance", previous);
        }
    }

    /** 负控:未同意共享时,连脱敏号都不下发(现有同意闸不能被脱敏改动放宽)。 */
    @Test
    void listCustomersHidesPhoneWithoutCurrentDataSharingConsent() throws Exception {
        com.chengyinhub.business.security.PiiCryptoService previous =
                com.chengyinhub.business.security.PiiCryptoService.getInstance();
        com.chengyinhub.business.security.PiiCryptoService crypto =
                new com.chengyinhub.business.security.PiiCryptoService(
                        java.util.Base64.getEncoder().encodeToString(new byte[32]), "test-only");
        crypto.register();
        try (SqlSession session = factory().openSession(false)) {
            createSchema(session);
            seedCollision(session);
            try (Statement statement = session.getConnection().createStatement()) {
                statement.execute("alter table cms_registration alter column phone varchar(512)");
            }
            try (java.sql.PreparedStatement statement = session.getConnection().prepareStatement(
                    "update cms_registration set phone=? where id=301")) {
                statement.setString(1, crypto.encrypt("13800000000"));
                statement.executeUpdate();
            }

            com.chengyinhub.business.service.impl.MerchantCrmAppServiceImpl service =
                    crmAppService(session.getMapper(MerchantCrmAppMapper.class), false);

            MerchantCrmCustomerPageVO page = service.listCustomers(41L, query(77L, null));

            assertNull(page.getRows().get(0).getPhone());
            assertEquals("客户未授权联系方式共享", page.getRows().get(0).getContactHint());
            session.rollback();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    com.chengyinhub.business.security.PiiCryptoService.class, "instance", previous);
        }
    }

    private com.chengyinhub.business.service.impl.MerchantCrmAppServiceImpl crmAppService(
            MerchantCrmAppMapper mapper, boolean consentGranted) {
        com.chengyinhub.business.service.impl.MerchantCrmAppServiceImpl service =
                new com.chengyinhub.business.service.impl.MerchantCrmAppServiceImpl();
        com.chengyinhub.business.access.merchant.IMerchantAccessService access =
                org.mockito.Mockito.mock(com.chengyinhub.business.access.merchant.IMerchantAccessService.class);
        org.mockito.Mockito.when(access.require(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new com.chengyinhub.business.access.merchant.MerchantAccessContext(
                        41L, 77L, 88L,
                        com.chengyinhub.business.access.merchant.MerchantRoleCode.MERCHANT_MANAGER));
        com.chengyinhub.common.config.ComplianceProperties properties =
                new com.chengyinhub.common.config.ComplianceProperties();
        properties.setDocVersions(java.util.Collections.singletonMap("activity_host_data_sharing", "v2.1"));
        com.chengyinhub.business.service.IConsentRecordService consent =
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IConsentRecordService.class);
        org.mockito.Mockito.when(consent.hasCurrentAgreement(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(consentGranted);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "merchantAccessService", access);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "merchantCrmAppMapper", mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "merchantCustomerTagMapper",
                org.mockito.Mockito.mock(MerchantCustomerTagMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "consentRecordService", consent);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "complianceProperties", properties);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "playerRoleService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IPlayerRoleService.class));
        return service;
    }

    private MerchantCrmCustomerQuery query(Long merchantId, String segment) {
        MerchantCrmCustomerQuery query = new MerchantCrmCustomerQuery();
        query.setMerchantId(merchantId);
        query.setSegment(segment);
        return query;
    }

    private MerchantCrmCustomerRowVO rowFor(List<MerchantCrmCustomerRowVO> rows, Long memberId) {
        for (MerchantCrmCustomerRowVO row : rows) {
            if (memberId.equals(row.getMemberId())) return row;
        }
        throw new AssertionError("missing member " + memberId);
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration configuration = new Configuration();
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:merchant_crm_profile_namespace;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        for (String resource : new String[] { APP_XML, PROFILE_XML }) {
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void createSchema(SqlSession session) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("drop all objects");
            statement.execute("create table merchant_customer_profile("
                    + "id bigint auto_increment primary key, merchant_id bigint not null, "
                    + "merchant_scope_version int not null, member_id bigint not null, member_nickname varchar(100), "
                    + "member_phone varchar(32), registration_count bigint not null, order_count bigint not null, "
                    + "order_amount decimal(18,2) not null, coupon_receive_count bigint not null, "
                    + "coupon_verify_count bigint not null, last_interaction_time timestamp, customer_stage varchar(32), "
                    + "city_code varchar(32), tags_json varchar(1000), del_flag int not null, "
                    + "create_time timestamp, update_time timestamp, "
                    + "unique(merchant_id, merchant_scope_version, member_id))");
            statement.execute("create table ums_member(id bigint primary key, nickname varchar(100), "
                    + "avatar varchar(500), phone varchar(32), del_flag int not null)");
            statement.execute("create table cms_registration(id bigint primary key, member_id bigint not null, "
                    + "phone varchar(32), payment_status int not null, verification_status int, "
                    + "payable_amount decimal(18,2), total_amount decimal(18,2), verification_time timestamp, "
                    + "create_time timestamp not null, owner_type int not null, owner_id bigint not null)");
            statement.execute("create table cms_topic(id bigint primary key, member_id bigint not null, "
                    + "club_id bigint, product_type int, del_flag int not null)");
            statement.execute("create table cms_registration_merchant(id bigint primary key, topic_id bigint not null, "
                    + "member_id bigint not null, audit_status int not null, del_flag int not null)");
            statement.execute("create table coop_invite(id bigint primary key, topic_id bigint not null, "
                    + "to_type varchar(32), status int, del_flag int not null)");
            statement.execute("create table cms_activity(id bigint primary key, topic_id bigint, "
                    + "member_id bigint not null, del_flag int not null)");
            statement.execute("create table merchant_customer_note(id bigint primary key, merchant_id bigint not null, "
                    + "merchant_scope_version int not null, member_id bigint not null, content varchar(500), "
                    + "hidden int not null, create_time timestamp)");
            // 2026-09-05 客户行补出队伍 / 角色(定向广播按这两档分组)。建表在这里,
            // 不塞种子数据 —— 现有断言要证的是「同一商家行内的字段不串台」,
            // 这两列此时恒 null,正好也顺带证明了「没进过队 / 不是活动局」不会凭空造出分组。
            statement.execute("create table play_team(id bigint primary key, owner_type int, owner_id bigint, "
                    + "title varchar(100))");
            statement.execute("create table play_team_member(id bigint primary key, team_id bigint not null, "
                    + "member_id bigint not null, status int not null)");
            statement.execute("create table player_activity_role(id bigint primary key, activity_id bigint not null, "
                    + "member_id bigint not null, role_code varchar(32))");
        }
    }

    private void seedCollision(SqlSession session) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("insert into merchant_customer_profile(merchant_id,merchant_scope_version,member_id,"
                    + "member_nickname,registration_count,order_count,order_amount,coupon_receive_count,"
                    + "coupon_verify_count,customer_stage,del_flag,create_time) values "
                    + "(77,0,41,'A店历史客户',9,9,999.00,0,0,'vip',0,current_timestamp)");
            statement.execute("insert into ums_member(id,nickname,phone,del_flag) values "
                    + "(41,'B店客户','13800000000',0)");
            statement.execute("insert into cms_activity values (31,null,88,0)");
            statement.execute("insert into cms_registration values "
                    + "(301,41,'13800000000',2,1,12.00,12.00,current_timestamp,current_timestamp,2,31)");
        }
    }

    private String normalized(String value) {
        return value.toLowerCase().replaceAll("\\s+", " ").trim();
    }
}

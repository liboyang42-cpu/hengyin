package com.chengyinhub.business.it;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.vo.MerchantCrmContactVO;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerPageVO;
import com.chengyinhub.business.domain.vo.MerchantCrmCustomerRowVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.mapper.MerchantCrmAppMapper;
import com.chengyinhub.business.mapper.MerchantCustomerTagMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.IMerchantCrmAppService;
import com.chengyinhub.business.service.impl.ConsentRecordServiceImpl;
import com.chengyinhub.business.service.impl.MerchantCrmAppServiceImpl;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ROUND10 f4 第 3a 项(2026-11-04 业主拍板):探店日是自由探索的旧名,不是另一个产品;
 * 自由探索(product_type = 2)的买家进商家「客户」名册。
 *
 * <p>真库 + 真 Mapper XML + 真同意服务钉住四件事:
 * ① 主题方看到已付款且已核销的买家,号码在通用同意下照常脱敏下发;
 * ② 审核通过的参与商家看到同一买家,但号码不经通用名册下发(玩家对单店另有授权),揭示接口 403;
 * ③ 待审核的参与商家、不在主题上的商家一行都看不到,揭示接口 404;
 * ④ 普通主题的口径不变(对照行)。</p>
 *
 * <p>第十一轮 h2 第 1 项(2026-11-04 业主):玩家撤回对某参与商家的单店同意后,该店名册不再列他,
 * 重新同意后回来;主题方视图不变。</p>
 */
class MerchantCrmFreeExploreRosterMySqlIT {

    private static final long HOST = 88L;          // 自由探索主题方
    private static final long SHOP = 66L;          // 审核通过的参与商家(中标 audit_status = 1)
    private static final long CHAPTER_SHOP = 68L;  // 章节承接申请已通过的参与商家
    private static final long PENDING_SHOP = 67L;  // 待审核参与商家
    private static final long OUTSIDER = 99L;      // 不在主题上的商家
    private static final long OPERATOR = 41L;
    private static final long BUYER = 61L;
    private static final long PLAIN_BUYER = 62L;
    private static final String BUYER_PHONE = "13800138061";

    private IMerchantAccessService access;
    private IMerchantCrmAppService service;
    private MerchantCrmAppMapper mapper;

    @Test
    void freeExploreBuyersReachHostAndApprovedShopsOnlyWithoutRelaxingPhoneConsent() throws Exception {
        String database = MybatisItDatabase.databaseNameAfterCreate(
                "crm_free_explore_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        String url = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                + "/" + database + "?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        PiiCryptoService previous = PiiCryptoService.getInstance();
        PiiCryptoService crypto = new PiiCryptoService(
                Base64.getEncoder().encodeToString(new byte[32]), "crm-free-explore-it");
        try {
            crypto.register();
            createSchemaAndSeed(url, crypto);
            wireService(url);

            // ① 主题方:买家在名册,已核销计到店,号码脱敏下发;揭示接口给明文。
            stubOwner(HOST, 801L);
            MerchantCrmCustomerRowVO hostRow = only(service.listCustomers(OPERATOR, query()), BUYER);
            assertEquals(1, hostRow.getArrivedCount().intValue(), "核销过的票计到店");
            assertEquals("138****8061", hostRow.getPhone());
            MerchantCrmContactVO hostContact = service.revealContact(OPERATOR, BUYER, "call");
            assertEquals(BUYER_PHONE, hostContact.getPhone());
            // 第十二轮 k6(p2 N-2):主题方(活动壳主办)收了这张 ¥39 的票款,累计实付 ¥39。
            assertEquals(0, new java.math.BigDecimal("39.00").compareTo(hostRow.getPaidAmount()), "主题方累计实付");

            // ② 审核通过的参与商家:同一买家在名册,号码不下发,揭示 403。
            stubOwner(SHOP, 802L);
            MerchantCrmCustomerRowVO shopRow = only(service.listCustomers(OPERATOR, query()), BUYER);
            assertNull(shopRow.getPhone(), "参与商家不经通用名册拿号码");
            assertNull(shopRow.getContactHint(), "第十二轮 k9(p2 N-17):列表不再逐行复述单店授权说明");
            // 第十二轮 k6(p2 N-2):参与商家没收到这张票款,累计实付不再是买家整张票价(列表与详情同口径)。
            assertEquals(0, java.math.BigDecimal.ZERO.compareTo(shopRow.getPaidAmount()), "参与商家累计实付应为 0");
            assertEquals(0, java.math.BigDecimal.ZERO.compareTo(
                    mapper.selectOwnedCustomerSummary(SHOP, BUYER).getPaidAmount()), "客户详情累计实付同口径");
            ServiceException shopReveal = assertThrows(ServiceException.class,
                    () -> service.revealContact(OPERATOR, BUYER, "call"));
            assertEquals(403, shopReveal.getCode());
            assertEquals(1, scalar(url, "select count(1) from business_audit_event"
                    + " where action='CRM_CONTACT_REVEAL_DENIED' and reason like '%deny=free_explore_shop'"));

            // ②b 章节承接申请已通过的参与商家:同样可见、同样不下发号码。
            stubOwner(CHAPTER_SHOP, 804L);
            assertNull(only(service.listCustomers(OPERATOR, query()), BUYER).getPhone());

            // ②c 第十一轮 h2 第 1 项:玩家撤回对参与商家 66 的单店同意 → 66 的客户名册不再列这位买家
            //     (与现场名单跳过 REVOKED 整行一致),揭示 404;其它参与商家与主题方不受影响。
            consent(url, BUYER, SHOP, "AGREE", "2026-11-04 09:00:00", "it-fe-shop-agree-1");
            consent(url, BUYER, SHOP, "REVOKE", "2026-11-04 10:00:00", "it-fe-shop-revoke-1");
            consent(url, BUYER, HOST, "REVOKE", "2026-11-04 10:00:00", "it-fe-host-revoke-1");
            stubOwner(SHOP, 802L);
            MerchantCrmCustomerPageVO revokedPage = service.listCustomers(OPERATOR, query());
            assertTrue(revokedPage.getRows().stream().noneMatch(row -> Long.valueOf(BUYER).equals(row.getMemberId())),
                    "撤回单店同意后参与商家不该再看到这位买家");
            assertEquals(1L, revokedPage.getTotal().longValue(), "只剩普通主题买家");
            assertEquals(1L, mapper.countOwnedRegistrationCustomers(SHOP), "名册计数同口径");
            ServiceException revokedReveal = assertThrows(ServiceException.class,
                    () -> service.revealContact(OPERATOR, BUYER, "call"));
            assertEquals(404, revokedReveal.getCode());
            stubOwner(CHAPTER_SHOP, 804L);
            only(service.listCustomers(OPERATOR, query()), BUYER);
            stubOwner(HOST, 801L);
            assertEquals("138****8061", only(service.listCustomers(OPERATOR, query()), BUYER).getPhone(),
                    "主题方视图不变:单店同意撤回不影响主题方");

            // ②d 重新同意(同一时刻、更大的 id 也算更新)→ 这位买家回到 66 的名册,号码仍不经通用名册下发。
            consent(url, BUYER, SHOP, "AGREE", "2026-11-04 10:00:00", "it-fe-shop-agree-2");
            stubOwner(SHOP, 802L);
            assertNull(only(service.listCustomers(OPERATOR, query()), BUYER).getPhone());
            assertEquals(2L, mapper.countOwnedRegistrationCustomers(SHOP));
            // 再撤回一次又消失:看的是最新一条,不是「有没有撤回过」。
            consent(url, BUYER, SHOP, "REVOKE", "2026-11-04 11:00:00", "it-fe-shop-revoke-2");
            assertTrue(service.listCustomers(OPERATOR, query()).getRows().stream()
                    .noneMatch(row -> Long.valueOf(BUYER).equals(row.getMemberId())));
            consent(url, BUYER, SHOP, "AGREE", "2026-11-04 12:00:00", "it-fe-shop-agree-3");

            // ③ 待审核参与商家 / 不在主题上的商家:一行都没有,揭示 404。
            for (long otherOwner : new long[]{PENDING_SHOP, OUTSIDER}) {
                stubOwner(otherOwner, 803L);
                MerchantCrmCustomerPageVO page = service.listCustomers(OPERATOR, query());
                assertEquals(0L, page.getTotal().longValue(), "owner " + otherOwner + " 不该看到任何客户");
                assertTrue(page.getRows().isEmpty());
                ServiceException outside = assertThrows(ServiceException.class,
                        () -> service.revealContact(OPERATOR, BUYER, "call"));
                assertEquals(404, outside.getCode());
            }

            // ④ 对照:普通主题(product_type 为空)仍按原口径 —— 参与商家同样是联系负责方。
            stubOwner(SHOP, 802L);
            MerchantCrmCustomerRowVO plainRow = only(service.listCustomers(OPERATOR, query()), PLAIN_BUYER);
            assertEquals("客户未授权联系方式共享", plainRow.getContactHint());
            assertEquals(0, java.math.BigDecimal.ZERO.compareTo(plainRow.getPaidAmount()),
                    "普通主题上的合作商家同样没收到票款");
            stubOwner(HOST, 801L);
            assertEquals(0, new java.math.BigDecimal("20.00").compareTo(
                    only(service.listCustomers(OPERATOR, query()), PLAIN_BUYER).getPaidAmount()), "主题方自己的主题票款照算");
        } finally {
            ReflectionTestUtils.setField(PiiCryptoService.class, "instance", previous);
            dropAndVerifyGone(database);
        }
    }

    /** 改过的名册 SQL 要过应用同配置的 Druid wall(IT 走裸数据源,wall 拦不到)。 */
    @Test
    void changedRosterStatementsPassTheApplicationWall() throws Exception {
        com.alibaba.druid.wall.WallConfig config = new com.alibaba.druid.wall.WallConfig(
                com.alibaba.druid.wall.spi.MySqlWallProvider.DEFAULT_CONFIG_DIR);
        config.setMultiStatementAllow(false);
        com.alibaba.druid.wall.spi.MySqlWallProvider wall = new com.alibaba.druid.wall.spi.MySqlWallProvider(config);
        Configuration configuration = new Configuration();
        String root = Paths.get("").toAbsolutePath().getParent()
                + "/chengyinhub-system/src/main/resources/mapper/business/";
        for (String file : new String[]{"MerchantCrmAppMapper.xml", "MerchantCrmMarketingConsentMapper.xml"}) {
            try (InputStream input = new FileInputStream(root + file)) {
                new XMLMapperBuilder(input, configuration, root + file, configuration.getSqlFragments()).parse();
            }
        }
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        MerchantCrmCustomerQuery query = query();
        query.setMerchantId(801L);
        params.put("ownerMemberId", HOST);
        params.put("query", query);
        params.put("customerMemberId", BUYER);
        params.put("merchantId", 801L);
        params.put("memberId", BUYER);
        params.put("limit", 20);
        params.put("offset", 0);
        int checked = 0;
        for (org.apache.ibatis.mapping.MappedStatement statement : configuration.getMappedStatements()) {
            String id = statement.getId();
            if (!id.contains("selectOwned") && !id.contains("upsertOwnedCustomerProfile")
                    && !id.contains("markStaleCustomerProfiles") && !id.contains("countOwnedRegistrationCustomers")
                    && !id.contains("selectEligibleMerchants")) {
                continue;
            }
            String bound = statement.getBoundSql(params).getSql();
            assertTrue(wall.check(bound).getViolations().isEmpty(), id + " 被 wall 拒:" + wall.check(bound).getViolations());
            checked++;
        }
        assertTrue(checked >= 6, "至少扫过名册的主要语句:" + checked);
    }

    private static MerchantCrmCustomerQuery query() {
        MerchantCrmCustomerQuery query = new MerchantCrmCustomerQuery();
        query.setPageNum(1);
        query.setPageSize(20);
        return query;
    }

    private static MerchantCrmCustomerRowVO only(MerchantCrmCustomerPageVO page, long memberId) {
        List<MerchantCrmCustomerRowVO> rows = page.getRows();
        MerchantCrmCustomerRowVO found = null;
        for (MerchantCrmCustomerRowVO row : rows) {
            if (Long.valueOf(memberId).equals(row.getMemberId())) found = row;
        }
        assertTrue(found != null, "名册里没有客户 " + memberId + ":" + rows.size() + " 行");
        return found;
    }

    private void stubOwner(long ownerMemberId, long merchantId) {
        MerchantAccessContext context = new MerchantAccessContext(
                OPERATOR, merchantId, ownerMemberId, MerchantRoleCode.MERCHANT_MANAGER);
        when(access.require(anyLong(), anyString())).thenReturn(context);
    }

    private void wireService(String url) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(MybatisItDatabase.user());
        dataSource.setPassword(MybatisItDatabase.password());
        Configuration configuration = new Configuration(new Environment("crm-free-explore-it",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        String root = Paths.get("").toAbsolutePath().getParent()
                + "/chengyinhub-system/src/main/resources/mapper/business/";
        for (String file : new String[]{
                "MerchantCrmAppMapper.xml", "BusinessAuditEventMapper.xml", "ConsentRecordMapper.xml"}) {
            try (InputStream input = new FileInputStream(root + file)) {
                new XMLMapperBuilder(input, configuration, root + file, configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));

        ConsentRecordServiceImpl consent = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(consent, "consentRecordMapper", template.getMapper(ConsentRecordMapper.class));
        ComplianceProperties properties = new ComplianceProperties();
        properties.setDocVersions(Collections.singletonMap("activity_host_data_sharing", "v2.1"));

        access = mock(IMerchantAccessService.class);
        MerchantCustomerTagMapper tags = mock(MerchantCustomerTagMapper.class);
        MerchantCrmAppServiceImpl target = new MerchantCrmAppServiceImpl();
        ReflectionTestUtils.setField(target, "merchantAccessService", access);
        mapper = template.getMapper(MerchantCrmAppMapper.class);
        ReflectionTestUtils.setField(target, "merchantCrmAppMapper", mapper);
        ReflectionTestUtils.setField(target, "merchantCustomerTagMapper", tags);
        ReflectionTestUtils.setField(target, "businessAuditEventMapper",
                template.getMapper(BusinessAuditEventMapper.class));
        ReflectionTestUtils.setField(target, "consentRecordService", consent);
        ReflectionTestUtils.setField(target, "complianceProperties", properties);
        ReflectionTestUtils.setField(target, "playerRoleService",
                mock(com.chengyinhub.business.service.IPlayerRoleService.class));

        TransactionInterceptor interceptor = new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(IMerchantCrmAppService.class);
        proxyFactory.addAdvice(interceptor);
        service = (IMerchantCrmAppService) proxyFactory.getProxy();
    }

    private void createSchemaAndSeed(String url, PiiCryptoService crypto) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, MybatisItDatabase.user(),
                MybatisItDatabase.password())) {
            ItSchema.applyMigrations(connection, "sql/migration_business_audit_event_20260823.sql");
            ItSchema.applyMigrations(connection, "sql/migration_compliance_consent_deregister_20260711.sql");
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE business_audit_event ADD COLUMN actor_type VARCHAR(16)"
                        + " NOT NULL DEFAULT 'UNKNOWN' AFTER owner_member_id,"
                        + " MODIFY COLUMN request_id VARCHAR(64) NOT NULL");
                statement.execute("ALTER TABLE consent_record ADD COLUMN scope_type VARCHAR(32) NULL AFTER scene,"
                        + " ADD COLUMN scope_id BIGINT NULL AFTER scope_type");
                statement.execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, nickname VARCHAR(64),"
                        + " avatar VARCHAR(255), phone VARCHAR(512), del_flag INT) ENGINE=InnoDB");
                statement.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, member_id BIGINT,"
                        + " club_id BIGINT, product_type INT, del_flag INT) ENGINE=InnoDB");
                statement.execute("CREATE TABLE cms_registration_merchant (id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + " topic_id BIGINT, member_id BIGINT, audit_status INT, del_flag INT) ENGINE=InnoDB");
                statement.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, member_id BIGINT,"
                        + " topic_id BIGINT, del_flag INT) ENGINE=InnoDB");
                statement.execute("CREATE TABLE coop_invite (id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + " topic_id BIGINT, to_type VARCHAR(16), status INT, del_flag INT) ENGINE=InnoDB");
                statement.execute("CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL,"
                        + " phone VARCHAR(512), payment_status INT, verification_status INT,"
                        + " payable_amount DECIMAL(12,2), total_amount DECIMAL(12,2), verification_time DATETIME,"
                        + " create_time DATETIME, owner_type INT, owner_id BIGINT, activity_title VARCHAR(255),"
                        + " real_name VARCHAR(512)"
                        + " ) ENGINE=InnoDB");
                statement.execute("CREATE TABLE merchant_chapter_application (id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + " topic_id BIGINT, chapter_id BIGINT, merchant_id BIGINT, status TINYINT, del_flag TINYINT)"
                        + " ENGINE=InnoDB");
                statement.execute("CREATE TABLE merchant_customer_note (id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + " merchant_id BIGINT, merchant_scope_version INT, member_id BIGINT, hidden INT,"
                        + " content VARCHAR(255), create_time DATETIME) ENGINE=InnoDB");
                statement.execute("CREATE TABLE merchant_customer_tag_assignment (id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + " merchant_id BIGINT, merchant_scope_version INT, member_id BIGINT, tag_id BIGINT,"
                        + " action VARCHAR(16)) ENGINE=InnoDB");
                statement.execute("CREATE TABLE play_team (id BIGINT PRIMARY KEY, title VARCHAR(64),"
                        + " owner_type INT, owner_id BIGINT) ENGINE=InnoDB");
                statement.execute("CREATE TABLE play_team_member (id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + " team_id BIGINT, member_id BIGINT, status INT) ENGINE=InnoDB");
                statement.execute("CREATE TABLE player_activity_role (id BIGINT PRIMARY KEY AUTO_INCREMENT,"
                        + " member_id BIGINT, activity_id BIGINT, role_code VARCHAR(32)) ENGINE=InnoDB");

                statement.execute("INSERT INTO ums_member VALUES (61,'自由探索买家','',NULL,0),"
                        + "(62,'普通主题买家','','13900000062',0)");
                // 81:主题方 88 发布的自由探索;82:主题方 88 的普通主题。
                statement.execute("INSERT INTO cms_topic VALUES (81,88,0,2,0),(82,88,0,NULL,0)");
                statement.execute("INSERT INTO cms_registration_merchant (topic_id,member_id,audit_status,del_flag)"
                        + " VALUES (81,66,1,0),(81,67,0,0),(82,66,1,0)");
                statement.execute("INSERT INTO merchant_chapter_application (topic_id,chapter_id,merchant_id,status,del_flag)"
                        + " VALUES (81,9001,68,1,0),(81,9002,67,0,0)");
                // 自由探索的票挂在主题方建的活动壳 501 上(owner_type = 2,与生产购票路径一致)。
                statement.execute("INSERT INTO cms_activity VALUES (501,88,81,0)");
                // 401:已付款且已核销(扫码)的自由探索票;402:普通主题票。
                statement.execute("INSERT INTO cms_registration (id,member_id,phone,payment_status,"
                        + " verification_status,payable_amount,total_amount,verification_time,create_time,"
                        + " owner_type,owner_id,activity_title) VALUES "
                        + "(401,61,NULL,2,1,39.00,39.00,current_timestamp,current_timestamp,2,501,'自由探索'),"
                        + "(402,62,NULL,2,0,20.00,20.00,NULL,current_timestamp,1,82,'普通主题')");
                statement.execute("INSERT INTO consent_record (member_id,doc_type,doc_version,scene,event_type,"
                        + " occurred_at,request_id,ip_address,create_time) VALUES "
                        + "(61,'activity_host_data_sharing','v2.1','activity_signup','AGREE',"
                        + " date_sub(current_timestamp, interval 1 hour),'it-fe-consent-61',NULL,current_timestamp)");
                statement.execute("UPDATE ums_member SET phone='" + crypto.encrypt(BUYER_PHONE) + "' WHERE id=61");
            }
        }
    }

    private static void consent(String url, long member, long merchantOwner, String event, String at,
                                String requestId) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, MybatisItDatabase.user(),
                MybatisItDatabase.password()); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO consent_record (member_id,doc_type,doc_version,scene,scope_type,scope_id,"
                    + " event_type,occurred_at,request_id,ip_address,create_time) VALUES (" + member
                    + ",'merchant_onsite_data_sharing','v1','merchant_redeem','MERCHANT'," + merchantOwner + ",'"
                    + event + "','" + at + "','" + requestId + "',NULL,current_timestamp)");
        }
    }

    private long scalar(String url, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, MybatisItDatabase.user(),
                MybatisItDatabase.password()); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void dropAndVerifyGone(String database) throws Exception {
        String adminUrl = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                + "/?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection connection = DriverManager.getConnection(adminUrl, MybatisItDatabase.user(),
                MybatisItDatabase.password()); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + database + "'")) {
                rows.next();
                assertEquals(0, rows.getInt(1), "scratch 库必须已 DROP");
            }
        }
    }
}

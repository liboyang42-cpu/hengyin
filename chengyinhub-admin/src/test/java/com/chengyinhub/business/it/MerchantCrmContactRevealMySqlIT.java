package com.chengyinhub.business.it;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.vo.MerchantCrmContactVO;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F15「获取联系号码」真库验证:权限之外的四道闸(归属 / 本店负责 / 同意 / 日限)与审计落库。
 *
 * <p>为什么必须连真库:归属与「是否由本商家负责联系」是两条 SQL(OwnedRegistrationScope /
 * MerchantResponsibleRegistration),Mockito 掉 mapper 等于把这两条闸一起 mock 了 ——
 * 该红却绿的形状。这里用真实 MySQL + 真实 Mapper XML + 真实 ConsentRecordServiceImpl 钉住:
 * 越权商家拒、俱乐部带团拒、撤回同意拒、昨日次数不占今日额度、第 51 次拒。</p>
 *
 * <p><b>2026-09-15 收紧。</b>①服务改成经真实 {@link TransactionInterceptor} 代理调用:
 * 越权/超限被拒时写的那条 DENIED 审计靠 {@code @Transactional(noRollbackFor = ServiceException)}
 * 才能活下来,裸 mapper(autocommit)测不到这条规则;②新增并发用例:日限只差 1 次时两请求
 * 只能成功一个 —— 频控的原子性在 Mapper 的 {@code select ... for update} 上。</p>
 *
 * <p>一类一库:自带唯一后缀的 scratch schema,跑完 DROP 并回读 0。</p>
 */
class MerchantCrmContactRevealMySqlIT {

    private static final long MERCHANT = 77L;
    private static final long OWNER = 88L;
    private static final long OTHER_OWNER = 99L;
    private static final long OPERATOR = 41L;
    private static final long CUSTOMER = 51L;
    private static final long NO_CONSENT_CUSTOMER = 52L;
    private static final long CLUB_CUSTOMER = 53L;
    private static final long CLUB_INVITE_CUSTOMER = 54L;
    private static final int DAILY_LIMIT = 50;

    /** 夹具种下的全部明文号码:审计任何一列都不许带上它们中的任意一个。 */
    private static final String PHONE_CONSENTED = "13800138000";   // 51 同意客户
    private static final String PHONE_REVOKED = "13900000002";     // 52 撤回客户
    private static final String PHONE_CLUB = "13900000003";        // 53 俱乐部带团客户
    private static final String PHONE_CLUB_INVITE = "13900000004"; // 54 邀约俱乐部客户
    private static final String[] SEEDED_PHONES = {
            PHONE_CONSENTED, PHONE_REVOKED, PHONE_CLUB, PHONE_CLUB_INVITE};
    /** 号码段前缀:自由文本里连脱敏片段(138****8000)都不许出现。 */
    private static final String[] PHONE_PREFIXES = {"138", "139"};
    /**
     * 被拒审计请求号的唯一合法形状:appendAuditEvent 写的是
     * "crm-contact-denied-" + sha256Hex(...).substring(0, 44),即前缀 + 恰好 44 位小写十六进制。
     */
    private static final Pattern DENIED_REQUEST_ID = Pattern.compile("crm-contact-denied-[0-9a-f]{44}");

    private IMerchantAccessService access;
    private IMerchantCrmAppService service;

    @Test
    void contactRevealEnforcesOwnershipResponsibilityConsentAuditAndDailyCap() throws Exception {
        String database = MybatisItDatabase.databaseNameAfterCreate(
                "crm_contact_reveal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        String url = itUrl(database);
        PiiCryptoService previous = PiiCryptoService.getInstance();
        PiiCryptoService crypto = new PiiCryptoService(
                Base64.getEncoder().encodeToString(new byte[32]), "crm-contact-it");
        try {
            crypto.register();
            createSchemaAndSeed(url, crypto);
            wireService(url);

            // 昨日已用满 50 次:自然日窗口必须按当天重置,今天第一单仍要放行(兼作日窗负控)。
            seedRevealAuditRows(url, OPERATOR, "yesterday", DAILY_LIMIT);
            MerchantCrmContactVO revealed = service.revealContact(OPERATOR, CUSTOMER, "call");
            assertEquals(PHONE_CONSENTED, revealed.getPhone(),
                    "授权链全过时必须下发明文(密文必须被真实 TypeHandler 解开)");
            assertRevealAudit(url, OPERATOR, CUSTOMER, "purpose=call");

            // ① 越权商家:同一客户不属于解析出的 owner,必须查不出;被拒也要留审计(deny=ownership),
            //    但不得留下成功审计、不占日额度。
            long todayBefore = revealAuditCountToday(url, OPERATOR);
            stubOwner(OTHER_OWNER);
            ServiceException crossMerchant = assertThrows(ServiceException.class,
                    () -> service.revealContact(OPERATOR, CUSTOMER, "call"));
            assertEquals(404, crossMerchant.getCode());
            assertEquals(todayBefore, revealAuditCountToday(url, OPERATOR), "越权被拒不得留下成功审计");

            // ② 撤回同意:有归属、本店负责、有号码,但最新同意事件是 REVOKE → 拒。
            stubOwner(OWNER);
            ServiceException noConsent = assertThrows(ServiceException.class,
                    () -> service.revealContact(OPERATOR, NO_CONSENT_CUSTOMER, "copy"));
            assertEquals(403, noConsent.getCode());
            assertEquals("客户未授权联系方式共享", noConsent.getMessage());
            assertEquals(todayBefore, revealAuditCountToday(url, OPERATOR), "无同意被拒不得留下成功审计");

            // ③ 负控 · 俱乐部带团(club_id 挂靠):属于名册但不归商家联系,即使同意有效也拒。
            ServiceException clubLed = assertThrows(ServiceException.class,
                    () -> service.revealContact(OPERATOR, CLUB_CUSTOMER, "call"));
            assertEquals(403, clubLed.getCode());
            assertEquals("由俱乐部带团，联系请找俱乐部负责人", clubLed.getMessage());
            // ④ 负控 · 主题虽无 club_id,但已有俱乐部接受邀约 → 同样不归商家联系。
            ServiceException clubInvited = assertThrows(ServiceException.class,
                    () -> service.revealContact(OPERATOR, CLUB_INVITE_CUSTOMER, "call"));
            assertEquals(403, clubInvited.getCode());
            assertEquals(todayBefore, revealAuditCountToday(url, OPERATOR), "俱乐部带团被拒不得留下成功审计");

            // ⑤ 日限:今日审计 = 1(上面成功那单) + 49(补足)= 50,第 51 次必须拒且不再写成功审计。
            seedRevealAuditRows(url, OPERATOR, "today", DAILY_LIMIT - 1);
            assertEquals(DAILY_LIMIT, revealAuditCountToday(url, OPERATOR));
            ServiceException capped = assertThrows(ServiceException.class,
                    () -> service.revealContact(OPERATOR, CUSTOMER, "call"));
            assertEquals(429, capped.getCode());
            assertEquals(DAILY_LIMIT, revealAuditCountToday(url, OPERATOR), "超限被拒不得留下成功审计");

            // ⑥ 审计只留「谁、哪个客户、什么用途」;禁止把号码写进正文、原因或请求号。
            assertRevealAuditRow(url, OPERATOR, CUSTOMER);

            // ⑦ 越权/超限的失败尝试各自留一条 DENIED 审计(独立动作码,不占成功日额度),
            //    且一个号码字符都不许进去 —— 没有这条,「被拒也写审计」可以悄悄被删掉。
            assertEquals(5, deniedAuditCountToday(url, OPERATOR), "越权/超限被拒都要留 DENIED 审计");
            assertEquals(1, deniedAuditCount(url, OPERATOR, "ownership"));
            assertEquals(1, deniedAuditCount(url, OPERATOR, "no_consent"));
            assertEquals(2, deniedAuditCount(url, OPERATOR, "club_led"));
            assertEquals(1, deniedAuditCount(url, OPERATOR, "daily_limit"));
            assertDeniedAuditHasNoPhoneBody(url, OPERATOR);
        } finally {
            ReflectionTestUtils.setField(PiiCryptoService.class, "instance", previous);
            dropAndVerifyGone(database);
        }
    }

    /**
     * 并发下限只差 1 次:两个请求只能成功一个,另一个必须 429 且留 DENIED 审计。
     *
     * <p>确定性设计:给「第一个到达频控计数的请求」加一个闸 —— 它的 count 已拿到行锁、
     * 还没写成功审计时停住,第二个请求顶到同一把锁上,再放行。若把 Mapper 的
     * {@code for update} 去掉(负控),两边都会读到 49、双双放行,断言即红。</p>
     */
    @Test
    void concurrentRevealsCannotBreakTheDailyCap() throws Exception {
        String database = MybatisItDatabase.databaseNameAfterCreate(
                "crm_contact_quota_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        String url = itUrl(database);
        PiiCryptoService previous = PiiCryptoService.getInstance();
        PiiCryptoService crypto = new PiiCryptoService(
                Base64.getEncoder().encodeToString(new byte[32]), "crm-contact-it");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            crypto.register();
            createSchemaAndSeed(url, crypto);

            AtomicBoolean firstCounter = new AtomicBoolean(true);
            CountDownLatch firstCountReached = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            DriverManagerDataSource dataSource = dataSource(url);
            SqlSessionTemplate template = template(dataSource);
            BusinessAuditEventMapper realAudit = template.getMapper(BusinessAuditEventMapper.class);
            wireService(template, dataSource,
                    delayingAuditMapper(realAudit, firstCounter, firstCountReached, releaseFirst));
            seedRevealAuditRows(url, OPERATOR, "today", DAILY_LIMIT - 1);
            assertEquals(DAILY_LIMIT - 1, revealAuditCountToday(url, OPERATOR));

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<String> first = pool.submit(() -> revealOutcome(ready, start));
            Future<String> second = pool.submit(() -> revealOutcome(ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(firstCountReached.await(10, TimeUnit.SECONDS), "第一个请求必须走到频控计数");
            // 让第二个请求真正压到同一把行锁上(FOR UPDATE)再放行第一个。
            Thread.sleep(400);
            releaseFirst.countDown();

            List<String> outcomes = Arrays.asList(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(outcomes, "OK"),
                    "日限只差 1 次时并发两请求只能成功一个:" + outcomes);
            assertEquals(1, Collections.frequency(outcomes, "429"),
                    "另一个必须被日限拒绝,不能两单都放行把上限捅穿:" + outcomes);
            assertEquals(DAILY_LIMIT, revealAuditCountToday(url, OPERATOR),
                    "成功揭示审计必须恰好 50 条,不能是 51");
            assertEquals(1, deniedAuditCountToday(url, OPERATOR), "被拒的一方要留一条 DENIED 审计");
            assertEquals(1, deniedAuditCount(url, OPERATOR, "daily_limit"));
            assertDeniedAuditHasNoPhoneBody(url, OPERATOR);
        } finally {
            pool.shutdownNow();
            ReflectionTestUtils.setField(PiiCryptoService.class, "instance", previous);
            dropAndVerifyGone(database);
        }
    }

    private String itUrl(String database) {
        return "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                + "/" + database + "?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private void stubOwner(long ownerMemberId) {
        when(access.require(anyLong(), anyString())).thenReturn(new MerchantAccessContext(
                OPERATOR, MERCHANT, ownerMemberId, MerchantRoleCode.MERCHANT_MANAGER));
    }

    /** 用真实 Mapper XML 与真实 ConsentRecordServiceImpl 装配服务;access 只负责解析出指定 owner。 */
    private void wireService(String url) throws Exception {
        DriverManagerDataSource dataSource = dataSource(url);
        SqlSessionTemplate template = template(dataSource);
        wireService(template, dataSource, template.getMapper(BusinessAuditEventMapper.class));
    }

    /**
     * 服务经真实 {@link TransactionInterceptor} 代理装配 —— @Transactional / noRollbackFor /
     * 行锁语义与生产同一条路径,不用裸 mapper 的 autocommit 假替身。
     */
    private void wireService(SqlSessionTemplate template, DriverManagerDataSource dataSource,
                             BusinessAuditEventMapper auditMapper) {
        ConsentRecordServiceImpl consent = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(consent, "consentRecordMapper", template.getMapper(ConsentRecordMapper.class));
        ComplianceProperties properties = new ComplianceProperties();
        properties.setDocVersions(Collections.singletonMap("activity_host_data_sharing", "v2.1"));

        access = mock(IMerchantAccessService.class);
        stubOwner(OWNER);

        MerchantCrmAppServiceImpl target = new MerchantCrmAppServiceImpl();
        ReflectionTestUtils.setField(target, "merchantAccessService", access);
        ReflectionTestUtils.setField(target, "merchantCrmAppMapper", template.getMapper(MerchantCrmAppMapper.class));
        ReflectionTestUtils.setField(target, "merchantCustomerTagMapper", mock(MerchantCustomerTagMapper.class));
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(target, "consentRecordService", consent);
        ReflectionTestUtils.setField(target, "complianceProperties", properties);
        ReflectionTestUtils.setField(target, "playerRoleService",
                mock(com.chengyinhub.business.service.IPlayerRoleService.class));

        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(IMerchantCrmAppService.class);
        proxyFactory.addAdvice(interceptor);
        service = (IMerchantCrmAppService) proxyFactory.getProxy();
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(MybatisItDatabase.user());
        dataSource.setPassword(MybatisItDatabase.password());
        return dataSource;
    }

    private SqlSessionTemplate template(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment("crm-contact-it",
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
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
    }

    /**
     * 只拦「第一个到达频控计数的请求」:真实 count 执行完(锁已在手)后停住,
     * 让第二个请求确定性顶到同一把锁上,不靠 sleep 撞运气。
     */
    private BusinessAuditEventMapper delayingAuditMapper(BusinessAuditEventMapper real, AtomicBoolean first,
                                                         CountDownLatch reached, CountDownLatch release) {
        return (BusinessAuditEventMapper) Proxy.newProxyInstance(
                MerchantCrmContactRevealMySqlIT.class.getClassLoader(),
                new Class<?>[]{BusinessAuditEventMapper.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                    if ("countActorActionToday".equals(method.getName()) && first.compareAndSet(true, false)) {
                        reached.countDown();
                        release.await(10, TimeUnit.SECONDS);
                    }
                    return result;
                });
    }

    private String revealOutcome(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        try {
            service.revealContact(OPERATOR, CUSTOMER, "call");
            return "OK";
        } catch (ServiceException denied) {
            return String.valueOf(denied.getCode());
        }
    }

    private void createSchemaAndSeed(String url, PiiCryptoService crypto) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, MybatisItDatabase.user(),
                MybatisItDatabase.password())) {
            // 审计与同意的建表走仓库真实 migration,只把后续演进的列补上(20260828 的
            // actor_type / request_id 收紧;20260815 的 scope 列),不另抄一份过期 DDL。
            ItSchema.applyMigrations(connection, "sql/migration_business_audit_event_20260823.sql");
            ItSchema.applyMigrations(connection, "sql/migration_compliance_consent_deregister_20260711.sql");
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE business_audit_event ADD COLUMN actor_type VARCHAR(16)"
                        + " NOT NULL DEFAULT 'UNKNOWN' AFTER owner_member_id,"
                        + " MODIFY COLUMN request_id VARCHAR(64) NOT NULL");
                statement.execute("ALTER TABLE consent_record ADD COLUMN scope_type VARCHAR(32) NULL AFTER scene,"
                        + " ADD COLUMN scope_id BIGINT NULL AFTER scope_type,"
                        + " ADD KEY idx_consent_member_doc_scene_scope"
                        + " (member_id, doc_type, scene, scope_type, scope_id, occurred_at)");

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
                        + " create_time DATETIME, owner_type INT, owner_id BIGINT, activity_title VARCHAR(255)"
                        + " ) ENGINE=InnoDB");

                statement.execute("INSERT INTO ums_member VALUES "
                        + "(51,'同意客户','',NULL,0),"
                        + "(52,'撤回客户','','" + PHONE_REVOKED + "',0),"
                        + "(53,'俱乐部带团客户','','" + PHONE_CLUB + "',0),"
                        + "(54,'邀约俱乐部客户','','" + PHONE_CLUB_INVITE + "',0)");
                // owner 88 的自办主题:71 无俱乐部且无邀约(本店负责),72 挂靠俱乐部,73 有已接受邀约。
                statement.execute("INSERT INTO cms_topic VALUES (71,88,0,NULL,0),(72,88,99,NULL,0),(73,88,0,NULL,0)");
                statement.execute("INSERT INTO coop_invite (topic_id,to_type,status,del_flag)"
                        + " VALUES (73,'club',1,0)");
                // 报名电话留空,号码只从会员表解出 —— 正好验证列表同款 coalesce 兜底。
                statement.execute("INSERT INTO cms_registration (id,member_id,phone,payment_status,"
                        + " verification_status,payable_amount,total_amount,verification_time,create_time,"
                        + " owner_type,owner_id,activity_title) VALUES "
                        + "(301,51,NULL,2,1,10.00,10.00,current_timestamp,current_timestamp,1,71,'主题报名'),"
                        + "(302,52,NULL,2,1,10.00,10.00,current_timestamp,current_timestamp,1,71,'主题报名'),"
                        + "(303,53,NULL,2,1,10.00,10.00,current_timestamp,current_timestamp,1,72,'主题报名'),"
                        + "(304,54,NULL,2,1,10.00,10.00,current_timestamp,current_timestamp,1,73,'主题报名')");
                // 同意真源:51 当前版同意;52 最新事件为撤回(即使曾经同意过);53/54 同意,用来证明被拒不是同意闸。
                statement.execute("INSERT INTO consent_record (member_id,doc_type,doc_version,scene,event_type,"
                        + " occurred_at,request_id,ip_address,create_time) VALUES "
                        + "(51,'activity_host_data_sharing','v2.1','activity_signup','AGREE',"
                        + " date_sub(current_timestamp, interval 1 hour),'it-consent-51',NULL,current_timestamp),"
                        + "(52,'activity_host_data_sharing','v2.1','activity_signup','AGREE',"
                        + " date_sub(current_timestamp, interval 2 hour),'it-consent-52-a',NULL,current_timestamp),"
                        + "(52,'activity_host_data_sharing','v2.1','activity_signup','REVOKE',"
                        + " date_sub(current_timestamp, interval 1 hour),'it-consent-52-r',NULL,current_timestamp),"
                        + "(53,'activity_host_data_sharing','v2.1','activity_signup','AGREE',"
                        + " date_sub(current_timestamp, interval 1 hour),'it-consent-53',NULL,current_timestamp),"
                        + "(54,'activity_host_data_sharing','v2.1','activity_signup','AGREE',"
                        + " date_sub(current_timestamp, interval 1 hour),'it-consent-54',NULL,current_timestamp)");
            }
            try (PreparedStatement encrypted = connection.prepareStatement(
                    "update ums_member set phone=? where id=51")) {
                encrypted.setString(1, crypto.encrypt(PHONE_CONSENTED));
                encrypted.executeUpdate();
            }
        }
    }

    private void seedRevealAuditRows(String url, long actor, String day, int count) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, MybatisItDatabase.user(),
                MybatisItDatabase.password());
             PreparedStatement insert = connection.prepareStatement(
                     "insert into business_audit_event (domain_type, domain_id, owner_member_id, actor_type,"
                             + " actor_member_id, actor_role_code, action, target_type, target_id, reason,"
                             + " request_id, create_time) values ('MERCHANT',?,?, 'MEMBER',?,'MERCHANT_MANAGER',"
                             + " 'CRM_CONTACT_REVEAL','CRM_CUSTOMER',?, 'purpose=call',?,"
                             + ("yesterday".equals(day) ? "date_sub(current_timestamp, interval 1 day)" : "current_timestamp")
                             + ")")) {
            for (int i = 0; i < count; i++) {
                insert.setLong(1, MERCHANT);
                insert.setLong(2, OWNER);
                insert.setLong(3, actor);
                insert.setLong(4, 900000L + i);
                insert.setString(5, "crm-contact-seed-" + day + "-" + i);
                insert.executeUpdate();
            }
        }
    }

    private long revealAuditCountToday(String url, long actor) throws Exception {
        return scalar(url, "select count(1) from business_audit_event where actor_member_id=?"
                + " and action='CRM_CONTACT_REVEAL' and create_time >= curdate()", actor);
    }

    private long deniedAuditCountToday(String url, long actor) throws Exception {
        return scalar(url, "select count(1) from business_audit_event where actor_member_id=?"
                + " and action='CRM_CONTACT_REVEAL_DENIED' and create_time >= curdate()", actor);
    }

    private long deniedAuditCount(String url, long actor, String denyCode) throws Exception {
        return scalar(url, "select count(1) from business_audit_event where actor_member_id=?"
                + " and action='CRM_CONTACT_REVEAL_DENIED' and reason like ?",
                actor, "%deny=" + denyCode);
    }

    private long scalar(String url, String sql, long actor, String... extra) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, MybatisItDatabase.user(),
                MybatisItDatabase.password());
             PreparedStatement query = connection.prepareStatement(sql)) {
            query.setLong(1, actor);
            for (int i = 0; i < extra.length; i++) {
                query.setString(i + 2, extra[i]);
            }
            try (ResultSet row = query.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private void assertRevealAudit(String url, long actor, long customer, String reason) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, MybatisItDatabase.user(),
                MybatisItDatabase.password());
             PreparedStatement query = connection.prepareStatement(
                     "select count(1) from business_audit_event where actor_member_id=? and target_id=?"
                             + " and action='CRM_CONTACT_REVEAL' and reason=?")) {
            query.setLong(1, actor);
            query.setLong(2, customer);
            query.setString(3, reason);
            try (ResultSet row = query.executeQuery()) {
                row.next();
                assertEquals(1, row.getLong(1), "每次成功揭示必须恰好留一条访问审计");
            }
        }
    }

    private void assertRevealAuditRow(String url, long actor, long customer) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, MybatisItDatabase.user(),
                MybatisItDatabase.password());
             PreparedStatement query = connection.prepareStatement(
                     "select domain_type, domain_id, owner_member_id, actor_type, actor_member_id,"
                             + " actor_role_code, target_type, target_id, reason, before_json, after_json, request_id"
                             + " from business_audit_event where actor_member_id=? and target_id=?"
                             + " and action='CRM_CONTACT_REVEAL' order by id limit 1")) {
            query.setLong(1, actor);
            query.setLong(2, customer);
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals("MERCHANT", row.getString("domain_type"));
                assertEquals(MERCHANT, row.getLong("domain_id"));
                assertEquals(OWNER, row.getLong("owner_member_id"));
                assertEquals("MEMBER", row.getString("actor_type"));
                assertEquals(OPERATOR, row.getLong("actor_member_id"));
                assertEquals(MerchantRoleCode.MERCHANT_MANAGER, row.getString("actor_role_code"));
                assertEquals("CRM_CUSTOMER", row.getString("target_type"));
                assertEquals(CUSTOMER, row.getLong("target_id"));
                assertEquals("purpose=call", row.getString("reason"));
                assertNull(row.getString("before_json"), "审计正文不得夹带号码");
                assertNull(row.getString("after_json"), "审计正文不得夹带号码");
                assertTrue(row.getString("request_id").startsWith("crm-contact-"));
                assertFalse(row.getString("request_id").contains(PHONE_CONSENTED));
            }
        }
    }

    /**
     * DENIED 审计的禁止面:reason / before / after 三列自由文本直扫号码,
     * request_id 用「完整号码 + 严格形状」两条确定性断言把守。
     *
     * <p><b>为什么 request_id 不再按 %138% 片段扫。</b>appendAuditEvent 写的 request_id 是
     * sha256 的十六进制前 44 位,而「1」「3」「8」三位全是合法十六进制字符 —— 拿片段去扫一个
     * 随机十六进制串,是在拿撞车概率当安全断言:实测单条误报率 0.99%,本用例 5 条 DENIED
     * 行 ≈ 4.9%/次红(run 35354557804 正是这么红的,而同一断言在更新的 7b83dbd5f 上绿)。
     * 这与 #1099 修掉的 CI-01 是同一根因,只是当时只修了单测、漏了这个 IT。
     * 闸本身没有被改弱:号码进 request_id 一定被下面两条之一抓住 ——
     * ① 拼进自由文本/带分隔符的形状立刻破;② 即便号码全是合法十六进制位、形状侥幸没破,
     * 完整号码扫描仍然红(号码本身是固定的 11 位,不是随机串)。</p>
     *
     * <p>顺带把原来扫不到的口子补上:旧版只扫「138」,而夹具里 52/53/54 三个客户的号是 139 开头,
     * 真泄了也不会红;现在四个号逐个扫,自由文本另扫 138/139 前缀(连脱敏片段 138****8000 也红)。</p>
     */
    private void assertDeniedAuditHasNoPhoneBody(String url, long actor) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, MybatisItDatabase.user(),
                MybatisItDatabase.password());
             PreparedStatement query = connection.prepareStatement(
                     "select reason, before_json, after_json, request_id from business_audit_event"
                             + " where actor_member_id=? and action='CRM_CONTACT_REVEAL_DENIED'")) {
            query.setLong(1, actor);
            try (ResultSet rows = query.executeQuery()) {
                int scanned = 0;
                while (rows.next()) {
                    scanned++;
                    for (String freeText : new String[]{rows.getString(1), rows.getString(2),
                            rows.getString(3)}) {
                        assertFreeTextCarriesNoPhone(freeText);
                    }
                    String requestId = rows.getString(4);
                    assertNotNull(requestId, "DENIED 审计必须带请求号");
                    for (String phone : SEEDED_PHONES) {
                        assertFalse(requestId.contains(phone), "request_id 不得编进号码:" + requestId);
                    }
                    // 形状闸:request_id 只能是前缀 + 44 位十六进制。任何把号码/客户号/自由文本
                    // 拼进请求号的改法(含 mapper 的 SHA2 兜底把前缀丢掉)都在这里红。
                    assertTrue(DENIED_REQUEST_ID.matcher(requestId).matches(),
                            "request_id 必须恰为 crm-contact-denied- + 44 位十六进制,实际=" + requestId);
                }
                // 防自己变成空断言:一行没扫过就等于没测。
                assertTrue(scanned > 0, "必须真的扫过至少一条 DENIED 审计,否则本断言是空的");
            }
        }
    }

    /** 自由文本(reason/before_json/after_json):号码正文与号码片段一律不许出现。 */
    private void assertFreeTextCarriesNoPhone(String value) {
        if (value == null) {
            return;
        }
        for (String phone : SEEDED_PHONES) {
            assertFalse(value.contains(phone), "审计自由文本不得夹带号码:" + value);
        }
        for (String prefix : PHONE_PREFIXES) {
            assertFalse(value.contains(prefix), "审计自由文本不得夹带号码片段:" + value);
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
                assertEquals(0, rows.getInt(1), "scratch 库必须已 DROP,回读必须为 0");
            }
        }
        System.out.println("CRM_CONTACT_SCRATCH_DROPPED=true db=" + database);
    }
}

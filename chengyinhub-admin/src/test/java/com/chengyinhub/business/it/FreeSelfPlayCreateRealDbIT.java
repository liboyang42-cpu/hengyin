package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.RegistrationOrderResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IRegistrationCreationTransaction;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.MerchantBusinessStatusServiceImpl;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.impl.RegistrationOrderCreateGateImpl;
import com.chengyinhub.business.service.impl.RegistrationOrderServiceImpl;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RUN-41 后端第二根因复现闸：免费主题（DRAFT + 0 元自玩）建自玩通行证的**整条真实建单事务**。
 *
 * <p><b>为什么存在。</b>生产上 topic 990059（预制人生 · 四站试玩版，lifecycle=DRAFT、self_play=1、
 * self_play_price=0.00、在架未截止）用完整合法 payload 调 /api/registration/create 仍 500，
 * 且 cms_registration 无落库行 —— 异常被控制器 catch 掩成「系统繁忙」且零日志（RUN-51）。
 * 仓里所有资金 IT 都把 {@code registrationOrderCreateGate} 整个 mock 掉，
 * 这条链在测试世界里从未以生产形态跑通过。本类把它按生产事实全量接通。
 *
 * <p><b>faithful 的边界。</b>schema 不是仓里手写的迷你 fixture，而是 2026-09-19 从生产
 * SHOW CREATE TABLE 逐字导出的 11 张表（it-schema/run41-prod-ddl.sql），种子行是
 * 990059/100092/商家 100000 的真实快照（手机号/姓名已脱敏，run41-prod-seed.sql）。
 * 「fixture 与生产 schema 漂移」正是本闸要排掉的那类根因，所以不能反过来用 fixture 复现。
 *
 * <p><b>不覆盖什么：</b>finishOrder 之后的零元支付链（completeZeroAmountPayment→核销码/结算/成长值）
 * 不在本闸 —— 生产无落库行已证明异常发生在 create 事务内，那半段由资金 IT 各自覆盖。
 *
 * <p>仅在显式打开 {@code CHENGYIN_SELFCREATE_IT_MYSQL=1} 且本机有 MySQL 时运行，普通单测不误连库。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_SELFCREATE_IT_MYSQL", matches = "1")
class FreeSelfPlayCreateRealDbIT {

    private static final String TEST_PII_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String DB_NAME = "chengyin_a_selfplay_" + java.util.UUID.randomUUID().toString().replace("-", "");
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("a_selfplay");
    private static boolean createdDatabase;

    /** 生产事实（990059 / 100092），不随 fixture 漂移。 */
    private static final long TOPIC_ID = 990059L;
    private static final long MEMBER_ID = 100092L;

    private static SqlSessionTemplate template;
    private static RegistrationOrderServiceImpl orderService;

    @BeforeAll
    static void createSchemaAndWireProductionShape() throws Exception {
        new PiiCryptoService(TEST_PII_KEY, "run41-it-hmac").register();
        for (String key : new String[]{"CHENGYIN_TEST_MYSQL_HOST", "CHENGYIN_TEST_MYSQL_PORT", "CHENGYIN_TEST_MYSQL_USER"}) {
            if (System.getenv(key) == null || System.getenv(key).trim().isEmpty()) throw new IllegalStateException("missing test target: " + key);
        }
        if (!MYSQL.adminUrl().startsWith("jdbc:mysql://127.0.0.1:") && !MYSQL.adminUrl().startsWith("jdbc:mysql://localhost:")) {
            throw new IllegalStateException("scratch localhost only");
        }
        DataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + DB_NAME + " CHARACTER SET utf8mb4");
            createdDatabase = true;
        }
        DataSource ds = dataSource(MYSQL.databaseUrl().replace("/" + MYSQL.databaseName() + "?", "/" + DB_NAME + "?"));
        try (Connection db = ds.getConnection()) {
            ScriptUtils.executeSqlScript(db, new org.springframework.core.io.ClassPathResource(
                    "it-schema/run41-prod-ddl.sql"));
            // ★ 按生产的真实演进顺序叠仓库迁移:基线 DDL(今天生产的样子)→ 本次修复的迁移。
            //   这同时把「迁移必须先于 jar 落库」的发布顺序焊进了测试 —— 谁删迁移文件,本闸立刻红。
            // 2026-10-14 集成:快照之后仓库演进到本 IT 用到的列(mapper 已读)一并叠加，否则查询直接 Unknown column。
            for (String name : new String[]{
                    "migration_20260919_cms_registration_selfplay_ticket_columns_nullable.sql",
                    "migration_20260930_registration_first_successful_scan.sql"}) {
                java.io.File migration = new java.io.File("sql/" + name);
                if (!migration.exists()) {
                    // surefire 工作目录是模块目录时走 ../;两种都认,别再犯 ExploreItDatabase 被 cwd 咬的错。
                    migration = new java.io.File("../" + migration.getPath());
                }
                assertTrue(migration.exists(), "RUN-41 修复迁移必须存在且随仓库演进: " + migration);
                ScriptUtils.executeSqlScript(db, new FileSystemResource(migration));
            }
            try (Statement ddl = db.createStatement()) {
                for (String sql : new String[]{
                        // cms_topic: migration_theme_runtime_20261004.sql
                        "ALTER TABLE cms_topic ADD COLUMN theme_runtime_config_json MEDIUMTEXT NULL",
                        "ALTER TABLE cms_topic ADD COLUMN published_theme_runtime_config_json MEDIUMTEXT NULL",
                        // merchant_chapter_offer: migration_20261003_merchant_offer_withdrawal_columns.sql
                        "ALTER TABLE merchant_chapter_offer ADD COLUMN replacement_of_offer_id BIGINT NULL DEFAULT NULL",
                        "ALTER TABLE merchant_chapter_offer ADD COLUMN withdraw_reason VARCHAR(500) NULL DEFAULT NULL",
                        "ALTER TABLE merchant_chapter_offer ADD COLUMN withdraw_required TINYINT(1) NULL DEFAULT NULL",
                        // merchant_chapter_offer: migration_club_direct_merchant_receivable_20261004.sql
                        "ALTER TABLE merchant_chapter_offer ADD COLUMN payer_club_id BIGINT NULL"}) {
                    ddl.execute(sql);
                }
            }
            ScriptUtils.executeSqlScript(db, new org.springframework.core.io.ClassPathResource(
                    "it-schema/run41-prod-seed.sql"));
        }

        template = new SqlSessionTemplate(buildFactory(ds));
        CmsTopicMapper topicMapper = template.getMapper(CmsTopicMapper.class);
        CmsRegistrationMapper registrationMapper = template.getMapper(CmsRegistrationMapper.class);

        // ── 建单事务本体：与生产 Spring 装配同构（真 mapper、真门禁、真策略）──
        CmsRegistrationServiceImpl target = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(target, "omsTicketMapper", template.getMapper(OmsTicketMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberMapper", template.getMapper(UmsMemberMapper.class));
        ReflectionTestUtils.setField(target, "cmsActivityMapper", template.getMapper(CmsActivityMapper.class));
        ReflectionTestUtils.setField(target, "topicSaleSnapshotMapper",
                template.getMapper(TopicSaleSnapshotMapper.class));
        ReflectionTestUtils.setField(target, "clubMapper", template.getMapper(ClubMapper.class));
        ReflectionTestUtils.setField(target, "clubMemberMapper", template.getMapper(ClubMemberMapper.class));
        ReflectionTestUtils.setField(target, "commercialTopicPolicy", new CommercialTopicPolicy(
                template.getMapper(OmsTicketMapper.class), template.getMapper(CmsTopicChapterMapper.class),
                template.getMapper(MerchantChapterOfferMapper.class), topicMapper));
        // 这些下游只在支付回调/退款侧工作,create 事务不触达 —— 与资金 IT 同款桩。
        ReflectionTestUtils.setField(target, "settlementRuleService", mock(ISettlementRuleService.class));
        ReflectionTestUtils.setField(target, "merchantReferralBountyService",
                mock(MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(target, "firstReviewPointsClawback",
                mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        ReflectionTestUtils.setField(target, "growthService",
                mock(com.chengyinhub.business.service.IGrowthService.class));
        ReflectionTestUtils.setField(target, "wxPaymentCallbackInboxService",
                mock(com.chengyinhub.business.service.IWxPaymentCallbackInboxService.class));
        ReflectionTestUtils.setField(target, "perkLedgerService",
                mock(com.chengyinhub.business.service.IPerkLedgerService.class));
        ReflectionTestUtils.setField(target, "cmsPointsSettingService",
                mock(com.chengyinhub.business.service.ICmsPointsSettingService.class));
        ReflectionTestUtils.setField(target, "settlementEntryService",
                mock(com.chengyinhub.business.service.ISettlementEntryService.class));
        // clubWaitlistOrderBridge 故意不注入：create() 对无候补字段的请求走 null 分支，与生产语义一致。

        PlatformTransactionManager txManager = new DataSourceTransactionManager(ds);
        TransactionInterceptor interceptor = new TransactionInterceptor(txManager,
                new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setInterfaces(ICmsRegistrationService.class, IRegistrationCreationTransaction.class);
        proxyFactory.addAdvice(interceptor);
        Object proxy = proxyFactory.getProxy();

        // 幂等键/实时门禁：生产用 RegistrationOrderCreateGateImpl 真身，资金 IT 长期 mock 掉的正是它。
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenAnswer(invocation ->
                topicMapper.selectCmsTopicById(TOPIC_ID));
        IMmsMerchantService mmsMerchantService = mock(IMmsMerchantService.class);
        MmsMerchantMapper merchantMapper = template.getMapper(MmsMerchantMapper.class);
        when(mmsMerchantService.selectMmsMerchantByMemberId(anyLong())).thenAnswer(invocation ->
                merchantMapper.selectMmsMerchantByMemberId(invocation.getArgument(0)));
        MerchantBusinessStatusServiceImpl businessStatus = new MerchantBusinessStatusServiceImpl();
        ReflectionTestUtils.setField(businessStatus, "topicService", topicService);
        ReflectionTestUtils.setField(businessStatus, "activityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(businessStatus, "mmsMerchantService", mmsMerchantService);

        IWechatPayService wechatPayService = mock(IWechatPayService.class);
        when(wechatPayService.isReady()).thenReturn(true);
        RegistrationOrderCreateGateImpl gate = new RegistrationOrderCreateGateImpl();
        ReflectionTestUtils.setField(gate, "registrationService", proxy);
        ReflectionTestUtils.setField(gate, "topicService", topicService);
        ReflectionTestUtils.setField(gate, "wechatPayService", wechatPayService);
        ReflectionTestUtils.setField(gate, "merchantBusinessStatusService", businessStatus);

        orderService = new RegistrationOrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "registrationService", proxy);
        ReflectionTestUtils.setField(orderService, "registrationCreationTransaction",
                (IRegistrationCreationTransaction) proxy);
        ReflectionTestUtils.setField(orderService, "registrationOrderCreateGate", gate);
    }

    @Test
    void freeTopicSelfPlayPassCreatePersists() {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(1);
        request.setOwnerId(TOPIC_ID);
        request.setRealName("IT测试玩家");
        request.setPhone("13800001111");
        request.setIsUsePoint(0);
        request.setRequestId("req_run41_it_1");

        RegistrationOrderResult result = orderService.createOrReplay(MEMBER_ID, request);

        assertNotNull(result.getPrimary());
        List<CmsRegistration> rows = allRegistrations();
        assertEquals(1, rows.size(), "免费主题自玩建单必须真实落库一行");
        CmsRegistration r = rows.get(0);
        assertEquals(0, r.getPayableAmount().compareTo(BigDecimal.ZERO));
        assertEquals(Long.valueOf(1L), r.getOwnerType());
        assertEquals(Long.valueOf(TOPIC_ID), r.getTopicId());
        assertEquals(Integer.valueOf(1), r.getRegistrationStatus());
        assertEquals(Integer.valueOf(0), r.getPaymentStatus());
        assertNotNull(r.getRequestFingerprint(), "requestId 指纹必须在同事务内绑定");

        // 幂等重放：同 requestId + 同指纹 ⇒ 回到同一张单，不再插第二行。
        RegistrationOrderResult replay = orderService.createOrReplay(MEMBER_ID, request);
        assertEquals(r.getId(), replay.getPrimary().getId());
        assertEquals(1, allRegistrations().size());
    }

    private List<CmsRegistration> allRegistrations() {
        CmsRegistration query = new CmsRegistration();
        query.setMemberId(MEMBER_ID);
        return template.getMapper(CmsRegistrationMapper.class).selectCmsRegistrationList(query);
    }

    @AfterAll
    static void dropOnlyCreatedScratchSchema() throws Exception {
        if (!createdDatabase) return;
        try (Connection c = dataSource(MYSQL.adminUrl()).getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE " + DB_NAME);
            createdDatabase = false;
        }
    }

    private static DataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }

    private static SqlSessionFactory buildFactory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("run41-selfplay-it",
                new SpringManagedTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(configuration, CmsRegistrationMapper.class, "mapper/business/CmsRegistrationMapper.xml");
        addMapper(configuration, CmsTopicMapper.class, "mapper/business/CmsTopicMapper.xml");
        addMapper(configuration, CmsTopicChapterMapper.class, "mapper/business/CmsTopicChapterMapper.xml");
        addMapper(configuration, MerchantChapterOfferMapper.class,
                "mapper/business/MerchantChapterOfferMapper.xml");
        addMapper(configuration, OmsTicketMapper.class, "mapper/business/OmsTicketMapper.xml");
        addMapper(configuration, CmsActivityMapper.class, "mapper/business/CmsActivityMapper.xml");
        addMapper(configuration, TopicSaleSnapshotMapper.class, "mapper/business/TopicSaleSnapshotMapper.xml");
        addMapper(configuration, UmsMemberMapper.class, "mapper/business/UmsMemberMapper.xml");
        addMapper(configuration, ClubMapper.class, "mapper/business/ClubMapper.xml");
        addMapper(configuration, ClubMemberMapper.class, "mapper/business/ClubMemberMapper.xml");
        addMapper(configuration, MmsMerchantMapper.class, "mapper/business/MmsMerchantMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void addMapper(Configuration configuration, Class<?> mapper, String resource)
            throws Exception {
        configuration.addMapper(mapper);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到 MyBatis XML: " + resource);
            }
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }
}

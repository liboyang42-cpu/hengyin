package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.sql.Connection;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemplateAdoptionRoundTripMySqlIT {
    private static PooledDataSource source;
    private static boolean scratchCreated;
    private static CmsMemberTemplateMapper members;
    private static CmsTemplateLibraryMapper libraries;
    private static ITemplatePublishService publish;
    private static JdbcTemplate db;
    private static SqlSessionTemplate sql;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll static void start() throws Exception {
        assertEquals("127.0.0.1", MybatisItDatabase.host());
        String scratchUrl = MybatisItDatabase.url("template_adoption_roundtrip");
        scratchCreated = true;
        source = new PooledDataSource("com.mysql.cj.jdbc.Driver", scratchUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
        try (Connection c = source.getConnection()) {
            ItSchema.apply(c, "it-schema/journey-full-flow-it-schema.sql");
            ItSchema.applyMigrations(c, "sql/migration_20261012_app_media_asset.sql",
                    "sql/migration_20261012_app_media_asset_namespaces.sql");
        }
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE cms_template_library LIKE cms_member_template");
        db.execute("ALTER TABLE cms_template_library ADD member_template_id BIGINT, ADD pack_type INT");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain,com.chengyinhub.business.domain.vo");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsMemberTemplateMapper.xml"), new ClassPathResource("mapper/business/CmsTemplateLibraryMapper.xml"), new ClassPathResource("mapper/business/AppMediaAssetMapper.xml"));
        sql = new SqlSessionTemplate(factory.getObject());
        members = sql.getMapper(CmsMemberTemplateMapper.class); libraries = sql.getMapper(CmsTemplateLibraryMapper.class);
        CmsMemberTemplateServiceImpl memberService = new CmsMemberTemplateServiceImpl(); ReflectionTestUtils.setField(memberService, "cmsMemberTemplateMapper", members);
        CmsTemplateLibraryServiceImpl libraryService = new CmsTemplateLibraryServiceImpl(); ReflectionTestUtils.setField(libraryService, "cmsTemplateLibraryMapper", libraries);
        TemplatePublishServiceImpl target = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(target, "memberTemplateService", memberService); ReflectionTestUtils.setField(target, "memberTemplateMapper", members);
        ReflectionTestUtils.setField(target, "templateLibraryService", libraryService); ReflectionTestUtils.setField(target, "libraryMapper", libraries);
        ReflectionTestUtils.setField(target, "auditTaskService", mock(IAuditTaskService.class));
        ReflectionTestUtils.setField(target, "auditTaskMapper", mock(AuditTaskMapper.class)); ReflectionTestUtils.setField(target, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(target, "mediaPublicationGuard", MediaPublicationMySqlFixture.guard(sql, members));
        ProxyFactory proxy = new ProxyFactory(target); proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        publish = (ITemplatePublishService) proxy.getProxy();
    }
    @AfterAll static void close() throws Exception {
        try { if (source != null) source.forceCloseAll(); }
        finally { dropScratch(); }
    }

    private static final String SCAN_QA="{\"schemaVersion\":1,\"present\":\"inline\",\"scan\":{\"enabled\":true,\"kind\":\"TEXT\",\"reply\":\"欢迎\"},\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"答案\",\"answerText\":\"yes\"}}";
    @BeforeEach void clean() { db.update("DELETE FROM cms_template_library"); db.update("DELETE FROM cms_member_template"); }
    @Test void scanParticipationDoesNotForcePublishedMainGameplayFullscreen() throws Exception {
        CmsTemplatePublishRequest request=new CmsTemplatePublishRequest();request.setTitle("扫码答题");request.setValidationMethod(4);request.setAdvancedConfigJson(SCAN_QA);
        Long id=publish.publishTemplateWithTransaction(request,7L,"author");CmsMemberTemplate saved=members.selectCmsMemberTemplateById(id);
        assertEquals(4,saved.getValidationMethod());assertEquals(json.readTree(SCAN_QA),json.readTree(saved.getAdvancedConfigJson()));
        assertEquals("inline",com.chengyinhub.business.service.support.AdvancedGameConfigValidator.resolvePresent(json.readTree(saved.getAdvancedConfigJson())));
    }
    @Test void scanParticipationAdoptionPreservesMainPresentationAndScanReply() throws Exception {
        CmsTemplateLibrary original=new CmsTemplateLibrary();original.setMemberId(99L);original.setTitle("扫码问答");original.setValidationMethod(4);original.setAdvancedConfigJson(SCAN_QA);original.setStatus(1L);original.setPublishStatus(1L);original.setDelFlag(0);libraries.insertCmsTemplateLibrary(original);
        CmsTemplatePublishRequest request=new CmsTemplatePublishRequest();request.setOriginalTemplateId(original.getId());request.setAdoptSourceOnly(true);
        Long id=publish.saveOrUpdateDraft(request,7L,"author");CmsMemberTemplate saved=members.selectCmsMemberTemplateById(id);
        assertEquals(4,saved.getValidationMethod());assertEquals(json.readTree(SCAN_QA),json.readTree(saved.getAdvancedConfigJson()));assertEquals("欢迎",json.readTree(saved.getAdvancedConfigJson()).path("scan").path("reply").asText());
    }

    @Test void unknownLegacyMediaIsRejectedBeforeDraftWriteButOwnHoldCanSaveAndCannotPublish() {
        CmsTemplatePublishRequest unknown = new CmsTemplatePublishRequest();
        unknown.setTitle("未知旧媒体"); unknown.setStoryImg("https://www.chengyinhub.com/favicon.ico");
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> publish.saveOrUpdateDraft(unknown, 7L, "author"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM cms_member_template", Integer.class));
        String held = MediaPublicationMySqlFixture.heldImage(sql, 7L);
        CmsTemplatePublishRequest own = new CmsTemplatePublishRequest();
        own.setTitle("作者待审草稿"); own.setStoryImg(held);
        Long id = publish.saveOrUpdateDraft(own, 7L, "author");
        assertEquals(held, members.selectCmsMemberTemplateById(id).getStoryImg());
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> publish.publishTemplateWithTransaction(own, 7L, "author"));
        assertEquals(0L, members.selectCmsMemberTemplateById(id).getPublishStatus());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM cms_member_template", Integer.class));
        CmsTemplatePublishRequest foreign = new CmsTemplatePublishRequest();
        foreign.setTitle("异账号待审素材"); foreign.setStoryImg(held);
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> publish.saveOrUpdateDraft(foreign, 8L, "outsider"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM cms_member_template", Integer.class));
    }

    @Test void adoptionWithCollidingIdsPreservesAllFieldsAndOwnedResumeOnlyChangesItsCopy() throws Exception {
        CmsMemberTemplate existing = new CmsMemberTemplate(); existing.setMemberId(7L); existing.setTitle("现有个人模板"); existing.setDelFlag(0);
        assertEquals(1, members.insertCmsMemberTemplate(existing));
        CmsTemplateLibrary original = new CmsTemplateLibrary(); original.setMemberId(99L); original.setTitle("公共传感器模板");
        original.setValidationMethod(7); original.setSensorType("steps"); original.setSensorConfig("{\"targetSteps\":120,\"windowSec\":300}");
        String reviewedImage = MediaPublicationMySqlFixture.approvedImage(sql, 99L);
        original.setRewardRulesJson("[{\"outcomeCode\":\"STEPS_PASS\",\"xp\":10}]"); original.setMerchantGuide("工作人员说明"); original.setStoryImg(reviewedImage);
        original.setQuestionAnswer("原核验答案"); original.setQuestionImg(reviewedImage);
        original.setAdvancedConfigJson("{\"schemaVersion\":1,\"timer\":{\"enabled\":true,\"durationSeconds\":300,\"timeoutResult\":\"FAILED\"}}");
        original.setStatus(1L); original.setPublishStatus(1L); original.setDelFlag(0); assertEquals(1, libraries.insertCmsTemplateLibrary(original));
        db.update("UPDATE cms_template_library SET id=? WHERE id=?", existing.getId(), original.getId());
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest(); request.setOriginalTemplateId(existing.getId()); request.setAdoptSourceOnly(true);
        Long adoptedId = publish.saveOrUpdateDraft(request, 7L, "author"); assertNotEquals(existing.getId(), adoptedId);
        CmsMemberTemplate adopted = members.selectCmsMemberTemplateById(adoptedId);
        assertEquals(7L, adopted.getMemberId()); assertEquals(existing.getId(), adopted.getOriginalTemplateId());
        BeanUtils.copyProperties(adopted, request, "duration", "adoptSourceOnly"); request.setAdoptSourceOnly(false); request.setTitle("只修改副本标题");
        assertEquals(adoptedId, publish.saveOrUpdateDraft(request, 7L, "author"));
        CmsMemberTemplate saved = members.selectCmsMemberTemplateById(adoptedId);
        assertEquals(original.getMerchantGuide(), saved.getMerchantGuide()); assertEquals(original.getStoryImg(), saved.getStoryImg());
        assertEquals(original.getQuestionAnswer(), saved.getQuestionAnswer()); assertEquals(original.getQuestionImg(), saved.getQuestionImg());
        assertEquals(original.getSensorType(), saved.getSensorType()); assertEquals(json.readTree(original.getSensorConfig()), json.readTree(saved.getSensorConfig()));
        assertEquals(json.readTree(original.getRewardRulesJson()), json.readTree(saved.getRewardRulesJson()));
        assertEquals(json.readTree(original.getAdvancedConfigJson()), json.readTree(saved.getAdvancedConfigJson()));
        assertEquals("现有个人模板", members.selectCmsMemberTemplateById(existing.getId()).getTitle());
        assertEquals("公共传感器模板", libraries.selectCmsTemplateLibraryById(existing.getId()).getTitle());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM cms_member_template", Integer.class));
        assertThrows(RuntimeException.class, () -> publish.saveOrUpdateDraft(request, 8L, "outsider"));
        assertEquals("只修改副本标题", members.selectCmsMemberTemplateById(adoptedId).getTitle());
    }

    private static void dropScratch() throws Exception {
        if (!scratchCreated) return;
        String database = MybatisItDatabase.databaseName("template_adoption_roundtrip");
        if (!"127.0.0.1".equals(MybatisItDatabase.host()) || !database.matches("chengyin_mybatis_[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Refusing cleanup outside owned loopback scratch");
        }
        try (java.sql.Connection admin = java.sql.DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + MybatisItDatabase.port() + "/?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password());
             java.sql.Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE `" + database + "`");
            try (java.sql.PreparedStatement check = admin.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                check.setString(1, database);
                try (java.sql.ResultSet row = check.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(0, row.getInt(1), "owned scratch must be absent");
                    assertFalse(row.next());
                }
            }
            scratchCreated = false;
        }
    }
}

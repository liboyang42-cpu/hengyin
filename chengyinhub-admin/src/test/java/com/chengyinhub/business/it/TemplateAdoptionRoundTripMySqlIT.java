package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.dto.TemplateAdoptionCommand;
import com.chengyinhub.business.service.support.TemplateAdoptionService;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static TemplateAdoptionService adoption;
    private static ICapabilityGuard capabilities;
    private static IAuditTaskService audit;
    private static TemplatePublishServiceImpl publishTarget;
    private static CmsMemberTemplateServiceImpl memberServiceTarget;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll static void start() throws Exception {
        assertEquals("127.0.0.1", MybatisItDatabase.host());
        String scratchUrl = MybatisItDatabase.url("template_adoption_roundtrip");
        scratchCreated = true;
        source = new PooledDataSource("com.mysql.cj.jdbc.Driver", scratchUrl, MybatisItDatabase.user(), MybatisItDatabase.password());
        try (Connection c = source.getConnection()) {
            ItSchema.apply(c, "it-schema/journey-full-flow-it-schema.sql");
            ItSchema.applyMigrations(c, "sql/migration_preset_draft_source_20261108.sql",
                    "sql/migration_20261012_app_media_asset.sql",
                    "sql/migration_20261012_app_media_asset_namespaces.sql", "sql/migration_20261109_app_media_provenance.sql");
        }
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE cms_template_library LIKE cms_member_template");
        db.execute("ALTER TABLE cms_template_library ADD member_template_id BIGINT, ADD pack_type INT");
        db.execute("CREATE TABLE audit_task (id BIGINT PRIMARY KEY, snapshot_json LONGTEXT)");
        db.execute("CREATE TABLE sys_category (id BIGINT PRIMARY KEY, category_name VARCHAR(64), icon VARCHAR(255), status INT, del_flag INT)");
        db.update("INSERT INTO sys_category VALUES (12,'采用热度',NULL,1,0)");
        try (Connection c = source.getConnection()) {
            ItSchema.applyMigrations(c, "sql/migration_20261027_template_adoption_receipt.sql",
                    "sql/migration_20261107_template_adoption_inherited_config_hash.sql",
                    "sql/migration_20261112_template_market_status.sql",
                    "sql/migration_20261112_template_usage_count.sql");
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain,com.chengyinhub.business.domain.vo");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsMemberTemplateMapper.xml"), new ClassPathResource("mapper/business/CmsTemplateLibraryMapper.xml"), new ClassPathResource("mapper/business/AppMediaAssetMapper.xml"), new ClassPathResource("mapper/business/TemplateAdoptionReceiptMapper.xml"));
        sql = new SqlSessionTemplate(factory.getObject());
        members = sql.getMapper(CmsMemberTemplateMapper.class); libraries = sql.getMapper(CmsTemplateLibraryMapper.class);
        CmsMemberTemplateServiceImpl memberService = new CmsMemberTemplateServiceImpl(); ReflectionTestUtils.setField(memberService, "cmsMemberTemplateMapper", members);
        memberServiceTarget = memberService;
        CmsTemplateLibraryServiceImpl libraryService = new CmsTemplateLibraryServiceImpl(); ReflectionTestUtils.setField(libraryService, "cmsTemplateLibraryMapper", libraries);
        TemplatePublishServiceImpl target = new TemplatePublishServiceImpl();
        publishTarget = target;
        ReflectionTestUtils.setField(target, "memberTemplateService", memberService); ReflectionTestUtils.setField(target, "memberTemplateMapper", members);
        ReflectionTestUtils.setField(target, "templateLibraryService", libraryService); ReflectionTestUtils.setField(target, "libraryMapper", libraries);
        audit = mock(IAuditTaskService.class);
        ReflectionTestUtils.setField(target, "auditTaskService", audit);
        capabilities = mock(ICapabilityGuard.class);
        ReflectionTestUtils.setField(target, "auditTaskMapper", mock(AuditTaskMapper.class)); ReflectionTestUtils.setField(target, "capabilityGuard", capabilities);
        ReflectionTestUtils.setField(target, "mediaPublicationGuard", MediaPublicationMySqlFixture.guard(sql, members));
        ProxyFactory proxy = new ProxyFactory(target); proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        publish = (ITemplatePublishService) proxy.getProxy();
        adoption = new TemplateAdoptionService(sql.getMapper(TemplateAdoptionReceiptMapper.class), publish,
                new DataSourceTransactionManager(source));
        ReflectionTestUtils.setField(target, "adoptionService", adoption);
    }
    @AfterAll static void close() throws Exception {
        try { if (source != null) source.forceCloseAll(); }
        finally { dropScratch(); }
    }

    private static final String SCAN_QA="{\"schemaVersion\":1,\"present\":\"inline\",\"scan\":{\"enabled\":true,\"kind\":\"TEXT\",\"reply\":\"欢迎\"},\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"答案\",\"answerText\":\"yes\"}}";
    @BeforeEach void clean() { reset(capabilities, audit); ReflectionTestUtils.setField(publishTarget, "libraryMapper", libraries); ReflectionTestUtils.setField(publishTarget, "memberTemplateMapper", members); ReflectionTestUtils.setField(memberServiceTarget, "cmsMemberTemplateMapper", members); db.update("DELETE FROM template_adoption_receipt"); db.update("DELETE FROM cms_template_library"); db.update("DELETE FROM cms_member_template"); }

    @Test void firstAdoptedDraftCountsBeforePublicationAndReplayOrReadoptionNeverInflates() {
        CmsTemplateLibrary original = adoptionSource(false);
        TemplateAdoptionCommand command = adoptionCommand(original.getId(), 7L, "d89_first_draft_01");
        Map<String, Object> completed = adoption.adopt(7L, 7L, command, "adopter");
        Long draftId = Long.valueOf((String) completed.get("draftId"));
        CmsMemberTemplate draft = members.selectCmsMemberTemplateById(draftId);
        assertEquals(0, draft.getDraftStatus());
        assertEquals(0L, draft.getStatus());
        assertEquals(0L, draft.getPublishStatus());
        assertEquals(1, useNum(original.getId()), "采用草稿创建即计数，不等待发布");
        CmsTemplatePublishRequest edit = new CmsTemplatePublishRequest();
        BeanUtils.copyProperties(draft, edit, "duration");
        edit.setTitle("我的采用副本");
        assertEquals(draftId, publish.saveOrUpdateDraft(edit, 7L, "adopter"));
        assertEquals(draftId, publish.publishTemplateWithTransaction(edit, 7L, "adopter"));
        assertEquals(1, useNum(original.getId()), "编辑、发布同一副本都不重复计数");
        db.update("UPDATE cms_template_library SET status=3 WHERE id=?", original.getId());
        assertEquals(draftId.toString(), adoption.adopt(7L, 7L, command, "lost-ack").get("draftId"));
        assertEquals(1, adoptionCount(original.getId(), 7L));
        assertEquals(1, useNum(original.getId()), "来源下架后完成回执仍复用，不计数");
        db.update("UPDATE cms_template_library SET status=1 WHERE id=?", original.getId());
        Long anotherCopy = publish.saveOrUpdateDraft(adoptRequest(original.getId()), 7L, "adopter");
        assertNotEquals(draftId, anotherCopy);
        assertEquals(1, useNum(original.getId()));
        publish.saveOrUpdateDraft(adoptRequest(original.getId()), 8L, "another-member");
        assertEquals(2, useNum(original.getId()), "另一个会员首次采用增加一次");
        db.update("UPDATE cms_member_template SET del_flag=2 WHERE member_id=7 AND original_template_id=?", original.getId());
        Long readopted = publish.saveOrUpdateDraft(adoptRequest(original.getId()), 7L, "readopter");
        assertNotEquals(draftId, readopted);
        assertEquals(2, useNum(original.getId()), "包括已删除历史副本在内，会员只计首次");
        assertEquals(draftId.toString(), adoption.adopt(7L, 7L, command, "replay-deleted").get("draftId"));
        assertEquals(3, adoptionCount(original.getId(), 7L));
        assertEquals(2, useNum(original.getId()));
    }

    @Test void concurrentSeededFirstDraftsLockSourceBeforeEitherInsertAndCountOnce() throws Exception {
        concurrentFirstAdoptions(false, false);
    }

    @Test void concurrentCanonicalPublishAndDraftShareCanonicalThenLibraryLockAndCountOnce() throws Exception {
        concurrentFirstAdoptions(true, true);
    }

    private void concurrentFirstAdoptions(boolean canonical, boolean publishFirst) throws Exception {
        CmsTemplateLibrary original = adoptionSource(canonical);
        CountDownLatch firstLocked = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        CountDownLatch secondTryingLock = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicInteger inserts = new AtomicInteger();
        CmsTemplateLibraryMapper lockingLibraries = mock(CmsTemplateLibraryMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(libraries));
        doAnswer(call -> {
            if (!canonical && !first.get()) secondTryingLock.countDown();
            CmsTemplateLibrary locked = libraries.selectByIdForUpdate(call.getArgument(0));
            if (first.compareAndSet(true, false)) {
                firstLocked.countDown();
                assertTrue(releaseFirst.await(10, TimeUnit.SECONDS), "首个事务必须获准释放来源锁");
            }
            return locked;
        }).when(lockingLibraries).selectByIdForUpdate(original.getId());
        ReflectionTestUtils.setField(publishTarget, "libraryMapper", lockingLibraries);
        CmsMemberTemplateMapper lockingMembers = mock(CmsMemberTemplateMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(members));
        doAnswer(call -> {
            inserts.incrementAndGet();
            return members.insertCmsMemberTemplate(call.getArgument(0));
        }).when(lockingMembers).insertCmsMemberTemplate(any(CmsMemberTemplate.class));
        ReflectionTestUtils.setField(memberServiceTarget, "cmsMemberTemplateMapper", lockingMembers);
        if (canonical) {
            doAnswer(call -> {
                if (!first.get()) secondTryingLock.countDown();
                return members.selectCmsMemberTemplateByIdsForUpdate(call.getArgument(0));
            }).when(lockingMembers).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(original.getMemberTemplateId()));
            ReflectionTestUtils.setField(publishTarget, "memberTemplateMapper", lockingMembers);
        }
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Long> a = workers.submit(() -> {
                CmsTemplatePublishRequest request = adoptRequest(original.getId());
                if (!publishFirst) return publish.saveOrUpdateDraft(request, 7L, "first");
                request.setAdoptSourceOnly(false); request.setTitle("直接提交采用副本");
                request.setValidationMethod(0); request.setQuestionAnswer("answer");
                return publish.publishTemplateWithTransaction(request, 7L, "first");
            });
            assertTrue(firstLocked.await(5, TimeUnit.SECONDS), "实际 mapper 已取得来源行锁");
            Future<Long> b = workers.submit(() -> publish.saveOrUpdateDraft(adoptRequest(original.getId()), 7L, "second"));
            assertTrue(secondTryingLock.await(5, TimeUnit.SECONDS), "第二个事务确已进入来源锁调用");
            assertThrows(TimeoutException.class, () -> b.get(200, TimeUnit.MILLISECONDS));
            assertEquals(0, inserts.get(), "实际个人 mapper 的 INSERT 不能先于来源锁");
            assertEquals(0, adoptionCount(original.getId(), 7L), "锁必须发生在个人副本 INSERT 之前");
            releaseFirst.countDown();
            Long aId = a.get(10, TimeUnit.SECONDS), bId = b.get(10, TimeUnit.SECONDS);
            assertNotEquals(aId, bId, "两个请求都创建自己的副本，不能靠吞掉一个请求伪造幂等");
            assertEquals(2, inserts.get());
            assertEquals(2, adoptionCount(original.getId(), 7L));
            assertEquals(1, useNum(original.getId()));
            publish.saveOrUpdateDraft(adoptRequest(original.getId()), 8L, "distinct-member");
            assertEquals(2, useNum(original.getId()));
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "并发采用线程必须结束，不能掩盖死锁");
        }
    }

    @Test void differentConcurrentReceiptKeysCreateTwoRealDraftsButCountSameMemberOnce() throws Exception {
        CmsTemplateLibrary original = adoptionSource(true);
        TemplateAdoptionCommand a = adoptionCommand(original.getId(), 7L, "d89_concurrent_receipt_a");
        TemplateAdoptionCommand b = adoptionCommand(original.getId(), 7L, "d89_concurrent_receipt_b");
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Map<String, Object>>> results = new ArrayList<>();
            for (TemplateAdoptionCommand command : Arrays.asList(a, b)) results.add(workers.submit(() -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                return adoption.adopt(7L, 7L, command, "concurrent-receipt");
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            Map<String, Object> first = results.get(0).get(15, TimeUnit.SECONDS);
            Map<String, Object> second = results.get(1).get(15, TimeUnit.SECONDS);
            assertEquals("COMPLETED", first.get("status")); assertEquals("COMPLETED", second.get("status"));
            assertNotEquals(first.get("draftId"), second.get("draftId"));
            assertEquals(2, adoptionCount(original.getId(), 7L));
            assertEquals(1, useNum(original.getId()));
            assertEquals(first.get("draftId"), adoption.adopt(7L, 7L, a, "replay-a").get("draftId"));
            assertEquals(second.get("draftId"), adoption.adopt(7L, 7L, b, "replay-b").get("draftId"));
            assertEquals(2, adoptionCount(original.getId(), 7L)); assertEquals(1, useNum(original.getId()));
        } finally {
            start.countDown(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "回执并发不能残留阻塞线程");
        }
    }

    @Test void marketAndCategoryListsUseAdoptionPopularityThenStableDescendingIdentity() {
        CmsTemplateLibrary older = adoptionSource(false), newer = adoptionSource(false);
        CmsTemplateLibrary zero = adoptionSource(false), newerZero = adoptionSource(false);
        publish.saveOrUpdateDraft(adoptRequest(older.getId()), 7L, "older-first");
        publish.saveOrUpdateDraft(adoptRequest(older.getId()), 8L, "older-second");
        publish.saveOrUpdateDraft(adoptRequest(newer.getId()), 7L, "newer-first");
        assertMarketOrder(Arrays.asList(older.getId(), newer.getId(), newerZero.getId(), zero.getId()));
        publish.saveOrUpdateDraft(adoptRequest(newer.getId()), 8L, "newer-second");
        assertMarketOrder(Arrays.asList(newer.getId(), older.getId(), newerZero.getId(), zero.getId()));
        db.update("UPDATE cms_template_library SET status=3 WHERE id=?", newer.getId());
        assertMarketOrder(Arrays.asList(older.getId(), newerZero.getId(), zero.getId()));
    }

    private void assertMarketOrder(List<Long> expected) {
        CmsTemplateLibrary query = new CmsTemplateLibrary(); query.setStatus(1L); query.setDelFlag(0);
        assertEquals(expected, libraries.selectCmsTemplateLibraryList(query).stream().map(CmsTemplateLibrary::getId).toList());
        assertEquals(expected, libraries.selectTemplatesByCategoryIds(Collections.singletonList(12L)).stream().map(row -> row.getId()).toList());
    }

    private CmsTemplateLibrary adoptionSource(boolean canonical) {
        CmsTemplateLibrary row = new CmsTemplateLibrary();
        row.setMemberId(99L); row.setTitle("采用计数来源"); row.setValidationMethod(0); row.setQuestionAnswer("answer");
        row.setCategoryId(12L); row.setActivityCategoryids("12"); row.setStatus(1L); row.setDelFlag(0); row.setUseNum(0);
        if (canonical) {
            CmsMemberTemplate original = new CmsMemberTemplate();
            original.setMemberId(99L); original.setTitle(row.getTitle()); original.setValidationMethod(0);
            original.setQuestionAnswer("answer"); original.setStatus(1L); original.setDelFlag(0);
            assertEquals(1, members.insertCmsMemberTemplate(original)); row.setMemberTemplateId(original.getId());
        }
        assertEquals(1, libraries.insertCmsTemplateLibrary(row)); return row;
    }

    private static CmsTemplatePublishRequest adoptRequest(Long sourceId) {
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setOriginalTemplateId(sourceId); request.setAdoptSourceOnly(true); return request;
    }

    private static TemplateAdoptionCommand adoptionCommand(Long sourceId, Long member, String key) {
        TemplateAdoptionCommand command = new TemplateAdoptionCommand();
        command.setOriginalTemplateId(sourceId.toString()); command.setScope(""); command.setRequestId(key);
        command.setSourceVersion((String) adoption.source(member, member, sourceId.toString()).get("sourceVersion"));
        return command;
    }

    private static int useNum(Long sourceId) {
        return db.queryForObject("SELECT IFNULL(use_num,0) FROM cms_template_library WHERE id=?", Integer.class, sourceId);
    }

    private static int adoptionCount(Long sourceId, Long member) {
        return db.queryForObject("SELECT COUNT(*) FROM cms_member_template WHERE original_template_id=? AND member_id=?", Integer.class, sourceId, member);
    }

    @Test void seededPublicAdoptionUsesRealReceiptAndDraftServiceWithoutInventingReviewApproval() throws Exception {
        // TEST-only copies of public IDs 50/53; generated IDs below are local fixture identities.
        String seed = java.nio.file.Files.readString(java.nio.file.Path.of("sql/migration_seed_merchant_game_a8_20260823.sql"));
        String[][] specs = {
                {"signature_dish_blitz", "招牌菜限时盲猜", "哪一项最像这家店今天的招牌主角?", "店员重点推荐的当日招牌", "菜单里最贵的一项", "颜色最鲜艳的一项", "名字最长的一项"},
                {"fitness_reaction_60", "60秒健身知识反应赛", "开始训练前 哪个做法更稳妥?", "先确认器械和身体状态再开始", "直接使用最大重量", "忽略教练和场馆提示", "不热身马上冲刺"}
        };
        when(capabilities.checkAdvancedMechanics(eq(7L), anyString())).thenReturn("当前身份未解锁博弈机制");
        Set<Long> draftIds = new HashSet<>();
        for (String[] spec : specs) {
            var config = java.util.regex.Pattern.compile("VALUES \\('" + spec[0] + "', '(.*?)', NULL\\);").matcher(seed);
            assertTrue(config.find(), "Use the actual authored seed config");
            int start = seed.indexOf("-- TEMPLATE " + spec[0] + "|");
            int end = seed.indexOf("-- TEMPLATE ", start + 1);
            String block = seed.substring(start, end < 0 ? seed.length() : end);
            for (int i = 1; i < spec.length; i++) assertTrue(block.contains("'" + spec[i] + "'"));
            var answer = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("'" + spec[6] + "'") + "\\s*,\\s*'([^']+)'").matcher(block);
            assertTrue(answer.find(), "Keep the authored answer, not a fixture-selected replacement");
            CmsTemplateLibrary original = new CmsTemplateLibrary();
            original.setMemberId(100000L); original.setTitle(spec[1]); original.setValidationMethod(3);
            original.setQuestionName(spec[2]); original.setQuestionA(spec[3]); original.setQuestionB(spec[4]);
            original.setQuestionC(spec[5]); original.setQuestionD(spec[6]); original.setCorrectAnswer(answer.group(1));
            original.setAdvancedConfigJson(config.group(1)); original.setCreateBy("r30_test_fixture");
            original.setStatus(1L); original.setDelFlag(0);
            assertEquals(1, libraries.insertCmsTemplateLibrary(original));
            TemplateAdoptionCommand command = new TemplateAdoptionCommand();
            command.setOriginalTemplateId(original.getId().toString()); command.setScope("");
            command.setRequestId("r30_seed_" + spec[0]);
            command.setSourceVersion((String) adoption.source(7L, 7L, command.getOriginalTemplateId()).get("sourceVersion"));
            var actualReceipts = sql.getMapper(TemplateAdoptionReceiptMapper.class);
            var failedFinish = mock(TemplateAdoptionReceiptMapper.class, org.mockito.AdditionalAnswers.delegatesTo(actualReceipts));
            doThrow(new IllegalStateException("R30 injected receipt finish failure")).when(failedFinish)
                    .finish(any(), eq(command.getRequestId()), eq("COMPLETED"), any(), any(), any(), any());
            var failedAdoption = new TemplateAdoptionService(failedFinish, publish, new DataSourceTransactionManager(source));
            int before = db.queryForObject("SELECT COUNT(*) FROM cms_member_template", Integer.class);
            assertThrows(IllegalStateException.class, () -> failedAdoption.adopt(7L, 7L, command, "r30_test_fixture"));
            assertEquals(before, db.queryForObject("SELECT COUNT(*) FROM cms_member_template", Integer.class), "Real draft insert and receipt completion must roll back together");
            assertEquals(0, useNum(original.getId()), "Receipt completion failure rolls back the first-draft counter too");
            assertEquals("PENDING", adoption.receipt(7L, 7L, "", command.getRequestId()).get("status"));
            Map<String, Object> adopted = adoption.adopt(7L, 7L, command, "r30_test_fixture");
            assertEquals("COMPLETED", adopted.get("status"));
            assertEquals(1, useNum(original.getId()), "Successful receipt retry counts its first draft once");
            Map<String, Object> receipt = adoption.receipt(7L, 7L, "", command.getRequestId());
            assertEquals(adopted.get("draftId"), receipt.get("draftId"));
            Long id = Long.valueOf((String) receipt.get("draftId")); assertTrue(draftIds.add(id));
            CmsMemberTemplate draft = members.selectCmsMemberTemplateById(id);
            assertTrue(adoption.matchesDraft(receipt, draft));
            assertEquals(7L, draft.getMemberId()); assertEquals(original.getId(), draft.getOriginalTemplateId());
            assertEquals(original.getCorrectAnswer(), draft.getCorrectAnswer()); assertEquals(spec[2], draft.getQuestionName());
            assertEquals(json.readTree(original.getAdvancedConfigJson()), json.readTree(draft.getAdvancedConfigJson()));
            CmsTemplatePublishRequest own = new CmsTemplatePublishRequest();
            BeanUtils.copyProperties(draft, own, "duration");
            own.setTitle(spec[1] + " R30 TEST");
            assertEquals(id, publish.saveOrUpdateDraft(own, 7L, "r30_test_fixture"));
            when(audit.submitOrRefreshTemplate(eq(id), eq(7L), anyString())).thenThrow(new IllegalStateException("R30 injected review handoff failure"));
            assertThrows(IllegalStateException.class, () -> publish.publishTemplateWithTransaction(own, 7L, "r30_test_fixture"));
            assertEquals(0, members.selectCmsMemberTemplateById(id).getDraftStatus(), "Failed submit must roll back the existing draft update");
            assertEquals("COMPLETED", adoption.receipt(7L, 7L, "", command.getRequestId()).get("status"));
            when(audit.submitOrRefreshTemplate(eq(id), eq(7L), anyString())).thenReturn(1L);
            assertEquals(id, publish.publishTemplateWithTransaction(own, 7L, "r30_test_fixture"));
            CmsMemberTemplate submitted = members.selectCmsMemberTemplateById(id);
            assertEquals(1, submitted.getDraftStatus()); assertEquals(0L, submitted.getStatus());
            assertEquals(0L, submitted.getPublishStatus(), "Submit is not review approval or runtime readiness");
            assertEquals(original.getCorrectAnswer(), submitted.getCorrectAnswer());
            assertEquals(json.readTree(original.getAdvancedConfigJson()), json.readTree(submitted.getAdvancedConfigJson()));
            assertEquals(spec[1], libraries.selectCmsTemplateLibraryById(original.getId()).getTitle());
            assertEquals(id.toString(), adoption.adopt(7L, 7L, command, "r30_test_fixture").get("draftId"));
            assertEquals(1, useNum(original.getId()), "Receipt replay after publishing cannot recount adoption");
        }
        assertEquals(2, draftIds.size());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM template_adoption_receipt WHERE status='COMPLETED' AND inherited_advanced_config_hash IS NOT NULL", Integer.class));
    }
    @Test void scanParticipationDoesNotForcePublishedMainGameplayFullscreen() throws Exception {
        CmsTemplatePublishRequest request=new CmsTemplatePublishRequest();request.setTitle("扫码答题");request.setValidationMethod(4);request.setAdvancedConfigJson(SCAN_QA);
        Long id=publish.publishTemplateWithTransaction(request,7L,"author");CmsMemberTemplate saved=members.selectCmsMemberTemplateById(id);
        assertEquals(4,saved.getValidationMethod());assertEquals(json.readTree(SCAN_QA),json.readTree(saved.getAdvancedConfigJson()));
        assertEquals("inline",com.chengyinhub.business.service.support.AdvancedGameConfigValidator.resolvePresent(json.readTree(saved.getAdvancedConfigJson())));
    }
    @Test void scanParticipationAdoptionPreservesMainPresentationAndScanReply() throws Exception {
        CmsTemplateLibrary original=new CmsTemplateLibrary();original.setMemberId(99L);original.setTitle("扫码问答");original.setValidationMethod(4);original.setAdvancedConfigJson(SCAN_QA);original.setStatus(1L);original.setDelFlag(0);libraries.insertCmsTemplateLibrary(original);
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
        original.setStatus(1L); original.setDelFlag(0); assertEquals(1, libraries.insertCmsTemplateLibrary(original));
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

    @Test void completeContentAdoptionAndOwnedEditKeepLocalSnapshotAndRealSourceIdentity() throws Exception {
        String image = MediaPublicationMySqlFixture.approvedImage(sql, 99L);
        CmsMemberTemplate canonical = new CmsMemberTemplate();
        canonical.setMemberId(99L); canonical.setTitle("完整原玩法"); canonical.setCategoryId(12L);
        canonical.setDescription("玩法说明"); canonical.setImgUrl(image); canonical.setPlayers("2-4");
        canonical.setUsageLocation("室内"); canonical.setRequiredMaterials("材料卡"); canonical.setDuration(8L);
        canonical.setDifficulty("中等"); canonical.setRuleInstructions("先找线索再答题");
        canonical.setMerchantGuide("店员核验说明");
        canonical.setValidationMethod(7); canonical.setInteractionType("hidden_menu");
        canonical.setSensorType("steps"); canonical.setSensorConfig("{\"targetSteps\":120,\"windowSec\":300}");
        canonical.setQuestionName("原题目"); canonical.setQuestionAnswer("原答案");
        canonical.setQuestionA("选项甲"); canonical.setQuestionB("选项乙");
        canonical.setQuestionC("选项丙"); canonical.setQuestionD("选项丁"); canonical.setCorrectAnswer("B");
        canonical.setQuestionImg(image); canonical.setQuestionOptionMediaJson("{\"A\":{\"img\":\"" + image + "\"}}");
        canonical.setFeedbackText("原反馈"); canonical.setMedalName("原奖章");
        canonical.setMedalImg(image); canonical.setMedalStyle("enamel");
        canonical.setRewardRulesJson("[{\"outcomeCode\":\"STEPS_PASS\",\"xp\":2,\"feedbackText\":\"成功反馈\"}]");
        canonical.setStoryText("原剧情"); canonical.setStoryImg(image);
        canonical.setStoryJson("[{\"text\":\"下一站\",\"tag\":\"引导\"}]");
        canonical.setHint1("提示一"); canonical.setHint2("提示二"); canonical.setAnswerReveal("原解析");
        canonical.setPhotoRequireDesc("拍到招牌"); canonical.setPhotoReview(1);
        canonical.setActivityCategoryids("12");
        canonical.setAdvancedConfigJson("{\"schemaVersion\":1,\"interactionRules\":{"
                + "\"resourceSlots\":[{\"key\":\"material\",\"kind\":\"ITEM\",\"label\":\"材料\",\"required\":true}],"
                + "\"resourceCosts\":[{\"itemId\":\"material\",\"quantity\":1}]}}");
        canonical.setAdvancedConfigJson(new com.chengyinhub.business.service.support.AdvancedGameConfigValidator()
                .validateAndNormalize(canonical.getAdvancedConfigJson()));
        canonical.setRewardRulesJson(new com.chengyinhub.business.service.support.RewardRulesValidator()
                .validateAndNormalize(canonical.getRewardRulesJson()));
        canonical.setStatus(1L); canonical.setCheckStatus(1); canonical.setDraftStatus(1);
        canonical.setPublishStatus(0L); canonical.setDelFlag(0);
        assertEquals(1, members.insertCmsMemberTemplate(canonical));
        CmsTemplateLibrary original = new CmsTemplateLibrary();
        BeanUtils.copyProperties(canonical, original, "id");
        original.setMemberTemplateId(canonical.getId()); original.setStatus(1L);
        assertEquals(1, libraries.insertCmsTemplateLibrary(original));
        CmsTemplateLibrary readProjection = libraries.selectCmsTemplateLibraryById(original.getId());
        assertEquals("hidden_menu", readProjection.getInteractionType());
        String marketHashContent = com.chengyinhub.business.service.support.TemplateAuditSnapshot.market(readProjection);
        assertEquals("hidden_menu", json.readTree(marketHashContent).path("interactionType").asText());

        CmsTemplatePublishRequest adopt = new CmsTemplatePublishRequest();
        adopt.setOriginalTemplateId(original.getId()); adopt.setAdoptSourceOnly(true);
        adopt.setClearFields(new HashSet<>(Arrays.asList("merchantGuide", "storyImg", "rewardRulesJson")));
        Long adoptedId = publish.saveOrUpdateDraft(adopt, 7L, "adopter");
        CmsMemberTemplate saved = members.selectCmsMemberTemplateById(adoptedId);
        assertEquals(7L, saved.getMemberId()); assertEquals(original.getId(), saved.getOriginalTemplateId());
        assertEquals("hidden_menu", saved.getInteractionType());
        for (String field : Arrays.asList("title", "categoryId", "description", "imgUrl", "players",
                "usageLocation", "requiredMaterials", "duration", "difficulty", "ruleInstructions",
                "merchantGuide", "validationMethod", "sensorType", "questionName", "questionAnswer",
                "questionA", "questionB", "questionC", "questionD", "correctAnswer", "questionImg",
                "feedbackText", "medalName", "medalImg", "medalStyle", "storyText", "storyImg",
                "hint1", "hint2", "answerReveal", "photoRequireDesc", "photoReview", "activityCategoryids")) {
            assertEquals(new org.springframework.beans.BeanWrapperImpl(canonical).getPropertyValue(field),
                    new org.springframework.beans.BeanWrapperImpl(saved).getPropertyValue(field), field);
        }
        for (String field : Arrays.asList("sensorConfig", "questionOptionMediaJson", "rewardRulesJson",
                "storyJson", "advancedConfigJson")) {
            assertEquals(json.readTree((String) new org.springframework.beans.BeanWrapperImpl(canonical).getPropertyValue(field)),
                    json.readTree((String) new org.springframework.beans.BeanWrapperImpl(saved).getPropertyValue(field)), field);
        }
        String captured = saved.getAdvancedConfigJson();
        db.update("UPDATE cms_template_library SET status=3,advanced_config_json=? WHERE id=?",
                "{\"schemaVersion\":1}", original.getId());
        db.update("UPDATE cms_member_template SET interaction_type='city_story_card',medal_name='来源新奖章' WHERE id=?",
                canonical.getId());
        CmsTemplatePublishRequest edit = new CmsTemplatePublishRequest();
        BeanUtils.copyProperties(saved, edit, "duration");
        edit.setDuration(Math.toIntExact(saved.getDuration())); edit.setTitle("仅改我的副本");
        edit.setOriginalTemplateId(null); // 客户端省略出身字段也不能把来源改成 0。
        assertEquals(adoptedId, publish.saveOrUpdateDraft(edit, 7L, "adopter"));
        CmsMemberTemplate after = members.selectCmsMemberTemplateById(adoptedId);
        assertEquals(original.getId(), after.getOriginalTemplateId());
        assertEquals(captured, after.getAdvancedConfigJson()); assertEquals("原奖章", after.getMedalName());
        assertEquals("hidden_menu", after.getInteractionType());
        assertEquals("来源新奖章", members.selectCmsMemberTemplateById(canonical.getId()).getMedalName());
        assertEquals(3L, libraries.selectCmsTemplateLibraryById(original.getId()).getStatus());
        assertThrows(RuntimeException.class, () -> publish.saveOrUpdateDraft(edit, 8L, "foreign"));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM cms_member_template", Integer.class));
    }

    @Test void preferenceAdoptionKeepsWholeGroupAndProjectionCannotBorrowAnotherOwnersInteraction() throws Exception {
        String preference = "{\"steps\":[{\"key\":\"pace\",\"type\":\"single\",\"title\":\"选一个\","
                + "\"options\":[{\"key\":\"A\",\"text\":\"慢\",\"scores\":{\"slow\":1}},"
                + "{\"key\":\"B\",\"text\":\"稳\",\"scores\":{\"slow\":2}}]}],\"dimensions\":[\"slow\"],"
                + "\"results\":{\"slow\":{\"title\":\"慢生活\",\"body\":\"因为你选择了{{choices}}\","
                + "\"nextStep\":\"散步\",\"nextStepDays\":7}}}";
        CmsMemberTemplate canonical = new CmsMemberTemplate(); canonical.setMemberId(99L);
        canonical.setTitle("偏好内容"); canonical.setValidationMethod(6); canonical.setInteractionType("city_story_card");
        canonical.setStatus(1L); canonical.setDelFlag(0); assertEquals(1, members.insertCmsMemberTemplate(canonical));
        CmsTemplateLibrary sourceRow = new CmsTemplateLibrary(); sourceRow.setMemberId(99L);
        sourceRow.setMemberTemplateId(canonical.getId()); sourceRow.setTitle("偏好内容");
        sourceRow.setValidationMethod(6); sourceRow.setPreferenceJson(preference);
        sourceRow.setStatus(1L); sourceRow.setDelFlag(0);
        assertEquals(1, libraries.insertCmsTemplateLibrary(sourceRow));
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setOriginalTemplateId(sourceRow.getId()); request.setAdoptSourceOnly(true);
        Long adoptedId = publish.saveOrUpdateDraft(request, 7L, "adopter");
        assertEquals(json.readTree(preference), json.readTree(members.selectCmsMemberTemplateById(adoptedId).getPreferenceJson()));
        db.update("UPDATE cms_template_library SET member_id=98 WHERE id=?", sourceRow.getId());
        assertNull(libraries.selectCmsTemplateLibraryById(sourceRow.getId()).getInteractionType());
        CmsTemplatePublishRequest conflicting = new CmsTemplatePublishRequest();
        conflicting.setOriginalTemplateId(sourceRow.getId()); conflicting.setAdoptSourceOnly(true);
        assertThrows(IllegalArgumentException.class, () -> publish.saveOrUpdateDraft(conflicting, 7L, "adopter"));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM cms_member_template", Integer.class));
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

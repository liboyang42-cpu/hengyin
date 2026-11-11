package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.RegistrationEntitlementMapper;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMerchantOfferService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.impl.CmsRegistrationMerchantServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.MerchantChapterApplicationServiceImpl;
import com.chengyinhub.business.service.impl.MerchantOfferServiceImpl;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import com.chengyinhub.business.service.support.TopicContentWriteGuard;
import com.chengyinhub.business.service.support.TopicPublicationContentGuard;
import com.chengyinhub.business.service.support.TopicSupplyReviewService;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiTopicController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AC1 · P0-23「需补供给恢复」的后端半边(P0-14 {@link P0FreeExploreSupplyGateMySqlIT} 的续篇):
 * 售票中的自由探索主题,必选章唯一商家退出 → 主题停售进入「需补供给」;只有主办批准的补位商家
 * 真实接续并生效后,主办本人经 {@code /api/topic/update_user_status} 才能恢复在售。
 *
 * <p>驱动方式:生产 {@link MerchantOfferServiceImpl#withdrawOwnChapterOffer}/{@link MerchantOfferServiceImpl#enroll}、
 * {@link CmsTopicServiceImpl#casUserStatus}(含 {@link TopicPublicationContentGuard} 的恢复判定)与
 * {@link ApiTopicController#topicUpdateUserStatus}(归属校验),真 mapper XML + 真 MySQL 8 + TransactionInterceptor 代理。
 * 补位供给 0→1 走生产 SQL {@code MerchantChapterOfferMapper.approveReplacement}(点位审核调用的同一条)。
 *
 * <p>Mockito 只用于非存储边界:登录身份({@code getAppUserId})、主办在架额度闸(原样执行写动作)、
 * 广场帖可见性联动、提交后通知。不覆盖:点位审核全链(ChapterMerchantNode 单测)与真 HTTP。
 *
 * <p>Schema 只来自 {@code sql/base_schema_20260818.sql} 与既有 {@code migration_*.sql}(同
 * {@link FreeExploreNodeSupplyMySqlIT} 的截取口径),一类一库,跑完删库并回读。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P0FreeExploreSupplyRecoveryMySqlIT {
    private static final String SUITE = "p0_free_supply_recovery";

    private static final long HOST = 7100L, ORIGINAL_MERCHANT = 7201L, REPLACEMENT_MERCHANT = 7202L,
            UNLINKED_MERCHANT = 7203L, STRANGER = 7300L;
    private static final long TOPIC = 8300L, REQUIRED_CHAPTER = 8310L, OPTIONAL_CHAPTER = 8320L;
    private static final long ORIGINAL_OFFER = 6301L, OPTIONAL_OFFER = 6302L;
    private static final String METHODS = "1,2";
    private static final int MAX_XP = 5;
    private static final String NEEDS_SUPPLY = "必选章节有商家退出，须替补商家确认有效供给后由主办恢复；无法履约请取消退款";

    private static final List<String> TABLES = List.of("cms_topic", "cms_topic_chapter", "cms_topic_node",
            "merchant_chapter_offer", "merchant_chapter_application", "cms_registration_merchant", "audit_task");
    private static final Map<String, List<String>> MIGRATIONS = new LinkedHashMap<>();
    static {
        MIGRATIONS.put("cms_topic", List.of(
                "migration_20260815_tandianri_roam_referral.sql",
                "migration_20260820_new_city_setup_plan.sql",
                "migration_20260821_topic_beta_zone.sql",
                "migration_20260821_topic_team_config.sql",
                "migration_circle_theme_batch2_20260821.sql",
                "migration_circle_theme_primary_schema_20260821.sql",
                "migration_20260823_game_session_runtime.sql",
                "migration_20260823_topic_route_graph.sql",
                "migration_20260823_topic_route_published_snapshot.sql",
                "migration_club_topic_display_settings_20260906.sql",
                "migration_topic_journey_rules_20260916.sql",
                "migration_topic_journey_story_20260916.sql",
                "migration_theme_runtime_20261004.sql",
                "migration_audit_level_20261020.sql",
                "migration_content_safety_hold_20261020.sql",
                "migration_o14_cms_topic_city_code_20261022.sql",
                "migration_topic_location_contract_20261107.sql"));
        MIGRATIONS.put("cms_topic_chapter", List.of(
                "migration_20260820_new_city_setup_plan.sql",
                "migration_20260820_questo_puzzle_atmosphere.sql",
                "migration_circle_theme_batch2_20260821.sql",
                "migration_x10_chapter_audio_20260905.sql",
                "migration_topic_chapter_finish_time_20260910.sql",
                "migration_20261105_chapter_video.sql"));
        MIGRATIONS.put("cms_topic_node", List.of("migration_node_unlock_after_20260820.sql"));
        MIGRATIONS.put("merchant_chapter_offer", List.of(
                "migration_20261003_merchant_offer_withdrawal_columns.sql",
                "migration_club_direct_merchant_receivable_20261004.sql"));
        MIGRATIONS.put("merchant_chapter_application", List.of(
                "migration_20261003_merchant_offer_withdrawal_columns.sql",
                "migration_20261024_d_chapter_application_round.sql"));
        MIGRATIONS.put("cms_registration_merchant", List.of("migration_registration_merchant_audit_time_20260910.sql"));
    }

    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private SqlSessionTemplate sql;
    private DataSourceTransactionManager tx;

    private MerchantChapterOfferMapper offerMapper;
    private IMerchantOfferService offerService;
    private ICmsTopicService topicService;
    private IUmsCreativeSquareService square;
    private ApiTopicController controller;
    private Long actor;

    @BeforeAll
    void createSchema() throws Exception {
        assertFalse(ForbiddenDbHosts.isForbidden(MybatisItDatabase.host(), System.getenv()),
                "IT 只许连本地/CI 专用库,禁止连生产库主机");
        source = P0AcceptanceSchema.dataSource(SUITE);
        db = new JdbcTemplate(source);
        assertEquals(MybatisItDatabase.databaseName(SUITE), db.queryForObject("select database()", String.class));
        assertTrue(db.queryForObject("select version()", String.class).startsWith("8."), "须 MySQL 8");
        P0AcceptanceSchema.baseTables(db, TABLES.toArray(new String[0]));
        for (Map.Entry<String, List<String>> table : MIGRATIONS.entrySet()) {
            for (String migration : table.getValue()) {
                String script = new String(Files.readAllBytes(P0AcceptanceSchema.repoRoot()
                        .resolve("chengyinhub-admin/sql").resolve(migration)), StandardCharsets.UTF_8);
                List<String> alters = FreeExploreNodeSupplyMySqlIT.alterStatements(script, table.getKey());
                assertFalse(alters.isEmpty(), migration + " 里找不到对 " + table.getKey() + " 的 ALTER");
                for (String alter : alters) db.execute(alter);
            }
        }
        // 上架 CAS 依赖的取消轮次游标与回执表(整文件原样执行)。
        P0AcceptanceSchema.migrations(source, "migration_20261029_topic_cancellation_receipt.sql");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfigLocation(new ClassPathResource("mybatis/mybatis-config.xml"));
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain,com.chengyinhub.system.domain");
        List<Resource> xml = new ArrayList<>();
        for (String name : List.of("CmsTopicMapper", "CmsTopicChapterMapper", "CmsTopicNodeMapper",
                "MerchantChapterOfferMapper", "MerchantChapterApplicationMapper", "CmsRegistrationMerchantMapper",
                "AuditTaskMapper", "AuditRecordMapper")) {
            xml.add(new ClassPathResource("mapper/business/" + name + ".xml"));
        }
        factory.setMapperLocations(xml.toArray(new Resource[0]));
        sql = new SqlSessionTemplate(factory.getObject());
        tx = new DataSourceTransactionManager(source);
    }

    @AfterAll
    void dropSchema() {
        if (db != null) P0AcceptanceSchema.drop(db, SUITE);
    }

    @BeforeEach
    void seedAndWire() {
        for (String table : List.of("cms_topic_cancellation_receipt", "cms_topic", "cms_topic_chapter", "cms_topic_node",
                "merchant_chapter_offer", "merchant_chapter_application", "cms_registration_merchant", "audit_task")) {
            db.update("delete from " + table);
        }
        // 售票中(lifecycle=3)、已过审(status=1)、在售(user_status=1)的普通自由探索主题。
        db.update("insert into cms_topic(id,member_id,name,product_type,lifecycle,status,user_status,is_draft,del_flag,"
                + "check_status,recruit_deadline,start_date,create_time) values(?,?,'城东自由探索',2,3,1,1,0,0,0,"
                + "date_add(now(),interval 1 day),date_add(now(),interval 14 day),now())", TOPIC, HOST);
        db.update("insert into cms_topic_chapter(id,topic_id,name,sort_id,required,terms_mode,max_merchant,recruit_enabled,"
                + "allowed_validation_methods,max_node_xp) values(?,?,'必选章',1,1,'TRAFFIC',4,0,?,?),"
                + "(?,?,'可选章',2,0,'TRAFFIC',4,0,?,?)",
                REQUIRED_CHAPTER, TOPIC, METHODS, MAX_XP, OPTIONAL_CHAPTER, TOPIC, METHODS, MAX_XP);
        offer(ORIGINAL_OFFER, REQUIRED_CHAPTER, ORIGINAL_MERCHANT);
        offer(OPTIONAL_OFFER, OPTIONAL_CHAPTER, UNLINKED_MERCHANT);
        // 中标报名(recruit_enabled=0 章节的入驻凭证):原商家、补位商家、未关联商家。
        db.update("insert into cms_registration_merchant(id,member_id,topic_id,mode,audit_status,del_flag) values"
                + "(1,?,?,1,1,0),(2,?,?,1,1,0),(3,?,?,1,1,0)",
                ORIGINAL_MERCHANT, TOPIC, REPLACEMENT_MERCHANT, TOPIC, UNLINKED_MERCHANT, TOPIC);
        db.update("insert into merchant_chapter_application(id,topic_id,chapter_id,merchant_id,status,source,del_flag)"
                + " values(1,?,?,?,1,0,0)", TOPIC, REQUIRED_CHAPTER, ORIGINAL_MERCHANT);
        wire();
        actor = HOST;
    }

    // ===================================================================== 正路径

    @Test
    void confirmedReplacementLetsOnlyTheHostRestoreSalesAndPriceGate() {
        assertEquals(0, offerMapper.countRequiredChaptersWithoutLiveOffer(TOPIC), "开局:必选章有生效供给");
        enterNeedsSupply();

        // 审核/自动上线路径永不自动恢复;主办显式恢复在补位前被拒,主题仍停售。
        assertEquals("商家退出后的报名恢复须由主办显式操作，审核不会自动恢复",
                topicService.topicSupplyAutoOnlineBlockReason(TOPIC));
        assertRestoreRefused(NEEDS_SUPPLY);

        // 主办批准补位邀请 → 补位商家经生产入驻接续原供给,先落待生效(status=0)。
        approveReplacementInvitation(REPLACEMENT_MERCHANT);
        Long replacement = offerService.enroll(trafficOffer(), REPLACEMENT_MERCHANT);
        Map<String, Object> pending = offerRow(replacement);
        assertEquals(0, num(pending.get("status")), "补位供给经点位与条款重审后才生效");
        assertEquals(ORIGINAL_OFFER, num(pending.get("replacement_of_offer_id")).longValue(), "关联只来自主办批准的申请");
        assertEquals(METHODS, pending.get("allowed_validation_methods"), "玩法边界沿用原供给");
        assertEquals(MAX_XP, num(pending.get("max_node_xp")));
        assertRestoreRefused(NEEDS_SUPPLY);   // 待生效补位不算真实供给

        assertEquals(1, offerMapper.approveReplacement(replacement, REPLACEMENT_MERCHANT), "补位供给 0→1");
        assertEquals(0, offerMapper.countRequiredChaptersWithoutLiveOffer(TOPIC), "锁价/开售的供给闸重新放行");
        long epoch = epoch();

        AjaxResult restored = controller.topicUpdateUserStatus(String.valueOf(TOPIC), "0", null);
        assertEquals("上架成功", msg(restored));
        assertEquals(1, userStatus(), "主题恢复在售");
        assertEquals(epoch + 1, epoch(), "恢复推进售卖轮次");
        Map<String, Object> original = offerRow(ORIGINAL_OFFER);
        assertEquals(0, num(original.get("status")), "原退出供给保持失效,历史不被抹掉");
        assertEquals("停止营业", original.get("withdraw_reason"));
        assertEquals(1, num(offerRow(replacement).get("status")));
        verify(square).syncTopicPostVisibility(TOPIC, true);
    }

    // ===================================================================== 反例:没有真实供给

    @Test
    void unlinkedLiveSupplyOrExpiredReplacementDoesNotRecover() {
        enterNeedsSupply();
        // 锁价后未经补位邀请的新商家不能入驻;即便库里另有一家未关联的生效供给,也不算接续。
        IllegalStateException locked = assertThrows(IllegalStateException.class,
                () -> offerService.enroll(trafficOffer(), UNLINKED_MERCHANT));
        assertEquals("主题已锁价,不能再入驻", locked.getMessage());
        db.update("insert into merchant_chapter_offer(id,topic_id,chapter_id,merchant_id,terms_mode,quota_total,quota_used,"
                + "allowed_validation_methods,max_node_xp,status,create_time) values(6309,?,?,?,'TRAFFIC',0,0,?,?,1,now())",
                TOPIC, REQUIRED_CHAPTER, UNLINKED_MERCHANT, METHODS, MAX_XP);
        assertEquals(0, offerMapper.countRequiredChaptersWithoutLiveOffer(TOPIC));
        assertRestoreRefused(NEEDS_SUPPLY);
        db.update("delete from merchant_chapter_offer where id=6309");

        // 补位已生效但随后过期:同样不能恢复。
        approveReplacementInvitation(REPLACEMENT_MERCHANT);
        Long replacement = offerService.enroll(trafficOffer(), REPLACEMENT_MERCHANT);
        assertEquals(1, offerMapper.approveReplacement(replacement, REPLACEMENT_MERCHANT));
        db.update("update merchant_chapter_offer set valid_end=now() - interval 1 minute where id=?", replacement);
        // 原供给无期限,已过期的补位既短于原供给、也不再生效:按接续条款先拒。
        assertRestoreRefused("补位供给有效期不能短于原供给");
        assertEquals(1, offerMapper.countRequiredChaptersWithoutLiveOffer(TOPIC), "过期补位不算生效供给");
        verify(square, never()).syncTopicPostVisibility(anyLong(), eq(true));
    }

    // ===================================================================== 反例:归属

    @Test
    void anotherOwnerOrTheReplacementMerchantCannotRestore() {
        enterNeedsSupply();
        approveReplacementInvitation(REPLACEMENT_MERCHANT);
        Long replacement = offerService.enroll(trafficOffer(), REPLACEMENT_MERCHANT);
        assertEquals(1, offerMapper.approveReplacement(replacement, REPLACEMENT_MERCHANT));
        List<Map<String, Object>> topicBefore = db.queryForList("select * from cms_topic");
        List<Map<String, Object>> offersBefore = db.queryForList("select * from merchant_chapter_offer order by id");

        for (Long other : List.of(STRANGER, REPLACEMENT_MERCHANT)) {
            actor = other;
            assertEquals("无权操作该主题", msg(controller.topicUpdateUserStatus(String.valueOf(TOPIC), "0", null)));
        }
        assertEquals(topicBefore, db.queryForList("select * from cms_topic"), "主题行逐列不变");
        assertEquals(offersBefore, db.queryForList("select * from merchant_chapter_offer order by id"));
        verify(square, never()).syncTopicPostVisibility(anyLong(), anyBoolean());

        actor = HOST;
        assertEquals("上架成功", msg(controller.topicUpdateUserStatus(String.valueOf(TOPIC), "0", null)));
        assertEquals(1, userStatus());
    }

    // ===================================================================== 幂等

    @Test
    void repeatedRestoreAndReplayedReplacementAreNoOps() {
        enterNeedsSupply();
        approveReplacementInvitation(REPLACEMENT_MERCHANT);
        Long replacement = offerService.enroll(trafficOffer(), REPLACEMENT_MERCHANT);
        assertEquals(1, offerMapper.approveReplacement(replacement, REPLACEMENT_MERCHANT));
        assertEquals("上架成功", msg(controller.topicUpdateUserStatus(String.valueOf(TOPIC), "0", null)));
        long epoch = epoch();
        List<Map<String, Object>> offersBefore = db.queryForList("select * from merchant_chapter_offer order by id");

        // 客户端重放同一次「恢复」:CAS 不翻转为下架,也不再推进轮次。
        assertEquals("主题当前是「已上架」，与你点击时看到的不一致，请刷新后重试",
                msg(controller.topicUpdateUserStatus(String.valueOf(TOPIC), "0", null)));
        assertEquals(0, topicService.casUserStatus(TOPIC, 0, 1), "服务层重复恢复命中 0 行");
        // 补位不可重放:第二条补位入驻与第二次生效都不落库。
        IllegalStateException replay = assertThrows(IllegalStateException.class,
                () -> offerService.enroll(trafficOffer(), REPLACEMENT_MERCHANT));
        assertEquals("原退出供给或补位邀请已变化，请刷新后确认", replay.getMessage());
        assertEquals(0, offerMapper.approveReplacement(replacement, REPLACEMENT_MERCHANT));

        assertEquals(1, userStatus());
        assertEquals(epoch, epoch());
        assertEquals(offersBefore, db.queryForList("select * from merchant_chapter_offer order by id"));
        verify(square, times(1)).syncTopicPostVisibility(TOPIC, true);
    }

    // ===================================================================== 夹具

    /** 必选章唯一商家经生产退出 → 主题停售,供给闸重新拦锁价/开售。 */
    private void enterNeedsSupply() {
        assertTrue(offerService.withdrawOwnChapterOffer(ORIGINAL_OFFER, ORIGINAL_MERCHANT, " 停止营业 "), "必选章退出");
        assertEquals(0, userStatus(), "必选章退出即停售");
        Map<String, Object> original = offerRow(ORIGINAL_OFFER);
        assertEquals(0, num(original.get("status")));
        assertEquals("停止营业", original.get("withdraw_reason"));
        assertEquals(Boolean.TRUE, original.get("withdraw_required"), "必选快照落库");
        assertEquals(1, offerMapper.countRequiredChaptersWithoutLiveOffer(TOPIC), "需补供给");
        reset(square);
    }

    private void assertRestoreRefused(String reason) {
        long epoch = epoch();
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> controller.topicUpdateUserStatus(String.valueOf(TOPIC), "0", null));
        assertEquals(reason, refused.getMessage());
        assertEquals(0, userStatus(), "未恢复");
        assertEquals(epoch, epoch(), "拒绝不推进售卖轮次");
    }

    /** 主办批准补位邀请(会员侧审批入口另有覆盖;这里只落其结果行)。 */
    private void approveReplacementInvitation(long merchant) {
        db.update("insert into merchant_chapter_application(topic_id,chapter_id,merchant_id,status,source,del_flag,"
                + "replacement_of_offer_id) values(?,?,?,1,0,0,?)", TOPIC, REQUIRED_CHAPTER, merchant, ORIGINAL_OFFER);
    }

    private static MerchantChapterOffer trafficOffer() {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(REQUIRED_CHAPTER);
        o.setTermsMode(TermsMode.TRAFFIC);
        return o;
    }

    private void offer(long id, long chapter, long merchant) {
        db.update("insert into merchant_chapter_offer(id,topic_id,chapter_id,merchant_id,terms_mode,quota_total,quota_used,"
                + "allowed_validation_methods,max_node_xp,status,create_time) values(?,?,?,?,'TRAFFIC',0,0,?,?,1,now())",
                id, TOPIC, chapter, merchant, METHODS, MAX_XP);
    }

    private void wire() {
        CmsTopicMapper topicMapper = sql.getMapper(CmsTopicMapper.class);
        CmsTopicChapterMapper chapterMapper = sql.getMapper(CmsTopicChapterMapper.class);
        offerMapper = sql.getMapper(MerchantChapterOfferMapper.class);
        MerchantChapterApplicationMapper applicationMapper = sql.getMapper(MerchantChapterApplicationMapper.class);
        square = mock(IUmsCreativeSquareService.class);

        TopicContentWriteGuard writeGuard = new TopicContentWriteGuard(topicMapper, chapterMapper, null, null, null);
        ReflectionTestUtils.setField(writeGuard, "cmsTopicNodeMapper", sql.getMapper(CmsTopicNodeMapper.class));
        TopicPublicationContentGuard guard = new TopicPublicationContentGuard();
        ReflectionTestUtils.setField(guard, "contentWriteGuard", writeGuard);
        ReflectionTestUtils.setField(guard, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(guard, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(guard, "merchantChapterOfferMapper", offerMapper);
        CmsTopicServiceImpl topics = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(topics, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(topics, "publicationContentGuard", guard);
        ReflectionTestUtils.setField(topics, "umsCreativeSquareService", square);
        ReflectionTestUtils.setField(topics, "transactionManager", tx);
        topicService = transactional(topics, ICmsTopicService.class);

        TopicSupplyReviewService supplyReview = new TopicSupplyReviewService(sql.getMapper(AuditTaskMapper.class),
                sql.getMapper(AuditRecordMapper.class), topicMapper, chapterMapper);
        MerchantChapterApplicationServiceImpl applications = new MerchantChapterApplicationServiceImpl();
        ReflectionTestUtils.setField(applications, "applicationMapper", applicationMapper);
        CmsRegistrationMerchantServiceImpl wonBids = new CmsRegistrationMerchantServiceImpl();
        ReflectionTestUtils.setField(wonBids, "cmsRegistrationMerchantMapper", sql.getMapper(CmsRegistrationMerchantMapper.class));
        com.chengyinhub.business.service.ISystemNotificationIntentService ownerIntents =
                mock(com.chengyinhub.business.service.ISystemNotificationIntentService.class);
        when(ownerIntents.findIntentId(anyString())).thenReturn(null);
        when(ownerIntents.enqueue(anyLong(), anyInt(), anyString(), nullable(String.class), anyString())).thenReturn(9001L);
        MerchantOfferServiceImpl offers = new MerchantOfferServiceImpl();
        ReflectionTestUtils.setField(offers, "supplyReviewService", supplyReview);
        ReflectionTestUtils.setField(offers, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(offers, "registrationEntitlementMapper", mock(RegistrationEntitlementMapper.class));
        ReflectionTestUtils.setField(offers, "businessNotice", mock(CommittedBusinessNotice.class));
        ReflectionTestUtils.setField(offers, "notifyService", mock(INotifyService.class));
        ReflectionTestUtils.setField(offers, "systemNotificationIntents", ownerIntents);
        ReflectionTestUtils.setField(offers, "imService", mock(com.chengyinhub.business.service.IImService.class));
        ReflectionTestUtils.setField(offers, "ownerNoticeWxChannel", mock(com.chengyinhub.business.notify.WxSubscribeChannel.class));
        ReflectionTestUtils.setField(offers, "ownerNoticeExecutor", mock(java.util.concurrent.ScheduledExecutorService.class));
        ReflectionTestUtils.setField(offers, "applicationMapper", applicationMapper);
        ReflectionTestUtils.setField(offers, "cmsTopicService", topicService);   // 真主题服务:退出停售走真 CAS
        ReflectionTestUtils.setField(offers, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(offers, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(offers, "registrationMerchantService", wonBids);
        ReflectionTestUtils.setField(offers, "merchantChapterApplicationService", applications);
        offerService = transactional(offers, IMerchantOfferService.class);

        controller = spy(new ApiTopicController());
        doAnswer(call -> actor).when(controller).getAppUserId();
        IQuotaService quota = mock(IQuotaService.class);
        doAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(2)).get())
                .when(quota).runWithThemeOnlineGuard(anyLong(), any(), any());
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "quotaService", quota);
        ReflectionTestUtils.setField(controller, "clubAccessService",
                mock(com.chengyinhub.business.access.club.IClubAccessService.class));
    }

    private <T> T transactional(T target, Class<T> api) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(api);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return api.cast(proxy.getProxy());
    }

    private int userStatus() {
        return db.queryForObject("select user_status from cms_topic where id=?", Integer.class, TOPIC);
    }

    private long epoch() {
        return db.queryForObject("select cancellation_epoch from cms_topic where id=?", Long.class, TOPIC);
    }

    private Map<String, Object> offerRow(long id) {
        return db.queryForMap("select * from merchant_chapter_offer where id=?", id);
    }

    private static Integer num(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static String msg(AjaxResult result) {
        return String.valueOf(result.get(AjaxResult.MSG_TAG));
    }
}

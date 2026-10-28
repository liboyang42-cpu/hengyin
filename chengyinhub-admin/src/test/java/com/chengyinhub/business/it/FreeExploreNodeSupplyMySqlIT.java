package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MerchantCircleSupplyProfileMapper;
import com.chengyinhub.business.mapper.RegistrationEntitlementMapper;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMerchantOfferService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.business.service.impl.ChapterMerchantNodeServiceImpl;
import com.chengyinhub.business.service.impl.CmsMemberTemplateServiceImpl;
import com.chengyinhub.business.service.impl.CmsRegistrationMerchantServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicChapterServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicNodeServiceImpl;
import com.chengyinhub.business.service.impl.MerchantChapterApplicationServiceImpl;
import com.chengyinhub.business.service.impl.MerchantOfferServiceImpl;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import com.chengyinhub.business.service.support.TopicSupplyReviewService;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.*;

/**
 * 自由探索商家验收卡的**真库**一截:SC-FREE-NODE-EDIT(M1/M2)、SC-FREE-SUPPLY(M3)、SC-FREE-TERMS(M4)。
 *
 * <p>mock 版({@code FreeExploreSupplyNodeTermsAcceptanceTest})证明的是「服务端传给 mapper 的对象对不对」;
 * 本类证明的是 mock 证明不了的那半:**真 mapper XML 在真 MySQL 8 上到底写了什么**——
 * {@code updateCmsTopicNode} 的 null 跳过 {@code <if>} 会不会把「审核痕迹清空」吞掉、
 * {@code selectCmsTopicNodeList} 的三维 probe 在真表上是否只命中自己那一行、
 * 重复入驻到底被唯一键 {@code uk_offer_merchant_chapter} 还是被服务层拦下、退出只动自己的供给行。
 *
 * <p>驱动方式:真服务实现 + 真 mapper XML(SqlSessionFactoryBean)+ TransactionInterceptor 代理,
 * 与 {@code GameSessionLedgerMySqlFixture} 同范式。只有非存储协作者是 Mockito:
 * 提交后通知({@link CommittedBusinessNotice}/{@link INotifyService})、权益/计酬/类目/运营范围这些
 * 在 TRAFFIC 普通章节路径上根本不会被调用的依赖(逐个 {@code verifyNoInteractions} 证明没走到)。
 * 写库的协作者一律真身:节点/章节/模板/中标报名/承接申请服务、供给审核锁 {@link TopicSupplyReviewService}。
 *
 * <p>Schema:逐表照抄 {@code sql/base_schema_20260818.sql} 的生产 DDL,再按日期顺序叠
 * {@code chengyinhub-admin/sql/migration_*.sql} 里对这些表的 ALTER(含 PREPARE 字符串里的那条,
 * 原样反转义后执行)。不新增 it-schema 文件。
 *
 * <p>一类一库:{@code MybatisItDatabase.url("free_explore_node_supply")},跑完 DROP 并回读 0。
 */
class FreeExploreNodeSupplyMySqlIT {

    private static final String SUITE = "free_explore_node_supply";

    static final long HOST = 300L, ME = 500L, OTHER = 600L, STRANGER = 777L, OLD_REVIEWER = 301L;
    static final long TOPIC = 8100L, OTHER_TOPIC = 8200L;
    static final long CHAPTER = 8110L, OTHER_CHAPTER = 8120L, OTHER_TOPIC_CHAPTER = 8210L;
    static final long TEMPLATE = 7001L;
    static final long MY_NODE = 9001L, NB_OTHER_TOPIC = 9002L, NB_MY_OTHER_CHAPTER = 9003L,
            NB_OTHER_MERCHANT = 9004L, NB_HOST_NODE = 9005L;
    static final long MY_OFFER = 6001L, OTHER_MERCHANT_OFFER = 6002L, MY_OTHER_CHAPTER_OFFER = 6003L;
    static final String CHAPTER_METHODS = "1,2";
    static final int CHAPTER_MAX_XP = 5;

    /** 本类会写/会读的全部表;每个用例前清空重灌。 */
    private static final List<String> TABLES = List.of("cms_topic", "cms_topic_chapter", "cms_topic_node",
            "merchant_chapter_offer", "merchant_chapter_application", "cms_member_template",
            "cms_registration_merchant", "audit_task", "coop_invite");

    /**
     * 基线之后对上述表的迁移,按文件日期排(无日期的按其内容依赖放在被依赖者之后)。
     * 每个文件只取「ALTER TABLE 本表」那几条执行 —— 同文件里给别的表建表/改表的语句不在本类范围。
     */
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
                "migration_content_safety_hold_20261020.sql"));
        MIGRATIONS.put("cms_topic_chapter", List.of(
                "migration_20260820_new_city_setup_plan.sql",
                "migration_20260820_questo_puzzle_atmosphere.sql",
                "migration_circle_theme_batch2_20260821.sql",
                "migration_x10_chapter_audio_20260905.sql",
                "migration_topic_chapter_finish_time_20260910.sql"));
        MIGRATIONS.put("cms_topic_node", List.of("migration_node_unlock_after_20260820.sql"));
        // 两份 20261003 是同一组列的先后两版守卫脚本;后一版(merchant_offer_withdrawal_columns)是
        // 「先只读断言形状、再补列」的收口版,覆盖 offer 三列 + application 一列,只叠它,不叠两遍。
        MIGRATIONS.put("merchant_chapter_offer", List.of(
                "migration_20261003_merchant_offer_withdrawal_columns.sql",
                "migration_club_direct_merchant_receivable_20261004.sql"));
        MIGRATIONS.put("merchant_chapter_application", List.of(
                "migration_20261003_merchant_offer_withdrawal_columns.sql",
                "migration_20261024_d_chapter_application_round.sql"));
        MIGRATIONS.put("cms_member_template", List.of(
                "migration_question_media_20260816.sql",
                "migration_20260820_new_city_preference_chain.sql",
                "migration_app_sensor_template_fields_20260821.sql",
                "migration_20260822_advanced_game_runtime.sql",
                "migration_template_merchant_guide_20260906.sql",
                "migration_template_reward_rules_20260906.sql"));
        MIGRATIONS.put("cms_registration_merchant", List.of("migration_registration_merchant_audit_time_20260910.sql"));
        MIGRATIONS.put("coop_invite", List.of("migration_coop_deposit_refund_state_20260913.sql"));
    }

    private static String database;
    private static DriverManagerDataSource source;
    private static JdbcTemplate db;
    private static SqlSessionTemplate sql;

    private IChapterMerchantNodeService nodeService;
    private IMerchantOfferService offerService;
    private CommittedBusinessNotice notice;
    private ICmsTopicService topicService;
    private List<Object> unusedOnTrafficPath;

    @BeforeAll
    static void createSchema() throws Exception {
        assertFalse(ForbiddenDbHosts.isForbidden(MybatisItDatabase.host(), System.getenv()),
                "IT 只许连本地/CI 专用库,禁止连生产库主机");
        String url = MybatisItDatabase.url(SUITE);
        database = MybatisItDatabase.databaseName(SUITE);
        source = new DriverManagerDataSource(url, MybatisItDatabase.user(), MybatisItDatabase.password());
        db = new JdbcTemplate(source);
        assertEquals(database, db.queryForObject("select database()", String.class));
        assertTrue(db.queryForObject("select version()", String.class).startsWith("8."), "须 MySQL 8(CHECK 约束生效)");

        String base = read("../sql/base_schema_20260818.sql");
        for (String table : TABLES) {
            db.execute(createStatement(base, table));
            for (String migration : MIGRATIONS.getOrDefault(table, List.of())) {
                List<String> alters = alterStatements(read("sql/" + migration), table);
                assertFalse(alters.isEmpty(), migration + " 里找不到对 " + table + " 的 ALTER");
                for (String alter : alters) db.execute(alter);
            }
        }

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfigLocation(new ClassPathResource("mybatis/mybatis-config.xml"));
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain,com.chengyinhub.system.domain");
        List<Resource> xml = new ArrayList<>();
        for (String name : List.of("CmsTopicNodeMapper", "CmsTopicChapterMapper", "CmsTopicMapper",
                "MerchantChapterOfferMapper", "MerchantChapterApplicationMapper", "CmsMemberTemplateMapper",
                "CmsRegistrationMerchantMapper", "AuditTaskMapper", "AuditRecordMapper", "CoopInviteMapper")) {
            xml.add(new ClassPathResource("mapper/business/" + name + ".xml"));
        }
        factory.setMapperLocations(xml.toArray(new Resource[0]));
        sql = new SqlSessionTemplate(factory.getObject());
    }

    @AfterAll
    static void dropSchema() {
        if (database == null) return;
        // 库删掉后原数据源连不上了;回读走不带库名的管理连接。
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://" + MybatisItDatabase.host()
                + ":" + MybatisItDatabase.port() + "/?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password()));
        admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        assertEquals(Integer.valueOf(0), admin.queryForObject(
                "select count(*) from information_schema.schemata where schema_name = ?", Integer.class, database),
                "scratch 库必须删干净");
    }

    @BeforeEach
    void seedAndWire() {
        for (String table : TABLES) db.update("delete from " + table);
        seed();
        wire();
    }

    // ===================================================================== M1 SC-FREE-NODE-EDIT

    /**
     * M1:已过审点位再编辑 —— 真 probe 只命中 (本主题, 本章, 本商家) 那一行;
     * 审核态回到待审,且旧审核人 / 旧审核时间**真的落库被覆盖**(0 / 纪元),不是被 null 跳过留在库里;
     * 邻居(同主题他章 / 同章他商家 / 他主题同章号 / 主办方节点)逐字节不变;编辑永不新增行。
     */
    @Test
    void m1_editApprovedNode_updatesOnlyMyRow_resetsAuditTraceInDb_neighboursByteIdentical() {
        List<Map<String, Object>> before = nodes();
        CmsTopicNode draft = validDraft();
        draft.setId(NB_OTHER_MERCHANT);        // 客户端伪造:邻居主键 + 他人归属 + 他主题他章
        draft.setTopicId(OTHER_TOPIC);
        draft.setChapterId(OTHER_CHAPTER);
        draft.setMerchantMemberId(OTHER);
        draft.setNodeAuditStatus(1);
        draft.setNodeAuditMemberId(HOST);

        CmsTopicNode saved = nodeService.submit(ME, CHAPTER, draft);

        assertEquals(Long.valueOf(MY_NODE), saved.getId(), "只能落在 (本主题, 本章, 本商家) 那一行");
        List<Map<String, Object>> after = nodes();
        assertEquals(before.size(), after.size(), "编辑既有点位不得新增行");
        assertEquals(without(before, MY_NODE), without(after, MY_NODE), "邻居节点必须逐字节不变");

        Map<String, Object> mine = row(after, MY_NODE);
        assertEquals(TOPIC, num(mine.get("topic_id")), "归属主题服务端定,客户端伪造无效");
        assertEquals(CHAPTER, num(mine.get("chapter_id")));
        assertEquals(ME, num(mine.get("merchant_member_id")));
        assertEquals("新·星海咖啡", mine.get("name"));
        assertEquals("星海街 9 号", mine.get("address"));
        assertEquals("120.7", mine.get("longitude"));
        assertEquals("31.3", mine.get("latitude"));
        assertEquals(3, ((Number) mine.get("xp_value")).intValue());
        assertEquals("CODE-MINE", mine.get("checkin_code"), "编辑不得换掉已印好的打卡码");
        assertEquals(0, ((Number) mine.get("node_audit_status")).intValue(), "改一版就回到待审(0)");
        assertEquals("", mine.get("node_audit_reason"), "旧审核意见必须真被清掉");
        // ★ null 跳过 <if> 的坑:这两列若传 null,旧审核人/旧时间会原样留在库里,冒充「新版已被批」。
        assertEquals(0L, num(mine.get("node_audit_member_id")).longValue(),
                "旧审核人(" + OLD_REVIEWER + ")必须被覆盖成 0 落库,而不是留在库里");
        Timestamp auditTime = db.queryForObject("select node_audit_time from cms_topic_node where id=?",
                Timestamp.class, MY_NODE);
        assertNotNull(auditTime, "审核时间列本身不是被置 NULL,而是写入纪元哨兵");
        assertEquals(0L, auditTime.getTime(), "旧审核时间必须被纪元哨兵覆盖落库");
        assertEquals(Integer.valueOf(0), db.queryForObject("select count(*) from cms_topic_node where id=? and ("
                + "node_audit_member_id = ? or node_audit_time = '2026-10-01 10:00:00')", Integer.class,
                MY_NODE, OLD_REVIEWER), "库里不得残留任何旧审核痕迹");

        // 待审版本对玩家侧(无 merchantMemberId/nodeAuditStatus 的 probe)不可见:真可见性闸。
        CmsTopicNode publicProbe = new CmsTopicNode();
        publicProbe.setChapterId(CHAPTER);
        List<Long> visible = new ArrayList<>();
        for (CmsTopicNode n : sql.getMapper(CmsTopicNodeMapper.class).selectCmsTopicNodeList(publicProbe)) {
            visible.add(n.getId());
        }
        assertFalse(visible.contains(MY_NODE), "重审中的点位不得出现在玩家侧列表:" + visible);
        assertTrue(visible.contains(NB_OTHER_MERCHANT) && visible.contains(NB_HOST_NODE),
                "其他已过审 / 主办方节点照常可见:" + visible);
    }

    // ===================================================================== M2 节点取消编辑(后端侧)

    /** M2:地址为空 —— 服务端拒,cms_topic_node 整表不变(取消/无效提交不落半条)。 */
    @Test
    void m2_blankAddress_rejected_nodeTableUnchanged() {
        List<Map<String, Object>> before = nodes();
        CmsTopicNode draft = validDraft();
        draft.setAddress("   ");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> nodeService.submit(ME, CHAPTER, draft));

        assertEquals("请填写到店地址并重新地图选点", e.getMessage());
        assertEquals(before, nodes(), "无效提交不得改动任何节点行");
    }

    /** M2:坐标非法(纬度越界 / 经度非数字)—— 服务端拒,整表不变。 */
    @Test
    void m2_invalidCoordinates_rejected_nodeTableUnchanged() {
        List<Map<String, Object>> before = nodes();
        for (String[] coords : List.of(new String[] {"120.7", "91"}, new String[] {"abc", "31.3"},
                new String[] {"NaN", "31.3"}, new String[] {null, "31.3"})) {
            CmsTopicNode draft = validDraft();
            draft.setLongitude(coords[0]);
            draft.setLatitude(coords[1]);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> nodeService.submit(ME, CHAPTER, draft), Arrays.toString(coords));

            assertEquals("请在地图选择有效的到店坐标", e.getMessage(), Arrays.toString(coords));
        }
        assertEquals(before, nodes(), "非法坐标不得改动任何节点行");
    }

    // ===================================================================== M3 SC-FREE-SUPPLY

    /**
     * M3:入驻 —— 客户端伪造 topic/merchant/status/quotaUsed/条款,真库落的是服务端解析值:
     * topic 由章节反查、merchant 是调用方、status=1、quota_used=0、非容量档 quota_total=0、
     * 玩法边界冻结章节那份;邻居供给行不变,恰好新增一行。
     */
    @Test
    void m3_enroll_persistsServerResolvedFields_andFreezesChapterTerms() {
        db.update("delete from merchant_chapter_offer where id=?", MY_OFFER);  // 本章我还没入驻
        List<Map<String, Object>> before = offers();

        Long id = offerService.enroll(forgedTrafficOffer(), ME);

        assertNotNull(id, "useGeneratedKeys 必须回填主键");
        List<Map<String, Object>> after = offers();
        assertEquals(before.size() + 1, after.size(), "恰好新增一行");
        assertEquals(before, without(after, id), "其他供给行逐字节不变");
        Map<String, Object> o = row(after, id);
        assertEquals(TOPIC, num(o.get("topic_id")), "topic_id 必须由章节反查,不信客户端");
        assertEquals(CHAPTER, num(o.get("chapter_id")));
        assertEquals(ME, num(o.get("merchant_id")), "merchant_id 必须是调用方,不信客户端");
        assertEquals(1, ((Number) o.get("status")).intValue(), "非补位供给落库即生效");
        assertEquals(0, ((Number) o.get("quota_used")).intValue(), "quota_used 恒从 0 起");
        assertEquals(0, ((Number) o.get("quota_total")).intValue(), "TRAFFIC 非容量档 quota_total 归 0");
        assertEquals(TermsMode.TRAFFIC, o.get("terms_mode"));
        assertEquals(CHAPTER_METHODS, o.get("allowed_validation_methods"), "核验方式冻结章节那份");
        assertEquals(CHAPTER_MAX_XP, ((Number) o.get("max_node_xp")).intValue(), "探索值上限冻结章节那份");
        assertNull(o.get("perk_id"));
        assertNull(o.get("per_head_fee"));
        assertNull(o.get("payer_club_id"));
        assertNull(o.get("replacement_of_offer_id"));
        assertNull(o.get("withdraw_reason"));
        assertTrue(unusedOnTrafficPath.stream().allMatch(m -> { verifyNoInteractions(m); return true; }));
    }

    /**
     * M3:同一商家同章重复入驻 —— 真实行为是**唯一键 uk_offer_merchant_chapter 拦下**
     * (服务层没有前置查重:assertMaxMerchant 只数名额,1 < 4 放行;是 INSERT 撞键 →
     * DuplicateKeyException → 服务翻译成人话)。事务整体回滚,表里仍只有原来那一行。
     */
    @Test
    void m3_duplicateEnroll_rejectedByUniqueKey_noSecondRow() {
        List<Map<String, Object>> before = offers();
        assertEquals(Integer.valueOf(1), db.queryForObject("select count(*) from information_schema.statistics"
                + " where table_schema=database() and table_name='merchant_chapter_offer'"
                + " and index_name='uk_offer_merchant_chapter' and non_unique=0 and column_name='chapter_id'",
                Integer.class), "真 DDL 里的唯一键必须在位");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> offerService.enroll(forgedTrafficOffer(), ME));

        assertEquals("您已入驻本章节,请勿重复提交", e.getMessage());
        assertEquals(before, offers(), "重复入驻不得新增或改动任何供给行");
    }

    /**
     * M3:退出只动调用方自己的那一行 —— status 0 + 理由 + 必选快照落库;
     * 同章别家 / 我在他章的供给、主题行逐字节不变;我在本章的承接申请随之退役,别家申请不动。
     */
    @Test
    void m3_withdraw_touchesOnlyCallersOwnOffer() {
        List<Map<String, Object>> offersBefore = offers();
        List<Map<String, Object>> topicBefore = db.queryForList("select * from cms_topic order by id");

        boolean required = offerService.withdrawOwnChapterOffer(MY_OFFER, ME, " 店面装修 ");

        assertFalse(required, "本章是可选章(required=0)");
        List<Map<String, Object>> offersAfter = offers();
        assertEquals(without(offersBefore, MY_OFFER), without(offersAfter, MY_OFFER), "别人的 / 我他章的供给不得被波及");
        Map<String, Object> mine = row(offersAfter, MY_OFFER);
        assertEquals(0, ((Number) mine.get("status")).intValue(), "退出 = 标失效,不删行");
        assertEquals("店面装修", mine.get("withdraw_reason"));
        // withdraw_required 是 TINYINT(1),驱动按 BIT 语义读成 Boolean。
        assertEquals(Boolean.FALSE, mine.get("withdraw_required"), "退出时必选章快照落库为 0");
        assertEquals(String.valueOf(ME), mine.get("update_by"));
        assertEquals(topicBefore, db.queryForList("select * from cms_topic order by id"),
                "可选章 + 未上架:主题行不动(不触发 casUserStatus)");
        verifyNoInteractions(topicService);
        assertEquals(Integer.valueOf(2), db.queryForObject("select del_flag from merchant_chapter_application"
                + " where chapter_id=? and merchant_id=?", Integer.class, CHAPTER, ME), "我在本章的已批申请随退出退役");
        assertEquals(Integer.valueOf(0), db.queryForObject("select del_flag from merchant_chapter_application"
                + " where chapter_id=? and merchant_id=?", Integer.class, CHAPTER, OTHER), "别家申请不动");
    }

    /** M3:别家拿我的 offerId 来退出 —— 拒,所有供给行与申请行逐字节不变。 */
    @Test
    void m3_withdrawSomeoneElsesOffer_rejected_nothingChanges() {
        List<Map<String, Object>> offersBefore = offers();
        List<Map<String, Object>> appsBefore = db.queryForList("select * from merchant_chapter_application order by id");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> offerService.withdrawOwnChapterOffer(MY_OFFER, OTHER, "抢退"));

        assertEquals("只能退出自己的章节供给", e.getMessage());
        assertEquals(offersBefore, offers());
        assertEquals(appsBefore, db.queryForList("select * from merchant_chapter_application order by id"));
        verifyNoInteractions(notice);
    }

    // ===================================================================== M4 SC-FREE-TERMS

    /**
     * M4:同一条邀约经发出方列表 SQL(from_id)与接收方列表 SQL(to_type/to_id)投影,条款列逐字段一致;
     * 第三方无论按发出方还是接收方口径都查不到它。
     */
    @Test
    void m4_sameInvite_sentAndReceivedProjectionCarryIdenticalTerms_strangerSeesNone() {
        db.update("insert into coop_invite(id,invite_type,from_type,from_id,to_type,to_id,topic_id,target_level,status,"
                + "expire_time,config_deadline,share_mode,share_rate,fixed_fee,deposit_amount,deposit_status,create_time)"
                + " values(5101,2,'merchant',?,'merchant',?,?,0,1,date_add(now(),interval 3 day),"
                + "date_add(now(),interval 2 day),1,12.50,null,null,0,now())", HOST, ME, TOPIC);
        CoopInviteMapper mapper = sql.getMapper(CoopInviteMapper.class);
        Date now = new Date();

        CoopInvite sentQ = new CoopInvite();
        sentQ.setFromId(HOST);
        CoopInvite receivedQ = new CoopInvite();
        receivedQ.setToType("merchant");
        receivedQ.setToId(ME);
        List<CoopInvite> sent = mapper.selectCoopInviteListReadOnly(sentQ, now);
        List<CoopInvite> received = mapper.selectCoopInviteListReadOnly(receivedQ, now);

        assertEquals(1, sent.size(), "发出方恰好看到这一条");
        assertEquals(1, received.size(), "接收方恰好看到这一条");
        assertEquals(terms(sent.get(0)), terms(received.get(0)), "双方看到的条款字段必须逐字段一致");
        assertEquals(Long.valueOf(5101L), sent.get(0).getId());
        assertEquals(0, new java.math.BigDecimal("12.50").compareTo(sent.get(0).getShareRate()));

        CoopInvite strangerSent = new CoopInvite();
        strangerSent.setFromId(STRANGER);
        CoopInvite strangerReceivedMerchant = new CoopInvite();
        strangerReceivedMerchant.setToType("merchant");
        strangerReceivedMerchant.setToId(STRANGER);
        CoopInvite strangerReceivedClub = new CoopInvite();
        strangerReceivedClub.setToType("club");
        strangerReceivedClub.setToId(ME);   // 号段撞上也不行:to_type 不同就不是他的
        assertTrue(mapper.selectCoopInviteListReadOnly(strangerSent, now).isEmpty(), "第三方发出口径查不到");
        assertTrue(mapper.selectCoopInviteListReadOnly(strangerReceivedMerchant, now).isEmpty(), "第三方接收口径查不到");
        assertTrue(mapper.selectCoopInviteListReadOnly(strangerReceivedClub, now).isEmpty(), "错 to_type 查不到");
    }

    // ===================================================================== 夹具

    private void seed() {
        // 主题:自由定向(product_type=2)、招募中(lifecycle=1)、未上架(user_status=0)、招募截止在未来。
        db.update("insert into cms_topic(id,member_id,name,product_type,lifecycle,status,user_status,is_draft,del_flag,"
                + "recruit_deadline,start_date,create_time) values"
                + "(?,?,'城南自由探索',2,1,1,0,0,0,date_add(now(),interval 7 day),date_add(now(),interval 14 day),now()),"
                + "(?,?,'城北自由探索',2,1,1,0,0,0,date_add(now(),interval 7 day),date_add(now(),interval 14 day),now())",
                TOPIC, HOST, OTHER_TOPIC, HOST);
        db.update("insert into cms_topic_chapter(id,topic_id,name,sort_id,required,terms_mode,max_merchant,recruit_enabled,"
                + "allowed_validation_methods,max_node_xp) values"
                + "(?,?,'第一章',1,0,'TRAFFIC',4,0,?,?),(?,?,'第二章',2,0,'TRAFFIC',4,0,?,?),(?,?,'北一章',1,0,'TRAFFIC',4,0,?,?)",
                CHAPTER, TOPIC, CHAPTER_METHODS, CHAPTER_MAX_XP, OTHER_CHAPTER, TOPIC, CHAPTER_METHODS, CHAPTER_MAX_XP,
                OTHER_TOPIC_CHAPTER, OTHER_TOPIC, CHAPTER_METHODS, CHAPTER_MAX_XP);
        db.update("insert into cms_member_template(id,member_id,title,validation_method) values(?,?,'到店拍照',1)",
                TEMPLATE, ME);
        // 节点:我的那行 sort_id 最大 —— selectCmsTopicNodeList 按 sort_id 升序,probe 少任一维都会先命中邻居。
        node(NB_HOST_NODE, TOPIC, CHAPTER, null, null, null, null, "主办方节点", 0);
        node(NB_OTHER_TOPIC, OTHER_TOPIC, CHAPTER, ME, 1, HOST, "2026-09-01 09:00:00", "他主题同章号节点", 1);
        node(NB_MY_OTHER_CHAPTER, TOPIC, OTHER_CHAPTER, ME, 1, HOST, "2026-09-02 09:00:00", "我在他章的节点", 2);
        node(NB_OTHER_MERCHANT, TOPIC, CHAPTER, OTHER, 1, HOST, "2026-09-03 09:00:00", "同章别家节点", 3);
        node(MY_NODE, TOPIC, CHAPTER, ME, 1, OLD_REVIEWER, "2026-10-01 10:00:00", "我的旧点位", 4);
        db.update("update cms_topic_node set node_audit_reason='旧版已过审' where id=?", MY_NODE);
        // 生效供给:我在本章、别家在本章、我在他章。
        offer(MY_OFFER, TOPIC, CHAPTER, ME);
        offer(OTHER_MERCHANT_OFFER, TOPIC, CHAPTER, OTHER);
        offer(MY_OTHER_CHAPTER_OFFER, TOPIC, OTHER_CHAPTER, ME);
        db.update("insert into merchant_chapter_application(id,topic_id,chapter_id,merchant_id,status,source,del_flag)"
                + " values(1,?,?,?,1,0,0),(2,?,?,?,1,0,0)", TOPIC, CHAPTER, ME, TOPIC, CHAPTER, OTHER);
        // 历史中标闸(recruit_enabled=0 的章节走它):我在本主题中标。
        db.update("insert into cms_registration_merchant(id,member_id,topic_id,mode,audit_status,del_flag) values(1,?,?,1,1,0)",
                ME, TOPIC);
    }

    private void node(long id, long topic, long chapter, Long merchant, Integer audit, Long reviewer, String auditTime,
                      String name, int sort) {
        db.update("insert into cms_topic_node(id,topic_id,chapter_id,merchant_member_id,node_audit_status,node_audit_member_id,"
                + "node_audit_time,name,description,address,longitude,latitude,template_id,checkin_code,status,sort_id,xp_value,"
                + "create_time,update_time) values(?,?,?,?,?,?,?,?,'旧描述','旧地址','120.1','31.1',?,?,1,?,2,"
                + "'2026-09-01 08:00:00','2026-09-01 08:00:00')",
                id, topic, chapter, merchant, audit, reviewer, auditTime, name, TEMPLATE,
                id == MY_NODE ? "CODE-MINE" : "CODE-" + id, sort);
    }

    private void offer(long id, long topic, long chapter, long merchant) {
        db.update("insert into merchant_chapter_offer(id,topic_id,chapter_id,merchant_id,terms_mode,quota_total,quota_used,"
                + "allowed_validation_methods,max_node_xp,status,create_time) values(?,?,?,?,'TRAFFIC',0,0,?,?,1,"
                + "'2026-09-01 08:00:00')", id, topic, chapter, merchant, CHAPTER_METHODS, CHAPTER_MAX_XP);
    }

    private void wire() {
        CmsTopicMapper topicMapper = sql.getMapper(CmsTopicMapper.class);
        CmsTopicChapterMapper chapterMapper = sql.getMapper(CmsTopicChapterMapper.class);
        MerchantChapterOfferMapper offerMapper = sql.getMapper(MerchantChapterOfferMapper.class);
        MerchantChapterApplicationMapper applicationMapper = sql.getMapper(MerchantChapterApplicationMapper.class);
        TopicSupplyReviewService supplyReview = new TopicSupplyReviewService(sql.getMapper(AuditTaskMapper.class),
                sql.getMapper(AuditRecordMapper.class), topicMapper, chapterMapper);
        notice = mock(CommittedBusinessNotice.class);
        INotifyService notify = mock(INotifyService.class);
        RegistrationEntitlementMapper entitlements = mock(RegistrationEntitlementMapper.class); // 只在提交后回调里读
        topicService = mock(ICmsTopicService.class);

        CmsTopicNodeServiceImpl nodes = new CmsTopicNodeServiceImpl();
        ReflectionTestUtils.setField(nodes, "cmsTopicNodeMapper", sql.getMapper(CmsTopicNodeMapper.class));
        com.chengyinhub.business.service.support.TopicContentBudgetTestWiring.wire(nodes,
                topicMapper, chapterMapper, sql.getMapper(CmsTopicNodeMapper.class));
        CmsTopicChapterServiceImpl chapters = new CmsTopicChapterServiceImpl();
        ReflectionTestUtils.setField(chapters, "cmsTopicChapterMapper", chapterMapper);
        CmsMemberTemplateServiceImpl templates = new CmsMemberTemplateServiceImpl();
        ReflectionTestUtils.setField(templates, "cmsMemberTemplateMapper", sql.getMapper(CmsMemberTemplateMapper.class));
        ChapterMerchantNodeServiceImpl node = new ChapterMerchantNodeServiceImpl(nodes, chapters, topicService, templates,
                offerMapper, applicationMapper);
        ReflectionTestUtils.setField(node, "supplyReviewService", supplyReview);
        ReflectionTestUtils.setField(node, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(node, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(node, "registrationEntitlementMapper", entitlements);
        ReflectionTestUtils.setField(node, "businessNotice", notice);
        ReflectionTestUtils.setField(node, "notifyService", notify);
        nodeService = transactional(node, IChapterMerchantNodeService.class);

        MerchantChapterApplicationServiceImpl applications = new MerchantChapterApplicationServiceImpl();
        ReflectionTestUtils.setField(applications, "applicationMapper", applicationMapper);
        CmsRegistrationMerchantServiceImpl wonBids = new CmsRegistrationMerchantServiceImpl();
        ReflectionTestUtils.setField(wonBids, "cmsRegistrationMerchantMapper",
                sql.getMapper(CmsRegistrationMerchantMapper.class));
        // TRAFFIC 普通章节路径上不会被调用的依赖:mock,并在用例里 verifyNoInteractions 证明没走到。
        unusedOnTrafficPath = List.of(mock(CoopPerkMapper.class), mock(IProductRoleService.class),
                mock(ICoopInviteService.class), mock(ISysCategoryService.class),
                mock(ITopicOperationsScopeAuthorizer.class), mock(MerchantCircleSupplyProfileMapper.class));
        MerchantOfferServiceImpl offers = new MerchantOfferServiceImpl();
        ReflectionTestUtils.setField(offers, "supplyReviewService", supplyReview);
        ReflectionTestUtils.setField(offers, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(offers, "registrationEntitlementMapper", entitlements);
        ReflectionTestUtils.setField(offers, "businessNotice", notice);
        ReflectionTestUtils.setField(offers, "notifyService", notify);
        com.chengyinhub.business.service.ISystemNotificationIntentService ownerIntents =
                mock(com.chengyinhub.business.service.ISystemNotificationIntentService.class);
        when(ownerIntents.findIntentId(anyString())).thenReturn(null);
        when(ownerIntents.enqueue(anyLong(), anyInt(), anyString(), nullable(String.class), anyString())).thenReturn(9001L);
        ReflectionTestUtils.setField(offers, "systemNotificationIntents", ownerIntents);
        ReflectionTestUtils.setField(offers, "imService", mock(com.chengyinhub.business.service.IImService.class));
        ReflectionTestUtils.setField(offers, "ownerNoticeWxChannel", mock(com.chengyinhub.business.notify.WxSubscribeChannel.class));
        ReflectionTestUtils.setField(offers, "ownerNoticeExecutor", mock(java.util.concurrent.ScheduledExecutorService.class));
        ReflectionTestUtils.setField(offers, "applicationMapper", applicationMapper);
        ReflectionTestUtils.setField(offers, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(offers, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(offers, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(offers, "registrationMerchantService", wonBids);
        ReflectionTestUtils.setField(offers, "merchantChapterApplicationService", applications);
        ReflectionTestUtils.setField(offers, "coopPerkMapper", unusedOnTrafficPath.get(0));
        ReflectionTestUtils.setField(offers, "productRoleService", unusedOnTrafficPath.get(1));
        ReflectionTestUtils.setField(offers, "coopInviteService", unusedOnTrafficPath.get(2));
        ReflectionTestUtils.setField(offers, "sysCategoryService", unusedOnTrafficPath.get(3));
        ReflectionTestUtils.setField(offers, "topicOperationsScopeAuthorizer", unusedOnTrafficPath.get(4));
        ReflectionTestUtils.setField(offers, "circleSupplyMapper", unusedOnTrafficPath.get(5));
        offerService = transactional(offers, IMerchantOfferService.class);
    }

    private static <T> T transactional(T target, Class<T> api) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(api);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        return api.cast(proxy.getProxy());
    }

    private static CmsTopicNode validDraft() {
        CmsTopicNode draft = new CmsTopicNode();
        draft.setName("  新·星海咖啡 ");
        draft.setAddress("星海街 9 号");
        draft.setLongitude("120.7");
        draft.setLatitude("31.3");
        draft.setTemplateId(TEMPLATE);
        draft.setXpValue(3);
        draft.setDescription("新描述");
        return draft;
    }

    private static MerchantChapterOffer forgedTrafficOffer() {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setChapterId(CHAPTER);
        o.setTermsMode(TermsMode.TRAFFIC);
        o.setTopicId(OTHER_TOPIC);
        o.setMerchantId(STRANGER);
        o.setStatus(0);
        o.setQuotaUsed(40);
        o.setQuotaTotal(80);
        o.setAllowedValidationMethods("1,2,3,4,5");
        o.setMaxNodeXp(99);
        return o;
    }

    private static List<Object> terms(CoopInvite i) {
        return Arrays.asList(i.getId(), i.getInviteType(), i.getFromType(), i.getFromId(), i.getToType(), i.getToId(),
                i.getTopicId(), i.getStatus(), i.getShareMode(), i.getShareRate(), i.getFixedFee(), i.getExpireTime(),
                i.getConfigDeadline(), i.getDepositAmount(), i.getDepositStatus());
    }

    private static List<Map<String, Object>> nodes() {
        return db.queryForList("select * from cms_topic_node order by id");
    }

    private static List<Map<String, Object>> offers() {
        return db.queryForList("select * from merchant_chapter_offer order by id");
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, long id) {
        for (Map<String, Object> r : rows) if (num(r.get("id")) == id) return r;
        throw new AssertionError("找不到行 id=" + id);
    }

    private static List<Map<String, Object>> without(List<Map<String, Object>> rows, long id) {
        List<Map<String, Object>> rest = new ArrayList<>();
        for (Map<String, Object> r : rows) if (num(r.get("id")) != id) rest.add(r);
        return rest;
    }

    private static Long num(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** 从生产 DDL 快照里切出一张表的 CREATE(列注释里可能有分号,所以从表尾 ") ENGINE=" 之后找行末分号)。 */
    private static String createStatement(String script, String table) {
        String head = "CREATE TABLE `" + table + "` (";
        int start = script.indexOf(head);
        assertTrue(start >= 0, "真 DDL 里找不到:" + head);
        int tail = script.indexOf("\n) ENGINE=", start);
        return script.substring(start, script.indexOf(";\n", tail));
    }

    /**
     * 取出迁移脚本里「ALTER TABLE 本表」的语句,原样执行。
     * 两种写法都认:裸 ALTER;以及 information_schema 预检 + PREPARE 的那种 —— ALTER 在单引号字符串里,
     * 取出后把 '' 反转义。基线表必然缺这些列,所以预检分支一定走「执行」,直接执行等价。
     */
    static List<String> alterStatements(String script, String table) {
        StringBuilder text = new StringBuilder();
        for (String line : script.split("\n")) {
            if (!line.trim().startsWith("--")) text.append(line).append('\n');
        }
        java.util.regex.Pattern bare = java.util.regex.Pattern.compile(
                "(?is)^ALTER\\s+TABLE\\s+`?" + table + "`?\\s.*");
        java.util.regex.Pattern quoted = java.util.regex.Pattern.compile(
                "(?i)'(ALTER\\s+TABLE\\s+`?" + table + "`?\\s)");
        List<String> out = new ArrayList<>();
        for (String statement : ItSchema.splitStatements(text.toString())) {
            if (bare.matcher(statement).matches()) {
                out.add(statement);
                continue;
            }
            java.util.regex.Matcher m = quoted.matcher(statement);
            while (m.find()) {
                StringBuilder literal = new StringBuilder();
                int i = m.start(1);
                for (; i < statement.length(); i++) {
                    char c = statement.charAt(i);
                    if (c == '\'') {
                        if (i + 1 < statement.length() && statement.charAt(i + 1) == '\'') {
                            literal.append('\'');
                            i++;
                            continue;
                        }
                        break;
                    }
                    literal.append(c);
                }
                out.add(literal.toString());
            }
        }
        return out;
    }
}

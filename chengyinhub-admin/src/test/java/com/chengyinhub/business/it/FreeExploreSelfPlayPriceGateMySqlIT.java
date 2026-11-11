package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.exception.TopicPublicationContentException;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.TopicSaleFinalizationServiceImpl;
import com.chengyinhub.business.service.support.CommercialReviewStatusSource;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.business.service.support.MerchantPublishedTopicTransferGate;
import com.chengyinhub.business.service.support.PendingRecruitWorkflowTerminator;
import com.chengyinhub.business.service.support.TopicPublicationContentGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * f-money3:自由探索主题 + 收费自玩票没有结算出口(f-money2 RESULT 第五节 2),发布 / 开售各入口失败关闭。
 *
 * <p>真 MySQL 8 + 真 CmsTopicMapper XML:
 * <ol>
 *   <li>生产 {@link TopicSaleFinalizationServiceImpl#finalizeForSale} 行锁读到违规行即拒,行原样不动,
 *       没有进入审核 / 合作单 / 开售 CAS;同一行改成免费自玩后不再被本闸拦(后续闸照旧)。</li>
 *   <li>生产 {@link TopicPublicationContentGuard#publishReadinessProblems} 对真库读回的行给出
 *       {@code FREE_EXPLORE_SELF_PLAY_PRICED} issue。</li>
 *   <li>RESULT.md 里给生产用的只读预检 SQL 原文在真 DDL 上执行:只命中违规行,不误报经典定向 / 零价 / 关闭的自玩票。</li>
 * </ol>
 *
 * <p>Mockito 只用于非存储边界(审核源、合作阵容源、转让闸等),本闸在它们之前拒绝,断言它们零调用。
 * Schema 只来自 {@code sql/base_schema_20260818.sql} 与既有 {@code migration_*.sql},一类一库,跑完删库并回读。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreeExploreSelfPlayPriceGateMySqlIT {
    private static final String SUITE = "f_money3_self_play_gate";
    private static final String MESSAGE = "自由探索主题不能设置收费自玩票，请改为给主题门票定价";
    private static final String RESULT_MD = "docs/delivery/questo-ui-20261109/f-money3/RESULT.md";

    /** 违规:自由探索 + 自玩开 + 39.90,定价阶段。 */
    private static final long BAD_PRICING = 9101L;
    /** 违规但已删除:预检照样列出(del_flag 列给运营判断),不漏。 */
    private static final long BAD_DELETED = 9102L;
    private static final long CLASSIC_PAID = 9201L;
    private static final long FREE_ZERO = 9202L;
    private static final long FREE_OFF = 9203L;
    private static final long FREE_NULL_PRICE = 9204L;

    private static final Map<String, List<String>> MIGRATIONS = new LinkedHashMap<>();
    static {
        // 与 P0FreeExploreSupplyRecoveryMySqlIT 同一份 cms_topic 迁移链(CmsTopicMapper.selectCmsTopicVo 要的列)。
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
    }

    private JdbcTemplate db;
    private CmsTopicMapper topicMapper;
    private TransactionTemplate tx;

    @BeforeAll
    void createSchema() throws Exception {
        assertFalse(ForbiddenDbHosts.isForbidden(MybatisItDatabase.host(), System.getenv()),
                "IT 只许连本地/CI 专用库,禁止连生产库主机");
        DriverManagerDataSource source = P0AcceptanceSchema.dataSource(SUITE);
        db = new JdbcTemplate(source);
        assertEquals(MybatisItDatabase.databaseName(SUITE), db.queryForObject("select database()", String.class));
        assertTrue(db.queryForObject("select version()", String.class).startsWith("8."), "须 MySQL 8");
        P0AcceptanceSchema.baseTables(db, "cms_topic", "cms_registration");
        for (Map.Entry<String, List<String>> table : MIGRATIONS.entrySet()) {
            for (String migration : table.getValue()) {
                String script = new String(Files.readAllBytes(P0AcceptanceSchema.repoRoot()
                        .resolve("chengyinhub-admin/sql").resolve(migration)), StandardCharsets.UTF_8);
                List<String> alters = FreeExploreNodeSupplyMySqlIT.alterStatements(script, table.getKey());
                assertFalse(alters.isEmpty(), migration + " 里找不到对 " + table.getKey() + " 的 ALTER");
                for (String alter : alters) db.execute(alter);
            }
        }
        P0AcceptanceSchema.migrations(source, "migration_20261029_topic_cancellation_receipt.sql");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfigLocation(new ClassPathResource("mybatis/mybatis-config.xml"));
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain,com.chengyinhub.system.domain");
        List<Resource> xml = new ArrayList<>();
        for (String name : List.of("CmsTopicMapper", "CmsTopicChapterMapper", "CmsTopicNodeMapper")) {
            xml.add(new ClassPathResource("mapper/business/" + name + ".xml"));
        }
        factory.setMapperLocations(xml.toArray(new Resource[0]));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        topicMapper = sql.getMapper(CmsTopicMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterAll
    void dropSchema() {
        if (db != null) P0AcceptanceSchema.drop(db, SUITE);
    }

    @BeforeEach
    void seed() {
        db.update("delete from cms_registration");
        db.update("delete from cms_topic");
        topic(BAD_PRICING, 2, 1, "39.90", 2, 0);
        topic(BAD_DELETED, 2, 1, "19.90", 0, 2);
        topic(CLASSIC_PAID, 1, 1, "39.90", 2, 0);
        topic(FREE_ZERO, 2, 1, "0.00", 2, 0);
        topic(FREE_OFF, 2, 0, "39.90", 2, 0);
        topic(FREE_NULL_PRICE, 2, 1, null, 2, 0);
        // 违规主题上已售出 / 已退款的自玩通行证各一笔(topic_id 只由 createSelfPlayPass 写)。
        registration(1L, BAD_PRICING, 2);
        registration(2L, BAD_PRICING, 4);
        registration(3L, CLASSIC_PAID, 2);
    }

    // ===================================================================== 开售终检

    @Test
    void finalizeForSaleRefusesTheViolatingRowAndLeavesItUntouched() {
        Map<String, Object> before = row(BAD_PRICING);
        Collaborators c = new Collaborators();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> tx.execute(status -> c.service.finalizeForSale(BAD_PRICING)));

        assertEquals(MESSAGE, refused.getMessage());
        assertEquals(before, row(BAD_PRICING), "失败关闭只拒,不改存量行");
        assertEquals(null, row(BAD_PRICING).get("selling_started_at"));
        verifyNoInteractions(c.reviewStatusSource, c.lineupSource, c.transferGate, c.terminator);
    }

    @Test
    void sameRowWithAFreeSelfPlayPassesThisGateAndReachesTheExistingSaleChecks() {
        db.update("update cms_topic set self_play_price=0 where id=?", BAD_PRICING);
        Collaborators c = new Collaborators();
        org.mockito.Mockito.doThrow(new IllegalStateException("开售审核未通过"))
                .when(c.reviewStatusSource).assertSaleApproved(BAD_PRICING);

        IllegalStateException next = assertThrows(IllegalStateException.class,
                () -> tx.execute(status -> c.service.finalizeForSale(BAD_PRICING)));

        assertEquals("开售审核未通过", next.getMessage(), "本闸放行后由原有开售闸接手");
        // cms_topic.lifecycle 是 TINYINT(1),Connector/J 默认把它映射成 Boolean(2 读出来是 true);按整数取原值断言仍停在 2。
        assertEquals(2, db.queryForObject("select lifecycle from cms_topic where id=?", Integer.class, BAD_PRICING));
    }

    // ===================================================================== 发布检查

    @Test
    void publishReadinessReadsTheRealRowAndReportsTheIssue() {
        TopicPublicationContentGuard guard = new TopicPublicationContentGuard();
        OmsTicketMapper tickets = mock(OmsTicketMapper.class);
        when(tickets.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(guard, "omsTicketMapper", tickets);

        CmsTopic bad = topicMapper.selectCmsTopicById(BAD_PRICING);
        assertEquals("FREE_EXPLORE_SELF_PLAY_PRICED", guard.publishReadinessProblems(bad).get(0).getCode());
        TopicPublicationContentException refused = assertThrows(TopicPublicationContentException.class,
                () -> guard.assertPublishReady(bad));
        assertEquals("selfPlayPrice", refused.getData().getIssues().get(0).getField());

        for (long ok : new long[] {CLASSIC_PAID, FREE_ZERO, FREE_OFF, FREE_NULL_PRICE}) {
            assertTrue(guard.publishReadinessProblems(topicMapper.selectCmsTopicById(ok)).stream()
                    .noneMatch(p -> "FREE_EXPLORE_SELF_PLAY_PRICED".equals(p.getCode())), "id=" + ok);
        }
    }

    // ===================================================================== RESULT 只读预检 SQL

    @Test
    void resultPrecheckSqlRunsReadOnlyAndFindsExactlyTheViolatingRows() throws Exception {
        List<String> statements = precheckStatements();
        assertEquals(3, statements.size(), "预检 = 列存在性 + 违规行 + 已售自玩单");
        for (String statement : statements) {
            assertTrue(statement.trim().toUpperCase().startsWith("SELECT"), "预检只许 SELECT:" + statement);
        }
        long checksum = tableChecksum();

        List<Map<String, Object>> columns = db.queryForList(statements.get(0));
        assertEquals(12, columns.size(), "预检①列出的 cms_topic / cms_registration 列都在");

        List<Map<String, Object>> rows = db.queryForList(statements.get(1));
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> r : rows) ids.add(((Number) r.get("id")).longValue());
        assertEquals(List.of(BAD_PRICING, BAD_DELETED), ids);

        List<Map<String, Object>> sold = db.queryForList(statements.get(2));
        assertEquals(2, sold.size(), "按主题 + 支付状态分组:9101 已支付 1、已退款 1");
        for (Map<String, Object> r : sold) {
            assertEquals(BAD_PRICING, ((Number) r.get("topic_id")).longValue());
            assertEquals(1, ((Number) r.get("orders")).intValue());
        }

        assertEquals(checksum, tableChecksum(), "预检不改任何行");
    }

    // ===================================================================== helpers

    private final class Collaborators {
        final CommercialReviewStatusSource reviewStatusSource = mock(CommercialReviewStatusSource.class);
        final CoopOrderLineupSource lineupSource = mock(CoopOrderLineupSource.class);
        final MerchantPublishedTopicTransferGate transferGate = mock(MerchantPublishedTopicTransferGate.class);
        final PendingRecruitWorkflowTerminator terminator = mock(PendingRecruitWorkflowTerminator.class);
        final TopicSaleFinalizationServiceImpl service;

        @SuppressWarnings("unchecked")
        Collaborators() {
            ObjectProvider<PendingRecruitWorkflowTerminator> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(terminator);
            service = new TopicSaleFinalizationServiceImpl(topicMapper, mock(OmsTicketMapper.class),
                    mock(CmsTopicChapterMapper.class), mock(MerchantChapterOfferMapper.class), lineupSource, provider,
                    mock(CommercialTopicPolicy.class), reviewStatusSource, transferGate);
        }
    }

    private void topic(long id, int productType, int selfPlay, String price, int lifecycle, int delFlag) {
        db.update("insert into cms_topic(id,member_id,name,product_type,self_play,self_play_price,lifecycle,status,"
                        + "user_status,del_flag,check_status,publish_mode,recruit_deadline,create_time,update_time)"
                        + " values(?,?,?,?,?,?,?,0,0,?,0,'pro',now(),now(),'2026-11-11 08:00:00')",
                id, 7100L, "主题" + id, productType, selfPlay, price == null ? null : new java.math.BigDecimal(price),
                lifecycle, delFlag);
    }

    private void registration(long id, long topicId, int paymentStatus) {
        db.update("insert into cms_registration(id,registration_no,owner_id,ticket_id,ticket_price,member_id,"
                        + "original_amount,total_amount,payable_amount,pay_expire_time,payment_status,topic_id,purchase_kind)"
                        + " values(?,?,0,0,39.90,8100,39.90,39.90,39.90,now(),?,?,2)",
                id, "FM3" + id, paymentStatus, topicId);
    }

    private Map<String, Object> row(long id) {
        return db.queryForMap("select product_type,self_play,self_play_price,self_play_quota,lifecycle,status,"
                + "user_status,del_flag,selling_started_at,update_time from cms_topic where id=?", id);
    }

    private long tableChecksum() {
        long sum = 0;
        for (Map<String, Object> r : db.queryForList("checksum table cms_topic, cms_registration")) {
            sum = sum * 31 + ((Number) r.get("Checksum")).longValue();
        }
        return sum;
    }

    /** RESULT.md 里 {@code -- f-money3-precheck:begin/end} 之间的 SQL 原文,按 ';' 切成语句。 */
    private static List<String> precheckStatements() throws Exception {
        String md = new String(Files.readAllBytes(P0AcceptanceSchema.repoRoot().resolve(RESULT_MD)),
                StandardCharsets.UTF_8);
        int from = md.indexOf("-- f-money3-precheck:begin");
        int to = md.indexOf("-- f-money3-precheck:end");
        assertTrue(from >= 0 && to > from, "RESULT.md 缺预检 SQL 标记");
        StringBuilder body = new StringBuilder();
        for (String line : md.substring(from, to).split("\n")) {
            if (!line.trim().startsWith("--")) body.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String s : body.toString().split(";")) {
            if (!s.trim().isEmpty()) statements.add(s.trim());
        }
        return statements;
    }
}

package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopic;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主题列表的官方/广场路由必须从生效 CONTENT 槽过滤，而不是沿用 topic_type。
 *
 * <p>这是 SQL 级负控：如果将来漏掉 status=1，会把已改判前的历史内容槽也算入来源；
 * 如果未知 scope 落回全量列表，则菜单/路由参数写错时会静默越过来源边界。
 */
class CmsTopicContentScopeSqlTest {

    private static final String XML = "mapper/business/CmsTopicMapper.xml";
    private static final String STATEMENT =
            "com.chengyinhub.business.mapper.CmsTopicMapper.selectCmsTopicList";

    private String sql(String contentScope) throws Exception {
        CmsTopic query = new CmsTopic();
        query.setContentScope(contentScope);
        return sql(query);
    }

    private String sql(CmsTopic query) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement(STATEMENT)
                .getBoundSql(query)
                .getSql()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    @Test
    void officialScope_readsOnlyActivePlatformContentSlot() throws Exception {
        String sql = sql("OFFICIAL");
        assertTrue(sql.contains("exists ( select 1 from product_role content_role"), sql);
        assertTrue(sql.contains("content_role.topic_id = cms_topic.id"), sql);
        assertTrue(sql.contains("content_role.slot = 'content'"), sql);
        assertTrue(sql.contains("content_role.status = 1"), sql);
        assertTrue(sql.contains("content_role.entity_type = 'platform'"), sql);
        assertTrue(!sql.contains("topic_type ="), "官方入口不能再由 topic_type 分流。实际: " + sql);
    }

    @Test
    void plazaScope_readsOnlyActiveClubOrMemberContentSlot() throws Exception {
        String sql = sql("PLAZA");
        assertTrue(sql.contains("content_role.entity_type in ('club', 'member')"), sql);
        assertTrue(sql.contains("content_role.status = 1"), sql);
    }

    @Test
    void plazaSubqueue_filtersByActiveContentSlotAndFailsClosedForOtherPublishers() throws Exception {
        CmsTopic clubQuery = new CmsTopic();
        clubQuery.setContentScope("PLAZA");
        clubQuery.setContentPublisherType("CLUB");
        String clubSql = sql(clubQuery);
        assertTrue(clubSql.contains("content_role.entity_type = 'club'"), clubSql);
        assertTrue(!clubSql.contains("topic_type ="), "广场子队列不能回退到 topic_type。实际: " + clubSql);

        CmsTopic memberQuery = new CmsTopic();
        memberQuery.setContentScope("PLAZA");
        memberQuery.setContentPublisherType("MEMBER");
        String memberSql = sql(memberQuery);
        assertTrue(memberSql.contains("content_role.entity_type = 'member'"), memberSql);

        CmsTopic invalidQuery = new CmsTopic();
        invalidQuery.setContentScope("PLAZA");
        invalidQuery.setContentPublisherType("PLATFORM");
        assertTrue(sql(invalidQuery).contains("1 = 0"),
                "广场子队列不能接受官方或未知发布主体。");
    }

    @Test
    void historicalScope_meansNoActiveContentSlot() throws Exception {
        String sql = sql("HISTORICAL");
        assertTrue(sql.contains("not exists ( select 1 from product_role content_role"), sql);
        assertTrue(sql.contains("content_role.slot = 'content'"), sql);
        assertTrue(sql.contains("content_role.status = 1"), sql);
    }

    @Test
    void crossSourceSearch_readsOnlyValidActiveContentOwners() throws Exception {
        String sql = sql("CROSS_SOURCE");
        assertTrue(sql.contains("content_role.entity_type in ('platform', 'club', 'member')"), sql);
        assertTrue(sql.contains("content_role.status = 1"), sql);
        assertTrue(!sql.contains("1 = 0"), "跨来源检索不是第三个全量菜单，也不能被未知范围分支拒绝。实际: " + sql);
    }

    @Test
    void unknownScope_failsClosedInsteadOfLeakingAllTopics() throws Exception {
        String sql = sql("TYPO");
        assertTrue(sql.contains("1 = 0"), "未知来源范围必须返回空集。实际: " + sql);
    }

    @Test
    void lifecycleFilter_reachesTheListQuery() throws Exception {
        CmsTopic query = new CmsTopic();
        query.setLifecycle(3);
        assertTrue(sql(query).contains("lifecycle = ?"), "运营阶段筛选必须真正落到 SQL。");
    }


    @Test
    void clubFulfillmentFilter_usesActualApplicationsInvitesAndContracts() throws Exception {
        CmsTopic query = new CmsTopic();
        query.setClubFulfillmentState("SIGNED");
        String sql = sql(query);
        assertTrue(sql.contains("coop_order"), sql);
        assertTrue(sql.contains("club_lead_state"), sql);
        assertTrue(sql.contains("lead_state.status in (1, 2)"), sql);
        assertTrue(sql.contains("to_type = 'club'"), sql);
        assertTrue(sql.contains("status = 0"), sql);
        assertTrue(sql.contains("del_flag = 0"), sql);
    }

    @Test
    void merchantSupplyFilter_usesLiveChapterOffersInsteadOfRegistrationCount() throws Exception {
        CmsTopic query = new CmsTopic();
        query.setMerchantSupplyState("GAP");
        String sql = sql(query);
        assertTrue(sql.contains("cms_topic_chapter"), sql);
        assertTrue(sql.contains("merchant_chapter_offer"), sql);
        assertTrue(sql.contains("valid_end is null or"), sql);
        assertTrue(sql.contains("candidate.status in (0, 1)"), sql);
        assertTrue(sql.contains("status = 1"), sql);
    }

    @Test
    void runReadinessFilter_carriesEverySharedPolicyInput() throws Exception {
        CmsTopic query = new CmsTopic();
        query.setRunReadiness("READY");
        String sql = sql(query);
        assertTrue(sql.contains("lifecycle in (3, 4)"), sql);
        assertTrue(sql.contains("cms_topic_node"), sql);
        assertTrue(sql.contains("oms_ticket"), sql);
        assertTrue(sql.contains("coop_invite"), sql);
        assertTrue(sql.contains("coop_order"), sql);
    }

    @Test
    void terminalLifecycleIsExcludedFromReadyAndIncludedInBlockedFilter() throws Exception {
        CmsTopic readyQuery = new CmsTopic();
        readyQuery.setRunReadiness("READY");
        String readySql = sql(readyQuery);
        assertTrue(readySql.contains("cms_topic.lifecycle in (3, 4)"), readySql);

        CmsTopic blockedQuery = new CmsTopic();
        blockedQuery.setRunReadiness("BLOCKED");
        String blockedSql = sql(blockedQuery);
        assertTrue(blockedSql.contains("cms_topic.lifecycle in (3, 4)"), blockedSql);
        assertTrue(blockedSql.contains("cms_topic.lifecycle in (0, 1, 2, 7)"), blockedSql);
        assertTrue(blockedSql.contains("not ("), blockedSql);
    }


    @Test
    void operationalFilters_useExplicitAndAuditableTruthSources() throws Exception {
        CmsTopic query = new CmsTopic();
        query.setDepositStatus(2);
        query.setCityKeyword("上海");
        query.setOperationStartDate(new java.util.Date());
        query.setOperationEndDate(new java.util.Date());
        query.setMerchantSupplyGapOnly(true);
        query.setPendingClubApplicationOnly(true);
        query.setInProgressAnomalyOnly(true);

        String sql = sql(query);

        assertTrue(sql.contains("club_invite.status = 1"), sql);
        assertTrue(sql.contains("club_invite.deposit_status = ?"), sql);
        assertTrue(sql.contains("address_name like concat('%', ?, '%')"), sql);
        assertTrue(sql.contains("end_date >= ?"), sql);
        assertTrue(sql.contains("start_date <= ?"), sql);
        assertTrue(sql.contains("merchant_chapter_offer"), sql);
        assertTrue(sql.contains("coop_club_apply"), sql);
        assertTrue(sql.contains("cms_topic.lifecycle = 4"), sql);
        assertTrue(sql.contains("coop_complaint"), sql);
        assertTrue(sql.contains("complaint.status < 3"), sql);
    }

    @Test
    void partiallyReadyFilter_acceptsAnyLiveOffer_evenWhenItIsOptionalChapter() throws Exception {
        CmsTopic query = new CmsTopic();
        query.setMerchantSupplyState("PARTIALLY_READY");

        String sql = sql(query);

        assertTrue(sql.contains("from cms_topic_chapter supplied_chapter"), sql);
        assertTrue(sql.contains("supplied_chapter.topic_id = cms_topic.id"), sql);
        assertTrue(!sql.contains("supplied_chapter.required = 1"),
                "部分就绪不能排除仅在可选章节提供的有效供给。实际: " + sql);
    }

    @Test
    void selfPlayOnlyTopicsAreNotClassifiedAsClubRequired() throws Exception {
        CmsTopic query = new CmsTopic();
        query.setClubFulfillmentState("NOT_REQUIRED");
        String sql = sql(query);

        assertTrue(sql.contains("not exists ( select 1 from oms_ticket"), sql);
        assertTrue(sql.contains("lower(guided_ticket.sub_type) != 'self'"), sql);
        assertTrue(!sql.contains("self_managed"), sql);
    }
}

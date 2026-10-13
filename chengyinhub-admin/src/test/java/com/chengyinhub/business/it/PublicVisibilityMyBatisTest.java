package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiProjectController;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInterceptor;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 真实 MyBatis + 本地 MySQL fixture：公开可见性、分页 total 与活动归属投影。
 * fixture 不可达即失败，避免真实投影门禁在默认 Maven test 中静默跳过。
 */
class PublicVisibilityMyBatisTest {

    @ParameterizedTest
    @ValueSource(strings = {"list", "byId", "detail"})
    void publicQueriesHideDeletedOrMissingParentWithoutChangingOwnerAndRawReads(String query) throws Exception {
        try (SqlSession s = factory().openSession(true)) {
            Statement st = s.getConnection().createStatement();
            recreateClubTable(st);
            recreateActivityTable(st);
            recreateTopicTable(st);
            recreateCircleSupplyTables(st);
            seedClubs(st);
            seedActivities(st);
            seedTopics(st);
            st.execute("INSERT INTO cms_activity (id,member_id,name,status,publish_status,del_flag,topic_id,create_time)"
                    + " VALUES (106,7,'删除父主题',1,1,0,5,NOW()),(107,7,'缺失父主题',1,1,0,999,NOW())");
            st.execute("INSERT INTO cms_registration (id,owner_type,owner_id,member_id,registration_status,payment_status)"
                    + " VALUES (1,2,106,8,2,2)");
            CmsActivityMapper mapper = s.getMapper(CmsActivityMapper.class);
            if ("list".equals(query)) {
                PageHelper.startPage(1, 20);
                List<CmsActivity> rows = mapper.selectPublicCmsActivityList(new CmsActivity());
                assertEquals(4, ((Page<?>) rows).getTotal());
                assertFalse(rows.stream().anyMatch(a -> a.getId() == 106L || a.getId() == 107L));
            } else {
                for (long id : new long[]{106L, 107L}) {
                    assertNull("byId".equals(query) ? mapper.selectPublicCmsActivityById(id)
                            : mapper.selectCmsActivityDetailById(id, 8L));
                }
            }
            for (long id : new long[]{1L, 102L}) {
                assertEquals(id, mapper.selectPublicCmsActivityById(id).getId().longValue());
                assertEquals(id, mapper.selectCmsActivityDetailById(id, 8L).getId().longValue());
            }
            for (long id : new long[]{106L, 107L}) {
                assertEquals(id, mapper.selectCmsActivityDetailById(id, 7L).getId().longValue());
                assertEquals(id, mapper.selectCmsActivityById(id).getId().longValue());
            }
        }
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CmsActivity", CmsActivity.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("public_visibility"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        PageInterceptor interceptor = new PageInterceptor();
        Properties properties = new Properties();
        properties.setProperty("helperDialect", "mysql");
        interceptor.setProperties(properties);
        cfg.addInterceptor(interceptor);
        parse(cfg, "mapper/business/CmsActivityMapper.xml");
        parse(cfg, "mapper/business/CmsTopicMapper.xml");
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private void parse(Configuration cfg, String resource) throws Exception {
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
    }

    @Test
    void publicQueries_failClosedAndActivityProjectionDrivesOwnership() throws Exception {
        SqlSessionFactory f = factory();
        try (SqlSession probe = f.openSession()) {
            assertTrue(probe.getConnection().isValid(2), "真实 MySQL fixture 必须可达");
        }

        try (SqlSession s = f.openSession(true)) {
            Statement st = s.getConnection().createStatement();
            recreateClubTable(st);
            recreateActivityTable(st);
            recreateTopicTable(st);
            recreateCircleSupplyTables(st);
            seedClubs(st);
            seedActivities(st);
            seedTopics(st);

            CmsActivityMapper activityMapper = s.getMapper(CmsActivityMapper.class);
            CmsTopicMapper topicMapper = s.getMapper(CmsTopicMapper.class);

            CmsActivity forgedActivity = new CmsActivity();
            forgedActivity.setStatus(9L);
            forgedActivity.setPublishStatus(0L);
            forgedActivity.setCheckStatus(9);
            PageHelper.startPage(1, 20);
            List<CmsActivity> publicActivities = activityMapper.selectPublicCmsActivityList(forgedActivity);
            assertEquals(4, ((Page<?>) publicActivities).getTotal(), "分页 total 只能统计公开可见活动");
            assertEquals(4, publicActivities.size());
            assertTrue(publicActivities.stream().allMatch(a -> a.getStatus() == 1L
                    && a.getPublishStatus() == 1L && a.getDelFlag() == 0));
            assertEquals(1L, activityMapper.selectPublicCmsActivityById(1L).getId(),
                    "审核通过且已上架的活动详情可见");
            assertNull(activityMapper.selectPublicCmsActivityById(2L), "下架详情与缺失统一不可见");
            assertNull(activityMapper.selectPublicCmsActivityById(3L), "拒审详情不可见");
            assertNull(activityMapper.selectPublicCmsActivityById(4L), "待审详情不可见");
            assertNull(activityMapper.selectPublicCmsActivityById(5L), "删除详情不可见");
            assertNull(activityMapper.selectPublicCmsActivityById(6L), "未知状态必须 fail-closed");
            assertNull(activityMapper.selectPublicCmsActivityById(104L), "已解散俱乐部的场次不可成为幽灵公开数据");
            assertNull(activityMapper.selectPublicCmsActivityById(105L), "经主题归属已解散俱乐部的场次同样不可见");
            assertNull(activityMapper.selectCmsActivityDetailById(104L, 43L),
                    "即使是发布者也不能直达已解散俱乐部的场次");
            assertNull(activityMapper.selectCmsActivityDetailById(105L, 43L),
                    "经已解散父主题归属的场次详情同样不可直达");
            assertEquals(2L, activityMapper.selectCmsActivityDetailById(2L, 7L).getId(),
                    "现有 owner 身份 seam 可预览自己的下架活动");
            assertNull(activityMapper.selectCmsActivityDetailById(2L, 8L),
                    "非 owner 不能借详情 seam 看见下架活动");
            CmsActivity topicActivityQuery = new CmsActivity();
            topicActivityQuery.setTopicId(900L);
            List<CmsActivity> topicActivities = activityMapper.selectPublicCmsActivityList(topicActivityQuery);
            assertEquals(1, topicActivities.size(), "主题详情只能附带本主题的公开活动场次");
            assertEquals(102L, topicActivities.get(0).getId());
            assertEquals(2, topicActivities.get(0).getProductType(),
                    "公开活动列表必须投影所属主题类型，首页活动 Feed 才能显示自由探索而不是空白");

            CmsTopic forgedTopic = new CmsTopic();
            forgedTopic.setStatus(9);
            forgedTopic.setUserStatus(0);
            forgedTopic.setCheckStatus(9);
            PageHelper.startPage(1, 20);
            List<CmsTopic> publicTopics = topicMapper.selectPublicCmsTopicList(forgedTopic);
            assertEquals(1, ((Page<?>) publicTopics).getTotal(), "主题分页 total 不含隐藏项");
            assertEquals(1L, publicTopics.get(0).getId());
            assertEquals(1L, topicMapper.selectPublicCmsTopicById(1L).getId(),
                    "审核通过且已上架的主题详情可见");
            assertNull(topicMapper.selectPublicCmsTopicById(2L));
            assertNull(topicMapper.selectPublicCmsTopicById(3L));
            assertNull(topicMapper.selectPublicCmsTopicById(4L));
            assertNull(topicMapper.selectPublicCmsTopicById(5L));
            assertNull(topicMapper.selectPublicCmsTopicById(6L));
            assertNull(topicMapper.selectPublicCmsTopicById(7L), "已解散俱乐部的主题不可成为幽灵公开数据");
            assertNull(topicMapper.selectCmsTopicDetailById(7L, 43L),
                    "即使是发布者也不能直达已解散俱乐部的主题");
            assertNull(topicMapper.selectMerchantVisibleCmsTopicById(7L),
                    "商家详情入口也必须隐藏已解散俱乐部的主题");
            CmsTopic consumerTopicQuery = new CmsTopic();
            consumerTopicQuery.setMemberId(43L);
            consumerTopicQuery.setActiveClubOnly(true);
            assertTrue(topicMapper.selectCmsTopicList(consumerTopicQuery).isEmpty(),
                    "玩家我发布的列表不得保留已解散俱乐部主题");
            CmsTopic merchantSquareQuery = new CmsTopic();
            merchantSquareQuery.setStatus(1);
            merchantSquareQuery.setMerchantStatus(1);
            merchantSquareQuery.setDelFlag(0);
            merchantSquareQuery.setActiveClubOnly(true);
            assertEquals(1, topicMapper.selectCmsTopicList(merchantSquareQuery).size(),
                    "商家招募广场不得出现已解散俱乐部主题");
            assertEquals(2L, topicMapper.selectCmsTopicDetailById(2L, 7L).getId(),
                    "现有 owner 身份 seam 可预览自己的下架主题");
            assertNull(topicMapper.selectCmsTopicDetailById(2L, 8L),
                    "非 owner 不能借详情 seam 看见下架主题");
            assertEquals(1L, topicMapper.selectMerchantVisibleCmsTopicById(1L).getId());
            assertNull(topicMapper.selectMerchantVisibleCmsTopicById(2L), "商家端下架详情不可见");
            assertNull(topicMapper.selectMerchantVisibleCmsTopicById(3L), "拒审详情不可见");
            assertNull(topicMapper.selectMerchantVisibleCmsTopicById(5L), "删除详情不可见");
            assertNull(topicMapper.selectMerchantVisibleCmsTopicById(6L), "未知商家状态不可见");

            CmsActivity ownerQuery = new CmsActivity();
            ownerQuery.setMemberId(42L);
            List<CmsActivity> ownerRows = activityMapper.selectCmsActivityList(ownerQuery);
            assertEquals(3, ownerRows.size());
            assertEquals(42L, ownerRows.get(0).getMemberId());
            assertTrue(ownerRows.stream().anyMatch(a -> Long.valueOf(700L).equals(a.getClubId())),
                    "俱乐部归属所需 club_id 必须来自真实投影");
            assertTrue(ownerRows.stream().anyMatch(a -> Long.valueOf(900L).equals(a.getTopicId())),
                    "商家中标归属所需 topic_id 必须来自真实投影");
            CmsActivity dissolvedOwnerQuery = new CmsActivity();
            dissolvedOwnerQuery.setMemberId(43L);
            dissolvedOwnerQuery.setActiveClubOnly(true);
            assertTrue(activityMapper.selectCmsActivityList(dissolvedOwnerQuery).isEmpty(),
                    "玩家我发布的活动列表不得保留已解散俱乐部场次");

            st.execute("INSERT INTO cms_registration "
                    + "(id,owner_type,owner_id,member_id,registration_status,payment_status,verification_status)"
                    + " VALUES (1001,2,102,81,2,2,1),(1002,2,102,82,2,2,0)");
            assertProjectOwnership(activityMapper);
        }
    }

    @SuppressWarnings("unchecked")
    private void assertProjectOwnership(CmsActivityMapper activityMapper) {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        CmsActivityServiceImpl activityService = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(activityService, "cmsActivityMapper", activityMapper);
        ICmsRegistrationMerchantService merchantService = mock(ICmsRegistrationMerchantService.class);
        ApiProjectController controller = spy(new ApiProjectController());
        doReturn(42L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", merchantService);
        com.chengyinhub.business.service.ICoopInviteService inviteService =
                mock(com.chengyinhub.business.service.ICoopInviteService.class);
        ReflectionTestUtils.setField(controller, "coopInviteService", inviteService);
        when(inviteService.selectCoopInviteList(any())).thenReturn(Collections.emptyList());
        when(topicService.selectDraftTopicIds(anyLong())).thenReturn(Collections.emptyList());
        when(topicService.selectCmsTopicList(any())).thenReturn(Collections.emptyList());
        CmsRegistrationMerchant won = new CmsRegistrationMerchant();
        won.setTopicId(900L);
        when(merchantService.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(won));

        AjaxResult result = controller.my(null, null, null, 1, 20, null);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        assertTrue(rows.stream().anyMatch(r -> "club".equals(r.get("ownerType"))));
        assertTrue(rows.stream().anyMatch(r -> "merchant".equals(r.get("ownerType"))));
        assertTrue(rows.stream().anyMatch(r -> "member".equals(r.get("ownerType"))));
        Map<String, Object> merchantRow = rows.stream()
                .filter(r -> Long.valueOf(102L).equals(r.get("id"))).findFirst().orElseThrow();
        assertEquals("merchant", merchantRow.get("ownerType"));
        assertEquals(2, merchantRow.get("signupCount"), "报名数必须来自真实批量统计");
        assertEquals(1, merchantRow.get("pendingVerifyCount"), "已核销报名不得计入待核销");
    }

    /**
     * 「只对成员可见」这道闸真跑一遍 SQL。
     *
     * <p>写这条是因为 club_member_only 从加列那天起就只有写没有读:主题设置页写着
     * 「开启后非成员看不到这条主题」,而四个读口没有一个过滤它 —— 开关是装饰,承诺是假的。
     * 用字符串断言 SQL 不够:列名写错、OR 的括号错位、子查询连错表,都能让断言绿着而闸是空的。
     * 所以这条走真库,喂五种身份读回来。
     */
    @Test
    void clubMemberOnlyTopicsAreHiddenFromEveryoneButMembersOwnerAndBuyers() throws Exception {
        SqlSessionFactory f = factory();
        try (SqlSession s = f.openSession(true)) {
            Statement st = s.getConnection().createStatement();
            recreateClubTable(st);
            recreateTopicTable(st);
            recreateCircleSupplyTables(st);
            seedClubs(st);
            st.execute("INSERT INTO cms_topic (id,member_id,club_id,product_type,name,status,user_status,"
                    + "merchant_status,del_flag,check_status,club_pinned,club_member_only,create_time) VALUES "
                    + "(800,90,700,1,'只对成员可见',1,1,1,0,0,0,1,NOW()),"
                    + "(801,90,700,1,'公开且置顶',1,1,1,0,0,1,0,NOW()),"
                    + "(802,90,700,1,'只对成员可见但有人买过',1,1,1,0,0,0,1,NOW())");
            st.execute("INSERT INTO club_member (id,club_id,member_id,status,del_flag) VALUES "
                    + "(1,700,91,1,0),(2,700,92,0,0)");
            recreateActivityTable(st);
            // 800 这条主题开过团:主理人开团产出的是场次(cms_activity),报名落 owner_type=2。
            st.execute("INSERT INTO cms_activity (id,member_id,topic_id,club_id,status,publish_status,del_flag,create_time)"
                    + " VALUES (9002,90,800,700,1,1,0,NOW())");
            st.execute("INSERT INTO cms_registration (id,owner_type,owner_id,member_id,topic_id,registration_status,payment_status)"
                    + " VALUES (1,1,802,93,NULL,2,2),"       // 93:主题级买过 802
                    + "        (2,2,9002,95,NULL,2,2),"      // 95:场次级买过 800 —— 只认 owner_type=1 的话这条会被漏掉
                    + "        (3,1,800,96,NULL,3,2),"       // 96:买过 800 但已取消(registration_status=3)
                    + "        (4,1,800,97,NULL,2,2)");      // 97:买过 800 的记录随后被物理删除
            // cms_registration 没有软删列,删除就是物理 DELETE(CmsRegistrationMapper.deleteCmsRegistrationById)。
            st.execute("DELETE FROM cms_registration WHERE id=4");

            CmsTopicMapper mapper = s.getMapper(CmsTopicMapper.class);

            assertEquals(Collections.singletonList(801L), clubTopicIds(mapper, null),
                    "没登录的人只该看到公开那条");
            assertEquals(Collections.singletonList(801L), clubTopicIds(mapper, 94L),
                    "登录了但不是成员,一样看不到");
            assertEquals(Collections.singletonList(801L), clubTopicIds(mapper, 92L),
                    "status=0 的成员记录不算成员(退群/待审都落在这)");
            assertEquals(Arrays.asList(801L, 802L, 800L), clubTopicIds(mapper, 91L),
                    "成员三条都看得到,且置顶那条排最前");
            assertEquals(Arrays.asList(801L, 802L, 800L), clubTopicIds(mapper, 90L),
                    "主理人不在 club_member 里,要单独认");
            assertEquals(Arrays.asList(801L, 802L), clubTopicIds(mapper, 93L),
                    "已付款的人保住自己买过的那条,别的只对成员可见主题仍看不到");
            assertEquals(Arrays.asList(801L, 800L), clubTopicIds(mapper, 95L),
                    "★买的是场次(owner_type=2)也算买过这条主题 —— 俱乐部开团产出的正是场次");
            assertEquals(Collections.singletonList(801L), clubTopicIds(mapper, 96L),
                    "已取消的报名不算买过");
            assertEquals(Collections.singletonList(801L), clubTopicIds(mapper, 97L),
                    "已删除的报名不算买过");

            CmsTopic wholeFeed = new CmsTopic();
            List<Long> feedIds = new ArrayList<>();
            for (CmsTopic row : mapper.selectPublicCmsTopicList(wholeFeed)) feedIds.add(row.getId());
            assertFalse(feedIds.contains(800L), "推荐流/主题页签是同一条 SQL,只堵俱乐部 tab 等于没堵");
            assertTrue(feedIds.contains(801L), "公开的俱乐部主题照常进公开流");
            assertEquals(801L, feedIds.get(0).longValue(),
                    "不限定俱乐部时不许按 club_pinned 排 —— 否则某个俱乐部的置顶会浮到全站最前");

            assertNull(mapper.selectCmsTopicDetailById(800L, null), "分享链接不能绕过闸");
            assertNull(mapper.selectCmsTopicDetailById(800L, 94L), "非成员打不开详情");
            assertEquals(800L, mapper.selectCmsTopicDetailById(800L, 91L).getId(), "成员打得开");
        }
    }

    private List<Long> clubTopicIds(CmsTopicMapper mapper, Long viewerMemberId) {
        CmsTopic probe = new CmsTopic();
        probe.setClubId(700L);
        probe.setViewerMemberId(viewerMemberId);
        List<Long> ids = new ArrayList<>();
        for (CmsTopic row : mapper.selectPublicCmsTopicList(probe)) ids.add(row.getId());
        return ids;
    }

    private void recreateActivityTable(Statement st) throws Exception {
        st.execute("DROP TABLE IF EXISTS cms_activity");
        st.execute("CREATE TABLE cms_activity ("
                + "id BIGINT PRIMARY KEY, member_id BIGINT, name VARCHAR(100), description TEXT, img_url VARCHAR(255),"
                + "img_arr TEXT, category_ids VARCHAR(100), address_name VARCHAR(100), longitude VARCHAR(30), latitude VARCHAR(30),"
                + "address VARCHAR(255), status BIGINT, publish_status BIGINT, cancel_time DATETIME, cancel_reason VARCHAR(255),"
                + "reason VARCHAR(255), del_flag INT, start_date DATETIME, end_date DATETIME, template_id BIGINT,"
                + "topic_id BIGINT, club_id BIGINT, check_status INT, is_settlement INT, create_by VARCHAR(64),"
                + "create_time DATETIME, update_by VARCHAR(64), update_time DATETIME)");
    }

    private void recreateClubTable(Statement st) throws Exception {
        st.execute("DROP TABLE IF EXISTS club");
        st.execute("CREATE TABLE club (id BIGINT PRIMARY KEY, member_id BIGINT, del_flag INT)");
        // 只对成员可见那道闸要认三种人:成员、主理人(club.member_id)、已付款的人。
        st.execute("DROP TABLE IF EXISTS club_member");
        st.execute("CREATE TABLE club_member ("
                + "id BIGINT PRIMARY KEY, club_id BIGINT, member_id BIGINT, status INT, del_flag INT)");
        // 活动详情现在会回答「当前这个人报过名没有」(has_signup),用到报名表。
        // 只建判据用得上的那几列 —— 判据本身照抄 ClubEventOpsMapper.countActiveRegistration。
        st.execute("DROP TABLE IF EXISTS cms_registration");
        st.execute("CREATE TABLE cms_registration ("
                + "id BIGINT PRIMARY KEY, owner_type INT, owner_id BIGINT, member_id BIGINT,"
                + " topic_id BIGINT,"
                + " registration_status INT, payment_status INT, verification_status INT)");
    }

    private void seedClubs(Statement st) throws Exception {
        st.execute("INSERT INTO club (id,member_id,del_flag) VALUES (700,90,0),(701,90,1)");
    }

    private void recreateTopicTable(Statement st) throws Exception {
        st.execute("DROP TABLE IF EXISTS cms_topic");
        st.execute("CREATE TABLE cms_topic ("
                + "id BIGINT PRIMARY KEY, member_id BIGINT, club_id BIGINT, topic_type INT, product_type INT,"
                + "circle_theme_code VARCHAR(24), circle_reviewed_at DATETIME, is_template INT NOT NULL DEFAULT 0,"
                + "source_template_topic_id BIGINT, complete_rule_json JSON, route_mode VARCHAR(20) NOT NULL DEFAULT 'LINEAR',"
                + "route_graph_json JSON, config_version BIGINT NOT NULL DEFAULT 1,"
                + "published_route_graph_json JSON, published_config_version BIGINT NOT NULL DEFAULT 0,"
                + "game_module_config_json JSON,"
                + "journey_rules_json JSON,"
                + "journey_story_json JSON, theme_runtime_config_json MEDIUMTEXT, published_theme_runtime_config_json MEDIUMTEXT,"
                + "roam_referral_enabled INT NOT NULL DEFAULT 0,"
                + "fee_platform_rate DECIMAL(10,4), fee_guarantee_base_rate DECIMAL(10,4), fee_guarantee_step_rate DECIMAL(10,4),"
                + "fee_guarantee_max_merchant INT, topic_id BIGINT, name VARCHAR(100), subtitle VARCHAR(100), description TEXT,"
                + "address_name VARCHAR(100), total_time INT, total_mileage DECIMAL(10,2), location_count INT, template_count INT,"
                + "start_date DATETIME, end_date DATETIME, img_url VARCHAR(255), img_arr TEXT, category_ids VARCHAR(100),"
                + "status INT, reason VARCHAR(255), user_status INT, merchant_status INT, open_club_pool INT,"
                + "merchant_sign_up_start_date DATETIME, merchant_sign_up_end_date DATETIME, del_flag INT, is_sync INT,"
                + "is_recommend INT, check_status INT, is_settlement INT, publish_mode VARCHAR(32), audio_url VARCHAR(255),"
                + "audio_duration INT, self_play INT, self_play_price DECIMAL(10,2), self_play_quota INT,"
                + "team_mode INT NOT NULL DEFAULT 0, team_max_members INT NOT NULL DEFAULT 4, xp_budget INT,"
                + "finish_medal_name VARCHAR(100), finish_medal_img VARCHAR(255), complete_reward_coupon_id BIGINT,"
                + "beta_flag TINYINT NOT NULL DEFAULT 0,"
                + "club_coop_open TINYINT NOT NULL DEFAULT 1, club_pinned TINYINT NOT NULL DEFAULT 0,"
                + "club_member_only TINYINT NOT NULL DEFAULT 0,"
                + "recruit_deadline DATETIME, recruit_extend_count INT, lifecycle INT, sale_end_at DATETIME, selling_started_at DATETIME, source_topic_id BIGINT, create_by VARCHAR(64), create_time DATETIME,"
                + "update_by VARCHAR(64), update_time DATETIME)");
    }

    private void recreateCircleSupplyTables(Statement st) throws Exception {
        st.execute("DROP TABLE IF EXISTS merchant_circle_supply_profile");
        st.execute("DROP TABLE IF EXISTS merchant_chapter_offer");
        st.execute("DROP TABLE IF EXISTS cms_topic_chapter");
        st.execute("CREATE TABLE cms_topic_chapter (id BIGINT PRIMARY KEY, circle_enabled INT NOT NULL DEFAULT 0)");
        st.execute("CREATE TABLE merchant_chapter_offer ("
                + "id BIGINT PRIMARY KEY, topic_id BIGINT, chapter_id BIGINT, merchant_id BIGINT,"
                + "status INT, valid_end DATETIME)");
        st.execute("CREATE TABLE merchant_circle_supply_profile ("
                + "id BIGINT PRIMARY KEY, offer_id BIGINT, is_current INT NOT NULL DEFAULT 0, checked_at DATETIME)");
        // selectCmsTopicList 的 clubId 分支现在还认"本俱乐部承接的执行单",所以这两张表
        // 必须跟着建 —— 少一张整条查询直接 SQLSyntaxError,而不是判定错,更不会自己报出来。
        st.execute("DROP TABLE IF EXISTS coop_order");
        st.execute("DROP TABLE IF EXISTS coop_invite");
        st.execute("CREATE TABLE coop_order ("
                + "id BIGINT PRIMARY KEY, topic_id BIGINT, invite_id BIGINT, to_type VARCHAR(16),"
                + "to_id BIGINT, status INT, del_flag INT DEFAULT 0)");
        st.execute("CREATE TABLE coop_invite ("
                + "id BIGINT PRIMARY KEY, invite_type INT)");
    }

    private void seedActivities(Statement st) throws Exception {
        st.execute("INSERT INTO cms_activity (id,member_id,name,status,publish_status,del_flag,topic_id,club_id,check_status,create_time) VALUES "
                + "(1,7,'公开',1,1,0,NULL,NULL,0,NOW()),"
                + "(2,7,'下架',1,0,0,NULL,NULL,0,NOW()),"
                + "(3,7,'拒审',2,1,0,NULL,NULL,0,NOW()),"
                + "(4,7,'待审',0,1,0,NULL,NULL,0,NOW()),"
                + "(5,7,'删除',1,1,2,NULL,NULL,0,NOW()),"
                + "(6,7,'未知',9,1,0,NULL,NULL,0,NOW()),"
                + "(101,42,'俱乐部',1,1,0,NULL,700,0,NOW()),"
                + "(102,42,'商家承接',1,1,0,900,NULL,0,NOW()),"
                + "(103,42,'会员自办',1,1,0,NULL,NULL,0,NOW()),"
                + "(104,43,'已解散俱乐部场次',1,1,0,NULL,701,0,NOW()),"
                + "(105,43,'已解散俱乐部主题场次',1,1,0,7,NULL,0,NOW())");
    }

    private void seedTopics(Statement st) throws Exception {
        st.execute("INSERT INTO cms_topic (id,member_id,club_id,product_type,name,status,user_status,merchant_status,del_flag,check_status,create_time) VALUES "
                + "(1,7,NULL,1,'公开主题',1,1,1,0,0,NOW()),"
                + "(2,7,NULL,1,'下架主题',1,0,0,0,0,NOW()),"
                + "(3,7,NULL,1,'拒审主题',2,1,1,0,0,NOW()),"
                + "(4,7,NULL,1,'待审主题',0,1,1,0,0,NOW()),"
                + "(5,7,NULL,1,'删除主题',1,1,1,2,0,NOW()),"
                + "(6,7,NULL,1,'未知主题',9,1,9,0,0,NOW()),"
                + "(7,43,701,1,'已解散俱乐部主题',1,1,1,0,0,NOW()),"
                + "(900,42,NULL,2,'自由探索承接主题',0,0,0,0,0,NOW())");
    }
}

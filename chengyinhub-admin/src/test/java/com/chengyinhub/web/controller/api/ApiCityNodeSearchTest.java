package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IMerchantNodeDiscoveryService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiCityNodeSearchTest {

    @Test
    void repeatedCompleteReturnsFrozenPendingCouponInsteadOfCurrentTemplate() {
        RoamMapper mapper = mock(RoamMapper.class);
        ApiCityNodeController player = new ApiCityNodeController() {
            @Override public Long getAppUserId() { return 7001L; }
        };
        ReflectionTestUtils.setField(player, "roamMapper", mapper);
        CityNodeVO node = new CityNodeVO();
        node.setPoiId(21L);
        node.setStatus(1);
        node.setValidationMethod(5);
        node.setLat(java.math.BigDecimal.valueOf(31.2));
        node.setLng(java.math.BigDecimal.valueOf(121.5));
        when(mapper.selectPlayerMerchantNodeDetail(21L, 0L)).thenReturn(node);
        when(mapper.selectPendingCityNodeCouponId(21L, 7001L)).thenReturn(88L);
        java.util.Map<?, ?> pending = (java.util.Map<?, ?>) player.complete(21L, 31.2, 121.5, "", "", "").get(AjaxResult.DATA_TAG);
        assertEquals(true, pending.get("alreadyClaimed"));
        assertEquals(88L, pending.get("couponId"));
        assertEquals(true, pending.get("needRedeem"));
        node.setCouponId(99L);
        when(mapper.selectPendingCityNodeCouponId(21L, 7001L)).thenReturn(null);
        java.util.Map<?, ?> completed = (java.util.Map<?, ?>) player.complete(21L, 31.2, 121.5, "", "", "").get(AjaxResult.DATA_TAG);
        assertNull(completed.get("couponId"));
        assertEquals(false, completed.get("needRedeem"));
    }


    @Test
    void playerDetailRequiresPublicContentOrOwnCheckinAndDoesNotHidePendingOwnershipClaims() throws Exception {
        try (SqlSession session = cityNodeSqlSessionFactory().openSession()) {
            Statement statement = session.getConnection().createStatement();
            statement.execute("update roam_poi set status=0, application_type=1, audit_status=0 where id=1");
            statement.execute("update roam_poi set application_type=2, audit_status=0 where id=2");
            statement.execute("update roam_poi set status=0, application_type=1, audit_status=1 where id=3");
            statement.execute("update roam_poi set status=1, application_type=1, audit_status=2 where id=4");
            ApiCityNodeController player = new ApiCityNodeController() {
                @Override public Long getAppUserId() { return 7001L; }
            };
            ReflectionTestUtils.setField(player, "roamMapper", session.getMapper(RoamMapper.class));
            ReflectionTestUtils.setField(player, "collectibleMapper",
                    mock(com.chengyinhub.business.mapper.MemberCollectibleMapper.class));
            assertEquals(410, player.detail(1L).get(AjaxResult.CODE_TAG), "待审内容不公开");
            assertEquals(200, player.detail(2L).get(AjaxResult.CODE_TAG), "认领审核不能隐藏既有公开内容");
            assertEquals(410, player.detail(3L).get(AjaxResult.CODE_TAG), "普通旧链接不可读下线内容");
            assertEquals(410, player.detail(4L).get(AjaxResult.CODE_TAG), "驳回投放不能靠脏status上线");
            statement.execute("insert into roam_poi_reward_log(poi_id,member_id,coupon_id,status) values(3,7002,88,0)");
            session.clearCache();
            assertEquals(410, player.detail(3L).get(AjaxResult.CODE_TAG), "他人的待履约记录不能授权我");
            statement.execute("insert into roam_poi_reward_log(poi_id,member_id,coupon_id,status) values(3,7001,88,0)");
            session.clearCache();
            assertEquals(200, player.detail(3L).get(AjaxResult.CODE_TAG), "本人下线前打卡的履约内容仍可访问");
        }
    }

    @Test
    void mapperAppliesAllDiscoveryFiltersAndReturnsNearestNodeFirst() throws Exception {
        SqlSessionFactory factory = cityNodeSqlSessionFactory();

        try (SqlSession session = factory.openSession()) {
            List<CityNodeVO> nodes = session.getMapper(RoamMapper.class).selectMerchantNodesNear(
                    31.20, 31.27, 121.44, 121.51,
                    31.2304, 121.4737,
                    "咖啡", 9, "夜间友好", "街区客厅");

            assertEquals(Arrays.asList(1L, 2L), nodes.stream()
                    .map(CityNodeVO::getPoiId)
                    .collect(Collectors.toList()));
            assertTrue(nodes.get(0).getDistance() < nodes.get(1).getDistance(),
                    "距离必须由查询计算并升序返回");

            RoamMapper mapper = session.getMapper(RoamMapper.class);
            assertEquals(1, mapper.updateMerchantPoiTags(101L, "凌晨友好"));
            List<CityNodeVO> synced = mapper.selectMerchantNodesNear(
                    31.20, 31.27, 121.44, 121.51,
                    31.2304, 121.4737, null, null, "凌晨友好", null);
            assertEquals(Collections.singletonList(1L), synced.stream()
                    .map(CityNodeVO::getPoiId).collect(Collectors.toList()));
            assertEquals(1L, mapper.selectActiveMerchantNode(101L).getPoiId());
        }
    }

    @Test
    void nodesForwardsDiscoveryFiltersToGeoSortedMerchantNodeQuery() {
        RoamMapper mapper = mock(RoamMapper.class);
        ApiCityNodeController controller = new ApiCityNodeController();
        ReflectionTestUtils.setField(controller, "roamMapper", mapper);

        CityNodeVO node = new CityNodeVO();
        node.setPoiId(7L);
        node.setMerchantPhone("13800000000");
        when(mapper.selectMerchantNodesNear(
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Collections.singletonList(node));

        AjaxResult result = controller.nodes(31.2304, 121.4737, 3000,
                "咖啡", 9, "夜间友好", "街区客厅");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        List<CityNodeVO> data = (List<CityNodeVO>) result.get(AjaxResult.DATA_TAG);
        assertNull(data.get(0).getMerchantPhone(), "公开搜索结果不得泄露商家联系电话");
        verify(mapper).selectMerchantNodesNear(
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.eq(31.2304), org.mockito.ArgumentMatchers.eq(121.4737),
                org.mockito.ArgumentMatchers.eq("咖啡"), org.mockito.ArgumentMatchers.eq(9),
                org.mockito.ArgumentMatchers.eq("夜间友好"), org.mockito.ArgumentMatchers.eq("街区客厅"));
    }

    @Test
    void merchantNode_returnsOnlyActiveNodeForPublicProfileFavorite() {
        IMerchantNodeDiscoveryService discoveryService = mock(IMerchantNodeDiscoveryService.class);
        ApiCityNodeController controller = new ApiCityNodeController();
        ReflectionTestUtils.setField(controller, "merchantNodeDiscoveryService", discoveryService);
        ReflectionTestUtils.setField(controller, "collectibleMapper",
                mock(com.chengyinhub.business.mapper.MemberCollectibleMapper.class));
        CityNodeVO active = new CityNodeVO(); active.setPoiId(2L); active.setStatus(1);
        when(discoveryService.findActiveNode(8L)).thenReturn(active);

        AjaxResult result = controller.merchantNode(8L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(2L, ((CityNodeVO) result.get(AjaxResult.DATA_TAG)).getPoiId());
        verify(discoveryService).findActiveNode(8L);
    }

    private SqlSessionFactory cityNodeSqlSessionFactory() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:city_node_search_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table mms_merchant ("
                    + "id bigint primary key, name varchar(100), logo varchar(255), address varchar(255), "
                    + "phone varchar(32), category_id bigint, city_role varchar(100))");
            statement.execute("create table cms_member_template ("
                    + "id bigint primary key, title varchar(100), description varchar(255), interaction_type varchar(32), "
                    + "validation_method varchar(32), coupon_id bigint, photo_require_desc varchar(255), "
                    // vm=3 选项问答的题面与选项(玩家侧要看到)。⚠️ correct_answer 不进这张投影 —— 选进来就是泄题。
                    + "question_name varchar(255), question_a varchar(255), question_b varchar(255), "
                    + "question_c varchar(255), question_d varchar(255))");
            statement.execute("create table roam_poi ("
                    + "id bigint primary key, name varchar(100), description varchar(255), lat double, lng double, "
                    + "address varchar(255), gallery_json text, quotes_json text, photo_credit text, photo_required int, badge_code varchar(80), "
                    + "radius_m int, node_level int, tags varchar(255), cover_img varchar(255), city_code varchar(32), "
                    + "status int, audit_status int, application_type int, type int, merchant_id bigint, template_id bigint, sort int)");
            statement.execute("create table roam_poi_reward_log (poi_id bigint, member_id bigint, coupon_id bigint, status int)");
            statement.execute("create table ums_comment (owner_type int, owner_id bigint, rating int, status int, del_flag int, reply_id bigint)");

            insertMerchant(statement, 101, "咖啡一号", 9, "街区客厅");
            insertMerchant(statement, 102, "咖啡二号", 9, "街区客厅");
            insertMerchant(statement, 103, "茶铺", 9, "街区客厅");
            insertMerchant(statement, 104, "咖啡异品类", 8, "街区客厅");
            insertMerchant(statement, 105, "咖啡异标签", 9, "街区客厅");
            insertMerchant(statement, 106, "咖啡异角色", 9, "补给站");

            insertPoi(statement, 1, 101, 31.2305, 121.4738, 1, "夜间友好");
            insertPoi(statement, 2, 102, 31.2500, 121.4900, 4, "夜间友好");
            insertPoi(statement, 3, 103, 31.2350, 121.4780, 2, "夜间友好");
            insertPoi(statement, 4, 104, 31.2360, 121.4790, 2, "夜间友好");
            insertPoi(statement, 5, 105, 31.2370, 121.4800, 2, "亲子友好");
            insertPoi(statement, 6, 106, 31.2380, 121.4810, 2, "夜间友好");
        }

        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("RoamPoi", RoamPoi.class);
        configuration.getTypeAliasRegistry().registerAlias("RoamSession", RoamSession.class);
        try (InputStream mapperXml = ApiCityNodeSearchTest.class
                .getResourceAsStream("/mapper/business/RoamMapper.xml")) {
            XMLMapperBuilder builder = new XMLMapperBuilder(mapperXml, configuration,
                    "mapper/business/RoamMapper.xml", configuration.getSqlFragments());
            builder.parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void insertMerchant(Statement statement, long id, String name,
                                long categoryId, String cityRole) throws Exception {
        statement.execute("insert into mms_merchant "
                + "(id, name, category_id, city_role) values (" + id + ", '" + name + "', "
                + categoryId + ", '" + cityRole + "')");
    }

    private void insertPoi(Statement statement, long id, long merchantId,
                           double lat, double lng, int nodeLevel, String tags) throws Exception {
        statement.execute("insert into roam_poi "
                + "(id, name, lat, lng, radius_m, node_level, tags, city_code, status, type, merchant_id, sort) "
                + "values (" + id + ", '节点" + id + "', " + lat + ", " + lng + ", 80, "
                + nodeLevel + ", '" + tags + "', '310000', 1, 2, " + merchantId + ", " + id + ")");
    }
}

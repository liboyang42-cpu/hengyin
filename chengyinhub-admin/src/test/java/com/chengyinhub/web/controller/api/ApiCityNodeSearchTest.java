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
                    "咖啡", 9, "夜间友好", "街区客厅", 100);

            assertEquals(Arrays.asList(1L, 2L), nodes.stream()
                    .map(CityNodeVO::getPoiId)
                    .collect(Collectors.toList()));
            assertTrue(nodes.get(0).getDistance() < nodes.get(1).getDistance(),
                    "距离必须由查询计算并升序返回");

            RoamMapper mapper = session.getMapper(RoamMapper.class);
            assertEquals(1, mapper.updateMerchantPoiTags(101L, "[\"凌晨友好\"]"));
            List<CityNodeVO> synced = mapper.selectMerchantNodesNear(
                    31.20, 31.27, 121.44, 121.51,
                    31.2304, 121.4737, null, null, "凌晨友好", null, 100);
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
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Collections.singletonList(node));

        AjaxResult result = controller.nodes(31.2304, 121.4737, 3000,
                "咖啡", 9, "夜间友好", "街区客厅", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        List<CityNodeVO> data = (List<CityNodeVO>) result.get(AjaxResult.DATA_TAG);
        assertNull(data.get(0).getMerchantPhone(), "公开搜索结果不得泄露商家联系电话");
        verify(mapper).selectMerchantNodesNear(
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.eq(31.2304), org.mockito.ArgumentMatchers.eq(121.4737),
                org.mockito.ArgumentMatchers.eq("咖啡"), org.mockito.ArgumentMatchers.eq(9),
                org.mockito.ArgumentMatchers.eq("夜间友好"), org.mockito.ArgumentMatchers.eq("街区客厅"),
                org.mockito.ArgumentMatchers.eq(ApiCityNodeController.NODES_DEFAULT_LIMIT));
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

    @Test
    void nodesRejectsInvalidCoordinatesAndTagsAndClampsLimit() {
        RoamMapper mapper = mock(RoamMapper.class);
        ApiCityNodeController controller = new ApiCityNodeController();
        ReflectionTestUtils.setField(controller, "roamMapper", mapper);
        for (double[] bad : new double[][]{{Double.NaN, 121.4}, {31.2, Double.POSITIVE_INFINITY}, {91, 121.4}, {31.2, 181}}) {
            assertEquals("定位无效", controller.nodes(bad[0], bad[1], 3000, null, null, null, null, null).get(AjaxResult.MSG_TAG));
        }
        assertEquals("标签无效", controller.nodes(31.2, 121.4, 3000, null, null, "a%b", null, null).get(AjaxResult.MSG_TAG));
        assertEquals("标签无效", controller.nodes(31.2, 121.4, 3000, null, null, "a\"b", null, null).get(AjaxResult.MSG_TAG));
        org.mockito.Mockito.verifyNoInteractions(mapper);

        when(mapper.selectMerchantNodesNear(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Collections.emptyList());
        assertEquals(200, controller.nodes(31.2, 121.4, 3000, null, null, null, null, 99999).get(AjaxResult.CODE_TAG));
        verify(mapper).selectMerchantNodesNear(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(ApiCityNodeController.NODES_MAX_LIMIT));
    }

    @Test
    void nodesMapperLimitsRowsAndMatchesWholeJsonTagOnly() throws Exception {
        try (SqlSession session = cityNodeSqlSessionFactory().openSession()) {
            RoamMapper mapper = session.getMapper(RoamMapper.class);
            assertEquals(1, mapper.selectMerchantNodesNear(31.20, 31.27, 121.44, 121.51, 31.2304, 121.4737,
                    null, null, null, null, 1).size(), "limit 生效");
            assertEquals(0, mapper.selectMerchantNodesNear(31.20, 31.27, 121.44, 121.51, 31.2304, 121.4737,
                    null, null, "夜间", null, 100).size(), "子串不命中整标签");
            assertEquals(5, mapper.selectMerchantNodesNear(31.20, 31.27, 121.44, 121.51, 31.2304, 121.4737,
                    null, null, "夜间友好", null, 100).size());
        }
    }

    @Test
    void catalogPlaceDetailIsReadOnlyAndOnlyForListedSeedPlaces() throws Exception {
        try (SqlSession session = cityNodeSqlSessionFactory().openSession()) {
            Statement st = session.getConnection().createStatement();
            st.execute("insert into roam_poi (id,name,description,lat,lng,address,gallery_json,quotes_json,photo_credit,radius_m,node_level,tags,city_code,status,type,sort) "
                    + "values (900,'鲁迅公园','d',31.27,121.48,'地址','[\"a.jpg\"]','[{\"text\":\"q\"}]','CC BY',80,1,"
                    + "'[\"roam:park\",\"roam:seed:v1:sh-luxun-park\"]','SH',1,1,1)");
            st.execute("insert into roam_poi (id,name,lat,lng,tags,city_code,status,type,sort) values (901,'无种子',31.27,121.48,'[\"x\"]','SH',1,1,2)");
            st.execute("insert into roam_poi (id,name,lat,lng,tags,city_code,status,type,sort) values (902,'已下架',31.27,121.48,'[\"roam:seed:v1:z\"]','SH',0,1,3)");
            st.execute("insert into roam_poi (id,name,lat,lng,tags,city_code,status,type,merchant_id,sort) values (903,'商家点',31.27,121.48,'[\"roam:seed:v1:m\"]','SH',1,1,101,4)");
            ApiCityNodeController c = new ApiCityNodeController();
            ReflectionTestUtils.setField(c, "roamMapper", session.getMapper(RoamMapper.class));
            AjaxResult ok = c.place(900L);
            assertEquals(200, ok.get(AjaxResult.CODE_TAG));
            com.chengyinhub.business.domain.vo.CatalogPlaceVO vo = (com.chengyinhub.business.domain.vo.CatalogPlaceVO) ok.get(AjaxResult.DATA_TAG);
            assertEquals("CC BY", vo.getPhotoCredit());
            assertEquals("[{\"text\":\"q\"}]", vo.getQuotesJson());
            assertEquals("[\"a.jpg\"]", vo.getGalleryJson());
            for (long id : new long[]{901, 902, 903, 1}) assertEquals(410, c.place(id).get(AjaxResult.CODE_TAG), "id=" + id);
        }
    }

    @Test
    void catalogBatchStatusStaysWithinCityAndSeedCatalogRows() throws Exception {
        try (SqlSession session = cityNodeSqlSessionFactory().openSession()) {
            Statement st = session.getConnection().createStatement();
            st.execute("insert into roam_poi (id,name,lat,lng,tags,city_code,status,type,sort) values (910,'沪1',31.27,121.48,'[\"roam:seed:v1:sh-a\"]','SH',0,1,1)");
            st.execute("insert into roam_poi (id,name,lat,lng,tags,city_code,status,type,sort) values (911,'沪2',31.28,121.48,'[\"roam:seed:v1:sh-b\"]','SH',0,1,2)");
            st.execute("insert into roam_poi (id,name,lat,lng,tags,city_code,status,type,sort) values (912,'京1',39.9,116.4,'[\"roam:seed:v1:bj-a\"]','BJ',0,1,3)");
            st.execute("insert into roam_poi (id,name,lat,lng,tags,city_code,status,type,merchant_id,sort) values (913,'商家',31.27,121.48,'[\"roam:seed:v1:sh-m\"]','SH',0,1,101,4)");
            st.execute("insert into roam_poi (id,name,lat,lng,tags,city_code,status,type,sort) values (914,'无种子',31.27,121.48,'[\"x\"]','SH',0,1,5)");
            st.execute("insert into roam_poi (id,name,lat,lng,tags,city_code,status,type,application_type,audit_status,sort) values (915,'申请',31.27,121.48,'[\"roam:seed:v1:sh-app\"]','SH',0,1,1,0,6)");
            RoamMapper m = session.getMapper(RoamMapper.class);
            assertEquals(2, m.countCatalogBatchTargets("SH", null, 1));
            assertEquals(1, m.updateCatalogBatchStatus("SH", "roam:seed:v1:sh-a", 1));
            assertEquals(1, m.updateCatalogBatchStatus("SH", null, 1), "只剩 911 还没上架");
            assertEquals(0, m.updateCatalogBatchStatus("SH", null, 1), "幂等");
            assertEquals(1, statusOf(st, 910));
            assertEquals(1, statusOf(st, 911));
            assertEquals(0, statusOf(st, 912), "别的城市不动");
            assertEquals(0, statusOf(st, 913), "商家据点不动");
            assertEquals(0, statusOf(st, 914), "无种子标签不动");
            assertEquals(0, statusOf(st, 915), "商家申请行不动");
            assertEquals(2, m.updateCatalogBatchStatus("SH", null, 0), "可整城下架");
        }
    }

    private static int statusOf(Statement st, long id) throws Exception {
        try (java.sql.ResultSet rs = st.executeQuery("select status from roam_poi where id=" + id)) { rs.next(); return rs.getInt(1); }
    }

    private SqlSessionFactory cityNodeSqlSessionFactory() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:city_node_search_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table mms_merchant ("
                    + "id bigint primary key, name varchar(100), logo varchar(255), address varchar(255), "
                    + "phone varchar(32), category_id bigint, city_role varchar(100), "
                    + "status bigint default 1, account_status bigint default 1, del_flag int default 0)");
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
                + nodeLevel + ", '[\"" + tags + "\"]', '310000', 1, 2, " + merchantId + ", " + id + ")");
    }
}

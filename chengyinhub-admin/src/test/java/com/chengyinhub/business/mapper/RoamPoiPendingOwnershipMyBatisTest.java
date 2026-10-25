package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 待审 applicant 不是 effective owner；三道 owner SQL 必须各自 fail closed。 */
class RoamPoiPendingOwnershipMyBatisTest {
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("RoamPoi", RoamPoi.class);
        configuration.getTypeAliasRegistry().registerAlias("RoamSession", RoamSession.class);
        configuration.getTypeAliasRegistry().registerAlias("CityNodeVO", CityNodeVO.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:roam_pending_owner;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/RoamMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement statement = session.getConnection().createStatement()) {
            statement.execute("drop table if exists roam_poi");
            statement.execute("drop table if exists mms_merchant");
            statement.execute("drop table if exists cms_member_template");
            statement.execute("drop table if exists ums_comment");
            statement.execute("drop table if exists roam_poi_reward_log");
            statement.execute("drop table if exists member_collectible");
            statement.execute("create table ums_comment (owner_type bigint, owner_id bigint, rating int, reply_id bigint, status int, del_flag int)");
            statement.execute("create table roam_poi_reward_log (poi_id bigint, member_id bigint, coupon_id bigint, trigger_lat decimal(10,6), trigger_lng decimal(10,6), status int, create_time timestamp, claim_fingerprint varchar(100), photo_url varchar(2048), submitted_answer varchar(255), unique(poi_id,member_id))");
            statement.execute("create table member_collectible (member_id bigint, ref_id bigint, coll_type int, create_time timestamp)");
            statement.execute("create table roam_poi (id bigint primary key auto_increment, name varchar(80), type tinyint, lat decimal(10,6), lng decimal(10,6), radius_m int, address varchar(255), description varchar(255), xp int, coupon_template_id bigint, city_code varchar(20), status tinyint, create_time timestamp, merchant_id bigint, template_id bigint, node_level tinyint, tags varchar(255), cover_img varchar(500), sort int, audit_status tinyint, application_type tinyint, applicant_merchant_id bigint, audit_reason varchar(200), gallery_json text, quotes_json text, photo_credit text, photo_required int, badge_code varchar(80))");
            statement.execute("create table mms_merchant (id bigint primary key, name varchar(80), logo varchar(500), address varchar(255), phone varchar(50), member_id bigint)");
            statement.execute("create table cms_member_template (id bigint primary key, title varchar(80), description varchar(255), interaction_type varchar(40), validation_method int, coupon_id bigint, question_name varchar(255), question_a varchar(255), question_b varchar(255), question_c varchar(255), question_d varchar(255), photo_require_desc varchar(255))");
            statement.execute("insert into mms_merchant(id, member_id, name) values (88, 7, '申请商家')");
            statement.execute("insert into roam_poi(id, name, type, lat, lng, status, applicant_merchant_id, audit_status, application_type, merchant_id) values (101, '待审点', 2, 30.1, 120.2, 0, 88, 0, 1, null)");
        }
    }

    @Test
    void pendingApplicantCannotReadPoiThroughEffectiveOwnerListGate() {
        try (SqlSession session = factory.openSession(true)) {
            RoamMapper mapper = session.getMapper(RoamMapper.class);
            assertTrue(mapper.selectMyNodes(88L).isEmpty(),
                    "selectMyNodes 必须只认 merchant_id，不能把 applicant_merchant_id 当 owner");
        }
    }

    @Test
    void pendingApplicantCannotEditPoiThroughEffectiveOwnerUpdateGate() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            RoamMapper mapper = session.getMapper(RoamMapper.class);
            RoamPoi forged = new RoamPoi();
            forged.setId(101L);
            forged.setMerchantId(88L);
            forged.setName("越权改名");
            assertEquals(0, mapper.updateMerchantPoiOwned(forged),
                    "updateMerchantPoiOwned 必须由 merchant_id owner 闸返回 0");
            assertEquals("待审点", scalar(session, "select name from roam_poi where id=101"));
        }
    }

    @Test
    void pendingApplicantCannotTogglePoiThroughEffectiveOwnerStatusGate() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            RoamMapper mapper = session.getMapper(RoamMapper.class);
            assertEquals(0, mapper.setNodeStatusOwned(101L, 88L, 1),
                    "setNodeStatusOwned 必须由 merchant_id owner 闸返回 0");
            assertEquals("0", scalar(session, "select status from roam_poi where id=101"));
        }
    }

    @Test
    void pendingApplicationCannotUseAdminVisibilityLeverBeforeApproval() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            RoamMapper mapper = session.getMapper(RoamMapper.class);
            assertEquals(0, mapper.updatePoiStatus(101L, 1),
                    "updatePoiStatus 必须由 audit_status 闸拒绝待审申请上架");
            assertEquals("0", scalar(session, "select status from roam_poi where id=101"));
        }
    }

    @Test
    void pendingClaimCanStillBeTakenOfflineByGovernance() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            session.getConnection().createStatement().execute(
                    "insert into roam_poi(id, name, type, lat, lng, status, applicant_merchant_id, audit_status, application_type, merchant_id) "
                            + "values (103, '待审认领在架点', 1, 30.1, 120.2, 1, 88, 0, 2, null)");
            RoamMapper mapper = session.getMapper(RoamMapper.class);
            assertEquals(1, mapper.updatePoiStatus(103L, 0),
                    "审核期间的治理下架杆必须保留");
            assertEquals("0", scalar(session, "select status from roam_poi where id=103"));
        }
    }

    @Test
    void approvingClaimPreservesTheNodesCurrentVisibility() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            session.getConnection().createStatement().execute(
                    "insert into roam_poi(id, name, type, lat, lng, status, applicant_merchant_id, audit_status, application_type, merchant_id) "
                            + "values (102, '待审认领点', 1, 30.1, 120.2, 0, 88, 3, 2, null)");
            RoamMapper mapper = session.getMapper(RoamMapper.class);
            assertEquals(1, mapper.approveRoamApplication(102L, 2, 88L));
            assertEquals("0", scalar(session, "select status from roam_poi where id=102"),
                    "认领通过只转 owner，不得越过治理台重新上架已下架节点");
            assertEquals("88", scalar(session, "select merchant_id from roam_poi where id=102"));
        }
    }

    private String scalar(SqlSession session, String sql) throws Exception {
        try (Statement statement = session.getConnection().createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void officialApprovalEvidenceRatingAndFavoritesUseTheSamePoi() throws Exception {
        try (SqlSession session = factory.openSession(true); Statement sql = session.getConnection().createStatement()) {
            sql.execute("insert into cms_member_template(id,title,validation_method,question_name,question_a,question_b,photo_require_desc) values(81,'入口观察',3,'哪个字？','艺','海','拍摄入口')");
            RoamMapper mapper = session.getMapper(RoamMapper.class);
            RoamPoi poi = new RoamPoi(); poi.setName("街角艺术馆"); poi.setTemplateId(81L);
            poi.setGalleryJson("[\"curated/entrance.jpg\"]"); poi.setCoverImg("curated/entrance.jpg");
            poi.setAddress("测试街42号"); poi.setPhotoRequired(true);
            assertEquals(1, mapper.insertOfficialPoi(poi));
            assertEquals(null, mapper.selectPlayerMerchantNodeDetail(poi.getId(), 0L));
            assertEquals(0, mapper.updatePoiStatus(poi.getId(), 1));
            assertEquals(1, mapper.approveOfficialPoi(poi.getId(), "ROAM_POI_" + poi.getId()));
            assertEquals(0, mapper.approveOfficialPoi(poi.getId(), "ROAM_POI_" + poi.getId()));
            CityNodeVO visible = mapper.selectPlayerMerchantNodeDetail(poi.getId(), 0L);
            assertEquals(poi.getGalleryJson(), visible.getGalleryJson());
            assertEquals("测试街42号", visible.getMerchantAddress());
            assertEquals(true, visible.getPhotoRequired());
            assertEquals(1, mapper.insertRewardClaim(poi.getId(),7L,null,null,null,1,new java.util.Date(),"fixture-photo-visit"));
            assertEquals(1, mapper.savePoiEvidence(poi.getId(),7L,"owned-photo-url","A"));
            assertEquals(0, mapper.savePoiEvidence(poi.getId(),7L,"different-photo","B"));
            assertEquals("owned-photo-url", scalar(session,"select photo_url from roam_poi_reward_log where member_id=7"));
            sql.execute("insert into ums_comment values(4," + poi.getId() + ",4,0,1,0),(4," + poi.getId() + ",2,0,1,0),(4," + poi.getId() + ",5,0,0,0)");
            CityNodeVO rated = mapper.selectPlayerMerchantNodeDetail(poi.getId(),0L);
            assertEquals(3.0, rated.getAverageRating()); assertEquals(2,rated.getRatingCount());
            sql.execute("insert into member_collectible values(7," + poi.getId() + ",3,current_timestamp)");
            assertEquals(poi.getId(), mapper.selectFavoriteMerchantNodes(7L).get(0).getPoiId());
            assertEquals(1, mapper.updatePoiStatus(poi.getId(),0));
            assertEquals(null, mapper.selectPlayerMerchantNodeDetail(poi.getId(),0L));
            assertEquals(0, mapper.selectPlayerMerchantNodeDetail(poi.getId(),7L).getStatus());
        }
    }
}

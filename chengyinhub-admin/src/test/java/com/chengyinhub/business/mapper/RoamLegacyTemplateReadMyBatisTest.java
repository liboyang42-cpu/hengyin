package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Executes production XML against H2; no claim of MySQL/provider acceptance. */
class RoamLegacyTemplateReadMyBatisTest {
    private static class Fixture {
        final JdbcTemplate jdbc;
        final RoamMapper mapper;
        Fixture() throws Exception {
            DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:roam_"
                    + UUID.randomUUID().toString().replace("-", "")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(ds);
            jdbc.execute("create table cms_member_template(id bigint primary key,w18_provenance_kind varchar(32),title varchar(100),interaction_type varchar(32),validation_method varchar(32),coupon_id bigint,question_name varchar(100),question_a varchar(100),question_b varchar(100),question_c varchar(100),question_d varchar(100))");
            jdbc.execute("create table w18_cms_template_provenance(template_id bigint primary key)");
            jdbc.execute("create table roam_poi(id bigint primary key,name varchar(100),type int,lat double,lng double,radius_m int,address varchar(100),description varchar(100),xp int,coupon_template_id bigint,city_code varchar(32),status int,audit_status int,application_type int,applicant_merchant_id bigint,audit_reason varchar(100),create_time timestamp,merchant_id bigint,template_id bigint,node_level int,tags varchar(100),cover_img varchar(100),sort int)");
            jdbc.execute("create table mms_merchant(id bigint primary key,name varchar(100),logo varchar(100),address varchar(100),phone varchar(100),category_id int,city_role varchar(100))");
            jdbc.execute("create table member_collectible(ref_id bigint,member_id bigint,coll_type int,create_time timestamp)");
            jdbc.execute("create table roam_poi_reward_log(poi_id bigint,member_id bigint,coupon_id bigint,status int)");
            jdbc.execute("create table roam_poi_found(poi_id bigint,member_id bigint)");
            jdbc.execute("create table npc_profile(avatar varchar(100),scope_type int,scope_id bigint,enabled int,audit_status int,del_flag varchar(1),sort int,id bigint)");
            Configuration c = new Configuration();
            c.setEnvironment(new Environment("h2", new SpringManagedTransactionFactory(), ds));
            c.getTypeAliasRegistry().registerAlias("RoamPoi", RoamPoi.class);
            c.getTypeAliasRegistry().registerAlias("RoamSession", RoamSession.class);
            String resource = "mapper/business/RoamMapper.xml";
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in,c,resource,c.getSqlFragments()).parse();
            }
            mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c)).getMapper(RoamMapper.class);
        }
        void node(long id, Long templateId, String marker) {
            if (templateId != null && templateId > 0 && templateId != 999L) {
                jdbc.update("insert into cms_member_template(id,w18_provenance_kind,title,question_name,question_a) values(?,?,?,?,?)",templateId,marker,"title-"+id,"body-"+id,"option-"+id);
            }
            jdbc.update("insert into mms_merchant(id,name,category_id,city_role) values(?,?,9,'living')",id,"shop-"+id);
            jdbc.update("insert into roam_poi(id,name,type,lat,lng,status,merchant_id,template_id,node_level,sort,tags) values(?,?,2,31.23,121.47,1,?,?,1,?,'night')",id,"node-"+id,id,templateId,id);
            jdbc.update("insert into member_collectible values(?,7,3,DATEADD('SECOND',?,TIMESTAMP '2020-01-01 00:00:00'))",id,id);
        }
        void mixed() {
            node(1,101L,null); node(2,102L,""); node(3,103L,"UNKNOWN");
            node(4,104L,"W18_MANAGED"); node(5,105L,null);
            jdbc.update("insert into w18_cms_template_provenance values(105)");
            node(6,null,null); node(7,999L,null); node(8,0L,null);
        }
        List<CityNodeVO> near() {
            return mapper.selectMerchantNodesNear(31.2,31.3,121.4,121.5,31.23,121.47,null,null,null,null);
        }
    }
    private static List<Long> ids(List<CityNodeVO> nodes) { return nodes.stream().map(CityNodeVO::getPoiId).collect(Collectors.toList()); }
    private static Set<Long> poiIds(List<RoamPoi> nodes) { return nodes.stream().map(RoamPoi::getId).collect(Collectors.toSet()); }

    @Test void sixPlayerReadsExcludeEveryMarkerSidecarAndDanglingBinding() throws Exception {
        Fixture f = new Fixture(); f.mixed();
        assertEquals(Arrays.asList(1L,6L),ids(f.near()));
        assertEquals(Arrays.asList(6L,1L),ids(f.mapper.selectFavoriteMerchantNodes(7L)));
        assertEquals(new HashSet<>(Arrays.asList(1L,6L)),poiIds(f.mapper.selectPoisNear(31.2,31.3,121.4,121.5,7L)));
        assertEquals(new HashSet<>(Arrays.asList(1L,6L)),poiIds(f.mapper.selectPlayerPoisByIds(Arrays.asList(1L,2L,3L,4L,5L,6L,7L,8L))));
        for(long id : Arrays.asList(2L,3L,4L,5L,7L,8L)) {
            assertNull(f.mapper.selectPlayerMerchantNodeDetail(id,7L),"detail " + id);
            assertNull(f.mapper.selectActiveMerchantNode(id),"merchant discovery " + id);
        }
        CityNodeVO legacy = f.mapper.selectPlayerMerchantNodeDetail(1L,7L);
        assertEquals("body-1",legacy.getQuestionName()); assertEquals("option-1",legacy.getQuestionA());
        assertEquals(101L,legacy.getTemplateId());
        assertNotNull(f.mapper.selectActiveMerchantNode(1L));
        CityNodeVO metadata = f.mapper.selectPlayerMerchantNodeDetail(6L,7L);
        assertNotNull(metadata); assertNull(metadata.getTemplateId()); assertNull(metadata.getQuestionName());
    }
    @Test void naturalMetadataSurvivesAndClaimApprovalCannotBypassTemplateGuard() throws Exception {
        Fixture f = new Fixture(); f.mixed();
        f.jdbc.update("update roam_poi set type=1 where id=6");
        f.jdbc.update("update roam_poi set application_type=2,audit_status=0 where id in(1,4,5)");
        assertEquals(new HashSet<>(Arrays.asList(1L,6L)),poiIds(f.mapper.selectPoisNear(31.2,31.3,121.4,121.5,7L)));
        assertEquals(new HashSet<>(Arrays.asList(1L,6L)),poiIds(f.mapper.selectPlayerPoisByIds(Arrays.asList(1L,4L,5L,6L))));
        assertEquals(Collections.singletonList(1L),ids(f.near()));
        assertNotNull(f.mapper.selectPlayerMerchantNodeDetail(1L,7L));
        assertNull(f.mapper.selectPlayerMerchantNodeDetail(4L,7L));
        assertNull(f.mapper.selectActiveMerchantNode(5L));
        assertEquals(Collections.singletonList(1L),ids(f.mapper.selectFavoriteMerchantNodes(7L)));
    }
    @Test void merchantAndAdminEvidenceQueriesRemainSeparate() throws Exception {
        Fixture f = new Fixture(); f.mixed();
        assertEquals("body-4",f.mapper.selectMerchantNodeDetail(4L).getQuestionName());
        assertEquals(Collections.singletonList(4L),ids(f.mapper.selectMyNodes(4L)));
        assertEquals(new HashSet<>(Arrays.asList(4L,5L)),poiIds(f.mapper.selectPoisByIds(Arrays.asList(4L,5L))));
    }
    @Test void offlineRewardDoesNotAuthorizeProtectedLiveBodyAndOwnerCheckSurvives() throws Exception {
        Fixture f = new Fixture(); f.mixed();
        f.jdbc.update("update roam_poi set status=0 where id in(1,4,5)");
        f.jdbc.update("insert into roam_poi_reward_log values(1,7,11,0),(4,7,12,0),(5,7,13,1)");
        assertNotNull(f.mapper.selectPlayerMerchantNodeDetail(1L,7L));
        assertNull(f.mapper.selectPlayerMerchantNodeDetail(1L,8L));
        assertNull(f.mapper.selectPlayerMerchantNodeDetail(1L,0L));
        assertNull(f.mapper.selectPlayerMerchantNodeDetail(4L,7L));
        assertNull(f.mapper.selectPlayerMerchantNodeDetail(5L,7L));
    }
    @Test void statusAuditBoundsSearchAndFavoriteOwnerFiltersStillApply() throws Exception {
        Fixture f = new Fixture();
        for(long id=1;id<=6;id++) f.node(id,100+id,null);
        f.jdbc.update("update roam_poi set status=0 where id=2");
        f.jdbc.update("update roam_poi set application_type=1,audit_status=0 where id=3");
        f.jdbc.update("update roam_poi set lat=40 where id=4");
        f.jdbc.update("update roam_poi set tags='other' where id=5");
        f.jdbc.update("update mms_merchant set category_id=8 where id=6");
        assertEquals(Collections.singletonList(1L),ids(f.mapper.selectMerchantNodesNear(31.2,31.3,121.4,121.5,31.23,121.47,"shop",9,"night","living")));
        assertTrue(f.mapper.selectFavoriteMerchantNodes(8L).isEmpty());
        assertNull(f.mapper.selectPlayerMerchantNodeDetail(3L,7L));
        assertEquals(Arrays.asList(6L,5L,4L,1L),ids(f.mapper.selectFavoriteMerchantNodes(7L)));
    }
    @Test void missingProvenanceTableFailsClosedAcrossAllSixPlayerQueries() throws Exception {
        Fixture f = new Fixture(); f.mixed(); f.jdbc.execute("drop table w18_cms_template_provenance");
        assertThrows(RuntimeException.class,f::near);
        assertThrows(RuntimeException.class,()->f.mapper.selectPlayerMerchantNodeDetail(1L,7L));
        assertThrows(RuntimeException.class,()->f.mapper.selectActiveMerchantNode(1L));
        assertThrows(RuntimeException.class,()->f.mapper.selectFavoriteMerchantNodes(7L));
        assertThrows(RuntimeException.class,()->f.mapper.selectPoisNear(31.2,31.3,121.4,121.5,7L));
        assertThrows(RuntimeException.class,()->f.mapper.selectPlayerPoisByIds(Collections.singletonList(1L)));
    }
    @Test void missingMarkerColumnFailsClosedEvenWithSidecarAvailable() throws Exception {
        Fixture f = new Fixture(); f.mixed(); f.jdbc.execute("alter table cms_member_template drop column w18_provenance_kind");
        assertThrows(RuntimeException.class,f::near);
        assertThrows(RuntimeException.class,()->f.mapper.selectPlayerMerchantNodeDetail(1L,7L));
    }
}

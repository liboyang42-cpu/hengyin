package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: production reference SELECT on H2, with independent JDBC storage reads; no HTTP claim. */
class E02_03Test {
    @Test
    void cancelledActivityHidesOnlyItsCardAndPreservesBodyAndStoredSnapshot() throws Exception {
        Fixture f = new Fixture();
        try (SqlSession session = f.factory.openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(Arrays.asList(1L, 2L), ids(mapper.selectReferences(7L)));
            f.jdbc.update("update cms_activity set cancel_time=CURRENT_TIMESTAMP where id=101");
            session.clearCache();
            assertEquals(Collections.singletonList(2L), ids(mapper.selectReferences(7L)));
            f.assertStorageUnchanged();
        }
    }

    @Test
    void offlineActivityHidesOnlyItsCardAndPreservesBodyAndStoredSnapshot() throws Exception {
        Fixture f = new Fixture();
        try (SqlSession session = f.factory.openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(Arrays.asList(1L, 2L), ids(mapper.selectReferences(7L)));
            f.jdbc.update("update cms_activity set publish_status=0 where id=101");
            session.clearCache();
            assertEquals(Collections.singletonList(2L), ids(mapper.selectReferences(7L)));
            f.assertStorageUnchanged();
        }
    }

    @Test
    void activeCardKeepsHistoricalProjectionDespiteTargetEditsOrRegistrationChanges() throws Exception {
        Fixture f = new Fixture();
        f.jdbc.update("update cms_activity set name='new live title',member_id=99 where id=101");
        f.jdbc.update("delete from cms_registration where owner_id=101");
        try (SqlSession session = f.factory.openSession(true)) {
            List<Map<String, Object>> rows = session.getMapper(CommunityPostMapper.class).selectReferences(7L);
            assertEquals(Arrays.asList(1L, 2L), ids(rows));
            assertEquals(f.snapshot, rows.get(0).get("snapshot_json"));
            assertEquals(new HashSet<>(Arrays.asList("id", "reference_type", "reference_id", "snapshot_json", "privacy_snapshot")),
                    rows.get(0).keySet());
            assertEquals("PUBLIC_SAFE", rows.get(0).get("privacy_snapshot"));
            f.assertStorageUnchanged();
        }
    }

    @Test
    void unrelatedTypesMissingTargetsNullStatesAndOriginalRowGatesRemainUnchanged() throws Exception {
        Fixture f = new Fixture();
        f.jdbc.update("update cms_activity set publish_status=0,cancel_time=CURRENT_TIMESTAMP where id=101");
        f.jdbc.update("insert into cms_activity(id,publish_status) values (102,null)");
        f.reference(3, "TOPIC", 101, 7, null);
        f.reference(4, "ROUTE", 101, 7, null);
        f.reference(5, "CLUB", 101, 7, null);
        f.reference(6, "ACTIVITY", 999, 7, null);
        f.reference(7, "ACTIVITY", 102, 7, null);
        f.reference(8, null, 101, 7, null);
        f.reference(9, "ACTIVITY", 102, 7, "2026-01-01 00:00:00");
        f.reference(10, "ACTIVITY", 102, 8, null);
        try (SqlSession session = f.factory.openSession(true)) {
            List<Map<String, Object>> rows = session.getMapper(CommunityPostMapper.class).selectReferences(7L);
            assertEquals(Arrays.asList(2L, 3L, 4L, 5L, 6L, 7L, 8L), ids(rows));
            assertEquals(Collections.singletonList(10L), ids(session.getMapper(CommunityPostMapper.class).selectReferences(8L)));
            assertEquals(Integer.valueOf(10), f.jdbc.queryForObject("select count(*) from community_post_reference", Integer.class));
            assertEquals(Integer.valueOf(1), f.jdbc.queryForObject("select count(*) from community_post_reference where invalidated_at is not null", Integer.class));
        }
    }

    private static List<Long> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> ((Number) row.get("id")).longValue()).collect(Collectors.toList());
    }

    private static class Fixture {
        final JdbcTemplate jdbc;
        final SqlSessionFactory factory;
        final String snapshot = "{\"title\":\"old activity title\",\"cover_url\":\"stored-cover\",\"subtitle\":\"old address\",\"starts_at\":\"2026-10-01\",\"ends_at\":\"2026-10-02\"}";

        Fixture() throws Exception {
            DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:e02_03_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(ds);
            jdbc.execute("create table community_post(id bigint primary key,body varchar(5000))");
            jdbc.execute("create table community_post_reference(id bigint primary key,post_id bigint,reference_type varchar(32),reference_id bigint,snapshot_json varchar(2000),privacy_snapshot varchar(32),invalidated_at timestamp)");
            jdbc.execute("create table cms_activity(id bigint primary key,publish_status int,cancel_time timestamp,name varchar(80),member_id bigint)");
            jdbc.execute("create table cms_registration(owner_id bigint,member_id bigint)");
            jdbc.update("insert into community_post values (7,'original post body')");
            jdbc.update("insert into cms_activity values (101,1,null,'old activity title',11)");
            jdbc.update("insert into cms_registration values (101,11)");
            reference(1, "ACTIVITY", 101, 7, null);
            reference(2, "POI", 101, 7, null);
            Configuration c = new Configuration(new Environment("e02-03", new JdbcTransactionFactory(), ds));
            c.getTypeAliasRegistry().registerAlias("CommunityPost", CommunityPost.class);
            c.addMapper(CommunityPostMapper.class);
            String resource = "mapper/business/CommunityPostMapper.xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                new XMLMapperBuilder(input, c, resource, c.getSqlFragments()).parse();
            }
            factory = new SqlSessionFactoryBuilder().build(c);
        }

        void reference(long id, String type, long target, long post, String invalidated) {
            jdbc.update("insert into community_post_reference values (?,?,?,?,?,'PUBLIC_SAFE',?)", id, post, type, target, snapshot, invalidated);
        }

        void assertStorageUnchanged() {
            assertEquals("original post body", jdbc.queryForObject("select body from community_post where id=7", String.class));
            assertEquals(snapshot, jdbc.queryForObject("select snapshot_json from community_post_reference where id=1", String.class));
            assertEquals(Integer.valueOf(2), jdbc.queryForObject("select count(*) from community_post_reference", Integer.class));
            assertEquals(Integer.valueOf(0), jdbc.queryForObject("select count(*) from community_post_reference where invalidated_at is not null", Integer.class));
        }
    }
}

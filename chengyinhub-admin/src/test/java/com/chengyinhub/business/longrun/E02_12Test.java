package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN: actual production XML on H2; synthetic NULL is a compatibility negative control. */
class E02_12Test {
    @Test
    void poiOptionsRequireExplicitOnlineStateLikeSaveSnapshot() throws Exception {
        try (SqlSession session = fixture().openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            List<Map<String, Object>> options = mapper.selectReferenceOptions("POI", 7L, 20);
            assertEquals(3, options.size());
            assertEquals(3L, id(options.get(0)));
            assertEquals(2L, id(options.get(1)));
            assertEquals(1L, id(options.get(2)));
            assertNotNull(mapper.selectReferenceSnapshot("POI", 3L, 7L));
            assertNull(mapper.selectReferenceSnapshot("POI", 8L, 7L));
            assertNull(mapper.selectReferenceSnapshot("POI", 9L, 7L));
        }
    }

    @Test
    void poiOptionsPreserveCityGateProjectionDescendingOrderAndLimit() throws Exception {
        try (SqlSession session = fixture().openSession(true)) {
            List<Map<String, Object>> options = session.getMapper(CommunityPostMapper.class)
                    .selectReferenceOptions("POI", 7L, 2);
            assertEquals(2, options.size());
            assertEquals(3L, id(options.get(0)));
            assertEquals(2L, id(options.get(1)));
            assertEquals(new HashSet<>(Arrays.asList("id", "name", "city_code", "subtitle")),
                    options.get(0).keySet());
            assertEquals("third", options.get(0).get("name"));
            assertEquals("SH", options.get(0).get("city_code"));
            assertEquals("address", options.get(0).get("subtitle"));
            assertEquals("description", options.get(1).get("subtitle"));
            List<Map<String, Object>> all = session.getMapper(CommunityPostMapper.class)
                    .selectReferenceOptions("POI", 7L, 20);
            assertEquals("", all.get(2).get("subtitle"));
            // Higher IDs with NULL/empty cities must not consume the limit.
            assertEquals(3, all.size());
        }
    }

    private long id(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private SqlSessionFactory fixture() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:e02_12_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table roam_poi (id bigint primary key, name varchar(80), status tinyint default 1, "
                + "city_code varchar(20), address varchar(255), description varchar(255))");
        jdbc.update("insert into roam_poi(id,name,status,city_code,address,description) values "
                + "(1,'first',1,'SH',null,null),(2,'second',1,'SH',null,'description'),"
                + "(3,'third',1,'SH','address','ignored'),(6,'no-city',1,null,'x','x'),"
                + "(7,'empty-city',1,'','x','x'),(8,'offline',0,'SH','x','x'),"
                + "(9,'unknown-state',null,'SH','x','x')");
        Configuration c = new Configuration(new Environment("e02-12", new JdbcTransactionFactory(), ds));
        c.getTypeAliasRegistry().registerAlias("CommunityPost", CommunityPost.class);
        c.addMapper(CommunityPostMapper.class);
        String resource = "mapper/business/CommunityPostMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            new XMLMapperBuilder(input, c, resource, c.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(c);
    }
}

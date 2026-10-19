package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real production XML/resultMap on H2. No production migration or MySQL acceptance claim. */
class CmsMemberTemplateProvenanceMappingTest {
    private static final String NS = "com.chengyinhub.business.mapper.CmsMemberTemplateMapper.";

    @Test void actualSelectAndExplicitResultMapPreserveNullKnownAndUnknownMarkers() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:marker_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Configuration configuration = configuration(dataSource);
        JdbcTemplate jdbc = createSchema(configuration, dataSource);
        jdbc.update("insert into cms_member_template(id,w18_provenance_kind) values(?,?)", 1L, "W18_MANAGED");
        jdbc.update("insert into cms_member_template(id,w18_provenance_kind) values(?,?)", 2L, null);
        jdbc.update("insert into cms_member_template(id,w18_provenance_kind) values(?,?)", 3L, "UNKNOWN_FUTURE");
        CmsMemberTemplateMapper mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration))
                .getMapper(CmsMemberTemplateMapper.class);
        assertEquals("W18_MANAGED", mapper.selectCmsMemberTemplateById(1L).getW18ProvenanceKind());
        assertNull(mapper.selectCmsMemberTemplateById(2L).getW18ProvenanceKind());
        assertEquals("UNKNOWN_FUTURE", mapper.selectCmsMemberTemplateById(3L).getW18ProvenanceKind());
    }

    private JdbcTemplate createSchema(Configuration configuration, DriverManagerDataSource dataSource) {
        // Derive the minimal test schema from the real projection; never replace the production SQL.
        String sql = configuration.getMappedStatement(NS + "selectCmsMemberTemplateById")
                .getBoundSql(1L).getSql().trim();
        String projection = sql.substring(sql.indexOf("select ") + 7, sql.indexOf(" from cms_member_template"));
        List<String> columns = new ArrayList<>();
        for (String column : projection.split(",")) {
            String name = column.trim();
            assertTrue(name.matches("[a-z_][a-z0-9_]*"), "Unexpected projected column: " + name);
            columns.add(name + ("id".equals(name) ? " bigint auto_increment primary key" : " varchar(255)"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table cms_member_template(" + String.join(",", columns) + ")");
        return jdbc;
    }

    @Test void jacksonNeitherAcceptsNorExposesMarkerIncludingFieldVisibility() throws Exception {
        for (ObjectMapper json : new ObjectMapper[] {new ObjectMapper(),
                new ObjectMapper().setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)}) {
            CmsMemberTemplate input = json.readValue("{\"id\":1,\"w18ProvenanceKind\":\"W18_MANAGED\"}", CmsMemberTemplate.class);
            assertNull(input.getW18ProvenanceKind());
            input.setW18ProvenanceKind("W18_MANAGED");
            assertFalse(json.readTree(json.writeValueAsString(input)).has("w18ProvenanceKind"));
        }
    }

    @Test void internalBeanCopyRetainsMarkerWithoutMakingItWritableInGenericSql() throws Exception {
        CmsMemberTemplate source = new CmsMemberTemplate();
        source.setId(1L); source.setTitle("template"); source.setW18ProvenanceKind("W18_MANAGED");
        CmsMemberTemplate copy = new CmsMemberTemplate(); BeanUtils.copyProperties(source, copy);
        assertEquals("W18_MANAGED", copy.getW18ProvenanceKind());
        Configuration configuration = configuration(null);
        for (String operation : new String[] {"insertCmsMemberTemplate", "updateCmsMemberTemplate"}) {
            String sql = configuration.getMappedStatement(NS + operation).getBoundSql(copy).getSql();
            assertFalse(sql.contains("w18_provenance_kind"));
        }
        // BeanUtils preservation alone is NOT persistence: generic copies still need source guard.
    }

    @Test void actualGenericInsertCannotSetMarkerAndUpdateCannotClearOrReplaceIt() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:marker_write_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Configuration configuration = configuration(dataSource);
        JdbcTemplate jdbc = createSchema(configuration, dataSource);
        CmsMemberTemplateMapper mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration))
                .getMapper(CmsMemberTemplateMapper.class);
        CmsMemberTemplate row = new CmsMemberTemplate();
        row.setTitle("ordinary insert"); row.setW18ProvenanceKind("W18_MANAGED");
        assertEquals(1, mapper.insertCmsMemberTemplate(row));
        assertNotNull(row.getId());
        assertNull(jdbc.queryForObject("select w18_provenance_kind from cms_member_template where id=?", String.class, row.getId()));
        // Represents the authoritative writer's marker, seeded directly only in the H2 fixture.
        jdbc.update("update cms_member_template set w18_provenance_kind='W18_MANAGED' where id=?", row.getId());
        for (String attempted : new String[] {null, "", "UNKNOWN_FUTURE"}) {
            row.setTitle("ordinary update"); row.setW18ProvenanceKind(attempted);
            assertEquals(1, mapper.updateCmsMemberTemplate(row));
            assertEquals("W18_MANAGED", jdbc.queryForObject("select w18_provenance_kind from cms_member_template where id=?", String.class, row.getId()));
        }
    }

    private Configuration configuration(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration();
        configuration.setAutoMappingBehavior(AutoMappingBehavior.NONE);
        configuration.getTypeAliasRegistry().registerAlias("CmsMemberTemplate", CmsMemberTemplate.class);
        configuration.getTypeAliasRegistry().registerAlias("MemberTemplateVO", MemberTemplateVO.class);
        if (dataSource != null) configuration.setEnvironment(new Environment("cms-marker-h2",
                new SpringManagedTransactionFactory(), dataSource));
        String resource = "mapper/business/CmsMemberTemplateMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
}

package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInterceptor;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Production XML + real PageHelper on H2; not MySQL or production migration acceptance. */
class CmsMemberTemplateLegacyListMyBatisTest {
    private static final String NS = "com.chengyinhub.business.mapper.CmsMemberTemplateMapper.";

    private static class Fixture {
        final JdbcTemplate jdbc;
        final CmsMemberTemplateMapper mapper;
        Fixture() throws Exception {
            DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:list_"
                    + UUID.randomUUID().toString().replace("-", "")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            Configuration c = new Configuration();
            c.setAutoMappingBehavior(AutoMappingBehavior.NONE);
            c.setEnvironment(new Environment("h2", new SpringManagedTransactionFactory(), ds));
            c.getTypeAliasRegistry().registerAlias("CmsMemberTemplate", CmsMemberTemplate.class);
            c.getTypeAliasRegistry().registerAlias("MemberTemplateVO", MemberTemplateVO.class);
            PageInterceptor pagination = new PageInterceptor();
            Properties properties = new Properties(); properties.setProperty("helperDialect", "h2");
            pagination.setProperties(properties); c.addInterceptor(pagination);
            String resource = "mapper/business/CmsMemberTemplateMapper.xml";
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, c, resource, c.getSqlFragments()).parse();
            }
            String sql = c.getMappedStatement(NS + "selectCmsMemberTemplateById").getBoundSql(1L).getSql().trim();
            String projection = sql.substring(sql.indexOf("select ") + 7, sql.indexOf(" from cms_member_template"));
            List<String> columns = new ArrayList<>();
            Set<String> numbers = new HashSet<>(Arrays.asList("original_template_id", "member_id", "status", "draft_status", "publish_status", "del_flag"));
            for (String projected : projection.split(",")) {
                String name = projected.trim();
                assertTrue(name.matches("[a-z_][a-z0-9_]*"), name);
                columns.add(name + (name.equals("id") ? " bigint primary key" :
                        numbers.contains(name) ? " bigint" : name.endsWith("_time") ? " timestamp" : " varchar(1000)"));
            }
            jdbc = new JdbcTemplate(ds);
            jdbc.execute("create table cms_member_template(" + String.join(",", columns) + ")");
            jdbc.execute("create table w18_cms_template_provenance(template_id bigint primary key)");
            jdbc.execute("create table cms_template_library(id bigint primary key, member_template_id bigint, member_id bigint, status bigint, publish_status bigint, del_flag bigint, reason varchar(100))");
            mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(c)).getMapper(CmsMemberTemplateMapper.class);
        }
        void row(long id, String marker, long owner, String title, long status) {
            jdbc.update("insert into cms_member_template(id,w18_provenance_kind,member_id,title,status,draft_status,del_flag,original_template_id,rule_instructions,question_answer,story_json,create_time) values(?,?,?,?,?,0,0,0,?,?,?,DATEADD('SECOND',?,TIMESTAMP '2020-01-01 00:00:00'))",
                    id, marker, owner, title, status, "rules-" + id, "answer-" + id, "story-" + id, id);
        }
        void mixed() {
            row(1, null, 10, "keep one", 1); row(2, null, 10, "keep two", 1);
            row(3, "", 10, "keep empty", 1); row(4, "UNKNOWN", 10, "keep unknown", 1);
            row(5, "W18_MANAGED", 10, "keep managed", 1); row(6, null, 10, "keep sidecar", 1);
            jdbc.update("insert into w18_cms_template_provenance values(6)");
        }
    }
    private static List<Long> ids(List<? extends CmsMemberTemplate> rows) {
        return rows.stream().map(CmsMemberTemplate::getId).collect(Collectors.toList());
    }
    private static MemberTemplateVO memberProbe() {
        MemberTemplateVO p = new MemberTemplateVO(); p.setMemberId(10L); p.setTitle("keep"); p.setStatus(1L); p.setDraftStatus(0); return p;
    }
    private static CmsMemberTemplate genericProbe() {
        CmsMemberTemplate p = new CmsMemberTemplate(); p.setMemberId(10L); p.setTitle("keep"); p.setStatus(1L); p.setDraftStatus(0); return p;
    }
    @Test void allThreeActualQueriesExcludeEveryNonNullMarkerAndSidecarOnly() throws Exception {
        Fixture f = new Fixture(); f.mixed();
        assertEquals(Arrays.asList(2L, 1L), ids(f.mapper.selectCmsMemberTemplateList(genericProbe())));
        assertEquals(Arrays.asList(2L, 1L), ids(f.mapper.selectMemberTemplateList(memberProbe())));
        List<CmsMemberTemplate> batch = f.mapper.selectCmsMemberTemplateByIds(Arrays.asList(1L,2L,3L,4L,5L,6L,999L));
        assertEquals(new HashSet<>(Arrays.asList(1L,2L)), new HashSet<>(ids(batch)));
        for (CmsMemberTemplate row : batch) {
            assertEquals("rules-" + row.getId(), row.getRuleInstructions());
            assertEquals("answer-" + row.getId(), row.getQuestionAnswer());
            assertEquals("story-" + row.getId(), row.getStoryJson());
        }
        // Evidence/locking reads are deliberately NOT generic-list filtered.
        assertNotNull(f.mapper.selectCmsMemberTemplateById(5L));
        assertEquals(2, f.mapper.selectCmsMemberTemplateByIdsForUpdate(Arrays.asList(5L,6L)).size());
    }
    @Test void originalOwnerTitleStatusDraftAndOrderingRemainEffective() throws Exception {
        Fixture f = new Fixture(); f.mixed();
        f.row(10,null,20,"keep wrong owner",1); f.row(11,null,10,"different",1);
        f.row(12,null,10,"keep wrong status",0); f.row(13,null,10,"keep wrong draft",1);
        f.jdbc.update("update cms_member_template set draft_status=1 where id=13");
        assertEquals(Arrays.asList(2L,1L), ids(f.mapper.selectCmsMemberTemplateList(genericProbe())));
        assertEquals(Arrays.asList(2L,1L), ids(f.mapper.selectMemberTemplateList(memberProbe())));
    }
    @Test void personalListRealLibraryJoinsPreserveAvailabilityAndMarketProjection() throws Exception {
        Fixture f = new Fixture(); f.row(1,null,10,"keep adopted",1); f.row(2,null,10,"keep unavailable",1);
        f.jdbc.update("insert into cms_template_library values(100,null,null,1,1,0,null),(101,null,null,1,0,0,null),(200,1,10,1,1,0,'market reason')");
        f.jdbc.update("update cms_member_template set original_template_id=100 where id=1");
        f.jdbc.update("update cms_member_template set original_template_id=101 where id=2");
        List<MemberTemplateVO> rows=f.mapper.selectMemberTemplateList(memberProbe());
        assertEquals(Collections.singletonList(1L),ids(rows));
        assertEquals(200L,rows.get(0).getMarketTemplateId());
        assertEquals("引用模板",rows.get(0).getTemplateSource());
    }
    @Test void actualPageHelperGenericCountAndRowsUseSameFilter() throws Exception {
        Fixture f=new Fixture(); f.mixed();
        try {
            PageHelper.startPage(1,1);
            List<CmsMemberTemplate> rows=f.mapper.selectCmsMemberTemplateList(genericProbe());
            assertTrue(rows instanceof Page); assertEquals(2L,((Page<?>)rows).getTotal());
            assertEquals(Collections.singletonList(2L),ids(rows));
        } finally { PageHelper.clearPage(); }
    }
    @Test void actualPageHelperMemberJoinCountAndSecondPageUseSameFilter() throws Exception {
        Fixture f=new Fixture(); f.mixed();
        try {
            PageHelper.startPage(2,1);
            List<MemberTemplateVO> rows=f.mapper.selectMemberTemplateList(memberProbe());
            assertTrue(rows instanceof Page); assertEquals(2L,((Page<?>)rows).getTotal());
            assertEquals(Collections.singletonList(1L),ids(rows));
        } finally { PageHelper.clearPage(); }
    }
    @Test void missingSidecarSchemaFailsAllQueriesWithoutFallback() throws Exception {
        Fixture f=new Fixture(); f.mixed(); f.jdbc.execute("drop table w18_cms_template_provenance");
        assertThrows(DataAccessException.class,()->f.mapper.selectCmsMemberTemplateList(genericProbe()));
        assertThrows(DataAccessException.class,()->f.mapper.selectMemberTemplateList(memberProbe()));
        assertThrows(DataAccessException.class,()->f.mapper.selectCmsMemberTemplateByIds(Collections.singletonList(1L)));
    }
    @Test void missingMarkerColumnFailsAllQueriesWithoutFallback() throws Exception {
        Fixture f=new Fixture(); f.mixed(); f.jdbc.execute("alter table cms_member_template drop column w18_provenance_kind");
        assertThrows(DataAccessException.class,()->f.mapper.selectCmsMemberTemplateList(genericProbe()));
        assertThrows(DataAccessException.class,()->f.mapper.selectMemberTemplateList(memberProbe()));
        assertThrows(DataAccessException.class,()->f.mapper.selectCmsMemberTemplateByIds(Collections.singletonList(1L)));
    }
}

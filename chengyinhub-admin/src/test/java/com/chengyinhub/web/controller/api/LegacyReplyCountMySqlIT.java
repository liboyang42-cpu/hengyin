package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ViewComment;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.ViewCommentMapper;
import com.chengyinhub.business.service.impl.ViewCommentServiceImpl;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInterceptor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MySQL + production Mapper XML + PageHelper + existing POST form contract.
 * No H2/Mockito substitute. Run only on the coordinator's approved test database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LegacyReplyCountMySqlIT {
    private JdbcTemplate jdbc;
    private ViewCommentMapper mapper;
    private MockMvc mvc;
    private String database;
    private final BatchQueries batches = new BatchQueries();

    @BeforeAll
    void setup() throws Exception {
        assertTrue(Set.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()));
        String suite = "legacy_reply_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        DriverManagerDataSource source = new DriverManagerDataSource(MybatisItDatabase.url(suite),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        database = MybatisItDatabase.databaseName(suite);
        jdbc = new JdbcTemplate(source);
        assertEquals(database, jdbc.queryForObject("SELECT DATABASE()", String.class));
        // Extract exact public baseline DDL, including the actual view's visibility predicate.
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/base_schema_20260818.sql"))) root = root.getParent();
        assertNotNull(root, "public schema snapshot missing");
        String schema = Files.readString(root.resolve("sql/base_schema_20260818.sql"));
        for (String table : List.of("ums_member", "ums_comment", "ums_comment_like")) {
            Matcher ddl = Pattern.compile("(?s)CREATE TABLE `" + table + "` [(].*?[)] ENGINE=[^;]+;").matcher(schema);
            assertTrue(ddl.find(), table);
            jdbc.execute(ddl.group());
        }
        Matcher view = Pattern.compile("(?m)^/[*]!50001 VIEW `view_comment` AS (.*) [*]/;$").matcher(schema);
        assertTrue(view.find(), "actual view DDL missing");
        jdbc.execute("CREATE VIEW view_comment AS " + view.group(1));

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ViewCommentMapper.xml"));
        PageInterceptor pages = new PageInterceptor();
        Properties properties = new Properties();
        properties.setProperty("helperDialect", "mysql");
        pages.setProperties(properties);
        factory.setPlugins(pages, batches);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ViewCommentMapper.class);
        ViewCommentServiceImpl service = new ViewCommentServiceImpl();
        ReflectionTestUtils.setField(service, "viewCommentMapper", mapper);
        ApiCommentController controller = new ApiCommentController() {
            @Override public Long getAppUserId() { return 0L; }
        };
        ReflectionTestUtils.setField(controller, "viewCommentService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM ums_comment");
        insert(100, 3, 42, null, 1, 0);
        insert(90, 3, 42, 0L, 1, 0);
        insert(1, 3, 42, 100L, 1, 0);
        insert(2, 3, 42, 100L, 1, 0);
        insert(3, 3, 42, 1L, 1, 0); // grandchild belongs to parent 1, not 100
        insert(4, 3, 42, 100L, 2, 0); // hidden
        insert(5, 3, 42, 100L, 1, 2); // deleted
        insert(6, 3, 88, 100L, 1, 0); // other owner id
        insert(7, 2, 42, 100L, 1, 0); // other owner type
        insert(110, 3, 42, null, 2, 0); // hidden root excluded from rows/total
        batches.parents.clear();
    }

    @AfterEach
    void clearPage() { PageHelper.clearPage(); }

    @AfterAll
    void cleanup() {
        if (jdbc != null && database != null) {
            assertTrue(database.matches("chengyin_mybatis_legacy_reply_[a-z0-9_]+"));
            jdbc.execute("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void realBatchCountsOnlyVisibleDirectRepliesForTheRequestedOwner() {
        assertEquals(Map.of(100L, 2L, 1L, 1L), counts(3L, 42L, List.of(100L, 90L, 1L)));
        assertEquals(Map.of(100L, 1L), counts(3L, 88L, List.of(100L)));
        assertEquals(Map.of(100L, 1L), counts(2L, 42L, List.of(100L)));
        assertEquals(Map.of(), counts(4L, 42L, List.of(100L)));
    }

    @Test
    void sameVisibilityAppliesToAllExistingOwnerTypesAndStateChanges() {
        for (long type : List.of(1L, 2L, 3L, 4L)) {
            insert(300 + type, (int) type, 77, 100L, 1, 0);
            insert(310 + type, (int) type, 77, 100L, 2, 0);
            insert(320 + type, (int) type, 77, 100L, 1, 2);
            assertEquals(Map.of(100L, 1L), counts(type, 77L, List.of(100L)));
        }
        jdbc.update("UPDATE ums_comment SET status=2 WHERE id IN (1,2)");
        assertEquals(Map.of(), counts(3L, 42L, List.of(100L)));
    }

    @Test
    void emptyOrNullParentSetCannotCountAllReplies() {
        assertTrue(mapper.selectVisibleReplyCountsByParents(3L, 42L, List.of()).isEmpty());
        assertTrue(mapper.selectVisibleReplyCountsByParents(3L, 42L, null).isEmpty());
    }

    @Test
    void oldPostFormKeepsRowsTotalAndPageNumWhileReturningZeroAndMultipleCounts() throws Exception {
        page(3, 42, 1, 2)
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.rows[0].id").value(100))
                .andExpect(jsonPath("$.data.rows[0].replyCommentCount").value(2))
                .andExpect(jsonPath("$.data.rows[1].id").value(90))
                .andExpect(jsonPath("$.data.rows[1].replyCommentCount").value(0))
                .andExpect(jsonPath("$.data.rows[0].isLiked").value(0))
                .andExpect(jsonPath("$.data.rows[0].isFollowTheUser").value(0));
        assertEquals(List.of(List.of(100L, 90L)), batches.parents);
        page(3, 42, 2, 2)
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.rows[0].id").value(3))
                .andExpect(jsonPath("$.data.rows[1].id").value(2));
        assertEquals(List.of(3L, 2L), batches.parents.get(1));
    }

    @Test
    void emptyPageSkipsBatchAndKeepsTotal() throws Exception {
        page(3, 42, 9, 2).andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.rows.length()").value(0));
        page(3, 999, 1, 2).andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.rows.length()").value(0));
        assertTrue(batches.parents.isEmpty());
    }

    @Test
    void batchQueryCountDoesNotGrowWithPageLength() throws Exception {
        for (long id = 200; id < 225; id++) insert(id, 3, 42, null, 1, 0);
        page(3, 42, 1, 25).andExpect(jsonPath("$.data.total").value(30))
                .andExpect(jsonPath("$.data.rows.length()").value(25))
                .andExpect(jsonPath("$.data.rows[0].replyCommentCount").value(0));
        assertEquals(1, batches.parents.size());
        assertEquals(25, batches.parents.get(0).size());
    }

    @Test
    void otherOwnerAndInvalidRequestKeepExistingContract() throws Exception {
        page(2, 42, 1, 10).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].id").value(7))
                .andExpect(jsonPath("$.data.rows[0].replyCommentCount").value(0));
        assertEquals(List.of(List.of(7L)), batches.parents);
        mvc.perform(post("/api/comment/list").param("owner_type", "0").param("owner_id", "42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500));
        assertEquals(1, batches.parents.size());
    }

    @Test
    void requestWithoutPaginationParametersStillUsesExistingDefaults() throws Exception {
        mvc.perform(post("/api/comment/list").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("owner_type", "3").param("owner_id", "42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.rows.length()").value(5))
                .andExpect(jsonPath("$.data.rows[0].replyCommentCount").value(2));
        assertEquals(1, batches.parents.size());
        assertEquals(5, batches.parents.get(0).size());
    }

    private ResultActions page(int type, long owner, int page, int size) throws Exception {
        return mvc.perform(post("/api/comment/list").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("owner_type", String.valueOf(type)).param("owner_id", String.valueOf(owner))
                .param("pageNum", String.valueOf(page)).param("pageSize", String.valueOf(size))
                .param("reasonable", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    private Map<Long, Long> counts(Long type, Long owner, List<Long> parents) {
        return mapper.selectVisibleReplyCountsByParents(type, owner, parents).stream()
                .collect(Collectors.toMap(ViewComment::getId, ViewComment::getReplyCommentCount));
    }

    private void insert(long id, int type, long owner, Long parent, int status, int deleted) {
        jdbc.update("INSERT INTO ums_comment(id,member_id,owner_type,owner_id,reply_id,status,del_flag,contents) "
                + "VALUES(?,10,?,?,?,?,?,?)", id, type, owner, parent, status, deleted, "fixture");
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    public static class BatchQueries implements Interceptor {
        final List<List<Long>> parents = new ArrayList<>();
        @Override
        @SuppressWarnings("unchecked")
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().equals(ViewCommentMapper.class.getName() + ".selectVisibleReplyCountsByParents")) {
                Map<String, Object> arguments = (Map<String, Object>) invocation.getArgs()[1];
                List<Long> ids = (List<Long>) arguments.get("parentIds");
                parents.add(ids == null ? List.of() : new ArrayList<>(ids));
            }
            return invocation.proceed();
        }
    }
}

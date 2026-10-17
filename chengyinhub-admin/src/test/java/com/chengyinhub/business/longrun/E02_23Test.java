package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.ViewCreativeSquare;
import com.chengyinhub.business.mapper.ViewCreativeSquareMapper;
import com.chengyinhub.business.service.ICommunityPostService;
import com.chengyinhub.business.service.impl.ViewCreativeSquareServiceImpl;
import com.chengyinhub.web.controller.api.ApiCreativeSquareController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Actual mapping/filter/batch XML on H2; post act/unact is a mocked delegation boundary. */
public class E02_23Test {
    @Test
    void resolverRequiresOldRowAuthorExactRequestAndServerMarker() throws Exception {
        try (SqlSession session = fixture().openSession(true)) {
            ViewCreativeSquareMapper mapper = session.getMapper(ViewCreativeSquareMapper.class);
            assertEquals(Long.valueOf(1), mapper.selectMappedCommunityPostId(1L));
            for (long id = 2; id <= 11; id++) {
                assertNull(mapper.selectMappedCommunityPostId(id), "untrusted legacy target " + id);
            }
        }
    }

    @Test
    void mappedLegacyBookmarkAndCancelKeepExistingCommunityDelegation() throws Exception {
        try (SqlSession session = fixture().openSession(true)) {
            ICommunityPostService posts = mock(ICommunityPostService.class);
            when(posts.act(eq(8L), eq(1L), any())).thenReturn(Collections.emptyMap());
            when(posts.unact(8L, 1L, "BOOKMARK", "mapped-off-1")).thenReturn(Collections.emptyMap());
            ApiCreativeSquareController controller = controller(service(session), posts);
            assertEquals(200, controller.creativeSquareBookmark("1", 1, "mapped-on-1").get("code"));
            assertEquals(200, controller.creativeSquareBookmark("1", 0, "mapped-off-1").get("code"));
            verify(posts).act(eq(8L), eq(1L), argThat(action ->
                    "BOOKMARK".equals(action.getActionType()) && "mapped-on-1".equals(action.getRequestId())
                            && "LEGACY_MINIPROGRAM".equals(action.getSource())));
            verify(posts).unact(8L, 1L, "BOOKMARK", "mapped-off-1");
            verifyNoMoreInteractions(posts);
        }
    }

    @Test
    void untrustedTargetsRejectBothBookmarkAndCancelBeforeCommunityEffects() throws Exception {
        try (SqlSession session = fixture().openSession(true)) {
            ICommunityPostService posts = mock(ICommunityPostService.class);
            ApiCreativeSquareController controller = controller(service(session), posts);
            for (long id = 2; id <= 11; id++) {
                assertEquals(500, controller.creativeSquareBookmark(String.valueOf(id), 1, "reject-on-" + id).get("code"));
                assertEquals(500, controller.creativeSquareBookmark(String.valueOf(id), 0, "reject-off-" + id).get("code"));
            }
            verifyNoInteractions(posts);
        }
    }

    @Test
    void favoriteFilterAndBatchStateShareTrustedMappingBeforePagination() throws Exception {
        try (SqlSession session = fixture().openSession(true)) {
            ViewCreativeSquareMapper mapper = session.getMapper(ViewCreativeSquareMapper.class);
            ViewCreativeSquare query = new ViewCreativeSquare();
            query.setViewerMemberId(8L);
            query.setFavoriteOnly(true);
            List<ViewCreativeSquare> rows = mapper.selectViewCreativeSquareList(query);
            assertEquals(Collections.singletonList(1L), rows.stream().map(ViewCreativeSquare::getId).collect(Collectors.toList()));
            assertEquals(Collections.singletonList(1L), mapper.selectCommunityBookmarkedPostIds(8L,
                    Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L)));
            assertTrue(mapper.selectCommunityBookmarkedPostIds(99L, Arrays.asList(1L, 2L)).isEmpty());
            assertTrue(mapper.selectCommunityBookmarkedPostIds(8L, Collections.emptyList()).isEmpty());
        }
    }

    @Test
    void controllerEnrichmentDoesNotMarkUnrelatedSameNumberPosts() throws Exception {
        try (SqlSession session = fixture().openSession(true)) {
            ViewCreativeSquareServiceImpl views = spy(service(session));
            // Non-bookmark collaborators are outside this mapping regression; batch bookmark SQL stays real.
            doReturn(Collections.emptyList()).when(views).selectCreativeSquareLikesByMemberAndDataIds(anyLong(), anyList());
            doReturn(Collections.emptyList()).when(views).selectMemberFollowsByMemberAndFollowIds(anyLong(), anyList());
            ApiCreativeSquareController controller = controller(views, mock(ICommunityPostService.class));
            List<ViewCreativeSquare> rows = new ArrayList<>();
            for (long id = 1; id <= 11; id++) {
                ViewCreativeSquare square = new ViewCreativeSquare();
                square.setId(id); square.setMemberId(11L); rows.add(square);
            }
            ReflectionTestUtils.invokeMethod(controller, "enrichPersonalStateBatch", rows, 8L);
            assertEquals(Integer.valueOf(1), rows.get(0).getIsBookmarked());
            for (int i = 1; i < rows.size(); i++) assertEquals(Integer.valueOf(0), rows.get(i).getIsBookmarked());
        }
    }

    @Test
    void ordinaryCreateAndEditRejectClientSuppliedMigrationMarkerBeforeWrites() {
        com.chengyinhub.business.mapper.CommunityPostMapper mapper = mock(com.chengyinhub.business.mapper.CommunityPostMapper.class);
        when(mapper.countGuidelineVersion(1L)).thenReturn(1);
        com.chengyinhub.business.service.impl.CommunityPostServiceImpl posts =
                new com.chengyinhub.business.service.impl.CommunityPostServiceImpl(mapper,
                        new com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy(), null, null, null, null);
        com.chengyinhub.business.domain.dto.CommunityPostRequests.Upsert request =
                new com.chengyinhub.business.domain.dto.CommunityPostRequests.Upsert();
        request.setClientRequestId("legacy-square-2");
        request.setPostType("MOMENT"); request.setBody("normal body"); request.setAudience("PUBLIC");
        request.setCommentPolicy("EVERYONE"); request.setGuidelineVersionId(1L);
        request.setSafetyLabels(Collections.singletonList("LEGACY_MIGRATED"));
        com.chengyinhub.common.exception.ServiceException create = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class, () -> posts.createDraft(11L, request));
        com.chengyinhub.common.exception.ServiceException edit = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class, () -> posts.edit(11L, 2L, request));
        assertEquals(Integer.valueOf(400), create.getCode());
        assertEquals(Integer.valueOf(400), edit.getCode());
        assertEquals("帖文安全提示无效", create.getMessage());
        assertEquals("帖文安全提示无效", edit.getMessage());
        verify(mapper, times(2)).countGuidelineVersion(1L);
        verifyNoMoreInteractions(mapper);
    }

    private ViewCreativeSquareServiceImpl service(SqlSession session) {
        ViewCreativeSquareServiceImpl service = new ViewCreativeSquareServiceImpl();
        ReflectionTestUtils.setField(service, "viewCreativeSquareMapper", session.getMapper(ViewCreativeSquareMapper.class));
        return service;
    }

    private ApiCreativeSquareController controller(ViewCreativeSquareServiceImpl views, ICommunityPostService posts) {
        ApiCreativeSquareController controller = spy(new ApiCreativeSquareController());
        doReturn(8L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "viewCreativeSquareService", views);
        ReflectionTestUtils.setField(controller, "communityPostService", posts);
        return controller;
    }

    /** Limited H2 adapter for a root JSON array containing an exact JSON string; not a MySQL proof. */
    public static int jsonContains(String document, String candidate) throws Exception {
        if (document == null || candidate == null) return 0;
        ObjectMapper json = new ObjectMapper();
        JsonNode array;
        JsonNode expected;
        try {
            array = json.readTree(document);
            expected = json.readTree(candidate);
        } catch (java.io.IOException invalidJson) {
            return 0;
        }
        if (array == null || expected == null) return 0;
        if (!array.isArray() || !expected.isTextual()) return 0;
        for (JsonNode value : array) if (value.isTextual() && value.equals(expected)) return 1;
        return 0;
    }

    /** H2-only JSON_TYPE adapter: malformed text is not an array. Production column is native JSON. */
    public static String jsonType(String document) {
        if (document == null) return null;
        try {
            JsonNode node = new ObjectMapper().readTree(document);
            return node != null && node.isArray() ? "ARRAY" : "OTHER";
        } catch (java.io.IOException invalidJson) {
            return "INVALID";
        }
    }

    private SqlSessionFactory fixture() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:e02_23_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("create alias JSON_TYPE for \"com.chengyinhub.business.longrun.E02_23Test.jsonType\"");
        jdbc.execute("create alias JSON_CONTAINS for \"com.chengyinhub.business.longrun.E02_23Test.jsonContains\"");
        jdbc.execute("create table ums_creative_square(id bigint primary key, member_id bigint)");
        jdbc.execute("create table community_post(id bigint primary key,author_id bigint,client_request_id varchar(96),safety_labels_json varchar(500))");
        jdbc.execute("create table community_post_member_action(post_id bigint,member_id bigint,action_type varchar(32))");
        jdbc.execute("create table im_block(member_id bigint,blocked_member_id bigint)");
        jdbc.execute("create table ums_member_follow(member_id bigint,follow_member_id bigint)");
        jdbc.execute("create table cms_topic(id bigint,del_flag int,status int,user_status int)");
        jdbc.update("insert into ums_creative_square values (1,11),(2,11),(3,11),(4,11),(6,11),(7,11),(8,11),(9,11),(10,11),(11,11)");
        jdbc.update("insert into community_post values "
                + "(1,11,'legacy-square-1','[\"LEGACY_MIGRATED\"]'),"
                + "(2,11,'legacy-square-2','[]'),"
                + "(3,99,'legacy-square-3','[\"LEGACY_MIGRATED\"]'),"
                + "(4,11,'other-request','[\"LEGACY_MIGRATED\"]'),"
                + "(5,11,'legacy-square-5','[\"LEGACY_MIGRATED\"]'),"
                + "(6,11,'legacy-square-7','[\"LEGACY_MIGRATED\"]'),"
                + "(8,11,'legacy-square-8','[]'),"
                + "(9,11,'legacy-square-9','[\"LEGACY_MIGRATED_EXTRA\"]'),"
                + "(10,11,'legacy-square-10','not-json'),"
                + "(11,11,'legacy-square-11','\"LEGACY_MIGRATED\"')");
        jdbc.update("insert into community_post_member_action values (1,8,'BOOKMARK'),(2,8,'BOOKMARK'),"
                + "(3,8,'BOOKMARK'),(4,8,'BOOKMARK'),(5,8,'BOOKMARK'),(6,8,'BOOKMARK'),(8,8,'BOOKMARK'),(9,8,'BOOKMARK'),(10,8,'BOOKMARK'),(11,8,'BOOKMARK')");
        jdbc.execute("create view view_creative_square as select id,member_id,"
                + "null contents,null pics,null file_url,null address,null longitude,null latitude,"
                + "null data_id,null data_type,null like_num,null nolike_num,1 status,0 is_crowdfunding,0 del_flag,"
                + "null create_by,null create_time,null update_by,null update_time,null member_nickname,"
                + "null member_avatar,null member_user_type,null member_level_id,null comment_count,"
                + "null no_like_count,null like_count from ums_creative_square");
        Configuration c = new Configuration(new Environment("e02-23", new JdbcTransactionFactory(), ds));
        c.getTypeAliasRegistry().registerAlias("ViewCreativeSquare", ViewCreativeSquare.class);
        c.addMapper(ViewCreativeSquareMapper.class);
        String resource = "mapper/business/ViewCreativeSquareMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            new XMLMapperBuilder(input, c, resource, c.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(c);
    }
}

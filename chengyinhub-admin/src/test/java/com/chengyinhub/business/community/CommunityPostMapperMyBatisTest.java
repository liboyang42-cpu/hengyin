package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.community.CommunityPostFeedQuery;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommunityPostMapperMyBatisTest {
    @Test
    void topicReadbackIsBoundToPostAndSurvivesBodyOnlyRelationReplay() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        jdbc.update("insert into community_post_topic(post_id,topic_code) values (1,'WALK'),(1,'NIGHT'),(2,'OTHER')");
        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            List<String> before = mapper.selectTopicCodes(1L);
            assertEquals(List.of("NIGHT", "WALK"), before);
            mapper.deleteTopics(1L);
            for (String code : before) mapper.insertTopic(1L, code, new java.util.Date());
            assertEquals(before, mapper.selectTopicCodes(1L));
            assertEquals(List.of("OTHER"), mapper.selectTopicCodes(2L));
            assertEquals(List.of(), mapper.selectTopicCodes(3L));
        }
    }

    @Test
    void authorWorkspaceIncludesOnlyOwnNonDeletedLifecycleUnion() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        String[] states = {"DRAFT", "CHECKING", "PUBLISHED", "LIMITED", "HIDDEN", "REMOVED", "DELETED"};
        for (int i = 0; i < states.length; i++) {
            post(jdbc, i + 1, 11, states[i], "PUBLIC", "SH", 1, 1, states[i]);
            post(jdbc, i + 11, 12, states[i], "PUBLIC", "SH", 1, 1, "other");
        }
        jdbc.update("update community_post set client_request_id='owner-request',poi_name='owner-place' where author_id=11");
        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            List<CommunityPost> owned = mapper.selectAuthorPosts(11L, null, 20);
            assertEquals(List.of(6L, 5L, 4L, 3L, 2L, 1L), owned.stream().map(CommunityPost::getId).toList());
            for (CommunityPost row : owned) {
                assertEquals(11L, row.getAuthorId());
                assertEquals("owner-request", row.getClientRequestId());
                assertEquals("wx4g", row.getApproximateGeohash());
                assertEquals("owner-place", row.getPoiName());
            }
            assertEquals(List.of(3L, 2L), mapper.selectAuthorPosts(11L, 4L, 2).stream().map(CommunityPost::getId).toList());
            assertEquals(5, mapper.selectAuthorDrafts(11L, null, 20).size(), "existing draft contract unchanged");
        }
    }

    @Test
    void deletedRequestLookupRemainsOwnerBoundWhilePublicAndWorkspaceReadsExcludeIt() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "DELETED", "PUBLIC", "SH", 1, 1, "deleted owner");
        post(jdbc, 2, 12, "DELETED", "PUBLIC", "SH", 1, 1, "other owner");
        jdbc.update("update community_post set client_request_id='same-request'");
        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            CommunityPost own = mapper.selectByAuthorRequest(11L, "same-request");
            assertEquals(1L, own.getId());
            assertEquals(11L, own.getAuthorId());
            assertEquals("DELETED", own.getLifecycle());
            assertEquals("wx4g", own.getApproximateGeohash());
            assertEquals(2L, mapper.selectByAuthorRequest(12L, "same-request").getId());
            assertNull(mapper.selectByAuthorRequest(13L, "same-request"));
            assertNull(mapper.selectByAuthorRequest(11L, "different-request"));
            assertNull(mapper.selectById(1L, 11L));
            assertNull(mapper.selectById(1L, 12L));
            assertNull(mapper.selectById(1L, 0L));
            assertEquals(List.of(), mapper.selectAuthorPosts(11L, null, 20));
        }
    }

    @Test
    void authorDeleteEvidenceRequiresMatchingReceiptAndJobAndRejectsRestrictedErasure() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "DELETED", "PUBLIC", "SH", 1, 1, "deleted");
        post(jdbc, 2, 12, "DELETED", "PUBLIC", "SH", 1, 1, "other");
        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(0, mapper.countAuthorDeleteEvidence(11L, 1L));
            jdbc.update("insert into community_erasure_job values (1,1,'POST_DELETE','delete-1',0)");
            session.clearCache();
            assertEquals(0, mapper.countAuthorDeleteEvidence(11L, 1L), "job alone is insufficient");
            jdbc.update("insert into community_post_command_receipt values (11,'different','POST_DELETE',1)");
            session.clearCache();
            assertEquals(0, mapper.countAuthorDeleteEvidence(11L, 1L), "request must match both records");
            jdbc.update("update community_post_command_receipt set request_id='delete-1',member_id=12");
            session.clearCache();
            assertEquals(0, mapper.countAuthorDeleteEvidence(11L, 1L), "other actor cannot prove author deletion");
            jdbc.update("update community_post_command_receipt set member_id=11,aggregate_id=2");
            session.clearCache();
            assertEquals(0, mapper.countAuthorDeleteEvidence(11L, 1L), "aggregate must match");
            jdbc.update("update community_post_command_receipt set aggregate_id=1");
            session.clearCache();
            assertEquals(1, mapper.countAuthorDeleteEvidence(11L, 1L));
            assertEquals(0, mapper.countAuthorDeleteEvidence(12L, 1L));
            assertEquals(0, mapper.countAuthorDeleteEvidence(11L, 2L));
            jdbc.update("delete from community_erasure_job");
            session.clearCache();
            assertEquals(0, mapper.countAuthorDeleteEvidence(11L, 1L), "receipt alone is insufficient");
            jdbc.update("insert into community_erasure_job values (1,1,'POST_DELETE','delete-1',0)");
            for (String restriction : List.of("COPYRIGHT_REMOVE", "ACCOUNT_DEREGISTER")) {
                jdbc.update("insert into community_erasure_job values (2,1,?,'restriction',0)", restriction);
                session.clearCache();
                assertEquals(0, mapper.countAuthorDeleteEvidence(11L, 1L), restriction + " wins over old author deletion");
                jdbc.update("delete from community_erasure_job where id=2");
            }
            jdbc.update("update community_erasure_job set legal_hold=1");
            session.clearCache();
            assertEquals(0, mapper.countAuthorDeleteEvidence(11L, 1L));
            jdbc.update("update community_erasure_job set legal_hold=0");
            jdbc.update("insert into community_erasure_job values (2,1,'LOCATION_WITHDRAW','held',1)");
            session.clearCache();
            assertEquals(0, mapper.countAuthorDeleteEvidence(11L, 1L), "any legal hold takes precedence");
            jdbc.update("delete from community_erasure_job where id=2");
            jdbc.update("insert into community_post_command_receipt values (11,'copyright','COPYRIGHT_REMOVE',1)");
            session.clearCache();
            assertEquals(0, mapper.countAuthorDeleteEvidence(11L, 1L), "copyright operator ID may equal author ID");
            jdbc.update("delete from community_post_command_receipt where command_type='COPYRIGHT_REMOVE'");
            jdbc.update("insert into community_erasure_job values (2,2,'COPYRIGHT_REMOVE','other-post',0)");
            session.clearCache();
            assertEquals(1, mapper.countAuthorDeleteEvidence(11L, 1L), "another post does not affect this one");
            jdbc.update("update community_post set lifecycle='REMOVED' where id=1");
            session.clearCache();
            assertEquals(0, mapper.countAuthorDeleteEvidence(11L, 1L));
        }
    }

    @Test
    void authorWorkspacePersistsOnlyOwnNotInterestedWithoutApplyingOtherFeedPreferences() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        for (int id = 1; id <= 4; id++) post(jdbc, id, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "own");
        jdbc.update("insert into community_post_member_action values (1,12,'NOT_INTERESTED'),(2,11,'LIKE'),(3,11,'SEE_LESS_AUTHOR'),(4,11,'SEE_LESS_TOPIC')");
        jdbc.update("insert into community_post_topic(post_id,topic_code) values (1,'SAME'),(4,'SAME')");
        jdbc.update("insert into im_block values (11,11)");
        jdbc.update("insert into community_member_mute values (11,11)");
        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(List.of(4L,3L,2L,1L), mapper.selectAuthorPosts(11L,null,20).stream().map(CommunityPost::getId).toList());
            jdbc.update("insert into community_post_member_action values (2,11,'NOT_INTERESTED')");
        }
        // A new session proves the filtering is database-backed, not an in-memory removed row.
        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(List.of(4L,3L,1L), mapper.selectAuthorPosts(11L,null,20).stream().map(CommunityPost::getId).toList());
            assertEquals(List.of(3L,1L), mapper.selectAuthorPosts(11L,4L,2).stream().map(CommunityPost::getId).toList());
            assertEquals(List.of(), mapper.selectAuthorPosts(12L,null,20));
        }
        jdbc.update("delete from community_post_member_action where post_id=2 and member_id=11 and action_type='NOT_INTERESTED'");
        try (SqlSession session = factory(dataSource).openSession(true)) {
            assertEquals(List.of(4L,3L,2L,1L), session.getMapper(CommunityPostMapper.class).selectAuthorPosts(11L,null,20).stream().map(CommunityPost::getId).toList());
        }
    }

    @Test
    void bookmarksUseViewerVisibilityAndNeverExposeOtherOwnerColumns() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "public");
        post(jdbc, 2, 12, "PUBLISHED", "FOLLOWERS", "SH", 1, 1, "followers");
        post(jdbc, 3, 13, "PUBLISHED", "COMMUNITY", "SH", 1, 1, "community");
        post(jdbc, 4, 14, "HIDDEN", "PUBLIC", "SH", 1, 1, "hidden");
        post(jdbc, 5, 8, "CHECKING", "PUBLIC", "SH", 1, 1, "own checking");
        post(jdbc, 6, 8, "DELETED", "PUBLIC", "SH", 1, 1, "own deleted");
        post(jdbc, 7, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "only other bookmark");
        jdbc.update("update community_post set client_request_id='private-request',poi_name='private-place',community_id=70");
        for (long id = 1; id <= 6; id++) jdbc.update("insert into community_post_member_action values (?,8,'BOOKMARK')", id);
        jdbc.update("insert into community_post_member_action values (7,9,'BOOKMARK')");
        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(List.of(5L, 1L), mapper.selectBookmarkedPosts(8L, null, 20).stream().map(CommunityPost::getId).toList());
            jdbc.update("insert into ums_member_follow values (8,12)");
            jdbc.update("insert into club_member values (8,70,1,0)");
            session.clearCache();
            List<CommunityPost> visible = mapper.selectBookmarkedPosts(8L, null, 20);
            assertEquals(List.of(5L, 3L, 2L, 1L), visible.stream().map(CommunityPost::getId).toList());
            for (CommunityPost row : visible) {
                assertNull(row.getClientRequestId());
                assertNull(row.getApproximateGeohash());
                if (row.getAuthorId() != 8L) assertNull(row.getPoiName());
            }
            assertEquals(List.of(2L, 1L), mapper.selectBookmarkedPosts(8L, 3L, 20).stream().map(CommunityPost::getId).toList());
            assertEquals(List.of(7L), mapper.selectBookmarkedPosts(9L, null, 20).stream().map(CommunityPost::getId).toList());
            jdbc.update("insert into im_block values (12,8),(8,13)");
            jdbc.update("insert into community_member_mute values (8,11)");
            session.clearCache();
            assertEquals(List.of(5L), mapper.selectBookmarkedPosts(8L, null, 20).stream().map(CommunityPost::getId).toList());
            jdbc.update("delete from im_block");
            jdbc.update("delete from community_member_mute");
            jdbc.update("insert into community_post_member_action values (1,8,'NOT_INTERESTED'),(2,8,'SEE_LESS_AUTHOR'),(3,8,'SEE_LESS_TOPIC')");
            jdbc.update("insert into community_post_topic(post_id,topic_code) values (3,'quiet')");
            session.clearCache();
            assertEquals(List.of(5L), mapper.selectBookmarkedPosts(8L, null, 20).stream().map(CommunityPost::getId).toList());
        }
    }

    @Test
    void feedModesShareVisibilityAndPersonalStatePolicy() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "followed");
        post(jdbc, 2, 12, "PUBLISHED", "PUBLIC", "SH", 1, 1, "not-followed");
        post(jdbc, 3, 13, "PUBLISHED", "PUBLIC", "SH", 1, 1, "blocked");
        post(jdbc, 4, 14, "PUBLISHED", "PUBLIC", "BJ", 1, 1, "muted");
        post(jdbc, 5, 11, "HIDDEN", "PUBLIC", "SH", 1, 1, "hidden");
        jdbc.update("insert into ums_member_follow(member_id,follow_member_id) values (8,11),(8,13),(8,14)");
        jdbc.update("insert into im_block(member_id,blocked_member_id) values (13,8)");
        jdbc.update("insert into community_member_mute(member_id,muted_member_id) values (8,14)");
        jdbc.update("insert into community_post_member_action(post_id,member_id,action_type) values (1,8,'LIKE'),(1,8,'BOOKMARK')");
        jdbc.update("insert into community_post_topic(post_id,topic_code) values (1,'night-walk')");
        jdbc.update("insert into community_featured_entry(post_id,collection_code,starts_at,ends_at) values (1,'editor',now(),null)");
        jdbc.update("insert into community_search_document(document_type,document_id,visibility_state,search_text) values ('POST',2,'VISIBLE','projection-only-keyword')");

        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            CommunityPostFeedQuery q = query("FOLLOWING");
            List<CommunityPost> following = mapper.selectFeed(q);
            assertEquals(1, following.size());
            assertEquals(Long.valueOf(1), following.get(0).getId());
            assertEquals(Integer.valueOf(1), following.get(0).getViewerLiked());
            assertEquals(Integer.valueOf(1), following.get(0).getViewerBookmarked());
            assertEquals(Integer.valueOf(0), following.get(0).getBookmarkCount());

            q.setMode("NEARBY");
            q.setCityCode("SH");
            List<CommunityPost> nearby = mapper.selectFeed(q);
            assertEquals(2, nearby.size());
            assertNull(nearby.get(0).getApproximateGeohash(), "public feed must not project location cell");

            q.setMode("TOPIC");
            q.setTopicCode("night-walk");
            assertEquals(1, mapper.selectFeed(q).size());

            q.setMode("FEATURED");
            q.setCollectionCode("editor");
            assertEquals(1, mapper.selectFeed(q).size());

            q = query("LATEST");
            q.setKeyword("projection-only-keyword");
            List<CommunityPost> search = mapper.selectFeed(q);
            assertEquals(1, search.size(), "keyword search must consume the moderated search projection");
            assertEquals(Long.valueOf(2), search.get(0).getId());

            q = query("LATEST");
            q.setViewerId(11L);
            assertEquals(4, mapper.selectFeed(q).size(), "author feed still excludes non-published rows");
            assertEquals(1, mapper.selectAuthorDrafts(11L, null, 20).size(),
                    "author workspace exposes review states outside the public feed");
        }
    }

    @Test
    void memberCanReadDirectAndPostScopedEnforcementsForAppeal() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "owned");
        jdbc.update("insert into community_post_enforcement(id,post_id,target_member_id,enforcement_type,rule_code,guideline_version_id,reason_code,status,version,create_time) values (1,1,null,'HIDE_POST','R1',1,'reason','ACTIVE',0,now()),(2,null,11,'LIMIT_MEMBER','R2',1,'reason','ACTIVE',0,now()),(3,1,12,'LIMIT_MEMBER','R3',1,'reason','ACTIVE',0,now())");
        jdbc.update("insert into community_post_appeal(id,enforcement_id,appellant_member_id,request_id,status,create_time) values (9,1,11,'appeal-9','SUBMITTED',now())");

        try (SqlSession session = factory(dataSource).openSession(true)) {
            List<Map<String, Object>> rows = session.getMapper(CommunityPostMapper.class)
                    .selectMemberEnforcements(11L, null, 20);
            assertEquals(2, rows.size());
            assertEquals(2L, ((Number) rows.get(0).get("id")).longValue());
            assertEquals(1L, ((Number) rows.get(1).get("id")).longValue());
            assertEquals(9L, ((Number) rows.get(1).get("appeal_id")).longValue());
        }
    }

    @Test
    void commentCursorPagesRootThreadsWithoutReturningOrphanReplies() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "owned");
        jdbc.update("insert into community_post_comment(id,post_id,author_id,root_id,parent_id,body,lifecycle,author_approval_state,version,like_count,create_time) values "
                + "(10,1,11,null,null,'old root','PUBLISHED','VISIBLE',0,0,now()),"
                + "(20,1,12,null,null,'new root','PUBLISHED','VISIBLE',0,0,now()),"
                + "(21,1,13,20,20,'new reply','PUBLISHED','VISIBLE',0,0,now())");

        jdbc.update("insert into ums_member_follow(member_id,follow_member_id) values (8,13),(0,12)");
        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            List<Map<String, Object>> first = mapper.selectComments(1L, 8L, null, 1);
            assertEquals(2, first.size());
            assertEquals(20L, ((Number) first.get(0).get("id")).longValue());
            assertEquals(21L, ((Number) first.get(1).get("id")).longValue());
            assertEquals(0, ((Number) first.get(0).get("viewer_following")).intValue(), "root author is not followed");
            assertEquals(1, ((Number) first.get(1).get("viewer_following")).intValue(), "reply uses its own author relation");
            List<Map<String, Object>> second = mapper.selectComments(1L, 8L, 20L, 1);
            assertEquals(1, second.size());
            assertEquals(10L, ((Number) second.get(0).get("id")).longValue());
            assertEquals(0, ((Number) second.get(0).get("viewer_following")).intValue());
            List<Map<String, Object>> anonymous = mapper.selectComments(1L, 0L, null, 1);
            assertEquals(2, anonymous.size());
            for (Map<String, Object> row : anonymous) {
                assertEquals(0, ((Number) row.get("viewer_following")).intValue(), "anonymous must not inherit any stored relationship");
            }
        }
    }

    @Test
    void mutedAuthorsAreRemovedFromCommentThreads() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "owned");
        jdbc.update("insert into community_post_comment(id,post_id,author_id,root_id,parent_id,body,lifecycle,author_approval_state,version,like_count,create_time) values "
                + "(10,1,11,null,null,'visible root','PUBLISHED','VISIBLE',0,0,now()),"
                + "(20,1,12,null,null,'muted root','PUBLISHED','VISIBLE',0,0,now()),"
                + "(21,1,13,10,10,'muted reply','PUBLISHED','VISIBLE',0,0,now())");
        jdbc.update("insert into community_member_mute(member_id,muted_member_id) values (8,12),(8,13)");

        try (SqlSession session = factory(dataSource).openSession(true)) {
            List<Map<String, Object>> rows = session.getMapper(CommunityPostMapper.class)
                    .selectComments(1L, 8L, null, 20);
            assertEquals(1, rows.size());
            assertEquals(10L, ((Number) rows.get(0).get("id")).longValue());
        }
    }

    @Test
    void blockedOrMutedCommentAuthorsCannotBeRepliedToOrLiked() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "owned");
        jdbc.update("insert into community_post_comment(id,post_id,author_id,root_id,parent_id,body,lifecycle,author_approval_state,version,like_count,create_time) values "
                + "(20,1,12,null,null,'muted','PUBLISHED','VISIBLE',0,0,now()),"
                + "(21,1,13,null,null,'blocked','PUBLISHED','VISIBLE',0,0,now())");
        jdbc.update("insert into community_member_mute(member_id,muted_member_id) values (8,12)");
        jdbc.update("insert into im_block(member_id,blocked_member_id) values (13,8)");

        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertNull(mapper.selectCommentForReply(20L, 1L, 8L));
            assertNull(mapper.selectCommentForReply(21L, 1L, 8L));
            assertEquals(0, mapper.insertCommentAction(20L, 8L, "like-muted-1", new java.util.Date()));
            assertEquals(0, mapper.insertCommentAction(21L, 8L, "like-blocked-1", new java.util.Date()));
        }
    }

    @Test
    void disabledPostRejectsCommentLikes() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "owned");
        jdbc.update("update community_post set interaction_enabled=0 where id=1");
        jdbc.update("insert into community_post_comment(id,post_id,author_id,root_id,parent_id,body,lifecycle,author_approval_state,version,like_count,create_time) values "
                + "(20,1,12,null,null,'visible','PUBLISHED','VISIBLE',0,0,now())");

        try (SqlSession session = factory(dataSource).openSession(true)) {
            assertEquals(0, session.getMapper(CommunityPostMapper.class)
                    .insertCommentAction(20L, 8L, "like-disabled-1", new java.util.Date()));
        }
    }

    @Test
    void notificationsRespectPostLifecycleAndBlockMuteRelationships() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "visible");
        post(jdbc, 2, 11, "HIDDEN", "PUBLIC", "SH", 0, 0, "hidden");
        jdbc.update("insert into im_block(member_id,blocked_member_id) values (13,8)");
        jdbc.update("insert into community_member_mute(member_id,muted_member_id) values (8,14)");
        jdbc.update("insert into community_notification(id,recipient_member_id,event_id,post_id,actor_member_id,notification_type,aggregate_key,actor_count,payload_json,create_time,update_time) values "
                + "(1,8,'n1',1,12,'INTERACTION','1:LIKE:12',1,'{}',now(),now()),"
                + "(2,8,'n2',1,13,'INTERACTION','1:LIKE:13',1,'{}',now(),now()),"
                + "(3,8,'n3',1,14,'INTERACTION','1:LIKE:14',1,'{}',now(),now()),"
                + "(4,8,'n4',2,12,'INTERACTION','2:LIKE:12',1,'{}',now(),now()),"
                + "(5,8,'n5',2,0,'SYSTEM_GOVERNANCE','2:REMOVE',1,'{}',now(),now())");
        jdbc.update("insert into community_notification_actor(recipient_member_id,notification_type,aggregate_key,actor_member_id,last_event_id,create_time,update_time) values "
                + "(8,'INTERACTION','1:LIKE:12',12,'n1',now(),now()),"
                + "(8,'INTERACTION','1:LIKE:13',13,'n2',now(),now()),"
                + "(8,'INTERACTION','1:LIKE:14',14,'n3',now(),now()),"
                + "(8,'INTERACTION','2:LIKE:12',12,'n4',now(),now())");

        try (SqlSession session = factory(dataSource).openSession(true)) {
            List<Map<String, Object>> rows = session.getMapper(CommunityPostMapper.class)
                    .selectNotifications(8L, null, 20);
            assertEquals(2, rows.size());
            assertEquals(5L, ((Number) rows.get(0).get("id")).longValue());
            assertEquals(1L, ((Number) rows.get(1).get("id")).longValue());
        }
    }

    @Test
    void notificationsAggregateAndHonorPreferencesWithoutMutingGovernance() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "visible");
        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(1, mapper.insertNotification(8L, "e1", 1L, 12L, null,
                    "INTERACTION", "1:LIKE", "{}", new java.util.Date()));
            assertEquals(1, mapper.insertNotificationActor(8L, "INTERACTION", "1:LIKE", 12L,
                    "e1", new java.util.Date()));
            mapper.insertNotification(8L, "e2", 1L, 13L, null,
                    "INTERACTION", "1:LIKE", "{}", new java.util.Date());
            assertEquals(1, mapper.insertNotificationActor(8L, "INTERACTION", "1:LIKE", 13L,
                    "e2", new java.util.Date()));
            assertEquals(1, mapper.recomputeNotificationActorCount(8L, "INTERACTION", "1:LIKE",
                    new java.util.Date()));
            assertEquals(2, jdbc.queryForObject(
                    "select actor_count from community_notification where recipient_member_id=8 and aggregate_key='1:LIKE'",
                    Integer.class));

            jdbc.update("insert into im_block(member_id,blocked_member_id) values (8,12)");
            List<Map<String, Object>> visible = mapper.selectNotifications(8L, null, 20);
            assertEquals(1, visible.size());
            assertEquals(1, ((Number) visible.get(0).get("actor_count")).intValue());
            assertEquals(13L, ((Number) visible.get(0).get("actor_member_id")).longValue());

            assertEquals(1, mapper.upsertNotificationPreferences(8L, true, false, true, new java.util.Date()));
            assertEquals(0, mapper.insertNotification(8L, "e3", 1L, 12L, null,
                    "COMMENT_MENTION", "1:MENTION", "{}", new java.util.Date()));
            assertEquals(1, mapper.insertNotification(8L, "e4", 1L, 0L, null,
                    "SYSTEM_GOVERNANCE", "1:REMOVE", "{}", new java.util.Date()));
        }
    }

    @Test
    void editingPostMovesCommunityAndGuidelineAtomically() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "before");

        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            CommunityPost post = mapper.selectByIdForUpdate(1L);
            post.setCommunityId(55L);
            post.setGuidelineVersionId(7L);
            post.setAudience("COMMUNITY");
            assertEquals(1, mapper.updateContentCas(post, 0, "DRAFT", new java.util.Date()));
        }

        Map<String, Object> row = jdbc.queryForMap(
                "select community_id,guideline_version_id,audience from community_post where id=1");
        assertEquals(55L, ((Number) row.get("community_id")).longValue());
        assertEquals(7L, ((Number) row.get("guideline_version_id")).longValue());
        assertEquals("COMMUNITY", row.get("audience"));
    }

    @Test
    void pendingClubApplicantCannotReadOrSatisfyMembershipPolicy() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "COMMUNITY", "SH", 1, 1, "members only");
        jdbc.update("update community_post set community_id=55 where id=1");
        jdbc.update("insert into club_member(member_id,club_id,status,del_flag) values (8,55,0,0)");

        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(0, mapper.selectFeed(query("LATEST")).size());
            assertEquals(0, mapper.countCommunityMember(8L, 55L));
        }
        jdbc.update("update club_member set status=1 where member_id=8 and club_id=55");
        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(1, mapper.selectFeed(query("LATEST")).size());
            assertEquals(1, mapper.countCommunityMember(8L, 55L));
        }
    }

    @Test
    void accountLevelEnforcementKeepsValidAnonymousTargetDuringErasure() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        jdbc.update("insert into community_post_enforcement(id,post_id,target_member_id,enforcement_type,rule_code,guideline_version_id,reason_code,evidence_snapshot_json,status,version,create_time,update_time) values (4,null,11,'READ_ONLY','R4',1,'reason','{}','ACTIVE',0,now(),now())");

        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(0, mapper.scrubAccountAppeals(11L));
            assertEquals(1, mapper.scrubAccountGovernanceContent(11L));
        }

        Map<String, Object> row = jdbc.queryForMap(
                "select post_id,target_member_id,evidence_snapshot_json from community_post_enforcement where id=4");
        assertNull(row.get("post_id"));
        assertEquals(0L, ((Number) row.get("target_member_id")).longValue());
        assertEquals("{\"erased\":true}", row.get("evidence_snapshot_json"));
    }

    @Test
    void appealAnonymizationRekeysRequestsSharedByDifferentAccounts() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        jdbc.update("insert into community_post_appeal(id,enforcement_id,appellant_member_id,request_id,status,create_time) values "
                + "(41,101,11,'same-client-request','SUBMITTED',now()),"
                + "(42,102,12,'same-client-request','SUBMITTED',now())");

        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(1, mapper.anonymizeAccountAppeals(11L));
            assertEquals(1, mapper.anonymizeAccountAppeals(12L));
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,appellant_member_id,request_id from community_post_appeal order by id");
        assertEquals(0L, ((Number) rows.get(0).get("appellant_member_id")).longValue());
        assertEquals("erased-appeal-41", rows.get(0).get("request_id"));
        assertEquals(0L, ((Number) rows.get(1).get("appellant_member_id")).longValue());
        assertEquals("erased-appeal-42", rows.get(1).get("request_id"));
    }

    private CommunityPostFeedQuery query(String mode) {
        CommunityPostFeedQuery query = new CommunityPostFeedQuery();
        query.setViewerId(8L);
        query.setMode(mode);
        query.setLimit(20);
        return query;
    }

    @Test
    void checkingContentCasKeepsOwnerVersionAndRemovedBoundaries() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "CHECKING", "PUBLIC", "SH", 1, 1, "old");
        jdbc.update("update community_post set version=4 where id=1");
        CommunityPost replacement = new CommunityPost();
        replacement.setId(1L); replacement.setAuthorId(11L); replacement.setBody("new");
        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            replacement.setAuthorId(12L);
            assertEquals(0, mapper.updateContentCas(replacement, 4, "CHECKING", new java.util.Date()));
            replacement.setAuthorId(11L);
            assertEquals(0, mapper.updateContentCas(replacement, 3, "CHECKING", new java.util.Date()));
            assertEquals(1, mapper.updateContentCas(replacement, 4, "CHECKING", new java.util.Date()));
            assertEquals("new", jdbc.queryForObject("select body from community_post where id=1", String.class));
            assertEquals(5, jdbc.queryForObject("select version from community_post where id=1", Integer.class));
            assertEquals("CHECKING", jdbc.queryForObject("select lifecycle from community_post where id=1", String.class));
            for (String state : new String[] {"REMOVED", "DELETED"}) {
                jdbc.update("update community_post set lifecycle=? where id=1", state);
                assertEquals(0, mapper.updateContentCas(replacement, 5, "CHECKING", new java.util.Date()));
            }
        }
    }

    private void schema(JdbcTemplate jdbc) {
        jdbc.execute("create table community_post(id bigint primary key,author_id bigint,community_id bigint,client_request_id varchar(96),body varchar(100),title varchar(100),post_type varchar(32),audience varchar(16),comment_policy varchar(16),reply_approval_enabled int,slow_mode_seconds int,lifecycle varchar(16),location_precision varchar(16),city_code varchar(32),poi_name varchar(100),approximate_geohash varchar(16),recommendation_eligible int,search_eligible int,interaction_enabled int,disclosure_type varchar(24),safety_labels_json varchar(100),guideline_version_id bigint,like_count int,comment_count int,bookmark_count int,share_count int,published_at timestamp,edited_at timestamp,deleted_at timestamp,version int,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table community_post_command_receipt(member_id bigint,request_id varchar(96),command_type varchar(32),aggregate_id bigint)");
        jdbc.execute("create table community_erasure_job(id bigint primary key,post_id bigint,trigger_type varchar(32),request_id varchar(96),legal_hold int)");
        jdbc.execute("create table ums_member(id bigint primary key,nickname varchar(100),avatar varchar(100))");
        jdbc.execute("create table ums_member_follow(member_id bigint,follow_member_id bigint)");
        jdbc.execute("create table im_block(member_id bigint,blocked_member_id bigint)");
        jdbc.execute("create table community_member_mute(member_id bigint,muted_member_id bigint)");
        jdbc.execute("create table club_member(member_id bigint,club_id bigint,status int,del_flag int)");
        jdbc.execute("create table community_post_member_action(post_id bigint,member_id bigint,action_type varchar(32))");
        jdbc.execute("create table community_post_topic(post_id bigint,topic_code varchar(64),create_time timestamp)");
        jdbc.execute("create table community_featured_entry(post_id bigint,collection_code varchar(64),starts_at timestamp,ends_at timestamp)");
        jdbc.execute("create table community_search_document(document_type varchar(16),document_id bigint,visibility_state varchar(16),search_text varchar(255))");
        jdbc.execute("create table community_post_enforcement(id bigint primary key,post_id bigint,target_member_id bigint,enforcement_type varchar(32),rule_code varchar(64),guideline_version_id bigint,reason_code varchar(64),previous_lifecycle varchar(16),evidence_snapshot_json varchar(255),operator_user_id bigint,starts_at timestamp,ends_at timestamp,status varchar(16),version int,create_time timestamp,update_time timestamp,constraint chk_community_enforcement_target check (post_id is not null or target_member_id is not null))");
        jdbc.execute("create table community_post_appeal(id bigint primary key,enforcement_id bigint,appellant_member_id bigint,request_id varchar(96) not null,reason varchar(255),evidence_asset_ids_json varchar(255),status varchar(16),decision_reason varchar(255),reviewed_at timestamp,create_time timestamp,update_time timestamp,unique(appellant_member_id,request_id))");
        jdbc.execute("create table community_post_comment(id bigint primary key,post_id bigint,author_id bigint,root_id bigint,parent_id bigint,replied_to_member_id bigint,body varchar(100),lifecycle varchar(16),author_approval_state varchar(16),version int,like_count int,create_time timestamp)");
        jdbc.execute("create table community_post_comment_action(comment_id bigint,member_id bigint,action_type varchar(32),request_id varchar(96),create_time timestamp)");
        jdbc.execute("create table community_notification(id bigint auto_increment primary key,recipient_member_id bigint,event_id varchar(36),post_id bigint,comment_id bigint,actor_member_id bigint,notification_type varchar(32),aggregate_key varchar(128),actor_count int,payload_json varchar(255),read_at timestamp,create_time timestamp,update_time timestamp,unique(recipient_member_id,event_id),unique(recipient_member_id,notification_type,aggregate_key))");
        jdbc.execute("create table community_notification_preference(member_id bigint primary key,interaction_enabled int,mention_enabled int,social_enabled int,create_time timestamp,update_time timestamp)");
        jdbc.execute("create table community_notification_actor(recipient_member_id bigint,notification_type varchar(32),aggregate_key varchar(128),actor_member_id bigint,last_event_id varchar(36),create_time timestamp,update_time timestamp,primary key(recipient_member_id,notification_type,aggregate_key,actor_member_id))");
        jdbc.update("insert into ums_member values (11,'a',''),(12,'b',''),(13,'c',''),(14,'d','')");
    }

    private void post(JdbcTemplate jdbc, long id, long author, String lifecycle, String audience,
                      String city, int recommendation, int search, String body) {
        jdbc.update("insert into community_post(id,author_id,body,post_type,audience,comment_policy,reply_approval_enabled,slow_mode_seconds,lifecycle,location_precision,city_code,approximate_geohash,recommendation_eligible,search_eligible,interaction_enabled,disclosure_type,safety_labels_json,guideline_version_id,like_count,comment_count,bookmark_count,share_count,published_at,version,create_time,update_time) values (?,?,?,'MOMENT',?,'EVERYONE',0,0,?,'APPROXIMATE',?,'wx4g',?,?,1,'NONE','[]',1,0,0,0,0,now(),0,now(),now())",
                id, author, body, audience, lifecycle, city, recommendation, search);
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:community_post_mapper_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        return ds;
    }

    private SqlSessionFactory factory(DriverManagerDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "community-post", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("CommunityPost", CommunityPost.class);
        configuration.addMapper(CommunityPostMapper.class);
        String resource = "mapper/business/CommunityPostMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}

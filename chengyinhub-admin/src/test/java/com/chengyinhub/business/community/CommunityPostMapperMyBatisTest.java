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
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CommunityPostMapperMyBatisTest {
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
    void personalListsUseOnlyCanonicalOwnedActionsAndKeepReviewStates() throws Exception {
        DriverManagerDataSource ds = dataSource(); JdbcTemplate jdbc = new JdbcTemplate(ds); schema(jdbc);
        String[] states = {"DRAFT", "CHECKING", "PUBLISHED", "LIMITED", "HIDDEN", "REMOVED", "DELETED"};
        for (int i = 0; i < states.length; i++) post(jdbc, 10 + i, 8, states[i], "PUBLIC", "SH", 1, 1, "own");
        post(jdbc, 30, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "public");
        post(jdbc, 31, 12, "PUBLISHED", "FOLLOWERS", "SH", 1, 1, "private");
        post(jdbc, 32, 13, "PUBLISHED", "PUBLIC", "SH", 1, 1, "blocked");
        post(jdbc, 33, 11, "CHECKING", "PUBLIC", "SH", 1, 1, "checking");
        jdbc.update("insert into im_block(member_id,blocked_member_id) values (13,8)");
        jdbc.update("insert into community_post_member_action(post_id,member_id,action_type) values (30,8,'BOOKMARK'),(31,8,'BOOKMARK'),(32,8,'BOOKMARK'),(33,8,'BOOKMARK'),(12,9,'BOOKMARK')");
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            CommunityPostFeedQuery q = query("BOOKMARKS");
            List<CommunityPost> favorites = mapper.selectBookmarkedFeed(q);
            assertEquals(1, favorites.size()); assertEquals(Long.valueOf(30), favorites.get(0).getId());
            assertEquals(Integer.valueOf(1), favorites.get(0).getViewerBookmarked());
            assertNull(favorites.get(0).getApproximateGeohash());
            q.setCursor(30L); assertEquals(0, mapper.selectBookmarkedFeed(q).size()); q.setCursor(null);
            jdbc.update("delete from community_post_member_action where member_id=8 and post_id=30 and action_type='BOOKMARK'");
            session.clearCache();
            assertEquals(0, mapper.selectBookmarkedFeed(q).size());
            q.setMode("OWN_POSTS"); List<CommunityPost> own = mapper.selectOwnPosts(q);
            assertEquals(6, own.size()); assertEquals(Long.valueOf(15), own.get(0).getId());
            for (CommunityPost row : own) assertEquals(Long.valueOf(8), row.getAuthorId());
            q.setCursor(13L); own = mapper.selectOwnPosts(q);
            assertEquals(3, own.size()); assertEquals(Long.valueOf(12), own.get(0).getId());
            q.setViewerId(9L); assertEquals(0, mapper.selectOwnPosts(q).size());
        }
    }

    @Test
    void ownerDetailRetainsOriginalLocationAndTopicsWithoutPublicGridDisclosure() throws Exception {
        DriverManagerDataSource ds = dataSource(); JdbcTemplate jdbc = new JdbcTemplate(ds); schema(jdbc);
        post(jdbc, 40, 8, "PUBLISHED", "PUBLIC", "SH", 1, 1, "located");
        jdbc.update("update community_post set location_precision='APPROXIMATE', approximate_geohash='original-grid' where id=40");
        jdbc.update("insert into community_post_topic(post_id,topic_code) values (40,'WALK'),(40,'CITY')");
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals("original-grid",mapper.selectById(40L,8L).getApproximateGeohash());
            assertNull(mapper.selectById(40L,9L).getApproximateGeohash());
            assertNull(mapper.selectById(40L,0L).getApproximateGeohash());
            assertEquals(java.util.Arrays.asList("CITY", "WALK"),mapper.selectTopicCodes(40L));
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
    void commentFollowingProjectionUsesCurrentViewerAndActualAuthor() throws Exception {
        DriverManagerDataSource ds = dataSource(); JdbcTemplate jdbc = new JdbcTemplate(ds); schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "owned");
        jdbc.update("insert into ums_member_follow(member_id,follow_member_id) values (8,11),(9,12)");
        jdbc.update("insert into community_post_comment(id,post_id,author_id,root_id,parent_id,body,lifecycle,author_approval_state,version,like_count,create_time) values "
                + "(10,1,11,null,null,'followed','PUBLISHED','VISIBLE',0,0,now()),"
                + "(20,1,12,null,null,'not followed','PUBLISHED','VISIBLE',0,0,now()),"
                + "(30,1,8,null,null,'self','PUBLISHED','VISIBLE',0,0,now())");
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            List<Map<String,Object>> rows = mapper.selectComments(1L,8L,null,20);
            assertEquals(3,rows.size());
            assertEquals(0,((Number)rows.get(0).get("viewer_following")).intValue());
            assertEquals(0,((Number)rows.get(1).get("viewer_following")).intValue());
            assertEquals(1,((Number)rows.get(2).get("viewer_following")).intValue());
            for (Map<String,Object> row : mapper.selectComments(1L,0L,null,20))
                assertEquals(0,((Number)row.get("viewer_following")).intValue());
            List<Map<String,Object>> other = mapper.selectComments(1L,9L,null,20);
            assertEquals(1,((Number)other.get(1).get("viewer_following")).intValue());
            assertEquals(0,((Number)other.get(2).get("viewer_following")).intValue());
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

        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            List<Map<String, Object>> first = mapper.selectComments(1L, 8L, null, 1);
            assertEquals(2, first.size());
            assertEquals(20L, ((Number) first.get(0).get("id")).longValue());
            assertEquals(21L, ((Number) first.get(1).get("id")).longValue());
            List<Map<String, Object>> second = mapper.selectComments(1L, 8L, 20L, 1);
            assertEquals(1, second.size());
            assertEquals(10L, ((Number) second.get(0).get("id")).longValue());
        }
    }

    @Test
    void deletedRootsKeepVisibleDescendantsWithRedactedAncestorsAndStableRootPaging() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "owned");
        jdbc.update("insert into community_post_comment(id,post_id,author_id,root_id,parent_id,body,lifecycle,author_approval_state,version,like_count,create_time) values "
                + "(10,1,11,null,null,'old normal root','PUBLISHED','VISIBLE',0,0,now()),"
                + "(20,1,12,null,null,'private deleted root','DELETED','VISIBLE',1,0,now()),"
                + "(21,1,13,20,20,'private deleted middle','DELETED','VISIBLE',1,0,now()),"
                + "(22,1,14,20,21,'visible grandchild','PUBLISHED','VISIBLE',0,0,now()),"
                + "(23,1,12,20,20,'private deleted leaf','DELETED','VISIBLE',1,0,now()),"
                + "(24,2,14,20,20,'foreign post reply','PUBLISHED','VISIBLE',0,0,now())");
        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            List<Map<String, Object>> first = mapper.selectComments(1L, 8L, null, 1);
            assertEquals(List.of(20L, 21L, 22L, 23L), first.stream()
                    .map(row -> ((Number) row.get("id")).longValue()).toList());
            assertEquals("", first.get(0).get("body"));
            assertEquals("", first.get(1).get("body"));
            assertEquals("visible grandchild", first.get(2).get("body"));
            assertEquals("", first.get(3).get("body"), "even pruned deleted leaves must never load original text");
            List<Map<String, Object>> second = mapper.selectComments(1L, 8L, 20L, 1);
            assertEquals(List.of(10L), second.stream().map(row -> ((Number) row.get("id")).longValue()).toList());
            assertEquals(List.of(), mapper.selectComments(1L, 8L, 10L, 1));
            assertNull(mapper.selectCommentForReply(20L, 1L, 8L));
            assertNull(mapper.selectCommentForReply(21L, 1L, 8L));
            assertEquals(0, mapper.insertCommentAction(20L, 8L, "like-deleted-root", new java.util.Date()));
            assertEquals(0, mapper.insertCommentAction(21L, 8L, "like-deleted-middle", new java.util.Date()));
            assertEquals(0, jdbc.queryForObject("select count(*) from community_post_comment_action", Integer.class));
        }
    }

    @Test
    void deletedRootsWithoutLegallyVisibleRepliesDoNotConsumePageSlots() throws Exception {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "owned");
        jdbc.update("insert into community_post_comment(id,post_id,author_id,root_id,parent_id,body,lifecycle,author_approval_state,version,like_count,create_time) values "
                + "(10,1,11,null,null,'normal root','PUBLISHED','VISIBLE',0,0,now()),"
                + "(20,1,11,null,null,'no replies','DELETED','VISIBLE',1,0,now()),"
                + "(30,1,11,null,null,'pending replies','DELETED','VISIBLE',1,0,now()),"
                + "(31,1,12,30,30,'pending','PUBLISHED','PENDING',0,0,now()),"
                + "(40,1,11,null,null,'checking replies','DELETED','VISIBLE',1,0,now()),"
                + "(41,1,12,40,40,'checking','CHECKING','VISIBLE',0,0,now()),"
                + "(50,1,11,null,null,'muted replies','DELETED','VISIBLE',1,0,now()),"
                + "(51,1,13,50,50,'muted','PUBLISHED','VISIBLE',0,0,now()),"
                + "(60,1,11,null,null,'blocked replies','DELETED','VISIBLE',1,0,now()),"
                + "(61,1,14,60,60,'blocked','PUBLISHED','VISIBLE',0,0,now()),"
                + "(70,1,14,null,null,'blocked root','DELETED','VISIBLE',1,0,now()),"
                + "(71,1,11,70,70,'visible reply','PUBLISHED','VISIBLE',0,0,now()),"
                + "(80,1,11,null,null,'foreign replies','DELETED','VISIBLE',1,0,now()),"
                + "(81,2,11,80,80,'foreign','PUBLISHED','VISIBLE',0,0,now())");
        jdbc.update("insert into community_member_mute(member_id,muted_member_id) values (8,13)");
        jdbc.update("insert into im_block(member_id,blocked_member_id) values (14,8)");
        try (SqlSession session = factory(dataSource).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            List<Map<String, Object>> rows = mapper.selectComments(1L, 8L, null, 1);
            assertEquals(List.of(10L), rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList());
            List<Map<String, Object>> ownerRows = mapper.selectComments(1L, 12L, 50L, 1);
            assertEquals(List.of(40L, 41L), ownerRows.stream()
                    .map(row -> ((Number) row.get("id")).longValue()).toList(), "existing own CHECKING visibility remains unchanged");
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

    /** 审核分级 P2:标记列 null=保持原值,且不 bump version(生命周期 CAS 已在同一事务里 bump 过)。 */
    @Test
    void auditMarksKeepUnspecifiedColumnsAndDoNotBumpVersion() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        schema(jdbc);
        post(jdbc, 1, 11, "PUBLISHED", "PUBLIC", "SH", 1, 1, "body");
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(1, mapper.updateAuditMarks(1L, 1, null, 1, new java.util.Date()));
            assertEquals(1, mapper.updateAuditMarks(1L, 2, 1, null, new java.util.Date()));
            assertEquals(0, mapper.updateAuditMarks(99L, 2, 1, 1, new java.util.Date()));
        }
        assertEquals(java.util.Arrays.asList(2, 1, 1, 0), jdbc.queryForObject(
                "select audit_level,audit_before_publish,review_flag,version from community_post where id=1",
                (rs, row) -> java.util.Arrays.asList(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4))));
    }

    /** AUDIT-G3-5/6:处置行回指举报单/评论只写一次;已关联的行不会被另一个举报改写。 */
    @Test
    void linkEnforcementWritesReportAndCommentOnce() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        schema(jdbc);
        jdbc.update("insert into community_post_enforcement(id,post_id,target_member_id,enforcement_type,rule_code,guideline_version_id,reason_code,evidence_snapshot_json,status,version,create_time,update_time) values (5,null,12,'REMOVE','R5',1,'reason','{}','ACTIVE',0,now(),now())");
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(1, mapper.linkEnforcement(5L, 88L, 55L));
            assertEquals(0, mapper.linkEnforcement(5L, 99L, null));
            assertEquals(Long.valueOf(55L), mapper.selectEnforcement(5L).get("comment_id"));
        }
        assertEquals(java.util.Arrays.asList(88L, 55L), jdbc.queryForObject(
                "select report_case_id,comment_id from community_post_enforcement where id=5",
                (rs, row) -> java.util.Arrays.asList(rs.getLong(1), rs.getLong(2))));
    }

    /** AUDIT-G3-1:待审被驳回的帖子申诉撤销后恢复 PUBLISHED,补上 published_at;已有的发布时间不被改写。 */
    @Test
    void reconcileToPublishedFillsMissingPublishedAtOnly() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        schema(jdbc);
        post(jdbc, 1, 11, "REMOVED", "PUBLIC", "SH", 0, 0, "never published");
        post(jdbc, 2, 11, "REMOVED", "PUBLIC", "SH", 0, 0, "published before");
        jdbc.update("update community_post set published_at=null where id=1");
        jdbc.update("update community_post set published_at=timestamp '2026-01-01 00:00:00' where id=2");
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            assertEquals(1, mapper.reconcilePostEnforcementCas(1L, 0, "PUBLISHED", 1, 1, 1, new java.util.Date()));
            assertEquals(1, mapper.reconcilePostEnforcementCas(2L, 0, "PUBLISHED", 1, 1, 1, new java.util.Date()));
        }
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from community_post where id=1 and published_at is not null", Integer.class));
        assertEquals("2026-01-01", jdbc.queryForObject(
                "select cast(cast(published_at as date) as varchar) from community_post where id=2", String.class));
    }

    @Test
    void checkingEditRetainsOwnerVersionAndRemovedDeletedGuards() throws Exception {
        DriverManagerDataSource ds = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        schema(jdbc);
        post(jdbc, 1, 11, "CHECKING", "PUBLIC", "SH", 1, 1, "before");
        post(jdbc, 2, 11, "REMOVED", "PUBLIC", "SH", 1, 1, "removed");
        post(jdbc, 3, 11, "DELETED", "PUBLIC", "SH", 1, 1, "deleted");
        try (SqlSession session = factory(ds).openSession(true)) {
            CommunityPostMapper mapper = session.getMapper(CommunityPostMapper.class);
            CommunityPost changed = mapper.selectByIdForUpdate(1L);
            changed.setBody("after");
            assertEquals(1, mapper.updateContentCas(changed, 0, "CHECKING", new java.util.Date()));
            assertEquals(0, mapper.updateContentCas(changed, 0, "CHECKING", new java.util.Date()));
            changed.setAuthorId(12L);
            assertEquals(0, mapper.updateContentCas(changed, 1, "CHECKING", new java.util.Date()));
            for (long id : new long[]{2L, 3L}) {
                assertEquals(0, mapper.updateContentCas(mapper.selectByIdForUpdate(id), 0,
                        "CHECKING", new java.util.Date()));
            }
        }
        assertEquals("after", jdbc.queryForObject("select body from community_post where id=1", String.class));
        assertEquals(1, jdbc.queryForObject("select version from community_post where id=1", Integer.class));
        assertEquals("CHECKING", jdbc.queryForObject("select lifecycle from community_post where id=1", String.class));
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
    void activityReferenceProjectsOnlyItsCurrentPublicTopicAndPreservesLegacySnapshot() throws Exception {
        DriverManagerDataSource ds = dataSource(); JdbcTemplate jdbc = new JdbcTemplate(ds); schema(jdbc);
        jdbc.execute("create table community_post_reference(id bigint primary key,post_id bigint,reference_type varchar(24),reference_id bigint,snapshot_json varchar(255),privacy_snapshot varchar(32),invalidated_at timestamp)");
        jdbc.execute("create table cms_activity(id bigint primary key,topic_id bigint,del_flag int)");
        jdbc.execute("create table cms_topic(id bigint primary key,status int,user_status int,del_flag int,is_template int)");
        jdbc.update("insert into cms_topic values (900,1,1,0,0),(901,0,1,0,0),(902,1,0,0,0),(903,1,1,1,0),(904,1,1,0,1)");
        jdbc.update("insert into cms_activity values (70,900,0),(71,null,0),(72,901,0),(73,902,0),(74,903,0),(75,904,0),(76,900,1)");
        jdbc.update("insert into community_post_reference(id,post_id,reference_type,reference_id,snapshot_json) values (1,56,'ACTIVITY',70,'{\"legacy\":true}'),(2,56,'ACTIVITY',71,'{}'),(3,56,'ACTIVITY',72,'{}'),(4,56,'ACTIVITY',73,'{}'),(5,56,'ACTIVITY',74,'{}'),(6,56,'ACTIVITY',75,'{}'),(7,56,'ACTIVITY',76,'{}')");
        try (SqlSession session = factory(ds).openSession(true)) {
            List<Map<String, Object>> refs = session.getMapper(CommunityPostMapper.class).selectReferences(56L);
            assertEquals(7, refs.size());
            assertNotNull(refs.get(0).get("topic_id"), "activity reference must project its real public topic ID");
            assertEquals(900L, ((Number) refs.get(0).get("topic_id")).longValue());
            assertEquals(70L, ((Number) refs.get(0).get("reference_id")).longValue());
            assertEquals("{\"legacy\":true}", refs.get(0).get("snapshot_json"));
            for (int j = 1; j < refs.size(); j++) assertNull(refs.get(j).get("topic_id"));
        }
    }

    private void schema(JdbcTemplate jdbc) {
        jdbc.execute("create table community_post(id bigint primary key,author_id bigint,community_id bigint,client_request_id varchar(96),body varchar(100),title varchar(100),post_type varchar(32),audience varchar(16),comment_policy varchar(16),reply_approval_enabled int,slow_mode_seconds int,lifecycle varchar(16),location_precision varchar(16),city_code varchar(32),poi_name varchar(100),approximate_geohash varchar(16),recommendation_eligible int,search_eligible int,interaction_enabled int,disclosure_type varchar(24),safety_labels_json varchar(100),guideline_version_id bigint,like_count int,comment_count int,bookmark_count int,share_count int,published_at timestamp,edited_at timestamp,deleted_at timestamp,version int,create_time timestamp,update_time timestamp,audit_level tinyint not null default 0,audit_before_publish tinyint not null default 0,review_flag tinyint not null default 0)");
        jdbc.execute("create table ums_member(id bigint primary key,nickname varchar(100),avatar varchar(100))");
        jdbc.execute("create table ums_member_follow(member_id bigint,follow_member_id bigint)");
        jdbc.execute("create table im_block(member_id bigint,blocked_member_id bigint)");
        jdbc.execute("create table community_member_mute(member_id bigint,muted_member_id bigint)");
        jdbc.execute("create table club_member(member_id bigint,club_id bigint,status int,del_flag int)");
        jdbc.execute("create table community_post_member_action(post_id bigint,member_id bigint,action_type varchar(32))");
        jdbc.execute("create table community_post_topic(post_id bigint,topic_code varchar(64))");
        jdbc.execute("create table community_featured_entry(post_id bigint,collection_code varchar(64),starts_at timestamp,ends_at timestamp)");
        jdbc.execute("create table community_search_document(document_type varchar(16),document_id bigint,visibility_state varchar(16),search_text varchar(255))");
        jdbc.execute("create table community_post_enforcement(id bigint primary key,post_id bigint,target_member_id bigint,enforcement_type varchar(32),rule_code varchar(64),guideline_version_id bigint,reason_code varchar(64),previous_lifecycle varchar(16),evidence_snapshot_json varchar(255),operator_user_id bigint,starts_at timestamp,ends_at timestamp,status varchar(16),version int,create_time timestamp,update_time timestamp,report_case_id bigint,comment_id bigint,constraint chk_community_enforcement_target check (post_id is not null or target_member_id is not null))");
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

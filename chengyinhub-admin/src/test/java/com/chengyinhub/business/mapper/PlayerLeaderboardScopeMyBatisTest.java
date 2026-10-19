package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.impl.PlayerNodeProgressServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.ThemeRuntimeFeaturePolicy;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;

class PlayerLeaderboardScopeMyBatisTest
{
    private static SqlSessionFactory factory;
    private long nextId = 100;
    private static final String THEME = "{\"themeConfig\":{}}";

    // Single base schema shared by independent databases. Keep it pre-instance so the
    // missing-schema negative control is not accidentally upgraded with the normal fixture.
    private static void createLeaderboardBaseTables(Statement st) throws java.sql.SQLException {
        st.execute("create table player_node_progress(member_id bigint,activity_id bigint,topic_id bigint," +
                "status int,del_flag int,checkin_time timestamp)");
        st.execute("create table ums_member(id bigint,nickname varchar(50),avatar varchar(255))");
    }

    @BeforeAll
    static void setUp() throws Exception
    {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:leaderboard_scope;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("PlayerNodeProgress", PlayerNodeProgress.class);
        String resource = "mapper/business/PlayerNodeProgressMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        resource = "mapper/business/TopicRouteSessionMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement st = session.getConnection().createStatement())
        {
            st.execute("create table topic_route_session(id bigint primary key,activity_id bigint,topic_id bigint," +
                    "member_id bigint,play_instance_id varchar(64),config_version int,config_snapshot_json clob," +
                    "state_json clob,status int,current_node_id bigint,recommended_node_id bigint,seed bigint," +
                    "version int,completed_at timestamp,create_time timestamp,update_time timestamp)");
            createLeaderboardBaseTables(st);
            st.execute("alter table ums_member alter column id set not null");
            st.execute("alter table ums_member add primary key (id)");
            st.execute("alter table player_node_progress add column id bigint primary key before member_id");
            st.execute("alter table player_node_progress add column node_id bigint before status");
            st.execute("insert into ums_member values (1, '自玩玩家', 'a'), (2, '活动玩家', 'b')");
            st.execute("alter table player_node_progress add column play_instance_id varchar(64) default 'default'");
            st.execute("alter table player_node_progress add column pic_url varchar(255)");
            st.execute("alter table player_node_progress add column review_status int");
            st.execute("alter table player_node_progress add column longitude varchar(50)");
            st.execute("alter table player_node_progress add column latitude varchar(50)");
            st.execute("alter table player_node_progress add column puzzle_score int");
            st.execute("alter table player_node_progress add column completion_mode varchar(30)");
            st.execute("alter table player_node_progress add column create_time timestamp");
            st.execute("insert into player_node_progress(id,member_id,activity_id,topic_id,node_id,status,checkin_time,del_flag) values " +
                    "(1, 1, 0, 300, 11, 1, timestamp '2026-08-21 10:00:00', 0)," +
                    "(2, 2, 500, 300, 12, 1, timestamp '2026-08-21 10:01:00', 0)");
        }
    }

    @Test
    void topicLeaderboardOnlyCountsSelfPlaySession()
    {
        try (SqlSession session = factory.openSession(true))
        {
            List<LeaderboardItemVO> rows = session.getMapper(PlayerNodeProgressMapper.class)
                    .selectActivityLeaderboard(null, 300L, 50);
            assertEquals(1, rows.size());
            assertEquals(Long.valueOf(1L), rows.get(0).getMemberId());
        }
    }

    @Test
    void activityLeaderboardStillCountsItsOwnSession()
    {
        try (SqlSession session = factory.openSession(true))
        {
            List<LeaderboardItemVO> rows = session.getMapper(PlayerNodeProgressMapper.class)
                    .selectActivityLeaderboard(500L, null, 50);
            assertEquals(1, rows.size());
            assertEquals(Long.valueOf(2L), rows.get(0).getMemberId());
        }
    }

    @BeforeEach
    void resetAddedFixtures() throws Exception {
        nextId = 100;
        try (SqlSession session = factory.openSession(true); Statement st = session.getConnection().createStatement()) {
            st.execute("delete from topic_route_session");
            st.execute("delete from player_node_progress where id >= 100");
            st.execute("delete from ums_member where id > 2");
        }
    }

    @Test
    void memberOnlyReadsUseCurrentInstanceForEachExactOwnerTopicAndActivity() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            route(session, 7, 710, 0, "default", "{}");
            route(session, 7, 710, 0, "stray-legacy", "{}");
            long legacy = progress(session, 7, 710, 0, "default", 1, 0, "10:00:00");
            progress(session, 7, 710, 0, "stray-legacy", 1, 0, "10:01:00");
            route(session, 7, 710, 500, "default", THEME);
            route(session, 7, 710, 500, "a-current", THEME);
            progress(session, 7, 710, 500, "default", 1, 0, "10:00:00");
            long currentA = progress(session, 7, 710, 500, "a-current", 1, 0, "10:02:00");
            route(session, 7, 710, 600, "b-current", THEME);
            long currentB = progress(session, 7, 710, 600, "b-current", 0, 0, "10:03:00");
            route(session, 7, 720, 500, "other-topic", THEME);
            long otherTopic = progress(session, 7, 720, 500, "other-topic", 1, 0, "10:04:00");
            route(session, 8, 710, 500, "foreign-current", THEME);
            progress(session, 8, 710, 500, "foreign-current", 1, 0, "10:04:00");
            progress(session, 7, 710, 500, "a-current", 1, 1, "10:05:00");
            PlayerNodeProgressServiceImpl service = service(session, true);
            List<PlayerNodeProgress> rows = service.selectPlayerNodeProgressList(owner(7));
            assertEquals(Arrays.asList(legacy, currentA, otherTopic, currentB), ids(rows));
            assertTrue(rows.stream().allMatch(row -> row.getMemberId().equals(7L)));
            PlayerNodeProgress topic = owner(7); topic.setTopicId(710L);
            assertEquals(Arrays.asList(legacy, currentA, currentB), ids(service.selectPlayerNodeProgressList(topic)));
            PlayerNodeProgress activity = owner(7); activity.setActivityId(500L);
            assertEquals(Arrays.asList(currentA, otherTopic), ids(service.selectPlayerNodeProgressList(activity)));
            PlayerNodeProgress node = owner(7); node.setNodeId(currentA);
            assertEquals(Collections.singletonList(currentA), ids(service.selectPlayerNodeProgressList(node)));
        }
    }

    @Test
    void memberOnlyCallerCannotOverrideAnyResolvedInstance() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            PlayerNodeProgress probe = owner(1); probe.setPlayInstanceId("default");
            assertThrows(ServiceException.class, () -> service(session, true).selectPlayerNodeProgressList(probe));
            probe.setPlayInstanceId("replay-client");
            assertThrows(ServiceException.class, () -> service(session, true).selectPlayerNodeProgressList(probe));
        }
    }

    @Test
    void replayWithoutCurrentProgressCannotReuseHistoricalCompletion() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            route(session, 7, 710, 500, "old", THEME);
            progress(session, 7, 710, 500, "old", 1, 0, "10:00:00");
            route(session, 7, 710, 500, "fresh", THEME);
            assertTrue(service(session, true).selectPlayerNodeProgressList(owner(7)).isEmpty());
            assertTrue(service(session, true).selectActivityLeaderboard(500L, 710L, 50).isEmpty());
        }
    }

    @Test
    void currentLeaderboardPreservesScoreTimeMemberTieBreakAndPublicFields() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            for (int member = 7; member <= 10; member++) {
                member(session, member);
                route(session, member, 710, 500, "old", THEME);
                progress(session, member, 710, 500, "old", 1, 0, "09:00:00");
                route(session, member, 710, 500, "current-" + member, THEME);
                progress(session, member, 710, 500, "current-" + member, 1, 0, "10:00:00");
            }
            progress(session, 7, 710, 500, "current-7", 1, 0, "10:05:00");
            progress(session, 8, 710, 500, "current-8", 1, 0, "10:04:00");
            progress(session, 9, 710, 500, "current-9", 1, 0, "10:04:00");
            progress(session, 10, 710, 500, "current-10", 0, 0, "10:01:00");
            progress(session, 10, 710, 500, "current-10", 1, 1, "10:01:00");
            progress(session, 10, 710, 600, "current-10", 1, 0, "10:01:00");
            progress(session, 10, 720, 500, "current-10", 1, 0, "10:01:00");
            PlayerNodeProgressServiceImpl service = service(session, true);
            List<LeaderboardItemVO> rows = service.selectActivityLeaderboard(500L, 710L, 50);
            assertEquals(Arrays.asList(8L, 9L, 7L, 10L), members(rows));
            assertEquals(Arrays.asList(2L, 2L, 2L, 1L), rows.stream().map(LeaderboardItemVO::getScore).collect(Collectors.toList()));
            assertEquals(Arrays.asList(8L, 9L), members(service.selectActivityLeaderboard(500L, 710L, 2)));
            assertTrue(service.selectActivityLeaderboard(500L, 710L, 0).isEmpty());
            for (LeaderboardItemVO row : rows) {
                assertEquals(LeaderboardItemVO.class, row.getClass());
                assertEquals("member-" + row.getMemberId(), row.getNickname());
                assertEquals("avatar-" + row.getMemberId(), row.getAvatar());
                assertNull(row.getRank());
                assertNull(row.getRankPercentage());
            }
        }
    }

    @Test
    void topicLeaderboardRemainsSelfPlayOnlyAndLegacyMapperStaysDefault() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            route(session, 7, 710, 0, "current", THEME);
            progress(session, 7, 710, 0, "default", 1, 0, "09:00:00");
            progress(session, 7, 710, 0, "current", 1, 0, "10:00:00");
            progress(session, 7, 710, 0, "current", 1, 0, "10:01:00");
            route(session, 8, 710, 500, "other-activity", THEME);
            progress(session, 8, 710, 500, "other-activity", 1, 0, "10:00:00");
            PlayerNodeProgressServiceImpl service = service(session, true);
            List<LeaderboardItemVO> rows = service.selectActivityLeaderboard(null, 710L, 50);
            assertEquals(Collections.singletonList(7L), members(rows));
            assertEquals(Long.valueOf(2), rows.get(0).getScore());
            PlayerNodeProgressMapper mapper = session.getMapper(PlayerNodeProgressMapper.class);
            assertEquals(Long.valueOf(1), mapper.selectActivityLeaderboard(null, 710L, 50).get(0).getScore());
            PlayerNodeProgress probe = owner(7);
            assertEquals(1, mapper.selectPlayerNodeProgressList(probe).size());
            probe.setPlayInstanceId("current");
            assertTrue(mapper.selectPlayerNodeProgressList(probe).isEmpty());
        }
    }

    @Test
    void defaultOffAndBrokenFrozenSchemaFailClosedForBothStatisticsPaths() throws Exception {
        for (String snapshot : Arrays.asList(THEME, "{broken", "{\"themeConfig\":null}", "{} {}")) {
            try (SqlSession session = factory.openSession(true)) {
                clearAdded(session);
                route(session, 7, 710, 500, "current", snapshot);
                progress(session, 7, 710, 500, "current", 1, 0, "10:00:00");
                PlayerNodeProgressServiceImpl service = service(session, false);
                assertThrows(ServiceException.class, () -> service.selectPlayerNodeProgressList(owner(7)), snapshot);
                assertThrows(ServiceException.class, () -> service.selectActivityLeaderboard(500L, 710L, 50), snapshot);
            }
        }
    }

    @Test
    void emptyResolvedScopesAndMissingInstanceNeverBroadenSql() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            PlayerNodeProgressMapper mapper = session.getMapper(PlayerNodeProgressMapper.class);
            assertTrue(mapper.selectProgressScopes(new PlayerNodeProgress()).isEmpty());
            assertTrue(mapper.selectProgressForResolvedScopes(Collections.emptyList(), null).isEmpty());
            assertTrue(mapper.selectLeaderboardForResolvedScopes(0L, 300L, Collections.emptyList()).isEmpty());
            PlayerNodeProgress scope = owner(1); scope.setTopicId(300L); scope.setActivityId(0L);
            assertTrue(mapper.selectProgressForResolvedScopes(Collections.singletonList(scope), null).isEmpty());
            assertTrue(mapper.selectLeaderboardForResolvedScopes(0L, 300L, Collections.singletonList(scope)).isEmpty());
            ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
            when(runtime.playInstances(anyList())).thenReturn(Collections.emptyMap());
            PlayerNodeProgressServiceImpl service = service(session, runtime);
            assertThrows(ServiceException.class, () -> service.selectPlayerNodeProgressList(owner(1)));
            assertThrows(ServiceException.class, () -> service.selectActivityLeaderboard(null, 300L, 50));
        }
    }

    @Test
    void postReadBatchRejectsAnyChangedOrMissingToken() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            for (Map<String, String> after : Arrays.asList(Collections.<String, String>emptyMap(),
                    Collections.singletonMap(ITopicRouteRuntimeService.sessionKey(1L, 300L, 0L), "new"))) {
                ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
                Map<String, String> before = Collections.singletonMap(ITopicRouteRuntimeService.sessionKey(1L, 300L, 0L), "default");
                when(runtime.playInstances(anyList())).thenReturn(before, after);
                assertThrows(ServiceException.class, () -> service(session, runtime).selectPlayerNodeProgressList(owner(1)));
                reset(runtime);
                when(runtime.playInstances(anyList())).thenReturn(before, after);
                assertThrows(ServiceException.class, () -> service(session, runtime).selectActivityLeaderboard(null, 300L, 50));
            }
        }
    }

    @Test
    void activityOnlyServiceResolvesActualTopicOrRejectsMissingMetadata() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            route(session, 7, 710, 500, "current", THEME);
            progress(session, 7, 710, 500, "current", 1, 0, "10:00:00");
            PlayerNodeProgressServiceImpl service = service(session, true);
            CmsActivityMapper activities = mock(CmsActivityMapper.class);
            CmsActivity activity = new CmsActivity(); activity.setId(500L); activity.setTopicId(710L);
            when(activities.selectCmsActivityById(500L)).thenReturn(activity);
            ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
            assertEquals(Collections.singletonList(7L), members(service.selectActivityLeaderboard(500L, null, 50)));
            verify(activities).selectCmsActivityById(500L);
            assertThrows(ServiceException.class, () -> service.selectActivityLeaderboard(600L, null, 50));
            assertThrows(ServiceException.class, () -> service.selectActivityLeaderboard(null, null, 50));
            service.selectActivityLeaderboard(500L, 710L, 50);
            verifyNoMoreInteractionsExceptMissing(activities);
        }
    }

    @Test
    void boundedLeaderboardBatchesSortGloballyAndMatchLegacyNullTieBreak() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            for (int member = 1000; member < 1205; member++) {
                route(session, member, 710, 500, "default", "{}");
                progress(session, member, 710, 500, "default", 1, 0, "10:00:00");
            }
            progress(session, 1204, 710, 500, "default", 1, 0, "10:01:00");
            route(session, 2000, 710, 500, "default", "{}");
            progress(session, 2000, 710, 500, "default", 1, 0, null);
            PlayerNodeProgressMapper mapper = mock(PlayerNodeProgressMapper.class, delegatesTo(session.getMapper(PlayerNodeProgressMapper.class)));
            PlayerNodeProgressServiceImpl service = service(session, true);
            ReflectionTestUtils.setField(service, "playerNodeProgressMapper", mapper);
            assertEquals(Arrays.asList(1204L, 2000L, 1000L), members(service.selectActivityLeaderboard(500L, 710L, 3)));
            verify(mapper, times(3)).selectLeaderboardForResolvedScopes(eq(500L), eq(710L), argThat(scopes -> scopes.size() <= 100));
            verify(mapper, never()).selectPlayerNodeProgressList(any());
            verify(mapper, never()).selectActivityLeaderboard(any(), any(), anyInt());
        }
    }

    @Test
    void memberOnlyAndLeaderboardStillReadOrdinaryDefaultWithoutRouteSession() {
        try (SqlSession session = factory.openSession(true)) {
            assertEquals(Collections.singletonList(1L), ids(service(session, true).selectPlayerNodeProgressList(owner(1))));
            assertEquals(Collections.singletonList(1L), members(service(session, true).selectActivityLeaderboard(null, 300L, 50)));
        }
    }


    @Test
    void actualReplayBetweenProgressReadAndTokenCheckRejectsWholeResult() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            route(session, 7, 710, 500, "before", THEME);
            progress(session, 7, 710, 500, "before", 1, 0, "10:00:00");
            PlayerNodeProgressMapper real = session.getMapper(PlayerNodeProgressMapper.class);
            PlayerNodeProgressMapper mapper = mock(PlayerNodeProgressMapper.class, delegatesTo(real));
            PlayerNodeProgressServiceImpl service = service(session, true);
            ReflectionTestUtils.setField(service, "playerNodeProgressMapper", mapper);
            doAnswer(call -> {
                List<PlayerNodeProgress> rows = real.selectProgressForResolvedScopes(call.getArgument(0), call.getArgument(1));
                // A separate committed JDBC connection must not clear the reader's local MyBatis cache.
                try (SqlSession writer = factory.openSession(true)) { route(writer, 7, 710, 500, "after", THEME); }
                return rows;
            }).when(mapper).selectProgressForResolvedScopes(anyList(), any());
            assertThrows(ServiceException.class, () -> service.selectPlayerNodeProgressList(owner(7)));
        }
    }

    @Test
    void actualReplayBetweenLeaderboardReadAndTokenCheckRejectsWholeResult() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            route(session, 7, 710, 500, "before", THEME);
            progress(session, 7, 710, 500, "before", 1, 0, "10:00:00");
            PlayerNodeProgressMapper real = session.getMapper(PlayerNodeProgressMapper.class);
            PlayerNodeProgressMapper mapper = mock(PlayerNodeProgressMapper.class, delegatesTo(real));
            PlayerNodeProgressServiceImpl service = service(session, true);
            ReflectionTestUtils.setField(service, "playerNodeProgressMapper", mapper);
            doAnswer(call -> {
                List<PlayerNodeProgressMapper.LeaderboardScopeScore> rows = real.selectLeaderboardForResolvedScopes(
                        call.getArgument(0), call.getArgument(1), call.getArgument(2));
                try (SqlSession writer = factory.openSession(true)) { route(writer, 7, 710, 500, "after", THEME); }
                return rows;
            }).when(mapper).selectLeaderboardForResolvedScopes(anyLong(), anyLong(), anyList());
            assertThrows(ServiceException.class, () -> service.selectActivityLeaderboard(500L, 710L, 50));
        }
    }

    @Test
    void missingInstanceSchemaFailsInsteadOfRunningLegacyStatistics() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:leaderboard_missing_instance;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("PlayerNodeProgress", PlayerNodeProgress.class);
        String resource = "mapper/business/PlayerNodeProgressMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
             Statement st = session.getConnection().createStatement()) {
            createLeaderboardBaseTables(st);
            PlayerNodeProgress scope = owner(1); scope.setTopicId(300L); scope.setActivityId(0L); scope.setPlayInstanceId("default");
            RuntimeException error = assertThrows(RuntimeException.class, () -> session.getMapper(PlayerNodeProgressMapper.class)
                    .selectLeaderboardForResolvedScopes(0L, 300L, Collections.singletonList(scope)));
            assertTrue(error.getMessage().toLowerCase().contains("play_instance_id"));
        } finally { dataSource.forceCloseAll(); }
    }

    @Test
    void duplicateCandidateTuplesAreResolvedAndCountedOnlyOnce() {
        try (SqlSession session = factory.openSession(true)) {
            PlayerNodeProgress scope = owner(1); scope.setTopicId(300L); scope.setActivityId(0L);
            PlayerNodeProgressMapper mapper = mock(PlayerNodeProgressMapper.class,
                    delegatesTo(session.getMapper(PlayerNodeProgressMapper.class)));
            doReturn(Collections.nCopies(201, scope)).when(mapper).selectProgressScopes(any());
            PlayerNodeProgressServiceImpl service = service(session, true);
            ReflectionTestUtils.setField(service, "playerNodeProgressMapper", mapper);
            assertEquals(Collections.singletonList(1L), ids(service.selectPlayerNodeProgressList(owner(1))));
            assertEquals(Collections.singletonList(1L), members(service.selectActivityLeaderboard(null, 300L, 50)));
            verify(mapper).selectProgressForResolvedScopes(argThat(scopes -> scopes.size() == 1), isNull());
            verify(mapper).selectLeaderboardForResolvedScopes(eq(0L), eq(300L), argThat(scopes -> scopes.size() == 1));
        }
    }

    @Test
    void missingOrForeignCandidateScopeFailsBeforeRuntimeResolution() {
        try (SqlSession session = factory.openSession(true)) {
            PlayerNodeProgress foreign = owner(2); foreign.setTopicId(300L); foreign.setActivityId(0L);
            List<List<PlayerNodeProgress>> responses = Arrays.asList(null, Collections.singletonList(null), Collections.singletonList(foreign));
            for (List<PlayerNodeProgress> response : responses) {
                PlayerNodeProgressMapper mapper = mock(PlayerNodeProgressMapper.class);
                when(mapper.selectProgressScopes(any())).thenReturn(response);
                ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
                PlayerNodeProgressServiceImpl service = service(session, runtime);
                ReflectionTestUtils.setField(service, "playerNodeProgressMapper", mapper);
                assertThrows(ServiceException.class, () -> service.selectPlayerNodeProgressList(owner(1)));
                verifyNoInteractions(runtime);
                verify(mapper, never()).selectProgressForResolvedScopes(anyList(), any());
            }
        }
    }

    @Test
    void foreignOrStaleProgressRowsCannotEscapeBatchBoundary() {
        try (SqlSession session = factory.openSession(true)) {
            for (int mismatch = 0; mismatch < 5; mismatch++) {
                PlayerNodeProgress row = owner(mismatch == 0 ? 2 : 1);
                row.setTopicId(mismatch == 1 ? 301L : 300L);
                row.setActivityId(mismatch == 2 ? 500L : 0L);
                row.setPlayInstanceId(mismatch == 3 ? "stale" : "default");
                row.setDelFlag(mismatch == 4 ? 1 : 0);
                PlayerNodeProgressMapper mapper = mock(PlayerNodeProgressMapper.class,
                        delegatesTo(session.getMapper(PlayerNodeProgressMapper.class)));
                doReturn(Collections.singletonList(row)).when(mapper).selectProgressForResolvedScopes(anyList(), any());
                PlayerNodeProgressServiceImpl service = service(session, true);
                ReflectionTestUtils.setField(service, "playerNodeProgressMapper", mapper);
                assertThrows(ServiceException.class, () -> service.selectPlayerNodeProgressList(owner(1)));
            }
        }
    }

    @Test
    void foreignStaleOrDuplicateScoreRowsCannotEscapeLeaderboardBoundary() {
        try (SqlSession session = factory.openSession(true)) {
            for (int mismatch = 0; mismatch < 5; mismatch++) {
                PlayerNodeProgressMapper.LeaderboardScopeScore row = new PlayerNodeProgressMapper.LeaderboardScopeScore();
                row.setMemberId(mismatch == 0 ? 2L : 1L); row.setScore(1L);
                row.setTopicId(mismatch == 1 ? 301L : 300L);
                row.setActivityId(mismatch == 2 ? 500L : 0L);
                row.setPlayInstanceId(mismatch == 3 ? "stale" : "default");
                PlayerNodeProgressMapper mapper = mock(PlayerNodeProgressMapper.class,
                        delegatesTo(session.getMapper(PlayerNodeProgressMapper.class)));
                doReturn(mismatch == 4 ? Arrays.asList(row, row) : Collections.singletonList(row)).when(mapper)
                        .selectLeaderboardForResolvedScopes(anyLong(), anyLong(), anyList());
                PlayerNodeProgressServiceImpl service = service(session, true);
                ReflectionTestUtils.setField(service, "playerNodeProgressMapper", mapper);
                assertThrows(ServiceException.class, () -> service.selectActivityLeaderboard(null, 300L, 50));
            }
        }
    }

    @Test
    void memberOnlyReadChunksManyActivitiesWithoutLosingScopeOrProgress() throws Exception {
        try (SqlSession session = factory.openSession(true)) {
            for (int activity = 1000; activity < 1205; activity++) {
                route(session, 7, 710, activity, "current-" + activity, THEME);
                progress(session, 7, 710, activity, "current-" + activity, 1, 0, "10:00:00");
                progress(session, 7, 710, activity, "old", 1, 0, "09:00:00");
            }
            PlayerNodeProgressMapper mapper = mock(PlayerNodeProgressMapper.class,
                    delegatesTo(session.getMapper(PlayerNodeProgressMapper.class)));
            PlayerNodeProgressServiceImpl service = service(session, true);
            ReflectionTestUtils.setField(service, "playerNodeProgressMapper", mapper);
            List<PlayerNodeProgress> rows = service.selectPlayerNodeProgressList(owner(7));
            assertEquals(205, rows.size());
            assertEquals(205, rows.stream().map(PlayerNodeProgress::getActivityId).distinct().count());
            assertTrue(rows.stream().allMatch(row -> row.getPlayInstanceId().equals("current-" + row.getActivityId())));
            verify(mapper, times(3)).selectProgressForResolvedScopes(argThat(scopes -> scopes.size() <= 100), isNull());
        }
    }

    private void verifyNoMoreInteractionsExceptMissing(CmsActivityMapper mapper) {
        verify(mapper).selectCmsActivityById(600L);
        verifyNoMoreInteractions(mapper);
    }

    private PlayerNodeProgressServiceImpl service(SqlSession session, boolean enabled) {
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicById(anyLong())).thenAnswer(call -> {
            CmsTopic topic = new CmsTopic(); topic.setId(call.getArgument(0)); return topic;
        });
        TopicRouteRuntimeServiceImpl runtime = new TopicRouteRuntimeServiceImpl(
                session.getMapper(TopicRouteSessionMapper.class), topics, null, null, null, null);
        ThemeRuntimeFeaturePolicy policy = new ThemeRuntimeFeaturePolicy();
        ReflectionTestUtils.setField(policy, "enabled", enabled);
        ReflectionTestUtils.setField(runtime, "themeRuntimePolicy", policy);
        return service(session, runtime);
    }

    private PlayerNodeProgressServiceImpl service(SqlSession session, ITopicRouteRuntimeService runtime) {
        PlayerNodeProgressServiceImpl service = new PlayerNodeProgressServiceImpl();
        ReflectionTestUtils.setField(service, "playerNodeProgressMapper", session.getMapper(PlayerNodeProgressMapper.class));
        ReflectionTestUtils.setField(service, "routeRuntimeService", runtime);
        return service;
    }

    private PlayerNodeProgress owner(long member) {
        PlayerNodeProgress probe = new PlayerNodeProgress(); probe.setMemberId(member); return probe;
    }

    private List<Long> ids(List<PlayerNodeProgress> rows) {
        return rows.stream().map(PlayerNodeProgress::getId).collect(Collectors.toList());
    }

    private List<Long> members(List<LeaderboardItemVO> rows) {
        return rows.stream().map(LeaderboardItemVO::getMemberId).collect(Collectors.toList());
    }

    private void route(SqlSession session, long member, long topic, long activity, String instance, String snapshot) throws Exception {
        try (PreparedStatement st = session.getConnection().prepareStatement("insert into topic_route_session" +
                "(id,member_id,topic_id,activity_id,play_instance_id,config_snapshot_json,status) values(?,?,?,?,?,?,0)")) {
            st.setLong(1, nextId++); st.setLong(2, member); st.setLong(3, topic); st.setLong(4, activity);
            st.setString(5, instance); st.setString(6, snapshot); st.executeUpdate();
        }
        session.clearCache();
    }

    private long progress(SqlSession session, long member, long topic, long activity, String instance,
                          int status, int deleted, String time) throws Exception {
        long id = nextId++;
        try (PreparedStatement st = session.getConnection().prepareStatement("insert into player_node_progress" +
                "(id,member_id,topic_id,activity_id,play_instance_id,node_id,status,del_flag,checkin_time) values(?,?,?,?,?,?,?,?,?)")) {
            st.setLong(1, id); st.setLong(2, member); st.setLong(3, topic); st.setLong(4, activity);
            st.setString(5, instance); st.setLong(6, id); st.setInt(7, status); st.setInt(8, deleted);
            st.setTimestamp(9, time == null ? null : java.sql.Timestamp.valueOf("2026-08-21 " + time)); st.executeUpdate();
        }
        session.clearCache();
        return id;
    }

    private void member(SqlSession session, long member) throws Exception {
        try (PreparedStatement st = session.getConnection().prepareStatement("insert into ums_member values(?,?,?)")) {
            st.setLong(1, member); st.setString(2, "member-" + member); st.setString(3, "avatar-" + member); st.executeUpdate();
        }
    }

    private void clearAdded(SqlSession session) throws Exception {
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("delete from topic_route_session"); st.execute("delete from player_node_progress where id >= 100");
        }
        session.clearCache();
    }

}

package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.support.TopicRouteSessionScope;
import com.chengyinhub.common.exception.ServiceException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 真正 JUnit 单测；仅替换 mapper 边界，不声称运行数据库或 MyBatis。 */
class ThemeRuntimeSessionScopeTest {
    private final MapperReads reads = new MapperReads();

    @Test void explicitActivityUsesLatestFrozenThemeReplay() {
        reads.current = session(102L, 10L, "replay:2", true);
        reads.legacy = session(101L, 10L, "default", true);
        assertSame(reads.current, TopicRouteSessionScope.find(reads.mapper, 1L, 2L, 10L));
        assertEquals(Collections.singletonList("selectCurrentByOwner"), reads.calls);
    }

    @Test void selfPlayActivityZeroIsAnExplicitScope() {
        reads.current = session(102L, 0L, "replay:2", true);
        reads.expectedActivity = 0L;
        assertSame(reads.current, TopicRouteSessionScope.find(reads.mapper, 1L, 2L, 0L));
    }

    @Test void nonThemeLatestDoesNotReplaceLegacyDefaultContract() {
        reads.current = session(102L, 10L, "other", false);
        reads.legacy = session(101L, 10L, "default", false);
        assertSame(reads.legacy, TopicRouteSessionScope.find(reads.mapper, 1L, 2L, 10L));
        assertEquals(Arrays.asList("selectCurrentByOwner", "selectByOwner"), reads.calls);
    }

    @Test void frozenThemeDefaultIsStillThemeWithoutReplayPrefix() {
        reads.current = session(101L, 10L, "default", true);
        assertSame(reads.current, TopicRouteSessionScope.find(reads.mapper, 1L, 2L, 10L));
        assertFalse(reads.calls.contains("selectByOwner"));
    }

    @Test void explicitScopeWithoutSessionsReturnsNull() {
        assertNull(TopicRouteSessionScope.find(reads.mapper, 1L, 2L, 10L));
    }

    @Test void omittedActivityAllowsOnlyOneMemberTopicSession() {
        TopicRouteSession only = session(101L, 10L, "default", false);
        reads.matches = Collections.singletonList(only);
        assertSame(only, TopicRouteSessionScope.find(reads.mapper, 1L, 2L, null));
        assertEquals(Collections.singletonList("selectMemberTopicSessions"), reads.calls);
    }

    @Test void omittedActivityNeverSelectsLatestAcrossActivities() {
        reads.matches = Arrays.asList(session(101L, 10L, "default", true), session(102L, 20L, "default", true));
        assertThrows(IllegalArgumentException.class, () -> TopicRouteSessionScope.find(reads.mapper, 1L, 2L, null));
        assertEquals(Collections.singletonList("selectMemberTopicSessions"), reads.calls);
    }

    @Test void omittedActivityRejectsMultipleRunsWithinOneActivityToo() {
        reads.matches = Arrays.asList(session(101L, 10L, "default", true), session(102L, 10L, "replay:2", true));
        assertThrows(IllegalArgumentException.class, () -> TopicRouteSessionScope.find(reads.mapper, 1L, 2L, null));
        assertEquals(Collections.singletonList("selectMemberTopicSessions"), reads.calls);
    }

    @Test void omittedActivityWithoutSessionsReturnsNull() {
        assertNull(TopicRouteSessionScope.find(reads.mapper, 1L, 2L, null));
        reads.matches = null;
        assertNull(TopicRouteSessionScope.find(reads.mapper, 1L, 2L, null));
    }

    @Test void latestAndLegacyRowsMustMatchAllSuppliedScopeFields() {
        for (int mismatch = 0; mismatch < 3; mismatch++) {
            TopicRouteSession wrong = session(101L, 10L, "default", true);
            changeScope(wrong, mismatch);
            reads.current = wrong;
            assertThrows(ServiceException.class, () -> TopicRouteSessionScope.find(reads.mapper, 1L, 2L, 10L));
            reads.current = null;
            reads.legacy = wrong;
            assertThrows(ServiceException.class, () -> TopicRouteSessionScope.find(reads.mapper, 1L, 2L, 10L));
        }
    }

    @Test void singletonWithoutActivityStillValidatesMemberAndTopic() {
        for (int mismatch = 0; mismatch < 2; mismatch++) {
            TopicRouteSession wrong = session(101L, 10L, "default", false);
            changeScope(wrong, mismatch);
            reads.matches = Collections.singletonList(wrong);
            assertThrows(ServiceException.class, () -> TopicRouteSessionScope.find(reads.mapper, 1L, 2L, null));
        }
    }

    @Test void invalidScopeFailsBeforeAnyMapperRead() {
        for (Long value : new Long[] { null, 0L, -1L }) {
            assertThrows(IllegalArgumentException.class, () -> TopicRouteSessionScope.find(reads.mapper, value, 2L, 10L));
            assertThrows(IllegalArgumentException.class, () -> TopicRouteSessionScope.find(reads.mapper, 1L, value, 10L));
        }
        assertThrows(IllegalArgumentException.class, () -> TopicRouteSessionScope.find(reads.mapper, 1L, 2L, -1L));
        assertTrue(reads.calls.isEmpty());
    }

    @Test void corruptCurrentSnapshotCannotFallBackToLegacyDefault() {
        reads.current = session(102L, 10L, "replay:2", true);
        reads.current.setConfigSnapshotJson("{broken");
        reads.legacy = session(101L, 10L, "default", false);
        assertThrows(ServiceException.class, () -> TopicRouteSessionScope.find(reads.mapper, 1L, 2L, 10L));
        assertEquals(Collections.singletonList("selectCurrentByOwner"), reads.calls);
    }

    @Test void explicitHistoricalSessionRemainsReadableAfterReplay() {
        reads.history = session(101L, 10L, "default", true);
        reads.current = session(102L, 10L, "replay:2", true);
        assertSame(reads.history, TopicRouteSessionScope.findHistorical(reads.mapper, 1L, 2L, 10L, 101L));
        assertEquals(Collections.singletonList("selectAccessibleById"), reads.calls);
    }

    @Test void historicalSessionAllowsOmittedActivityButNotMismatchedExplicitActivity() {
        reads.history = session(101L, 20L, "default", true);
        assertSame(reads.history, TopicRouteSessionScope.findHistorical(reads.mapper, 1L, 2L, null, 101L));
        assertThrows(ServiceException.class, () -> TopicRouteSessionScope.findHistorical(reads.mapper, 1L, 2L, 10L, 101L));
        reads.history.setActivityId(0L);
        assertThrows(ServiceException.class, () -> TopicRouteSessionScope.findHistorical(reads.mapper, 1L, 2L, 10L, 101L));
    }

    @Test void historicalSessionChecksMemberTopicActivityAndSessionId() {
        for (int mismatch = 0; mismatch < 4; mismatch++) {
            reads.history = session(101L, 10L, "default", true);
            if (mismatch < 3) changeScope(reads.history, mismatch);
            else reads.history.setId(999L);
            assertThrows(ServiceException.class, () -> TopicRouteSessionScope.findHistorical(reads.mapper, 1L, 2L, 10L, 101L));
        }
    }

    @Test void inaccessibleHistoryReturnsNullAndInvalidIdDoesNotQuery() {
        assertNull(TopicRouteSessionScope.findHistorical(reads.mapper, 1L, 2L, 10L, 101L));
        reads.calls.clear();
        for (Long id : new Long[] { null, 0L, -1L }) {
            assertThrows(IllegalArgumentException.class, () -> TopicRouteSessionScope.findHistorical(reads.mapper, 1L, 2L, 10L, id));
        }
        assertTrue(reads.calls.isEmpty());
    }

    @Test void stillRunningHistoricalWriteIsRejectedAfterReplay() {
        TopicRouteSession old = session(101L, 10L, "default", true);
        reads.current = session(102L, 10L, "replay:2", true);
        ServiceException ex = assertThrows(ServiceException.class, () -> TopicRouteSessionScope.requireCurrent(reads.mapper, old));
        assertEquals(Integer.valueOf(409), ex.getCode());
        assertEquals(Integer.valueOf(TopicRouteSession.STATUS_RUNNING), old.getStatus());
    }

    @Test void currentCompletedSessionCanReachReceiptReplayBeforeStatusCheck() {
        reads.current = session(102L, 10L, "replay:2", true);
        reads.current.setStatus(TopicRouteSession.STATUS_COMPLETED);
        assertDoesNotThrow(() -> TopicRouteSessionScope.requireCurrent(reads.mapper, reads.current));
    }

    @Test void legacyDefaultWriteKeepsExistingSessionSelection() {
        reads.current = session(102L, 10L, "other", false);
        reads.legacy = session(101L, 10L, "default", false);
        assertDoesNotThrow(() -> TopicRouteSessionScope.requireCurrent(reads.mapper, reads.legacy));
        assertThrows(ServiceException.class, () -> TopicRouteSessionScope.requireCurrent(reads.mapper, reads.current));
    }

    @Test void missingCurrentSessionCannotBeWritten() {
        assertThrows(ServiceException.class, () -> TopicRouteSessionScope.requireCurrent(reads.mapper,
                session(101L, 10L, "default", true)));
    }

    @Test void currentWriteRequiresPersistedIdAndExplicitActivity() {
        assertThrows(IllegalArgumentException.class, () -> TopicRouteSessionScope.requireCurrent(reads.mapper, null));
        TopicRouteSession missingId = session(null, 10L, "default", true);
        TopicRouteSession missingActivity = session(101L, null, "default", true);
        assertThrows(IllegalArgumentException.class, () -> TopicRouteSessionScope.requireCurrent(reads.mapper, missingId));
        assertThrows(IllegalArgumentException.class, () -> TopicRouteSessionScope.requireCurrent(reads.mapper, missingActivity));
        assertTrue(reads.calls.isEmpty());
    }

    @Test void actualMapperCurrentQueryKeepsAllThreeOwnerBindingsAndLatestOrdering() throws Exception {
        BoundSql sql = mappedSql("selectCurrentByOwner");
        assertTrue(normalize(sql).contains("where activity_id=? and topic_id=? and member_id=? order by id desc limit 1"));
        assertEquals(Arrays.asList("activityId", "topicId", "memberId"), parameterNames(sql));
    }

    @Test void actualMapperUniquenessQueryIncludesReplaysButStaysBounded() throws Exception {
        BoundSql sql = mappedSql("selectMemberTopicSessions");
        String where = normalize(sql).substring(normalize(sql).indexOf("where "));
        assertEquals("where member_id = ? and topic_id = ? order by id limit 2", where);
        assertEquals(Arrays.asList("memberId", "topicId"), parameterNames(sql));
    }

    @Test void actualMapperLegacyDefaultAndHistoricalOwnerBindingsStayIntact() throws Exception {
        BoundSql legacy = mappedSql("selectByOwner");
        assertTrue(normalize(legacy).contains("where activity_id=? and topic_id=? and member_id=? and play_instance_id=? limit 1"));
        assertEquals(Arrays.asList("activityId", "topicId", "memberId", "playInstanceId"), parameterNames(legacy));
        BoundSql history = mappedSql("selectAccessibleById");
        assertTrue(normalize(history).contains("where id=? and member_id=? limit 1"));
        assertEquals(Arrays.asList("id", "memberId"), parameterNames(history));
    }

    @Test void batchCandidateResolverKeepsThemeLatestAndLegacyDefaultIndependentOfOrder() {
        TopicRouteSession legacy=session(1L,10L,"default",false);
        TopicRouteSession other=session(3L,10L,"other",false);
        assertSame(legacy,TopicRouteSessionScope.resolveCurrentCandidates(Arrays.asList(other,legacy,legacy),1L,2L,10L));
        TopicRouteSession replay=session(4L,10L,"replay:1",true);
        assertSame(replay,TopicRouteSessionScope.resolveCurrentCandidates(Arrays.asList(replay,legacy),1L,2L,10L));
        assertSame(replay,TopicRouteSessionScope.resolveCurrentCandidates(Arrays.asList(legacy,replay),1L,2L,10L));
    }

    @Test void batchCandidateResolverNeverInventsDefaultForNonThemeOrphan() {
        assertNull(TopicRouteSessionScope.resolveCurrentCandidates(Collections.singletonList(session(3L,10L,"other",false)),1L,2L,10L));
        assertNull(TopicRouteSessionScope.resolveCurrentCandidates(Collections.emptyList(),1L,2L,10L));
    }

    @Test void batchCandidateResolverRejectsCorruptionCrossOwnerAndDuplicateDefault() {
        TopicRouteSession corrupt=session(5L,10L,"replay:1",true);corrupt.setConfigSnapshotJson("{broken");
        assertThrows(ServiceException.class,()->TopicRouteSessionScope.resolveCurrentCandidates(Arrays.asList(session(1L,10L,"default",false),corrupt),1L,2L,10L));
        assertThrows(ServiceException.class,()->TopicRouteSessionScope.resolveCurrentCandidates(Collections.singletonList(session(5L,20L,"default",false)),1L,2L,10L));
        assertThrows(ServiceException.class,()->TopicRouteSessionScope.resolveCurrentCandidates(Arrays.asList(session(1L,10L,"default",false),session(2L,10L,"default",false)),1L,2L,10L));
        assertThrows(IllegalArgumentException.class,()->TopicRouteSessionScope.resolveCurrentCandidates(Collections.emptyList(),1L,2L,null));
    }

    @Test void batchRuntimeSeparatesMemberTopicActivityAndChecksExpectedToken() {
        TopicRouteSession first=session(1L,10L,"default",true);first.setStatus(1);
        TopicRouteSession replay=session(2L,10L,"replay:1",true);replay.setStatus(0);
        TopicRouteSession other=session(3L,20L,"default",false);other.setStatus(1);
        TopicRouteSessionMapper mapper=org.mockito.Mockito.mock(TopicRouteSessionMapper.class);
        org.mockito.Mockito.when(mapper.selectCurrentCandidatesByOwners(org.mockito.ArgumentMatchers.anyList())).thenReturn(Arrays.asList(other,replay,first));
        TopicRouteRuntimeServiceImpl runtime=runtime(mapper,true);
        TopicRouteSession a=session(100L,10L,"replay:1",false),b=session(101L,20L,"default",false);
        Map<String,Integer> statuses=runtime.sessionStatuses(Arrays.asList(a,b));
        assertEquals(Integer.valueOf(0),statuses.get("1:2:10"));assertEquals(Integer.valueOf(1),statuses.get("1:2:20"));
        assertEquals("replay:1",runtime.playInstances(Arrays.asList(a,b)).get("1:2:10"));
        a.setPlayInstanceId("default");assertThrows(ServiceException.class,()->runtime.sessionStatuses(Arrays.asList(a,b)));
    }

    @Test void batchRuntimeRejectsUnexpectedOwnersAndDefaultOffTheme() {
        TopicRouteSessionMapper mapper=org.mockito.Mockito.mock(TopicRouteSessionMapper.class);
        TopicRouteSession requested=session(1L,10L,null,false);
        org.mockito.Mockito.when(mapper.selectCurrentCandidatesByOwners(org.mockito.ArgumentMatchers.anyList())).thenReturn(Collections.singletonList(session(2L,20L,"default",false)));
        assertThrows(ServiceException.class,()->runtime(mapper,true).sessionStatuses(Collections.singletonList(requested)));
        org.mockito.Mockito.when(mapper.selectCurrentCandidatesByOwners(org.mockito.ArgumentMatchers.anyList())).thenReturn(Collections.singletonList(session(2L,10L,"replay:1",true)));
        assertThrows(ServiceException.class,()->runtime(mapper,false).sessionStatuses(Collections.singletonList(requested)));
    }

    @Test void batchRuntimeBoundsQueriesAndDeduplicatesExactScopes() {
        TopicRouteSessionMapper mapper=org.mockito.Mockito.mock(TopicRouteSessionMapper.class);
        List<Integer> sizes=new ArrayList<>();
        org.mockito.Mockito.when(mapper.selectCurrentCandidatesByOwners(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            List<TopicRouteSession> scopes=call.getArgument(0);sizes.add(scopes.size());
            List<TopicRouteSession> rows=new ArrayList<>();
            for(TopicRouteSession scope:scopes)rows.add(session(scope.getActivityId()+1000L,scope.getActivityId(),"default",false));
            return rows;
        });
        List<TopicRouteSession> requested=new ArrayList<>();for(long i=0;i<201;i++)requested.add(session(i+1,i,null,false));
        requested.add(requested.get(0));
        assertEquals(201,runtime(mapper,true).sessionStatuses(requested).size());
        assertEquals(Arrays.asList(100,100,1),sizes);
    }

    @Test void batchRuntimeEmptyScopesNeverQueriesAndInvalidScopesFailBeforeQuery() {
        TopicRouteSessionMapper mapper=org.mockito.Mockito.mock(TopicRouteSessionMapper.class);
        TopicRouteRuntimeServiceImpl runtime=runtime(mapper,true);
        assertTrue(runtime.sessionStatuses(null).isEmpty());assertTrue(runtime.playInstances(Collections.emptyList()).isEmpty());
        assertThrows(IllegalArgumentException.class,()->runtime.sessionStatuses(Collections.singletonList(session(1L,null,null,false))));
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @Test void batchMissingSessionsCheckPublishedThemeOncePerTopicAndNeverCreate() {
        TopicRouteSessionMapper mapper=org.mockito.Mockito.mock(TopicRouteSessionMapper.class);
        com.chengyinhub.business.mapper.CmsTopicMapper topics=org.mockito.Mockito.mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        TopicRouteRuntimeServiceImpl runtime=runtime(mapper,true);
        org.springframework.test.util.ReflectionTestUtils.setField(runtime,"topicMapper",topics);
        List<TopicRouteSession> scopes=new ArrayList<>();for(long i=0;i<201;i++)scopes.add(session(i+1,i,null,false));
        assertEquals(201,runtime.playInstances(scopes).size());
        org.mockito.Mockito.verify(topics,org.mockito.Mockito.times(1)).selectCmsTopicById(2L);
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.never()).insertSession(org.mockito.ArgumentMatchers.any());
        com.chengyinhub.business.domain.CmsTopic configured=new com.chengyinhub.business.domain.CmsTopic();configured.setPublishedThemeRuntimeConfigJson("{\"schemaVersion\":1}");
        org.mockito.Mockito.when(topics.selectCmsTopicById(2L)).thenReturn(configured);
        assertThrows(ServiceException.class,()->runtime.playInstances(scopes));
        assertThrows(ServiceException.class,()->runtime.currentSessionFacts(scopes));
    }

    @Test void batchConflictingTokensFailBeforeAnyRead() {
        TopicRouteSessionMapper mapper=org.mockito.Mockito.mock(TopicRouteSessionMapper.class);
        assertThrows(ServiceException.class,()->runtime(mapper,true).sessionStatuses(Arrays.asList(session(1L,10L,"default",false),session(2L,10L,"replay:1",false))));
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @Test void completionFactsUseFrozenThemeIdentityWithExactInstanceAndStatus() {
        TopicRouteSessionMapper mapper=org.mockito.Mockito.mock(TopicRouteSessionMapper.class);
        TopicRouteSession selected=session(2L,10L,"replay:1",true);selected.setStatus(TopicRouteSession.STATUS_ENDED);
        org.mockito.Mockito.when(mapper.selectCurrentCandidatesByOwners(org.mockito.ArgumentMatchers.anyList())).thenReturn(Collections.singletonList(selected));
        com.chengyinhub.business.service.ITopicRouteRuntimeService.CurrentSessionFact fact=runtime(mapper,true).currentSessionFacts(Collections.singletonList(session(1L,10L,null,false))).get("1:2:10");
        assertTrue(fact.isTheme());assertEquals("replay:1",fact.getPlayInstanceId());assertEquals(Integer.valueOf(TopicRouteSession.STATUS_ENDED),fact.getStatus());
        selected.setPlayInstanceId("default");selected.setConfigSnapshotJson("{}");
        fact=runtime(mapper,true).currentSessionFacts(Collections.singletonList(session(1L,10L,null,false))).get("1:2:10");
        assertFalse(fact.isTheme());assertEquals("default",fact.getPlayInstanceId());
    }

    private static TopicRouteRuntimeServiceImpl runtime(TopicRouteSessionMapper mapper,boolean enabled) {
        TopicRouteRuntimeServiceImpl runtime=new TopicRouteRuntimeServiceImpl(mapper,org.mockito.Mockito.mock(com.chengyinhub.business.mapper.CmsTopicMapper.class),null,null,null,null);
        com.chengyinhub.business.service.support.ThemeRuntimeFeaturePolicy policy=new com.chengyinhub.business.service.support.ThemeRuntimeFeaturePolicy();
        org.springframework.test.util.ReflectionTestUtils.setField(policy,"enabled",enabled);
        org.springframework.test.util.ReflectionTestUtils.setField(runtime,"themeRuntimePolicy",policy);
        return runtime;
    }

    /** 解析实际 MyBatis XML 并生成绑定 SQL，不连接或执行数据库。 */
    private static BoundSql mappedSql(String statement) throws Exception {
        String resource = "mapper/business/TopicRouteSessionMapper.xml";
        Configuration configuration = new Configuration();
        try (InputStream input = ThemeRuntimeSessionScopeTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "Missing actual mapper resource");
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("activityId", 10L);
        parameters.put("topicId", 2L);
        parameters.put("memberId", 1L);
        parameters.put("playInstanceId", "default");
        parameters.put("id", 101L);
        return configuration.getMappedStatement(TopicRouteSessionMapper.class.getName() + "." + statement)
                .getBoundSql(parameters);
    }

    private static String normalize(BoundSql sql) {
        return sql.getSql().replaceAll("\\s+", " ").trim();
    }

    private static List<String> parameterNames(BoundSql sql) {
        List<String> names = new ArrayList<>();
        sql.getParameterMappings().forEach(parameter -> names.add(parameter.getProperty()));
        return names;
    }

    private static void changeScope(TopicRouteSession session, int field) {
        if (field == 0) session.setMemberId(99L);
        if (field == 1) session.setTopicId(99L);
        if (field == 2) session.setActivityId(99L);
    }

    private static TopicRouteSession session(Long id, Long activity, String instance, boolean theme) {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(id);
        session.setMemberId(1L);
        session.setTopicId(2L);
        session.setActivityId(activity);
        session.setPlayInstanceId(instance);
        session.setStatus(TopicRouteSession.STATUS_RUNNING);
        session.setConfigSnapshotJson(theme ? "{\"themeConfig\":{\"schemaVersion\":1}}" : "{\"journeyRules\":{}}");
        return session;
    }

    /** 显式记录只读调用并断言 SQL 绑定参数；任何写调用都会使单测失败。 */
    private static final class MapperReads {
        private TopicRouteSession current;
        private TopicRouteSession legacy;
        private TopicRouteSession history;
        private Long expectedActivity = 10L;
        private List<TopicRouteSession> matches = Collections.emptyList();
        private final List<String> calls = new ArrayList<>();
        private final TopicRouteSessionMapper mapper = (TopicRouteSessionMapper) Proxy.newProxyInstance(
                TopicRouteSessionMapper.class.getClassLoader(), new Class<?>[] { TopicRouteSessionMapper.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    calls.add(name);
                    if ("selectCurrentByOwner".equals(name) || "selectByOwner".equals(name)) {
                        assertEquals(expectedActivity, args[0]);
                        assertEquals(2L, args[1]);
                        assertEquals(1L, args[2]);
                        if ("selectByOwner".equals(name)) {
                            assertEquals("default", args[3]);
                            return legacy;
                        }
                        return current;
                    }
                    if ("selectMemberTopicSessions".equals(name)) {
                        assertArrayEquals(new Object[] { 1L, 2L }, args);
                        return matches;
                    }
                    if ("selectAccessibleById".equals(name)) {
                        assertArrayEquals(new Object[] { 101L, 1L }, args);
                        return history;
                    }
                    throw new AssertionError("Unexpected mapper call: " + name);
                });
    }
}

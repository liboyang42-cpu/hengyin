package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CommunityReportCase;
import com.chengyinhub.business.domain.vo.CommunityReportVO;
import com.chengyinhub.business.domain.community.CommunityPostFeedQuery;
import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.domain.dto.CommunityReportSubmitRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.CommunityTrustService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICommunityMediaReceiptService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityPostServiceImplTest {
    private CommunityPostMapper mapper;
    private IContentSafetyGate safetyGate;
    private CommunityPostServiceImpl service;
    private ICommunityMediaReceiptService mediaReceiptService;
    private CommunityTrustService communityTrustService;
    private IAuditTaskService auditTaskService;

    @BeforeEach
    void setUp() {
        mapper = mock(CommunityPostMapper.class);
        safetyGate = mock(IContentSafetyGate.class);
        mediaReceiptService = mock(ICommunityMediaReceiptService.class);
        communityTrustService = mock(CommunityTrustService.class);
        auditTaskService = mock(IAuditTaskService.class);
        service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(), safetyGate,
                auditTaskService, communityTrustService, mediaReceiptService);
        when(mapper.countActiveMemberEnforcement(anyLong(), anyList(), any())).thenReturn(0);
        when(mapper.countRecentPosts(anyLong(), any())).thenReturn(0);
        when(mediaReceiptService.verify(anyLong(), anyString(), anyLong(), any())).thenReturn(true);
        when(mapper.selectCommandReceipt(anyLong(), anyString())).thenReturn(null);
        when(mapper.insertCommandReceipt(anyLong(), anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(1);
        when(mapper.insertOutbox(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTypedOutbox(anyString(), anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.countGuidelineVersion(anyLong())).thenReturn(1);
        when(mapper.countGuidelineRule(anyLong(), anyString())).thenReturn(1);
        when(mapper.countNotificationDeliverable(anyLong(), anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.countMemberNotificationDeliverable(anyLong(), anyLong())).thenReturn(1);
    }

    @Test
    void bodyEditRoundTripPreservesAuthorTopicRelations() {
        CommunityPost post = draft(7L, 9L, 3);
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        java.util.ArrayList<String> persistedTopics = new java.util.ArrayList<>(Arrays.asList("NIGHT", "WALK"));
        when(mapper.selectTopicCodes(7L)).thenAnswer(invocation -> new java.util.ArrayList<>(persistedTopics));
        when(mapper.deleteTopics(7L)).thenAnswer(invocation -> { persistedTopics.clear(); return 2; });
        when(mapper.insertTopic(eq(7L), anyString(), any())).thenAnswer(invocation -> {
            persistedTopics.add(invocation.getArgument(1)); return 1;
        });
        when(mapper.updateContentCas(any(), eq(3), eq("DRAFT"), any())).thenReturn(1);
        Map<String, Object> before = service.detail(9L, 7L);
        assertEquals(Arrays.asList("NIGHT", "WALK"), before.get("topicCodes"));
        CommunityPostRequests.Upsert request = validUpsert();
        request.setExpectedVersion(3);
        request.setBody("只改正文");
        request.setTopicCodes(((List<?>) before.get("topicCodes")).stream().map(Object::toString).toList());
        Map<String, Object> after = service.edit(9L, 7L, request);
        assertEquals(Arrays.asList("NIGHT", "WALK"), persistedTopics);
        assertEquals(before.get("topicCodes"), after.get("topicCodes"));
        verify(mapper).deleteTopics(7L);
        verify(mapper).insertTopic(eq(7L), eq("NIGHT"), any());
        verify(mapper).insertTopic(eq(7L), eq("WALK"), any());
    }

    @Test
    void personalListsUseLookaheadAndPreserveRealPostVersion() {
        CommunityPost first = draft(30L, 9L, 7);
        CommunityPost second = draft(20L, 9L, 4);
        when(mapper.selectAuthorPosts(9L, null, 2)).thenReturn(Arrays.asList(first, second));
        Map<String, Object> page = service.myPosts(9L, null, 1);
        assertEquals(true, page.get("hasMore"));
        assertEquals(30L, page.get("nextCursor"));
        List<?> items = (List<?>) page.get("items");
        assertEquals(1, items.size());
        CommunityPost actual = (CommunityPost) ((Map<?, ?>) items.get(0)).get("post");
        assertEquals(Integer.valueOf(7), actual.getVersion());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) items.get(0)).get("viewerIsAuthor"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) items.get(0)).get("viewerFollowing"));
        when(mapper.selectAuthorPosts(9L, 30L, 2)).thenReturn(Collections.singletonList(second));
        Map<String, Object> last = service.myPosts(9L, 30L, 1);
        assertEquals(false, last.get("hasMore"));
        assertEquals(20L, last.get("nextCursor"));
        when(mapper.selectAuthorPosts(9L, 20L, 2)).thenReturn(Collections.emptyList());
        Map<String, Object> empty = service.myPosts(9L, 20L, 1);
        assertEquals(false, empty.get("hasMore"));
        assertEquals(null, empty.get("nextCursor"));
        assertEquals(Collections.emptyList(), empty.get("items"));
    }

    @Test
    void bookmarkPageUsesCurrentMemberAndRejectsAnonymousBeforeQuery() {
        when(mapper.selectBookmarkedPosts(9L, null, 2))
                .thenReturn(Arrays.asList(draft(40L, 8L, 2), draft(30L, 8L, 1)));
        Map<String, Object> page = service.myBookmarks(9L, null, 1);
        assertEquals(true, page.get("hasMore"));
        assertEquals(40L, page.get("nextCursor"));
        assertEquals(1, ((List<?>) page.get("items")).size());
        when(mapper.selectBookmarkedPosts(9L, 40L, 2)).thenReturn(Collections.singletonList(draft(30L, 8L, 1)));
        assertEquals(false, service.myBookmarks(9L, 40L, 1).get("hasMore"));
        when(mapper.selectBookmarkedPosts(9L, 30L, 2)).thenReturn(Collections.emptyList());
        Map<String, Object> empty = service.myBookmarks(9L, 30L, 1);
        assertEquals(null, empty.get("nextCursor"));
        assertEquals(false, empty.get("hasMore"));
        assertThrows(ServiceException.class, () -> service.myPosts(0L, null, 1));
        assertThrows(ServiceException.class, () -> service.myBookmarks(0L, null, 1));
        verify(mapper, never()).selectAuthorPosts(eq(0L), any(), anyInt());
        verify(mapper, never()).selectBookmarkedPosts(eq(0L), any(), anyInt());
    }

    @Test
    void commentLookaheadDropsOnlyExtraRootWithItsWholeThread() {
        when(mapper.selectById(7L, 9L)).thenReturn(draft(7L, 9L, 3));
        Map<String, Object> root30 = threadRow(30L, null);
        Map<String, Object> reply31 = threadRow(31L, 30L);
        Map<String, Object> reply32 = threadRow(32L, 31L);
        Map<String, Object> root20 = threadRow(20L, null);
        Map<String, Object> reply21 = threadRow(21L, 20L);
        when(mapper.selectComments(7L, 9L, null, 2))
                .thenReturn(Arrays.asList(root30, reply31, reply32, root20, reply21));
        Map<String, Object> first = service.comments(9L, 7L, null, 1);
        assertEquals(Arrays.asList(root30, reply31, reply32), first.get("items"));
        assertEquals(true, first.get("hasMore"));
        assertEquals(30L, first.get("nextCursor"));
        when(mapper.selectComments(7L, 9L, 30L, 2)).thenReturn(Arrays.asList(root20, reply21));
        Map<String, Object> last = service.comments(9L, 7L, 30L, 1);
        assertEquals(Arrays.asList(root20, reply21), last.get("items"));
        assertEquals(false, last.get("hasMore"));
        assertEquals(20L, last.get("nextCursor"));
        when(mapper.selectComments(7L, 9L, 20L, 2)).thenReturn(Collections.emptyList());
        Map<String, Object> empty = service.comments(9L, 7L, 20L, 1);
        assertEquals(Collections.emptyList(), empty.get("items"));
        assertEquals(false, empty.get("hasMore"));
        assertEquals(null, empty.get("nextCursor"));
    }

    private Map<String, Object> threadRow(Long id, Long parentId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("parent_id", parentId);
        return row;
    }

    @Test
    void checkingEditSavesNewVersionAndSubmitsBoundSnapshotWithoutPublishing() {
        CommunityPost checking = draft(7L, 9L, 3); checking.setLifecycle("CHECKING");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(checking);
        when(mapper.selectById(7L, 9L)).thenReturn(checking);
        when(mapper.selectActiveGuideline()).thenReturn(Collections.singletonMap("id", 5L));
        when(mapper.countGuidelineAck(9L, 5L)).thenReturn(1);
        when(mapper.updateContentCas(any(), eq(3), eq("CHECKING"), any())).thenReturn(1);
        CommunityPostRequests.Upsert request = validUpsert(); request.setExpectedVersion(3);
        service.edit(9L, 7L, request);
        org.mockito.ArgumentCaptor<CommunityPost> content = org.mockito.ArgumentCaptor.forClass(CommunityPost.class);
        verify(mapper).updateContentCas(content.capture(), eq(3), eq("CHECKING"), any());
        assertEquals(4, content.getValue().getVersion());
        assertEquals("CHECKING", content.getValue().getLifecycle());
        verify(auditTaskService).submitSafetyHold(eq(AuditTask.BIZ_COMMUNITY_POST), eq(7L), eq(9L), eq("EDIT_REVIEW"),
                argThat(snapshot -> com.chengyinhub.business.service.support.CommunityPostAuditSnapshot
                        .reviewedVersion(snapshot, 7L, 9L) == 4));
        verify(mapper).upsertSearchDocument(eq(7L), eq("HIDDEN"), any());
        verify(safetyGate, never()).decide(any());
    }

    @Test
    void publishHoldSnapshotNamesPostVersionAfterPublishCas() {
        CommunityPost post = draft(7L, 9L, 3);
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        when(mapper.selectActiveGuideline()).thenReturn(Collections.singletonMap("id", 5L));
        when(mapper.countGuidelineAck(9L, 5L)).thenReturn(1);
        when(safetyGate.decide(any())).thenReturn(SafetyDecision.hold("REVIEW", 19L));
        when(mapper.transitionCas(eq(7L), eq("DRAFT"), eq("CHECKING"), eq(3), any(), any(), any(), any(), any(), any())).thenReturn(1);
        service.publish(9L, null, 7L, state(3));
        verify(safetyGate).decide(argThat(request ->
                com.chengyinhub.business.service.support.CommunityPostAuditSnapshot.reviewedVersion(request.getSnapshot(), 7L, 9L) == 4));
        assertEquals(3, post.getVersion(), "capturing review evidence must not mutate the locked DRAFT object");
        verify(mapper).upsertSearchDocument(eq(7L), eq("HIDDEN"), any());
    }

    @Test
    void staleAuditCallbackCannotApproveCurrentNewContent() {
        CommunityPost post = draft(7L, 9L, 4); post.setLifecycle("CHECKING");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        assertEquals(409, assertThrows(ServiceException.class, () -> service.applyAuditDecision(7L, true, 3)).getCode());
        assertEquals(409, assertThrows(ServiceException.class, () -> service.applyAuditDecision(7L, false, null)).getCode());
        verify(mapper, never()).transitionCas(anyLong(), anyString(), anyString(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void checkingEditAndAuditRefreshRollBackTogetherThroughRealSpringProxies() {
        org.springframework.jdbc.datasource.DriverManagerDataSource ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:community_edit_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.execute("create table post_state(id bigint primary key,body varchar(100),version int)");
        jdbc.execute("create table task_state(id bigint primary key,snapshot_json clob,version int)");
        jdbc.execute("create table revision_state(id int primary key)");
        jdbc.update("insert into post_state values(7,'old',3)");
        jdbc.update("insert into task_state values(19,'legacy',2)");
        com.chengyinhub.business.mapper.AuditTaskMapper taskMapper = mock(com.chengyinhub.business.mapper.AuditTaskMapper.class);
        com.chengyinhub.business.mapper.AuditRecordMapper records = mock(com.chengyinhub.business.mapper.AuditRecordMapper.class);
        com.chengyinhub.business.service.impl.AuditTaskServiceImpl audit = new com.chengyinhub.business.service.impl.AuditTaskServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(audit, "auditTaskMapper", taskMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(audit, "auditRecordMapper", records);
        org.springframework.test.util.ReflectionTestUtils.setField(audit, "communityPostMapper", mapper);
        org.springframework.jdbc.datasource.DataSourceTransactionManager tx = new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
        org.springframework.aop.framework.ProxyFactory auditProxy = new org.springframework.aop.framework.ProxyFactory(audit);
        auditProxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(tx,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        CommunityPostServiceImpl target = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(), safetyGate,
                (IAuditTaskService) auditProxy.getProxy(), communityTrustService, mediaReceiptService);
        org.springframework.aop.framework.ProxyFactory postProxy = new org.springframework.aop.framework.ProxyFactory(target);
        postProxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(tx,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        com.chengyinhub.business.service.ICommunityPostService transactional =
                (com.chengyinhub.business.service.ICommunityPostService) postProxy.getProxy();
        when(mapper.selectActiveGuideline()).thenReturn(Collections.singletonMap("id", 5L));
        when(mapper.countGuidelineAck(9L, 5L)).thenReturn(1);
        CommunityPostRequests.Upsert request = validUpsert(); request.setExpectedVersion(3); request.setBody("new");
        AtomicReference<CommunityPost> updated = new AtomicReference<>();
        when(mapper.selectByIdForUpdate(7L)).thenAnswer(inv -> {
            if (jdbc.queryForObject("select version from post_state where id=7", Integer.class) == 4) return updated.get();
            CommunityPost current = draft(7L, 9L, 3); current.setLifecycle("CHECKING"); current.setBody("old"); return current;
        });
        when(mapper.insertRevision(anyLong(), anyLong(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> jdbc.update("insert into revision_state values(1)"));
        when(mapper.updateContentCas(any(), eq(3), eq("CHECKING"), any())).thenAnswer(inv -> {
            CommunityPost next = inv.getArgument(0); updated.set(next);
            return jdbc.update("update post_state set body=?,version=4 where id=7 and version=3", next.getBody());
        });
        AuditTask open = new AuditTask(); open.setId(19L); open.setBizType(AuditTask.BIZ_COMMUNITY_POST);
        open.setBizId(7L); open.setSubmitterId(9L); open.setVersion(2); open.setStatus(0);
        when(taskMapper.selectOpenAuditTaskForUpdate(AuditTask.BIZ_COMMUNITY_POST, 7L)).thenReturn(open);
        for (String failure : new String[] {"other-source", "snapshot-cas", "record"}) {
            open.setSourceType("other-source".equals(failure) ? "report" : "auto_gate");
            when(taskMapper.refreshCommunitySafetySnapshotCas(eq(19L), eq(2), eq(9L), anyString(), anyString(), any()))
                    .thenAnswer(inv -> "snapshot-cas".equals(failure) ? 0 : jdbc.update(
                            "update task_state set snapshot_json=?,version=3 where id=19 and version=2", (String) inv.getArgument(4)));
            when(records.insertAuditRecord(any())).thenReturn(0);
            assertThrows(ServiceException.class, () -> transactional.edit(9L, 7L, request), failure);
            assertEquals("old", jdbc.queryForObject("select body from post_state where id=7", String.class), failure);
            assertEquals(3, jdbc.queryForObject("select version from post_state where id=7", Integer.class), failure);
            assertEquals("legacy", jdbc.queryForObject("select snapshot_json from task_state where id=19", String.class), failure);
            assertEquals(2, jdbc.queryForObject("select version from task_state where id=19", Integer.class), failure);
            assertEquals(0, jdbc.queryForObject("select count(*) from revision_state", Integer.class), failure);
        }
    }

    @Test
    void publishPassMakesPostVisibleAndWritesSearchAndOutbox() {
        CommunityPost post = draft(7L, 9L, 3);
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        when(mapper.selectActiveGuideline()).thenReturn(Collections.<String, Object>singletonMap("ID", 5L));
        when(mapper.countGuidelineAck(9L, 5L)).thenReturn(1);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(safetyGate.decide(any())).thenReturn(SafetyDecision.pass());
        when(mapper.transitionCas(eq(7L), eq("DRAFT"), eq("PUBLISHED"), eq(3), any(), eq(null), eq(1), eq(1), eq(1), any())).thenReturn(1);
        when(mapper.upsertSearchDocument(eq(7L), eq("VISIBLE"), any())).thenReturn(1);
        when(mapper.insertOutbox(anyString(), eq(7L), eq("POST_PUBLISHED"), anyString(), any())).thenReturn(1);
        CommunityPost visible = draft(7L, 9L, 4);
        visible.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(visible);
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectMentionedMemberIds(7L)).thenReturn(Arrays.asList(21L, 9L));

        service.publish(9L, null, 7L, state(3));

        verify(mapper).upsertSearchDocument(eq(7L), eq("VISIBLE"), any());
        verify(mapper).insertOutbox(anyString(), eq(7L), eq("POST_PUBLISHED"), anyString(), any());
        verify(mapper).insertTypedOutbox(anyString(), eq("COMMUNITY_NOTIFICATION"), eq(21L),
                eq("NOTIFICATION_REQUESTED"), anyString(), any());
    }

    @Test
    void publishWithoutCurrentGuidelineAckStopsBeforeSafetyGate() {
        when(mapper.selectByIdForUpdate(7L)).thenReturn(draft(7L, 9L, 3));
        when(mapper.selectActiveGuideline()).thenReturn(Collections.<String, Object>singletonMap("id", 5L));
        when(mapper.countGuidelineAck(9L, 5L)).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.publish(9L, null, 7L, state(3)));

        assertEquals(Integer.valueOf(428), error.getCode());
        verify(safetyGate, never()).decide(any());
    }

    @Test
    void editingPublishedPostRequiresCurrentGuidelineAckBeforeReview() {
        CommunityPost published = draft(7L, 9L, 3);
        published.setLifecycle("PUBLISHED");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(published);
        when(mapper.selectActiveGuideline()).thenReturn(Collections.<String, Object>singletonMap("id", 5L));
        when(mapper.countGuidelineAck(9L, 5L)).thenReturn(0);
        CommunityPostRequests.Upsert request = validUpsert();
        request.setExpectedVersion(3);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.edit(9L, 7L, request));

        assertEquals(Integer.valueOf(428), error.getCode());
        verify(mapper, never()).insertRevision(anyLong(), anyLong(), anyInt(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void publishRetryReturnsStoredStateWithoutRunningSafetyAgain() {
        CommunityPost published = draft(7L, 9L, 4);
        published.setLifecycle("PUBLISHED");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(published);
        Map<String, Object> receipt = new HashMap<>();
        receipt.put("command_type", "POST_PUBLISH");
        receipt.put("aggregate_id", 7L);
        when(mapper.selectCommandReceipt(9L, "publish-request-1")).thenReturn(receipt);
        when(mapper.selectById(7L, 9L)).thenReturn(published);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());

        service.publish(9L, null, 7L, state(3));

        verify(safetyGate, never()).decide(any());
        verify(mapper, never()).transitionCas(anyLong(), anyString(), anyString(), anyInt(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void publicPostCannotStoreExactLocation() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setLocationPrecision("EXACT");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void incompleteBodyCanBeSavedAsDraft() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setBody("   ");
        CommunityPost stored = draft(7L, 9L, 0);
        stored.setBody("");
        when(mapper.insertPost(any())).thenAnswer(invocation -> {
            CommunityPost inserted = invocation.getArgument(0);
            inserted.setId(7L);
            return 1;
        });
        when(mapper.upsertSearchDocument(eq(7L), eq("HIDDEN"), any())).thenReturn(1);
        when(mapper.selectById(7L, 9L)).thenReturn(stored);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());

        Map<String, Object> result = service.createDraft(9L, request);

        assertEquals(stored, result.get("post"));
    }

    @Test
    void completedOwnDeletedCreateReceiptReturnsOnlyTerminalFlagWithoutReattachingMedia() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setMediaIds(List.of(31L));
        when(mapper.attachMedia(eq(7L), eq(9L), eq(31L), eq(0), any())).thenReturn(1);
        Map<String, Object> receipt = captureCreateReceipt(request);
        CommunityPost current = draft(7L, 9L, 0);
        when(mapper.selectByIdForUpdate(7L)).thenReturn(current);
        when(mapper.transitionCas(eq(7L), eq("DRAFT"), eq("DELETED"), eq(0), isNull(), any(),
                eq(0), eq(0), eq(0), any())).thenReturn(1);
        when(mapper.insertErasureJob(isNull(), eq(7L), eq("POST_DELETE"), eq("delete-request-1"), anyString(), any()))
                .thenReturn(1);
        CommunityPostRequests.State deletion = state(0);
        deletion.setRequestId("delete-request-1");
        service.delete(9L, 7L, deletion);
        verify(mapper).insertErasureJob(isNull(), eq(7L), eq("POST_DELETE"), eq("delete-request-1"), anyString(), any());
        verify(mapper).insertCommandReceipt(eq(9L), eq("delete-request-1"), eq("POST_DELETE"), eq(7L), anyString(), any());
        when(mapper.countAuthorDeleteEvidence(9L, 7L)).thenReturn(1);
        CommunityPost deleted = draft(7L, 9L, 1);
        deleted.setLifecycle("DELETED");
        when(mapper.selectByAuthorRequest(9L, request.getClientRequestId())).thenReturn(deleted);
        when(mapper.selectCommandReceipt(9L, request.getClientRequestId())).thenReturn(receipt);
        // After deletion media may no longer be attachable; a replay must not reattach it.
        when(mapper.attachMedia(eq(7L), eq(9L), eq(31L), eq(0), any())).thenReturn(0);
        clearInvocations(mapper);

        assertEquals(Map.of("intentDiscarded", true), service.createDraft(9L, request));

        verify(mapper, never()).selectById(anyLong(), anyLong());
        verify(mapper, never()).insertPost(any());
        verify(mapper, never()).attachMedia(anyLong(), anyLong(), anyLong(), anyInt(), any());
        verify(mapper, never()).insertCommandReceipt(anyLong(), anyString(), anyString(), anyLong(), anyString(), any());
        verify(mapper, never()).insertOutbox(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void terminalCreateReceiptRequiresExactOwnerAggregateAndExistingRow() {
        CommunityPostRequests.Upsert request = validUpsert();
        Map<String, Object> receipt = captureCreateReceipt(request);
        when(mapper.selectCommandReceipt(9L, request.getClientRequestId())).thenReturn(receipt);
        when(mapper.selectById(7L, 9L)).thenReturn(null);
        CommunityPost otherOwner = draft(7L, 10L, 1);
        otherOwner.setLifecycle("DELETED");
        CommunityPost otherAggregate = draft(8L, 9L, 1);
        otherAggregate.setLifecycle("DELETED");
        for (CommunityPost candidate : Arrays.asList(otherOwner, otherAggregate, null)) {
            when(mapper.selectByAuthorRequest(9L, request.getClientRequestId())).thenReturn(candidate);
            ServiceException failure = assertThrows(ServiceException.class, () -> service.createDraft(9L, request));
            assertEquals(Integer.valueOf(404), failure.getCode());
        }
        CommunityPost deleted = draft(7L, 9L, 1);
        deleted.setLifecycle("DELETED");
        when(mapper.selectByAuthorRequest(9L, request.getClientRequestId())).thenReturn(deleted);
        for (Long aggregate : Arrays.asList(0L, -1L, 8L, null)) {
            Map<String, Object> invalidAggregate = new HashMap<>(receipt);
            invalidAggregate.put("aggregate_id", aggregate);
            when(mapper.selectCommandReceipt(9L, request.getClientRequestId())).thenReturn(invalidAggregate);
            assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class,
                    () -> service.createDraft(9L, request)).getCode());
        }
        verify(mapper, times(1)).insertPost(any());
    }

    @Test
    void removedAndOrdinaryCreateReplaysNeverReturnDeletedTerminalFlag() {
        CommunityPostRequests.Upsert request = validUpsert();
        Map<String, Object> receipt = captureCreateReceipt(request);
        when(mapper.selectCommandReceipt(9L, request.getClientRequestId())).thenReturn(receipt);
        for (String lifecycle : List.of("DRAFT", "CHECKING", "PUBLISHED", "LIMITED", "HIDDEN", "REMOVED")) {
            CommunityPost existing = draft(7L, 9L, 1);
            existing.setLifecycle(lifecycle);
            when(mapper.selectByAuthorRequest(9L, request.getClientRequestId())).thenReturn(existing);
            when(mapper.selectById(7L, 9L)).thenReturn(existing);
            Map<String, Object> result = service.createDraft(9L, request);
            assertEquals(existing, result.get("post"));
            assertEquals(false, result.containsKey("intentDiscarded"));
        }
        verify(mapper, times(1)).insertPost(any());
    }

    @Test
    void deletedCreateReceiptStillRejectsDifferentFingerprintOrIncompleteCommand() {
        CommunityPostRequests.Upsert request = validUpsert();
        Map<String, Object> receipt = captureCreateReceipt(request);
        CommunityPost deleted = draft(7L, 9L, 1);
        deleted.setLifecycle("DELETED");
        when(mapper.selectByAuthorRequest(9L, request.getClientRequestId())).thenReturn(deleted);
        when(mapper.selectCommandReceipt(9L, request.getClientRequestId())).thenReturn(receipt);
        CommunityPostRequests.Upsert changedBody = validUpsert(); changedBody.setBody("changed");
        CommunityPostRequests.Upsert changedMedia = validUpsert(); changedMedia.setMediaIds(List.of(31L));
        CommunityPostRequests.Upsert changedTopic = validUpsert(); changedTopic.setTopicCodes(List.of("WALK"));
        for (CommunityPostRequests.Upsert changed : List.of(changedBody, changedMedia, changedTopic)) {
            assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                    () -> service.createDraft(9L, changed)).getCode());
        }
        Map<String, Object> wrongCommand = new HashMap<>(receipt); wrongCommand.put("command_type", "POST_PUBLISH");
        Map<String, Object> missingFingerprint = new HashMap<>(receipt); missingFingerprint.put("result_json", "{}");
        for (Map<String, Object> incomplete : List.of(wrongCommand, missingFingerprint)) {
            when(mapper.selectCommandReceipt(9L, request.getClientRequestId())).thenReturn(incomplete);
            assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                    () -> service.createDraft(9L, request)).getCode());
        }
        verify(mapper, times(1)).insertPost(any());
    }

    @Test
    void deletedRowWithoutCompleteCreateReceiptCannotDiscardIntentOrCreateAgain() {
        CommunityPostRequests.Upsert request = validUpsert();
        CommunityPost deleted = draft(7L, 9L, 1);
        deleted.setLifecycle("DELETED");
        when(mapper.selectByAuthorRequest(9L, request.getClientRequestId())).thenReturn(deleted);
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request)).getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void deletedCreateReplayWithoutVerifiedAuthorDeletionEvidenceNeverDiscards() {
        CommunityPostRequests.Upsert request = validUpsert();
        Map<String, Object> receipt = captureCreateReceipt(request);
        CommunityPost deleted = draft(7L, 9L, 1);
        deleted.setLifecycle("DELETED");
        when(mapper.selectCommandReceipt(9L, request.getClientRequestId())).thenReturn(receipt);
        when(mapper.selectByAuthorRequest(9L, request.getClientRequestId())).thenReturn(deleted);
        when(mapper.selectById(7L, 9L)).thenReturn(null);
        // Zero includes absent, partial, copyright, deregistration and legal-hold evidence.
        // The real SQL tests prove each category; the service must not infer permission from DELETED.
        when(mapper.countAuthorDeleteEvidence(9L, 7L)).thenReturn(0);
        clearInvocations(mapper);
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request)).getCode());
        verify(mapper).countAuthorDeleteEvidence(9L, 7L);
        verify(mapper, never()).insertPost(any());
        verify(mapper, never()).attachMedia(anyLong(), anyLong(), anyLong(), anyInt(), any());
    }

    private Map<String, Object> captureCreateReceipt(CommunityPostRequests.Upsert request) {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        when(mapper.insertPost(any())).thenAnswer(invocation -> {
            CommunityPost inserted = invocation.getArgument(0);
            inserted.setId(7L);
            return 1;
        });
        when(mapper.selectById(7L, 9L)).thenReturn(draft(7L, 9L, 0));
        when(mapper.insertCommandReceipt(eq(9L), eq(request.getClientRequestId()), eq("POST_CREATE"),
                eq(7L), anyString(), any())).thenAnswer(invocation -> {
            Map<String, Object> receipt = new HashMap<>();
            receipt.put("member_id", 9L);
            receipt.put("request_id", request.getClientRequestId());
            receipt.put("command_type", "POST_CREATE");
            receipt.put("aggregate_id", 7L);
            receipt.put("result_json", invocation.getArgument(4));
            captured.set(receipt);
            return 1;
        });
        service.createDraft(9L, request);
        return captured.get();
    }

    @Test
    void incompleteBodyCannotBePublished() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setBody(" ");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.publish(9L, null, 7L, state(3)));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(safetyGate, never()).decide(any());
    }

    @Test
    void accountEnforcementBlocksCreation() {
        when(mapper.countActiveMemberEnforcement(anyLong(), anyList(), any())).thenReturn(1);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, validUpsert()));

        assertEquals(Integer.valueOf(403), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void referenceSnapshotMustBePublicJsonObject() {
        CommunityPostRequests.Upsert request = validUpsert();
        CommunityPostRequests.Reference reference = new CommunityPostRequests.Reference();
        reference.setReferenceType("ACTIVITY");
        reference.setReferenceId(8L);
        reference.setSnapshotJson("[]");
        reference.setPrivacySnapshot("OWNER_ONLY");
        request.setReferences(Collections.singletonList(reference));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void authorCannotForgeUnrecognizedSafetyLabels() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setSafetyLabels(Collections.singletonList("PLATFORM_VERIFIED"));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void mediaRegistrationRejectsExecutableOrUntrustedSchemes() {
        CommunityPostRequests.MediaRegister request = new CommunityPostRequests.MediaRegister();
        request.setUploadRequestId("upload-request-1");
        request.setMediaType("IMAGE");
        request.setObjectKey("javascript:alert(1)");
        request.setMimeType("image/jpeg");
        request.setByteSize(123L);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.registerMedia(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertMedia(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), any(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void followingFeedRequiresPrincipalEvenThoughPublicFeedsAllowGuests() {
        CommunityPostFeedQuery query = new CommunityPostFeedQuery();
        query.setMode("FOLLOWING");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.feed(0L, query));

        assertEquals(Integer.valueOf(401), error.getCode());
        verify(mapper, never()).selectFeed(any());
    }

    @Test
    void publishPassUsesRealMediaUrlAndMakesApprovedMediaPublic() {
        CommunityPost post = draft(7L, 9L, 3);
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        when(mapper.selectActiveGuideline()).thenReturn(Collections.<String, Object>singletonMap("id", 5L));
        when(mapper.countGuidelineAck(9L, 5L)).thenReturn(1);
        Map<String, Object> media = new HashMap<>();
        media.put("derived_object_key", "https://cdn.example/community/image.jpg");
        when(mapper.selectMediaForSafety(7L)).thenReturn(Collections.singletonList(media));
        when(safetyGate.decide(argThat(request -> request.getMediaUrls().equals(
                Collections.singletonList("https://cdn.example/community/image.jpg")))))
                .thenReturn(SafetyDecision.pass());
        when(mapper.transitionCas(eq(7L), eq("DRAFT"), eq("PUBLISHED"), eq(3), any(), eq(null),
                eq(1), eq(1), eq(1), any())).thenReturn(1);
        when(mapper.updateMediaReviewState(eq(7L), eq("PASS"), any())).thenReturn(1);
        when(mapper.upsertSearchDocument(eq(7L), eq("VISIBLE"), any())).thenReturn(1);
        when(mapper.insertOutbox(anyString(), eq(7L), eq("POST_PUBLISHED"), anyString(), any())).thenReturn(1);
        CommunityPost visible = draft(7L, 9L, 4);
        visible.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(visible);
        when(mapper.selectMedia(7L)).thenReturn(Collections.singletonList(media));
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());

        service.publish(9L, null, 7L, state(3));

        verify(mapper).updateMediaReviewState(eq(7L), eq("PASS"), any());
    }

    @Test
    void mediaRequestIdCannotBeReplayedForAnotherObject() {
        CommunityPostRequests.MediaRegister request = new CommunityPostRequests.MediaRegister();
        request.setUploadRequestId("upload-request-1");
        request.setMediaType("IMAGE");
        request.setObjectKey("https://cdn.example/community/new.jpg");
        request.setMimeType("image/jpeg");
        request.setByteSize(123L);
        request.setUploadReceipt("receipt");
        Map<String, Object> existing = new HashMap<>();
        existing.put("media_type", "IMAGE");
        existing.put("derived_object_key", "https://cdn.example/community/old.jpg");
        existing.put("mime_type", "image/jpeg");
        existing.put("byte_size", 123L);
        when(mapper.selectMediaByOwnerRequest(9L, "upload-request-1")).thenReturn(existing);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.registerMedia(9L, request));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper, never()).insertMedia(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), any(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void uploadReceiptCannotBeConsumedByAnotherRegistration() {
        CommunityPostRequests.MediaRegister request = new CommunityPostRequests.MediaRegister();
        request.setUploadRequestId("another-request");
        request.setMediaType("IMAGE");
        request.setObjectKey("https://cdn.example/community/photo.jpg");
        request.setMimeType("image/jpeg");
        request.setByteSize(123L);
        request.setUploadReceipt("signed-receipt");
        Map<String, Object> consumed = new HashMap<>();
        consumed.put("owner_member_id", 11L);
        when(mapper.selectMediaByReceiptHash(anyString())).thenReturn(consumed);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.registerMedia(9L, request));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper, never()).insertMedia(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), any(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void blockedCommentNeverTransitionsToPublished() {
        CommunityPost post = draft(7L, 11L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        CommunityPostRequests.Comment request = new CommunityPostRequests.Comment();
        request.setClientRequestId("comment-request-1");
        request.setBody("违规回复");
        Map<String, Object> inserted = new HashMap<>();
        inserted.put("id", 17L);
        inserted.put("post_id", 7L);
        inserted.put("author_id", 9L);
        inserted.put("body", "违规回复");
        inserted.put("author_approval_state", "VISIBLE");
        inserted.put("lifecycle", "CHECKING");
        when(mapper.selectCommentByAuthorRequest(9L, "comment-request-1")).thenReturn(null, inserted);
        when(mapper.insertComment(eq(7L), eq(9L), eq(null), eq(null), eq(null), anyString(),
                eq("违规回复"), eq("CHECKING"), eq("VISIBLE"), any())).thenReturn(1);
        when(safetyGate.decide(argThat(gate -> AuditTask.BIZ_COMMUNITY_POST_COMMENT.equals(gate.getContentType())
                && Long.valueOf(17L).equals(gate.getContentId())))).thenReturn(SafetyDecision.block("POLICY"));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.comment(9L, 7L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).updateCommentLifecycle(anyLong(), anyString(), anyString(), anyInt(), any());
        verify(mapper, never()).recomputeCounts(7L);
    }

    @Test
    void duplicateCommentRecoversCommittedWinnerWithCurrentRead() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        CommunityPostRequests.Comment request = new CommunityPostRequests.Comment();
        request.setClientRequestId("comment-concurrent");
        request.setBody("并发回复");
        Map<String, Object> winner = new HashMap<>();
        winner.put("post_id", 7L);
        winner.put("body", "并发回复");
        winner.put("author_approval_state", "VISIBLE");
        winner.put("lifecycle", "PUBLISHED");
        when(mapper.selectCommentByAuthorRequest(9L, "comment-concurrent")).thenReturn(null);
        when(mapper.insertComment(eq(7L), eq(9L), isNull(), isNull(), isNull(),
                eq("comment-concurrent"), eq("并发回复"), eq("CHECKING"), eq("VISIBLE"), any()))
                .thenThrow(new DuplicateKeyException("committed winner"));
        when(mapper.selectCommentByAuthorRequestForUpdate(9L, "comment-concurrent")).thenReturn(winner);

        Map<String, Object> result = service.comment(9L, 7L, request);

        assertEquals("PUBLISHED", result.get("lifecycle"));
        assertEquals("VISIBLE", result.get("approvalState"));
        verify(mapper, times(1)).selectCommentByAuthorRequest(9L, "comment-concurrent");
        verify(mapper).selectCommentByAuthorRequestForUpdate(9L, "comment-concurrent");
        verify(safetyGate, never()).decide(any());
        verify(mapper, never()).recomputeCounts(anyLong());
        verify(mapper, never()).insertOutbox(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void duplicateCommentCurrentReadStillRejectsChangedBodyOrPost() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        CommunityPostRequests.Comment request = new CommunityPostRequests.Comment();
        request.setClientRequestId("comment-conflict");
        request.setBody("请求正文");
        when(mapper.insertComment(eq(7L), eq(9L), isNull(), isNull(), isNull(),
                eq("comment-conflict"), eq("请求正文"), eq("CHECKING"), eq("VISIBLE"), any()))
                .thenThrow(new DuplicateKeyException("committed winner"));
        for (boolean differentPost : Arrays.asList(false, true)) {
            Map<String, Object> winner = new HashMap<>();
            winner.put("post_id", differentPost ? 8L : 7L);
            winner.put("body", differentPost ? "请求正文" : "不同正文");
            when(mapper.selectCommentByAuthorRequestForUpdate(9L, "comment-conflict")).thenReturn(winner);
            ServiceException error = assertThrows(ServiceException.class,
                    () -> service.comment(9L, 7L, request));
            assertEquals(Integer.valueOf(409), error.getCode());
        }
        verify(safetyGate, never()).decide(any());
        verify(mapper, never()).recomputeCounts(anyLong());
        verify(mapper, never()).insertOutbox(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void deletedCommentCannotBeResurrectedByLateAuditCallback() {
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", 17L);
        comment.put("post_id", 7L);
        comment.put("lifecycle", "DELETED");
        comment.put("version", 4);
        when(mapper.selectCommentForAudit(17L)).thenReturn(comment);

        service.applyCommentAuditDecision(17L, true);

        verify(mapper, never()).updateCommentLifecycle(anyLong(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void maintenanceKeepsPublishedLifecycleWhileCombiningIndependentLimits() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("LIMITED");
        when(mapper.selectEnforcementBoundaryPostIds(any(), anyInt())).thenReturn(Collections.singletonList(7L));
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any())).thenReturn(
                Arrays.asList("DOWNRANK", "SEARCH_EXCLUDE", "INTERACTION_LIMIT"));
        when(mapper.reconcilePostEnforcementCas(eq(7L), eq(3), eq("PUBLISHED"), eq(0), eq(0), eq(0), any()))
                .thenReturn(1);

        service.runMaintenance(20);

        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("PUBLISHED"), eq(0), eq(0), eq(0), any());
    }

    @Test
    void overlappingEnforcementKeepsOriginalPublishedBaseline() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("HIDDEN");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        when(mapper.selectActiveEnforcementBaseline(eq(7L), any())).thenReturn("PUBLISHED");
        when(mapper.insertEnforcement(anyLong(), any(), anyString(), anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyLong(), any(), any(), anyString(), any())).thenReturn(1);
        Map<String, Object> stored = new HashMap<>();
        stored.put("id", 21L);
        when(mapper.selectEnforcementByRequest(5L, "enforce-overlap")).thenReturn(stored);
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any()))
                .thenReturn(Arrays.asList("HIDE_PENDING_REVIEW", "REMOVE"));
        when(mapper.reconcilePostEnforcementCas(eq(7L), eq(3), eq("REMOVED"), eq(0), eq(0), eq(0), any()))
                .thenReturn(1);
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setEnforcementType("REMOVE");
        request.setRuleCode("SAFETY");
        request.setGuidelineVersionId(5L);
        request.setReasonCode("HARM");
        request.setEvidenceJson("{\"snapshotId\":21}");
        request.setRequestId("enforce-overlap");

        service.enforce(5L, 7L, request);

        verify(mapper).insertEnforcement(eq(7L), any(), eq("REMOVE"), eq("SAFETY"), eq(5L),
                eq("HARM"), eq("{\"snapshotId\":21}"), eq("PUBLISHED"), eq(5L), any(), any(), eq("enforce-overlap"), any());
    }

    @Test
    void memberEnforcementMustExplicitlyTargetPostAuthor() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setEnforcementType("PERMANENT_BAN");
        request.setRuleCode("SERIOUS_ABUSE");
        request.setGuidelineVersionId(5L);
        request.setReasonCode("ABUSE");
        request.setEvidenceJson("{\"snapshotId\":22}");
        request.setRequestId("member-ban-1");

        ServiceException missing = assertThrows(ServiceException.class,
                () -> service.enforce(5L, 7L, request));
        assertEquals(Integer.valueOf(400), missing.getCode());

        request.setTargetMemberId(10L);
        request.setRequestId("member-ban-2");
        ServiceException mismatch = assertThrows(ServiceException.class,
                () -> service.enforce(5L, 7L, request));
        assertEquals(Integer.valueOf(400), mismatch.getCode());
        verify(mapper, never()).insertEnforcement(anyLong(), any(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyLong(), any(), any(), anyString(), any());
    }

    @Test
    void enforcementRequiresNonEmptyStructuredEvidence() {
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setEnforcementType("REMOVE");
        request.setRuleCode("SAFETY");
        request.setGuidelineVersionId(5L);
        request.setReasonCode("HARM");
        request.setRequestId("enforce-no-evidence");

        ServiceException missing = assertThrows(ServiceException.class,
                () -> service.enforce(5L, 7L, request));
        assertEquals(Integer.valueOf(400), missing.getCode());

        request.setEvidenceJson("{}");
        ServiceException empty = assertThrows(ServiceException.class,
                () -> service.enforce(5L, 7L, request));
        assertEquals(Integer.valueOf(400), empty.getCode());
        verify(mapper, never()).insertEnforcement(anyLong(), any(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyLong(), any(), any(), anyString(), any());
    }

    @Test
    void enforcementRejectsUnknownGuidelineVersion() {
        when(mapper.countGuidelineVersion(99L)).thenReturn(0);
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setEnforcementType("REMOVE");
        request.setRuleCode("SAFETY");
        request.setGuidelineVersionId(99L);
        request.setReasonCode("HARM");
        request.setEvidenceJson("{\"snapshotId\":99}");
        request.setRequestId("enforce-unknown-guideline");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.enforce(5L, 7L, request));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper, never()).selectByIdForUpdate(anyLong());
    }

    @Test
    void enforcementRejectsRuleOutsideSelectedGuidelineVersion() {
        when(mapper.countGuidelineRule(5L, "MADE_UP_RULE")).thenReturn(0);
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setEnforcementType("REMOVE");
        request.setRuleCode("made_up_rule");
        request.setGuidelineVersionId(5L);
        request.setReasonCode("HARM");
        request.setEvidenceJson("{\"snapshotId\":99}");
        request.setRequestId("enforce-unknown-rule");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.enforce(5L, 7L, request));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper, never()).selectByIdForUpdate(anyLong());
    }

    @Test
    void enforcementStoresTheSameNormalizedRuleCodeThatWasValidated() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        when(mapper.insertEnforcement(eq(7L), eq(9L), eq("REMOVE"), eq("SAFETY"), eq(5L),
                eq("HARM"), anyString(), eq("PUBLISHED"), eq(5L), any(), any(), anyString(), any()))
                .thenReturn(1);
        Map<String, Object> saved = new HashMap<>();
        saved.put("id", 31L);
        when(mapper.selectEnforcementByRequest(5L, "normalize-rule-1")).thenReturn(saved);
        CommunityPostRequests.Enforcement request = new CommunityPostRequests.Enforcement();
        request.setTargetMemberId(9L);
        request.setEnforcementType("remove");
        request.setRuleCode(" safety ");
        request.setGuidelineVersionId(5L);
        request.setReasonCode("HARM");
        request.setEvidenceJson("{\"snapshotId\":99}");
        request.setRequestId("normalize-rule-1");
        request.setStartsAt(new Date(System.currentTimeMillis() + 60_000L));

        service.enforce(5L, 7L, request);

        verify(mapper).countGuidelineRule(5L, "SAFETY");
        verify(mapper).insertEnforcement(eq(7L), eq(9L), eq("REMOVE"), eq("SAFETY"), eq(5L),
                eq("HARM"), anyString(), eq("PUBLISHED"), eq(5L), any(), any(), anyString(), any());
    }

    @Test
    void appealSubmissionWritesGovernanceNotificationAndOutbox() {
        Map<String, Object> enforcement = new HashMap<>();
        enforcement.put("target_member_id", 9L);
        enforcement.put("post_id", 7L);
        when(mapper.selectEnforcement(31L)).thenReturn(enforcement);
        CommunityPostRequests.Appeal request = new CommunityPostRequests.Appeal();
        request.setEnforcementId(31L);
        request.setReason("需要人工复核");
        request.setRequestId("appeal-submit-1");

        service.appeal(9L, request);

        verify(mapper).insertTypedOutbox(anyString(), eq("COMMUNITY_NOTIFICATION"), eq(9L),
                eq("NOTIFICATION_REQUESTED"), anyString(), any());
        verify(mapper).insertOutbox(anyString(), eq(7L), eq("APPEAL_SUBMITTED"), anyString(), any());
    }

    @Test
    void appealReviewNotifiesAppellantAndWritesOutbox() {
        Map<String, Object> appeal = new HashMap<>();
        appeal.put("enforcement_id", 31L);
        appeal.put("appellant_member_id", 9L);
        Map<String, Object> enforcement = new HashMap<>();
        enforcement.put("post_id", 7L);
        enforcement.put("version", 0);
        when(mapper.selectAppeal(41L)).thenReturn(appeal);
        when(mapper.selectEnforcement(31L)).thenReturn(enforcement);
        when(mapper.reviewAppealCas(eq(41L), eq("UPHELD"), anyString(), eq(5L), eq(0), any())).thenReturn(1);
        CommunityPostRequests.AppealReview request = new CommunityPostRequests.AppealReview();
        request.setDecision("UPHELD");
        request.setReason("复核维持");
        request.setExpectedVersion(0);

        service.reviewAppeal(5L, 41L, request);

        verify(mapper).insertTypedOutbox(anyString(), eq("COMMUNITY_NOTIFICATION"), eq(9L),
                eq("NOTIFICATION_REQUESTED"), anyString(), any());
        verify(mapper).insertOutbox(anyString(), eq(7L), eq("APPEAL_REVIEWED"), anyString(), any());
    }

    @Test
    void accountAppealWritesReplayableAccountOutbox() {
        Map<String, Object> enforcement = new HashMap<>();
        enforcement.put("target_member_id", 9L);
        enforcement.put("post_id", null);
        when(mapper.selectEnforcement(31L)).thenReturn(enforcement);
        CommunityPostRequests.Appeal request = new CommunityPostRequests.Appeal();
        request.setEnforcementId(31L);
        request.setReason("账号处置需要复核");
        request.setRequestId("account-appeal-1");

        service.appeal(9L, request);

        verify(mapper).insertTypedOutbox(anyString(), eq("COMMUNITY_MEMBER"), eq(9L),
                eq("ACCOUNT_APPEAL_SUBMITTED"), anyString(), any());
    }

    @Test
    void accountAppealOutboxUpdatesReplayableMemberGovernanceProjection() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 60L);
        row.put("event_id", "event-60");
        row.put("aggregate_type", "COMMUNITY_MEMBER");
        row.put("aggregate_id", 9L);
        row.put("event_type", "ACCOUNT_APPEAL_SUBMITTED");
        row.put("payload_json", "{\"enforcementId\":31}");
        when(mapper.selectDueOutbox(any(), anyInt())).thenReturn(Collections.singletonList(row));
        when(mapper.claimOutbox(eq(60L), any())).thenReturn(1);
        when(mapper.upsertMemberGovernanceProjection(eq(9L), eq("ACCOUNT_APPEAL_SUBMITTED"),
                eq("{\"enforcementId\":31}"), any())).thenReturn(1);

        service.runMaintenance(20);

        verify(mapper).upsertMemberGovernanceProjection(eq(9L), eq("ACCOUNT_APPEAL_SUBMITTED"),
                eq("{\"enforcementId\":31}"), any());
        verify(mapper).completeOutbox(eq(60L), any());
    }

    @Test
    void activeGuidelineUpdateQueuesOneGovernanceNoticePerUnacknowledgedCommunityMember() {
        when(mapper.selectActiveGuideline()).thenReturn(Collections.<String, Object>singletonMap("id", 5L));
        when(mapper.selectGuidelineNoticeRecipients(5L, "null:GUIDELINE_UPDATED:5", 20))
                .thenReturn(Arrays.asList(9L, 10L));

        Map<String, Integer> result = service.runMaintenance(20);

        assertEquals(Integer.valueOf(2), result.get("guidelineNotices"));
        verify(mapper, times(2)).insertTypedOutbox(anyString(), eq("COMMUNITY_NOTIFICATION"), anyLong(),
                eq("NOTIFICATION_REQUESTED"), anyString(), any());
    }

    @Test
    void appealEvidenceAttachmentsFailClosedUntilOwnershipAndRedactionAreConnected() {
        Map<String, Object> enforcement = new HashMap<>();
        enforcement.put("target_member_id", 9L);
        enforcement.put("post_id", 7L);
        when(mapper.selectEnforcement(31L)).thenReturn(enforcement);
        CommunityPostRequests.Appeal request = new CommunityPostRequests.Appeal();
        request.setEnforcementId(31L);
        request.setReason("请复核");
        request.setRequestId("appeal-evidence-1");
        request.setEvidenceAssetIds(Collections.singletonList(71L));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.appeal(9L, request));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper, never()).insertAppeal(anyLong(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void notificationOutboxRechecksVisibilityBeforeDelivery() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 61L);
        row.put("event_id", "event-61");
        row.put("aggregate_type", "COMMUNITY_NOTIFICATION");
        row.put("aggregate_id", 9L);
        row.put("payload_json", "{\"recipientId\":9,\"postId\":7,\"actorId\":8,"
                + "\"commentId\":17,\"notificationType\":\"INTERACTION\",\"aggregateKey\":\"7:REPLY:8:17\"}");
        when(mapper.selectDueOutbox(any(), anyInt())).thenReturn(Collections.singletonList(row));
        when(mapper.claimOutbox(eq(61L), any())).thenReturn(1);
        when(mapper.countNotificationDeliverable(9L, 7L, 8L, 17L)).thenReturn(0);

        service.runMaintenance(20);

        verify(mapper, never()).insertNotification(anyLong(), anyString(), any(), any(), any(), anyString(),
                anyString(), anyString(), any());
        verify(mapper).completeOutbox(eq(61L), any());
    }

    @Test
    void notificationApiReplacesPayloadActorWithTheRelationshipFilteredActor() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 71L);
        row.put("notification_type", "INTERACTION");
        row.put("actor_member_id", 13L);
        row.put("actor_count", 1);
        row.put("payload_json", "{\"action\":\"LIKE\",\"actorId\":12}");
        when(mapper.selectNotifications(9L, null, 20)).thenReturn(Collections.singletonList(row));

        Map<String, Object> result = service.notifications(9L, null, 20);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertEquals(1, items.size());
        assertEquals(true, String.valueOf(items.get(0).get("payload_json")).contains("\"actorId\":13"));
        assertEquals(false, String.valueOf(items.get(0).get("payload_json")).contains("\"actorId\":12"));
    }

    @Test
    void followQueuesSocialNotificationWithoutInventingAPost() {
        service.notifyFollow(9L, 12L);

        verify(mapper).insertTypedOutbox(anyString(), eq("COMMUNITY_NOTIFICATION"), eq(9L),
                eq("NOTIFICATION_REQUESTED"), argThat(payload -> payload.contains("\"action\":\"FOLLOW\"")
                        && payload.contains("\"postId\":null")), any());
    }

    @Test
    void notificationDeliveryFailureMovesOutboxToRetry() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 62L);
        row.put("event_id", "event-62");
        row.put("aggregate_type", "COMMUNITY_NOTIFICATION");
        row.put("aggregate_id", 9L);
        row.put("payload_json", "{\"recipientId\":9,\"postId\":7,\"actorId\":8,"
                + "\"notificationType\":\"INTERACTION\",\"aggregateKey\":\"7:LIKE:8:0\"}");
        when(mapper.selectDueOutbox(any(), anyInt())).thenReturn(Collections.singletonList(row));
        when(mapper.claimOutbox(eq(62L), any())).thenReturn(1);
        when(mapper.countNotificationDeliverable(9L, 7L, 8L, null)).thenReturn(1);
        when(mapper.insertNotification(eq(9L), eq("event-62"), eq(7L), eq(8L), isNull(), eq("INTERACTION"),
                anyString(), anyString(), any())).thenThrow(new RuntimeException("temporary"));

        service.runMaintenance(20);

        verify(mapper).retryOutbox(eq(62L), eq("RuntimeException"), any(), any());
        verify(mapper, never()).completeOutbox(eq(62L), any());
    }

    @Test
    void malformedNotificationOutboxMovesToRetryInsteadOfWritingNullStrings() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 63L);
        row.put("event_id", "event-63");
        row.put("aggregate_type", "COMMUNITY_NOTIFICATION");
        row.put("aggregate_id", 9L);
        row.put("payload_json", "{\"recipientId\":9,\"postId\":7,\"actorId\":8}");
        when(mapper.selectDueOutbox(any(), anyInt())).thenReturn(Collections.singletonList(row));
        when(mapper.claimOutbox(eq(63L), any())).thenReturn(1);

        service.runMaintenance(20);

        verify(mapper).retryOutbox(eq(63L), eq("ServiceException"), any(), any());
        verify(mapper, never()).insertNotification(anyLong(), anyString(), any(), any(), any(), anyString(),
                anyString(), anyString(), any());
        verify(mapper, never()).completeOutbox(eq(63L), any());
    }

    @Test
    void unknownOutboxAggregateTypeMovesToRetry() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 64L);
        row.put("event_id", "event-64");
        row.put("aggregate_type", "UNRECOGNIZED");
        row.put("aggregate_id", 9L);
        row.put("payload_json", "{}");
        when(mapper.selectDueOutbox(any(), anyInt())).thenReturn(Collections.singletonList(row));
        when(mapper.claimOutbox(eq(64L), any())).thenReturn(1);

        service.runMaintenance(20);

        verify(mapper).retryOutbox(eq(64L), eq("ServiceException"), any(), any());
        verify(mapper, never()).completeOutbox(eq(64L), any());
    }

    @Test
    void notificationPreferencesPatchMergesAndGovernanceCannotBeDisabled() {
        Map<String, Object> before = new HashMap<>();
        before.put("interaction_enabled", 1);
        before.put("mention_enabled", 1);
        before.put("social_enabled", 0);
        Map<String, Object> after = new HashMap<>();
        after.put("interaction_enabled", 1);
        after.put("mention_enabled", 0);
        after.put("social_enabled", 0);
        when(mapper.selectNotificationPreferences(9L)).thenReturn(before, after);
        when(mapper.upsertNotificationPreferences(eq(9L), eq(true), eq(false), eq(false), any())).thenReturn(1);
        CommunityPostRequests.NotificationPreferences request = new CommunityPostRequests.NotificationPreferences();
        request.setMentionEnabled(false);

        Map<String, Object> result = service.updateNotificationPreferences(9L, request);

        assertEquals(Boolean.FALSE, result.get("mentionEnabled"));
        assertEquals(Boolean.TRUE, result.get("governanceEnabled"));
        verify(mapper).upsertNotificationPreferences(eq(9L), eq(true), eq(false), eq(false), any());
    }

    @Test
    void feedbackReplayRemainsAcknowledgedAfterVisibilityChanges() {
        for (String action : Arrays.asList("NOT_INTERESTED", "SEE_LESS_AUTHOR", "SEE_LESS_TOPIC")) {
            CommunityPostRequests.Action request = postAction(action, "feedback-" + action);
            CommunityPost post = draft(7L, 8L, 3);
            post.setLifecycle("PUBLISHED");
            AtomicReference<Map<String, Object>> receipt = new AtomicReference<>();
            when(mapper.selectById(7L, 9L)).thenReturn(post, null);
            when(mapper.selectCommandReceipt(9L, request.getRequestId()))
                    .thenAnswer(invocation -> receipt.get());
            when(mapper.insertMemberAction(eq(7L), eq(9L), eq(action), eq(request.getRequestId()), any(), any()))
                    .thenReturn(1);
            when(mapper.insertCommandReceipt(eq(9L), eq(request.getRequestId()), eq("POST_ACTION_" + action),
                    eq(7L), anyString(), any())).thenAnswer(invocation -> {
                receipt.set(Map.of("command_type", invocation.getArgument(2), "aggregate_id", invocation.getArgument(3)));
                return 1;
            });
            Map<String, Object> expected = Map.of("postId", 7L, "action", action, "applied", true);
            assertEquals(expected, service.act(9L, 7L, request));
            verify(mapper).insertMemberAction(eq(7L), eq(9L), eq(action), eq(request.getRequestId()), any(), any());
            verify(mapper).insertCommandReceipt(eq(9L), eq(request.getRequestId()), eq("POST_ACTION_" + action),
                    eq(7L), anyString(), any());
            clearInvocations(mapper);

            assertEquals(expected, service.act(9L, 7L, request));

            verify(mapper).selectCommandReceipt(9L, request.getRequestId());
            org.mockito.Mockito.verifyNoMoreInteractions(mapper);
            clearInvocations(mapper);
        }
    }

    @Test
    void feedbackReplayRejectsReceiptTypeOrAggregateMismatch() {
        CommunityPostRequests.Action request = postAction("NOT_INTERESTED", "feedback-mismatched-receipt");
        for (Map<String, Object> receipt : Arrays.<Map<String, Object>>asList(
                Map.of("command_type", "POST_ACTION_SEE_LESS_AUTHOR", "aggregate_id", 7L),
                Map.of("command_type", "POST_ACTION_NOT_INTERESTED", "aggregate_id", 8L))) {
            when(mapper.selectCommandReceipt(9L, request.getRequestId())).thenReturn(receipt);

            assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                    () -> service.act(9L, 7L, request)).getCode());

            verify(mapper).selectCommandReceipt(9L, request.getRequestId());
            org.mockito.Mockito.verifyNoMoreInteractions(mapper);
            clearInvocations(mapper);
        }
    }

    @Test
    void hiddenPostWithoutReceiptStillRejectsWithoutWrites() {
        CommunityPostRequests.Action request = postAction("NOT_INTERESTED", "feedback-current-member-only");
        when(mapper.selectCommandReceipt(9L, request.getRequestId())).thenReturn(
                Map.of("command_type", "POST_ACTION_NOT_INTERESTED", "aggregate_id", 7L));
        for (String action : Arrays.asList("NOT_INTERESTED", "SEE_LESS_AUTHOR", "SEE_LESS_TOPIC", "LIKE", "BOOKMARK")) {
            request.setActionType(action);

            assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class,
                    () -> service.act(10L, 7L, request)).getCode());

            assertNoPostActionWrites();
            clearInvocations(mapper);
        }
        assertEquals(Integer.valueOf(401), assertThrows(ServiceException.class,
                () -> service.act(0L, 7L, request)).getCode());
        assertNoPostActionWrites();
    }

    @Test
    void positiveActionReplayStillRequiresCurrentVisibility() {
        for (String action : Arrays.asList("LIKE", "BOOKMARK")) {
            CommunityPostRequests.Action request = postAction(action, "positive-replay-" + action);
            when(mapper.selectCommandReceipt(9L, request.getRequestId())).thenReturn(
                    Map.of("command_type", "POST_ACTION_" + action, "aggregate_id", 7L));

            assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class,
                    () -> service.act(9L, 7L, request)).getCode());

            assertNoPostActionWrites();
            clearInvocations(mapper);
        }
    }

    private void assertNoPostActionWrites() {
        verify(mapper, never()).insertMemberAction(anyLong(), anyLong(), anyString(), anyString(), any(), any());
        verify(mapper, never()).insertCommandReceipt(anyLong(), anyString(), anyString(), anyLong(), anyString(), any());
        verify(mapper, never()).recomputeCounts(anyLong());
        verify(mapper, never()).insertOutbox(anyString(), anyLong(), anyString(), anyString(), any());
        verify(mapper, never()).insertTypedOutbox(anyString(), anyString(), anyLong(), anyString(), anyString(), any());
    }

    private CommunityPostRequests.Action postAction(String action, String requestId) {
        CommunityPostRequests.Action request = new CommunityPostRequests.Action();
        request.setActionType(action);
        request.setRequestId(requestId);
        return request;
    }

    @Test
    void commentLikeAndUnlikeWritePostScopedOutboxEvents() {
        Map<String, Object> comment = new HashMap<>();
        comment.put("post_id", 7L);
        when(mapper.selectCommentForAudit(17L)).thenReturn(comment);
        when(mapper.selectCommentActionByRequest(9L, "like-comment-1")).thenReturn(null);
        when(mapper.insertCommentAction(eq(17L), eq(9L), eq("like-comment-1"), any())).thenReturn(1);
        when(mapper.deleteCommentAction(17L, 9L)).thenReturn(1);

        service.likeComment(9L, 17L, "like-comment-1");
        service.unlikeComment(9L, 17L, "unlike-comment-1");

        verify(mapper).insertOutbox(anyString(), eq(7L), eq("COMMENT_LIKED"), anyString(), any());
        verify(mapper).insertOutbox(anyString(), eq(7L), eq("COMMENT_UNLIKED"), anyString(), any());
    }

    @Test
    void concurrentCommentLikeReplayDoesNotEmitDuplicateOutbox() {
        Map<String, Object> replay = new HashMap<>();
        replay.put("comment_id", 17L);
        when(mapper.selectCommentActionByRequest(9L, "like-comment-race"))
                .thenReturn(null, replay);
        when(mapper.insertCommentAction(eq(17L), eq(9L), eq("like-comment-race"), any()))
                .thenThrow(new DuplicateKeyException("duplicate request"));

        service.likeComment(9L, 17L, "like-comment-race");

        verify(mapper, never()).recomputeCommentLikes(anyLong());
        verify(mapper, never()).insertOutbox(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void postMentionsAreVisibleOnlyToTheAuthor() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 8L)).thenReturn(post);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectMentionedMemberIds(7L)).thenReturn(Arrays.asList(21L, 22L));

        Map<String, Object> result = service.detail(8L, 7L);

        assertEquals(Collections.emptyList(), result.get("mentions"));
        assertEquals(Collections.emptyList(), result.get("topicCodes"));
        verify(mapper, never()).selectTopicCodes(7L);
        verify(mapper, never()).selectMentionedMemberIds(7L);
    }

    @Test
    void detailProjectsViewerCommentPermissionFromServerPolicy() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        post.setCommentPolicy("FOLLOWERS");
        when(mapper.selectById(7L, 8L)).thenReturn(post);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());
        when(mapper.countFollow(8L, 9L)).thenReturn(1);

        Map<String, Object> allowed = service.detail(8L, 7L);

        assertEquals(Boolean.TRUE, allowed.get("viewerCanComment"));
        assertEquals(Boolean.TRUE, allowed.get("viewerFollowing"));
        verify(mapper, times(1)).countFollow(8L, 9L);
    }

    @Test
    void detailHidesCommentComposerForRestrictedViewer() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 8L)).thenReturn(post);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());
        when(mapper.countActiveMemberEnforcement(eq(8L), anyList(), any())).thenReturn(1);

        Map<String, Object> result = service.detail(8L, 7L);

        assertEquals(Boolean.FALSE, result.get("viewerCanComment"));
    }

    @Test
    void anonymousViewerCannotCommentEvenWhenPolicySaysEveryone() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 0L)).thenReturn(post);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());

        Map<String, Object> result = service.detail(0L, 7L);

        assertEquals(Boolean.FALSE, result.get("viewerCanComment"));
        assertEquals(Boolean.FALSE, result.get("viewerFollowing"));
        verify(mapper, never()).countFollow(anyLong(), anyLong());
    }

    @Test
    void commentPageCursorUsesOldestRootInsteadOfLastReply() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        when(mapper.selectMedia(7L)).thenReturn(Collections.emptyList());
        when(mapper.selectReferences(7L)).thenReturn(Collections.emptyList());
        Map<String, Object> root = new HashMap<>();
        root.put("id", 100L);
        root.put("parent_id", null);
        Map<String, Object> reply = new HashMap<>();
        reply.put("id", 200L);
        reply.put("parent_id", 100L);
        when(mapper.selectComments(7L, 9L, null, 21)).thenReturn(Arrays.asList(root, reply));

        Map<String, Object> page = service.comments(9L, 7L, null, 20);

        assertEquals(100L, page.get("nextCursor"));
    }

    @Test
    void featuredCommandReplayDoesNotCreateDuplicateCurationEntry() {
        CommunityPost post = draft(7L, 9L, 3);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post);
        when(mapper.insertFeatured(eq(7L), eq("CITY_PICK"), eq("SH"), eq("城市精选"), eq(5L), any(), any(), any()))
                .thenReturn(1);
        AtomicReference<String> storedCommandType = new AtomicReference<>();
        when(mapper.insertCommandReceipt(eq(5L), eq("feature-request-1"), anyString(), eq(7L), anyString(), any()))
                .thenAnswer(invocation -> {
                    storedCommandType.set(invocation.getArgument(2));
                    return 1;
                });
        when(mapper.selectCommandReceipt(5L, "feature-request-1")).thenAnswer(invocation -> {
            if (storedCommandType.get() == null) return null;
            Map<String, Object> receipt = new HashMap<>();
            receipt.put("command_type", storedCommandType.get());
            receipt.put("aggregate_id", 7L);
            return receipt;
        });
        CommunityPostRequests.Featured request = new CommunityPostRequests.Featured();
        request.setCollectionCode("CITY_PICK");
        request.setCityCode("SH");
        request.setReason("城市精选");
        request.setRequestId("feature-request-1");

        service.feature(5L, 7L, request);
        service.feature(5L, 7L, request);

        verify(mapper, times(1)).insertFeatured(eq(7L), eq("CITY_PICK"), eq("SH"), eq("城市精选"),
                eq(5L), any(), any(), any());
    }

    @Test
    void commentReportTargetsTheCommentRatherThanItsPost() {
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", 17L);
        comment.put("post_id", 7L);
        comment.put("author_id", 11L);
        comment.put("lifecycle", "PUBLISHED");
        when(mapper.selectCommentForAudit(17L)).thenReturn(comment);
        when(mapper.selectCommentForReply(17L, 7L, 9L)).thenReturn(comment);
        CommunityPost post = draft(7L, 12L, 1);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        CommunityReportSubmitRequest request = new CommunityReportSubmitRequest();
        request.setTargetType("COMMENT");
        request.setTargetId(17L);
        request.setReasonCode("HARASSMENT");
        request.setRequestId("report-request-1");
        when(communityTrustService.submitReport(9L, request)).thenReturn(submittedReport("COMMENT", 17L));

        Object result = service.report(9L, request);

        assertEquals("COMMENT", request.getTargetType());
        assertEquals(Long.valueOf(17L), request.getTargetId());
        assertReporterProjection(result, "COMMENT", 17L);
        verify(communityTrustService).submitReport(9L, request);
    }

    @Test
    void contentReportSubmissionUsesTheSamePublicReporterProjection() {
        CommunityPost post = draft(7L, 11L, 1);
        post.setLifecycle("PUBLISHED");
        when(mapper.selectById(7L, 9L)).thenReturn(post);
        CommunityReportSubmitRequest request = new CommunityReportSubmitRequest();
        request.setTargetId(7L);
        request.setRequestId("report-content-request");
        when(communityTrustService.submitReport(9L, request)).thenReturn(submittedReport("CONTENT", 7L));

        Object result = service.report(9L, request);

        assertEquals("CONTENT", request.getTargetType());
        assertReporterProjection(result, "CONTENT", 7L);
        verify(communityTrustService).submitReport(9L, request);
    }

    private CommunityReportCase submittedReport(String targetType, Long targetId) {
        CommunityReportCase row = new CommunityReportCase();
        row.setId(23L);
        row.setReporterMemberId(9L);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setStage("SUBMITTED");
        row.setVersion(0);
        row.setPolicyVersion("COMMUNITY_REPORT_POLICY_V1");
        row.setDescription("举报说明");
        row.setRequestId("internal-request-marker");
        row.setPayloadHash("internal-payload-digest");
        row.setPrioritySnapshot("URGENT");
        row.setFirstResponseMinutesSnapshot(15);
        return row;
    }

    private void assertReporterProjection(Object result, String targetType, Long targetId) {
        assertEquals(CommunityReportVO.class, result.getClass());
        Map<String, Object> json = com.alibaba.fastjson2.JSON.parseObject(
                com.alibaba.fastjson2.JSON.toJSONString(result));
        assertEquals(23L, ((Number) json.get("id")).longValue());
        assertEquals(targetType, json.get("targetType"));
        assertEquals(targetId.longValue(), ((Number) json.get("targetId")).longValue());
        assertEquals("SUBMITTED", json.get("stage"));
        assertEquals("RECEIVED", json.get("publicStage"));
        assertEquals(0, json.get("version"));
        assertEquals("举报说明", json.get("description"));
        for (String internal : Arrays.asList("reporterMemberId", "requestId", "payloadHash",
                "prioritySnapshot", "firstResponseMinutesSnapshot", "internalNote", "operatorId")) {
            assertEquals(false, json.containsKey(internal), internal);
        }
    }

    @Test
    void hiddenCommentCannotBeReportedByGuessingItsId() {
        Map<String, Object> comment = new HashMap<>();
        comment.put("id", 17L);
        comment.put("post_id", 7L);
        comment.put("author_id", 11L);
        comment.put("lifecycle", "CHECKING");
        when(mapper.selectCommentForAudit(17L)).thenReturn(comment);
        CommunityReportSubmitRequest request = new CommunityReportSubmitRequest();
        request.setTargetType("COMMENT");
        request.setTargetId(17L);
        request.setReasonCode("HARASSMENT");
        request.setRequestId("report-hidden-comment");

        ServiceException error = assertThrows(ServiceException.class, () -> service.report(9L, request));

        assertEquals(Integer.valueOf(404), error.getCode());
        verify(communityTrustService, never()).submitReport(anyLong(), any());
    }

    @Test
    void muteRequestIdCannotBeReusedForAnotherTarget() {
        Map<String, Object> replay = new HashMap<>();
        replay.put("muted_member_id", 15L);
        when(mapper.selectMuteByRequest(9L, "mute-request-1")).thenReturn(replay);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.mute(9L, 16L, "mute-request-1"));

        assertEquals(Integer.valueOf(409), error.getCode());
        verify(mapper, never()).insertMute(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void communityAudienceRequiresMembershipAndCommunityId() {
        CommunityPostRequests.Upsert missing = validUpsert();
        missing.setAudience("COMMUNITY");
        ServiceException missingError = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, missing));
        assertEquals(Integer.valueOf(400), missingError.getCode());

        CommunityPostRequests.Upsert outsider = validUpsert();
        outsider.setAudience("COMMUNITY");
        outsider.setCommunityId(88L);
        when(mapper.countCommunityMember(9L, 88L)).thenReturn(0);
        ServiceException outsiderError = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, outsider));
        assertEquals(Integer.valueOf(403), outsiderError.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void restrictedCommentPoliciesRequireTheirScope() {
        CommunityPostRequests.Upsert members = validUpsert();
        members.setCommentPolicy("MEMBERS");
        ServiceException missingCommunity = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, members));
        assertEquals(Integer.valueOf(400), missingCommunity.getCode());

        CommunityPostRequests.Upsert mentioned = validUpsert();
        mentioned.setCommentPolicy("MENTIONED");
        ServiceException missingMention = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, mentioned));
        assertEquals(Integer.valueOf(400), missingMention.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void publicLocationRequiresCityCode() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setLocationPrecision("CITY");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void noneLocationRejectsResidualPublicLocationFields() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setCityCode("SH");
        request.setPoiName("苏河湾");
        request.setApproximateGeohash("wtw3s");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void approximateLocationRequiresCoarseValidGrid() {
        CommunityPostRequests.Upsert request = validUpsert();
        request.setLocationPrecision("APPROXIMATE");
        request.setCityCode("SH");
        request.setApproximateGeohash("not-a-grid");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.createDraft(9L, request));

        assertEquals(Integer.valueOf(400), error.getCode());
        verify(mapper, never()).insertPost(any());
    }

    @Test
    void locationWithdrawalUsesVersionedErasureQueue() {
        CommunityPostRequests.State request = new CommunityPostRequests.State();
        request.setExpectedVersion(3);
        request.setRequestId("location-withdraw-1");
        when(mapper.withdrawPostLocation(eq(7L), eq(9L), eq(3), any())).thenReturn(1);
        when(mapper.insertErasureJob(eq(9L), eq(7L), eq("LOCATION_WITHDRAW"),
                eq("location-withdraw-1"), anyString(), any())).thenReturn(1);

        service.withdrawLocation(9L, 7L, request);

        verify(mapper).withdrawPostLocation(eq(7L), eq(9L), eq(3), any());
        verify(mapper).insertErasureJob(eq(9L), eq(7L), eq("LOCATION_WITHDRAW"),
                eq("location-withdraw-1"), anyString(), any());
    }

    @Test
    void erasureDoesNotDeleteObjectStillReferencedByAnotherLiveMediaRow() {
        Map<String, Object> job = new HashMap<>();
        job.put("id", 31L);
        job.put("post_id", 7L);
        job.put("trigger_type", "POST_DELETE");
        job.put("legal_hold", 0);
        Map<String, Object> media = new HashMap<>();
        media.put("id", 41L);
        media.put("object_key", "https://cdn.example/upload/shared.jpg");
        when(mapper.selectDueErasureJobs(any(), anyInt())).thenReturn(Collections.singletonList(job));
        when(mapper.claimErasureJob(eq(31L), any())).thenReturn(1);
        when(mapper.selectDueMediaForErasure(eq(7L), any())).thenReturn(Collections.singletonList(media));
        when(mapper.countLiveMediaObjectRefs("https://cdn.example/upload/shared.jpg", 41L)).thenReturn(1);

        service.runMaintenance(20);

        verify(mapper).deletePostNotificationActors(7L);
        verify(mapper).deletePostNotifications(7L);
        verify(mapper).eraseDueMedia(eq(7L), any());
        verify(mapper).completeErasureJob(eq(31L), any());
    }

    private CommunityPost draft(Long id, Long authorId, int version) {
        CommunityPost post = new CommunityPost();
        post.setId(id);
        post.setAuthorId(authorId);
        post.setBody("正文");
        post.setAudience("PUBLIC");
        post.setCommentPolicy("EVERYONE");
        post.setLocationPrecision("NONE");
        post.setLifecycle("DRAFT");
        post.setInteractionEnabled(1);
        post.setGuidelineVersionId(5L);
        post.setVersion(version);
        return post;
    }

    private CommunityPostRequests.State state(int version) {
        CommunityPostRequests.State state = new CommunityPostRequests.State();
        state.setExpectedVersion(version);
        state.setRequestId("publish-request-1");
        return state;
    }

    private CommunityPostRequests.Upsert validUpsert() {
        CommunityPostRequests.Upsert request = new CommunityPostRequests.Upsert();
        request.setClientRequestId("create-request-1");
        request.setPostType("MOMENT");
        request.setBody("正文");
        request.setAudience("PUBLIC");
        request.setCommentPolicy("EVERYONE");
        request.setLocationPrecision("NONE");
        request.setDisclosureType("NONE");
        request.setGuidelineVersionId(5L);
        return request;
    }
}

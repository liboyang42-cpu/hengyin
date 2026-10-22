package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.CommunityReportCase;
import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.dto.CommunityReportSubmitRequest;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O-05 #1:帖文 LIMITED(降分发)此前没有生产者。开关(chengyin.community.post-limit.enabled)打开后,
 * 分发类处置(DOWNRANK/SEARCH_EXCLUDE)命中即把公开帖落 LIMITED;读侧 feed/搜索不出、作者可见、受众内可直达。
 * 开关关闭时行为与旧版一致。
 */
class CommunityPostLimitProducerTest {
    private CommunityPostMapper mapper;
    private CommunityPostServiceImpl service;
    private CommunityTrustService trustService;

    @BeforeEach
    void setUp() {
        mapper = mock(CommunityPostMapper.class);
        trustService = mock(CommunityTrustService.class);
        service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(), mock(IContentSafetyGate.class),
                mock(IAuditTaskService.class), trustService, mock(ICommunityMediaReceiptService.class));
        stubMaintenance();
    }

    private void stubMaintenance() {
        when(mapper.insertOutbox(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertTypedOutbox(anyString(), anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.selectEnforcementBoundaryPostIds(any(), anyInt())).thenReturn(Collections.singletonList(7L));
        when(mapper.reconcilePostEnforcementCas(eq(7L), eq(3), anyString(), anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(1);
    }

    private CommunityPost post(String lifecycle) {
        CommunityPost post = new CommunityPost();
        post.setId(7L);
        post.setAuthorId(9L);
        post.setBody("正文");
        post.setAudience("PUBLIC");
        post.setCommentPolicy("EVERYONE");
        post.setLocationPrecision("NONE");
        post.setLifecycle(lifecycle);
        post.setInteractionEnabled(1);
        post.setVersion(3);
        return post;
    }

    private void enable() {
        ReflectionTestUtils.setField(service, "postLimitEnabled", true);
    }

    @Test
    void 开关关闭_降权处置不改生命周期() {
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("PUBLISHED"));
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any())).thenReturn(Collections.singletonList("DOWNRANK"));

        service.runMaintenance(20);

        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("PUBLISHED"), eq(0), eq(1), eq(1), any());
        verify(mapper).upsertSearchDocument(eq(7L), eq("VISIBLE"), any());
    }

    @Test
    void 开关打开_降权命中把公开帖落LIMITED并撤出搜索() {
        enable();
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("PUBLISHED"));
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any())).thenReturn(Collections.singletonList("DOWNRANK"));

        service.runMaintenance(20);

        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("LIMITED"), eq(0), eq(1), eq(1), any());
        verify(mapper).upsertSearchDocument(eq(7L), eq("HIDDEN"), any());
    }

    @Test
    void 开关打开_搜索排除同样落LIMITED_已LIMITED保持() {
        enable();
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("LIMITED"));
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any()))
                .thenReturn(Arrays.asList("SEARCH_EXCLUDE", "INTERACTION_LIMIT"));

        service.runMaintenance(20);

        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("LIMITED"), eq(1), eq(0), eq(0), any());
    }

    @Test
    void 开关打开_分发处置失效后LIMITED回到PUBLISHED() {
        enable();
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("LIMITED"));
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any())).thenReturn(Collections.<String>emptyList());

        service.runMaintenance(20);

        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("PUBLISHED"), eq(1), eq(1), eq(1), any());
    }

    @Test
    void 开关打开_互动限制不算分发处置() {
        enable();
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("PUBLISHED"));
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any()))
                .thenReturn(Collections.singletonList("INTERACTION_LIMIT"));

        service.runMaintenance(20);

        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("PUBLISHED"), eq(1), eq(1), eq(0), any());
    }

    @Test
    void 开关打开_下架优先于降分发() {
        enable();
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("PUBLISHED"));
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any())).thenReturn(Arrays.asList("DOWNRANK", "REMOVE"));

        service.runMaintenance(20);

        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("REMOVED"), eq(0), eq(0), eq(0), any());
    }

    @Test
    void 开关关闭_从未产生LIMITED的库_非作者读不到仍是404() {
        // 直达口只认 lifecycle='LIMITED'(见 H2 用例);从未打开过开关的库没有这种行,结果与旧版一致。
        when(mapper.selectById(7L, 11L)).thenReturn(null);
        when(mapper.selectLimitedById(7L, 11L)).thenReturn(null);

        ServiceException e = assertThrows(ServiceException.class, () -> service.detail(11L, 7L));

        assertEquals(Integer.valueOf(404), e.getCode());
    }

    @Test
    void S532_3_开关开后又关_已LIMITED帖对受众内读者仍可读() {
        when(mapper.selectById(7L, 11L)).thenReturn(null);
        when(mapper.selectLimitedById(7L, 11L)).thenReturn(post("LIMITED"));

        Map<String, Object> detail = service.detail(11L, 7L);

        assertEquals(Long.valueOf(7L), ((CommunityPost) detail.get("post")).getId());
    }

    private CommunityReportSubmitRequest contentReport() {
        CommunityReportSubmitRequest request = new CommunityReportSubmitRequest();
        request.setTargetId(7L);
        request.setReasonCode("HARASSMENT");
        request.setRequestId("report-limited");
        CommunityReportCase row = new CommunityReportCase();
        row.setId(23L);
        row.setReporterMemberId(11L);
        when(trustService.submitReport(11L, request)).thenReturn(row);
        return request;
    }

    @Test
    void S532_1_开关打开_能直达看到的LIMITED帖可以举报() {
        enable();
        when(mapper.selectById(7L, 11L)).thenReturn(null);
        when(mapper.selectLimitedById(7L, 11L)).thenReturn(post("LIMITED"));
        CommunityReportSubmitRequest request = contentReport();

        service.report(11L, request);

        assertEquals("CONTENT", request.getTargetType());
        verify(trustService).submitReport(11L, request);
    }

    @Test
    void S532_1_开关关闭_已LIMITED帖同样可以举报() {
        when(mapper.selectById(7L, 11L)).thenReturn(null);
        when(mapper.selectLimitedById(7L, 11L)).thenReturn(post("LIMITED"));
        CommunityReportSubmitRequest request = contentReport();

        service.report(11L, request);

        verify(trustService).submitReport(11L, request);
    }

    @Test
    void S532_1_直达口也读不到的帖子仍不可举报() {
        enable();
        when(mapper.selectById(7L, 11L)).thenReturn(null);
        when(mapper.selectLimitedById(7L, 11L)).thenReturn(null);
        CommunityReportSubmitRequest request = contentReport();

        ServiceException e = assertThrows(ServiceException.class, () -> service.report(11L, request));

        assertEquals(Integer.valueOf(404), e.getCode());
        verify(trustService, never()).submitReport(anyLong(), any());
    }

    @Test
    void S532_2_降权_隐藏_降权到期_隐藏到期后回到PUBLISHED() {
        enable();
        // 1. DOWNRANK 生效:PUBLISHED → LIMITED
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("PUBLISHED"));
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any())).thenReturn(Collections.singletonList("DOWNRANK"));
        service.runMaintenance(20);
        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("LIMITED"), eq(0), eq(1), eq(1), any());

        // 2. 再施加 HIDE_PENDING_REVIEW(previous_lifecycle 记为 LIMITED):LIMITED → HIDDEN
        reset(mapper);
        stubMaintenance();
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("LIMITED"));
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any()))
                .thenReturn(Arrays.asList("DOWNRANK", "HIDE_PENDING_REVIEW"));
        service.runMaintenance(20);
        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("HIDDEN"), eq(0), eq(0), eq(1), any());

        // 3. DOWNRANK 到期,HIDE 仍在:保持 HIDDEN
        reset(mapper);
        stubMaintenance();
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("HIDDEN"));
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any()))
                .thenReturn(Collections.singletonList("HIDE_PENDING_REVIEW"));
        service.runMaintenance(20);
        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("HIDDEN"), eq(0), eq(0), eq(1), any());

        // 4. HIDE 到期:恢复值是存下的 LIMITED,但已无分发处置 → 必须回到 PUBLISHED 并重新进搜索
        reset(mapper);
        stubMaintenance();
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("HIDDEN"));
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any())).thenReturn(Collections.<String>emptyList());
        when(mapper.selectEnforcementRestoreLifecycle(7L)).thenReturn("LIMITED");
        service.runMaintenance(20);
        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("PUBLISHED"), eq(1), eq(1), eq(1), any());
        verify(mapper).upsertSearchDocument(eq(7L), eq("VISIBLE"), any());
    }

    @Test
    void S532_2_隐藏到期时分发处置仍生效_恢复为LIMITED() {
        enable();
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("HIDDEN"));
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any())).thenReturn(Collections.singletonList("SEARCH_EXCLUDE"));
        when(mapper.selectEnforcementRestoreLifecycle(7L)).thenReturn("LIMITED");

        service.runMaintenance(20);

        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("LIMITED"), eq(1), eq(0), eq(1), any());
    }

    @Test
    void S532_2_开关已关_恢复值为LIMITED时回到PUBLISHED() {
        when(mapper.selectByIdForUpdate(7L)).thenReturn(post("REMOVED"));
        when(mapper.selectActivePostEnforcementTypes(eq(7L), any())).thenReturn(Collections.singletonList("DOWNRANK"));
        when(mapper.selectEnforcementRestoreLifecycle(7L)).thenReturn("LIMITED");

        service.runMaintenance(20);

        verify(mapper).reconcilePostEnforcementCas(eq(7L), eq(3), eq("PUBLISHED"), eq(0), eq(1), eq(1), any());
    }

    @Test
    void 开关打开_受众内读者可直达LIMITED帖() {
        enable();
        when(mapper.selectById(7L, 11L)).thenReturn(null);
        when(mapper.selectLimitedById(7L, 11L)).thenReturn(post("LIMITED"));

        Map<String, Object> detail = service.detail(11L, 7L);

        CommunityPost read = (CommunityPost) detail.get("post");
        assertEquals(Long.valueOf(7L), read.getId());
        assertEquals("LIMITED", read.getLifecycle());
    }

    @Test
    void 开关打开_游客不走直达读取() {
        enable();
        when(mapper.selectById(7L, 0L)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.detail(null, 7L));
        verify(mapper, never()).selectLimitedById(anyLong(), anyLong());
    }
}

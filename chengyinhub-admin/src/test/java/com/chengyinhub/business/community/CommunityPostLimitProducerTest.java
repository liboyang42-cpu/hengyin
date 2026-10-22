package com.chengyinhub.business.community;

import com.chengyinhub.business.domain.community.CommunityPost;
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

    @BeforeEach
    void setUp() {
        mapper = mock(CommunityPostMapper.class);
        service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(), mock(IContentSafetyGate.class),
                mock(IAuditTaskService.class), mock(CommunityTrustService.class), mock(ICommunityMediaReceiptService.class));
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
    void 开关关闭_非作者读不到LIMITED帖_不走直达读取() {
        when(mapper.selectById(7L, 11L)).thenReturn(null);

        ServiceException e = assertThrows(ServiceException.class, () -> service.detail(11L, 7L));

        assertEquals(Integer.valueOf(404), e.getCode());
        verify(mapper, never()).selectLimitedById(anyLong(), anyLong());
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

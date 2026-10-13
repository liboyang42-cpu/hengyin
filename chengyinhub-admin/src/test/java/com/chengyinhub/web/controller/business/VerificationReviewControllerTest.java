package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.VerificationReview;
import com.chengyinhub.business.mapper.VerificationReviewMapper;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批 5 · T3:处置端点的权限与对象归属。
 *
 * <p>三条各自能变红:
 * ① 列表逐行按主题范围过滤 —— 不传 topicId(最常用的「看全部待复核」)那条路也必须过滤;
 * ② 处置前用**行自己的 topic_id** 判范围,不能凭前端传值;
 * ③ 越权/重复处置不得写库,且错误可读。
 *
 * <p>{@code @PreAuthorize} 的权限串本身由 chengyinhub-ui 的
 * shop-day-node-and-risk-admin-contract 合同钉住(它读 Java 源码比对菜单迁移里的 perms),
 * 单测跑不到 Spring Security 那一层。
 */
class VerificationReviewControllerTest {

    private static final long MINE = 100L;
    private static final long OTHERS = 200L;

    private VerificationReviewMapper mapper;
    private ITopicOperationsScopeAuthorizer authorizer;
    private VerificationReviewController controller;

    @BeforeEach
    void setUp() {
        mapper = mock(VerificationReviewMapper.class);
        authorizer = mock(ITopicOperationsScopeAuthorizer.class);
        controller = new VerificationReviewController();
        ReflectionTestUtils.setField(controller, "reviewMapper", mapper);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", authorizer);
    }

    private VerificationReview row(long id, long topicId, int status) {
        VerificationReview r = new VerificationReview();
        r.setId(id);
        r.setTopicId(topicId);
        r.setStatus(status);
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void listFiltersRowsOutsideScopeEvenWithoutTopicFilter() {
        when(mapper.selectQueue(any())).thenReturn(Arrays.asList(row(1L, MINE, 0), row(2L, OTHERS, 0)));
        when(authorizer.canViewTopic(MINE)).thenReturn(true);
        when(authorizer.canViewTopic(OTHERS)).thenReturn(false);

        AjaxResult r = controller.list(0, null, null);

        List<VerificationReview> data = (List<VerificationReview>) r.get(AjaxResult.DATA_TAG);
        assertEquals(1, data.size(), "不传 topicId 那条路不过滤 = 范围闸对唯一真实使用方无效");
        assertEquals(1L, data.get(0).getId().longValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listWithForeignTopicFilterReturnsEmptyWithoutQuerying() {
        when(authorizer.canViewTopic(OTHERS)).thenReturn(false);

        AjaxResult r = controller.list(0, OTHERS, null);

        assertTrue(((List<VerificationReview>) r.get(AjaxResult.DATA_TAG)).isEmpty());
        verify(mapper, never()).selectQueue(any());
    }

    @Test
    void handleUsesRowsOwnTopicIdForScopeDecision() {
        when(mapper.selectById(1L)).thenReturn(row(1L, MINE, 0));
        when(authorizer.canEditTopic(MINE)).thenReturn(true);
        when(mapper.updateStatus(1L, MINE, 2)).thenReturn(1);

        AjaxResult r = controller.handle(1L, 2);

        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        // 写路径的 WHERE 必须带行自己的 topic_id,不是前端传的
        verify(mapper).updateStatus(eq(1L), eq(MINE), eq(2));
    }

    @Test
    void handleOutsideScopeWritesNothing() {
        when(mapper.selectById(2L)).thenReturn(row(2L, OTHERS, 0));
        when(authorizer.canEditTopic(OTHERS)).thenReturn(false);

        AjaxResult r = controller.handle(2L, 1);

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("工单不存在或当前账号无操作范围", r.get(AjaxResult.MSG_TAG));
        verify(mapper, never()).updateStatus(anyLong(), anyLong(), anyInt());
    }

    @Test
    void handleMissingRowIsRejectedWithoutLeakingExistence() {
        when(mapper.selectById(9L)).thenReturn(null);
        AjaxResult r = controller.handle(9L, 1);
        assertEquals("工单不存在或当前账号无操作范围", r.get(AjaxResult.MSG_TAG));
        verify(mapper, never()).updateStatus(anyLong(), anyLong(), anyInt());
    }

    /** CAS 落空(已被别人处置)必须变成可读错误,不能报成功。 */
    @Test
    void repeatedHandleReportsConflict() {
        when(mapper.selectById(1L)).thenReturn(row(1L, MINE, 0));
        when(authorizer.canEditTopic(MINE)).thenReturn(true);
        when(mapper.updateStatus(1L, MINE, 1)).thenReturn(0);

        AjaxResult r = controller.handle(1L, 1);

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("该工单已被处置或状态已变化", r.get(AjaxResult.MSG_TAG));
    }

    /** 只收 1/2:收 0 等于把处置过的退回待复核,那是撤销不是处置。 */
    @Test
    void onlyConclusiveStatusesAccepted() {
        for (Integer bad : new Integer[]{null, 0, 3, -1}) {
            AjaxResult r = controller.handle(1L, bad);
            assertEquals(500, r.get(AjaxResult.CODE_TAG), "status=" + bad + " 必须被拒");
        }
        verify(mapper, never()).selectById(anyLong());
        verify(mapper, never()).updateStatus(anyLong(), anyLong(), anyInt());
    }

    @Test
    void badIdRejected() {
        assertEquals(500, controller.handle(null, 1).get(AjaxResult.CODE_TAG));
        assertEquals(500, controller.handle(0L, 1).get(AjaxResult.CODE_TAG));
        verify(mapper, never()).selectById(anyLong());
    }
}

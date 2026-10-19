package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicLike;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.Collections;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiTopicOwnLikeStateTest {
    private static final class Fixture {
        final ApiTopicController controller = spy(new ApiTopicController());
        final ICmsTopicLikeService service = mock(ICmsTopicLikeService.class);
        Fixture(Long principal) {
            doReturn(principal).when(controller).getAppUserId();
            ReflectionTestUtils.setField(controller, "cmsTopicLikeService", service);
        }
    }
    private void refused(AjaxResult result, int code) {
        assertEquals(code, result.get("code"));
        assertFalse(result.containsKey("data"));
    }
    @Test void missingZeroNegativePrincipalCannotReadOrDelete() {
        for (Long id : new Long[] {null, 0L, -7L}) {
            Fixture f = new Fixture(id);
            refused(f.controller.ownTopicLikeState("41"), 401);
            refused(f.controller.unlikeOwnTopic("41"), 401);
            verifyNoInteractions(f.service);
        }
    }
    @Test void unavailablePrincipalDoesNotFallBackToClientOrGuest() {
        Fixture f = new Fixture(7L);
        doThrow(new SecurityException("no identity")).when(f.controller).getAppUserId();
        refused(f.controller.ownTopicLikeState("41"), 401);
        refused(f.controller.unlikeOwnTopic("41"), 401);
        verifyNoInteractions(f.service);
    }
    @Test void malformedNonCanonicalOrOverflowTopicCannotReachService() {
        for (String id : new String[] {null,"","0","-1","+1","01"," 1","1 ","1.0","1e3","１","9223372036854775808","99999999999999999999"}) {
            Fixture f = new Fixture(7L);
            refused(f.controller.ownTopicLikeState(id), 400);
            refused(f.controller.unlikeOwnTopic(id), 400);
            verifyNoInteractions(f.service);
        }
    }
    @Test void positiveLongBoundaryAndExactCurrentOwnerAreReturned() {
        Fixture f = new Fixture(7L);
        when(f.service.readOwnTopicLikeState(7L, Long.MAX_VALUE)).thenReturn(true);
        AjaxResult result = f.controller.ownTopicLikeState(Long.toString(Long.MAX_VALUE));
        assertEquals(200, result.get("code"));
        Map<?, ?> state = (Map<?, ?>) result.get("data");
        assertEquals(7L, state.get("memberId"));
        assertEquals(Long.MAX_VALUE, state.get("topicId"));
        assertEquals(Boolean.TRUE, state.get("isLiked"));
        assertEquals(3, state.size());
    }
    @Test void forgedClientMemberIdDoesNotSelectAnotherAccount() {
        Fixture f = new Fixture(7L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("memberId", "8"); request.addParameter("member_id", "8");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            f.controller.ownTopicLikeState("41"); f.controller.unlikeOwnTopic("41");
            verify(f.service).readOwnTopicLikeState(7L,41L);
            verify(f.service).cancelOwnTopicLike(7L,41L);
            verifyNoMoreInteractions(f.service);
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
    @Test void unlikeReturnsTrueWhenAuthoritativeReadObservedConcurrentReAdd() {
        Fixture f = new Fixture(7L);
        when(f.service.cancelOwnTopicLike(7L,41L)).thenReturn(true);
        Map<?, ?> state = (Map<?, ?>) f.controller.unlikeOwnTopic("41").get("data");
        assertEquals(Boolean.TRUE, state.get("isLiked"));
    }
    @Test void falseIsAnExplicitBooleanFromServiceNotMessageGuessing() {
        Fixture f = new Fixture(7L);
        when(f.service.cancelOwnTopicLike(7L,41L)).thenReturn(false);
        Map<?, ?> state = (Map<?, ?>) f.controller.unlikeOwnTopic("41").get("data");
        assertEquals(Boolean.FALSE, state.get("isLiked"));
        assertTrue(state.get("isLiked") instanceof Boolean);
    }
    @Test void inaccessibleAndMissingTopicsExposeNeitherStateNorPrivateMessage() {
        Fixture f = new Fixture(7L);
        when(f.service.readOwnTopicLikeState(7L,41L)).thenThrow(new ServiceException("private topic title"));
        when(f.service.cancelOwnTopicLike(7L,41L)).thenThrow(new ServiceException("topic does not exist"));
        AjaxResult a=f.controller.ownTopicLikeState("41"), b=f.controller.unlikeOwnTopic("41");
        refused(a,404); refused(b,404); assertEquals(a.get("msg"),b.get("msg"));
        assertEquals("主题不可访问",a.get("msg"));
    }
    @Test void newMappingsHaveOnlyIdAndCannotAliasLegacyToggle() throws Exception {
        assertArrayEquals(new String[]{"/like-state"},ApiTopicController.class.getMethod("ownTopicLikeState",String.class).getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/unlike"},ApiTopicController.class.getMethod("unlikeOwnTopic",String.class).getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/like"},ApiTopicController.class.getMethod("topicLike",String.class,String.class).getAnnotation(PostMapping.class).value());
    }
    @Test void unchangedLegacyToggleStillInsertsWithTypeZeroWhenNoRecordExists() {
        Fixture f = new Fixture(7L);
        when(f.service.selectCmsTopicLikeList(any(CmsTopicLike.class))).thenReturn(Collections.emptyList());
        when(f.service.insertCmsTopicLike(any(CmsTopicLike.class))).thenReturn(1);
        assertEquals(200,f.controller.topicLike("41","0").get("code"));
        ArgumentCaptor<CmsTopicLike> row=ArgumentCaptor.forClass(CmsTopicLike.class);
        verify(f.service).insertCmsTopicLike(row.capture());
        assertEquals(7L,row.getValue().getMemberId()); assertEquals(41L,row.getValue().getDataId());
        assertEquals(Integer.valueOf(0),row.getValue().getLikeType());
        verify(f.service,never()).cancelOwnTopicLike(anyLong(),anyLong());
    }
}

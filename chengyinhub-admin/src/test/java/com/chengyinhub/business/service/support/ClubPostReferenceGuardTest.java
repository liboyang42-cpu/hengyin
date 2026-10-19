package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F0098:伪造他人通关 ID、引用私密/已删对象一律拒;合法引用照常通过。 */
class ClubPostReferenceGuardTest {

    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CmsActivityMapper activities = mock(CmsActivityMapper.class);
    private final CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
    private final ClubPostReferenceGuard guard = new ClubPostReferenceGuard();

    ClubPostReferenceGuardTest() {
        ReflectionTestUtils.setField(guard, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(guard, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(guard, "cmsRegistrationMapper", registrations);
        when(topics.selectCmsTopicDetailById(10L, null)).thenReturn(new CmsTopic());
        CmsActivity activity = new CmsActivity();
        activity.setTopicId(10L);
        when(activities.selectPublicCmsActivityById(20L)).thenReturn(activity);
    }

    private int code(Runnable action) {
        ServiceException e = assertThrows(ServiceException.class, action::run);
        return e.getCode();
    }

    @Test
    void ownSucceededRegistrationOnPublicTopicIsCitable() {
        when(registrations.countSucceededJoin(7L, 1L, 10L)).thenReturn(1);
        guard.requireCitable(7L, 2, 10L);
        when(registrations.countSucceededJoin(7L, 2L, 20L)).thenReturn(1);
        guard.requireCitable(7L, 1, 20L);
    }

    @Test
    void forgedSomeoneElsesRecordIsRejected() {
        when(registrations.countSucceededJoin(7L, 2L, 20L)).thenReturn(0);
        assertEquals(403, code(() -> guard.requireCitable(7L, 1, 20L)));
    }

    @Test
    void privateDeletedOrUnknownObjectIsRejectedWithoutConfirmingExistence() {
        // 仅成员可见/下架/已删:匿名口径详情查不到;报名记录再真也不能公开引用
        when(registrations.countSucceededJoin(anyLong(), anyLong(), anyLong())).thenReturn(1);
        assertEquals(404, code(() -> guard.requireCitable(7L, 2, 11L)));
        assertEquals(404, code(() -> guard.requireCitable(7L, 1, 21L)));
        verify(topics).selectCmsTopicDetailById(11L, null);
    }

    @Test
    void activityUnderPrivateParentTopicIsNotPubliclyVisible() {
        CmsActivity underPrivate = new CmsActivity();
        underPrivate.setTopicId(12L);
        when(activities.selectPublicCmsActivityById(22L)).thenReturn(underPrivate);
        when(topics.selectCmsTopicDetailById(12L, null)).thenReturn(null);
        assertFalse(guard.isPubliclyVisible(1, 22L));
        assertTrue(guard.isPubliclyVisible(1, 20L));
    }

    @Test
    void malformedOrUnsupportedReferenceIsBadRequest() {
        assertEquals(400, code(() -> guard.requireCitable(7L, 1, null)));
        assertEquals(400, code(() -> guard.requireCitable(7L, 3, 10L)));
        verify(registrations, never()).countSucceededJoin(anyLong(), anyLong(), anyLong());
    }

    @Test
    void noReferenceIsAlwaysFine() {
        guard.requireCitable(7L, null, null);
        verify(topics, never()).selectCmsTopicDetailById(anyLong(), isNull());
    }
}

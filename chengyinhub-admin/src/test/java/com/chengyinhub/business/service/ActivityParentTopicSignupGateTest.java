package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AUDIT-G1-1:父主题被驳回 / 作者下架 / 违规下架后,其场次不再接新单。
 *
 * <p>自办壳由 publishBeforeReview 以 status=1/publish_status=1 建出,主题后续下架不回写场次;
 * 报价、建单、重试付款都经过 validateAndGetActivity,所以闸放在这里一处。
 */
class ActivityParentTopicSignupGateTest {

    private static final Long TOPIC = 700L;
    private static final Long ACTIVITY = 800L;

    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;
    private CmsRegistrationServiceImpl service;
    private CmsActivity activity;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        activity.setEndDate(new Date(System.currentTimeMillis() + 86400_000L));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
    }

    private static CmsTopic topic(Integer status, Integer userStatus, Integer delFlag) {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setStatus(status);
        t.setUserStatus(userStatus);
        t.setDelFlag(delFlag);
        return t;
    }

    private CmsActivity validate() {
        return ReflectionTestUtils.invokeMethod(service, "validateAndGetActivity", ACTIVITY, 9L);
    }

    @Test
    void rejectedAuthorDownPendingDeletedOrMissingParentTopic_refusesNewSignup() {
        CmsTopic[] dead = {
                topic(2, 1, 0),   // 驳回 / 违规下架
                topic(1, 0, 0),   // 作者下架
                topic(0, 1, 0),   // 待审
                topic(1, 1, 2),   // 删除
                null              // 主题行缺失
        };
        for (CmsTopic t : dead) {
            when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
            RuntimeException e = assertThrows(RuntimeException.class, this::validate,
                    "父主题不在架时场次必须拒单:" + (t == null ? "null" : t.getStatus() + "/" + t.getUserStatus() + "/" + t.getDelFlag()));
            assertEquals("活动所属主题已下架", e.getMessage());
        }
    }

    @Test
    void liveParentTopicOrStandaloneActivity_passes() {
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(1, 1, 0));
        assertSame(activity, validate());

        activity.setTopicId(null);
        assertSame(activity, validate(), "独立活动没有父主题,不受此闸影响");
    }
}

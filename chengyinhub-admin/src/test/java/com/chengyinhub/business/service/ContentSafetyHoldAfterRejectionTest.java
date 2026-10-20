package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.impl.WxContentSecurityServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-INT-7(#383/#381 × #384):已被人工驳回的主题/活动再收到图片违规回调,此前不留任何内容安全标记
 * (#383 为保留人工驳回原因有意不写 check_status/reason;活动下架只改 publish_status,已驳回即 no-op)。
 * #384 让驳回可申诉/可撤销后,违规图会随申诉成立重新公开。现在回调一律落 content_safety_hold=1,
 * 原驳回原因不动;申诉/撤销据此拒绝恢复(见 AuditAppealRevertContentTest)。
 */
class ContentSafetyHoldAfterRejectionTest {

    private static final Long ID = 880L;

    private final WxContentSecurityServiceImpl service = new WxContentSecurityServiceImpl();

    private boolean riskyCallback(int bizType) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, "processMappedMediaCheckResult",
                bizType + ":" + ID, "trace-1", true));
    }

    @Test
    void humanRejectedTopicStillRecordsTheHoldAndKeepsItsReason() {
        ICmsTopicService topics = mock(ICmsTopicService.class);
        ReflectionTestUtils.setField(service, "cmsTopicService", topics);
        CmsTopic rejected = new CmsTopic();
        rejected.setId(ID);
        rejected.setStatus(2);
        rejected.setUserStatus(0);
        rejected.setCheckStatus(2);
        rejected.setReason("人工驳回原因");
        when(topics.selectCmsTopicById(ID)).thenReturn(rejected);

        assertTrue(riskyCallback(IWxContentSecurityService.BIZ_TYPE_TOPIC));

        verify(topics).markContentSafetyHold(ID);
        verify(topics, never()).updateCmsTopic(any());
    }

    @Test
    void alreadyUnpublishedRejectedActivityStillRecordsTheHold() {
        ICmsActivityService activities = mock(ICmsActivityService.class);
        ReflectionTestUtils.setField(service, "cmsActivityService", activities);
        CmsActivity rejected = new CmsActivity();
        rejected.setId(ID);
        rejected.setStatus(2L);
        rejected.setPublishStatus(0L);
        when(activities.selectCmsActivityById(ID)).thenReturn(rejected);

        assertTrue(riskyCallback(IWxContentSecurityService.BIZ_TYPE_ACTIVITY));

        verify(activities).markContentSafetyHold(ID);
        verify(activities, never()).updateCmsActivity(any());
    }

    @Test
    void liveActivityTakedownAlsoRecordsTheHold() {
        ICmsActivityService activities = mock(ICmsActivityService.class);
        ReflectionTestUtils.setField(service, "cmsActivityService", activities);
        CmsActivity live = new CmsActivity();
        live.setId(ID);
        live.setStatus(1L);
        live.setPublishStatus(1L);
        when(activities.selectCmsActivityById(ID)).thenReturn(live);
        when(activities.updateCmsActivity(any())).thenReturn(1);

        assertTrue(riskyCallback(IWxContentSecurityService.BIZ_TYPE_ACTIVITY));

        verify(activities).markContentSafetyHold(ID);
    }
}

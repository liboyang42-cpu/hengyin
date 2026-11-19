package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.ActivityCancelManualRefundCase;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FE-G6:订单详情权益行的状态与行名只改展示,不改权益状态本身。 */
class RegistrationDetailEntitlementDisplayTest {

    private static RegistrationEntitlement ent(long chapterId, int status) {
        RegistrationEntitlement e = new RegistrationEntitlement();
        e.setChapterId(chapterId);
        e.setStatus(status);
        return e;
    }

    private static CmsRegistration registration(Date activityEnd, String caseStatus, RegistrationEntitlement... rows) {
        CmsRegistration r = new CmsRegistration();
        CmsActivity activity = new CmsActivity();
        activity.setEndDate(activityEnd);
        r.setCmsActivity(activity);
        r.setManualRefundCaseStatus(caseStatus);
        r.setEntitlements(new ArrayList<>(List.of(rows)));
        return r;
    }

    @Test
    void pendingRowAfterSessionEndReadsExpiredAndRedeemedRowsKeepTheirLabel() {
        Date now = new Date();
        CmsRegistration r = registration(new Date(now.getTime() - 1000), null, ent(1L, 0), ent(2L, 1), ent(3L, 2));
        RegistrationDetailDisplay.enrich(r, null, null, now);
        assertEquals(List.of("已过期", "已核销", "已失效"),
                r.getEntitlements().stream().map(RegistrationEntitlement::getStatusLabel).toList());
        assertEquals(Integer.valueOf(0), r.getEntitlements().get(0).getStatus(), "只改展示不改状态");
    }

    @Test
    void openManualRefundCaseReadsAfterSaleAndClosedCaseDoesNot() {
        Date now = new Date();
        Date future = new Date(now.getTime() + 86_400_000L);
        CmsRegistration open = registration(future, ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT, ent(1L, 0));
        RegistrationDetailDisplay.enrich(open, null, null, now);
        assertEquals("售后处理中", open.getEntitlements().get(0).getStatusLabel());
        CmsRegistration closed = registration(future, ActivityCancelManualRefundCase.STATUS_NO_REFUND, ent(1L, 0));
        RegistrationDetailDisplay.enrich(closed, null, null, now);
        assertEquals("待核销", closed.getEntitlements().get(0).getStatusLabel());
    }

    @Test
    void rowNameUsesStoreAndPerkAndFallsBackToChapterName() {
        ICmsTopicChapterService chapters = mock(ICmsTopicChapterService.class);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setName("第二站");
        when(chapters.selectCmsTopicChapterById(2L)).thenReturn(chapter);
        Date now = new Date();
        CmsRegistration r = registration(new Date(now.getTime() + 86_400_000L), null, ent(1L, 0), ent(2L, 0), ent(3L, 0));
        RegistrationDetailDisplay.enrich(r, chapters, Map.of(1L, "转角咖啡 · 美式一杯"), now);
        assertEquals(List.of("转角咖啡 · 美式一杯", "第二站", "章节 3"),
                r.getEntitlements().stream().map(RegistrationEntitlement::getChapterName).toList());
        assertEquals("待核销", r.getEntitlements().get(0).getStatusLabel());
    }
}

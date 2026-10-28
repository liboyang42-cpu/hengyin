package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.MerchantTodoRegistrationVO;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** W2: 请求规模变化不得恢复逐topic/scan读；不改变待办统计及访问主体。 */
class ApiMerchantTodoBatchTest {
    private static final long PRINCIPAL = 71L, OWNER = 9001L, MERCHANT_ROW = 11L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static class Fixture {
        final ApiMerchantController controller = spy(new ApiMerchantController());
        final IMerchantAccessService access = mock(IMerchantAccessService.class);
        final ICmsRegistrationMerchantService offers = mock(ICmsRegistrationMerchantService.class);
        final ICmsRegistrationScanService scans = mock(ICmsRegistrationScanService.class);
        final ICmsActivityService activities = mock(ICmsActivityService.class);
        final ICmsTopicService topics = mock(ICmsTopicService.class);
        final ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        final List<CmsRegistrationMerchant> won = new ArrayList<>();
        final List<CmsRegistrationScan> pending = new ArrayList<>();
        final List<CmsActivity> activityRows = new ArrayList<>();
        final Map<Long, Date> ends = new HashMap<>();
        final Map<Long, long[]> counts = new HashMap<>();
        final Map<Long, MerchantTodoRegistrationVO> owners = new HashMap<>();

        Fixture(String role) {
            doReturn(PRINCIPAL).when(controller).getAppUserId();
            when(access.require(PRINCIPAL, MerchantPermission.BASIC_READ))
                    .thenReturn(new MerchantAccessContext(PRINCIPAL, MERCHANT_ROW, OWNER, role));
            ReflectionTestUtils.setField(controller, "merchantAccessService", access);
            ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", offers);
            ReflectionTestUtils.setField(controller, "cmsRegistrationScanService", scans);
            ReflectionTestUtils.setField(controller, "cmsActivityService", activities);
            ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
            ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrations);
            when(offers.selectCmsRegistrationMerchantList(any())).thenReturn(won);
            when(scans.selectCmsRegistrationScanList(any())).thenReturn(pending);
            when(activities.selectCmsActivityList(any())).thenReturn(activityRows);
            when(topics.selectTodoTopicEndDates(any())).thenReturn(ends);
            when(registrations.countPaidVerificationByOwners(eq(1L), any())).thenReturn(counts);
            when(registrations.countPaidVerificationByOwners(eq(2L), any())).thenReturn(Collections.emptyMap());
            when(registrations.selectTodoRegistrationOwners(any())).thenReturn(owners);
        }
        void won(long id) {
            CmsRegistrationMerchant row = new CmsRegistrationMerchant(); row.setTopicId(id); won.add(row);
        }
        void scan(Long id) {
            CmsRegistrationScan row = new CmsRegistrationScan(); row.setRegistrationId(id); pending.add(row);
        }
        void owner(long id, Long type, Long project, Date end) {
            MerchantTodoRegistrationVO row = new MerchantTodoRegistrationVO();
            row.setId(id); row.setOwnerType(type); row.setOwnerId(project); row.setEndDate(end); owners.put(id, row);
        }
        Map<?, ?> read() {
            AjaxResult result = controller.todoSummary(); assertEquals(200, result.get(AjaxResult.CODE_TAG));
            return (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        }
        void noSingleReads() {
            verify(topics, never()).selectCmsTopicById(any());
            verify(registrations, never()).selectCmsRegistrationById(any());
            verify(registrations, never()).selectCmsRegistrationList(any());
            verify(activities, never()).selectCmsActivityById(any());
        }
    }

    @Test
    void batchesStayConstantAtOneFiftyAndFiveHundredTopicsAndScans() {
        for (int size : new int[] {1, 50, 500}) {
            Fixture f = new Fixture(MerchantRoleCode.MERCHANT_CHECKIN);
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            for (long id = 1; id <= size; id++) {
                ids.add(id); f.won(id); f.won(id); // 同一主题多章，bid行数保留，统计去重。
                f.counts.put(id, new long[] {2, 3}); f.ends.put(id, null);
                f.scan(id); f.owner(id, 1L, id, null);
            }
            f.scan(1L); // 同一报名的第二条扫码仍计数，但查询ID去重。
            Map<?, ?> data = f.read();
            assertEquals(2 * size, data.get("biddingTopics"));
            assertEquals(2 * size, data.get("pendingVerify"));
            assertEquals(3 * size, data.get("verifiedCount"));
            assertEquals(size + 1, data.get("pendingScanConfirm"));
            assertEquals(size, ((List<?>) data.get("byProject")).size());
            assertEquals(2, ((Map<?, ?>) ((List<?>) data.get("byProject")).get(0)).get("pendingScanConfirm"));
            verify(f.topics, times(1)).selectTodoTopicEndDates(ids);
            verify(f.registrations, times(1)).selectTodoRegistrationOwners(ids);
            verify(f.registrations, times(1)).countPaidVerificationByOwners(1L, ids);
            verify(f.registrations, never()).countPaidVerificationByOwners(eq(2L), any());
            verify(f.offers).selectCmsRegistrationMerchantList(argThat(p -> p.getMemberId().equals(OWNER)
                    && p.getAuditStatus() == 1L && p.getDelFlag() == 0));
            verify(f.scans).selectCmsRegistrationScanList(argThat(p -> p.getMemberId().equals(OWNER) && p.getStatus() == 0L));
            f.noSingleReads();
        }
    }

    @Test
    void endedProjectsKeepHistoryAndUncardedScansStillCountGlobally() {
        Fixture f = new Fixture(MerchantRoleCode.MERCHANT_CHECKIN);
        Date old = new Date(System.currentTimeMillis() - 8 * DAY), recent = new Date(System.currentTimeMillis() - 6 * DAY);
        f.won(1); f.won(2); f.ends.put(1L, old); f.ends.put(2L, recent);
        f.counts.put(1L, new long[] {7, 11}); f.counts.put(2L, new long[] {2, 3});
        CmsActivity activity = new CmsActivity(); activity.setId(2L); activity.setEndDate(recent); f.activityRows.add(activity);
        CmsActivity endedActivity = new CmsActivity(); endedActivity.setId(3L); endedActivity.setEndDate(old); f.activityRows.add(endedActivity);
        when(f.registrations.countPaidVerificationByOwners(eq(2L), any()))
                .thenReturn(Map.of(2L, new long[] {4, 5}, 3L, new long[] {8, 6}));
        f.owner(1, 1L, 1L, old); f.owner(2, 1L, 2L, recent);
        f.owner(3, 2L, 2L, recent); // 同id主题/活动是不同卡。
        f.owner(4, 1L, 99L, null); // 本人扫码但没有卡。
        f.owner(5, 2L, 3L, old); f.owner(6, null, 99L, null); f.owner(7, 1L, null, null);
        f.owner(8, 3L, 99L, null); // 旧ownerEndDate对未知类型返回null，仍计全局。
        for (long id = 1; id <= 9; id++) f.scan(id); // 9=报名不存在。
        f.scan(null);
        Map<?, ?> data = f.read();
        assertEquals(6, data.get("pendingVerify")); assertEquals(25, data.get("verifiedCount"));
        assertEquals(4, data.get("pendingScanConfirm"));
        List<?> cards = (List<?>) data.get("byProject"); assertEquals(2, cards.size());
        assertEquals(1, ((Map<?, ?>) cards.get(0)).get("pendingScanConfirm"));
        assertEquals(1, ((Map<?, ?>) cards.get(1)).get("pendingScanConfirm"));
        assertEquals(Set.of("biddingTopics", "pendingVerify", "verifiedCount", "pendingScanConfirm",
                "pendingOrders", "refundCount", "pendingPredict", "byProject"), data.keySet());
        f.noSingleReads();
    }

    @Test
    void emptyInputsAndMissingVerifyPermissionNeverReadBatchOrSingleRows() {
        for (String role : new String[] {MerchantRoleCode.MERCHANT_CHECKIN, MerchantRoleCode.MERCHANT_MARKETING}) {
            Fixture f = new Fixture(role); f.scan(null);
            Map<?, ?> data = f.read(); assertEquals(0, data.get("pendingScanConfirm"));
            verifyNoInteractions(f.topics, f.registrations);
            if (role.equals(MerchantRoleCode.MERCHANT_MARKETING)) verifyNoInteractions(f.offers, f.scans, f.activities);
        }
    }

    @Test
    void accessRejectionRunsNoReads() {
        Fixture f = new Fixture(MerchantRoleCode.MERCHANT_CHECKIN);
        when(f.access.require(PRINCIPAL, MerchantPermission.BASIC_READ)).thenThrow(new IllegalStateException("denied"));
        assertThrows(IllegalStateException.class, f.controller::todoSummary);
        verifyNoInteractions(f.topics, f.registrations, f.offers, f.scans, f.activities);
    }

    @Test
    void batchServicesShortCircuitEmptyAndDelegateNonemptyOnce() {
        CmsTopicMapper topics = mock(CmsTopicMapper.class); CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        CmsTopicServiceImpl topicService = new CmsTopicServiceImpl(); CmsRegistrationServiceImpl registrationService = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(topicService, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(registrationService, "cmsRegistrationMapper", registrations);
        assertTrue(topicService.selectTodoTopicEndDates(null).isEmpty());
        assertTrue(topicService.selectTodoTopicEndDates(Collections.emptySet()).isEmpty());
        assertTrue(registrationService.selectTodoRegistrationOwners(null).isEmpty());
        assertTrue(registrationService.selectTodoRegistrationOwners(Collections.emptySet()).isEmpty());
        verifyNoInteractions(topics, registrations);
        CmsTopic topic = new CmsTopic(); topic.setId(1L); // null日期必须保留，不能用不支持null值的toMap。
        MerchantTodoRegistrationVO reg = new MerchantTodoRegistrationVO(); reg.setId(2L); reg.setOwnerType(1L); reg.setOwnerId(1L);
        when(topics.selectTodoTopicEndDates(Set.of(1L))).thenReturn(List.of(topic));
        when(registrations.selectTodoRegistrationOwners(Set.of(2L))).thenReturn(List.of(reg));
        assertTrue(topicService.selectTodoTopicEndDates(Set.of(1L)).containsKey(1L));
        assertSame(reg, registrationService.selectTodoRegistrationOwners(Set.of(2L)).get(2L));
        verify(topics, times(1)).selectTodoTopicEndDates(Set.of(1L));
        verify(registrations, times(1)).selectTodoRegistrationOwners(Set.of(2L));
    }

    @Test
    void exactWeekBoundaryKeepsExistingStrictComparison() {
        Date now = new Date(1_800_000_000_000L);
        assertFalse(ApiMerchantController.endedOverWeek(new Date(now.getTime() - 7 * DAY), now));
        assertTrue(ApiMerchantController.endedOverWeek(new Date(now.getTime() - 7 * DAY - 1), now));
    }
}

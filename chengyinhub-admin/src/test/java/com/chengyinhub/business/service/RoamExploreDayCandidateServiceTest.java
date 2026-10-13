package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.RoamExploreDayCandidate;
import com.chengyinhub.business.domain.vo.RoamExploreDayCandidateVO;
import com.chengyinhub.business.mapper.RoamExploreDayCandidateMapper;
import com.chengyinhub.business.service.impl.RoamExploreDayCandidateServiceImpl;
import com.chengyinhub.business.service.support.MerchantPublishedTopicTransferGate;
import com.chengyinhub.business.service.support.CommercialReviewStatusSource;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RoamExploreDayCandidateServiceTest {

    private static final long GOOD_ACTIVITY = 9001L;
    private static final long BAD_ACTIVITY = 9002L;

    @Test
    void 只返回同时满足产品_owner_两审_承接_开关_距离_可售的最近活动壳() {
        assertVariantRejected(c -> c.setProductType(1));
        assertVariantRejected(c -> c.setMerchantMemberId(777L));
        assertVariantRejected(c -> c.setTicketOwnerType(1L));
        assertVariantRejected(c -> c.setTicketOwnerId(777L));
        assertVariantRejected(c -> c.setTopicLifecycle(0));
        assertVariantRejected(c -> c.setProjectReviewStatus(0));
        assertVariantRejected(c -> c.setSaleReviewStatus(0));
        assertVariantRejected(c -> c.setRoamReferralEnabled(0));
        assertVariantRejected(c -> c.setGatherLat(new BigDecimal("31.250000")));
        assertVariantRejected(c -> c.setActivityStatus(0L));
        assertVariantRejected(c -> c.setActivityPublishStatus(0L));
        assertVariantRejected(c -> c.setRemainingInventory(0L));
    }

    @Test
    void null或未知状态一律拒绝且不能把脏候选当成默认通过() {
        assertVariantRejected(c -> c.setProductType(null));
        assertVariantRejected(c -> c.setMerchantStatus(null));
        assertVariantRejected(c -> c.setTopicLifecycle(null));
        assertVariantRejected(c -> c.setTopicLifecycle(99));
        assertVariantRejected(c -> c.setProjectReviewStatus(null));
        assertVariantRejected(c -> c.setProjectReviewStatus(99));
        assertVariantRejected(c -> c.setProjectReviewSnapshotJson(null));
        assertVariantRejected(c -> c.setProjectReviewSnapshotJson("{\"requestedTarget\":\"UNKNOWN\"}"));
        assertVariantRejected(c -> c.setSaleReviewStatus(null));
        assertVariantRejected(c -> c.setSaleReviewStatus(99));
        assertVariantRejected(c -> c.setRoamReferralEnabled(null));
        assertVariantRejected(c -> c.setActivityStatus(null));
        assertVariantRejected(c -> c.setActivityPublishStatus(null));
        assertVariantRejected(c -> c.setTicketOwnerType(null));
        assertVariantRejected(c -> c.setGatherLat(null));
        assertVariantRejected(c -> c.setMerchantStatus(0L));
        assertVariantRejected(c -> c.setMerchantDelFlag(1));
        assertVariantRejected(c -> c.setTopicDelFlag(1));
        assertVariantRejected(c -> c.setActivityDelFlag(1));
        assertVariantRejected(c -> c.setTicketDelFlag(1));
        assertVariantRejected(c -> c.setActivityCancelTime(new Date()));
        assertVariantRejected(c -> c.setSellingStartedAt(new Date(System.currentTimeMillis() + 3_600_000)));
    }

    @Test
    void 商家主题没有完整club承接时拒绝但不影响全合格候选() {
        RoamExploreDayCandidateMapper mapper = mapper(good(), good(BAD_ACTIVITY, 31.230500));
        MerchantPublishedTopicTransferGate transferGate = mock(MerchantPublishedTopicTransferGate.class);
        doThrow(new IllegalStateException("未 handoff"))
                .when(transferGate).assertTransferred(argThat((CmsTopic t) -> t != null && BAD_ACTIVITY == t.getId()));

        RoamExploreDayCandidateVO result = service(mapper, transferGate, mock(CommercialReviewStatusSource.class))
                .findNearestEligible(31.230416, 121.473701);

        assertEquals(GOOD_ACTIVITY, result.getActivityId());
    }

    @Test
    void 必须复用商业审核真源重验快照而不是只信approved状态码() {
        CommercialReviewStatusSource reviewSource = mock(CommercialReviewStatusSource.class);
        doThrow(new IllegalStateException("开售快照已漂移"))
                .when(reviewSource).assertSaleApproved(BAD_ACTIVITY);

        RoamExploreDayCandidateVO result = service(
                mapper(good(), good(BAD_ACTIVITY, 31.230500)),
                mock(MerchantPublishedTopicTransferGate.class), reviewSource)
                .findNearestEligible(31.230416, 121.473701);

        assertEquals(GOOD_ACTIVITY, result.getActivityId());
        verify(reviewSource).assertProjectApprovedReadOnly(eq(BAD_ACTIVITY), any());
    }

    @Test
    void 没有全合格候选返回null而不是全城回退或占位() {
        RoamExploreDayCandidate only = good(BAD_ACTIVITY, 31.250000);
        RoamExploreDayCandidateVO result = service(
                mapper(only), mock(MerchantPublishedTopicTransferGate.class), mock(CommercialReviewStatusSource.class))
                .findNearestEligible(31.230416, 121.473701);
        assertNull(result);
    }

    @Test
    void 平台自建期没有商家行仍可进入候选() {
        RoamExploreDayCandidate platform = good(GOOD_ACTIVITY, 31.230500);
        platform.setTopicMemberId(100000L);
        platform.setMerchantMemberId(null);
        platform.setMerchantStatus(null);
        platform.setMerchantDelFlag(null);

        assertNotNull(service(mapper(platform), mock(MerchantPublishedTopicTransferGate.class),
                mock(CommercialReviewStatusSource.class)).findNearestEligible(31.230416, 121.473701));
    }

    @Test
    void 平台会员配置变化后候选判据同步变化() {
        RoamExploreDayCandidate configuredPlatform = good(GOOD_ACTIVITY, 31.230500);
        configuredPlatform.setTopicMemberId(42L);
        configuredPlatform.setMerchantMemberId(null);
        configuredPlatform.setMerchantStatus(null);
        configuredPlatform.setMerchantDelFlag(null);
        RoamExploreDayCandidateServiceImpl configured = new RoamExploreDayCandidateServiceImpl(
                mapper(configuredPlatform), mock(MerchantPublishedTopicTransferGate.class),
                mock(CommercialReviewStatusSource.class), 42L);

        assertNotNull(configured.findNearestEligible(31.230416, 121.473701));
    }

    @Test
    void 响应字段是严格allowlist不含会员电话结算费率和候选池() {
        java.util.Set<String> fields = new java.util.TreeSet<>();
        Arrays.stream(RoamExploreDayCandidateVO.class.getDeclaredFields()).forEach(f -> fields.add(f.getName()));
        assertEquals(new java.util.TreeSet<>(Arrays.asList(
                "activityId", "coverImg", "distanceM", "meetingPoint", "subtitle", "title")), fields);
    }

    private void assertVariantRejected(java.util.function.Consumer<RoamExploreDayCandidate> mutation) {
        RoamExploreDayCandidate variant = good(BAD_ACTIVITY, 31.230500);
        mutation.accept(variant);
        RoamExploreDayCandidateVO result = service(
                mapper(good(), variant), mock(MerchantPublishedTopicTransferGate.class), mock(CommercialReviewStatusSource.class))
                .findNearestEligible(31.230416, 121.473701);
        assertEquals(GOOD_ACTIVITY, result.getActivityId(), "不合格近候选不得挤掉合格候选");
    }

    private RoamExploreDayCandidateMapper mapper(RoamExploreDayCandidate... rows) {
        return (minLat, maxLat, minLng, maxLng) -> rows == null
                ? Collections.emptyList() : Arrays.asList(rows);
    }

    private RoamExploreDayCandidate good() {
        return good(GOOD_ACTIVITY, 31.238000);
    }

    private RoamExploreDayCandidate good(long activityId, double lat) {
        Date now = new Date();
        RoamExploreDayCandidate c = new RoamExploreDayCandidate();
        c.setActivityId(activityId);
        c.setTopicId(activityId);
        c.setTopicMemberId(88L);
        c.setMerchantMemberId(88L);
        c.setMerchantStatus(1L);
        c.setMerchantDelFlag(0);
        c.setProductType(ProductType.FREE_EXPLORE);
        c.setTopicLifecycle(3);
        c.setTopicDelFlag(0);
        c.setSellingStartedAt(new Date(now.getTime() - 60_000));
        c.setSaleEndAt(new Date(now.getTime() + 3_600_000));
        c.setRoamReferralEnabled(1);
        c.setProjectReviewStatus(1);
        c.setProjectReviewSnapshotJson("{\"requestedTarget\":\"PRICING\"}");
        c.setSaleReviewStatus(1);
        c.setActivityStatus(1L);
        c.setActivityPublishStatus(1L);
        c.setActivityDelFlag(0);
        c.setTicketId(activityId + 1000);
        c.setTicketOwnerType(2L);
        c.setTicketOwnerId(activityId);
        c.setTicketDelFlag(0);
        c.setRemainingInventory(5L);
        c.setTicketSignupDeadline(new Date(now.getTime() + 1_800_000));
        c.setGatherLat(BigDecimal.valueOf(lat));
        c.setGatherLng(new BigDecimal("121.473701"));
        c.setTitle("合规自由探索活动");
        c.setSubtitle("活动壳");
        c.setCoverImg("https://img.example/cover.jpg");
        c.setMeetingPoint("集合点");
        return c;
    }

    private RoamExploreDayCandidateServiceImpl service(RoamExploreDayCandidateMapper mapper,
                                                       MerchantPublishedTopicTransferGate transferGate,
                                                       CommercialReviewStatusSource reviewStatusSource) {
        return new RoamExploreDayCandidateServiceImpl(mapper, transferGate, reviewStatusSource, 100000L);
    }
}

package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsActivityLike;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicLike;
import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.mapper.CmsActivityLikeMapper;
import com.chengyinhub.business.mapper.CmsRegistrationStatisticsMapper;
import com.chengyinhub.business.mapper.CmsTopicLikeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.TmsBrowseRecordMapper;
import com.chengyinhub.business.mapper.UmsCommentMapper;
import com.chengyinhub.business.mapper.UmsMemberFollowMapper;
import com.chengyinhub.business.service.ICmsActivityLikeService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import com.chengyinhub.business.service.ICmsTopicService;
import org.mockito.ArgumentCaptor;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.ITmsBrowseRecordService;
import com.chengyinhub.business.service.ITopicContentPublisherProjectionService;
import com.chengyinhub.business.service.IUmsCommentService;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.AnnotationConfigContextLoader;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.vo.PublicActivityListVO;
import com.chengyinhub.business.domain.vo.PublicTopicListVO;
import com.chengyinhub.system.domain.SysCategory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * App Activity/Topic list read-model budget guard.
 *
 * <p>The row decorators below used to run one query per returned row. These tests deliberately
 * compare a whole page against a fixed upper bound: duplicate ids and larger page sizes must not
 * linearly increase the number of row-scoped reads.</p>
 */
@WebAppConfiguration
@ContextConfiguration(loader = AnnotationConfigContextLoader.class)
class ApiActivityTopicListQueryBudgetTest {

    private static final Long VIEWER = 88L;

    @BeforeEach
    void bindRequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void unbindRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void activityList_hasFixedDecorationBudgetForWholePage() {
        ActivityFixture f = activityFixture(Arrays.asList(activity(1L), activity(2L), activity(2L)), VIEWER);

        AjaxResult res = f.controller.activityList("0", null, null, null, null, null);

        assertEquals(200, res.get("code"), res.toString());
        assertEquals(3, rows(res).size());
        verify(f.activityLikeService, atMost(1)).selectCmsActivityLikeList(any());
        verify(f.memberFollowService, atMost(1)).selectUmsMemberFollowList(any());
        verify(f.statisticsMapper, atMost(1)).statisticsSalesAmoutByOwner(any());
        verify(f.ticketService, atMost(1)).selectMinTicketPrice(any());
        verify(f.commentService, atMost(1)).selectAvgRatingByCondition(any());
        verify(f.commentService, atMost(1)).selectUmsCommentList(any());
        verify(f.browseRecordService, atMost(1)).countBrowseByTypeAndTarget(any());
        verify(f.categoryService, atMost(1)).selectSysCategoryListByIds(any());
        verify(f.activityLikeMapper).selectCmsActivityLikesByMemberAndDataIds(any(), any());
        verify(f.memberFollowMapper).selectUmsMemberFollowsByMemberAndFollowMemberIds(any(), any());
        verify(f.statisticsMapper).statisticsSalesAmoutByOwners(any(), any());
        verify(f.ticketMapper).selectMinTicketPrices(any(), any());
        verify(f.commentMapper).selectRatingSummaryByOwners(any(), any());
        verify(f.browseMapper).countBrowseByTypeAndTargets(any(), any());
    }

    @Test
    void activityList_emptyPageDoesNotRunDecorationQueries() {
        ActivityFixture f = activityFixture(Collections.emptyList(), VIEWER);

        AjaxResult res = f.controller.activityList("0", null, null, null, null, null);

        assertEquals(200, res.get("code"), res.toString());
        assertEquals(0, rows(res).size());
        verifyNoInteractions(f.activityLikeService, f.memberFollowService, f.statisticsMapper,
                f.ticketService, f.commentService, f.browseRecordService, f.categoryService);
    }

    @Test
    void activityDistanceFilterDoesNotSilentlyTurnLatestIntoNearest() {
        ActivityFixture f = activityFixture(Collections.emptyList(), VIEWER);
        ArgumentCaptor<CmsActivity> query = ArgumentCaptor.forClass(CmsActivity.class);

        AjaxResult res = f.controller.activityList("0", null, null, null,
                "121.47", "31.23", null, null, "LATEST", null, "100000");

        assertEquals(200, res.get("code"), res.toString());
        verify(f.activityService).selectPublicCmsActivityList(query.capture(), any());
        assertEquals("121.47", query.getValue().getLongitude(), "显式半径必须保留坐标供 SQL 筛选");
        assertEquals("31.23", query.getValue().getLatitude(), "显式半径必须保留坐标供 SQL 筛选");
        assertEquals(Integer.valueOf(100000), query.getValue().getDistanceMeters(), "公开主查询必须消费显式半径");
        assertNull(query.getValue().getSortType(), "LATEST 不得被半径筛选偷偷改成 NEAREST");
    }

    @Test
    void legacyCoordinateOnlyQueryStillValidatesBeforeSpatialSql() {
        ActivityFixture f = activityFixture(Collections.emptyList(), VIEWER);

        AjaxResult invalid = f.controller.activityList("0", null, null, null,
                "not-a-number", "91", null, null, null, null, null);

        assertEquals(400, invalid.get("code"), "旧客户端坐标入口也必须走冻结契约校验");
        assertEquals("longitude/latitude 必须为合法经纬度", invalid.get("msg"),
                "非法坐标必须指认字段，不能下沉成空间 SQL 异常");
        verify(f.activityService, never()).selectPublicCmsActivityList(any(), any());

        ActivityFixture missingPairFixture = activityFixture(Collections.emptyList(), VIEWER);
        AjaxResult missingPair = missingPairFixture.controller.activityList("0", null, null, null,
                "121.4", null, null, null, "LATEST", null, null);

        assertEquals(400, missingPair.get("code"), "单边坐标不能被静默丢弃");
        assertEquals("longitude、latitude 必须同时提供", missingPair.get("msg"),
                "坐标配对错误必须指认字段");
        verify(missingPairFixture.activityService, never()).selectPublicCmsActivityList(any(), any());
    }

    @Test
    void activityList_anonymousViewerDoesNotQueryUserState() {
        ActivityFixture f = activityFixture(Collections.singletonList(activity(1L)), null);

        AjaxResult res = f.controller.activityList("0", null, null, null, null, null);

        assertEquals(200, res.get("code"), res.toString());
        verifyNoInteractions(f.activityLikeService, f.memberFollowService);
        verifyNoInteractions(f.activityLikeMapper, f.memberFollowMapper);
    }

    @Test
    void topicList_publicBranchMustNotApplyTheEndDateGate() {
        // 这条守的是控制器那一行,不是 SQL。公开分支曾经设 isNew=1(→ SQL 加 end_date > now()),
        // 而主题是常青内容、不是场次:2026-08-27 实测全库 end_date 在未来的主题为 0 条
        // ⇒「推荐主题」和底部「主题」页签长期静默全空,开发版还被 mock 盖住看不出来。
        // mapper 层的 PublicTopicFeedGateMyBatisTest 拦不住这个回归 —— 那道 <if> 只有
        // 调用方真的设了 isNew 才生效,所以必须在这一层钉住「公开分支不设它」。
        TopicFixture f = topicFixture(Arrays.asList(topic(1L)), VIEWER);

        AjaxResult res = f.controller.topicList("0", null, null, null, null, null);

        assertEquals(200, res.get("code"), res.toString());
        ArgumentCaptor<CmsTopic> probe = ArgumentCaptor.forClass(CmsTopic.class);
        verify(f.topicService).selectPublicCmsTopicList(probe.capture());
        assertNull(probe.getValue().getIsNew(),
                "公开主题查询不得带 end_date 闸 —— 主题是常青内容，上下架用 status/user_status 表达");
    }

    @Test
    void topicList_hasFixedDecorationBudgetForWholePage() {
        TopicFixture f = topicFixture(Arrays.asList(topic(1L), topic(2L), topic(2L)), VIEWER);

        AjaxResult res = f.controller.topicList("0", null, null, null, null, null);

        assertEquals(200, res.get("code"), res.toString());
        assertEquals(3, rows(res).size());
        verify(f.topicLikeService, atMost(1)).selectCmsTopicLikeList(any());
        verify(f.memberFollowService, atMost(1)).selectUmsMemberFollowList(any());
        verify(f.statisticsMapper, atMost(1)).statisticsSalesAmoutByOwner(any());
        verify(f.ticketService, atMost(1)).selectMinTicketPrice(any());
        verify(f.commentService, atMost(1)).selectAvgRatingByCondition(any());
        verify(f.commentService, atMost(1)).selectUmsCommentList(any());
        verify(f.browseRecordService, atMost(1)).countBrowseByTypeAndTarget(any());
        verify(f.categoryService, atMost(1)).selectSysCategoryListByIds(any());
        verify(f.topicLikeMapper).selectCmsTopicLikesByMemberAndDataIds(any(), any());
        verify(f.memberFollowMapper).selectUmsMemberFollowsByMemberAndFollowMemberIds(any(), any());
        verify(f.statisticsMapper).statisticsSalesAmoutByOwners(any(), any());
        verify(f.ticketMapper).selectMinTicketPrices(any(), any());
        verify(f.commentMapper).selectRatingSummaryByOwners(any(), any());
        verify(f.browseMapper).countBrowseByTypeAndTargets(any(), any());
    }

    @Test
    void topicList_anonymousViewerDoesNotQueryUserState() {
        TopicFixture f = topicFixture(Collections.singletonList(topic(1L)), null);

        AjaxResult res = f.controller.topicList("0", null, null, null, null, null);

        assertEquals(200, res.get("code"), res.toString());
        verifyNoInteractions(f.topicLikeService, f.memberFollowService);
        verifyNoInteractions(f.topicLikeMapper, f.memberFollowMapper);
    }

    @Test
    void merchantScopedMyTopicsUseOwnerIdentityButBlankScopeStaysPersonal() {
        TopicFixture f = topicFixture(Collections.emptyList(), VIEWER);
        when(f.merchantAccess.require(VIEWER, MerchantPermission.PROJECT_MANAGE)).thenReturn(
                new MerchantAccessContext(VIEWER, 77L, 900L, MerchantRoleCode.MERCHANT_MANAGER));
        when(f.topicService.selectCmsTopicList(any())).thenReturn(Collections.emptyList());

        AjaxResult merchant = f.controller.topicList("1", null, null, null, null, "MERCHANT");
        assertEquals(200, merchant.get("code"));
        org.mockito.ArgumentCaptor<CmsTopic> merchantQuery = org.mockito.ArgumentCaptor.forClass(CmsTopic.class);
        verify(f.topicService).selectCmsTopicList(merchantQuery.capture());
        assertEquals(900L, merchantQuery.getValue().getMemberId());

        org.mockito.Mockito.clearInvocations(f.topicService, f.merchantAccess);
        when(f.topicService.selectCmsTopicList(any())).thenReturn(Collections.emptyList());
        AjaxResult personal = f.controller.topicList("1", null, null, null, null, null);
        assertEquals(200, personal.get("code"));
        org.mockito.ArgumentCaptor<CmsTopic> personalQuery = org.mockito.ArgumentCaptor.forClass(CmsTopic.class);
        verify(f.topicService).selectCmsTopicList(personalQuery.capture());
        assertEquals(VIEWER, personalQuery.getValue().getMemberId());
        verify(f.merchantAccess, never()).require(any(), any());
    }

    @Test
    void merchantClubInvitePickerUsesCoopPermissionAndOwnerScope() {
        TopicFixture f = topicFixture(Collections.emptyList(), VIEWER);
        when(f.merchantAccess.require(VIEWER, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException("无项目管理权限", 403));
        when(f.merchantAccess.require(VIEWER, MerchantPermission.COOP_MANAGE)).thenReturn(
                new MerchantAccessContext(VIEWER, 77L, 900L, MerchantRoleCode.MERCHANT_MARKETING));
        when(f.topicService.selectCmsTopicList(any())).thenReturn(Collections.emptyList());

        AjaxResult res = f.controller.topicList("1", null, null, null, null, "MERCHANT", null, null, "club");

        assertEquals(200, res.get("code"));
        ArgumentCaptor<CmsTopic> query = ArgumentCaptor.forClass(CmsTopic.class);
        verify(f.topicService).selectCmsTopicList(query.capture());
        assertEquals(900L, query.getValue().getMemberId());
        verify(f.merchantAccess).require(VIEWER, MerchantPermission.COOP_MANAGE);
        verify(f.merchantAccess, never()).require(VIEWER, MerchantPermission.PROJECT_MANAGE);
    }

    @Test
    void merchantClubInvitePickerRejectsMissingCoopPermissionBeforeReadingTopics() {
        TopicFixture f = topicFixture(Collections.emptyList(), VIEWER);
        when(f.merchantAccess.require(VIEWER, MerchantPermission.COOP_MANAGE))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException("无合作管理权限", 403));

        AjaxResult res = f.controller.topicList("1", null, null, null, null, "MERCHANT", null, null, "club");

        assertEquals(403, res.get("code"));
        verify(f.topicService, never()).selectCmsTopicList(any());
        verify(f.merchantAccess, never()).require(VIEWER, MerchantPermission.PROJECT_MANAGE);
    }

    @Test
    void otherMerchantTopicQueriesStillRequireProjectPermission() {
        for (String target : Arrays.asList(null, "merchant", "CLUB", " club", "unknown")) {
            TopicFixture f = topicFixture(Collections.emptyList(), VIEWER);
            when(f.merchantAccess.require(VIEWER, MerchantPermission.PROJECT_MANAGE))
                    .thenThrow(new com.chengyinhub.common.exception.ServiceException("无项目管理权限", 403));

            AjaxResult res = f.controller.topicList("1", null, null, null, null, "MERCHANT", null, null, target);

            assertEquals(403, res.get("code"), "invite_target=" + target);
            verify(f.topicService, never()).selectCmsTopicList(any());
            verify(f.merchantAccess, never()).require(VIEWER, MerchantPermission.COOP_MANAGE);
        }
    }

    @Test
    void clubInviteTargetWithoutMerchantScopeStaysPersonal() {
        TopicFixture f = topicFixture(Collections.emptyList(), VIEWER);
        when(f.topicService.selectCmsTopicList(any())).thenReturn(Collections.emptyList());

        AjaxResult res = f.controller.topicList("1", null, null, null, null, null, null, null, "club");

        assertEquals(200, res.get("code"));
        ArgumentCaptor<CmsTopic> query = ArgumentCaptor.forClass(CmsTopic.class);
        verify(f.topicService).selectCmsTopicList(query.capture());
        assertEquals(VIEWER, query.getValue().getMemberId());
        verifyNoInteractions(f.merchantAccess);
    }

    // CU-M-29:发起协作邀约页读「我的主题」时按方向回填 inviteWindowOpen,判据与写闸同一份。
    @Test
    void myTopicsWithInviteTargetCarryInviteWindowFlag() {
        TopicFixture f = topicFixture(Collections.emptyList(), VIEWER);
        CmsTopic recruiting = topic(1L);
        recruiting.setLifecycle(1);
        recruiting.setRecruitDeadline(new java.util.Date(System.currentTimeMillis() + 86_400_000L));
        CmsTopic locked = topic(2L);
        locked.setLifecycle(3);
        locked.setRecruitDeadline(new java.util.Date(System.currentTimeMillis() + 86_400_000L));
        when(f.topicService.selectCmsTopicList(any())).thenReturn(Arrays.asList(recruiting, locked));
        com.chengyinhub.business.service.support.ClubCoopWindowPolicy clubPolicy =
                org.mockito.Mockito.mock(com.chengyinhub.business.service.support.ClubCoopWindowPolicy.class);
        when(clubPolicy.isOpen(org.mockito.ArgumentMatchers.eq(recruiting), any())).thenReturn(true);
        when(clubPolicy.isOpen(org.mockito.ArgumentMatchers.eq(locked), any())).thenReturn(false);
        ReflectionTestUtils.setField(f.controller, "clubCoopWindowPolicy", clubPolicy);

        f.controller.topicList("1", null, null, null, null, null, null, null, "club");
        assertEquals(Boolean.TRUE, recruiting.getInviteWindowOpen());
        assertEquals(Boolean.FALSE, locked.getInviteWindowOpen());

        recruiting.setInviteWindowOpen(null);
        locked.setInviteWindowOpen(null);
        f.controller.topicList("1", null, null, null, null, null, null, null, "merchant");
        assertEquals(Boolean.TRUE, recruiting.getInviteWindowOpen(), "商家方向走 RecruitmentWindowPolicy:招募中且未过截止");
        assertEquals(Boolean.FALSE, locked.getInviteWindowOpen(), "锁价后(lifecycle=3)不在招商期");

        recruiting.setInviteWindowOpen(null);
        f.controller.topicList("1", null, null, null, null, null, null, null, null);
        assertNull(recruiting.getInviteWindowOpen(), "不带 invite_target 的调用方不算、不回填");
    }

    @Test
    void activityList_batchLikesAndFollowsFlowToVO() {
        ActivityFixture f = activityFixture(Arrays.asList(activity(1L), activity(2L)), VIEWER);
        CmsActivityLike liked = new CmsActivityLike();
        liked.setDataId(1L);
        liked.setLikeType(2);
        when(f.activityLikeMapper.selectCmsActivityLikesByMemberAndDataIds(any(), any()))
                .thenReturn(Collections.singletonList(liked));
        UmsMemberFollow followed = new UmsMemberFollow();
        followed.setFollowMemberId(701L);
        followed.setMemberId(VIEWER);
        when(f.memberFollowMapper.selectUmsMemberFollowsByMemberAndFollowMemberIds(any(), any()))
                .thenReturn(Collections.singletonList(followed));

        AjaxResult res = f.controller.activityList("0", null, null, null, null, null);
        assertEquals(200, res.get("code"), res.toString());
        List<?> dataRows = rows(res);
        PublicActivityListVO a1 = (PublicActivityListVO) dataRows.get(0);
        PublicActivityListVO a2 = (PublicActivityListVO) dataRows.get(1);
        assertEquals(2, a1.getIsLiked().intValue(), "activity 1L got likeType=2 from batch");
        assertEquals(1, a1.getIsFollowTheUser().intValue(), "activity 1L publisher 701L is followed");
        assertEquals(0, a2.getIsLiked().intValue(), "activity 2L not in batch like result");
        assertEquals(0, a2.getIsFollowTheUser().intValue(), "activity 2L publisher 702L not followed");
    }

    @Test
    void activityList_batchPriceRatingBrowseCategoriesFlowToVO() {
        ActivityFixture f = activityFixture(Collections.singletonList(activity(1L)), VIEWER);
        Long oid = 1L;
        Map<String, Object> priceRow = new HashMap<>();
        priceRow.put("owner_id", oid);
        priceRow.put("minTicketPrice", new BigDecimal("99"));
        when(f.ticketMapper.selectMinTicketPrices(any(), any()))
                .thenReturn(Collections.singletonList(priceRow));
        Map<String, Object> ratingRow = new HashMap<>();
        ratingRow.put("owner_id", oid);
        ratingRow.put("avg_rating", 4.5);
        ratingRow.put("rating_total", 7L);
        when(f.commentMapper.selectRatingSummaryByOwners(any(), any()))
                .thenReturn(Collections.singletonList(ratingRow));
        Map<String, Object> browseRow = new HashMap<>();
        browseRow.put("target_id", oid);
        browseRow.put("browse_count", 42L);
        when(f.browseMapper.countBrowseByTypeAndTargets(any(), any()))
                .thenReturn(Collections.singletonList(browseRow));
        SysCategory cat = new SysCategory();
        cat.setId(1L);
        when(f.categoryService.selectSysCategoryListByIds(any()))
                .thenReturn(Collections.singletonList(cat));

        AjaxResult res = f.controller.activityList("0", null, null, null, null, null);
        assertEquals(200, res.get("code"), res.toString());
        PublicActivityListVO a1 = (PublicActivityListVO) rows(res).get(0);
        assertEquals(0, new BigDecimal("99").compareTo(a1.getMinAmout()), "minPrice=99");
        assertEquals(4.5, a1.getRating(), 0.001, "rating=4.5");
        assertEquals(7, a1.getRatingTotal().intValue(), "ratingTotal=7");
        assertEquals(42L, a1.getViewCount().longValue(), "viewCount=42");
        assertEquals(1, a1.getSysCategoryList().size(), "category list size=1");
        assertEquals(1L, a1.getSysCategoryList().get(0).getId().longValue(), "category id=1");
    }

    @Test
    void topicList_batchLikePriceRatingBrowseCategoriesFlowToVO() {
        TopicFixture f = topicFixture(Collections.singletonList(topic(1L)), VIEWER);
        Long oid = 1L;
        CmsTopicLike liked = new CmsTopicLike();
        liked.setDataId(oid);
        when(f.topicLikeMapper.selectCmsTopicLikesByMemberAndDataIds(any(), any()))
                .thenReturn(Collections.singletonList(liked));
        Map<String, Object> priceRow = new HashMap<>();
        priceRow.put("owner_id", oid);
        priceRow.put("minTicketPrice", new BigDecimal("55"));
        when(f.ticketMapper.selectMinTicketPrices(any(), any()))
                .thenReturn(Collections.singletonList(priceRow));
        Map<String, Object> ratingRow = new HashMap<>();
        ratingRow.put("owner_id", oid);
        ratingRow.put("avg_rating", 3.5);
        ratingRow.put("rating_total", 3L);
        when(f.commentMapper.selectRatingSummaryByOwners(any(), any()))
                .thenReturn(Collections.singletonList(ratingRow));
        Map<String, Object> browseRow = new HashMap<>();
        browseRow.put("target_id", oid);
        browseRow.put("browse_count", 100L);
        when(f.browseMapper.countBrowseByTypeAndTargets(any(), any()))
                .thenReturn(Collections.singletonList(browseRow));
        SysCategory cat = new SysCategory();
        cat.setId(2L);
        when(f.categoryService.selectSysCategoryListByIds(any()))
                .thenReturn(Collections.singletonList(cat));

        AjaxResult res = f.controller.topicList("0", null, null, null, null, null);
        assertEquals(200, res.get("code"), res.toString());
        PublicTopicListVO t1 = (PublicTopicListVO) rows(res).get(0);
        assertEquals(1, t1.getIsLike().intValue(), "topic liked from batch");
        assertEquals(0, new BigDecimal("55").compareTo(t1.getMinAmout()), "minPrice=55");
        assertEquals(3.5, t1.getRating(), 0.001, "rating=3.5");
        assertEquals(3, t1.getRatingTotal().intValue(), "ratingTotal=3");
        assertEquals(100L, t1.getViewCount().longValue(), "viewCount=100");
        assertEquals(1, t1.getSysCategoryList().size(), "category list size=1");
        assertEquals(2L, t1.getSysCategoryList().get(0).getId().longValue(), "category id=2");
    }

    private ActivityFixture activityFixture(List<CmsActivity> activities, Long viewer) {
        ActivityFixture f = new ActivityFixture();
        f.activityService = mock(ICmsActivityService.class);
        when(f.activityService.selectPublicCmsActivityList(any(), any())).thenReturn(new ArrayList<>(activities));
        f.activityLikeService = mock(ICmsActivityLikeService.class);
        when(f.activityLikeService.selectCmsActivityLikeList(any())).thenReturn(Collections.emptyList());
        f.memberFollowService = mock(IUmsMemberFollowService.class);
        when(f.memberFollowService.selectUmsMemberFollowList(any())).thenReturn(Collections.emptyList());
        f.statisticsMapper = mock(CmsRegistrationStatisticsMapper.class);
        when(f.statisticsMapper.statisticsSalesAmoutByOwner(any())).thenReturn(Collections.emptyList());
        f.ticketService = mock(IOmsTicketService.class);
        when(f.ticketService.selectMinTicketPrice(any())).thenReturn(BigDecimal.ZERO);
        f.commentService = mock(IUmsCommentService.class);
        when(f.commentService.selectAvgRatingByCondition(any())).thenReturn(null);
        when(f.commentService.selectUmsCommentList(any())).thenReturn(Collections.emptyList());
        f.browseRecordService = mock(ITmsBrowseRecordService.class);
        when(f.browseRecordService.countBrowseByTypeAndTarget(any())).thenReturn(Collections.emptyList());
        f.categoryService = mock(ISysCategoryService.class);
        when(f.categoryService.selectSysCategoryListByIds(any())).thenReturn(Collections.emptyList());
        f.activityLikeMapper = mock(CmsActivityLikeMapper.class);
        when(f.activityLikeMapper.selectCmsActivityLikesByMemberAndDataIds(any(), any())).thenReturn(Collections.emptyList());
        f.memberFollowMapper = mock(UmsMemberFollowMapper.class);
        when(f.memberFollowMapper.selectUmsMemberFollowsByMemberAndFollowMemberIds(any(), any())).thenReturn(Collections.emptyList());
        f.ticketMapper = mock(OmsTicketMapper.class);
        when(f.ticketMapper.selectMinTicketPrices(any(), any())).thenReturn(Collections.emptyList());
        f.commentMapper = mock(UmsCommentMapper.class);
        when(f.commentMapper.selectRatingSummaryByOwners(any(), any())).thenReturn(Collections.emptyList());
        f.browseMapper = mock(TmsBrowseRecordMapper.class);
        when(f.browseMapper.countBrowseByTypeAndTargets(any(), any())).thenReturn(Collections.emptyList());

        f.controller = spy(new ApiActivityController());
        if (viewer == null) {
            doThrow(new RuntimeException("anonymous")).when(f.controller).getAppUserId();
        } else {
            doReturn(viewer).when(f.controller).getAppUserId();
        }
        ReflectionTestUtils.setField(f.controller, "cmsActivityService", f.activityService);
        ReflectionTestUtils.setField(f.controller, "cmsActivityLikeService", f.activityLikeService);
        ReflectionTestUtils.setField(f.controller, "umsMemberFollowService", f.memberFollowService);
        ReflectionTestUtils.setField(f.controller, "cmsRegistrationStatisticsMapper", f.statisticsMapper);
        ReflectionTestUtils.setField(f.controller, "omsTicketService", f.ticketService);
        ReflectionTestUtils.setField(f.controller, "umsCommentService", f.commentService);
        ReflectionTestUtils.setField(f.controller, "tmsBrowseRecordService", f.browseRecordService);
        ReflectionTestUtils.setField(f.controller, "sysCategoryService", f.categoryService);
        ReflectionTestUtils.setField(f.controller, "cmsActivityLikeMapper", f.activityLikeMapper);
        ReflectionTestUtils.setField(f.controller, "umsMemberFollowMapper", f.memberFollowMapper);
        ReflectionTestUtils.setField(f.controller, "omsTicketMapper", f.ticketMapper);
        ReflectionTestUtils.setField(f.controller, "umsCommentMapper", f.commentMapper);
        ReflectionTestUtils.setField(f.controller, "tmsBrowseRecordMapper", f.browseMapper);
        return f;
    }

    private TopicFixture topicFixture(List<CmsTopic> topics, Long viewer) {
        TopicFixture f = new TopicFixture();
        f.topicService = mock(ICmsTopicService.class);
        when(f.topicService.selectPublicCmsTopicList(any())).thenReturn(new ArrayList<>(topics));
        f.topicLikeService = mock(ICmsTopicLikeService.class);
        when(f.topicLikeService.selectCmsTopicLikeList(any())).thenReturn(Collections.emptyList());
        f.memberFollowService = mock(IUmsMemberFollowService.class);
        when(f.memberFollowService.selectUmsMemberFollowList(any())).thenReturn(Collections.emptyList());
        f.statisticsMapper = mock(CmsRegistrationStatisticsMapper.class);
        when(f.statisticsMapper.statisticsSalesAmoutByOwner(any())).thenReturn(Collections.emptyList());
        f.ticketService = mock(IOmsTicketService.class);
        when(f.ticketService.selectMinTicketPrice(any())).thenReturn(BigDecimal.ZERO);
        f.commentService = mock(IUmsCommentService.class);
        when(f.commentService.selectAvgRatingByCondition(any())).thenReturn(null);
        when(f.commentService.selectUmsCommentList(any())).thenReturn(Collections.emptyList());
        f.browseRecordService = mock(ITmsBrowseRecordService.class);
        when(f.browseRecordService.countBrowseByTypeAndTarget(any())).thenReturn(Collections.emptyList());
        f.categoryService = mock(ISysCategoryService.class);
        when(f.categoryService.selectSysCategoryListByIds(any())).thenReturn(Collections.emptyList());
        f.publisherProjection = mock(ITopicContentPublisherProjectionService.class);
        f.merchantAccess = mock(IMerchantAccessService.class);
        when(f.publisherProjection.project(any())).thenAnswer(inv -> inv.getArgument(0));
        f.topicLikeMapper = mock(CmsTopicLikeMapper.class);
        when(f.topicLikeMapper.selectCmsTopicLikesByMemberAndDataIds(any(), any())).thenReturn(Collections.emptyList());
        f.memberFollowMapper = mock(UmsMemberFollowMapper.class);
        when(f.memberFollowMapper.selectUmsMemberFollowsByMemberAndFollowMemberIds(any(), any())).thenReturn(Collections.emptyList());
        f.ticketMapper = mock(OmsTicketMapper.class);
        when(f.ticketMapper.selectMinTicketPrices(any(), any())).thenReturn(Collections.emptyList());
        f.commentMapper = mock(UmsCommentMapper.class);
        when(f.commentMapper.selectRatingSummaryByOwners(any(), any())).thenReturn(Collections.emptyList());
        f.browseMapper = mock(TmsBrowseRecordMapper.class);
        when(f.browseMapper.countBrowseByTypeAndTargets(any(), any())).thenReturn(Collections.emptyList());

        f.controller = spy(new ApiTopicController());
        if (viewer == null) {
            doThrow(new RuntimeException("anonymous")).when(f.controller).getAppUserId();
        } else {
            doReturn(viewer).when(f.controller).getAppUserId();
        }
        ReflectionTestUtils.setField(f.controller, "cmsTopicService", f.topicService);
        ReflectionTestUtils.setField(f.controller, "cmsTopicLikeService", f.topicLikeService);
        ReflectionTestUtils.setField(f.controller, "umsMemberFollowService", f.memberFollowService);
        ReflectionTestUtils.setField(f.controller, "cmsRegistrationStatisticsMapper", f.statisticsMapper);
        ReflectionTestUtils.setField(f.controller, "omsTicketService", f.ticketService);
        ReflectionTestUtils.setField(f.controller, "umsCommentService", f.commentService);
        ReflectionTestUtils.setField(f.controller, "tmsBrowseRecordService", f.browseRecordService);
        ReflectionTestUtils.setField(f.controller, "sysCategoryService", f.categoryService);
        ReflectionTestUtils.setField(f.controller, "topicContentPublisherProjectionService", f.publisherProjection);
        ReflectionTestUtils.setField(f.controller, "merchantAccessService", f.merchantAccess);
        ReflectionTestUtils.setField(f.controller, "cmsTopicLikeMapper", f.topicLikeMapper);
        ReflectionTestUtils.setField(f.controller, "umsMemberFollowMapper", f.memberFollowMapper);
        ReflectionTestUtils.setField(f.controller, "omsTicketMapper", f.ticketMapper);
        ReflectionTestUtils.setField(f.controller, "umsCommentMapper", f.commentMapper);
        ReflectionTestUtils.setField(f.controller, "tmsBrowseRecordMapper", f.browseMapper);
        return f;
    }

    private CmsActivity activity(Long id) {
        CmsActivity activity = new CmsActivity();
        activity.setId(id);
        activity.setMemberId(700L + id);
        activity.setName("活动" + id);
        activity.setCategoryIds("1,2");
        return activity;
    }

    private CmsTopic topic(Long id) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        topic.setMemberId(800L + id);
        topic.setName("主题" + id);
        topic.setCategoryIds("1,2");
        return topic;
    }

    private List<?> rows(AjaxResult res) {
        return ((TableDataInfo) res.get("data")).getRows();
    }

    private static class ActivityFixture {
        ApiActivityController controller;
        ICmsActivityService activityService;
        ICmsActivityLikeService activityLikeService;
        IUmsMemberFollowService memberFollowService;
        CmsRegistrationStatisticsMapper statisticsMapper;
        IOmsTicketService ticketService;
        IUmsCommentService commentService;
        ITmsBrowseRecordService browseRecordService;
        ISysCategoryService categoryService;
        CmsActivityLikeMapper activityLikeMapper;
        UmsMemberFollowMapper memberFollowMapper;
        OmsTicketMapper ticketMapper;
        UmsCommentMapper commentMapper;
        TmsBrowseRecordMapper browseMapper;
    }

    private static class TopicFixture {
        ApiTopicController controller;
        ICmsTopicService topicService;
        ICmsTopicLikeService topicLikeService;
        IUmsMemberFollowService memberFollowService;
        CmsRegistrationStatisticsMapper statisticsMapper;
        IOmsTicketService ticketService;
        IUmsCommentService commentService;
        ITmsBrowseRecordService browseRecordService;
        ISysCategoryService categoryService;
        ITopicContentPublisherProjectionService publisherProjection;
        IMerchantAccessService merchantAccess;
        CmsTopicLikeMapper topicLikeMapper;
        UmsMemberFollowMapper memberFollowMapper;
        OmsTicketMapper ticketMapper;
        UmsCommentMapper commentMapper;
        TmsBrowseRecordMapper browseMapper;
    }
}

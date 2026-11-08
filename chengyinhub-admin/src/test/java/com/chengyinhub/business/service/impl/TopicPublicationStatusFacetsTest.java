package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.vo.TopicBundleSubmitResultVO;
import com.chengyinhub.business.domain.vo.TopicPublicationStatusVO;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.support.SaleAvailabilityPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 发布状态五段事实:草稿 / 审核 / 公开 / 售卖 / 可玩 必须分别表达。
 *
 * <p>核心不变量(本次需求「公开待审不等于过审」):专业主题先公开后审时
 * {@code published=true} 同时 {@code reviewPassed=false};两者不能互相替代。
 */
class TopicPublicationStatusFacetsTest {

    private static final long TOPIC_ID = 501L;
    private static final long MEMBER_ID = 7L;

    private CmsTopicMapper topicMapper;
    private IAuditTaskService auditTaskService;
    private TopicPublicationService service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        auditTaskService = mock(IAuditTaskService.class);
        lenient().when(auditTaskService.selectAuditTaskList(any(AuditTask.class)))
                .thenReturn(Collections.emptyList());
        service = new TopicPublicationService();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "auditTaskService", auditTaskService);
    }

    /** 草稿:没提交、没人看、卖不了、也开不了局 —— 四段各自说清楚,不互相顶替。 */
    @Test
    void draftTopicIsDraftNotPublicAndNotPlayable() {
        CmsTopic topic = topic(0, 0, 0);
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(1);

        TopicPublicationStatusVO status = service.publicationStatus(topic);

        assertTrue(status.getDraft().isDraft());
        assertTrue(status.getDraft().isEditable(), "草稿必须可编辑");
        assertEquals(TopicPublicationStatusVO.REVIEW_DRAFT, status.getReview().getState());
        assertFalse(status.getReview().isPending(), "草稿不是「等审核结论」");
        assertFalse(status.getVisibility().isVisible());
        assertEquals("尚未发布，玩家看不到", status.getVisibility().getReason());
        assertFalse(status.getSale().isOnSale());
        assertEquals("lifecycle 必须为 SELLING", status.getSale().getBlockReason());
        assertFalse(status.getPlayable().isPlayable());
    }

    /** 公开待审:玩家看得见 + 还没过审 + 还没开售。三条同时成立,不能被一个 published 抹平。 */
    @Test
    void publicTopicAwaitingCommercialReviewIsVisibleButNotPassedAndNotOnSale() {
        CmsTopic topic = topic(1, 1, 0);
        topic.setLifecycle(TopicLifecycleState.PRICING.getCode());
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);

        TopicPublicationStatusVO status = service.publicationStatus(topic);

        assertTrue(status.getVisibility().isVisible());
        assertNull(status.getVisibility().getReason());
        assertFalse(status.getReview().isPassed(), "check_status=0 就是没过审,公开不算过审");
        // 没有任何未结审核单时不能声称「待审」:check_status=0 单独不等于存在审核单。
        assertFalse(status.getReview().isPending());
        assertEquals(TopicPublicationStatusVO.REVIEW_AWAITING, status.getReview().getState());
        assertNull(status.getReview().getAuditTaskId());
        assertTrue(status.getSale().isAwaitingReview());
        assertFalse(status.getSale().isOnSale());
        assertEquals("请在定价工作台保存完整开售参数，保存后自动提交开售审核", status.getSale().getNextStep());
    }

    /** 审核单未结时,审核段要指到那张单子,作者才知道去看哪一单。 */
    @Test
    void openReviewTaskSuppliesTheAuditTaskId() {
        CmsTopic topic = topic(1, 1, 0);
        topic.setLifecycle(TopicLifecycleState.PENDING_REVIEW.getCode());
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);
        AuditTask task = new AuditTask();
        task.setId(9001L);
        task.setStatus(AuditTask.STATUS_ESCALATED);
        when(auditTaskService.selectAuditTaskList(any(AuditTask.class)))
                .thenReturn(Collections.singletonList(task));

        TopicPublicationStatusVO status = service.publicationStatus(topic);

        assertEquals(9001L, status.getReview().getAuditTaskId());
        assertEquals(TopicPublicationStatusVO.REVIEW_ESCALATED, status.getReview().getState());
        assertFalse(status.getReview().isPassed());
    }

    /** 已过审且在售:可售闸放行 → onSale 与 lifecycleSaleOpen 同为 true,blockReason 必须为 null。 */
    @Test
    void passedTopicOnSaleIsPlayableThroughItsPublishedSnapshot() {
        CmsTopic topic = topic(1, 1, 1);
        topic.setLifecycle(TopicLifecycleState.SELLING.getCode());
        topic.setSellingStartedAt(withinSellingWindow());
        topic.setSaleEndAt(farFuture());
        topic.setPublishedConfigVersion(3L);
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);

        TopicPublicationStatusVO status = service.publicationStatus(topic);

        assertEquals(TopicPublicationStatusVO.REVIEW_PASSED, status.getReview().getState());
        assertTrue(status.getReview().isPassed());
        assertTrue(status.getVisibility().isVisible());
        assertTrue(status.getSale().isOnSale());
        assertTrue(status.getSale().isLifecycleSaleOpen());
        assertNull(status.getSale().getNextStep());
        assertNull(status.getSale().getBlockReason(), "售卖闸放行时不能编一条拒绝理由");
        assertTrue(status.getPlayable().isPlayable());
        assertEquals(3L, status.getPlayable().getPublishedConfigVersion());
        assertEquals(TopicPublicationStatusVO.Playable.GATE_PUBLISHED_SNAPSHOT_ONLY,
                status.getPlayable().getGate(), "可玩段只答内容侧,不能冒充完整开局许可");
        assertFalse(status.getDraft().isEditable(), "在售主题商业结构冻结");
    }

    /**
     * 售止已过:生命周期仍在 SELLING,但「在售」必须为 false —— 否则同屏出现
     * 「在售」+「saleEndAt 已到,不可再售」。原因用售卖闸原话。
     *
     * <p>负控:把 onSale 写回 {@code lifecycleSaleOpen},本用例红。
     */
    @Test
    void pastSaleEndUsesTheSaleGateWording() {
        CmsTopic topic = topic(1, 1, 1);
        topic.setLifecycle(TopicLifecycleState.SELLING.getCode());
        topic.setSellingStartedAt(at("2026-08-11T01:00:00Z"));
        topic.setSaleEndAt(at("2026-08-11T04:00:00Z"));
        topic.setPublishedConfigVersion(1L);
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);

        assertTrue(Instant.now().isAfter(at("2026-08-11T04:00:00Z").toInstant()));
        TopicPublicationStatusVO status = service.publicationStatus(topic);

        // 与真实售卖闸同一句(见 SaleAvailabilityPolicy#assertSellable 的抛出版本)。
        assertThrowsIllegalState(topic);
        assertEquals("saleEndAt 已到，自玩通行证不可再售", status.getSale().getBlockReason());
        assertTrue(status.getSale().isLifecycleSaleOpen(), "生命周期事实单独保留");
        assertFalse(status.getSale().isOnSale(), "不可售时不能同时报「在售」");
    }

    /** 内容安全留置:既不是「已过审」也不是「被驳回」,是等人工复核。 */
    @Test
    void contentSafetyHoldIsPendingReviewNotRejected() {
        CmsTopic topic = topic(1, 0, 2);
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);

        TopicPublicationStatusVO status = service.publicationStatus(topic);

        assertEquals(TopicPublicationStatusVO.REVIEW_PENDING, status.getReview().getState());
        assertEquals("内容安全复核中", status.getReview().getLabel());
        assertFalse(status.getReview().isPassed());
        assertFalse(status.getVisibility().isVisible());
        assertEquals("内容安全复核中，暂不公开", status.getVisibility().getReason());
    }

    /** 人工驳回:未过审、不公开,理由是驳回理由。 */
    @Test
    void rejectedTopicCarriesTheReasonAndStaysInvisible() {
        CmsTopic topic = topic(2, 0, 2);
        topic.setReason("内容不符合平台规范");
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);

        TopicPublicationStatusVO status = service.publicationStatus(topic);

        assertEquals(TopicPublicationStatusVO.REVIEW_REJECTED, status.getReview().getState());
        assertFalse(status.getReview().isPassed());
        assertEquals("内容不符合平台规范", status.getReview().getDetail());
        assertFalse(status.getVisibility().isVisible());
        assertEquals("审核未通过，玩家看不到", status.getVisibility().getReason());
    }

    /** 发布接口的返回:published=true 但 reviewPassed=false —— 公开待审不等于过审。 */
    @Test
    void publishResultSeparatesPublicFromPassedReview() {
        CmsTopic topic = topic(1, 1, 0);
        topic.setLifecycle(TopicLifecycleState.PRICING.getCode());
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);

        TopicBundleSubmitResultVO result = service.noBundleReview(TOPIC_ID, "NOT_REQUIRED");

        assertTrue(result.isPublished(), "玩家看得见");
        assertFalse(result.isReviewPassed(), "但还没过审");
        assertTrue(result.isPublicVisible());
        assertNull(result.getPublicReason());
        assertFalse(result.isSaleOpen());
        assertEquals("请在定价工作台保存完整开售参数，保存后自动提交开售审核", result.getCommercialNextStep());
        assertEquals(result.getCommercialNextStep(), result.getPublicationStatus().getSale().getNextStep(),
                "发布结果与状态投影必须是同一份可售原因");
        assertEquals(0, result.getCheckStatus());
        assertEquals(topic.getConfigVersion(), result.getReviewConfigVersion());
    }
    /**
     * simple / ai_simple 没有豁免:{@code currentScope} 对所有 routeMode 都要求
     * {@code publishedConfigVersion>0}(或本局已存在),所以没版本就不能开局。
     *
     * <p>负控:把 snapshotPublished 加回 {@code isSimplePublishMode → true} 的豁免,本用例红。
     */
    @Test
    void simpleTopicWithoutPublishedVersionIsNotPlayable() {
        for (String mode : new String[]{"simple", "ai_simple"}) {
            CmsTopic topic = topic(1, 1, 1);
            topic.setPublishMode(mode);
            topic.setPublishedConfigVersion(null);
            when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);

            TopicPublicationStatusVO status = service.publicationStatus(topic);

            assertTrue(status.getVisibility().isVisible(), mode + ": 看得见是另一条事实");
            assertFalse(status.getPlayable().isPlayable(), mode + ": 没有已发布版本不能开局");
            assertEquals("路线尚未发布", status.getPlayable().getReason());
        }
    }

    /** 反过来:simple 主题一旦有已发布版本就可开局 —— 判据是版本事实,不是 publishMode。 */
    @Test
    void simpleTopicWithPublishedVersionIsPlayable() {
        CmsTopic topic = topic(1, 1, 1);
        topic.setPublishMode("simple");
        topic.setPublishedConfigVersion(2L);
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);

        TopicPublicationStatusVO status = service.publicationStatus(topic);

        assertTrue(status.getPlayable().isPlayable());
        assertNull(status.getPlayable().getReason());
    }

    /** 分支主题只发布了一半(有版本、没有图):按运行期同一句拒绝,不能算可玩。 */
    @Test
    void branchTopicWithoutPublishedGraphIsNotPlayable() {
        CmsTopic topic = topic(1, 1, 1);
        topic.setRouteMode("BRANCH_GRAPH");
        topic.setPublishedConfigVersion(2L);
        topic.setPublishedRouteGraphJson(null);
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);

        TopicPublicationStatusVO status = service.publicationStatus(topic);

        assertFalse(status.getPlayable().isPlayable());
        assertEquals("路线尚未发布", status.getPlayable().getReason(),
                "与运行期 ensureBranchSession 的拒绝文案同一句");
    }

    /**
     * 作者内容就绪 与 玩家开局许可 是两件事:
     * 已发布但已下架的主题 → visibility.visible=false,而 playable(内容侧快照)仍为 true。
     *
     * <p>负控:把 playable 写回 {@code visible && snapshotPublished(...)},本用例红。
     */
    @Test
    void publishedButUnlistedTopicKeepsSnapshotReadinessSeparateFromVisibility() {
        CmsTopic topic = topic(1, 0, 1);
        topic.setPublishedConfigVersion(4L);
        when(topicMapper.selectTopicIsDraft(TOPIC_ID)).thenReturn(0);

        TopicPublicationStatusVO status = service.publicationStatus(topic);

        assertFalse(status.getVisibility().isVisible(), "已下架:新玩家看不到");
        assertEquals("已下架，玩家看不到", status.getVisibility().getReason());
        assertTrue(status.getPlayable().isPlayable(), "内容侧快照就绪与是否上架是两条事实");
    }

    /**
     * 结构证明:发布域拿不到报名/权益/可玩时段依赖,所以它<b>不可能</b>在状态投影里替玩家判开局许可。
     * 那两件事分别由运行期 {@code CurrentPlayRegistration.require} 与
     * {@code TopicPlaySetupService.requireOpen} 在开局时判。
     *
     * <p>负控:给 TopicPublicationService 注入报名或可玩时段服务,本用例红。
     */
    @Test
    void publicationOwnerHasNoEntitlementOrPlayWindowDependency() {
        for (java.lang.reflect.Field field : TopicPublicationService.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            String name = type.getName();
            assertFalse(name.contains("Registration"), "发布域不得依赖报名:" + field.getName());
            assertFalse(name.contains("TopicPlaySetup"), "发布域不得依赖可玩时段:" + field.getName());
            assertFalse(name.contains("PlayerActivityRole"), "发布域不得依赖权益/角色:" + field.getName());
        }
    }

    private static void assertThrowsIllegalState(CmsTopic topic) {
        try {
            SaleAvailabilityPolicy.assertSellable(topic, null, new Date());
            throw new AssertionError("过期售卖窗必须被售卖闸拒绝");
        } catch (IllegalStateException expected) {
            assertEquals("saleEndAt 已到，自玩通行证不可再售", expected.getMessage());
        }
    }
    /** 开售时间已过(售止窗内可售)。 */
    private static Date withinSellingWindow() {
        return new Date(System.currentTimeMillis() - 3600_000L);
    }

    /** 售止远在未来。 */
    private static Date farFuture() {
        return new Date(System.currentTimeMillis() + 86_400_000L);
    }

    private static CmsTopic topic(int status, int userStatus, int checkStatus) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(MEMBER_ID);
        topic.setName("寒冬");
        topic.setPublishMode("pro");
        topic.setStatus(status);
        topic.setUserStatus(userStatus);
        topic.setCheckStatus(checkStatus);
        topic.setLifecycle(TopicLifecycleState.DRAFT.getCode());
        topic.setConfigVersion(11L);
        return topic;
    }

    private static Date at(String iso) {
        return Date.from(Instant.parse(iso));
    }
}

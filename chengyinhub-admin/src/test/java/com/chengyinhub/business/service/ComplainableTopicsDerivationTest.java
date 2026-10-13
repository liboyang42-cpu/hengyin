package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.ComplainableTopicVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「哪些主题可投诉」的**归一判据**测试。
 *
 * <p><b>为什么这些用例在这里而不是在 controller 测试里</b>(2026-08-18 并入 master #738 时搬的):
 * 判据原先手写在 {@code ApiCoopController.complaintReport} 里,#738 把它提到
 * {@code ICmsRegistrationService.selectComplainableTopics},让「投诉页的选择源」与「受理口的准入」
 * 共用同一份。判据搬了家,钉它的测试也得跟着搬 —— 留在 controller 上就会变成
 * 「stub 了新 seam 返回空 ⇒ 所有拒绝用例恒绿」的假绿(这次合并里实测到 8 条正是这样)。
 *
 * <p>钉住的四条与原先一字不差:owner1 用 ownerId、owner2 顺活动溯源、未支付不进集合、
 * 反查不出主题一律 fail-closed 不兜底。
 */
class ComplainableTopicsDerivationTest {

    private static final Long MEMBER_ID = 41L;
    private static final Long TOPIC_ID = 91L;
    private static final Long ACTIVITY_ID = 191L;
    private static final Long REGISTRATION_ID = 291L;

    private CmsRegistrationMapper registrationMapper;
    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;
    private CmsRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        registrationMapper = mock(CmsRegistrationMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(TOPIC_ID, "外滩夜行"));
    }

    /** ★ 只捞已支付的单:未支付/已退款(payment_status=4)由 SQL 层的 payment_status=2 排除。 */
    @Test
    void onlyPaidRegistrationsAreQueried() {
        when(registrationMapper.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());

        service.selectComplainableTopics(MEMBER_ID);

        ArgumentCaptor<CmsRegistration> probe = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrationMapper).selectCmsRegistrationList(probe.capture());
        assertEquals(MEMBER_ID, probe.getValue().getMemberId(), "只能查本人的单");
        assertEquals(Integer.valueOf(2), probe.getValue().getPaymentStatus(),
                "必须按 payment_status=2 收窄 —— 未支付/已退款的人不该能投诉去冻结别人的结算");
    }

    /** owner2 活动壳单:顺 cms_activity.topic_id 溯源到主题,这正是 ③ 自由探索今天在用的售票路径。 */
    @Test
    void paidOwner2ActivityTicketResolvesToItsSourceTopic() {
        when(registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(registration(2L, ACTIVITY_ID)));
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity(TOPIC_ID));

        List<ComplainableTopicVO> topics = service.selectComplainableTopics(MEMBER_ID);

        assertEquals(1, topics.size());
        assertEquals(TOPIC_ID, topics.get(0).getTopicId());
        assertEquals("外滩夜行", topics.get(0).getTopicName());
        assertEquals(REGISTRATION_ID, topics.get(0).getRegistrationId());
    }

    /** owner1 主题单:ownerId 就是 topicId。 */
    @Test
    void owner1UsesOwnerIdAsCanonicalTopic() {
        when(registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(registration(1L, TOPIC_ID)));

        List<ComplainableTopicVO> topics = service.selectComplainableTopics(MEMBER_ID);

        assertEquals(1, topics.size());
        assertEquals(TOPIC_ID, topics.get(0).getTopicId());
        verify(topicMapper).selectCmsTopicById(TOPIC_ID);
    }

    /**
     * ★ fail-closed:反查不出主题的单一律不进集合,绝不兜底成 ownerId。
     *
     * <p>cms_topic 与 cms_activity 是两条独立自增序列、id 区间大量重叠 ⇒ 兜底就会让玩家
     * 投诉到一个 id 恰好撞上的**别的主题**,而受理即冻结那个主题的结算放款。
     */
    @Test
    void unresolvableRegistrationsAreFailClosedNotFallenBackToOwnerId() {
        // ① 独立活动(topic_id 为空 = 平台自办)
        when(registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(registration(2L, ACTIVITY_ID)));
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity(null));
        assertTrue(service.selectComplainableTopics(MEMBER_ID).isEmpty(), "独立活动不可投诉");

        // ② 活动压根查不到
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(null);
        assertTrue(service.selectComplainableTopics(MEMBER_ID).isEmpty(), "查不到活动即 fail-closed");

        // ③ ownerId 缺失
        CmsRegistration noOwner = registration(2L, null);
        when(registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(noOwner));
        assertTrue(service.selectComplainableTopics(MEMBER_ID).isEmpty(), "ownerId 缺失即 fail-closed");
    }

    /** 一条反查不出来的单不能中断扫描 —— 后面那张能反查出主题的单仍要进集合。 */
    @Test
    void oneUnresolvableRegistrationDoesNotStopScanningTheRest() {
        CmsRegistration standalone = registration(2L, ACTIVITY_ID + 1);
        standalone.setId(REGISTRATION_ID + 1);
        when(registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(Arrays.asList(standalone, registration(2L, ACTIVITY_ID)));
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID + 1)).thenReturn(null);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity(TOPIC_ID));

        List<ComplainableTopicVO> topics = service.selectComplainableTopics(MEMBER_ID);

        assertEquals(1, topics.size());
        assertEquals(TOPIC_ID, topics.get(0).getTopicId());
        assertEquals(REGISTRATION_ID, topics.get(0).getRegistrationId(), "带回来的必须是那张能反查出主题的单");
    }

    /** 同一主题多张单只保留先到的那张,避免下拉里出现两个同名项。 */
    @Test
    void sameTopicIsDeduplicatedKeepingTheFirstRegistration() {
        CmsRegistration second = registration(1L, TOPIC_ID);
        second.setId(REGISTRATION_ID + 5);
        when(registrationMapper.selectCmsRegistrationList(any()))
                .thenReturn(Arrays.asList(registration(1L, TOPIC_ID), second));

        List<ComplainableTopicVO> topics = service.selectComplainableTopics(MEMBER_ID);

        assertEquals(1, topics.size());
        assertEquals(REGISTRATION_ID, topics.get(0).getRegistrationId());
    }

    @Test
    void anonymousOrNullMemberGetsEmptySetWithoutQuerying() {
        assertTrue(service.selectComplainableTopics(null).isEmpty());
        assertTrue(service.selectComplainableTopics(0L).isEmpty());
        verify(registrationMapper, org.mockito.Mockito.never()).selectCmsRegistrationList(any());
    }

    private CmsRegistration registration(Long ownerType, Long ownerId) {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(REGISTRATION_ID);
        registration.setMemberId(MEMBER_ID);
        registration.setOwnerType(ownerType);
        registration.setOwnerId(ownerId);
        registration.setPaymentStatus(2);
        return registration;
    }

    private CmsActivity activity(Long topicId) {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTopicId(topicId);
        return activity;
    }

    private CmsTopic topic(Long id, String name) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        topic.setName(name);
        return topic;
    }
}

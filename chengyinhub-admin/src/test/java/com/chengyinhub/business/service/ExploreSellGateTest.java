package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-50 ③ 开卖闸 —— <b>行为测试,不是源码断言</b>。
 *
 * <p><b>为什么必须是行为测试</b>:这道闸我放错层放了两次,两次都是「源码里写着、路径上走不到」:
 * <ol>
 *   <li>第一次放在**建题**路径(「③ 必须传 recruitDeadline」)—— 招募失败会把 lifecycle 推回 0,
 *       而 ③ 的 accepted 恒为 0(它数 CoopInvite,③ 的供给却是 merchant_chapter_offer,
 *       后者还没有 insert 调用点)⇒ 闸只把开卖推迟十分钟。</li>
 *   <li>第二次放在 {@code validateAndGetTopic}(ownerType=1 分支)—— 但活动单(ownerType=2)
 *       走的是 {@code validateAndGetActivity},**它不看 lifecycle 也不看 product_type**,
 *       而活动能挂 topicId 指向 ③ 主题 ⇒ ③ 的票从活动这条路照卖。
 *       更糟:{@code ApiRegistrationController:199-222} 把 ownerType==1 无条件 early-return 进
 *       createSelfPlayPass ⇒ **那个 else 分支从 API 根本不可达**,活动这条才是在用的路径。</li>
 * </ol>
 * 两次的 5 条负控全绿、源码断言全绿,**洞照样在** —— 因为负控证明的是「删了闸会红」,
 * 不是「闸在路径上」。{@code RevshareGateWiringTest} 那种 {@code s.contains("...")}
 * 守不住这个,它把「算得对 ≠ 有人调用」的陷阱只往上挪了一层就停了。
 *
 * <p><b>所以这一类必须真的把方法调进去。</b>
 *
 * ⚠️ 负控已实测:
 *  - **删活动分支的 assertExploreSellable(= 退回上一版那个洞)⇒ activityCarryingExploreTopic_rejected 红**
 *    —— 这是本类存在的理由:上一版 5 条负控 + 全部源码断言都绿,却没抓到它。
 *  - 闸条件放宽成 lifecycle != 0 也放行 ⇒ 红(lifecycle=0 是生产常态,17 个主题全是它)
 *
 * ⚠️ <b>诚实的覆盖缺口</b>:删 {@code validateAndGetTopic} 里那条 ⇒ 本类**不红**。
 * 因为 {@code ApiRegistrationController:199-222} 把 ownerType==1 无条件 early-return 进
 * createSelfPlayPass ⇒ 那条分支从 API 不可达,行为测试够不到它。它由
 * {@link RevshareGateWiringTest} 的源码断言兜着 —— **那是弱一档的保护,别当它等价**。
 */
class ExploreSellGateTest {

    private static final Long TOPIC = 700L;
    private static final Long ACTIVITY = 800L;

    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;
    private CmsRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
    }

    private CmsTopic topic(Integer productType, Integer lifecycle) {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setName("测试主题");
        t.setProductType(productType);
        t.setLifecycle(lifecycle);
        t.setStatus(1);
        t.setUserStatus(1);
        t.setDelFlag(0);
        return t;
    }

    private void givenActivityCarrying(Long topicId) {
        CmsActivity a = new CmsActivity();
        a.setId(ACTIVITY);
        a.setName("测试活动");
        a.setTopicId(topicId);
        a.setStatus(1L);
        a.setPublishStatus(1L);
        a.setDelFlag(0);
        a.setEndDate(new Date(System.currentTimeMillis() + 86400_000L));
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(a);
    }

    /** 直接调私有闸 —— 它是两条分支的公共承重点,先把它本身的契约钉死。 */
    private void gate(CmsTopic t) {
        ReflectionTestUtils.invokeMethod(service, "assertExploreSellable", t);
    }

    // ------------------------------------------------- 闸本身的契约

    /** ③ 未锁价 ⇒ 拒。lifecycle=0 是生产实测的常态(17 个主题全是 0)。 */
    @Test
    void explore_notLocked_rejected() {
        for (Integer lc : new Integer[]{null, 0, 1, 2}) {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> gate(topic(ProductType.FREE_EXPLORE, lc)));
            assertTrue(e.getMessage().contains("暂未开售"), "lifecycle=" + lc + " 实际:" + e.getMessage());
        }
    }

    /** ③ 已锁价 ⇒ 放行(四道闸都在 confirmPrice 里跑过了)。 */
    @Test
    void explore_locked_passes() {
        gate(topic(ProductType.FREE_EXPLORE, 3));
    }

    /** ①(经典定向)不受本闸约束 —— 现网 17 个主题全是它,改了就是越界。 */
    @Test
    void orienteering_untouched() {
        for (Integer lc : new Integer[]{null, 0, 1, 2, 3}) {
            gate(topic(ProductType.ORIENTEERING, lc));
        }
    }

    /** product_type 为 null(存量未回填 / 独立活动)⇒ 走原口径,别拿它赌。 */
    @Test
    void nullProductType_untouched() {
        gate(topic(null, 0));
    }

    // ------------------------------------------------- ★路径可达性:真的调进去

    /**
     * <b>★这条是本类的心脏</b>:活动挂着 ③ 主题时,买活动票必须被拒。
     *
     * <p>这正是上一版漏掉的路径 —— 闸只写在 ownerType=1 分支里,而活动单走的是另一条。
     * 源码断言看不出这个,只有真的把 createRegistration 调进去才看得出。
     */
    @Test
    void activityCarryingExploreTopic_rejected() {
        givenActivityCarrying(TOPIC);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(ProductType.FREE_EXPLORE, 0));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createRegistration(1L, 2, ACTIVITY, 9L, 0, "张三", "13800000000", null, null));
        assertTrue(e.getMessage().contains("暂未开售"),
                "活动挂 ③ 主题时必须被闸拦 —— 否则 ③ 的票从活动这条路照卖,而它是今天在用的路径。实际:"
                        + e.getMessage());
    }

    /** 活动挂着已锁价的 ③ ⇒ 过闸(会因为后续校验失败,但**不能是「暂未开售」**)。 */
    @Test
    void activityCarryingLockedExplore_passesGate() {
        givenActivityCarrying(TOPIC);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(ProductType.FREE_EXPLORE, 3));

        try {
            service.createRegistration(1L, 2, ACTIVITY, 9L, 0, "张三", "13800000000", null, null);
        } catch (Exception e) {
            assertTrue(!String.valueOf(e.getMessage()).contains("暂未开售"),
                    "已锁价的 ③ 不该被开卖闸拦。实际:" + e.getMessage());
        }
    }

    /** 独立活动(不挂 topicId)⇒ 无主题真源,闸不该误伤它。 */
    @Test
    void standaloneActivity_notBlockedByGate() {
        givenActivityCarrying(null);
        try {
            service.createRegistration(1L, 2, ACTIVITY, 9L, 0, "张三", "13800000000", null, null);
        } catch (Exception e) {
            assertTrue(!String.valueOf(e.getMessage()).contains("暂未开售"),
                    "独立活动没有主题真源,不该被 ③ 闸拦。实际:" + e.getMessage());
        }
    }

    /** 活动挂着 ①(经典定向)主题 ⇒ 不受影响。 */
    @Test
    void activityCarryingOrienteering_notBlocked() {
        givenActivityCarrying(TOPIC);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(ProductType.ORIENTEERING, 0));

        try {
            service.createRegistration(1L, 2, ACTIVITY, 9L, 0, "张三", "13800000000", null, null);
        } catch (Exception e) {
            assertTrue(!String.valueOf(e.getMessage()).contains("暂未开售"),
                    "① 不该被 ③ 闸拦 —— 现网 17 个主题全是它。实际:" + e.getMessage());
        }
    }
}

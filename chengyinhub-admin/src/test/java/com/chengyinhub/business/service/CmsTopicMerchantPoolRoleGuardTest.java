package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 招商资格闸:**只有俱乐部主理人能开商家池**(2026-08-11 用户拍板)。
 *
 * <p><b>为什么后端也要有这道闸</b>:小程序 step3.wxml 已按 {@code merchantPoolEditable} 隐藏开关,
 * 但「不渲染」≠「值变 0」—— 草稿复制与编辑回填都能把旧的 {@code openMerchantPool=1}
 * 带回上送体;构造请求更是直接绕过前端。改动前 {@code merchant_status} 全仓零身份校验:
 * 任何登录会员发个主题就能开商家池招商,而商家分润确实会入账(coop_settlement → 个人余额),
 * 平台却从没审过这个发布者有没有履约能力。
 *
 * <p><b>商家为什么也不放行</b>:定稿 D18 的「商家 ✅ 自发自招商」= 商家自己同时占内容槽与供给槽,
 * 而 {@code merchant_status=1} 是**开池让别的商家报名**,没有定稿条款授权。
 *
 * <p>⚠️ 负控(每条分别摘、分别验,别只验一条就当整道闸绿):
 * <ul>
 *   <li>删掉 {@code if (!isClubLeader) throw} ⇒ {@code plainPlayerCannotOpenMerchantPool}
 *       与 {@code disabledClubLeaderCannotOpenMerchantPool} 红</li>
 *   <li>把 status 判断写成 {@code status == 1}(不认 null)⇒ {@code clubLeaderMayOpenMerchantPool} 红</li>
 *   <li>删掉「非 1 直接返回 0」的提前返回 ⇒ {@code closedSwitchNeverTriggersRoleCheck} 红</li>
 *   <li>删掉 createTopicInternal / rebuildTopicContent 里任一调用行 ⇒
 *       {@code gateIsWiredIntoBothWritePaths} 红</li>
 * </ul>
 */
class CmsTopicMerchantPoolRoleGuardTest {

    private static final String TOPIC_SERVICE =
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java";

    private static final Long MEMBER = 9001L;

    /** 该会员没有 club_leader 档(与「有档但 status 为空」是两种不同情形,别混用 null 表达)。 */
    private static final Integer NO_LEADER_RECORD = null;

    /**
     * @param leaderStatus club_leader 档的 status;{@code hasLeaderRecord=false} 时忽略
     */
    private Integer invokeGate(Integer openMerchantPool, boolean hasLeaderRecord, Integer leaderStatus) {
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));

        IClubLeaderService leaders = mock(IClubLeaderService.class);
        ClubLeader leader = null;
        if (hasLeaderRecord) {
            leader = new ClubLeader();
            leader.setStatus(leaderStatus);   // null = 档在但 status 空,既有口径按生效算
        }
        when(leaders.selectClubLeaderByMemberId(anyLong())).thenReturn(leader);
        ReflectionTestUtils.setField(service, "clubLeaderService", leaders);

        return ReflectionTestUtils.invokeMethod(
                service, "resolveMerchantPoolFlag", openMerchantPool, MEMBER);
    }

    private void assertRejectedByThisGate(Executable call) {
        IllegalStateException error = assertThrows(IllegalStateException.class, call::run);
        // 断言到具体这道闸:同一条写入路径上相邻的招募/俱乐部闸抛的是同型异常,
        // 只断 assertThrows 会被相邻那道接住(见 feedback-assert-which-gate-not-just-that-it-threw)。
        assertTrue(error.getMessage().contains("商家招商"),
                "必须是招商资格这道闸拦下的,而不是相邻的闸:" + error.getMessage());
    }

    private interface Executable {
        void run();
    }

    /** 纯玩家:没有 club_leader 档 ⇒ 想开就当场拒。 */
    @Test
    void plainPlayerCannotOpenMerchantPool() {
        assertRejectedByThisGate(() -> invokeGate(1, false, NO_LEADER_RECORD));
    }

    /** 主理人:status=1 与 status 为空都按生效算(与 ApiTopicController 经典定向门禁同口径)。 */
    @Test
    void clubLeaderMayOpenMerchantPool() {
        assertEquals(1, invokeGate(1, true, 1));
        assertEquals(1, invokeGate(1, true, null));
    }

    /** 停用的主理人档(status=0)不算资格 —— 否则注销主理人后仍能招商。 */
    @Test
    void disabledClubLeaderCannotOpenMerchantPool() {
        assertRejectedByThisGate(() -> invokeGate(1, true, 0));
    }

    /**
     * 开关关着时**不做身份查询也不报错** —— 否则纯玩家连一个不招商的主题都存不下来,
     * 且存量主题一编辑就炸。
     */
    @Test
    void closedSwitchNeverTriggersRoleCheck() {
        assertDoesNotThrow(() -> {
            assertEquals(0, invokeGate(0, false, NO_LEADER_RECORD));
            assertEquals(0, invokeGate(null, false, NO_LEADER_RECORD));
        });
    }

    /**
     * 闸算得对 ≠ 有人调用。建题与改题是两条独立写入路径,漏一条就等于没有这道闸。
     */
    @Test
    void gateIsWiredIntoBothWritePaths() throws Exception {
        String flat = WiringSource.read(TOPIC_SERVICE).replaceAll("\\s+", "");
        String call = "resolveMerchantPoolFlag(dto.getOpenMerchantPool(),memberId)";
        int first = flat.indexOf(call);
        assertTrue(first > 0, "建题必须调这道闸 —— 删掉它编译过、其余测试全绿、闸消失");
        assertTrue(flat.indexOf(call, first + 1) > 0,
                "改题也要调 —— 只挂一条,另一条就是没闸的入口");
        assertTrue(flat.indexOf("setMerchantStatus(dto.getOpenMerchantPool()") < 0,
                "不许再有绕开闸直接落值的写法");
    }
}

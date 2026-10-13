package com.chengyinhub.business.service;

import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * spec §2.1 发布流第三步开关:「邀请俱乐部带队」只属于城市定向。
 *
 * <p><b>为什么后端也要有这道闸</b>:小程序在自由探索下已经不渲染这个开关(step3.wxml),
 * 但「不渲染」≠「值变 0」—— 主题从城市定向改成自由探索时,{@code open_club_pool=1}
 * 会随编辑回填一路带回上送体。落库成 1 之后 {@code CoopClubApplyServiceImpl} 的
 * {@code openClubPool != 1 → 拒} 就会放俱乐部进来申请承接一条本不该有带队方的主题:
 * 开关关着(看不见),后端仍按开着算。
 *
 * <p>⚠️ 负控已实测,两次输出贴在 PR 描述里:
 * <ul>
 *   <li>把闸里的 {@code ProductType.ORIENTEERING} 改成 {@code FREE_EXPLORE}
 *       ⇒ {@code cityOrienteeringMayOpenClubLead} + {@code freeExploreCannotOpenClubLead} 红</li>
 *   <li>删掉 createTopic / updateTopic 里的调用行 ⇒ {@code gateIsWiredIntoBothWritePaths} 红</li>
 * </ul>
 */
class CmsTopicClubLeadSwitchGuardTest {

    private static final String TOPIC_SERVICE =
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java";

    /** 城市定向=1、自由探索=2(com.chengyinhub.business.constant.ProductType)。 */
    private static final int CITY_ORIENTEERING = 1;
    private static final int FREE_EXPLORE = 2;

    private void invokeGate(Integer openClubPool, Integer productType) {
        ReflectionTestUtils.invokeMethod(new CmsTopicServiceImpl(),
                "assertClubLeadOnlyForCityOrienteering", openClubPool, productType);
    }

    @Test
    void freeExploreCannotOpenClubLead() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> invokeGate(1, FREE_EXPLORE));
        // 断言到具体这道闸:同一批写入里 assertChapterRecruitmentOnlyForFreeExplore 抛的是同型异常,
        // 只断 assertThrows 会被相邻那道闸接住。
        assertTrue(error.getMessage().contains("俱乐部带队"),
                "必须是「邀请俱乐部带队」这道闸拦下的,而不是相邻的章节招商闸:" + error.getMessage());
    }

    @Test
    void cityOrienteeringMayOpenClubLead() {
        assertDoesNotThrow(() -> invokeGate(1, CITY_ORIENTEERING));
    }

    /** 关着的开关在任何主题类型下都不该被拦 —— 否则存量自由探索主题一编辑就存不下来。 */
    @Test
    void closedSwitchIsNeverBlocked() {
        assertDoesNotThrow(() -> invokeGate(0, FREE_EXPLORE));
        assertDoesNotThrow(() -> invokeGate(null, FREE_EXPLORE));
    }

    /**
     * 闸算得对 ≠ 有人调用。建题与改题是两条独立写入路径,漏一条就等于没有这道闸。
     */
    @Test
    void gateIsWiredIntoBothWritePaths() throws Exception {
        String flat = WiringSource.read(TOPIC_SERVICE).replaceAll("\\s+", "");
        String call = "assertClubLeadOnlyForCityOrienteering(dto.getOpenClubPool(),productType)";
        int first = flat.indexOf(call);
        assertTrue(first > 0, "createTopic/updateTopic 必须调这道闸 —— 删掉它编译过、其余测试全绿、闸消失");
        assertTrue(flat.indexOf(call, first + 1) > 0,
                "建题与改题两条写入路径都要调 —— 只挂一条,另一条就是没闸的入口");
    }
}

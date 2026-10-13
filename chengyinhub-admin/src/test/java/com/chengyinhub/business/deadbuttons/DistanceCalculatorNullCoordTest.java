package com.chengyinhub.business.deadbuttons;

import com.chengyinhub.common.utils.DistanceCalculatorUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D3:坐标缺失时 {@code Double.parseDouble(null)} 抛的是 NullPointerException,
 * 而 catch 只接住 NumberFormatException ⇒ 未受保护的 NPE 顺着
 * {@code CmsTopicServiceImpl.calculateTotalDistance} 冒到顶,整次建题失败,
 * 客户端只看到「主题发布失败,请稍后重试」,不指出是哪个节点缺哪个字段。
 *
 * <p>为什么修在这个共享方法而不是各个调用点:三个调用点
 * ({@code calculateTotalDistance / calculateCrossChapterDistance})全部路过这里,
 * 一道守卫比三道少。
 *
 * <p>为什么是「当 0 公里」而不是「抛业务异常」:这个方法对无法解析的字符串本来就返回 0.0
 * (总里程只是展示统计,不参与钱);null 与 "abc" 在语义上是同一件事 —— 这个点没有可用坐标。
 * 真需要坐标的路径另有硬校验({@code assertAiSimpleNodeLocations}「第N个地点尚未在地图确认」),
 * 不靠这里代偿。
 */
class DistanceCalculatorNullCoordTest {

    private final DistanceCalculatorUtils utils = new DistanceCalculatorUtils();

    @Test
    void nullCoordinate_returnsZero_insteadOfThrowingNpe() {
        assertEquals(0.0, utils.calculateDistance(null, "31.2", "121.5", "31.3"), 0.0);
        assertEquals(0.0, utils.calculateDistance("121.4", null, "121.5", "31.3"), 0.0);
        assertEquals(0.0, utils.calculateDistance("121.4", "31.2", null, "31.3"), 0.0);
        assertEquals(0.0, utils.calculateDistance("121.4", "31.2", "121.5", null), 0.0);
        assertEquals(0.0, utils.calculateDistance(null, null, null, null), 0.0);
    }

    /** 空串/空白与不可解析串走的是同一条兜底,顺手钉住,免得下一个人改守卫时把它们放行成 NaN。 */
    @Test
    void blankAndGarbageCoordinate_stillReturnZero() {
        assertEquals(0.0, utils.calculateDistance("", "31.2", "121.5", "31.3"), 0.0);
        assertEquals(0.0, utils.calculateDistance("  ", "31.2", "121.5", "31.3"), 0.0);
        assertEquals(0.0, utils.calculateDistance("abc", "31.2", "121.5", "31.3"), 0.0);
    }

    /** 负控的另一半:守卫不许把正常坐标也一起吞成 0。 */
    @Test
    void realCoordinates_stillComputeDistance() {
        double km = utils.calculateDistance("121.4", "31.2", "121.5", "31.3");
        org.junit.jupiter.api.Assertions.assertTrue(km > 10 && km < 20,
                "上海市区两点约 14km,实际:" + km);
    }
}

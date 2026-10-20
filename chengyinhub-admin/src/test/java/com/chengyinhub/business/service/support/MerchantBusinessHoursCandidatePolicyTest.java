package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 商家经营时间编辑器的“次日”格式必须能被真实路线候选策略读取。 */
class MerchantBusinessHoursCandidatePolicyTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String REQUIRE_OPEN = "{\"nodeRequirements\":[{\"nodeId\":2,\"requireOpen\":true}]}";

    @ParameterizedTest(name = "{0}")
    @MethodSource("businessHoursCases")
    void merchantHoursProduceAuthoritativeCandidateStatus(String name, String hours,
                                                          String beijingTime,
                                                          TopicRouteCandidatePolicy.Status expected) {
        TopicRouteCandidatePolicy.Evaluation result = evaluate(hours, beijingTime, REQUIRE_OPEN);

        assertEquals(expected, result.getNodeStatuses().get(2L), name);
        assertEquals(expected != TopicRouteCandidatePolicy.Status.AVAILABLE,
                result.getUnavailableNodeIds().contains(2L), "候选排除集合必须与判定一致");
        if (expected == TopicRouteCandidatePolicy.Status.AVAILABLE) {
            assertTrue(result.getReasonCodes().get(2L).isEmpty());
        } else {
            String reason = expected == TopicRouteCandidatePolicy.Status.UNKNOWN
                    ? "BUSINESS_TIME_UNKNOWN" : "OUTSIDE_BUSINESS_HOURS";
            assertEquals(Collections.singletonList(reason), result.getReasonCodes().get(2L));
        }
    }

    static Stream<Arguments> businessHoursCases() {
        return Stream.of(
                available("全周次日格式在晚间开放", "周一至周日 21:00-次日02:00", "2026-10-09T23:30"),
                available("全周次日格式在午夜开放", "周一至周日 21:00-次日02:00", "2026-10-10T00:00"),
                available("开门分钟沿用包含边界", "周五 21:00-次日02:00", "2026-10-09T21:00"),
                unavailable("开门前一分钟关闭", "周五 21:00-次日02:00", "2026-10-09T20:59"),
                available("关门分钟沿用包含边界", "周五 21:00-次日02:00", "2026-10-10T02:00"),
                unavailable("关门后一分钟关闭", "周五 21:00-次日02:00", "2026-10-10T02:01"),
                available("周六凌晨属于周五营业日", "周五 21:00-次日02:00", "2026-10-10T00:30"),
                unavailable("周五凌晨不能提前算周五晚间", "周五 21:00-次日02:00", "2026-10-09T00:30"),
                unavailable("未选周六晚间保持关闭", "周五 21:00-次日02:00", "2026-10-10T23:30"),
                available("星期日跨周到周一", "周日 21:00-次日02:00", "2026-10-12T00:30"),
                available("编辑器多营业日格式", "周一、三、五 21:00-次日02:00", "2026-10-10T01:30"),
                unavailable("编辑器多营业日的休息日", "周一、三、五 21:00-次日02:00", "2026-10-11T01:30"),
                available("跨周营业日区间", "周五至周一 21:00-次日02:00", "2026-10-13T01:30"),
                available("跨月凌晨归属上月营业日", "周六 22:30-次日01:15", "2026-11-01T00:30"),
                available("跨年凌晨归属前一年营业日", "周四 22:30-次日01:15", "2027-01-01T00:30"),
                available("结束时间为零点", "周五 23:00-次日00:00", "2026-10-10T00:00"),
                unavailable("零点结束后不继续开放", "周五 23:00-次日00:00", "2026-10-10T00:01"),
                available("旧纯时间跨夜格式继续兼容", "21:00-02:00", "2026-10-10T00:30"),
                available("旧星期跨夜格式继续兼容", "周五 21:00-02:00", "2026-10-10T00:30"),
                available("旧至分隔符继续兼容", "周五 21:00至02:00", "2026-10-10T00:30"),
                available("普通当日格式继续兼容", "周五 09:00-18:00", "2026-10-09T12:00"),
                unavailable("普通当日关闭保持关闭", "周五 09:00-18:00", "2026-10-09T18:01"),
                unknown("次日与当日时间矛盾", "周五 09:00-次日18:00", "2026-10-09T12:00"),
                unknown("次日相同时间不解释成二十四小时", "周五 21:00-次日21:00", "2026-10-09T21:00"),
                unknown("非法小时继续未知", "周五 24:00-次日02:00", "2026-10-10T00:30"),
                unknown("非法分钟继续未知", "周五 21:00-次日02:60", "2026-10-10T00:30"),
                unknown("未知星期继续未知", "周八 21:00-次日02:00", "2026-10-10T00:30"),
                unknown("重复次日标记继续未知", "周五 21:00-次日次日02:00", "2026-10-10T00:30"),
                unknown("文本尾随内容继续未知", "周五 21:00-次日02:00 以实际为准", "2026-10-10T00:30"),
                unknown("缺营业时间继续未知", null, "2026-10-10T00:30"));
    }

    @Test
    void nodesWithoutOpenRequirementRemainUnaffected() {
        TopicRouteCandidatePolicy.Evaluation result = evaluate("周五 09:00-次日18:00",
                "2026-10-09T12:00", "{}");
        assertEquals(TopicRouteCandidatePolicy.Status.AVAILABLE, result.getNodeStatuses().get(2L));
        assertTrue(result.getUnavailableNodeIds().isEmpty());
    }

    private static Arguments available(String name, String hours, String time) {
        return Arguments.of(name, hours, time, TopicRouteCandidatePolicy.Status.AVAILABLE);
    }

    private static Arguments unavailable(String name, String hours, String time) {
        return Arguments.of(name, hours, time, TopicRouteCandidatePolicy.Status.UNAVAILABLE);
    }

    private static Arguments unknown(String name, String hours, String time) {
        return Arguments.of(name, hours, time, TopicRouteCandidatePolicy.Status.UNKNOWN);
    }

    private TopicRouteCandidatePolicy.Evaluation evaluate(String hours, String beijingTime, String config) {
        Clock clock = Clock.fixed(LocalDateTime.parse(beijingTime).atZone(SHANGHAI).toInstant(), SHANGHAI);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(2L);
        node.setBusinessTime(hours);
        return new TopicRouteCandidatePolicy(clock, RouteReachabilityProvider.UNKNOWN)
                .evaluate(config, TopicRouteCandidatePolicy.Facts.unknown(), 5L, null,
                        Collections.singletonList(node));
    }
}

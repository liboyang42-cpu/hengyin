package com.chengyinhub.business.service;

import java.util.regex.Pattern;

/** 漫游结算回顾的文案边界：只能回看已发生的足迹，不得指引下一步。 */
public final class RoamRouteStoryPolicy {
    public static final String FORWARD_GUIDANCE_ERROR = "足迹回顾不得包含下次、建议或先后指引";

    private static final Pattern FORWARD_GUIDANCE = Pattern.compile(
            "下次|下一次|下回|接下来|下一站|建议|推荐|不妨|可以去|应该去|先[\\s\\S]{0,40}(再|然后)"
    );

    private RoamRouteStoryPolicy() {
    }

    public static boolean containsForwardGuidance(String text) {
        return text != null && FORWARD_GUIDANCE.matcher(text).find();
    }
}

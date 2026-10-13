package com.chengyinhub.business.service.support;

/** 资金类操作的双人复核约束。身份缺失时关闭流程，不能把 null 当成“不同的人”。 */
public final class MakerCheckerGuard {
    private MakerCheckerGuard() { }

    public static void requireActor(Object actor, String role) {
        if (actor == null || actor.toString().trim().isEmpty()) {
            throw new IllegalArgumentException(role + " identity is required");
        }
    }

    public static void requireDistinct(Object maker, Object checker) {
        requireActor(maker, "maker");
        requireActor(checker, "checker");
        if (maker.toString().trim().equals(checker.toString().trim())) {
            throw new IllegalStateException("复核人不能与提交人为同一人(双人复核)");
        }
    }
}

package com.chengyinhub.common.enums;

/**
 * 限流类型
 *
 * @author ruoyi
 */

public enum LimitType
{
    /**
     * 默认策略全局限流
     */
    DEFAULT,

    /**
     * 根据请求者IP进行限流
     */
    IP,

    /**
     * 根据小程序登录账号(memberId)进行限流。
     *
     * <p>X13:DEFAULT 是全局限流,给写接口挂上会让一个刷子拖垮所有人;
     * IP 在小程序场景下会被运营商 NAT 合并。滥用与成本的真实单位是账号,
     * 所以按 memberId 计数;未登录时降级为 IP。
     */
    MEMBER
}

package com.chengyinhub.web.service.play;

import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * 玩家基础命令的「拒绝可解释 + 原请求可恢复」回包契约。
 *
 * <p>为什么需要:被拒时客户端只拿到一句中文,无法机器判定下一步——是原样重发(网络/节流/定位漂移)、
 * 还是必须先走近、换新动作标识、刷新动作身份、按动作号查回执。全部基础命令(扫码/答题/GPS/传感器)
 * 共用本契约,避免每个入口自造字段。
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code errorCode} 稳定机器码,同一原因永不换值。既有 ARRIVE_FIRST /
 *       ARRIVE_ACCURACY_MISSING / ARRIVE_ACCURACY_INVALID / ARRIVE_GUARD_THROTTLE /
 *       ARRIVE_GUARD_SPEED / ARRIVE_GUARD_WINDOW_DISTANCE / ADVANCED_FIRST 保持原值,
 *       只在其上追加字段。</li>
 *   <li>{@code retryable} 原因消失后重发(可用新动作标识)可能成功:网络抖动、节流、定位漂移。
 *       false = 原样重发不会成功,须换输入 / 先完成前置 / 换新动作标识。</li>
 *   <li>{@code refreshRequired} 必须先重读动作身份(routeSessionId / expectedRouteVersion /
 *       runtimeInstance / authorityEpoch)再创建新意图。</li>
 *   <li>{@code intentNotExecuted=true} 仅出现在**可证明本次请求没有产生任何业务副作用**的回包上。
 *       缺省即未知:答错会记一次失误、传感器判定会占一次挑战、守卫拒绝会落守卫表 —— 这些路径
 *       一律不带该字段,客户端不得据此重发或当作未发生。</li>
 *   <li>{@code newActionIdRequired=true} 该 routeActionId 已被终态回执占用,重试必须换新的动作标识。</li>
 *   <li>{@code receiptState} 只反映**真实查到的回执**:UNKNOWN=按该动作号查无回执,
 *       PENDING=已占位未结算。两者都不等于失败,更不得升级成失败。</li>
 * </ul>
 *
 * <p>不变量:只加字段,不改既有 body code、不改既有 msg 文案、不放宽 50m 围栏与 80m 精度闸、
 * 不改幂等键(仍由 routeActionId 派生)与结算语义。回执重放**不加任何字段**:重放体与首次逐字一致。
 */
public final class PlayCommandErrorContract
{
    // ---- 定位 / 到达(50m 围栏与 80m 精度闸原值不动,这里只补可解释性) ----
    /** 客户端未带经纬度:多半是定位权限被拒,重新授权后可原样重发。 */
    public static final String ARRIVE_LOCATION_MISSING = "ARRIVE_LOCATION_MISSING";
    /** 经纬度非有限数 / 越界 / 站点坐标非法:重新定位后可重发。 */
    public static final String ARRIVE_LOCATION_INVALID = "ARRIVE_LOCATION_INVALID";
    /** 上报精度大于 80m:到开阔处重新定位后可重发。 */
    public static final String ARRIVE_ACCURACY_LOW = "ARRIVE_ACCURACY_LOW";
    /** 超 50m 围栏:走近后可重发。 */
    public static final String ARRIVE_TOO_FAR = "ARRIVE_TOO_FAR";
    /** 本局要求先经 GPS 记到达才能作答,而尚无到达事实。 */
    public static final String ARRIVE_NOT_RECORDED = "ARRIVE_NOT_RECORDED";
    /** vm=5 站点缺坐标:配置缺合同,扫码或找运营,重发无用。 */
    public static final String ARRIVE_COORD_NOT_CONFIGURED = "ARRIVE_COORD_NOT_CONFIGURED";
    /** 既有码:作答路径的「先到站」聚合拒绝,细分原因见 reasonCode。 */
    public static final String ARRIVE_FIRST = "ARRIVE_FIRST";
    /** 既有码:玩法/任务未通过就到达(保持原值)。 */
    public static final String ADVANCED_FIRST = "ADVANCED_FIRST";

    // ---- 意图 / 回执 ----
    /** 已有占位回执但未结算,或按该动作号查无回执:稍后按同一动作号查询,不要新建意图。 */
    public static final String INTENT_PENDING = "INTENT_PENDING";
    /** 期望版本 / 实例 / 权威版本已过期:刷新动作身份后重建意图。 */
    public static final String INTENT_STALE_STATE = "INTENT_STALE_STATE";
    /** 该动作号已用于另一份输入:必须换新的动作号。 */
    public static final String INTENT_ACTION_REUSED = "INTENT_ACTION_REUSED";
    /** 原局已结束:不能再写。 */
    public static final String INTENT_SESSION_ENDED = "INTENT_SESSION_ENDED";
    /** 请求携带的原局不属于当前玩家 / 活动 / 主题。 */
    public static final String INTENT_SESSION_MISMATCH = "INTENT_SESSION_MISMATCH";
    /** 无权读取该命令或本次游玩。 */
    public static final String INTENT_SCOPE_DENIED = "INTENT_SCOPE_DENIED";
    /** 动作标识 / 期望版本 / 运行时身份缺失或格式不合法。 */
    public static final String INTENT_INPUT_INVALID = "INTENT_INPUT_INVALID";
    /** 未登录。 */
    public static final String LOGIN_REQUIRED = "LOGIN_REQUIRED";

    // ---- 节点 / 任务前置 ----
    public static final String NODE_NOT_FOUND = "NODE_NOT_FOUND";
    public static final String NODE_DELISTED = "NODE_DELISTED";
    /** 该站须先完成对应任务(传感器 / 滤镜 / 答题)。 */
    public static final String TASK_REQUIRED = "TASK_REQUIRED";
    /** 该站只认店内扫码进店,GPS 不能替代。 */
    public static final String STORE_SCAN_REQUIRED = "STORE_SCAN_REQUIRED";
    public static final String CHAPTER_REDEEMED = "CHAPTER_REDEEMED";
    /** 配置缺合同(模板悬空 / 未配置答题 / 本局缺冻结到达配置)。 */
    public static final String CONFIG_INVALID = "CONFIG_INVALID";
    public static final String SCAN_CODE_INVALID = "SCAN_CODE_INVALID";
    public static final String SCAN_CODE_AMBIGUOUS = "SCAN_CODE_AMBIGUOUS";
    public static final String SCAN_CODE_USED = "SCAN_CODE_USED";
    public static final String SCAN_CODE_EXPIRED = "SCAN_CODE_EXPIRED";
    /** 扫到的节点与本次意图目标不一致。 */
    public static final String SCAN_CODE_NODE_MISMATCH = "SCAN_CODE_NODE_MISMATCH";
    /** 线性解锁未到 / 本章未放行。 */
    public static final String ROUTE_LOCKED = "ROUTE_LOCKED";
    /** 带领场:集合中不可推进,须队长放行。 */
    public static final String TEAM_LEAD_REQUIRED = "TEAM_LEAD_REQUIRED";
    /** 答案不正确(该动作号已终态,换答案必须换新动作号)。 */
    public static final String ANSWER_WRONG = "ANSWER_WRONG";
    /** 解谜分 / 揭示状态暂时读不到:重试即可,不是玩家错误。 */
    public static final String PUZZLE_STATE_UNAVAILABLE = "PUZZLE_STATE_UNAVAILABLE";
    /** App 传感器挑战判定未通过(如数据不匹配 / 已用过):须重新完成挑战。 */
    public static final String SENSOR_REJECTED = "SENSOR_REJECTED";

    private PlayCommandErrorContract()
    {
    }

    /**
     * 瞬时拒绝,且可证明本次请求未产生任何业务副作用(坐标解析、精度闸、围栏、节流)。
     * 原因消失后重发(可换新动作号)可能成功。
     */
    public static AjaxResult retryable(String message, String errorCode)
    {
        return rejected(message, errorCode).put("retryable", true);
    }

    /**
     * 瞬时拒绝,但本次请求是否已留下副作用无法从调用点证明。不声称 {@code intentNotExecuted}。
     */
    public static AjaxResult retryableUncertain(String message, String errorCode)
    {
        return unresolvedEffect(message, errorCode).put("retryable", true);
    }

    /**
     * 需要客户端先改变输入 / 先完成前置才能继续;原样重发不会成功。
     * 只用于可证明尚未产生业务副作用的路径(登录/参数/归属/节点查无/配置缺合同/解锁未到)。
     */
    public static AjaxResult rejected(String message, String errorCode)
    {
        return AjaxResult.error(message)
                .put("errorCode", errorCode)
                .put("retryable", false)
                .put("refreshRequired", false)
                .put("intentNotExecuted", true);
    }

    /**
     * 业务拒绝,但本次请求可能已写入事实(答错记失误、传感器占挑战、守卫落表)。
     * 因此不声称 {@code intentNotExecuted};是否终态由回执写入方决定。
     */
    public static AjaxResult unresolvedEffect(String message, String errorCode)
    {
        return AjaxResult.error(message)
                .put("errorCode", errorCode)
                .put("retryable", false)
                .put("refreshRequired", false);
    }

    /**
     * 动作身份冲突(409):期望版本 / 实例 / 权威版本已过期。
     */
    public static AjaxResult conflict(String message, String errorCode)
    {
        return new AjaxResult(409, message)
                .put("errorCode", errorCode)
                .put("retryable", false)
                .put("refreshRequired", false)
                .put("intentNotExecuted", true);
    }

    /**
     * 必须先刷新动作身份再创建新意图(409)。
     */
    public static AjaxResult refresh(String message, String errorCode)
    {
        return conflict(message, errorCode).put("refreshRequired", true);
    }

    /**
     * 回执已占位未结算(520):同一动作号稍后查询即可,不要新建意图。
     * 绝不标 {@code intentTerminal} —— 结算可能仍在进行。
     */
    public static AjaxResult pending(String message)
    {
        return new AjaxResult(520, message)
                .put("errorCode", INTENT_PENDING)
                .put("retryable", true)
                .put("refreshRequired", false)
                .put("receiptState", "PENDING");
    }

    /**
     * 按该动作号查无回执(520):结果未知,可稍后按同一动作号再查。
     * 未知不等于失败,不得升级成失败。
     */
    public static AjaxResult unresolved(String message)
    {
        return new AjaxResult(520, message)
                .put("errorCode", INTENT_PENDING)
                .put("retryable", true)
                .put("refreshRequired", false)
                .put("receiptState", "UNKNOWN");
    }

    /**
     * 作答路径的「先到站」聚合拒绝:errorCode 仍是既有 ARRIVE_FIRST,细分原因放 reasonCode。
     */
    public static AjaxResult arriveFirst(String message, String reasonCode)
    {
        return rejected(message, ARRIVE_FIRST).put("reasonCode", reasonCode);
    }

    /**
     * 动作号已被另一份输入占用:必须换新动作号。此时原回执仍是原输入的终态,不可覆盖或复用。
     */
    public static AjaxResult actionReused(String message)
    {
        // 不能复用 conflict():它带 intentNotExecuted=true。指纹冲突说明这个动作号已属于**另一份输入**,
        // 原请求是否落地不可知,声称「未执行」就是把未知说成事实。这里只给可执行指引。
        return new AjaxResult(409, message)
                .put("errorCode", INTENT_ACTION_REUSED)
                .put("retryable", false)
                .put("refreshRequired", false)
                .put("newActionIdRequired", true);
    }

    /**
     * 已写入终态回执后的非成功回包:该动作号已占用,重试必须换新的动作标识。
     */
    public static AjaxResult terminalFailure(AjaxResult response)
    {
        return response.put("intentTerminal", true).put("newActionIdRequired", true);
    }
}

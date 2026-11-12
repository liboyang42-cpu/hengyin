package com.chengyinhub.web.metrics;

/**
 * OPS-01 固定路由。标签值一经发布不得改名或复用；新增路由必须同步
 * GameSloMetrics 的固定序列与 GameSloProjection 的读法。
 */
public enum GameSloRoute
{
    /** 高级玩法启动 / 恢复：POST /api/play/advanced/start（含 teamId 的组队启动）。 */
    ADVANCED_START("advanced_start", Family.START),
    /** 城市定向开局：POST /api/play/orient/session/start。 */
    ORIENT_START("orient_start", Family.START),
    /** 故事开局流程：POST /api/play/journey/opening/complete。 */
    OPENING_COMPLETE("opening_complete", Family.START),
    /** 到达判定：POST /api/play/arrive。 */
    ARRIVE("arrive", Family.ARRIVE),
    /** 微信支付回调：POST /api/registration/callback。 */
    PAY_CALLBACK("pay_callback", Family.PAY),
    /** 完局发奖幂等冲突（布尔计数，不参与 HTTP 成功率）。 */
    REWARD_GRANT("reward_grant", Family.REWARD);

    public enum Family
    {
        START, ARRIVE, PAY, REWARD
    }

    private final String tag;
    private final Family family;

    GameSloRoute(String tag, Family family)
    {
        this.tag = tag;
        this.family = family;
    }

    public String tag()
    {
        return tag;
    }

    public Family family()
    {
        return family;
    }
}

package com.chengyinhub.web.metrics;

/**
 * OPS-01 固定结果标签。
 *
 * <p>suCcess 只给真实业务成功；HTTP 200 + AjaxResult 业务失败一律不是 success。
 * rejected 是带稳定业务码的拒绝（用户可改输入/先满足前置），fault 是服务端未能完成。
 * 两者都不是成功，但告警只看 fault，避免把正常用户拒绝当成故障。
 */
public enum GameSloResult
{
    SUCCESS("success"),
    REJECTED("rejected"),
    FAULT("fault");

    private final String tag;

    GameSloResult(String tag)
    {
        this.tag = tag;
    }

    public String tag()
    {
        return tag;
    }
}

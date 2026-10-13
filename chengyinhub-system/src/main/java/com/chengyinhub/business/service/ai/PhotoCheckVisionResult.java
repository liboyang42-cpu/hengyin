package com.chengyinhub.business.service.ai;

/**
 * 拍照审核的视觉判定结果:模型的 {pass, confidence, reason},或「拿不到结论」(degraded)。
 *
 * <p>degraded=true 时 pass 恒为 false —— 拿不到结论绝不等于通过,
 * 该不该放行由玩法段的 fallback 决定(见 AdvancedGameRuntimeServiceImpl.submitPhotoCheck)。
 *
 * @author chengyinhub
 */
public class PhotoCheckVisionResult
{
    private final boolean pass;
    private final int confidence;
    private final String reason;
    private final boolean degraded;

    private PhotoCheckVisionResult(boolean pass, int confidence, String reason, boolean degraded)
    {
        this.pass = pass;
        this.confidence = confidence;
        this.reason = reason == null ? "" : reason;
        this.degraded = degraded;
    }

    /** 模型给出了结论。 */
    public static PhotoCheckVisionResult concluded(boolean pass, int confidence, String reason)
    {
        return new PhotoCheckVisionResult(pass, confidence, reason, false);
    }

    /** key 未注入 / 超时 / 限流 / 解析失败 —— 拿不到结论。 */
    public static PhotoCheckVisionResult degraded(String reason)
    {
        return new PhotoCheckVisionResult(false, 0, reason, true);
    }

    public boolean isPass()
    {
        return pass;
    }

    public int getConfidence()
    {
        return confidence;
    }

    public String getReason()
    {
        return reason;
    }

    public boolean isDegraded()
    {
        return degraded;
    }
}

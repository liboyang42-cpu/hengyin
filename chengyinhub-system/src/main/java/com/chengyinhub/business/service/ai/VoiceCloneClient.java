package com.chengyinhub.business.service.ai;

/**
 * 声音复刻与语音合成。
 *
 * 抽成接口是为了能测:真实实现要打外网,单测里换成假的就能验完整条业务链路
 * (鉴权、频控、状态流转),而不必依赖 key 和网络。
 *
 * 两个方法都只抛 {@link AiServiceException} —— 上层需要区分「没开通」「配额用尽」
 * 「上游挂了」这三种,它们对用户是三句不同的话。把 provider 的原始异常直接往外扔,
 * 上层只能笼统地报「失败」,用户永远不知道该等一会还是该去开通。
 *
 * @author chengyinhub
 */
public interface VoiceCloneClient
{
    /**
     * 用一段录音创建音色。
     *
     * @param audioUrl 录音的可公开访问地址。百炼是拉取式的 —— 它自己去下载这个 URL,
     *                 所以内网地址、需要签名的私有地址都取不到,会得到一个含糊的失败。
     * @return 音色 id
     */
    String enroll(String audioUrl) throws AiServiceException;

    /**
     * 查音色当前状态。创建完不是立刻可用,中间有部署过程。
     *
     * @return true 表示已就绪可用于合成
     */
    boolean isReady(String voiceId) throws AiServiceException;

    /**
     * 合成一段语音。
     *
     * @return 音频地址。注意这是有时效的临时地址(官方 24 小时),
     *         **不要当成永久 URL 存进业务表** —— 存了就会在第二天变成一堆死链。
     */
    String synthesize(String voiceId, String text) throws AiServiceException;

    /** 是否真的可用(开关开着且 key/空间 id 都配齐)。 */
    boolean usable();
}

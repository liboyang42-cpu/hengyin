package com.chengyinhub.business.service.ai;

/**
 * 语音转文字。门店分身的语音提问要先过这一层,再走和打字完全相同的那条链路。
 *
 * <p><b>为什么单独抽一层</b>:仓里没有任何 ASR 能力(AiGatewayService 只有文本
 * {@code generate(system, user)}),接哪家是一个还没做的选型 + 一份还没有的密钥。
 * 把它抽成接口,是为了让「缺的到底是什么」有名字:除了这一个实现,
 * 语音链路的其余部分(录音、上传、归属校验、幂等、内容安全、计费、落库)全都已经就位。
 *
 * <p>⚠️ 实现方必须遵守两条:
 * <ol>
 *   <li><b>音频不落盘</b>。片段只在内存里转成文字,文字进 npc_chat_log(已有导出/删除端点覆盖);
 *       把音频存下来会凭空多出一份没人管的声纹数据。</li>
 *   <li><b>识别不出来要返回 null,不要返回空串或猜测</b>。上层据此回一句「没听清」,
 *       而不是拿一个空问题去调模型 —— 那是白花钱且必然答非所问。</li>
 * </ol>
 */
public interface SpeechToTextService
{
    /**
     * 这一层有没有被配置好。false 时上层直接拒绝,<b>不扣额度、不调模型</b>。
     */
    boolean available();

    /**
     * @param audio  音频字节(小程序 MP3 或 App M4A/AAC；入口限制为 2MB)
     * @param format 服务端按字节内容识别出的受控格式："mp3" / "m4a" / "aac" / "wav"
     *               解码或服务异常必须抛出；null 只表示音频里确实没有识别出文字。
     * @return 识别文本;识别不出返回 null
     */
    String transcribe(byte[] audio, String format);
}

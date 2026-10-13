package com.chengyinhub.business.service.ai.impl;

import com.chengyinhub.business.service.ai.SpeechToTextService;

/**
 * 未接入 ASR 时的默认实现:永远说「我没配好」。
 *
 * <p>这不是占位,是**故意的失败关闭**:{@link #available()} 恒 false,
 * 上层据此在进模型之前就拒绝,不扣额度、不产生费用、不落一条假的对话记录。
 *
 * <p>真接了哪家 ASR,就写一个 {@link SpeechToTextService} 的 @Service 实现 ——
 * 本类由 {@link SpeechToTextConfig} 兜底注册,有真实现时就不再注册,调用方不用改。
 *
 * <p>⚠️ 本类**故意不带 @Service**:让位那件事必须由 @Bean 方法上的
 * {@code @ConditionalOnMissingBean} 来判。写在被组件扫描的 @Service 上不成立 ——
 * 那个注解只在 @Bean 方法/自动配置类里有确定语义,扫描顺序不保证,
 * 真接 ASR 那天要么两个 bean 冲突起不来、要么让位失败继续恒 false。
 */
public class NoopSpeechToTextService implements SpeechToTextService
{
    @Override
    public boolean available()
    {
        return false;
    }

    @Override
    public String transcribe(byte[] audio, String format)
    {
        // available() 恒 false ⇒ 调用方不该走到这里;真走到了说明有人绕过了闸,让它响。
        throw new IllegalStateException("语音识别未接入:先实现 SpeechToTextService 再开语音提问");
    }
}

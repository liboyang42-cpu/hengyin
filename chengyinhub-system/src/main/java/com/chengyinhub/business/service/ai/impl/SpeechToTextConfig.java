package com.chengyinhub.business.service.ai.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.chengyinhub.business.service.ai.SpeechToTextService;

/**
 * 语音识别的兜底注册:容器里没有任何 {@link SpeechToTextService} 时,给一个恒 false 的实现。
 *
 * <p>为什么要单独一个 @Configuration:{@code @ConditionalOnMissingBean} 的可靠语义只在
 * @Bean 方法(与自动配置类)上成立 —— 它在这里是**在其它 bean 定义都注册完之后**才判定的。
 * 写在被组件扫描的 @Service 上则取决于扫描顺序,那是个兑现不了的承诺。
 *
 * <p>所以真接了 ASR 之后,只要写一个 {@code @Service class XxxSpeechToTextService
 * implements SpeechToTextService},本方法就不再注册 Noop,调用方一行不用改。
 */
@Configuration
public class SpeechToTextConfig
{
    @Bean
    @ConditionalOnMissingBean(SpeechToTextService.class)
    public SpeechToTextService noopSpeechToTextService()
    {
        return new NoopSpeechToTextService();
    }
}

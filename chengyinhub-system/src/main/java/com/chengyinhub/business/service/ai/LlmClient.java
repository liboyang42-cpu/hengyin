package com.chengyinhub.business.service.ai;

/**
 * 大模型对话客户端（可切换:mock / qwen / deepseek）。
 *
 * 契约：
 *  - chat 同步返回模型纯文本；约定 systemPrompt 会要求模型「只输出 JSON 字符串」，
 *    上层 AiCreatorServiceImpl 负责把返回文本 JSON 解析成具体 VO。
 *  - systemPrompt 内含 [BIZ:xxx] 标记标明场景；userPrompt 是结构化上下文 JSON。
 *  - 实现选择由 AiModelConfig.provider 决定，mock 为本期默认(无需 key)。
 *
 * @author chengyinhub
 */
public interface LlmClient
{
    /** 实现名:mock / qwen / deepseek / openai */
    String name();

    /**
     * 同步对话。
     *
     * @param systemPrompt 角色设定 + 输出 JSON 模式 + [BIZ:xxx] 标记
     * @param userPrompt   结构化上下文 JSON
     * @return 模型输出文本(约定为 JSON 字符串)
     */
    String chat(String systemPrompt, String userPrompt);

    /**
     * 带图对话(OpenAI vision 格式):messages[].content 为数组,[{type:"image_url",...},{type:"text",...}]。
     * 默认实现不支持视觉;支持视觉的实现(OpenAiCompatibleLlmClient)覆盖。
     *
     * @param systemPrompt 角色设定 + 输出要求
     * @param imageUrl     公网图片 URL 或 data:image/jpeg;base64,...
     * @param userPrompt   文字上下文
     * @return 模型输出文本(约定为 JSON 字符串)
     */
    default String chatVision(String systemPrompt, String imageUrl, String userPrompt)
    {
        throw new UnsupportedOperationException(name() + " 不支持视觉调用");
    }

    /**
     * 流式对话(SSE):逐 chunk 回调 consumer。
     * 默认实现: 调用 chat() 后整段回调(兼容无流式能力的模型)。
     */
    default void streamChat(String systemPrompt, String userPrompt, java.util.function.Consumer<String> onDelta)
    {
        String full = chat(systemPrompt, userPrompt);
        if (full != null)
        {
            // 模拟逐字: 每次吐出 1~3 个字符
            for (int i = 0; i < full.length(); i += Math.min(3, full.length() - i))
            {
                int end = Math.min(i + 3, full.length());
                onDelta.accept(full.substring(i, end));
                try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }
}

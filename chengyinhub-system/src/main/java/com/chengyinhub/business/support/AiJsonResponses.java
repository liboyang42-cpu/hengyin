package com.chengyinhub.business.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;

/**
 * AI 响应 JSON 解析的共用实现(B:P2-041)。原先 AiCreatorServiceImpl 与 AiTopicPlanServiceImpl
 * 各抄了一份 stripJsonFence / parseAiJson / PARSE_ERROR_MSG,行为相同但改一处易漏另一处。
 */
public final class AiJsonResponses
{
    /** 模型输出无法解析时给用户的统一文案。 */
    public static final String PARSE_ERROR_MSG = "AI 响应格式异常,请重试";

    private AiJsonResponses()
    {
    }

    /**
     * 剥离 LLM 可能包上的 markdown 代码块围栏(```json ... ``` 或 ``` ... ```)。
     * prompt 里只是「请求」模型不要包,模型仍可能包;不剥离则 fastjson2 必抛 JSONException。
     * 无围栏时原样(trim 后)返回;null 返回 null。
     */
    public static String stripJsonFence(String text)
    {
        if (text == null) return null;
        String s = text.trim();
        if (!s.startsWith("```")) return s;
        // 跳过首行围栏(首行可能带语言标注,如 ```json)
        int nl = s.indexOf('\n');
        if (nl < 0) return s;
        s = s.substring(nl + 1);
        int close = s.lastIndexOf("```");
        if (close >= 0) s = s.substring(0, close);
        return s.trim();
    }

    /**
     * 统一的 AI 响应 JSON 解析:先剥围栏再解析;text 为 null/空 或解析结果为 null 时抛
     * JSONException,让调用方复用既有的 catch 降级分支(否则后续 json.getXxx() 会抛 NPE 冒泡 500)。
     */
    public static JSONObject parseAiJson(String text)
    {
        JSONObject json = JSON.parseObject(stripJsonFence(text));
        if (json == null)
        {
            throw new JSONException("empty ai response");
        }
        return json;
    }
}

package com.chengyinhub.business.service.impl;

import org.springframework.stereotype.Service;

/**
 * NPC 降级兜底话术:模型挂了/限流时用,保证体验不崩。
 *
 * @author chengyinhub
 */
@Service
public class NpcFallbackProvider
{
    private static final String[] FALLBACKS = {
        "嗯...让我想想。",
        "这个问题有点意思。",
        "抱歉,我暂时有点走神了——不过没关系,我们继续往前走吧。",
        "嘿,先别管这个了,看看周围——下一个线索说不定就在你眼前。"
    };

    /**
     * 取一条降级兜底话术(轮转)。
     */
    public String line(Long profileId)
    {
        int idx = (int) (System.currentTimeMillis() % FALLBACKS.length);
        return FALLBACKS[idx];
    }
}

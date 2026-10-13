package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.chengyinhub.business.domain.NpcLineCache;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.vo.ai.NpcLineVO;
import com.chengyinhub.business.mapper.NpcLineCacheMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;

/**
 * NPC 事件冒泡话术服务(查表随机取变体,不调模型,<=200ms)。
 *
 * @author chengyinhub
 */
@Service
public class NpcEventService
{
    private static final Logger log = LoggerFactory.getLogger(NpcEventService.class);
    private final Random rand = new Random();

    @Autowired
    private NpcLineCacheMapper npcLineCacheMapper;

    @Autowired
    private NpcProfileMapper npcProfileMapper;

    /**
     * 取事件冒泡话术。优先按 node 取,无则用通用话术;cacheOnly=false 时再无则回 greeting。
     *
     * @param cacheOnly true = 缓存未命中返回空 line，且不携带人设字段。
     *
     * <p>为什么要这个开关:漫游 Event 轨靠「line 为空」判定「无预审话术 → 静默降级」。
     * 若未命中时回落 greeting,line 恒非空 → 静默分支永远走不到:种子没落库、或 eventType
     * 差一个字,用户就会被同一句开场白复读五次(每次还各自消耗配额、各自发 impression 埋点),
     * 而「话术全预审」这类验收断言抓不到它 —— greeting 本身也是预审的。
     *
     * <p>⚠️ cacheOnly 还会剥离 name/avatar，漫游提示层只接收已审核的短文本。
     */
    public NpcLineVO getEventLine(Long profileId, String eventType, Long nodeId, boolean cacheOnly)
    {
        List<NpcLineCache> caches = npcLineCacheMapper.selectByProfileAndEvent(profileId, eventType, nodeId);
        NpcProfile profile = npcProfileMapper.selectGlobalNpc(); // fallback: 用全局NPC兜底

        NpcLineVO vo = new NpcLineVO();
        vo.setProfileId(profileId);

        if (caches != null && !caches.isEmpty())
        {
            NpcLineCache cache = caches.get(0);
            try
            {
                JSONArray arr = JSON.parseArray(cache.getVariantsJson());
                if (arr != null && !arr.isEmpty())
                {
                    int idx = rand.nextInt(arr.size());
                    vo.setLine(arr.getString(idx));
                    if (!cacheOnly && profile != null)
                    {
                        vo.setName(profile.getName());
                        vo.setAvatar(profile.getAvatar());
                    }
                    return vo;
                }
            }
            catch (Exception e)
            {
                log.warn("[NpcEvent] parse variants_json failed profileId={} eventType={}", profileId, eventType, e);
            }
        }

        // 只查缓存:未命中 = 无预审话术。line 留空由调用方静默，且不暴露人设字段。
        if (cacheOnly)
        {
            vo.setLine("");
            return vo;
        }

        // 降级:用 NPC 的 greeting 兜底(cacheOnly=false 的原行为,逐字保留)
        if (profile != null && profile.getGreeting() != null)
        {
            vo.setLine(profile.getGreeting());
            vo.setName(profile.getName());
            vo.setAvatar(profile.getAvatar());
        }
        else
        {
            vo.setLine("");
        }
        return vo;
    }
}

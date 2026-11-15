package com.chengyinhub.business.service;

import java.util.Map;
import com.chengyinhub.business.domain.dto.ai.NpcDialogueReq;

/**
 * 主题 AI NPC 对话服务:校验登记态 relation.* / tag.* 状态键并用 CAS 写入本局 state。
 */
public interface INpcDialogueService
{
    Map<String, Object> dialogue(Long memberId, NpcDialogueReq req);
}

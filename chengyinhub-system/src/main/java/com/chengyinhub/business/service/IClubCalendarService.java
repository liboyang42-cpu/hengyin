package com.chengyinhub.business.service;

import java.util.Map;

/** 俱乐部月历场次只读服务(W10a #19)。 */
public interface IClubCalendarService {
    /** 单月最多返回的场次数;超出时 truncated=true,前端不得声称完整排期。 */
    int MAX_SESSIONS_PER_MONTH = 200;

    /**
     * 返回某俱乐部某月(yyyy-MM)的真实场次。可见性由调用方判定后传入:
     * includeUnpublished=true 仅限俱乐部治理者。
     *
     * @return {clubId, month, sessions[], truncated, updatedAt}
     * @throws IllegalArgumentException clubId 缺失或 month 不是 yyyy-MM
     */
    Map<String, Object> monthSessions(Long clubId, String month, boolean includeUnpublished);
}

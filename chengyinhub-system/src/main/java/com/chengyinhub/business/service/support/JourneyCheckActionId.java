package com.chengyinhub.business.service.support;

import java.util.regex.Pattern;

/**
 * 检定动作 id 的唯一定义(B5 掷骰 / 重掷 / 结算与叙事段读结算回执共用)。
 *
 * <p>此前 B5 的 {@code TopicRouteRuntimeServiceImpl#checkActionId} 与 NARRATIVE 的
 * {@code PlayEncounterServiceImpl#settledCheckSuccess} 各拼一份 {@code check:{nodeId}:{checkId}:{step}},
 * 两处一旦漂移就会出现「结算写了一个 id、叙事读另一个 id」——回执永远读不到、失败文案静默不生效。
 * 这里收成一处,两边都调用它。
 */
public final class JourneyCheckActionId {

    private static final Pattern CHECK_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** R22-SEC S12:合法最长值 = check:(6) + 19 位 nodeId + :(1) + 64 位 checkId + :(1) + settle(6) = 97。 */
    private static final int MAX_LENGTH = 97;

    /** B5 结算步骤名:叙事段只读这一步的回执。 */
    public static final String STEP_SETTLE = "settle";

    private JourneyCheckActionId() {
    }

    /** 检定某一步的动作 id;入参非法直接抛(与 B5 原有校验一字不差)。 */
    public static String of(Long nodeId, String checkId, String step) {
        if (nodeId == null || nodeId <= 0L) throw new IllegalArgumentException("nodeId 无效");
        String id = checkId == null ? "" : checkId.trim();
        if (!CHECK_ID.matcher(id).matches()) throw new IllegalArgumentException("checkId 无效");
        String actionId = "check:" + nodeId + ":" + id + ":" + step;
        if (actionId.length() > MAX_LENGTH) throw new IllegalArgumentException("检定标识过长");
        return actionId;
    }

    /** 结算动作 id:叙事段按它读本节点最近一次检定成败。 */
    public static String settle(Long nodeId, String checkId) {
        return of(nodeId, checkId, STEP_SETTLE);
    }
}

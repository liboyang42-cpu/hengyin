package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.NodeMerchantOverride;

/**
 * 某个主题节点的「承接方现场层」。
 *
 * <p>职责切分(2026-08-06 用户拍板):
 * <ul>
 *   <li><b>主办方</b>拥有玩法本身 —— 题目、答案、验证方式。节点是剧情的一环,
 *       前站产出可能咬合后站,商家改就断链,所以这些永远取节点模板。</li>
 *   <li><b>承接商家</b>拥有现场执行 —— 规则怎么讲、答对了跟玩家说什么、到店给什么券。
 *       这些本来就该是他自己的:是他的店、他的人力、他的成本。</li>
 * </ul>
 *
 * <p>为什么单独开一个服务而不是在各处直接查:同一份「谁承接了这一站」要被
 * 节点下发、答题反馈、发券三处用到,散着查迟早漂移(三处对"中标"的判据不一致就是资损)。
 * 判据在这里收口一次:{@code audit_status=1 且 del_flag=0}。
 */
public interface INodeMerchantOverrideService {

    /**
     * @param nodeId 主题节点
     * @return 该节点中标商家的现场层;没有中标商家、或商家没配任何一项时返回**空对象**(不是 null),
     *         调用方直接 {@code override.feedbackTextOr(tplValue)} 取值,不必判空
     */
    NodeMerchantOverride forNode(Long nodeId);
}

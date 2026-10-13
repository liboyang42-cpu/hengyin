package com.chengyinhub.business.service;

import java.util.Map;

/**
 * 项目主页聚合读。
 *
 * 同一条路线上的主办方和承接方落到同一个页面,关系(俱乐部/商家/玩家)挂在主题上。
 * 在此之前这些数据散在五个接口里(topic/info-to-merchant、registration/merchant/info、
 * coop/candidates、coop/list、merchant/upcoming-runs),前端要打五次才拼得出一屏。
 */
public interface IProjectHomeReadService {

    /**
     * @param memberId 当前登录用户
     * @param topicId  主题
     * @return role=host/join 及对应身份需要的数据;都不是则抛异常(浏览态走原有主题详情)
     */
    Map<String, Object> getProjectHome(Long memberId, Long topicId);
}

package com.chengyinhub.business.service;

/** 玩家个人微信小程序码签发与复用。 */
public interface IPlayerWxacodeService {

    /** 返回可由小程序 image 组件直接加载的小程序码 URL。 */
    String getOrCreate(Long memberId);

    /** 门口游戏码：scene 就是节点 checkin_code，落地 pages/index/index。 */
    String getOrCreateScene(String scene);
}

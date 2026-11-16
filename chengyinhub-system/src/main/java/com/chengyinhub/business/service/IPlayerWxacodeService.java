package com.chengyinhub.business.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 玩家个人微信小程序码签发与复用。 */
public interface IPlayerWxacodeService {

    /**
     * ONB-5:与小程序码同一 scene 的标准链接。印成普通二维码后,微信(「扫普通链接二维码打开小程序」)、
     * iOS 相机和 App 内「扫一扫」都能打开同一页。App 的 universal link 已认领 /door。
     */
    String DOOR_LINK_BASE = "https://www.chengyinhub.com/door?scene=";

    /** 返回可由小程序 image 组件直接加载的小程序码 URL。 */
    String getOrCreate(Long memberId);

    /** 门口游戏码：scene 就是节点 checkin_code，落地 pages/index/index。 */
    String getOrCreateScene(String scene);

    /** 标准链接:scene 为空返回 null。不调用微信,不占配额。 */
    static String linkFor(String scene) {
        if (scene == null || scene.trim().isEmpty()) {
            return null;
        }
        return DOOR_LINK_BASE + URLEncoder.encode(scene.trim(), StandardCharsets.UTF_8);
    }
}

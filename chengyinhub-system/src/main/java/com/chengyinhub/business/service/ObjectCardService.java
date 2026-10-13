package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PlayerObjectCard;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 立体藏品卡(拍物成卡)的读写面。
 *
 * <p>只有三件事:铸一张、读自己的册子、给自己的卡改名。没有删除、没有分享、没有别人的视角 ——
 * 这一版的契约就这么大(见 docs/design 与施工验收单 §10)。
 */
public interface ObjectCardService {

    /** 来源:玩法节点。商家侧 L1 还没开工,所以今天只有这一种来源。 */
    String ORIGIN_PLAY = "PLAY";

    /**
     * 铸卡:玩家这一关真的判过之后调用。
     *
     * @param memberId   登录态里的卡主,永不来自客户端
     * @param sourceUrl  玩家那张照片(本站上传链产出的地址),外域地址直接拒绝
     * @param photoCheck 该节点 photoCheck 配置段(cardTitle / cardStyle / title / shotNote)
     * @param originType {@link #ORIGIN_PLAY}
     * @param originId   来源标识(玩法时是节点 id 字符串),同一来源覆盖同一行
     * @throws IllegalArgumentException 照片地址不是自家的 / 来源不认识
     */
    PlayerObjectCard mint(Long memberId, String sourceUrl, JsonNode photoCheck, String originType, String originId);

    /** @param category 只看这一类;null = 全部 */
    List<PlayerObjectCard> list(Long memberId, String category, int offset, int limit);

    int count(Long memberId, String category);

    /** 改自己卡的名字。返回 null = 这张卡不是你的(或不存在),调用方必须报错而不是回一份别人的数据。 */
    PlayerObjectCard rename(Long memberId, Long cardId, String title);
}

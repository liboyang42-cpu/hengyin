package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayerObjectCard;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 立体藏品卡 Mapper。
 *
 * <p>每一条读写都带 member_id —— 这不是防御性写法:卡是玩家私有的,
 * 少一条 WHERE 就是任何人都能按 id 读走、改掉别人的藏品。
 */
public interface PlayerObjectCardMapper {

    /**
     * 铸卡 = 一条 upsert。覆盖语义完全由 uk_member_card_origin 决定,
     * 不做「先查再插」—— 那种写法在两局并发重拍同一节点时会多写一行。
     */
    int upsert(PlayerObjectCard card);

    /** 我的收藏册:按铸卡时间倒序分页。 */
    List<PlayerObjectCard> selectByMember(@Param("memberId") Long memberId,
                                          @Param("category") String category,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit);

    int countByMember(@Param("memberId") Long memberId, @Param("category") String category);

    /** 改卡名。WHERE 同时带 id 与 member_id:归属不匹配时返回 0 行,调用方按「不是你的」处理。 */
    int renameOwned(@Param("id") Long id, @Param("memberId") Long memberId, @Param("title") String title);

    /** 改名后回读那一张给前端。同样只认「我的那一张」。 */
    PlayerObjectCard selectOwnedById(@Param("id") Long id, @Param("memberId") Long memberId);
}

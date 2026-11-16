package com.chengyinhub.business.clubinvite.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * W7 SOC-15: {@code club_invite} (see {@code sql/migration_20261129_club_invite.sql}) plus the club card the
 * landing shows. Uses are taken with one guarded UPDATE so two people cannot both take the last use.
 */
public interface ClubInviteMapper
{
    String COLS = "id, club_id AS clubId, channel_id AS channelId, code, creator_member_id AS creatorMemberId, "
            + "max_uses AS maxUses, uses, expires_at AS expiresAt, revoked_at AS revokedAt, create_time AS createTime";

    @Insert("INSERT INTO club_invite (club_id, channel_id, code, creator_member_id, max_uses, uses, expires_at, create_time) "
            + "VALUES (#{clubId}, #{channelId}, #{code}, #{creatorMemberId}, #{maxUses}, 0, #{expiresAt}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertInvite(Map<String, Object> row);

    @Select("SELECT " + COLS + " FROM club_invite WHERE code = #{code}")
    Map<String, Object> selectByCode(@Param("code") String code);

    @Select("SELECT " + COLS + " FROM club_invite WHERE club_id = #{clubId} ORDER BY id DESC LIMIT #{limit}")
    List<Map<String, Object>> selectByClub(@Param("clubId") long clubId, @Param("limit") int limit);

    /** Takes one use while the invite is live; 0 rows = expired, used up or revoked (or a race lost on the last use). */
    @Update("UPDATE club_invite SET uses = uses + 1 WHERE id = #{id} AND revoked_at IS NULL "
            + "AND (expires_at IS NULL OR expires_at > #{now}) AND (max_uses IS NULL OR uses < max_uses)")
    int takeUse(@Param("id") long id, @Param("now") Date now);

    /** Gives a use back when the join it was taken for did not go through. */
    @Update("UPDATE club_invite SET uses = uses - 1 WHERE id = #{id} AND uses > 0")
    int returnUse(@Param("id") long id);

    @Update("UPDATE club_invite SET revoked_at = NOW() WHERE id = #{id} AND revoked_at IS NULL")
    int revoke(@Param("id") long id);

    @Select("SELECT id, name, logo, status, del_flag AS delFlag FROM club WHERE id = #{clubId}")
    Map<String, Object> selectClubCard(@Param("clubId") long clubId);
}

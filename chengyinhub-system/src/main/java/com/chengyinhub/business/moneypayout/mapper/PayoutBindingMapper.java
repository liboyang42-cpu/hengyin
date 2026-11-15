package com.chengyinhub.business.moneypayout.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.Map;

/**
 * Binding codes ({@code cy_payout_bind_code}) and the wallet payout identity ({@code cy_member_payout_wechat}).
 * Only the sha256 of a code is stored. State changes are compare-and-set on the current status.
 */
public interface PayoutBindingMapper {

    String CODE_COLUMNS = "id, purpose, member_id as memberId, club_id as clubId, status, claimed_openid as claimedOpenid, "
            + "expires_at as expiresAt, claimed_at as claimedAt, confirmed_at as confirmedAt, create_time as createTime";

    @Update("update cy_payout_bind_code set status = 'CANCELLED' "
            + "where member_id = #{memberId} and purpose = #{purpose} and status in ('ISSUED', 'CLAIMED')")
    int cancelOpen(@Param("memberId") Long memberId, @Param("purpose") String purpose);

    @Insert("insert into cy_payout_bind_code (code_hash, purpose, member_id, club_id, status, expires_at, create_time) "
            + "values (#{hash}, #{purpose}, #{memberId}, #{clubId}, 'ISSUED', #{expiresAt}, now())")
    int insert(@Param("hash") String hash, @Param("purpose") String purpose, @Param("memberId") Long memberId,
               @Param("clubId") Long clubId, @Param("expiresAt") Date expiresAt);

    @Select("select " + CODE_COLUMNS + " from cy_payout_bind_code where code_hash = #{hash} for update")
    Map<String, Object> selectByHashForUpdate(@Param("hash") String hash);

    @Select("select " + CODE_COLUMNS + " from cy_payout_bind_code where code_hash = #{hash}")
    Map<String, Object> selectByHash(@Param("hash") String hash);

    @Select("select " + CODE_COLUMNS + " from cy_payout_bind_code where member_id = #{memberId} and purpose = #{purpose} "
            + "and club_id = #{clubId} order by id desc limit 1")
    Map<String, Object> selectLatest(@Param("memberId") Long memberId, @Param("purpose") String purpose,
                                     @Param("clubId") Long clubId);

    @Update("update cy_payout_bind_code set status = 'CLAIMED', claimed_openid = #{openid}, claimed_at = now() "
            + "where id = #{id} and status = 'ISSUED' and expires_at > now()")
    int claim(@Param("id") Long id, @Param("openid") String openid);

    @Update("update cy_payout_bind_code set status = 'CONFIRMED', confirmed_at = now() "
            + "where id = #{id} and member_id = #{memberId} and status = 'CLAIMED' and expires_at > now()")
    int confirm(@Param("id") Long id, @Param("memberId") Long memberId);

    @Select("select member_id as memberId, appid, openid, bound_at as boundAt from cy_member_payout_wechat "
            + "where member_id = #{memberId}")
    Map<String, Object> selectWallet(@Param("memberId") Long memberId);

    @Insert("insert into cy_member_payout_wechat (member_id, appid, openid, bound_at) values (#{memberId}, #{appid}, #{openid}, now()) "
            + "on duplicate key update appid = values(appid), openid = values(openid), bound_at = values(bound_at)")
    int upsertWallet(@Param("memberId") Long memberId, @Param("appid") String appid, @Param("openid") String openid);
}

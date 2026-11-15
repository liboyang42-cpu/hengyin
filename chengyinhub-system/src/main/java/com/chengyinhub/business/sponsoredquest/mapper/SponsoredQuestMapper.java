package com.chengyinhub.business.sponsoredquest.mapper;

import com.chengyinhub.business.sponsoredquest.SponsoredQuestCampaign;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;
import java.util.Map;

/**
 * S3b sponsored quests ({@code sql/migration_20261115_sponsored_quests.sql}). Read only by the sponsored-quest services:
 * the fog map, roam pin ranking and search never join these tables (guarded by {@code SponsoredQuestIsolationGuardTest}).
 */
public interface SponsoredQuestMapper {

    /** Same player gate as the roam place read: published, and merchant applications only once approved. */
    String PLACE_GATE = "p.status = 1 and (p.application_type is null or p.application_type = 2"
            + " or (p.application_type in (1, 3) and p.audit_status = 1))";

    @Select("select c.id, c.sponsor_name as sponsorName, c.title, c.content, c.cover_img as coverImg,"
            + " c.start_time as startTime, c.end_time as endTime"
            + " from sponsored_quest_campaign c"
            + " where c.status = 1 and c.start_time <= now() and c.end_time > now()"
            + " and (c.budget_cent = 0 or c.spent_cent < c.budget_cent)"
            + " and exists (select 1 from sponsored_quest_place qp join roam_poi p on p.id = qp.poi_id"
            + "   where qp.campaign_id = c.id and " + PLACE_GATE
            + "   and p.lat between #{minLat} and #{maxLat} and p.lng between #{minLng} and #{maxLng})"
            + " order by c.id desc limit #{limit}")
    List<Map<String, Object>> selectLiveInBox(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                              @Param("minLng") double minLng, @Param("maxLng") double maxLng,
                                              @Param("limit") int limit);

    @Select("<script>select qp.campaign_id as campaignId, p.id as poiId, p.name, p.lat, p.lng"
            + " from sponsored_quest_place qp join roam_poi p on p.id = qp.poi_id"
            + " where " + PLACE_GATE + " and qp.campaign_id in"
            + " <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " order by qp.campaign_id, qp.sort, p.id</script>")
    List<Map<String, Object>> selectLivePlaces(@Param("ids") List<Long> campaignIds);

    @Select("select c.id, c.sponsor_name as sponsorName, c.sponsor_merchant_id as sponsorMerchantId, c.title, c.content,"
            + " c.cover_img as coverImg, c.budget_cent as budgetCent, c.spent_cent as spentCent,"
            + " c.start_time as startTime, c.end_time as endTime, c.status,"
            + " c.create_by as createBy, c.create_time as createTime, c.update_by as updateBy, c.update_time as updateTime,"
            + " (select count(*) from sponsored_quest_place qp where qp.campaign_id = c.id) as placeCount"
            + " from sponsored_quest_campaign c order by c.id desc limit #{limit}")
    List<Map<String, Object>> selectAdminList(@Param("limit") int limit);

    @Select("select c.id, c.sponsor_name as sponsorName, c.sponsor_merchant_id as sponsorMerchantId, c.title, c.content,"
            + " c.cover_img as coverImg, c.budget_cent as budgetCent, c.spent_cent as spentCent,"
            + " c.start_time as startTime, c.end_time as endTime, c.status"
            + " from sponsored_quest_campaign c where c.id = #{id}")
    Map<String, Object> selectById(@Param("id") Long id);

    @Select("select c.id, c.budget_cent as budgetCent, c.spent_cent as spentCent, c.status"
            + " from sponsored_quest_campaign c where c.id = #{id} for update")
    Map<String, Object> selectForUpdate(@Param("id") Long id);

    @Select("select poi_id from sponsored_quest_place where campaign_id = #{id} order by sort, poi_id")
    List<Long> selectPlaceIds(@Param("id") Long id);

    @Select("<script>select count(*) from roam_poi p where " + PLACE_GATE + " and p.id in"
            + " <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int countPublishedPlaces(@Param("ids") List<Long> ids);

    @Insert("insert into sponsored_quest_campaign (sponsor_name, sponsor_merchant_id, title, content, cover_img,"
            + " budget_cent, spent_cent, start_time, end_time, status, create_by, create_time)"
            + " values (#{c.sponsorName}, #{c.sponsorMerchantId}, #{c.title}, #{c.content}, #{c.coverImg},"
            + " #{c.budgetCent}, 0, #{c.startTime}, #{c.endTime}, 0, #{by}, now())")
    @Options(useGeneratedKeys = true, keyProperty = "c.id", keyColumn = "id")
    int insertCampaign(@Param("c") SponsoredQuestCampaign campaign, @Param("by") String by);

    @Update("update sponsored_quest_campaign set sponsor_name = #{c.sponsorName}, sponsor_merchant_id = #{c.sponsorMerchantId},"
            + " title = #{c.title}, content = #{c.content}, cover_img = #{c.coverImg}, budget_cent = #{c.budgetCent},"
            + " start_time = #{c.startTime}, end_time = #{c.endTime}, update_by = #{by}, update_time = now()"
            + " where id = #{c.id} and status in (0, 1, 2)")
    int updateCampaign(@Param("c") SponsoredQuestCampaign campaign, @Param("by") String by);

    @Update("update sponsored_quest_campaign set status = #{status}, update_by = #{by}, update_time = now()"
            + " where id = #{id} and status = #{from}")
    int updateStatus(@Param("id") Long id, @Param("from") int from, @Param("status") int status, @Param("by") String by);

    @Delete("delete from sponsored_quest_place where campaign_id = #{id}")
    int deletePlaces(@Param("id") Long id);

    @Insert("insert into sponsored_quest_place (campaign_id, poi_id, sort) values (#{id}, #{poiId}, #{sort})")
    int insertPlace(@Param("id") Long id, @Param("poiId") Long poiId, @Param("sort") int sort);

    @Insert("insert ignore into sponsored_quest_charge (campaign_id, ref_key, amount_cent, reason, create_by, create_time)"
            + " values (#{id}, #{refKey}, #{amount}, #{reason}, #{by}, now())")
    int insertChargeIgnore(@Param("id") Long id, @Param("refKey") String refKey, @Param("amount") long amount,
                           @Param("reason") String reason, @Param("by") String by);

    @Select("select campaign_id from sponsored_quest_charge where ref_key = #{refKey}")
    Long selectChargeCampaign(@Param("refKey") String refKey);

    @Update("update sponsored_quest_campaign set spent_cent = spent_cent + #{amount}"
            + " where id = #{id} and #{amount} > 0 and spent_cent + #{amount} <= budget_cent")
    int addSpentWithinBudget(@Param("id") Long id, @Param("amount") long amount);

    @Select("select id, campaign_id as campaignId, ref_key as refKey, amount_cent as amountCent, reason,"
            + " create_by as createBy, create_time as createTime"
            + " from sponsored_quest_charge where campaign_id = #{id} order by id desc limit #{limit}")
    List<Map<String, Object>> selectCharges(@Param("id") Long id, @Param("limit") int limit);
}
